package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.chart.*
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.util.Rect

/** 绘图区底色 / 网格线 / 刻度文字 / 数据线。 */
private const val PLOT_BG = 0xFF1A1D22.toInt()
private const val GRID = 0xFF3A3F47.toInt()
private const val AXIS_LINE = 0xFF6B727C.toInt()
private const val TICK_TEXT = 0xFFC0C6CF.toInt()

/** 两条系列的配色。 */
private const val COLOR_SALES = 0xFF4FC3F7.toInt()
private const val COLOR_COST = 0xFFFF8A65.toInt()

/** 12 个月。 */
private val MONTHS = arrayOf(
    "1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"
)

/**
 * 合成数据：销售额与成本（万元）。
 *
 * <p>**写死，不用 Random**——"这一帧画对了没有"要能复核。随机数据让每一次运行都是新画面，
 * 出了偏差也说不清是渲染错了还是数据本来如此。
 */
private val SALES = doubleArrayOf(42.0, 48.0, 51.0, 47.0, 62.0, 75.0, 81.0, 78.0, 88.0, 95.0, 102.0, 118.0)
private val COST = doubleArrayOf(31.0, 35.0, 39.0, 41.0, 46.0, 52.0, 55.0, 58.0, 61.0, 64.0, 70.0, 76.0)

/**
 * 图表模式的装配与绘制。
 *
 * <p>**坐标轴装配与绘制是两件事**：[build] 造出 [Chart]（纯计算，任意线程可调），
 * [draw] 每帧在 GL 线程上画。而 `Axis.setDisplayLength` 必须**每帧按当前绘图区重设**
 * ——窗口一缩放绘图区就变，刻度位置就跟着错开，而那看起来像数据本身的问题。
 */
internal object DemoChart {

    /** 菜单里的图型。第二项是 [ChartType]；`AREA` / `BAR` 需要额外的样式参数，见 [build]。 */
    val KINDS: List<Pair<String, ChartType>> = listOf(
        "折线" to ChartType.LINE,
        "柱状" to ChartType.BAR,
        "面积" to ChartType.AREA,
        "散点" to ChartType.SCATTER
    )

    /** 当前选中的图型下标（菜单在 JavaFX 线程写，绘制在 GL 线程读）。 */
    @Volatile
    var selectedKind: Int = 0

    /**
     * 已建好的图表与它的图型下标。
     *
     * <p><strong>★ 图表必须缓存，不能每帧 `build()`。</strong>
     * `ChartRenderer` 用 `IdentityHashMap` 按 **`Series` 对象身份**缓存每个系列的 GPU 缓冲
     * 与拾取号（`ChartRenderer.java` 的 `buffers.computeIfAbsent(series, …)` 与
     * `pickIds.computeIfAbsent(series, pickRegistry::register)`），而这两个 map
     * **只在 `ChartRenderer.dispose()` 里清空，没有任何"这个系列不再画了"的回收接口**。
     *
     * <p>于是每帧新建 `Series` 的后果是：**每帧泄漏一块 GPU 缓冲**（显存不回），
     * 并且**每帧消耗两个拾取号**。耗尽时 `PickRegistry` 会抛异常——而 GL 线程上的异常
     * 在本项目是**静默吞掉**的，所以表现是"图表忽然不画了，没有任何报错"。
     * 这正是本仓库头号敌人的那类缺陷，而在画面上完全看不出来（前几百帧一切正常）。
     *
     * <p>缓存之后仍然有**有界**的泄漏：每切换一次图型就多留两个 `Series` 的缓冲与号
     * （菜单只有四项，所以上限是 8 个）。要根治需要 `ChartRenderer` 提供
     * "释放这些系列"的接口，那是扩库的 API 面——记进 `CLAUDE.md` 的待办，不在本 demo 里做。
     */
    private var cachedChart: Chart? = null
    private var cachedKind: Int = -1

    /**
     * 取当前图型的图表；图型没变就复用**同一个** [Chart] 实例（理由见 [cachedChart]）。
     *
     * <p>**只在 GL 线程调用。**
     */
    fun chart(): Chart {
        val k = selectedKind
        val existing = cachedChart
        if (existing != null && cachedKind == k) {
            return existing
        }
        val fresh = build(k)
        cachedChart = fresh
        cachedKind = k
        return fresh
    }

    /**
     * 造出图表（纯计算）。**不持有 GL 资源**，所以可以随便什么时候调——但**别每帧调**，
     * 理由见 [cachedChart]。
     *
     * @param kindIndex [KINDS] 里的下标
     */
    private fun build(kindIndex: Int): Chart {
        val data = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (MONTHS.size - 1).toDouble(), "月份", ""),
                AxisRange(0.0, 130.0, "金额", "万元")
            ),
            arrayOf(
                DoubleArray(MONTHS.size) { it.toDouble() },   // 维度 0 = x（数据下标）
                SALES.copyOf()                                // 维度 1 = y（销售额）
            )
        )
        val data2 = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (MONTHS.size - 1).toDouble(), "月份", ""),
                AxisRange(0.0, 130.0, "金额", "万元")
            ),
            arrayOf(DoubleArray(MONTHS.size) { it.toDouble() }, COST.copyOf())
        )

        val type = KINDS[kindIndex].second
        val x = Axis(AxisType.LINEAR, data.axisRange(0))
        val y = Axis(AxisType.LINEAR, data.axisRange(1))

        return Chart(x, y)
            .title("月度销售与成本（合成数据）")
            .titleFontSize(16f)
            .legendSide(ChartSide.BOTTOM)
            .axisTitlesVisible(true)
            .axisTitleFontSize(12f)
            .tickLabelReserve(ChartSide.BOTTOM, 18f)
            .tickLabelReserve(ChartSide.LEFT, 18f)
            .padding(ChartInsets(8f, 8f, 8f, 8f))
            .apply {
                addLayer("数据").add(styleFor(Series("销售额", data, type).color(COLOR_SALES)))
                    .add(styleFor(Series("成本", data2, type).color(COLOR_COST)))
            }
    }

    /**
     * 图型各自的样式。
     *
     * <p>**两种图型天然要求不同的装配**，不能只换 [ChartType]：柱状与面积要用 `baseline`，
     * 面积还要 `fillAlpha`。只换 type 的话柱状会以 0 为基线（默认就是 0，正好对），
     * 而面积的填充透明度会是默认的 0.5——这里显式写出来，是为了让"哪些是刻意的"可见。
     *
     * <p>`Series` 的样式 setter **拒绝 NaN / ±Infinity**（抛 `IllegalArgumentException`），
     * 所以下面的常量都必须是有限数。
     */
    private fun styleFor(s: Series): Series = when (s.type()) {
        ChartType.BAR -> s.baseline(0f).categoryGap(0.25f).barGap(0.2f)
        ChartType.AREA -> s.baseline(0f).fillAlpha(0.35f)
        ChartType.SCATTER -> s.markerSize(4f)
        else -> s.lineWidth(2f)
    }

    /**
     * 画一帧图表。
     *
     * <p>顺序是硬约束，见设计文档 §7：**算布局 → 设 displayLength → 网格 → flush → drawChart → 刻度**。
     */
    fun draw(gc: Gc) {
        val frame = Rect(40f, 30f, gc.width - 80f, gc.height - 80f)
        if (frame.width < 80f || frame.height < 80f) return   // 窗口太小，排不下

        val chart = chart()          // ★ 取缓存的，**不是** build()——见 cachedChart 的说明
        val metrics = GcTextMetrics(gc)
        val layout = ChartLayout.compute(chart, frame, metrics)
        val plot = layout.plotRect()

        // ★ 顺序约束：displayLength 必须等布局算完，且**每帧重设**（窗口缩放会改绘图区尺寸）
        chart.axis(0).setDisplayLength(plot.width.toDouble())
        chart.axis(1).setDisplayLength(plot.height.toDouble())

        // 绘图区底色
        gc.fill = PLOT_BG
        gc.fillRect(plot.x, plot.y, plot.width, plot.height)

        // 网格。**库没有辅助**，全仓唯一一份手写循环在 README.md
        gc.save()
        gc.lineWidth = 1f
        for (t in chart.axis(1).ticks()) {         // y 轴要翻：值越大越靠上
            if (!t.isMajor()) continue
            val sy = plot.y + plot.height - t.position().toFloat()
            gc.stroke = GRID
            gc.drawLine(plot.x, sy, plot.x + plot.width, sy)
        }
        for (t in chart.axis(0).ticks()) {
            if (!t.isMajor()) continue
            val sx = plot.x + t.position().toFloat()
            if (t.value() == 0.0) continue          // 0 那条竖线让给 y 轴
            gc.stroke = GRID
            gc.drawLine(sx, plot.y, sx, plot.y + plot.height)
        }
        gc.restore()

        // ★ 网格落定。数据系列是当场就画的，不 flush 就没有"网格 → 数据 → 标注"的夹心 z 序
        gc.flush()

        // 装饰（标题 / 图例 / 轴标题）+ 数据系列。**不能带着变换调用**（会抛）。
        gc.charts.drawChart(chart, frame, gc.width, gc.height)

        // 刻度文字画在数据之上。只有主刻度的 label 非空，中/次是空串。
        gc.save()
        gc.fontSize = 12f
        gc.fill = TICK_TEXT
        for (t in chart.axis(1).ticks()) {
            if (!t.isMajor()) continue
            val sy = plot.y + plot.height - t.position().toFloat()
            val w = gc.measureText(t.label())
            gc.drawText(t.label(), plot.x - w - 6f, sy + 4f)     // y 是基线，+4 让文字垂直居中于刻度
        }
        for (t in chart.axis(0).ticks()) {
            if (!t.isMajor()) continue
            val sx = plot.x + t.position().toFloat()
            val w = gc.measureText(t.label())
            gc.drawText(t.label(), sx - w / 2f, plot.y + plot.height + 16f)
        }
        gc.restore()
    }
}

/**
 * 给 [ChartLayout] 用的文字度量。
 *
 * <p>**为什么 demo 要自己实现**：`Gc` 内部有一支口径完全相同的笔，但它是 private 的、
 * 且 `Gc` 本身不实现 [ChartTextMetrics]。这是本 demo 记录的一条缺口（见设计文档 §6.3）。
 *
 * <p>**`lineHeight` 必须是 `字号 × ChartLayout.LINE_HEIGHT_FACTOR`，不是字体的真实行高**
 * ——布局要能被精确预测，读真实 ascent 会让"同一个外框 + 同一个配置"在不同字体下
 * 给出不同的绘图区。重写歪了的症状是"网格与标题带对不上"，而画面只是"看着有点挤"。
 */
private class GcTextMetrics(private val gc: Gc) : ChartTextMetrics {
    override fun width(text: String, fontSize: Float): Float {
        val saved = gc.fontSize
        gc.fontSize = fontSize
        val w = gc.measureText(text)
        gc.fontSize = saved
        return w
    }

    override fun lineHeight(fontSize: Float): Float = fontSize * ChartLayout.LINE_HEIGHT_FACTOR
}
