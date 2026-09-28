package com.bingbaihanji.xuan.chart;

/**
 * 一维或多维数据的只读视图，供渲染器消费。
 *
 * <h2>随机访问仍然在，但"追加"才是主角</h2>
 * <p>{@link #value(int, int)} 保留了随机访问：静态数据靠它，流式数据的可见窗口也靠它。
 * 但真正让高性能成立的是 {@link #revision()} 与 {@link #dirtyRange(long)} 这一对——
 * 它们回答的是"<strong>渲染器怎么知道哪些数据是新的</strong>"。
 *
 * <h2>索引空间</h2>
 * <p>{@code index ∈ [0, itemCount())}，是<strong>相对于当前可见窗口</strong>的下标。
 * 流式实现里窗口会滑动，因此同一个下标在不同时刻对应的样本可能不同——
 * 渲染器要判断"我看到的东西还在不在"，必须用 {@link #dirtyRange(long)} 而不是自己缓存下标。
 *
 * <h2>线程</h2>
 * <p>实现可能被采集线程写、GL 线程读（见 {@link RingChartData}）。
 * 本接口不承诺线程安全，<strong>每个实现各自在类文档里写清自己的线程约定</strong>。
 */
public interface ChartData {

    /**
     * 返回某个维度的范围与名称单位。
     *
     * @param dim 维度下标，从 0 开始
     * @return 该维度的范围
     */
    AxisRange axisRange(int dim);

    /**
     * 返回当前可见的样本数。
     *
     * <p>流式实现里这个值<strong>不会超过环形缓冲的容量</strong>：更早的样本已经被覆盖，
     * 看不见了。
     *
     * @return 可见样本数
     */
    int itemCount();

    /**
     * 读一个样本。
     *
     * @param dim   维度下标
     * @param index 可见窗口内的下标，{@code [0, itemCount())}
     * @return 样本值
     */
    double value(int dim, int index);

    /**
     * 返回当前修订号：每次内容变更递增。
     *
     * <p>渲染器把它当作"我上次看的是哪一版"的凭据，传给 {@link #dirtyRange(long)}。
     *
     * @return 修订号
     */
    long revision();

    /**
     * 返回自 {@code sinceRevision} 以来变了的那一段。
     *
     * @param sinceRevision 渲染器上次看到的修订号（{@link #revision()} 的旧值）
     * @return 脏区间；无变化时返回 {@link DirtyRange#EMPTY}
     */
    DirtyRange dirtyRange(long sinceRevision);
}
