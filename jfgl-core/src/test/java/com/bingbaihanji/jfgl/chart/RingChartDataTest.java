package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RingChartData} 的单测。
 *
 * <p><strong>它是单线程的。</strong>并发行为由 {@code ChartDataConcurrencyTest} 冒烟覆盖，
 * 但那个测试是探测器而不是保证（见它的类文档）。这里的每一条都是确定性的行为断言。
 *
 * <p>命名约定：{@code 窗口下标 i} 指 {@code [0, itemCount())}；{@code 绝对号 n}
 * 指"第 n 个写入的样本"（从 0 起）。窗口滑动之后，同一个窗口下标对应的绝对号会变——
 * 这正是 {@link #缺口落在窗口之外的位置并返回NaN} 要钉住的事。
 */
class RingChartDataTest {

    private static final AxisRange[] ONE_DIM = {AxisRange.of(0, 100)};

    private static RingChartData ring(int capacity) {
        return new RingChartData(ONE_DIM, capacity);
    }

    private static void append(RingChartData data, int count) {
        for (int i = 0; i < count; i++) {
            data.append(i);
        }
    }

    @Test
    void 追加后按窗口下标读回样本() {
        RingChartData data = ring(8);
        data.append(11);
        data.append(22);
        data.append(33);

        assertEquals(3, data.itemCount());
        assertEquals(11.0, data.value(0, 0), 0);
        assertEquals(33.0, data.value(0, 2), 0);
        assertEquals(3L, data.writeIndex(), "写索引等于已写入的样本总数");
    }

    @Test
    void 一维与二维快捷追加路径写入正确维度() {
        RingChartData one = ring(8);
        one.append(7.5);
        assertEquals(7.5, one.value(0, 0), 0);

        RingChartData two = new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(-1, 1)}, 8);
        two.append(3.0, -4.0);
        assertEquals(3.0, two.value(0, 0), 0);
        assertEquals(-4.0, two.value(1, 0), 0);
    }

    @Test
    void 快捷追加路径的维度不符时明确拒绝() {
        assertThrows(IllegalArgumentException.class, () -> ring(8).append(1.0, 2.0));
        RingChartData two = new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(-1, 1)}, 8);
        assertThrows(IllegalArgumentException.class, () -> two.append(1.0));
    }

    @Test
    void 修订号与写索引同步推进() {
        RingChartData data = ring(8);
        assertEquals(0L, data.revision());
        assertEquals(0, data.itemCount());
        append(data, 3);
        assertEquals(3L, data.revision(), "revision 就是 writeIndex：这样\"自某修订号以来\""
                + "天然就是\"自某个绝对样本号以来\"，不用再维护一张历史表");
        assertEquals(data.writeIndex(), data.revision());
    }

    @Test
    void 未写满时窗口从零开始() {
        RingChartData data = ring(8);
        append(data, 5);
        assertEquals(0L, data.windowStart());
        assertEquals(5, data.itemCount());
    }

    @Test
    void 环绕后读到的是最新的一圈() {
        RingChartData data = ring(8);
        append(data, 12);

        assertEquals(8, data.itemCount(), "环形缓冲最多只能看见 capacity 个样本");
        assertEquals(12L, data.writeIndex());
        for (int i = 0; i < 8; i++) {
            assertEquals(4 + i, data.value(0, i), 0,
                    "窗口下标 " + i + " 应当是第 " + (4 + i) + " 号样本");
        }
    }

    @Test
    void 环绕后窗口起点同步前移() {
        RingChartData data = ring(8);
        append(data, 12);
        assertEquals(4L, data.windowStart(),
                "窗口起点必须是 writeIndex - itemCount()：它不跟着前移的话，"
                        + "value() 会读到已经被覆盖的槽位（值还是对的，只是属于别的样本）");
    }

    @Test
    void 读者落后整圈时丢弃最旧并计数() {
        RingChartData data = ring(8);
        append(data, 8);
        data.markConsumed(0);          // 读者一个都还没用上
        append(data, 3);               // 0/1/2 号样本在读者用上它们之前就被覆盖了
        data.markConsumed(0);          // 读者下一次公布进度时，才发现自己在窗口之外

        assertEquals(3L, data.lostSamples(), "被丢掉的样本数必须如实计数——"
                + "\"这一屏数据完整吗\"本来就是示波器用户要问的问题");
        assertEquals(3L, data.windowStart());
        assertEquals(3L, data.consumedIndex(), "读者的进度被钳到窗口起点：窗口之外的数据它再也拿不到了");
    }

    @Test
    void 缺口落在窗口之外的位置并返回NaN() {
        RingChartData data = ring(8);
        append(data, 8);
        data.markConsumed(0);
        append(data, 3);

        // 0/1/2 号样本已经被覆盖。读者手里记的是绝对号，换算到窗口下标得到负数——
        // 那就是缺口的位置。它必须给出"没有值"，而不是错位的旧值。
        assertTrue(Double.isNaN(data.value(0, -1)),
                "被丢弃的样本位置必须返回 NaN；读出错位的旧值会把缺口连成一条假线");
        assertTrue(Double.isNaN(data.value(0, -3)));
        assertTrue(Double.isNaN(data.value(0, data.itemCount())),
                "窗口上界之外同样没有值");
        assertTrue(Double.isNaN(data.value(0, 99999)));
    }

    @Test
    void 窗口内的样本一个都不错位() {
        RingChartData data = ring(8);
        append(data, 20);
        data.markConsumed(data.writeIndex() - 5);   // 读者落后 5 个（但还没被丢弃）

        // itemCount() 是**可见窗口**的长度，不会因为读者落后而变小：
        // 窗口是绝对号 [12, 20)，也就是窗口下标 0..7
        long start = data.windowStart();
        assertEquals(8, data.itemCount());
        assertEquals(12L, start);
        for (int i = 0; i < data.itemCount(); i++) {
            assertEquals(start + i, data.value(0, i), 0,
                    "窗口内的每个下标都必须对得上它的绝对号");
            assertFalse(Double.isNaN(data.value(0, i)), "窗口内的下标不该出现缺口");
        }
    }

    @Test
    void 读者回退不重复计数() {
        RingChartData data = ring(8);
        append(data, 8);
        data.markConsumed(0);
        append(data, 4);               // 窗口滑到 [4,12)
        data.markConsumed(0);          // 读者这才发现 0..3 号已经没了
        long lost = data.lostSamples();
        assertTrue(lost > 0, "先要真的丢过数据，否则这条断言是空转");

        data.markConsumed(0);          // 再报一次同样的旧进度
        assertEquals(lost, data.lostSamples(), "重复上报旧进度不能重复计数");
        data.markConsumed(data.writeIndex());
        assertEquals(lost, data.lostSamples(), "追上进度也不该改变已经丢掉的数量");
    }

    @Test
    void 没有追加时脏区间为空() {
        RingChartData data = ring(8);
        append(data, 4);
        assertTrue(data.dirtyRange(data.revision()).isEmpty(), "没有追加却报了脏区");
        assertTrue(data.dirtyRange(data.revision() + 10).isEmpty());
    }

    @Test
    void 新增样本的脏区间只覆盖新增的那一段() {
        RingChartData data = ring(8);
        append(data, 4);
        long revision = data.revision();
        append(data, 3);

        DirtyRange dirty = data.dirtyRange(revision);
        assertEquals(4, dirty.firstDirty(), "脏区间必须从新增的第一个下标开始："
                + "从 0 开始的话每帧都要重传整个窗口，增量上传的好处全部抵消");
        assertEquals(7, dirty.lastDirty());
    }

    @Test
    void 窗口滑动后整个窗口都算脏() {
        RingChartData data = ring(8);
        append(data, 8);
        long revision = data.revision();      // 读者看到的修订号 = 绝对号 8
        data.markConsumed(0);
        append(data, 10);                     // 窗口滑到 [10,18)：绝对号 8 已经被丢掉了

        DirtyRange dirty = data.dirtyRange(revision);
        assertEquals(0, dirty.firstDirty(), "读者手里的位置已经被覆盖时，下标与样本的对应关系"
                + "整体错位——部分重传会把新旧两段拼成一条假线，只有整体重传是安全的");
        assertEquals(data.itemCount(), dirty.lastDirty());

        // 对照：读者只落后一点点（还没掉出窗口）时报的是**部分脏**，不该整窗重传
        RingChartData other = ring(8);
        append(other, 8);
        long otherRevision = other.revision();
        append(other, 4);                     // 窗口滑到 [4,12)，绝对号 8 仍在窗口里
        DirtyRange partial = other.dirtyRange(otherRevision);
        assertEquals(4, partial.firstDirty(),
                "读者还在窗口里的时候报整窗脏，等于每帧白传一整个窗口");
        assertEquals(8, partial.lastDirty());
    }

    @Test
    void 传感器自身的NaN会原样返回() {
        RingChartData data = ring(8);
        data.append(1);
        data.append(Double.NaN);
        data.append(3);

        assertTrue(Double.isNaN(data.value(0, 1)),
                "传感器自己吐的 NaN 必须照原样留着：渲染器的规则只有一条"
                        + "\"遇到 NaN 就断开折线\"，它不必也不该知道这个 NaN 是丢包还是传感器给的");
        assertEquals(3.0, data.value(0, 2), 0);
        assertEquals(0L, data.lostSamples(), "NaN 样本不是丢包，不该计数");
    }

    @Test
    void 维度数与声明不符时抛异常() {
        RingChartData data = new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(0, 1)}, 8);
        assertThrows(IllegalArgumentException.class, () -> data.append(1));
        assertThrows(IllegalArgumentException.class, () -> data.append(1, 2, 3));
        assertDoesNotThrow(() -> data.append(1, 2));
        assertEquals(2.0, data.value(1, 0), 0);
    }

    @Test
    void 容量不是2的幂时抛异常() {
        for (int capacity : new int[]{0, 1, 3, 6, 100, 1000}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new RingChartData(ONE_DIM, capacity),
                    "容量 " + capacity + " 不是 2 的幂，必须构造时就拒绝");
            assertTrue(e.getMessage().contains("2 的幂"),
                    "消息要说清原因：" + e.getMessage());
        }
        assertDoesNotThrow(() -> new RingChartData(ONE_DIM, 2));
        assertDoesNotThrow(() -> new RingChartData(ONE_DIM, 1 << 20));
    }

    @Test
    void 写索引必须是volatile的long() throws IOException {
        // 这是一条**结构断言**，不是行为断言，理由：两条最关键的不变式在单测口径下
        // 根本不可观测——int 要在 2³¹ 次追加之后才回绕（几十分钟），
        // 非 volatile 则取决于 JIT 与缓存（可能永远看不到，也可能明天才看不到）。
        // 唯一能在毫秒级钉住它们的东西是看一眼源码。不优雅，但比"没有防守"强：
        // 把 long 改成 int、或把 volatile 去掉，这条会立刻失败。
        Path source = Path.of("src", "main", "java", "com", "bingbaihanji", "jfgl", "chart",
                "RingChartData.java");
        assertTrue(Files.isRegularFile(source), "找不到源文件：" + source.toAbsolutePath()
                + "（工作目录应为项目根，找错了目录会让本测试形同虚设）");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        Matcher matcher = Pattern.compile("volatile\\s+long\\s+writeIndex").matcher(text);
        assertTrue(matcher.find(),
                "writeIndex 必须声明成 volatile long：int 会在几十分钟后静默回绕、全盘错乱；"
                        + "非 volatile 则读者可能永远看不到新数据（循环里读的是寄存器里的旧值）");
    }
}
