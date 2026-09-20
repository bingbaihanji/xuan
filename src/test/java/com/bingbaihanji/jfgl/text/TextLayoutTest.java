package com.bingbaihanji.jfgl.text;

import com.bingbaihanji.jfgl.renderer.VertexFormat;
import com.bingbaihanji.jfgl.renderer.VertexWriter;
import com.bingbaihanji.jfgl.renderer.ViewTransform;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextLayout} 的单测：**纯算术**，用真的 {@link VertexWriter} 与真的
 * {@link ViewTransform}，不需要 GL 上下文、不需要字体、不需要 stb。
 *
 * <p>断言落在三样东西上：顶点坐标（NDC）、uv、返回的推进宽度。
 * 这三样任何一样错了都不会报错，只会让文字画歪或者字距不对。
 */
class TextLayoutTest {

    private static final int W = 800;
    private static final int H = 600;

    /** 测试用的基础矩阵：用户坐标 1:1 映射到设备像素，与管线一致。 */
    private static ViewTransform transform() {
        ViewTransform t = new ViewTransform();
        t.beginFrame(W, H);
        return t;
    }

    /** 一个非空槽位：尺寸 8×10、偏移 (-1,-9)、推进 12。 */
    private static GlyphSlot slot() {
        return new GlyphSlot(0.1f, 0.2f, 0.15f, 0.25f, -1, -9, 8, 10, 12f);
    }

    /** 一个空槽位（宽高为 0），推进同样是 12。 */
    private static GlyphSlot emptySlot() {
        return new GlyphSlot(0f, 0f, 0f, 0f, 0, 0, 0, 0, 12f);
    }

    private static float ndcX(float userX) {
        return 2f * userX / W - 1f;
    }

    private static float ndcY(float userY) {
        return 1f - 2f * userY / H;
    }

    private static float userY(float ndcY) {
        return (1f - ndcY) * H / 2f;
    }

    private static void assertVertex(ByteBuffer bytes, int index, float userX, float userY) {
        int base = index * VertexFormat.STRIDE_BYTES;
        assertEquals(ndcX(userX), bytes.getFloat(base), 1e-4f, "顶点 " + index + " 的 x");
        assertEquals(ndcY(userY), bytes.getFloat(base + 4), 1e-4f, "顶点 " + index + " 的 y");
    }

    private static VertexWriter writer() {
        VertexWriter writer = new VertexWriter(64);
        writer.setState(1, 0, 0, W, H);
        return writer;
    }

    @Test
    void 顶点数等于非空字形数乘以六() {
        VertexWriter writer = writer();
        List<GlyphSlot> slots = List.of(slot(), slot(), slot());

        float advance = TextLayout.layout(slots, 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        assertEquals(18, writer.vertexCount(), "每个字形两个三角形、六个顶点");
        assertEquals(36f, advance, 1e-3f);
    }

    @Test
    void 四边形坐标符合偏移与缩放() {
        VertexWriter writer = writer();
        float scale = 2f;
        TextLayout.layout(List.of(slot()), 100f, 200f, scale, 0xFFFFFFFF, 0, writer, transform());

        ByteBuffer bytes = writer.buffer();
        // 缩放 2：左 = 100 + (-1)*2 = 98，上 = 200 + (-9)*2 = 182，
        //         右 = 98 + 8*2 = 114，下 = 182 + 10*2 = 202。
        // 四个角点的顺序必须是 左上、右上、右下、左下，第二个三角形共用 0-2 对角线。
        assertVertex(bytes, 0, 98f, 182f);     // 左上
        assertVertex(bytes, 1, 114f, 182f);    // 右上
        assertVertex(bytes, 2, 114f, 202f);    // 右下
        assertVertex(bytes, 3, 98f, 182f);     // 又是左上：第二个三角形的起点
        assertVertex(bytes, 4, 114f, 202f);    // 右下
        assertVertex(bytes, 5, 98f, 202f);     // 左下
    }

    @Test
    void uv取自槽位() {
        VertexWriter writer = writer();
        TextLayout.layout(List.of(slot()), 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        ByteBuffer bytes = writer.buffer();
        // 顶点布局：x(0) y(4) u(8) v(12)
        // quad 的 uv 约定：角点 0/3 取 (u0,v0)，角点 1/4 取 (u1,v1) 的一侧，角点 5 取 (u0,v1)
        assertEquals(0.1f, bytes.getFloat(0 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.2f, bytes.getFloat(0 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
        assertEquals(0.15f, bytes.getFloat(1 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.2f, bytes.getFloat(1 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
        assertEquals(0.15f, bytes.getFloat(4 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.25f, bytes.getFloat(4 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
        assertEquals(0.1f, bytes.getFloat(5 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.25f, bytes.getFloat(5 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
    }

    @Test
    void 推进总量等于各字形推进之和乘以缩放() {
        VertexWriter writer = writer();
        List<GlyphSlot> slots = List.of(slot(), slot(), slot());

        float advance = TextLayout.layout(slots, 100f, 200f, 2.5f, 0xFFFFFFFF, 0, writer, transform());

        // 每个槽位推进 12，三个共 36；缩放 2.5 → 90
        assertEquals(90f, advance, 1e-3f);
        assertNotEquals(36f, advance,
                "用了未缩放的推进值——字号越大字距越紧，而且每个字都会往左偏，越靠后越偏");
    }

    @Test
    void 空槽位只推进不发顶点() {
        VertexWriter writer = writer();
        List<GlyphSlot> slots = List.of(emptySlot(), slot(), emptySlot());

        float advance = TextLayout.layout(slots, 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        assertEquals(6, writer.vertexCount(), "只有中间那个非空字形该发顶点");
        assertEquals(36f, advance, 1e-3f, "三个槽位都在推进，空槽位也不例外");
    }

    @Test
    void 起点是基线而不是文本框左上角() {
        VertexWriter writer = writer();
        TextLayout.layout(List.of(slot()), 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        ByteBuffer bytes = writer.buffer();
        float top = userY(bytes.getFloat(4));
        float bottom = userY(bytes.getFloat(4 * VertexFormat.STRIDE_BYTES + 4));

        // 缩放 1：上 = 200 + (-9) = 191，下 = 191 + 10 = 201
        assertEquals(191f, top, 1e-3f);
        assertEquals(201f, bottom, 1e-3f);
        assertTrue(top < 200f,
                "上沿在基线之上。若把 (x,y) 当成文本框左上角，上沿会正好等于 200——"
                        + "而画面看起来「只是位置偏了一点」");
    }
}
