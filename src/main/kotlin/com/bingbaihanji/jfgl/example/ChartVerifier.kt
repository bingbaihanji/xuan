package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.chart.ArrayChartData
import com.bingbaihanji.jfgl.chart.Axis
import com.bingbaihanji.jfgl.chart.AxisRange
import com.bingbaihanji.jfgl.chart.AxisType
import com.bingbaihanji.jfgl.chart.Chart
import com.bingbaihanji.jfgl.chart.ChartType
import com.bingbaihanji.jfgl.chart.Series
import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.util.Rect
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * 图表绘制后端（子项目 D-②）的**最小冒烟校验器**：把一个形状完全已知的斜坡数据
 * 画进一块已知的绘图区，回读帧缓冲，逐项比对像素，失败则以非零码退出。
 *
 * <h2>它为什么存在</h2>
 *
 * <p>本仓库的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
 * 图表的绘制路径尤其危险——`SeriesBuffer` 用错一个写指针会让画面**定格在第一屏**，
 * 实例属性的 4 字节偏移写错会让波形**少一半的点**，两者都不报错、都"看起来像条曲线"。
 * 这些只有把**最终像素**作为口径才拦得住。
 *
 * <p>本文件是 Task 10 + Task 11 的收口：它证明 ② 真的把东西画出来了，而且是画在
 * 该在的地方。Task 12 会把它扩成十条断言的完整校验器（上传字节数、场景会变、
 * NaN 断开、z 序……），本文件**刻意只做冒烟**，不替 Task 12 把活干完。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.ChartVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。它会自己关窗退出。
 *
 * <h2>坐标前提</h2>
 *
 * <p>与 PipelineVerifier 同一条前提：用户坐标**按 1:1 映射到设备像素**。
 * 实测本机的帧缓冲是 **988x738**（逻辑 800x600 的窗口，系统缩放 125%），
 * 而下面的期望值仍然直接成立——绘图区、样本下标到屏幕 x、数值到屏幕 y
 * 全都按"用户坐标 = 设备像素"书写。若日后引入真正的 DPI 缩放，
 * 这些期望值需要乘以缩放系数——那时它会失败，正是它该提醒的。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 绘图区（数据区域）矩形，设备像素。 */
private const val PLOT_X = 100
private const val PLOT_Y = 100
private const val PLOT_W = 600
private const val PLOT_H = 400

/** 数据点数与斜坡的满量程。 */
private const val POINTS = 101

/**
 * x 轴的可见窗口（单位是**数据下标**），比数据范围略小，用来看"窗口之外的数据不参与绘制"。
 */
private const val WINDOW_MIN = 10.0
private const val WINDOW_MAX = 90.0

/**
 * 溢出系列在窗口外的高度（数值单位）。
 *
 * <p>它大于 y 轴的窗口上界 1.0，因此这段折线会画到**绘图区上方**去。
 * 这是让"裁剪生效"这条断言不是橡皮图章的唯一办法：x 轴的窗口总是被映射到绘图区
 * 左右边缘上，所以**横向上永远溢不出去**；能溢出绘图区的只有超过 y 窗口的数值。
 * 实测过：只用正向斜坡时，漏掉裁剪测试也只有 **2 个**像素越界（首个线段的法向
 * 外扩角），那条断言几乎恒真。
 */
private const val SPILL_VALUE = 1.2

/** 溢出系列在此之前保持高值，之后落回绘图区内。 */
private const val SPILL_UNTIL = 30

/**
 * "标注"矩形的左上角与尺寸。它压在斜坡上（x=540 处的线在 y≈225），且在绘图区**之内**。
 *
 * <p>它在 `charts.draw()` 之后由 {@code Gc} 画出，因此钉住了 `Gc.flush()` 文档里
 * 那套 z 序的另一半：**数据系列是当场就画的**，后画的标注才盖得住它。
 * 若图表把顶点攒进 `Gc` 的写入器、等 `endFrame` 才提交，标注就会被折线穿过——
 * 而"折线上有个洞"与"折线本来就该这样"在肉眼上分不开。
 */
private const val LABEL_X = 540f
private const val LABEL_Y = 210f
private const val LABEL_W = 40f
private const val LABEL_H = 20f

/**
 * 溢出系列回落后的数值。
 *
 * <p>刻意不取 0.5（那正好是 y = 300，与斜坡在 x = 400 处**完全重合**）：
 * 后画的系列会把先画的盖掉，于是斜坡的断言会被一条无关的线悄悄弄坏——
 * 实测过一次，中心那 40 px 只剩 14 px。0.85 让它在 x < 475 一带彻底避开斜坡。
 */
private const val SPILL_LOW = 0.85

// ---------------------------------------------------------------------------
// 折返图：专门用来钉住"线段的两端是两个不同的实例属性"
// ---------------------------------------------------------------------------

/** 折返图自己的绘图区，落在斜坡绘图区的下方（两者不重叠）。 */
private const val ZIG_PLOT_X = 350f
private const val ZIG_PLOT_Y = 505f
private const val ZIG_PLOT_W = 450f
private const val ZIG_PLOT_H = 90f

/** 折返图的 x 轴窗口：只有 4 段。 */
private const val ZIG_WINDOW_MIN = 0.0
private const val ZIG_WINDOW_MAX = 4.0

/**
 * 折返图的 y 值：**5 个点大幅折返**，相邻两点的值一个 0 一个 1。
 *
 * <p><strong>为什么必须陡。</strong>这条断言要分开的两种实现是：
 * <ul>
 *   <li>正确：线段两端取的是 {@code y[k]} 与 {@code y[k+1]}（同一个 VBO、偏移差 4 字节）；</li>
 *   <li>坏掉：两个实例属性都指向 {@code y[k]}（第二个偏移写成 0），于是每个线段退化成
 *       一段**水平小横线**，画在左端点的高度上。</li>
 * </ul>
 * 判别式只有"相邻两点的**中点**"这一处：正确实现在那里是两端点的平均值，
 * 坏实现里那里什么都没有（它的线在左端点的高度上）。
 *
 * <p>斜坡那种密而缓的数据分不开这两种实现——差值只有约 2 px，而且
 * (250,380) / (400,300) 恰好都是坏实现那些横线的**顶点**，两条断言照样通过（实测）。
 * 折返图把相邻两点拉开整个绘图区高度，中点到左端点的高度差是**半高 = 45 px**，
 * 一眼可辨，也不怕抗锯齿。
 */
private val ZIG_VALUES = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0)

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已有顶层 `main()`，两个同名顶层函数会让
 * `import com.bingbaihanji.jfgl.example.main` 报"重载歧义"。用 `@JvmName("main")`
 * 把 JVM 方法名钉回 `main`，上面文档里的命令行因此照常可用。
 */
@JvmName("main")
fun chartVerifyMain() {
    Application.launch(ChartVerifierApp::class.java)
}

class ChartVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 帧计数。**场景逐帧在变**（见 [movingSquareX]），好让跨帧状态泄漏没有藏身处。 */
    private var frame = 0

    /** 绘制期间抛出的异常。GL 线程上一个没接住的异常会静默吞掉退出码，见 [verifyOnce]。 */
    private var drawError: Throwable? = null

    // ---- 配色 ----
    // 背景 0x333333 来自 FXGLTransfer 的 glClearColor(0.2, 0.2, 0.2)。
    private val background = 0x333333

    /** 绘图区底色，由 [Gc] 画（顺带验证"Gc 画底 → flush → 图表画在其上"这个 z 序）。 */
    private val plotBackground = 0x202040

    /** 主系列：斜坡折线。 */
    private val rampRgb = 0x00FF00

    /** 溢出系列：有一段画到绘图区上方，用来钉住裁剪。 */
    private val spillRgb = 0x00FFFF

    /** 反证系列：线宽 0，一个像素都不该出现。 */
    private val degenerateRgb = 0xFF00FF

    /** 跨帧状态泄漏探针的颜色。 */
    private val squareRgb = 0xFFFFFF

    /** 标注矩形的颜色。 */
    private val labelRgb = 0xFF8000

    /** 折返图的颜色。 */
    private val zigRgb = 0xFFFF00

    private val zigArgb = zigRgb or (0xFF shl 24)

    private val rampArgb = rampRgb or (0xFF shl 24)
    private val spillArgb = spillRgb or (0xFF shl 24)
    private val degenerateArgb = degenerateRgb or (0xFF shl 24)
    private val plotBackgroundArgb = plotBackground or (0xFF shl 24)
    private val squareArgb = squareRgb or (0xFF shl 24)
    private val labelArgb = labelRgb or (0xFF shl 24)

    // ---- 场景（形状已知，期望的像素位置全部可以手算出来）----
    // 数据：101 个点，y 从 0 线性升到 1（x 就是下标）；另有一个"溢出"系列，
    //       下标 ≤ 30 处取 1.2、之后落回 0.5。
    // 映射：x 轴窗口 [10, 90] → 每样本 600/80 = 7.5 px；
    //       y 轴窗口 [0, 1] → 值 v 落在 y = 100 + (1 - v) * 400。
    //
    // 由此：屏幕 x 处的样本下标 = 10 + (x - 100) / 7.5，值 = 下标 / 100。
    //   x = 250 → 下标 30 → 值 0.30 → y = 380（翻转的话会落在 y = 220）
    //   x = 400 → 下标 50 → 值 0.50 → y = 300
    // 而值 1.2 → y = 20，在绘图区（y ≥ 100）**上方**——溢出系列靠它钉住裁剪。
    private val chart: Chart = buildChart()

    /**
     * 折返图：**另一张图、另一块绘图区、另一个颜色**，与上面那张互不干扰。
     * 它只为一条断言存在——"线段的两端是两个不同的实例属性"，见 [ZIG_VALUES]。
     */
    private val zigzagChart: Chart = buildZigzagChart()

    /** 折返图里数据下标 → 屏幕 x。 */
    private fun zigX(index: Double): Double =
        ZIG_PLOT_X + (index - ZIG_WINDOW_MIN) / (ZIG_WINDOW_MAX - ZIG_WINDOW_MIN) * ZIG_PLOT_W

    /** 折返图里数值 → 屏幕 y（与 ChartRenderLayout 同一条映射，值越大越靠上）。 */
    private fun zigY(value: Double): Double = ZIG_PLOT_Y + (1.0 - value) * ZIG_PLOT_H

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Chart Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 造图：一个斜坡系列 + 一个溢出系列 + 一个"线宽为 0"的反证系列。
     *
     * <p>三个系列**同图同绘图区**，这是刻意的：
     * <ul>
     *   <li>反证系列与斜坡用同一份数据、同一条代码路径，只把线宽设成退化值——
     *       于是"那个位置没有像素"只可能是退化处理的结果，而不是"它压根没被画到"；</li>
     *   <li>溢出系列给裁剪断言一个**肉眼可见**的失败面（见 [SPILL_VALUE]）。</li>
     * </ul>
     */
    private fun buildChart(): Chart {
        val ramp = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (POINTS - 1).toDouble(), "样本", ""),
                AxisRange(0.0, 1.0, "值", "")
            ),
            arrayOf(
                DoubleArray(POINTS) { it.toDouble() },
                DoubleArray(POINTS) { it.toDouble() / (POINTS - 1) }
            )
        )
        val spill = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (POINTS - 1).toDouble(), "样本", ""),
                AxisRange(0.0, 2.0, "值", "")
            ),
            arrayOf(
                DoubleArray(POINTS) { it.toDouble() },
                DoubleArray(POINTS) { if (it <= SPILL_UNTIL) SPILL_VALUE else SPILL_LOW }
            )
        )
        val xAxis = Axis(AxisType.LINEAR, ramp.axisRange(0))
            .setDisplayLength(PLOT_W.toDouble())
            .setWindow(WINDOW_MIN, WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, ramp.axisRange(1))
            .setDisplayLength(PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("主")
            .add(Series("斜坡", ramp, ChartType.LINE).color(rampArgb).lineWidth(4f))
            .add(Series("溢出", spill, ChartType.LINE).color(spillArgb).lineWidth(4f))
            .add(Series("退化", ramp, ChartType.LINE).color(degenerateArgb).lineWidth(0f))
        return chart
    }

    /**
     * 造折返图：5 个点、x 均匀铺满窗口、y 在 0 与 1 之间大幅折返。
     *
     * <p>点数刻意少（4 段）且值刻意陡，理由见 [ZIG_VALUES]。
     */
    private fun buildZigzagChart(): Chart {
        val data = ArrayChartData(
            arrayOf(
                AxisRange(ZIG_WINDOW_MIN, ZIG_WINDOW_MAX, "样本", ""),
                AxisRange(0.0, 1.0, "值", "")
            ),
            arrayOf(
                DoubleArray(ZIG_VALUES.size) { it.toDouble() },
                ZIG_VALUES.copyOf()
            )
        )
        val xAxis = Axis(AxisType.LINEAR, data.axisRange(0))
            .setDisplayLength(ZIG_PLOT_W.toDouble())
            .setWindow(ZIG_WINDOW_MIN, ZIG_WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, data.axisRange(1))
            .setDisplayLength(ZIG_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("折返").add(Series("折返", data, ChartType.LINE).color(zigArgb).lineWidth(4f))
        return chart
    }

    /**
     * 一帧的场景。**它是逐帧变化的**（探测方块在动），原因见 [movingSquareX]。
     */
    private fun drawScene(gc: Gc) {
        try {
            val n = frame++

            // 1) 绘图区底色（Gc 画的普通图元）
            gc.fill = plotBackgroundArgb
            gc.fillRect(PLOT_X.toFloat(), PLOT_Y.toFloat(), PLOT_W.toFloat(), PLOT_H.toFloat())

            // 2) 跨帧状态泄漏探针：一个每帧换位置的方块，落在绘图区**之外**
            gc.fill = squareArgb
            gc.fillRect(movingSquareX(n), 540f, SQUARE.toFloat(), SQUARE.toFloat())

            // 3) 让网格落定，再把数据系列画在其上。这就是 Gc.flush() 存在的理由
            //    （见它的文档）：不 flush 的话数据系列只能整个画在 Gc 内容之上或之下。
            gc.flush()
            gc.charts.draw(chart, Rect(PLOT_X.toFloat(), PLOT_Y.toFloat(),
                PLOT_W.toFloat(), PLOT_H.toFloat()), gc.width, gc.height)
            // 3b) 第二张图。同一帧里画两张图也是顺带被覆盖到的用法。
            gc.charts.draw(zigzagChart, Rect(ZIG_PLOT_X, ZIG_PLOT_Y, ZIG_PLOT_W, ZIG_PLOT_H),
                gc.width, gc.height)

            // 4) 标注：在图表**之后**画的普通图元。它必须盖在数据系列之上——
            //    这一条钉住的是"图表是当场就画的"，见 [LABEL_X] 的说明。
            gc.fill = labelArgb
            gc.fillRect(LABEL_X, LABEL_Y, LABEL_W, LABEL_H)
        } catch (t: Throwable) {
            drawError = t
        }
    }

    /**
     * 移动方块的左上角 x：每帧在两个位置之间来回。
     *
     * <p>方块必须动：静止的场景里，"上一帧的像素留在了画面上"这类缺陷会被本帧原样
     * 覆盖掉，**画面看起来完全正常**——而同一帧里的像素计数却是翻倍的。这与
     * PipelineVerifier 的 flush 探针是同一个教训：静态场景的校验器有盲区。
     *
     * <p><strong>它探不出什么（实测过，别指望它）。</strong>写这条时以为它能抓住
     * "图表渲染器忘了还原 `GL_SCISSOR_TEST`"——`glClear` 是受裁剪测试影响的，
     * 于是下一帧的 `glClear` 会被裁到绘图区，绘图区外留下鬼影。**实测不成立**：
     * 在一帧的最后故意 `glEnable(GL_SCISSOR_TEST)` + `glScissor`，下一帧仍然干干净净，
     * 因为 JavaFX/Prism 在我们的两帧之间用同一个 GL 上下文渲染场景、期间会重设 GL 状态。
     * 所以这条断言不是裁剪状态的守卫（那条纪律靠的是渲染器自己成对地还原状态，
     * 因为管线的可组合性需要它）。
     *
     * <p><strong>它探得出什么</strong>：画面里"上一帧的东西"留下的任何其他痕迹
     * （例如图表的绘制落到别的帧缓冲上、某处少清了一块），以及 `Gc` 的图元
     * 恰好各画一次——多于或少于 1600 个像素都会失败。
     */
    private fun movingSquareX(frame: Int): Float = if (frame % 2 == 0) 20f else 80f

    /**
     * `onRender` 回调的入口：把校验体包进 try/catch。
     *
     * <p>校验过程本身抛出异常时必须**以非零码退出**。
     *
     * <p>理由不是洁癖：这部分代码在 GL 线程上跑，一旦抛出去，线程死掉、
     * 汇总行与 `exitProcess` 都走不到，JVM 会因为「最后一个非守护线程结束」而
     * 以 0 退出——**一个已经打印了 FAIL 的校验器报出退出码 0**，
     * 正是本仓库最忌讳的那种「静默的绿」。三个既有校验器都栽在同一个坑里过，
     * `drawError` 那条也是同一个坑的另一半：绘制期间的异常若被 GL 事件循环吞掉，
     * 表现就是"什么都没画，但退出码 0"。
     */
    private fun verifyOnce() {
        try {
            verifyAll()
        } catch (t: Throwable) {
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun verifyAll() {
        val bridge = transfer ?: return
        if (frame < 5) return
        drawError?.let { throw it }
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return

        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        println("=== JFGL 图表绘制后端像素校验（冒烟） ===")
        println("帧缓冲 ${w}x$h  GL_FRAMEBUFFER_BINDING=${glGetInteger(GL_FRAMEBUFFER_BINDING)}")

        // glReadPixels 行序自下而上；用户坐标 y 向下，故翻转回读行号。
        // 口径是**不含 alpha 的 RGB**：拿 ARGB 去比永远不相等，而看起来像"颜色画错了"。
        fun pixelAt(x: Int, y: Int): Int {
            val i = ((h - 1 - y) * w + x) * 4
            return ((buf.get(i).toInt() and 0xFF) shl 16) or
                    ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                    (buf.get(i + 2).toInt() and 0xFF)
        }

        fun countIn(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                    if (pixelAt(x, y) == rgb) n++
                }
            }
            return n
        }

        // 一次全图扫描，得到每种颜色各有多少像素。移动方块留下的鬼影、多出来的杂散色，
        // 都是在这一趟里数出来的（绘图区外的越界像素另有一个带坐标的版本，见下面）。
        val counts = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixelAt(x, y)
                counts[c] = (counts[c] ?: 0) + 1
            }
        }

        val failures = ArrayList<String>()
        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        // ---- 1. 折线确实画出来了，而且画在它该在的位置 ----
        println("\n-- 折线：画出来了，且位置正确 --")
        // 断言的量必须是**位置**，不能只是"画面里有绿色"：画歪了、整体平移、
        // 少画半条，对一个"有没有绿像素"的断言统统成立——那是橡皮图章。
        // 窗口 [10,90]、每样本 7.5 px 决定了斜坡必须**恰好**穿过这两个点：
        //   x=250 → 下标 30 → 值 0.30 → y = 380
        //   x=400 → 下标 50 → 值 0.50 → y = 300
        val centerBox = countIn(396, 296, 404, 304, rampRgb)
        report("绘图区中心附近有该系列颜色的像素", centerBox >= 15,
            "以 (400,300) 为中心的 9x9 里 ${centerBox} px（期望 ≥15，线宽 4）")
        report("斜坡恰好穿过 (250,380)",
            pixelAt(250, 380) == rampRgb,
            "(250,380) = #%06X，期望 #%06X".format(pixelAt(250, 380), rampRgb))

        // ---- 2. y 轴方向：值越大越靠上，不是镜像 ----
        // 斜坡本身是**对称**的：翻转 y 之后它看起来仍是同一条线（只是镜像），
        // 所以"中心有像素"对翻转同样成立。必须挑一个**不对称**的位置来钉：
        // x=250 处 y=380（正确）与 y=220（翻转后）两者只能有一个是线。
        println("\n-- y 轴方向：值越大越靠上 --")
        report("x=250 处的线在 y=380 而不是 y=220",
            pixelAt(250, 220) == plotBackground,
            "(250,220) = #%06X，期望绘图区底色 #%06X".format(
                pixelAt(250, 220), plotBackground))

        // ---- 3. 裁剪：绘图区外一个像素都没有 ----
        println("\n-- 裁剪：绘图区外无越界像素 --")
        fun isInPlot(x: Int, y: Int) =
            x >= PLOT_X && x < PLOT_X + PLOT_W && y >= PLOT_Y && y < PLOT_Y + PLOT_H

        fun countOutside(rgb: Int): Int {
            var n = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (!isInPlot(x, y) && pixelAt(x, y) == rgb) n++
                }
            }
            return n
        }

        // 溢出系列的[高值段]整个画在绘图区**上方**（值 1.2 > y 窗口上界 1.0）。
        // 两条断言必须成对：只有"外面没有"是恒真的（不画也成立），
        // 只有"里面有"也一样（画穿了也成立）。
        report("溢出系列在绘图区内存在（否则下一条恒真）",
            countIn(PLOT_X, PLOT_Y, PLOT_X + PLOT_W - 1, PLOT_Y + PLOT_H - 1, spillRgb) > 0,
            "绘图区内 ${countIn(PLOT_X, PLOT_Y, PLOT_X + PLOT_W - 1, PLOT_Y + PLOT_H - 1, spillRgb)} px")
        report("溢出系列：绘图区外一个像素都没有", countOutside(spillRgb) == 0,
            "绘图区外 ${countOutside(spillRgb)} px（漏掉裁剪测试时这里会有几百 px）")

        // 斜坡本身也要复核一遍：它的两端（下标 0 与 100）在窗口之外，不该被画出来。
        report("斜坡：绘图区外没有该颜色的像素", countOutside(rampRgb) == 0,
            "绘图区外 ${countOutside(rampRgb)} px")
        report("斜坡该颜色在画面里存在（否则上一条恒真）", (counts[rampRgb] ?: 0) > 0,
            "全画面 ${counts[rampRgb] ?: 0} px")

        // ---- 4. 反证：线宽 0 的系列一个像素都不该留下 ----
        println("\n-- 反证：退化系列不画任何东西 --")
        // 与主系列同一份数据、同一个绘图区、同一条代码路径，只有 lineWidth 不同。
        // 这条断言的存在意义是证明上面三条**不是恒真的**：若渲染器对"0 宽"没有正确退化，
        // 这里就会数到像素，而那意味着真正的几何（半宽、属性偏移）也没被验证。
        report("线宽 0 的系列：全画面一个像素都没有", (counts[degenerateRgb] ?: 0) == 0,
            "退化色像素 ${counts[degenerateRgb] ?: 0}")

        // ---- 5. 动态场景：画面里没有上一帧的残留 ----
        println("\n-- 动态场景：绘图区外的移动方块 --")
        // 方块每帧换位置，所以"上一帧的方块"一旦残留下来就盖不住：像素数翻倍。
        // 它探得出什么、探不出什么，见 [movingSquareX]——**实测它不是裁剪状态的守卫**。
        val squarePixels = counts[squareRgb] ?: 0
        report("移动方块不多不少 $SQUARE×$SQUARE（无鬼影）", squarePixels == SQUARE * SQUARE,
            "实际 $squarePixels，期望 ${SQUARE * SQUARE}")

        // ---- 6. 没有 GL 错误 ----
        // 多余的 draw call / 打不进去的 uniform 都不会让画面变坏，但会在这里留下痕迹。
        val err = glGetError()
        println("\n-- GL 错误 --")
        report("glGetError() == 0", err == 0, "glGetError=$err")

        // ---- 7. z 序：图表之后画的 Gc 图元盖得住数据系列 ----
        println("\n-- z 序：标注盖在数据之上（图表是当场就画的）--")
        // 两条成对：标注没被折线穿过去，而且它铺满了整块矩形。
        // 反过来的失败模式（图表把顶点攒进 Gc 的写入器、等 endFrame 才提交）在这里
        // 会让折线横穿标注——而"折线上有个洞"与"折线本来就这样"肉眼分不开。
        val labelArea = (LABEL_W * LABEL_H).toInt()
        report("标注矩形完整可见（没有被折线穿过去）",
            countIn(LABEL_X.toInt(), LABEL_Y.toInt(),
                LABEL_X.toInt() + LABEL_W.toInt() - 1, LABEL_Y.toInt() + LABEL_H.toInt() - 1,
                labelRgb) == labelArea,
            "标注色 ${counts[labelRgb] ?: 0} px，期望 $labelArea")
        report("标注覆盖之处没有折线像素（否则说明它被盖在了下面）",
            countIn(LABEL_X.toInt(), LABEL_Y.toInt(),
                LABEL_X.toInt() + LABEL_W.toInt() - 1, LABEL_Y.toInt() + LABEL_H.toInt() - 1,
                rampRgb) == 0,
            "标注矩形内折线像素 ${
                countIn(LABEL_X.toInt(), LABEL_Y.toInt(),
                    LABEL_X.toInt() + LABEL_W.toInt() - 1, LABEL_Y.toInt() + LABEL_H.toInt() - 1,
                    rampRgb)
            }")

        // ---- 8. 线段的两端确实是两个不同的实例属性 ----
        println("\n-- 实例属性：线段两端的 y 是两次不同的取值 --")
        // 守的是 LineSeriesRenderer.configureDataAttributes 里的第二行：
        //     glVertexAttribPointer(2, 1, GL_FLOAT, false, Float.BYTES, Float.BYTES);
        //                                   偏移 ─────────────────────────┘
        // 把那个偏移写成 0L，两个属性就都指向 y[k]，折线退化成"每个样本一段水平小横线"。
        //
        // **上面那两条位置断言抓不到它**：坏实现的横线仍然穿过每个样本点，
        // 而 (250,380) 与 (400,300) 恰好都是那些横线的顶点（实测确认过）。
        // 能分开两种实现的地方是**相邻两点的中点**（实测：变异后这两条都失败，
        // 而上面那些位置断言全部照常通过）：
        //   正确 → 那里是两端点的平均值（线段从这儿斜着过去）；
        //   坏掉 → 那里什么都没有，线在**左端点的高度**上横着走。
        //
        // 两条断言必须成对，各自防的退化不同：
        //   只有"中点有像素" → "把横线画在左端点高度、**同时**把正确的斜线也画出来"
        //                     这类实现照样通过；
        //   只有"左端点高度没有像素" → "整条线一个像素都没画"照样通过。
        // 单看任何一条都是橡皮图章。
        //
        // 取第 1→2 段（值 1.0 → 0.0），几何全部可以手算：
        //   p0 = (462.5, 505)、p1 = (575, 595) → 中点 x = 518、y = 550；
        //   左端点的高度 = 505。两者相差 45 px，不是斜坡那种 2 px。
        val segMidX = ((zigX(1.0) + zigX(2.0)) / 2.0).toInt()
        val segMidY = ((zigY(1.0) + zigY(0.0)) / 2.0).toInt()
        val segLeftY = zigY(1.0).toInt()
        val midHit = countIn(segMidX - 2, segMidY - 3, segMidX + 3, segMidY + 3, zigRgb)
        report("折返段的中点处有折线（正确实现：两端的平均值）", midHit >= 10,
            "($segMidX,$segMidY) 附近 ${midHit} px，期望 ≥10")
        val wrongHit = countIn(segMidX - 2, segLeftY - 2, segMidX + 3, segLeftY + 2, zigRgb)
        report("折返段的**左端点高度**处没有折线（坏实现会把水平小横线画在这里）",
            wrongHit == 0,
            "($segMidX,$segLeftY) 附近 ${wrongHit} px，期望 0——" +
                    "非 0 说明线段两端取的是同一个 y（第二个实例属性的偏移写成了 0）")

        // ---- 9. 画面的颜色集合恰好是预期的那几个 ----
        // "只数了那几种颜色"管不住"多出来一种颜色"；这条管住了。
        // 退化色不在集合里，正是因为它一个像素都不该剩下。
        println("\n-- 颜色集合 --")
        val expectedColors =
            setOf(background, plotBackground, rampRgb, spillRgb, squareRgb, labelRgb, zigRgb)
        report("画面只有这 7 种颜色（无杂散像素）", counts.keys == expectedColors,
            "实际 ${counts.keys.sorted().joinToString { "#%06X".format(it) }}")

        println("\n画面出现的颜色：${counts.keys.sorted().joinToString { "#%06X".format(it) }}")
        println("背景 ${counts[background] ?: 0} px，绘图区底色 ${counts[plotBackground] ?: 0} px")
        println("斜坡 ${counts[rampRgb] ?: 0} px，溢出 ${counts[spillRgb] ?: 0} px")

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    override fun stop() {
        transfer?.dispose()
    }

    private companion object {
        /** 移动方块的边长。 */
        const val SQUARE = 40
    }
}
