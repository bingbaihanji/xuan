package com.bingbaihanji.xuan.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenerTest {

    /** 返回点 ({@code px}, {@code py}) 到折线所有线段的最短距离。 */
    private static float distanceToPolyline(Flattener f, float px, float py) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i + 1 < f.pointCount(); i++) {
            float ax = f.x(i), ay = f.y(i);
            float dx = f.x(i + 1) - ax, dy = f.y(i + 1) - ay;
            float len2 = dx * dx + dy * dy;
            float u = len2 < 1e-12f ? 0f : ((px - ax) * dx + (py - ay) * dy) / len2;
            u = Math.max(0f, Math.min(1f, u));
            best = Math.min(best, (float) Math.hypot(px - (ax + u * dx), py - (ay + u * dy)));
        }
        return best;
    }

    @Test
    void 直线段原样输出() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(10f, 0f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertEquals(2, f.pointCount());
        assertEquals(0f, f.x(0), 1e-4f);
        assertEquals(10f, f.x(1), 1e-4f);
    }

    @Test
    void 三次贝塞尔被细分成多个点() {
        Path p = new Path();
        p.moveTo(0f, 0f).bezierCurveTo(0f, 100f, 100f, 100f, 100f, 0f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertTrue(f.pointCount() > 5, "曲线应细分成多个点，实际 " + f.pointCount());
        assertEquals(0f, f.x(0), 1e-4f);
        assertEquals(100f, f.x(f.pointCount() - 1), 1e-4f);
    }

    @Test
    void 缩放越大细分越密() {
        Path p = new Path();
        p.moveTo(0f, 0f).bezierCurveTo(0f, 100f, 100f, 100f, 100f, 0f);

        Flattener coarse = new Flattener();
        coarse.flatten(p, 1f);

        Flattener fine = new Flattener();
        fine.flatten(p, 16f);

        assertTrue(fine.pointCount() > coarse.pointCount(),
                "scale=16 应比 scale=1 产生更多点：fine=" + fine.pointCount()
                        + " coarse=" + coarse.pointCount());
    }

    @Test
    void 曲线端点被精确保留() {
        Path p = new Path();
        p.moveTo(0f, 0f).quadraticCurveTo(50f, 100f, 100f, 0f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        int last = f.pointCount() - 1;
        assertEquals(100f, f.x(last), 1e-3f);
        assertEquals(0f, f.y(last), 1e-3f);
    }

    @Test
    void reset清空点数() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(1f, 1f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        f.reset();
        assertEquals(0, f.pointCount());
    }

    @Test
    void 可查询子路径起点() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(10f, 0f).moveTo(50f, 50f).lineTo(60f, 50f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertEquals(2, f.subPathCount());
        assertEquals(0, f.subPathStart(0));
        assertEquals(2, f.subPathStart(1));
    }

    @Test
    void 显式容差生效() {
        Path p = new Path();
        p.moveTo(0f, 0f).bezierCurveTo(0f, 100f, 100f, 100f, 100f, 0f);

        Flattener loose = new Flattener();
        loose.flatten(p, 1f, 64f);

        Flattener strict = new Flattener();
        strict.flatten(p, 1f, 0.25f);

        assertTrue(loose.pointCount() < strict.pointCount(),
                "容差 64 应比容差 0.25 产生更少的点：loose=" + loose.pointCount()
                        + " strict=" + strict.pointCount());
    }

    @Test
    void 平坦化偏差不超过设备像素容差() {
        Path p = new Path();
        p.moveTo(0f, 0f).bezierCurveTo(0f, 100f, 100f, 100f, 100f, 0f);

        final float scale = 16f;
        final float tolerance = 0.25f;
        Flattener f = new Flattener();
        f.flatten(p, scale, tolerance);

        // 独立采样真实曲线（不复用生产代码），逐点量到输出折线的最近距离，
        // 取最大值换算成设备像素后与容差比较。这是该类的核心契约。
        float worstDevice = 0f;
        for (int s = 0; s <= 4000; s++) {
            float t = s / 4000f;
            float u = 1f - t;
            float cx = 3f * u * t * t * 100f + t * t * t * 100f;
            float cy = 3f * u * u * t * 100f + 3f * u * t * t * 100f;
            float d = distanceToPolyline(f, cx, cy) * scale;
            if (d > worstDevice) {
                worstDevice = d;
            }
        }

        assertTrue(worstDevice <= tolerance,
                "最大偏差应不超过 " + tolerance + " 设备像素，实际 " + worstDevice);
    }

    @Test
    void 闭合子路径回到自身起点() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(10f, 0f).lineTo(10f, 10f).close();
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertEquals(4, f.pointCount());
        assertEquals(0f, f.x(3), 1e-4f);
        assertEquals(0f, f.y(3), 1e-4f);
    }

    @Test
    void 多子路径各自闭合到自身起点() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(10f, 0f).close()
                .moveTo(50f, 50f).lineTo(60f, 50f).close();
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertEquals(6, f.pointCount());
        assertEquals(2, f.subPathCount());
        assertEquals(0, f.subPathStart(0));
        assertEquals(3, f.subPathStart(1));
        // 第二个子路径的收尾点必须是它自己的起点，而不是上一个子路径的起点
        assertEquals(50f, f.x(5), 1e-4f);
        assertEquals(50f, f.y(5), 1e-4f);
    }

    @Test
    void close在不产生线段时不多加点() {
        // 已在起点上再次 close：不应追加重复点
        Flattener repeated = new Flattener();
        repeated.flatten(new Path().moveTo(0f, 0f).lineTo(10f, 0f).close().close(), 1f);
        assertEquals(3, repeated.pointCount());

        // 从未 moveTo 过（hasSubPath 为假）：close 不应产生任何点
        Flattener orphan = new Flattener();
        orphan.flatten(new Path().close(), 1f);
        assertEquals(0, orphan.pointCount());
        assertEquals(0, orphan.subPathCount());
    }

    @Test
    void copyPointsTo写出扁平交错数组() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(10f, 0f).lineTo(10f, 5f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);

        float[] dst = new float[64];
        int written = f.copyPointsTo(dst);
        assertEquals(f.pointCount() * 2, written, "返回的应是写入的 float 个数");
        for (int i = 0; i < f.pointCount(); i++) {
            assertEquals(f.x(i), dst[i * 2], 0f, "第 " + i + " 个点的 x 应逐位一致");
            assertEquals(f.y(i), dst[i * 2 + 1], 0f, "第 " + i + " 个点的 y 应逐位一致");
        }

        // 调用方复用同一块缓冲区摊平下一条路径：只覆盖前 4 个 float，不重新分配
        Path q = new Path();
        q.moveTo(1f, 2f).lineTo(3f, 4f);
        f.flatten(q, 1f);
        assertEquals(4, f.copyPointsTo(dst));
        assertEquals(1f, dst[0], 0f);
        assertEquals(2f, dst[1], 0f);
        assertEquals(3f, dst[2], 0f);
        assertEquals(4f, dst[3], 0f);
    }

    @Test
    void copyPointsTo目标数组太小时抛出异常() {
        Path p = new Path();
        p.moveTo(0f, 0f).lineTo(10f, 0f).lineTo(10f, 5f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertThrows(IllegalArgumentException.class, () -> f.copyPointsTo(new float[5]));
    }
}
