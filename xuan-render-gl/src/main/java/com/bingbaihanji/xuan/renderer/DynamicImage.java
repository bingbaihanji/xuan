package com.bingbaihanji.xuan.renderer;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import java.nio.ByteBuffer;

/**
 * 可更新纹理：专为模拟器/视频等每帧刷新的场景设计。
 *
 * <p>与 {@link ImageHandle} 的区别：ImageHandle 按 int[] 对象身份缓存纹理，
 * 就地修改不生效；DynamicImage 直接持有 GL 纹理名，通过
 * {@code glTexSubImage2D} 增量更新，零分配、零重建。
 *
 * <h2>支持的像素格式</h2>
 * <ul>
 *   <li>{@code ARGB_8888} — 0xAARRGGBB int 数组</li>
 *   <li>{@code RGB_565} — 16-bit 5:6:5，mGBA 默认输出格式</li>
 *   <li>{@code XRGB_8888} — 32-bit 8:8:8:8，高位忽略</li>
 * </ul>
 *
 * <h2>线程约束</h2>
 * 创建、update、dispose 都必须在 GL 线程上调用。
 */
public final class DynamicImage implements Disposable {

    /** 像素格式。 */
    public enum PixelFormat {
        ARGB_8888,
        RGB_565,
        XRGB_8888
    }

    private final GLAbstraction gl;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private int textureId;
    private boolean disposed;

    /**
     * 创建可更新纹理。分配 GPU 显存但不上传像素。
     *
     * @param gl     GL 抽象
     * @param width  纹理宽度
     * @param height 纹理高度
     * @param format 像素格式
     */
    public DynamicImage(GLAbstraction gl, int width, int height, PixelFormat format) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("纹理尺寸必须为正: " + width + "x" + height);
        }
        this.gl = gl;
        this.width = width;
        this.height = height;
        this.format = format;
        this.textureId = allocateTexture(gl, width, height, format);
    }

    /**
     * 用 RGB_565 数据更新纹理（mGBA 默认格式）。
     *
     * @param pixels direct ByteBuffer，内容为 RGB_565，长度 ≥ width × height × 2
     */
    public void update(ByteBuffer pixels) {
        checkNotDisposed();
        if (format != PixelFormat.RGB_565) {
            throw new IllegalStateException("格式不匹配: 期望 RGB_565，实际 " + format);
        }
        gl.uploadRgb565SubImage(textureId, 0, 0, width, height, pixels);
    }

    /**
     * 用 0xAARRGGBB 像素数组更新纹理。
     *
     * @param pixels 长度必须等于 width × height
     */
    public void update(int[] pixels) {
        checkNotDisposed();
        if (pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数 " + pixels.length + " != " + width + "×" + height);
        }
        if (format == PixelFormat.ARGB_8888 || format == PixelFormat.XRGB_8888) {
            gl.uploadArgbSubImage(textureId, 0, 0, width, height, pixels);
        } else {
            throw new IllegalStateException("格式不匹配: 期望 " + format + "，用 update(ByteBuffer)");
        }
    }

    /** 纹理 GL 名字，供 {@code Gc} 内部使用。 */
    public int textureId() {
        checkNotDisposed();
        return textureId;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public PixelFormat format() {
        return format;
    }

    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (textureId != 0) {
            gl.deleteTexture(textureId);
            textureId = 0;
        }
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("DynamicImage 已 dispose()，不能继续使用");
        }
    }

    private static int allocateTexture(GLAbstraction gl, int w, int h, PixelFormat format) {
        // createTexture 会分配显存并绑定到 GL_TEXTURE_2D
        // 对于 RGB_565 用专门分配；否则用 ARGB 分配
        if (format == PixelFormat.RGB_565) {
            return gl.createRgb565Texture(w, h);
        } else {
            // 用空数据分配 ARGB 纹理（内容无所谓，后面 update 会覆盖）
            int[] empty = new int[w * h];
            return gl.createPremultipliedTexture(w, h, empty);
        }
    }
}
