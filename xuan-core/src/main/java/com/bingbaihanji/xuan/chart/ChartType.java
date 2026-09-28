package com.bingbaihanji.xuan.chart;

/**
 * 图型标签：<strong>属性组合，不是类层级</strong>。
 *
 * <h2>为什么不给每种图型一个类</h2>
 * <p>这条抄 chart-fx：它用 {@code ErrorStyle × LineStyle × 布尔量} 覆盖了大部分 2D 图型，
 * <strong>零新类出很多图型</strong>。要是给每种图型一个类，每个类都要重新回答
 * "数据怎么取、轴怎么用、顶点怎么发"，而这些问题的答案在两两之间几乎相同。
 *
 * <h2>边界（规格 §12 R3）</h2>
 * <p>属性组合管"<strong>同一套顶点怎么画</strong>"，独立渲染器管"<strong>顶点怎么来</strong>"。
 * 热力图与眼图属于后者：它们的顶点不是"每个样本一个点"，而是"每个像素列一个直方图"，
 * 不是属性组合能表达的，会是独立的 {@link SeriesRenderer}。
 * 枚举里留着它们的常量，是为了让系列能声明自己的图型，而不是让一个折线渲染器
 * 去猜"这个系列我画不了"。
 *
 * <h2>{@link #polylineFamily()} 的语义只是"顶点来自逐样本"</h2>
 * <p>它回答的是<b>顶点从哪来</b>（每个样本一两个点），<b>不是</b>"能不能由同一个渲染器画"。
 * 这两件事曾经被写成一件事——那时家族里只有折线与散点，恰好每个图型一个渲染器，
 * 于是"在家族里"与"折线渲染器画得出来"看起来等价。{@link #STEP} / {@link #AREA} /
 * {@link #BAR} 每个都有自己的渲染器（顶点怎么画不同：阶梯要拐角、面积要基线、
 * 柱子是矩形），却仍然在家族里（它们的顶点确实来自逐样本的点）。
 *
 * <p>因此<b>家族内也不许跨渲染器兜底</b>：{@link #BAR} 交给折线渲染器会画成一串
 * 牵在一起的方块、{@link #AREA} 交给它则整个填充消失，而两者的画面都"看起来正常"。
 * 每个渲染器只接受它自己那几个图型，其余一律抛异常（见 {@code ChartRenderer} 的
 * 查表与各渲染器的 {@code requireSupported}）。
 *
 * <h2>渲染器必须明确报错，不许静默不画</h2>
 * <p>渲染器遇到自己不支持的图型，必须抛异常或明确报错。
 * <strong>静默什么都不画正是本项目最典型的"静默错误输出"</strong>：
 * 画面里少了一条曲线，与"这条曲线没数据"在视觉上完全一样。
 */
public enum ChartType {

    /** 折线：样本之间连线。 */
    LINE(Flag.CONNECTS),

    /** 散点：只画标记点，不连线。 */
    SCATTER(Flag.MARKERS),

    /** 折线 + 标记点。 */
    LINE_AND_MARKERS(Flag.CONNECTS | Flag.MARKERS),

    /** 阶梯线：连线按"先横后竖"走，适合枚举值与计数值。 */
    STEP(Flag.CONNECTS | Flag.STEPPED),

    /** 面积图：折线下方填充。 */
    AREA(Flag.CONNECTS | Flag.FILLS),

    /** 柱状：每个样本一个矩形，不连线。 */
    BAR(Flag.BARS),

    /** 热力图：顶点来自密度/列直方图，由独立渲染器实现。 */
    HEATMAP(0),

    /** 瀑布图：滚动的一列一列，由独立渲染器实现。 */
    WATERFALL(0),

    /**
     * 频谱：数据是频域幅度，由 GPU 上的 FFT 算出（子项目 ③-1）。
     *
     * <p><strong>它不是"折线的一种画法"，是"顶点怎么来"的问题</strong>——
     * 与热力图、瀑布图同类，所以 {@link #polylineFamily()} 对它返回 {@code false}，
     * 由独立的 {@code SpectrumSeriesRenderer} 实现（见本类文档关于
     * "<b>属性组合管"同一套顶点怎么画"，独立渲染器管"顶点怎么来"</b>"那条边界）。
     */
    SPECTRUM(0);

    private final int flags;

    ChartType(int flags) {
        this.flags = flags;
    }

    /** 是否把样本连成折线。 */
    public boolean connectsSamples() {
        return (flags & Flag.CONNECTS) != 0;
    }

    /** 是否在样本位置画标记点。 */
    public boolean drawsMarkers() {
        return (flags & Flag.MARKERS) != 0;
    }

    /** 是否按"先横后竖"走阶梯。 */
    public boolean stepped() {
        return (flags & Flag.STEPPED) != 0;
    }

    /** 是否填充折线下方。 */
    public boolean fillsUnderCurve() {
        return (flags & Flag.FILLS) != 0;
    }

    /** 是否画柱。 */
    public boolean drawsBars() {
        return (flags & Flag.BARS) != 0;
    }

    /**
     * 是否是"折线族"图型：顶点来自逐样本的一两个点。
     *
     * <p>返回 false 的图型（热力图、瀑布图、频谱）的顶点不是逐样本来的，
     * 必须由各自的独立渲染器处理。
     *
     * <p><strong>返回 true 不等于"折线渲染器画得出来"</strong>——阶梯、面积、柱状都在家族里，
     * 却各有各的渲染器，理由见本类文档里那条"语义只是顶点来自逐样本"。
     *
     * @return 折线族为 true
     */
    public boolean polylineFamily() {
        return connectsSamples() || drawsMarkers() || drawsBars();
    }

    private static final class Flag {

        static final int CONNECTS = 1;

        static final int MARKERS = 2;

        static final int STEPPED = 4;

        static final int FILLS = 8;

        static final int BARS = 16;

        private Flag() {
        }
    }
}
