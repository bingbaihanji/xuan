package com.bingbaihanji.xuan.geom;

/**
 * 点与几何的命中判定。<strong>纯计算，零 GL 依赖，不持有任何状态。</strong>
 *
 * <p>两个判定各自服务 {@code Gc.isPointInPath} 与 {@code Gc.isPointInStroke}
 * 的一个算法选择，理由写在
 * {@code docs/superpowers/specs/2026-09-28-xuan-path-hit-design.md} §4：
 * 路径用<strong>交叉计数</strong>（便宜、无前提、与填充的等价性可证），
 * 描边用<strong>复用 {@link StrokeGenerator} 的三角形</strong>（接头几何繁复，
 * 抄一遍就是制造"同一条尺规的两份实现"）。
 *
 * <h2>调用方负责把两边放进同一个坐标系</h2>
 * <p>本类只做"点和给定几何的算术"，不问它们从哪来。{@code Gc} 那边把路径/描边
 * 变换到**设备像素**空间再送进来，因为公开 API 的查询点是设备像素
 * （Canvas 语义：点不受变换影响、路径受）。
 *
 * <h2>边界上的点没有约定</h2>
 * <p>正好落在轮廓线或三角形边上的点，两个函数都**不保证**返回什么——浮点比较在
 * 那里本来就没有稳定答案，硬定一个（"算在内"或"算在外"）只会给出一种**看起来**
 * 确定、实际依赖最后一位的行为。要用在边界上时请自己留一点余量。
 */
public final class PathHit {

    private PathHit() {
    }

    /**
     * 交叉计数（奇偶规则）：点是否落在这些轮廓围出的区域内部。
     *
     * <p>对**全部**轮廓的交叉次数取总奇偶：奇数在内部、偶数在外部。
     * 于是"外轮廓 + 内挖空"（环图那种）自然成立——洞里的点被穿两次。
     *
     * <p><strong>这与 Xuan 的填充是同一套语义</strong>：{@code Tessellator} 按
     * <b>包含关系</b>定洞（嵌套深度偶数为外轮廓、奇数为洞），而对良构路径
     * （它的前提：各轮廓是简单多边形、无自相交）**嵌套深度奇偶 ≡ 交叉计数奇偶**。
     * 两者只在"部分重叠但不包含"的轮廓上分家，而那已经踩了前提。
     *
     * @param points       扁平顶点数组 {@code [x0,y0,x1,y1,...]}，所有轮廓共用一块
     * @param offsets      每条轮廓在 {@code points} 里的**起点下标**（顶点下标，不是 float 下标）
     * @param counts       每条轮廓的**顶点个数**
     * @param contourCount 轮廓条数
     * @param x            查询点 x
     * @param y            查询点 y
     * @return 在内部为 true
     */
    public static boolean isPointInContours(float[] points, int[] offsets, int[] counts,
                                            int contourCount, float x, float y) {
        // 非有限的查询点直接判否，不停在"NaN 与任何比较都为 false 所以恰好也对"上：
        // 那依赖于判据**恰好**写成哪种比较，而下面的交叉计数里有一处
        // `(yi > y) != (yj > y)` —— NaN 让两边都为 false、"不等"为 false，
        // 于是那条边被跳过。结果是"碰巧对"，不是"被保证对"。
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        if (points == null || offsets == null || counts == null) {
            return false;
        }
        boolean inside = false;
        for (int c = 0; c < contourCount; c++) {
            if (c >= offsets.length || c >= counts.length) {
                break;
            }
            // 少于 3 个点围不出面积；退化的轮廓跳过而不是当成"奇数次交叉"。
            if (counts[c] < 3) {
                continue;
            }
            if (crossesRay(points, offsets[c], counts[c], x, y)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 一条轮廓与"从 {@code (x,y)} 向右的射线"是否有奇数个交点。
     *
     * <p>用的是半开区间判据 {@code (yi > y) != (yj > y)}：顶点**恰好**落在射线上时
     * 只算一次。写成 {@code >=} 那类闭区间的话，一条恰好从查询点高度穿过的水平边
     * 会被数两次（两端点各一次），奇偶就翻回去了——而"射线正好压在一个顶点上"
     * 在网格化、对齐的图形上并不罕见。
     */
    private static boolean crossesRay(float[] pts, int firstVertex, int count,
                                      float x, float y) {
        boolean odd = false;
        // ★ 这里的下标一律是**顶点**下标，取坐标时才 `* 2`。
        //   初版写成 `firstVertex + i * 2`（把顶点下标当成了 float 下标），
        //   于是**只有第一条轮廓**（firstVertex == 0）碰巧对——0 加多少都对——
        //   而第二条起全都读到隔壁轮廓的坐标上。单轮廓的测试因此全绿，
        //   是"两条不相交轮廓"那条测试把它逼出来的。
        //   这正是"边界值恰好让错误实现看起来对"的又一个实例。
        int prev = firstVertex + count - 1;
        for (int i = 0; i < count; i++) {
            int cur = firstVertex + i;
            float yi = pts[cur * 2 + 1];
            float yj = pts[prev * 2 + 1];
            if ((yi > y) != (yj > y)) {
                // 射线与这条边的交点的 x（这里 yj != yi 由上面那个判据保证）
                float xi = pts[cur * 2];
                float xj = pts[prev * 2];
                if (x < xi + (y - yi) / (yj - yi) * (xj - xi)) {
                    odd = !odd;
                }
            }
            prev = cur;
        }
        return odd;
    }

    /**
     * 点是否落在这些三角形中的**任一个**内部。
     *
     * <p>三角形之间通常**互相重叠**（描边的每一段、每个接头各发一批），
     * 而"在任一个里"取的正是**并集**——那就是墨迹覆盖的区域。这一点很重要：
     * 换成"覆盖层数的奇偶"会把重叠处的判定反过来。
     *
     * @param triangles  扁平三角形数组 {@code [x0,y0,x1,y1,x2,y2, ...]}，每 6 个 float 一个
     * @param floatCount 有效的 float 个数（不是三角形个数）
     * @param x          查询点 x
     * @param y          查询点 y
     * @return 落在任一个三角形内为 true
     */
    public static boolean isPointInTriangles(float[] triangles, int floatCount,
                                             float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        if (triangles == null) {
            return false;
        }
        for (int i = 0; i + 5 < floatCount; i += 6) {
            float x0 = triangles[i];
            float y0 = triangles[i + 1];
            float x1 = triangles[i + 2];
            float y1 = triangles[i + 3];
            float x2 = triangles[i + 4];
            float y2 = triangles[i + 5];
            // ★ 退化三角形（三点共线，或两个顶点重合）**必须跳过**，不能交给下面的
            //   半平面判据。两个顶点重合时，`!(有正 && 有负)` 会退化成"点与第三个顶点
            //   在底边的同侧"——那是一个**半平面**，命中范围大得离谱，而画面上那块
            //   三角形的面积是 0（本来就什么都没画）。生成器确实会产出退化的三角形
            //   （零长度的描边段、超限回退的接头）。
            if (Math.abs(signedArea2(x0, y0, x1, y1, x2, y2)) < 1e-9f) {
                continue;
            }
            if (coversPoint(x0, y0, x1, y1, x2, y2, x, y)) {
                return true;
            }
        }
        return false;
    }

    /** 三角形面积的两倍（有符号）。 */
    private static float signedArea2(float x0, float y0, float x1, float y1,
                                     float x2, float y2) {
        return (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
    }

    /**
     * 点是否在三角形内（含边界）。
     *
     * <p>判据是三个有符号面积**同号**：全非负或全非正即在内部。写成
     * {@code !(hasNegative && hasPositive)} 而不是分别比较，是为了让"点在边上"
     * （某个面积为 0）落进"在内部"那一侧——那与 {@link #isPointInContours}
     * 的半开区间判据一样，只是让边界有个**确定**的归属，不是承诺它精确。
     */
    private static boolean coversPoint(float x0, float y0, float x1, float y1,
                                       float x2, float y2, float px, float py) {
        float d0 = side(px, py, x0, y0, x1, y1);
        float d1 = side(px, py, x1, y1, x2, y2);
        float d2 = side(px, py, x2, y2, x0, y0);
        boolean hasNegative = d0 < 0f || d1 < 0f || d2 < 0f;
        boolean hasPositive = d0 > 0f || d1 > 0f || d2 > 0f;
        return !(hasNegative && hasPositive);
    }

    /** 点相对有向边 (ax,ay)→(bx,by) 在哪一侧。 */
    private static float side(float px, float py, float ax, float ay,
                              float bx, float by) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }
}
