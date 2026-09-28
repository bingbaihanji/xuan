package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlyphAtlas} 的单测：**零 GL 上下文、零字体依赖**。
 *
 * <p>这是整个文本子系统里唯一能做到这一点的部分——图集只认识 {@link GlyphSource}
 * 这个接口，喂一个返回固定尺寸位图的假实现就能把分配、uv、上传、重置、耗尽全测完。
 * 真字体与真 SDF 由 {@code GlyphRasterizerTest} 和 {@code TextVerifier} 覆盖。
 */
class GlyphAtlasTest {

    /** 假的字形来源：按 key 生成固定尺寸的位图，并记录每个 key 被问了几次。 */
    private static final class FakeSource implements GlyphSource {

        /** 默认位图宽。 */
        int width = 64;

        /** 默认位图高。 */
        int height = 64;

        /** 默认推进宽度（em 像素）。 */
        float advance = 48f;

        /** 尺寸例外：key → {宽, 高}。用来造"空字形"与"比图集还大的字形"。 */
        final Map<Integer, int[]> sizes = new HashMap<>();

        /** 每个 key 被索取的次数。 */
        private final Map<Integer, Integer> calls = new HashMap<>();

        @Override
        public GlyphBitmap pixelsFor(int key) {
            calls.merge(key, 1, Integer::sum);
            int[] wh = sizes.get(key);
            int w = wh == null ? width : wh[0];
            int h = wh == null ? height : wh[1];
            byte[] pixels = new byte[w * h];
            // 用 key 低 8 位填充：这样"上传的内容确实落在自己的槽位里"可以被逐像素断言，
            // 而不是只能断言"上传过一次"。
            Arrays.fill(pixels, (byte) (key & 0xFF));
            return new GlyphBitmap(w, h, -2, -40, advance, pixels);
        }

        int calls(int key) {
            return calls.getOrDefault(key, 0);
        }
    }

    /** 槽位左上角在纹理里的 x。uv 带半点偏移，所以反过来算时要先减掉。 */
    private static int slotX(GlyphSlot slot) {
        return Math.round(slot.u0() * GlyphAtlas.SIZE - 0.5f);
    }

    /** 槽位左上角在纹理里的 y。 */
    private static int slotY(GlyphSlot slot) {
        return Math.round(slot.v0() * GlyphAtlas.SIZE - 0.5f);
    }

    private static void assertNoOverlap(GlyphSlot a, GlyphSlot b) {
        int ax = slotX(a);
        int ay = slotY(a);
        int bx = slotX(b);
        int by = slotY(b);
        boolean disjoint = ax + a.width() <= bx || bx + b.width() <= ax
                || ay + a.height() <= by || by + b.height() <= ay;
        assertTrue(disjoint, "两个字形的槽位重叠了：a=(" + ax + "," + ay + ") "
                + a.width() + "x" + a.height() + "，b=(" + bx + "," + by + ") "
                + b.width() + "x" + b.height());
    }

    @Test
    void 同一个key只生成一次() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot first = atlas.acquire(7);
        GlyphSlot second = atlas.acquire(7);

        assertEquals(1, source.calls(7), "同一个字形第二次索取必须命中缓存，不能重新光栅化");
        assertEquals(first, second, "两次拿到的槽位必须完全相同");
        assertEquals(1, gl.r8Uploads.size(), "命中缓存不该再上传一次");
    }

    @Test
    void 不同的key分配到的槽位不重叠() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        GlyphSlot a = atlas.acquire(1);
        GlyphSlot b = atlas.acquire(2);
        GlyphSlot c = atlas.acquire(3);

        assertNoOverlap(a, b);
        assertNoOverlap(a, c);
        assertNoOverlap(b, c);
    }

    @Test
    void uv带半点偏移且归一化在0到1之间() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        GlyphSlot slot = atlas.acquire(1);
        int w = slot.width();
        int h = slot.height();

        assertTrue(slot.u0() > 0f, "第一个槽位在左上角，左边缘仍要留出半个纹素，不能恰好是 0");
        assertTrue(slot.v0() > 0f, "同理，上边缘也不能恰好是 0");
        assertTrue(slot.u1() < 1f && slot.v1() < 1f);

        // 采样点落在纹素中心（i + 0.5）。于是"首尾两个纹素中心之间的距离"是
        // (w - 1) / SIZE，而不是 w / SIZE。去掉半点偏移后这里会变成 w / SIZE，
        // 差出 1/SIZE —— 恰好能被这两条断言抓住。
        assertEquals((w - 1) / (float) GlyphAtlas.SIZE, slot.u1() - slot.u0(), 1e-6f,
                "uv 少了半点纹素偏移：会采到相邻纹素，表现为边缘发虚或字形轻微错位");
        assertEquals((h - 1) / (float) GlyphAtlas.SIZE, slot.v1() - slot.v0(), 1e-6f);
    }

    @Test
    void 上传的内容落在自己的槽位内() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        GlyphSlot slot = atlas.acquire(5);
        byte[] texture = gl.r8Textures.get(atlas.textureId());
        assertNotNull(texture, "图集必须真的创建了一张纹理");

        int x = slotX(slot);
        int y = slotY(slot);
        for (int row = 0; row < slot.height(); row++) {
            for (int col = 0; col < slot.width(); col++) {
                assertEquals((byte) 5, texture[(y + row) * GlyphAtlas.SIZE + (x + col)],
                        "槽位内 (" + col + "," + row + ") 的像素不是这个字形的数据");
            }
        }
        // 槽位右侧紧邻的一列仍是 0：证明写入没有越界到邻居身上。
        assertEquals(0, texture[y * GlyphAtlas.SIZE + x + slot.width()],
                "写入越界到了槽位右边");
    }

    @Test
    void 一行填满之后换到下一行() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        source.width = GlyphAtlas.SIZE / 2;   // 一行恰好放两个
        source.height = 8;
        // 本行刻意做成**高矮不一**：第一个是 32 高，后两个是 8 高。
        // 若整行等高，"行高 = 本行最大高度"与"行高 = 触发换行那个字形的高度"
        // 恰好相等，换行逻辑写错也测不出来——这条断言就成了橡皮图章。
        source.sizes.put(1, new int[]{GlyphAtlas.SIZE / 2, 32});
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot first = atlas.acquire(1);
        GlyphSlot second = atlas.acquire(2);
        GlyphSlot third = atlas.acquire(3);

        assertEquals(0, slotX(first));
        assertEquals(GlyphAtlas.SIZE / 2, slotX(second), "第二个应当紧挨着第一个");
        assertEquals(slotY(first), slotY(second), "同一行的 v 应当相同");
        assertEquals(0, slotX(third), "第三个应当回到行首");
        assertEquals(32, slotY(third),
                "第三个应当在下一行，行高是本行位图的最大高度（32），"
                        + "而不是触发换行那个字形自己的高度（8）");
    }

    @Test
    void 位图大于图集时抛异常() {
        FakeSource source = new FakeSource();
        source.sizes.put(1, new int[]{GlyphAtlas.SIZE + 1, 4});
        GlyphAtlas atlas = new GlyphAtlas(new FakeGLAbstraction(), source);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> atlas.acquire(1));
        assertTrue(e.getMessage().contains(String.valueOf(GlyphAtlas.SIZE)),
                "消息里要带上图集边长，否则读者不知道该把上限调成多少");
    }

    @Test
    void 货架耗尽时抛异常且下一个帧边界自愈() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());   // 默认 64x64

        // 循环必须有界：若耗尽检测被改坏，无界循环会让测试**挂住**而不是失败，
        // 那比失败更难查。
        int placed = 0;
        IllegalStateException failure = null;
        for (int i = 0; i < 5000 && failure == null; i++) {
            try {
                atlas.acquire(placed);
                placed++;
            } catch (IllegalStateException e) {
                failure = e;
            }
        }
        assertNotNull(failure, "装了 5000 个 64x64 的字形都没报满——4096x4096 只装得下 4096 个");
        assertEquals(64 * 64, placed, "4096 / 64 = 每行 64 个、共 64 行，恰好 4096 个槽位");
        assertTrue(failure.getMessage().contains("已满"), "消息要说清是图集满了：" + failure.getMessage());

        // 帧边界整体重置：分代重置的全部意义就在这里——失效只可能发生在帧边界，
        // 因此同一帧内已经发射过的 uv 绝不会被回收掉（规格 §5.3 那类缺陷）。
        atlas.beginFrame();
        assertDoesNotThrow(() -> atlas.acquire(9999), "下一个帧边界应当整体重置并自愈");
    }

    @Test
    void reset之后字形会重新生成() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot before = atlas.acquire(3);
        atlas.reset();
        GlyphSlot after = atlas.acquire(3);

        assertEquals(2, source.calls(3), "reset 之后缓存必须失效，字形要重新生成");
        assertEquals(2, gl.r8Uploads.size(), "重新生成必然伴随一次重新上传");
        assertEquals(before, after, "重置后货架从左上角重新开始，分配结果应当与第一次相同");
    }

    @Test
    void 空字形只让笔前进不占槽位也不上传() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        source.sizes.put(9, new int[]{0, 0});      // 空格这类字形
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot slot = atlas.acquire(9);

        assertEquals(0, slot.width());
        assertEquals(0, slot.height());
        assertEquals(48f, slot.advance(), 0f, "空字形仍要让笔前进——这是正常情况，不是错误");
        assertTrue(gl.r8Uploads.isEmpty(), "空字形不该上传任何东西");

        // 空字形也不能推动货架游标：下一个真实字形仍然从左上角开始。
        GlyphSlot real = atlas.acquire(1);
        assertEquals(0, slotX(real));
        assertEquals(0, slotY(real));
    }

    @Test
    void 释放之后再acquire会抛异常() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        atlas.dispose();
        atlas.dispose();     // 幂等

        assertEquals(1, gl.deletedTextures.size(), "重复 dispose 不该重复删除纹理");
        assertThrows(IllegalStateException.class, () -> atlas.acquire(1),
                "释放之后再分配必须炸：纹理名字可能已经被驱动发给了别的纹理");
    }
}
