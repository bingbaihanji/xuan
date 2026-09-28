package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.chart.ArrayChartData
import com.bingbaihanji.xuan.chart.Axis
import com.bingbaihanji.xuan.chart.AxisRange
import com.bingbaihanji.xuan.chart.AxisStyle
import com.bingbaihanji.xuan.chart.AxisType
import com.bingbaihanji.xuan.chart.Chart
import com.bingbaihanji.xuan.chart.ChartInsets
import com.bingbaihanji.xuan.chart.ChartLayout
import com.bingbaihanji.xuan.chart.ChartTextMetrics
import com.bingbaihanji.xuan.chart.ChartType
import com.bingbaihanji.xuan.chart.Series
import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.util.Rect
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glReadPixels
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/** 窗口的**逻辑**尺寸。绘制区的设备像素尺寸由系统缩放决定。 */
private const val SCENE_W = 820.0
private const val SCENE_H = 620.0

/** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是 (0.2,0.2,0.2)。 */
private const val BG = 0xFF18212B.toInt()

/** 系列色。与下面三个轴色**互不相同**（本校验器靠精确颜色比对来数像素）。 */
private const val SERIES = 0xFF4FC3F7.toInt()

/** `AxisStyle.defaults()` 里那三个颜色——判据要用**默认样式**，所以这里独立抄一份做对照。 */
private const val GRID = 0xFF394452.toInt()
private const val AXIS = 0xFFE3E8EF.toInt()
private const val LABEL = 0xFFC7D0DB.toInt()

/** 绘图区外框（设备像素坐标下的那一块）。留出四周空间给刻度文字与箭头。 */
private val FRAME = Rect(60f, 50f, 660f, 500f)

/**
 * 坐标系（网格 / 轴线 / 箭头 / 刻度线 / 刻度文字）的像素校验。
 *
 * <h2>它证的三件事</h2>
 *
 * 1. **默认关是逐像素无操作**：关着的那一帧里，三个轴色**一个像素都没有**。
 *    这是"这个特性纯增量"的直接证据——`ChartVerifier` 那 168 条断言不动只是间接证据。
 * 2. **开着的时候真的按刻度画**：网格竖线数 == 绘图区**内部**的主刻度数、
 *    横线数同理。判据是"数出来的等于同一份 `ticks()` 算出来的"，
 *    **不是"有 5 条线"**——后者在刻度算法变了以后会变成一条恒假断言。
 * 3. **四个部件各自都在**：箭头在轴端上方、刻度文字在预留带里。
 *
 * <h2>扫描为什么绕开网格线本身</h2>
 *
 * 扫一行去数竖线时，如果那一行正好落在一条**横**网格线上，整行都会是网格色。
 * 所以两个扫描位置都取"相邻两条正交刻度线的中点"，那里保证没有正交线穿过。
 */
@JvmName("main")
@Suppress("unused")
fun axisVerifierMain() {
    Application.launch(AxisVerifierApp::class.java)
}

class AxisVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 已提交的帧数。帧 0 = 关轴（对照），帧 1 = 开轴（**在它上面断言**）。 */
    private var rendered = 0

    /** 关着的那一帧里数到的三个轴色像素数（用来证"默认关是无操作"）。 */
    private var offGrid = -1
    private var offAxis = -1
    private var offLabel = -1

    override fun start(stage: Stage) {
        // 采样数走同一个系统属性（解析与 MsaaVerifier 共用），否则下面那道守卫是死代码。
        val bridge = FXGLTransfer(msaa = readRequestedMsaa())
        // 本校验器的读数全部来自 glReadPixels，多采样画布上那次调用是非法操作。
        requirePixelReadback(bridge)
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "Xuan Axis Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.show()
    }

    /** 画一帧。**在 GL 线程上执行**。帧 0 关轴、之后开轴。 */
    private fun drawScene(gc: Gc) {
        gc.pickId = 0
        gc.fill = BG
        gc.fillRect(0f, 0f, gc.width.toFloat(), gc.height.toFloat())

        val metrics = GcMetrics(gc)
        val chart = buildChart(axisOn = rendered >= 1, metrics = metrics)
        // displayLength 必须与绘图区一致：它是"数据值 → 显示位置"的比例基准。
        val plot = ChartLayout.compute(chart, FRAME, metrics).plotRect()
        chart.axis(0).setDisplayLength(plot.width.toDouble())
        chart.axis(1).setDisplayLength(plot.height.toDouble())

        // 图表是当场就画的，而背景在 Gc 的批次里等提交 ⇒ 先 flush 才能让背景垫在底下。
        gc.flush()
        gc.charts.drawChart(chart, FRAME, gc.width, gc.height)
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        val just = rendered
        rendered++
        val gc = bridge.gc() ?: return
        try {
            if (just == 0) {
                // 关轴那一帧：只抓读数，不断言。
                val shot = Shot(frameOf(bridge))
                offGrid = shot.count(GRID)
                offAxis = shot.count(AXIS)
                offLabel = shot.count(LABEL)
                return
            }
            if (just == 1) {
                verifyAll(bridge, gc)
            }
        } catch (t: Throwable) {
            // 校验代码本身抛异常时必须以非零码退出：它在 GL 线程上跑，抛出去会让
            // 汇总行与 exitProcess 都走不到，JVM 以 0 退出 —— 一个"打印了 FAIL 却报绿"
            // 的校验器，正是本仓库最忌讳的那种静默。
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun frameOf(bridge: FXGLTransfer) = Shot.Size(bridge.scaledWidth, bridge.scaledHeight)

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    private fun verifyAll(bridge: FXGLTransfer, gc: Gc) {
        println("=== Xuan 坐标系像素校验（帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}）===")
        val failures = ArrayList<String>()
        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        // ---- 0. 纯计算：默认关 ----
        println("\n-- 0. 默认值 --")
        report("★ 前提：AxisStyle 的默认是**关**", !AxisStyle.defaults().visible,
            "defaults().visible()=${AxisStyle.defaults().visible}（改成 true 会让 ChartVerifier 的" +
                    "现有像素断言整批倒下——那是这次改动唯一的非破坏性保证）")

        // ---- 1. 关轴那一帧：三个轴色一个像素都没有 ----
        println("\n-- 1. 默认关是逐像素无操作 --")
        report("★ 关轴时网格色像素数为 0", offGrid == 0, "实测 $offGrid 个")
        report("★ 关轴时轴线色像素数为 0", offAxis == 0, "实测 $offAxis 个")
        report("★ 关轴时刻度文字色像素数为 0", offLabel == 0, "实测 $offLabel 个")

        // ---- 2. 开轴那一帧 ----
        val metrics = GcMetrics(gc)
        val chart = buildChart(axisOn = true, metrics = metrics)
        val plot = ChartLayout.compute(chart, FRAME, metrics).plotRect()
        chart.axis(0).setDisplayLength(plot.width.toDouble())
        chart.axis(1).setDisplayLength(plot.height.toDouble())

        // 开启轴之后绘图区**必须变小**（预留真的扣掉了）。
        val offPlot = ChartLayout.compute(buildChart(axisOn = false, metrics = metrics),
            FRAME, metrics).plotRect()
        val style = AxisStyle.defaults()
        val expectReserve = style.tickLength() + style.tickLabelFontSize() * ChartLayout.LINE_HEIGHT_FACTOR
        report("★ 开轴后绘图区在下方让出预留", kotlin.math.abs(
            (offPlot.height - plot.height) - expectReserve) < 0.01f,
            "关轴高 ${offPlot.height}、开轴高 ${plot.height}，差 ${offPlot.height - plot.height}，" +
                    "期望 $expectReserve（tickLength + 字号 × LINE_HEIGHT_FACTOR）")
        report("★ 开轴后绘图区在左侧让出预留", kotlin.math.abs(
            (plot.x - offPlot.x) - expectReserve) < 0.01f,
            "左边多出 ${plot.x - offPlot.x}，期望 $expectReserve")

        val shot = Shot(frameOf(bridge))
        // ---- 2.5 诊断：把"画面里到底有什么颜色"印出来 ----
        //
        // 这两行**刻意留着**，不因为它不参与断言就删掉。本校验器初版把四条
        // **本来通过**的断言报成失败（读数恒 0），而唯一看出"功能其实是好的"的手段
        // 就是把画面里的颜色直接印出来——上面那张直方图里 `394452:4913` 明明白白，
        // 而断言那边读到 0（原因是比较的两边一个是 ARGB、一个是 RGB，见 [Shot.key]）。
        // 换句话说：**这两行是把"假失败"和"真缺陷"分开的那件工具**，删了下次还得重写。
        println("  [诊断] 画面颜色计数：BG=${shot.count(BG)} SERIES=${shot.count(SERIES)} " +
                "GRID=${shot.count(GRID)} AXIS=${shot.count(AXIS)} LABEL=${shot.count(LABEL)}")
        println("  [诊断] 最多的 6 种颜色：" +
                shot.topColors(6).joinToString(" ") { "%06X:%d".format(it.first, it.second) })
        val xTicks = chart.axis(0).ticks()
        val yTicks = chart.axis(1).ticks()
        val xLen = chart.axis(0).displayLength()
        val yLen = chart.axis(1).displayLength()

        // 绘图区**内部**的主刻度（两个端点上的与坐标轴重合，本库不画）
        val innerX = xTicks.filter { it.isMajor && it.position() > 0.0 && it.position() < xLen }
        val innerY = yTicks.filter { it.isMajor && it.position() > 0.0 && it.position() < yLen }
        report("前提：两种方向都至少各有 2 条内部主刻度（否则下面的扫描没意义）",
            innerX.size >= 2 && innerY.size >= 2,
            "内部主刻度 x=${innerX.size} y=${innerY.size}")

        shoot@ if (innerX.size >= 2 && innerY.size >= 2) {
            // ---- 3. 网格：竖线数 == 内部主 x 刻度数 ----
            println("\n-- 3. 网格线与刻度一一对应 --")
            // 扫的那一行取"相邻两条**横**网格线的中点"：那里保证没有横线穿过整行。
            val y0 = plot.y + (plot.height - (innerY[0].position() / yLen).toFloat() * plot.height)
            val y1 = plot.y + (plot.height - (innerY[1].position() / yLen).toFloat() * plot.height)
            val scanRow = ((y0 + y1) * 0.5f).toInt()
            val cols = shot.countInRow(GRID, scanRow, plot.x.toInt(), (plot.x + plot.width).toInt())
            report("★ 网格竖线数 == 内部主 x 刻度数", cols == innerX.size,
                "扫第 $scanRow 行：网格色 $cols 列，期望 ${innerX.size}（线宽 ${style.gridWidth()}）")

            // ---- 4. 网格：横线数 == 内部主 y 刻度数 ----
            // 扫的那一列取"相邻两条**竖**网格线的中点"，理由同上。
            val x0 = plot.x + (innerX[0].position() / xLen).toFloat() * plot.width
            val x1 = plot.x + (innerX[1].position() / xLen).toFloat() * plot.width
            val scanCol = ((x0 + x1) * 0.5f).toInt()
            val rows = shot.countInCol(GRID, scanCol, plot.y.toInt(), (plot.y + plot.height).toInt())
            report("★ 网格横线数 == 内部主 y 刻度数", rows == innerY.size,
                "扫第 $scanCol 列：网格色 $rows 行，期望 ${innerY.size}")

            // ---- 5. 箭头：轴端**上方**有轴线色的像素 ----
            //
            // 判据选得刻意：x 轴线本身只占 `bottom` 那一两行，**只有箭头的臂**会伸到
            // 轴线之上。所以"轴线以上有轴线色"这件事，关掉箭头就一定为假。
            println("\n-- 5. 箭头 --")
            val right = (plot.x + plot.width).toInt()
            val bottom = (plot.y + plot.height).toInt()
            val arm = shot.countInBox(AXIS, right - 8, bottom - 4, 8, 3)
            report("★ x 轴末端**上方**有箭头像素（轴线本身伸不到那里）", arm > 0,
                "轴端左上方 8x3 的盒子里轴线色 $arm px")

            // ---- 6. 刻度文字：落在预留带里 ----
            println("\n-- 6. 刻度文字 --")
            val band = shot.countInBox(LABEL, plot.x.toInt(), bottom + 1,
                plot.width.toInt(), expectReserve.toInt())
            report("★ 绘图区下方的预留带里真的有刻度文字", band > 0,
                "预留带里文字色 $band px（带高 $expectReserve）")
        }

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项 ===")
            failures.forEach { println("  ✗ $it") }
        }
        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    /**
     * 造一张图。
     *
     * <p>数据刻意取成一条**低位的水平线**（值 12 / 满量程 100）：它落在绘图区靠下的位置，
     * 而下面两个扫描点都在绘图区上半部与相邻网格线的中点 —— 于是系列色一个像素都不会
     * 落进扫描路径，判据只反映网格。
     */
    private fun buildChart(axisOn: Boolean, metrics: ChartTextMetrics): Chart {
        val xs = DoubleArray(12) { it.toDouble() }
        val ys = DoubleArray(12) { 12.0 }
        val data = ArrayChartData(
            arrayOf(AxisRange(0.0, 11.0, "下标", ""), AxisRange(0.0, 100.0, "数值", "")),
            arrayOf(xs, ys))

        val chart = Chart(
            Axis(AxisType.LINEAR, data.axisRange(0)),
            Axis(AxisType.LINEAR, data.axisRange(1)))

        // 不用标题与图例：它们的文字色与刻度文字色都是浅灰，会把"文字色像素"的计数搅浑。
        chart.legendVisible(false)
        chart.title("")
        chart.padding(ChartInsets.NONE)
        chart.addLayer("数据").add(Series("s", data, ChartType.LINE).color(SERIES))
        chart.axisStyle(if (axisOn) AxisStyle.defaults().visible(true) else AxisStyle.defaults())
        return chart
    }

    override fun stop() {
        transfer?.dispose()
    }

    /**
     * 把 `Gc` 当成度量尺给 `ChartLayout` 用。
     *
     * <p>与 `Main.java` / `DemoChart` 里那两份是同一件事——**这正是"`Gc` 没公开
     * `ChartTextMetrics`"那条缺口的第三份绕法**（另两份见 `DemoChart.GcTextMetrics`
     * 与 `Main.java` 的匿名类）。它在这里是必需的：没有度量，布局算不出绘图区，
     * 而没有绘图区就没有判据要扫的那些带子。
     */
    private class GcMetrics(private val gc: Gc) : ChartTextMetrics {
        override fun width(text: String, fontSize: Float): Float {
            val old = gc.fontSize
            gc.fontSize = fontSize
            val w = gc.measureText(text)
            gc.fontSize = old
            return w
        }

        override fun lineHeight(fontSize: Float) = fontSize * ChartLayout.LINE_HEIGHT_FACTOR
    }
}

/**
 * 一次帧缓冲回读。
 *
 * <p>`glReadPixels` 的行序**自下而上**，所以 `y` 全按"从底数"解释——
 * 它的消费者只有下面那几个按行/列/框数像素的函数，全部用同一套行号，
 * 不存在"某处忘了翻"的余地。
 */
private class Shot(val size: Size) {

    class Size(val w: Int, val h: Int)

    private val w = size.w
    private val h = size.h
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4)

    init {
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)
    }

    /** 自下而上的行号取 RGB（忽略 alpha：本场景不透明）。 */
    private fun rgbAt(x: Int, yUp: Int): Int {
        if (x < 0 || yUp < 0 || x >= w || yUp >= h) return 0
        val i = (yUp * w + x) * 4
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * 本类**对外一律用"原点左上、y 向下"的行号**（与整条管线、与 `ChartLayout` 一致），
     * 只在这一个地方翻成自下而上。
     *
     * <p>⚠️ **这个翻转是必需的，而且漏掉它的症状是"读数恒为 0"**：初版
     * `countInRow` / `countInCol` 直接把自己的行号交给了 [rgbAt]，于是三个扫描点
     * 全落在镜像位置上——四条像素断言一起报 0，看起来像"轴压根没画出来"。
     */
    private fun flip(yTopDown: Int) = h - 1 - yTopDown

    /**
     * 把调用方给的 {@code 0xAARRGGBB} 常量削成 24 位，与 [rgbAt] 的返回值同一口径。
     *
     * <p>⚠️ **这个削位同样是必需的，而且漏掉它的症状与上面那条一模一样：读数恒为 0。**
     * 本场景画的全是不透明色（alpha = 0xFF），而 [rgbAt] 只取 RGB 三个字节；
     * 拿 {@code 0xFF18212B} 去比 {@code 0x18212B} 永远不相等。
     * 初版因此把四条**本来通过**的断言报成失败——而"功能其实是好的"这件事，
     * 只有靠 [topColors] 那种**直接印出画面里有什么**的诊断才看得出来
     * （印出来就看到 {@code 394452:4913} 明明在那儿）。
     * 这是本仓库反复吃的那一类亏：**比较的两边口径不同，而结果看起来像缺陷。**
     */
    private fun key(argb: Int) = argb and 0xFFFFFF

    /** 整幅里等于该颜色（{@code 0xAARRGGBB}，只比 RGB）的像素数。 */
    fun count(rgb: Int): Int {
        val k = key(rgb)
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (rgbAt(x, y) == k) n++
        return n
    }

    /** 出现次数最多的几种颜色（诊断用）。 */
    fun topColors(n: Int): List<Pair<Int, Int>> {
        val m = HashMap<Int, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val c = rgbAt(x, y)
            m[c] = (m[c] ?: 0) + 1
        }
        return m.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    /** 某一行里、列区间内的像素数。行号是**原点左上**的。 */
    fun countInRow(rgb: Int, rowTopDown: Int, x0: Int, x1: Int): Int {
        val k = key(rgb)
        return (x0 until x1).count { rgbAt(it, flip(rowTopDown)) == k }
    }

    /** 某一列里、行区间内的像素数。行号是**原点左上**的。 */
    fun countInCol(rgb: Int, col: Int, y0TopDown: Int, y1TopDown: Int): Int {
        val k = key(rgb)
        return (y0TopDown until y1TopDown).count { rgbAt(col, flip(it)) == k }
    }

    /** 一个矩形里（左上角 x,y + 宽高，**设备像素、原点左上**）的像素数。 */
    fun countInBox(rgb: Int, x: Int, yTop: Int, w: Int, h: Int): Int {
        val k = key(rgb)
        var n = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            if (rgbAt(x + xx, flip(yTop + yy)) == k) n++
        }
        return n
    }
}
