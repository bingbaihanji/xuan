package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.ArrayChartData;
import com.bingbaihanji.xuan.chart.AxisRange;
import com.bingbaihanji.xuan.chart.RingChartData;
import com.bingbaihanji.xuan.gl.FakeGLAbstraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeriesBufferTest {

    private FakeGLAbstraction gl;
    private RingChartData data;

    /**
     * 造一个容量为 {@code capacity} 的流式数据。
     *
     * <p><strong>必须是二维</strong>：{@link SeriesBuffer} 取的是 <b>1 号维度</b>（y），
     * 只声明一维的数据在取数时会抛 {@code IndexOutOfBoundsException} 而不是"画不出来"。
     */
    private static RingChartData newData(int capacity) {
        return new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(-1, 1)}, capacity);
    }

    /**
     * 追加若干个 y 值（x 维取当时的写入总数）。
     *
     * <p>{@code RingChartData.append} 要求值个数与声明的维度数<strong>严格相等</strong>，
     * 所以"追加 3 个点"不能写成 {@code append(1, 2, 3)}——那是 3 个值给 2 个维度，直接抛。
     */
    private static void appendY(RingChartData d, double... ys) {
        for (double y : ys) {
            d.append(d.writeIndex(), y);
        }
    }

    @BeforeEach
    void setUp() {
        gl = new FakeGLAbstraction();
        data = newData(8);
    }

    @Test
    void 构造时分配固定大小的缓冲并留一个float余量() {
        new SeriesBuffer(gl, data);
        assertEquals(1, gl.createdVbos.size(), "应当建恰好一个 VBO");
        assertEquals((8 + 1) * Float.BYTES, SeriesBuffer.bufferBytesFor(8),
                "缓冲字节数必须是 (容量 + 1) * 4");
    }

    @Test
    void 首次上传带上全部已有数据() {
        appendY(data, 1.0, 2.0, 3.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        assertEquals(12, buf.uploadedBytesThisFrame(), "3 个点 * 4 字节");
    }

    @Test
    void 第二次上传只传新增的点() {
        appendY(data, 1.0, 2.0, 3.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        buf.beginFrame();

        appendY(data, 4.0, 5.0);
        buf.uploadNewSamples();

        assertEquals(8, buf.uploadedBytesThisFrame(),
                "只该传新增的 2 个点 = 8 字节。整窗重传的话这里是 20");
    }

    @Test
    void 没有新数据时一个字节都不传() {
        appendY(data, 1.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        buf.beginFrame();

        buf.uploadNewSamples();

        assertEquals(0, buf.uploadedBytesThisFrame());
        assertEquals(1, gl.vboSubDataCalls.size(), "第二次不该产生任何上传调用");
    }

    @Test
    void 子上传的偏移是环槽位乘4() {
        appendY(data, 1.0, 2.0, 3.0, 4.0, 5.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();

        // 记录格式是 "VBO名@偏移:字节数"（带目标 VBO，因为 Task 11 之后会多 VBO 并存）。
        // 这里只断言偏移与字节数，不硬编码 VBO 名——那个名字取决于假实现的 ID 分配顺序。
        assertEquals(1, gl.vboSubDataCalls.size());
        assertTrue(gl.vboSubDataCalls.get(0).endsWith("@0:20"),
                "偏移应是 0、字节数 20（5 个点 * 4），实际 " + gl.vboSubDataCalls.get(0));
    }

    @Test
    void 跨环绕时产生两次子上传() {
        // 关键是**这一次上传**的新增样本要跨过环绕点，而不是"环曾经绕过"。
        // 先把前 6 个点写进去且**不上传**，再写 4 个使总数到 10——于是这一次上传
        // 覆盖环里还留着的 8 个样本（绝对号 2..9 → 槽位 2..7 与 0,1），真的跨环绕。
        appendY(data, 1, 2, 3, 4, 5, 6);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        appendY(data, 7, 8, 9, 10);
        buf.uploadNewSamples();

        // **三次**而不是两次：两次样本子上传之外，还有一次槽位 0 的镜像
        // （偏移 capacity*4 = 32）。它的理由见 SeriesUploadPlan.mirrorSourceIndex：
        // 环已绕过之后，最后一个槽位上的那个实例（绝对号 7）的第二端读的正是那个位置，
        // 而它必须等于槽位 0 上的样本（绝对号 8）。
        assertEquals(3, gl.vboSubDataCalls.size(), "两次样本子上传 + 一次槽位 0 的镜像");
        assertTrue(gl.vboSubDataCalls.get(0).endsWith("@8:24"), "槽位 2..7 的样本，实际 "
                + gl.vboSubDataCalls.get(0));
        assertTrue(gl.vboSubDataCalls.get(1).endsWith("@0:8"), "槽位 0,1 的样本，实际 "
                + gl.vboSubDataCalls.get(1));
        assertTrue(gl.vboSubDataCalls.get(2).endsWith("@32:4"),
                "镜像写在偏移 capacity*4 上；少了它，跨环绕点那一个实例会画一条掉到 0 的斜线。实际 "
                        + gl.vboSubDataCalls.get(2));
    }

    @Test
    void 环写满之后仍然继续上传() {
        // 这是本任务最重要的一条断言。
        //
        // RingChartData.itemCount() 返回 min(writeIndex, capacity)——环满之后它就
        // 停在 capacity 不动了。若 SeriesBuffer 拿 itemCount() 当"写到哪了"，
        // 它会在环满之后认为"没有新数据"，于是静默地再也不上传，画面定格在第一屏。
        // 那条路径不报任何错，只有这条断言拦得住。
        for (int i = 0; i < 8; i++) {
            appendY(data, i);
        }
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        assertEquals(8, data.itemCount(), "前提：环已满");
        gl.vboSubDataCalls.clear();
        buf.beginFrame();

        appendY(data, 99);
        assertEquals(8, data.itemCount(), "前提：环满之后 itemCount() 不再增长");

        buf.uploadNewSamples();

        // 8 字节 = 新点 4 字节 + 槽位 0 的镜像 4 字节。写第 9 个点（下标 8）时正好
        // 落进槽位 0，而环已经绕过——从这一刻起，最后一个槽位上的实例（下标 7）
        // 的第二端就要读那个镜像（见 SeriesUploadPlan.mirrorSourceIndex）。
        assertEquals(8, buf.uploadedBytesThisFrame(),
                "环满之后新点照样要上传（这里若为 0，说明把 itemCount() 当成了写指针）");
    }

    @Test
    void 写入总数倒退时明确报错() {
        // 数据源的写入总数倒退（换了数据源、或 ChartData 实现被替换）时，
        // GPU 缓冲里留的是**旧数据**。此时若安静地"不上传"，画面会继续显示
        // 过期内容而一声不响——本项目最警惕的形状。
        // 当前数据源不会退（writeIndex 单调、itemCount 恒定），所以这条不可达，
        // 但不可达不等于无害：把它变成响亮的失败，并在消息里指出出路。
        //
        // 注意这里走的是**生产路径本身**（注入一个会倒退的数据源后调 uploadNewSamples），
        // 而不是某个"供测试调用"的旁路入口：旁路入口与真正的守卫是两份可以各自腐烂的代码。
        BackwardSource source = new BackwardSource(8);
        source.written = 5L;
        SeriesBuffer buf = new SeriesBuffer(gl, source, 8);
        buf.uploadNewSamples();
        assertEquals(20, buf.uploadedBytesThisFrame(), "前提：已上传到绝对号 5");
        buf.beginFrame();

        source.written = 2L;                        // 写入总数倒退

        IllegalStateException e = assertThrows(IllegalStateException.class,
                buf::uploadNewSamples,
                "写入总数倒退必须抛异常，不能安静地不上传");
        assertTrue(e.getMessage().contains("重建"),
                "消息里必须指出出路（换数据源时重建 SeriesBuffer），实际：" + e.getMessage());
    }

    @Test
    void 容量与数据不一致时抛异常() {
        RingChartData bigger = newData(16);
        assertThrows(IllegalArgumentException.class,
                () -> new SeriesBuffer(gl, bigger, 8),
                "缓冲容量与数据的环容量不一致会让槽位算术全错，必须构造时就拦住");
    }

    @Test
    void 释放后删除VBO且幂等() {
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.dispose();
        buf.dispose();
        assertEquals(1, gl.deletedVbos.size());
    }

    @Test
    void 非环形数据按追加语义处理() {
        ArrayChartData staticData = ChartDataFixtures.arrayOf(0, 1, 2, 3);
        SeriesBuffer buf = new SeriesBuffer(gl, staticData);
        buf.uploadNewSamples();
        int first = buf.uploadedBytesThisFrame();
        buf.beginFrame();
        buf.uploadNewSamples();

        assertEquals(16, first);
        assertEquals(0, buf.uploadedBytesThisFrame(), "静态数据一次上传后必须永不重传");
    }

    // —— 以下钉住**平滑布局**（SeriesLayout.SMOOTH）的缓冲本身 ——

    @Test
    void 平滑布局的缓冲多三个float() {
        new SeriesBuffer(gl, data, true);
        assertEquals(1, gl.createdVbos.size(), "应当建恰好一个 VBO");
        assertEquals((8 + 3) * Float.BYTES, SeriesBuffer.bufferBytesFor(8, true),
                "平滑布局是 容量 + 3 个 float（前置 1 + 后置 2）");
        assertEquals((8 + 1) * Float.BYTES, SeriesBuffer.bufferBytesFor(8, false),
                "普通布局一字未改");
    }

    @Test
    void 平滑布局的跨环绕上传写三个镜像() {
        // 容量 8、已写 10 个：环里是绝对号 2..9（槽位 2..7 与 0,1），三个镜像的位置是
        // 字节 0（前置，← 槽位 7）、36（← 槽位 0）、40（← 槽位 1）。
        RecordingSource source = new RecordingSource(8);
        source.written = 10L;
        SeriesBuffer buf = new SeriesBuffer(gl, source, SeriesLayout.SMOOTH);
        buf.uploadNewSamples();

        assertEquals(44, buf.uploadedBytesThisFrame(), "8 个样本 * 4 + 三个镜像 * 4");
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 7L, 8L, 9L), source.requested,
                "末尾三个 7/8/9 是三个镜像；少了它们，曲线在环绕点附近会弯向 0");
        assertEquals(5, gl.vboSubDataCalls.size(), "两段样本 + 三个镜像");
    }

    @Test
    void 平滑布局的可平滑区间排除首末两段() {
        // 静态数据 7 个点（容量 8）：四个控制点齐全的实例是 1..4，
        // 也就是首段（0）与末段（5）必须退回直线——它们是像素校验器里
        // "边界不与邻居相连"那两条断言的算术来源。
        ArrayChartData staticData = ChartDataFixtures.arrayOf(0, 1, 2, 3, 4, 5, 6);
        SeriesBuffer buf = new SeriesBuffer(gl, staticData, true);
        buf.uploadNewSamples();

        assertEquals(7L, buf.writeCount());
        assertEquals(1L, buf.smoothableFirst(), "首段的左邻居不存在");
        assertEquals(5L, buf.smoothableEnd(), "半开区间的右端：末段的右邻居还没采到");
    }

    @Test
    void 平滑布局环滑动后的可平滑区间() {
        // 环容量 8、已上传 10 个：环里还留着绝对号 2..9，于是可平滑的实例是 3..7。
        RecordingSource source = new RecordingSource(8);
        source.written = 10L;
        SeriesBuffer buf = new SeriesBuffer(gl, source, SeriesLayout.SMOOTH);
        buf.uploadNewSamples();

        assertEquals(3L, buf.smoothableFirst(), "绝对号 2 是最老的那个：实例 3 才能往前看一格");
        assertEquals(8L, buf.smoothableEnd(), "最后一个可平滑的实例是 7");
    }

    @Test
    void NaN端点被标记为退化() {
        RingChartData d = newData(8);
        appendY(d, 1.0, Double.NaN, 3.0);
        SeriesBuffer buf = new SeriesBuffer(gl, d);
        buf.uploadNewSamples();

        assertTrue(buf.hasGapBetween(0, 2), "样本 0 与 1 里有一个是 NaN");
        assertFalse(buf.hasGapBetween(2, 3), "样本 2 是 3.0，不是 NaN");
    }

    // —— 以下钉住 SeriesSource 的"绝对号"语义 ——
    //
    // 上面那些测试只观察**偏移与字节数**，观察不到"传进去的到底是哪个样本"。
    // 而"偏移对、内容错"恰好是本项目最警惕的形状（画面正常、数据错）。
    // 这一组补的就是那个盲区：假实现只记 "VBO名@偏移:字节数"，回读不到字节内容，
    // 所以改为直接断言 SeriesSource 的取数，以及断言"向它请求了哪几个绝对号"。

    @Test
    void 跨环绕的上传按绝对号连续取数() {
        // 环容量 8、已写 10 个：窗口是绝对号 2..9，其中 0..1 已被覆盖。
        RecordingSource source = new RecordingSource(8);
        source.written = 10L;
        SeriesBuffer buf = new SeriesBuffer(gl, source, 8);
        buf.uploadNewSamples();

        // 36 = 8 个样本 * 4 + 镜像 4：环已绕过，槽位 0 上的样本（绝对号 8）还要再写一份
        // 到偏移 capacity*4 上，供最后一个槽位上的实例读它的第二端。
        assertEquals(36, buf.uploadedBytesThisFrame(), "环里还留着的 8 个点 * 4 + 镜像 4 字节");
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 8L), source.requested,
                "第一段的绝对号必须是 written - 点数。若从 range.byteOffset()/4 反推，"
                        + "跨环绕的第二段会回到绝对号 0、1，于是把两个不同时刻的样本画到一起。"
                        + "**末尾那个 8 是槽位 0 的镜像**：槽位 0 上放的正是样本 8，"
                        + "而环已绕过、它要再写一份到槽位 capacity 上");
        assertEquals(3, gl.vboSubDataCalls.size(), "绝对号 2..9 跨过环绕点切成两段，外加镜像一次");
    }

    @Test
    void 环滑动后仍按绝对号取数() {
        RingChartData d = newData(8);
        for (int i = 0; i < 12; i++) {
            d.append(i, i * 10.0);
        }
        SeriesSource s = SeriesSource.of(d);

        assertEquals(12L, s.writeCount(), "写入总数必须继续增长，不能停在容量上");
        assertEquals(8, s.capacity());
        for (int i = 0; i < 8; i++) {
            assertEquals(d.value(1, i), s.valueAt(1, 4 + i),
                    "环滑动后窗口起点是绝对号 4，第 " + i + " 个可见样本对应绝对号 " + (4 + i));
        }
        assertEquals(110.0, s.valueAt(1, 11), "最后一个样本");
        assertTrue(Double.isNaN(s.valueAt(1, 3)), "绝对号 3 已被覆盖，取数是缺口而不是抛异常");
        assertTrue(Double.isNaN(s.valueAt(1, 12)), "绝对号 12 还没写，取数是缺口");
    }

    @Test
    void 静态数据的写入总数恒等于点数() {
        ArrayChartData d = ChartDataFixtures.arrayOf(9, 8, 7);
        SeriesSource s = SeriesSource.of(d);

        assertEquals(3L, s.writeCount());
        assertEquals(4, s.capacity(), "3 个点向上取到 2 的幂");
        assertEquals(9.0, s.valueAt(1, 0));
        assertEquals(7.0, s.valueAt(1, 2));
        assertTrue(Double.isNaN(s.valueAt(1, 3)), "越界取数是缺口而不是抛异常");
        assertTrue(Double.isNaN(s.valueAt(1, -1)), "负号取数是缺口而不是抛异常");
    }

    @Test
    void 容量向上取到二的幂() {
        assertEquals(1, SeriesSource.capacityFor(0));
        assertEquals(1, SeriesSource.capacityFor(1));
        assertEquals(2, SeriesSource.capacityFor(2));
        assertEquals(4, SeriesSource.capacityFor(3));
        assertEquals(4, SeriesSource.capacityFor(4));
        assertEquals(8, SeriesSource.capacityFor(5));
        assertEquals(1024, SeriesSource.capacityFor(1024));
        assertEquals(2048, SeriesSource.capacityFor(1025));
    }

    /** 写入总数会倒退的假数据源，用来走**生产路径**验证那句守卫。 */
    private static final class BackwardSource implements SeriesSource {

        long written;
        private final int capacity;

        BackwardSource(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public long writeCount() {
            return written;
        }

        @Override
        public double valueAt(int dim, long absoluteIndex) {
            return absoluteIndex;
        }

        @Override
        public int capacity() {
            return capacity;
        }
    }

    /** 记下被请求过哪些绝对号的假数据源。 */
    private static final class RecordingSource implements SeriesSource {

        long written;
        final List<Long> requested = new ArrayList<>();
        private final int capacity;

        RecordingSource(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public long writeCount() {
            return written;
        }

        @Override
        public double valueAt(int dim, long absoluteIndex) {
            requested.add(absoluteIndex);
            return absoluteIndex;
        }

        @Override
        public int capacity() {
            return capacity;
        }
    }
}
