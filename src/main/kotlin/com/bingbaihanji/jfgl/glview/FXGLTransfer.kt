package com.bingbaihanji.jfgl.glview

import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.gl.LwjglGLAbstraction
import com.huskerdev.grapl.gl.GLContext
import com.huskerdev.grapl.gl.GLProfile
import com.huskerdev.openglfx.GLExecutor
import com.huskerdev.openglfx.canvas.GLCanvas
import com.huskerdev.openglfx.canvas.events.GLRenderEvent
import com.huskerdev.openglfx.internal.GLInteropType
import com.huskerdev.openglfx.lwjgl.LWJGLExecutor.Companion.LWJGL_MODULE
import javafx.scene.Node
import org.lwjgl.opengl.GL11.*

/**
 * JavaFX 与 OpenGL 的桥接封装，提供可配置的 GLCanvas 及事件管理。
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

    private var drawEngine: DrawEngine? = null
    private var onInitCallback: (() -> Unit)? = null
    private var onRenderCallback: (() -> Unit)? = null
    private var onDisposeCallback: (() -> Unit)? = null

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
        // 初始化：创建并初始化 DrawEngine
        addOnInitEvent {
            glClearColor(0.2f, 0.2f, 0.2f, 1.0f)
            val gl = LwjglGLAbstraction()
            drawEngine = DrawEngine(gl, scaledWidth, scaledHeight)
            drawEngine?.initialize()
            onInitCallback?.invoke()
        }

        // 渲染：委托给 DrawEngine
        addOnRenderEvent { event ->
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            drawEngine?.render()
            onRenderCallback?.invoke()
        }

        // 视口调整：通知 DrawEngine
        addOnReshapeEvent { event ->
            glViewport(0, 0, event.width, event.height)
            drawEngine?.resize(scaledWidth, scaledHeight)
        }

        // 释放：销毁 DrawEngine
        addOnDisposeEvent {
            onDisposeCallback?.invoke()
            drawEngine?.dispose()
            drawEngine = null
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
     * 返回当前的 DrawEngine 实例，可用于配置场景、相机等。
     */
    fun getDrawEngine(): DrawEngine? = drawEngine

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

}
