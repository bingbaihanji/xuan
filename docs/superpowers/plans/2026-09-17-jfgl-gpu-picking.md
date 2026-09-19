# JFGL GPU 拾取实施计划（子项目 C）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把顶点格式里已预留但从未使用的拾取 ID 通道接上，实现像素级精确、组件零样板、跨线程可用的 GPU 拾取。

**Architecture:** 自建一个附 `R32UI` 整数纹理的 FBO。每批 `submit` 在颜色 pass 之后，用**同一份 VBO、同一张命令表、只换一个 ID 专用 shader** 重画一遍；`pick(x,y)` 就是从这个 FBO 读一个像素。ID 通过 `PickRegistry` 分配并映射回调用方的对象。

**Tech Stack:** Java 21（`gl/`、`renderer/` 顶点侧、注册表）+ Kotlin（`Gc` 门面、`FXGLTransfer`）、LWJGL 3.3.6、openglfx-lwjgl、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-17-jfgl-gpu-picking-design.md`

---

## 关键背景（执行前必读）

### 语言分工

`g/` 与 `renderer/` 的顶点侧用 **Java**；`Gc`、`FXGLTransfer` 用 **Kotlin**。注释与 Javadoc **一律中文**。

### 唯一的几何发射入口

`Gc.emitTriangles`（`Gc.kt:720`）是**所有**绘制的唯一出口：填充走 `emitShape → emitTriangles`，描边走 `strokeOutline → emitTriangles`，路径填充直接调它。全文件只有一处 `writer().vertex(...)`，就在它内部（`Gc.kt:734`）。

**因此在它里面把 `pickId` 传下去，就覆盖了 100% 的绘制路径。** 不需要逐个形状方法去改。

### 运行与验证命令

```bash
# 编译
mvn -o compile

# 全量测试
mvn -o test

# 单个测试类
mvn test -Dtest=PickRegistryTest

# 跑像素校验器（自动关窗；退出码 0=通过、1=断言失败）
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```

**不要用 `mvn exec:java`**：它会让 openglfx 链接到另一份 `com.sun.prism.GraphicsPipeline`，启动即抛 `Could not detect pipeline`。必须用 `exec:exec` fork 独立 JVM。

### 两个容易踩的实现约束

1. **整数纹理的过滤器必须是 `GL_NEAREST`**。`GL_LINEAR` 对整数纹理非法。
2. **清空整数附件必须用 `glClearBufferuiv`**，不能用 `glClearColor` + `glClear`（后者对整数附件是未定义行为）。

### 测试基线

开工前：**125 个测试，0 失败，2 跳过**。每个任务结束时这个数字只应增加，不应有失败。

---

## 文件结构

**新建**

| 文件 | 职责 |
|---|---|
| `src/main/java/com/bingbaihanji/jfgl/renderer/PickRegistry.java` | ID 分配、`id → payload` 映射、回收。纯内存，无 GL 依赖 |
| `src/main/java/com/bingbaihanji/jfgl/renderer/PickHit.java` | 命中结果记录类型（对外） |
| `src/main/java/com/bingbaihanji/jfgl/renderer/PickPixel.java` | 区域内一次命中的 ID 与像素坐标（`PickBuffer` 内部用） |
| `src/main/java/com/bingbaihanji/jfgl/gl/Framebuffer.java` | FBO + `R32UI` 纹理的资源持有与完整性检查 |
| `src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java` | 拾取缓冲的尺寸管理、清空、读点、读区域（含 y 翻转） |
| `src/test/java/com/bingbaihanji/jfgl/renderer/PickRegistryTest.java` | 注册表单测 |
| `src/main/kotlin/com/bingbaihanji/jfgl/example/PickVerifier.kt` | 拾取的像素级端到端校验器 |

**修改**

| 文件 | 改动 |
|---|---|
| `src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java` | 补 FBO / 整数纹理 / 读回方法 |
| `src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java` | 实现上述方法 |
| `src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java` | 增加 `hasPickableVertices()` |
| `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java` | ID 着色器、`drawPickPass`、`beginFrame`、诊断计数 |
| `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt` | `pickId` 进样式栈、`pickable`、`pick`/`pickRect`、`pickRegistry` |
| `src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt` | `pickAsync` |
| `CLAUDE.md` / `README.md` | 文档更新 |

---

## Task 1: PickRegistry（纯 CPU，可独立验证）

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/PickRegistry.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/renderer/PickRegistryTest.java`

这是整个子项目里**唯一不依赖 GL 的部分**，所以放在最前面，先拿到一个确定通过的成果。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/renderer/PickRegistryTest.java`：

```java
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
    void ID耗尽时抛异常而不是环绕() {
        // 构造一个只剩 2 个可用 ID 的注册表，验证到顶时的行为。
        // 上界是「含」的：maxId = 2 → 可用 ID 为 1、2。
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
        PickRegistry r = new PickRegistry(2);
        int a = r.register("a");
        int b = r.register("b");
        r.unregister(a);
        int c = r.register("c");
        assertEquals(a, c, "到顶后回收的 ID 应当可用，而不是继续抛异常");
        assertSame("c", r.resolve(c));
        assertSame("b", r.resolve(b));
    }

    @Test
    void 空payload的ID注销后同样能回收() {
        PickRegistry r = new PickRegistry(2);
        int a = r.register(null);    // 1，载荷为 null
        int b = r.register("b");     // 2
        r.unregister(a);
        int c = r.register("c");
        assertEquals(a, c,
                "载荷为 null 的 ID 必须同样能回收——否则「注册过一个空对象」的 ID 会被永久占死");
        assertSame("c", r.resolve(c));
        assertSame("b", r.resolve(b));
    }

    @Test
    void clear会清空空闲表不会发出重复ID() {
        PickRegistry r = new PickRegistry();
        int a = r.register("a");     // 1
        r.register("b");             // 2
        r.unregister(a);             // 1 进入空闲表

        r.clear();

        // 若 clear() 漏掉 freeIds.clear()：空闲表里还留着陈旧的 1，
        // 而 nextId 也已归 1。下一次 register 弹出陈旧的 1，
        // 再下一次 nextId 又走回 1 —— 两个对象拿到同一个拾取 ID。
        // 表现是「拾取到毫不相干的对象」，且只有先 unregister 再 clear 才会出现。
        int c = r.register("c");
        int d = r.register("d");
        assertNotEquals(c, d, "clear() 之后发出的 ID 必须互不相同");
        assertSame("c", r.resolve(c));
        assertSame("d", r.resolve(d));
    }

    @Test
    void 空闲ID按后进先出复用() {
        PickRegistry r = new PickRegistry();
        int a = r.register("a");     // 1
        int b = r.register("b");     // 2
        int c = r.register("c");     // 3
        r.unregister(a);
        r.unregister(c);

        assertEquals(c, r.register("d"), "应先复用最近注销的那个 ID（LIFO）");
        assertEquals(a, r.register("e"), "再复用更早注销的那个");
        assertSame("b", r.resolve(b));
        assertSame("d", r.resolve(c));
        assertSame("e", r.resolve(a));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=PickRegistryTest`
Expected: 编译失败 — `找不到符号: 类 PickRegistry`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/renderer/PickRegistry.java`：

```java
package com.bingbaihanji.jfgl.renderer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取 ID 的分配器与 {@code id → payload} 映射表。
 *
 * <p>纯内存实现，<strong>不做任何 GL 调用</strong>，因此可以脱离窗口单测——
 * 这也是整个拾取子系统里<strong>本期唯一</strong>能被常规单测覆盖的部分。
 * 说「本期」是有意的：后续的 {@code Framebuffer} / {@code PickBuffer} 只依赖
 * {@code GLAbstraction} 这个<em>接口</em>，喂一个假实现就能零 GL 上下文单测，
 * 别把这句话读成「那些部分不用写单测」的许可证。
 *
 * <h2>ID 空间的规则</h2>
 * <ol>
 *   <li><strong>0 永不分配</strong>，恒定保留给「什么都没命中」。分配从 1 开始。</li>
 *   <li><strong>绝不环绕</strong>。用尽到 {@link #maxId} 时抛
 *       {@link IllegalStateException}。环绕意味着新对象复用了一个仍被引用的 ID，
 *       表现为<strong>拾取到毫不相干的对象</strong>，而且是概率极低的偶发，宁可炸。</li>
 *   <li><strong>回收复用</strong>：{@link #unregister} 把 ID 放进空闲表（LIFO），
 *       下次 {@link #register} 优先复用。否则长生命周期应用反复注册/注销会把 ID 空间磨光。</li>
 * </ol>
 *
 * <h2>生命周期由调用方负责</h2>
 * <p>本类持有 payload 的<strong>强引用</strong>，调用方必须 {@link #unregister} 或
 * {@link #clear}。不用弱引用：被回收后表现为「什么都拾取不到」，又是一个静默失败。
 *
 * <h2>用法约定</h2>
 * <p>注册发生在<strong>数据变化时，不是每帧</strong>。本表是跨帧稳定的映射，
 * 每帧重新注册会耗尽 ID 空间并让 {@code unregister} 的语义失效。
 *
 * <h2>线程安全</h2>
 * <p>注册（随数据变化，通常发生在 JavaFX 线程）与解析（拾取查询，在 GL 线程）
 * 天然分处两个线程，所以本类<strong>必须</strong>线程安全，而不是把「只能在 GL 线程
 * 调用」写进文档了事——那条约束与自然用法相悖，迟早被违反，而违反的表现是
 * {@link #resolve} 静默返回 {@code null}（表现为「什么都没拾取到」），
 * 又是一个不崩的静默失败。
 *
 * <p>分工：{@code payloads} 用 {@link ConcurrentHashMap}，使 {@link #resolve}
 * 保持<strong>无锁</strong>——{@code pickRect} 要逐像素解析，这是查询热路径；
 * ID 分配状态（{@code freeIds} / {@code nextId}）由 {@code lock} 护住，
 * 整个 {@link #register} / {@link #unregister} / {@link #clear} 在同一把锁内完成
 * （分配是「取空闲表 → 递增计数器 → 写入映射」的复合操作，
 * 单靠并发容器不足以保证原子性）。按上面的用法约定，注册不是热路径，
 * 锁的开销无关紧要。
 */
public final class PickRegistry {

    /**
     * 默认 ID 上界。
     *
     * <p>取 {@code Integer.MAX_VALUE - 1} 而不是 {@code MAX_VALUE}：上界是「含」的，
     * 配 {@code nextId++} 用的话，{@code MAX_VALUE} 那一格自增后会溢出成负数，
     * 而 {@code nextId > maxId} 对负数为假——本类承诺的「绝不环绕」就破了。
     * 留一格余量让这条不变式在算术上真的成立。
     *
     * <p>顺带一提：这个边界在实际中不可达。每条登记要占一个 {@code HashMap} 条目
     * 加一个装箱的 {@code Integer}，约 48 字节，2^31 条第 100 GB 量级——
     * 内存会先炸。但「不可达」和「不成立」是两回事，而修正的代价是一个常量。
     */
    private static final int DEFAULT_MAX_ID = Integer.MAX_VALUE - 1;

    /**
     * {@code null} payload 的占位符。
     *
     * <p>{@link ConcurrentHashMap} <strong>不接受 {@code null} 值</strong>——这是它与
     * {@code HashMap} 的一处语义差异，{@code put(id, null)} 会直接抛
     * {@link NullPointerException}。而本类承诺 payload 可为 {@code null}
     * （见 {@link #register}），且 {@link #unregister} 的判据是「条目在不在」
     * 而不是「值是不是 null」。两者一冲突，用私有哨兵占位：入口把 {@code null}
     * 换成它，出口再翻译回 {@code null}。
     *
     * <p>哨兵不出本类，调用方永远看不到它。<strong>不能</strong>改用
     * {@code Collections.synchronizedMap} 绕开——那会把锁加回 {@link #resolve}，
     * 而 {@link #resolve} 是 {@code pickRect} 逐像素解析的热路径，无锁正是本类的要求。
     */
    private static final Object NULL_PAYLOAD = new Object();

    /**
     * 已分配的 ID → payload。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 {@code HashMap}：{@link #resolve}
     * 是查询热路径（{@code pickRect} 逐像素解析），必须无锁且不被撕裂。
     * 分配侧的原子性由 {@link #lock} 负责，不靠这个容器。
     */
    private final Map<Integer, Object> payloads = new ConcurrentHashMap<>();

    /** 已回收、可供复用的 ID（LIFO）。由 {@link #lock} 护住。 */
    private final Deque<Integer> freeIds = new ArrayDeque<>();

    /** 下一个待分配的 ID。由 {@link #lock} 护住。 */
    private int nextId = 1;

    /** ID 上界（含）。 */
    private final int maxId;

    /**
     * 护住 ID 分配状态的锁。
     *
     * <p>只锁「取空闲表 → 递增计数器 → 写入映射」这个复合操作。
     * 不加在 {@link #resolve} 上——那是热路径，靠 {@link ConcurrentHashMap} 本身就够。
     */
    private final Object lock = new Object();

    /** 用默认上界创建注册表。 */
    public PickRegistry() {
        this(DEFAULT_MAX_ID);
    }

    /**
     * 指定 ID 上界创建注册表，仅供测试使用。
     *
     * @param maxId ID 上界（含），小于 1 时按 1 处理
     */
    PickRegistry(int maxId) {
        this.maxId = Math.max(1, maxId);
    }

    /**
     * 注册一个 payload 并返回它的拾取 ID。
     *
     * @param payload 命中时要取回的对象，可为 {@code null}
     * @return 非零的拾取 ID
     * @throws IllegalStateException ID 空间耗尽时
     */
    public int register(Object payload) {
        synchronized (lock) {
            int id;
            if (!freeIds.isEmpty()) {
                id = freeIds.pop();
            } else {
                if (nextId > maxId) {
                    throw new IllegalStateException(
                            "拾取 ID 已耗尽（上界 " + maxId + "）：请检查是否在每帧重复注册，"
                                    + "或对不再需要的对象调用 unregister");
                }
                id = nextId++;
            }
            payloads.put(id, payload == null ? NULL_PAYLOAD : payload);
            return id;
        }
    }

    /**
     * 注销一个 ID 并把它归还到空闲表。注销未注册的 ID 是无副作用的。
     *
     * <p><strong>必须先判后删，不能写成 {@code payloads.remove(id) != null || containsKey(id)}</strong>：
     * {@code remove} 会先删掉条目，其后的 {@code containsKey} 必然为 false，
     * 第二个条件恒不成立、整个表达式退化成「只看 remove 的返回值」——
     * 于是载荷为 {@code null} 的 ID 永远回收不了。这正是要防的那个 bug，
     * 而且它不报错、不抛异常，只是悄悄占死 ID。
     *
     * @param id 要注销的 ID
     */
    public void unregister(int id) {
        if (id == 0) {
            // 双保险，不是唯一防线：0 永远不会出现在 payloads 里（register 从 1 开始），
            // 下面那个 containsKey 守卫本来就会挡掉它。留着是为了让「0 是保留值」这条
            // 规则在代码里看得见——别把这行当成这里唯一的保护。
            return;
        }
        synchronized (lock) {
            if (payloads.containsKey(id)) {
                payloads.remove(id);
                freeIds.push(id);
            }
        }
    }

    /**
     * 解析 ID 对应的 payload。
     *
     * @param id 拾取 ID
     * @return 对应的 payload；未注册时返回 {@code null}
     */
    public Object resolve(int id) {
        Object payload = payloads.get(id);
        return payload == NULL_PAYLOAD ? null : payload;
    }

    /**
     * 清空全部映射与空闲表，并把 ID 计数归零。
     *
     * <p><strong>此前发出的所有 ID 立即失效。</strong>计数器归 1 之后，那些 ID 会被
     * 重新发给<em>别的</em> payload——拿 {@code clear()} 之前缓存的 ID 去
     * {@link #resolve}，会拿到一个毫不相干的对象，而且不报错。
     * 调用方必须丢弃手上每一个缓存的 ID。
     *
     * <p>重发是有意的：{@code clear()} 是生命周期逃生口，若不归还 ID 空间，
     * 它就退化回「{@code maxId} 耗尽」的原样，失去存在的意义。
     */
    public void clear() {
        synchronized (lock) {
            payloads.clear();
            freeIds.clear();
            nextId = 1;
        }
    }

    /**
     * 返回当前已注册的 ID 数量。
     *
     * @return 已注册数量
     */
    public int size() {
        return payloads.size();
    }

}
```

`unregister` 用 `payloads.containsKey(id)` 而不是 `resolve(id) != null`：payload 允许为 `null`，
后者会把「注册过一个空对象」误判成「没注册」，那个 ID 就再也回收不了了。
先判后删，两步都要——上面的 Javadoc 说了为什么不能合成一步。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=PickRegistryTest`
Expected: `Tests run: 18, Failures: 0, Errors: 0` — BUILD SUCCESS
（原计划此处是 15。评审追加见下方「Task 1 评审追加」：A 节补 2 个新测试 → **17**，
B 节的并发测试再补 1 个 → **18**。分两批做的，别按第一步就跳到 18。）

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 143, Failures: 0, Errors: 0, Skipped: 2`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/PickRegistry.java \
        src/test/java/com/bingbaihanji/jfgl/renderer/PickRegistryTest.java
git commit -m "feat(pick): PickRegistry —— ID 分配与 id→payload 映射

0 号 ID 恒定保留给「未命中」；ID 耗尽时抛异常而不环绕（环绕会让新对象
复用仍被引用的 ID，拾取到毫不相干的对象）；注销的 ID 进 LIFO 空闲表复用，
避免长生命周期应用磨光 ID 空间。

unregister 先判后删：payload 允许为 null，若写成 remove()!=null || containsKey(id)，
remove 已先删掉条目、后面的 containsKey 必然为 false，第二个条件成了死代码，
等于只看 remove 返回值——载荷为 null 的 ID 就永远回收不了。这条由
「空payload的ID注销后同样能回收」钉住。

默认上界取 MAX_VALUE-1：上界是「含」的，配 nextId++ 用时 MAX_VALUE 会溢出成
负数而 nextId > maxId 对负数为假，「绝不环绕」的承诺就破了。边界实际不可达
（2^31 条登记约 100 GB，内存先炸），但修正代价是一个常量。"
```

---

### Task 1 评审追加（实现完成后由代码质量评审发现）

Task 1 的实现提交之后，代码质量评审跑了变异验证，发现**三处行为正确但没有任何测试防守**——
把实现改错，15 个测试依然全绿。本项目的缺陷多是「静默错误输出」，这类缺口是最危险的一类：
测试读起来像覆盖了，实际没有，失败表现是「拾取到毫不相干的对象」而不是报错。

还有两处是**规格层面的决定**，写进 `docs/superpowers/specs/2026-09-17-jfgl-gpu-picking-design.md`：

- §5 规则 2 原本写「计数到 `0xFFFFFFFF` 时抛异常」，与实现的 `Integer.MAX_VALUE - 1`
  矛盾（读规格的人会以为 ID 空间是实际的两倍）。已改为说明**有意只用正半区**
  （`Int` 对 `uint` 顶点属性的问题），以及为什么还要再留一格。
- §5 新增规则 5 与 §7 新增「注册侧」一段：**注册表必须线程安全**。
  `pickAsync` 只解决了查询的跨线程，注册侧没有交代；而注册的自然位置是
  JavaFX 线程（数据变化时），解析在 GL 线程——普通 `HashMap` 在这两边的竞争下
  会丢条目，表现为 `resolve` 静默返回 `null`（「什么都没拾取到」），不崩。
  这不属于「文档约束一下就行」：那条约束与自然用法相悖，迟早被违反。

**A. 补三条防守测试**（放在 `PickRegistryTest.java` 末尾）

```java
    @Test
    void 上界小于1时被钳到1() {
        PickRegistry r = new PickRegistry(0);
        assertEquals(1, r.register("a"), "上界被钳到 1，仍能发出恰好一个 ID");
        assertThrows(IllegalStateException.class, () -> r.register("b"),
                "钳位后上界是 1，第二个 ID 就该到顶");
    }

    @Test
    void clear之后ID从1重新发放旧ID会指向新对象() {
        PickRegistry r = new PickRegistry();
        int stale = r.register("a");           // 1，调用方把它缓存了起来
        r.clear();
        int fresh = r.register("b");
        assertEquals(1, fresh, "clear() 必须把计数器归 1，否则这个逃生口不归还任何 ID 空间");
        assertEquals(stale, fresh, "重发是规格要求的：同一个 ID 现在指向另一个对象");
        assertSame("b", r.resolve(stale),
                "拿 clear() 之前缓存的 ID 去 resolve，会拿到毫不相干的对象——"
                        + "调用方必须在 clear() 后丢弃所有缓存的 ID");
    }
```

同时把 `注销不存在的ID是无副作用的` **延伸**（不新增方法）到两次注销之后——这是三条里最重要的一条：

```java
        // 上面两次注销各自都可能往空闲表里灌一条脏数据：unregister(999) 灌入
        // 从未分配过的 999，重复注销把 id 推入两次。脏数据的表现不是报错，
        // 而是后续两次 register 弹出同一个 ID，两个对象共用一个拾取 ID。
        int x = r.register("x");
        int y = r.register("y");
        assertNotEquals(x, y, "注销的副作用不该泄漏到后续分配：两次 register 必须拿到不同的 ID");
        assertSame("x", r.resolve(x));
        assertSame("y", r.resolve(y));
```

**B. 改成线程安全**（规格 §5 规则 5）

- `payloads`：`HashMap` → `ConcurrentHashMap`，让 `resolve` 保持无锁
  （`pickRect` 逐像素解析，是查询热路径）。
- **`ConcurrentHashMap` 不接受 `null` 值**，而本类契约允许 `null` payload
  （`允许空payload注册`、`空payload的ID注销后同样能回收` 两条测试钉着它）。
  照字面换容器会直接炸在 `允许空payload注册` 上（`NullPointerException`
  于 `ConcurrentHashMap.putVal`）。解法是**私有哨兵** `NULL_PAYLOAD`：
  `register` 入口 `payload == null ? NULL_PAYLOAD : payload`，
  `resolve` 出口 `payload == NULL_PAYLOAD ? null : payload`。
  哨兵不出本类。**不要**改用 `Collections.synchronizedMap` 绕开——
  那会把锁加回 `resolve` 这个热路径，正是本节要避免的。
  （此处由实现者发现并向规格/计划反哺；原计划的字段代码是错的。）
- 新增 `private final Object lock = new Object();`，**整个** `register` / `unregister` /
  `clear` 都在锁内完成。分配是「取空闲表 → 递增计数器 → 写入映射」的复合操作，
  单靠并发容器不足以保证原子性（两个线程会读到同一个 `nextId`）。
- `unregister` 的 `id == 0` 早返回保留，但补注释说明它是**双保险、不是唯一防线**
  （真正的防线是 `containsKey` 守卫），否则它会让这个方法**看起来**覆盖了
  「0 是保留值」那条规则。
- `clear()` 的 Javadoc 必须写明：**此前发出的所有 ID 立即失效，可能被重发给别的
  payload，调用方必须丢弃每一个缓存的 ID**。类的 Javadoc 花了 12 行论证「环绕不可接受」
  用的就是这个 hazard，却在 `clear()` 上用一行带过。

配套测试（`PickRegistryTest.java` 末尾）：

```java
    @Test
    void 并发注册不会发出重复的ID() throws Exception {
        // 这是「冒烟探测器」，不是保证：并发缺陷的复现是概率性的。
        // 它能保证的只有「不误报」——正确实现永远通过；它不能保证一定抓到错误实现，
        // 所以变异验证必须真的跑一遍并如实记录结果。若换成 HashMap 后它只是偶尔失败，
        // 就该在报告里明说它抓不住，而不是假装有覆盖。
        PickRegistry r = new PickRegistry();
        int threads = 8;
        int perThread = 2000;
        Set<Integer> seen = Collections.synchronizedSet(new HashSet<>());
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        Object payload = new Object();
                        int id = r.register(payload);
                        if (!seen.add(id)) {
                            failure.compareAndSet(null, "ID 重复发放: " + id);
                            return;
                        }
                        if (r.resolve(id) != payload) {
                            failure.compareAndSet(null, "ID " + id + " 解析到了别的对象");
                            return;
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, String.valueOf(e));
                }
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join();
        }
        assertNull(failure.get(), "并发注册失败: " + failure.get());
        assertEquals(threads * perThread, r.size(), "并发注册不该丢失任何一条映射");
    }
```

需要补的 import：`java.util.Collections`、`java.util.HashSet`、`java.util.Set`、
`java.util.concurrent.CountDownLatch`、`java.util.concurrent.atomic.AtomicReference`。

**C. 清理**（评审的 Minor）

- 内联一次性的私有 `containsKey(int)` 辅助方法：它只被调用一次，名字与 `Map.containsKey`
  同名，还把「为什么单看 `remove()` 返回值不行」的解释拆到了两处。
  内联回 `if (payloads.containsKey(id))`，那段解释**只保留一份**。
- 类 Javadoc 的「唯一能被常规单测覆盖的部分」软化为「**本期唯一**」，
  并说明 `Framebuffer` / `PickBuffer` 只依赖 `GLAbstraction` 接口、喂假实现即可单测——
  否则这句话会被下一个任务读成「那些部分不用写单测」的许可证。

**D. 变异验证**（每条都要做，做完立刻 `git checkout --` 还原）

| 变异 | 应失败的测试 |
|------|--------------|
| `unregister` 守卫换成无条件 `remove` + `push` | `注销不存在的ID是无副作用的` |
| `clear()` 删掉 `nextId = 1;` | `clear之后ID从1重新发放旧ID会指向新对象` |
| 构造函数 `Math.max(1, maxId)` → `maxId` | `上界小于1时被钳到1` |
| `payloads` 换回 `HashMap` + 去掉 `synchronized` | `并发注册不会发出重复的ID`（如实记录是否稳定失败） |

**E. 计数更新**：`PickRegistryTest` 15 → **17**（A 节，已完成，提交 `cd8a466`）
→ **18**（B 节的并发测试落地后）；全量 140 → 142 → **143**。
A 节与 B 节**分两批交付**：A 节不含并发测试，那一批是 17 / 142，别按 18 去凑。

往下每个任务的全量计数（以实际跑出来的为准，这里是**预期**，对不上先查再改）：

| 任务 | 新增测试 | 全量 |
|------|----------|------|
| Task 1 | +18 `PickRegistryTest` | 143 |
| Task 2 | 0（`PickHit` 是 record，见该任务的说明） | 143 |
| Task 3 | 0（纯管道，常量已核到字节码，不写橡皮图章测试） | 143 |
| Task 4 | +5 `FramebufferTest` | **148** |
| Task 5 | +8 `PickBufferTest` | **156**（实跑确认） |
| Task 6 | +3 `VertexWriterTest`（`hasPickableVertices` 是纯 CPU 标志，能单测） | **159** |
| Task 6.5 | +7 `PickBufferTest`、+2 `LwjglGLAbstractionTest` | **168** |
| Task 7 | 0（`RenderBatch` 的 ID pass 要真 GL 上下文，由 Task 10 校验器覆盖） | 167 |
| Task 11 | 终验 | 167 |

**数字对不上就停下来查**，不要为了让计数凑上而增删测试——**已经踩过两次**：

- Task 1：派单时我按「并发测试也算进去」写了 18 / 143，实现者只交了 17 / 142。
  它把差异报了上来而不是编一个填充测试。
- Task 5：我写 `Tests run: 9`，可**计划自己给的代码块里只有 8 个 `@Test`**——
  我数重了。实现者跑了 8 个、如实报了 156 而不是 157，也没有编第 9 个。
  我随后又基于「Task 5 = 9」这个错前提把下游抬到 160，一错再错。

所以：**计划里的预期数字是笔算结果，代码块才是唯一事实来源。**
对不上时先 `grep -c "@Test"` 数一遍代码块，再决定是改测试还是改数字。

**F. 交接纪律**：评审者/实现者在任务边界必须 `git status --porcelain` 确认工作区干净。
已经发生过一次：被中断的评审者在工作区留下了一个未还原的变异（`unregister` 的守卫被删），
差一点被下一个任务的 `git add` 扫进提交。

**G. 变异表是假设，不是命令**（Task 4 实测追加）。

Task 4 的变异 3 原写作「尺寸校验挪到创建资源之后 → `尺寸非正时抛异常` 应当失败」。
按字面实施后实测 **5 全绿，变异存活**——因为那条测试当时的唯一后置断言是
`gl.deletedFramebuffers.isEmpty()`，而「建完就抛」根本没删，删除记录同样是空的：
正确实现与错误实现的可观测状态**一模一样**。断言与它自己的提示语
（"参数校验应当在创建任何 GL 资源之前"）说的不是一回事，是恒真检查。
改断言到 `createdFramebuffers`（校验真正保护的那个量）之后才抓到。

**给后续任务的两条**：

1. 本计划里每一张变异表都按这个标准自检一遍再执行——
   **把错误实现与正确实现都摆出来，问「这两个状态可区分吗」**。不可区分就是橡皮图章。
2. **变异存活 ≠ 这段代码多余。** 前两次存活确实意味着"守卫无人防守"，该补测试；
   这一次若照字面下结论，会得出"尺寸校验可以删掉"——**把一个正确的守卫，
   基于一个瞎了的检查删掉**。规则：**先怀疑检查，再怀疑代码**；
   分不清就如实上报，**不要单方面动代码**。

Task 4 的这条正是这么被发现的：实现者没有自行"修代码"，而是改了测试并把差异报了上来。

---

## Task 2: PickHit

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/PickHit.java`

- [ ] **Step 1: 写实现**

这是一个纯数据记录类型，没有行为，不需要单独的单测（它的构造与访问器由编译器保证；真正的验证在第 10 节的校验器里）。

创建 `src/main/java/com/bingbaihanji/jfgl/renderer/PickHit.java`：

```java
package com.bingbaihanji.jfgl.renderer;

/**
 * 一次拾取查询的命中结果。
 *
 * @param id      命中的拾取 ID，恒不为 0
 * @param payload 该 ID 在 {@link PickRegistry} 中注册的对象；
 *                <strong>ID 已注册但载荷为 null，与 ID 未注册，都表现为 null</strong>
 * @param x       查询点 x（用户坐标，y 向下）
 * @param y       查询点 y（用户坐标，y 向下）
 *
 * <p><strong>关于 x/y</strong>：{@code Gc.pick} 返回的就是传入的查询点坐标；
 * {@code Gc.pickRect} 返回的是该 ID 在查询区域内<strong>按行扫描首次出现的像素坐标</strong>
 * （从区域左上角起、逐行向右），是确定性的、可复现的，便于调试与断言。
 * 它<strong>不是</strong>该图元的中心或任何几何代表点。
 *
 * <p><strong>payload 为 null 不代表没命中</strong>：ID 本身仍是真实的命中信息。
 * 静默降级成「未命中」会让人以为没点到。
 */
public record PickHit(int id, Object payload, float x, float y) {
}
```

- [ ] **Step 2: 编译**

Run: `mvn -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/PickHit.java
git commit -m "feat(pick): PickHit 命中结果类型"
```

---

## Task 3: GLAbstraction 扩充（FBO / 整数纹理 / 读回）

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java`
- Modify: `src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java`

`GLAbstraction` 目前**一个 FBO 相关方法都没有**，这一节是纯新增基础设施。

- [ ] **Step 1: 在接口里加方法**

在 `GLAbstraction.java` 的 `createTexture` 方法之后、结束大括号之前插入：

```java
    /**
     * {@code GL_FRAMEBUFFER_COMPLETE} 的枚举值。
     *
     * <p>放在接口上是为了让调用方不必为了比较一个状态码而引入 LWJGL 的常量。
     */
    int FRAMEBUFFER_COMPLETE = 0x8CD5;

    /**
     * 创建帧缓冲对象（FBO）。
     *
     * @return FBO 的 ID
     */
    int createFramebuffer();

    /**
     * 绑定帧缓冲。0 表示默认帧缓冲。
     *
     * @param framebuffer FBO 的 ID
     */
    void bindFramebuffer(int framebuffer);

    /**
     * 删除帧缓冲对象。
     *
     * @param framebuffer FBO 的 ID
     */
    void deleteFramebuffer(int framebuffer);

    /**
     * 返回当前绑定的帧缓冲 ID。
     *
     * <p>openglfx 渲染到它<strong>自己的</strong> FBO，因此正常运行时这个值通常<strong>非 0</strong>。
     * 任何临时切换帧缓冲的操作都必须先取这个值、事后再恢复回去。
     *
     * @return 当前绑定的帧缓冲 ID
     */
    int currentFramebufferBinding();

    /**
     * 创建一张 {@code R32UI} 整数纹理。
     *
     * <p>整数纹理的过滤器<strong>必须</strong>是 {@code GL_NEAREST}：{@code GL_LINEAR}
     * 对整数纹理非法。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @return 纹理的 ID
     */
    int createIntegerTexture(int width, int height);

    /**
     * 删除纹理。
     *
     * @param texture 纹理的 ID
     */
    void deleteTexture(int texture);

    /**
     * 把一张 2D 纹理挂到<strong>当前绑定的</strong>帧缓冲的 0 号颜色附件上。
     *
     * @param texture 纹理的 ID
     */
    void attachTextureToColor0(int texture);

    /**
     * 返回当前绑定的帧缓冲的完整性状态，等于 {@link #FRAMEBUFFER_COMPLETE} 表示可用。
     *
     * @return 帧缓冲状态码
     */
    int framebufferStatus();

    /**
     * 用一个整数清除值清空当前绑定的帧缓冲的 0 号颜色附件。
     *
     * <p>整数附件<strong>不能</strong>用 {@code glClearColor} + {@code glClear}：
     * 那对整数附件是未定义行为。本方法内部走 {@code glClearBufferuiv}。
     *
     * @param value 清除值（写进 R 通道，其余通道为 0）
     */
    void clearIntegerColor(int value);

    /**
     * 读回一个无符号整数像素。
     *
     * <p>坐标是 <strong>GL 约定</strong>：原点在帧缓冲左下角、y 向上。
     * 调用方负责从「原点左上、y 向下」的用户坐标换算过来。
     *
     * @param x 像素 x（GL 约定）
     * @param y 像素 y（GL 约定）
     * @return 该像素的整数值
     */
    int readUnsignedIntPixel(int x, int y);

    /**
     * 读回一块无符号整数像素。
     *
     * <p>坐标同样是 GL 约定。读回的行序<strong>自下而上</strong>，
     * 即 {@code out} 的第 0 行对应 GL 坐标系里最下面那一行。
     *
     * @param x      左上角 x（GL 约定）
     * @param y      左上角 y（GL 约定）
     * @param width  宽度
     * @param height 高度
     * @param out    结果数组，长度至少为 {@code width * height}
     */
    void readUnsignedIntPixels(int x, int y, int width, int height, int[] out);
```

- [ ] **Step 2: 在 LWJGL 实现里补上**

在 `LwjglGLAbstraction.java` 的 `createTexture` 之后、`dispose` 之前插入：

```java
    @Override
    public int createFramebuffer() {
        return GL30.glGenFramebuffers();
    }

    @Override
    public void bindFramebuffer(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    }

    @Override
    public void deleteFramebuffer(int framebuffer) {
        GL30.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public int currentFramebufferBinding() {
        return GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    }

    @Override
    public int createIntegerTexture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // 整数纹理的过滤器必须是 GL_NEAREST。GL_LINEAR 对整数纹理非法
        // （会生成 GL_INVALID_OPERATION，且采样结果未定义）。默认的
        // GL_NEAREST_MIPMAP_LINEAR 在没有 mipmap 时同样是不完整的。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI,
                width, height, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void deleteTexture(int texture) {
        GL11.glDeleteTextures(texture);
    }

    @Override
    public void attachTextureToColor0(int texture) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
    }

    @Override
    public int framebufferStatus() {
        return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
    }

    @Override
    public void clearIntegerColor(int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.ints(value, 0, 0, 0);
            GL30.glClearBufferuiv(GL30.GL_COLOR, 0, buffer);
        }
    }

    @Override
    public int readUnsignedIntPixel(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.mallocInt(1);
            GL11.glReadPixels(x, y, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
            return buffer.get(0);
        }
    }

    @Override
    public void readUnsignedIntPixels(int x, int y, int width, int height, int[] out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.mallocInt(width * height);
            GL11.glReadPixels(x, y, width, height,
                    GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
            buffer.get(out, 0, width * height);
        }
    }
```

同时在文件顶部的 import 区补上（`GL30` 已有）：

```java
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
```

- [ ] **Step 3: 编译**

Run: `mvn -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 跑全量测试确认没破坏**

Run: `mvn -o test`
Expected: `Tests run: 143, Failures: 0, Skipped: 2`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java \
        src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java
git commit -m "feat(gl): GLAbstraction 补 FBO / R32UI 整数纹理 / 整数读回

拾取需要一个自己的帧缓冲，而抽象层此前一个 FBO 方法都没有。

整数纹理的过滤器必须是 GL_NEAREST（GL_LINEAR 对整数纹理非法）；
清空整数附件走 glClearBufferuiv 而非 glClearColor+glClear（后者对整数附件
是未定义行为，在多数驱动上「看起来能跑」但换个驱动才暴露）。

currentFramebufferBinding 是给调用方保存/恢复用的——openglfx 渲染到它自己的
FBO（非 0），临时切换后不恢复会让下一帧画进拾取缓冲。"
```

---

## Task 4: gl/Framebuffer

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/gl/Framebuffer.java`

- [ ] **Step 1: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/gl/Framebuffer.java`：

```java
package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;

/**
 * 一个附有 {@code R32UI} 整数纹理的帧缓冲对象（FBO）。
 *
 * <p>只负责<strong>资源生命周期</strong>与创建时的完整性检查；
 * 绑定、清空、读回这些「用法」留给 {@code PickBuffer}。
 *
 * <p><strong>恒为单采样。</strong>拾取需要精确的 ID，抗锯齿产生的部分覆盖像素
 * 把 ID 插值成「零点几个对象」没有意义。
 *
 * <p>所有方法必须在 GL 线程上调用。
 */
public final class Framebuffer implements Disposable {

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /** FBO 的 ID。 */
    private int framebuffer;

    /** 颜色附件纹理的 ID。 */
    private int texture;

    /** 宽度（像素）。 */
    private final int width;

    /** 高度（像素）。 */
    private final int height;

    /** 是否已释放，保证 {@link #dispose()} 幂等。 */
    private boolean disposed = false;

    /**
     * 创建帧缓冲并把一张 {@code R32UI} 纹理挂到 0 号颜色附件上。
     *
     * <p>构造过程中会临时绑定自己的 FBO，<strong>结束后恢复原绑定</strong>：
     * openglfx 渲染到它自己的 FBO，不复原的话后续所有绘制都会画进这里。
     *
     * @param gl     GL 抽象层
     * @param width  宽度（像素），必须为正
     * @param height 高度（像素），必须为正
     * @throws IllegalArgumentException 宽或高不为正时
     * @throws IllegalStateException    帧缓冲不完整时
     */
    public Framebuffer(GLAbstraction gl, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "帧缓冲尺寸必须为正：实际 " + width + "x" + height);
        }
        this.gl = gl;
        this.width = width;
        this.height = height;

        int previous = gl.currentFramebufferBinding();
        this.framebuffer = gl.createFramebuffer();
        this.texture = gl.createIntegerTexture(width, height);
        gl.bindFramebuffer(framebuffer);
        gl.attachTextureToColor0(texture);
        int status = gl.framebufferStatus();
        gl.bindFramebuffer(previous);

        // 不完整的帧缓冲读回来全是 0，表现为「什么都拾取不到」——一个安静的、
        // 看起来像业务逻辑问题的失败。必须在这里就炸掉，并带上状态码以便定位
        // （GL_FRAMEBUFFER_UNSUPPORTED 与 GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        // 的排查方向完全不同）。
        if (status != GLAbstraction.FRAMEBUFFER_COMPLETE) {
            dispose();
            throw new IllegalStateException(String.format(
                    "拾取帧缓冲创建失败（%dx%d）：glCheckFramebufferStatus = 0x%04X，期望 0x%04X",
                    width, height, status, GLAbstraction.FRAMEBUFFER_COMPLETE));
        }
    }

    /**
     * 返回 FBO 的 ID。
     *
     * @return FBO 的 ID
     */
    public int id() {
        return framebuffer;
    }

    /**
     * 返回颜色附件纹理的 ID。
     *
     * @return 纹理的 ID
     */
    public int textureId() {
        return texture;
    }

    /**
     * 返回宽度（像素）。
     *
     * @return 宽度
     */
    public int width() {
        return width;
    }

    /**
     * 返回高度（像素）。
     *
     * @return 高度
     */
    public int height() {
        return height;
    }

    /** 释放 FBO 与纹理。重复调用无副作用。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        if (framebuffer != 0) {
            gl.deleteFramebuffer(framebuffer);
            framebuffer = 0;
        }
        if (texture != 0) {
            gl.deleteTexture(texture);
            texture = 0;
        }
        disposed = true;
    }
}
```

- [ ] **Step 2: 假 GL 与 FramebufferTest**（评审追加）

`Framebuffer` 只依赖 `GLAbstraction` **接口**，喂一个假实现就能零 GL 上下文单测。
构造时「恢复原绑定」那一步漏掉的话，**后续所有绘制都会画进拾取缓冲**——
画面全黑或全是拾取色，排查方向完全指错。这是本项目最擅长产生的那类静默失败，
而它只需几毫秒的纯内存测试就能钉死。

创建 `src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java`：

```java
package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的 {@link GLAbstraction} 假实现。
 *
 * <p>只实现拾取路径真正用到的那几个方法，其余一律抛
 * {@link UnsupportedOperationException}——测试里真调到了就说明走偏了，
 * 静默返回 0 反而会让断言看起来通过。
 *
 * <p>{@link #setUserPixel} 是给测试用的便捷入口：按<strong>用户坐标</strong>
 * （原点左上、y 向下）写入虚拟屏幕，内部换算成 GL 行序。这样测试用例可以用
 * 人思考场景的方式来布置数据，而 {@code PickBuffer} 的翻转一旦写错，
 * 读回来的坐标就对不上——两者是独立的代码路径，不会互相抵消。
 */
public final class FakeGLAbstraction implements GLAbstraction {

    /** 分配出的 ID 从这里递增，便于断言「确实拿去用了」而不是默认值 0。 */
    private int nextId = 100;

    /** 当前绑定的 FBO。初值刻意非 0，模拟 openglfx 渲染到自己的 FBO。 */
    public int boundFramebuffer = 7;

    /** 每次 bindFramebuffer 的入参，用来断言「恢复了原绑定」。 */
    public final List<Integer> bindCalls = new ArrayList<>();

    /** framebufferStatus() 的返回值，测试可改。 */
    public int statusToReturn = FRAMEBUFFER_COMPLETE;

    /** 删除调用的记录。 */
    public final List<Integer> deletedFramebuffers = new ArrayList<>();
    public final List<Integer> deletedTextures = new ArrayList<>();

    /** 虚拟屏幕，<strong>GL 行序</strong>：下标 0 对应最下面一行。 */
    private int[] screen = new int[0];
    private int screenWidth;
    private int screenHeight;

    /** 按 GL 行序（自下而上）设置整块虚拟屏幕。 */
    public void setScreenBottomUp(int width, int height, int[] rowsBottomUp) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.screen = rowsBottomUp.clone();
    }

    /** 按<strong>用户坐标</strong>（原点左上、y 向下）写一个像素。 */
    public void setUserPixel(int x, int y, int value) {
        screen[(screenHeight - 1 - y) * screenWidth + x] = value;
    }

    @Override
    public int createFramebuffer() {
        return nextId++;
    }

    @Override
    public void bindFramebuffer(int framebuffer) {
        boundFramebuffer = framebuffer;
        bindCalls.add(framebuffer);
    }

    @Override
    public void deleteFramebuffer(int framebuffer) {
        deletedFramebuffers.add(framebuffer);
    }

    @Override
    public int currentFramebufferBinding() {
        return boundFramebuffer;
    }

    @Override
    public int createIntegerTexture(int width, int height) {
        return nextId++;
    }

    @Override
    public void deleteTexture(int texture) {
        deletedTextures.add(texture);
    }

    @Override
    public void attachTextureToColor0(int texture) {
        // 假实现不做附件检查，完整性由 statusToReturn 单独控制
    }

    @Override
    public int framebufferStatus() {
        return statusToReturn;
    }

    @Override
    public void clearIntegerColor(int value) {
        java.util.Arrays.fill(screen, value);
    }

    @Override
    public int readUnsignedIntPixel(int x, int y) {
        return screen[y * screenWidth + x];
    }

    @Override
    public void readUnsignedIntPixels(int x, int y, int width, int height, int[] out) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                out[row * width + col] = screen[(y + row) * screenWidth + (x + col)];
            }
        }
    }

    // —— 以下与拾取路径无关，真调到了说明走偏了 ——

    @Override public void initialize() { throw new UnsupportedOperationException(); }
    @Override public void clear(Color color) { throw new UnsupportedOperationException(); }
    @Override public void setViewport(int x, int y, int w, int h) { throw new UnsupportedOperationException(); }
    @Override public int createVao() { throw new UnsupportedOperationException(); }
    @Override public int createVbo() { throw new UnsupportedOperationException(); }
    @Override public void bindVao(int vao) { throw new UnsupportedOperationException(); }
    @Override public void bindVbo(int vbo) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboData(float[] data) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboData(int[] data) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboBytes(ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void deleteVao(int vao) { throw new UnsupportedOperationException(); }
    @Override public void deleteVbo(int vbo) { throw new UnsupportedOperationException(); }
    @Override public void drawArrays(int mode, int offset, int count) { throw new UnsupportedOperationException(); }
    @Override public void drawElements(int mode, int count) { throw new UnsupportedOperationException(); }
    @Override public void enableBlend() { throw new UnsupportedOperationException(); }
    @Override public void disableBlend() { throw new UnsupportedOperationException(); }
    @Override public void setBlendFunc(int s, int d) { throw new UnsupportedOperationException(); }
    @Override public ShaderProgram createShader(String v, String f) { throw new UnsupportedOperationException(); }
    @Override public int createTexture(int w, int h, int[] p) { throw new UnsupportedOperationException(); }
    @Override public void dispose() { /* 无资源可释放 */ }
}
```

创建 `src/test/java/com/bingbaihanji/jfgl/gl/FramebufferTest.java`：

```java
package com.bingbaihanji.jfgl.gl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FramebufferTest {

    @Test
    void 构造后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.boundFramebuffer = 7;          // 模拟 openglfx 自己的 FBO
        new Framebuffer(gl, 16, 16);
        assertEquals(7, gl.boundFramebuffer,
                "构造完必须把绑定还给调用方；否则后续所有绘制都画进拾取缓冲");
        assertEquals(7, gl.bindCalls.get(gl.bindCalls.size() - 1),
                "最后一次 bindFramebuffer 应当是恢复，而不是又切到自己的 FBO");
    }

    @Test
    void 帧缓冲不完整时抛异常并带上状态码() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.statusToReturn = 0x8CD6;       // GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new Framebuffer(gl, 16, 16));
        assertTrue(e.getMessage().contains("8CD6"),
                "消息必须带上实际状态码——UNSUPPORTED 与 INCOMPLETE_ATTACHMENT 的排查方向完全不同");
    }

    @Test
    void 帧缓冲不完整时不泄漏已创建的资源() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.statusToReturn = 0x8CD6;
        assertThrows(IllegalStateException.class, () -> new Framebuffer(gl, 16, 16));
        assertEquals(1, gl.deletedFramebuffers.size(), "创建失败也必须回收 FBO");
        assertEquals(1, gl.deletedTextures.size(), "创建失败也必须回收纹理");
    }

    @Test
    void 尺寸非正时抛异常() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        assertThrows(IllegalArgumentException.class, () -> new Framebuffer(gl, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new Framebuffer(gl, 16, -1));
        assertTrue(gl.deletedFramebuffers.isEmpty(), "参数校验应当在创建任何 GL 资源之前");
    }

    @Test
    void 释放是幂等的() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        Framebuffer fb = new Framebuffer(gl, 16, 16);
        fb.dispose();
        fb.dispose();
        assertEquals(1, gl.deletedFramebuffers.size(), "重复 dispose 不该重复删除");
        assertEquals(1, gl.deletedTextures.size());
    }
}
```

Run: `mvn -o test -Dtest=FramebufferTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

**变异验证**（每条做完立刻还原）：

| 变异 | 应失败的测试 |
|------|--------------|
| 构造结尾去掉 `gl.bindFramebuffer(previous)` | `构造后恢复原先的帧缓冲绑定` |
| 不完整分支去掉 `dispose()` | `帧缓冲不完整时不泄漏已创建的资源` |
| 尺寸校验挪到创建资源之后 | `尺寸非正时抛异常` |

- [ ] **Step 3: 全量测试**

Run: `mvn -o test`
Expected: `Tests run: 148, Failures: 0, Errors: 0, Skipped: 2`（143 + 5）

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/gl/Framebuffer.java \
        src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java \
        src/test/java/com/bingbaihanji/jfgl/gl/FramebufferTest.java
git commit -m "feat(gl): Framebuffer —— R32UI 附件的 FBO 封装

构造时临时绑定自己的 FBO 并在结束前恢复原绑定（openglfx 渲染到它自己的
FBO，不复原会让后续绘制全画进拾取缓冲）。

创建后立即检查 glCheckFramebufferStatus：不完整的 FBO 读回来全是 0，
表现为「什么都拾取不到」，是一个看起来像业务逻辑问题的静默失败。

同批加 FakeGLAbstraction：本类只依赖 GLAbstraction 接口，喂假实现即可
零 GL 上下文单测，这是本期唯一能做到这一点的机会。三条变异验证：
绑定恢复、失败时资源回收、参数校验先于资源创建。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 5: PickBuffer

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java`

- [ ] **Step 1: 先写 readRect 的返回类型**

区域查询要回答的不只是「命中了哪些 ID」，还有「在哪儿」——后者是调试与断言时的关键信息。
用一个小的记录类型承载它，比返回 `int[]` 三元组或让调用方自己去猜行列要有用得多。

创建 `src/main/java/com/bingbaihanji/jfgl/renderer/PickPixel.java`：

```java
package com.bingbaihanji.jfgl.renderer;

/**
 * 拾取缓冲里一个非零像素：命中的 ID 与它的坐标。
 *
 * <p>由 {@link PickBuffer#readRect} 产生，表示某个 ID 在查询区域内
 * <strong>按行扫描首次出现</strong>的那个像素。
 *
 * @param id 该像素的拾取 ID，恒不为 0
 * @param x  用户坐标 x（y 向下）
 * @param y  用户坐标 y（y 向下）
 */
public record PickPixel(int id, int x, int y) {
}
```

- [ ] **Step 2: 写 PickBuffer**

创建 `src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java`：

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.Framebuffer;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 拾取缓冲：一块与主帧缓冲同尺寸的 {@code R32UI} 离屏缓冲，存每个像素命中的拾取 ID。
 *
 * <h2>坐标约定</h2>
 * <p>本类对外的坐标一律是<strong>用户坐标</strong>：像素、原点左上、y 向下。
 * {@code glReadPixels} 的原点在左下、y 向上，<strong>翻转集中在本类内部完成</strong>——
 * 漏掉翻转会得到一个上下镜像的、部分正确的拾取结果，是本项目最擅长产生的那类缺陷。
 *
 * <h2>尺寸不变式</h2>
 * <p>必须与主帧缓冲<strong>同尺寸</strong>。裁剪靠 {@code glScissor}，而它的 y 是按
 * {@code viewportHeight} 换算的；两者尺寸不同会让裁剪错位——被裁掉的部分仍可拾取，
 * 用户点看不见的地方却命中。{@link #ensureSize} 负责在尺寸变化时重建。
 *
 * <p>所有方法必须在 GL 线程上调用。
 */
public final class PickBuffer implements Disposable {

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /** 底层帧缓冲，尺寸变化时会被替换。 */
    private Framebuffer framebuffer;

    /** 是否已释放。 */
    private boolean disposed = false;

    /**
     * 创建拾取缓冲。
     *
     * @param gl     GL 抽象层
     * @param width  宽度（像素），必须为正
     * @param height 高度（像素），必须为正
     */
    public PickBuffer(GLAbstraction gl, int width, int height) {
        this.gl = gl;
        this.framebuffer = new Framebuffer(gl, width, height);
    }

    /**
     * 确保缓冲尺寸与给定值一致，不一致则重建。
     *
     * <p>尺寸没变时是空操作，因此每帧调用没有开销。
     *
     * @param width  期望宽度
     * @param height 期望高度
     */
    public void ensureSize(int width, int height) {
        if (framebuffer.width() == width && framebuffer.height() == height) {
            return;
        }
        framebuffer.dispose();
        framebuffer = new Framebuffer(gl, width, height);
    }

    /**
     * 把整个缓冲清成 0（即「什么都没命中」）。
     *
     * <p>走 {@code glClearBufferuiv} 而不是 {@code glClearColor} + {@code glClear}：
     * 后者对整数附件是未定义行为。绑定在使用前后被恢复。
     */
    public void clear() {
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        gl.clearIntegerColor(0);
        gl.bindFramebuffer(previous);
    }

    /**
     * 读回一个像素的拾取 ID。
     *
     * @param x 用户坐标 x（y 向下）
     * @param y 用户坐标 y（y 向下）
     * @return 该像素的 ID；坐标越界时返回 0
     */
    public int readPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= framebuffer.width() || y >= framebuffer.height()) {
            // 越界读 glReadPixels 是未定义行为，必须在这里挡掉。
            return 0;
        }
        int glY = framebuffer.height() - 1 - y;
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        int id = gl.readUnsignedIntPixel(x, glY);
        gl.bindFramebuffer(previous);
        return id;
    }

    /**
     * 读回一块区域内出现过的拾取 ID，每个 ID 附带它在区域内**首次出现**的像素坐标。
     *
     * <p>区域会先与缓冲求交；交集为空返回空列表（<strong>不抛异常</strong>——
     * 刷选拖到窗口外是正常操作）。
     *
     * <p><strong>扫描顺序</strong>：从区域左上角起、逐行向右，先遇到的那个像素即为
     * 「首次出现」。结果按 ID 升序排列（{@link TreeMap} 的键序），
     * 因此同一个场景两次查询的返回顺序稳定，便于断言与调试。
     *
     * <p><strong>行序要反过来遍历</strong>：{@code glReadPixels} 的回读结果自下而上，
     * {@code raw} 的第 0 行对应 GL 坐标里最下面那一行，也就是用户坐标里 y 最大的那一行。
     * 所以「从区域顶部开始」对应 {@code raw} 的行下标<strong>递减</strong>。
     * 顺着遍历会得到一个上下颠倒的「首次出现」，答案看着合理但位置是错的。
     *
     * @param x 用户坐标左边缘
     * @param y 用户坐标上边缘（y 向下）
     * @param w 宽度
     * @param h 高度
     * @return 区域内出现过的非零 ID 及其首次出现坐标，按 ID 升序
     */
    public List<PickPixel> readRect(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return List.of();
        }
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(framebuffer.width(), x + w);
        int y1 = Math.min(framebuffer.height(), y + h);
        if (x1 <= x0 || y1 <= y0) {
            return List.of();
        }
        int readWidth = x1 - x0;
        int readHeight = y1 - y0;

        // 用户坐标的 y0 是上边缘，换算到 GL：该区域占据 GL 行 [height - y1, height - y0)。
        int glY = framebuffer.height() - y1;

        int[] raw = new int[readWidth * readHeight];
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        gl.readUnsignedIntPixels(x0, glY, readWidth, readHeight, raw);
        gl.bindFramebuffer(previous);

        // 这是一次按需查询（刷选），不是每帧路径，为了清晰起见接受这点装箱开销。
        TreeMap<Integer, PickPixel> firstSeen = new TreeMap<>();
        for (int row = readHeight - 1; row >= 0; row--) {
            // raw 的第 row 行对应 GL 行 glY + row，即用户坐标 y = height - 1 - (glY + row)。
            int userY = framebuffer.height() - 1 - (glY + row);
            int rowBase = row * readWidth;
            for (int col = 0; col < readWidth; col++) {
                int value = raw[rowBase + col];
                if (value != 0 && !firstSeen.containsKey(value)) {
                    firstSeen.put(value, new PickPixel(value, x0 + col, userY));
                }
            }
        }
        return new ArrayList<>(firstSeen.values());
    }

    /**
     * 返回当前宽度（像素）。
     *
     * @return 宽度
     */
    public int width() {
        return framebuffer.width();
    }

    /**
     * 返回当前高度（像素）。
     *
     * @return 高度
     */
    public int height() {
        return framebuffer.height();
    }

    /** 释放底层帧缓冲。重复调用无副作用。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        framebuffer.dispose();
        disposed = true;
    }
}
```

- [ ] **Step 3: 编译**

Run: `mvn -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/PickPixel.java \
        src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java
git commit -m "feat(pick): PickBuffer —— 清空、读点、读区域

y 翻转集中在本类内部：对外一律用户坐标（原点左上、y 向下），内部换算成
glReadPixels 的左下原点约定。漏掉翻转会得到上下镜像但部分正确的结果。

读区域先与缓冲求交，交集为空返回空列表而非抛异常——刷选拖到窗口外是
正常操作。越界读 glReadPixels 是未定义行为，在 readPixel 里挡掉。

readRect 返回 PickPixel（ID + 首次出现坐标）而非裸 ID 数组：区域查询要
回答的不只是「命中了谁」，还有「在哪儿」。回读结果自下而上，所以
「从区域顶部开始扫描」对应 raw 的行下标递减——顺着遍历会得到上下颠倒的
「首次出现」，答案看着合理但位置是错的。"
```

- [ ] **Step 4: 用假 GL 单测 PickBuffer 的坐标数学**（评审追加）

`FakeGLAbstraction` 已在 Task 4 落地（连同 `FramebufferTest`），本步直接复用，
不要重复创建。`PickBuffer` 里藏着一处**逻辑**（其余是管道），失败方式正是本项目
最擅长产生的那一类：`readRect` 的 y 翻转与行扫描序——错了得到**上下镜像但
部分正确**的拾取坐标，看着合理，位置是错的。

不测的话它只能靠 Task 10 的像素校验器兜底；校验器要 GL 上下文、跑得慢，
而且覆盖不到裁剪、退化区域、去重顺序这些分支。**这是本期唯一能零 GL 覆盖
坐标数学的机会，别放过。**

创建 `src/test/java/com/bingbaihanji/jfgl/renderer/PickBufferTest.java`：

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PickBufferTest {

    /** 造一个 8x4 的拾取缓冲，虚拟屏幕初始全 0。 */
    private static PickBuffer buffer(FakeGLAbstraction gl) {
        gl.setScreenBottomUp(8, 4, new int[8 * 4]);
        return new PickBuffer(gl, 8, 4);
    }

    @Test
    void 区域查询的坐标是用户坐标而非上下镜像() {
        // 安排：用户坐标 (5, 1) 处是 id=42。用户坐标原点在左上、y 向下，
        // 所以它在 GL 行序里是倒数第二行。读回来若还在 (5, 1) 说明翻转正确；
        // 若漏翻转会得到 (5, 2)——同样「看起来合理」，所以必须断言精确坐标。
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(5, 1, 42);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        assertEquals(1, hits.size());
        assertEquals(42, hits.get(0).id());
        assertEquals(5, hits.get(0).x());
        assertEquals(1, hits.get(0).y(), "翻转写错会得到上下镜像的 y，且不报错");
    }

    @Test
    void 首次出现按从上到下的行序选取() {
        // 同一个 id 出现在更靠下的 (3, 3) 与更靠上的 (3, 0)，
        // 「按行扫描首次出现」应当取更靠上的那个。
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(3, 3, 42);
        gl.setUserPixel(3, 0, 42);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).y(),
                "扫描序反了会取到更靠下的那个——坐标合法但位置是错的");
    }

    @Test
    void 同一行内取最左边的() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(6, 2, 42);
        gl.setUserPixel(1, 2, 42);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        assertEquals(1, hits.get(0).x());
    }

    @Test
    void 结果按ID升序且每个ID只出现一次() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(0, 0, 9);
        gl.setUserPixel(1, 0, 3);
        gl.setUserPixel(2, 0, 9);   // 9 的第二次出现，应被忽略
        gl.setUserPixel(3, 0, 5);

        List<PickPixel> hits = pb.readRect(0, 0, 8, 4);

        // 必须连坐标一起断言，不能只断言 ID 集合：去掉判重之后，
        // 9 会被后来的 (2, 0) 覆盖成 (2, 0)，但「键 9 存在且只出现一次」
        // 依然成立，ID 列表分毫不变——只看 ID 就是恒真检查，变异会存活。
        // 判重保护的是「首次出现的坐标」，断言必须落在坐标上。
        assertEquals(List.of(new PickPixel(3, 1, 0),
                        new PickPixel(5, 3, 0),
                        new PickPixel(9, 0, 0)),
                hits);
    }

    @Test
    void 零号像素被忽略() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(0, 0, 0);   // 0 = 没命中，不该出现在结果里

        assertTrue(pb.readRect(0, 0, 8, 4).isEmpty());
    }

    @Test
    void 退化区域返回空列表() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(0, 0, 42);

        assertTrue(pb.readRect(0, 0, 0, 4).isEmpty(), "宽度为 0");
        assertTrue(pb.readRect(0, 0, 4, 0).isEmpty(), "高度为 0");
        assertTrue(pb.readRect(-100, -100, 1, 1).isEmpty(), "完全在缓冲外");
    }

    @Test
    void 越界区域被裁剪到缓冲内且坐标仍正确() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(7, 3, 42);          // 右下角

        // 从 (5,2) 起查 100x100，应当裁剪到 8x4 的范围内而不是越界读
        List<PickPixel> hits = pb.readRect(5, 2, 100, 100);

        assertEquals(1, hits.size());
        assertEquals(7, hits.get(0).x());
        assertEquals(3, hits.get(0).y());
    }

    @Test
    void 读完之后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.boundFramebuffer = 7;
        PickBuffer pb = buffer(gl);
        pb.readRect(0, 0, 8, 4);
        assertEquals(7, gl.boundFramebuffer,
                "读回后必须恢复绑定，否则后续绘制会画进拾取缓冲");
    }
}
```

Run: `mvn -o test -Dtest=PickBufferTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

**变异验证**（每条做完立刻还原）：

| 变异 | 应失败的测试 |
|------|--------------|
| `readRect` 里 `userY` 改成 `glY + row` | `区域查询的坐标是用户坐标而非上下镜像` |
| `readRect` 的 `row` 循环改成从 0 递增 | `首次出现按从上到下的行序选取` |
| `PickBuffer.readRect` 结尾去掉 `gl.bindFramebuffer(previous)` | `读完之后恢复原先的帧缓冲绑定` |
| `readRect` 去掉 `!firstSeen.containsKey(value)` 判重 | `结果按ID升序且每个ID只出现一次`、`同一行内取最左边的` |

（上面第 4 条原本是橡皮图章：那条测试原来只断言 ID 列表，而判重保护的是
**首次出现的坐标**——覆盖之后 ID 集合不变，变异会存活。断言已改成整表
`PickPixel` 比较，覆盖坐标。这正是 G 节说的那条，写在这里做个实证。）

全量测试：`mvn -o test` → 期望 `Tests run: 156, Failures: 0, Errors: 0, Skipped: 2`
（148 + 8）。

提交：

```bash
git add src/test/java/com/bingbaihanji/jfgl/renderer/PickBufferTest.java
git commit -m "test(pick): 用假 GL 单测 PickBuffer 的坐标数学

PickBuffer 只依赖 GLAbstraction 接口（假实现见 Task 4 的 FakeGLAbstraction），
喂它即可零 GL 上下文覆盖坐标数学——这是本期唯一能做到这一点的机会。

覆盖的是逻辑而非管道：readRect 的 y 翻转与行扫描序，错了会得到上下镜像
但部分正确的拾取坐标——看着合理，位置是错的。另外覆盖了裁剪到缓冲边界、
退化区域返回空、同 ID 只取首次出现、结果按 ID 升序这几条分支，它们都是
像素校验器跑不到的地方。

四条变异验证：翻转、扫描序、绑定恢复、去重。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 6: VertexWriter.hasPickableVertices

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/renderer/VertexWriterTest.java`

- [ ] **Step 1: 写失败的测试**

在 `VertexWriterTest.java` 末尾（最后一个 `}` 之前）追加：

```java
    @Test
    void 无拾取ID时不报告可拾取顶点() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 10, 10);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        assertFalse(w.hasPickableVertices(),
                "全部 ID 为 0 时不该声称有可拾取内容——否则每帧都会白跑一趟 ID pass");
    }

    @Test
    void 出现非零ID后报告可拾取顶点() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 10, 10);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        w.vertex(1f, 1f, 0f, 0f, 0xFFFFFFFF, 7);
        assertTrue(w.hasPickableVertices());
    }

    @Test
    void reset清掉可拾取标志() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 10, 10);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 9);
        assertTrue(w.hasPickableVertices());
        w.reset();
        assertFalse(w.hasPickableVertices(),
                "reset 是帧中途 flush 的分批边界，标志必须跟着分批复位");
    }
```

如果 `VertexWriterTest.java` 里没有 `import static org.junit.jupiter.api.Assertions.*;`，补上。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=VertexWriterTest`
Expected: 编译失败 — `找不到符号: 方法 hasPickableVertices()`

- [ ] **Step 3: 写实现**

在 `VertexWriter.java` 的 `flushRequested` 字段之后加：

```java
    /** 当前这批顶点里是否出现过非零的拾取 ID。{@link #reset()} 时复位。 */
    private boolean hasPickable = false;
```

在 `vertex(...)` 方法体的 `vertexCount++;` 之前插入：

```java
        if (id != 0) {
            hasPickable = true;
        }
```

在 `reset()` 方法体里，`flushRequested = false;` 之后加：

```java
        hasPickable = false;
```

在 `isFlushRequested()` 方法之后加：

```java
    /**
     * 返回当前这批顶点里是否出现过非零的拾取 ID。
     *
     * <p>供 {@code RenderBatch} 决定是否需要渲染 ID pass：整帧都没用到拾取时
     * 连清空带重画都省掉，不用拾取的应用因此零开销。
     *
     * <p>粒度是<strong>每批</strong>而不是每帧——{@link #reset()} 会清掉它，
     * 而 {@code reset()} 正是帧中途 flush 的分批边界。这样每批各自贡献自己的 ID，
     * 谁都不用回放历史。
     *
     * @return 本批是否含可拾取顶点
     */
    public boolean hasPickableVertices() {
        return hasPickable;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=VertexWriterTest`
Expected: `Tests run: 18, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异验证**（每条做完立刻还原，共 3 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `vertex` 里去掉 `if (id != 0) { hasPickable = true; }` | `出现非零ID后报告可拾取顶点`、`reset清掉可拾取标志` |
| `vertex` 改成无条件 `hasPickable = true;` | `无拾取ID时不报告可拾取顶点` |
| `reset()` 里去掉 `hasPickable = false;` | `reset清掉可拾取标志` |

三条都是可区分的：变异 1 让标志恒假（前两条断言 `assertFalse`，看着照样过，
真正抓到它的是两条 `assertTrue`），变异 2 让标志恒真，变异 3 只破坏复位。
**若哪条实测存活，先怀疑断言、再怀疑代码，然后如实上报**（见 G 节）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java \
        src/test/java/com/bingbaihanji/jfgl/renderer/VertexWriterTest.java
git commit -m "feat(pick): VertexWriter 记录本批是否含可拾取顶点

粒度是每批而非每帧：reset() 清掉标志，而它正是帧中途 flush 的分批边界。
这样每批各自贡献自己的 ID，无需回放历史，天然兼容 flush。

三条变异验证：标志恒假、标志恒真、复位漏掉。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 6.5: 修复 GL 边界层评审发现的缺陷（F1–F4 + R1）

**为什么插在这里**：Task 3–6 落地后做了一次合并代码评审，在 GL 边界层找出 4 条**真缺陷**。
Task 7 要建的正是 `PickBuffer`/`Framebuffer` 之上的 ID pass，而其中两条会让 `pickRect`
一用就抛错、抛完还把渲染画面弄坏。**先修再往下走。**

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java`
- Modify: `src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java`
- Modify: `src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/renderer/PickBufferTest.java`
- Create: `src/test/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstractionTest.java`

- [ ] **Step 1: F1 —— 区域读回不能用 `MemoryStack`**

**缺陷**：`LwjglGLAbstraction.java:200` 的 `stack.mallocInt(width * height)`。
LWJGL 的 `MemoryStack` 默认只有 **64 KB**（`Configuration.STACK_SIZE` 默认 64），
`mallocInt` 超了就抛 `java.lang.OutOfMemoryError: Out of stack space.`。
实测：16384 像素（128×128）刚好能过，**20000 像素起必抛**。
后果：`pickRect` 的框选稍大一点就整个不可用——而规格 §6 正是按 `w×h×4` 字节为大片刷选设计的。
抛的是 `Error` 不是 `Exception`，直接引出 F2。

把它切成一个**不碰 GL 的静态分配函数**，这样回归能被单测掐住（真方法无 GL 上下文会让 JVM abort，永远测不了）：

```java
    /**
     * 为区域读回分配缓冲。
     *
     * <p><strong>不能用 {@link MemoryStack}</strong>：它默认只有 64 KB
     * （{@code Configuration.STACK_SIZE} 默认 64），而这里需要
     * {@code pixels * 4} 字节。128×128 恰好是临界点，再大一点就抛
     * {@link OutOfMemoryError}——框选稍大一点即废，且抛的是 {@code Error}。
     *
     * <p>返回直接缓冲交给 GC：{@code pickRect} 是用户触发的低频查询（框选），
     * 不是每帧路径，为此维护可复用缓冲池是不必要的复杂度。
     *
     * <p>包级可见且不碰 GL，<strong>专为可测</strong>：真正的方法需要 GL 上下文，
     * 在单测里调用会让 JVM 直接 abort。
     *
     * @param pixels 像素个数，必须非负
     * @return 容量为 {@code pixels} 的直接 IntBuffer
     */
    static IntBuffer allocateReadBuffer(int pixels) {
        return BufferUtils.createIntBuffer(pixels);
    }
```

`readUnsignedIntPixels` 改成：

```java
        IntBuffer buffer = allocateReadBuffer(width * height);
        GL11.glReadPixels(x, y, width, height,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
        buffer.get(out, 0, width * height);
```

（不再有 `try (MemoryStack ...)`。若 `MemoryStack` 在本文件不再被使用，**删掉那条 import**。）

同时修 R4：`readUnsignedIntPixel` 里的 `stack.mallocInt(1)` 改成 `stack.callocInt(1)`。
`mallocInt` 不清零，若 `glReadPixels` 因 GL 错误没写入，读回的是栈上残留值——
非确定的拾取结果。

新增 `BufferUtils` 的 import：`import org.lwjgl.BufferUtils;`

创建 `src/test/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstractionTest.java`：

```java
package com.bingbaihanji.jfgl.gl;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

class LwjglGLAbstractionTest {

    @Test
    void 区域读回缓冲能装下整个帧缓冲() {
        // 988x738 是 125% 缩放下 800x600 窗口的真实设备像素数（见 CLAUDE.md）。
        // 用 MemoryStack 的话这里必抛 OutOfMemoryError: Out of stack space.。
        int pixels = 988 * 738;
        IntBuffer buffer = LwjglGLAbstraction.allocateReadBuffer(pixels);
        assertEquals(pixels, buffer.capacity());
    }

    @Test
    void 区域读回缓冲能装下远大于64KB的区域() {
        // 64 KB / 4 = 16384 像素，正是 MemoryStack 的临界点。
        IntBuffer buffer = LwjglGLAbstraction.allocateReadBuffer(16384 * 4);
        assertEquals(16384 * 4, buffer.capacity());
    }
}
```

- [ ] **Step 2: F2 —— 三条读写路径的绑定恢复必须走 `finally`**

`PickBuffer` 的 `clear()` / `readPixel()` / `readRect()` 都是「绑定 → 操作 → 恢复」的
直线代码，**中间任何一步抛错，绑定就停在拾取 FBO 上**。F1 让这条路径必然可达。

后果正是本项目最擅长产生的那类静默失败：openglfx 渲染到它自己的 FBO，
绑定没还回去，下一帧的颜色会画进拾取缓冲——**画面全黑或停在上一帧，且没有任何报错**。

`clear()`：

```java
    public void clear() {
        checkNotDisposed();
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        try {
            gl.clearIntegerColor(0);
        } finally {
            // 必须用 finally：任何 GL 调用都可能抛错，一旦抛出去而绑定停在拾取 FBO，
            // 后续所有绘制都会画进这里——画面全黑或停在上一帧，且不报错。
            gl.bindFramebuffer(previous);
        }
    }
```

`readPixel()`（`checkNotDisposed()` 加在方法开头，越界判断之前）：

```java
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        try {
            return gl.readUnsignedIntPixel(x, glY);
        } finally {
            gl.bindFramebuffer(previous);
        }
```

`readRect()` 里那段：

```java
        int[] raw = new int[readWidth * readHeight];
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        try {
            gl.readUnsignedIntPixels(x0, glY, readWidth, readHeight, raw);
        } finally {
            gl.bindFramebuffer(previous);
        }
```

- [ ] **Step 3: F3 —— 构造与重建后各清空一次**

规格 §6.2 明文承诺「从未渲染过拾取 pass → `pick` 返回 `null`、`pickRect` 返回空列表」。
但唯一的清空是懒清空（每批 `submit` 时按 `hasPickableVertices` 触发）。
`glTexImage2D(..., null)` 之后纹理内容是**规范定义的未定义值**，重建后同理——
多数驱动恰好给 0，所以看起来一切正常，正是本项目最警惕的「在多数驱动上看起来能跑」。

构造函数尾部加 `clear();`，`ensureSize` 重建之后加 `clear();`（见 Step 4 的完整代码）。

- [ ] **Step 4: F4 + R5 —— 先建后弃，并且释放后要炸而不是静默操作默认帧缓冲**

**缺陷**：`ensureSize` 是先 `dispose()` 再 `new`。构造函数抛错（FBO 不完整
`IllegalStateException`，或尺寸非正 `IllegalArgumentException`）时字段不会被替换，
仍指向**已释放**的 `Framebuffer`，其 `id()` 已是 **0——在 OpenGL 里 0 是默认帧缓冲**。
此后 `readPixel`/`readRect` 读的是**窗口自己的像素**当成 ID（「拾取到毫不相干的对象」），
`clear()` 则去擦窗口的颜色缓冲。`width()/height()` 还返回旧尺寸。全程无信号。

`ensureSize` 与构造函数改成：

```java
    public PickBuffer(GLAbstraction gl, int width, int height) {
        this.gl = gl;
        this.framebuffer = new Framebuffer(gl, width, height);
        // 新建的纹理内容是规范定义的未定义值（多数驱动恰好给 0）。不在这里清一次，
        // 「从未渲染过拾取 pass」读回的就是驱动的恩赐，而不是代码保证的「什么都没命中」。
        clear();
    }

    public void ensureSize(int width, int height) {
        checkNotDisposed();
        if (framebuffer.width() == width && framebuffer.height() == height) {
            return;
        }
        // 先建后弃：构造函数抛错时旧缓冲必须原封不动。反过来写（先 dispose 再 new）
        // 一旦 new 抛错，字段就停在已释放对象上，而它的 id() 是 0——在 OpenGL 里
        // 0 是默认帧缓冲，此后读的是窗口自己的像素当成 ID，clear 则去擦窗口的颜色。
        Framebuffer next = new Framebuffer(gl, width, height);
        framebuffer.dispose();
        framebuffer = next;
        clear();
    }
```

新增私有方法（放在 `dispose()` 之前）：

```java
    /**
     * 断言尚未被释放。
     *
     * <p>释放之后底层 FBO 的 id 变成 0，而 0 在 OpenGL 里是<strong>默认帧缓冲</strong>。
     * 不挡的话，读回拿的是窗口自己的像素（当成拾取 ID），清空擦的是窗口画面——
     * 两个都是不崩溃、只是结果悄悄错了的失败。宁可在这里炸。
     *
     * @throws IllegalStateException 已释放时
     */
    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException(
                    "PickBuffer 已释放：底层 FBO id 已是 0，而 0 在 OpenGL 里是默认帧缓冲；"
                            + "继续读会拿到窗口像素当成拾取 ID，继续清会擦掉窗口画面");
        }
    }
```

`width()`、`height()`、`readRect()` 也各加一行 `checkNotDisposed();`（`readRect` 放在方法开头）。

关于 `readRect`：它内部会调 `framebuffer.width()`，而 `width()` 现在会抛，所以守卫
**事实上**已经在，只漏了 `w <= 0 || h <= 0` 那个提前返回的分支——在已释放的缓冲上
返回空列表而不报错。那条分支无害，但"事实上在"和"写着在"是两回事，后者不依赖
调用链的形状，所以照样显式写一行。

- [ ] **Step 5: 给 `FakeGLAbstraction` 加故障注入开关**

F2 必须能在单测里被触发，否则改完还是没人防守。在 `FakeGLAbstraction` 里加：

```java
    /** 置为 true 后，所有整数读回与整数清空都抛异常，用于验证「抛错时仍恢复绑定」。 */
    public boolean throwOnIntegerCall = false;

    private void maybeThrow() {
        if (throwOnIntegerCall) {
            throw new IllegalStateException("注入的 GL 故障");
        }
    }
```

在 `clearIntegerColor`、`readUnsignedIntPixel`、`readUnsignedIntPixels`
三个方法体**开头**各调一次 `maybeThrow();`。

- [ ] **Step 6: 补测试**

在 `PickBufferTest` 里加 6 条（文件已有 `buffer(gl)` 辅助方法与 `assertNotNull` 类 import）：

```java
    @Test
    void 清空之后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.boundFramebuffer = 7;
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;          // 构造内部的清空会把绑定还回来，这里重申一次
        pb.clear();
        assertEquals(7, gl.boundFramebuffer,
                "清空后必须恢复绑定，否则后续绘制会画进拾取缓冲");
    }

    @Test
    void 读点之后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;
        pb.readPixel(1, 1);
        assertEquals(7, gl.boundFramebuffer);
    }

    @Test
    void 抛错时仍恢复帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;
        gl.throwOnIntegerCall = true;
        assertThrows(IllegalStateException.class, () -> pb.readRect(0, 0, 8, 4));
        assertEquals(7, gl.boundFramebuffer,
                "读回抛错也必须把绑定还回去——否则下一帧颜色全画进拾取缓冲，且不报错");
    }

    @Test
    void 构造后缓冲是全零() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        // 预置非零，模拟「新纹理内容是未定义值」的驱动行为
        gl.setScreenBottomUp(8, 4, new int[]{-1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1});
        PickBuffer pb = new PickBuffer(gl, 8, 4);
        assertTrue(pb.readRect(0, 0, 8, 4).isEmpty(),
                "构造时必须清一次；不清就是「在多数驱动上看起来能跑」");
    }

    @Test
    void 重建失败时旧缓冲原封不动() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.setUserPixel(1, 1, 42);
        assertThrows(IllegalArgumentException.class, () -> pb.ensureSize(0, 0));
        assertTrue(gl.deletedFramebuffers.isEmpty(),
                "先建后弃：新缓冲建失败时旧缓冲不该被释放");
        assertEquals(42, pb.readPixel(1, 1), "旧缓冲应当仍然可用");
    }

    @Test
    void 释放后读点抛异常而不是操作默认帧缓冲() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        pb.dispose();
        assertThrows(IllegalStateException.class, () -> pb.readPixel(1, 1));
    }

    @Test
    void 清空抛错时仍恢复帧缓冲绑定() {
        // 这条不能靠「读回抛错」那条代替：那条走的是 readRect，根本到不了 clear()。
        // 而不注入故障的话，直线代码和 try/finally 的恢复行为完全一致，
        // 「清空之后恢复原先的帧缓冲绑定」区分不了两者——守卫无人防守。
        FakeGLAbstraction gl = new FakeGLAbstraction();
        PickBuffer pb = buffer(gl);
        gl.boundFramebuffer = 7;
        gl.throwOnIntegerCall = true;
        assertThrows(IllegalStateException.class, () -> pb.clear());
        assertEquals(7, gl.boundFramebuffer,
                "清空抛错也必须把绑定还回去——否则后续绘制全画进拾取缓冲，且不报错");
    }
```

注意 `构造后缓冲是全零` 里 `setScreenBottomUp` 必须在 `new PickBuffer(...)` **之前**调用
（构造里的清空会把屏幕填 0）——这正是该测试要验的。

- [ ] **Step 7: 跑测试**

Run: `mvn -o test -Dtest='PickBufferTest,LwjglGLAbstractionTest'`
Expected: `Tests run: 17, Failures: 0, Errors: 0`（PickBuffer 15 + Lwjgl 2）

- [ ] **Step 8: 变异验证**（每条做完立刻还原）

| 变异 | 应失败的测试 |
|------|--------------|
| `allocateReadBuffer` 改回 `stack.mallocInt(pixels)` 的写法 | `区域读回缓冲能装下整个帧缓冲` |
| `clear()` 去掉 `finally`（恢复语句移出） | `清空抛错时仍恢复帧缓冲绑定` |
| `readRect` 去掉 `finally` | `抛错时仍恢复帧缓冲绑定` |
| 构造函数尾部去掉 `clear();` | `构造后缓冲是全零` |
| `ensureSize` 改回「先 dispose 再 new」 | `重建失败时旧缓冲原封不动` |
| `checkNotDisposed()` 改成直接 `return;` | `释放后读点抛异常而不是操作默认帧缓冲` |

**第 1 条要特别小心**：`allocateReadBuffer` 是静态方法，若改成用 `MemoryStack`，
需要 `stackPush()`/`close()`，写法会变。请如实记录你实际注入了什么以及是否失败——
**若某条存活，先怀疑检查、再怀疑代码，然后如实上报**（见 G 节）。

- [ ] **Step 9: 跑全量测试**

Run: `mvn -o test`
Expected: `Tests run: 168, Failures: 0, Errors: 0, Skipped: 2`（159 + 7 + 2）

- [ ] **Step 10: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java \
        src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java \
        src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java \
        src/test/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstractionTest.java \
        src/test/java/com/bingbaihanji/jfgl/renderer/PickBufferTest.java
git commit -F - <<'EOF'
fix(pick): 修 GL 边界层评审发现的 4 条缺陷

F1 区域读回用 MemoryStack 装缓冲，而它默认只有 64 KB：128x128 是临界点，
再大就抛 OutOfMemoryError: Out of stack space.，框选稍大一点整个不可用。
实测 20000 像素起必抛。改用 BufferUtils.createIntBuffer，并把分配切成一个
不碰 GL 的静态方法，让这条回归能被单测掐住（真方法无 GL 上下文会让 JVM abort）。
顺带把 readUnsignedIntPixel 的 mallocInt 改成 callocInt——不清零的话，
glReadPixels 没写入时读回的是栈上残留值。

F2 clear/readPixel/readRect 的绑定恢复走的是直线代码，中间抛错就不恢复，
绑定停在拾取 FBO 上，下一帧颜色全画进拾取缓冲。F1 让这条路径必然可达。
三处都改成 try/finally。此前这两条路径的绑定恢复零测试覆盖，
变异（同时删掉两处恢复）全量 159 仍全绿——本轮补上。

F3 构造与重建都不清空附件，读回的是规范定义的未定义内容，多数驱动恰好给 0，
属于「在多数驱动上看起来能跑」。构造与 ensureSize 之后各清一次。

F4 ensureSize 先 dispose 再 new，构造抛错时字段停在已释放对象上，其 id() 是 0，
而 0 在 OpenGL 里是默认帧缓冲——此后读的是窗口自己的像素当成 ID，
clear 则去擦窗口颜色。改成先建后弃，并加 checkNotDisposed 让释放后的调用炸掉
而不是静默操作默认帧缓冲。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 6.5 追加：第 7 个测试与 `readRect` 的守卫

上面的 Step 1–10 已落地（提交 `ca35d28`）。执行者报了两件事，**两件都成立**：

**1. 变异表第 2 行写错了，`clear()` 的 `finally` 无人防守**（我独立复现：
把 `clear()` 的 `finally` 拆成直线代码，`PickBufferTest` **14 个全绿**）。
两处原因叠加：`抛错时仍恢复帧缓冲绑定` 走的是 `readRect`，**根本到不了 `clear()`**；
而 `清空之后恢复原先的帧缓冲绑定` 不注入故障，直线代码与 `finally` 的恢复行为
完全一致，**区分不了两者**。Step 6 已补上第 7 个测试，Step 8 的表行已改。

**2. `readRect` 的守卫是"事实上在"而不是"写着在"**——它内部调 `framebuffer.width()`
而 `width()` 会抛，所以除了 `w <= 0 || h <= 0` 的提前返回分支之外都被覆盖到了。
Step 4 已补上显式的一行。

- [ ] **Step 11: 补 `readRect` 的显式守卫与第 7 个测试**

按 Step 4 与 Step 6 的当前版本改（`readRect` 开头加 `checkNotDisposed();`；
`PickBufferTest` 加 `清空抛错时仍恢复帧缓冲绑定`）。**不要重做其余部分**——
Step 1–10 已完成且已提交。

- [ ] **Step 12: 验证第 2 行变异现在真的失败**

把 `clear()` 的 `finally` 拆成直线代码 → 跑 `mvn -o test -Dtest=PickBufferTest`
→ **必须恰好 1 条失败**（`清空抛错时仍恢复帧缓冲绑定`，`expected: <7> but was: <100>`）
→ 反向 Edit 还原 → `git diff` 确认为空。

- [ ] **Step 13: 跑全量并提交**

Run: `mvn -o test` → `Tests run: 168, Failures: 0, Errors: 0, Skipped: 2`

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java \
        src/test/java/com/bingbaihanji/jfgl/renderer/PickBufferTest.java
git commit -F - <<'EOF'
test(pick): 补上 clear() 的绑定恢复守卫

变异表说「clear() 去掉 finally 应当有测试失败」，实测不变异的话两处都抓不到：
抛错时仍恢复帧缓冲绑定 走的是 readRect，根本到不了 clear()；
清空之后恢复原先的帧缓冲绑定 不注入故障，直线代码和 try/finally 的恢复
行为完全一致，区分不了两者。于是 clear() 的 finally 无人防守——把 finally
拆成直线代码，PickBufferTest 14 个全绿。

补第 7 个测试：注入故障后调 clear()，断言绑定仍被还回去。readRect 也补上
显式的 checkNotDisposed（原先只靠它内部调 width() 间接生效，写着比靠着好）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: RenderBatch —— ID 着色器与拾取 pass

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java`

- [ ] **Step 1: 加字段与 ID 着色器**

在 `RenderBatch.java` 的 `FRAGMENT_SHADER` 常量之后加：

```java
    /**
     * ID pass 的顶点着色器：只把拾取 ID 透传下去，位置同样已在 CPU 端烘焙好。
     *
     * <p>与颜色着色器分开、而不是在颜色着色器里多输出一个变量：
     * 颜色 pass 是热路径，不该为它用不到的东西多背一个 varying。
     *
     * <p>{@code flat} 不可省略——ID 是整数，跨三角形插值出来的中间值
     * 对应不存在的对象。
     */
    private static final String PICK_VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 3) in uint aId;
            flat out uint vId;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vId = aId;
            }
            """;

    /** ID pass 的片段着色器：直接写出 ID，不看颜色、不看 alpha、不采样纹理。 */
    private static final String PICK_FRAGMENT_SHADER = """
            #version 330 core
            flat in uint vId;
            out uint fragId;
            void main() {
                fragId = vId;
            }
            """;
```

在 `private final ShaderProgram shader;` 之后加：

```java
    /** ID pass 使用的着色器程序。 */
    private final ShaderProgram pickShader;

    /** 拾取缓冲，与颜色 pass 同尺寸。 */
    private final PickBuffer pickBuffer;

    /** 本帧的拾取缓冲是否已被清空（每帧最多清一次，懒执行）。 */
    private boolean pickBufferCleared = false;

    /** 本帧渲染过 ID pass 后为 true；{@link #beginFrame} 时复位。 */
    private boolean pickBufferValid = false;

    /** 累计执行过的 ID pass 次数，仅供校验器断言「跳过优化」确实生效。 */
    private int pickPassCount = 0;
```

**同时必须改掉 `RenderBatch` 的类级 Javadoc。** 它现在写着（第 48 行）：

> `submit(VertexWriter)` 是幂等的外观操作，**不持有"本帧已提交"之类的状态**

上一句在本步之后就**不成立了**：`pickBufferCleared` 与 `pickBufferValid` 正是跨提交存活的
每帧状态（`Gc` 一帧内会多次调用 `submit`——`endFrame` 一次 + 每次 `flushIfNeeded` 一次）。
留着不改，下一个人会照着这行注释推理，而「跳过优化」会**静默失效**：要么每批都重新清空
拾取缓冲（把前一批的 ID 抹掉，只有最后一批可拾取），要么读到上一帧的陈旧 ID。

把那段改成：

```java
 * <h2>可以在同一帧内多次调用</h2>
 * <p>{@link #submit(VertexWriter)} 每次调用都做同样的事，帧中途 flush
 * （见 {@link VertexWriter#isFlushRequested()} 的消费方契约）与帧末提交走的是同一条路径。
 * 每次提交结束时都会把 GL 状态收回到中性（解绑 VAO、解绑着色器、关闭混合与裁剪测试），
 * 因此上一批的残留不会影响下一批。
 *
 * <p><strong>但它并非无状态</strong>：拾取相关的一组标志
 * （{@code pickBufferCleared} / {@code pickBufferValid} / {@code pickPassCount}）
 * 跨同一帧内的多次提交存活。它们的<strong>唯一</strong>复位点是
 * {@link #beginFrame(int, int)}——每帧必须恰好调用一次，且要在本帧第一次
 * {@code submit} 之前。漏调或不调，表现为「只有最后一批可拾取」或
 * 「拾取到上一帧已消失的对象」，两者都不报错。
```

- [ ] **Step 2: 构造函数里创建它们**

在构造函数中 `this.whiteTexture = ...` 之后加：

```java
        this.pickShader = gl.createShader(PICK_VERTEX_SHADER, PICK_FRAGMENT_SHADER);
        // 尺寸先给 1x1 占位，第一帧 beginFrame 时会按真实尺寸重建。
        this.pickBuffer = new PickBuffer(gl, 1, 1);
```

- [ ] **Step 3: 新增 beginFrame**

**先不要动 `setViewportHeight`** —— `Gc` 还在调它，此刻删掉会让本任务结束时编译不过。
新增 `beginFrame`，把删除留给 Task 8（那一步会同时把 `Gc` 切过来）。
每个任务的提交都应该是一棵能编译、能跑测试的树。

在 `setViewportHeight` 方法**之后**加：

```java
    /**
     * 开始一帧：设置视口高度、把拾取缓冲调整到帧缓冲尺寸、复位本帧的拾取状态。
     *
     * <p>取代了早先的 {@code setViewportHeight}：尺寸调整与状态复位必须在同一处发生，
     * 分成两个方法迟早会有人只调其中一个。
     *
     * <p>拾取缓冲的尺寸检查放在这里而不是 reshape 回调里，是为了让任何来源的尺寸变化
     * 都被覆盖到——多一条路径就多一次漏掉的机会，而这里的开销只是一次整数比较。
     *
     * @param width  帧缓冲宽度（像素），必须为正
     * @param height 帧缓冲高度（像素），必须为正
     */
    public void beginFrame(int width, int height) {
        this.viewportHeight = height;
        pickBuffer.ensureSize(width, height);
        pickBufferCleared = false;
        // 本帧还没渲染 ID pass 之前，缓冲里装的是上一帧的结果。标为无效，
        // 这样 pick() 会诚实地返回「没命中」，而不是拿陈旧的 ID 去注册表里查——
        // 那会拾取到早已消失的对象，而画面完全正常。
        pickBufferValid = false;
    }
```

- [ ] **Step 4: 在 submit 里接上 ID pass**

在 `submit` 方法中，颜色 pass 的 `for` 循环结束之后、`glDisable(GL_SCISSOR_TEST);` 之前插入：

```java
        // 颜色 pass 画完后再走 ID pass：复用同一份 VBO、同一张命令表，只换程序。
        // 放在这里而不是另起一趟，是因为此刻 VAO/VBO/属性指针与 scissor 都正好是
        // 绘制所需的状态。
        if (writer.hasPickableVertices()) {
            drawPickPass(commands);
        }
```

- [ ] **Step 5: 实现 drawPickPass 与读回入口**

在 `applyScissor` 方法之后加：

```java
    /**
     * 用 ID 着色器把同一批命令重画进拾取缓冲。
     *
     * <p><strong>前置条件</strong>：调用方已绑定 VAO/VBO、已配置属性指针、
     * 已启用裁剪测试，且颜色 pass 的绘制循环刚刚结束。
     *
     * <p><strong>混合必须关闭</strong>：对整数附件开混合是无效操作。
     *
     * <p><strong>裁剪必须保持开启</strong>：ID pass 复用同一套 scissor 换算，
     * 因此被裁掉的部分不可拾取。否则用户点了看不见的地方却命中，
     * 而画面完全正常——典型的静默错误。
     *
     * @param commands 本批的绘制命令
     */
    private void drawPickPass(List<DrawCommand> commands) {
        if (!pickBufferCleared) {
            pickBuffer.clear();
            pickBufferCleared = true;
        }

        int previousFramebuffer = gl.currentFramebufferBinding();
        gl.bindFramebuffer(pickBufferId());

        // 整数附件不能开混合；ID 被插值成「零点几个对象」也没有意义。
        gl.disableBlend();
        pickShader.use();

        for (DrawCommand command : commands) {
            if (command.vertexCount() == 0) {
                continue;
            }
            applyScissor(command);
            glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
        }

        pickShader.unuse();
        // 必须恢复：openglfx 渲染到它自己的 FBO，不恢复的话下一帧会画进拾取缓冲。
        gl.bindFramebuffer(previousFramebuffer);

        pickPassCount++;
        pickBufferValid = true;
    }

    /**
     * 返回拾取缓冲的 FBO ID。
     *
     * @return FBO 的 ID
     */
    private int pickBufferId() {
        return pickBuffer.framebufferId();
    }

    /**
     * 读回一个像素的拾取 ID。
     *
     * <p>本帧没有渲染过 ID pass 时返回 0（「未命中」）而不做读回：
     * 此时缓冲里是上一帧的陈旧数据，读出来会拾取到早已消失的对象。
     *
     * @param x 用户坐标 x
     * @param y 用户坐标 y
     * @return 命中的 ID，未命中或本帧无拾取内容时为 0
     */
    public int readPickPixel(int x, int y) {
        if (!pickBufferValid) {
            return 0;
        }
        return pickBuffer.readPixel(x, y);
    }

    /**
     * 读回一块区域内的拾取命中（按 ID 升序，每个附带首次出现坐标）。
     *
     * @param x 用户坐标左边缘
     * @param y 用户坐标上边缘
     * @param w 宽度
     * @param h 高度
     * @return 区域内出现过的命中，按 ID 升序；本帧无拾取内容时为空列表
     */
    public List<PickPixel> readPickRect(int x, int y, int w, int h) {
        if (!pickBufferValid) {
            return List.of();
        }
        return pickBuffer.readRect(x, y, w, h);
    }

    /**
     * 返回累计执行过的 ID pass 次数。
     *
     * <p>仅供校验器断言「无拾取对象时整趟跳过」确实生效。没有它，
     * 那条优化就只是注释里的一句承诺。
     *
     * @return ID pass 执行次数
     */
    public int pickPassCount() {
        return pickPassCount;
    }
```

- [ ] **Step 6: 在 dispose 里释放新资源**

在 `dispose()` 的 `shader.dispose();` 之后加：

```java
        pickShader.dispose();
        pickBuffer.dispose();
```

- [ ] **Step 7: 给 PickBuffer 补一个 framebufferId()**

`drawPickPass` 用到了 `pickBuffer.framebufferId()`。在 `PickBuffer.java` 的 `clear()` 方法之前加：

```java
    /**
     * 返回底层帧缓冲的 FBO ID，供调用方自行绑定。
     *
     * @return FBO 的 ID
     */
    public int framebufferId() {
        return framebuffer.id();
    }
```

- [ ] **Step 8: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: BUILD SUCCESS；`Tests run: 168, Failures: 0, Skipped: 2`

（`beginFrame` 此刻还没有调用方，`Gc` 仍在用 `setViewportHeight`——这是刻意的，
见 Step 3 的说明。）

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java \
        src/main/java/com/bingbaihanji/jfgl/renderer/PickBuffer.java
git commit -m "feat(pick): RenderBatch 的 ID pass

同一份 VBO、同一张命令表，只换一个 ID 专用 shader 重画一遍。单独的
pickShader 而非在颜色着色器里多输出一个变量——颜色 pass 是热路径。

清空是每帧懒执行一次的：放在 beginFrame 清会白付一次全屏清空（不用拾取的
应用也付），改成「上帧用过才清」则会读到陈旧数据，拾取到早已消失的对象。
判断粒度下沉到每批（hasPickableVertices），两种毛病都没有。

ID pass 保持裁剪开启并复用同一套 scissor 换算，被裁掉的部分因此不可拾取。

新增 beginFrame（setViewportHeight 的删除留到下一个任务、与 Gc 的切换同一步，
保证每个提交都是一棵能编译的树）：尺寸调整与状态复位分在两个方法里，
迟早有人只调其中一个。"
```

---

## Task 8: Gc —— pickId、pickable、pick/pickRect

**Files:**
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt`

- [ ] **Step 1: 加 pickId 字段与注册表**

在 `var globalAlpha: Float = 1f` 之后加：

```kotlin
    /**
     * 当前拾取 ID。**0 表示不参与拾取**（默认）。
     *
     * <p>取值来自 [pickRegistry] 的分配结果，或由调用方自行指定的任意非零整数。
     * 与 [fill]、[stroke] 一样属于绘制状态，会被 [save] / [restore] 存取。
     */
    var pickId: Int = 0

    /**
     * 拾取 ID 的分配器与 `id → 对象` 映射表。
     *
     * <p>命中时 [pick] 会用它把 ID 解析回注册时给的对象，组件因此不必各自维护映射表。
     * 注册发生在**数据变化时**而非每帧；不再需要的对象必须 `unregister`，否则会一直
     * 被强引用着。
     *
     * <p>**`pickRegistry` 本身是线程安全的**（见 [PickRegistry] 的类文档），
     * 所以可以、也应当在 JavaFX 线程上随数据变化直接调 `register` / `unregister`，
     * 不必像 [pick] 那样跳线程——把注册也塞进 `onFrame` 是过度设计。
     * 注意 `Gc` 的**其余部分**仍然只能在 GL 线程用，这个 `val` 是刻意的例外。
     *
     * <p>由此产生的两个可见后果都是规格内的，**不是缺陷**，消费方别当 bug 去"修"：
     * 刚注册的对象当帧可能还没被画出来（差一帧）；刚注销的对象当帧可能仍被画着，
     * 于是命中 `PickHit(id, null, ...)`——这正是 [PickHit] 文档里
     * 「ID 已注册但载荷为 null，与 ID 未注册，都表现为 null」那一条。
     */
    val pickRegistry = PickRegistry()
```

- [ ] **Step 2: 把 pickId 加进样式栈**

把两个栈数组与 `styleDepth` 附近的注释与声明改为：

```kotlin
    /** 样式栈的整数部分：每层 [INTS_PER_STYLE_LEVEL] 个值（fill、stroke、pickId）。 */
    private var styleInts = IntArray(INITIAL_STACK_LEVELS * INTS_PER_STYLE_LEVEL)

    /** 样式栈的浮点部分：每层 [FLOATS_PER_STYLE_LEVEL] 个值（lineWidth、globalAlpha）。 */
    private var styleFloats = FloatArray(INITIAL_STACK_LEVELS * FLOATS_PER_STYLE_LEVEL)
```

把 `ensureStyleCapacity` 整个替换为：

```kotlin
    /**
     * 确保样式栈能容纳给定的层数。
     *
     * <p>两个数组按**各自的每层宽度**独立扩容：整数部分每层 3 个（fill、stroke、pickId），
     * 浮点部分每层 2 个（lineWidth、globalAlpha）。此前两者都是 2，
     * 所以共用了一个 `capacityLevels * 2` 的算法——`pickId` 进来以后那个算法对整数部分就是错的，
     * 会让栈在深层 [save] 时越界。
     *
     * @param capacityLevels 需要的层数
     */
    private fun ensureStyleCapacity(capacityLevels: Int) {
        val neededInts = capacityLevels * INTS_PER_STYLE_LEVEL
        if (neededInts > styleInts.size) {
            var size = styleInts.size * 2
            while (size < neededInts) {
                size *= 2
            }
            styleInts = styleInts.copyOf(size)
        }
        val neededFloats = capacityLevels * FLOATS_PER_STYLE_LEVEL
        if (neededFloats > styleFloats.size) {
            var size = styleFloats.size * 2
            while (size < neededFloats) {
                size *= 2
            }
            styleFloats = styleFloats.copyOf(size)
        }
    }
```

在 `companion object` 里加常量：

```kotlin
        /** 样式栈每层占用的 int 个数：fill、stroke、pickId。 */
        private const val INTS_PER_STYLE_LEVEL = 3

        /** 样式栈每层占用的 float 个数：lineWidth、globalAlpha。 */
        private const val FLOATS_PER_STYLE_LEVEL = 2
```

- [ ] **Step 3: 更新 save / restore**

`save()` 中原本的：

```kotlin
        val base = styleDepth * 2
        styleInts[base] = fill
        styleInts[base + 1] = stroke
        styleFloats[base] = lineWidth
        styleFloats[base + 1] = globalAlpha
        styleDepth++
```

替换为：

```kotlin
        val intBase = styleDepth * INTS_PER_STYLE_LEVEL
        styleInts[intBase] = fill
        styleInts[intBase + 1] = stroke
        styleInts[intBase + 2] = pickId
        val floatBase = styleDepth * FLOATS_PER_STYLE_LEVEL
        styleFloats[floatBase] = lineWidth
        styleFloats[floatBase + 1] = globalAlpha
        styleDepth++
```

`restore()` 中原本的：

```kotlin
        val base = styleDepth * 2
        fill = styleInts[base]
        stroke = styleInts[base + 1]
        lineWidth = styleFloats[base]
        globalAlpha = styleFloats[base + 1]
```

替换为：

```kotlin
        val intBase = styleDepth * INTS_PER_STYLE_LEVEL
        fill = styleInts[intBase]
        stroke = styleInts[intBase + 1]
        pickId = styleInts[intBase + 2]
        val floatBase = styleDepth * FLOATS_PER_STYLE_LEVEL
        lineWidth = styleFloats[floatBase]
        globalAlpha = styleFloats[floatBase + 1]
```

同时把 `save()` 的 KDoc 里那句「压入当前的**全部绘制状态**：变换、裁剪、填充色、描边色、线宽、全局不透明度。」
改为「……变换、裁剪、填充色、描边色、线宽、全局不透明度、拾取 ID。」

- [ ] **Step 4: beginFrame 改调 batch.beginFrame**

在 `beginFrame` 中把 `batch.setViewportHeight(height)` 替换为：

```kotlin
        batch.beginFrame(width, height)
```

然后删除 `RenderBatch.setViewportHeight` —— 这是它最后一个调用方，此刻它已经没有用了。
（在本任务里删而不是上一个任务里删，是为了让每一步的提交都能编译。）

- [ ] **Step 5: emitTriangles 传下 pickId**

把 `emitTriangles` 里的这一行：

```kotlin
                writer().vertex(
                    state.transformX(wx, wy), state.transformY(wx, wy),
                    0f, 0f, packed, 0
                )
```

替换为：

```kotlin
                writer().vertex(
                    state.transformX(wx, wy), state.transformY(wx, wy),
                    0f, 0f, packed, pickId
                )
```

并在该方法的 KDoc 里补一句：

```
     * <p>这是**全部绘制路径的唯一出口**：填充走 [emitShape]、描边走 [strokeOutline]、
     * 路径填充直接调用本方法。因此拾取 ID 只需在这里传下去，就覆盖了每一个图元。
```

- [ ] **Step 6: 加 pickable / pick / pickRect**

在 `endFrame()` 之后加：

```kotlin
    // ------------------------------------------------------------------
    // 拾取
    // ------------------------------------------------------------------

    /**
     * 在给定的拾取 ID 下执行一段绘制。
     *
     * <p>等价于 `save(); pickId = id; block(); restore()`，因此**块内的变换与裁剪改动
     * 也会在块结束时回滚**——与 `save/restore` 的语义完全一致，块是自包含的。
     *
     * <p>相比手工设 [pickId]，本方法不会因为忘记复位而让后续图元错误地继承 ID——
     * 那是这类 API 最常见的 bug。
     *
     * @param id    本块内所有图元的拾取 ID，0 表示不参与拾取
     * @param block 绘制块
     */
    fun pickable(id: Int, block: () -> Unit) {
        save()
        pickId = id
        try {
            block()
        } finally {
            restore()
        }
    }

    /**
     * 查询某个点上最上层的可拾取图元。
     *
     * <p>命中的是**像素**而不是包围盒：判定用的是 GPU 实际光栅化的结果，
     * 与画面所见完全一致。
     *
     * <p>**必须在 GL 线程上调用。** 组件响应 JavaFX 鼠标事件时请用
     * `FXGLTransfer.pickAsync`，它会把请求调度到 GL 线程并把结果送回 JavaFX 线程。
     *
     * <p>拾取只由几何决定，**与颜色和透明度无关**——`globalAlpha = 0` 的图元照样能命中。
     * 图表的「隐形热区」正是靠这个行为实现的。
     *
     * @param x 查询点 x（用户坐标，y 向下）
     * @param y 查询点 y（用户坐标，y 向下）
     * @return 命中结果；未命中、坐标越界、或本帧没有任何可拾取图元时返回 null
     */
    fun pick(x: Float, y: Float): PickHit? {
        val id = batch.readPickPixel(x.toInt(), y.toInt())
        if (id == 0) {
            return null
        }
        return PickHit(id, pickRegistry.resolve(id), x, y)
    }

    /**
     * 查询一个矩形区域内出现过的全部可拾取图元，按 ID 升序去重返回。
     *
     * <p>区域会与绘制区求交；完全在绘制区之外返回空列表（不抛异常——
     * 刷选拖到窗口外是正常操作）。代价与区域面积成正比（`w×h×4` 字节的读回）。
     *
     * @param x 区域左边缘（用户坐标）
     * @param y 区域上边缘（用户坐标，y 向下）
     * @param w 区域宽度
     * @param h 区域高度
     * @return 命中的结果列表，按 ID 升序；每个元素的 x/y 是该 ID 在区域内按行扫描
     *         **首次出现的像素坐标**，不是图元的几何代表点
     */
    fun pickRect(x: Float, y: Float, w: Float, h: Float): List<PickHit> {
        val pixels = batch.readPickRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        if (pixels.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<PickHit>(pixels.size)
        for (pixel in pixels) {
            result.add(
                PickHit(pixel.id(), pickRegistry.resolve(pixel.id()),
                    pixel.x().toFloat(), pixel.y().toFloat())
            )
        }
        return result
    }
```

- [ ] **Step 7: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: BUILD SUCCESS；`Tests run: 168, Failures: 0, Skipped: 2`

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt
git commit -m "feat(pick): Gc 的 pickId / pickable / pick / pickRect

pickId 作为第七项进 save/restore 栈，于是 pickable 只是一行糖。副作用是
块内的变换与裁剪改动也会在块尾回滚——与 save/restore 语义一致，块自包含。

ensureStyleCapacity 改成两个数组按各自每层宽度独立扩容：此前两者都是 2，
共用一个 capacityLevels*2 的算法，pickId 进来后那个算法对整数部分就是错的，
会让深层 save 时栈越界。

emitTriangles 是全部绘制路径的唯一出口，ID 只需在那里传下去。"
```

---

## Task 9: FXGLTransfer.pickAsync

**Files:**
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt`

- [ ] **Step 1: 加请求槽与 API**

在 `FXGLTransfer` 类里，`private var onFrameCallback: ((Gc) -> Unit)? = null` 之后加：

```kotlin
    /**
     * 待处理的异步拾取请求。
     *
     * <p><strong>「最新覆盖旧的」而不是队列</strong>：鼠标拖拽每秒产生几十个事件，
     * 而帧率只有 60，排队毫无意义且会累积延迟。
     *
     * <p>用 [AtomicReference] 而不是 `@Volatile` 字段：取出与清空必须是原子的，
     * 否则在「读到旧值」与「置空」之间到达的新请求会被丢掉。
     */
    private val pendingPick = AtomicReference<PickRequest?>()
```

在 `onDispose(callback: () -> Unit)` 之后加：

```kotlin
    /**
     * 异步查询某个点上最上层的可拾取图元。
     *
     * <p>这是**给 JavaFX 应用线程用的**入口：组件的鼠标事件都在那个线程上，
     * 而 [Gc] 只能在 GL 线程使用。本方法把请求记下来，在下一帧渲染完成后于 GL 线程解析，
     * 再把结果经 `Platform.runLater` **送回 JavaFX 线程**——这样回调里可以安全地
     * 碰 JavaFX 状态，不需要调用方自己再跳一次。
     *
     * <p>同一时刻只保留最新的一次请求；连续调用会覆盖前一次的回调。
     *
     * @param x        查询点 x（用户坐标，y 向下）
     * @param y        查询点 y（用户坐标，y 向下）
     * @param callback 结果回调，在 JavaFX 应用线程上被调用；未命中时参数为 null
     */
    fun pickAsync(x: Float, y: Float, callback: (PickHit?) -> Unit) {
        pendingPick.set(PickRequest(x, y, callback))
    }

    /**
     * 一次待处理的拾取请求。
     *
     * @param x        查询点 x
     * @param y        查询点 y
     * @param callback 结果回调
     */
    private class PickRequest(
        val x: Float,
        val y: Float,
        val callback: (PickHit?) -> Unit
    )
```

- [ ] **Step 2: 在帧末解析请求**

把渲染事件回调里的：

```kotlin
            if (context != null && scaledWidth > 0 && scaledHeight > 0) {
                context.beginFrame(scaledWidth, scaledHeight)
                onFrameCallback?.invoke(context)
                context.endFrame()
            }
            onRenderCallback?.invoke()
```

替换为：

```kotlin
            if (context != null && scaledWidth > 0 && scaledHeight > 0) {
                context.beginFrame(scaledWidth, scaledHeight)
                onFrameCallback?.invoke(context)
                context.endFrame()
                // 必须在 endFrame 之后：ID pass 是在提交时渲染的，
                // 提前读会拿到本帧尚未写入的缓冲。
                resolvePendingPick(context)
            }
            onRenderCallback?.invoke()
```

并在类里加：

```kotlin
    /**
     * 取出待处理的拾取请求并在 GL 线程上解析，结果经 JavaFX 线程送达。
     *
     * <p>先 `getAndSet(null)` 再解析：解析期间到达的新请求会被保留下来，
     * 留给下一帧处理，而不是被这次清空顺手丢掉。
     *
     * @param context 当前帧的绘制上下文
     */
    private fun resolvePendingPick(context: Gc) {
        val request = pendingPick.getAndSet(null) ?: return
        val hit = context.pick(request.x, request.y)
        Platform.runLater { request.callback(hit) }
    }
```

- [ ] **Step 3: 补 import**

在文件顶部的 import 区加：

```kotlin
import com.bingbaihanji.jfgl.renderer.PickHit
import javafx.application.Platform
import java.util.concurrent.atomic.AtomicReference
```

- [ ] **Step 4: 编译**

Run: `mvn -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt
git commit -m "feat(pick): FXGLTransfer.pickAsync 线程安全入口

组件的鼠标事件在 JavaFX 线程，而 Gc 只能在 GL 线程。让每个组件自己写这个
跳转迟早有人写错——直接调用就是跨线程 GL 调用，崩得毫无规律。

请求用「最新覆盖旧的」槽位而非队列（拖拽每秒几十个事件、帧率只有 60）；
用 AtomicReference 的 getAndSet 而非 volatile 字段，否则读到旧值与置空之间
到达的新请求会被丢掉。回调经 Platform.runLater 送回 JavaFX 线程。"
```

---

## Task 10: PickVerifier 与三条变异验证

**Files:**
- Create: `src/main/kotlin/com/bingbaihanji/jfgl/example/PickVerifier.kt`
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt`（加 `pickPassCountForTest` 转发）

**为什么不扩展现有的 `PipelineVerifier`**：它断言「画面只有 6 种颜色」和若干精确包围盒，
往它的场景里加拾取用的图形会把这些断言全部推翻。新建一个专用校验器既能保住原有回归网，
又能给拾取断言一个干净的场景。

- [ ] **Step 1: 写校验器**

创建 `src/main/kotlin/com/bingbaihanji/jfgl/example/PickVerifier.kt`：

```kotlin
package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kotlin.system.exitProcess

/**
 * GPU 拾取的端到端**像素级校验器**。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>拾取是本项目里最危险的一类子系统：**一个错误的拾取实现不会让任何画面变坏**，
 * 只会让点击落在错误的对象上——而这在肉眼看来完全正常。单元测试也拦不住，
 * 因为它们测不到 GL 光栅化的结果。
 *
 * <p>所以这里用与 [PipelineVerifier] 相同的纪律：画一个每个图元 ID 都已知的场景，
 * 在已知坐标上查询，逐条断言结果。任何一条不成立就以非零码退出。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
 *     -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败。它自己关窗退出，不需要手动关闭。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/**
 * 校验器启动入口。
 *
 * <p>函数名不叫 `main`：同包已有一个顶层 `main()`（[PipelineExample]），
 * 两个同名顶层函数会让 `import ...example.main` 报重载歧义，而同包内无法用别名区分。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，上面文档里的命令行因此照常可用。
 */
@JvmName("main")
fun pickVerifyMain() {
    Application.launch(PickVerifierApp::class.java)
}

class PickVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /**
     * **刚刚渲染完的那一帧**的序号（从 0 开始）。
     *
     * <p>语义是「已完成」而不是「进行中」：{@code onFrame} 里 [drawScene] 读到的值与
     * 随后 {@code onRender} 里 [verifyOnce] 读到的值相同，两边对「现在是第几帧」没有分歧。
     */
    private var rendered = 0

    // 每个图形一个 ID。0 号不在此列——它恒定表示「什么都没命中」。
    private val idA = 1
    private val idB = 2
    private val idStroke = 3
    private val idTransparent = 4
    private val idClipped = 5

    // 图形几何（用户坐标）
    private val aX = 50f; private val aY = 50f; private val aW = 200f; private val aH = 100f
    private val bX = 150f; private val bY = 100f; private val bW = 200f; private val bH = 100f
    private val sX = 400f; private val sY = 50f; private val sW = 150f; private val sH = 100f
    private val tX = 50f; private val tY = 250f; private val tW = 150f; private val tH = 100f
    private val clipX = 400; private val clipY = 250; private val clipW = 100; private val clipH = 100

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        // 注册 payload，用来验证「ID 能解析回对象」这条链路。
        bridge.gc()?.let { gc ->
            gc.pickRegistry.register("A")
            gc.pickRegistry.register("B")
            gc.pickRegistry.register("Stroke")
            gc.pickRegistry.register("Transparent")
            gc.pickRegistry.register("Clipped")
        }

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Pick Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 画场景。
     *
     * <p>帧 0 与帧 1 **不带任何拾取 ID**，用来验证「整帧无拾取对象时 ID pass 被跳过」。
     * 帧 2 起才带上 ID。这个划分与 [verifyOnce] 里的两处计数断言是一对的。
     */
    private fun drawScene(gc: Gc) {
        val withIds = rendered >= 2

        // A：只被 B 覆盖一部分
        gc.pickId = if (withIds) idA else 0
        gc.fill = 0xFFCC0000.toInt()
        gc.fillRect(aX, aY, aW, aH)

        // B：后画，压在 A 上面 → 重叠处 B 赢
        gc.pickId = if (withIds) idB else 0
        gc.fill = 0xFF00CC00.toInt()
        gc.fillRect(bX, bY, bW, bH)

        // 纯描边：只有边可拾取，内部不可
        gc.pickId = if (withIds) idStroke else 0
        gc.stroke = 0xFF0000FF.toInt()
        gc.lineWidth = 6f
        gc.strokeRect(sX, sY, sW, sH)

        // 全透明填充：肉眼看不见，但必须仍可拾取（隐形热区）
        gc.pickId = if (withIds) idTransparent else 0
        gc.fill = 0xFF00CCCC.toInt()
        gc.globalAlpha = 0f
        gc.fillRect(tX, tY, tW, tH)
        gc.globalAlpha = 1f

        // 被裁剪：大矩形只画出与裁剪区的交集，因此也只有交集可拾取
        gc.pickId = if (withIds) idClipped else 0
        gc.fill = 0xFFCC00CC.toInt()
        gc.save()
        gc.clipRect(clipX.toFloat(), clipY.toFloat(), clipW.toFloat(), clipH.toFloat())
        gc.fillRect(350f, 200f, 300f, 300f)
        gc.restore()
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        val justRendered = rendered
        rendered++

        // 帧 0 与帧 1 画的全是 ID=0 的图元：一趟 ID pass 都不该跑。
        // 这条断言必须在带上 ID 之前取样——等到校验帧再看，计数里已经混进了
        // 后面那些带 ID 的帧，就什么都证明不了了。
        if (justRendered == 1) {
            reportSkipOptimization(bridge, expected = 0,
                detail = "前两帧均无拾取 ID")
            return
        }
        // 帧 2、3、4 带 ID，各跑一趟；此刻刚好三趟。
        if (justRendered < 4) return

        val gc = bridge.gc() ?: return

        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        // PickHit / PickPixel 是 Java record，从 Kotlin 一律用显式访问器调用
        // （hit.id() 而不是 hit.id）：属性语法依赖 Kotlin 对 record 组件的处理，
        // 显式调用则永远是合法的 Java 方法调用。
        fun expectPick(label: String, x: Float, y: Float, expectedId: Int) {
            val hit = gc.pick(x, y)
            val actual = hit?.id() ?: 0
            report(label, actual == expectedId,
                "($x,$y) 实际=$actual 期望=$expectedId")
        }

        // 标题与「跳过优化」一节已在第 1 帧处打印过了，这里接着往下走。
        println("\n-- 跳过优化 --")
        // 帧 0、1 无 ID（已在第 1 帧处断言过为 0），帧 2、3、4 各跑一趟 → 恰好 3。
        // 这个数字与 drawScene 的帧划分是一对的：改动任何一边都要同步另一边。
        report("有拾取对象时每帧恰好一趟 ID pass", bridge.pickPassCountForTest() == 3,
            "ID pass 执行次数=${bridge.pickPassCountForTest()}，期望 3（帧 2、3、4）")

        println("\n-- 点查询 --")
        expectPick("A 独占区域命中 A", 100f, 80f, idA)
        expectPick("重叠区域取最上层（B）", 200f, 120f, idB)
        expectPick("B 独占区域命中 B", 300f, 180f, idB)
        expectPick("A 左边缘外 1px 未命中", aX - 1f, aY + 30f, 0)
        expectPick("A 上边缘外 1px 未命中", aX + 30f, aY - 1f, 0)
        expectPick("描边边缘命中", sX + sW / 2f, sY, idStroke)
        expectPick("描边内部未命中（纯描边不填充）", sX + sW / 2f, sY + sH / 2f, 0)
        expectPick("全透明图元仍可拾取", tX + tW / 2f, tY + tH / 2f, idTransparent)
        expectPick("裁剪区内命中", 420f, 270f, idClipped)
        expectPick("被裁掉的区域未命中", 380f, 270f, 0)
        expectPick("画面空白处未命中", 700f, 560f, 0)

        println("\n-- payload 解析 --")
        val hitA = gc.pick(100f, 80f)
        report("命中结果能解析回注册对象", hitA?.payload() == "A",
            "payload=${hitA?.payload()}")

        println("\n-- 矩形区域查询 --")
        fun expectRect(label: String, x: Float, y: Float, w: Float, h: Float, expected: Set<Int>) {
            val actual = gc.pickRect(x, y, w, h).map { it.id() }.toSet()
            report(label, actual == expected, "实际=$actual 期望=$expected")
        }
        expectRect("区域内只有 A", 60f, 60f, 80f, 40f, setOf(idA))
        expectRect("覆盖 A 与 B", 60f, 60f, 320f, 160f, setOf(idA, idB))

        // 首次出现坐标：区域从 (60,60) 起逐行扫描。A 在区域左上角就出现；
        // B 要到 y=100 那一行、且 x 越过 A 的右边界（250）之前的 150 才出现。
        // 这一条专门钉住 readRect 的扫描方向——回读结果自下而上，行序反了会得到
        // 上下颠倒的坐标，而 ID 集合完全正确，光看集合发现不了。
        val abHits = gc.pickRect(60f, 60f, 320f, 160f).associateBy { it.id() }
        val aAt = abHits[idA]
        val bAt = abHits[idB]
        report("A 首次出现坐标", aAt != null && aAt.x() == 60f && aAt.y() == 60f,
            "实际=(${aAt?.x()},${aAt?.y()}) 期望=(60.0,60.0)")
        report("B 首次出现坐标", bAt != null && bAt.x() == 150f && bAt.y() == 100f,
            "实际=(${bAt?.x()},${bAt?.y()}) 期望=(150.0,100.0)")
        expectRect("覆盖裁剪区与描边", 390f, 45f, 180f, 320f, setOf(idStroke, idClipped))
        expectRect("完全在画面外", -500f, -500f, 10f, 10f, emptySet())
        expectRect("拖到画面外仍返回交集部分", -500f, -500f, 700f, 700f, setOf(idA, idB))

        println("\n-- 状态栈 --")
        gc.save()
        gc.pickId = idA
        gc.pickable(idB) {
            report("pickable 块内 ID 生效", gc.pickId == idB, "块内 pickId=${gc.pickId}")
        }
        report("pickable 块结束后 ID 复原", gc.pickId == idA, "块后 pickId=${gc.pickId}")
        gc.restore()
        report("restore 后 ID 回到 0", gc.pickId == 0, "pickId=${gc.pickId}")

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    /**
     * 断言「无拾取对象时整趟跳过 ID pass」。
     *
     * <p>这是**唯一一条在取样帧当场判定**的断言（其余都攒到校验帧统一报告）：
     * 它必须在带上拾取 ID **之前**取样——等到校验帧再看，计数里已经混进了后面那些
     * 带 ID 的帧，就什么都证明不了了。
     *
     * @param bridge   桥接对象
     * @param expected 期望的 ID pass 执行次数
     * @param detail   说明文字
     */
    private fun reportSkipOptimization(bridge: FXGLTransfer, expected: Int, detail: String) {
        val actual = bridge.pickPassCountForTest()
        val ok = actual == expected
        println("=== JFGL 拾取校验（帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}）===")
        println("\n-- 跳过优化 --")
        println("  [${if (ok) "PASS" else "FAIL"}] 无拾取对象时不渲染 ID pass — " +
                "$detail，实际=$actual 期望=$expected")
        if (!ok) {
            println("\n=== 失败 1 项 ===")
            Platform.exit()
            exitProcess(1)
        }
    }

    override fun stop() {
        transfer?.dispose()
    }
}
```

- [ ] **Step 2: 给 FXGLTransfer 加一个转发方法**

校验器用到了 `bridge.pickPassCountForTest()`。在 `FXGLTransfer` 的 `gc()` 方法之后加：

```kotlin
    /**
     * 返回累计执行过的 ID pass 次数，**仅供校验器断言「跳过优化」确实生效**。
     *
     * <p>没有它，那条优化就只是注释里的一句承诺——而「优化悄悄失效」
     * 正是本项目最该防的那类问题。生产代码不应依赖它。
     *
     * @return ID pass 执行次数；GL 未初始化时为 0
     */
    fun pickPassCountForTest(): Int = renderBatch?.pickPassCount() ?: 0
```

- [ ] **Step 3: 注册 payload 的时机有问题，改成在 onInit 里**

上面 `start` 里用 `bridge.gc()?.let { ... }` 注册，但**此刻 GL 还没初始化**，`gc()` 返回 null，
注册会静默不执行——然后「payload 解析」那条断言就会失败。

把 `start` 里那段改为使用 `onInit` 回调：

```kotlin
        bridge.onInit {
            bridge.gc()?.let { gc ->
                gc.pickRegistry.register("A")
                gc.pickRegistry.register("B")
                gc.pickRegistry.register("Stroke")
                gc.pickRegistry.register("Transparent")
                gc.pickRegistry.register("Clipped")
            }
        }
```

并把 `start` 中原先那段直接注册的代码删掉。

（ID 恰好是 1..5，与上面 `idA..idClipped` 的取值一致，因为注册表从 1 开始递增分配。
这个耦合是刻意写明的：如果注册顺序变了，这段与上面的常量必须一起改。）

- [ ] **Step 4: 编译并运行**

Run:
```bash
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```
Expected: 全部 PASS，退出码 0。

任何一条 FAIL 都要先查清原因再继续——**不要为了让校验器通过而放宽断言**。

- [ ] **Step 5: 变异验证一 —— 去掉 y 翻转**

把 `PickBuffer.readPixel` 里的

```java
        int glY = framebuffer.height() - 1 - y;
```

临时改成

```java
        int glY = y;
```

Run: 重跑上面的校验器命令
Expected: **必须失败**（多条点查询断言 FAIL），退出码 1。

如果它仍然全部通过，说明场景在垂直方向上是对称的、断言没有区分力——那就必须把
图形改成垂直不对称的，直到去掉翻转能确实导致失败为止。

确认失败后**改回来**。

- [ ] **Step 6: 变异验证二 —— 让 ID pass 忽略裁剪**

把 `RenderBatch.drawPickPass` 里的

```java
            applyScissor(command);
```

临时注释掉。

Run: 重跑校验器
Expected: **必须失败**，且失败项应包含「被裁掉的区域未命中」——它会变成命中 `idClipped`。

确认后**改回来**。

- [ ] **Step 7: 变异验证三 —— 反转 readRect 的行扫描方向**

把 `PickBuffer.readRect` 里的

```java
        for (int row = readHeight - 1; row >= 0; row--) {
```

临时改成

```java
        for (int row = 0; row < readHeight; row++) {
```

Run: 重跑校验器
Expected: **必须失败**，且失败项应是「A 首次出现坐标」与「B 首次出现坐标」——
坐标会被上下翻转，而**两个 ID 集合断言全部照常通过**。

这正是这条断言存在的理由：它拦的是一个「返回的 ID 完全正确、只有坐标是错的」
的缺陷，光看集合发现不了。如果这一变异没有导致失败，说明断言没咬住，必须改。

确认后**改回来**。

- [ ] **Step 8: 确认回滚干净**

Run: `git diff`（应无输出）；重跑校验器（应全部 PASS，退出码 0）。

- [ ] **Step 9: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/example/PickVerifier.kt \
        src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt
git commit -m "test(pick): 拾取的像素级端到端校验器 + 三条变异验证

拾取是最危险的子系统：一个错误的实现不会让画面变坏，只会让点击落在错误的
对象上，肉眼完全正常，单元测试也够不着。所以用像素口径。

不扩展 PipelineVerifier：它断言「画面只有 6 种颜色」与若干精确包围盒，
往它的场景里加图形会把这些断言全部推翻，新建专用校验器既保住原有回归网
又给出干净场景。

覆盖：点查询（独占区/重叠取最上层/边缘外 1px/纯描边内部/全透明/被裁剪区）、
payload 解析、矩形区域查询（含首次出现坐标、拖到画面外只取交集）、
跳过优化计数、pickable 作用域与状态栈复原。

变异验证（均已实际注入确认失败后回滚）：
1. 去掉 PickBuffer.readPixel 的 y 翻转 → 点查询断言必须失败
2. 让 drawPickPass 忽略 applyScissor → 「被裁掉的区域未命中」必须失败
3. 反转 readRect 的行扫描方向 → 「首次出现坐标」必须失败，而 ID 集合断言
   应当照常通过——这条正是为「ID 全对、只有坐标错」的缺陷准备的"
```

---

## Task 11: 文档更新

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: 更新 CLAUDE.md 的「已实现 vs 未实现」**

把「**GPU 拾取**（子项目 C）：顶点格式的 `id` 属性已就位，缺 ID 通道 FBO 与读回。」
这一条从「未实现 / 待办」移到「可用（依赖 GL 上下文）」区块，改为：

```markdown
`renderer/PickRegistry`（ID 分配与 `id→对象` 映射，纯内存可单测）、
`renderer/PickBuffer`、`PickHit`、`Gc` 的 `pickId` / `pickable` / `pick` / `pickRect`、
`FXGLTransfer.pickAsync`
```

- [ ] **Step 2: 在 CLAUDE.md 补一节拾取的使用要点**

在「坐标与单位约定」之后插入：

```markdown
### 拾取

`gc.pickId = n` 给后续图元打标，`gc.pickable(n) { ... }` 是它的作用域版本（等价于
`save/pickId/restore`，块内的变换与裁剪改动也会回滚）。`gc.pick(x, y)` / `pickRect` 查询。

- **ID 0 表示不参与拾取**，也是「什么都没命中」的返回值。注册表 `pickRegistry`
  分配的 ID 从 1 开始，永不返回 0。
- **拾取只由几何决定，与颜色和透明度无关**：`globalAlpha = 0` 的图元照样能命中。
  图表的「隐形热区」（比数据点大一圈的透明矩形）就是靠这个行为。**这是刻意保留的，
  不要"顺手修好"它**——有测试钉着。
- **裁剪生效**：被 `clipRect` 裁掉的部分不可拾取，与画面一致。
- **只返回最上层**：重叠时后画的赢。要"全部重叠对象"需要逐对象多趟渲染，不在范围内。
- **组件在 JavaFX 线程响应鼠标事件时用 `FXGLTransfer.pickAsync`**，不要直接调 `Gc.pick`
  ——那是跨线程 GL 调用，崩得毫无规律。
- 注册发生在**数据变化时而非每帧**；不再用的对象要 `unregister`，否则一直被强引用着。
```

- [ ] **Step 3: 在 CLAUDE.md 的验证章节加上拾取校验器**

在「怎么验证改动」一节的第 1 条里，把运行命令区补上：

```markdown
改**拾取**路径后跑 `PickVerifier`（同样回读像素、断言精确 ID，退出码 0/1）：

```bash
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```
```

并补一句：「拾取尤其危险：**错误的拾取不会让任何画面变坏**，只会让点击落在错误的对象上。」

- [ ] **Step 4: 更新 README.md**

在 API 章节的「路径」之后加一节：

```markdown
### 拾取

```kotlin
// 注册：数据变化时做一次，不是每帧
val id = gc.pickRegistry.register(myDataPoint)

// 打标：状态字段（进 save/restore 栈）
gc.pickId = id
gc.fillCircle(x, y, 4f)

// 或作用域块，块结束自动复原
gc.pickable(id) {
    gc.fillCircle(x, y, 4f)
}

// 查询（GL 线程）
val hit = gc.pick(mouseX, mouseY)          // 最上层命中，PickHit?
val hits = gc.pickRect(x, y, w, h)         // 区域内的全部命中

// JavaFX 线程（鼠标事件里）用异步版本
bridge.pickAsync(mouseX, mouseY) { hit ->
    // 回调在 JavaFX 线程上执行
    label.text = hit?.payload?.toString() ?: "无"
}
```

拾取是**像素级**的（判定用 GPU 实际光栅化的结果，与所见一致），且**只看几何**——
全透明的图元照样能命中，图表的隐形热区正是靠这个行为。
```

- [ ] **Step 5: 跑全量测试与两个校验器**

Run:
```bash
mvn -o test
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```
Expected: 测试 168 通过 / 0 失败 / 2 跳过；两个校验器都退出码 0。

- [ ] **Step 6: 提交**

```bash
git add CLAUDE.md README.md
git commit -m "docs: 补拾取的使用要点与验证方式"
```

---

## 完成标准

- [ ] `mvn -o test` → 168 通过 / 0 失败 / 2 跳过
- [ ] `PipelineVerifier` 退出码 0（原有回归网未被破坏）
- [ ] `PickVerifier` 退出码 0
- [ ] 三条变异验证都**实际注入并确认失败**过，且已回滚（`git diff` 为空）
- [ ] `CLAUDE.md` / `README.md` 已更新
- [ ] 未使用 `mvn exec:java`

## 已知遗留（不在本计划范围）

- PBO 异步读回（见 spec 第 11 节的偏差说明）
- 套索/多边形区域查询
- 同一点下「全部重叠对象」的列表
- 拾取缓冲的可视化调试视图
