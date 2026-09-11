package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.chart.BarChart
import com.bingbaihanji.jfgl.chart.Chart.ChartSeries
import com.bingbaihanji.jfgl.chart.LineChart
import com.bingbaihanji.jfgl.chart.PieChart
import com.bingbaihanji.jfgl.chart.ScatterChart
import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.util.Color
import com.bingbaihanji.jfgl.util.Rect

// ---------------------------------------------------------------------------
// DrawEngine extension functions for chart creation
// ---------------------------------------------------------------------------

/**
 * Creates and configures a [LineChart] on this [DrawEngine].
 *
 * ```kotlin
 * engine.lineChart(50f, 50f, 600f, 400f) {
 *     title = "Monthly Sales"
 *     xAxisLabel = "Month"
 *     yAxisLabel = "Revenue ($)"
 *     series("Product A", Color.RED) {
 *         data(1.0, 10.0)
 *         data(2.0, 25.0)
 *         data(3.0, 18.0)
 *     }
 *     series("Product B", Color.BLUE) {
 *         data(1.0, 8.0)
 *         data(2.0, 20.0)
 *         data(3.0, 22.0)
 *     }
 * }
 * ```
 *
 * @param x      left edge x-coordinate of the chart area
 * @param y      top edge y-coordinate of the chart area
 * @param width  width of the chart area
 * @param height height of the chart area
 * @param block  DSL block to configure the chart
 * @return the configured [LineChart]
 */
fun DrawEngine.lineChart(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    block: LineChartBuilder.() -> Unit
): LineChart {
    val builder = LineChartBuilder(this, Rect(x, y, width, height))
    builder.block()
    return builder.build()
}

/**
 * Int overload for [lineChart].
 */
fun DrawEngine.lineChart(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    block: LineChartBuilder.() -> Unit
): LineChart = lineChart(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), block)

/**
 * Creates and configures a [BarChart] on this [DrawEngine].
 *
 * ```kotlin
 * engine.barChart(50f, 50f, 600f, 400f) {
 *     title = "Quarterly Results"
 *     xAxisLabel = "Quarter"
 *     yAxisLabel = "Profit"
 *     series("2024", Color.GREEN) {
 *         data(1.0, 120.0)
 *         data(2.0, 150.0)
 *         data(3.0, 130.0)
 *         data(4.0, 170.0)
 *     }
 * }
 * ```
 *
 * @param x      left edge x-coordinate of the chart area
 * @param y      top edge y-coordinate of the chart area
 * @param width  width of the chart area
 * @param height height of the chart area
 * @param block  DSL block to configure the chart
 * @return the configured [BarChart]
 */
fun DrawEngine.barChart(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    block: BarChartBuilder.() -> Unit
): BarChart {
    val builder = BarChartBuilder(this, Rect(x, y, width, height))
    builder.block()
    return builder.build()
}

/**
 * Int overload for [barChart].
 */
fun DrawEngine.barChart(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    block: BarChartBuilder.() -> Unit
): BarChart = barChart(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), block)

/**
 * Creates and configures a [PieChart] on this [DrawEngine].
 *
 * ```kotlin
 * engine.pieChart(400f, 300f, 200f) {
 *     title = "Market Share"
 *     series("Browser Share", Color.RED) {
 *         data(0.0, 45.0)   // Chrome
 *         data(1.0, 25.0)   // Firefox
 *         data(2.0, 18.0)   // Safari
 *         data(3.0, 12.0)   // Other
 *     }
 * }
 * ```
 *
 * @param cx     center x-coordinate of the pie chart
 * @param cy     center y-coordinate of the pie chart
 * @param radius radius of the pie chart
 * @param block  DSL block to configure the chart
 * @return the configured [PieChart]
 */
fun DrawEngine.pieChart(
    cx: Float,
    cy: Float,
    radius: Float,
    block: PieChartBuilder.() -> Unit
): PieChart {
    val builder = PieChartBuilder(this, cx, cy, radius)
    builder.block()
    return builder.build()
}

/**
 * Int overload for [pieChart].
 */
fun DrawEngine.pieChart(
    cx: Int,
    cy: Int,
    radius: Int,
    block: PieChartBuilder.() -> Unit
): PieChart = pieChart(cx.toFloat(), cy.toFloat(), radius.toFloat(), block)

/**
 * Creates and configures a [ScatterChart] on this [DrawEngine].
 *
 * ```kotlin
 * engine.scatterChart(50f, 50f, 600f, 400f) {
 *     title = "Height vs Weight"
 *     xAxisLabel = "Height (cm)"
 *     yAxisLabel = "Weight (kg)"
 *     series("Male", Color.BLUE) {
 *         data(170.0, 68.0)
 *         data(175.0, 75.0)
 *         data(180.0, 82.0)
 *     }
 *     series("Female", Color.RED) {
 *         data(160.0, 55.0)
 *         data(165.0, 60.0)
 *         data(170.0, 65.0)
 *     }
 * }
 * ```
 *
 * @param x      left edge x-coordinate of the chart area
 * @param y      top edge y-coordinate of the chart area
 * @param width  width of the chart area
 * @param height height of the chart area
 * @param block  DSL block to configure the chart
 * @return the configured [ScatterChart]
 */
fun DrawEngine.scatterChart(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    block: ScatterChartBuilder.() -> Unit
): ScatterChart {
    val builder = ScatterChartBuilder(this, Rect(x, y, width, height))
    builder.block()
    return builder.build()
}

/**
 * Int overload for [scatterChart].
 */
fun DrawEngine.scatterChart(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    block: ScatterChartBuilder.() -> Unit
): ScatterChart = scatterChart(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), block)

// ---------------------------------------------------------------------------
// ChartSeriesBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a single [ChartSeries].
 *
 * Accumulates data points and configures series metadata such as name and
 * color. Used inside chart builders' [series] methods.
 */
class ChartSeriesBuilder internal constructor(
    private val name: String,
    private val color: Color
) {

    private val dataPoints = mutableListOf<DoubleArray>()

    /**
     * Adds a single data point with x and y values.
     *
     * @param x the x-axis value
     * @param y the y-axis value
     */
    fun data(x: Double, y: Double) {
        dataPoints.add(doubleArrayOf(x, y))
    }

    /**
     * Adds a single data point with x and y values (Int overload).
     */
    fun data(x: Int, y: Int) {
        data(x.toDouble(), y.toDouble())
    }

    /**
     * Adds multiple data points from a list of pairs.
     *
     * @param points list of (x, y) pairs
     */
    fun data(points: List<Pair<Double, Double>>) {
        points.forEach { (x, y) -> data(x, y) }
    }

    /**
     * Adds multiple data points from vararg pairs.
     *
     * @param points vararg of (x, y) pairs
     */
    fun data(vararg points: Pair<Double, Double>) {
        points.forEach { (x, y) -> data(x, y) }
    }

    /**
     * Builds and returns the configured [ChartSeries].
     */
    internal fun build(): ChartSeries = ChartSeries(name, dataPoints, color)
}

// ---------------------------------------------------------------------------
// BaseChartBuilder (shared configuration)
// ---------------------------------------------------------------------------

/**
 * Abstract base builder providing shared chart configuration such as title
 * and axis labels. Concrete chart builders extend this class.
 *
 * @param T the concrete chart type being built
 */
abstract class BaseChartBuilder<T>(
    protected val engine: DrawEngine
) {

    /** The chart title displayed at the top. */
    var title: String? = null

    /** The label for the x-axis. */
    var xAxisLabel: String? = null

    /** The label for the y-axis. */
    var yAxisLabel: String? = null

    protected val seriesList = mutableListOf<ChartSeries>()

    /**
     * Adds a named data series with the specified color.
     *
     * @param name  display name for the series (used in legends)
     * @param color the [Color] used to render this series
     * @param block DSL block to add data points to the series
     */
    fun series(name: String, color: Color, block: ChartSeriesBuilder.() -> Unit) {
        val builder = ChartSeriesBuilder(name, color)
        builder.block()
        seriesList.add(builder.build())
    }

    /**
     * Adds a named data series using a hex color string.
     *
     * @param name  display name for the series
     * @param hex   hex color string (e.g. `"#FF0000"`)
     * @param block DSL block to add data points to the series
     */
    fun series(name: String, hex: String, block: ChartSeriesBuilder.() -> Unit) {
        series(name, Color.fromHex(hex), block)
    }

    /**
     * Adds a pre-built [ChartSeries] directly.
     *
     * @param chartSeries the series to add
     */
    fun addSeries(chartSeries: ChartSeries) {
        seriesList.add(chartSeries)
    }

    /**
     * Applies common chart properties (title, axis labels) and series data
     * to the given chart instance.
     */
    protected fun applyCommonProperties(chart: com.bingbaihanji.jfgl.chart.Chart) {
        title?.let { chart.title = it }
        xAxisLabel?.let { chart.xAxisLabel = it }
        yAxisLabel?.let { chart.yAxisLabel = it }
        seriesList.forEach { chart.addSeries(it) }
    }

    /**
     * Builds and returns the configured chart.
     */
    internal abstract fun build(): T
}

// ---------------------------------------------------------------------------
// LineChartBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [LineChart].
 *
 * @param engine the [DrawEngine] that owns this chart
 * @param area   the rectangular area the chart occupies
 */
class LineChartBuilder internal constructor(
    engine: DrawEngine,
    private val area: Rect
) : BaseChartBuilder<LineChart>(engine) {

    /** Whether to draw smooth curves instead of straight line segments. */
    var smooth: Boolean = false

    /** Whether to display data point markers. */
    var showPoints: Boolean = true

    /** The radius of data point markers in pixels. */
    var pointRadius: Float = 4f

    /** Line width in pixels. */
    var lineWidth: Float = 2f

    /**
     * Builds and returns the configured [LineChart].
     */
    override fun build(): LineChart {
        val chart = LineChart(engine, area)
        applyCommonProperties(chart)
        chart.rebuild()
        return chart
    }
}

// ---------------------------------------------------------------------------
// BarChartBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [BarChart].
 *
 * @param engine the [DrawEngine] that owns this chart
 * @param area   the rectangular area the chart occupies
 */
class BarChartBuilder internal constructor(
    engine: DrawEngine,
    private val area: Rect
) : BaseChartBuilder<BarChart>(engine) {

    /** The gap between bar groups as a fraction of bar width (0.0 - 1.0). */
    var groupGap: Float = 0.2f

    /** The gap between bars within a group as a fraction of bar width (0.0 - 1.0). */
    var barGap: Float = 0.1f

    /** Whether bars are stacked rather than grouped side by side. */
    var stacked: Boolean = false

    /**
     * Builds and returns the configured [BarChart].
     */
    override fun build(): BarChart {
        val chart = BarChart(engine, area)
        applyCommonProperties(chart)
        chart.rebuild()
        return chart
    }
}

// ---------------------------------------------------------------------------
// PieChartBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [PieChart].
 *
 * Pie charts use center coordinates and radius instead of a rectangular area.
 *
 * @param engine the [DrawEngine] that owns this chart
 * @param cx     center x-coordinate
 * @param cy     center y-coordinate
 * @param radius radius of the pie
 */
class PieChartBuilder internal constructor(
    engine: DrawEngine,
    private val cx: Float,
    private val cy: Float,
    private val radius: Float
) : BaseChartBuilder<PieChart>(engine) {

    /** Whether to display percentage labels on slices. */
    var showPercentages: Boolean = true

    /** Whether to display the legend. */
    var showLegend: Boolean = true

    /** The starting angle in degrees (0 = 3 o'clock, 90 = 6 o'clock). */
    var startAngle: Float = 0f

    /**
     * Builds and returns the configured [PieChart].
     */
    override fun build(): PieChart {
        val chart = PieChart(engine, Rect(cx - radius, cy - radius, radius * 2, radius * 2))
        applyCommonProperties(chart)
        chart.rebuild()
        return chart
    }
}

// ---------------------------------------------------------------------------
// ScatterChartBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [ScatterChart].
 *
 * @param engine the [DrawEngine] that owns this chart
 * @param area   the rectangular area the chart occupies
 */
class ScatterChartBuilder internal constructor(
    engine: DrawEngine,
    private val area: Rect
) : BaseChartBuilder<ScatterChart>(engine) {

    /** The radius of each scatter point in pixels. */
    var pointRadius: Float = 5f

    /** Whether to connect points with lines. */
    var connectPoints: Boolean = false

    /**
     * Builds and returns the configured [ScatterChart].
     */
    override fun build(): ScatterChart {
        val chart = ScatterChart(engine, area)
        applyCommonProperties(chart)
        chart.rebuild()
        return chart
    }
}
