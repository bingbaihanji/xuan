package com.bingbaihanji.xuan.chart;

/**
 * 流式数据：单生产者单消费者（SPSC）的环形缓冲。采集线程写，GL 线程读。
 *
 * <h2>⚠️ 硬前提：只有一个写者</h2>
 * <p>采集线程由使用方自己起（已确认），所以这个前提成立。
 * <strong>若将来数据源改成网络/串口回调</strong>（回调线程可能是 IO 线程池里的任意一个），
 * <strong>单生产者前提就不成立，整个无锁设计必须换掉</strong>（多写者需要 CAS 或加锁）。
 *
 * <pre>
 * 采集线程（唯一写者）                  GL 线程（唯一读者）
 *   写 data[d][w &amp; (cap-1)]              读 writeIndex（volatile，acquire）
 *   ↓                                       取 [读者自己的位置, writeIndex) 这段
 *   写 writeIndex = w + 1（volatile，release）上传顶点
 * </pre>
 *
 * <p><strong>不用锁</strong>：采集线程是实时的，它一次都不该被 GL 线程阻塞。
 * 在采集卡场景下"阻塞"往往意味着硬件 FIFO 溢出，丢得更多。
 *
 * <h2>三处正确性要点（每一条错了都不报错）</h2>
 * <ol>
 *   <li><strong>先写数据、再递增索引。</strong>volatile 写在 Java 里有 release 语义、
 *       读有 acquire 语义，这个顺序保证读者看到索引时数据一定已可见。
 *       <strong>反过来写就是经典的半初始化读取</strong>：读者拿到新索引，
 *       读到的却是还没写完的旧数据——它不是 NaN，也不是 0，而是一个看起来完全正常的错值。</li>
 *   <li><strong>索引用 {@code long} 不用 {@code int}。</strong>每秒几百万点，
 *       {@code int} 几十分钟就溢出，<strong>溢出后静默回绕、全盘错乱</strong>，
 *       而且是在运行了很久之后才发生，最难查。{@code long} 在 2⁶³ 处才溢出，实际不可达。</li>
 *   <li><strong>容量取 2 的幂，用 {@code & (cap - 1)} 取模。</strong>
 *       规格 §5.5 第 3 条给的理由（"Java 的 {@code %} 对负数返回负值"）在本实现里
 *       <strong>不成立</strong>：{@code writeIndex} 从 0 起只增不减，永远是正的，
 *       把掩码换成 {@code %} 数值完全等价。真正需要 2 的幂的是<strong>构造期校验</strong>：
 *       {@code cap = 6} 时 {@code mask = 5}，索引 6 会被映射到槽位 4，静默错位。
 *       所以这里在构造时拒绝非 2 的幂。</li>
 * </ol>
 *
 * <h2>溢出：丢弃最旧 + 缺口 + 计数</h2>
 * <p>采集线程比 GL 线程快是常态（掉帧、窗口被遮挡），<strong>写者绕过读者一定会发生</strong>。
 * 策略是<strong>丢弃最旧的数据，并且让它可见</strong>：
 * <ul>
 *   <li>窗口 = 最近 {@link #capacity()} 个样本，<strong>窗口里全是有效数据</strong>，
 *       被丢弃的样本的位置必然<strong>在窗口之外</strong>。</li>
 *   <li>因此缺口的表示就是：<strong>窗口之外的 {@link #value} 返回 NaN</strong>。
 *       读者手里记着绝对号，换算到窗口下标得到负数，于是拿到 NaN 并断开折线。
 *       <strong>不额外加 {@code isGap(index)}</strong>：多一条判断路径迟早会与
 *       "遇到 NaN 就断开"这条不一致，而它们不一致的那一天，画面会连出一条假线。</li>
 *   <li>{@link #lostSamples()} 计数由<strong>读者</strong>在 {@link #markConsumed(long)}
 *       时更新——只有读者知道自己落了多远。计数暴露给 UI："这一屏数据完整吗"
 *       本来就是示波器用户要问的问题。</li>
 *   <li><strong>丢失是在读者<em>下一次</em>公布进度时才被发现的。</strong>
 *       写者完全不参与（它一次都不读读者的进度），这是 SPSC 纯粹性的代价，
 *       也换来了写者绝不会因为读者慢而多做一个 volatile 读。
 *       因此读者<strong>必须每帧调用一次</strong> {@link #markConsumed(long)}——
 *       不调用的话窗口照样滑动、缺口照样出现，只是计数停在 0，
 *       而"计数停在 0"与"没有丢数据"在 UI 上完全一样。</li>
 *   <li><strong>为什么宁丢点不阻塞</strong>：采集卡场景下阻塞往往意味着硬件 FIFO 溢出，丢得更多。
 *       <strong>为什么不让它无限增长</strong>：采集是持续的，内存最终会爆，
 *       而"爆"的时候离出问题的地方很远——把立即的、可观测的问题换成了延迟的、不可观测的。</li>
 * </ul>
 *
 * <h2>{@link #dirtyRange(long)} 的语义（窗口会滑动，这是主要风险）</h2>
 * <table border="1">
 *   <caption>哪些情况算脏</caption>
 *   <tr><td>没有追加</td><td>{@code EMPTY}</td></tr>
 *   <tr><td>追加了 N 个，读者仍在窗口内</td>
 *       <td>{@code (sinceRevision - windowStart, itemCount)}，只覆盖新增的那一段</td></tr>
 *   <tr><td><strong>窗口滑动过</strong>（读者停在窗口之外）</td>
 *       <td>{@code (0, itemCount)}：下标与样本的对应关系整体错位，
 *           部分重传会把新旧两段拼成一条假线，只有整体重传是安全的</td></tr>
 * </table>
 * <p>{@link #revision()} 直接返回 {@code writeIndex}：这样"自某修订号以来"天然就是
 * "自某个绝对样本号以来"，不必再维护一张"修订号 → 位置"的历史表。
 *
 * <h2>线程</h2>
 * <p>{@link #append(double...)} 只在采集线程调用（唯一写者）。一维与二维数据还提供
 * 固定参数重载，避免实时采样循环因 Java varargs 产生临时数组。
 * {@link #value}、{@link #itemCount}、{@link #dirtyRange}、{@link #markConsumed}
 * 只在 GL 线程调用（唯一读者）。{@link #writeIndex()}、{@link #lostSamples()} 等
 * 只读查询可以被别的线程读（它们读的是 volatile 字段）。
 */
public final class RingChartData implements ChartData {

    /** 缺口的值：{@link #value} 在窗口之外返回它。 */
    public static final double GAP = Double.NaN;

    private final AxisRange[] ranges;

    /** 物理容量，2 的幂。 */
    private final int capacity;

    /** {@code capacity - 1}，用于 {@code & } 取模。 */
    private final int mask;

    /** 数据：{@code data[dim * capacity + slot]}。 */
    private final double[] data;

    /**
     * 已写入的样本总数（从 0 起，只增不减）。
     *
     * <p><strong>必须是 {@code volatile long}</strong>：{@code int} 会在几十分钟后静默回绕，
     * 非 volatile 则读者可能永远看不到新数据。这条由 {@code RingChartDataTest} 的结构断言钉住。
     */
    private volatile long writeIndex;

    /** 读者公布的消费进度（绝对号）。读者写、别人读，因此也是 volatile。 */
    private volatile long consumedIndex;

    /** 被丢弃的样本数。读者在 {@link #markConsumed(long)} 时更新。 */
    private volatile long lostSamples;

    /**
     * 构造。
     *
     * @param ranges   各维度的范围，长度即维度数，至少 1 个
     * @param capacity 环形缓冲的容量，<strong>必须是 2 的幂且 ≥ 2</strong>
     * @throws IllegalArgumentException 维度数为 0、参数为 null、或容量不是 2 的幂时
     */
    public RingChartData(AxisRange[] ranges, int capacity) {
        if (ranges == null || ranges.length == 0) {
            throw new IllegalArgumentException("至少要声明一个维度");
        }
        for (int d = 0; d < ranges.length; d++) {
            if (ranges[d] == null) {
                throw new IllegalArgumentException("第 " + d + " 个维度的范围是 null");
            }
        }
        if (capacity < 2) {
            throw new IllegalArgumentException("容量至少为 2（2 是最小的 2 的幂），实际为 " + capacity);
        }
        if ((capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "容量必须是 2 的幂，实际为 " + capacity + "：取模用 & (capacity-1) 实现，"
                            + "容量不是 2 的幂时这个掩码会把索引映射到错的位置（静默错位，"
                            + "表现为读到别的样本的值）");
        }
        this.ranges = ranges.clone();
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.data = new double[ranges.length * capacity];
    }

    /** 环形缓冲的容量（可见样本数的上限）。 */
    public int capacity() {
        return capacity;
    }

    /** 已写入的样本总数。写入它的是采集线程，读它的是任何人。 */
    public long writeIndex() {
        return writeIndex;
    }

    /** 可见窗口的起点在<strong>绝对号</strong>空间里的位置。 */
    public long windowStart() {
        long w = writeIndex;
        return w > capacity ? w - capacity : 0L;
    }

    /** 读者公布的消费进度（绝对号）。 */
    public long consumedIndex() {
        return consumedIndex;
    }

    /** 因为写者绕过读者而被丢弃的样本数。 */
    public long lostSamples() {
        return lostSamples;
    }

    /**
     * 追加一维样本的无分配快捷路径。
     *
     * @param value 样本值
     * @throws IllegalArgumentException 当前数据不是一维时
     */
    public void append(double value) {
        requireDimensions(1);
        appendValue(value);
    }

    /**
     * 追加二维样本的无分配快捷路径，适合 {@code (x, y)} 流式数据。
     *
     * @param first  第一个维度的值
     * @param second 第二个维度的值
     * @throws IllegalArgumentException 当前数据不是二维时
     */
    public void append(double first, double second) {
        requireDimensions(2);
        long w = writeIndex;
        int slot = (int) (w & mask);
        data[slot] = first;
        data[capacity + slot] = second;
        writeIndex = w + 1;
    }

    /**
     * 追加一个样本。
     *
     * <p><strong>只能在采集线程调用</strong>（唯一写者）。这里的顺序不能动：
     * 先写数据、再发布索引。
     *
     * @param values 各维度的值，个数必须与声明的维度数一致；允许 NaN（传感器故障）
     * @throws IllegalArgumentException 维度数不符或为 null 时
     */
    public void append(double... values) {
        if (values == null || values.length != ranges.length) {
            throw new IllegalArgumentException(
                    "样本维度数与声明不符：声明 " + ranges.length + " 维，实际 "
                            + (values == null ? 0 : values.length) + " 维");
        }
        long w = writeIndex;
        int slot = (int) (w & mask);
        for (int d = 0; d < ranges.length; d++) {
            // ① 先把数据写进去
            data[d * capacity + slot] = values[d];
        }
        // ② 再发布索引。volatile 写有 release 语义：这之前的写对读者可见。
        //    顺序反过来就是半初始化读取——读者拿到新索引，读到的却是还没写完的旧值。
        writeIndex = w + 1;
    }

    private void appendValue(double value) {
        long w = writeIndex;
        data[(int) (w & mask)] = value;
        writeIndex = w + 1;
    }

    private void requireDimensions(int expected) {
        if (ranges.length != expected) {
            throw new IllegalArgumentException(
                    "快捷追加路径要求 " + expected + " 维，实际声明 " + ranges.length + " 维；"
                            + "多维数据请使用 append(double...)");
        }
    }

    /**
     * 读者宣告"我（GL 线程）已经消费到绝对号 {@code upto}"。
     *
     * <p><strong>每帧调用一次</strong>：丢失只会在调用它的那一刻被发现
     * （写者不读读者的进度），不调用的话 {@link #lostSamples()} 会一直停在 0，
     * 而"计数停在 0"与"没有丢数据"在 UI 上完全一样。
     *
     * <p>被写者绕过的那一段记进 {@link #lostSamples()}，进度被钳到窗口起点
     * （窗口之外的数据再也拿不到了）。重复上报旧进度<strong>不会</strong>重复计数。
     *
     * @param upto 已经消费到的绝对号（不含）
     */
    public void markConsumed(long upto) {
        long w = writeIndex;
        long start = w > capacity ? w - capacity : 0L;
        long clamped = Math.min(Math.max(upto, 0L), w);
        if (clamped < consumedIndex) {
            return;                         // 报了个更旧的进度：不重复计数
        }
        if (clamped < start) {
            lostSamples += start - clamped;
            clamped = start;
        }
        consumedIndex = clamped;
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
        long w = writeIndex;
        return (int) Math.min(w, capacity);
    }

    @Override
    public double value(int dim, int index) {
        axisRange(dim);
        int count = itemCount();
        if (index < 0 || index >= count) {
            // 窗口之外：那一段要么还没写（上界），要么已经被覆盖（下界 = 缺口）。
            // 返回 NaN 而不是抛异常、也不是去读一个错位的槽位：读者只需要一条规则
            // "遇到 NaN 就断开折线"，缺口与传感器自己的 NaN 因此天然合一。
            return GAP;
        }
        long absolute = windowStart() + index;
        return data[dim * capacity + (int) (absolute & mask)];
    }

    @Override
    public long revision() {
        return writeIndex;
    }

    @Override
    public DirtyRange dirtyRange(long sinceRevision) {
        long w = writeIndex;
        if (sinceRevision >= w) {
            return DirtyRange.EMPTY;
        }
        int count = itemCount();
        long from = sinceRevision - (w - count);
        if (from < 0) {
            // 读者停在被覆盖的区域里：窗口里每一个下标对应的样本都换过了。
            // 部分重传会把新旧两段拼成一条假线——那条线显示了一个不存在的信号。
            return new DirtyRange(0, count);
        }
        return new DirtyRange((int) from, count);
    }
}
