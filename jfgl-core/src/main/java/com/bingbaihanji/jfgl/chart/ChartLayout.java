package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.util.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一张图的装配：外框 → 标题带 / 图例带 / 绘图区。<strong>纯计算，零 GL 依赖。</strong>
 *
 * <h2>它存在的原因</h2>
 * <p>标题与图例不是"画上去就完了"——它们<b>占地方</b>，而占掉的地方要从绘图区里扣。
 * 于是"绘图区在哪"不再等于"调用方给的那个矩形"，
 * 而 {@code ChartRenderLayout}（数值 ↔ 屏幕的映射）要的正是一个绘图区矩形。
 * 本类就是那两者之间的一步：把外框切成几块，只把剩下的那块交给渲染后端。
 *
 * <h2>尺寸规则（全部与字体无关）</h2>
 * <p>除了文字要占多宽（那必须问度量），所有的高度都由字号乘以一个固定系数算出来，
 * <b>不读字体的真实 ascent/descent</b>。这不是偷懒，是让布局可被精确预测：
 * 校验器里那些逐像素的期望值全部依赖"同一个外框 + 同一个配置 → 同一个绘图区"。
 * 字号是配置项，字体不是。
 *
 * <pre>
 *   行高 bandH(字号)   = 字号 × {@link #LINE_HEIGHT_FACTOR}      // 上限式：宁可多留，不能把字切掉
 *   基线                  = 带子顶部 + 字号 × {@link #BASELINE_FACTOR}
 *   标题带                = (内框左, 顶, 内框宽, bandH(标题字号))
 *   图例带（上下放时）    = (内框左, 顶, 内框宽, bandH(图例字号))
 *   图例带（左右放时）    = 宽 = 各项里最宽的那一项，高 = 剩下的全部
 *   绘图区                = 扣掉上面这些带子之后剩下的矩形
 * </pre>
 *
 * <h2>顺序：标题在最外面</h2>
 * <p>标题放上时它在上、图例在它下面（图例紧挨绘图区）；放下时对称。
 * 这一条抄 JavaFX：标题是整张图的标题，不该夹在图例与绘图区之间。
 *
 * <h2>退化不抛异常，缩成空矩形</h2>
 * <p>外框小到装不下装饰时（标题 + 图例 + 间隙超过了可用高度），绘图区会缩成
 * <b>宽或高为 0 的矩形</b>，而不是负数。负宽高一路传到 {@code glScissor} 会变成
 * 一个方向相反的裁剪盒（GL 的负宽高是未定义行为），而"这张图什么都没画"
 * 与"这张图的数据是空的"在画面上一样。调用方拿到 0 就能自己判断。
 *
 * <h2>它不是 {@code ChartRenderLayout}</h2>
 * <p>两者是前后两步、各有各的关注点：
 * <ul>
 *   <li><b>本类</b>（① 纯计算）：矩形怎么切；</li>
 *   <li>{@code ChartRenderLayout}（② 渲染后端）：数值怎么映射到像素。</li>
 * </ul>
 * 本类把 {@link #plotRect()} 交给后者即可，两者之间没有别的接口。
 */
public final class ChartLayout {

    /**
     * 行高系数：一行文字占的高度 = 字号 × 它。
     *
     * <p>取 1.4 是<b>上限式</b>的：多数中文字体的 ascent + descent 在 1.2~1.35 em 之间，
     * 1.4 留了余量。取小了会把字的顶部切掉一点，而"标题最上面那几个像素没了"
     * 看起来像字号的问题，不像布局的问题。
     *
     * <p>它同时也是 {@link ChartTextMetrics#lineHeight(float)} 的实现口径——
     * 布局不问字体真实行高，就是为了让这个数只有一个来源。
     */
    public static final float LINE_HEIGHT_FACTOR = 1.4f;

    /** 基线在带子内的位置：带子顶部 + 字号 × 它。 */
    public static final float BASELINE_FACTOR = 1.0f;

    /** 图例里色块与它右边文字之间的间隙（像素，固定值）。 */
    public static final float SWATCH_LABEL_GAP = 4f;

    /** 图例里相邻两项之间的间隙（像素，固定值）。 */
    public static final float LEGEND_ITEM_GAP = 8f;

    private final Rect frame;

    private final Rect titleRect;

    private final float titleBaseline;

    private final Rect legendRect;

    private final List<LegendItem> legendItems;

    private final Rect plotRect;

    private ChartLayout(Rect frame, Rect titleRect, float titleBaseline, Rect legendRect,
                        List<LegendItem> legendItems, Rect plotRect) {
        this.frame = frame;
        this.titleRect = titleRect;
        this.titleBaseline = titleBaseline;
        this.legendRect = legendRect;
        this.legendItems = legendItems;
        this.plotRect = plotRect;
    }

    /**
     * 把外框切成标题带、图例带与绘图区。
     *
     * <p><b>没有任何装饰时，绘图区就是外框本身</b>（逐字段相等）——
     * 这是"给标题留位置不许悄悄改动已验证的行为"那条要求的实现形式：
     * 既不设标题也不设图例、外边距为 0 时，{@link #plotRect()} 与传进来的
     * {@code frame} 一模一样，于是画出来的每一个像素都与不经过本类时相同。
     * 这条有单测钉着（{@code ChartLayoutTest}）。
     *
     * @param chart   图表（标题、图例、间距、外边距都从它读）
     * @param frame   外框（设备像素），通常是调用方想用来画整张图的那块地方
     * @param metrics 文字度量（见 {@link ChartTextMetrics}）
     * @return 切好的布局
     * @throws IllegalArgumentException 任一参数为 null 时
     */
    public static ChartLayout compute(Chart chart, Rect frame, ChartTextMetrics metrics) {
        if (chart == null || frame == null || metrics == null) {
            throw new IllegalArgumentException("chart / frame / metrics 都不能为 null");
        }
        ChartInsets pad = chart.padding();
        float innerX = frame.x + pad.left();
        float innerY = frame.y + pad.top();
        float innerW = frame.width - pad.horizontal();
        float innerH = frame.height - pad.vertical();
        if (!(innerW > 0f) || !(innerH > 0f)) {
            // 外边距本身就已经吃掉了整个外框：下面全是空矩形，不做任何算术。
            Rect empty = new Rect(innerX, innerY, 0f, 0f);
            return new ChartLayout(frame, null, 0f, null, List.of(), empty);
        }

        float left = innerX;
        float right = innerX + innerW;
        float top = innerY;
        float bottom = innerY + innerH;

        boolean hasTitle = chart.title() != null && !chart.title().isEmpty();
        List<LegendItem> items = chart.legendVisible() ? legendItems(chart) : List.of();
        boolean hasLegend = !items.isEmpty();

        // ---- 1) 上边的带子：标题在外、图例在内 ----
        Rect titleRect = null;
        float titleBaseline = 0f;
        float titleBandH = hasTitle ? lineHeight(chart.titleFontSize(), metrics) : 0f;
        float legendBandH = hasLegend && isHorizontal(chart.legendSide())
                ? lineHeight(chart.legendFontSize(), metrics) : 0f;
        if (hasTitle && chart.titleSide() == ChartSide.TOP) {
            titleRect = new Rect(innerX, top, innerW, titleBandH);
            titleBaseline = top + chart.titleFontSize() * BASELINE_FACTOR;
            top += titleBandH + chart.titleGap();
        }
        Rect legendRect = null;
        if (hasLegend && chart.legendSide() == ChartSide.TOP) {
            legendRect = new Rect(innerX, top, innerW, legendBandH);
            top += legendBandH + chart.legendGap();
        }

        // ---- 2) 下边的带子：同样标题在外（最下面）----
        if (hasTitle && chart.titleSide() == ChartSide.BOTTOM) {
            float y = bottom - titleBandH;
            titleRect = new Rect(innerX, y, innerW, titleBandH);
            titleBaseline = y + chart.titleFontSize() * BASELINE_FACTOR;
            bottom = y - chart.titleGap();
        }
        if (hasLegend && chart.legendSide() == ChartSide.BOTTOM) {
            float y = bottom - legendBandH;
            legendRect = new Rect(innerX, y, innerW, legendBandH);
            bottom = y - chart.legendGap();
        }

        // ---- 3) 左右的带子：宽度取决于最宽的那一项（这一条**是**与字体有关的）----
        if (hasLegend && !isHorizontal(chart.legendSide())) {
            float bandW = 0f;
            for (LegendItem item : items) {
                bandW = Math.max(bandW, chart.legendSwatchSize() + SWATCH_LABEL_GAP
                        + metrics.width(item.label(), chart.legendFontSize()));
            }
            if (chart.legendSide() == ChartSide.LEFT) {
                legendRect = new Rect(left, top, bandW, Math.max(0f, bottom - top));
                left += bandW + chart.legendGap();
            } else {
                legendRect = new Rect(right - bandW, top, bandW, Math.max(0f, bottom - top));
                right -= bandW + chart.legendGap();
            }
        }

        // ---- 4) 剩下的是绘图区。两个方向都可能被挤成 0（见类文档）----
        Rect plotRect = new Rect(left, top,
                Math.max(0f, right - left), Math.max(0f, bottom - top));

        return new ChartLayout(frame, titleRect, titleBaseline, legendRect,
                items.isEmpty() ? List.of() : placeItems(chart, metrics, items, legendRect),
                plotRect);
    }

    /**
     * 图例项：一个色块 + 一行文字。
     *
     * <p>{@code labelX} 与 {@code labelBaseline} 是<b>文字</b>的起点与基线
     * （{@code Gc.drawText} 的口径：{@code (x, y)} 是基线的起点，不是左上角）。
     * 色块自己是一个 {@link Rect}。
     *
     * @param label          文字
     * @param argb           色块的颜色（ARGB），取自系列主色
     * @param swatch         色块矩形，设备像素
     * @param labelX         文字的起点 x
     * @param labelBaseline  文字的基线 y
     */
    public record LegendItem(String label, int argb, Rect swatch,
                             float labelX, float labelBaseline) {
    }

    private static boolean isHorizontal(ChartSide side) {
        return side == ChartSide.TOP || side == ChartSide.BOTTOM;
    }

    /** 每一行文字需要的高度（与字体无关，见类文档）。 */
    private static float lineHeight(float fontSize, ChartTextMetrics metrics) {
        return metrics.lineHeight(fontSize);
    }

    /** 收集图例项：每个系列一项，顺序就是绘制顺序（层序优先、层内其次）。 */
    private static List<LegendItem> legendItems(Chart chart) {
        List<LegendItem> items = new ArrayList<>();
        for (Series series : chart.allSeries()) {
            // 色块的位置稍后才知道（要先有带子），这里先占位。
            items.add(new LegendItem(series.name(), series.color(),
                    new Rect(0f, 0f, 0f, 0f), 0f, 0f));
        }
        return items;
    }

    /** 把色块与文字摆进图例带子。 */
    private static List<LegendItem> placeItems(Chart chart, ChartTextMetrics metrics,
                                              List<LegendItem> items, Rect band) {
        float swatch = chart.legendSwatchSize();
        float fontSize = chart.legendFontSize();
        List<LegendItem> placed = new ArrayList<>(items.size());
        if (isHorizontal(chart.legendSide())) {
            float x = band.x;
            float swatchY = band.y + (band.height - swatch) * 0.5f;
            // 文字垂直居中于带子：基线 = 带子中心 + 半个字面高（0.7 × 字号 ≈ 字面高的两倍不到）。
            // 这是一个近似——精确值要问字体的 ascent/capHeight，而那会让布局依赖字体（见类文档）。
            float baseline = band.y + (band.height + fontSize * 0.7f) * 0.5f;
            for (LegendItem item : items) {
                placed.add(new LegendItem(item.label(), item.argb(),
                        new Rect(x, swatchY, swatch, swatch),
                        x + swatch + SWATCH_LABEL_GAP, baseline));
                x += swatch + SWATCH_LABEL_GAP + metrics.width(item.label(), fontSize)
                        + LEGEND_ITEM_GAP;
            }
        } else {
            float rowH = Math.max(swatch, metrics.lineHeight(fontSize));
            float y = band.y;
            for (LegendItem item : items) {
                placed.add(new LegendItem(item.label(), item.argb(),
                        new Rect(band.x, y + (rowH - swatch) * 0.5f, swatch, swatch),
                        band.x + swatch + SWATCH_LABEL_GAP,
                        y + (rowH + fontSize * 0.7f) * 0.5f));
                y += rowH;
            }
        }
        return Collections.unmodifiableList(placed);
    }

    /** 调用方给的整块外框（设备像素）。 */
    public Rect frame() {
        return frame;
    }

    /** 标题带子；没有标题时为 null。 */
    public Rect titleRect() {
        return titleRect;
    }

    /** 标题的基线 y（{@code Gc.drawText} 的 y）；没有标题时为 0。 */
    public float titleBaseline() {
        return titleBaseline;
    }

    /** 图例带子；没有图例（或没有系列）时为 null。 */
    public Rect legendRect() {
        return legendRect;
    }

    /** 图例项（含每个色块与文字的最终位置），顺序即绘制顺序。 */
    public List<LegendItem> legendItems() {
        return legendItems;
    }

    /**
     * 绘图区（数据区域）。
     *
     * <p><b>不设标题、不设图例、外边距为 0 时它与 {@link #frame()} 逐字段相等。</b>
     */
    public Rect plotRect() {
        return plotRect;
    }
}
