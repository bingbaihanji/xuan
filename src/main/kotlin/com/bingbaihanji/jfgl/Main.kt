package com.bingbaihanji.jfgl

import javafx.application.Application

fun main(args: Array<String>) {
    System.setProperty("prism.dirtyopts", "true")  // 禁用脏位图优化。 禁用脏区域优化会导致 JavaFX 重新渲染整个场景，从而可能提高质量，但会影响性能。
    System.setProperty("prism.order", "d3d,sw") // 优先硬件加速，失败后回退到软件渲染（推荐）
    System.setProperty("prism.allowhidpi", "true") //允许 JavaFX 在高 DPI 显示器上启用高分辨率渲染
    System.setProperty("prism.forceGPU", "true") // 强制gpu
    System.setProperty("prism.verbose", "true") //  JavaFX 提供了调试模式，可以输出更详细的日志
    System.setProperty("prism.renderquality", "base") // # 控制渲染质量，设置为 best 可以提高渲染的质量，进而改善抗锯齿效果 [low medium high best]
    System.setProperty("prism.vsync", "true") // # 开启垂直同步，可减少画面撕裂 设置true可以 让帧数比显示器频率更高
    Application.launch(App::class.java, *args)
}
