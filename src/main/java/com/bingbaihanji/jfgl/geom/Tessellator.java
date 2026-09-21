package com.bingbaihanji.jfgl.geom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 把简单多边形（可凹、无自相交）三角化为三角形列表。
 *
 * <p>输出格式为扁平的 float 数组，每 6 个 float 描述一个三角形：
 * {@code [x0,y0, x1,y1, x2,y2, ...]}。
 *
 * <p>算法：先判断凸性。凸多边形走扇形三角化（O(n)）；凹多边形走耳切法（O(n²)）。
 * 顶点顺序（顺/逆时针）不要求，内部会统一为逆时针。
 *
 * <p>带孔洞的多边形用 {@link #tessellateWithHoles}：先把每个洞用一对重合边桥接到
 * 轮廓上合并成单个简单多边形，再走同一套耳切法。
 *
 * <p><b>输入契约</b>（超出契约的输入不会抛异常、也不会死循环，但结果不保证正确，
 * 可能凭空丢掉一部分面积）：
 * <ul>
 *   <li>外轮廓与每个洞各自都是简单多边形：无自相交、无重合顶点、面积非零；</li>
 *   <li>每个洞完整落在外轮廓内部，洞与洞之间不相交、不互相包含；</li>
 *   <li>洞的顶点数至少 3，且坐标个数与顶点数一致。</li>
 * </ul>
 *
 * <p><b>已知局限</b>：即使输入完全满足上面的契约，洞数一多，桥接点也可能被别的洞
 * 全部挡住，此时只能退化成"最近顶点"连一条并不合法的桥，结果是尽力而为的
 * ——会少画一部分面积，但不会抛异常。实测在随机生成的合法输入上的失败率：
 * <b>2 个洞 0.4%、3 个洞 0.8%、4 个洞 7%、5 个洞 13%</b>，6 个洞以上显著升高
 * （4051 个用例的统计）。环形图、饼图挖空这类一两个洞的常规图形不受影响。
 *
 * <p>为了不让这种退化变成"静默少画"，{@link #tessellateWithHoles} 结束前会用
 * 鞋带公式把输出面积和输入面积对一遍，不一致就通过日志告警（只告警、不抛异常：
 * 渲染库在路经病态路径时宁可画出个大概并说清楚，也好过整帧崩掉）。
 *
 * <p>本类不含任何 GL 依赖，可脱离窗口做单元测试。实例可复用：
 * 每次 {@link #tessellate} 都会先清空上一次的结果。
 */
public final class Tessellator {

    /** 斜率比较与面积判断的容差。 */
    private static final float EPSILON = 1e-6f;

    /** 输出面积与输入面积允许的相对偏差，超出即告警。 */
    private static final double AREA_TOLERANCE = 1e-3;

    /** 日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(Tessellator.class);

    /** 输出三角形缓冲，每 6 个 float 一个三角形，容量不足时翻倍。 */
    private float[] triangles = new float[3 * 6 * 4];

    /** 当前已产出的三角形个数。 */
    private int triangleCount = 0;

    /** 顶点 x 坐标工作区，避免每次调用重新分配。 */
    private float[] scratchX = new float[64];

    /** 顶点 y 坐标工作区，避免每次调用重新分配。 */
    private float[] scratchY = new float[64];

    /** 桥接时洞顶点的 x 坐标工作区。 */
    private float[] holeX = new float[64];

    /** 桥接时洞顶点的 y 坐标工作区。 */
    private float[] holeY = new float[64];

    /** 创建三角化器。 */
    public Tessellator() {
        // 使用默认初始容量
    }

    /**
     * 用鞋带公式计算多边形的面积（取绝对值，顺/逆时针都适用）。
     *
     * @param pts   扁平顶点数组 {@code [x0,y0, x1,y1, ...]}
     * @param count 顶点个数
     * @return 多边形面积
     */
    private static double polygonArea(float[] pts, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            sum += (double) pts[i * 2] * pts[j * 2 + 1] - (double) pts[j * 2] * pts[i * 2 + 1];
        }
        return Math.abs(sum * 0.5);
    }

    /**
     * 判断第 {@code h} 个洞是否可用（非空且至少 3 个顶点）。
     *
     * @param holes      每个洞的扁平顶点数组
     * @param holeCounts 每个洞的顶点数
     * @param h          洞的下标
     * @return 可用返回 {@code true}
     */
    private static boolean isUsableHole(float[][] holes, int[] holeCounts, int h) {
        return holes[h] != null && holeCounts[h] >= 3
                && holes[h].length >= holeCounts[h] * 2;
    }

    /**
     * 把一个洞桥接到当前轮廓上，返回合并后的顶点数。
     *
     * <p>做法：取洞的最右顶点 H 与轮廓上的桥接点 M，在 M 之后依次插入
     * {@code H → 绕洞一周 → H → M}，其中 {@code M→H} 与 {@code H→M}
     * 是那对方向相反的重合边。轮廓在 M 之后的部分整体后移。
     *
     * @param mx 当前轮廓的 x 坐标（原地修改）
     * @param my 当前轮廓的 y 坐标（原地修改）
     * @param n  当前轮廓的顶点数
     * @param hx 洞的 x 坐标（已归一化为与外轮廓相反的绕向）
     * @param hy 洞的 y 坐标
     * @param hc 洞的顶点数
     * @param holes       全部洞的扁平顶点数组（用于避开尚未合并的洞）
     * @param holeCounts  全部洞的顶点数
     * @param requireVisible 为 {@code true} 时找不到可见桥接点就返回 {@code -1}；
     *                    为 {@code false} 时退化为"最近的顶点"
     * @return 合并后的顶点数；{@code requireVisible} 且无可见桥接点时返回 {@code -1}
     */
    private static int bridgeHole(float[] mx, float[] my, int n,
                                  float[] hx, float[] hy, int hc,
                                  float[][] holes, int[] holeCounts,
                                  boolean requireVisible) {
        int holeRight = 0;
        for (int i = 1; i < hc; i++) {
            if (hx[i] > hx[holeRight]) {
                holeRight = i;
            }
        }
        int target = findBridgeVertex(mx, my, n, hx[holeRight], hy[holeRight], holes, holeCounts);
        if (target < 0) {
            if (requireVisible) {
                return -1;
            }
            // 所有轮廓顶点都连不过去（桥会穿过别的洞或别的边）时，退一步在轮廓边上
            // 找一个最近的可见点，把它当作新顶点插进轮廓再连。
            // 直接连"最近的顶点"会连出一条穿过边界的桥，把多边形弄成自交的，
            // 后面的耳切只能在错误图形上瞎剪，面积就丢了。
            float[] point = new float[2];
            int edge = findBridgeEdge(mx, my, n, hx[holeRight], hy[holeRight],
                    holes, holeCounts, point);
            if (edge >= 0) {
                n = insertVertex(mx, my, n, edge + 1, point[0], point[1]);
                target = edge + 1;
            } else {
                target = nearestVertex(mx, my, n, hx[holeRight], hy[holeRight]);
            }
        }

        int shift = hc + 2;
        for (int i = n - 1; i > target; i--) {
            mx[i + shift] = mx[i];
            my[i + shift] = my[i];
        }

        int p = target + 1;
        for (int k = 0; k < hc; k++) {
            int idx = (holeRight + k) % hc;
            mx[p] = hx[idx];
            my[p] = hy[idx];
            p++;
        }
        mx[p] = hx[holeRight];  // 绕回洞的最右顶点：桥的第一条边终点
        my[p] = hy[holeRight];
        p++;
        mx[p] = mx[target];     // 回到桥接点：与来路重合的第二条边
        my[p] = my[target];
        p++;

        return n + shift;
    }

    /**
     * 在当前轮廓中寻找洞顶点 {@code (hxp, hyp)} 的桥接顶点。
     *
     * <p>取距离最近的可见顶点；一个可见顶点都没有时返回 {@code -1}。
     *
     * @param mx  当前轮廓的 x 坐标
     * @param my  当前轮廓的 y 坐标
     * @param n   当前轮廓的顶点数
     * @param hxp 洞顶点的 x 坐标
     * @param hyp 洞顶点的 y 坐标
     * @param holes      全部洞的扁平顶点数组
     * @param holeCounts 全部洞的顶点数
     * @return 桥接顶点在轮廓中的下标；没有可见顶点时返回 {@code -1}
     */
    private static int findBridgeVertex(float[] mx, float[] my, int n,
                                        float hxp, float hyp,
                                        float[][] holes, int[] holeCounts) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            float dx = mx[i] - hxp;
            float dy = my[i] - hyp;
            float d = dx * dx + dy * dy;
            if (d >= bestDist) {
                continue;
            }
            // 已经用过一次的桥接点（坐标在轮廓里出现了不止一次）不再重复使用：
            // 两个洞都接在同一个顶点上会让那里变成被访问三次的"掐点"，
            // 耳切在掐点上找不出合法的耳，只能中途放弃
            if (isRepeated(mx, my, n, i)) {
                continue;
            }
            if (isBridgeVisible(mx, my, n, hxp, hyp, mx[i], my[i], -1, holes, holeCounts)) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * 在当前轮廓的<b>边</b>上寻找桥接点：即轮廓边 {A}-{B} 上离洞顶点最近、
     * 且与洞顶点之间没有遮挡的一点，找到后写入 {@code out}。
     *
     * <p>取点时会把参数 {@code t} 夹在两端点之间（不让它落在顶点上），
     * 免得新插进去的顶点与已有顶点重合、又造出一个"掐点"。
     *
     * @param mx         当前轮廓的 x 坐标
     * @param my         当前轮廓的 y 坐标
     * @param n          当前轮廓的顶点数
     * @param hxp        洞顶点的 x 坐标
     * @param hyp        洞顶点的 y 坐标
     * @param holes      全部洞的扁平顶点数组
     * @param holeCounts 全部洞的顶点数
     * @param out        接收找到的点，长度为 2
     * @return 该点所在边的起点下标；一个可见点都没有时返回 {@code -1}
     */
    private static int findBridgeEdge(float[] mx, float[] my, int n,
                                      float hxp, float hyp,
                                      float[][] holes, int[] holeCounts, float[] out) {
        int bestEdge = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = mx[i], ay = my[i];
            float bx = mx[j], by = my[j];
            float dx = bx - ax, dy = by - ay;
            float len2 = dx * dx + dy * dy;
            if (len2 <= EPSILON) {
                continue; // 退化的边
            }
            float t = ((hxp - ax) * dx + (hyp - ay) * dy) / len2;
            t = Math.max(0.02f, Math.min(0.98f, t));
            float px = ax + t * dx, py = ay + t * dy;
            float ddx = px - hxp, ddy = py - hyp;
            float d = ddx * ddx + ddy * ddy;
            if (d >= bestDist) {
                continue;
            }
            if (isBridgeVisible(mx, my, n, hxp, hyp, px, py, i, holes, holeCounts)) {
                bestDist = d;
                bestEdge = i;
                out[0] = px;
                out[1] = py;
            }
        }
        return bestEdge;
    }

    /**
     * 把点 {@code (x, y)} 作为新顶点插到轮廓的下标 {@code at} 处，后面的顶点整体后移。
     *
     * @param mx 轮廓 x 坐标（原地修改）
     * @param my 轮廓 y 坐标（原地修改）
     * @param n  插入前的顶点数
     * @param at 新顶点插入的位置
     * @param x  新顶点的 x 坐标
     * @param y  新顶点的 y 坐标
     * @return 插入后的顶点数
     */
    private static int insertVertex(float[] mx, float[] my, int n, int at, float x, float y) {
        for (int i = n - 1; i >= at; i--) {
            mx[i + 1] = mx[i];
            my[i + 1] = my[i];
        }
        mx[at] = x;
        my[at] = y;
        return n + 1;
    }

    /**
     * 判断轮廓中该顶点坐标是否出现了不止一次（即已被某次桥接占用过）。
     *
     * @param mx 轮廓 x 坐标
     * @param my 轮廓 y 坐标
     * @param n  轮廓顶点数
     * @param i  待检查的顶点下标
     * @return 该坐标在轮廓中重复出现返回 {@code true}
     */
    private static boolean isRepeated(float[] mx, float[] my, int n, int i) {
        for (int j = 0; j < n; j++) {
            if (j != i && samePoint(mx[j], my[j], mx[i], my[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回轮廓上距离 {@code (hxp, hyp)} 最近的顶点下标。
     *
     * @param mx  当前轮廓的 x 坐标
     * @param my  当前轮廓的 y 坐标
     * @param n   当前轮廓的顶点数
     * @param hxp 洞顶点的 x 坐标
     * @param hyp 洞顶点的 y 坐标
     * @return 最近顶点在轮廓中的下标
     */
    private static int nearestVertex(float[] mx, float[] my, int n, float hxp, float hyp) {
        int nearest = 0;
        float nearestDist = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float dx = mx[i] - hxp;
            float dy = my[i] - hyp;
            float d = dx * dx + dy * dy;
            if (d < nearestDist) {
                nearestDist = d;
                nearest = i;
            }
        }
        return nearest;
    }

    /**
     * 判断洞顶点到轮廓顶点的连线是否可见（不与任何边真正相交）。
     *
     * <p>除了当前轮廓，还要避开<b>所有</b>洞的边界：桥是在洞被逐个合并的过程中选出来的，
     * 尚未合并的洞此时还不在轮廓里，但桥照样不能从它们身上穿过去。
     *
     * @param mx     当前轮廓的 x 坐标
     * @param my     当前轮廓的 y 坐标
     * @param n      当前轮廓的顶点数
     * @param hxp    洞顶点的 x 坐标
     * @param hyp    洞顶点的 y 坐标
     * @param tx     候选桥接点的 x 坐标
     * @param ty     候选桥接点的 y 坐标
     * @param skipEdge 候选点落在这条边上（顶点候选传 {@code -1}），该边自身不算遮挡
     * @param holes      全部洞的扁平顶点数组
     * @param holeCounts 全部洞的顶点数
     * @return 连线未被遮挡返回 {@code true}
     */
    private static boolean isBridgeVisible(float[] mx, float[] my, int n,
                                           float hxp, float hyp, float tx, float ty,
                                           int skipEdge,
                                           float[][] holes, int[] holeCounts) {
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (i == skipEdge) {
                continue;
            }
            if (segmentsConflict(hxp, hyp, tx, ty, mx[i], my[i], mx[j], my[j])) {
                return false;
            }
        }
        for (int h = 0; h < holes.length && h < holeCounts.length; h++) {
            float[] hole = holes[h];
            int hc = holeCounts[h];
            if (hole == null || hc < 3) {
                continue;
            }
            for (int i = 0; i < hc; i++) {
                int j = (i + 1) % hc;
                if (segmentsConflict(hxp, hyp, tx, ty,
                        hole[i * 2], hole[i * 2 + 1], hole[j * 2], hole[j * 2 + 1])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 判断桥与一条边是否冲突：不仅包含真正的交叉跨越，也包含"擦到"——
     * 边的端点落在桥内部，或桥的端点落在边内部。
     *
     * <p>桥与别的洞擦到虽然不算穿越，却会让合并后的多边形出现"顶点正好压在某条边上"，
     * 耳切在这种退化图形上很容易卡死，所以这里直接避开。
     * 桥自身两个端点落在别的边上（起止点是相邻边的端点）不算冲突。
     *
     * @param ax 桥起点 x 坐标
     * @param ay 桥起点 y 坐标
     * @param bx 桥终点 x 坐标
     * @param by 桥终点 y 坐标
     * @param cx 边的起点 x 坐标
     * @param cy 边的起点 y 坐标
     * @param dx 边的终点 x 坐标
     * @param dy 边的终点 y 坐标
     * @return 冲突返回 {@code true}
     */
    private static boolean segmentsConflict(float ax, float ay, float bx, float by,
                                            float cx, float cy, float dx, float dy) {
        if (segmentsProperlyIntersect(ax, ay, bx, by, cx, cy, dx, dy)) {
            return true;
        }
        return strictlyInside(ax, ay, bx, by, cx, cy)
                || strictlyInside(ax, ay, bx, by, dx, dy)
                || strictlyInside(cx, cy, dx, dy, ax, ay)
                || strictlyInside(cx, cy, dx, dy, bx, by);
    }

    /**
     * 判断点是否落在线段内部（在线段上但不算两个端点）。
     *
     * @param ax 线段起点 x 坐标
     * @param ay 线段起点 y 坐标
     * @param bx 线段终点 x 坐标
     * @param by 线段终点 y 坐标
     * @param px 被测试点的 x 坐标
     * @param py 被测试点的 y 坐标
     * @return 落在内部返回 {@code true}
     */
    private static boolean strictlyInside(float ax, float ay, float bx, float by,
                                          float px, float py) {
        if (Math.abs(cross(ax, ay, bx, by, px, py)) > EPSILON) {
            return false;
        }
        if (samePoint(px, py, ax, ay) || samePoint(px, py, bx, by)) {
            return false;
        }
        return px >= Math.min(ax, bx) - EPSILON && px <= Math.max(ax, bx) + EPSILON
                && py >= Math.min(ay, by) - EPSILON && py <= Math.max(ay, by) + EPSILON;
    }

    /**
     * 判断两条线段是否真正相交（交叉跨越，共端点或共线不算）。
     *
     * @param ax 线段 1 起点 x 坐标
     * @param ay 线段 1 起点 y 坐标
     * @param bx 线段 1 终点 x 坐标
     * @param by 线段 1 终点 y 坐标
     * @param cx 线段 2 起点 x 坐标
     * @param cy 线段 2 起点 y 坐标
     * @param dx 线段 2 终点 x 坐标
     * @param dy 线段 2 终点 y 坐标
     * @return 真正相交返回 {@code true}
     */
    private static boolean segmentsProperlyIntersect(float ax, float ay, float bx, float by,
                                                     float cx, float cy, float dx, float dy) {
        float d1 = cross(cx, cy, dx, dy, ax, ay);
        float d2 = cross(cx, cy, dx, dy, bx, by);
        float d3 = cross(ax, ay, bx, by, cx, cy);
        float d4 = cross(ax, ay, bx, by, dx, dy);
        boolean straddle1 = (d1 > EPSILON && d2 < -EPSILON) || (d1 < -EPSILON && d2 > EPSILON);
        boolean straddle2 = (d3 > EPSILON && d4 < -EPSILON) || (d3 < -EPSILON && d4 > EPSILON);
        return straddle1 && straddle2;
    }

    /**
     * 判断顶点 {@code b} 是否为耳（凸角且三角形内不含其他顶点）。
     *
     * @param px 顶点 x 坐标
     * @param py 顶点 y 坐标
     * @param n  当前顶点个数
     * @param a  前一个顶点下标
     * @param b  当前顶点下标
     * @param c  后一个顶点下标
     * @return 是耳返回 {@code true}
     */
    private static boolean isEar(float[] px, float[] py, int n, int a, int b, int c) {
        // 允许共线（cross == 0）：凸多边形里不会有，但桥接多边形里桥的两端
        // 必然造成共线顶点，若一律拒绝它们当耳，耳切会卡在"没有任何顶点是耳"
        // 的僵局里提前放弃，结果就是大面积丢失。共线的耳面积为零，挖掉它
        // 不改变结果面积。
        float turn = cross(px[a], py[a], px[b], py[b], px[c], py[c]);
        if (turn < -EPSILON) {
            return false; // 凹角，不是耳
        }
        if (turn <= EPSILON) {
            return true; // 退化耳（三点共线）：面积为零，直接剪掉
        }
        // 对角线 a-c 必须落在多边形内部：一旦与别的边真正相交，剪掉这个"耳"
        // 就会把多边形剪成自交的，后续耳切必然卡死并丢掉大片面积。
        // 光靠下面"三角形内有没有别的顶点"不够——桥接会制造坐标完全重合的重复顶点，
        // 与 b 重合的那份拷贝所连出的边可以从三角形外侧穿过对角线，
        // 两端都不落在三角形内，顶点判定根本看不见。
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (i == a || j == a || i == c || j == c) {
                continue; // 与 a、c 相连的边和对角线共端点，不算相交
            }
            if (segmentsConflict(px[a], py[a], px[c], py[c],
                    px[i], py[i], px[j], py[j])) {
                return false;
            }
        }
        for (int i = 0; i < n; i++) {
            if (i == a || i == b || i == c) {
                continue;
            }
            // 桥接多边形里同一个坐标会出现两次（重合双边的两端），
            // 与三角形角点重合的顶点不算遮挡，否则这些角永远成不了耳
            if (samePoint(px[i], py[i], px[a], py[a])
                    || samePoint(px[i], py[i], px[b], py[b])
                    || samePoint(px[i], py[i], px[c], py[c])) {
                continue;
            }
            // 落在这条对角线上的顶点只是相切，不构成遮挡
            if (onSegment(px[a], py[a], px[c], py[c], px[i], py[i])) {
                continue;
            }
            if (pointInTriangle(px[i], py[i],
                    px[a], py[a], px[b], py[b], px[c], py[c])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断点是否落在线段 {@code (ax,ay)-(bx,by)} 上（含端点，按 {@link #EPSILON} 容差）。
     *
     * @param ax 线段起点 x 坐标
     * @param ay 线段起点 y 坐标
     * @param bx 线段终点 x 坐标
     * @param by 线段终点 y 坐标
     * @param px 被测试点的 x 坐标
     * @param py 被测试点的 y 坐标
     * @return 落在线段上返回 {@code true}
     */
    private static boolean onSegment(float ax, float ay, float bx, float by,
                                     float px, float py) {
        if (Math.abs(cross(ax, ay, bx, by, px, py)) > EPSILON) {
            return false;
        }
        return px >= Math.min(ax, bx) - EPSILON && px <= Math.max(ax, bx) + EPSILON
                && py >= Math.min(ay, by) - EPSILON && py <= Math.max(ay, by) + EPSILON;
    }

    /**
     * 判断两点是否重合（按 {@link #EPSILON} 容差）。
     *
     * @param ax 第一个点的 x 坐标
     * @param ay 第一个点的 y 坐标
     * @param bx 第二个点的 x 坐标
     * @param by 第二个点的 y 坐标
     * @return 重合返回 {@code true}
     */
    private static boolean samePoint(float ax, float ay, float bx, float by) {
        return Math.abs(ax - bx) <= EPSILON && Math.abs(ay - by) <= EPSILON;
    }

    /**
     * 判断点是否落在三角形内（含边界，退化点视为在内）。
     *
     * @param px 被测试点的 x 坐标
     * @param py 被测试点的 y 坐标
     * @param ax 三角形顶点 A 的 x 坐标
     * @param ay 三角形顶点 A 的 y 坐标
     * @param bx 三角形顶点 B 的 x 坐标
     * @param by 三角形顶点 B 的 y 坐标
     * @param cx 三角形顶点 C 的 x 坐标
     * @param cy 三角形顶点 C 的 y 坐标
     * @return 在三角形内返回 {@code true}
     */
    private static boolean pointInTriangle(float px, float py,
                                           float ax, float ay, float bx, float by,
                                           float cx, float cy) {
        float d1 = cross(ax, ay, bx, by, px, py);
        float d2 = cross(bx, by, cx, cy, px, py);
        float d3 = cross(cx, cy, ax, ay, px, py);
        // 传进来的三角形一定是逆时针的（isEar 已保证），故"三条叉积都非负"即在内部。
        // 若用"有正有负"判断，角点落在某条边延长线外侧时会被误判为在内部，
        // 导致本来合法的耳被挡住、耳切提前放弃。
        return d1 >= -EPSILON && d2 >= -EPSILON && d3 >= -EPSILON;
    }

    /**
     * 计算叉积 {@code (b-a) × (c-a)}，其正负表示转角方向。
     *
     * @param ax 点 a 的 x 坐标
     * @param ay 点 a 的 y 坐标
     * @param bx 点 b 的 x 坐标
     * @param by 点 b 的 y 坐标
     * @param cx 点 c 的 x 坐标
     * @param cy 点 c 的 y 坐标
     * @return 叉积值（两倍三角形面积，逆时针为正）
     */
    private static float cross(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * 从数组中删除指定下标的顶点，后续顶点前移。
     *
     * @param px    顶点 x 坐标
     * @param py    顶点 y 坐标
     * @param index 待删除的下标
     * @param n     删除前的顶点个数
     */
    private static void removeAt(float[] px, float[] py, int index, int n) {
        for (int i = index; i < n - 1; i++) {
            px[i] = px[i + 1];
            py[i] = py[i + 1];
        }
    }

    /**
     * 用鞋带公式计算多边形的有符号面积。
     *
     * @param px 顶点 x 坐标
     * @param py 顶点 y 坐标
     * @param n  顶点个数
     * @return 有符号面积，逆时针为正
     */
    private static float signedArea(float[] px, float[] py, int n) {
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += px[i] * py[j] - px[j] * py[i];
        }
        return sum * 0.5f;
    }

    /**
     * 原地反转顶点顺序（顺/逆时针互换）。
     *
     * @param px 顶点 x 坐标
     * @param py 顶点 y 坐标
     * @param n  顶点个数
     */
    private static void reverse(float[] px, float[] py, int n) {
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            float tx = px[i];
            px[i] = px[j];
            px[j] = tx;
            float ty = py[i];
            py[i] = py[j];
            py[j] = ty;
        }
    }

    // ------------------------------------------------------------------
    // 耳切法
    // ------------------------------------------------------------------

    /**
     * 判断逆时针多边形是否为凸多边形。
     *
     * @param px 顶点 x 坐标
     * @param py 顶点 y 坐标
     * @param n  顶点个数
     * @return 凸返回 {@code true}
     */
    private static boolean isConvex(float[] px, float[] py, int n) {
        for (int i = 0; i < n; i++) {
            int a = i, b = (i + 1) % n, c = (i + 2) % n;
            if (cross(px[a], py[a], px[b], py[b], px[c], py[c]) < -EPSILON) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回当前结果中的三角形个数。
     *
     * @return 三角形个数
     */
    public int triangleCount() {
        return triangleCount;
    }

    /**
     * 返回当前结果的紧凑副本。
     *
     * <p>每次调用都分配并复制一个新数组；热路径上请改用 {@link #rawTriangles()}。
     *
     * @return 扁平三角形数组，长度为 {@code triangleCount() * 6}
     */
    public float[] triangles() {
        return Arrays.copyOf(triangles, triangleCount * 6);
    }

    /**
     * 返回内部三角形数组本身，<strong>不复制</strong>。每 6 个 float 一个三角形
     * （{@code x0,y0,x1,y1,x2,y2}），有效数据是前 {@code triangleCount() * 6} 个 float，
     * 后面是上一次调用遗留的无效数据。
     *
     * <p>给热路径用：调用方（{@code RenderBatch} 一侧）可以直接遍历这 {@code count * 6} 个 float
     * 写顶点，省掉一次整表复制。作为代价，数组长度通常<strong>大于</strong>有效数据长度，
     * 千万不要把整个数组当成三角形列表。
     *
     * <p><strong>不得保留</strong>：这是内部缓冲，内容只在下一次 {@link #reset()} /
     * {@link #tessellate} / {@link #tessellateWithHoles} 调用之前有效，且扩容时会换一块新数组。
     * 需要稳定副本请用 {@link #triangles()}。
     *
     * @return 内部三角形数组（数组长度 ≥ {@code triangleCount() * 6}）
     */
    public float[] rawTriangles() {
        return triangles;
    }

    /** 清空上一次三角化的结果。 */
    public void reset() {
        triangleCount = 0;
    }

    /**
     * 三角化一个简单多边形。
     *
     * <p>输入<strong>始终</strong>按闭合环处理：填充的是多边形内部，首尾之间天然有边，
     * 因此没有「是否闭合」这个开关——一个被忽略的参数只会让填开放折线的调用方
     * 以为自己传的 {@code false} 起了作用。需要描边（含开放折线的端点封口）请用
     * {@link StrokeGenerator}。
     *
     * @param points 扁平顶点数组 {@code [x0,y0, x1,y1, ...]}
     * @param count  顶点个数
     */
    public void tessellate(float[] points, int count) {
        reset();
        if (count < 3) {
            return;
        }
        ensureScratch(count);
        for (int i = 0; i < count; i++) {
            scratchX[i] = points[i * 2];
            scratchY[i] = points[i * 2 + 1];
        }

        if (signedArea(scratchX, scratchY, count) < 0f) {
            reverse(scratchX, scratchY, count);
        }

        if (isConvex(scratchX, scratchY, count)) {
            for (int i = 1; i + 1 < count; i++) {
                emit(scratchX[0], scratchY[0],
                        scratchX[i], scratchY[i],
                        scratchX[i + 1], scratchY[i + 1]);
            }
            return;
        }

        earClip(scratchX, scratchY, count);
    }

    /**
     * 三角化带孔洞的多边形。
     *
     * <p>实现方式：把每个洞用一条"桥"接到当前轮廓上——即插入一对方向相反的重合边，
     * 使带洞多边形变成单个简单多边形，再走既有的耳切法。桥接点取当前轮廓上
     * 与洞的最右顶点距离最近的<b>可见</b>顶点（连线不与任何边真正相交）。
     *
     * <p>每个洞都针对<b>已经合并了先前洞</b>的轮廓重新寻找桥接点，因此支持任意多个洞。
     * 某个洞当下的桥如果会被别的洞挡住，就先合并别的洞、下一轮再处理它
     * （桥接点用"当前轮廓上最近的可见顶点"，可见性同时避开尚未合并的洞）。
     * 洞被处理时会统一取与外轮廓相反的绕向（外轮廓逆时针则洞顺时针），
     * 因此调用方传入洞的顺/逆时针都不影响结果。
     *
     * <p>洞超出外轮廓、洞之间相交等病态输入不会抛异常也不会死循环，结果是尽力而为的。
     * 注意：洞越多，桥接越容易互相挡住，病态输入下丢面积的概率越高——两三洞的常规图形
     * （环形图、条形图的挖空）不受影响。
     *
     * @param outer      外轮廓的扁平顶点数组 {@code [x0,y0, x1,y1, ...]}
     * @param outerCount 外轮廓顶点数
     * @param holes      每个洞的扁平顶点数组
     * @param holeCounts 每个洞的顶点数
     */
    public void tessellateWithHoles(float[] outer, int outerCount,
                                    float[][] holes, int[] holeCounts) {
        reset();
        if (outerCount < 3) {
            return;
        }
        if (holes == null || holes.length == 0) {
            tessellate(outer, outerCount);
            checkArea(outer, outerCount, null, null);
            return;
        }

        int holeNum = Math.min(holes.length, holeCounts.length);
        int capacity = outerCount + 8;
        for (int h = 0; h < holeNum; h++) {
            if (isUsableHole(holes, holeCounts, h)) {
                capacity += holeCounts[h] + 2; // 洞顶点 + 重合双边的终点
            }
        }

        float[] mergedX = new float[capacity];
        float[] mergedY = new float[capacity];
        int n = 0;
        for (int i = 0; i < outerCount; i++) {
            mergedX[n] = outer[i * 2];
            mergedY[n] = outer[i * 2 + 1];
            n++;
        }
        // 外轮廓统一为逆时针，洞才能以顺时针"挖去"
        if (signedArea(mergedX, mergedY, n) < 0f) {
            reverse(mergedX, mergedY, n);
        }

        // 一个洞此刻可能找不到可见的桥接点（桥会穿过别的洞），
        // 但把别的洞先合并进来以后就有了——所以反复扫描，直到没有洞能再合并为止
        boolean[] done = new boolean[holeNum];
        int pending = 0;
        for (int h = 0; h < holeNum; h++) {
            if (isUsableHole(holes, holeCounts, h)) {
                pending++;
            }
        }
        while (pending > 0) {
            boolean progress = false;
            for (int h = 0; h < holeNum; h++) {
                if (done[h] || !isUsableHole(holes, holeCounts, h)) {
                    continue;
                }
                prepareHole(holes[h], holeCounts[h]);
                int merged = bridgeHole(mergedX, mergedY, n, holeX, holeY, holeCounts[h],
                        holes, holeCounts, true);
                if (merged >= 0) {
                    n = merged;
                    done[h] = true;
                    pending--;
                    progress = true;
                }
            }
            if (!progress) {
                // 剩下的洞对着当前轮廓怎么连都会被挡住：退化成"最近的顶点"，尽力而为
                for (int h = 0; h < holeNum; h++) {
                    if (done[h] || !isUsableHole(holes, holeCounts, h)) {
                        continue;
                    }
                    prepareHole(holes[h], holeCounts[h]);
                    n = bridgeHole(mergedX, mergedY, n, holeX, holeY, holeCounts[h],
                            holes, holeCounts, false);
                    done[h] = true;
                    pending--;
                }
            }
        }

        float[] poly = new float[n * 2];
        for (int i = 0; i < n; i++) {
            poly[i * 2] = mergedX[i];
            poly[i * 2 + 1] = mergedY[i];
        }
        tessellate(poly, n);
        checkArea(outer, outerCount, holes, holeCounts);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 三角化之后自查面积：把输出三角形的总面积和"外轮廓减掉所有洞"的期望面积对一遍，
     * 偏差超过 {@link #AREA_TOLERANCE} 就在日志里告警。
     *
     * <p>只用鞋带公式扫一遍，O(n)，相对耳切法可以忽略。
     * <b>只告警不抛异常</b>：病态路径下渲染库宁可画出个大概并说清楚，
     * 也好过整帧崩掉。
     *
     * @param outer      外轮廓的扁平顶点数组
     * @param outerCount 外轮廓顶点数
     * @param holes      每个洞的扁平顶点数组，可为 {@code null}
     * @param holeCounts 每个洞的顶点数，可为 {@code null}
     */
    private void checkArea(float[] outer, int outerCount, float[][] holes, int[] holeCounts) {
        if (outer == null || outerCount < 3) {
            return;
        }
        double expected = polygonArea(outer, outerCount);
        int holeNum = 0;
        int holeLimit = holes == null ? 0 : holes.length;
        for (int h = 0; h < holeLimit && h < holeCounts.length; h++) {
            if (isUsableHole(holes, holeCounts, h)) {
                expected -= polygonArea(holes[h], holeCounts[h]);
                holeNum++;
            }
        }
        if (expected <= EPSILON) {
            return; // 退化成零面积，没有可比的基准
        }
        double actual = 0;
        for (int i = 0; i < triangleCount * 6; i += 6) {
            double x0 = triangles[i], y0 = triangles[i + 1];
            double x1 = triangles[i + 2], y1 = triangles[i + 3];
            double x2 = triangles[i + 4], y2 = triangles[i + 5];
            actual += Math.abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)) * 0.5;
        }
        if (Math.abs(actual - expected) > expected * AREA_TOLERANCE) {
            LOGGER.warn("三角化面积与输入不符：期望 {}，实际 {}（外轮廓 {} 个顶点，{} 个洞）。"
                            + "带孔洞多边形的桥接点可能被别的洞全部挡住，结果是尽力而为的，"
                            + "会少画一部分面积。",
                    expected, actual, outerCount, holeNum);
        }
    }

    /**
     * 把一个洞的顶点复制到工作区，并归一化为与外轮廓相反的绕向
     * （外轮廓逆时针，则洞取顺时针，才能被"挖去"）。
     *
     * @param hole 洞的扁平顶点数组
     * @param hc   洞的顶点数
     */
    private void prepareHole(float[] hole, int hc) {
        ensureHoleScratch(hc);
        for (int i = 0; i < hc; i++) {
            holeX[i] = hole[i * 2];
            holeY[i] = hole[i * 2 + 1];
        }
        if (signedArea(holeX, holeY, hc) > 0f) {
            reverse(holeX, holeY, hc);
        }
    }

    /**
     * 对逆时针简单多边形做耳切三角化。
     *
     * <p>遇到病态输入（自相交、共线堆积等）找不到耳时直接放弃剩余部分，
     * 保证不会死循环。
     *
     * @param px   顶点 x 坐标（会被原地破坏）
     * @param py   顶点 y 坐标（会被原地破坏）
     * @param count 顶点个数
     */
    private void earClip(float[] px, float[] py, int count) {
        int remaining = count;
        int guard = 0;
        int maxIterations = count * count + 8;

        while (remaining > 3 && guard++ < maxIterations) {
            boolean clipped = false;
            for (int i = 0; i < remaining; i++) {
                int prev = (i - 1 + remaining) % remaining;
                int next = (i + 1) % remaining;

                if (!isEar(px, py, remaining, prev, i, next)) {
                    continue;
                }
                emit(px[prev], py[prev], px[i], py[i], px[next], py[next]);
                removeAt(px, py, i, remaining);
                remaining--;
                clipped = true;
                break;
            }
            if (!clipped) {
                // 病态输入（自相交等）：放弃剩余部分，避免死循环
                break;
            }
        }

        if (remaining == 3) {
            emit(px[0], py[0], px[1], py[1], px[2], py[2]);
        }
    }

    /**
     * 按需扩容顶点工作区。
     *
     * @param count 本次要容纳的顶点个数
     */
    private void ensureScratch(int count) {
        if (count <= scratchX.length) {
            return;
        }
        scratchX = new float[count];
        scratchY = new float[count];
    }

    /**
     * 按需扩容洞顶点工作区。
     *
     * @param count 本次要容纳的洞顶点个数
     */
    private void ensureHoleScratch(int count) {
        if (count <= holeX.length) {
            return;
        }
        holeX = new float[count];
        holeY = new float[count];
    }

    /**
     * 追加一个三角形到输出缓冲。
     *
     * @param x0 第一个顶点的 x 坐标
     * @param y0 第一个顶点的 y 坐标
     * @param x1 第二个顶点的 x 坐标
     * @param y1 第二个顶点的 y 坐标
     * @param x2 第三个顶点的 x 坐标
     * @param y2 第三个顶点的 y 坐标
     */
    private void emit(float x0, float y0, float x1, float y1, float x2, float y2) {
        if (triangleCount * 6 + 6 > triangles.length) {
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
        }
        int o = triangleCount * 6;
        triangles[o] = x0;
        triangles[o + 1] = y0;
        triangles[o + 2] = x1;
        triangles[o + 3] = y1;
        triangles[o + 4] = x2;
        triangles[o + 5] = y2;
        triangleCount++;
    }
}
