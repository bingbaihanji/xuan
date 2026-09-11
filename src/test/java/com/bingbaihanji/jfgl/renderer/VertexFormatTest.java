package com.bingbaihanji.jfgl.renderer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VertexFormatTest {

    @Test
    void 步长与偏移自洽() {
        assertEquals(24, VertexFormat.STRIDE_BYTES);
        assertEquals(6, VertexFormat.WORDS_PER_VERTEX);
        assertEquals(0, VertexFormat.OFFSET_POSITION);
        assertEquals(8, VertexFormat.OFFSET_UV);
        assertEquals(16, VertexFormat.OFFSET_COLOR);
        assertEquals(20, VertexFormat.OFFSET_ID);
        assertEquals(VertexFormat.STRIDE_BYTES, VertexFormat.WORDS_PER_VERTEX * 4);
    }

    @Test
    void 打包颜色为预乘整数() {
        // 50% 透明红，预乘后 r=0.5, g=0, b=0, a=0.5
        int packed = VertexFormat.packPremultiplied(1f, 0f, 0f, 0.5f);
        assertEquals(128, (packed >> 24) & 0xFF, 1);
        assertEquals(0, (packed >> 16) & 0xFF);
        assertEquals(0, (packed >> 8) & 0xFF);
        assertEquals(128, packed & 0xFF, 1);
    }

    @Test
    void 打包不透明色不改变分量() {
        int packed = VertexFormat.packPremultiplied(1f, 1f, 1f, 1f);
        assertEquals(0xFFFFFFFF, packed);
    }

    @Test
    void 打包时数值被夹紧到合法范围() {
        int packed = VertexFormat.packPremultiplied(2f, -1f, 0.5f, 2f);
        assertEquals(255, (packed >> 24) & 0xFF);
        assertEquals(0, (packed >> 16) & 0xFF);
        assertEquals(255, packed & 0xFF);
    }

    @Test
    void 零alpha的任何颜色都打包成全零() {
        int packed = VertexFormat.packPremultiplied(1f, 1f, 1f, 0f);
        assertEquals(0, packed);
    }

    @Test
    void DrawCommand保存全部状态() {
        DrawCommand c = new DrawCommand(7, 12, 34, 1, 2, 3, 4);
        assertEquals(7, c.textureId());
        assertEquals(12, c.firstVertex());
        assertEquals(34, c.vertexCount());
        assertEquals(1, c.scissorX());
        assertEquals(2, c.scissorY());
        assertEquals(3, c.scissorWidth());
        assertEquals(4, c.scissorHeight());
    }
}
