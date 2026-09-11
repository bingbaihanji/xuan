package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.renderer.Path;
import com.bingbaihanji.jfgl.scene.Node;
import com.bingbaihanji.jfgl.scene.ShapeNode;
import com.bingbaihanji.jfgl.style.FillStyle;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.List;

/**
 * A scatter chart that renders data points as filled circles.
 *
 * <p>Each data point from the chart's series is drawn as a small circle
 * positioned according to its x/y values within the chart area. The
 * chart automatically scales data coordinates to fit the available area.
 *
 * <p>Usage:
 * <pre>{@code
 * ScatterChart chart = new ScatterChart(engine, new Rect(50, 50, 600, 400));
 * chart.addSeries(new ChartSeries("Measurements", dataPoints, Color.BLUE));
 * chart.rebuild();
 * scene.getRoot().add(chart);
 * }</pre>
 */
public class ScatterChart extends Chart {

    /** Default radius of each data point circle in pixels. */
    private static final float DEFAULT_POINT_RADIUS = 5.0f;

    /** Number of line segments used to approximate each circle. */
    private static final int CIRCLE_SEGMENTS = 32;

    private float pointRadius;

    /**
     * Creates a new scatter chart with the given engine and area.
     *
     * @param engine the draw engine
     * @param area   the bounding rectangle for the chart
     */
    public ScatterChart(DrawEngine engine, Rect area) {
        this(engine, area, DEFAULT_POINT_RADIUS);
    }

    /**
     * Creates a new scatter chart with a custom point radius.
     *
     * @param engine     the draw engine
     * @param area       the bounding rectangle for the chart
     * @param pointRadius the radius of each data point circle in pixels
     */
    public ScatterChart(DrawEngine engine, Rect area, float pointRadius) {
        super(engine, area);
        this.pointRadius = pointRadius;
    }

    /**
     * Returns the radius of each data point circle.
     *
     * @return the point radius in pixels
     */
    public float getPointRadius() {
        return pointRadius;
    }

    /**
     * Sets the radius of each data point circle.
     *
     * @param pointRadius the new radius in pixels
     */
    public void setPointRadius(float pointRadius) {
        this.pointRadius = pointRadius;
    }

    /**
     * Rebuilds the scatter chart by creating circle nodes for each data point.
     *
     * <p>This method clears all existing children and recreates them from the
     * current series data. Each data point is rendered as a filled circle
     * positioned within the chart area based on its x/y values.
     */
    @Override
    public void rebuild() {
        // Clear all existing children
        List<Node> children = List.copyOf(getChildren());
        for (Node child : children) {
            remove(child);
        }

        List<ChartSeries> seriesList = getSeries();
        if (seriesList.isEmpty()) {
            return;
        }

        Rect area = getArea();
        if (area.width <= 0 || area.height <= 0) {
            return;
        }

        // Find data bounds across all series
        float minX = findMinX(seriesList);
        float maxX = findMaxX(seriesList);
        float minY = findMinY(seriesList);
        float maxY = findMaxY(seriesList);

        // Add padding to prevent points from being clipped at edges
        float padding = pointRadius * 2;
        Rect plotArea = area.expand(-padding);

        // Avoid division by zero for flat data
        float rangeX = maxX - minX;
        float rangeY = maxY - minY;
        if (rangeX == 0) {
            rangeX = 1;
        }
        if (rangeY == 0) {
            rangeY = 1;
        }

        // Create circle nodes for each data point
        for (ChartSeries series : seriesList) {
            FillStyle fillStyle = FillStyle.of(series.getColor());

            for (double[] point : series.getData()) {
                if (point.length < 2) {
                    continue;
                }

                // Map data coordinates to pixel coordinates
                float px = plotArea.x + (float) ((point[0] - minX) / rangeX) * plotArea.width;
                float py = plotArea.y + plotArea.height - (float) ((point[1] - minY) / rangeY) * plotArea.height;

                Path circlePath = createCirclePath(px, py, pointRadius);
                ShapeNode circleNode = new ShapeNode(circlePath, fillStyle, null);
                add(circleNode);
            }
        }
    }

    /**
     * Creates a circular path centered at the given position.
     *
     * @param cx       the center x-coordinate
     * @param cy       the center y-coordinate
     * @param radius   the circle radius
     * @return a path approximating a circle
     */
    private Path createCirclePath(float cx, float cy, float radius) {
        Path.Builder builder = Path.builder();

        // Start at the rightmost point of the circle
        builder.moveTo(cx + radius, cy);

        // Approximate the circle with line segments
        for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
            float x = cx + (float) (radius * Math.cos(angle));
            float y = cy + (float) (radius * Math.sin(angle));
            builder.lineTo(x, y);
        }

        return builder.build();
    }

    /**
     * Finds the minimum x value across all series.
     */
    private float findMinX(List<ChartSeries> seriesList) {
        float min = Float.MAX_VALUE;
        for (ChartSeries series : seriesList) {
            for (double[] point : series.getData()) {
                if (point.length >= 1 && point[0] < min) {
                    min = (float) point[0];
                }
            }
        }
        return min;
    }

    /**
     * Finds the maximum x value across all series.
     */
    private float findMaxX(List<ChartSeries> seriesList) {
        float max = -Float.MAX_VALUE;
        for (ChartSeries series : seriesList) {
            for (double[] point : series.getData()) {
                if (point.length >= 1 && point[0] > max) {
                    max = (float) point[0];
                }
            }
        }
        return max;
    }

    /**
     * Finds the minimum y value across all series.
     */
    private float findMinY(List<ChartSeries> seriesList) {
        float min = Float.MAX_VALUE;
        for (ChartSeries series : seriesList) {
            for (double[] point : series.getData()) {
                if (point.length >= 2 && point[1] < min) {
                    min = (float) point[1];
                }
            }
        }
        return min;
    }

    /**
     * Finds the maximum y value across all series.
     */
    private float findMaxY(List<ChartSeries> seriesList) {
        float max = -Float.MAX_VALUE;
        for (ChartSeries series : seriesList) {
            for (double[] point : series.getData()) {
                if (point.length >= 2 && point[1] > max) {
                    max = (float) point[1];
                }
            }
        }
        return max;
    }
}
