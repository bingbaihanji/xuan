package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * 一个 OpenGL 纹理资源。
 */
public class Texture implements Disposable {

    private int textureId;

    private final int width;

    private final int height;

    private boolean disposed;

    /**
     * 用项目统一的 ARGB 像素数据创建一张纹理。
     *
     * @param width  纹理宽度（像素）
     * @param height 纹理高度（像素）
     * @param pixels 像素数据，每个 int 为 {@code 0xAARRGGBB}
     */
    public Texture(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("纹理尺寸必须为正数：" + width + "x" + height);
        }
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("像素数量必须等于纹理面积");
        }
        this.width = width;
        this.height = height;

        ByteBuffer buffer = argbToRgba(width, height, pixels);

        // 建纹理并设参数
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * 把本纹理绑到指定的纹理单元。
     *
     * @param unit 纹理单元（从 0 开始，例如 {@code GL_TEXTURE0} 就是 0）
     */
    public void bind(int unit) {
        checkNotDisposed();
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    /** 从当前活动的纹理单元上解绑。 */
    public void unbind() {
        checkNotDisposed();
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * OpenGL 纹理名（{@code glGenTextures} 返回的那个）。
     *
     * @return 纹理名
     */
    public int getTextureId() {
        checkNotDisposed();
        return textureId;
    }

    /**
     * 纹理宽度。
     *
     * @return 宽度（像素）
     */
    public int getWidth() {
        return width;
    }

    /**
     * 纹理高度。
     *
     * @return 高度（像素）
     */
    public int getHeight() {
        return height;
    }

    @Override
    public void dispose() {
        if (!disposed) {
            glDeleteTextures(textureId);
            textureId = 0;
            disposed = true;
        }
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("纹理已释放");
        }
    }

    /** 将公共 API 的 ARGB 像素转换为 OpenGL 的 RGBA 字节序，供无上下文测试复用。 */
    static ByteBuffer argbToRgba(int width, int height, int[] pixels) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >>> 16) & 0xFF));
            buffer.put((byte) ((pixel >>> 8) & 0xFF));
            buffer.put((byte) (pixel & 0xFF));
            buffer.put((byte) ((pixel >>> 24) & 0xFF));
        }
        return buffer.flip();
    }
}
