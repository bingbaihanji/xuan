package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlattenerTest {

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
        p.moveTo(0f, 0f).cubicTo(0f, 100f, 100f, 100f, 100f, 0f);
        Flattener f = new Flattener();
        f.flatten(p, 1f);
        assertTrue(f.pointCount() > 5, "曲线应细分成多个点，实际 " + f.pointCount());
        assertEquals(0f, f.x(0), 1e-4f);
        assertEquals(100f, f.x(f.pointCount() - 1), 1e-4f);
    }

    @Test
    void 缩放越大细分越密() {
        Path p = new Path();
        p.moveTo(0f, 0f).cubicTo(0f, 100f, 100f, 100f, 100f, 0f);

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
        p.moveTo(0f, 0f).quadTo(50f, 100f, 100f, 0f);
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
}
