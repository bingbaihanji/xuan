package com.bingbaihanji.jfgl.style;

/**
 * Composite style combining fill, stroke, and text styling.
 */
public class Style {

    private FillStyle fill;

    private StrokeStyle stroke;

    private TextStyle text;

    /**
     * Creates an empty Style with all properties set to {@code null}.
     */
    public Style() {
    }

    /**
     * Creates a Style with the specified fill and stroke, and no text style.
     *
     * @param fill   the fill style
     * @param stroke the stroke style
     */
    public Style(FillStyle fill, StrokeStyle stroke) {
        this.fill = fill;
        this.stroke = stroke;
    }

    /**
     * Returns a new {@link StyleBuilder} for fluent construction of a {@code Style}.
     *
     * @return a new builder instance
     */
    public static StyleBuilder builder() {
        return new StyleBuilder();
    }

    /**
     * Returns the fill style.
     *
     * @return the fill style, or {@code null} if unset
     */
    public FillStyle getFill() {
        return fill;
    }

    /**
     * Sets the fill style.
     *
     * @param fill the fill style
     */
    public void setFill(FillStyle fill) {
        this.fill = fill;
    }

    /**
     * Returns the stroke style.
     *
     * @return the stroke style, or {@code null} if unset
     */
    public StrokeStyle getStroke() {
        return stroke;
    }

    /**
     * Sets the stroke style.
     *
     * @param stroke the stroke style
     */
    public void setStroke(StrokeStyle stroke) {
        this.stroke = stroke;
    }

    /**
     * Returns the text style.
     *
     * @return the text style, or {@code null} if unset
     */
    public TextStyle getText() {
        return text;
    }

    /**
     * Sets the text style.
     *
     * @param text the text style
     */
    public void setText(TextStyle text) {
        this.text = text;
    }

    /**
     * Builder for constructing {@link Style} instances fluently.
     */
    public static class StyleBuilder {

        private FillStyle fill;

        private StrokeStyle stroke;

        private TextStyle text;

        /**
         * Sets the fill style.
         *
         * @param fill the fill style
         * @return this builder
         */
        public StyleBuilder fill(FillStyle fill) {
            this.fill = fill;
            return this;
        }

        /**
         * Sets the stroke style.
         *
         * @param stroke the stroke style
         * @return this builder
         */
        public StyleBuilder stroke(StrokeStyle stroke) {
            this.stroke = stroke;
            return this;
        }

        /**
         * Sets the text style.
         *
         * @param text the text style
         * @return this builder
         */
        public StyleBuilder text(TextStyle text) {
            this.text = text;
            return this;
        }

        /**
         * Builds and returns a new {@link Style} from the configured values.
         *
         * @return a new {@code Style} instance
         */
        public Style build() {
            Style style = new Style();
            style.setFill(fill);
            style.setStroke(stroke);
            style.setText(text);
            return style;
        }
    }
}
