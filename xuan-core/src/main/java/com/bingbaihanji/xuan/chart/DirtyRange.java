package com.bingbaihanji.xuan.chart;

/**
 * 数据里"变了的那一段"，半开区间 {@code [firstDirty, lastDirty)}。
 *
 * <h2>为什么脏区间是一等公民</h2>
 * <p>chart-fx 的 {@code DataSet} 也有脏位，但那是<strong>每个 {@code DataSet} 自己的一张
 * 位掩码</strong>（{@code io.fair_acc.dataset.events.ChartBits}），而且它的维度是
 * <strong>「类别」（哪一类变了）</strong>，不是<strong>「区间」（哪一段变了）</strong>：
 * 按类别分成"加/删/范围/名字/样式/元数据/置换/轴布局/画布……"若干位
 * （位宽另有静态断言钉死 ≤ 32）。于是它的渲染器每帧仍要把二分查窗口、
 * 重烘屏幕坐标、重做缩减全部走一遍——脏位只说明"要重画"，没说明"重画哪一段"。
 *
 * <p>Xuan 要的是 <strong>GPU 常驻 + 增量上传</strong>：数据上传一次长期驻留，
 * 只有新增的那几十个点走 {@code glBufferSubData}。这要求"变了哪一段"能被精确表达，
 * 而不是"哪一类变了"。
 *
 * <p><strong>一个该抄没抄的改进方向（是本项目的下一步候选，不是承诺）</strong>：
 * {@code dirtyRange} 可以升级成<strong>「类别 + 区间」两级判据</strong>——
 * "只改了颜色"这类变更就根本不必去问区间（问了也只会得到整个区间）。
 * chart-fx 的类别位在这一层上是对的，缺的只是区间那一级。
 *
 * <h2>空区间的表示</h2>
 * <p>无变化时返回 {@link #EMPTY}（即 {@code (0, 0)}）。判定统一用 {@link #isEmpty()}：
 * 它是 {@code firstDirty >= lastDirty}，因此 {@code (5, 5)} 这种"贴着某个位置的空区间"
 * 也一并算空——不引入第二种空区间的表示。
 *
 * @param firstDirty 脏区间的起点（含）
 * @param lastDirty  脏区间的终点（不含）；等于 {@code firstDirty} 即空
 */
public record DirtyRange(int firstDirty, int lastDirty) {

    /** 空区间：没有任何变化。 */
    public static final DirtyRange EMPTY = new DirtyRange(0, 0);

    public DirtyRange {
        if (firstDirty < 0) {
            throw new IllegalArgumentException("firstDirty 不能为负：" + firstDirty);
        }
        if (lastDirty < firstDirty) {
            throw new IllegalArgumentException(
                    "lastDirty 不能小于 firstDirty：" + firstDirty + ".." + lastDirty);
        }
    }

    /**
     * 是否为空区间（没有任何变化）。
     *
     * @return 无变化时为 true
     */
    public boolean isEmpty() {
        return firstDirty >= lastDirty;
    }
}
