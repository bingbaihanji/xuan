package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.Axis;
import com.bingbaihanji.xuan.chart.ChartData;
import com.bingbaihanji.xuan.chart.ChartType;
import com.bingbaihanji.xuan.chart.ColorMapping;
import com.bingbaihanji.xuan.chart.RenderContext;
import com.bingbaihanji.xuan.chart.Series;
import com.bingbaihanji.xuan.chart.SeriesRenderer;
import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.gl.ShaderProgram;
import com.bingbaihanji.xuan.gpu.FftKernel;
import com.bingbaihanji.xuan.gpu.FftWindow;
import com.bingbaihanji.xuan.gpu.SpectrogramKernel;
import com.bingbaihanji.xuan.util.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 声谱图渲染器：{@link ChartType#WATERFALL}（时间 × 频率 × 颜色）。
 *
 * <h2>数据模型不动，FFT 与彩色化都在渲染器内部</h2>
 * <p>它照样只收一个 {@link Series}，系列的 {@link ChartData} 是<b>时域样本的环</b>
 * （与频谱系列共用同一个环）。渲染器每帧自己推进 {@link FftKernel} 与
 * {@link SpectrogramKernel}，把结果写进一张常驻纹理。于是不需要新数据模型，
 * {@code chart/}（冻结层）一个字节都不用改——与 {@code SpectrumSeriesRenderer}
 * 的做法同源。
 *
 * <h2>时间轴怎么推进：hop = n/2，一帧最多 4 列</h2>
 * <p>相邻两列相差 {@code hop = n/2} 个样本（50% 重叠，实时频谱的常规选择）。
 * 每帧新积累的 hop 数是 {@code (writeCount - n) / hop + 1} 的增量，而<b>一帧最多只画
 * {@value #MAX_HOPS_PER_FRAME} 列</b>，多出来的<b>丢最旧</b>——画面上是"时间轴跳了一下"，
 * 而不是每帧越拖越久、永远追不上采集。丢了多少累加进 {@link #droppedHops()}，
 * 那个计数是刻意的观测口：没有它，"时间轴跳了"与"FFT 算错了"这两种现象
 * （画面都只是"谱的形状不太对"）分不开。
 *
 * <p>丢最旧之后还要再夹一次：环里比 {@code writeCount - 容量} 更老的样本已经被覆盖，
 * 从那里取窗口读到的是<b>上一圈残留</b>——谱形照旧正常，内容却是假的。
 *
 * <h2>环形映射不搬像素，但取模必须由我们自己来</h2>
 * <p>纹理是常驻的，每列只写一次，所以显示时用 UV 把环"卷"过来：屏幕从左到右对应
 * 纹理列 {@code headColumn .. headColumn-1}（环形）。列号越过 {@code W-1} 会绕回 0，
 * 于是 UV 在环绕点上是不连续的——<b>这个取模只能放在片段着色器里</b>
 * （顶点着色器里做会让 UV 在四边形内部跳变，插值出来的是一段垃圾）。
 *
 * <p><b>不能靠 {@code GL_REPEAT} 绕着来</b>：{@link GLAbstraction#createImageTexture}
 * 建的纹理环绕方式是 {@code GL_CLAMP_TO_EDGE}（它自己的注释写明"环形映射由调用方在 UV 上做"）。
 * 依赖 {@code GL_REPEAT} 而不去看那一行，表现是右边一小段被拉成最后一列——
 * 画面"只是一侧有点糊"，不报错。
 *
 * <h2>两根轴都不参与映射</h2>
 * <p>四边形铺满绘图区，纹理的 u 是时间（自己滚动）、v 是整个半谱（DC 在下、Nyquist 在上）。
 * 于是 <b>x 轴窗口与 y 轴窗口都被忽略</b>——它们既不滚动时间轴、也不做频率放大。
 * 这是刻意的：频域窗口缩放是另一个特性（要重新安排 bin → 屏幕行的映射），
 * 顺手接上会让"这条轴到底管什么"变得说不清。轴标签由调用方按 {@code Δf = fs/N} 自己写，
 * 渲染层不知道采样率，也不该猜。
 *
 * <h2>行序：这里<b>不</b>再翻一次</h2>
 * <p>{@link SpectrogramKernel} 写的是 {@code row = binCount - 1 - k}，即纹理第 0 行
 * 已经是最高频（Nyquist）。屏幕上方是绘图区上边缘（y 小），所以把屏幕行坐标
 * {@code t = 0} 直接当纹理 {@code v = 0} 用即可。<b>这里再翻一次就会上下颠倒</b>，
 * 而画面看上去只是一张"形状挺像"的声谱图。
 *
 * <h2>配色只走 LUT，且打包顺序不能反</h2>
 * <p>色带由 {@link ColorMapping#toLut()} 生成（{@code byte[1024]}，<b>RGBA 字节</b>），
 * 而 {@link GLAbstraction#createTexture} 收的是本项目统一的 <b>{@code 0xAARRGGBB} 整数</b>
 * （它内部再做 ARGB→RGBA）。所以这里必须先打包成整数——<b>直接把那 256 个字节塞进
 * {@code int[]} 是错的</b>，而且错了以后画面只是"配色不对"（红蓝互换或整片偏色），
 * 极难归因。
 *
 * <h2>拾取：本期不做，理由不是"忘了"</h2>
 * <p>声谱图的四边形覆盖<b>整个绘图区</b>，若给它一个 ID，它会把同一块地方里
 * 别的系列（折线、散点）的拾取整个盖掉——"点中哪里都命中声谱图"，
 * 而画面完全正常。要做得先回答"声谱图的拾取语义是什么"（是整块？还是最近的那一列？），
 * 那是设计决定，不该顺手塞进来。
 *
 * <h2>GL 状态由它自己负责</h2>
 * <p>与 {@code SpectrumSeriesRenderer} 逐条相同（scissor 的启用与还原、预乘混合因子、
 * VAO 与程序的解绑、采样器纹理的解绑），理由见那个类的类文档。
 */
final class SpectrogramRenderer implements SeriesRenderer {

    /** 输出纹理的列数 W（2 的幂）。512 列在 60 fps 下约记住 8.5 秒的时间轴。 */
    static final int DEFAULT_IMAGE_WIDTH = 512;

    /** 默认变换长度。与 {@code SpectrumSeriesRenderer.DEFAULT_FFT_LENGTH} 同值同理由。 */
    static final int DEFAULT_FFT_LENGTH = 2048;

    /**
     * 一帧最多推进几列。
     *
     * <p>掉帧或采集快于渲染时，hop 会积压；不夹的话每帧要补的列数只增不减，
     * 时间轴永远追不上采集，而且每帧越来越慢。夹住之后丢弃的是<b>最旧的</b>那些
     * ——用户看到"时间轴跳了一下"，而不是"画面永远落后一个固定的距离"。
     */
    static final int MAX_HOPS_PER_FRAME = 4;

    /**
     * 窗函数。与频谱系列同值同理由：示波器场景常要在小谐波旁边看大载波，
     * Blackman-Harris 的旁瓣是 −92 dB，Hamming 只有 −43 dB。
     */
    private static final FftWindow WINDOW = FftWindow.BLACKMAN_HARRIS;

    /**
     * 色带。声谱图是强度图，需要一条从暗到亮的连续渐变——
     * {@code INFRARED_4} 是经典的红外配色（黑 → 暗红 → 橙 → 白）。
     */
    private static final ColorMapping PALETTE = ColorMapping.INFRARED_4;

    /** 单位四边形的四个角，按 triangle strip 顺序。divisor = 0，四个顶点共用。 */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /**
     * 顶点着色器：把单位四边形铺到绘图区，并算出一个<b>未取模</b>的环形列坐标。
     *
     * <p>{@code vRing.x = headColumn/W + d}（{@code d} 是 0..1 的横向比例）。
     * 取模放在片段着色器里（见类文档）；这里刻意<b>不</b>取模——在顶点侧取模会让
     * 环绕点两侧的 UV 从 1 跳回 0，插值穿过这条缝的片元会采到中间的列。
     *
     * <p>{@code vRing.y} 直接是 0..1 的行比例，纹理第 0 行在上（类文档说明为什么不再翻）。
     */
    private static final String VERTEX_SHADER = """
            #version 330 core

            // 每顶点（divisor = 0）：单位四边形的角，(0/1, 0/1)
            layout(location = 0) in vec2 aCorner;

            uniform vec4  uPlotRect;   // x, y, w, h（设备像素，原点左上）
            uniform vec2  uViewport;   // 帧缓冲宽高
            uniform float uHeadU;      // headColumn / W，环形映射的起点

            out vec2 vRing;            // x = 未取模的环形列坐标，y = 0..1 的行比例

            void main() {
                float d = aCorner.x;
                float t = aCorner.y;

                // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下（与其它图表着色器同一条）。
                vec2 p = vec2(uPlotRect.x + d * uPlotRect.z, uPlotRect.y + t * uPlotRect.w);
                vec2 ndc = vec2(p.x / uViewport.x * 2.0 - 1.0,
                                1.0 - p.y / uViewport.y * 2.0);

                vRing = vec2(uHeadU + d, t);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    /**
     * 片段着色器：{@code fract} 是环形映射的<b>唯一</b>取模点，随后按预乘输出。
     *
     * <p>预乘不能省：{@link ColorMapping#toLut()} 存的是直通（straight）alpha，
     * 而本管线的混合因子是 {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}（预乘）。
     * 不乘的话半透明配色会过亮一倍——而画面只是"颜色艳了点"。
     */
    private static final String FRAGMENT_SHADER = """
            #version 330 core

            in vec2 vRing;
            uniform sampler2D uSpectrogram;
            out vec4 fragColor;

            void main() {
                // 环形列坐标在这里取模——全项目唯一的一处（不要挪到顶点着色器里）。
                vec4 c = texture(uSpectrogram, vec2(fract(vRing.x), vRing.y));
                fragColor = vec4(c.rgb * c.a, c.a);
            }
            """;

    private final GLAbstraction gl;

    private final int vao;

    private final int cornerVbo;

    private final ShaderProgram shader;

    /** 1×256 的色带纹理（{@code 0xAARRGGBB} 上传，见类文档的打包顺序）。 */
    private final int lutTexture;

    /**
     * 环容量 → FFT 核。按容量分而不是只留一个的理由与 {@code SpectrumSeriesRenderer} 相同：
     * {@link FftKernel} 在构造时把源环容量收下并存住，一个核只对一种环容量成立。
     */
    private final Map<Integer, FftKernel> fftKernels = new HashMap<>();

    /**
     * 系列 → 它自己的时间轴状态（写列位置、已消费到第几个 hop、常驻纹理）。
     *
     * <p>纹理必须<b>逐系列</b>持有，不能按 {@code n} 共享：它是常驻的历史记录，
     * 两个同 {@code n} 的系列共用一个纹理就会互相覆盖对方的列——画面上是两张图
     * 各缺一半的列，而每一列看起来都正常。{@link FftKernel} 则可以共享，
     * 因为它只是"算一次、立刻画掉"的临时缓冲。
     */
    private final Map<Series, SpectrogramState> states = new IdentityHashMap<>();

    /** 因一帧上限而被丢弃的 hop 总数，单调递增。见 {@link #droppedHops()}。 */
    private long droppedHops;

    private boolean disposed;

    SpectrogramRenderer(GLAbstraction gl) {
        this.gl = gl;
        this.shader = gl.createShader(VERTEX_SHADER, FRAGMENT_SHADER);
        this.lutTexture = gl.createTexture(ColorMapping.LUT_SIZE, 1, packLut(PALETTE.toLut()));
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

        // location 0：单位四边形，每顶点取一次（不是实例属性——这个渲染器没有实例）。
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 把 {@link ColorMapping#toLut()} 的 RGBA 字节重新打包成上传要的 {@code 0xAARRGGBB} 整数。
     *
     * <p>包级可见且不碰 GL，<strong>专为可测</strong>：这一步做反了（把字节直接当整数、
     * 或者把 RGBA 当成 ARGB）不会报任何错，只表现为"配色不对"——
     * 正是那种要靠一个纯函数断言钉住的东西。
     *
     * @param lut {@code ColorMapping.toLut()} 的输出，长度必须是 4 的倍数（每像素 R,G,B,A）
     * @throws IllegalArgumentException 长度不是 4 的倍数
     */
    static int[] packLut(byte[] lut) {
        if (lut == null || lut.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "色带必须是每像素 4 字节的 RGBA 序列，实际长度 "
                            + (lut == null ? 0 : lut.length));
        }
        int[] argb = new int[lut.length / 4];
        for (int i = 0; i < argb.length; i++) {
            int r = lut[i * 4] & 0xFF;
            int g = lut[i * 4 + 1] & 0xFF;
            int b = lut[i * 4 + 2] & 0xFF;
            int a = lut[i * 4 + 3] & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return argb;
    }

    /**
     * 本渲染器只画 {@link ChartType#WATERFALL}，其余明确报错。
     *
     * <p>与 {@code SpectrumSeriesRenderer} 那道检查同源：{@code ChartRenderer} 已经按图型
     * 分派，这里是第二道白名单。它挡的是"分派表被改坏"——那时本渲染器会把别的系列的
     * 时域样本当频谱画成一整块矩形，而<b>一整块按频率上色的矩形看起来完全正常</b>。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.WATERFALL) {
            return;
        }
        throw new IllegalArgumentException(
                "SpectrogramRenderer 不支持图型 " + type + "。明确报错而不是静默不画："
                        + "声谱图的四边形覆盖整个绘图区，拿它画别的图型会盖掉整块地方。");
    }

    /**
     * 本次变换用的长度 N：{@code min(默认长度, 环容量)}，与
     * {@code SpectrumSeriesRenderer.transformLength} 同一条规则（理由见那边）。
     *
     * @throws IllegalArgumentException 环容量小于 {@link FftKernel#MIN_N}
     */
    private static int transformLength(int ringCapacity) {
        if (ringCapacity < FftKernel.MIN_N) {
            throw new IllegalArgumentException(
                    "声谱图系列的环形缓冲容量是 " + ringCapacity + "，小于 FFT 的最小变换长度 "
                            + FftKernel.MIN_N + "。这个系列永远算不出频谱，因此明确报错："
                            + "继续画只能画出一块假的声谱图。");
        }
        return Math.min(DEFAULT_FFT_LENGTH, ringCapacity);
    }

    /**
     * 把裁剪盒设到绘图区。{@code glScissor} 的原点在帧缓冲左下角、y 向上，
     * 而本管线的用户空间是"原点左上、y 向下"——漏掉这个翻转的后果是裁剪区上下镜像。
     */
    private static void setScissorTo(Rect plot, int viewportHeight) {
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }

    /**
     * 帧前预扫：丢掉<b>本帧不在图表里</b>的系列的状态（连同它那张常驻纹理）。
     *
     * <p>为什么需要这一步：状态表按 {@code Series} 的<b>对象身份</b>索引，而
     * "每帧重建 {@code Chart}"（每帧 new 出新的 Series）是最自然的写法——没有这次清扫，
     * 那种写法会<b>每帧新建一张纹理与一个 compute program，而且永不释放</b>。
     * {@code ChartRenderer.releaseUnused} 管不到这里（它只知道自己的缓冲与拾取号），
     * 所以由 {@code ChartRenderer} 把"本帧活着的水瀑布系列"传进来。
     *
     * @param live 本帧图表里全部的 {@code WATERFALL} 系列（可以为空）
     */
    void prepareFrame(List<Series> live) {
        if (states.isEmpty()) {
            return;
        }
        Set<Series> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(live);
        for (Iterator<Map.Entry<Series, SpectrogramState>> it = states.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Series, SpectrogramState> entry = it.next();
            if (!keep.contains(entry.getKey())) {
                entry.getValue().dispose();
                it.remove();
            }
        }
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;
        requireSupported(series.type());

        // 缓冲归 ChartRenderer 管，渲染器自己不持有（SeriesRenderer 的类文档要求它是纯函数）。
        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        int ringCapacity = buffer.capacity();
        int n = transformLength(ringCapacity);
        long writeCount = buffer.writeCount();
        if (writeCount < n) {
            // 预热期（与频谱系列那条早退一字不差）：环里还没有一个完整的窗。
            // 不补零（zero-padding 会让用户看到一块"看起来正常"的假谱），也不抛异常
            // （那是采集刚开始的暂态）。真正不成立的配置在 transformLength 里响亮报错。
            return;
        }

        SpectrogramState state = stateFor(series, n);
        advance(state, buffer, ringCapacity, n, writeCount);

        ChartRenderLayout layout = c.layout();
        Rect plot = layout.plotRect();

        boolean scissorWasOn = gl.isScissorEnabled();
        gl.setScissorEnabled(true);
        setScissorTo(plot, c.viewportHeight());

        gl.enableBlend();
        // enableBlend() 会顺手把混合因子设成非预乘的那一组，所以这一行必须在它之后。
        gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        gl.bindVao(vao);
        shader.use();
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uHeadU", (float) state.headColumn / (float) state.imageWidth);
        // 采样器指向纹理单元 0；下面那一对调用就是把声谱图纹理绑到那个单元上。
        shader.setUniform("uSpectrogram", 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, state.spectrogram.imageTextureId());
        gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4);
        // 解绑是房规：纹理单元的绑定没有走抽象层，留着会让下一次"以为单元 0 是空的"的代码踩坑。
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unuse();

        gl.bindVao(0);
        gl.disableBlend();
        gl.setScissorEnabled(scissorWasOn);
    }

    /**
     * 推进时间轴：把积压的 hop 写进纹理，一帧最多 {@link #MAX_HOPS_PER_FRAME} 列。
     *
     * <p>第 {@code h} 个 hop（0 起）覆盖绝对样本 {@code [h*hop, h*hop+n)}，
     * 于是在环里的起点槽位是 {@code (h*hop) & (容量-1)}。
     */
    private void advance(SpectrogramState state, SeriesBuffer buffer,
                         int ringCapacity, int n, long writeCount) {
        int hop = n / 2;
        FftKernel fft = fftKernelFor(ringCapacity, n);

        // 可用的 hop 总数（h = 0 .. available-1），由"窗口右端必须已经上传"推出来。
        long available = (writeCount - n) / hop + 1;
        long pending = available - state.hopIndex;
        if (pending > 0) {
            long toProcess = Math.min(pending, MAX_HOPS_PER_FRAME);
            // 丢最旧：只画最新的 toProcess 个。
            long first = available - toProcess;
            // 再夹一次：比 writeCount-容量 更老的样本已经被覆盖，从那里取的窗口是上一圈的残留
            // ——谱形照旧正常，内容却是假的。
            long oldest = (writeCount - ringCapacity + hop - 1) / hop;
            if (oldest < 0) {
                oldest = 0;
            }
            if (first < oldest) {
                first = oldest;
            }
            droppedHops += pending - (available - first);
            for (long h = first; h < available; h++) {
                int ringStart = (int) ((h * hop) & (ringCapacity - 1));
                // 顺序是硬约束：execute 内部的 memoryBarrier 是"compute 写 → 采样器读"之间
                // 唯一那道屏障，写反了会读上一帧的列——数值错，不报任何 GL 错误。
                fft.execute(buffer.vboId(), ringStart, WINDOW);
                state.spectrogram.execute(fft.outputBufferId(), lutTexture, state.headColumn);
                state.headColumn = (state.headColumn + 1) & (state.imageWidth - 1);
            }
            state.hopIndex = available;
        }
    }

    /** 取（必要时创建、必要时重建）该系列的时间轴状态。只在 GL 线程上调用。 */
    private SpectrogramState stateFor(Series series, int n) {
        SpectrogramState state = states.get(series);
        if (state == null) {
            state = new SpectrogramState();
            states.put(series, state);
        }
        if (state.spectrogram == null || state.n != n) {
            // n 变了（换了数据、环容量跟着变）就得重建：SpectrogramKernel 按 n 构造，
            // 而输出纹理的高度是 n/2+1。用旧纹理画新 n 的谱，采样的是错位的行
            // ——画面上只是一张"看起来有点歪"的声谱图。
            if (state.spectrogram != null) {
                state.spectrogram.dispose();
            }
            state.spectrogram = new SpectrogramKernel(gl, n, DEFAULT_IMAGE_WIDTH);
            state.n = n;
            state.imageWidth = DEFAULT_IMAGE_WIDTH;
            // 纹理内容已经是未初始化的，时间轴从头开始。
            state.headColumn = 0;
            state.hopIndex = 0;
        }
        return state;
    }

    /** 取（必要时创建）该环容量的 FFT 核。只在 GL 线程上调用。 */
    private FftKernel fftKernelFor(int ringCapacity, int n) {
        FftKernel kernel = fftKernels.get(ringCapacity);
        if (kernel == null) {
            kernel = new FftKernel(gl, n, ringCapacity);
            fftKernels.put(ringCapacity, kernel);
        }
        return kernel;
    }

    /**
     * 因一帧上限（或"窗口已经滑出环外"）而<b>被丢弃的 hop 总数</b>，单调递增。
     *
     * <h2>它为什么存在</h2>
     * <p>丢 hop 与"FFT 算错了"在画面上是同一类症状——谱的形状不太对、时间轴好像跳了一下，
     * 两者都<b>不报错</b>。而它们是完全不同的事：前者是"这一帧补不上"，后者是"这一帧算错了"。
     * 没有这个计数，"时间轴跳了"就只能靠眼睛猜。
     *
     * <p>与 {@code ChartRenderer.takeUploadedBytes} / {@code cachedBufferCount} 同一类东西：
     * <b>为一个断言而存在的观测口</b>，生产代码不该依赖它。
     *
     * @return 自本渲染器创建以来丢弃的 hop 总数
     */
    long droppedHops() {
        return droppedHops;
    }

    /** 释放本类持有的 GL 资源（VAO、四边形 VBO、色带纹理、程序、全部核与纹理）。幂等。 */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        states.values().forEach(SpectrogramState::dispose);
        states.clear();
        fftKernels.values().forEach(FftKernel::dispose);
        fftKernels.clear();
        gl.deleteTexture(lutTexture);
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
        shader.dispose();
    }

    /** 一个系列的滚动状态：写列位置、已消费到第几个 hop、以及它自己那张常驻纹理。 */
    private final class SpectrogramState {

        /** 本 hop 该写第几列；每写一列 {@code +1 & (W-1)}。由本类维护（核不记状态）。 */
        int headColumn;

        /** 已经消费到第几个 hop（绝对序号）。 */
        long hopIndex;

        /** 当前输出纹理对应的变换长度。与 {@link #spectrogram} 同生共死。 */
        int n;

        /** 输出纹理的列数，与 {@link #spectrogram} 一致。 */
        int imageWidth;

        SpectrogramKernel spectrogram;

        void dispose() {
            if (spectrogram != null) {
                spectrogram.dispose();
                spectrogram = null;
            }
        }
    }
}
