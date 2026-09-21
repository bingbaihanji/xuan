package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.ArrayChartData;
import com.bingbaihanji.jfgl.chart.ChartData;
import com.bingbaihanji.jfgl.chart.RingChartData;

/**
 * 把两种数据实现的索引语义归一成"绝对号"。
 *
 * <h2>为什么必须有这一层</h2>
 * <p>{@link RingChartData#itemCount()} 返回 {@code min(writeIndex, capacity)}——
 * <b>环写满之后它就永远停在 capacity 不动了</b>。拿它当"写到哪了"，
 * 增量上传会在环满之后认为没有新数据，于是<b>静默地再也不上传，画面定格在第一屏</b>。
 *
 * <p>而且 {@code RingChartData.value(dim, i)} 的 {@code i} 是<b>相对可见窗口</b>的，
 * 而 {@code ArrayChartData.value(dim, i)} 的是绝对下标。两者的差别不该泄漏到
 * {@link SeriesBuffer} 里——泄漏进去的表现是"曲线整体错位若干个样本"，
 * 画面完全正常，只有把它与源数据逐点对齐才看得出来。
 *
 * <table border="1">
 *   <caption>两种实现的语义对照</caption>
 *   <tr><th></th><th>{@code RingChartData}</th><th>{@code ArrayChartData}</th></tr>
 *   <tr><td>已写样本总数</td><td>{@code writeIndex()}，<b>一直增长</b></td>
 *       <td>{@code itemCount()}，不变</td></tr>
 *   <tr><td>{@code itemCount()} 的含义</td>
 *       <td>{@code min(writeIndex, capacity)}，<b>环满了就停住</b></td><td>点数</td></tr>
 *   <tr><td>{@code value(dim, i)} 的 {@code i}</td>
 *       <td><b>相对可见窗口</b>：{@code absolute = windowStart() + i}</td><td>绝对下标</td></tr>
 *   <tr><td>环容量</td><td>{@code capacity()}</td><td>无（按点数向上取 2 的幂）</td></tr>
 * </table>
 *
 * <h2>它是包内可见的接缝</h2>
 * <p>对外只有 {@link SeriesBuffer} 一个门面，本接口不出现在公开 API 里。
 * 包内可见还有第二个用处：测试可以注入一个<b>会倒退</b>的数据源，
 * 从而走生产路径验证 {@link SeriesBuffer} 的那句守卫——
 * 若为此在生产 API 上开一个 {@code xxxForTest} 的旁路入口，
 * 旁路与真正的守卫就是两份可以各自腐烂的代码。
 */
interface SeriesSource {

    /** 已写入的样本总数（<b>单调增长</b>，不是"可见样本数"）。 */
    long writeCount();

    /** 取某个维度上第 {@code absoluteIndex} 个样本的值；超出有效范围返回 NaN，不抛异常。 */
    double valueAt(int dim, long absoluteIndex);

    /** 环容量（2 的幂）。 */
    int capacity();

    /** 按数据的实际类型选实现。 */
    static SeriesSource of(ChartData data) {
        if (data instanceof RingChartData ring) {
            return new SeriesSource() {
                @Override
                public long writeCount() {
                    // 不是 itemCount()：那个在环满之后停住。
                    return ring.writeIndex();
                }

                @Override
                public double valueAt(int dim, long absoluteIndex) {
                    // 先把"绝对号 → 窗口内下标"的减法在 long 域里做完并判界，再窄化成 int。
                    // 反过来先窄化的话，一个远离窗口的绝对号会回绕成一个落在 [0, count) 里的
                    // 合法下标——于是读到**别的样本的值**，而且看起来完全正常（本项目最警惕的形状）。
                    long relative = absoluteIndex - ring.windowStart();
                    if (relative < 0 || relative >= ring.itemCount()) {
                        return RingChartData.GAP;
                    }
                    return ring.value(dim, (int) relative);
                }

                @Override
                public int capacity() {
                    return ring.capacity();
                }
            };
        }
        if (data instanceof ArrayChartData array) {
            int cap = capacityFor(array.itemCount());
            return new SeriesSource() {
                @Override
                public long writeCount() {
                    // 静态数据没有"写入"这回事：点数就是写入总数，且它恒定不变。
                    // 于是 SeriesBuffer 在首次上传之后每次都判定"没有新数据"。
                    return array.itemCount();
                }

                @Override
                public double valueAt(int dim, long absoluteIndex) {
                    // 绝对下标，没有窗口偏移。越界返回缺口而不是抛异常：
                    // 调用方（SeriesBuffer 的装配循环）不该为了取数再做一次边界判断。
                    if (absoluteIndex < 0 || absoluteIndex >= array.itemCount()) {
                        return RingChartData.GAP;
                    }
                    return array.value(dim, (int) absoluteIndex);
                }

                @Override
                public int capacity() {
                    return cap;
                }
            };
        }
        throw new IllegalArgumentException(
                "不认识的数据实现：" + data.getClass().getName()
                        + "。SeriesBuffer 依赖\"已写样本总数\"与\"按绝对号取数\"这两个语义，"
                        + "新实现必须在这里补上适配，否则增量上传会静默算错。");
    }

    /**
     * 下一次 2 的幂（不小于 {@code n}）。
     *
     * <p>槽位算术用 {@code & (capacity - 1)} 取模，因此容量**必须是** 2 的幂：
     * 不是的话掩码会把下标映射到错的位置，读到别的样本的值——静默错位。
     */
    static int capacityFor(int n) {
        int m = Math.max(1, n);
        int c = Integer.highestOneBit(m);
        return c < m ? c << 1 : c;
    }
}
