package com.bingbaihanji.xuan.chart;

/**
 * 渲染器：数据 + 轴 → 顶点。**自己不持有数据，也不认识场景图。**
 *
 * <h2>它是纯函数</h2>
 * <p>规格 §9：Xuan 里 {@code SeriesRenderer} 是纯函数。这个签名里没有 GL、
 * 没有场景图节点、没有 JavaFX。<strong>轴、网格、图例各自独立，都不是场景图节点。</strong>
 *
 * <p>避开 chart-fx 的三个坑（规格 §9）：它的 {@code Renderer} 接口混了三件事
 * （{@code render()} 绘制、{@code updateAxisRange()} 数据域、{@code getNode()} /
 * {@code drawLegendSymbol()} 场景图），而且 {@code AbstractRenderer extends Parent}——
 * 渲染器本身是场景图节点，逻辑与节点树耦合。
 *
 * <h2>实现者必须遵守的两条</h2>
 * <ol>
 *   <li><strong>只画脏区间。</strong>{@link ChartData#dirtyRange(long)} 是第一步就调用的东西：
 *       数据是 <strong>GPU 常驻 + 增量上传</strong>的，每帧重传整个窗口会把增量上传的好处
 *       全部抵消（规格 §5.1、§8）。顶点缓冲也应该是环，滚动用一个 uniform 表达，
 *       而不是每帧重建顶点（规格 §8）。</li>
 *   <li><strong>遇到 NaN 就断开折线，遇到不支持的图型就明确报错。</strong>
 *       数据里的 NaN（无论是丢包还是传感器故障）与
 *       {@link RingChartData#GAP} 是同一种东西：<strong>连过去的那条直线是假的，
 *       它显示了一个不存在的信号</strong>——这比不显示更糟，而且看起来完全正常。
 *       不支持 {@link ChartType} 时静默不画同样是"静默错误输出"。</li>
 * </ol>
 *
 * <h2>② 怎么实现它</h2>
 * <p>② 定义 {@code GLRenderContext extends RenderContext}，在渲染器第一行转型取得
 * 顶点写入器与当前帧状态，其余全部是纯算术。① 不需要知道这些。
 */
@FunctionalInterface
public interface SeriesRenderer {

    /**
     * 把一个系列画出来。
     *
     * @param ctx   渲染上下文，由渲染后端（②）提供并实现
     * @param data  系列的数据
     * @param series 系列（图型标签与样式都在这里）
     * @param axes  各维度的轴，长度等于数据的维度数，下标即维度
     */
    void render(RenderContext ctx, ChartData data, Series series, Axis[] axes);
}
