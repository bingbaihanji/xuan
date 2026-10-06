package com.bingbaihanji.xuan.renderer;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 图片纹理的缓存与回收：一张图上传一次，之后每次绘制都只是查表。
 *
 * <h2>键是像素数组的<strong>对象身份</strong></h2>
 * <p>{@code gc.drawImage(pixels, ...)} 与 {@code gc.drawImage(handle, ...)}
 * <strong>共用同一条缓存槽</strong>——句柄持有的就是那个数组，所以两条入口不是两套机制，
 * 而是同一个键的两种传法。这是本类存在的支点。
 *
 * <p>由此产生一个<strong>必须写下来的约定</strong>：<strong>交进来的数组改了内容不会被感知</strong>
 * （键没变，就不会重传）。要更新一张图，请传一个<strong>新的数组</strong>——
 * 新数组会重新上传，旧的那张在连续两代没被画到之后自动释放。
 *
 * <p>按身份而不是按内容比较是刻意的：按内容要在每次绘制时扫一遍整张图
 * （100 万像素 = 每帧 4 MB 的读），而收益只是省下"用户把同一张图读了两遍"那点显存。
 * 反过来说，同一个数组配<strong>不同的宽高</strong>是明确的缺陷，所以那一条会抛异常
 * （见 {@link #textureFor}）。
 *
 * <h2>回收：连续两代没被画到就释放</h2>
 * <p>与 {@code ChartRenderer} 对 {@code Series} 的处置同构，包括{@link #GRACE_GENERATIONS}
 * 的取值理由：宽限两代是为了挡住"每隔一帧画一次"那种用法——取 1 的话它会
 * <strong>每画一次就销毁又重建一次</strong>（= 重传整张图），而画面逐像素相同。
 *
 * <p>于是<strong>没有 {@code retain} / {@code release} 这类要记得调的 API</strong>：
 * 不再画的图片自己会被释放。忘记 {@code ImageHandle.dispose()} 的后果退化成
 * "隔两代重传一次"，而不是泄漏显存。
 *
 * <h2>这一层为什么不是可选的</h2>
 * <p>"每帧全量重传"与"只传一次"画出来<strong>逐像素相同</strong>，
 * "释放了"与"泄漏着"也逐像素相同（泄漏要几千帧后才显形）。所以本类专门留了两个计数
 * （{@link #imageTextureCount()} / {@link #takeUploadedImageCount()}）给校验器读数——
 * 与 {@code ChartRenderer.cachedBufferCount()}、{@code takeUploadedBytes()} 是同一类
 * <strong>为断言而存在的观测口</strong>，生产代码不该依赖它们。
 *
 * <p>所有方法必须在 GL 线程上调用（{@link GLAbstraction} 的约束）。
 */
public final class ImageStore implements Disposable {

    /**
     * 宽限几代：最后一次被画到之后，还要经过这么多次帧首才释放。
     *
     * <p>取 2 而不是 1 是<strong>承重</strong>的，不是随手取的（见类文档）。
     * 判据是 {@code ImageStoreTest.每隔一帧画一次的用法不会被反复回收重传}。
     */
    static final int GRACE_GENERATIONS = 2;

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /**
     * 像素数组 → 纹理槽。
     *
     * <p>用 {@link IdentityHashMap} 而不是 {@code HashMap}：数组的 {@code hashCode} /
     * {@code equals} 恰好就是身份实现，所以两者今天<strong>行为完全相同</strong>——
     * 但那是巧合而不是意图。键将来若从 {@code int[]} 换成别的类型（或者有人给数组套一层
     * 包装类并实现了按内容的 equals），{@code HashMap} 会<strong>静默变成按值比较</strong>，
     * 而那意味着每次绘制都要扫一遍整张图。这里把意图写死。
     */
    private final Map<int[], Entry> entries = new IdentityHashMap<>();

    /** 帧序号，每帧首 +1；只用来算"隔了几代"。 */
    private long generation;

    /** 自上次取走以来新上传的图片数（观测口）。 */
    private long uploadedSinceTake;

    private boolean disposed;

    /**
     * 创建缓存。
     *
     * @param gl GL 抽象层
     */
    public ImageStore(GLAbstraction gl) {
        this.gl = gl;
    }

    /**
     * 取出（必要时上传）一个像素数组的纹理。
     *
     * <p>命中缓存时是纯查表，<strong>零 GL 调用</strong>；未命中才上传一次。
     * 无论哪种情形，本方法都会把这一槽标成"本代被用过"，从而刷新它的回收计时。
     *
     * @param pixels 像素数据，{@code 0xAARRGGBB}；同时充当缓存的键（按对象身份）
     * @param width  图像宽度
     * @param height 图像高度
     * @return 可用于 {@link RenderBatch} 的纹理 ID
     * @throws IllegalArgumentException 像素数与面积不匹配，或同一个数组被换了一个尺寸
     * @throws IllegalStateException    本对象已释放
     */
    public int textureFor(int[] pixels, int width, int height) {
        checkNotDisposed();
        requireValidPixels(pixels, width, height);

        Entry entry = entries.get(pixels);
        if (entry == null) {
            entry = new Entry(gl.createPremultipliedTexture(width, height, pixels), width, height);
            entries.put(pixels, entry);
            uploadedSinceTake++;
        } else if (entry.width != width || entry.height != height) {
            // 同一个数组配两个尺寸：留着旧纹理硬画的话，uv 仍是 0..1，每一行会按错误的
            // 跨距解释 —— 画面是一张**扭曲但完全正常**的图。宁可响亮失败。
            throw new IllegalArgumentException(
                    "同一个像素数组被用于两种尺寸：先 " + entry.width + "x" + entry.height
                            + "、后 " + width + "x" + height
                            + "。缓存按数组身份索引，换了尺寸不会重新上传，画出来会是一张"
                            + "按错误跨距解释的图。要换尺寸请传一个新的数组。");
        }
        entry.lastSeenGeneration = generation;
        return entry.textureId;
    }

    /**
     * 帧首：回收连续 {@link #GRACE_GENERATIONS} 代没被画到的纹理，然后进入下一代。
     *
     * <p>由 {@code RenderBatch.beginFrame} 每帧调一次——那是全项目每帧恰好一次的位置。
     * 回收只发生在这里，所以绘制中途不会有纹理被删掉。
     */
    public void beginFrame() {
        checkNotDisposed();
        for (Iterator<Map.Entry<int[], Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            if (generation - entry.lastSeenGeneration < GRACE_GENERATIONS) {
                continue;
            }
            it.remove();
            gl.deleteTexture(entry.textureId);
        }
        generation++;
    }

    /**
     * 立刻释放某个键的纹理（{@link ImageHandle#dispose()} 用）。
     *
     * <p>键不存在时是空操作——包括"这个数组从来没画过"与"已经被回收了"两种情形。
     * 本对象已释放时同样是空操作：那时全部纹理已经被删掉了，而清理之后再清理
     * 不该失败（与 {@link #dispose()} 的幂等同一条口径）。
     *
     * @param pixels 要释放的键
     */
    public void release(int[] pixels) {
        if (disposed) {
            return;
        }
        Entry entry = entries.remove(pixels);
        if (entry != null) {
            gl.deleteTexture(entry.textureId);
        }
    }

    /**
     * 当前缓存的纹理数（观测口）。
     *
     * <p>与 {@code ChartRenderer.cachedBufferCount()} 同类：回收的成效在画面上没有痕迹，
     * 只有这个数能证。"不回收"在显存耗尽之前也不会崩，所以任何像素断言都分不开两者。
     *
     * @return 持有的纹理数；从没画过图片时为 0
     */
    public int imageTextureCount() {
        return entries.size();
    }

    /**
     * 取走并清零"新上传的图片数"（观测口）。
     *
     * <p>判据是"<strong>每一帧恰好传了几张</strong>"：同一张图连续画 N 帧应当只让这个数
     * 涨 1。它与像素无关——而"每帧重传"正是画面上看不出来的那类浪费。
     *
     * @return 自上次调用以来上传的图片数
     */
    public long takeUploadedImageCount() {
        long count = uploadedSinceTake;
        uploadedSinceTake = 0;
        return count;
    }

    /**
     * 释放全部纹理，归还 GL 名字并丢弃全部键。
     *
     * <p>幂等。释放后本对象不可再用（{@link #textureFor} / {@link #beginFrame} 会抛
     * {@link IllegalStateException}，见 {@code Disposable} 契约第 2 条）。
     *
     * <p>丢弃键这件事不只是清理：那些键是<strong>强引用</strong>，留着的话
     * 用户传进来的像素数组会一直被引用着——而 4K 图的一份就是 33 MB。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (Entry entry : entries.values()) {
            gl.deleteTexture(entry.textureId);
        }
        entries.clear();
    }

    /**
     * 像素数组与宽高的校验：<strong>全项目唯一的一份判定</strong>。
     *
     * <p>包级可见是为了让 {@link ImageHandle} 的构造器与这里用同一个判据——
     * 本仓库刚为"共用属性名 ≠ 共用判定"吃过亏（同一个自检开关，两侧各写了一份
     * 解析，其中一份没跟着改，于是脚本照跑而观测一个都不写）。
     *
     * <p>校验必须发生在<strong>创建任何 GL 对象之前</strong>：否则每次失败的调用
     * 都会漏一个已经 {@code glGenTextures} 出来、却没有任何人知道要去删的名字
     * （{@code Disposable} 契约第 3 条）。
     *
     * @param pixels 像素数据
     * @param width  宽度
     * @param height 高度
     */
    static void requireValidPixels(int[] pixels, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图像尺寸必须为正数：" + width + "x" + height);
        }
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数量必须等于图像面积，实际为 " + (pixels == null ? 0 : pixels.length)
                            + "，期望 " + width * height);
        }
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("ImageStore 已释放，不能继续使用");
        }
    }

    /** 一个缓存槽：GL 名字、上传时的尺寸、最后一次被画到的帧序号。 */
    private static final class Entry {

        final int textureId;

        /** 上传时的尺寸，用来拦住"同一个数组换尺寸"。 */
        final int width;

        final int height;

        long lastSeenGeneration;

        Entry(int textureId, int width, int height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }
}
