package com.bingbaihanji.xuan.geom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class StrokeDashTest {

    /**
     * ★ **每一项都小于那个阈值**的模式（而总和恰好大于它）必须"什么都不画"，
     * 而**不是死循环**。
     *
     * <h2>为什么这条测试带 {@link Timeout}</h2>
     * <p>它在修复之前的行为是**挂死**，不是失败——`strokeDashed` 内层那条
     * "本格太短、只推进 patternIndex"的分支会永远转下去，`cursor` 一步不动。
     * 不带超时的话它会把整个 `mvn test` 吊住（而"测试挂住"比"测试失败"难查得多：
     * 没有栈、没有读数、只有一个不动的进程）。**5 秒足够**——正常路径是微秒级的。
     *
     * <h2>为什么这个输入是可达的</h2>
     * <p>模式总和 `1.2e-6 > 1e-6`，所以那条"全零模式"的早退**不触发**；
     * 而每一项都 `<= 1e-6`，于是循环里每一格都走"太短"那条分支。
     * 2026-09-28 之前 `Gc` 不暴露虚线，唯一的调用方是图表的十字虚线、dashLength
     * 由配置层强制为正 ⇒ 这一支**不可达**；接上公开 API 之后它就可达了
     * （`gc.dashPattern = floatArrayOf(6e-7f, 6e-7f)`）。
     */
    @Test
    @Timeout(5)
    void 每一项都低于阈值的模式不产生三角形而不是死循环() {
        StrokeGenerator g = new StrokeGenerator();
        g.strokeDashed(new float[]{0f, 0f, 100f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f,
                new float[]{6e-7f, 6e-7f}, 0f, 8);

        assertEquals(0, g.triangleCount(),
                "一格实线都发不出来 ⇒ 应当是空的，而不是卡在那儿");
    }

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
