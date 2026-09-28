package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.util.Disposable;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一份 TrueType 字体：stb 的封装，提供字形索引与度量。
 *
 * <h2>字体字节必须活到最后一刻</h2>
 * <p>stb 的 {@code stbtt_fontinfo} <strong>不复制</strong>字体数据，它只记住
 * 数据块的地址。因此本类：
 * <ol>
 *   <li>用 {@link MemoryUtil#memAlloc(int)} 分配<strong>堆外</strong>内存持有字体字节，
 *       并在 {@link #dispose()} 里 {@link MemoryUtil#memFree}；</li>
 *   <li><strong>绝不用 {@code MemoryStack}</strong>——它默认只有 64 KB，
 *       而字体是 9.7 MB，压栈的那一刻就炸。</li>
 * </ol>
 * <p>{@code STBTTFontinfo} 本身也是 {@code malloc} 出来的本地结构，同样在 dispose 里释放。
 *
 * <p>释放之后再调用任何方法都会抛 {@link IllegalStateException}：此刻字体字节已经
 * 归还，再交给 stb 就是 use-after-free——它不会报错，只会读到垃圾或者直接崩进程。
 *
 * <h2>线程</h2>
 * <p>非线程安全（内部有一个复用的度量暂存数组）。与 {@link GlyphAtlas} 一样，
 * 只在 GL 线程上使用。
 */
public final class FontFile implements Disposable {


    /**
     * 字体字节的持有者。
     *
     * <p>这块内存<strong>必须</strong>比 {@link #info} 活得久：stb 只记住地址，不复制。
     * 用直接内存而不是 {@code MemoryStack}——后者默认 64 KB，装不下 9.7 MB 的字体。
     */
    private final ByteBuffer data;

    /** stb 的字体信息结构。 */
    private final STBTTFontinfo info;

    /** 度量查询的复用暂存，避免每个字形都往 {@code MemoryStack} 里压两格。 */
    private final int[] hMetricsScratch = new int[2];

    /** 光栅化时复用的直接缓冲，按需扩容。 */
    private ByteBuffer rasterScratch;

    /** 是否已释放。 */
    private boolean disposed = false;

    private FontFile(ByteBuffer data, STBTTFontinfo info) {
        this.data = data;
        this.info = info;
    }

    /**
     * 从文件加载字体。
     *
     * @param path 字体文件路径
     * @return 加载好的字体
     * @throws UncheckedIOException 读取失败时
     * @throws IllegalArgumentException 不是有效的 TrueType 字体时
     */
    public static FontFile load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体文件失败: " + path, e);
        }
    }

    /**
     * 从流加载字体。
     *
     * @param in 字体数据的输入流，由本方法负责读完（但不负责关闭）
     * @return 加载好的字体
     * @throws UncheckedIOException 读取失败时
     * @throws IllegalArgumentException 不是有效的 TrueType 字体时
     */
    public static FontFile load(InputStream in) {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体数据失败", e);
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("字体数据为空");
        }

        // 用 memAlloc 而不是 MemoryStack：字体是 9.7 MB，MemoryStack 默认只有 64 KB。
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes);
        data.flip();

        STBTTFontinfo info = STBTTFontinfo.malloc();
        if (!STBTruetype.stbtt_InitFont(info, data)) {
            // 解析失败也要把这两块资源还回去，否则每失败一次泄一次
            info.free();
            MemoryUtil.memFree(data);
            throw new IllegalArgumentException(
                    "无法解析字体数据（stbtt_InitFont 返回 0）：请确认它是一个 TTF 文件。"
                            + "stb_truetype 对 CFF/PostScript 轮廓（.otf）支持较弱，"
                            + "可变字体（带 fvar/gvar）也只能渲染默认实例——"
                            + "见 src/main/resources/fonts/README.md");
        }
        return new FontFile(data, info);
    }

    /**
     * 从 classpath 加载字体。
     *
     * @param resource 资源路径，如某个 `.ttf` 的 classpath 路径
     * @return 加载好的字体
     * @throws IllegalStateException 资源不存在时（消息里给出期望路径）
     */
    public static FontFile loadClasspath(String resource) {
        try (InputStream in = FontFile.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "字体资源不存在: " + resource
                                + "。它应当在 src/main/resources" + resource
                                + "（构建后会进 target/classes）——"
                                + "见 src/main/resources/fonts/README.md");
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体资源失败: " + resource, e);
        }
    }

    /**
     * 返回码点对应的字形索引。
     *
     * @param codepoint Unicode 码点
     * @return 字形索引；<strong>0 表示 .notdef</strong>（字体里没有这个码点），
     *         调用方应当照常画它（通常是个方框），不要静默跳过
     */
    public int glyphIndex(int codepoint) {
        ensureAlive();
        return STBTruetype.stbtt_FindGlyphIndex(info, codepoint);
    }

    /**
     * 返回"把字体缩放到给定像素高度"所需的缩放因子。
     *
     * <p>用法：{@code 像素值 = 字体单位值 * scaleForPixelHeight(像素高度)}。
     * 它与像素高度<strong>严格成正比</strong>——本期的字号缩放完全建立在这条性质上。
     *
     * @param pixelHeight 期望的像素高度（字号）
     * @return 缩放因子
     */
    public float scaleForPixelHeight(float pixelHeight) {
        ensureAlive();
        return STBTruetype.stbtt_ScaleForPixelHeight(info, pixelHeight);
    }

    /**
     * 返回字形的推进宽度，单位是<strong>字体单位</strong>。
     *
     * @param glyphIndex 字形索引
     * @return 推进宽度（字体单位）；先乘 {@link #scaleForPixelHeight(float)} 才是像素
     */
    public int advance(int glyphIndex) {
        hMetrics(glyphIndex);
        return hMetricsScratch[0];
    }

    /**
     * 返回字形的左旁距，单位是<strong>字体单位</strong>。
     *
     * @param glyphIndex 字形索引
     * @return 左旁距（字体单位）
     */
    public int glyphLeftSideBearing(int glyphIndex) {
        hMetrics(glyphIndex);
        return hMetricsScratch[1];
    }

    /**
     * 返回字形在字体坐标下的包围盒，写进 {@code out} 的 {@code [0..3]}。
     *
     * <p>顺序为 {@code x0, y0, x1, y1}，y 向下，因此 {@code y0} 通常是负数（基线之上）。
     * 没有轮廓的字形（空格、以及索引不在字体里的字形）四值全为 0。
     *
     * @param glyphIndex 字形索引
     * @param out        接收 4 个值的数组，长度至少为 4
     */
    public void glyphBox(int glyphIndex, int[] out) {
        ensureAlive();
        requireOut(out);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            boolean hasBox = STBTruetype.stbtt_GetGlyphBox(info, glyphIndex, x0, y0, x1, y1);
            out[0] = hasBox ? x0.get(0) : 0;
            out[1] = hasBox ? y0.get(0) : 0;
            out[2] = hasBox ? x1.get(0) : 0;
            out[3] = hasBox ? y1.get(0) : 0;
        }
    }

    /**
     * 返回字形在<strong>给定缩放下的像素包围盒</strong>，写进 {@code out} 的 {@code [0..3]}。
     *
     * <p>顺序为 {@code x0, y0, x1, y1}，y 向下，{@code y0} 通常是负数。
     * 这一步同时完成了取整：stb 的 {@code stbtt_GetGlyphBitmapBox} 会把包围盒按
     * "覆盖所有被字形碰到的像素"取整，因此用它算出来的位图尺寸不会裁掉任何笔画。
     *
     * <p>没有轮廓的字形返回全 0（即宽度或高度为 0 → 空字形）。
     * 注意 {@code y1 - y0} 可能为负（退化字形），调用方必须先判空再算宽高。
     *
     * @param glyphIndex 字形索引
     * @param scale      缩放因子，见 {@link #scaleForPixelHeight(float)}
     * @param out        接收 4 个值的数组，长度至少为 4
     */
    public void glyphPixelBox(int glyphIndex, float scale, int[] out) {
        ensureAlive();
        requireOut(out);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBox(info, glyphIndex, scale, scale, x0, y0, x1, y1);
            out[0] = x0.get(0);
            out[1] = y0.get(0);
            out[2] = x1.get(0);
            out[3] = y1.get(0);
        }
    }

    /**
     * 把字形光栅化成 8 位覆盖度位图，写进 {@code out}，按行存储。
     *
     * <p>覆盖度是 stb 的抗锯齿结果：0 = 完全在字形外，255 = 完全在字形内。
     * 之后要交给 {@link SdfGenerator} 转成距离场。
     *
     * @param glyphIndex 字形索引
     * @param scale      缩放因子，见 {@link #scaleForPixelHeight(float)}
     * @param width      位图宽度，来自 {@link #glyphPixelBox}
     * @param height     位图高度，来自 {@link #glyphPixelBox}
     * @param out        接收 {@code width * height} 个字节的数组，长度至少为这么多
     * @throws IllegalArgumentException 数组长度不足时
     */
    public void makeGlyphBitmap(int glyphIndex, float scale, int width, int height, byte[] out) {
        ensureAlive();
        int bytes = width * height;
        if (out.length < bytes) {
            throw new IllegalArgumentException(
                    "输出数组长度不足：需要 " + bytes + "，实际 " + out.length);
        }
        ensureRasterScratch(bytes);
        rasterScratch.clear();
        rasterScratch.limit(bytes);
        // stb 要的是本地内存，byte[] 不行，所以必须过一趟直接缓冲。
        // 缓冲复用、按需扩容，避免每来一个新字形就 memAlloc 一次。
        STBTruetype.stbtt_MakeGlyphBitmap(info, rasterScratch, width, height, width,
                scale, scale, glyphIndex);
        rasterScratch.position(0);
        rasterScratch.get(out, 0, bytes);
    }

    /** 释放字体字节、stb 结构体与光栅化缓冲。重复调用无副作用。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        // 先放 stb 结构体再放字体内存：stb 结构体里存着指向字体字节的裸指针，
        // 虽然 free 本身不会解引用它，但这个顺序读起来更安全。
        info.free();
        MemoryUtil.memFree(data);
        if (rasterScratch != null) {
            MemoryUtil.memFree(rasterScratch);
            rasterScratch = null;
        }
        disposed = true;
    }

    /**
     * 释放之后再使用就抛异常。
     *
     * <p>必须挡住：字体字节已经归还，再交给 stb 是 use-after-free，
     * 它不会报错，只会读到垃圾或者直接崩进程。
     */
    private void ensureAlive() {
        if (disposed) {
            throw new IllegalStateException(
                    "字体已释放（dispose()）：此时字体字节已归还，再调用 stb 就是 use-after-free");
        }
    }

    private void requireOut(int[] out) {
        if (out == null || out.length < 4) {
            throw new IllegalArgumentException("输出数组长度至少为 4");
        }
    }

    /** 查询字形的水平度量，结果写进 {@link #hMetricsScratch}。 */
    private void hMetrics(int glyphIndex) {
        ensureAlive();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advanceWidth = stack.mallocInt(1);
            IntBuffer leftSideBearing = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(info, glyphIndex, advanceWidth, leftSideBearing);
            hMetricsScratch[0] = advanceWidth.get(0);
            hMetricsScratch[1] = leftSideBearing.get(0);
        }
    }

    /** 保证光栅化用的直接缓冲至少有 {@code bytes} 字节。 */
    private void ensureRasterScratch(int bytes) {
        if (rasterScratch == null || rasterScratch.capacity() < bytes) {
            if (rasterScratch != null) {
                MemoryUtil.memFree(rasterScratch);
            }
            rasterScratch = MemoryUtil.memAlloc(bytes);
        }
    }
}
