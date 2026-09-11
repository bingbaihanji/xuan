package com.bingbaihanji.jfgl

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL33.*
import org.lwjgl.glfw.GLFW
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 圆环绘制应用
 * - 左键点击确定圆心
 * - 移动鼠标实时预览圆环大小
 * - 再次左键点击确定半径，完成绘制
 * - 右键撤销上一个圆环
 * - 圆环边缘颜色不断变换，中间透明
 */
class App : Application() {

    private lateinit var glTransfer: FXGLTransfer
    private lateinit var mainView: MainView

    // 着色器程序
    private var shaderProgram: Int = 0

    // 绘制状态
    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var isDrawing: Boolean = false
    private var mouseX: Float = 0f
    private var mouseY: Float = 0f

    // 已完成的圆环列表 (圆心x, 圆心y, 半径)
    private val completedRings = mutableListOf<Triple<Float, Float, Float>>()

    // 动画时间
    private var startTime: Long = System.currentTimeMillis()

    // 圆环宽度
    private val ringWidth: Float = 0.03f

    // 窗口尺寸
    private var windowWidth: Double = 800.0
    private var windowHeight: Double = 600.0

    override fun init() {
        super.init()
    }

    override fun start(stage: Stage) {
        glTransfer = FXGLTransfer().apply {
            // 设置GL初始化回调
            onInit {
                setupShader()
                startTime = System.currentTimeMillis()
            }

            // 设置渲染回调
            onRender {
                drawAll()
            }

            // 设置释放回调
            onDispose {
                cleanupResources()
            }
        }

        mainView = MainView().apply {
            center = glTransfer.createGlFXView()
        }

        val scene = Scene(mainView.createMainView(), windowWidth, windowHeight)

        // 添加鼠标事件处理
        scene.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            handleMouseClick(event)
        }

        scene.addEventHandler(MouseEvent.MOUSE_MOVED) { event ->
            handleMouseMove(event)
        }

        stage.scene = scene
        stage.title = "JFGL - 彩虹圆环绘制"
        stage.show()
    }

    /**
     * 处理鼠标点击事件
     */
    private fun handleMouseClick(event: MouseEvent) {
        // 将屏幕坐标转换为OpenGL坐标 (-1 到 1)
        val glX = ((event.x / windowWidth) * 2 - 1).toFloat()
        val glY = (-(event.y / windowHeight) * 2 + 1).toFloat()

        when (event.button) {
            MouseButton.PRIMARY -> {
                if (!isDrawing) {
                    // 第一次点击：设置圆心
                    centerX = glX
                    centerY = glY
                    isDrawing = true
                    println("圆心已设置: ($centerX, $centerY)")
                } else {
                    // 第二次点击：计算半径并完成圆环
                    val dx = glX - centerX
                    val dy = glY - centerY
                    val radius = sqrt(dx * dx + dy * dy)
                    completedRings.add(Triple(centerX, centerY, radius))
                    isDrawing = false
                    println("圆环已创建: 圆心=($centerX, $centerY), 半径=$radius")
                }
            }
            MouseButton.SECONDARY -> {
                // 右键撤销
                if (isDrawing) {
                    isDrawing = false
                    println("取消绘制")
                } else if (completedRings.isNotEmpty()) {
                    val removed = completedRings.removeAt(completedRings.size - 1)
                    println("撤销圆环: 圆心=(${removed.first}, ${removed.second}), 半径=${removed.third}")
                }
            }
            else -> {}
        }

        // 触发重绘
        glTransfer.repaint()
    }

    /**
     * 处理鼠标移动事件
     */
    private fun handleMouseMove(event: MouseEvent) {
        if (isDrawing) {
            // 将屏幕坐标转换为OpenGL坐标
            mouseX = ((event.x / windowWidth) * 2 - 1).toFloat()
            mouseY = (-(event.y / windowHeight) * 2 + 1).toFloat()
            // 触发重绘
            glTransfer.repaint()
        }
    }

    /**
     * 设置着色器程序
     */
    private fun setupShader() {
        shaderProgram = glCreateProgram()
        val vertexShader = glCreateShader(GL_VERTEX_SHADER)
        glShaderSource(vertexShader, """
            #version 330 core
            layout (location = 0) in vec2 aPosition;
            layout (location = 1) in vec4 aColor;
            out vec4 vColor;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vColor = aColor;
            }
        """.trimIndent())
        glCompileShader(vertexShader)

        val fragmentShader = glCreateShader(GL_FRAGMENT_SHADER)
        glShaderSource(fragmentShader, """
            #version 330 core
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vColor;
            }
        """.trimIndent())
        glCompileShader(fragmentShader)

        glAttachShader(shaderProgram, vertexShader)
        glAttachShader(shaderProgram, fragmentShader)
        glLinkProgram(shaderProgram)

        // 着色器已链接，可以删除
        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)
    }

    /**
     * 绘制所有圆环
     */
    private fun drawAll() {
        if (shaderProgram == 0) return

        // 计算动画时间（秒）
        val currentTime = (System.currentTimeMillis() - startTime) / 1000.0f

        glUseProgram(shaderProgram)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        // 绘制已完成的圆环
        for ((cx, cy, radius) in completedRings) {
            drawRing(cx, cy, radius, currentTime)
        }

        // 绘制预览圆环（正在绘制中）
        if (isDrawing) {
            val dx = mouseX - centerX
            val dy = mouseY - centerY
            val radius = sqrt(dx * dx + dy * dy)
            drawRing(centerX, centerY, radius, currentTime)
        }

        glUseProgram(0)
    }

    /**
     * 清理OpenGL资源
     */
    private fun cleanupResources() {
        if (shaderProgram != 0) {
            glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
    }

    /**
     * 绘制单个圆环（边缘颜色不断变化，中间透明）
     *
     * @param cx 圆心 x 坐标
     * @param cy 圆心 y 坐标
     * @param radius 外半径
     * @param time 当前时间（用于动画）
     */
    private fun drawRing(cx: Float, cy: Float, radius: Float, time: Float) {
        val segments = 128
        val innerRadius = radius - ringWidth

        // 如果内半径小于0，设置为0
        val actualInnerRadius = if (innerRadius < 0) 0f else innerRadius

        val vertices = mutableListOf<Float>()

        // 使用 TRIANGLE_STRIP 绘制圆环
        // 交替添加外圆和内圆上的点
        for (i in 0..segments) {
            val angle = (2.0f * Math.PI.toFloat() * i) / segments

            // 计算彩虹色（随角度和时间变化）
            val hue = (angle / (2.0f * Math.PI.toFloat()) + time * 0.5f) % 1.0f
            val color = hsvToRgb(hue, 1.0f, 1.0f)

            // 外圆上的点
            val outerX = cx + radius * cos(angle)
            val outerY = cy + radius * sin(angle)
            vertices.addAll(listOf(outerX, outerY, color[0], color[1], color[2], 1.0f))

            // 内圆上的点（相同的颜色，但可以设置为半透明）
            val innerX = cx + actualInnerRadius * cos(angle)
            val innerY = cy + actualInnerRadius * sin(angle)
            vertices.addAll(listOf(innerX, innerY, color[0], color[1], color[2], 0.0f))
        }

        val vertexArray = vertices.toFloatArray()
        val vertexCount = (segments + 1) * 2

        // 创建临时VAO和VBO
        val vao = glGenVertexArrays()
        val vbo = glGenBuffers()

        glBindVertexArray(vao)

        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertexArray, GL_DYNAMIC_DRAW)

        // 位置属性
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 6 * 4, 0)
        glEnableVertexAttribArray(0)

        // 颜色属性
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 6 * 4, (2 * 4).toLong())
        glEnableVertexAttribArray(1)

        // 绘制圆环
        glDrawArrays(GL_TRIANGLE_STRIP, 0, vertexCount)

        // 清理临时资源
        glBindVertexArray(0)
        glDeleteVertexArrays(vao)
        glDeleteBuffers(vbo)
    }

    /**
     * HSV 转 RGB 颜色
     *
     * @param h 色相 (0-1)
     * @param s 饱和度 (0-1)
     * @param v 明度 (0-1)
     * @return RGB 颜色数组 [r, g, b]，每个分量范围 0-1
     */
    private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val i = (h * 6).toInt()
        val f = h * 6 - i
        val p = v * (1 - s)
        val q = v * (1 - f * s)
        val t = v * (1 - (1 - f) * s)

        return when (i % 6) {
            0 -> floatArrayOf(v, t, p)
            1 -> floatArrayOf(q, v, p)
            2 -> floatArrayOf(p, v, t)
            3 -> floatArrayOf(p, q, v)
            4 -> floatArrayOf(t, p, v)
            5 -> floatArrayOf(v, p, q)
            else -> floatArrayOf(v, p, q)
        }
    }

    override fun stop() {
        super.stop()
        // 注意：不要在这里清理OpenGL资源，因为上下文可能已经失效
        glTransfer.dispose()
    }
}
