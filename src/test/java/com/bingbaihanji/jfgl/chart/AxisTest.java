package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Axis} 的单测：**纯算术，零依赖**。
 *
 * <p>重点在<strong>互逆性</strong>与<strong>退化范围不除零</strong>。
 * 这两条错了都不会报错：映射反了在静态画面上看不出来（曲线还是画出来了，
 * 只是与坐标对不上），除零得到的 NaN 会让整条曲线连同同批次别的图元一起消失。
 */
class AxisTest {

    private static final double LENGTH = 800.0;

    private static Axis linear(AxisRange range) {
        return new Axis(AxisType.LINEAR, range).setDisplayLength(LENGTH);
    }

    private static void assertRoundTrip(Axis axis, double value) {
        double px = axis.dataToDisplay(value);
        double back = axis.displayToData(px);
        assertEquals(value, back, Math.max(1e-9, Math.abs(value) * 1e-9),
                value + " 经过一次往返变成了 " + back);
    }

    @Test
    void 线性映射把窗口两端映射到零和像素长度() {
        Axis axis = linear(AxisRange.of(10, 50));
        assertEquals(0.0, axis.dataToDisplay(10), 1e-9);
        assertEquals(LENGTH, axis.dataToDisplay(50), 1e-9);
        assertEquals(LENGTH / 2.0, axis.dataToDisplay(30), 1e-9);
    }

    @Test
    void 线性映射是互逆的() {
        Axis axis = linear(AxisRange.of(-3.5, 17.25));
        for (double v : new double[]{-3.5, -1, 0, 0.5, 7.125, 17.25}) {
            assertRoundTrip(axis, v);
        }
        // 反方向也要能往返
        for (double px : new double[]{0, 1, 123.456, 799.999, LENGTH}) {
            assertEquals(px, axis.dataToDisplay(axis.displayToData(px)), 1e-6,
                    px + " 经过一次往返变成了 " + axis.dataToDisplay(axis.displayToData(px)));
        }
    }

    @Test
    void 越出窗口的值仍然线性外推() {
        Axis axis = linear(AxisRange.of(0, 100));
        // 轴不做裁剪：裁剪是渲染侧 glScissor 的事。轴要是自己钳位，
        // 结果就是"曲线在边界上被压平"，比画出去更难查。
        assertEquals(-LENGTH / 10.0, axis.dataToDisplay(-10), 1e-9);
        assertEquals(LENGTH * 1.1, axis.dataToDisplay(110), 1e-9);
    }

    @Test
    void 窗口退化时不除零() {
        Axis axis = linear(AxisRange.of(5, 5));
        double px = assertDoesNotThrow(() -> axis.dataToDisplay(5));
        assertTrue(Double.isFinite(px), "退化范围的映射给出了 " + px);
        assertTrue(Double.isFinite(axis.displayToData(400)));
        assertRoundTrip(axis, 5);

        // 窗口被显式设成退化的值，同样不能除零
        Axis other = linear(AxisRange.of(0, 100)).setWindow(7, 7);
        assertTrue(Double.isFinite(other.dataToDisplay(7)));
        assertTrue(Double.isFinite(other.displayToData(1)));
    }

    @Test
    void 窗口写成反的时自动交换() {
        Axis axis = linear(AxisRange.of(0, 100)).setWindow(80, 20);
        assertEquals(20.0, axis.windowMin(), 1e-9);
        assertEquals(80.0, axis.windowMax(), 1e-9);
        assertEquals(0.0, axis.dataToDisplay(20), 1e-9);
        assertEquals(LENGTH, axis.dataToDisplay(80), 1e-9);
    }

    @Test
    void 对数轴把等比值映射成等距() {
        Axis axis = new Axis(AxisType.LOGARITHMIC, AxisRange.of(1, 1000)).setDisplayLength(LENGTH);
        assertEquals(0.0, axis.dataToDisplay(1), 1e-6);
        assertEquals(LENGTH / 3.0, axis.dataToDisplay(10), 1e-6);
        assertEquals(2 * LENGTH / 3.0, axis.dataToDisplay(100), 1e-6);
        assertEquals(LENGTH, axis.dataToDisplay(1000), 1e-6);
        assertRoundTrip(axis, 3.1622776601683795);
    }

    @Test
    void 对数轴上非正值被钳到下限且不产生NaN() {
        Axis axis = new Axis(AxisType.LOGARITHMIC, AxisRange.of(0, 100)).setDisplayLength(LENGTH);
        for (double v : new double[]{0, -1, -1000}) {
            double px = axis.dataToDisplay(v);
            assertTrue(Double.isFinite(px), "对数轴上 " + v + " 映射出了 " + px);
            assertEquals(0.0, px, 1e-6, "非正值应当钳到窗口下限");
        }
        assertTrue(axis.displayToData(0) > 0, "反查出来的值必须仍在对数轴的定义域里");
        assertTrue(Double.isFinite(axis.displayToData(LENGTH)));
    }

    @Test
    void 刻度装配后位置与映射一致() {
        Axis axis = linear(AxisRange.of(0, 100));
        Tick[] ticks = axis.ticks();
        assertTrue(ticks.length > 0, "0..100 应当有刻度");
        boolean sawMajor = false;
        for (Tick tick : ticks) {
            assertEquals(axis.dataToDisplay(tick.value()), tick.position(), 1e-6,
                    "刻度的位置必须由轴自己的映射算出——刻度生成器不许自己算一遍");
            assertTrue(tick.value() >= 0 && tick.value() <= 100);
            sawMajor |= tick.isMajor();
        }
        assertTrue(sawMajor, "一个主刻度都没有，断言等于空转");

        // 对数轴的刻度同样按映射装配
        Axis log = new Axis(AxisType.LOGARITHMIC, AxisRange.of(1, 1000)).setDisplayLength(LENGTH);
        List<Tick> majors = List.of(log.ticks()).stream().filter(Tick::isMajor).toList();
        assertEquals(4, majors.size());
        assertEquals(LENGTH / 3.0, majors.get(1).position(), 1e-6);
    }
}
