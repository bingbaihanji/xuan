package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.util.Rect
import kotlin.math.abs

/**
 * 笔位十字的颜色（橙）。
 *
 * <p>**与选中高亮刻意不同色**（后者是 `JfglDemo.kt` 里的黄）——同一个画面上可能同时有
 * "选中框"与"笔位标记"，同色就分不出哪个是哪个了。
 */
internal const val HIGHLIGHT_CROSS = 0xFFFF6D00.toInt()

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
    val color: Int
    val style: ShapeStyle
    val lineWidth: Float

    /** 画自己。**只在 GL 线程调用。** */
    fun draw(gc: Gc)

    /**
     * 轴对齐包围盒（设备像素）。选中高亮与状态栏用。
     *
     * <p>**约定**：尺寸参数（`w` / `h` / `r` / `rx` / `ry`）**非负**。
     * 六个实现里只有 [LineShape] 自己用 `minOf` / `abs` 规范化，另外三个
     * （[RectShape] / [CircleShape] / [EllipseShape]）把尺寸参数直接交给矩形算术——
     * 传负值会得到一个**负尺寸矩形**，而 `util/Rect` 的
     * `contains` / `intersects` 在负尺寸下恒为 `false`（`px <= x + width` 里右边比左边小了）。
     * [PolygonShape] / [BezierShape] 不受影响：它们没有尺寸参数，框由 `boundsOf` 从点集算。
     *
     * <p>所以"反向拖拽"必须在**构造处**规范化（demo 里是 `JfglDemo.commitShape` 用
     * `minOf` / `abs` / `hypot` 做的）。**这里不就地规范化是刻意的**：每帧多几次 `abs`
     * 不贵，但把约定留在构造点那一处，比让六个实现各自决定怎么翻正要好读——
     * 而且 [LineShape] 已经翻正了，另五个再翻一遍会让"谁负责"这件事变糊涂。
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
        override val style = ShapeStyle.FILL
        override val lineWidth = 1f

        override fun draw(gc: Gc) {
            gc.save()
            gc.fontSize = size
            gc.fill = color
            gc.drawText(text, x, y)
            // 笔位十字：横竖各 8px
            gc.stroke = HIGHLIGHT_CROSS
            gc.lineWidth = 1f
            gc.drawLine(x - 8f, y, x + 8f, y)
            gc.drawLine(x, y - 8f, x, y + 8f)
            gc.restore()
        }

        /**
         * 文本的外接框（设备像素）。
         *
         * <p><strong>宽度是按**字符数**估的，不是量出来的</strong>（`size × min(字符数, 8)`）——
         * CJK 约 1 em/字、拉丁约 0.5 em/字，所以这个框**不会贴着文字**。
         * 它是给选中高亮用的近似值，够用即可；真要精确就用 `gc.measureText`，
         * 但那要求在这里持有 `Gc`，而 `Shape` 是纯模型——不值当。
         *
         * <p>上下取 `size × 1.3`：基线之下留出降部（`g`/`y` 的尾巴）。
         * **尺寸项恒非负**（`size > 0`），满足 `Shape.bounds()` 的"尺寸非负"约定。
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

/** 只描边：先把填充色设成同一个色也没关系，因为 `body` 不会调填充方法。 */
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
