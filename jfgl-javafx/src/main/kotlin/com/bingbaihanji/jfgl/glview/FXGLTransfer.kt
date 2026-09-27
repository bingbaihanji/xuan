package com.bingbaihanji.jfgl.glview

import com.bingbaihanji.jfgl.gl.LwjglGLAbstraction
import com.bingbaihanji.jfgl.chart.Chart
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
import java.util.HashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.input.MouseEvent
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
 * @param msaa 多重采样抗锯齿的采样数。**默认 0**（关）；**只能在构造时给**
 *   ——采样数是帧缓冲的属性，`GLCanvas` 没有 setter（实测）。
 *   **非 0 的 `msaa`（含负数）都会让像素回读失效**，见 [canReadPixels]——
 *   负数按 openglfx 的约定是"用最大采样数"，**不是"关"**。
 *   它是**公开只读**的：七个校验器的回读守卫要把这个值打进失败信息
 *   （"画布是 msaa=4 的多采样 FBO"），而 `example/MsaaVerifier.kt` 还要拿它
 *   核对"请求值有没有真的生效"。
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
    val msaa: Int = GLCanvas.Defaults.MSAA,
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

    /**
     * 渲染异常处理器。
     *
     * <p>**`@Volatile` 不是装饰**：本字段由 JavaFX 应用线程写（`onError { }`）、
     * 由 **GL 线程**读（`reportRenderFailure`），不加的话 JVM 允许 GL 线程
     * 一直读到 `null` ⇒ 处理器注册了却永远不生效，异常继续走 `printStackTrace`
     * ——表现是"我明明设了 onError，它却还在刷栈"。
     * （同文件其余几个回调字段是同样的形状。它们都在首帧之前设好，实际也安全；
     * 这里加 `@Volatile` 是因为**这个字段的读点发生在每一帧的异常路径上**，
     * 而"注册不生效"恰好是最难查的那类。）
     */
    @Volatile
    private var onErrorCallback: ((Throwable) -> Unit)? = null

    /**
     * 渲染异常处理器。**建议在 `onInit` / `start` 里设一次**（见 [onErrorCallback] 的说明）。
     */
    fun onError(callback: (Throwable) -> Unit) {
        onErrorCallback = callback
    }

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

    /**
     * 待提交的**点击**请求队列。[pickAsync] 的「最新覆盖旧的」对**离散的点击**是错的：
     * 一次点击的回调被后一次请求覆盖掉，这次点击就**什么都不会发生、且没有任何错误**。
     * 点击因此走这条有界 FIFO，逐帧按序交付。
     *
     * <p>**容量写死在 [CLICK_QUEUE_CAPACITY]**：它必须是有界的，否则"点得比帧率快"
     * （脚本连发、或渲染卡住时用户狂点）会让队列无限增长。满了的语义见 [clickAsync]。
     *
     * <p>用 [ArrayBlockingQueue] 而不是自己拿 [AtomicReference] 拼一个：容量由数据结构
     * 本身保证，`size()` 是 O(1) 且线程安全（写者在 JavaFX 线程、消费者在 GL 线程）。
     */
    private val clickQueue = ArrayBlockingQueue<PickRequest>(CLICK_QUEUE_CAPACITY)

    /**
     * 因队列满而被丢弃的点击请求数（累计，**从不复位**）。
     *
     * <p>丢弃既然是必然可达的一条路径（见 [clickAsync]），就不能是静默的：
     * 这个计数器是它唯一的出口，调试时先看它是不是 0。
     */
    private val droppedClicks = AtomicInteger()

    /** 已提交给 PBO、等待 GPU 完成的请求；只在 GL 线程访问。 */
    private val inFlightPicks = HashMap<Long, PickRequest>()

    /** PBO 结果与请求关联的单调令牌；只在 GL 线程递增。 */
    private var nextPickToken = 1L

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
                try {
                    onFrameCallback?.invoke(context)
                    context.endFrame()
                    // 必须在 endFrame 之后：ID pass 是在提交时渲染的，
                    // 提前读会拿到本帧尚未写入的缓冲。
                    resolvePendingPick(context)
                } catch (failure: Throwable) {
                    context.abortFrame()
                    reportRenderFailure(failure)
                }
            }
            try {
                onRenderCallback?.invoke()
            } catch (failure: Throwable) {
                reportRenderFailure(failure)
            }
        }

        // 视口调整：Gc 的像素→NDC 基础矩阵每帧都按 scaledWidth/scaledHeight 重建，
        // 这里同步 GL 视口即可。reshape 事件带的宽高正是 DPI 缩放后的帧缓冲尺寸，
        // 与 beginFrame 用的是同一套值，两者不会对不上。
        addOnReshapeEvent { event ->
            glViewport(0, 0, event.width, event.height)
        }

        // 释放：销毁批处理提交器持有的全部 GL 资源
        addOnDisposeEvent {
            var callbackFailure: Throwable? = null
            try {
                onDisposeCallback?.invoke()
            } catch (failure: Throwable) {
                callbackFailure = failure
            } finally {
                // 图表后端在上层，先放它再放批处理：所有权链条是
                // FXGLTransfer → RenderBatch，而 Gc.charts 挂在这条链的下游。
                gc?.disposeCharts()
                renderBatch?.dispose()
                renderBatch = null
                gc = null
            }
            callbackFailure?.let { reportRenderFailure(it) }
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

    /**
     * 当前画布能否用 `glReadPixels` 回读像素。**只有 `msaa == 0` 时能。**
     *
     * <p>判据是"**画布 FBO 是不是单采样**"，与 `msaa` 的**符号无关**——
     * 两处都不看符号：
     * <ul>
     *   <li>**负数不是"关"**：openglfx 的 `GLCanvas` KDoc 写明 `-1 – maximum available samples`，
     *       实现是 `msaa < 0 -> Framebuffer.MultiSampled(width, height, GL_MAX_SAMPLES)`
     *       ⇒ **负数是多采样画布**。实测 `-Djfgl.probe.msaa=-1`：
     *       `GL_SAMPLE_BUFFERS=1`、`GL_SAMPLES=32`（本机上限），
     *       而 `glReadPixels` 报 `GL_INVALID_OPERATION`、读回全 0。
     *       ⚠️ 写 `msaa <= 0` 会把它当成"可以回读"⇒ 症状正是这条守卫要消灭的那种
     *       （六个校验器照常开跑、读回全 0、报一整片"画面全黑"式假失败）。</li>
     *   <li>**`msaa = 1` 也不是单采样**：实数会被驱动抬到它能支持的档位，
     *       实测 `msaa=1` ⇒ `GL_SAMPLE_BUFFERS=1`、`GL_SAMPLES=2`。
     *       ⇒ **只有 0 是单采样**，于是判据只能是 `== 0`。</li>
     * </ul>
     *
     * <p>理由已实测（`example/MsaaVerifier.kt` 把它逐次钉着）：多采样帧缓冲上
     * `glReadPixels` 直接返回 `GL_INVALID_OPERATION`，读出来是全 0
     * （不是"读到旧帧"、也不是"读到黑"——是**那次调用整个非法**）。
     *
     * <p>本仓库**靠画布 FBO 回读的那六个像素校验器**（`Pipeline` / `Path` / `Pick` /
     * `Click` / `Text` / `Chart`）都靠它回读。**没有这条守卫，它们会读回全 0
     * 然后报一大堆"画面全黑"式的假失败**——那比"明确拒绝"坏得多：
     * 假失败会让人去查渲染，而真因在配置。
     *
     * <p>⚠️ **第七个 `FftVerifier` 是例外**：它**不画像素**，读的是 SSBO
     * （`glGetBufferSubData`），那条路与画布帧缓冲的采样数无关 ⇒ `msaa != 0` **不影响它**
     * （实测：`msaa=4` 下 50 条断言全过、与 `msaa=0` 逐行相同）。它也挂同一道守卫，
     * 但那是**纪律**（七个一律 `msaa=0` 这条口径比"每个各自判断自己受不受影响"更不容易
     * 出错），**不是实测的必要性**——别把"六个"读成"七个"。
     *
     * <p>⚠️ **不受影响的是拾取**：拾取 FBO 是**独立创建的单采样** FBO
     * （`gl/Framebuffer` 明文写着"恒为单采样"），实测 `msaa=4` 下
     * `pick` / `pickRect` 与 `msaa=0` **逐项相同**。所以"`msaa` 非 0"与
     * "拾取坏了"是两回事，别把这一条读成后者。
     *
     * @return `msaa == 0` 时为 true；其余（含**负数**与 1）为 false
     */
    val canReadPixels: Boolean get() = msaa == 0

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
     * 返回累计执行过的 ID pass 次数，**仅供校验器断言「跳过优化」确实生效**。
     *
     * <p>没有它，那条优化就只是注释里的一句承诺——而「优化悄悄失效」
     * 正是本项目最该防的那类问题。生产代码不应依赖它。
     *
     * @return ID pass 执行次数；GL 未初始化时为 0
     */
    fun pickPassCountForTest(): Int = renderBatch?.pickPassCount() ?: 0

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

    /** 累计的渲染失败次数。**GL 线程写、任意线程读**，所以是 `@Volatile`。 */
    @Volatile
    private var renderFailureCount = 0

    /**
     * 累计的渲染失败次数。
     *
     * <p>存在的理由是**节流**：帧回调里的失败会**每帧重演**（漏了一个 `restore()`
     * 就每帧都抛），而帧率是 60 ⇒ 不节流的话控制台每秒刷 60 段同样的栈，
     * 真正有用的第一段立刻被冲走。这与本文件里那条既有的口径是同一条
     * （"这里跳过而不是抛异常，**否则渲染线程会每帧刷一次栈**"）。
     *
     * <p>计数本身也是给间歇性问题用的：现象是"偶尔崩一帧"，而"偶尔"到底多偶尔，
     * 只有计数说得出来。
     */
    fun renderFailureCount(): Int = renderFailureCount

    private fun reportRenderFailure(failure: Throwable) {
        val attempt = ++renderFailureCount
        // ★ 只有**第一次**打完整的栈，之后每 60 次打一行摘要（约每秒一行）。
        //   不节流的话：一帧一次、每秒 60 次，把有意义的第一段冲掉；
        //   而完全不打又会让"一直在失败"变成静默。
        val firstOrPeriodic = attempt == 1 || attempt % 60 == 0
        val handler = onErrorCallback
        if (handler != null) {
            // 处理器总是被调用（它可能有自己的计数/上报/熔断），
            // 但**它的异常只报第一次**，否则处理器自己坏掉时会变成新的刷屏源。
            try {
                handler(failure)
            } catch (handlerFailure: Throwable) {
                handlerFailure.addSuppressed(failure)
                if (attempt == 1) {
                    handlerFailure.printStackTrace()
                }
            }
            if (firstOrPeriodic) {
                System.err.println("[jfgl] 渲染失败第 $attempt 次：${failure}")
            }
            return
        }
        if (firstOrPeriodic) {
            System.err.println("[jfgl] 渲染失败第 $attempt 次（同一失败会每帧重演，只报首次与每 60 次）")
            failure.printStackTrace()
        }
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
     * <p><strong>它是给「连续量」用的（hover、拖拽）。</strong>连续量每一帧重算一次，
     * 旧的答案本来就过时了，所以覆盖是对的。**离散的点击请用 [clickAsync] 或
     * [onClick]**：点击被覆盖掉的表现是"点了没反应，且没有任何错误"。
     *
     * @param x        查询点 x（用户坐标，y 向下）
     * @param y        查询点 y（用户坐标，y 向下）
     * @param callback 结果回调，在 JavaFX 应用线程上被调用；未命中时参数为 null
     */
    fun pickAsync(x: Float, y: Float, callback: (PickHit?) -> Unit) {
        pendingPick.set(PickRequest(x, y, callback))
    }

    /**
     * 请求在**节点局部坐标** [x]/[y] 处做一次异步拾取。
     *
     * <p>这是给 JavaFX 鼠标事件用的入口：[MouseEvent.getX]/`getY` 给的是**画布节点的
     * 局部坐标**（逻辑像素），而 [pickAsync] 要的是**设备像素**，两者差一个窗口输出
     * 缩放系数。少了这一次换算，点击会落在**另一个对象**上，而画面完全正常——
     * 在 100% 缩放的机器上还一切正常（那时系数是 1），是典型的"在我机器上没问题"。
     *
     * <p><strong>两轴都用 `outputScaleY`，这是刻意的，不是笔误</strong>：
     * 帧缓冲的宽高都由它算出（`GLCanvas.scaledWidth/Height = ceil(节点尺寸 × dpi)`，
     * 而 `dpi = GLFXUtils.getDPI(node)` 只取 `window.outputScaleY`），所以
     * "1 个局部单位 = 多少个帧缓冲像素"在 x/y 上是**同一个数**。反过来用
     * `outputScaleX` 换算 x，在非等比缩放下反而是错的。
     * ⚠️ 本机（Windows 125%）`outputScaleX == outputScaleY == 1.25`，
     * **非等比缩放造不出来，因此这一条没有实测**——上面是选 Y 的理由，不是实测结论。
     *
     * @param node     画布节点（`createGlFXView()` 的返回值），坐标以它的左上角为原点
     * @param x        节点局部坐标 x
     * @param y        节点局部坐标 y
     * @param callback 结果回调，在 JavaFX 应用线程上被调用；未命中时参数为 null
     */
    fun pickAsyncAtNode(node: Node, x: Double, y: Double, callback: (PickHit?) -> Unit) {
        val scale = deviceScale(node)
        pickAsync((x * scale).toFloat(), (y * scale).toFloat(), callback)
    }

    /**
     * 请求在**节点局部坐标** [x]/[y] 处做一次异步拾取，语义是**离散的点击**。
     *
     * <p>与 [pickAsyncAtNode] 的区别只有交付语义：这条走**有界 FIFO**，
     * 一次点击一条、按序交付、**不会因为后面来了别的请求就被覆盖掉**。
     * 鼠标点击请用这条（或直接用 [onClick]）。
     *
     * @param node     画布节点，坐标以它的左上角为原点
     * @param x        节点局部坐标 x
     * @param y        节点局部坐标 y
     * @param callback 结果回调，在 JavaFX 应用线程上被调用；未命中时参数为 null
     */
    fun clickAsyncAtNode(node: Node, x: Double, y: Double, callback: (PickHit?) -> Unit) {
        val scale = deviceScale(node)
        clickAsync((x * scale).toFloat(), (y * scale).toFloat(), callback)
    }

    /**
     * 在节点上接一个**点击**回调：内部注册 `MOUSE_CLICKED` 并按 [clickAsyncAtNode]
     * 的语义交付。
     *
     * <p>这是把"点击闭环"接起来的最短路径——调用方不必自己记住"局部坐标要乘窗口缩放"，
     * 也不必自己处理事件注册/注销。`jfgl { }` 的 `onClick { }` 就是转接到这里。
     *
     * <p>回调在 **JavaFX 应用线程**上执行，可以安全地改界面。同一个节点可以接多个
     * 回调（JavaFX 的处理器表天然支持），它们都会收到同一次点击。
     *
     * @param node     画布节点（`createGlFXView()` 的返回值）
     * @param callback 结果回调；未命中时参数为 null
     */
    fun onClick(node: Node, callback: (PickHit?) -> Unit) {
        node.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            clickAsyncAtNode(node, event.x, event.y, callback)
        }
    }

    /**
     * 把节点鼠标移动直接绑定到图表 hover 状态。
     *
     * <p>事件坐标会自动换算为设备像素；离开节点时清除 hover。图表绘制调用
     * `gc.charts.drawChart(chart, ...)` 后会自动绘制十字线、命中点和提示框。
     */
    fun trackChartHover(node: Node, chart: Chart) {
        node.addEventHandler(MouseEvent.MOUSE_MOVED) { event ->
            val scale = deviceScale(node)
            chart.interaction().updatePointer((event.x * scale).toFloat(), (event.y * scale).toFloat())
            repaint()
        }
        node.addEventHandler(MouseEvent.MOUSE_EXITED) {
            chart.interaction().clearPointer()
            repaint()
        }
    }

    /**
     * 请求在**设备像素**坐标 [x]/[y] 处做一次异步拾取，语义是**离散的点击**。
     *
     * <h2>为什么不能复用 [pickAsync]</h2>
     *
     * <p>`pickAsync` 只保留最新一次请求。对 hover 那是对的（连续量、每帧重算），
     * 但**点击是离散事件**：一次点击的回调被后一次请求覆盖掉，这次点击就
     * **什么都不会发生、且没有任何错误**。只要应用同时用 `pickAsync` 做 hover
     * （文档推荐的用法），鼠标点完往往还会动一下，两者相隔几微秒、落在同一帧窗口内，
     * 于是**点击几乎必丢**。
     *
     * <h2>交付语义</h2>
     *
     * <ul>
     *   <li><strong>按序（FIFO）</strong>：先点的先交付，一帧交付一条（与 PBO 的
     *       回收节奏一致），因此不会乱序；</li>
     *   <li><strong>不覆盖</strong>：后到的请求不影响已入队的；</li>
     *   <li><strong>有界，容量 [CLICK_QUEUE_CAPACITY]</strong>：约 0.27 秒的积压
     *       （60fps 每帧交付一条）。</li>
     * </ul>
     *
     * <h2>队列满时：丢弃**最旧**的一条</h2>
     *
     * <p>这是刻意的选择，不是"没想清楚"：
     *
     * <ul>
     *   <li><strong>丢最旧而不是丢最新</strong>：用户刚点的那一下才是他的意图；
     *       而积压了 16 帧（约 0.27 秒）的那一下早就是过期意图了——那一帧的场景
     *       可能都已经变了（列表滚动过、弹窗关掉了），交付它比不交付更糟。</li>
     *   <li><strong>不抛异常</strong>：这个方法在 JavaFX 事件处理器里被调用，
     *       抛出去会打断事件分发；而"用户点得比帧率快"不是程序错误，
     *       是必然出现的正常压力。</li>
     *   <li><strong>但绝不静默</strong>：每一次丢弃都计入 [droppedClicks]，
     *       该计数器累计且从不复位。丢弃这条路是可观测的。</li>
     * </ul>
     *
     * <p>队列满只可能发生在"积压"时，而点击的积压说明这一帧的拾取已经连续
     * [CLICK_QUEUE_CAPACITY] 帧没能交付——真到那一步，该调的是帧率或拾取成本，
     * 而不是把队列开大。
     *
     * @param x        查询点 x（设备像素；来自鼠标事件时请用 [clickAsyncAtNode]）
     * @param y        查询点 y（设备像素）
     * @param callback 结果回调，在 JavaFX 应用线程上被调用；未命中时参数为 null
     */
    fun clickAsync(x: Float, y: Float, callback: (PickHit?) -> Unit) {
        val request = PickRequest(x, y, callback)
        if (clickQueue.offer(request)) {
            return
        }
        // 满：腾一格给这一条（丢最旧的）。每一次丢弃都要计数，包括下面那次兜底，
        // 否则"并发下这条也没进去"就成了唯一一条静默路径。
        val dropped = clickQueue.poll()
        if (dropped != null) {
            droppedClicks.incrementAndGet()
        }
        if (!clickQueue.offer(request)) {
            droppedClicks.incrementAndGet()
        }
    }

    /**
     * 当前**待提交**的点击请求数。
     *
     * <p>不含已经提交给 PBO、正在等 GPU 的那些（那些在 [inFlightPicks] 里）。
     * 供诊断与断言用：持续不为 0 说明交付跟不上点击。
     *
     * @return 队列深度（0 表示没有积压）
     */
    fun clickQueueDepth(): Int = clickQueue.size

    /**
     * 返回累计因队列满而被丢弃的点击请求数（**从不复位**）。
     *
     * <p>没有它，"丢弃"就是一条静默路径；有了它，调用方可以断言它一直是 0。
     *
     * @return 累计丢弃数
     */
    fun droppedClicks(): Int = droppedClicks.get()

    /**
     * 把**已经提交成功**（或已判定无需 GPU）的那条请求从点击队列头部取下来。
     *
     * <p><strong>必须是 `remove(request)` 而不是 `poll()`</strong>：`peek` 与本次提交之间，
     * JavaFX 线程可能又入队了一条，而队列**满**时 [clickAsync] 会丢掉**最旧**的一条——
     * 那条恰好可能就是我刚才 peek 到的这个。此时 `poll()` 会把**下一条**（还没提交过）
     * 取下来扔掉，而它的回调永远没人调：又是一条静默丢失，正是这个队列存在的理由的反面。
     *
     * <p>`remove(Object)` 按**身份**删（[PickRequest] 没有重写 `equals`，每次入队都是新对象），
     * `n ≤ 容量` 所以线性查找的代价可以忽略。返回 false 也没关系：说明它已经被 [clickAsync]
     * 当作"最旧的一条"丢掉了，而它此刻已经被提交，回调照样会交付一次（不多不少）。
     *
     * @param request 刚刚提交的那条请求
     */
    private fun takeFromClickQueue(request: PickRequest) {
        clickQueue.remove(request)
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
     * 节点局部坐标 → 设备像素的换算系数。
     *
     * <p>取的是**窗口输出缩放**，与 `GLCanvas.dpi` 同一个量。节点还没上场景/窗口时
     * （理论上鼠标事件不可能发生）按 1 处理，而不是崩。
     *
     * <p>不用 `scaledWidth / node.width` 反推：那是同一个量的另一种算法，但
     * `scaledWidth` 是 `ceil(宽度 × 缩放)`，反推出来会带上最多 1 个像素的误差。
     *
     * <p><strong>为什么它是 public</strong>：[pickAsyncAtNode] / [clickAsyncAtNode] 用它把
     * 鼠标事件的坐标换算成 [Gc] 要的设备像素。**任何自己接鼠标事件的应用也要做同一件事**
     * （库只给了 `onClick` 这一个事件接线入口，没有 `onDrag` / `onHover` / `onScroll`）。
     * 而漏乘这个系数的表现是"点 A 命中 B，画面完全正常"——在 100% 缩放的机器上还一切正常。
     * 与其让每个应用各自重新推导一遍，不如把这一乘公开出来。
     *
     * <p>用法：`val s = bridge.deviceScale(canvas); val dx = (e.x * s).toFloat(); val dy = (e.y * s).toFloat()`
     * （用 `e.x` / `e.y`（节点局部），**不是** `e.sceneX` / `e.sceneY`——后者是场景坐标，
     * 与画布原点差一个布局偏移，乘出来是静默错位的。）
     * （**两轴都用它**，理由见 [pickAsyncAtNode]）。
     */
    fun deviceScale(node: Node): Double = node.scene?.window?.outputScaleY ?: 1.0

    /**
     * 非阻塞地消费 PBO 结果，再把下一条请求提交给空闲 PBO。
     *
     * <p>PBO 读回至少跨一帧：提交后只在 fence 已完成时映射，GPU 未完成时直接返回，
     * 不会让 JavaFX hover 在 `glReadPixels` 上等待。双缓冲都忙时，请求保留到下一帧。
     *
     * <p><strong>每帧只提交一条</strong>，优先取积压的**点击**（FIFO，见 [clickAsync]），
     * 队列空了才取 hover 的最新一条（[pickAsync] 的「最新覆盖旧的」语义原样不动）。
     * 点击优先是有意的：点击是离散意图，hover 每帧重算、晚一两帧没有代价
     * （它本来就会被后续移动覆盖）。
     *
     * @param context 当前帧的绘制上下文
     */
    private fun resolvePendingPick(context: Gc) {
        context.pollAsyncPick()?.let { result ->
            val request = inFlightPicks.remove(result.token()) ?: return@let
            val hit = if (result.id() == 0) {
                null
            } else {
                PickHit(result.id(), context.pickRegistry.resolve(result.id()), request.x, request.y)
            }
            Platform.runLater { request.callback(hit) }
        }

        // 点击队列优先。**peek 而不是 poll**：只有真正提交成功（或明确判定为
        // 不需要 GPU 就能回答）才把它取走。PBO 两个槽都忙时留在队里，下一帧再试——
        // 这样"有界队列"在任何情况下都不会丢一条本可以交付的点击。
        val queued = clickQueue.peek()
        val fromClickQueue = queued != null
        val request = queued ?: pendingPick.getAndSet(null) ?: return
        val token = nextPickToken++
        when (context.enqueueAsyncPick(request.x.toInt(), request.y.toInt(), token)) {
            com.bingbaihanji.jfgl.renderer.PickBuffer.AsyncReadStatus.QUEUED -> {
                inFlightPicks[token] = request
                if (fromClickQueue) {
                    takeFromClickQueue(request)
                }
            }
            com.bingbaihanji.jfgl.renderer.PickBuffer.AsyncReadStatus.NO_FREE_SLOT -> {
                // 点击：留在队里下帧再试（上面的 peek 已经保证了这一点）。
                // hover：新请求已经抵达时保留它；否则把本次最新请求留到下一帧。
                if (!fromClickQueue) {
                    pendingPick.compareAndSet(null, request)
                }
            }
            com.bingbaihanji.jfgl.renderer.PickBuffer.AsyncReadStatus.OUT_OF_BOUNDS,
            com.bingbaihanji.jfgl.renderer.PickBuffer.AsyncReadStatus.NO_PICK_CONTENT -> {
                // 不需要 GPU 就能回答（越界 / 本帧没有 ID pass）：直接交付"未命中"，
                // 而不是让它留在队里等到天荒地老。
                if (fromClickQueue) {
                    takeFromClickQueue(request)
                }
                Platform.runLater { request.callback(null) }
            }
        }
    }

    companion object {
        /**
         * [RenderBatch] 的初始顶点容量（单位：顶点）。
         *
         * <p>65536 个顶点即 2.0 MiB（每个顶点 32 字节），够画满一屏文字级别的图元量而无需扩容；
         * 超出后会触发帧中途 flush，不会失败。
         */
        private const val INITIAL_VERTEX_CAPACITY = 65536

        /**
         * 点击队列的容量。
         *
         * <p>一帧交付一条，所以 16 条约等于 **0.27 秒**的积压。取值理由：人手点击最快
         * 每秒十几次，而拾取滞后的容忍上限是"用户还没觉得卡"的那个量级；
         * 更大只会让"过期意图"交付得更晚（见 [clickAsync] 的满时语义）。
         */
        private const val CLICK_QUEUE_CAPACITY = 16
    }
}
