package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.math.Transform;
import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.style.TextStyle;
import com.bingbaihanji.jfgl.util.Rect;

/**
 * A scene node that renders a text string with a given style.
 */
public class TextNode extends Node {

    private String text;

    private TextStyle textStyle;

    public TextNode(String text, TextStyle textStyle) {
        this.text = text;
        this.textStyle = textStyle;
    }

    public TextNode(float x, float y, String text, TextStyle textStyle) {
        super(new Transform(new Vec2(x, y), 0f, new Vec2(1f, 1f)));
        this.text = text;
        this.textStyle = textStyle;
    }

    @Override
    public void render(RenderContext context) {
        if (!isVisible() || text == null || text.isEmpty()) {
            return;
        }
        // Rendering implementation delegated to the concrete renderer layer
    }

    @Override
    public Rect getBounds() {
        Vec2 pos = transform.getPosition();
        if (text == null || text.isEmpty() || textStyle == null) {
            return new Rect(pos.x(), pos.y(), 0f, 0f);
        }
        float estimatedWidth = text.length() * textStyle.fontSize() * 0.6f;
        float estimatedHeight = textStyle.fontSize();
        return new Rect(pos.x(), pos.y(), estimatedWidth, estimatedHeight);
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public TextStyle getTextStyle() {
        return textStyle;
    }

    public void setTextStyle(TextStyle textStyle) {
        this.textStyle = textStyle;
    }
}
