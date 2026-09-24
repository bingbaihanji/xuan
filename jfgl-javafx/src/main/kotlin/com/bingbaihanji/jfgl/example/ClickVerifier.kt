package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.system.exitProcess

/**
 * 「鼠标点击 → GPU 拾取 → 回调拿到对象 → 更新界面」这条闭环的端到端校验器。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>这条链的**每一个环节都错得起**，而且都不报错：
 *
 * 1. **坐标换算**：鼠标事件给的是**逻辑像素**，`pickAsync` 要的是**设备像素**
 *    （见 [devicePointFor]）。少乘一次窗口缩放系数，点击会落在完全不同的地方——
 *    在 100% 缩放的机器上还一切正常。本校验器用一对 ★ 探针把这一步钉死：
 *    用**同一个局部坐标**，换算后应当点中 P，不换算则点中 Q，而 P 与 Q 是两个不同的对象。
 * 2. **事件根本没送到**：合成事件绕过 JavaFX 的拾取（`fireEvent` 不做命中测试），
 *    所以本文件另外断言 `canvas.isPickOnBounds`——`Region` 默认是 true，
 *    一旦哪天被改成 false，真实鼠标事件将**永远**到不了画布，而本校验器其余部分全绿。
 * 3. **回调丢结果**：`pickAsync` 是「最新覆盖旧的」（对 hover 是对的），
 *    对**离散的点击**却不是——见文件末尾的「★ 点击丢失复现」一节。
 *
 * <p>因此本文件用**合成的 JavaFX `MouseEvent` 走真实事件路径**
 * （`Node.fireEvent`，且从 JavaFX 线程发出），而不是在 GL 线程直接调 `gc.pick`——
 * 后者测的是拾取本身，不是闭环。
 *
 * <h2>它断言什么</h2>
 *
 * <p>见 [reportProbes] 里的清单。判据一律是**对象身份**（`payload`）与**像素坐标**，
 * 不是「看起来对」：命中就要求拿到的正是那个对象，未命中就要求 ID 是 0。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.ClickVerifierKt"
 * ```
 *
 * <p>`-Dstdout.encoding=UTF-8` 必须带（见 `CLAUDE.md`）：不带的话中文断言与**失败清单**
 * 全是 GBK 乱码。
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败。它自己关窗退出，不需要手动关闭。
 */

/** 窗口的逻辑尺寸。设备像素尺寸由系统缩放决定（见 [ClickVerifierApp.reportCalibration]）。 */
private const val SCENE_W = 800.0
private const val SCENE_H = 620.0

/** 大号文本的字号与颜色。校验器会断言「点大号那段拿到的那一条」确实记着这些值。 */
private const val TEXT_BIG_SIZE = 40f
private const val TEXT_BIG_COLOR = 0xFFFFD54F.toInt()

/** 小号文本的字号与颜色。必须与大号**不同**，否则两条断言会互相印证不出东西来。 */
private const val TEXT_SMALL_SIZE = 16f
private const val TEXT_SMALL_COLOR = 0xFF80DEEA.toInt()

/** 大号 / 小号文本的内容。 */
private const val TEXT_BIG_CONTENT = "大号文字"
private const val TEXT_SMALL_CONTENT = "小号文字"

/** 背景色。与 `FXGLTransfer` 的 `glClearColor`(0.2,0.2,0.2) 不同，便于肉眼区分。 */
private const val BG = 0xFF101418.toInt()

/** ★ FIFO 突发阶段：一帧里连点几下（每一下后面紧跟一次移动）。 */
private const val FIFO_ROUNDS = 8

/**
 * ★ FIFO 突发阶段用到的探针标签，每个标签对应**一个不同的对象**。
 *
 * <p>刻意让 8 下点在不同的对象上：这样"交付顺序"才有内容可判——
 * 若 8 下都打同一个对象，FIFO 乱序了也看不出来（交付内容全都一样）。
 */
private val FIFO_PLAN_TAGS =
    listOf("A", "ROUND_BODY", "D_EDGE", "C_IN", "U_UNDER", "U_OVER", "ORIGIN", "FAR")

/** FIFO 那些结果的兜底截止帧（正常 10 帧内齐）。 */
private const val FIFO_DEADLINE = 40

/** ★ 队列满阶段：先发 4 条（最旧的会被丢掉），再发 16 条。 */
private const val OVERFLOW_ORIGIN = 4
private const val OVERFLOW_FAR = 16
private const val OVERFLOW_TOTAL = OVERFLOW_ORIGIN + OVERFLOW_FAR

/**
 * 队列容量，**必须与 `FXGLTransfer.CLICK_QUEUE_CAPACITY` 一致**。
 *
 * <p>刻意写死而不是从库里读：这是一条**联动断言**——谁改了容量，这里就会失败，
 * 逼他同时确认"满时丢最旧"这条语义还成立、并把这里的期望值改对。
 * 从库里读的话，容量改成 1 也能全绿，那是橡皮图章。
 */
private const val CAPACITY = 16

/** 溢出阶段的兜底截止帧（16 条、一帧一条）。 */
private const val OVERFLOW_DEADLINE = 60

/**
 * 校验器启动入口。
 *
 * <p>函数名不叫 `main`：同包已有一个顶层 `main()`（[PipelineExample]），
 * 两个同名顶层函数会让 `import ...example.main` 报重载歧义，而同包内无法用别名区分。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，文档里的命令行因此照常可用。
 */
@JvmName("main")
fun clickVerifyMain() {
    Application.launch(ClickVerifierApp::class.java)
}

/**
 * 注册进 `pickRegistry` 的载荷。
 *
 * <p>故意把**样式**（是否填充、填充色、边框色、字号）也放进来：清单上要求的是
 * 「常见图形（含边框色/是否填充/填充色）与文本（大小/颜色）都要有点击事件」，
 * 那么"点中了"就不该只是"点中了某个东西"——拿回来的那条必须真的记着那个字号和颜色。
 * 只比对对象身份的话，两段文字互换身份这种错误就抓不住。
 *
 * @param name      对象名，探针的期望值就是它
 * @param kind      类别（矩形 / 圆 / 文本 …）
 * @param filled    是否填充
 * @param fillHex   填充色 ARGB；[filled] 为 false 时无意义
 * @param strokeHex 边框色 ARGB；0 表示无边框
 * @param fontSize  字号；非文本为 0
 */
private class Target(
    val name: String,
    val kind: String,
    val filled: Boolean,
    val fillHex: Int,
    val strokeHex: Int,
    val fontSize: Float = 0f
) {
    /** 拾取 ID，在 `onInit` 里注册时得到。0 表示尚未注册。 */
    var id: Int = 0
}

/**
 * 场景里一个对象的几何（设备像素）。
 *
 * <p>文本项的 `x`/`y` 是**基线起点**（`drawText` 的约定）；其余是左上角。
 */
private class Slot(val name: String, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * 一个探针：在**局部坐标** [localX]/[localY] 上合成一次鼠标事件，期望拿到 [expect]。
 *
 * @param tag            标签（结果的归位依据；同一标签必须恰好交付一次）
 * @param label          报告里显示的中文说明
 * @param localX         合成事件用的**节点局部坐标**（逻辑像素）
 * @param localY         同上
 * @param convert        true = 按生产写法换算成设备像素后再查；false = **故意不换算**（★ 反证）
 * @param expect         硬编码的期望命中对象名；null = 期望未命中
 * @param deviceX        实际会传给 `pickAsync` 的 x（设备像素）
 * @param deviceY        实际会传给 `pickAsync` 的 y（设备像素）
 * @param checkSync      是否把「提交当帧的同步 `Gc.pick` 答案」也当作判据
 * @param phase          "probe" / "vanishAfter"
 * @param altDeviceX     ★ 反证用：换算**后**的那个点（用来打印"本该命中谁"）
 * @param altDeviceY     同上
 * @param move           true = 合成 `MOUSE_MOVED`，false = 合成 `MOUSE_CLICKED`
 */
private class Probe(
    val tag: String,
    val label: String,
    val localX: Double,
    val localY: Double,
    val convert: Boolean,
    val expect: String?,
    val deviceX: Double,
    val deviceY: Double,
    val checkSync: Boolean,
    val phase: String = "probe",
    val altDeviceX: Double = -1.0,
    val altDeviceY: Double = -1.0,
    val move: Boolean = false,
    /** 走哪条交付路径；见 [ClickVerifierApp.onMouseEvent]。 */
    val kind: ProbeKind = ProbeKind.CLICK
)

/** 探针的交付路径。 */
private enum class ProbeKind {
    /** 点击语义：`clickAsyncAtNode`，有界 FIFO、按序交付。 */
    CLICK,

    /** hover 语义：`pickAsyncAtNode`，最新覆盖旧的。 */
    HOVER
}

/**
 * 一次合成事件的观测结果。
 *
 * <p>**由 JavaFX 线程写、GL 线程读**（回调在 JavaFX 线程上），所以收集容器必须是
 * [CopyOnWriteArrayList]：普通 `ArrayList` 在这种跨线程读写下的可见性没有保证，
 * 表现是"结果明明交付了，校验器却报未交付"，而且随机器负载时有时无。
 *
 * @param tag         探针标签
 * @param id          命中的拾取 ID；0 = 未命中
 * @param payload     解析出的对象名；未命中或未注册为 null
 * @param hitX        回调拿到的查询点 x（设备像素）
 * @param hitY        同上
 * @param onFxThread  回调是否在 JavaFX 应用线程上执行
 * @param sawLocalX   处理器**实际看到**的事件局部坐标 x（校验合成事件是否忠实）
 * @param sawLocalY   同上
 * @param labelText   回调里更新界面之后，标签的文本
 */
private class Outcome(
    val tag: String,
    val id: Int,
    val payload: String?,
    val hitX: Float,
    val hitY: Float,
    val onFxThread: Boolean,
    val sawLocalX: Double,
    val sawLocalY: Double,
    val labelText: String
)

/** 窗口与帧缓冲的实测指标。全部在 JavaFX 线程上读，之后只读。 */
private class Metrics(
    val nodeW: Double,
    val nodeH: Double,
    val fbW: Int,
    val fbH: Int,
    val scaleX: Double,
    val scaleY: Double,
    val pickOnBounds: Boolean,
    val boundsX: Double,
    val boundsY: Double
)

/**
 * 点击闭环校验器。
 *
 * <h2>场景为什么要动</h2>
 *
 * <p>`VANISH` 那个矩形只在早期帧存在：拾取读的是**帧缓冲**，静态场景下"这一帧有它"
 * 与"上一帧有它"分不开，陈旧 ID 泄漏那一类缺陷（点击到一个已经消失的对象）
 * 因此完全不可见。它被移走之后必须不再命中——这一条只有在场景会变时才成立。
 */
class ClickVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 画布节点。合成事件就发给它。 */
    private lateinit var canvas: Node

    /** 回调里更新的标签。用它证明「回调里能安全地碰界面」。 */
    private val status = Label("等待探针").apply {
        isWrapText = false
        padding = Insets(6.0, 10.0, 6.0, 10.0)
        style = "-fx-font-size: 13px;"
    }

    /** 全部可拾取对象。 */
    private val targets: Map<String, Target> = listOf(
        Target("A_FILL_RECT", "矩形", true, 0xFFE53935.toInt(), 0),
        Target("D_STROKE_RECT", "矩形", false, 0, 0xFF42A5F5.toInt()),
        Target("ROUND_RECT", "圆角矩形", true, 0xFFFFA726.toInt(), 0xFF6D4C41.toInt()),
        Target("CIRCLE", "圆", true, 0xFF66BB6A.toInt(), 0),
        Target("UNDER", "矩形", true, 0xFF283593.toInt(), 0),
        Target("OVER", "矩形", true, 0xFFFFEE58.toInt(), 0),
        Target("VANISH", "矩形", true, 0xFF8E24AA.toInt(), 0),
        Target("P_TARGET", "矩形", true, 0xFFD81B60.toInt(), 0),
        Target("Q_TARGET", "矩形", true, 0xFF00ACC1.toInt(), 0),
        Target("ORIGIN_DOT", "矩形", true, 0xFFFFFFFF.toInt(), 0),
        Target("FAR_DOT", "矩形", true, 0xFFFFFFFF.toInt(), 0),
        Target("TEXT_BIG", "文本", true, TEXT_BIG_COLOR, 0, TEXT_BIG_SIZE),
        Target("TEXT_SMALL", "文本", true, TEXT_SMALL_COLOR, 0, TEXT_SMALL_SIZE)
    ).associateBy { it.name }

    // ------------------------------------------------------------------
    // 帧计数与阶段
    // ------------------------------------------------------------------

    /**
     * **刚刚渲染完的那一帧**的序号（从 0 开始）。
     *
     * <p>语义是「已完成」：它在 [verifyOnce] 里自增，而 [verifyOnce] 挂在 `onRender` 上、
     * 跑在 `onFrame` 之后，因此同一帧里 `onFrame` 与 `onRender` 看到的号是一致的。
     */
    private var rendered = 0

    /** 指标是否已经请求读取（只请求一次）。 */
    private var metricsRequested = false

    /** 实测指标；读取完成前为 null（`Platform.runLater` 的可见性顺带由它保证）。 */
    @Volatile
    private var metrics: Metrics? = null

    /** 探针计划；指标就绪后建一次，之后只读。 */
    @Volatile
    private var plan: List<Probe>? = null

    /** 下一个要发的探针下标。 */
    private var nextProbe = 0

    /** 上一个发出的探针是否还没收到结果。 */
    private var probeInFlight = false

    /** 上一个发出的探针标签。 */
    private var lastTag = ""

    /** 上一个探针发出的帧号，用于超时判失败。 */
    private var lastDispatchFrame = -1

    /** 超时未交付的探针标签。**它们必须计入失败**，否则"结果永远不来"会变成"没跑"。 */
    private val notDelivered = ArrayList<String>()

    /**
     * `VANISH` 是否已经消失。
     *
     * <p>判据是「VANISH 那条探针的结果**已经收到**」而不是某个帧号：帧号判据依赖
     * 「提交帧 + N」这条推断，而结果是异步的、落点会飘；用"已经收到结果"当判据，
     * 那一刻它必然还在画，于是之后移走它、再等几帧，两条探针的时间关系就是确定性的。
     */
    private var vanish = false

    /** `VANISH` 消失的帧号。 */
    private var vanishFrame = -1

    /** ★ FIFO 突发阶段的状态。 */
    private var fifoDispatched = false
    private var fifoDone = false
    private var fifoDispatchFrame = -1

    /** ★ 队列满（溢出）阶段的状态。 */
    private var overflowDispatched = false
    private var overflowDone = false
    private var overflowDispatchFrame = -1

    /** FIFO 阶段结束时 `droppedClicks()` 的取值（见 [fifoPhase] 里为什么要当场取）。 */
    private var fifoDropped = -1

    /** 队列深度采样（用来数"提交被推迟"的帧数，见 [sampleQueueDepth]）。 */
    private var lastQueueDepth = 0
    private var queueBusyFrames = 0
    private var queueDrainFrames = 0

    /** 当前正在合成的探针（只在 JavaFX 线程上读写，因为合成与处理都在那一帧之内同步完成）。 */
    private var firing: Probe? = null

    /**
     * ★ 真实鼠标点击（`Robot`，走操作系统事件）阶段用的探针。
     *
     * <p>合成事件**绕过 JavaFX 的拾取**（`fireEvent` 不做命中测试），所以它证明不了
     * "真实鼠标事件能到达画布"。这一条用 [javafx.scene.robot.Robot] 把光标移到画布上
     * 并点一下，走的是完整的 OS → JavaFX → 节点 → 回调链路，而且是**屏幕坐标**
     * （凭 `localToScreen` 换算）——因此它同时验证了"局部坐标 ↔ 设备像素"这条换算
     * 在真实输入下也成立。
     *
     * <p>跨线程：由 GL 线程在分发时置上、收到结果后清掉，所以是 `@Volatile`。
     */
    @Volatile
    private var robotProbe: Probe? = null

    /** 场景（`localToScreen` 要用它算屏幕坐标）。 */
    private var stage: Stage? = null

    /** 探针结果。由 JavaFX 线程写、GL 线程读（见 [Outcome] 的说明）。 */
    private val outcomes = CopyOnWriteArrayList<Outcome>()

    /** 同步对照：每个标签在**提交当帧** `Gc.pick` 的答案（未命中为 null）。GL 线程读写。 */
    private val syncAnswers = HashMap<String, String?>()

    /** ★ 反证用：换算后的那个点上同步 `Gc.pick` 的答案（证明两个点是不同对象）。 */
    private val altSyncAnswers = HashMap<String, String?>()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()

        // 注册 payload。**必须在 onInit 里**：start() 的此刻 GL 还没初始化，
        // gc() 返回 null，注册会静默不执行——然后所有探针都会拿到 null payload。
        bridge.onInit {
            bridge.gc()?.let { gc ->
                for (t in targets.values) {
                    t.id = gc.pickRegistry.register(t)
                }
            }
        }
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val view = bridge.createGlFXView()
        canvas = view
        // 两个事件都接：探针既发 MOUSE_CLICKED（闭环本体），也发 MOUSE_MOVED
        // （★ 丢失复现需要它）。
        view.addEventHandler(MouseEvent.MOUSE_CLICKED) { e -> onMouseEvent(e) }
        view.addEventHandler(MouseEvent.MOUSE_MOVED) { e -> onMouseEvent(e) }

        val mainView = MainView().apply {
            center = view
            bottom = status
        }
        stage.title = "JFGL 点击闭环校验器"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.show()
        this.stage = stage
    }

    override fun stop() {
        transfer?.dispose()
    }

    // ------------------------------------------------------------------
    // 合成事件与回调（全部在 JavaFX 应用线程上）
    // ------------------------------------------------------------------

    /**
     * 被校验的**生产代码路径**：鼠标事件 → 坐标换算 → 异步拾取 → 回调更新界面。
     *
     * <p>坐标换算与"点击走队列"这两件事都由库负责（`clickAsyncAtNode`），
     * 这里只是把它接起来——这正是 [ClickExample] 与 `jfgl { onClick { } }` 的写法。
     */
    private fun onMouseEvent(event: MouseEvent) {
        val probe = robotProbe ?: firing ?: return
        // 事件类型必须与探针期望的一致：机器人阶段会先移动光标（那是 MOUSE_MOVED），
        // 只有随后的 MOUSE_CLICKED 才是被测的那一下；不挡的话两条会挤在同一个标签上，
        // 反而把"回调被覆盖"当成"回调交付了两次"。
        val wanted = if (probe.move) MouseEvent.MOUSE_MOVED else MouseEvent.MOUSE_CLICKED
        if (event.eventType != wanted) {
            return
        }
        val bridge = transfer ?: return
        val localX = event.x
        val localY = event.y

        // 生产写法：把**节点局部坐标**交给库，换算在 clickAsyncAtNode 里做。
        //
        // 探针可以要求改走另外两条路，两条都不是随便加的：
        //   - kind = HOVER：用 hover 语义（pickAsyncAtNode，"最新覆盖旧的"），
        //     ★ FIFO 那一节必须有它才是真场景（点击后紧跟一次移动）；
        //   - convert = false：**故意不换算**（★ 反证探针），直接用局部坐标当设备坐标，
        //     于是同一个局部坐标会落到另一个对象上——"点 A 命中 B"的现场。
        val deliver: (PickHit?) -> Unit = { hit: PickHit? ->
            val payload = hit?.payload() as? Target
            status.text = if (payload == null) "未命中" else "命中：${payload.name}"
            outcomes.add(
                Outcome(
                    probe.tag,
                    hit?.id() ?: 0,
                    payload?.name,
                    hit?.x() ?: 0f,
                    hit?.y() ?: 0f,
                    Platform.isFxApplicationThread(),
                    localX,
                    localY,
                    status.text
                )
            )
        }
        when {
            !probe.convert -> bridge.clickAsync(localX.toFloat(), localY.toFloat(), deliver)
            probe.kind == ProbeKind.HOVER -> bridge.pickAsyncAtNode(canvas, localX, localY, deliver)
            else -> bridge.clickAsyncAtNode(canvas, localX, localY, deliver)
        }
    }

    /**
     * 在给定**局部坐标**上合成一次鼠标事件，从 JavaFX 线程发给画布。
     *
     * <p>坐标取 `localToScene`：`MouseEvent` 的 `x/y` 在派发链上会按源节点逐级换算，
     * 直接塞局部坐标会被当成**场景坐标**再换算一次（本容器有 5 像素内边距，正好差 5）。
     * 本文件另有一条断言核对"处理器看到的坐标就是想要的坐标"，所以这里选错会被抓住，
     * 不会静默地把所有探针都测成别的位置。
     */
    private fun fireSynthetic(probe: Probe) {
        val scene = canvas.localToScene(probe.localX, probe.localY)
        val screen = canvas.localToScreen(probe.localX, probe.localY) ?: Point2D(0.0, 0.0)
        val type = if (probe.move) MouseEvent.MOUSE_MOVED else MouseEvent.MOUSE_CLICKED
        val button = if (probe.move) MouseButton.NONE else MouseButton.PRIMARY
        val clicks = if (probe.move) 0 else 1
        val event = MouseEvent(
            type, scene.x, scene.y, screen.x, screen.y,
            button, clicks,
            false, false, false, false,
            false, false, false,
            false, false, true,
            null
        )
        firing = probe
        try {
            canvas.fireEvent(event)
        } finally {
            firing = null
        }
    }

    // ------------------------------------------------------------------
    // 绘制（GL 线程）
    // ------------------------------------------------------------------

    /**
     * 场景几何：全部按帧缓冲尺寸的比例算（设备像素）。
     *
     * <p>用比例而不是常量：帧缓冲尺寸随系统缩放变化，写死设备坐标的话换一台机器
     * 布局就整个错位——而探针的期望值是跟着这份布局算的，于是两边一起错、断言全绿。
     *
     * <p>中间那条横带（[0.18h, 0.50h]）刻意留空：只有 ★ 的 P / Q 两个块在里面。
     * 它们必须独占，否则"换算后点中 P、不换算点中 Q"这条判据会被别的对象挡住。
     *
     * @param w     帧缓冲宽度（设备像素）
     * @param h     帧缓冲高度（设备像素）
     * @param scale 窗口缩放系数；0 表示还没测到，此时不画 Q（它的位置依赖它）
     */
    private fun layout(w: Float, h: Float, scale: Double): List<Slot> {
        val out = ArrayList<Slot>(16)
        // 第 1 排：四个图形并排
        out.add(Slot("D_STROKE_RECT", 0.04f * w, 0.04f * h, 0.13f * w, 0.12f * h))
        out.add(Slot("A_FILL_RECT", 0.20f * w, 0.04f * h, 0.13f * w, 0.12f * h))
        out.add(Slot("ROUND_RECT", 0.36f * w, 0.04f * h, 0.13f * w, 0.12f * h))
        // 圆按中心与半径给（半径取宽度的比例），外接框由此推出来
        val cr = 0.05f * w
        out.add(Slot("CIRCLE", 0.56f * w - cr, 0.10f * h - cr, cr * 2f, cr * 2f))
        // 两个角落标记：一个钉原点（有没有偏移）、一个钉远端（缩放对不对），互为补充
        out.add(Slot("ORIGIN_DOT", 0f, 0f, 14f, 14f))
        out.add(Slot("FAR_DOT", w - 16f, h - 16f, 14f, 14f))
        // 第 2 排：重叠的一对（后画的赢）+ 会消失的那个
        out.add(Slot("UNDER", 0.05f * w, 0.62f * h, 0.16f * w, 0.16f * h))
        out.add(Slot("VANISH", 0.26f * w, 0.62f * h, 0.14f * w, 0.16f * h))
        out.add(Slot("OVER", 0.10f * w, 0.66f * h, 0.06f * w, 0.08f * h))
        // 保留带里的 ★ 一对
        if (scale > 0) {
            out.add(Slot("P_TARGET", 0.62f * w - 0.045f * w, 0.42f * h - 0.045f * w, 0.09f * w, 0.09f * w))
            val qx = (0.62f * w / scale).toFloat()
            val qy = (0.42f * h / scale).toFloat()
            out.add(Slot("Q_TARGET", qx - 0.03f * w, qy - 0.03f * w, 0.06f * w, 0.06f * w))
        }
        // 两段文本。文本项的 x/y 是**基线起点**（drawText 的约定）。
        out.add(Slot("TEXT_BIG", 0.04f * w, 0.86f * h, 0f, 0f))
        out.add(Slot("TEXT_SMALL", 0.04f * w, 0.95f * h, 0f, 0f))
        return out
    }

    /** 画一帧。在 GL 线程上执行，不碰任何 JavaFX 控件。 */
    private fun drawScene(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()
        gc.pickId = 0
        gc.fill = BG
        gc.fillRect(0f, 0f, w, h)

        for (slot in layout(w, h, metrics?.scaleY ?: 0.0)) {
            if (slot.name == "VANISH" && vanish) {
                continue
            }
            val target = targets.getValue(slot.name)
            gc.pickable(target.id) { drawSlot(gc, target, slot) }
        }
    }

    /** 按对象名画它。坐标一律是设备像素。 */
    private fun drawSlot(gc: Gc, t: Target, s: Slot) {
        when (t.name) {
            "D_STROKE_RECT" -> {
                gc.stroke = t.strokeHex
                gc.lineWidth = 6f
                gc.strokeRect(s.x, s.y, s.w, s.h)
            }
            "A_FILL_RECT", "UNDER", "OVER", "VANISH", "P_TARGET", "Q_TARGET", "ORIGIN_DOT", "FAR_DOT" -> {
                gc.fill = t.fillHex
                gc.fillRect(s.x, s.y, s.w, s.h)
            }
            "ROUND_RECT" -> {
                val r = minOf(s.w, s.h) * 0.2f
                gc.fill = t.fillHex
                gc.fillRect(s.x, s.y, s.w, s.h, r)
                gc.stroke = t.strokeHex
                gc.lineWidth = 4f
                gc.strokeRect(s.x, s.y, s.w, s.h, r)
            }
            "CIRCLE" -> {
                gc.fill = t.fillHex
                gc.fillCircle(s.x + s.w / 2f, s.y + s.h / 2f, minOf(s.w, s.h) / 2f)
            }
            "TEXT_BIG" -> {
                gc.fontSize = t.fontSize
                gc.fill = t.fillHex
                gc.drawText(TEXT_BIG_CONTENT, s.x, s.y)
            }
            "TEXT_SMALL" -> {
                gc.fontSize = t.fontSize
                gc.fill = t.fillHex
                gc.drawText(TEXT_SMALL_CONTENT, s.x, s.y)
            }
            else -> error("场景里有一个没写画法的对象：${t.name}")
        }
    }

    // ------------------------------------------------------------------
    // 调度（GL 线程）
    // ------------------------------------------------------------------

    /** `onRender` 的入口：把校验体包进 try/catch。 */
    private fun verifyOnce() {
        try {
            tick()
        } catch (t: Throwable) {
            // 这里在 GL 线程上跑：异常抛出去会让线程死掉，汇总行与 exitProcess 都走不到，
            // JVM 会因为"最后一个非守护线程结束"而以 **0** 退出——一个已经打印了 FAIL 的
            // 校验器报出退出码 0，正是本仓库最忌讳的那种"静默的绿"。
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun tick() {
        val bridge = transfer ?: return
        rendered++
        val gc = bridge.gc() ?: return

        // 阶段 0：先量指标。节点要等布局完成，所以在 JavaFX 线程上读。
        val m = metrics
        if (m == null) {
            if (!metricsRequested) {
                metricsRequested = true
                Platform.runLater { captureMetrics(bridge) }
            }
            return
        }

        // 阶段 1：建探针计划（只建一次）
        if (plan == null) {
            reportCalibration(bridge, m)
            plan = buildPlan(m.fbW.toFloat(), m.fbH.toFloat(), m.scaleY)
        }

        // 阶段 2：逐个发探针。**同一时刻只允许一条在飞**——pickAsync 是最新覆盖旧的，
        // 并行发会让前一条静默消失（见文件末尾）。
        if (nextProbe < plan!!.size) {
            dispatchNextProbe(gc)
            return
        }

        // 阶段 3：★ 真实鼠标点击（Robot）
        if (!robotDone) {
            robotPhase()
            return
        }

        // 阶段 4：★ FIFO（点击不再被后一次请求覆盖）
        if (!fifoDone) {
            sampleQueueDepth()
            fifoPhase()
            return
        }

        // 阶段 5：★ 队列满（丢最旧）
        if (!overflowDone) {
            sampleQueueDepth()
            overflowPhase()
            return
        }

        // 阶段 6：结算
        settle()
    }

    /**
     * 发下一个探针：先收上一个，再发新的。
     *
     * <p>顺序不能反：上一个的结果没到时发新的，旧的那条会被覆盖掉，而**它不会报错**——
     * 只是那一条断言永远不会被评估。
     */
    private fun dispatchNextProbe(gc: Gc) {
        if (probeInFlight) {
            if (outcomeArrived(lastTag)) {
                probeInFlight = false
                // VANISH 那条的结果一到，就把它从场景里移走。
                // 判据用"结果已到"而不是帧号：那一刻它必然还在画（拾取读的就是它所在的帧），
                // 于是"消失前命中、消失后不命中"这两条的时间关系是确定性的，
                // 不依赖"提交帧 + N"那条会飘的推断。
                if (lastTag == "V_BEFORE") {
                    vanish = true
                    vanishFrame = rendered
                }
            } else if (rendered - lastDispatchFrame > 12) {
                // 超时：不是"再等等"，是判失败。结果永远不来时挂在这里不退出，
                // 是另一种静默（报告上什么都看不到）。
                notDelivered.add(lastTag)
                probeInFlight = false
            } else {
                return
            }
        }

        val p = plan!![nextProbe]

        // VANISH 的"消失后"那条要等它真的消失几帧：拾取读的是帧缓冲，
        // 场景切换与提交/读取之间必须留出余量，否则"命中/未命中"分不清是哪一帧的。
        if (p.phase == "vanishAfter" && rendered < vanishFrame + 3) {
            return
        }

        // 提交当帧的同步答案：异步与同步是两条独立实现（PBO + fence vs 同步 glReadPixels），
        // 二者必须一致。**但只有它不够**——两条一起写错时它们会一起错，所以下面同时要求
        // 硬编码的期望值成立。见 PickVerifier 里同样的设计。
        val syncName = if (p.checkSync) nameAt(gc, p.deviceX, p.deviceY) else "-"
        syncAnswers[p.tag] = syncName
        if (p.altDeviceX >= 0) {
            altSyncAnswers[p.tag] = nameAt(gc, p.altDeviceX, p.altDeviceY)
        }

        nextProbe++
        probeInFlight = true
        lastTag = p.tag
        lastDispatchFrame = rendered
        Platform.runLater { fireSynthetic(p) }
    }

    /** ★ 真实点击阶段的调度状态。 */
    private var robotDispatched = false
    private var robotDone = false
    private var robotDispatchFrame = -1

    /** 真实点击阶段不可用时的原因（窗口被遮挡/没抢到焦点）；非 null 时该阶段记 [SKIP]。 */
    @Volatile
    private var robotUnavailable: String? = null

    private val robotTag = "ROBOT"

    /**
     * ★ 真实鼠标点击阶段：用 `Robot` 在**画布上真实地点一下**。
     *
     * <p>为什么非要有它：前面所有探针都是 `Node.fireEvent` 合成的，而 `fireEvent`
     * **不做命中测试**——它只要求事件对象送到节点上。也就是说，即使画布节点根本
     * 收不到真实鼠标事件（被遮挡、`pickOnBounds` 被改、互操作层把输入吃掉），
     * 那些探针**照样全绿**。这一条补的就是这个盲区。
     *
     * <p>目标点由设备像素反算成局部坐标，再用 `localToScreen` 变成屏幕坐标——
     * 于是整条链路（设备像素 → 局部 → 屏幕 → 操作系统 → JavaFX 拾取 → 局部 → 设备像素）
     * 闭合，其中"局部 → 屏幕"这一段由 JavaFX 负责，不是我们自己算的。
     */
    private fun robotPhase() {
        if (!robotDispatched) {
            robotDispatched = true
            robotDispatchFrame = rendered
            Platform.runLater { fireRobotClick() }
            return
        }
        if (outcomeArrived(robotTag)) {
            robotProbe = null
            robotDone = true
            return
        }
        if (rendered - robotDispatchFrame > 20) {
            // 结果没来：**判失败**（不是"再等等"）。原因可能是窗口没抢到焦点，
            // 也可能是这一下真的没送到——两种情况都该被人看见。
            robotDone = true
        }
    }

    /**
     * 在 JavaFX 线程上执行真实点击。
     *
     * <p>**不抢焦点就不点**：真实点击会作用到光标下的任何窗口，窗口不在最上层时
     * 那一下会落到别的程序上。所以窗口没焦点时直接记 [SKIP]，不做任何输入。
     */
    private fun fireRobotClick() {
        val m = metrics ?: return
        val s = stage
        if (s == null || !s.isFocused) {
            robotUnavailable = "窗口未获得焦点（stage.isFocused=false），为避免把点击落到别的窗口，本阶段不执行"
            robotDone = true
            return
        }
        // 目标：填充矩形内部（与 A 探针同一个点，便于对照）
        val deviceX = 0.265 * m.fbW
        val deviceY = 0.10 * m.fbH
        val localX = deviceX / m.scaleY
        val localY = deviceY / m.scaleY
        val screen = canvas.localToScreen(localX, localY)
        if (screen == null) {
            robotUnavailable = "localToScreen 返回 null（节点不在已显示的窗口里）"
            robotDone = true
            return
        }
        robotProbe = Probe(
            robotTag, "★ 真实鼠标点击（Robot，走操作系统事件）",
            localX, localY, true, "A_FILL_RECT", deviceX, deviceY, true
        )
        val robot = javafx.scene.robot.Robot()
        val before = robot.mousePosition
        robot.mouseMove(screen.x, screen.y)
        robot.mouseClick(MouseButton.PRIMARY)
        // 还原光标：这是本校验器唯一会动真实光标的动作，用完就还回去。
        if (before != null) {
            robot.mouseMove(before.x, before.y)
        }
    }

    /**
     * ★ FIFO 突发阶段：**一帧之内连点 8 下，每一下后面紧跟一次鼠标移动**。
     *
     * <p>这 8 对都在**同一个 JavaFX 线程任务**里发出，因此必然落在同一帧窗口内
     * （帧率 60 → 窗口 16.7 ms，而一对之间只隔几微秒）。它同时复现两件事：
     *
     * 1. **原始的丢失场景**：点击之后紧跟一次移动——真实用户点完往往就会动一下鼠标。
     *    改之前实测 `点击回调被交付 0/8 次`；改之后必须 8/8，且**按序**、**每条都对上对象**。
     * 2. **突发**：8 条点击同时入队，正好考验队列的按序交付。
     *
     * <p>移动走的是 hover 语义（最新覆盖旧的），所以只有最后一条移动还在
     * pendingPick 里——那是**规格内**的，本节只断言"至少交付过一条"。
     */
    private fun fifoPhase() {
        if (!fifoDispatched) {
            fifoDispatched = true
            fifoDispatchFrame = rendered
            Platform.runLater {
                for ((k, tag) in FIFO_PLAN_TAGS.withIndex()) {
                    val p = plan?.firstOrNull { it.tag == tag } ?: continue
                    val expect = p.expect
                    fireSynthetic(
                        Probe(
                            "fifo$k:click", "第 ${k + 1} 次点击（目标 $expect）", p.localX, p.localY,
                            true, expect, p.deviceX, p.deviceY, false
                        )
                    )
                    fireSynthetic(
                        Probe(
                            "fifo$k:move", "第 ${k + 1} 次点击后紧跟的移动", p.localX, p.localY,
                            true, null, p.deviceX, p.deviceY, false,
                            move = true, kind = ProbeKind.HOVER
                        )
                    )
                }
            }
            return
        }
        if (fifoOutcomesArrived() || rendered - fifoDispatchFrame > FIFO_DEADLINE) {
            fifoDone = true
            // **必须在这里取样**，不能等到结算：`droppedClicks()` 是累计值，
            // 而下一节（队列满）会故意丢掉 4 条——结算时读到的会把那 4 条算进来，
            // 于是这条断言永远失败（第一版就是这么错的）。
            fifoDropped = transfer?.droppedClicks() ?: -1
        }
    }

    /** 8 条点击的结果是否都到齐了。 */
    private fun fifoOutcomesArrived(): Boolean =
        (0 until FIFO_ROUNDS).all { outcomeArrived("fifo$it:click") }

    /**
     * ★ 队列**满**时的语义：一帧之内连发 20 条点击（4 条打 ORIGIN + 16 条打 FAR）。
     *
     * <p>容量是 16，所以先入队的 4 条**最旧的**会被丢掉，留下 16 条。
     * 判据是"交付的 16 条**全部**是 FAR"——丢最新的话留下来的会是
     * "4 条 ORIGIN + 12 条 FAR"，一眼分得开。
     *
     * <p>同时统计"这一帧想提交但两个 PBO 都忙"的帧数（队列非空的帧数 − 队列真的缩短了的帧数）：
     * 那个差值 > 0 就说明 `NO_FREE_SLOT` 那条分支真的走到了。
     */
    private fun overflowPhase() {
        if (!overflowDispatched) {
            overflowDispatched = true
            overflowDispatchFrame = rendered
            Platform.runLater {
                val origin = plan?.firstOrNull { it.tag == "ORIGIN" }
                val far = plan?.firstOrNull { it.tag == "FAR" }
                if (origin == null || far == null) {
                    return@runLater
                }
                for (k in 0 until OVERFLOW_ORIGIN) {
                    fireSynthetic(
                        Probe("ovf$k", "溢出前 $k（目标 ORIGIN_DOT）", origin.localX, origin.localY,
                            true, "ORIGIN_DOT", origin.deviceX, origin.deviceY, false)
                    )
                }
                for (k in 0 until OVERFLOW_FAR) {
                    fireSynthetic(
                        Probe("ovf${OVERFLOW_ORIGIN + k}", "溢出后 $k（目标 FAR_DOT）", far.localX, far.localY,
                            true, "FAR_DOT", far.deviceX, far.deviceY, false)
                    )
                }
            }
            return
        }
        if (overflowOutcomesArrived() || rendered - overflowDispatchFrame > OVERFLOW_DEADLINE) {
            overflowDone = true
        }
    }

    private fun overflowOutcomesArrived(): Boolean = overflowOutcomes().size >= expectDeliveredOverflow()

    /** 队列里最终应该交付多少条（容量以内的那些）。 */
    private fun expectDeliveredOverflow(): Int = OVERFLOW_TOTAL - (OVERFLOW_TOTAL - CAPACITY)

    /** 这一节实际收到的结果（按交付顺序）。 */
    private fun overflowOutcomes(): List<Outcome> =
        (0 until OVERFLOW_TOTAL).mapNotNull { k -> outcomes.firstOrNull { it.tag == "ovf$k" } }

    /**
     * 每一帧采一次队列深度，用来算出"想提交但没提交成"的帧数。
     *
     * <p>队列深度只在**成功提交**（或判定不需要 GPU）时才减 1，所以
     * "队列非空的帧数 − 队列变短了的帧数"就是提交被推迟的帧数——
     * 而那只有一个原因：两个 PBO 槽都还忙着（`NO_FREE_SLOT`）。
     */
    private fun sampleQueueDepth() {
        val depth = transfer?.clickQueueDepth() ?: return
        if (depth > 0) {
            queueBusyFrames++
            if (depth < lastQueueDepth) {
                queueDrainFrames++
            }
        }
        lastQueueDepth = depth
    }

    // ------------------------------------------------------------------
    // 指标、计划与结算
    // ------------------------------------------------------------------

    /** 在 JavaFX 线程上读一次节点与窗口指标。 */
    private fun captureMetrics(bridge: FXGLTransfer) {
        val node = canvas
        val window = node.scene?.window
        metrics = Metrics(
            nodeW = node.layoutBounds.width,
            nodeH = node.layoutBounds.height,
            fbW = bridge.scaledWidth,
            fbH = bridge.scaledHeight,
            scaleX = window?.outputScaleX ?: -1.0,
            scaleY = window?.outputScaleY ?: -1.0,
            pickOnBounds = node.isPickOnBounds,
            boundsX = node.boundsInLocal.minX,
            boundsY = node.boundsInLocal.minY
        )
    }

    /**
     * 打印并断言坐标约定。
     *
     * <p>这是本文件里**唯一**能证明"乘一个窗口缩放系数就够了"的地方：
     * 它断言帧缓冲尺寸确实等于 `ceil(节点逻辑尺寸 × outputScaleY)`，
     * 也就是断言 `Gc` 的坐标系与鼠标事件的坐标系之间**只差这一个系数、没有偏移**。
     * 同时打印 `outputScaleX`——**两轴用的是同一个系数**（帧缓冲两轴都按 `outputScaleY` 算），
     * 这不是笔误：非等比缩放下用 `outputScaleX` 换算 x 反而是错的。
     */
    private fun reportCalibration(bridge: FXGLTransfer, m: Metrics) {
        val failures = ArrayList<String>()
        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        println("\n=== JFGL 点击闭环校验 ===")
        println("-- 坐标约定 --")
        println("  节点局部尺寸 ${m.nodeW}x${m.nodeH} 逻辑像素；窗口缩放 X=${m.scaleX} Y=${m.scaleY}")
        println("  帧缓冲尺寸   ${m.fbW}x${m.fbH} 设备像素；Gc.width/height=${bridge.gc()?.width}x${bridge.gc()?.height}")
        println("  换算关系     设备像素 = 局部坐标 × ${m.scaleY}")

        report(
            "帧缓冲尺寸 = ceil(节点逻辑尺寸 × 窗口缩放)",
            m.fbW == ceil(m.nodeW * m.scaleY).toInt() && m.fbH == ceil(m.nodeH * m.scaleY).toInt(),
            "实测 ${m.fbW}x${m.fbH}，按 ${m.nodeW}x${m.nodeH} × ${m.scaleY} 应为 " +
                    "${ceil(m.nodeW * m.scaleY).toInt()}x${ceil(m.nodeH * m.scaleY).toInt()}"
        )
        // Region 默认 pickOnBounds = true。**合成事件绕过拾取**（fireEvent 不做命中测试），
        // 所以这条是"真实鼠标能不能到这个节点"的唯一防线：它一旦是 false，
        // 真实点击会落在父节点上，而本校验器其余部分照样全绿。
        report(
            "画布节点参与鼠标拾取（Region 的 pickOnBounds）",
            m.pickOnBounds,
            "isPickOnBounds=${m.pickOnBounds}（false 时真实鼠标事件永远到不了画布）"
        )
        report(
            "节点局部坐标原点即绘制区原点（无内边距/边框偏移）",
            abs(m.boundsX) < 0.001 && abs(m.boundsY) < 0.001,
            "boundsInLocal 起点=(${m.boundsX}, ${m.boundsY})"
        )
        calibrationFailures = failures.size
    }

    /** 坐标约定那一节的失败数。 */
    private var calibrationFailures = 0

    /**
     * 造探针计划。
     *
     * <p>每个探针的**设备像素**目标是硬编码的（都是 [layout] 里那些矩形的比例），
     * 而局部坐标是 `设备 ÷ 缩放` 反算出来的——这样计划本身与机器无关：
     * 换一个缩放系数，局部坐标跟着变，而"该点中谁"不变。
     *
     * @param w     帧缓冲宽度（设备像素）
     * @param h     帧缓冲高度（设备像素）
     * @param s     窗口缩放系数
     */
    private fun buildPlan(w: Float, h: Float, s: Double): List<Probe> {
        val out = ArrayList<Probe>(20)

        fun probe(
            tag: String,
            label: String,
            dx: Double,
            dy: Double,
            expect: String?,
            checkSync: Boolean = true,
            phase: String = "probe",
            convert: Boolean = true,
            altX: Double = -1.0,
            altY: Double = -1.0,
            move: Boolean = false
        ) {
            val localX = dx / s
            val localY = dy / s
            val devX = if (convert) localX * s else localX
            val devY = if (convert) localY * s else localY
            out.add(Probe(tag, label, localX, localY, convert, expect, devX, devY, checkSync, phase, altX, altY, move))
        }

        // 图形：填充 / 纯描边 / 圆角矩形（填充 + 边框）/ 圆（内部与"外接框角上"）
        probe("A", "填充矩形内部", 0.265 * w, 0.10 * h, "A_FILL_RECT")
        probe("ROUND_BODY", "圆角矩形填充内部", 0.425 * w, 0.10 * h, "ROUND_RECT")
        probe("ROUND_EDGE", "圆角矩形左边框上", 0.36 * w, 0.10 * h, "ROUND_RECT")
        probe("D_EDGE", "纯描边矩形的上边框上", 0.105 * w, 0.04 * h, "D_STROKE_RECT")
        probe("D_INNER", "纯描边矩形的内部（不填充 → 不命中）", 0.105 * w, 0.10 * h, null)
        probe("C_IN", "实心圆内部", 0.56 * w, 0.10 * h, "CIRCLE")
        probe("C_OUT", "圆的**外接框**角上（圆外 → 不命中）",
            0.56 * w - 0.05 * w + 3.0, 0.10 * h - 0.05 * w + 3.0, null)
        // 重叠：后画的赢
        probe("U_UNDER", "重叠区的下层独占部分", 0.07 * w, 0.70 * h, "UNDER")
        probe("U_OVER", "重叠区（后画的赢）", 0.13 * w, 0.70 * h, "OVER")
        // 角落：一个钉原点、一个钉远端
        probe("ORIGIN", "画面左上角（原点处无偏移）", 7.0, 7.0, "ORIGIN_DOT")
        probe("FAR", "画面右下角（远端缩放正确）", (w - 9).toDouble(), (h - 9).toDouble(), "FAR_DOT")
        // ★ 换算与不换算：同一个局部坐标，两条路
        probe("STAR_CONVERTED", "★ 同一局部坐标（换算后）→ 点中 P",
            0.62 * w, 0.42 * h, "P_TARGET")
        probe("STAR_RAW", "★ 同一局部坐标（**不换算**）→ 点中 Q",
            0.62 * w, 0.42 * h, "Q_TARGET", convert = false,
            altX = 0.62 * w, altY = 0.42 * h)
        // Q 的落点是 P ÷ 缩放，缩放越大它越靠近原点。本校验器为 1.15~2.05 留了空带；
        // 超出这个范围时 P/Q 可能与别的对象重叠，那两个探针的判据就不再是"换算对不对"了，
        // 于是明确跳过并打印原因——**不是静默跳过**，报告里有 [SKIP] 行。
        skipStarProbes = s < 1.15 || s > 2.05
        // 会消失的那个：消失前命中，消失后不命中（陈旧 ID 泄漏只有场景会变时才看得见）
        probe("V_BEFORE", "会消失的矩形（消失前）", 0.33 * w, 0.70 * h, "VANISH")
        probe("V_AFTER", "会消失的矩形（消失后 → 不命中）", 0.33 * w, 0.70 * h, null, phase = "vanishAfter")
        // 空白
        probe("BLANK", "画面空白处", 0.30 * w, 0.30 * h, null)
        // 文本：大小 / 颜色各不同，必须是两个不同的对象
        probe("T_BIG", "大号文本上", 0.04 * w + 20.0, 0.86 * h - 14.0, "TEXT_BIG")
        probe("T_SMALL", "小号文本上", 0.04 * w + 8.0, 0.95 * h - 6.0, "TEXT_SMALL")
        return out
    }

    /**
     * 在设备像素坐标上做一次**同步**拾取，返回命中的对象名（未命中为 null）。
     *
     * <p>只在 GL 线程上调用（它读的是当前帧已经渲染好的拾取缓冲）。
     */
    private fun nameAt(gc: Gc, x: Double, y: Double): String? =
        (gc.pick(x.toFloat(), y.toFloat())?.payload() as? Target)?.name

    private fun outcomeArrived(tag: String): Boolean = outcomes.any { it.tag == tag }

    /**
     * 结算：逐条核对探针结果，然后打印 ★ 点击丢失复现，最后以退出码收场。
     *
     * <p>这是**唯一**的退出点，所以各阶段的失败数在这里合并——中途退出会让
     * "已经打印了 FAIL 却报退出码 0"成为可能。
     */
    private fun settle() {
        // 等最后一节的结果：不等到就结算，会把"还没交付"当成"没跑"，
        // 报告上写着全部通过，而那条断言一次都没被评估。
        if (!overflowOutcomesArrived() && rendered - overflowDispatchFrame <= OVERFLOW_DEADLINE) {
            return
        }
        if (settled) {
            return
        }
        settled = true

        val failures = ArrayList<String>()
        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        println("\n-- 事件合成忠实性 --")
        // 只看探针的那些事件：★ 丢失复现那一段的事件不在探针计划里，没有"期望坐标"可比。
        val planned = outcomes.filter { o -> plan?.any { it.tag == o.tag } == true }
        val mismatched = planned.filter {
            abs(it.sawLocalX - expectedLocal(it.tag)) >= 1e-3 || abs(it.sawLocalY - expectedLocalY(it.tag)) >= 1e-3
        }
        report(
            "处理器看到的局部坐标 = 合成事件传入的坐标",
            planned.isNotEmpty() && mismatched.isEmpty(),
            if (mismatched.isEmpty()) "${planned.size} 条一致"
            else "标签 ${mismatched.first().tag}：传入 (${expectedLocal(mismatched.first().tag)}, " +
                    "${expectedLocalY(mismatched.first().tag)})，" +
                    "看到 (${mismatched.first().sawLocalX}, ${mismatched.first().sawLocalY})"
        )
        val offFx = outcomes.filter { !it.onFxThread }
        report(
            "事件处理在 JavaFX 应用线程上（生产路径）",
            outcomes.isNotEmpty() && offFx.isEmpty(),
            "${outcomes.size} 条中 ${offFx.size} 条不在 JavaFX 线程"
        )

        reportProbes(::report)

        reportRobotClick(::report)

        reportClickFifo(::report)

        reportOverflow(::report)

        val total = calibrationFailures + failures.size
        println()
        if (total == 0) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 $total 项（坐标约定 $calibrationFailures 项，探针 ${failures.size} 项）：" +
                    "${failures.joinToString("；")} ===")
        }
        Platform.exit()
        exitProcess(if (total == 0) 0 else 1)
    }

    private var settled = false

    /** 探针标签对应的期望局部坐标（合成忠实性断言用）。 */
    private fun expectedLocal(tag: String): Double =
        plan?.firstOrNull { it.tag == tag }?.localX ?: Double.NaN

    private fun expectedLocalY(tag: String): Double =
        plan?.firstOrNull { it.tag == tag }?.localY ?: Double.NaN

    /**
     * 逐条核对探针。
     *
     * <p>每条同时要求三件事成立：**硬编码的期望对象**、**同步与异步答案一致**、
     * **界面标签被回调更新过**。第一条是主判据；第二条挡"两条实现一起错"；
     * 第三条证明回调里确实能安全地碰界面（闭环的最后一环）。
     */
    private fun reportProbes(report: (String, Boolean, String) -> Unit) {
        println("\n-- 探针（每帧一条，命中判据是对象身份而不是像素颜色）--")
        val planList = plan ?: return
        val starSkipped = skipStarProbes

        for (p in planList) {
            if (p.tag == "STAR_RAW" && starSkipped) {
                println("  [SKIP] ${p.label} — 本机窗口缩放 ${metrics?.scaleY} 不在本校验器覆盖的范围内" +
                        "（P/Q 会与别的对象重叠），该条未执行")
                continue
            }
            if (p.tag == "STAR_CONVERTED" && starSkipped) {
                println("  [SKIP] ${p.label} — 同上")
                continue
            }
            val got = outcomes.filter { it.tag == p.tag }
            // singleOrNull 而不是 firstOrNull：交付两次也是错的（回调被重复调用），
            // 而 firstOrNull 会把重复交付掩盖成一次正常交付。
            val one = got.singleOrNull()
            val detail = String.format(
                "局部 (%.1f,%.1f) → 设备 (%d,%d)，期望=%s 实际=%s 命中ID=%d",
                p.localX, p.localY, p.deviceX.toInt(), p.deviceY.toInt(),
                p.expect ?: "未命中", one?.payload ?: "未交付", one?.id ?: -1
            )
            if (one == null) {
                report(p.label, false, detail + if (got.isEmpty() && notDelivered.contains(p.tag)) "（超时未交付）" else "")
                continue
            }
            // 未命中必须是 id==0 且 payload==null 两者同时成立：
            // "命中了一个 payload 为 null 的对象"与"什么都没命中"是两回事。
            var hitOk = if (p.expect == null) one.id == 0 && one.payload == null else one.payload == p.expect
            // 命中结果带回的查询点必须就是传进去的那个点（id==0 时 PickHit 根本不存在，
            // 也就没有坐标可回显，跳过这一项）。
            // 容差：合成探针是精确的（只差一次 toInt 截断）；真实点击那条多两级量化
            // ——操作系统光标只能落在**整数屏幕像素**上，再加上 JavaFX 自己的
            // 屏幕↔局部换算，往返误差实测约 1.4 个设备像素（见 reportRobotClick 的说明）。
            val tol = if (p.tag == robotTag) 3.0 else 1.5
            val echoOk = one.id == 0 ||
                    (abs(one.hitX - p.deviceX.toFloat()) < tol && abs(one.hitY - p.deviceY.toFloat()) < tol)
            hitOk = hitOk && echoOk
            report(p.label, hitOk, detail + if (one.id != 0) "，回显 (${one.hitX.toInt()},${one.hitY.toInt()})" else "")
            if (p.checkSync) {
                val sync = syncAnswers[p.tag]
                report("  ↳ ${p.tag} 异步与同步一致", one.payload == sync, "同步=$sync 异步=${one.payload}")
            }
            val labelOk = if (p.expect == null) one.labelText == "未命中" else one.labelText == "命中：${p.expect}"
            report("  ↳ ${p.tag} 回调里更新了界面", labelOk, "标签文本=\"${one.labelText}\"")
        }

        // 样式：清单要求"含边框色/是否填充/填充色"与"文本大小/颜色"都要可点，
        // 那就不该只验证"点中了某个对象"——拿回来的那条必须真的记着那些值。
        println("\n-- 命中对象的样式（清单要求的那几项）--")
        fun payloadOf(tag: String): Target? = outcomes.firstOrNull { it.tag == tag }?.payload
            ?.let { name -> targets[name] }

        val round = payloadOf("ROUND_BODY")
        report(
            "圆角矩形：填充且带边框（两个色都记着）",
            round != null && round.filled && round.fillHex == 0xFFFFA726.toInt() && round.strokeHex == 0xFF6D4C41.toInt(),
            "filled=${round?.filled} fill=0x${round?.fillHex?.let { Integer.toHexString(it) }} stroke=0x${round?.strokeHex?.let { Integer.toHexString(it) }}"
        )
        val strokeOnly = payloadOf("D_EDGE")
        report(
            "纯描边矩形：不填充，边框色正确",
            strokeOnly != null && !strokeOnly.filled && strokeOnly.strokeHex == 0xFF42A5F5.toInt(),
            "filled=${strokeOnly?.filled} stroke=0x${strokeOnly?.strokeHex?.let { Integer.toHexString(it) }}"
        )
        report(
            "边框与填充命中同一个对象（不是两个）",
            payloadOf("ROUND_EDGE") === round,
            "边框=${payloadOf("ROUND_EDGE")?.name} 填充=${round?.name}"
        )
        val big = payloadOf("T_BIG")
        val small = payloadOf("T_SMALL")
        report(
            "大号文本：字号 ${TEXT_BIG_SIZE.toInt()} 与颜色都对",
            big != null && big.fontSize == TEXT_BIG_SIZE && big.fillHex == TEXT_BIG_COLOR,
            "fontSize=${big?.fontSize} fill=0x${big?.fillHex?.let { Integer.toHexString(it) }}"
        )
        report(
            "小号文本：字号 ${TEXT_SMALL_SIZE.toInt()} 与颜色都对",
            small != null && small.fontSize == TEXT_SMALL_SIZE && small.fillHex == TEXT_SMALL_COLOR,
            "fontSize=${small?.fontSize} fill=0x${small?.fillHex?.let { Integer.toHexString(it) }}"
        )
        report(
            "两段文本是两个不同的对象（字号不能张冠李戴）",
            big != null && small != null && big !== small && big.fontSize != small.fontSize,
            "大=${big?.fontSize} 小=${small?.fontSize}"
        )

        // ★ 反证：同一个局部坐标，换算与不换算落在两个不同的对象上。
        val converted = outcomes.firstOrNull { it.tag == "STAR_CONVERTED" }?.payload
        val raw = outcomes.firstOrNull { it.tag == "STAR_RAW" }?.payload
        val star = planList.first { it.tag == "STAR_CONVERTED" }
        val altAnswer = altSyncAnswers["STAR_RAW"] ?: "-"
        println("\n-- ★ 坐标换算是否承重（“点 A 命中 B”的复现）--")
        println("  同一个局部坐标 (${star.localX.toInt()}, ${star.localY.toInt()})：")
        println("    换算后查询 → ${converted ?: "未交付"}")
        println("    不换算查询 → ${raw ?: "未交付"}（落在设备像素 ${blankStarRawDevice()}，那里是另一个对象）")
        println("    换算后的那个点上，同步拾取给出 $altAnswer —— 那才是“本该命中谁”")
    }

    private fun blankStarRawDevice(): String {
        val p = plan?.firstOrNull { it.tag == "STAR_RAW" } ?: return "-"
        return "(${p.deviceX.toInt()},${p.deviceY.toInt()})"
    }

    /**
     * ★ 探针是否因为缩放不在覆盖范围内而被跳过。
     *
     * <p>P 与 Q 的落点由窗口缩放决定：Q 在 `P ÷ 缩放` 处，缩放越大它越靠近原点。
     * 本校验器的布局为 1.15~2.05 留了空带；超出这个范围时 P/Q 可能与别的对象重叠，
     * 那时两个探针的判据就不再是"换算对不对"了，所以**明确跳过并打印原因**，
     * 而不是让它变成一次看起来像缺陷的假失败。（这不是静默跳过：报告里有 [SKIP] 行。）
     */
    private var skipStarProbes = false

    /**
     * ★ 真实鼠标点击（Robot）那一节的判定。
     *
     * <p>它答的是一个合成事件答不了的问题：**真实鼠标事件到底有没有到达画布**。
     * 合成事件直接发给节点，绕过了 JavaFX 的拾取，所以哪怕画布一个真实事件都收不到，
     * 前面那一整套断言也照样全绿。
     */
    private fun reportRobotClick(report: (String, Boolean, String) -> Unit) {
        println("\n-- ★ 真实鼠标点击（Robot，走操作系统事件，不是合成事件）--")
        val unavailable = robotUnavailable
        if (unavailable != null) {
            println("  [SKIP] $unavailable")
            return
        }
        val got = outcomes.filter { it.tag == robotTag }
        val one = got.singleOrNull()
        val expectedLocal = robotExpectedLocal()
        report(
            "真实点击到达画布并命中正确的对象",
            one != null && one.payload == "A_FILL_RECT",
            "期望=A_FILL_RECT 实际=${one?.payload ?: if (got.isEmpty()) "未收到回调" else "收到 ${got.size} 条"}，命中ID=${one?.id ?: -1}"
        )
        if (one != null && expectedLocal != null) {
            // 这一条把"我们的换算"与"JavaFX 自己的屏幕↔局部换算"对上：
            // 光标按 localToScreen 移过去，事件里回来必须是同一个局部坐标。
            //
            // 容差 3 个**局部**像素不是随手松的：真实光标只能落在整数屏幕像素上
            // （1 屏幕像素 = 1/缩放 ≈ 0.8 局部像素），再加上 JavaFX 的屏幕↔局部换算
            // 与 localToScreen 不是逐位互逆，实测往返差约 1.4 个设备像素。
            // 判据依然是有效的：换算错一个缩放系数的话差的是**几十**像素，不是 1 个。
            val dx = abs(one.sawLocalX - expectedLocal[0])
            val dy = abs(one.sawLocalY - expectedLocal[1])
            report(
                "真实事件里的局部坐标 ≈ 我们按 localToScreen 算出去的那个（±3 局部像素）",
                dx < 3.0 && dy < 3.0,
                "期望 (${expectedLocal[0]}, ${expectedLocal[1]})，实际 (${one.sawLocalX}, ${one.sawLocalY})，" +
                        "差 (${(dx * 100).toInt() / 100.0}, ${(dy * 100).toInt() / 100.0})"
            )
        }
    }

    /** 真实点击的目标点在**局部坐标**下是多少（与 [fireRobotClick] 里那个点必须一致）。 */
    private fun robotExpectedLocal(): DoubleArray? {
        val m = metrics ?: return null
        return doubleArrayOf(0.265 * m.fbW / m.scaleY, 0.10 * m.fbH / m.scaleY)
    }

    /**
     * ★ FIFO：**点击不再被后一次请求覆盖**。
     *
     * <p>修改前这里是 `[KNOWN]`：一帧内"先点击、几微秒后移动"（真实用户点完往往就会
     * 动一下鼠标）实测 `点击回调被交付 0/8 次`——8 次真实点击**全部静默消失**，
     * 界面毫无反应、没有任何错误。原因是 `pickAsync` 只保留最新一次请求。
     *
     * <p>现在点击走独立的有界 FIFO，所以本节是**真判据**（计入退出码），一共三件：
     *
     * 1. **不丢**：8 次点击 8 条回调；
     * 2. **按序**：交付顺序 == 提交顺序（每条打的是**不同的对象**，
     *    所以顺序这件事真的可判——全打同一个对象的话乱序也看不出来）；
     * 3. **对象正确**：每条回调拿到的 `payload` 就是它那一下点中的对象。
     *
     * <p>移动仍然走 hover 语义（最新覆盖旧的），所以只有最后一条还在队列里，
     * 本节只断言"至少交付过一条"——那是**规格内**的，不是缺陷。
     */
    private fun reportClickFifo(report: (String, Boolean, String) -> Unit) {
        println("\n-- ★ 点击 FIFO（8 下连点 + 每下紧跟一次移动）--")
        val clicks = outcomes.filter { it.tag.startsWith("fifo") && it.tag.endsWith(":click") }
        val moves = outcomes.count { it.tag.startsWith("fifo") && it.tag.endsWith(":move") }
        val dropped = fifoDropped

        report(
            "点击全交付（一帧内 8 下连点，每下后面紧跟一次移动）",
            clicks.size == FIFO_ROUNDS,
            "交付 ${clicks.size}/$FIFO_ROUNDS 条" +
                    if (clicks.size < FIFO_ROUNDS) {
                        "，缺 " + (0 until FIFO_ROUNDS).filter { k -> clicks.none { it.tag == "fifo$k:click" } }
                    } else ""
        )

        // 保序：交付顺序（outcomes 是追加写的，顺序即交付顺序）必须等于提交顺序。
        val deliveredOrder = clicks.map { it.tag }
        val expectedOrder = (0 until FIFO_ROUNDS).map { "fifo$it:click" }
        report(
            "点击按提交顺序交付（FIFO）",
            deliveredOrder == expectedOrder,
            "实际顺序=${deliveredOrder.joinToString(",")}"
        )

        // 对象正确：第 k 下点的是 FIFO_PLAN_TAGS[k] 那个对象，拿回来的必须是它。
        val wrong = (0 until FIFO_ROUNDS).mapNotNull { k ->
            val want = plan?.firstOrNull { it.tag == FIFO_PLAN_TAGS[k] }?.expect ?: return@mapNotNull null
            val got = outcomes.firstOrNull { it.tag == "fifo$k:click" }?.payload
            if (got == want) null else "第${k + 1}下 期望=$want 实际=$got"
        }
        val pairs = (0 until FIFO_ROUNDS).joinToString("；") { k ->
            "${FIFO_PLAN_TAGS[k]}→${outcomes.firstOrNull { it.tag == "fifo$k:click" }?.payload ?: "未交付"}"
        }
        report("每次点击拿回的都是它自己点中的那个对象", wrong.isEmpty(), pairs)

        report(
            "本轮没有任何点击因队列满被丢弃",
            dropped == 0,
            "droppedClicks=$dropped（累计值；队列满只在下一节人为造出来）"
        )
        // 移动那条是同一帧里最后提交的，**必须**至少交付一次：它要是也没到，
        // 说明这一节连"事件确实发出去了"这个前提都没成立。
        report("移动（hover）至少交付过一次（证明事件路径本身通）", moves >= 1, "交付 $moves/$FIFO_ROUNDS 条（hover 语义：只留最新一条）")
    }

    /**
     * ★ 队列满时的语义：**丢最旧**，并且**不静默**（计数器）。
     *
     * <p>一帧内连发 20 条（4 条打 ORIGIN、16 条打 FAR），容量是 [CAPACITY]，
     * 所以最旧的 4 条被丢掉、留下 16 条。判据是"交付的 16 条**全部**是 FAR_DOT"：
     * 若实现改成"丢最新"，留下来的会是"4 条 ORIGIN + 12 条 FAR"，
     * 这条断言会立刻失败——两种语义给出**不同的观测结果**，这才叫判据。
     *
     * <p>同时报告"想提交但两个 PBO 都忙"的帧数（见 [sampleQueueDepth]）：
     * 差值 > 0 就说明 `NO_FREE_SLOT` 那条分支真的走到了。
     * 而无论走到没走到，**一条都不许丢**——这是 FIFO 相对 hover 的关键改进。
     */
    private fun reportOverflow(report: (String, Boolean, String) -> Unit) {
        println("\n-- ★ 队列满：丢最旧，且不静默 --")
        val delivered = overflowOutcomes()
        val dropped = transfer?.droppedClicks() ?: -1
        val farCount = delivered.count { it.payload == "FAR_DOT" }
        val originCount = delivered.count { it.payload == "ORIGIN_DOT" }
        println("  一帧内连发 $OVERFLOW_TOTAL 条（$OVERFLOW_ORIGIN 条打 ORIGIN_DOT，$OVERFLOW_FAR 条打 FAR_DOT），容量 $CAPACITY")

        report(
            "容量以内的全部交付（$CAPACITY 条）",
            delivered.size == CAPACITY,
            "交付 ${delivered.size} 条"
        )
        report(
            "丢的是**最旧**的 $OVERFLOW_ORIGIN 条（留下的全是后发的 FAR_DOT）",
            farCount == CAPACITY && originCount == 0,
            "FAR_DOT=$farCount ORIGIN_DOT=$originCount（若丢最新则 ORIGIN_DOT 会是 $OVERFLOW_ORIGIN）"
        )
        report(
            "丢弃被计数（不是静默）",
            dropped == OVERFLOW_TOTAL - CAPACITY,
            "droppedClicks=$dropped，期望=${OVERFLOW_TOTAL - CAPACITY}"
        )
        report(
            "留下的 $CAPACITY 条仍按序交付",
            delivered.map { it.tag } == (0 until CAPACITY).map { "ovf${OVERFLOW_ORIGIN + it}" },
            "实际顺序=${delivered.map { it.tag }.joinToString(",")}"
        )

        val busy = queueBusyFrames
        val drain = queueDrainFrames
        val stalls = busy - drain
        println("  队列非空 $busy 帧、其中成功提交 $drain 帧（FIFO + 溢出两节累计）⇒ 提交被推迟 $stalls 帧（NO_FREE_SLOT）")
        println(
            if (stalls > 0) {
                "  → `NO_FREE_SLOT` 这条分支**实测走到了**：两个 PBO 都忙时并没有丢请求，只是推迟一帧。"
            } else {
                "  → `NO_FREE_SLOT` 这条分支**本轮仍未复现**（两个 PBO 槽始终有一个空闲）。" +
                        "它不丢请求的理由在代码里：取队列用的是 peek，只有提交成功才 poll，否则留到下一帧。"
            }
        )
    }
}
