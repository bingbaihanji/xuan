package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import java.util.Arrays;

/**
 * GPU 上的 FFT：一次 dispatch 干完全部（取数 → 加窗 → 位反转 → log2(N) 级蝶形 → 幅度）。
 *
 * <p>三条设计决定都来自 {@code GPUFFT} 实测出来的缺陷，不要违反：
 * <ul>
 *   <li>位反转必须是着色器内部的第一步。{@code GPUFFT} 算了位反转索引却从没引用过它，
 *       而 DIT 蝶形要求输入先位反转，于是它的输出等于 {@code DFT(输入按位反转)}，峰值落在错误的 bin 上。</li>
 *   <li>uniform 一律声明 {@code int}，与 {@code glUniform1i} 配对。{@code GPUFFT} 声明成 {@code uint}，
 *       得到 {@code GL_INVALID_OPERATION} 且值不生效 → {@code u_N} 恒为 0 → 每次都提前返回，输出逐位等于输入。</li>
 *   <li>不许用 GLSL 保留字当标识符。{@code GPUFFT} 用了 {@code half}，着色器编译不过——它从未成功运行过一次。</li>
 * </ul>
 *
 * <p>单个 workgroup、全部在 shared memory 里做，代价是 N 有上限：数组按 {@code MAX_N} 静态分配，
 * 恒占 {@code 2×}{@link #MAX_N}×4 = 32 KB（与 {@code n} 无关）。{@code MAX_N} 抬到 8192 就是 64 KB，
 * 超过驱动 48 KB 的默认上限（实测以 {@code C5041} 链接失败）。
 *
 * <p>时域样本存在 ② 的环形缓冲里，{@code N} 个样本可能跨过接缝，取数要 {@code & (cap-1)} 取模。
 * 这与 ② 的"槽位 0 镜像"是同一类问题——那里没处理，产出过一段"掉到 0 的假信号"。
 *
 * <p>全部走 {@link GLAbstraction}，不静态导入 LWJGL 的 GL 函数：那样会让 SSBO 那几个方法
 * 失去唯一的执行验证。
 */
public final class FftKernel implements Disposable {

    /** 本核支持的最大变换长度。由 shared memory 决定（2×MAX_N 个 float），见类文档。 */
    public static final int MAX_N = 4096;

    /** 本核支持的最小变换长度。 */
    public static final int MIN_N = 256;

    /**
     * 线程组大小。它与共享内存数组的长度（{@code shared float sRe[MAX_N]}，由 {@link #MAX_N}
     * 决定）无关，唯一与它绑定的是着色器里的 {@code layout(local_size_x = ...)}；
     * 三处循环的步长走 {@code gl_WorkGroupSize.x}，由布局推导，不再各写一份。
     */
    private static final int LOCAL_SIZE = 1024;

    /**
     * 着色器源码模板。三个 {@code %d} 占位符由上面的常量填入，依次是 workgroup 大小
     * （{@link #LOCAL_SIZE}）、shared 数组长度、shared 数组长度（后两者都是 {@link #MAX_N}）。
     *
     * <p>共享内存数组的长度必须与 {@link #MAX_N} 同源：若各写一个字面量，把 {@code MAX_N}
     * 抬大就会让着色器越界写共享内存，而驱动既不报错也不崩（实测：这类越界只表现为数值静默错）。
     * 着色器内部一概不出现字面量 {@code 1024}，三处循环的步长走 {@code gl_WorkGroupSize.x}。
     *
     * <p>往这份模板里写 GLSL 的两个坑：
     * <ul>
     *   <li>取模 {@code %} 必须写成 {@code %%}：模板要走 {@link String#formatted}，
     *       光秃秃的 {@code %} 会被当成格式符。实测 {@code k % halfLen} 让 {@code shaderSource()}
     *       抛 {@code UnknownFormatConversionException: Conversion = h}，着色器一个字符都没提交给驱动
     *       （这条路是响亮失败，不是静默）。</li>
     *   <li>不要在 GLSL 注释里写 {@code %} 或 {@code %d} 这类序列——同样会被格式化吃掉
     *       （{@code %d} 还会消耗一个占位符实参）。</li>
     * </ul>
     */
    private static final String SHADER_TEMPLATE = """
                                                  #version 430
                                                  layout(local_size_x = %d) in;
                                                  
                                                  layout(std430, binding = 0) readonly  buffer InputBuffer  { float inY[]; };
                                                  layout(std430, binding = 1) writeonly buffer OutputBuffer { float outMag[]; };
                                                  
                                                  // 全部 uniform 是 int —— setUniform 走 glUniform1i，声明成 uint 会静默失效。
                                                  // u_N：变换长度（2 的幂，范围见 Java 侧 MIN_N/MAX_N，刻意不写数字）
                                                  uniform int   u_N;
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
                                                      // 注意：移位必须在 uint 域里做完再转 int。bitfieldReverse 后
                                                      // 最高位几乎总是 1（i 的最低位变成最高位），而 int(...) >> 是
                                                      // 算术右移，i=1 时得到 0xFFFFFC00 = -1024 而不是 1024，
                                                      // sRe[rev] 就用负下标越界写共享内存。步长走 gl_WorkGroupSize.x
                                                      // （它是 uint，GLSL 不做隐式转换，必须显式 int(...)），不写 1024。
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

    private final GLAbstraction gl;

    private final ComputeShader shader;

    private final int outputBuffer;

    private final int n;

    /**
     * 源环形缓冲的容量。构造器收下、校验并存住，之后每次 {@link #execute} 都用它
     * ——校验与使用是同一个值。
     */
    private final int ringCapacity;

    /**
     * 每个窗的 {@code u_Scale}（{@code 2/n × 窗补偿}），按 {@link FftWindow#ordinal()} 索引。
     *
     * <p>刻意记忆化：{@link FftWindow#compensation(int)} 是 O(N) 求和（BH 窗每个系数 3 次
     * {@code cos}），n=4096 时约 1.2 万次 {@code cos}——而 {@code n} 在构造时就定死，
     * 这个值只与（窗, n）有关、是个常量，每帧重算是白烧 CPU 预算。
     *
     * <p>{@code NaN} 当"尚未计算"的哨兵（{@code u_Scale} 恒为正，不会混淆）。
     * {@code compensation} 抛异常时不写缓存，下次照旧重新求值并再次抛——不把失败也缓存起来。
     */
    private final float[] scaleByWindow = new float[FftWindow.values().length];

    /** {@link #dispose()} 的幂等标志——{@code deleteBuffer} 只许删一次。 */
    private boolean disposed;

    /**
     * @param gl           GL 抽象层
     * @param n            变换长度，必须是 2 的幂且落在 [{@link #MIN_N}, {@link #MAX_N}]
     * @param ringCapacity 源环形缓冲的容量，必须是 2 的幂
     * @throws IllegalArgumentException 参数不合法
     */
    public FftKernel(GLAbstraction gl, int n, int ringCapacity) {
        if (n < MIN_N || n > MAX_N || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "FFT 长度必须是 2 的幂且落在 [" + MIN_N + ", " + MAX_N + "]：实际 " + n
                            + "。上限由 shared memory 决定（2×MAX_N 个 float）。");
        }
        if (ringCapacity <= 0 || (ringCapacity & (ringCapacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + ringCapacity);
        }
        Arrays.fill(scaleByWindow, Float.NaN);
        this.gl = gl;
        this.n = n;
        this.ringCapacity = ringCapacity;
        this.shader = new ComputeShader(shaderSource());
        // 从这里往下都是 GL 调用。构造没完成时 dispose() 永远不会被调用，这条路径必须自己收尾，
        // 别留半截 program / buffer 在上下文里。（今天 LwjglGLAbstraction 直通、不查 GL 错误，
        // 所以实际不可达；只要有人给抽象层加上错误检查，它立刻就变成真的。）
        int buf = 0;
        try {
            buf = gl.createBuffer();
            gl.bindShaderStorageBuffer(buf);
            gl.allocateBufferStorage((long) (n / 2 + 1) * Float.BYTES);
        } catch (RuntimeException e) {
            shader.dispose();
            if (buf != 0) {
                gl.deleteBuffer(buf);
            }
            throw e;
        }
        // 解绑放在 try 之外：写进 finally 的话，它自己抛出的异常会顶掉上面那个原始异常，
        // 让"为什么构造失败"变成一句谎话。
        gl.bindShaderStorageBuffer(0);
        this.outputBuffer = buf;
    }

    /**
     * 由常量生成的着色器源码；构造 {@link FftKernel} 时才会求值。
     *
     * <p>包级可见是刻意的：纯字符串运算、不需要 GL 上下文，按本项目判据（"能不能脱离 GL
     * 上下文跑测试"）它该进单测——见 {@code FftKernelTest}。三个坑（裸 {@code %} 被当格式符、
     * 共享数组长度与 {@link #MAX_N} 脱钩、步长写回字面量）都属于不看字符串就发现不了的那一类。
     */
    static String shaderSource() {
        return SHADER_TEMPLATE.formatted(LOCAL_SIZE, MAX_N, MAX_N);
    }

    /** {@link #LOCAL_SIZE} 的只读访问器，供同包测试断言布局与步长的一致性。 */
    static int localSize() {
        return LOCAL_SIZE;
    }

    /**
     * {@link #SHADER_TEMPLATE} 的只读访问器——测试必须能看见"格式化之前"的那份。
     *
     * <p>{@code shaderSource()} 返回的是格式化之后的字符串，两种缺陷在那里看不见：
     * 模板里写死的 {@code sRe[4096]} 与 {@code sRe[%d]} 在 {@code MAX_N == 4096} 时生成
     * 一模一样的串；{@code %n} 这类格式符会被 {@code formatted} 吃掉（换成换行符），
     * 生成串里根本不留下它。实测：只断言生成串的话，这两条变异都全绿。
     */
    static String shaderTemplate() {
        return SHADER_TEMPLATE;
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
     * <p>环容量不在参数里：它由构造器收下、校验并存成字段供每次调用使用——校验与使用是同一个值。
     * 早先的版本让构造器校验一个从不被使用的值、这里收下一个从不被校验的值：cap 偏小则取到错的
     * 槽位（谱形完全正常而峰位偏），cap 偏大则 {@code & (cap-1)} 越过 SSBO 末尾读。两种都静默。
     *
     * @param inputBuffer 源环形缓冲的名字（② 的 {@code SeriesBuffer} 的 VBO）
     * @param ringStart   第一个样本在环里的槽位
     * @param window      窗函数
     */
    public void execute(int inputBuffer, int ringStart, FftWindow window) {
        shader.use();
        shader.setUniform("u_N", n);
        shader.setUniform("u_RingCapacity", ringCapacity);
        shader.setUniform("u_RingStart", ringStart);
        // 与 FftWindow 的枚举顺序绑定 —— FftWindowTest 里有一条断言钉着这个顺序。
        shader.setUniform("u_WindowKind", window.ordinal());
        shader.setUniform("u_Scale", scaleFor(window));
        gl.bindBufferBase(0, inputBuffer);
        gl.bindBufferBase(1, outputBuffer);
        shader.dispatch(1, 1, 1);
        shader.memoryBarrier();
        // 进来时动过的 GL 状态，出去前自己收尾（② 的房规是渲染器自己负责；而 FftKernel 不在
        // RenderBatch.submit 里，没有"进中性、出来还原"那套可依赖）。解绑成 0 是中性状态，
        // 不是"还原成调用前那一份"——抽象层没有查询当前绑定的方法，② 自己也是解绑而非还原。
        gl.bindBufferBase(0, 0);
        gl.bindBufferBase(1, 0);
        shader.unuse();
    }

    /** 该窗在长度 {@code n} 上的缩放系数，按 ordinal 记忆化（见 {@link #scaleByWindow}）。 */
    private float scaleFor(FftWindow window) {
        int i = window.ordinal();
        float scale = scaleByWindow[i];
        if (Float.isNaN(scale)) {
            // 半谱归一化：单位幅度余弦的谱峰是 N/2，要 ×2/N 才能读回 1.0；再乘窗补偿，
            // 于是换窗不改变读数。
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
