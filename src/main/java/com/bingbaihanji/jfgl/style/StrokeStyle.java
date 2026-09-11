package com.bingbaihanji.jfgl.style;

import com.bingbaihanji.jfgl.util.Color;

public final class StrokeStyle {

    private final Color color;

    private final float width;

    private final LineCap lineCap;

    private final LineJoin lineJoin;

    private final float[] dashPattern;

    public StrokeStyle(Color color, float width) {
        this(color, width, LineCap.BUTT, LineJoin.MITER, null);
    }

    public StrokeStyle(Color color, float width, LineCap lineCap, LineJoin lineJoin, float[] dashPattern) {
        this.color = color;
        this.width = width;
        this.lineCap = lineCap;
        this.lineJoin = lineJoin;
        this.dashPattern = dashPattern;
    }

    public static StrokeStyle of(Color color, float width) {
        return new StrokeStyle(color, width);
    }

    public Color getColor() {
        return color;
    }

    public float getWidth() {
        return width;
    }

    public LineCap getLineCap() {
        return lineCap;
    }

    public LineJoin getLineJoin() {
        return lineJoin;
    }

    public float[] getDashPattern() {
        return dashPattern;
    }

    public enum LineCap {
        BUTT,
        ROUND,
        SQUARE
    }

    public enum LineJoin {
        MITER,
        ROUND,
        BEVEL
    }
}
