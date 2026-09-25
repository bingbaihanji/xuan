# JFGL 抗锯齿（子项目 A）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让细线、描边与图表系列在斜向处不再有阶梯状锯齿，且抗锯齿可配置（MSAA 构造时、
解析式 AA 运行期）。

**Architecture:** 给顶点格式加一条 `vec2 aEdge`（横向、沿向），由 `StrokeGenerator` 在生成
描边轮廓时顺带算出；片元用 `fwidth` 求屏幕空间导数做覆盖率羽化。**填充不做解析式 AA**
（三角形汤里"内部顶点到边界的距离"没有定义），交给 MSAA——两者互补。
**两个开关默认都关**，因此既有 370 条单测与七个校验器的精确像素期望全部不动。

**Tech Stack:** Java 21 / Kotlin 21 / `#version 330 core` GLSL / LWJGL 3.3.6 + openglfx。

**设计依据：** `docs/superpowers/specs/2026-09-26-jfgl-antialias-design.md`。
术语、取舍理由、证据强度分级、实测数据都在那里，**本计划不重复论证**；
下面对每个 Task 只写"照哪一节"。

---

## 本仓库的元规则（先读，它们每一条都踩过）

1. **`jfgl-render-gl` 的改动必须 `install` 才能被校验器看到。**
   `jfgl-javafx` 的校验器是用 `-f jfgl-javafx/pom.xml` 跑的，`jfgl-render-gl` 从**本地仓库**
   解析。改了它却只 `compile`，跑出来的仍是**旧版本**——而输出会**完全一致地失败**，
   看起来像校验器"飘"。**每个碰了 `jfgl-render-gl` 的 Task 结束前都要**：
   ```bash
   mvn -o install -DskipTests -pl jfgl-render-gl -am
   ```
   （`jfgl-javafx` 自己的改动不必 install——那是当场编译的。）
2. **跑校验器/示例必须带两个编码开关**：`-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。
   少任何一个，中文断言与诊断都是乱码（默认 GBK）。
3. **`grep stderr | grep -i exception` 在本项目几乎证明不了任何事**：GL 线程的异常被
   openglfx 的原生回调吞掉，**我们代码里一处 `catch` 都没有**。要证明"这段代码跑通了"，
   **在它末尾打一行**——加在末尾才证明"整段函数体都返回了"。
4. **变异注入要用最小 token 改动**（判据改恒真、常量改值、分支改恒假），
   **不要用块删除正则**——它在 slurp 模式下会吃错范围，而**失败形态是"编译不过"**，
   与"注入成功但断言没抓到"在粗 grep 下长得一样。注入后**先确认那条断言的读数行还在**。
5. **`msaa > 0` 时像素校验器全体失效**（画布 FBO 上 `glReadPixels` 非法，实测）。
   所以**校验器一律在 `msaa=0` 下跑**，且 Task 6 要加一条"明确拒绝"的守卫。
6. **不抢前台、不截屏；跑完按 PID 确认残留 JVM**（本机常驻 IDEA 的 Kotlin 守护进程与
   Maven 的 Kotlin daemon，**绝不要写成"杀光 java.exe"**）。

---

## 文件结构

| 文件 | 本计划改什么 |
|---|---|
| `jfgl-render-gl/.../renderer/VertexFormat.java` | stride 24→32，加 `OFFSET_EDGE`，改布局文档 |
| `jfgl-render-gl/.../renderer/VertexWriter.java` | 加带 `aEdge` 的 `vertex` 重载；旧的委托给它 |
| `jfgl-render-gl/.../renderer/RenderBatch.java` | VAO 第 5 个属性；顶点着色器加 `vEdge`；片元加羽化 |
| `jfgl-core/.../geom/StrokeGenerator.java` | 并行输出 `edges`（横向、沿向）与 `rawEdges()` |
| `jfgl-render-gl/.../renderer/Gc.kt` | `antialias` 进样式栈；描边几何外扩 1 像素；把 `aEdge` 写进顶点 |
| `jfgl-render-gl/.../chartrender/SeriesShaders.java` | 系列片元加羽化；顶点着色器输出 `vEdge` |
| `jfgl-render-gl/.../chartrender/*SeriesRenderer.java` | 传 `uAntialias` |
| `jfgl-render-gl/.../test/renderer/GcAntialiasTest.java` | **新建**：`antialias` 进样式栈的单测 |
| `jfgl-javafx/.../example/PipelineVerifier.kt` | 新增"★ 抗锯齿"一节 |
| `jfgl-javafx/.../example/ChartVerifier.kt` | 新增"★ 系列抗锯齿"一节 |
| `jfgl-javafx/.../glview/FXGLTransfer.kt` | `msaa > 0` 时的回读拒绝守卫 |
| `jfgl-javafx/.../dsl/JFGL.kt` | `antialias { msaa = N }` |
| `CLAUDE.md` | 顶点格式 24→32；MSAA 与像素校验器互斥 |

---

## Task 1: 顶点格式 24 → 32（纯 Java 数据层，零 GL）

**Files:**
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/VertexFormat.java`
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java`
- Test: `jfgl-render-gl/src/test/java/com/bingbaihanji/jfgl/renderer/VertexFormatTest.java`
- Test: `jfgl-render-gl/src/test/java/com/bingbaihanji/jfgl/renderer/VertexWriterTest.java`

> 本 Task **不碰 GL、不碰着色器、不碰 `Gc`**——所以它能用纯单测验证，且**行为逐位不变**
> （两个分量全写 0）。设计依据：规格 §4.1。

- [ ] **Step 1: 写失败的测试（VertexFormatTest）**

替换 `VertexFormatTest` 里那条断言 stride 的用例，并在其后**新增**一条：

```java
    @Test
    void 顶点布局是32字节8字() {
        assertEquals(32, VertexFormat.STRIDE_BYTES);
        assertEquals(8, VertexFormat.WORDS_PER_VERTEX);
        assertEquals(VertexFormat.STRIDE_BYTES, VertexFormat.WORDS_PER_VERTEX * 4);
    }

    @Test
    void 抗锯齿边距在偏移24处() {
        assertEquals(24, VertexFormat.OFFSET_EDGE);
        // 它必须紧跟在 20 处的 uint 之后、且恰好占满到 32
        assertEquals(VertexFormat.OFFSET_EDGE + 8, VertexFormat.STRIDE_BYTES);
        assertEquals(VertexFormat.OFFSET_ID + 4, VertexFormat.OFFSET_EDGE);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl jfgl-render-gl -Dtest=VertexFormatTest`
Expected: FAIL —— `expected: <32> but was: <24>`，且 `OFFSET_EDGE` 编译不过。

- [ ] **Step 3: 改 `VertexFormat`**

把类头注释里的布局表换成：

```java
 * 布局（共 32 字节）：
 * <pre>
 *  偏移  0  vec2 float            位置（已烘焙到 NDC）
 *  偏移  8  vec2 float            纹理坐标
 *  偏移 16  vec4 ubyte normalized 颜色（预乘 alpha）
 *  偏移 20  uint                  拾取 ID
 *  偏移 24  vec2 float            抗锯齿边距（横向、沿向）
 * </pre>
```

常量改成：

```java
    /** 每个顶点占用的 32 位字数（8 字 = 32 字节）。 */
    public static final int WORDS_PER_VERTEX = 8;

    /** 每个顶点占用的字节数。 */
    public static final int STRIDE_BYTES = 32;
```

并在 `OFFSET_ID` 之后加：

```java
    /**
     * 抗锯齿边距（vec2 float）在顶点内的字节偏移。
     *
     * <p><strong>两个分量分别是"横向"与"沿向"</strong>，约定与用法见
     * {@link StrokeGenerator#rawEdges()} 与渲染管线里的描边片段着色器。
     * 简言之：{@code x} 是到中心线的有符号距离（归一化到真实半线宽，{@code ±1} 是两条真实外缘），
     * {@code y} 是到最近端帽的沿路径距离（同样归一化，**恒 ≥ 0**）。
     *
     * <p><strong>填充与文本两个分量都写 0</strong>——那时该 vary 在图元上是常量、
     * 屏幕空间导数为 0，片段着色器据此走"完全覆盖"的分支。
     * 这条不是特例，是判据本身（见片元着色器里那两个 {@code w > 0.0}）。
     */
    public static final int OFFSET_EDGE = 24;
```

- [ ] **Step 4: 改 `VertexWriter`**

把现有的 `vertex(...)` 整个换成下面**两个**方法（旧签名保留、委托给新的）：

```java
    /**
     * 追加一个顶点，抗锯齿边距写 0。必须先调用 {@link #setState}。
     *
     * <p>这个重载给"与抗锯齿无关的几何"用：填充、文本，以及 {@code Gc.antialias} 关时的
     * 一切几何。写 0 而不是别的值是**有意义的**——它让片段着色器里那两个
     * {@code fwidth} 为 0，从而走"完全覆盖"的分支（见 {@link VertexFormat#OFFSET_EDGE}）。
     */
    public void vertex(float x, float y, float u, float v, int premultipliedRgba, int id) {
        vertex(x, y, u, v, premultipliedRgba, id, 0f, 0f);
    }

    /**
     * 追加一个带抗锯齿边距的顶点。必须先调用 {@link #setState}。
     *
     * @param x                位置 x（已烘焙到 NDC）
     * @param y                位置 y（已烘焙到 NDC）
     * @param u                纹理坐标 u
     * @param v                纹理坐标 v
     * @param premultipliedRgba 预乘 alpha 后的 RGBA 颜色
     * @param id               拾取 ID
     * @param edgeCross        横向：到中心线的有符号距离 ÷ 半线宽（{@code ±1} = 两条真实外缘）
     * @param edgeAlong        沿向：到最近端帽的沿路径距离 ÷ 半线宽（**恒 ≥ 0**）
     * @throws IllegalStateException 尚未调用 {@link #setState} 时；
     *                               或缓冲区已写满、消费方始终没有执行帧中途 flush 时
     */
    public void vertex(float x, float y, float u, float v, int premultipliedRgba, int id,
                       float edgeCross, float edgeAlong) {
        if (currentFirstVertex == NO_COMMAND) {
            throw new IllegalStateException("写入顶点前必须先调用 setState()");
        }
        if (vertexCount >= flushThresholdVertices) {
            grow();
        }
        if (vertexCount >= capacityVertices) {
            throw new IllegalStateException(
                    "顶点缓冲已满：必须在每个图元之前检查 isFlushRequested() 并执行帧中途 flush");
        }
        int offset = vertexCount * VertexFormat.STRIDE_BYTES;
        buffer.putFloat(offset, x);
        buffer.putFloat(offset + 4, y);
        buffer.putFloat(offset + 8, u);
        buffer.putFloat(offset + 12, v);
        buffer.putInt(offset + 16, premultipliedRgba);
        buffer.putInt(offset + 20, id);
        buffer.putFloat(offset + 24, edgeCross);
        buffer.putFloat(offset + 28, edgeAlong);
        if (id != 0) {
            hasPickable = true;
        }
        vertexCount++;
    }
```

**`quad(...)` 不用改**——它内部调的是那个 6 参 `vertex`，边距自然是 0。

- [ ] **Step 5: 修 `VertexWriterTest` 里那个写死的 24**

`VertexWriterTest` 第 90 行附近有一处**字面量** `b.getFloat(24)`（它本意是"顶点 1 的 x"，
即 stride 偏移）。改成符号：

```java
        assertEquals(10f, b.getFloat(VertexFormat.STRIDE_BYTES), 1e-6f, "顶点1 的 x 应为右上角 10");
```

（其余位置用的已经是 `VertexFormat.STRIDE_BYTES`，不必动。）

> **★★ 2026-09-26 执行期修正：上面那句"其余位置……不必动"是错的。** 执行者实测：
> 同一个文件第 91~93 行还有**三处**写死的 `120`（= 5×24）以及 `120+8` / `120+12`。
> 只改第 90 行那处的话，这个用例会因为**读到顶点 4 的 y**（`0f` ≠ 期望的 `1f`）而失败，
> 而失败原因与"边距"毫不相干——排查方向会被完全带偏。
> 一并改成 `5 * VertexFormat.STRIDE_BYTES` 这类**由 stride 推出**的写法则可免疫。
> **教训**：这类"把 stride 写死在断言里"的字面量，一处漏掉就会在下一次改格式时
> 变成一条**指向错误方向的**失败。

- [ ] **Step 6: 新增一条"边距真的落在 24/28"的测试**

追加到 `VertexWriterTest` 末尾：

```java
    @Test
    void 抗锯齿边距写在偏移24与28处() {
        VertexWriter writer = new VertexWriter(16);
        writer.setState(0, 0, 0, 100, 100);
        writer.vertex(1f, 2f, 0f, 0f, 0xFF00FF00, 0, -1f, 0.25f);

        ByteBuffer b = writer.buffer();
        assertEquals(-1f, b.getFloat(24), 1e-6f, "横向边距应在偏移 24");
        assertEquals(0.25f, b.getFloat(28), 1e-6f, "沿向边距应在偏移 28");
        // 与它相邻的两个字段不能被挤动
        assertEquals(0xFF00FF00, b.getInt(16), "颜色仍在偏移 16");
        assertEquals(0, b.getInt(20), "拾取 ID 仍在偏移 20");
    }
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -o test -pl jfgl-render-gl -Dtest='VertexFormatTest,VertexWriterTest'`
Expected: PASS。

- [ ] **Step 8: 跑全量单测 + install**

```bash
mvn -o test
mvn -o install -DskipTests -pl jfgl-render-gl -am
```

Expected: `Tests run: 159 + 211 = 370, Failures: 0, Errors: 0, Skipped: 2`（条数不变），
`BUILD SUCCESS`。

> ⚠️ 此刻**必须**跑一次 `PipelineVerifier` 确认画面**逐像素没变**——stride 改了、
> 而属性指针也跟着改了，接错一个偏移就会全画面错乱：
> ```bash
> mvn -o -f jfgl-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
>   "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
> ```
> Expected: 退出码 0。**但这一步会失败**——本 Task 改了 stride 却还没给新属性建指针，
> 顶点着色器会读到未定义值。所以这里要**顺带**在
> `RenderBatch.configureVaoAttributes()` 末尾加：

```java
        // 抗锯齿边距：vec2，两个 float。**它必须在这里建立**，否则 stride 变成 32 之后
        // 显存里那条属性没有任何指针指着它——顶点着色器读到的会是未定义值。
        glVertexAttribPointer(4, 2, GL_FLOAT, false, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_EDGE);
        glEnableVertexAttribArray(4);
```

（加完再跑 `PipelineVerifier`，Expected: 退出码 0。）

> **★★ 2026-09-26 执行期修正：上面那句"接错一个偏移就会全画面错乱"在本 Task 范围内
> 不成立——它是 Task 3 才成立的。** 执行者实测指出：本 Task 不碰着色器，所以此刻
> **没有任何着色器声明 `layout(location = 4)`**（全仓库只有 location 0~3）。
> GL 对"启用了但没指针/没被读"的属性返回通用常量，于是：
> **把这句话删掉、或把偏移写成 0 或 28，画面都照样逐位不变。**
> 也就是说 **`PipelineVerifier` 绿在这件事上不是证据**——本 Task 里这条属性指针的
> 正确性只有"代码读过一遍"这一层保障。
> **端到端证据要等 Task 3 给顶点着色器加上 `aEdge` 之后**，见 Task 3 的 Step 8。
> 留着这段话是因为它指出了**未来真实的故障模式**（一旦着色器开始读它，接错就全画面错乱），
> 但**不要**把它当成 Task 1 的验收依据。

- [ ] **Step 9: 提交**

```bash
git add jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/VertexFormat.java \
        jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java \
        jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java \
        jfgl-render-gl/src/test/java/com/bingbaihanji/jfgl/renderer/VertexFormatTest.java \
        jfgl-render-gl/src/test/java/com/bingbaihanji/jfgl/renderer/VertexWriterTest.java
git commit -m "feat(render): 顶点格式 24→32，加 aEdge(横向,沿向)；行为逐位不变"
```

---

## Task 2: `StrokeGenerator` 输出 `aEdge`（纯计算 + 单测）

**Files:**
- Modify: `jfgl-core/src/main/java/com/bingbaihanji/jfgl/geom/StrokeGenerator.java`
- Test: `jfgl-core/src/test/java/com/bingbaihanji/jfgl/geom/StrokeGeneratorTest.java`

> 本 Task **一行 GL 都不碰**，行为也不变（还没人读 `edges`）。
> 设计依据：规格 §4.1（两个分量的定义）、§7.4（单测判据）、§7.5（变异表）。
>
> **关键事实（读代码得来）**：`emitCap` 对 `Cap.BUTT` **直接 `return`、不发射任何几何**
> ——所以平头端的端线就是**最后一段带四边形的边**，`along` 只要按**弧长簿记**逐顶点算出来
> 即可，**不需要新几何**。

- [ ] **Step 1: 写失败的测试**

追加到 `StrokeGeneratorTest`：

```java
    @Test
    void 水平直线段的横向边距是正负一() {
        StrokeGenerator g = new StrokeGenerator();
        // 一条从 (0,0) 到 (100,0) 的线，线宽 10 ⇒ 半线宽 5
        g.stroke(new float[]{0f, 0f, 100f, 0f}, 2, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        // 4 个顶点（两个三角形共 6 个，但四边形只有 4 个不同角点）
        // 竖直方向的偏移是 ±5，归一化到半线宽 ⇒ ±1
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            float c = e[i * 2];
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        assertEquals(-1f, min, 1e-5f, "一条长边的横向边距应为 -1");
        assertEquals(1f, max, 1e-5f, "另一条长边的横向边距应为 +1");
    }

    @Test
    void 开放路径的沿向边距在端帽处为零且恒非负() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 100f, 0f}, 2, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        float min = Float.MAX_VALUE;
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            min = Math.min(min, e[i * 2 + 1]);
        }
        assertEquals(0f, min, 1e-5f, "起点的沿向边距应为 0（它就在端帽线上）");
        // 另一头：沿向 = 100/5 = 20
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            max = Math.max(max, e[i * 2 + 1]);
        }
        assertEquals(20f, max, 1e-4f, "终点的沿向边距应为 全长/半线宽");
    }

    @Test
    void 闭合路径的沿向边距远离零() {
        StrokeGenerator g = new StrokeGenerator();
        // 一个 100x100 的方框，周长 400，半线宽 5 ⇒ 沿向应当恒 ≥ 400/5 = 80
        g.stroke(new float[]{0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f}, 4, true, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            assertTrue(e[i * 2 + 1] >= 80f,
                    "闭合路径不该有靠近 0 的沿向边距（会在起点凭空造出羽化边），实测 " + e[i * 2 + 1]);
        }
    }

    @Test
    void 边距数组长度与三角形对齐且不含NaN() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 10f, 20f, 0f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        assertTrue(e.length >= g.triangleCount() * 6, "边距数组至少要有 三角形数*6 个 float");
        for (int i = 0; i < g.triangleCount() * 6; i++) {
            assertTrue(Float.isFinite(e[i]), "边距不该是 NaN/Infinity，实测 " + e[i]);
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl jfgl-core -Dtest=StrokeGeneratorTest`
Expected: 编译失败（`rawEdges()` 不存在）。

- [ ] **Step 3: 加字段与访问器**

在 `triangles` / `triangleCount` 字段旁加：

```java
    /**
     * 与 {@link #triangles} <strong>逐顶点平行</strong>的抗锯齿边距：每 3 个 float 一个三角形，
     * 依次是三个顶点的 {@code (横向, 沿向)}。
     *
     * <p>长度恒为 {@code triangles.length / 2}（三角形每顶点 2 个 float，边距每顶点 2 个 float）。
     * 有效数据是前 {@code triangleCount * 6} 个 float。
     *
     * <p><strong>为什么两者要分开存</strong>：{@code triangles} 是"位置"的唯一真相，
     * 已有的读取方（{@code Gc.emitTriangles}）按 6 个 float 一个三角形遍历它；
     * 把边距缠进同一个数组会改掉那个契约，而契约的另一头还有 {@code PathVerifier} 在跑。
     */
    private float[] edges = new float[3 * 6 * 2];

    /** 返回内部边距数组本身，<strong>不复制</strong>。约定与 {@link #rawTriangles()} 逐条相同。 */
    public float[] rawEdges() {
        return edges;
    }
```

并在 `reset()` 里与 `triangleCount = 0` 并列加 `edgeCount = 0;`（若 `reset()` 里没有单独的
`edgeCount`，就删掉这一句——见 Step 5 的说明，`edgeCount` 与 `triangleCount` 恒等，
**不单独维护**）。

- [ ] **Step 4: 改 `emitTriangle`，把边距一并写进去**

把 `emitTriangle` 换成下面这个（**旧的 6 参版本删掉**，所有调用点由 Step 5 逐个改）：

```java
    /**
     * 追加一个三角形与它三个顶点的抗锯齿边距，必要时扩容内部数组。
     *
     * <p>边距的语义见 {@link #rawEdges()}。
     */
    private void emitTriangle(float x0, float y0, float x1, float y1, float x2, float y2,
                              float e0c, float e0a, float e1c, float e1a, float e2c, float e2a) {
        if (triangleCount * 6 + 6 > triangles.length) {
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
            edges = Arrays.copyOf(edges, edges.length * 2);
        }
        int o = triangleCount * 6;
        triangles[o] = x0;
        triangles[o + 1] = y0;
        triangles[o + 2] = x1;
        triangles[o + 3] = y1;
        triangles[o + 4] = x2;
        triangles[o + 5] = y2;

        edges[o] = e0c;
        edges[o + 1] = e0a;
        edges[o + 2] = e1c;
        edges[o + 3] = e1a;
        edges[o + 4] = e2c;
        edges[o + 5] = e2a;
        triangleCount++;
    }

```

> **不提供"省略边距"的重载。** 加一个 `emitTriangle(x0,y0,x1,y1,x2,y2, e0,e1,e2)`
> 会诱使调用方把沿向当成可选项，而本类的契约是**每个顶点都有两个分量**。
> 所有调用点在第 5 步一次性改完。

- [ ] **Step 5: 把横向与沿向真正算出来**

`generateOutline` 要在循环**之前**先算总弧长（沿向要用它），并维护一个累加弧长 `s`：

```java
        float half = width * 0.5f;
        int segmentCount = closed ? count : count - 1;
        if (segmentCount < 1) {
            return;
        }

        // 沿向边距要用到总弧长：开放路径取 min(到起点, 到终点)，闭合路径取 弧长+全长。
        // 前者让"远离两端"的顶点沿向很大（覆盖率恒 1），后者保证闭合路径**没有**靠近 0 的
        // 顶点——否则会在路径起点凭空造出一条羽化边。
        float totalLength = 0f;
        for (int i = 0; i < segmentCount; i++) {
            int b = (i + 1) % count;
            float ex = points[b * 2] - points[i * 2];
            float ey = points[b * 2 + 1] - points[i * 2 + 1];
            totalLength += (float) Math.sqrt(ex * ex + ey * ey);
        }
        float arc = 0f;          // 当前段的起点在整条路径上的弧长
        float lastArc = 0f;      // 最后一次有效段推进后的弧长（emitCap 要用）
```

在循环内、`float nx = -dy / len * half;` **之后**加：

```java
            float arcB = arc + len;
```

把 `emitJoin(...)` 调用改成带沿向参数的版本，各 `emitQuad` / `emitTriangle` 调用改成
把 `along` 一并传下去。**四类发射点的取值规则**（这是本 Task 的全部内容，逐条都对应规格 §7.4）：

| 发射点 | 横向 `cross` | 沿向 `along` |
|---|---|---|
| 段四边形 | `+1` 在 `+n` 侧、`-1` 在 `-n` 侧 | 该角点的 `min(arc, totalLength-arc)/half`（闭合时 `(arc+totalLength)/half`） |
| 接头的**关节顶点** `(px,py)` | `0`（它在中心线上） | 该拐点的 `along` |
| 接头的**偏移点 / miter 尖角** | 与它所在那一侧**同号**的 `±1` | 同上（同一个弧长） |
| 端帽 | 见下 | 端帽线上是 `0` |

加一个私有方法把"弧长 → 沿向"封在一处（**只写一遍**，段四边形与接头都用它）：

```java
    /**
     * 把沿路径的弧长换算成"沿向边距"（已归一化到半线宽）。
     *
     * <p>开放路径取"到**最近**端帽的距离" ⇒ 两端附近接近 0、中段最大（`totalLength/2/half`）。
     * 闭合路径没有端帽，取 `arc + totalLength` ⇒ **恒 ≥ `totalLength/half`**，
     * 远离 0 ⇒ 那片区域的沿向测试恒为"完全覆盖"，不会在路径起点**凭空造出一条羽化边**。
     *
     * @param arc         从路径起点量起的弧长
     * @param totalLength 整条路径的弧长
     * @param half        半线宽
     * @param closed      闭合路径（无端帽）
     */
    private static float alongAt(float arc, float totalLength, float half, boolean closed) {
        float d = closed ? (arc + totalLength) : Math.min(arc, totalLength - arc);
        return d / half;
    }
```

`emitQuad` 与三个 `emitTriangle` 调用点改成显式传 6 个边距值：

```java
            float aStart = alongAt(arc, totalLength, half, closed);
            float aEnd = alongAt(arcB, totalLength, half, closed);
            emitQuad(ax + nx, ay + ny, aStart, 1f,
                     bx + nx, by + ny, aEnd, 1f,
                     bx - nx, by - ny, aEnd, -1f,
                     ax - nx, ay - ny, aStart, -1f);
```

`emitQuad` 的新签名（注意参数顺序是"位置在前、边距在后"，与 `emitTriangle` 一致）：

```java
    private void emitQuad(float x0, float y0, float a0, float c0,
                          float x1, float y1, float a1, float c1,
                          float x2, float y2, float a2, float c2,
                          float x3, float y3, float a3, float c3) {
        emitTriangle(x0, y0, x1, y1, x2, y2, c0, a0, c1, a1, c2, a2);
        emitTriangle(x2, y2, x3, y3, x0, y0, c2, a2, c3, a3, c0, a0);
    }
```

`emitJoin` 加一个 `along` 参数（调用方传 `alongAt(arc, …)`），内部三个发射点：

```java
        if (join == Join.BEVEL || join == Join.ROUND) {
            emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y,
                    0f, along, s, along, s, along);
            ...
        }
        ...
        emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y,
                0f, along, s, along, s, along);
        emitTriangle(px + o1x, py + o1y, mx, my, px + o2x, py + o2y,
                s, along, s, along, s, along);
```

（`s` 就是 `emitJoin` 里已有的"凸侧符号" `±1` ⇒ 偏移点与尖角都在**外侧边界**上，
所以它们的横向是 `±1` 而不是别的值——这正是 §7.4 那条"尖角与同侧偏移点同号"的判据。）

`emitCap` 加 `along` 参数：**BUTT 分支照旧 `return`**（不发几何，端线就是段四边形的边）；
`SQUARE` 与 `ROUND` 的沿向取 `-距离/half`（向外为负，端帽外那半个像素才有片元）。

`emitArc` 加 `alongBase` 参数，内部按角度算 `along = alongBase - radius*…/half`；
**并在它的 KDoc 里写明：`Gc` 目前只用 `Cap.BUTT`，所以这条路径只有 `StrokeGeneratorTest`
在跑**——别把它当成"生产在用"。

**删掉 Step 4 里那个 9 参重载。** 最终只留 12 参那一个。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -o test -pl jfgl-core -Dtest=StrokeGeneratorTest`
Expected: PASS（含既有的全部用例——**行为不得变**）。

- [ ] **Step 7: 变异验证（两条）**

| 变异 | 必须倒 |
|---|---|
| `alongAt` 里开放路径的 `Math.min(arc, totalLength - arc)` 改成 `arc` | `开放路径的沿向边距在端帽处为零且恒非负`（终点会变成 20 而不是…实际上终点仍是 20；**这条要改成断"另一端也是 0"** ⇒ 见下） |
| `alongAt` 里闭合路径的 `arc + totalLength` 去掉 | `闭合路径的沿向边距远离零` |

> **上表第一行暴露了 Step 1 那条测试的一个弱点**：`min(arc, L-arc)` 与 `arc` 在**起点与终点**
> 上给出相同的值（0 与 L），只有**中段**才分得开。所以 Step 1 的
> `开放路径的沿向边距在端帽处为零且恒非负` 必须**再加一条断言**：
> 一条 100 长的线，中段某个顶点的沿向应当 ≈ `50/5 = 10`（最大值），而不是 20。
> **把它补进 Step 1 的测试里**，再跑变异。

- [ ] **Step 8: install + 回归**

```bash
mvn -o test
mvn -o install -DskipTests -pl jfgl-core -am
```
Expected: 370 通过 / 0 失败 / 2 跳过。

- [ ] **Step 9: 提交**

```bash
git add jfgl-core/src/main/java/com/bingbaihanji/jfgl/geom/StrokeGenerator.java \
        jfgl-core/src/test/java/com/bingbaihanji/jfgl/geom/StrokeGeneratorTest.java
git commit -m "feat(geom): StrokeGenerator 输出 aEdge（横向/沿向），行为不变"
```

---

## Task 3: 描边片元羽化 + `Gc.antialias` + 几何外扩

**Files:**
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java`
- Modify: `jfgl-render-gl/src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt`

> 这是**第一次改变画面**的一步。设计依据：规格 §4.3（片元公式与外扩）、§4.1（外扩多少）。
>
> **外扩的实现落点是 `Gc.strokeOutline`**：它现在把 `lineWidth` 直接交给
> `strokeGenerator.stroke(...)`。开了 AA 时，要传 `lineWidth + 2*px`（两侧各 1 像素），
> 而 `aEdge` 的归一化分母仍是**真实**的 `lineWidth/2`。

- [ ] **Step 1: 顶点着色器加 `vEdge`**

`RenderBatch.VERTEX_SHADER` 改成：

```glsl
#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
layout(location = 3) in uint aId;
layout(location = 4) in vec2 aEdge;
out vec2 vUV;
out vec4 vColor;
out vec2 vEdge;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
    vEdge = aEdge;
}
```

- [ ] **Step 2: 主片段着色器加羽化**

`RenderBatch.FRAGMENT_SHADER` 改成：

```glsl
#version 330 core
in vec2 vUV;
in vec4 vColor;
in vec2 vEdge;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    // 抗锯齿：两个分量各算一个覆盖率，再相乘（见设计文档 §4.3）。
    // ★ 两个 w > 0.0 的判别式不可省：填充与文本的 aEdge 恒为 (0,0)，
    //   它的 fwidth 是 0，直接拿去做除数会得到 NaN ⇒ 整片像素变黑。
    //   它们**同时**是不需要 uAntialias uniform 的原因：开关落在 CPU 侧"写不写真实边距"上，
    //   而 uniform 会打断合批。
    float wc = fwidth(vEdge.x);
    float ac = (wc > 0.0) ? clamp(0.5 - (abs(vEdge.x) - 1.0) / wc, 0.0, 1.0) : 1.0;
    float wa = fwidth(vEdge.y);
    float aa = (wa > 0.0) ? clamp(0.5 + vEdge.y / wa, 0.0, 1.0) : 1.0;

    // 最后一步乘的是**标量** a：顶点色是预乘的，乘标量不破坏预乘性。
    // 写成 vec4(vColor.rgb, vColor.a * a) 会让每个被覆盖的像素饱和到全白（SDF 那段有同样的记录）。
    fragColor = texture(uTex, vUV) * vColor * (ac * aa);
}
```

- [ ] **Step 3: `Gc` 加 `antialias` 字段并进样式栈**

在 `lineWidth` 旁加：

```kotlin
    /**
     * 是否为后续**描边**开启解析式抗锯齿。**默认关。**
     *
     * <p>它进 `save`/`restore` 栈，与 [lineWidth] 同构 ⇒ 可以只给某一条线开，
     * 也可以 `save` 内开、`restore` 关。
     *
     * <p><strong>默认关是刻意的</strong>：开了之后描边边缘会多出半透明像素，
     * 而本仓库的像素校验器里有一批**精确到 ±0 的期望值**（如蓝圆角矩形描边 = 3084），
     * 它们花了整轮才立住。默认关 ⇒ 默认路径逐像素不变。
     *
     * <p>它只影响**描边**；填充与文本不受影响（它们本来就写 `aEdge = (0,0)`）。
     */
    var antialias: Boolean = false
```

把 `INTS_PER_STYLE_LEVEL` 从 3 改成 4，`save()` / `restore()` 各加一行
（`antialias` 用 int 存，1/0）：

```kotlin
        // save()
        styleInts[intBase + 3] = if (antialias) 1 else 0
        // restore()
        antialias = styleInts[intBase + 3] != 0
```

并更新那两处 KDoc（"fill、stroke、pickId" → "fill、stroke、pickId、antialias"）。

> **★ 顺手收掉 Task 1 留下的两处"当前尚不存在"标注。** Task 1 的评审要求给两处前向引用
> 加标注（`VertexFormat.OFFSET_EDGE` 里提的"片段着色器那两个 `w > 0.0`"、
> `VertexWriter.vertex` 里提的 `Gc.antialias`），因为当时它们都还不存在。
> **本 Task 一落地，那两句标注本身就变成过时注释了**——正是 Task 1 报告里点名的
> "新加的两句标注也是前向引用"。**把"随 Task 3 加入 / 当前尚不存在"这两句删掉，
> 引用保留。** 这是本 Task 的收尾动作之一。

- [ ] **Step 4: 描边几何外扩 1 像素**

`Gc.strokeOutline` 现在是这样：

```kotlin
    private fun strokeOutline(points: FloatArray, count: Int, closed: Boolean) {
        if (count < 2) return
        strokeGenerator.stroke(
            points, count, closed, lineWidth,
            StrokeGenerator.Cap.BUTT,
            StrokeGenerator.Join.MITER,
            MITER_LIMIT,
            ROUND_SEGMENTS
        )
        emitTriangles(strokeGenerator.rawTriangles(), strokeGenerator.triangleCount() * 6, stroke)
    }
```

改成：

```kotlin
    private fun strokeOutline(points: FloatArray, count: Int, closed: Boolean) {
        if (count < 2) return
        // ★ 开 AA 时把描边带**向两侧各外扩 1 个设备像素**。这一步不是可选的：
        //   覆盖率公式 clamp(0.5 - 像素距离, 0, 1) 里那个"像素距离"要能取到正数，
        //   而真实边缘之外若没有片元，覆盖率就永远推不到 0.5 以下——
        //   边缘会从"全亮"直接跳到"没有"，AA 只做了一半。
        //   外扩量按当前变换换算成局部单位（matrixScale() 是"1 局部单位 = 多少屏幕单位"）。
        val px = if (antialias) 1f / matrixScale().coerceAtLeast(1e-6f) else 0f
        strokeGenerator.stroke(
            points, count, closed, lineWidth + 2f * px,
            StrokeGenerator.Cap.BUTT,
            StrokeGenerator.Join.MITER,
            MITER_LIMIT,
            ROUND_SEGMENTS
        )
        val n = strokeGenerator.triangleCount()
        // aEdge 的归一化分母是**真实**半线宽（不是外扩后的）——见设计文档 §4.1。
        emitTriangles(strokeGenerator.rawTriangles(), n * 6, stroke,
            strokeGenerator.rawEdges(), lineWidth * 0.5f)
    }
```

> ⚠️ **`matrixScale()` 的确切语义要在实现时核实**（它现在被 `Flattener.flatten(path, matrixScale())`
> 用着，方向是"局部 → 屏幕"还是反过来，看那处的用法）。**算反了的表现是外扩量随缩放跑偏**，
> 而画面"只是边缘略厚"，极难发现 ⇒ 所以它必须有断言（Task 4）。

- [ ] **Step 5: `emitTriangles` 把边距写进顶点**

```kotlin
    /**
     * @param edges      与 [triangles] 平行的边距数组（每顶点 2 个 float）；null 表示全 0
     * @param halfWidth  [edges] 里横向分量的归一化分母（真实半线宽）；[edges] 为 null 时忽略
     */
    private fun emitTriangles(triangles: FloatArray, floatCount: Int, argb: Int,
                              edges: FloatArray? = null, halfWidth: Float = 1f) {
        if (floatCount < 6) return
        flushIfNeeded()
        syncState(batch.whiteTextureId())
        val packed = packColor(argb)
        var i = 0
        while (i + 5 < floatCount) {
            var k = 0
            while (k < 3) {
                val wx = triangles[i]
                val wy = triangles[i + 1]
                val ec = if (edges != null) edges[i] / halfWidth else 0f
                val ea = if (edges != null) edges[i + 1] / halfWidth else 0f
                writer().vertex(
                    state.transformX(wx, wy), state.transformY(wx, wy),
                    0f, 0f, packed, pickId, ec, ea
                )
                i += 2
                k++
            }
        }
    }
```

> **`edges` 为 null 时全写 0** ⇒ 填充（`emitShape`）、文本、以及 `antialias = false` 的描边
> 全部走"完全覆盖"分支 ⇒ **行为与现状逐位相同**。

- [ ] **Step 6: 给 `antialias` 进状态栈写单测**

> 规格 §7.3 要求这一条，而它**没有任何别的 Task 覆盖**。
> `save()` / `restore()` 是**纯内存**操作（只碰 `styleInts` / `styleFloats` 与 `ViewTransform`），
> 所以这一条能脱离 GL 上下文跑。

新建 `jfgl-render-gl/src/test/java/com/bingbaihanji/jfgl/renderer/GcAntialiasTest.java`：

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcAntialiasTest {

    /**
     * `antialias` 必须与 `lineWidth` 一样进样式栈——否则
     * "save 里开、restore 关" 这种局部开关会漏到块外，
     * 而症状是"某一条线之后的**所有**线都变模糊了"，没有任何报错。
     */
    @Test
    void antialias 进样式栈且逐层独立() {
        Gc gc = new Gc(new RenderBatch(new FakeGLAbstraction(), 1024));

        assertFalse(gc.getAntialias(), "默认必须是关的（它保护着一批精确像素期望）");

        gc.save();
        gc.setAntialias(true);
        assertTrue(gc.getAntialias());

        gc.save();
        gc.setAntialias(false);
        assertFalse(gc.getAntialias());

        gc.restore();
        assertTrue(gc.getAntialias(), "内层改了不该影响外层");

        gc.restore();
        assertFalse(gc.getAntialias(), "restore 之后必须回到默认的关闭状态");
    }
}
```

> **若 `new RenderBatch(new FakeGLAbstraction(), 1024)` 构造失败**（它的构造器会调用
> `gl.createShader` 并建一块 `VertexBuffer`，假 GL 未必兜得住），**不要改 `RenderBatch`**
> ——那是生产代码，为测试改它属于本末倒置。改用另一条判据，写在 `PipelineVerifier` 里：
> `save → antialias = true → restore` **之后再画一条线**，断言它与"全程 `antialias` 为假"
> 画出来的那条**逐像素相同**。那条判据测的是同一件事，而且更强（它端到端）。

- [ ] **Step 7: 编译 + 跑 `PipelineVerifier` 确认默认路径没变**

```bash
mvn -o compile && mvn -o install -DskipTests -pl jfgl-render-gl -am
mvn -o -f jfgl-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
  "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
```
Expected: **退出码 0**。`antialias` 默认 false ⇒ 外扩量 0、边距全 0 ⇒ 画面必须**逐像素不变**。
若这里红了，说明**默认路径被改动了**，先别往下走。

- [ ] **Step 8: 给 `aEdge` 的**偏移**补一条端到端探针（Task 1 的欠账）**

> **★ 为什么必须有这一步**：Task 1 加了 `location 4` 的属性指针，但**当时没有任何着色器
> 声明它**，所以那条指针的正确性**当时无法验证**（Task 1 Step 8 有一条与此相关的修正记录）。
> 本 Task 第一次让着色器真正读 `aEdge` ⇒ **现在可以验了，而且必须验**：
> 偏移接错（比如写成 20，读到拾取 ID）不会报任何错，只会让抗锯齿行为**莫名其妙**。

加一个只在自检/校验模式下的探针：让片段着色器**把 `vEdge` 直接当颜色输出**
（`fragColor = vec4(vEdge * 0.5 + 0.5, 0.0, 1.0)`），画一条已知几何的描边，
回读几个像素并断言：

- 描带**两条外缘**上的像素，红通道（`vEdge.x` 映射后）≈ `0.0` 与 `1.0`（即 `-1` 与 `+1`）；
- 描带**中心线**上是 ≈ `0.5`（即 `0`）；
- 绿通道（`vEdge.y`）在**中段**远大于 0.5、在**端帽那一排**≈ 0.5（即 `0`）。

**注意**：探针必须画在**离屏**或一帧内用完即弃——别让它污染 Task 4 的那四条判据。
实现上最简单的做法是**只在校验器里**用一段独立的着色器（或给它一个 `uProbe` uniform
走另一条分支），**不要**改生产着色器的默认行为。

- [ ] **Step 9: ~~顺手修一处过时注释~~ —— 已在 Task 1 的修复轮里做掉了**

> **2026-09-26 执行期修正：本步作废，不必再做。** 原计划让本 Task 顺手改
> `Gc.kt` 里「约 1.5 MB（65536 个顶点 * 24 字节）」那句过时注释。Task 1 的**规格审查**
> 查出同类过时**还剩两处**（`Gc.kt:1395` 与 `FXGLTransfer.kt:539`），
> 已在 Task 1 的修复轮里一并改掉（各一行注释，零风险）。
> **保留这个已划掉的步骤是为了留痕**：它记着"stride 一改，全仓库有**四处**
> 写着旧字节数的注释"这件事，下次再动格式时可以直接 grep 这一类。
> 顺手记下那四处：`RenderBatch.configureVaoAttributes` 的 Javadoc、
> `VertexWriter.MAX_VERTEX_CAPACITY`、`Gc.INITIAL_VERTEX_CAPACITY`、
> `FXGLTransfer` 的顶点容量注释。

- [ ] **Step 10: 提交**

```bash
git add jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java \
        jfgl-render-gl/src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt \
        jfgl-render-gl/src/test/java/com/bingbaihanji/jfgl/renderer/GcAntialiasTest.java \
        jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineVerifier.kt
git commit -m "feat(render): 描边解析式抗锯齿（Gc.antialias，默认关）+ 几何外扩 1 像素"
```

> **上面这份 `git add` 清单在原计划里是错的**（2026-09-26 执行期修正）：它漏掉了
> 本 Task 新增的两处——`GcAntialiasTest.java`（Step 6）与 `PipelineVerifier.kt` 里的
> `aEdge` 偏移探针（Step 8）。**按这份清单加。**

---

## Task 4: `PipelineVerifier` 的 AA 变体 + 变异验证

**Files:**
- Modify: `jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineVerifier.kt`

> 设计依据：规格 §7.1（四条判据）、§7.5（变异表）。
> **这一节是"解析式 AA 到底是好是坏"唯一的地方**——规格 §1.3 已声明它没有实测支撑。

- [ ] **Step 1: 加一节"★ 抗锯齿"**

在同一帧里画**两条同样的 45° 线**（线宽 4px），中间隔开，一条 `antialias = false`、
一条 `true`：

```kotlin
    /**
     * ★ 抗锯齿：**同一条几何画两遍**，一遍开一遍关。
     *
     * <p>四条判据缺一不可（见设计文档 §7.1）：
     * ① 关的那条**没有**中间值（对照组干净）；② 开的那条**有**过渡带；
     * ③ 两条的**线心行**纯色像素数**精确相等**（没移位、没整体变淡）；
     * ④ 墨量守恒（没变粗也没变细）。
     *
     * <p>★ ③ **只取线心行**：开了 AA 之后最外侧那一圈纯色像素本来就会变成过渡像素，
     * 总纯色数**必然略减**——写成"总数相等"是一条**恒假断言**，比没有断言更坏。
     */
    private fun assertAntialias(gc: Gc) {
        val y0 = 560f
        val y1 = 660f
        gc.lineWidth = 4f
        gc.stroke = 0xFFFFFFFF.toInt()

        gc.antialias = false
        gc.drawLine(60f, y0, 460f, y0 + 100f)

        gc.antialias = true
        gc.drawLine(60f, y1, 460f, y1 + 100f)
        gc.antialias = false
    }
```

（两条线都是 45°、线宽 4 ⇒ 解析墨量 = `长度 × 线宽 × 亮度差`，可手算 ⇒ ④ 用**精确相等**。）

- [ ] **Step 2: 实现四条判据**

回读后（沿用本文件既有的 `pixelAt` / `counts` 口径）：

- **过渡像素**的定义：既非背景色、也非线色（白）的像素。
- 两条线各自的**包围盒**内统计，避免互相污染（两条线相距 100px，线宽 4 ⇒ 不重叠）。
- **线心行**：取包围盒的**中间那几行**（避开边缘），比较纯色像素数。
- **墨量**：`Σ (luma − 背景luma)`，对包围盒内逐像素求和。

按下面四条 `check(...)` 落：

```kotlin
        check("★ 抗锯齿① AA 关的线没有过渡像素", aaOffEdge == 0,
            "实测 $aaOffEdge 个（期望 0）")
        check("★ 抗锯齿② AA 开的线有过渡像素", aaOnEdge > 0 && aaOnEdge <= aaOnPerimeterPx * 2,
            "实测 $aaOnEdge 个，线周长约 $aaOnPerimeterPx px（上界取 2 倍）")
        check("★ 抗锯齿③ 线心行的纯色像素数两种模式相等", aaOffCore == aaOnCore,
            "关=$aaOffCore，开=$aaOnCore（期望相等）")
        check("★ 抗锯齿④ 墨量守恒（线没变粗也没变细）", aaOnInk == aaOffInk,
            "关=$aaOffInk，开=$aaOnInk（期望精确相等；若开的那条明显偏大，说明 §4.3 那条"细带偏厚"的公式需要换成三分量）")
```

- [ ] **Step 3: 跑校验器**

```bash
mvn -o -f jfgl-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
  "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
```
Expected: 退出码 0。

> **若 ④ 失败**：那不是"把容差放宽"的信号，而是规格 §4.3 那条"细带偏厚"的公式需要
> 换成三分量的信号（`aEdge` 从 `vec2` 扩到 `vec3`，stride 32→36）。
> **先量出实际墨量偏差是多少、并把它写进读数**，再决定——不要凭"看着差不多"下结论。

- [ ] **Step 4: 变异验证（六条）**

按规格 §7.5 的表逐条注入（**每条都用最小 token 改动**，还原后 `cmp` 逐字节核对）：

| 变异 | 必须倒 |
|---|---|
| 片段着色器里 `abs(vEdge.x)` 改成 `vEdge.x` | ② |
| 两个 `w > 0.0` 判别式删掉 | ①③④ 全倒 |
| `Gc.strokeOutline` 里外扩量改成 `0f` | ④ |
| `clamp(0.5 - …, 0, 1)` 的 `0.5` 改成 `0.0` | ④ |
| `emitTriangles` 里 `ec` 恒传 `0f` | ② |
| `edges[i] / halfWidth` 改成 `edges[i]`（不归一化） | ②（整条线半透明） |

- [ ] **Step 5: 提交**

```bash
git add jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineVerifier.kt
git commit -m "test(render): 抗锯齿的四条像素判据（过渡带/线心/墨量守恒）+ 六条变异"
```

---

## Task 5: 图表系列的 `vEdge` + `uAntialias`

**Files:**
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/SeriesShaders.java`
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/LineSeriesRenderer.java`
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/ScatterSeriesRenderer.java`
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/StepSeriesRenderer.java`
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/AreaSeriesRenderer.java`
- Modify: `jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/BarSeriesRenderer.java`
- Modify: `jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt`

> 设计依据：规格 §5。**图表系列不进 `RenderBatch`**（它们在 `Gc.flush()` 处当场 instanced
> 绘制），所以 Task 1 的顶点格式**与它们无关**——它们各自在顶点着色器里多算一个 varying。
>
> **不需要新属性**：`aCorner`（单位四边形的角，divisor=0）**已经携带了"我在带的哪一侧"**。
> 系列本来就一个系列一条 draw call ⇒ **可以用 uniform**（不涉及合批）。

- [ ] **Step 1: 五个顶点着色器各输出 `vEdge`**

**图表侧用 `float` 而不是 `vec2`**：系列没有端帽（它们的实例区间由数据窗口决定，
两端落在裁剪边界上），所以只有"横向"一个分量有意义。用 `float` 就不必假装还有第二个。

**`aCorner` 在五个着色器里的语义各不相同**（这是本步唯一容易做错的地方，
也正是规格 §5 说的"每个图型一个渲染器"的同一条教训——**不要统一套同一个公式**）：

| 着色器 | `aCorner.x` | `aCorner.y` | `vEdge` 该取什么 |
|---|---|---|---|
| `LINE_VERTEX` | 线段的哪一端（0/1） | **法向的哪一侧**（0/1） | `aCorner.y * 2.0 - 1.0` |
| `SCATTER_VERTEX` | 居中四边形的 x（0/1） | 居中四边形的 y（0/1） | `max(abs(x*2-1), abs(y*2-1))`（方形 SDF） |
| `STEP_VERTEX` | **点序号 0/1/2**（一个实例六个角） | 法向的哪一侧（0/1） | `aCorner.y * 2.0 - 1.0` |
| `AREA_VERTEX` | 线段哪一端（0 左 / 1 右） | **0 = 取数据值 / 1 = 取基线** | `1.0 - aCorner.y * 2.0`（顶边与底边才是边界） |
| `BAR_VERTEX` | 0 左边缘 / 1 右边缘 | 0 = 取数值 / 1 = 取基线 | `max(abs(x*2-1), abs(y*2-1))`（柱是矩形，四边都要） |

逐个加到各自的 `main()` 末尾（`out float vEdge;` 加在 `out vec4 vColor;` 旁边）。
以 `LINE_VERTEX` 为例：

```glsl
    // "我在描边带的哪一侧"——与 Gc 路径的 aEdge.x 同一个意思。
    // 规范化到 [-1,1]：±1 就是带的两条外缘。
    vEdge = aCorner.y * 2.0 - 1.0;
```

`AREA_VERTEX` 那一条要**在它的 KDoc 里写明为什么只取 y**：面积的左右两端落在
绘图区/裁剪边界上，那两条边不该被羽化——把它们也标成边界会让绘图区左右各多出
一条半透明的竖带，而"边缘淡了一点"最难注意到。

- [ ] **Step 2: 系列片段着色器加羽化**

`SeriesShaders.LINE_FRAGMENT` 改成（`PICK_FRAGMENT` **不动**——ID pass 不能有半透明）：

```glsl
#version 330 core
in vec4 vColor;
in float vEdge;
uniform float uAntialias;    // 1.0 = 开，0.0 = 关
out vec4 fragColor;
void main() {
    float a = 1.0;
    if (uAntialias > 0.5) {
        float w = fwidth(vEdge);
        a = (w > 0.0) ? clamp(0.5 - (abs(vEdge) - 1.0) / w, 0.0, 1.0) : 1.0;
    }
    fragColor = vec4(vColor.rgb * vColor.a * a, vColor.a * a);
}
```

> ⚠️ **预乘在这里必须两个通道一起乘**（与 `Gc` 路径那条"乘标量不破坏预乘性"同一件事）：
> `vec4(rgb*a, a)` 才是预乘色，写成 `vec4(rgb, a*a)` 会丢掉预乘性，
> 半透明系列会整片偏色。
>
> ⚠️ **`uAntialias` 是 `if` 而不是乘进去**：关掉时必须是**逐位**的旧行为
> （`vec4(vColor.rgb * vColor.a, vColor.a)`）。写成 `a = mix(1.0, aa, uAntialias)`
> 在 `uAntialias = 0` 时结果相同，但多一次运算——**无所谓，选哪个都行，
> 关键是关掉时不能有 `fwidth` 参与**（它在某些驱动上对常量 vary 会给出 0 以外的值）。

- [ ] **Step 3: 每个渲染器传 `uAntialias`**

在 `LineSeriesRenderer` 的绘制循环里（`uPickTolerance` 那一组 uniform 旁边）加：

```java
        // 系列是否开 AA 由调用方决定（Gc.charts 把它透传下来）。
        // 这里用 uniform 是安全的：一个系列一条 draw call，不涉及 VertexWriter 的合批。
        shader.setUniform("uAntialias", c.antialias() ? 1f : 0f);
```

`GLRenderContext` 加一个 `antialias()`（它的实现 `GLRenderContextImpl` 从 `Gc.antialias` 读）。
**五个渲染器逐个加上同一行**——漏掉一个的表现是"那一种图型没有 AA 而其余有"，
在混合图里很难注意到。

- [ ] **Step 4: `ChartVerifier` 加一段**

与 Task 4 同构：同一个折线系列画两遍（一遍 `gc.antialias = false`、一遍 `true`），
四条判据照搬。

- [ ] **Step 5: 跑 `ChartVerifier`**

```bash
mvn -o install -DskipTests -pl jfgl-render-gl -am
mvn -o -f jfgl-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
  "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.ChartVerifierKt"
```
Expected: 退出码 0，含新增的一段。

- [ ] **Step 6: 提交**

```bash
git add jfgl-render-gl/src/main/java/com/bingbaihanji/jfgl/chartrender/ \
        jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt
git commit -m "feat(chart): 图表系列解析式抗锯齿（vEdge + uAntialias）"
```

---

## Task 6: MSAA 配置项 + 回读拒绝守卫 + 文档

**Files:**
- Modify: `jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt`
- Modify: `jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/dsl/JFGL.kt`
- Modify: `CLAUDE.md`
- Rename: `jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/MsaaProbe.kt` → `MsaaVerifier.kt`

> 设计依据：规格 §2（两个开关）、§6.2（拒绝守卫）、§7.2（判据）、§7.6（探针的去留）。

- [ ] **Step 1: `MsaaProbe` 改造成 `MsaaVerifier`**

- 文件重命名；`@JvmName("main")` 保留（JVM 入口仍是 `MsaaVerifier`）。
- **修掉快照的混淆项**：`snapshotPhase` 现在按**逻辑尺寸**取快照（892×692），
  那会对设备分辨率的纹理重采样，**重采样自己会产生中间值**。改成按设备缩放取：
  ```kotlin
        val sp = SnapshotParameters()
        val s = bridge.deviceScale(node)
        sp.transform = javafx.scene.transform.Scale(s, s)
        val img = node.snapshot(sp, null)
  ```
- **加上四条断言 + 退出码**（现在是纯打印）：`msaa=4` 的过渡像素 > `msaa=0` 的，
  且两者的**线心行纯色像素数相等**（与 Task 4 同构）。
- 跑法变成跑两次（`-Djfgl.probe.msaa=0` 与 `4`），**由一个 shell 脚本比对**——
  一个进程只能有一个 msaa 值。脚本里用 `trap ... EXIT` 保证清理。

- [ ] **Step 2: `FXGLTransfer` 加回读拒绝守卫**

```kotlin
    /**
     * 当前画布能否用 `glReadPixels` 回读像素。**`msaa > 0` 时不能。**
     *
     * <p>理由已实测：多采样帧缓冲上 `glReadPixels` 直接返回 `GL_INVALID_OPERATION`，
     * 读出来是全 0（不是"读到旧帧"、也不是"读到黑"——是**那次调用整个非法**）。
     *
     * <p>本仓库的全部七个像素校验器都靠它回读。**没有这条守卫，它们会读回全 0
     * 然后报一大堆"画面全黑"式的假失败**——那比"明确拒绝"坏得多：
     * 假失败会让人去查渲染，而真因在配置。
     */
    val canReadPixels: Boolean get() = msaa <= 0
```

（`msaa` 现在是构造参数，要提成 `private val` 字段。）

- [ ] **Step 3: 校验器入口加断言**

七个校验器（`PipelineVerifier` / `PathVerifier` / `PickVerifier` / `ClickVerifier` /
`TextVerifier` / `ChartVerifier` / `FftVerifier`）**每个**在 `start()` 里加：

```kotlin
        check(bridge.canReadPixels) {
            "本校验器靠 glReadPixels 回读像素，而画布是 msaa=${'$'}{...} 的多采样 FBO——" +
                "多采样 FBO 上 glReadPixels 是非法操作（实测 GL_INVALID_OPERATION），" +
                "读数会全是 0。请用 msaa=0 跑（默认值就是 0）。"
        }
```

**七个都要加**，且**每一条都要有毒**：漏一个的表现是"那个校验器在 msaa>0 时报一堆假失败"。

- [ ] **Step 4: DSL 暴露**

`jfgl { }` 加：

```kotlin
    /**
     * 抗锯齿配置。**`msaa` 只能在建窗口时给**——采样数是帧缓冲的属性，
     * `GLCanvas` 没有 setter（实测）。
     *
     * <p>`msaa > 0` 时**像素回读会失效**（见 `FXGLTransfer.canReadPixels`），
     * 所以它默认 0：本仓库的七个像素校验器都靠回读。
     * 想要细线质量请用 `Gc.antialias`（运行期、且不牺牲回读）。
     */
    fun antialias(block: AntialiasConfig.() -> Unit) { ... }
```

- [ ] **Step 5: 文档**

`CLAUDE.md`：
- 顶点格式那一行 `24 字节（VertexFormat）` → **`32 字节`**，并在属性表里加
  `vec2 aEdge`(24)（横向、沿向）。
- 「未实现 / 待办」里那条"抗锯齿：MSAA"的设计叙述要**改成实测结论**：
  MSAA 能用、**只能在构造时配**、**`msaa>0` 时七个像素校验器全部失效**。
- 加一条"怎么验证改动"的入口：**改抗锯齿路径后跑 `PipelineVerifier` 的 ★ 抗锯齿一节**。

- [ ] **Step 6: 全量回归**

```bash
mvn -o clean test
mvn -o install -DskipTests
```
Expected: 370 通过 / 0 失败 / 2 跳过。

再逐个跑七个校验器（**都在 `msaa=0` 下**）+ demo 自检，全部退出码 0。

- [ ] **Step 7: 提交**

```bash
git add -A jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/ \
              jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/glview/ \
              jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/dsl/ \
              CLAUDE.md
git commit -m "feat(aa): MSAA 配置项 + 回读拒绝守卫；MsaaProbe 改造成 MsaaVerifier；文档"
```

---

## 自检清单

- [ ] 每个碰了 `jfgl-render-gl` / `jfgl-core` 的 Task 结束前都 `install` 过
- [ ] Task 1、2 **不改变画面**（跑过 `PipelineVerifier` 确认逐像素不变）
- [ ] Task 3 完成后 `PipelineVerifier` 在**默认（AA 关）**下仍退出码 0
- [ ] 两个开关**默认都关**
- [ ] 七个校验器在 `msaa=0` 下全绿；`msaa>0` 时**明确拒绝**而不是报假失败
- [ ] 变异注入全部用最小 token 改动；还原后 `cmp` 逐字节核对
- [ ] 临时改动（探针、种子、变异）全部删净；残留 JVM = 0；没抢前台、没截屏
- [ ] 提交信息中文且说明了"为什么"
- [ ] **规格 §4.3 那条"细带可能偏厚"的结论**：Task 4 Step 3 若失败，
      按那里的路径走（`vec2` → `vec3`），**不要调容差**；若通过，把实测墨量写进规格
