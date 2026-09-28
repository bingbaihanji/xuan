package com.bingbaihanji.xuan

import com.bingbaihanji.xuan.example.main as runPipelineExample

fun main(args: Array<String>) {
    System.setProperty("prism.dirtyopts", "true")  // 禁用脏位图优化。 禁用脏区域优化会导致 JavaFX 重新渲染整个场景，从而可能提高质量，但会影响性能。
    System.setProperty("prism.order", "d3d,sw") // 优先硬件加速，失败后回退到软件渲染（推荐）
    System.setProperty("prism.allowhidpi", "true") //允许 JavaFX 在高 DPI 显示器上启用高分辨率渲染
    System.setProperty("prism.forceGPU", "true") // 强制gpu
    System.setProperty("prism.vsync", "true") // # 开启垂直同步，可减少画面撕裂 设置true可以 让帧数比显示器频率更高
    runPipelineExample()
}
