package com.bingbaihanji.xuan.example.demo

import com.bingbaihanji.xuan.example.textFont
import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.renderer.PickHit
import com.bingbaihanji.xuan.renderer.PickRegistry
import com.bingbaihanji.xuan.util.Rect
import com.bingbaihanji.xuan.view.MainView
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Insets
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.system.exitProcess

/** 窗口的**逻辑**尺寸。绘制区是设备像素，比它大（125% 缩放下 900 → 1113）。 */
private const val SCENE_W = 900.0
private const val SCENE_H = 700.0

/**
 * 合成事件自检的**系统属性名**。
 *
 * <p>提成常量是因为有**两个**文件读它：本文件的 [SELFTEST]，以及 `DemoChart.kt` 里
 * 那个（它只用来挡住两个纯观测计数器）。两处各写一份字符串的话，改名字那天
 * 一个跟着改、另一个静默地永远读不到——那样 `DemoChart` 的观测计数器恒为 0，
 * 而自检会把它读成"图表没画出来"。
 *
 * <p><strong>★ 但共用属性名还不够</strong>：解析也必须共用同一个判定，否则就是
 * "属性名一样、认得的值不一样"——那正是 [selfTestEnabled] 的文档里记着的那次假缺陷。
 * 两处引用同一个常量只解决了"名字"，判定由 [selfTestEnabled] 解决。
 */
internal const val SELFTEST_PROPERTY = "xuan.demo.selftest"

/**
 * **MSAA 采样数的启动属性**（`-Dxuan.demo.msaa=4`）。**默认 0（单采样）。**
 *
 * <p><strong>★ 为什么它是启动属性而不是菜单项</strong>：采样数是**帧缓冲的属性**，
 * `GLCanvas` 只有 `getMsaa()`、**没有 setter**（实测过 jar 的公开签名）——所以
 * **运行期改它没有任何效果**。菜单里那条只能是个**禁用的说明项**，见 [buildMenuBar] 的
 * 「抗锯齿」菜单；真正要开 MSAA 只能在这里给，然后重启。
 *
 * <p>已知取值（openglfx 的约定）：正数是具体采样数、**负数是"取最大可用采样数"**
 * （`GL_MAX_SAMPLES`，本机 32）、0 才是单采样。**别把负数读成"关"**——
 * 它是多采样画布（这一点由 `MsaaVerifier` 钉着）。
 *
 * <p><strong>它管的是填充边缘</strong>；描边与图表系列的抗锯齿由
 * [antialias]（运行期、菜单里可切）负责。两者互补，见 `CLAUDE.md` 的「抗锯齿」一节。
 */
internal const val MSAA_PROPERTY = "xuan.demo.msaa"

/** [MSAA_PROPERTY] 的解析结果。非法值**出声**（而不是静默退回 0）。 */
private val DEMO_MSAA: Int = run {
    val raw = System.getProperty(MSAA_PROPERTY) ?: return@run 0
    val v = raw.toIntOrNull()
    if (v == null) {
        System.err.println("[demo] 属性 $MSAA_PROPERTY=$raw 不是整数，按 0（单采样）处理")
        0
    } else {
        v
    }
}

/** [SELFTEST_PROPERTY] 的原始值。**只读一次**，理由见 [SELFTEST_ON]。 */
private val SELFTEST_RAW: String? = System.getProperty(SELFTEST_PROPERTY)

/**
 * ★ **自检开关的唯一判定**（`-Dxuan.demo.selftest`）——**两个读它的文件都走这一个函数**。
 *
 * <p><strong>为什么必须是一个共用的判定，而不是"两边各写一份、只共用属性名"</strong>：
 * `312054c` 把本文件这一侧的判据放宽成认 `1` **与** `true`，而 `DemoChart.kt` 那一侧
 * 没跟着改（仍是 `System.getProperty(...) == "1"`）。于是同一份属性名下有了**两份解析
 * 不同的副本**，后果是：`-Dxuan.demo.selftest=true` 下**脚本照跑，而三个图表观测一个
 * 都不写**——⑩ 的末尾探针恒 +0、⑪ 的身份恒 0、`selfTestLastDrawnKind` 恒 -1，
 * 而**绘制与拾取号完全正常**。自检于是报出一堆"图表好像坏了"的读数，
 * 真正坏的是开关解析。**共用属性名 ≠ 共用判定**，这一条正是栽在这里的。
 *
 * <p>这条曾经被记成"间歇性缺陷、间歇率约 1/17"（⑩⑪ 全倒、身份全 0）。复核把它推翻了：
 * 那次唯一的差别就是**开关写的是 `=true` 而不是 `=1`**，而那一批运行里只有一个日志是
 * 这么跑的。旧日志 `/tmp/trueflag.log` 里那 **6 行「等待超预算」**（⑩ 1 行 + ⑪ 5 行）
 * 是"观测写入被关掉"的指纹——**异常假说下不会有它们**（帧一停，`frameCount` 就不再涨，
 * 等待条件既不会成立、也永远用不满帧预算，脚本只会挂在看门狗那一条上）。
 *
 * <p>防复发：启动时由 [XuanDemoApp.verifySelfTestFlagsAgree] 判一次"两边一致"。
 */
internal fun selfTestEnabled(): Boolean = SELFTEST_ON

/**
 * 判定本身。**解析一次、也只出声一次**——[selfTestEnabled] 会被两个文件各要一次，
 * 而"认不出来的值"那行警告不该印两遍。
 *
 * <p>认 `1` 与 `true`（大小写不敏感）。**认不出来的值要出声**：`-Dxuan.demo.selftest=yes`
 * 那种写法下自检一条都不跑、进程进交互模式永不退出——症状与"看门狗没兜住的挂死"
 * 一模一样（没输出、不退出），而这条路径**看门狗也兜不到**（它根本没被启动）。
 * 所以这里把"认不出来的值"打成一行刺眼的 stderr，而不是静默地当没开。
 */
private val SELFTEST_ON: Boolean = run {
    val raw = SELFTEST_RAW
    val on = raw != null && (raw == "1" || raw.equals("true", ignoreCase = true))
    if (raw != null && !on) {
        System.err.println(
            "[自检-合成] 属性 $SELFTEST_PROPERTY=$raw 不是能识别的真值（用 1 或 true）；" +
                    "**自检不会运行**，窗口会进入正常交互模式（不会自己退出）"
        )
    }
    on
}

/**
 * 本文件的开关（= [selfTestEnabled]），**内容是 `false` 时自检一行都不跑**
 * （连 [XuanDemoApp.frameCount] 都不自增）——这不是"少跑点"，而是生产路径**零开销**
 * 这条承诺：自检的所有状态都只在 `if (SELFTEST)` 里被碰。
 *
 * <p>为什么要有它：这个 demo **没有像素校验器**（画面取决于用户点了哪儿，没有可断言的
 * 判据），所以"画出来的图形对不对、点得中不中"**只有静态证据**。
 * 合成事件走的是 `wireMouse` 接的那四条真实处理器（`ClickVerifier` 已证明这条路通），
 * 于是交互闭环第一次有了运行时证据。
 *
 * <p><strong>★ 它到底证了哪几环（说准，别多承诺）</strong>：它证的是**合成事件真能到达
 * `wireMouse` 那四个处理器**，以及**那四个处理器里的四件事**——坐标换算（由 ① 里那条
 * **设备坐标绝对值**钉住；按比例量的断言在整体等比缩放下不变，删掉或写成 `* s * s`
 * 都照样全绿）、拾取往返、回调交付、状态更新。
 *
 * <p>它**没有**证、也不该被当成证了的：<br>
 * ① **真实鼠标事件能否到达画布**——`fireEvent` 不做命中测试，合成事件绕过 JavaFX 的拾取；
 * 那一条由 `ClickVerifier` 的 `Robot` 探针管（走操作系统事件）。<br>
 * ② **画面对不对**——这里断言的全是状态，本 demo 也没有像素校验器。<br>
 * ③ hover 那条（`pickAsync`）路径——本脚本只驱动点击与拖拽。
 */
private val SELFTEST: Boolean = selfTestEnabled()

/** 自检的看门狗超时（秒）。见 [XuanDemoApp.startSelfTestWatchdog]。 */
private const val SELFTEST_TIMEOUT_SECONDS = 60L

/**
 * 自检退出时等 [XuanDemoApp.stop] 跑完 `dispose` 的上限（秒）。
 * 超了就照退，只多打一行警告——**不能让清理把退出本身拖住**。
 */
private const val DISPOSE_TIMEOUT_SECONDS = 5L

/** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是 (0.2,0.2,0.2)。 */
private const val BG = 0xFF23262B.toInt()

/** 选中高亮色。 */
private const val HIGHLIGHT = 0xFFFFEB3B.toInt()

/** 小于这个位移（设备像素）的一次按下-抬起算**单击**，否则算拖拽。 */
private const val CLICK_SLOP = 4.0

/** 虚线预览的实段/空段长度（设备像素）。见设计文档 §4.2.2 的"预览的虚线参数"。 */
private const val PREVIEW_DASH_ON = 6f
private const val PREVIEW_DASH_OFF = 4f

/** 预览曲线（圆/椭圆）的折线段数。固定值——预览不追求与 `Gc` 的细分规则一致。 */
private const val PREVIEW_CURVE_SEGMENTS = 48

/** 八色预设色板。 */
private val PALETTE = intArrayOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt()
)

/** 文本模式的字号档位。 */
private val FONT_SIZES = floatArrayOf(14f, 22f, 32f, 48f)

/** 文本模式的样本串（按落字序号轮换）。不随机——画面要可复现。 */
private val TEXT_SAMPLES = arrayOf(
    "Xuan immediate-mode text",
    "中文文本，基线对齐",
    "Mixed 中英混排 123",
    "这是一行用来观察推进宽度的较长文本"
)

private enum class Mode(val label: String) { DRAW("绘图"), TEXT("文本"), CHART("图表") }

/**
 * 一个图形 + 它的拾取 ID。
 *
 * <p>**ID 为什么不放进 [Shape]**：`Shape` 是**不可变快照**，而拾取 ID 是在图形被创建
 * **之后**由 `pickRegistry` 分配的。给 `Shape` 加一个可变字段会破坏"整表替换"这条
 * 跨线程契约（GL 线程读到的列表就不再是自洽的了）。所以 ID 放在外面这一层。
 *
 * @param pickId 0 表示**不参与拾取**（但仍照常画）。成因有二：**注册失败**
 *               （`gc()` 为 null 时 `id = 0`，见 [commitShape] 里写明的降级），
 *               或**刻意不注册**（[commitText] 落下的文本就是这样，见那里的说明）
 */
data class Placed(val shape: Shape, val pickId: Int)

class XuanDemoApp : Application() {

    private var transfer: FXGLTransfer? = null
    private val status = Label("拖动鼠标画一个矩形").apply {
        padding = Insets(6.0, 10.0, 6.0, 10.0)
        style = "-fx-font-size: 13px; -fx-text-fill: #1b1b1b;"
    }

    // ---- 跨线程状态。**分三类，别混。** ----
    //
    //   ① **引用快照**（`shapes` / `selection` / `marqueePending`）：整表替换、
    //      从不原地改，所以 GL 线程读到的永远是一个自洽的快照；
    //      ★ **写者有两处，不是一处**（终审补准的）：JavaFX 线程（`commitShape` /
    //      `commitText` / `deleteSelected` / `clearAll`），以及**自检模式下 GL 线程上的
    //      那个钩子**（`consumeMarquee` 里调 `removeShapes` —— 见那里的 KDoc）。
    //      后者是**例外**，只在自检路径上（生产路径被 `if (SELFTEST …)` 挡着），
    //      而且它是一次**读-改-写**（`shapes.set(shapes.get().filter { … })`），
    //      与 JavaFX 线程那侧交错时理论上会丢更新——真丢了会让第 ⑩ 条的判据响亮失败，
    //      不是静默错画，所以本期只把这句话说准，不动结构；
    //   ② **需要跨线程的标量**（`mode` / `kind`）：`@Volatile`；
    //   ③ **分别发布的标量**（`dragStart*` / `marquee[XYWH]`）：也 `@Volatile`，
    //      但它们是**逐字段发布、成组读取**的，所以允许**单帧不一致**
    //      （例如"新的起点 + 旧的轨迹"），只影响那一帧的预览观感。
    //      **不要把它们说成"快照"**——那条承诺比实际强。

    /** 已画完的图形。整表替换，不做原地改。 */
    private val shapes = AtomicReference<List<Placed>>(emptyList())

    /** 选中集（pickId）。 */
    private val selection = AtomicReference<Set<Int>>(emptySet())

    /**
     * 当前模式与图形种类。**这两个真的跨线程**：`mode` 被 GL 线程的
     * [drawScene] / [drawDragPreview] 读、也被 JavaFX 线程的 [onDrag] / [onRelease] 读，
     * `kind` 被 [drawDragPreview] 读，而它们由菜单在 JavaFX 线程写——所以需要 `@Volatile`。
     */
    @Volatile
    private var mode = Mode.DRAW
    @Volatile
    private var kind = ShapeKind.RECT

    // ---- 以下是**只在 JavaFX 线程**读写的状态，因此**不需要** `@Volatile`。 ----
    //
    //   它们的唯一读点是 [commitShape] 与 [commitText]，两者都在
    //   JavaFX 线程上（由鼠标事件 / 菜单触发）；`fontSize` 虽然要装进
    //   `TextShape` 再交给 GL 线程使用，但那个交接发生在"构造快照"这一步
    //   （装好之后的 `TextShape` 是**不可变**的），而不是靠这个字段跨线程可见。
    //   **别顺手给它们加 `@Volatile`**——那会让人以为它们跨线程，从而看不出
    //   真正的跨线程字段是哪两个。

    /** 新画图形用的样式 / 颜色 / 线宽。**只在 JavaFX 线程读写。** */
    private var style = ShapeStyle.FILL_AND_STROKE
    private var color = PALETTE[0]
    private var lineWidth = 2f

    /**
     * 描边与图表系列的**解析式抗锯齿**开关（菜单「抗锯齿」里的那一项）。
     *
     * <p>**`@Volatile`**：菜单在 JavaFX 线程写，而 [drawScene] 在 GL 线程每帧读。
     * 它**不是** `Gc.antialias` 本身——那是个 `save`/`restore` 状态，**每帧都会被重置**，
     * 所以只能每帧从本字段拷进去一次（见 [drawScene] 的开头）。
     *
     * <p>**默认关**，与库的默认值一致：开了之后描边边缘会多出半透明像素，
     * 而本仓库的像素校验器有一批精确到 ±0 的期望值。见 `CLAUDE.md` 的「抗锯齿」一节。
     */
    @Volatile
    private var antialias = false

    /** 文本模式的字号。**只在 JavaFX 线程读写。** */
    private var fontSize = FONT_SIZES[1]

    /**
     * 落字序号，用来按序轮换 [TEXT_SAMPLES]。**只在 JavaFX 线程读写，所以不需要 `@Volatile`。**
     *
     * <p>它的唯一读点是 [commitText]，而那是在 JavaFX 线程上被调用的。它**不会**进 GL 线程
     * ——真正交给 GL 线程的是已经装好的 [Shape.TextShape] 快照（`text` 已经定死），
     * 序号本身与绘制无关。
     */
    private var textSeq = 0

    /**
     * 三个"当前模式是什么"的 JavaFX 属性，**只为菜单的启用/禁用服务**。
     *
     * <p>为什么不直接绑到 [mode] 那个 `@Volatile` 字段：JavaFX 的 `bind` 要的是
     * `ObservableValue`，而 `@Volatile` 的语义是"跨线程可见"而不是"可观察"。
     * 两者服务的目的不同，所以各留一份、在 [modeItem] 的动作里同步设置。
     * 这一份**只在 JavaFX 线程上读写**，因此不需要 `@Volatile`。
     */
    private val drawModeActive = SimpleBooleanProperty(true)
    private val textModeActive = SimpleBooleanProperty(false)
    private val chartModeActive = SimpleBooleanProperty(false)

    /** 当前图形是不是直线（直线恒描边，样式菜单对它没有意义）。**只在 JavaFX 线程读写。** */
    private val styleIsLine = SimpleBooleanProperty(false)

    // ---- 以下是**合成事件自检**（`-Dxuan.demo.selftest=1`）专用的状态 ----
    //
    //   **运行期**的状态全部只在 [SELFTEST] 为真时被读写（`frameCount` 的自增、
    //   交付计数、钩子标志），生产路径一帧都不碰它们——这是"默认关闭 ⇒ 零开销"
    //   这条承诺的具体形态：不是少跑几个分支，而是**没有任何一处生产代码读这些字段**
    //   （除了 `if (SELFTEST)` 那一层）。
    //
    //   **唯一的一处例外、明写在这里免得读者以为漏了**：下面三个菜单项引用
    //   （`modeMenuItems` / `styleMenuItems` / `chartMenuItems`）是在 [buildMenuBar]
    //   里**无条件** put 的——它们是**启动时一次性**的记账，不进任何热路径
    //   （每帧跑的是 `drawScene`，它一次都不读它们）。

    /**
     * 菜单项引用，**只给自检用**（生产路径一次都不读）。
     *
     * <p>第 ④ 条与第 ⑪~⑭ 条要按"**菜单动作**"驱动，不能去改 `mode` / `selectedKind`
     * / `style` / `kind` 常量：改常量绕过了"菜单 → 字段 → 下一帧重建"这条**端到端链路**，
     * 而那条链才是要验的东西（比如 `chart()` 变成永远返回缓存时，改常量照样全绿，
     * 而菜单变死）。`MenuItem.fire()` 是纯 JavaFX 线程逻辑，能直接驱动它
     * （顺带：它连**禁用**状态都不判，"菜单被灰掉了"这件事得靠断言里的行为看出来）。
     *
     * <p>它们是**启动时一次性 put 的**（四处 `buildMenuBar` 里的记账），
     * 每帧一次都不碰——见上面那段"唯一的一处例外"。
     */
    private val modeMenuItems = HashMap<Mode, RadioMenuItem>()
    private val styleMenuItems = HashMap<ShapeStyle, RadioMenuItem>()
    private val chartMenuItems = ArrayList<RadioMenuItem>()

    /**
     * 图形种类的菜单项。**只给自检用**（第 ⑤ 条要切到"圆"验它那条新尺规，
     * 第 ⑦ 条要切回"矩形"；而"切图形"在 [resetDragState] / [cancelAnchor] 的 KDoc 里
     * 是四条语义路径之一，所以它必须是**生产路径**上的那个动作，
     * 不能直接给 `kind` 字段赋值——见上面那段说明）。
     */
    private val kindMenuItems = HashMap<ShapeKind, RadioMenuItem>()

    /** 已在 [drawScene] 里执行过的帧数。**只在自检模式下自增**（见 [SELFTEST]）。 */
    @Volatile
    private var frameCount = 0

    /**
     * 自检专用的**末尾探针**：[drawDragPreview] 的两点式**虚线分支真的画完了**的帧数。
     *
     * <p><strong>为什么在调用方、不在被调的那个绘制里</strong>：2026-09-28 之前这里调的是一支
     * demo 自带的虚线助手（`strokeDashedPolyline`），它的闸门是 `DemoShapeMath.kt` 里那几条
     * **判据的单元测试**（只比较布尔值、不跑循环）；现在改调 `Gc.strokePolyline`，
     * 弧长切分在库里、有 `PathVerifier` 第 8 个变体按像素钉着——**但那个变体证的是
     * "虚线画得对"，证不了"这一段接线通了"**：`drawDragPreview` 跑在 GL 线程上，
     * 帧缓冲读不回来，且 GL 线程的异常在本项目**被 openglfx 的原生回调吞掉**
     * （"少画了一帧"与"这一帧抛了异常"在画面上长得一样）。所以这个探针仍然需要。
     *
     * <p>它记的是"**锚点已定 → 鼠标移动过 → 轮廓非退化 → 虚线描边调用返回了**"这一整条链：
     * 锚点刚设上时 `previewX/Y` 还等于锚点本身，RECT 的轮廓在那里是零宽/零高
     * （[twoPointPreviewOutline] 返回 null），所以**这一格只在鼠标真的移动过之后才会涨**。
     *
     * <p>与 `DemoChart.selfTestDrawnFrames` 同一形态：只在 [SELFTEST] 打开时自增，
     * **没有任何绘制逻辑读它**。
     */
    @Volatile
    private var previewDashedFrames = 0

    /** 拾取回调交付的命中 / 未命中次数。用来核对"回调真的被交付了"，而不只是"状态栏碰巧对"。 */
    private var pickHitCount = 0
    private var pickMissCount = 0

    /**
     * 自检第 ⑩ 条的一次性钩子：让 [consumeMarquee] **在拾取读回之后、活快照之前**
     * 执行一次"删除"。完整的时序理由见那个钩子所在的注释。
     */
    @Volatile
    private var selfTestDeleteAfterReadback = false

    /** 上面那个钩子真的执行了几次（GL 线程写、JavaFX 线程读）。**必须 >0 才说明第 ⑩ 条测的是那条路径**。 */
    @Volatile
    private var selfTestDeleteHookRuns = 0

    /**
     * 第 ⑩ 条：**那一次读回到底命中了哪些号**（钩子在 GL 线程上顺手记下的）。
     *
     * <p>没有它，⑦ 的判别力挂在一个没人断言的前提上：`gc.pickRect` 这次若返回**空**，
     * "活快照过滤"根本没参与运算（`ids` 空 ⇒ `selection` 空 ⇒ "新图形没被高亮"真），
     * 而钩子执行过、号复用过、点中过 D 全都成立 ⇒ **⑦ 空转通过**。所以 `ok` 里必须有
     * "读回里含 `selfTestDId`"这一项——它把"过滤把号滤掉了"与"这次压根没读到号"分开。
     */
    @Volatile
    private var selfTestHookHitIds: List<Int> = emptyList()

    /** 第 ⑨ 条的两个前提：删之前有几个图形、删除那一刻状态栏说了什么。 */
    private var selfTestDeleteBefore = -1
    private var selfTestDeleteStatus = ""

    /** 被测的桥接器（① 要拿它算 `deviceScale`，见那条断言的说明）。 */
    private var selfTestBridge: FXGLTransfer? = null

    /** 看门狗的收工信号（正常收尾与超时各一次）。 */
    private val selfTestDone = CountDownLatch(1)

    /**
     * 每一步开始前记下的交付计数基准，用来判**这一次**交付了几条。
     *
     * <p>为什么不用累计值的绝对值：这个计数器是全脚本累计的，写死期望值会随"前面某条
     * 也点空过"而变。实测第一版就在这上面栽了——第 ④ 条自己就是一次未命中，
     * 于是第 ⑨ 条那句 `pickMissCount == 1` 恒为假。
     */
    private var selfTestHitBefore = 0
    private var selfTestMissBefore = 0

    /** ① 的虚线预览探针基准（见 [previewDashedFrames]）。 */
    private var selfTestPreviewBefore = 0

    /**
     * ⑥（`Esc` 取消）的前提：按 `Esc` 之前有几个图形。
     *
     * <p>它与 ⑨ 的 `selfTestDeleteBefore` 是**同一种做法**——把"这一步之前有几个"
     * 记下来再断言"之后还是几个"，否则"压根没画出来任何东西"与"画了又被 `Esc` 正确取消"
     * 在读数上分不开（那正是本仓库"被静默跳过的断言"的形态）。
     */
    private var selfTestSizeBeforeEsc = -1

    /** ⑦（两点式上拖拽什么都不做）的前提：拖之前有几个图形。理由同 [selfTestSizeBeforeEsc]。 */
    private var selfTestSizeBeforeDrag = -1

    /** 模式切换后同步那三个属性。**只在 JavaFX 线程调用。** */
    private fun syncModeProperties() {
        drawModeActive.set(mode == Mode.DRAW)
        textModeActive.set(mode == Mode.TEXT)
        chartModeActive.set(mode == Mode.CHART)
    }

    /** 拖拽预览：起点（设备像素）。NaN 表示没有正在进行的拖拽。 */
    @Volatile
    private var dragStartX = Float.NaN
    @Volatile
    private var dragStartY = Float.NaN

    /**
     * 拖拽轨迹点：**每次追加都新建数组**（`@Volatile` 只保证引用可见，不保证数组内容，
     * 原地 append 会让 GL 线程读到"长度已改、元素没写完"的半截数组）。
     *
     * <p><strong>取舍记录</strong>：这条写法的代价是 O(n²)——每个拖拽事件一次
     * `copyOf(size + 2)`，一笔 n 个事件的拖拽累计约 n² 次 float 拷贝。
     * 量级上**可忽略**：10000 个事件（十秒连续涂鸦）累计约 **10⁸ 次 float 拷贝**
     * （4×10⁸ 字节 = 400 MB 的 `arraycopy`），摊在那十秒里约 40 ms。
     *
     * <p>**不用"预分配缓冲 + 计数"去换掉它**，虽然那是 O(1) 摊还。真正的代价**不是** JMM
     * （几何级数扩容仍可保持"每次整体替换、不共享可变状态"），而是它引入了
     * **"数组长度 ≠ 点数"** 这个新状态：一旦两者不同步，产出的就是**几何被静默截断
     * 或多读一段**——正是本项目最防的那类"画面看起来正常"。用显而易见的正确换 1% 的
     * 病态路径开销，这个交易对 demo 划算。
     *
     * <p>真要做实时长轨迹，那时该做的是**降采样而不是换发布协议**。
     */
    @Volatile
    private var trajectory = FloatArray(0)

    /**
     * **已定第一点**的锚点（两点式图形专用）。`NaN` 表示没有。
     *
     * <p><strong>为什么是独立的字段、不复用 [dragStartX]</strong>：`onPress` 会**覆盖**
     * `dragStartX`，而锚点必须**跨两次点击活着**。初稿把它挂在 `dragStartX` 上，
     * 那会让"第二次点击提交之后紧接着的那次抬起"又被当成"第一点"——
     * 因为提交时 `dragStartX` 被清成 NaN、抬起时 `moved` 就是 0。
     *
     * <p>只有四条路径能清它：**提交**、`Esc`、**切模式**、**切图形**——四条**全部**
     * 走 [cancelAnchor]，而那个函数的调用点**恰好也只有这四处**。
     * （这条曾经**不成立**：清锚点一度寄居在 [resetDragState] 里，而那条函数还被
     * 右键释放、两点式上的拖拽释放、文本释放调用，于是那三个手势会**顺带把
     * 用户手里的第一点丢掉**。拆开之后"四条"才是字面成立的。）
     */
    @Volatile
    private var anchorX = Float.NaN
    @Volatile
    private var anchorY = Float.NaN

    /**
     * 当前鼠标位置（设备像素）。**只为虚线预览存在。**
     *
     * <p>它由新注册的 `MOUSE_MOVED` 写——**没有键按下时也要能跟着鼠标走**，
     * 而 `MOUSE_DRAGGED` 只在按键期间才有。鼠标移动事件可以高达 1000 Hz，
     * 但**一次 volatile float 写是零成本的**，不构成热路径问题。
     * 只在"已定第一点"时被读；其余时候写了没人看。
     */
    @Volatile
    private var previewX = Float.NaN
    @Volatile
    private var previewY = Float.NaN

    /**
     * 待框选的矩形（左, 上, 宽, 高，设备像素）。`marqueeW` 为 NaN 表示没有框选在进行。
     *
     * <p>这四个是**分别发布**的（见类顶部第 ③ 类），所以 GL 线程可能读到
     * "新的 `marqueeW` + 旧的 `marqueeH`"——**允许**，只影响那一帧选框的高度。
     * 真正跨线程交付给 GL 线程的那个矩形走的是下面 [marqueePending]（一个不可变对象），
     * **没有**这个问题——这个对照正是"为什么交付用一个对象、而预览用四个标量"的理由。
     */
    @Volatile
    private var marqueeX = Float.NaN
    @Volatile
    private var marqueeY = 0f
    @Volatile
    private var marqueeW = Float.NaN
    @Volatile
    private var marqueeH = 0f

    /** 待处理的框选请求（GL 线程消费）。**用对象快照，不用上面那四个标量。** */
    private val marqueePending = AtomicReference<Rect?>(null)

    /**
     * 启动一次性自检：**两份 `SELFTEST`（本文件的与 `DemoChart` 的）必须是同一个值**。
     *
     * <p>它防的是历史上有过的那次真事故：两边**属性名相同、解析不同**
     * （`XuanDemo` 认 `1` 与 `true`，`DemoChart` 只认 `1`），于是
     * `-Dxuan.demo.selftest=true` 下脚本照跑、而图表那三个观测值一个都不写——
     * 自检报出来的却是一堆"图表好像坏了"的读数（⑩ 探针恒 +0、⑪ 身份恒 0），
     * 真正坏的是开关。**当场自曝**比让下一个人花一整轮去查"图表渲染"划算得多。
     *
     * <p><strong>★ 说清楚这个检查现在治的是什么</strong>：两侧的判定都已经收进
     * [selfTestEnabled] 一个函数了，所以它**在今天的代码上是结构恒真的**——
     * 它不会、也不可能在今天报出不一致。它的价值只在**将来**：哪天有人给某一侧
     * 重新写一份自己的解析（那正是当初发生的事），下一次启动就会打一行刺眼的
     * stderr 并以非 0 退出，而不是又伪装成一个渲染缺陷。
     * 换句话说它是一道**防复发的哨兵**，不是一条"当前会失败的断言"。
     *
     * @return 一致返回 true；不一致时已打印原因，调用方应退非 0
     */
    private fun verifySelfTestFlagsAgree(): Boolean {
        val mine = SELFTEST
        val charts = DemoChart.selfTestFlag()
        if (mine == charts) return true
        System.err.println(
            "[自检] ★ 两份自检开关的值不一致：XuanDemo=$mine，DemoChart=$charts。" +
                    "它们读的是同一个属性 $SELFTEST_PROPERTY 却给出了不同结果——" +
                    "**这不是图表坏了，是开关解析分叉了**（历史上真发生过：" +
                    "一侧认 1 与 true、另一侧只认 1）。读数不可信，直接退出。"
        )
        return false
    }

    override fun start(stage: Stage) {
        if (!verifySelfTestFlagsAgree()) {
            // 退出交给 JavaFX 线程（本方法就在 JavaFX 线程上）——与 `onInit` 里那条自检
            // 用同一套写法，理由见那里：别在没人测过的时机关 JVM。
            // `return` 掉后面的建窗：既然自检读数不可信，就别再跑一遍自检了。
            Platform.runLater { exitProcess(1) }
            return
        }
        // MSAA 只能在这里给（采样数是帧缓冲的属性、运行期改不了，见 [MSAA_PROPERTY]）。
        // 默认 0 = 单采样 ⇒ 与加这个参数之前逐位相同。
        val bridge = FXGLTransfer(msaa = DEMO_MSAA, font = textFont())
        bridge.onInit {
            // ★ 启动自检**放在 `gc()` 的 let 之外**——它只用 println / System.err，
            //   **根本不需要 `Gc`**。包在 `let` 里的话就多出一条"静默跳过"路径：
            //   哪天 `FXGLTransfer` 的初始化顺序变了（比如 onInitCallback 早于 `gc = Gc(batch)`
            //   被调用），自检**一条都不跑**、`[自检] 全部通过` 不打印、进程以 0 退出。
            //   而本模块没有 junit / 没有 surefire（见计划开头的「关于验证口径」），
            //   **这份自检是唯一的自动化闸门**——仓库自己写过判据：
            //   "被静默跳过的断言比失败的断言更坏"。
            val failures = selfCheckShapeMath()
            if (failures != 0) {
                System.err.println("[自检] 失败 $failures 项，demo 不可信，退出")
                // ★ 退出**交给 JavaFX 线程**，不在这里（GL 线程）直接 exitProcess。
                //   仓库既有的两个自检都是从 JavaFX 线程退出的（`ClickDslExample.kt:161`
                //   与 `:182` 后者在 `Platform.runLater` 内）。GL 回调里直接 `System.exit`
                //   会触发 JavaFX 的关闭钩子、而此刻本线程正卡在 openglfx 的原生回调里——
                //   那是**没人测过**的一条路径，没必要为省一次 `runLater` 去赌它。
                Platform.runLater { exitProcess(1) }
                return@onInit
            }
            println("[自检] 全部通过")
        }
        bridge.onFrame { gc -> drawScene(gc) }
        // ★ 框选的拾取读回挂在这里，**不是** onFrame 里。
        //   理由见 consumeMarquee 的文档：`onFrame` 跑在 `endFrame()` 之前，
        //   那时 `pickBufferValid` 是 false（`beginFrame` 刻意置的），
        //   `gc.pickRect` 会恒返回空列表——框选会**永远选不中任何东西且不报错**。
        //   `onRender` 跑在 `endFrame()` **之后**，此刻本帧的 ID pass 刚渲染完。
        //
        // ★ 这里用 **`bridge.gc()`**，不是 `transfer?.gc()`。`transfer` 是个**普通**字段
        //   （JavaFX 线程在下一行才写、GL 线程在这里每帧读），两侧没有任何 happens-before 边，
        //   读它属于数据竞争。`bridge` 是**本方法的局部变量**——发生在同一个线程、
        //   在 `onRender` 注册之前就已构造完成，所以它和它的 `gc()`（`FXGLTransfer` 内部
        //   那个 `gc` 字段是在 GL 线程的 `onInit` 里赋值的）都是安全的。
        bridge.onRender { bridge.gc()?.let { gc -> consumeMarquee(gc) } }
        transfer = bridge

        val view = bridge.createGlFXView()
        wireMouse(bridge, view)

        val mainView = MainView().apply {
            menu = buildMenuBar()
            center = view
            bottom = status
        }
        stage.title = "Xuan 交互式 Demo"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.scene.setOnKeyPressed { e ->
            if (e.code == KeyCode.ESCAPE) {
                // 取消"已定第一点"——这是 [cancelAnchor] 四条语义路径里的一条。
                // 顺带清掉进行中的这次交互（[resetDragState]），两条各清各的。
                resetDragState()
                cancelAnchor()
                status.text = "已取消"
                return@setOnKeyPressed
            }
            if (e.code == KeyCode.DELETE || e.code == KeyCode.BACK_SPACE) deleteSelected()
        }
        stage.show()

        // ★ 合成事件自检在 `stage.show()` **之后**起：窗口要先 show 过，
        //   画布节点才有 `scene`/`window`（[FXGLTransfer.deviceScale] 从 `scene.window.outputScaleY`
        //   取缩放系数，没 show 之前恒为 1.0——那时合成事件算出来的设备坐标与真实点击
        //   不是同一个口径，第 1 条之后全会落到别的地方）。
        if (SELFTEST) startSelfTest(bridge, view, stage.scene)
    }

    // ---- 鼠标接线（JavaFX 线程） ----

    /**
     * 接上按下 / 拖拽 / 抬起 / **移动**。
     *
     * <p>**每一次都要乘 [FXGLTransfer.deviceScale]**：鼠标事件给的是节点的**逻辑**局部坐标，
     * 而 [Gc] 要的是**设备像素**。漏乘的表现是"图形画在别的地方"或"点 A 命中 B"，

     * `MOUSE_MOVED` **也不例外**（见 [onMove]）：它是虚线预览唯一的输入源，
     * 漏乘的话预览轮廓会整体缩到 1/缩放 的位置上，而**提交的图形是对的**——
     * "只是预览偏了一点"正是最难发现的那一类。
     */
    private fun wireMouse(bridge: FXGLTransfer, node: Node) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED) { e -> onPress(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED) { e -> onDrag(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_RELEASED) { e -> onRelease(bridge, node, e) }
        // 虚线预览要"没有键按下也跟着鼠标走"，所以必须再注册 MOUSE_MOVED——
        // MOUSE_DRAGGED 只在按键期间才有。
        node.addEventHandler(MouseEvent.MOUSE_MOVED) { e -> onMove(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_EXITED) {
            DemoChart.clearHoverPointer()
        }
    }

    /**
     * 鼠标移动（**没有键按下时也来**）。只做一件事：把当前位置记给虚线预览。
     *
     * <p>它**不改任何绘制状态**，所以无论此刻在哪个模式、哪个阶段都安全。
     */
    private fun onMove(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        previewX = (e.x * s).toFloat()
        previewY = (e.y * s).toFloat()
        DemoChart.updateHoverPointer(previewX, previewY)
    }

    private fun onPress(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        if (e.button == MouseButton.SECONDARY) {
            // 右键：框选。**只在绘图模式**——文本/图表模式下图形没有被画出来，
            // ID pass 里也没有它们，走一趟框选必然是空结果，白付一次同步 glReadPixels
            // （那是 GPU 停等）。这里挡住，[onRelease] 那边靠 marqueeW 仍为 NaN 自然短路。
            if (mode != Mode.DRAW) return
            marqueeX = dx; marqueeY = dy; marqueeW = 0f; marqueeH = 0f
            return
        }
        if (e.button != MouseButton.PRIMARY) return
        when (mode) {
            Mode.DRAW -> {
                dragStartX = dx; dragStartY = dy
                trajectory = floatArrayOf(dx, dy)
                previewX = dx; previewY = dy      // 按下时预览立刻定住，不等下一次 MOUSE_MOVED
            }

            Mode.TEXT -> Unit          // 文本在抬起时落字
            Mode.CHART -> Unit         // 图表模式不吃鼠标
        }
    }

    /**
     * 拖拽中。
     *
     * <p><strong>★ 这里必须用 `isPrimaryButtonDown` / `isSecondaryButtonDown`，
     * 不能用 `e.button`。</strong>
     *
     * <p>契约上的理由：`MouseEvent.getButton()` 的文档写的是
     * <em>"which, if any, of the mouse buttons has <b>changed state</b>"</em>——
     * 它回答的是"哪个按键刚变了状态"，**不是**"当前按着哪个"。而拖拽期间没有任何按键
     * 改变状态（键在 `MOUSE_PRESSED` 那一刻就已按下），所以按契约它就**不该**被依赖。
     * 判断"当前按着哪个"是 `isXxxButtonDown()` 的职责，文档对这两个方法的分工是明确的。
     *
     * <p>用 `e.button == SECONDARY` 之类的判断，一旦它对拖拽事件不给出正确的键，
     * 两个分支就永远不成立，**整个绘制功能会静默失效**：轨迹一个点都收不到、
     * 图形画不出来、框选永远不出现，**而且不报任何错**——画面看起来就是"拖了没反应"。
     *
     * <p><strong>★ 实测（2026-09-24，本机 Windows 11 + JDK 内置的 JavaFX 25）</strong>：
     * 用 `javafx.scene.robot.Robot` 驱动一次真实拖拽、监听 `MOUSE_DRAGGED`，结果是
     * **`e.getButton()` 返回真实按下的那个键**（左拖 `PRIMARY`、右拖 `SECONDARY`），
     * **不是 `NONE`**。也就是说——**用 `e.button` 判拖拽在本机是能工作的**。
     *
     * <p><strong>但换掉它仍然是对的，只是理由变了。</strong>
     * 本机之所以拿到正确的值，是因为 glass 层在 Windows 上把按下的键透传了上来
     * （读本机 JDK 的 `javafx/scene/Scene.java` 可见它两处都是
     * `new MouseEvent(..., e.getButton(), ...)` 的**原样透传**）。
     * **那是未文档化的平台行为，不是契约**——`getButton()` 的契约仍然是
     * "哪个按键**改变了状态**"，而拖拽期间没有任何键改变状态。
     * 所以这是一处典型的「**在本机能跑，正确性却挂在没人承诺过的东西上**」。
     *
     * <p>这段历史值得留着，因为它记下了**推断与实测的差别**：原先这里写的是
     * "按契约推断它返回 `NONE`"，实测发现本机不返回 `NONE`。
     * **结论没变，理由从"它会返回 NONE"改成了"契约不承诺它，而 `isXxxButtonDown()`
     * 正是为回答这个问题而存在的"**。哪天有人想把它改回 `e.button`，这就是那条"别改"的理由。
     *
     * <p>`MOUSE_PRESSED` / `MOUSE_RELEASED` 上 `getButton()` 是可靠的——那两刻确实
     * 有按键改变了状态，正是它契约所指的情形。所以 [onPress] / [onRelease] 照旧用它。
     */
    private fun onDrag(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        if (!marqueeW.isNaN() && e.isSecondaryButtonDown) {
            marqueeW = dx - marqueeX; marqueeH = dy - marqueeY
            return
        }
        if (!e.isPrimaryButtonDown) return
        if (mode != Mode.DRAW || dragStartX.isNaN()) return

        // 轨迹点：新建数组再赋值（见 trajectory 字段的说明）
        val old = trajectory
        val next = old.copyOf(old.size + 2)
        next[old.size] = dx; next[old.size + 1] = dy
        trajectory = next
    }

    /**
     * 清**这一次交互**的临时状态：按下起点、轨迹、框选。
     *
     * <p><strong>它不动"已定第一点"的锚点</strong>——那是一个**跨点击**的状态，
     * 而本函数会被右键释放（拾取）、两点式上的拖拽释放、文本释放这些
     * **与锚点无关**的路径调用。曾经把它们混在一起，后果是
     * "定完第一点、右键点一下去选别的图形 ⇒ **第一点被悄悄丢掉**，状态栏不说"。
     * 锚点现在有自己的清理入口 [cancelAnchor]，两者**互不越界**：
     * "清锚点的路径"就是那四条语义路径，一条不多。
     *
     * <p>**必须在每一处"这次交互结束了"调用**（至少下面这三处），否则不变式
     * "`dragStartX` 非 NaN ⟺ 真的有一次 DRAW 拖拽在进行"不被任何东西维护：
     * ① 拖拽正常结束（[onRelease] 的 DRAW 分支）；
     * ② 框选正常结束（[onRelease] 的 SECONDARY 分支）——**顺带取消可能在进行的主键拖拽**。
     *    双键同时按下是个语义含糊的手势，**而这半边只闭合了一半**，两种松开顺序不同：
     *    - **右键先松开**：主键拖拽被取消，随后那次左键释放 `moved = 0`、退化成一次
     *      单击拾取——无害；
     *    - **左键先松开**：走 PRIMARY 分支、用**释放点**提交，而预览停在最后一次
     *      `MOUSE_DRAGGED`（[onDrag] 的右键分支早返回，轨迹此后不再更新）——
     *      于是"提交的位置"与"停帧显示的预览"不一致。轨迹型图形不受影响
     *      （它们不吃 `endX/endY`，提交的仍是那条冻结的轨迹）；紧接着的右键释放
     *      会因为 `marqueeW` 已被清而**静默取消这次框选**。
     *
     *    **没有把左键那半也闭合是刻意的**：双键手势本身语义含糊，而"按鼠标实际位置提交"
     *    并不比"按预览提交"更错——**不假装它已经被处理**。
     *    （这条措辞是质量评审逼出来的：原稿写的是"取消它比提交到错误的终点好"，
     *    而那只在右键先松开时成立。**文档承诺超过实现**与本文件 I1 那条是同一类问题。）
     * ③ **模式被切走时**（[buildMenuBar] 的 `modeItem`）。
     *
     * <p>③ 是最容易漏的那一处。**下面这条链路是"漏掉它会怎样"的说明，不是当前行为**——
     * 当前代码在 ③ 处调用了本方法，所以走不出来；把 `modeItem` 里那一行删掉就能复现：
     * 绘图模式按下并拖动 → **按住不放**、用键盘切到**图表** → 松开
     * （`Mode.CHART -> Unit` 什么都不清；`Mode.TEXT` 那条**也不再是 `Unit`**——它现在
     * 会在"位移超过 `CLICK_SLOP`"时清掉 `dragStartX`，但那只是模式专属的窄清理，
     * 清不到轨迹与框选标志，**代替不了**本方法要做的整条清理）→ 切回绘图。
     * 此刻残留的 `dragStartX` 让 [drawDragPreview] **凭空画一个预览框**：它画的是一条
     * 用户从没拖出来的矩形/轨迹，而图形列表与状态栏里都没有它——正是本仓库最防的那类
     * "看起来正常、其实没这回事"。（它一直画到下一次 DRAW 按下把起点覆盖掉为止。）
     *
     * <p>（可达性已核实：Windows 上 Alt/F10 能在**鼠标按键按住时**走菜单，
     * 因为 JavaFX 的 `Scene` 只在**所有**键抬起后才结束 press-drag-release 手势。）
     */
    private fun resetDragState() {
        dragStartX = Float.NaN
        dragStartY = Float.NaN
        trajectory = FloatArray(0)
        marqueeW = Float.NaN
    }

    /**
     * 取消**"已定第一点"**。**只在四个语义路径上调用**：提交之后、`Esc`、
     * 切模式、切图形。这四条是**全部**——别的路径不该动它。
     *
     * <p><strong>为什么必须与 [resetDragState] 分开</strong>：锚点是**跨点击**活着的状态，
     * 而"这次交互结束了"有三条路径与它无关（右键释放＝拾取/框选、两点式上的拖拽释放、
     * 文本释放）。混在一起时，那些手势会**顺带把用户手里的第一点丢掉**，
     * 而状态栏一个字都不说——正是本仓库最防的那类"看起来正常、其实没这回事"。
     *
     * <p>★ **"四条"现在是字面成立的**（不是"语义上四条、调用点多于四条"）：
     * `grep` 本方法的调用点只有下面四处，且每处都是真的在"取消第一点"。
     * 加第五处之前，请先回答"这个手势凭什么取消用户已经定下的第一点"。
     */
    private fun cancelAnchor() {
        anchorX = Float.NaN
        anchorY = Float.NaN
    }

    private fun onRelease(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        // 设备坐标在**两个键的分支都要**（右键那条也要：只按不拖 = 拾取选中，
        // 而 [onPick] 的状态栏与 `pickAsync` 的口径都是设备像素），所以提到最前面算。
        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()

        if (e.button == MouseButton.SECONDARY) {
            if (!marqueeW.isNaN()) {
                // ★ **只按不拖的右键不算框选**（终审指出的不对称：主键那条有 `CLICK_SLOP`
                //   闸门，次级这条没有）。按下时 `marqueeW` 被置成 **`0f` 而不是 NaN**，
                //   所以"按下即松开"会照走一遍**零尺寸**框选：它要么把光标那一个像素下的
                //   图形选上、要么在空白处把选中集**清空**——两种都是用户没表达过的意图。
                //   闸门与主键同款：位移小于 `CLICK_SLOP` 就当作没框选。
                val dragged = abs(marqueeW) >= CLICK_SLOP || abs(marqueeH) >= CLICK_SLOP
                if (dragged) {
                    // 与 [drawMarquee] 共用同一份规范化——读回用的矩形与画出来的框是同一个。
                    marqueePending.set(normalizedRect(marqueeX, marqueeY, marqueeX + marqueeW, marqueeY + marqueeH))
                } else {
                    // ★★ **只按不拖 = 拾取选中**（2026-09-25 从"左键单击"移到这里，
                    //   见设计文档 §4.2 的手势表与 §4.2.1 ①）。
                    //   为什么必须在**右键**上：第一下左键现在是"定起点"，
                    //   不能同时是"选中"；而右键**已经**有"拖 = 框选"，
                    //   于是"只按不拖 = 拾取"就是**同一个 4 像素判据在右键上复刻一遍**，
                    //   与左键的"拖 = 轨迹型 / 点 = 两点式"完全同构。
                    //   **判据同样只能在抬起时做**——按下那一刻不知道后面会不会拖。
                    //   未命中时 [onPick] 会把选中集清空，这正是"点空白处取消选中"。
                    bridge.clickAsyncAtNode(node, e.x, e.y) { hit -> onPick(hit, dx, dy) }
                }
                // 提交与否都要清：不清的话残留的 `marqueeW = 0f` 会让**下一次没有按下的**
                // 右键释放也照走这条分支（它只判 NaN）。见 [resetDragState] 的说明：
                // 顺带取消掉可能在进行的主键拖拽。
                //
                // ★ **它不清"已定第一点"的锚点**（2026-09-25 拆分后如此）：右键这一下
                //   是"拾取选中"或"框选"，两者都与两点式的第一点无关——
                //   混在一起时，用户定完第一点、右键点一下去选别的图形，
                //   **手里的第一点会被悄悄丢掉而状态栏不说**。这条正是 [cancelAnchor]
                //   与 [resetDragState] 分开的理由，见那两个函数的 KDoc。
                resetDragState()
            }
            return
        }
        if (e.button != MouseButton.PRIMARY) return
        val moved = if (dragStartX.isNaN()) 0.0 else hypot((dx - dragStartX).toDouble(), (dy - dragStartY).toDouble())

        when (mode) {
            Mode.DRAW -> {
                if (kind.isTrajectory) {
                    // 轨迹型：与第一版完全一致
                    if (moved < CLICK_SLOP) {
                        resetDragState()
                        bridge.clickAsyncAtNode(node, e.x, e.y) { hit -> onPick(hit, dx, dy) }
                    } else {
                        commitShape(dx, dy)
                        resetDragState()
                    }
                } else if (moved < CLICK_SLOP) {
                    // 两点式的一次"点击"：没有锚点就定锚点，有锚点就提交。
                    // **判据是 anchorX 是不是 NaN**——不是 dragStartX（按下刚覆盖过它）。
                    if (anchorX.isNaN()) {
                        // 先清掉这次按下留下的 dragStartX / trajectory，再把锚点设上。
                        // ★ **顺序现在已无所谓**（[resetDragState] 不再碰锚点，见 [cancelAnchor]），
                        //   保持这个写法只是因为它读起来就是意图。这条注记留着是为了说明
                        //   这里**曾经**有一条顺序要求（那时混在一起，"先设锚点"会被清掉，
                        //   而症状看起来只是"第一点没记住"）。
                        val ax = dx
                        val ay = dy
                        resetDragState()
                        anchorX = ax
                        anchorY = ay
                        status.text = "${kind.label}：已定第一点 (${ax.toInt()},${ay.toInt()})，" +
                                "再点一下完成（Esc 取消）"
                    } else {
                        val before = shapes.get().size
                        commitTwoPointShape(dx, dy)
                        resetDragState()
                        cancelAnchor()      // ★ 提交 = 四条语义取消路径之一
                        // 只在**真的画出来了**的时候报"已画"——两点重合会被拒，
                        // 那时 commitTwoPointShape 自己写了原因，别把它盖掉。
                        if (shapes.get().size > before) {
                            status.text = "已画：${shapes.get().last().shape.describe()} · " +
                                    "共 ${shapes.get().size} 个"
                        }
                    }
                } else {
                    // 两点式上"拖拽"：**取消这次按下、但不取消已定的第一点**
                    //（同一个图形不能既靠拖又靠点，所以什么都不画）。
                    // ★ 措辞改准过一次：早先这里写的是"什么都不做"，而那句 `resetDragState()`
                    //   其实**会清掉按下起点**（现在它不再清锚点，见 [cancelAnchor]）——
                    //   **注释与代码不是一回事**，这条差异曾经差点被 Task 3 的断言照抄下去。
                    resetDragState()
                    // 状态栏要分两种情形：**手里已经有第一点时，"第一下定起点"那句是错的**。
                    if (anchorX.isNaN()) {
                        status.text = "${kind.label}请点两下：第一下定起点、第二下完成"
                    } else {
                        status.text = "${kind.label}：第一点还在 (${anchorX.toInt()},${anchorY.toInt()})，" +
                                "点第二下完成（Esc 取消）"
                    }
                }
            }
            // 文本：**在抬起时落字**（`onPress` 的 TEXT 分支是 `Unit`，不记起点）。
            //
            // ★ **`moved` 这条判据是防御性的、当前不可达**——不要说它能"把单击落字与
            //   拖着划了一下什么都没有分开"：文本模式下 `moved` **恒为 0.0**
            //   （`onPress` 的 TEXT 分支不记起点，而 `mode` 的唯一写点 `modeItem`
            //   **总是**调 [resetDragState]，所以进 TEXT 时 `dragStartX` 必是 NaN），
            //   于是 `else dragStartX = Float.NaN` **永不执行**——
            //   **左键拖 200px 也照样在松开处落字**。行为本身与验收表一致，
            //   但照旧注释写出来的断言会是一条**恒假断言**，而本仓库把"被静默跳过的
            //   断言"与失败的断言同等看待。守卫留着（零成本），只是别把它当判据。
            //
            // ★ **这条分支末尾调 [resetDragState]，且只调它**（不调 [cancelAnchor]）——
            //   落字与"取消已定的第一点"毫无关系，那四条语义路径里没有它。
            //
            //   以前（2026-09-25 之前）它只清 `dragStartX`，理由是：
            //   ① 上面那个 else 已不可达，它清的是"万一"；② TEXT 模式下 `trajectory` 与
            //   `marqueeW` 都不参与画面（预览由 [drawDragPreview] 画，而它在非 DRAW 模式
            //   **直接返回**）。两条今天仍然成立，所以尾部这次整条清理也**不是必需的**，
            //   它是**防御性**的，与上面那条 `moved` 判据同性质：**谁也不该拿它当判据**。
            //   （早年这里留过一句"别忘了在末尾也 resetDragState()"，那是 `commitText`
            //     还不存在时的占位提醒；后来换成更窄的写法，现在又换回来了——
            //     **新旧写法都各有一半理由，这一句就是它们的交接记录**。）
            Mode.TEXT -> {
                if (moved < CLICK_SLOP) commitText(dx, dy) else dragStartX = Float.NaN
                resetDragState()
            }

            Mode.CHART -> Unit
        }
    }

    /** 把当前拖拽的起止点 / 轨迹变成一个图形，追加进 [shapes]。 */
    private fun commitShape(endX: Float, endY: Float) {
        val sx = dragStartX
        val sy = dragStartY
        if (sx.isNaN()) return
        val w = endX - sx
        val h = endY - sy

        val s: Shape? = if (kind.isTrajectory) {
            val pts = if (kind == ShapeKind.POLYGON) ShapeMath.polygonFrom(trajectory)
            else ShapeMath.bezierFrom(trajectory)
            // 点数不够就丢弃，**不产生退化图形**（理由见 ShapeMath.polygonFrom 的说明）
            if (pts == null) null
            else if (kind == ShapeKind.POLYGON) Shape.PolygonShape(pts, color, style, lineWidth)
            else Shape.BezierShape(pts, color, style, lineWidth)
        } else {
            // 面状的三种（矩形 / 圆 / 椭圆）**要求两轴都非零**：纯水平或纯竖直地拖会得到
            // `h = 0` 的"矩形"、`ry = 0` 的"椭圆"、`r = 0` 的"圆"——它们什么都画不出来，
            // 却占着一个拾取号与一条列表项。而同一个 demo 里 `polygonFrom` / `bezierFrom`
            // 对退化输入是**明确拒绝**的（理由见 ShapeMath 的说明），两种口径不该并存。
            // 直线不受这条约束：任意两点都是一条合法的线段。
            val planar = abs(w) > 0f && abs(h) > 0f
            when (kind) {
                ShapeKind.RECT -> if (planar)
                    Shape.RectShape(minOf(sx, endX), minOf(sy, endY), abs(w), abs(h), color, style, lineWidth)
                else null

                // **内切于拖拽框**（半径 = 较短边的一半），与椭圆同一条尺规，
                // 也与设计文档 §4.2 写的"圆**内切于**该框"一致。
                // 早先这里用的是 `hypot(w,h)/2`，那是**外接**圆（直径 = 对角线）——
                // 于是同一个框拖出的圆**比预览框还大**，而预览画的正是那个框
                // （[drawDragPreview] 画 `strokeRect`），用户看不出多出来的那一圈从哪来。
                ShapeKind.CIRCLE -> if (planar)
                    Shape.CircleShape(
                        sx + w / 2f, sy + h / 2f, minOf(abs(w), abs(h)) / 2f,
                        color, style, lineWidth
                    )
                else null

                ShapeKind.ELLIPSE -> if (planar)
                    Shape.EllipseShape(sx + w / 2f, sy + h / 2f, abs(w) / 2f, abs(h) / 2f, color, style, lineWidth)
                else null

                ShapeKind.LINE -> Shape.LineShape(sx, sy, endX, endY, color, style, lineWidth)
                else -> null
            }
        }
        if (s == null) {
            // **用常量拼提示，不要写死数字**：写死的话，改了 ShapeMath 的阈值、
            // 这句提示说的数就与实际判据不一致——而它恰恰是**用户唯一能看到**的那句话。
            // 两种拒绝理由共用这一句：轨迹型是"点数不够"，面状是"有一轴为零"。
            status.text = "拖得太短，没有形成图形" +
                    "（多边形至少 ${ShapeMath.MIN_POLYGON_POINTS} 个点、" +
                    "曲线至少 ${ShapeMath.MIN_CURVE_POINTS} 个点；" +
                    "矩形/圆/椭圆要求横竖都不为零）"
            return
        }
        // pickRegistry 自己是线程安全的（Gc 文档里明确的例外），
        // 所以可以在 JavaFX 线程直接注册，不必塞进 onFrame。
        // **号由注册表发，不要自己维护计数器**——两本账迟早对不上。
        val id = transfer?.gc()?.pickRegistry?.register(s) ?: 0
        shapes.set(shapes.get() + Placed(s, id))
        status.text = "已画：${s.describe()} · 共 ${shapes.get().size} 个"
    }

    /**
     * 用**锚点 + 这一下**提交一个两点式图形。**只在 JavaFX 线程调用。**
     *
     * <p>尺规见设计文档 §4.2 的那张表。**与第一版的差别只有圆**：
     * 它是"**圆心 + 半径**"（`r = 锚点到这一下的距离`），而第一版是"内切于拖拽框"。
     * 那是刻意的改动，不是回归。
     *
     * <p>★ **本函数与 [twoPointPreviewOutline] 是"同一条尺规的两份实现"**——
     * 改一处必须同时改另一处，否则**预览与提交结果不是同一个图形**，
     * 而"预览只是稍微偏一点"是画面上一眼看不出来的那类错误。
     *
     * <p>**它不设 `status.text`**——那句话由调用方按"已画：…"的既有格式写
     * （见 [onRelease] 里那两处 `status.text`），避免两处各写一半。
     */
    private fun commitTwoPointShape(x1: Float, y1: Float) {
        val x0 = anchorX
        val y0 = anchorY
        val w = x1 - x0
        val h = y1 - y0
        val s: Shape? = when (kind) {
            ShapeKind.RECT ->
                if (abs(w) > 0f && abs(h) > 0f)
                    Shape.RectShape(minOf(x0, x1), minOf(y0, y1), abs(w), abs(h), color, style, lineWidth)
                else null

            ShapeKind.CIRCLE -> {
                val r = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                if (r > 0f) Shape.CircleShape(x0, y0, r, color, style, lineWidth) else null
            }

            ShapeKind.ELLIPSE ->
                if (abs(w) > 0f && abs(h) > 0f)
                    Shape.EllipseShape(x0, y0, abs(w), abs(h), color, style, lineWidth)
                else null

            ShapeKind.LINE -> Shape.LineShape(x0, y0, x1, y1, color, style, lineWidth)
            else -> null       // 轨迹型不走这条路
        }
        if (s == null) {
            status.text = "两点重合，没有形成图形（${kind.label} 要求两点不重合）"
            return
        }
        val id = transfer?.gc()?.pickRegistry?.register(s) ?: 0
        shapes.set(shapes.get() + Placed(s, id))
    }

    /**
     * 落一段文字。**在 JavaFX 线程上被调用**（从 [onRelease]）。
     *
     * <p><strong>★ 文本按规格注册成 `pickId = 0`，即不参与拾取</strong>——这不是
     * "还没做"，是本期**刻意**的选择（框架本来就支持可拾取文本：`Gc` 的文档写着文本
     * "裁剪、z 序、合批、GPU 拾取全部自动成立"，不做反而少演示了一块能力）。
     *
     * <p>**代价必须写下来**：`pickId = 0` 让文本**没有单独的删除途径**——
     * `删除选中` 删不到它（它进不了选中集），**只有「清空画布」能清，而那会连所有图形
     * 一起清掉**。误点落下一段字之后，唯一补救是全清。
     *
     * <p>要把它做成可拾取的，只需在这里像 [commitShape] 那样发号（一行）——**但必须先
     * 按真实推进宽度重算 [Shape.TextShape.bounds]**：那时它才开始被选中高亮消费，
     * 而它现在是个"按字符数估、且在本 demo 里退化成了常数"的近似值
     * （详见那个方法的 KDoc）。
     */
    private fun commitText(x: Float, y: Float) {
        val sample = TEXT_SAMPLES[textSeq % TEXT_SAMPLES.size]
        textSeq++
        shapes.set(
            shapes.get() + Placed(
                Shape.TextShape(sample, x, y, fontSize, color), 0   // 文本不参与拾取，见上
            )
        )
        // 坐标一律取整再进状态栏（本文件别处都这么写）——原样插 float 会显示成
        // "笔位 (671.4286,183.71428)"，读起来像精度暴露，其实只是没取整。
        status.text = "落字：「$sample」${fontSize.toInt()}px，" +
                "笔位 (${x.toInt()},${y.toInt()})，基线 y=${y.toInt()}"
    }

    /**
     * 拾取结果回调。**在 JavaFX 线程上执行**。
     *
     * <p>**坐标从参数传进来，不从 [PickHit] 里取。** `PickHit.x()/y()` 只在**命中**时
     * 才有意义（它是"命中发生在哪个像素"），未命中时 `hit` 就是 null——
     * 早先写成 `hit?.x() ?: 0` 的后果是**每一次未命中都显示"设备像素 0,0"**，
     * 而状态栏是用户唯一能看到的反馈。那属于小号的静默错误输出。
     *
     * @param hit 命中结果；未命中为 null
     * @param dx  本次点击的设备像素 x（已换算）
     * @param dy  本次点击的设备像素 y（已换算）
     */
    private fun onPick(hit: PickHit?, dx: Float, dy: Float) {
        val item = hit?.payload() as? Shape
        val id = hit?.id() ?: 0
        // 自检用的交付计数（见字段说明）。放在**这里**而不是回调外面：
        // 这条路径就是"点击 → 拾取 → 回调"的终点，数它才等于数"回调被交付了几次"。
        if (SELFTEST) {
            if (item == null) pickMissCount++ else pickHitCount++
        }
        selection.set(if (item == null) emptySet() else setOf(id))
        status.text = if (item == null) {
            "未命中（设备像素 ${dx.toInt()},${dy.toInt()}）"
        } else {
            "命中：${item.describe()}（设备像素 ${dx.toInt()},${dy.toInt()}）"
        }
        // **再打一行 stdout**：状态栏画在 GL 画布上，截图抓不到它；这行日志是
        // "鼠标真的点下去、且命中了对的东西"**唯一能被自动核对**的出口。
        // 仓库既有的 `ClickDslExample.kt` 正是为这个理由这么做的（见它的类文档）。
        println("[点击] ${status.text}")
    }

    private fun deleteSelected() {
        val sel = selection.get()
        if (sel.isEmpty()) {
            status.text = "没有选中任何图形"; return
        }
        // 注册表在**本线程**（JavaFX 线程）解析好再传进去——`transfer` 是个**普通**字段，
        // 它不是线程安全的载体（见 [start] 里 `bridge.gc()` 那段说明）。
        removeShapes(sel, transfer?.gc()?.pickRegistry)
        status.text = "已删除 ${sel.size} 个图形 · 剩 ${shapes.get().size} 个"
    }

    /**
     * 把给定拾取号对应的图形从画布上摘掉（注销 + 移出列表 + 清空选中集）。
     *
     * <p><strong>★ 注册表走参数，不是在里面读 [transfer] 拿的</strong>——这不是风格问题：
     * `transfer` 是个**普通字段**（`@Volatile` 都没有），而本文件在 [start] 里明文把这种读法
     * 称作数据竞争（那边的原话是"读它属于数据竞争"）。本函数**要在 GL 线程上被调**
     * （自检第 ⑩ 条的钩子，见 [consumeMarquee]），所以在里面读 `transfer?.gc()` 就凭空多出
     * 一处跨线程读——而它的 KDoc 同时声称"只碰线程安全的三样东西"，**文档承诺超过实现**。
     * 改法是让那句话**为真**，不是给它加一段注解：注册表由**调用方在它自己那一侧**解析。
     *
     * <p>于是它的三个入参（`registry` / `sel` 与它碰的两张表）都来自**调用方自己那一侧**：
     * JavaFX 线程的调用方（[deleteSelected]）同线程读 `transfer`，本来就没问题；
     * GL 线程的钩子手里已经有 `consumeMarquee(gc)` 那个 `gc`，直接交 `gc.pickRegistry`。
     *
     * <p><strong>★ 但"它只碰线程安全的东西"这句话对后两样仍然不真</strong>
     * （终审指出；`0c13440` 只消掉了 `transfer` 那一处）：
     * `shapes.set(shapes.get().filter { … })` 与 `selection.set(emptySet())` 都是
     * **读-改-写**，而本函数**有两个线程上的调用方**——[deleteSelected]（JavaFX 线程）与
     * 自检第 ⑩ 条的钩子（**GL 线程**，见 [consumeMarquee]）。两次 RMW 交错会**丢一次更新**
     * （经典的 lost update：A 读到旧表 → B 读到旧表并写回 → A 写回它的旧表）。
     *
     * <p><strong>为什么这仍然不是生产缺陷</strong>：GL 线程那一侧被
     * `if (SELFTEST &amp;&amp; selfTestDeleteAfterReadback)` 挡着，**生产路径根本走不到**；
     * 而真丢了更新的话，第 ⑩ 条自己的判据（`shapes.size`、`selection`）会**响亮失败**，
     * 不是静默错画。所以本轮**只把话说准**：
     * **这两张表的写者有两处，一处是自检模式下 GL 线程上的钩子**——那是个例外，且只在自检路径。
     * （**不改成 `Platform.runLater`**：那会把第 ⑩ 条的时序判据——"读回落地之前按 Delete"——
     * 从确定性变成竞态，正是那条判据花了一整轮才立住的东西。）
     *
     * <p>状态栏是 JavaFX 控件，**不在这里写**，由调用方负责
     * （[deleteSelected] 在 JavaFX 线程上直接写；钩子那边走 `runLater`）。
     *
     * @param sel      要删掉的拾取号集合
     * @param registry 拾取号注册表；**为 null 表示此刻拿不到**（`gc` 还没建好），
     *                 那就只摘列表、不注销——图形照删，只是注册表里多留一条到进程结束
     *                 （与 [commitShape] 里"`gc()` 为 null 时 `id = 0`"是同一种降级）
     */
    private fun removeShapes(sel: Set<Int>, registry: PickRegistry?) {
        // 所有权：选定集必须 unregister，否则 pickRegistry 会一直强引用着它们
        registry?.let { r -> sel.forEach { r.unregister(it) } }
        shapes.set(shapes.get().filter { it.pickId !in sel })
        selection.set(emptySet())
    }

    /** 画一帧。**在 GL 线程上执行**，不要在这里碰任何 JavaFX 控件。 */
    private fun drawScene(gc: Gc) {
        // ★ **每帧把菜单的开关拷进 `Gc`**（抗锯齿）。**为什么必须每帧设**：
        //   `Gc.antialias` 是 `save`/`restore` 状态栈里的一员，而本帧的绘制里
        //   到处都有 `save`/`restore`（选中高亮、框选、预览…），任何一次 restore
        //   都可能把它弹回旧值；在帧首统一设一次，能保证"菜单说什么就是什么"。
        //   它读的是 `@Volatile` 字段（菜单在 JavaFX 线程写），一次 volatile 读是零成本的。
        gc.antialias = antialias

        // ★ 帧计数（自检的节拍器）。**只在自检模式下自增**——生产路径一行都不多。
        //   合成事件必须在 JavaFX 线程发（`fireEvent` 要碰场景图），而拾取要走一帧
        //   （`clickAsyncAtNode` 的请求在下一帧 `endFrame` 之后才提交、再下一帧才读回、
        //   最后经 `Platform.runLater` 回到 JavaFX 线程）。所以脚本只能"注入 → 等 N 帧 → 断言"，
        //   而"等 N 帧"必须由**真帧**来数：脉冲数在窗口被遮挡/最小化时不等于帧数。
        if (SELFTEST) frameCount++
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()
        gc.pickId = 0
        gc.fill = BG
        gc.fillRect(0f, 0f, w, h)

        if (mode == Mode.CHART) {
            DemoChart.draw(gc)
            return
        }

        val sel = selection.get()
        for (p in shapes.get()) {
            if (p.pickId != 0) {
                // pickable 是 save/pickId/restore 的作用域版本：块内改的颜色/线宽
                // 在块结束时全部回滚，因此不可能"忘了复位 pickId"。
                gc.pickable(p.pickId) { p.shape.draw(gc) }
            } else {
                // **这一支与 `pickable(0) { … }` 逐位等价**（`pickId` 在此刻的环境值就是 0：
                // 帧首置过 0、`shape.draw` 内部的 save/restore 不动它，而 ID pass 的片段
                // 着色器无条件写 `fragId`、拾取缓冲被清成 0，所以"写 0"与背景不可区分），
                // 所以它不是必需的。留着只是让"这一个是不可拾取的"在调用点一眼可见。
                // **它也不掩盖注册失败**：`gc()` 为 null 时 `id = 0`，图形照画、只是点不中，
                // 那正是 [commitShape] 里写明的降级。
                // （文本就是按规格注册成 `pickId = 0` 的，所以这一支真的会被走到。）
                p.shape.draw(gc)
            }
        }
        // 选中高亮画在所有图形之后，此时 pickId 已被 pickable 复原成 0。
        //
        // **这里也不加 `try/finally`**，与 [drawDragPreview] / [drawMarquee] 一致——
        // 理由见那两处的 KDoc：`body` 抛异常会让 `FXGLTransfer` 的 `endFrame()` 整趟被跳过、
        // 此后每帧都抛，`finally` **挡不住**真正的风险。这里写一句是免得下一个人以为是漏了。
        //
        // 顺带：`bounds()` 的**生产**调用点只有这里（另一个调用点在自检第 ① 条的
        // 几何绝对值断言里，见那里的 `s.firstOrNull()?.shape?.bounds()`——
        // "只被这里用"那句话是假的，是终审指出来的）。它的约定是"尺寸非负"
        // （见 `Shape.bounds()` 的 KDoc）。
        // `commitShape` 用 `planar` 保证了这一点；`TextShape` 的尺寸项也恒非负
        // （`size × min(字符数, 8)` 与 `size × 1.3`，而 `size` 是 `FONT_SIZES` 里的正数），
        // 所以不会出现负尺寸矩形。
        // ⚠️ 但要说准：**文本那个 `bounds()` 一次都不会被调到**——理由**不是**"这里没人调它"
        // （这里确实调，只是判据 `p.pickId in sel` 把它挡在外面了），而是**文本恒
        // `pickId = 0` 而 `sel` 永不含 0**（`Gc.pick` 对 0 返回 null、`PickBuffer.readRect`
        // 滤掉 0，见 `TextShape.bounds()` 的 KDoc）。所以"文本的尺寸非负"这件事今天是
        // **没人消费的约定**，不是一条被这里验证过的性质。
        gc.save()
        gc.pickId = 0
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 3f
        for (p in shapes.get()) {
            if (p.pickId in sel) {
                val b = p.shape.bounds()
                gc.strokeRect(b.x - 6f, b.y - 6f, b.width + 12f, b.height + 12f)
            }
        }
        gc.restore()
        drawDragPreview(gc)
        drawMarquee(gc)
        // ★ 这里**不能**调 consumeMarquee（框选读回）——见它的文档：
        //   `onFrame` 跑在 `endFrame()` 之前，那时本帧的 ID pass 还没渲染，
        //   `gc.pickRect` 会**恒返回空列表**。它在 [start] 里挂到 onRender 上。
    }

    /**
     * 把"两个角点"规范化成一个 `Rect`（左 / 上 / 宽 / 高，**后两项非负**）。
     *
     * <p>**为什么要有它**（终审的 M4）：同一套 `minOf` / `abs` 规范化在本文件里写了**四遍**，
     * 而其中两遍是 [drawMarquee] 里**紧邻的两句**——填充用一句、描边用一句。那种写法的
     * 风险不是"重复"，而是**两遍会分家**：改了一处（比如把 `abs` 写掉、或把某个 `+ w`
     * 写成 `- w`）就会有半透明填充框与描边框**互相错开**，而画面上两支框都还在、
     * 看起来只是"框有点歪"，不会有人想到是两处算了两遍。
     * 抽成一个函数之后，"填充与描边的框**不可能**不一致"就从一句约定变成了**结构性事实**。
     *
     * <p>它同时收掉了 [onRelease]（提交给 GL 线程的那个矩形）与 [drawDragPreview]
     * （拖拽预览框）里的另外两遍——三处共用一份规范化，就没有"某处算得跟别处不一样"的余地。
     * 这是一个**纯函数**（只有算术，不碰任何状态），所以随便哪个线程调都对。
     */
    private fun normalizedRect(x0: Float, y0: Float, x1: Float, y1: Float): Rect =
        Rect(minOf(x0, x1), minOf(y0, y1), abs(x1 - x0), abs(y1 - y0))

    /**
     * 预览。**两种形态**：
     * - **两点式**（矩形/圆/椭圆/直线）在"已定第一点"时画**虚线轮廓** + 锚点上的十字；
     * - **轨迹型**按住拖拽时画**实线轨迹**（与第一版一致）。
     *
     * <p>**`gc.pickId = 0` 写在 `save()` 之后，不靠调用方的帧首复位**：`save` 会保存并恢复
     * `pickId`，所以"块内不设"等于**继承外层的值**。Task 6 之后图形循环会给每个图形发号，
     * 若 `drawScene` 那时忘了在循环后复位，这个预览框就会**带着最后一个图形的 ID 被画出来**
     * ——一行的事，把承诺变成局部的。
     *
     * <p>**这里不加 `try/finally`**（与 `DemoShapes.drawWith` 那边**刻意不同**）：那边加，
     * 是因为"栈平衡要在本文件内闭合"。这里加**挡不住真正的风险**——`body` 抛异常会让
     * `FXGLTransfer` 的 `endFrame()` **整个被跳过**、`frameActive` 停在 true、此后每帧都抛
     * （见 `DemoShapes` 里那段 ⚠️）。既然挡不住，就不写一段**看着像有防护**的代码。
     * 这条差异是**有意的**，写出来免得下一个人以为是漏了。
     *
     * <p>★ **`save()`/`restore()` 在两条分支上都必须配对**：轨迹型那条在
     * "`dragStartX` 是 NaN"时**提前返回**，所以那句 `restore()` 是复制的重点
     * ——漏了它，一次未闭合的 `save()` 会把预览的 `pickId = 0` 与画笔泄漏到
     * 同一帧后面所有图元上（而画面只是"高亮没了/颜色串了"）。
     */
    private fun drawDragPreview(gc: Gc) {
        if (mode != Mode.DRAW) return
        gc.save()
        gc.pickId = 0
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f

        val ax = anchorX
        if (!ax.isNaN() && !kind.isTrajectory) {
            // ① 两点式：虚线轮廓。鼠标位置取 previewX/Y；
            //    还没收到过 MOUSE_MOVED（刚进这个状态）时退回锚点本身，
            //    此时轮廓退化成一个点——**锚点十字就是那个状态下唯一的可见反馈**。
            val px = if (previewX.isNaN()) ax else previewX
            val py = if (previewY.isNaN()) anchorY else previewY
            val outline = twoPointPreviewOutline(kind, ax, anchorY, px, py)
            if (outline != null) {
                // ★ 虚线现在由 **`Gc` 的状态字段**提供（2026-09-28 接上公开 API）：
                //   设一次模式，然后**照常描边**——弧长切分在库里做。
                //
                //   此前这里调的是 `DemoShapes.strokeDashedPolyline`：一份约 100 行的
                //   手写弧长切分，还自带 `isUsableDashPattern` / `MAX_DASH_SEGMENTS`
                //   两道守卫（防的是"迭代次数无上界 ⇒ GL 线程卡死"）。
                //   **那份守卫现在不需要了**——同样的死循环在库里被修掉了
                //   （`StrokeGenerator.strokeDashed` 加了一条"每一项都太短就什么都不画"，
                //   判据是 `StrokeDashTest` 里带 `@Timeout` 的那条）。
                //
                //   ⚠️ `dashPattern` 是**状态栈里的一员**，而本函数开头有 `gc.save()`，
                //   所以其实不复位也安全；显式复位是为了让"这一段用虚线"读起来一眼可见。
                gc.dashPattern = floatArrayOf(PREVIEW_DASH_ON, PREVIEW_DASH_OFF)
                gc.dashPhase = 0f
                gc.strokePolyline(outline, closed = kind != ShapeKind.LINE)
                gc.dashPattern = null
                // ★ **末尾探针**（只在自检模式下写，见 [previewDashedFrames]）。
                //   位置有讲究：写在描边**之后**，所以它证明的是
                //   "整段调用都返回了"——而 GL 线程的异常在本项目是被 openglfx 静默吞掉的，
                //   "少画一帧"与"这一帧抛了"在画面上长得一样，只有这个计数能把两者分开。
                if (SELFTEST) previewDashedFrames++
            }
            // 锚点十字：与鼠标重合时虚线退化成零长、什么都看不见，
            // 没有它就分不出"还没有第一点"与"第一点正好在鼠标下"。
            // 颜色用 `DemoShapes.PEN_CROSS`（与文本的笔位十字同一个橙，理由见那个常量的 KDoc）。
            gc.stroke = PEN_CROSS
            gc.drawLine(ax - 8f, anchorY, ax + 8f, anchorY)
            gc.drawLine(ax, anchorY - 8f, ax, anchorY + 8f)
        } else {
            // ② 轨迹型：与第一版一致
            val sx = dragStartX
            if (sx.isNaN()) {
                gc.restore(); return
            }
            val pts = trajectory
            if (pts.size >= 4) gc.strokePolyline(pts, closed = false)
        }
        gc.restore()
    }

    /**
     * 两点式的预览轮廓。**中心/半径/半轴的尺规必须与 [commitTwoPointShape] 一致**——
     * 两处各持一半解释的话，预览与提交结果会不一样，而"预览只是稍微偏一点"最难发现。
     *
     * <p>★ 尤其**圆**：这里是"**圆心 + 到鼠标的距离**"，与 [commitTwoPointShape] 逐字同一条
     * 公式（`hypot` 也是同一份实现，所以两者**逐位相同**，不是"差不多"）。
     * 第一版那个"内切于拖拽框"的尺规在这里**不能出现**——它已经是历史了。
     *
     * @return `[x0,y0, x1,y1, ...]`；退化输入返回 null
     */
    private fun twoPointPreviewOutline(
        kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float
    ): FloatArray? = when (kind) {
        ShapeKind.RECT -> if (abs(x1 - x0) > 0f && abs(y1 - y0) > 0f)
            floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1) else null

        ShapeKind.CIRCLE -> {
            val r = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
            if (r <= 0f) null else circlePoints(x0, y0, r)
        }

        ShapeKind.ELLIPSE -> if (abs(x1 - x0) > 0f && abs(y1 - y0) > 0f)
            ellipsePoints(x0, y0, abs(x1 - x0), abs(y1 - y0)) else null

        ShapeKind.LINE -> if (x1 != x0 || y1 != y0) floatArrayOf(x0, y0, x1, y1) else null
        else -> null
    }

    /** 预览用的圆周折线。段数是**固定的**（预览不追求与 `Gc` 的细分规则一致）。 */
    private fun circlePoints(cx: Float, cy: Float, r: Float): FloatArray =
        ellipsePoints(cx, cy, r, r)

    /** 预览用的椭圆折线。 */
    private fun ellipsePoints(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val seg = PREVIEW_CURVE_SEGMENTS
        val out = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            out[i * 2] = cx + rx * cos(a)
            out[i * 2 + 1] = cy + ry * sin(a)
        }
        return out
    }

    /**
     * 框选矩形：半透明填充 + 描边。**pickId 必须是 0**，否则框选矩形自己会被拾取到。
     *
     * <p>理由与 [drawDragPreview] 同：`pickId = 0` 写在 `save()` 之后，不靠调用方的帧首复位
     * （`save` 会恢复 `pickId`，块内不设就是继承外层）。不加 `try/finally` 的理由也见那里。
     */
    private fun drawMarquee(gc: Gc) {
        val w0 = marqueeW
        if (w0.isNaN()) return
        // ★ 填充与描边**共用同一个规范化结果**（终审 M4）：以前这两句各算一遍
        //   `minOf` / `abs`，改坏一处就会得到两个互相错开的框，而画面上两支框都在、
        //   只是"有点歪"。现在它们不可能不一致——那是结构性的，不是靠约定。
        val r = normalizedRect(marqueeX, marqueeY, marqueeX + w0, marqueeY + marqueeH)
        gc.save()
        gc.pickId = 0
        gc.globalAlpha = 0.25f
        gc.fill = HIGHLIGHT
        gc.fillRect(r.x, r.y, r.width, r.height)
        gc.globalAlpha = 1f
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f
        gc.strokeRect(r.x, r.y, r.width, r.height)
        gc.restore()
    }

    /**
     * 消费待处理的框选请求。**只由 `onRender` 调用（跑在 `endFrame()` 之后）。**
     *
     * <p><strong>★ 为什么不能在 `onFrame` 里做</strong>：`RenderBatch.beginFrame` 会**刻意**
     * 把 `pickBufferValid` 置为 `false`，注释原文是"本帧还没渲染 ID pass 之前，缓冲里装的是
     * 上一帧的结果。标为无效，这样上层的拾取查询会诚实地返回「没命中」，而不是拿陈旧的 ID
     * 去注册表里查——那会拾取到早已消失的对象，而画面完全正常"。
     * 而它只在 `withPickPass`（`endFrame` → `submit` 时）里被置回 `true`。
     *
     * <p>于是 `onFrame` 里调 `gc.pickRect` / `gc.pick` 会**恒返回空**——
     * **框选会永远选不中任何东西，而且不报任何错**。这不是"读到上一帧的陈旧数据"
     * （那样至少静态场景下是对的），而是库**明确拒绝**给你答案。
     *
     * <p>`onRender` 跑在 `endFrame()` **之后**，此刻本帧的 ID pass 刚渲染完、
     * `pickBufferValid` 是 `true`，读回的是**本帧**的拾取结果。它没有 `gc` 参数，
     * 但同一线程上 `bridge.gc()` 拿得到。
     *
     * <p>（顺带一条给文档的口子：`xuan { }` 的 DSL 只暴露 `onRender` 给不了 `Gc`、
     * 也没把 `onRender` 这一层暴露出来，所以**走 DSL 的应用拿不到"帧末拾取"这个时机**。
     * 单像素拾取有 `pickAsync` / `clickAsync` 兜着，区域拾取没有。）
     */
    private fun consumeMarquee(gc: Gc) {
        // ★ **先确认这一帧真的渲染过，再去取请求。**
        //   `FXGLTransfer` 在"本帧没有渲染"（首帧布局未完成、画布尺寸为 0）时**仍然会调
        //   `onRender`**，而那时本帧的 ID pass 没跑过、`pickRect` 只能返回空。
        //   若先 `getAndSet(null)` 再发现查不了，这个请求就被**静默吞掉**了：
        //   选中集被清空、状态栏写"框选到 0 个图形"——用户以为没框到东西。
        //
        // ⚠️ **仍有残留缺口，照实写出来**：`gc.width/height` 是 `beginFrame` 设的，
        //   而"帧被跳过"时 `beginFrame` 不会被调用，所以它保留的是**上一次成功帧**的值。
        //   因此这行只能挡住"还没渲染过任何一帧"（那时它是 0），
        //   挡不住"窗口被缩到 0 尺寸"那类跳过。要彻底挡住，需要 `FXGLTransfer`
        //   只在真的渲染过时才调 `onRender`——那是库的改动，不在本 demo 范围内。
        if (gc.width <= 0 || gc.height <= 0) return
        val r = marqueePending.getAndSet(null) ?: return
        val hits = gc.pickRect(r.x, r.y, r.width, r.height)

        // ★★ **自检第 ⑩ 条的钩子**（只在 `-Dxuan.demo.selftest=1` 时可能为真）。
        //
        //    它模拟的是**真实可达**的那个时序：用户在框选读回还在飞的时候按了 Delete。
        //    Delete 走的是 JavaFX 线程，而这一帧的 ID pass 早在 `endFrame` 时就渲染完了
        //    ——于是"读回拿到了一批号"与"这批号对应的图形已经被删掉"可以同时成立。
        //
        //    **为什么必须在这里注入、而不是在 JavaFX 线程上"等 1 帧再调 deleteSelected()"**：
        //    那是**竞态**，而且几乎总是落在错的一侧。`Platform.runLater` 是 FIFO 的，
        //    本函数末尾那条 `runLater`（把 ids 写进 selection）是**本帧 onRender 里**
        //    发出去的；而 JavaFX 线程上的下一个动作最早也要等到**下一次脉冲**（约 16 ms 后），
        //    那时这条 runLater 早已执行完 —— 于是删除落在它**之后**，删除会把选中集清空，
        //    "新图形被高亮"这个症状永远不会出现，第 ⑩ 条就成了**恒真**的橡皮图章。
        //    钩子放在这两行之间，判据是确定的：**读回已经发生、活快照还没取**。
        //
        //    它调的是 [removeShapes]（生产代码本身），不是一处"专为测试写的近似删除"。
        if (SELFTEST && selfTestDeleteAfterReadback) {
            selfTestDeleteAfterReadback = false
            selfTestDeleteHookRuns++
            // 顺手记下**这次读回命中了哪些号**（零成本，就是一次 map）。第 ⑩ 条的 `ok`
            // 要断言"里面含被删的那个号"——否则读回为空时那条断言会空转通过，见字段说明。
            selfTestHookHitIds = hits.map { it.id() }
            val sel = selection.get()
            // 注册表用**手里这个 `gc`**（本函数的参数，GL 线程上的），不去读 `transfer`
            // ——见 [removeShapes] 的 KDoc：在里面读 `transfer` 就是一处跨线程读。
            removeShapes(sel, gc.pickRegistry)
            // 状态栏要回 JavaFX 线程写（GL 线程不能碰控件）。这条 runLater 排在
            // 本函数末尾那条**之前**，所以最后显示的是框选结果——与真实时序一致。
            Platform.runLater { status.text = "（自检）读回落地前删除 ${sel.size} 个 · 剩 ${shapes.get().size} 个" }
        }

        // ★ **过滤两道，缺一不可**——两个成因都是真的，而且都会让高亮/删除
        //   静默作用到**另一个**图形上：
        //
        //   ① **payload 为 null 的命中**：库明确写了它可达——"刚注销的对象当帧可能仍被画着，
        //      于是命中 `PickHit(id, null, …)`"（见 `Gc.pickRegistry` 的文档）。
        //      凡是按"拾取号"存选中集的地方，都得自己把这条挡掉。
        //   ② **号已经不在 `shapes` 里了**：`PickRegistry` 注销时把号 `push` 进空闲表、
        //      `register` 优先 `pop`——**它 LIFO 复用**，紧接着的下一次注册就把这个号发回去。
        //      留着它，用户随手画的下一个图形会**立刻被高亮框住**，再按 Delete 删掉的是
        //      **那个新图形**，而状态栏照常显示"已删除 1 个图形"。
        //
        //   快照取在 **GL 线程**（用 `shapes.get()`），**不是到 `runLater` 里再取**：
        //   后者中间隔着一整帧，删一个再画一个就可能把号复用回来、又被误选中。
        val live = shapes.get().map { it.pickId }.toSet()
        val ids = hits.filter { it.payload() != null && it.id() in live }.map { it.id() }.toSet()
        // 回 JavaFX 线程更新选中集与状态栏（GL 线程不能碰控件）
        Platform.runLater {
            selection.set(ids)
            status.text = "框选到 ${ids.size} 个图形"
        }
    }

    private fun buildMenuBar(): MenuBar {
        val modeGroup = ToggleGroup()
        fun modeItem(m: Mode) = RadioMenuItem(m.label).apply {
            toggleGroup = modeGroup
            isSelected = (m == Mode.DRAW)
            setOnAction {
                mode = m
                status.text = "模式：${m.label}"
                syncModeProperties()
                // ★ 切模式必须把进行中的交互清掉——否则会**凭空落一个用户没拖过的图形**。
                //   完整的时序与理由见 [resetDragState] 的说明。
                resetDragState()
                // 切模式也是 [cancelAnchor] 四条语义路径里的一条（设计文档 §4.2.2：
                // "已定第一点 ├─ 切模式 / 切图形 ─► 取消"）——换了模式还在等第二点，
                // 用户一点就会画出一个他没想要的东西。
                cancelAnchor()
            }
            modeMenuItems[m] = this     // 只给自检用（第 8~10 条按菜单动作驱动）
        }

        val modeMenu = Menu("模式").apply { items.addAll(Mode.entries.map { modeItem(it) }) }

        val kindGroup = ToggleGroup()
        val kindItems = ShapeKind.entries.map { k ->
            RadioMenuItem(k.label).apply {
                toggleGroup = kindGroup
                isSelected = (k == ShapeKind.RECT)
                setOnAction {
                    kind = k
                    status.text = "当前图形：${k.label}"
                    // ★ **切图形也必须清掉进行中的交互**（与 [modeItem] 那一行同理，也是设计
                    //   文档 §4.2.2 状态机里明写的一条："已定第一点 ├─ 切模式 / 切图形 ─► 取消"）。
                    //   两点式改"点两下"之后，"已定第一点"这个状态**能跨过切图形活下来**——
                    //   不清的话：定下第一点 → 菜单换成另一种图形 → 再点一下，
                    //   就会用**旧锚点**画出**新图形**。用户没表达过这个意图，
                    //   而画面上该图形一切正常（虚线预览在切的那一刹那也跟着换了形状）。
                    resetDragState()
                    cancelAnchor()      // ★ 四条语义路径里的"切图形"
                    // 直线没有"填充"这回事（[Shape.LineShape] 恒走描边），
                    // 所以选中直线时把样式菜单灰掉。不灰的话用户选"只填充"再拖一条线，
                    // 会得到一条**实心描边**的线而界面毫无反馈——那是"设了但没用"的静默失效。
                    styleIsLine.set(k == ShapeKind.LINE)
                }
                kindMenuItems[k] = this    // 只给自检用（第 ⑤ 条要切到"圆"）
            }
        }
        val kindMenu = Menu("图形").apply { items.addAll(kindItems) }

        val styleGroup = ToggleGroup()
        val styleItems = listOf(
            ShapeStyle.FILL to "只填充", ShapeStyle.STROKE to "只描边", ShapeStyle.FILL_AND_STROKE to "填充+描边"
        ).map { (s, label) ->
            RadioMenuItem(label).apply {
                toggleGroup = styleGroup
                isSelected = (s == ShapeStyle.FILL_AND_STROKE)
                // ★ 必须写 `this@XuanDemoApp.style`，**不能**只写 `style`：
                //   `MenuItem` 自己有一个 `style` 属性（String，CSS），
                //   而在 `apply { }` 的接收者作用域里，内层那个会**遮蔽**外层的字段。
                //   只写 `style = s` 会去赋菜单项的 CSS 字符串，编译报
                //   "Assignment type mismatch: actual type is 'ShapeStyle', but 'String!' was expected"。
                //   往 CSS 里塞 "FILL"/"STROKE" 是无效 CSS，所以这里没有第二种读法。
                setOnAction { this@XuanDemoApp.style = s }
                styleMenuItems[s] = this    // 只给自检用（第 ④ 条要切「只描边」）
            }
        }
        val widthGroup = ToggleGroup()
        val widthItems = listOf(1f, 2f, 4f, 8f).map { lw ->
            RadioMenuItem("线宽 ${lw.toInt()}").apply {
                toggleGroup = widthGroup
                isSelected = (lw == 2f)
                setOnAction { lineWidth = lw }
            }
        }
        val styleMenu = Menu("样式").apply { items.addAll(styleItems + SeparatorMenuItem() + widthItems) }

        val colorGroup = ToggleGroup()
        val colorItems = PALETTE.mapIndexed { i, c ->
            RadioMenuItem("颜色 %02X".format((c shr 16) and 0xFF)).apply {
                toggleGroup = colorGroup
                isSelected = (i == 0)
                setOnAction { color = c }
            }
        }
        val colorMenu = Menu("颜色").apply { items.addAll(colorItems) }

        val fontGroup = ToggleGroup()
        val fontItems = FONT_SIZES.mapIndexed { i, fs ->
            RadioMenuItem("${fs.toInt()} px").apply {
                toggleGroup = fontGroup
                isSelected = (i == 1)
                setOnAction { fontSize = fs }
            }
        }
        val fontMenu = Menu("字号").apply { items.addAll(fontItems) }

        val chartGroup = ToggleGroup()
        val chartItems = DemoChart.KINDS.mapIndexed { i, (label, _) ->
            RadioMenuItem(label).apply {
                toggleGroup = chartGroup
                isSelected = (i == 0)
                setOnAction { DemoChart.selectedKind = i }
                chartMenuItems.add(this)    // 只给自检用（第 ⑬、⑭ 条按菜单动作切图型）
            }
        }
        val chartMenu = Menu("图型").apply { items.addAll(chartItems) }

        // 平滑曲线。**只对折线与面积生效**——`STEP`/`SPECTRUM`/`SCATTER`/`BAR` 一律忽略它
        // （见 `Series.smooth` 的 KDoc；频谱那条是硬理由：它的实例属性指向 FFT 的输出缓冲，
        // 而那个缓冲没有邻居余量）。所以切到柱状/散点时勾着它也不会有变化，**这是对的**。
        val smoothItem = CheckMenuItem("平滑曲线（折线 / 面积）").apply {
            isSelected = DemoChart.smoothOn
            setOnAction {
                DemoChart.smoothOn = isSelected
                status.text = if (isSelected) {
                    "平滑曲线：已开（折线与面积；柱状/散点不受影响）"
                } else {
                    "平滑曲线：已关"
                }
            }
        }
        chartMenu.items.addAll(SeparatorMenuItem(), smoothItem)

        val fileMenu = Menu("文件").apply {
            items.addAll(
                MenuItem("清空画布").apply { setOnAction { clearAll() } },
                MenuItem("删除选中").apply { setOnAction { deleteSelected() } },
                SeparatorMenuItem(),
                // 只 exit，不在这里 dispose：GL 资源由 [stop] 释放。
                // 在事件处理器里 dispose 会撞上"上下文可能已失效"那条（见 CLAUDE.md 的线程模型）。
                MenuItem("退出").apply { setOnAction { Platform.exit() } }
            )
        }

        // 按模式启用/禁用：画图时才选图形与样式，文本时才选字号，图表时才选图型。
        // 绑定的是上面那三个 SimpleBooleanProperty，**不是** @Volatile 的 mode——
        // 后者是"跨线程可见"，前者是"可观察"，两件事。
        kindMenu.disableProperty().bind(drawModeActive.not())
        // 样式菜单：非绘图模式灰掉，**且**选中直线时也灰掉（直线恒描边，理由见 kindItems）。
        styleMenu.disableProperty().bind(drawModeActive.not().or(styleIsLine))
        fontMenu.disableProperty().bind(textModeActive.not())
        chartMenu.disableProperty().bind(chartModeActive.not())
        // 颜色三种模式都可用（文本也用颜色）

        // 抗锯齿。**两项的可用性刻意不同**，那正是这一整个菜单要讲的事：
        //   ① 描边与图表系列 —— `Gc.antialias`，运行期状态，**可以随时切**；
        //   ② 填充边缘 —— MSAA，采样数是**帧缓冲**的属性、`GLCanvas` 没有 setter，
        //      **运行期改它没有任何效果**。所以那一项做成**禁用的说明项**而不是
        //      一个点了没反应的复选框——本仓库的口径是"明确拒绝"好过"静默无效"。
        val antialiasItem = CheckMenuItem("描边与图表系列（可随时切）").apply {
            isSelected = antialias
            setOnAction {
                antialias = isSelected
                status.text = if (isSelected) {
                    "抗锯齿：描边与图表系列已开（填充边缘仍由 MSAA 管，见旁边那项）"
                } else {
                    "抗锯齿：已关"
                }
            }
        }
        val msaaItem = MenuItem(
            if (DEMO_MSAA == 0) "填充边缘（MSAA）：当前关，用 -Dxuan.demo.msaa=4 启动可开"
            else "填充边缘（MSAA）：当前 $DEMO_MSAA（启动时给，运行期改不了）"
        ).apply {
            isDisable = true
        }
        val antialiasMenu = Menu("抗锯齿").apply {
            items.addAll(antialiasItem, SeparatorMenuItem(), msaaItem)
        }

        return MenuBar().apply {
            menus.addAll(
                fileMenu, modeMenu, kindMenu, styleMenu, colorMenu, fontMenu, chartMenu, antialiasMenu
            )
        }
    }

    /** 清空画布：全部 unregister 后清列表。**所有权要求注销**，否则 pickRegistry 一直强引用。 */
    private fun clearAll() {
        transfer?.gc()?.let { gc -> shapes.get().forEach { gc.pickRegistry.unregister(it.pickId) } }
        shapes.set(emptyList())
        selection.set(emptySet())
        status.text = "已清空"
    }

    /**
     * 窗口关闭时释放 GL 资源。**不要在 stop 之外的地方 dispose**。
     *
     * <p>自检模式下的退出路径（[selfTestFinish]）会**等这个 latch**：以前那里是
     * `exitProcess` 直退，于是本方法**一次都不会被调到**，每跑一次自检就漏一批
     * GL/D3D 对象。`finally` 是必要的——`dispose` 抛异常时 latch 若不放行，
     * 退出路径会白等满超时（那会把一次有报告的失败变慢，但至少不会变哑）。
     */
    override fun stop() {
        try {
            transfer?.dispose()
        } finally {
            selfTestDisposed.countDown()
        }
    }

    /**
     * [stop] 跑完（含 `dispose`）之后放行。**只在自检模式用**——交互模式下窗口一关
     * 进程就结束了，没有人在等它。
     */
    private val selfTestDisposed = CountDownLatch(1)

    // ==================================================================
    // 合成事件自检（`-Dxuan.demo.selftest=1`；不开的话下面**一行都不跑**）
    // ==================================================================
    //
    // 为什么住在类里而不是另开一个 `*Verifier.kt`：断言要读 `shapes` / `selection` /
    // `status` 这些**私有状态**，只有住在同一个类里才读得到。仓库既有的七个校验器验的是
    // **库**（各自另搭一个场景），这一个验的是**这个 demo 自己的接线**。
    //
    // 它证明不了"画面对"（那是像素校验器的活，而本 demo 没有——画面取决于用户点了哪儿）。
    // 它证明的是**另一件事**：合成事件真的走到了 `wireMouse` 接的那四个处理器上，
    // 坐标换算、拾取、回调、状态更新这一整条链在真 GL 上下文里接通了。
    // 本项目的 GL 线程异常是**静默吞掉**的（吞在 openglfx 的原生回调那层，
    // 我们代码里一处 catch 都没有），所以"没崩"是弱证据；这里的证据形态是
    // **每条断言都打印量到的实际值**——断了哪一环，读出来的数就与期望不一样。

    /**
     * 自检脚本的一段：注入 → **等结果**（`until` 成立，或等满 [budget] 帧）→（可选）读一次数。
     *
     * @param budget 帧预算。`until == null` 时它就是"等这么多帧"；非 null 时它只是兜底，
     *               用满即**记一条失败**（"结果已到"在预算内没成立 ⇒ 这一段之后的断言是在
     *               **还没到**的状态上求值，那正是"被静默跳过的断言"的变体）
     * @param until  "结果已到"的判据。**它必须是与该段断言不同的一个量**——用断言本身当
     *               等待条件会让那条断言退化成恒真（例如第 ⑧ 条等的是"状态栏说框选到了"，
     *               断的是"选择集等于这三个号"）
     */
    private class Segment(
        val budget: Int,
        val drive: () -> Unit,
        val until: (() -> Boolean)? = null,
        val observe: (() -> Unit)? = null
    )

    /** 自检脚本的一步 = 若干段 + **一条**断言（一步正好对应输出里的一行）。 */
    private class Step(val title: String, val segments: List<Segment>, val verify: () -> Unit)

    /** 第 ⑭ 条每一步读到的三样东西：图型下标、拾取号总数、当前 `Chart` 的身份哈希。 */
    private class KindReading(val kind: Int, val ids: Int, val identity: Int)

    /** 自检的脉冲上限。超了判失败退出——**卡死不退出是另一种静默**（报告上什么都看不到）。 */
    private val selfTestPulseLimit = 900

    private var selfTestNode: Node? = null
    private var selfTestScene: Scene? = null
    private var selfTestTimer: AnimationTimer? = null
    private var selfTestSteps: List<Step> = emptyList()

    /**
     * 已评估的断言条数。**`@Volatile` 不是装饰**：看门狗线程（[startSelfTestWatchdog]）
     * 在超时那条路径上要读它，而它与 JavaFX 线程之间**没有任何 happens-before 边**
     * （唯一的同步点 `selfTestDone` 恰恰是"没等到"的那一侧）。
     * 不写 `@Volatile` 的话超时报告里那句「已评估 k/N」可能印出一个陈旧值——
     * 而那个 k 正是"被静默跳过的断言"这一形态下**唯一的读数**，印错了就没有第二处可查。
     */
    @Volatile
    private var selfTestStep = 0
    private var selfTestSeg = 0
    private var selfTestInjected = false
    private var selfTestBaseFrame = 0
    private var selfTestPulses = 0

    /** 失败条数。理由与 [selfTestStep] 相同——看门狗那条路径同样要读它。 */
    @Volatile
    private var selfTestFailures = 0

    /** 脚本自己带来的中间量（**不是**被测状态）。 */
    private var selfTestAId = 0
    private var selfTestDId = 0
    private var selfTestArmed = false
    private var selfTestChartBaseline = 0
    private var selfTestSizeAfterKind0 = 0
    private var selfTestIdentityKind0 = 0
    private var selfTestDrawnBefore = 0

    /** 第 ⑭ 条每一段要等的那个图型（等待条件见 [chartDrawnAfterStep]）。 */
    private var selfTestStepKind = 0
    private val selfTestKindReadings = ArrayList<KindReading>()

    /** 起自检：[start] 在 `stage.show()` 之后调它。 */
    private fun startSelfTest(bridge: FXGLTransfer, node: Node, scene: Scene) {
        selfTestBridge = bridge
        selfTestNode = node
        selfTestScene = scene
        selfTestSteps = buildSelfTestSteps()
        println("[自检-合成] 开始：${selfTestSteps.size} 条断言，由合成事件驱动（不需要人工操作窗口）")
        startSelfTestWatchdog()
        val timer = object : AnimationTimer() {
            override fun handle(now: Long) = selfTestTick()
        }
        selfTestTimer = timer
        timer.start()
    }

    /**
     * 驱动。**在 JavaFX 线程上被调**（`AnimationTimer` 的每个脉冲一次）。
     *
     * <p>为什么是"按帧推进的状态机"而不是一串同步调用：合成事件必须在 **JavaFX 线程**发
     * （`fireEvent` 要碰场景图），而**拾取要走帧**（`clickAsyncAtNode` 的请求在下一帧
     * `endFrame` 之后才提交给 PBO、再下一帧才读回，最后经 `Platform.runLater` 回到
     * JavaFX 线程）。两边的时间尺度不同，只能"注入 → 等 N 帧 → 断言"。
     */
    private fun selfTestTick() {
        selfTestPulses++
        if (selfTestPulses > selfTestPulseLimit) {
            // 超时**判失败**而不是继续等：结果永远不来时挂在这里，报告上写着"跑了"
            // 而后面几条断言一次都没被评估——那比失败更坏。
            check(
                "脚本超时", false,
                "已过 $selfTestPulses 次脉冲（帧计数 $frameCount），停在「${selfTestSteps.getOrNull(selfTestStep)?.title}」" +
                        "——它之后的断言一条都没被评估"
            )
            return selfTestFinish()
        }
        if (selfTestStep >= selfTestSteps.size) return selfTestFinish()

        val step = selfTestSteps[selfTestStep]
        val seg = step.segments[selfTestSeg]
        if (!selfTestInjected) {
            // 一帧都还没渲染过时**坚决不注入**：那时 `beginFrame` 还没跑过、
            // 拾取缓冲里没有任何东西，注入的事件只会白等一场。
            if (frameCount == 0) return
            seg.drive()
            selfTestInjected = true
            selfTestBaseFrame = frameCount
            return
        }
        val elapsed = frameCount - selfTestBaseFrame
        val ready = if (seg.until != null) {
            if (seg.until.invoke()) {
                true
            } else if (elapsed >= seg.budget) {
                // 预算用满而"结果已到"仍不成立：**判失败**，不能继续当没事发生——
                // 后面的断言会在"还没到"的状态上求值，而那种失败读起来像是断言本身错了。
                check(
                    "${step.title} —— 等待超预算", false,
                    "等了 $elapsed 帧（预算 ${seg.budget}）「结果已到」仍不成立；这一段的断言会在**还没到**的状态上求值，读数不可信"
                )
                true
            } else {
                false
            }
        } else {
            elapsed >= seg.budget
        }
        if (!ready) return
        seg.observe?.invoke()
        selfTestSeg++
        if (selfTestSeg < step.segments.size) {
            selfTestInjected = false
            return
        }
        step.verify()
        selfTestStep++
        selfTestSeg = 0
        selfTestInjected = false
    }

    private fun selfTestFinish() {
        selfTestTimer?.stop()
        selfTestTimer = null
        // 让看门狗线程收工（见 [startSelfTestWatchdog]）——**必须在这条路径上放下**，
        // 否则正常跑完也会被 60 秒后那一下 halt(1) 打断。
        selfTestDone.countDown()
        println()
        // ★ **分母是"已评估"而不是"总共"**：超时/挂死那条路径走到这里时，后面的断言
        //   一次都没跑。写成"断言 14 条，失败 1 项"读起来像"14 条里只坏了 1 条"，
        //   而实际可能是"只跑了 4 条、剩下 7 条从没被评估"——本仓库那条判据（"被静默
        //   跳过的断言比失败的断言更坏"）说的就是这种报告。
        println("[自检-合成] 已评估 ${selfTestStep}/${selfTestSteps.size} 条断言，失败 $selfTestFailures 项")
        if (selfTestFailures == 0) {
            println("[自检-合成] 全部通过")
        } else {
            println("[自检-合成] 失败 $selfTestFailures 项，见上面的 ★ 失败 行")
        }

        // ★ 退出路径**先走 `Platform.exit()`**，让 JavaFX 真的跑一遍 `stop()` → `dispose()`。
        //   以前这里直接 `exitProcess`，于是 `stop()` **从不执行**：每跑一次自检就漏一批
        //   GL/D3D 对象。（这是**卫生项**，与"自检会不会间歇性失败"无关——那条的真因是
        //   开关解析分叉，见 [selfTestEnabled]。）
        //
        //   ⚠️ **等待必须在另一个线程上做**：本函数就在 JavaFX 线程上（AnimationTimer 的
        //   脉冲里），而 `stop()` 也要 JavaFX 线程才能跑——在这里等它，等于把要用的线程
        //   占住，必然等满超时。所以另起一个**非 daemon** 线程等 latch：非 daemon 保证
        //   JVM 不会在 `Platform.exit()` 之后、退出码还没落定之前就走掉。
        val code = if (selfTestFailures == 0) 0 else 1
        Platform.exit()
        Thread({
            val disposed = selfTestDisposed.await(DISPOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (disposed) {
                println("[自检-合成] stop()/dispose() 已跑完，GL 资源已释放")
            } else {
                // 出声而不是静默：清理没跑完是**已知会漏对象**的那种情况，
                // 而它的症状（下一次运行的行为）与这里毫无关系，不写下来就查不到。
                System.err.println(
                    "[自检-合成] ★ 警告：Platform.exit() 之后 ${DISPOSE_TIMEOUT_SECONDS} 秒内" +
                            " stop()/dispose() 没跑完，直接退出（这一轮会漏掉一批 GL/D3D 对象）"
                )
            }
            System.out.flush()
            System.err.flush()
            // 退出码在这里落定。**必须是显式退出**：JavaFX 自己的关停路径不设退出码，
            // 而 `Application.launch()` 返回后 JVM 会以 0 收尾——失败的那次会被报成"成功"。
            //
            // ★ **用 `Runtime.halt(code)`，不是 `exitProcess(code)`** —— 退出码一样，
            //   但 `halt` **不跑关闭钩子**。理由是实测撞出来的：`exitProcess` 会去跑钩子，
            //   而此刻 **JavaFX 线程正在 `stop()` 之后继续它自己的关停**（`Platform.exit()`
            //   只是开始拆，钩子与它**并发**碰 GL/D3D）——5 次经过这条退出路径的运行里
            //   崩过 **1 次原生 ACCESS_VIOLATION**（`0xC0000005`，maven 报退出值
            //   `-1073741819`），而**当次 12+11 条断言全绿**。那种崩法把"全过"报成了
            //   非 0 退出，正是本仓库最防的"报告与事实相反"。
            //   `halt` 不会引入新的并发：latch 是在 `stop()` 的 `finally` 里放的，
            //   所以走到这里**我们自己的 `dispose()` 已经返回**。
            //   这条理由在本文件里不是新发明——看门狗那处早就写着同样的话
            //   （见 [startSelfTestWatchdog]："后者要跑关闭钩子，而此刻我们可能正卡在
            //   GL 回调里"）。
            Runtime.getRuntime().halt(code)
        }, "xuan-selftest-exit").apply { isDaemon = false }.start()
    }

    /**
     * 看门狗：**脉冲停了就没人再调 [selfTestTick]，那时既不退出也不报失败、进程就那么挂着**
     * （窗口被 iconify 时 JavaFX 会暂停主定时器，正是这个形态），而挂死会留下孤儿 JVM。
     * 所以另起一个 daemon 线程，60 秒还没跑完就打两行然后**硬停**。
     *
     * <p>**必须是 `Runtime.halt(1)` 而不是 `exitProcess(1)`**：后者要跑关闭钩子，
     * 而此刻我们可能正卡在 GL 回调里（那正是"脉冲停了"的另一种形态）——钩子里再去碰
     * GL/JavaFX，就是把一次有报告的失败换成一个没报告的挂死。`halt` 跳过钩子，直接停。
     *
     * <p>daemon 属性是必须的：主线程（JavaFX）先退出时它不该拖住 JVM。
     */
    private fun startSelfTestWatchdog() {
        val watchdog = Thread({
            if (!selfTestDone.await(SELFTEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                println(
                    "[自检-合成] 超时：${SELFTEST_TIMEOUT_SECONDS} 秒内没跑完，且**脉冲已经停了**" +
                            "（窗口被最小化/遮挡时 JavaFX 会暂停主定时器，`selfTestTick` 一次都不会再被调）"
                )
                println("[自检-合成] 已评估 $selfTestStep/${selfTestSteps.size} 条断言，失败 $selfTestFailures 项")
                System.out.flush()
                Runtime.getRuntime().halt(1)
            }
        }, "xuan-selftest-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    /** 记一条断言。**每条都打印量到的实际值**：只打印"通过"的话，断言写反了也照样通过。 */
    private fun check(name: String, pass: Boolean, detail: String) {
        println("[自检-合成] $name -> $detail${if (pass) "" else "   ★ 失败"}")
        if (!pass) selfTestFailures++
    }

    // ---- 合成事件（全部在 JavaFX 线程上） ----

    /** 画布逻辑尺寸里的一点的 x（自检坐标一律按**比例**给，免得写死的像素在别的缩放下出界）。 */
    private fun nx(f: Double): Double = (selfTestNode?.layoutBounds?.width ?: 800.0) * f

    private fun ny(f: Double): Double = (selfTestNode?.layoutBounds?.height ?: 600.0) * f

    /**
     * 在画布节点的**局部坐标** [lx]/[ly] 上合成一次鼠标事件。
     *
     * <p>坐标先经 `localToScene`：`MouseEvent` 的 `x/y` 在派发链上会被当成**场景坐标**
     * 逐级换算到源节点的局部空间，直接塞局部坐标会被再换算一次
     * （`ClickVerifier` 记着这条：本容器的内边距会让它正好差 5 像素）。
     *
     * <p>`pickResult` 传 `null`：合成事件**不参与命中测试**（`fireEvent` 本来就不做），
     * 而 `MouseEvent` 会自己用一个以传入坐标为交点的 `PickResult` 兜底——
     * 那正是"按场景坐标换算到局部"这条路能走通的原因。
     */
    private fun fireMouse(
        type: EventType<MouseEvent>, lx: Double, ly: Double,
        button: MouseButton, primaryDown: Boolean, secondaryDown: Boolean, clicks: Int
    ) {
        val node = selfTestNode ?: return
        val scene = node.localToScene(lx, ly)
        val screen = node.localToScreen(lx, ly) ?: Point2D(0.0, 0.0)
        node.fireEvent(
            MouseEvent(
                type, scene.x, scene.y, screen.x, screen.y, button, clicks,
                false, false, false, false,        // shift / ctrl / alt / meta
                primaryDown, false, secondaryDown, // 左 / 中 / 右——拖拽那半靠 isXxxButtonDown 判
                false, false,                      // synthesized / popupTrigger（合成事件不参与拾取）
                true,                               // stillSincePress：与真实单击一致
                null                                // pickResult
            )
        )
    }

    private fun firePress(lx: Double, ly: Double, secondary: Boolean = false) = fireMouse(
        MouseEvent.MOUSE_PRESSED, lx, ly, if (secondary) MouseButton.SECONDARY else MouseButton.PRIMARY,
        !secondary, secondary, 1
    )

    private fun fireDrag(lx: Double, ly: Double, secondary: Boolean = false) = fireMouse(
        MouseEvent.MOUSE_DRAGGED, lx, ly, if (secondary) MouseButton.SECONDARY else MouseButton.PRIMARY,
        !secondary, secondary, 0
    )

    private fun fireReleaseP(lx: Double, ly: Double, secondary: Boolean = false) = fireMouse(
        MouseEvent.MOUSE_RELEASED, lx, ly, if (secondary) MouseButton.SECONDARY else MouseButton.PRIMARY,
        !secondary, secondary, 1
    )

    /** 一次完整拖拽：按下 → 中间三个点 → 抬起。**位移必须 ≥ `CLICK_SLOP`**，否则会被当成单击。 */
    private fun dragFromTo(fx0: Double, fy0: Double, fx1: Double, fy1: Double) {
        firePress(nx(fx0), ny(fy0))
        for (i in 1..3) {
            val t = i / 4.0
            fireDrag(nx(fx0) + (nx(fx1) - nx(fx0)) * t, ny(fy0) + (ny(fy1) - ny(fy0)) * t)
        }
        fireReleaseP(nx(fx1), ny(fy1))
    }

    /**
     * 一次**左键**单击：按下与抬起在**同一个点**（`moved == 0 < CLICK_SLOP`）。
     *
     * <p>2026-09-25 起左键单击有**两种**含义，由 `anchorX` 是不是 NaN 分（见 [onRelease]）：
     * 两点式上是"定第一点"或"提交"，轨迹型上仍是"拾取选中"（那条路径没改）。
     * **拾取选中在两点式上已经挪到右键**（见 [clickRight]）。
     */
    private fun clickAt(fx: Double, fy: Double) {
        firePress(nx(fx), ny(fy))
        fireReleaseP(nx(fx), ny(fy))
    }

    /**
     * 一次**右键**单击 —— 两点式上的"拾取选中"（2026-09-25 从 [clickAt] 移到这里）。
     *
     * <p>按下与抬起**必须在同一个点**：`onRelease` 那条右键分支用 `CLICK_SLOP` 把
     * "只按不拖 = 拾取"与"拖着 = 框选"分开，位移一超就落进后者（见 [marqueeFromTo]）。
     */
    private fun clickRight(fx: Double, fy: Double) {
        firePress(nx(fx), ny(fy), secondary = true)
        fireReleaseP(nx(fx), ny(fy), secondary = true)
    }

    /**
     * 一次**鼠标移动**（**没有键按下**）—— 虚线预览唯一的输入源。
     *
     * <p>`button = NONE` 且三个 down 标志全 `false` 是**判据的一部分**：它必须是
     * "没有键按下"的那一种，否则与 `MOUSE_DRAGGED` 无异，而这条链要证的恰恰是
     * "**虚线在没有键按下时也跟得上鼠标**"（`MOUSE_DRAGGED` 只在按键期间才有，
     * 所以只注册它是不够的——那正是 [wireMouse] 多注册一个 `MOUSE_MOVED` 的理由）。
     */
    private fun moveTo(fx: Double, fy: Double) {
        fireMouse(MouseEvent.MOUSE_MOVED, nx(fx), ny(fy), MouseButton.NONE, false, false, 0)
    }

    /** 按**菜单动作**切图形种类（不是给 `kind` 字段赋值——见 [kindMenuItems] 的说明）。 */
    private fun fireKind(k: ShapeKind) {
        kindMenuItems[k]?.fire()
    }

    /**
     * 合成一次 `Esc` 按键，**走真实的事件路径**（`stage.scene.setOnKeyPressed` 那个处理器）。
     * 理由与 [fireDeleteKey] 同：那条接线本身也是被测的东西。
     */
    private fun fireEscKey() {
        val sc = selfTestScene ?: return
        Event.fireEvent(sc, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
    }

    /** 右键框选：按下 → 拖到另一点 → 抬起。**释放点不参与矩形**（`onRelease` 用的是 `marqueeW/H`）。 */
    private fun marqueeFromTo(fx0: Double, fy0: Double, fx1: Double, fy1: Double) {
        firePress(nx(fx0), ny(fy0), secondary = true)
        fireDrag(nx(fx1), ny(fy1), secondary = true)
        fireReleaseP(nx(fx1), ny(fy1), secondary = true)
    }

    /**
     * 合成一次 Delete 按键，**走真实的事件路径**（`Scene.setOnKeyPressed` 那个处理器）。
     *
     * <p>不直接调 [deleteSelected]：那条接线本身也是被测的东西——`stage.scene.setOnKeyPressed`
     * 里那两个 `KeyCode` 是这次交互唯一的键盘入口，写错了症状是"按 Delete 没反应"。
     */
    private fun fireDeleteKey() {
        // 用 `Event.fireEvent(scene, e)` 而不是 `scene.fireEvent(e)`：`fireEvent(Event)` 是
        // **`Node` 上**的方法，`Scene` 没有（`EventTarget` 只有 `buildEventDispatchChain`）。
        val sc = selfTestScene ?: return
        Event.fireEvent(sc, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false))
    }

    /** 按**菜单动作**切图型（不是改 `DemoChart.selectedKind` 常量——见 [chartMenuItems] 的说明）。 */
    private fun fireChartKind(index: Int) {
        chartMenuItems.getOrNull(index)?.fire()
    }

    private fun gcNow(): Gc? = transfer?.gc()

    private fun registrySize(): Int = gcNow()?.pickRegistry?.size() ?: 0

    /**
     * 自检脚本。**一步 = 输出里的一条断言**，共 14 条。
     *
     * <p><strong>★ 2026-09-25 起手势是"点两下"</strong>（两点式图形：矩形 / 圆 / 椭圆 / 直线），
     * 拾取选中挪到**右键只按不拖**。所以 ①②③④ 与 ⑩⑫ 都由
     * `clickAt`（左键，定第一点 / 第二点提交）与 `clickRight`（右键，拾取）驱动，
     * 而 `dragFromTo` 只剩两处用途：轨迹型，以及 ⑦ 那条"两点式上拖拽什么都不做"的**反面**断言。
     * 新增的三条是 ⑤（圆的新尺规）、⑥（`Esc` 取消）、⑦（拖拽不做）。
     *
     * <p>坐标全是**比例**（相对画布逻辑尺寸），断言里也按同一套比例算期望值——
     * 于是脚本不会因为系统缩放变了就落到图形外面（125% 下画布是 1113x—，不是 900x700）。
     *
     * <p><strong>★ 但"比例"这条便利恰恰会掩盖一整类缺陷</strong>：所有包含关系在
     * **整体等比缩放**下都不变，所以 `wireMouse` 里那个 `* deviceScale(node)` 被删掉、
     * 或写成 `* s * s`，按比例量的断言**一条都不会倒**（而症状是"点 A 命中 B / 图形画在
     * 别处，画面完全正常"——本仓库最防的那类）。所以 ① 与 ⑤ 额外钉了**设备坐标的绝对值**
     * （见那两条的断言），那也是全脚本仅有的不与比例共变形的判据。
     *
     * <p>`Segment(budget, drive = {...})` 里那个数**不是"等几帧"**，而是**帧预算**：
     * 依赖拾取往返的段一律给 `until = { … }`（等"结果已到"），预算只是兜底——
     * 用固定帧数是本仓库 `ClickVerifier` 栽过的坑（"帧号判据依赖'提交帧 + N'这条推断，
     * 而结果是异步的、落点会飘"），而"等条件"比它强：能区分"交付了"与"还没交付"。
     */
    private fun buildSelfTestSteps(): List<Step> = listOf(

        // ① 点两下画出一个矩形：状态栏要变成"已画："，shapes 从 0 变 1
        //
        // ★ 2026-09-25 起两点式图形（矩形/圆/椭圆/直线）是**点两下**，不再是拖拽。
        //   所以这一段是"点 1 → 移动鼠标 → 点 2"，而**中间那一步单独占一段**：
        //   虚线预览的探针要在"鼠标已经移动到对角、但还没点第二下"的那个窗口里读
        //   （提交之后锚点被清、预览分支整条不再跑，计数就停在原地了）。
        Step(
            "① 点两下画出矩形（虚线预览真的跟着鼠标）",
            listOf(
                // 第一下：定锚点。**等的是"锚点被设上了"**——它与本条断言量的是两回事。
                Segment(6, drive = { clickAt(0.06, 0.10) }, until = { !anchorX.isNaN() }),
                // 移动鼠标：虚线预览唯一的输入源。**只给帧预算、不给 `until`**——
                // 等待条件若取"探针涨了"，那条断言在 verify 时就成了恒真
                // （本仓库明令禁止：`Segment` 的 KDoc 就是这么写的）。
                Segment(6, drive = { selfTestPreviewBefore = previewDashedFrames; moveTo(0.26, 0.34) }),
                // 第二下：提交
                Segment(6, drive = { clickAt(0.26, 0.34) }),
            ),
            {
                val s = shapes.get()
                selfTestAId = s.firstOrNull()?.pickId ?: 0
                // ★★ **全脚本唯一一处不与比例共变形的判据**：图形落到**设备像素**的哪里。
                //    `nx/ny` 给的是节点**局部**坐标，而 `wireMouse` 的契约是"把局部坐标乘
                //    `deviceScale(node)` 变成设备像素"（`Gc` 只认设备像素）。只按比例量的
                //    包含关系在**整体等比缩放**下不变 ⇒ 那个 `* deviceScale(node)` 被删掉
                //    （或写成 `* s * s`）时，前面那些断言**一条都不会倒**——而症状是
                //    "图形画在别处 / 点 A 命中 B，画面完全正常"。所以这里把**绝对值**钉住：
                //    期望 = 注入的局部坐标 × `deviceScale`，容差 1px。
                //    （实测：`0.06 × 884 × 1.25 = 66.3`，实测 x=66.0 —— 见每次运行的读数。）
                val scale = selfTestBridge?.deviceScale(selfTestNode!!) ?: 1.0
                val b = s.firstOrNull()?.shape?.bounds()
                val expX = nx(0.06) * scale
                val expY = ny(0.10) * scale
                val expW = (nx(0.26) - nx(0.06)) * scale
                val geomOk = b != null && abs(b.x - expX) <= 1f && abs(b.y - expY) <= 1f &&
                        abs(b.width - expW) <= 1f
                // ★ **虚线预览那半的证据**（见 [previewDashedFrames]）：锚点定完、鼠标
                //   移到对角、**还没有点第二下**的那些帧里，`drawDragPreview` 的两点式分支
                //   必须真的跑完过。这一项是**独立于几何**的另一条链（事件 → `onMove` →
                //    `previewX/Y` → 轮廓 → 虚线描边），几何全对而它恒 0 是完全可能的。
                val previewGrew = previewDashedFrames - selfTestPreviewBefore
                val ok = s.size == 1 && s[0].shape is Shape.RectShape && status.text.startsWith("已画：") &&
                        geomOk && previewGrew > 0
                check(
                    "① 点两下画出矩形（shapes 0→1、状态栏「已画：」、**设备坐标等于局部×缩放**、" +
                            "**虚线预览真的画了**)",
                    ok,
                    "shapes.size=${s.size}，第一个=${s.firstOrNull()?.shape?.describe() ?: "无"}" +
                            "（pickId=$selfTestAId），状态栏=「${status.text}」；" +
                            "设备坐标实测=(${b?.x},${b?.y},${b?.width})，期望=($expX,$expY,$expW)" +
                            "（= 局部 (${nx(0.06)},${ny(0.10)}) × deviceScale $scale），容差 1px；" +
                            "虚线预览画了 $previewGrew 帧（期望 ≥1）"
                )
            }
        ),

        // ② **右键**点它内部：命中回调必须被交付，且选中集里是它
        //
        // ★ 2026-09-25 起拾取选中从"左键单击"移到"**右键只按不拖**"（设计文档 §4.2 的手势表）：
        //   第一下左键现在是"定起点"，不能同时是"选中"。
        //
        // ★ 等待条件（`until`）一律是"**结果已到**"，不是"等 N 帧"：固定帧数是本仓库
        //   `ClickVerifier` 栽过的坑（"帧号判据依赖'提交帧 + N'这条推断，而结果是异步的、
        //   落点会飘"）。`HIT_DONE` 的判据是**交付计数涨了**——它与本条断言量的是两回事
        //   （断言量的是"交给的是哪一个"），所以不会让断言退化成恒真。
        Step(
            "② 右键单击选中",
            listOf(
                Segment(
                    12, drive = { selfTestHitBefore = pickHitCount; clickRight(0.16, 0.22) },
                    until = { pickHitCount > selfTestHitBefore }
                )
            ),
            {
                val sel = selection.get()
                val ok = sel == setOf(selfTestAId) && pickHitCount - selfTestHitBefore == 1 &&
                        status.text.startsWith("命中：")
                check(
                    "② 右键单击图形内部 → 命中并选中（回调真的被交付了）", ok,
                    "selection=$sel（期望 {${selfTestAId}}），命中回调本次 ${pickHitCount - selfTestHitBefore} 次" +
                            "（累计 $pickHitCount）/ 未命中累计 $pickMissCount 次，状态栏=「${status.text}」"
                )
            }
        ),

        // ③ 再画一个**与它重叠**的，点重叠处：后画的赢（z 序）
        Step(
            "③ 重叠处命中的是后画的",
            listOf(
                Segment(6, drive = { clickAt(0.16, 0.22) }, until = { !anchorX.isNaN() }),
                Segment(6, drive = { clickAt(0.36, 0.46) }),
                Segment(
                    12, drive = { selfTestHitBefore = pickHitCount; clickRight(0.20, 0.26) },
                    until = { pickHitCount > selfTestHitBefore }
                ),
            ),
            {
                val s = shapes.get()
                val sel = selection.get()
                val b = s.getOrNull(1)
                val bId = b?.pickId ?: 0
                val ok = s.size == 2 && b?.shape is Shape.RectShape &&
                        sel == setOf(bId) && bId > selfTestAId
                check(
                    "③ 重叠处命中的是后画的那个（z 序，号更大）", ok,
                    "shapes.size=${s.size}，第一个号=$selfTestAId，第二个=${b?.shape?.describe()}" +
                            "（号=${b?.pickId}），selection=$sel，状态栏=「${status.text}」"
                )
            }
        ),

        // ④ 切「样式 → 只描边」再画一个，点它**内部**：不命中（拾取只看几何）
        Step(
            "④ 只描边的内部点不中",
            listOf(
                Segment(6, drive = {
                    // ★ 走菜单动作（`MenuItem.fire()`），不是直接给 `style` 字段赋值——
                    //   菜单里那一行的 `this@XuanDemoApp.style = s` 才是生产路径。
                    styleMenuItems[ShapeStyle.STROKE]?.fire()
                    clickAt(0.50, 0.10)
                }, until = { !anchorX.isNaN() }),
                Segment(6, drive = { clickAt(0.70, 0.34) }),
                // 记基准再点：这个计数器是**全脚本累计**的，写死绝对值会随前面某条也点空过而错
                //（实测第一版就栽在这里——第 ④ 条自己就是一次未命中）。
                // 等的是"**未命中回调也交付了一条**"：这一条验的正是"点空了"，
                // 所以必须等它真的交付，不能拿"没交付"当"没命中"。
                Segment(
                    12, drive = { selfTestMissBefore = pickMissCount; clickRight(0.60, 0.22) },   // 离边框 ≥ 8 像素的内部
                    until = { pickMissCount > selfTestMissBefore }
                ),
            ),
            {
                val s = shapes.get()
                val sel = selection.get()
                val c = s.getOrNull(2)?.shape
                val ok = s.size == 3 && c?.style == ShapeStyle.STROKE && sel.isEmpty() &&
                        status.text.startsWith("未命中")
                check(
                    "④ 只描边的图形：点内部**不**命中（拾取只看几何，与填充无关）", ok,
                    "shapes.size=${s.size}，第三个=${c?.describe()}（样式=${c?.style}），" +
                            "selection=$sel，未命中回调本次 ${pickMissCount - selfTestMissBefore} 次" +
                            "（累计 $pickMissCount），状态栏=「${status.text}」"
                )
            }
        ),

        // ⑤ ★ 圆的两点式尺规 —— **与第一版不同**（第一版是"内切于拖拽框"）。
        //
        // 为什么单独立一条：这条尺规在 2026-09-25 改过，而**改错的方向是静默的**——
        // 圆心取两点中点、半径取 `min(|dx|,|dy|)/2`（旧尺规）时，画出来的**仍然是一个
        // 正常的圆**，半径甚至可能"看着差不多"。所以圆心与半径**两项都要断**：
        // 只断半径，"圆心取中点"能蒙过去；只断圆心，`min/2` 照样过。
        //
        // ★ 第二点**刻意取在对角**（不是同一行）：那样两种尺规给出**都非零但不同**的半径
        //   （`hypot(dx,dy)` vs `min/2`），差异是"值错了"而不是"退化成零、什么都没画"。
        //   同一行的话旧尺规会得到 `r = 0` ⇒ 图形压根不出现 ⇒ 断言虽然也倒，
        //   但倒成了"没画出来"，**指向的是一种与真因不同的病**。
        Step(
            "⑤ ★ 圆的尺规：圆心 = 锚点、半径 = 锚点到第二点的距离",
            listOf(
                Segment(
                    6, drive = { fireKind(ShapeKind.CIRCLE); clickAt(0.22, 0.58) },
                    until = { !anchorX.isNaN() }),
                Segment(6, drive = { clickAt(0.30, 0.70) }),
            ),
            {
                val s = shapes.get()
                val c = s.lastOrNull()?.shape as? Shape.CircleShape
                // 期望值按**设备像素**算（与 ① 同一口径）：两点的局部坐标各乘一次 `deviceScale`。
                val scale = selfTestBridge?.deviceScale(selfTestNode!!) ?: 1.0
                val ax = nx(0.22) * scale
                val ay = ny(0.58) * scale
                val expR = hypot(nx(0.30) * scale - ax, ny(0.70) * scale - ay)
                // `c.cx` 是 Float、`ax` 是 Double ⇒ 减法已是 Double，不用再 `toDouble()`。
                val dCenter = if (c == null) Double.NaN else hypot(c.cx - ax, c.cy - ay)
                val dR = if (c == null) Double.NaN else abs(c.r - expR)
                val ok = s.size == 4 && c != null && dCenter <= 1.0 && dR <= 1.0
                check(
                    "⑤ ★ 圆的尺规：圆心 = **锚点**（不是两点中点）、半径 = **锚点到第二点的距离**" +
                            "（不是 min(|dx|,|dy|)/2）", ok,
                    "shapes.size=${s.size}，最后一个=${c?.describe() ?: "无（不是圆）"}；" +
                            "圆心实测=(${c?.cx},${c?.cy})，期望锚点=($ax,$ay)，偏差=${"%.3f".format(dCenter)}px；" +
                            "半径实测=${c?.r}，期望=$expR（= hypot 两个设备像素点），偏差=${"%.3f".format(dR)}px；容差 1px"
                )
            }
        ),

        // ⑥ `Esc` 取消"已定第一点"（2026-09-25 新增的手势）。
        //
        // ★ 判别式**分两半**，缺一半就会"空转通过"：
        //   ① `Esc` 之后 `shapes` **不变**——但只断这个的话，"压根没点中、锚点从没设上"
        //      也满足它（`selfTestSizeBeforeEsc` 就是留着堵这条的：它钉住"按 Esc 之前确实
        //      有 N 个图形"，而 N 是前面四条真的画出来的）；
        //   ② 再点一下**不会**接着上一点画出来（`shapes` 仍然不变）。
        //   ② 才是 `Esc` 真正的语义——`Esc` 若没清掉锚点，这一下就会**提交**一个图形。
        Step(
            "⑥ Esc 取消「已定第一点」",
            listOf(
                Segment(
                    6, drive = { selfTestSizeBeforeEsc = shapes.get().size; clickAt(0.42, 0.82) },
                    until = { !anchorX.isNaN() }),
                Segment(6, drive = { fireEscKey() }, until = { anchorX.isNaN() }),
                // 再点一下：**不该**接着上一点画东西，而应该只是重新定了个第一点。
                // 等的是"锚点又被设上了"——它与本条断言量的是两回事（断言量的是 shapes 没涨）。
                Segment(6, drive = { clickAt(0.56, 0.92) }, until = { !anchorX.isNaN() }),
            ),
            {
                val sizeNow = shapes.get().size
                val ok = selfTestSizeBeforeEsc > 0 && sizeNow == selfTestSizeBeforeEsc
                check(
                    "⑥ Esc 取消：取消后 shapes 不变，且再点一下**不会**接着上一点画出来", ok,
                    "按 Esc 之前 shapes.size=$selfTestSizeBeforeEsc（期望 >0），" +
                            "三步走完 shapes.size=$sizeNow（期望与之前相同）；" +
                            "（此刻锚点已被最后那一下重新设上，收尾会清掉）"
                )
                // ★ 把这一条自己留下的锚点收掉。不收的话下一条（⑦）一进 `onRelease`
                //   就会拿它**提交**一个图形，而 ⑦ 的断言是"拖拽什么都不做"——
                //   它会因为一个与本条无关的原因倒，读起来像 ⑦ 的实现坏了。
                cancelAnchor()
            }
        ),

        // ⑦ 两点式上做"拖拽"**什么都不做**（设计文档 §4.2.2 的三处细节之三）。
        //
        // 这条守的是"同一个图形不能既靠拖又靠点"：手势改了之后，旧习惯（拖着画矩形）
        // 必须**明确失效并给出提示**，而不是静默地什么都不发生——后者用户会以为程序卡了。
        // ★ 换回矩形在这一步做（下游的 ⑫ 按 `RectShape` 断言），走的是"切图形"那条
        //   生产路径，它自己会清锚点。
        Step(
            "⑦ 两点式上「拖拽」什么都不做（状态栏提示「请点两下」）",
            listOf(
                Segment(6, drive = {
                    fireKind(ShapeKind.RECT)
                    selfTestSizeBeforeDrag = shapes.get().size
                    dragFromTo(0.60, 0.06, 0.78, 0.24)
                }),
            ),
            {
                val ok = selfTestSizeBeforeDrag > 0 && shapes.get().size == selfTestSizeBeforeDrag &&
                        status.text.contains("请点两下")
                check(
                    "⑦ 两点式上拖拽**什么都不做**（shapes 不变、状态栏提示「请点两下」）", ok,
                    "拖之前 shapes.size=$selfTestSizeBeforeDrag（期望 >0），拖之后 ${shapes.get().size}；" +
                            "状态栏=「${status.text}」（期望含「请点两下」）"
                )
            }
        ),

        // ⑧ 右键框选全部四个（2026-09-25 起是四个：多了一个 ⑤ 画出来的圆）
        Step(
            "⑧ 右键框选全部",
            listOf(
                Segment(
                    // ★ 框选矩形**放大到几乎整块画布**（原来是 `0.02,0.04 → 0.80,0.50`）：
                    //   ⑤ 那个圆在 y≈0.46~0.70，而 `pickRect` 只要有**一个像素**的几何落在
                    //   矩形里就算命中——旧矩形会**擦着圆的顶边**，于是"选到几个"取决于
                    //   圆顶那一两个像素压在 0.50 的哪一侧。那种判据在换字体/换窗口尺寸后
                    //   会变成"本次只框到 3 个"，而画面看起来完全正常。
                    //   取整块画布，四个图形**无条件**都在里面。
                    12, drive = { marqueeFromTo(0.02, 0.02, 0.96, 0.96) },
                    // 等的是"读回**落地**了"（状态栏被那次 runLater 改掉），阈值取"不再是
                    // 上一帧那句话"——**不能**用"选择集等于这几个号"当等待条件，那正好是
                    // 本条的断言，会让它恒真。
                    until = { status.text.startsWith("框选到") }
                )
            ),
            {
                val ids = shapes.get().map { it.pickId }.toSet()
                val sel = selection.get()
                val ok = ids.size == 4 && sel == ids
                check(
                    "⑧ 框选到全部 ${ids.size} 个（状态栏「框选到 N 个图形」）", ok,
                    "selection=$sel（期望 $ids），状态栏=「${status.text}」"
                )
            }
        ),

        // ⑥ 合成 Delete 键删掉选中，再点原位置：不命中
        //
        // ★★ **这一条曾经会"空转通过"**：若 ①~⑤ 连锁失败（图形压根没画出来、框选没选中
        //   任何东西），`deleteSelected()` 会在"选中集为空"处**提前 return**（只写一句
        //   "没有选中任何图形"），而那次点击当然也不命中 ⇒ `isEmpty` + 未命中计数 +1 +
        //   状态栏"未命中" **三项全真** ⇒ ⑥ 打印 PASS。**"什么都没做"与"做对了"在读数上
        //   分不开**，这正是"测试会不会骗人"的形态。修法是把它删之前有几个图形、以及
        //   删除那一刻状态栏说了什么**都记下来并断言**——与 ⑦ 设 `armed` 挡的是同一条。
        Step(
            "⑨ Delete 删除 + 原位置不命中",
            listOf(
                Segment(6, drive = {
                    selfTestDeleteBefore = shapes.get().size
                    fireDeleteKey()
                    // 状态栏要在**这一行**读：下一段那次点击会把状态栏改写成"未命中…"。
                    selfTestDeleteStatus = status.text
                }),
                Segment(
                    12, drive = { selfTestMissBefore = pickMissCount; clickRight(0.16, 0.22) },   // 第一个矩形原来的位置
                    until = { pickMissCount > selfTestMissBefore }
                ),
            ),
            {
                val ok = selfTestDeleteBefore == 4 &&       // 前面八条真的画出了 4 个（矩形×3 + 圆）
                        selfTestDeleteStatus.startsWith("已删除 4 个图形") &&   // 删除**真的执行了**
                        shapes.get().isEmpty() && pickMissCount - selfTestMissBefore == 1 &&
                        status.text.startsWith("未命中")
                check(
                    "⑨ 合成 Delete 键删掉选中（走 Scene 的按键处理器），原位置不再命中", ok,
                    "删除前 shapes.size=${selfTestDeleteBefore}（期望 4），删除那一刻状态栏=「$selfTestDeleteStatus」" +
                            "（期望以「已删除 4 个图形 · 剩 0 个」开头），删后 shapes.size=${shapes.get().size}，" +
                            "未命中回调本次 ${pickMissCount - selfTestMissBefore} 次（累计 $pickMissCount），" +
                            "状态栏（点击后）=「${status.text}」"
                )
            }
        ),

        // ⑩ ★ LIFO 复用的判别式 —— 见 consumeMarquee 里那个钩子的长注释
        Step(
            "⑩ ★ 框选读回落地前按 Delete → LIFO 回收的号不该被高亮",
            listOf(
                // 先回到填充+描边（上一步为了第 ④ 条切成了只描边，而只描边的**内部点不中**，
                // 这一步却要先靠一次单击把 D 选上——所以样式必须能填）。
                Segment(6, drive = {
                    styleMenuItems[ShapeStyle.FILL_AND_STROKE]?.fire()
                    clickAt(0.50, 0.50)
                }, until = { !anchorX.isNaN() }),
                // 第二下：提交 D。observe 里记下 D 的号——最后那条断言要判的正是
                // "那个被回收的号"，而它只有在 D **真的被画出来之后**才存在
                //（所以 observe 挂在这一段，不能挂在定锚点那一段）。
                Segment(
                    6, drive = { clickAt(0.70, 0.72) },
                    observe = { selfTestDId = shapes.get().lastOrNull()?.pickId ?: 0 }),
                // **右键**单击 D 的内部 → 选中集 = {D}。**这一步的落定要等**（拾取往返 2~3 帧），
                // 所以它单独占一段：`until` 等"命中回调交付了"（**不是**等"选中集等于 {D}"，
                // 那是 observe 里的 `armed`，是这条链的下一个环节），observe 里记下
                // "选中集真的落在 D 上了"——不然最后那条断言会在"压根没选中"时**恒真**。
                Segment(
                    12, drive = { selfTestHitBefore = pickHitCount; clickRight(0.60, 0.61) },
                    until = { pickHitCount > selfTestHitBefore },
                    observe = { selfTestArmed = selection.get() == setOf(selfTestDId) }
                ),
                // 框选 D + **在读回之后、活快照之前**删掉它（钩子）。
                // 等的是"钩子跑过了"= 那一帧的读回已经发生、且删除已经作用在活快照上；
                // 断言再去看它到底读到了什么、以及新图形有没有被高亮。
                Segment(
                    12,
                    drive = { marqueeFromTo(0.46, 0.46, 0.76, 0.76); selfTestDeleteAfterReadback = true },
                    until = { selfTestDeleteHookRuns >= 1 }
                ),
                // 再画一个新图形：它会拿到刚刚被回收的那个号
                Segment(6, drive = { clickAt(0.20, 0.60) }, until = { !anchorX.isNaN() }),
                Segment(6, drive = { clickAt(0.36, 0.80) }),
            ),
            {
                val e = shapes.get().lastOrNull()
                val recycled = e != null && e.pickId != 0 && e.pickId == selfTestDId
                val highlighted = e?.pickId in selection.get()
                // ★ **`hookHitIds` 那一项堵的是"空转通过"**：读回若是空的（`pickRect` 什么都没
                //   读到），"活快照过滤"根本没参与运算，而 `armed`/`hookRuns`/`recycled` 全成立
                //   ⇒ ⑦ 会通过。要求"读回里含被删的那个号"，才把"过滤把它滤掉了"与
                //   "这次压根没读到号"分开。
                val sawD = selfTestDId in selfTestHookHitIds
                val ok = selfTestArmed && selfTestDeleteHookRuns >= 1 && sawD && recycled && !highlighted
                check(
                    "⑩ ★ 框选读回落地前按 Delete：LIFO 回收的号不该被高亮", ok,
                    "钩子执行 $selfTestDeleteHookRuns 次；那一次读回命中的号=${selfTestHookHitIds}" +
                            "（含被删的 $selfTestDId=$sawD）；点中 D=${selfTestArmed}；" +
                            "新图形=${e?.shape?.describe()}（号=${e?.pickId}，复用了那个号=$recycled）；" +
                            "selection=${selection.get()}"
                )
            }
        ),

        // ⑪ 切文本模式，单击落一段字
        //
        // 这两段**都是同步**的（菜单动作改 `mode`；TEXT 模式下 `onRelease` 直接
        // `commitText`，不走拾取），所以用帧预算而不是 `until`——但预算仍要够一帧：
        // 模式位是 GL 线程在下一帧读的。
        Step(
            "⑪ 文本模式落字",
            listOf(
                Segment(3, drive = { modeMenuItems[Mode.TEXT]?.fire() }),
                Segment(3, drive = { clickAt(0.12, 0.60) }),
            ),
            {
                val s = shapes.get()
                val last = s.lastOrNull()?.shape
                val ok = last is Shape.TextShape && status.text.startsWith("落字：")
                check(
                    "⑪ 文本模式：合成 press+release 落下一个 TextShape", ok,
                    "shapes.size=${s.size}，最后一个是 ${last?.describe() ?: "无"}" +
                            "（pickId=${s.lastOrNull()?.pickId ?: -1}），状态栏=「${status.text}」"
                )
            }
        ),

        // ⑫ 切回绘图模式再**点两下**：落下的必须是图形而不是文字（模式没串）
        Step(
            "⑫ 切回绘图模式",
            listOf(
                Segment(3, drive = { modeMenuItems[Mode.DRAW]?.fire() }),
                Segment(6, drive = { clickAt(0.60, 0.80) }, until = { !anchorX.isNaN() }),
                Segment(6, drive = { clickAt(0.76, 0.92) }),
            ),
            {
                val s = shapes.get()
                val last = s.lastOrNull()?.shape
                val ok = last is Shape.RectShape && status.text.startsWith("已画：")
                check(
                    "⑫ 切回绘图模式：落下的是**图形**不是文字（模式没有串）", ok,
                    "shapes.size=${s.size}，最后一个是 ${last?.describe() ?: "无"}，状态栏=「${status.text}」"
                )
            }
        ),

        // ⑬ 切到图表模式（**合成菜单动作**，不是改 mode 常量）
        Step(
            "⑬ 切到图表模式",
            listOf(
                Segment(
                    12, drive = {
                        selfTestChartBaseline = registrySize()
                        selfTestDrawnBefore = DemoChart.selfTestDrawnFrames
                        modeMenuItems[Mode.CHART]?.fire()
                    },
                    // 等"图表**画过两帧**"（末尾探针涨了 2）。用探针当等待条件是**与被测
                    // 实现无关**的一个量：无论缓存对不对，图表模式每帧都会跑完 `draw`。
                    // 拿"拾取号涨了 2"当等待条件就糟了——那正是本条的断言（变异 B 下它会
                    // 一直等不到，于是这条失败会被报成"工序超预算"而不是"重建没发生"）。
                    until = { DemoChart.selfTestDrawnFrames >= selfTestDrawnBefore + 2 }
                )
            ),
            {
                val grew = DemoChart.selfTestDrawnFrames - selfTestDrawnBefore
                val ids = registrySize() - selfTestChartBaseline
                // 末尾探针按帧递增 = 整条绘制路径跑到了末尾（本项目 GL 线程的异常是静默吞掉的，
                // "少画了东西"与"抛了异常"在画面上长得一样，只有这个计数能把两者分开）。
                // 拾取号 +2 = 两条系列在 `ChartRenderer` 里注册了号 —— 那只能发生在
                // `gc.charts`（懒创建）已经建出来并走进了 `drawChart` 之后。
                // （`grew >= 2` 已被上面的等待条件保证，留着是为了让这行读数自解释；
                //   **承重的是 `ids == 2`** —— 它同时挡住"没建出来"与"建了不止一次"。）
                val ok = mode == Mode.CHART && grew >= 2 && ids == 2
                check(
                    "⑬ 图表模式：draw 的末尾探针按帧到达 + gc.charts 被创建（两条系列注册了号）", ok,
                    "mode=${mode.label}，末尾探针 +$grew 帧（等待条件要求 ≥2），拾取号 +$ids（期望恰好 2）"
                )
                selfTestIdentityKind0 = DemoChart.selfTestLastChartIdentity
                selfTestSizeAfterKind0 = registrySize()
                selfTestKindReadings.clear()
            }
        ),

        // ⑭ ★ 四种图型遍历：每一步都要**真的重建** Chart（身份跳变），
        //    而**切回已经建过的图型时必须回到原来那个实例**（缓存生效 = 菜单没变死）。
        //    这一步判的正是"欠重建"那个盲区：`chart()` 变成永远返回同一个实例时，
        //    身份不再跳变，而画面看起来毫无问题（只是菜单变死）。
        //
        // ★ **号那一条在 2026-09-26 反过来了**（本次改动）。它以前断言"每换一种 +2、
        //   切回旧的复用（号不变）"——那测的是 `DemoChart.cachedCharts` 这张表在防泄漏。
        //   现在 `ChartRenderer` 自己会回收（`releaseUnused`，由 `Gc.beginFrame` 每帧调一次，
        //   见那边的文档）：**切走的图型那两条系列连续两帧没被画过就被释放、号被归还**，
        //   所以号数**不再随切换次数增长**。新判据正是这件事的反面读法：
        //   `r[i].ids <= r[i-1].ids`——每切一次，图表占的号**只可能减，不可能增**。
        //   这条对"库不回收"是定向敏感的（那时每切一种新图型就 +2，序列严格递增）。
        //
        //   为什么读数是 6 而不是 2：读数取在"新图型刚画过一帧"那一刻，而宽限是**两帧**，
        //   所以上一个图型那两条号**这一帧还没被归还**。判据因此写成 `<=` 而不是 `==`：
        //   它量的是"没有增长"，不是"恰好等于某个数"——后者会随读数的时机而变。
        //
        // ★ 每段的等待条件是"**图表又画了一帧**"（探针涨 1），不是"身份变了"——
        //   后者在变异 B 下永远等不到，那这条失败就会被报成"工序超预算"，
        //   读起来像测试坏了而不是实现坏了。探针则与缓存对不对无关。
        Step(
            "⑭ ★ 四种图型遍历",
            listOf(
                Segment(
                    12, drive = { chartKindStep(1) }, until = { chartDrawnAfterStep() },
                    observe = { selfTestKindReadings.add(reading()) }),
                Segment(
                    12, drive = { chartKindStep(2) }, until = { chartDrawnAfterStep() },
                    observe = { selfTestKindReadings.add(reading()) }),
                Segment(
                    12, drive = { chartKindStep(3) }, until = { chartDrawnAfterStep() },
                    observe = { selfTestKindReadings.add(reading()) }),
                Segment(
                    12, drive = { chartKindStep(0) }, until = { chartDrawnAfterStep() },
                    observe = { selfTestKindReadings.add(reading()) }),
                Segment(
                    12, drive = { chartKindStep(1) }, until = { chartDrawnAfterStep() },
                    observe = { selfTestKindReadings.add(reading()) }),
            ),
            {
                val r = selfTestKindReadings
                val chartIds = registrySize() - selfTestChartBaseline
                val ok = r.size == 5 &&
                        r[0].identity != selfTestIdentityKind0 &&
                        r[1].identity != r[0].identity &&
                        r[2].identity != r[1].identity &&
                        r[3].identity == selfTestIdentityKind0 &&
                        r[4].identity == r[0].identity &&
                        r[1].ids <= r[0].ids && r[2].ids <= r[1].ids &&
                        r[3].ids <= r[2].ids && r[4].ids <= r[3].ids &&
                        chartIds <= 8
                val series = if (r.size == 5) r.joinToString(" → ") {
                    "${DemoChart.KINDS[it.kind].first}:号${it.ids}/身份${it.identity}"
                } else "只记到 ${r.size} 步"
                check(
                    "⑭ ★ 遍历四种图型：每换一种就重建（身份跳变）、切回旧的复用（身份回归）；" +
                            "而号**不随切换增长**（切走的那两条被库回收、号被归还）", ok,
                    "$series ；起点（折线）身份=$selfTestIdentityKind0、进入图表模式时号=$selfTestSizeAfterKind0；" +
                            "图表系列一共占 $chartIds 个拾取号（上限 8 = 4 图型 × 2 系列）。" +
                            "四个号读数必须是**不增**的——递增就说明回收没发生（每换一种新图型 +2）"
                )
            }
        ),
    )

    /** 第 ⑭ 条的一段：按菜单切成 [index] 号图型。等待条件见 [chartDrawnAfterStep]。 */
    private fun chartKindStep(index: Int) {
        selfTestStepKind = index
        fireChartKind(index)
    }

    /**
     * 第 ⑭ 条那一段的等待条件：**某一帧真的用了这一段要的那个图型**。
     *
     * <p>**不能用"又画了一帧"当判据**（第一版就是这么写的，实测倒了）：菜单在 JavaFX 线程
     * 改 `selectedKind`，而 GL 线程的那一帧**可能已经跑过 `chart()`** ⇒ 紧接着那帧画的
     * 仍是**旧图型**，"涨了一帧"当场成立，读数就是把**旧实例**贴上**新图型**的标签
     * （实测：第一段的身份与起点**相同**、号也没涨）。探针 `selfTestLastDrawnKind` 直接
     * 回答"这一帧用的哪个图型"，正是那个缺的判据。
     */
    private fun chartDrawnAfterStep(): Boolean = DemoChart.selfTestLastDrawnKind == selfTestStepKind

    /**
     * 第 ⑭ 条每步读一次：**图型下标读的是生产状态**（`DemoChart.selectedKind`），
     * 不是脚本"想切成哪一个"。
     *
     * <p>这个区别只在失败报告上体现，而那正是这份输出的全部意义：脚本意图与生产状态
     * 不一致时（菜单没接上、菜单被禁用、切了但没生效），按意图贴标签会把**实际画出来的**
     * 图型说成另一个——让人照着错的标签去查。
     */
    private fun reading() = KindReading(DemoChart.selectedKind, registrySize(), DemoChart.selfTestLastChartIdentity)
}

/**
 * 应用入口。
 *
 * <p>**没有它，Step 4 那条运行命令跑不起来**——命令打的是 `XuanDemoKt`，
 * 而那个类里得有一个顶层 `main()` 才谈得上入口。缺了它的报错是
 * `在类 com.bingbaihanji.xuan.example.demo.XuanDemoKt 中找不到 main 方法`，
 * **不是编译错**（编译能过），所以特别容易漏。
 *
 * <p>`@JvmName("main")` 在这里**不需要**：`ClickExample.kt` 之所以要它，是因为
 * `example` 包里已经有一个顶层 `main()`（`PipelineExample.kt`）会造成重载歧义；
 * 而本文件在 `example.demo` 这个**新包**里，没有同包冲突。
 */
fun main() {
    Application.launch(XuanDemoApp::class.java)
}
