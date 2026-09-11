package com.bingbaihanji.jfgl.renderer;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class VertexWriterTest {

    private static final int WHITE = 0xFFFFFFFF;

    @Test
    void 初始状态无顶点无命令() {
        VertexWriter w = new VertexWriter(16);
        assertEquals(0, w.vertexCount());
        assertEquals(0, w.commandCount());
    }

    @Test
    void 追加顶点写入正确数据() {
        VertexWriter w = new VertexWriter(16);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(2f, 3f, 0.5f, 0.25f, WHITE, 0);
        assertEquals(1, w.vertexCount());

        ByteBuffer b = w.buffer();
        assertEquals(2f, b.getFloat(0), 1e-6f);
        assertEquals(3f, b.getFloat(4), 1e-6f);
        assertEquals(0.5f, b.getFloat(8), 1e-6f);
        assertEquals(0.25f, b.getFloat(12), 1e-6f);
        assertEquals(WHITE, b.getInt(16));
        assertEquals(0, b.getInt(20));
    }

    @Test
    void 连续同状态只产生一条命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        w.vertex(0f, 1f, 0f, 0f, WHITE, 0);
        assertEquals(1, w.commandCount());
        assertEquals(3, w.command(0).vertexCount());
    }

    @Test
    void 纹理变化开启新命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.setState(2, 0, 0, 100, 100);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        assertEquals(2, w.commandCount());
        assertEquals(1, w.command(0).textureId());
        assertEquals(1, w.command(0).vertexCount());
        assertEquals(2, w.command(1).textureId());
        assertEquals(1, w.command(1).firstVertex());
    }

    @Test
    void 裁剪变化开启新命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.setState(1, 10, 10, 50, 50);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        assertEquals(2, w.commandCount());
    }

    @Test
    void 状态变回原值不会与更早的命令合并() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.setState(2, 0, 0, 100, 100);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(2f, 0f, 0f, 0f, WHITE, 0);
        assertEquals(3, w.commandCount(), "绘制顺序即 z 序，不允许重排合并");
    }

    @Test
    void quad产生两个三角形共六个顶点() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.quad(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f, 1f, 1f, WHITE, 0);
        assertEquals(6, w.vertexCount());
        ByteBuffer b = w.buffer();
        assertEquals(0f, b.getFloat(0), 1e-6f);
        assertEquals(10f, b.getFloat(24), 1e-6f, "顶点1 的 x 应为右上角 10");
        assertEquals(0f, b.getFloat(120), 1e-6f, "顶点5 回到左下角 x=0");
        assertEquals(0f, b.getFloat(120 + 8), 1e-6f, "顶点5 的 u 应为 u0=0");
        assertEquals(1f, b.getFloat(120 + 12), 1e-6f, "顶点5 的 v 应为 v1=1");
    }

    @Test
    void reset清空顶点与命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.reset();
        assertEquals(0, w.vertexCount());
        assertEquals(0, w.commandCount());
    }

    @Test
    void 未设置状态时写入顶点抛出异常() {
        VertexWriter w = new VertexWriter(64);
        assertThrows(IllegalStateException.class,
                () -> w.vertex(0f, 0f, 0f, 0f, WHITE, 0));
    }

    @Test
    void 容量耗尽后仍能完整写入一个四边形() {
        // 用一个很小的上限，让兜底路径在几次写入内就被触发，无需真的写满 1M 顶点
        VertexWriter w = new VertexWriter(8, 16);
        w.setState(1, 0, 0, 100, 100);

        int guard = 0;
        while (!w.isFlushRequested() && guard++ < 1000) {
            w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        }
        assertTrue(w.isFlushRequested(), "持续写入应最终触发 flush 请求");

        int before = w.vertexCount();
        // 不抛异常即为通过：置位 flush 之后仍必须容得下整个四边形
        assertDoesNotThrow(() -> w.quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f,
                0f, 0f, 1f, 1f, WHITE, 0));
        assertEquals(before + 6, w.vertexCount());
    }
}
