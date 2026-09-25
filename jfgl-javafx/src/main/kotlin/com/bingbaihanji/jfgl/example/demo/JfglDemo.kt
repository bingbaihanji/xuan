package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit
import com.bingbaihanji.jfgl.util.Rect
import com.bingbaihanji.jfgl.view.MainView
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
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
 */
internal const val SELFTEST_PROPERTY = "jfgl.demo.selftest"

/**
 * 是否处于**合成事件自检**模式（`-Djfgl.demo.selftest=1`）。
 *
 * <p>**默认关闭，且关掉时自检一行都不跑**（连 [JfglDemoApp.frameCount] 都不自增）——
 * 这不是"少跑点"，而是生产路径**零开销**这条承诺：自检的所有状态都只在
 * `if (SELFTEST)` 里被碰。
 *
 * <p>为什么要有它：这个 demo **没有像素校验器**（画面取决于用户点了哪儿，没有可断言的
 * 判据），所以"画出来的图形对不对、点得中不中"到 Task 10 为止**只有静态证据**。
 * 合成事件走的是 `wireMouse` 接的那三条真实处理器（`ClickVerifier` 已证明这条路通），
 * 于是交互闭环第一次有了运行时证据。它**不能**替代像素校验器（它断言的是状态，
 * 不是画面），但它能证明"事件 → 换算 → 拾取 → 回调 → 状态"这条链真的接通了。
 */
private val SELFTEST: Boolean = System.getProperty(SELFTEST_PROPERTY) == "1"

/** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是 (0.2,0.2,0.2)。 */
private const val BG = 0xFF23262B.toInt()

/** 选中高亮色。 */
private const val HIGHLIGHT = 0xFFFFEB3B.toInt()

/** 小于这个位移（设备像素）的一次按下-抬起算**单击**，否则算拖拽。 */
private const val CLICK_SLOP = 4.0

/** 八色预设色板。 */
private val PALETTE = intArrayOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt()
)

/** 文本模式的字号档位。 */
private val FONT_SIZES = floatArrayOf(14f, 22f, 32f, 48f)

/** 文本模式的样本串（按落字序号轮换）。不随机——画面要可复现。 */
private val TEXT_SAMPLES = arrayOf(
    "JFGL immediate-mode text",
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
 * @param pickId 0 表示尚未注册（此时不参与拾取，但仍照常画）
 */
data class Placed(val shape: Shape, val pickId: Int)

class JfglDemoApp : Application() {

    private var transfer: FXGLTransfer? = null
    private val status = Label("拖动鼠标画一个矩形").apply {
        padding = Insets(6.0, 10.0, 6.0, 10.0)
        style = "-fx-font-size: 13px; -fx-text-fill: #1b1b1b;"
    }

    // ---- JavaFX 线程写、GL 线程读的状态。**分三类，别混。** ----
    //
    //   ① **引用快照**（`shapes` / `selection` / `marqueePending`）：整表替换、
    //      从不原地改，所以 GL 线程读到的永远是一个自洽的快照；
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
     * [drawScene] / [drawDragPreview] / [onDrag] 读，`kind` 被 [drawDragPreview] 读，
     * 而它们由菜单在 JavaFX 线程写——所以需要 `@Volatile`。
     */
    @Volatile private var mode = Mode.DRAW
    @Volatile private var kind = ShapeKind.RECT

    // ---- 以下是**只在 JavaFX 线程**读写的状态，因此**不需要** `@Volatile`。 ----
    //
    //   它们的唯一读点是 [commitShape]（以及 Task 8 的 `commitText`），两者都在
    //   JavaFX 线程上（由鼠标事件 / 菜单触发）；即使 Task 8 把 `fontSize` 装进
    //   `TextShape` 再交给 GL 线程使用，那个交接也发生在"构造快照"这一步，
    //   而不是靠这个字段跨线程可见。
    //   **别顺手给它们加 `@Volatile`**——那会让人以为它们跨线程，从而看不出
    //   真正的跨线程字段是哪两个。

    /** 新画图形用的样式 / 颜色 / 线宽。**只在 JavaFX 线程读写。** */
    private var style = ShapeStyle.FILL_AND_STROKE
    private var color = PALETTE[0]
    private var lineWidth = 2f

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

    // ---- 以下是**合成事件自检**（`-Djfgl.demo.selftest=1`）专用的状态 ----
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
     * <p>第 8~11 条要按"**菜单动作**"驱动，不能去改 `mode` / `selectedKind` 常量：
     * 改常量绕过了"菜单 → 字段 → 下一帧重建"这条**端到端链路**，而那条链才是要验的东西
     * （比如 `chart()` 变成永远返回缓存时，改常量照样全绿，而菜单变死）。
     * `MenuItem.fire()` 是纯 JavaFX 线程逻辑，能直接驱动它
     * （顺带：它连**禁用**状态都不判，"菜单被灰掉了"这件事得靠断言里的行为看出来）。
     *
     * <p>它们是**启动时一次性 put 的**（三处 `buildMenuBar` 里的记账），
     * 每帧一次都不碰——见上面那段"唯一的一处例外"。
     */
    private val modeMenuItems = HashMap<Mode, RadioMenuItem>()
    private val styleMenuItems = HashMap<ShapeStyle, RadioMenuItem>()
    private val chartMenuItems = ArrayList<RadioMenuItem>()

    /** 已在 [drawScene] 里执行过的帧数。**只在自检模式下自增**（见 [SELFTEST]）。 */
    @Volatile private var frameCount = 0

    /** 拾取回调交付的命中 / 未命中次数。用来核对"回调真的被交付了"，而不只是"状态栏碰巧对"。 */
    private var pickHitCount = 0
    private var pickMissCount = 0

    /**
     * 自检第 7 条的一次性钩子：让 [consumeMarquee] **在拾取读回之后、活快照之前**
     * 执行一次"删除"。完整的时序理由见那个钩子所在的注释。
     */
    @Volatile private var selfTestDeleteAfterReadback = false

    /** 上面那个钩子真的执行了几次（GL 线程写、JavaFX 线程读）。**必须 >0 才说明第 7 条测的是那条路径**。 */
    @Volatile private var selfTestDeleteHookRuns = 0

    /**
     * 每一步开始前记下的交付计数基准，用来判**这一次**交付了几条。
     *
     * <p>为什么不用累计值的绝对值：这个计数器是全脚本累计的，写死期望值会随"前面某条
     * 也点空过"而变。实测第一版就在这上面栽了——第 ④ 条自己就是一次未命中，
     * 于是第 ⑥ 条那句 `pickMissCount == 1` 恒为假。
     */
    private var selfTestHitBefore = 0
    private var selfTestMissBefore = 0

    /** 模式切换后同步那三个属性。**只在 JavaFX 线程调用。** */
    private fun syncModeProperties() {
        drawModeActive.set(mode == Mode.DRAW)
        textModeActive.set(mode == Mode.TEXT)
        chartModeActive.set(mode == Mode.CHART)
    }

    /** 拖拽预览：起点（设备像素）。NaN 表示没有正在进行的拖拽。 */
    @Volatile private var dragStartX = Float.NaN
    @Volatile private var dragStartY = Float.NaN

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
    @Volatile private var trajectory = FloatArray(0)

    /**
     * 待框选的矩形（左, 上, 宽, 高，设备像素）。`marqueeW` 为 NaN 表示没有框选在进行。
     *
     * <p>这四个是**分别发布**的（见类顶部第 ③ 类），所以 GL 线程可能读到
     * "新的 `marqueeW` + 旧的 `marqueeH`"——**允许**，只影响那一帧选框的高度。
     * 真正跨线程交付给 GL 线程的那个矩形走的是下面 [marqueePending]（一个不可变对象），
     * **没有**这个问题——这个对照正是"为什么交付用一个对象、而预览用四个标量"的理由。
     */
    @Volatile private var marqueeX = Float.NaN
    @Volatile private var marqueeY = 0f
    @Volatile private var marqueeW = Float.NaN
    @Volatile private var marqueeH = 0f

    /** 待处理的框选请求（GL 线程消费）。**用对象快照，不用上面那四个标量。** */
    private val marqueePending = AtomicReference<Rect?>(null)

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
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
        stage.title = "JFGL 交互式 Demo"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.scene.setOnKeyPressed { e ->
            if (e.code == KeyCode.DELETE || e.code == KeyCode.BACK_SPACE) deleteSelected()
        }
        stage.show()

        // ★ 合成事件自检在 `stage.show()` **之后**起：窗口要先 show 过，
        //   画布节点才有 `scene`/`window`（[FXGLTransfer.deviceScale] 从 `scene.window.outputScaleY`
        //   取缩放系数，没 show 之前恒为 1.0——那时合成事件算出来的设备坐标与真实点击
        //   不是同一个口径，第 1 条之后全会落到别的地方）。
        if (SELFTEST) startSelfTest(view, stage.scene)
    }

    // ---- 鼠标接线（JavaFX 线程） ----

    /**
     * 接上按下 / 拖拽 / 抬起。
     *
     * <p>**每一次都要乘 [FXGLTransfer.deviceScale]**：鼠标事件给的是节点的**逻辑**局部坐标，
     * 而 [Gc] 要的是**设备像素**。漏乘的表现是"图形画在别的地方"或"点 A 命中 B"，
     * 而画面本身完全正常——在 100% 缩放的机器上还一切正常。
     */
    private fun wireMouse(bridge: FXGLTransfer, node: Node) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED) { e -> onPress(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED) { e -> onDrag(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_RELEASED) { e -> onRelease(bridge, node, e) }
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
     * 清掉"有一次交互正在进行"的全部状态：拖拽起点、轨迹、以及框选的有效性标志。
     *
     * <p>**必须在三处调用**，否则不变式"`dragStartX` 非 NaN ⟺ 真的有一次 DRAW 拖拽在进行"
     * 不被任何东西维护：
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
     * <p>③ 是最容易漏的那一处，而漏掉的后果是**静默错画**：
     * 绘图模式按下并拖动 → **按住不放**、用键盘切到文本 → 松开（走 `Mode.TEXT -> Unit`，
     * 什么都不清）→ 切回绘图。此刻残留的 `dragStartX` 让 [drawDragPreview] **凭空画一个预览框**；
     * 更糟的是接着在任意处按下再松开时，`moved` 从那个**旧起点**量起、
     * [commitShape] 还会用**旧轨迹**，于是**落下一个用户从没拖过的图形**，
     * 而状态栏正常显示"已画：…"——正是本仓库最防的那类"静默错画"。
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

    private fun onRelease(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        if (e.button == MouseButton.SECONDARY) {
            if (!marqueeW.isNaN()) {
                val r = Rect(minOf(marqueeX, marqueeX + marqueeW), minOf(marqueeY, marqueeY + marqueeH),
                    abs(marqueeW), abs(marqueeH))
                marqueePending.set(r)          // GL 线程在下一帧消费
                resetDragState()               // 见它的说明：顺带取消掉可能在进行的主键拖拽
            }
            return
        }
        if (e.button != MouseButton.PRIMARY) return

        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        val moved = if (dragStartX.isNaN()) 0.0 else hypot((dx - dragStartX).toDouble(), (dy - dragStartY).toDouble())

        when (mode) {
            Mode.DRAW -> {
                if (moved < CLICK_SLOP) {
                    // 单击：拾取选中。**把换算后的设备坐标一起带进回调**——见 [onPick] 的说明。
                    resetDragState()
                    bridge.clickAsyncAtNode(node, e.x, e.y) { hit -> onPick(hit, dx, dy) }
                } else {
                    commitShape(dx, dy)
                    resetDragState()
                }
            }
            // 文本：**在抬起时落字**（`onPress` 的 TEXT 分支是 `Unit`，不记起点），
            // 所以这里用 `moved` 把"单击落字"与"拖着划了一下什么都没有"分开。
            //
            // ★ 这条分支只清 `dragStartX`，**不是** [resetDragState]。两处都写清楚了：
            //   ① `moved` 在 `dragStartX` 为 NaN 时**恒为 0.0**（见上面的 `moved` 计算），
            //      所以 `moved >= CLICK_SLOP` 只可能出现在"起点是残留的"那种情形——
            //      而这里清掉那个残留就够了；
            //   ② TEXT 模式下 `trajectory` 与 `marqueeW` 都不参与画面：预览框由
            //      [drawDragPreview] 画，而它在非 DRAW 模式**直接返回**。
            //   （Task 4 留下的那句"别忘了在末尾也 resetDragState()"是当时 `commitText`
            //     还不存在时的占位提醒；计划 Task 8 给的是这个更窄的写法，理由如上。）
            Mode.TEXT -> {
                if (moved < CLICK_SLOP) commitText(dx, dy) else dragStartX = Float.NaN
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
                    Shape.CircleShape(sx + w / 2f, sy + h / 2f, minOf(abs(w), abs(h)) / 2f,
                        color, style, lineWidth)
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

    /** 落一段文字。**在 JavaFX 线程上被调用**（从 onRelease）。 */
    private fun commitText(x: Float, y: Float) {
        val sample = TEXT_SAMPLES[textSeq % TEXT_SAMPLES.size]
        textSeq++
        shapes.set(shapes.get() + Placed(
            Shape.TextShape(sample, x, y, fontSize, color), 0   // 文本不参与拾取（Task 8 不做文本拾取）
        ))
        status.text = "落字：「$sample」${fontSize.toInt()}px，笔位 ($x,$y)，基线 y=$y"
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
        if (SELFTEST) { if (item == null) pickMissCount++ else pickHitCount++ }
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
        if (sel.isEmpty()) { status.text = "没有选中任何图形"; return }
        removeShapes(sel)
        status.text = "已删除 ${sel.size} 个图形 · 剩 ${shapes.get().size} 个"
    }

    /**
     * 把给定拾取号对应的图形从画布上摘掉（注销 + 移出列表 + 清空选中集）。
     *
     * <p>**它只碰三样线程安全的东西**（`pickRegistry` / `shapes` / `selection`），
     * 所以**GL 线程上也能调**——自检第 7 条正是要在 GL 线程的那一帧里调它
     * （见 [consumeMarquee] 里那段钩子）。状态栏是 JavaFX 控件，**不在这里写**，
     * 由调用方负责（[deleteSelected] 在 JavaFX 线程上直接写；钩子那边走 `runLater`）。
     *
     * @param sel 要删掉的拾取号集合
     */
    private fun removeShapes(sel: Set<Int>) {
        // 所有权：选定集必须 unregister，否则 pickRegistry 会一直强引用着它们
        transfer?.gc()?.let { gc -> sel.forEach { gc.pickRegistry.unregister(it) } }
        shapes.set(shapes.get().filter { it.pickId !in sel })
        selection.set(emptySet())
    }

    /** 画一帧。**在 GL 线程上执行**，不要在这里碰任何 JavaFX 控件。 */
    private fun drawScene(gc: Gc) {
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
                // （Task 8 的文本按规格注册成 `pickId = 0`，会实际走到这一支。）
                p.shape.draw(gc)
            }
        }
        // 选中高亮画在所有图形之后，此时 pickId 已被 pickable 复原成 0。
        //
        // **这里也不加 `try/finally`**，与 [drawDragPreview] / [drawMarquee] 一致——
        // 理由见那两处的 KDoc：`body` 抛异常会让 `FXGLTransfer` 的 `endFrame()` 整趟被跳过、
        // 此后每帧都抛，`finally` **挡不住**真正的风险。这里写一句是免得下一个人以为是漏了。
        //
        // 顺带：`bounds()` 只被这里用，而它的约定是"尺寸非负"（见 `Shape.bounds()` 的 KDoc）。
        // `commitShape` 用 `planar` 保证了这一点；Task 8 的 `TextShape` 尺寸项也恒非负
        // （`size × 字符数` 与 `size × 1.3`），所以不会出现负尺寸矩形。
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
     * 拖拽预览：两点定义的那四种画一个临时图形；轨迹定义的那两种画原始轨迹。
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
     */
    private fun drawDragPreview(gc: Gc) {
        if (mode != Mode.DRAW) return
        val sx = dragStartX
        val sy = dragStartY
        if (sx.isNaN()) return
        val pts = trajectory
        gc.save()
        gc.pickId = 0
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f
        if (kind.isTrajectory) {
            if (pts.size >= 4) gc.strokePolyline(pts, closed = false)
        } else {
            val last = if (pts.size >= 2) Pair(pts[pts.size - 2], pts[pts.size - 1]) else Pair(sx, sy)
            gc.strokeRect(minOf(sx, last.first), minOf(sy, last.second),
                abs(last.first - sx), abs(last.second - sy))
        }
        gc.restore()
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
        gc.save()
        gc.pickId = 0
        gc.globalAlpha = 0.25f
        gc.fill = HIGHLIGHT
        gc.fillRect(minOf(marqueeX, marqueeX + w0), minOf(marqueeY, marqueeY + marqueeH), abs(w0), abs(marqueeH))
        gc.globalAlpha = 1f
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f
        gc.strokeRect(minOf(marqueeX, marqueeX + w0), minOf(marqueeY, marqueeY + marqueeH), abs(w0), abs(marqueeH))
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
     * <p>（顺带一条给文档的口子：`jfgl { }` 的 DSL 只暴露 `onRender` 给不了 `Gc`、
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

        // ★★ **自检第 7 条的钩子**（只在 `-Djfgl.demo.selftest=1` 时可能为真）。
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
        //    "新图形被高亮"这个症状永远不会出现，第 7 条就成了**恒真**的橡皮图章。
        //    钩子放在这两行之间，判据是确定的：**读回已经发生、活快照还没取**。
        //
        //    它调的是 [removeShapes]（生产代码本身），不是一处"专为测试写的近似删除"。
        if (SELFTEST && selfTestDeleteAfterReadback) {
            selfTestDeleteAfterReadback = false
            selfTestDeleteHookRuns++
            val sel = selection.get()
            removeShapes(sel)
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
                    // 直线没有"填充"这回事（[Shape.LineShape] 恒走描边），
                    // 所以选中直线时把样式菜单灰掉。不灰的话用户选"只填充"再拖一条线，
                    // 会得到一条**实心描边**的线而界面毫无反馈——那是"设了但没用"的静默失效。
                    styleIsLine.set(k == ShapeKind.LINE)
                }
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
                // ★ 必须写 `this@JfglDemoApp.style`，**不能**只写 `style`：
                //   `MenuItem` 自己有一个 `style` 属性（String，CSS），
                //   而在 `apply { }` 的接收者作用域里，内层那个会**遮蔽**外层的字段。
                //   只写 `style = s` 会去赋菜单项的 CSS 字符串，编译报
                //   "Assignment type mismatch: actual type is 'ShapeStyle', but 'String!' was expected"。
                //   往 CSS 里塞 "FILL"/"STROKE" 是无效 CSS，所以这里没有第二种读法。
                setOnAction { this@JfglDemoApp.style = s }
                styleMenuItems[s] = this    // 只给自检用（第 4 条要切「只描边」）
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
                chartMenuItems.add(this)    // 只给自检用（第 10、11 条按菜单动作切图型）
            }
        }
        val chartMenu = Menu("图型").apply { items.addAll(chartItems) }

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

        return MenuBar().apply {
            menus.addAll(fileMenu, modeMenu, kindMenu, styleMenu, colorMenu, fontMenu, chartMenu)
        }
    }

    /** 清空画布：全部 unregister 后清列表。**所有权要求注销**，否则 pickRegistry 一直强引用。 */
    private fun clearAll() {
        transfer?.gc()?.let { gc -> shapes.get().forEach { gc.pickRegistry.unregister(it.pickId) } }
        shapes.set(emptyList())
        selection.set(emptySet())
        status.text = "已清空"
    }

    /** 窗口关闭时释放 GL 资源。**不要在 stop 之外的地方 dispose**。 */
    override fun stop() {
        transfer?.dispose()
    }

    // ==================================================================
    // 合成事件自检（`-Djfgl.demo.selftest=1`；不开的话下面**一行都不跑**）
    // ==================================================================
    //
    // 为什么住在类里而不是另开一个 `*Verifier.kt`：断言要读 `shapes` / `selection` /
    // `status` 这些**私有状态**，只有住在同一个类里才读得到。仓库既有的七个校验器验的是
    // **库**（各自另搭一个场景），这一个验的是**这个 demo 自己的接线**。
    //
    // 它证明不了"画面对"（那是像素校验器的活，而本 demo 没有——画面取决于用户点了哪儿）。
    // 它证明的是**另一件事**：合成事件真的走到了 `wireMouse` 接的那三个处理器上，
    // 坐标换算、拾取、回调、状态更新这一整条链在真 GL 上下文里接通了。
    // 本项目的 GL 线程异常是**静默吞掉**的（吞在 openglfx 的原生回调那层，
    // 我们代码里一处 catch 都没有），所以"没崩"是弱证据；这里的证据形态是
    // **每条断言都打印量到的实际值**——断了哪一环，读出来的数就与期望不一样。

    /** 自检脚本的一段：注入 → 等 [frames] 帧 → （可选）读一次数。 */
    private class Segment(val frames: Int, val drive: () -> Unit, val observe: (() -> Unit)? = null)

    /** 自检脚本的一步 = 若干段 + **一条**断言（一步正好对应输出里的一行）。 */
    private class Step(val title: String, val segments: List<Segment>, val verify: () -> Unit)

    /** 第 11 条每一步读到的三样东西：图型下标、拾取号总数、当前 `Chart` 的身份哈希。 */
    private class KindReading(val kind: Int, val ids: Int, val identity: Int)

    /** 自检的脉冲上限。超了判失败退出——**卡死不退出是另一种静默**（报告上什么都看不到）。 */
    private val selfTestPulseLimit = 900

    private var selfTestNode: Node? = null
    private var selfTestScene: Scene? = null
    private var selfTestTimer: AnimationTimer? = null
    private var selfTestSteps: List<Step> = emptyList()
    private var selfTestStep = 0
    private var selfTestSeg = 0
    private var selfTestInjected = false
    private var selfTestBaseFrame = 0
    private var selfTestPulses = 0
    private var selfTestFailures = 0

    /** 脚本自己带来的中间量（**不是**被测状态）。 */
    private var selfTestAId = 0
    private var selfTestDId = 0
    private var selfTestArmed = false
    private var selfTestChartBaseline = 0
    private var selfTestSizeAfterKind0 = 0
    private var selfTestIdentityKind0 = 0
    private var selfTestDrawnBefore = 0
    private val selfTestKindReadings = ArrayList<KindReading>()

    /** 起自检：[start] 在 `stage.show()` 之后调它。 */
    private fun startSelfTest(node: Node, scene: Scene) {
        selfTestNode = node
        selfTestScene = scene
        selfTestSteps = buildSelfTestSteps()
        println("[自检-合成] 开始：${selfTestSteps.size} 条断言，由合成事件驱动（不需要人工操作窗口）")
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
        if (frameCount - selfTestBaseFrame < seg.frames) return
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
        println()
        println("[自检-合成] 断言 ${selfTestSteps.size} 条，失败 $selfTestFailures 项")
        if (selfTestFailures == 0) {
            println("[自检-合成] 全部通过")
        } else {
            println("[自检-合成] 失败 $selfTestFailures 项，见上面的 ★ 失败 行")
        }
        // 退出放在 JavaFX 线程上（本函数就在 AnimationTimer 里跑）——与 demo 既有的启动自检
        // 同一条理由：GL 回调里直接 exit 是一条没人测过的路径（见 [start] 的 `onInit`）。
        exitProcess(if (selfTestFailures == 0) 0 else 1)
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

    /** 一次单击：按下与抬起在**同一个点**（`moved == 0 < CLICK_SLOP`）。 */
    private fun clickAt(fx: Double, fy: Double) {
        firePress(nx(fx), ny(fy))
        fireReleaseP(nx(fx), ny(fy))
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
     * 自检脚本。**一步 = 输出里的一条断言**，顺序与计划 Task 11 那张表一一对应。
     *
     * <p>坐标全是**比例**（相对画布逻辑尺寸），断言里也按同一套比例算期望值——
     * 于是脚本不会因为系统缩放变了就落到图形外面（125% 下画布是 1113x—，不是 900x700）。
     */
    /**
     * 自检脚本。**一步 = 输出里的一条断言**，顺序与计划 Task 11 那张表一一对应。
     *
     * <p>坐标全是**比例**（相对画布逻辑尺寸），断言里也按同一套比例算期望值——
     * 于是脚本不会因为系统缩放变了就落到图形外面（125% 下画布是 1113x—，不是 900x700）。
     *
     * <p>`Segment(n, drive = {...})` 里的 `n` 是"注入之后等几帧再断言"：单击/框选这类
     * 要走拾取往返的给 3~4 帧（请求在下一帧提交给 PBO、再下一帧读回，最后经
     * `Platform.runLater` 回到 JavaFX 线程），纯同步的动作（拖拽提交）给 2~3 帧也够。
     */
    private fun buildSelfTestSteps(): List<Step> = listOf(

        // ① 拖出一个矩形：状态栏要变成"已画："，shapes 从 0 变 1
        Step(
            "① 拖出矩形",
            listOf(Segment(3, drive = { dragFromTo(0.06, 0.10, 0.26, 0.34) })),
            {
                val s = shapes.get()
                selfTestAId = s.firstOrNull()?.pickId ?: 0
                val ok = s.size == 1 && s[0].shape is Shape.RectShape && status.text.startsWith("已画：")
                check(
                    "① 绘图模式拖出矩形（shapes 0→1、状态栏「已画：」）", ok,
                    "shapes.size=${s.size}，第一个=${s.firstOrNull()?.shape?.describe() ?: "无"}" +
                        "（pickId=$selfTestAId），状态栏=「${status.text}」"
                )
            }
        ),

        // ② 点它内部：命中回调必须被交付，且选中集里是它
        Step(
            "② 单击选中",
            listOf(Segment(3, drive = { selfTestHitBefore = pickHitCount; clickAt(0.16, 0.22) })),
            {
                val sel = selection.get()
                val ok = sel == setOf(selfTestAId) && pickHitCount - selfTestHitBefore == 1 &&
                        status.text.startsWith("命中：")
                check(
                    "② 单击图形内部 → 命中并选中（回调真的被交付了）", ok,
                    "selection=$sel（期望 {${selfTestAId}}），命中回调本次 ${pickHitCount - selfTestHitBefore} 次" +
                        "（累计 $pickHitCount）/ 未命中累计 $pickMissCount 次，状态栏=「${status.text}」"
                )
            }
        ),

        // ③ 再画一个**与它重叠**的，点重叠处：后画的赢（z 序）
        Step(
            "③ 重叠处命中的是后画的",
            listOf(
                Segment(3, drive = { dragFromTo(0.16, 0.22, 0.36, 0.46) }),
                Segment(4, drive = { clickAt(0.20, 0.26) }),
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
                Segment(3, drive = {
                    // ★ 走菜单动作（`MenuItem.fire()`），不是直接给 `style` 字段赋值——
                    //   菜单里那一行的 `this@JfglDemoApp.style = s` 才是生产路径。
                    styleMenuItems[ShapeStyle.STROKE]?.fire()
                    dragFromTo(0.50, 0.10, 0.70, 0.34)
                }),
                // 记基准再点：这个计数器是**全脚本累计**的，写死绝对值会随前面某条也点空过而错
                //（实测第一版就栽在这里——第 ④ 条自己就是一次未命中）。
                Segment(4, drive = { selfTestMissBefore = pickMissCount; clickAt(0.60, 0.22) }),   // 离边框 ≥ 8 像素的内部
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

        // ⑤ 右键框选全部三个
        Step(
            "⑤ 右键框选全部",
            listOf(Segment(4, drive = { marqueeFromTo(0.02, 0.04, 0.80, 0.50) })),
            {
                val ids = shapes.get().map { it.pickId }.toSet()
                val sel = selection.get()
                val ok = ids.size == 3 && sel == ids && status.text.startsWith("框选到")
                check(
                    "⑤ 框选到全部 ${ids.size} 个（状态栏「框选到 N 个图形」）", ok,
                    "selection=$sel（期望 $ids），状态栏=「${status.text}」"
                )
            }
        ),

        // ⑥ 合成 Delete 键删掉选中，再点原位置：不命中
        Step(
            "⑥ Delete 删除 + 原位置不命中",
            listOf(
                Segment(2, drive = { fireDeleteKey() }),
                Segment(4, drive = { selfTestMissBefore = pickMissCount; clickAt(0.16, 0.22) }),   // 第一个矩形原来的位置
            ),
            {
                val ok = shapes.get().isEmpty() && pickMissCount - selfTestMissBefore == 1 &&
                        status.text.startsWith("未命中")
                check(
                    "⑥ 合成 Delete 键删掉选中（走 Scene 的按键处理器），原位置不再命中", ok,
                    "shapes.size=${shapes.get().size}，未命中回调本次 ${pickMissCount - selfTestMissBefore} 次" +
                        "（累计 $pickMissCount），状态栏=「${status.text}」"
                )
            }
        ),

        // ⑦ ★ LIFO 复用的判别式 —— 见 consumeMarquee 里那个钩子的长注释
        Step(
            "⑦ ★ 框选读回落地前按 Delete → LIFO 回收的号不该被高亮",
            listOf(
                // 先回到填充+描边（上一步为了第 ④ 条切成了只描边，而只描边的**内部点不中**，
                // 这一步却要先靠一次单击把 D 选上——所以样式必须能填）。
                // observe 里记下 D 的号：最后那条断言要判的正是"那个被回收的号"。
                Segment(3, drive = {
                    styleMenuItems[ShapeStyle.FILL_AND_STROKE]?.fire()
                    dragFromTo(0.50, 0.50, 0.70, 0.72)
                }, observe = { selfTestDId = shapes.get().lastOrNull()?.pickId ?: 0 }),
                // 单击 D 的内部 → 选中集 = {D}。**这一步的落定要等**（拾取往返 2~3 帧），
                // 所以它单独占一段，并在 observe 里记下"选中集真的落在 D 上了"——
                // 不然最后那条断言会在"压根没选中"的情况下**恒真**。
                Segment(4, drive = { clickAt(0.60, 0.61) },
                    observe = { selfTestArmed = selection.get() == setOf(selfTestDId) }),
                // 框选 D + **在读回之后、活快照之前**删掉它（钩子）
                Segment(4, drive = { marqueeFromTo(0.46, 0.46, 0.76, 0.76); selfTestDeleteAfterReadback = true }),
                // 再画一个新图形：它会拿到刚刚被回收的那个号
                Segment(3, drive = { dragFromTo(0.20, 0.60, 0.36, 0.80) }),
            ),
            {
                val e = shapes.get().lastOrNull()
                val recycled = e != null && e.pickId != 0 && e.pickId == selfTestDId
                val highlighted = e?.pickId in selection.get()
                val ok = selfTestArmed && selfTestDeleteHookRuns >= 1 && recycled && !highlighted
                check(
                    "⑦ ★ 框选读回落地前按 Delete：LIFO 回收的号不该被高亮", ok,
                    "钩子执行 $selfTestDeleteHookRuns 次；点中 D=${selfTestArmed}；" +
                        "被删的号=$selfTestDId；新图形=${e?.shape?.describe()}（号=${e?.pickId}，" +
                        "复用了那个号=$recycled）；selection=${selection.get()}"
                )
            }
        ),

        // ⑧ 切文本模式，单击落一段字
        Step(
            "⑧ 文本模式落字",
            listOf(
                Segment(3, drive = { modeMenuItems[Mode.TEXT]?.fire() }),
                Segment(4, drive = { clickAt(0.12, 0.60) }),
            ),
            {
                val s = shapes.get()
                val last = s.lastOrNull()?.shape
                val ok = last is Shape.TextShape && status.text.startsWith("落字：")
                check(
                    "⑧ 文本模式：合成 press+release 落下一个 TextShape", ok,
                    "shapes.size=${s.size}，最后一个是 ${last?.describe() ?: "无"}" +
                        "（pickId=${s.lastOrNull()?.pickId ?: -1}），状态栏=「${status.text}」"
                )
            }
        ),

        // ⑨ 切回绘图模式再拖一次：落下的必须是图形而不是文字（模式没串）
        Step(
            "⑨ 切回绘图模式",
            listOf(
                Segment(3, drive = { modeMenuItems[Mode.DRAW]?.fire() }),
                Segment(4, drive = { dragFromTo(0.60, 0.80, 0.76, 0.92) }),
            ),
            {
                val s = shapes.get()
                val last = s.lastOrNull()?.shape
                val ok = last is Shape.RectShape && status.text.startsWith("已画：")
                check(
                    "⑨ 切回绘图模式：落下的是**图形**不是文字（模式没有串）", ok,
                    "shapes.size=${s.size}，最后一个是 ${last?.describe() ?: "无"}，状态栏=「${status.text}」"
                )
            }
        ),

        // ⑩ 切到图表模式（**合成菜单动作**，不是改 mode 常量）
        Step(
            "⑩ 切到图表模式",
            listOf(
                Segment(5, drive = {
                    selfTestChartBaseline = registrySize()
                    selfTestDrawnBefore = DemoChart.drawnFrames
                    modeMenuItems[Mode.CHART]?.fire()
                })
            ),
            {
                val grew = DemoChart.drawnFrames - selfTestDrawnBefore
                val ids = registrySize() - selfTestChartBaseline
                // 末尾探针按帧递增 = 整条绘制路径跑到了末尾（本项目 GL 线程的异常是静默吞掉的，
                // "少画了东西"与"抛了异常"在画面上长得一样，只有这个计数能把两者分开）。
                // 拾取号 +2 = 两条系列在 `ChartRenderer` 里注册了号 —— 那只能发生在
                // `gc.charts`（懒创建）已经建出来并走进了 `drawChart` 之后。
                val ok = mode == Mode.CHART && grew >= 3 && ids == 2
                check(
                    "⑩ 图表模式：draw 的末尾探针按帧到达 + gc.charts 被创建（两条系列注册了号）", ok,
                    "mode=${mode.label}，末尾探针 +$grew 帧（期望 ≥3），拾取号 +$ids（期望恰好 2）"
                )
                selfTestIdentityKind0 = DemoChart.lastChartIdentity
                selfTestSizeAfterKind0 = registrySize()
                selfTestKindReadings.clear()
            }
        ),

        // ⑪ ★ 四种图型遍历：每一步都要**真的重建** Chart（身份跳变），
        //    而**切回已经建过的图型时必须回到原来那个实例**（不然每绕一圈漏两块 GPU 缓冲）。
        //    这一步判的正是"欠重建"那个盲区：`chart()` 变成永远返回缓存时，
        //    身份不再跳变、号数停在原地，而画面看起来毫无问题（只是菜单变死）。
        Step(
            "⑪ ★ 四种图型遍历",
            listOf(
                Segment(3, drive = { fireChartKind(1) }, observe = { selfTestKindReadings.add(reading(1)) }),
                Segment(3, drive = { fireChartKind(2) }, observe = { selfTestKindReadings.add(reading(2)) }),
                Segment(3, drive = { fireChartKind(3) }, observe = { selfTestKindReadings.add(reading(3)) }),
                Segment(3, drive = { fireChartKind(0) }, observe = { selfTestKindReadings.add(reading(0)) }),
                Segment(3, drive = { fireChartKind(1) }, observe = { selfTestKindReadings.add(reading(1)) }),
            ),
            {
                val r = selfTestKindReadings
                val chartIds = registrySize() - selfTestChartBaseline
                val ok = r.size == 5 &&
                        r[0].ids == selfTestSizeAfterKind0 + 2 && r[0].identity != selfTestIdentityKind0 &&
                        r[1].ids == r[0].ids + 2 && r[1].identity != r[0].identity &&
                        r[2].ids == r[1].ids + 2 && r[2].identity != r[1].identity &&
                        r[3].ids == r[2].ids && r[3].identity == selfTestIdentityKind0 &&
                        r[4].ids == r[3].ids && r[4].identity == r[0].identity &&
                        chartIds <= 8
                val series = if (r.size == 5) r.joinToString(" → ") {
                    "${DemoChart.KINDS[it.kind].first}:号${it.ids}/身份${it.identity}"
                } else "只记到 ${r.size} 步"
                check(
                    "⑪ ★ 遍历四种图型：每换一种就重建（身份跳变、号 +2），切回旧的复用（号不变、身份回归）", ok,
                    "$series ；起点（折线）身份=$selfTestIdentityKind0、号=$selfTestSizeAfterKind0；" +
                        "图表系列一共占 $chartIds 个拾取号（上限 8 = 4 图型 × 2 系列）"
                )
            }
        ),
    )

    /** 第 ⑪ 条每步读一次：图型下标、当前拾取号总数、当前 `Chart` 的身份哈希。 */
    private fun reading(kind: Int) = KindReading(kind, registrySize(), DemoChart.lastChartIdentity)
}

/**
 * 应用入口。
 *
 * <p>**没有它，Step 4 那条运行命令跑不起来**——命令打的是 `JfglDemoKt`，
 * 而那个类里得有一个顶层 `main()` 才谈得上入口。缺了它的报错是
 * `在类 com.bingbaihanji.jfgl.example.demo.JfglDemoKt 中找不到 main 方法`，
 * **不是编译错**（编译能过），所以特别容易漏。
 *
 * <p>`@JvmName("main")` 在这里**不需要**：`ClickExample.kt` 之所以要它，是因为
 * `example` 包里已经有一个顶层 `main()`（`PipelineExample.kt`）会造成重载歧义；
 * 而本文件在 `example.demo` 这个**新包**里，没有同包冲突。
 */
fun main() {
    Application.launch(JfglDemoApp::class.java)
}
