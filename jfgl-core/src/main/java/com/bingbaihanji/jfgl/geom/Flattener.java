package com.bingbaihanji.jfgl.geom;

/**
 * 把 {@link Path} 中的曲线段按设备像素容差细分成折线。
 *
 * <p>容差以<strong>设备像素</strong>为单位，通过 {@code scale} 参数把世界单位换算过去。
 * 这样放大时曲线自动细分得更密，缩小时不浪费顶点。
 *
 * <p>结果按子路径组织：每个 {@code moveTo} 开启一段新的子路径，其起点下标记录在
 * {@link #subPathStart(int)} 中；{@code close()} 会补一条回到起点的线段（起点与当前点
 * 已经重合时不再重复追加）。
 *
 * <p>与 {@link Path} 一样，此类可被反复 {@link #reset()} 并复用；实例在稳态下不产生
 * 分配（扩容时除外）。
 *
 * <p>本类不依赖 {@code gl} 包，可脱离 GL 上下文进行单元测试。
 */
public final class Flattener {

    /** 每条曲线最少细分段数，避免容差过大时退化成一条直线。 */
    private static final int MIN_SEGMENTS = 4;

    /** 每条曲线最多细分段数，防止病态输入产生海量顶点。 */
    private static final int MAX_SEGMENTS = 256;

    /** 折线顶点的 x 坐标。 */
    private float[] xs = new float[256];

    /** 折线顶点的 y 坐标。 */
    private float[] ys = new float[256];

    /** 当前已产生的顶点数量。 */
    private int count = 0;

    /** 各子路径起点在顶点数组中的下标。 */
    private int[] subPathStarts = new int[16];

    /** 当前已记录的子路径数量。 */
    private int subPathCount = 0;

    /**
     * 计算三次贝塞尔曲线到其弦的最大偏差上界：(3/4)·max(|P0−2P1+P2|, |P1−2P2+P3|)。
     *
     * <p>这是基于控制多边形二阶差分（second difference）的标准保守上界：曲线按参数等分成
     * n 段后，每一段到其弦的偏差不超过该上界的 {@code 1/n^2}，因此可直接用于反解段数。
     * 上界恒不小于真实最大偏差（例如控制点 (0,0)、(0,100)、(100,100)、(100,0) 时上界为
     * 106.07，而真实最大偏差为 75），故不会细分不足。
     *
     * @param x0  起点的 x 坐标
     * @param y0  起点的 y 坐标
     * @param c1x 第一个控制点的 x 坐标
     * @param c1y 第一个控制点的 y 坐标
     * @param c2x 第二个控制点的 x 坐标
     * @param c2y 第二个控制点的 y 坐标
     * @param x1  终点的 x 坐标
     * @param y1  终点的 y 坐标
     * @return 曲线到弦的最大偏差上界（世界单位）
     */
    private static float cubicMaxDeviationBound(float x0, float y0, float c1x, float c1y,
                                                float c2x, float c2y, float x1, float y1) {
        float d1x = x0 - 2f * c1x + c2x;
        float d1y = y0 - 2f * c1y + c2y;
        float d2x = c1x - 2f * c2x + x1;
        float d2y = c1y - 2f * c2y + y1;
        float n1 = (float) Math.sqrt(d1x * d1x + d1y * d1y);
        float n2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
        return 0.75f * Math.max(n1, n2);
    }

    /**
     * 把世界单位的偏差换算成设备像素偏差后求所需段数。
     *
     * <p>误差随段数按 {@code n^-exponent} 衰减，故 {@code n = (d / tol)^(1/exponent)}，
     * 结果被限制在 [{@value #MIN_SEGMENTS}, {@value #MAX_SEGMENTS}] 之间。
     * 段数随 {@code scale} 单调不减：放大时细分更密，缩小时不浪费顶点。
     *
     * @param worldDeviation 世界单位下的最大偏差
     * @param scale          当前变换的缩放因子（设备像素 / 世界单位）
     * @param tolerance      允许的最大偏差（设备像素）
     * @param exponent       误差衰减指数；二次与三次贝塞尔的弦逼近偏差都按 {@code n^-2} 衰减，均取 2
     * @return 该曲线应细分的段数
     */
    private static int segmentsForDeviation(float worldDeviation, float scale,
                                            float tolerance, float exponent) {
        float deviceDeviation = worldDeviation * Math.abs(scale);
        if (deviceDeviation <= tolerance) {
            return MIN_SEGMENTS;
        }
        // 误差随段数按 n^-exponent 衰减：n = (d / tol)^(1/exponent)
        double n = Math.pow(deviceDeviation / tolerance, 1.0 / exponent);
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, (int) Math.ceil(n)));
    }

    /**
     * 清空结果，保留已分配的数组容量。
     *
     * <p>此方法不释放内部数组，因此可被复用的实例在稳态下不会产生分配。
     */
    public void reset() {
        count = 0;
        subPathCount = 0;
    }

    /**
     * 返回折线顶点数量。
     *
     * @return 已产生的顶点数量
     */
    public int pointCount() {return count;}

    /**
     * 返回第 {@code i} 个顶点的 x 坐标。
     *
     * @param i 顶点下标，须小于 {@link #pointCount()}
     * @return 顶点的 x 坐标
     */
    public float x(int i) {return xs[i];}

    /**
     * 返回第 {@code i} 个顶点的 y 坐标。
     *
     * @param i 顶点下标，须小于 {@link #pointCount()}
     * @return 顶点的 y 坐标
     */
    public float y(int i) {return ys[i];}

    /**
     * 把折线顶点按扁平的 {@code [x0,y0, x1,y1, ...]} 顺序复制到 {@code dst}，
     * 返回写入的 float 个数（等于 {@code pointCount() * 2}）。
     *
     * <p>{@link Tessellator#tessellate} 与 {@link StrokeGenerator#stroke} 消费的都是这种交错布局，
     * 而本类的 {@link #x(int)}/{@link #y(int)} 是两条平行数组，直接喂给它们就得每个形状
     * 每帧现搭一个数组。用本方法让调用方在帧之间复用同一块缓冲区，热路径上零分配。
     *
     * <p>与 {@link #x(int)} 一样，只在 {@code i < pointCount()} 时有意义。
     *
     * @param dst 接收顶点数据的数组，长度至少为 {@code pointCount() * 2}
     * @return 实际写入的 float 个数
     * @throws IllegalArgumentException {@code dst} 装不下全部顶点时
     */
    public int copyPointsTo(float[] dst) {
        int needed = count * 2;
        if (dst.length < needed) {
            throw new IllegalArgumentException(
                    "目标数组太小：需要 " + needed + " 个 float，实际只有 " + dst.length + " 个");
        }
        for (int i = 0; i < count; i++) {
            dst[i * 2] = xs[i];
            dst[i * 2 + 1] = ys[i];
        }
        return needed;
    }

    /**
     * 返回子路径数量。
     *
     * @return 由 {@code moveTo} 开启的子路径数量
     */
    public int subPathCount() {return subPathCount;}

    /**
     * 返回第 {@code i} 条子路径的起点在顶点数组中的下标。
     *
     * @param i 子路径下标，须小于 {@link #subPathCount()}
     * @return 该子路径起点对应的顶点下标
     */
    public int subPathStart(int i) {return subPathStarts[i];}

    /**
     * 平坦化路径。结果会被 {@link #reset()} 后重新填充。
     *
     * @param path     待平坦化的路径
     * @param scale    当前变换的平均缩放因子（设备像素 / 世界单位）
     * @param tolerance 允许的最大偏差（设备像素）
     */
    public void flatten(Path path, float scale, float tolerance) {
        reset();
        if (path.isEmpty()) {
            return;
        }

        float currentX = 0f, currentY = 0f;
        float startX = 0f, startY = 0f;
        boolean hasSubPath = false;

        for (int i = 0; i < path.commandCount(); i++) {
            switch (path.commandType(i)) {
                case MOVE_TO -> {
                    currentX = path.commandX(i, 0);
                    currentY = path.commandY(i, 0);
                    startX = currentX;
                    startY = currentY;
                    if (subPathCount == subPathStarts.length) {
                        subPathStarts = java.util.Arrays.copyOf(subPathStarts, subPathCount * 2);
                    }
                    subPathStarts[subPathCount++] = count;
                    appendPoint(currentX, currentY);
                    hasSubPath = true;
                }
                case LINE_TO -> {
                    currentX = path.commandX(i, 0);
                    currentY = path.commandY(i, 0);
                    appendPoint(currentX, currentY);
                }
                case QUADRATIC_CURVE_TO -> {
                    float cx = path.commandX(i, 0), cy = path.commandY(i, 0);
                    float ex = path.commandX(i, 1), ey = path.commandY(i, 1);
                    int segs = quadSegments(currentX, currentY, cx, cy, ex, ey, scale, tolerance);
                    for (int s = 1; s <= segs; s++) {
                        float t = s / (float) segs;
                        float u = 1f - t;
                        appendPoint(u * u * currentX + 2f * u * t * cx + t * t * ex,
                                u * u * currentY + 2f * u * t * cy + t * t * ey);
                    }
                    currentX = ex;
                    currentY = ey;
                }
                case BEZIER_CURVE_TO -> {
                    float c1x = path.commandX(i, 0), c1y = path.commandY(i, 0);
                    float c2x = path.commandX(i, 1), c2y = path.commandY(i, 1);
                    float ex = path.commandX(i, 2), ey = path.commandY(i, 2);
                    int segs = cubicSegments(currentX, currentY, c1x, c1y, c2x, c2y, ex, ey,
                            scale, tolerance);
                    for (int s = 1; s <= segs; s++) {
                        float t = s / (float) segs;
                        float u = 1f - t;
                        float x = u * u * u * currentX + 3f * u * u * t * c1x
                                + 3f * u * t * t * c2x + t * t * t * ex;
                        float y = u * u * u * currentY + 3f * u * u * t * c1y
                                + 3f * u * t * t * c2y + t * t * t * ey;
                        appendPoint(x, y);
                    }
                    currentX = ex;
                    currentY = ey;
                }
                case CLOSE -> {
                    if (hasSubPath && (currentX != startX || currentY != startY)) {
                        appendPoint(startX, startY);
                        currentX = startX;
                        currentY = startY;
                    }
                }
            }
        }
    }

    /**
     * 使用默认容差 0.25 设备像素平坦化路径。
     *
     * @param path  待平坦化的路径
     * @param scale 当前变换的平均缩放因子（设备像素 / 世界单位）
     */
    public void flatten(Path path, float scale) {
        flatten(path, scale, 0.25f);
    }

    /**
     * 按“控制点到弦的最大距离 ≤ 容差”估算二次贝塞尔所需段数。
     * 用曲线在 t=0.5 处到弦中点距离的 2 倍近似最大偏差。
     *
     * @param x0        起点的 x 坐标
     * @param y0        起点的 y 坐标
     * @param cx        控制点的 x 坐标
     * @param cy        控制点的 y 坐标
     * @param x1        终点的 x 坐标
     * @param y1        终点的 y 坐标
     * @param scale     当前变换的缩放因子（设备像素 / 世界单位）
     * @param tolerance 允许的最大偏差（设备像素）
     * @return 该曲线应细分的段数
     */
    private int quadSegments(float x0, float y0, float cx, float cy,
                             float x1, float y1, float scale, float tolerance) {
        float mx = 0.5f * (x0 + x1), my = 0.5f * (y0 + y1);
        float qx = 0.25f * x0 + 0.5f * cx + 0.25f * x1;
        float qy = 0.25f * y0 + 0.5f * cy + 0.25f * y1;
        float dx = qx - mx, dy = qy - my;
        return segmentsForDeviation((float) Math.sqrt(dx * dx + dy * dy), scale, tolerance, 2f);
    }

    /**
     * 三次贝塞尔：按控制多边形二阶差分的标准上界估算到弦的最大偏差，再求所需段数。
     *
     * <p>用 {@link #cubicMaxDeviationBound} 而非在 t=1/3、t=2/3 处采样：那两个采样点并非
     * 最大偏差所在位置，会低估偏差，从而细分不足、放大后露出棱角。
     *
     * <p>衰减指数取 2：曲线按参数等分成 n 段后，每段到其弦的偏差与控制点二阶差分都按
     * {@code n^-2} 衰减，二次与三次贝塞尔皆然。此处若取 4 会严重低估段数
     * （例如本包测试曲线在 scale=16、tol=0.25 下只用 10 段，实际偏差达 12 设备像素）。
     *
     * @param x0        起点的 x 坐标
     * @param y0        起点的 y 坐标
     * @param c1x       第一个控制点的 x 坐标
     * @param c1y       第一个控制点的 y 坐标
     * @param c2x       第二个控制点的 x 坐标
     * @param c2y       第二个控制点的 y 坐标
     * @param x1        终点的 x 坐标
     * @param y1        终点的 y 坐标
     * @param scale     当前变换的缩放因子（设备像素 / 世界单位）
     * @param tolerance 允许的最大偏差（设备像素）
     * @return 该曲线应细分的段数
     */
    private int cubicSegments(float x0, float y0, float c1x, float c1y,
                              float c2x, float c2y, float x1, float y1,
                              float scale, float tolerance) {
        float deviation = cubicMaxDeviationBound(x0, y0, c1x, c1y, c2x, c2y, x1, y1);
        return segmentsForDeviation(deviation, scale, tolerance, 2f);
    }

    /**
     * 追加一个顶点，必要时按倍数扩容。
     *
     * @param x 顶点的 x 坐标
     * @param y 顶点的 y 坐标
     */
    private void appendPoint(float x, float y) {
        if (count == xs.length) {
            xs = java.util.Arrays.copyOf(xs, count * 2);
            ys = java.util.Arrays.copyOf(ys, count * 2);
        }
        xs[count] = x;
        ys[count] = y;
        count++;
    }
}
