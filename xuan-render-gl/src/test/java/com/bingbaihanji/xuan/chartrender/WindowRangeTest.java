package com.bingbaihanji.xuan.chartrender;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WindowRange} 的单元测试。
 *
 * <p>这些断言全部落在<b>具体区间</b>上，不是"不为空"这种覆盖性检查——
 * 覆盖性检查对"少画了一段"和"多画了一段"同样成立，是橡皮图章。
 */
class WindowRangeTest {

    /** 环容量 8，已经写满：有效数据下标 [0, 8)，可画的线段是 [0, 6]。 */
    private static final int CAP = 8;

    @Test
    void 环绕未发生时只有一段() {
        // 窗口 [1, 4) -> 线段 1,2,3，物理槽位 1..3
        List<WindowRange.Segment> segs = WindowRange.compute(1.0, 4.0, 8L, CAP);
        assertEquals(1, segs.size());
        assertEquals(new WindowRange.Segment(1, 1L, 3), segs.get(0));
    }

    @Test
    void 环绕发生时切成两段() {
        // writeIndex=12, cap=8 -> 有效 [4,12)，可画线段 [4,11)
        // 窗口 [6,12) 取交 -> [6,11)，共 5 个
        // firstSlot = 6 & 7 = 6，6 + 5 = 11 > 8 -> 环绕
        List<WindowRange.Segment> segs = WindowRange.compute(6.0, 12.0, 12L, CAP);
        assertEquals(2, segs.size());
        assertEquals(new WindowRange.Segment(6, 6L, 2), segs.get(0));   // 槽位 6,7
        assertEquals(new WindowRange.Segment(0, 8L, 3), segs.get(1));   // 槽位 0,1,2
    }

    @Test
    void 恰好填满时不切段() {
        // 边界：firstSlot + count == capacity 必须走单段分支
        List<WindowRange.Segment> segs = WindowRange.compute(6.0, 9.0, 9L, CAP);
        assertEquals(1, segs.size(), "firstSlot + count == capacity 时必须只有一段");
        assertEquals(new WindowRange.Segment(6, 6L, 2), segs.get(0));
    }

    @Test
    void 窗口在有效数据之前时为空() {
        // writeIndex=100，环容量 8 -> 有效 [92, 100)
        List<WindowRange.Segment> segs = WindowRange.compute(10.0, 20.0, 100L, CAP);
        assertTrue(segs.isEmpty(), "窗口完全落在被覆盖的旧数据上，什么都不该画");
    }

    @Test
    void 窗口在已写数据之后时为空() {
        List<WindowRange.Segment> segs = WindowRange.compute(50.0, 60.0, 20L, CAP);
        assertTrue(segs.isEmpty(), "窗口落在还没采到的未来，什么都不该画");
    }

    @Test
    void 窗口大于环容量时被裁到有效范围() {
        // writeIndex=12, cap=8 -> 有效[4,12)，可画[4,11)
        List<WindowRange.Segment> segs = WindowRange.compute(-1000.0, 1000.0, 12L, CAP);
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(7, total, "有效线段是 [4, 11)，共 7 个");
        assertEquals(4L, segs.get(0).firstDataIndex(), "第一段的起始数据下标必须是 4");
    }

    @Test
    void 最后一个线段不可画因为右端还没采到() {
        // writeIndex=5：样本 [0,5) 已写，可画线段 [0, 4) —— 线段 4 需要样本 5，还没写
        List<WindowRange.Segment> segs = WindowRange.compute(0.0, 100.0, 5L, CAP);
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(4, total, "线段数必须是 writeIndex - 1，不是 writeIndex");
    }

    @Test
    void 空窗口为空() {
        assertTrue(WindowRange.compute(5.0, 5.0, 100L, CAP).isEmpty());
        assertTrue(WindowRange.compute(5.0, 4.0, 100L, CAP).isEmpty());
    }

    @Test
    void 点数版本不排除最后一个点() {
        // 折线版本要 writeIndex - 2（线段需要右端），点数版本到 writeIndex - 1。
        // 这个差别就是两个方法的全部区别，必须专门钉住。
        List<WindowRange.Segment> segs = WindowRange.computePoints(0.0, 100.0, 5L, CAP);
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(5, total, "散点要把 5 个点都画出来，不是 4 个");
    }

    @Test
    void 点数版本跨环绕也切两段() {
        List<WindowRange.Segment> segs = WindowRange.computePoints(6.0, 12.0, 12L, CAP);
        assertEquals(2, segs.size());
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(6, total, "可见点是 [6, 12) 与有效范围 [4, 12) 的交，共 6 个");
    }

    @Test
    void 小数窗口边界向上取整() {
        // 窗口左边界 1.5 -> 第一个可画的线段是 2（线段 1 从 1 开始，左端落在窗口外）
        List<WindowRange.Segment> segs = WindowRange.compute(1.5, 4.0, 8L, CAP);
        assertEquals(1, segs.size());
        assertEquals(2L, segs.get(0).firstDataIndex());
        assertEquals(2, segs.get(0).instanceCount());   // 线段 2、3
    }

    @Test
    void 容量非2的幂时抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> WindowRange.compute(0.0, 10.0, 100L, 12));
    }
}
