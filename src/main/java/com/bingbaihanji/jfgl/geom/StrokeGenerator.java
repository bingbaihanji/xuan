package com.bingbaihanji.jfgl.geom;

import java.util.Arrays;

/**
 * 把折线转成描边轮廓的三角形列表。
 *
 * <p>输出格式与 {@link Tessellator} 一致：每 6 个 float 一个三角形，
 * 依次为 {@code x0,y0,x1,y1,x2,y2}。生成结果是覆盖描边面积的一组三角形，
 * 调用方按普通三角形批次上传即可，无需 GL 线图元。
 *
 * <p><strong>关于非均匀缩放</strong>：本类在<strong>局部空间</strong>生成轮廓，
 * 由调用方在烘焙变换时处理缩放。因此 {@code scale(2,1)} 下圆的描边会正确变成椭圆环，
 * 而不是一段宽度不均的环带。本类不做任何缩放补偿。
 *
 * <p><strong>已知限制</strong>：凹角处相邻段的偏移轮廓会自相交，产生重叠三角形。
 * 配合预乘 alpha，不透明描边无可见影响；半透明描边会出现颜色叠加。
 *
 * <p><strong>零长度段</strong>：长度小于 {@code 1e-6} 的段会被跳过（不产生三角形，
 * 也不会出现除零），其两侧的接头与端点会自动落到最近的有效段上。但重复顶点过多时
 * （整条折线退化）不会产生任何三角形。
 *
 * <p>本类不依赖 {@code gl} 包，可脱离 GL 上下文进行单元测试。
 */
public final class StrokeGenerator {

    /** 端点样式。 */
    public enum Cap {

        /** 平端点：轮廓恰好止于线段端点，不向外延伸。 */
        BUTT,

        /** 圆端点：以端点为圆心补一个半径等于半线宽的半圆。 */
        ROUND,

        /** 方端点：在端点处沿切线方向向外延伸半个线宽。 */
        SQUARE
    }

    /** 接头样式。 */
    public enum Join {

        /** 尖角接头：延伸到两条偏移线的交点，超过 miter limit 时回退为 {@link #BEVEL}。 */
        MITER,

        /** 圆角接头：用半径等于半线宽的圆弧补角。 */
        ROUND,

        /** 斜接接头：用连接两条偏移线的弦切掉尖角。 */
        BEVEL
    }

    /** 未指定细分段数时，圆端点与圆角接头使用的默认段数。 */
    private static final int DEFAULT_ROUND_SEGMENTS = 8;

    /** 输出三角形顶点数组，布局：每个三角形 6 个 float（x0,y0,x1,y1,x2,y2）。 */
    private float[] triangles = new float[3 * 6 * 4];

    /** 已写入的三角形数量。 */
    private int triangleCount = 0;

    /**
     * 返回已生成的三角形数量。
     *
     * @return 三角形个数
     */
    public int triangleCount() {
        return triangleCount;
    }

    /**
     * 返回已生成三角形的顶点副本。
     *
     * <p>返回值只包含有效部分，长度为 {@code triangleCount() * 6}。
     *
     * @return 顶点数组副本，布局为每个三角形 6 个 float
     */
    public float[] triangles() {
        return Arrays.copyOf(triangles, triangleCount * 6);
    }

    /**
     * 清空已生成的三角形，保留已分配的数组容量。
     *
     * <p>与 {@link #stroke} 不同，本方法只把计数归零，因此实例可在热路径上反复复用。
     */
    public void reset() {
        triangleCount = 0;
    }

    /**
     * 使用默认的圆角细分段数生成描边轮廓。
     *
     * @param points     扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count      顶点个数
     * @param closed     是否闭合
     * @param width      线宽（局部空间单位）
     * @param cap        端点样式
     * @param join       接头样式
     * @param miterLimit miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit) {
        stroke(points, count, closed, width, cap, join, miterLimit, DEFAULT_ROUND_SEGMENTS);
    }

    /**
     * 生成描边轮廓，结果写入本实例（会清空上一次的结果）。
     *
     * @param points        扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count         顶点个数
     * @param closed        是否闭合；闭合路径不生成端点封口，并在首尾之间补接头
     * @param width         线宽（局部空间单位）
     * @param cap           端点样式
     * @param join          接头样式
     * @param miterLimit    miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     * @param roundSegments 圆端点与圆角接头的细分段数
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit, int roundSegments) {
        reset();
        if (count < 2 || width <= 0f) {
            return;
        }
        float half = width * 0.5f;
        int segmentCount = closed ? count : count - 1;
        if (segmentCount < 1) {
            return;
        }

        // 上一段有效段的终点与方向，用于补接头（重复顶点会被跳过，接头要落到有效段之间）
        float prevDx = 0f, prevDy = 0f;
        boolean hasPrev = false;
        boolean capStartDone = false;
        float lastX = 0f, lastY = 0f, lastDx = 0f, lastDy = 0f;

        for (int i = 0; i < segmentCount; i++) {
            int a = i;
            int b = (i + 1) % count;
            float ax = points[a * 2], ay = points[a * 2 + 1];
            float bx = points[b * 2], by = points[b * 2 + 1];
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) {
                continue;
            }
            float nx = -dy / len * half;
            float ny = dx / len * half;

            // 与上一有效段之间补接头
            if (hasPrev) {
                emitJoin(ax, ay, prevDx, prevDy, dx, dy, half, join, miterLimit, roundSegments);
            }
            // 起点封口落在第一段有效段上
            if (!closed && !capStartDone) {
                emitCap(ax, ay, -dx, -dy, half, cap, roundSegments);
                capStartDone = true;
            }

            // 每段是一个四边形（两个三角形）
            emitQuad(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny);

            prevDx = dx;
            prevDy = dy;
            hasPrev = true;
            lastX = bx;
            lastY = by;
            lastDx = dx;
            lastDy = dy;
        }

        if (closed && hasPrev) {
            // 闭合路径：最后一段与第一段有效段之间也要补接头
            int first = firstValidSegment(points, count, segmentCount);
            if (first >= 0) {
                int b = (first + 1) % count;
                emitJoin(points[first * 2], points[first * 2 + 1], prevDx, prevDy,
                        points[b * 2] - points[first * 2], points[b * 2 + 1] - points[first * 2 + 1],
                        half, join, miterLimit, roundSegments);
            }
        } else if (capStartDone) {
            // 终点封口落在最后一段有效段的终点上
            emitCap(lastX, lastY, lastDx, lastDy, half, cap, roundSegments);
        }
    }

    /**
     * 找到第一个非退化段的下标，用于闭合路径的首尾接头。
     *
     * @return 第一个长度不为零的段下标；全部退化时返回 {@code -1}
     */
    private static int firstValidSegment(float[] points, int count, int segmentCount) {
        for (int i = 0; i < segmentCount; i++) {
            int b = (i + 1) % count;
            float dx = points[b * 2] - points[i * 2];
            float dy = points[b * 2 + 1] - points[i * 2 + 1];
            if (dx * dx + dy * dy > 1e-12f) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 在折线拐点处补出接头三角形，填掉相邻两段偏移轮廓之间的缝隙。
     *
     * @param px            拐点坐标 x
     * @param py            拐点坐标 y
     * @param d1x           入段方向向量 x（未归一化）
     * @param d1y           入段方向向量 y（未归一化）
     * @param d2x           出段方向向量 x（未归一化）
     * @param d2y           出段方向向量 y（未归一化）
     * @param half          半线宽
     * @param join          接头样式
     * @param miterLimit    miter 阈值（相对于半线宽）
     * @param roundSegments 圆角细分段数
     */
    private void emitJoin(float px, float py, float d1x, float d1y,
                          float d2x, float d2y, float half,
                          Join join, float miterLimit, int roundSegments) {
        float l1 = (float) Math.sqrt(d1x * d1x + d1y * d1y);
        float l2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
        if (l1 < 1e-6f || l2 < 1e-6f) {
            return;
        }
        float u1x = d1x / l1, u1y = d1y / l1;
        float u2x = d2x / l2, u2y = d2y / l2;

        float cross = u1x * u2y - u1y * u2x;
        float dot = u1x * u2x + u1y * u2y;
        if (Math.abs(cross) < 1e-6f && dot > 0f) {
            return; // 共线同向，无缝隙
        }

        // 缝隙在凸侧：左转（cross > 0）时凸侧在行进方向右侧，右转时在左侧，
        // 即偏移法线取 (-u.y, u.x) 的反方向（左转）或正方向（右转）。
        boolean leftTurn = cross > 0f;
        float s = leftTurn ? -1f : 1f;
        float o1x = -u1y * half * s, o1y = u1x * half * s;
        float o2x = -u2y * half * s, o2y = u2x * half * s;

        if (join == Join.BEVEL || join == Join.ROUND) {
            emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y);
            if (join == Join.ROUND) {
                // 从 o1 方向扫到 o2 方向，扫过角度即转向角
                float start = (float) Math.atan2(o1y, o1x);
                emitArc(px, py, half, start, (float) Math.atan2(cross, dot), roundSegments);
            }
            return;
        }

        // MITER：求两条偏移直线的交点（p + o1 + t*u1 与 p + o2 + t2*u2）
        float wx = o2x - o1x, wy = o2y - o1y;
        float t = (wx * u2y - wy * u2x) / cross;
        float mx = px + o1x + u1x * t;
        float my = py + o1y + u1y * t;
        float miterLength = (float) Math.sqrt((mx - px) * (mx - px) + (my - py) * (my - py));

        if (miterLength > miterLimit * half) {
            // 超过 miter limit，回退为 bevel
            emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y);
            return;
        }
        emitTriangle(px + o1x, py + o1y, mx, my, px + o2x, py + o2y);
    }

    /**
     * 在折线端点处补出封口三角形。
     *
     * @param px            端点坐标 x
     * @param py            端点坐标 y
     * @param dx            由端点指向线段内部的方向向量 x（未归一化）
     * @param dy            由端点指向线段内部的方向向量 y（未归一化）
     * @param half          半线宽
     * @param cap           端点样式
     * @param roundSegments 圆端点细分段数
     */
    private void emitCap(float px, float py, float dx, float dy, float half,
                         Cap cap, int roundSegments) {
        if (cap == Cap.BUTT) {
            return;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) {
            return;
        }
        float ux = dx / len, uy = dy / len;
        float nx = -uy * half, ny = ux * half;

        if (cap == Cap.SQUARE) {
            emitQuad(px + nx, py + ny, px + nx + ux * half, py + ny + uy * half,
                    px - nx + ux * half, py - ny + uy * half, px - nx, py - ny);
            return;
        }

        // ROUND：以端点为中心、朝外（沿 -u 方向）的半圆扇，从 u 逆转 90° 扫到顺转 90°
        float start = (float) Math.atan2(uy, ux) - (float) (Math.PI / 2);
        emitArc(px, py, half, start, (float) Math.PI, roundSegments);
    }

    /**
     * 生成以 {@code (cx,cy)} 为圆心、{@code radius} 为半径的圆弧三角扇。
     *
     * @param cx         圆心 x
     * @param cy         圆心 y
     * @param radius     半径
     * @param startAngle 起始角（弧度）
     * @param sweep      扫过角度（弧度，可为负）
     * @param segments   细分段数
     */
    private void emitArc(float cx, float cy, float radius, float startAngle, float sweep, int segments) {
        for (int i = 0; i < segments; i++) {
            float a0 = startAngle + sweep * i / segments;
            float a1 = startAngle + sweep * (i + 1) / segments;
            emitTriangle(cx, cy,
                    cx + (float) Math.cos(a0) * radius, cy + (float) Math.sin(a0) * radius,
                    cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius);
        }
    }

    /**
     * 生成一个四边形的两个三角形，顶点按 {@code p0 → p1 → p2 → p3} 顺序环绕。
     */
    private void emitQuad(float x0, float y0, float x1, float y1,
                          float x2, float y2, float x3, float y3) {
        emitTriangle(x0, y0, x1, y1, x2, y2);
        emitTriangle(x2, y2, x3, y3, x0, y0);
    }

    /**
     * 追加一个三角形，必要时扩容内部数组。
     */
    private void emitTriangle(float x0, float y0, float x1, float y1,
                              float x2, float y2) {
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
