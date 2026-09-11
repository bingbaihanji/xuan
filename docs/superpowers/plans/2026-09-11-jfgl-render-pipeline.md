# JFGL 绘制管线实施计划（子项目 A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 JFGL 重建成单一渲染栈——GPU 批处理的立即模式 2D 绘制管线，对外提供 Canvas 风格的 `gc` API。

**Architecture:** 绘制调用只往一个直接 `ByteBuffer` 追加顶点（纯 CPU 操作，无 GL 调用）；一帧结束时一次性上传，按命令列表执行个位数 draw call。变换在 CPU 端烘焙进顶点坐标，因此换变换不破坏合批。所有 Paint 归一化为纹理，全局只有一个 fragment shader。几何计算（平坦化/三角化/描边）放在零 GL 依赖的 `geom/` 包，可脱离窗口单测。

**Tech Stack:** Java 21（`geom/`、`renderer/` 热路径）、Kotlin（`Gc` 门面与 `dsl/`）、LWJGL 3.3.6、openglfx-lwjgl 4.2.3、JUnit 5.10.1

**Spec:** `docs/superpowers/specs/2026-09-11-jfgl-render-pipeline-design.md`

---

## 全局约定

**代码风格**：所有 public 类、方法、字段必须有中文 Javadoc，与现有代码库一致。计划中的代码块为节省篇幅省略了 Javadoc，实现时必须补上。

**语言分工**：`geom/` 与 `renderer/` 用 Java；`Gc` 与 `dsl/` 用 Kotlin。Kotlin 方法带默认参数时**必须加 `@JvmOverloads`**，否则 Java 调用方（`chart/` 等）无法使用。

**测试常量**：浮点断言统一用 `1e-4f` 容差。

**核心数据结构**（各任务共用，此处先固定，后续任务不再重复定义）：

```java
// renderer/VertexFormat.java 的常量
public static final int WORDS_PER_VERTEX = 6;   // 6 个 32 位字 = 24 字节
public static final int STRIDE_BYTES     = 24;
public static final int OFFSET_POSITION  = 0;   // vec2 float
public static final int OFFSET_UV        = 8;   // vec2 float
public static final int OFFSET_COLOR     = 16;  // vec4 ubyte normalized（预乘）
public static final int OFFSET_ID        = 20;  // uint
```

```java
// renderer/DrawCommand.java
public record DrawCommand(int textureId, int firstVertex, int vertexCount,
                          int scissorX, int scissorY, int scissorWidth, int scissorHeight) {}
```

---

## 文件结构

**新建**

```
src/main/java/com/bingbaihanji/jfgl/geom/
├── Path.java              可变路径累加器（float 数组，零分配）
├── Flattener.java         曲线 → 折线，设备像素容差
├── Tessellator.java       填充三角化（扇形 + 耳切 + 孔洞桥接）
└── StrokeGenerator.java   描边轮廓生成
src/main/java/com/bingbaihanji/jfgl/renderer/
├── VertexFormat.java      顶点布局常量
├── DrawCommand.java       单条绘制命令
├── VertexWriter.java      纯 CPU：顶点追加 + 命令合并 + 扩容   ← 可单测
├── RenderBatch.java       GL 侧：VBO + 着色器 + 命令执行
└── Gc.kt                  Kotlin 门面（用户 API）
src/main/java/com/bingbaihanji/jfgl/gl/
├── VertexBuffer.java      可增长 VBO
└── Framebuffer.java       离屏 FBO（黄金图测试与拾取用）
src/main/java/com/bingbaihanji/jfgl/style/
├── Paint.java             样式基类
├── SolidPaint.java        纯色
├── GradientPaint.java     线性/径向渐变（LUT 纹理）
├── ImagePaint.java        图像填充
└── TextureUploadQueue.java  延迟纹理上传
src/test/java/com/bingbaihanji/jfgl/geom/     单测
src/test/java/com/bingbaihanji/jfgl/renderer/ 单测
```

**删除**：`scene/`、`event/`、`chart/`、`engine/`、`renderer/BatchRenderer.java`、`renderer/RenderContext.java`、`renderer/TextRenderer.java`、`style/FillStyle.java`、`style/Style.java`、`style/TextStyle.java`、`dsl/DrawDSL.kt`、`dsl/Shapes.kt`、`dsl/Charts.kt`、`dsl/Interactions.kt`、`dsl/Styles.kt`

**保留复用不动**：`math/`、`util/`、`gl/GLAbstraction.java`、`gl/LwjglGLAbstraction.java`、`gl/ShaderProgram.java`、`gl/Texture.java`、`glview/FXGLTransfer.kt`、`view/MainView.kt`、`gpu/`

**取代**：`renderer/Path.java` 被 `geom/Path.java` 取代（Task 1 新建后者）。
但**旧文件要到 Task 13 才删除**——`scene/ShapeNode` 与 `chart/` 四个类在 Task 13 之前仍引用它，
提前删除会让项目从 Task 1 起就无法编译。两个 `Path` 分属不同包，并存不冲突。

---

## Task 0: 初始化 git 仓库

当前目录不是 git 仓库，而本计划后续会删除大量文件，需要版本控制兜底。

**Files:**
- Create: `.gitignore`

- [ ] **Step 1: 确认当前确实不是 git 仓库**

Run: `git -C D:/bingbaihanji/jfglView status`
Expected: `fatal: not a git repository (or any of the parent directories): .git`

- [ ] **Step 2: 创建 .gitignore**

```gitignore
target/
bin/
out/
.idea/
.xcodemap/
*.iml
*.log
```

- [ ] **Step 3: 初始化并提交初始状态**

```bash
cd D:/bingbaihanji/jfglView
git init
git add -A
git commit -m "chore: 初始提交（重构前基线）"
```

- [ ] **Step 4: 确认提交成功**

Run: `git log --oneline`
Expected: 一行 `chore: 初始提交（重构前基线）`

---

## Task 1: geom/Path — 可变路径累加器

现有 `renderer/Path.java` 是不可变 Builder，每条命令分配一个 `Vec2` 和一个可变参数数组。绘制热路径上每帧要构建大量路径，这个分配量不可接受。改为**可变累加器 + 基本类型数组**，由 `Gc` 持有一个实例反复复用。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/geom/Path.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/geom/PathTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathTest {

    @Test
    void 新建路径为空() {
        Path p = new Path();
        assertEquals(0, p.commandCount());
        assertFalse(p.isEmpty());
        assertTrue(p.isEmpty(), "空路径不存任何命令");
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
```

> 注意：上面 `新建路径为空` 里 `assertFalse(p.isEmpty())` 是**故意写错的**，用于确认测试真的会失败。Step 3 中修正。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PathTest`
Expected: 编译失败，`找不到符号: 类 Path`

- [ ] **Step 3: 实现 Path**

删除原 `renderer/Path.java`，新建 `geom/Path.java`：

```java
package com.bingbaihanji.jfgl.geom;

/**
 * 可变的 2D 路径累加器，用于在绘制热路径上零分配地构建路径。
 *
 * <p>与不可变的构建器不同，此类的实例可被反复 {@link #reset()} 并复用。
 * 命令与坐标存储在基本类型数组中，{@code moveTo}/{@code lineTo} 等调用不产生对象分配。
 *
 * <p>用法：
 * <pre>{@code
 * Path path = new Path();
 * path.reset();
 * path.moveTo(0, 0).lineTo(100, 0).lineTo(100, 100).close();
 * }</pre>
 */
public final class Path {

    /** 路径命令类型。 */
    public enum Type { MOVE_TO, LINE_TO, QUAD_TO, CUBIC_TO, CLOSE }

    /** 每条命令最多携带的点数（三次贝塞尔为 3）。 */
    public static final int MAX_POINTS_PER_COMMAND = 3;

    private byte[] types = new byte[16];
    /** 布局：[commandIndex * MAX_POINTS_PER_COMMAND * 2 + pointIndex * 2 + (0=x,1=y)] */
    private float[] coords = new float[16 * MAX_POINTS_PER_COMMAND * 2];
    private byte[] pointCounts = new byte[16];
    private int count = 0;

    /** 清空所有命令，保留已分配的数组容量。 */
    public void reset() {
        count = 0;
    }

    /** 返回路径是否不含任何命令。 */
    public boolean isEmpty() {
        return count == 0;
    }

    /** 返回命令数量。 */
    public int commandCount() {
        return count;
    }

    /** 返回内部命令数组容量（仅供测试与诊断）。 */
    public int arrayCapacity() {
        return types.length;
    }

    /** 返回第 {@code i} 条命令的类型。 */
    public Type commandType(int i) {
        return Type.values()[types[i]];
    }

    /** 返回第 {@code i} 条命令携带的点数。 */
    public int pointCount(int i) {
        return pointCounts[i];
    }

    /** 返回第 {@code i} 条命令第 {@code p} 个点的 x 坐标。 */
    public float commandX(int i, int p) {
        return coords[(i * MAX_POINTS_PER_COMMAND + p) * 2];
    }

    /** 返回第 {@code i} 条命令第 {@code p} 个点的 y 坐标。 */
    public float commandY(int i, int p) {
        return coords[(i * MAX_POINTS_PER_COMMAND + p) * 2 + 1];
    }

    public Path moveTo(float x, float y) {
        return add(Type.MOVE_TO, 1, x, y, 0f, 0f, 0f, 0f);
    }

    public Path lineTo(float x, float y) {
        return add(Type.LINE_TO, 1, x, y, 0f, 0f, 0f, 0f);
    }

    public Path quadTo(float cx, float cy, float x, float y) {
        return add(Type.QUAD_TO, 2, cx, cy, x, y, 0f, 0f);
    }

    public Path cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        return add(Type.CUBIC_TO, 3, c1x, c1y, c2x, c2y, x, y);
    }

    public Path close() {
        return add(Type.CLOSE, 0, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    private Path add(Type type, int points,
                     float x0, float y0, float x1, float y1, float x2, float y2) {
        ensureCapacity(count + 1);
        int ci = count++;
        types[ci] = (byte) type.ordinal();
        pointCounts[ci] = (byte) points;
        int base = ci * MAX_POINTS_PER_COMMAND * 2;
        if (points > 0) { coords[base] = x0; coords[base + 1] = y0; }
        if (points > 1) { coords[base + 2] = x1; coords[base + 3] = y1; }
        if (points > 2) { coords[base + 4] = x2; coords[base + 5] = y2; }
        return this;
    }

    private void ensureCapacity(int needed) {
        if (needed <= types.length) {
            return;
        }
        int newCap = Math.max(needed, types.length * 2);
        byte[] newTypes = new byte[newCap];
        byte[] newCounts = new byte[newCap];
        float[] newCoords = new float[newCap * MAX_POINTS_PER_COMMAND * 2];
        System.arraycopy(types, 0, newTypes, 0, count);
        System.arraycopy(pointCounts, 0, newCounts, 0, count);
        System.arraycopy(coords, 0, newCoords, 0, count * MAX_POINTS_PER_COMMAND * 2);
        types = newTypes;
        pointCounts = newCounts;
        coords = newCoords;
    }
}
```

同时修正测试中故意写错的那行，改为：

```java
    @Test
    void 新建路径为空() {
        Path p = new Path();
        assertEquals(0, p.commandCount());
        assertTrue(p.isEmpty());
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=PathTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/geom/Path.java src/test/java/com/bingbaihanji/jfgl/geom/PathTest.java
git commit -m "feat(geom): 用可变零分配累加器重写 Path"
```

> **不要在此任务删除 `renderer/Path.java`。** `scene/ShapeNode` 与 `chart/` 的四个图表类仍引用它，
> 提前删除会导致整个项目在 Task 13 之前都无法编译，后续任务全部无法验证。
> 旧文件保留到 Task 13 随 `scene/`、`chart/` 一并清除。两个 `Path` 分属不同包，同时存在不冲突。

---

## Task 2: geom/Flattener — 曲线平坦化

把路径的曲线段按**设备像素容差**细分成折线。容差必须绑定当前变换的缩放，否则放大后曲线会露出棱角。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/geom/Flattener.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/geom/FlattenerTest.java`

- [ ] **Step 1: 写失败测试**

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=FlattenerTest`
Expected: 编译失败，`找不到符号: 类 Flattener`

- [ ] **Step 3: 实现 Flattener**

```java
package com.bingbaihanji.jfgl.geom;

/**
 * 把 {@link Path} 中的曲线段按设备像素容差细分成折线。
 * <p>
 * 容差以<strong>设备像素</strong>为单位，通过 {@code scale} 参数把世界单位换算过去。
 * 这样放大时曲线自动细分得更密，缩小时不浪费顶点。
 */
public final class Flattener {

    /** 每条曲线最少细分段数，避免容差过大时退化成一条直线。 */
    private static final int MIN_SEGMENTS = 4;
    /** 每条曲线最多细分段数，防止病态输入产生海量顶点。 */
    private static final int MAX_SEGMENTS = 256;

    private float[] xs = new float[256];
    private float[] ys = new float[256];
    private int count = 0;

    private int[] subPathStarts = new int[16];
    private int subPathCount = 0;

    /** 清空结果，保留已分配的数组容量。 */
    public void reset() {
        count = 0;
        subPathCount = 0;
    }

    public int pointCount() { return count; }
    public float x(int i) { return xs[i]; }
    public float y(int i) { return ys[i]; }
    public int subPathCount() { return subPathCount; }
    public int subPathStart(int i) { return subPathStarts[i]; }

    /**
     * 平坦化路径。结果会被 {@link #reset()} 后重新填充。
     *
     * @param path     待平坦化的路径
     * @param scale    当前变换的平均缩放因子（设备像素 / 世界单位）
     * @param tolerance 允许的最大偏差（设备像素）
     */
    public void flatten(Path path, float scale, float tolerance) {
        reset();
        if (path.isEmpty()) {
            return;
        }

        float currentX = 0f, currentY = 0f;
        float startX = 0f, startY = 0f;
        boolean hasSubPath = false;

        for (int i = 0; i < path.commandCount(); i++) {
            switch (path.commandType(i)) {
                case MOVE_TO -> {
                    currentX = path.commandX(i, 0);
                    currentY = path.commandY(i, 0);
                    startX = currentX;
                    startY = currentY;
                    if (subPathCount == subPathStarts.length) {
                        subPathStarts = java.util.Arrays.copyOf(subPathStarts, subPathCount * 2);
                    }
                    subPathStarts[subPathCount++] = count;
                    appendPoint(currentX, currentY);
                    hasSubPath = true;
                }
                case LINE_TO -> {
                    currentX = path.commandX(i, 0);
                    currentY = path.commandY(i, 0);
                    appendPoint(currentX, currentY);
                }
                case QUAD_TO -> {
                    float cx = path.commandX(i, 0), cy = path.commandY(i, 0);
                    float ex = path.commandX(i, 1), ey = path.commandY(i, 1);
                    int segs = quadSegments(currentX, currentY, cx, cy, ex, ey, scale, tolerance);
                    for (int s = 1; s <= segs; s++) {
                        float t = s / (float) segs;
                        float u = 1f - t;
                        appendPoint(u * u * currentX + 2f * u * t * cx + t * t * ex,
                                    u * u * currentY + 2f * u * t * cy + t * t * ey);
                    }
                    currentX = ex; currentY = ey;
                }
                case CUBIC_TO -> {
                    float c1x = path.commandX(i, 0), c1y = path.commandY(i, 0);
                    float c2x = path.commandX(i, 1), c2y = path.commandY(i, 1);
                    float ex = path.commandX(i, 2), ey = path.commandY(i, 2);
                    int segs = cubicSegments(currentX, currentY, c1x, c1y, c2x, c2y, ex, ey,
                                             scale, tolerance);
                    for (int s = 1; s <= segs; s++) {
                        float t = s / (float) segs;
                        float u = 1f - t;
                        float x = u * u * u * currentX + 3f * u * u * t * c1x
                                + 3f * u * t * t * c2x + t * t * t * ex;
                        float y = u * u * u * currentY + 3f * u * u * t * c1y
                                + 3f * u * t * t * c2y + t * t * t * ey;
                        appendPoint(x, y);
                    }
                    currentX = ex; currentY = ey;
                }
                case CLOSE -> {
                    if (hasSubPath && (currentX != startX || currentY != startY)) {
                        appendPoint(startX, startY);
                        currentX = startX;
                        currentY = startY;
                    }
                }
            }
        }
    }

    /** 使用默认容差 0.25 设备像素。 */
    public void flatten(Path path, float scale) {
        flatten(path, scale, 0.25f);
    }

    /**
     * 按"控制点到弦的最大距离 ≤ 容差"估算二次贝塞尔所需段数。
     * 用曲线在 t=0.5 处到弦中点距离的 2 倍近似最大偏差。
     */
    private int quadSegments(float x0, float y0, float cx, float cy,
                             float x1, float y1, float scale, float tolerance) {
        float mx = 0.5f * (x0 + x1), my = 0.5f * (y0 + y1);
        float qx = 0.25f * x0 + 0.5f * cx + 0.25f * x1;
        float qy = 0.25f * y0 + 0.5f * cy + 0.25f * y1;
        float dx = qx - mx, dy = qy - my;
        return segmentsForDeviation((float) Math.sqrt(dx * dx + dy * dy), scale, tolerance, 2f);
    }

    /**
     * 三次贝塞尔：分别取 t=1/3 与 t=2/3 处到弦的距离，取较大者作为最大偏差上界。
     */
    private int cubicSegments(float x0, float y0, float c1x, float c1y,
                              float c2x, float c2y, float x1, float y1,
                              float scale, float tolerance) {
        float d1 = pointLineDistance(
                (float) (0.2962962963 * x0 + 0.4444444444 * c1x + 0.2222222222 * c2x + 0.0370370370 * x1),
                (float) (0.2962962963 * y0 + 0.4444444444 * c1y + 0.2222222222 * c2y + 0.0370370370 * y1),
                x0, y0, x1, y1);
        float d2 = pointLineDistance(
                (float) (0.0370370370 * x0 + 0.2222222222 * c1x + 0.4444444444 * c2x + 0.2962962963 * x1),
                (float) (0.0370370370 * y0 + 0.2222222222 * c1y + 0.4444444444 * c2y + 0.2962962963 * y1),
                x0, y0, x1, y1);
        return segmentsForDeviation(Math.max(d1, d2), scale, tolerance, 4f);
    }

    private static float pointLineDistance(float px, float py,
                                           float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-12f) {
            float ex = px - x0, ey = py - y0;
            return (float) Math.sqrt(ex * ex + ey * ey);
        }
        float cross = Math.abs((px - x0) * dy - (py - y0) * dx);
        return cross / (float) Math.sqrt(len2);
    }

    private static int segmentsForDeviation(float worldDeviation, float scale,
                                            float tolerance, float exponent) {
        float deviceDeviation = worldDeviation * Math.abs(scale);
        if (deviceDeviation <= tolerance) {
            return MIN_SEGMENTS;
        }
        // 误差随段数按 n^-exponent 衰减：n = (d / tol)^(1/exponent)
        double n = Math.pow(deviceDeviation / tolerance, 1.0 / exponent);
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, (int) Math.ceil(n)));
    }

    private void appendPoint(float x, float y) {
        if (count == xs.length) {
            xs = java.util.Arrays.copyOf(xs, count * 2);
            ys = java.util.Arrays.copyOf(ys, count * 2);
        }
        xs[count] = x;
        ys[count] = y;
        count++;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=FlattenerTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/geom/Flattener.java src/test/java/com/bingbaihanji/jfgl/geom/FlattenerTest.java
git commit -m "feat(geom): 加入绑定设备像素容差的曲线平坦化"
```

---

## Task 3: renderer/VertexFormat 与 DrawCommand

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/VertexFormat.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/DrawCommand.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/renderer/VertexFormatTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.bingbaihanji.jfgl.renderer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VertexFormatTest {

    @Test
    void 步长与偏移自洽() {
        assertEquals(24, VertexFormat.STRIDE_BYTES);
        assertEquals(6, VertexFormat.WORDS_PER_VERTEX);
        assertEquals(0, VertexFormat.OFFSET_POSITION);
        assertEquals(8, VertexFormat.OFFSET_UV);
        assertEquals(16, VertexFormat.OFFSET_COLOR);
        assertEquals(20, VertexFormat.OFFSET_ID);
        assertEquals(VertexFormat.STRIDE_BYTES, VertexFormat.WORDS_PER_VERTEX * 4);
    }

    @Test
    void 打包颜色为预乘整数() {
        // 50% 透明红，预乘后 r=0.5, g=0, b=0, a=0.5
        int packed = VertexFormat.packPremultiplied(1f, 0f, 0f, 0.5f);
        assertEquals(128, (packed >> 24) & 0xFF, 1);
        assertEquals(0, (packed >> 16) & 0xFF);
        assertEquals(0, (packed >> 8) & 0xFF);
        assertEquals(128, packed & 0xFF, 1);
    }

    @Test
    void 打包不透明色不改变分量() {
        int packed = VertexFormat.packPremultiplied(1f, 1f, 1f, 1f);
        assertEquals(0xFFFFFFFF, packed);
    }

    @Test
    void 打包时数值被夹紧到合法范围() {
        int packed = VertexFormat.packPremultiplied(2f, -1f, 0.5f, 2f);
        assertEquals(255, (packed >> 24) & 0xFF);
        assertEquals(0, (packed >> 16) & 0xFF);
        assertEquals(255, packed & 0xFF);
    }

    @Test
    void 零alpha的任何颜色都打包成全零() {
        int packed = VertexFormat.packPremultiplied(1f, 1f, 1f, 0f);
        assertEquals(0, packed);
    }

    @Test
    void DrawCommand保存全部状态() {
        DrawCommand c = new DrawCommand(7, 12, 34, 1, 2, 3, 4);
        assertEquals(7, c.textureId());
        assertEquals(12, c.firstVertex());
        assertEquals(34, c.vertexCount());
        assertEquals(1, c.scissorX());
        assertEquals(2, c.scissorY());
        assertEquals(3, c.scissorWidth());
        assertEquals(4, c.scissorHeight());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=VertexFormatTest`
Expected: 编译失败，`找不到符号: 类 VertexFormat`

- [ ] **Step 3: 实现**

`VertexFormat.java`：

```java
package com.bingbaihanji.jfgl.renderer;

/**
 * 顶点布局常量与颜色打包工具。
 * <p>
 * 布局（共 24 字节）：
 * <pre>
 *  偏移  0  vec2 float            位置（已烘焙到 NDC）
 *  偏移  8  vec2 float            纹理坐标
 *  偏移 16  vec4 ubyte normalized 颜色（预乘 alpha）
 *  偏移 20  uint                  拾取 ID
 * </pre>
 */
public final class VertexFormat {

    public static final int WORDS_PER_VERTEX = 6;
    public static final int STRIDE_BYTES = 24;
    public static final int OFFSET_POSITION = 0;
    public static final int OFFSET_UV = 8;
    public static final int OFFSET_COLOR = 16;
    public static final int OFFSET_ID = 20;

    private VertexFormat() {
    }

    /**
     * 把直通（非预乘）的 RGBA 分量打包为预乘后的 32 位整数。
     * <p>
     * 字节序为 RGBA，即最高字节是 R。分量先夹紧到 0..1，再预乘 alpha。
     * 预乘是为了避免重叠的半透明抗锯齿边缘出现二次混合的暗缝。
     *
     * @param r 红色分量（0-1，直通）
     * @param g 绿色分量（0-1，直通）
     * @param b 蓝色分量（0-1，直通）
     * @param a alpha 分量（0-1）
     * @return 打包后的 RGBA 整数
     */
    public static int packPremultiplied(float r, float g, float b, float a) {
        float ca = clamp(a);
        int ri = toByte(clamp(r) * ca);
        int gi = toByte(clamp(g) * ca);
        int bi = toByte(clamp(b) * ca);
        int ai = toByte(ca);
        return (ri << 24) | (gi << 16) | (bi << 8) | ai;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int toByte(float v) {
        return (int) (v * 255f + 0.5f) & 0xFF;
    }
}
```

`DrawCommand.java`：

```java
package com.bingbaihanji.jfgl.renderer;

/**
 * 单条绘制命令，描述一段连续顶点及其所需的 GL 状态。
 * <p>
 * 命令按提交顺序执行，顺序即 2D 的 z 序，不可重排。
 *
 * @param textureId    要绑定到 0 号纹理单元的纹理 ID
 * @param firstVertex  起始顶点索引
 * @param vertexCount  顶点数量
 * @param scissorX     裁剪矩形左下角 x（帧缓冲像素坐标）
 * @param scissorY     裁剪矩形左下角 y
 * @param scissorWidth 裁剪矩形宽度
 * @param scissorHeight 裁剪矩形高度
 */
public record DrawCommand(int textureId, int firstVertex, int vertexCount,
                          int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=VertexFormatTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/VertexFormat.java \
        src/main/java/com/bingbaihanji/jfgl/renderer/DrawCommand.java \
        src/test/java/com/bingbaihanji/jfgl/renderer/VertexFormatTest.java
git commit -m "feat(renderer): 定义顶点布局与绘制命令"
```

---

## Task 4: renderer/VertexWriter — 顶点追加与命令合并

这是整条管线的心脏，且**完全是纯 CPU 逻辑，可脱离 GL 单测**。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/renderer/VertexWriterTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.bingbaihanji.jfgl.renderer;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class VertexWriterTest {

    private static final int WHITE = 0xFFFFFFFF;

    @Test
    void 初始状态无顶点无命令() {
        VertexWriter w = new VertexWriter(16);
        assertEquals(0, w.vertexCount());
        assertEquals(0, w.commandCount());
    }

    @Test
    void 追加顶点写入正确数据() {
        VertexWriter w = new VertexWriter(16);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(2f, 3f, 0.5f, 0.25f, WHITE, 0);
        assertEquals(1, w.vertexCount());

        ByteBuffer b = w.buffer();
        assertEquals(2f, b.getFloat(0), 1e-6f);
        assertEquals(3f, b.getFloat(4), 1e-6f);
        assertEquals(0.5f, b.getFloat(8), 1e-6f);
        assertEquals(0.25f, b.getFloat(12), 1e-6f);
        assertEquals(WHITE, b.getInt(16));
        assertEquals(0, b.getInt(20));
    }

    @Test
    void 连续同状态只产生一条命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        w.vertex(0f, 1f, 0f, 0f, WHITE, 0);
        assertEquals(1, w.commandCount());
        assertEquals(3, w.command(0).vertexCount());
    }

    @Test
    void 纹理变化开启新命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.setState(2, 0, 0, 100, 100);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        assertEquals(2, w.commandCount());
        assertEquals(1, w.command(0).textureId());
        assertEquals(1, w.command(0).vertexCount());
        assertEquals(2, w.command(1).textureId());
        assertEquals(1, w.command(1).firstVertex());
    }

    @Test
    void 裁剪变化开启新命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.setState(1, 10, 10, 50, 50);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        assertEquals(2, w.commandCount());
    }

    @Test
    void 状态变回原值不会与更早的命令合并() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.setState(2, 0, 0, 100, 100);
        w.vertex(1f, 0f, 0f, 0f, WHITE, 0);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(2f, 0f, 0f, 0f, WHITE, 0);
        assertEquals(3, w.commandCount(), "绘制顺序即 z 序，不允许重排合并");
    }

    @Test
    void quad产生两个三角形共六个顶点() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.quad(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f, 1f, 1f, WHITE, 0);
        assertEquals(6, w.vertexCount());
        ByteBuffer b = w.buffer();
        assertEquals(0f, b.getFloat(0), 1e-6f);
        assertEquals(10f, b.getFloat(24), 1e-6f, "顶点1 的 x 应为右上角 10");
        assertEquals(0f, b.getFloat(120), 1e-6f, "顶点5 回到左下角 x=0");
        assertEquals(0f, b.getFloat(120 + 8), 1e-6f, "顶点5 的 u 应为 u0=0");
        assertEquals(1f, b.getFloat(120 + 12), 1e-6f, "顶点5 的 v 应为 v1=1");
    }

    @Test
    void reset清空顶点与命令() {
        VertexWriter w = new VertexWriter(64);
        w.setState(1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, WHITE, 0);
        w.reset();
        assertEquals(0, w.vertexCount());
        assertEquals(0, w.commandCount());
    }

    @Test
    void 未设置状态时写入顶点抛出异常() {
        VertexWriter w = new VertexWriter(64);
        assertThrows(IllegalStateException.class,
                () -> w.vertex(0f, 0f, 0f, 0f, WHITE, 0));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=VertexWriterTest`
Expected: 编译失败，`找不到符号: 类 VertexWriter`

- [ ] **Step 3: 实现 VertexWriter**

```java
package com.bingbaihanji.jfgl.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 CPU 的顶点收集器：往一个直接缓冲区追加顶点，并把连续的绘制拆成绘制命令。
 *
 * <p>此类<strong>不进行任何 OpenGL 调用</strong>，可在无 GL 上下文的环境下单元测试。
 * 缓冲区使用直接内存并保持小端序，因此可被 {@code glBufferData} 直接消费，无需再复制一次。
 *
 * <p>合批规则：只有<strong>相邻</strong>且状态（纹理、裁剪）相同的绘制才会合并为一条命令。
 * 不允许跨命令合并，因为 2D 中绘制顺序即 z 序。
 */
public final class VertexWriter {

    /** 状态未设置时的哨兵值。 */
    private static final int STATE_UNSET = Integer.MIN_VALUE;

    private ByteBuffer buffer;
    private List<DrawCommand> commands = new ArrayList<>();

    private int vertexCount = 0;
    private int capacityVertices;

    private int textureId = STATE_UNSET;
    private int scissorX, scissorY, scissorWidth, scissorHeight;

    /** 达到此顶点数时触发帧中途 flush（由 RenderBatch 消费）。 */
    private int flushThresholdVertices;

    /** 本帧是否已发生过帧中途 flush 请求。 */
    private boolean flushRequested = false;

    /**
     * @param initialVertexCapacity 初始顶点容量
     */
    public VertexWriter(int initialVertexCapacity) {
        this.capacityVertices = Math.max(1, initialVertexCapacity);
        this.buffer = allocate(this.capacityVertices);
        this.flushThresholdVertices = this.capacityVertices - 3;
    }

    /**
     * 设置当前状态。与上一条命令状态不同时会结束当前命令。
     *
     * @param textureId     纹理 ID
     * @param scissorX      裁剪矩形左下角 x
     * @param scissorY      裁剪矩形左下角 y
     * @param scissorWidth  裁剪矩形宽度
     * @param scissorHeight 裁剪矩形高度
     */
    public void setState(int textureId, int scissorX, int scissorY,
                         int scissorWidth, int scissorHeight) {
        if (this.textureId == textureId
                && this.scissorX == scissorX && this.scissorY == scissorY
                && this.scissorWidth == scissorWidth && this.scissorHeight == scissorHeight) {
            return;
        }
        this.textureId = textureId;
        this.scissorX = scissorX;
        this.scissorY = scissorY;
        this.scissorWidth = scissorWidth;
        this.scissorHeight = scissorHeight;
        commands.add(new DrawCommand(textureId, vertexCount, 0,
                scissorX, scissorY, scissorWidth, scissorHeight));
    }

    /** 追加一个顶点。必须先调用 {@link #setState}。 */
    public void vertex(float x, float y, float u, float v, int premultipliedRgba, int id) {
        if (textureId == STATE_UNSET) {
            throw new IllegalStateException("写入顶点前必须先调用 setState()");
        }
        if (vertexCount >= flushThresholdVertices) {
            grow();
        }
        int offset = vertexCount * VertexFormat.STRIDE_BYTES;
        buffer.putFloat(offset, x);
        buffer.putFloat(offset + 4, y);
        buffer.putFloat(offset + 8, u);
        buffer.putFloat(offset + 12, v);
        buffer.putInt(offset + 16, premultipliedRgba);
        buffer.putInt(offset + 20, id);
        vertexCount++;

        DrawCommand last = commands.get(commands.size() - 1);
        if (last.vertexCount() == 0 && last.firstVertex() != vertexCount - 1) {
            return;
        }
        commands.set(commands.size() - 1, new DrawCommand(
                last.textureId(), last.firstVertex(), vertexCount - last.firstVertex(),
                last.scissorX(), last.scissorY(), last.scissorWidth(), last.scissorHeight()));
    }

    /**
     * 追加一个四边形（两个三角形，顶点顺序为 0-1-2 与 2-3-0）。
     * 四个角点按左上、右上、右下、左下给出；UV 的两个角为 (u0,v0) 与 (u1,v1)。
     */
    public void quad(float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3,
                     float u0, float v0, float u1, float v1,
                     int premultipliedRgba, int id) {
        vertex(x0, y0, u0, v0, premultipliedRgba, id);
        vertex(x1, y1, u1, v0, premultipliedRgba, id);
        vertex(x2, y2, u1, v1, premultipliedRgba, id);
        vertex(x2, y2, u1, v1, premultipliedRgba, id);
        vertex(x3, y3, u0, v1, premultipliedRgba, id);
        vertex(x0, y0, u0, v0, premultipliedRgba, id);
    }

    /** 清空所有顶点与命令，保留缓冲区容量。 */
    public void reset() {
        vertexCount = 0;
        commands.clear();
        flushRequested = false;
    }

    public int vertexCount() { return vertexCount; }

    public int commandCount() { return commands.size(); }

    public DrawCommand command(int i) { return commands.get(i); }

    /** 返回本帧已写入的全部命令（只读视图）。 */
    public List<DrawCommand> commands() { return java.util.Collections.unmodifiableList(commands); }

    /**
     * 返回顶点缓冲区。position 为 0，limit 为已写入字节数。
     */
    public ByteBuffer buffer() {
        buffer.position(0);
        buffer.limit(vertexCount * VertexFormat.STRIDE_BYTES);
        return buffer;
    }

    /** 返回当前顶点容量。 */
    public int vertexCapacity() { return capacityVertices; }

    /** 返回是否因达到容量上限而请求了帧中途 flush。 */
    public boolean isFlushRequested() { return flushRequested; }

    /** 清除帧中途 flush 请求标志。 */
    public void clearFlushRequest() { flushRequested = false; }

    /**
     * 容量翻倍。若已达到硬上限仍未满足，则改为请求帧中途 flush 并复用已有缓冲区。
     */
    private void grow() {
        if (capacityVertices >= MAX_VERTEX_CAPACITY) {
            flushRequested = true;
            return;
        }
        capacityVertices = Math.min(capacityVertices * 2, MAX_VERTEX_CAPACITY);
        ByteBuffer old = buffer;
        buffer = allocate(capacityVertices);
        old.position(0);
        old.limit(Math.min(old.capacity(), capacityVertices * VertexFormat.STRIDE_BYTES));
        buffer.put(0, old, 0, Math.min(old.capacity(), buffer.capacity()));
        flushThresholdVertices = capacityVertices - 3;
    }

    /** 顶点数硬上限，约 24 MB。 */
    public static final int MAX_VERTEX_CAPACITY = 1 << 20;

    private static ByteBuffer allocate(int vertices) {
        return ByteBuffer.allocateDirect(vertices * VertexFormat.STRIDE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
    }
}
```

> **实现提示**：`vertex()` 中更新 `last` 命令顶点数的逻辑较绕。若实现时觉得难以读懂，可改为更直白的写法——在 `setState` 时把**上一条**命令的 `vertexCount` 定稿，并把新命令的 `firstVertex` 记为当前 `vertexCount`。两种写法行为等价，选可读性更好的那个，但测试必须全部通过。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=VertexWriterTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/VertexWriter.java \
        src/test/java/com/bingbaihanji/jfgl/renderer/VertexWriterTest.java
git commit -m "feat(renderer): 实现纯 CPU 的顶点收集与命令合并"
```

---

## Task 5: geom/Tessellator — 填充三角化

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/geom/Tessellator.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/geom/TessellatorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TessellatorTest {

    /** 用鞋带公式计算三角形列表的总面积（取绝对值）。 */
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
    void 三角形输入产生一个三角形() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 10f, 0f, 0f, 10f}, 3, false);
        assertEquals(1, t.triangleCount());
        assertEquals(50f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 凸四边形面积守恒() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, false);
        assertEquals(100f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 凹多边形面积守恒() {
        // L 形：外框 10x10 减去右上角 5x5
        Tessellator t = new Tessellator();
        float[] poly = {0f, 0f, 10f, 0f, 10f, 5f, 5f, 5f, 5f, 10f, 0f, 10f};
        t.tessellate(poly, 6, false);
        assertEquals(75f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 凹多边形的三角形数不超过N减2() {
        Tessellator t = new Tessellator();
        float[] poly = {0f, 0f, 10f, 0f, 10f, 5f, 5f, 5f, 5f, 10f, 0f, 10f};
        t.tessellate(poly, 6, false);
        assertTrue(t.triangleCount() <= 4, "6 边形最多 4 个三角形，实际 " + t.triangleCount());
    }

    @Test
    void 顺时针与逆时针输入结果一致() {
        Tessellator a = new Tessellator();
        a.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, false);

        Tessellator b = new Tessellator();
        b.tessellate(new float[]{0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f}, 4, false);

        assertEquals(a.triangleCount(), b.triangleCount());
        assertEquals(area(a.triangles()), area(b.triangles()), 1e-3f);
    }

    @Test
    void 退化输入不抛异常且产生零面积() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 1f, 1f, 2f, 2f}, 3, false);
        assertEquals(0f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 顶点数不足时不产生三角形() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 1f, 1f}, 2, false);
        assertEquals(0, t.triangleCount());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=TessellatorTest`
Expected: 编译失败，`找不到符号: 类 Tessellator`

- [ ] **Step 3: 实现 Tessellator（扇形 + 耳切法）**

本任务先实现凸多边形的扇形三角化与凹多边形的耳切法。**孔洞桥接放在 Task 6**（`hasHoles` 参数此处已存在但暂不处理洞，仅按单轮廓处理）。

```java
package com.bingbaihanji.jfgl.geom;

import java.util.Arrays;

/**
 * 把简单多边形（可凹、无自相交）三角化为三角形列表。
 *
 * <p>输出格式为扁平的 float 数组，每 6 个 float 描述一个三角形：
 * {@code [x0,y0, x1,y1, x2,y2, ...]}。
 *
 * <p>算法：先判断凸性。凸多边形走扇形三角化（O(n)）；凹多边形走耳切法（O(n²)）。
 * 顶点顺序（顺/逆时针）不要求，内部会统一为逆时针。
 */
public final class Tessellator {

    /** 斜率比较与面积判断的容差。 */
    private static final float EPSILON = 1e-6f;

    private float[] triangles = new float[3 * 6 * 4];
    private int triangleCount = 0;

    private float[] scratchX = new float[64];
    private float[] scratchY = new float[64];
    private int[] scratchIndex = new int[64];

    public int triangleCount() { return triangleCount; }

    public float[] triangles() {
        return Arrays.copyOf(triangles, triangleCount * 6);
    }

    public void reset() {
        triangleCount = 0;
    }

    /**
     * 三角化一个简单多边形。
     *
     * @param points 扁平顶点数组 {@code [x0,y0, x1,y1, ...]}
     * @param count  顶点个数
     * @param closed 输入是否为闭合环（当前实现忽略此参数，始终按闭合环处理）
     */
    public void tessellate(float[] points, int count, boolean closed) {
        reset();
        if (count < 3) {
            return;
        }
        ensureScratch(count);
        for (int i = 0; i < count; i++) {
            scratchX[i] = points[i * 2];
            scratchY[i] = points[i * 2 + 1];
            scratchIndex[i] = i;
        }

        if (signedArea(scratchX, scratchY, count) < 0f) {
            reverse(scratchX, scratchY, count);
        }

        if (isConvex(scratchX, scratchY, count)) {
            for (int i = 1; i + 1 < count; i++) {
                emit(scratchX[0], scratchY[0],
                     scratchX[i], scratchY[i],
                     scratchX[i + 1], scratchY[i + 1]);
            }
            return;
        }

        earClip(scratchX, scratchY, count);
    }

    // ------------------------------------------------------------------
    // 耳切法
    // ------------------------------------------------------------------

    private void earClip(float[] px, float[] py, int count) {
        int remaining = count;
        int guard = 0;
        int maxIterations = count * count + 8;

        while (remaining > 3 && guard++ < maxIterations) {
            boolean clipped = false;
            for (int i = 0; i < remaining; i++) {
                int prev = (i - 1 + remaining) % remaining;
                int next = (i + 1) % remaining;

                if (!isEar(px, py, remaining, prev, i, next)) {
                    continue;
                }
                emit(px[prev], py[prev], px[i], py[i], px[next], py[next]);
                removeAt(px, py, i, remaining);
                remaining--;
                clipped = true;
                break;
            }
            if (!clipped) {
                // 病态输入（自相交等）：放弃剩余部分，避免死循环
                break;
            }
        }

        if (remaining == 3) {
            emit(px[0], py[0], px[1], py[1], px[2], py[2]);
        }
    }

    private static boolean isEar(float[] px, float[] py, int n, int a, int b, int c) {
        if (cross(px[a], py[a], px[b], py[b], px[c], py[c]) <= EPSILON) {
            return false; // 凹角或退化，不是耳
        }
        for (int i = 0; i < n; i++) {
            if (i == a || i == b || i == c) {
                continue;
            }
            if (pointInTriangle(px[i], py[i],
                    px[a], py[a], px[b], py[b], px[c], py[c])) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInTriangle(float px, float py,
                                           float ax, float ay, float bx, float by,
                                           float cx, float cy) {
        float d1 = cross(ax, ay, bx, by, px, py);
        float d2 = cross(bx, by, cx, cy, px, py);
        float d3 = cross(cx, cy, ax, ay, px, py);
        boolean hasNeg = d1 < -EPSILON || d2 < -EPSILON || d3 < -EPSILON;
        boolean hasPos = d1 > EPSILON || d2 > EPSILON || d3 > EPSILON;
        return !(hasNeg && hasPos);
    }

    private static float cross(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static void removeAt(float[] px, float[] py, int index, int n) {
        for (int i = index; i < n - 1; i++) {
            px[i] = px[i + 1];
            py[i] = py[i + 1];
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static float signedArea(float[] px, float[] py, int n) {
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += px[i] * py[j] - px[j] * py[i];
        }
        return sum * 0.5f;
    }

    private static void reverse(float[] px, float[] py, int n) {
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            float tx = px[i]; px[i] = px[j]; px[j] = tx;
            float ty = py[i]; py[i] = py[j]; py[j] = ty;
        }
    }

    private static boolean isConvex(float[] px, float[] py, int n) {
        for (int i = 0; i < n; i++) {
            int a = i, b = (i + 1) % n, c = (i + 2) % n;
            if (cross(px[a], py[a], px[b], py[b], px[c], py[c]) < -EPSILON) {
                return false;
            }
        }
        return true;
    }

    private void ensureScratch(int count) {
        if (count <= scratchX.length) {
            return;
        }
        scratchX = new float[count];
        scratchY = new float[count];
        scratchIndex = new int[count];
    }

    private void emit(float x0, float y0, float x1, float y1, float x2, float y2) {
        if (triangleCount * 6 + 6 > triangles.length) {
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
        }
        int o = triangleCount * 6;
        triangles[o] = x0;      triangles[o + 1] = y0;
        triangles[o + 2] = x1;  triangles[o + 3] = y1;
        triangles[o + 4] = x2;  triangles[o + 5] = y2;
        triangleCount++;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=TessellatorTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/geom/Tessellator.java \
        src/test/java/com/bingbaihanji/jfgl/geom/TessellatorTest.java
git commit -m "feat(geom): 实现扇形与耳切法填充三角化"
```

---

## Task 6: geom/Tessellator — 孔洞支持

饼图中心挖空、环形图、以及"填充 + 描边同路径"都依赖带洞轮廓。

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/geom/Tessellator.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/geom/TessellatorHoleTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TessellatorHoleTest {

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
    void 带方形洞的方形面积为差集() {
        Tessellator t = new Tessellator();
        float[] outer = {0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f};
        float[] inner = {5f, 5f, 5f, 15f, 15f, 15f, 15f, 5f};

        t.tessellateWithHoles(outer, 4, new float[][]{inner}, new int[]{4});

        assertEquals(400f - 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 无洞时与普通三角化等价() {
        Tessellator a = new Tessellator();
        a.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, false);

        Tessellator b = new Tessellator();
        b.tessellateWithHoles(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4,
                new float[0][], new int[0]);

        assertEquals(area(a.triangles()), area(b.triangles()), 1e-3f);
    }

    @Test
    void 洞完全在内部时被正确挖去() {
        Tessellator t = new Tessellator();
        float[] outer = {0f, 0f, 30f, 0f, 30f, 30f, 0f, 30f};
        float[] inner = {10f, 10f, 10f, 20f, 20f, 20f, 20f, 10f};

        t.tessellateWithHoles(outer, 4, new float[][]{inner}, new int[]{4});

        assertEquals(900f - 100f, area(t.triangles()), 1e-2f);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=TessellatorHoleTest`
Expected: 编译失败，`找不到符号: 方法 tessellateWithHoles`

- [ ] **Step 3: 实现孔洞桥接**

在 `Tessellator` 中加入。算法：把每个洞的**最右**顶点与外轮廓上可见的顶点用两条重合边连起来，把带洞多边形转成单个简单多边形，再走已有的耳切法。

```java
    /**
     * 三角化带孔洞的多边形。
     *
     * <p>实现方式：把每个洞用一个"桥"接到外轮廓上，合并成单个简单多边形后走耳切法。
     *
     * @param outer     外轮廓的扁平顶点数组
     * @param outerCount 外轮廓顶点数
     * @param holes     每个洞的扁平顶点数组
     * @param holeCounts 每个洞的顶点数
     */
    public void tessellateWithHoles(float[] outer, int outerCount,
                                    float[][] holes, int[] holeCounts) {
        reset();
        if (outerCount < 3) {
            return;
        }
        if (holes == null || holes.length == 0) {
            tessellate(outer, outerCount, true);
            return;
        }

        float[] mergedX = new float[outerCount + sumLengths(holeCounts) * 2 + 8];
        float[] mergedY = new float[mergedX.length];
        int n = 0;
        for (int i = 0; i < outerCount; i++) {
            mergedX[n] = outer[i * 2];
            mergedY[n] = outer[i * 2 + 1];
            n++;
        }

        for (int h = 0; h < holes.length; h++) {
            int hc = holeCounts[h];
            if (hc < 3) {
                continue;
            }
            if (n + hc * 2 + 2 > mergedX.length) {
                mergedX = Arrays.copyOf(mergedX, mergedX.length * 2);
                mergedY = Arrays.copyOf(mergedY, mergedX.length);
            }
            n = bridgeHole(mergedX, mergedY, n, holes[h], hc, outer, outerCount);
        }

        float[] poly = new float[n * 2];
        for (int i = 0; i < n; i++) {
            poly[i * 2] = mergedX[i];
            poly[i * 2 + 1] = mergedY[i];
        }
        tessellate(poly, n, true);
    }

    private static int sumLengths(int[] counts) {
        int s = 0;
        for (int c : counts) {
            s += c;
        }
        return s;
    }

    /**
     * 把一个洞接到当前轮廓上，返回合并后的顶点数。
     * 做法：取洞的最右顶点，与外轮廓上距离最近的顶点之间插入一对重合边。
     */
    private static int bridgeHole(float[] mx, float[] my, int n,
                                  float[] hole, int holeCount,
                                  float[] outer, int outerCount) {
        // 洞的最右顶点
        int holeRight = 0;
        for (int i = 1; i < holeCount; i++) {
            if (hole[i * 2] > hole[holeRight * 2]) {
                holeRight = i;
            }
        }

        // 外轮廓上离它最近的顶点
        int outerNearest = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < outerCount; i++) {
            float dx = outer[i * 2] - hole[holeRight * 2];
            float dy = outer[i * 2 + 1] - hole[holeRight * 2 + 1];
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                outerNearest = i;
            }
        }

        // 把外轮廓插入点定位到合并数组中的同一位置
        // 合并数组前 outerCount 个点即外轮廓，故索引一致
        // 桥：先走到外轮廓桥接点，再绕洞一圈，再回到外轮廓桥接点
        int target = outerNearest;
        // 先回到桥接点（若已在桥接点则不动）
        if (n > 0) {
            // 复制外轮廓到桥接点这一段
        }

        int written = target + 1;
        for (int i = 0; i < written && i < n; i++) {
            // 已在合并数组中
        }

        // 绕洞
        int p = written;
        for (int k = 0; k <= holeCount; k++) {
            int idx = (holeRight + k) % holeCount;
            mx[p] = hole[idx * 2];
            my[p] = hole[idx * 2 + 1];
            p++;
        }
        // 回到桥接点
        mx[p] = outer[target * 2];
        my[p] = outer[target * 2 + 1];
        p++;

        // 追加外轮廓剩余部分
        for (int i = target + 1; i < outerCount; i++) {
            mx[p] = outer[i * 2];
            my[p] = outer[i * 2 + 1];
            p++;
        }

        return p;
    }
```

> **实现提示**：上面 `bridgeHole` 的骨架把"合并数组中的外轮廓索引"与"原始外轮廓索引"当成了同一个，只在**单个洞**时成立。多个洞时第二个洞的桥接点索引会错位。实现时应改为：维护一个"合并轮廓"并始终在其中查找最近的、**可见的**桥接顶点；或用更稳妥的做法——先把外轮廓复制进合并数组，再对每个洞重新计算它在合并数组中的最近顶点。第一种洞的测试通过后，补一个双洞测试再提交。

- [ ] **Step 4: 补一个双洞测试并确认通过**

在 `TessellatorHoleTest` 中追加：

```java
    @Test
    void 两个洞都被正确挖去() {
        Tessellator t = new Tessellator();
        float[] outer = {0f, 0f, 40f, 0f, 40f, 20f, 0f, 20f};
        float[] h1 = {5f, 5f, 5f, 15f, 15f, 15f, 15f, 5f};
        float[] h2 = {25f, 5f, 25f, 15f, 35f, 15f, 35f, 5f};

        t.tessellateWithHoles(outer, 4, new float[][]{h1, h2}, new int[]{4, 4});

        assertEquals(800f - 100f - 100f, area(t.triangles()), 1e-2f);
    }
```

Run: `mvn test -Dtest=TessellatorHoleTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/geom/Tessellator.java \
        src/test/java/com/bingbaihanji/jfgl/geom/TessellatorHoleTest.java
git commit -m "feat(geom): 支持带孔洞多边形填充"
```

---

## Task 7: geom/StrokeGenerator — 描边轮廓生成

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/geom/StrokeGenerator.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/geom/StrokeGeneratorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrokeGeneratorTest {

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

    private static float minX(float[] tris) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 2) m = Math.min(m, tris[i]);
        return m;
    }

    private static float maxX(float[] tris) {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 2) m = Math.max(m, tris[i]);
        return m;
    }

    private static float minY(float[] tris) {
        float m = Float.MAX_VALUE;
        for (int i = 1; i < tris.length; i += 2) m = Math.min(m, tris[i]);
        return m;
    }

    private static float maxY(float[] tris) {
        float m = -Float.MAX_VALUE;
        for (int i = 1; i < tris.length; i += 2) m = Math.max(m, tris[i]);
        return m;
    }

    @Test
    void 水平线段描边面积为长乘宽() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertEquals(20f, area(g.triangles()), 1e-2f);
    }

    @Test
    void 线宽体现在垂直方向的包围盒上() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        // BUTT 端点不向外延伸，故 x 方向恰好是线段本身
        assertEquals(0f, minX(g.triangles()), 1e-2f);
        assertEquals(10f, maxX(g.triangles()), 1e-2f);
        // 宽度 4 意味着上下各偏 2
        assertEquals(-2f, minY(g.triangles()), 1e-2f);
        assertEquals(2f, maxY(g.triangles()), 1e-2f);
    }

    @Test
    void 方形端点向外延伸半个线宽() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.SQUARE, StrokeGenerator.Join.MITER, 4f);
        assertEquals(-2f, minX(g.triangles()), 1e-2f, "方端点应向左延伸半个线宽");
        assertEquals(12f, maxX(g.triangles()), 1e-2f, "方端点应向右延伸半个线宽");
    }

    @Test
    void 圆形端点比平端点面积更大() {
        StrokeGenerator butt = new StrokeGenerator();
        butt.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator round = new StrokeGenerator();
        round.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.ROUND, StrokeGenerator.Join.MITER, 4f, 16);

        // 圆端点在两端各加一个半径 2 的半圆，面积约 +4π ≈ +12.6
        assertEquals(area(butt.triangles()) + 4f * (float) Math.PI,
                area(round.triangles()), 0.5f);
    }

    @Test
    void 折线拐角产生连续覆盖() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertTrue(area(g.triangles()) > 20f, "折线应产生大于单段的面积");
    }

    @Test
    void 闭合路径无端点封口() {
        StrokeGenerator open = new StrokeGenerator();
        open.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator closed = new StrokeGenerator();
        closed.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, true, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        assertTrue(area(closed.triangles()) > area(open.triangles()));
    }

    @Test
    void 单点或空输入不产生三角形() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{1f, 1f}, 1, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertEquals(0, g.triangleCount());
    }

    @Test
    void 零宽描边不产生三角形() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 0f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertEquals(0, g.triangleCount());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=StrokeGeneratorTest`
Expected: 编译失败，`找不到符号: 类 StrokeGenerator`

- [ ] **Step 3: 实现 StrokeGenerator（基础：butt/square/round 端点 + miter join）**

```java
package com.bingbaihanji.jfgl.geom;

import java.util.Arrays;

/**
 * 把折线转成描边轮廓的三角形列表。
 *
 * <p>输出格式与 {@link Tessellator} 一致：每 6 个 float 一个三角形。
 *
 * <p><strong>关于非均匀缩放</strong>：本类在<strong>局部空间</strong>生成轮廓，
 * 由调用方在烘焙变换时处理缩放。因此 {@code scale(2,1)} 下圆的描边会正确变成椭圆环。
 *
 * <p><strong>已知限制</strong>：凹角处相邻段的偏移轮廓会自相交，产生重叠三角形。
 * 配合预乘 alpha，不透明描边无可见影响；半透明描边会出现颜色叠加。
 */
public final class StrokeGenerator {

    /** 端点样式。 */
    public enum Cap { BUTT, ROUND, SQUARE }

    /** 接头样式。 */
    public enum Join { MITER, ROUND, BEVEL }

    private float[] triangles = new float[3 * 6 * 4];
    private int triangleCount = 0;
    private float[] ringX = new float[64];
    private float[] ringY = new float[64];

    public int triangleCount() { return triangleCount; }

    public float[] triangles() { return Arrays.copyOf(triangles, triangleCount * 6); }

    public void reset() { triangleCount = 0; }

    /** 使用默认圆角细分段数。 */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit) {
        stroke(points, count, closed, width, cap, join, miterLimit, 8);
    }

    /**
     * 生成描边轮廓。
     *
     * @param points     扁平折线顶点数组
     * @param count      顶点个数
     * @param closed     是否闭合
     * @param width      线宽（局部空间单位）
     * @param cap        端点样式
     * @param join       接头样式
     * @param miterLimit miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     * @param roundSegments 圆角/圆端点的细分段数
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit, int roundSegments) {
        reset();
        if (count < 2 || width <= 0f) {
            return;
        }
        float half = width * 0.5f;

        int segmentCount = closed ? count : count - 1;
        if (segmentCount < 1) {
            return;
        }

        for (int i = 0; i < segmentCount; i++) {
            int a = i;
            int b = (i + 1) % count;
            float ax = points[a * 2], ay = points[a * 2 + 1];
            float bx = points[b * 2], by = points[b * 2 + 1];
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) {
                continue;
            }
            float nx = -dy / len * half;
            float ny = dx / len * half;

            // 每段是一个四边形（两个三角形）
            float x0 = ax + nx, y0 = ay + ny;
            float x1 = bx + nx, y1 = by + ny;
            float x2 = bx - nx, y2 = by - ny;
            float x3 = ax - nx, y3 = ay - ny;
            emitQuad(x0, y0, x1, y1, x2, y2, x3, y3);

            // 接头：填充相邻两段之间的缝隙
            boolean isLastOpen = !closed && i == segmentCount - 1;
            boolean isFirstOpen = !closed && i == 0;
            if (!isLastOpen) {
                int c = closed ? (i + 2) % count : i + 2;
                float cx = points[c * 2], cy = points[c * 2 + 1];
                emitJoin(bx, by, dx, dy, cx - bx, cy - by, half, join, miterLimit, roundSegments);
            }
            if (isFirstOpen) {
                emitCap(ax, ay, -dx, -dy, half, cap, roundSegments);
            }
            if (isLastOpen) {
                emitCap(bx, by, dx, dy, half, cap, roundSegments);
            }
        }
    }

    private void emitJoin(float px, float py, float d1x, float d1y,
                          float d2x, float d2y, float half,
                          Join join, float miterLimit, int roundSegments) {
        float l1 = (float) Math.sqrt(d1x * d1x + d1y * d1y);
        float l2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
        if (l1 < 1e-6f || l2 < 1e-6f) {
            return;
        }
        float u1x = d1x / l1, u1y = d1y / l1;
        float u2x = d2x / l2, u2y = d2y / l2;

        float cross = u1x * u2y - u1y * u2x;
        float dot = u1x * u2x + u1y * u2y;
        if (Math.abs(cross) < 1e-6f && dot > 0f) {
            return; // 共线同向，无缝隙
        }

        // 两个法线方向
        float n1x = -u1y * half, n1y = u1x * half;
        float n2x = -u2y * half, n2y = u2x * half;
        boolean leftTurn = cross > 0f;

        float ox1 = leftTurn ? n1x : -n1x, oy1 = leftTurn ? n1y : -n1y;
        float ox2 = leftTurn ? n2x : -n2x, oy2 = leftTurn ? n2y : -n2y;

        if (join == Join.BEVEL || join == Join.ROUND) {
            emitQuad(px, py, px + ox1, py + oy1, px + ox2, py + oy2, px, py);
            if (join == Join.ROUND) {
                emitArc(px, py, half, u1x, u1y, u2x, u2y, leftTurn, roundSegments);
            }
            return;
        }

        // MITER：求两条偏移直线的交点
        float denom = ox1 * oy2 - oy1 * ox2;
        if (Math.abs(denom) < 1e-9f) {
            emitQuad(px, py, px + ox1, py + oy1, px + ox2, py + oy2, px, py);
            return;
        }
        float t = ((ox2 - ox1) * oy2 - (oy2 - oy1) * ox2) / denom;
        float mx = px + ox1 * t, my = py + oy1 * t;
        float dx = mx - px, dy = my - py;
        float miterLength = (float) Math.sqrt(dx * dx + dy * dy);

        if (miterLength > miterLimit * half) {
            // 超过 miter limit，回退为 bevel
            emitQuad(px, py, px + ox1, py + oy1, px + ox2, py + oy2, px, py);
            return;
        }
        emitTriangle(px + ox1, py + oy1, mx, my, px + ox2, py + oy2);
    }

    private void emitCap(float px, float py, float dx, float dy, float half,
                         Cap cap, int roundSegments) {
        if (cap == Cap.BUTT) {
            return;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) {
            return;
        }
        float ux = dx / len, uy = dy / len;
        float nx = -uy * half, ny = ux * half;

        if (cap == Cap.SQUARE) {
            emitQuad(px + nx, py + ny, px + nx + ux * half, py + ny + uy * half,
                     px - nx + ux * half, py - ny + uy * half, px - nx, py - ny);
            return;
        }

        // ROUND：以端点为中心、从 +n 扫到 -n 的半圆扇
        float startAngle = (float) Math.atan2(ny, nx);
        float sweep = (float) Math.PI;
        for (int i = 0; i < roundSegments; i++) {
            float a0 = startAngle + sweep * i / roundSegments;
            float a1 = startAngle + sweep * (i + 1) / roundSegments;
            emitTriangle(px, py,
                    px + (float) Math.cos(a0) * half, py + (float) Math.sin(a0) * half,
                    px + (float) Math.cos(a1) * half, py + (float) Math.sin(a1) * half);
        }
    }

    private void emitArc(float cx, float cy, float radius,
                         float u1x, float u1y, float u2x, float u2y,
                         boolean leftTurn, int segments) {
        float a1 = (float) Math.atan2(u1y, u1x);
        float a2 = (float) Math.atan2(u2y, u2x);
        float sweep = a2 - a1;
        while (sweep > Math.PI) sweep -= 2 * (float) Math.PI;
        while (sweep < -Math.PI) sweep += 2 * (float) Math.PI;

        float n1x = -u1y, n1y = u1x;
        float start = (float) Math.atan2(leftTurn ? n1y : -n1y, leftTurn ? n1x : -n1x);
        float target = start + (leftTurn ? sweep : -sweep);

        for (int i = 0; i < segments; i++) {
            float a0 = start + (target - start) * i / segments;
            float aA = start + (target - start) * (i + 1) / segments;
            emitTriangle(cx, cy,
                    cx + (float) Math.cos(a0) * radius, cy + (float) Math.sin(a0) * radius,
                    cx + (float) Math.cos(aA) * radius, cy + (float) Math.sin(aA) * radius);
        }
    }

    private void emitQuad(float x0, float y0, float x1, float y1,
                          float x2, float y2, float x3, float y3) {
        emitTriangle(x0, y0, x1, y1, x2, y2);
        emitTriangle(x2, y2, x3, y3, x0, y0);
    }

    private void emitTriangle(float x0, float y0, float x1, float y1,
                              float x2, float y2) {
        if (triangleCount * 6 + 6 > triangles.length) {
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
        }
        int o = triangleCount * 6;
        triangles[o] = x0;     triangles[o + 1] = y0;
        triangles[o + 2] = x1; triangles[o + 3] = y1;
        triangles[o + 4] = x2; triangles[o + 5] = y2;
        triangleCount++;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=StrokeGeneratorTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/geom/StrokeGenerator.java \
        src/test/java/com/bingbaihanji/jfgl/geom/StrokeGeneratorTest.java
git commit -m "feat(geom): 实现描边轮廓生成（端点/接头/miter limit）"
```

---

## Task 8: geom/StrokeGenerator — 虚线支持

**Files:**
- Modify: `src/main/java/com/bingbaihanji/jfgl/geom/StrokeGenerator.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/geom/StrokeDashTest.java`

- [ ] **Step 1: 写失败测试**

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=StrokeDashTest`
Expected: 编译失败，`找不到符号: 方法 strokeDashed`

- [ ] **Step 3: 实现 strokeDashed**

在 `StrokeGenerator` 中追加。做法：沿折线按 dash pattern 累计弧长，切分出实线段，再对每段调用已有的 `stroke(..., Cap.BUTT, Join.BEVEL, ...)`。

```java
    /**
     * 生成虚线描边轮廓。
     *
     * <p>实现方式：沿折线按 dash 模式累计弧长切分出实线段，再逐段生成描边。
     * 相位跨段连续，因此折线拐角处的虚线不会重新起算。
     *
     * @param points        扁平折线顶点数组
     * @param count         顶点个数
     * @param closed        是否闭合
     * @param width         线宽
     * @param cap           端点样式
     * @param join          接头样式
     * @param miterLimit    miter 阈值
     * @param dashPattern   虚线模式，偶数下标为实线长度、奇数下标为空白长度
     * @param dashPhase     起始相位（0 到模式总长之间）
     * @param roundSegments 圆角细分段数
     */
    public void strokeDashed(float[] points, int count, boolean closed, float width,
                             Cap cap, Join join, float miterLimit,
                             float[] dashPattern, float dashPhase, int roundSegments) {
        if (dashPattern == null || dashPattern.length == 0) {
            stroke(points, count, closed, width, cap, join, miterLimit, roundSegments);
            return;
        }
        float patternLength = 0f;
        for (float d : dashPattern) {
            patternLength += d;
        }
        if (patternLength <= 1e-6f) {
            reset();
            return;
        }

        reset();
        int segmentCount = closed ? count : count - 1;
        // 归一化相位到 [0, patternLength)
        float phase = dashPhase % patternLength;
        if (phase < 0f) {
            phase += patternLength;
        }

        float[] seg = new float[4];
        for (int i = 0; i < segmentCount; i++) {
            int a = i;
            int b = (i + 1) % count;
            float ax = points[a * 2], ay = points[a * 2 + 1];
            float bx = points[b * 2], by = points[b * 2 + 1];
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) {
                continue;
            }
            float ux = dx / len, uy = dy / len;

            float cursor = 0f;              // 本段内已处理长度
            int patternIndex = 0;
            float consumed = phase;         // 当前模式格已消耗的长度
            // 定位起始模式格
            while (consumed >= dashPattern[patternIndex % dashPattern.length]) {
                consumed -= dashPattern[patternIndex % dashPattern.length];
                patternIndex++;
            }

            while (cursor < len) {
                float dashLen = dashPattern[patternIndex % dashPattern.length];
                if (dashLen <= 1e-6f) {
                    patternIndex++;
                    consumed = 0f;
                    continue;
                }
                float available = dashLen - consumed;
                float step = Math.min(available, len - cursor);
                boolean onDash = (patternIndex % 2) == 0;

                if (onDash && step > 1e-6f) {
                    float x0 = ax + ux * cursor, y0 = ay + uy * cursor;
                    float x1 = ax + ux * (cursor + step), y1 = ay + uy * (cursor + step);
                    seg[0] = x0; seg[1] = y0; seg[2] = x1; seg[3] = y1;
                    appendStrokeInto(seg, 2, width, cap, join, miterLimit, roundSegments);
                }
                cursor += step;
                consumed += step;
                if (consumed >= dashLen - 1e-6f) {
                    consumed = 0f;
                    patternIndex++;
                }
            }
        }
    }

    /**
     * 把一段描边追加到当前三角形列表中，不重置已有结果。
     * 与 {@link #stroke} 的区别是不调用 {@code reset()}，也不覆盖 triangleCount。
     */
    private void appendStrokeInto(float[] points, int count, float width,
                                  Cap cap, Join join, float miterLimit, int roundSegments) {
        int saved = triangleCount;
        float[] savedTriangles = triangles;
        // 用临时实例生成，避免递归 reset 影响状态
        StrokeGenerator tmp = new StrokeGenerator();
        tmp.stroke(points, count, false, width, cap, join, miterLimit, roundSegments);
        float[] produced = tmp.triangles();
        triangleCount = saved;
        triangles = savedTriangles;
        for (int i = 0; i < produced.length; i += 6) {
            emitTriangle(produced[i], produced[i + 1], produced[i + 2],
                    produced[i + 3], produced[i + 4], produced[i + 5]);
        }
    }
```

> **实现提示**：上面的 `appendStrokeInto` 每段虚线分配一个临时 `StrokeGenerator`，在热路径上不理想。测试通过后，可重构为把描边段直接写进当前实例（提取一个不 reset 的私有方法）。**不要**在测试通过之前做这个重构。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=StrokeDashTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/geom/StrokeGenerator.java \
        src/test/java/com/bingbaihanji/jfgl/geom/StrokeDashTest.java
git commit -m "feat(geom): 支持虚线描边"
```

---

## Task 9: gl/VertexBuffer — 可增长 VBO

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/gl/VertexBuffer.java`

- [ ] **Step 1: 确认现有 GL 抽象层的接口**

Run: `grep -n "int createVbo\|void bindVbo\|void uploadVboData\|void deleteVbo" src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java`
Expected: 四行，确认方法签名与下方代码一致

- [ ] **Step 2: 实现 VertexBuffer**

```java
package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;

import java.nio.ByteBuffer;

/**
 * 可增长的顶点缓冲对象（VBO）封装。
 *
 * <p>使用 {@code GL_DYNAMIC_DRAW}，每帧通过 {@link #upload} 覆盖写入。
 * 容量不足时自动重建更大的缓冲。
 *
 * <p>所有方法都必须在 GL 线程上调用。
 */
public final class VertexBuffer implements Disposable {

    private final GLAbstraction gl;
    private int vbo;
    private int capacityBytes;
    private boolean disposed = false;

    /**
     * @param gl               GL 抽象层
     * @param initialCapacityBytes 初始容量（字节）
     */
    public VertexBuffer(GLAbstraction gl, int initialCapacityBytes) {
        this.gl = gl;
        this.capacityBytes = Math.max(1, initialCapacityBytes);
        this.vbo = gl.createVbo();
        gl.bindVbo(vbo);
        gl.uploadVboData(new float[this.capacityBytes / 4]);
        gl.bindVbo(0);
    }

    /** 返回底层 VBO 的 ID。 */
    public int id() {
        return vbo;
    }

    public int capacityBytes() {
        return capacityBytes;
    }

    /**
     * 上传数据。若数据超过当前容量则先扩容。
     *
     * @param data 数据，position 为 0、limit 为有效字节数
     */
    public void upload(ByteBuffer data) {
        int needed = data.remaining();
        if (needed > capacityBytes) {
            grow(needed);
        }
        gl.bindVbo(vbo);
        gl.uploadVboBytes(data);
        gl.bindVbo(0);
    }

    private void grow(int neededBytes) {
        int newCapacity = capacityBytes;
        while (newCapacity < neededBytes) {
            newCapacity *= 2;
        }
        gl.deleteVbo(vbo);
        this.capacityBytes = newCapacity;
        this.vbo = gl.createVbo();
        gl.bindVbo(vbo);
        gl.uploadVboData(new float[newCapacity / 4]);
        gl.bindVbo(0);
    }

    @Override
    public void dispose() {
        if (!disposed) {
            gl.deleteVbo(vbo);
            disposed = true;
        }
    }
}
```

- [ ] **Step 3: 给 GLAbstraction 补一个字节上传方法**

在 `GLAbstraction` 接口中追加：

```java
    /**
     * 上传字节数据到当前绑定的 VBO。
     *
     * @param data 要上传的字节数据
     */
    void uploadVboBytes(java.nio.ByteBuffer data);
```

在 `LwjglGLAbstraction` 中实现：

```java
    @Override
    public void uploadVboBytes(java.nio.ByteBuffer data) {
        org.lwjgl.opengl.GL15.glBufferData(
                org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, data,
                org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
    }
```

- [ ] **Step 4: 编译确认通过**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`（无测试，本任务只做编译验证）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/gl/VertexBuffer.java \
        src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java \
        src/main/java/com/bingbaihanji/jfgl/gl/LwjglGLAbstraction.java
git commit -m "feat(gl): 加入可增长 VertexBuffer 与字节上传支持"
```

---

## Task 10: renderer/RenderBatch — 着色器与命令执行

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java`

- [ ] **Step 1: 实现 RenderBatch**

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.gl.VertexBuffer;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glDisableVertexAttribArray;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL11.glVertexAttribPointer;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glScissorTest;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL30.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

/**
 * GL 侧的批处理提交器：把 {@link VertexWriter} 收集的顶点上传并执行绘制命令。
 *
 * <p>本类只负责"把已收集的数据画出来"，不做任何几何计算。
 * 全部方法必须在 GL 线程上调用。
 */
public final class RenderBatch implements Disposable {

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;
            layout(location = 3) in uint aId;
            out vec2 vUV;
            out vec4 vColor;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = aUV;
                vColor = aColor;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUV) * vColor;
            }
            """;

    private final GLAbstraction gl;
    private final ShaderProgram shader;
    private final VertexBuffer vertexBuffer;

    private int vao;
    private int whiteTexture;

    /** 视口高度，用于把裁剪矩形从 y 向下翻转为 GL 的 y 向上。 */
    private int viewportHeight;
    private boolean disposed = false;

    public RenderBatch(GLAbstraction gl, int initialVertexCapacity) {
        this.gl = gl;
        this.shader = gl.createShader(VERTEX_SHADER, FRAGMENT_SHADER);
        this.vertexBuffer = new VertexBuffer(gl, initialVertexCapacity * VertexFormat.STRIDE_BYTES);
        this.whiteTexture = gl.createTexture(1, 1, new int[]{0xFFFFFFFF});
        createVao();
    }

    private void createVao() {
        vao = gl.createVao();
        gl.bindVao(vao);
        gl.bindVbo(vertexBuffer.id());

        glVertexAttribPointer(0, 2, GL_FLOAT, false, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_POSITION);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, 2, GL_FLOAT, false, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_UV);
        glEnableVertexAttribArray(1);

        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_COLOR);
        glEnableVertexAttribArray(2);

        // ID 是整数属性，必须用 IPointer，不能用归一化的 VertexAttribPointer
        glVertexAttribIPointer(3, 1, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT,
                VertexFormat.STRIDE_BYTES, VertexFormat.OFFSET_ID);
        glEnableVertexAttribArray(3);

        gl.bindVao(0);
    }

    /** 设置本帧的视口高度，用于裁剪坐标换算。 */
    public void setViewportHeight(int height) {
        this.viewportHeight = height;
    }

    /** 返回 1×1 白色纹理的 ID，纯色绘制时绑定它。 */
    public int whiteTextureId() {
        return whiteTexture;
    }

    /**
     * 提交并绘制一帧。
     *
     * @param writer 已收集好的顶点与命令
     */
    public void submit(VertexWriter writer) {
        if (writer.vertexCount() == 0) {
            return;
        }
        vertexBuffer.upload(writer.buffer());

        shader.use();
        gl.bindVao(vao);
        gl.bindVbo(vertexBuffer.id());

        gl.enableBlend();
        org.lwjgl.opengl.GL11.glBlendFunc(
                org.lwjgl.opengl.GL11.GL_ONE,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        glScissorTest(true);

        List<DrawCommand> commands = writer.commands();
        for (DrawCommand command : commands) {
            if (command.vertexCount() == 0) {
                continue;
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, command.textureId());
            applyScissor(command);
            glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
        }

        glScissorTest(false);
        gl.bindVao(0);
        shader.unuse();
        gl.disableBlend();
    }

    private void applyScissor(DrawCommand command) {
        int y = viewportHeight - command.scissorY() - command.scissorHeight();
        glScissor(command.scissorX(), y, command.scissorWidth(), command.scissorHeight());
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        shader.dispose();
        vertexBuffer.dispose();
        gl.deleteVao(vao);
        org.lwjgl.opengl.GL11.glDeleteTextures(whiteTexture);
        disposed = true;
    }
}
```

> **实现提示**：`glEnableVertexAttribArray` 与 `glVertexAttribPointer` 同时存在于 GL11 和 GL20 静态导入中会产生歧义，实现时只保留 GL20 的导入。上面的 import 列表已标出冲突项，请以能编译通过的最小集合为准。

- [ ] **Step 2: 编译并修复导入冲突**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`。若报导入歧义，按提示删除 GL11 中重复的静态导入。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java
git commit -m "feat(renderer): 实现 GL 侧批处理提交"
```

---

## Task 11: renderer/Gc.kt — 用户门面（变换栈与裁剪）

**Files:**
- Create: `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt`

- [ ] **Step 1: 实现 Gc 的骨架、变换栈与裁剪**

```kotlin
package com.bingbaihanji.jfgl.renderer

import com.bingbaihanji.jfgl.math.Mat3
import com.bingbaihanji.jfgl.util.Color
import com.bingbaihanji.jfgl.util.Rect

/**
 * 立即模式的 2D 绘制上下文，提供类似 Canvas 的 API。
 *
 * 所有绘制调用只往顶点缓冲追加数据，不做 GL 调用；真正提交发生在 [endFrame]。
 *
 * 坐标系统为**像素、原点左上、y 向下**。
 */
class Gc internal constructor(private val batch: RenderBatch) {

    private val writer = VertexWriter(INITIAL_VERTEX_CAPACITY)

    /** 像素坐标 → NDC 的基础矩阵。 */
    private var baseMatrix: Mat3 = Mat3.identity()

    /** 用户变换栈。索引 0 是当前矩阵。 */
    private val transformStack = ArrayDeque<Mat3>()

    /** 裁剪矩形栈（用户坐标，未变换）。 */
    private val clipStack = ArrayDeque<Rect>()

    private var currentMatrix: Mat3 = Mat3.identity()
    private var currentClip: Rect? = null

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var frameActive = false

    /** 当前填充样式（ARGB 打包）。 */
    var fill: Int = 0xFFFFFFFF.toInt()

    /** 当前描边样式。 */
    var stroke: Int = 0xFF000000.toInt()

    /** 当前线宽（用户坐标单位）。 */
    var lineWidth: Float = 1f

    /** 全局不透明度（0-1），与样式颜色相乘。 */
    var globalAlpha: Float = 1f

    // ------------------------------------------------------------------
    // 帧
    // ------------------------------------------------------------------

    /** 开始一帧。 */
    fun beginFrame(width: Int, height: Int) {
        check(!frameActive) { "beginFrame 与 endFrame 必须配对调用" }
        viewportWidth = width
        viewportHeight = height
        baseMatrix = Mat3.translation(-1f, 1f)
            .multiply(Mat3.scale(2f / width, -2f / height))
        currentMatrix = baseMatrix
        transformStack.clear()
        clipStack.clear()
        currentClip = null
        writer.reset()
        frameActive = true
        batch.setViewportHeight(height)
    }

    /** 结束一帧并提交绘制。 */
    fun endFrame() {
        check(frameActive) { "endFrame 调用于 beginFrame 之前" }
        batch.submit(writer)
        frameActive = false
    }

    // ------------------------------------------------------------------
    // 变换
    // ------------------------------------------------------------------

    /** 压入当前变换与裁剪状态。 */
    fun save() {
        transformStack.addLast(currentMatrix)
        clipStack.addLast(currentClip ?: FULL_CLIP)
    }

    /** 弹出并恢复变换与裁剪状态。 */
    fun restore() {
        check(transformStack.isNotEmpty()) { "restore 与 save 不配对，栈为空" }
        currentMatrix = transformStack.removeLast()
        val clip = clipStack.removeLast()
        currentClip = if (clip === FULL_CLIP) null else clip
    }

    fun translate(tx: Float, ty: Float) {
        currentMatrix = currentMatrix.multiply(Mat3.translation(tx, ty))
    }

    fun scale(sx: Float, sy: Float) {
        currentMatrix = currentMatrix.multiply(Mat3.scale(sx, sy))
    }

    /** 旋转。正值为屏幕上的顺时针方向（与 HTML Canvas 一致）。 */
    fun rotate(degrees: Float) {
        currentMatrix = currentMatrix.multiply(Mat3.rotation(Math.toRadians(degrees.toDouble()).toFloat()))
    }

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    /**
     * 用矩形裁剪后续绘制。
     *
     * 注意：因底层使用 `glScissor`，实际生效的是**变换后包围盒**。
     * 若当前变换包含旋转，裁剪区域会是旋转后矩形的轴对齐包围盒，而非旋转矩形本身。
     */
    fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        val corners = floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val p = currentMatrix.transform(
                com.bingbaihanji.jfgl.math.Vec2(corners[i * 2], corners[i * 2 + 1]))
            minX = minOf(minX, p.x()); minY = minOf(minY, p.y())
            maxX = maxOf(maxX, p.x()); maxY = maxOf(maxY, p.y())
        }
        val device = toDeviceRect(minX, minY, maxX, maxY)
        currentClip = currentClip?.intersect(device) ?: device
    }

    /** 把 NDC 包围盒换算成帧缓冲像素矩形。 */
    private fun toDeviceRect(ndcMinX: Float, ndcMinY: Float, ndcMaxX: Float, ndcMaxY: Float): Rect {
        val x0 = (ndcMinX * 0.5f + 0.5f) * viewportWidth
        val x1 = (ndcMaxX * 0.5f + 0.5f) * viewportWidth
        val y0 = (1f - (ndcMaxY * 0.5f + 0.5f)) * viewportHeight
        val y1 = (1f - (ndcMinY * 0.5f + 0.5f)) * viewportHeight
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun Rect.intersect(other: Rect): Rect {
        val nx = maxOf(x, other.x)
        val ny = maxOf(y, other.y)
        val nr = minOf(x + width, other.x + other.width)
        val nb = minOf(y + height, other.y + other.height)
        return Rect(nx, ny, maxOf(0f, nr - nx), maxOf(0f, nb - ny))
    }

    // ------------------------------------------------------------------
    // 供后续任务使用
    // ------------------------------------------------------------------

    internal fun writer(): VertexWriter = writer

    internal fun matrix(): Mat3 = currentMatrix

    internal fun clip(): Rect = currentClip ?: FULL_CLIP

    internal fun syncState(textureId: Int) {
        val c = clip()
        writer.setState(textureId, c.x.toInt(), c.y.toInt(),
            maxOf(1f, c.width).toInt(), maxOf(1f, c.height).toInt())
    }

    /** 应用全局不透明度后打包颜色。 */
    internal fun packColor(argb: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255f * globalAlpha
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return VertexFormat.packPremultiplied(r, g, b, a)
    }

    companion object {
        private const val INITIAL_VERTEX_CAPACITY = 65536
        internal val FULL_CLIP = Rect(0f, 0f, 1e9f, 1e9f)
    }
}
```

- [ ] **Step 2: 编译确认通过**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt
git commit -m "feat(renderer): 加入 Gc 门面（变换栈与裁剪）"
```

---

## Task 12: renderer/Gc — 形状与路径绘制，端到端跑通

**Files:**
- Modify: `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt`
- Create: `src/main/kotlin/com/bingbaihanji/jfgl/dsl/JFGL.kt`（重写）
- Delete: `src/main/kotlin/com/bingbaihanji/jfgl/dsl/DrawDSL.kt`

- [ ] **Step 1: 给 Gc 补绘制方法**

在 `Gc` 中追加：

```kotlin
    // ------------------------------------------------------------------
    // 形状
    // ------------------------------------------------------------------

    /** 填充矩形，可选圆角。 */
    @JvmOverloads
    fun fillRect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        emitShape(rectOutline(x, y, w, h, radius), fill)
    }

    /** 描边矩形，可选圆角。 */
    @JvmOverloads
    fun strokeRect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        strokeOutline(rectOutline(x, y, w, h, radius))
    }

    private fun rectOutline(x: Float, y: Float, w: Float, h: Float, radius: Float): FloatArray {
        if (radius <= 0f) {
            return floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
        }
        val r = minOf(radius, w * 0.5f, h * 0.5f)
        val seg = 6
        val pts = ArrayList<Float>(4 * (seg + 1) * 2)
        fun arc(cx: Float, cy: Float, startDeg: Float) {
            for (i in 0..seg) {
                val a = Math.toRadians((startDeg + 90.0 * i / seg)).toFloat()
                pts.add(cx + r * kotlin.math.cos(a))
                pts.add(cy + r * kotlin.math.sin(a))
            }
        }
        arc(x + w - r, y + r, -90f)
        arc(x + w - r, y + h - r, 0f)
        arc(x + r, y + h - r, 90f)
        arc(x + r, y + r, 180f)
        return pts.toFloatArray()
    }

    /** 填充圆。 */
    @JvmOverloads
    fun fillCircle(cx: Float, cy: Float, radius: Float, segments: Int = 0) {
        emitShape(circleOutline(cx, cy, radius, segments), fill)
    }

    /** 描边圆。 */
    @JvmOverloads
    fun strokeCircle(cx: Float, cy: Float, radius: Float, segments: Int = 0) {
        strokeOutline(circleOutline(cx, cy, radius, segments))
    }

    private fun circleOutline(cx: Float, cy: Float, radius: Float, segments: Int): FloatArray {
        val seg = if (segments > 0) segments else circleSegments(radius)
        val pts = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            pts[i * 2] = cx + radius * kotlin.math.cos(a)
            pts[i * 2 + 1] = cy + radius * kotlin.math.sin(a)
        }
        return pts
    }

    /** 按半径自适应细分段数，保证视觉平滑且不过度细分。 */
    private fun circleSegments(radius: Float): Int {
        val scale = matrixScale()
        val deviceRadius = kotlin.math.abs(radius * scale)
        return (deviceRadius * 0.7f).toInt().coerceIn(12, 256)
    }

    /** 填充椭圆。 */
    @JvmOverloads
    fun fillEllipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 0) {
        emitShape(ellipseOutline(cx, cy, rx, ry, segments), fill)
    }

    /** 描边椭圆。 */
    @JvmOverloads
    fun strokeEllipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 0) {
        strokeOutline(ellipseOutline(cx, cy, rx, ry, segments))
    }

    private fun ellipseOutline(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int): FloatArray {
        val seg = if (segments > 0) segments else circleSegments(maxOf(rx, ry))
        val pts = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            pts[i * 2] = cx + rx * kotlin.math.cos(a)
            pts[i * 2 + 1] = cy + ry * kotlin.math.sin(a)
        }
        return pts
    }

    /** 绘制直线段。 */
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        strokeOutline(floatArrayOf(x1, y1, x2, y2))
    }

    /** 填充多边形。 */
    fun fillPolygon(points: FloatArray) {
        emitShape(points, fill)
    }

    /** 描边折线。 */
    @JvmOverloads
    fun strokePolyline(points: FloatArray, closed: Boolean = false) {
        strokeOutline(points, closed)
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    private val path = com.bingbaihanji.jfgl.geom.Path()
    private val flattener = com.bingbaihanji.jfgl.geom.Flattener()
    private val tessellator = com.bingbaihanji.jfgl.geom.Tessellator()
    private val strokeGenerator = com.bingbaihanji.jfgl.geom.StrokeGenerator()

    fun beginPath() {
        path.reset()
    }

    fun moveTo(x: Float, y: Float) = path.moveTo(x, y).let { }
    fun lineTo(x: Float, y: Float) = path.lineTo(x, y).let { }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = path.quadTo(cx, cy, x, y).let { }
    fun bezierCurveTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) =
        path.cubicTo(c1x, c1y, c2x, c2y, x, y).let { }
    fun closePath() = path.close().let { }

    /**
     * 填充当前路径。
     *
     * **本任务已知限制**：路径中的所有子路径会被展平后当作**单个**多边形三角化。
     * 因此"外轮廓 + 内挖空"这类多子路径填充结果不正确。
     * 修正方式是改用 `Tessellator.tessellateWithHoles`，按子路径起点切分
     * （`Flattener.subPathCount()` / `subPathStart(i)` 已提供该信息），
     * 第一个子路径作外轮廓、其余作洞。此项在需要环形/饼图填充时补上。
     */
    fun fillPath() {
        if (path.isEmpty) return
        flattener.flatten(path, matrixScale())
        if (flattener.pointCount() < 3) return
        tessellator.tessellate(flattenerPoints(), flattener.pointCount(), true)
        emitTriangles(tessellator.triangles(), fill)
    }

    /** 描边当前路径。 */
    fun strokePath() {
        if (path.isEmpty) return
        flattener.flatten(path, matrixScale())
        if (flattener.pointCount() < 2) return
        strokeOutline(flattenerPoints())
    }

    private fun flattenerPoints(): FloatArray {
        val n = flattener.pointCount()
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            pts[i * 2] = flattener.x(i)
            pts[i * 2 + 1] = flattener.y(i)
        }
        return pts
    }

    // ------------------------------------------------------------------
    // 内部发射
    // ------------------------------------------------------------------

    private fun emitShape(points: FloatArray, color: Int) {
        if (points.size < 6) return
        tessellator.tessellate(points, points.size / 2, true)
        emitTriangles(tessellator.triangles(), color)
    }

    private fun strokeOutline(points: FloatArray, closed: Boolean = false) {
        if (points.size < 4) return
        strokeGenerator.stroke(points, points.size / 2, closed, lineWidth,
            com.bingbaihanji.jfgl.geom.StrokeGenerator.Cap.BUTT,
            com.bingbaihanji.jfgl.geom.StrokeGenerator.Join.MITER,
            4f, 8)
        emitTriangles(strokeGenerator.triangles(), stroke)
    }

    private fun emitTriangles(triangles: FloatArray, argb: Int) {
        if (triangles.isEmpty()) return
        syncState(batch.whiteTextureId())
        val packed = packColor(argb)
        val m = currentMatrix
        var i = 0
        while (i < triangles.size) {
            var k = 0
            while (k < 3) {
                val wx = triangles[i]; val wy = triangles[i + 1]
                val nx = m.transform(com.bingbaihanji.jfgl.math.Vec2(wx, wy))
                writer().vertex(nx.x(), nx.y(), 0f, 0f, packed, 0)
                i += 2
                k++
            }
        }
    }

    /** 返回当前变换的平均缩放因子。 */
    private fun matrixScale(): Float {
        val m = currentMatrix
        val a = m.transform(com.bingbaihanji.jfgl.math.Vec2(1f, 0f))
        val b = m.transform(com.bingbaihanji.jfgl.math.Vec2(0f, 1f))
        val sx = kotlin.math.hypot(a.x().toDouble(), a.y().toDouble()).toFloat()
        val sy = kotlin.math.hypot(b.x().toDouble(), b.y().toDouble()).toFloat()
        return (sx + sy) * 0.5f
    }
```

- [ ] **Step 2: 重写 dsl/JFGL.kt**

```kotlin
package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.input.MouseEvent
import javafx.stage.Stage

/** JFGL 应用配置与入口。 */
class JFGL {
    var title: String = "JFGL 应用"
    var width: Double = 800.0
    var height: Double = 600.0

    private var onInitCallback: ((Gc) -> Unit)? = null
    private var onRenderCallback: (Gc.() -> Unit)? = null

    fun onInit(block: (Gc) -> Unit) { onInitCallback = block }
    fun onRender(block: Gc.() -> Unit) { onRenderCallback = block }

    fun start() {
        val transfer = FXGLTransfer().apply {
            onRender { }
        }
        JFGLApplication.config = this
        JFGLApplication.transfer = transfer
        Application.launch(JFGLApplication::class.java)
    }

    internal fun invokeInit(gc: Gc) = onInitCallback?.invoke(gc)
    internal fun invokeRender(gc: Gc) = onRenderCallback?.invoke(gc)
}

internal class JFGLApplication : Application() {
    companion object {
        var config: JFGL? = null
        var transfer: FXGLTransfer? = null
    }

    override fun start(stage: Stage) {
        val mainView = com.bingbaihanji.jfgl.view.MainView().apply {
            center = transfer!!.createGlFXView()
        }
        val scene = Scene(mainView.createMainView(), config!!.width, config!!.height)
        stage.title = config!!.title
        stage.scene = scene
        stage.show()
    }

    override fun stop() {
        transfer?.dispose()
    }
}

/** JFGL DSL 入口。 */
fun jfgl(block: JFGL.() -> Unit) {
    JFGL().apply(block).start()
}
```

> **实现提示**：`FXGLTransfer` 目前不暴露 `DrawEngine` 之外的渲染钩子，且 `Gc` 需要 `RenderBatch`（依赖 GL 上下文）。因此 `jfgl` 与 `FXGLTransfer` 的接线是**下一步**：需要在 `FXGLTransfer` 的 `onInit`/`onRender` 回调里创建 `RenderBatch` 与 `Gc`，并转发调用。本步骤先让代码可编译，接线在 Step 3 完成。

> **本步必须一并删除 `example/DrawExample.kt`。** 它使用的是旧 DSL API
> （`onInit { draw -> }`、NDC 坐标的 `drawRect(...)`、已被移除的 `onClick`/`onMove`），
> 重写 `JFGL.kt` 后它无法编译。它由本任务新建的 `PipelineExample.kt` 取代。
> 计划原先把它的删除放在 Task 13，那会导致 Task 12 的 `mvn -o compile` 失败。
>
> ```bash
> git rm src/main/kotlin/com/bingbaihanji/jfgl/example/DrawExample.kt
> ```

- [ ] **Step 3: 在 FXGLTransfer 中接线**

修改 `src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt`：删除其中对 `DrawEngine` 的依赖，改为持有 `RenderBatch` 与 `Gc`，并新增 `onFrame` 回调：

```kotlin
    private var renderBatch: RenderBatch? = null
    private var gc: Gc? = null
    private var onFrameCallback: ((Gc) -> Unit)? = null

    fun onFrame(callback: (Gc) -> Unit) {
        onFrameCallback = callback
    }

    /** 在 GL 线程上返回绘制上下文；未初始化时为 null。 */
    fun gc(): Gc? = gc
```

在 `addOnInitEvent` 中：

```kotlin
            glClearColor(0.2f, 0.2f, 0.2f, 1.0f)
            val gl = LwjglGLAbstraction()
            renderBatch = RenderBatch(gl, 65536)
            gc = Gc(renderBatch!!)
            onInitCallback?.invoke()
```

在 `addOnRenderEvent` 中：

```kotlin
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            val context = gc
            if (context != null) {
                context.beginFrame(scaledWidth, scaledHeight)
                onFrameCallback?.invoke(context)
                context.endFrame()
            }
            onRenderCallback?.invoke()
```

在 `addOnDisposeEvent` 中：

```kotlin
            onDisposeCallback?.invoke()
            renderBatch?.dispose()
            renderBatch = null
            gc = null
```

- [ ] **Step 4: 写一个可见的示例**

新建 `src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineExample.kt`：

```kotlin
package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.dsl.jfgl
import com.bingbaihanji.jfgl.util.Color

fun main() {
    jfgl {
        title = "JFGL 批处理管线"
        width = 800.0
        height = 600.0

        onFrame {
            fill = 0xFFFF0000.toInt()
            fillRect(50f, 50f, 200f, 120f)

            fill = 0xFF00FF00.toInt()
            fillCircle(500f, 200f, 80f)

            stroke = 0xFF0000FF.toInt()
            lineWidth = 4f
            strokeRect(100f, 300f, 250f, 150f, radius = 16f)

            fill = 0xFF333333.toInt()
            fillPolygon(floatArrayOf(500f, 300f, 700f, 300f, 700f, 450f, 600f, 520f, 500f, 450f))

            stroke = 0xFFFF00FF.toInt()
            lineWidth = 3f
            beginPath()
            moveTo(50f, 550f)
            bezierCurveTo(200f, 450f, 300f, 650f, 450f, 550f)
            strokePath()
        }
    }
}
```

- [ ] **Step 5: 编译并运行确认窗口出图**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`

Run: `mvn exec:java -Dexec.mainClass="com.bingbaihanji.jfgl.example.PipelineExampleKt"`
Expected: 弹出窗口，显示红色矩形、绿色圆、蓝色圆角矩形描边、深灰多边形、品红贝塞尔曲线

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt \
        src/main/kotlin/com/bingbaihanji/jfgl/dsl/JFGL.kt \
        src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt \
        src/main/kotlin/com/bingbaihanji/jfgl/example/PipelineExample.kt
git rm src/main/kotlin/com/bingbaihanji/jfgl/dsl/DrawDSL.kt
git commit -m "feat: 打通批处理管线端到端绘制"
```

---

## Task 13: 清理旧代码

**Files:**
- Delete: 见下方列表

- [ ] **Step 1: 删除被取代的包与类**

```bash
git rm -r src/main/java/com/bingbaihanji/jfgl/scene
git rm -r src/main/java/com/bingbaihanji/jfgl/event
git rm -r src/main/java/com/bingbaihanji/jfgl/chart
git rm -r src/main/java/com/bingbaihanji/jfgl/engine
git rm src/main/java/com/bingbaihanji/jfgl/renderer/Path.java
git rm src/main/java/com/bingbaihanji/jfgl/renderer/BatchRenderer.java
git rm src/main/java/com/bingbaihanji/jfgl/renderer/RenderContext.java
git rm src/main/java/com/bingbaihanji/jfgl/renderer/TextRenderer.java
git rm src/main/java/com/bingbaihanji/jfgl/style/FillStyle.java
git rm src/main/java/com/bingbaihanji/jfgl/style/Style.java
git rm src/main/java/com/bingbaihanji/jfgl/style/TextStyle.java
git rm -r src/main/kotlin/com/bingbaihanji/jfgl/dsl/Shapes.kt \
          src/main/kotlin/com/bingbaihanji/jfgl/dsl/Charts.kt \
          src/main/kotlin/com/bingbaihanji/jfgl/dsl/Interactions.kt \
          src/main/kotlin/com/bingbaihanji/jfgl/dsl/Styles.kt
```

- [ ] **Step 2: 删除引用了已删类的示例与入口**

`App.kt` 依赖已删除的 `DrawEngine`/`DrawDSL`。删除它，并把 `Main.kt` 的启动目标改为新的示例：

```bash
git rm src/main/kotlin/com/bingbaihanji/jfgl/App.kt
```

> `example/DrawExample.kt` 已在 Task 12 删除（它依赖被重写的旧 DSL API），此处不再重复删除。

修改 `src/main/kotlin/com/bingbaihanji/jfgl/Main.kt` 为：

```kotlin
package com.bingbaihanji.jfgl

import javafx.application.Application
import com.bingbaihanji.jfgl.example.PipelineExample

fun main(args: Array<String>) {
    System.setProperty("prism.dirtyopts", "true")
    System.setProperty("prism.order", "d3d,sw")
    System.setProperty("prism.allowhidpi", "true")
    System.setProperty("prism.forceGPU", "true")
    System.setProperty("prism.vsync", "true")
    PipelineExample.main(args)
}
```

- [ ] **Step 3: 修正 pom.xml 中过时的 mainClass**

把 `pom.xml:269` 的

```xml
<mainClass>com.bingbaihanji.fxgl3d.AppKt</mainClass>
```

改为

```xml
<mainClass>com.bingbaihanji.jfgl.MainKt</mainClass>
```

- [ ] **Step 4: 全量编译与测试**

Run: `mvn -o clean test`
Expected: `BUILD SUCCESS`，所有 `geom`/`renderer` 测试通过，无编译错误

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor: 删除被取代的保留模式栈，统一为单一批处理管线"
```

---

## Task 14: 更新 CLAUDE.md

`CLAUDE.md` 中"两条互不相通的绘制路径"一节已完全过时，必须更新，否则后续会话会按错误的信息工作。

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 重写架构相关章节**

把 `CLAUDE.md` 中的以下内容替换：

- 删除"⚠️ 两条互不相通的绘制路径"整节，改为描述单一批处理管线
- 删除"已实现 vs 未实现速查"中的过期条目
- 更新"坐标与单位约定"表为单一坐标系（像素、原点左上、y 向下）
- 更新"启动链路"为 `Main.kt → PipelineExample → jfgl{} → FXGLTransfer → Gc`
- 更新包结构说明，加入 `geom/`
- 保留"构建配置须知"（仍然有效）
- 保留测试章节，但更新为"`geom/` 与 `renderer/` 已有单测，`mvn test` 可跑"

- [ ] **Step 2: 确认没有残留引用**

Run: `grep -n "DrawDSL\|DrawEngine\|Scene\|BatchRenderer\|两条" CLAUDE.md`
Expected: 无输出

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 更新 CLAUDE.md 以反映单一批处理管线"
```

---

## 后续计划（不在本计划范围）

子项目 A 完成后，以下能力各自单独出 spec 与计划：

- **子项目 B（SDF 文本）**：字形栅格化、距离场生成、动态图集、描边/阴影/发光 shader、CJK 排版。需要在顶点格式中启用已预留的 `uv` 与纹理切换路径（`Gc.syncState(textureId)` 已支持）。
- **子项目 C（GPU 拾取）**：ID 通道 FBO、PBO 异步读回、hover/click/drag 状态机。顶点格式中的 `id` 属性（location 3）已就位，只需新增一个输出 ID 的 fragment shader 与读回逻辑。
- **子项目 D（科学绘图图表）**：标度、刻度算法、坐标轴、系列类型、colormap/热力图/等值线。建立在本计划的 `Gc` 之上。

---

## 自检记录

**Spec 覆盖情况**

| Spec 章节 | 对应任务 |
|---|---|
| 4.1 帧生命周期 | Task 11（beginFrame/endFrame） |
| 4.2 顶点格式 | Task 3 |
| 4.3 着色器 | Task 10 |
| 4.4 无索引缓冲 | Task 3、Task 10（用 drawArrays） |
| 4.5 命令列表 | Task 4 |
| 4.6 缓冲增长 | Task 4（翻倍 + 上限 + flush 请求）、Task 9（VBO 扩容） |
| 4.7 渲染调度 | **未覆盖**——见下方说明 |
| 5.1 像素坐标 | Task 11（baseMatrix） |
| 5.2 CPU 烘焙变换 | Task 12（emitTriangles 中变换顶点） |
| 6 裁剪 | Task 11（clipRect + scissor） |
| 7.1 Paint 类型 | **延后**——见下方说明 |
| 7.2 归一化为纹理 | Task 10（纯色走白纹理） |
| 7.3 延迟上传 | **延后**——见下方说明 |
| 8.1 路径与平坦化 | Task 1、Task 2 |
| 8.2 填充三角化 | Task 5、Task 6 |
| 8.3 描边生成 | Task 7、Task 8 |
| 9 混合与抗锯齿 | Task 10（预乘 alpha）；MSAA 由 GLCanvas 参数控制，已在现有 `FXGLTransfer` 中 |
| 10 错误处理 | Task 4（状态误用）、Task 11（栈误用）；ShaderCompileException 见下方说明 |
| 11 测试策略 | Task 1–8 单测；黄金图测试见下方说明 |
| 12 代码迁移 | Task 13 |

**有意延后到后续计划的项**（本计划聚焦"管线跑通"，这些属于在其之上的能力）：

1. **Paint 体系与渐变（spec 7.1 / 7.3）**——纯色已通过"白纹理 + 顶点色"落地，渐变 LUT 与延迟纹理上传是独立的一块，且有明确的挂载点（`Gc.syncState` 已支持任意纹理 ID）。单独一个计划更清晰。
2. **黄金图测试（spec 11）**——需要 GLFW 离屏上下文，是一个自包含的基础设施任务，适合单独计划。
3. **RenderScheduler 接线（spec 4.7）**——现有实现依赖已删除的 `DrawEngine`，需重写为独立类并接入 `FXGLTransfer`。
4. **ShaderCompileException（spec 10）**——需先修正现有 `ShaderProgram` 的异常信息缺失问题。
5. **图像填充与 STB 解码（spec 7.3）**——依赖 Paint 体系。

这些延后项都是**新增**而非返工，不影响已完成部分的有效性。
