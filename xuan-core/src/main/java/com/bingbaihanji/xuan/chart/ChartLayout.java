package com.bingbaihanji.xuan.chart;

import com.bingbaihanji.xuan.util.Rect;

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
 *   轴标题带（下/左）     = 宽/高 = 文字宽度（y 轴）或内框宽（x 轴），高 = bandH(轴标题字号)
 *   刻度预留带            = 调用方声明的量（见 {@code Chart.tickLabelReserve}），默认 0
 *   绘图区                = 扣掉上面这些带子之后剩下的矩形
 * </pre>
 *
 * <h2>从外向里的顺序（两边都一样）</h2>
 * <pre>
 *   下边：标题 → 图例 → 轴标题带 → 刻度预留 → 绘图区
 *   左边：图例 → 轴标题带 → 刻度预留 → 绘图区
 * </pre>
 * <p>即"离绘图区越近的东西越贴着它"：调用方画的刻度文字紧挨绘图区，
 * 轴标题再往外面一层（标题在画面上才不会与刻度文字挤在一起）。
 *
 * <h2>两处<b>显式声明</b>的近似（别把它们当成精确值）</h2>
 * <ol>
 *   <li><b>行高</b>（{@link #LINE_HEIGHT_FACTOR} = 1.4 em）是<b>上限式</b>的取值：绝大多数
 *       字体的实际行高比它小，所以带子里会多留一点空白。取小的方向才是缺陷
 *       （字的顶部被切掉一点，看起来像字号的问题）。</li>
 *   <li><b>横排文字在带子里的垂直居中</b>（{@link #CENTER_BASELINE_FACTOR}）是近似的：
 *       精确的居中要问字体的 ascent，而那会让布局依赖字体（见上）。字面特别高的字体
 *       会略微偏下——但带子按 1.4 em 留了余量，所以不会变成"字被切掉"。</li>
 * </ol>
 * <p>两条都写在对应常量的文档里，这里再点一次名，是为了让"看起来精确其实在猜"这件事
 * 没有藏身处：读到这个类的人应当知道哪两个数是近似，以及为什么不把它做精确。
 *
 * <h2>刻度预留带为什么是配置项，不是自动测量</h2>
 * <p>本库的刻度文字由<b>调用方</b>画（{@code Axis.ticks()} 只给位置），图表层拿不到它占多高。
 * 硬猜一个值的话，调用方把刻度文字画大一点就压在数据上了——而"刻度文字与数据线重叠"
 * 看起来像绘图区太小，不像配置问题。所以让调用方声明自己画的刻度文字占多少，
 * 本类负责把它从绘图区里扣掉。
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
 *
 * <h2>已知限制：图例与轴标题的文字不折行、不省略</h2>
 * <p>一项的文字比带子还宽时（系列名很长、外框很窄），后面的部分会被<b>裁掉</b>——
 * 带子的矩形就是它的边界，绘制那一层会把它裁到带子里（见 {@code ChartPainter.begin}）。
 *
 * <p><b>为什么不折行、不加省略号</b>：两者都要在"哪里断"上做决定（按字符？按像素？
 * 加不加省略号），那是一个排版决策，不该由布局悄悄替调用方做。
 *
 * <p><b>为什么是裁剪而不是抛异常</b>：这一条被三种处置方式都考虑过。
 * <ul>
 *   <li><b>抛异常</b>被否掉，而且理由不是"太严格"：{@code ChartLayout.compute} 是在
 *       <b>绘制路径上</b>每帧调用的，而 GL 线程上抛出的异常在这个项目里是
 *       <b>静默吞掉</b>的（见 {@code RENDER} 一节与 {@code ChartVerifier} 的说明）——
 *       一张图因为系列名太长就整帧消失，而"消失"与"这一帧没画"在画面上一样。
 *       用一件静默的坏事去修另一件静默的坏事，没有意义。</li>
 *   <li><b>画到界外</b>（原来的行为）被否掉：那是真正的问题所在——文字跑到绘图区上、
 *       甚至跑出窗口，而调用方完全不知道。</li>
 *   <li><b>裁剪</b>留下：它把"画到别处"变成"在带子边缘被切断"——<b>看得见</b>
 *       （用户会去改名字或把外框留宽），界限明确，且不影响别的图元。</li>
 * </ul>
 * <p>要知道自己有没有被裁，{@code ChartTextMetrics.width} 就够：量一下最宽的那一项，
 * 与带子宽度比一比即可。这个类不额外提供一个"有没有溢出"的查询口——
 * 那会是一个只有测试在用的 API。
 *
 * <h2>已知限制：y 轴标题带的高度 = 绘图区的高</h2>
 * <p>y 轴标题是一条**横排**文字，它的带子取的是"绘图区的高"（那是这条轴自己的纵向范围），
 * 于是绘图区被挤到比一行还矮时（堆了太多带子），标题会被裁掉一大半——
 * 竖直方向上它没有别的解释可用了。这是"这张图的框太小、要的东西太多"的又一种样子，
 * 与"绘图区缩成空矩形"同一个性质：**不做自动回避，也不抛异常**，
 * 但也别指望它在 4px 高的绘图区里显示得出来。
 *
 * <p><b>例外：左右放的图例不会被裁到文字以外的地方去</b>，因为它的带宽
 * （{@link #legendRect()} 的宽）就是按最宽的那一项算出来的；会溢出的是
 * <b>上下放的图例</b>（带宽 = 内框宽，装不下就在右边缘被切断）与
 * <b>项目多于带子高度的左右图例</b>（最后几项在下边缘被切断）。
 * 后者是这份取舍里最不好的一种情况：被切断的是<b>整项</b>（不只是文字的一部分）。
 * 要多项图例请把外框留高，或者关掉 {@code legendVisible} 自己画。
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

    /**
     * 横排文字在一条带子里"垂直居中"时的基线偏移（相对带子中心，单位是字号）。
     *
     * <h2>它是<b>近似</b>，而且这一点是显式声明的</h2>
     * <p>精确的垂直居中要知道字体的 ascent（字面高的一半），而本类的全部尺寸规则
     * <b>故意不读字体</b>——布局要能被精确预测，见类文档。0.35 是"中文字面高约占
     * 0.7 em"的一半：多数汉字与拉丁大写落在基线之上 0.6~0.75 em 之间。
     *
     * <p><b>它可能不精确的地方</b>：字面高明显大于 0.7 em 的字体（某些装饰字体、
     * 或者一行里全是带升部的拉丁小写如 {@code bdfhkl}）会让文字在带子里<b>偏下</b>，
     * 极端情况下下沿会被带子裁掉一点。反之字面矮的字体（全小写 {@code ace}）
     * 会略偏上。带子本身是 {@link #LINE_HEIGHT_FACTOR}（1.4 em）的<b>上限式</b>取值，
     * 留的余量足以覆盖这个偏差——所以它不会变成"字被切掉"，只是"看着不太居中"。
     *
     * <p>要真正精确，得给 {@link ChartTextMetrics} 加一个"基线以上多高"的度量，
     * 那会让每个带子的位置都依赖字体——代价大于收益。
     */
    public static final float CENTER_BASELINE_FACTOR = 0.35f;

    /** 名字与单位之间的分隔（{@code "电压 (V)"}）。 */
    private static final String UNIT_PREFIX = " (";

    private final Rect frame;

    private final Rect titleRect;

    private final float titleBaseline;

    private final Rect legendRect;

    private final List<LegendItem> legendItems;

    private final AxisTitle xAxisTitle;

    private final AxisTitle yAxisTitle;

    private final Rect plotRect;

    private ChartLayout(Rect frame, Rect titleRect, float titleBaseline, Rect legendRect,
                        List<LegendItem> legendItems, AxisTitle xAxisTitle, AxisTitle yAxisTitle,
                        Rect plotRect) {
        this.frame = frame;
        this.titleRect = titleRect;
        this.titleBaseline = titleBaseline;
        this.legendRect = legendRect;
        this.legendItems = legendItems;
        this.xAxisTitle = xAxisTitle;
        this.yAxisTitle = yAxisTitle;
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
            return new ChartLayout(frame, null, 0f, null, List.of(), null, null, empty);
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

        // ---- 4) 轴标题与刻度预留：从外往里各占一层（顺序见类文档的图）----
        //
        // 下边：轴标题带在**外**、刻度预留紧挨绘图区。反过来（刻度在内、标题在外——
        // 就是这里写的顺序）才符合"离绘图区越近的东西越贴着它"。
        boolean showAxisTitles = chart.axisTitlesVisible();
        float axisFontSize = chart.axisTitleFontSize();
        float axisBandH = showAxisTitles ? lineHeight(axisFontSize, metrics) : 0f;

        // x 轴标题的**纵向**位置在这里定下来，横向范围要等左边的带子都算完
        // （它横跨的是绘图区，见下面第 5 步）——否则它会与 y 轴标题带在左下角重叠，
        // 而"带子互不重叠"正是这份布局打算给人看的东西。
        String xText = showAxisTitles ? axisTitleText(chart, 0) : "";
        float xTitleY = 0f;
        if (!xText.isEmpty()) {
            xTitleY = bottom - axisBandH;
            bottom = xTitleY - chart.axisTitleGap();
        }
        bottom -= tickLabelReserve(chart, ChartSide.BOTTOM, metrics);

        // 左边：y 轴标题是一行**横排**文字（本层没有旋转文字路径，与左右放的图例
        // 同一条取舍），所以它的带宽由度量决定——这是本类第二处与字体有关的算术。
        AxisTitle yTitle = null;
        String yText = showAxisTitles ? axisTitleText(chart, 1) : "";
        if (!yText.isEmpty()) {
            float bandW = metrics.width(yText, axisFontSize);
            float h = Math.max(0f, bottom - top);
            Rect band = new Rect(left, top, bandW, h);
            yTitle = new AxisTitle(yText, band, left,
                    top + (h + axisFontSize * (2f * CENTER_BASELINE_FACTOR)) * 0.5f);
            left += bandW + chart.axisTitleGap();
        }
        left += tickLabelReserve(chart, ChartSide.LEFT, metrics);

        // ---- 5) 剩下的是绘图区。两个方向都可能被挤成 0（见类文档）----
        Rect plotRect = new Rect(left, top,
                Math.max(0f, right - left), Math.max(0f, bottom - top));

        // ---- 6) x 轴标题带：横跨**绘图区**的横向范围，文字居中 ----
        //
        // 横跨绘图区（而不是整条内框）有两个好处：它不会与 y 轴标题带在左下角重叠；
        // 而"居中于绘图区"本来就是"这条轴从哪到哪"的正确语义。
        AxisTitle xTitle = null;
        if (!xText.isEmpty()) {
            Rect band = new Rect(left, xTitleY, plotRect.width, axisBandH);
            float textW = metrics.width(xText, axisFontSize);
            xTitle = new AxisTitle(xText, band, left + (band.width - textW) * 0.5f,
                    xTitleY + axisFontSize * BASELINE_FACTOR);
        }

        return new ChartLayout(frame, titleRect, titleBaseline, legendRect,
                items.isEmpty() ? List.of() : placeItems(chart, metrics, items, legendRect),
                xTitle, yTitle, plotRect);
    }

    /**
     * 一条轴的标题文字：{@code name}，有单位时拼成 {@code "name (unit)"}。
     *
     * <p>只有单位（名字为空）时只给单位——拼成 {@code " (V)"} 会画出一对空括号，
     * 看起来像排版坏了，而不像"这个名字是空的"。
     *
     * <p>轴下标越界（图表只有一根轴）时返回空串：0 号轴永远存在（{@code Chart} 的
     * 构造就要求至少一根），1 号轴可能没有，那时没有 y 轴标题也不该抛异常。
     */
    private static String axisTitleText(Chart chart, int dim) {
        if (dim >= chart.axes().size()) {
            return "";
        }
        AxisRange range = chart.axis(dim).range();
        String name = range.name();
        String unit = range.unit();
        if (unit.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return unit;
        }
        return name + UNIT_PREFIX + unit + ")";
    }

    private static boolean isHorizontal(ChartSide side) {
        return side == ChartSide.TOP || side == ChartSide.BOTTOM;
    }

    /**
     * 某一方向上刻度文字要占的预留量（像素）。**两条来源，二选一**。
     *
     * <ul>
     *   <li>{@link AxisStyle#visible()} <b>关着</b>（默认）：刻度文字由调用方画，
     *       所以用它声明的量（{@code Chart.tickLabelReserve}）。<b>既有行为一字不变。</b></li>
     *   <li><b>开着</b>：刻度文字由库自己画，就用库自己算的量
     *       （{@code tickLength + 字号 × LINE_HEIGHT_FACTOR}），
     *       <b>调用方声明的那个被忽略</b>。</li>
     * </ul>
     *
     * <p><b>为什么是覆盖而不是相加 / 取大</b>：后两者都会让"绘图区到底多大"有两个来源，
     * 而绘图区算错只表现为"图小了一圈"，不会有任何报错。覆盖只有一条规则。
     *
     * <p>这里用"字号 × {@link #LINE_HEIGHT_FACTOR}"而不是去量真实字宽，与
     * {@link ChartTextMetrics} 的类文档是同一条口径：<b>布局要可被精确预测</b>。
     * 顺带也避开了一个循环——真要按刻度文字的实际宽度算，就得先知道有哪些刻度，
     * 而 {@code Axis.ticks()} 依赖 {@code displayLength}，那个又来自本函数算出来的绘图区。
     *
     * <p><b>已知代价</b>：y 轴上一个很宽的文字（{@code 1000000}）会在预留带边缘被切断。
     * 这与"装饰裁到带子里、不折行不省略"是同一条取舍，而且<b>看得见</b>。
     * 要更多空间请给整张图加 {@link Chart#padding}。
     */
    private static float tickLabelReserve(Chart chart, ChartSide side,
                                          ChartTextMetrics metrics) {
        AxisStyle style = chart.axisStyle();
        if (style.visible() && style.tickLabelsVisible()) {
            return style.tickLength() + lineHeight(style.tickLabelFontSize(), metrics);
        }
        return chart.tickLabelReserve(side);
    }

    private static float lineHeight(float fontSize, ChartTextMetrics metrics) {
        return metrics.lineHeight(fontSize);
    }

    /** 每一行文字需要的高度（与字体无关，见类文档）。 */

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
            // 文字垂直居中于带子：基线 = 带子中心 + 半个字面高。
            // **这是近似**，而且这一点是显式声明的——见 CENTER_BASELINE_FACTOR 的文档。
            float baseline = band.y
                    + (band.height + fontSize * (2f * CENTER_BASELINE_FACTOR)) * 0.5f;
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
                        y + (rowH + fontSize * (2f * CENTER_BASELINE_FACTOR)) * 0.5f));
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
     * x 轴（0 号轴）的标题；没有（{@code axisTitlesVisible} 关着，或名字与单位都空）时为 null。
     */
    public AxisTitle xAxisTitle() {
        return xAxisTitle;
    }

    /**
     * y 轴（1 号轴）的标题；没有时为 null——包括"图表只有一根轴"这种情形
     * （那时没有 1 号轴可取，不该抛异常）。
     */
    public AxisTitle yAxisTitle() {
        return yAxisTitle;
    }

    /**
     * 绘图区（数据区域）。
     *
     * <p><b>不设标题、不设图例、外边距为 0 时它与 {@link #frame()} 逐字段相等。</b>
     */
    public Rect plotRect() {
        return plotRect;
    }

    /**
     * 一条轴标题：文字 + 它所在的带子 + 文字基线的 y。
     *
     * <p>与 {@link LegendItem} 一样，这里给的是<b>文字</b>的落点
     * （{@code (x, baseline)}，与 {@code Gc.drawText} 同口径），
     * 而 {@code rect} 只是带子——绘制那一层不需要再算任何坐标。
     *
     * @param text     标题文字（由 {@code AxisRange} 的 name / unit 拼成）
     * @param rect     轴标题带（设备像素），也是它的裁剪矩形
     * @param x        文字起点 x
     * @param baseline 文字的基线 y
     */
    public record AxisTitle(String text, Rect rect, float x, float baseline) {
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
}
