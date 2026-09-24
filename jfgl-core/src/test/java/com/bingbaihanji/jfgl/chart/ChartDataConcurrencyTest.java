package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RingChartData} 的并发冒烟测试：一个写者、一个读者，跑一段时间。
 *
 * <h2>⚠️ 这个测试有多强？如实说</h2>
 * <p><strong>它是冒烟探测器，不是保证。</strong>正确实现永远通过；
 * <strong>但错误实现也可能通过</strong>——线程调度、JIT 与 CPU 缓存都可能让
 * 一个漏掉 volatile 的实现在几百毫秒里侥幸不出错。真正的正确性来自
 * {@code RingChartData} 类文档里的那三条不变式 + 代码评审。
 *
 * <p>它抓得住的那一类是"读者看到新索引、数据却还没写完"（半初始化读取）：
 * 两个维度写同一个绝对号的相反数，读到的一对值必须满足 {@code a == -b}。
 * 判据与"读到的是第几号样本"无关，因此写者飞快绕过好几圈也不会误报。
 *
 * <h2>⚠️ 扫描范围必须是「头部 + 尾部」两段，只扫头部会漏掉整整一类变异</h2>
 * <p>"先发布索引、后写数据"这个变异（把 {@code writeIndex = w + 1} 挪到写数据之前）
 * 让<strong>正在被写的槽位</strong>落在窗口的<strong>最后一个下标</strong>上，
 * 而正确实现里它是<strong>第 0 个下标</strong>——推导见 {@link #HEAD_MARGIN}
 * 与 {@link #SCAN_SPAN}。
 *
 * <p>所以只扫窗口前 512 个下标的驱动，对这个变异<strong>永远不可见，跑 50 遍也是空转</strong>。
 * 实测证据（仓库外跑的对照实验）：只扫头部的驱动在变异态 5 次全部 {@code torn=0}、
 * 全部退出码 0；<strong>扫尾部的驱动在变异态 {@code torn=2294 / 1774 / 2904}、3 次全失败，
 * 而正确态 {@code torn=0}</strong>。仓库内也复现了同一件事：把本测试临时改成只扫头部，
 * 变异态 {@code torn=0}、2 次全部通过；改成只扫尾部则 {@code torn=2465}、立刻失败。
 * 本测试因此两端都扫，<strong>不要把它"化简"回只扫头部</strong>。
 *
 * <p>读一对值的两读之间要夹一道"写索引没动过"的检查：动过就说明这两个值不属于
 * 同一个绝对号，跳过即可。<strong>少了这道闸，正确的实现会被误判成撕裂</strong>
 * ——实测它一次能报出上百万次假撕裂，比没有测试更浪费时间。
 *
 * <h2>实测命中率（本机，200 毫秒一轮，正确态 10 轮全部 {@code torn=0}）</h2>
 * <ul>
 *   <li>把索引的发布挪到写数据之前：<strong>5/5 抓住</strong>（撕裂 807~2774 对）。</li>
 *   <li>去掉 {@code volatile}：<strong>2/2 抓住</strong>（撕裂约 1.1 万对）——
 *       注意同一次运行里"写索引单调不回退"那条<strong>存活</strong>：它只是看到索引冻在旧值上，
 *       既不回退也不报错。抓得住它的是本测试里"索引与数据必须自洽"这条判据。</li>
 *   <li>{@code long} 改成 {@code int}：<strong>未能捕获</strong>。它要跑到 2³¹ 次追加
 *       （几十分钟）才暴露，200 毫秒的冒烟测试在原理上就够不着；
 *       兜底的是 {@code RingChartDataTest.写索引必须是volatile的long} 那条结构断言
 *       （实测把它改成 {@code int} 后那条断言立刻失败）。</li>
 * </ul>
 * <p><strong>以上是实测命中率，不是保证</strong>：换一台机器、换一个 JIT 版本都可能不同。
 */
class ChartDataConcurrencyTest {

    private static final int CAPACITY = 1 << 16;

    /** 跑多久。取 200 毫秒：够跑几百万次写入，又不至于让全量测试变慢。 */
    private static final long RUN_NANOS = 200_000_000L;

    /**
     * 读者不碰窗口头部的这几格。
     *
     * <p>正确实现里，写者此刻正在覆写的槽位就是<strong>窗口下标 0</strong>：
     * 写者已经把第 {@code w} 号样本写了一半（两个维度之间还没写全），而它占的槽位
     * {@code w & (capacity-1)} 与下标 0 对应的绝对号 {@code w - capacity} 同槽。
     * 读者若去读这一格，dim0 是新样本、dim1 还是旧样本，
     * <strong>{@code a == -b} 必然不成立——那是真实现，不是撕裂</strong>。
     *
     * <p>留一点余量而不是只跳 1 格，是为了让"头部不安全"这件事在代码里显眼。
     * 其余位置安全：绝对号小于已发布的 {@code w} 的样本，两个维度都已写完（volatile
     * 写在最后，release 语义保证它们都可见）。
     *
     * <p><strong>注意"写索引没动过"那道闸救不了这里</strong>：写者在 {@code writeIndex}
     * 不变的情况下照样在往这个槽位里写数据，闸门过了、撕裂依然是真的。
     */
    private static final int HEAD_MARGIN = 4;

    /**
     * 每一端各扫多少格：头部 {@code [HEAD_MARGIN, HEAD_MARGIN + SCAN_SPAN)}、
     * 尾部 {@code [count - SCAN_SPAN, count)}。
     *
     * <p><strong>尾部这一段是必需的那一半，不是对称美学。</strong>
     * "先发布索引、后写数据"的变异被注入之后，读者看到的是 {@code W = w + 1}，
     * 于是窗口整体后移一格，写者正在写的槽位 {@code w} 对应的窗口下标变成
     * {@code capacity - 1}——<strong>窗口的最后一个下标</strong>。只扫头部的话这一格
     * 扫不到，撕裂一个都报不出来（这正是该变异曾经"跑 5 遍全部存活"的原因）。
     *
     * <p>正确实现里尾部是安全的：最后一个下标对应绝对号 {@code w - 1}，早已写完并发布。
     */
    private static final int SCAN_SPAN = 512;

    private static RingChartData freshRing() {
        return new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(-1, 0)}, CAPACITY);
    }

    /** 读者一侧的统计。两个测试各用一个，读者是单线程，不需要同步。 */
    private static final class Tally {
        /** 读到的撕裂对数：{@code dim0 != -dim1}。 */
        long torn;
        /** 真正比对过的对数（NaN 与被闸门拦下的不计）。 */
        long checked;
    }

    /**
     * 扫窗口的一段下标，逐对校验 {@code value(0,i) == -value(1,i)}。
     *
     * @param fromIndex 起始下标（含）
     * @param toIndex   结束下标（不含）
     */
    private static void 扫描一段(RingChartData data, long fromIndex, long toIndex, Tally tally) {
        for (long index = fromIndex; index < toIndex; index++) {
            long before = data.writeIndex();
            double a = data.value(0, (int) index);
            double b = data.value(1, (int) index);
            // 闸门：两读之间写索引动过的话，这两个值就不属于同一个绝对号了——
            // 必须在这里，否则写者飞快时几乎每一对都会被误判成撕裂。
            // 用 writeIndex 而不是 windowStart：未写满时 windowStart 恒为 0，那道闸会失效。
            if (data.writeIndex() != before) {
                continue;
            }
            if (Double.isNaN(a) || Double.isNaN(b)) {
                continue;   // 缺口（窗口之外）：没有值可比，谈不上撕裂
            }
            tally.checked++;
            if (a != -b) {
                tally.torn++;
            }
        }
    }

    @Test
    void 单写单读下读到的样本不会是半初始化的() throws Exception {
        RingChartData data = freshRing();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            long n = 0;
            try {
                while (!stop.get()) {
                    // 两个维度写同一个绝对号的相反数：读到的 a 与 b 必须互为相反数。
                    // 先递增索引后写数据的实现会让读者拿到 a == -b 不成立的一对值。
                    data.append(n, -n);
                    n++;
                }
            } catch (Throwable t) {
                writerFailure.compareAndSet(null, t);
            }
        }, "采集线程");
        writer.setDaemon(true);
        writer.start();

        Tally tally = new Tally();
        long lastSeenWriteIndex = 0;
        long rounds = 0;
        long deadline = System.nanoTime() + RUN_NANOS;
        try {
            while (System.nanoTime() < deadline) {
                long w = data.writeIndex();
                if (w > lastSeenWriteIndex) {
                    lastSeenWriteIndex = w;
                }
                long start = data.windowStart();
                long count = w - start;             // 窗口内的样本数
                if (count > HEAD_MARGIN) {
                    long headEnd = Math.min(count, HEAD_MARGIN + SCAN_SPAN);
                    if (headEnd > HEAD_MARGIN) {
                        扫描一段(data, HEAD_MARGIN, headEnd, tally);
                    }
                    // 尾部：写者正在写的槽位在变异态下就落在这里，不能省
                    long tailStart = Math.max(headEnd, count - SCAN_SPAN);
                    if (count > tailStart) {
                        扫描一段(data, tailStart, count, tally);
                    }
                }
                data.markConsumed(w);
                rounds++;
                Thread.onSpinWait();
            }
        } finally {
            stop.set(true);
            writer.join(2000);
        }

        System.out.println("[并发冒烟] 轮次=" + rounds + " 比对=" + tally.checked
                + " 撕裂=" + tally.torn + " 写索引=" + lastSeenWriteIndex);

        assertNull(writerFailure.get(), "采集线程抛异常了：" + writerFailure.get());
        assertEquals(0, tally.torn,
                "读到 " + tally.torn + " 对撕裂的值（dim0 与 dim1 不互为相反数）：读者拿到了一对"
                        + "不属于同一次写入的值——要么写者把索引的发布放在了数据写入之前，"
                        + "要么 writeIndex 丢了 volatile（读者对索引的两次读会一起停在旧值上，"
                        + "那道闸门于是形同虚设）");
        // 护栏：写者没跑起来的话上面的相等断言是恒真的（橡皮图章）
        assertTrue(lastSeenWriteIndex > 4L * CAPACITY,
                "写者只写了 " + lastSeenWriteIndex + " 个样本，这个测试没有真的在并发——"
                        + "恒真的断言比没有断言更危险");
        assertTrue(tally.checked > 1000, "只验了 " + tally.checked + " 对值，覆盖太薄");
    }

    @Test
    void 写索引单调不回退() throws Exception {
        RingChartData data = freshRing();
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread writer = new Thread(() -> {
            long n = 0;
            while (!stop.get()) {
                data.append(n, -n);
                n++;
            }
        }, "采集线程");
        writer.setDaemon(true);
        writer.start();

        long regressions = 0;
        long previous = -1;
        long observations = 0;
        long deadline = System.nanoTime() + RUN_NANOS;
        try {
            while (System.nanoTime() < deadline) {
                long w = data.writeIndex();
                if (w < previous) {
                    regressions++;
                }
                previous = Math.max(previous, w);
                observations++;
                Thread.onSpinWait();
            }
        } finally {
            stop.set(true);
            writer.join(2000);
        }

        System.out.println("[并发冒烟] 观察=" + observations + " 回退=" + regressions
                + " 写索引=" + previous);

        assertEquals(0, regressions, "写索引回退了 " + regressions + " 次："
                + "单调递增是 SPSC 的全部前提，回退意味着读者会读到已经作废的下标");
        assertTrue(observations > 1000, "只观察了 " + observations + " 次，覆盖太薄");
        assertTrue(previous > 4L * CAPACITY, "写者没跑起来，上面的断言是恒真的");
    }
}
