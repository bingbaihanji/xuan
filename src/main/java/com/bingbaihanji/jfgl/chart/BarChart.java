package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.renderer.Path;
import com.bingbaihanji.jfgl.scene.Node;
import com.bingbaihanji.jfgl.scene.ShapeNode;
import com.bingbaihanji.jfgl.style.FillStyle;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.List;

/**
 * A chart that renders each series as vertical bars.
 *
 * <p>Data bounds are computed across all series so that every bar shares a
 * common coordinate mapping. When multiple series are present, bars for the
 * same x-value are placed side by side within a grouped layout.
 *
 * <p>Each data point is a {@code double[]} with at least two elements:
 * {@code [x, y]} where {@code x} is the category/position and {@code y} is
 * the bar height.
 *
 * <p>Usage:
 * <pre>{@code
 * var chart = new BarChart(engine, new Rect(50, 50, 600, 400));
 * chart.addSeries(new Chart.ChartSeries("Revenue", dataPoints, Color.BLUE));
 * chart.rebuild();
 * scene.getRoot().add(chart);
 * }</pre>
 */
public class BarChart extends Chart {

    /** Fraction of the chart area reserved as padding on each side. */
    private static final float PADDING_FRACTION = 0.05f;

    /** Fraction of a bar group's width used as spacing between bars. */
    private static final float BAR_GAP_FRACTION = 0.1f;

    /**
     * Creates a new bar chart that will render within the given screen area.
     *
     * @param engine the draw engine
     * @param area   the screen-space rectangle defining the chart bounds
     */
    public BarChart(DrawEngine engine, Rect area) {
        super(engine, area);
    }

    /**
     * Rebuilds the chart scene graph from the current series data.
     *
     * <p>This method clears all existing children, computes the data-space
     * bounds across every series, and creates a filled {@link ShapeNode}
     * rectangle for each data point, arranged as grouped bars.
     */
    @Override
    public void rebuild() {
        List<Node> children = List.copyOf(getChildren());
        for (Node child : children) {
            remove(child);
        }

        List<ChartSeries> allSeries = getSeries();
        if (allSeries.isEmpty()) {
            return;
        }

        Rect area = getArea();
        if (area.width <= 0 || area.height <= 0) {
            return;
        }

        // Collect global data bounds across all series.
        double dataMinX = Double.MAX_VALUE;
        double dataMinY = Double.MAX_VALUE;
        double dataMaxX = -Double.MAX_VALUE;
        double dataMaxY = -Double.MAX_VALUE;
        boolean hasData = false;

        for (ChartSeries series : allSeries) {
            List<double[]> points = series.getData();
            if (points == null || points.isEmpty()) {
                continue;
            }
            for (double[] point : points) {
                if (point.length < 2) {
                    continue;
                }
                double px = point[0];
                double py = point[1];
                if (px < dataMinX) {
                    dataMinX = px;
                }
                if (px > dataMaxX) {
                    dataMaxX = px;
                }
                if (py < dataMinY) {
                    dataMinY = py;
                }
                if (py > dataMaxY) {
                    dataMaxY = py;
                }
                hasData = true;
            }
        }

        if (!hasData) {
            return;
        }

        // Add padding so bars don't sit flush against the edges.
        float padX = area.width * PADDING_FRACTION;
        float padY = area.height * PADDING_FRACTION;
        float screenLeft = area.x + padX;
        float screenTop = area.y + padY;
        float screenWidth = area.width - 2 * padX;
        float screenHeight = area.height - 2 * padY;

        // Baseline is the y-value mapped to the bottom of the chart area.
        float baselineY = screenTop + screenHeight;

        // Avoid division by zero when all y values are identical.
        double dataRangeY = dataMaxY - dataMinY;
        if (dataRangeY == 0.0) {
            dataRangeY = 1.0;
        }

        // Determine the number of distinct x categories from the first series.
        int categoryCount = 0;
        for (ChartSeries series : allSeries) {
            List<double[]> points = series.getData();
            if (points != null && points.size() > categoryCount) {
                categoryCount = points.size();
            }
        }
        if (categoryCount == 0) {
            return;
        }

        int seriesCount = allSeries.size();

        // Width allocated to each category slot on screen.
        float categoryWidth = screenWidth / categoryCount;

        // Width of an individual bar within a category group.
        // Reserve a small gap fraction between bars in the same group.
        float groupGap = categoryWidth * BAR_GAP_FRACTION;
        float availableBarWidth = categoryWidth - groupGap;
        float barWidth = availableBarWidth / seriesCount;

        // Build a filled rectangle for every data point.
        for (int s = 0; s < seriesCount; s++) {
            ChartSeries series = allSeries.get(s);
            List<double[]> points = series.getData();
            if (points == null || points.isEmpty()) {
                continue;
            }

            Color color = series.getColor();
            FillStyle fillStyle = (color != null)
                    ? FillStyle.of(color)
                    : FillStyle.of(Color.WHITE);

            for (int i = 0; i < points.size(); i++) {
                double[] point = points.get(i);
                if (point.length < 2) {
                    continue;
                }

                // Map the y value to a screen-space bar height.
                float barHeight = (float) ((point[1] - dataMinY) / dataRangeY * screenHeight);
                if (barHeight <= 0) {
                    continue;
                }

                // Compute the x position for this bar within its category group.
                float categoryLeft = screenLeft + i * categoryWidth;
                float barX = categoryLeft + groupGap / 2.0f + s * barWidth;
                float barY = baselineY - barHeight;

                Path barPath = Path.builder()
                        .moveTo(barX, barY)
                        .lineTo(barX + barWidth, barY)
                        .lineTo(barX + barWidth, baselineY)
                        .lineTo(barX, baselineY)
                        .close()
                        .build();

                ShapeNode barNode = new ShapeNode(barPath, fillStyle, null);
                add(barNode);
            }
        }
    }
}
