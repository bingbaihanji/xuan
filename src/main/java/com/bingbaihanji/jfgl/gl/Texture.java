package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * Represents an OpenGL texture resource.
 */
public class Texture implements Disposable {

    private final int textureId;

    private final int width;

    private final int height;

    /**
     * Creates a new texture from RGBA pixel data.
     *
     * @param width  the width of the texture in pixels
     * @param height the height of the texture in pixels
     * @param pixels the RGBA pixel data (4 bytes per pixel)
     */
    public Texture(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;

        // Convert int[] RGBA to ByteBuffer
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                buffer.put((byte) ((pixel >> 0) & 0xFF));   // Red
                buffer.put((byte) ((pixel >> 8) & 0xFF));   // Green
                buffer.put((byte) ((pixel >> 16) & 0xFF));  // Blue
                buffer.put((byte) ((pixel >> 24) & 0xFF));  // Alpha
            }
        }
        buffer.flip();

        // Generate and configure the texture
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Upload pixel data
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        // Unbind texture
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Binds this texture to the specified texture unit.
     *
     * @param unit the texture unit to bind to (0-based, e.g., GL_TEXTURE0)
     */
    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    /**
     * Unbinds this texture from the currently active texture unit.
     */
    public void unbind() {
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Returns the OpenGL texture ID.
     *
     * @return the texture ID
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Returns the width of the texture.
     *
     * @return the width in pixels
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns the height of the texture.
     *
     * @return the height in pixels
     */
    public int getHeight() {
        return height;
    }

    @Override
    public void dispose() {
        glDeleteTextures(textureId);
    }
}