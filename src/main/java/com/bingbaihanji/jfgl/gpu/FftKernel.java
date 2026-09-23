package com.bingbaihanji.jfgl.gpu;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.util.Disposable;

/**
 * GPU 上的 FFT：<strong>一次 dispatch 干完全部</strong>
 * （取数 → 加窗 → 位反转 → log2(N) 级蝶形 → 幅度）。
 *
 * <h2>三个设计决定，都来自 {@code GPUFFT} 实测出来的缺陷</h2>
 * <ol>
 *   <li><strong>位反转是着色器内部的第一步</strong>，不依赖任何外部步骤。
 *       {@code GPUFFT} 算了位反转索引却从没引用过它——DIT 蝶形要求输入先位反转，
 *       所以它的输出等于 {@code DFT(输入按位反转)}，峰值落在错误的 bin 上。</li>
 *   <li><strong>所有 uniform 声明成 {@code int}</strong>，与
 *       {@code ShaderProgram.setUniform(String,int)} 的 {@code glUniform1i} 配对。
 *       {@code GPUFFT} 声明成 {@code uint}，于是 {@code GL_INVALID_OPERATION}
 *       <strong>且值不生效</strong> → {@code u_N} 恒为 0 → 每次都提前返回，
 *       输出逐位等于输入。</li>
 *   <li><strong>不许用 GLSL 保留字当标识符</strong>。{@code GPUFFT} 用了
 *       {@code half}，着色器编译不过——<strong>它从未成功运行过一次</strong>。</li>
 * </ol>
 *
 * <h2>为什么是单个 workgroup</h2>
 * <p>全部在 shared memory 里做，{@code barrier()} 分隔各级。好处是位反转天然发生在
 * 着色器内部、且免去 {@code log2(N)} 次 dispatch 与内存屏障的 CPU 开销。
 * <b>代价是 N 有上限</b>：shared memory 要放 {@code 2×N} 个 float，
 * N=4096 时是 32 KB（安全），N=8192 就是 64 KB（超了）。<b>所以上限是 4096。</b>
 *
 * <h2>输入是环形缓冲</h2>
 * <p>时域样本存在 ② 的环形缓冲里，{@code N} 个样本可能跨过环的接缝，
 * 所以取数要 {@code & (cap-1)} 取模。<strong>这与 ② 的"槽位 0 镜像"是同一类问题</strong>
 * ——那里没处理，产出过一段"掉到 0 的假信号"。
 *
 * <h2>全部走 {@link GLAbstraction}</h2>
 * <p>不静态导入 LWJGL 的 GL 函数：那样会让 SSBO 那几个方法的正确性
 * <strong>失去唯一的执行验证</strong>（见 Task 1 的复核结论）。
 */
public final class FftKernel implements Disposable {

    /** 线程组大小。同时是 shared memory 里数组的长度上限。 */
    private static final int LOCAL_SIZE = 1024;

    /** 本核支持的最大变换长度。由 shared memory 决定（2×MAX_N 个 float），见类文档。 */
    public static final int MAX_N = 4096;

    /** 本核支持的最小变换长度。 */
    public static final int MIN_N = 256;

    /**
     * 着色器源码模板。
     *
     * <p><strong>两个占位符由上面的常量填入</strong>——这是刻意的：共享内存数组的长度
     * 如果与 {@link #MAX_N} 各写一个字面量，把 {@code MAX_N} 抬大就会让着色器
     * <strong>越界写共享内存</strong>，而驱动<strong>既不报错也不崩</strong>
     * （实测：这类越界只表现为数值静默错）。<strong>让它们只能从同一个源来。</strong>
     *
     * <p>{@code %d} 依次是：workgroup 大小（{@link #LOCAL_SIZE}）、
     * shared 数组长度（{@link #MAX_N}）、shared 数组长度（{@link #MAX_N}）。
     *
     * <p>着色器内部一概不再出现字面量 {@code 1024}：三处循环的步长走
     * {@code gl_WorkGroupSize.x}，于是线程组大小只有
     * {@code layout(local_size_x = ...)} 这<strong>一个</strong>来源。
     *
     * <h2>⚠️ 往这份模板里写 GLSL 时的两个坑</h2>
     * <ul>
     *   <li><strong>GLSL 的取模 {@code %} 必须写成 {@code %%}</strong>：
     *       模板要走 {@link String#formatted}，一个光秃秃的 {@code %}
     *       会被当成格式符。实测踩过：{@code k % halfLen} 让
     *       {@code shaderSource()} 抛 {@code UnknownFormatConversionException:
     *       Conversion = h}，<strong>着色器一个字符都没提交给驱动</strong>。
     *       好消息是这条路是<strong>响亮</strong>失败的（抛异常），不是静默的。</li>
     *   <li>不要在 GLSL 注释里写 {@code %} 或 {@code %d} 这类序列——它们同样会被格式化
     *       吃掉（{@code %d} 还会<strong>消耗一个占位符实参</strong>）。</li>
     * </ul>
     */
    private static final String SHADER_TEMPLATE = """
            #version 430
            layout(local_size_x = %d) in;

            layout(std430, binding = 0) readonly  buffer InputBuffer  { float inY[]; };
            layout(std430, binding = 1) writeonly buffer OutputBuffer { float outMag[]; };

            // 全部是 int —— setUniform 走 glUniform1i。声明成 uint 会静默失效。
            uniform int   u_N;            // 变换长度（2 的幂，256..4096）
            uniform int   u_RingCapacity; // 环形缓冲容量（2 的幂）
            uniform int   u_RingStart;    // 第一个样本在环里的槽位
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
                int tid = int(gl_LocalInvocationID.x);
                int n   = u_N;
                int cap = u_RingCapacity;

                int logN = 0;
                for (int t = n; t > 1; t >>= 1) logN++;

                // ① 取数 + 加窗 + 位反转，一次做完。
                //    bitfieldReverse 反转全部 32 位，右移掉高位即得 logN 位的反转。
                //
                //    ⚠️ 移位必须在 **uint 域**里做完再转 int。
                //    写成 `int(bitfieldReverse(...)) >> (32 - logN)` 是错的：
                //    反转之后**最高位几乎总是 1**（i 的最低位变成了最高位），
                //    转成 int 就是负数，而 GLSL 对**有符号**左操作数的 >> 是**算术右移**
                //    （符号扩展）。例如 i=1 时得到 0xFFFFFC00 = **-1024** 而不是 1024，
                //    于是 sRe[rev] 用一个**负下标**写共享内存——那是**越界写**，
                //    驱动可能崩、也可能悄悄写坏别处。
                //    步长走 gl_WorkGroupSize.x（= local_size_x 的声明值），不写 1024 字面量。
                //    ⚠️ 它是 uint，GLSL 不做 int↔uint 的隐式转换，必须显式 int(...)。
                for (int i = tid; i < n; i += int(gl_WorkGroupSize.x)) {
                    int src = (u_RingStart + i) & (cap - 1);
                    int rev = int(bitfieldReverse(uint(i)) >> uint(32 - logN));
                    sRe[rev] = inY[src] * windowAt(i);
                    sIm[rev] = 0.0;
                }
                barrier();

                // ② log2(N) 级蝶形（DIT，输入已位反转）
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

                // ③ 幅度（半谱，含 DC 与 Nyquist）
                for (int k = tid; k <= n / 2; k += int(gl_WorkGroupSize.x)) {
                    float re = sRe[k];
                    float im = sIm[k];
                    outMag[k] = sqrt(re * re + im * im) * u_Scale;
                }
            }
            """;

    /** 由常量生成的着色器源码；构造 {@link FftKernel} 时才会求值。 */
    private static String shaderSource() {
        return SHADER_TEMPLATE.formatted(LOCAL_SIZE, MAX_N, MAX_N);
    }

    private final GLAbstraction gl;
    private final ComputeShader shader;
    private final int outputBuffer;
    private final int n;

    /**
     * @param gl           GL 抽象层
     * @param n            变换长度，<strong>必须是 2 的幂且落在 [256, 4096]</strong>
     * @param ringCapacity 源环形缓冲的容量，必须是 2 的幂
     * @throws IllegalArgumentException 参数不合法
     */
    public FftKernel(GLAbstraction gl, int n, int ringCapacity) {
        if (n < MIN_N || n > MAX_N || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "FFT 长度必须是 2 的幂且落在 [" + MIN_N + ", " + MAX_N + "]：实际 " + n
                            + "。上限由 shared memory 决定（2×N 个 float）。");
        }
        if (ringCapacity <= 0 || (ringCapacity & (ringCapacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + ringCapacity);
        }
        this.gl = gl;
        this.n = n;
        this.shader = new ComputeShader(shaderSource());
        this.outputBuffer = gl.createBuffer();
        gl.bindShaderStorageBuffer(outputBuffer);
        gl.allocateBufferStorage((long) (n / 2 + 1) * Float.BYTES);
        gl.bindShaderStorageBuffer(0);
    }

    /** FFT 输出的 bin 数（半谱，含 DC 与 Nyquist）。 */
    public int binCount() {
        return n / 2 + 1;
    }

    /** 输出缓冲的名字。它同时可以被绑成顶点缓冲，供实例化绘制读走。 */
    public int outputBufferId() {
        return outputBuffer;
    }

    /**
     * 对环形缓冲里从 {@code ringStart} 起的 {@code n} 个样本做 FFT。
     *
     * @param inputBuffer  源环形缓冲的名字（② 的 {@code SeriesBuffer} 的 VBO）
     * @param ringCapacity 环容量
     * @param ringStart    第一个样本在环里的槽位
     * @param window       窗函数
     */
    public void execute(int inputBuffer, int ringCapacity, int ringStart, FftWindow window) {
        shader.use();
        shader.setUniform("u_N", n);
        shader.setUniform("u_RingCapacity", ringCapacity);
        shader.setUniform("u_RingStart", ringStart);
        // 与 FftWindow 的枚举顺序绑定 —— FftWindowTest 里有一条断言钉着这个顺序。
        shader.setUniform("u_WindowKind", window.ordinal());
        // 半谱的归一化：单位幅度余弦的谱峰是 N/2，所以要 ×2/N 才能读回 1.0；
        // 再乘窗补偿，于是换窗不改变读数。
        shader.setUniform("u_Scale", (float) (2.0 / n * window.compensation(n)));
        gl.bindBufferBase(0, inputBuffer);
        gl.bindBufferBase(1, outputBuffer);
        shader.dispatch(1, 1, 1);
        shader.memoryBarrier();
        shader.unuse();
    }

    @Override
    public void dispose() {
        shader.dispose();
        gl.deleteBuffer(outputBuffer);
    }
}
