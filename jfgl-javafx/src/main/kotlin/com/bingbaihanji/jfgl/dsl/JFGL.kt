package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.stage.Stage

/**
 * JFGL 应用配置与入口，承载窗口参数、两个生命周期回调与点击回调。
 *
 * <p>典型用法：
 * ```kotlin
 * fun main() {
 *     jfgl {
 *         title = "我的应用"
 *         onRender {
 *             fill = 0xFFFF0000.toInt()
 *             fillRect(10f, 10f, 100f, 50f)
 *         }
 *     }
 * }
 * ```
 *
 * <p>要接鼠标点击就再加一个 [onClick]：`onRender` 里用 `gc.pickable(id) { }` 打标，
 * `onClick` 里就能从 `hit.payload()` 拿回那个对象（细节见 [onClick] 的说明）。
 *
 * <p>要放 `Label` / `Button` 这类 **JavaFX 控件**就再加一个 [onScene]，
 * 在里面往 [overlay] 里加（那是一个叠在画布之上的透明容器）。
 *
 * <p>坐标系：回调拿到的是 [Gc]（批处理的 2D 绘制上下文），坐标是**像素、原点左上、y 向下**。
 *
 * <p>**注意高 DPI**：用户坐标按 1:1 映射到**设备像素**，而设备像素尺寸受系统缩放影响——
 * 上面 `width = 800` 的窗口在 125% 缩放下，实际帧缓冲是 988×738 设备像素。因此画到
 * `x = 800` 只覆盖约 81% 的宽度。需要铺满整屏时请用 [Gc.width] / [Gc.height]，
 * 而不是这里配置的逻辑尺寸。
 *
 * <p>**回调线程**：[onInit] 与 [onRender] 都在 GL 线程上调用，可以直接调用 [Gc] 的任何方法，
 * 但**不要**在其中做 JavaFX 场景图操作——那些必须在 JavaFX 应用线程上进行。
 */
class JFGL {

    /** 窗口标题。 */
    var title: String = "JFGL 应用"

    /** 窗口宽度（逻辑像素）。 */
    var width: Double = 800.0

    /** 窗口高度（逻辑像素）。 */
    var height: Double = 600.0

    /** GL 初始化完成、[Gc] 已可用时的回调。 */
    private var onInitCallback: ((Gc) -> Unit)? = null

    /** 每帧绘制回调，接收者为 [Gc]。 */
    private var onRenderCallback: (Gc.() -> Unit)? = null

    /** 点击回调。为空表示不接鼠标事件（那就不做任何拾取，白付代价）。 */
    private var onClickCallback: ((PickHit?) -> Unit)? = null

    /** 场景图就绪回调，在 JavaFX 应用线程上调用一次。 */
    private var onSceneCallback: ((Scene) -> Unit)? = null

    /**
     * 叠加在 GL 画布**之上**的 JavaFX 容器：`Label`、`Button`、`Tooltip` 这类控件放这里。
     *
     * <h2>它是"最小的口子"，不是一套布局</h2>
     * <p>本库的画布是一个普通的 JavaFX 节点，所以控件与画布共存本来就不需要什么机制——
     * 缺的只是"往哪儿放"。这里给的就是那一个位置：一个背景透明的 `Pane`，
     * 叠在画布上面。想排工具条就把自己的 `BorderPane`/`HBox` 塞进来，本库不替你决定版式。
     *
     * <p>用 `StackPane` 叠放而不是把画布塞进 `BorderPane` 的中心，是因为**控件要浮在画面上**
     * 才是这里最常见的用法（状态角标、十字光标读数、图例开关）。要占整条边的工具条，
     * 自己往里放一个 `BorderPane` 即可。
     *
     * <h2>三条必须知道的</h2>
     * <ol>
     *   <li><b>只能在 JavaFX 应用线程上碰它</b>——它是场景图的一部分。可用的时机是
     *       [onScene] 与 [onClick] 这两个回调（它们都在 JavaFX 线程上）；
     *       [onRender] / [onInit] 在 GL 线程上，在那里加控件是跨线程操作场景图，
     *       崩起来毫无规律。</li>
     *   <li><b>它自己不吃鼠标事件</b>（`isPickOnBounds = false`），所以画布上的点击照旧
     *       落到画布上，`onClick` 不会因为多了这个容器而失灵。但**控件本身占的那块地方会吃掉点击**
     *       ——点在按钮上不该同时命中画布，这是对的。</li>
     *   <li><b>坐标系是 JavaFX 的</b>（逻辑像素、随窗口缩放），而 [Gc] 用的是设备像素。
     *       两者在高 DPI 下差一个缩放系数（见 [Gc.width] 的说明），
     *       所以"把控件对准画面上的某个图形"要先自己做一次换算，
     *       或者干脆用 `localToScene` 之类的 JavaFX 手段（`ClickExample` 里就是这么做的）。</li>
     * </ol>
     *
     * <p>典型用法：
     * ```kotlin
     * jfgl {
     *     onScene {                     // JavaFX 线程，窗口显示之前
     *         val label = Label("在图形上点一下")
     *         overlay.children.add(label)
     *     }
     *     onClick { hit -> /* ... */ }  // JavaFX 线程，可以安全地改那个 label
     * }
     * ```
     */
    val overlay: Pane by lazy { Pane().apply { isPickOnBounds = false } }

    /**
     * 设置**场景图就绪**回调：在 JavaFX 应用线程上、窗口显示之前调用一次，
     * 参数是刚建好的 [Scene]。
     *
     * <p>为什么需要它：[onInit] / [onRender] 都在 GL 线程上（不能碰场景图），
     * [onClick] 在 JavaFX 线程上但要等到用户点一下。往 [overlay] 里放控件需要一个
     * "JavaFX 线程、只跑一次"的时机，这个回调就是那一个。
     *
     * <p>在配置块（`jfgl { ... }`）里直接 new 控件是**不行的**：那个块在
     * `Application.launch` 之前执行，JavaFX 工具包还没起来。
     *
     * @param block 回调；参数是场景（根节点可用 `scene.root` 取）
     */
    fun onScene(block: (Scene) -> Unit) {
        onSceneCallback = block
    }

    /**
     * 转发场景就绪回调。由 [JFGLApplication] 在 JavaFX 线程上调用。
     *
     * @param scene 刚建好的场景
     */
    internal fun invokeScene(scene: Scene) {
        onSceneCallback?.invoke(scene)
    }

    /**
     * 设置**点击**回调：在画布上点一下，回调拿到命中的对象。
     *
     * <p>这是 `jfgl { }` 里接鼠标的唯一入口。它替调用方做了三件容易做错的事：
     *
     * 1. 注册 `MOUSE_CLICKED`（并**做坐标换算**——鼠标事件给的是节点的**逻辑**局部
     *    坐标，而拾取要的是**设备像素**，两者差一个窗口缩放系数；少了它的表现是
     *    "点 A 命中 B"，画面完全正常，在 100% 缩放的机器上还一切正常）；
     * 2. 走**点击队列**而不是"最新覆盖旧的"——离散的点击被后一次请求覆盖掉会
     *    **静默消失**（见 `FXGLTransfer.clickAsync`）；
     * 3. 把回调送回 **JavaFX 应用线程**，因此可以安全地改界面。
     *
     * <p>典型用法：
     * ```kotlin
     * jfgl {
     *     onInit { gc -> id = gc.pickRegistry.register(myObject) }   // 注册载荷
     *     onRender { gc -> gc.pickable(id) { gc.fillRect(...) } }    // 打标
     *     onClick { hit -> label.text = hit?.payload().toString() }  // 点中谁就是谁
     * }
     * ```
     *
     * <p>回调里**不要**直接调 [Gc]：它的任何方法都必须在 GL 线程上运行。
     * 需要"点中之后改画面"，把结果存进一个 `@Volatile` 字段，在 [onRender] 里读。
     *
     * @param block 点击回调；未命中时参数为 null
     */
    fun onClick(block: (PickHit?) -> Unit) {
        onClickCallback = block
    }

    /**
     * 是否接了点击回调。由 [JFGLApplication] 判断要不要装事件处理器。
     *
     * @return 已设置 [onClick] 时为 true
     */
    internal fun wantsClicks(): Boolean = onClickCallback != null

    /**
     * 转发点击回调。由 [JFGLApplication] 在 JavaFX 线程上调用。
     *
     * @param hit 命中结果；未命中时为 null
     */
    internal fun invokeClick(hit: PickHit?) {
        onClickCallback?.invoke(hit)
    }

    /**
     * 设置初始化回调：GL 上下文就绪后调用一次，参数是可用于**一次性**准备工作的 [Gc]
     * （例如预上传纹理、构建路径）。
     *
     * <p>此时不能绘制——绘制必须发生在 [onRender] 里，因为每帧开始时顶点缓冲会被清空。
     *
     * @param block 初始化回调
     */
    fun onInit(block: (Gc) -> Unit) {
        onInitCallback = block
    }

    /**
     * 设置逐帧绘制回调。每帧被调用一次，返回后本帧收集到的全部顶点会被一次性提交。
     *
     * @param block 绘制回调，接收者为 [Gc]
     */
    fun onRender(block: Gc.() -> Unit) {
        onRenderCallback = block
    }

    /**
     * 启动 JavaFX 应用。本方法会阻塞到窗口关闭。
     *
     * <p>桥接对象**必须等到 JavaFX 工具包启动之后**才能创建——`GLCanvas.Defaults.INTEROP_TYPE`
     * 是 `GLInteropType.auto`，它在类初始化时要向 JavaFX 询问当前的 Prism 渲染管线，
     * 工具包尚未启动时会抛 `UnsupportedOperationException: Could not detect pipeline`。
     * 因此这里只把配置交给 [JFGLApplication]，真正创建 [FXGLTransfer] 的时机放在
     * `Application.start` 里（那时工具包必然已经就绪）。
     */
    fun start() {
        JFGLApplication.config = this
        Application.launch(JFGLApplication::class.java)
    }

    /**
     * 转发初始化回调。由 [JFGLApplication] 在 GL 上下文就绪后调用。
     *
     * @param gc 已可用的绘制上下文
     */
    internal fun invokeInit(gc: Gc) {
        onInitCallback?.invoke(gc)
    }

    /**
     * 转发逐帧绘制回调。由 [JFGLApplication] 在每帧开帧之后调用。
     *
     * @param gc 当前帧的绘制上下文
     */
    internal fun invokeRender(gc: Gc) {
        onRenderCallback?.invoke(gc)
    }
}

/**
 * JavaFX 应用外壳：只负责搭出窗口并把 [FXGLTransfer] 的 GL 画布放进场景图。
 *
 * <p>之所以用伴生对象传递配置：`Application.launch` 要求目标类有一个无参构造器并由 JavaFX
 * 自行实例化，没有机会把配置通过构造器传进去。
 */
internal class JFGLApplication : Application() {

    companion object {
        /** 当前应用配置，由 [JFGL.start] 在 `launch` 之前写入。 */
        var config: JFGL? = null
    }

    /**
     * 桥接对象。
     *
     * <p>是实例字段而不是伴生对象字段：它只能在本类的 `start` 里创建
     * （见 [JFGL.start] 里关于 JavaFX 工具包启动顺序的说明），
     * 而 `stop` 与 `start` 是同一个实例上的回调，实例字段天然配对。
     */
    private var transfer: FXGLTransfer? = null

    /**
     * JavaFX 启动回调：创建桥接对象、接线回调，把 GL 画布放进 [MainView] 的中心区域并显示窗口。
     *
     * <p>此刻 JavaFX 工具包已经启动，因此可以安全地构造 [FXGLTransfer]。
     *
     * @param stage 主窗口
     */
    override fun start(stage: Stage) {
        val config = config ?: error("JFGL 配置缺失：请通过 jfgl { } 启动，不要直接 launch JFGLApplication")
        val bridge = FXGLTransfer()
        bridge.onInit {
            // 走到这里 Gc 必然已经创建好；用 let 兜住"未来某天接线顺序变了"的情况，
            // 而不是用 !! 在回调里制造一个无从定位的空指针。
            bridge.gc()?.let { gc -> config.invokeInit(gc) }
        }
        bridge.onFrame { gc -> config.invokeRender(gc) }
        transfer = bridge

        val view = bridge.createGlFXView()
        // 只有真的注册了点击回调才装事件处理器：没接鼠标的应用不该为每次点击
        // 白付一次 GPU 拾取的开销。
        if (config.wantsClicks()) {
            bridge.onClick(view) { hit -> config.invokeClick(hit) }
        }

        // 画布在下、叠加层在上。**顺序就是 z 序**：StackPane 按 children 的先后画，
        // 后一个在上面，所以控件浮在画面上。
        val mainView = MainView().apply {
            center = StackPane(view, config.overlay)
        }
        stage.title = config.title
        val scene = Scene(mainView.createMainView(), config.width, config.height)
        stage.scene = scene
        // 控件必须在 JavaFX 线程上、并趁场景已经建好时加进来。放在 show() 之前：
        // 第一帧就能看见它们（放到 show() 之后会闪过一帧没有控件的画面）。
        config.invokeScene(scene)
        stage.show()
    }

    /** 窗口关闭时释放 GL 资源。 */
    override fun stop() {
        transfer?.dispose()
    }
}

/**
 * JFGL DSL 入口：配置并启动一个 JFGL 应用。
 *
 * <p>本函数会阻塞到窗口关闭（内部是 `Application.launch`）。
 *
 * @param block 应用配置块，接收者为 [JFGL]
 */
fun jfgl(block: JFGL.() -> Unit) {
    JFGL().apply(block).start()
}
