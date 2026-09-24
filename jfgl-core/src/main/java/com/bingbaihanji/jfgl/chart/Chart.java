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
     * @return 不可修改的列表
     */
    public List<Series> allSeries() {
        List<Series> out = new ArrayList<>();
        for (Layer layer : layers) {
            out.addAll(layer.series());
        }
        return Collections.unmodifiableList(out);
    }
}
