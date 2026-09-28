# Xuan 抗锯齿（子项目 A）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让细线、描边与图表系列在斜向处不再有阶梯状锯齿，且抗锯齿可配置（MSAA 构造时、
解析式 AA 运行期）。

**Architecture:** 给顶点格式加一条 `vec2 aEdge`（横向、沿向），由 `StrokeGenerator` 在生成
描边轮廓时顺带算出；片元用 `fwidth` 求屏幕空间导数做覆盖率羽化。**填充不做解析式 AA**
（三角形汤里"内部顶点到边界的距离"没有定义），交给 MSAA——两者互补。
**两个开关默认都关**，因此既有 370 条单测与七个校验器的精确像素期望全部不动。

**Tech Stack:** Java 21 / Kotlin 21 / `#version 330 core` GLSL / LWJGL 3.3.6 + openglfx。

**设计依据：** `docs/superpowers/specs/2026-09-26-xuan-antialias-design.md`。
术语、取舍理由、证据强度分级、实测数据都在那里，**本计划不重复论证**；
下面对每个 Task 只写"照哪一节"。

---

## 本仓库的元规则（先读，它们每一条都踩过）

1. **`xuan-render-gl` 的改动必须 `install` 才能被校验器看到。**
   `xuan-javafx` 的校验器是用 `-f xuan-javafx/pom.xml` 跑的，`xuan-render-gl` 从**本地仓库**
   解析。改了它却只 `compile`，跑出来的仍是**旧版本**——而输出会**完全一致地失败**，
   看起来像校验器"飘"。**每个碰了 `xuan-render-gl` 的 Task 结束前都要**：
   ```bash
   mvn -o install -DskipTests -pl xuan-render-gl -am
   ```
   （`xuan-javafx` 自己的改动不必 install——那是当场编译的。）
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
| `xuan-render-gl/.../renderer/VertexFormat.java` | stride 24→32，加 `OFFSET_EDGE`，改布局文档 |
| `xuan-render-gl/.../renderer/VertexWriter.java` | 加带 `aEdge` 的 `vertex` 重载；旧的委托给它 |
| `xuan-render-gl/.../renderer/RenderBatch.java` | VAO 第 5 个属性；顶点着色器加 `vEdge`；片元加羽化 |
| `xuan-core/.../geom/StrokeGenerator.java` | 并行输出 `edges`（横向、沿向）与 `rawEdges()` |
| `xuan-render-gl/.../renderer/Gc.kt` | `antialias` 进样式栈；描边几何外扩 1 像素；把 `aEdge` 写进顶点 |
| `xuan-render-gl/.../chartrender/SeriesShaders.java` | 系列片元加羽化；顶点着色器输出 `vEdge` |
| `xuan-render-gl/.../chartrender/*SeriesRenderer.java` | 传 `uAntialias` |
| `xuan-render-gl/.../test/renderer/GcAntialiasTest.java` | **新建**：`antialias` 进样式栈的单测 |
| `xuan-javafx/.../example/PipelineVerifier.kt` | 新增"★ 抗锯齿"一节 |
| `xuan-javafx/.../example/ChartVerifier.kt` | 新增"★ 系列抗锯齿"一节 |
| `xuan-javafx/.../glview/FXGLTransfer.kt` | `msaa > 0` 时的回读拒绝守卫 |
| `xuan-javafx/.../dsl/Xuan.kt` | `antialias { msaa = N }` |
| `CLAUDE.md` | 顶点格式 24→32；MSAA 与像素校验器互斥 |

---

## Task 1: 顶点格式 24 → 32（纯 Java 数据层，零 GL）

**Files:**
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/VertexFormat.java`
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/VertexWriter.java`
- Test: `xuan-render-gl/src/test/java/com/bingbaihanji/xuan/renderer/VertexFormatTest.java`
- Test: `xuan-render-gl/src/test/java/com/bingbaihanji/xuan/renderer/VertexWriterTest.java`

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

Run: `mvn -o test -pl xuan-render-gl -Dtest=VertexFormatTest`
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
     * {@code y} 是到最近端帽的有符号沿路径距离（同样归一化）：**0 是端帽线、带内为正、带外为负**。
     * 片段着色器靠这个符号区分"在线内"与"在线外"（见设计文档 §4.3 的 {@code 0.5 + y/wa}）。
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
     * @param edgeAlong        沿向：到最近端帽的**有符号**距离 ÷ 半线宽
     *                         （0 = 端帽线，带内为正、带外为负；填充与文本写 0，
     *                          它们与描边靠 `fwidth == 0` 区分，**不是**靠符号）
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

Run: `mvn -o test -pl xuan-render-gl -Dtest='VertexFormatTest,VertexWriterTest'`
Expected: PASS。

- [ ] **Step 8: 跑全量单测 + install**

```bash
mvn -o test
mvn -o install -DskipTests -pl xuan-render-gl -am
```

Expected: `Tests run: 159 + 211 = 370, Failures: 0, Errors: 0, Skipped: 2`（条数不变），
`BUILD SUCCESS`。

> ⚠️ 此刻**必须**跑一次 `PipelineVerifier` 确认画面**逐像素没变**——stride 改了、
> 而属性指针也跟着改了，接错一个偏移就会全画面错乱：
> ```bash
> mvn -o -f xuan-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
>   "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt"
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
git add xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/VertexFormat.java \
        xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/VertexWriter.java \
        xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/RenderBatch.java \
        xuan-render-gl/src/test/java/com/bingbaihanji/xuan/renderer/VertexFormatTest.java \
        xuan-render-gl/src/test/java/com/bingbaihanji/xuan/renderer/VertexWriterTest.java
git commit -m "feat(render): 顶点格式 24→32，加 aEdge(横向,沿向)；行为逐位不变"
```

---

## Task 2: `StrokeGenerator` 输出 `aEdge`（纯计算 + 单测）

**Files:**
- Modify: `xuan-core/src/main/java/com/bingbaihanji/xuan/geom/StrokeGenerator.java`
- Test: `xuan-core/src/test/java/com/bingbaihanji/xuan/geom/StrokeGeneratorTest.java`

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

Run: `mvn -o test -pl xuan-core -Dtest=StrokeGeneratorTest`
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
    private float[] edges = new float[triangles.length];

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

> **★★ 2026-09-26 执行期修正（两处计划错误，都由执行者发现）：**
>
> **1. `edges` 的长度与 `triangles` **恒等**，不是 `triangles.length / 2`。**
> 一个三角形在两个数组里**都占 6 个 float**（`triangles`：3 顶点 × 2 坐标；
> `edges`：3 顶点 × 2 分量）。原计划的 `new float[3 * 6 * 2]`（=36，而 `triangles` 是 72）
> 是**把"每顶点 2 个分量"误当成了"每三角形 2 个分量"**——照它写必然半量越界，
> 而且那条不变量在第一次扩容后就不成立。**两个数组一起翻倍即可。**
>
> **2. Step 7 那张变异表的第一行，用 Step 1 给的那条测试是抓不住的。**
> `水平直线段的横向边距是正负一` 用 `min`/`max` 对**整个顶点集合**取值——
> 把 `+n` 侧写成 `-1`、`-n` 侧写成 `+1` 之后集合仍是 `{+1, -1}`，**逐位不变**。
> 执行者实测：该变异**第一轮 25/25 全绿**（存活）。
> **修法**：在同一个测试方法内补**位置相关**的断言（`y > 0 ⇒ +1`、`y < 0 ⇒ -1`），
> 补完立刻倒。**这是与 `CLAUDE.md` 里"橡皮图章断言"同一类的东西，
> 而它又一次是在变异验证里被逼出来的——不是读代码看出来的。**
>
> ★ 规格审查者**独立复验**了这一点，证据比"第一轮存活"更硬：变异下**只有新补的
> 位置相关断言**（`:485`）倒下，而**旧的 `min`/`max` 两条断言（`:470/471`）照样通过**
> ——这就是"集合 `{+1,-1}` 在符号翻转下逐位不变"的直接实验证据，不是推断。
>
> **★★ 3.（2026-09-26 追加）Step 1 给的 `边距数组长度与三角形对齐且不含NaN` 名不副实——
> 那是我的测试体写错了。** 它只断言 `e.length >= triangleCount() * 6`，
> **从未与 `rawTriangles().length` 比较**；而且它给的几何只出 6 个三角形（36 float），
> **连初始容量 72 都没撑到**，所以"扩容后是否仍对齐"根本没验。
> 名字声称了它没做的事 —— 本仓库最防"名字与判据不一致"。
> 已要求补：**逐字相等**（不是 `>=`）+ **用能撑破初始容量的几何强制走扩容路径**。
>
> **★★ 4.（2026-09-26 追加）`rawEdges()` 的"不复制"契约没有任何测试守着。**
> 审查者注入"返回副本"的变异 ⇒ **25/25 全绿存活**。
> 而它是**热路径**接口（Task 3 起 `Gc.emitTriangles` 每帧调它），
> 整条路径之所以有 `rawTriangles()` 就是为了省掉那次整表复制。
> 将来有人"顺手"让它返回副本 = **每帧一次静默分配，画面上毫无症状**。
> 同族的 `rawTriangles` 有 `assertSame` 测试，`rawEdges` 没有 ⇒ 已要求补一条同形的。
>
> **5.（记一句，不改代码）** `edges = new float[triangles.length]` **依赖字段声明顺序**
> （`triangles` 必须在它之前）。调换顺序 ⇒ `edges` 长度 0，扩容那句 `edges.length * 2`
> 仍是 0 ⇒ 立刻 AIOOBE。风险低，加一句注释说明即可。
>
> **★ 审查者这次用的两种验证手法值得复用**（比"跑一遍测试"强得多）：
> ① **把改动前后的实现同时编译、逐顶点比对**（本次 6008 个用例，最大偏差 `0.0`）
> ——"行为不变"当成**逐位**结论来验，而不是靠抽样；
> ② 变异注入**自己写脚本 + 逐条 `cmp` 核对还原**，并注意 `core.autocrlf=true`
> 使"`git diff --quiet` 通过"**不等于**逐字节相同。
>
> **★★ 6.（2026-09-26 代码质量审查追加）沿向那三条断言犯了与横向**同一个**错。**
> 它们全是 `min`/`max` 口径 ⇒ 两条变异存活：**M13**（段远端沿向复用近端 ⇒
> 三角形内沿向恒定 ⇒ `wa = 0` ⇒ 沿向 AA 全灭）、**M14**（`arc += 0f`，
> 且因测试用等长两段 ⇒ `aEnd` 恰好与原值重合 ⇒ min/max 一字不变）。
> 修法与横向同形：**把沿向与顶点位置绑起来断言**（`x=0` 那排 0、`x=30` 那排 6、
> `x=100` 那排 0，并断言中段插值与端点不同）。
>
> **★★ 7. `emitCap` / `emitArc` 的边距取值**一条断言都没有**，而它们生产里到不了**
> ⇒ 永久不可验（五条变异全存活）。KDoc 标了"生产无消费者"，但没标"边距取值没有断言"
> ——那是**另一个**缺口。补法：按值断言（SQUARE 外边 `-1`、ROUND 尖端 `-1` 两端 `0`
> 圆心 `0`、圆角盘沿向恒 `alongBase`），期望值**自己从几何推**并在注释里写出推导。
>
> **★ 8.** KDoc 里"段内线性 ⇒ 插值沿向恰好等于'到最近端帽的沿路径距离'"**是假的**：
> 实测 `(0,0)→(30,0)→(100,0)` 在路径中点插值得 `3.0`，真值 `7.0`（偏小一倍多）。
> 按 §4.3 的公式今天不算错（着色器只在乎 `y=0` 等值线的位置与 `wa > 0`），
> 但那句话是"沿向语义"的唯一出处、也是 Task 4 手算期望值的依据 ⇒ 必须改口为
> "只在端帽附近约一个半线宽内与真实距离一致，中段偏小，而着色器不依赖中段绝对值"。
>
> ⚠️ **上一条里那个数字我先是写错又改对的，值得留痕**：第一轮质量审查报的是 `13.0`，
> 我**没核算术就抄进了提交信息**；复审者复算指出那条几何的**全长是 100**（段长 30 与 70），
> 中点处到最近端帽是 `min(65, 35)/5 = 7.0` —— `13.0` 是拿错的全长（130）算的。
> **实质结论不变**（插值 ≠ 真值、中段偏小），但**"转述一个数字"与"核过这个数字"是两件事**，
> 而我把前者当成了后者。这正是本仓库那条"按契约推断 vs 实测结论要分开标注"的同族。
>
> **★★ 9.（2026-09-26 复审追加）`capExtension` 的第一版修复自己引入了两个新缺口——**
> 两者的**共同形态**是"**断言的话比它实际证的强**"，值得记成一条独立教训：
> - **外扩四边形的两端只钉住了一端**：那条用例用一个 `sawOuter` 布尔量，
>   而它**只要任意一端外扩就为真**。变异"终点不外扩"与"起点不外扩"**各自全绿**。
>   而 C1 的可见后果**正是两端各长出一像素** ⇒ 只钉一端等于一半没钉。
>   **修法**：两端各一个计数器/断言。
> - **`strokeDashed(…, capExtension)` 这个新加的 11 参重载零调用点、零测试**：
>   变异"把它悄悄置 0"⇒ 35 条用例全绿。它与 I2 那条同族（"生产里到不了 ⇒ 永久不可验"），
>   只是**这次是新加的 API 面**。新加公开 API 时，"有没有断言"要与"有没有消费者"分开问。

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

Run: `mvn -o test -pl xuan-core -Dtest=StrokeGeneratorTest`
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
mvn -o install -DskipTests -pl xuan-core -am
```
Expected: 370 通过 / 0 失败 / 2 跳过。

- [ ] **Step 9: 提交**

```bash
git add xuan-core/src/main/java/com/bingbaihanji/xuan/geom/StrokeGenerator.java \
        xuan-core/src/test/java/com/bingbaihanji/xuan/geom/StrokeGeneratorTest.java
git commit -m "feat(geom): StrokeGenerator 输出 aEdge（横向/沿向），行为不变"
```

---

## Task 3: 描边片元羽化 + `Gc.antialias` + 几何外扩

**Files:**
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/RenderBatch.java`
- Modify: `xuan-render-gl/src/main/kotlin/com/bingbaihanji/xuan/renderer/Gc.kt`

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

> **★★ 2026-09-26 执行期修正：上面那段只做了"横向"外扩，还差"沿向"——那半边不能省。**
>
> 写计划时我只想到把描边带**加宽**（`lineWidth + 2*px`）。但 §4.1 要求的是
> **两个方向都外扩**：横向外扩让**长边**有外侧片元，**沿向外扩让端帽的端边有外侧片元**。
> 少了后者，平头端那一条边仍然推不到 0.5 以下 —— 也就是**长边有 AA、两端还是硬角**，
> 正是"平头端"这个设计理由要解决的那件事。
>
> **做法**：开放路径在两端各**延长** `px`（沿首段/末段方向），把延长后的折线交给
> `strokeGenerator.stroke(...)`。**闭合路径不延长**（它没有端帽）。
>
> **★★ 但"延长折线"这个做法是错的——2026-09-26 代码质量审查实测推翻了它。**
> 我原来在这里写："延长出去的那两点弧长越过全长 ⇒ `alongAt` 自动给出负值 ⇒
> 不需要任何特判"。**实测为假**：`alongAt` 里的 `全长` 是**所传折线**的全长，
> 延长点成了新的端点 ⇒ 它们的 `min(arc, 全长−arc)` **恒为 0**；
> 而真实端线反而拿到 `+e/half`。**任何折线的顶点弧长都不可能超过它自己所在折线的全长**，
> 所以那个条件永远不成立。
> **后果是可见的**：`y = 0` 的等值线落在**延长后的外缘**，整圈 fringe 都在 ≥ 0.5 一侧
> ⇒ 端帽端边**拿不到羽化**，同时描边**在两端各长出约 1 个设备像素**。
>
> **正确接法（已改到 Task 2 里去实现，本 Task 只负责传值）**：
> `StrokeGenerator.stroke(...)` 收一个 **`capExtension`**（局部单位，**默认 0**），
> 非闭合 + `Cap.BUTT` + `capExtension > 0` 时由**它自己**发射一段外扩四边形——
> 真实端线沿向 `0`、外缘沿向 `-capExtension/half`、横向 `±1`。
> **折线不再被延长**，`alongAt` 的口径也不用改。
>
> 所以本 Task 这一步只剩：
> ```kotlin
> // 1 设备像素折成局部单位（matrixScale 的方向要在实现时核实）
> val px = if (antialias && !closed) 1f / matrixScale().coerceAtLeast(1e-6f) else 0f
> strokeGenerator.stroke(points, count, closed, lineWidth + 2f * px,
>         Cap.BUTT, Join.MITER, MITER_LIMIT, ROUND_SEGMENTS, px)   // ← 末尾多一个 capExtension
> ```
>
> **它必须有断言**：一条 4px 横线的**左右两端**也要出现在 Task 4 的"过渡像素"计数里——
> 只数长边的话，"端帽外扩漏了"会静默通过；
> 而**"描边没有变长"**也要断言（沿向外扩做错时的症状恰恰是"线长了一像素"）——
> 用两条同长不同 AA 开关的线，断言它们的**墨量相等**（§7.1 的判据 ④ 已覆盖）。

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

新建 `xuan-render-gl/src/test/java/com/bingbaihanji/xuan/renderer/GcAntialiasTest.java`：

```java
package com.bingbaihanji.xuan.renderer;

import com.bingbaihanji.xuan.gl.FakeGLAbstraction;
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
mvn -o compile && mvn -o install -DskipTests -pl xuan-render-gl -am
mvn -o -f xuan-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
  "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt"
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
git add xuan-render-gl/src/main/java/com/bingbaihanji/xuan/renderer/RenderBatch.java \
        xuan-render-gl/src/main/kotlin/com/bingbaihanji/xuan/renderer/Gc.kt \
        xuan-render-gl/src/test/java/com/bingbaihanji/xuan/renderer/GcAntialiasTest.java \
        xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/PipelineVerifier.kt
git commit -m "feat(render): 描边解析式抗锯齿（Gc.antialias，默认关）+ 几何外扩 1 像素"
```

> **上面这份 `git add` 清单在原计划里是错的**（2026-09-26 执行期修正）：它漏掉了
> 本 Task 新增的两处——`GcAntialiasTest.java`（Step 6）与 `PipelineVerifier.kt` 里的
> `aEdge` 偏移探针（Step 8）。**按这份清单加。**

---

## Task 4: `PipelineVerifier` 的 AA 变体 + 变异验证

**Files:**
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/PipelineVerifier.kt`

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
mvn -o -f xuan-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
  "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt"
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
git add xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/PipelineVerifier.kt
git commit -m "test(render): 抗锯齿的四条像素判据（过渡带/线心/墨量守恒）+ 六条变异"
```

---

## Task 5: 图表系列的 `vEdge` + `uAntialias`

**Files:**
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/SeriesShaders.java`
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/LineSeriesRenderer.java`
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/ScatterSeriesRenderer.java`
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/StepSeriesRenderer.java`
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/AreaSeriesRenderer.java`
- Modify: `xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/BarSeriesRenderer.java`
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/ChartVerifier.kt`

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
| `SCATTER_VERTEX` | 居中四边形的 x（0/1） | 居中四边形的 y（0/1） | `aCorner * 2.0 - 1.0`（两个轴**各自**一个分量） |
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
mvn -o install -DskipTests -pl xuan-render-gl -am
mvn -o -f xuan-javafx/pom.xml compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
  "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.ChartVerifierKt"
```
Expected: 退出码 0，含新增的一段。

- [ ] **Step 6: 提交**

```bash
git add xuan-render-gl/src/main/java/com/bingbaihanji/xuan/chartrender/ \
        xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/ChartVerifier.kt
git commit -m "feat(chart): 图表系列解析式抗锯齿（vEdge + uAntialias）"
```

---

## Task 6: MSAA 配置项 + 回读拒绝守卫 + 文档

**Files:**
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/glview/FXGLTransfer.kt`
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/dsl/Xuan.kt`
- Modify: `CLAUDE.md`
- Rename: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/MsaaProbe.kt` → `MsaaVerifier.kt`

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
- 跑法变成跑两次（`-Dxuan.probe.msaa=0` 与 `4`），**由一个 shell 脚本比对**——
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

`xuan { }` 加：

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
  MSAA 能用、**只能在构造时配**、**`msaa>0` 时★ 六个**像素校验器失去回读能力**。
  （★ 我在这里原写"七个"——**过度概括**：`FftVerifier` 读的是 SSBO 不是画布 FBO，
  实测 `msaa=4` 下 50 条全过、输出逐行相同。**守卫仍挂七个**，但其中一个的必要性
  是"纪律"而非"实测"——**"统一挂"与"必要性相同"是两件事**。）
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
git add -A xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/ \
              xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/glview/ \
              xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/dsl/ \
              CLAUDE.md
git commit -m "feat(aa): MSAA 配置项 + 回读拒绝守卫；MsaaProbe 改造成 MsaaVerifier；文档"
```

---

## Task 3 执行期修正（2026-09-26，**本计划的三处错误 + 一条可复用的判据**）

> **★★ 1. Step 5 的归一化公式写反了：是**乘**，不是除。**
> 我写的是 `ec = edges[i] / halfWidth`（`halfWidth = lineWidth * 0.5f`）。
> **错因**：`rawEdges()` **已经按它收到的那条线宽**归一化过了，而 Step 4 让 `Gc` 传进去的是
> **加宽后**的线宽 ⇒ 生成器的 `±1` 落在**外扩后**的外缘上。要换算回真实半线宽，
> 倍率是 **`几何半宽 / 真实半宽`（乘）**。
> **后果（实测）**：真实外缘落在 `|x| = 1/3` ⇒ 覆盖率整片为正 ⇒ **外扩带全亮、边缘仍是硬的**
> ——一条 4px 的线画成 **6px 实心带**。而**既有的 26 条判据一条都不倒**。
>
> **★★ 一条可复用的判据（执行者提出，比这次的具体修法更值钱）**：
> **任何"把某个量换算到另一套单位"的公式，都应当存在一个输入使它是恒等变换；
> 拿那个输入去验，不需要任何实验就能判对错。**
> 本次的实例：`antialias = false` 时 `px = 0`、两个半宽相等 ⇒ 任何正确的公式在那里**必须是恒等**；
> 而 `edges[i] / realHalf` 在 px=0 时给出 `±1/2` ⇒ **必错**。
> 这条判据**先于任何变异实验**就把错误定死了。
>
> **★ 2. Step 4 的 `!closed` 把**横向**外扩也一起挡掉了。**
> 我写成 `val px = if (antialias && !closed) …`，而**横向**外扩对闭合路径同样需要
> （矩形/圆的描边长边也要有外侧片元）；只有**沿向**（`capExtension`）才与端帽绑定。
> 按我给的写法，闭合路径的长边只拿到"半边"羽化。
> 正确写法是**两个量分开**：`px` 只看 `antialias`，`capExt = if (closed) 0f else px`。
>
> **★★ 3. MITER 拐角在 AA 开时会**缺口**——这是"用一个标量描述边界"的固有不足。**
> `emitJoin` 给接头三个顶点横向取 `s = ±1`（本意"尖角在内部，取 s 让它完全覆盖"），
> 而覆盖率公式在 `|x| = 1` 处给的是 **0.5** 不是 1 ——**那个本意从来没实现过**。
> 而 `Gc` 那一步乘法对**所有**顶点生效 ⇒ 接头顶点被乘成 `1.5` ⇒ 覆盖率 **0**
> ⇒ **风筝形外侧整圈透明 = 每个拐角被啃掉一块**。
> **修法**：接头三角形的横向一律取 **`0f`** ⇒ `fwidth == 0` ⇒ 走"完全覆盖"分支
> ⇒ 接头不透明、外缘是**硬边**。**取 0 才是真正达成那个本意的写法，且与缩放倍率无关。**
> **降级照实记**：miter 外缘**没有羽化**（硬边）。三个选项——硬边 / 半透明边 / 缺口——
> 里硬边是唯一"画对了形状"的。真要羽化 miter，需要每条边各一个距离分量
> （`aEdge` 从 `vec2` 扩到 `vec3` 以上），**当前没有证据说明它值得**（Task 4 量了再定）。
>
> **★ 4. `matrixScale()` 的方向无法用变异验证。** 本机恒等变换下 `1 * x` 与 `1 / x` 都是 1，
> 注入变异后读数一字不变。它只有"读定义（`Flattener` 的 Javadoc 写明单位）+ 读另一个消费者"
> 这一层保障——**照实说，别写成"已验证"**。
>
> **★ 5. 规格 §4.3 那条"细带可能偏厚、可能要扩成 vec3"的担心，在归一化正确之后不成立。**
> 执行者实测：线心墨量 `0.75/1/1/1/0.25 = 4.00 px` **精确守恒**；
> 线宽 1 的细带在"线心落在像素边界上"与"落在像素内部"两种相位下都**恰好 `1.0 px`**。
> **那条担心是错误归一化的产物。**（仍要等 Task 4 的四条判据确认，届时写进规格。）
>
> **★ 6. Step 8 探针的设计理由值得留**：执行者**没有**"在校验器里换一份着色器"，
> 理由是那条 `location 4` 的指针由 `RenderBatch.configureVAOAttributes()` 建立、
> VAO 与程序都是**私有**的 ⇒ **校验器自己搭一套只能证明它自己接对了，证不了生产路径**
> ——那恰好绕过了这一步要守的东西，等于橡皮图章。它改用生产着色器里的 `uProbe` uniform
> （初值 0、用完立刻关回、只占一整帧）。**这个取舍判得对。**
> **变异证据**：`OFFSET_EDGE` 24→20 ⇒ 探针全塌成 127、**9 条探针断言倒**，
> 而**既有 26 条照过、3084 照过** ⇒ 精确复现了 Task 1 那个"接错偏移画面逐像素不变"的盲区。

---

## Task 3 收尾期追加（2026-09-26）

> **★★ 7. 变异必须**点名有梯度的那一处**——尖角三角形注变异打不倒任何东西。**
> 执行者第一轮变异注在 **miter 尖角**三角形上，校验器全绿。**不是校验器弱，是那里本来就没错**：
> 尖角三角形的三个顶点横向**相同** ⇒ `fwidth(cross) == 0` ⇒ 它**在改动之前就已经走
> "完全覆盖"分支**。缺口只出在**底边**那一半（它的横向从 0 变到 `s`，才有梯度）。
> **⇒ Task 4 的变异表若写这条，必须点名"底边那一处"。**
> **一般化**：**"改一个值"不等于"改出梯度"**——判据来自导数的地方，
> 逐顶点一致的值怎么改都是常量。写变异时要先问"这一改有没有改变**梯度**"。
>
> **★★ 8. 取 0 之后拐角外沿比真实轮廓胖约 1 像素（实测，已批准修）。**
> 接头几何**也**被外扩过（用的是同一个 `half`），而它现在恒不透明 ⇒ 外扩那一圈变成实心：
>
> | 位置 | 真实覆盖率 | 修复前实测 |
> |---|---|---|
> | 真外缘外 0.25px、**直边** | 0.25 | `0.25` ✓ |
> | 真外缘外 0.25px、**拐角** | 0.25 | **`1.0`** ✗ |
> | 外拐角对角线像素 | 0.0625 | **`1.0`** ✗ |
>
> **修法（执行者提出并已分析过、我批准）**：`stroke(...)` 再加一个"**接头半宽**"参数
> （`joinHalf`，默认 = 线宽的一半 ⇒ 既有调用点零改动），`Gc` 传**真实**半线宽
> ⇒ **接头用未外扩的几何**。
> 不会在交界处留缝（相邻段四边形被外扩、接头不被外扩，两者并集恰好覆盖真实墨迹 + 该有的 fringe），
> 也不需要改 `alongAt`（沿向只由弧长决定，与顶点位置无关）。
> **这对 `strokeRect` 这种常用操作是可见的**（矩形四角胖 1px 且硬边），所以不是"可以留着的偏差"。
> **回归检查**：修完之后**直边**那一行必须**仍是 `0.25`**（不能跟着变）——那是"没改坏"的判据。
>
> **★ 修完的实测值（2026-09-26，含一处**我预期错了**的地方）**：
>
> | 位置 | 真实覆盖率 | 修前 | 修后实测 |
> |---|---|---|---|
> | 直边外缘外 0.25px | 0.25 | 0.25 ✓ | **0.25 ✓（未变，回归通过）** |
> | 拐角外缘外 0.25px | 0.25 | 1.0 ✗ | **0（背景）** |
> | 拐角对角线像素 | 0.0625 | 1.0 ✗ | **0（背景）** |
>
> **后两行读 0 而不是 0.25/0.0625，是结构性的**：接头横向恒 0 ⇒ 硬边
> ⇒ 真实轮廓外那些半覆盖像素（0.06~0.25）**既不在任何几何里、也没法被羽化出来**，
> 只能按像素中心规则整块取 0。**我原来期望它们变成 0.25/0.0625，是漏了这层量化。**
> **但"1 像素向外多画"确实没有了**，误差方向从"轮廓之外"翻到"轮廓之内"、幅度 1px → ≤0.5px。
> 直接证据：拐角那块 **4×4 现在 AA 开与关逐像素相同**。
> 要拿到拐角的 0.25 必须给接头每条边各一个距离分量（`aEdge` 扩到 `vec3`）——
> **规格 §4.3 明留的口子，当前没有证据说明值得做。**
>
> **★ 9. 两条"判据本身会不会恒真"的设计，判得好，留档**：
> - 闭合路径的验收读数**坐标刻意带 `.75`**（真外缘取整数的话，两侧像素要么全覆盖要么全不覆盖，
>   判据**恒真**）；
> - 拐角判据用"**有多少像素不是纯白**"而不是"有多少是背景"——只数背景的话，
>   **羽化那一类漏画法在它前面恒真**（实测底边变异时一个背景像素都没有，只有 9 个像素掉到 0.25）。

---

## Task 3 规格审查的发现（2026-09-26）

> **★ 1. 实现报告里 5 处"数字/症状与实测不符"——都不影响交付物，但有一条是代码里的错陈述。**
> - 「`!closed` 加回去 ⇒ 3 条倒」→ 实测 **1 条**；
> - 探针的失败信息里「…**或闭合路径不再外扩，本条都会倒**」——**后半句是假的**（实测该条照过）；
> - `RenderBatch` 的 KDoc 写「整片像素**变黑**」→ 实测是 **整片消失**（`NaN` → α=0，
>   预乘色的 rgb 也是 0 ⇒ 完全透明，背景透出来）。**症状描述正是下一个人会去搜的东西**，
>   按"变黑"去找会怀疑到别的问题上；
> - 规格字面的"端帽那排 `vEdge.y = 0`"没断言，换成了两条解析的 `±1/6`
>   （审查者判**判别力更强**：x 取整数是刻意的，端线上没有像素中心）——但要**在注释里写明**，
>   免得后人对照规格以为漏了一条；
> - 变异计数 9 vs 10（多的那条是新探针，也是新断言）。
>
> **★ 2. 一处未断言的副作用（不是缺陷，但必须写下来）**：
> **AA 开时描边的可拾取范围比可见线宽 1 像素**（ID pass 复用加宽后的顶点缓冲）。
> 它与 `CLAUDE.md` 已声明的"拾取只由几何决定，与颜色和透明度无关"以及
> "全透明图元仍可拾取"**一致**（几何确实宽了 1 像素），而且最外那 1 像素本来就半可见。
> **但它必须写进 `Gc.antialias` 的 KDoc**——用户会注意到"点在可见线外 1 像素也算命中"。
> **不要去改 ID pass 的几何**：那会让画面与拾取**不一致**，比现在更糟。
>
> **★ 3. `git checkout -- <file>` 在本仓库**不能**当还原手段。**
> 审查者实测：开工前的 `Gc.kt` / `VertexFormat.java` 是 **LF-only**，而 autocrlf 的 checkout
> 写出 **CRLF** ⇒ 还原不到开工前的字节。它靠保存的原始副本 `cp` 回去 +
> **`git hash-object` 与索引 blob 比对**才还原干净。
> 另记：`grep -c $'\r'` 数的是**行数**不是 CR 数，那个读数靠不住——**md5/hash-object 才是真相**。
> （这与 Task 2 那条"`git diff --quiet` 通过 ≠ 逐字节相同"是同一个根因。）
>
> **★ 4. 审查者自己的两次注入也是坏的，它自己发现并纠正了**：
> 第一次把 `OFFSET_EDGE` 改在 `RenderBatch`（`occurrences = 0` ⇒ 常量在 `VertexFormat`）；
> 第二次把 MITER 底边的三个顶点**全**改成 `s` —— 那是**真像素恒等**（横向变常量 ⇒ `fwidth == 0`），
> 所以 0 倒；改回 `(0,s,s)` 就复现了。**这与本计划上面"变异要点名有梯度的那一处"是同一条。**

---

## Task 3 代码质量审查的发现（2026-09-26）

> **★★ 1. 一类可复用的校验器缺陷：**打印的方块不是被断言的方块**。**
> `PipelineVerifier` 打印的是 `boxColors(295,95,299,99)`（16 格里只印 4 格），
> 而断言数的是 `countNotIn(297,97,301,101)` —— **另外 12 个被断言的像素永远不出现在报告里**。
> **实测后果**：某变异下这行报「实测 7 个不是纯白」，而**打印出来的两个 4×4 全是 `333333`/`FFFFFF`**
> ——那 7 个像素在打印窗口之外。**读日志的人会以为读数与断言互相矛盾。**
> 本仓库明写「失败信息可读恰恰是这些校验器存在的一半理由」⇒ 这不是排版问题。
> **一般化：校验器打印的窗口必须**覆盖**（最好**等于**）它断言的窗口**——
> 否则失败时报出的读数与断言**互相打架**，而那比不打印更坏。
>
> **★★ 2. 那条"无法用变异验证"的公式，错误模式也**不**在系统缩放上。**
> 注释原写「算反了的表现是外扩量随**系统缩放**跑偏」。**不会发生**：
> 恒等变换下 `matrixScale()` 对**任何**帧缓冲尺寸 / 系统缩放都返回 **1**
> （基础矩阵按"1 用户单位 = 1 设备像素"给）⇒ `1/matrixScale()` 与 `matrixScale()` 都是 1。
> **这正是它无法用变异验证的原因**，也说明那条路走不通。
> 它真正的入口是**画布变换的缩放**：`gc.scale(2f)` 下正确值 0.5 局部单位、写反是 2
> （= **4** 设备像素——`matrixScale() = 2`，1 局部单位 = 2 设备像素）。
>
> ⚠️ **这里原写的是"8 设备像素"，是错的**（多了一倍）。**而且它是"我抄了审查者给的数字
> 却没核算术"的第二次**：第一次是 Task 2 那个"13.0"（正确 7.0）。
> 两次都是同一动作——**把别人报告里的数字当既成事实转述**。
> 与本仓库那条"按契约推断 vs 实测结论要分开标注"同族：**转述 ≠ 核实**。
> ⇒ 注释改成"随**画布变换的缩放**跑偏"，并写明"**未验证**"。
>
> **★ 3. 一条未声明的降级（非等比缩放）**：`matrixScale()` 取两轴**平均**
> ⇒ 非等比缩放下"1 设备像素折成的局部单位数"不成立。`gc.scale(1f, 10f)` 时压缩轴上
> 外扩只有 0.18 设备像素 ⇒ 外侧像素中心拿不到片元 ⇒ **那条轴的 AA 退化成硬边**（单向，不画错形状）。
> 与「旋转 `clipRect` 实际生效的是轴对齐包围盒」**同类** ⇒ 照它的格式**声明**（含"未验证"）。
>
> **★ 4. 本地仓库残留变异在本轮被撞到**两次**。** 审查者只 install `xuan-render-gl` 时，
> `xuan-core` 里上一轮的变异还在 ⇒ 同一份源码先"全过"后"倒 1 条"，读数自相矛盾。
> 判据正是 `CLAUDE.md` 那条：**输出完全一致地失败 ⇒ 先怀疑产物**。
> ⇒ **本计划所有 Task 的收尾都要 install 受影响的**所有**模块，不是只装一个。**
>
> **★ 5. 探针里有一条**判别力为零**的读数**：探针二第 300 行那条（期望 128、实测 127 落在 ±4 内）
> 在"属性整体归零"时也照过。它不错（0 与"读到 0"本来就同值），但**要在注释里记一句**
> ——不然将来有人做变异时会以为它守住了什么。
>
> **★ 6. 覆盖缺口（记而不补）**：`lineWidth <= 0f` 守卫没有任何断言覆盖
> （七个校验器都没有该场景）。注释只说了理由 ⇒ 标注"**未验证**"即可。

---

## Task 4 实测结果（2026-09-26）

**四条判据**（45°、线宽 4；A = AA 关、B = AA 开，两者是整数平移 ⇒ 相位逐项相同）：

| # | 实测 | 出处 |
|---|---|---|
| ① | **0** | 精确 |
| ② | **400 = 4W** | 每列 4 个（`m = ±2` 与 `±3` 各两个），精确 |
| ③ | 关 **300** / 开 **300** | `3W`，两模式精确相等 |
| ④ | **115400** | 解析 `4√2 ⇒ 115399.8`，**相差 +0.2** |
| ④对照 | **102000 = 5W × 204** | 精确（硬边读数） |
| ④b（1px） | 四种组合**都是 81600 = 1.000 px/列**，比值 **1.0000** | `[0.9,1.1]` 没用上 |

> **★★ 1. ④ 原计划写的是 `aaOnInk == aaOffInk`——那是一条**恒假断言**，而且本来就不该成立。**
> 实测差 **11.6%**。根因是**硬边的本性**：45° 直线走**像素中心采样**，
> 只有 `|m| ≤ 2` 的像素中心落在真实带内 ⇒ 有效宽度 `2√2 ≈ 2.83` 而不是 4
> （**阶梯内接**于斜带，**系统性偏细**）；而覆盖率斜坡是**面积守恒**的。
> ⇒ **④ 的参照系必须是"真实面积"，不是对照组。**
> **一般化：拿"关掉新特性的那条路"当参照系，只有在"它俩本该给出同一个量"时才成立。**
> 这里关掉 AA 后**量本身就变了**（离散采样对斜边的系统性偏差），所以参照系必须是**解析真值**。
>
> **★★ 2. ② 原写的界 `≤ 线长 × 2` 会把**正确实现**判为失败。**
> 那个界隐含"每列 2 个过渡像素"——那是**轴对齐**线的情形；45° 下是 `4W`。
> **界写窄了与写宽了同样是缺陷**：写宽了藏真缺陷（蓝描边那条 5%），
> 写窄了**冤枉正确实现**。两者都源于"没把几何代进去算"，都属于"期望值没有出处"。
>
> **★ 3. 窗形状是关键**：必须让每一列**完整包住横截面**。方形窗会**少掉** `O(1)`（★ 见下）
> （`4√2·S−8.14` vs `4√2·S−8`）⇒ **精确相等就没了**。
> 而 `±2W` 那个界是**取整边界所需的最小值，不是随手留的余量**——
> 两个羽化等级的解析值落在 8 位取整边界**外 0.0004**，本机浮点噪声就推过去了。
>
> **★ 4. 非等比缩放那条（本 Task 新立）**：`gc.scale(1f, 10f)` 下
> **压缩轴真外缘之外 0 个片元**（几何只外扩 0.18 设备像素）、**拉伸轴 1 个**（覆盖率 0.25）。
> **两个相位都是挑过的**：斜坡只有 1 设备像素宽、像素间距也是 1 ⇒
> "之外有没有片元"是**相位决定的**。**只钉方向、不钉数值。**
>
> **★ 5. 九条变异的关键读数**：
> - 两处 `if (antialias)` → `if (false)` ⇒ **15 条倒**（★ 实现者报 14，质量审查实测 **15**——
>   漏了 `★ 非等比缩放 拉伸轴`；**我第四次把报告里的计数未经核实转述出去**），
>   且 `C ≠ A` 倒而 `A ≡ B` 照常通过 ✓
>   ——反证的有效性被独立证实（正是要的那个方向）。
> - `restore()` 里写死 `antialias = false` ⇒ **只倒 1 条**（值断言），`A ≡ B` 照过
>   ——因为 B 是在 restore **之后**画的。**与计划表的预期不同，但方向正确**：
>   那条变异确实被抓住了，只是由另一条断言抓的。
> - `restore()` 里**整行删掉** ⇒ 2 条倒（值断言 + `A ≡ B`）。
> - `edges` 恒传 `null` ⇒ 14 条；外扩量 → `0f` ⇒ 10 条；乘 → 除 ⇒ 9 条；
>   `abs(x)` → `x` ⇒ 5 条；`clamp` 的 `0.5` → `0.0` ⇒ 6 条（**② 照过**——
>   覆盖率整体内缩一档，过渡带仍在 ⇒ ② 对这条不敏感，**这是 ④ 存在的理由**）。
>
> **★ 6. `lineWidth <= 0f` 仍无断言覆盖**（与 Task 3 记的是同一条，仍未补）。

---

## Task 4 收尾：一条"无法验证"如何变成可验证（2026-09-26）

> **★ "变异验不出它"这句话有隐含前提——探针集合。**
> Task 3 曾记下：`px = 1/matrixScale()` 这条**方向**"**无法用变异验证**"
> （恒等变换下 `1*x` 与 `1/x` 都是 1）。**那句话在当时为真，但不是原理性的。**
> Task 4 立了 `gc.scale(1f, 10f)` 的探针之后，同一个变异**立刻可验**：
> 把 `1f / matrixScale()` 写成 `1f * matrixScale()` ⇒ **压缩轴那条断言倒**
> （真外缘之外多出 1 个片元，x=102），**其余 56 条照过**。
> ⇒ **写"未验证"时要连着写"在当前探针集合下"**，否则下一个人会把
> "暂时没测"读成"测不了"，而两者之间隔着一个探针的距离。
>
> **★ 连带收掉两处**（同一文件、同一类假陈述，且都是**本 Task 自己把它们变成假的**）：
> - `Gc.antialias` 的 KDoc 里"未验证…没有任何断言盖着非等比缩放" ⇒ 改成"由非等比缩放探针
>   钉住**方向**"，并**照实写证据强度**（只钉方向不钉数值；两个相位是**挑过的**——
>   斜坡只有 1 设备像素宽、像素中心间距也是 1 ⇒ "真外缘之外有没有片元"是**相位决定**的，
>   挪动线的位置就要重新核这两个相位）；
> - `strokeOutline` 里 `px` 方向那段"变异注入验不出它" ⇒ 同上（已实测可验）。
>
> **★ 仍未补**：`lineWidth <= 0f` 的覆盖缺口（七个校验器都没有该场景）——标注"未验证"即可。

---

## Task 4 修复轮的发现（2026-09-26）

> **★★ 1. Kotlin 字符串模板里 `$var + CONST` 不会插值——而它的输出**看起来是对的**。**
> `println("窗 x∈[$wX0Off,$wX0Off + AA_W) …")` 实际印出 `x∈[150,150 + AA_W)`：
> `$wX0Off` 之后被当成**字面量**。它字面读还像"空窗 `[150,150)`"。
> **这与上一轮那条"打印 4 格、断言 16 格"同族**：失败时读数与断言对不上；
> 而它**没被察觉的原因**是断言本身没受影响（y 界印对了、整窗直方图也是真窗口）。
> **可复用的回归检查**：`grep '\$[A-Za-z_]\w* [+-] '`（本次修完后全文 0 处；
> 那条注释本身也被改写成不含该模式，好让这个 grep 以后还能当检查用）。
>
> **★★ 2. 相位 .50 下那对 1px 线**逐像素相同**——它对"AA 是否生效"**天然无法作证**。**
> 1px 线的中线正好落在像素中心时，覆盖率斜坡的采样**只取 `{1, 0}` 两个端点值**，
> 一个中间值都不产生 ⇒ AA 开与关**逐像素相同**。
> ⇒ `on.fringe > 0` 那条断言**只加在相位 .25 上**（实测过渡 800）；
> 相位 .50 保留原样，钉的是"开 AA 不该把一条本来就对齐的线画歪"，
> **不承担**"AA 生效"的举证。
> **变异证实**：两处 `if(antialias)`→`if(false)` ⇒ `PASS=43 FAIL=15`，
> **新加的那条 FAIL**，而**两条 `④b 墨量比` 照常 PASS** —— 它填的正是那个盲区。
> **一般化**：**一条判据能不能为某件事作证，取决于它的几何是否落在该现象的"非退化"区间里。**
> 退化相位下的断言不是错的，但它**作不了那个证**——而它看起来完全一样。
>
> **★ 3. 量化界要给"两边都反向"的极端留位。** `inkTol = 2W + 2` 里的 `+2` 的来路：
> 单像素实测能偏到 `+0.5004`，真正的界是 `2W + 4×0.0004×W = 200.16`；
> 取 202 是为了同时容住"两档都反向"（−199.8）与"两档都正向"（+200.2）。
> **界太紧会把正确实现判失败**——与"界太宽会藏真缺陷"是同一件事的两面。
>
> **★ 4. 我第三次把报告里的事实未经核实转述进持久文档**（见上面的更正）。
> **改法**：子代理报告里出现**具体数字或具体档位**、而我要写进持久文档时，
> **先去看它自己产出的原始读数**（打印的直方图、剖面、代码），而不是报告里的转述。
> **报告的结论可以采信**（审查者给了推导），**具体数值必须回到原始读数上核**。

---

## Task 4 质量审查的发现（2026-09-26）

> **★★ 1. "读数看得见、判据看不见"在同一份文件里**又**出现了一次——这次是相位 .50 那对。**
> 注释声称 `.50` 那一对钉的是"开 AA 不该把一条本来就对齐的线画歪"，依据是
> "**墨量与纯色数**都与关的那条逐项相等"。**两处都不成立**：
> 纯色数**只有读数没有判据**；而**墨量比按构造看不见平移**——
> 1px 线在**任意**相位下 `Σ cov = 1.0 px/列`（像素中心关于斜坡中心**对称** ⇒ 采样和恒等于 1）。
> **实测**：把 `.50` 的 AA 开那条画在 `+30.25f`（判据窗仍按 `+30f` 取）⇒ **全部通过、退出码 0**，
> 而读数从 `纯色 400，过渡 0` 变成 `纯色 0，过渡 800`。
> **一般化：一条判据看不见某类偏差，可能不是"不够灵敏"，而是**按构造就看不见**——
> 后者用放宽容差、加密采样都救不回来，只有换判据。**
>
> **★★ 2. 判据的先决条件不写出来，会让它**冤枉正确实现**并**给出错误的解释**。**
> `AA_W` 没有上界：`AA_W = 160` ⇒ **4 条 FAIL**，而 ④对照 的失败信息写的是
> "比解析面积少 17.2% —— 这是硬边像素中心采样内接于斜带的必然结果，**不是缺陷**"。
> **真因是窗越过了线的末端**（窗左边界 150、线 A 到 `x=300` 为止 ⇒ 必须 `AA_W ≤ 150`）。
> **一条会把正确实现判失败、还给出错误归因的判据，比没有判据更坏。**
> 同类未写明的先决条件还有 `AA_THIN_WX0/WX1` 与 `AA_WIN_DY ≥ 3`（`=2` 时 ② 读 398）。
>
> **★ 3. 同值字面量 ≠ 同一个量。** `ANISO_DEVICE_X` 与 `ANISO_VX`、`ANISO_DEVICE_Y` 与
> `ANISO_HY × ANISO_SCALE_Y`、`ANISO_TRUE_HALF_X/Y` 与 `AA_LINE_WIDTH/2 × 缩放`——
> 都是**第二份副本**。实测：只挪 `ANISO_HY`（保持相位）而不同步改 `ANISO_DEVICE_Y`
> ⇒ 窗里根本没有线 ⇒ **拉伸轴那条对正确实现失败**、还附一句错解释。
> 写法改成**派生式**。（与 `Gc.kt` 那两处 `INITIAL_VERTEX_CAPACITY` 注释同族；
> 本仓库那句"共用属性名 ≠ 共用判定"在这里是"**同值字面量 ≠ 同一个量**"。）
>
> **★ 4. 措辞与剖面不符**：多处写"羽化**退化成硬边**"，但同一份输出里竖线剖面是
> `98:#CDCDCD`（真外缘**之内**、覆盖率 0.75 的羽化像素**仍在**），而 AA 关的对照
> `98..101 全白`（**那才叫硬边**）。被切掉的只是**外缘之外**那 0.18 设备像素。
> ⇒ 改成"**真外缘之外**那 0.18 设备像素的羽化被切掉"。判据本身（外缘之外 = 0）是对的。
>
> **★ 5. ④ 同时钉住了**斜坡宽度**——那是它的价值，但 KDoc 的理由会把人引偏。**
> `Σ = 4√2` 需要斜坡在 `m` 坐标下半宽恰为 1（来自"对角线 `fwidth = |dFdx|+|dFdy|` 是梯度的 √2 倍"）。
> 换宽度等式就不成立（实测换成真梯度长度 ⇒ `Σ = 5.757`、墨量 `117450` ⇒ ④ 失败）。
>
> **★ 6. 我第四次把报告里的具体数字未经核实转述**（15 vs 14，见上面的更正）。
> **这已经是第 4 次**（13.0 / 8 / 238 / 14）⇒ 不是偶发，是**流程缺陷**。
> 计划里写的改法（"数值必须回到原始读数上核"）**没有被我自己执行**。
> **下一步的硬动作**：把子代理的读数写进文档之前，**先跑一遍那个校验器把那一行读出来**，
> 或者**只写"它的报告称 X"而不把 X 写成事实**。

---

## Task 4 收尾状态（2026-09-26）

`PipelineVerifier` **59 条 PASS / 0 FAIL、退出码 0**；蓝描边**精确 3084**；
`mvn -o test` **385 / 0 失败 / 2 跳过**。
提交序列：`85ef6be`（判据）→ `9c511cf`（两处 KDoc）→ `0f0ff69`（打印模板 + ④b）→
`4e9e2c5`（质量审查七处）。
**关键验收**：`if(antialias)`→`if(false)` 变异下 **15 条倒、`C ≠ A` 倒而 `A ≡ B` 照过**；
相位 .50 那条新断言在"把线挪 0.25 像素"下**倒**，而**墨量比照常 1.0000**（正是它补的盲区）。
**AA 仍默认关 ⇒ 图表与描边的画面在这一步之后**依然**逐像素未变。**

---

## Task 5 实测与偏离（2026-09-26）

提交 `eff42d1`（12 文件，+847/−14）。`ChartVerifier` **146 PASS / 0 FAIL**、
`PipelineVerifier` **59 PASS**（3084 未变）、`mvn -o test` **385/0/2**。

> **★★ 1. 我在任务书里写"在顶点上取 `max` 得到方形 SDF"——那是错的，造出的是"一点 AA 都没有"。**
> 盒子的四个角上 `max(|2·0−1|, |2·0−1|)` **全等于 1** ⇒ **顶点上算完再插值出来是常量**
> ⇒ `fwidth = 0` ⇒ 守卫 `(w > 0) ? … : 1.0` 给出覆盖率 **1**。
> **着色器编译得过、画得出图、只是没有 AA。**
> **错因：把"在顶点上算"与"在片元上算"混为一谈**——距离场必须由**插值出来的坐标**在片元里求。
> **正确形态**：`vEdge` 是 `vec2`，逐轴各算覆盖率再相乘；
> 单轴图型的第二分量恒 0 ⇒ 退化成 1 ⇒ 乘积即单轴（**与只算一个轴逐位相同**，折线四条读数未变为证）。
>
> **★ 1b. 一条可复用的**探测指纹**（实现者提出）**：
> **"关/开两帧读数完全一致"是"AA 整条没生效"的指纹**——比"过渡像素少"**更确定**
> （M10 的读数就是「实测 0 个（纯墨色 1338）」，**与 AA 关那一帧逐项相同**）。
> 凡是"新增一个开/关特性"的场景，都可以先看这条。
>
> **★ 2. "一个相位能验羽化**存在**，只有两个相位才验得出羽化是**对称的**。"**
> `abs(vEdge) → vEdge`（把斜坡变成单侧）时**相位 A 的 ② 照过**（它的外缘行恰好落在
> "单侧公式照样给出 0.75"的那一侧），**只有相位 B 倒**（100 → 0）。
> ⇒ 两个相位**不是冗余**。这条已写进两个相位常量**使用点**的 KDoc。
>
> **★ 3. ④ 的解析值在这里是 `2.75` 而不是 `3`——因为图表路径**几何不外扩**。**
> 与 `Gc` 路径（CPU 侧双向外扩 1 个设备像素、**正是为了把整个斜坡装进几何里**）不同，
> 这里斜坡在外侧那一半**没有片元** ⇒ 每列固定少 `∫₀^0.5(0.5−t)dt × 2 = 0.25 px`，
> **且与线宽无关**。已按"声明过的降级"定级（与 `Gc` 那条"压缩轴上真外缘之外的羽化被切掉"并列）。
>
> **★★ 4. 硬边与 AA 的墨量差**方向**由几何决定**——**这一点很容易被写死成一句话。**
> - **45° 直线**：硬边**少** 11.6%（阶梯**内接**于斜带）。
> - **近水平粗线（图表那条）**：硬边**多** 8.3%（3.0 vs 2.75）。
> ⇒ **"AA 关的对照组"既不能当 ④ 的期望值，也不能用来推断方向**；
> 参照系只能是对应几何的**解析面积**。
>
> **★ 5. "默认关"这条防线是**承重的**，不是摆设**：变异 M3（`uAntialias` 恒传 `1f`）
> 倒 **12** 条，其中 **9 条是既有断言**（"画面只有这 7 种颜色"、图例/标题/外边距那几组的数据线行号）
> ——那些像素期望本来就建立在"AA 默认关"上。
>
> **★ 6. 六个渲染器逐个被独立覆盖**：M9/M7/M4/M5/M6/M8 各自**只让对应那条 ② 倒**
> ⇒ 任务书里"漏掉某个渲染器会活下来"的担心**不成立**。
> 其中 M5（删 `AreaSeriesRenderer` 那行）**只有 1 条倒**，且它证明了一件事：
> **AREA 的窗口取"基线那一段"是必要的**——取整个绘图区的话，轮廓线的羽化会撑住它，变异就活下来。
>
> **★ 7. 两处偏离已批准**：① `vEdge` 改 `vec2`（见 1）；② 多改 `ChartRenderer.java` + `Gc.kt`（1 行）
> ——`GLRenderContextImpl` 只在 `ChartRenderer.draw` 里被构造，而 `ChartRenderer` **看不见 `Gc`**
> （依赖方向）⇒ 用 `BooleanSupplier` 按既有纪律显式注入，是**最小增量**，且 `Gc` 的行为未改。
>
> **★ 8. 已知缺口（记而不补）**：`LINE_AND_MARKERS` 的**标记点那一半没有专属像素探针**
> （证据是"同一语句 + 删那一行的变异"而非像素）。做一条要把窗口切到线带之外才能把两个半边分开，
> **成本与收益不成比例**；实现者照实说了这一点，这是对的。

---

## Task 5 规格审查的发现（2026-09-26）

**✅ 符合规格**。审查者的验证手法里有一条值得记：它**额外跑了一次 BASE 对照**——
把 `4e9e2c5` 版的 `ChartVerifier.kt` 临时覆盖回去、对**同一份 HEAD 库**跑
（AA 关 ⇒ 行为等价），得到 **125 PASS**，与 HEAD 版输出 diff 只有**纯插入 37 行**。
⇒ "既有 125 条逐字符相同 + 新增 21 条"这两条声称**同时被证实**。
**这比"新增的没倒"强得多**：它证的是"旧的那些一个字都没被改动"。

> **★★ 1. 一个新的"理由写错"变体：**公式给出的数是对的，但它是**碰巧**对的**。**
> 注释写"每列固定少 `∫₀^0.5(0.5−t)dt × 2`、**与线宽无关**"。**数值对**（实测 −0.25/列），
> 但**结构上讲不准**：每边实际被切掉的量取决于**该边到最近外侧像素中心的距离**
> （`d ∈ (0,0.5]` ⇒ 该边损失 `0.5−d`，否则 **0**）。本例是**上边切 0.25、下边切 0**。
> 那个 `∫×2` 是**连续面积口径**，与**离散口径碰巧同值**——**换相位或换线宽就不成立**。
> **一般化：一个公式"算对了"不等于"理由对"**；判据可以因为前置条件被钉住而成立，
> 而理由里的通用化陈述**已经在别的相位上错了**。这类错误**不会让任何断言倒**，只能靠读。
> ⇒ 改成"**本相位下实测 −0.25/列**"，并写离散理由。
>
> **★ 2. "斜坡恒为 1 个设备像素宽"是 `fwidth` 的标准近似**——
> `fwidth` 的定义是 `|dFdx| + |dFdy|`，**不是梯度长度**：
> - **轴向**边界（水平/竖直：折线带、柱的上下沿、面积填充的上下沿）只有一个偏导非零
>   ⇒ `fwidth` 恰好等于梯度长度 ⇒ **1 px 精确**；
> - **45° 斜边**两个偏导各是梯度长的 `1/√2`，加起来大了 `√2` 倍
>   ⇒ **全宽 `√2` px、半宽 `1/√2` px**（更**软**，不是更硬）。
>
> ★ 这一处值得记的是**数字的"半/全"之别**：审查者报的是 `1/√2 px`（**半宽**），
> 实现者自己按 `dcov/dp = −(1/w)|∇v|` 复算后**细化为"全宽 √2 / 半宽 1/√2"**——
> **两个数都对，但指的是不同的量**。它把两种写法都写进注释，
> "避免下一个人按半全宽之争再核一遍"。**这次是它主动去核，不是我转述。**
>
> **这句是从 `RenderBatch` 继承的、不是本次新引入**——但既然写进了注释就要加限定，
> **以免将来被当成"已保证的宽度"**。
>
> **★ 3. 审查者对偏离①补了一句更准的表述**（值得留）：
> **坏的不是"`max` 这种 SDF"，而是"先在顶点上取 `max` 再插值"把信号塌成常量**——
> 在片元里对插值出来的坐标取 max 是**能工作的**。
> 所以"`vec2` + 逐轴乘积"是**有理由的、可选更优**的选择：
> 乘积在**角像素**上给出 0.25，**恰等于角像素的真实面积覆盖**（而单轴乘积以外还有别的可行写法）。

---

## Task 5 质量审查的发现（2026-09-26）

**可以批准、无 Critical**。四处 Important **全是陈述级**，其中**两条是"自己写下的实测好**被独立证伪**：

> **★★ 1. 两条"实测"结论被证伪，而它们都写在带 ★ 的段落里。**
> - **`SeriesShaders` 的两条 bullet 互斥**：前一条把"常量分量的 `fwidth` 恒为 0"当前提，
>   后一条却说写非零常量会让那一轴淡一半——**与前提直接矛盾**。
>   **实测**：把 `LINE_VERTEX` 第二分量 `0.0` → `1.0` ⇒ **146 PASS、AA 段与基线逐字节相同**。
>   ⇒ "**必须写 0**"在这里**只是约定，没有任何断言能发现违反**。
> - **`ChartVerifier` 说"AREA 的窗口不能改宽"**（否则变异会活下来）。
>   **实测**：窗口改成整个绘图区 **且**删掉那一行 ⇒ **仍然恰好 1 条倒**。
>   机制：探针填充是**不透明**白，轮廓线同色 ⇒ 它 0.75 的覆盖率叠在**已满覆盖的白**上**看不见**。
>
> **★ 2. 同一次"−25"被两处**给出相反的归因**。** 一处说"与斜坡被切掉一致"、
> 另一处说"是 8 位向下取整"。**后者才对**——解析值 `70125` 里**已经含了斜坡那一份**
> （2.75 = 3 − 0.25/列），所以那 −25 **只可能是量化**。
> **失败场景很具体**：换相位重推的人会拿"−25 与斜坡一致"当端到端校验，
> 于是**把量化那一份重复计进去**。
> **一般化：一个读数只能有一个归因。两处各写一半时，它们会互相掩护——
> 谁都不会单独倒下，因为它们都没被断言。**
>
> **★ 3. `Gc.antialias` 的 KDoc"只影响描边"被本 Task 弄成假的**——
> 本次给它接上了**第七个消费者**（六个图表渲染器，其中 **AREA/BAR 是填充**、SCATTER 是标记点）。
> **失败场景**：用户读属性文档判断"给图表开 AA 要不要设它"——答案是**要**而文档说"不用"；
> 下一个人还可能因此**新加一个 `chartAntialias` 开关**，把同一件事摊成两处。
>
> **★ 4. 一条正面读数：顺序错了不会静默。** 把 `uAntialias` 移到 `shader.use()` **之前**
> ⇒ `glGetError() == 1282`（`GL_INVALID_OPERATION`）+ 对应那条 ② 倒。
> 与 `CLAUDE.md` 里"`glUniform1i` 对 uint uniform 报 INVALID_OPERATION"那条同族——
> **这个仓库有一条现成的、能被抓住的失败模式**，值得继续依赖。
>
> **★ 5. 两条"声明但未验证"（记而不补）**：`STEP` 探针的 8 个样本全等 ⇒ 它**没有拐角**，
> 那条 ② 只证"uniform 到了 STEP 程序"、证不了拐角；
> `ChartRenderer` 的 3 参构造**无任何调用点** ⇒ "不注入时恒按 AA 关画"没有断言盖着。

---

## Task 6 收尾与发现（2026-09-26）

提交 `e85d628`（主体）+ `7a3325b` + `85fabec`。

**`MsaaVerifier` 的读数**：`msaa=4` 的两个过渡行是 `#CCCCCC`(204) 与 `#666666`(102)
—— **正是 4 子样本的四分档**；整幅图只有 **6 种 RGB**（3 基色 + 3 中间档）。
四条判据命中**事先推导**的解析值（过渡 `2W=1782`、线心 `3W=2673`、纯色 `4W=3564`、三类和 == 窗内像素数）。

> **★★ 1. 又一条"期望值由被测对象自己算出来"的恒真断言——它是在我要求"补一次实测"之后才被发现的。**
> `MsaaVerifier` 的"探针窗非空"前置断言，期望值写成 `WINDOW_DY1 - WINDOW_DY0`
> ⇒ **把两者设成相等，两边同时变 0 ⇒ 恒真**。而这**正是它上一轮刚刚写下的**东西。
> 更细的一层：**那条断言的标签也插值那两个常量**，于是变异后印出的是"行数 == **0**"，
> **让假 PASS 看起来自洽**。
> 修法：引入**独立字面量** `WINDOW_ROWS = 7`（出处：线心 3 行 + 2 个过渡行 + 上下各 1 行背景），
> 先决条件与标签都用它。KDoc 写明"**期望值不许由被测的那两个常量自己算出来**"。
>
> **★ 2. 变异测试有它自己的盲区，而且要说出来**：**"只去掉限幅"单独是不可检测的**
> ——限幅在**正常路径上是恒等变换**，不改任何读数。它只在"窗越界"时才起作用。
> ⇒ 一条"注入了却全绿"的变异，**未必说明断言弱**，可能是**那条代码路径在正常输入下不参与运算**。
> （与"变异要点名有梯度的那一处"同族：**先问这一改有没有改变被观测的量**。）
>
> **★ 3. 一个新类：**修一处文档会让另一处对它**旧说法**的引用变成"校正一个不存在的陈述"。**
> 我在 `999acd0` 里把设计文档 §6.2 就地更正为"六个"，而 `CLAUDE.md` 里那句
> 「设计文档 §6.2 **说**"全部七个……"这句也过头了」**引用的正是那个已经不存在的陈述**
> ⇒ 与"宽窄两处并存、互相掩护"是同一类病。
> **一般化：文档之间互相引用时，"更正 A"必须连带检查"谁在引用 A 的旧说法"。**
>
> **★ 4. 守卫的"共享判定"与"必要性"是两件事**（`FftVerifier` 那条）——
> 已在 `999acd0` 与本文档记过；实现者把 `:597` / `:994` 两处**都**收窄成
> "靠画布 FBO 回读的那**六个**"，并**就地**点出第七个的例外，
> 还说明"**统一挂是刻意的**：那个例外将来若改成画像素，守卫已经在了"。
>
> **★ 5. 脚本的失败路径实测过**：把读数行改名 ⇒ 脚本**响亮失败**
> （`[FAIL] 没有解析到 MSAA_READING 行 …——校验器没有跑到读数阶段`，退出码 1），
> **不把"缺读数"当 0 继续比**。这条是"脚本自己会不会骗人"的判据。

---

## Task 6 规格审查的修复轮（2026-09-26，`02fc6d8`）

> **★★ 1. 我给的 ④ 修法不够——实现者照实说了，并给出了完整机理。**
> 我说"把右边改成独立解析式就能让洋红注入被抓住"。**不行**：分类用带 `else` 的 `when`
> ⇒ 洋红被收进"过渡"那一类 ⇒ **三类之和对像素内容恒不变**。
> 能改这个和的只有**窗/循环的几何**。
> ⇒ ④ 的真正职责是"**计数循环有没有覆盖整窗**"：
> **实测**（循环少扫一行、窗边界不变）⇒ ④ **倒**（`5346 ≠ 6237`）；
> 而**旧写法下这一行改动完全不可见**（两边一起少 891）。
> 洋红那一刀由 **①②③** 抓（实测三者全倒）。
> ⚠️ **而我的原话会让下一个人照"守门员"去指望它** ⇒ KDoc 已改正。
>
> **★ 2. 一处诊断修得对**：失败信息里的"扫过的行数"原来印的是**窗声明值** `y1 - y0`
> ⇒ 循环少扫一行时它**仍印 7，指着错的方向**。现在真的计数
> （`窗声明 7 行，计数循环实际扫过 6 行`）。
> **与"打印窗口 ≠ 断言窗口"是同一类**：**失败信息里的每个数都必须来自被观测的那个量**。
>
> **★★ 3. 守卫不再是死代码**：七个入口改成 `FXGLTransfer(msaa = readRequestedMsaa())`，
> 解析与 `MsaaVerifier` **共用一份**（属性名也只有 `MSAA_PROPERTY` 一个常量）。
> **实测**：`-Dxuan.probe.msaa=4` 跑 `PipelineVerifier` ⇒ **退出码 1、断言被求值 0 条、零假失败**。
> ⇒ 一个"明确拒绝"的机制**从"只防源码改动"变成了"也防配置"**。
>
> **★★ 4. `ClickVerifier` 的污染是**真缺陷**，不是环境**：
> 修前 **67 PASS / 8 FAIL**（1 条真 + **7 条被污染**）→ 修后 **74 PASS / 1 FAIL**（只剩真那条），
> FIFO/溢出两节**全过**。
> ★ **之前那次"环境性红灯"的 8 条形态与它逐条相同** ⇒ **那次就是这个缺陷**，
> 我当时的归因（"机器被占用"）**是错的**。现在有确定性复现。
> ★ 造"落空"的**位置**很关键：放在 `fireRobotClick` 函数开头会永远只有 1 FAIL——
> 因为 `robotProbe` 是在函数**中段**才赋值的，放前面等于探针没设上，不构成"落空"。
> （**这条记者的价值**：非确定性红灯一旦有了确定性复现，就应当**改归因**，
> 而不是继续用"环境"解释它。）
>
> **★ 5. 一次过程教训的细化**：实现者**第二次**栽在过期备份上（还原时把刚加的 `scannedRows`
> 冲掉了）。它把元规则从"**每次提交后刷新**"细化成"**每次接受改动之后立刻刷新**"
> ——因为"接受改动"与"提交"之间还有窗口期。

---

## Task 6 质量审查的发现（2026-09-26，最后一道）

> **★★ 1.（Critical）那道新守卫的判据**对负 msaa 判错**——而症状正是它承诺消灭的那种。**
> `canReadPixels = msaa <= 0`。**依据不是推测**：openglfx 的 `GLCanvas` KDoc 写
> **"`-1` – maximum available samples"**，实现是 `msaa < 0 -> MultiSampled(..., GL_MAX_SAMPLES)`
> ⇒ **负数 = 最大采样数，不是"关"**。
> **实测**（`-Dxuan.probe.msaa=-1`）：`GL_SAMPLE_BUFFERS=1 / GL_SAMPLES=32`，
> 而 **`canReadPixels = true` ⇒ 守卫放行 ⇒ `glReadPixels` 报 1282、读回全 0**
> ⇒ 一片"画面全黑"式**假失败**，把排查引向渲染而真因在配置。
> **修法**：判据是"画布 FBO 是不是单采样"，**与符号无关** ⇒ 改成 **`msaa == 0`**
> （实测 `msaa=1` 被驱动抬成 `GL_SAMPLES=2`，所以只有 0 是单采样）。
>
> **★ 而"失败信息与事实矛盾"在同一行上又出现了一次**：`MsaaVerifier` 把 `"msaa=0"` 写死，
> 于是 -1 那次的详情印的是「（msaa=0：回读合法，读数可用）」，而**同一行**是
> `glGetError = 1282`。——**打印里的每个数都必须来自被观测的那个量**（本会话第 N 次）。
>
> **★ 2. 判据的覆盖范围要跟着取值域走。** `MsaaVerifier` **自己**能抓负数
> （-1 时"回读守卫的前提"断言倒），但**脚本只跑 0 与 4** ⇒ 那条断言**永远看不到负数**。
> ⇒ **一条断言"存在"与"会被跑到"是两件事**；新增一条判据时，要问"它的取值域被覆盖了吗"。
>
> **★★ 3. `MsaaVerifier` 是唯一不挂守卫的校验器——而文件里没有一句解释这个例外。**
> 它**必须**在 `msaa>0` 下跑，所以不能挂守卫；但守卫的 KDoc 把"七个入口各调一次"写成**纪律**
> ⇒ **下一个"统一一下"的人会很自然地把守卫加进它的 `start()`，然后把跨进程那两条判据一起弄死**。
> ⇒ 例外必须在**它自己那一侧**说明理由，不能只在"多数派"那一侧写"一律"。
>
> **★ 4. 一个数没有出处就活不下来。** `CLAUDE.md` 里"当时仓库 **15 处**调用点没有一处传过 `msaa`"
> ——被它引作出处的 §9 设计文档里**没有这个数**；**实测构造点是 11**
> （10 个校验器/示例各 1 + `Xuan.kt` 1）。它的实质结论（"无一处传过 msaa"）在分支起点为真，
> 但**那个数字现在只活在 CLAUDE.md 里**——原来带它的探针正是本 Task 删掉的。
> **⇒ 转述来的数，在"原始出处被删掉"之后会变成无处可核的孤证。**
>
> **★ 5. 两个"接线无闸门"的观察（记进"已声明但未验证"）**：某一处守卫退回 `FXGLTransfer()`
> 时**没有任何断言会响**；`MSAA_READING` 的两个字段**没有与进程内被断言的变量对过**
> （字段互换只因两次运行不同才被抓到，写成一**致的偏移**则两边都看不见）。
>
> **★ 6. 一处值得留的正面判据**（审查者的注入 A）：把 `MsaaVerifier` 的计数循环
> 改成"少扫一行" ⇒ **恰好 ④ 一条倒**（`5346 ≠ 6237`），而失败信息印的是
> "计数循环实际扫过 6 行"。⇒ ④ 的**新职责**（"循环覆盖了整窗"）是**真的、且定向的**。

---

## 六个 Task 的收尾状态（2026-09-26）

| Task | 提交数 | 最终验收 |
|---|---|---|
| ① 顶点格式 24→32 | 5 | 385 单测全绿、3084 未变 |
| ② `StrokeGenerator` 输出 `aEdge` | 6 | 35,640 用例位置逐位相同 |
| ③ 描边解析式 AA | 6 | `PipelineVerifier` 57 条、默认路径逐像素不变 |
| ④ 四条 AA 判据 + 变异 | 5 | **59 条**、④ 对解析面积精确相等（+0.2） |
| ⑤ 图表系列 AA | 6 | **`ChartVerifier` 146 条**、既有 125 条逐字符相同 |
| ⑥ MSAA + 回读守卫 + 文档 | 5 | `MsaaVerifier` ×3（0/4/**-1**）+ 脚本 4 条跨进程判据 |

> **★ 一条值得留的判断**：我建议"可考虑加 `require(msaa >= 0)`"，实现者**拒绝了**，
> 理由是 **`-1` 是上游有文档的能力**（"用最大采样数"），砍掉它是**砍功能**、
> 与"判据该改成 `== 0`"这件事无关 ⇒ 改成在 KDoc 里写明"负数不是关、是最大采样数"。
> **我给的是选项，它给了更好的答案**——这类"实现者比任务书更懂边界"的情况，
> 是这条子代理流程里最有价值的产出之一。
>
> **★ 脚本自己的一道闸门被实测印证**：实现者加 `-1` 那一跑时把守卫消息改出一处 `+ +`
> （一元 `+`），**编译直接失败**；而脚本对三次运行**全部报
> 「没有解析到 MSAA_READING 行……校验器没有跑到读数阶段」并以 1 退出**
> ——**正是它该做的反应**（没把缺读数当 0 继续比）。

---

## 自检清单

- [ ] 每个碰了 `xuan-render-gl` / `xuan-core` 的 Task 结束前都 `install` 过
- [ ] Task 1、2 **不改变画面**（跑过 `PipelineVerifier` 确认逐像素不变）
- [ ] Task 3 完成后 `PipelineVerifier` 在**默认（AA 关）**下仍退出码 0
- [ ] 两个开关**默认都关**
- [ ] 七个校验器在 `msaa=0` 下全绿；`msaa>0` 时**明确拒绝**而不是报假失败
- [ ] 变异注入全部用最小 token 改动；还原后 `cmp` 逐字节核对
- [ ] 临时改动（探针、种子、变异）全部删净；残留 JVM = 0；没抢前台、没截屏
- [ ] 提交信息中文且说明了"为什么"
- [ ] **规格 §4.3 那条"细带可能偏厚"的结论**：Task 4 Step 3 若失败，
      按那里的路径走（`vec2` → `vec3`），**不要调容差**；若通过，把实测墨量写进规格
