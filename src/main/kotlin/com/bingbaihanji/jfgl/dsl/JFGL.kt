package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.scene.Scene
import javafx.stage.Stage

/**
 * JFGL 应用配置与入口，承载窗口参数与两个生命周期回调。
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

        val mainView = MainView().apply {
            center = bridge.createGlFXView()
        }
        stage.title = config.title
        stage.scene = Scene(mainView.createMainView(), config.width, config.height)
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
