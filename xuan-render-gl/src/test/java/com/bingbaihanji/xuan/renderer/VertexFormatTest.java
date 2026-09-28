package com.bingbaihanji.xuan.renderer;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class VertexFormatTest {

    @Test
    void 顶点布局是32字节8字() {
        assertEquals(32, VertexFormat.STRIDE_BYTES);
        assertEquals(8, VertexFormat.WORDS_PER_VERTEX);
        assertEquals(VertexFormat.STRIDE_BYTES, VertexFormat.WORDS_PER_VERTEX * 4);

        // 下面四条偏移断言原先在「步长与偏移自洽」里，改成 32 字节时原样保留：
        // 它们与步长无关，删掉不会让新布局更容易写错，只会让"已有偏移被挪动了"失去守卫。
        // 挪动偏移正是本类最典型的静默缺陷——画面只是整体错位或读到垃圾，不报任何错。
        assertEquals(0, VertexFormat.OFFSET_POSITION);
        assertEquals(8, VertexFormat.OFFSET_UV);
        assertEquals(16, VertexFormat.OFFSET_COLOR);
        assertEquals(20, VertexFormat.OFFSET_ID);
    }

    @Test
    void 抗锯齿边距在偏移24处() {
        assertEquals(24, VertexFormat.OFFSET_EDGE);
        // 它必须紧跟在 20 处的 uint 之后、且恰好占满到 32
        assertEquals(VertexFormat.OFFSET_EDGE + 8, VertexFormat.STRIDE_BYTES);
        assertEquals(VertexFormat.OFFSET_ID + 4, VertexFormat.OFFSET_EDGE);
    }

    @Test
    void 打包颜色为预乘整数() {
        // 50% 透明红，预乘后 r=0.5, g=0, b=0, a=0.5。
        // 整数布局自高字节到低字节为 A,B,G,R（配合小端写入，内存里才是 R,G,B,A）
        int packed = VertexFormat.packPremultiplied(1f, 0f, 0f, 0.5f);
        assertEquals(128, (packed >> 24) & 0xFF, "最高字节应为 A");
        assertEquals(0, (packed >> 16) & 0xFF, "次高字节应为 B");
        assertEquals(0, (packed >> 8) & 0xFF, "次低字节应为 G");
        assertEquals(128, packed & 0xFF, "最低字节应为 R");
    }

    @Test
    void 打包不透明色不改变分量() {
        int packed = VertexFormat.packPremultiplied(1f, 1f, 1f, 1f);
        assertEquals(0xFFFFFFFF, packed);
    }

    @Test
    void 打包时数值被夹紧到合法范围() {
        // 夹紧后 r=1, g=0, b=0.5, a=1；再按 alpha=1 预乘，分量不变。
        // 整数布局自高字节到低字节为 A,B,G,R
        int packed = VertexFormat.packPremultiplied(2f, -1f, 0.5f, 2f);
        assertEquals(255, (packed >> 24) & 0xFF, "最高字节应为夹紧后的 A=1");
        assertEquals(128, (packed >> 16) & 0xFF, "次高字节应为 B=0.5");
        assertEquals(0, (packed >> 8) & 0xFF, "次低字节应为夹紧后的 G=0");
        assertEquals(255, packed & 0xFF, "最低字节应为夹紧后的 R=1");
    }

    /**
     * 字节级回归：着色器读到的通道顺序由<strong>内存字节</strong>决定，而不是整数的位序。
     *
     * <p>写入方 {@link VertexWriter} 用小端 {@code ByteBuffer} 写这个 int，
     * {@code glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, normalized, ...)} 再按地址升序
     * 读成 {@code vec4(r,g,b,a)}。所以「不透明绿」的内存字节必须恰好是 {@code [0,255,0,255]}。
     * 此前打包成 {@code (r<<24)|(g<<16)|(b<<8)|a}，内存里是 {@code [a,b,g,r]}，
     * 不透明绿被读成 {@code (1,0,1,0)}——alpha=0 的品红，肉眼完全看不见。
     */
    @Test
    void 打包后的内存字节序为RGBA() {
        int packed = VertexFormat.packPremultiplied(0f, 1f, 0f, 1f);   // 不透明绿
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, packed);
        assertEquals(0, b.get(0) & 0xFF, "字节0 应为 R");
        assertEquals(255, b.get(1) & 0xFF, "字节1 应为 G");
        assertEquals(0, b.get(2) & 0xFF, "字节2 应为 B");
        assertEquals(255, b.get(3) & 0xFF, "字节3 应为 A");
    }

    @Test
    void 零alpha的任何颜色都打包成全零() {
        int packed = VertexFormat.packPremultiplied(1f, 1f, 1f, 0f);
        assertEquals(0, packed);
    }

    @Test
    void DrawCommand保存全部状态() {
        // 材质刻意用 SDF_TEXT 而不是 COLOR：用默认值做断言的话，
        // "忘了把材质传进命令"这个变异会存活。
        DrawCommand c = new DrawCommand(7, Material.SDF_TEXT, 12, 34, 1, 2, 3, 4);
        assertEquals(7, c.textureId());
        assertEquals(Material.SDF_TEXT, c.material(),
                "材质进了合批判据，就必须进命令——否则文本会被当成纯色画");
        assertEquals(12, c.firstVertex());
        assertEquals(34, c.vertexCount());
        assertEquals(1, c.scissorX());
        assertEquals(2, c.scissorY());
        assertEquals(3, c.scissorWidth());
        assertEquals(4, c.scissorHeight());
    }
}
