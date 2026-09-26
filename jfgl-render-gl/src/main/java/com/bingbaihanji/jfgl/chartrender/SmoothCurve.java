package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.gl.GLAbstraction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 平滑曲线这一件事的<b>共享零件</b>：细分档位、角点数据、属性偏移、以及
 * "这一段实例里哪几段可以画成曲线"的算术。
 *
 * <h2>它存在的理由：折线族与面积图必须<b>逐字相同</b></h2>
 * <p>面积图的填充顶边与它的轮廓线是<b>同一条曲线</b>（轮廓线由折线路径画，见
 * {@code AreaSeriesRenderer}）。两处要是各算各的细分档位或各写一份角点布局，
 * 迟早会分叉——而分叉的表现是<b>填充的顶边与轮廓线错开半个像素</b>：
 * 沿曲线露出一条背景色的细缝，或者轮廓线浮在填充上方。那看起来只是"边有点毛"。
 * 所以这里的每一条算术都只有一份，两个渲染器都调它。
 *
 * <h2>为什么角点按"站位"存，而不是只存四个角</h2>
 * <p>一条曲线段要分成 K 小段画，每一小段都是一个四边形（上下两边各一对顶点），
 * 于是实例的顶点数变成 {@code 2 × (K+1)}；而<b>顶点数必须是每次 draw 的固定参数</b>
 * （一个系列一条 draw call），所以"站位参数 t"直接烘进角点属性：
 * {@code aCorner.x = i / K}（i = 0..K）。四档 K 各占一段连续区间，
 * CPU 按"一个样本摊多少像素"选一档、把 {@code (first, count)} 传给 draw。
 *
 * <p><b>K 全是 2 的幂</b>：{@code i / K} 于是都是精确的二进制小数，站位不会因为
 * 除法而偏一点——而这种"偏一点"的表现是曲线上每隔几像素一个看不见的台阶。
 *
 * <h2>为什么档位随"一个样本占多少像素"走，而不写死一个 K</h2>
 * <ul>
 *   <li>采样密（每样本不到 8 px）时用 K = 2：顶点数是普通折线的 1.5 倍，
 *       而不是 4.5 倍。百万点的曲线图不该为了一个看不见的差别付 4.5 倍的前端开销；
 *       但<b>也不退回"不细分"</b>——那会让开关静默地什么都不做（曲线与折线逐像素相同），
 *       而"设了没生效"比"效果差一点"难查得多。</li>
 *   <li>采样疏（放大之后一段几百像素）时用 K = 16：站位间距被压到 4 px 左右，
 *       曲线不会露出折线段的棱角。</li>
 * </ul>
 *
 * <h2>站位参数 t 与"哪一侧"共用 aCorner 一个 vec2</h2>
 * <p>普通路径的 {@code aCorner.x} 是"线段的哪一端（0/1）"，平滑路径的是"站位参数
 * （0..1）"——<b>两者在 0 与 1 处重合</b>，所以顶点着色器只需要一套代码：
 * 非平滑时它只会取到 0 与 1（顶点数 4），平滑时才会取到中间值。
 */
final class SmoothCurve {

    /** 细分档位下界。**不能是 1**：那样中间站一个都没有，曲线退化成弦（= 不生效）。 */
    static final int K_MIN = 2;

    /** 细分档位上界。16 档 = 每实例 34 个顶点。 */
    static final int K_MAX = 16;

    /** 站位之间希望不超过多少设备像素：决定了档位怎么随缩放走。 */
    private static final double STATION_PX = 4.0;

    /** 平滑站位的档位表（都是 2 的幂，见类文档）。 */
    private static final int[] TIERS = {K_MIN, 4, 8, K_MAX};

    /** 调用方那四个"普通角点"占的顶点数。 */
    private static final int PLAIN_VERTICES = 4;

    private SmoothCurve() {
    }

    /**
     * 按"一个样本摊多少设备像素"选细分档位。
     *
     * @param pxPerSample 一个样本在屏幕上占的横向像素数（{@code 绘图区宽 / 窗口跨度}）
     * @return {@link #K_MIN}..{@link #K_MAX} 之间的 2 的幂
     */
    static int subdivisionFor(double pxPerSample) {
        if (!(pxPerSample > 0)) {
            // NaN / 0 / 负数：窗口退化时渲染器本来就不会走到这里（段列表为空），
            // 但这条路径不能把 NaN 带进循环判断（NaN 与任何数比较都是 false，
            // 于是会静默地"选到最小档"——这里干脆写明）。
            return K_MIN;
        }
        int k = K_MIN;
        while (k < K_MAX && pxPerSample > k * STATION_PX) {
            k *= 2;
        }
        return k;
    }

    /**
     * 造角点数据：前 {@value #PLAIN_VERTICES} 个是调用方自己的"普通四角"，
     * 其后是四档平滑站位，每档 {@code 2 × (K+1)} 个顶点、按站位分组
     * （{@code (t, 0), (t, 1)}）——这条顺序就是 {@code GL_TRIANGLE_STRIP} 画一条带子所需的顺序。
     *
     * @param plainCorners 调用方的四个普通角点（{@code x, y} 依次排开，共 8 个 float）。
     *                     折线的 {@code x} 是"哪一端"、面积的是"哪一端"，
     *                     而两者的 {@code y} 都是"哪一侧"——平滑档位里
     *                     {@code x} 是站位参数 t、{@code y} 仍然是"哪一侧"，
     *                     于是同一份站位数据对两个渲染器都成立。
     */
    static float[] cornerData(float[] plainCorners) {
        if (plainCorners.length != PLAIN_VERTICES * 2) {
            throw new IllegalArgumentException(
                    "普通角点必须是 " + PLAIN_VERTICES + " 个顶点（" + (PLAIN_VERTICES * 2)
                            + " 个 float），实际 " + plainCorners.length);
        }
        int total = PLAIN_VERTICES;
        for (int k : TIERS) {
            total += 2 * (k + 1);
        }
        float[] out = new float[total * 2];
        System.arraycopy(plainCorners, 0, out, 0, plainCorners.length);
        int at = PLAIN_VERTICES * 2;
        for (int k : TIERS) {
            for (int i = 0; i <= k; i++) {
                float t = (float) i / k;
                out[at++] = t;
                out[at++] = 0f;
                out[at++] = t;
                out[at++] = 1f;
            }
        }
        return out;
    }

    /** 某一档站位数据在角点缓冲里的起始顶点下标（{@code glDrawArrays*} 的 {@code first}）。 */
    static int firstVertex(int subdivision) {
        int first = PLAIN_VERTICES;
        for (int k : TIERS) {
            if (k == subdivision) {
                return first;
            }
            first += 2 * (k + 1);
        }
        throw new IllegalArgumentException("细分档位必须是 " + java.util.Arrays.toString(TIERS)
                + " 之一，实际 " + subdivision);
    }

    /** 某一档站位数据的顶点数（{@code glDrawArrays*} 的 {@code count}）。 */
    static int vertexCount(int subdivision) {
        for (int k : TIERS) {
            if (k == subdivision) {
                return 2 * (k + 1);
            }
        }
        throw new IllegalArgumentException("细分档位必须是 " + java.util.Arrays.toString(TIERS)
                + " 之一，实际 " + subdivision);
    }

    /**
     * 配置数据侧的四个实例属性（{@code aY0 / aY1 / aYm1 / aY2}）。
     *
     * <h2>★ 偏移全部由布局算出来，而且必须<b>恒在缓冲内</b></h2>
     * <p>平滑布局的前置余量把槽位 {@code s} 挪到字节 {@code 4(s+1)}，于是四个控制点的
     * 偏移是 {@code 0 / 4 / 8 / 12}（差 4 字节，见 {@link SeriesLayout}）。
     *
     * <p><b>普通布局怎么办？</b>它没有前置余量，而"前一个样本"的偏移会是
     * {@code -4}——负偏移在 OpenGL 里不存在。所以那里把 {@code aYm1} 指到 {@code aY0}、
     * {@code aY2} 指到 {@code aY1}（<b>同一个值读两遍</b>）：{@code uSmooth = 0} 时
     * 着色器根本不读它们，但<b>属性抓取照样发生</b>，所以偏移必须落在缓冲里。
     * 这不是"多配了两个没用的属性"——是"不能让一次抓取跑出缓冲"。
     *
     * <p>调用方必须已经绑定本类的 VAO，并且会在之后重新绑定自己需要的 VBO
     * （本方法结束时把 {@code GL_ARRAY_BUFFER} 解绑，与改动前的那两份实现一致）。
     *
     * @param seriesVbo 系列的 GPU 常驻缓冲
     * @param biasBytes {@link SeriesLayout#byteOffsetOfSlot(int) byteOffsetOfSlot(0)}：
     *                  普通布局是 0，平滑布局是 4
     */
    static void configureDataAttributes(GLAbstraction gl, int seriesVbo, int biasBytes) {
        int neighborBack = biasBytes > 0 ? biasBytes - Float.BYTES : biasBytes;
        int neighborForward = biasBytes > 0 ? biasBytes + 2 * Float.BYTES : biasBytes + Float.BYTES;
        gl.bindVbo(seriesVbo);
        attribute(gl, 1, biasBytes);
        attribute(gl, 2, biasBytes + Float.BYTES);
        attribute(gl, 3, neighborBack);
        attribute(gl, 4, neighborForward);
        gl.bindVbo(0);
    }

    /**
     * 一个实例属性：每实例一个 float、步长 4 字节。
     *
     * <p>步长是 {@code 4} 而不是 {@code 8}：四个属性各自是"每实例一个 float"，
     * 由 {@code baseInstance} 把它们整体挪到环里正确的那一段上。写成 {@code 8}
     * 会让相邻实例间隔一个样本，画出来的波形<b>正好少一半的点</b>，
     * 而线条看起来仍然连贯——最难查的那种。
     */
    private static void attribute(GLAbstraction gl, int location, int offsetBytes) {
        glVertexAttribPointer(location, 1, GL_FLOAT, false, Float.BYTES, (long) offsetBytes);
        glEnableVertexAttribArray(location);
        gl.setVertexAttribDivisor(location, 1);
    }

    /**
     * 本次 draw 里<b>第一个可以画成曲线</b>的实例下标（含），后面直接交给
     * {@code uSmoothFrom}。
     *
     * <p>判据是"这个实例的数据下标落在 {@code [smoothableFirst, smoothableEnd)} 里"，
     * 再夹到本段的实例区间 {@code [0, instanceCount]} ——于是越界的实例（段的左端或右端
     * 伸进"控制点不全"的那一段）自动退回直线。
     *
     * <p><b>为什么夹到本段区间而不是传绝对下标</b>：绝对下标是 {@code long}、可以是几百万，
     * 传进着色器只能靠 float，而 float 在 1e6 上的精度约 0.06——足以让边界附近的一条实例
     * 被判错，而它读到的控制点正是<b>陈旧数据</b>（曲线弯向垃圾值，画面看起来正常）。
     * 夹到本段之后这个数恒在 {@code [0, 实例数]} 里，几百几千，float 表示得精确。
     *
     * @param firstDataIndex 本段第一个实例的数据下标（绝对号）
     * @param instanceCount  本段的实例数
     * @param smoothableFirst {@link SeriesBuffer#smoothableFirst()}
     */
    static float smoothFromInSegment(long firstDataIndex, int instanceCount, long smoothableFirst) {
        long relative = smoothableFirst - firstDataIndex;
        if (relative <= 0) {
            return 0f;
        }
        return (float) Math.min(relative, instanceCount);
    }

    /**
     * 本次 draw 里"最后一个可以画成曲线的实例"的<b>后一个</b>下标，交给 {@code uSmoothTo}。
     *
     * @param firstDataIndex 本段第一个实例的数据下标（绝对号）
     * @param instanceCount  本段的实例数
     * @param smoothableEnd  {@link SeriesBuffer#smoothableEnd()}（半开）
     */
    static float smoothToInSegment(long firstDataIndex, int instanceCount, long smoothableEnd) {
        long relative = smoothableEnd - firstDataIndex;
        if (relative <= 0) {
            return 0f;
        }
        return (float) Math.min(relative, instanceCount);
    }

    /**
     * 造一个只有角点数据的 VBO，内容见 {@link #cornerData}。
     *
     * <p>调用方负责 {@code gl.bindVao} 之后配置 location 0 的指针。
     */
    static int createCornerVbo(GLAbstraction gl, float[] plainCorners) {
        float[] data = cornerData(plainCorners);
        int vbo = gl.createVbo();
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float v : data) {
            buffer.putFloat(v);
        }
        buffer.flip();
        gl.bindVbo(vbo);
        gl.uploadVboBytes(buffer);
        gl.bindVbo(0);
        return vbo;
    }
}
