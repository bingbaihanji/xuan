package com.bingbaihanji.jfgl.renderer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PickRegistryTest {

    @Test
    void 第一个分配的ID不是0() {
        PickRegistry r = new PickRegistry();
        assertEquals(1, r.register("a"), "ID 从 1 开始，0 恒定保留给「什么都没命中」");
    }

    @Test
    void 分配的ID互不重复() {
        PickRegistry r = new PickRegistry();
        int a = r.register("a");
        int b = r.register("b");
        int c = r.register("c");
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertNotEquals(a, c);
    }

    @Test
    void 解析已注册的ID返回原对象() {
        PickRegistry r = new PickRegistry();
        Object payload = new Object();
        int id = r.register(payload);
        assertSame(payload, r.resolve(id));
    }

    @Test
    void 未注册的ID解析为null() {
        PickRegistry r = new PickRegistry();
        assertNull(r.resolve(12345));
    }

    @Test
    void 零号ID永远解析为null() {
        PickRegistry r = new PickRegistry();
        r.register("a");
        assertNull(r.resolve(0), "0 是「未命中」的保留值，任何人都不该注册到它");
    }

    @Test
    void 注销后解析为null() {
        PickRegistry r = new PickRegistry();
        int id = r.register("a");
        r.unregister(id);
        assertNull(r.resolve(id));
    }

    @Test
    void 注销的ID会被复用() {
        PickRegistry r = new PickRegistry();
        int id = r.register("a");
        r.unregister(id);
        int reused = r.register("b");
        assertEquals(id, reused, "空闲的 ID 应被回收复用，否则长生命周期应用会磨光 ID 空间");
        assertSame("b", r.resolve(reused));
    }

    @Test
    void 注销不存在的ID是无副作用的() {
        PickRegistry r = new PickRegistry();
        int id = r.register("a");
        r.unregister(999);
        assertSame("a", r.resolve(id), "注销一个没注册过的 ID 不该影响已有映射");
        r.unregister(id);
        r.unregister(id);   // 重复注销同样无害
        assertNull(r.resolve(id));
    }

    @Test
    void clear清空全部映射并保留复用能力() {
        PickRegistry r = new PickRegistry();
        r.register("a");
        r.register("b");
        r.clear();
        assertEquals(0, r.size());
        assertNull(r.resolve(1));

        int id = r.register("c");
        assertTrue(id > 0);
        assertSame("c", r.resolve(id));
    }

    @Test
    void 允许空payload注册() {
        PickRegistry r = new PickRegistry();
        int id = r.register(null);
        assertTrue(id > 0, "payload 为 null 也应拿到有效 ID");
        assertNull(r.resolve(id), "解析出来是 null，但 ID 本身是有效的");
    }

    @Test
    void 空payload的ID注销后同样能回收() {
        // 这条专门锁住 unregister 的判据：单看 remove() 的返回值，null 载荷
        // 与「没注册过」无法区分，那个 ID 就再也回收不了（静默泄漏 ID 空间）。
        // 注意 remove() 必须先判后删——先 remove 再 containsKey 同样恒为 false。
        PickRegistry r = new PickRegistry();
        int id = r.register(null);
        r.unregister(id);
        assertEquals(id, r.register("b"), "空载荷注销后 ID 也应回到空闲表等待复用");
        assertSame("b", r.resolve(id));
    }

    @Test
    void ID耗尽时抛异常而不是环绕() {
        // 构造一个只剩 2 个可用 ID 的注册表，验证到顶时的行为。
        // 上界是「含」的：maxId = 2 时可用 ID 恰为 1、2 两个。
        PickRegistry r = new PickRegistry(2);
        int a = r.register("a");     // 1
        int b = r.register("b");     // 2
        assertThrows(IllegalStateException.class, () -> r.register("c"),
                "ID 环绕会让新对象复用仍被引用的 ID，拾取到毫不相干的对象——必须炸");
        assertEquals(1, a);
        assertEquals(2, b);
    }

    @Test
    void 到顶后注销再注册仍可用() {
        PickRegistry r = new PickRegistry(2);   // 同上：1、2 用满即到顶
        int a = r.register("a");
        int b = r.register("b");
        r.unregister(a);
        int c = r.register("c");
        assertEquals(a, c, "到顶后回收的 ID 应当可用，而不是继续抛异常");
        assertSame("c", r.resolve(c));
        assertSame("b", r.resolve(b));
    }
}
