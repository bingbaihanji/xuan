package com.bingbaihanji.jfgl.text;

/**
 * 字形位图的来源：给一个 key，返回它的位图。
 *
 * <p><strong>图集只认识这个接口，不认识字体，也不认识 SDF。</strong>
 * 这个切分是为了可测：{@code GlyphAtlasTest} 喂一个返回固定尺寸位图的假实现，
 * 就能在不加载 stb、不开 GL 上下文的前提下测全部分配逻辑；
 * 真实实现 {@link FontGlyphSource}（把 {@link FontFile} + {@link GlyphRasterizer} +
 * {@link SdfGenerator} 串起来）则由 {@code TextVerifier} 覆盖。
 *
 * <p><strong>实现必须是同步的、且不得返回 {@code null}。</strong>
 * 本接口在 GL 线程上被调用，首次出现的字形会在这里当场完成光栅化与距离场计算。
 *
 * <p>key 的含义由实现定义。真实实现按<strong>字形索引</strong>缓存，
 * 因为本期只有一个 em 尺寸桶（见 {@link GlyphRasterizer#EM_SIZE}）。
 */
@FunctionalInterface
public interface GlyphSource {

    /**
     * 返回 key 对应的字形位图。
     *
     * @param key 字形标识，含义由实现定义
     * @return 位图，不得为 {@code null}
     */
    GlyphBitmap pixelsFor(int key);
}
