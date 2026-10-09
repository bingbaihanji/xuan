package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

/**
 * GPU 上的<strong>通用复数 FFT</strong>：正变换、逆变换、批量、实数输入快通道。
 *
 * <h2>它与 {@link FftKernel} 的分工</h2>
 * <p>两者<b>不是</b>同一个东西的两个版本，别搞混：
 * <ul>
 *   <li>{@link FftKernel} 是<b>频谱分析/显示</b>专用——实数进、加窗、出<b>半谱幅度</b>
 *       （{@code N/2+1} 个 float），已经接上绘制、有 50 条断言钉着。<b>本类不碰它。</b></li>
 *   <li>本类是<b>通用原语</b>——复数（或实数）进、出<b>完整复数谱</b>（{@code N} 个复数），
 *       可正可逆，可批量。<b>不做加窗、不做"物理幅度"归一化</b>：那些是分析语义，
 *       归上面那条线。要做频域滤波/重合成这类事，用的就是本类。</li>
 * </ul>
 *
 * <h2>三条来自 {@code GPUFFT} 实测缺陷的设计决定（{@link FftKernel} 同源）</h2>
 * <ol>
 *   <li><b>位反转是着色器内部的第一步</b>，不依赖任何外部步骤。
 *       （{@code GPUFFT} 算了位反转索引却从没引用过它。）</li>
 *   <li><b>所有 uniform 声明成 {@code int}/{@code float}</b>，与
 *       {@code ComputeShader.setUniform} 的 {@code glUniform1i}/{@code glUniform1f} 配对。
 *       声明成 {@code uint} 会 {@code GL_INVALID_OPERATION} <b>且值不生效</b>（静默）。</li>
 *   <li><b>不许用 GLSL 保留字当标识符</b>（{@code half}）——那样着色器编译不过。</li>
 * </ol>
 *
 * <h2>批量：{@code gl_WorkGroupID.x} 就是通道号</h2>
 * <p>{@code dispatch(batch, 1, 1)}，一个 workgroup 干一路。每个 workgroup
 * <b>独占</b>自己那份 shared memory（{@code 2×MAX_N} 个 float），
 * <b>所以批量不增加 shared 占用</b>——这正是它几乎免费的原因。
 *
 * <h2>实数快通道（{@code u_InStride}）</h2>
 * <p>采集来的实数信号若按"补零虚部当复数"处理，会<b>白传一倍字节、白算一倍</b>。
 * 于是输入 SSBO 只有一种布局（交错 {@code float[]}、通道优先），由一个 uniform 决定怎么读：
 * <ul>
 *   <li>{@code u_InStride == 1}：实数——{@code re = in[base+i]}, {@code im = 0}；</li>
 *   <li>{@code u_InStride == 2}：交错复数——{@code re = in[base+2i]}, {@code im = in[base+2i+1]}。</li>
 * </ul>
 * <p><b>⚠️ 这是本类最容易静默出错的一处</b>（"一个缓冲两种含义"）。因此
 * {@code FftVerifier} 有一条定向断言：同一实数信号走 stride 1 与走 stride 2（im=0）
 * <b>必须逐点一致</b>。
 *
 * <h2>归一化：只允许一种约定</h2>
 * <p>正向<b>不归一</b>（单位幅度余弦的谱峰是 {@code N/2}），逆向乘 {@code 1/N}。
 * 见 {@link #scaleFor(int, boolean)}。两套约定并存就是静默错误
 * （原规格 R5 点名批评过参考项目"{@code sqrt(2)/N} 与 {@code 2/N} 差 3 dB"）。
 *
 * <h2>N 的上限由 shared memory 决定</h2>
 * <p>共享内存要放 {@code 2×MAX_N} 个 float，{@link #MAX_N} = 4096 时是 32 KB；
 * 抬到 8192 就是 64 KB，超驱动 48 KB 默认上限（实测以 {@code C5041} 链接失败，响亮）。
 * <b>所以上限是 {@link #MAX_N}。</b>
 */
public final class ComplexFft implements Disposable {

    /** 本类支持的最小变换长度。 */
    public static final int MIN_N = 256;

    /** 本类支持的最大变换长度。由 shared memory 决定（2×MAX_N 个 float），见类文档。 */
    public static final int MAX_N = 4096;

    /**
     * 线程组大小。
     *
     * <p><b>它与共享内存数组的长度<em>无关</em></b>——数组长度由 {@link #MAX_N} 决定。
     * 唯一与它绑定的东西是着色器里的 {@code layout(local_size_x = ...)}，
     * 而三处循环的步长走 {@code gl_WorkGroupSize.x}，由布局推导，不再各写一份。
     */
    private static final int LOCAL_SIZE = 1024;

    /**
     * 着色器源码模板。
     *
     * <p>{@code %d} 依次是：workgroup 大小（{@link #LOCAL_SIZE}）、
     * shared 数组长度（{@link #MAX_N}）、shared 数组长度（{@link #MAX_N}）。
     *
     * <h2>⚠️ 往这份模板里写 GLSL 时的两个坑</h2>
     * <ul>
     *   <li><strong>GLSL 的取模 {@code %} 必须写成 {@code %%}</strong>：模板要走
     *       {@link String#formatted}，一个光秃秃的 {@code %} 会被当成格式符
     *       （{@code UnknownFormatConversionException}）。这条路是<b>响亮</b>失败的。</li>
     *   <li>不要在 GLSL 注释里写 {@code %} 或 {@code %d} 这类序列——它们同样会被格式化
     *       吃掉（{@code %d} 还会<b>消耗一个占位符实参</b>）。</li>
     * </ul>
     */
    private static final String SHADER_TEMPLATE = """
                                                  #version 430
                                                  layout(local_size_x = %d) in;

                                                  layout(std430, binding = 0) readonly  buffer In  { float inX[]; };
                                                  layout(std430, binding = 1) writeonly buffer Out { float outX[]; };

                                                  // 全部是 int / float —— setUniform 走 glUniform1i / glUniform1f。
                                                  // 声明成 uint 会 GL_INVALID_OPERATION 且**值不生效**（静默）。
                                                  uniform int   u_N;         // 变换长度（2 的幂，范围见 Java 侧 MIN_N / MAX_N）
                                                  uniform int   u_InStride;  // 1 = 实数输入，2 = 交错复数
                                                  uniform int   u_Inverse;   // 0 = 正向，1 = 逆向
                                                  uniform float u_Scale;     // 归一化：正向 1，逆向 1/N

                                                  // 注意：不要用 half 当变量名 —— 它是 GLSL 保留字。
                                                  shared float sRe[%d];
                                                  shared float sIm[%d];

                                                  void main() {
                                                      int b   = int(gl_WorkGroupID.x);   // 通道号 = 批量维度
                                                      int tid = int(gl_LocalInvocationID.x);
                                                      int n   = u_N;
                                                      int inBase  = b * u_InStride * n;  // 每一路的输入起点（通道优先）
                                                      int outBase = b * 2 * n;           // 每一路的输出起点（交错复数）

                                                      int logN = 0;
                                                      for (int t = n; t > 1; t >>= 1) logN++;

                                                      // ① 取数 + 位反转，一次做完。实数时虚部取 0。
                                                      //    ⚠️ 移位必须在 **uint 域**里做完再转 int：
                                                      //    反转之后最高位几乎总是 1，转成 int 就是负数，
                                                      //    而 GLSL 对**有符号**左操作数的 >> 是算术右移（符号扩展）
                                                      //    ⇒ 负下标写共享内存 = 越界写。
                                                      for (int i = tid; i < n; i += int(gl_WorkGroupSize.x)) {
                                                          int rev = int(bitfieldReverse(uint(i)) >> uint(32 - logN));
                                                          sRe[rev] = inX[inBase + i * u_InStride];
                                                          sIm[rev] = (u_InStride == 1) ? 0.0 : inX[inBase + i * u_InStride + 1];
                                                      }
                                                      barrier();

                                                      // ② log2(N) 级蝶形（DIT，输入已位反转）。
                                                      //    逆向取共轭旋转因子（+2π 而非 −2π），末尾再乘 1/N。
                                                      for (int len = 2; len <= n; len <<= 1) {
                                                          int halfLen = len >> 1;
                                                          for (int k = tid; k < n / 2; k += int(gl_WorkGroupSize.x)) {
                                                              int group = k / halfLen;
                                                              int pos   = k %% halfLen;
                                                              int k1    = group * len + pos;
                                                              int k2    = k1 + halfLen;
                                                              float angle = (u_Inverse == 1)
                                                                      ?  6.283185307179586 * float(pos) / float(len)
                                                                      : -6.283185307179586 * float(pos) / float(len);
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

                                                      // ③ 输出**完整复数谱**（自然序，N 个复数，交错存放），乘归一化系数。
                                                      for (int k = tid; k < n; k += int(gl_WorkGroupSize.x)) {
                                                          outX[outBase + 2 * k]     = sRe[k] * u_Scale;
                                                          outX[outBase + 2 * k + 1] = sIm[k] * u_Scale;
                                                      }
                                                  }
                                                  """;

    private final GLAbstraction gl;

    private final ComputeShader shader;

    /** 输出缓冲：{@code 2 × n × maxBatch} 个 float（交错复数，通道优先）。 */
    private final int outputBuffer;

    private final int n;

    private final int maxBatch;

    /** {@link #dispose()} 的幂等标志——{@code deleteBuffer} 只许删一次。 */
    private boolean disposed;

    /**
     * @param gl       GL 抽象层
     * @param n        变换长度，<strong>必须是 2 的幂且落在
     *                 [{@link #MIN_N}, {@link #MAX_N}]</strong>
     * @param maxBatch 单次 dispatch 最多几路，决定输出缓冲大小
     * @throws IllegalArgumentException 参数不合法
     */
    public ComplexFft(GLAbstraction gl, int n, int maxBatch) {
        if (n < MIN_N || n > MAX_N || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "FFT 长度必须是 2 的幂且落在 [" + MIN_N + ", " + MAX_N + "]：实际 " + n
                            + "。上限由 shared memory 决定（2×MAX_N 个 float）。");
        }
        if (maxBatch < 1) {
            throw new IllegalArgumentException("maxBatch 必须 ≥ 1，实际 " + maxBatch);
        }
        this.gl = gl;
        this.n = n;
        this.maxBatch = maxBatch;
        this.shader = new ComputeShader(shaderSource());
        // 从这里往下都是 GL 调用。构造没完成时 dispose() 永远不会被调用，
        // 所以这条路径必须自己收尾——别留半截 program / buffer 在上下文里。
        int buf = 0;
        try {
            buf = gl.createBuffer();
            gl.bindShaderStorageBuffer(buf);
            gl.allocateBufferStorage((long) 2 * n * maxBatch * Float.BYTES);
        } catch (RuntimeException e) {
            shader.dispose();
            if (buf != 0) {
                gl.deleteBuffer(buf);
            }
            throw e;
        }
        // 解绑放在 try 之外：写进 finally 的话，它自己抛出的异常会**顶掉**上面那个原始异常。
        gl.bindShaderStorageBuffer(0);
        this.outputBuffer = buf;
    }

    /**
     * 由常量生成的着色器源码；构造 {@link ComplexFft} 时才会求值。
     *
     * <p><strong>包级可见是刻意的</strong>：它是纯字符串运算、不需要 GL 上下文，
     * 所以按本项目的判据（"能不能脱离 GL 上下文跑测试"）它<strong>该进单测</strong>——
     * 见 {@code ComplexFftTest}。
     */
    static String shaderSource() {
        return SHADER_TEMPLATE.formatted(LOCAL_SIZE, MAX_N, MAX_N);
    }

    /** {@link #LOCAL_SIZE} 的只读访问器，供同包测试断言布局与步长的一致性。 */
    static int localSize() {
        return LOCAL_SIZE;
    }

    /**
     * {@link #SHADER_TEMPLATE} 的只读访问器——<strong>测试必须能看见"格式化之前"的那份</strong>。
     *
     * <p>理由与 {@code FftKernel.shaderTemplate()} 逐条相同：{@code shaderSource()} 的返回值是
     * 格式化<b>之后</b>的串，两处缺陷在那里看不见——写死的 {@code sRe[4096]} 与
     * {@code sRe[%d]} 在 {@code MAX_N == 4096} 时生成一模一样的串；{@code %n} 会被
     * {@code formatted} 吃掉。
     */
    static String shaderTemplate() {
        return SHADER_TEMPLATE;
    }

    /**
     * 该方向的归一化系数：正向 {@code 1}（不归一），逆向 {@code 1/n}。
     *
     * <p><strong>包级可见是刻意的</strong>：这是一个纯算术，脱离 GL 上下文就能穷举断言
     * （见 {@code ComplexFftTest}）——而"两套归一化约定"正是原规格点名的静默错误。
     */
    static float scaleFor(int n, boolean inverse) {
        return inverse ? 1.0f / n : 1.0f;
    }

    /** 变换长度。 */
    public int n() {
        return n;
    }

    /** 单次 dispatch 允许的最大路数。 */
    public int maxBatch() {
        return maxBatch;
    }

    /** 输出缓冲的名字。它同时可以被绑成顶点缓冲，供实例化绘制读走（零拷贝）。 */
    public int outputBufferId() {
        return outputBuffer;
    }

    /**
     * 正向变换：输入 → 完整复数谱（<b>不归一</b>）。
     *
     * @param inputBuffer 输入 SSBO（交错 {@code float[]}，通道优先）
     * @param batch       本次几路，落在 {@code [1, maxBatch]}
     * @param inStride    {@code 1} = 实数输入，{@code 2} = 交错复数
     */
    public void forward(int inputBuffer, int batch, int inStride) {
        checkUsable();
        if (inStride != 1 && inStride != 2) {
            // 不默认成 2：猜错了就是静默读错数据。
            throw new IllegalArgumentException(
                    "inStride 必须是 1（实数）或 2（交错复数），实际 " + inStride
                            + "。明确报错而不是猜一个默认值——猜错是静默读错数据。");
        }
        execute(inputBuffer, batch, inStride, false);
    }

    /**
     * 逆向变换：复数谱 → 复数信号（末尾乘 {@code 1/n}）。
     *
     * @param inputBuffer 输入 SSBO（交错复数，通道优先）
     * @param batch       本次几路，落在 {@code [1, maxBatch]}
     */
    public void inverse(int inputBuffer, int batch) {
        checkUsable();
        execute(inputBuffer, batch, 2, true);
    }

    private void execute(int inputBuffer, int batch, int inStride, boolean inverse) {
        if (batch < 1 || batch > maxBatch) {
            throw new IllegalArgumentException(
                    "batch 必须落在 [1, " + maxBatch + "]，实际 " + batch
                            + "。超出会写穿输出缓冲的末尾（GL 不报错）。");
        }
        shader.use();
        shader.setUniform("u_N", n);
        shader.setUniform("u_InStride", inStride);
        shader.setUniform("u_Inverse", inverse ? 1 : 0);
        shader.setUniform("u_Scale", scaleFor(n, inverse));
        gl.bindBufferBase(0, inputBuffer);
        gl.bindBufferBase(1, outputBuffer);
        shader.dispatch(batch, 1, 1);
        shader.memoryBarrier();
        // ★ 进来时动过的 GL 状态，出去前自己收尾（解绑成 0 是中性状态）。
        gl.bindBufferBase(0, 0);
        gl.bindBufferBase(1, 0);
        shader.unuse();
    }

    private void checkUsable() {
        if (disposed) {
            throw new IllegalStateException("ComplexFft 已释放");
        }
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
