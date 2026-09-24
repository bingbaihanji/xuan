package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Chart} / {@link Layer} / {@link Series} / {@link ChartType} 的装配测试。
 *
 * <p>只断言一件事：<strong>装配顺序即绘制顺序</strong>。
 * 本项目 CLAUDE.md 里写死的规则是"绘制顺序即 2D 的 z 序，绝不重排"，
 * 而装配层要是把层内顺序与层间顺序搞反（先排完所有层的第一个系列、再排第二个……），
 * 画面会变成"后加的层被压在下面"，看起来像是渲染器的 bug。
 */
class ChartTest {

    private static ArrayChartData data() {
        return new ArrayChartData(new AxisRange[]{AxisRange.of(0, 10)},
                new double[][]{{1, 2, 3}});
    }

    private static Series series(String name) {
        return new Series(name, data(), ChartType.LINE);
    }

    @Test
    void 图层与系列的顺序就是绘制顺序() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));
        // 第一层必须放两个系列，这个测试才看得见"层序优先"。
        // 若第一层只有一个系列，"层序优先"与"层内优先"（先排完所有层的第一个系列、
        // 再排第二个……）排出来的结果完全一样——a0,(b),a1 退化成了 a0,a1,b。
        // 这一点是变异验证查出来的：把 allSeries() 换成层内优先的实现，
        // 单系列的第一层下测试照样全绿。别把它"简化"回一个系列。
        chart.addLayer("底图").add(series("a0")).add(series("a1"));
        chart.addLayer("数据").add(series("b")).add(series("c"));

        List<Series> flat = chart.allSeries();
        assertEquals(4, flat.size());
        assertEquals(List.of("a0", "a1", "b", "c"),
                flat.stream().map(Series::name).toList(),
                "顺序必须是「层序优先、层内其次」。反过来（先排完所有层的第一个系列）"
                        + "会让后加的层被压在下面，看起来像渲染器画错了");
        assertEquals("底图", chart.layers().get(0).name());
        assertEquals("数据", chart.layers().get(1).name());
        assertEquals(List.of("a0", "a1"),
                chart.layers().get(0).series().stream().map(Series::name).toList());
        assertEquals(List.of("b", "c"),
                chart.layers().get(1).series().stream().map(Series::name).toList());
    }

    @Test
    void 同一层里重复添加同一个系列会抛异常() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));
        Series s = series("a");
        Layer layer = chart.addLayer("层");
        layer.add(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> layer.add(s),
                "同一个系列加两次会被画两遍——在带透明度的图元上表现为颜色莫名变深，"
                        + "而且没有任何报错");
        assertTrue(e.getMessage().contains("已经在这个图层里"), "消息要说清原因：" + e.getMessage());

        // 只读视图不是装饰：series() 若返回活列表，调用方一句 add 就绕过了上面这道守卫，
        // 而 Javadoc 承诺的是"不可修改的列表"。这条是变异验证查出来的——
        // 把 unmodifiableList 换成裸列表后，上面两条断言照样全绿。
        assertThrows(UnsupportedOperationException.class, () -> layer.series().add(series("d")),
                "series() 承诺返回不可修改的列表，否则重复守卫形同虚设");
        assertThrows(UnsupportedOperationException.class, () -> chart.layers().clear());
    }

    @Test
    void 频谱不属于折线族() {
        // 这条把"频谱必须由独立渲染器实现"钉住。
        // 若有人把它归进折线族，ChartRenderer 会把它路由给 LineSeriesRenderer，
        // 而那会把**频域数据**当普通点连成折线——画面看起来像频谱，
        // 实际上 x 轴的含义全错（频率被当成了样本序号）。
        assertFalse(ChartType.SPECTRUM.polylineFamily(),
                "频谱的顶点不来自逐样本的点，必须由独立渲染器实现");
        assertFalse(ChartType.SPECTRUM.connectsSamples());
        assertFalse(ChartType.SPECTRUM.drawsMarkers());
        assertFalse(ChartType.SPECTRUM.drawsBars());
        assertFalse(ChartType.SPECTRUM.stepped());
        assertFalse(ChartType.SPECTRUM.fillsUnderCurve());
    }

    @Test
    void 柱状与面积与阶梯都在折线族里但各有各的渲染器() {
        // "在折线族里"的含义是**顶点来自逐样本的点**，不是"折线渲染器画得出来"。
        // 这三条钉住的是前者：它们的实例仍然是"每个样本一两个点"，
        // 所以实例区间算术（WindowRange）、环绕切分、NaN 断开全部照用。
        assertTrue(ChartType.STEP.polylineFamily());
        assertTrue(ChartType.AREA.polylineFamily());
        assertTrue(ChartType.BAR.polylineFamily());
        assertFalse(ChartType.HEATMAP.polylineFamily());
        assertFalse(ChartType.WATERFALL.polylineFamily());

        // 而"顶点怎么画"各不相同：这三条一旦有一个为假，ChartRenderer 的查表与
        // ChartType 的文档就有一处是错的（属性组合是那张表的输入）。
        assertTrue(ChartType.STEP.stepped());
        assertTrue(ChartType.AREA.fillsUnderCurve());
        assertTrue(ChartType.BAR.drawsBars());
        // 阶梯与面积都"连样本"，柱状不连——这正是它们不能被按属性推出来的原因：
        // 只按 connectsSamples() 推的话，阶梯与面积会落进折线渲染器，
        // 于是阶梯被拉成斜线、面积图的填充整个消失，而画面都"看起来正常"。
        assertTrue(ChartType.STEP.connectsSamples());
        assertTrue(ChartType.AREA.connectsSamples());
        assertFalse(ChartType.BAR.connectsSamples());
    }

    @Test
    void 面积与柱状的样式有确定默认值() {
        // 默认值是**行为**的一部分：基线为 0 时柱状图与 JavaFX 的
        // forceZeroInRange 一致；填充不透明度 0.5 时面积图的轮廓线才看得见
        // （两者同色同不透明度的话，"轮廓画了"与"没画"逐像素相同）。
        // 这里只钉住"改默认值要同时改测试"——像素口径那两条在 ChartVerifier 里。
        Series s = series("s");
        assertEquals(0f, s.baseline(), 0f, "默认基线是 0（值，不是绘图区下边缘）");
        assertEquals(0.5f, s.fillAlpha(), 0f, "默认填充不透明度 0.5（与 JavaFX 的面积填充一致）");
        assertEquals(0.2f, s.categoryGap(), 0f);
        assertEquals(0.2f, s.barGap(), 0f);

        // 链式 setter 返回自身（否则装配代码得写两遍变量名）。
        Series t = new Series("t", data(), ChartType.BAR);
        assertSame(t, t.baseline(0.5f));
        assertSame(t, t.fillAlpha(1f));
        assertSame(t, t.categoryGap(0.5f));
        assertSame(t, t.barGap(0f));
        assertEquals(0.5f, t.baseline(), 0f);
        assertEquals(1f, t.fillAlpha(), 0f);
        assertEquals(0.5f, t.categoryGap(), 0f);
        assertEquals(0f, t.barGap(), 0f);
    }

    /**
     * 样式里的**非有限数**必须是响亮的：NaN / Infinity 不是"越界的值"，是程序错误。
     *
     * <p>这条与"越界但有限的量按原契约钳住/退化"（{@code fillAlpha(2)} → 实心、
     * {@code lineWidth(-1)} → 不画线）**不冲突**，两者的分界正是
     * "渲染侧对这种输入有没有一个明确、可预期的处置"：
     * <ul>
     *   <li>NaN 没有。{@code fillAlpha(NaN)} 会落进 {@code AreaSeriesRenderer.fillColor}
     *       的两个分支都不成立的那条缝里，{@code Math.round(NaN)} 得 0，
     *       于是填充**全透明**——而"用户把不透明度设成 0"与"用户算出了一个 NaN"
     *       在画面上逐像素相同；</li>
     *   <li>NaN 线宽进顶点着色器后整个图元一起消失，也是静默的。</li>
     * </ul>
     * <p>Infinity 与 NaN 同类处置：几何量取 Infinity 让图元退化（在画面上等于没画），
     * 而 {@code fillAlpha(Infinity)} 虽然恰好被钳成 1，但那条路径上没有任何东西
     * 能告诉调用方"你给的不是一个数"——统一在入口拒绝，就没有第二种解释。
     */
    @Test
    void 样式里的非有限数会抛异常() {
        Series s = new Series("s", data(), ChartType.LINE);

        assertThrows(IllegalArgumentException.class, () -> s.fillAlpha(Float.NaN),
                "NaN 不透明度会静默变成全透明（Math.round(NaN) = 0），必须抛");
        assertThrows(IllegalArgumentException.class, () -> s.lineWidth(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.markerSize(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.baseline(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.categoryGap(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> s.barGap(Float.NaN));

        assertThrows(IllegalArgumentException.class, () -> s.fillAlpha(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> s.lineWidth(Float.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> s.markerSize(Float.POSITIVE_INFINITY));

        // 抛异常之后不许改到字段：否则"抛了但值已经进去了"比不抛更难查。
        assertEquals(0.5f, s.fillAlpha(), 0f, "被拒绝的赋值不该改到字段");
        assertEquals(1f, s.lineWidth(), 0f);
        assertEquals(3f, s.markerSize(), 0f);
        assertEquals(0f, s.baseline(), 0f);
        assertEquals(0.2f, s.categoryGap(), 0f);
        assertEquals(0.2f, s.barGap(), 0f);

        // 反面：越界但**有限**的量仍然按原契约收下（渲染侧钳住/退化），
        // "越界抛异常"会让用户只能靠读栈去猜是哪个字段。
        assertEquals(2f, s.fillAlpha(2f).fillAlpha(), 0f, "fillAlpha > 1 不抛，由渲染侧钳到 1");
        assertEquals(-1f, s.lineWidth(-1f).lineWidth(), 0f, "负线宽不抛，渲染侧不画线");
    }

    @Test
    void 非法装配会抛异常() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> chart.addLayer(""));
        assertThrows(IllegalArgumentException.class, () -> new Layer(null));
        assertThrows(IllegalArgumentException.class, () -> chart.addLayer("层").add(null));
        assertThrows(IllegalArgumentException.class, () -> new Series("s", null, ChartType.LINE));
        assertThrows(IllegalArgumentException.class, () -> new Series("s", data(), null));
        assertThrows(IllegalArgumentException.class, () -> chart.axis(3));
        assertEquals(1, chart.axes().size());
        assertEquals(AxisType.LINEAR, chart.axis(0).type());
        assertThrows(IllegalArgumentException.class, () -> new Chart());
    }
}
