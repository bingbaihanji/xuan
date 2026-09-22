package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.chart.ArrayChartData
import com.bingbaihanji.jfgl.chart.Axis
import com.bingbaihanji.jfgl.chart.AxisRange
import com.bingbaihanji.jfgl.chart.AxisType
import com.bingbaihanji.jfgl.chart.Chart
import com.bingbaihanji.jfgl.chart.ChartType
import com.bingbaihanji.jfgl.chart.RingChartData
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
 * <p>本文件是 Task 10 + Task 11 的冒烟收口（前 15 条断言：折线画出来了、位置对、
 * 裁剪生效、退化系列不画、z 序、实例属性偏移、颜色集合），Task 12 在它之上补了
 * 四组断言——<b>这四组各自守着一件"像素看不出来"的事</b>：
 *
 * <ol>
 *   <li><b>本帧上传了多少字节</b>（[STREAM_POINTS_PER_FRAME] 那一节）。整个 ② 的性能主张是
 *       "每帧只上传新增的点"，而增量上传与每帧全量重传<b>画出来的图逐像素相同</b>——
 *       前 15 条一条也分不开它们。这是四组里唯一"断言的量就是那件事本身"的一条。</li>
 *   <li><b>缺口必须断开折线</b>（[GAP_VALUES] 那一节）。"缺口处没有像素"是唯一能把
 *       "遇到 NaN 就断开"与"把缺口连过去"分开的量——连过去之后波形看起来完全正常。</li>
 *   <li><b>某条曲线消失后，它原来的位置必须干净</b>（[CROSS_REMOVE_FRAME] 那一节）。
 *       这是吸取 {@code PickVerifier} 的教训：它 24 条断言全绿却漏掉一个真缺陷，
 *       因为<b>它的场景每帧完全相同</b>。</li>
 *   <li><b>onFrame 的异常也要让退出码非 0</b>（[runFrame] 那一节）。GL 线程上一个
 *       没接住的异常会让 JVM 以 0 正常退出，校验器报一个<b>静默的绿</b>。</li>
 * </ol>
 *
 * <h2>观察期与校验期</h2>
 *
 * <p>第 1 条需要"滚动若干帧"才成立（一帧是量不出增量的），第 3 条需要"前后两帧"，
 * 所以本校验器不再是"到第 5 帧就断言"：前 [STREAM_FRAMES] 帧是<b>观察期</b>
 * （三张实验图每帧都画、逐帧记数、按需抓快照），之后场景回到 Task 10/11 那个原样的场景，
 * 在 [TOTAL_FRAMES] 帧上做全部断言。这样前 15 条断言的场景与期望值<b>一字未改</b>。
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

// ---------------------------------------------------------------------------
// Task 12：三张实验图各自占一块独立的绘图区（互不重叠，也不压住上面那些）
// ---------------------------------------------------------------------------

/**
 * 观察期的帧数：前这么多帧里三张实验图每帧都画，之后场景回到 Task 10/11 的原样。
 *
 * <p>100 帧不是随手取的：它要让"每帧传 10 个点"量够多次（一次不算数），
 * 又要让总量 100 × 10 = 1000 落在 [STREAM_CAPACITY] = 2^10 里——
 * 这样测量期内环不滑动，"增量"与"全量"的差别只来自**上传策略**，
 * 不掺进跨环绕那一套（那套由 {@code SeriesBufferTest} 单测钉着）。
 */
private const val STREAM_FRAMES = 100

/**
 * 全部断言在**第几帧**上跑。留 4 帧余量：实验图在观察期结束时就不画了，
 * 那几帧正好顺带验证"上一帧画过的东西这一帧**整体**消失"没有留下任何痕迹
 * （颜色集合那条断言会数出来）。
 */
private const val TOTAL_FRAMES = STREAM_FRAMES + 4

// ---- 一、流式实验：量"本帧上传了多少字节" ----

/**
 * 流式实验的绘图区：左上角一块空地，x 20..320、y 10..90。
 * 帧缓冲是 988x738，主绘图区从 y=100 起，所以这里干净。
 */
private const val STREAM_PLOT_X = 20f
private const val STREAM_PLOT_Y = 10f
private const val STREAM_PLOT_W = 300f
private const val STREAM_PLOT_H = 80f

/** 流式数据的环容量（2 的幂）。 */
private const val STREAM_CAPACITY = 1 shl 10

/**
 * 每帧追加的样本数 K。
 *
 * <p><strong>整个第一组断言的判别力就来自这个数</strong>：正确实现每帧上传
 * {@code K * 4 = 40} 字节；每帧全量重传则会传"环里留着的全部样本 × 4"
 * （测量结束时是 1000 × 4 = 4000 字节）。40 与 4000 差 100 倍，
 * 而两种实现画出来的图**逐像素相同**。
 */
private const val STREAM_POINTS_PER_FRAME = 10

/** 流式图 x 轴上同时可见的样本数。窗口每帧跟着写指针滑，模拟真实示波器。 */
private const val STREAM_VISIBLE = 100

/**
 * 每个样本在 GPU 缓冲里占的字节数。
 *
 * <p>`SeriesBuffer` 只存 y 值、存成 float32，所以是 4 —— 这也是 {@code SeriesUploadPlan}
 * 的"每点 4 字节"那一句的同一个数。写成常量是因为下面两条断言的**全部判别力**
 * 都落在"K 乘以这个数"上，散落成字面量 4 的话，改错了没人看得出来。
 */
private const val BYTES_PER_SAMPLE = 4

// ---- 二、缺口实验：NaN 必须断开折线 ----

/**
 * 缺口实验的绘图区：画面最下方，x 20..620、y 610..710。
 * 折返图到 y=595 为止、移动方块在 y=540..580，所以这里不压任何东西。
 */
private const val GAP_PLOT_X = 20f
private const val GAP_PLOT_Y = 610f
private const val GAP_PLOT_W = 600f
private const val GAP_PLOT_H = 100f

/** 缺口实验的样本数（下标 0..10）。 */
private const val GAP_POINTS = 11

/**
 * 缺口实验的数据：**前 7 个点在高位平坦、中间一个 NaN、后 3 个点在低位平坦**。
 *
 * <p>这个形状是为"能把两种实现分开"挑的：
 * <ul>
 *   <li>正确：值 0.9 的 6 段折线照画、值 0.1 的 2 段折线照画，
 *       而**碰到 NaN 的那两段（下标 6→7 与 7→8）整个消失**——缺口正下方是背景色；</li>
 *   <li>连过去：那两段变成一条从高位斜穿到低位的线（或一条从高位掉到 0 的线），
 *       <b>下标 7 的竖直条带上就会出现折线像素</b>。</li>
 * </ul>
 * 所以判别的量是"<b>下标 7 那一条竖直带里有没有折线色</b>"，
 * 而不是"画面里有没有折线"——后者对"连过去了"同样成立，是橡皮图章。
 *
 * <p>两端刻意做成**水平**的平坦段：这样"缺口左边有线"与"缺口右边有线"各自都能
 * 用一条简单的像素断言钉住，于是"整条曲线都没画"这种退化也跑不掉。
 */
private val GAP_VALUES = doubleArrayOf(
    0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, Double.NaN, 0.1, 0.1, 0.1
)

/** 缺口实验里 NaN 所在的下标。下标 7 的竖直条带是判别式所在。 */
private const val GAP_NAN_INDEX = 7

// ---- 三、跨帧实验：某条曲线消失后，它原来的位置必须干净 ----

/**
 * 跨帧实验的绘图区：右上角，x 710..968、y 10..90（主绘图区到 x=700 为止）。
 */
private const val CROSS_PLOT_X = 710f
private const val CROSS_PLOT_Y = 10f
private const val CROSS_PLOT_W = 258f
private const val CROSS_PLOT_H = 80f

/** 跨帧实验的样本数。 */
private const val CROSS_POINTS = 11

/**
 * 从第几帧起，"短暂"那条系列**不再画**（换一张没有它的图，不是改它的数据）。
 *
 * <p>取 60 是让它在观察期正中间：前面有 60 帧它在画面上（快照能抓到它），
 * 后面还有 40 帧它不在，而观察期结束前不会被校验期的场景切换混淆。
 */
private const val CROSS_REMOVE_FRAME = 60

/**
 * 跨帧实验里"短暂"系列的竖直位置（数值），0.8 → 绘图区局部 y = 0.2 × 80 = 16。
 *
 * <p>与常驻系列的 0.2（局部 y = 64）拉开整整 48 px：两者在**竖直方向**上分得开，
 * 于是"移除之后那几行是否干净"可以只看几行像素，不必整块比。
 */
private const val CROSS_TRANSIENT_VALUE = 0.8

/** 跨帧实验里常驻系列的值（数值），0.2 → 局部 y = 64。 */
private const val CROSS_PERSIST_VALUE = 0.2

/** "短暂"系列那条线占的局部行范围（线宽 3 → 半宽 1.5，取 ±6 的余量）。 */
private const val CROSS_TRANSIENT_ROW_LO = 10
private const val CROSS_TRANSIENT_ROW_HI = 22

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

    /**
     * 已完成的帧数。**场景逐帧在变**（见 [movingSquareX] 与三张实验图），
     * 好让跨帧状态泄漏没有藏身处。
     *
     * <p>它在 [runFrame] 的 finally 里递增，所以"绘制抛了异常"也照样推进——
     * 不推进的话校验帧永远到不了，校验器会一直挂着，
     * 而"挂着"在 CI 里与"通过"一样安静（见 [runFrame]）。
     */
    private var frame = 0

    /**
     * 绘制期间抛出的异常。
     *
     * <p>GL 线程上一个没接住的异常会<b>静默吞掉退出码</b>：线程死掉、汇总行与
     * `exitProcess` 都走不到，JVM 因为"最后一个非守护线程结束"而以 0 退出——
     * 一个已经画错了的校验器报出退出码 0。见 [runFrame] 与 [verifyAll]。
     */
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

    // ---- Task 12 的三张实验图的颜色 ----
    // 三者与上面那 7 种、以及彼此，都**两两不同**：颜色集合那条断言靠这一点才有意义。
    // 它们也都不等于退化色 0xFF00FF（那个颜色被断言为"全画面一个像素都没有"）。

    /** 流式实验的折线色。 */
    private val streamRgb = 0x8000FF

    /** 缺口实验的折线色。 */
    private val gapRgb = 0x80FF00

    /** 跨帧实验里"常驻"系列的色。 */
    private val crossPersistRgb = 0x0080FF

    /** 跨帧实验里"短暂"系列的色。它必须在它消失之后一个像素都不剩。 */
    private val crossTransientRgb = 0xFF0080

    private val streamArgb = streamRgb or (0xFF shl 24)
    private val gapArgb = gapRgb or (0xFF shl 24)
    private val crossPersistArgb = crossPersistRgb or (0xFF shl 24)
    private val crossTransientArgb = crossTransientRgb or (0xFF shl 24)

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

    // -----------------------------------------------------------------------
    // Task 12 的三张实验图
    // -----------------------------------------------------------------------

    private val streamRect = Rect(STREAM_PLOT_X, STREAM_PLOT_Y, STREAM_PLOT_W, STREAM_PLOT_H)

    private val gapRect = Rect(GAP_PLOT_X, GAP_PLOT_Y, GAP_PLOT_W, GAP_PLOT_H)

    private val crossRect = Rect(CROSS_PLOT_X, CROSS_PLOT_Y, CROSS_PLOT_W, CROSS_PLOT_H)

    /** 流式数据：环容量 2^10，采集线程就是 GL 线程自己（见 [drawStreamingChart]）。 */
    private val streamData = RingChartData(
        arrayOf(
            AxisRange(0.0, 1.0, "样本", ""),
            AxisRange(-1.0, 1.0, "值", "")
        ),
        STREAM_CAPACITY
    )

    /**
     * 流式图的 x 轴。窗口**每帧**跟着写指针滑——这既像真实示波器，
     * 也让每一帧的绘制区间都在变（静止的场景探不出跨帧残留，见 [movingSquareX]）。
     */
    private val streamXAxis = Axis(AxisType.LINEAR, streamData.axisRange(0))
        .setDisplayLength(STREAM_PLOT_W.toDouble())
        .setWindow(-STREAM_VISIBLE.toDouble(), 0.0)

    private val streamSeries = Series("流式", streamData, ChartType.LINE)
        .color(streamArgb).lineWidth(2f)

    private val streamChart: Chart = buildStreamChart()

    /** 流式图每一帧真正上传的字节数，下标即帧号。第一组断言的全部输入。 */
    private val streamUploadedBytes = ArrayList<Int>(STREAM_FRAMES)

    /** 缺口实验的数据：中间一个 NaN，见 [GAP_VALUES]。 */
    private val gapData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (GAP_POINTS - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(
            DoubleArray(GAP_POINTS) { it.toDouble() },
            GAP_VALUES.copyOf()
        )
    )

    private val gapSeries = Series("缺口", gapData, ChartType.LINE).color(gapArgb).lineWidth(3f)

    private val gapChart: Chart = buildGapChart()

    /** 跨帧实验的数据：11 个点，x 均匀铺满窗口。 */
    private val crossData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (CROSS_POINTS - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(
            DoubleArray(CROSS_POINTS) { it.toDouble() },
            DoubleArray(CROSS_POINTS) { CROSS_PERSIST_VALUE }
        )
    )

    private val crossPersistSeries = Series("常驻", crossData, ChartType.LINE)
        .color(crossPersistArgb).lineWidth(3f)

    /** "短暂"系列要另起一份数据：同一条水平线的话，两个系列会画在同一个高度上。 */
    private val crossTransientData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (CROSS_POINTS - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(
            DoubleArray(CROSS_POINTS) { it.toDouble() },
            DoubleArray(CROSS_POINTS) { CROSS_TRANSIENT_VALUE }
        )
    )

    private val crossTransientSeries = Series("短暂", crossTransientData, ChartType.LINE)
        .color(crossTransientArgb).lineWidth(3f)

    /** 移除之前的那张图：两个系列都在。 */
    private val crossChartWithTransient: Chart = buildCrossChart(true)

    /**
     * 移除之后的那张图：**只有常驻系列**。
     *
     * <p>刻意用"换一张图"而不是改数据：改数据的话那条曲线只是换个高度，
     * 而这里要验的是"它整个从画面上消失，且原来的位置回到背景色"。
     */
    private val crossChartWithout: Chart = buildCrossChart(false)

    // ---- 观察期抓下来的快照（校验期才断言，见 [captureObservations]）----

    private var streamSnapshot: Shot? = null

    private var gapSnapshot: Shot? = null

    private var crossSnapshotBefore: Shot? = null

    private var crossSnapshotAfter: Shot? = null

    /**
     * 一块区域的像素快照：RGB（不含 alpha）、行优先、**从上到下**。
     *
     * <p>做成一个小类而不是 `IntArray`，是因为断言要按 (x, y) 或按行取值，
     * 而裸数组的下标算式（`y * w + x`）散在断言里写错一次就是"数了别的地方的像素"——
     * 那种失败看起来像"渲染错了"。
     */
    private class Shot(val w: Int, val h: Int, val px: IntArray) {

        fun at(x: Int, y: Int): Int = px[y * w + x]

        fun count(rgb: Int): Int = px.count { it == rgb }

        fun countIn(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                    if (at(x, y) == rgb) n++
                }
            }
            return n
        }

        /** 某个局部行区间里不是给定颜色的像素数。 */
        fun countNonBackgroundInRows(y0: Int, y1: Int, rgb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
                for (x in 0 until w) {
                    if (at(x, y) != rgb) n++
                }
            }
            return n
        }

        /** 两张快照是不是同一张图。 */
        fun sameAs(other: Shot): Boolean =
            w == other.w && h == other.h && px.contentEquals(other.px)
    }

    /**
     * 造流式图。x 轴是 [streamXAxis]（每帧改窗口），y 轴范围 [-1, 1]。
     */
    private fun buildStreamChart(): Chart {
        val chart = Chart(
            streamXAxis,
            Axis(AxisType.LINEAR, streamData.axisRange(1))
                .setDisplayLength(STREAM_PLOT_H.toDouble())
        )
        chart.addLayer("流式").add(streamSeries)
        return chart
    }

    /**
     * 造缺口图：11 个点、一段高位平坦 + 一个 NaN + 一段低位平坦，见 [GAP_VALUES]。
     *
     * <p>x 轴窗口就是数据范围（每样本 600/10 = 60 px），于是"下标 7 的竖直条带"
     * 的屏幕位置可以手算：{@code 20 + 7 × 60 = 440}。
     */
    private fun buildGapChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, gapData.axisRange(0))
            .setDisplayLength(GAP_PLOT_W.toDouble())
            .setWindow(0.0, (GAP_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, gapData.axisRange(1))
            .setDisplayLength(GAP_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("缺口").add(gapSeries)
        return chart
    }

    /**
     * 造跨帧图。两张图各自有独立的轴（轴是可变的，不能共用），
     * 但**共用同一批 Series 对象**——这正是要验的："这个系列还画不画"。
     *
     * @param withTransient 是否带上那条稍后会消失的系列
     */
    private fun buildCrossChart(withTransient: Boolean): Chart {
        val xAxis = Axis(AxisType.LINEAR, crossData.axisRange(0))
            .setDisplayLength(CROSS_PLOT_W.toDouble())
            .setWindow(0.0, (CROSS_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, crossData.axisRange(1))
            .setDisplayLength(CROSS_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        val layer = chart.addLayer("跨帧")
        // 常驻系列在两张图里都注册；短暂系列只在前一张里。
        layer.add(crossPersistSeries)
        if (withTransient) {
            layer.add(crossTransientSeries)
        }
        return chart
    }

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        // onFrame 走 [runFrame] 而不是直接进 [drawScene]：异常必须在这里就被接住，
        // 见 [runFrame] 的第 4 组说明。
        bridge.onFrame { gc -> runFrame(gc) }
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
     * `onFrame` 回调的入口：**异常必须在这里被接住**，而且帧号必须照常推进。
     *
     * <p><strong>为什么不能只靠 [drawScene] 自己包一层。</strong>
     * 绘制期抛出的异常若逃出去，GL 线程会死掉：后面的帧不再渲染、`onRender` 不再被调用、
     * 汇总行与 `exitProcess` 都走不到，JVM 因为"最后一个非守护线程结束"而
     * <b>以 0 退出</b>——一个已经画不出东西的校验器报出退出码 0，也就是最忌讳的"静默的绿"。
     *
     * <p><strong>帧号为什么要放在 finally 里（实测过）。</strong>`frame` 是"校验帧到了没有"的
     * 唯一判据。把递增留在 `drawScene` 里面的话，一个在 `drawScene` 开头抛的异常会让帧号
     * 永远停在 0：校验帧永远不到，进程既不正确退出也不失败，而是一直<b>挂着</b>。
     *
     * <p>实测（把递增改回 try 里、并把 [verifyAll] 的 `drawError` 检查挪回帧号闸门之后）：
     * 在 `drawScene` 的第一行注入 `throw RuntimeException`，`timeout 75` 掐掉时
     * 退出码是 <b>124</b>、一行断言都没打印——既不是 0 也不是 1，是<b>什么都没有</b>。
     * 挂在 CI 里与"通过"一样安静（超时之前没人会看），而且它连"我挂了"都不说。
     *
     * <p><strong>它必须排在帧号闸门之前。</strong>`drawError` 的检查早于
     * `frame < TOTAL_FRAMES`：绘制抛异常的那一帧，帧缓冲里的内容是没意义的，
     * 再等到校验帧只会报出一堆误导性的像素 FAIL，把真正的原因埋掉。
     *
     * <p>捕获到的异常交给 [verifyAll]：<b>计入失败、保证退出码非 0</b>。
     */
    private fun runFrame(gc: Gc) {
        try {
            drawScene(gc)
        } catch (t: Throwable) {
            drawError = t
        } finally {
            frame++
        }
    }

    /**
     * 一帧的场景。**它是逐帧变化的**（探测方块在动 + 观察期里三张实验图在滚），
     * 原因见 [movingSquareX]。
     *
     * <p>它**不递增** [frame]：那件事在 [runFrame] 的 finally 里做（理由见那里）。
     * 因此这里读到的 `frame` 就是本帧的下标（从 0 开始），与原来 `frame++` 的取值一致。
     */
    private fun drawScene(gc: Gc) {
        val n = frame

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

        // 3c) Task 12 的三张实验图。**只在观察期画**：
        //     第 1 组要"滚动若干帧"才成立、第 3 组要"前后两帧"才成立，
        //     而观察期一过就让画面回到 Task 10/11 的原样——于是前 15 条断言的
        //     期望值（尤其是"画面只有这 7 种颜色"）一字未改。
        if (n < STREAM_FRAMES) {
            drawStreamingChart(gc)
            drawGapChart(gc)
            drawCrossChart(gc, n)
        }

        // 4) 标注：在图表**之后**画的普通图元。它必须盖在数据系列之上——
        //    这一条钉住的是"图表是当场就画的"，见 [LABEL_X] 的说明。
        gc.fill = labelArgb
        gc.fillRect(LABEL_X, LABEL_Y, LABEL_W, LABEL_H)
    }

    /**
     * 流式实验：每帧追加 K 个点、窗口跟着写指针滑，然后<b>量一下这一帧真正上传了多少字节</b>。
     *
     * <h2>为什么这一帧的量就是那件事本身</h2>
     * <p>② 的性能主张是"每帧只上传新增的点，不是整个窗口"。
     * 而"上传了多少字节"在画面上<b>没有任何痕迹</b>——`SeriesBuffer` 每帧从 0 开始
     * 全量重传的话，画出来的波形与增量上传<b>逐像素相同</b>。
     * 所以这里断言的不是"画面里有条曲线"（那对两种实现都成立），而是
     * <b>每一帧的上传字节数恒等于 `STREAM_POINTS_PER_FRAME × 4`</b>。
     *
     * <p>"取走即清零"的语义见 `ChartRenderer.takeUploadedBytes` 的文档：
     * 每帧问一次，拿到的就是"这一帧传了多少"。
     */
    private fun drawStreamingChart(gc: Gc) {
        // 采集线程就是 GL 线程自己：RingChartData 的硬前提是"只有一个写者"，
        // 同一个线程既写又读当然满足它（真实场景里写者是采集线程，读者是 GL 线程）。
        repeat(STREAM_POINTS_PER_FRAME) {
            val abs = streamData.writeIndex()
            streamData.append(abs.toDouble(), Math.sin(abs * 0.05))
        }
        val written = streamData.writeIndex()
        streamXAxis.setWindow((written - STREAM_VISIBLE).toDouble(), written.toDouble())
        gc.charts.draw(streamChart, streamRect, gc.width, gc.height)
        streamUploadedBytes.add(gc.charts.takeUploadedBytes(streamSeries))
    }

    /** 缺口实验：数据里那个 NaN 必须把折线断开，见 [GAP_VALUES]。 */
    private fun drawGapChart(gc: Gc) {
        gc.charts.draw(gapChart, gapRect, gc.width, gc.height)
    }

    /**
     * 跨帧实验：第 [CROSS_REMOVE_FRAME] 帧起换成一张<b>没有那条系列</b>的图。
     *
     * <p>换图而不是改数据，是因为要验的是"它整个从画面上消失"。
     */
    private fun drawCrossChart(gc: Gc, n: Int) {
        val shown = if (n < CROSS_REMOVE_FRAME) crossChartWithTransient else crossChartWithout
        gc.charts.draw(shown, crossRect, gc.width, gc.height)
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
     * 观察期的取样：在**指定的那几帧**上把实验图那一小块回读下来。
     *
     * <p>为什么不在校验帧一次性回读：第 2、3 组断言的证据在<b>不同的帧</b>上——
     * "移除之前它在画"与"移除之后它没留下"是两帧的两张图，一帧里看不到。
     * 而快照必须是**那一帧刚画完**的时候取的，事后补不回来。
     *
     * <p>取的都是小区域（几百 × 几十像素）：观测不该把校验器本身的开销变成主要矛盾。
     */
    private fun captureObservations(h: Int) {
        // 观察期的最后一帧：流式图与缺口图都还在画，各抓一张。
        if (frame == STREAM_FRAMES) {
            streamSnapshot = grab(h, streamRect)
            gapSnapshot = grab(h, gapRect)
        }
        // 移除之前的那一帧（`frame` 是"已完成帧数"，所以它等于下标 + 1）。
        if (frame == CROSS_REMOVE_FRAME) {
            crossSnapshotBefore = grab(h, crossRect)
        }
        // 移除**之后**再等一帧：刚移除的那一帧可能还留着上一帧的痕迹，
        // 隔一帧取才是"稳态下它真的不在了"。
        if (frame == CROSS_REMOVE_FRAME + 2) {
            crossSnapshotAfter = grab(h, crossRect)
        }
    }

    /**
     * 回读一块矩形区域的像素，口径是**不含 alpha 的 RGB**（与全帧回读一致）。
     *
     * <p>{@code glReadPixels} 的原点在帧缓冲<b>左下角</b>、y 向上，而本项目的用户坐标
     * 原点在左上、y 向下：所以要先把上边缘翻成 GL 的行号
     * （{@code h - y - 高度}），回读之后再把行序翻回来。
     * 漏掉这一步的症状是"上下的东西对调了"——而一块平缓的波形上下对调之后
     * 仍然是"一条波形"，靠肉眼分不出来。
     *
     * @param rect 要回读的区域（用户坐标，y 向下）
     * @return 行优先、从上到下的 RGB 快照
     */
    private fun grab(h: Int, rect: Rect): Shot {
        val rw = rect.width.toInt()
        val rh = rect.height.toInt()
        val buf = ByteBuffer.allocateDirect(rw * rh * 4)
        glReadPixels(rect.x.toInt(), h - rect.y.toInt() - rh, rw, rh,
            GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        val px = IntArray(rw * rh)
        for (row in 0 until rh) {
            val glRow = rh - 1 - row
            for (col in 0 until rw) {
                val i = (glRow * rw + col) * 4
                px[row * rw + col] =
                    ((buf.get(i).toInt() and 0xFF) shl 16) or
                            ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                            (buf.get(i + 2).toInt() and 0xFF)
            }
        }
        return Shot(rw, rh, px)
    }

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
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return

        // ---- 0. onFrame 里抛出的异常：先于一切像素断言处理 ----
        //
        // 它必须**计入失败并保证退出码非 0**，而且不能等到校验帧：
        // 绘制期抛异常的那一帧，帧缓冲里的内容是没意义的，后面的像素断言
        // 只会报出一堆误导性的 FAIL，把真正的原因埋掉。
        //
        // 这一条是"退出码完整性"的守卫：GL 线程上一个没接住的异常会让 JVM 以 0 退出，
        // 于是校验器报一个**静默的绿**。而"绿"是所有信号里唯一不会被人再看一眼的那个。
        drawError?.let { err ->
            println("\n=== onFrame 绘制期抛出异常，判为失败（此时像素断言没有意义）===")
            err.printStackTrace()
            println("\n=== 失败 1 项：onFrame 绘制期异常（退出码必须非 0） ===")
            Platform.exit()
            exitProcess(1)
        }

        // ---- 观察期：只抓快照、只记数，不做任何断言 ----
        captureObservations(h)
        if (frame < TOTAL_FRAMES) return

        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        println("=== JFGL 图表绘制后端像素校验 ===")
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

        // ---- 10. ★ ② 的性能主张：每帧只上传新增的点 ----
        //
        // 这一条是本次新增里唯一"断言的量就是那件事本身"的断言，
        // 也是唯一能把**增量上传**与**每帧全量重传**分开的量：两种实现画出来的图
        // 逐像素相同，上面 15 条一条也拦不住。
        //
        // 判据写成"每一帧的字节数恰好等于 K × 4"，而不是"小于某个阈值"：
        // 阈值会把"传了一半"这种半吊子实现放过去，而"恰好等于 K × 4"要求
        // 每一帧都只传了新增的那 K 个点——一个字节不多、一个字节不少。
        println("\n-- ★ 性能主张：每帧只上传新增的点 --")
        val expectedBytesPerFrame = STREAM_POINTS_PER_FRAME * BYTES_PER_SAMPLE
        val oddFrames = streamUploadedBytes.withIndex().filter { it.value != expectedBytesPerFrame }
        report("流式系列：每一帧的上传字节数恒等于 K×4 = $expectedBytesPerFrame",
            streamUploadedBytes.size == STREAM_FRAMES && oddFrames.isEmpty(),
            "记录 ${streamUploadedBytes.size} 帧（期望 $STREAM_FRAMES），不符 ${oddFrames.size} 帧" +
                    (oddFrames.firstOrNull()?.let { "；首个：第 ${it.index} 帧 ${it.value} 字节" } ?: "") +
                    "。若每帧全量重传，这里每帧应是环里留着的样本 × 4 = " +
                    "${streamData.itemCount() * BYTES_PER_SAMPLE} 字节")

        // 上面那条必须与这两条成对：一条管"量到的确实是这一帧的量"，
        // 一条管"全量与增量在这套参数下真的差得开"——否则"每帧 40 字节"
        // 也可能只是因为它压根没什么可传。
        report("前提：测量期确实在流式追加（否则上一条恒真）",
            streamData.writeIndex() == (STREAM_FRAMES * STREAM_POINTS_PER_FRAME).toLong(),
            "写入总数 ${streamData.writeIndex()}，期望 ${STREAM_FRAMES * STREAM_POINTS_PER_FRAME}")
        report("前提：环里装着的样本数远多于 K，两种实现才分得开",
            streamData.itemCount() >= STREAM_CAPACITY / 4 * 3,
            "环里 ${streamData.itemCount()} 个样本，每帧只新增 $STREAM_POINTS_PER_FRAME 个——" +
                    "全量重传要传 ${streamData.itemCount() * BYTES_PER_SAMPLE} 字节，" +
                    "是增量的 ${streamData.itemCount() / STREAM_POINTS_PER_FRAME} 倍")

        // ---- 11. 缺口（NaN）必须断开折线 ----
        //
        // 判别的量是"NaN 那个样本的**竖直条带**里有没有折线色"，不是"画面里有没有折线"：
        // 后者对"把缺口连过去了"同样成立（那也是一条线），是橡皮图章。
        //
        // 条带取的是下标 7 的屏幕 x 处 ±3 px、从上边缘到下边缘的一整列。
        // 无论坏实现是"把 NaN 当成 0"（线掉到底部）还是"跳过 NaN 直接连两端"
        // （线从高位斜穿到低位），它都必须穿过这一列——所以这一个量能同时拦住两种。
        println("\n-- 缺口：NaN 必须断开折线 --")
        val gapShot = gapSnapshot
        if (gapShot == null) {
            report("前提：缺口实验的快照抓到了", false, "gapSnapshot 为 null")
        } else {
            // 局部坐标：快照就是 GAP_PLOT 那一块，左上角为原点。
            // 下标 i 的屏幕 x = 20 + i × 60，减去 20 即局部 x = 60 i。
            val nanCol = GAP_NAN_INDEX * (GAP_PLOT_W.toInt() / (GAP_POINTS - 1))
            val inGap = gapShot.countIn(nanCol - 3, 0, nanCol + 3, gapShot.h - 1, gapRgb)
            report("缺口那一段的像素是背景色（NaN 处一条折线都没有）", inGap == 0,
                "局部 x=${nanCol}±3 的整列里有 $inGap px 折线色，期望 0——" +
                        "非 0 说明缺口被连了过去（那条线显示了一个不存在的信号）")

            // 成对断言：缺口**两侧**都得有折线，否则"整条曲线都没画"也能让上一条通过。
            val leftCol = 3 * (GAP_PLOT_W.toInt() / (GAP_POINTS - 1))
            val rightCol = 9 * (GAP_PLOT_W.toInt() / (GAP_POINTS - 1))
            val leftPx = gapShot.countIn(leftCol - 3, 0, leftCol + 3, gapShot.h - 1, gapRgb)
            val rightPx = gapShot.countIn(rightCol - 3, 0, rightCol + 3, gapShot.h - 1, gapRgb)
            report("缺口之前（下标 3）有折线（否则上一条恒真）", leftPx > 0,
                "局部 x=$leftCol±3 里 $leftPx px")
            report("缺口之后（下标 9）有折线（证明缺口之后的数据照样在画）", rightPx > 0,
                "局部 x=$rightCol±3 里 $rightPx px")
        }

        // ---- 12. 跨帧：某条曲线消失后，它原来的位置必须干净 ----
        //
        // 这一组是吸取 PickVerifier 的教训：它 24 条断言全绿却漏掉一个真缺陷，
        // 因为**它的场景每帧完全相同**——"上一帧的东西留在了画面上"在静止场景里
        // 会被本帧原样盖住，只有"上一帧有、这一帧没有"才暴露得出来。
        //
        // 三条断言必须成对：**移除前它确实在**（否则"移除后没有"恒真）、
        // **移除后它的位置是背景色**、**两帧确实不是同一张图**。
        println("\n-- 跨帧：消失的曲线不能留下残留 --")
        val before = crossSnapshotBefore
        val after = crossSnapshotAfter
        if (before == null || after == null) {
            report("前提：跨帧实验的两张快照都抓到了", false,
                "before=${before != null}，after=${after != null}")
        } else {
            val wasThere = before.count(crossTransientRgb)
            report("移除之前：那条系列确实在画面上（否则下面两条恒真）", wasThere > 0,
                "短暂色 ${wasThere} px（局部 ${before.w}×${before.h}）")
            report("两张快照里常驻系列都在（否则可能是整张图都没画）",
                before.count(crossPersistRgb) > 0 && after.count(crossPersistRgb) > 0,
                "移除前 ${before.count(crossPersistRgb)} px，移除后 ${after.count(crossPersistRgb)} px")
            report("移除之后：它原来占的那几行（局部 y $CROSS_TRANSIENT_ROW_LO..$CROSS_TRANSIENT_ROW_HI）就是背景色",
                after.countNonBackgroundInRows(
                    CROSS_TRANSIENT_ROW_LO, CROSS_TRANSIENT_ROW_HI, background) == 0,
                "那几行里不是背景色的像素 " +
                        "${after.countNonBackgroundInRows(CROSS_TRANSIENT_ROW_LO, CROSS_TRANSIENT_ROW_HI, background)} px，" +
                        "其中短暂色 ${
                            after.countIn(0, CROSS_TRANSIENT_ROW_LO, after.w - 1,
                                CROSS_TRANSIENT_ROW_HI, crossTransientRgb)
                        } px——非 0 就是上一帧的残留")
            report("两张快照确实不是同一张图（场景真的变了）", !before.sameAs(after),
                "移除前 ${before.count(crossTransientRgb)} px 短暂色，移除后 ${after.count(crossTransientRgb)} px")
        }

        // ---- 13. 流式图确实画出来了 ----
        // 第 10 组量的是字节数，不管画面。这条补上"那张图真的在画"，
        // 免得"字节数对、但压根没画"这种组合悄悄成立。
        println("\n-- 流式图确实画出来了 --")
        val streamShot = streamSnapshot
        if (streamShot == null) {
            report("前提：流式图的快照抓到了", false, "streamSnapshot 为 null")
        } else {
            report("流式图上有该系列颜色的像素", streamShot.count(streamRgb) > 0,
                "流式色 ${streamShot.count(streamRgb)} px（局部 ${streamShot.w}×${streamShot.h}）")
        }

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
