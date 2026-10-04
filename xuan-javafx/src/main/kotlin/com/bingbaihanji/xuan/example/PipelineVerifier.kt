package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * 批处理管线的端到端**像素校验器**：画一个各图元颜色、位置、尺寸都已知的场景，
 * 回读帧缓冲，逐项比对像素数与包围盒，失败则以非零码退出。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>本管线的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
 * 原因是单元测试的口径与几何无关——`StrokeGenerator` 的测试断言"闭合面积大于开放面积"，
 * 它<strong>自己是对的</strong>；错的是调用方忘了传 `closed = true`。
 * 这类缺陷只有把**最终像素**作为口径才拦得住。
 *
 * <p>本文件就是这么来的：它第一次运行时就发现 `strokeRect/strokeCircle/strokeEllipse`
 * 全部漏掉了闭合边（矩形整整少一条边），而 124 个单元测试没有一个发现。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
 *     -Dexec.args="-cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。
 * 它会自己关窗退出，不需要手动关闭。
 *
 * <h2>坐标前提</h2>
 *
 * <p>当前管线把用户坐标**按 1:1 映射到设备像素**（`beginFrame` 拿到的是 DPI 缩放后的
 * `scaledWidth/scaledHeight`，而基础矩阵恰好使 `(u,v)` 落在设备像素 `(u,v)`）。
 * 因此下面的期望值可以直接用用户坐标书写。若日后引入真正的 DPI 缩放，本文件的
 * 期望值需要乘以缩放系数——那时这些断言会失败，正是它该提醒的。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 判定用的容差：圆弧用折线逼近，像素数必然略少于解析值。 */
private const val TOLERANCE = 0.05

// ---------------------------------------------------------------------------
// 探针用的帧计划
//
// 主场景那一段（5 个主帧之后回读）沿用原样；两个探针各自占**一整帧**：
// 那一帧只画探针自己的几何，于是探针的读数不可能被主场景的图元污染，
// 主场景的计数与颜色种数也不可能被探针污染——两块互不干扰，谁也不改谁。
//
// ★ 三个阶段都在**同一个进程**里跑完，`frame` 只在主阶段 +1，
//   所以"主场景画满 5 帧"这个既有节奏一个字节都没动。
// ---------------------------------------------------------------------------

/** 状态栈探针所在的帧号。 */
private const val STACK_FRAME = 8

/** 边距（`aEdge`）探针所在的帧号。 */
private const val EDGE_FRAME = 10

/** 闭合路径探针（横向外扩 + 拐角）所在的帧号。 */
private const val CLOSED_FRAME = 12

/** 抗锯齿探针（§7.1 的四条判据 + 1px 细线）所在的帧号。 */
private const val AA_FRAME = 16

/** 非等比缩放探针（`gc.scale(1, 10)`）所在的帧号。 */
private const val ANISO_FRAME = 18

/** 收尾帧：汇总并落退出码。 */
private const val FINISH_FRAME = 20

/**
 * 闭合路径探针：两个**同一几何**的描边矩形，一个 AA 关、一个 AA 开，左右并排。
 *
 * <p>线宽 8（半线宽 4、外扩后 5）、边长 120、左上角取 `y = 100.75`。
 * 坐标带 `.75` 是刻意的：真外缘落在 **296.75 / 96.75** 这样的位置，
 * 于是"真外缘之外 0.25 像素"那一列的像素中心（296.5）**存在**——
 * 外扩有没有生效，就看那一列有没有片元。若把边缘取成整数，
 * 外缘两侧的像素要么全覆盖要么全不覆盖，**这条判据会恒真**。
 */
private const val CLOSED_LINE_WIDTH = 8f
private const val CLOSED_SIZE = 120f
private const val CLOSED_Y = 100.75f

/** AA 关的那一个（对照）与 AA 开的那个（被测）的左上角 x。 */
private const val CLOSED_X_NO_AA = 100.75f
private const val CLOSED_X_AA = 300.75f

/** 左边缘中段的探测行：远离上下两个拐角，只有一条直边在那里。 */
private const val CLOSED_PROBE_ROW = 160

/** 边距探针的线宽；半线宽 2 ⇒ 外扩 1 像素后 3，几何纵向覆盖 297.5..303.5。 */
private const val EDGE_LINE_WIDTH = 4f

/**
 * 边距探针的几何：一条**水平三折点共线折线**，线宽 4，开抗锯齿。
 *
 * <p><strong>为什么是三个点而不是两个</strong>：沿向的分量取
 * `min(弧长, 全长 − 弧长) / 半线宽`，而两点折线的**两端弧长都是 0**——
 * 于是整条四边形上沿向恒为 0（生成器在 `rawEdges()` 里记着这一条），
 * "中段远大于 0"这个读数根本不存在。三点共线时中间那个顶点拿到
 * `300/3 = 100`，沿向这才有一个真正非零的中段。（共线 ⇒ 拐点不发射接头，
 * 几何仍是两个干净的四边形。）
 *
 * <p><strong>y 取 `x.5`、x 取整数</strong>，两者都是刻意的，因为读数全是解析值：
 * <ul>
 *   <li>中心线 `y = 300.5` ⇒ 第 300 行的像素中心**正好**落在中心线上（横向 = 0），
 *       而 ±2（= 真实半线宽，1 用户单位 = 1 设备像素）正好落在第 298 / 302 行的中心上
 *       （横向 = ∓1、±1）。中间那两行给 ∓0.5、±0.5 —— 一共五个解析读数。</li>
 *   <li>端线取**整数** x ⇒ 端帽外扩那一圈（宽 1 个设备像素）里正好装得下**一个**像素中心
 *       （第 99 列，中心 99.5）。端线若取 `x.5`，那一圈的两条边都压在像素中心上，
 *       外扩列**一个片元都拿不到**，"带外为负"就无从读起。</li>
 * </ul>
 */
private val EDGE_POINTS = floatArrayOf(100f, 300.5f, 400f, 300.5f, 700f, 300.5f)

/**
 * 8 位通道的判定余量。期望值全是解析值（0 / 127.5 / 255），
 * 留 ±4 只是给"着色器算到 0.5 之后驱动按哪个方向取整"这点余地：
 * `0.5 * 255 = 127.5`，取 127 还是 128 由实现定，与我们的对错无关。
 */
private const val CHANNEL_TOLERANCE = 4

// ---------------------------------------------------------------------------
// ★ 抗锯齿探针的几何（设计文档 §7.1 的四条判据）
//
// 两条**同一条几何**的 45° 线，线宽 4，一条 AA 关（对照）、一条 AA 开（被测）：
//   A（AA 关）：(100,100) → (300,300)，中线 `y = x`
//   B（AA 开）：(500,100) → (700,300)，中线 `y = x − 400`
//
// **B 是 A 的整数平移**（x → x+400）⇒ 两条线与格点的相位**逐项相同**，
// 于是"同一套期望值换一个 c"就能同时量两条线，读数之间的任何差异都只可能来自开关。
// 两者在像素上离得很远（垂直间距 400/√2 ≈ 283 px），互不污染。
//
// ★ **测量窗为什么是这个形状**：对每一列 x 都取「中线上下各 4 行」，
// 于是窗对每一列都**完整**包住描边带的横截面（覆盖率非零的只有 `|m| ≤ 3` 那 7 行，
// 其中 m = y − x − c 在**像素中心**上恰好取整数——两个 `0.5` 相消）。
// 这个形状让"离散覆盖率之和"与"真实面积"**逐项相等**：
//   Σ_{|m| ≤ 3} cov(m) = 1 + 2·1 + 2(√2 − 0.5) + 2(√2 − 1) = 4√2
// 而窗内描边带的真实面积 = W × 4√2（每列 4√2，等于线宽 4 在 45° 下的横向投影）。
// 换成方形窗就不会有这条等式——方向是**少**，不是多：被窗的两条竖边切掉的是**边界列**的
// 横截面，而方形窗相对本窗多出来的那些格子（`|m| ≥ 4`）cov 恒为 0、一个 luma 都不贡献。
// （审查实测方形窗：② 390、③ 开 298、④ 113738，三项**全是少**。）
// 见 §7.1 那条"精确相等"的说明。
// ---------------------------------------------------------------------------

/**
 * 测量窗的**列数**（x 方向的宽度）。窗内的每条读数都正比于它。
 *
 * <p>⚠️ **它不是自由参数，有上下界**，两个界都是"越界会冤枉正确实现"那一类：
 * <ul>
 *   <li><strong>上界 `AA_W ≤ 145`</strong>：窗的左边界在 `x = 150`，而对照线终止于
 *       `x = 300`（[AA_AX1]）⇒ 窗的右边界越过线的末端就会把**端帽的羽化**收进窗里，
 *       读数不再正比于 `AA_W`。（精确的临界值是 `x ≤ 299.5`，即 `AA_W ≤ 149`；
 *       取 145 是给端帽斜坡留一点余量。）**实测 `AA_W = 160` 会让 4 条断言倒**
 *       （② 603≠640、③ 449≠480、④ 173099、④对照 152796≠163200），
 *       其中 ④对照 的失败信息还会把原因归成"硬边内接于斜带"——**真因是窗超界**。
 *       一条会把正确实现判失败、还给出错误解释的判据，比没有判据更坏。</li>
 *   <li><strong>下界（大）**没有**</strong>：`AA_W = 60` 全过——窗变窄只是让每条读数
 *       按比例变小。</li>
 * </ul>
 */
private const val AA_W = 100

/** 对照线（AA 关）的窗左边界；中线 `y = x` ⇒ c = 0。 */
private const val AA_WX0_OFF = 150
private const val AA_C_OFF = 0

/** 被测线（AA 开）的窗左边界；中线 `y = x − 400` ⇒ c = −400。 */
private const val AA_WX0_ON = 550
private const val AA_C_ON = -400

/**
 * 窗在中线上下各取的行数（半高）。
 *
 * <p>⚠️ **必须 ≥ 3**：横截面里覆盖率非零的有 `|m| ≤ 3` 那 7 行，半高取 2 会把
 * `|m| = 3` 那一圈切在窗外——**实测半高 2 时 ② 读 398**（期望 400），
 * 而那是判据自己切掉的，不是实现的问题。取 4 是"7 行全包 + 上下各留 1 行背景"。
 */
private const val AA_WIN_DY = 4

/**
 * 抗锯齿探针帧里的线色与背景色，以及两者的亮度差。
 *
 * <p>背景就是 `glClearColor(0.2, 0.2, 0.2)` 的 `#333333`（与 [PipelineVerifierApp.background] 同值）；
 * 线一律纯白。帧内**没有别的颜色**，混出来的中间调必然是灰的
 * ⇒ **红通道就是亮度**，`墨量 = Σ(red − 51)`，而"亮度差"= 255 − 51 = **204**。
 */
private const val AA_LINE_RGB = 0xFFFFFF
private const val AA_BG_LUMA = 0x33
private const val AA_LUMA_SPAN = 0xFF - AA_BG_LUMA

/** 两条 45° 线的线宽与端点（[drawAntialiasScene]）。 */
private const val AA_LINE_WIDTH = 4f
private const val AA_AX0 = 100f
private const val AA_AX1 = 300f
private const val AA_BX0 = 500f
private const val AA_BX1 = 700f

/**
 * 1px 细线那两条判据的相位。
 *
 * <p>线宽 1、恒等变换下 `px = 1` ⇒ **几何**半宽 1.5、**真实**半宽 0.5，
 * 而覆盖率公式给的是 `cov = clamp(1 − |d|, 0, 1)`（d = 到中线的距离，设备像素）。
 * - `y = k + 0.25`：覆盖 0.75 / 0.25 两行，和 = **1.00**；
 * - `y = k + 0.50`：中间那行满覆盖，和 = **1.00**。
 * 两种相位下"每列一个像素"这条墨量守恒都成立（§4.3 说的"细带可能偏厚"就在这里量）。
 */
private const val AA_THIN_Y_P25 = 400.25f
private const val AA_THIN_Y_P50 = 460.5f

/**
 * 细线的 x 范围，以及墨量比对的窗。
 *
 * <p>⚠️ 同 [AA_W]：窗 `[200, 600)` 必须落在线的 `[100, 700)` **内部**（两端各留 100 的余量，
 * 端帽羽化与窗边界互不干扰）。行的上下界由 [verifyAntialias] 按 `线心 ∓3/+4` 取，
 * 理由同 [AA_WIN_DY]（细线的 `cov` 非零只有那两行，行界不跟着线心走就会切掉羽化）。
 */
private const val AA_THIN_X0 = 100f
private const val AA_THIN_X1 = 700f
private const val AA_THIN_WX0 = 200
private const val AA_THIN_WX1 = 600

// ---------------------------------------------------------------------------
// ★ 非等比缩放探针的几何（`Gc.antialias` 的 KDoc 里**声明**过、但一直没有断言的那条降级）
//
// `gc.scale(1f, 10f)` 下 `matrixScale() = (1 + 10) / 2 = 5.5` ⇒ `px = 1/5.5 ≈ 0.1818`。
// 于是"1 个设备像素"的外扩量在两条轴上差 10 倍：
//   · **压缩轴**（x，scale 1）：几何只到真外缘之外 **0.1818** 个设备像素，
//     而覆盖率斜坡要 **0.5** 个 ⇒ **真外缘之外那 0.18 个设备像素的羽化被切掉**
//     （⚠️ 不是"退化成硬边"：带内的羽化照常画出来——实测剖面 `98:#CDCDCD`
//     是覆盖率 0.75 的过渡像素，而真正硬边的 AA 关对照在同位置是**全白**）；
//   · **拉伸轴**（y，scale 10）：几何到真外缘之外 **1.818** 个设备像素 ⇒ 斜坡完整。
// ---------------------------------------------------------------------------

/** 探针用的画布缩放；两条线共用（x 是压缩轴、y 是拉伸轴）。 */
private const val ANISO_SCALE_X = 1f
private const val ANISO_SCALE_Y = 10f

/**
 * 竖线（横向 = 压缩轴）的 x 与横线（横向 = 拉伸轴）的 y。
 *
 * <p><strong>两个相位都是挑过的</strong>，因为"真外缘之外有没有片元"这件事
 * 在整数像素网格上是**相位决定**的（斜坡只有 1 个设备像素宽，而像素中心间距也是 1）：
 * <ul>
 *   <li>竖线取 `k + 0.25` ⇒ 像素中心到中线的距离是 `0.25 + ℤ`：真外缘 2.0 之外的
 *       最近一档是 **2.25**，而几何止于 2.1818 ⇒ **那一档没有片元**（要钉的就是它）。</li>
 *   <li>横线取 `k + 0.025` ⇒ **乘 10 之后**是 `k + 0.25`（设备 y = 200.25），
 *       真外缘 20.0 之外的最近一档是 **20.25**（覆盖率 0.25）⇒ **有片元**。</li>
 * </ul>
 */
private const val ANISO_VX = 100.25f
private const val ANISO_HY = 20.025f

// 下面四个**一律派生，不抄字面量**。
// ⚠️ 抄一份同值字面量的后果是"挪了上面那个、下面这个不跟着动"：实测只把 ANISO_HY 挪到
//    40.025（相位不变）而不同步改 ANISO_DEVICE_Y ⇒ 窗 [170,232) 里**根本没有线**
//    ⇒ **拉伸轴那条对正确实现失败**，还附一句错的解释。
//    与 `Gc.INITIAL_VERTEX_CAPACITY` 那两处注释同族：**同值字面量 ≠ 同一个量**。

/** 竖线在设备坐标里的中线 x：x 轴的缩放是 [ANISO_SCALE_X] ⇒ 设备 x = 用户 x。 */
private const val ANISO_DEVICE_X = ANISO_VX * ANISO_SCALE_X

/** 横线在设备坐标里的中线 y：设备 y = 用户 y × [ANISO_SCALE_Y]。 */
private const val ANISO_DEVICE_Y = ANISO_HY * ANISO_SCALE_Y

/** 两条线各自的**真半线宽**（设备像素）：线宽的一半 × 各自轴的缩放。 */
private const val ANISO_TRUE_HALF_X = AA_LINE_WIDTH * 0.5f * ANISO_SCALE_X
private const val ANISO_TRUE_HALF_Y = AA_LINE_WIDTH * 0.5f * ANISO_SCALE_Y

/** 状态栈探针三条线的线心 y。刻意都带 `.25`：边缘落在**像素内部**，AA 才看得出来。 */
private const val LINE_A_Y = 60.25f
private const val LINE_B_Y = 120.25f
private const val LINE_C_Y = 180.25f

/**
 * 状态栈探针三条线的 x 范围，以及比对窗口。
 *
 * <p>窗口的 y 是**相对线心**的（{@link #LINE_WINDOW_DY0} 到 {@link #LINE_WINDOW_DY1}，半开区间）：
 * 从线心上方 4 行到下方 5 行，共 **10 行**（校验器运行时也会把 `窗口 320x10` 印出来）。
 * 描边带本体只有 4 行、开了 AA 连最外那圈共 5 行 ⇒ **窗口比描边带高一倍以上**，
 * AA 多出来的 fringe 才落得进比对范围。只比线心那几行的话，开与不开 AA 的核心行逐像素相同
 * ——窗口开窄了等于没有断言。
 */
private const val LINE_X0 = 60f
private const val LINE_X1 = 460f
private const val LINE_WINDOW_X0 = 100
private const val LINE_WINDOW_X1 = 420
private const val LINE_WINDOW_DY0 = -4
private const val LINE_WINDOW_DY1 = 6

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已经有一个顶层 `main()`，两个同名顶层函数
 * 会让 `import com.bingbaihanji.xuan.example.main` 报"重载歧义"——而 `Main.kt` 正是这样
 * 导入示例入口的（同包内无法靠别名区分）。用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，
 * 上面文档里的 `java -cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt`
 * 因此照常可用。
 */
@JvmName("main")
fun verifyMain() {
    Application.launch(PipelineVerifierApp::class.java)
}

class PipelineVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 帧序号，每帧 +1。只用来决定**本帧画哪个场景**。 */
    private var frame = 0

    /** 主场景已经画过的帧数。探针帧不计入——它替代了原来那个"等到第 5 帧再回读"的计数。 */
    private var mainFrames = 0

    /** 主场景那一整套断言是否已经跑过（只跑一次）。 */
    private var mainVerified = false

    /**
     * 全部失败项，**跨阶段**累积。
     *
     * <p>它是字段而不是局部变量：现在有三个阶段（主场景、状态栈探针、边距探针），
     * 各自独立地往同一份清单里记——退化成三个各自的局部清单，
     * 那"退出码"就只能反映最后一个阶段的成败，前面那些失败会被静默吞掉。
     */
    private val failures = ArrayList<String>()

    // 场景配色。每个图元都用与背景(0x333333)及其余图元**可区分**的颜色：
    // 原示例的凹多边形用 0xFF333333，与 glClearColor(0.2,0.2,0.2) 完全相同，
    // 于是"多边形到底画出来没有"根本无从判断——这本身就是个应当避免的示例缺陷。
    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val cyan = 0x00FFFF
    private val magenta = 0xFF00FF
    private val background = 0x333333

    // flush() 的 z 序探针专用色。**刻意不复用上面任何一个**：这两个颜色在画面里
    // 只出现在三个探针矩形内，于是"先画的那层一个像素都不剩"就成了覆盖整幅画面的不变量，
    // 而失败信息也能直接点名是 flush 的问题，不会伪装成"红矩形画多了"。
    //
    // 分了 RGB 与 ARGB 两套：`pixelAt` 与 `counts` 的口径都是**不含 alpha 的 RGB**，
    // 拿 ARGB 去比会永远不相等（同一个色的 0xFF00FF80 与 0x00FF80 就不相等），
    // 而那看起来像"颜色画错了"。
    private val probeUnderRgb = 0xFF8000
    private val probeOverRgb = 0x00FF80
    private val probeUnderArgb = probeUnderRgb or (0xFF shl 24)
    private val probeOverArgb = probeOverRgb or (0xFF shl 24)

    /** 三个 flush 探针的左上角 x（y、尺寸见 [assertFlushKeepsZOrder]），都落在主场景的空区域里。 */
    private val PROBE_X = floatArrayOf(40f, 200f, 620f)

    override fun start(stage: Stage) {
        // 采样数走**同一个系统属性**（`-Dxuan.probe.msaa`，解析与 MsaaVerifier 共用）：
        // 没有这一行时下面那道守卫是**死代码**——msaa 恒为默认 0，"明确拒绝"只在改源码时才可能触发。
        val bridge = FXGLTransfer(msaa = readRequestedMsaa(), font = textFont())
        // ★ 回读拒绝守卫（实现见 MsaaVerifier.kt 的 requirePixelReadback）：本校验器的读数
        //   全部来自 glReadPixels，而多采样画布上那次调用是**非法操作**——它会读回全 0，
        //   然后让下面每一条断言报"画面全黑"式的假失败。假失败比"明确拒绝"坏得多：
        //   它会让人去查渲染，而真因在配置。
        requirePixelReadback(bridge)
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "Xuan Pipeline Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * `onFrame` 回调的入口：按当前帧号决定画哪个场景。
     *
     * <p>探针各占**一整帧**，那一帧里主场景一个图元都不画——
     * 这是"探针不污染既有判据"的实现方式：既有判据读的是主场景帧，
     * 探针读的是探针帧，两者在像素上完全没有交集。
     */
    private fun drawScene(gc: Gc) {
        when (frame) {
            STACK_FRAME -> drawStyleStackScene(gc)
            EDGE_FRAME -> drawEdgeProbeScene(gc)
            CLOSED_FRAME -> drawClosedProbeScene(gc)
            AA_FRAME -> drawAntialiasScene(gc)
            ANISO_FRAME -> drawAnisoProbeScene(gc)
            else -> drawMainScene(gc)
        }
    }

    private fun drawMainScene(gc: Gc) {
        // flush 探针画在最前面：它们占的是主场景没碰过的空区域（y 175..295、x 40..739），
        // 因此既不影响下面那些颜色计数与包围盒，也让"flush 丢了顶点"这类退化**只**打在新断言上，
        // 不去连累主场景那些既有断言。
        assertFlushKeepsZOrder(gc)

        gc.fill = 0xFFFF0000.toInt()
        gc.fillRect(50f, 50f, 200f, 120f)

        gc.fill = 0xFF00FF00.toInt()
        gc.fillCircle(500f, 200f, 80f)

        gc.stroke = 0xFF0000FF.toInt()
        gc.lineWidth = 4f
        gc.strokeRect(100f, 300f, 250f, 150f, radius = 16f)

        gc.fill = 0xFF00FFFF.toInt()
        gc.fillPolygon(floatArrayOf(500f, 300f, 700f, 300f, 700f, 450f, 600f, 520f, 500f, 450f))

        gc.stroke = 0xFFFF00FF.toInt()
        gc.lineWidth = 3f
        gc.beginPath()
        gc.moveTo(50f, 550f)
        gc.bezierCurveTo(200f, 450f, 300f, 650f, 450f, 550f)
        gc.strokePath()
    }

    // ------------------------------------------------------------------
    // 探针一：Gc.antialias 进样式栈（Task 3 Step 6 的像素版）
    // ------------------------------------------------------------------

    /**
     * 画三条**几何完全相同**的横线，只有 `antialias` 的来路不同。
     *
     * <p>判据（`verifyStyleStack`）：A 与 B **逐像素相同**，而 C 必须与 A **不同**。
     * 两条缺一不可——
     *
     * <ul>
     *   <li>A ≡ B 说的是"`save` 内开的那次 AA 被 `restore` 关回去了"。
     *       它失败的样子很具体：`restore()` 漏掉 `antialias` ⇒ B 带着 AA 画出来
     *       ⇒ 会在上下各多出一圈半透明像素。</li>
     *   <li>★ C ≠ A 是那条**反证**。少了它，A ≡ B 就是一句**恒真**的话：
     *       把 `antialias` 整个字段删掉、让 `strokeOutline` 永远当它是 false，
     *       A 与 B 照样逐像素相同——也就是说那条断言根本拦不住"这条开关压根没接线"。
     *       这与本文件里"只断言外角有像素会被'整块都画错了'骗过去"是同一类教训。</li>
     * </ul>
     *
     * <p>三条线都在线段中段取比对窗口，且窗口**比描边带高一倍以上**：窗口 10 行
     * （[LINE_WINDOW_DY0] −4 到 [LINE_WINDOW_DY1] 6，见那里的说明），
     * 而描边带本体只有 4 行、开了 AA 连最外那圈共 5 行。
     * 只比"线心那几行"的话，AA 多出来的最外圈 fringe 落在窗口之外，
     * 开了 AA 与没开 AA 的核心行逐像素相同——窗口开窄了就等于没有断言。
     */
    private fun drawStyleStackScene(gc: Gc) {
        gc.lineWidth = 4f
        gc.stroke = 0xFFFFFFFF.toInt()

        // A：基线。全程不开，它同时是 C 的对照。
        gc.antialias = false
        gc.drawLine(LINE_X0, LINE_A_Y, LINE_X1, LINE_A_Y)

        // B：save 内开、restore 关。画的时候必须已经回到"关"。
        gc.save()
        gc.antialias = true
        gc.restore()
        gc.drawLine(LINE_X0, LINE_B_Y, LINE_X1, LINE_B_Y)

        // C：反向控制。同样经 save/restore 往返，但**画的时候 AA 是开的**——
        // 它证明这条开关确实会改变像素，从而让"A ≡ B"不再是橡皮图章。
        gc.save()
        gc.antialias = true
        gc.drawLine(LINE_X0, LINE_C_Y, LINE_X1, LINE_C_Y)
        gc.restore()

        gc.antialias = false
    }

    /**
     * 边距（`aEdge`）探针：把 `vEdge` 直接当颜色输出，回读若干**解析位置**上的读数。
     *
     * <p>它守的是 Task 1 留下的一笔欠账：`layout(location = 4)` 那条属性指针加进来时
     * **没有任何着色器声明它**，于是把偏移写成 0 或 20（读到拾取 ID）画面也逐像素不变
     * ——"指针接对了"当时只有"代码读过一遍"这一层保障。本帧第一次让着色器真的读它，
     * 于是它可以被回读了。
     *
     * <p>用完即弃：探针模式只在**本帧**开，`flush()` 之后立刻关掉。
     * 它不进主场景、也不进任何 Task 4 的判据——那些判据量的是"抗锯齿好不好"，
     * 这里量的是"边距这个信号本身接对了没有"，两件事、两个通道。
     */
    private fun drawEdgeProbeScene(gc: Gc) {
        gc.lineWidth = EDGE_LINE_WIDTH
        gc.stroke = 0xFFFFFFFF.toInt()
        gc.antialias = true

        // 必须 flush 一次再关：`uProbe` 是**着色器程序**上的状态，
        // 而顶点是攒到 submit 时才画的——不先把它画出来就关掉，探针等于没开。
        gc.setEdgeProbe(true)
        gc.strokePolyline(EDGE_POINTS)
        gc.flush()
        gc.setEdgeProbe(false)

        gc.antialias = false
    }

    /**
     * 闭合路径探针：同一几何的描边矩形画两遍，一遍 AA 关、一遍 AA 开。
     *
     * <p>它守两件事，都**只对闭合路径**成立：
     * <ol>
     *   <li><b>横向外扩必须对闭合路径也生效</b>。曾用同一个 `!closed` 同时挡掉了
     *       横向与沿向两种外扩，于是矩形/圆/椭圆描边的真外缘之外没有片元、
     *       覆盖率只能从 0.5 起步（`0.5 → 1` 而不是 `0 → 1`）。
     *       判别式是"真外缘**之外** 0.25 像素那一列有没有片元"（见 [CLOSED_Y] 的说明）。</li>
     *   <li><b>拐角不能被啃掉一块</b>。接头三角形的横向取 ±1 时，覆盖率公式在 `|x| = 1`
     *       处只给 0.5，而外层那个 `几何半宽/真实半宽` 的倍率会把它推到 1.5 ⇒ 覆盖率 0
     *       ⇒ 风筝形外侧整片透明。判据是"真实轮廓**内部**的拐角方块里一个背景像素都没有"。</li>
     * </ol>
     */
    private fun drawClosedProbeScene(gc: Gc) {
        gc.lineWidth = CLOSED_LINE_WIDTH
        gc.stroke = 0xFFFFFFFF.toInt()

        gc.antialias = false
        gc.strokeRect(CLOSED_X_NO_AA, CLOSED_Y, CLOSED_SIZE, CLOSED_SIZE)

        gc.antialias = true
        gc.strokeRect(CLOSED_X_AA, CLOSED_Y, CLOSED_SIZE, CLOSED_SIZE)
        gc.antialias = false
    }

    /**
     * ★ 抗锯齿探针：**同一条几何画两遍**（一遍关、一遍开），外加两对 1px 的细线。
     *
     * <p>四条判据（① 对照组没有中间值 / ② 实验组有过渡带 / ③ 线心行纯色数相等 /
     * ④ 墨量守恒）与细线那条方向断言都在 [verifyAntialias] 里，
     * 几何与窗的形状见 [AA_W] 那段注释。
     *
     * <p>四条线的颜色**一律纯白**、背景是 `glClearColor` 的 `#333333`：
     * 于是"中间值"就是灰色的中间调，而墨量差恰好是 255 − 51 = **204**，
     * 判据里的每个数都可以手算。
     */
    private fun drawAntialiasScene(gc: Gc) {
        gc.lineWidth = AA_LINE_WIDTH
        gc.stroke = 0xFFFFFFFF.toInt()

        // A：对照。整帧的其余部分也关着（本探针帧只有这四条线）。
        gc.antialias = false
        gc.drawLine(AA_AX0, AA_AX0, AA_AX1, AA_AX1)

        // B：被测。与 A 是同一条几何的整数平移。
        gc.antialias = true
        gc.drawLine(AA_BX0, AA_AX0, AA_BX1, AA_AX1)

        // 1px 细线：两种相位各一对（关 / 开）。线宽 1 时**每条线的墨量都是 1.0 px/列**，
        // 两种模式、两种相位四种组合应当给出同一个数——这条是"细带有没有偏厚"的方向断言。
        gc.lineWidth = 1f
        gc.antialias = false
        gc.drawLine(AA_THIN_X0, AA_THIN_Y_P25, AA_THIN_X1, AA_THIN_Y_P25)
        gc.antialias = true
        gc.drawLine(AA_THIN_X0, AA_THIN_Y_P25 + 30f, AA_THIN_X1, AA_THIN_Y_P25 + 30f)
        gc.antialias = false
        gc.drawLine(AA_THIN_X0, AA_THIN_Y_P50, AA_THIN_X1, AA_THIN_Y_P50)
        gc.antialias = true
        gc.drawLine(AA_THIN_X0, AA_THIN_Y_P50 + 30f, AA_THIN_X1, AA_THIN_Y_P50 + 30f)

        gc.antialias = false
    }

    /**
     * ★ 非等比缩放探针：`gc.scale(1f, 10f)` 下画一竖一横两条线（几何见 [ANISO_VX]）。
     *
     * <p>它替 `Gc.antialias` 的 KDoc 里那条**声明过却没有断言**的降级立闸门：
     * **压缩轴上真外缘之外的羽化被切掉**（不是"退化成硬边"——带内的羽化照常画出来，
     * 见 [ANISO_SCALE_X] 上面那段）。原文档写的是"未验证：这一条是从代码读出来的
     * （七个校验器全部跑恒等变换）"——本帧之后它就不是了。
     *
     * <p>两条线都开 AA（要量的正是"开着的时候还羽化不羽化"），
     * 且都在缩放作用域内画——`px` 是在 `strokeOutline` 里按当时的 `matrixScale()` 算的。
     * **缩放量走 [ANISO_SCALE_X] / [ANISO_SCALE_Y] 两个常量**：判定那边的
     * "设备中线"与"真半线宽"都是从它们派生的，抄字面量会让两边脱钩。
     */
    private fun drawAnisoProbeScene(gc: Gc) {
        gc.lineWidth = AA_LINE_WIDTH
        gc.stroke = 0xFFFFFFFF.toInt()
        gc.antialias = true

        gc.save()
        gc.scale(ANISO_SCALE_X, ANISO_SCALE_Y)
        // 竖线：它的**横向**是 x（压缩轴）。设备 y 落在 [200, 600]。
        gc.drawLine(ANISO_VX, 20f, ANISO_VX, 60f)
        // 横线：它的**横向**是 y（拉伸轴）。设备 x 落在 [150, 700]。
        gc.drawLine(150f, ANISO_HY, 700f, ANISO_HY)
        gc.restore()

        gc.antialias = false
    }

    /**
     * flush() 的 z 序：后画的必须盖住先画的，<b>无论中间有没有 flush</b>。
     *
     * <p>三种情况画的都是同一件事——同一个位置上先画 [probeUnderArgb]、后画 [probeOverArgb] 的
     * 两个重合矩形，只有中间那一步不同：
     * <ol>
     *   <li><b>不 flush</b>：两层落在同一个批里，靠批内的顶点顺序分胜负；</li>
     *   <li><b>中间 flush</b>：两层落在两个批里，靠两批的提交顺序分胜负；</li>
     *   <li><b>flush + 立即模式绘制</b>：上面那层不走 [Gc]，而是用 [immediateFill] 当场发一条
     *       在本帧命令流里就地执行的 GL 命令，模拟图表后端"数据系列自己发 draw call"这件事。</li>
     * </ol>
     *
     * <p><b>为什么必须有第三情况。</b>前两种情况里的两层都由 `Gc` 记在同一个顶点写入器里，
     * 最终按顶点顺序画进同一个帧缓冲——也就是说，<b>把 flush() 掏空成空方法，前两种情况的
     * 像素一模一样</b>。只测前两种的话，这条断言看着在守，其实拦不住 flush 退化，
     * 正是本仓库说的橡皮图章。只有让一方"当场就画"，"flush 把网格先落定"这件事才在像素上可观测：
     * 没有 flush，`Gc` 的顶点要等到 `endFrame` 才提交，于是它会盖在即时绘制的那层<b>上面</b>。
     *
     * <p><b>三种情况的排放顺序是有意的：不含 flush 的情况一放在最后。</b>
     * 一个坏掉的 `flush()` 会把它<b>之前</b>攒下的顶点一起丢掉，所以在这三组探针里，
     * 只有"后面不再跟着任何 flush"的那一组才不会被别人的 flush 连累。
     * 情况一因此排在最后（它后面只有主场景，主场景不 flush），于是"不 flush"那一组
     * 不会替后面某次坏掉的 flush 背锅——不会出现"报了'不 flush'，真凶却是后面那次
     * flush"这种误导。
     *
     * <p><b>但别把这句读成"一次注入只打一条断言"。</b>实测（把 `flush()` 掏空成空方法，
     * 跑本校验器）：一次失败 <b>5 条</b>——情况三那两条（中心色、后画色铺满），
     * 外加三条连带（先画色残影、画面颜色种数、探针后画色的计数）。
     * 它们不是误报，是同一个原因的下游表现：探针那一块画的顺序错了，
     * 全画面的颜色计数当然跟着错。
     * 排序真正保证的只是：这两组里"不 flush"那一组照常通过，不替别人背锅。
     */
    private fun assertFlushKeepsZOrder(gc: Gc) {
        val ys = 175f
        val size = 120f
        val w = size.toInt()
        val h = size.toInt()

        // 情况二：中间 flush，under 先 over 后 —— 期望 over
        gc.fill = probeUnderArgb
        gc.fillRect(PROBE_X[1], ys, size, size)
        gc.flush()
        gc.fill = probeOverArgb
        gc.fillRect(PROBE_X[1], ys, size, size)
        gc.flush()

        // 情况三：flush 之后由**立即模式**的一条 GL 命令盖上去 —— 期望 over
        gc.fill = probeUnderArgb
        gc.fillRect(PROBE_X[2], ys, size, size)
        gc.flush()
        immediateFill(gc, PROBE_X[2].toInt(), ys.toInt(), w, h, probeOverArgb)

        // 情况一：不 flush，under 先 over 后 —— 期望 over
        // 放在最后：它后面只有主场景（不 flush），所以它的失败只可能由它自己造成。
        gc.fill = probeUnderArgb
        gc.fillRect(PROBE_X[0], ys, size, size)
        gc.fill = probeOverArgb
        gc.fillRect(PROBE_X[0], ys, size, size)
    }

    /**
     * 直接向 GL 发一次"立即模式"的矩形填充，绕开 [Gc] 的批处理。
     *
     * <p>存在的理由：`Gc` 的图元攒到 `endFrame` 才提交，而图表后端（子项目 D-②）的数据系列
     * 是**当场就画**的。校验 `flush()` 的 z 序就必须有一方是"当场就画"的，否则两边都攒在同一个批里、
     * 按顶点顺序绘制，<b>flush 存在与否根本不影响最终像素</b>——那样的断言是橡皮图章。
     *
     * <p><b>它模拟的是"一条在本帧命令流里就地执行的 GL 命令"，不是 instanced draw。</b>
     * 用 `glClear` + `glScissor` 而不是 `glDrawArrays`，是因为这样同样能验证"就地执行 vs 攒到
     * `endFrame`"这条唯一的判据，却不需要再搭一套着色器与 VAO。真实数据系列那一侧
     * （着色器、VAO、以及它与本类批处理的混合状态互操作）由 Task 12 的 ChartVerifier 覆盖，
     * 不在本文件的职责内。
     *
     * <p>清屏色是全局状态，用完必须还原，否则下一帧的 `glClear` 会把整幅背景刷成这个颜色。
     *
     * @param gc  当前帧的绘制上下文：只用来取 [Gc.height] 作为视口高度。
     *            刻意不另取别的来源——同一帧里 `Gc.height` 与帧缓冲高度必须是同一个数，
     *            而上面正有一条断言钉着这一点。
     * @param x    矩形左上角 x（用户坐标，与设备像素 1:1）
     * @param y    矩形左上角 y（用户坐标，y 向下）
     * @param w    宽度
     * @param h    高度
     * @param argb 填充色
     */
    private fun immediateFill(gc: Gc, x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val prevClear = FloatArray(4)
        glGetFloatv(GL_COLOR_CLEAR_VALUE, prevClear)

        glClearColor(
            ((argb ushr 16) and 0xFF) / 255f,
            ((argb ushr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            1f
        )
        glEnable(GL_SCISSOR_TEST)
        // glScissor 的 y 从**底边**起算，用户坐标 y 向下，故翻成 height - (y + h)。
        glScissor(x, gc.height - (y + h), w, h)
        glClear(GL_COLOR_BUFFER_BIT)
        glDisable(GL_SCISSOR_TEST)

        glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3])
    }

    /**
     * `onRender` 回调的入口：把校验体包进 try/catch。
     *
     * <p>校验过程本身抛出异常时必须**以非零码退出**。
     *
     * <p>理由不是洁癖：这部分代码在 GL 线程上跑，一旦抛出去，线程死掉、
     * 汇总行与 `exitProcess` 都走不到，JVM 会因为「最后一个非守护线程结束」而
     * 以 0 退出——**一个已经打印了 FAIL 的校验器报出退出码 0**，
     * 正是本仓库最忌讳的那种「静默的绿」。
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

    /**
     * 按帧号分派到某个阶段。**每个阶段各占一整帧**，读的是**本帧自己画的东西**。
     *
     * <p>分派靠帧号而不是靠一个"本帧画了什么"的字段：那个字段在
     * "尺寸还没就绪、`drawScene` 被跳过"时会留下上一帧的值，
     * 于是探针帧会被**静默跳过**——而那正是本仓库最忌讳的失败形态
     * （报告说全过，实际上有一条断言从未求值）。
     *
     * <p><strong>帧号只在"这一帧真的读到了东西"时才推进</strong>：
     * `grabFrame()` 拿不到帧缓冲时，阶段停在原帧号上重试。
     * 若在那里也照常推进，一个还没布局完的画布就会让**整条探针一个读数都不产生**，
     * 而摘要照样打"全部通过"。
     */
    private fun verifyAll() {
        when (val f = frame) {
            STACK_FRAME -> if (verifyStyleStack()) frame = f + 1
            EDGE_FRAME -> if (verifyEdgeProbe()) frame = f + 1
            CLOSED_FRAME -> if (verifyClosedStroke()) frame = f + 1
            AA_FRAME -> if (verifyAntialias()) frame = f + 1
            ANISO_FRAME -> if (verifyAnisoScale()) frame = f + 1
            FINISH_FRAME -> finish()
            else -> {
                // 主阶段之后、收尾帧之前的那几帧：主场景照画（只是画，没人读），什么都不校验。
                if (mainVerified) {
                    frame = f + 1
                    return
                }
                if (++mainFrames < 5) {
                    frame = f + 1
                    return
                }
                // 没读到就**不推进**：主阶段一旦被跳过，整份报告会以"没有任何断言"收场，
                // 且退出码是 0。
                if (verifyMainScene()) {
                    mainVerified = true
                    frame = f + 1
                }
            }
        }
    }

    /**
     * 打印判定行，失败则记进 [failures]。
     *
     * <p>提成类级方法（原来是 `verifyAll` 里的局部闭包）是因为现在有三个阶段要往
     * **同一份**失败清单里记：各自记各自的清单，退出码就只能反映最后一个阶段的成败。
     */
    private fun report(label: String, ok: Boolean, detail: String) {
        println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
        if (!ok) failures.add(label)
    }

    /** @return 是否真的跑完了（帧缓冲尺寸未就绪时返回 false，由 [verifyAll] 下一帧重试） */
    private fun verifyMainScene(): Boolean {
        val bridge = transfer ?: return false
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return false

        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        println("=== Xuan 管线像素校验 ===")
        println("帧缓冲 ${w}x${h}  GL_FRAMEBUFFER_BINDING=${glGetInteger(GL_FRAMEBUFFER_BINDING)}  glGetError=${glGetError()}")

        // glReadPixels 行序自下而上；用户坐标 y 向下，故翻转回读行号。
        fun pixelAt(x: Int, y: Int): Int {
            val i = ((h - 1 - y) * w + x) * 4
            return ((buf.get(i).toInt() and 0xFF) shl 16) or
                    ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                    (buf.get(i + 2).toInt() and 0xFF)
        }

        // 一次全图扫描，同时得到每色像素数与包围盒。
        val counts = HashMap<Int, Int>()
        val minX = HashMap<Int, Int>();
        val minY = HashMap<Int, Int>()
        val maxX = HashMap<Int, Int>();
        val maxY = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixelAt(x, y)
                counts[c] = (counts[c] ?: 0) + 1
                if ((minX[c] ?: Int.MAX_VALUE) > x) minX[c] = x
                if ((maxX[c] ?: Int.MIN_VALUE) < x) maxX[c] = x
                if ((minY[c] ?: Int.MAX_VALUE) > y) minY[c] = y
                if ((maxY[c] ?: Int.MIN_VALUE) < y) maxY[c] = y
            }
        }

        fun countIn(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                    if (pixelAt(x, y) == argb) n++
                }
            }
            return n
        }

        fun approx(label: String, actual: Int, expected: Double, tol: Double = TOLERANCE) {
            val lo = expected * (1 - tol);
            val hi = expected * (1 + tol)
            report(
                label, actual.toDouble() in lo..hi,
                "实际 $actual，期望 ${expected.toInt()} ±${(tol * 100).toInt()}%"
            )
        }

        // Gc.width/height 是用户查询绘制区大小的唯一途径（逻辑窗口尺寸在高 DPI 下不等于它），
        // 必须与真实帧缓冲一致，否则用户按它铺满会铺歪。
        println("\n-- 绘制区尺寸 --")
        val gc = bridge.gc()
        report("Gc.width 等于帧缓冲宽度", gc?.width == w, "Gc.width=${gc?.width}，帧缓冲宽=$w")
        report("Gc.height 等于帧缓冲高度", gc?.height == h, "Gc.height=${gc?.height}，帧缓冲高=$h")

        println("\n-- 形状：像素数 --")
        approx("红矩形填充 200x120", counts[red] ?: 0, 200.0 * 120)
        approx("绿圆填充 r=80", counts[green] ?: 0, Math.PI * 80 * 80)
        // 凹多边形（耳切三角化）：鞋带公式面积 = 37000，三角化应当精确命中而非近似。
        approx("青凹多边形填充", counts[cyan] ?: 0, 37000.0, 0.01)
        // 圆角矩形描边：**精确相等**，不再用百分比容差。
        //
        // 这条断言曾经是 `772.5 * 4 = 3090`（±5%，即 ±154 px），而那个 5% **藏住了
        // 一个真缺陷**：MITER 接头漏掉"顶点与底边之间"那半个三角形时，实测是 3068
        // ——偏离 0.7%，照样"通过"。一条宽到能藏住真缺陷的断言，比没有断言更坏。
        //
        // 新期望值 3084 的出处（不是猜的，两条独立证据）：
        //   · **连跑 8 次**（每次新 JVM）都是 3084，**极差 0**；
        //   · 旧的 3090 用的是**光滑圆弧**周长 2π*16，而实现里的中心线是 **28 边形**，
        //     长度 772.244（24 段 15° 弦都比弧短）。凸闭合折线的描边带面积恰好是
        //     `2 * L * half`（角上的二阶项相消，已用鞋带公式逐项核对）= 3088.98。
        //   · 3088.98 − 3084 = **4.98 px 是"像素中心采样"的确定偏移**：另写一份独立的
        //     参考量算（外/内 miter 偏移多边形 + 逐点判中心在内）复算，得到的正是 3084，
        //     与 GPU 回读逐位相同——所以这不是抖动，也不是没解释掉的损耗。
        //   · 这条带子上**没有**"中心正好落在边界上"的像素（直边在整数坐标、
        //     斜边是无理斜率），因此它不依赖光栅化的 tie 规则，**不需要留余量**。
        // 收紧之后它能分辨的最小变化就是 1 px（MITER 缺陷那种是 16 px）。
        val blueCount = counts[blue] ?: 0
        report(
            "蓝圆角矩形描边恰好 3084 px（8 次运行极差 0；旧的 ±154 px 容差会放过 3068）",
            blueCount == 3084, "实际 $blueCount，期望 3084"
        )
        report("品红贝塞尔描边存在", (counts[magenta] ?: 0) > 200, "实际 ${counts[magenta] ?: 0} px")
        // 7 种 = 主场景的 6 种（背景 + 红绿蓝青品红）+ flush 探针的后画色。
        // 探针的**先画色**不在里面，正是因为它一个像素都不该剩下（见下面的 z 序断言）。
        report(
            "画面只有 7 种颜色（无杂散像素）",
            counts.size == 7,
            "实际 ${counts.size} 种：${counts.keys.sorted().joinToString { "#%06X".format(it) }}"
        )
        // "只有 7 种颜色"管不住**数量**：多画一块或少画一块，颜色种数都照样是 7。
        // 三个探针各 120x120，所以这里要求**恰好** 43200——多一个像素说明有别的东西在用
        // 这个颜色，少一个像素说明某个探针没铺满。实测这条失败时是**少**了一块：
        // 把 flush() 掏空会让情况三的探针缺 14400 px（不是"别处多画了"）。
        report(
            "后画色恰好铺满三个探针矩形（不多不少）", (counts[probeOverRgb] ?: 0) == 3 * 120 * 120,
            "实际 ${counts[probeOverRgb] ?: 0}，期望 ${3 * 120 * 120}"
        )

        println("\n-- 形状：包围盒 --")
        fun bbox(label: String, argb: Int, ex: Int, ey: Int, ew: Int, eh: Int) {
            if ((counts[argb] ?: 0) == 0) {
                report(label, false, "无像素"); return
            }
            val ax = minX[argb]!!;
            val ay = minY[argb]!!
            val aw = maxX[argb]!! - ax + 1;
            val ah = maxY[argb]!! - ay + 1
            report(
                label, ax == ex && ay == ey && aw == ew && ah == eh,
                "实际 ($ax,$ay) ${aw}x$ah，期望 ($ex,$ey) ${ew}x$eh"
            )
        }
        bbox("红矩形位置尺寸", red, 50, 50, 200, 120)
        bbox("绿圆位置尺寸", green, 420, 120, 160, 160)
        // 凹多边形 x∈[500,700)、y∈[300,520)：宽 200、高 220。
        bbox("青多边形位置尺寸", cyan, 500, 300, 200, 220)
        // 描边向路径两侧各扩半个线宽：路径 100..350 / 300..450 → 98..351 / 298..451。
        bbox("蓝描边外沿（98..351 x 298..451）", blue, 98, 298, 254, 154)

        println("\n-- 描边：四条边分别计数 --")
        // 这是本文件存在的直接原因：闭合边曾经整条消失，而只有把每条边单独计数才看得出来。
        val top = countIn(116, 294, 334, 306, blue)
        val bottom = countIn(116, 444, 334, 456, blue)
        val left = countIn(94, 316, 106, 434, blue)
        val right = countIn(344, 316, 356, 434, blue)
        println("  上=$top 下=$bottom 左=$left 右=$right")
        report(
            "描边无缺边（每条边都 > 300 px）", top > 300 && bottom > 300 && left > 300 && right > 300,
            "上=$top 下=$bottom 左=$left 右=$right"
        )
        // 上下边、左右边各应等长；不对称就说明有一段几何被丢掉或重复。
        report("上下边对称", Math.abs(top - bottom) <= top * 0.05, "上=$top 下=$bottom")
        report("左右边对称", Math.abs(left - right) <= left * 0.05, "左=$left 右=$right")
        approx("上边长度", top, 218.0 * 4)
        approx("右边长度", right, 118.0 * 4)

        println("\n-- 背景 --")
        report("背景色为 clear 色", (counts[background] ?: 0) > 0, "背景像素 ${counts[background] ?: 0}")

        println("\n-- flush() 的 z 序 --")
        // 三个探针都是"先画 probeUnder、后画 probeOver"的重合矩形，最终必须**只剩 probeOver**。
        // 断言的量必须是颜色：只断言"有东西画出来了"，对顺序颠倒同样成立，那是橡皮图章。
        val probeLabels = arrayOf("不 flush", "中间 flush", "flush + 立即模式绘制")
        for (i in PROBE_X.indices) {
            val x0 = PROBE_X[i].toInt()
            val y0 = 175
            val cx = x0 + 60
            val cy = y0 + 60
            val center = pixelAt(cx, cy)
            report(
                "z 序（${probeLabels[i]}）：后画的盖住先画的", center == probeOverRgb,
                "中心 ($cx,$cy) = #%06X，期望 #%06X".format(center, probeOverRgb)
            )
            approx(
                "z 序（${probeLabels[i]}）：后画色铺满 120x120",
                countIn(x0, y0, x0 + 119, y0 + 119, probeOverRgb), 120.0 * 120, 0.01
            )
        }
        // probeUnder 只出现在这三个探针里，所以"一个像素都不剩"是一条覆盖整幅画面的不变量：
        // 顺序反了会留下它，后画的那层没盖全（位置/尺寸错了）也会留下它。
        report(
            "z 序：先画的那层被完全盖住（全画面无残影）", (counts[probeUnderRgb] ?: 0) == 0,
            "先画色像素 ${counts[probeUnderRgb] ?: 0}"
        )
        return true
    }

    // ------------------------------------------------------------------
    // 探针一：Gc.antialias 进样式栈
    // ------------------------------------------------------------------

    /**
     * 状态栈探针的判定。
     *
     * <p>两半：
     * <ol>
     *   <li><b>直接读值</b>（不依赖像素）：`save → antialias = true → save → false → restore → restore`
     *       走一遍，逐层断言。它测的是 [Gc] 的状态栈本身，是本文件里唯一一条
     *       "不靠回读"的断言——栈是纯内存，本来就不需要 GL 上下文。</li>
     *   <li><b>像素比对</b>：A ≡ B、且 C ≠ A（理由见 [drawStyleStackScene]）。</li>
     * </ol>
     *
     * <p>读值那一半故意**同时**测嵌套：只测一层的话，
     * `restore()` 里写成 `antialias = false`（而不是从栈里取）也能通过——
     * 那正是这类缺陷最常见的写法，而症状是"某一次 restore 之后 AA 莫名关了"。
     */
    private fun verifyStyleStack(): Boolean {
        val f = grabFrame() ?: return false
        val gc = transfer?.gc() ?: return false

        println("\n-- ★ 探针一：Gc.antialias 进样式栈 --")
        // 逐层记下**当时读到**的值，报告里打的是它——不是循环结束之后 gc 的当前值。
        // 打当前值的话五条读数会一模一样（都是最后的 false），失败时看不出是哪一层错了。
        val atDefault = gc.antialias
        gc.save(); gc.antialias = true
        val atLevel1 = gc.antialias
        gc.save(); gc.antialias = false
        val atLevel2 = gc.antialias
        gc.restore()
        val afterRestore1 = gc.antialias
        gc.restore()
        val afterRestore2 = gc.antialias
        report("探针一 默认是关的（它保护着一批精确像素期望）", !atDefault, "默认读到 $atDefault")
        report("探针一 save 内可开", atLevel1, "save 之后读到 $atLevel1")
        report("探针一 嵌套的 save 可再改", !atLevel2, "嵌套 save 之后读到 $atLevel2")
        report("探针一 restore 回到外层（内层改了不该影响外层）", afterRestore1, "restore 之后读到 $afterRestore1")
        report("探针一 restore 回到默认的关闭状态", !afterRestore2, "再 restore 之后读到 $afterRestore2")

        val a = f.snapshot(
            LINE_WINDOW_X0, LINE_A_Y.toInt() + LINE_WINDOW_DY0,
            LINE_WINDOW_X1, LINE_A_Y.toInt() + LINE_WINDOW_DY1
        )
        val b = f.snapshot(
            LINE_WINDOW_X0, LINE_B_Y.toInt() + LINE_WINDOW_DY0,
            LINE_WINDOW_X1, LINE_B_Y.toInt() + LINE_WINDOW_DY1
        )
        val c = f.snapshot(
            LINE_WINDOW_X0, LINE_C_Y.toInt() + LINE_WINDOW_DY0,
            LINE_WINDOW_X1, LINE_C_Y.toInt() + LINE_WINDOW_DY1
        )
        val diffAB = differingPixels(a, b)
        val diffAC = differingPixels(a, c)
        report(
            "探针一 A≡B：save 内开的那次 AA 被 restore 关了回去", diffAB == 0,
            "A 与 B 相差 $diffAB 个像素"
        )
        // ★ 反证：少了它，上面那条就是恒真的——把 antialias 整个删掉、让描边永远当它是 false，
        //   A 与 B 照样逐像素相同。
        report(
            "探针一 C≠A：这条开关真的会改变像素（反证）", diffAC > 0,
            "A 与 C 相差 $diffAC 个像素"
        )
        // 读数：两条线各印**过线心的 6 行**（含上下各一行背景）。其中线内的 5 行是
        // **墨量守恒**最直接的证据——线心取 y=60.25 时解析覆盖率是
        // 0.75 / 1 / 1 / 1 / 0.25，合计恰好 4.00 px（= lineWidth）；
        // A 的对应 5 行是 1 / 1 / 1 / 1 / 0（它罩在带内），合计同样 4.00。
        // 两者相等正是"AA 没把线画粗"。
        val x = LINE_WINDOW_X0 + 100
        println("  窗口 ${LINE_WINDOW_X1 - LINE_WINDOW_X0}x${LINE_WINDOW_DY1 - LINE_WINDOW_DY0}")
        println("  A（AA 关）线心 ${f.rowColors(x, LINE_A_Y.toInt() - 3, 6)}")
        println("  C（AA 开）线心 ${f.rowColors(x, LINE_C_Y.toInt() - 3, 6)}")
        return true
    }

    // ------------------------------------------------------------------
    // 探针二：aEdge 的偏移（Task 1 的欠账）
    // ------------------------------------------------------------------

    /**
     * 边距探针的判定：把 `vEdge` 当颜色输出的那一帧，回读一组**解析位置**。
     *
     * <p>读数全是手算的（几何见 [EDGE_POINTS] 的说明），没有一个是"对着输出量出来的"：
     * <pre>
     *   红 = (横向 + 1) / 2       绿 = (沿向 + 1) / 2
     *   横向：中心线 0 → 128；±0.5 → 64 / 191；±1（真实外缘）→ 0 / 255
     *   沿向：中段 100 → 255（饱和）；端线外侧那一列 −1/6 → 106
     * </pre>
     *
     * <p>为什么这组读数能证明"偏移接对了"：若 `configureVaoAttributes` 里那条指针
     * 写成了 20（读到拾取 ID）或 0（读到位置），这两个分量会变成**别有来源**的数
     * ——例如整条带子横向恒 0（把 ID 0 的位模式读成浮点 0.0）、或者随位置线性变化。
     * 实测证据见提交信息里的变异记录。
     *
     * <p>★ <strong>与规格的一处偏差，写在这里免得后人以为漏读了一条</strong>：
     * 规格字面要求断言"端帽那一排 `vEdge.y = 0`"（映射后 `0.5`）。这里换成了**两条解析读数**
     * ——端线内侧一格的 `+1/6`（第 100 / 699 列）与端帽外扩那一圈的 `−1/6`（第 99 / 700 列）。
     * 判别力**更强**，不是更弱：几何的端点取在**整数** x（见 [EDGE_POINTS] 的说明），
     * 端线落在整数坐标上 ⇒ 那一排**没有像素中心**（中心在 `k + 0.5`），
     * "端线那一排等于 0"这个读数根本取不到；而 `±1/6` 这两条既在端线两侧、
     * 又各自带符号，把"零点是端线、带内为正、带外为负"这**三件**事一次钉住。
     */
    private fun verifyEdgeProbe(): Boolean {
        val f = grabFrame() ?: return false

        println("\n-- ★ 探针二：aEdge 的偏移（片段着色器把 vEdge 当颜色输出） --")
        // 先打两行读数：断言失败时，报告里得有"实际是多少"，
        // 否则一次 FAIL 只会留下一句"不对"，排查方向反而指向着色器本身。
        println(
            "  横向剖面（第 300 列，行 296..304）："
                    + (296..304).joinToString(" ") { "r$it=${f.r(300, it)}" })
        println(
            "  沿向剖面（第 300 行，列 97..103 / 397..403 / 697..703）："
                    + ((97..103) + (397..403) + (697..703)).joinToString(" ") { "c$it=${f.g(it, 300)}" })

        // 横向：五个解析读数（`byte = (v + 1) / 2 * 255`）
        //
        // ⚠ 第 300 行那一条（期望 128、实测 127）**判别力为零**：128 ± 4 把 127 收进去了，
        //   所以"这条属性整体读到 0"（vEdge.x 恒 0 ⇒ 红通道恒 127）时它照样通过。
        //   它**不是错的**（真实外缘的中点本来就是 0，而"读到 0"与"算出来是 0"同值），
        //   但别把它算成一条有效覆盖——真正拦得住"整体读到 0"的是另外四条
        //   （0 / 64 / 191 / 255）与整个沿向剖面。写在这里是因为评审量到过这一点。
        val crossChecks = mapOf(298 to 0, 299 to 64, 300 to 128, 301 to 191, 302 to 255)
        for ((row, expected) in crossChecks) {
            val actual = f.r(300, row)
            report(
                "探针二 横向：第 $row 行 red=$actual，即 vEdge.x=${"%.2f".format(actual / 127.5 - 1)}"
                        + "（期望 $expected = ${"%.2f".format(expected / 127.5 - 1)}）",
                Math.abs(actual - expected) <= CHANNEL_TOLERANCE, "实际 $actual，期望 $expected"
            )
        }
        // 沿向。中段单列一条：沿向在那里是 100，映射后**饱和**到 1.0，
        // 所以"回映射"只能给出下界——写成"恰好等于 255"是拿一个饱和值当精确读数用。
        val midGreen = f.g(400, 300)
        report(
            "探针二 沿向：中段（第 400 列）green=$midGreen，沿向远大于 0（映射后饱和）",
            midGreen >= 250, "实际 $midGreen，期望 ≥ 250"
        )
        val alongChecks = mapOf(
            100 to 149,   // 端线**内侧**一格：+1/6
            99 to 106,    // 端帽外扩那一圈：**−1/6**（带外为负，端帽羽化的全部来路）
            700 to 106,   // 另一端的外扩圈，与起点对称
            699 to 149,
        )
        for ((col, expected) in alongChecks) {
            val actual = f.g(col, 300)
            report(
                "探针二 沿向：第 $col 列 green=$actual，即 vEdge.y=${"%.2f".format(actual / 127.5 - 1)}"
                        + "（期望 $expected = ${"%.2f".format(expected / 127.5 - 1)}）",
                Math.abs(actual - expected) <= CHANNEL_TOLERANCE, "实际 $actual，期望 $expected"
            )
        }
        return true
    }

    // ------------------------------------------------------------------
    // 探针三：闭合路径的横向外扩 + 拐角
    // ------------------------------------------------------------------

    /**
     * 闭合路径探针的判定（几何与两件判据见 [drawClosedProbeScene]）。
     *
     * <p>读数全是解析值：
     * <pre>
     *   线宽 8 ⇒ 半线宽 4；AA 开时外扩到 5。
     *   真外缘 = 中心线 ∓ 4。左边缘中段那一行：
     *     真外缘之外 0.25 像素那一列（AA 开）→ 覆盖率 0.25 → 混出 #666666（102）
     *     同一列（AA 关）→ 几何止于真外缘 ⇒ 没有片元 ⇒ 仍是背景 #333333
     *   拐角：真轮廓内部的 4x4 个像素必须**一个背景像素都没有**。
     * </pre>
     */
    private fun verifyClosedStroke(): Boolean {
        val f = grabFrame() ?: return false

        println("\n-- ★ 探针三：闭合路径的横向外扩 + 拐角 --")
        // 左边缘中段那一行、跨过真外缘的 5 列（两个矩形各一份）
        val row = CLOSED_PROBE_ROW
        val noAaCols = (94..99).joinToString(" ") { "x$it=#%06X".format(f.rgb(it, row)) }
        val aaCols = (294..299).joinToString(" ") { "x$it=#%06X".format(f.rgb(it, row)) }
        println("  AA 关（外缘 96.75）第 $row 行：$noAaCols")
        println("  AA 开（外缘 296.75）第 $row 行：$aaCols")
        // ★ 打印的方块必须**就是被断言的方块**（下面 countNotIn 用的那两个）。
        //   曾经打印 x∈[295,299) 而断言数 x∈[297,301)：16 个被断言的像素只印出 4 个，
        //   失败时报告里会出现"打印出来的全是纯白、断言却说 7 个不是"这种自相矛盾的读数——
        //   而"失败信息可读"恰恰是这些校验器存在的一半理由。
        println("  AA 关 被断言的 4x4（x 97..100 × y 97..100）：\n" + f.boxColors(97, 97, 101, 101))
        println("  AA 开 被断言的 4x4（x 297..300 × y 97..100）：\n" + f.boxColors(297, 97, 301, 101))
        // 外沿那三个像素（也是被断言的，见 ③）
        println(
            "  AA 开 拐角外沿三点："
                    + listOf(296 to 96, 296 to 97, 297 to 96)
                .joinToString(" ") { "(${it.first},${it.second})=#%06X".format(f.rgb(it.first, it.second)) })

        // ① 横向外扩对闭合路径同样生效：真外缘之外 0.25 像素处必须有片元。
        //    AA 关时那一列是背景（几何止于真外缘），这是**对照**，必须有——
        //    少了它，"AA 开时那一列不是背景"可能只是"那里本来就画了什么"。
        //    ★ 这一条是本探针里**唯一**对"px 有没有被 !closed 挡掉"敏感的断言：
        //      实测把 `&& !closed` 加回 px ⇒ 只有它倒（其余四条照过——接头的几何拿的是
        //      joinHalf，与外扩量无关）。
        val outsideNoAa = f.rgb(96, row)
        val outsideAa = f.rgb(296, row)
        report(
            "探针三 对照组：AA 关时真外缘之外那一列就是背景", outsideNoAa == background,
            "x=96 = #%06X，期望 #%06X".format(outsideNoAa, background)
        )
        val r = f.r(296, row)
        report(
            "探针三 闭合描边的真外缘之外也有片元（横向外扩没被 !closed 挡掉）",
            Math.abs(r - 102) <= CHANNEL_TOLERANCE,
            "x=296 的 red=$r，期望 102（覆盖率 0.25 的白色压在 #333333 上）"
        )

        // ② 拐角既不能有缺口、也不能有羽化：接头（风筝形）**整块**必须是不透明的。
        //    判据取"方块里有多少个像素不是纯白"而不是"有多少个是背景"：
        //    只数背景像素的话，**羽化**（半透明）那一类漏画法在它前面恒真——
        //    实测就是如此（把接头横向改回 s 时，底边那一半只是变淡到 0.25，一个背景像素都没有）。
        //
        //    两边现在用的是**同一套相对偏移**：接头拿到的是真实半线宽（不参与外扩），
        //    所以两个风筝形都是 [外缘, 外缘+4]² —— AA 关在 [96.75,100.75]²、
        //    AA 开在 [296.75,300.75]²。取完全落在各自内部的 4x4。
        val offNonWhite = f.countNotIn(97, 97, 101, 101, 0xFFFFFF)
        val onNonWhite = f.countNotIn(297, 97, 301, 101, 0xFFFFFF)
        report(
            "探针三 对照组：AA 关的拐角方块 16 个像素全是纯白", offNonWhite == 0,
            "实测 $offNonWhite 个不是纯白"
        )
        // 这条只对"接头横向取 0"敏感。**不要**在失败信息里顺手把"闭合路径不再外扩"
        // 也算进来：实测（把 `&& !closed` 加回 px）那条变异让**别处**倒 1 条，本条照过
        // ——接头拿到的是 joinHalf，与外扩量无关。失败信息里断言一件不会发生的事，
        // 与注释写错同性质。
        report(
            "探针三 AA 开的接头风筝形整块不透明（既无缺口也无羽化）", onNonWhite == 0,
            "实测 $onNonWhite 个不是纯白（变异：把接头横向改回 s ⇒ 底边那一半会羽化）"
        )

        // ③ 拐角**不再向外多画**：真轮廓之外的三个外沿像素必须是背景。
        //    曾把外扩量也加在接头上（接头横向恒 0 ⇒ 不会被羽化）⇒ 这三个像素是**纯白**，
        //    即每个拐角胖 1 像素。判据就是"它们回到背景"。
        //    ★ 注意它们读的是 **0** 而不是解析覆盖率（对角线 0.0625、另两个 0.25）：
        //      接头的边距恒 0 ⇒ 它走"完全覆盖"分支 ⇒ 它的边界是**硬边**，
        //      半覆盖的像素按像素中心规则整块取 0 或 1。这一条是**已声明的降级**
        //      （接头的横向这一维在拐点处不够用，见 StrokeGenerator.emitJoin），
        //      不是"拐角画对了到小数点后两位"。
        val rim = listOf(296 to 96, 296 to 97, 297 to 96)
        val rimOver = rim.count { f.rgb(it.first, it.second) != background }
        report(
            "探针三 拐角不再向外多画 1 像素（真轮廓之外那一圈回到背景）", rimOver == 0,
            rim.joinToString(" ") { "(${it.first},${it.second})=#%06X".format(f.rgb(it.first, it.second)) })
        return true
    }

    // ------------------------------------------------------------------
    // ★ 抗锯齿：§7.1 的四条判据 + 细线的方向断言
    // ------------------------------------------------------------------

    /**
     * 抗锯齿探针的判定（几何见 [drawAntialiasScene]，窗的形状见 [AA_W] 那段注释）。
     *
     * <p><strong>四条判据缺一不可</strong>（设计文档 §7.1）：
     * <pre>
     *   ① AA **关**的那条确实没有中间值：既非背景 #333333 也非线色 #FFFFFF 的像素数 = **0**
     *  ② AA **开**的那条过渡带存在：该像素数 = **4W**（每列 2 侧 × 2 级）
     *  ③ **线心没移位、没变淡**：`|m| ≤ 1` 那三条对角带上的纯色像素数两种模式**精确相等** = 3W
     *  ④ **墨量**：窗内墨量 == 窗内描边带的**真实面积** × 亮度差（± 8 位量化界）
     * </pre>
     *
     * <p>★ **③ 为什么只取线心（`|m| ≤ 1`）、不取"总纯色数"**：开了 AA 之后最外那一圈
     * 纯色像素本来就会变成过渡像素，总纯色数**必然略减**——写成"总数相等"是一条
     * **恒假断言**，比没有断言更坏。线心那三条带离边缘足够远，两种模式下都是满覆盖，
     * 它相等是**成立**的：AA 关时是"5 条带全白"，AA 开时"3 条带全白 + 2 条带羽化"，
     * 交集恰好是那 3 条。
     *
     * <p>⚠️ **但 ③ 的灵敏度比它读起来低得多，别拿它当"平移的判据"**：
     * 线心像素的覆盖率是 `cov = 0.5 + (1 − |v|)·√2`，在 `|m| ≤ 1` 处已是 **1.4142**
     * （早被 `clamp` 压到 1 了）——它要**掉出纯色**得让 `|m|` 越过 **1.828**。
     * 换成可感的量：沿 x（或 y）平移 **> 0.83** 设备像素、或沿法向平移 **> 0.59** 设备像素、
     * 或把覆盖率整体压掉 **约 30%**（1 − 1/1.4142）才动得了它。
     * **实测**：把线内缩 0.1 设备像素 ⇒ **③ 照过、④ 倒**（−11599.8）。
     * 也就是说"整体平移"这件事主要由 ④ 承担，③ 只挡住**大**的位移与整体降透明度。
     *
     * <p>★ **④ 为什么不写成"AA 开 == AA 关"**（这是本探针最要紧的一句）：
     * 45° 直线下这两者**本来就不相等，而且不该相等**。硬边是**像素中心采样**——
     * 只有 `|m| ≤ 2` 那些像素的中心落在真实带内，于是它的"有效宽度"是 2√2 ≈ 2.83 px
     * 而不是 4（三角形像素的阶梯**内接**于斜带）⇒ 硬边的墨量比真实面积**少 11.6%**。
     * 而解析式 AA 的覆盖率斜坡是**面积守恒**的：窗内离散覆盖率之和
     * `Σ_{|m| ≤ 3} cov(m) = 1 + 2·1 + 2(√2−0.5) + 2(√2−1) = 4√2`，
     * 与真实面积 `4√2` **逐项相等**。所以 ④ 的参照系是**真实面积**，不是对照组：
     * 拿"两者相等"当判据会得到一条**恒假**的断言，而它读起来像是"AA 错了"。
     * 对照组的墨量照实打印（它是 5W × 亮度差），**它比真实面积少**这件事本身是读数，
     * 不是缺陷。
     *
     * <p>⚠️ **那条 `Σ = 4√2` 不是"斜坡守恒"的普遍性质，它依赖斜坡宽度正好等于采样间距**——
     * 而那一步里的 √2 值得写明（不写明的话，下一个人会以为换个斜坡宽度也照样成立）：
     * <pre>
     *   fwidth 的定义是 |dFdx| + |dFdy|；45° 法向下两个偏导各是梯度长的 1/√2
     *   ⇒ fwidth = √2 × |∇v|（**不是**梯度长本身）
     *   ⇒ 斜坡在 v 里的半宽 = wc/2 = √2/(2·realHalf)
     *   而 m = √2 · d（m 也是 d 的 √2 倍）⇒ 斜坡在 m 里的半宽 = 1
     *   ⇒ **采样间距（m 步长 1）正好等于斜坡半宽** ⇒ 采样和与积分逐项相等
     * </pre>
     * **换掉那个 √2 等式就不成立**：审查实测把 fwidth 换成真梯度长度 ⇒ `Σ = 5.757`、
     * 墨量 117450 ⇒ **④ 会失败**。所以 ④ 实际上**同时钉住了斜坡宽度**——这正是它的价值。
     *
     * <p>★ ④ 的**量化界**是算出来的，不是"看着差不多"给的：窗内每列有 4 个羽化像素
     * （`|m| = 2` 与 `3` 各两个），8 位取整让每个最多偏离解析值 0.5 ⇒ 每列 ±2、全窗 ±2W。
     * 那 4 个的解析值是 `204 × (√2 − 0.5) = 186.4996`（`|m| = 2`）与
     * `204 × (√2 − 1) = 84.4996`（`|m| = 3`）——**离 8 位取整边界只有 0.0004**，
     * 浮点噪声就够把某一档推过去。**本机实测就推过去了一档，而且推的是 `|m| = 3`**：
     * 校验器自己印的直方图是 `#EDEDED×200`（= **237**，`|m| = 2` **向下**取整，没被推过）
     * 与 `#888888×200`（= **136**，`|m| = 3` 被推过界 +0.5004）。
     * 每列因此多 2 luma（2 个 `|m| = 3` 像素各 +1），而 2 个 `|m| = 2` 像素各 −0.4996
     * 几乎抵掉它 ⇒ 实测净偏离只有 **+0.2**（115400 对 115399.8）。
     * **别把这个界读成余量**：驱动若反向取整（两档都向下），偏离就是 **−199.8**。
     *
     * <p>代码里那个 `+2` 是给上面这件事留的垫子，不是"界本该是 2W + 2"：
     * **2W = 200 是"每个羽化像素最多偏 0.5"这条*最小*界**，而实测已证明单个像素可以偏到
     * +0.5004（解析值落在边界外 0.0004 处）⇒ 真正的界是 `2W + 4 × 0.0004 × W`，
     * 对 W = 100 是 200.16。取 202 是为了让"两档都反向取整"（−199.8）与"两档都正向"
     * （+200.2）这两种极端都留得住，而不是卡在 200.16 上——**界卡得太紧会把正确实现判为失败**。
     *
     * <p>**它窄到能分辨出真缺陷**（变异实测）：把 `clamp` 的 0.5 改成 0.0（覆盖率整体内缩
     * 一档）墨量 115400 → **74600**（−35%）、改成 1.0 → **156200**（+35%）；
     * 把外扩量改成 0 → **98400**（−15%）；把 `edges` 恒传 `null` → **183600**（+59%）。
     * 都远在界外。
     */
    private fun verifyAntialias(): Boolean {
        val f = grabFrame() ?: return false

        val wX0Off = AA_WX0_OFF
        val wX0On = AA_WX0_ON
        val yOff0 = wX0Off + AA_C_OFF - AA_WIN_DY
        val yOff1 = wX0Off + AA_W + AA_C_OFF + AA_WIN_DY
        val yOn0 = wX0On + AA_C_ON - AA_WIN_DY
        val yOn1 = wX0On + AA_W + AA_C_ON + AA_WIN_DY

        println("\n-- ★ 抗锯齿：同一条几何画两遍（§7.1 的四条判据） --")
        // ★ 打印的窗必须**就是**被断言的窗（本文件的规矩，见 verifyClosedStroke 那段）。
        // ⚠️ 上界必须写成 `${wX0Off + AA_W}`（**带花括号**）：不加花括号的话 Kotlin 只把
        //   紧跟着 `$` 的那一截变量名插值，剩下的 `+ AA_W` 是**字面量**
        //   ⇒ 印出来是 `x∈[150,150 + AA_W)`，字面读还像"空窗 [150,150)"。
        //   而**断言用的是真窗口**（`wX0Off + AA_W`）——于是失败时读数与断言对不上，
        //   与上一轮那条"打印 4 格、断言 16 格"同族。
        //   （可复用的检查手法：grep 正则 `\$[A-Za-z_]\w* \+ `（`$变量` 后面直接跟 ` + `）
        //     —— 修好之后全文应当**一处都没有**：本注释已改写成不含该模式的措辞。）
        println("  AA 关 窗 x∈[$wX0Off,${wX0Off + AA_W}) y∈[$yOff0,$yOff1)  中线 y = x")
        println("  AA 开 窗 x∈[$wX0On,${wX0On + AA_W}) y∈[$yOn0,$yOn1)  中线 y = x − 400")
        // 横截面读数：m = y − x − c 在像素中心上取整数，所以"第几级覆盖"完全由它决定。
        val midOff = wX0Off + AA_W / 2
        val midOn = wX0On + AA_W / 2
        println(
            "  AA 关 第 $midOff 列横截面（m = y − x）："
                    + (-4..4).joinToString(" ") { "m$it=#%06X".format(f.rgb(midOff, midOff + AA_C_OFF + it)) })
        println(
            "  AA 开 第 $midOn 列横截面（m = y − x + 400）："
                    + (-4..4).joinToString(" ") { "m$it=#%06X".format(f.rgb(midOn, midOn + AA_C_ON + it)) })
        // 整窗的颜色直方图：① ② 的读数就是从这个直方图里来的，打出来让失败可读。
        println("  AA 关 窗内颜色：${f.aaHistogram(wX0Off, yOff0, wX0Off + AA_W, yOff1)}")
        println("  AA 开 窗内颜色：${f.aaHistogram(wX0On, yOn0, wX0On + AA_W, yOn1)}")

        val off = f.aaStats(wX0Off, yOff0, wX0Off + AA_W, yOff1, AA_C_OFF)
        val on = f.aaStats(wX0On, yOn0, wX0On + AA_W, yOn1, AA_C_ON)

        report(
            "★ 抗锯齿① AA 关的线没有过渡像素（既非背景也非线色）", off.fringe == 0,
            "实测 ${off.fringe} 个（期望 0）"
        )
        report(
            "★ 抗锯齿② AA 开的线有过渡像素，且恰好 4W（每列 2 侧 × 2 级）",
            on.fringe == 4 * AA_W,
            "实测 ${on.fringe} 个，期望 ${4 * AA_W}（= 每列 m=±2 与 m=±3 各两个）"
        )
        report(
            "★ 抗锯齿③ 线心（|m| ≤ 1）纯色像素数两种模式精确相等，且 = 3W",
            off.core == on.core && on.core == 3 * AA_W,
            "关=${off.core}，开=${on.core}，期望 ${3 * AA_W}（AA 关的 |m|=2 那两条带是白的，但不在线心）"
        )
        // ④ 的参照系是**真实面积**（见 KDoc）。亮度差 204 = 白 255 − 背景 51。
        val analyticInk = AA_LUMA_SPAN * 4.0 * Math.sqrt(2.0) * AA_W
        val inkTol = 2.0 * AA_W + 2
        report(
            "★ 抗锯齿④ 墨量 == 窗内描边带的真实面积 × 亮度差（± 量化界 2W）",
            Math.abs(on.ink - analyticInk) <= inkTol,
            "实测 ${on.ink}，解析 ${"%.1f".format(analyticInk)}，允许 ±${"%.0f".format(inkTol)}"
                    + "（相差 ${"%.1f".format(on.ink - analyticInk)}；2W 是 4 个羽化像素 × 0.5 的 8 位量化界）"
        )
        // 对照组的墨量：硬边每列只有 |m| ≤ 2 那 5 个像素 ⇒ 5W × 204。
        // **它必须精确等于这个数**：多一列/多一圈都说明"关着的时候几何被外扩了"，
        // 而那正是 AA 关时最该逐像素不变的一件事。
        report(
            "★ 抗锯齿④ 对照：AA 关的墨量 == 5W × 亮度差（硬边几何没有被外扩）",
            off.ink == 5 * AA_W * AA_LUMA_SPAN,
            "实测 ${off.ink}，期望 ${5 * AA_W * AA_LUMA_SPAN}"
                    + "（比解析面积少 ${"%.1f".format(100.0 * (1 - off.ink / analyticInk))}%"
                    + " —— 这是硬边**像素中心采样**内接于斜带的必然结果，不是缺陷）"
        )

        // ---------------------------------------------------------------
        // 1px 细线：**方向断言**
        //
        // 设计文档 §7.1 对细带留了 ±10% 的容差，因为 §4.3 那条公式**已知可能偏厚**
        // （斜坡宽度 1 个设备像素、而线本身还不到 1 个像素时，线性斜坡不再等于面积）。
        // 这条容差要暴露的正是那件事：**一旦它失败，收口是把 aEdge 扩到三分量**，
        // 而不是"把容差放宽"。实测（两种相位 × 两种模式四种组合）它们的墨量
        // **恰好都是 400 列 × 204** ⇒ 本条当前不是"勉强通过"，10% 在这里没有兜住任何东西。
        //
        // ★ **光有墨量比还不够**：把 AA 整条做成空操作时，细带的墨量**依然是 1.000 px/列**
        //   （硬边与羽化在这个几何上等价：1px 线的覆盖率正好是"斜坡面积 = 1"），
        //   于是那两条 ④b 在变异下**照常通过**，而读数行明写着"纯色 400 / 过渡 0"
        //   ——**读数看得见、判据看不见**。所以补一条"AA 开的那条**真的有羽化**"。
        //   它挡的就是"AA 整条没生效"这件事（与探针一的 `C ≠ A` 一起抓；实测 M1 两者同时倒）。
        //
        // ⚠️ 但它**只加在相位 .25 上**：相位 .50 上那条不可能有羽化——
        //   中线正好落在像素中心、带边正好落在像素边界 ⇒ 斜坡的采样就落在 `{1, 0}`
        //   两个端点值上，**一个中间值都不产生**（也就是说那条线 AA 开与 AA 关**逐像素相同**）。
        //   这是构造使然，不是缺陷；**它不承担"AA 生效"的举证**。
        //
        // ★ 相位 .50 那一对钉的是"**开 AA 不该把一条本来就对齐的线画歪**"，
        //   而这条判据**必须是纯色数/过渡数的相等**，不能只靠墨量比——
        //   **墨量比按构造看不见平移**：1px 线在**任意**相位下 `Σ cov = 1.0 px/列`
        //   （像素中心关于斜坡中心对称 ⇒ 采样和恒等于 1，把线挪多少都一样）。
        //   实测（只把 `.50` 的 AA 开那条画在 `+30.25f`，判据窗仍按 `+30f` 取，
        //   即"AA 把一条对齐的线画歪了 0.25 像素"）：**加这条之前 4 条断言全过、
        //   退出码 0**，而读数行已经从 `纯色 400，过渡 0` 变成 `纯色 0，过渡 800`
        //   ——又是"读数看得见、判据看不见"。加 `white`/`fringe` 相等之后它立刻倒。
        // ---------------------------------------------------------------
        println("  1px 细线（窗 x∈[$AA_THIN_WX0,$AA_THIN_WX1) y∈[线心−3,线心+4)，每列墨量的期望 = 1.0 px）：")
        // 相位表：标签、线心 y、以及该相位上"AA 开的那条按构造该不该有过渡像素"。
        val thinPhases = listOf(
            Triple("相位 .25", AA_THIN_Y_P25, true),
            Triple("相位 .50", AA_THIN_Y_P50, false),
        )
        val thin = Array(thinPhases.size) { arrayOfNulls<AaStats>(2) }
        for ((i, ph) in thinPhases.withIndex()) {
            val (label, baseY, expectFringe) = ph
            for ((j, aa) in listOf(false, true).withIndex()) {
                val yc = baseY + if (aa) 30f else 0f
                val s = f.aaStats(AA_THIN_WX0, yc.toInt() - 3, AA_THIN_WX1, yc.toInt() + 4, 0)
                thin[i][j] = s
                println(
                    "    $label AA=${if (aa) "开" else "关"}：线心 ${yc}，被断言的行 ${
                        yc.toInt() - 3
                    }..${yc.toInt() + 3}，"
                            + "墨量 ${s.ink}（${"%.3f".format(s.ink.toDouble() / (AA_THIN_WX1 - AA_THIN_WX0) / AA_LUMA_SPAN)} px/列）"
                            + "，纯色 ${s.white}，过渡 ${s.fringe}"
                )
            }
            val offThin = thin[i][0]!!
            val onThin = thin[i][1]!!
            val ratio = onThin.ink.toDouble() / offThin.ink
            report(
                "★ 抗锯齿④ 1px 细线（$label）：AA 开 / 关 的墨量在 [0.9, 1.1] 内",
                ratio in 0.9..1.1,
                "关=${offThin.ink}，开=${onThin.ink}，比值 ${"%.4f".format(ratio)}"
                        + "（失败即 §4.3 那条细带公式确实偏厚 ⇒ 该换三分量 aEdge，不是放宽容差）"
            )
            if (expectFringe) {
                report(
                    "★ 抗锯齿④ 1px 细线（$label）：AA 开的那条**真的有羽化**（过渡像素 > 0）",
                    onThin.fringe > 0,
                    "实测过渡 ${onThin.fringe} 个、纯色 ${onThin.white} 个"
                            + "（AA 关那条：过渡 ${offThin.fringe}、纯色 ${offThin.white}）"
                            + " —— 挡的是“AA 整条没生效”：那时墨量仍是 1.000 px/列（硬边与羽化在这个几何上等价）"
                )
            } else {
                // 相位 .50：无羽化是构造使然（见上面那段），所以钉的是**逐项相等**。
                // 这一条**不能省成只比墨量**：墨量比在任意相位下都恒为 1.0（对称性），
                // 对"AA 把线画歪了"完全隐形（实测把 AA 开那条挪 0.25 像素，只有它抓得住）。
                report(
                    "★ 抗锯齿④ 1px 细线（$label）：AA 开与关**逐项相等**（纯色数、过渡数都不变）",
                    onThin.white == offThin.white && onThin.fringe == offThin.fringe,
                    "开：纯色 ${onThin.white} / 过渡 ${onThin.fringe}，"
                            + "关：纯色 ${offThin.white} / 过渡 ${offThin.fringe}"
                            + "（这条挡的是“AA 把一条本来就对齐的线画歪”——墨量比对平移恒为 1.0，看不见它）"
                )
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // ★ 非等比缩放：声明的降级（压缩轴**真外缘之外**的羽化被切掉）
    // ------------------------------------------------------------------

    /**
     * 非等比缩放探针的判定（几何见 [drawAnisoProbeScene]）。
     *
     * <p><strong>只钉方向，不钉具体数值</strong>——与"旋转 `clipRect` 实际生效的是
     * 轴对齐包围盒"那条同类：底层手段按定义只有一个标量（`matrixScale()` 取两轴平均），
     * 于是"1 个设备像素"的外扩量在两条轴上必然差一个倍率。
     *
     * <p>两条判据互为对照，缺一不可：
     * <ul>
     *   <li><b>压缩轴</b>（x，scale 1）真外缘之外**没有**片元 —— 被切掉的是
     *       **外缘之外那 0.18 个设备像素**的羽化（⚠️ **不是"整条退化成硬边"**：
     *       带内的羽化照常画出来，实测剖面 `98:#CDCDCD` 就是覆盖率 0.75 的过渡像素）；
     *       若单独看这一条，它也可能是"AA 整条没生效"，所以——</li>
     *   <li><b>拉伸轴</b>（y，scale 10）真外缘之外**有**片元 —— 证明同一帧里
     *       AA 确实开着、几何确实外扩了，于是上面那条是**轴**的降级而不是开关坏了。</li>
     * </ul>
     *
     * <p>两个方向的相位都是挑过的（理由见 [ANISO_VX]），所以这条判据钉的是
     * "外扩量按哪个量算"这件事，而不是像素中心的运气。
     */
    private fun verifyAnisoScale(): Boolean {
        val f = grabFrame() ?: return false

        val vRow = 400
        val hCol = 400
        println("\n-- ★ 非等比缩放（gc.scale(1,10)）：压缩轴上的羽化退化 --")
        // ★ 打印的剖面**就是**被断言的剖面（整段，不是摘要）。
        println("  竖线（横向 = 压缩轴 x，scale 1）第 $vRow 行，x∈[90,116)：")
        println("    " + (90 until 116).joinToString(" ") { "%d:#%06X".format(it, f.rgb(it, vRow)) })
        println("  横线（横向 = 拉伸轴 y，scale 10）第 $hCol 列，y∈[170,232)：")
        println("    " + (170 until 232).joinToString(" ") { "%d:#%06X".format(it, f.rgb(hCol, it)) })

        // 压缩轴：真外缘在 xc ± 2（设备像素）。之外一个片元都不该有。
        val vBeyond = ArrayList<Int>()
        for (x in 90 until 116) {
            if (Math.abs(x + 0.5 - ANISO_DEVICE_X) > ANISO_TRUE_HALF_X && f.rgb(x, vRow) != background) {
                vBeyond.add(x)
            }
        }
        report(
            "★ 非等比缩放 压缩轴（x，scale 1）：真外缘之外的片元数 = 0（外缘之外那 0.18 设备像素的羽化被切掉）",
            vBeyond.isEmpty(),
            "实测 ${vBeyond.size} 个${if (vBeyond.isEmpty()) "" else "：x=$vBeyond"}"
                    + "（带内的羽化仍在：外缘之内那一档覆盖率 0.75 —— 所以不是“退化成硬边”）"
        )

        // 拉伸轴：真外缘在 yc ± 20。之外至少要有**一个**片元。
        val hBeyond = ArrayList<Int>()
        for (y in 170 until 232) {
            if (Math.abs(y + 0.5 - ANISO_DEVICE_Y) > ANISO_TRUE_HALF_Y && f.rgb(hCol, y) != background) {
                hBeyond.add(y)
            }
        }
        report(
            "★ 非等比缩放 拉伸轴（y，scale 10）：真外缘之外**有**片元（羽化完整）",
            hBeyond.isNotEmpty(),
            "实测 ${hBeyond.size} 个${if (hBeyond.isEmpty()) "（这条失败说明同一帧里 AA 没生效，而不是缩放降级）" else "：y=$hBeyond"}"
        )
        return true
    }

    /** 收尾：汇总 + 落退出码。放在最后单独一帧，好让探针阶段也进同一份摘要。 */

    private fun finish() {
        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }
        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    // ------------------------------------------------------------------
    // 回读
    // ------------------------------------------------------------------

    /** 回读整幅帧缓冲；尺寸不可用时返回 null（此时**不**推进阶段，见 [verifyAll] 的说明）。 */
    private fun grabFrame(): Frame? {
        val bridge = transfer ?: return null
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return null
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        return Frame(buf, w, h)
    }

    /**
     * 一帧的回读结果，按**用户坐标**取色（原点左上、y 向下）。
     *
     * <p>取色口径与主阶段里那个局部 `pixelAt` 逐字一致——这里刻意把那份换算收成**一处**：
     * 探针与主场景若各写一份行序翻转，两边就可能一个对一个错，
     * 而"一个对一个错"看起来只会像"探针的几何画歪了"。
     */
    private class Frame(private val buf: ByteBuffer, val w: Int, val h: Int) {

        fun r(x: Int, y: Int): Int = channel(x, y, 0)

        fun g(x: Int, y: Int): Int = channel(x, y, 1)

        fun b(x: Int, y: Int): Int = channel(x, y, 2)

        private fun channel(x: Int, y: Int, c: Int): Int {
            // glReadPixels 行序自下而上；用户坐标 y 向下，故翻转回读行号。
            val i = ((h - 1 - y) * w + x) * 4 + c
            return buf.get(i).toInt() and 0xFF
        }

        /** 取一块矩形里的 RGB（不含 alpha），按行优先。越界部分夹紧到帧缓冲内。 */
        fun snapshot(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
            val xs = x0.coerceAtLeast(0)..(x1 - 1).coerceAtMost(w - 1)
            val ys = y0.coerceAtLeast(0)..(y1 - 1).coerceAtMost(h - 1)
            val out = IntArray(xs.count() * ys.count())
            var i = 0
            for (y in ys) {
                for (x in xs) {
                    out[i++] = (r(x, y) shl 16) or (g(x, y) shl 8) or channel(x, y, 2)
                }
            }
            return out
        }

        /** 逐通道打印一串行上的颜色，给报告当读数用。 */
        fun rowColors(x: Int, y0: Int, rows: Int): String =
            (y0 until y0 + rows).joinToString(" ") {
                "y$it=#%06X".format(
                    (r(x, it) shl 16) or (g(
                        x,
                        it
                    ) shl 8) or channel(x, it, 2)
                )
            }

        /** 取一块矩形的 RGB（不含 alpha）。 */
        fun rgb(x: Int, y: Int): Int = (r(x, y) shl 16) or (g(x, y) shl 8) or b(x, y)

        /** 数一块矩形（半开区间）里等于给定 RGB 的像素个数。 */
        fun countIn(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
                for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                    if (rgb(x, y) == rgb) n++
                }
            }
            return n
        }

        /** 数一块矩形（半开区间）里**不等于**给定 RGB 的像素个数。 */
        fun countNotIn(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
                for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                    if (rgb(x, y) != rgb) n++
                }
            }
            return n
        }

        /** 把一块矩形逐行打成可读的读数（给报告用）。 */
        fun boxColors(x0: Int, y0: Int, x1: Int, y1: Int): String =
            (y0.coerceAtLeast(0) until y1.coerceAtMost(h)).joinToString("\n") { y ->
                "    y$y: " + (x0.coerceAtLeast(0) until x1.coerceAtMost(w)).joinToString(" ") {
                    "%06X".format(rgb(it, y))
                }
            }

        /**
         * 一块抗锯齿测量窗的读数（口径见 [verifyAntialias]）。
         *
         * <p>`m = y − x − c` 在**像素中心**上恰好取整数（两个 `0.5` 相消），
         * 所以"第几级覆盖"完全由它决定：`|m| ≤ 1` 恒为满覆盖、`|m| = 2 / 3` 是两圈羽化、
         * `|m| ≥ 4` 恒为背景。线心判据取 `|m| ≤ 1` 而不是"窗中间那几行"，
         * 正是因为这个量**不随窗的位置漂移**。
         *
         * @param fringe 既非背景也非线色的像素个数（① ② 的读数）
         * @param core   线心那三条对角带（`|m| ≤ 1`）上的**纯色**像素个数（③ 的读数）
         * @param ink    墨量 `Σ (red − 背景)`，饱和度口径见 [AA_LUMA_SPAN]（④ 的读数）
         * @param white  窗内纯线色像素总数（**读数，不是判据**：它必然随开不开 AA 而变）
         */
        fun aaStats(x0: Int, y0: Int, x1: Int, y1: Int, c: Int): AaStats {
            var fringe = 0
            var core = 0
            var ink = 0
            var white = 0
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
                for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                    val lum = r(x, y)
                    val color = (lum shl 16) or (g(x, y) shl 8) or b(x, y)
                    if (color == AA_LINE_RGB) {
                        white++
                        if (Math.abs(y - x - c) <= 1) core++
                    } else if (color != (AA_BG_LUMA or (AA_BG_LUMA shl 8) or (AA_BG_LUMA shl 16))) {
                        fringe++
                    }
                    ink += lum - AA_BG_LUMA
                }
            }
            return AaStats(fringe, core, ink, white)
        }

        /**
         * 一块窗内每种颜色各有多少像素（给报告当读数用）。
         *
         * <p>顺序是**按像素数降序**——这条不只是排版：① ② 的失败可读性全靠它
         * （④ 的 KDoc 也直接引用这里印出的 `#EDEDED×200`），所以**排序方式是承重信息**。
         * 并列时次序由 `HashMap` 的迭代序决定，**不承诺颜色序**，别照着它写断言。
         */
        fun aaHistogram(x0: Int, y0: Int, x1: Int, y1: Int): String {
            val hist = HashMap<Int, Int>()
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
                for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                    val color = rgb(x, y)
                    hist[color] = (hist[color] ?: 0) + 1
                }
            }
            return hist.entries.sortedByDescending { it.value }
                .joinToString(" ") { "#%06X×%d".format(it.key, it.value) }
        }
    }

    /** [Frame.aaStats] 的结果（`private class` 里也能声明，只是不导出）。 */
    private class AaStats(val fringe: Int, val core: Int, val ink: Int, val white: Int)

    /** 两个同形快照里不同的像素个数。 */
    private fun differingPixels(a: IntArray, b: IntArray): Int {
        var n = 0
        for (i in a.indices) {
            if (a[i] != b[i]) n++
        }
        return n
    }

    override fun stop() {
        transfer?.dispose()
    }
}
