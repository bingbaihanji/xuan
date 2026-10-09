package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import java.util.Arrays;

/**
 * GPU 上的<strong>多路半谱 FFT</strong>：一次 dispatch 算完 {@code C} 路频谱。
 *
 * <h2>它与 {@link FftKernel} 的关系</h2>
 * <p><b>数学上完全等价</b>——同一个着色器逻辑（取数 → 加窗 → 位反转 → {@code log2(N)} 级蝶形 →
 * 幅度，单 workgroup 全在 shared memory 里做），输出格式也一样（每路 {@code n/2+1} 个 bin 的
 * 线性幅度）。唯一的差别是**多了一个 batch 维度**：
 *
 * <ul>
 *   <li>{@link FftKernel}：一次 dispatch 一路，调用方逐路调；</li>
 *   <li>本类：一次 dispatch {@code C} 路，{@code gl_WorkGroupID.x} 就是路号。</li>
 * </ul>
 *
 * <p><b>为什么值得</b>：{@link FftKernel} 逐路调时，每一路都是"一次 dispatch + 一次
 * {@code glMemoryBarrier}"，而 {@code C} 路是**串行**的（每次一个 workgroup，其余 SM 空转）。
 * 批量之后 {@code C} 个 workgroup **并发**跑，且只剩一次屏障。
 *
 * <h2>输入怎么进同一个 dispatch：SSBO 数组块</h2>
 * <p>一个 compute dispatch 只能读**一个**输入 SSBO，所以多路样本必须"能被同一次 dispatch 读到"。
 * 本类的做法是 <b>{@code ARB_shader_storage_buffer_object} 的数组块</b>：
 *
 * <pre>
 *   layout(std430, binding = 0) readonly buffer In { float y[]; } ch[MAX_BATCH];
 * </pre>
 *
 * <p>数组块配 {@code binding = 0} 时，元素 {@code c} 落在绑定点 {@code c} 上。
 * 于是**每个系列保留自己的 VBO 不动**，调用方把它们分别
 * {@code glBindBufferBase(c, vbo_c)} 即可，**不需要任何数据层改动**
 * （{@code SeriesBuffer} 一行没动）。
 *
 * <p>本机实测（{@code SsboArrayProbe}，2026-10-09）：数组块编译链接通过、
 * {@code ch[gl_WorkGroupID.x]} 读到的确实是各自绑定点上那个缓冲的数据、
 * 全程 {@code glGetError == 0}；{@code GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS = 16}
 * （<b>这个才是 {@link #MAX_BATCH} 的上限</b>，见那个常量的文档）。
 *
 * <h2>⚠️ 输入绑定点 0..MAX_BATCH-1 与输出的关系</h2>
 * <p>数组块占了绑定点 {@code 0..MAX_BATCH-1}，所以**输出必须绑在 {@link #MAX_BATCH} 上**。
 * 写进 0..MAX_BATCH-1 之内就会覆盖掉某一路的输入——而输出的形状看着完全正常。
 * 这条由 {@code FftBatchKernelTest} 钉着。
 *
 * <h2>分组约束：环容量与起始槽位是**共享**的</h2>
 * <p>{@code u_RingCapacity} 与 {@code u_RingStart} 是**所有路共用**的一组 uniform，
 * 所以一次 batch 要求组内所有系列**环容量相同、写指针相同**（同步采集天然满足）。
 * 不满足的必须**回退到单路 {@link FftKernel}**——见 {@code SpectrumSeriesRenderer}。
 */
public final class FftBatchKernel implements Disposable {

    /** 本核支持的最大变换长度。由 shared memory 决定（2×MAX_N 个 float）。 */
    public static final int MAX_N = 4096;

    /** 本核支持的最小变换长度。 */
    public static final int MIN_N = 256;

    /**
     * 一次 dispatch 最多几路。
     *
     * <p><b>它是编译期常量</b>：GLSL 的数组长度必须常量。
     *
     * <p><b>上限不是绑定点数，而是"每个 compute 着色器最多几个 SSBO 块"。</b>
     * 数组块 {@code ch[N]} 在链接器眼里是 <b>N 个块</b>，再加输出那一个，一共 {@code N+1} 个。
     * 本机实测 {@code GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS = 16}（而
     * {@code GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS = 96}——<b>那是绑定点总数，不是块数，别混</b>），
     * 所以 {@code MAX_BATCH = 15}。
     *
     * <p>⚠️ <b>实测踩过</b>：最初按绑定点预算取了 32，结果**编译通过、链接失败**
     * （33 个块 &gt; 16），而错误信息只有一句 {@code linking failed}。
     * 换驱动/换机器要重新查这个上限——它不是能"估"的量。
     *
     * <p>路数超过 {@link #MAX_BATCH} 时**分批**（每批 ≤ 15 路）或回退到单路 {@link FftKernel}，
     * 见 {@code SpectrumSeriesRenderer}。
     */
    public static final int MAX_BATCH = 15;

    /** 线程组大小。与共享内存数组长度**无关**（那个由 {@link #MAX_N} 定）。 */
    private static final int LOCAL_SIZE = 1024;

    /**
     * 着色器源码模板。
     *
     * <p>{@code %d} 依次是：workgroup 大小（{@link #LOCAL_SIZE}）、数组块长度
     * （{@link #MAX_BATCH}）、输出绑定点（{@link #MAX_BATCH}）、shared 长度 ×2（{@link #MAX_N}）。
     *
     * <p>⚠️ 两个坑与 {@link FftKernel} 逐条相同：GLSL 的取模 {@code %} 要写成 {@code %%}；
     * 别在注释里写 {@code %} 或 {@code %d}。
     */
    private static final String SHADER_TEMPLATE = """
                                                  #version 430
                                                  layout(local_size_x = %d) in;

                                                  // 数组块：元素 c 落在绑定点 c 上。于是每路一个 VBO、分别 bindBufferBase 即可。
                                                  layout(std430, binding = 0) readonly  buffer In  { float y[]; } ch[%d];
                                                  // ⚠️ 输出必须绑在 MAX_BATCH 上：输入数组块占了 0..MAX_BATCH-1。
                                                  layout(std430, binding = %d) writeonly buffer Out { float mag[]; };

                                                  // 全部是 int / float —— setUniform 走 glUniform1i / glUniform1f。
                                                  uniform int   u_N;
                                                  uniform int   u_RingCapacity; // 环容量（2 的幂）——**所有路共享**
                                                  uniform int   u_RingStart;    // 第一个样本在环里的槽位——**所有路共享**
                                                  uniform int   u_WindowKind;   // 0=矩形 1=Hann 2=Hamming 3=BH
                                                  uniform float u_Scale;        // 2/N × 窗补偿

                                                  // 注意：不要用 half 当变量名 —— 它是 GLSL 保留字。
                                                  shared float sRe[%d];
                                                  shared float sIm[%d];

                                                  float windowAt(int i) {
                                                      float x = 6.283185307179586 * float(i) / float(u_N - 1);
                                                      if (u_WindowKind == 0) return 1.0;
                                                      if (u_WindowKind == 1) return 0.5 - 0.5 * cos(x);
                                                      if (u_WindowKind == 2) return 0.54 - 0.46 * cos(x);
                                                      return 0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2.0 * x) - 0.01168 * cos(3.0 * x);
                                                  }

                                                  void main() {
                                                      int c   = int(gl_WorkGroupID.x);   // 路号 = 批量维度
                                                      int tid = int(gl_LocalInvocationID.x);
                                                      int n   = u_N;
                                                      int cap = u_RingCapacity;
                                                      int outBase = c * (n / 2 + 1);     // 本路在输出里那一段的基址

                                                      int logN = 0;
                                                      for (int t = n; t > 1; t >>= 1) logN++;

                                                      // ① 取数 + 加窗 + 位反转。ch[c] 是本路自己的那个缓冲。
                                                      for (int i = tid; i < n; i += int(gl_WorkGroupSize.x)) {
                                                          int src = (u_RingStart + i) & (cap - 1);
                                                          int rev = int(bitfieldReverse(uint(i)) >> uint(32 - logN));
                                                          sRe[rev] = ch[c].y[src] * windowAt(i);
                                                          sIm[rev] = 0.0;
                                                      }
                                                      barrier();

                                                      // ② log2(N) 级蝶形（DIT，输入已位反转）。
                                                      for (int len = 2; len <= n; len <<= 1) {
                                                          int halfLen = len >> 1;
                                                          for (int k = tid; k < n / 2; k += int(gl_WorkGroupSize.x)) {
                                                              int group = k / halfLen;
                                                              int pos   = k %% halfLen;
                                                              int k1    = group * len + pos;
                                                              int k2    = k1 + halfLen;
                                                              float angle = -6.283185307179586 * float(pos) / float(len);
                                                              float wr = cos(angle);
                                                              float wi = sin(angle);
                                                              float tr = wr * sRe[k2] - wi * sIm[k2];
                                                              float ti = wr * sIm[k2] + wi * sRe[k2];
                                                              float ur = sRe[k1];
                                                              float ui = sIm[k1];
                                                              sRe[k1] = ur + tr;
                                                              sIm[k1] = ui + ti;
                                                              sRe[k2] = ur - tr;
                                                              sIm[k2] = ui - ti;
                                                          }
                                                          barrier();
                                                      }

                                                      // ③ 幅度（半谱，含 DC 与 Nyquist），写到本路自己那一段。
                                                      for (int k = tid; k <= n / 2; k += int(gl_WorkGroupSize.x)) {
                                                          float re = sRe[k];
                                                          float im = sIm[k];
                                                          mag[outBase + k] = sqrt(re * re + im * im) * u_Scale;
                                                      }
                                                  }
                                                  """;

    private final GLAbstraction gl;

    private final ComputeShader shader;

    /** 输出缓冲：{@code MAX_BATCH × binCount} 个 float，一路一段。 */
    private final int outputBuffer;

    private final int n;

    private final float[] scaleByWindow = new float[FftWindow.values().length];

    private boolean disposed;

    /**
     * @param gl GL 抽象层
     * @param n  变换长度，<strong>必须是 2 的幂且落在 [{@link #MIN_N}, {@link #MAX_N}]</strong>
     * @throws IllegalArgumentException 参数不合法
     */
    public FftBatchKernel(GLAbstraction gl, int n) {
        if (n < MIN_N || n > MAX_N || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "FFT 长度必须是 2 的幂且落在 [" + MIN_N + ", " + MAX_N + "]：实际 " + n
                            + "。上限由 shared memory 决定（2×MAX_N 个 float）。");
        }
        Arrays.fill(scaleByWindow, Float.NaN);
        this.gl = gl;
        this.n = n;
        this.shader = new ComputeShader(shaderSource());
        int buf = 0;
        try {
            buf = gl.createBuffer();
            gl.bindShaderStorageBuffer(buf);
            gl.allocateBufferStorage((long) MAX_BATCH * binCount() * Float.BYTES);
        } catch (RuntimeException e) {
            shader.dispose();
            if (buf != 0) {
                gl.deleteBuffer(buf);
            }
            throw e;
        }
        gl.bindShaderStorageBuffer(0);
        this.outputBuffer = buf;
    }

    /** 由常量生成的着色器源码；构造时才会求值。<strong>包级可见是刻意的</strong>（见 FftKernel）。 */
    static String shaderSource() {
        return SHADER_TEMPLATE.formatted(LOCAL_SIZE, MAX_BATCH, MAX_BATCH, MAX_N, MAX_N);
    }

    /** {@link #LOCAL_SIZE} 的只读访问器，供同包测试断言布局与步长的一致性。 */
    static int localSize() {
        return LOCAL_SIZE;
    }

    /** {@link #SHADER_TEMPLATE} 的只读访问器——测试必须能看见"格式化之前"的那份。 */
    static String shaderTemplate() {
        return SHADER_TEMPLATE;
    }

    /** FFT 输出的 bin 数（半谱，含 DC 与 Nyquist），**每一路**这么多。 */
    public int binCount() {
        return n / 2 + 1;
    }

    /** 输出缓冲的名字。第 c 路的谱在偏移 {@code c * binCount()} 处。可绑 VBO 直接画。 */
    public int outputBufferId() {
        return outputBuffer;
    }

    /**
     * 对 {@code count} 路做一次批量半谱 FFT。
     *
     * <p><strong>环容量与起始槽位是所有路共用的</strong>（见类文档的分组约束）——
     * 调用方必须保证组内每个系列的环容量与写指针都相同，否则取到错的槽位、
     * <b>谱形完全正常而峰位偏</b>。
     *
     * @param inputBuffers 长度 ≥ {@code count}，第 c 个是第 c 路的环 VBO，会被绑到绑定点 c
     * @param count        本次几路，落在 {@code [1, } {@link #MAX_BATCH}{@code ]}
     * @param ringStart    第一个样本在环里的槽位
     * @param ringCapacity 环容量（2 的幂）
     * @param window       窗函数
     */
    public void execute(int[] inputBuffers, int count, int ringStart, int ringCapacity, FftWindow window) {
        if (disposed) {
            throw new IllegalStateException("FftBatchKernel 已释放");
        }
        if (count < 1 || count > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "count 必须落在 [1, " + MAX_BATCH + "]，实际 " + count);
        }
        if (inputBuffers.length < count) {
            throw new IllegalArgumentException(
                    "inputBuffers 长度 " + inputBuffers.length + " 少于 count " + count);
        }
        if (ringCapacity <= 0 || (ringCapacity & (ringCapacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + ringCapacity);
        }
        shader.use();
        shader.setUniform("u_N", n);
        shader.setUniform("u_RingCapacity", ringCapacity);
        shader.setUniform("u_RingStart", ringStart);
        shader.setUniform("u_WindowKind", window.ordinal());
        shader.setUniform("u_Scale", scaleFor(window));
        for (int c = 0; c < count; c++) {
            gl.bindBufferBase(c, inputBuffers[c]);
        }
        // ★ 输出绑在 MAX_BATCH 上，避开输入数组块占的 0..MAX_BATCH-1。
        gl.bindBufferBase(MAX_BATCH, outputBuffer);
        shader.dispatch(count, 1, 1);
        shader.memoryBarrier();
        for (int c = 0; c < count; c++) {
            gl.bindBufferBase(c, 0);
        }
        gl.bindBufferBase(MAX_BATCH, 0);
        shader.unuse();
    }

    /** 该窗在长度 {@code n} 上的缩放系数，按 ordinal 记忆化（理由同 FftKernel）。 */
    private float scaleFor(FftWindow window) {
        int i = window.ordinal();
        float scale = scaleByWindow[i];
        if (Float.isNaN(scale)) {
            scale = (float) (2.0 / n * window.compensation(n));
            scaleByWindow[i] = scale;
        }
        return scale;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        shader.dispose();
        gl.deleteBuffer(outputBuffer);
    }
}
