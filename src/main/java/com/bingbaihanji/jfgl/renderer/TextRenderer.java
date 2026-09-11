package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.Texture;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders text by generating a font texture atlas using Java AWT and drawing
 * glyphs via a {@link BatchRenderer}. The atlas is a single 512x512 texture
 * that packs all printable ASCII characters at construction time.
 */
public class TextRenderer implements Disposable {

    private static final int ATLAS_SIZE = 512;

    private static final int FIRST_CHAR = 32;   // space

    private static final int LAST_CHAR = 126;    // tilde

    private final GLAbstraction gl;

    private final Map<Character, GlyphInfo> glyphCache;

    private final Font font;

    private final int lineHeight;

    private Texture fontTexture;

    /**
     * Creates a new {@code TextRenderer} with the given OpenGL abstraction and
     * the specified AWT font. The constructor immediately rasterises all
     * printable ASCII characters into a 512x512 texture atlas.
     *
     * @param gl   the OpenGL abstraction used to upload the atlas texture
     * @param font the AWT font to rasterise
     */
    public TextRenderer(GLAbstraction gl, Font font) {
        this.gl = gl;
        this.font = font;
        this.glyphCache = new HashMap<>();

        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = scratch.createGraphics();
        g2d.setFont(font);
        FontMetrics metrics = g2d.getFontMetrics();
        this.lineHeight = metrics.getHeight();
        g2d.dispose();

        buildAtlas();
    }

    /**
     * Convenience constructor that creates a {@code TextRenderer} using the
     * default serif plain font at the specified size.
     *
     * @param gl       the OpenGL abstraction
     * @param fontSize the point size of the font
     */
    public TextRenderer(GLAbstraction gl, int fontSize) {
        this(gl, new Font(Font.SERIF, Font.PLAIN, fontSize));
    }

    /**
     * Rasterises every printable ASCII glyph into a 512x512 atlas image,
     * uploads it as an OpenGL texture, and populates the glyph cache.
     */
    private void buildAtlas() {
        BufferedImage atlasImage = new BufferedImage(
                ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlasImage.createGraphics();

        // Transparent background
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, ATLAS_SIZE, ATLAS_SIZE);
        g.setComposite(AlphaComposite.SrcOver);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);

        FontMetrics metrics = g.getFontMetrics();
        FontRenderContext frc = g.getFontRenderContext();

        int cursorX = 1;  // 1-pixel padding to avoid bleeding
        int cursorY = 1;
        int rowHeight = 0;

        for (int codePoint = FIRST_CHAR; codePoint <= LAST_CHAR; codePoint++) {
            char ch = (char) codePoint;

            GlyphVector gv = font.createGlyphVector(frc, String.valueOf(ch));
            Rectangle2D bounds = gv.getVisualBounds();
            int charWidth = (int) Math.ceil(bounds.getWidth());
            int charHeight = (int) Math.ceil(bounds.getHeight());
            int advance = metrics.charWidth(ch);

            // Skip zero-width glyphs (e.g. space at small sizes)
            if (charWidth <= 0) {
                charWidth = advance;
            }
            if (charHeight <= 0) {
                charHeight = lineHeight;
            }

            // Wrap to next row when the atlas edge is reached
            if (cursorX + charWidth + 1 >= ATLAS_SIZE) {
                cursorX = 1;
                cursorY += rowHeight + 1;
                rowHeight = 0;
            }

            // Rasterise the glyph
            g.drawChars(new char[]{ch}, 0, 1,
                    cursorX - (int) bounds.getX(),
                    cursorY - (int) bounds.getY());

            // Compute normalised UV coordinates
            float u = (float) cursorX / ATLAS_SIZE;
            float v = (float) cursorY / ATLAS_SIZE;
            float u2 = (float) (cursorX + charWidth) / ATLAS_SIZE;
            float v2 = (float) (cursorY + charHeight) / ATLAS_SIZE;

            glyphCache.put(ch, new GlyphInfo(u, v, u2, v2,
                    charWidth, charHeight, advance));

            rowHeight = Math.max(rowHeight, charHeight);
            cursorX += charWidth + 1;
        }

        g.dispose();

        // Convert the atlas to RGBA int[] for the Texture constructor
        int[] pixels = new int[ATLAS_SIZE * ATLAS_SIZE];
        atlasImage.getRGB(0, 0, ATLAS_SIZE, ATLAS_SIZE, pixels, 0, ATLAS_SIZE);
        fontTexture = new Texture(ATLAS_SIZE, ATLAS_SIZE, pixels);
    }

    /**
     * Draws a string at the specified position using the given batch renderer
     * and colour. Each glyph is drawn as a textured quad via the batch.
     *
     * @param batch the batch renderer to submit quads to
     * @param text  the string to draw
     * @param x     the x position of the text baseline origin
     * @param y     the y position of the text baseline origin
     * @param color the colour to tint the text with
     */
    public void drawText(BatchRenderer batch, String text, float x, float y,
                         Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }

        fontTexture.bind(0);

        float drawX = x;
        float drawY = y;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            // Handle newline
            if (ch == '\n') {
                drawX = x;
                drawY += lineHeight;
                continue;
            }

            GlyphInfo glyph = glyphCache.get(ch);
            if (glyph == null) {
                // Fall back to space for unmapped characters
                glyph = glyphCache.get(' ');
                if (glyph == null) {
                    continue;
                }
            }

            batch.drawQuad(
                    drawX, drawY,
                    glyph.width, glyph.height,
                    glyph.u, glyph.v, glyph.u2, glyph.v2,
                    color
            );

            drawX += glyph.advance;
        }
    }

    /**
     * Returns the line height of the font used by this renderer.
     *
     * @return the line height in pixels
     */
    public int getLineHeight() {
        return lineHeight;
    }

    /**
     * Returns the horizontal advance width for a single character.
     *
     * @param ch the character to measure
     * @return the advance width in pixels, or the space advance if the
     *         character is not in the cache
     */
    public int getCharAdvance(char ch) {
        GlyphInfo glyph = glyphCache.get(ch);
        return glyph != null ? glyph.advance : glyphCache.get(' ').advance;
    }

    /**
     * Returns the width of the given string in pixels.
     *
     * @param text the string to measure
     * @return the total advance width
     */
    public int getTextWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        int maxWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                maxWidth = Math.max(maxWidth, width);
                width = 0;
            } else {
                width += getCharAdvance(ch);
            }
        }
        return Math.max(maxWidth, width);
    }

    /**
     * Returns the underlying font texture atlas.
     *
     * @return the font texture
     */
    public Texture getFontTexture() {
        return fontTexture;
    }

    @Override
    public void dispose() {
        if (fontTexture != null) {
            fontTexture.dispose();
            fontTexture = null;
        }
        glyphCache.clear();
    }

    /**
     * Holds the UV coordinates and metrics for a single glyph within the font
     * texture atlas.
     */
    public static final class GlyphInfo {

        public final float u;

        public final float v;

        public final float u2;

        public final float v2;

        public final int width;

        public final int height;

        public final int advance;

        GlyphInfo(float u, float v, float u2, float v2,
                  int width, int height, int advance) {
            this.u = u;
            this.v = v;
            this.u2 = u2;
            this.v2 = v2;
            this.width = width;
            this.height = height;
            this.advance = advance;
        }
    }
}
