package com.bingbaihanji.xuan.chart;

import com.bingbaihanji.xuan.util.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChartLayout} 的单元测试：外框怎么切成标题带、图例带与绘图区。
 *
 * <p><b>它守的第一条是"不许悄悄改动已验证的行为"</b>：不设标题、不设图例、
 * 外边距为 0 时，绘图区必须与传进来的外框<b>逐字段相等</b>。只要这一条成立，
 * "给标题留位置"就只影响显式配置了标题的图——而既有画面（那条路径的期望值
 * 是按像素钉着的）不会因为多了一个布局类而整体挪几个像素。
 *
 * <p>它守的第二条是<b>尺寸规则与字体无关</b>：所有的高度都由"字号 × 固定系数"
 * 算出来，不读字体的真实行高。于是"同一个外框 + 同一个配置 → 同一个绘图区"，
 * 校验器里那些逐像素的期望值才有地方写。
 *
 * <p>度量是一个假的实现（宽度 = 字数 × 字号 × 0.5），所以本文件一行 GL 都不需要。
 */
class ChartLayoutTest {

    private static final Rect FRAME = new Rect(100f, 50f, 400f, 300f);

    /** 假度量：宽度与字数成正比。真实字体只影响"文字占多宽"，不影响任何一条带子的高度。 */
    private static final ChartTextMetrics METRICS = new ChartTextMetrics() {
        @Override
        public float width(String text, float fontSize) {
            return text.codePointCount(0, text.length()) * fontSize * 0.5f;
        }

        @Override
        public float lineHeight(float fontSize) {
            return fontSize * ChartLayout.LINE_HEIGHT_FACTOR;
        }
    };

    private static Chart chart() {
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{AxisRange.of(0, 10), AxisRange.of(0, 1)},
                new double[][]{{0, 1, 2, 3}, {0.1, 0.2, 0.3, 0.4}});
        Chart chart = new Chart(new Axis(AxisType.LINEAR, data.axisRange(0)),
                new Axis(AxisType.LINEAR, data.axisRange(1)));
        chart.addLayer("主").add(new Series("电压", data, ChartType.LINE).color(0xFF00FF00));
        return chart;
    }

    /** 带名字与单位的图：轴标题的文字来自 {@code AxisRange} 的 name / unit。 */
    private static Chart namedChart() {
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{new AxisRange(0, 10, "样本", ""),
                        new AxisRange(0, 1, "值", "V")},
                new double[][]{{0, 1, 2, 3}, {0.1, 0.2, 0.3, 0.4}});
        Chart chart = new Chart(new Axis(AxisType.LINEAR, data.axisRange(0)),
                new Axis(AxisType.LINEAR, data.axisRange(1)));
        chart.addLayer("主").add(new Series("电压", data, ChartType.LINE).color(0xFF00FF00));
        return chart;
    }

    private static void assertSameRect(Rect expected, Rect actual, String what) {
        assertEquals(expected.x, actual.x, 1e-4f, what + " x");
        assertEquals(expected.y, actual.y, 1e-4f, what + " y");
        assertEquals(expected.width, actual.width, 1e-4f, what + " 宽");
        assertEquals(expected.height, actual.height, 1e-4f, what + " 高");
    }

    @Test
    void 不设标题与图例时绘图区就是外框本身() {
        Chart chart = chart();
        chart.legendVisible(false);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        // 逐字段相等——这是"不设标题/图例时逐像素与现在相同"的实现形式。
        assertSameRect(FRAME, layout.plotRect(), "无装饰时的绘图区");
        assertNull(layout.titleRect(), "没设标题时不该有标题带");
        assertNull(layout.legendRect(), "关了图例时不该有图例带");
        assertTrue(layout.legendItems().isEmpty(), "关了图例时不该有图例项");
        assertSameRect(FRAME, layout.frame(), "外框");
    }

    @Test
    void 标题带在绘图区之上而且高度只由字号决定() {
        Chart chart = chart();
        chart.legendVisible(false).title("电压监测").titleFontSize(20f).titleGap(7f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        // 高度 = 字号 × 1.4（与字体无关），宽度铺满内框，左边缘与内框对齐。
        assertSameRect(new Rect(FRAME.x, FRAME.y, FRAME.width,
                20f * ChartLayout.LINE_HEIGHT_FACTOR), layout.titleRect(), "标题带");
        // 基线 = 带子顶部 + 字号
        assertEquals(FRAME.y + 20f, layout.titleBaseline(), 1e-4f, "标题基线");
        // 绘图区被整体下推：标题带高 + 间隙
        assertEquals(FRAME.y + 20f * ChartLayout.LINE_HEIGHT_FACTOR + 7f,
                layout.plotRect().y, 1e-4f, "绘图区上边缘");
        assertEquals(FRAME.width, layout.plotRect().width, 1e-4f, "绘图区宽度不变（标题只吃高度）");
    }

    @Test
    void 图例带在绘图区之下且项与项不重叠() {
        Chart chart = chart();
        chart.legendSide(ChartSide.BOTTOM).legendFontSize(10f).legendGap(5f)
                .legendSwatchSize(8f);
        chart.addLayer("第二").add(new Series("电流", chart.allSeries().get(0).data(),
                ChartType.LINE).color(0xFF00FFFF));

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        float bandH = 10f * ChartLayout.LINE_HEIGHT_FACTOR;
        assertSameRect(new Rect(FRAME.x, FRAME.getBottom() - bandH, FRAME.width, bandH),
                layout.legendRect(), "图例带");
        assertEquals(FRAME.getBottom() - bandH - 5f, layout.plotRect().getBottom(), 1e-4f,
                "绘图区下边缘 = 图例带上边 - 间隙");
        assertEquals(2, layout.legendItems().size(), "两个系列两项");

        // 每一项：色块边长 = legendSwatchSize，文字在色块右边（间隙 4）
        ChartLayout.LegendItem first = layout.legendItems().get(0);
        ChartLayout.LegendItem second = layout.legendItems().get(1);
        assertEquals(8f, first.swatch().width, 1e-4f, "色块宽度");
        assertEquals(8f, first.swatch().height, 1e-4f, "色块高度");
        assertEquals(first.swatch().getRight() + ChartLayout.SWATCH_LABEL_GAP, first.labelX(),
                1e-4f, "文字在色块右边");
        // 第二项在第一项右边，且隔着一个项间隙 → 位置可以逐项算出来
        float firstLabelWidth = METRICS.width(first.label(), 10f);
        assertEquals(first.labelX() + firstLabelWidth + ChartLayout.LEGEND_ITEM_GAP,
                second.swatch().x, 1e-4f, "第二项的色块 x");
        // 色块垂直居中于带子
        assertEquals(layout.legendRect().y + (bandH - 8f) * 0.5f, first.swatch().y, 1e-4f,
                "色块垂直居中");
        assertSameRect(FRAME, layout.frame(), "外框");
    }

    @Test
    void 标题在最外面图例紧挨绘图区() {
        Chart chart = chart();
        chart.title("顶").titleSide(ChartSide.BOTTOM).titleFontSize(10f).titleGap(4f)
                .legendSide(ChartSide.BOTTOM).legendFontSize(10f).legendGap(6f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        float titleH = 10f * ChartLayout.LINE_HEIGHT_FACTOR;
        float legendH = 10f * ChartLayout.LINE_HEIGHT_FACTOR;
        // 标题在最下面（最外），图例在它上面，绘图区再上面
        assertEquals(FRAME.getBottom() - titleH, layout.titleRect().y, 1e-4f, "标题带贴外框下边");
        assertEquals(FRAME.getBottom() - titleH - 4f - legendH, layout.legendRect().y, 1e-4f,
                "图例带在标题带之上");
        assertEquals(layout.legendRect().y - 6f, layout.plotRect().getBottom(), 1e-4f,
                "绘图区在图例带之上");
    }

    @Test
    void 左右放的图例占宽度而不是高度() {
        Chart chart = chart();
        chart.legendSide(ChartSide.LEFT).legendFontSize(10f).legendSwatchSize(8f)
                .legendGap(5f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        // 带子宽度 = 色块 + 间隙 + 最宽的那一项文字（这一条**是**与字体有关的）
        float want = 8f + ChartLayout.SWATCH_LABEL_GAP
                + METRICS.width("电压", 10f);
        assertEquals(want, layout.legendRect().width, 1e-4f, "左图例的宽度");
        assertEquals(FRAME.height, layout.legendRect().height, 1e-4f, "左图例占满高度");
        assertEquals(FRAME.x + want + 5f, layout.plotRect().x, 1e-4f,
                "绘图区被右推");
        assertEquals(FRAME.width - want - 5f, layout.plotRect().width, 1e-4f,
                "绘图区变窄（高度不变）");
    }

    @Test
    void 外边距把内框四边各收掉相应的量() {
        Chart chart = chart();
        chart.legendVisible(false).padding(new ChartInsets(3f, 5f, 7f, 11f));

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        assertSameRect(new Rect(FRAME.x + 11f, FRAME.y + 3f,
                FRAME.width - 16f, FRAME.height - 10f), layout.plotRect(), "内框");
    }

    @Test
    void 外框装不下装饰时绘图区缩成空的而不是负数() {
        Chart chart = chart();
        chart.title("很高").titleFontSize(40f)
                .legendSide(ChartSide.BOTTOM).legendFontSize(40f);

        // 300 高的外框里有 2 × 40 × 1.4 = 112……还装得下；把外框压到 20 高才装不下。
        ChartLayout layout = ChartLayout.compute(chart, new Rect(0f, 0f, 100f, 20f), METRICS);

        // 负宽高进到 glScissor 是未定义行为；"什么都没画"与"数据是空的"在画面上一样，
        // 所以这里的选择是缩成 0，让调用方能自己判断。
        assertEquals(0f, layout.plotRect().height, 1e-4f, "高度缩成 0");
        assertTrue(layout.plotRect().height >= 0f && layout.plotRect().width >= 0f,
                "宽高都不许为负");

        // 外边距自己就把外框吃光时也一样
        ChartLayout eaten = ChartLayout.compute(
                chart.padding(ChartInsets.uniform(50f)), new Rect(0f, 0f, 100f, 60f), METRICS);
        assertEquals(0f, eaten.plotRect().width, 1e-4f);
        assertEquals(0f, eaten.plotRect().height, 1e-4f);
    }

    @Test
    void 没有系列时图例不占地方() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));

        // 默认 legendVisible = true（与 JavaFX 一致），但一个系列都没有 → 一项都没有 →
        // 带子高度 0、不挤绘图区。少了这条，"默认显示图例"会给空图凭空加一条空白带。
        assertTrue(chart.legendVisible(), "默认显示图例");
        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);
        assertNull(layout.legendRect(), "没有系列就没有图例带");
        assertSameRect(FRAME, layout.plotRect(), "没有装饰时的绘图区");
    }

    @Test
    void 装配配置的默认值() {
        Chart chart = chart();
        assertEquals("", chart.title(), "默认没有标题");
        assertEquals(ChartSide.TOP, chart.titleSide());
        assertEquals(ChartSide.BOTTOM, chart.legendSide());
        assertEquals(ChartInsets.NONE, chart.padding());
        assertEquals(15f, chart.titleFontSize(), 1e-4f);
        assertEquals(12f, chart.legendFontSize(), 1e-4f);
        assertEquals(10f, chart.legendSwatchSize(), 1e-4f);
        // 轴标题与刻度预留默认**都不占地方**：它们是新能力，默认打开会无声地改动
        // 每一张既有图的绘图区（那些图的期望值是按像素钉着的）。理由见 Chart 的字段说明。
        assertFalse(chart.axisTitlesVisible(), "默认不显示轴标题");
        assertEquals(0f, chart.tickLabelReserve(ChartSide.BOTTOM), 0f);
        assertEquals(0f, chart.tickLabelReserve(ChartSide.LEFT), 0f);
    }

    // ------------------------------------------------------------------
    // 刻度文字预留带与轴标题
    // ------------------------------------------------------------------

    /**
     * 刻度文字在本库里是**调用方画的**（{@code Axis.ticks()} 给位置，画由应用做），
     * 所以图表层不可能知道它占多高——那正是"可配置的预留量"而不是"自动测量"的理由。
     *
     * <p>它必须像别的带子一样，把绘图区往里挤，而不是让刻度文字压在数据上。
     */
    @Test
    void 刻度文字预留带把绘图区往里挤() {
        Chart chart = chart();
        chart.legendVisible(false)
                .tickLabelReserve(ChartSide.BOTTOM, 18f)
                .tickLabelReserve(ChartSide.LEFT, 26f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        assertSameRect(new Rect(FRAME.x + 26f, FRAME.y,
                FRAME.width - 26f, FRAME.height - 18f), layout.plotRect(), "扣掉预留之后的绘图区");
    }

    /** 一次设两边的简写（x 轴与 y 轴刻度通常一样高）。 */
    @Test
    void 刻度文字预留带可以一次设两边() {
        Chart chart = chart();
        chart.legendVisible(false).tickLabelReserve(15f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        assertEquals(15f, chart.tickLabelReserve(ChartSide.BOTTOM), 1e-4f);
        assertEquals(15f, chart.tickLabelReserve(ChartSide.LEFT), 1e-4f);
        assertSameRect(new Rect(FRAME.x + 15f, FRAME.y,
                FRAME.width - 15f, FRAME.height - 15f), layout.plotRect(), "两边各收 15");
    }

    /**
     * 轴标题在**刻度预留之外**（也就是离绘图区更远），而不是压着刻度文字。
     *
     * <p>顺序从外向里是：图例 → 轴标题带 → 刻度预留 → 绘图区。
     */
    @Test
    void 轴标题带在刻度预留之外() {
        Chart chart = namedChart();
        chart.legendVisible(false)
                .axisTitlesVisible(true).axisTitleFontSize(10f).axisTitleGap(4f)
                .tickLabelReserve(ChartSide.BOTTOM, 18f)
                .tickLabelReserve(ChartSide.LEFT, 26f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        float xTitleH = 10f * ChartLayout.LINE_HEIGHT_FACTOR;
        ChartLayout.AxisTitle x = layout.xAxisTitle();
        assertNotNull(x, "x 轴标题（轴 0 的 name/unit）");
        assertEquals("样本", x.text(), "轴 0 的名字就是 x 轴标题（单位为空时不带括号）");
        // 它的横向范围**就是绘图区**：这样它不会与 y 轴标题带在左下角重叠，
        // 而"居中于绘图区"也是"这条轴从哪到哪"的正确语义。
        float yBandW = METRICS.width(layout.yAxisTitle().text(), 10f);
        assertSameRect(new Rect(FRAME.x + yBandW + 4f + 26f,
                FRAME.y + FRAME.height - xTitleH,
                FRAME.width - yBandW - 4f - 26f, xTitleH), x.rect(),
                "x 轴标题带贴内框下边、横跨绘图区");
        assertEquals(FRAME.y + FRAME.height - xTitleH + 10f * ChartLayout.BASELINE_FACTOR,
                x.baseline(), 1e-4f, "基线 = 带子顶部 + 字号 × BASELINE_FACTOR");

        // 绘图区下边 = 内框下边 - 轴标题带 - 轴标题间隙 - 刻度预留
        assertEquals(FRAME.y + FRAME.height - xTitleH - 4f - 18f,
                layout.plotRect().y + layout.plotRect().height, 1e-4f,
                "刻度预留紧挨绘图区，轴标题带在它外面");
    }

    /**
     * x 轴标题**水平居中于带子**（它是轴的标题，左边对齐会看着像另一个标注）。
     *
     * <p>居中用的是度量给的宽度，所以它是本类第二处"与字体有关"的算术
     * （第一处是左右图例的带宽）——但那不影响任何**带子**的高度。
     */
    @Test
    void x轴标题水平居中而y轴标题带宽由度量决定() {
        Chart chart = namedChart();
        chart.legendVisible(false).axisTitlesVisible(true).axisTitleFontSize(10f);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        ChartLayout.AxisTitle x = layout.xAxisTitle();
        float textW = METRICS.width(x.text(), 10f);
        assertEquals(x.rect().x + x.rect().width / 2f, x.x() + textW / 2f, 1e-4f,
                "x 轴标题的**文字**在带子里居中（左对齐会看着像另一个标注）");
        assertEquals(layout.plotRect().x, x.rect().x, 1e-4f, "x 轴标题带横跨绘图区");
        assertEquals(layout.plotRect().width, x.rect().width, 1e-4f);

        ChartLayout.AxisTitle y = layout.yAxisTitle();
        assertNotNull(y, "y 轴标题（轴 1 的 name/unit）");
        assertEquals(METRICS.width(y.text(), 10f), y.rect().width, 1e-4f,
                "y 轴标题带的宽度 = 文字宽度（横排文字，不旋转）");
        assertEquals(layout.plotRect().x, y.rect().x + y.rect().width + chart.axisTitleGap(),
                1e-4f, "y 轴标题带与绘图区之间隔着一个轴标题间隙");
        assertEquals(y.rect().x, y.x(), 1e-4f, "y 轴标题的文字从带子左边缘起（带子只有文字那么宽）");
        // 垂直居中：基线 = 带子中心 + 半个字面高（近似，见 CENTER_BASELINE_FACTOR 的文档）
        assertEquals(y.rect().y
                        + (y.rect().height + 10f * (2f * ChartLayout.CENTER_BASELINE_FACTOR)) * 0.5f,
                y.baseline(), 1e-4f, "y 轴标题在带子里垂直居中");
    }

    /** 名字与单位怎么拼：单位为空时不要多出空括号。 */
    @Test
    void 轴标题的文字由名字与单位拼成() {
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{new AxisRange(0, 10, "时间", "s"),
                        new AxisRange(0, 1, "电压", "V")},
                new double[][]{{0, 1}, {0, 1}});
        Chart chart = new Chart(new Axis(AxisType.LINEAR, data.axisRange(0)),
                new Axis(AxisType.LINEAR, data.axisRange(1)));
        chart.legendVisible(false).axisTitlesVisible(true);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);
        assertEquals("时间 (s)", layout.xAxisTitle().text());
        assertEquals("电压 (V)", layout.yAxisTitle().text());

        // 名字为空、只有单位 → 只显示单位（不然会画出一对空括号）
        ArrayChartData onlyUnit = new ArrayChartData(
                new AxisRange[]{new AxisRange(0, 10, "", "s"),
                        new AxisRange(0, 1, "", "")},
                new double[][]{{0, 1}, {0, 1}});
        Chart c2 = new Chart(new Axis(AxisType.LINEAR, onlyUnit.axisRange(0)),
                new Axis(AxisType.LINEAR, onlyUnit.axisRange(1)));
        c2.legendVisible(false).axisTitlesVisible(true);
        ChartLayout l2 = ChartLayout.compute(c2, FRAME, METRICS);
        assertEquals("s", l2.xAxisTitle().text(), "只有单位时只显示单位");
        assertNull(l2.yAxisTitle(), "名字与单位都空 → 没有标题，也不占地方");
    }

    /** 关掉轴标题时它一点都不占地方（绘图区与不设它时逐字段相等）。 */
    @Test
    void 关掉轴标题时不占地方() {
        Chart chart = namedChart();
        chart.legendVisible(false).tickLabelReserve(5f);

        ChartLayout off = ChartLayout.compute(chart, FRAME, METRICS);
        chart.axisTitlesVisible(true);
        ChartLayout on = ChartLayout.compute(chart, FRAME, METRICS);

        assertNull(off.xAxisTitle(), "默认没有 x 轴标题");
        assertNull(off.yAxisTitle());
        assertTrue(on.plotRect().height < off.plotRect().height,
                "打开之后绘图区变矮（带子真的占了地方）");
        assertTrue(on.plotRect().width < off.plotRect().width,
                "并且变窄（y 轴标题带占了左边）");
    }

    /** 只有一根轴时没有 y 轴标题（不能去 axis(1) 上越界）。 */
    @Test
    void 只有一根轴时没有y轴标题() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR,
                new AxisRange(0, 10, "样本", "")));
        chart.axisTitlesVisible(true);

        ChartLayout layout = ChartLayout.compute(chart, FRAME, METRICS);

        assertNull(layout.yAxisTitle());
        assertNotNull(layout.xAxisTitle(), "0 号轴仍然是 x 轴");
    }

    /** 预留量只支持 BOTTOM（x 轴刻度）与 LEFT（y 轴刻度）；别的方向明确抛异常。 */
    @Test
    void 刻度预留只支持下边与左边() {
        Chart chart = chart();
        assertThrows(IllegalArgumentException.class,
                () -> chart.tickLabelReserve(ChartSide.TOP, 10f));
        assertThrows(IllegalArgumentException.class,
                () -> chart.tickLabelReserve(ChartSide.RIGHT, 10f));
        assertThrows(IllegalArgumentException.class,
                () -> chart.tickLabelReserve(ChartSide.BOTTOM, -1f));
        assertThrows(IllegalArgumentException.class,
                () -> chart.tickLabelReserve(ChartSide.BOTTOM, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> chart.axisTitleFontSize(0f));
        assertThrows(IllegalArgumentException.class, () -> chart.axisTitleGap(-1f));
    }

    @Test
    void 非法配置会抛异常() {
        Chart chart = chart();
        // 左右放的标题要转 90°，而绘制入口只有横排文字 → 明确抛异常，不画成横的
        assertThrows(IllegalArgumentException.class, () -> chart.titleSide(ChartSide.LEFT));
        assertThrows(IllegalArgumentException.class, () -> chart.titleSide(ChartSide.RIGHT));
        assertThrows(IllegalArgumentException.class, () -> chart.titleSide(null));
        assertThrows(IllegalArgumentException.class, () -> chart.legendSide(null));
        assertThrows(IllegalArgumentException.class, () -> chart.titleFontSize(0f));
        assertThrows(IllegalArgumentException.class, () -> chart.titleFontSize(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> chart.legendFontSize(-1f));
        assertThrows(IllegalArgumentException.class, () -> chart.legendGap(-1f));
        assertThrows(IllegalArgumentException.class, () -> chart.legendSwatchSize(-1f));
        assertThrows(IllegalArgumentException.class, () -> chart.titleGap(-1f));
        assertThrows(IllegalArgumentException.class, () -> chart.padding(null));
        assertThrows(IllegalArgumentException.class, () -> new ChartInsets(-1f, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new ChartInsets(0f, 0f, Float.NaN, 0f));
        assertThrows(IllegalArgumentException.class,
                () -> ChartLayout.compute(null, FRAME, METRICS));
        assertThrows(IllegalArgumentException.class,
                () -> ChartLayout.compute(chart, null, METRICS));

        // 图例的四个方向都支持（左右是一列横排文字，不需要旋转）
        assertSame(chart, chart.legendSide(ChartSide.LEFT));
        assertSame(chart, chart.legendSide(ChartSide.RIGHT));
        assertSame(chart, chart.legendSide(ChartSide.TOP));
        assertSame(chart, chart.title("t"));
        assertSame(chart, chart.title(null));
        assertEquals("", chart.title());
    }
}
