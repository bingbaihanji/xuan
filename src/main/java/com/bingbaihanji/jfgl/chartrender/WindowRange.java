package com.bingbaihanji.jfgl.chartrender;

import java.util.List;

/**
 * 可见窗口 → 要绘制的实例区间。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>为什么单独成类</h2>
 * <p>这一段算术决定了"这一帧从哪个物理槽位开始画、画几个、要不要切成两段"。
 * 算错的表现是<b>画面缺一段、或者多出一条横贯屏幕的乱线</b>——两者都不报错。
 * 抽成纯函数之后它可以被穷举测试，而不必靠肉眼看窗口。
 *
 * <h2>三个概念必须分清</h2>
 * <ul>
 *   <li><b>数据下标</b>：从采集开始累加的绝对序号，用 {@code long}。
 *       每秒几百万点，{@code int} 几十分钟就溢出——而溢出后静默回绕，全盘错乱。</li>
 *   <li><b>物理槽位</b>：{@code 数据下标 & (容量 - 1)}，即它在环形缓冲里的位置。</li>
 *   <li><b>实例</b>：一个实例就是一个线段，连接数据下标 {@code i} 与 {@code i + 1}。
 *       因此实例 {@code i} 的物理槽位也是 {@code i & (容量 - 1)}——
 *       顶点着色器靠"同一个缓冲、偏移差 4 字节"的两个属性拿到 {@code (y[i], y[i+1])}。</li>
 * </ul>
 *
 * <h2>为什么要返回多段</h2>
 * <p>可见窗口跨过环的环绕点时，物理槽位不是连续的，必须切成两段分别绘制。
 * 每段带自己的 {@code firstInstance}（物理槽位）与 {@code firstDataIndex}（绝对下标），
 * 后者供顶点着色器算 x 坐标。
 */
public final class WindowRange {

    /**
     * 一段连续的实例。
     *
     * @param firstInstance  起始物理槽位（供 {@code glDrawArraysInstancedBaseInstance}
     *                       的 {@code baseInstance} 用）
     * @param firstDataIndex 起始数据下标（绝对序号，供顶点着色器算 x）
     * @param instanceCount  实例数
     */
    public record Segment(int firstInstance, long firstDataIndex, int instanceCount) {
    }

    private WindowRange() {
    }

    /**
     * 计算这一帧要绘制哪些实例。
     *
     * @param windowStart 可见窗口左边缘的数据下标，可以是小数（亚像素滚动）
     * @param windowEnd   可见窗口右边缘的数据下标（半开）
     * @param writeIndex  采集线程已写入的样本总数，即有效数据是 {@code [?, writeIndex)}
     * @param capacity    环形缓冲容量，<strong>必须是 2 的幂</strong>
     * @return 0、1 或 2 段；窗口与有效数据没有交集时为空列表
     * @throws IllegalArgumentException 容量不是 2 的幂
     */
    public static List<Segment> compute(double windowStart, double windowEnd,
                                        long writeIndex, int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + capacity);
        }
        if (!(windowEnd > windowStart)) {
            return List.of();
        }

        // 环里最老的那个还没被覆盖的样本。写者绕过读者时，比它更老的已经丢了。
        long validStart = Math.max(0L, writeIndex - capacity);
        // 一个线段要两端都有，所以最后一个可画的线段下标是 writeIndex - 2。
        long lastSegment = writeIndex - 2;

        long lo = Math.max((long) Math.ceil(windowStart), validStart);
        long hi = Math.min((long) Math.ceil(windowEnd), lastSegment + 1);
        if (hi <= lo) {
            return List.of();
        }

        int count = (int) (hi - lo);
        int firstSlot = (int) (lo & (capacity - 1));
        if (firstSlot + count <= capacity) {
            return List.of(new Segment(firstSlot, lo, count));
        }

        int headCount = capacity - firstSlot;
        return List.of(
                new Segment(firstSlot, lo, headCount),
                new Segment(0, lo + headCount, count - headCount));
    }
}
