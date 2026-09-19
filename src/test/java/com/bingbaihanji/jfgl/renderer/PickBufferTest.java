package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PickBufferTest {

    /** 造一个 8x4 的拾取缓冲，虚拟屏幕初始全 0。 */
    private static PickBuffer buffer(FakeGLAbstraction gl) {
        gl.setScreenBottomUp(8, 4, new int[8 * 4]);
        return new PickBuffer(gl, 8, 4);
    }

    @Test
    void 区域查询的坐标是用户坐标而非上下镜像() {
        // 安排：用户坐标 (5, 1) 处是 id=42。用户坐标原点在左上、y 向下，
        // 所以它在 GL 行序里是倒数第二行。读回来若还在 (5, 1) 说明翻转正确；
        // 若漏翻转会得到 (5, 2)——同样「看起来合理」，所以必须断言精确坐标。
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(5, 1, 42);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        assertEquals(1, hits.size());
        assertEquals(42, hits.get(0).id());
        assertEquals(5, hits.get(0).x());
        assertEquals(1, hits.get(0).y(), "翻转写错会得到上下镜像的 y，且不报错");
    }

    @Test
    void 首次出现按从上到下的行序选取() {
        // 同一个 id 出现在更靠下的 (3, 3) 与更靠上的 (3, 0)，
        // 「按行扫描首次出现」应当取更靠上的那个。
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(3, 3, 42);
        gl.setUserPixel(3, 0, 42);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).y(),
                "扫描序反了会取到更靠下的那个——坐标合法但位置是错的");
    }

    @Test
    void 同一行内取最左边的() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(6, 2, 42);
        gl.setUserPixel(1, 2, 42);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        assertEquals(1, hits.get(0).x());
    }

    @Test
    void 结果按ID升序且每个ID只出现一次() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(0, 0, 9);
        gl.setUserPixel(1, 0, 3);
        gl.setUserPixel(2, 0, 9);   // 9 的第二次出现，应被忽略
        gl.setUserPixel(3, 0, 5);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        // 必须连坐标一起断言，不能只断言 ID 集合：去掉判重之后，
        // 9 会被后来的 (2, 0) 覆盖成 (2, 0)，但「键 9 存在且只出现一次」
        // 依然成立，ID 列表分毫不变——只看 ID 就是恒真检查，变异会存活。
        // 判重保护的是「首次出现的坐标」，断言必须落在坐标上。
        assertEquals(List.of(new PickPixel(3, 1, 0),
                        new PickPixel(5, 3, 0),
                        new PickPixel(9, 0, 0)),
                hits);
    }

    @Test
    void 零号像素被忽略() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(0, 0, 0);   // 0 = 没命中，不该出现在结果里

        assertTrue(pb.readRect(0, 0, 8, 4).isEmpty());
    }

    @Test
    void 退化区域返回空列表() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(0, 0, 42);

        assertTrue(pb.readRect(0, 0, 0, 4).isEmpty(), "宽度为 0");
        assertTrue(pb.readRect(0, 0, 4, 0).isEmpty(), "高度为 0");
        assertTrue(pb.readRect(-100, -100, 1, 1).isEmpty(), "完全在缓冲外");
    }

    @Test
    void 越界区域被裁剪到缓冲内且坐标仍正确() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(7, 3, 42);          // 右下角

        // 从 (5,2) 起查 100x100，应当裁剪到 8x4 的范围内而不是越界读
        List<PickPixel> hits = pb.readRect(5, 2, 100, 100);

        assertEquals(1, hits.size());
        assertEquals(7, hits.get(0).x());
        assertEquals(3, hits.get(0).y());
    }

    @Test
    void 读完之后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.boundFramebuffer = 7;
        PickBuffer pb = buffer(gl);
        pb.readRect(0, 0, 8, 4);
        assertEquals(7, gl.boundFramebuffer,
                "读回后必须恢复绑定，否则后续绘制会画进拾取缓冲");
    }

    @Test
    void 清空之后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.boundFramebuffer = 7;
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;          // 构造内部的清空会把绑定还回来，这里重申一次
        pb.clear();
        assertEquals(7, gl.boundFramebuffer,
                "清空后必须恢复绑定，否则后续绘制会画进拾取缓冲");
    }

    @Test
    void 读点之后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;
        pb.readPixel(1, 1);
        assertEquals(7, gl.boundFramebuffer);
    }

    @Test
    void 抛错时仍恢复帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;
        gl.throwOnIntegerCall = true;
        assertThrows(IllegalStateException.class, () -> pb.readRect(0, 0, 8, 4));
        assertEquals(7, gl.boundFramebuffer,
                "读回抛错也必须把绑定还回去——否则下一帧颜色全画进拾取缓冲，且不报错");
    }

    @Test
    void 构造后缓冲是全零() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        // 预置非零，模拟「新纹理内容是未定义值」的驱动行为
        gl.setScreenBottomUp(8, 4, new int[]{-1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1});
        PickBuffer pb = new PickBuffer(gl, 8, 4);
        assertTrue(pb.readRect(0, 0, 8, 4).isEmpty(),
                "构造时必须清一次；不清就是「在多数驱动上看起来能跑」");
    }

    @Test
    void 重建失败时旧缓冲原封不动() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(1, 1, 42);
        assertThrows(IllegalArgumentException.class, () -> pb.ensureSize(0, 0));
        assertTrue(gl.deletedFramebuffers.isEmpty(),
                "先建后弃：新缓冲建失败时旧缓冲不该被释放");
        assertEquals(42, pb.readPixel(1, 1), "旧缓冲应当仍然可用");
    }

    @Test
    void 释放后读点抛异常而不是操作默认帧缓冲() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        pb.dispose();
        assertThrows(IllegalStateException.class, () -> pb.readPixel(1, 1));
    }

    @Test
    void 清空抛错时仍恢复帧缓冲绑定() {
        // 这条不能靠「读回抛错」那条代替：那条走的是 readRect，根本到不了 clear()。
        // 而不注入故障的话，直线代码和 try/finally 的恢复行为完全一致，
        // 「清空之后恢复原先的帧缓冲绑定」区分不了两者——守卫无人防守。
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;
        gl.throwOnIntegerCall = true;
        assertThrows(IllegalStateException.class, () -> pb.clear());
        assertEquals(7, gl.boundFramebuffer,
                "清空抛错也必须把绑定还回去——否则后续绘制全画进拾取缓冲，且不报错");
    }

    @Test
    void 清空会临时关掉裁剪并在之后恢复() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.scissorEnabled = true;
        // 假 GL 的 clearIntegerColor 在裁剪开启时抛异常，所以这行本身就是断言：
        // 真 GL 上同样的错误是静默的（只清掉裁剪盒内那一块）。
        pb.clear();
        assertTrue(gl.scissorEnabled, "清空结束必须把裁剪状态恢复原样");
    }

    @Test
    void 构造与重建清空时也关掉裁剪() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.setScreenBottomUp(8, 4, new int[8 * 4]);
        gl.scissorEnabled = true;
        PickBuffer pb = new PickBuffer(gl, 8, 4);   // 构造里会 clear()
        pb.ensureSize(16, 16);                       // 重建后也会 clear()
        assertTrue(gl.scissorEnabled, "两处清空都必须恢复裁剪状态");
    }
}
