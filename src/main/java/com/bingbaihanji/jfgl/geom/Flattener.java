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
    public int pointCount() { return count; }

    /**
     * 返回第 {@code i} 个顶点的 x 坐标。
     *
     * @param i 顶点下标，须小于 {@link #pointCount()}
     * @return 顶点的 x 坐标
     */
    public float x(int i) { return xs[i]; }

    /**
     * 返回第 {@code i} 个顶点的 y 坐标。
     *
     * @param i 顶点下标，须小于 {@link #pointCount()}
     * @return 顶点的 y 坐标
     */
    public float y(int i) { return ys[i]; }

    /**
     * 返回子路径数量。
     *
     * @return 由 {@code moveTo} 开启的子路径数量
     */
    public int subPathCount() { return subPathCount; }

    /**
     * 返回第 {@code i} 条子路径的起点在顶点数组中的下标。
     *
     * @param i 子路径下标，须小于 {@link #subPathCount()}
     * @return 该子路径起点对应的顶点下标
     */
    public int subPathStart(int i) { return subPathStarts[i]; }

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
                case QUAD_TO -> {
                    float cx = path.commandX(i, 0), cy = path.commandY(i, 0);
                    float ex = path.commandX(i, 1), ey = path.commandY(i, 1);
                    int segs = quadSegments(currentX, currentY, cx, cy, ex, ey, scale, tolerance);
                    for (int s = 1; s <= segs; s++) {
                        float t = s / (float) segs;
                        float u = 1f - t;
                        appendPoint(u * u * currentX + 2f * u * t * cx + t * t * ex,
                                    u * u * currentY + 2f * u * t * cy + t * t * ey);
                    }
                    currentX = ex; currentY = ey;
                }
                case CUBIC_TO -> {
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
                    currentX = ex; currentY = ey;
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
     * 三次贝塞尔：分别取 t=1/3 与 t=2/3 处到弦的距离，取较大者作为最大偏差上界。
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
        float d1 = pointLineDistance(
                (float) (0.2962962963 * x0 + 0.4444444444 * c1x + 0.2222222222 * c2x + 0.0370370370 * x1),
                (float) (0.2962962963 * y0 + 0.4444444444 * c1y + 0.2222222222 * c2y + 0.0370370370 * y1),
                x0, y0, x1, y1);
        float d2 = pointLineDistance(
                (float) (0.0370370370 * x0 + 0.2222222222 * c1x + 0.4444444444 * c2x + 0.2962962963 * x1),
                (float) (0.0370370370 * y0 + 0.2222222222 * c1y + 0.4444444444 * c2y + 0.2962962963 * y1),
                x0, y0, x1, y1);
        return segmentsForDeviation(Math.max(d1, d2), scale, tolerance, 4f);
    }

    /**
     * 计算点 ({@code px}, {@code py}) 到线段 ({@code x0}, {@code y0})-({@code x1}, {@code y1}) 的垂直距离。
     *
     * <p>线段退化成一点时（弦长为零）返回点到该点的距离。
     *
     * @param px 点的 x 坐标
     * @param py 点的 y 坐标
     * @param x0 线段起点的 x 坐标
     * @param y0 线段起点的 y 坐标
     * @param x1 线段终点的 x 坐标
     * @param y1 线段终点的 y 坐标
     * @return 点到线段的距离
     */
    private static float pointLineDistance(float px, float py,
                                           float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-12f) {
            float ex = px - x0, ey = py - y0;
            return (float) Math.sqrt(ex * ex + ey * ey);
        }
        float cross = Math.abs((px - x0) * dy - (py - y0) * dx);
        return cross / (float) Math.sqrt(len2);
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
     * @param exponent       误差衰减指数（二次曲线取 2，三次曲线取 4）
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
