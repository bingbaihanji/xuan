package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.util.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 图表交互状态与纯计算命中器。
 *
 * <p>输入线程只更新指针，渲染线程在绘制时调用 {@link #probe(Chart, Rect)}。
 * 命中规则只有一条，静态图、流式图与 NaN 缺口共用它：**在可见窗口内的样本里
 * 取离指针最近的那个**（窗口外的一律不考虑——理由见 {@code probe} 的
 * 「只考虑可见窗口内的样本」一节，那条是正确性而不是优化）。
 *
 * <p>扫描是线性的：先按窗口判据跳过，再算屏幕距离。大数据量场景可以在这里换成
 * 二分/LOD 索引，**不需要修改 GL 后端或 JavaFX 层**——但要先解决一个前提，
 * 见 {@code probe} 的「复杂度」一节（当前没有"x 随下标单调"的契约）。
 */
public final class ChartInteraction {

    private record Pointer(float x, float y) {
    }

    private final AtomicReference<Pointer> pointer = new AtomicReference<>();
    private volatile ChartInteractionConfig config = ChartInteractionConfig.defaults();

    public ChartInteractionConfig config() {
        return config;
    }

    public ChartInteraction setConfig(ChartInteractionConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("交互配置不能为 null");
        }
        config = value;
        return this;
    }

    /** 更新设备像素坐标。坐标应与 ChartLayout.plotRect() 使用同一坐标系。 */
    public void updatePointer(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            clearPointer();
            return;
        }
        pointer.set(new Pointer(x, y));
    }

    public void clearPointer() {
        pointer.set(null);
    }

    public boolean hasPointer() {
        return pointer.get() != null;
    }

    /**
     * 计算当前指针最近的逐样本点；无命中时返回 null。
     *
     * <h2>只考虑可见窗口内的样本</h2>
     *
     * <p>判据是 <strong>样本的 x 值落在 x 轴的 <em>数据值</em>窗口内</strong>
     * （{@code x ∈ [windowMin, windowMax]}）。这一条不是优化，是<strong>正确性</strong>：
     * {@link Axis#dataToDisplay(double)} 不夹取（{@code (value-windowMin)/(windowMax-windowMin)}，
     * 无界），所以窗口外的样本会被映射到绘图区<em>之外</em>——而
     * {@code ChartRenderer} 的十字线与命中点都被裁在绘图区内。
     * 窗口起点不是采样间隔整数倍时（流式滚动图正是如此），窗口外紧邻的那个样本
     * 可能只落在绘图区外几个像素，于是它比任何<em>可见</em>样本都更靠近指针 ⇒
     * 命中的是它，后果是：纵向十字线与命中点被整条裁掉（看上去只有横线、没有命中点），
     * 而提示框照常报出一个用户根本看不见的样本的读数。**没有任何报错。**
     *
     * <h2>y 不做这个过滤，是刻意的</h2>
     *
     * <p>值超出 y 窗口只意味着"它现在在视野上下之外"，而"这个 x 上的样本值是多少"
     * 仍然是有意义的答案（纵轴放大时尤其如此）。x 则不同：x 轴是<em>样本轴</em>，
     * 出窗口就意味着这个样本根本不在当前视图里。两者的处置不同是有理由的，不是漏了。
     *
     * <h2>与渲染器的隐性耦合：x 从哪来</h2>
     *
     * <p>本方法用 <strong>{@code value(0, i)}</strong>（用户 append 的 x 值）定 x，
     * 而 {@code ChartRenderLayout.screenX} 用的是<strong>样本下标</strong>
     * （{@code fraction(index, xMin, xMax)}）。两者只在"维度 0 的值恰好就是样本下标"
     * 时逐点一致——也就是 {@code append(i, v)} 这种用法（本仓库所有示例、
     * 以及频谱的 {@code x = bin 下标} 都是这么写的）。
     * **这依赖一条不成文的约定**：{@code ChartData} 的文档只说了 {@code index} 是
     * "相对于当前可见窗口的下标"，没有说维度 0 必须等于它。
     * 若哪天有人 append 真时间戳而轴窗口也按时间戳设置，这两条会分家
     * （那时渲染器那一侧是错的，本方法是对的）——**那条路今天没有被任何断言覆盖**。
     *
     * <h2>复杂度</h2>
     *
     * <p>仍是 {@code O(itemCount())} 的全量扫描，只是每个样本先做两次比较就能跳过
     * （窗口判据在算屏幕坐标<em>之前</em>）。**没有做成"只遍历窗口内那一段下标"**：
     * 那要求"x 值随下标单调"，而 {@code ChartData} 没有这条契约
     * （{@code value(0, i)} 返回的是用户 append 的原始值，可以是时间戳、也可以是任意数）。
     * 提示框文本**只在循环结束后为最终命中点构造一次**——它以前在每一个
     * "更近的候选"上都要构造（建 {@code ArrayList} + 调 formatter），
     * 密集数据下那是这一趟扫描里最贵的一部分，而 {@code tooltipVisible == false} 时
     * 照样在执行。
     */
    public ChartHover probe(Chart chart, Rect plot) {
        if (chart == null || plot == null || !config.enabled()) {
            return null;
        }
        Pointer p = pointer.get();
        if (p == null || p.x < plot.x || p.y < plot.y
                || p.x > plot.x + plot.width || p.y > plot.y + plot.height) {
            return null;
        }
        if (chart.axes().size() < 2) {
            return null;
        }
        Axis xAxis = chart.axis(0);
        Axis yAxis = chart.axis(1);
        double windowMin = xAxis.windowMin();
        double windowMax = xAxis.windowMax();

        Series bestSeries = null;
        int bestIndex = -1;
        double bestX = 0;
        double bestY = 0;
        float bestSx = 0;
        float bestSy = 0;
        double bestDistance = (double) config.snapRadius() * config.snapRadius();

        for (Series series : chart.allSeries()) {
            if (!series.type().polylineFamily() || series.data().itemCount() == 0) {
                continue;
            }
            int count = series.data().itemCount();
            for (int i = 0; i < count; i++) {
                double x = series.data().value(0, i);
                // 窗口判据放在最前面：它是两次比较，而下面那两行各有两次除法。
                if (x < windowMin || x > windowMax) {
                    continue;
                }
                double y = series.data().value(1, i);
                if (!Double.isFinite(x) || !Double.isFinite(y)) {
                    continue;
                }
                float sx = (float) (plot.x + xAxis.dataToDisplay(x));
                float sy = (float) (plot.y + plot.height - yAxis.dataToDisplay(y));
                double dx = sx - p.x;
                double dy = sy - p.y;
                double distance = dx * dx + dy * dy;
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    bestSeries = series;
                    bestIndex = i;
                    bestX = x;
                    bestY = y;
                    bestSx = sx;
                    bestSy = sy;
                }
            }
        }
        if (bestSeries == null) {
            return null;
        }
        return new ChartHover(bestSeries, bestIndex, bestX, bestY, bestSx, bestSy,
                tooltipLines(bestSeries, bestX, bestY, xAxis.range(), yAxis.range()));
    }

    private List<ChartHover.Line> tooltipLines(Series series, double x, double y,
                                                AxisRange xRange, AxisRange yRange) {
        List<ChartHover.Line> lines = new ArrayList<>(2);
        lines.add(new ChartHover.Line(axisLabel(xRange), config.formatter().format(x, xRange)));
        String seriesLabel = series.name();
        String yLabel = axisLabel(yRange);
        if (!seriesLabel.isBlank()) {
            yLabel = seriesLabel + " / " + yLabel;
        }
        lines.add(new ChartHover.Line(yLabel, config.formatter().format(y, yRange)));
        return lines;
    }

    private static String axisLabel(AxisRange range) {
        if (range.name().isBlank()) {
            return range.unit().isBlank() ? "值" : range.unit();
        }
        return range.unit().isBlank() ? range.name() : range.name() + " (" + range.unit() + ")";
    }
}
