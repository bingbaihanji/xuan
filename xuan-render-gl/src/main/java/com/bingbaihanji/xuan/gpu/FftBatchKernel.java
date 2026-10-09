package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import java.util.Arrays;

/**
 * GPU 上的多路半谱 FFT：一次 dispatch 算完 {@code C} 路频谱。与 {@link FftKernel} 数学上完全等价（同一套取数 → 加窗 → 位反转 → 蝶形 → 幅度，输出也是每路 {@code n/2+1} 个 bin 的线性幅度），只多一个 batch 维度：{@code gl_WorkGroupID.x} 就是路号，{@code C} 个 workgroup 并发跑、只剩一次屏障（逐路调时每路一次 dispatch + 一次 {@code glMemoryBarrier}，且拿到的 workgroup 是串行的）。
 * <p>多路样本靠 <b>SSBO 数组块</b>进同一次 dispatch：{@code layout(std430, binding = 0) readonly buffer In { float y[]; } ch[MAX_BATCH];}
 * ——元素 {@code c} 落在绑定点 {@code c} 上，于是每个系列保留自己的 VBO 不动，调用方分别 {@code glBindBufferBase(c, vbo_c)} 即可（{@code SeriesBuffer} 一行没动）。本机实测（{@code SsboArrayProbe}，2026-10-09）：编译链接通过、{@code ch[gl_WorkGroupID.x]} 确实读到各自绑定点上那个缓冲的数据、全程 {@code glGetError == 0}；{@code GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS = 16}，这才是 {@link #MAX_BATCH} 的上限。
 * <p>数组块占了绑定点 {@code 0..MAX_BATCH-1}，所以输出必须绑在 {@link #MAX_BATCH} 上；写进那个区间会覆盖掉某一路的输入，而输出的形状看着完全正常（{@code FftBatchKernelTest} 钉着）。
 * <p>{@code u_RingCapacity} 与 {@code u_RingStart} 是<b>所有路共用</b>的 uniform，所以一次 batch 要求组内所有系列环容量相同、写指针相同（同步采集天然满足）；不满足的必须回退到单路 {@link FftKernel}（见 {@code SpectrumSeriesRenderer}）。
 */
public final class FftBatchKernel implements Disposable {

    /** 本核支持的最大变换长度，由 shared memory 决定（{@code 2×MAX_N} 个 float）。 */
    public static final int MAX_N = 4096;

    /** 本核支持的最小变换长度。 */
    public static final int MIN_N = 256;

    /**
     * 一次 dispatch 最多几路。<b>编译期常量</b>（GLSL 的数组长度必须常量）。上限不是绑定点数，而是"每个 compute 着色器最多几个 SSBO 块"——数组块 {@code ch[N]} 在链接器眼里是 {@code N} 个块，加输出共 {@code N+1} 个；本机实测 {@code GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS = 16}（{@code GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS = 96} 是绑定点总数，不是块数，别混）⇒ {@code MAX_BATCH = 15}。路数超过它时要分批（每批 ≤ 15 路）或回退到单路 {@link FftKernel}。
     *
     * <p>实测踩过：最初按绑定点预算取了 32，结果编译通过、链接失败（33 个块 &gt; 16），错误信息只有一句 {@code linking failed}。换驱动/换机器要重新查这个上限——它不是能"估"的量。
     */
    public static final int MAX_BATCH = 15;

    /** 线程组大小。与共享内存数组长度<b>无关</b>（那个由 {@link #MAX_N} 定）。 */
    private static final int LOCAL_SIZE = 1024;

    /**
     * 着色器源码模板。{@code %d} 依次是：workgroup 大小（{@link #LOCAL_SIZE}）、数组块长度与输出
     * 绑定点（都是 {@link #MAX_BATCH}）、shared 长度 ×2（{@link #MAX_N}）。
     *
     * <p>两个坑与 {@link FftKernel} 相同：GLSL 的取模 {@code %} 要写成 {@code %%}；注释里别写
     * {@code %} 或 {@code %d}。
     */
    private static final String SHADER_TEMPLATE = """
                                                  #version 430
                                                  layout(local_size_x = %d) in;

                                                  // 数组块：元素 c 落在绑定点 c 上。于是每路一个 VBO、分别 bindBufferBase 即可。
                                                  layout(std430, binding = 0) readonly  buffer In  { float y[]; } ch[%d];
                                                  // 输出必须绑在 MAX_BATCH 上：输入数组块占了 0..MAX_BATCH-1。
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
     * 对 {@code count} 路做一次批量半谱 FFT。<b>环容量与起始槽位是所有路共用的</b>，调用方必须保证
     * 组内每个系列的环容量与写指针都相同，否则取到错的槽位、<b>谱形完全正常而峰位偏</b>。
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
        // 输出绑在 MAX_BATCH 上，避开输入数组块占的 0..MAX_BATCH-1。
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
