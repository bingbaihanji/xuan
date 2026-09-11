package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.renderer.Path;
import com.bingbaihanji.jfgl.scene.ShapeNode;
import com.bingbaihanji.jfgl.style.StrokeStyle;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.List;

/**
 * A chart that renders each series as a connected line through its data points.
 *
 * <p>Data bounds are computed across all series so that every line shares a
 * common coordinate mapping.  The chart area defined by the {@link Rect}
 * passed to the constructor is used as the screen-space target rectangle.
 *
 * <p>Usage:
 * <pre>{@code
 * var chart = new LineChart(engine, new Rect(50, 50, 600, 400));
 * chart.addSeries(new Chart.ChartSeries("Sales", dataPoints, Color.BLUE));
 * chart.rebuild();
 * scene.getRoot().add(chart);
 * }</pre>
 */
public class LineChart extends Chart {

    private static final float DEFAULT_LINE_WIDTH = 2.0f;

    private static final float PADDING_FRACTION = 0.05f;

    /**
     * Creates a new line chart that will render within the given screen area.
     *
     * @param engine the draw engine
     * @param area   the screen-space rectangle defining the chart bounds
     */
    public LineChart(DrawEngine engine, Rect area) {
        super(engine, area);
    }

    /**
     * Rebuilds the chart scene graph from the current series data.
     *
     * <p>This method clears all existing children, computes the data-space
     * bounds across every series, and creates a {@link ShapeNode} per series
     * whose line path is mapped into the chart's screen area.
     */
    @Override
    public void rebuild() {
        dispose();

        List<ChartSeries> allSeries = getSeries();
        if (allSeries.isEmpty()) {
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

        // Add a small padding so lines don't sit flush against the edges.
        Rect area = getArea();
        float padX = area.width * PADDING_FRACTION;
        float padY = area.height * PADDING_FRACTION;
        float screenLeft = area.x + padX;
        float screenTop = area.y + padY;
        float screenWidth = area.width - 2 * padX;
        float screenHeight = area.height - 2 * padY;

        // Avoid division by zero when all values in an axis are identical.
        double dataRangeX = dataMaxX - dataMinX;
        double dataRangeY = dataMaxY - dataMinY;
        if (dataRangeX == 0.0) {
            dataRangeX = 1.0;
        }
        if (dataRangeY == 0.0) {
            dataRangeY = 1.0;
        }

        // Build one ShapeNode per series.
        for (ChartSeries series : allSeries) {
            List<double[]> points = series.getData();
            if (points == null || points.isEmpty()) {
                continue;
            }

            Path.Builder pathBuilder = Path.builder();
            boolean first = true;

            for (double[] point : points) {
                if (point.length < 2) {
                    continue;
                }
                float sx = (float) ((point[0] - dataMinX) / dataRangeX * screenWidth + screenLeft);
                // Screen y increases downward, so invert the data y-axis.
                float sy = (float) ((dataMaxY - point[1]) / dataRangeY * screenHeight + screenTop);

                if (first) {
                    pathBuilder.moveTo(sx, sy);
                    first = false;
                } else {
                    pathBuilder.lineTo(sx, sy);
                }
            }

            Path linePath = pathBuilder.build();
            Color color = series.getColor();
            StrokeStyle stroke = (color != null)
                    ? StrokeStyle.of(color, DEFAULT_LINE_WIDTH)
                    : StrokeStyle.of(Color.WHITE, DEFAULT_LINE_WIDTH);

            ShapeNode lineNode = new ShapeNode(linePath, null, stroke);
            add(lineNode);
        }
    }
}
