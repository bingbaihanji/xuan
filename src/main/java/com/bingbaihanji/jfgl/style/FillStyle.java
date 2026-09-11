package com.bingbaihanji.jfgl.style;

import com.bingbaihanji.jfgl.util.Color;

/**
 * Represents a fill style with color and opacity settings.
 */
public class FillStyle {

    private Color color;

    private float opacity;

    /**
     * Creates a FillStyle with the specified color and full opacity (1.0).
     *
     * @param color the fill color
     */
    public FillStyle(Color color) {
        this(color, 1.0f);
    }

    /**
     * Creates a FillStyle with the specified color and opacity.
     *
     * @param color   the fill color
     * @param opacity the opacity value, clamped to [0.0, 1.0]
     */
    public FillStyle(Color color, float opacity) {
        this.color = color;
        this.opacity = Math.clamp(opacity, 0.0f, 1.0f);
    }

    /**
     * Creates a FillStyle with the specified color and full opacity.
     *
     * @param color the fill color
     * @return a new FillStyle instance
     */
    public static FillStyle of(Color color) {
        return new FillStyle(color);
    }

    /**
     * Creates a FillStyle with the specified color and opacity.
     *
     * @param color   the fill color
     * @param opacity the opacity value
     * @return a new FillStyle instance
     */
    public static FillStyle of(Color color, float opacity) {
        return new FillStyle(color, opacity);
    }

    /**
     * Returns the fill color.
     *
     * @return the fill color
     */
    public Color getColor() {
        return color;
    }

    /**
     * Sets the fill color.
     *
     * @param color the fill color
     */
    public void setColor(Color color) {
        this.color = color;
    }

    /**
     * Returns the opacity value.
     *
     * @return the opacity in range [0.0, 1.0]
     */
    public float getOpacity() {
        return opacity;
    }

    /**
     * Sets the opacity value, clamping it to [0.0, 1.0].
     *
     * @param opacity the opacity value
     */
    public void setOpacity(float opacity) {
        this.opacity = Math.clamp(opacity, 0.0f, 1.0f);
    }

    /**
     * Returns a new Color representing the fill color with opacity applied.
     * The resulting color has its alpha channel multiplied by the opacity.
     *
     * @return the effective color with opacity applied
     */
    public Color getEffectiveColor() {
        return color.withAlpha(opacity);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FillStyle that)) {
            return false;
        }
        return Float.compare(opacity, that.opacity) == 0 && java.util.Objects.equals(color, that.color);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(color, opacity);
    }

    @Override
    public String toString() {
        return "FillStyle[color=%s, opacity=%.2f]".formatted(color, opacity);
    }
}
