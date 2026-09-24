package com.bingbaihanji.jfgl.text;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.HashMap;
import java.util.Map;

/**
 * 字形图集：一张固定大小的 {@code GL_R8} 纹理，加一个货架（shelf）分配器。
 *
 * <h2>它不认识字体，也不认识 SDF</h2>
 * <p>它是一个通用的「小位图图集」：给一个 key，返回一个 {@link GlyphSlot}（uv 矩形 + 偏移）；
 * 未命中时通过 {@link GlyphSource} 索取像素。只依赖 {@link GLAbstraction} <strong>接口</strong>，
 * 因此和 {@code PickBuffer} 一样可以用 {@code FakeGLAbstraction} 零 GL 上下文单测。
 *
 * <h2>为什么是"只增不减 + 帧边界整体重置"，而不是逐槽 LRU 淘汰</h2>
 * <p>规格 §3.1 原本写的是「动态图集 + LRU 逐槽淘汰」，本实现改为分代重置，理由：
 * <ol>
 *   <li><strong>逐槽淘汰正是规格 §5.3 那个 bug 的温床</strong>——淘汰掉一个本帧已经
 *       发射过 uv 的槽，会让同一帧内的字形互相覆盖，表现为"某些字偶尔变成别的字"。
 *       要靠"只淘汰本帧没用过的槽"来防，那是给一个本可不存在的问题写防守。</li>
 *   <li>货架分配 + 逐槽回收<strong>必然碎片化</strong>，最终会"还有空间却分配不出"。</li>
 *   <li>对驱动场景（图表，几百个字形）两者都不会触发，区别只在真触发时哪个是对的。</li>
 * </ol>
 * <p><strong>做法</strong>：固定 {@value #SIZE}×{@value #SIZE}（R8，16 MB 显存），
 * 容量 4096 个 64² 槽位，只增不减；分配器判定装不下时置位 {@code exhausted} 并抛异常，
 * 下一个 {@link #beginFrame()} 发现该标志就整体重置。
 * 于是"失效"只可能发生在<strong>帧边界</strong>、且是<strong>整体失效</strong>，
 * §5.3 那类 bug 在结构上不可能发生。
 *
 * <p><strong>为什么不就地重置</strong>：耗尽的那一刻很可能已经往顶点缓冲里发射过
 * 本帧的 uv 了，此刻重置等于把那些 uv 指向别人的字形——正是上面第 1 条要避免的事。
 * 所以帧中途唯一安全的动作是<strong>抛异常</strong>，把问题暴露在它发生的地方。
 *
 * <h2>线程</h2>
 * <p>非线程安全，且必须在 GL 线程上使用（{@link GLAbstraction} 的约定）。
 */
public final class GlyphAtlas implements Disposable {

    /**
     * 图集边长（像素）。
     *
     * <p>取 4096 是显存与容量的折中：R8 单通道，16 MB 显存，装得下 4096 个 64² 的槽位。
     * 单字形位图的最大边长是「em 尺寸 + 2×spread」（见 {@link GlyphRasterizer#EM_SIZE}
     * 与 {@link SdfGenerator#SPREAD}），图集边长必须<strong>不小于</strong>它，
     * 否则一个字形的槽位就放不进一行。
     */
    public static final int SIZE = 4096;

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /** 字形来源。 */
    private final GlyphSource source;

    /** 图集纹理的 ID。 */
    private final int texture;

    /** 已分配的槽位，key → 槽位。 */
    private final Map<Integer, GlyphSlot> slots = new HashMap<>();

    /** 当前货架行的起始 x。 */
    private int shelfX = 0;

    /** 当前货架行的起始 y。 */
    private int shelfY = 0;

    /** 当前货架行的高度（本行内已放入位图的最大高度）。 */
    private int shelfHeight = 0;

    /** 上一次分配是否判定为"装不下"。为 true 时下一个 {@link #beginFrame()} 会整体重置。 */
    private boolean exhausted = false;

    /** 是否已释放。 */
    private boolean disposed = false;

    /**
     * 创建图集并分配纹理。
     *
     * <p>必须在 GL 线程（且 GL 上下文已 current）上调用。
     *
     * @param gl     GL 抽象层
     * @param source 字形来源
     * @throws IllegalArgumentException 任一参数为 {@code null} 时
     */
    public GlyphAtlas(GLAbstraction gl, GlyphSource source) {
        if (gl == null) {
            throw new IllegalArgumentException("gl 不能为 null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source 不能为 null");
        }
        this.gl = gl;
        this.source = source;
        this.texture = gl.createR8Texture(SIZE, SIZE);
    }

    /**
     * 返回图集纹理的 ID。
     *
     * <p>{@link #dispose()} 之后返回的是一个已被删除的名字——调用方不应在释放后使用它。
     *
     * @return 纹理 ID
     */
    public int textureId() {
        return texture;
    }

    /**
     * 帧边界：若上一帧判定过"装不下"，就整体重置。
     *
     * <p>整体重置会让<strong>此前发射过的全部 uv 失效</strong>，所以只能在帧边界做——
     * 这也是本类选择"分代重置"而不是"逐槽淘汰"的全部理由（见类说明）。
     */
    public void beginFrame() {
        if (exhausted) {
            resetShelves();
            slots.clear();
            exhausted = false;
        }
    }

    /**
     * 取一个字形的槽位，未命中则向 {@link GlyphSource} 索取并上传。
     *
     * @param key 字形标识，含义由 {@link GlyphSource} 实现定义
     * @return 槽位；空字形返回宽高为 0 的槽位
     * @throws IllegalStateException    已释放后调用时；货架装不下时
     * @throws IllegalArgumentException 单个位图大于图集边长时
     */
    public GlyphSlot acquire(int key) {
        if (disposed) {
            throw new IllegalStateException(
                    "字形图集已释放：dispose() 之后不能再 acquire（纹理名字可能已经发给别人了）");
        }
        GlyphSlot cached = slots.get(key);
        if (cached != null) {
            return cached;
        }
        GlyphSlot slot = allocate(source.pixelsFor(key));
        slots.put(key, slot);
        return slot;
    }

    /**
     * 丢弃全部槽位并从左上角重新开始分配。
     *
     * <p>已经发射出去的 uv 在调用之后<strong>立即失效</strong>，因此不要在帧中途调用。
     * 正常情况下不需要手动调用——{@link #beginFrame()} 会在需要时自己重置。
     */
    public void reset() {
        slots.clear();
        resetShelves();
        exhausted = false;
    }

    /** 释放图集纹理。重复调用无副作用，释放后不可再 {@link #acquire}。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        gl.deleteTexture(texture);
        slots.clear();
        disposed = true;
    }

    /**
     * 在货架里分配一块 {@code w×h} 的区域并上传像素，返回槽位。
     *
     * @param bitmap 字形位图
     * @return 槽位
     */
    private GlyphSlot allocate(GlyphBitmap bitmap) {
        int w = bitmap.width();
        int h = bitmap.height();
        float advance = bitmap.advance();

        // 空字形：只让笔前进，不占槽位、不上传、不发顶点。这是正常情况而不是错误——
        // 若把它当成"分配一个 0 大小的槽"，货架的换行判断会被 0 宽度的位图搅乱
        // （明明放了东西的行高却不增长），下一个字形就会压在它上面。

        if (w == 0 || h == 0) {
            return new GlyphSlot(0f, 0f, 0f, 0f,
                    bitmap.offsetX(), bitmap.offsetY(), 0, 0, advance);
        }

        // 单字形必须能装进一行，否则分配会死循环。规格 §5.7 把这条列进了"必须做对的细节"。
        if (w > SIZE || h > SIZE) {
            throw new IllegalArgumentException(
                    "字形位图 " + w + "x" + h + " 大于图集边长 " + SIZE
                            + "：请调大 GlyphAtlas.SIZE（会线性增加显存），"
                            + "或调小 GlyphRasterizer.EM_SIZE / SdfGenerator.SPREAD");
        }

        if (shelfX + w > SIZE) {
            // 换行：上一行的高度就是新行的起始 y 增量
            shelfY += shelfHeight;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + h > SIZE) {
            // 不在这里重置：此刻很可能已经发射过本帧的 uv，重置会让它们指向别人的字形。
            // 只置位标志，交给下一个 beginFrame()（帧边界，整体失效是安全的）。
            exhausted = true;
            throw new IllegalStateException(
                    "字形图集已满（" + SIZE + "x" + SIZE + "）：本帧需要的字形超过图集的槽位容量。"
                            + "请调大 GlyphAtlas.SIZE，或减少单帧内出现的不同字形数"
                            + "（例如把文本拆到多帧绘制）");
        }

        int x = shelfX;
        int y = shelfY;
        shelfX += w;
        if (h > shelfHeight) {
            shelfHeight = h;
        }

        gl.uploadR8SubImage(texture, x, y, w, h, bitmap.pixels());

        // 采样点落在纹素中心（i + 0.5）：uv 必须按"首尾纹素中心"给出，
        // 否则会采到相邻纹素。规格 §5.4。
        float u0 = (x + 0.5f) / SIZE;
        float v0 = (y + 0.5f) / SIZE;
        float u1 = (x + w - 0.5f) / SIZE;
        float v1 = (y + h - 0.5f) / SIZE;
        return new GlyphSlot(u0, v0, u1, v1,
                bitmap.offsetX(), bitmap.offsetY(), w, h, advance);
    }

    /** 把货架游标带回左上角。 */
    private void resetShelves() {
        shelfX = 0;
        shelfY = 0;
        shelfHeight = 0;
    }
}
