package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.dsl.jfgl

/**
 * 绘制示例 - 展示 JFGL 框架的基本用法
 */
fun main() {
    jfgl {
        title = "JFGL 绘制示例"
        width = 800.0
        height = 600.0

        // 初始化回调
        onInit { draw ->
            println("JFGL 初始化完成！")
        }

        // 渲染回调 - 每帧调用
        onRender {
            // 绘制背景矩形
            drawRect(-1f, -1f, 2f, 2f, r = 0.15f, g = 0.15f, b = 0.2f)

            // 绘制红色圆形
            drawCircle(-0.5f, 0.3f, 0.2f, r = 1f, g = 0f, b = 0f)

            // 绘制绿色矩形
            drawRect(0.2f, 0.1f, 0.4f, 0.3f, r = 0f, g = 1f, b = 0f)

            // 绘制蓝色三角形
            drawTriangle(
                -0.3f, -0.2f,
                0.3f, -0.2f,
                0f, -0.6f,
                r = 0f, g = 0f, b = 1f
            )

            // 绘制黄色椭圆
            drawEllipse(0.6f, -0.4f, 0.2f, 0.15f, r = 1f, g = 1f, b = 0f)

            // 绘制白色线段
            drawLine(-0.8f, -0.8f, 0.8f, -0.8f, r = 1f, g = 1f, b = 1f, lineWidth = 2f)

            // 绘制渐变圆形
            drawCircle(0f, 0f, 0.1f, r = 1f, g = 0.5f, b = 0f, a = 0.8f)
        }

        // 鼠标点击回调
        onClick { x, y, button ->
            println("鼠标点击: x=$x, y=$y, button=$button")
        }

        // 鼠标移动回调
        onMove { x, y ->
            // 可以在这里实现鼠标跟随效果
        }
    }
}
