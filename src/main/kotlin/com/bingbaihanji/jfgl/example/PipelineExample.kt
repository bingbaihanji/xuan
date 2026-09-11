package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.dsl.jfgl

/**
 * 批处理管线的端到端示例：一帧里画满各种图元，用来肉眼确认管线确实出图。
 *
 * <p>覆盖的路径：
 * - 填充矩形（[com.bingbaihanji.jfgl.renderer.Gc.fillRect]）
 * - 填充圆（自适应分段）
 * - 圆角矩形描边（圆角轮廓 + 描边轮廓生成）
 * - 凹多边形填充（耳切三角化）
 * - 三次贝塞尔曲线描边（曲线平坦化）
 *
 * <p>坐标是**像素、原点左上、y 向下**，窗口 800×600。
 *
 * <p>**运行方式**：不要用 `mvn exec:java`。那个插件的类加载器会让 openglfx 链接到另一份
 * `com.sun.prism.GraphicsPipeline`（其静态字段永远为 null），启动时就抛
 * `UnsupportedOperationException: Could not detect pipeline`。
 * 用 fork 出独立 JVM 的方式运行：
 * ```
 * mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
 *     -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PipelineExampleKt"
 * ```
 */
fun main() {
    jfgl {
        title = "JFGL 批处理管线"
        width = 800.0
        height = 600.0

        onRender {
            // 红色矩形
            fill = 0xFFFF0000.toInt()
            fillRect(50f, 50f, 200f, 120f)

            // 绿色圆
            fill = 0xFF00FF00.toInt()
            fillCircle(500f, 200f, 80f)

            // 蓝色圆角矩形描边
            stroke = 0xFF0000FF.toInt()
            lineWidth = 4f
            strokeRect(100f, 300f, 250f, 150f, radius = 16f)

            // 深灰凹多边形（耳切三角化的验证：右侧有一个内凹顶点）
            fill = 0xFF333333.toInt()
            fillPolygon(floatArrayOf(500f, 300f, 700f, 300f, 700f, 450f, 600f, 520f, 500f, 450f))

            // 品红贝塞尔曲线
            stroke = 0xFFFF00FF.toInt()
            lineWidth = 3f
            beginPath()
            moveTo(50f, 550f)
            bezierCurveTo(200f, 450f, 300f, 650f, 450f, 550f)
            strokePath()
        }
    }
}
