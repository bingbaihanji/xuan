package com.bingbaihanji.jfgl.chart;

import java.util.Arrays;

/**
 * 静态数据：一次性给出、之后整体替换的实现。
 *
 * <h2>脏区间的语义</h2>
 * <p>{@code revision} <strong>只在 {@link #replace(double[][])} 时递增</strong>。
 * 于是：
 * <ul>
 *   <li>{@code revision} 没变 → {@link DirtyRange#EMPTY}，
 *       渲染器一次上传后<strong>永不重传</strong>。这是静态科学绘图的路径。</li>
 *   <li>整体替换 → 脏区间是 {@code (0, itemCount())}，即"整个窗口都变了"。
 *       静态数据的替换是整体语义，报一个"部分脏"反而会把新旧两段拼成一条假线。</li>
 * </ul>
 *
 * <h2>为什么是深拷贝</h2>
 * <p>构造与替换都拷贝一份。调用方在别处继续改自己那个数组、而这里悄悄跟着变，
 * 是这类容器最难查的一类 bug：数据"自己变了"，而 {@code revision} 没变，
 * 于是渲染器按契约不重传——画面停在旧数据上，怎么查都查不出。
 *
 * <h2>线程</h2>
 * <p>非线程安全。静态路径的约定是：构造/替换发生在数据变化时，绘制发生在 GL 线程。
 * 需要"边采边画"请用 {@link RingChartData}。
 */
public final class ArrayChartData implements ChartData {

    private final AxisRange[] ranges;

    /** {@code values[dim][index]}。 */
    private double[][] values;

    /** 每次整体替换递增。初值取 1，让"还什么都没看过"的渲染器（传 0 进来）也能拿到脏区。 */
    private long revision = 1;

    /**
     * 构造。
     *
     * @param ranges 各维度的范围，长度即维度数，至少 1 个
     * @param values {@code values[dim][index]}，各维度长度必须一致
     * @throws IllegalArgumentException 维度数为 0、参数为 null、或各维度长度不一致时
     */
    public ArrayChartData(AxisRange[] ranges, double[][] values) {
        if (ranges == null || ranges.length == 0) {
            throw new IllegalArgumentException("至少要声明一个维度");
        }
        for (int d = 0; d < ranges.length; d++) {
            if (ranges[d] == null) {
                throw new IllegalArgumentException("第 " + d + " 个维度的范围是 null");
            }
        }
        validate(values, ranges.length);
        this.ranges = ranges.clone();
        this.values = deepCopy(values);
    }

    private static void validate(double[][] values, int dims) {
        if (values == null) {
            throw new IllegalArgumentException("values 不能为 null");
        }
        if (values.length != dims) {
            throw new IllegalArgumentException(
                    "值的维度数与声明不符：声明 " + dims + " 维，实际 " + values.length + " 维");
        }
        if (values[0] == null) {
            throw new IllegalArgumentException("第 0 维的数据是 null");
        }
        int count = values[0].length;
        for (int d = 1; d < values.length; d++) {
            if (values[d] == null) {
                throw new IllegalArgumentException("第 " + d + " 维的数据是 null");
            }
            if (values[d].length != count) {
                throw new IllegalArgumentException(
                        "各维度的长度必须一致：第 0 维是 " + count + "，第 " + d + " 维是 "
                                + values[d].length);
            }
        }
    }

    private static double[][] deepCopy(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int d = 0; d < source.length; d++) {
            copy[d] = Arrays.copyOf(source[d], source[d].length);
        }
        return copy;
    }

    /**
     * 整体替换数据。这是静态路径唯一的变更入口。
     *
     * @param newValues 新数据，各维度长度必须一致（可以与旧长度不同）
     * @throws IllegalArgumentException 维度数或长度不合法时
     */
    public void replace(double[][] newValues) {
        validate(newValues, ranges.length);
        this.values = deepCopy(newValues);
        this.revision++;
    }

    @Override
    public AxisRange axisRange(int dim) {
        if (dim < 0 || dim >= ranges.length) {
            throw new IndexOutOfBoundsException("维度下标越界：" + dim + "，共 " + ranges.length + " 维");
        }
        return ranges[dim];
    }

    @Override
    public int itemCount() {
        return values[0].length;
    }

    @Override
    public double value(int dim, int index) {
        axisRange(dim);
        return values[dim][index];
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public DirtyRange dirtyRange(long sinceRevision) {
        if (sinceRevision >= revision) {
            return DirtyRange.EMPTY;
        }
        return new DirtyRange(0, itemCount());
    }
}
