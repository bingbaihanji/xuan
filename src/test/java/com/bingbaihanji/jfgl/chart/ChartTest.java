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
