
# JFGL 开发者手册

本文档面向使用和扩展 JFGL 的开发人员，说明总体架构、设计模式、线程与资源模型，以及几何图形、文字、统计图表三大模块的 API。

## 1. 总体设计

### 1.1 目标

JFGL 是一个嵌入 JavaFX 的高性能 2D 绘制框架。API 使用立即模式，内部通过 OpenGL 批处理完成绘制，适合大量几何图形、高频刷新、GPU 拾取、SDF 文本和实时图表。

JFGL 不替代 JavaFX Scene Graph：

- JavaFX 负责窗口、布局、控件和事件；
- JFGL 负责高吞吐像素绘制；
- 两者通过 GLCanvas 和 FXGLTransfer 集成。

### 1.2 模块

```text
jfgl-core
  math / geom / chart / util

jfgl-render-gl
  gl / renderer / text / gpu / chartrender

jfgl-javafx
  glview / dsl / view / example
```

依赖方向只能向上：

```text
jfgl-core -> jfgl-render-gl -> jfgl-javafx
```

jfgl-core 不依赖 JavaFX、LWJGL 或 OpenGL，因此可以独立测试。

### 1.3 设计模式

#### 立即模式

应用每帧调用 Gc。绘制调用只写入 VertexWriter，帧末由 RenderBatch 上传 VBO 并提交 OpenGL draw call。

#### 门面模式

Gc 是应用门面，统一提供绘制状态、变换、裁剪、文字、拾取和图表入口。业务代码不应直接操作 RenderBatch 或 OpenGL 对象。

#### 批处理模式

只合并相邻且材质、纹理、裁剪矩形相同的命令。不能为了减少 draw call 对命令排序，因为 2D 调用顺序就是 z 序。

#### 策略模式

- SeriesRenderer：图表系列渲染；
- ChartPainter：标题、图例和交互装饰绘制；
- ChartValueFormatter：tooltip 数值格式化；
- GlyphSource：字形来源；
- GLAbstraction：OpenGL 调用抽象。

#### Adapter 模式

FXGLTransfer 把 JavaFX 事件坐标和线程模型适配到 GL 线程，并把异步拾取结果切回 JavaFX 线程。

### 1.4 坐标、颜色和线程

- 用户坐标：像素、原点左上、y 轴向下；
- 图表坐标：数据值通过 Axis 映射到绘图区；
- 颜色：0xAARRGGBB；
- 旋转：度数，正值顺时针；
- GL 资源：只能在 GL 线程创建和释放；
- JavaFX 控件：只能在 JavaFX 应用线程操作；
- RingChartData.append：单一采集线程；
- RingChartData 读取和 GPU 上传：GL 线程。

### 1.5 帧生命周期

```text
beginFrame
  -> 应用 onFrame，记录图元
  -> endFrame，提交颜色 pass 和 ID pass
  -> onRender，处理帧末拾取
  -> 下一帧
```

FXGLTransfer 自动管理 beginFrame/endFrame。业务回调不应手动调用它们。

回调（`onFrame` / `onRender` / `onDispose`）里抛出的异常会被捕获，并通过 `abortFrame`
清理当前帧状态，避免"抛一次就此后每帧都抛、画布永久冻结"。异常本身交给
`FXGLTransfer.onError` 处理；**不设处理器时打到 stderr**，不会静默。

## 2. 几何图形模块

### 2.1 分层

```text
geom/          纯计算：Path、曲线、三角化、描边
renderer/      顶点：颜色、纹理、拾取 ID、批处理
Gc             门面：状态、变换、图元 API
```

geom 零 GL 依赖。新增几何算法时，先在 jfgl-core 写纯计算测试，再接入 Gc。

### 2.2 绘制状态

```kotlin
gc.fill = 0xFFFF0000.toInt()
gc.stroke = 0xFFFFFFFF.toInt()
gc.lineWidth = 2f
gc.globalAlpha = 0.8f
gc.antialias = true
gc.fontSize = 16f
gc.pickId = id
```

状态由 save/restore 管理：

```kotlin
gc.save()
gc.translate(100f, 50f)
gc.rotate(15f)
gc.fillRect(0f, 0f, 100f, 40f)
gc.restore()
```

### 2.3 图元 API

```kotlin
gc.fillRect(x, y, width, height, radius = 0f)
gc.strokeRect(x, y, width, height, radius = 0f)
gc.fillCircle(cx, cy, radius, segments = 0)
gc.strokeCircle(cx, cy, radius, segments = 0)
gc.fillEllipse(cx, cy, rx, ry, segments = 0)
gc.strokeEllipse(cx, cy, rx, ry, segments = 0)
gc.drawLine(x1, y1, x2, y2)
gc.fillPolygon(floatArrayOf(x1, y1, x2, y2, x3, y3))
gc.strokePolyline(points, closed = false)
```

segments = 0 表示按半径和变换自动选择细分数量。

### 2.3b 虚线

虚线是**状态字段**（与 `lineWidth` / `antialias` 并列，进 `save`/`restore` 栈），
不是每个形状各一个方法：

```kotlin
gc.dashPattern = floatArrayOf(6f, 4f)   // 偶数下标实线、奇数下标空白；null = 实线
gc.dashPhase = 0f                       // 起始相位（流动虚线就逐帧加它）

gc.strokeCircle(400f, 300f, 120f)       // 所有描边方法都吃这两个字段
gc.strokePath()
gc.dashPattern = null                   // 回到实线
```

- **一个字段管全部**：六个描边方法（`strokePath` / `strokePolyline` / `strokeRect` /
  `strokeCircle` / `strokeEllipse` / `drawLine`）都汇进同一条描边路径。
- 模式长度任意（`[6,4]`、`[8,3,2,3]`…），单位与 `lineWidth` 同为用户坐标。
- **setter 拷贝数组**：不拷的话，改自己那个数组会静默改掉已压进状态栈的历史值。
- 空数组、`NaN`/`Infinity`/负数项、总和为 0 的模式都会抛 `IllegalArgumentException`。

### 2.4 Path API

```kotlin
gc.beginPath()
gc.moveTo(40f, 80f)
gc.lineTo(120f, 80f)
gc.quadraticCurveTo(160f, 20f, 220f, 80f)
gc.bezierCurveTo(260f, 120f, 320f, 20f, 380f, 80f)
gc.closePath()
gc.fillPath()
gc.strokePath()
```

### 2.4b 路径命中判定

画完一条路径之后，可以直接问"这一点在不在它里面 / 在不在它的描边上"：

```kotlin
gc.beginPath(); gc.moveTo(…); gc.lineTo(…); gc.fillPath()

gc.isPointInPath(x, y)      // 在填充区域里？
gc.isPointInStroke(x, y)    // 在描边上？
```

- **`(x, y)` 是设备像素**，路径按当前变换走——**Canvas 语义**（点不受变换影响、路径受）。
  与 GPU 拾取同口径。
- 针对**当前路径**：不需要 `pickId`、不走 GPU、**任意线程**可调。
  `fillPath` 之后路径仍在（`beginPath` 才清空，与 Canvas 一致）。
- `isPointInPath` 用**奇偶规则**，与 JFGL 的填充（按包含关系定洞）在良构路径上恒等。
- `isPointInStroke` **与画面同源**：复用同一个描边生成器与同一组参数，
  所以含 miter 尖角、含超限回退 bevel，当前 `dashPattern` 也被遵守。
  ⚠️ 唯一不含的是 **AA 的 1 设备像素外扩**——开着 AA 时它比拾取窄约 1 像素。

> ⚠️ **它与 GPU 拾取是两条路，会给出不同答案**（不是缺陷）：
> 拾取要注册过的对象、必须 GL 线程、**受 `clipRect` 影响**、像素精确；
> 本判定问当前路径、任意线程、**不看裁剪**、解析判定。图形边缘那一个像素上会分家。
>
> **怎么选**：要"用户点中了哪个注册对象"用拾取；要"当前这条路径包不包含这一点"
> （拖拽预览自命中、把路径当区域用、在非 GL 线程里做几何判断）用本判定。

处理链：

```text
Path -> Flattener -> Tessellator / StrokeGenerator -> VertexWriter
```

### 2.5 裁剪

`clipRect` 与已有裁剪**求交**，并且和其他绘制状态一样进 `save` / `restore` 栈：

```kotlin
gc.save()
gc.clipRect(plotX, plotY, plotWidth, plotHeight)
gc.fillCircle(200f, 150f, 8f)      // 超出的部分不显示
gc.restore()
```

裁剪只支持**轴对齐矩形**（底层是 `glScissor`）。变换含**旋转**时，实际生效的是旋转后
矩形的轴对齐包围盒——这个降级是**单向**的（只会裁少，不会吞掉本该显示的内容）。
要精确裁剪旋转区域，请自己算出想要的轴对齐范围，或者在旋转之前就把裁剪设好。

### 2.6 拾取与点击事件

拾取回答的是"这个像素点上是谁"。判定用的是 **GPU 实际光栅化的结果**，所以与所见一致：
被裁掉的部分不可拾取，被挡住的也不可拾取。

#### 三步闭环

```text
① 注册    gc.pickRegistry.register(payload)      数据变化时一次，不是每帧
② 打标    gc.pickable(id) { ... }                绘制这个对象的时候
③ 接事件  bridge.clickAsyncAtNode(node, x, y) { hit -> ... }
```

**① 给进去的 `payload` 就是你想要的回调结果**：命中时它会原样带回来，
你不需要再维护一张 `id → 对象` 的表。

#### 完整例子：点中图形，控制台输出「图形已点击」

**DSL 版**——最短，按 README 快速开始入门的用这个：

```kotlin
jfgl {
    title = "点一下"
    width = 800.0
    height = 600.0

    var circleId = 0

    onInit { gc ->
        // 注册发生在数据变化时，不是每帧：注册表持有 payload 的强引用，
        // 每帧重注册会耗尽 ID 空间（PickRegistry 会抛异常，不会静默）。
        circleId = gc.pickRegistry.register("红色圆")
    }
    onRender {
        fill = 0xFFFF0000.toInt()
        pickable(circleId) { fillCircle(400f, 300f, 120f) }
    }
    onClick { hit ->
        // 在 JavaFX 应用线程上回调；未命中时 hit 为 null
        if (hit == null) println("点空了") else println("图形已点击：${hit.payload()}")
    }
}
```

整个应用只有 `onInit` / `onRender` / `onClick` 三块，没有一处手工搭 `Scene` / `Stage`，
也没有一处手写坐标换算。

**`FXGLTransfer` 版**——自己搭窗口时用（坐标换算的细节看得更清楚）：

```kotlin
val bridge = FXGLTransfer()
bridge.onInit { gc -> circleId = gc.pickRegistry.register(myCircle) }
bridge.onFrame { gc -> gc.pickable(circleId) { myCircle.draw(gc) } }

val canvas = bridge.createGlFXView()
canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { e ->
    // 原始逻辑坐标交给它，换算在内部完成
    bridge.clickAsyncAtNode(canvas, e.x, e.y) { hit -> onHit(hit) }
}
```

**Java 写法**——同一个闭环，形状上多两处噪音：

```java
// 拾取号存**字段**（不是局部变量）：lambda 里赋值要求它是字段或 final。
private int circleId;

// ① 注册
bridge.onInit(() -> {
    circleId = bridge.gc().getPickRegistry().register(circle);
    return Unit.INSTANCE;
});

// ② 打标：pickable 在 Java 侧是 Function0<Unit>，所以 lambda 要返回值
gc.pickable(circleId, () -> {
    drawOneCircle(gc, circle);
    return Unit.INSTANCE;
});

// ③ 接事件
bridge.clickAsyncAtNode(canvas, e.getX(), e.getY(), hit -> {
    onHit(hit);
    return Unit.INSTANCE;
});
```

> **Java 侧的通用形状**：Kotlin 的 `() -> Unit` 与 `(T) -> Unit` 在 Java 里分别是
> `Function0<Unit>` 与 `Function1<T, Unit>`，**返回值不能省**——写成 `void` 会编译失败
> （报错是 `void 无法转换为 kotlin.Unit`）。`onInit` / `onFrame` / `onError` / `onClick`
> 全都适用。可运行的完整 Java 示例见 `jfgl-javafx/src/test/java/com/bingbaihanji/gl/Main.java`。

#### 回调拿到的 PickHit

```kotlin
hit?.id()        // 拾取号（就是你注册时拿到的那个）
hit?.payload()   // 注册时给进去的对象本身
hit?.x()         // 查询点 x，**设备像素**（可直接拿去显示或做坐标换算的对照）
hit?.y()         // 查询点 y，**设备像素**
```

#### 两条交付语义不能混

| 入口 | 语义 | 用于 |
|------|------|------|
| `pickAsync` / `pickAsyncAtNode` | **最新覆盖旧的** | hover、拖拽这类**连续量** |
| `clickAsync` / `clickAsyncAtNode` / `onClick` | **有界 FIFO、按序交付** | **点击** |

把点击接在 `pickAsync` 上会**静默丢点击**：实测「点一下、几微秒后移动鼠标」
（真实用户点完往往就会动一下）时，8 次真实点击 **0 次**交付——回调不执行、
界面毫无反应、没有任何错误。点击队列容量 16，满时丢最旧并计入 `droppedClicks()`。

#### 坐标换算

鼠标事件给的是节点的**逻辑**局部坐标，而 `Gc` 要的是**设备像素**，两者差一个
**窗口输出缩放系数**（本机 125% 下是 1.25）。

- `pickAsyncAtNode` / `clickAsyncAtNode` / `onClick` **替你乘好了**——不要自己再乘一遍；
- 自己调 `pickAsync` 就**必须自己乘**：`val s = bridge.deviceScale(node)`。
  漏乘的表现是"点 A 命中 B"，**而画面完全正常**——在 100% 缩放的机器上还一切正常。
- 两个轴都用 `deviceScale`（它取 `window.outputScaleY`），**这是刻意的**：
  `GLCanvas` 的 `dpi` 也只取这个值，拿 `outputScaleX` 去换算 x 在非等比缩放下反而是错的。

#### 线程

回调在 **JavaFX 应用线程**上执行，所以可以安全地改控件（`Label.setText` 之类）。
但**不要碰 `Gc`**——它只能在 GL 线程用。要"点中之后改画面"，把结果存进字段
（跨线程可见性用 `@Volatile` / `AtomicReference`），在 `onRender` 里读。
普通字段在这种跨线程读写下的可见性没有保证，表现是"点了没反应，偶尔又有反应"。

#### 明确写出来的边界行为

- **ID 0 = 不参与拾取**，也是"什么都没命中"的返回值。注册表发的号从 **1** 开始。
- **拾取只由几何决定，与颜色和透明度无关**：`globalAlpha = 0` 的图元照样能命中。
  图表的隐形热区、文本比墨迹大一圈的可拾取范围，靠的都是这个行为，**是刻意的**。
- **裁剪生效**：被 `clipRect` 裁掉的部分不可拾取，与画面一致。
- **只返回最上层**：重叠时后画的赢。要"全部重叠对象"需要逐对象多趟渲染，不在范围内。
- **对象不再用了要 `unregister`**：注册表持有 payload 的强引用，不注销就一直留着。
- **同步 `pick` / `pickRect` 只在 GL 线程用**：在 JavaFX 线程里调它们是跨线程 GL 调用，
  崩得毫无规律。也**不能在 `onFrame` 里调**——那时帧还没提交，`pickRect` 恒返回空列表，
  而且不报错。区域拾取的正确位置是帧末（`FXGLTransfer.onRender`）。

## 3. 文字模块

### 3.1 设计

```text
FontFile / stb
  -> GlyphRasterizer
  -> SdfGenerator
  -> GlyphAtlas（R8）
  -> SDF shader
```

同一字形只光栅化并上传一次。字号变化主要由顶点缩放和 SDF 片段着色器处理。

### 3.2 绘制接口

```kotlin
gc.fontSize = 24f
gc.fill = 0xFFF1F5F9.toInt()

val advance = gc.drawText("销售额 2026", 100f, 80f)
val width = gc.measureText("销售额 2026")
```

drawText(x, y) 的 y 是基线，不是文本框顶部。文字同样支持状态栈、变换、裁剪、z 序和拾取。

```kotlin
gc.pickable(labelId) {
    gc.drawText("可点击标签", 120f, 90f)
}
```

默认字体位于 jfgl-render-gl/src/main/resources/fonts。生产项目必须确认字体授权；多字体 fallback 和复杂文本 shaping 应扩展 GlyphSource，不要把字体选择逻辑塞进 Gc.drawText。

## 4. 统计图表模块

### 4.1 分层

```text
chart/
  ChartData、Axis、Tick、Chart、Layer、Series、ChartLayout、ChartInteraction

chartrender/
  ChartRenderer、ChartRenderLayout、SeriesRenderer、SeriesBuffer、ChartPainter
```

chart 是纯计算层；chartrender 是 OpenGL 后端。系列渲染器只负责如何绘制系列，不负责鼠标状态。

### 4.2 数据

静态数据：

```java
ArrayChartData data = new ArrayChartData(
    new AxisRange[] {
        new AxisRange(0, 100, "时间", "s"),
        new AxisRange(0, 50, "数量", "件")
    },
    new double[][] { xValues, yValues }
);
```

流式数据：

```java
RingChartData data = new RingChartData(
    new AxisRange[] { AxisRange.of(0, 1), AxisRange.of(0, 100) },
    1 << 16
);
data.append(timestamp, value);
```

RingChartData 是单生产者、单消费者模型。多生产者必须在业务侧先汇聚。

### 4.3 轴和系列

```java
Axis x = new Axis(AxisType.LINEAR, data.axisRange(0))
        .setDisplayLength(plotWidth)
        .setWindow(0, 100);

Axis y = new Axis(AxisType.LINEAR, data.axisRange(1))
        .setDisplayLength(plotHeight)
        .setWindow(0, 50);

Chart chart = new Chart(x, y)
        .title("订单统计")
        .legendVisible(true)
        .axisTitlesVisible(true);

chart.addLayer("业务数据")
        .add(new Series("订单数", data, ChartType.LINE)
                .color(0xFF4FC3F7)
                .lineWidth(2f));
```

Layer 和 Series 的添加顺序就是绘制顺序。不要依赖渲染器排序。

### 4.4 绘制入口

高层入口：

```kotlin
gc.charts.drawChart(
    chart,
    Rect(40f, 40f, gc.width - 80f, gc.height - 80f),
    gc.width,
    gc.height
)
```

低层入口：

```kotlin
gc.flush()
gc.charts.draw(chart, plotRect, gc.width, gc.height)
// 最后绘制刻度文字和业务标注
```

推荐 z 序：

```text
背景 -> 网格 -> 数据系列 -> crosshair/tooltip -> 刻度和标注
```

> **★ 2026-09-28 起，上面那套 z 序里的「网格」与「刻度」可以整段交给库。**
> 打开坐标系后，`drawChart` 自己按「装饰 → 网格 → 轴线/箭头/刻度线 → 数据 →
> 刻度文字 → crosshair/tooltip」的顺序画完，**不需要中间插 `flush()`**
> （刻度文字在绘图区之外，而数据被裁在绘图区之内，盖不到它）。
> 低层入口 `draw(chart, plotRect, …)` 拿的是**已经算好的绘图区**，
> 排不下轴与刻度文字，所以坐标系**只在 `drawChart` 那条路上生效**。

#### 坐标系（`AxisStyle`）

```kotlin
chart.axisStyle(AxisStyle.defaults()
        .visible(true)                    // ★ 必须显式打开：默认是关的
        .gridVisible(true).gridColor(0xFF394452).gridWidth(1f)
        .axisColor(0xFFE3E8EF).axisWidth(1.5f)
        .arrowsVisible(true).arrowSize(8f)
        .tickMarksVisible(true).tickLength(5f)
        .tickLabelsVisible(true).tickLabelFontSize(12f).tickLabelColor(0xFFC7D0DB))

gc.charts.drawChart(chart, frame, gc.width, gc.height)   // 一步画完：装饰 + 坐标系 + 数据
```

打开之后：

- **刻度预留由库自己算**，`chart.tickLabelReserve(...)` 会被**覆盖**（不是相加）。
  要更多空间请给整张图加 `padding`；
- **网格线只画严格在绘图区内部的主刻度**——两端点上的与坐标轴本身重合，
  再画一遍只是把轴线加粗一档。注意这**不是**"跳过值为 0 的刻度"：
  y 窗口跨过 0 时，中间那条零线是要画的；
- 刻度文字取 `Tick.label()`，所以**业务上的格式化仍然在应用侧配 `Axis`**；
- **默认关**：开了轴绘图区就要让出带子，而"绘图区变了就是画面变了"，
  既有图不该被一个新开关悄悄挪几像素。

### 4.5 图表类型

当前后端支持：

- LINE
- SCATTER
- STEP
- AREA
- BAR
- SPECTRUM

HEATMAP、WATERFALL 等顶点来源不同的图型应实现独立 SeriesRenderer，不要强行复用折线渲染器。

### 4.6 Hover、十字线和 tooltip

交互状态属于 Chart，输入和渲染分层：

```kotlin
chart.interaction().setConfig(
    ChartInteractionConfig.defaults()
        .snapRadius(24f)
        .crosshairVisible(true)
        .tooltipVisible(true)
        .formatter { value, _ -> "%.2f".format(value) }
)

bridge.trackChartHover(canvasNode, chart)
```

绘制时调用 drawChart 即可。系统会自动：

- 吸附到最近数据点；
- 忽略 NaN 缺口；
- 绘制横向和纵向虚线；
- 绘制命中点；
- 显示 x/y 轴名称、单位和数据值；
- 显示系列名称；
- 防止 tooltip 越出绘图区。

配置项包括 enabled、crosshairVisible、tooltipVisible、snapRadius、crosshairColor、crosshairWidth、dashLength、tooltipBackground、tooltipBorder、tooltipText、tooltipFontSize、tooltipPadding、tooltipOffset 和 formatter。

后续加入缩放、框选、多系列比较时，应扩展 ChartInteraction，不要把交互逻辑写进具体系列渲染器。

### 4.7 新增图表类型

1. 在 ChartType 增加类型；
2. 增加或复用 Series 样式字段；
3. 实现 SeriesRenderer；
4. 在 ChartRenderer 注册分派；
5. 同时提供颜色 pass 和拾取 pass；
6. 设计 SeriesBuffer 布局和 dirty range 上传；
7. 增加纯计算测试和真实 GL 像素校验。

## 5. 扩展与发布规范

### 5.1 推荐依赖的 API

业务代码优先依赖：

- Gc
- FXGLTransfer
- PickHit（拾取回调的入参，见 §2.6）
- Chart
- Series
- Axis
- ChartData
- ChartInteractionConfig
- ArrayChartData
- RingChartData

以下类属于后端实现，业务层不应直接依赖：

- RenderBatch
- VertexWriter
- GLAbstraction
- ShaderProgram
- Framebuffer
- SeriesBuffer
- PickBuffer

### 5.2 测试分层

```text
jfgl-core 纯计算测试
jfgl-render-gl FakeGLAbstraction 测试
真实 GPU 像素校验器
真实 JavaFX 事件闭环
```

改动顶点、几何或描边后运行 PipelineVerifier；改动文字运行 TextVerifier；改动图表运行 ChartVerifier；改动拾取和 PBO 运行 PickVerifier；改动点击事件运行 ClickVerifier。

### 5.3 资源释放

GL 资源只能在 GL 线程释放。JavaFX Application.stop() 负责触发 FXGLTransfer.dispose()。自定义 GL 资源必须实现 Disposable，并满足：

- dispose() 幂等；
- 释放后继续使用明确抛异常；
- 构造失败时回收已创建资源；
- 所有权和释放顺序明确。

## 6. 最小完整示例

```kotlin
fun main() {
    jfgl {
        title = "JFGL Chart"

        var chart: Chart? = null

        onInit { _ ->
            // 构造 data、Axis、Chart 和 Series
            // chart = ...
        }

        onRender {
            chart?.let {
                charts.drawChart(
                    it,
                    Rect(40f, 40f, width - 80f, height - 80f),
                    width,
                    height
                )
            }
        }
    }
}
```

JavaFX 原生入口中，把节点传给 FXGLTransfer.trackChartHover(node, chart)。DSL 如果后续需要提供 chart hover 语法糖，也应继续复用同一个 ChartInteraction。

