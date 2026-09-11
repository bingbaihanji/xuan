package com.bingbaihanji.jfgl.geom;

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
 * <p>本类不含任何 GL 依赖，可脱离窗口做单元测试。实例可复用：
 * 每次 {@link #tessellate} 都会先清空上一次的结果。
 */
public final class Tessellator {

    /** 斜率比较与面积判断的容差。 */
    private static final float EPSILON = 1e-6f;

    /** 输出三角形缓冲，每 6 个 float 一个三角形，容量不足时翻倍。 */
    private float[] triangles = new float[3 * 6 * 4];

    /** 当前已产出的三角形个数。 */
    private int triangleCount = 0;

    /** 顶点 x 坐标工作区，避免每次调用重新分配。 */
    private float[] scratchX = new float[64];

    /** 顶点 y 坐标工作区，避免每次调用重新分配。 */
    private float[] scratchY = new float[64];

    /** 创建三角化器。 */
    public Tessellator() {
        // 使用默认初始容量
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
     * @return 扁平三角形数组，长度为 {@code triangleCount() * 6}
     */
    public float[] triangles() {
        return Arrays.copyOf(triangles, triangleCount * 6);
    }

    /** 清空上一次三角化的结果。 */
    public void reset() {
        triangleCount = 0;
    }

    /**
     * 三角化一个简单多边形。
     *
     * @param points 扁平顶点数组 {@code [x0,y0, x1,y1, ...]}
     * @param count  顶点个数
     * @param closed 输入是否为闭合环（当前实现忽略此参数，始终按闭合环处理）
     */
    public void tessellate(float[] points, int count, boolean closed) {
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

    // ------------------------------------------------------------------
    // 耳切法
    // ------------------------------------------------------------------

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
        if (cross(px[a], py[a], px[b], py[b], px[c], py[c]) <= EPSILON) {
            return false; // 凹角或退化，不是耳
        }
        for (int i = 0; i < n; i++) {
            if (i == a || i == b || i == c) {
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
        boolean hasNeg = d1 < -EPSILON || d2 < -EPSILON || d3 < -EPSILON;
        boolean hasPos = d1 > EPSILON || d2 > EPSILON || d3 > EPSILON;
        return !(hasNeg && hasPos);
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

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

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
            float tx = px[i]; px[i] = px[j]; px[j] = tx;
            float ty = py[i]; py[i] = py[j]; py[j] = ty;
        }
    }

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
        triangles[o] = x0;      triangles[o + 1] = y0;
        triangles[o + 2] = x1;  triangles[o + 3] = y1;
        triangles[o + 4] = x2;  triangles[o + 5] = y2;
        triangleCount++;
    }
}
