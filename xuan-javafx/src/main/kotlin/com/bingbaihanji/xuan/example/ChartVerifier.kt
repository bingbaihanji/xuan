package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.chart.ArrayChartData
import com.bingbaihanji.xuan.chart.Axis
import com.bingbaihanji.xuan.chart.AxisRange
import com.bingbaihanji.xuan.chart.AxisType
import com.bingbaihanji.xuan.chart.Chart
import com.bingbaihanji.xuan.chart.ChartInsets
import com.bingbaihanji.xuan.chart.ChartLayout
import com.bingbaihanji.xuan.chart.ChartData
import com.bingbaihanji.xuan.chart.ChartSide
import com.bingbaihanji.xuan.chart.ChartType
import com.bingbaihanji.xuan.chart.RingChartData
import com.bingbaihanji.xuan.chart.Series
import com.bingbaihanji.xuan.chartrender.ChartRenderLayout
import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.gpu.FftWindow
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.util.Rect
import com.bingbaihanji.xuan.view.MainView
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
 * <p>Task 5（子项目 A 的抗锯齿）补了<b>图表系列的解析式 AA</b> 那一节。它守的是
 * 「{@code uAntialias} 真的接到了每一个渲染器上」——而漏接一个的表现是
 * <b>那一种图型没有 AA、其余都有</b>，混在图里几乎注意不到；折线那一对另外按
 * 四条判据判（关时无过渡 / 开时每列一个过渡 / 线心纯色数两模式相等 / 墨量对解析值）。
 * **AA 默认关**，所以这一节之外的全部像素期望一字未改——它同时是那条默认值的闸门。
 *
 * <p><b>平滑曲线</b>（{@code Series.smooth()}）补了第 23 节，九幕按帧轮换、两块新绘图区。
 * 它的三条判据各自守着一件"像素看不出来"的事：**开关生效**（漏接的表现只是"曲线
 * 有点像折线"）、**边界回退**（环里读不到邻居的那个位置是<b>陈旧数据</b>、不是 NaN，
 * 曲线会弯向垃圾值）、**缺口回退**（NaN 被插值过去 = 那条线显示了一个不存在的信号）。
 * 另有一幕专门验"改开关会让缓冲重建"，以及一对**环形缓冲跨环绕**的——后者靠
 * "共线数据上平滑就是直线"这条性质，把三个镜像的读取放进了像素口径。
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
 *     "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.ChartVerifierKt"
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
 * 观察期结束到回收期之间的**空档**帧数。实验图在观察期结束时就不画了，
 * 这几帧正好顺带验证"上一帧画过的东西这一帧**整体**消失"没有留下任何痕迹
 * （颜色集合那条断言会数出来）。
 */
private const val SETTLE_FRAMES = 4

/**
 * 回收实验的起始帧（= 观察期 + 空档）。
 *
 * <p><b>它恰好等于改动前的 {@code TOTAL_FRAMES}</b>（104）——所以"断言从哪一帧起跑"
 * 这件事一字未改，变的只是后面又多接了一段回收期。
 */
private const val RECLAIM_START = STREAM_FRAMES + SETTLE_FRAMES

/**
 * 回收实验的帧数：探针一共画这么多帧（`n ∈ [RECLAIM_START, RECLAIM_END)`）。
 *
 * <p>30 是按**读数要进入稳态**定的，不是随手取的：A 组每帧都 new 两个系列，
 * 而回收有两代的宽限（见 `ChartRenderer.GRACE_GENERATIONS`），于是"同时活着的
 * A 组系列数"要到第 3 帧才停止爬坡（2 → 4 → 6，之后一直是 6）。
 * 断言只取 {@code RECLAIM_START + 3} 之后的读数，正好跳过那两帧。
 * 剩下的 27 帧足够看出"每帧 +2"这种线性增长——**不开回收时它会从 6 涨到 60**。
 */
private const val RECLAIM_FRAMES = 30

/**
 * 回收期探针画到**这一帧为止**（不含）。
 *
 * <p><b>★ 这个上界比"回收期多长"要紧，第一版就栽在它上面（实测）。</b>
 * 校验帧 {@code f} 上回读到的画面是**绘制帧 {@code f − 1}** 画出来的
 * （`frame` 是"已完成帧数"，见 [drawScene]）。所以探针的上界必须比
 * [TOTAL_FRAMES] **至少早一帧**——第一版写的是 `n < TOTAL_FRAMES`，
 * 于是绘制帧 {@code TOTAL_FRAMES − 1} 上三块探针还在画，校验帧里就多出
 * 336 个白像素（三条 112px 的线，探针系列的默认色恰好也是白），
 * "移动方块不多不少 40×40" 当场变成 1936。
 *
 * <p>既有那几张实验图用的是 {@code n < STREAM_FRAMES}（100）配
 * {@code TOTAL_FRAMES}（104），中间那 4 帧就是同一个道理。
 */
private const val RECLAIM_END = RECLAIM_START + RECLAIM_FRAMES

/**
 * 全部断言在**第几帧**上跑。
 *
 * <p>它比改动前往后挪了 {@link RECLAIM_FRAMES} + {@link SETTLE_FRAMES} 帧
 * （104 → 138），而**校验帧的画面一字未改**：`drawScene` 里除观察期与回收期
 * 那两段外都不看帧号（唯一的例外是 [movingSquareX] 的奇偶），而
 * {@code TOTAL_FRAMES − 1}（137）与改动前的 103 **同奇偶**（都是奇数），
 * 方块仍在同一个位置。回收期那一段带着 {@link RECLAIM_END} 的上界，
 * 所以校验帧上探针早已不画——"画面恰好只有这 7 种颜色"与各色像素计数
 * 因此一字未改（实测：改动前后那 7 个数字逐字相同）。
 */
private const val TOTAL_FRAMES = RECLAIM_END + SETTLE_FRAMES

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

// ---------------------------------------------------------------------------
// 图型实验：柱状 / 面积 / 阶梯（ChartType.BAR / AREA / STEP）
//
// 四张小图挤在画面最左边那一条 96px 宽的空白里（x ∈ [0,96)，y ∈ [100,500)）。
// 那一带是唯一一块**没有别的实验图**的空地：主绘图区从 x=100 起、流式图在 y ≤ 90、
// 缺口图在 y ≥ 610、移动方块在 y ∈ [540,580]，都不与它相交（方块那一条决定了
// 这四张图必须止步于 y = 500 以上，所以四张的高度和步长都是 96 而不是 100+）。
//
// 与其它实验图一样**只在观察期画**："画面恰好只有这 7 种颜色"那条断言是既有断言，
// 在画面里常驻一种新颜色会让它失败；而这里要验的东西（几何、分组、基线、轮廓线）
// 只需要"某一帧画过"。
//
// 尺寸刻意取小（96×96）：下面每一条期望值都是**手算出来的整数**，
// 几何越小越容易一眼复核。四张图的共同前提是：
//   局部 x = (数据下标 - 窗口左端) / 窗口跨度 × 96
//   局部 y = (1 - (值 - y窗口左端) / y跨度) × 96        （值越大越靠上）
// ---------------------------------------------------------------------------

/** 四张图型实验图的公共尺寸与列偏移（都在 x ∈ [0,96) 那一条里）。 */
private const val KIND_PLOT_X = 0f
private const val KIND_PLOT_W = 96
private const val KIND_PLOT_H = 96

/** 柱状实验图的 y 起点。 */
private const val BAR_PLOT_Y = 100

/** 面积（斜填充，无线）实验图的 y 起点。 */
private const val AREA_PLOT_Y = 200

/** 面积（水平填充 + 轮廓线）实验图的 y 起点。 */
private const val AREA_LINE_PLOT_Y = 300

/** 阶梯实验图的 y 起点。 */
private const val STEP_PLOT_Y = 400

/**
 * 柱状图的类别间距与柱间距（都是"比例"，见 `chart/Series`）。
 *
 * <p>取 1/6 与 0.5 是**为了手算**：格宽 96/4 = 24px，群宽 = 24 × (1 - 1/6) = 20px，
 * 柱宽 = 20 / (2 + 1×0.5) = 8px，柱心偏移 = ∓6px——全是整数，
 * 于是"哪一列属于哪根柱"可以逐列写出来，不必在断言里留任何余量。
 */
private const val BAR_CATEGORY_GAP = 1f / 6f
private const val BAR_GAP = 0.5f

/**
 * 柱状实验的 y 窗口下界。
 *
 * <p><b>刻意取 -0.5 而不是 0</b>：柱子的下沿是 {@code Series.baseline()} 那个**数值**（默认 0），
 * 而窗口下界是 -0.5。两者重合的话，"下沿取的是数值 0"与"下沿取的是绘图区下边缘"这两种实现
 * <b>画出来逐像素相同</b>，那条断言就是橡皮图章。差开半格之后：
 * 正确实现的下沿在局部 y = 64，用窗口下界的那种会一路铺到 y = 96。
 */
private const val KIND_Y_WINDOW_MIN = -0.5

private const val KIND_Y_WINDOW_MAX = 1.0

/** 柱状实验的 x 窗口：**左右各留半格**，于是四根柱都完整落在绘图区里。 */
private const val BAR_WINDOW_MIN = -0.5
private const val BAR_WINDOW_MAX = 3.5

/**
 * 柱状实验里第 1 个系列（并排的左槽位）的值。
 *
 * <p>四个值各不相同，于是四根柱的高度各不相同——"柱高跟着数值走"因此不是一句话，
 * 而是四组可以逐条对上的像素。
 */
private val BAR_VALUES_A = doubleArrayOf(0.25, 0.5, 0.75, 1.0)

/**
 * 第 2 个系列（右槽位）的值：三个 1.0 + **一个 NaN**。
 *
 * <p>NaN 那一格是柱状图的"缺口"：它必须一根柱都不画。NaN 的取值放在中间（下标 2）
 * 而不是末尾，是为了让"它左边和右边的柱照常画"这两条正反断言都能成立——
 * 放在末尾的话，右侧没有邻居，那条断言就少了一半。
 */
private val BAR_VALUES_B = doubleArrayOf(1.0, 1.0, Double.NaN, 1.0)

/** 柱状实验里 NaN 所在的数据下标。 */
private const val BAR_NAN_INDEX = 2

/** 面积（斜填充）实验里四个点的值：一条斜率恒定的斜坡。 */
private val AREA_VALUES = doubleArrayOf(0.25, 0.5, 0.75, 1.0)

/** 面积（水平）实验的填充值。水平是刻意的：没有斜率就没有"边界落在哪个像素"的争议。 */
private const val AREA_LINE_VALUE = 0.5

/** 面积（水平）实验的线宽。取 4 是为了让轮廓线占**整整 4 行**（见下）。 */
private const val AREA_LINE_WIDTH = 4f

/**
 * 阶梯实验的四个值：低 → 高 → 低 → **NaN**。
 *
 * <p>前三个值在 y 窗口 [0,1] 上分别落在局部 y = 72 / 24 / 72（全是整数），
 * 于是"踏步在哪一行、竖段在哪一列"都能手算。末尾那个 NaN 让最后一段
 * （下标 2 → 3）整个消失——它是"NaN 必须断开"这条断言在阶梯图上的形态，
 * 而它的判别式与缺口实验（折线）同源：<b>同一行上有墨迹的左半边、没有墨迹的右半边</b>。
 */
private val STEP_VALUES = doubleArrayOf(0.25, 0.75, 0.25, Double.NaN)

/** 阶梯实验的线宽（半宽 2px）：踏步占 4 行、竖段占 4 列，都是整数。 */
private const val STEP_LINE_WIDTH = 4f

// ---------------------------------------------------------------------------
// 装配实验：标题 / 图例 / 外边距（Chart.title / legend / padding）
//
// 它挤在画面最左边那一条的**下半段**（x ∈ [0,20)、y ∈ [502,602)）——
// 那是唯一一块还没被别的实验图占掉的空地：x ≥ 20 那一带是移动方块（y ∈ [540,580)）
// 的地盘，而方块正是"跨帧残留"的探针，不能挪它。
//
// 20px 宽是很窄，但这一节要验的是**带子的位置**（标题在上、绘图区被挤到中间、
// 图例在下、外边距四边各收掉一点），而不是字好不好看。窄反而让"挤没挤"更明显：
// 绘图区只有 16×67.6，一行数据的位置差几个像素就能一眼对上。
//
// 字号取 8：小到足以让标题带（11.2）、图例带（11.2）与绘图区（67.6）挤进 96 的内框里。
// 期望值全部由"字号 × ChartLayout.LINE_HEIGHT_FACTOR"手算出来，见下面各条断言。
// ---------------------------------------------------------------------------

/** 装配实验图的外框（x ∈ [0,20) 那一条的下半段）。 */
private const val DECOR_PLOT_X = 0
private const val DECOR_PLOT_Y = 502
private const val DECOR_PLOT_W = 20
private const val DECOR_PLOT_H = 100

/** 四边各 2px 的外边距：内框变成 x ∈ [2,18)、y ∈ [504,600)。 */
private const val DECOR_PADDING = 2f

private const val DECOR_TITLE_FONT = 8f
private const val DECOR_LEGEND_FONT = 8f
private const val DECOR_SWATCH = 6f
private const val DECOR_TITLE_GAP = 3f
private const val DECOR_LEGEND_GAP = 3f

/** 数据值：0.25 → 线落在绘图区偏下的位置（**不是正中**，见下）。 */
private const val DECOR_VALUE = 0.25

/** 线宽：半宽 1px，于是墨迹恰好占两行。 */
private const val DECOR_LINE_WIDTH = 2f

/**
 * 走 {@code drawChart}（而不是 {@code draw}）的那一帧。
 *
 * <p>这一帧画出来的柱状图与相邻帧"逐像素相同"是那条身份断言的判别式：
 * 同一张图、同一块矩形，一条路径经过 {@code ChartLayout}、另一条不经过，
 * 而这张图上没有标题、没有图例、外边距为 0——于是布局算出来的绘图区
 * <b>就是</b>那块矩形，两条路径必须给出同一张图。
 * 取 98 是为了让快照落在观察期最后一帧之前（101 帧的观察期：0..99）。
 */
private const val DECOR_IDENTITY_FRAME = 98

/** 装配实验的标题文字与系列名（都用拉丁字母：8px 下中文会顶出 20px 宽的带子）。 */
private const val DECOR_TITLE_TEXT = "T"
private const val DECOR_SERIES_NAME = "A"

// ---------------------------------------------------------------------------
// 左/右图例、底部标题、以及"带子边界"那一组实验
//
// 它挤在 x ∈ [20,128)、y ∈ [502,540) 这一块：右边是频谱图（从 128 起）、
// 左边是装配实验的竖条（x ∈ [0,20)）、下面是移动方块的地盘（y ∈ [540,580)，
// 而方块正是"跨帧残留"的探针，不能挪它）。
//
// **同一块矩形上按帧轮流画三个变体**（见 [drawOverflowCharts] 与 [captureOverflow]）：
//   A 帧：左图例 + 底部标题      B 帧：右图例 + 底部标题      C 帧：底部图例 + 超长标签
// 三者的**带子位置**互不相同，而一块 108×38 的地方只装得下一个变体，
// 所以按帧轮换、各自在自己那一段的最后抓一张快照（快照必须是"那一帧刚画完"时取的）。
//
// 全部期望值仍然**手算**，而且刻意只用与字体无关的量：带子高（字号 × 行高系数）、
// 色块位置、绘图区的上下边缘。左右图例的**带宽**与字体有关（= 色块 + 间隙 + 文字宽度），
// 所以那几条断言只钉"绘图区从带子之后开始""文字在色块右边"这类关系，
// 不去写一个"猜出来的"固定列号——那种期望值在换字体时会红，而它红的原因与缺陷无关。
// ---------------------------------------------------------------------------

/** 三个变体共用的外框（x ∈ [20,128) 那一条的上半段）。 */
private const val OVER_X = 20
private const val OVER_Y = 502
private const val OVER_W = 108
private const val OVER_H = 38

/** 四边各 2px 的外边距：内框 x ∈ [22,126)、y ∈ [504,538)。 */
private const val OVER_PADDING = 2f

private const val OVER_FONT = 8f
private const val OVER_SWATCH = 6f
private const val OVER_TITLE_GAP = 2f
private const val OVER_LEGEND_GAP = 3f

/** 数据值 0.5 → 线落在绘图区正中。 */
private const val OVER_VALUE = 0.5

/** 线宽 2 → 半宽 1 → 墨迹两行。 */
private const val OVER_LINE_WIDTH = 2f

/** 底部标题的文字。 */
private const val OVER_TITLE_TEXT = "T"

/** 正常长度的系列名（变体 A / B 用）。 */
private const val OVER_NAME = "A"

/**
 * 变体 C 的超长系列名：40 个 `W`。
 *
 * <p>要**比内框宽（104px）**才能触发"过界"那条路径：8px 下每个 `W` 约 7px，
 * 40 个约 280px，远超带宽。取一堆同字符是为了让"它比带子宽"这件事一眼可算，
 * 而不是依赖某个具体字形。
 */
private const val OVER_LONG_NAME = "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW"

/**
 * 把四个变体的快照按像素**原样打印出来**（`.` 背景 / `#` 系列色 / `?` 其它）。
 *
 * <p>默认关着。留着它是因为这一组断言写第一遍时，六条红里有五条是**断言的期望值**算错了
 * （色块在竖排图例里是从带子顶部开始堆的，而不是垂直居中），而失败输出里
 * "色块 0 px"与"图例压根没画"长得一模一样。把原始像素打出来，一眼就分开了
 * ——这正是 `CLAUDE.md` 里那条"失败时先打原始像素，不要先怀疑实现"。
 */
private const val OVER_DEBUG_DUMP = false

/** 变体 C 的最后那一列（内框右边缘的前一列，**局部**）：文字必须画到这里才说明它被切断了。 */
private const val OVER_LOCAL_CUT = OVER_W - OVER_PADDING.toInt() - 1

/** 五个变体各占 20 帧（观察期一共 100 帧）。 */
private const val OVER_SWITCH_AB = 20
private const val OVER_SWITCH_BC = 40
private const val OVER_SWITCH_CD = 60
private const val OVER_SWITCH_DE = 80

/** 变体 D 的轴标题间隙与刻度预留（取值见 overChartD 的说明）。 */
private const val OVER_AXIS_GAP = 2f
private const val OVER_TICK_RESERVE = 2f

/** 抓快照的帧号（`frame` 是"已完成帧数"，所以它等于最后一帧的下标 + 1）。 */
private const val OVER_SHOT_A = OVER_SWITCH_AB
private const val OVER_SHOT_B = OVER_SWITCH_BC
private const val OVER_SHOT_C = OVER_SWITCH_CD
private const val OVER_SHOT_D = OVER_SWITCH_DE
private const val OVER_SHOT_E = 100

/** 变体 E 的 x 轴窗口：比数据范围宽，好让两个样本都不落在绘图区的边界上（见 overChartE）。 */
private const val OVER_E_WINDOW_MIN = -0.5
private const val OVER_E_WINDOW_MAX = 1.5

/** 变体 E 的标记点半径（用户坐标单位）——着色器里的边长是它的两倍（见散点渲染器）。 */
private const val OVER_E_MARKER_RADIUS = 3f

/** 变体 C 的文字墨迹的判别色：随便一个不与其它颜色冲突的 RGB。 */
private val overSeriesRgb = 0xD07020

// ---------------------------------------------------------------------------
// Task 5：图表系列的解析式抗锯齿（顶点着色器的 vEdge + 片元的 uAntialias）
//
// 系列**不进 RenderBatch**（它们在 Gc.flush() 处当场 instanced 绘制），所以 Task 1 的
// 顶点格式与它们无关——它们各自在顶点着色器里多算一个 varying，片元按同一条覆盖率公式
// 羽化。下面这组判据与 PipelineVerifier 的 ★ 抗锯齿一节**同构**（四条），
// 几何换成了图表画得出来的形状。
//
// ★ 几何与相位：每一个期望值都可以手算
//   绘图区 300×22，y 窗口 [0,1] ⇒ sy(v) = AA_PLOT_Y + (1 − v)·22。
//   两个相位（同一个折线图里的两条系列，一上一下，互不重叠）：
//     A：v = 0.5  ⇒ 带心 723.25（**k + 0.25** 那一档）
//     B：v = 0.25 ⇒ 带心 728.75（k + 0.75 那一档，与 A 关于像素栅格镜像）
//   线宽 3 ⇒ 半宽 b = 1.5 ⇒ 带 = 带心 ± 1.5。
//   覆盖率斜坡宽 1 个设备像素（fwidth(vEdge) 就是 vary 每像素的变化量，与几何尺寸、
//   缩放都无关；⚠️ "1 px" 是 fwidth 的标准近似，对**轴向**边界精确、对 45° 斜边是
//   √2 px——本节的几何全是水平边，所以这里精确）⇒
//       cov = clamp(b + 0.5 − |q|, 0, 1)，q = 像素中心到带心的距离
//   ⇒ cov = clamp(2 − |q|)。
//
//   相位 A：像素中心在 722.5 / 723.5 / 724.5 的三行落进带内
//     （|q| = 0.75 / 0.25 / 1.25），其余行中心在带外（|q| = 1.75 ⇒ 没有片元）。
//     **三行都不是边界**（|q| ≠ 1.5）⇒ 不发生"中心正好压在边界上"的 tie。
//     覆盖率 1 / 1 / 0.75 ⇒ 每列 2 个纯色 + 1 个过渡（过渡那行是**下方**那行）。
//   相位 B：镜像 ⇒ 727（cov 0.75）/ 728 / 729（cov 1），过渡在上方。
//
//   ★ **两个相位都要**，不是冗余：把片元的 `abs(vEdge)` 写成 `vEdge`（变异）之后
//   斜坡变成**单侧**的（cov = 2 − q，q 带符号），于是相位 A 照样给出一个 0.75 的
//   过渡像素、②**照过**；只有相位 B 三行全变成满覆盖、② 才倒。
//   只留 A 的话那条变异会活下来（实测的相位选择就是这么定的）。
//
// ★ 墨量的解析参照是 **2.75 px/列**，不是带的全宽 3：
//   斜坡以**带边缘**为心、宽 1 px，而图表这条路径**几何没有外扩**（顶点着色器只画到
//   真实边缘为止，与 Gc 路径"双向外扩 1 设备像素"不同）⇒ 斜坡在外侧的那一半
//   **没有片元**。这是**声明过的降级**（同 Gc 那条"压缩轴上真外缘之外的羽化被切掉"），
//   不是缺陷。
//
//   ★ **"少 0.25"是①本相位下实测出来的，不是一条通用公式**（连续面积那套讲不准）：
//     离散口径是"外侧半斜坡里**有**像素中心才丢那个样本的覆盖率"——
//       上边 721.75：外侧半斜坡 (721.25, 721.75) 里有**一个**中心（721.5，边外 0.25）
//                    ⇒ 丢 0.5 − 0.25 = **0.25**；
//       下边 724.75：外侧半斜坡 (724.75, 725.25) 里**一个中心都没有**（最近的在 725.5，
//                    已在斜坡之外）⇒ 丢 **0**。
//     ⇒ 每列少 0.25 —— **这就是解析值 2.75 而不是 3 的全部来路**。
//     ⚠️ **别拿"实测的 −25（70125 → 70100）"当它的证据**：那 −25 是**另一回事**——
//     解析值里**已经含了**这份斜坡损失（2.75 = 3 − 0.25/列），所以 70125 → 70100 这一段
//     只可能是**那一列唯一一个过渡像素的 8 位量化**（0.75 × 255 = 191.25 → 191，
//     每列 −0.25 luma × 100 列）。两个 0.25 单位不同（一个是覆盖率、一个是 luma）、
//     成因也不同，**碰巧同值**；混起来讲会让下一个重新推导的人**把量化那一份重复计进去**
//     （或反过来把解析值调回 3.0），而 ④ 那条容差的来历也就讲不清了（见 ④ 那一处的说明）。
//     ⚠️ 写成"两侧各 ∫₀^0.5(0.5−t)dt"（0.125 × 2）是**碰巧同值**：那是连续面积口径，
//     而离散口径是一边 0.25、一边 0。**换相位就不成立**——带心落在 k+0.5 时同一套算术
//     给出"丢 0"（三行全满覆盖，Σ = 3 = 全宽）。换相位/换线宽都要重新数（相位由
//     "★ AA 前置"那四条钉着，所以它漂了会响亮地失败）。
//
// ★ **④ 的参照系必须是这个解析值，不能是"AA 关的那条"**（PipelineVerifier 的学费）：
//   硬边在这个几何上恰好是 3.0 px/列（三行全满覆盖，**比 AA 开多 8.3%**）——
//   写成"两者相等"会是一条**恒假**断言。（45° 斜线上硬边比真实面积**少** 11.6%，
//   差的方向由几何决定，不是"硬边总偏少"。）
//
// ★ **相位是先决条件，不是自由参数**：上面每个读数都建立在"带心落在 k+0.25 / k+0.75"
//   上。改 AA_PLOT_Y / AA_PLOT_H / AA_VALUE_A / AA_VALUE_B 里的任何一个都要重算一遍
//   （窗口的行号、过渡像素的行号、墨量）。判据里那两条"前置"断言（见 verifySeriesAntialias
//   开头的两段）就是为了让这件事**响亮地失败**——否则判据会悄悄滑到别的相位上继续
//   通过，而它守的东西已经不在了。
// ---------------------------------------------------------------------------

/**
 * AA 探针的绘图区：**帧缓冲最底下那一条**（y ∈ [712, 734)）。
 *
 * <p>为什么在那里：别的绘图区把画布占满了（见各自的常量说明），而这一条**是空的**——
 * 缺口图与跨环绕图都止于 y = 710。回读的那 22 行**整行**都在帧缓冲里
 * （实测帧缓冲 988×738），由 [aaPrecondition] 那两条断言守着：
 * 越界时读数会全是背景色，而"全是背景"看起来像"AA 没生效"，归因就错了。
 *
 * <p>y 取 **712.25** 而不是整数，是相位需要的一部分（见上面那段推导）：
 * `712.25 + 0.5×22 = 723.25`。
 */
private const val AA_PLOT_X = 20f
private const val AA_PLOT_Y = 712.25f
private const val AA_PLOT_W = 300f
private const val AA_PLOT_H = 22f

/**
 * 回读窗的**顶边**（设备行，整数）。
 *
 * <p>画用的绘图区顶边在 [AA_PLOT_Y] = 712.25，而回读只能按整数行取——
 * 于是**局部行 = 设备行 − [AA_GRAB_Y]**，下面每一个行号常量都按这条换算。
 * 读数与判据全部用局部行，打印时同时给出设备行（否则失败信息里的行号
 * 与上面那段推导对不上）。
 */
private const val AA_GRAB_Y = 712

/** 探针的绘图区（画用，y 取相位要求的小数）与回读窗（**整行**，从 [AA_GRAB_Y] 那一行开始）。 */
private val aaPlotRect = Rect(AA_PLOT_X, AA_PLOT_Y, AA_PLOT_W, AA_PLOT_H)
private val aaGrabRect = Rect(AA_PLOT_X, AA_GRAB_Y.toFloat(), AA_PLOT_W, AA_PLOT_H)

/** 样本数。窗口取 [0, 7] ⇒ 每样本 300/7 ≈ 42.857 px（**非整数**，柱与标记的竖直边因此也有小数相位）。 */
private const val AA_POINTS = 8

/**
 * 两条系列的数值：0.5 与 0.25 ⇒ 带心 723.25（相位 A）与 728.75（相位 B）。见上面那段推导。
 *
 * <p>★ <b>两个相位不是冗余，别省掉一个</b>（实测，见 M1）：
 * `abs(vEdge) → vEdge` 把斜坡变成**单侧**的，而相位 A 的外缘行恰好落在
 * "单侧公式照样给出 0.75"的那一侧 ⇒ **相位 A 的 ② 照过**，只有相位 B 三行全变满覆盖、
 * ② 才倒（实测：相位 A 100 个、相位 B **0** 个）。
 * 换句话说：**一个相位能验"羽化存在"，只有两个相位才验得出"羽化是对称的"**。
 */
private const val AA_VALUE_A = 0.5
private const val AA_VALUE_B = 0.25

/** 线宽 3 ⇒ 半宽 1.5。见上面那段推导（相位是按它算的，改它就要重算）。 */
private const val AA_LINE_WIDTH = 3f

/** 面积图/柱状图的下沿（数值）：0.25 ⇒ 屏幕行 728.75，与带心 B 同一个相位。 */
private const val AA_BASELINE = 0.25f

/** 散点标记的半径（着色器里的边长是它的两倍）。 */
private const val AA_MARKER_RADIUS = 2f

/** 探针的背景与墨色：背景纯黑、系列纯白 ⇒ **红通道就是覆盖率 × 255**，墨量可以逐项手算。 */
private const val AA_BG_RGB = 0x000000
private const val AA_INK_RGB = 0xFFFFFF
private const val AA_INK_ARGB = 0xFFFFFFFF.toInt()

/** 墨色与背景之间的亮度差：`255 − 0 = 255`。墨量 = Σ(红通道) = 覆盖率之和 × 它。 */
private const val AA_INK_LUMA = 0xFF

/** AA 开的那些绘制帧（供 [aaProbeIsOn] 判断；关的那些不在这个集合里）。 */
private val AA_ON_FRAMES = setOf(
    AA_LINE_ON, AA_STEP_ON, AA_AREA_ON, AA_BAR_ON, AA_SCATTER_ON, AA_SPECTRUM_ON
)

/** 六个图型各两帧（关、开）。**每两帧用的是同一张图、同一块绘图区**，唯一的变量是开关。 */
private const val AA_LINE_OFF = 20
private const val AA_LINE_ON = 21
private const val AA_STEP_OFF = 22
private const val AA_STEP_ON = 23
private const val AA_AREA_OFF = 24
private const val AA_AREA_ON = 25
private const val AA_BAR_OFF = 26
private const val AA_BAR_ON = 27
private const val AA_SCATTER_OFF = 28
private const val AA_SCATTER_ON = 29

/**
 * 频谱那两帧取在**第二幕**（帧 25..49，单位幅度、峰在 bin 62）里。
 *
 * <p>不能取在第四幕（帧 75 起是全 NaN，一个像素都不画），也不该跨幕——
 * 两帧的谱必须是同一张，否则"开"与"关"比的不再是同一样东西。
 */
private const val AA_SPECTRUM_OFF = 30
private const val AA_SPECTRUM_ON = 31

/** 测量窗（**局部**坐标：局部行 = 设备行 − 712，局部列 = 设备列 − AA_PLOT_X）。 */
private const val AA_W = 100

/** 窗的列范围：离线的两端各 100 px 以上，端帽与窗边界互不干扰。 */
private const val AA_X0 = 100
private const val AA_X1 = AA_X0 + AA_W

/** 相位 A 的窗（行 8..13 = 设备行 720..725）与其中的过渡行（设备行 724，cov 0.75）。 */
private const val AA_ROWS_A0 = 8
private const val AA_ROWS_A1 = 14
private const val AA_FRINGE_ROW_A = 12

/** 相位 B 的窗（行 14..19 = 设备行 726..731）与其中的过渡行（设备行 727，cov 0.75）。 */
private const val AA_ROWS_B0 = 14
private const val AA_ROWS_B1 = 20
private const val AA_FRINGE_ROW_B = 15

/** 相位 A/B 的**线心**行（覆盖率恒为 1 的那两行）：A → 722、723；B → 728、729。 */
private val AA_CORE_ROWS_A = 10..11
private val AA_CORE_ROWS_B = 16..17

// ---------------------------------------------------------------------------
// 平滑曲线（Series.smooth()）：开关真的生效、边界回退、缺口回退
//
// 五幕 + 两幕都画在**两块新的空地**上，理由与其它实验图一样（"画面恰好只有这 7 种颜色"
// 那条既有断言不许改弱，所以这些图只在观察期画）：
//   · [SMOOTH_PLOT_*]      主绘图区右下方：主图到 x = 700 / y = 500 为止，
//                          标记尺寸图（x 710..970）到 y = 480 为止。
//   · [SMOOTH_GAP_PLOT_*]  画面最右下角：折返/跨环绕图到 y = 710 为止，
//                          AA 探针只在 x < 320 那一条上。
//
// ★ **判别式的设计**：同一块矩形、同一份数据、同一个颜色、同一个线宽，
// 唯一变的是 Series.smooth()。于是"两张快照的差异"只可能来自那个开关——
// 不存在"因为颜色不同所以当然不同"这种自证。
//
// ★ **每一个期望值都可以手算**（绘图区 280×22、x 窗口 [-0.5, 6.5] ⇒ 每样本 40px；
//   y 窗口**显式声明为 [0, 1]**）：
//   局部 x = (下标 + 0.5) × 40           → 下标 0..6 落在列 20/60/100/140/180/220/260
//   局部 y = (1 − 值) × 22               → 值 0.1 → 19.8、值 0.9 → 2.2
//                                          （再按线宽 4 上下各撑 2px）
//   Catmull-Rom 在两条平台之间（0.9 ↔ 0.1）的 t = 0.25 处偏离弦 **0.075**（手算：
//   两端切线都是 0 ⇒ v(t) = y0 + (y1 − y0)·h01(t)、而弦是 y0 + (y1 − y0)·t，
//   差 = (y1 − y0)·(h01(0.25) − 0.25) = 0.8 × (0.15625 − 0.25) = −0.075）
//   ⇒ 换算到像素是 0.075 × 22 = **1.65 px**。1.65px 足以让一条 4px 宽的带子整行地
//   换位置，所以"两张快照必然不同"不是"应该会不同"，而是"不可能相同"。
//
// ⚠️ **y 窗口不能声明得更窄**：`AxisRange.withMinimumSpan()` 会把跨度不足 1 的范围
//   以中心为心扩到跨度为 1（`MIN_SPAN`），声明 [0.125, 0.875] 实际拿到的是 [0, 1]——
//   于是"手算的映射"与"跑出来的画面"整整差一截，而那一截看起来完全像渲染错了。
//   （这条实测踩过：第一版的推导就是按 [0.125, 0.875] 写的，量出来的墨迹行比预期低两行。）
// ---------------------------------------------------------------------------

/** 曲线实验（折线/面积）的绘图区。 */
private const val SMOOTH_PLOT_X = 700f
private const val SMOOTH_PLOT_Y = 480f
private const val SMOOTH_PLOT_W = 280f
private const val SMOOTH_PLOT_H = 22f

/** 缺口实验（曲线不跨缺口）的绘图区。 */
private const val SMOOTH_GAP_PLOT_X = 330f
private const val SMOOTH_GAP_PLOT_Y = 712f
private const val SMOOTH_GAP_PLOT_W = 300f
private const val SMOOTH_GAP_PLOT_H = 22f

/**
 * 曲线实验的数据：**7 个点、相邻两点在 0.1 与 0.9 之间交替**。
 *
 * <p>交替是刻意的：每一段的两个切线都恰好是 0（{@code (y[k+1] - y[k-1]) / 2} 里
 * 两端相等），于是曲线是"零切线的 S 形"，与弦的偏差在段中是 0.075（见上面的手算）。
 * 换成斜坡那种缓数据的话两者只差零点几像素，判据就退化成橡皮图章。
 *
 * <p>振幅取满 0.1..0.9 而不是 0.2..0.8 也是同一个理由：偏差正比于**跳变幅度**，
 * 而 0.9 − 0.1 = 0.8 是"上下各留 0.1 余量"下能给的最大幅度
 * （再靠近 0 / 1 的话，线宽 2 的带子会顶到绘图区上下边缘被裁掉）。
 *
 * <p>首末两段**必须与不平滑那版逐像素相同**（它们没有外侧邻居，只能画直线）——
 * 那是第三条判据，见 [SMOOTH_HEAD_FROM]。
 */
private val SMOOTH_VALUES = doubleArrayOf(0.1, 0.9, 0.1, 0.9, 0.1, 0.9, 0.1)

/**
 * 曲线实验的 y 窗口：值与绘图区高度 1:1（值 0.1 → 行 19.8、0.9 → 行 2.2）。
 *
 * <p><b>为什么写得这么宽。</b>`AxisRange.withMinimumSpan()` 保证跨度 ≥ 1，
 * 写窄了会被它悄悄扩回来（见上面那条 ⚠️），所以这里直接写 [0, 1]——
 * 声明值就是实际值，手算才有意义。
 */
private const val SMOOTH_Y_WINDOW_MIN = 0.0
private const val SMOOTH_Y_WINDOW_MAX = 1.0

/** 曲线实验的 x 窗口：左右各留半格，7 个样本都落在绘图区内部。 */
private const val SMOOTH_X_WINDOW_MIN = -0.5
private const val SMOOTH_X_WINDOW_MAX = 6.5

/** 曲线与面积实验的线宽：半宽 2 ⇒ 带子 4px 高，1.65px 的偏差必然整行地改变覆盖。 */
private const val SMOOTH_LINE_WIDTH = 4f

/**
 * 缺口实验的数据：**10 个真实样本 + 中间一个 NaN**。
 *
 * <p>缺口两侧各是一整段平台（0.2 / 0.8）。平台是刻意的：**平滑与不平滑在平台上
 * 画出来的必须逐像素相同**（四个控制点全等时 Catmull-Rom 就是那条直线），
 * 于是整块矩形都可以拿来逐像素比对——而只要缺口那一段被"连过去"或者被
 * 插值坏了，比对立刻不等。
 *
 * <p>NaN 放在下标 4（而不是边上）：那样下标 2 与下标 5 那两段的四个控制点里
 * <b>含</b>着这个 NaN，它们只在"看到 NaN 就退回直线"这条规则下才画得对——
 * 把那条规则删掉，这两段就整段消失（{@code gl_Position} 是 NaN）。
 */
private val SMOOTH_GAP_VALUES = doubleArrayOf(
    0.2, 0.2, 0.2, 0.2, Double.NaN, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8
)

/** 缺口实验里 NaN 所在的下标。 */
private const val SMOOTH_GAP_NAN_INDEX = 4

private const val SMOOTH_GAP_X_WINDOW_MIN = 0.0
private const val SMOOTH_GAP_X_WINDOW_MAX = 10.0

/** 缺口实验的线宽：半宽 1.5 ⇒ 带子 3px 高。 */
private const val SMOOTH_GAP_LINE_WIDTH = 3f

/**
 * 九幕的分组边界（帧号）。每一幕都在<b>自己最后那一帧</b>抓快照
 * （`frame` 是"已完成帧数"，所以 `frame == 11` 抓到的正是绘制帧 10 的画面）。
 *
 * <p>每幕十几帧：第一帧要建缓冲并整环上传，后面那些帧才是"稳态"。
 * 第 5 幕是**同一个 Series 对象**把开关从 false 改成 true（见 [drawSmoothCharts]），
 * 它要验的是"改开关会让缓冲重建、而且重建之后画得对"；
 * 第 6、7 幕是**环形缓冲跨环绕**的一对（见 [SMOOTH_WRAP_SMOOTH_END] 的说明）。
 */
private const val SMOOTH_CURVE_PLAIN_END = 11
private const val SMOOTH_CURVE_SMOOTH_END = 22
private const val SMOOTH_GAP_PLAIN_END = 33
private const val SMOOTH_GAP_SMOOTH_END = 44
private const val SMOOTH_TOGGLED_END = 55
private const val SMOOTH_WRAP_PLAIN_END = 66
private const val SMOOTH_WRAP_SMOOTH_END = 77
private const val SMOOTH_AREA_PLAIN_END = 88
private const val SMOOTH_AREA_SMOOTH_END = 100

/**
 * 第三条判据的两段列范围（局部坐标）：**首段**与**末段**，各向里缩 4 列。
 *
 * <p>缩 4 列是必须的：带子沿法向撑开，斜段两端的角点会**横向**探出
 * {@code 半宽 × sin θ}（本几何 0.8px），而相邻那一段的墨迹也会探进交界处
 * ——不缩的话比的就不止那一段了，而"相邻段是曲线"正是本判据要区别开的东西。
 */
private const val SMOOTH_HEAD_FROM = 24
private const val SMOOTH_HEAD_TO = 56
private const val SMOOTH_TAIL_FROM = 224
private const val SMOOTH_TAIL_TO = 256

/**
 * 面积实验的探针列：段 1 的 t = 0.25 处（段 1 从局部列 60 到 100）。
 *
 * <p>取 t = 0.25 是因为那是**偏差最大**的位置（0.075 值 ⇒ 1.65px），
 * 判据"这一列最上面那个墨迹像素的行号：平滑版必须比不平滑版**高**"才有确定的符号。
 * 方向也是手算的：段 1 的值从 0.9 降到 0.1，而曲线在 t < 0.5 时**高于**弦
 * （h00(0.25)×0.9 + h01(0.25)×0.1 = 0.775 > 0.7）⇒ 屏幕上更靠上 ⇒ 行号更小。
 * 手算的绝对行：不平滑 5、平滑 3（弦心 6.6 / 曲线心 4.95，各再减 1.83 的法向分量）
 * ——断言只钉**符号**，不钉这两个数（它们依赖线宽与斜率的取整）。
 *
 * <p>最上面那个墨迹像素必须是**纯主色**（轮廓线），而不是填充的混合色——
 * 所以这里用 `topInkRow(col, 主色)` 而不是"最上面那个非背景像素"：
 * 填充是半透明的（默认 fillAlpha = 0.5），它的颜色与主色不同，
 * 于是"轮廓线画没画"与"填充画没画"在这里天然分得开。
 */
private const val SMOOTH_AREA_PROBE_COL = 70

/** 缺口实验里"一定是背景"的那一段列（局部）：缺口在列 90..150，取中间一段。 */
private const val SMOOTH_GAP_BLANK_FROM = 100
private const val SMOOTH_GAP_BLANK_TO = 140

/**
 * 跨环绕那一对用的数据：**环形缓冲、容量 8、写 20 个样本、值是绝对下标本身**。
 *
 * <p><b>为什么值取"下标本身"。</b>这样相邻两点的差恒为 1（一条直线），
 * 而**共线的样本上 Catmull-Rom 就是那条直线**（切线 {@code (y[k+1]-y[k-1])/2}
 * 恰好等于弦的斜率）——于是"平滑"与"不平滑"必须**逐像素相同**，
 * 包括跨环绕的那一段。
 *
 * <p>这正好把本特性最危险的一块摆在像素口径下：段的四个控制点里，
 * 环的**物理两端**（前置余量、以及末尾两个镜像）不是靠"读到 NaN"暴露的，
 * 而是靠 {@code SeriesBuffer} 在上传时同步的那三份拷贝。
 * 少同步任何一份，被影响的那一段就会**朝 0 弯过去**（那个位置在缓冲里是 0），
 * 而这条断言立刻不等——它比对的是整块矩形。
 *
 * <p>数据与 {@code Series} 沿用既有的跨环绕实验（{@code wrapData}）：
 * 那份数据由既有的那一幕写好（容量 8、窗口 [12, 20]，**跨过环绕点 16**），
 * 这里只是换一块矩形、换一个渲染开关再画一遍——两份的坐标映射完全相同。
 */
private const val SMOOTH_WRAP_WINDOW_START = 12.0
private const val SMOOTH_WRAP_WINDOW_END = 20.0

/** 曲线实验的系列色（只在观察期出现，所以不进校验帧的颜色集合）。 */
private val smoothCurveRgb = 0xE0A020

/** 缺口实验的系列色。 */
private val smoothGapRgb = 0x2FD0A0

/** 面积实验的系列色。 */
private val smoothAreaRgb = 0x8060E0

private val smoothCurveArgb = smoothCurveRgb or (0xFF shl 24)
private val smoothGapArgb = smoothGapRgb or (0xFF shl 24)
private val smoothAreaArgb = smoothAreaRgb or (0xFF shl 24)

// ---------------------------------------------------------------------------
// 回收实验：图表的 GPU 资源跟着使用走（ChartRenderer.releaseUnused）
//
// 它验的是**台账**，不是像素：`ChartRenderer` 按 Series 的对象身份缓存缓冲与拾取号，
// 而"每帧重建 Chart"（= 每帧 new 出新的 Series）是最自然的写法。以前那种写法
// 每帧泄漏一块缓冲并消耗两个拾取号，号耗尽时抛异常——而 GL 线程上的异常在本项目
// 是静默吞掉的，症状是"前几百帧完全正常，然后图表忽然不画了，没有任何报错"。
//
// 三路探针各问一件事（判据互相独立，见各自的断言）：
//   A 每帧 **new** 一个 Chart 和两个 Series  ⇒ 问"台账不再增长"
//   B **同一个 Series 对象**，但只在奇数帧画 ⇒ 问"宽限真的挡得住每隔一帧的用法"
//   C **同一个 Chart**，每帧都画            ⇒ 问"还在用的东西没有被回收掉"
//
// ⚠️ 三块探针画在**屏幕里**，不是屏幕外：本实验要读**拾取号**，而 `Gc.pick`
// 只能命中"这一帧真的画在画面上、且没被 scissor 裁掉"的东西——画到屏幕外的话
// 三个读数恒为 0，而"号不变"与"号恒为 0"在那条断言里长得一模一样。
// ---------------------------------------------------------------------------

/**
 * 三块探针绘图区：帧缓冲**最底下那一条空带**（y 712..736）。
 *
 * <p>位置是照 {@link AA_PLOT_Y} 那一段的推导挑的：AA 探针占 x 20..320、
 * 平滑缺口探针占 x 330..630（两者都在 y 712..734），再往右到 x 984 是空的。
 * 三块横着排开：636 / 754 / 872，各 112 px 宽，右端 984。
 *
 * <p>⚠️ **它依赖帧缓冲 ≥ 984×736**（本机 125% 缩放下实测 988×738）。
 * 这与 AA 那一组是同一条前提，所以 {@code reclaimPrecondition} 会先把它报出来：
 * 越界时三个拾取号会全是 0，而"全是 0"看起来像"回收把还在用的号也收走了"，
 * 归因就完全错了。
 */
private const val RECLAIM_A_PLOT_X = 636f
private const val RECLAIM_B_PLOT_X = 754f
private const val RECLAIM_C_PLOT_X = 872f
private const val RECLAIM_PLOT_Y = 712f
private const val RECLAIM_PLOT_W = 112f
private const val RECLAIM_PLOT_H = 24f

/**
 * 探针系列的数据点数与取值，与拾取探针图（{@code pickProbeData}）同一种形状：
 * **一条水平线**，值取 y 窗口 [0,1] 的正中。
 *
 * <p>取水平线是为了让"探针点落在线上"这句话不需要算斜线的法向：
 * 值 0.5 在窗口 [0,1] 里映射到绘图区正中，即设备行 {@code 712 + 12 = 724}。
 * 拾取热区半宽 4px，所以取正中那一行必然命中——它命不中的话是**绘图区没在
 * 帧缓冲里**（前提那条断言会先报出来），不是回收的事。
 */
private const val RECLAIM_POINTS = 11
private const val RECLAIM_VALUE = 0.5

/** 探针点相对绘图区左上角的偏移（取正中：宽 112/2 = 56、高 24/2 = 12）。 */
private const val RECLAIM_PROBE_DX = 56f

/** 探针点相对绘图区顶边的设备行（= y 窗口正中那一行）。 */
private const val RECLAIM_PROBE_ROW = 12f

/**
 * 探针系列的色，以及它的 ARGB。
 *
 * <p><b>给它们一个别处都没有的颜色是刻意的</b>（不是随手挑的）：
 * 探针万一漏进校验帧，"画面恰好只有这 7 种颜色"会直接点名这个颜色。
 * 用默认色（**白**，`0xFFFFFF`）的话，漏进去的后果是"移动方块不多不少 40×40"
 * 变成 1936——排查方向会跑向方块与鬼影，而真因是三块探针没按时收工。
 * 这个坑是实测踩出来的（见 {@link RECLAIM_END}）。
 */
private val reclaimRgb = 0x123456

private val reclaimArgb = reclaimRgb or (0xFF shl 24)

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已有顶层 `main()`，两个同名顶层函数会让
 * `import com.bingbaihanji.xuan.example.main` 报"重载歧义"。用 `@JvmName("main")`
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

    // ---- 图型实验（柱状 / 面积 / 阶梯）的颜色 ----
    // 同上：只在观察期画，所以也不在画面的常驻颜色集合里。
    // 五者两两不同、也与上面所有颜色不同——柱状图那两条断言是**按颜色分开数**的，
    // 两个系列同色的话"并排分组"这条就退化成"数一数一共有多少像素"。

    /** 柱状图并排的左槽位（系列 A）。 */
    private val barSlotARgb = 0xE03060

    /** 柱状图并排的右槽位（系列 B）。 */
    private val barSlotBRgb = 0x30E0A0

    /** 面积（斜填充、线宽 0）实验的颜色。它只以**半透明**出现（默认 fillAlpha = 0.5）。 */
    private val areaSlopeRgb = 0x207080

    /** 面积（水平填充 + 轮廓线）实验的颜色。 */
    private val areaLineRgb = 0x6060FF

    /** 阶梯实验的颜色。 */
    private val stepRgb = 0xC0C0C0

    // ---- 装配实验（标题 / 图例 / 外边距）的颜色 ----

    /** 装配实验的系列色（同时也是图例色块的颜色）。 */
    private val decorSeriesRgb = 0x2050C0

    private val degenScatterArgb = degenScatterRgb or (0xFF shl 24)

    private val spectrumArgb = spectrumRgb or (0xFF shl 24)

    private val barSlotAArgb = barSlotARgb or (0xFF shl 24)
    private val barSlotBArgb = barSlotBRgb or (0xFF shl 24)
    private val areaSlopeArgb = areaSlopeRgb or (0xFF shl 24)
    private val areaLineArgb = areaLineRgb or (0xFF shl 24)
    private val stepArgb = stepRgb or (0xFF shl 24)
    private val decorSeriesArgb = decorSeriesRgb or (0xFF shl 24)

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

    // -----------------------------------------------------------------------
    // Task 5 的 AA 探针：六个图型各两张（关 / 开），同样观察期抓、校验期断言
    // -----------------------------------------------------------------------

    private var aaLinesOff: Shot? = null
    private var aaLinesOn: Shot? = null
    private var aaStepOff: Shot? = null
    private var aaStepOn: Shot? = null
    private var aaAreaOff: Shot? = null
    private var aaAreaOn: Shot? = null
    private var aaBarOff: Shot? = null
    private var aaBarOn: Shot? = null
    private var aaScatterOff: Shot? = null
    private var aaScatterOn: Shot? = null
    private var aaSpectrumOff: Shot? = null
    private var aaSpectrumOn: Shot? = null

    // -----------------------------------------------------------------------
    // 图型实验的四张图（柱状 / 面积 ×2 / 阶梯）
    // -----------------------------------------------------------------------

    private val barRect = Rect(KIND_PLOT_X, BAR_PLOT_Y.toFloat(), KIND_PLOT_W.toFloat(),
        KIND_PLOT_H.toFloat())

    private val areaSlopeRect = Rect(KIND_PLOT_X, AREA_PLOT_Y.toFloat(), KIND_PLOT_W.toFloat(),
        KIND_PLOT_H.toFloat())

    private val areaLineRect = Rect(KIND_PLOT_X, AREA_LINE_PLOT_Y.toFloat(), KIND_PLOT_W.toFloat(),
        KIND_PLOT_H.toFloat())

    private val stepRect = Rect(KIND_PLOT_X, STEP_PLOT_Y.toFloat(), KIND_PLOT_W.toFloat(),
        KIND_PLOT_H.toFloat())

    /** 柱状实验：同一层里**两个**柱状系列，于是每格并排两根柱（分组的判别式所在）。 */
    private val barDataA = ArrayChartData(
        arrayOf(
            AxisRange(BAR_WINDOW_MIN, BAR_WINDOW_MAX, "类别", ""),
            AxisRange(KIND_Y_WINDOW_MIN, KIND_Y_WINDOW_MAX, "值", "")
        ),
        arrayOf(DoubleArray(BAR_VALUES_A.size) { it.toDouble() }, BAR_VALUES_A.copyOf())
    )

    private val barDataB = ArrayChartData(
        arrayOf(
            AxisRange(BAR_WINDOW_MIN, BAR_WINDOW_MAX, "类别", ""),
            AxisRange(KIND_Y_WINDOW_MIN, KIND_Y_WINDOW_MAX, "值", "")
        ),
        arrayOf(DoubleArray(BAR_VALUES_B.size) { it.toDouble() }, BAR_VALUES_B.copyOf())
    )

    /**
     * 两个柱状系列**共用一组间距**：不同的话 `ChartRenderer` 会抛异常
     * （柱宽取决于系列数，各自一套的话同格里的柱子会宽窄不一）。
     */
    private val barSeriesA = Series("柱A", barDataA, ChartType.BAR)
        .color(barSlotAArgb).categoryGap(BAR_CATEGORY_GAP).barGap(BAR_GAP)

    private val barSeriesB = Series("柱B", barDataB, ChartType.BAR)
        .color(barSlotBArgb).categoryGap(BAR_CATEGORY_GAP).barGap(BAR_GAP)

    private val barChart: Chart = buildBarChart()

    /** 面积（斜填充）实验：**线宽 0**，于是画面上只有填充这一件事好数。 */
    private val areaSlopeData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (AREA_VALUES.size - 1).toDouble(), "样本", ""),
            AxisRange(KIND_Y_WINDOW_MIN, KIND_Y_WINDOW_MAX, "值", "")
        ),
        arrayOf(DoubleArray(AREA_VALUES.size) { it.toDouble() }, AREA_VALUES.copyOf())
    )

    private val areaSlopeSeries = Series("面积斜率", areaSlopeData, ChartType.AREA)
        .color(areaSlopeArgb).lineWidth(0f)

    private val areaSlopeChart: Chart = buildAreaChart(
        areaSlopeData, areaSlopeSeries, 0.0, (AREA_VALUES.size - 1).toDouble()
    )

    /**
     * 面积（水平 + 轮廓线）实验：两个点、同一个值，于是曲线是一条**水平线**——
     * 轮廓线正好占 4 行（线宽 4）而不是斜着切过像素格，期望值全是整数。
     */
    private val areaLineData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, 1.0, "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(AREA_LINE_VALUE, AREA_LINE_VALUE))
    )

    private val areaLineSeries = Series("面积水平", areaLineData, ChartType.AREA)
        .color(areaLineArgb).lineWidth(AREA_LINE_WIDTH)

    private val areaLineChart: Chart = buildAreaChart(areaLineData, areaLineSeries, 0.0, 1.0)

    /** 阶梯实验：四个值、末尾一个 NaN，见 [STEP_VALUES]。 */
    private val stepData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (STEP_VALUES.size - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(DoubleArray(STEP_VALUES.size) { it.toDouble() }, STEP_VALUES.copyOf())
    )

    private val stepSeries = Series("阶梯", stepData, ChartType.STEP)
        .color(stepArgb).lineWidth(STEP_LINE_WIDTH)

    private val stepChart: Chart = buildStepChart()

    // -----------------------------------------------------------------------
    // 平滑曲线实验（Series.smooth()）：两块绘图区、七幕
    //
    // 每一幕都是"同一块矩形 + 同一份数据 + 同一个颜色 + 同一个线宽"，
    // **只有 Series.smooth() 不同**（第 5 幕连 Series 对象都是同一个，只改那个开关）。
    // 于是"两张快照不同"只可能来自那个开关——不存在"因为颜色不同所以当然不同"这种自证。
    //
    // Series **必须逐个建**（哪怕同名同数据）：ChartRenderer 的缓冲与拾取号都按
    // Series 的**对象身份**缓存，共用对象就等于共用缓冲，而缓冲的物理布局正是这里
    // 要比较的东西之一（见下面第 5 幕）。
    // -----------------------------------------------------------------------

    private val smoothCurveRect = Rect(SMOOTH_PLOT_X, SMOOTH_PLOT_Y,
        SMOOTH_PLOT_W, SMOOTH_PLOT_H)

    private val smoothGapRect = Rect(SMOOTH_GAP_PLOT_X, SMOOTH_GAP_PLOT_Y,
        SMOOTH_GAP_PLOT_W, SMOOTH_GAP_PLOT_H)

    /**
     * 造一张"一个系列、一块矩形"的图（x 窗口显式给，y 窗口就是数据的范围）。
     *
     * <p>数据取 {@code ChartData}（不是 {@code ArrayChartData}）：跨环绕那一对用的是
     * {@code RingChartData}，而两者的坐标映射完全一样（都是"轴窗口 → 绘图区"）。
     */
    private fun buildSmoothChart(series: Series, data: ChartData,
                                 xMin: Double, xMax: Double,
                                 plotW: Float, plotH: Float): Chart {
        val xAxis = Axis(AxisType.LINEAR, data.axisRange(0))
            .setDisplayLength(plotW.toDouble())
            .setWindow(xMin, xMax)
        val yAxis = Axis(AxisType.LINEAR, data.axisRange(1))
            .setDisplayLength(plotH.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("平滑").add(series)
        return chart
    }

    /** 曲线实验的数据：7 个点、0.2 与 0.8 交替（见 [SMOOTH_VALUES]）。 */
    private val smoothCurveData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (SMOOTH_VALUES.size - 1).toDouble(), "样本", ""),
            AxisRange(SMOOTH_Y_WINDOW_MIN, SMOOTH_Y_WINDOW_MAX, "值", "")
        ),
        arrayOf(DoubleArray(SMOOTH_VALUES.size) { it.toDouble() }, SMOOTH_VALUES.copyOf())
    )

    /**
     * 第 1 幕与第 5 幕共用的那个系列：**同一个对象**。
     *
     * <p>第 5 幕会把它改成 {@code smooth(true)}——那时缓冲的物理布局必须重建
     * （见 {@code ChartRenderer.bufferFor}），而"这一改真的生效了"由那一幕的
     * 像素与第 2 幕逐像素相同来钉住。
     */
    private val smoothCurvePlainSeries = Series("曲线直线", smoothCurveData, ChartType.LINE)
        .color(smoothCurveArgb).lineWidth(SMOOTH_LINE_WIDTH)

    private val smoothCurveSmoothSeries = Series("曲线平滑", smoothCurveData, ChartType.LINE)
        .color(smoothCurveArgb).lineWidth(SMOOTH_LINE_WIDTH).smooth(true)

    private val smoothCurvePlainChart = buildSmoothChart(smoothCurvePlainSeries, smoothCurveData,
        SMOOTH_X_WINDOW_MIN, SMOOTH_X_WINDOW_MAX, SMOOTH_PLOT_W, SMOOTH_PLOT_H)

    private val smoothCurveSmoothChart = buildSmoothChart(smoothCurveSmoothSeries, smoothCurveData,
        SMOOTH_X_WINDOW_MIN, SMOOTH_X_WINDOW_MAX, SMOOTH_PLOT_W, SMOOTH_PLOT_H)

    /** 缺口实验的数据：中间一个 NaN（见 [SMOOTH_GAP_VALUES]）。 */
    private val smoothGapData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (SMOOTH_GAP_VALUES.size - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(DoubleArray(SMOOTH_GAP_VALUES.size) { it.toDouble() },
            SMOOTH_GAP_VALUES.copyOf())
    )

    private val smoothGapPlainSeries = Series("缺口直线", smoothGapData, ChartType.LINE)
        .color(smoothGapArgb).lineWidth(SMOOTH_GAP_LINE_WIDTH)

    private val smoothGapSmoothSeries = Series("缺口平滑", smoothGapData, ChartType.LINE)
        .color(smoothGapArgb).lineWidth(SMOOTH_GAP_LINE_WIDTH).smooth(true)

    private val smoothGapPlainChart = buildSmoothChart(smoothGapPlainSeries, smoothGapData,
        SMOOTH_GAP_X_WINDOW_MIN, SMOOTH_GAP_X_WINDOW_MAX, SMOOTH_GAP_PLOT_W, SMOOTH_GAP_PLOT_H)

    private val smoothGapSmoothChart = buildSmoothChart(smoothGapSmoothSeries, smoothGapData,
        SMOOTH_GAP_X_WINDOW_MIN, SMOOTH_GAP_X_WINDOW_MAX, SMOOTH_GAP_PLOT_W, SMOOTH_GAP_PLOT_H)

    /**
     * 面积实验：与曲线实验**同一份数据、同一块矩形、同一个 y 窗口**，
     * 于是"顶边该在哪一行"可以照抄上面那份手算。
     *
     * <p>面积图比折线多一层：轮廓线（折线路径画的）与填充的顶边必须是<b>同一条曲线</b>。
     * 填充的顶点在 {@code AREA_VERTEX} 里算、轮廓线在 {@code LINE_VERTEX} 里算，
     * 两处共用同一份 GLSL 函数（见 {@code SeriesShaders.STATION_GLSL}）——
     * 分叉的表现是沿曲线露出一条背景色的细缝，那看起来只是"边有点毛"。
     */
    private val smoothAreaPlainSeries = Series("面积直线", smoothCurveData, ChartType.AREA)
        .color(smoothAreaArgb).lineWidth(SMOOTH_LINE_WIDTH)

    private val smoothAreaSmoothSeries = Series("面积平滑", smoothCurveData, ChartType.AREA)
        .color(smoothAreaArgb).lineWidth(SMOOTH_LINE_WIDTH).smooth(true)

    private val smoothAreaPlainChart = buildSmoothChart(smoothAreaPlainSeries, smoothCurveData,
        SMOOTH_X_WINDOW_MIN, SMOOTH_X_WINDOW_MAX, SMOOTH_PLOT_W, SMOOTH_PLOT_H)

    private val smoothAreaSmoothChart = buildSmoothChart(smoothAreaSmoothSeries, smoothCurveData,
        SMOOTH_X_WINDOW_MIN, SMOOTH_X_WINDOW_MAX, SMOOTH_PLOT_W, SMOOTH_PLOT_H)

    // ---- 跨环绕的一对（第 6、7 幕）：与既有的跨环绕实验共用数据与窗口 ----

    /**
     * 直线那一版：**就是既有跨环绕实验的那个系列**（{@code wrapSeries}）。
     *
     * <p>复用对象是有意的：两张图共用同一个 `Series` 就是共用同一块缓冲，
     * 于是"两张快照必须逐像素相同"这条判据里不会掺进"两块缓冲各自上传到哪"这种差别。
     */
    private val smoothWrapPlainChart = buildSmoothChart(wrapSeries, wrapData,
        SMOOTH_WRAP_WINDOW_START, SMOOTH_WRAP_WINDOW_END, SMOOTH_GAP_PLOT_W, SMOOTH_GAP_PLOT_H)

    /** 平滑那一版：同数据、同窗口、同颜色、同线宽，只多一个开关。 */
    private val smoothWrapSmoothSeries = Series("跨环绕平滑", wrapData, ChartType.LINE)
        .color(wrapArgb).lineWidth(3f).smooth(true)

    private val smoothWrapSmoothChart = buildSmoothChart(smoothWrapSmoothSeries, wrapData,
        SMOOTH_WRAP_WINDOW_START, SMOOTH_WRAP_WINDOW_END, SMOOTH_GAP_PLOT_W, SMOOTH_GAP_PLOT_H)

    // ---- 九幕各自的快照（观察期抓下来，校验期才断言）----

    private var smoothCurvePlainShot: Shot? = null
    private var smoothCurveSmoothShot: Shot? = null
    private var smoothGapPlainShot: Shot? = null
    private var smoothGapSmoothShot: Shot? = null
    private var smoothToggledShot: Shot? = null
    private var smoothWrapPlainShot: Shot? = null
    private var smoothWrapSmoothShot: Shot? = null
    private var smoothAreaPlainShot: Shot? = null
    private var smoothAreaSmoothShot: Shot? = null

    // -----------------------------------------------------------------------
    // 装配实验：标题 / 图例 / 外边距
    // -----------------------------------------------------------------------

    private val decorRect = Rect(DECOR_PLOT_X.toFloat(), DECOR_PLOT_Y.toFloat(),
        DECOR_PLOT_W.toFloat(), DECOR_PLOT_H.toFloat())

    /** 装配实验的数据：两个点、同一个值 0.25，画出来是一条水平线。 */
    private val decorData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, 1.0, "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(DECOR_VALUE, DECOR_VALUE))
    )

    private val decorSeries = Series(DECOR_SERIES_NAME, decorData, ChartType.LINE)
        .color(decorSeriesArgb).lineWidth(DECOR_LINE_WIDTH)

    /**
     * 装配实验图：**只有一张图带标题与图例**，于是"带子挤掉的那块地方"可以写出来。
     *
     * <p>数据刻意取 0.25（不是 0.5）：0.5 会让线落在绘图区正中间，而"标题带 + 图例带"
     * 恰好是对称的（两张 11.2 高的带子），于是**挤与不挤画出来的线在同一行上**——
     * 那条断言就成了橡皮图章。0.25 让线在偏下 3/4 处，两种情形差 7 行。
     */
    // 下面这一组期望值必须声明在 decorChart **之前**：Kotlin 按声明顺序初始化属性，
    // 而 buildDecorChart() 要用 decorPlotBottom - decorPlotTop 当 y 轴的长度
    // （契约要求轴长度 = 绘图区高）。放到后面会读到还没初始化的 0.0，
    // 表现是 Axis.setDisplayLength 抛"必须为正的有限数"——一个与顺序有关、
    // 与几何无关的异常。这行注释是实测换来的。
    // -----------------------------------------------------------------------
    // 装配实验（标题 / 图例 / 外边距）：期望值全部按 ChartLayout 的尺寸规则手算
    //
    // 规则只有两条（见 ChartLayout 的类文档，**与字体无关**）：
    //   带子高 = 字号 × ChartLayout.LINE_HEIGHT_FACTOR
    //   基线   = 带子顶部 + 字号 × ChartLayout.BASELINE_FACTOR
    // 这里把每一条带子的位置照样写一遍——**故意的**：它是"布局算出来的东西"
    // 与"画面上真的那么画"之间的独立一算。写成引用 layout.plotRect() 的话，
    // 布局错了这里跟着错，断言就成了橡皮图章。
    // -----------------------------------------------------------------------

    // 下面这些 y 全是**局部坐标**（相对外框左上角），因为断言读的是那块区域的快照，
    // 快照的行号就是从外框上边缘算起的。外框的 x 从 0 开始，所以 x 不必换算。

    /** 装配实验的内框上边缘（外框 + 上边距）。 */
    private val decorInnerTop = DECOR_PADDING

    /** 装配实验的内框下边缘。 */
    private val decorInnerBottom = DECOR_PLOT_H - DECOR_PADDING

    /** 标题带的高度（字号 × 行高系数）。 */
    private val decorTitleBandH = DECOR_TITLE_FONT * ChartLayout.LINE_HEIGHT_FACTOR

    /** 图例带的高度。 */
    private val decorLegendBandH = DECOR_LEGEND_FONT * ChartLayout.LINE_HEIGHT_FACTOR

    /** 绘图区的上边缘 = 内框上边 + 标题带 + 标题间隙。 */
    private val decorPlotTop = decorInnerTop + decorTitleBandH + DECOR_TITLE_GAP

    /** 绘图区的下边缘 = 内框下边 - 图例带 - 图例间隙。 */
    private val decorPlotBottom = decorInnerBottom - decorLegendBandH - DECOR_LEGEND_GAP

    /** 图例带的 y（贴内框下边）。 */
    private val decorLegendBandY = decorInnerBottom - decorLegendBandH

    /** 数据线所在的局部 y（值 0.25 → 绘图区高度的 75% 处）。 */
    private val decorLineY =
        decorPlotTop + (1.0 - DECOR_VALUE) * (decorPlotBottom - decorPlotTop)

    /**
     * 数据线的墨迹覆盖的局部行范围：线宽 2（半宽 1）→ 四边形的 y 跨度是 `[sy-1, sy+1]`，
     * 覆盖的行是"中心落在其中"的那些。
     */
    private val decorInkRows = kotlin.math.ceil(decorLineY - 1.0 - 0.5).toInt()..
            kotlin.math.floor(decorLineY + 1.0 - 0.5).toInt()

    private val decorChart: Chart = buildDecorChart()

    private fun buildDecorChart(): Chart {
        // 轴的 displayLength 是**绘图区**的宽高（不是外框的）：契约要求它与
        // ChartRenderLayout 收到的矩形一致，否则刻度位置会与数据点错开。
        // 本图不画刻度，所以这条在这里不影响任何像素——但它是那条契约的前提，
        // 写错了会让后面照着抄的人以为 96 是对的。
        val xAxis = Axis(AxisType.LINEAR, decorData.axisRange(0))
            .setDisplayLength((DECOR_PLOT_W - 2 * DECOR_PADDING).toDouble())
            .setWindow(0.0, 1.0)
        val yAxis = Axis(AxisType.LINEAR, decorData.axisRange(1))
            .setDisplayLength((decorPlotBottom - decorPlotTop).toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("装配").add(decorSeries)
        chart.title(DECOR_TITLE_TEXT).titleFontSize(DECOR_TITLE_FONT).titleGap(DECOR_TITLE_GAP)
        chart.legendSide(ChartSide.BOTTOM).legendFontSize(DECOR_LEGEND_FONT)
            .legendSwatchSize(DECOR_SWATCH).legendGap(DECOR_LEGEND_GAP)
        chart.padding(ChartInsets.uniform(DECOR_PADDING))
        return chart
    }

    /** 装配实验图在观察期最后一帧的快照。 */
    private var decorSnapshot: Shot? = null

    /**
     * 走 {@code drawChart} 的那一帧里柱状图的快照（与 [barSnapshot] 逐像素比对）。
     *
     * <p>两条路径画的**是同一张图、同一块矩形**，唯一的差别是中间过没过
     * {@code ChartLayout}——而这张图上没有标题、没有图例、外边距为 0，
     * 于是布局算出来的绘图区就是那块矩形本身。它按像素证明了
     * "给标题留位置这件事，在没有标题时一点都没有改变画面"。
     */
    private var barViaLayoutSnapshot: Shot? = null

    /** 四张图型实验图在观察期最后一帧的快照（校验期做全部断言）。 */
    private var barSnapshot: Shot? = null
    private var areaSlopeSnapshot: Shot? = null
    private var areaLineSnapshot: Shot? = null
    private var stepSnapshot: Shot? = null

    /**
     * 图型实验的拾取结果（观察期抓，校验期断言）。
     *
     * <p>拾取坏了<b>画面一点都不会变坏</b>，只会让点击落在错误的对象上——
     * 所以它必须单独断言。而这几条还有一层：柱状图的拾取要把**柱心的偏移**也算进去，
     * 忘了偏移的 ID pass 会在样本中心周围盖一个方方的热区，而它<b>照样能命中</b>——
     * 只有"点在柱的边缘（离样本中心 7px 处）"能把两种实现分开。
     */
    private var kindPickBarSlotA = false
    private var kindPickBarSlotB = false
    private var kindPickBarBelow = false
    private var kindPickAreaFill = false
    private var kindPickStepRiser = false

    // -----------------------------------------------------------------------
    // 左/右图例、底部标题、带子边界、轴标题：四个变体共用一块矩形
    // -----------------------------------------------------------------------

    private val overRect = Rect(OVER_X.toFloat(), OVER_Y.toFloat(),
        OVER_W.toFloat(), OVER_H.toFloat())

    /** 三个变体共用的数据：两个点、同一个值 0.5，画出来是一条水平线。 */
    private val overData = ArrayChartData(
        arrayOf(AxisRange(0.0, 1.0, "样本", ""), AxisRange(0.0, 1.0, "值", "")),
        arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(OVER_VALUE, OVER_VALUE))
    )

    private val overArgb = overSeriesRgb or (0xFF shl 24)

    /** 四个变体在观察期各自最后那一帧的快照。 */
    private var overSnapshotA: Shot? = null
    private var overSnapshotB: Shot? = null
    private var overSnapshotC: Shot? = null
    private var overSnapshotD: Shot? = null
    private var overSnapshotE: Shot? = null

    /** 变换守卫的探针结果：带着 `translate` 调 `drawChart` 时抛出的那个异常。 */
    private var transformGuardError: Throwable? = null

    // -----------------------------------------------------------------------
    // 回收实验（见文件上方那一段"回收实验"的说明）
    // -----------------------------------------------------------------------

    private val reclaimRectA = Rect(RECLAIM_A_PLOT_X, RECLAIM_PLOT_Y,
        RECLAIM_PLOT_W, RECLAIM_PLOT_H)

    private val reclaimRectB = Rect(RECLAIM_B_PLOT_X, RECLAIM_PLOT_Y,
        RECLAIM_PLOT_W, RECLAIM_PLOT_H)

    private val reclaimRectC = Rect(RECLAIM_C_PLOT_X, RECLAIM_PLOT_Y,
        RECLAIM_PLOT_W, RECLAIM_PLOT_H)

    /**
     * A/B/C 三路探针共用的数据（一份静态的 [ArrayChartData]，一条水平线）。
     *
     * <p>共用是刻意的：A 组每帧 new 两个 `Series`，三个 `Series` 指向**同一份数据**
     * 才能保证"重建缓冲要重传的字节数"在 A/B/C 三处是同一个数
     * ——否则"传了 44 字节"与"传了 80 字节"的差别会掺进数据本身的差别。
     */
    private val reclaimData = ArrayChartData(
        arrayOf(
            AxisRange(0.0, (RECLAIM_POINTS - 1).toDouble(), "样本", ""),
            AxisRange(0.0, 1.0, "值", "")
        ),
        arrayOf(
            DoubleArray(RECLAIM_POINTS) { it.toDouble() },
            DoubleArray(RECLAIM_POINTS) { RECLAIM_VALUE }
        )
    )

    /**
     * B 组的系列：**同一个对象跨帧复用**，但只在奇数帧画（见 [drawReclaimProbes]）。
     *
     * <p>它的存在就是为了那条宽限：如果回收做成"上一帧没画就收"，
     * 这个系列会**每画一次就销毁并重建一次缓冲**——而重建意味着把整个环重传。
     * 画面上逐像素相同，只有 GPU 上传量悄悄翻倍。
     */
    private val reclaimBSeries = Series("回收B", reclaimData, ChartType.LINE).color(reclaimArgb)

    private val reclaimBChart: Chart = buildReclaimChart(reclaimBSeries, reclaimRectB)

    /** C 组的系列：**每帧都画**，所以它一次都不该被回收。 */
    private val reclaimCSeries = Series("回收C", reclaimData, ChartType.LINE).color(reclaimArgb)

    private val reclaimCChart: Chart = buildReclaimChart(reclaimCSeries, reclaimRectC)

    /**
     * 一帧的读数。做成一个小类而不是几条平行的列表：B 组只在奇数帧有值，
     * 用平行列表的话下标会错开，而"数错了哪一帧"的失败看起来像"回收坏了"。
     */
    private class ReclaimSample(
        /** 这一份读数取自哪一个绘制帧。 */
        val frame: Int,
        /** `Gc.pickRegistry.size()`：全项目共用的那一本注册表。 */
        val registrySize: Int,
        /** `ChartRenderer.cachedBufferCount()`。 */
        val bufferCount: Int,
        /** B 组读回的拾取号；这一帧没画它时为 0。 */
        val bId: Int,
        /** B 组这一帧的上传字节数；这一帧没画它时为 0。 */
        val bUpload: Int,
        /** C 组读回的拾取号。 */
        val cId: Int,
        /** C 组这一帧的上传字节数。 */
        val cUpload: Int,
    )

    /** 回收期逐帧记下的读数（只 append，断言在校验帧上做）。 */
    private val reclaimSamples = ArrayList<ReclaimSample>()

    /**
     * 回收实验的四条判据（设计说明见文件上方"回收实验"那一段）。
     *
     * <h2>判据为什么只能是台账，不能是像素</h2>
     * <p>回收只发生在"不再画了"之后，而那时画面上本来就没有它——于是
     * "<b>回收真的发生了</b>"与"<b>每帧泄漏一块缓冲</b>"这两件事的
     * <b>画面逐像素相同</b>，本文件其它任何一条像素断言都分不开它们。
     * 所以这里读的是三个台账口径：
     * <ul>
     *   <li>{@code Gc.pickRegistry.size()}——拾取号有没有被归还（公开入口）；</li>
     *   <li>{@code ChartRenderer.cachedBufferCount()}——缓冲有没有被释放
     *       （与 {@code takeUploadedBytes} 同类：为一个断言而存在的观测口）；</li>
     *   <li>{@code takeUploadedBytes(series)}——"缓冲对象还是原来那一个"没有公开入口，
     *       而 <b>重建的唯一可见后果就是重传整环</b>，所以"有没有重传"是它的等价判据，
     *       而且是用户真的会感觉到的那一面。</li>
     * </ul>
     *
     * <h2>读数只取 RECLAIM_START + 3 之后</h2>
     * <p>A 组每帧 new 两个系列，而回收有<b>两代宽限</b>，所以"同时活着的 A 组系列数"
     * 要到第 3 帧才停止爬坡（2 → 4 → 6，之后一直是 6）。
     * 把爬坡那两帧算进去的话，断言会因为**正确的实现**而失败。
     *
     * <h2>每条断言都打印它量到的实际值</h2>
     * <p>而且打印窗口覆盖断言窗口：下面先把 30 帧的读数整表打出来，再做断言。
     * 失败时不用再去猜"到底是多少"。
     */
    private fun verifyReclaim(w: Int, h: Int, report: (String, Boolean, String) -> Unit) {
        println("\n-- 回收实验：图表资源跟着使用走（ChartRenderer.releaseUnused）--")

        val needW = RECLAIM_C_PLOT_X + RECLAIM_PLOT_W
        val needH = RECLAIM_PLOT_Y + RECLAIM_PLOT_H
        report("前提：回收实验的三块探针绘图区都在帧缓冲里", w >= needW && h >= needH,
            "帧缓冲 ${w}x$h，探针要 x ≥ $needW、y ≥ $needH。" +
                    "越界时三个拾取号会全是 0——而\"全是 0\"看起来像\"回收把还在用的号" +
                    "也收走了\"，归因就完全错了（与 AA 那一组是同一条前提）")

        val tail = reclaimSamples.filter { it.frame >= RECLAIM_START + 3 }
        if (tail.size < RECLAIM_FRAMES - 3) {
            // 没有这条前提的话，下面的判据会在**空集合**上恒真——一条静默的绿。
            report("前提：回收实验的读数取满了 ${RECLAIM_FRAMES - 3} 帧", false,
                "实际 ${tail.size} 帧（共记下 ${reclaimSamples.size} 帧，" +
                        "回收期是 [$RECLAIM_START, $RECLAIM_END)）")
            return
        }

        // 两组"第一次被画"的样本：它们在建缓冲那一帧上，**落在断言窗口之外**
        // （窗口从 RECLAIM_START + 3 起，理由见函数文档），但要把它们的读数打出来
        // ——它们证明这个读数口真的能看见"整环重传"（44 字节 = 11 个样本 × 4），
        // 于是窗口里那一片 0 才有意义，而不是"这个口读数恒为 0"。
        val firstBDraw = reclaimSamples.first { it.frame % 2 == 1 }
        val firstCDraw = reclaimSamples.first()

        println("  回收期逐帧读数（帧号 / pickRegistry.size / cachedBufferCount / " +
                "B 号 / B 上传 / C 号 / C 上传）：")
        for (s in reclaimSamples) {
            // "B 这一帧没画"用 `-` 标出来：写成 0 的话与"号真的读成了 0"分不开，
            // 而后者正是断言要抓的东西。
            val bDrawn = s.frame % 2 == 1
            println(
                "    ${s.frame} / ${s.registrySize} / ${s.bufferCount} / " +
                        "${if (bDrawn) s.bId.toString() else "-"} / " +
                        "${if (bDrawn) s.bUpload.toString() else "-"} / " +
                        "${s.cId} / ${s.cUpload}"
            )
        }

        // ---- ① 每帧重建 Chart：台账不增长 ----
        //
        // 这一段是本次修复的正面判据。它的"对照组"（不能恒真）由变异给出：
        // 把 Gc.beginFrame 里那句 releaseUnused() 删掉，这两个读数就会**每帧 +2**
        // ——A 组每帧留下两个新 Series，而没有任何东西回收它们。
        // 下面的 detail 里把那个对照组的**精确**数字写出来：不开回收时
        // `size` 在这 ${tail.size} 帧里会单调涨 ${2 * (tail.size - 1)}（每帧恰好 +2）。
        val regFirst = tail.first().registrySize
        val regLast = tail.last().registrySize
        report("① 每帧重建 Chart（每帧 new 两个 Series）${tail.size} 帧后，" +
                "pickRegistry 的规模不增长",
            tail.all { it.registrySize == regFirst },
            "第 ${tail.first().frame} 帧 size=$regFirst，第 ${tail.last().frame} 帧 size=$regLast；" +
                    "本段 size 的取值集合=${tail.map { it.registrySize }.toSortedSet()}。" +
                    "对照组（把 Gc.beginFrame 里的 releaseUnused() 删掉）这一段会涨 " +
                    "${2 * (tail.size - 1)} 个号（每帧恰好 +2，A 组两个新系列）")

        val bufFirst = tail.first().bufferCount
        val bufLast = tail.last().bufferCount
        report("① 每帧重建 Chart ${tail.size} 帧后，缓存的缓冲数不增长",
            tail.all { it.bufferCount == bufFirst },
            "第 ${tail.first().frame} 帧 bufferCount=$bufFirst，" +
                    "第 ${tail.last().frame} 帧 bufferCount=$bufLast；" +
                    "本段 bufferCount 的取值集合=${tail.map { it.bufferCount }.toSortedSet()}。" +
                    "对照组（不开回收）这一段会涨 ${2 * (tail.size - 1)} 块" +
                    "（每帧泄漏两块 SeriesBuffer）")

        // ---- ② 每隔一帧画一次：宽限挡得住，缓冲与号都不动 ----
        //
        // 这一条是**反面**：B 组一直画着同一个 Series 对象，所以它一次都不该被回收。
        // 它对"宽限"这个词是定向敏感的：把 GRACE_GENERATIONS 从 2 改成 1
        //（"上一帧没画就收"）之后，B 组在每一帧的帧首被释放、又在同一帧里被重建，
        // 于是"不重传"那条立刻倒，而画面逐像素相同。
        val bFrames = tail.filter { it.frame % 2 == 1 }
        val bIds = bFrames.map { it.bId }.toSortedSet()
        report("② 每隔一帧画一次（奇数帧画、偶数帧不画）：拾取号始终不变且非 0",
            bIds.size == 1 && bIds.first() != 0,
            "本段画了 ${bFrames.size} 次，读到的号集合=$bIds（期望恰好一个元素且非 0）。" +
                    "号变了说明这个还在用的系列被回收后又重新注册（拾取会静默失效）；" +
                    "0 说明这一帧压根没画出来（先看上面那条前提）")

        val bUploads = bFrames.map { it.bUpload }
        report("② 每隔一帧画一次：缓冲没有被回收（每一次都不重传）",
            bUploads.all { it == 0 },
            "本段 ${bUploads.size} 次画它的上传字节取值集合=${bUploads.toSortedSet()}" +
                    "（期望只有 0）。非 0 就是\"缓冲被销毁又重建\"的直接证据——" +
                    "重建必然把整环重传（1M 点就是 4 MB），而画面逐像素相同。" +
                    "这个读数口本身不是恒 0 的：它第一次被画（帧 " +
                    "${firstBDraw.frame}，在建缓冲那一帧上，落在本段之外）实测传了 " +
                    "${firstBDraw.bUpload} 字节")

        // ---- ③ 一直画同一个 Chart：还在用的东西不会被回收 ----
        val cIds = tail.map { it.cId }.toSortedSet()
        report("③ 一直画同一个 Chart：拾取号始终不变且非 0",
            cIds.size == 1 && cIds.first() != 0,
            "本段 ${tail.size} 帧读到的号集合=$cIds（期望恰好一个元素且非 0）")

        val cUploads = tail.map { it.cUpload }
        report("③ 一直画同一个 Chart：缓冲没有被回收（每一帧都不重传）",
            cUploads.all { it == 0 },
            "本段 ${cUploads.size} 帧的上传字节取值集合=${cUploads.toSortedSet()}" +
                    "（期望只有 0）。非 0 说明回收把**正在画**的系列也收掉了——" +
                    "那表现为每帧重传整环，而且拾取号会跟着变。" +
                    "同样地，这个读数口不是恒 0 的：它第一次被画（帧 " +
                    "${firstCDraw.frame}，建缓冲那一帧，落在本段之外）实测传了 " +
                    "${firstCDraw.cUpload} 字节")
    }

    /**
     * 造一张探针图：一条水平线，两个轴都按数据自己的范围给窗口。
     *
     * <p>不设标题、不设图例——本实验走的是低层的 `draw`，装饰本来就不参与，
     * 那两行只是把"这里没有装饰"写在代码里。
     */
    private fun buildReclaimChart(series: Series, rect: Rect): Chart {
        val xAxis = Axis(AxisType.LINEAR, reclaimData.axisRange(0))
            .setDisplayLength(rect.width.toDouble())
            .setWindow(0.0, (RECLAIM_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, reclaimData.axisRange(1))
            .setDisplayLength(rect.height.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("回收实验").add(series)
        return chart
    }

    /**
     * 造**一个全新的** Chart 与两个全新的 Series（A 组每帧调一次）。
     *
     * <p>它必须每帧都新建，一次都不能省：这条断言要的正是"用户每帧都 new 出新的
     * `Series` 对象"那种用法（`Chart` 看起来是个纯计算对象，所以那是最自然的写法）。
     * 把 Chart 缓存起来就没有东西可泄漏了，测的也就不是那条缺陷了。
     */
    private fun buildReclaimAFrame(): Chart {
        val xAxis = Axis(AxisType.LINEAR, reclaimData.axisRange(0))
            .setDisplayLength(RECLAIM_PLOT_W.toDouble())
            .setWindow(0.0, (RECLAIM_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, reclaimData.axisRange(1))
            .setDisplayLength(RECLAIM_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("回收A")
            .add(Series("回收A-甲", reclaimData, ChartType.LINE).color(reclaimArgb))
            .add(Series("回收A-乙", reclaimData, ChartType.LINE).color(reclaimArgb))
        return chart
    }

    /**
     * 回收实验的三路探针，**只在回收期画**（`n ∈ [RECLAIM_START, TOTAL_FRAMES)`）。
     *
     * <p>"只在回收期画"与其它实验图是同一条纪律：校验帧的画面上不许有它们的颜色，
     * 否则"画面恰好只有这 7 种颜色"那条既有断言会失败。上界之所以是
     * {@link RECLAIM_END} 而不是 {@code TOTAL_FRAMES}，见 {@link RECLAIM_END} 的说明。
     *
     * <p>读数在本方法末尾取，取的是**本帧全部画完之后**的台账。
     * 三路探针的绘制顺序（A → B → C）在同一帧内固定，所以逐帧的读数可比。
     *
     * @param n 本帧的绘制帧下标（与 `frame` 差 1，见 [drawScene]）
     */
    private fun drawReclaimProbes(gc: Gc, n: Int) {
        // A 组：每帧新建 Chart + 两个 Series。
        gc.charts.draw(buildReclaimAFrame(), reclaimRectA, gc.width, gc.height)
        // B 组：同一个 Series 对象，只在**奇数帧**画（偶数帧完全不碰它）。
        val bDrawn = n % 2 == 1
        if (bDrawn) {
            gc.charts.draw(reclaimBChart, reclaimRectB, gc.width, gc.height)
        }
        // C 组：同一个 Chart，每帧都画。
        gc.charts.draw(reclaimCChart, reclaimRectC, gc.width, gc.height)

        // 上传字节数必须**当场取走**（`takeUploadedBytes` 是取走即清零的语义）。
        // B 组没画的那一帧不取：它的缓冲还在（宽限），但那一帧压根没有上传，
        // 取回来的 0 与"重建之后传了整环"是两件事，混在一起就分不开了。
        val bUpload = if (bDrawn) gc.charts.takeUploadedBytes(reclaimBSeries) else 0
        val cUpload = gc.charts.takeUploadedBytes(reclaimCSeries)

        // 拾取号只能在**它这一帧画着的时候**问（`Gc.pick` 读的是本帧的 ID 缓冲）。
        val bId = if (bDrawn) pickAtReclaimProbe(gc, reclaimRectB) else 0
        val cId = pickAtReclaimProbe(gc, reclaimRectC)

        reclaimSamples.add(
            ReclaimSample(
                frame = n,
                registrySize = gc.pickRegistry.size(),
                bufferCount = gc.charts.cachedBufferCount(),
                bId = bId,
                bUpload = bUpload,
                cId = cId,
                cUpload = cUpload,
            )
        )
    }

    /** 在探针绘图区的正中央读一次拾取号（那里必然压着那条水平线）。 */
    private fun pickAtReclaimProbe(gc: Gc, rect: Rect): Int =
        gc.pick(rect.x + RECLAIM_PROBE_DX, rect.y + RECLAIM_PROBE_ROW)?.id() ?: 0

    /**
     * 造一个变体：图例放哪一边 + 可选的底部标题 + 外边距。
     *
     * <p>轴的 displayLength 传的是**外框**尺寸而不是绘图区尺寸——本实验不画刻度，
     * 所以它对像素没有任何影响（与装配实验同一情况，见那里的说明）。
     */
    private fun buildOverChart(name: String, side: ChartSide, withTitle: Boolean): Chart {
        val xAxis = Axis(AxisType.LINEAR, overData.axisRange(0))
            .setDisplayLength(OVER_W.toDouble())
            .setWindow(0.0, 1.0)
        val yAxis = Axis(AxisType.LINEAR, overData.axisRange(1))
            .setDisplayLength(OVER_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("溢出实验").add(
            Series(name, overData, ChartType.LINE).color(overArgb).lineWidth(OVER_LINE_WIDTH)
        )
        chart.legendSide(side).legendFontSize(OVER_FONT).legendSwatchSize(OVER_SWATCH)
            .legendGap(OVER_LEGEND_GAP)
        if (withTitle) {
            chart.title(OVER_TITLE_TEXT).titleSide(ChartSide.BOTTOM)
                .titleFontSize(OVER_FONT).titleGap(OVER_TITLE_GAP)
        }
        chart.padding(ChartInsets.uniform(OVER_PADDING))
        return chart
    }

    /** 变体 A：**左**图例 + 底部标题。 */
    private val overChartA = buildOverChart(OVER_NAME, ChartSide.LEFT, withTitle = true)

    /** 变体 B：**右**图例 + 底部标题。 */
    private val overChartB = buildOverChart(OVER_NAME, ChartSide.RIGHT, withTitle = true)

    /** 变体 C：**底部**图例 + 一个比带子还长的系列名。 */
    private val overChartC = buildOverChart(OVER_LONG_NAME, ChartSide.BOTTOM, withTitle = false)

    /**
     * 变体 D：轴标题 + 刻度预留（其余都关掉，好让绘图区还剩下几像素画得下一条线）。
     *
     * <p>带子从内框下边往上依次是：图例带（8×1.4 = 11.2）→ 图例间隙 3 →
     * 轴标题带（11.2）→ 轴标题间隙 2 → 刻度预留 2 → 绘图区。剩下的绘图区高 4.6px，
     * 数据线（值 0.5、线宽 2）因此落在两行上——**那两行的位置就是"带子真的挤过"的证据**：
     * 少了轴标题带与刻度预留，绘图区会高 15.2px，线会落到局部第 10、11 行。
     */
    private val overChartD: Chart = buildOverChart(OVER_NAME, ChartSide.BOTTOM, withTitle = false)
        .axisTitlesVisible(true).axisTitleFontSize(OVER_FONT).axisTitleGap(OVER_AXIS_GAP)
        .tickLabelReserve(ChartSide.BOTTOM, OVER_TICK_RESERVE)

    /**
     * 变体 E：{@code LINE_AND_MARKERS}——**折线 + 标记点那一半**。
     *
     * <p>x 窗口取 [-0.5, 1.5]（比数据范围宽）：两个样本因此落在绘图区的 1/4 与 3/4 处，
     * 标记点（边长 2×半径 = 6）整个在绘图区里。窗口贴着数据取 [0,1] 的话，两个标记会正好压在
     * 绘图区左右边界上、各被 `glScissor` 裁掉一半——那样"两个 6×6 的块"这条断言就废了。
     *
     * <p>关掉图例：这一变体要的是"线的行号 + 标记的两块"都落在可手算的位置上。
     */
    private val overChartE: Chart = run {
        val xAxis = Axis(AxisType.LINEAR, overData.axisRange(0))
            .setDisplayLength(OVER_W.toDouble())
            .setWindow(OVER_E_WINDOW_MIN, OVER_E_WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, overData.axisRange(1))
            .setDisplayLength(OVER_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("标记").add(
            Series(OVER_NAME, overData, ChartType.LINE_AND_MARKERS)
                .color(overArgb).lineWidth(OVER_LINE_WIDTH).markerSize(OVER_E_MARKER_RADIUS)
        )
        chart.legendVisible(false)
        chart.padding(ChartInsets.uniform(OVER_PADDING))
        chart
    }

    /** 本帧该画哪个变体（五个变体按帧轮换，见 [captureOverflow]）。 */
    private fun overChartAt(n: Int): Chart = when {
        n < OVER_SWITCH_AB -> overChartA
        n < OVER_SWITCH_BC -> overChartB
        n < OVER_SWITCH_CD -> overChartC
        n < OVER_SWITCH_DE -> overChartD
        else -> overChartE
    }

    /**
     * 画本帧该画的变体。**只在观察期画**（与其它实验图同一个理由：校验帧的画面上
     * 不该有它，否则"画面只有这 7 种颜色"那条会被它的颜色打破）。
     */
    private fun drawOverflowChart(gc: Gc, n: Int) {
        gc.charts.drawChart(overChartAt(n), overRect, gc.width, gc.height)
    }

    /**
     * 在**画面真的有东西**的那一帧把四个变体的快照抓下来（"必须在那一帧抓"的理由见
     * [captureObservations]）。`frame` 是"已完成帧数"，所以它等于最后一帧的下标 + 1。
     *
     * <p>顺手做一次**变换守卫**的探针：带着 `translate` 调 {@code drawChart} 必须抛异常。
     * 这件事在画面上**没有任何痕迹**（它本来就该什么都不画），所以只能直接问一次。
     * 探针放在抓快照**之前**：万一守卫失效（变异），平移过的装饰会画进这一帧的快照里，
     * 下面那些像素断言跟着红——而不是"静默地绿"。
     *
     * <p>反向对照是现成的：每一帧的 [drawOverflowChart] 都**不带变换**调同一个
     * {@code drawChart}，它要是抛异常，`drawError` 会先让校验判负。
     */
    private fun captureOverflow(bridge: FXGLTransfer, h: Int) {
        when (frame) {
            OVER_SHOT_A -> overSnapshotA = grab(h, overRect)
            OVER_SHOT_B -> overSnapshotB = grab(h, overRect)
            OVER_SHOT_C -> overSnapshotC = grab(h, overRect)
            OVER_SHOT_D -> overSnapshotD = grab(h, overRect)
            OVER_SHOT_E -> overSnapshotE = grab(h, overRect)
        }
        if (frame == OVER_SHOT_A) {
            val gc = bridge.gc() ?: return
            gc.save()
            gc.translate(3f, 3f)
            transformGuardError = try {
                gc.charts.drawChart(overChartA, overRect, gc.width, gc.height)
                null
            } catch (t: Throwable) {
                t
            } finally {
                gc.restore()
            }
        }
    }

    /**
     * 造柱状图：两个柱状系列在同一层，x 轴窗口左右各留半格。
     *
     * <p>半格余量是**必须**的：柱心落在样本的屏幕 x 上，所以窗口取 [0,3] 时
     * 最左那根柱有一半在绘图区之外、被 scissor 裁掉——那条"每根柱 8×高 像素"
     * 的断言会莫名其妙地少一半，而画面看起来只是"第一根柱贴着边"。
     */
    private fun buildBarChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, barDataA.axisRange(0))
            .setDisplayLength(KIND_PLOT_W.toDouble())
            .setWindow(BAR_WINDOW_MIN, BAR_WINDOW_MAX)
        val yAxis = Axis(AxisType.LINEAR, barDataA.axisRange(1))
            .setDisplayLength(KIND_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        // 顺序即绘制顺序：B 画在 A 之上。两者**不重叠**（并排），所以顺序不影响像素。
        chart.addLayer("柱状").add(barSeriesA).add(barSeriesB)
        // 关掉图例是**刻意的**：这张图要用两条路径（draw 与 drawChart）各画一帧再逐像素比对，
        // 而 drawChart 会照着配置排布装饰——留着一个默认可见的图例，两条路径就不该相同了
        // （那时差异来自布局本身，而不是缺陷）。"不设标题/图例时逐像素不变"正是要验的那条。
        chart.legendVisible(false)
        return chart
    }

    /** 造面积图：x 窗口就是数据范围（每样本 96/(N-1) px），y 窗口见 [KIND_Y_WINDOW_MIN]。 */
    private fun buildAreaChart(data: ArrayChartData, series: Series,
                               windowMin: Double, windowMax: Double): Chart {
        val xAxis = Axis(AxisType.LINEAR, data.axisRange(0))
            .setDisplayLength(KIND_PLOT_W.toDouble())
            .setWindow(windowMin, windowMax)
        val yAxis = Axis(AxisType.LINEAR, data.axisRange(1))
            .setDisplayLength(KIND_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("面积").add(series)
        return chart
    }

    /** 造阶梯图：x 窗口 [0, 3]（每样本 32px）、y 窗口 [0, 1]。 */
    private fun buildStepChart(): Chart {
        val xAxis = Axis(AxisType.LINEAR, stepData.axisRange(0))
            .setDisplayLength(KIND_PLOT_W.toDouble())
            .setWindow(0.0, (STEP_VALUES.size - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, stepData.axisRange(1))
            .setDisplayLength(KIND_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("阶梯").add(stepSeries)
        return chart
    }

    /** 图型实验（y 窗口 [-0.5, 1]）里数值 → 局部 y。 */
    private fun kindY(value: Double): Double =
        (1.0 - (value - KIND_Y_WINDOW_MIN) / (KIND_Y_WINDOW_MAX - KIND_Y_WINDOW_MIN)) * KIND_PLOT_H

    /**
     * 预乘混合（{@code GL_ONE} / {@code GL_ONE_MINUS_SRC_ALPHA}）之后某个通道的期望值。
     *
     * <p>片段着色器输出的是<b>预乘色</b> {@code (rgb×a, a)}，所以混合结果就是
     * {@code src×a + dst×(1-a)}——这一条断言钉住的正是那个约定：写成非预乘的因子组合
     * 时半透明填充会比预期暗一截，而"颜色深一点"在画面上没有参照物。
     */
    private fun blendChannel(dst: Int, src: Int, alpha: Double): Int =
        (src * alpha + dst * (1.0 - alpha)).roundToInt()

    /** 两个 RGB 的每个通道都相差不超过 {@code tol}。 */
    private fun channelClose(a: Int, b: Int, tol: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tol &&
                abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tol &&
                abs((a and 0xFF) - (b and 0xFF)) <= tol

    /**
     * 面积（斜填充）实验的 CPU 参考：**按定义**逐列数一遍"曲线之下、基线之上的像素"。
     *
     * <h2>为什么这条参考必须存在</h2>
     * <p>像素口径的形状断言有强弱之分。"该颜色有像素"对"填充画反了""填到窗口下沿去了"
     * "只填了第一段"全都成立，是橡皮图章；"某些探针点是对的颜色"强一些，但它们只是有限的
     * 几个点。<b>逐列数一遍</b>才能把整条边界钉住：任何一列上边界差一个像素都会让总数对上不了。
     *
     * <h2>它为什么是"显然对的"</h2>
     * <p>它不从着色器的公式出发（{@code aCorner}、{@code uBaseline}、六个 uniform 一个都不用），
     * 而是从**定义**出发：横轴是数据下标，值由相邻样本线性插值，
     * 覆盖的像素就是"中心落在曲线与基线之间"的那些——一行代码一个概念。
     * 与频谱的 `SpectrumReference` 同一个套路。
     *
     * <p>它假定了采样规则是"像素中心在不在多边形里"（GL 的默认规则）。几何是刻意挑的：
     * 边界在每一列的中心处都不是整数（斜坡的斜率 0.5、起点 47.75），
     * 所以不存在"正好落在边界上"的像素，参考与光栅化不会因为取舍规则不同而差一。
     *
     * @param matrix 每个数据点的值
     * @param baseline 基线（数值）
     * @return 逐列累加出来的填充像素数
     */
    private fun areaFillReference(matrix: DoubleArray, baseline: Double): Int {
        val lastIndex = matrix.size - 1
        var total = 0
        for (col in 0 until KIND_PLOT_W) {
            // 列中心对应的数据下标与值（相邻样本线性插值）
            val index = (col + 0.5) / KIND_PLOT_W * lastIndex
            val i = index.toInt().coerceIn(0, lastIndex - 1)
            val frac = index - i
            val value = matrix[i] + (matrix[i + 1] - matrix[i]) * frac
            val top = kindY(value)
            val bottom = kindY(baseline)
            val yLo = minOf(top, bottom)
            val yHi = maxOf(top, bottom)
            // 中心落在 [yLo, yHi) 里的行
            var row = kotlin.math.ceil(yLo - 0.5).toInt()
            while (row + 0.5 < yHi) {
                total++
                row++
            }
        }
        return total
    }

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

        /**
         * 某个局部列区间里不是给定颜色的像素数（整列都算）。
         *
         * <p>它是"缺口的这一段必须是背景"那类判据的口径：写成"该颜色 0 个"会被
         * <b>任何</b>别的颜色骗过去（曲线被插值过去时画出来的可能是混合色、
         * 也可能是另一段落的更暗的值），而"不是背景"把那些一起拦下。
         */
        fun countNonBackgroundInCols(x0: Int, x1: Int, rgb: Int): Int {
            var n = 0
            for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                for (y in 0 until h) {
                    if (at(x, y) != rgb) n++
                }
            }
            return n
        }

        /** 两张快照是不是同一张图。 */
        fun sameAs(other: Shot): Boolean =
            w == other.w && h == other.h && px.contentEquals(other.px)

        /**
         * 两张**同尺寸**快照在给定列区间（含两端）里不同的像素数；行是整列都算。
         *
         * <p>它是"平滑与不平滑必须不同"（以及"这两处必须**完全**相同"）那几条断言的判别式：
         * 报出**变化了多少像素**，才能把"只差一个抗锯齿边缘像素"与"整条曲线的形状变了"分开。
         * 只报"变了 / 没变"的话，前者会让"开关没生效"那条假通过（反过来也一样）。
         */
        fun diffIn(x0: Int, x1: Int, other: Shot): Int {
            require(w == other.w && h == other.h) {
                "两张快照的尺寸必须相同才能逐像素比：${w}x$h vs ${other.w}x${other.h}"
            }
            var n = 0
            for (y in 0 until h) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                    if (at(x, y) != other.at(x, y)) n++
                }
            }
            return n
        }

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
        // 采样数走**同一个系统属性**（`-Dxuan.probe.msaa`，解析与 MsaaVerifier 共用）：
        // 没有这一行时下面那道守卫是**死代码**——msaa 恒为默认 0，"明确拒绝"只在改源码时才可能触发。
        val bridge = FXGLTransfer(msaa = readRequestedMsaa(), font = textFont())
        // ★ 回读拒绝守卫（实现见 MsaaVerifier.kt 的 requirePixelReadback）：本校验器的读数
        //   全部来自 glReadPixels，而多采样画布上那次调用是**非法操作**——它会读回全 0，
        //   然后让下面每一条断言报"画面全黑"式的假失败。
        requirePixelReadback(bridge)
        // onFrame 走 [runFrame] 而不是直接进 [drawScene]：异常必须在这里就被接住，
        // 见 [runFrame] 的第 4 组说明。
        bridge.onFrame { gc -> runFrame(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "Xuan Chart Verifier"
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

    // -----------------------------------------------------------------------
    // Task 5：AA 探针的六张图（几何与相位见文件上方那段推导）
    // -----------------------------------------------------------------------

    /** 探针图的系列：8 个等值样本、纯白、线宽 3。 */
    private fun aaSeries(data: ArrayChartData, type: ChartType): Series =
        Series("AA", data, type)
            .color(AA_INK_ARGB)
            .lineWidth(AA_LINE_WIDTH)
            .markerSize(AA_MARKER_RADIUS)
            .baseline(AA_BASELINE)
            // ★ 填充必须**不透明**：默认的 fillAlpha = 0.5 会让面积填充的满覆盖色
            // 变成灰（127/128），而判据的"过渡像素"就是按"既非背景也非纯墨色"数的——
            // 那样一来 **AA 关**的那一帧也会数出一大堆"过渡像素"，① 恒假。
            // （不是判据挑剔：那一堆灰确实是满覆盖，只是"满覆盖"不等于"纯白"。）
            .fillAlpha(1f)

    /** 造一张探针图：一块 [aaPlotRect]、8 个等值样本（值 = [value]）。 */
    private fun buildAaProbe(value: Double, type: ChartType): Chart {
        val data = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (AA_POINTS - 1).toDouble(), "样本", ""),
                AxisRange(0.0, 1.0, "值", "")
            ),
            arrayOf(
                DoubleArray(AA_POINTS) { it.toDouble() },
                DoubleArray(AA_POINTS) { value }
            )
        )
        val xAxis = Axis(AxisType.LINEAR, data.axisRange(0))
            .setDisplayLength(AA_PLOT_W.toDouble())
            .setWindow(0.0, (AA_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, data.axisRange(1))
            .setDisplayLength(AA_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("AA").add(aaSeries(data, type))
        return chart
    }

    /**
     * 折线探针：**两条系列、两个相位**（同一条几何的两种相位，见文件上方那段推导）。
     *
     * <p>两条都在同一张图、同一层里，于是**一帧里的两条线必然用同一个 AA 开关**——
     * 这正是"两次绘制只差一个 uniform"的写法：关与开是两帧，其余一切逐项相同。
     */
    private fun buildAaLineProbe(): Chart {
        val dataA = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (AA_POINTS - 1).toDouble(), "样本", ""),
                AxisRange(0.0, 1.0, "值", "")
            ),
            arrayOf(DoubleArray(AA_POINTS) { it.toDouble() }, DoubleArray(AA_POINTS) { AA_VALUE_A })
        )
        val dataB = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (AA_POINTS - 1).toDouble(), "样本", ""),
                AxisRange(0.0, 1.0, "值", "")
            ),
            arrayOf(DoubleArray(AA_POINTS) { it.toDouble() }, DoubleArray(AA_POINTS) { AA_VALUE_B })
        )
        val xAxis = Axis(AxisType.LINEAR, dataA.axisRange(0))
            .setDisplayLength(AA_PLOT_W.toDouble())
            .setWindow(0.0, (AA_POINTS - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, dataA.axisRange(1))
            .setDisplayLength(AA_PLOT_H.toDouble())
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("A").add(aaSeries(dataA, ChartType.LINE))
        chart.addLayer("B").add(aaSeries(dataB, ChartType.LINE))
        return chart
    }

    private val aaLines = buildAaLineProbe()
    private val aaStep = buildAaProbe(AA_VALUE_A, ChartType.STEP)
    private val aaArea = buildAaProbe(AA_VALUE_A, ChartType.AREA)
    private val aaBar = buildAaProbe(AA_VALUE_A, ChartType.BAR)
    private val aaScatter = buildAaProbe(AA_VALUE_A, ChartType.SCATTER)

    /** 本帧要画的探针图；不是探针帧时返回 null。 */
    private fun aaProbeChart(n: Int): Chart? = when (n) {
        AA_LINE_OFF, AA_LINE_ON -> aaLines
        AA_STEP_OFF, AA_STEP_ON -> aaStep
        AA_AREA_OFF, AA_AREA_ON -> aaArea
        AA_BAR_OFF, AA_BAR_ON -> aaBar
        AA_SCATTER_OFF, AA_SCATTER_ON -> aaScatter
        // 频谱直接复用观察期那张图（它的环在帧 0 就写满了一整环，
        // 而这两帧落在第二幕里，谱是干净的单频）。同一张图**换一块绘图区**画第二遍。
        AA_SPECTRUM_OFF, AA_SPECTRUM_ON -> spectrumChart
        else -> null
    }

    /**
     * 画本帧的 AA 探针（不是探针帧时什么都不做）。
     *
     * <h2>黑底必须走 Gc 的批量，而且必须在 flush 之前</h2>
     * <p>{@code gc.fillRect} 只是把顶点写进批处理缓冲，而批要等 {@code endFrame}
     * 才提交——所以"先 fillRect、再 flush、最后 charts.draw"这个顺序不能动：
     * 少了那个 flush，黑底会盖在数据系列**上面**（与主场景里那个标注矩形同一条规矩，
     * 见 [LABEL_X]）。这里多出来的这一次 flush 是安全的：此刻批里只有这一块黑底
     * （布局底色与移动方块早在主场景那一次 flush 里落定了）。
     *
     * <p>开关按帧取：**关与开用的是同一张图、同一块绘图区**，唯一变化的是
     * {@code gc.antialias}——它在 draw 的入口被取一次快照（见 {@code ChartRenderer}）。
     */
    private fun drawAaProbe(gc: Gc, n: Int) {
        val chart = aaProbeChart(n) ?: return
        gc.fill = AA_BG_RGB or (0xFF shl 24)
        gc.fillRect(AA_PLOT_X, AA_PLOT_Y, AA_PLOT_W, AA_PLOT_H)
        gc.flush()
        gc.antialias = n in AA_ON_FRAMES
        gc.charts.draw(chart, aaPlotRect, gc.width, gc.height)
        // 还回去：本帧后面还有别的绘制（标注矩形），它们不该带着探针的开关。
        gc.antialias = false
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
        if (n >= RECLAIM_START && n < RECLAIM_END) {
            // 回收实验：**只在回收期画**（理由与其它实验图相同——校验帧的画面上
            // 不许有它们的颜色，见"画面恰好只有这 7 种颜色"那条断言）。
            // 上界是 RECLAIM_END 而**不是** TOTAL_FRAMES：校验帧回读到的是
            // 绘制帧 TOTAL_FRAMES − 1，上界写成 TOTAL_FRAMES 的话探针会留在
            // 那张画面里（第一版就是这么错的，实测多出 336 个白像素）。
            drawReclaimProbes(gc, n)
        }
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
            // 图型实验的四张图（柱状 / 面积 ×2 / 阶梯），同样只在观察期画（同样的理由）。
            drawKindCharts(gc)
            // 装配实验图（标题 / 图例 / 外边距），同样只在观察期画。
            drawDecorChart(gc)
            // 平滑曲线实验的七幕，同样只在观察期画（理由同上）。
            drawSmoothCharts(gc)
            // 左/右图例、底部标题、带子边界、轴标题：四个变体按帧轮换，同样只在观察期画。
            drawOverflowChart(gc, n)
            // Task 5 的 AA 探针：六个图型各两帧（关 / 开），同样只在观察期画。
            drawAaProbe(gc, n)
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
     * 图型实验的四张图：柱状（两个系列并排）、面积（斜填充，线宽 0）、
     * 面积（水平填充 + 轮廓线）、阶梯（末尾一个 NaN）。
     *
     * <p>四张图**不画底色**：它们下面就是 `glClear` 的背景色 0x333333，
     * 于是"面积填充的半透明混合"这条断言的底色是一个已知且唯一的量
     * （画了底色的话，混合结果取决于底色与图元谁先谁后，而那个顺序在断言里看不见）。
     *
     * <p>它们**每帧都画**（与散点/退化散点那两张一样），只在观察期里：
     * 观察期最后一帧抓快照，校验帧的画面上没有它们——"画面恰好只有这 7 种颜色"
     * 那条既有断言因此一字未改。
     */
    private fun drawKindCharts(gc: Gc) {
        // 第 [DECOR_IDENTITY_FRAME] 帧换一条路径画**同一张柱状图**：走 drawChart
        // （过 ChartLayout），而这张图上没有标题、图例与内边距。两条路径逐像素相同
        // 是那条身份断言的判别式，见 [barViaLayoutSnapshot]。
        if (frame == DECOR_IDENTITY_FRAME) {
            gc.charts.drawChart(barChart, barRect, gc.width, gc.height)
        } else {
            gc.charts.draw(barChart, barRect, gc.width, gc.height)
        }
        gc.charts.draw(areaSlopeChart, areaSlopeRect, gc.width, gc.height)
        gc.charts.draw(areaLineChart, areaLineRect, gc.width, gc.height)
        gc.charts.draw(stepChart, stepRect, gc.width, gc.height)
    }

    /**
     * 平滑曲线实验的七幕。
     *
     * <p><b>为什么按帧轮换。</b>每一幕都要"同一块矩形"才比得出来（差异只能来自开关），
     * 而一块矩形一次只装得下一幕——与左/右图例那五个变体是同一种排布。
     *
     * <p><b>七幕是怎么排的。</b>前四幕是两对"不平滑 / 平滑"：曲线一对、缺口一对；
     * 第 5 幕换回第 1 幕<b>那个 Series 对象</b>、把它的开关改成 true（于是缓冲必须重建）；
     * 最后两幕是面积图的一对（顶边那条曲线）。
     *
     * <p>每一幕的快照都在它**最后那一帧**抓（见 [captureObservations]）——
     * 快照必须是"那一帧刚画完"时取的，事后补不回来。
     */
    private fun drawSmoothCharts(gc: Gc) {
        when {
            frame < SMOOTH_CURVE_PLAIN_END ->
                gc.charts.draw(smoothCurvePlainChart, smoothCurveRect, gc.width, gc.height)

            frame < SMOOTH_CURVE_SMOOTH_END ->
                gc.charts.draw(smoothCurveSmoothChart, smoothCurveRect, gc.width, gc.height)

            frame < SMOOTH_GAP_PLAIN_END ->
                gc.charts.draw(smoothGapPlainChart, smoothGapRect, gc.width, gc.height)

            frame < SMOOTH_GAP_SMOOTH_END ->
                gc.charts.draw(smoothGapSmoothChart, smoothGapRect, gc.width, gc.height)

            frame < SMOOTH_TOGGLED_END -> {
                // 第 5 幕的第一帧改开关。**改的是已经画过十几帧的那个 Series 对象**，
                // 所以缓冲的物理布局（前面有没有留一个 float）与现状不符——
                // ChartRenderer 会因此重建它（见那边的 bufferFor）。
                // 这一改若被静默忽略，属性偏移与 uSmooth 就会各说各话：
                // 曲线弯向别的样本，而画面"只是一条形状略有出入的曲线"。
                if (frame == SMOOTH_GAP_SMOOTH_END) {
                    smoothCurvePlainSeries.smooth(true)
                }
                gc.charts.draw(smoothCurvePlainChart, smoothCurveRect, gc.width, gc.height)
            }

            // 第 6、7 幕：跨环绕的一对（**换一块矩形**画既有跨环绕实验的数据）。
            frame < SMOOTH_WRAP_PLAIN_END ->
                gc.charts.draw(smoothWrapPlainChart, smoothGapRect, gc.width, gc.height)

            frame < SMOOTH_WRAP_SMOOTH_END ->
                gc.charts.draw(smoothWrapSmoothChart, smoothGapRect, gc.width, gc.height)

            frame < SMOOTH_AREA_PLAIN_END ->
                gc.charts.draw(smoothAreaPlainChart, smoothCurveRect, gc.width, gc.height)

            frame < SMOOTH_AREA_SMOOTH_END ->
                gc.charts.draw(smoothAreaSmoothChart, smoothCurveRect, gc.width, gc.height)
        }
    }

    /**
     * 装配实验图：**只有它是走 `drawChart` 的常客**（其余都走低层的 `draw`）。
     *
     * <p>它每帧都画，参数一字不改——这里要验的是布局算出来的带子与画面是否一致，
     * 逐帧变化只会让快照多几种解释（跨帧那件事由别的实验图负责）。
     */
    private fun drawDecorChart(gc: Gc) {
        gc.charts.drawChart(decorChart, decorRect, gc.width, gc.height)
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
            // 图型实验的四张图也在这一帧上取样（同样只在观察期画）。
            barSnapshot = grab(h, barRect)
            areaSlopeSnapshot = grab(h, areaSlopeRect)
            areaLineSnapshot = grab(h, areaLineRect)
            stepSnapshot = grab(h, stepRect)
            captureKindPicks(bridge)
            // 装配实验图也在这一帧上取样。
            decorSnapshot = grab(h, decorRect)
        }
        // 左/右图例那一组：四个变体各自在自己那一段的**最后一帧**上取样
        // （那时画面上正是它，下一变体一画就把这块矩形整个换掉）。
        captureOverflow(bridge, h)
        // 身份断言的两张快照：第 [DECOR_IDENTITY_FRAME] 帧走 drawChart、
        // 下一帧走 draw（后者就是上面 STREAM_FRAMES 那一帧抓的 barSnapshot）。
        if (frame == DECOR_IDENTITY_FRAME + 1) {
            barViaLayoutSnapshot = grab(h, barRect)
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
        // Task 5 的 AA 探针：六个图型各抓关、开两张（`frame` 是"已完成帧数"，
        // 所以 `frame == AA_X_OFF + 1` 抓到的正是绘制帧 `AA_X_OFF` 的画面）。
        // 也只在观察期画（理由同上："画面只有这 7 种颜色"那条断言不许改弱）。
        if (frame == AA_LINE_OFF + 1) aaLinesOff = grab(h, aaGrabRect)
        if (frame == AA_LINE_ON + 1) aaLinesOn = grab(h, aaGrabRect)
        if (frame == AA_STEP_OFF + 1) aaStepOff = grab(h, aaGrabRect)
        if (frame == AA_STEP_ON + 1) aaStepOn = grab(h, aaGrabRect)
        if (frame == AA_AREA_OFF + 1) aaAreaOff = grab(h, aaGrabRect)
        if (frame == AA_AREA_ON + 1) aaAreaOn = grab(h, aaGrabRect)
        if (frame == AA_BAR_OFF + 1) aaBarOff = grab(h, aaGrabRect)
        if (frame == AA_BAR_ON + 1) aaBarOn = grab(h, aaGrabRect)
        if (frame == AA_SCATTER_OFF + 1) aaScatterOff = grab(h, aaGrabRect)
        if (frame == AA_SCATTER_ON + 1) aaScatterOn = grab(h, aaGrabRect)
        if (frame == AA_SPECTRUM_OFF + 1) aaSpectrumOff = grab(h, aaGrabRect)
        if (frame == AA_SPECTRUM_ON + 1) aaSpectrumOn = grab(h, aaGrabRect)
        // 平滑曲线实验的七幕：各自在**自己那一幕的最后一帧**抓一张
        // （`frame` 是"已完成帧数"，所以 `frame == 14` 抓到的正是绘制帧 13 的画面）。
        // 它们也只在观察期画（理由同上："画面只有这 7 种颜色"那条断言不许改弱）。
        if (frame == SMOOTH_CURVE_PLAIN_END) smoothCurvePlainShot = grab(h, smoothCurveRect)
        if (frame == SMOOTH_CURVE_SMOOTH_END) smoothCurveSmoothShot = grab(h, smoothCurveRect)
        if (frame == SMOOTH_GAP_PLAIN_END) smoothGapPlainShot = grab(h, smoothGapRect)
        if (frame == SMOOTH_GAP_SMOOTH_END) smoothGapSmoothShot = grab(h, smoothGapRect)
        if (frame == SMOOTH_TOGGLED_END) smoothToggledShot = grab(h, smoothCurveRect)
        if (frame == SMOOTH_WRAP_PLAIN_END) smoothWrapPlainShot = grab(h, smoothGapRect)
        if (frame == SMOOTH_WRAP_SMOOTH_END) smoothWrapSmoothShot = grab(h, smoothGapRect)
        if (frame == SMOOTH_AREA_PLAIN_END) smoothAreaPlainShot = grab(h, smoothCurveRect)
        if (frame == SMOOTH_AREA_SMOOTH_END) smoothAreaSmoothShot = grab(h, smoothCurveRect)
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
     * 在图型实验的四张图还画着的那一帧，把五个探测点的拾取结果记下来
     * （理由同 [capturePickProbes]：那四张图只在观察期画）。
     *
     * <p>坐标都是设备像素（用户坐标 1:1）：柱状图在局部列 [2,10) 与 [14,22)
     * 各有一根柱，探针取**柱内偏边**的位置（局部 x = 5 与 15，而样本中心在 12），
     * 因为"忘了柱心偏移"的 ID pass 会在样本中心周围盖一个半宽 4px 的方热区
     * （覆盖局部 x ∈ [8,16]）——柱中心那一点照样命中，只有偏边的这一点能分开。
     */
    private fun captureKindPicks(bridge: FXGLTransfer) {
        val gc = bridge.gc() ?: return
        kindPickBarSlotA = gc.pick(5f, BAR_PLOT_Y + 56f)?.payload() === barSeriesA
        kindPickBarSlotB = gc.pick(15f, BAR_PLOT_Y + 56f)?.payload() === barSeriesB
        // 下沿（局部行 64）以下：那里一根柱都没有，所以必须落空。
        kindPickBarBelow = gc.pick(5f, BAR_PLOT_Y + 80f) == null
        // 面积填充的深处（离曲线 40px）：只有填充自己的 ID pass 能命中，
        // 轮廓线那条路径（线宽 0，热区 4px）离得太远，够不着。
        kindPickAreaFill = gc.pick(95f, AREA_PLOT_Y + 40f)?.payload() === areaSlopeSeries
        // 阶梯的竖段上一点。
        kindPickStepRiser = gc.pick(32f, STEP_PLOT_Y + 40f)?.payload() === stepSeries
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
    // -----------------------------------------------------------------------
    // Task 5：AA 探针的读数与判定
    // -----------------------------------------------------------------------

    /**
     * 一块区域里的"抗锯齿三件套"读数。
     *
     * <p>三者都由"**背景纯黑、墨色是一个不透明的已知值**"这条约定定义：
     * **过渡像素 = 既非背景色也非墨色**的那些（AA 关时它必须恒为 0——硬边没有中间值）。
     * 墨色取纯白时红通道就是"覆盖率 × 255"，墨量可以逐项手算（折线那四条判据用的就是它）。
     *
     * @param fringe 过渡像素数
     * @param pure   纯墨色像素数
     * @param ink    墨量（红通道之和；只有墨色是纯白时它才等于"覆盖率之和 × 255"）
     */
    private class AaStats(val fringe: Int, val pure: Int, val ink: Int)

    /**
     * 取一块区域的读数；坐标是 [aaGrabRect] 的**局部**坐标。
     *
     * <p>[inkRgb] 是这一张图里系列的**实际颜色**，不是恒定的纯白：
     * 频谱探针复用观察期那张图，而它的系列色是紫色。写成"恒等于纯白"会让
     * **AA 关**那一帧的每一个墨迹像素都算成"过渡像素"，① 于是恒假——
     * 而那个失败看起来像"AA 关的时候也有羽化"，归因全错。
     */
    private fun aaStats(s: Shot, x0: Int, x1: Int, y0: Int, y1: Int,
                        inkRgb: Int = AA_INK_RGB): AaStats {
        // 快照的像素是**不含 alpha 的 RGB**（与全帧回读同一个口径），而调用方手上
        // 通常是 ARGB 常量 ⇒ 这里统一去掉 alpha 那一字节。不去的话比较恒不成立
        // （表现为"纯墨色 0 个、过渡 390 个"，看起来像"关着也在羽化"）。
        val inkRgbRgb = inkRgb and 0xFFFFFF
        var fringe = 0
        var pure = 0
        var ink = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val rgb = s.at(x, y)
                when (rgb) {
                    inkRgbRgb -> {
                        pure++
                        ink += (rgb shr 16) and 0xFF
                    }
                    // 背景的红通道就是 0，什么都不用加
                    AA_BG_RGB -> Unit
                    else -> {
                        fringe++
                        ink += (rgb shr 16) and 0xFF
                    }
                }
            }
        }
        return AaStats(fringe, pure, ink)
    }

    /** 逐行的读数（失败时"过渡像素落在哪一行"必须是可读的，不能只有一个总数）。 */
    private fun aaRowProfile(s: Shot, x0: Int, x1: Int, y0: Int, y1: Int): String =
        (y0 until y1).joinToString("  ") { y ->
            val st = aaStats(s, x0, x1, y, y + 1)
            "r$y(设备${y + AA_GRAB_Y}):纯${st.pure} 过${st.fringe} 墨${st.ink}"
        }

    /**
     * ★ 图表系列的解析式 AA：**六个图型各一对快照**（关 / 开），折线那一对按四条判据判。
     *
     * <p>几何与相位的推导在文件上方那段长注释里；这里只写"判据为什么长这样"。
     *
     * <h2>另外五个图型只判 ① ②（关时无过渡、开时有），折线按四条判</h2>
     * <p>①② 是**接线判据**：每一个渲染器都必须真的把 {@code uAntialias} 传下去，
     * 而"漏掉一个"在画面上只表现为"那一种图型没有 AA"——混在图里几乎注意不到。
     * 断言的量（过渡像素的有无）**恰好就是那个 uniform 的效果本身**，
     * 所以删掉任何一个渲染器里那一行，它对应那条 ② 就倒（六个逐一实测过）。
     *
     * <p>折线那一对另外按 ③④ 判（线心纯色数、墨量对解析值）——那两条需要**相位可控**
     * 的几何，而水平线是唯一能精确手算的（见那段推导）。其余图型的边缘位置由
     * 柱宽/标记半径/FFT 幅值决定，写不出精确的解析值，硬写出来的只会是"猜一个容差"。
     *
     * <p><b>AREA 的窗口只取基线那一段——这是"读数可归因"，不是"必要"</b>：面积图有
     * **两样东西**带边界（填充自己的上下沿 + 那条轮廓线），而轮廓线走的是折线渲染器、
     * 有自己的 uAntialias。窗口取**基线**那一段之后，轮廓线够不到那里，于是 ② 里出现的
     * 过渡像素**只可能来自填充渲染器**（实测：删掉 AreaSeriesRenderer 那一行，恰好只倒
     * 这一条）。
     *
     * <p>⚠️ **别把"窄窗口"写成必要条件**（我原来在这里写过"窗口取整个绘图区就会
     * 让变异活下来"，**实测证伪**）：轮廓线的覆盖率叠在**已经满覆盖的白**上**看不见**
     * ——探针的填充是**不透明**白（{@code fillAlpha(1f)}，那是 ① 成立的必要条件），
     * 轮廓线同色，{@code 0.75 × 255 + 0.25 × 255 = 255}。所以宽窗口下那一条 ②
     * 照样会倒（实测：窗口改成 [0, 22) 且删掉那一行，仍然恰好 1 条倒）。
     * 窄窗口的价值是**归因**：失败时能直接说"是填充渲染器没接线"。
     */
    private fun reportSeriesAntialias(w: Int, h: Int, report: (String, Boolean, String) -> Unit) {
        println("\n-- ★ 图表系列的解析式抗锯齿：六个图型的 uAntialias + 折线的四条判据 --")

        // ---- 前置：回读窗与相位（**先决条件，不是自由参数**）----
        //
        // 窗在帧缓冲最底下那一条。越界时读数会**全是背景色**——而"全是背景"
        // 看起来像"AA 没生效"，归因就反了，所以它必须是第一条断言。
        report("★ AA 前置：回读窗整块在帧缓冲里（x ∈ [${AA_PLOT_X.toInt()}, ${(AA_PLOT_X + AA_PLOT_W).toInt()})、" +
                "y ∈ [$AA_GRAB_Y, ${AA_GRAB_Y + AA_PLOT_H.toInt()})）",
            w >= (AA_PLOT_X + AA_PLOT_W).toInt() && h >= AA_GRAB_Y + AA_PLOT_H.toInt(),
            "帧缓冲 ${w}x$h")

        // 相位：由带心（= AA_PLOT_Y + (1 − v)·AA_PLOT_H）推出"哪些行被光栅化"，
        // 再与上面那批**硬编码**的行号常量比。两者不一致说明有人动了绘图区或数值，
        // 而判据还按旧相位在数——那时它会**继续通过**，但它守的东西已经不在了。
        // 判据：像素中心落在带内（|q| < b）的那些行；q = 行中心 − 带心。
        val centerA = AA_PLOT_Y + (1f - AA_VALUE_A.toFloat()) * AA_PLOT_H
        val centerB = AA_PLOT_Y + (1f - AA_VALUE_B.toFloat()) * AA_PLOT_H
        fun bandRows(center: Float): List<Int> =
            (0 until aaGrabRect.height.toInt()).filter {
                abs((AA_GRAB_Y + it + 0.5f) - center) < AA_LINE_WIDTH * 0.5f
            }
        fun nearestEdgeGap(center: Float): Float =
            (0 until aaGrabRect.height.toInt()).minOfOrNull {
                abs(abs((AA_GRAB_Y + it + 0.5f) - center) - AA_LINE_WIDTH * 0.5f)
            } ?: 0f
        val rowsA = bandRows(centerA)
        val rowsB = bandRows(centerB)
        report("★ AA 前置：相位 A 的带心 = 723.25、被光栅化的行 = [722, 723, 724]（局部 [10, 11, 12]）",
            rowsA == listOf(AA_FRINGE_ROW_A - 2, AA_FRINGE_ROW_A - 1, AA_FRINGE_ROW_A),
            "实测带心 $centerA、行 $rowsA（局部；设备行 ${rowsA.map { it + AA_GRAB_Y }}）" +
                    "，期望 [${AA_FRINGE_ROW_A - 2}, ${AA_FRINGE_ROW_A - 1}, $AA_FRINGE_ROW_A]" +
                    " ——不一致说明 AA_PLOT_Y / AA_VALUE_A / 线宽被动过，**四条判据的行号要重算**")
        report("★ AA 前置：相位 B 的带心 = 728.75、被光栅化的行 = [727, 728, 729]（局部 [15, 16, 17]）",
            rowsB == listOf(AA_FRINGE_ROW_B, AA_FRINGE_ROW_B + 1, AA_FRINGE_ROW_B + 2),
            "实测带心 $centerB、行 $rowsB（局部；设备行 ${rowsB.map { it + AA_GRAB_Y }}）" +
                    "，期望 [$AA_FRINGE_ROW_B, ${AA_FRINGE_ROW_B + 1}, ${AA_FRINGE_ROW_B + 2}]" +
                    " ——同上前提")
        // 没有像素中心正好压在带边缘上（tie 会让光栅化的取舍规则参与进来，
        // 而那个规则不在我们的控制里）。实测余量 0.25 px。
        val gapA = nearestEdgeGap(centerA)
        val gapB = nearestEdgeGap(centerB)
        report("★ AA 前置：没有像素中心落在带边缘上（tie 会让取舍规则进到读数里）",
            gapA > 0.2f && gapB > 0.2f,
            "最近的像素中心到带边缘 相位A ${"%.3f".format(gapA)} px、相位B ${"%.3f".format(gapB)} px")

        val lineOff = aaLinesOff
        val lineOn = aaLinesOn
        if (lineOff == null || lineOn == null) {
            report("★ AA 折线：关 / 开两张快照都在", false,
                "关=${lineOff != null}，开=${lineOn != null}")
            return
        }

        println("  折线窗 x∈[$AA_X0, $AA_X1)（局部；设备 x∈[${AA_X0 + AA_PLOT_X.toInt()}, " +
                "${AA_X1 + AA_PLOT_X.toInt()})）")
        println("  相位 A 行 $AA_ROWS_A0..${AA_ROWS_A1 - 1}（设备 ${AA_ROWS_A0 + AA_GRAB_Y}.." +
                "${AA_ROWS_A1 - 1 + AA_GRAB_Y}）  AA 关：${aaRowProfile(lineOff, AA_X0, AA_X1, AA_ROWS_A0, AA_ROWS_A1)}")
        println("  相位 A 行 $AA_ROWS_A0..${AA_ROWS_A1 - 1}  AA 开：${aaRowProfile(lineOn, AA_X0, AA_X1, AA_ROWS_A0, AA_ROWS_A1)}")
        println("  相位 B 行 $AA_ROWS_B0..${AA_ROWS_B1 - 1}（设备 ${AA_ROWS_B0 + AA_GRAB_Y}.." +
                "${AA_ROWS_B1 - 1 + AA_GRAB_Y}）  AA 关：${aaRowProfile(lineOff, AA_X0, AA_X1, AA_ROWS_B0, AA_ROWS_B1)}")
        println("  相位 B 行 $AA_ROWS_B0..${AA_ROWS_B1 - 1}  AA 开：${aaRowProfile(lineOn, AA_X0, AA_X1, AA_ROWS_B0, AA_ROWS_B1)}")

        val offA = aaStats(lineOff, AA_X0, AA_X1, AA_ROWS_A0, AA_ROWS_A1)
        val onA = aaStats(lineOn, AA_X0, AA_X1, AA_ROWS_A0, AA_ROWS_A1)
        val offB = aaStats(lineOff, AA_X0, AA_X1, AA_ROWS_B0, AA_ROWS_B1)
        val onB = aaStats(lineOn, AA_X0, AA_X1, AA_ROWS_B0, AA_ROWS_B1)
        // 线心那两行：相位 A 是局部 10、11；相位 B 是局部 16、17（覆盖率恒为 1）。
        val coreOffA = aaStats(lineOff, AA_X0, AA_X1, AA_CORE_ROWS_A.first, AA_CORE_ROWS_A.last + 1).pure
        val coreOnA = aaStats(lineOn, AA_X0, AA_X1, AA_CORE_ROWS_A.first, AA_CORE_ROWS_A.last + 1).pure
        val coreOffB = aaStats(lineOff, AA_X0, AA_X1, AA_CORE_ROWS_B.first, AA_CORE_ROWS_B.last + 1).pure
        val coreOnB = aaStats(lineOn, AA_X0, AA_X1, AA_CORE_ROWS_B.first, AA_CORE_ROWS_B.last + 1).pure

        // ① AA 关：一条过渡像素都不许有（硬边没有中间值）。
        report("★ AA 折线① AA 关时两条相位都没有过渡像素",
            offA.fringe == 0 && offB.fringe == 0,
            "相位A ${offA.fringe} 个、相位B ${offB.fringe} 个（都期望 0）")
        // ② AA 开：每列恰好 1 个过渡像素（带心取的是 k+0.25 / k+0.75 两档，
        // 带内三行的覆盖率是 1 / 1 / 0.75 ⇒ 每列**只有一个外缘行**是部分覆盖的）。
        // 两条相位都要：`abs(vEdge) → vEdge` 那条变异把斜坡变成单侧的，
        // 相位 A 照过、相位 B 才倒（见文件上方那段推导）。
        report("★ AA 折线② AA 开时两条相位各有 $AA_W 个过渡像素（每列 1 个，相位 A 在下缘、相位 B 在上缘）",
            onA.fringe == AA_W && onB.fringe == AA_W,
            "相位A ${onA.fringe} 个、相位B ${onB.fringe} 个，期望各 $AA_W" +
                    "（相位 A 的过渡行是局部 $AA_FRINGE_ROW_A＝设备 ${AA_FRINGE_ROW_A + AA_GRAB_Y}，" +
                    "相位 B 是局部 $AA_FRINGE_ROW_B＝设备 ${AA_FRINGE_ROW_B + AA_GRAB_Y}）")
        // ③ 线心（覆盖率恒为 1 的那两行）的纯色像素数两模式**精确相等**，且 = 2W。
        // 写成"总纯色数相等"是一条**恒假**断言：AA 开时最外那一圈本来就会变成过渡像素，
        // 总数必然略减（关 3W、开 2W）。线心离边缘足够远，两模式都是满覆盖。
        report("★ AA 折线③ 线心（2 行）纯色像素数两模式精确相等、且 = ${2 * AA_W}",
            coreOffA == coreOnA && coreOnA == 2 * AA_W &&
                    coreOffB == coreOnB && coreOnB == 2 * AA_W,
            "相位A 关=$coreOffA 开=$coreOnA；相位B 关=$coreOffB 开=$coreOnB，期望各 ${2 * AA_W}")
        // ④ 墨量对**解析值** 2.75 px/列 × 亮度差 255 × W（推导见文件上方那段：
        //    带的全宽 3 减去"斜坡在外侧被切掉的那 0.25"——图表这条路径几何不外扩，
        //    与 Gc 的双向外扩不同；**这是声明过的降级**，与"压缩轴外缘之外的羽化被切掉"并列）。
        // ⚠️ 参照系**不是**"AA 关的那条"：硬边在这个几何上恰好是 3.0 px/列，
        //    比 AA 开**多** 8.3%——写成"两者相等"会是恒假断言（PipelineVerifier 的学费）。
        //
        // ⚠️ **容差只有一侧被实测过，照实说**：解析偏离实测 **−25**（= 每列 −0.25，因为
        //    0.75 × 255 = 191.25 被驱动**向下**取整），可解释；但"驱动反向取整"（+0.25/列
        //    ⇒ +25 ⇒ 70150）这一侧**本机没有出现过**，界 `0.5W + 2 = 52` 对它只是**推导值**
        //    （每列 1 个过渡像素 × 8 位量化界 0.5）。**别把这个界读成余量**：
        //    它挡的是"覆盖率整体没生效/斜坡宽度写错"那一类——实测变异（把折线的
        //    `uAntialias` 恒传 `0f`）墨量变成硬边的 76500，偏离 **+6375 ≫ 52**。
        val analytic = 2.75 * AA_INK_LUMA * AA_W
        val tol = 0.5 * AA_W + 2
        report("★ AA 折线④ 墨量 == 解析值（2.75 px/列 × 亮度差 255 × $AA_W = ${"%.0f".format(analytic)}）",
            abs(onA.ink - analytic) <= tol && abs(onB.ink - analytic) <= tol,
            "相位A 实测 ${onA.ink}、相位B 实测 ${onB.ink}，解析 ${"%.0f".format(analytic)}，" +
                    "允许 ±${"%.0f".format(tol)}（每列 1 个过渡像素 × 8 位量化界 0.5）")
        // ④ 对照：AA 关的墨量必须**精确**等于 3 px/列 × 255 × W。
        // 它钉的是"关着的时候几何没有被外扩"——外扩一行就会多出 W × 255。
        report("★ AA 折线④ 对照：AA 关的墨量 == 3 px/列 × 亮度差 255 × $AA_W（硬边几何没有被外扩）",
            offA.ink == 3 * AA_INK_LUMA * AA_W && offB.ink == 3 * AA_INK_LUMA * AA_W,
            "相位A 实测 ${offA.ink}、相位B 实测 ${offB.ink}，期望各 ${3 * AA_INK_LUMA * AA_W}")

        // ---- 其余五个图型：接线判据（关时无过渡 / 开时有）----
        //
        // 窗口按各自几何取，**列取整块绘图区**（这几条的窗口只界定行：
        // 柱的左右两条竖边、标记的四条边都可能落在窗内的任何一列上，
        // 只取 100 列会把 8 个标记里的 6 个切到窗外，读数小得看不出问题）：
        //   · AREA 只取**基线**那一段——曲线那一段的羽化有一半来自轮廓线，
        //     而轮廓线走折线路径、有自己的 uAntialias；混在一起就分不出是哪个渲染器的。
        //   · 频谱复用观察期那张图，系列色是紫色 ⇒ 读数要带上它自己的墨色（见 aaStats）。
        //   · STEP 探针的 8 个样本**全等** ⇒ 阶梯是平的、**没有拐角**。所以这条 ② 证的是
        //     "uAntialias 到了 STEP 那个程序"，**证不了拐角**（拐角处 `n = sn / dot(sn,n1)`
        //     把带撑长、斜坡跟着变宽，见 SeriesShaders 的 LINE_FRAGMENT KDoc 里那第三条限定）。
        //     亚像素级，不另加判据。
        aaWiring(report, "阶梯 STEP", aaStepOff, aaStepOn, AA_ROWS_A0, AA_ROWS_A1)
        aaWiring(report, "面积 AREA（基线那一段，只可能来自填充）", aaAreaOff, aaAreaOn, 14, 19)
        aaWiring(report, "柱状 BAR", aaBarOff, aaBarOn, 8, 19)
        aaWiring(report, "散点 SCATTER", aaScatterOff, aaScatterOn, 8, 14)
        aaWiring(report, "频谱 SPECTRUM", aaSpectrumOff, aaSpectrumOn, 0, AA_PLOT_H.toInt(),
            spectrumArgb)

        // 交叉：**平坦的阶梯段画出来的就是一条普通水平线**——同一块绘图区、同一个值、
        // 同一个线宽，两帧逐项读数应当完全相同。它替"阶梯的拐角会不会把平坦段画歪"
        // 立一道闸门（那两个渲染器的顶点公式不同，值一样不代表几何一样）。
        val stepOff = aaStepOff
        val stepOn = aaStepOn
        if (stepOff != null) {
            val sOff = aaStats(stepOff, AA_X0, AA_X1, AA_ROWS_A0, AA_ROWS_A1)
            report("★ AA 交叉：平坦的阶梯与同一条水平线逐项读数相同（相位 A 那一段）",
                sOff.fringe == offA.fringe && sOff.pure == offA.pure && sOff.ink == offA.ink,
                "阶梯 过${sOff.fringe}/纯${sOff.pure}/墨${sOff.ink}，" +
                        "折线 过${offA.fringe}/纯${offA.pure}/墨${offA.ink}")
        }
        if (stepOn != null) {
            val sOn = aaStats(stepOn, AA_X0, AA_X1, AA_ROWS_A0, AA_ROWS_A1)
            report("★ AA 交叉：同上，AA 开的那一对",
                sOn.fringe == onA.fringe && sOn.pure == onA.pure && sOn.ink == onA.ink,
                "阶梯 过${sOn.fringe}/纯${sOn.pure}/墨${sOn.ink}，" +
                        "折线 过${onA.fringe}/纯${onA.pure}/墨${onA.ink}")
        }
    }

    /**
     * 一个图型的**接线判据**：AA 关的那一帧没有过渡像素、开的那一帧有。
     *
     * <p>这两条合起来才成立：只有 ② 会被"AA 永远是开的"骗过去，只有 ① 会被
     * "AA 永远没生效"骗过去（那时 ① 恒真）。
     */
    private fun aaWiring(report: (String, Boolean, String) -> Unit, label: String,
                         off: Shot?, on: Shot?, y0: Int, y1: Int,
                         inkRgb: Int = AA_INK_RGB) {
        if (off == null || on == null) {
            report("★ AA $label：关 / 开两张快照都在", false, "关=${off != null}，开=${on != null}")
            return
        }
        val a = aaStats(off, 0, AA_PLOT_W.toInt(), y0, y1, inkRgb)
        val b = aaStats(on, 0, AA_PLOT_W.toInt(), y0, y1, inkRgb)
        println("  $label 行 $y0..${y1 - 1}（设备 ${y0 + AA_GRAB_Y}..${y1 - 1 + AA_GRAB_Y}）、" +
                "全宽（设备 x ${AA_PLOT_X.toInt()}..${(AA_PLOT_X + AA_PLOT_W).toInt() - 1}），" +
                "墨色 #%06X".format(inkRgb))
        println("    关：过${a.fringe}/纯${a.pure}   开：过${b.fringe}/纯${b.pure}")
        report("★ AA $label ①：AA 关时没有过渡像素", a.fringe == 0,
            "实测 ${a.fringe} 个（期望 0；纯墨色 ${a.pure} 个）")
        report("★ AA $label ②：AA 开时有过渡像素（> 0）", b.fringe > 0,
            "实测 ${b.fringe} 个（期望 > 0；纯墨色 ${b.pure} 个）" +
                    "——0 说明这个渲染器没有把 uAntialias 传下去，" +
                    "而画面上只表现为\"这一种图型没有 AA\"")
    }

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

        println("=== Xuan 图表绘制后端像素校验 ===")
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

        // ---- 21. ★ 图型：柱状 / 面积 / 阶梯 ----
        //
        // 四张图各自守着一件"像素看不出来"的事：
        //   · 柱状：**并排分组**（同层多个柱状系列各占一格的一段）与**下沿是数值 0**
        //     ——不分组的话两组柱子完全重叠，画面上只剩最后画的那一个，看起来就是
        //     一张正常的单系列柱状图；
        //   · 面积（斜填充）：填充的**逐列**像素数与按定义算出来的参考逐列相符
        //     ——"该颜色有像素"对填反了、填到窗口下沿去了、只填了第一段全都成立；
        //   · 面积（水平 + 轮廓线）：轮廓线是**4 行整**的纯色，填充是半透明的一整片
        //     ——两者同色时"轮廓画了"与"没画"逐像素相同，所以填充必须能分辨出来；
        //   · 阶梯：踏步在正确的行、竖段在正确的列，**拐角的外角是补满的**
        //     ——两段四边形各自收尾会在外角缺一个半宽见方的角，那看起来像抗锯齿。
        println("\n-- ★ 图型：柱状 / 面积 / 阶梯 --")
        val bar = barSnapshot
        val areaSlope = areaSlopeSnapshot
        val areaLine = areaLineSnapshot
        val step = stepSnapshot
        if (bar == null || areaSlope == null || areaLine == null || step == null) {
            report("图型实验的四张快照都取到了（否则下面整节都是橡皮图章）", false,
                "bar=$bar areaSlope=$areaSlope areaLine=$areaLine step=$step")
        } else {
            // ---- 21a. 柱状：并排分组 + 下沿是数值 ----
            //
            // 几何全部手算：格宽 96/4 = 24，群宽 = 24×(1-1/6) = 20，柱宽 = 20/(2+1×0.5) = 8，
            // 柱心偏移 ∓6 → 左槽位（A）占局部列 [2,10) [26,34) [50,58) [74,82)，
            // 右槽位（B）占 [12,20) [36,44) [60,68) [84,92)。
            // y 窗口 [-0.5, 1] → 局部 y：值 0 → 64（下沿）、0.25 → 48、0.5 → 32、0.75 → 16、1.0 → 0。
            // 于是 A 的四根柱高 = 16/32/48/64、B 的三根（下标 2 是 NaN）= 64/64/_/64。
            val barHeightA = BAR_VALUES_A.sumOf { kindY(0.0) - kindY(it) }.toInt()
            // 每根柱 8 列宽（见上面的手算），高度之和 × 8 就是 A 的全部像素。
            val barPixelsA = 8 * BAR_VALUES_A.sumOf { (kindY(0.0) - kindY(it)).toInt() }
            val bottom = kindY(0.0).toInt()
            report("柱状：左槽位（系列 A）的像素数 = 8 列 × 四根柱高之和",
                bar.count(barSlotARgb) == barPixelsA,
                "实际 ${bar.count(barSlotARgb)} px，期望 $barPixelsA" +
                        "（柱高 ${BAR_VALUES_A.joinToString { (bottom - kindY(it)).toInt().toString() }}，" +
                        "下沿在局部行 $bottom）——数目对不上说明柱宽、分组、下沿或高度之一定错了")
            val barPixelsB = 3 * 8 * (bottom - 0)
            report("柱状：右槽位（系列 B）的像素数 = 8 列 × 三根满高柱（下标 2 是 NaN）",
                bar.count(barSlotBRgb) == barPixelsB,
                "实际 ${bar.count(barSlotBRgb)} px，期望 $barPixelsB")

            // ★ 分组的判别式：同一格（样本 0，局部列 2..21）里，左半是 A、右半是 B。
            // 不分组（两个系列都当第 0 根、总数 1）时两组柱完全重叠，B 后画、
            // 整格都是 B 的颜色——而画面看起来就是一张正常的单系列柱状图。
            report("★ 并排分组：同一格里左半是 A 的颜色", bar.at(5, 56) == barSlotARgb,
                "局部 (5,56) = #%06X，期望 #%06X".format(bar.at(5, 56), barSlotARgb) +
                        "——不是 A 说明两个柱状系列画在了同一个位置上（后画的盖住了先画的）")
            report("★ 并排分组：同一格里右半是 B 的颜色", bar.at(15, 56) == barSlotBRgb,
                "局部 (15,56) = #%06X，期望 #%06X".format(bar.at(15, 56), barSlotBRgb))
            // 与上面两条成对：柱高必须跟着数值走，而不是四根一样高。
            report("柱状：A 的第一根柱之上（值 0.25 的柱顶以上）没有墨迹",
                bar.at(5, 40) == background,
                "局部 (5,40) = #%06X，期望背景 #%06X".format(bar.at(5, 40), background) +
                        "——非背景说明柱高没跟着数值走（或下沿取的是窗口下界）")
            // 下沿以下整条带子：正确实现是 0（下沿在局部行 64），
            // 取窗口下界（-0.5 → 行 96）或取绘图区底边的话这里会有几千像素。
            val belowBaseline = bar.countIn(0, bottom + 1, KIND_PLOT_W - 1, KIND_PLOT_H - 1,
                barSlotARgb) + bar.countIn(0, bottom + 1, KIND_PLOT_W - 1, KIND_PLOT_H - 1,
                barSlotBRgb)
            report("柱状：下沿（局部行 $bottom）以下一个柱像素都没有——下沿是**数值 0**，" +
                    "不是窗口下界也不是绘图区底边",
                belowBaseline == 0,
                "行 ${bottom + 1}..${KIND_PLOT_H - 1} 里 $belowBaseline px，期望 0" +
                        "——非 0 说明 uBaseline 被当成「画到绘图区下边缘」了")
            // NaN 那一格：既不画柱，也不该退化成一根从下沿到 0 的柱。
            report("柱状：NaN 那一格（下标 $BAR_NAN_INDEX）没有柱（反证：它的邻居有）",
                bar.at(64, 30) == background && bar.at(40, 30) == barSlotBRgb,
                "NaN 那一格 (64,30) = #%06X（期望背景 #%06X）；邻居 (40,30) = #%06X（期望 #%06X）"
                    .format(bar.at(64, 30), background, bar.at(40, 30), barSlotBRgb) +
                        "——NaN 那一格要是有一根柱，那是凭空多出来的一根不存在的柱子")

            // ---- 21b. 面积（斜填充，线宽 0）：逐列对上参考 ----
            //
            // x 窗口 [0,3] → 每样本 32px；y 窗口 [-0.5,1] → 值 0 落在局部行 64（下沿）、
            // 值 1.0 落在行 0。四个值 0.25/0.5/0.75/1.0 于是对应行 48/32/16/0。
            val areaRef = areaFillReference(AREA_VALUES, 0.0)
            report("面积：填充的逐列像素数与按定义算出的参考完全一致（一共 $areaRef 列像素）",
                areaRef > 1000 && areaSlope.countNonBackgroundInRows(
                    0, KIND_PLOT_H - 1, background) == areaRef,
                "实际 ${areaSlope.countNonBackgroundInRows(0, KIND_PLOT_H - 1, background)} px，" +
                        "期望 $areaRef——逐列对不上说明边界（值→y 的映射、斜率、基线）有一处错了")
            report("面积：基线以下（局部行 ${bottom + 1}..）一个像素都没有——基线是**数值 0**",
                areaSlope.countNonBackgroundInRows(bottom + 1, KIND_PLOT_H - 1, background) == 0,
                "实际 ${areaSlope.countNonBackgroundInRows(bottom + 1, KIND_PLOT_H - 1, background)} px，" +
                        "期望 0——非 0 说明下沿取的是窗口下界或绘图区底边（两者都会把行 64 以下也填满）")
            report("面积：线宽 0 → 该颜色的**纯色**一个像素都没有（填充是半透明的）",
                areaSlope.count(areaSlopeRgb) == 0,
                "纯色 ${areaSlope.count(areaSlopeRgb)} px，期望 0——非 0 说明轮廓线没按线宽退化")
            // 默认 fillAlpha = 0.5：填充色必须是"主色与背景各一半"，而不是主色本身。
            // 这一条同时钉住了预乘混合（片段着色器输出 rgb×a，混合因子 GL_ONE/GL_ONE_MINUS_SRC_ALPHA）。
            val fillAt = areaSlope.at(95, 40)
            val wantFillR = blendChannel(background shr 16 and 0xFF, areaSlopeRgb shr 16 and 0xFF, 0.5)
            val wantFillG = blendChannel(background shr 8 and 0xFF, areaSlopeRgb shr 8 and 0xFF, 0.5)
            val wantFillB = blendChannel(background and 0xFF, areaSlopeRgb and 0xFF, 0.5)
            val wantFill = (wantFillR shl 16) or (wantFillG shl 8) or wantFillB
            report("面积：填充是**半透明**的（默认 fillAlpha = 0.5，与 JavaFX 的面积填充一致）",
                channelClose(fillAt, wantFill, 1),
                "局部 (95,40) = #%06X，期望 ≈ #%06X（主色 #%06X 与背景 #%06X 各一半）"
                    .format(fillAt, wantFill, areaSlopeRgb, background) +
                        "——等于主色说明 fillAlpha 被当成 1 了（那样轮廓线在画面上看不出存在过）")
            report("面积：填充不与主色、背景混淆（上一条的成对反证）",
                fillAt != areaSlopeRgb && fillAt != background,
                "局部 (95,40) = #%06X".format(fillAt))

            // ---- 21c. 面积（水平 + 轮廓线）：4 行纯色 + 46 行半透明 ----
            //
            // x 窗口 [0,1] → 两个点落在局部列 0 与 96；y 窗口 [0,1] → 值 0.5 落在局部行 48，
            // 下沿（数值 0）落在行 96 —— 正是绘图区底边，于是填充占行 48..95。
            // 线宽 4 → 轮廓线（折线路径画的）正好占行 46..49（中心 46.5..49.5）。
            val lineRows = AREA_LINE_WIDTH.toInt()
            report("面积轮廓线：纯色像素 = 线宽 × 宽度（一根 4 行高的水平线）",
                areaLine.count(areaLineRgb) == lineRows * KIND_PLOT_W,
                "实际 ${areaLine.count(areaLineRgb)} px，期望 ${lineRows * KIND_PLOT_W}" +
                        "——少了说明轮廓线没画（填充与它同色时画面上看不出来），" +
                        "多了说明线宽或位置不对")
            report("面积轮廓线：纯色只出现在**曲线那一行**的上下各 2px 里（局部行 46..49）",
                areaLine.countIn(0, 46, KIND_PLOT_W - 1, 49, areaLineRgb) ==
                        lineRows * KIND_PLOT_W &&
                        areaLine.countIn(0, 0, KIND_PLOT_W - 1, 45, areaLineRgb) == 0 &&
                        areaLine.countIn(0, 50, KIND_PLOT_W - 1, KIND_PLOT_H - 1,
                            areaLineRgb) == 0,
                "行 46..49 里 ${areaLine.countIn(0, 46, KIND_PLOT_W - 1, 49, areaLineRgb)} px、" +
                        "行 0..45 里 ${areaLine.countIn(0, 0, KIND_PLOT_W - 1, 45, areaLineRgb)} px、" +
                        "行 50.. 里 ${areaLine.countIn(0, 50, KIND_PLOT_W - 1, KIND_PLOT_H - 1, areaLineRgb)} px")
            report("面积填充：曲线之下（行 50..95）整片都是填充（46 行 × 96 列，" +
                    "与曲线之上那一片成对：那边 0 px、这边铺满）",
                areaLine.countNonBackgroundInRows(50, KIND_PLOT_H - 1, background) ==
                        46 * KIND_PLOT_W,
                "实际 ${areaLine.countNonBackgroundInRows(50, KIND_PLOT_H - 1, background)} px，" +
                        "期望 ${46 * KIND_PLOT_W}")
            report("面积填充：曲线之上（行 0..45）一个像素都没有",
                areaLine.countNonBackgroundInRows(0, 45, background) == 0,
                "实际 ${areaLine.countNonBackgroundInRows(0, 45, background)} px，期望 0" +
                        "——非 0 说明填充画到了曲线**上方**（基线当成绘图区顶边了）")

            // ---- 21d. 阶梯：踏步、竖段、拐角、NaN ----
            //
            // x 窗口 [0,3] → 每样本 32px；y 窗口 [0,1] → 值 0.25/0.75 落在局部行 72/24；
            // 线宽 4（半宽 2）→ 踏步占 4 行（70..73 / 22..25）、竖段占 4 列（30..33 / 62..65）。
            // 四个值 0.25 / 0.75 / 0.25 / NaN → 最后一段（下标 2→3）整段消失。
            report("阶梯：第一段先横（局部行 72 有踏步）", step.at(16, 72) == stepRgb,
                "局部 (16,72) = #%06X，期望 #%06X".format(step.at(16, 72), stepRgb))
            report("★ 阶梯：那不是一条斜线（斜线会从这里穿过）", step.at(16, 48) == background,
                "局部 (16,48) = #%06X，期望背景 #%06X".format(step.at(16, 48), background) +
                        "——非背景说明阶梯被按普通折线画了：形状是错的、画面却完全正常")
            report("阶梯：再竖（局部列 32 的竖段从行 24 到行 72）",
                step.at(32, 40) == stepRgb && step.at(32, 80) == background,
                "局部 (32,40) = #%06X（期望 #%06X）、(32,80) = #%06X（期望背景 #%06X）"
                    .format(step.at(32, 40), stepRgb, step.at(32, 80), background) +
                        "——上面那个点没有墨迹说明走的是「先竖后横」，下面那个点有墨迹说明竖段越过了拐角")
            report("阶梯：第二段的踏步在值 0.75 那一行（局部行 24），不是斜线",
                step.at(48, 24) == stepRgb && step.at(48, 48) == background,
                "局部 (48,24) = #%06X（期望 #%06X）、(48,48) = #%06X（期望背景 #%06X）"
                    .format(step.at(48, 24), stepRgb, step.at(48, 48), background))
            // ★ 拐角是**斜接**的：右-上转角的外角顶点落在 (30,70)，
            // 而"两段四边形各自收尾"的实现那里缺一个半宽见方的角（外观像抗锯齿）。
            report("★ 阶梯：拐角的外角是补满的（斜接），不是一个豁口",
                step.at(33, 72) == stepRgb,
                "局部 (33,72) = #%06X，期望 #%06X".format(step.at(33, 72), stepRgb) +
                        "——不是该颜色说明两段四边形各画各的、拐角缺了一块")
            report("阶梯：拐角之外（外角顶点再往外）没有墨迹（上一条的成对反证）",
                step.at(35, 69) == background,
                "局部 (35,69) = #%06X，期望背景 #%06X".format(step.at(35, 69), background) +
                        "——非背景说明拐角被撑大了（斜接的长度算错）")
            // NaN 的判别式与缺口实验同源：**同一行上，左半边有墨迹、右半边没有**。
            // 只断言"那段没有像素"会被"整张图都没画"骗过去。
            report("阶梯：末尾是 NaN → 最后一段整段消失（同一行上左半边有、右半边没有）",
                step.at(80, 72) == background && step.at(16, 72) == stepRgb,
                "局部 (80,72) = #%06X（期望背景 #%06X）、(16,72) = #%06X（期望 #%06X）"
                    .format(step.at(80, 72), background, step.at(16, 72), stepRgb) +
                        "——非背景说明 NaN 那段被连过去了（那条线是假的，比不画更糟）")
            report("阶梯：顶部（局部行 0..21）一个像素都没有（值→y 的映射没有翻转/偏移）",
                step.countNonBackgroundInRows(0, 21, background) == 0,
                "实际 ${step.countNonBackgroundInRows(0, 21, background)} px，期望 0")

            // ---- 21e. 拾取：三个新图型的 ID pass ----
            //
            // 拾取坏了**画面一点都不会变坏**，只会让点击落在错误的对象上。
            // 柱状那两条还格外有判别力：ID pass 要把柱心偏移算进去，
            // 忘了偏移的实现会在样本中心周围盖一个方热区（局部 x ∈ [8,16]），
            // 而探针刻意取在柱内偏边处（局部 x = 5 与 15）——中心的点照样命中，
            // 只有偏边的点能把两种实现分开。
            println("\n-- 图型：拾取（柱状 / 面积 / 阶梯）--")
            report("拾取：柱状左槽位（柱内偏边，局部 x=5，样本中心在 12）命中系列 A",
                kindPickBarSlotA,
                "payload 是不是「柱A」= $kindPickBarSlotA——漏了 uBarOffset 的 ID pass 会在" +
                        "样本中心周围盖热区，而这一点离样本中心 7px、落在热区之外，于是这里落空")
            report("拾取：柱状右槽位（局部 x=15）命中系列 B", kindPickBarSlotB,
                "payload 是不是「柱B」= $kindPickBarSlotB")
            report("拾取：柱的下沿以下（局部行 80）什么都没命中（与上面两条成对）",
                kindPickBarBelow,
                "那里必须有柱才该命中；命中说明热区被撑到了下沿以下")
            report("拾取：面积填充的深处（离曲线 40px）命中该系列" +
                    "（轮廓线那条路径够不到那里，所以只可能是填充自己的 ID pass）",
                kindPickAreaFill, "payload 是不是「面积斜率」= $kindPickAreaFill")
            report("拾取：阶梯的竖段上命中该系列", kindPickStepRiser,
                "payload 是不是「阶梯」= $kindPickStepRiser")

            // ---- 21f. 跨帧：这四张图在校验帧上必须已经不在画面里 ----
            //
            // 它们只在观察期画。校验帧上那一带必须是干净的背景——
            // 否则"画面恰好只有这 7 种颜色"那条既有断言会失败，而那条断言
            // 是 Task 10/11 留下的、不许改弱的。这一条把"它们没漏进校验帧"
            // 从"那条颜色断言间接证明了"变成"直接量了一遍"。
            var kindResidue = 0
            for (yy in BAR_PLOT_Y until STEP_PLOT_Y + KIND_PLOT_H) {
                for (xx in KIND_PLOT_X.toInt() until KIND_PLOT_W) {
                    if (pixelAt(xx, yy) != background) kindResidue++
                }
            }
            // 装配实验图那一块（x ∈ [0,20)、y ∈ [502,602)）同样要干净。
            var decorResidue = 0
            for (yy in DECOR_PLOT_Y until DECOR_PLOT_Y + DECOR_PLOT_H) {
                for (xx in DECOR_PLOT_X until DECOR_PLOT_W) {
                    if (pixelAt(xx, yy) != background) decorResidue++
                }
            }
            report("图型与装配实验图在校验帧上都已经不在画面里（观察期专属，无残留）",
                kindResidue == 0 && decorResidue == 0,
                "左边那一条 96×${STEP_PLOT_Y + KIND_PLOT_H - BAR_PLOT_Y} 里有 $kindResidue px 不是背景；" +
                        "装配那一块 ${DECOR_PLOT_W}×$DECOR_PLOT_H 里有 $decorResidue px 不是背景")
        }

        // ---- 22. ★ 装配：标题 / 图例 / 外边距 ----
        //
        // 这一节的期望值全部按 ChartLayout 的**两条尺寸规则**手算（见那些常量的说明），
        // 而不是去读 layout.plotRect()——后者会让"布局算错了"与"画面按错的布局画"
        // 同时成立，断言就成了橡皮图章。唯一的例外是那条身份断言：它比的正是
        // "过布局"与"不过布局"这两条路径的最终像素。
        //
        // 判别式选的是**数据线的行号**而不是"标题带里有没有字"：标题带 + 图例带
        // 恰好是对称的两条（同字号 → 同高），所以线取 0.5 时挤与不挤落在同一行上。
        // 取 0.25 之后两者差 7 行，"布局真的把绘图区挤了"才有像素证据。
        println("\n-- ★ 装配：标题 / 图例 / 外边距 --")
        val decor = decorSnapshot
        if (decor == null) {
            report("装配实验图的快照取到了（否则下面整节都是橡皮图章）", false, "decor=null")
        } else {
            // "文字墨迹" = 既不是背景、也不是系列色。不去比对字体的那个灰色：
            // SDF 文字是抗锯齿的，8px 下没有哪一个像素的覆盖度能到 1.0，
            // 于是**没有任何一个像素**等于那个纯色——比对一个具体值只会天天红。
            //
            // 注意像素口径是 **RGB**（快照回读时已经丢掉 alpha），所以这里一律用
            // `decorSeriesRgb`（0x2050C0）而不是 `decorSeriesArgb`（0xFF2050C0）。
            // 这个坑真的踩过一次：五条断言同时红，而画面完全正确——表现与"实现没接线"
            // 一模一样（绘图区里 32 px、图例带 0 px、文字跑到色块左边）。区分办法只有一个：
            // 把那一块的像素逐行打出来看（当时打出来的是"线在第 66..67 行、色块 6×6"，
            // 与设计**逐像素相符**，于是问题只能出在断言的比较值上）。
            fun textInk(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                var n = 0
                for (yy in y0..y1) {
                    for (xx in x0..x1) {
                        val c = decor.at(xx, yy)
                        if (c != background && c != decorSeriesRgb) n++
                    }
                }
                return n
            }

            val titleRows = decorInnerTop.toInt()..(decorInnerTop + decorTitleBandH).toInt() - 1
            val legendRows = decorLegendBandY.toInt()..
                    (decorLegendBandY + decorLegendBandH).toInt() - 1
            val plotRows = decorPlotTop.toInt()..decorPlotBottom.toInt()

            // 标题：**画在标题带里**。没有这一条，"标题没画"会从下面每一条底下溜过去。
            report("标题画在标题带里（局部行 $titleRows）",
                textInk(0, titleRows.first, DECOR_PLOT_W - 1, titleRows.last) > 0,
                "标题带里的文字墨迹 ${textInk(0, titleRows.first, DECOR_PLOT_W - 1, titleRows.last)} px，" +
                        "期望 > 0")
            // 与上一条成对：标题的墨迹不许越过带子进到绘图区里
            // （漏掉"给标题留位置"时，标题会直接压在绘图区最上面几行上）。
            report("绘图区里没有标题的墨迹（标题带真的把它挡在外面了）",
                textInk(0, plotRows.first, DECOR_PLOT_W - 1, plotRows.last) == 0,
                "绘图区里的文字墨迹 ${textInk(0, plotRows.first, DECOR_PLOT_W - 1, plotRows.last)} px，期望 0")

            // ★ 绘图区被挤过：数据线的行号 == 按布局规则算出来的那两行。
            // 不挤的话线会落在局部第 ${...} 行（比这里高 7 行）——而画面看起来完全正常。
            val inkCol = DECOR_PLOT_W / 2
            val inkRange = decor.inkRange(inkCol, decorSeriesRgb)
            report("★ 绘图区的下边缘到局部行 ${"%.1f".format(decorPlotBottom)}：" +
                    "数据线落在第 $decorInkRows 行（标题与图例真的把绘图区挤了）",
                inkRange != null && inkRange.first == decorInkRows.first &&
                        inkRange.last == decorInkRows.last,
                "第 $inkCol 列上的墨迹行范围 $inkRange，期望 $decorInkRows" +
                        "（不挤的话线会落在更高的几行上，因为这个值是按「内框 + 标题带 + 间隙」" +
                        "算出来的——标题带与图例带同高，所以数据取 0.5 时分不出挤没挤，" +
                        "取 0.25 才分得出）")

            // padding：绘图区的内容没有越过内框（左右各 2px）。16 列 × 2 行 = 32 px。
            val inPlot = decor.countIn(DECOR_PADDING.toInt(), decorInkRows.first,
                (DECOR_PLOT_W - DECOR_PADDING).toInt() - 1, decorInkRows.last, decorSeriesRgb)
            val inLeftPad = decor.countIn(0, decorInkRows.first,
                DECOR_PADDING.toInt() - 1, decorInkRows.last, decorSeriesRgb)
            val inRightPad = decor.countIn((DECOR_PLOT_W - DECOR_PADDING).toInt(),
                decorInkRows.first, DECOR_PLOT_W - 1, decorInkRows.last, decorSeriesRgb)
            report("外边距 ${DECOR_PADDING.toInt()}px 生效：数据线恰好占内框那 16 列 × 2 行",
                inPlot == 32 && inLeftPad == 0 && inRightPad == 0,
                "内框里 $inPlot px（期望 32）、左边距里 $inLeftPad px、右边距里 $inRightPad px（都期望 0）")

            // ★ 图例色块：6×6 = 36 px，而且是图例带里**唯一**的系列色。
            val swatchPixels = decor.countIn(0, legendRows.first, DECOR_PLOT_W - 1,
                legendRows.last, decorSeriesRgb)
            report("★ 图例色块 = ${DECOR_SWATCH.toInt()}×${DECOR_SWATCH.toInt()} = 36 px，" +
                    "且它是图例带里唯一的系列色",
                swatchPixels == 36,
                "图例带（局部行 $legendRows）里的系列色 $swatchPixels px，期望 36" +
                        "——多了说明色块比 legendSwatchSize 大（或位置偏了），" +
                        "少了说明被别的绘制盖住或者压根没画")
            // 图例文字：在色块**右边**（不许压在色块上，也不许跑到带子外面）
            val legendTextX = (0 until DECOR_PLOT_W).filter { xx ->
                legendRows.any { yy ->
                    decor.at(xx, yy) != background && decor.at(xx, yy) != decorSeriesRgb
                }
            }
            val swatchRight = DECOR_PADDING.toInt() + DECOR_SWATCH.toInt()
            report("图例文字在色块右边（最左一列 $legendTextX 的墨迹在色块右边缘之后）",
                legendTextX.isNotEmpty() && legendTextX.first() > swatchRight,
                "文字墨迹的列 $legendTextX，色块右边缘在第 $swatchRight 列" +
                        "——文字压在色块上或跑到左边，图例就读不出来了")

            // 身份断言：同一张图、同一块矩形，过 ChartLayout 与不过它逐像素相同。
            val viaLayout = barViaLayoutSnapshot
            val plain = barSnapshot
            report("★ 同一张图（无标题/无图例/无内边距）：drawChart 与 draw 逐像素相同",
                viaLayout != null && plain != null && viaLayout.sameAs(plain),
                if (viaLayout == null || plain == null) "有一张快照没抓到"
                else if (!viaLayout.sameAs(plain)) {
                    var diff = 0
                    for (yy in 0 until plain.h) {
                        for (xx in 0 until plain.w) {
                            if (viaLayout.at(xx, yy) != plain.at(xx, yy)) diff++
                        }
                    }
                    "$diff px 不同——说明 ChartLayout 在没有装饰时也动了绘图区，" +
                            "而那是**无声地改动了已验证的行为**"
                } else "两张快照逐像素相同（${plain.w}×${plain.h}）")
        }

        // ---- 23. ★ 左/右图例、底部标题、轴标题、以及"带子就是边界" ----
        //
        // 四个变体共用一块 108×38 的矩形、按帧轮换（见那一组常量的说明）。全部期望值
        // **手算**，而且只用与字体无关的量：带子高（字号 × 行高系数）、色块位置、
        // 绘图区的上下边缘。左右图例的**带宽**与字体有关（色块 + 间隙 + 文字宽度），
        // 所以那几条只钉"绘图区从带子之后开始""文字在色块右边"这类关系，
        // 不写一个猜出来的固定列号——那种期望值换字体时会红，而红的原因与缺陷无关。
        //
        // 这一节补的是三处"只有几何单测、没有像素断言"的地方（左/右图例、底部标题），
        // 外加两条新的能力：带子边界（超出部分被裁）与轴标题。
        println("\n-- ★ 左/右图例 / 底部标题 / 轴标题 / 带子边界 --")
        val overInnerLeft = OVER_PADDING.toInt()
        val overInnerRight = OVER_W - OVER_PADDING.toInt()
        val overInnerTop = OVER_PADDING.toInt()
        val overInnerBottom = OVER_H - OVER_PADDING.toInt()
        val overBandH = OVER_FONT * ChartLayout.LINE_HEIGHT_FACTOR

        // 文字墨迹 = 既不是背景、也不是系列色。与装配实验同一个口径（不比对那个灰色：
        // 抗锯齿之后没有哪一个像素等于纯色），也同样是 RGB 口径。
        fun overInk(s: Shot, x0: Int, y0: Int, x1: Int, y1: Int): Int {
            var n = 0
            for (yy in y0..y1) {
                for (xx in x0..x1) {
                    val c = s.at(xx, yy)
                    if (c != background && c != overSeriesRgb) n++
                }
            }
            return n
        }

        /** 行区间里所有带文字墨迹的列（升序）。 */
        fun overInkColumns(s: Shot, y0: Int, y1: Int): List<Int> =
            (0 until s.w).filter { xx -> (y0..y1).any { yy -> s.at(xx, yy) != background && s.at(xx, yy) != overSeriesRgb } }

        /** 行区间里带系列色的列（升序）——用来"看见"绘图区从哪一列到哪一列。 */
        fun overSeriesColumns(s: Shot, y0: Int, y1: Int): List<Int> =
            (0 until s.w).filter { xx -> (y0..y1).any { yy -> s.at(xx, yy) == overSeriesRgb } }

        val overA = overSnapshotA
        val overB = overSnapshotB
        if (OVER_DEBUG_DUMP) {
            listOf("A" to overA, "B" to overB, "C" to overSnapshotC, "D" to overSnapshotD,
                "E" to overSnapshotE)
                .forEach { (name, shot) ->
                    println("---- 变体 $name 的原始像素（. 背景 / # 系列色 / ? 其它）----")
                    shot?.let { s ->
                        for (y in 0 until s.h) {
                            val row = StringBuilder()
                            for (x in 0 until s.w) {
                                val c = s.at(x, y)
                                row.append(
                                    when (c) {
                                        background -> '.'
                                        overSeriesRgb -> '#'
                                        else -> '?'
                                    }
                                )
                            }
                            println("%3d %s".format(y, row))
                        }
                    }
                }
        }
        val overC = overSnapshotC
        val overD = overSnapshotD
        val overE = overSnapshotE

        // ---- 23a. 变换守卫：带着变换调 drawChart 必须抛 ----
        //
        // 这件事在画面上没有任何痕迹（它本来就该什么都不画），所以只能直接问一次
        // （探针见 captureOverflow）。消息里必须提到"变换"——否则"抛了异常"也可能
        // 是因为别的入口检查（例如矩形非法），那就是一条对不上因的断言。
        report("★ 带着变换调 drawChart 抛 IllegalStateException（装饰的布局算在设备像素上）",
            transformGuardError is IllegalStateException &&
                    transformGuardError?.message?.contains("变换") == true,
            "抛的是 ${transformGuardError?.javaClass?.simpleName ?: "什么都没抛"}，" +
                    "消息：${transformGuardError?.message?.take(60) ?: "—"}")

        if (overA == null || overB == null || overC == null || overD == null || overE == null) {
            report("五个变体的快照都抓到了（否则下面整节都是橡皮图章）", false,
                "A=${overA != null} B=${overB != null} C=${overC != null} " +
                        "D=${overD != null} E=${overE != null}")
        } else {
            // 手算的公共量：数据线（值 0.5、线宽 2）在各自绘图区里的墨迹行范围。
            // 装配实验那条用的是同一个式子：行 = ceil(sy-1.5) .. floor(sy+0.5)。
            fun lineRows(plotTop: Double, plotH: Double): IntRange {
                val sy = plotTop + (1.0 - OVER_VALUE) * plotH
                return kotlin.math.ceil(sy - 1.5).toInt()..kotlin.math.floor(sy + 0.5).toInt()
            }

            // ---- 23b. 变体 A：左图例 + 底部标题 ----
            //
            // 手算：内框 y ∈ [2,36)。底部标题带 = 11.2（字号 8 × 1.4）→ 绘图区 y ∈ [2, 22.8)。
            // 左图例带占满内框高，宽 = 6 + 4 + "A" 的宽度（与字体有关）。
            val aPlotH = overInnerBottom - overInnerTop - overBandH - OVER_TITLE_GAP
            val aLineRows = lineRows(overInnerTop.toDouble(), aPlotH.toDouble())
            val aSwatch = overA.countIn(overInnerLeft, 5, overInnerLeft + 5, 10, overSeriesRgb)
            report("★ A（左图例）：色块 6×6 = 36 px 且贴内框左边缘、在图例带最上面那一行（局部行 5..10）",
                aSwatch == 36,
                "内框左上角 6×6 里 ${aSwatch} px，期望 36——色块不在这里说明图例没摆在左边" +
                        "（上下放的图例色块会在底部带子里）")
            val aLine = overA.inkRange(60, overSeriesRgb)
            report("★ A：数据线落在第 $aLineRows 行（底部标题带真的把绘图区挤矮了）",
                aLine == aLineRows,
                "第 60 列上的系列色行范围 $aLine，期望 $aLineRows" +
                        "（不挤的话绘图区高 15.2px，线会落到局部第 11、12 行上）")
            val aTextCols = overInkColumns(overA, 5, 10)
            report("A：图例文字从色块右边（局部列 ${overInnerLeft + 10} 附近）起画",
                aTextCols.firstOrNull()?.let { it in (overInnerLeft + 9)..(overInnerLeft + 12) } == true,
                "色块那一行（局部行 5..10）里文字墨迹的列 $aTextCols，" +
                        "期望最左一列 ≈ ${overInnerLeft + 10}（色块 6 + 间隙 4）")
            val aTitleCols = overInkColumns(overA, (overInnerBottom - overBandH).toInt() + 1,
                overInnerBottom - 1)
            report("A：底部标题画在内框左下角（标题带里最左的墨迹从内框左边缘起）",
                aTitleCols.firstOrNull()?.let { it <= overInnerLeft + 2 } == true,
                "标题带（局部行 ${(overInnerBottom - overBandH).toInt() + 1}..${overInnerBottom - 1}）" +
                        "里最左的墨迹列 ${aTitleCols.firstOrNull()}，期望 ≤ ${overInnerLeft + 2}" +
                        "（左图例的文字在更右边，所以「跑到最左边」只可能是标题画的）")
            report("A：绘图区里没有文字墨迹（标题与图例都没画到数据上）",
                overInk(overA, 30, overInnerTop, overInnerRight - 1, aLineRows.first - 2) == 0,
                "绘图区上部 ${overInk(overA, 30, overInnerTop, overInnerRight - 1, aLineRows.first - 2)} px")

            // ---- 23c. 变体 B：右图例 + 底部标题 ----
            //
            // 与 A 逐条成对，判别式只有一条：**色块在右半边**（A 的在最左边）。
            // 这条能分开"图例摆错了边"，而"绘图区被挤"由数据线的行号钉着（两者同高）。
            val bSwatch = overB.countIn(53, 5, 105, 10, overSeriesRgb)
            report("★ B（右图例）：色块 36 px 且落在内框右半边（局部列 ≥ 53）",
                bSwatch == 36,
                "右下角 6×6 里 ${bSwatch} px，期望 36——0 说明图例摆在了左边（那是 A 的样子）")
            report("B：左图例那一块（局部列 ${overInnerLeft}..7、行 5..10）里一个色块像素都没有",
                overB.countIn(overInnerLeft, 5, overInnerLeft + 5, 10, overSeriesRgb) == 0,
                "内框左上角里 ${overB.countIn(overInnerLeft, 5, overInnerLeft + 5, 10, overSeriesRgb)} px 系列色" +
                        "（数据线在行 11、12，不在这个区间里）")
            val bLine = overB.inkRange(30, overSeriesRgb)
            report("★ B：数据线落在第 $aLineRows 行（与 A 同：底部标题带把绘图区挤到同样高）",
                bLine == aLineRows,
                "第 30 列上的系列色行范围 $bLine，期望 $aLineRows")
            val bInkCols = overInkColumns(overB, 5, 10)
            val bSwatchCols = overSeriesColumns(overB, 5, 10)
            report("B：图例文字在色块右边（最左一列 ${bInkCols.firstOrNull()} 在色块右边缘之后）",
                bInkCols.isNotEmpty() && bSwatchCols.isNotEmpty() &&
                        bInkCols.first() > bSwatchCols.last(),
                "文字墨迹的列 ${bInkCols.firstOrNull()}，色块列 ${bSwatchCols.firstOrNull()}..${bSwatchCols.lastOrNull()}")
            val bTitleCols = overInkColumns(overB, (overInnerBottom - overBandH).toInt() + 1,
                overInnerBottom - 1)
            report("B：底部标题画在内框左下角（与 A 逐条成对）",
                bTitleCols.firstOrNull()?.let { it <= overInnerLeft + 2 } == true,
                "标题带里最左的墨迹列 ${bTitleCols.firstOrNull()}，期望 ≤ ${overInnerLeft + 2}")

            // ---- 23d. 变体 C：底部图例 + 一个比带子还长的系列名 ----
            //
            // 手算：底部图例带 = 11.2 → 带子 y ∈ [24.8, 36)；色块垂直居中 → 行 27..32。
            // 40 个 W（每个约 7px）从局部列 12 起，远超内框宽 104px → 右边必然被切断。
            val cBandTop = overInnerBottom - overBandH
            val cSwatch = overC.countIn(overInnerLeft, 27, overInnerLeft + 5, 32, overSeriesRgb)
            report("C：底部图例的色块 36 px 且贴内框左下角（局部行 27..32）",
                cSwatch == 36, "左下角 6×6 里 ${cSwatch} px，期望 36")
            val cTextCols = overInkColumns(overC, cBandTop.toInt(), overInnerBottom - 1)
            report("★ C：超长系列名一直画到内框右边缘的前一列（局部列 $OVER_LOCAL_CUT），" +
                    "说明它**真的**比带子宽、被切断了",
                cTextCols.lastOrNull() == OVER_LOCAL_CUT,
                "图例带里文字墨迹的最右一列 ${cTextCols.lastOrNull()}，期望 $OVER_LOCAL_CUT" +
                        "（比它小说明这条断言是橡皮图章：文字根本没长到那里）")
            report("★ C：内框右边缘之外（那 2px 外边距）一个墨迹像素都没有——超出的部分被裁掉了",
                overInk(overC, overInnerRight, 0, OVER_W - 1, OVER_H - 1) == 0,
                "右边距两列里有 ${overInk(overC, overInnerRight, 0, OVER_W - 1, OVER_H - 1)} px" +
                        "（不裁的话 40 个 W 会一直画到快 300px 处）")
            report("C：绘图区里没有长标签的墨迹（带子也挡住了纵向的越界）",
                overInk(overC, 30, overInnerTop, overInnerRight - 1, cBandTop.toInt() - 2) == 0,
                "绘图区 ${overInk(overC, 30, overInnerTop, overInnerRight - 1, cBandTop.toInt() - 2)} px")

            // ---- 23e. 变体 D：轴标题 + 刻度预留 ----
            //
            // 手算（内框 y ∈ [2,36)）：底部图例带 11.2 → 间隙 3 → 轴标题带 11.2
            // → 轴标题间隙 2 → 刻度预留 2 → 绘图区 y ∈ [2, 6.6)，高 4.6。
            // 线（值 0.5）因此落在局部第 3、4 行——**那两行就是"带子真的挤过"的证据**：
            // 少了轴标题带与预留，绘图区会高 15.2px，线会落到第 10、11 行。
            val dPlotBottom = overInnerBottom - overBandH - OVER_LEGEND_GAP - overBandH -
                    OVER_AXIS_GAP - OVER_TICK_RESERVE
            val dLineRows = lineRows(overInnerTop.toDouble(),
                (dPlotBottom - overInnerTop).toDouble())
            val dLineCols = overSeriesColumns(overD, dLineRows.first, dLineRows.last)
            val dAxisBandTop = (overInnerBottom - overBandH - OVER_LEGEND_GAP - overBandH).toInt()
            // 轴标题带的上边缘 = 内框下边 - 图例带 - 图例间隙 - 轴标题带高；下边缘 = 减去前两项
            val dAxisBandBottom = overInnerBottom - overBandH - OVER_LEGEND_GAP
            report("★ D：数据线落在第 $dLineRows 行（轴标题带 + 刻度预留真的把绘图区挤矮了）",
                overD.inkRange(60, overSeriesRgb) == dLineRows,
                "第 60 列上的系列色行范围 ${overD.inkRange(60, overSeriesRgb)}，期望 $dLineRows" +
                        "（不挤的话绘图区高 20.2px，线会落到局部第 10、11 行）")
            report("★ D：轴标题画在轴标题带里（局部行 $dAxisBandTop..${dAxisBandBottom.toInt() - 1}）",
                overInk(overD, 30, dAxisBandTop, overInnerRight - 1,
                    dAxisBandBottom.toInt() - 1) > 0,
                "轴标题带里的墨迹 ${overInk(overD, 30, dAxisBandTop, overInnerRight - 1, dAxisBandBottom.toInt() - 1)} px")
            report("D：绘图区那几行里没有轴标题的墨迹（它被带子挡在外面）",
                overInk(overD, 30, overInnerTop, overInnerRight - 1, dAxisBandTop - 2) == 0,
                "绘图区 + 预留带 + 轴标题间隙里 ${overInk(overD, 30, overInnerTop, overInnerRight - 1, dAxisBandTop - 2)} px")
            if (dLineCols.isEmpty()) {
                report("D：绘图区的横向范围可以从数据线读出来（否则下一条是橡皮图章）", false, "没有读到系列色")
            } else {
                val plotLeft = dLineCols.first()
                val plotRight = dLineCols.last()
                val titleCols = overInkColumns(overD, dAxisBandTop, dAxisBandBottom.toInt() - 1)
                    .filter { it >= plotLeft }
                // 居中时：文字左边缘离绘图区左边缘约 (绘图区宽 - 文字宽) / 2 ≈ 43；
                // 左对齐时约 0；右对齐时文字右边缘会贴到 plotRight。两条一起把三者分开。
                val centeredLeft = titleCols.firstOrNull()?.let { it - plotLeft } ?: -1
                val centeredRight = titleCols.lastOrNull()?.let { plotRight - it } ?: -1
                report("★ D：轴标题**居中**于轴（不是左对齐也不是右对齐）",
                    centeredLeft >= 20 && centeredRight >= 5,
                    "文字左边缘离绘图区左边缘 $centeredLeft px（居中应约 43、左对齐约 0）、" +
                            "右边缘离右边缘 $centeredRight px（右对齐约 0）")
            }
            // ---- 23f. 变体 E：LINE_AND_MARKERS 的**两个半边都要在** ----
            //
            // 手算（内框 y ∈ [2,36)，没有图例也没有标题，绘图区就是整个内框）：
            // 值 0.5 → 线的中心行 = 2 + 17 = 19 → 线占局部行 18、19（线宽 2）。
            // x 窗口 [-0.5, 1.5] → 每样本 52px，两个样本落在局部列 28 与 80；
            // 标记的边长是 2×半径 = 6 → 两个 6×6 的块，行 16..21、列 25..30 / 77..82。
            //
            // 两条断言合起来才是"两半都在"：只画点不画线 → 线那条红；
            // 只画线不画点 → 标记那条红（标记比线高、也比线宽，所以它躲不掉）。
            val eMarkerRows = overE.countIn(0, 16, OVER_W - 1, 17, overSeriesRgb) +
                    overE.countIn(0, 20, OVER_W - 1, 21, overSeriesRgb)
            report("★ E（LINE_AND_MARKERS）：两个标记各 6×6，线的上下各 2 行里共 48 px 系列色",
                eMarkerRows == 48,
                "线的上下各 2 行（局部行 16、17、20、21）里有 $eMarkerRows px，期望 48" +
                        "（2 个标记 × 6 列 × 4 行）——0 说明标记点那一半压根没画；" +
                        "24 说明标记的边长写成了半径（3 而不是 6）")
            val eMarkerCols = (0 until OVER_W).filter { xx ->
                (16..17).any { yy -> overE.at(xx, yy) == overSeriesRgb } ||
                        (20..21).any { yy -> overE.at(xx, yy) == overSeriesRgb }
            }
            report("★ E：标记画在两个样本的位置上（局部列 25..30 与 77..82）",
                eMarkerCols == (25..30).toList() + (77..82).toList(),
                "标记墨迹的列 $eMarkerCols，期望 ${(25..30).toList() + (77..82).toList()}")
            val eLineRows = overE.countIn(0, 18, OVER_W - 1, 19, overSeriesRgb)
            val eLineCols = overSeriesColumns(overE, 18, 19)
            // 线的两端各被标记盖住 3 列（线横跨 28..79，标记横跨 25..30 与 77..82），
            // 所以这两行上的总数 = 52 列 × 2 行 + 6 列 × 2 行 = 104 + 12 = 116。
            // 写成"116"而不是"≥104"：只画点不画线时这里是 24，只画线不画点时是 104，
            // 两者都与 116 差得很远，不必靠容差去猜。
            report("★ E：折线那一半还在（局部行 18、19 上是 116 px 系列色、列 25..82）",
                eLineRows == 116 && eLineCols == (25..82).toList(),
                "行 18、19 上有 $eLineRows px、列 ${eLineCols.firstOrNull()}..${eLineCols.lastOrNull()}，" +
                        "期望 116 px、列 25..82（线段 52 列 + 两个标记各探出线端 3 列）" +
                        "——104 说明标记没画、24 说明折线被丢掉了（只剩一串点，看起来像刻意的散射风格）")
            report("E：绘图区之外一个像素都没有（标记没有越出裁剪盒）",
                overE.countIn(0, 0, OVER_W - 1, OVER_PADDING.toInt() - 1, overSeriesRgb) == 0 &&
                        overE.countIn(0, OVER_H - OVER_PADDING.toInt(), OVER_W - 1, OVER_H - 1,
                            overSeriesRgb) == 0,
                "上下外边距里的系列色 " +
                        "${overE.countIn(0, 0, OVER_W - 1, OVER_PADDING.toInt() - 1, overSeriesRgb) + overE.countIn(0, OVER_H - OVER_PADDING.toInt(), OVER_W - 1, OVER_H - 1, overSeriesRgb)} px")

        }
        // ---- 23. ★ 平滑曲线：开关真的生效、边界回退、缺口回退 ----
        //
        // 每一条都守着一件"像素看不出来"的事：
        //   · **开关生效**：漏接的话画面只是"这条曲线有点像折线"，肉眼下完全判不准；
        //   · **边界回退**：数据/环的两端读不到邻居，而环里那个位置是**陈旧数据**
        //     （不是 NaN），拿它当控制点曲线会**弯向垃圾值**——那一段看起来仍是一条
        //     平滑的曲线；
        //   · **缺口回退**：NaN 被插值过去 = 那条线显示了一个不存在的信号（本项目最忌）。
        // 三条都必须成对：只断言"两张不同"对"两幕都没画"同样成立，
        // 只断言"两张相同"对"两幕都空"同样成立。
        //
        // ★ 判别式为什么站得住：同一块矩形、同一份数据、同一个颜色、同一个线宽，
        //   唯一变的是 Series.smooth()——两张快照的差异只可能来自那个开关。
        println("\n-- ★ 平滑曲线：Series.smooth() 的开关、边界回退、缺口回退 --")
        val curvePlainShot = smoothCurvePlainShot
        val curveSmoothShot = smoothCurveSmoothShot
        val gapPlainShot = smoothGapPlainShot
        val gapSmoothShot = smoothGapSmoothShot
        val toggledShot = smoothToggledShot
        if (curvePlainShot == null || curveSmoothShot == null || gapPlainShot == null
            || gapSmoothShot == null || toggledShot == null
        ) {

            report("前提：平滑曲线实验的五张快照都抓到了（否则这一节全是橡皮图章）", false,
                "曲线不平滑=$curvePlainShot、曲线平滑=$curveSmoothShot、" +
                        "缺口不平滑=$gapPlainShot、缺口平滑=$gapSmoothShot、开关重建=$toggledShot")
        } else {
            fun inkOf(s: Shot) = s.countNonBackgroundInRows(0, s.h - 1, background)

            // ---- 23a. 前提：两幕都真的画了 ----
            val curvePlainInk = inkOf(curvePlainShot)
            val curveSmoothInk = inkOf(curveSmoothShot)
            report("前提：不平滑那一幕真的画了（否则下面两条恒真）", curvePlainInk > 0,
                "非背景像素 $curvePlainInk px（局部 ${curvePlainShot.w}×${curvePlainShot.h}）")
            report("前提：平滑那一幕也真的画了", curveSmoothInk > 0,
                "非背景像素 $curveSmoothInk px")

            // ---- 23b. 判据一：开关真的生效 ----
            val curveDiff = curvePlainShot.diffIn(0, curvePlainShot.w - 1, curveSmoothShot)
            report("★ ① 平滑与不平滑画出来必须不同（开关真的接到了渲染路径上）",
                curveDiff >= 20,
                "两张快照有 $curveDiff px 不同（期望 ≥ 20；墨迹 $curvePlainInk / " +
                        "$curveSmoothInk px）。**0 说明开关根本没生效**——逐像素相同就等于" +
                        "\"这条曲线还是折线\"，而「看起来有点像折线」在肉眼下判不准。" +
                        "下界的出处：段中偏差手算 0.075 值 × 绘图区高 " +
                        "${SMOOTH_PLOT_H.toInt()} = 1.65 px（y 窗口跨度是 1，见上面的手算），" +
                        "而带子只有 ${SMOOTH_LINE_WIDTH.toInt()}px 高——" +
                        "四段曲线各有几十列会整行地换位置")

            // ---- 23c. 判据二：首末两段不与邻居相连 ----
            // 判据是"那一段与不平滑那版**逐像素相同**"：没有外侧邻居时只能画直线，
            // 而"弯一下"与"直着"的差别只有一两像素——肉眼看不出来，逐像素比才拦得住。
            val headDiff = curvePlainShot.diffIn(SMOOTH_HEAD_FROM, SMOOTH_HEAD_TO, curveSmoothShot)
            report("★ ② 首段（局部列 $SMOOTH_HEAD_FROM..$SMOOTH_HEAD_TO）不与邻居相连：" +
                    "与不平滑那版逐像素相同", headDiff == 0,
                "不同像素 $headDiff 个（期望 0）——非 0 说明这一段用了不存在的控制点：" +
                        "左端那个邻居在缓冲里是**陈旧数据**（不是 NaN，NaN 反而会露出来）")
            val tailDiff = curvePlainShot.diffIn(SMOOTH_TAIL_FROM, SMOOTH_TAIL_TO, curveSmoothShot)
            report("★ ② 末段（局部列 $SMOOTH_TAIL_FROM..$SMOOTH_TAIL_TO）同样逐像素相同",
                tailDiff == 0,
                "不同像素 $tailDiff 个（期望 0）——末段的右邻居是**还没采到的样本**：" +
                        "拿那个槽位当控制点（静态数据里它是 0）会让曲线朝 0 弯过去")
            val headInkCol = (SMOOTH_HEAD_FROM + SMOOTH_HEAD_TO) / 2
            val tailInkCol = (SMOOTH_TAIL_FROM + SMOOTH_TAIL_TO) / 2
            report("前提：首段与末段里真的各有一条线（否则上面两条对「两版都没画」同样成立）",
                curvePlainShot.topInkRow(headInkCol, smoothCurveRgb) != null &&
                        curvePlainShot.topInkRow(tailInkCol, smoothCurveRgb) != null,
                "列 $headInkCol 的最高墨迹行 ${curvePlainShot.topInkRow(headInkCol, smoothCurveRgb)}、" +
                        "列 $tailInkCol 的最高墨迹行 " +
                        "${curvePlainShot.topInkRow(tailInkCol, smoothCurveRgb)}（期望都不是 null）")

            // ---- 23d. 判据三：缺口不被插值过去 ----
            val gapPlainInk = inkOf(gapPlainShot)
            val gapSmoothInk = inkOf(gapSmoothShot)
            val gapDiff = gapPlainShot.diffIn(0, gapPlainShot.w - 1, gapSmoothShot)
            report("★ ③ 缺口两侧：平滑与不平滑**逐像素相同**（缺口没有被插值）",
                gapDiff == 0 && gapSmoothInk > 0,
                "不同像素 $gapDiff 个（期望 0）、平滑那一幕的墨迹 $gapSmoothInk px（期望 > 0）" +
                        "——非 0 说明缺口旁那几段用了含 NaN 的控制点（整段消失或画出垃圾），" +
                        "而「缺口被连过去」那条线显示的是一个不存在的信号")
            report("前提：不平滑那一幕也真的画了（否则「逐像素相同」对「两幕都空」同样成立）",
                gapPlainInk > 0, "非背景像素 $gapPlainInk px")
            val gapBlank = gapSmoothShot.countNonBackgroundInCols(
                SMOOTH_GAP_BLANK_FROM, SMOOTH_GAP_BLANK_TO, background)
            val gapLeftInk = gapSmoothShot.countNonBackgroundInCols(70, 80, background)
            val gapRightInk = gapSmoothShot.countNonBackgroundInCols(160, 170, background)
            report("★ ③ 缺口那一段（局部列 $SMOOTH_GAP_BLANK_FROM..$SMOOTH_GAP_BLANK_TO）" +
                    "一个像素都没有：平滑没有把缺口连过去", gapBlank == 0,
                "那几列里不是背景的像素 $gapBlank 个（期望 0）——非 0 说明曲线跨过了" +
                        "下标 $SMOOTH_GAP_NAN_INDEX 那个缺口（两侧平台的高度差 0.6 × " +
                        "${SMOOTH_GAP_PLOT_H.toInt()} = 13 px，连过去一眼可辨）")
            report("★ ③ 缺口两侧都有墨迹（上一条的成对反证：整条曲线没画也会让它是 0）",
                gapLeftInk > 0 && gapRightInk > 0,
                "左侧（列 70..80）$gapLeftInk px、右侧（列 160..170）$gapRightInk px")

            // ---- 23e. 判据四：改开关要重建缓冲 ----
            // 前四幕用的是**不同的 Series 对象**，所以缓冲从头就是对的；这一幕把
            // 已经画过十几帧的那个对象改成 smooth(true)——缓冲的物理布局（前面有没有
            // 留一个 float）与现状不符，只能重建。不重建的话四个属性的偏移与 uSmooth
            // 各说各话：曲线弯向别的样本，而画面"只是一条形状略有出入的曲线"。
            val toggledInk = inkOf(toggledShot)
            val toggleDiff = toggledShot.diffIn(0, toggledShot.w - 1, curveSmoothShot)
            report("★ ④ 同一个 Series 把开关改成 true 之后，画面与「一开始就是 true」逐像素相同",
                toggleDiff == 0 && toggledInk > 0,
                "与第 2 幕（一开始就 true）不同像素 $toggleDiff 个（期望 0）、本幕墨迹 " +
                        "$toggledInk px（期望 > 0）——非 0 说明改开关没有重建缓冲：" +
                        "属性偏移与 uSmooth 不一致，而那种错在画面上是一条「形状略有出入的曲线」")
        }

        // ---- 23f. 跨环绕：环的物理两端（三个镜像）必须真的对上 ----
        //
        // 这一节的判据是"平滑与不平滑**逐像素相同**"，而它成立的**前提是数据共线**：
        // 值是绝对下标本身（差恒为 1）⇒ Catmull-Rom 的切线恰好等于弦的斜率
        // ⇒ 曲线**就是**那条直线。于是任何一处"控制点取错了"，画出来的就不再是直线——
        // 而环的物理两端（槽位 0 前面那一个前置余量、末尾两个镜像）在缓冲里
        // **不是 NaN、是 0**（从未写过）或上一圈的值，少同步任何一份，那一段就朝 0 弯过去。
        //
        // 这一条把本特性最重的一块放在像素口径下：{@code SeriesBufferTest} 只钉住
        // "往哪个字节写哪个样本"，钉不住"GPU 真的从那儿取到了值"。
        val wrapPlainShot = smoothWrapPlainShot
        val wrapSmoothShot = smoothWrapSmoothShot
        if (wrapPlainShot == null || wrapSmoothShot == null) {
            report("前提：跨环绕那一对的两张快照都抓到了", false,
                "不平滑=$wrapPlainShot、平滑=$wrapSmoothShot")
        } else {
            val wrapDiff = wrapPlainShot.diffIn(0, wrapPlainShot.w - 1, wrapSmoothShot)
            val wrapSmoothInk = wrapSmoothShot.countNonBackgroundInRows(0, wrapSmoothShot.h - 1,
                background)
            report("★ ⑤ 跨环绕的一对：共线数据上平滑与不平滑**逐像素相同**" +
                    "（三个镜像真的被 GPU 读到了）", wrapDiff == 0 && wrapSmoothInk > 0,
                "不同像素 $wrapDiff 个（期望 0）、平滑那版墨迹 $wrapSmoothInk px（期望 > 0）；" +
                        "窗口 [12, 20] 跨过环绕点 16（环容量 8、写 20 个样本）——" +
                        "被测的那几个实例分别读前置余量、镜像 cap、镜像 cap+1，" +
                        "少同步一份它们就读到 0 并朝 0 弯过去")
            report("前提：这一对确实画在环绕点上（否则上一条恒真）",
                wrapData.writeIndex() == WRAP_TOTAL.toLong() && wrapData.itemCount() == WRAP_CAPACITY,
                "写入总数 ${wrapData.writeIndex()}（期望 $WRAP_TOTAL）、" +
                        "环里 ${wrapData.itemCount()} 个样本（期望容量 $WRAP_CAPACITY，" +
                        "即 20 > 8：槽位与数据下标已经彻底错开）")
        }

        // ---- 23g. 面积图的顶边（填充与轮廓线必须是同一条曲线）----

        val areaPlainShot = smoothAreaPlainShot
        val areaSmoothShot = smoothAreaSmoothShot
        if (areaPlainShot == null || areaSmoothShot == null) {
            report("前提：面积实验的两张快照都抓到了", false,
                "不平滑=$areaPlainShot、平滑=$areaSmoothShot")
        } else {
            val areaPlainInk = areaPlainShot.countNonBackgroundInRows(0, areaPlainShot.h - 1,
                background)
            val areaDiff = areaPlainShot.diffIn(0, areaPlainShot.w - 1, areaSmoothShot)
            report("★ ⑥ 面积图的**顶边**也平滑（填充那一半真的接上了 uSmooth）",
                areaDiff > 0 && areaPlainInk > 0,
                "两版不同像素 $areaDiff 个（期望 > 0）、不平滑那版墨迹 $areaPlainInk px。" +
                        "0 说明面积填充那条路径漏接了 uSmooth——而「填充的顶边还是折线」在画面上" +
                        "只会被看成「这条曲线不够顺」")
            val plainTop = areaPlainShot.topInkRow(SMOOTH_AREA_PROBE_COL, smoothAreaRgb)
            val smoothTop = areaSmoothShot.topInkRow(SMOOTH_AREA_PROBE_COL, smoothAreaRgb)
            report("★ ⑥ 段 1 的 t=0.25 那一列（局部列 $SMOOTH_AREA_PROBE_COL）：" +
                    "平滑版的最高墨迹行**更靠上**（行号更小）",
                plainTop != null && smoothTop != null && smoothTop < plainTop,
                "不平滑 $plainTop 行、平滑 $smoothTop 行（期望平滑的更小）。" +
                        "手算：这一段的值从 0.9 降到 0.1，曲线在 t<0.5 时比弦**高** 0.075 ⇒ " +
                        "屏幕上高 0.075 × ${SMOOTH_PLOT_H.toInt()} = 1.65 px ⇒ " +
                        "最高墨迹行必然跨过至少一行（手算的绝对行是 5 与 3）；" +
                        "两者取不到墨迹说明那一列压根没有曲线")
        }

        // ---- Task 5：图表系列的解析式抗锯齿（六个图型 + 折线的四条判据）----
        reportSeriesAntialias(w, h) { label, ok, detail -> report(label, ok, detail) }

        // ---- 回收实验：图表的 GPU 资源跟着使用走 ----
        verifyReclaim(w, h) { label, ok, detail -> report(label, ok, detail) }

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
