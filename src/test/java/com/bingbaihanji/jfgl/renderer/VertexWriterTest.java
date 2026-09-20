package com.bingbaihanji.jfgl.renderer;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    @Test
    void 上一帧写入更多顶点后下一帧仍可写入() {
        // 取过 buffer() 之后内部缓冲区的 limit 不受影响（返回的是副本），
        // 所以下一帧写更多顶点不会像早期实现那样撞上被收窄的 limit。
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        for (int i = 0; i < 10; i++) {
            w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        }
        w.buffer();          // 只收窄副本
        w.reset();
        w.setState(1, 0, 0, 100, 100);
        for (int i = 0; i < 20; i++) {   // 比上一帧多写顶点
            w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        }
        assertEquals(20, w.vertexCount());
    }

    @Test
    void buffer收窄limit后触发扩容不抛异常且旧数据保留() {
        VertexWriter w = new VertexWriter(8, 64);   // 阈值 1，很快就会扩容
        w.setState(1, 0, 0, 100, 100);
        w.vertex(7f, 8f, 0f, 0f, WHITE, 0);
        w.buffer();                                 // 只收窄副本，内部 limit 仍是 capacity
        // 不抛异常即为通过：扩容搬运不得按被收窄的 limit 校验长度
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 40; i++) {
                w.vertex(i, i, 0f, 0f, WHITE, i);
            }
        });
        assertEquals(41, w.vertexCount());
        ByteBuffer b = w.buffer();
        assertEquals(7f, b.getFloat(0), 1e-6f, "扩容后第 0 个顶点的 x 应保留");
        assertEquals(8f, b.getFloat(4), 1e-6f, "扩容后第 0 个顶点的 y 应保留");
    }

    @Test
    void buffer返回的是副本调用方改不动写入器状态() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        for (int i = 0; i < 10; i++) {
            w.vertex(i, i, 0f, 0f, WHITE, i);
        }

        ByteBuffer view = w.buffer();
        assertEquals(10 * VertexFormat.STRIDE_BYTES, view.limit(), "副本的 limit 应为已写入字节数");
        assertEquals(0, view.position());
        assertEquals(7f, view.getFloat(7 * VertexFormat.STRIDE_BYTES), 1e-6f);
        assertEquals(WHITE, view.getInt(16));

        // 在副本上任意折腾 position/limit/字节序，都不得影响写入器的内部状态。
        // （副本与内部缓冲区共享同一段直接内存，所以往副本里写字节会改到顶点数据本身——
        //  这是零拷贝 API 的固有性质；这里要保证的是计数、命令与 limit 不变量不被破坏。
        //  顺带一提：绝对定位读也是按 limit 而非 capacity 判界的，收窄 limit 后就读不到了，
        //  这正是早期直接把内部缓冲区交出去时会踩的坑。）
        view.order(ByteOrder.BIG_ENDIAN);
        view.limit(3);
        assertThrows(IndexOutOfBoundsException.class, () -> view.getInt(16),
                "副本被收窄的 limit 只影响副本自己");
        view.position(1);

        // 写入器继续可用：顶点数正确、再取一次仍是全新的副本
        for (int i = 0; i < 20; i++) {
            w.vertex(i, i, 0f, 0f, WHITE, i);
        }
        assertEquals(30, w.vertexCount());
        ByteBuffer again = w.buffer();
        assertEquals(30 * VertexFormat.STRIDE_BYTES, again.limit());
        assertEquals(0, again.position());
        assertNotSame(view, again, "每次都应返回新的副本，而不是同一个实例");
        // 内部缓冲区没有被副本带偏：第 0 个顶点仍是副本写入前的数据
        assertEquals(0f, again.getFloat(0), 1e-6f);
    }

    @Test
    void 缓冲区写满后继续写入抛出点名补救办法的异常() {
        // 容量与上限都是 8：写满即置位 flush，之后继续写必然越界。
        // 不用清标志就能走到这条路——消费方忽略 isFlushRequested() 一直写就是这个下场。
        VertexWriter w = new VertexWriter(8, 8);
        w.setState(1, 0, 0, 100, 100);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            for (int i = 0; i < 100; i++) {
                w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
            }
        }, "越界必须是点明补救办法的 IllegalStateException，而不是 msg 为 null 的 IndexOutOfBoundsException");
        assertNotNull(ex.getMessage(), "异常消息不得为空");
        assertTrue(ex.getMessage().contains("isFlushRequested()"),
                "异常消息必须点名补救办法，实际为：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("flush"),
                "异常消息必须点名补救办法，实际为：" + ex.getMessage());
    }

    @Test
    void reset后以相同状态setState再写顶点不抛异常() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.reset();
        w.setState(1, 0, 0, 100, 100);   // 与 reset 前完全相同的状态
        w.vertex(1f, 1f, 0f, 0f, WHITE, 0);
        assertEquals(1, w.vertexCount());
    }

    @Test
    void 无拾取ID时不报告可拾取顶点() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 10, 10);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        assertFalse(w.hasPickableVertices(),
                "全部 ID 为 0 时不该声称有可拾取内容——否则每帧都会白跑一趟 ID pass");
    }

    @Test
    void 出现非零ID后报告可拾取顶点() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 10, 10);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        w.vertex(1f, 1f, 0f, 0f, 0xFFFFFFFF, 7);
        assertTrue(w.hasPickableVertices());
    }

    @Test
    void reset清掉可拾取标志() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 10, 10);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 9);
        assertTrue(w.hasPickableVertices());
        w.reset();
        assertFalse(w.hasPickableVertices(),
                "reset 是帧中途 flush 的分批边界，标志必须跟着分批复位");
    }

    @Test
    void 材质不同的相邻绘制不合并() {
        VertexWriter w = new VertexWriter(64);
        w.setState(Material.COLOR, 1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        // 只有材质不同，纹理与裁剪完全一样
        w.setState(Material.SDF_TEXT, 1, 0, 0, 100, 100);
        w.vertex(1f, 1f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(2, w.commandCount(),
                "材质不同必须切命令：否则文本与纯色会被同一条命令画出来，"
                        + "采样的是白色纹理，文字糊成方块");
    }

    @Test
    void 材质相同的相邻绘制合并() {
        VertexWriter w = new VertexWriter(64);
        w.setState(Material.SDF_TEXT, 1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        w.setState(Material.SDF_TEXT, 1, 0, 0, 100, 100);
        w.vertex(1f, 1f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(1, w.commandCount(),
                "材质、纹理、裁剪都相同就该合并——一段连续文本必须是一条 draw call");
    }

    @Test
    void 材质记进命令且五参数重载落成纯色() {
        VertexWriter w = new VertexWriter(64);
        w.setState(Material.SDF_TEXT, 9, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(Material.SDF_TEXT, w.command(0).material());
        assertEquals(9, w.command(0).textureId(), "加材质不该挤掉纹理");

        VertexWriter plain = new VertexWriter(64);
        plain.setState(1, 0, 0, 100, 100);      // 五参数重载
        plain.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(Material.COLOR, plain.command(0).material(),
                "五参数重载是给纯色绘制的，必须落成 COLOR 而不是未定义值");
    }
}
