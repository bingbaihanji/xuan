package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Path} 的单元测试：验证命令记录、坐标存取与容量复用行为。
 */
class PathTest {

    @Test
    void 新建路径为空() {
        Path p = new Path();
        assertEquals(0, p.commandCount());
        assertTrue(p.isEmpty());
    }

    @Test
    void 记录命令类型与坐标() {
        Path p = new Path();
        p.moveTo(1f, 2f);
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
        p.cubicTo(1f, 1f, 2f, 2f, 3f, 3f);
        assertEquals(Path.Type.CUBIC_TO, p.commandType(1));
        assertEquals(3, p.pointCount(1));
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
}
