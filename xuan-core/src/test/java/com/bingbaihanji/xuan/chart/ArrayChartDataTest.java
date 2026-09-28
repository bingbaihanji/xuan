package com.bingbaihanji.xuan.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ArrayChartData} 的单测。
 *
 * <p>核心断言只有一条：<strong>revision 不变时报"无脏区"</strong>。
 * 它要是坏了，渲染器每帧都会把整份数据重传一遍——画面完全正常，只是白烧带宽，
 * 属于最典型的"没有报错但性能全丢"。
 */
class ArrayChartDataTest {

    private static AxisRange[] ranges(int dims) {
        AxisRange[] out = new AxisRange[dims];
        for (int d = 0; d < dims; d++) {
            out[d] = new AxisRange(0, 10, "维" + d, "u");
        }
        return out;
    }

    private static ArrayChartData data() {
        return new ArrayChartData(ranges(2), new double[][]{{1, 2, 3}, {10, 20, 30}});
    }

    @Test
    void 按维度与下标读回数据() {
        ArrayChartData data = data();
        assertEquals(3, data.itemCount());
        assertEquals(1, data.value(0, 0), 0);
        assertEquals(30, data.value(1, 2), 0);
        assertEquals("维1", data.axisRange(1).name());
        assertEquals("u", data.axisRange(0).unit());
    }

    @Test
    void revision不变时报无脏区() {
        ArrayChartData data = data();
        long revision = data.revision();
        assertTrue(data.dirtyRange(revision).isEmpty(),
                "revision 没变却报了脏区：渲染器会每帧重传整份数据，画面正常但白烧带宽");
        // 比当前 revision 更新的值也算"没有新增"（调用方可能拿到未来的号）
        assertTrue(data.dirtyRange(revision + 5).isEmpty());
    }

    @Test
    void 整体替换后报全区间为脏() {
        ArrayChartData data = data();
        long before = data.revision();
        data.replace(new double[][]{{4, 5, 6}, {40, 50, 60}});

        DirtyRange dirty = data.dirtyRange(before);
        assertFalse(dirty.isEmpty(), "替换之后必须有脏区，否则画面永远停在旧数据上");
        assertEquals(0, dirty.firstDirty());
        assertEquals(3, dirty.lastDirty(), "静态数据的替换是整体的，脏区间必须覆盖全部样本");
        assertEquals(5, data.value(0, 1), 0);
    }

    @Test
    void 替换后revision递增() {
        ArrayChartData data = data();
        long first = data.revision();
        data.replace(new double[][]{{7}, {70}});
        long second = data.revision();
        assertTrue(second > first, "替换必须递增 revision：" + first + " → " + second);
        data.replace(new double[][]{{8}, {80}});
        assertTrue(data.revision() > second);
    }

    @Test
    void 替换成不同长度的数据后脏区覆盖新长度() {
        ArrayChartData data = data();
        long before = data.revision();
        data.replace(new double[][]{{1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}});

        assertEquals(5, data.itemCount());
        DirtyRange dirty = data.dirtyRange(before);
        assertEquals(5, dirty.lastDirty(),
                "脏区必须用**新**长度：用旧长度会让多出来的两个样本永远传不上去");
    }

    @Test
    void 各维度长度不一致时构造抛异常() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ArrayChartData(ranges(2), new double[][]{{1, 2, 3}, {1, 2}}));
        assertTrue(e.getMessage().contains("长度"), "消息要说清是长度不一致：" + e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new ArrayChartData(ranges(2), new double[][]{{1, 2, 3}}));
    }
}
