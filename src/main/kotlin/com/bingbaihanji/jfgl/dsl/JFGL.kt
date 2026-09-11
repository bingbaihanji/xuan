package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage

/**
 * JFGL 绘图框架的 DSL 入口。
 *
 * 使用示例：
 * ```kotlin
 * jfgl {
 *     title = "我的应用"
 *     width = 800.0
 *     height = 600.0
 *
 *     onInit {
 *         // 初始化代码
 *     }
 *
 *     onRender {
 *         // 绘制代码
 *         drawCircle(0f, 0f, 0.5f, r = 1f, g = 0f, b = 0f)
 *         drawRect(-0.5f, -0.5f, 0.3f, 0.3f, r = 0f, g = 1f, b = 0f)
 *     }
 *
 *     onClick { x, y, button ->
 *         // 鼠标点击处理
 *     }
 *
 *     onMove { x, y ->
 *         // 鼠标移动处理
 *     }
 * }
 * ```
 */
class JFGL {
    /** 窗口标题 */
    var title: String = "JFGL 应用"

    /** 窗口宽度 */
    var width: Double = 800.0

    /** 窗口高度 */
    var height: Double = 600.0

    /** 清屏颜色（RGBA，0-1） */
    var clearColor: FloatArray = floatArrayOf(0.2f, 0.2f, 0.2f, 1.0f)

    // 回调函数
    private var onInitCallback: ((DrawDSL) -> Unit)? = null
    private var onRenderCallback: (DrawDSL.() -> Unit)? = null
    private var onClickCallback: ((Float, Float, MouseButton) -> Unit)? = null
    private var onMoveCallback: ((Float, Float) -> Unit)? = null

    // 绘制工具
    private var drawDSL: DrawDSL? = null
    private var transfer: FXGLTransfer? = null

    /**
     * 设置初始化回调
     */
    fun onInit(block: (DrawDSL) -> Unit) {
        onInitCallback = block
    }

    /**
     * 设置渲染回调
     */
    fun onRender(block: DrawDSL.() -> Unit) {
        onRenderCallback = block
    }

    /**
     * 设置鼠标点击回调
     */
    fun onClick(block: (Float, Float, MouseButton) -> Unit) {
        onClickCallback = block
    }

    /**
     * 设置鼠标移动回调
     */
    fun onMove(block: (Float, Float) -> Unit) {
        onMoveCallback = block
    }

    /**
     * 获取绘制工具
     */
    fun getDraw(): DrawDSL? = drawDSL

    /**
     * 启动应用
     */
    fun start() {
        transfer = FXGLTransfer().apply {
            onInit {
                drawDSL = DrawDSL(this).apply { init() }
                onInitCallback?.invoke(drawDSL!!)
            }

            onRender {
                drawDSL?.let { draw ->
                    onRenderCallback?.invoke(draw)
                }
            }

            onDispose {
                drawDSL?.dispose()
            }
        }

        // 启动 JavaFX 应用
        JFGLApplication.title = title
        JFGLApplication.width = width
        JFGLApplication.height = height
        JFGLApplication.transfer = transfer
        JFGLApplication.onClickCallback = onClickCallback
        JFGLApplication.onMoveCallback = onMoveCallback

        Application.launch(JFGLApplication::class.java)
    }
}

/**
 * JavaFX 应用程序类
 */
class JFGLApplication : Application() {
    companion object {
        var title: String = "JFGL 应用"
        var width: Double = 800.0
        var height: Double = 600.0
        var transfer: FXGLTransfer? = null
        var onClickCallback: ((Float, Float, MouseButton) -> Unit)? = null
        var onMoveCallback: ((Float, Float) -> Unit)? = null
    }

    override fun start(stage: Stage) {
        val mainView = com.bingbaihanji.jfgl.view.MainView().apply {
            center = transfer!!.createGlFXView()
        }

        val scene = Scene(mainView.createMainView(), width, height)

        // 鼠标点击事件
        scene.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            val glX = ((event.x / width) * 2 - 1).toFloat()
            val glY = (-(event.y / height) * 2 + 1).toFloat()
            onClickCallback?.invoke(glX, glY, event.button)
            transfer?.repaint()
        }

        // 鼠标移动事件
        scene.addEventHandler(MouseEvent.MOUSE_MOVED) { event ->
            val glX = ((event.x / width) * 2 - 1).toFloat()
            val glY = (-(event.y / height) * 2 + 1).toFloat()
            onMoveCallback?.invoke(glX, glY)
        }

        stage.title = title
        stage.scene = scene
        stage.show()
    }

    override fun stop() {
        transfer?.dispose()
    }
}

/**
 * JFGL DSL 入口函数
 *
 * 使用示例：
 * ```kotlin
 * fun main() {
 *     jfgl {
 *         title = "我的应用"
 *
 *         onRender {
 *             drawCircle(0f, 0f, 0.5f)
 *         }
 *     }
 * }
 * ```
 */
fun jfgl(block: JFGL.() -> Unit) {
    val jfgl = JFGL()
    jfgl.block()
    jfgl.start()
}
