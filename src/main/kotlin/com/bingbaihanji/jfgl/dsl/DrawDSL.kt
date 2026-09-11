package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL33.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * 绘制工具 DSL，提供简洁的 API 来绘制各种图形。
 */
class DrawDSL(private val transfer: FXGLTransfer) {

    // 着色器程序
    private var shaderProgram: Int = 0
    private var isInitialized = false

    /**
     * 初始化绘制环境（在 GL 上下文中调用）
     */
    fun init() {
        if (isInitialized) return

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

        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)

        isInitialized = true
    }

    /**
     * 绘制圆形
     *
     * @param cx 圆心 x 坐标（OpenGL 坐标，-1 到 1）
     * @param cy 圆心 y 坐标（OpenGL 坐标，-1 到 1）
     * @param radius 半径
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     * @param segments 线段数（越大越平滑）
     */
    fun drawCircle(cx: Float, cy: Float, radius: Float,
                   r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
                   segments: Int = 64) {
        if (!isInitialized) return

        val vertices = mutableListOf<Float>()
        vertices.addAll(listOf(cx, cy, r, g, b, a))

        for (i in 0..segments) {
            val angle = (2.0f * Math.PI.toFloat() * i) / segments
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            vertices.addAll(listOf(x, y, r, g, b, a))
        }

        drawPrimitive(GL_TRIANGLE_FAN, vertices)
    }

    /**
     * 绘制矩形
     *
     * @param x 左上角 x 坐标
     * @param y 左上角 y 坐标
     * @param width 宽度
     * @param height 高度
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     */
    fun drawRect(x: Float, y: Float, width: Float, height: Float,
                 r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        if (!isInitialized) return

        val vertices = listOf(
            x, y, r, g, b, a,
            x + width, y, r, g, b, a,
            x + width, y + height, r, g, b, a,
            x, y, r, g, b, a,
            x + width, y + height, r, g, b, a,
            x, y + height, r, g, b, a
        )

        drawPrimitive(GL_TRIANGLES, vertices)
    }

    /**
     * 绘制线段
     *
     * @param x1 起点 x 坐标
     * @param y1 起点 y 坐标
     * @param x2 终点 x 坐标
     * @param y2 终点 y 坐标
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     * @param lineWidth 线宽
     */
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float,
                 r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
                 lineWidth: Float = 1f) {
        if (!isInitialized) return

        glLineWidth(lineWidth)
        val vertices = listOf(
            x1, y1, r, g, b, a,
            x2, y2, r, g, b, a
        )

        drawPrimitive(GL_LINES, vertices)
        glLineWidth(1f)
    }

    /**
     * 绘制三角形
     *
     * @param x1 第一个顶点 x 坐标
     * @param y1 第一个顶点 y 坐标
     * @param x2 第二个顶点 x 坐标
     * @param y2 第二个顶点 y 坐标
     * @param x3 第三个顶点 x 坐标
     * @param y3 第三个顶点 y 坐标
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     */
    fun drawTriangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
                     r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        if (!isInitialized) return

        val vertices = listOf(
            x1, y1, r, g, b, a,
            x2, y2, r, g, b, a,
            x3, y3, r, g, b, a
        )

        drawPrimitive(GL_TRIANGLES, vertices)
    }

    /**
     * 绘制多边形
     *
     * @param points 顶点列表，每两个元素表示一个点的 x, y 坐标
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     */
    fun drawPolygon(points: List<Float>, r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        if (!isInitialized || points.size < 6) return

        val vertices = mutableListOf<Float>()
        // 使用三角形扇形绘制多边形
        val centerX = points.chunked(2).map { it[0] }.average().toFloat()
        val centerY = points.chunked(2).map { it[1] }.average().toFloat()

        for (i in points.indices step 2) {
            // 中心点
            vertices.addAll(listOf(centerX, centerY, r, g, b, a))
            // 当前点
            vertices.addAll(listOf(points[i], points[i + 1], r, g, b, a))
            // 下一个点
            val nextI = (i + 2) % points.size
            vertices.addAll(listOf(points[nextI], points[nextI + 1], r, g, b, a))
        }

        drawPrimitive(GL_TRIANGLES, vertices)
    }

    /**
     * 绘制椭圆
     *
     * @param cx 中心 x 坐标
     * @param cy 中心 y 坐标
     * @param radiusX x 方向半径
     * @param radiusY y 方向半径
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     * @param segments 线段数
     */
    fun drawEllipse(cx: Float, cy: Float, radiusX: Float, radiusY: Float,
                    r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
                    segments: Int = 64) {
        if (!isInitialized) return

        val vertices = mutableListOf<Float>()
        vertices.addAll(listOf(cx, cy, r, g, b, a))

        for (i in 0..segments) {
            val angle = (2.0f * Math.PI.toFloat() * i) / segments
            val x = cx + radiusX * cos(angle)
            val y = cy + radiusY * sin(angle)
            vertices.addAll(listOf(x, y, r, g, b, a))
        }

        drawPrimitive(GL_TRIANGLE_FAN, vertices)
    }

    /**
     * 绘制弧线
     *
     * @param cx 中心 x 坐标
     * @param cy 中心 y 坐标
     * @param radius 半径
     * @param startAngle 起始角度（弧度）
     * @param endAngle 结束角度（弧度）
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a alpha 分量（0-1）
     * @param segments 线段数
     */
    fun drawArc(cx: Float, cy: Float, radius: Float,
                startAngle: Float, endAngle: Float,
                r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
                segments: Int = 32) {
        if (!isInitialized) return

        val vertices = mutableListOf<Float>()
        vertices.addAll(listOf(cx, cy, r, g, b, a))

        val angleRange = endAngle - startAngle
        for (i in 0..segments) {
            val angle = startAngle + (angleRange * i) / segments
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            vertices.addAll(listOf(x, y, r, g, b, a))
        }

        drawPrimitive(GL_TRIANGLE_FAN, vertices)
    }

    /**
     * 内部绘制方法
     */
    private fun drawPrimitive(mode: Int, vertices: List<Float>) {
        val vertexArray = vertices.toFloatArray()
        val vertexCount = vertices.size / 6

        val vao = glGenVertexArrays()
        val vbo = glGenBuffers()

        glBindVertexArray(vao)

        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertexArray, GL_DYNAMIC_DRAW)

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 6 * 4, 0)
        glEnableVertexAttribArray(0)

        glVertexAttribPointer(1, 4, GL_FLOAT, false, 6 * 4, (2 * 4).toLong())
        glEnableVertexAttribArray(1)

        glUseProgram(shaderProgram)
        glDrawArrays(mode, 0, vertexCount)
        glUseProgram(0)

        glBindVertexArray(0)
        glDeleteVertexArrays(vao)
        glDeleteBuffers(vbo)
    }

    /**
     * 清理资源
     */
    fun dispose() {
        if (shaderProgram != 0) {
            glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        isInitialized = false
    }
}
