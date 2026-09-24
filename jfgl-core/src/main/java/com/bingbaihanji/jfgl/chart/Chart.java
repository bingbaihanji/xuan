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

    private static void requireNonNegative(float value, String what) {
        if (!(value >= 0f)) {
            throw new IllegalArgumentException(what + "不能为负（也不会是 NaN），实际为 " + value);
        }
    }
}
