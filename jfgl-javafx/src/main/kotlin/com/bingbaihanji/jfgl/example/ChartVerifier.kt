package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.chart.ArrayChartData
import com.bingbaihanji.jfgl.chart.Axis
import com.bingbaihanji.jfgl.chart.AxisRange
import com.bingbaihanji.jfgl.chart.AxisType
import com.bingbaihanji.jfgl.chart.Chart
import com.bingbaihanji.jfgl.chart.ChartType
import com.bingbaihanji.jfgl.chart.RingChartData
import com.bingbaihanji.jfgl.chart.Series
import com.bingbaihanji.jfgl.chartrender.ChartRenderLayout
import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.gpu.FftWindow
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
import kotlin.jvm.java
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
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
 * <p>Task 13 <b>接拾取</b>又补了两组（第 14、15 节），它们守的是同一类东西——
 * <b>拾取坏了画面一点都不会变坏</b>，只会让点击落在错误的对象上：
 *
 * <ol>
 *   <li><b>拾取的 ID 与容差</b>（[PICK_PROBE_PLOT_X] 那一节）。断言的量是"读回的 ID 是哪一个"，
 *       而不是"有没有返回坐标"；容差那两条必须成对——只有"旁边 3px 仍命中"会被
 *       "线本来就有 8px 粗"的实现骗过去，只有"旁边 20px 不命中"又拦不住"永远返回 0"。
 *       另有一条钉住裁剪在 ID pass 里同样生效（[PICK_PROBE_FAR_Y] 附近那条）。</li>
 *   <li><b>跨环绕时 {@code baseInstance} 真的生效</b>（[WRAP_WINDOW_START] 那一节）。
 *       实例属性按 {@code gl_InstanceID} 取，而它<b>每次从 0 开始</b>；如果测试场景
 *       从不跨环绕点，那么 {@code baseInstance} 恒为 0，传对传错都一样。这是唯一能把
 *       "实现了"与"生效了"分开的场景。</li>
 * </ol>
 *
 * <p>Task 14 <b>散点</b>又补了两组（第 16、17 节）：
 *
 * <ol>
 *   <li><b>点与点之间没有连线</b>（[SCATTER_VALUES] 那一节）。**这是散点与折线的唯一区别**，
 *       而"每个数据点位置有像素"对折线同样成立（折线的顶点就落在数据点上）——
 *       只断言它等于放任"把散点画成折线"全绿，而画面上那是一条显示着<b>不存在的信号</b>
 *       的线。判别的量是<b>相邻两点的中点</b>：正确实现那里什么都没有。
 *       同组还钉住"热区也没把两点连起来"（拾取坏了画面不会变坏）。</li>
 *   <li><b>标记尺寸真的被用上了</b>（第 17 节）。断言的量是<b>像素数 = 点数 × 边长²</b>，
 *       而不是"有像素"——后者对任何非 0 的 markerSize 都成立。</li>
 * </ol>
 *
 * <p>Task 7（子项目 D-③-1）<b>频谱</b>补了第 19 节。它守的是「GPU 上的 FFT 输出被画到了
 * 对的地方」——数值那一半（峰值落在正确的 bin、与朴素 DFT 逐 bin 比对、换窗不改读数、
 * 跨环绕）归 {@code FftVerifier}，而**位置与几何那一半只有像素拦得住**，
 * 所以它在这里而不在那里（`FftVerifier` 明确"不画任何东西"）。
 *
 * <p><b>它的判别式只有一条，而且不是最显眼的那条</b>：相邻两个 bin 之间那一列上的墨迹，
 * 落在**两者幅值的中点**那一行，还是只落在左边那个的高度上。
 * 把 {@code SpectrumSeriesRenderer} 第二个实例属性的偏移从 4 改成 0（两个属性都指向同一个
 * bin）时，每一段退化成**水平小横线**，而**峰那一列的最高有色行仍然在顶边**——
 * 于是"峰值在正确的 bin"与"峰高对得上参考幅值"这两条**照常通过**（实测：这一节 14 条里
 * 只有那两条 ★ 失败，其余 12 条全绿）。这与折返图那条判据（[ZIG_VALUES]）是同一件事，
 * 只是这里把"两个实例之间"的分辨尺度交给了 FFT 的主瓣。
 *
 * <h2>观察期与校验期</h2>
 *
 * <p>第 1 条需要"滚动若干帧"才成立（一帧是量不出增量的），第 3 条需要"前后两帧"，
 * 所以本校验器不再是"到第 5 帧就断言"：前 [STREAM_FRAMES] 帧是<b>观察期</b>
 * （五张实验图每帧都画、逐帧记数、按需抓快照），之后场景回到 Task 10/11 那个原样的场景，
 * 在 [TOTAL_FRAMES] 帧上做全部断言。这样前 15 条断言的场景与期望值<b>一字未改</b>。
 *
 * <p>Task 13 的两张实验图（拾取探针与跨环绕）也只在观察期画：**"画面恰好只有这 7 种颜色"
 * 是不许改弱的既有断言**，在画面里常驻一种新颜色会让它失败，而这两张图要验的事
 * 只需要"某一帧画过"。
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

// ---------------------------------------------------------------------------
// Task 13 的两张实验图
//
// 它们**只在观察期画**（与上面三张一样），原因是"画面只有这 7 种颜色"那条断言
// 是 Task 10/11 留下的、不许改弱的断言：在画面里常驻一种新颜色会让它失败。
// 而这两张图要验的东西（拾取容差、跨环绕）都只需要"某一帧画过"。
// ---------------------------------------------------------------------------

// ---- 四、拾取实验：容差与系列级 ID ----

/**
 * 拾取探针图的绘图区：主绘图区**上方**那块空地（流式图到 x = 320 为止、跨帧图从 x = 710 起）。
 */
private const val PICK_PROBE_PLOT_X = 340f
private const val PICK_PROBE_PLOT_Y = 10f
private const val PICK_PROBE_PLOT_W = 360f

/**
 * 拾取探针图的绘图区高度。
 *
 * <p>**取 81（奇数）不是随手写的。**探针系列的线宽是 1，它的四边形在竖直方向恰好 1px：
 * 中心落在整数行上时四边形是 {@code [49.5, 50.5]}，**两个像素的中心都正好压在边界上**，
 * 能不能光栅化出像素由实现的填充规则决定——那会让"线画了 1px"变成一件说不清的事。
 * 奇数高度把数据值 0.5 映射到 **50.5**，四边形于是恰好覆盖整行 50，没有边界上的暧昧。
 */
private const val PICK_PROBE_PLOT_H = 81f

/** 探针图的样本数（只决定每段多宽；形状是一条水平线）。 */
private const val PICK_PROBE_POINTS = 11

/** 探针线的数据值。0.5 → 屏幕 y = 10 + (1 - 0.5) × 81 = 50.5。 */
private const val PICK_PROBE_VALUE = 0.5

/** 探针线宽：**1px**。这是"容差"那两条断言的前提——线宽一粗，它们就恒真了。 */
private const val PICK_PROBE_LINE_WIDTH = 1f

/** 三个探测点的 x：第 4 段的中间（每段 36px，数据 4 在 x = 484、数据 5 在 x = 520）。 */
private const val PICK_PROBE_X = 502f

/** 探测点 1：线上（行 50 被四边形精确覆盖）。 */
private const val PICK_PROBE_ON_LINE_Y = 50f

/** 探测点 2：线**旁 3px**（行 53 的中心 53.5 距线的中心 50.5 恰好 3px）。 */
private const val PICK_PROBE_BESIDE_Y = 53f

/** 探测点 3：线旁 20px，必须落空。 */
private const val PICK_PROBE_FAR_Y = 70f

/**
 * 拾取容差（半宽，设备像素）。
 *
 * <p>**与 `LineSeriesRenderer.PICK_TOLERANCE_PX` 是同一个数**，在这里重写一遍是为了让
 * "线旁 3px 仍命中、20px 不命中"这两条断言**能就地算出期望值**，而不是把 4 这个数
 * 散落在断言里。它只影响 ID pass，不影响画面。
 */
private const val PICK_TOLERANCE_PX = 4

// ---- 五、跨环绕实验：baseInstance 必须生效 ----

/** 跨环绕实验的绘图区：画面右下角（缺口图到 x = 620 为止，y 到 710）。 */
private const val WRAP_PLOT_X = 630f
private const val WRAP_PLOT_Y = 610f
private const val WRAP_PLOT_W = 330f
private const val WRAP_PLOT_H = 100f

/** 环容量：**刻意取小**，三帧就写满两圈以上。 */
private const val WRAP_CAPACITY = 8

/** 写入总数。20 > 8 × 2，环已经绕过两圈以上，槽位与数据下标彻底错开。 */
private const val WRAP_TOTAL = 20

/**
 * x 轴窗口。左端 12 **落在环绕点（下标 16 = 槽位 0）之前**，于是这一屏必然跨过环绕点，
 * `WindowRange` 会给出**两段**实例（槽位 4..7 与槽位 0..2）。
 *
 * <p>这正是"少了 `baseInstance` 就画错"的触发条件：实例属性按 {@code gl_InstanceID} 取，
 * 而它**每次都从 0 开始**（`baseInstance` 不影响它，只影响属性取哪一份数据）。
 * 少了它，第一段拿到的是槽位 0..3 的数据（下标 16..19），整条线上移 4 个样本——
 * **画面里是一条连续的线，只是它显示的值全错了**。
 */
private const val WRAP_WINDOW_START = 12.0
private const val WRAP_WINDOW_END = 20.0

// ---------------------------------------------------------------------------
// Task 14 的两张实验图：散点
//
// 与 Task 12/13 的实验图一样**只在观察期画**（理由同上："画面只有这 7 种颜色"
// 那条断言不许改弱），而它们要验的东西只需要"某一帧画过"。
// ---------------------------------------------------------------------------

/** 散点图的绘图区：主绘图区**右侧**那块空地（主图到 x = 700 为止，跨帧图在 y < 100）。 */
private const val SCATTER_PLOT_X = 710f
private const val SCATTER_PLOT_Y = 100f
private const val SCATTER_PLOT_W = 260f
private const val SCATTER_PLOT_H = 180f

/**
 * 标记尺寸对比图的绘图区：凑散点图正下方。
 *
 * <p>**尺寸、数据、x 窗口都与散点图完全相同**，唯一的变量是 markerSize——
 * 只有这样，"像素数变多"才只可能是 markerSize 造成的。
 */
private const val MARKER_PLOT_X = 710f
private const val MARKER_PLOT_Y = 300f
private const val MARKER_PLOT_W = 260f
private const val MARKER_PLOT_H = 180f

/** 散点图的点数：5 个点、4 个间隔，每格 52 px。 */
private const val SCATTER_POINTS = 5

/**
 * 散点图的 x 轴窗口：左右各留**半格**，于是 5 个标记全都落在绘图区内部。
 *
 * <p>不这么做的话，最左/最右那两个标记会被 scissor 切掉一部分，像素计数会变成
 * 49 或者 81 这种"说不清"的数——而"断言只是差了几个像素"是最难判断的一类失败。
 */
private const val SCATTER_WINDOW_MIN = -0.5
private const val SCATTER_WINDOW_MAX = 4.5

/**
 * 退化散点图（`markerSize = 0`）的绘图区：折返图右侧那块空地。
 *
 * <p>这一张图**每帧都画**（与上面两张只在观察期画的不同）。理由是它一个像素都画不出来
 * ——于是它既不会给"画面只有这 7 种颜色"那条断言添一种颜色，也不会动任何像素计数；
 * 而"全画面没有该颜色"这条断言只有在**校验帧**上数才有意义（`counts` 是校验帧的全图扫描）。
 *
 * <p>反过来的前提由拾取钉住：这张图要是压根没被画，那条断言就是恒真的，
 * 所以另有一条"退化系列在拾取里仍能命中"。
 */
private const val DEGEN_PLOT_X = 810f
private const val DEGEN_PLOT_Y = 505f
private const val DEGEN_PLOT_W = 160f
private const val DEGEN_PLOT_H = 90f

/** 退化系列的探测点：它在自己绘图区里的第一个数据点（局部 x = 16；值 0.15 → 局部 y = 76.5）。 */
private const val DEGEN_PROBE_X = DEGEN_PLOT_X + 16f
private const val DEGEN_PROBE_Y = DEGEN_PLOT_Y + 76f

/**
 * 小标记的**半径**（设备像素）——`Series.markerSize()` 声明的就是半径，不是边长。
 *
 * <p>取 10 让**边长** = 2 × 10 = 20，正好压在整数边界上（渲染器里的换算是乘 2，
 * 见 `ScatterSeriesRenderer.markerEdge`）。下面所有期望值都按
 * **点数 × (2 × 半径)²** 算——写成"边长²"会让这组断言去钉一个错的语义。
 */
private const val SCATTER_MARKER_RADIUS_SMALL = 10f

/** 大标记的半径：正好是小标记的 2 倍，于是期望像素数是 4 倍（面积随边长的平方）。 */
private const val SCATTER_MARKER_RADIUS_BIG = 20f

/**
 * 散点图的数据：**只有 5 个点，相邻两点一个 0.15 一个 0.85**。
 *
 * <p><strong>为什么必须这么陡。</strong>本组唯一能区分"散点"与"折线"的量是
 * **相邻两点中点处有没有像素**：正确实现在那里什么都没有，而把点连成线的实现在那里
 * 有一条线。数据要是不够陡（例如斜坡那种每格只差 2 px 的），中点就离两端太近——
 * 标记点自身（以及它的边界）会糊住中点，"中点为空"于是变成一条恒假的断言。
 * 这里相邻两点差 0.7，纵向是 **126 px**，而横向只有 52 px：
 * 中点到两端的距离远超任何标记半径（实测余量：小标记图 69 px、大标记图 49 px）。
 *
 * <p>值取 0.15 / 0.85 而不是贴着 0 与 1：**大标记的半径是 20 px**，取 0.1 / 0.9
 * （局部 y = 162 / 18）会让最上面那个标记伸出绘图区上边缘 2 px 被 scissor 切掉，
 * 于是"像素数 = 点数 × 边长²"这条精确断言会差掉几十个像素——
 * 而那种失败看起来像"渲染器算错了尺寸"。
 *
 * <p>四段全是陡的（0→1 与 2→3 是下折、1→2 与 3→4 是上折），四段都参与判定。
 */
private val SCATTER_VALUES = doubleArrayOf(0.15, 0.85, 0.15, 0.85, 0.15)

/**
 * "空白处也拾取不到"的那个探测点（用户坐标）。它**不在任何标记附近**，
 * 却**正落在一条特定的坏热区上**——所以它能把"热区形状正确"与"热区是一条斜带"分开。
 *
 * <p><strong>它是怎么算出来的。</strong>散点的 ID pass 一旦错用折线的顶点程序
 * （`LINE_VERTEX`），第二个实例属性 `aY1` 就没人喂（散点的 VAO 里没有它），
 * 于是每个实例的热区是"从 (x_k, y_k) 斜拉到 (x_k + 1 格, 值 0 那一行)"的一条带子。
 * 第 0 个点的值是 0.15、屏幕 y = 253；值 0 落在屏幕 y = 280（绘图区下边缘）。
 * 于是那条带子从 (736, 253) 走到 (788, 280)，在 x = 762 处中心 y = 266.5、
 * 竖直半宽 4.5 px——**探测点 (762, 267) 的像素中心正好落在那条带子里**。
 *
 * <p>而在正确实现下，它的热区是标记周围 20×20 的方块（半径 10 → 边长 20），
 * 离这一点最近的标记也在 16 px 之外，所以那里什么都没有。
 *
 * <p>这一对断言（"标记上命中" + "旁边空白处不命中"）必须成对：
 * 只有"标记上命中"对"热区开成整个绘图区"同样成立。
 */
private const val SCATTER_BLANK_X = 762f
private const val SCATTER_BLANK_Y = 267f

// ---------------------------------------------------------------------------
// Task 7 的频谱实验图（子项目 D-③-1）
//
// 与 Task 12/13/14 的实验图一样**只在观察期画**（理由同上："画面只有这 7 种颜色"
// 那条断言不许改弱）。它比前几张多一层：**同一张图、同一块绘图区、同一份数据对象**，
// 在观察期里分四幕换输入——于是"频谱跟着输入变"这件事不必靠四块地方来演，
// 而且跨帧的状态泄漏（"只有第一次算对"）在这里同样无处可藏（同 [movingSquareX]）。
// ---------------------------------------------------------------------------

/**
 * 频谱实验图的绘图区：主绘图区**下方**那块空地。
 *
 * <p>逐条避开别的东西：主绘图区到 y=500 为止；折返图从 x=350 起（y 505..595）；
 * 缺口图从 y=610 起；每帧移动的那个方块落在 x ≤ 120、y 540..580。
 */
private const val SPECTRUM_PLOT_X = 128f
private const val SPECTRUM_PLOT_Y = 502f
private const val SPECTRUM_PLOT_W = 220f
private const val SPECTRUM_PLOT_H = 103f

/**
 * 频谱的环容量。**它就是 FFT 的变换长度**：
 * `SpectrumSeriesRenderer.transformLength(容量)` 取 `min(默认长度 2048, 容量)`，
 * 容量 512 时就是 512——半谱 257 个 bin，每帧跑一次 512 点的 FFT。
 *
 * <p>取 512 而不是 2048 是刻意的：够小（每帧的 compute 开销可以忽略），
 * 又远在 [SPECTRUM_WINDOW_MAX] 之上（可见窗口落在 bin 44..68，离 Nyquist 很远）。
 */
private const val SPECTRUM_CAPACITY = 512

/**
 * `SpectrumSeriesRenderer.DEFAULT_FFT_LENGTH` 的**第二份**（它在渲染器里是私有的）。
 *
 * <p>存在的理由只有一个：[SPECTRUM_CAPACITY] 这个环容量要与它比较一次——
 * 变换长度是 `min(它, 容量)`，只有它 ≥ 容量时"变换长度 == 环容量"才成立，
 * 而下面那份 CPU 参考正是按这个长度算的。**两边改一处就得改另一处。**
 */
private const val SPECTRUM_DEFAULT_FFT_LENGTH = 2048

/**
 * ★ x 轴窗口**刻意不取 [0, …]**：左端 44。
 *
 * <p>窗口左端为 0 时，`uFirstRelIndex`（本次 draw 的第一个实例相对窗口左端的偏移）
 * **恒等于 0**——传对传错画出的是同一张图，那个量根本不起作用。
 * 左端非 0 之后，它写错（例如传成绝对下标）会让整条谱平移 44 个 bin、
 * 约 400 px，远超这块绘图区：峰值那一列的断言因此立刻失败。
 * 这是从 Task 6 的探针里学来的写法。
 *
 * <p>窗口取 24 个 bin、绘图区 220 px，于是每个 bin 摊 **9.17 px**——
 * "相邻两个 bin 之间那一列"由此有足够的宽度可点（见 [SPECTRUM_MID_BAND_PX]）。
 */
private const val SPECTRUM_WINDOW_MIN = 44.0
private const val SPECTRUM_WINDOW_MAX = 68.0

/**
 * 四幕的频率（单位是 **bin 下标**，不是 Hz——频谱的 x 轴单位就是 bin）。
 *
 * <p>两个频率都在窗口 [44, 68] 内，且**各留了 4 个 bin 的主瓣余量**
 * （BH 窗的主瓣是 ±4 个 bin：52-4=48 ≥ 44、62+4=66 ≤ 68）——
 * 主瓣被窗口切掉的话，峰的形状就不再是"一个完整的峰"了。
 *
 * <p>52 → 62 相差 10 个 bin ≈ 92 px，峰位的移动一眼可辨。
 */
private const val SPECTRUM_K0_A = 52
private const val SPECTRUM_K0_B = 62

/** 单位幅度：`|X[k0]|` 读回 1.0（这一条由 FftVerifier 钉着，这里直接当已知量用）。 */
private const val SPECTRUM_AMP = 1.0

/** 第三幕的幅度。改幅度**只该改峰高、不该改峰位**。 */
private const val SPECTRUM_AMP_LOW = 0.25

/**
 * y 轴的窗口上界。留约 20% 余量：单位幅度正弦的谱峰是 `1.0`，
 * 窗口取 [0, 1] 的话峰顶正好压在绘图区上边缘、被 scissor 切掉——
 * 那时"最高的一列在哪"就不再是一个能读的量了。
 */
private const val SPECTRUM_Y_MAX = 1.2

/**
 * 谱线的线宽（半宽 = 1）。
 *
 * <p>取细是刻意的：下面那条判别式看的是"某一列上的墨迹落在哪一行"，
 * 线越粗，墨迹在竖直方向摊得越开，两个高度之间的空档就越小。
 */
private const val SPECTRUM_LINE_WIDTH = 2f

/**
 * 四幕的切换帧（观察期内的**绘制**帧下标）。
 *
 * <p>每一幕都**整整附录一环**（[SPECTRUM_CAPACITY] 个样本 = 环容量）：
 * 环里因此只剩这一幕的样本，谱是干净的单频，不掺上一幕的尾巴。
 */
private const val SPECTRUM_STAGE_B = 25
private const val SPECTRUM_STAGE_C = 50
private const val SPECTRUM_STAGE_D = 75

/**
 * 四张快照的抓取帧（`frame` 是"已完成帧数"，故比它所在的绘制帧下标大 1）。
 *
 * <p>每一张都抓在它那一幕的**中段**：切换那一帧的谱是过渡态（缓冲刚换内容），
 * 隔开几帧再取才是稳态。
 */
private const val SPECTRUM_SHOT_A = 10
private const val SPECTRUM_SHOT_B = 35
private const val SPECTRUM_SHOT_C = 60
private const val SPECTRUM_SHOT_D = 85

/**
 * ★ 相邻两个 bin 之间那一列的**中点带**（半高，行）。
 *
 * <p>正确实现那里是一条斜着穿过去的线段，它在那一列上的墨迹是**以两 bin 幅值的中点为心**
 * 的一小段；坏实现（第二个实例属性的偏移写成 0、两个属性都指向 `mag[k]`）把每一段画成
 * **水平小横线**，那一列上的墨迹落在**左端点**的高度上。
 * 宽度取 5 是为了容下线段自身的法向外扩（近竖直的线段在水平方向切一刀，切面比线宽长）。
 */
private const val SPECTRUM_MID_BAND_PX = 5

/**
 * ★ 那一列上"**左端点高度**附近不许有墨迹"的带宽（半高，行）。
 *
 * <p>它就是坏实现会把水平小横线画在的地方。带宽取得比中点带窄，
 * 是为了给正确实现留出余量——正确实现的墨迹从两 bin 中点再往上只到
 * `线宽/2 ÷ cos(倾角)` 那么多（实测约 4 px）。
 */
private const val SPECTRUM_WRONG_BAND_PX = 3

/**
 * 判别式的**前提**：相邻两个 bin 的行距至少要这么宽。
 *
 * <p>窄了它就成了橡皮图章——本仓库的教训是"<b>差 2px 抓不住，差四分之一屏才一眼可辨</b>"
 * （见 [ZIG_VALUES] 的那段说明）。实测这一对是 BH 窗主瓣上最陡的一级：
 * `|X[k0]|=1.0`、`|X[k0+1]|≈0.68`，在 [SPECTRUM_Y_MAX] 与 103 px 的绘图区上约 **27 px**。
 */
private const val SPECTRUM_MIN_DROP_PX = 8

/** 峰位那条断言允许的列误差（px）。抗锯齿与实例边界都会让它差上一两个像素。 */
private const val SPECTRUM_PEAK_COL_TOL = 4.0

/**
 * "最高的那一列"取平均时的容差（px）：最高行在 `minTop + 它` 之内的列都算峰顶。
 *
 * <p>峰顶两侧各有一列的最高行只比它低一两个像素（线段一升一降），
 * 取单个极值会因抗锯齿差一个像素就翻。
 */
private const val SPECTRUM_TIE_PX = 3

/**
 * "峰位跟着频率移动"这条断言要求的最小位移（px）。
 *
 * <p>`SPECTRUM_K0_A → SPECTRUM_K0_B` 差 10 个 bin，实测约 92 px——留一半余量，
 * 免得它退化成"移动了 1 个像素也算移动"。
 */
private const val SPECTRUM_MOVE_MIN_PX = 50.0

/**
 * 峰顶那一列取"墨迹上下界的中心"时的行误差（px）。
 *
 * <p>判据是"墨迹的中心 == 参考幅值所在的行"——线段在峰顶两侧一升一降，
 * 于是在峰顶那一列上，两段的墨迹关于顶点近似对称，**中心就是顶点**，
 * 与线宽和倾角都无关。留下的误差只有几个像素。
 */
private const val SPECTRUM_PEAK_ROW_TOL = 5.0

/** 第三幕的峰比第一幕矮——至少这么多行，"峰高跟着幅度变"才不是一句空话。 */
private const val SPECTRUM_HEIGHT_DROP_MIN = 40.0

/**
 * 频谱用的窗函数。**与 `SpectrumSeriesRenderer.WINDOW` 同一个值**（那边是私有常量）。
 *
 * <p>只有下面那份 CPU 参考需要它：渲染那段谱用的是哪个窗，参考就得用哪个窗，
 * 否则逐 bin 比的是"两个窗的差别"。渲染器换窗时这里要跟着换。
 */
private val SPECTRUM_WINDOW = FftWindow.BLACKMAN_HARRIS

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已有顶层 `main()`，两个同名顶层函数会让
 * `import com.bingbaihanji.jfgl.example.main` 报"重载歧义"。用 `@JvmName("main")`
 * 把 JVM 方法名钉回 `main`，上面文档里的命令行因此照常可用。
 *
 * <p>另外三个校验器（Pipeline / Pick / Text）用的是同一个写法，本文件保持一致。
 */
@JvmName("main")
fun chartVerifyMain() {
    Application.launch(ChartVerifierApp::class.java)
}

class ChartVerifierApp : Application() {
//    companion object{
//        @JvmStatic
//        fun main(args: Array<String>) {
//            launch(ChartVerifierApp::class.java, *args)
//        }
//    }

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

    // ---- Task 13 的两张实验图的颜色 ----
    // 两者互相不同、也与上面所有颜色不同；它们不在画面的常驻颜色集合里，
    // 因为那两张图只在观察期画（见上面 Task 13 那一段的说明）。

    /** 拾取探针图的折线色（线宽 1）。 */
    private val pickProbeRgb = 0x40C0C0

    /** 跨环绕实验的折线色。 */
    private val wrapRgb = 0xFF4000

    // ---- Task 14 的两张实验图的颜色 ----
    // 同上：只在观察期画，所以也不在画面的常驻颜色集合里。

    /** 散点图的标记色（小标记）。 */
    private val scatterRgb = 0x00FF80

    /** 标记尺寸对比图的标记色（大标记）。 */
    private val markerBigRgb = 0xC000FF

    /**
     * 退化散点系列（`markerSize = 0`）的颜色。它**必须全画面一个像素都没有**，
     * 所以也不在画面的常驻颜色集合里——尽管这张图每帧都画。
     */
    private val degenScatterRgb = 0x00C0FF

    // ---- Task 7 的频谱实验图的颜色 ----
    // 同上：只在观察期画，所以也不在画面的常驻颜色集合里。

    /** 频谱曲线的颜色。与上面所有颜色都不同（尤其不等于退化色 0xFF00FF）。 */
    private val spectrumRgb = 0x9F00FF

    private val degenScatterArgb = degenScatterRgb or (0xFF shl 24)

    private val spectrumArgb = spectrumRgb or (0xFF shl 24)

    private val scatterArgb = scatterRgb or (0xFF shl 24)
    private val markerBigArgb = markerBigRgb or (0xFF shl 24)

    private val pickProbeArgb = pickProbeRgb or (0xFF shl 24)
    private val wrapArgb = wrapRgb or (0xFF shl 24)

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

    // -----------------------------------------------------------------------
    // Task 13 的两张实验图
    // -----------------------------------------------------------------------

    private val pickProbeRect = Rect(PICK_PROBE_PLOT_X, PICK_PROBE_PLOT_Y,
        PICK_PROBE_PLOT_W, PICK_PROBE_PLOT_H)

    private val wrapRect = Rect(WRAP_PLOT_X, WRAP_PLOT_Y, WRAP_PLOT_W, WRAP_PLOT_H)

    /**
     * 拾取探针图的数据：11 个点、**全部同一个值**，于是画出来是一条水平线。
     *
     * <p>水平是刻意的：线段的"垂直方向"因此就是屏幕竖直方向，"线旁 3px"这句话
     * 才有唯一的意思，不必再去算一条斜线的法向。
     */
    private val pickProbeData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (PICK_PROBE_POINTS - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(
            DoubleArray(PICK_PROBE_POINTS) { it.toDouble() },
            DoubleArray(PICK_PROBE_POINTS) { PICK_PROBE_VALUE }
        )
    )

    /** 拾取探针系列：线宽 1，颜色只在这张图里出现。 */
    private val pickProbeSeries = Series("拾取探针", pickProbeData, ChartType.LINE)
        .color(pickProbeArgb).lineWidth(PICK_PROBE_LINE_WIDTH)

    private val pickProbeChart: Chart = buildPickProbeChart()

    /**
     * 跨环绕实验的数据：环容量 8，写 20 个样本，值是**绝对下标本身**（0..19）。
     *
     * <p>值取下标本身是为了让"画对了"与"画错了"在画面上长得完全不同：
     * 画对了，窗口里是一条斜率恒定的直线；少了 `baseInstance`，第一段会取到
     * 高 4 个样本的值——**同一段 x 范围上是一条同样平滑、但整体平移了 20px 的线**，
     * 并在两段交界处留下一个 20px 的跳变。
     */
    private val wrapData = RingChartData(
        arrayOf(
            AxisRange(0.0, WRAP_TOTAL.toDouble(), "样本", ""),
            AxisRange(0.0, WRAP_TOTAL.toDouble(), "值", "")
        ),
        WRAP_CAPACITY
    )

    private val wrapSeries = Series("跨环绕", wrapData, ChartType.LINE)
        .color(wrapArgb).lineWidth(3f)

    private val wrapChart: Chart = buildWrapChart()

    /** 跨环绕实验的数据是否已经写完（只写一次，像真实采集那样）。 */
    private var wrapWritten = false

    // -----------------------------------------------------------------------
    // Task 14 的两张实验图：散点
    // -----------------------------------------------------------------------

    private val scatterRect = Rect(SCATTER_PLOT_X, SCATTER_PLOT_Y,
        SCATTER_PLOT_W, SCATTER_PLOT_H)

    private val markerRect = Rect(MARKER_PLOT_X, MARKER_PLOT_Y,
        MARKER_PLOT_W, MARKER_PLOT_H)

    private val degenRect = Rect(DEGEN_PLOT_X, DEGEN_PLOT_Y,
        DEGEN_PLOT_W, DEGEN_PLOT_H)

    /**
     * 散点实验的数据。两张实验图**共用同一份**——只有 markerSize 不同。
     *
     * <p>x 那一维的值（0..4）其实没人读：x 由"样本在缓冲里的位置"隐含给出
     * （`SeriesBuffer` 只存 y），这里给的是下标本身，读起来最不容易误会。
     */
    private val scatterData = ArrayChartData(
        arrayOf(
            AxisRange(SCATTER_WINDOW_MIN, SCATTER_WINDOW_MAX, "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(
            DoubleArray(SCATTER_POINTS) { it.toDouble() },
            SCATTER_VALUES.copyOf()
        )
    )

    /**
     * 小标记的散点系列。**lineWidth 设成 4 是刻意留的**：散点根本不用它，
     * 但如果哪天这个系列被错当成折线画（变异验证就是这么做的），那条线必须足够粗，
     * 中点那 5×17 的盒子才一定被盖住——否则"中点为空"会因为线太细而变成恒真。
     */
    private val scatterSeries = Series("散点", scatterData, ChartType.SCATTER)
        .color(scatterArgb).markerSize(SCATTER_MARKER_RADIUS_SMALL).lineWidth(4f)

    /** 大标记的系列：同一份数据、同一个绘制路径，只有 markerSize 不同。 */
    private val markerBigSeries = Series("大标记", scatterData, ChartType.SCATTER)
        .color(markerBigArgb).markerSize(SCATTER_MARKER_RADIUS_BIG)

    private val scatterChart: Chart = buildScatterChart()
    private val markerChart: Chart = buildMarkerChart()

    /**
     * 退化散点系列：**半径 0**。一个像素都不该画出来（见类文档的"markerSize 退化"一节），
     * 但它在拾取里照样存在——"看不见的图元仍可拾取"是本仓库的既有约定
     * （与折线那边 `lineWidth = 0` 的隐形式是同一个行为）。
     */
    private val degenScatterSeries = Series("退化散点", scatterData, ChartType.SCATTER)
        .color(degenScatterArgb).markerSize(0f)

    private val degenChart: Chart = buildDegenChart()

    /** 退化系列在拾取里读回的 ID（在观察期取样）。它必须是 0 之外的某个值。 */
    private var degenPickId = 0

    // -----------------------------------------------------------------------
    // Task 7 的频谱实验图（子项目 D-③-1）
    //
    // 一块绘图区、一个系列、一份数据，四幕换输入。见文件上方"Task 7 的频谱实验图"。
    // -----------------------------------------------------------------------

    private val spectrumRect = Rect(SPECTRUM_PLOT_X, SPECTRUM_PLOT_Y,
        SPECTRUM_PLOT_W, SPECTRUM_PLOT_H)

    /**
     * 频谱的数据：环容量 [SPECTRUM_CAPACITY]，0 号维度是"样本序号"（没人读，
     * 频谱的 x 轴单位是 bin），1 号维度是时域采样值。
     *
     * <p>1 号维度的范围声明成 `[0, SPECTRUM_Y_MAX]`，于是 y 轴的默认窗口就是它——
     * **它同时是着色器的 `uValueRange`**，下面所有"幅值 → 屏幕行"的期望值都按它算。
     */
    private val spectrumData = RingChartData(
        arrayOf(
            AxisRange(0.0, SPECTRUM_CAPACITY.toDouble(), "样本", ""),
            AxisRange(0.0, SPECTRUM_Y_MAX, "幅度", "")
        ),
        SPECTRUM_CAPACITY
    )

    /** 频谱的 x 轴：窗口是 **bin 下标**，且刻意不取 [0, …]（见 [SPECTRUM_WINDOW_MIN]）。 */
    private val spectrumXAxis = Axis(AxisType.LINEAR, spectrumData.axisRange(0))
        .setDisplayLength(SPECTRUM_PLOT_W.toDouble())
        .setWindow(SPECTRUM_WINDOW_MIN, SPECTRUM_WINDOW_MAX)

    private val spectrumYAxis = Axis(AxisType.LINEAR, spectrumData.axisRange(1))
        .setDisplayLength(SPECTRUM_PLOT_H.toDouble())

    private val spectrumSeries = Series("频谱", spectrumData, ChartType.SPECTRUM)
        .color(spectrumArgb).lineWidth(SPECTRUM_LINE_WIDTH)

    private val spectrumChart: Chart = buildSpectrumChart()

    /**
     * 三幕各自的输入样本（CPU 参考要用它算预期幅值）。
     *
     * <p>存下来而不是"用的时候按同一个公式再生成一遍"：再生成一遍的话，
     * 参考比的是"我以为我喂进去的东西"，而真实喂进去的那一批可能不是它
     * （`Float` 截断、追加顺序、环的槽位……任何一环错了都看不见）。
     * 第四幕（全 NaN）不需要——它的期望值是"一个像素都没有"。
     */
    private var spectrumSamplesA: DoubleArray? = null
    private var spectrumSamplesB: DoubleArray? = null
    private var spectrumSamplesC: DoubleArray? = null

    /** 四幕的快照（观察期抓，校验期断言）。 */
    private var spectrumSnapshotA: Shot? = null
    private var spectrumSnapshotB: Shot? = null
    private var spectrumSnapshotC: Shot? = null
    private var spectrumSnapshotD: Shot? = null

    /** 散点图里数据下标 → 屏幕 x。**与折线的顶点取同一个映射**（不加半格）。 */
    private fun scatterX(index: Double): Double = SCATTER_PLOT_X +
            (index - SCATTER_WINDOW_MIN) / (SCATTER_WINDOW_MAX - SCATTER_WINDOW_MIN) * SCATTER_PLOT_W

    /** 散点图里数值 → 屏幕 y（与 ChartRenderLayout 同一条映射，值越大越靠上）。 */
    private fun scatterY(value: Double): Double = SCATTER_PLOT_Y + (1.0 - value) * SCATTER_PLOT_H

    /** 两张散点实验图的快照（观察期抓，校验期断言）。 */
    private var scatterSnapshot: Shot? = null
    private var markerSnapshot: Shot? = null

    /** 散点图上三个探测点的拾取结果，在它们还画着的那一帧记录下来。 */
    private var scatterPickCaptured = false

    /** 探测点 1（第一个标记的中心）读回的 ID，以及 payload 是不是那个系列对象。 */
    private var scatterOnMarkerId = 0
    private var scatterOnMarkerPayloadOk = false

    /** 探测点 2（第二个标记的中心）读回的 ID。系列级发号时它必须与上面那个相同。 */
    private var scatterOnMarker2Id = 0

    /** 探测点 3（相邻两点的中点）读回的 ID。它必须是 0——**热区同样不许把两点连起来**。 */
    private var scatterMidId = 0

    /**
     * 探测点 4（[SCATTER_BLANK_X] / [SCATTER_BLANK_Y]）读回的 ID。它必须是 0。
     *
     * <p>这一点不在任何标记附近，却**落在"错用折线顶点程序"那条斜带上**
     * （见 [SCATTER_BLANK_Y] 的推导）。它管的是热区的**形状**：少了它，
     * 一个把热区画成"从数据值斜拉到绘图区底部"的长条的实现照样全绿——
     * 而那时用户点标记下方二十几像素的空白也会命中，画面却完全正常。
     */
    private var scatterBlankId = 0

    // ---- 观察期抓下来的快照（校验期才断言，见 [captureObservations]）----

    private var streamSnapshot: Shot? = null

    /** 拾取探针图那一块的快照：用来钉住"线确实只画了 1px"。 */
    private var pickProbeSnapshot: Shot? = null

    /** 跨环绕实验那一块的快照：用来钉住"跨环绕处是一条连续的线"。 */
    private var wrapSnapshot: Shot? = null

    /**
     * 探针图上三个探测点的拾取结果，在探针图还画着的那一帧记录下来。
     *
     * <p>为什么必须当场记：这三条断言问的是**探针图**（线宽 1），而它只在观察期画
     * （理由见 [PICK_PROBE_PLOT_X] 那一段）。校验帧上它已经不在画面里，
     * 那时再问只会得到"没命中"，而那与"拾取坏了"长得一模一样。
     */
    private var pickProbeCaptured = false

    /** 探测点 1（线上）读回的 ID，以及它的 payload 是不是那个系列对象。 */
    private var pickOnLineId = 0
    private var pickOnLinePayloadOk = false

    /** 探测点 2（线旁 3px）读回的 ID。容差生效时它必须与线上那个相同。 */
    private var pickBesideId = 0

    /** 探测点 3（线旁 20px）读回的 ID。它必须是 0（什么都没命中）。 */
    private var pickFarId = 0

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

        /** 某一列里最上面那个该颜色像素的**行号**；整列都没有这个颜色时返回 null。 */
        fun topInkRow(x: Int, rgb: Int): Int? {
            for (y in 0 until h) {
                if (at(x, y) == rgb) return y
            }
            return null
        }

        /** 某一列里该颜色像素的行范围 `[上, 下]`；整列都没有这个颜色时返回 null。 */
        fun inkRange(x: Int, rgb: Int): IntRange? {
            var top = -1
            var bottom = -1
            for (y in 0 until h) {
                if (at(x, y) == rgb) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            return if (top < 0) null else top..bottom
        }

        /**
         * "最高的那一列"：频谱的峰在哪里。
         *
         * <p>返回列位置是"最高行在 `minTop + tiePx` 之内的那些列的**平均**"，
         * 不是"最高的那一列"——峰顶两侧各有一列的最高行只比它低一两个像素
         * （线段一升一降），取平均比取单个极值稳，也不会因为抗锯齿差一个像素就翻。
         *
         * @return 一列都没有该颜色时返回 null（"这张图压根没画"与"峰在别处"必须分开）
         */
        fun inkPeak(rgb: Int, tiePx: Int): InkPeak? {
            var best = Int.MAX_VALUE
            for (x in 0 until w) {
                val top = topInkRow(x, rgb) ?: continue
                if (top < best) best = top
            }
            if (best == Int.MAX_VALUE) return null
            var sum = 0.0
            var count = 0
            for (x in 0 until w) {
                val top = topInkRow(x, rgb) ?: continue
                if (top <= best + tiePx) {
                    sum += x
                    count++
                }
            }
            return InkPeak(sum / count, best)
        }
    }

    /** [Shot.inkPeak] 的结果：峰所在的列（小数）与那一列的最高行。 */
    private class InkPeak(val column: Double, val topRow: Int)

    /**
     * 频谱的 CPU 参考：**直接按定义**算一个 bin 的幅值。
     *
     * <p>`|X[k]| = |Σ x[t]·w[t]·e^{-2πikt/n}| · 2/n · 窗补偿`，只算被问到的那几个 bin
     * （不像 FftVerifier 那份要算整条半谱，所以这里是 O(N) 一次、可以随用随算）。
     *
     * <p><strong>它为什么在这里。</strong>下面那条判别式问的是"两个 bin 之间那一列上的墨迹
     * 落在**哪一行**"，而那一行只由两个 bin 的幅值决定——幅值不知道的话，
     * 判据就只剩"有墨迹"（对正确与坏掉的实现都成立，是橡皮图章）。
     * 幅值里只有 `|X[k0]| = 1.0` 是"由构造已知"的，`|X[k0+1]| ≈ 0.68` 不是（它由窗的主瓣决定）。
     *
     * <p>刻意**不写成 FFT**：这份参考的全部价值在于**它显然是对的**——
     * 没有位反转、没有蝶形、没有 shared memory，只有一个循环
     * （与 FftVerifier 的 `CpuReference` 同一条理由）。
     *
     * <p>它与被测路径**共用 `FftWindow`**（同一套窗系数、同一个补偿），所以它比的是"窗上面的变换"，
     * 不是窗本身；而"GPU 的输出与这个式子逐 bin 相符"这一条由 FftVerifier 钉着。
     */
    private class SpectrumReference(
        private val n: Int,
        private val samples: DoubleArray,
        window: FftWindow,
    ) {

        private val w = DoubleArray(n) { window.coefficient(it, n) }

        private val scale = 2.0 / n * window.compensation(n)

        /** 第 `k` 个 bin 的幅值（半谱归一化之后，与 GPU 的输出同口径）。 */
        fun magnitude(k: Int): Double {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val v = samples[t] * w[t]
                val a = -2.0 * PI * k * t / n
                sr += v * cos(a)
                si += v * sin(a)
            }
            return hypot(sr, si) * scale
        }
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

    /**
     * 造拾取探针图：一条线宽 1 的水平线，见 [PICK_PROBE_VALUE] 与 [PICK_PROBE_PLOT_H]。
     */
    private fun buildPickProbeChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, pickProbeData.axisRange(0))
            .setDisplayLength(PICK_PROBE_PLOT_W.toDouble())
            .setWindow(0.0, (PICK_PROBE_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, pickProbeData.axisRange(1))
            .setDisplayLength(PICK_PROBE_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("拾取探针").add(pickProbeSeries)
        return chart
    }

    /**
     * 造跨环绕实验图：x 轴窗口跨过环绕点，见 [WRAP_WINDOW_START]。
     *
     * <p>y 轴窗口就是数据范围 [0, [WRAP_TOTAL]]，于是每个样本占
     * {@code WRAP_PLOT_H / WRAP_TOTAL = 5px}——"错位 4 个样本"= 20px 这句话能就地算出来。
     */
    private fun buildWrapChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, wrapData.axisRange(0))
            .setDisplayLength(WRAP_PLOT_W.toDouble())
            .setWindow(WRAP_WINDOW_START, WRAP_WINDOW_END)
        val yAxis = Axis(AxisType.LINEAR, wrapData.axisRange(1))
            .setDisplayLength(WRAP_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("跨环绕").add(wrapSeries)
        return chart
    }

    /**
     * 造散点实验图：5 个点、值在 0.1 与 0.9 之间大幅折返，见 [SCATTER_VALUES]。
     *
     * <p>x 窗口左右各留半格，于是 5 个标记都完整落在绘图区内部。
     */
    private fun buildScatterChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, scatterData.axisRange(0))
            .setDisplayLength(SCATTER_PLOT_W.toDouble())
            .setWindow(SCATTER_WINDOW_MIN, SCATTER_WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, scatterData.axisRange(1))
            .setDisplayLength(SCATTER_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("散点").add(scatterSeries)
        return chart
    }

    /**
     * 造标记尺寸对比图：**与散点图逐项相同，只换 markerSize**（见 [MARKER_PLOT_X]）。
     *
     * <p>轴不能共用（轴是可变的），所以要另起两根，但数据与系列之外的配置一字不差。
     */
    private fun buildMarkerChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, scatterData.axisRange(0))
            .setDisplayLength(MARKER_PLOT_W.toDouble())
            .setWindow(SCATTER_WINDOW_MIN, SCATTER_WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, scatterData.axisRange(1))
            .setDisplayLength(MARKER_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("标记尺寸").add(markerBigSeries)
        return chart
    }

    /**
     * 造退化散点图：与散点图同一份数据、同一个 x 窗口，只有 markerSize 不同（= 0）。
     */
    private fun buildDegenChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, scatterData.axisRange(0))
            .setDisplayLength(DEGEN_PLOT_W.toDouble())
            .setWindow(SCATTER_WINDOW_MIN, SCATTER_WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, scatterData.axisRange(1))
            .setDisplayLength(DEGEN_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("退化散点").add(degenScatterSeries)
        return chart
    }

    /**
     * 造频谱实验图：一个 `ChartType.SPECTRUM` 系列、x 轴窗口是 bin 下标。
     *
     * <p>图里**没有任何别的东西**：网格、刻度、底色一概不画。
     * 下面那些断言看的是"这一块地方该颜色的墨迹落在哪一行"，
     * 多画一样东西就要多解释一次"那些像素是谁的"。
     */
    private fun buildSpectrumChart(): Chart {
        val chart = Chart(spectrumXAxis, spectrumYAxis)
        chart.addLayer("频谱").add(spectrumSeries)
        return chart
    }

    /**
     * Task 14 的两张散点实验图。
     *
     * <p>第二张（大标记）只是同一个场景换了 markerSize——两张图的数据、x 窗口、
     * 绘图区尺寸全部相同，唯一的变量就是标记边长。
     */
    private fun drawScatterCharts(gc: Gc) {
        gc.charts.draw(scatterChart, scatterRect, gc.width, gc.height)
        gc.charts.draw(markerChart, markerRect, gc.width, gc.height)
    }

    /**
     * 退化散点图。**每帧都画**（理由见 [DEGEN_PLOT_X]）：它画不出任何像素，
     * 因此不会给颜色集合或任何像素计数添乱；而"全画面没它的颜色"这条断言
     * 需要它处于**校验帧的场景**里。
     */
    private fun drawDegenerateScatterChart(gc: Gc) {
        gc.charts.draw(degenChart, degenRect, gc.width, gc.height)
    }

    /** 跨环绕实验里数据下标 → 屏幕 x。 */
    private fun wrapX(index: Double): Double = WRAP_PLOT_X +
            (index - WRAP_WINDOW_START) / (WRAP_WINDOW_END - WRAP_WINDOW_START) * WRAP_PLOT_W

    /** 跨环绕实验里数值 → 屏幕 y（与 ChartRenderLayout 同一条映射，值越大越靠上）。 */
    private fun wrapY(value: Double): Double = WRAP_PLOT_Y + (1.0 - value / WRAP_TOTAL) * WRAP_PLOT_H

    /** 跨环绕实验里屏幕 x 处那条线**应该**落在哪一行（x 是设备像素）。 */
    private fun wrapLineRowAt(x: Double): Double = wrapY(
        WRAP_WINDOW_START + (x - WRAP_PLOT_X) *
                (WRAP_WINDOW_END - WRAP_WINDOW_START) / WRAP_PLOT_W
    )

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
        // 3b2) 退化散点图：**每帧都画**（不是只在观察期），理由见 DEGEN_PLOT_X。
        drawDegenerateScatterChart(gc)

        // 3c) Task 12 的三张实验图。**只在观察期画**：
        //     第 1 组要"滚动若干帧"才成立、第 3 组要"前后两帧"才成立，
        //     而观察期一过就让画面回到 Task 10/11 的原样——于是前 15 条断言的
        //     期望值（尤其是"画面只有这 7 种颜色"）一字未改。
        if (n < STREAM_FRAMES) {
            drawStreamingChart(gc)
            drawGapChart(gc)
            drawCrossChart(gc, n)
            // Task 13 的两张实验图，同样只在观察期画。理由见文件上方"Task 13 的两张实验图"。
            drawPickProbeChart(gc)
            drawWrapChart(gc)
            // Task 14 的两张散点实验图，同样只在观察期画（同样的理由）。
            drawScatterCharts(gc)
            // Task 7 的频谱实验图，同样只在观察期画（同样的理由）。它一块绘图区演四幕。
            drawSpectrumChart(gc, n)
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

    /** 拾取探针图：一条线宽 1 的水平线，见 [PICK_PROBE_PLOT_H]。 */
    private fun drawPickProbeChart(gc: Gc) {
        gc.charts.draw(pickProbeChart, pickProbeRect, gc.width, gc.height)
    }

    /**
     * 频谱实验图：**一张图、四幕**。
     *
     * <p>四幕分别是：单位幅度正弦（峰在 bin [SPECTRUM_K0_A]）→ 换频率 → 换幅度 → 全 NaN。
     * 画面因此逐帧在变，而**这条绘制路径、这个系列对象、这一份缓冲自始至终是同一个**——
     * "只有第一幕算得对"或"状态跨幕泄漏"的实现没有藏身处。
     *
     * <p>每次换幕都**整整附录一环**：环里于是只剩这一幕的样本，谱是干净的单频。
     *
     * @param n 本帧的绘制帧下标（与 `frame` 差 1，见 [drawScene]）
     */
    private fun drawSpectrumChart(gc: Gc, n: Int) {
        when (n) {
            0 -> spectrumSamplesA = appendSpectrumSine(SPECTRUM_K0_A, SPECTRUM_AMP)
            SPECTRUM_STAGE_B -> spectrumSamplesB = appendSpectrumSine(SPECTRUM_K0_B, SPECTRUM_AMP)
            SPECTRUM_STAGE_C -> spectrumSamplesC =
                appendSpectrumSine(SPECTRUM_K0_A, SPECTRUM_AMP_LOW)
            SPECTRUM_STAGE_D -> appendSpectrumGap()
        }
        gc.charts.draw(spectrumChart, spectrumRect, gc.width, gc.height)
    }

    /**
     * 附录一整环（[SPECTRUM_CAPACITY] 个样本）的正弦，返回刚写进去的那批样本
     * （CPU 参考要用它）。
     *
     * <p>样本**过一遍 `Float`**：GPU 里存的就是 float32，参考拿 double 去比对的话，
     * 比出来的是"输入本来就有的取整误差"，不是变换误差（与 FftVerifier 的 `cosineInput` 同一条）。
     *
     * <p>写法是"先攒成数组、再整批 append"而不是边算边 append：两者写进环的字节必须**逐位相同**，
     * 否则参考算的是另一批数。这里让它们只可能来自同一个数组。
     */
    private fun appendSpectrumSine(k0: Int, amp: Double): DoubleArray {
        val samples = DoubleArray(SPECTRUM_CAPACITY) {
            (amp * cos(2.0 * PI * k0 * it / SPECTRUM_CAPACITY)).toFloat().toDouble()
        }
        for (i in samples.indices) {
            spectrumData.append(i.toDouble(), samples[i])
        }
        return samples
    }

    /**
     * 第四幕：整整一环全 **NaN**——反证用的输入。
     *
     * <p>全 NaN 的谱是 NaN，着色器的 `isnan(aY0) || isnan(aY1)` 会把每一个实例都退化到
     * 裁剪空间之外，于是那一块地方**一个像素都不该有**。
     * 这一条同时是"跨帧残留"的检查：上一幕的谱刚在那里画过。
     */
    private fun appendSpectrumGap() {
        for (i in 0 until SPECTRUM_CAPACITY) {
            spectrumData.append(i.toDouble(), Double.NaN)
        }
    }

    /**
     * 跨环绕实验图：**先把 20 个样本写进环里**（只写一次），再画。
     *
     * <p>写在这里而不是 `start()` 里：{@code RingChartData} 的硬前提是"只有一个写者"，
     * 而 GL 线程就是这里的那个写者（与上面流式实验同一个做法）。写一次就够——
     * 这一屏要验的是"环绕过之后槽位与下标错开了"，与帧数无关。
     */
    private fun drawWrapChart(gc: Gc) {
        if (!wrapWritten) {
            repeat(WRAP_TOTAL) { wrapData.append(it.toDouble(), it.toDouble()) }
            wrapWritten = true
        }
        gc.charts.draw(wrapChart, wrapRect, gc.width, gc.height)
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
    private fun captureObservations(bridge: FXGLTransfer, h: Int) {
        // 观察期的最后一帧：流式图与缺口图都还在画，各抓一张。
        if (frame == STREAM_FRAMES) {
            streamSnapshot = grab(h, streamRect)
            gapSnapshot = grab(h, gapRect)
            // Task 13 的两张实验图也在这一帧上取样：它们**只在观察期画**，
            // 校验帧上已经不在画面里了（理由见各自的常量说明）。
            pickProbeSnapshot = grab(h, pickProbeRect)
            wrapSnapshot = grab(h, wrapRect)
            capturePickProbes(bridge)
            // Task 14 的两张散点实验图也在这一帧上取样：它们同样**只在观察期画**。
            scatterSnapshot = grab(h, scatterRect)
            markerSnapshot = grab(h, markerRect)
            captureScatterPicks(bridge)
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
        // Task 7 的四幕频谱各抓一张：它们同样**只在观察期画**，
        // 而且每一幕的像素只存在于它自己那几帧里（下一幕会把它整个换掉）。
        if (frame == SPECTRUM_SHOT_A) spectrumSnapshotA = grab(h, spectrumRect)
        if (frame == SPECTRUM_SHOT_B) spectrumSnapshotB = grab(h, spectrumRect)
        if (frame == SPECTRUM_SHOT_C) spectrumSnapshotC = grab(h, spectrumRect)
        if (frame == SPECTRUM_SHOT_D) spectrumSnapshotD = grab(h, spectrumRect)
    }

    /**
     * 在探针图还画着的那一帧，把三个探测点的拾取结果记下来。
     *
     * <p>**为什么不在校验帧问。**探针图（线宽 1）只在观察期画，校验帧的画面上没有它；
     * 那时去问只会得到"什么都没命中"，而那个结果与"拾取坏了"完全一样——
     * 一条恒真的反证断言。所以在这里当场问，把答复存下来到校验帧再判定。
     *
     * <p>此刻的拾取缓冲里是**本帧刚画进去的内容**：`RenderBatch.beginFrame` 每个渲染趟
     * 把它标成无效，图表路径的 `withPickPass` 再把它标回有效（见 `RenderBatch` 的
     * 那两条注释）。本槽位在 `onRender` 回调里，而 `onFrame` 已经跑完（见 `FXGLTransfer`），
     * 所以读到的是本帧的 ID，不是上一帧的。
     */
    private fun capturePickProbes(bridge: FXGLTransfer) {
        val gc = bridge.gc() ?: return
        val onLine = gc.pick(PICK_PROBE_X, PICK_PROBE_ON_LINE_Y)
        pickOnLineId = onLine?.id() ?: 0
        pickOnLinePayloadOk = onLine?.payload() === pickProbeSeries
        pickBesideId = gc.pick(PICK_PROBE_X, PICK_PROBE_BESIDE_Y)?.id() ?: 0
        pickFarId = gc.pick(PICK_PROBE_X, PICK_PROBE_FAR_Y)?.id() ?: 0
        pickProbeCaptured = true
    }

    /**
     * 在散点图还画着的那一帧，把三个探测点的拾取结果记下来（理由同 [capturePickProbes]）。
     *
     * <p>三个点分别是：第一个标记的中心、第二个标记的中心、以及**相邻两点的中点**。
     * 第三个点问的是"热区有没有把两点连起来"——它是画面那条"中点为空"的孪生断言：
     * 拾取坏了**画面一点都不会变坏**，只会让点击落在不该命中的地方。
     */
    private fun captureScatterPicks(bridge: FXGLTransfer) {
        val gc = bridge.gc() ?: return
        val onMarker = gc.pick(
            scatterX(0.0).toFloat(), scatterY(SCATTER_VALUES[0]).toFloat())
        scatterOnMarkerId = onMarker?.id() ?: 0
        scatterOnMarkerPayloadOk = onMarker?.payload() === scatterSeries
        scatterOnMarker2Id = gc.pick(
            scatterX(1.0).toFloat(), scatterY(SCATTER_VALUES[1]).toFloat())?.id() ?: 0
        scatterMidId = gc.pick(
            ((scatterX(0.0) + scatterX(1.0)) / 2.0).toFloat(),
            ((scatterY(SCATTER_VALUES[0]) + scatterY(SCATTER_VALUES[1])) / 2.0).toFloat()
        )?.id() ?: 0
        scatterBlankId = gc.pick(SCATTER_BLANK_X, SCATTER_BLANK_Y)?.id() ?: 0
        // 退化系列（markerSize = 0）：画不出像素，**但拾取热区照旧**。
        // 这一条是"全画面没有它的颜色"那个断言的前提（那张图要是压根没画，
        // 那条断言就是恒真的）。
        degenPickId = gc.pick(DEGEN_PROBE_X, DEGEN_PROBE_Y)?.id() ?: 0
        scatterPickCaptured = true
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
        captureObservations(bridge, h)
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

        // ---- 14. ★ 拾取：系列级 ID、容差与裁剪 ----
        //
        // 拾取是本仓库里最危险的一类子系统：**错误的拾取不会让任何画面变坏**，
        // 只会让点击落在错误的对象上。所以这里断言的量必须是"读回的 ID 是哪一个"，
        // 不能是"有没有返回一个坐标"——后者对"永远返回同一个 ID"同样成立。
        //
        // **为什么这些断言不落在「斜坡」那条线上。**同一块绘图区里还压着一条
        // `lineWidth = 0` 的「退化」系列（见 buildChart 的说明）：它走同一份数据、
        // 拾取容差同样是 4px，而且**画在斜坡之后**——于是斜坡线上的每一个像素
        // 都被它盖住，拾取到的是那个**肉眼看不见**的系列。这是"拾取只由几何决定、
        // 与可见性无关"（与 Gc 那边"全透明图元仍可拾取"同一条规则）的直接后果，
        // 本任务不改变它，只把断言挪到没有被隐形系列压住的曲线上。
        //
        // 系列对象按**名字**从装配结果里取：写成 layers()[0].series()[0] 的话，
        // 装配顺序一改就会静默指到另一个系列上，而症状看起来像是"拾取串号了"。
        println("\n-- ★ 拾取：系列级 ID、容差与裁剪 --")
        val zigSeries = zigzagChart.layers().first().series().first { it.name() == "折返" }
        val spillSeries = chart.layers().first().series().first { it.name() == "溢出" }

        // gc() 在这里不会为 null（都画了 100 多帧了），但**不能写成 `?: return`**：
        // 那样一旦为 null，整个校验体就静默跳过、退出码还是 0——正是本文件最忌讳的
        // "静默的绿"。所以把它当成一条断言来报。
        val gc = bridge.gc()
        if (gc == null) {
            report("前提：拾取查询需要 GL 上下文（Gc）", false,
                "bridge.gc() 返回 null：拾取那一组断言无法进行")
        } else {
            fun describePayload(p: Any?): String = when {
                p === null -> "null（没命中，或 ID 没注册）"
                p === zigSeries -> "「折返」那个 Series 对象"
                p === spillSeries -> "「溢出」那个 Series 对象"
                else -> "另一个对象（${p::class.simpleName}）"
            }

            // 折返图线段 1→2 的中点，与上一节那条"实例属性"断言是同一个点。
            val zigHit = gc.pick(segMidX.toFloat(), segMidY.toFloat())
            val zigId = zigHit?.id() ?: 0
            report("曲线上的像素命中该系列（payload 就是那个 Series 对象）",
                zigId != 0 && zigHit?.payload() === zigSeries,
                "($segMidX,$segMidY) 实际 ID=$zigId（期望非 0），" +
                        "payload=${describePayload(zigHit?.payload())}")

            // 系列级 ID：同一条曲线的**两个不同位置**必须读到同一个号。
            // 按点发号（每个样本一个 ID）会让这两处读到不同的号——那样"点中一条曲线"
            // 得到的号与点中另一处得到的号不同，而画面上什么都看不出来。
            val zigMidX2 = ((zigX(3.0) + zigX(4.0)) / 2.0).toInt()
            val zigId2 = gc.pick(zigMidX2.toFloat(), segMidY.toFloat())?.id() ?: 0
            report("系列级 ID：同一条曲线上两个不同位置读到同一个 ID",
                zigId != 0 && zigId == zigId2,
                "($segMidX,$segMidY) ID=$zigId，($zigMidX2,$segMidY) ID=$zigId2" +
                        "（期望相等且非 0）")

            // 拾取四边形必须**落在画面里那条线上**——这是"点到的地方就是看到的地方"。
            // 主图的斜坡是**斜的**、而且它的实例区间不从槽位 0 开始（窗口 [10,90] →
            // baseInstance = 10），所以 ID pass 一旦漏掉 baseInstance，它的拾取四边形会
            // 整体挪到别的样本上，这两个点就都落空。**平直的曲线（溢出）抓不到这个**：
            // 它的值在一大片下标上完全相同，挪了也还落在原地。
            // 命中的是压在斜坡上的那个隐形式（见本节开头），这里只要求"两处都命中、
            // 且是同一个号"——不去钉具体是哪一个，因为那属于另一个决定。
            val rampHitA = gc.pick(400f, 300f)
            val rampHitB = gc.pick(250f, 380f)
            val rampIdA = rampHitA?.id() ?: 0
            val rampIdB = rampHitB?.id() ?: 0
            report("斜坡线上两个不同位置都命中同一个系列（拾取四边形与画面同一条线）",
                rampIdA != 0 && rampIdA == rampIdB,
                "(400,300) ID=$rampIdA（${describePayload(rampHitA?.payload())}），" +
                        "(250,380) ID=$rampIdB（${describePayload(rampHitB?.payload())}）" +
                        "——期望相等且非 0")

            // 两条曲线不串号。零号是"什么都没命中"，所以两个号都必须非 0 才有意义；
            // 而"号不同"还不够——它们各自还得解析回**自己**那个 Series 对象。
            // 溢出系列在 x=400 处是值 0.85 → 屏幕 y = 160 的水平线。
            val spillHit = gc.pick(400f, 160f)
            val spillId = spillHit?.id() ?: 0
            report("两条曲线不串号：折返与溢出的 ID 互不相同，且各自解析回自己",
                zigId != 0 && spillId != 0 && zigId != spillId &&
                        zigHit?.payload() === zigSeries && spillHit?.payload() === spillSeries,
                "折返 ID=$zigId（payload=${describePayload(zigHit?.payload())}），" +
                        "溢出 ID=$spillId（payload=${describePayload(spillHit?.payload())}）")

            // 裁剪在 ID pass 里同样生效：被裁掉的部分不可拾取，与画面一致。
            // 探测点是**溢出系列被裁掉的那一段**：值从 1.2 跌到 0.85 的那一条陡线
            // （数据下标 30→31，屏幕 x 250→257.5），值 1.2 对应屏幕 y = 20，
            // **整段在绘图区上方**。它的拾取四边形（容差 4px → x 约 246..261）
            // 会覆盖 (253,60) 一带，而 ID pass 一旦丢掉 scissor，那一片就会带上溢出的 ID
            // ——**画面完全正常，只有点击落在错误的对象上**。
            // 它不是橡皮图章：上面"溢出系列在绘图区内存在"保证了这个系列真的在画；
            // 而且实测过——把 ID pass 的 scissor 测试关掉，这一条立刻失败（见最终报告）。
            val outsideId = gc.pick(253f, 60f)?.id() ?: 0
            report("拾取也受裁剪约束：绘图区之外不可拾取", outsideId == 0,
                "(253,60) ID=$outsideId，期望 0——非 0 说明 ID pass 没有按 scissor 裁剪")
        }

        // 容差那一组问的是**探针图**（线宽 1），所以用的是观察期当场记下的三个 ID，
        // 见 [capturePickProbes]：探针图只在观察期画，校验帧上它已经不在画面里了。
        val probeShot = pickProbeSnapshot
        if (!pickProbeCaptured || probeShot == null) {
            report("前提：探针图的快照与三个探测点的拾取结果都取到了", false,
                "pickProbeCaptured=$pickProbeCaptured，pickProbeSnapshot=${probeShot != null}")
        } else {
            // 局部坐标：快照就是探针图那一块，左上角为原点。
            val probeCol = (PICK_PROBE_X - PICK_PROBE_PLOT_X).toInt()
            val probeRow = (PICK_PROBE_ON_LINE_Y - PICK_PROBE_PLOT_Y).toInt()

            // **前提**：线确实只画了 1px 高。少了这一条，下面"线旁 3px 仍命中"
            // 对"线本来就有 8px 粗"的实现同样成立——那它就是一条橡皮图章。
            val thin = probeShot.countIn(probeCol - 3, probeRow - 8, probeCol + 3, probeRow + 8,
                pickProbeRgb)
            report("前提：探针线确实只画了 1px 高（否则容差那两条恒真）", thin == 7,
                "以探测点为中心 7 列 × 17 行里有 $thin px，期望 7（每列恰好 1 px）")

            report("探针线上的一点命中探针系列（否则下面两条恒真）",
                pickOnLineId != 0 && pickOnLinePayloadOk,
                "线上 ID=$pickOnLineId，payload 是探针系列=$pickOnLinePayloadOk")

            // 容差：线只有 1px 宽，要求用户精确点中是不合理的。
            // **这是刻意行为，不是 bug**——与"全透明图元仍可拾取"
            // "文本的可拾取范围比墨迹大一圈"同类。
            val besidePx = (PICK_PROBE_BESIDE_Y - PICK_PROBE_ON_LINE_Y).toInt()
            report("拾取容差：线旁 ${besidePx}px 仍命中（线画 1px）",
                pickBesideId != 0 && pickBesideId == pickOnLineId,
                "线上 ID=$pickOnLineId，线旁 ${besidePx}px ID=$pickBesideId" +
                        "（期望相等且非 0；容差是 $PICK_TOLERANCE_PX px 半宽）")

            // 成对：容差不能是"无边界"。少了这一条，"容差开成 100px"照样通过，
            // 而那会让整个绘图区都变成某条曲线的热区。
            val farPx = (PICK_PROBE_FAR_Y - PICK_PROBE_ON_LINE_Y).toInt()
            report("拾取不越界：线旁 ${farPx}px 不命中",
                pickFarId == 0,
                "线旁 ${farPx}px（行 ${PICK_PROBE_FAR_Y.toInt()}）ID=$pickFarId，期望 0")
        }

        // ---- 15. ★ 跨环绕：baseInstance 生效，且那一个实例的第二端读到的是槽位 0 ----
        //
        // 这一组守两件都会"画出一条看着正常的假线"的事：
        //   (a) `drawArraysInstancedBaseInstance` 的**最后一个参数**。实例属性按
        //       `gl_InstanceID` 取，而它**每次都从 0 开始**（`baseInstance` 不影响它，
        //       只影响属性取哪一份数据）。少了它，跨环绕那一屏的第一段会取到环里
        //       **别的槽位**的数据：线照样画得出来、照样平滑，只是显示的值全错。
        //   (b) 跨环绕点那一个实例的第二端：读的是偏移 `capacity*4`，那里必须放着
        //       槽位 0 的值（环的槽位 capacity 就是槽位 0）。不写就是一条掉到 0 的斜线。
        //
        // 判别式都是**位置**：窗口左端在数据下标 12（环绕点是 16），正确实现下
        // x = 640 处的线在局部 y ≈ 38.8；少了 baseInstance 时第一段整体上移 4 个样本
        // （每个样本 5px），那里是局部 y ≈ 18.8；少了镜像时 x 640 处仍对，
        // 但局部 x 135..158 那一段会斜着穿到 y ≈ 45..90。三者差 20px 以上，一眼可辨。
        println("\n-- ★ 跨环绕：baseInstance 必须生效 --")
        val wrapShot = wrapSnapshot
        if (wrapShot == null) {
            report("前提：跨环绕实验的快照抓到了", false, "wrapSnapshot 为 null")
        } else {
            val probeX = 640.0
            val col = (probeX - WRAP_PLOT_X).toInt()
            val rowOn = wrapLineRowAt(probeX) - WRAP_PLOT_Y
            // 少传 baseInstance 时第一段取到的是槽位 0..3（数据 16..19），
            // 而不是槽位 4..7（数据 12..15）：整段线因此上移 4 个样本。
            val shiftRows = (WRAP_WINDOW_START.toInt() and (WRAP_CAPACITY - 1)) *
                    (WRAP_PLOT_H / WRAP_TOTAL)
            val rowWrong = rowOn - shiftRows

            val onCount = wrapShot.countIn(col - 2, rowOn.toInt() - 5, col + 2, rowOn.toInt() + 5,
                wrapRgb)
            val wrongCount = wrapShot.countIn(col - 2, rowWrong.toInt() - 5, col + 2,
                rowWrong.toInt() + 5, wrapRgb)
            report("跨环绕处那条线画在它该在的行上（局部 y≈${"%.1f".format(rowOn)}）", onCount > 0,
                "x=$probeX 附近 ${onCount} px，期望 > 0（线的中心在屏幕 y=${"%.1f".format(rowOn + WRAP_PLOT_Y)}，" +
                        "即局部行 ${"%.1f".format(rowOn)}）")
            report("少了 baseInstance 时线会落到的那一块是空的", wrongCount == 0,
                "x=$probeX 附近局部行 ${rowWrong.toInt()}（= 正确位置上方 $shiftRows px）" +
                        "有 $wrongCount px，期望 0——非 0 说明第一段取的是别的槽位的数据")

            // 整段都在画（不是只有左边那一段）。
            val rightX = 910.0
            val rightRow = wrapLineRowAt(rightX) - WRAP_PLOT_Y
            val rightCount = wrapShot.countIn((rightX - WRAP_PLOT_X).toInt() - 5,
                rightRow.toInt() - 5, (rightX - WRAP_PLOT_X).toInt() + 5, rightRow.toInt() + 5,
                wrapRgb)
            report("窗口右端也在画（证明整段都在，不是只有一段）", rightCount > 0,
                "x=$rightX 附近 $rightCount px，期望 > 0")

            // ---- 跨环绕点那一个实例：它的第二端必须读到**槽位 0 的值** ----
            //
            // 它两端分别落在环的最后一个槽位（7）与槽位 0 上，而"同一个缓冲、偏移差 4 字节"
            // 这一招在这里读的是偏移 capacity*4 —— 缓冲末尾那个余量 float。
            // 环的本质是"槽位 capacity 就是槽位 0"，所以那个位置**不是垃圾**，
            // 它必须被写成槽位 0 的值（见 SeriesUploadPlan.mirrorSourceIndex）；
            // 不写它就恒为 0，于是这里会画出一条**从正常值掉到 0 的斜线**——
            // 不是乱码、不报错，看着还挺像一条信号。
            val badColA = wrapX(15.0) - WRAP_PLOT_X      // 坏线的第一端：数据下标 15
            val badColB = wrapX(16.0) - WRAP_PLOT_X      // 坏线的第二端：数据下标 16
            val badRowA = wrapY(15.0) - WRAP_PLOT_Y      // 该处 y = 635 → 局部 25
            val badRowB = wrapY(0.0) - WRAP_PLOT_Y       // 读到恒为 0 的余量 → 局部 100

            fun colAtRow(row: Double): Double =
                badColA + (row - badRowA) * (badColB - badColA) / (badRowB - badRowA)

            // 取坏线的**中段**（局部行 45..90，两端各留 2 行余量）：
            // 正确的线在那里是局部行 20..25，离得很远，所以这一块只可能被坏线占。
            val dropRowLo = 45
            val dropRowHi = 90
            val dropColLo = colAtRow(dropRowLo + 2.0).toInt()
            val dropColHi = colAtRow(dropRowHi - 2.0).toInt()
            val dropCount = wrapShot.countIn(dropColLo, dropRowLo, dropColHi, dropRowHi, wrapRgb)
            report("跨环绕处没有「掉到 0」的那条斜线（它的必经之路是空的）", dropCount == 0,
                "局部 x $dropColLo..$dropColHi × y $dropRowLo..$dropRowHi 里有 $dropCount px，期望 0" +
                        "——非 0 说明槽位 capacity（= 槽位 0）上那个镜像没写，" +
                        "跨环绕的实例读到的是从未写过的余量 float（恒为 0）")

            // 逐列取最上面的那个折线像素，看它是不是一条**连续、单调**的线。
            // **整条线都要扫**：跨环绕点那一段（局部 x 123..165）曾经只能被避开
            // （它当时是一条掉到 0 的斜线），镜像补上之后它就在线上了——
            // 把范围改回来本身就是这次修复的判据之一。
            fun straightness(from: Int, to: Int): Triple<Int, Int, Int> {
                var missing = 0
                var worst = 0
                var reverse = 0
                var previous = -1
                for (x in from until to) {
                    var row = -1
                    for (y in 0 until wrapShot.h) {
                        if (wrapShot.at(x, y) == wrapRgb) {
                            row = y
                            break
                        }
                    }
                    if (row < 0) {
                        missing++
                        continue
                    }
                    if (previous >= 0) {
                        val jump = kotlin.math.abs(row - previous)
                        if (jump > worst) worst = jump
                        if (row > previous) reverse++
                    }
                    previous = row
                }
                return Triple(missing, worst, reverse)
            }

            val whole = straightness(1, 288)
            report("跨环绕点前后是一条连续、单调的线（含跨环绕那一段）",
                whole.first == 0 && whole.second <= 2 && whole.third == 0,
                "局部 x 1..287：缺 ${whole.first} 列，最大跳变 ${whole.second} px，" +
                        "反向 ${whole.third} 处（期望：都不缺列、跳变 ≤ 2、无反向）")
        }

        // ---- 16. ★ 散点：点与点之间没有连线 ----
        //
        // **这一组唯一有判别力的量是"相邻两点的中点处有没有像素"。**
        // 那看起来更直观的"每个数据点位置有像素"对**折线**同样成立——折线的顶点
        // 恰恰就落在数据点上（ChartVerifier 第 1 组那条"斜坡恰好穿过 (250,380)"
        // 就是这么写的）。所以只断言"点上有像素"是橡皮图章：一个把散点画成折线的
        // 实现会全绿，而画面上那是一条**显示着不存在信号**的线。
        //
        // 数据刻意挑陡（见 SCATTER_VALUES）：相邻两点纵向差 144 px、横向只差 52 px，
        // 中点到两端的距离远超任何标记半径，"中点为空"因此不是一条恒真的断言。
        //
        // **变异验证（两条，结论不同，都实跑过）**：
        //   (1) 把 `rendererFor` 里的 SCATTER 改路由到折线渲染器、并让
        //       `LineSeriesRenderer` 放行 SCATTER：本组 8 条**全失败**——
        //       那样连标记都不见了，"标记像素数 = 点数 × 边长²"那几条自然一起倒。
        //   (2) **标记照画，只是额外把相邻两点用同色连起来**：标记那几条与拾取那几条
        //       **照常通过**，唯一失败的就是下面这条"中点为空"（实测 52/53/52/53 px）。
        //       第 (2) 条才是"这条断言不可替代"的证据；第 (1) 条只说明整组是活的。
        println("\n-- ★ 散点：点与点之间没有连线 --")
        val scatShot = scatterSnapshot
        val markerShot = markerSnapshot
        if (scatShot == null || markerShot == null || !scatterPickCaptured) {
            report("前提：两张散点实验图的快照与拾取结果都取到了", false,
                "scatterSnapshot=${scatShot != null}，markerSnapshot=${markerShot != null}，" +
                        "scatterPickCaptured=$scatterPickCaptured")
        } else {
            // 局部坐标：快照就是 SCATTER_PLOT 那一块，左上角为原点。
            val markerPts = (0 until SCATTER_POINTS).map {
                Pair((scatterX(it.toDouble()) - SCATTER_PLOT_X).toInt(),
                    (scatterY(SCATTER_VALUES[it]) - SCATTER_PLOT_Y).toInt())
            }

            // (a) 每个数据点位置上都是标记。盒子取 5×5：它整个落在边长 10 的标记内部，
            //     所以期望是**恰好** 25 px——是数出来的，不是"有像素"。
            //     这一条同时是下面中点那条的前提：中点为空不能靠"整张图都没画"来满足。
            val pointHits = markerPts.map { (px, py) ->
                scatShot.countIn(px - 2, py - 2, px + 2, py + 2, scatterRgb)
            }
            report("每个数据点位置上都是标记（点周围 5×5 各 25 px）",
                pointHits.all { it == 25 },
                "5 个点分别 ${pointHits.joinToString()} px，期望都是 25" +
                        "（5×5 的盒子完全落在半径 $SCATTER_MARKER_RADIUS_SMALL 的标记内部）")

            // (b) ★ 相邻两点的中点处一个该系列颜色的像素都没有——**"散点不是折线"的判据**。
            //
            // 盒子取 5 列 × 17 行（而不是 5×5）：连线的斜率是 144/52 ≈ 2.8，
            // 横向 ±2 px 对应纵向 ±5.5 px，17 行给足了余量——**连线必然穿过这个盒子**。
            // 而它离最近的标记也有 20 px 以上，所以正确实现那里只可能是底色。
            val segFails = ArrayList<String>()
            for (i in 0 until SCATTER_POINTS - 1) {
                val (ax, ay) = markerPts[i]
                val (bx, by) = markerPts[i + 1]
                val mx = (ax + bx) / 2
                val my = (ay + by) / 2
                val n = scatShot.countIn(mx - 2, my - 8, mx + 2, my + 8, scatterRgb)
                if (n != 0) segFails.add("第 ${i}→${i + 1} 段的中点 ($mx,$my) 附近 $n px")
            }
            report("相邻两点的中点处【没有】该系列的像素（散点没有连线）",
                segFails.isEmpty(),
                if (segFails.isEmpty())
                    "4 段各自的 5×17 盒子全空——把点连成线的实现会在那里留下一条线"
                else segFails.joinToString("；") + "，期望 0——" +
                        "非 0 说明两点被连了起来，而那条线显示的是一个不存在的信号")

            // (c) 拾取：标记上的像素命中该系列（散点与折线共用同一套 ID 机制），
            //     而且**热区也没有把两点连起来**——它是 (b) 的孪生断言。
            //     拾取坏了画面一点都不会变坏，只会让点击落在不该命中的地方。
            report("标记点上的像素命中散点系列（payload 就是那个 Series 对象）",
                scatterOnMarkerId != 0 && scatterOnMarkerPayloadOk,
                "标记上 ID=$scatterOnMarkerId（期望非 0），payload 是散点系列=$scatterOnMarkerPayloadOk")
            report("系列级 ID：同一系列的两个标记读到同一个 ID",
                scatterOnMarkerId != 0 && scatterOnMarkerId == scatterOnMarker2Id,
                "第一个标记 ID=$scatterOnMarkerId，第二个标记 ID=$scatterOnMarker2Id（期望相等且非 0）")
            report("相邻两点的中点处拾取不到任何东西（热区也没把两点连起来）",
                scatterMidId == 0,
                "中点 ID=$scatterMidId，期望 0——非 0 说明散点的 ID pass 用了几何形状不对的顶点程序" +
                        "（例如错用了折线那份：第二个实例属性没人喂、恒为 0，" +
                        "热区会变成一条从数据值竖直拉到 0 的长条）")
            report("标记之外的空白处拾取不到（热区的形状就是标记本身，不是一条斜带）",
                scatterBlankId == 0,
                "(${SCATTER_BLANK_X.toInt()},${SCATTER_BLANK_Y.toInt()}) ID=$scatterBlankId，期望 0——" +
                        "这一点离最近的标记有 26 px，却正落在\"错用折线顶点程序\"那条斜带上" +
                        "（见 SCATTER_BLANK_X 的推导）；非 0 就是那个形状错误的热区")

            // ---- 17. ★ 标记尺寸：markerSize 变大，覆盖的像素确实变多 ----
            //
            // "有像素"对任何非 0 的 markerSize 都成立，是橡皮图章。这里的量是**像素数**：
            // 四边形是轴对齐的正方形，而 `Series.markerSize()` 声明的是**半径**，
            // 渲染器在传进着色器之前乘 2 换成边长——
            // 所以解析期望是 **点数 × (2 × 半径)²**，写成"点数 × 半径²"会去钉一个错的语义
            // （那正是"用户设半径 5 拿到宽 5 的方块"那个 2 倍静默错误）。
            //
            // 两条断言各自独立的判别力：只认默认值、忽略 setter 的实现会让两张图的
            // 像素数一模一样（比值 1.00）；把半径当边长用的实现会得到 1/4 的值。
            println("\n-- ★ 标记尺寸：markerSize 变大时覆盖的像素真的变多 --")
            val smallCount = scatShot.count(scatterRgb)
            val bigCount = markerShot.count(markerBigRgb)
            val smallEdge = SCATTER_MARKER_RADIUS_SMALL.toInt() * 2
            val bigEdge = SCATTER_MARKER_RADIUS_BIG.toInt() * 2
            val smallExpected = SCATTER_POINTS * smallEdge * smallEdge
            val bigExpected = SCATTER_POINTS * bigEdge * bigEdge
            report("小标记图（半径 $SCATTER_MARKER_RADIUS_SMALL → 边长 $smallEdge）：" +
                    "标记像素数 = 点数 × 边长² = $smallExpected",
                smallCount == smallExpected,
                "实际 $smallCount px（局部 ${scatShot.w}×${scatShot.h}）")
            report("大标记图（半径 $SCATTER_MARKER_RADIUS_BIG → 边长 $bigEdge）：" +
                    "标记像素数 = 点数 × 边长² = $bigExpected",
                bigCount == bigExpected,
                "实际 $bigCount px（局部 ${markerShot.w}×${markerShot.h}）")
            report("标记变大 → 覆盖的像素确实变多（面积是边长的平方，这里约 4 倍）",
                bigCount > smallCount * 3,
                "小标记 $smallCount px，大标记 $bigCount px（比值 " +
                        "${"%.2f".format(bigCount.toDouble() / smallCount.coerceAtLeast(1))}，期望约 4）")
        }

        // ---- 18. ★ markerSize = 0：一个像素都不画，但拾取照旧 ----
        //
        // 两条必须成对，各自防的退化不同：
        //   只有"全画面没它的颜色" → "那张图压根没被画出来"照样通过（恒真）；
        //   只有"拾取命中" → "它其实画了一堆像素"照样通过。
        // 折线那边有一个同构的断言（第 4 组，"线宽 0 的系列全画面一个像素都没有"）。
        println("\n-- ★ markerSize = 0：不画像素，但拾取照旧 --")
        report("退化散点系列（半径 0）在拾取里仍能命中（否则下一条恒真）",
            degenPickId != 0,
            "探测点 (${DEGEN_PROBE_X.toInt()},${DEGEN_PROBE_Y.toInt()}) ID=$degenPickId，期望非 0" +
                    "——0 说明这张图根本没被画，那下一条就是橡皮图章")
        report("退化散点系列：全画面一个像素都没有", (counts[degenScatterRgb] ?: 0) == 0,
            "退化色像素 ${counts[degenScatterRgb] ?: 0}——非 0 说明 markerSize = 0 没有退化" +
                    "（边长取到了非 0 的值，标记会凭空出现在绘图区里）")

        // ---- 19. ★ 频谱：GPU 上的 FFT 输出真的被画成了曲线，而且画在它该在的位置上 ----
        //
        // 这一组问的是**像素**，所以它落在本校验器里而不在 FftVerifier 里：
        // 后者明确"不画任何东西"（它要的是 GL 上下文，不是画面），守的是"FFT 算对了"；
        // 这里守的是"算出来的东西被画到了对的地方"。两条口径各自独立，谁也替代不了谁。
        //
        // ★ 判别式是"相邻两个 bin 之间那一列"。**"峰值在正确的 bin、高度也对"这类断言
        //   抓不住它**：把第二个实例属性的偏移从 4 改成 0（两个属性都指向 mag[k]）之后，
        //   每一段退化成一段**水平小横线**，画在**左端点**的高度上，而**峰那一列的最高
        //   有色行仍然在顶边**——所有位置断言照常通过。能分开两种实现的地方只有
        //   "两个实例之间那一列上的墨迹落在哪一行"。这与 ② 里折返图那条判据是同一件事
        //   （见 [ZIG_VALUES]），只是这里的分辨尺度来自 FFT 的主瓣而不是数据本身。
        println("\n-- ★ 频谱：FFT 的输出画在了它该在的位置上 --")

        val specA = spectrumSnapshotA
        val specB = spectrumSnapshotB
        val specC = spectrumSnapshotC
        val specD = spectrumSnapshotD
        if (specA == null || specB == null || specC == null || specD == null) {
            report("前提：四张频谱快照都抓到了", false,
                "A=${specA != null}，B=${specB != null}，C=${specC != null}，D=${specD != null}")
        } else {
            // 与渲染路径**同一份映射**：ChartRenderLayout 就是 CPU 那一份（着色器里还有一份，
            // 两份的一致性由 ChartRenderLayoutTest 钉着）。期望值直接由它算，
            // 于是"画出来的位置"与"布局说该在的位置"是同一个口径，不会各说各话。
            val layout = ChartRenderLayout(spectrumRect, spectrumXAxis, spectrumYAxis)

            /** 快照是绘图区那一块，左上角为原点：绝对坐标减掉绘图区左上角即局部坐标。 */
            fun localX(abs: Double): Double = abs - SPECTRUM_PLOT_X
            fun localY(abs: Double): Double = abs - SPECTRUM_PLOT_Y

            val refA = spectrumSamplesA?.let {
                SpectrumReference(SPECTRUM_CAPACITY, it, SPECTRUM_WINDOW)
            }
            val refB = spectrumSamplesB?.let {
                SpectrumReference(SPECTRUM_CAPACITY, it, SPECTRUM_WINDOW)
            }
            val refC = spectrumSamplesC?.let {
                SpectrumReference(SPECTRUM_CAPACITY, it, SPECTRUM_WINDOW)
            }

            // 前提两条：下面所有期望值都站在它们之上。
            report("前提：FFT 的变换长度就是环容量 $SPECTRUM_CAPACITY" +
                    "（容量 ≤ 渲染器的默认长度，否则 CPU 参考要按另一个长度算）",
                SPECTRUM_CAPACITY <= SPECTRUM_DEFAULT_FFT_LENGTH,
                "min(默认长度 $SPECTRUM_DEFAULT_FFT_LENGTH, 容量 $SPECTRUM_CAPACITY) = " +
                        "${minOf(SPECTRUM_DEFAULT_FFT_LENGTH, SPECTRUM_CAPACITY)}" +
                        "——默认长度是渲染器里的私有常量，这里是它的第二份，改一处就得改另一处")
            report("前提：三幕的输入样本都记下来了（CPU 参考要用它们算预期幅值）",
                refA != null && refB != null && refC != null,
                "A=${refA != null}，B=${refB != null}，C=${refC != null}")

            // ---- (1) 画出来了，而且在**预期的那一列**上 ----
            val kA = SPECTRUM_K0_A
            val kB = SPECTRUM_K0_B
            val wantAX = localX(layout.screenX(kA.toDouble()).toDouble())
            val wantBX = localX(layout.screenX(kB.toDouble()).toDouble())
            val peakA = specA.inkPeak(spectrumRgb, SPECTRUM_TIE_PX)
            val peakB = specB.inkPeak(spectrumRgb, SPECTRUM_TIE_PX)
            val peakC = specC.inkPeak(spectrumRgb, SPECTRUM_TIE_PX)

            report("阶段 A：频谱在绘图区里画出来了（该颜色的像素 > 0）",
                peakA != null,
                "该颜色的像素 ${specA.count(spectrumRgb)} px（局部 ${specA.w}×${specA.h}）" +
                        "——这条只是前提：它成立不代表画对了地方")
            report("阶段 A：峰值那一列与 ChartRenderLayout.screenX($kA) 相符" +
                    "（x 轴窗口刻意不取 [0,…]，所以这一条同时钉住了 uFirstRelIndex）",
                peakA != null && abs(peakA.column - wantAX) <= SPECTRUM_PEAK_COL_TOL,
                peakA?.let {
                    "峰值读在局部第 ${"%.1f".format(it.column)} 列（屏幕 x = " +
                            "${"%.1f".format(it.column + SPECTRUM_PLOT_X)}），期望 " +
                            "${"%.1f".format(layout.screenX(kA.toDouble()).toDouble())}" +
                            "（局部 ${"%.1f".format(wantAX)}）——窗口左端非 0 时，" +
                            "uFirstRelIndex 传成绝对下标会让整条谱右移 44 个 bin（约 400 px）"
                } ?: "快照里一个该颜色的像素都没有"
            )

            // ---- (2) 峰的高度：就是参考幅值所在的行 ----
            //
            // 判据取"那一列墨迹的上下界的中点"而不是最高行：线段在峰顶两侧一升一降，
            // 于是峰顶那一列上两段的墨迹关于顶点近似对称，**中点就是顶点**——
            // 与线宽、倾角都无关，不必再去算线段的法向外扩。
            val magA0 = refA?.magnitude(kA) ?: Double.NaN
            val magA1 = refA?.magnitude(kA + 1) ?: Double.NaN
            val rowPeakA = localY(layout.screenY(magA0).toDouble())
            val rangeA = peakA?.let { specA.inkRange(it.column.roundToInt(), spectrumRgb) }
            val centerA = rangeA?.let { (it.first + it.last) / 2.0 }
            report("阶段 A：峰的高度就是参考幅值所在的行（|X[$kA]| ≈ ${"%.4f".format(magA0)}）",
                centerA != null && abs(centerA - rowPeakA) <= SPECTRUM_PEAK_ROW_TOL,
                if (centerA == null) "峰值那一列上没有该颜色的像素"
                else "峰顶墨迹的行范围 $rangeA，中点 ${"%.1f".format(centerA)}，" +
                        "期望 ${"%.1f".format(rowPeakA)}（±$SPECTRUM_PEAK_ROW_TOL）——" +
                        "这条钉的是 y 方向：值与行的映射反了的话它会差半个绘图区"
            )

            // ---- (3) ★ 相邻两个 bin 之间那一列：墨迹应当落在两者幅值的中点 ----
            val rowMidA = localY(layout.screenY((magA0 + magA1) / 2.0).toDouble())
            val midColA = localX(layout.screenX(kA + 0.5).toDouble()).roundToInt()
            report("前提：相邻两个 bin 的幅值差在屏幕上拉开的行距 ≥ ${SPECTRUM_MIN_DROP_PX}px" +
                    "（窄了下面两条就成了橡皮图章）",
                rowMidA - rowPeakA >= SPECTRUM_MIN_DROP_PX,
                "|X[$kA]| = ${"%.4f".format(magA0)}、|X[${kA + 1}]| = ${"%.4f".format(magA1)}" +
                        "（BH 窗主瓣上最陡的一级），在 ${SPECTRUM_PLOT_H.toInt()} px 高的绘图区上是 " +
                        "${"%.1f".format(rowMidA - rowPeakA)} px"
            )
            val midInk = specA.countIn(midColA - 1, (rowMidA - SPECTRUM_MID_BAND_PX).roundToInt(),
                midColA + 1, (rowMidA + SPECTRUM_MID_BAND_PX).roundToInt(), spectrumRgb)
            report("★ 相邻两个 bin 之间那一列有墨迹，且落在**两者幅值的中点**那一行上" +
                    "（正确实现：线段斜着穿过去）",
                midInk > 0,
                "局部第 $midColA 列（屏幕 x = ${midColA + SPECTRUM_PLOT_X}）、行 " +
                        "${"%.1f".format(rowMidA)}±$SPECTRUM_MID_BAND_PX 里有 $midInk px，期望 > 0"
            )
            val wrongInk = specA.countIn(midColA - 1, (rowPeakA - SPECTRUM_WRONG_BAND_PX).roundToInt(),
                midColA + 1, (rowPeakA + SPECTRUM_WRONG_BAND_PX).roundToInt(), spectrumRgb)
            report("★ 那一列在**左端点的高度**（峰值那一行）附近没有墨迹" +
                    "（坏实现把水平小横线画在这里）",
                wrongInk == 0,
                "局部第 $midColA 列、行 ${"%.1f".format(rowPeakA)}±$SPECTRUM_WRONG_BAND_PX 里有 " +
                        "$wrongInk px，期望 0——非 0 说明相邻两段的第二端取的是**同一个 bin**" +
                        "（第二个实例属性的偏移写成了 0），每一段退化成水平小横线；" +
                        "而峰那一列的最高有色行仍在顶边，所以上面那些位置断言一条也看不见它"
            )

            // ---- (4) 场景会变：换频率峰位动、换幅度峰高动、全 NaN 什么都不画 ----
            //
            // 三幕用的是**同一个系列对象、同一个 ChartRenderer、同一份缓冲**——
            // "只有第一幕算得对"或状态跨幕泄漏的实现没有藏身处（同 [movingSquareX]）。
            val magB0 = refB?.magnitude(kB) ?: Double.NaN
            val rowPeakB = localY(layout.screenY(magB0).toDouble())
            val rangeB = peakB?.let { specB.inkRange(it.column.roundToInt(), spectrumRgb) }
            val centerB = rangeB?.let { (it.first + it.last) / 2.0 }
            report("阶段 B（频率 $kA → $kB）：峰位跟着移动 ${"%.0f".format(wantBX - wantAX)} px 且落在 " +
                    "screenX($kB) 上",
                peakB != null && abs(peakB.column - wantBX) <= SPECTRUM_PEAK_COL_TOL &&
                        peakA != null && abs(peakB.column - peakA.column) >= SPECTRUM_MOVE_MIN_PX,
                peakB?.let {
                    "峰值读在局部第 ${"%.1f".format(it.column)} 列，期望 ${"%.1f".format(wantBX)}；" +
                            "与阶段 A 相差 ${"%.1f".format(abs(it.column - (peakA?.column ?: 0.0)))} px" +
                            "（要求 ≥ $SPECTRUM_MOVE_MIN_PX）"
                } ?: "阶段 B 的快照里没有该颜色的像素"
            )
            report("阶段 B：幅度没变，峰高仍是 1.0（换频率不该动峰高）",
                centerB != null && abs(centerB - rowPeakB) <= SPECTRUM_PEAK_ROW_TOL,
                if (centerB == null) "阶段 B 的快照里没有该颜色的像素"
                else "峰顶中点 ${"%.1f".format(centerB)}，期望 ${"%.1f".format(rowPeakB)}"
            )

            val magC0 = refC?.magnitude(kA) ?: Double.NaN
            val rowPeakC = localY(layout.screenY(magC0).toDouble())
            val rangeC = peakC?.let { specC.inkRange(it.column.roundToInt(), spectrumRgb) }
            val centerC = rangeC?.let { (it.first + it.last) / 2.0 }
            report("阶段 C（幅度 $SPECTRUM_AMP → $SPECTRUM_AMP_LOW、频率不变）：峰位不动、峰高降到" +
                    "参考幅值那一行",
                peakC != null && centerC != null && abs(peakC.column - wantAX) <= SPECTRUM_PEAK_COL_TOL &&
                        abs(centerC - rowPeakC) <= SPECTRUM_PEAK_ROW_TOL,
                peakC?.let {
                    "峰值在局部第 ${"%.1f".format(it.column)} 列（期望 ${"%.1f".format(wantAX)}）；" +
                            "峰顶中点 ${centerC?.let { c -> "%.1f".format(c) } ?: "无"}" +
                            "，期望 ${"%.1f".format(rowPeakC)}（|X[$kA]| ≈ ${"%.4f".format(magC0)}）"
                } ?: "阶段 C 的快照里没有该颜色的像素"
            )
            report("阶段 C：峰比阶段 A 矮了至少 ${SPECTRUM_HEIGHT_DROP_MIN.toInt()} px（峰高跟着幅度变）",
                centerA != null && centerC != null && centerC - centerA >= SPECTRUM_HEIGHT_DROP_MIN,
                if (centerA == null || centerC == null) "两幕里有一幕没读到峰顶"
                else "阶段 A 峰顶在行 ${"%.1f".format(centerA)}，阶段 C 在行 " +
                        "${"%.1f".format(centerC)}，相差 ${"%.1f".format(centerC - centerA)} px"
            )
            report("阶段 D（输入全 NaN）：绘图区里一个该颜色的像素都没有" +
                    "（反证：上面的像素确实来自数据，不是别处漏进来的）",
                specD.count(spectrumRgb) == 0,
                "该颜色的像素 ${specD.count(spectrumRgb)} px，期望 0——全 NaN 的谱是 NaN，" +
                        "着色器把每个实例都退化到裁剪空间之外"
            )
            report("阶段 D：整块绘图区都是背景色（上一幕的谱没有留下任何残留）",
                specD.countNonBackgroundInRows(0, specD.h - 1, background) == 0,
                "不是背景色的像素 ${specD.countNonBackgroundInRows(0, specD.h - 1, background)} px，" +
                        "期望 0——这一块地方除了这张频谱图没有别的东西画过"
            )
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
        @JvmStatic
        fun main(args: Array<String>) {
            launch(ChartVerifierApp::class.java, *args)
        }
    }
}
