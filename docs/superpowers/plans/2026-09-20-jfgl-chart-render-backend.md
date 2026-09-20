# JFGL 子项目 D-②（GPU 绘制后端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `chart/` 算出来的东西真正画到屏幕上——数据以 4 字节/点常驻 GPU，滚动与缩放全走 uniform，CPU 每帧只上传新增的那几个点。

**Architecture:** 数据系列用 instancing：一个共享的单位四边形配 divisor=0，两端的 y 值配 divisor=1（同一个缓冲、两个字节偏移，所以每点只存一次）。顶点着色器把"数值 → 屏幕 → NDC"整个算出来，几何在 GPU 侧生成。轴、网格、刻度文字继续走现有 `Gc` 管线，靠新增的 `Gc.flush()` 控制帧内 z 序。拾取复用 `RenderBatch` 的 ID pass，走"系列级 ID + CPU 定位到点"。

**Tech Stack:** Java 21（几何与渲染热路径）+ Kotlin 2.3（门面与校验器）、LWJGL 3.3.6、openglfx-lwjgl、GL 4.6 compatibility（实测）、JUnit 5

---

## 开工前必读

1. `docs/superpowers/specs/2026-09-20-jfgl-chart-render-backend-design.md` —— 本计划的规格。
2. `CLAUDE.md` 的「怎么验证改动」一节。**本项目的多数缺陷是「静默错误输出」：编译通过、单测全绿、画面却是错的。**
3. 记忆文件 `gl-render-layer-facts.md`（渲染层的能与不能）与 `jfgl-silent-output-defects.md`。

**两条派单纪律（本轮之前出过真实事故，必须沿用）：**
> **一、只 `git add` 你 `Files:` 里列出的确切路径。** 不要用 `git add -A` / `git add .`。工作区里别人的未提交文件不是你的责任。
>
> **二、禁止 `git reset` / `git rebase` / `git commit --amend`。** 发现自己的提交被污染，**停下来报告**，不要自行修历史。

**变异验证的规矩**：报告「变异存活 + 为什么」比报告「全部杀死」更有价值。对存活的变异**做对照实验证明变异是活的**（改 fixture、换扫描范围），把"没抓住"变成可修的诊断。

**命令里的 `-D` 必须加引号**（某些 shell 会把 `-D` 前缀吃掉）。**永远不要用 `mvn exec:java`**，它在这个项目里必崩。

> **已知陷阱：`util/Rect` 是 public 可变字段，不是访问器。**
> 用 `rect.x` / `rect.y` / `rect.width` / `rect.height`，**不是** `rect.x()`。
> （`math/Vec2` 才是访问器风格 `v.x()` — 两者不一致，容易顺手写错。）
> 本计划最初有 37 处写成了访问器形式，会让 **Task 6 / Task 10 / Task 13** 的示例代码
> **编译不过**；已全部纠正。**Task 10 与 Task 13 尚未实现，照抄时务必用字段。**
>
> 顺带记一条：`Rect` 可变而 `ChartRenderLayout` 直接持有调用方的实例、却在构造时快照了轴窗口值——
> 调用方构造后原地改 `rect` 的字段会让映射静默偏移。当前用法（每次 `draw` 新建）安全，
> **Task 14 若要把 layout 缓存起来，必须重新审视这一条。**

---

## 文件结构

**新建（主）** —— `src/main/java/com/bingbaihanji/jfgl/chartrender/`

| 文件 | 职责 | 能单测吗 |
|---|---|---|
| `WindowRange.java` | 可见窗口 → 实例区间（纯算术） | **能**（全量单测） |
| `ChartRenderLayout.java` | 数值/序号 ↔ 屏幕（纯算术） | **能**（含与 `Axis` 的一致性测试） |
| `SeriesUploadPlan.java` | 增量上传的字节区间（纯算术） | **能**（全量单测） |
| `SeriesShaders.java` | GLSL 源码常量 | 否 |
| `SeriesBuffer.java` | 一个系列的 GPU 常驻缓冲 | 部分（靠 `FakeGLAbstraction`） |
| `GLRenderContext.java` | `RenderContext` 的子接口 + 具体实现 | 否 |
| `LineSeriesRenderer.java` | 折线族渲染器 | 否 |
| `ScatterSeriesRenderer.java` | 散点渲染器 | 否 |
| `ChartRenderer.java` | 入口：`draw(Chart, Rect)` | 否 |

**新建（测试与校验）**

- `src/test/java/com/bingbaihanji/jfgl/chartrender/WindowRangeTest.java`
- `src/test/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayoutTest.java`
- `src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlanTest.java`
- `src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesBufferTest.java`
- `src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt`

**修改**

- `src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java` —— +3 个方法（Task 1）
- `src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java` —— +3 个转发（Task 1）
- `src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java` —— 跟上接口 + 记录缓冲调用（Task 1）
- `src/main/java/com/bingbaihanji/jfgl/gl/ShaderProgram.java` —— vec4 + location 缓存（Task 2）
- `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt` —— `flush()` + `charts`（Task 3、11）
- `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java` —— `withPickPass`（Task 4）
- `src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineVerifier.kt` —— +z 序断言（Task 3）
- `CLAUDE.md`、`README.md`（Task 15）

**不动**：`chart/` 下的**任何一行**。`ChartPackageIsolationTest` 递归守着它，一旦有文件引用 `gl/`、`renderer/`、`text/`、`geom/` 就会失败——② 的代码放在 `chartrender/`（`chart` 的兄弟目录）正是为了绕开这个范围。

---

## Task 1: `GLAbstraction` 的三个新方法

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java`
- Modify: `src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java`
- Modify: `src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java`

- [ ] **Step 1: 在 `GLAbstraction` 里加三个方法声明**

在 `uploadVboBytes(ByteBuffer data);` 之后、`deleteVao` 之前插入：

```java
    /**
     * 把数据写到 VBO 的指定字节偏移处，<strong>不重新分配缓冲</strong>。
     *
     * <p><strong>前置条件：目标 VBO 的容量必须已经够大。</strong>本方法不扩容——
     * {@code VertexBuffer.grow()} 是"删旧建新"，而扩容会让 VAO 里记录的数据缓冲绑定失效
     * （见 {@code RenderBatch.configureVaoAttributes} 里的说明）。因此调用方必须在创建时
     * 就定死容量，运行期永不增长。
     *
     * <p>越界写入是未定义行为：驱动可能报错，也可能静默损坏别的数据。
     *
     * @param offsetBytes 相对缓冲起点的字节偏移，必须 ≥ 0
     * @param data        数据，position 为 0、limit 为有效字节数
     */
    void uploadVboSubData(int offsetBytes, ByteBuffer data);

    /**
     * 设置某个顶点属性的实例除数。
     *
     * <p>0 = 每顶点取一次（默认），1 = 每实例取一次。图表的数据缓冲靠它把
     * "每个线段一份的两个端点 y 值"供给每个实例。
     *
     * @param index   顶点属性位置
     * @param divisor 除数，必须 ≥ 0
     */
    void setVertexAttribDivisor(int index, int divisor);

    /**
     * 实例化绘制，并指定<strong>实例属性的起始实例号</strong>。
     *
     * <p><strong>为什么不能只用 {@code glDrawArraysInstanced}</strong>：实例属性是按
     * {@code gl_InstanceID} 取的，而它<strong>每次都从 0 开始</strong>。环形缓冲里
     * "环绕点之后那一小段"的物理槽位不从 0 开始，普通版本没有任何办法把属性偏移过去——
     * 于是会取到错误的实例数据，<strong>而且不报错</strong>，只会画出一条乱线。
     * {@code baseInstance} 正是补这个偏移用的。
     *
     * @param mode          图元类型（如 {@code GL_TRIANGLE_STRIP}）
     * @param first         顶点数组的起始下标
     * @param count         顶点数
     * @param instanceCount 实例数
     * @param baseInstance  实例属性的起始实例号
     */
    void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                         int instanceCount, int baseInstance);
```

- [ ] **Step 2: 在 `LwjglGLAbstraction` 里加转发**

先在文件顶部的 import 区加上这两行（其余 import 已在）：

```java
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
```

再在 `uploadVboBytes` 的实现之后加：

```java
    @Override
    public void uploadVboSubData(int offsetBytes, ByteBuffer data) {
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offsetBytes, data);
    }

    @Override
    public void setVertexAttribDivisor(int index, int divisor) {
        GL33.glVertexAttribDivisor(index, divisor);
    }

    @Override
    public void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                                int instanceCount, int baseInstance) {
        GL42.glDrawArraysInstancedBaseInstance(mode, first, count, instanceCount, baseInstance);
    }
```

- [ ] **Step 3: 在 `FakeGLAbstraction` 里跟上接口**

`FakeGLAbstraction` 原本对 VBO 相关方法一律抛 `UnsupportedOperationException`（"真调到了说明走偏了"）。图表路径**会真的用到** VBO，所以要把这几个方法从"抛异常"改成"记录"。

把这四行从文件末尾的抛异常区**删掉**：

```java
    @Override public int createVbo() { throw new UnsupportedOperationException(); }
    @Override public void bindVbo(int vbo) { throw new UnsupportedOperationException(); }
    @Override public void deleteVbo(int vbo) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboData(float[] data) { throw new UnsupportedOperationException(); }
```

> **第四条是实施期补的**（执行 Task 1 时发现的规格缺口）：Task 8 的 `SeriesBuffer`
> 构造器要靠 `gl.uploadVboData(new float[capacity + 1])` 给缓冲定容，
> 不放开的话 `SeriesBufferTest` 一跑就撞异常。
>
> **按需放开，不要一次全放开。** 每多放开一个方法，就少一处"走偏了就报错"的护栏。
> `createVao` / `bindVao` / `deleteVao` / `uploadVboBytes` / `uploadVboData(int[])`
> **继续抛异常**——它们没有任何单元测试会走到，放开只会削弱守卫。
> 后面哪个任务真的需要，那时再放开那一个（并把它记在这里）。

再在类里加上：

```java
    // —— 顶点缓冲路径（图表后端用）——
    //
    // 这一组原本是抛 UnsupportedOperationException 的（"真调到了说明走偏了"）。
    // 图表后端确实要建自己的 VBO，所以其中几个改成记录。
    //
    // **只放开 Task 8 真正会用到的那些。** 计划对"放开抛异常的方法"要求按需放开，
    // 同一条纪律对"新增字段"一样适用——预先加字段，就是预先卸掉护栏。

    /** 创建的 VBO 名字。 */
    public final List<Integer> createdVbos = new ArrayList<>();

    /** 删除的 VBO 名字。 */
    public final List<Integer> deletedVbos = new ArrayList<>();

    /** 当前绑定的 VBO。越界检查要靠它判断这次上传落在哪个缓冲上。 */
    public int boundVbo = 0;

    /**
     * 每个 VBO 的已分配字节数，由定容调用（{@code uploadVboData(float[])}）记录。
     *
     * <p><strong>必须按 VBO 名字记，不能只留一个标量</strong>——理由与
     * {@code r8Widths} 完全相同：标量记的是"最后一次调用"，一旦同时存在多个 VBO，
     * 检查就会按错误的容量算，于是假实现悄悄放过真正的越界。
     */
    private final Map<Integer, Integer> vboCapacityBytes = new HashMap<>();

    /**
     * 每次 uploadVboSubData 的记录："VBO名@偏移:字节数"。
     *
     * <p>第一个字段是<strong>目标 VBO</strong>，不是多余的：Task 11 之后会同时存在多个
     * VBO（一个系列一个），而"上传落到了别的系列的缓冲上"是画面正常、只是数据错的一类
     * 缺陷——记录里必须能问出这件事。
     */
    public final List<String> vboSubDataCalls = new ArrayList<>();

    @Override
    public int createVbo() {
        int id = nextId++;
        createdVbos.add(id);
        return id;
    }

    @Override
    public void bindVbo(int vbo) {
        boundVbo = vbo;
    }

    @Override
    public void deleteVbo(int vbo) {
        deletedVbos.add(vbo);
        vboCapacityBytes.remove(vbo);
    }

    @Override
    public void uploadVboData(float[] data) {
        vboCapacityBytes.put(boundVbo, data.length * Float.BYTES);
    }

    @Override
    public void uploadVboSubData(int offsetBytes, ByteBuffer data) {
        int bytes = data.remaining();
        // 真 GL 上，越界要么报 GL_INVALID_OPERATION（而本仓库没有任何地方读错误码——
        // glGetError 全库只在 PipelineVerifier 的一处诊断打印里出现过），要么静默损坏
        // 别的数据。假实现把它变成显式失败，与本文件 uploadR8SubImage 的做法一致。
        //
        // 这一条不是吹毛求疵：Task 8 的整个测试策略就是靠这个假实现验证 SeriesBuffer，
        // 而 SeriesBuffer 是"GPU 常驻 + 增量上传"这条主张的承载者。不校验前置条件的话，
        // 它上面的断言会在"参数其实非法"时通过——本项目最警惕的形状。
        Integer capacity = vboCapacityBytes.get(boundVbo);
        if (capacity == null) {
            throw new AssertionError(
                    "向从未定容的 VBO " + boundVbo + " 子上传：真 GL 上这是 GL_INVALID_OPERATION");
        }
        if (offsetBytes < 0 || (long) offsetBytes + bytes > capacity) {
            throw new AssertionError(
                    "子上传越界：offset=" + offsetBytes + " bytes=" + bytes
                            + " 容量=" + capacity + "（真 GL 上是静默损坏）");
        }
        vboSubDataCalls.add(boundVbo + "@" + offsetBytes + ":" + bytes);
    }
```

> **并且新增 `src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstractionGuardTest.java`**，
> 把上面那三类清单钉成断言——**这样"护栏被卸掉"会变成一次可见的代码改动，而不是一次静默的通过**：
>
> ```java
> package com.bingbaihanji.jfgl.gl;
>
> import org.junit.jupiter.api.Test;
>
> import java.lang.reflect.Method;
> import java.util.Arrays;
> import java.util.Set;
> import java.util.TreeSet;
> import java.util.stream.Collectors;
>
> import static org.junit.jupiter.api.Assertions.*;
>
> /**
>  * 钉住假实现的<strong>护栏面</strong>。
>  *
>  * <p>{@code FakeGLAbstraction} 的价值全在"测试里真调到了不该调的东西就炸"。
>  * 而<strong>卸掉护栏没有任何症状</strong>：把某个方法的 {@code throw} 换成空实现，
>  * 测试照样全绿，直到某天一条走偏的代码路径静默通过。
>  *
>  * <p>所以这里把"哪些抛、哪些实现"写成两向断言。将来某个任务想放开一个方法，
>  * 必须同时改这张清单——于是那次放开是一次<strong>可见的</strong>代码改动，
>  * 而且会在计划里留下理由。这是本项目"护栏要能被观测"这条纪律的机器化。
>  */
> class FakeGLAbstractionGuardTest {
>
>     /** 必须抛 {@link UnsupportedOperationException} 的方法签名。 */
>     private static final Set<String> MUST_THROW = Set.of(
>             "initialize()", "clear(Color)", "setViewport(int,int,int,int)",
>             "createVao()", "bindVao(int)", "deleteVao(int)",
>             "uploadVboData(int[])",          // 注意：float[] 重载是"记录"，不是抛
>             "uploadVboBytes(ByteBuffer)",
>             "drawArrays(int,int,int)", "drawElements(int,int)",
>             "enableBlend()", "disableBlend()", "setBlendFunc(int,int)",
>             "createShader(String,String)", "createTexture(int,int,int[])",
>             "setVertexAttribDivisor(int,int)", "drawArraysInstancedBaseInstance(int,int,int,int,int)");
>
>     /** 真正实现或记录的方法签名（**不**抛异常的那些）。 */
>     private static final Set<String> IMPLEMENTED = Set.of(
>             // 拾取路径
>             "createFramebuffer()", "bindFramebuffer(int)", "deleteFramebuffer(int)",
>             "currentFramebufferBinding()", "createIntegerTexture(int,int)",
>             "deleteTexture(int)", "createR8Texture(int,int)",
>             "uploadR8SubImage(int,int,int,int,int,byte[])",
>             "attachTextureToColor0(int)", "framebufferStatus()", "clearIntegerColor(int)",
>             "isScissorEnabled()", "setScissorEnabled(boolean)",
>             "readUnsignedIntPixel(int,int)", "readUnsignedIntPixels(int,int,int,int,int[])",
>             "dispose()",
>             // 顶点缓冲路径（图表后端）
>             "createVbo()", "bindVbo(int)", "deleteVbo(int)",
>             "uploadVboData(float[])", "uploadVboSubData(int,ByteBuffer)");
>
>     private static String signature(Method m) {
>         return m.getName() + Arrays.stream(m.getParameterTypes())
>                 .map(Class::getSimpleName)
>                 .collect(Collectors.joining(",", "(", ")"));
>     }
>
>     @Test
>     void 假实现的方法分类不得静默变化() {
>         Set<String> actual = Arrays.stream(FakeGLAbstraction.class.getDeclaredMethods())
>                 .filter(m -> !m.isSynthetic())
>                 .map(FakeGLAbstractionGuardTest::signature)
>                 .collect(Collectors.toCollection(TreeSet::new));
>
>         Set<String> missing = new TreeSet<>(MUST_THROW);
>         missing.removeAll(actual);
>         assertTrue(missing.isEmpty(), "清单里的这些方法在假实现里不存在（改名了？）：" + missing);
>
>         Set<String> undocumented = new TreeSet<>(actual);
>         undocumented.removeAll(MUST_THROW);
>         undocumented.removeAll(IMPLEMENTED);
>         assertTrue(undocumented.isEmpty(),
>                 "假实现里有方法既不在 MUST_THROW 也不在 IMPLEMENTED 里：" + undocumented
>                         + "。新加一个不抛异常的方法意味着卸掉了一处护栏——"
>                         + "若是有意的，把它加进 IMPLEMENTED 并说明理由。");
>
>         Set<String> overImplemented = new TreeSet<>(IMPLEMENTED);
>         overImplemented.removeAll(actual);
>         assertTrue(overImplemented.isEmpty(),
>                 "IMPLEMENTED 里列了假实现其实没有的方法：" + overImplemented);
>     }
> }
> ```
>
> **注意**：`MUST_THROW` 与 `IMPLEMENTED` 的并集必须**恰好覆盖**假实现声明的所有方法。
> 这条断言的强度全在"两向"上——只查"清单里的都还在"是单向的，
> 新加一个不抛异常的方法照样溜过去。

> **实施期修正两处（执行 Task 1 时实测发现，都已落进代码）：**
>
> **一、这个测试必须有两面，只有分类那一面是不够的。**
> 上面只断言签名归属，而**反射看不到方法体**——把某个方法的 `throw` 换成空实现，
> `getDeclaredMethods()` 一条都不会少，分类断言**必然照样通过**。
> 也就是说分类断言保护的是"签名还在"，**不是**"护栏还在"，而"被掏空"恰恰是最像
> "静默卸护栏"的那一种。
>
> 所以还需要第二个测试，**按 `MUST_THROW` 逐个反射调用并断言抛
> `UnsupportedOperationException`**（参数给默认值即可，这些方法体就是一句 throw）。
> 实测：把所有方法改成空实现，全量 263 条里**只有这一条失败**——其余 262 条全绿，
> 这就是"卸掉护栏是零症状的"的实测证据。
>
> **二、分类的取法要过滤成"只取实现了 `GLAbstraction` 接口的那些方法"。**
> `FakeGLAbstraction` 另有 `setUserPixel` / `setScreenBottomUp` / `maybeThrow`
> 三个**测试便捷入口**，它们不在两张清单里，"并集必须恰好覆盖所有声明方法"会误报它们；
> 而把它们塞进 `IMPLEMENTED` 会让清单语义变浑。
>
> 这个过滤器**不削弱守卫**：类必须实现全部接口方法（否则编译不过），所以任何新增的
> 接口方法**必然出现在**筛选结果里、必然要求被归类。

> **`setVertexAttribDivisor` 与 `drawArraysInstancedBaseInstance` 继续抛异常。**
> 质量复核指出这不是随意的：`divisorCalls` / `instanceDrawCalls` 在**整个计划里没有任何
> 消费者**（计划自己的文件结构表把 `LineSeriesRenderer` / `ChartRenderer` 标为"能单测吗：**否**"），
> 那两个字段会成为测试源码里的死代码。既然没有消费者，就不该放开。
> **Task 10 / 14 若真的需要单测渲染器的装配逻辑，那时再放开那两个（并记在这里）。**
>
> 附带好处：`setVertexAttribDivisor` 的真正可测接缝不在假实现里，而在
> `RenderBatch` **绕过 `GLAbstraction`** 直接调 `glVertexAttribPointer` 这件事上
> （见 `RenderBatch.java:31-33`）。**Task 8/10 要注意：§5.1 那套"同一个 VBO 绑两次、
> 只差 4 字节偏移、divisor=1"的机制，目前没有任何可测的接缝。**
>
> **它们仍需要在 `FakeGLAbstraction` 里写出实现体**——接口加了方法，实现类必须补齐，
> 否则编译不过。写进文件末尾那段"以下与拾取路径无关，真调到了说明走偏了"的抛异常区即可：
>
> ```java
>     @Override public void setVertexAttribDivisor(int index, int divisor) { throw new UnsupportedOperationException(); }
>     @Override public void drawArraysInstancedBaseInstance(int mode, int first, int count,
>                                                           int instanceCount, int baseInstance) { throw new UnsupportedOperationException(); }
> ```
>
> **并且把类 Javadoc 改成显式两类清单**（质量复核的 Finding 1）——原文那句
> "只实现拾取路径真正用到的那几个方法，其余一律抛 `UnsupportedOperationException`"
> 现在**是一句为假的安全声明**，而类级 Javadoc 是这个代码库被反复接手时读到的第一手信息：
>
> ```java
> /**
>  * 测试用的 {@link GLAbstraction} 假实现。
>  *
>  * <p>方法分成三类：
>  * <ul>
>  *   <li><strong>拾取路径</strong>（FBO / 整数纹理 / 读回 / 裁剪）：真正实现，
>  *       供 {@code PickBuffer}、{@code Framebuffer}、{@code GlyphAtlas} 单测。</li>
>  *   <li><strong>顶点缓冲路径</strong>（图表后端用）：只<em>记录</em>调用，
>  *       并对子上传做越界检查。这一组<strong>不抛异常</strong>——拾取路径若误调它们
>  *       不会报错，只能靠记录列表发现。</li>
>  *   <li><strong>其余</strong>：一律抛 {@link UnsupportedOperationException}
>  *       （见文件末尾的清单）。</li>
>  * </ul>
>  */
> ```

- [ ] **Step 4: 编译**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`。**这一步本身就是一条真检查**：Java 要求每个实现类补齐接口方法，编译通过即证明 `LwjglGLAbstraction` 与 `FakeGLAbstraction` 都跟上了。

- [ ] **Step 5: 跑全量测试确认没碰坏别的**

Run: `mvn -o test`
Expected: `Tests run: 263, Failures: 0, Errors: 0, Skipped: 2`
（261 + 新增的 `FakeGLAbstractionGuardTest` 两条）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java \
        src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java \
        src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstraction.java \
        src/test/java/com/bingbaihanji/jfgl/gl/FakeGLAbstractionGuardTest.java
git commit -F - <<'EOF'
feat(gl): 补三个图表后端要用的 GL 入口

- uploadVboSubData：增量上传，不扩容
- setVertexAttribDivisor：实例除数
- drawArraysInstancedBaseInstance：带 baseInstance 的实例化绘制

第三个不能省。实例属性按 gl_InstanceID 取，而它每次都从 0 开始，
环形缓冲里"环绕点之后那一小段"的物理槽位不从 0 开始——普通版本会
取到错误的实例数据，不报错，只画出一条乱线。

FakeGLAbstraction 的 VBO 方法从"抛异常"改成"记录"：图表后端确实
要建自己的 VBO。代价是拾取路径误调它们不再报错，但记录列表让误调
仍然可见。

这三个是薄转发，没有 GL 上下文无法单测——真正的验证在 ChartVerifier。
EOF
```

---

## Task 2: `ShaderProgram` 的 vec4 uniform 与位置缓存

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/gl/ShaderProgram.java`

- [ ] **Step 1: 加 location 缓存**

把现有的 `getUniformLocation` 整个替换为：

```java
    /** uniform 名字 → 位置。程序链接后位置就固定了，不必每次现查。 */
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    /**
     * 获取此程序中 uniform 变量的位置，带缓存。
     *
     * <p><strong>为什么要缓存</strong>：{@code glGetUniformLocation} 是按名字做字符串查找。
     * 图表每个系列每帧要设约 8 个 uniform，多个系列叠加时它会变成热路径上的可见开销。
     * 程序链接之后位置就固定了，因此可以安全缓存。
     *
     * <p>找不到的 uniform 会返回 -1 并<strong>把这个 -1 也缓存下来</strong>——
     * 免得每帧都去查一个永远不存在的名字。注意 -1 传给 {@code glUniform*} 是**静默无操作**，
     * 所以 uniform 名字写错不会有任何报错，只会"设了但没生效"。
     *
     * @param name uniform 变量的名称
     * @return uniform 位置，未找到时为 -1
     */
    public int getUniformLocation(String name) {
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached;
        }
        int location = glGetUniformLocation(programId, name);
        uniformLocations.put(name, location);
        return location;
    }
```

并在 import 区加上：

```java
import java.util.HashMap;
import java.util.Map;
```

- [ ] **Step 2: 加 vec4 的 setUniform**

在 `setUniform(String name, float x, float y)` 之后插入：

```java
    /**
     * 设置四分量浮点 uniform。
     *
     * <p>图表用它一次传绘图区矩形（x, y, 宽, 高）。
     *
     * <p><strong>不要拿 {@link #setUniform(String, float[])} 代替</strong>——
     * 那个是 mat3（{@code glUniformMatrix3fv}），名字像但语义完全不同，
     * 而且传错了不会有任何报错。
     *
     * @param name uniform 名称
     * @param x    第一个分量
     * @param y    第二个分量
     * @param z    第三个分量
     * @param w    第四个分量
     */
    public void setUniform(String name, float x, float y, float z, float w) {
        glUniform4f(getUniformLocation(name), x, y, z, w);
    }
```

- [ ] **Step 3: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: `BUILD SUCCESS` / `Tests run: 261, Failures: 0, Errors: 0, Skipped: 2`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/gl/ShaderProgram.java
git commit -F - <<'EOF'
perf(gl): ShaderProgram 的 uniform 位置加缓存，并补 vec4

图表每个系列每帧要设约 8 个 uniform，而 glGetUniformLocation 是按
名字做字符串查找——多个系列叠加时会变成热路径上的可见开销。程序链接
之后位置就固定了，可以安全缓存。

缓存也存 -1（找不到的名字），免得每帧去查一个永远不存在的 uniform。

本类无法脱离 GL 上下文单测（构造器要 glCreateProgram），所以这两处
的正确性由 ChartVerifier 端到端覆盖：缓存错了的表现是 uniform 设不上，
画面上的映射会明显不对，Task 12 的映射断言会抓到。
EOF
```

---

## Task 3: `Gc.flush()` —— 公开的帧内提交点

**Files:**
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt`
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineVerifier.kt`

- [ ] **Step 1: 在 `Gc` 里加 `flush()`**

紧跟在 `endFrame()` 之后插入（`endFrame` 在 `Gc.kt:162` 附近）：

```kotlin
    /**
     * 帧内提交点：把到目前为止积累的顶点立刻提交掉。
     *
     * <p><strong>它是为 z 序存在的。</strong>本类的图元默认攒到 [endFrame] 才一次性提交，
     * 而图表的数据系列是<strong>当场就画</strong>的。没有这个方法，数据系列就只能整个
     * 画在 `Gc` 内容之上或之下，"网格 → 数据 → 标注"这种夹心顺序做不到。
     *
     * <pre>
     * gc.beginFrame(w, h)
     *   画网格
     * gc.flush()                      // 网格落定
     * charts.draw(chart, plotRect)    // 数据系列画在网格之上
     *   画刻度文字                     // 标注画在数据之上
     * gc.endFrame()
     * </pre>
     *
     * <p><strong>调用方不需要在之后重新设状态。</strong>[VertexWriter.reset] 会把写入器
     * 带回"尚未设置状态"，而下一个图元经由 [syncState] 重新 `setState`——
     * 这条路径与缓冲区写满时的自动提交（[flushIfNeeded]）完全一致，已经跑了很多年。
     *
     * <p><strong>它会让 ID pass 多跑几趟。</strong>[RenderBatch.submit] 在本批有可拾取顶点时
     * 每次都跑一趟 ID pass，分批提交就意味着分趟拾取。这是设计使然，不是缺陷：
     * 拾取结果与画面一致（被后画的挡住的部分，拾取到的是后画的那个），
     * 与不做 flush 时的"只返回最上层"语义相同。
     *
     * @throws IllegalStateException 未经 [beginFrame] 就调用
     */
    fun flush() {
        check(frameActive) { "flush 在 beginFrame 之前调用：beginFrame 与 endFrame 必须配对" }
        batch.submit(writer)
        writer.reset()
    }
```

> **⚠️ 实施期更正：原稿这里还有一道 `save/restore` 平衡检查，已删除。**
>
> 原稿写的是 `check(!state.hasUnbalancedSave()) { "…这会让后续图元的变换与裁剪状态错乱" }`，
> 两处都错：
>
> 1. **那个方法名不存在**（占位名，本任务本来就要求实现者去读 `endFrame` 照抄；`endFrame`
>    用的是 `state.clearStack()`，而它是**破坏性**的，帧中途不能用）。
> 2. **更重要的是那条检查本身是错的。** 顶点在发射时就把变换**烘焙**进顶点，裁剪在每次
>    `syncState` **重新读**——所以 `gc.save(); gc.clipRect(plot); …; gc.flush(); …; gc.restore()`
>    是**完全合法**的写法，而那道检查会硬抛异常，消息还宣称"会让状态错乱"，
>    **把合法模式判成状态损坏**，后来的实施者会去追一个不存在的 bug。
>    真正有害的条件是"有 save 永远不会 restore"，而**那个条件在 flush 时刻不可知**；
>    `stackDepth > 0` 只是它的**超集**，属于会误报的守卫。
>
> **帧内平衡本来就由 `endFrame()` 在正确的位置守着**，flush 不需要再管一次。
> 用户的自然写法（clip 住绘图区 → 画网格 → flush → 画数据系列）必须能跑。

> ~~**注意上面用到的 `state.hasUnbalancedSave()`**：`endFrame()` 里已经有一段等价的检查（`Gc.kt:162-166`），**照抄它的写法**。~~
>
> **这段已作废**——上面那段 ⚠️ 更正已经说明那道检查被整体删除，`hasUnbalancedSave()` 这个方法名从来不存在。
> （保留作废的原文是为了让读者看到原稿错在哪；`endFrame` 实际用的是 `state.clearStack()`，
> 而它是**破坏性**的，帧中途不能调用。）

- [ ] **Step 2: 编译**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 在 `PipelineVerifier` 里加 z 序断言**

在 `PipelineVerifier.kt` 里加一个场景：**同一个矩形位置先画红、`flush()`、再画绿**，断言最终像素是**绿**；再加一个**先画红、`flush()` 之前画的绿**……不，这样写会把自己绕进去。正确的对照是：

```kotlin
    /**
     * flush() 的 z 序：后画的必须盖住先画的，<b>无论中间有没有 flush</b>。
     *
     * <p>这条断言的对照价值在于它<b>同时</b>跑两种情况：
     * 不做 flush 时靠同批内的命令顺序，做 flush 时靠批次顺序。
     * 只测其中一种的话，"flush 之后顺序反了"这个缺陷不会被发现。
     */
    private fun assertFlushKeepsZOrder(gc: Gc) {
        val box = Rect(40f, 40f, 120f, 120f)

        // 情况一：不 flush，红先绿后 —— 期望绿
        gc.fill = 0xFFFF0000.toInt()
        gc.fillRect(box.x, box.y, box.width, box.height)
        gc.fill = 0xFF00FF00.toInt()
        gc.fillRect(box.x, box.y, box.width, box.height)

        // 情况二：中间 flush，红先绿后 —— 期望绿
        gc.flush()
        gc.fill = 0xFFFF0000.toInt()
        gc.fillRect(box.x + 200f, box.y, box.width, box.height)
        gc.flush()
        gc.fill = 0xFF00FF00.toInt()
        gc.fillRect(box.x + 200f, box.y, box.width, box.height)
    }
```

然后在读回像素处断言：`box` 中心与 `box` 右移 200 处**都是绿**。

> **别只断言"有东西画出来了"**——那对顺序颠倒同样成立，是典型的橡皮图章。断言的量必须是**颜色**。

- [ ] **Step 4: 跑 `PipelineVerifier`**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
```
Expected: 全部断言通过，退出码 0。

- [ ] **Step 5: 变异验证 —— 证明这条断言是活的，而且要做两组**

**第一组（函数级）**：把 `flush()` 变成**空方法**（方法体什么都不做），重跑 `PipelineVerifier`。
Expected: **只有"即时绘制"那一组断言失败**。

**第二组（条件级 / 部分失效）**：把 `batch.submit(writer)` 与 `writer.reset()` **对调顺序**，重跑。
Expected: **只有纯 `Gc` 那两组断言失败**。

**如果任何一组变异跑完仍然全绿，停下来如实报告**——那说明对应断言看的是别的量，**不要继续**。

两组做完后改回来，确认 `PipelineVerifier` 重新全绿。

> ### ⚠️ 实施期更正：原稿的预期被证伪，而且原稿的断言本身没有判别力
>
> 原稿写：「变异 → **情况二的断言必须失败**」。**实测：情况一与情况二照样 PASS。**
>
> 原因可证：**两种情况都是纯 `Gc` 内容**，它们记在**同一个顶点写入器**里，
> `RenderBatch.submit` 无论如何都按顶点顺序画进同一个帧缓冲。
> 顶点在发射时就把变换烘焙好了、探针色又是不透明的（预乘 alpha=1 ⇒ `dst = src`），
> 所以**分批与不分批的最终像素逐像素相同**。
> **空实现的 `flush()` 对纯 `Gc` 场景在像素上完全不可观测。**
>
> **也就是说原稿那条断言是橡皮图章**——它看起来不像：它断言的是**颜色**、还带了对照组。
> 但它观测不到那个量，**因为场景里只有一种绘制路径**。
>
> > **一条通用规则：要测一个"顺序/交错"性质，场景里必须同时存在被排序的那两个东西。**
> > 对照组若和被测对象走同一条路径，它对照不出任何东西。
>
> **正确的形态（实施期已补上，务必保留）**：加第三种情况——
> `flush()` 之后，上面那层**不走 `Gc`**，而用**即时 GL 绘制**（`glClear` + `glScissor`）
> 当场发一条命令再画一层。这正是图表后端数据系列的处境，也正是 `flush()` 存在的理由。
> 探针画在主场景**未触及**的区域，用**专用色**（不要复用主场景的红/绿，
> 否则既有的 `counts[red]` 这类断言会因重叠而失真——这不是偏好，是必要条件）。
>
> **并且：两组变异抓到的是不相交的断言集，砍掉任何一组都会漏掉一种退化。**
> 实测——掏空 `flush()` 只让"即时绘制"那组失败；对调 `submit`/`reset` 只让纯 `Gc` 那两组失败。
> 它们**互补，不是包含关系**。
> （原稿此前只说到"函数级证明有东西在守、条件级才证明每一条都在守"，那只对了一半。）

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt \
        src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineVerifier.kt
git commit -F - <<'EOF'
feat(gc): 公开帧内提交点 flush()，让 z 序可控

图表的数据系列是当场就画的，而 Gc 的图元攒到 endFrame 才一次性提交。
没有 flush 的话，数据系列只能整个画在 Gc 内容之上或之下，
"网格 -> 数据 -> 标注"这种夹心顺序做不到。

复用的是 flushIfNeeded 那条路径（缓冲区写满时自动提交走的就是它）。
PipelineVerifier 加了一条同时跑"不 flush"与"中间 flush"两种情况的
z 序断言，并做了变异验证：让 flush 变成空方法，那条断言必须失败。
EOF
```

---

## Task 4: `RenderBatch.withPickPass` —— 把 ID pass 的脚手架开放出来

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java`

- [ ] **Step 1: 加公开方法**

在 `drawPickPass` 之前插入：

```java
    /**
     * 在拾取缓冲上执行一段绘制。
     *
     * <p>本方法负责：本帧首次调用时清空拾取缓冲、绑定拾取 FBO、结束后恢复原 FBO
     * 并把拾取缓冲标记为有效。调用方负责：绑定自己的 VAO/VBO、配置属性指针、
     * 设置 scissor、发出 draw call。
     *
     * <p><strong>为什么是回调而不是 {@code begin()}/{@code end()} 成对</strong>：
     * 成对的 API 一定有人忘了调 {@code end()}，而忘掉的表现是"下一帧画进了拾取缓冲"——
     * 画面完全正常，只是拾取全错。回调式让编译器替他记住。
     *
     * <p><strong>调用方不得改动 {@link #pickPassCount()}</strong>。它跨帧累计、从不复位，
     * 是 {@code PickVerifier} 用来断言"无拾取对象时整趟跳过"的计数器。往它里面加计数会
     * 污染已有的断言，而症状是"拾取校验器突然失败"，排查方向会指向 ID pass 本身。
     *
     * <p><strong>调用方必须自己设置 scissor</strong>：被裁掉的部分不可拾取，与画面一致。
     * 本方法不做这件事，因为裁剪矩形取决于调用方的几何。
     *
     * @param body 要执行的绘制，不得为 null
     */
    public void withPickPass(Runnable body) {
        Objects.requireNonNull(body, "body");
        if (!pickBufferCleared) {
            pickBuffer.clear();
            pickBufferCleared = true;
        }

        int previousFramebuffer = gl.currentFramebufferBinding();
        gl.bindFramebuffer(pickBufferId());

        // 整数附件不能开混合；ID 被插值成「零点几个对象」也没有意义。
        gl.disableBlend();
        try {
            body.run();
        } finally {
            // 必须恢复：openglfx 渲染到它自己的 FBO，不恢复的话下一帧会画进拾取缓冲。
            gl.bindFramebuffer(previousFramebuffer);
        }

        // 标记缓冲有效：不置的话上层拾取查询会诚实地返回「没命中」，
        // 于是图表永远点不中，而画面完全正常——那种「看起来像没实现」的静默错误。
        pickBufferValid = true;
    }
```

并在 import 区加上：

```java
import java.util.Objects;
```

- [ ] **Step 2: 把 `drawPickPass` 改成用新方法（纯重构）**

把现有的 `drawPickPass` body 替换为：

```java
    private void drawPickPass(List<DrawCommand> commands) {
        pickShader.use();
        try {
            withPickPass(() -> {
                for (DrawCommand command : commands) {
                    if (command.vertexCount() == 0) {
                        continue;
                    }
                    applyScissor(command);
                    glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
                }
            });
        } finally {
            pickShader.unuse();
        }
        pickPassCount++;
    }
```

> **`pickPassCount++` 留在 `drawPickPass` 里，不放 `withPickPass`**——前者是 `RenderBatch` 自己那趟的计数，后者是通用入口，图表路径走它时不该计数（见 Step 1 的说明）。

- [ ] **Step 3: 跑 `PickVerifier` —— 这是纯重构，必须一模一样地绿**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```
Expected: 退出码 0，且 **`pickPassCount` 相关的断言仍然通过**（那几条断言专门钉"无拾取对象时整趟跳过"）。

- [ ] **Step 4: 跑另外两个校验器确认没连带影响**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.TextVerifierKt"
```
Expected: 两个都退出码 0。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java
git commit -F - <<'EOF'
refactor(renderer): 把 ID pass 的脚手架开放成 withPickPass

drawPickPass 本来就是"用当前绑定的 VAO/VBO 画"，不关心 VAO 是谁的，
所以图表走同一趟 ID pass 只需要换绑定。

回调式而不是 begin()/end() 成对：成对 API 一定有人忘了 end()，而忘掉
的表现是"下一帧画进了拾取缓冲"——画面正常，拾取全错。

pickPassCount 的 ++ 留在 drawPickPass 里，不放进通用入口：
它跨帧累计、从不复位，是 PickVerifier 用来断言"整趟跳过"的计数器，
图表路径往里加计数会污染已有的断言。

纯重构，PickVerifier/PipelineVerifier/TextVerifier 三个都必须原样绿。
EOF
```

---

## Task 5: `WindowRange` —— 可见窗口 → 实例区间（纯算术，TDD）

这是 ② 里第一块**能脱离 GL 上下文单测**的代码。它决定"这一帧要画哪几个实例"，算错了的表现是**画面缺一段或画出一段乱线**，都不报错。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/WindowRange.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chartrender/WindowRangeTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chartrender/WindowRangeTest.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WindowRange} 的单元测试。
 *
 * <p>这些断言全部落在<b>具体区间</b>上，不是"不为空"这种覆盖性检查——
 * 覆盖性检查对"少画了一段"和"多画了一段"同样成立，是橡皮图章。
 */
class WindowRangeTest {

    /** 环容量 8，已经写满：有效数据下标 [0, 8)，可画的线段是 [0, 6]。 */
    private static final int CAP = 8;

    @Test
    void 环绕未发生时只有一段() {
        // 窗口 [1, 4) -> 线段 1,2,3，物理槽位 1..3
        List<WindowRange.Segment> segs = WindowRange.compute(1.0, 4.0, 8L, CAP);
        assertEquals(1, segs.size());
        assertEquals(new WindowRange.Segment(1, 1L, 3), segs.get(0));
    }

    @Test
    void 环绕发生时切成两段() {
        // 窗口 [6, 10) 但环里只有 [0, 8)，可画线段 [6, 7) -> 只有线段 6，槽位 6
        // 窗口 [5, 9) -> 可画线段 [5, 7) -> 线段 5（槽位 5）与 6（槽位 6）
        // 要触发环绕，得让 firstSlot + count > 8
        List<WindowRange.Segment> segs = WindowRange.compute(6.0, 12.0, 12L, CAP);
        // 环里有效 [4, 12)，可画线段 [4, 11)，窗口 [6, 12) 取交 -> [6, 11)，共 5 个
        // firstSlot = 6 & 7 = 6，6 + 5 = 11 > 8 -> 环绕
        assertEquals(2, segs.size());
        assertEquals(new WindowRange.Segment(6, 6L, 2), segs.get(0));   // 槽位 6,7
        assertEquals(new WindowRange.Segment(0, 8L, 3), segs.get(1));   // 槽位 0,1,2
    }

    @Test
    void 恰好填满时不切段() {
        // 边界：firstSlot + count == capacity 必须走单段分支
        // 环容量 8，写满到 8：可画线段 [0, 6]。窗口 [6, 8) -> 线段 6，槽位 6 -> 单段
        // 构造 firstSlot=6,count=2：窗口 [6, 9)，可画 [6, 7) 只有 1 个…
        // 换 writeIndex=9：有效 [1, 9)，可画线段 [1, 8)。窗口 [6, 9) -> [6, 8)，2 个，槽位 6,7 -> 单段
        List<WindowRange.Segment> segs = WindowRange.compute(6.0, 9.0, 9L, CAP);
        assertEquals(1, segs.size(), "firstSlot + count == capacity 时必须只有一段");
        assertEquals(new WindowRange.Segment(6, 6L, 2), segs.get(0));
    }

    @Test
    void 窗口在有效数据之前时为空() {
        // writeIndex=100，环容量 8 -> 有效 [92, 100)
        List<WindowRange.Segment> segs = WindowRange.compute(10.0, 20.0, 100L, CAP);
        assertTrue(segs.isEmpty(), "窗口完全落在被覆盖的旧数据上，什么都不该画");
    }

    @Test
    void 窗口在已写数据之后时为空() {
        // 还没写到的位置没有线段
        List<WindowRange.Segment> segs = WindowRange.compute(50.0, 60.0, 20L, CAP);
        assertTrue(segs.isEmpty(), "窗口落在还没采到的未来，什么都不该画");
    }

    @Test
    void 窗口大于环容量时被裁到有效范围() {
        // 窗口开得极大，应该被裁到 [4, 11)（writeIndex=12, cap=8 -> 有效[4,12)，可画[4,11)）
        List<WindowRange.Segment> segs = WindowRange.compute(-1000.0, 1000.0, 12L, CAP);
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(7, total, "有效线段是 [4, 11)，共 7 个");
        assertEquals(4L, segs.get(0).firstDataIndex(), "第一段的起始数据下标必须是 4");
    }

    @Test
    void 最后一个线段不可画因为右端还没采到() {
        // writeIndex=5：样本 [0,5) 已写，可画线段 [0, 4) —— 线段 4 需要样本 5，还没写
        List<WindowRange.Segment> segs = WindowRange.compute(0.0, 100.0, 5L, CAP);
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(4, total, "线段数必须是 writeIndex - 1，不是 writeIndex");
    }

    @Test
    void 空窗口为空() {
        assertTrue(WindowRange.compute(5.0, 5.0, 100L, CAP).isEmpty());
        assertTrue(WindowRange.compute(5.0, 4.0, 100L, CAP).isEmpty());
    }

    @Test
    void 小数窗口边界向上取整() {
        // 窗口左边界 1.5 -> 第一个可画的线段是 2（线段 1 从 1 开始，左端落在窗口外）
        List<WindowRange.Segment> segs = WindowRange.compute(1.5, 4.0, 8L, CAP);
        assertEquals(1, segs.size());
        assertEquals(2L, segs.get(0).firstDataIndex());
        assertEquals(2, segs.get(0).instanceCount());   // 线段 2、3
    }

    @Test
    void 容量非2的幂时抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> WindowRange.compute(0.0, 10.0, 100L, 12));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=WindowRangeTest`
Expected: 编译失败（`WindowRange` 不存在）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chartrender/WindowRange.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import java.util.List;

/**
 * 可见窗口 → 要绘制的实例区间。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>为什么单独成类</h2>
 * <p>这一段算术决定了"这一帧从哪个物理槽位开始画、画几个、要不要切成两段"。
 * 算错的表现是<b>画面缺一段、或者多出一条横贯屏幕的乱线</b>——两者都不报错。
 * 抽成纯函数之后它可以被穷举测试，而不必靠肉眼看窗口。
 *
 * <h2>三个概念必须分清</h2>
 * <ul>
 *   <li><b>数据下标</b>：从采集开始累加的绝对序号，用 {@code long}。
 *       每秒几百万点，{@code int} 几十分钟就溢出——而溢出后静默回绕，全盘错乱。</li>
 *   <li><b>物理槽位</b>：{@code 数据下标 & (容量 - 1)}，即它在环形缓冲里的位置。</li>
 *   <li><b>实例</b>：一个实例就是一个线段，连接数据下标 {@code i} 与 {@code i + 1}。
 *       因此实例 {@code i} 的物理槽位也是 {@code i & (容量 - 1)}——
 *       顶点着色器靠"同一个缓冲、偏移差 4 字节"的两个属性拿到 {@code (y[i], y[i+1])}。</li>
 * </ul>
 *
 * <h2>为什么要返回多段</h2>
 * <p>可见窗口跨过环的环绕点时，物理槽位不是连续的，必须切成两段分别绘制。
 * 每段带自己的 {@code firstInstance}（物理槽位）与 {@code firstDataIndex}（绝对下标），
 * 后者供顶点着色器算 x 坐标。
 */
public final class WindowRange {

    /**
     * 一段连续的实例。
     *
     * @param firstInstance  起始物理槽位（供 {@code glDrawArraysInstancedBaseInstance}
     *                       的 {@code baseInstance} 用）
     * @param firstDataIndex 起始数据下标（绝对序号，供顶点着色器算 x）
     * @param instanceCount  实例数
     */
    public record Segment(int firstInstance, long firstDataIndex, int instanceCount) {
    }

    private WindowRange() {
    }

    /**
     * 计算这一帧要绘制哪些实例。
     *
     * @param windowStart 可见窗口左边缘的数据下标，可以是小数（亚像素滚动）
     * @param windowEnd   可见窗口右边缘的数据下标（半开）
     * @param writeIndex  采集线程已写入的样本总数，即有效数据是 {@code [?, writeIndex)}
     * @param capacity    环形缓冲容量，<strong>必须是 2 的幂</strong>
     * @return 0、1 或 2 段；窗口与有效数据没有交集时为空列表
     * @throws IllegalArgumentException 容量不是 2 的幂
     */
    public static List<Segment> compute(double windowStart, double windowEnd,
                                        long writeIndex, int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + capacity);
        }
        if (!(windowEnd > windowStart)) {
            return List.of();
        }

        // 环里最老的那个还没被覆盖的样本。写者绕过读者时，比它更老的已经丢了。
        long validStart = Math.max(0L, writeIndex - capacity);
        // 一个线段要两端都有，所以最后一个可画的线段下标是 writeIndex - 2。
        long lastSegment = writeIndex - 2;

        long lo = Math.max((long) Math.ceil(windowStart), validStart);
        long hi = Math.min((long) Math.ceil(windowEnd), lastSegment + 1);
        if (hi <= lo) {
            return List.of();
        }

        int count = (int) (hi - lo);
        int firstSlot = (int) (lo & (capacity - 1));
        if (firstSlot + count <= capacity) {
            return List.of(new Segment(firstSlot, lo, count));
        }

        int headCount = capacity - firstSlot;
        return List.of(
                new Segment(firstSlot, lo, headCount),
                new Segment(0, lo + headCount, count - headCount));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=WindowRangeTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异验证**

逐个做，每次只改一处，跑 `mvn -o test -Dtest=WindowRangeTest`，记录**哪条断言抓到**：

| 变异 | 期望 |
|---|---|
| `lastSegment = writeIndex - 1` | `最后一个线段不可画因为右端还没采到` 失败 |
| 去掉 `firstSlot + count <= capacity` 的判断，永远切两段 | `环绕未发生时只有一段`、`恰好填满时不切段` 失败 |
| `Math.ceil` 换成 `Math.floor` | `小数窗口边界向上取整` 失败 |
| `validStart` 用 `writeIndex - capacity + 1` | `窗口在有效数据之前时为空` 或 `窗口大于环容量时被裁到有效范围` 失败 |

**如果有任何一个变异存活，停下来报告**——那说明对应的断言看的是别的量。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/WindowRange.java \
        src/test/java/com/bingbaihanji/jfgl/chartrender/WindowRangeTest.java
git commit -F - <<'EOF'
feat(chartrender): 可见窗口 -> 实例区间（纯算术）

算错的表现是画面缺一段、或多出一条横贯屏幕的乱线，两者都不报错。
抽成纯函数之后可以穷举测试，不必靠肉眼看窗口。

三个概念在类文档里分清了：数据下标（long，绝对）、物理槽位（& cap-1）、
实例（一个线段，槽位等于它的数据下标）。跨环绕点时切成两段，
每段带自己的 baseInstance 与 firstDataIndex。

变异验证：四个变异逐个注入，确认各自被哪条断言抓到。
EOF
```

---

> **实施期记录：一处刻意的、但尚未被测试钉住的行为。**
>
> 守卫写成 `!(windowEnd > windowStart)` 而**不是** `windowEnd <= windowStart`，
> 于是 **NaN 窗口会返回空列表**（任何与 NaN 的比较都是 false）。返回空是**对的**——
> 窗口无意义时什么都不画，好过画出一屏垃圾。
>
> **风险**：有人若把它"改规范"成 `windowEnd <= windowStart`，NaN 会掉进主路径：
> `(long) Math.ceil(NaN)` 是 `0`，于是 `lo` 落到 `validStart`、`hi` 落到上界——
> 它会把**整个有效范围**画出来。**行为变了，而没有任何测试会响。**
>
> 实施者当时正确地**没有**自行加断言凑数（任务书没要求）。**将来若有任务再动
> `WindowRange`，顺手补一条 `compute(NaN, 4.0, 8L, CAP)` 必须为空的断言。**

## Task 6: `ChartRenderLayout` —— 数值/序号 ↔ 屏幕（纯算术，TDD）

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayout.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayoutTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayoutTest.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.AxisRange;
import com.bingbaihanji.jfgl.chart.AxisType;
import com.bingbaihanji.jfgl.util.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChartRenderLayout} 的单元测试。
 *
 * <p><b>核心是那条一致性断言</b>：数值 → 屏幕的映射在 CPU（{@code Axis.dataToDisplay}）
 * 与 GPU（顶点着色器里的 uniform 公式）上各有一份实现。两份不一致的表现是
 * <b>刻度线与数据点错开</b>——"看起来只是没对齐"，最难查的那类缺陷。
 * 这里用同一组输入同时跑两份，逐一比对。
 */
class ChartRenderLayoutTest {

    private static final Rect PLOT = new Rect(60f, 20f, 800f, 500f);

    private static Axis linearAxis(double min, double max, double px) {
        return new Axis(AxisType.LINEAR, new AxisRange(min, max, "v", "")).setDisplayLength(px);
    }

    @Test
    void y轴映射与Axis一致() {
        Axis y = linearAxis(-1.0, 1.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        // 轴自己的映射是"0 在顶部"（值越大越往下），而绘图区是数学惯例：值越大越往上。
        // 两者的关系必须是精确的：screenY(v) == plotY + plotH - axis.dataToDisplay(v)
        for (double v = -1.0; v <= 1.0; v += 0.125) {
            float expected = (float) (PLOT.y + PLOT.height - y.dataToDisplay(v));
            assertEquals(expected, layout.screenY(v), 1e-3f,
                    "y = " + v + " 处两份映射不一致");
        }
    }

    @Test
    void y轴端点落在绘图区边界上() {
        Axis y = linearAxis(-1.0, 1.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        assertEquals(PLOT.y + PLOT.height, layout.screenY(-1.0), 1e-3f, "最小值应落在底边");
        assertEquals(PLOT.y, layout.screenY(1.0), 1e-3f, "最大值应落在顶边");
        assertEquals(PLOT.y + PLOT.height / 2f, layout.screenY(0.0), 1e-3f, "中值应落在中线");
    }

    @Test
    void x轴按数据下标映射() {
        // 示波器：x 是采样序号，窗口 [100, 200)
        Axis x = linearAxis(100.0, 200.0, PLOT.width);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, x, linearAxis(-1, 1, PLOT.height));

        assertEquals(PLOT.x, layout.screenX(100.0), 1e-3f, "窗口左端的下标落在左边缘");
        assertEquals(PLOT.x + PLOT.width, layout.screenX(200.0), 1e-3f, "窗口右端落在右边缘");
        assertEquals(PLOT.x + PLOT.width / 2f, layout.screenX(150.0), 1e-3f);
    }

    @Test
    void 窗口外的值照样外推() {
        Axis y = linearAxis(0.0, 1.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        // 轴本身就不裁剪（裁剪是 glScissor 的活），映射必须照样给出线性外推的值，
        // 而不是钳到边界——钳了的话超出窗口的曲线会贴着边框画一条假的直线。
        assertTrue(layout.screenY(2.0) < PLOT.y, "超出上界的值应在绘图区上方，而不是被钳在顶边");
        assertTrue(layout.screenY(-1.0) > PLOT.y + PLOT.height, "超出下界的值应在下方");
    }

    @Test
    void 着色器uniform与CPU映射等价() {
        // 直接按顶点着色器里那几行公式算一遍，与 screenY 比对。
        // 着色器用的是 uValueMin / uValueMax / uPlotY / uPlotH 四个 uniform。
        Axis y = linearAxis(-2.0, 6.0, PLOT.height);
        ChartRenderLayout layout = new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), y);

        float uValueMin = layout.yMin();
        float uValueMax = layout.yMax();
        float uPlotY = PLOT.y;
        float uPlotH = PLOT.height;

        for (double v = -2.0; v <= 6.0; v += 0.25) {
            float fraction = (float) ((v - uValueMin) / (uValueMax - uValueMin));
            float shaderY = uPlotY + (1.0f - fraction) * uPlotH;
            assertEquals(shaderY, layout.screenY(v), 1e-3f,
                    "着色器公式与 CPU 映射在 y = " + v + " 处不一致");
        }
    }

    @Test
    void 对数轴明确抛异常不做静默错画() {
        Axis log = new Axis(AxisType.LOGARITHMIC, new AxisRange(1, 1000, "v", "")).setDisplayLength(500);
        assertThrows(UnsupportedOperationException.class,
                () -> new ChartRenderLayout(PLOT, linearAxis(0, 10, PLOT.width), log),
                "本期 GPU 路径只支持线性换算。静默按线性画对数轴，曲线形状是错的而画面正常");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ChartRenderLayoutTest`
Expected: 编译失败（`ChartRenderLayout` 不存在）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayout.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.AxisType;
import com.bingbaihanji.jfgl.util.Rect;

/**
 * 数值/序号 ↔ 屏幕像素的映射。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>它是两份实现里的 CPU 那一份</h2>
 * <p>同一个映射在顶点着色器里还有一份（用 {@code uValueMin} / {@code uValueMax} /
 * {@code uPlotY} / {@code uPlotH} 四个 uniform 算）。两份必须逐点一致，否则
 * <b>刻度线与数据点会错开</b>——那看起来"只是没对齐"，排查方向会跑偏。
 * 一致性由 {@code ChartRenderLayoutTest.着色器uniform与CPU映射等价} 钉住。
 *
 * <h2>y 的方向是反的</h2>
 * <p>{@link Axis#dataToDisplay(double)} 给出的是"0 在窗口最小值处、越大越往后"的位置；
 * 而绘图区是数学惯例——<b>值越大越靠上</b>。所以 y 要翻：
 * {@code screenY(v) == plotY + plotH - axis.dataToDisplay(v)}。
 * 这要求调用方把 y 轴的 {@code displayLength} 设成绘图区高度（x 轴设成宽度），
 * 该契约由上面那条一致性断言强制。
 *
 * <h2>本期只支持线性换算</h2>
 * <p>{@link AxisType#LINEAR} 与 {@link AxisType#TIME} 都是线性的（时间轴的值是纪元秒），
 * 两者都支持。{@link AxisType#LOGARITHMIC} 与 {@link AxisType#TEXT} 会
 * <b>明确抛异常</b>：按线性去画对数轴，曲线的形状是错的，而画面看起来完全正常——
 * 这正是本项目最警惕的静默错误输出。
 */
public final class ChartRenderLayout {

    private final Rect plotRect;
    private final float xMin;
    private final float xMax;
    private final float yMin;
    private final float yMax;

    /**
     * @param plotRect 绘图区（数据区域）矩形，设备像素
     * @param xAxis    x 轴，其窗口是可见的数据下标范围
     * @param yAxis    y 轴，其窗口是可见的数值范围
     * @throws UnsupportedOperationException 轴类型不是线性换算（见类文档）
     */
    public ChartRenderLayout(Rect plotRect, Axis xAxis, Axis yAxis) {
        this.plotRect = plotRect;
        requireLinear(xAxis, "x");
        requireLinear(yAxis, "y");
        this.xMin = (float) xAxis.windowMin();
        this.xMax = (float) xAxis.windowMax();
        this.yMin = (float) yAxis.windowMin();
        this.yMax = (float) yAxis.windowMax();
    }

    private static void requireLinear(Axis axis, String which) {
        AxisType type = axis.type();
        if (type != AxisType.LINEAR && type != AxisType.TIME) {
            throw new UnsupportedOperationException(
                    which + " 轴的类型是 " + type + "，本期 GPU 绘制路径只支持线性换算"
                            + "（LINEAR / TIME）。按线性去画非线性轴，曲线形状是错的而画面正常，"
                            + "因此这里明确报错而不是静默画错。");
        }
    }

    /** 绘图区矩形。 */
    public Rect plotRect() {
        return plotRect;
    }

    /** 着色器 uniform {@code uValueMin} 的 x 分量来源。 */
    public float xMin() {
        return xMin;
    }

    /** 着色器 uniform {@code uValueMax} 的 x 分量来源。 */
    public float xMax() {
        return xMax;
    }

    /** 着色器 uniform {@code uValueMin} 的 y 分量来源。 */
    public float yMin() {
        return yMin;
    }

    /** 着色器 uniform {@code uValueMax} 的 y 分量来源。 */
    public float yMax() {
        return yMax;
    }

    /**
     * 数据下标 → 屏幕 x。
     *
     * @param index 数据下标（可以是小数，支持亚像素滚动）
     * @return 屏幕 x（设备像素），窗口外照样线性外推
     */
    public float screenX(double index) {
        return (float) (plotRect.x + fraction(index, xMin, xMax) * plotRect.width);
    }

    /**
     * 数值 → 屏幕 y。<strong>已按"值越大越靠上"翻转。</strong>
     *
     * @param value 数据值
     * @return 屏幕 y（设备像素），窗口外照样线性外推
     */
    public float screenY(double value) {
        return (float) (plotRect.y + (1.0 - fraction(value, yMin, yMax)) * plotRect.height);
    }

    private static double fraction(double v, float min, float max) {
        double span = (double) max - min;
        // 退化范围在 ① 的 Axis 构造里已被 withMinimumSpan() 稳定化，不会走到这里；
        // 这里再兜一次，免得除零产生 NaN 后一路传到顶点位置上。
        if (span == 0.0) {
            return 0.0;
        }
        return (v - min) / span;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=ChartRenderLayoutTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异验证**

| 变异 | 期望失败的断言 |
|---|---|
| `screenY` 去掉 `1.0 -` 的翻转 | `y轴端点落在绘图区边界上` |
| `screenY` 用 `plotRect.x` 而非 `y()` | `y轴端点落在绘图区边界上` |
| `fraction` 里 `(v - min)` 写成 `(v - max)` | `着色器uniform与CPU映射等价` |
| `requireLinear` 改成对 LOG 也放行 | `对数轴明确抛异常不做静默错画` |

任何一条存活都要报告。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayout.java \
        src/test/java/com/bingbaihanji/jfgl/chartrender/ChartRenderLayoutTest.java
git commit -F - <<'EOF'
feat(chartrender): 数值/序号 <-> 屏幕的映射（纯算术）

这是两份实现里的 CPU 那一份，顶点着色器里还有一份。两份不一致的表现
是刻度线与数据点错开——"看起来只是没对齐"，最难查的那类缺陷。
测试里有一条断言用同一组输入同时跑两份公式，逐一比对。

y 方向是反的：Axis.dataToDisplay 给出"越大越靠后"，而绘图区是数学惯例
"值越大越靠上"。该断言同时钉住了"调用方必须把 y 轴 displayLength
设成绘图区高度"这个契约。

LOGARITHMIC 与 TEXT 轴明确抛异常。按线性去画对数轴，曲线形状是错的
而画面看起来完全正常——本项目最警惕的静默错误输出。
EOF
```

---

## Task 7: `SeriesUploadPlan` —— 增量上传的字节区间（纯算术，TDD）

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlan.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlanTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlanTest.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SeriesUploadPlan} 的单元测试。
 *
 * <p><b>这个类存在的全部理由是"每帧只上传新增的点"。</b>它的字节数统计
 * （{@link SeriesUploadPlan#totalBytes()}）是 {@code ChartVerifier} 里那条
 * "滚动 100 帧之后每帧上传字节数恒等于新增点数 × 4" 断言的来源——
 * 那是唯一能把"GPU 常驻 + 增量上传"与"每帧全量重传"区分开的量。
 */
class SeriesUploadPlanTest {

    private static final int CAP = 8;

    @Test
    void 空变更不产生上传() {
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 0L, 0L, CAP);
        assertTrue(plan.ranges().isEmpty());
        assertEquals(0, plan.totalBytes());
    }

    @Test
    void 连续新增是一段字节区间() {
        // 从 0 增加到 3：写槽位 0,1,2 -> 偏移 0..11，共 12 字节
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 3L, 0L, CAP);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(0, 12), plan.ranges().get(0));
        assertEquals(12, plan.totalBytes());
    }

    @Test
    void 跨环绕时切成两段() {
        // 容量 8，从 6 增加到 10：槽位 6,7,0,1 -> 偏移 24..31 与 0..7
        SeriesUploadPlan plan = SeriesUploadPlan.between(6L, 10L, 6L, CAP);
        assertEquals(2, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(24, 8), plan.ranges().get(0));
        assertEquals(new SeriesUploadPlan.Range(0, 8), plan.ranges().get(1));
        assertEquals(16, plan.totalBytes());
    }

    @Test
    void 环装得下时字节数只与新增点数有关() {
        // 新增 10 个点，两种环容量都装得下，所以都传 40 字节——
        // 与窗口多宽、环多大**无关**（这是"每帧只传新增的点"这条主张的最小形式）。
        assertEquals(40, SeriesUploadPlan.between(100L, 110L, 1024).totalBytes());
        assertEquals(40, SeriesUploadPlan.between(100L, 110L, 1 << 16).totalBytes());
    }

    @Test
    void 新增超过环容量时只传最后一圈() {
        // 这条与上一条**不矛盾**：上限是"环里还留着的那些"。
        // 一次新增 10 个点但环只有 8——前面 2 个已经被覆盖，传了也是白传。
        //
        // 注意这条测试纠正了原稿的一个错误预期：原稿声称"无论环多大都传 40"，
        // 而容量 8 时只能是 32。**夹取是对的，错的是原稿的期望值。**
        assertEquals(32, SeriesUploadPlan.between(100L, 110L, CAP).totalBytes(),
                "容量 8：只传最后 8 个点");
        assertEquals(40, SeriesUploadPlan.between(100L, 110L, 1 << 16).totalBytes(),
                "容量够大：10 个点全传");
    }

    @Test
    void 一次写满整个环() {
        // 从 0 增加到 8（正好一整圈）：槽位 0..7，偏移 0..31，一段
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 8L, 0L, CAP);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(0, 32), plan.ranges().get(0));
        assertEquals(32, plan.totalBytes());
    }

    @Test
    void 超过一整圈时只传最后一圈() {
        // 从 0 增加到 20，容量 8：前面的会被覆盖，只有最后 8 个还在缓冲里
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 20L, 0L, CAP);
        assertEquals(32, plan.totalBytes(), "超出容量的部分已经被覆盖，传了也是白传");
    }

    @Test
    void 单个点的偏移是下标乘4() {
        SeriesUploadPlan plan = SeriesUploadPlan.between(5L, 6L, 5L, CAP);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(20, 4), plan.ranges().get(0));
    }

    @Test
    void 容量非2的幂时抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> SeriesUploadPlan.between(0L, 1L, 0L, 12));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=SeriesUploadPlanTest`
Expected: 编译失败（`SeriesUploadPlan` 不存在）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlan.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import java.util.ArrayList;
import java.util.List;

/**
 * 增量上传的字节区间计划。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>它是 ② 的性能主张的度量点</h2>
 * <p>整个 ② 的主张是"<b>每帧只上传新增的点，不是整个窗口</b>"。
 * 这个类算的就是"要传哪几个字节"，而 {@link #totalBytes()} 是那个主张的<b>可观测形式</b>。
 *
 * <p>为什么需要一个可观测形式：画面在"增量上传"与"每帧全量重传"这两种实现下
 * <b>完全一样</b>。只看像素的断言一条都区分不出来。所以
 * {@code ChartVerifier} 断言的是"滚动 100 帧之后，每帧上传字节数恒等于新增点数 × 4"
 * ——与 {@code TextVerifier} 里那条"24px 与 192px 的过渡带宽度都是 1px"同类：
 * <b>断言的量就是那件事本身</b>。
 *
 * <h2>每个点 4 字节</h2>
 * <p>缓冲里只存 y 值（float）。线段的两端靠"同一个缓冲、偏移差 4 字节"的两个实例属性拿到，
 * 所以同一个 y 只存一次。缓冲要比环容量多留一个 float 的余量（最后一个实例的第二端
 * 会指到界外，虽然那个实例永远不画）。
 */
public final class SeriesUploadPlan {

    /**
     * 一段连续的上传。
     *
     * @param byteOffset 相对缓冲起点的字节偏移
     * @param byteLength 字节数
     */
    public record Range(int byteOffset, int byteLength) {
    }

    private final List<Range> ranges;
    private final int totalBytes;

    private SeriesUploadPlan(List<Range> ranges, int totalBytes) {
        this.ranges = ranges;
        this.totalBytes = totalBytes;
    }

    /**
     * 计算 {@code uploadedCount}（上一次已上传到哪）之后新写入的样本要传哪些字节。
     *
     * <p>超出环容量的部分已经被新数据覆盖，传了也是白传，直接丢掉。
     *
     * @param uploadedCount 上一次上传时的写入总数
     * @param writtenCount  本次的写入总数
     * @param capacity      环容量，<strong>必须是 2 的幂</strong>
     * @param ignoredCapacityForDocs 占位参数，见下方说明
     * @return 上传计划
     * @throws IllegalArgumentException 容量不是 2 的幂
     */
    public static SeriesUploadPlan between(long uploadedCount, long writtenCount,
                                           long capacityPlaceholder, int capacity) {
        throw new UnsupportedOperationException("待实现");
    }

    /** 要上传的字节区间，顺序即执行顺序（跨环绕时有两段）。 */
    public List<Range> ranges() {
        return ranges;
    }

    /** 要上传的总字节数。 */
    public int totalBytes() {
        return totalBytes;
    }
}
```

> **注意**：上面的签名有一处**必须由实现者修正**——`capacityPlaceholder` 是多余的（起点已经由 `uploadedCount` 表达，环容量只需要用来夹取"至多传一圈"）。**请把签名定成**
> ```java
> public static SeriesUploadPlan between(long uploadedCount, long writtenCount, int capacity)
> ```
> 并同步修改测试里的三处调用（`between(0L, 3L, 0L, CAP)` → `between(0L, 3L, CAP)`，依此类推）。这是本计划故意留下的唯一一处"实现时定签名"，因为占位参数在草稿里更容易看出多余。

- [ ] **Step 4: 实现 `between`**

替换掉 `throw new UnsupportedOperationException("待实现")`：

```java
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + capacity);
        }
        long newCount = writtenCount - uploadedCount;
        if (newCount <= 0) {
            return new SeriesUploadPlan(List.of(), 0);
        }
        // 超过一圈的部分已经被覆盖，传了也是白传。
        long effective = Math.min(newCount, capacity);
        long firstWritten = writtenCount - effective;

        int firstSlot = (int) (firstWritten & (capacity - 1));
        int points = (int) effective;
        List<Range> ranges = new ArrayList<>(2);
        if (firstSlot + points <= capacity) {
            ranges.add(new Range(firstSlot * Float.BYTES, points * Float.BYTES));
        } else {
            int headPoints = capacity - firstSlot;
            ranges.add(new Range(firstSlot * Float.BYTES, headPoints * Float.BYTES));
            ranges.add(new Range(0, (points - headPoints) * Float.BYTES));
        }
        int total = points * Float.BYTES;
        return new SeriesUploadPlan(List.copyOf(ranges), total);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -Dtest=SeriesUploadPlanTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 6: 变异验证**

| 变异 | 期望失败的断言 |
|---|---|
| 去掉 `Math.min(newCount, capacity)`（不做"只传一圈"的夹取） | `超过一整圈时只传最后一圈` |
| `firstSlot` 用 `writtenCount & (cap-1)` 而非 `firstWritten & (cap-1)` | `跨环绕时切成两段`、`连续新增是一段字节区间` |
| `Float.BYTES` 换成 `Double.BYTES` | 所有字节数断言 |
| 去掉 `newCount <= 0` 的早退 | `空变更不产生上传` |

任何一条存活都要报告。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlan.java \
        src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesUploadPlanTest.java
git commit -F - <<'EOF'
feat(chartrender): 增量上传的字节区间计划（纯算术）

totalBytes() 是"每帧只上传新增的点"这个主张的可观测形式。画面在增量
上传与每帧全量重传两种实现下完全一样，只看像素的断言一条都区分不出来,
所以 ChartVerifier 断言的是这个字节数。

超出一圈的部分已被覆盖，传了也是白传，直接丢掉。
EOF
```

---

> ## ⚠️ Task 7 实施期报上来的两条，Task 8 必须处置
>
> **一、"写入总数回退"目前是静默的。**
>
> `SeriesUploadPlan.between` 的早退条件 `newCount <= 0` 只被 `between(0,0,…)`（恰好为 0）
> 钉住。而 `uploadedCount > writtenCount`（写入总数**倒退**）走的是同一分支、返回"不上传"——
> **没有任何测试钉住它**。
>
> 更值得警惕的是**这个行为对不对**：如果数据源的写入总数倒退（换了数据源、或 `ChartData`
> 的实现被替换），GPU 缓冲里留的是**旧数据**，而返回"不上传"意味着**画面继续显示旧数据**，
> 一声不响。当前数据源不会退（`RingChartData.writeIndex` 单调、`ArrayChartData.itemCount` 恒定），
> 所以不可达——**但不可达不等于无害**。
>
> **Task 8 要做的**：在 `uploadNewSamples` 里把这个不变式变成**响亮的失败**——
> ```java
> if (written < uploadedCount) {
>     throw new IllegalStateException(
>             "写入总数倒退了（uploadedCount=" + uploadedCount + ", written=" + written
>                     + "）：缓冲里现在是旧数据，继续画会静默显示过期内容。"
>                     + "换数据源时必须重建 SeriesBuffer。");
> }
> ```
> 并补一条 `SeriesUploadPlanTest` 的断言（`between(50L, 20L, CAP)` 返回空），把"恰好为 0"
> 那半边补成完整的两半。
>
> **二、`totalBytes()` 在容量 ≥ 2^29 时整型溢出。**
>
> `points * Float.BYTES` 是 `int`：容量 `2^29` 时 `2^29 * 4 = 2^31` 溢出为负。
> 当前环容量在 `2^16` 量级，**不可达**；`2^20`（100 万点 = 4 MB）也远在安全区内。
> **安全上界是容量 ≤ `2^28`**（1 GB 缓冲）。
>
> **不改成 `long`**（当前不需要，YAGNI），但**把这条上界写进 `SeriesBuffer` 的类文档**，
> 并在将来真要把环做到 `2^29` 以上时改。
>
> 附带一条实施者发现的好东西：`capacity <= 0 || (capacity & (capacity-1)) != 0` 那半条
> `capacity <= 0` **不是多余的**——它挡住了 `1 << 31`（负数）冒充 2 的幂混过按位判断。
> **两条合起来才是完整的守卫**，别顺手删掉前一条。

## Task 8: `SeriesBuffer` —— 一个系列的 GPU 常驻缓冲

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesBuffer.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesBufferTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesBufferTest.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.gl.FakeGLAbstraction;
import com.bingbaihanji.jfgl.chart.AxisRange;
import com.bingbaihanji.jfgl.chart.RingChartData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SeriesBuffer} 的单元测试，用 {@code FakeGLAbstraction} 脱离 GL 上下文跑。
 *
 * <p>先例现成：{@code gl/Framebuffer}、{@code renderer/PickBuffer}、
 * {@code text/GlyphAtlas} 都是这么测的。
 */
class SeriesBufferTest {

    private FakeGLAbstraction gl;
    private RingChartData data;

    private static RingChartData newData(int capacity) {
        return new RingChartData(new AxisRange[]{AxisRange.of(0, 1)}, capacity);
    }

    @BeforeEach
    void setUp() {
        gl = new FakeGLAbstraction();
        data = newData(8);
    }

    @Test
    void 构造时分配固定大小的缓冲并留一个float余量() {
        new SeriesBuffer(gl, data);

        assertEquals(1, gl.createdVbos.size(), "应当建恰好一个 VBO");
        // 容量 8，但最后一个实例的第二端会指到界外，所以多留一个 float
        assertEquals((8 + 1) * Float.BYTES, SeriesBuffer.bufferBytesFor(8),
                "缓冲字节数必须是 (容量 + 1) * 4");
    }

    @Test
    void 首次上传带上全部已有数据() {
        data.append(1.0, 2.0, 3.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);

        buf.uploadNewSamples();

        assertEquals(12, buf.uploadedBytesThisFrame(), "3 个点 * 4 字节");
    }

    @Test
    void 第二次上传只传新增的点() {
        data.append(1.0, 2.0, 3.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        buf.beginFrame();

        data.append(4.0, 5.0);
        buf.uploadNewSamples();

        assertEquals(8, buf.uploadedBytesThisFrame(),
                "只该传新增的 2 个点 = 8 字节。整窗重传的话这里是 20");
    }

    @Test
    void 没有新数据时一个字节都不传() {
        data.append(1.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        buf.beginFrame();

        buf.uploadNewSamples();

        assertEquals(0, buf.uploadedBytesThisFrame());
        assertEquals(1, gl.vboSubDataCalls.size(), "第二次不该产生任何上传调用");
    }

    @Test
    void 子上传的偏移是环槽位乘4() {
        data.append(1.0, 2.0, 3.0, 4.0, 5.0);
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();

        // 5 个点写在槽位 0..4，偏移 0..16
        //
        // 记录格式是 "VBO名@偏移:字节数"（实施期质量复核要求带上目标 VBO——
        // Task 11 之后多 VBO 并存时，"上传落到别的系列的缓冲上"是画面正常、
        // 只是数据错的一类缺陷，不记 VBO 名就问不出来）。
        // 这里只断言偏移与字节数，不硬编码 VBO 名——那个名字取决于假实现的
        // ID 分配顺序，钉死它会让测试因为无关的改动而失败。
        assertEquals(1, gl.vboSubDataCalls.size());
        assertTrue(gl.vboSubDataCalls.get(0).endsWith("@0:20"),
                "偏移应是 0、字节数 20（5 个点 * 4），实际 " + gl.vboSubDataCalls.get(0));
    }

    @Test
    void 跨环绕时产生两次子上传() {
        // 关键是**这一次上传**的新增样本要跨过环绕点，而不是"环曾经绕过"。
        //
        // ⚠️ 原稿这条是错的：它先把前 8 个点传掉（uploadedCount=8），再追加 2 个——
        // 那 2 个落在槽位 0、1，是**连续**的，只会产生 1 次子上传，断言必然失败。
        // 要让一次上传跨环绕，必须让新样本的起始槽位高、数量大到越过环尾：
        data.append(1, 2, 3, 4, 5, 6);        // 先只写 6 个（槽位 0..5），**不上传**
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        data.append(7, 8, 9, 10);             // 再写 4 个（槽位 6,7,0,1）——共 10 个
        buf.uploadNewSamples();

        // between(0, 10, 8)：newCount=10、effective=8、firstWritten=2、firstSlot=2，
        // 2 + 8 > 8 -> 切成两段（槽位 2..7 与 0..1）
        assertEquals(2, gl.vboSubDataCalls.size(), "新增样本跨过环绕点必须切成两次子上传");
    }

    @Test
    void 环写满之后仍然继续上传() {
        // 这是本任务最重要的一条断言。
        //
        // RingChartData.itemCount() 返回 min(writeIndex, capacity)——环满之后它就
        // 停在 capacity 不动了。若 SeriesBuffer 拿 itemCount() 当"写到哪了"，
        // 它会在环满之后认为"没有新数据"，于是静默地再也不上传，画面定格在第一屏。
        // 那条路径不报任何错，只有这条断言拦得住。
        for (int i = 0; i < 8; i++) {
            data.append(i);
        }
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.uploadNewSamples();
        assertEquals(8, data.itemCount(), "前提：环已满");
        gl.vboSubDataCalls.clear();
        buf.beginFrame();

        data.append(99);
        assertEquals(8, data.itemCount(), "前提：环满之后 itemCount() 不再增长");

        buf.uploadNewSamples();

        assertEquals(4, buf.uploadedBytesThisFrame(),
                "环满之后新点照样要上传。这里若为 0，说明把 itemCount() 当成了写指针");
    }

    @Test
    void 容量与数据不一致时抛异常() {
        RingChartData bigger = newData(16);
        assertThrows(IllegalArgumentException.class,
                () -> new SeriesBuffer(gl, bigger, 8),
                "缓冲容量与数据的环容量不一致会让槽位算术全错，必须构造时就拦住");
    }

    @Test
    void 释放后删除VBO且幂等() {
        SeriesBuffer buf = new SeriesBuffer(gl, data);
        buf.dispose();
        buf.dispose();

        assertEquals(1, gl.deletedVbos.size());
    }

    @Test
    void 非环形数据按追加语义处理() {
        // ArrayChartData 是静态的：revision 不变时报无脏区，一次上传后永不重传
        var static_ = ChartDataFixtures.arrayOf(0, 1, 2, 3);
        SeriesBuffer buf = new SeriesBuffer(gl, static_);
        buf.uploadNewSamples();
        int first = buf.uploadedBytesThisFrame();
        buf.beginFrame();
        buf.uploadNewSamples();

        assertEquals(16, first);
        assertEquals(0, buf.uploadedBytesThisFrame(),
                "静态数据一次上传后必须永不重传");
    }

    @Test
    void NaN端点被标记为退化() {
        // NaN 不参与上传决策（它照样是 4 字节写进缓冲），但 SeriesBuffer 要能
        // 按窗口区间报出"这些实例里有 NaN"——供渲染器决定要不要画。
        RingChartData d = newData(8);
        d.append(1.0, Double.NaN, 3.0);
        SeriesBuffer buf = new SeriesBuffer(gl, d);
        buf.uploadNewSamples();

        assertTrue(buf.hasGapBetween(0, 2), "下标 1 是 NaN，实例 0 与 1 都跨了缺口");
        assertFalse(buf.hasGapBetween(2, 3), "实例 2 连接的是 2 与 3，两端都正常");
    }
}
```

> **两处辅助需要一并补上**（它们不属于被测类，放在测试目录里）：
>
> `src/test/java/com/bingbaihanji/jfgl/chartrender/ChartDataFixtures.java`：
> ```java
> package com.bingbaihanji.jfgl.chartrender;
>
> import com.bingbaihanji.jfgl.chart.ArrayChartData;
> import com.bingbaihanji.jfgl.chart.AxisRange;
>
> /** 测试用的数据装配。 */
> final class ChartDataFixtures {
>     private ChartDataFixtures() {
>     }
>
>     /** 造一个二维静态数据，第一维是下标 0..n-1，第二维取给定值。 */
>     static ArrayChartData arrayOf(double... values) {
>         double[] xs = new double[values.length];
>         for (int i = 0; i < values.length; i++) {
>             xs[i] = i;
>         }
>         return new ArrayChartData(
>                 new AxisRange[]{AxisRange.of(0, Math.max(1, values.length)),
>                                 AxisRange.of(-1, 1)},
>                 new double[][]{xs, values});
>     }
> }
> ```
>
> `RingChartData` 需要 `append(double... values)` 这个可变参数重载。**先去读 `RingChartData.java` 确认它实际的追加签名**——若是 `append(double)` 单值，就把测试里的 `data.append(1.0, 2.0, 3.0)` 改成三次单值调用，**不要为了测试去改生产代码的签名**。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=SeriesBufferTest`
Expected: 编译失败（`SeriesBuffer` 不存在）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesBuffer.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.ChartData;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.util.Disposable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 一个系列的 GPU 常驻缓冲。
 *
 * <h2>它只存 y 值</h2>
 * <p>每个样本 4 字节。线段的两端靠"同一个缓冲、偏移差 4 字节"的两个实例属性拿到
 * （见 {@code LineSeriesRenderer}），所以同一个 y 只存一次。
 *
 * <p><strong>缓冲比环容量多留一个 float</strong>：最后一个实例的第二端会指到界外。
 * 那个实例永远不画，但别让 GPU 有机会去读越界地址。
 *
 * <h2>容量在创建时定死，运行期永不扩容</h2>
 * <p>这是硬约束，不是优化。{@code VertexBuffer.grow()} 是"删旧建新"，
 * 而扩容会让 VAO 里记录的缓冲绑定失效（见 {@code RenderBatch.configureVaoAttributes}）。
 * 因此这里自己管 VBO，<strong>不复用 {@code VertexBuffer}</strong>。
 *
 * <h2>它自己不做脏区判断</h2>
 * <p>"该传哪几个字节"由 {@link SeriesUploadPlan} 算——那是纯算术，可以穷举测试。
 * 本类只负责把算出来的计划执行掉，并<strong>记下这一帧传了多少字节</strong>。
 *
 * <h2>每个点占 4 字节，不是 8</h2>
 * <p>数据存成 float32。若数据动态范围极大（量级 1e9 上要分辨 1e-3 的差别），
 * float32 表示不了，表现为<b>曲线是平的或有台阶</b>。
 * 逃生口：上传前先减去一个基准值（存相对值，着色器里再加回来）。
 * 本期不做。
 */
public final class SeriesBuffer implements Disposable {

    private final GLAbstraction gl;
    private final SeriesSource source;
    private final int capacity;
    private final int vbo;

    /** 上一次已经上传到哪（已写样本总数的绝对号）。 */
    private long uploadedCount;

    /** 本帧的上传字节数，每帧由 {@link #beginFrame()} 清零。 */
    private int uploadedBytesThisFrame;

    private boolean disposed = false;

    /**
     * 缓冲字节数：容量个 float，外加一个 float 的余量。
     *
     * @param capacity 环容量（2 的幂）
     * @return 字节数
     */
    public static int bufferBytesFor(int capacity) {
        return (capacity + 1) * Float.BYTES;
    }

    /**
     * 为给定的数据创建缓冲，容量与数据的环容量一致。
     *
     * @param gl   GL 抽象层
     * @param data 数据
     */
    public SeriesBuffer(GLAbstraction gl, ChartData data) {
        this(gl, data, capacityOf(data));
    }

    /**
     * 为给定的数据创建缓冲，显式指定容量。
     *
     * @param gl       GL 抽象层
     * @param data     数据
     * @param capacity 缓冲容量；必须与数据自己的环容量一致
     * @throws IllegalArgumentException 容量不是 2 的幂，或与数据的环容量不一致
     */
    public SeriesBuffer(GLAbstraction gl, ChartData data, int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("缓冲容量必须是 2 的幂，实际 " + capacity);
        }
        int dataCapacity = capacityOf(data);
        if (dataCapacity > 0 && dataCapacity != capacity) {
            throw new IllegalArgumentException(
                    "缓冲容量 " + capacity + " 与数据的环容量 " + dataCapacity
                            + " 不一致：槽位算术会全错，而画面只是"看起来有点歪"");
        }
        this.gl = gl;
        this.source = SeriesSource.of(data);
        this.capacity = capacity;
        this.vbo = gl.createVbo();
        gl.bindVbo(vbo);
        // 分配：用一次全零的 uploadVboData 把缓冲撑到目标大小。
        // 之后一律走 uploadVboSubData，永不重新分配。
        gl.uploadVboData(new float[capacity + 1]);
        gl.bindVbo(0);
    }

    private static int capacityOf(ChartData data) {
        // RingChartData 自己声明环容量。
        if (data instanceof RingChartData ring) {
            return ring.capacity();
        }
        // 静态数据（ArrayChartData）没有环，按"下一次 2 的幂 ≥ 点数"给，
        // 这样它也能走同一条增量上传路径（一次上传后 revision 不变，永不重传）。
        int n = Math.max(1, data.itemCount());
        int cap = Integer.highestOneBit(n);
        return cap < n ? cap << 1 : cap;
    }

    /** 底层 VBO 的名字，供 VAO 配置用。 */
    public int vboId() {
        return vbo;
    }

    /** 环容量。 */
    public int capacity() {
        return capacity;
    }

    /** 每帧开始时清零本帧的上传计数。 */
    public void beginFrame() {
        uploadedBytesThisFrame = 0;
    }

    /**
     * 把新增的样本传上去。<strong>已经传过的不会重传。</strong>
     */
    public void uploadNewSamples() {
        if (disposed) {
            throw new IllegalStateException("SeriesBuffer 已释放");
        }
        // 注意这里问的是 source.writeCount()（已写样本总数，只会增长），
        // 而 **不是** data.itemCount()。
        //
        // RingChartData.itemCount() 返回 min(writeIndex, capacity)——环写满之后
        // 它就永远停在 capacity 不动了。拿它当"写到哪了"，会让本方法在环满了之后
        // 认为"没有新数据"，于是 **静默地再也不上传**，画面定格在第一屏。
        long written = source.writeCount();
        if (written == uploadedCount) {
            return;
        }
        SeriesUploadPlan plan = SeriesUploadPlan.between(uploadedCount, written, capacity);
        if (plan.totalBytes() == 0) {
            uploadedCount = written;
            return;
        }
        int totalPoints = plan.totalBytes() / Float.BYTES;
        long absolute = written - totalPoints;
        ByteBuffer scratch = ByteBuffer.allocateDirect(plan.totalBytes())
                .order(ByteOrder.nativeOrder());
        for (SeriesUploadPlan.Range range : plan.ranges()) {
            scratch.clear();
            int points = range.byteLength() / Float.BYTES;
            for (int i = 0; i < points; i++) {
                // 绝对号递增地取；**不能**用 range.byteOffset() / 4 反推——
                // 那是环槽位，跨环绕时第二段的槽位回到 0，与绝对号对不上。
                scratch.putFloat((float) source.yAt(absolute++));
            }
            scratch.flip();
            gl.bindVbo(vbo);
            gl.uploadVboSubData(range.byteOffset(), scratch);
            gl.bindVbo(0);
        }
        uploadedBytesThisFrame += plan.totalBytes();
        uploadedCount = written;
    }

    /** 本帧通过 {@link #uploadNewSamples()} 上传的字节数。 */
    public int uploadedBytesThisFrame() {
        return uploadedBytesThisFrame;
    }

    /**
     * 区间 {@code [from, to)} 里有没有缺口（NaN）。
     *
     * <p>渲染器靠它决定要不要把这一段整体跳过——顶点着色器那侧还有一道
     * "任一端是 NaN 就退化"的保险，两道都要有：着色器那道防的是画错，
     * 这道防的是<b>让 GPU 去读一个 NaN 并产生未定义的光栅化</b>。
     *
     * @param fromAbsolute 起始样本的<strong>绝对号</strong>（不是窗口相对下标）
     * @param toAbsolute   结束样本的绝对号（半开）
     * @return 区间内有 NaN 时为 true
     */
    public boolean hasGapBetween(long fromAbsolute, long toAbsolute) {
        for (long i = fromAbsolute; i < toAbsolute; i++) {
            if (Double.isNaN(source.yAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        gl.deleteVbo(vbo);
        disposed = true;
    }
}
```

> **`uploadNewSamples` 用到的 `source` 是一个新的适配层**，创建
> `src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesSource.java`。
>
> **它存在的理由是两个数据实现的索引语义不同，而 `SeriesBuffer` 不该知道这件事：**
>
> | | `RingChartData` | `ArrayChartData` |
> |---|---|---|
> | 已写样本总数 | `writeIndex()`（**一直增长**） | `itemCount()`（不变） |
> | `itemCount()` 的含义 | `min(writeIndex, capacity)`——**环满了就停住** | 点数 |
> | `value(dim, i)` 的 `i` | **相对可见窗口**：`absolute = windowStart() + i` | 绝对下标 |
> | 环容量 | `capacity()` | 无（按点数取 2 的幂） |
>
> ```java
> package com.bingbaihanji.jfgl.chartrender;
>
> import com.bingbaihanji.jfgl.chart.ArrayChartData;
> import com.bingbaihanji.jfgl.chart.ChartData;
> import com.bingbaihanji.jfgl.chart.RingChartData;
>
> /**
>  * 把两种数据实现的索引语义归一成"绝对号"。
>  *
>  * <h2>为什么必须有这一层</h2>
>  * <p>{@link RingChartData#itemCount()} 返回 {@code min(writeIndex, capacity)}——
>  * <b>环写满之后它就永远停在 capacity 不动了</b>。拿它当"写到哪了"，
>  * 增量上传会在环满之后认为没有新数据，于是<b>静默地再也不上传，画面定格</b>。
>  *
>  * <p>而且 {@code RingChartData.value(dim, i)} 的 {@code i} 是<b>相对可见窗口</b>的，
>  * 而 {@code ArrayChartData.value(dim, i)} 的是绝对下标。两者的差别不该泄漏到
>  * {@link SeriesBuffer} 里。
>  */
> interface SeriesSource {
>
>     /** 已写入的样本总数（<b>单调增长</b>，不是"可见样本数"）。 */
>     long writeCount();
>
>     /**
>      * 取某个维度上第 {@code absoluteIndex} 个样本的值。
>      *
>      * <p>超出有效范围时返回 {@link RingChartData#GAP}（NaN），
>      * <b>不抛异常</b>——与 {@code ChartData.value} 的约定一致：
>      * 读者只需要一条规则"遇到 NaN 就断开折线"。
>      */
>     double valueAt(int dim, long absoluteIndex);
>
>     /** 环容量（2 的幂）。 */
>     int capacity();
>
>     /** 按数据的实际类型选实现。 */
>     static SeriesSource of(ChartData data) {
>         if (data instanceof RingChartData ring) {
>             return new SeriesSource() {
>                 @Override
>                 public long writeCount() {
>                     return ring.writeIndex();
>                 }
>
>                 @Override
>                 public double valueAt(int dim, long absoluteIndex) {
>                     return ring.value(dim, (int) (absoluteIndex - ring.windowStart()));
>                 }
>
>                 @Override
>                 public int capacity() {
>                     return ring.capacity();
>                 }
>             };
>         }
>         if (data instanceof ArrayChartData) {
>             int cap = capacityFor(data.itemCount());
>             return new SeriesSource() {
>                 @Override
>                 public long writeCount() {
>                     return data.itemCount();
>                 }
>
>                 @Override
>                 public double valueAt(int dim, long absoluteIndex) {
>                     if (absoluteIndex < 0 || absoluteIndex >= data.itemCount()) {
>                         return RingChartData.GAP;
>                     }
>                     return data.value(dim, (int) absoluteIndex);
>                 }
>
>                 @Override
>                 public int capacity() {
>                     return cap;
>                 }
>             };
>         }
>         throw new IllegalArgumentException(
>                 "不认识的数据实现：" + data.getClass().getName()
>                         + "。SeriesBuffer 依赖"已写样本总数"与"按绝对号取数"这两个语义，"
>                         + "新实现必须在这里补上适配，否则增量上传会静默算错。");
>     }
>
>     /** 下一次 2 的幂。 */
>     static int capacityFor(int n) {
>         int c = Integer.highestOneBit(Math.max(1, n));
>         return c < Math.max(1, n) ? c << 1 : c;
>     }
> }
> ```
>
> **两个实现的取数语义差别，必须有测试各钉一条**：`RingChartData` 的环绕取数
> （写满一圈后再写，`writeCount()` 继续涨而 `itemCount()` 不动）、
> `ArrayChartData` 的绝对取数（revision 不变时第二次上传为 0 字节）。
> 上面 `SeriesBufferTest` 里的 `跨环绕时产生两次子上传` 与 `非环形数据按追加语义处理`
> 就是这两条，**但它们还不充分**——再加一条"环写满后继续写，仍然有字节上传"，
> 那才是"用 `itemCount()` 当写指针"这个缺陷的直接对抗。

> **本任务的核心风险已经由 `SeriesSource` 隔离掉了**，但请务必确认上面那条
> "环写满后继续写"的测试**真的写出来了**——它是"用 `itemCount()` 当写指针"
> 这个缺陷的直接对抗。**如果那条测试不存在，这个缺陷会以"画面定格在第一屏"的形式出现，
> 而且没有任何报错。**

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=SeriesBufferTest`
Expected: 全部通过。

- [ ] **Step 5: 变异验证**

| 变异 | 期望失败的断言 |
|---|---|
| `uploadedCount` 不更新（每次都从 0 传） | `第二次上传只传新增的点`、`没有新数据时一个字节都不传` |
| `beginFrame()` 不清零 | `没有新数据时一个字节都不传` |
| 缓冲字节数去掉 `+ 1` | `构造时分配固定大小的缓冲并留一个float余量` |
| 构造时不做容量一致性检查 | `容量与数据不一致时抛异常` |

任何一条存活都要报告。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesBuffer.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesCapacities.java \
        src/test/java/com/bingbaihanji/jfgl/chartrender/SeriesBufferTest.java \
        src/test/java/com/bingbaihanji/jfgl/chartrender/ChartDataFixtures.java
git commit -F - <<'EOF'
feat(chartrender): 系列的 GPU 常驻缓冲

容量在创建时定死、运行期永不扩容。这是硬约束：VertexBuffer.grow() 是
"删旧建新"，扩容会让 VAO 里记录的缓冲绑定失效，所以这里自己管 VBO，
不复用 VertexBuffer。

它自己不做脏区判断——"该传哪几个字节"由 SeriesUploadPlan 算，
那是纯算术、可穷举测试。本类只执行计划并记下这一帧传了多少字节。

用 FakeGLAbstraction 脱离 GL 上下文测试，先例现成。
EOF
```

---

## Task 9: 着色器

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesShaders.java`

- [ ] **Step 1: 创建常量类**

```java
package com.bingbaihanji.jfgl.chartrender;

/**
 * 图表渲染器用的 GLSL 源码。
 *
 * <h2>一个顶点着色器，两个片段着色器</h2>
 * <p>绘制用 {@link #LINE_FRAGMENT}，拾取用 {@link #PICK_FRAGMENT}，
 * 两者共用 {@link #LINE_VERTEX}。这与 {@code RenderBatch} 里
 * "SDF 文本复用同一个顶点着色器、只换片段着色器"是同一个做法。
 *
 * <h2>顶点着色器只输出 vId，绘制时没人用</h2>
 * <p>顶点着色器声明了 {@code flat out uint vId}，而绘制用的片段着色器不声明对应的
 * {@code in}——GLSL 允许，未使用的输出会被丢弃。这样两个程序能共用一份顶点源码。
 *
 * <h2>位置在着色器里算，不在 CPU 算</h2>
 * <p>这是 ② 的核心：数据在 GPU 里存的是<b>数值</b>不是屏幕坐标，
 * 于是滚动、缩放、自动量程、窗口尺寸变化全都是改 uniform，<b>零重传</b>。
 */
final class SeriesShaders {

    private SeriesShaders() {
    }

    /**
     * 折线族的顶点着色器。
     *
     * <p>每个实例是一个线段。两端 y 值由两个实例属性供给——它们是<b>同一个缓冲、
     * 偏移差 4 字节</b>（见 {@code LineSeriesRenderer} 的 VAO 配置）。
     */
    static final String LINE_VERTEX = """
            #version 330 core

            // —— 每顶点（divisor = 0）：单位四边形的四个角 ——
            layout(location = 0) in vec2 aCorner;   // (0/1, 0/1)

            // —— 每实例（divisor = 1）：线段两端的数值 ——
            layout(location = 1) in float aY0;
            layout(location = 2) in float aY1;

            // 绘图区（设备像素，原点左上）
            uniform vec4  uPlotRect;      // x, y, w, h
            uniform vec2  uViewport;      // 帧缓冲宽高
            // 数值窗口
            uniform vec2  uValueRange;    // min, max
            // 横轴：本次 draw 的第一个实例相对窗口左端的偏移（见 WindowRange）
            uniform float uFirstRelIndex;
            uniform float uPxPerSample;
            // 线宽（半宽，设备像素）
            uniform float uHalfWidth;
            // 拾取容差：绘制时为 0，ID pass 时用一个更大的值，
            // 让"点在线旁边几像素"也能命中。max() 让两份共用一个着色器。
            uniform float uPickTolerance;
            // 颜色（直通，非预乘）
            uniform vec4  uColor;
            // 拾取 ID
            uniform uint  uPickId;

            out vec4 vColor;
            flat out uint vId;

            void main() {
                vId = uPickId;
                vColor = uColor;

                // 任一端是 NaN 就把整个四边形退化到裁剪空间之外。
                //
                // 不能靠"NaN 自然传播"：NaN 位置的光栅化行为是未定义的——
                // 驱动可能丢掉它，也可能产生垃圾像素，而且不保证丢掉。
                if (isnan(aY0) || isnan(aY1)) {
                    gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                    return;
                }

                float uMin = uValueRange.x;
                float uMax = uValueRange.y;
                float span = uMax - uMin;

                // 数值 -> 屏幕 y。值越大越靠上，所以要翻。
                float fy0 = (aY0 - uMin) / span;
                float fy1 = (aY1 - uMin) / span;
                float sy0 = uPlotRect.y + (1.0 - fy0) * uPlotRect.w;
                float sy1 = uPlotRect.y + (1.0 - fy1) * uPlotRect.w;

                // 实例序号 -> 数据下标 -> 屏幕 x。
                // 全程用"相对窗口左端"的小数，避免绝对下标在长时间采集后溢出 int。
                float rel = uFirstRelIndex + float(gl_InstanceID);
                float sx0 = uPlotRect.x + rel * uPxPerSample;
                float sx1 = sx0 + uPxPerSample;

                vec2 p0 = vec2(sx0, sy0);
                vec2 p1 = vec2(sx1, sy1);

                // 沿屏幕空间法线把四边形撑成有粗细的线段。
                vec2 delta = p1 - p0;
                float len = length(delta);
                vec2 dir = len > 0.0 ? delta / len : vec2(1.0, 0.0);
                vec2 nrm = vec2(-dir.y, dir.x);

                float halfWidth = max(uHalfWidth, uPickTolerance);
                vec2 base = aCorner.x < 0.5 ? p0 : p1;
                vec2 offset = nrm * halfWidth * (aCorner.y < 0.5 ? -1.0 : 1.0);
                vec2 p = base + offset;

                // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下。
                vec2 ndc = vec2(p.x / uViewport.x * 2.0 - 1.0,
                                1.0 - p.y / uViewport.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;

    /**
     * 绘制用的片段着色器。
     *
     * <p><strong>颜色是直通（非预乘）的，最后一步必须做预乘。</strong>
     * 半透明线段的端点会互相重叠，不预乘就会出现二次混合的暗缝——
     * 与 {@code RenderBatch} 里"混合因子顺序不能调换"是同一类问题。
     */
    static final String LINE_FRAGMENT = """
            #version 330 core
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor.rgb * vColor.a, vColor.a);
            }
            """;

    /**
     * 拾取用的片段着色器。
     *
     * <p>直接写出 ID，不看颜色、不看 alpha。{@code flat} 不能省——ID 是整数，
     * 跨三角形插值出来的中间值对应不存在的对象。
     */
    static final String PICK_FRAGMENT = """
            #version 330 core
            flat in uint vId;
            out uint fragId;
            void main() {
                fragId = vId;
            }
            """;
}
```

- [ ] **Step 2: 编译**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`（着色器此时还没被编译成 GL 程序，语法错误要等 Task 10 才暴露）。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesShaders.java
git commit -F - <<'EOF'
feat(chartrender): 折线族的 GLSL

位置在着色器里算，不在 CPU 算——这是 ② 的核心：数据在 GPU 里存的是
数值不是屏幕坐标，于是滚动/缩放/自动量程/窗口尺寸变化全是改 uniform。

NaN 显式退化到裁剪空间之外，不靠"NaN 自然传播"：NaN 位置的光栅化
行为是未定义的，驱动不保证丢掉它。

uPickTolerance 让绘制与 ID pass 共用一份顶点源码（绘制时传 0）。
EOF
```

---

## Task 10: `GLRenderContext` + `LineSeriesRenderer`

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/GLRenderContext.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/LineSeriesRenderer.java`

- [ ] **Step 1: 定义 `GLRenderContext`（`RenderContext` 的子接口 + 实现）**

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.RenderContext;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;

/**
 * 渲染一个系列时需要的全部东西。
 *
 * <p><strong>它必须是 {@link RenderContext} 的子接口，不能在 {@code RenderContext}
 * 原地加成员。</strong>{@code ChartPackageIsolationTest} 断言后者是空接口
 * （0 方法 / 0 字段 / 0 嵌套类型）——在原地加东西等于把 ② 的概念漏进 ①。
 *
 * <p>{@code SeriesRenderer} 的实现<b>第一行就向下转型</b>取它。
 */
public interface GLRenderContext extends RenderContext {

    /** GL 抽象层。 */
    GLAbstraction gl();

    /** 绘制用的着色器程序。 */
    ShaderProgram lineShader();

    /** 拾取用的着色器程序。 */
    ShaderProgram pickShader();

    /** 绘图区与映射。 */
    ChartRenderLayout layout();

    /** 帧缓冲尺寸（设备像素）。 */
    int viewportWidth();

    int viewportHeight();

    /**
     * 当前系列的 GPU 常驻缓冲。
     *
     * <p>渲染器<b>不该自己持有它</b>——缓冲归 {@code ChartRenderer} 管，
     * 渲染器是无状态的纯函数（见 {@code SeriesRenderer} 的类文档）。
     */
    SeriesBuffer bufferFor(Series series);

    /**
     * 当前系列的拾取 ID；<b>0 表示不参与拾取</b>（0 也是"什么都没命中"的返回值）。
     *
     * <p>ID 由 {@code ChartRenderer} 从 <b>{@code Gc} 的注册表</b>里取——
     * 不能另起一套，否则两套号会撞车，点击落在错误的对象上而画面正常。
     */
    int pickId();

    /**
     * 在拾取缓冲上执行一段绘制。转发给 {@code RenderBatch.withPickPass}——
     * 拾取缓冲与它那两个"本帧是否已清空/是否有效"的标志都归 {@code RenderBatch} 管，
     * 图表路径只是借用。
     *
     * @param body 要执行的绘制
     */
    void withPickPass(Runnable body);
}
```

- [ ] **Step 2: 写 `LineSeriesRenderer`**

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.ChartData;
import com.bingbaihanji.jfgl.chart.ChartType;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.chart.SeriesRenderer;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.util.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;

/**
 * 折线族渲染器：{@link ChartType#LINE} 与 {@link ChartType#LINE_AND_MARKERS}。
 *
 * <p>每个线段一个实例，几何在顶点着色器里生成。CPU 每帧只做两件事：
 * 把新增的点传上去、算一下可见窗口对应的实例区间。
 */
final class LineSeriesRenderer implements SeriesRenderer {

    /** 单位四边形的四个角，按 triangle strip 顺序。divisor = 0，所有实例共享。 */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    private final GLAbstraction gl;
    private final int vao;
    private final int cornerVbo;

    LineSeriesRenderer(GLAbstraction gl) {
        this.gl = gl;
        this.vao = gl.createVao();
        this.cornerVbo = gl.createVbo();

        gl.bindVao(vao);
        gl.bindVbo(cornerVbo);
        ByteBuffer corners = ByteBuffer.allocateDirect(CORNERS.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float c : CORNERS) {
            corners.putFloat(c);
        }
        corners.flip();
        gl.uploadVboBytes(corners);

        // location 0：单位四边形，每顶点取一次
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 配置数据侧的属性指针。
     *
     * <p><strong>同一个 VBO 绑两次、只差 4 个字节的偏移</strong>——这是"每点只存一次
     * 却能让每个实例拿到两端"的关键。偏移 0 拿 {@code y[k]}，偏移 4 拿 {@code y[k+1]}。
     *
     * <p>调用方必须已经绑定本类的 VAO 与系列的 VBO。
     */
    private void configureDataAttributes(int seriesVbo) {
        gl.bindVbo(seriesVbo);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        gl.setVertexAttribDivisor(1, 1);

        glVertexAttribPointer(2, 1, GL_FLOAT, false, Float.BYTES, Float.BYTES);
        glEnableVertexAttribArray(2);
        gl.setVertexAttribDivisor(2, 1);

        gl.bindVbo(0);
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;
        ChartType type = series.type();
        if (!type.connectsSamples() && !type.drawsMarkers()) {
            throw new IllegalArgumentException(
                    "LineSeriesRenderer 不支持图型 " + type + "。"
                            + "明确报错而不是静默不画：画面里少一条曲线，"
                            + "与"这条曲线没数据"在视觉上完全一样。");
        }

        // 缓冲归 ChartRenderer 管，渲染器自己不持有状态
        // （SeriesRenderer 的类文档要求它是纯函数）。
        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        // 可见窗口就是 x 轴的窗口（x 轴的单位是数据下标）
        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();
        List<WindowRange.Segment> segments =
                WindowRange.compute(windowStart, windowEnd, data.itemCount(), buffer.capacity());
        if (segments.isEmpty()) {
            return;
        }

        ChartRenderLayout layout = c.layout();
        gl.bindVao(vao);
        configureDataAttributes(buffer.vboId());

        ShaderProgram shader = c.lineShader();
        shader.use();
        shader.setUniform("uPlotRect", layout.plotRect().x, layout.plotRect().y,
                layout.plotRect().width, layout.plotRect().height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample",
                (float) (layout.plotRect().width / (windowEnd - windowStart)));
        shader.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
        // 绘制时容差为 0，max() 取到的就是真实线宽
        shader.setUniform("uPickTolerance", 0f);
        // Series.color() 返回 ARGB 整数，按 0xAARRGGBB 拆分量。
        // 顶点格式的预乘是 VertexFormat 的事；这里是另一套顶点布局，没有那个约定。
        int argb = series.color();
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        shader.setUniform("uPickId", 0);

        double windowFloor = Math.floor(windowStart);
        for (WindowRange.Segment seg : segments) {
            setScissorTo(layout.plotRect(), c.viewportHeight());
            shader.setUniform("uFirstRelIndex",
                    (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
            gl.drawArraysInstancedBaseInstance(
                    GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
        }

        shader.unuse();
        gl.bindVao(0);
    }

    private void setScissorTo(Rect plot, int viewportHeight) {
        // DrawCommand 的约定：scissorY 是矩形上边缘。glScissor 的原点在左下、y 向上。
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        org.lwjgl.opengl.GL11.glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }
}
```

> **`GLRenderContext` 要加一个 `bufferFor`**（上面的代码已经按它写了）：
> ```java
>     /** 取（必要时创建）某个系列的 GPU 常驻缓冲。缓冲归 ChartRenderer 管。 */
>     SeriesBuffer bufferFor(Series series);
> ```
>
> **`Series` 的实际签名已核对**（`Series.java`）：`color()` 返回 ARGB `int`、
> `lineWidth()` 返回 `float`、`markerSize()` 返回 `float`，另有 `data()` / `type()` / `name()`。
>
> **注意 `Series` 上<u>没有</u> `pickId()`**——那是刻意的：拾取 ID 是**渲染层**的概念，
> 不属于图表框架。见 Task 13 的处理方式。

- [ ] **Step 3: 编译**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`。

- [ ] **Step 4: 跑全量测试**

Run: `mvn -o test`
Expected: 261 + 新增的（WindowRange 10 + ChartRenderLayout 6 + SeriesUploadPlan 8 + SeriesBuffer 10）= **295**，0 失败，2 跳过。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/GLRenderContext.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/LineSeriesRenderer.java
git commit -F - <<'EOF'
feat(chartrender): GLRenderContext 与折线族渲染器

同一个 VBO 绑两次、只差 4 个字节的偏移，于是每个实例同时拿到 y[k] 与
y[k+1]，而缓冲里每个点只存一次——这是 4 字节/点的关键。

GLRenderContext 是 RenderContext 的子接口。不能在 RenderContext 原地
加成员：ChartPackageIsolationTest 断言后者是空接口（0 方法/0 字段/
0 嵌套类型），在原地加等于把 ② 的概念漏进 ①。

不支持的图型明确抛异常，不静默不画。
EOF
```

---

## Task 11: `ChartRenderer` + `Gc.charts`

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderer.java`
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt`

- [ ] **Step 1: 写 `ChartRenderer`**

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.Chart;
import com.bingbaihanji.jfgl.chart.ChartType;
import com.bingbaihanji.jfgl.chart.Layer;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.util.Disposable;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 图表绘制入口。
 *
 * <h2>它只做装配，不做几何</h2>
 * <p>每个系列交给对应的 {@link com.bingbaihanji.jfgl.chart.SeriesRenderer}，
 * 本类负责把"这个系列该用哪个渲染器"和"它的 GPU 缓冲在哪"接起来。
 *
 * <h2>它与 {@code Gc} 的关系</h2>
 * <p>由 {@code Gc.charts} 懒创建，与 {@code RenderBatch} 同生命周期。
 * GL 资源的所有权链条是 {@code FXGLTransfer.onDispose → RenderBatch.dispose()}，
 * <strong>本类必须挂进这条链</strong>，否则窗口关闭时泄漏。
 *
 * <h2>不支持的图型</h2>
 * <p>热力图与瀑布图（{@code polylineFamily() == false}）本期没有渲染器。
 * 遇到它们<b>明确抛异常</b>——静默不画是本项目最典型的静默错误输出。
 */
public final class ChartRenderer implements Disposable {

    private final GLAbstraction gl;
    private final ShaderProgram lineShader;
    private final ShaderProgram pickShader;

    /** 每个系列的 GPU 常驻缓冲。用 IdentityHashMap：Series 没有值语义。 */
    private final Map<Series, SeriesBuffer> buffers = new IdentityHashMap<>();

    /**
     * 拾取 ID 注册表。
     *
     * <p><strong>必须是 {@code Gc} 的那一个，不能自己新建。</strong>
     * 拾取缓冲里存的是一个 ID 空间：两套注册表各自从 1 发号的话，
     * "1 号"既可能是一条曲线也可能是一个按钮——点击会落在错误的对象上，
     * 而画面完全正常。
     */
    private final PickRegistry pickRegistry;

    /** 系列 → 拾取 ID。同一个 Series 只注册一次。 */
    private final Map<Series, Integer> pickIds = new IdentityHashMap<>();

    /** 借用 {@code RenderBatch} 的 ID pass 脚手架（见它的 withPickPass 说明）。 */
    private final Consumer<Runnable> pickPass;

    /** 折线族渲染器，全局一个（它自己不持有数据）。 */
    private final LineSeriesRenderer lineRenderer;

    private final ScatterSeriesRenderer scatterRenderer;

    private boolean disposed = false;

    public ChartRenderer(GLAbstraction gl, PickRegistry pickRegistry,
                         Consumer<Runnable> pickPass) {
        this.gl = gl;
        this.pickRegistry = pickRegistry;
        this.pickPass = pickPass;
        this.lineShader = gl.createShader(SeriesShaders.LINE_VERTEX, SeriesShaders.LINE_FRAGMENT);
        this.pickShader = gl.createShader(SeriesShaders.LINE_VERTEX, SeriesShaders.PICK_FRAGMENT);
        this.lineRenderer = new LineSeriesRenderer(gl);
        this.scatterRenderer = new ScatterSeriesRenderer(gl);
    }

    /**
     * 画一张图。
     *
     * <p>调用方应当先用 {@code Gc} 画好网格、调用 {@code gc.flush()}，
     * 再调本方法，最后画刻度文字——这样 z 序是"网格 → 数据 → 标注"。
     *
     * @param chart    图表
     * @param plotRect 绘图区（数据区域）矩形，设备像素
     * @param viewportWidth  帧缓冲宽度
     * @param viewportHeight 帧缓冲高度
     */
    public void draw(Chart chart, Rect plotRect, int viewportWidth, int viewportHeight) {
        if (disposed) {
            throw new IllegalStateException("ChartRenderer 已释放");
        }
        // 注意：Chart.axes() 返回的是 **List<Axis>**，不是数组。
        // 原稿这里直接写成 `Axis[] axes = chart.axes();` 是编译不过的。
        // SeriesRenderer.render 的签名要的是 Axis[]，所以这里转一次。
        Axis[] axes = chart.axes().toArray(new Axis[0]);
        ChartRenderLayout layout = new ChartRenderLayout(plotRect, axes[0], axes[1]);
        GLRenderContextImpl ctx = new GLRenderContextImpl(
                gl, lineShader, pickShader, layout, viewportWidth, viewportHeight, pickPass);

        for (Layer layer : chart.layers()) {
            for (Series series : layer.series()) {
                SeriesRenderer renderer = rendererFor(series.type());
                ctx.setCurrentBuffer(buffers.computeIfAbsent(series,
                        s -> new SeriesBuffer(gl, s.data())));
                // 拾取 ID 在**渲染层**分配，不在图表框架里——所以 Series 上没有 pickId()。
                // 注册的是 Series 对象本身：命中之后 PickHit.payload() 直接就是那个 Series。
                ctx.setPickId(pickIds.computeIfAbsent(series, pickRegistry::register));
                renderer.render(ctx, series.data(), series, axes);
            }
        }
    }

    private SeriesRenderer rendererFor(ChartType type) {
        if (!type.polylineFamily()) {
            throw new UnsupportedOperationException(
                    "图型 " + type + " 本期还没有渲染器（热力图与瀑布图不在折线族里）。"
                            + "明确报错而不是静默不画：画面里少一张图，"
                            + "与"这张图没数据"在视觉上完全一样。");
        }
        return type.drawsMarkers() && !type.connectsSamples() ? scatterRenderer : lineRenderer;
    }

    /** 取（必要时创建）某个系列的缓冲。 */
    SeriesBuffer bufferFor(Series series) {
        return buffers.computeIfAbsent(series,
                s -> new SeriesBuffer(gl, s.data()));
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        buffers.values().forEach(SeriesBuffer::dispose);
        buffers.clear();
        // 注册表对 payload 是强引用：不注销的话，被移除的 Series 会一直被引用着。
        // 注册发生在数据变化时而非每帧，注销也该是同一个时机——这里只是兜底。
        pickIds.values().forEach(id -> pickRegistry.unregister(id));
        pickIds.clear();
        lineRenderer.dispose();
        scatterRenderer.dispose();
        lineShader.dispose();
        pickShader.dispose();
        disposed = true;
    }
}
```

- [ ] **Step 2: 写 `GLRenderContextImpl`**

创建 `src/main/java/com/bingbaihanji/jfgl/chartrender/GLRenderContextImpl.java`：

```java
package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;

/** {@link GLRenderContext} 的实现。由 {@link ChartRenderer} 每个系列换一次当前系列。 */
final class GLRenderContextImpl implements GLRenderContext {

    private final GLAbstraction gl;
    private final ShaderProgram lineShader;
    private final ShaderProgram pickShader;
    private final ChartRenderLayout layout;
    private final int viewportWidth;
    private final int viewportHeight;
    private final Consumer<Runnable> pickPass;

    /** 当前正在渲染的系列的缓冲，由 ChartRenderer 每换一个系列注入一次。 */
    private SeriesBuffer currentBuffer;

    /** 当前系列的拾取 ID；0 表示这个系列不参与拾取。 */
    private int pickId;

    GLRenderContextImpl(GLAbstraction gl, ShaderProgram lineShader, ShaderProgram pickShader,
                        ChartRenderLayout layout, int viewportWidth, int viewportHeight,
                        Consumer<Runnable> pickPass) {
        this.gl = gl;
        this.lineShader = lineShader;
        this.pickShader = pickShader;
        this.layout = layout;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.pickPass = pickPass;
    }

    void setCurrentBuffer(SeriesBuffer buffer) {
        this.currentBuffer = buffer;
    }

    void setPickId(int pickId) {
        this.pickId = pickId;
    }

    @Override
    public GLAbstraction gl() {
        return gl;
    }

    @Override
    public ShaderProgram lineShader() {
        return lineShader;
    }

    @Override
    public ShaderProgram pickShader() {
        return pickShader;
    }

    @Override
    public ChartRenderLayout layout() {
        return layout;
    }

    @Override
    public int viewportWidth() {
        return viewportWidth;
    }

    @Override
    public int viewportHeight() {
        return viewportHeight;
    }

    @Override
    public SeriesBuffer bufferFor(Series series) {
        return currentBuffer;
    }

    @Override
    public int pickId() {
        return pickId;
    }

    @Override
    public void withPickPass(Runnable body) {
        pickPass.accept(body);
    }
}
```

> **`bufferFor` 的写法要按实际结构简化**。上面为了"渲染器不要自己存缓冲"绕了一圈，实现时**直接让 `ChartRenderer` 在循环里 `ctx.setCurrentBuffer(buffers.computeIfAbsent(...))`，`bufferFor` 返回它**即可。**目标是：渲染器无状态，缓冲归 `ChartRenderer` 管。**

- [ ] **Step 3: 在 `Gc` 里加 `charts`**

在 `Gc.kt` 的 `pickRegistry` 声明附近加：

```kotlin
    /**
     * 图表绘制入口，懒创建。
     *
     * <p>第一次访问时才建（它要编译着色器、建 VAO，不该让不用图表的应用白付这份开销）。
     * 生命周期与 [RenderBatch] 一致，由 [RenderBatch.dispose] 负责释放。
     */
    val charts: ChartRenderer by lazy {
        ChartRenderer(batch.glAbstraction(), pickRegistry, batch::withPickPass)
    }
```

**这需要 `RenderBatch` 暴露它持有的 `GLAbstraction`。** 在 `RenderBatch.java` 里加：

```java
    /**
     * 返回本批处理使用的 GL 抽象层。
     *
     * <p>图表后端需要它来建自己的着色器与 VAO。它是无状态的转发层，共享是安全的。
     *
     * @return GL 抽象层
     */
    public GLAbstraction glAbstraction() {
        return gl;
    }
```

并在 `RenderBatch.dispose()` 里加上 `charts` 的释放——**但 `RenderBatch` 不认识 `ChartRenderer`（它在上层包）**。正确做法是**在 `Gc` 里不持有释放职责**，改由 `FXGLTransfer` 的 `onDispose` 调 `gc.disposeCharts()`。**先去读 `FXGLTransfer.kt` 的 `onDispose` 现状**，按它实际的释放顺序接入。**这是本任务唯一需要动 `FXGLTransfer` 的地方，别扩大范围。**

- [ ] **Step 4: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: `BUILD SUCCESS` / 全部通过。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderer.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/GLRenderContextImpl.java \
        src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java \
        src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt \
        src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt
git commit -F - <<'EOF'
feat(chartrender): ChartRenderer 入口与 Gc.charts

ChartRenderer 只做装配：把"这个系列该用哪个渲染器"和"它的 GPU 缓冲在哪"
接起来。渲染器本身无状态（SeriesRenderer 的类文档要求它是纯函数）。

Gc.charts 懒创建——它要编译着色器、建 VAO，不该让不用图表的应用白付
这份开销。释放接进 FXGLTransfer.onDispose 那条链，否则窗口关闭时泄漏。
EOF
```

---

## Task 12: `ChartVerifier` —— 像素校验器

**Files:**
- Create: `src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt`

- [ ] **Step 1: 照 `TextVerifier` 的形状建骨架**

**先完整读一遍 `src/main/kotlin/com/bingbaihanji/jfgl/example/TextVerifier.kt`**，特别是：
- 它怎么起窗口、怎么在 `onRender` 里跑断言
- 它怎么回读像素
- 它怎么保证**退出码**（三个校验器都修过一个 bug：断言抛异常让 GL 线程死亡，于是 `exitProcess` 从未运行，打印了 FAIL 却报告退出码 0）

骨架必须**从一开始就按修好的形状写**：

```kotlin
package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.chart.*
import com.bingbaihanji.jfgl.dsl.jfgl
import com.bingbaihanji.jfgl.util.Rect
import javafx.application.Platform
import org.lwjgl.opengl.GL11.*
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * 图表绘制后端的端到端**像素级校验器**。
 *
 * <h2>核心验收点：每帧只上传新增的点</h2>
 *
 * <p>整个 ② 的性能主张是"数据常驻 GPU，每帧只上传新增的几十个点"。
 * <b>画面在"增量上传"与"每帧全量重传"这两种实现下完全一样</b>——
 * 只看像素的断言一条都区分不出来。所以这里断言的是
 * [SeriesBuffer.uploadedBytesThisFrame]：滚动 100 帧、每帧新增 10 个点之后，
 * 它必须恒等于 40，而不是整个窗口的字节数。
 *
 * <p>这与 TextVerifier 里那条"24px 与 192px 的过渡带宽度都是 1px"是同类：
 * <b>断言的量就是那件事本身</b>。
 *
 * <h2>场景必须会变</h2>
 *
 * <p>PickVerifier 出过一次真实盲区：24 条断言全绿，却漏掉了一个真缺陷，
 * 因为它的场景每帧完全相同、陈旧 ID 与新鲜 ID 恰好一致。
 * 因此本校验器的场景包含：曲线滚动了若干帧、某条曲线中途消失、数据里新插入 NaN。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600
private const val BG = 0x33

fun main() {
    Application.launch(ChartVerifierApp::class.java)
}
```

- [ ] **Step 2: 写断言**

按下面的清单实现，**每条都要落在具体像素坐标或具体数字上**：

| # | 断言 | 观测的量 |
|---|---|---|
| 1 | 映射正确：一条已知斜坡画出来后，绘图区中若干**具体像素**的颜色与 `ChartRenderLayout.screenX/screenY` 算出的位置一致 | 具体坐标的颜色 |
| 2 | `clipRect` 之外没有像素 | 绘图区外的像素是背景色 |
| 3 | z 序：网格线画在数据之下，重叠处是数据颜色 | 重叠点的颜色 |
| 4 | 缺口断开：数据里插 NaN，缺口那一段是背景色 | 缺口段的像素 |
| 5 | **增量上传：滚动 100 帧、每帧 10 点，`uploadedBytesThisFrame()` 恒等于 40** | **字节数** |
| 6 | 曲线中途消失后，那个位置是背景色（不是上一帧的残留） | 消失后的像素 |
| 7 | 拾取命中：点在线上的像素拾取到该系列 ID | 读回的 ID |
| 8 | 拾取容差：线画 1px，点在线旁 3px 仍命中 | 读回的 ID |
| 9 | 拾取不越界：点在线旁 20px 不命中 | 读回的 ID |
| 10 | 系列级 ID：同一曲线不同位置拾取到同一个 ID；两条曲线互不串号 | 读回的 ID |
| 11 | `pickId == 0` 的系列不可拾取 | 读回的 ID |
| **12** | **`baseInstance` 生效：环已写满且窗口跨过环绕点时，画面是连续的线，不是乱线** | **跨环绕处的像素** |

> **第 12 条是质量复核补的，而且它指出了一件要紧的事**：原来的十条里
> **没有任何一条会在 `baseInstance` 传错时失败**——如果测试场景从不跨环绕点，
> 那么 `baseInstance` 恒为 0，传对传错都一样，十条断言全绿。
>
> 而 `drawArraysInstancedBaseInstance` 存在的**全部理由**就是那个偏移
> （实例属性按 `gl_InstanceID` 取，而它每次从 0 开始）。所以这一条是把
> "实现了" 与 "生效了" 区分开的**唯一**场景——与 `TextVerifier` 里那条
> "24px 与 192px 的过渡带宽度都是 1px"同类：**断言的场景必须能让那个量真的起作用。**
>
> 构造方法：把环容量设小（比如 8）、写满两圈以上，再让 x 轴窗口
> **左端落在环绕点之后**（例如 `windowStart = 12.0`、`writeIndex = 20`），
> 这样 `WindowRange` 会给出两段。断言绘图区左半部分的像素构成一条**单调的线**
> 而不是错乱的散点。**变异验证**：把 `baseInstance` 硬编码成 0，这一条必须失败。

- [ ] **Step 3: 保证退出码**

```kotlin
    // 三个已有校验器都修过这个 bug：断言抛异常会让 GL 线程死亡，
    // 于是 exitProcess 从未运行——打印了 FAIL 却报告退出码 0。
    // 用一个 try/finally 把退出码钉死。
    private fun finish(failures: Int) {
        try {
            Platform.exit()
        } finally {
            exitProcess(if (failures == 0) 0 else 1)
        }
    }
```

**另外给 `onFrame` 也包上 try/catch。** 三个既有校验器目前都是这个形状：只有 `onRender` 里的
`verifyOnce` 有 try/catch，**`onFrame` 那条路径没有**。后果是——`onFrame` 里抛出的异常会让
GL 线程死掉、JVM 以 **0** 正常退出，**校验器报了一个静默的绿**。

Task 3 给 `Gc.flush()` 加的那条 `frameActive` 检查就落在 `onFrame` 上（虽然那条检查本身
已按 Task 3 的复核删掉了 `save/restore` 那一半，但**将来任何在 `onFrame` 里的抛出点都吃这个坑**）。
这正是 `verifyAll` 的文档在防的那件事，**在 Task 12 建骨架时顺手把 `onFrame` 也补上**。

- [ ] **Step 4: 跑校验器**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.ChartVerifierKt"
```
Expected: 全部通过，退出码 0。

- [ ] **Step 5: 变异验证 —— 这是本任务最重要的步骤**

| 变异 | 期望失败的断言 |
|---|---|
| `SeriesBuffer.uploadNewSamples()` 改成每次从 0 全量重传 | **#5** |
| 着色器的 y 翻转去掉（`1.0 - fy` 改成 `fy`） | #1 |
| 去掉 `isnan` 退化分支 | #4 |
| 拾取容差改成 0 | #8 |
| 去掉 series 消失时的清理 | #6 |

**每条变异都要跑一遍确认它确实被抓住。任何一条存活都要停下来报告**，并对它做对照实验证明变异是活的。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt
git commit -F - <<'EOF'
test(chart): ChartVerifier 像素校验器

核心断言是"每帧上传的字节数"——画面在增量上传与全量重传两种实现下
完全一样，只看像素的断言一条都区分不出来，所以断言的量就是那个字节数。

场景会变：曲线滚动了若干帧、某条曲线中途消失、数据里新插入 NaN。
PickVerifier 出过一次真实盲区，24 条断言全绿却漏掉一个真缺陷，
因为它的场景每帧完全相同。

退出码从一开始就按修好的形状写：断言抛异常会让 GL 线程死亡，
exitProcess 从未运行——打印了 FAIL 却报告退出码 0。
EOF
```

---

## Task 13: 接通拾取

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/chartrender/LineSeriesRenderer.java`
- Modify: `src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderer.java`
- Modify: `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java`（只改一句 Javadoc，见下）

> **⚠️ Task 4 复核提出、必须在首次使用 `withPickPass` 时补上的一条**：
>
> `RenderBatch.withPickPass` 的 Javadoc 只说"调用方必须自己设置 scissor"，
> **没说 `GL_SCISSOR_TEST` 必须已启用**。`glScissor` 只在裁剪测试开启时生效。
>
> 今天唯一调用者 `drawPickPass` 是从 `submit()` 进来的，而 `submit()` 里 `glEnable(GL_SCISSOR_TEST)`，
> 所以没事。但**图表后端是第一个在 `submit()` 之外用它的人**——若忘了开，
> scissor 会**静默失效**：被裁掉的部分变成可拾取，**画面完全正常，只有点击落错对象**。
> 这正是本仓库最在意的那类缺陷，也恰是这个方法存在的理由。
>
> **补一句 Javadoc**（不要改成由 `withPickPass` 自己开——它无法知道调用方想要哪个裁剪矩形，
> 开了裁剪测试却用着一个陈旧的矩形只会更糟）：
>
> ```java
>      * <p><strong>调用方必须自己设置 scissor，并确保 {@code GL_SCISSOR_TEST} 已启用。</strong>
>      * {@code glScissor} 只在裁剪测试开启时生效——忘了开的表现是裁剪静默失效：
>      * 被裁掉的部分变成可拾取，而画面完全正常，只有点击落在错误的对象上。
>      * 本方法不替调用方开关裁剪测试，因为它无法知道调用方想要哪个裁剪矩形。
>      * 本批处理自己的 {@code submit()} 路径在进入绘制循环前已经开好了。</p>
> ```
>
> **并且 Task 13 的实现里，图表路径必须自己 `glEnable(GL_SCISSOR_TEST)`**（它的绘制不在
> `submit()` 里面），用完恢复。

- [ ] **Step 1: 在 `LineSeriesRenderer` 里加 ID pass**

在 `render` 方法末尾（`gl.bindVao(0)` 之前）插入：

```java
        // ID pass：同一份 VAO、同一批实例，只换程序。
        // 拾取 ID 走 uniform 而不是顶点属性——图表的数据布局里没有 id 字段，
        // 而且按系列发号意味着发号成本与点数无关（一条百万点的曲线只注册一个 ID）。
        int pickId = c.pickId();
        if (pickId != 0) {
            c.pickShader().use();
            c.pickShader().setUniform("uPlotRect", layout.plotRect().x, layout.plotRect().y,
                    layout.plotRect().width, layout.plotRect().height);
            c.pickShader().setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
            c.pickShader().setUniform("uValueRange", layout.yMin(), layout.yMax());
            c.pickShader().setUniform("uPxPerSample",
                    (float) (layout.plotRect().width / (windowEnd - windowStart)));
            c.pickShader().setUniform("uHalfWidth", series.lineWidth() * 0.5f);
            // 拾取容差：线只有 1~2px 宽，要求用户精确点中不合理。
            // 这是刻意的，与"全透明图元仍可拾取""文本可拾取范围比墨迹大一圈"同类。
            c.pickShader().setUniform("uPickTolerance", PICK_TOLERANCE_PX);
            c.pickShader().setUniform("uPickId", pickId);

            c.withPickPass {
                for (WindowRange.Segment seg : segments) {
                    setScissorTo(layout.plotRect(), c.viewportHeight());
                    c.pickShader().setUniform("uFirstRelIndex",
                            (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
                    gl.drawArraysInstancedBaseInstance(
                            GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
                }
            }
            c.pickShader().unuse();
        }
```

并在类里加常量：

```java
    /**
     * 拾取容差（半宽，设备像素）。
     *
     * <p>画出来的线只有 1~2px 宽，要求用户精确点中是不合理的。
     * <strong>这是刻意行为，不是 bug</strong>——与"全透明图元仍可拾取"
     * "文本的可拾取范围比墨迹大一圈"同类，有测试钉着。
     * 它只影响 ID pass，<strong>不影响画面</strong>。
     */
    private static final float PICK_TOLERANCE_PX = 4f;
```

- [ ] **Step 2: 确认 `withPickPass` 的接线**

`c.withPickPass { ... }` 用的是 **Task 10 已经在 `GLRenderContext` 里声明、Task 11 已经接好的**那条转发链：

```
LineSeriesRenderer  ->  GLRenderContext.withPickPass
                    ->  ChartRenderer 传进来的 Consumer<Runnable>
                    ->  RenderBatch.withPickPass        (Task 4)
```

`Gc.charts` 里的构造是 `ChartRenderer(batch.glAbstraction(), pickRegistry, batch::withPickPass)`。

**注意必须用 `Gc` 的 `pickRegistry`**，不要在 `ChartRenderer` 里新建一个：
拾取缓冲里存的是一个 ID 空间，两套注册表各自从 1 发号的话，"1 号"既可能是一条曲线
也可能是一个按钮——点击落在错误的对象上，而画面完全正常。

- [ ] **Step 3: 跑 `ChartVerifier` 的拾取断言**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.ChartVerifierKt"
```
Expected: 断言 #7–#11 通过，退出码 0。

- [ ] **Step 4: 再跑 `PickVerifier` 确认没污染它**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```
Expected: 退出码 0。**特别是 `pickPassCount` 相关的断言**——图表路径绝不能改那个计数器。

- [ ] **Step 5: 变异验证**

| 变异 | 期望失败的断言 |
|---|---|
| `uPickTolerance` 改成 0 | ChartVerifier #8 |
| 去掉 `pickId != 0` 判断 | #11 |
| 去掉 scissor 设置 | #2（拾取越界）/ #3 |
| 在图表路径里 `pickPassCount++` | **PickVerifier** 的相关断言 |

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/LineSeriesRenderer.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/ChartRenderer.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/GLRenderContext.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/GLRenderContextImpl.java \
        src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt
git commit -F - <<'EOF'
feat(chartrender): 接通拾取

同一份 VAO、同一批实例，只换程序。拾取 ID 走 uniform 而不是顶点属性：
图表的数据布局里没有 id 字段，而且按系列发号意味着发号成本与点数无关
——一条百万点的曲线只注册一个 ID。

容差 4px 半宽（线画 1px）。这是刻意的：与"全透明图元仍可拾取"、
"文本可拾取范围比墨迹大一圈"同类，有测试钉着。它只影响 ID pass。

走 RenderBatch.withPickPass 复用它的清空/绑定/恢复纪律，
但绝不碰 pickPassCount——那是 PickVerifier 的计数器。
EOF
```

---

## Task 14: `ScatterSeriesRenderer`

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chartrender/ScatterSeriesRenderer.java`

- [ ] **Step 1: 写实现**

散点与折线共用同一套实例机制，区别只有三处：实例数是**点数**而不是线段数、每个实例只需要**一个** y 值、几何是**居中**的小四边形而不是线段。

**不要照抄折线的 `WindowRange.compute`**——它按线段算，最后一个线段需要右端存在；散点每个点都要画，没有这个约束。**先给 `WindowRange` 加一个点数版本，连同它的测试。**

- [ ] **Step 1: 给 `WindowRange` 加点数版本（含测试）**

在 `WindowRangeTest` 里加：

```java
    @Test
    void 点数版本不排除最后一个点() {
        // 折线版本要 writeIndex - 2（线段需要右端），点数版本到 writeIndex - 1。
        // 这个差别就是两个方法的全部区别，必须专门钉住。
        List<WindowRange.Segment> segs = WindowRange.computePoints(0.0, 100.0, 5L, CAP);
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(5, total, "散点要把 5 个点都画出来，不是 4 个");
    }

    @Test
    void 点数版本跨环绕也切两段() {
        List<WindowRange.Segment> segs = WindowRange.computePoints(6.0, 12.0, 12L, CAP);
        assertEquals(2, segs.size());
        int total = segs.stream().mapToInt(WindowRange.Segment::instanceCount).sum();
        assertEquals(6, total, "可见点是 [6, 12) 与有效范围 [4, 12) 的交，共 6 个");
    }
```

在 `WindowRange` 里把 `compute` 与新增的 `computePoints` 都转给一个私有方法：

```java
    /**
     * 计算这一帧要绘制哪些<b>线段</b>实例。
     *
     * <p>最后一个可画的线段需要它的右端样本已经采到，因此范围上界是 {@code writeIndex - 1}。
     */
    public static List<Segment> compute(double windowStart, double windowEnd,
                                        long writeIndex, int capacity) {
        return compute(windowStart, windowEnd, writeIndex, capacity, true);
    }

    /**
     * 计算这一帧要绘制哪些<b>点</b>实例（散点用）。
     *
     * <p>与 {@link #compute} 的唯一区别：每个点独立可画，不需要右端，
     * 所以范围上界是 {@code writeIndex} 而不是 {@code writeIndex - 1}。
     */
    public static List<Segment> computePoints(double windowStart, double windowEnd,
                                              long writeIndex, int capacity) {
        return compute(windowStart, windowEnd, writeIndex, capacity, false);
    }

    private static List<Segment> compute(double windowStart, double windowEnd,
                                         long writeIndex, int capacity, boolean segmentsOnly) {
        // …（原 compute 的实现，只把下面这一行改成条件式）
        long upperBound = segmentsOnly ? writeIndex - 1 : writeIndex;
        long hi = Math.min((long) Math.ceil(windowEnd), upperBound);
        // …其余不变
    }
```

- [ ] **Step 2: 加着色器**

在 `SeriesShaders` 里加 `SCATTER_VERTEX`。与 `LINE_VERTEX` 的差别：只有一个 `aY` 属性、几何以点为中心、用 `uMarkerSize` 而不是 `uHalfWidth`：

```glsl
    static final String SCATTER_VERTEX = """
            #version 330 core

            layout(location = 0) in vec2  aCorner;   // 每顶点：(0/1, 0/1)
            layout(location = 1) in float aY;        // 每实例：该点的数值

            uniform vec4  uPlotRect;      // x, y, w, h
            uniform vec2  uViewport;
            uniform vec2  uValueRange;    // min, max
            uniform float uFirstRelIndex;
            uniform float uPxPerSample;
            uniform float uMarkerSize;    // 边长（设备像素）
            uniform float uPickTolerance;
            uniform vec4  uColor;
            uniform uint  uPickId;

            out vec4 vColor;
            flat out uint vId;

            void main() {
                vId = uPickId;
                vColor = uColor;

                if (isnan(aY)) {
                    gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                    return;
                }

                float uMin = uValueRange.x;
                float uMax = uValueRange.y;
                float fy = (aY - uMin) / (uMax - uMin);
                float sy = uPlotRect.y + (1.0 - fy) * uPlotRect.w;

                float rel = uFirstRelIndex + float(gl_InstanceID);
                // 点在单元格中心：x 要加半个像素间隔
                float sx = uPlotRect.x + (rel + 0.5) * uPxPerSample;

                // 居中的四边形：aCorner 的 0/1 映射到 -0.5/+0.5
                float half = max(uMarkerSize, uPickTolerance * 2.0) * 0.5;
                vec2 p = vec2(sx, sy) + (aCorner - vec2(0.5)) * (half * 2.0);

                vec2 ndc = vec2(p.x / uViewport.x * 2.0 - 1.0,
                                1.0 - p.y / uViewport.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
            """;
```

复用同一个 `LINE_FRAGMENT` 与 `PICK_FRAGMENT`。

- [ ] **Step 3: 写 `ScatterSeriesRenderer`**

结构与 `LineSeriesRenderer` 一致（构造时建 VAO + 单位四边形 VBO；`render` 里 `bufferFor` → `uploadNewSamples` → `computePoints` → 设 uniform → 逐段 draw）。三处必须不同：

```java
        // 1) 只配一个数据属性，没有第二端
        gl.bindVbo(buffer.vboId());
        glVertexAttribPointer(1, 1, GL_FLOAT, false, Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        gl.setVertexAttribDivisor(1, 1);
        gl.bindVbo(0);

        // 2) 用点数版本，不是线段版本
        List<WindowRange.Segment> segments = WindowRange.computePoints(
                windowStart, windowEnd, source.writeCount(), buffer.capacity());

        // 3) markerSize 而不是 halfWidth
        shader.setUniform("uMarkerSize", series.markerSize());
```

ID pass 与折线**逐行相同**（同一个 `c.pickId()` 判断、同一个 `withPickPass`、同样的 `uPickTolerance`），只是换 `pickShader` 的属性位置——**散点的拾取容差用的是 `uMarkerSize` 与 `uPickTolerance * 2.0` 的较大者**（见上面着色器里那行 `max`）。

`ChartRenderer.rendererFor` 的分派逻辑不用改：`drawsMarkers() && !connectsSamples()` 已经会走到散点。

- [ ] **Step 4: 跑 `ChartVerifier`**

在 `ChartVerifier` 里加散点断言：
- 每个数据点位置有像素
- 点**之间**没有连线（这是折线与散点的唯一区别，**必须专门断言**——
  断言"有点"对连线的情况同样成立，是橡皮擦图章）

- [ ] **Step 5: 变异验证**

| 变异 | 期望失败的断言 |
|---|---|
| 散点渲染器改成连线 | "点之间没有连线" 失败 |
| `markerSize` 改成 0 | "每个数据点位置有像素" 失败 |
| `computePoints` 内部转调 `compute`（即用线段版本） | **`点数版本不排除最后一个点`** 失败 |

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chartrender/ScatterSeriesRenderer.java \
        src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesShaders.java \
        src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt
git commit -F - <<'EOF'
feat(chartrender): 散点渲染器

与折线共用同一套实例机制，区别是每个点一个实例、几何居中、不需要第二端。

校验器专门断言"点之间没有连线"——那是散点与折线的唯一区别，
而"有点"这个断言对连线的情况同样成立，是橡皮图章。
EOF
```

---

## Task 15: 更新文档

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: 更新 `CLAUDE.md`**

四处：

1. **「图表」一节**：把"绘制后端尚未实现"改成 ② 的现状，并写清：
   - 数据在 GPU 里存**数值**不存屏幕坐标（这是滚动免费的前提）
   - 每点 4 字节，靠"同一个缓冲、两个偏移"的实例属性拿到线段两端
   - **必须用 `glDrawArraysInstancedBaseInstance`** 而不是 `glDrawArraysInstanced`，
     原因（实例属性按 `gl_InstanceID` 取，它每次从 0 开始）
   - `Gc.flush()` 的存在理由（帧内 z 序）与它会让 ID pass 多跑几趟
   - 拾取按**系列**发号，容差 8px 是刻意的
   - 本期只做 LINE / SCATTER；LOGARITHMIC 与 TEXT 轴会明确抛异常
2. **「怎么验证改动」一节**：加 `ChartVerifier` 的命令与"改图表跑它"
3. **测试计数**：从 261 更新到实际值
4. **「未实现 / 待办」**：把"图表绘制后端"划掉，补上 STEP/AREA/BAR/HEATMAP/WATERFALL

- [ ] **Step 2: 更新 `README.md`**

- 「图表」一节：把"绘制后端尚未实现——它现在只算不画"改掉，补一段能跑的最小示例
  （建 `Chart`、画网格、`flush()`、`charts.draw(...)`、画刻度文字）
- 「运行」一节：加 `ChartVerifier` 的命令
- 测试计数

- [ ] **Step 0: 补上 Task 2 遗留的三处文案（**现在的"Step 3"里附了精确文本，照着改**）**

复核确认 Task 2（`ShaderProgram` 的 uniform 缓存与 vec4）**在 Task 12 之前没有任何运行覆盖**：

> 全仓清点后确认，`getUniformLocation` 与所有 `setUniform*` **在 `src/`（含 `example/`）里一个调用方都没有**。
> `RenderBatch` 全程不设 uniform——它的 GLSL 只有 `uniform sampler2D uTex`，靠 sampler 默认的 0 号纹理单元。
> **把 `getUniformLocation` 改成 `return 0;`，`PipelineVerifier` 照样退出 0。**

**这条要记住的意义是「归因」**：Task 2 引入的缺陷，只会以 **Task 12 的 `ChartVerifier` 失败**的形式暴露。
届时排查方向会天然指向 Task 11/12 的渲染器，**而那口锅可能属于 Task 2**。

- [ ] **Step 3: 清两处历史欠账的文案**

**先补 `ShaderProgram` 的两句**（Task 2 复核提的，当时判为 Minor 未返工，在这里落地）：

1. **线程约定**——本类现在有了唯一的可变状态（`uniformLocations`），而仓库里凡持有非线程安全可变状态的类都写了线程小节：`gl/Framebuffer`、`gl/VertexBuffer`、`renderer/PickBuffer`、`renderer/RenderBatch`、`text/FontFile`、`text/GlyphAtlas`、`text/FontGlyphSource`。**不是真风险**（按线程模型只在 GL 线程用），**所以不要改成 `ConcurrentHashMap`**——那是为假想的需要加东西。加一句即可：

   > 本类只在 GL 线程使用（见 `CLAUDE.md` 的线程模型），因此 uniform 位置缓存用非线程安全的 `HashMap`。

2. **重新链接的不变量**——字段注释说"程序链接之后位置就固定了"，但没写另一半：本类不提供重新链接入口；**若将来加入 `glLinkProgram`，必须清空该缓存**，否则缓存会静默返回旧位置，表现又是"设了但没生效"——本仓库最怕的那类静默错误。

**再修一处 `PipelineVerifier.kt` 里措辞偏宽的注释**（Task 3 收尾复核提的）：

- `:157` 的"空实现的 flush 只让情况三失败"**字面上不成立**——实测是 5 条失败（2 条带"情况三"标签 + 3 条全画面不变量），三条都由情况三残留的 under 造成。**指向没错，但"只让情况三失败"会让人以为全画面不变量是安全的。** 收紧为"只把情况三那一组（外加三条全画面不变量）打死"。
- 新加的 `3×120×120` 那条，标签写"别处无杂散"，**实际失败原因是探针少了一块**（28800 < 43200）。标签改成"后画色恰好铺满三个探针矩形（不多不少）"。

**再清 `gl/` 下的英文文案。** `CLAUDE.md` 明写"代码注释和 Javadoc **一律使用中文**"，但 `gl/` 下有两处英文：
`LwjglGLAbstraction`（`:16-22`、`:27`、`:303`）与 `Texture.java:14`。**属孤立的历史欠账**，
不是普遍现象（`gl/` 下其余文件都是中文）。

`CLAUDE.md` 明写"代码注释和 Javadoc **一律使用中文**"，但 `gl/` 下有两处英文：
`LwjglGLAbstraction`（`:16-22`、`:27`、`:303`）与 `Texture.java:14`。**属孤立的历史欠账**，
不是普遍现象（`gl/` 下其余文件都是中文）。

一并处理质量复核提的那条**过期清单**——`LwjglGLAbstraction.java:18` 写着
"delegates to the standard LWJGL OpenGL bindings (GL11, GL15, GL20, GL30)"：

> **不要去补这个清单，直接删掉那个括号。** 它在本轮改动之前就已经烂了：
> **`GL20` 被列在里面，而这个文件根本没有 import GL20**；同时 **`GL12` 在
> `createR8Texture` 里真的被用到了却没列**。一份只会腐烂的清单，补一次就会再烂一次。

- [ ] **Step 4: 跑全量验收**

```bash
mvn -o clean test
```
Expected: 全部通过，0 失败，2 跳过。

然后四个校验器逐个跑，**每个都必须退出码 0**：
```bash
git status --porcelain   # 先确认工作区状态，别把别人的文件带进来
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.TextVerifierKt"
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.ChartVerifierKt"
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java \
        src/main/java/com/bingbaihanji/jfgl/gl/Texture.java
git commit -F - <<'EOF'
docs: 补图表绘制后端（子项目 D-②）的现状与用法

CLAUDE.md：图表一节从"只算不画"更新到 ② 已完成的部分，
写清数据存数值不存屏幕坐标、4 字节/点、为什么必须用
glDrawArraysInstancedBaseInstance、Gc.flush() 的存在理由、
拾取按系列发号且容差是刻意的、本期只做 LINE/SCATTER。

README.md：补一段能跑的最小示例与 ChartVerifier 的命令。
EOF
```

---

## 自审记录

**规格覆盖检查**（逐节对回任务）：

| 规格节 | 落在哪个任务 |
|---|---|
| §3.1 代码放 `chartrender/` | 全局（文件结构一节已写明） |
| §4.1 存数值不存屏幕坐标 | Task 9（着色器）、Task 6 |
| §4.2 只存 y、4 字节/点 | Task 7、Task 8、Task 10（双偏移属性） |
| §4.3 轴/网格/文字走 `Gc` | Task 3（`flush()`）、Task 12（z 序断言 #3） |
| §4.4 帧内 z 序 | Task 3 |
| §5.1 instancing + `BaseInstance` | Task 1、Task 9、Task 10 |
| §5.2 三个 GL 方法 | Task 1 |
| §5.3 vec4 + location 缓存 | Task 2 |
| §5.4 新包类清单 | Task 5–11 |
| §5.5 用户 API | Task 11 |
| §5.6 `flush()` 契约 | Task 3 |
| §6.1 系列级 ID | Task 13 |
| §6.2 拾取容差 | Task 13 |
| §6.3 `withPickPass` | Task 4 |
| §7 NaN 断开 | Task 8（`hasGapBetween`）、Task 9（着色器退化）、Task 12（#4） |
| §8 `ChartType` 矩阵 | Task 11（`rendererFor` 抛异常）、Task 14 |
| §9.1 单元测试 | Task 5、6、7、8 |
| §9.2 像素校验器 | Task 12 |
| §9.3 上传字节数断言 | Task 12 #5（**本计划的核心**） |
| §9.4 场景会变 | Task 12 #6 |
| §9.5 退出码 | Task 12 Step 3 |
| §10 R1 容量定死 | Task 8 |
| §10 R2 双实现一致 | Task 6（一致性断言） |
| §10 R3 接头质量 | 已确认细线，不处理 |
| §10 R4 float 精度 | Task 8 类文档 |
| §10 R5 uniform 位置 | Task 2 |
| §10 R6 生命周期 | Task 11 Step 3 |
| §12 交付分解 | ②-1 = Task 1–4；②-2 = Task 5–11；②-3 = Task 12–14 |

**规格里没有、由本计划补充的决定**（应当回填进规格）：

1. **`glDrawArraysInstancedBaseInstance` 而不是 `glDrawArraysInstanced`** —— 已在规格更正提交 `35d14ba` 里补上。
2. **LOGARITHMIC / TEXT 轴明确抛异常** —— 规格 §8 的支持矩阵目前只列了 `ChartType`，没有列 `AxisType`。**实现 Task 6 时应把它补进规格 §8。**
3. **实例下标用"相对窗口左端"的小数** —— 绝对下标在长时间采集后会溢出 `int`（每秒百万点，约 36 分钟到 2^31）。规格没提这件事，**Task 9 的着色器注释里已写明**。

**已知的plan-internal风险**：

**自审时发现并修掉的三个真问题**（都不是措辞问题，是会让实现者写出坏代码的）：

1. **Task 8 用 `data.itemCount()` 当"已写入总数"是错的。**
   `RingChartData.itemCount()` 返回 `min(writeIndex, capacity)`——**环写满之后就永远停在 capacity**。
   拿它当写指针，增量上传会在环满之后认为"没有新数据"，于是**静默地再也不上传、画面定格在第一屏**，
   而且没有任何报错。修法是引入 `SeriesSource` 归一化两套索引语义，并补一条专门的对抗性测试
   （`环写满之后仍然继续上传`）。**这条是本计划里最值钱的一处修改。**
2. **草稿让渲染器读 `series.pickId()`，而 `Series` 上根本没有这个方法。**
   核对 `Series.java` 之后确认这是对的——拾取 ID 是**渲染层**的概念，不属于图表框架。
   改为 `ChartRenderer` 从 **`Gc` 的注册表**取号。**必须共用同一个 ID 空间**：
   两套注册表各自从 1 发号的话，"1 号"既可能是一条曲线也可能是一个按钮，
   点击落在错误的对象上而画面完全正常。
3. **Task 10 的草稿用了 `series.red()/green()/blue()/alpha()`**，实际只有
   `color()` 返回 ARGB `int`。已改为自己拆分量。

**其余两处已知的、刻意的留白**：

- Task 7 故意留了一处"实现时定签名"（去掉多余的占位参数），是全计划唯一一处。
- Task 15 的文档更新只给了"改哪四处"的清单，没有给逐字文本——
  文档要照着最终代码写，现在写死会与实现脱节。
- Task 10 Step 3 要动 `FXGLTransfer.kt` 的 `onDispose`——**先读它实际的释放顺序再接入**。
  `ChartRenderer` 的释放必须挂进那条链，否则窗口关闭时泄漏 GL 资源。
  计划里写的是"挂进去"，没写死代码，因为那段顺序要照着现状写。
