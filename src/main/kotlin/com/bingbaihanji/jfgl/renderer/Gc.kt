package com.bingbaihanji.jfgl.renderer

import com.bingbaihanji.jfgl.geom.Flattener
import com.bingbaihanji.jfgl.geom.Path
import com.bingbaihanji.jfgl.geom.StrokeGenerator
import com.bingbaihanji.jfgl.geom.Tessellator
import com.bingbaihanji.jfgl.math.Mat3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 立即模式的 2D 绘制上下文，对外提供类似 Canvas 的 API。
 *
 * <p>所有绘制调用只往顶点缓冲追加数据，**不做任何 GL 调用**；真正提交发生在 [endFrame]。
 * 因此整个 `onRender { }` 回调期间没有任何一次 GL 状态切换，
 * 整帧的 draw call 数量取决于状态切换次数（见 [VertexWriter] 的合批规则）。
 *
 * <p>坐标系统为**像素、原点左上、y 向下**，与 HTML Canvas 一致：
 * `translate`/`scale`/`rotate` 的角度以度为单位，正值在屏幕上是顺时针。
 *
 * ## 一帧的生命周期
 * ```
 * gc.beginFrame(w, h)   // 重置变换/裁剪/样式栈与顶点写入器，设置视口高度
 * gc.fillRect(...)      // 尽可能多地记录图元
 * gc.endFrame()         // 一次性提交；缓冲区中途写满时会自动提交一次再继续
 * ```
 * [beginFrame] 与 [endFrame] 必须严格配对：重复 [beginFrame]、或未经 [beginFrame] 就 [endFrame]
 * 都会抛出消息说明问题的 [IllegalStateException]，而不是安静地画出错误结果。
 *
 * ## 线程
 * 本类**不是线程安全的**，且必须在 GL 线程上使用（[RenderBatch] 只允许在 GL 线程调用）。
 *
 * ## 坐标数学在哪里
 * 像素→NDC 的基础矩阵、变换栈、裁剪栈及全部单位换算都在 [ViewTransform] 里，
 * 那是一个不依赖 GL 的纯数学类，可以脱离窗口单测；本类只负责状态管理、几何准备（平坦化/三角化/描边）
 * 与逐图元发射。
 *
 * ## 几何在哪里
 * 曲线平坦化、填充三角化、描边轮廓生成都在 [com.bingbaihanji.jfgl.geom] 包里，
 * 那是另一个零 GL 依赖、可脱离窗口单测的包。本类只按当前变换的缩放因子
 * （见 [matrixScale]）告诉它们"一个用户单位等于多少设备像素"，然后把结果按 [currentMatrix]
 * 烘焙成 NDC 顶点。
 *
 * ## 一帧内的多次提交
 * 顶点缓冲写满时 [VertexWriter] 会置位 flush 请求，本类在**每个图元之前**检查并
 * 中途提交一次（见 [flushIfNeeded]）。因此单帧的 draw call 数仍是个位数级别，
 * 与图元数量无关。
 */
class Gc internal constructor(private val batch: RenderBatch) {

    /** 顶点收集器。整帧的绘制结果都在这里，[endFrame] 时才交给 [RenderBatch]。 */
    private val writer = VertexWriter(INITIAL_VERTEX_CAPACITY)

    /** 纯坐标状态：基础矩阵、变换栈、裁剪栈。 */
    private val state = ViewTransform()

    /** 当前填充样式（ARGB 打包）。 */
    var fill: Int = 0xFFFFFFFF.toInt()

    /** 当前描边样式（ARGB 打包）。 */
    var stroke: Int = 0xFF000000.toInt()

    /** 当前线宽（用户坐标单位）。 */
    var lineWidth: Float = 1f

    /** 全局不透明度（0-1），与样式颜色相乘。 */
    var globalAlpha: Float = 1f

    /** 样式栈的整数部分：每层 2 个值（fill、stroke）。 */
    private var styleInts = IntArray(INITIAL_STACK_LEVELS * 2)

    /** 样式栈的浮点部分：每层 2 个值（lineWidth、globalAlpha）。 */
    private var styleFloats = FloatArray(INITIAL_STACK_LEVELS * 2)

    /**
     * 样式栈层数。
     *
     * <p>必须与 [ViewTransform.stackDepth] 始终相等——[save] 与 [restore] 是唯一改动它们的地方，
     * 每次都是成对压入/弹出。之所以不像变换与裁剪那样塞进 [ViewTransform]：
     * 那些是坐标数学，样式不是；而拆成两个类以后，保持同步的成本仅仅是在同一对方法里各动一次。
     */
    private var styleDepth = 0

    /** 本帧是否处于 [beginFrame] 与 [endFrame] 之间。 */
    private var frameActive = false

    // ------------------------------------------------------------------
    // 帧
    // ------------------------------------------------------------------

    /**
     * 开始一帧：设定视口尺寸、把变换重置为像素→NDC 的基础矩阵、清空全部状态栈与顶点缓冲。
     *
     * @param width  帧缓冲宽度（像素），必须为正
     * @param height 帧缓冲高度（像素），必须为正
     * @throws IllegalStateException    上一帧还没有 [endFrame] 时
     * @throws IllegalArgumentException 宽或高不为正时
     */
    fun beginFrame(width: Int, height: Int) {
        check(!frameActive) { "beginFrame 已调用过：beginFrame 与 endFrame 必须配对，不能嵌套" }
        state.beginFrame(width, height)
        styleDepth = 0
        writer.reset()
        batch.setViewportHeight(height)
        frameActive = true
    }

    /**
     * 结束一帧并把本帧收集到的全部顶点一次性提交。
     *
     * <p>提交后 GL 状态回到中性（见 [RenderBatch.submit]），因此可以在下一帧直接继续画。
     *
     * <p>若本帧有 [save] 没有配对的 [restore]，本方法仍然会先提交这一帧（用户能看到画面），
     * 随后清空状态栈并抛出 [IllegalStateException]。选择抛而不是安静地兜底，
     * 是因为漏掉 [restore] 会让栈无限增长并污染后续每一帧，属于必须暴露的缺陷。
     *
     * @throws IllegalStateException 未经 [beginFrame] 就调用，或本帧 [save]/[restore] 不配对
     */
    fun endFrame() {
        check(frameActive) { "endFrame 在 beginFrame 之前调用：beginFrame 与 endFrame 必须配对" }
        batch.submit(writer)
        frameActive = false
        val unbalanced = state.clearStack()
        styleDepth = 0
        check(unbalanced == 0) { "save() 与 restore() 不配对：本帧结束时仍残留 $unbalanced 层 save()" }
    }

    // ------------------------------------------------------------------
    // 状态栈
    // ------------------------------------------------------------------

    /**
     * 压入当前的**全部绘制状态**：变换、裁剪、填充色、描边色、线宽、全局不透明度。
     *
     * <p>与 HTML Canvas / JavaFX 的 `save()` 语义一致——用户改完样式再 [restore] 就能回到原样，
     * 不必手工记下每一个字段。每次 [save] 在稳态下不产生任何分配：
     * 变换压入的是引用，裁剪与样式压入的是预先分配的数组，裁剪的"无裁剪"用整个帧缓冲表示，
     * 不需要哨兵对象。
     */
    fun save() {
        state.save()
        ensureStyleCapacity(styleDepth + 1)
        val base = styleDepth * 2
        styleInts[base] = fill
        styleInts[base + 1] = stroke
        styleFloats[base] = lineWidth
        styleFloats[base + 1] = globalAlpha
        styleDepth++
    }

    /**
     * 弹出并恢复最近一次 [save] 的全部绘制状态。
     *
     * @throws IllegalStateException 没有配对的 [save] 时（此时状态不变）
     */
    fun restore() {
        check(styleDepth > 0) { "restore() 与 save() 不配对：当前栈为空，没有可恢复的状态" }
        state.restore()
        styleDepth--
        val base = styleDepth * 2
        fill = styleInts[base]
        stroke = styleInts[base + 1]
        lineWidth = styleFloats[base]
        globalAlpha = styleFloats[base + 1]
    }

    // ------------------------------------------------------------------
    // 变换
    // ------------------------------------------------------------------

    /**
     * 在当前变换之后追加平移（用户坐标单位）。
     *
     * @param tx 沿 x 轴的平移量
     * @param ty 沿 y 轴的平移量（y 向下）
     */
    fun translate(tx: Float, ty: Float) {
        state.translate(tx, ty)
    }

    /**
     * 在当前变换之后追加缩放。
     *
     * @param sx 沿 x 轴的缩放因子
     * @param sy 沿 y 轴的缩放因子
     */
    fun scale(sx: Float, sy: Float) {
        state.scale(sx, sy)
    }

    /**
     * 在当前变换之后追加旋转。**正值为屏幕上的顺时针**（与 HTML Canvas、JavaFX 一致）。
     *
     * @param degrees 旋转角度（度），正值为屏幕上的顺时针
     */
    fun rotate(degrees: Float) {
        state.rotate(degrees)
    }

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    /**
     * 用矩形裁剪后续绘制，矩形按**用户坐标**给出，与已有裁剪**求交**。
     *
     * <p>注意：底层使用 `glScissor`，实际生效的是**变换后包围盒**。
     * 若当前变换包含旋转，裁剪区域会是旋转后矩形的轴对齐包围盒，而非旋转矩形本身。
     *
     * @param x 矩形左上角 x（用户坐标）
     * @param y 矩形左上角 y（用户坐标，y 向下）
     * @param w 矩形宽度
     * @param h 矩形高度
     */
    fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        state.clipRect(x, y, w, h)
    }

    // ------------------------------------------------------------------
    // 形状
    // ------------------------------------------------------------------

    /**
     * 填充矩形，可选圆角。
     *
     * @param x      矩形左上角 x（用户坐标）
     * @param y      矩形左上角 y（用户坐标，y 向下）
     * @param w      矩形宽度
     * @param h      矩形高度
     * @param radius 圆角半径；0 表示直角，超过宽高一半时按一半截断
     */
    @JvmOverloads
    fun fillRect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        emitShape(rectOutline(x, y, w, h, radius), fill)
    }

    /**
     * 描边矩形，可选圆角。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param x      矩形左上角 x（用户坐标）
     * @param y      矩形左上角 y（用户坐标，y 向下）
     * @param w      矩形宽度
     * @param h      矩形高度
     * @param radius 圆角半径；0 表示直角，超过宽高一半时按一半截断
     */
    @JvmOverloads
    fun strokeRect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        strokeOutline(rectOutline(x, y, w, h, radius))
    }

    /**
     * 生成矩形的闭合轮廓（方向为屏幕上的顺时针，首尾不重复）。
     *
     * <p>圆角用每角 [CORNER_SEGMENTS] 段的折线圆弧逼近，半径超过宽高一半时按一半截断，
     * 因此 `radius` 传得再大也只会得到胶囊形，不会自交。
     *
     * @param x      左上角 x
     * @param y      左上角 y
     * @param w      宽度
     * @param h      高度
     * @param radius 圆角半径
     * @return 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     */
    private fun rectOutline(x: Float, y: Float, w: Float, h: Float, radius: Float): FloatArray {
        if (radius <= 0f) {
            return floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
        }
        val r = minOf(radius, w * 0.5f, h * 0.5f)
        val pts = ArrayList<Float>(4 * (CORNER_SEGMENTS + 1) * 2)
        // 从每个角的圆心出发，扫过 90°；四段拼接起来正好是顺时针一圈的圆角矩形轮廓
        fun arc(cx: Float, cy: Float, startDeg: Float) {
            for (i in 0..CORNER_SEGMENTS) {
                val a = Math.toRadians((startDeg + 90.0 * i / CORNER_SEGMENTS)).toFloat()
                pts.add(cx + r * cos(a))
                pts.add(cy + r * sin(a))
            }
        }
        arc(x + w - r, y + r, -90f)
        arc(x + w - r, y + h - r, 0f)
        arc(x + r, y + h - r, 90f)
        arc(x + r, y + r, 180f)
        return pts.toFloatArray()
    }

    /**
     * 填充圆。
     *
     * @param cx       圆心 x（用户坐标）
     * @param cy       圆心 y（用户坐标，y 向下）
     * @param radius   半径
     * @param segments 细分段数；0 表示按 [circleSegments] 依半径与当前缩放自适应
     */
    @JvmOverloads
    fun fillCircle(cx: Float, cy: Float, radius: Float, segments: Int = 0) {
        emitShape(circleOutline(cx, cy, radius, segments), fill)
    }

    /**
     * 描边圆。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param cx       圆心 x（用户坐标）
     * @param cy       圆心 y（用户坐标，y 向下）
     * @param radius   半径
     * @param segments 细分段数；0 表示按 [circleSegments] 依半径与当前缩放自适应
     */
    @JvmOverloads
    fun strokeCircle(cx: Float, cy: Float, radius: Float, segments: Int = 0) {
        strokeOutline(circleOutline(cx, cy, radius, segments))
    }

    /**
     * 生成圆的闭合轮廓。
     *
     * @param cx       圆心 x
     * @param cy       圆心 y
     * @param radius   半径
     * @param segments 细分段数；为 0 时按半径自适应
     * @return 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     */
    private fun circleOutline(cx: Float, cy: Float, radius: Float, segments: Int): FloatArray {
        val seg = if (segments > 0) segments else circleSegments(radius)
        val pts = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            pts[i * 2] = cx + radius * cos(a)
            pts[i * 2 + 1] = cy + radius * sin(a)
        }
        return pts
    }

    /**
     * 按半径自适应细分段数：既保证视觉平滑，又不至于在小圆上浪费顶点。
     *
     * <p>半径先经 [matrixScale] 换算成设备像素——放大 10 倍画出来的圆需要更多段，
     * 否则多边形的棱角会在屏幕上被肉眼看见。
     *
     * @param radius 半径（用户坐标）
     * @return 细分段数，限定在 `[MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS]` 内
     */
    private fun circleSegments(radius: Float): Int {
        val deviceRadius = abs(radius * matrixScale())
        return (deviceRadius * 0.7f).toInt().coerceIn(MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS)
    }

    /**
     * 填充椭圆。
     *
     * @param cx       中心 x（用户坐标）
     * @param cy       中心 y（用户坐标，y 向下）
     * @param rx       x 方向半径
     * @param ry       y 方向半径
     * @param segments 细分段数；0 表示按较大半径自适应
     */
    @JvmOverloads
    fun fillEllipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 0) {
        emitShape(ellipseOutline(cx, cy, rx, ry, segments), fill)
    }

    /**
     * 描边椭圆。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param cx       中心 x（用户坐标）
     * @param cy       中心 y（用户坐标，y 向下）
     * @param rx       x 方向半径
     * @param ry       y 方向半径
     * @param segments 细分段数；0 表示按较大半径自适应
     */
    @JvmOverloads
    fun strokeEllipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 0) {
        strokeOutline(ellipseOutline(cx, cy, rx, ry, segments))
    }

    /**
     * 生成椭圆的闭合轮廓。
     *
     * @param cx       中心 x
     * @param cy       中心 y
     * @param rx       x 方向半径
     * @param ry       y 方向半径
     * @param segments 细分段数；为 0 时按较大半径自适应
     * @return 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     */
    private fun ellipseOutline(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int): FloatArray {
        val seg = if (segments > 0) segments else circleSegments(maxOf(rx, ry))
        val pts = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            pts[i * 2] = cx + rx * cos(a)
            pts[i * 2 + 1] = cy + ry * sin(a)
        }
        return pts
    }

    /**
     * 绘制直线段。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * <p>与描边折线共用同一套轮廓生成，因此线宽同样按用户坐标单位解释，
     * 且端点样式为平头（BUTT）——与 Canvas 的 `lineCap="butt"` 一致。
     *
     * @param x1 起点 x（用户坐标）
     * @param y1 起点 y（用户坐标，y 向下）
     * @param x2 终点 x（用户坐标）
     * @param y2 终点 y（用户坐标，y 向下）
     */
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        // 复用 scratchPoints，避免每帧为一条线段现搭数组
        ensureScratch(4)
        scratchPoints[0] = x1
        scratchPoints[1] = y1
        scratchPoints[2] = x2
        scratchPoints[3] = y2
        strokeOutline(scratchPoints, 2, false)
    }

    /**
     * 填充多边形。顶点按给定顺序首尾相连，内部用耳切法三角化，因此**凹多边形也正确**。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`，至少 3 个点
     */
    fun fillPolygon(points: FloatArray) {
        emitShape(points, fill)
    }

    /**
     * 描边折线。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`，至少 2 个点
     * @param closed 是否闭合（首尾之间补一段并加接头）
     */
    @JvmOverloads
    fun strokePolyline(points: FloatArray, closed: Boolean = false) {
        strokeOutline(points, points.size / 2, closed)
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 当前路径。由 [beginPath] 复位，供 [fillPath] / [strokePath] 消费。 */
    private val path = Path()

    /** 路径平坦化器（曲线 → 折线）。跨帧复用，稳态零分配。 */
    private val flattener = Flattener()

    /** 填充三角化器。跨帧复用，稳态零分配。 */
    private val tessellator = Tessellator()

    /** 描边轮廓生成器。跨帧复用，稳态零分配。 */
    private val strokeGenerator = StrokeGenerator()

    /**
     * 平坦化与形状轮廓共用的浮动顶点缓冲，由 [ensureScratch] 按需增长。
     *
     * <p>之所以在 [Gc] 这一层留一块共用缓冲：`Flattener.copyPointsTo` 要求调用方提供目标数组，
     * 而每个形状每帧现搭一个数组正是这条管线要避免的分配。
     */
    private var scratchPoints = FloatArray(INITIAL_SCRATCH_FLOATS)

    /** 开始一条新路径，丢弃之前累积的全部子路径。 */
    fun beginPath() {
        path.reset()
    }

    /**
     * 把当前点移动到给定坐标并开启一条新子路径。
     *
     * @param x 目标 x（用户坐标）
     * @param y 目标 y（用户坐标，y 向下）
     */
    fun moveTo(x: Float, y: Float) {
        path.moveTo(x, y)
    }

    /**
     * 从当前点向给定坐标追加一条直线段。
     *
     * @param x 目标 x（用户坐标）
     * @param y 目标 y（用户坐标，y 向下）
     */
    fun lineTo(x: Float, y: Float) {
        path.lineTo(x, y)
    }

    /**
     * 追加一条二次贝塞尔曲线。
     *
     * @param cx 控制点 x（用户坐标）
     * @param cy 控制点 y（用户坐标）
     * @param x  终点 x（用户坐标）
     * @param y  终点 y（用户坐标）
     */
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) {
        path.quadTo(cx, cy, x, y)
    }

    /**
     * 追加一条三次贝塞尔曲线。
     *
     * @param c1x 第一个控制点 x（用户坐标）
     * @param c1y 第一个控制点 y（用户坐标）
     * @param c2x 第二个控制点 x（用户坐标）
     * @param c2y 第二个控制点 y（用户坐标）
     * @param x   终点 x（用户坐标）
     * @param y   终点 y（用户坐标）
     */
    fun bezierCurveTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        path.cubicTo(c1x, c1y, c2x, c2y, x, y)
    }

    /** 用一条直线回到当前子路径的起点，闭合该子路径。 */
    fun closePath() {
        path.close()
    }

    /**
     * 用当前 [fill] 填充当前路径。
     *
     * <p>曲线按 [matrixScale] 换算出的设备像素容差平坦化（默认 0.25 设备像素），
     * 因此缩放越大、曲线越平滑。
     *
     * <p>**已知限制**：路径中的所有子路径会被平坦化后当作**单个**多边形三角化，
     * 因此"外轮廓 + 内挖空"这类多子路径填充结果不正确。
     * 修正方式是改用 `Tessellator.tessellateWithHoles`，按子路径起点切分
     * （`Flattener.subPathCount()` / `subPathStart(i)` 已提供该信息），
     * 第一个子路径作外轮廓、其余作洞。此项在需要环形/饼图填充时补上。
     */
    fun fillPath() {
        if (path.isEmpty) {
            return
        }
        flattener.flatten(path, matrixScale())
        if (flattener.pointCount() < 3) {
            return
        }
        val count = flattenToScratch()
        tessellator.tessellate(scratchPoints, count)
        emitTriangles(tessellator.rawTriangles(), tessellator.triangleCount() * 6, fill)
    }

    /**
     * 用当前 [stroke] 与 [lineWidth] 描边当前路径。闭合子路径会额外加上接头。
     *
     * <p>**已知限制**：所有子路径会被平坦化后当作**一条**折线描边，
     * 因此多条子路径之间会多出一段并不存在的连线。需要多段独立描边时，
     * 请分别 `beginPath()` 后各自调用本方法。
     */
    fun strokePath() {
        if (path.isEmpty) {
            return
        }
        flattener.flatten(path, matrixScale())
        if (flattener.pointCount() < 2) {
            return
        }
        val count = flattenToScratch()
        strokeOutline(scratchPoints, count, false)
    }

    // ------------------------------------------------------------------
    // 逐图元发射
    // ------------------------------------------------------------------

    /**
     * 把平坦化结果复制进 [scratchPoints]，返回顶点个数。
     *
     * @return [Flattener.pointCount]
     */
    private fun flattenToScratch(): Int {
        val n = flattener.pointCount()
        ensureScratch(n * 2)
        flattener.copyPointsTo(scratchPoints)
        return n
    }

    /**
     * 保证 [scratchPoints] 至少能容纳 `capacity` 个 float。只在需要时分配。
     *
     * @param capacity 需要的 float 个数
     */
    private fun ensureScratch(capacity: Int) {
        if (scratchPoints.size >= capacity) {
            return
        }
        var size = scratchPoints.size
        while (size < capacity) {
            size *= 2
        }
        scratchPoints = FloatArray(size)
    }

    /**
     * 三角化一个闭合多边形并发射。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     * @param color  ARGB 颜色
     */
    private fun emitShape(points: FloatArray, color: Int) {
        val count = points.size / 2
        if (count < 3) {
            return
        }
        tessellator.tessellate(points, count)
        emitTriangles(tessellator.rawTriangles(), tessellator.triangleCount() * 6, color)
    }

    /**
     * 生成折线的描边轮廓并发射。
     *
     * <p>轮廓在**局部（用户）空间**生成，随后由 [emitTriangles] 按当前变换烘焙——
     * 因此非均匀缩放下线宽会随之变形，与 Canvas 的 `stroke` 一致。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     * @param count  顶点个数；默认取整个数组
     * @param closed 是否闭合
     */
    private fun strokeOutline(points: FloatArray, count: Int = points.size / 2, closed: Boolean = false) {
        if (count < 2) {
            return
        }
        strokeGenerator.stroke(
            points, count, closed, lineWidth,
            StrokeGenerator.Cap.BUTT,
            StrokeGenerator.Join.MITER,
            MITER_LIMIT,
            ROUND_SEGMENTS
        )
        emitTriangles(strokeGenerator.rawTriangles(), strokeGenerator.triangleCount() * 6, stroke)
    }

    /**
     * 若缓冲区已满则在图元边界处执行帧中途 flush。
     *
     * <p>必须**在每个图元写入之前**调用：`VertexWriter` 触发 flush 请求时只预留了一个最大图元的余量，
     * 不检查就会越界。flush 后 `reset()` 会把状态清回"未设置"，
     * 因此下一个图元会经由 [syncState] 重新 `setState`，无需在此额外处理。
     */
    private fun flushIfNeeded() {
        if (writer.isFlushRequested) {
            batch.submit(writer)
            writer.reset()
        }
    }

    /**
     * 把一组三角形（局部坐标）按当前变换烘焙成顶点并追加进 [VertexWriter]。
     *
     * <p>顺序很重要：先 [flushIfNeeded] 再 [syncState]——`reset()` 会把写入器带回
     * "尚未设置状态"，必须在它之后重新设状态，否则第一个顶点就会抛异常。
     *
     * <p>纯色绘制绑定 1×1 白色纹理，于是片段着色器的 `texture(uTex, vUV) * vColor`
     * 恰好退化成顶点色本身。
     *
     * @param triangles 扁平三角形数组，每 6 个 float 一个三角形
     * @param floatCount 有效 float 个数（**不是**数组长度：`rawTriangles()` 的数组通常更长）
     * @param argb       ARGB 颜色（会先乘以 [globalAlpha] 再预乘）
     */
    private fun emitTriangles(triangles: FloatArray, floatCount: Int, argb: Int) {
        if (floatCount < 6) {
            return
        }
        flushIfNeeded()
        syncState(batch.whiteTextureId())
        val packed = packColor(argb)
        var i = 0
        while (i + 5 < floatCount) {
            var k = 0
            while (k < 3) {
                val wx = triangles[i]
                val wy = triangles[i + 1]
                // 直接走 ViewTransform 的标量变换，避免每个顶点分配一个 Vec2
                writer().vertex(
                    state.transformX(wx, wy), state.transformY(wx, wy),
                    0f, 0f, packed, 0
                )
                i += 2
                k++
            }
        }
    }

    // ------------------------------------------------------------------
    // 供后续任务使用
    // ------------------------------------------------------------------

    /** 返回内部顶点写入器，供绘制方法追加顶点。 */
    internal fun writer(): VertexWriter = writer

    /** 当前变换（含基础矩阵）。逐顶点发射时用它把用户坐标烘焙到 NDC。 */
    internal val currentMatrix: Mat3
        get() = state.matrix

    /**
     * 把当前变换与裁剪状态同步给顶点写入器。
     *
     * <p>**裁剪 y 传的是顶边**：[ViewTransform.clipDeviceY] 已是 y 向下的设备像素顶边，
     * 与 [DrawCommand] 的 `scissorY` 约定一致；翻成 `glScissor` 的 y 向上坐标是
     * [RenderBatch.applyScissor] 的职责，这里<strong>不能再翻一次</strong>。
     *
     * <p>[VertexWriter.setState] 在状态未变时是空操作，所以每个图元调用一次没有额外开销；
     * 而帧中途 flush 之后 [VertexWriter.reset] 会把写入器带回"未设置状态"，
     * 下一次调用本方法正好重新设上——见 [VertexWriter.isFlushRequested] 的消费方契约。
     *
     * @param textureId 要绑定的纹理 ID
     */
    internal fun syncState(textureId: Int) {
        writer.setState(
            textureId,
            state.clipDeviceX,
            state.clipDeviceY,
            state.clipDeviceWidth,
            state.clipDeviceHeight
        )
    }

    /** 返回当前变换把用户单位换算成设备像素的平均缩放因子，供平坦化容差与分段数使用。 */
    internal fun matrixScale(): Float = state.matrixScale()

    /**
     * 应用全局不透明度后把 ARGB 颜色打包成顶点格式要求的预乘整数。
     *
     * @param argb 原始 ARGB 颜色
     * @return 预乘 alpha 后的 RGBA 整数（内存字节序 R,G,B,A）
     */
    internal fun packColor(argb: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255f * globalAlpha
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return VertexFormat.packPremultiplied(r, g, b, a)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 保证样式栈能容纳 `capacityLevels` 层。只在扩容时分配。
     *
     * @param capacityLevels 需要的层数
     */
    private fun ensureStyleCapacity(capacityLevels: Int) {
        val needed = capacityLevels * 2
        if (needed <= styleInts.size) {
            return
        }
        var size = styleInts.size * 2
        while (size < needed) {
            size *= 2
        }
        styleInts = styleInts.copyOf(size)
        styleFloats = styleFloats.copyOf(size)
    }

    companion object {
        /** 初始顶点容量，约 1.5 MB（65536 个顶点 * 24 字节）。 */
        private const val INITIAL_VERTEX_CAPACITY = 65536

        /** 圆角矩形每个角用多少段折线逼近 90° 圆弧。 */
        private const val CORNER_SEGMENTS = 6

        /** 圆/椭圆的最小细分段数：再小的圆也至少要视觉上是圆的。 */
        private const val MIN_CIRCLE_SEGMENTS = 12

        /**
         * 圆/椭圆的最大细分段数。
         *
         * <p>半径为 1 的用户单位在放大到整屏时也不该超过这个数——再多就是纯粹的顶点浪费了。
         */
        private const val MAX_CIRCLE_SEGMENTS = 256

        /** 描边 miter 接头超过该倍数（相对于半线宽）时回退为斜接。 */
        private const val MITER_LIMIT = 4f

        /** 描边的圆端点/圆角接头细分段数。 */
        private const val ROUND_SEGMENTS = 8

        /** [scratchPoints] 的初始长度（float 个数）。 */
        private const val INITIAL_SCRATCH_FLOATS = 256

        /**
         * 样式栈的初始层数。
         *
         * <p>与 [ViewTransform] 的裁剪栈互相独立，各自按需翻倍扩容，取值相同只是巧合而非约束。
         */
        private const val INITIAL_STACK_LEVELS = 8
    }
}
