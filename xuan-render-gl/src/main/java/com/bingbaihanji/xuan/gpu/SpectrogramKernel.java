package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

/**
 * 声谱图的一列：把 {@link FftKernel} 算出的半谱映射成颜色，写进一张纹理的第
 * {@code headColumn} 列（时间 × 频率 × 颜色里的那根时间轴）。
 *
 * <p>三条设计决定：
 * <ul>
 *   <li><strong>读 FFT 的输出缓冲，不重算 FFT</strong>。重算等于让"谱怎么算"有第二份实现，
 *       而两份实现分家时画面上两条谱各自都"看着正常"——本仓库为这类漂移付过代价。</li>
 *   <li><strong>上色只走 LUT 纹理</strong>。颜色公式住在 {@code chart/ColorMapping} 里，
 *       CPU 侧生成 1×256 的 RGBA 色带上传；着色器里只做一次
 *       {@code texture(u_Lut, vec2(t, 0.5))}。<strong>在着色器里另抄一份颜色公式就会分家</strong>，
 *       而分家的表现是"两个视图的配色对不上"，不是报错。</li>
 *   <li><strong>行序只在着色器里翻转一次</strong>（见 {@link #SHADER_TEMPLATE} 里那一句
 *       {@code row = u_BinCount - 1 - k}）：于是纹理第 0 行就是画面上方那一行、是最高的频率，
 *       绘制侧只要按"第 0 行在上"贴图，频率轴自然向上。<strong>这是全项目唯一的频率轴翻转点</strong>
 *       ——绘制侧再翻一次就会上下颠倒，而画面看上去只是一张"形状挺像"的声谱图。</li>
 * </ul>
 *
 * <p>输出纹理由本类持有（{@code GL_RGBA8}，{@code W × (n/2+1)}，内容全部由 compute 填）。
 * {@code W} 必须是 2 的幂：环形的列号由调用方按 {@code & (W-1)} 维护，
 * 不是 2 的幂时那个掩码会指到别的列上——而写错列只会让时间轴错位，不报错。
 *
 * <p>输出纹理的过滤固定 {@code GL_NEAREST}（见
 * {@link GLAbstraction#createImageTexture}）：声谱图的每一列是一个独立的时间窗，
 * 相邻列之间是真边界，插值会把它们糊成一片。
 */
public final class SpectrogramKernel implements Disposable {

    /** dB 下限的默认值。低于它的幅度全部压成同一个颜色。 */
    public static final float DEFAULT_DB_FLOOR = -120f;

    /**
     * 线程组大小。本核<strong>不用 shared memory</strong>（一次只做"读一个幅度、查一次
     * 色带、写一个像素"），所以这个数与内存无关，只是 workgroup 的尺寸；
     * 它唯一必须与之一致的地方是着色器的 {@code layout(local_size_x = ...)}。
     */
    private static final int LOCAL_SIZE = 64;

    /**
     * 着色器源码模板。{@code %d} 只有一个，填 {@link #LOCAL_SIZE}。
     *
     * <p>两个坑与 {@link FftKernel} 相同：GLSL 的取模 {@code %} 要写成 {@code %%}
     * （本核当前一处取模都没有，加的时候别忘）；注释里也别写 {@code %} 或 {@code %d}。
     */
    private static final String SHADER_TEMPLATE = """
            #version 430
            layout(local_size_x = %d) in;

            // 输入就是 FftKernel 的输出缓冲：一次 dispatch 只处理一个 hop 的那 n/2+1 个
            // 线性幅度，所以下标从 0 起，不重算 FFT。
            layout(std430, binding = 0) readonly buffer Spectrum { float mag[]; };

            // image unit 与纹理单元是两套命名空间，两者都取 0 不冲突。
            layout(rgba8, binding = 0) uniform writeonly image2D u_Spectrogram;

            uniform sampler2D u_Lut;      // 1×256 的色带，由 chart/ColorMapping 在 CPU 侧生成
            uniform int   u_ImageWidth;   // 纹理列数 W（2 的幂）
            uniform int   u_HeadColumn;   // 本 hop 写第几列
            uniform int   u_BinCount;     // 半谱的 bin 数 = n/2 + 1
            uniform float u_DbFloor;      // dB 下限，默认 -120

            // 注意：不要用 half 当变量名 —— 它是 GLSL 保留字。
            void main() {
                int k = int(gl_GlobalInvocationID.x);   // 一个 invocation 一个 bin

                // 越界写 image 是未定义行为、不报错，所以两个坐标都再挡一次。
                if (k >= u_BinCount) return;
                if (u_HeadColumn < 0 || u_HeadColumn >= u_ImageWidth) return;

                float amp = mag[k];
                // 20*log10(amp)：GLSL 的 log 是自然对数，这里用 log2 换算，
                // 免得"底数到底是多少"变成一句读代码要去猜的话。
                float db = 20.0 * log2(amp + 1e-12) / log2(10.0);
                float t  = clamp((db - u_DbFloor) / (0.0 - u_DbFloor), 0.0, 1.0);

                // 上色只走 LUT：颜色公式在 chart/ColorMapping 里只此一份，
                // 着色器里再抄一份就会分家。
                //
                // 行序：这里是全项目唯一的频率翻转点——纹理第 0 行是画面上方那一行，
                // 取最高频（Nyquist）。绘制侧不要再翻，按"第 0 行在上"贴图即可。
                int row = u_BinCount - 1 - k;
                imageStore(u_Spectrogram, ivec2(u_HeadColumn, row), texture(u_Lut, vec2(t, 0.5)));
            }
            """;

    private final GLAbstraction gl;

    private final ComputeShader shader;

    /** 变换长度。它同时决定输出纹理的高度（{@code n/2+1}）。 */
    private final int n;

    /** 输出纹理的列数 W（2 的幂），即环形缓冲能记住多少个 hop。 */
    private final int imageWidth;

    /** 输出纹理：{@code W × (n/2+1)}，全部由 compute 的 {@code imageStore} 填。 */
    private final int imageTexture;

    private float dbFloor = DEFAULT_DB_FLOOR;

    /** {@link #dispose()} 的幂等标志——{@code deleteTexture} 与 {@code glDeleteProgram} 只许走一次。 */
    private boolean disposed;

    /**
     * @param gl         GL 抽象层
     * @param n          变换长度，必须是 2 的幂且落在 [{@link FftKernel#MIN_N}, {@link FftKernel#MAX_N}]
     * @param imageWidth 输出纹理的列数 W，必须是 2 的幂
     * @throws IllegalArgumentException 参数不合法
     */
    public SpectrogramKernel(GLAbstraction gl, int n, int imageWidth) {
        // 长度范围与 FftKernel 同源：本核消费它的输出，各写一份就变成"FFT 支持多长"有两个说法。
        if (n < FftKernel.MIN_N || n > FftKernel.MAX_N || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "变换长度必须是 2 的幂且落在 [" + FftKernel.MIN_N + ", " + FftKernel.MAX_N
                            + "]：实际 " + n + "（与 FftKernel 同源，本核消费它的输出）");
        }
        if (imageWidth <= 0 || (imageWidth & (imageWidth - 1)) != 0) {
            throw new IllegalArgumentException(
                    "列数 W 必须是 2 的幂，实际 " + imageWidth
                            + "。环形的列号靠 & (W-1) 维护，W 不是 2 的幂时那个掩码会指到别的列上。");
        }
        this.gl = gl;
        this.n = n;
        this.imageWidth = imageWidth;
        this.shader = new ComputeShader(shaderSource());
        // 从这里往下是 GL 调用。构造没完成时 dispose() 永远不会被调用，这条路径必须自己收尾，
        // 别留半截 program / texture 在上下文里。
        int texture = 0;
        try {
            texture = gl.createImageTexture(imageWidth, binCount());
        } catch (RuntimeException e) {
            shader.dispose();
            if (texture != 0) {
                gl.deleteTexture(texture);
            }
            throw e;
        }
        this.imageTexture = texture;
    }

    /**
     * 由常量生成的着色器源码；构造时才会求值。
     *
     * <p>包级可见是刻意的（理由见 {@link FftKernel#shaderSource()}）：纯字符串运算、
     * 不需要 GL 上下文，按本项目判据它该进单测。
     */
    static String shaderSource() {
        return SHADER_TEMPLATE.formatted(LOCAL_SIZE);
    }

    /** {@link #LOCAL_SIZE} 的只读访问器，供同包测试断言布局与 dispatch 的一致性。 */
    static int localSize() {
        return LOCAL_SIZE;
    }

    /** {@link #SHADER_TEMPLATE} 的只读访问器——测试必须能看见"格式化之前"的那一份。 */
    static String shaderTemplate() {
        return SHADER_TEMPLATE;
    }

    /** 半谱的 bin 数，也就是输出纹理的高度。 */
    public int binCount() {
        return n / 2 + 1;
    }

    /** 输出纹理的列数 W。 */
    public int imageWidth() {
        return imageWidth;
    }

    /** 输出纹理的名字。绘制与回读都用它。 */
    public int imageTextureId() {
        return imageTexture;
    }

    public float dbFloor() {
        return dbFloor;
    }

    /**
     * 设置 dB 下限。
     *
     * <p>必须为负的有限值：下限取 0 时 {@code (0 - u_DbFloor)} 这个分母是 0，
     * 归一化会变成 NaN/Inf，而 {@code texture()} 采到 NaN 坐标的颜色是未定义的
     * ——画面上是一整片没有规律的颜色，不报错。
     *
     * @param dbFloor dB 下限（dBFS）
     * @throws IllegalArgumentException 非负或非有限时
     */
    public void setDbFloor(float dbFloor) {
        if (!(dbFloor < 0f) || !Float.isFinite(dbFloor)) {
            throw new IllegalArgumentException("dB 下限必须是负的有限值，实际 " + dbFloor);
        }
        this.dbFloor = dbFloor;
    }

    /**
     * 把一个 hop 的半谱写成输出纹理的第 {@code headColumn} 列。
     *
     * <p>{@code headColumn} 由调用方维护（{@code 每 hop +1，再 & (W-1)}）：本核<strong>不记
     * 状态</strong>，所以"这一列写到哪儿"只有一个来源，重放/回退都由调用方说了算。
     *
     * @param spectrumBuffer {@link FftKernel#outputBufferId()}，那一 hop 的半谱
     * @param lutTexture     1×256 的色带纹理（{@code chart/ColorMapping.toLut()} 的内容上传而来）
     * @param headColumn     本 hop 写第几列，落在 {@code [0, W)}
     * @throws IllegalStateException    已释放
     * @throws IllegalArgumentException headColumn 越界
     */
    public void execute(int spectrumBuffer, int lutTexture, int headColumn) {
        if (disposed) {
            throw new IllegalStateException("SpectrogramKernel 已释放");
        }
        if (headColumn < 0 || headColumn >= imageWidth) {
            throw new IllegalArgumentException(
                    "headColumn 必须落在 [0, " + imageWidth + ") 内，实际 " + headColumn);
        }
        shader.use();
        shader.setUniform("u_ImageWidth", imageWidth);
        shader.setUniform("u_HeadColumn", headColumn);
        shader.setUniform("u_BinCount", binCount());
        shader.setUniform("u_DbFloor", dbFloor);
        // 采样器指向纹理单元 0；下面那一对调用就是把色带绑到那个单元上。
        shader.setUniform("u_Lut", 0);
        gl.bindBufferBase(0, spectrumBuffer);
        gl.bindImageTexture(0, imageTexture, GLAbstraction.IMAGE_ACCESS_WRITE_ONLY);
        // 纹理单元的绑定没有走抽象层（那一层没有这个方法，RenderBatch 也是直接调的）：
        // u_Lut 那个 uniform 里的 0 说的就是这一个单元。RenderBatch 每次绑定前都会
        // 自己设一次 GL_TEXTURE0，所以这里不必还原当前单元。
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, lutTexture);
        shader.dispatch((binCount() + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        // compute 写完 → 绘制或回读读走，中间这一次屏障是唯一的同步点（FftKernel 同款）。
        shader.memoryBarrier();
        glBindTexture(GL_TEXTURE_2D, 0);
        gl.bindImageTexture(0, 0, GLAbstraction.IMAGE_ACCESS_WRITE_ONLY);
        gl.bindBufferBase(0, 0);
        shader.unuse();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        shader.dispose();
        gl.deleteTexture(imageTexture);
    }
}
