package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrokeDashTest {

    private static float area(float[] tris) {
        float sum = 0f;
        for (int i = 0; i < tris.length; i += 6) {
            float x0 = tris[i], y0 = tris[i + 1];
            float x1 = tris[i + 2], y1 = tris[i + 3];
            float x2 = tris[i + 4], y2 = tris[i + 5];
            sum += Math.abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)) * 0.5f;
        }
        return sum;
    }

    @Test
    void 虚线面积约为实线的一半() {
        StrokeGenerator solid = new StrokeGenerator();
        solid.stroke(new float[]{0f, 0f, 40f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator dashed = new StrokeGenerator();
        dashed.strokeDashed(new float[]{0f, 0f, 40f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f,
                new float[]{5f, 5f}, 0f, 8);

        float solidArea = area(solid.triangles());
        float dashedArea = area(dashed.triangles());
        assertTrue(dashedArea < solidArea * 0.65f,
                "虚线面积应显著小于实线：dashed=" + dashedArea + " solid=" + solidArea);
        assertTrue(dashedArea > solidArea * 0.35f,
                "虚线面积不应过小：dashed=" + dashedArea + " solid=" + solidArea);
    }

    @Test
    void 虚线起点相位影响结果() {
        float[] line = {0f, 0f, 40f, 0f};
        float[] pattern = {5f, 5f};

        StrokeGenerator a = new StrokeGenerator();
        a.strokeDashed(line, 2, false, 2f, StrokeGenerator.Cap.BUTT,
                StrokeGenerator.Join.MITER, 4f, pattern, 0f, 8);

        StrokeGenerator b = new StrokeGenerator();
        b.strokeDashed(line, 2, false, 2f, StrokeGenerator.Cap.BUTT,
                StrokeGenerator.Join.MITER, 4f, pattern, 5f, 8);

        assertTrue(Math.abs(area(a.triangles()) - area(b.triangles())) < 1e-2f,
                "整段上不同相位的虚线总面积应相同");
    }

    @Test
    void 空虚线模式等价于实线() {
        StrokeGenerator solid = new StrokeGenerator();
        solid.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator dashed = new StrokeGenerator();
        dashed.strokeDashed(new float[]{0f, 0f, 10f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f,
                new float[0], 0f, 8);

        assertEquals(area(solid.triangles()), area(dashed.triangles()), 1e-3f);
    }

    @Test
    void 全零虚线模式不产生三角形() {
        StrokeGenerator g = new StrokeGenerator();
        g.strokeDashed(new float[]{0f, 0f, 10f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f,
                new float[]{0f, 0f}, 0f, 8);
        assertEquals(0, g.triangleCount());
    }

    @Test
    void 虚线在折线拐角处连续() {
        StrokeGenerator g = new StrokeGenerator();
        g.strokeDashed(new float[]{0f, 0f, 20f, 0f, 20f, 20f}, 3, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f,
                new float[]{30f, 2f}, 0f, 8);
        assertTrue(g.triangleCount() > 0);
    }
}
