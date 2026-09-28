package com.bingbaihanji.xuan.chart;

import java.util.Arrays;

/**
 * 科学配色：一组色标 → 一张 1×256 的 RGBA 查找表。
 *
 * <h2>为什么要归一化成 LUT</h2>
 * <p>Xuan 已定的方向是<strong>所有 Paint 归一化为纹理</strong>（纯色 = 超白色纹理 + 顶点颜色，
 * 渐变 = 1×256 LUT）。热力图因此从 fxcharts 的"整幅逐像素重算颜色、每个像素 new 一个
 * Color 对象"变成"换一张纹理"——规格 §7 说这是收益最大的一条。
 *
 * <p>换配色 = 换纹理 = 一次 {@code glTexSubImage2D}，与数据量无关。
 *
 * <h2>为什么是 byte[1024] 而不是 int[256]</h2>
 * <p>上传纹理要的就是字节数组。{@code int[256]} 到 {@code byte[1024]} 的转换放在
 * 渲染热路径上纯属浪费——这里生成一次就不动了。排列是 <strong>RGBA</strong>，
 * 与 GL 的 {@code GL_RGBA} 上传格式一致。
 *
 * <h2>通道不做预乘</h2>
 * <p>LUT 里存的是直通（straight）alpha。本项目的管线是预乘混合
 * （{@code GL_ONE} / {@code GL_ONE_MINUS_SRC_ALPHA}），因此配色若带透明度，
 * 采样之后由渲染侧预乘。放在 LUT 里预乘是错的：那会让"换配色"与"改全局透明度"
 * 两件事互相污染。
 *
 * <p>纯计算、零依赖，因此可以彻底单测。
 */
public final class ColorMapping {

    /** LUT 的纹素个数。 */
    public static final int LUT_SIZE = 256;

    /** 灰度：从黑到白。任何需要"看得见就行"的场合都可以先拿它顶。 */
    public static final ColorMapping GRAYSCALE = of(
            new Stop(0.0, 0xFF000000),
            new Stop(1.0, 0xFFFFFFFF));

    /**
     * 红外配色（4 个关键节点的近似）。
     *
     * <p>经典的 infrared 调色板是一张 256 项的离散表；这里给的是它的四个关键节点。
     * 要原样复刻就把整张表铺进来——{@link Stop} 数组能表达任意长度，
     * 而 LUT 的生成代价与色标个数是线性关系。
     */
    public static final ColorMapping INFRARED_4 = of(
            new Stop(0.00, 0xFF000000),
            new Stop(0.33, 0xFF8B0000),
            new Stop(0.66, 0xFFFF6A00),
            new Stop(1.00, 0xFFFFFFFF));

    private final Stop[] stops;

    private ColorMapping(Stop[] stops) {
        if (stops.length == 0) {
            throw new IllegalArgumentException("至少要有一个色标");
        }
        for (int i = 1; i < stops.length; i++) {
            if (stops[i].position() < stops[i - 1].position()) {
                throw new IllegalArgumentException(
                        "色标位置必须非递减：第 " + (i - 1) + " 个是 " + stops[i - 1].position()
                                + "，第 " + i + " 个是 " + stops[i].position()
                                + "。倒序会让插值区间长度为负，结果是一段乱跳的颜色");
            }
        }
        this.stops = stops;
    }

    /**
     * 由色标构造。
     *
     * @param stops 色标，至少一个；位置必须非递减且落在 {@code [0,1]} 内
     * @return 配色
     * @throws IllegalArgumentException 色标为空或位置不合法时
     */
    public static ColorMapping of(Stop... stops) {
        if (stops == null) {
            throw new IllegalArgumentException("stops 不能为 null");
        }
        return new ColorMapping(stops.clone());
    }

    private static int lerp(int argbA, int argbB, double fraction) {
        int alpha = mix((argbA >>> 24) & 0xFF, (argbB >>> 24) & 0xFF, fraction);
        int red = mix((argbA >>> 16) & 0xFF, (argbB >>> 16) & 0xFF, fraction);
        int green = mix((argbA >>> 8) & 0xFF, (argbB >>> 8) & 0xFF, fraction);
        int blue = mix(argbA & 0xFF, argbB & 0xFF, fraction);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int mix(int a, int b, double fraction) {
        return (int) Math.round(a + (b - a) * fraction);
    }

    /**
     * 取 {@code t} 处的颜色。
     *
     * <p>{@code t} 在 {@code [0,1]} 之外时<strong>钳到端点颜色</strong>：
     * 数据一侧的四舍五入很容易给出 {@code 1.0000000000000002}，钳位是唯一挡住它的东西。
     *
     * @param t 归一化位置
     * @return 颜色，{@code 0xAARRGGBB}
     */
    public int colorAt(double t) {
        Stop first = stops[0];
        Stop last = stops[stops.length - 1];
        if (!(t > first.position())) {
            return first.argb();
        }
        if (t >= last.position()) {
            return last.argb();
        }
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i].position()) {
                Stop a = stops[i - 1];
                Stop b = stops[i];
                double span = b.position() - a.position();
                double fraction = span <= 0 ? 0.0 : (t - a.position()) / span;
                return lerp(a.argb(), b.argb(), fraction);
            }
        }
        return last.argb();
    }

    /**
     * 生成 1×256 的 RGBA 查找表。
     *
     * <p>第 {@code i} 个纹素取 {@code t = i / 255}，因此首末两个纹素恰好落在
     * 首末色标上——端点对不上是这类函数最常见的错，而它只表现为"配色整体偏了一点"。
     *
     * @return {@code byte[1024]}，按 RGBA 排列
     */
    public byte[] toLut() {
        byte[] lut = new byte[LUT_SIZE * 4];
        for (int i = 0; i < LUT_SIZE; i++) {
            int argb = colorAt(i / (double) (LUT_SIZE - 1));
            lut[i * 4] = (byte) ((argb >>> 16) & 0xFF);
            lut[i * 4 + 1] = (byte) ((argb >>> 8) & 0xFF);
            lut[i * 4 + 2] = (byte) (argb & 0xFF);
            lut[i * 4 + 3] = (byte) ((argb >>> 24) & 0xFF);
        }
        return lut;
    }

    /** 返回色标的副本，供渲染侧判断"配色是否变了"。 */
    public Stop[] stops() {
        return Arrays.copyOf(stops, stops.length);
    }

    /**
     * 一个色标。
     *
     * @param position 位置，{@code [0, 1]}
     * @param argb     颜色，{@code 0xAARRGGBB}（与本项目其它地方一致）
     */
    public record Stop(double position, int argb) {

        public Stop {
            if (!(position >= 0.0 && position <= 1.0)) {
                throw new IllegalArgumentException("色标位置必须在 [0,1] 内，实际为 " + position);
            }
        }
    }
}
