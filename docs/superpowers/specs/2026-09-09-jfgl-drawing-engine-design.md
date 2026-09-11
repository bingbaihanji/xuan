# JFGL 绘制引擎框架设计文档

> 日期：2026-09-09
> 状态：已确认

---

## 1. 项目概述

### 1.1 目标

构建一个基于 JavaFX + OpenGL（通过 openglfx-lwjgl）的 2D 绘制引擎框架，支持：

- 2D 矢量绘图
- 科学可视化
- 统计图表
- GPU 加速算法

### 1.2 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 2.3.0 |
| Java | 17 |
| JavaFX | 17.0.6 |
| LWJGL | 3.3.6 |
| openglfx-lwjgl | 4.2.3 |
| JOML | 1.10.5 |

---

## 2. 需求总结

| 维度 | 决策 |
|------|------|
| **用途** | 2D 绘图 + 科学可视化 |
| **核心能力** | 几何图形、文本、坐标系统、交互 |
| **渲染模式** | 混合模式（底层立即模式，上层保留模式） |
| **性能目标** | 实时/按需切换，中等规模（千~万级对象） |
| **扩展功能** | 统计图表、样式系统、GPU 算法（FFT 等） |
| **API 风格** | 底层命令式（Java），上层 Kotlin DSL |
| **语言分工** | Java（核心实现），Kotlin（API 接口） |

---

## 3. 架构设计

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  L4: Kotlin DSL API                                         │
│  com.bingbaihanji.jfgl.dsl                                  │
│  - 图形构建器、图表 DSL、样式 DSL、工具 DSL                     │
├─────────────────────────────────────────────────────────────┤
│  L3: Scene Graph                                            │
│  com.bingbaihanji.jfgl.scene                                │
│  - 节点树、空间索引(QuadTree)、脏标记、事件分发                   │
├─────────────────────────────────────────────────────────────┤
│  L2: Renderer                                               │
│  com.bingbaihanji.jfgl.render                               │
│  - 绘制命令、批处理、坐标变换、裁剪                              │
├─────────────────────────────────────────────────────────────┤
│  L1: GL Abstraction                                         │
│  com.bingbaihanji.jfgl.gl                                   │
│  - Shader管理、Buffer管理、状态管理、纹理管理                    │
├─────────────────────────────────────────────────────────────┤
│  L0: OpenGL (LWJGL + openglfx)                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 层级职责

| 层级 | 职责 | 语言 | 关键类 |
|------|------|------|--------|
| **L1 GL 抽象** | 封装 OpenGL 调用 | Java | GLContext, ShaderProgram, GLBuffer |
| **L2 渲染器** | 绘制命令、批处理 | Java | BatchRenderer, RenderContext, TextRenderer |
| **L3 场景图** | 节点树、空间索引 | Java | Scene, Node, QuadTree |
| **L4 DSL API** | 用户接口 | Kotlin | Shapes.kt, Charts.kt, Interactions.kt |
| **GPU 算法** | FFT、卷积等 | Java+GLSL | ComputeShader, GPUFFT |
| **事件系统** | 交互处理 | Java | EventDispatcher, EventHandler |

---

## 4. 包结构

```
com.bingbaihanji.jfgl/
├── gl/                    # L1: GL 抽象层
│   ├── GLContext.java
│   ├── GLState.java
│   ├── ShaderProgram.java
│   ├── GLBuffer.java
│   └── Texture2D.java
│
├── render/                # L2: 渲染器层
│   ├── RenderContext.java
│   ├── BatchRenderer.java
│   ├── PathRenderer.java
│   └── TextRenderer.java
│
├── scene/                 # L3: 场景图层
│   ├── Node.java
│   ├── ShapeNode.java
│   ├── TextNode.java
│   ├── GroupNode.java
│   ├── Scene.java
│   └── QuadTree.java
│
├── event/                 # 事件系统
│   ├── Event.java
│   ├── MouseEvent.java
│   ├── DragEvent.java
│   └── EventDispatcher.java
│
├── math/                  # 数学工具
│   ├── Vec2.java
│   ├── Mat3.java
│   ├── Transform.java
│   └── CoordinateSystem.java
│
├── chart/                 # 图表模块
│   ├── Chart.java
│   ├── LineChart.java
│   ├── BarChart.java
│   ├── PieChart.java
│   └── ScatterChart.java
│
├── gpu/                   # GPU 算法
│   ├── ComputeShader.java
│   ├── GPUFFT.java
│   └── GPUConvolution.java
│
├── style/                 # 样式系统
│   ├── Style.java
│   ├── FillStyle.java
│   ├── StrokeStyle.java
│   └── TextStyle.java
│
├── util/                  # 通用工具
│   ├── Color.java
│   ├── Rect.java
│   ├── Disposable.java
│   └── ResourceManager.java
│
├── engine/                # 引擎核心
│   ├── DrawEngine.java
│   └── RenderScheduler.java
│
└── dsl/                   # Kotlin DSL
    ├── Shapes.kt
    ├── Charts.kt
    ├── Styles.kt
    ├── Interactions.kt
    └── Utils.kt
```

---

## 5. 核心接口设计

### 5.1 L1: GL 抽象层

```java
// GL 上下文 - 封装 OpenGL 上下文生命周期
public interface GLContext {
    void makeCurrent();
    void release();
    boolean isCurrent();
    int getGlVersion();
}

// Shader 程序 - 编译、链接、Uniform 传递
public class ShaderProgram implements Disposable {
    private int programId;

    public ShaderProgram(String vertexSrc, String fragmentSrc);
    public void use();
    public void setUniform(String name, float value);
    public void setUniform(String name, Vec2 vec);
    public void setUniform(String name, Mat3 matrix);
    public void dispose();
}

// GL 缓冲区 - VBO/VAO/EBO 管理
public class GLBuffer implements Disposable {
    public GLBuffer(float[] data, int usage);
    public void bind();
    public void updateData(float[] data);
    public void dispose();
}

// 纹理
public class Texture2D implements Disposable {
    public Texture2D(int width, int height, ByteBuffer pixels);
    public void bind(int unit);
    public void dispose();
}
```

### 5.2 L2: 渲染器层

```java
// 渲染上下文 - 管理变换矩阵栈和裁剪区域
public class RenderContext {
    private MatrixStack transformStack;
    private Rect clipRegion;

    public void pushTransform();
    public void popTransform();
    public void translate(float x, float y);
    public void rotate(float angle);
    public void scale(float sx, float sy);
    public void setClip(Rect region);
    public Mat3 getMVPMatrix();
}

// 绘制命令 - 立即模式的基础
public interface DrawCommand {
    void execute(RenderContext ctx);
}

// 批处理渲染器 - 收集命令，批量提交
public class BatchRenderer implements Disposable {
    public void begin();
    public void drawLine(Vec2 from, Vec2 to, Style style);
    public void drawRect(Rect rect, Style style);
    public void drawCircle(Vec2 center, float radius, Style style);
    public void drawPath(Path path, Style style);
    public void drawText(String text, Vec2 position, TextStyle style);
    public void end(); // 批量提交到 GPU
}

// 路径 - 贝塞尔曲线等复杂形状
public class Path {
    public Path moveTo(float x, float y);
    public Path lineTo(float x, float y);
    public Path quadTo(float cx, float cy, float x, float y);
    public Path cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);
    public Path close();
}
```

### 5.3 L3: 场景图层

```java
// 场景节点基类
public abstract class Node {
    protected Transform transform;
    protected Style style;
    protected Rect bounds; // 包围盒
    protected boolean dirty;
    protected Node parent;

    public abstract void render(RenderContext ctx);
    public abstract boolean hitTest(Vec2 point);
    public abstract void updateBounds();

    public void markDirty() { /* 向上冒泡脏标记 */ }
}

// 形状节点 - 持有几何数据
public class ShapeNode extends Node {
    private Path path;
    private FillStyle fill;
    private StrokeStyle stroke;

    public void render(RenderContext ctx) {
        ctx.pushTransform();
        ctx.applyTransform(transform);
        // 使用 BatchRenderer 绘制
        ctx.popTransform();
    }
}

// 文本节点
public class TextNode extends Node {
    private String text;
    private Font font;
    private TextStyle style;
}

// 容器节点 - 子节点管理
public class GroupNode extends Node {
    private List<Node> children;

    public void addChild(Node child);
    public void removeChild(Node child);
    public List<Node> getChildren();
}

// 场景 - 根容器 + 空间索引
public class Scene {
    private GroupNode root;
    private QuadTree spatialIndex;

    public void render(RenderContext ctx);
    public Node hitTest(Vec2 point); // 空间索引加速
    public void updateSpatialIndex();
}
```

### 5.4 坐标系统

```java
// 坐标转换器
public class CoordinateSystem {
    private Mat3 worldToScreen; // 世界 → 屏幕
    private Mat3 screenToWorld; // 屏幕 → 世界

    public Vec2 worldToScreen(Vec2 worldPoint);
    public Vec2 screenToWorld(Vec2 screenPoint);

    // 视图控制
    public void pan(float dx, float dy);
    public void zoom(float factor, Vec2 center);
    public void rotate(float angle, Vec2 center);
    public void fitBounds(Rect worldBounds);
}
```

---

## 6. 样式系统

```java
// 样式基类 - 不可变对象
public abstract class Style {
    public abstract void apply(RenderContext ctx);
}

// 填充样式
public class FillStyle extends Style {
    private final Color color;
    private final float opacity;

    public static FillStyle solid(Color color);
    public static FillStyle solid(Color color, float opacity);
}

// 描边样式
public class StrokeStyle extends Style {
    private final Color color;
    private final float lineWidth;
    private final LineCap lineCap;    // BUTT, ROUND, SQUARE
    private final LineJoin lineJoin;  // MITER, ROUND, BEVEL
    private final float[] dashPattern;

    public static StrokeStyle of(Color color, float width);
}

// 文本样式
public class TextStyle extends Style {
    private final Font font;
    private final Color color;
    private final Alignment align;    // LEFT, CENTER, RIGHT
    private final float lineHeight;
}

// 组合样式
public class ShapeStyle {
    private final FillStyle fill;
    private final StrokeStyle stroke;

    public static ShapeStyle filled(FillStyle fill);
    public static ShapeStyle stroked(StrokeStyle stroke);
    public static ShapeStyle both(FillStyle fill, StrokeStyle stroke);
}

// 颜色
public class Color {
    private final float r, g, b, a;

    public static Color rgb(int r, int g, int b);
    public static Color rgba(int r, int g, int b, int a);
    public static Color hsl(float h, float s, float l);
    public static Color fromHex(String hex);

    public static final Color RED, GREEN, BLUE, WHITE, BLACK, ...;
}
```

---

## 7. 事件系统

### 7.1 事件类型

```java
// 事件基类
public abstract class Event {
    private boolean consumed;
    public void consume() { this.consumed = true; }
    public boolean isConsumed() { return consumed; }
}

// 鼠标事件
public class MouseEvent extends Event {
    private final Vec2 position;      // 屏幕坐标
    private final Vec2 worldPosition; // 世界坐标
    private final MouseButton button;
    private final EventType type;     // PRESS, RELEASE, MOVE, DRAG, CLICK
    private final int clickCount;
    private final float deltaX, deltaY;
}

// 拖拽事件
public class DragEvent extends Event {
    private final Vec2 startPosition;
    private final Vec2 currentPosition;
    private final Vec2 delta;
    private final Node target;
}

// 滚轮事件
public class ScrollEvent extends Event {
    private final Vec2 position;
    private final float deltaX, deltaY;
}
```

### 7.2 事件分发

```java
public class EventDispatcher {
    private Scene scene;
    private CoordinateSystem coords;
    private Node dragTarget;
    private Vec2 dragStart;

    public void onMouseEvent(javafx.scene.input.MouseEvent fxEvent);
    public void onScrollEvent(javafx.scene.input.ScrollEvent fxEvent);

    // 视图控制（中键拖拽平移，滚轮缩放）
    public void enableViewControls();
}
```

---

## 8. GPU 算法框架

### 8.1 计算着色器基类

```java
public abstract class ComputeShader implements Disposable {
    protected ShaderProgram program;

    protected abstract String getComputeShaderSource();

    public void dispatch(int numGroupsX, int numGroupsY, int numGroupsZ);
    public void memoryBarrier();

    public void bindSSBO(int binding, GLBuffer buffer);
    public void bindImageTexture(int unit, Texture2D texture, int access);
}
```

### 8.2 GPU-FFT

```java
public class GPUFFT extends ComputeShader {
    private GLBuffer inputBuffer;
    private GLBuffer outputBuffer;
    private int N; // FFT 点数（必须是 2 的幂）

    public GPUFFT(int n);
    public void forward(float[] real, float[] imag);
    public void inverse(float[] real, float[] imag);
    public float[] getMagnitude();
}
```

### 8.3 FFT 计算着色器

```glsl
#version 430

layout(local_size_x = 256) in;

layout(std430, binding = 0) buffer InputBuffer {
    vec2 data[];
};

layout(std430, binding = 1) buffer OutputBuffer {
    vec2 result[];
};

uniform int N;
uniform int stage;
uniform bool inverse;

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= N) return;

    uint pairIdx = idx ^ (1 << stage);
    if (pairIdx > idx) {
        float angle = 2.0 * 3.14159265
            * float(idx & ((1 << stage) - 1))
            / float(1 << (stage + 1));
        if (inverse) angle = -angle;

        vec2 twiddle = vec2(cos(angle), sin(angle));
        vec2 a = data[idx];
        vec2 b = data[pairIdx];
        vec2 t = vec2(
            twiddle.x * b.x - twiddle.y * b.y,
            twiddle.x * b.y + twiddle.y * b.x
        );
        result[idx] = a + t;
        result[pairIdx] = a - t;
    }
}
```

---

## 9. 性能优化策略

### 9.1 批处理渲染

```java
public class BatchRenderer {
    private static final int MAX_BATCH_SIZE = 8192;

    private void flush() {
        // 1. 排序（按材质/状态分组，减少状态切换）
        pendingCommands.sort(Comparator.comparingInt(DrawCommand::getSortKey));

        // 2. 合并到 VBO
        int vertexCount = 0;
        for (DrawCommand cmd : pendingCommands) {
            vertexCount += cmd.fillVertexData(batchVBO, vertexCount);
        }

        // 3. 一次 draw call 提交
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    }
}
```

### 9.2 空间索引（QuadTree）

```java
public class QuadTree {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_OBJECTS_PER_NODE = 16;

    public void insert(Node node);
    public void remove(Node node);
    public void update(Node node);

    public List<Node> query(Rect region);
    public Node hitTest(Vec2 point);
}
```

### 9.3 脏标记系统

```java
public class Scene {
    private boolean needsRedraw = true;

    public void markDirty() { needsRedraw = true; }

    public void render(RenderContext ctx) {
        if (!needsRedraw) return;
        renderDirtyNodes(ctx);
        needsRedraw = false;
    }
}
```

### 9.4 渲染模式切换

```java
public enum RenderMode {
    ON_DEMAND,  // 按需重绘
    REALTIME    // 每帧重绘
}

public class RenderScheduler {
    private RenderMode mode = RenderMode.ON_DEMAND;

    public void switchToRealtime();
    public void switchToOnDemand();
}
```

---

## 10. 文本渲染

使用 SDF（Signed Distance Field）技术：

```java
public class TextRenderer {
    private Texture2D fontAtlas;
    private Map<Character, GlyphInfo> glyphMap;
    private ShaderProgram sdfShader;

    public void init(Font font);
    public void renderText(RenderContext ctx, String text, Vec2 pos, TextStyle style);
}
```

SDF 优势：
- 放大不失真
- 可实现描边、阴影等效果
- 性能优于直接字形渲染

数学公式渲染：
- 基础数学符号（±×÷√∫∑∏等）通过 Unicode 字体支持
- 复杂公式（分数、上下标、矩阵）通过组合渲染实现
- 可选集成 JLaTeXMath 库进行 LaTeX 公式渲染

---

## 11. 引擎核心入口

```java
public class DrawEngine implements Disposable {
    private final GLCanvas canvas;
    private final GLContext glContext;
    private final RenderContext renderCtx;
    private final Scene scene;
    private final BatchRenderer renderer;
    private final EventDispatcher events;
    private final CoordinateSystem coords;
    private final RenderScheduler scheduler;

    public DrawEngine(GLCanvas canvas);

    // 生命周期
    private void onGLInit();
    private void onRender(GLRenderEvent event);
    private void onResize(GLReshapeEvent event);
    private void onDispose();

    // 公共 API
    public Scene getScene();
    public CoordinateSystem getCoords();
    public EventDispatcher getEvents();
    public GPUFFT getFFT(int n);
    public void repaint();
}
```

---

## 12. Kotlin DSL API

### 12.1 图形构建

```kotlin
fun Scene.line(init: LineBuilder.() -> Unit): LineNode
fun Scene.rect(init: RectBuilder.() -> Unit): RectNode
fun Scene.circle(init: CircleBuilder.() -> Unit): CircleNode
fun Scene.polygon(init: PolygonBuilder.() -> Unit): PolygonNode
fun Scene.path(init: PathBuilder.() -> Unit): PathNode
fun Scene.text(init: TextBuilder.() -> Unit): TextNode
```

### 12.2 样式 DSL

```kotlin
fun fill(color: Color, opacity: Float = 1.0f): FillStyle
fun stroke(color: Color, init: StrokeStyleBuilder.() -> Unit = {}): StrokeStyle
fun textStyle(font: Font, init: TextStyleBuilder.() -> Unit = {}): TextStyle
```

### 12.3 交互 DSL

```kotlin
fun <T : Node> T.onClick(handler: (MouseEvent) -> Unit): T
fun <T : Node> T.onDrag(handler: (DragEvent) -> Unit): T
fun <T : Node> T.onHover(handler: (Boolean) -> Unit): T
fun <T : Node> T.onScroll(handler: (ScrollEvent) -> Unit): T
```

### 12.4 图表 DSL

```kotlin
fun Scene.lineChart(init: LineChartBuilder.() -> Unit): LineChartNode
fun Scene.barChart(init: BarChartBuilder.() -> Unit): BarChartNode
fun Scene.pieChart(init: PieChartBuilder.() -> Unit): PieChartNode
fun Scene.scatterChart(init: ScatterChartBuilder.() -> Unit): ScatterChartNode
```

### 12.5 使用示例

```kotlin
// 基础图形
scene.rect {
    position(100, 100)
    size(200, 100)
    fill(Color.CORAL)
    cornerRadius(10.0)

    onClick { println("点击位置: ${it.worldPosition}") }
    onDrag { position += it.delta }
    onHover { fill(if (it) Color.ORANGE else Color.CORAL) }
}

// 折线图
scene.lineChart {
    position(50, 300)
    size(500, 300)

    xAxis { label("X轴"); range(0, 100) }
    yAxis { label("Y轴"); range(0, 50) }

    series("温度") {
        data(temperatureData)
        line { color = Color.RED; width = 2f }
        point { shape = PointShape.CIRCLE; size = 4f }
    }

    legend { position = LegendPosition.TOP_RIGHT }
}
```

---

## 13. 错误处理

```java
public class DrawEngineException extends RuntimeException {
    public enum ErrorCode {
        GL_INIT_FAILED,
        SHADER_COMPILE_FAILED,
        BUFFER_OVERFLOW,
        TEXTURE_LOAD_FAILED,
        INVALID_OPERATION
    }
}

public class ShaderCompileException extends DrawEngineException {
    public ShaderCompileException(String shaderSource, String errorLog);
}
```

---

## 14. 与现有代码整合

修改 `App.kt`：

```kotlin
class App : Application() {
    private lateinit var engine: DrawEngine

    override fun start(stage: Stage) {
        val mainView = MainView()

        val transfer = FXGLTransfer(fps = 60.0, msaa = 4)
        val canvas = transfer.createGlFXView() as GLCanvas

        engine = DrawEngine(canvas)
        mainView.center = canvas

        buildScene(engine)

        val scene = Scene(mainView.createMainView(), 1200.0, 800.0)
        stage.scene = scene
        stage.show()
    }

    override fun stop() {
        engine.dispose()
    }
}
```
