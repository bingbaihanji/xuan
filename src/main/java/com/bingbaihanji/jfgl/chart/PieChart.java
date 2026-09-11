package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.renderer.Path;
import com.bingbaihanji.jfgl.scene.ShapeNode;
import com.bingbaihanji.jfgl.style.FillStyle;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * A pie chart that renders data as proportional slices within a circular area.
 *
 * <p>Each slice is represented by a {@link Slice} containing a label, numeric
 * value, and color.  The chart computes each slice's angular extent relative
 * to the total of all values and renders filled arc-shaped {@link ShapeNode}s
 * using the {@link Path} builder.
 *
 * <p>Usage:
 * <pre>{@code
 * PieChart chart = new PieChart(engine, new Rect(100, 100, 400, 400));
 * chart.addSlice(new PieChart.Slice("Category A", 30.0, Color.RED));
 * chart.addSlice(new PieChart.Slice("Category B", 50.0, Color.BLUE));
 * chart.addSlice(new PieChart.Slice("Category C", 20.0, Color.GREEN));
 * chart.rebuild();
 * }</pre>
 */
public class PieChart extends Chart {

    private static final int DEFAULT_ARC_SEGMENTS = 64;

    private final List<Slice> slices = new ArrayList<>();

    private int arcSegments = DEFAULT_ARC_SEGMENTS;

    /**
     * Creates a new pie chart within the given drawing area.
     *
     * @param engine the drawing engine used for rendering
     * @param area   the bounding rectangle for the pie chart
     */
    public PieChart(DrawEngine engine, Rect area) {
        super(engine, area);
    }

    /**
     * Adds a slice to this pie chart.
     *
     * @param slice the slice to add; must not be {@code null}
     */
    public void addSlice(Slice slice) {
        if (slice == null) {
            throw new IllegalArgumentException("slice must not be null");
        }
        slices.add(slice);
    }

    /**
     * Returns the list of slices in this pie chart.
     *
     * @return an unmodifiable view of the slices
     */
    public List<Slice> getSlices() {
        return List.copyOf(slices);
    }

    /**
     * Removes all slices from this pie chart.
     */
    public void clearSlices() {
        slices.clear();
    }

    /**
     * Returns the number of line segments used to approximate each arc.
     *
     * @return the tessellation segment count
     */
    public int getArcSegments() {
        return arcSegments;
    }

    /**
     * Sets the number of line segments used to approximate each arc.
     * Higher values produce smoother curves but generate more geometry.
     *
     * @param arcSegments the segment count; must be at least 4
     */
    public void setArcSegments(int arcSegments) {
        if (arcSegments < 4) {
            throw new IllegalArgumentException("arcSegments must be >= 4, got " + arcSegments);
        }
        this.arcSegments = arcSegments;
    }

    /**
     * Rebuilds the pie chart geometry from the current slices.
     *
     * <p>Clears all existing children and creates a new {@link ShapeNode}
     * for each slice.  Each slice is rendered as a filled arc (pie wedge)
     * centered within the chart's bounding area.
     */
    @Override
    public void rebuild() {
        // Remove all existing children
        var existing = new ArrayList<>(getChildren());
        for (var child : existing) {
            remove(child);
        }

        if (slices.isEmpty()) {
            return;
        }

        Rect area = getArea();
        float cx = area.x + area.width * 0.5f;
        float cy = area.y + area.height * 0.5f;
        float radius = Math.min(area.width, area.height) * 0.5f;

        double total = slices.stream()
                .mapToDouble(Slice::value)
                .sum();

        if (total <= 0) {
            return;
        }

        double startAngle = 0.0;

        for (Slice slice : slices) {
            if (slice.value() <= 0) {
                continue;
            }

            double sweepAngle = (slice.value() / total) * 2.0 * Math.PI;
            double endAngle = startAngle + sweepAngle;

            Path path = buildArcPath(cx, cy, radius, startAngle, endAngle);
            ShapeNode node = new ShapeNode(path, FillStyle.of(slice.color()), null);
            add(node);

            startAngle = endAngle;
        }
    }

    /**
     * Builds a closed path representing a pie wedge (arc sector) from the
     * center of the circle to the arc and back.
     *
     * @param cx         the x-coordinate of the circle center
     * @param cy         the y-coordinate of the circle center
     * @param radius     the radius of the pie
     * @param startAngle the start angle in radians (0 = positive x-axis)
     * @param endAngle   the end angle in radians
     * @return the constructed arc path
     */
    private Path buildArcPath(float cx, float cy, float radius,
                              double startAngle, double endAngle) {
        Path.Builder builder = Path.builder();

        // Move to center
        builder.moveTo(cx, cy);

        // Line to start of arc
        float startX = cx + (float) (radius * Math.cos(startAngle));
        float startY = cy + (float) (radius * Math.sin(startAngle));
        builder.lineTo(startX, startY);

        // Arc segments: approximate the curve with line segments
        int segmentsForSlice = Math.max(
                2,
                (int) Math.ceil(arcSegments * (endAngle - startAngle) / (2.0 * Math.PI))
        );

        for (int i = 1; i <= segmentsForSlice; i++) {
            double t = i / (double) segmentsForSlice;
            double angle = startAngle + t * (endAngle - startAngle);
            float px = cx + (float) (radius * Math.cos(angle));
            float py = cy + (float) (radius * Math.sin(angle));
            builder.lineTo(px, py);
        }

        // Close back to center
        builder.close();

        return builder.build();
    }

    /**
     * A single slice of the pie chart, defined by a label, a numeric value,
     * and a display color.
     *
     * @param label the text label for this slice
     * @param value the numeric value determining the slice's angular proportion
     * @param color the fill color for this slice
     */
    public record Slice(String label, double value, Color color) {

        /**
         * Compact canonical constructor with validation.
         */
        public Slice {
            if (label == null) {
                throw new IllegalArgumentException("label must not be null");
            }
            if (value < 0) {
                throw new IllegalArgumentException("value must be non-negative, got " + value);
            }
            if (color == null) {
                throw new IllegalArgumentException("color must not be null");
            }
        }
    }
}
