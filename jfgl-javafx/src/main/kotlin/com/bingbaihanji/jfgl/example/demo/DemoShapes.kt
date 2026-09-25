package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.util.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * **笔位十字**的颜色（橙）。
 *
 * <p>**与选中高亮刻意不同色**（后者是 `JfglDemo.kt` 里的黄）——同一个画面上可能同时有
 * "选中框"与"笔位标记"，同色就分不出哪个是哪个了。
 *
 * <p>★ **名字里不带 `HIGHLIGHT`**（它早先叫 `HIGHLIGHT_CROSS`）：它标的是**笔位**，
 * 不是"某个被选中的东西"。与选中高亮共用同一个前缀，会让"这两个黄是不是同一个黄"
 * 重新变成"要读注释才知道"的问题——而那正是上面那句话想避免的。
 *
 * <p>`private` 够用：全仓只有 [Shape.TextShape.draw] 一处用它。
 */
private const val PEN_CROSS = 0xFFFF6D00.toInt()

/** 新画图形的描边方式。 */
enum class ShapeStyle { FILL, STROKE, FILL_AND_STROKE }

/** 六种可画的图形。取几个值给枚举菜单用。 */
enum class ShapeKind(val label: String) {
    RECT("矩形"), CIRCLE("圆"), ELLIPSE("椭圆"),
    LINE("直线"), POLYGON("多边形"), BEZIER("贝塞尔曲线");

    /** 是不是"轨迹定义"的那两种（其余四种是两点定义）。 */
    val isTrajectory: Boolean get() = this == POLYGON || this == BEZIER
}

/**
 * 一个**已经画完**的图形。坐标一律是**设备像素**（与 [Gc] 同一个坐标系）。
 *
 * <p>存的是**定义参数**而不是顶点：于是 `draw` 里每一种图形都能调用 [Gc] 专为它
 * 准备的那个方法——圆走 `fillCircle` 的自适应细分、多边形走 `fillPolygon` 的耳切、
 * 贝塞尔走 `Path` 的曲线平坦化。先把顶点烘成 `FloatArray` 的写法会丢掉这三样。
 *
 * <p>**不可变**：所有字段都是 `val`。图形列表靠整表替换（见 `JfglDemo.shapes`），
 * 于是 GL 线程拿到的永远是一个自洽的快照。
 */
sealed interface Shape {
    /**
     * 主色（ARGB 打包）。
     *
     * <p><strong>★ 它的语义按变体分两种，别以为全项目只有一个意思</strong>：
     * 六个几何变体（[RectShape] / [CircleShape] / [EllipseShape] / [LineShape] /
     * [PolygonShape] / [BezierShape]）里它是**填充与描边共用的那个色**——
     * `drawWith` 把它同时赋给 `gc.fill` 与 `gc.stroke`；而 [TextShape] 里它是**文字色**
     * （只进 `gc.fill`，因为文字走的就是填充那条路）。
     * 方向本身说得通（`Gc.drawText` 用的正是 `fill`），但不写下来，
     * 读的人只能从各自实现里猜。
     */
    val color: Int
    val style: ShapeStyle
    val lineWidth: Float

    /** 画自己。**只在 GL 线程调用。** */
    fun draw(gc: Gc)

    /**
     * 轴对齐包围盒（设备像素）。选中高亮与状态栏用。
     *
     * <p>**约定**：尺寸参数（`w` / `h` / `r` / `rx` / `ry` / `size`）**非负**。
     * 七个实现里只有 [LineShape] 自己用 `minOf` / `abs` 规范化，另外四个
     * （[RectShape] / [CircleShape] / [EllipseShape] / [TextShape]）把尺寸参数直接交给
     * 矩形算术——传负值会得到一个**负尺寸矩形**，而 `util/Rect` 的
     * `contains` / `intersects` 在负尺寸下恒为 `false`（`px <= x + width` 里右边比左边小了）。
     * [PolygonShape] / [BezierShape] 不受影响：它们没有尺寸参数，框由 `boundsOf` 从点集算。
     *
     * <p><strong>★ 但 [TextShape] 与那三个是同病不同因</strong>：它的非负性**不靠构造处
     * 规范化**，而是 `commitText` 传进来的那个 `size` 本来就是 `FONT_SIZES` 里的
     * **正数字面量**——**没有任何东西在替它翻正**。换一个能传负 `size` 的调用方，
     * 它立刻就是上面那个负尺寸矩形（它的 `bounds()` 自己的文档里也写了这一条）。
     *
     * <p>所以"反向拖拽"必须在**构造处**规范化（demo 里是 `JfglDemo.commitShape` 用
     * `minOf` / `abs` / `hypot` 做的）。**这里不就地规范化是刻意的**：每帧多几次 `abs`
     * 不贵，但把约定留在构造点那一处，比让七个实现各自决定怎么翻正要好读——
     * 而且 [LineShape] 已经翻正了，另六个再翻一遍会让"谁负责"这件事变糊涂。
     */
    fun bounds(): Rect

    /** 状态栏上显示的一行描述。 */
    fun describe(): String

    data class RectShape(
        val x: Float, val y: Float, val w: Float, val h: Float,
        override val color: Int, override val style: ShapeStyle, override val lineWidth: Float
    ) : Shape {
        override fun draw(gc: Gc) = drawWith(gc, style, color, lineWidth) {
            if (style != ShapeStyle.STROKE) gc.fillRect(x, y, w, h)
            if (style != ShapeStyle.FILL) gc.strokeRect(x, y, w, h)
        }
        override fun bounds() = Rect(x, y, w, h)
        override fun describe() = "矩形 ${w.toInt()}x${h.toInt()} @(${x.toInt()},${y.toInt()})"
    }

    data class CircleShape(
        val cx: Float, val cy: Float, val r: Float,
        override val color: Int, override val style: ShapeStyle, override val lineWidth: Float
    ) : Shape {
        override fun draw(gc: Gc) = drawWith(gc, style, color, lineWidth) {
            if (style != ShapeStyle.STROKE) gc.fillCircle(cx, cy, r)
            if (style != ShapeStyle.FILL) gc.strokeCircle(cx, cy, r)
        }
        override fun bounds() = Rect(cx - r, cy - r, r * 2f, r * 2f)
        override fun describe() = "圆 r=${r.toInt()} @(${cx.toInt()},${cy.toInt()})"
    }

    data class EllipseShape(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        override val color: Int, override val style: ShapeStyle, override val lineWidth: Float
    ) : Shape {
        override fun draw(gc: Gc) = drawWith(gc, style, color, lineWidth) {
            if (style != ShapeStyle.STROKE) gc.fillEllipse(cx, cy, rx, ry)
            if (style != ShapeStyle.FILL) gc.strokeEllipse(cx, cy, rx, ry)
        }
        override fun bounds() = Rect(cx - rx, cy - ry, rx * 2f, ry * 2f)
        override fun describe() = "椭圆 ${rx.toInt()}x${ry.toInt()} @(${cx.toInt()},${cy.toInt()})"
    }

    data class LineShape(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        override val color: Int, override val style: ShapeStyle, override val lineWidth: Float
    ) : Shape {
        // 直线**只有描边**这一种画法：填充一条没有面积的线段没有意义。
        // 所以 `style` 这个字段在直线身上**只写不读**——它只为满足 [Shape] 接口而存在，
        // 菜单里选"只填充"再拖一条线时，读的是 `JfglDemo.styleIsLine`（那边会把样式菜单灰掉）。
        // 与 [TextShape] 的 `style` / `lineWidth` 是同性质的事，一处交代了另一处也该交代。
        override fun draw(gc: Gc) = strokeWith(gc, color, lineWidth) { gc.drawLine(x1, y1, x2, y2) }
        override fun bounds() = Rect(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1))
        override fun describe() = "直线 (${x1.toInt()},${y1.toInt()})→(${x2.toInt()},${y2.toInt()})"
    }

    /**
     * 轨迹抽稀后的**闭合**多边形。`points` 是 `[x0,y0, x1,y1, ...]`。
     *
     * <p>点数少于 3 时**不会崩**（`Gc.fillPolygon` / `strokePolyline` 内部有
     * `count < 3` / `count < 2` 的守卫），但什么都画不出来——而一个占着拾取号与列表项、
     * 却看不见的图形，比拒绝构造它更糟。[ShapeMath.polygonFrom] 因此对不足 3 点的轨迹返回 null。
     *
     * <p><strong>构造时拷贝一份 `points`</strong>：`FloatArray` 是**可变引用**，
     * `val` 只固定了引用本身。而 [Shape] 的类文档声称"所有字段都是 `val`，所以 GL 线程
     * 拿到的是自洽的快照"——那句话只在数组不被调用方复用或改写时才成立。
     * `ShapeMath.bezierFrom` 在轨迹恰好 2 个点时会**原样返回调用方的数组**
     * （`simplify` 的 `n <= 2` 提前返回），那一刻图形就持有了调用方的活缓冲。
     * 拷贝一次（点数在几百量级）比依赖一条看不见的约定划算。
     *
     * <p>因此这里**不是 `data class`**：`data class` 对数组的 `equals`/`hashCode`
     * 是引用比较（本身就有误导性），而本 demo 从不比较图形的相等性——`copy()` 与
     * 解构也用不到。为数组而放弃 `data`，比为一个假的不变量保留它好。
     */
    class PolygonShape(
        points: FloatArray,
        override val color: Int, override val style: ShapeStyle, override val lineWidth: Float
    ) : Shape {

        /** 构造时拷贝过的一份，外部改不到（理由见类文档）。 */
        val points: FloatArray = points.copyOf()
        override fun draw(gc: Gc) = drawWith(gc, style, color, lineWidth) {
            if (style != ShapeStyle.STROKE) gc.fillPolygon(points)
            if (style != ShapeStyle.FILL) gc.strokePolyline(points, closed = true)
        }
        override fun bounds() = boundsOf(points)
        override fun describe() = "多边形 ${points.size / 2} 顶点"
    }

    /**
     * 轨迹抽稀后的**平滑曲线**。`points` 是 `[x0,y0, x1,y1, ...]`。
     *
     * <p><strong>约定</strong>：至少 2 个点（4 个 float）。这是**语义**要求——
     * 只有 1 个点时曲线退化成零长线段，等于什么都没画。
     *
     * <p><strong>崩溃边界（与上面的约定不是同一个数，别混）</strong>：[draw] 直接索引
     * `points[0]` / `points[1]` 与 `points[size-2]` / `points[size-1]`，**没有任何下层守卫兜底**
     * （它调的是 `Gc` 的路径方法，那些方法拿到的是已经索引过的标量；对比 [PolygonShape]
     * 是把整个数组转交给 `Gc`，而那边有 `count < 3` / `count < 2` 的守卫）。
     * 精确边界是 **`points.size < 2`（一个点都不到）才抛 `ArrayIndexOutOfBoundsException`**；
     * `points.size == 2`（恰好一个点）不会抛，但画出来是零长线段。
     * 而 GL 线程上的异常在本项目是**静默吞掉**的，所以真抛了的表现是
     * "这一帧什么都没画、也没有报错"。
     *
     * <p>本 demo 里由 [ShapeMath.bezierFrom] 用"不足 2 点则返回 null"保证前者；
     * 别处构造它时请自己守住。
     */
    class BezierShape(
        points: FloatArray,
        override val color: Int, override val style: ShapeStyle, override val lineWidth: Float
    ) : Shape {

        /** 构造时拷贝过的一份，外部改不到（理由见 [PolygonShape] 的类文档）。 */
        val points: FloatArray = points.copyOf()
        override fun draw(gc: Gc) = strokeWith(gc, color, lineWidth) {
            gc.beginPath()
            gc.moveTo(points[0], points[1])
            // 中点二次曲线平滑：每段以上一个点为控制点、以两点中点为终点。
            // 只描边不填充——一条开放曲线的"内部"没有定义。
            var i = 2
            while (i + 3 < points.size) {
                gc.quadTo(points[i], points[i + 1], (points[i] + points[i + 2]) / 2f, (points[i + 1] + points[i + 3]) / 2f)
                i += 2
            }
            gc.lineTo(points[points.size - 2], points[points.size - 1])
            gc.strokePath()
        }
        override fun bounds() = boundsOf(points)
        override fun describe() = "贝塞尔曲线 ${points.size / 2} 控制点"
    }

    /**
     * 一段文本。**`(x, y)` 是基线起点，不是文本框左上角**——这是 [Gc.drawText] 的约定，
     * 也是全项目最容易被调用方猜错的一条。
     *
     * <p>[draw] 除了文字还画一个**十字准星**标在笔位上：猜错基线的表现是"文字位置偏了一点"，
     * 肉眼分不出是约定错了还是布局错了；把笔位画出来，就不可能隐形。
     */
    data class TextShape(
        val text: String, val x: Float, val y: Float, val size: Float, override val color: Int
    ) : Shape {
        /**
         * 只为满足 [Shape] 接口而存在：**文字只有"填充"这一种画法**
         * （字形以 [color] 填进帧缓冲），所以恒为 [ShapeStyle.FILL]。
         * 这里跟 [LineShape] 那句"直线只有描边这一种画法"交代的是同一性质的事。
         */
        override val style = ShapeStyle.FILL

        /**
         * 同样只为满足接口：**它不参与绘制**——`draw` 里那个笔位十字的线宽是写死的 1f。
         * 写出来是免得读的人自己判断这是疏忽还是有意。
         */
        override val lineWidth = 1f

        /**
         * 画文字 + 一个**笔位十字**。
         *
         * <p>★ 走 [drawWith] 而不是手写 `save` / `restore`：**一行就拿到 `finally`**，
         * 而且消掉了"同一个文件里两种口径并存"——本文件的 [drawWith] / [strokeWith]
         * 都带 `finally`，只有这里以前是裸的。理由与那两个完全相同（见 [drawWith] 的
         * 文档）：把状态栈的平衡**在本文件内闭合**。
         *
         * <p>**这不是"行为修复"**：body 在正常路径上不抛，而真抛了的话
         * `FXGLTransfer` 会把 `endFrame()` 整趟跳过、此后每帧都抛——栈上多一层根本
         * 表现不出来。它的价值在一致性，以及"将来有人在帧回调外面套 `try/catch`
         * 保住帧循环时，这里不成新地雷"（那一条是架构评审提的）。
         *
         * <p>样式由 [drawWith] 设好（`fill` / `stroke` / `lineWidth` 都是 [color]），
         * body 里再把 `stroke` 覆盖成十字的橙色——与改动前逐位相同。
         */
        override fun draw(gc: Gc) = drawWith(gc, ShapeStyle.FILL, color, 1f) {
            gc.fontSize = size
            gc.drawText(text, x, y)
            // 笔位十字：横竖各 8px
            gc.stroke = PEN_CROSS
            gc.drawLine(x - 8f, y, x + 8f, y)
            gc.drawLine(x, y - 8f, x, y + 8f)
        }

        /**
         * 文本的外接框（设备像素）。
         *
         * <p><strong>★ 目前没有任何消费方</strong>：文本恒以 `pickId = 0` 落下，而
         * 两个调用点都把它挡在外面——选中高亮那个循环的判据是 `p.pickId in sel`，
         * 自检第 ① 条的几何断言读的是 `shapes[0]`（一个矩形）。而 `sel` 只可能来自
         * `Gc.pick`（对 id 0 返回 null）与 `PickBuffer.readRect`（滤掉 0），**永不含 0**。
         * 所以这个方法今天一次都不会**落在文本上**。
         * （★ 早先这里写的是"`bounds()` 的唯一调用点是选中高亮那个循环"——**那句是假的**：
         * 自检 ① 也调它。结论不变，但成立的理由是上面这条，不是"没人调"。）
         * **接入文本拾取之前必须按真实推进宽度把它重算一遍**（那时它才开始被消费）。
         *
         * <p><strong>宽度是按字符数估的，不是量出来的</strong>（`size × min(字符数, 8)`）——
         * CJK 约 1 em/字、拉丁约 0.5 em/字，所以这个框**不会贴着文字**。
         * **而且 `coerceAtMost(8)` 让它在本 demo 里退化成了常数**：四条样本串的字符数是
         * 24 / 9 / 14 / 17，**全部 ≥ 8**，于是乘数恒为 8、宽度恒为 `size × 8`。
         * （写这一句是免得下一个人以为它真的"按字符数估"。）
         *
         * <p><strong>为什么不在构造处用 `gc.measureText` 量真的</strong>：那把尺子
         * **只能在 GL 线程上调**——它取的是 `batch` 的字形源（`Gc.measureText` 里那句），
         * 而那一侧**没有** `pickRegistry` 那种"刻意例外"的线程安全承诺。
         * `TextShape` 是在 `commitText` 里（JavaFX 线程）构造的，所以在那里量
         * **本来就不通**，与"值当不值当"无关。
         *
         * <p>上下取 `size × 1.3`：基线之下留出降部（`g`/`y` 的尾巴）。
         * **尺寸项恒非负**，但那靠的是 `commitText` 只传 `FONT_SIZES` 里的正数字面量
         * ——**这里没有任何东西在翻正**（见 `Shape.bounds()` 的 KDoc）。
         */
        override fun bounds() = Rect(x, y - size, size * text.length.coerceAtMost(8), size * 1.3f)
        override fun describe() = "文本「$text」 ${size.toInt()}px @(${x.toInt()},${y.toInt()})"
    }
}

/**
 * 在给定的颜色 / 线宽下执行一段绘制。
 *
 * <p>样式是 [Gc] 的**可变状态**，所以这里必须 `save` / `restore` —— 否则一个图形的颜色
 * 会漏给它后面画的每一个图形（表现是"颜色会传染"，而代码看起来完全正常）。
 * 线宽只在描边时设置，纯填充时设它没有意义。
 *
 * <p>**`finally` 不能省**，这是本仓库的既有约定：同一个"压栈 → 画 → 弹栈"构造在库层
 * 一律带 `finally`（`Gc.pickable`、`ChartRenderer.drawChart`、`ChartDecorations` 都是），
 * 理由是"装饰画到一半抛异常时，状态栈会少弹一层，之后画的每一个图元都带着那一次压栈的状态"。
 *
 * <p>⚠️ **但要说清楚 `finally` 在这里治不了什么**：`body()` 抛异常时，异常会穿过
 * `onFrameCallback`，于是 `FXGLTransfer` 里那句 `context.endFrame()` **整个被跳过**，
 * `Gc.frameActive` 停在 `true`；下一帧 `beginFrame` 撞上 `check(!frameActive)` 又抛——
 * **此后每一帧都抛，画面永久冻结且不报错**。真正该加守卫的地方是 `FXGLTransfer` 的帧回调
 * （让 `endFrame` 无论如何都执行），那是库的事，不在本文件范围内。
 * 这里加 `finally` 是为了**把栈平衡在本文件内闭合**，以及避免将来有人在回调外面套
 * `try/catch` 保住帧循环时，`endFrame` 的配对检查变成新的地雷。这条已记进架构评审。
 */
private inline fun drawWith(gc: Gc, style: ShapeStyle, color: Int, lineWidth: Float, body: () -> Unit) {
    gc.save()
    gc.fill = color
    gc.stroke = color
    gc.lineWidth = lineWidth
    try {
        body()
    } finally {
        gc.restore()
    }
}

/**
 * 只描边：**只设描边色与线宽，不碰填充色**——`body` 不会调填充方法，
 * 设了也没有意义（早先那句"先把填充色设成同一个色也没关系"是本函数还设 `fill`
 * 时的残留，与现在的函数体已经不符）。
 */
private inline fun strokeWith(gc: Gc, color: Int, lineWidth: Float, body: () -> Unit) {
    gc.save()
    gc.stroke = color
    gc.lineWidth = lineWidth
    try {
        body()
    } finally {
        gc.restore()
    }
}

/**
 * 一组点的轴对齐包围盒。
 *
 * <p>**约定**：`points` 是 `[x0,y0, x1,y1, ...]`，**长度为偶数**。长度为奇数时
 * **末尾那个孤立的 float 会被丢掉**——这与 [Shape.BezierShape.draw] 的行为**不一致**
 * （它的 `lineTo(points[size-2], points[size-1])` 会把那个 float 当作 y 读）。
 * 正常路径产不出奇数长度——**偶数长度由调用方保证**（`JfglDemo` 每个鼠标事件追加 2 个 float）。
 *
 * @return `points.size < 2`（不到一个点）时返回零矩形，由调用方自己判
 */
internal fun boundsOf(points: FloatArray): Rect {
    if (points.size < 2) return Rect(0f, 0f, 0f, 0f)
    var minX = points[0]; var maxX = points[0]
    var minY = points[1]; var maxY = points[1]
    var i = 2
    while (i + 1 < points.size) {
        if (points[i] < minX) minX = points[i]
        if (points[i] > maxX) maxX = points[i]
        if (points[i + 1] < minY) minY = points[i + 1]
        if (points[i + 1] > maxY) maxY = points[i + 1]
        i += 2
    }
    return Rect(minX, minY, maxX - minX, maxY - minY)
}

/**
 * 折线段数的上限。远超任何预览需要（几百像素、6/4 图案约 100 段），只为让迭代有界。
 */
internal const val MAX_DASH_SEGMENTS = 4096f

/**
 * 这个 dash 模式**能不能用来画**：`on` / `off` 是不是正的有限数、
 * 它们的和是不是有限、**以及按这条路径的长度算下来迭代次数有没有上界**。
 *
 * <p><strong>最后一条不是凑数的</strong>：内层 while 的开销是 `Θ(arcLen / pattern)`，
 * 而 `k` 是 **`Int`**——pattern 小到 ~1e-7 时 `floor(s / pattern).toInt()` 会饱和到
 * `Int.MAX_VALUE`，`k * pattern` 仍 ≤ `s + len`，于是 `k++` **回绕到 `Int.MIN_VALUE`**，
 * 负数 `k` 也满足条件 ⇒ 状态重现 ⇒ **内层 while 永不退出**（它跑在 `onRender` 里，
 * 卡住的是 GL 线程）；而每段只有亚像素宽 ⇒ **预览同时静默消失**。
 * 两个症状都是调用方最不想要的那种。
 *
 * <p><strong>判据写成"可用"而不是"不可用"</strong>：正着写时 NaN 被 `> 0f` 自动挡掉
 * （不必在脑子里做一次德摩根反演），而且**没人会把 `x > 0f` "化简"成 `x <= 0f`**
 * ——危险的重写只会从 `!(x > 0f)` 出发。
 *
 * @param pathLength 这条折线的总弧长（调用方先算一遍）
 */
internal fun isUsableDashPattern(on: Float, off: Float, pathLength: Float): Boolean {
    if (!(on > 0f) || !on.isFinite()) return false
    if (!(off > 0f) || !off.isFinite()) return false
    val pattern = on + off
    if (!pattern.isFinite()) return false
    return pathLength / pattern <= MAX_DASH_SEGMENTS
}

/**
 * 用**虚线**描一条折线。**预览专用。**
 *
 * <p><strong>为什么在 demo 里手算</strong>：库里的 `StrokeGenerator.strokeDashed(...)`
 * **存在且有单测**（`StrokeDashTest`），但 **`Gc` 没有把它暴露出来**——`Gc` 的描边路径
 * 只调实线那个 `stroke(...)`。本 demo 因此自己按**弧长**把折线切成实段。
 * （这条缺口**将在 Task 4 记进** `CLAUDE.md` 的「未实现 / 待办」。）
 *
 * <p><strong>它只用于预览</strong>：提交时仍然走 `Gc` 的绘制方法
 * （`fillRect` / `fillCircle` / `fillEllipse` / `drawLine`，轨迹型是
 * `fillPolygon` / `strokePolyline` 与 `beginPath…strokePath`），
 * 所以**画出来的东西与这个助手无关**——**只要调用方传的是常量**（本 demo 就是），
 * 它坏掉最多是预览难看。
 *
 * <p>代价照实记：预览轮廓由本文件与 `JfglDemo` 各算一遍，
 * 与 `Gc` 内部那份（`rectOutline` / `circleOutline` / `ellipseOutline`，
 * 都是 `private`）**不是同一份代码**，因此**预览的圆与提交的圆在细分段数上可能不同**。
 *
 * <p><strong>任意两段绘制区间互不重叠</strong>——半透明预览下不会因叠加而加深。
 * 这条是**解析**结论、不需要仿真：第 k 格画的是 `[max(kp, s), min(kp+on, s+len)]`，
 * 第 k+1 格同理，而因 `off > 0` 有 `(k+1)p = kp + on + off > kp + on` ⇒ 两区间严格分离。
 * （仿真只复核过它，不是它的依据。）
 *
 * <p>★ **一条数值仿真的残余风险，照实记**：若只做"把本函数忠实移植成 float32"来仿真，
 * 那**与被审函数不是同一个函数**——`kotlin.math.hypot(Float, Float)` 的实现是
 * `(float) Math.hypot((double)x, (double)y)`（**双精度**；kotlin-stdlib 2.3.0 字节码实测为
 * `f2d / f2d / Math.hypot(DD)D / d2f`），而朴素移植会用 `sqrt(dx*dx + dy*dy)` 的 float32 算法。
 * 依赖 `len` 的结论（dash 边界落点）恰恰是两者差异会落到的地方。所以**仿真不能替代像素回读**；
 * 这个助手目前**唯一的自动化闸门**是 `DemoShapeMath.kt` 里那 6 条纯函数断言
 * （判据是"被放行的输入迭代有上界"，即**行为**，而不是"参数是正的有限数"）。
 *
 * @param gc      绘制上下文（调用方负责设好 `stroke` 与 `lineWidth`）
 * @param points  扁平顶点数组 `[x0,y0, x1,y1, ...]`。两条隐含契约：长度为**奇数**时
 *                末尾那个孤立的 float 被丢掉（与 [boundsOf] 同口径）；长度 `<= 1e-6f`
 *                的段被跳过（不描、也不推进累计弧长）。
 * @param closed  是否首尾相接（闭合时最后一段从末点回到首点）
 * @param dashOn  实段长度（设备像素）。**可用值是正的有限数**，判据见 [isUsableDashPattern]；
 *                不是可用值时退化成实线
 * @param dashOff 空段长度（设备像素）。同上
 */
internal fun strokeDashedPolyline(
    gc: Gc, points: FloatArray, closed: Boolean, dashOn: Float, dashOff: Float
) {
    val n = points.size / 2
    if (n < 2) return
    val segCount = if (closed) n else n - 1
    // 先算总弧长：`isUsableDashPattern` 的第三项要用它（迭代有上界）。
    // 这一趟与下面的主循环共用同一个 `segCount`（两侧的"段"必须是同一个集合）。
    var pathLen = 0f
    for (i in 0 until segCount) {
        val j = (i + 1) % n
        pathLen += hypot(points[j * 2] - points[i * 2], points[j * 2 + 1] - points[i * 2 + 1])
    }
    // 判据本身在 [isUsableDashPattern] 里（有名字、可单测），这里只说**意图**：
    // 参数构不成可用的 dash 模式时退化成实线，**不静默什么都不画**。
    // ★ 两端都要有界，不只是"参数是正的有限数"：**大端**由 `isFinite` 管住
    //   （+Inf ⇒ `k` 恒 0 ⇒ `0 * Inf = NaN` ⇒ 循环一次都不执行），
    //   **小端**由 `MAX_DASH_SEGMENTS` 管住（pattern ~1e-7 ⇒ `k` 回绕 ⇒ 死循环）。
    if (!isUsableDashPattern(dashOn, dashOff, pathLen)) {
        gc.strokePolyline(points, closed)
        return
    }
    // 走到这里 `pattern` 必为正的有限数——早先那条 `|| pattern <= 0f` 在判据之下恒假，已删
    // （留着一个恒假的判据，下一个人会以为它有意义）。
    val pattern = dashOn + dashOff
    var s = 0f                                  // 折线起点算起的累计弧长
    for (i in 0 until segCount) {
        val j = (i + 1) % n
        val ax = points[i * 2]
        val ay = points[i * 2 + 1]
        val bx = points[j * 2]
        val by = points[j * 2 + 1]
        val len = hypot(bx - ax, by - ay)
        if (len <= 1e-6f) continue
        // 本段覆盖全局弧长 [s, s+len)。逐个 dash 实区间与它求交：
        // 一个实区间可能横跨多个折线段，那就在每段里各画一段（拐角处留一个亚像素的缝，
        // 预览上不可见）。
        var k = floor((s / pattern).toDouble()).toInt()
        while (k * pattern <= s + len) {
            val from = maxOf(k * pattern, s)
            val to = minOf(k * pattern + dashOn, s + len)
            if (to > from) {
                val t0 = (from - s) / len
                val t1 = (to - s) / len
                gc.drawLine(
                    ax + (bx - ax) * t0, ay + (by - ay) * t0,
                    ax + (bx - ax) * t1, ay + (by - ay) * t1
                )
            }
            k++
        }
        s += len
    }
}
