package com.bingbaihanji.xuan.chartrender;

/**
 * 一个系列的 GPU 缓冲<strong>物理布局</strong>：前面留几个 float 的余量、要不要维护邻居镜像。
 *
 * <h2>只有两个实例：普通与平滑</h2>
 * <p><b>{@link #PLAIN}</b> = 改动前的布局（容量 + 1 个 float，槽位 {@code s} 在字节 {@code 4s}，
 * 镜像只有末尾那一个）。<b>{@link #SMOOTH}</b> = 平滑曲线需要的布局（容量 + 3 个 float，
 * 前面多留一个）。
 *
 * <h2>为什么平滑要在<b>前面</b>留余量</h2>
 * <p>折线的实例属性是"同一个 VBO 上的两个字节偏移"，而 Catmull-Rom 的每一段需要
 * 四个控制点 {@code y[k-1], y[k], y[k+1], y[k+2]}——也就是属性的偏移要写成
 * {@code -4, 0, +4, +8}。<b>负偏移在 OpenGL 里不存在</b>（{@code glVertexAttribPointer}
 * 的偏移是个指针，必须 ≥ 0），所以"槽位 0 的前一个样本"必须在物理上真的排在它前面：
 * 只能靠把整块数据向后挪一个 float。
 *
 * <pre>
 * 普通：  [槽位 0 … 槽位 capacity-1][镜像 cap ← 槽位 0]                 共 capacity+1
 * 平滑：  [余量 -1][槽位 0 … 槽位 capacity-1][镜像 cap][镜像 cap+1]     共 capacity+3
 * </pre>
 *
 * <p>于是"扩展槽位号 {@code A}"与字节偏移的关系只有一条：
 * {@link #byteOffsetOfSlot(int) byteOffsetOfSlot(A) = (A + leadingFloats) × 4}。
 * 普通布局下 {@code A = capacity}（那唯一的镜像）落在 {@code 4 × capacity} ✓；
 * 平滑布局下 {@code A = -1}（前置余量）落在 0、{@code A = capacity} 落在
 * {@code 4(capacity+1)}、{@code A = capacity + 1} 落在 {@code 4(capacity+2)} ✓。
 *
 * <h2>★ 三个余量各自的语义（它们是"最新一个落进该槽位的样本"，不是垃圾）</h2>
 * <ul>
 *   <li>{@code A = -1} → 槽位 {@code capacity-1} 的镜像：给<b>槽位 0</b> 上的实例当
 *       {@code aYm1}（它的前一个样本，绕环之后正落在最后一个槽位上）；</li>
 *   <li>{@code A = capacity} → 槽位 0 的镜像：给<b>最后一个槽位</b>上的实例当
 *       {@code aY1}，也给<b>倒数第二个</b>槽位上的实例当 {@code aY2}；</li>
 *   <li>{@code A = capacity + 1} → 槽位 1 的镜像：给<b>最后一个槽位</b>上的实例当
 *       {@code aY2}。</li>
 * </ul>
 * <p>谁维护它们见 {@link SeriesUploadPlan#mirrors()}；为什么需要见
 * {@link SeriesBuffer} 的类文档。
 *
 * <h2>★ 非平滑系列必须保持原布局</h2>
 * <p>"默认路径逐字节不变"是硬要求：{@code ChartVerifier} 有一条"流式系列每帧
 * 恰好上传 K×4 字节"的断言（整个 ② 的性能主张的度量点），而平滑布局比普通布局多写
 * 两个镜像（+8 字节）。所以布局<b>由系列自己声明</b>，两种布局并存、
 * 谁也影响不到谁——不是"全都升级成平滑布局，反正看不出来"。
 *
 * @param leadingFloats 槽位 0 前面留几个 float（普通 = 0，平滑 = 1）
 * @param smooth        是否维护三个邻居镜像（普通 = false 只维护末尾那一个）
 */
public record SeriesLayout(int leadingFloats, boolean smooth) {

    /** 改动前的布局：容量 + 1 个 float，槽位 {@code s} 在字节 {@code 4s}。 */
    public static final SeriesLayout PLAIN = new SeriesLayout(0, false);

    /**
     * 平滑曲线的布局：容量 + 3 个 float（前置 1 + 后置 2），槽位 {@code s} 在字节 {@code 4(s+1)}。
     *
     * <p>前置的 1 个是"槽位 0 的前一个样本"，后置的 2 个分别是"槽位 0 / 槽位 1 的镜像"。
     */
    public static final SeriesLayout SMOOTH = new SeriesLayout(1, true);

    /**
     * 这种布局下缓冲一共要分配几个 float。
     *
     * <p>普通 = {@code 容量 + 1}（那是既有行为，逐字节不变）；平滑 = {@code 容量 + 3}。
     */
    public int floatCount(int capacity) {
        return capacity + leadingFloats + (smooth ? 2 : 1);
    }

    /** 这种布局下缓冲一共要分配几个字节（见 {@link #floatCount(int)}）。 */
    public int byteCount(int capacity) {
        return floatCount(capacity) * Float.BYTES;
    }

    /**
     * <b>扩展槽位号</b> → 字节偏移。
     *
     * <p>扩展槽位号除了 {@code 0 .. capacity-1} 之外，还包括
     * {@code -1}（前置余量）与 {@code capacity}、{@code capacity + 1}（后置的两个镜像）。
     * 就这一条公式，两种布局都是它——差别只在 {@code leadingFloats} 是否为 0。
     *
     * <p><b>不校验范围</b>：它是个纯算术换算，调用方（{@link SeriesUploadPlan}）已经算清了
     * 哪些扩展槽位合法。加一道"越界就抛"的守卫只会让"布局与容量不匹配"这类错误
     * 从"算术"问题变成"异常"问题，而那个异常在 GL 线程上是**静默吞掉**的。
     */
    public int byteOffsetOfSlot(int extendedSlot) {
        return (extendedSlot + leadingFloats) * Float.BYTES;
    }
}
