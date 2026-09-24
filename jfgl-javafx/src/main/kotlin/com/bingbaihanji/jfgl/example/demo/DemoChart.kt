package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.chart.ChartType

/**
 * 图表模式。
 *
 * <p>本文件目前只有**菜单词汇**——"有哪几种图型"。菜单栏是 [JfglDemo] 建的，
 * 它需要这份清单；而图表的装配与绘制（`build` / `chart` / `draw`）是后续任务的事。
 *
 * <p>**不把这份清单放进 `JfglDemo.kt`** 的理由：它是"图表"这个概念的东西，
 * 而那个文件已经有窗口 / 菜单 / 事件三件事了。
 */
internal object DemoChart {

    /** 菜单里的图型。第二项是 [ChartType]。 */
    val KINDS: List<Pair<String, ChartType>> = listOf(
        "折线" to ChartType.LINE,
        "柱状" to ChartType.BAR,
        "面积" to ChartType.AREA,
        "散点" to ChartType.SCATTER
    )

    /** 当前选中的图型下标（菜单在 JavaFX 线程写，绘制在 GL 线程读）。 */
    @Volatile
    var selectedKind: Int = 0
}
