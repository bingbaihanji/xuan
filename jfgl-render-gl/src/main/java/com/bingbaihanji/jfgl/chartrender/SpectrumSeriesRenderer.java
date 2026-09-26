package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.ChartData;
import com.bingbaihanji.jfgl.chart.ChartType;
import com.bingbaihanji.jfgl.chart.RenderContext;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.chart.SeriesRenderer;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.gpu.FftKernel;
import com.bingbaihanji.jfgl.gpu.FftWindow;
import com.bingbaihanji.jfgl.util.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 频谱渲染器：{@link ChartType#SPECTRUM}。
 *
 * <h2>频谱画出来就是一条折线，所以这里没有新的着色器</h2>
 * <p>横轴是 bin 索引、纵轴是幅度，相邻 bin 之间连线。它的<b>实例布局与折线的线段
 * 完全一样</b>（每实例两个 float：{@code mag[k]} 与 {@code mag[k+1]}），因此本类复用
 * {@link SeriesShaders#LINE_VERTEX} 与 {@code ChartRenderer} 那两本折线程序
 * （{@link GLRenderContext#lineShader()} / {@link GLRenderContext#pickShader()}），
 * 实例化机制（双偏移属性、{@code baseInstance}、拾取 pass）也整套照用。
 *
 * <p>与 {@link LineSeriesRenderer} 的差别只有三处：
 * <ol>
 *   <li><b>数据源</b>：不是源系列的环形缓冲，而是 FFT 的输出缓冲——本类在绘制之前
 *       先跑一次 {@link FftKernel#execute}；</li>
 *   <li><b>实例区间的单位</b>：是 bin 而不是样本（见 {@link #binCapacityFor} 那一节）；</li>
 *   <li><b>可见点数</b>：{@code kernel.binCount()}（{@code N/2+1}），不是样本数。</li>
 * </ol>
 *
 * <h2>compute 写、顶点属性读，同一个缓冲，零拷贝</h2>
 * <p>FFT 的输出缓冲<b>同时</b>是 SSBO 与 VBO：{@code glVertexAttribPointer} 记录的是
 * <b>调用时绑在 {@code GL_ARRAY_BUFFER} 上的那个缓冲</b>，与 SSBO 绑定互不干扰
 * （规格 §4.5）。所以 compute 写完不需要任何 GPU 侧拷贝，本类只是
 * {@code gl.bindVbo(kernel.outputBufferId())} 之后照
 * {@code LineSeriesRenderer.configureDataAttributes} 的模式配两个偏移。
 *
 * <p><b>★ 中间必须有 memory barrier</b>：compute 写完到顶点属性读走之间漏了它，
 * 会读到旧值——<b>数值错，不报任何 GL 错误</b>。
 * {@link FftKernel#execute} 内部已经调了 {@code shader.memoryBarrier()}
 * （走 {@code GL_ALL_BARRIER_BITS}，覆盖顶点属性抓取），而本类在绘制之前
 * <b>先</b>调 {@code execute}（见 {@link #render} 的顺序），所以屏障必然在绘制之前。
 * <b>不要把 {@code execute} 挪到绘制之后</b>——那正是这条约束唯一会被破坏的方式。
 *
 * <h2>GL 状态由它自己负责，因为它不在 {@code RenderBatch.submit} 里</h2>
 * <p>与 {@link LineSeriesRenderer} 逐条相同（scissor 的启用与还原、预乘混合因子、
 * VAO 与程序的解绑），理由见那个类的类文档。
 *
 * <h2>拾取</h2>
 * <p>与折线逐行相同：{@code c.pickId()} / {@code c.withPickPass} /
 * {@link #PICK_TOLERANCE_PX}，ID 走 {@code uPickId} 这个 <b>int</b> uniform。
 * 频谱曲线细，要求用户精确点中不合理，所以热区容差照旧放宽。
 */
final class SpectrumSeriesRenderer implements SeriesRenderer {

    /** 单位四边形的四个角，按 triangle strip 顺序。divisor = 0，所有实例共享。 */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /**
     * 拾取容差（半宽，设备像素）。与 {@code LineSeriesRenderer.PICK_TOLERANCE_PX}
     * 同值同理由：只影响 ID pass，不影响画面。
     */
    private static final float PICK_TOLERANCE_PX = 4f;

    /**
     * 默认变换长度（规格 §6.1：固定可配，默认 2048）。
     *
     * <p>刻度是「每个 bin 摊多少像素」：2048 给出 1025 个 bin，与两千像素宽的屏幕大致匹配；
     * 512 只有 257 个 bin，每个摊 8 px，太粗。
     *
     * <p><b>实际用的长度还会被环容量压住</b>，见 {@link #transformLength}——
     * 环里拿不出比它自己容量更多的样本。
     */
    private static final int DEFAULT_FFT_LENGTH = 2048;

    /**
     * 窗函数，默认 Blackman-Harris（规格 §6.2）。
     *
     * <p>示波器场景常要在<b>大载波旁边看小谐波</b>：BH 的旁瓣是 −92 dB，Hamming 只有 −43 dB，
     * 而旁瓣高看起来像"底噪抬起来了"，不像 bug。
     *
     * <p>时期语义：<b>本期窗是全局常量，不可按系列配置</b>——{@code Series} 上没有这个字段，
     * 而 {@code chart/} 在 ③-1 里只许加 {@code ChartType.SPECTRUM} 一个常量（规格 §4.4）。
     * 做成每系列可配属于 ③-1b/c。
     */
    private static final FftWindow WINDOW = FftWindow.BLACKMAN_HARRIS;

    private final GLAbstraction gl;

    private final int vao;

    private final int cornerVbo;

    /**
     * 环容量 → FFT 核。
     *
     * <p><b>为什么要按容量分而不是只留一个</b>：{@link FftKernel} 把源环容量在构造时
     * <b>收下、校验、并存住</b>（{@code execute} 的签名里已经没有它了，见那个方法的说明），
     * 所以一个核只对一种环容量成立。两个采样率不同、环容量不同的频谱系列同时画时，
     * 只留一个核就只能对其中一个正确——而错的那些<b>谱形完全正常、只是取错了槽位</b>。
     *
     * <p>键是容量而不是系列：{@code n} 是容量的纯函数（见 {@link #transformLength}），
     * 所以同容量的两个系列共用同一个核是安全的——GL 调用是串行的，
     * 每个系列的 {@code execute} 都紧接着它自己的绘制，不存在交叉覆盖。
     */
    private final Map<Integer, FftKernel> kernels = new HashMap<>();

    private boolean disposed = false;

    SpectrumSeriesRenderer(GLAbstraction gl) {
        this.gl = gl;
        this.vao = gl.createVao();
        this.cornerVbo = gl.createVbo();

        gl.bindVao(vao);
        gl.bindVbo(cornerVbo);
        ByteBuffer corners = ByteBuffer.allocateDirect(CORNERS.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float c : CORNERS) {
            corners.putFloat(c);
        }
        corners.flip();
        gl.uploadVboBytes(corners);

        // location 0：单位四边形，每顶点取一次
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 配置数据侧的属性指针——数据源是 FFT 的输出缓冲。
     *
     * <p><b>同一个缓冲绑两次、只差 4 个字节的偏移</b>：偏移 0 拿 {@code mag[k]}、
     * 偏移 4 拿 {@code mag[k+1]}，于是每个 bin 只存一次（它与折线那边是同一件事，
     * 理由见 {@code LineSeriesRenderer.configureDataAttributes}）。
     *
     * <p>步长是 {@code 4} 而不是 {@code 8}：两个属性各自是"每实例一个 float"，
     * 由 {@code baseInstance} 挪到正确的那一段上。写成 {@code 8} 会让相邻实例间隔一个 bin，
     * 画出来的谱<b>正好少一半的点</b>，而曲线看起来仍然连贯。
     *
     * <p>调用方必须已经绑定本类的 VAO 与 FFT 的输出缓冲。
     */
    private void configureDataAttributes(int outputBuffer) {
        gl.bindVbo(outputBuffer);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        gl.setVertexAttribDivisor(1, 1);

        glVertexAttribPointer(2, 1, GL_FLOAT, false, Float.BYTES, Float.BYTES);
        glEnableVertexAttribArray(2);
        gl.setVertexAttribDivisor(2, 1);

        gl.bindVbo(0);
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;
        requireSupported(series.type());

        // 缓冲归 ChartRenderer 管，渲染器自己不持有状态（SeriesRenderer 的类文档要求它是纯函数）。
        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        int ringCapacity = buffer.capacity();
        int n = transformLength(ringCapacity);

        // 源系列的本帧可见区间里"最近 N 个样本"的起点槽位。
        //
        // 推导：环里第 m 个样本的槽位是 m & (capacity-1)；
        //       最近的 N 个样本是绝对号 [writeCount - N, writeCount)，
        //       所以第一个的槽位 = (writeCount - N) & (capacity - 1)。
        //
        // 注意 writeCount 是"已上传数"（SeriesBuffer.writeCount()），不是数据源的实时值——
        // 读实时值会把"刚写、还没上传"的样本当成有效，而那些槽位在缓冲里还是旧值
        // （表现为谱上多一段凭空的能量，看着完全正常）。
        long writeCount = buffer.writeCount();
        if (writeCount < n) {
            // 预热期：环里还没有一个完整的窗。
            //
            // **这里不补零**（规格 §8）：补零是 zero-padding，那是另一个特性，
            // 静默补零会让用户看到一条"看起来正常"的错谱——谱峰位置与旁瓣全是假的。
            // 也**不抛异常**：写指针还没走满一个窗是暂态（采集刚开始的那一瞬间），
            // 抛出去等于让应用在开头几毫秒里崩掉，而那是任何流式应用都会经历的状态。
            // 不画与"这条系列还没有数据"一致——与折线在可见区间为空时直接返回是同一种处理。
            // 真正无法成立的配置（容量小于最小变换长度）在 transformLength 里响亮报错。
            return;
        }
        int ringStart = (int) ((writeCount - n) & (ringCapacity - 1));

        FftKernel kernel = kernelFor(ringCapacity, n);
        // ★ 必须在绘制之前跑：execute 内部的 memoryBarrier 是"compute 写 → 顶点属性读"
        //   之间唯一的那道屏障（见类文档）。顺序反了读到旧值，数值错而不报错。
        kernel.execute(buffer.vboId(), ringStart, WINDOW);

        // 可见窗口就是 x 轴的窗口，而 x 轴的单位是 **bin**（规格 §5：x = bin 索引，
        // 要显示成 Hz 由应用自己换算轴标签）。
        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();

        int binCount = kernel.binCount();
        // 线段版（不是 computePoints）：频谱是**相邻 bin 连线**，最后一个实例要右端已算出来。
        // 上界因此是 binCount - 1，最后一个实例是 (binCount-2, binCount-1)——
        // 正好覆盖到 Nyquist 那个 bin。写成 computePoints 会让最后一个实例去读
        // 偏移 binCount*4（缓冲末尾之外），**越界读 SSBO，未定义行为且不报错**。
        List<WindowRange.Segment> segments = WindowRange.compute(
                windowStart, windowEnd, binCount, binCapacityFor(binCount));
        if (segments.isEmpty()) {
            return;
        }

        ChartRenderLayout layout = c.layout();
        Rect plot = layout.plotRect();

        boolean scissorWasOn = gl.isScissorEnabled();
        gl.setScissorEnabled(true);
        setScissorTo(plot, c.viewportHeight());

        gl.enableBlend();
        // enableBlend() 会顺手把混合因子设成**非预乘**的那一组，所以这一行必须在它之后
        // （与 LineSeriesRenderer 同一条理由、同一个顺序）。
        gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        gl.bindVao(vao);
        configureDataAttributes(kernel.outputBufferId());

        ShaderProgram shader = c.lineShader();
        shader.use();
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample", (float) (plot.width / (windowEnd - windowStart)));
        shader.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
        shader.setUniform("uPickTolerance", 0f);
        // 系列是否开 AA 由调用方决定（Gc.antialias 透传下来）。
        // 这里用 uniform 是安全的：一个系列一条 draw call，不涉及 VertexWriter 的合批。
        // 频谱与折线**共用同一个程序**（lineShader），但 uniform 是逐次 draw 设的，
        // 所以两者各自跟着当下的 Gc.antialias 走，不会互相串。
        shader.setUniform("uAntialias", c.antialias() ? 1f : 0f);
        int argb = series.color();
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        shader.setUniform("uPickId", 0);
        // ★ 频谱**不支持平滑**，这里必须显式关掉它。
        //
        // 折线的顶点程序里多了 uSmooth 与两个区间 uniform，而频谱与折线**共用同一个程序**，
        // 于是 uniform 的值会从上一个系列带过来——不设的话，"画完一条平滑的折线再画频谱"
        // 会让频谱走进曲线分支。那不是画面变差一点：频谱的实例属性只有两个
        // （`aY0`/`aY1`，指向 FFT 的输出缓冲），第三、四个属性在这个 VAO 里是**禁用的**、
        // 取到的通用值是 0，曲线于是被拉向 0——一条形状完全合理的假谱。
        //
        // 为什么频谱不该平滑：它的数据是 FFT 的输出（bin），不在"每个样本一个点"的
        // 那条轴上；而且那个输出缓冲只有 binCount 个 float、没有邻居余量，
        // 越界读在 GL 里既非法又不报错（见 {@code SpectrumSeriesRenderer} 的类文档）。
        shader.setUniform("uSmooth", 0f);
        shader.setUniform("uSmoothFrom", 0f);
        shader.setUniform("uSmoothTo", 0f);

        // 横轴的锚点：本段第一个实例相对可见窗口左端的小数偏移（绝对下标在着色器里用不了）。
        // 窗口左端不是整数时（亚像素滚动）那个小数的整数部分不能丢，故先减 floor 再减小数部分。
        double windowFloor = Math.floor(windowStart);
        for (WindowRange.Segment seg : segments) {
            shader.setUniform("uFirstRelIndex",
                    (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
            gl.drawArraysInstancedBaseInstance(
                    GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
        }

        shader.unuse();

        // ID pass：同一份 VAO、同一批实例，只换程序（与折线那边逐行相同）。
        // 插在 shader.unuse() 与 bindVao(0) 之间是有意的：此刻 VAO 与两个实例属性指针
        // 都还是绘制时那套，只换程序就够；搬走的话两份属性配置迟早会分叉，
        // 而分叉的表现是"拾取的位置和画面不一致"。
        int pickId = c.pickId();
        if (pickId != 0) {
            ShaderProgram pick = c.pickShader();
            pick.use();
            // 这一套 uniform 必须与上面的绘制循环**逐个对齐**：少设任何一个，
            // 拾取的位置就和画面不一致——"点到的地方不是看到的地方"。
            // uColor 不设：拾取着色器里它被优化掉。
            pick.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
            pick.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
            pick.setUniform("uValueRange", layout.yMin(), layout.yMax());
            pick.setUniform("uPxPerSample", (float) (plot.width / (windowEnd - windowStart)));
            pick.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
            pick.setUniform("uPickTolerance", PICK_TOLERANCE_PX);
            // 必须是 int 的那个 setUniform（glUniform1i）：对 uint uniform 用它报
            // GL_INVALID_OPERATION 且**值保持 0**，而 0 正是"什么都没命中"。
            pick.setUniform("uPickId", pickId);
            // 与绘制那一趟对齐（理由见上面那三行）：热区必须和画面是同一个形状。
            pick.setUniform("uSmooth", 0f);
            pick.setUniform("uSmoothFrom", 0f);
            pick.setUniform("uSmoothTo", 0f);

            // 裁剪盒在进入本方法时就设好了，到这里还没还原，所以 withPickPass 那条
            // "调用方必须确保 GL_SCISSOR_TEST 已启用"的契约天然满足，被裁掉的部分不可拾取。
            setScissorTo(plot, c.viewportHeight());
            c.withPickPass(() -> {
                for (WindowRange.Segment seg : segments) {
                    pick.setUniform("uFirstRelIndex",
                            (float) (seg.firstDataIndex() - windowFloor
                                    - (windowStart - windowFloor)));
                    gl.drawArraysInstancedBaseInstance(
                            GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
                }
            });
            pick.unuse();
        }

        gl.bindVao(0);
        gl.disableBlend();
        gl.setScissorEnabled(scissorWasOn);
    }

    /**
     * 本渲染器只画 {@link ChartType#SPECTRUM}，其余明确报错。
     *
     * <p>{@code ChartRenderer} 已经按图型分派到本类，这道检查是第二道闸：
     * 它挡的是"分派表被改坏"（比如把 {@code LINE} 也路由到这里）——那时频谱渲染器会拿
     * 折线的样本当幅度画出来，<b>画面上是一条形状完全合理的曲线</b>。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.SPECTRUM) {
            return;
        }
        throw new IllegalArgumentException(
                "SpectrumSeriesRenderer 不支持图型 " + type + "。明确报错而不是静默不画/画错："
                        + "把时域样本当成频谱幅度画出来，是一条形状完全合理的假曲线。");
    }

    /**
     * 本次变换用的长度 N：{@code min(默认长度, 环容量)}。
     *
     * <p>取值与 {@link #DEFAULT_FFT_LENGTH} 一样必须是 2 的幂——两者都是 2 的幂，
     * 所以 {@code min} 也是。被容量压住时用容量本身：<b>环里拿不出比它自己容量更多的样本</b>，
     * 硬要 N &gt; 容量就会把已经被覆盖的槽位当成有效样本，谱形照旧正常、内容是错的。
     *
     * <p><b>配置错误响亮报错</b>（与"预热期不画"是两回事，见 {@link #render}）：
     * 容量小于最小变换长度时这个系列<b>永远</b>算不出频谱，抛异常而不是每帧安静地不画。
     *
     * @throws IllegalArgumentException 环容量小于 {@link FftKernel#MIN_N}
     */
    private static int transformLength(int ringCapacity) {
        if (ringCapacity < FftKernel.MIN_N) {
            throw new IllegalArgumentException(
                    "频谱系列的环形缓冲容量是 " + ringCapacity + "，小于 FFT 的最小变换长度 "
                            + FftKernel.MIN_N + "。这个系列永远算不出频谱，因此明确报错："
                            + "继续画只能画出一条假的谱。");
        }
        return Math.min(DEFAULT_FFT_LENGTH, ringCapacity);
    }

    /**
     * 实例化绘制用的"环容量"参数：{@code binCount} 向上取到的<b>下一个 2 的幂</b>。
     *
     * <h2>★ 这里有个必须交代清楚的口径：缓冲实际有多大、容量参数是多少</h2>
     * <p>{@link WindowRange#compute} 用容量做槽位算术（{@code 下标 & (容量-1)}），
     * 所以它<strong>必须是 2 的幂</strong>。而 {@code FftKernel} 的输出缓冲只分配了
     * {@code binCount = N/2 + 1} 个 float（{@code N = 2048} 时是 1025）——
     * <strong>比它的下一个 2 的幂（2048）小</strong>。于是本类这样安排：
     *
     * <ul>
     *   <li><b>缓冲实际有</b> {@code binCount} 个 float（4·binCount 字节）；</li>
     *   <li><b>传给 compute 的 writeIndex 是</b> {@code binCount}——它是"有效数据的上界"，
     *       与缓冲大小不是一回事；</li>
     *   <li><b>传给 compute 的 capacity 是</b> {@code binCapacityFor(binCount)}——
     *       只用于槽位算术，<b>不是</b>缓冲的大小。</li>
     * </ul>
     *
     * <p><b>为什么这样不会越界读</b>（这是越界读的经典入口，而越界读在 GL 里不报错）：
     * 容量 ≥ binCount，于是 {@code validStart = max(0, binCount - 容量) = 0}、
     * 上界 {@code binCount - 1}，且 {@code firstSlot = lo & (容量-1) = lo}
     * （因为 {@code lo ≤ binCount-2 < 容量}）——<b>永不跨环绕，下标恒等于 bin 索引</b>。
     * 最后一个实例读的字节是偏移 {@code (binCount-1)·4} 与 {@code binCount·4}，
     * 正好落在缓冲的最后一个 float 内。<b>容量取小了才会出事</b>：那时
     * {@code bin k} 与 {@code bin (k-容量)} 会共用槽位，画出来的是**错位的谱**——
     * 谱形完全正常，所以那条路必须是"取下一个 2 的幂"这一种。
     *
     * <p>另一条路（让 {@code FftKernel} 按下一个 2 的幂分配输出缓冲、多出来的当余量）
     * 更省事，但 {@code gpu/} 在本任务里已收口、不许改；而且它会把"缓冲多大"与
     * "槽位算术的容量"这两个概念继续绑在一起——分开之后，
     * {@code WindowRange} 收到的容量就纯粹是算术参数了。
     */
    static int binCapacityFor(int binCount) {
        int highest = Integer.highestOneBit(binCount);
        return highest == binCount ? binCount : highest << 1;
    }

    /** 取（必要时创建）该环容量的 FFT 核。只在 GL 线程上调用。 */
    private FftKernel kernelFor(int ringCapacity, int n) {
        FftKernel kernel = kernels.get(ringCapacity);
        if (kernel == null) {
            kernel = new FftKernel(gl, n, ringCapacity);
            kernels.put(ringCapacity, kernel);
        }
        return kernel;
    }

    /**
     * 把裁剪盒设到绘图区。
     *
     * <p>{@code glScissor} 的原点在帧缓冲<b>左下角</b>、y 向上，而本管线的用户空间是
     * "像素、原点左上、y 向下"，{@code plot} 记的是矩形<strong>上边缘</strong>。
     * 因此 GL 侧的下边 = {@code viewportHeight - plot.y - plot.height}。
     * 漏掉这一步的后果是裁剪区上下镜像——数据在绘图区下半部分被裁掉、上半部分却画到
     * 了绘图区外面，而顶点本身是对的。
     */
    private static void setScissorTo(Rect plot, int viewportHeight) {
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }

    /** 释放本类持有的 GL 资源（VAO、单位四边形 VBO、全部 FFT 核）。幂等。 */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        kernels.values().forEach(FftKernel::dispose);
        kernels.clear();
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
    }
}
