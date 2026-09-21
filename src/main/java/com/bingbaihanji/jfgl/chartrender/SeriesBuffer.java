package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.ChartData;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.util.Disposable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 一个系列的 GPU 常驻缓冲。
 *
 * <h2>它只存 y 值</h2>
 * <p>每个样本 4 字节。线段的两端靠"同一个缓冲、偏移差 4 字节"的两个实例属性拿到
 * （见 {@code LineSeriesRenderer}），所以同一个 y 只存一次。
 *
 * <p><strong>缓冲比环容量多留一个 float</strong>：最后一个实例的第二端会指到界外。
 * 那个实例永远不画，但别让 GPU 有机会去读越界地址。
 *
 * <h2>容量在创建时定死，运行期永不扩容</h2>
 * <p>这是硬约束，不是优化。{@code VertexBuffer.grow()} 是"删旧建新"，
 * 而扩容会让 VAO 里记录的缓冲绑定失效（见 {@code RenderBatch.configureVaoAttributes}）。
 * 因此这里自己管 VBO，<strong>不复用 {@code VertexBuffer}</strong>。
 *
 * <h2>它自己不做脏区判断</h2>
 * <p>"该传哪几个字节"由 {@link SeriesUploadPlan} 算——那是纯算术，可以穷举测试。
 * 本类只负责把算出来的计划执行掉，并<strong>记下这一帧传了多少字节</strong>。
 *
 * <h2>绝对号从哪来</h2>
 * <p>增量上传要回答"我上次传到哪了"，而那个位置必须是<b>绝对号</b>
 * （{@link SeriesSource} 归一出来的那个），不是环槽位、也不是可见窗口内的下标。
 * 环滑动会让同一个绝对号落到不同槽位，用槽位当进度会重复传或漏传——
 * 两种都不报错，只是画出来的波形是错的。
 *
 * <h2>每个点占 4 字节，不是 8</h2>
 * <p>数据存成 float32。若数据动态范围极大（量级 1e9 上要分辨 1e-3 的差别），
 * float32 表示不了，表现为<b>曲线是平的或有台阶</b>。
 * 逃生口：上传前先减去一个基准值（存相对值，着色器里再加回来）。本期不做。
 *
 * <h2>已知上界</h2>
 * <p>{@link SeriesUploadPlan#totalBytes()} 是 {@code int}，容量达到 <b>2^29</b> 时
 * {@code 2^29 * 4} 会溢出。当前容量在 2^16 量级、远在安全区内，
 * <strong>安全上界是容量 ≤ 2^28</strong>（1 GB 缓冲）。真要做更大的环时，
 * 那个字段与这里的算术都要升成 {@code long}。
 *
 * <h2>写入总数必须单调</h2>
 * <p>它假定数据源的写入总数只增不减。倒退意味着数据源被换了，此时缓冲里是旧数据——
 * 继续画会<b>静默显示过期内容</b>，所以本类<strong>明确抛异常</strong>而不是安静地不传。
 * 换数据源时必须重建 {@code SeriesBuffer}。
 *
 * <h2>线程</h2>
 * <p>与 {@code Gc} 同一条纪律：所有方法只在 GL 线程调用。数据源可能被采集线程写
 * （见 {@code RingChartData} 的单写者约定），但本类只读它的 volatile 字段。
 */
public final class SeriesBuffer implements Disposable {

    /**
     * 存进缓冲的是 1 号维度（y）。
     *
     * <p>0 号维度是 x，而 x 由"样本在缓冲里的位置"隐含地给出（等距采样），
     * 所以不必占缓冲。写成常量而不是散落的字面量 1：这是全类唯一一处选择维度的决定。
     */
    private static final int VALUE_DIM = 1;

    private final GLAbstraction gl;

    private final SeriesSource source;

    private final int capacity;

    private final int vbo;

    /** 上一次已经上传到哪（已写样本总数的绝对号）。 */
    private long uploadedCount;

    /** 本帧的上传字节数，每帧由 {@link #beginFrame()} 清零。 */
    private int uploadedBytesThisFrame;

    private boolean disposed = false;

    /** 为给定的数据创建缓冲，容量与数据的环容量一致。 */
    public SeriesBuffer(GLAbstraction gl, ChartData data) {
        this(gl, SeriesSource.of(data));
    }

    /**
     * 为给定的数据创建缓冲，显式指定容量。
     *
     * @throws IllegalArgumentException 容量不是 2 的幂，或与数据的环容量不一致
     */
    public SeriesBuffer(GLAbstraction gl, ChartData data, int capacity) {
        this(gl, SeriesSource.of(data), capacity);
    }

    /**
     * 包内可见：绑定一个自定义的数据源，容量取数据源自己声明的。
     *
     * <p>存在的理由是"绝对号"这件事只有数据源知道，而测试需要拿一个
     * 行为可控（会倒退、会记录）的数据源去驱动本类的真实路径。
     */
    SeriesBuffer(GLAbstraction gl, SeriesSource source) {
        this(gl, source, source.capacity());
    }

    /**
     * @throws IllegalArgumentException 容量不是 2 的幂，或与数据源的环容量不一致
     */
    SeriesBuffer(GLAbstraction gl, SeriesSource source, int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("缓冲容量必须是 2 的幂，实际 " + capacity);
        }
        int dataCapacity = source.capacity();
        if (dataCapacity != capacity) {
            // 现在做成显式失败。放过去的话槽位算术会全错，而画面只是"看起来有点歪"，
            // 与"数据本身就是这个形状"分不开。
            throw new IllegalArgumentException(
                    "缓冲容量 " + capacity + " 与数据的环容量 " + dataCapacity
                            + " 不一致：槽位算术会全错，而画面只是\"看起来有点歪\"");
        }
        this.gl = gl;
        this.source = source;
        this.capacity = capacity;
        // 校验全部做完再建 GL 资源：抛异常时不该留下一个没人认领的 VBO。
        this.vbo = gl.createVbo();
        gl.bindVbo(vbo);
        gl.uploadVboData(new float[capacity + 1]);
        gl.bindVbo(0);
    }

    /** 缓冲字节数：容量个 float，外加一个 float 的余量。 */
    public static int bufferBytesFor(int capacity) {
        return (capacity + 1) * Float.BYTES;
    }

    /** 底层 VBO 的名字，供 VAO 配置用。 */
    public int vboId() {
        return vbo;
    }

    /** 环容量。 */
    public int capacity() {
        return capacity;
    }

    /**
     * 已上传到 GPU 的样本总数（<strong>单调不减</strong>）。
     *
     * <p>它是 {@link WindowRange#compute} 要的那个 {@code writeIndex}：渲染器每帧
     * {@link #uploadNewSamples()} 之后用它算"可见窗口落在哪些实例上"。
     *
     * <p><strong>为什么返回"已上传"而不是数据源的实时写入数</strong>：算出来的实例会被
     * 直接画出来，而 GPU 缓冲里只有已经传上去的那些样本。拿数据源的实时值当上界的话，
     * 写者刚写、还没上传的那几个样本会被当成有效数据画出来——它们在缓冲里仍是上一次的
     * 旧值（首次上传之前甚至是 0），表现为<b>曲线末端拖出一小段凭空的横线</b>。
     * 用已上传数则天然收口：算出来的每一个实例，两端都已经在缓冲里。
     *
     * <p><strong>不要改用 {@code ChartData.itemCount()}</strong>：环写满之后它就停在容量上
     * 不动了，拿它当写指针会让画面定格在第一屏（见 {@link SeriesSource} 的对照表），
     * 这正是 Task 8 抓到的那条静默缺陷。
     *
     * @return 已上传样本数，初值 0
     */
    public long writeCount() {
        return uploadedCount;
    }

    /** 每帧开始时清零本帧的上传计数。 */
    public void beginFrame() {
        uploadedBytesThisFrame = 0;
    }

    /** 把新增的样本传上去。<strong>已经传过的不会重传。</strong> */
    public void uploadNewSamples() {
        if (disposed) {
            throw new IllegalStateException("SeriesBuffer 已释放");
        }
        // 注意这里问的是 source.writeCount()（已写样本总数，只会增长），
        // 而 **不是** itemCount()。RingChartData.itemCount() 返回
        // min(writeIndex, capacity)——环写满之后它永远停在 capacity 不动，
        // 拿它当写指针会让本方法在环满之后静默地再也不上传，画面定格在第一屏。
        long written = source.writeCount();
        if (written < uploadedCount) {
            throw new IllegalStateException(
                    "写入总数倒退了（uploadedCount=" + uploadedCount + ", written=" + written
                            + "）：缓冲里现在是旧数据，继续画会静默显示过期内容。"
                            + "换数据源时必须重建 SeriesBuffer。");
        }
        if (written == uploadedCount) {
            return;
        }
        SeriesUploadPlan plan = SeriesUploadPlan.between(uploadedCount, written, capacity);
        if (plan.totalBytes() == 0) {
            uploadedCount = written;
            return;
        }
        // 第一段（也是唯一一段，除非跨环绕）的第一个样本的**绝对号**。
        //
        // 推导：plan 覆盖的样本数 = totalBytes / 4 = effective，而
        //   effective = min(written - uploadedCount, capacity)
        //   SeriesUploadPlan 里 firstWritten = writtenCount - effective
        // 所以首样本的绝对号就是 written - effective = written - totalPoints。
        //
        // 由 effective ≤ capacity 得 firstWritten ≥ written - capacity = windowStart()，
        // 即这一次上传的每一个样本都还在环里、都能取到真值（不会取成缺口）。
        //
        // 顺带记下**不能**怎么写：用 range.byteOffset() / 4 反推绝对号是错的——
        // 那是环槽位，跨环绕时第二段的槽位回到 0，与绝对号对不上，
        // 于是把两个相隔一整圈的样本画成相邻的，那条线是假的。
        int totalPoints = plan.totalBytes() / Float.BYTES;
        long absolute = written - totalPoints;
        ByteBuffer scratch = ByteBuffer.allocateDirect(plan.totalBytes())
                .order(ByteOrder.nativeOrder());
        for (SeriesUploadPlan.Range range : plan.ranges()) {
            scratch.clear();
            int points = range.byteLength() / Float.BYTES;
            for (int i = 0; i < points; i++) {
                // 绝对号递增地取；**不能**用 range.byteOffset() / 4 反推（理由见上）。
                scratch.putFloat((float) source.valueAt(VALUE_DIM, absolute++));
            }
            scratch.flip();
            gl.bindVbo(vbo);
            gl.uploadVboSubData(range.byteOffset(), scratch);
            gl.bindVbo(0);
        }
        uploadedBytesThisFrame += plan.totalBytes();
        uploadedCount = written;
    }

    /** 本帧通过 {@link #uploadNewSamples()} 上传的字节数。 */
    public int uploadedBytesThisFrame() {
        return uploadedBytesThisFrame;
    }

    /**
     * 绝对号区间 {@code [fromAbsolute, toAbsolute)} 里有没有缺口（NaN）。
     *
     * <p>这里问的是<b>已经上传进去的那些样本</b>有没有缺口（传感器自身吐的 NaN），
     * 不是"两个点之间在环上是否相邻"——后者由渲染器按绝对号连续性判断。
     *
     * @param fromAbsolute 起始样本的<strong>绝对号</strong>（不是窗口相对下标）
     * @param toAbsolute   结束样本的绝对号（半开）
     */
    public boolean hasGapBetween(long fromAbsolute, long toAbsolute) {
        for (long i = fromAbsolute; i < toAbsolute; i++) {
            if (Double.isNaN(source.valueAt(VALUE_DIM, i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        gl.deleteVbo(vbo);
        disposed = true;
    }
}
