package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Path} 的单元测试：验证命令记录、坐标存取、容量增长与实例复用行为。
 */
class PathTest {

    @Test
    void 新建路径为空() {
        Path p = new Path();
        assertTrue(p.isEmpty());
    }

    @Test
    void 记录命令类型与坐标() {
        Path p = new Path();
        p.moveTo(1f, 2f);
        assertFalse(p.isEmpty(), "记录一条命令后路径不再为空");
        p.lineTo(3f, 4f);

        assertEquals(2, p.commandCount());
        assertEquals(Path.Type.MOVE_TO, p.commandType(0));
        assertEquals(1f, p.commandX(0, 0), 1e-4f);
        assertEquals(2f, p.commandY(0, 0), 1e-4f);
        assertEquals(Path.Type.LINE_TO, p.commandType(1));
        assertEquals(3f, p.commandX(1, 0), 1e-4f);
    }

    @Test
    void reset清空但不释放容量() {
        Path p = new Path();
        p.moveTo(1f, 1f);
        int capBefore = p.arrayCapacity();
        p.reset();
        assertEquals(0, p.commandCount());
        assertEquals(capBefore, p.arrayCapacity(), "reset 不应重新分配数组");
    }

    @Test
    void 二次贝塞尔记录两个点() {
        Path p = new Path();
        p.moveTo(0f, 0f);
        p.quadTo(1f, 2f, 3f, 4f);
        assertEquals(Path.Type.QUAD_TO, p.commandType(1));
        assertEquals(1f, p.commandX(1, 0), 1e-4f);
        assertEquals(2f, p.commandY(1, 0), 1e-4f);
        assertEquals(3f, p.commandX(1, 1), 1e-4f);
        assertEquals(4f, p.commandY(1, 1), 1e-4f);
    }

    @Test
    void 三次贝塞尔记录三个点() {
        Path p = new Path();
        p.moveTo(0f, 0f);
        p.cubicTo(1f, 2f, 3f, 4f, 5f, 6f);
        assertEquals(Path.Type.CUBIC_TO, p.commandType(1));
        assertEquals(3, p.pointCount(1));
        assertEquals(1f, p.commandX(1, 0), 1e-4f);
        assertEquals(2f, p.commandY(1, 0), 1e-4f);
        assertEquals(3f, p.commandX(1, 1), 1e-4f);
        assertEquals(4f, p.commandY(1, 1), 1e-4f);
        assertEquals(5f, p.commandX(1, 2), 1e-4f);
        assertEquals(6f, p.commandY(1, 2), 1e-4f);
    }

    @Test
    void close记录无点命令() {
        Path p = new Path();
        p.moveTo(0f, 0f);
        p.lineTo(1f, 0f);
        p.close();
        assertEquals(Path.Type.CLOSE, p.commandType(2));
        assertEquals(0, p.pointCount(2));
    }

    @Test
    void 命令数超过初始容量时扩容并保留已有数据() {
        Path p = new Path();
        // 前 39 条命令以 4 为周期混合四种命令类型，命令数超过初始容量 16，必然触发扩容。
        for (int i = 0; i < 39; i++) {
            switch (i % 4) {
                case 0 -> p.moveTo(i * 10f + 1f, i * 10f + 2f);
                case 1 -> p.quadTo(i * 10f + 1f, i * 10f + 2f, i * 10f + 3f, i * 10f + 4f);
                case 2 -> p.cubicTo(i * 10f + 1f, i * 10f + 2f, i * 10f + 3f, i * 10f + 4f,
                        i * 10f + 5f, i * 10f + 6f);
                default -> p.close();
            }
        }
        // 第 40 条命令固定为三次贝塞尔，使末尾命令携带全部 3 个点，六个坐标都能被核对。
        p.cubicTo(391f, 392f, 393f, 394f, 395f, 396f);

        assertEquals(40, p.commandCount());
        assertTrue(p.arrayCapacity() > 16, "命令数超过 16 后应已扩容");

        // 扩容后首条命令：i = 0 时 moveTo(1, 2)
        assertEquals(Path.Type.MOVE_TO, p.commandType(0));
        assertEquals(1, p.pointCount(0));
        assertEquals(1f, p.commandX(0, 0), 1e-4f);
        assertEquals(2f, p.commandY(0, 0), 1e-4f);

        // 中间命令 20：20 % 4 == 0，moveTo(201, 202)
        assertEquals(Path.Type.MOVE_TO, p.commandType(20));
        assertEquals(1, p.pointCount(20));
        assertEquals(201f, p.commandX(20, 0), 1e-4f);
        assertEquals(202f, p.commandY(20, 0), 1e-4f);

        // 末条命令 39：三次贝塞尔，六个坐标全部核对（base+4/base+5 是唯一在此被校验的写入路径）
        assertEquals(Path.Type.CUBIC_TO, p.commandType(39));
        assertEquals(3, p.pointCount(39));
        assertEquals(391f, p.commandX(39, 0), 1e-4f);
        assertEquals(392f, p.commandY(39, 0), 1e-4f);
        assertEquals(393f, p.commandX(39, 1), 1e-4f);
        assertEquals(394f, p.commandY(39, 1), 1e-4f);
        assertEquals(395f, p.commandX(39, 2), 1e-4f);
        assertEquals(396f, p.commandY(39, 2), 1e-4f);
    }

    @Test
    void reset后可复用并完整覆盖上一次填充的数据() {
        Path p = new Path();
        // 第一次填充：命令 0 是单点的 MOVE_TO
        p.moveTo(1f, 1f);
        p.lineTo(2f, 2f);
        p.close();

        p.reset();
        // 第二次填充：命令 0 换成三点的 CUBIC_TO，类型、点数与六个坐标全部应被覆盖
        p.cubicTo(21f, 22f, 23f, 24f, 25f, 26f);

        assertEquals(1, p.commandCount());
        assertEquals(Path.Type.CUBIC_TO, p.commandType(0));
        assertEquals(3, p.pointCount(0));
        assertEquals(21f, p.commandX(0, 0), 1e-4f);
        assertEquals(22f, p.commandY(0, 0), 1e-4f);
        assertEquals(23f, p.commandX(0, 1), 1e-4f);
        assertEquals(24f, p.commandY(0, 1), 1e-4f);
        assertEquals(25f, p.commandX(0, 2), 1e-4f);
        assertEquals(26f, p.commandY(0, 2), 1e-4f);
    }
}
