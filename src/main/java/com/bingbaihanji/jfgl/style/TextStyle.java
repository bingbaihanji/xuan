package com.bingbaihanji.jfgl.style;

import com.bingbaihanji.jfgl.util.Color;

public final class TextStyle {

    private final String fontFamily;

    private final float fontSize;

    private final Color color;

    private final boolean bold;

    private final boolean italic;

    public TextStyle(String fontFamily, float fontSize, Color color) {
        this(fontFamily, fontSize, color, false, false);
    }

    public TextStyle(String fontFamily, float fontSize, Color color, boolean bold, boolean italic) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.color = color;
        this.bold = bold;
        this.italic = italic;
    }

    public static TextStyle of(String fontFamily, float fontSize, Color color) {
        return new TextStyle(fontFamily, fontSize, color);
    }

    public String fontFamily() {
        return fontFamily;
    }

    public float fontSize() {
        return fontSize;
    }

    public Color color() {
        return color;
    }

    public boolean bold() {
        return bold;
    }

    public boolean italic() {
        return italic;
    }
}
