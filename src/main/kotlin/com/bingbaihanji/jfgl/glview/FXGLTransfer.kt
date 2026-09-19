package com.bingbaihanji.jfgl.glview

import com.bingbaihanji.jfgl.gl.LwjglGLAbstraction
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit
import com.bingbaihanji.jfgl.renderer.RenderBatch
import com.huskerdev.grapl.gl.GLContext
import com.huskerdev.grapl.gl.GLProfile
import com.huskerdev.openglfx.GLExecutor
import com.huskerdev.openglfx.canvas.GLCanvas
import com.huskerdev.openglfx.canvas.events.GLRenderEvent
import com.huskerdev.openglfx.internal.GLInteropType
import com.huskerdev.openglfx.lwjgl.LWJGLExecutor.Companion.LWJGL_MODULE
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.scene.Node
import org.lwjgl.opengl.GL11.*

/**
 * JavaFX 与 OpenGL 的桥接封装，提供可配置的 GLCanvas 及事件管理。
 *
 * <p>本类同时持有批处理管线：GL 上下文就绪时创建 [RenderBatch] 与 [Gc]，
 * 每帧由本类配对调用 `beginFrame`/`endFrame`，中间的绘制交给 [onFrame] 注册的回调。
 * 之所以在这里创建而不是让调用方创建：[RenderBatch] 的构造会编译着色器、生成 VAO/VBO，
 * 必须在 GL 线程且上下文已 current 的时刻进行，而这个时刻只有本类知道。
 *
 * <p>**构造时机**：本类必须在 **JavaFX 工具包启动之后**才能构造。默认参数
 * `GLCanvas.Defaults.INTEROP_TYPE` 是 `GLInteropType.auto`，其类初始化要向 JavaFX 询问当前
 * Prism 渲染管线，工具包尚未启动时会抛
 * `UnsupportedOperationException: Could not detect pipeline`。
 * 也就是说：不要在 `Application.launch` 之前 `new FXGLTransfer()`，要放到 `Application.start` 里。
 *
 * @param executor OpenGL 实现库（默认 LWJGL_MODULE）
 * @param flipY Y 轴翻转
 * @param msaa 多重采样抗锯齿
 * @param fps 目标帧率
 * @param swapBuffers 交换链缓冲数
 * @param interopType 互操作类型
 * @param profile OpenGL 配置文件
 * @param glDebug 是否调试
 * @param shareWith 共享上下文
 * @param majorVersion OpenGL 主版本
 * @param minorVersion OpenGL 次版本
 * @param externalWindow 是否创建外部窗口
 */
class FXGLTransfer(
    private val executor: GLExecutor = LWJGL_MODULE,
    flipY: Boolean = GLCanvas.Defaults.FLIP_Y,
    msaa: Int = GLCanvas.Defaults.MSAA,
    fps: Double = GLCanvas.Defaults.FPS,
    swapBuffers: Int = GLCanvas.Defaults.SWAP_BUFFERS,
    interopType: GLInteropType = GLCanvas.Defaults.INTEROP_TYPE,
    profile: GLProfile = GLCanvas.Defaults.PROFILE,
    glDebug: Boolean = GLCanvas.Defaults.DEBUG,
    shareWith: GLContext? = GLCanvas.Defaults.SHARE_WITH,
    majorVersion: Int = GLCanvas.Defaults.MAJOR_VERSION,
    minorVersion: Int = GLCanvas.Defaults.MINOR_VERSION,
    externalWindow: Boolean = GLCanvas.Defaults.EXTERNAL_WINDOW
) {

    /** 批处理提交器。只有在 GL 上下文就绪之后才能构造，因此是在初始化回调里创建的。 */
    private var renderBatch: RenderBatch? = null

    /** 绘制上下文门面。与 [renderBatch] 同生共死，未初始化时为 null。 */
    private var gc: Gc? = null

    private var onInitCallback: (() -> Unit)? = null
    private var onRenderCallback: (() -> Unit)? = null
    private var onDisposeCallback: (() -> Unit)? = null

    /** 逐帧绘制回调，参数是当前帧的 [Gc]。 */
    private var onFrameCallback: ((Gc) -> Unit)? = null

    /**
     * 待处理的异步拾取请求。
     *
     * <p><strong>「最新覆盖旧的」而不是队列</strong>：鼠标拖拽每秒产生几十个事件，
     * 而帧率只有 60，排队毫无意义且会累积延迟。
     *
     * <p>用 [AtomicReference] 而不是 `@Volatile` 字段：取出与清空必须是原子的，
     * 否则在「读到旧值」与「置空」之间到达的新请求会被丢掉。
     */
    private val pendingPick = AtomicReference<PickRequest?>()

    // 创建 GLCanvas 实例（所有参数在构造时确定，不可变）
    private val canvas = GLCanvas(
        executor = executor,
        flipY = flipY,
        msaa = msaa,
        fps = fps,
        swapBuffers = swapBuffers,
        interopType = interopType,
        profile = profile,
        glDebug = glDebug,
        shareWith = shareWith,
        majorVersion = majorVersion,
        minorVersion = minorVersion,
        externalWindow = externalWindow
    ).apply {
        // 初始化：创建批处理提交器与绘制上下文
        addOnInitEvent {
            glClearColor(0.2f, 0.2f, 0.2f, 1.0f)
            val gl = LwjglGLAbstraction()
            // RenderBatch 在构造期就编译着色器、生成 VAO/VBO/纹理，必须有活着的 GL 上下文，
            // 因此只能在这里创建；此前 gc() 一直返回 null。
            val batch = RenderBatch(gl, INITIAL_VERTEX_CAPACITY)
            renderBatch = batch
            gc = Gc(batch)
            onInitCallback?.invoke()
        }

        // 渲染：开一帧、交给逐帧回调画、提交
        addOnRenderEvent {
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            val context = gc
            // 画布尺寸在首帧布局完成前可能还是 0，而 beginFrame 要求正尺寸；
            // 这里跳过而不是抛异常，否则渲染线程会每帧刷一次栈。
            if (context != null && scaledWidth > 0 && scaledHeight > 0) {
                context.beginFrame(scaledWidth, scaledHeight)
                onFrameCallback?.invoke(context)
                context.endFrame()
                // 必须在 endFrame 之后：ID pass 是在提交时渲染的，
                // 提前读会拿到本帧尚未写入的缓冲。
                resolvePendingPick(context)
            }
            onRenderCallback?.invoke()
        }

        // 视口调整：Gc 的像素→NDC 基础矩阵每帧都按 scaledWidth/scaledHeight 重建，
        // 这里同步 GL 视口即可。reshape 事件带的宽高正是 DPI 缩放后的帧缓冲尺寸，
        // 与 beginFrame 用的是同一套值，两者不会对不上。
        addOnReshapeEvent { event ->
            glViewport(0, 0, event.width, event.height)
        }

        // 释放：销毁批处理提交器持有的全部 GL 资源
        addOnDisposeEvent {
            onDisposeCallback?.invoke()
            renderBatch?.dispose()
            renderBatch = null
            gc = null
        }
    }

    /**
     * 返回 JavaFX 节点（GLCanvas 本身），可直接放入场景图。
     */
    fun createGlFXView(): Node = canvas

    /**
     * 手动触发重绘（当 fps = 0 时尤其有用）。
     */
    fun repaint() = canvas.repaint()

    /**
     * 释放所有 OpenGL 资源（在窗口关闭时调用）。
     */
    fun dispose() = canvas.dispose()

    // 可选：暴露画布的宽高（DPI 缩放后）
    val scaledWidth: Int get() = canvas.scaledWidth
    val scaledHeight: Int get() = canvas.scaledHeight

    // 可选：修改帧率（动态）
    var fps: Double
        get() = canvas.fps
        set(value) { canvas.fps = value }

    // 可选：添加自定义渲染监听（保留原有事件链）
    fun addRenderListener(listener: (GLRenderEvent) -> Unit) {
        canvas.addOnRenderEvent { event -> listener(event) }
    }

    /**
     * 返回当前的绘制上下文；**GL 初始化完成之前为 null**。
     *
     * <p>本方法可在任意线程调用，但返回的 [Gc] 只能在 GL 线程上使用
     * （见 [Gc] 的线程说明）。
     *
     * @return 绘制上下文，未初始化时为 null
     */
    fun gc(): Gc? = gc

    /**
     * 设置逐帧绘制回调。**帧已经开好**，回调里直接画即可，不要自己调用
     * `beginFrame`/`endFrame`（它们由本类配对调用）。
     *
     * @param callback 接收当前帧绘制上下文的回调
     */
    fun onFrame(callback: (Gc) -> Unit) {
        onFrameCallback = callback
    }

    /**
     * 设置GL初始化时的回调
     */
    fun onInit(callback: () -> Unit) {
        onInitCallback = callback
    }

    /**
     * 设置渲染时的回调
     */
    fun onRender(callback: () -> Unit) {
        onRenderCallback = callback
    }

    /**
     * 设置释放时的回调
     */
    fun onDispose(callback: () -> Unit) {
        onDisposeCallback = callback
    }

    /**
     * 异步查询某个点上最上层的可拾取图元。
     *
     * <p>这是**给 JavaFX 应用线程用的**入口：组件的鼠标事件都在那个线程上，
     * 而 [Gc] 只能在 GL 线程使用。本方法把请求记下来，在下一帧渲染完成后于 GL 线程解析，
     * 再把结果经 `Platform.runLater` **送回 JavaFX 线程**——这样回调里可以安全地
     * 碰 JavaFX 状态，不需要调用方自己再跳一次。
     *
     * <p>同一时刻只保留最新的一次请求；连续调用会覆盖前一次的回调。
     *
     * @param x        查询点 x（用户坐标，y 向下）
     * @param y        查询点 y（用户坐标，y 向下）
     * @param callback 结果回调，在 JavaFX 应用线程上被调用；未命中时参数为 null
     */
    fun pickAsync(x: Float, y: Float, callback: (PickHit?) -> Unit) {
        pendingPick.set(PickRequest(x, y, callback))
    }

    /**
     * 一次待处理的拾取请求。
     *
     * @param x        查询点 x
     * @param y        查询点 y
     * @param callback 结果回调
     */
    private class PickRequest(
        val x: Float,
        val y: Float,
        val callback: (PickHit?) -> Unit
    )

    /**
     * 取出待处理的拾取请求并在 GL 线程上解析，结果经 JavaFX 线程送达。
     *
     * <p>先 `getAndSet(null)` 再解析：解析期间到达的新请求会被保留下来，
     * 留给下一帧处理，而不是被这次清空顺手丢掉。
     *
     * @param context 当前帧的绘制上下文
     */
    private fun resolvePendingPick(context: Gc) {
        val request = pendingPick.getAndSet(null) ?: return
        val hit = context.pick(request.x, request.y)
        Platform.runLater { request.callback(hit) }
    }

    companion object {
        /**
         * [RenderBatch] 的初始顶点容量（单位：顶点）。
         *
         * <p>65536 个顶点即 1.5 MB（每个顶点 24 字节），够画满一屏文字级别的图元量而无需扩容；
         * 超出后会触发帧中途 flush，不会失败。
         */
        private const val INITIAL_VERTEX_CAPACITY = 65536
    }
}
