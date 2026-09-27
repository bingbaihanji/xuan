package com.bingbaihanji.jfgl.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 装配根：轴 + 层 + 系列。
 *
 * <h2>它不是场景图节点</h2>
 * <p>规格 §9：JFGL 里 <strong>渲染器是纯函数</strong>（数据 + 轴 → 顶点），
 * 轴、网格、图例各自独立，<strong>都不是场景图节点</strong>。
 * 这一条是本项目相对 chart-fx 的刻意取舍——它的 {@code AbstractRenderer extends Parent}，
 * 渲染器本身就是节点，逻辑与节点树耦合。
 *
 * <h2>顺序就是一切</h2>
 * <p>{@link #allSeries()} 的顺序是"层序优先、层内其次"，它<strong>就是绘制顺序</strong>，
 * 也就是 2D 的 z 序。本项目 CLAUDE.md 的规则是"绝不重排"，这条顺序因此有测试钉着。
 *
 * <h2>线程</h2>
 * <p>非线程安全。装配发生在应用线程，绘制发生在 GL 线程；本期约定两者不同时进行
 * （数据的并发由 {@link RingChartData} 自己负责，与装配无关）。
 */
public final class Chart {

    private final List<Axis> axes = new ArrayList<>();

    private final List<Layer> layers = new ArrayList<>();

    private final ChartInteraction interaction = new ChartInteraction();

    // ------------------------------------------------------------------
    // 装配配置：标题、图例、间距
    //
    // 它们**不影响绘制顺序，也不影响任何系列的几何**——只被 ChartLayout 用来把外框
    // 切成"标题带 / 图例带 / 绘图区"。全部默认值都是"不留白"：
    // 不设标题、图例为空、外边距为 0 时，绘图区与外框逐字段相等，
    // 于是画面与"不经过 ChartLayout"时逐像素相同（这条有单测钉着）。
    // ------------------------------------------------------------------

    /** 标题；空串表示不显示（默认空）。 */
    private String title = "";

    /** 标题放在哪一边。默认 {@link ChartSide#TOP}（与 JavaFX 一致）。 */
    private ChartSide titleSide = ChartSide.TOP;

    /** 标题字号（像素），默认 15。 */
    private float titleFontSize = 15f;

    /** 标题带与它下面那块之间的间隙（像素），默认 6。 */
    private float titleGap = 6f;

    /**
     * 是否显示图例。默认 <b>true</b>（与 JavaFX 一致：那边的 Chart 默认带图例）。
     *
     * <p>没有系列（或者层里一个系列都没有）时图例带的高度是 0，不占地方——
     * 所以"默认显示"不会给一张空图凭空加一条空白带。
     */
    private boolean legendVisible = true;

    /** 图例放在哪一边。默认 {@link ChartSide#BOTTOM}（与 JavaFX 一致）。 */
    private ChartSide legendSide = ChartSide.BOTTOM;

    /** 图例文字的字号（像素），默认 12。 */
    private float legendFontSize = 12f;

    /** 图例色块的边长（像素），默认 10。 */
    private float legendSwatchSize = 10f;

    /** 图例带与绘图区之间的间隙（像素），默认 8。 */
    private float legendGap = 8f;

    /** 外边距（内框 = 外框扣掉它），默认全 0。 */
    private ChartInsets padding = ChartInsets.NONE;

    // ------------------------------------------------------------------
    // 轴标题与刻度文字的预留带
    //
    // 刻度文字在本库里是**调用方画的**（`Axis.ticks()` 给位置，画由应用做），
    // 所以图表层不可能知道它占多高——这就是"可配置的预留量"而不是"自动测量"的理由。
    // 图表层能做的只有两件：把绘图区往里让出调用方声明的量，再把轴标题画在那之外。
    // ------------------------------------------------------------------

    /**
     * 是否显示轴标题（{@code AxisRange} 的 name / unit）。默认 <b>false</b>。
     *
     * <p><b>为什么默认关着</b>（与 JavaFX 的"设了 label 就显示"不同）：
     * 打开它会让绘图区让出两条带子，而**绘图区变了就是画面变了**——
     * 本库所有既有的图（含五个校验器里那些按像素钉着的期望值）都会整体挪几像素。
     * 那正是 {@link ChartLayout} 反复强调不许发生的事（"给标题留位置不许悄悄改动
     * 已验证的行为"）。新能力默认打开，与悄悄改行为没有区别。
     *
     * <p>数据本来就带了名字与单位（{@link AxisRange} 的 name / unit），所以打开之后
     * 不需要再配置一次文字：{@code 轴 0} 的 name/unit → x 轴标题，{@code 轴 1} → y 轴标题。
     */
    private boolean axisTitlesVisible = false;

    /** 轴标题字号（像素），默认 12。 */
    private float axisTitleFontSize = 12f;

    /** 轴标题带与它内侧那块（刻度预留 / 绘图区）之间的间隙（像素），默认 4。 */
    private float axisTitleGap = 4f;

    /**
     * x 轴刻度文字的预留量（像素，绘图区**下方**）。
     *
     * <p>默认 0（不让地方）——理由与 {@link #axisTitlesVisible} 同：让地方就是改画面。
     */
    private float bottomTickLabelReserve = 0f;

    /** y 轴刻度文字的预留量（像素，绘图区**左侧**）。默认 0。 */
    private float leftTickLabelReserve = 0f;

    /**
     * 坐标系（网格 / 轴线 / 箭头 / 刻度线 / 刻度文字）的外观与开关。
     *
     * <p><strong>默认整体关着</strong>（见 {@link AxisStyle#defaults()}）——理由与
     * {@link #axisTitlesVisible} 完全一样：开了轴绘图区就要让出带子，而"绘图区变了
     * 就是画面变了"。默认关是**纯增量**成立的前提。
     *
     * <p>打开之后本库**自己画刻度文字**，于是刻度预留也由库自己算——
     * 那两处 {@link #tickLabelReserve} 会被**覆盖**（不是相加），理由见
     * {@link #tickLabelReserve} 的说明。
     */
    private AxisStyle axisStyle = AxisStyle.defaults();

    /**
     * 构造，至少给一根轴。
     *
     * @param axes 各维度的轴，顺序即维度下标
     * @throws IllegalArgumentException 一根轴都没有时
     */
    public Chart(Axis... axes) {
        if (axes == null || axes.length == 0) {
            throw new IllegalArgumentException("至少要给一根轴");
        }
        for (Axis axis : axes) {
            if (axis == null) {
                throw new IllegalArgumentException("轴不能为 null");
            }
            this.axes.add(axis);
        }
    }

    /**
     * 追加一根轴（多一个维度）。
     *
     * @param axis 轴
     * @return 自身，便于链式调用
     */
    public Chart addAxis(Axis axis) {
        if (axis == null) {
            throw new IllegalArgumentException("轴不能为 null");
        }
        axes.add(axis);
        return this;
    }

    /**
     * 按维度下标取轴。
     *
     * @param dim 维度下标
     * @return 轴
     * @throws IllegalArgumentException 下标越界时
     */
    public Axis axis(int dim) {
        if (dim < 0 || dim >= axes.size()) {
            throw new IllegalArgumentException(
                    "维度下标越界：" + dim + "，共 " + axes.size() + " 根轴");
        }
        return axes.get(dim);
    }

    /**
     * 全部轴，按维度下标排列。它的长度就是数据的维度数——
     * {@link SeriesRenderer#render} 收到的 {@code axes[]} 正是这个列表。
     *
     * @return 不可修改的列表
     */
    public List<Axis> axes() {
        return Collections.unmodifiableList(axes);
    }

    /**
     * 加一层。层的添加顺序即绘制顺序。
     *
     * @param name 层名
     * @return 新建的层
     * @throws IllegalArgumentException 层名为空时
     */
    public Layer addLayer(String name) {
        Layer layer = new Layer(name);
        layers.add(layer);
        return layer;
    }

    /**
     * 全部层，按添加顺序。
     *
     * @return 不可修改的列表
     */
    public List<Layer> layers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * 全部系列，按绘制顺序（层序优先、层内其次）。
     *
     * <p>它同时是<b>图例项的顺序</b>——图例的顺序与画面的 z 序一致，
     * 于是"图例里第 3 项"与"画面上第 3 个画上去的系列"是同一个东西。
     *
     * @return 不可修改的列表
     */
    public List<Series> allSeries() {
        List<Series> out = new ArrayList<>();
        for (Layer layer : layers) {
            out.addAll(layer.series());
        }
        return Collections.unmodifiableList(out);
    }

    /** 返回线程安全的 hover 交互状态。 */
    public ChartInteraction interaction() {
        return interaction;
    }

    // ------------------------------------------------------------------
    // 标题
    // ------------------------------------------------------------------

    /** 标题；空串表示不显示。 */
    public String title() {
        return title;
    }

    /**
     * 设置标题。空串（或 null）表示不显示。
     *
     * @return 自身，便于链式调用
     */
    public Chart title(String value) {
        this.title = value == null ? "" : value;
        return this;
    }

    /** 标题放在哪一边。 */
    public ChartSide titleSide() {
        return titleSide;
    }

    /**
     * 设置标题的位置。
     *
     * <p><b>只支持 {@link ChartSide#TOP} 与 {@link ChartSide#BOTTOM}</b>：
     * 左右放的标题要把文字转 90°，而本层的绘制入口只有"横排文字"这一种
     * （图例的左右都是横排，所以那边四种都支持）。明确抛异常而不是把标题画成横的——
     * 横着放的标题看起来只是"位置怪"，不像少了个能力。
     *
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 传 LEFT / RIGHT 时
     */
    public Chart titleSide(ChartSide side) {
        if (side == null) {
            throw new IllegalArgumentException("标题位置不能为 null");
        }
        if (side == ChartSide.LEFT || side == ChartSide.RIGHT) {
            throw new IllegalArgumentException(
                    "标题只支持上下（TOP / BOTTOM），不支持 " + side
                            + "：左右放的标题要转 90°，而图表层的绘制入口只有横排文字。"
                            + "真想要竖排标题，请把它当成普通图元自己画（Gc.rotate + drawText）。");
        }
        this.titleSide = side;
        return this;
    }

    /** 标题字号（像素）。 */
    public float titleFontSize() {
        return titleFontSize;
    }

    /**
     * 设置标题字号。
     *
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 非正或非有限时（字号会一路进到行高与基线里）
     */
    public Chart titleFontSize(float px) {
        requirePositiveFinite(px, "标题字号");
        this.titleFontSize = px;
        return this;
    }

    /** 标题带与它下面那块之间的间隙（像素）。 */
    public float titleGap() {
        return titleGap;
    }

    /** 设置标题与下方内容之间的间隙。 */
    public Chart titleGap(float px) {
        requireNonNegative(px, "标题间隙");
        this.titleGap = px;
        return this;
    }

    // ------------------------------------------------------------------
    // 图例
    // ------------------------------------------------------------------

    /** 是否显示图例。 */
    public boolean legendVisible() {
        return legendVisible;
    }

    /** 设置是否显示图例。 */
    public Chart legendVisible(boolean visible) {
        this.legendVisible = visible;
        return this;
    }

    /** 图例放在哪一边。 */
    public ChartSide legendSide() {
        return legendSide;
    }

    /**
     * 设置图例的位置。四种都支持（左右放的图例是一列横排文字，不需要旋转）。
     *
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 为 null 时
     */
    public Chart legendSide(ChartSide side) {
        if (side == null) {
            throw new IllegalArgumentException("图例位置不能为 null");
        }
        this.legendSide = side;
        return this;
    }

    /** 图例文字的字号（像素）。 */
    public float legendFontSize() {
        return legendFontSize;
    }

    /** 设置图例文字的字号。 */
    public Chart legendFontSize(float px) {
        requirePositiveFinite(px, "图例字号");
        this.legendFontSize = px;
        return this;
    }

    /** 图例色块的边长（像素）。 */
    public float legendSwatchSize() {
        return legendSwatchSize;
    }

    /** 设置图例色块的边长。0 表示不要色块（只留文字）。 */
    public Chart legendSwatchSize(float px) {
        requireNonNegative(px, "图例色块边长");
        this.legendSwatchSize = px;
        return this;
    }

    /** 图例带与绘图区之间的间隙（像素）。 */
    public float legendGap() {
        return legendGap;
    }

    /** 设置图例与绘图区之间的间隙。 */
    public Chart legendGap(float px) {
        requireNonNegative(px, "图例间隙");
        this.legendGap = px;
        return this;
    }

    // ------------------------------------------------------------------
    // 轴标题与刻度文字的预留带
    // ------------------------------------------------------------------

    /** 是否显示轴标题（{@code AxisRange} 的 name / unit）。默认 {@code false}，理由见字段说明。 */
    public boolean axisTitlesVisible() {
        return axisTitlesVisible;
    }

    /**
     * 设置是否显示轴标题。
     *
     * <p>打开它会把绘图区让出两条带子（x 轴标题在下方、y 轴标题在左侧），
     * 让出来的宽度由{@code 轴 0 / 轴 1} 的 name / unit 与 {@link #axisTitleFontSize} 决定。
     *
     * @return 自身，便于链式调用
     */
    public Chart axisTitlesVisible(boolean visible) {
        this.axisTitlesVisible = visible;
        return this;
    }

    /** 轴标题的字号（像素）。 */
    public float axisTitleFontSize() {
        return axisTitleFontSize;
    }

    /**
     * 设置轴标题字号（同时决定轴标题带的高度，见 {@link ChartLayout} 的尺寸规则）。
     *
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 非正或非有限时
     */
    public Chart axisTitleFontSize(float px) {
        requirePositiveFinite(px, "轴标题字号");
        this.axisTitleFontSize = px;
        return this;
    }

    /** 轴标题带与它内侧那块之间的间隙（像素）。 */
    public float axisTitleGap() {
        return axisTitleGap;
    }

    /** 设置轴标题带与内侧那块之间的间隙。 */
    public Chart axisTitleGap(float px) {
        requireNonNegative(px, "轴标题间隙");
        this.axisTitleGap = px;
        return this;
    }

    /**
     * 坐标系（网格 / 轴线 / 箭头 / 刻度线 / 刻度文字）的外观与开关。默认整体关着。
     *
     * @see AxisStyle
     */
    public AxisStyle axisStyle() {
        return axisStyle;
    }

    /**
     * 设置坐标系样式。**打开它需要显式 {@code .visible(true)}**——默认关是纯增量成立的前提
     * （见 {@link #axisStyle} 字段的说明）。
     *
     * <p>打开之后本库自己画刻度文字，那两处 {@link #tickLabelReserve} 会被**覆盖**。
     *
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 传入 null 时
     */
    public Chart axisStyle(AxisStyle style) {
        if (style == null) {
            throw new IllegalArgumentException("坐标系样式不能为 null（要关掉请用 "
                    + "AxisStyle.defaults()，它的 visible 默认就是 false）");
        }
        this.axisStyle = style;
        return this;
    }

    /**
     * 刻度文字的预留量（像素）。
     *
     * <p><b>它为什么是一个配置项，而不是自动测量</b>：刻度文字**默认由调用方画**
     * （{@code Axis.ticks()} 只给位置），图表层拿不到它的高度；硬猜一个值的话，
     * 字号一大刻度文字就压在数据上——而"刻度文字和数据线重叠"看起来像绘图区太小，
     * 不像配置问题。所以这里让调用方声明自己画的刻度文字占多少，
     * 图表层负责把它从绘图区里扣掉。
     *
     * <h2>⚠️ 但 {@link AxisStyle#visible()} 打开时，这个量会被**覆盖**</h2>
     *
     * <p>那种配置下刻度文字由**库自己画**，于是它自己知道文字占多高，
     * 就用自己算的那个（{@code tickLength + 字号 × LINE_HEIGHT_FACTOR}），
     * **本方法的返回值不再参与布局**。
     *
     * <p><b>为什么是覆盖，不是相加、也不是取大</b>：后两者都会让"绘图区到底多大"
     * 有两个来源，而绘图区算错只表现为"图小了一圈"，不会有任何报错。
     * 覆盖只有一条规则，一眼能算出来。
     *
     * <p>要更多空间（比如 y 轴文字很宽），请给整张图加 {@link #padding}——
     * 那是一个方向明确的手段，而"两边都声明一点"不是。
     *
     * @param side {@link ChartSide#BOTTOM}（x 轴刻度，绘图区下方）或
     *             {@link ChartSide#LEFT}（y 轴刻度，绘图区左侧）
     * @return 该方向当前的预留量
     * @throws IllegalArgumentException side 为 null 时
     */
    public float tickLabelReserve(ChartSide side) {
        if (side == null) {
            throw new IllegalArgumentException("方向不能为 null");
        }
        if (side == ChartSide.BOTTOM) {
            return bottomTickLabelReserve;
        }
        if (side == ChartSide.LEFT) {
            return leftTickLabelReserve;
        }
        throw new IllegalArgumentException(
                "刻度文字的预留量只支持 BOTTOM 与 LEFT，实际为 " + side + "。"
                        + "本库的约定是：x 轴（下标轴）的刻度画在绘图区下方、y 轴（数值轴）"
                        + "画在左侧（见 README 的图表一节）。上面/右边的刻度文字没有对应的"
                        + "预留带——收下它然后什么也不做，就是「设了但没用」这种静默失效。");
    }

    /**
     * 设置某一侧的刻度文字预留量。
     *
     * @param side {@link ChartSide#BOTTOM} 或 {@link ChartSide#LEFT}
     * @param px   预留量（像素），0 表示不让地方
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException side 不是 BOTTOM/LEFT，或 px 为负/非有限时
     */
    public Chart tickLabelReserve(ChartSide side, float px) {
        requireNonNegative(px, "刻度文字预留量");
        // 先让上面的 getter 校验方向，再赋值——两处判断只留一份。
        tickLabelReserve(side);
        if (side == ChartSide.BOTTOM) {
            this.bottomTickLabelReserve = px;
        } else {
            this.leftTickLabelReserve = px;
        }
        return this;
    }

    /**
     * 一次设置两边的刻度文字预留量（x 轴与 y 轴刻度通常一样高）。
     *
     * @param px 预留量（像素）
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException px 为负/非有限时
     */
    public Chart tickLabelReserve(float px) {
        tickLabelReserve(ChartSide.BOTTOM, px);
        tickLabelReserve(ChartSide.LEFT, px);
        return this;
    }

    // ------------------------------------------------------------------
    // 外边距
    // ------------------------------------------------------------------

    /** 外边距。 */
    public ChartInsets padding() {
        return padding;
    }

    /**
     * 设置外边距（绘图的整块地方往内收多少）。
     *
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 为 null 时
     */
    public Chart padding(ChartInsets insets) {
        if (insets == null) {
            throw new IllegalArgumentException("外边距不能为 null（不要留白就传 ChartInsets.NONE）");
        }
        this.padding = insets;
        return this;
    }

    private static void requirePositiveFinite(float value, String what) {
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IllegalArgumentException(what + "必须是正的有限数，实际为 " + value);
        }
    }

    /**
     * 非负且有限。
     *
     * <p>非有限数一并拒掉的理由与 {@link Series} 那边相同：{@code Infinity} 的间隙会让
     * 绘图区缩成 0（在画面上等于"这张图没数据"），而 {@code NaN} 会一路传进矩形的算术里，
     * 那些 {@code Math.max(0f, ...)} 的兜底都拦不住它——两者都没有一个"明确的处置"。
     */
    private static void requireNonNegative(float value, String what) {
        if (!Float.isFinite(value) || value < 0f) {
            throw new IllegalArgumentException(
                    what + "必须是非负的有限数，实际为 " + value);
        }
    }
}
