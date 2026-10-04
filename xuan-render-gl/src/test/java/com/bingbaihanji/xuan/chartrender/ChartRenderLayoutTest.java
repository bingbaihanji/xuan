package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.Axis;
import com.bingbaihanji.xuan.chart.AxisRange;
import com.bingbaihanji.xuan.chart.AxisType;
import com.bingbaihanji.xuan.util.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChartRenderLayout} 的单元测试。
 *
 * <p><b>核心是那条一致性断言</b>：数值 → 屏幕的映射在 CPU（{@code Axis.dataToDisplay}）
 * 与 GPU（顶点着色器里的 uniform 公式）上各有一份实现。两份不一致的表现是
 * <b>刻度线与数据点错开</b>——"看起来只是没对齐"，最难查的那类缺陷。
 * 这里用同一组输入同时跑两份，逐一比对。
 *
 * <p>注：{@link Rect} 暴露的是 <b>public 字段</b> {@code x}/{@code y}/{@code width}/{@code height}，
 * 没有同名的访问器方法，所以下面一律写成 {@code PLOT.width} 而不是 {@code PLOT.width()}。
 */
class ChartRenderLayoutTest {

    private static final Rect PLOT = new Rect(60f, 20f, 800f, 500f);

    private static Axis linearAxis(double min, double max, double px) {
        return new Axis(AxisType.LINEAR, new AxisRange(min, max, "v", "")).setDisplayLength(px);
    }

    @Test
    void y轴映射与Axis一致() {
        Axis y = linearAxis(-1.0, 1.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        // 轴自己的映射是"0 在顶部"（值越大越往下），而绘图区是数学惯例：值越大越往上。
        // 两者的关系必须是精确的：screenY(v) == plotY + plotH - axis.dataToDisplay(v)
        for (double v = -1.0; v <= 1.0; v += 0.125) {
            float expected = (float) (PLOT.y + PLOT.height - y.dataToDisplay(v));
            assertEquals(expected, layout.screenY(v), 1e-3f,
                    "y = " + v + " 处两份映射不一致");
        }
    }

    @Test
    void y轴端点落在绘图区边界上() {
        Axis y = linearAxis(-1.0, 1.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        assertEquals(PLOT.y + PLOT.height, layout.screenY(-1.0), 1e-3f, "最小值应落在底边");
        assertEquals(PLOT.y, layout.screenY(1.0), 1e-3f, "最大值应落在顶边");
        assertEquals(PLOT.y + PLOT.height / 2f, layout.screenY(0.0), 1e-3f, "中值应落在中线");
    }

    @Test
    void x轴按数据下标映射() {
        // 示波器：x 是采样序号，窗口 [100, 200)
        Axis x = linearAxis(100.0, 200.0, PLOT.width);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, x, linearAxis(-1, 1, PLOT.height));

        assertEquals(PLOT.x, layout.screenX(100.0), 1e-3f, "窗口左端的下标落在左边缘");
        assertEquals(PLOT.x + PLOT.width, layout.screenX(200.0), 1e-3f, "窗口右端落在右边缘");
        assertEquals(PLOT.x + PLOT.width / 2f, layout.screenX(150.0), 1e-3f);
    }

    @Test
    void 窗口外的值照样外推() {
        Axis y = linearAxis(0.0, 1.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        // 轴本身就不裁剪（裁剪是 glScissor 的活），映射必须照样给出线性外推的值，
        // 而不是钳到边界——钳了的话超出窗口的曲线会贴着边框画一条假的直线。
        assertTrue(layout.screenY(2.0) < PLOT.y, "超出上界的值应在绘图区上方，而不是被钳在顶边");
        assertTrue(layout.screenY(-1.0) > PLOT.y + PLOT.height, "超出下界的值应在下方");
    }

    @Test
    void 着色器uniform与CPU映射等价() {
        // 直接按顶点着色器里那几行公式算一遍，与 screenY 比对。
        // 着色器用的是 uValueRange（数值窗口的 min/max）与 uPlotRect（x, y, w, h）：
        // 下面四个局部变量就是 uValueRange 的两个分量与 uPlotRect 的 y / w 分量
        // （uPlotRect 的第四个分量是**高**，见 SeriesShaders.LINE_VERTEX）。
        Axis y = linearAxis(-2.0, 6.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        float uValueMin = layout.yMin();
        float uValueMax = layout.yMax();
        float uPlotY = PLOT.y;
        float uPlotH = PLOT.height;

        for (double v = -2.0; v <= 6.0; v += 0.25) {
            float fraction = (float) ((v - uValueMin) / (uValueMax - uValueMin));
            float shaderY = uPlotY + (1.0f - fraction) * uPlotH;
            assertEquals(shaderY, layout.screenY(v), 1e-3f,
                    "着色器公式与 CPU 映射在 y = " + v + " 处不一致");
        }
    }

    @Test
    void 对数轴明确抛异常不做静默错画() {
        Axis log = new Axis(AxisType.LOGARITHMIC, new AxisRange(1, 1000, "v", "")).setDisplayLength(500);
        assertThrows(UnsupportedOperationException.class,
                () -> new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), log),
                "本期 GPU 路径只支持线性换算。静默按线性画对数轴，曲线形状是错的而画面正常");
    }

    @Test
    void 时间轴按线性处理不抛异常() {
        // TIME 轴的值是纪元秒，换算与 LINEAR 完全一样，所以必须放行。
        //
        // 这条断言钉的是类文档里那句"LINEAR 与 TIME 都支持"——没有它，
        // 有人把 requireLinear 收紧成 `type != LINEAR` 时不会有任何测试响，
        // 而症状是"时间轴的图全画不出来"（构造时抛异常），排查方向会跑偏。
        Axis time = new Axis(AxisType.TIME, new AxisRange(0, 3600, "t", "s"))
                .setDisplayLength(PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(
                PLOT, linearAxis(0, 10, PLOT.width), time);

        // 顺带确认它真的按线性算：值域中点落在绘图区中线
        assertEquals(PLOT.y + PLOT.height / 2f, layout.screenY(1800.0), 1e-3f,
                "TIME 轴必须按线性换算，中点落在中线");
    }
}
