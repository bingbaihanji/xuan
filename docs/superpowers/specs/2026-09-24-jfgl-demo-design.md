# JFGL 交互式 Demo 设计（菜单栏 + 鼠标绘制 + 文本 + 统计图表）

> 日期：2026-09-24
> 状态：已确认
> 范围：一个**应用层示例**（`example/demo/`），外加**一处库的可见性改动**（`FXGLTransfer.deviceScale`）。
> 不新增渲染能力，不改任何几何 / 渲染算法。

---

## 1. 背景与目标

JFGL 已有三个模块（`jfgl-core` 纯计算 → `jfgl-render-gl` GL 后端 → `jfgl-javafx` 场景图桥接）、
七个像素校验器、370 条单测。但**没有一个是"像应用那样用起来"的示例**：

- `PipelineExample.kt` 是启动演示，静态画面；
- `ClickExample.kt` / `ClickDslExample.kt` 只验证点击闭环，形状是写死的常量；
- `example/` 里七个 `*Verifier.kt` 是断言工具，不是示例。

本设计要的是一支**能当"用户视角验收"用的 demo**：一个窗口、一条菜单栏、三种模式，
用鼠标真的去画、去点、去框选，并且画出一张统计图表。

**目标**

1. 用鼠标**拖拽绘制**六种几何图形（矩形 / 圆 / 椭圆 / 直线 / 多边形 / 贝塞尔曲线），
   每一种都走框架专为它准备的那条几何路径；
2. **文本绘制**：点击落字，并且把「`y` 是基线不是左上角」这条约定**画出来**；
3. **统计图表**：合成数据 + 手写网格与刻度 + `drawChart` 的装饰，四种图型可切；
4. **拾取闭环**：点击选中、右键框选、`Delete` 删除——把 `pickRegistry` / `pickable` /
   `pickAsync` / `pickRect` 四条路径都真实走一遍；
5. **诚实暴露缺口**：demo 是"用户视角"，凡是必须绕过公开 API 才能做到的事，
   都是文档该记下来的缺陷。见第 6 节。

**非目标（本期不做）**

- 不改任何几何算法、着色器、顶点格式、批处理规则；
- 不给 `jfgl { }` DSL 加拖拽 / hover 入口（见 §6.1，本期只记录）；
- 不给 `Gc` 加 `pickRectAsync`（见 §6.2，本期只记录）；
- 不做图形编辑（拖动已画对象、改尺寸、撤销重做、保存 / 加载）；
- 不做图层、属性面板、颜色拾取器（颜色只给预设色板）；
- 不动 `Main.kt`（它硬编码 `PipelineExample`）。

---

## 2. 为什么是"应用层示例"而不是"补框架缺口"

本 demo 的定位由一次明确取舍决定：**只补 demo 真正缺的那一处**（§5），其余缺口写进文档（§6）。

理由是仓库自己的文化：`CLAUDE.md` 里大量条目是「**已声明的降级，不是缺陷**」
（旋转裁剪退化为包围盒、拾取不看 alpha、文本热区比墨迹大一圈……）。
把 demo 暴露的每一个不适都"顺手修好"，等于在没有需求的情况下扩公开 API 面——
而每一个新公开方法都是一条此后要维护的承诺。

---

## 3. 图形模型

### 3.1 三个候选与取舍

| 方案 | 做法 | 取舍 |
|---|---|---|
| **A（选用）** | 每种图形一个 `sealed class` 分支，各自保存**构造参数**；`draw(gc)` 按类型调 `gc.fillRect` / `fillCircle` / `fillEllipse` / `drawLine` / `fillPolygon` / `beginPath+quadTo+strokePath` | **每种图形都走框架专为它准备的那条路**：圆走 `circleSegments` 的自适应细分、多边形走 `Tessellator` 耳切 + `strokePolyline(closed)`、贝塞尔走 `Path` 的曲线平坦化。demo 因此覆盖到 `geom/` 最厚的三块代码 |
| B | 所有图形统一先拍成 `FloatArray` 顶点序列，渲染只有一条 `beginPath/fillPath` | 少一个 `when`；但**丢掉自适应细分与曲线平坦化**——圆被预烘焙成固定点数，放大后有棱角；`geom/Flattener` 与 `circleSegments` 都不再被 demo 覆盖 |
| C | 同 A，但坐标一律存**设备像素**，`draw()` 额外回写包围盒供选中高亮 | A 的全部优点；把 `ClickExample` 里那个 `box()` 侧信道**显式化**，而不是散在各处 |

**选 A**（把 C 的包围盒回写并进实现）。判据：B 会让 demo 绕过框架最厚的几何代码，
而"验收框架"正是本 demo 的目的。

### 3.2 数据模型

```kotlin
/** 一种图形的**定义**（不是它的顶点）。坐标一律是设备像素。 */
sealed interface Shape {
    val color: Int
    val style: ShapeStyle          // FILL / STROKE / FILL_AND_STROKE
    val lineWidth: Float
    /** 画自己。GL 线程调用。 */
    fun draw(gc: Gc)
    /** 自己的轴对齐包围盒（设备像素）。拾取高亮与状态栏用。 */
    fun bounds(): Rect

    data class Rect2(val x: Float, val y: Float, val w: Float, val h: Float, ...) : Shape
    data class Circle(val cx: Float, val cy: Float, val r: Float, ...) : Shape
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, ...) : Shape
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, ...) : Shape
    data class Polygon(val points: FloatArray, ...) : Shape       // 轨迹抽稀后的闭合多边形
    data class Bezier(val points: FloatArray, ...) : Shape        // 轨迹抽稀后的平滑曲线
}
```

**只有"两点定义"的四种图形在拖拽过程中有预览态**（`dragStart` + `dragCurrent` 合成一个临时 `Shape`）；
多边形与贝塞尔在拖拽过程中把轨迹点收进一个 `FloatArray`，松开时才抽稀成型。

### 3.3 轨迹 → 图形（多边形 / 贝塞尔）

两条都先做**最小距离抽稀**：遍历按下到松开的每个鼠标位置，与上一个**已收下**的点距离
≥ `SIMPLIFY_MIN_DIST`（默认 8 设备像素）才收。这既是去抖，也把点数压到可控范围。

- **多边形**：抽稀后直接 `gc.fillPolygon(pts)` + `gc.strokePolyline(pts, closed = true)`。
  点数少于 3 时丢弃（不产生退化图形）。
- **贝塞尔**：抽稀后走经典的「中点二次曲线平滑」——
  ```
  moveTo(P0)
  for (i in 1 until n-1) quadTo(Pi, mid(Pi, Pi+1))
  lineTo(Pn-1)
  ```
  然后 `strokePath()`。这条路径同时覆盖 `Path.quadTo` 与 `Flattener` 的二次贝塞尔细分。

**抽稀阈值是 demo 的参数，不是框架的参数**——写在 `DemoShapes.kt` 的伴生常量里。

---

## 4. 交互设计

### 4.1 菜单栏

用 JavaFX `MenuBar` 放进 `MainView.menu`（`MainView` 已经有这个槽位，`ClickExample` 没用它）。

| 菜单 | 项 | 说明 |
|---|---|---|
| 文件 | 清空画布 / 删除选中 / 退出 | 清空 = 全部 `unregister` 后清列表 |
| 模式 | 绘图 / 文本 / 图表 | `RadioMenuItem` + `ToggleGroup`，互斥 |
| 图形 | 矩形 / 圆 / 椭圆 / 直线 / 多边形 / 贝塞尔 | `RadioMenuItem`，**仅在绘图模式可用**（`disableProperty` 绑定） |
| 样式 | 填充 / 描边 / 填充+描边；线宽 1/2/4/8 | 决定新画图形的 `ShapeStyle` |
| 颜色 | 八色预设色板 | 决定新画图形的颜色 |
| 字号 | 14 / 22 / 32 / 48 | **仅在文本模式可用** |
| 图型 | 折线 / 柱状 / 面积 / 散点 | **仅在图表模式可用**；对应 `ChartType.LINE/BAR/AREA/SCATTER` |

状态栏（`MainView.bottom` 的 `Label`）显示：当前模式、当前图形、已画对象数、选中数、
最近一次拾取结果（含「局部坐标 → 设备像素」的换算过程，与 `ClickExample` 同一个教学目的）。

### 4.2 绘图模式的手势

| 手势 | 行为 |
|---|---|
| 左键**拖拽**（位移 ≥ 4 设备像素） | 画一个新图形，松开时提交 |
| 左键**单击**（位移 < 4 设备像素） | 走 `clickAsyncAtNode` 拾取并**选中**命中的图形；未命中则清空选中 |
| 右键**拖拽** | **框选**：画半透明选框，松开时选中框内全部图形 |
| `Delete` / `Backspace` | 删除选中的全部图形（并 `unregister`） |

「单击」与「拖拽」用同一个 4 像素阈值区分，**不引入模式开关**——
用户不需要先想"我现在是选择还是绘制"。

### 4.3 文本模式

点击画布 → 在该点落一段文字。字号由菜单决定。

**文本内容按落字序号从一张固定的样本表里轮换**（4 条：一条纯 ASCII、一条纯中文、
一条中英混排、一条长句用于看推进宽度），**不用随机、不用用户输入**——
随机或输入会让"这一帧画对了没有"无法复核，而本项目的验证文化要求画面可复现。

**★ 落字时在笔位画一个小十字（`+`），并把它作为图形的一部分永久保留。**
`Gc.drawText(text, x, y)` 的 `y` 是**基线**，`CLAUDE.md` 点名这是「最容易猜错、
且猜错后"看起来只是位置偏了一点"」的一条。把它画出来，猜错就不可能隐形。

### 4.4 图表模式

12 个月的「销售额 / 成本」两条合成序列。菜单切换图型时**重建 `Chart` 里的 `Series`**
（`ChartData`、轴、颜色、标题都不变，只换 `ChartType`），于是四种图型画的是**同一份数据**——
这样"换图型"这个动作的差异只来自渲染器，人眼一看就知道对不对。

> 两种图型天然要求不同的装配，切换时必须一起换：`BAR` 用 `baseline(0)` 与
> `categoryGap/barGap`（默认值够用）；`AREA` 用 `baseline` + `fillAlpha`（默认 0.5）。
> 只有 `LINE` / `SCATTER` 是纯粹的 `ChartType` 替换。
> `Series` 的样式 setter 全部拒绝 `NaN` / `±Infinity`（抛 `IllegalArgumentException`），
> 所以这些值写常量而不是算出来。

数据是**演示用的合成数据**，写在 `DemoChart.kt` 的伴生常量里（一条斜坡 + 一条带扰动的斜坡，
扰动是**写死的数组**而不是 `Random`）——随机数据会让"这一帧画对了没有"无法复核。

---

## 5. 库的改动：只有一处

### `FXGLTransfer.nodeScale` → `deviceScale`（private → public）

**现状**：`FXGLTransfer.kt:465` 的 `private fun nodeScale(node: Node): Double` 是
「节点局部坐标 → 设备像素」的**唯一**换算点，被 `pickAsyncAtNode` / `clickAsyncAtNode` 使用。

**为什么 demo 需要它**：鼠标拖拽要给 `Gc` 喂设备像素坐标，而 `Gc` 的坐标系是设备像素
（用户坐标 1:1 映射到设备像素）。这一乘正是 `Gc` / `FXGLTransfer` 文档反复点名的那个
「**漏乘的表现是"点 A 命中 B"，而画面完全正常**」——在 100% 缩放的机器上还一切正常。

**改法**：

```kotlin
/**
 * 节点局部坐标 → 设备像素的换算系数。
 *
 * <p>（以下文档整段照抄现 `nodeScale` 的说明，含"两轴都用 outputScaleY，这是刻意的"。）
 */
fun deviceScale(node: Node): Double = node.scene?.window?.outputScaleY ?: 1.0
```

两个既有调用点（`pickAsyncAtNode` / `clickAsyncAtNode`）跟着改名。**其余一行不动。**

**为什么是"公开一个系数"而不是"加一套拖拽回调"**：后者要为 demo 发明一个新的事件类型
（压在哪儿？和 `onClick` 什么关系？和 `overlay` 的 z 序什么关系？），
而 demo 需要的只是那一个数。加回调属于 §2 明确排除的"顺手扩 API 面"。
**代价**：demo 里仍然需要自己写 `(e.x * scale).toFloat()`（一行），
这条在 §6.1 记为缺口。

---

## 6. 记录进文档、本次不补的缺口

全部写进 `CLAUDE.md` 的「未实现 / 待办」一节。

### 6.1 `jfgl { }` 只暴露 `overlay`，不暴露画布节点本身

**先说清楚这条缺口到底有多大——它比看上去小。** `JFGLApplication.start` 里是：

```kotlin
val mainView = MainView().apply { center = StackPane(view, config.overlay) }
stage.scene = Scene(mainView.createMainView(), config.width, config.height)
```

`createMainView()` 返回的就是那个 `BorderPane`，而它被直接当成 `Scene` 的根节点——
**所以菜单栏是加得进去的**：

```kotlin
jfgl { onScene { (it.root as BorderPane).top = MenuBar(...) } }   // 能跑，只是要向下转型
```

真正的缺口在**画布节点拿不到**：`onScene` 给的是 `Scene`，而画布是
`StackPane(view, overlay)` 的第一个孩子，只能从 `overlay.parent` → `children[0]` 掏出来。
拖拽 / hover / 滚轮**都必须接在那个节点上**，于是"想在 DSL 里做拖拽"就得挖场景图。

DSL 另外只有 `onClick` 一个鼠标入口，没有按下 / 拖拽 / 抬起。

**结论**：这不是"装不下菜单栏"的硬墙，而是**没有一等入口**——
`overlay`（叠在画布之上那层）是显式给出的，画布自己反而要挖。
本 demo 因此绕过 DSL 直接用 `FXGLTransfer` + 手写 `Stage` / `Scene` / `MainView`，
作为这条绕法的完整样板。**记录，不补**：补法是给 `JFGL` 加画布节点属性或一组拖拽回调，
那是扩 API 面（§2）。

### 6.2 没有异步 `pickRect`

框选需要 `Gc.pickRect`，它是**同步**的 `glReadPixels`（`w×h×4` 字节），
只能在 GL 线程调用；而鼠标松开发生在 JavaFX 线程。库里没有任何异步版本
（`pickAsync` / `clickAsync` 都是**单像素**）。

**demo 的绕法**（这是正确写法，但每个应用都要自己发现一次）：JavaFX 线程把
「待框选的矩形」写进一个 `@Volatile` 字段，**`onRender` 回调（GL 线程）**里读走并调
`gc.pickRect`，结果经 `Platform.runLater` 送回 JavaFX 线程更新选中集。

> **★ 是 `onRender`，不是 `onFrame`。** 这份文档早先在这里写过一版 `onFrame` 并把它
> 称作"正确写法"——**那是错的，而且会主动误导**：同下面那十行就是"`onFrame` 里调
> `pickRect` 恒返回空"的说明。**照前半段实现，得到的正是这一节存在的唯一理由所要防的
> 那个缺陷**（框选永远选不中、且不报错）。终审把这对矛盾挑出来了。

**★ 必须一并写清楚的一条（我原来在这里写错了一版，记下来）**：`onFrame` 在
`FXGLTransfer` 的 `addOnRenderEvent` 里**跑在 `context.endFrame()` 之前**，
而 ID pass 是 `endFrame` → `submit` 时渲染的。

我最初的判断是"所以读到的是上一帧的 ID 缓冲，本 demo 场景静态，两者等价"。
**这个判断是错的**——`RenderBatch.beginFrame` 会**刻意**把 `pickBufferValid` 置为 `false`
（注释原文：不让上层"拿陈旧的 ID 去注册表里查——那会拾取到早已消失的对象，而画面完全正常"），
而它只在 `withPickPass` 里被置回 `true`。**库不是给你陈旧数据，而是明确拒绝给。**
所以 `onFrame` 里调 `pickRect` / `pick` 会**恒返回空**，框选会永远选不中任何东西、且不报错。

**正确位置是 `FXGLTransfer.onRender`**：它跑在 `endFrame()` **之后**、`pickBufferValid`
为 `true`，读回的是**本帧**的拾取结果。本 demo 因此把框选读回挂在那里
（它没有 `gc` 参数，但同一线程上 `bridge.gc()` 拿得到）。
单像素拾取不受影响——`pickAsync` / `clickAsync` 本来就由 `resolvePendingPick` 在
`endFrame` 之后处理。

**★ 顺带查出一条命名陷阱**（比本节的缺口本身更值得记）：

```
JFGL.onRender          ──转发到──►  bridge.onFrame  ──►  FXGLTransfer:151   在 endFrame() 之前
FXGLTransfer.onRender                                 ──►  FXGLTransfer:157   在 endFrame() 之后
```

**`JFGL.onRender` 实际等价于 `FXGLTransfer.onFrame`，而不是 `FXGLTransfer.onRender`**——
同名、不同时机（`JFGL.kt:271` 是 `bridge.onFrame { gc -> config.invokeRender(gc) }`）。

后果：一个想"帧末、手里有 `Gc`、干点什么"的 DSL 用户会按名字去找 `onRender`，
拿到的却是 `endFrame` **之前**那个——于是**区域拾取在 DSL 路径上恒返回空**（原因见上），
而"框选选不中"看起来是业务逻辑的问题，不像时机问题。

**走 DSL 的应用因此做不了区域拾取**：单像素有 `pickAsync` / `clickAsync` 兜着，区域没有。
（这也说明 `JFGL` 那个同名方法该改名，或者干脆转发到真正的帧末回调。记录，不在本期补。）

### 6.3 `Gc` 没有公开 `ChartTextMetrics`

想自己算布局（= 在正确的绘图区里画网格与刻度），必须自己实现 `ChartTextMetrics`：

```kotlin
override fun width(text: String, fontSize: Float): Float   // 临时改 gc.fontSize 再 measureText
override fun lineHeight(fontSize: Float): Float = fontSize * ChartLayout.LINE_HEIGHT_FACTOR
```

`Gc` 内部有一支口径完全相同的笔（`Gc.kt:163` 的 `private val chartPainter`），
但它是 **private**，`Gc` 本身不实现 `ChartTextMetrics`。

**不补的理由**：补它要让 `Gc` 实现一个公开接口，属于扩 API 面（§2）。
**风险照实记录**：重写歪了（比如把 `lineHeight` 写成字体的真实行高），
网格与标题带就对不上，而画面只是"看着有点挤"——与 `ChartLayout` 那段
「读字体的真实 ascent 会让逐像素期望值没地方写」是同一个陷阱。

### 6.4 没有"可拾取图元列表"这层抽象

demo 要自己维护：`ArrayList<Shape>` + 每个 `Shape` 的可变 `pickId` +
`pickRegistry.register/unregister` 的配对 + 选中集的跨线程可见性。
`ClickExample.kt:210` 手搓的 `ClickItem`（可变 `pickId` + `box()` 回写热区的侧信道）
是同一个模式。**每个应用都要重写一遍**，而写错的后果（忘了 `unregister` → 强引用泄漏；
忘了 `pickable` 包裹 → 拾取不到）都是静默的。

### 6.6 ★ `ChartRenderer` 没有"这个系列不再画了"的回收接口

`ChartRenderer` 用 `IdentityHashMap` 按 **`Series` 对象身份**缓存每个系列的 GPU 缓冲与拾取号：

```java
ctx.setCurrentBuffer(buffers.computeIfAbsent(series, s -> new SeriesBuffer(gl, s.data())));
ctx.setPickId(pickIds.computeIfAbsent(series, pickRegistry::register));
```

而这两个 map **只在 `ChartRenderer.dispose()` 里清空**（`ChartRenderer.java:512-513`），
中间没有任何回收路径——`dispose()` 的文档也只说"释放本类创建的全部 GL 资源"。

**后果（本 demo 差点踩上去）**：任何"每帧重建 `Chart`"的写法——而那是最自然的写法，
因为 `Chart` 看起来是个纯计算对象——都会**每帧泄漏一块 `SeriesBuffer`（GPU 显存）**
并**每帧消耗两个拾取号**。拾取号耗尽时 `PickRegistry` 会抛异常，**但 GL 线程上的异常在
本项目是静默吞掉的**，所以症状是"前几百帧完全正常，然后图表忽然不画了，没有任何报错"。

**demo 的处置**：`DemoChart` 按图型**缓存** `Chart`（`cachedChart` / `cachedKind`），
只有菜单切图型时才重建。**代价照实记录**：每切换一次图型仍多留两个 `Series` 的缓冲与号
（菜单四项 ⇒ 上限 8 个），这是**有界**的。

**该补的是库**：`ChartRenderer` 需要一个"本帧只保留这些系列"的入口
（形如 `retainSeries(Collection<Series>)` 或 `releaseSeries(Series)`），
在 `draw` 结束时回收不再出现的系列。属于扩 API 面，本期只记录。

### 6.5 其他（架构评审里展开）

`Gc` 1436 行 / 至少 6 项职责；`GPUFFT.java` 零代码引用的死代码；
`Gc.charts` 的释放要 `FXGLTransfer` 反向调 `gc.disposeCharts()`；
`Main.kt` 硬编码 `PipelineExample`；七个校验器住在 `src/main` 而无 CI。
详见 `2026-09-24-jfgl-architecture-review.md`。

---

## 7. 图表那一帧的确切顺序

```kotlin
// ① 纯计算：自己算一次布局（为了把网格画进正确的绘图区）
val layout = ChartLayout.compute(chart, frame, demoTextMetrics)
val plot = layout.plotRect()                    // util.Rect，字段 x/y/width/height（不是方法！）

// ①b ★ 顺序约束：displayLength 必须**等布局算完**才能设，且每帧都要重设
//     —— 绘图区尺寸依赖 frame（= gc.width/height），窗口一缩放它就变，
//     而 Axis.dataToDisplay / ticks() 的取值区间由 displayLength 决定。
//     设晚了（或只在装配时设一次）的症状：刻度与数据点沿轴错开，
//     而画面只是"网格线没对准数据"——看起来像数据本身的问题。
xAxis.setDisplayLength(plot.width.toDouble())
yAxis.setDisplayLength(plot.height.toDouble())

// ② 绘图区底色
gc.fill = BG; gc.fillRect(plot.x, plot.y, plot.width, plot.height)

// ③ 手写网格循环（库没有辅助，全仓唯一一份在 README.md:340-347）
gc.stroke = GRID; gc.lineWidth = 1f
for (t in yAxis.ticks()) {                      // y 轴要翻：值越大越靠上
    val sy = plot.y + plot.height - t.position().toFloat()
    gc.drawLine(plot.x, sy, plot.x + plot.width, sy)
}
for (t in xAxis.ticks()) {
    val sx = plot.x + t.position().toFloat()
    gc.drawLine(sx, plot.y, sx, plot.y + plot.height)
}

// ④ ★ 网格落定。数据系列是当场就画的，不 flush 就没有"网格 → 数据 → 标注"的夹心 z 序
gc.flush()

// ⑤ 装饰（标题 / 图例 / 轴标题）+ 数据系列
gc.charts.drawChart(chart, frame, gc.width, gc.height)

// ⑥ 刻度文字画在数据之上。只有主刻度的 label 非空，中/次是空串。
gc.fill = LABEL; gc.fontSize = 12f
for (t in xAxis.ticks()) {
    if (t.isMajor()) gc.drawText(t.label(), plot.x + t.position().toFloat(), plot.y + plot.height + 16f)
}
```

**要点与出处**

- `ChartLayout.compute` 在 ⑤ 里被 `drawChart` **又算了一遍**——纯函数，输入相同则结果相同，
  多算一次不是 bug（`ChartRenderer.java:271`）。
- `Axis.setDisplayLength(...)` 必须**等于**绘图区的宽 / 高，否则刻度与数据点错开
  （`ChartVerifier.kt:1589-1590` 的契约注释）。
- `gc.drawText(x, y)` 的 `y` 是基线；**文字颜色取 `gc.fill`，线段颜色取 `gc.stroke`**。
- `drawChart` **不能带着 `translate/scale/rotate`** 调用，`Gc` 会抛 `IllegalStateException`
  （`Gc.kt:170-176`）。demo 全程不带变换。
- `gc.charts.drawChart` 至少要**两根轴**（0 号数据下标、1 号数值）。
- 图型切换用 `ChartType.LINE / BAR / AREA / SCATTER`——四个渲染器都是现成的；
  `HEATMAP` / `WATERFALL` 会抛异常，不放进菜单。

---

## 8. 文件划分

`example/` 目前是**扁平**的（每个示例一个文件）。本 demo 不沿用：

```
jfgl-javafx/src/main/kotlin/com/bingbaihanji/jfgl/example/demo/
  JfglDemo.kt       Application + 菜单栏 + 状态栏 + 鼠标接线（JavaFX 线程的那一半）
  DemoShapes.kt     sealed Shape + 轨迹→图形 + 每帧绘制（GL 线程的那一半）
  DemoChart.kt      图表模式的装配与每帧绘制（含手写网格 / 刻度）
```

**理由**：估算 700~900 行，塞进一个文件就是"一个类干了太多事"。
拆成三块的边界是**线程**——`JfglDemo.kt` 全部在 JavaFX 线程，
`DemoShapes.kt` / `DemoChart.kt` 的绘制部分全部在 GL 线程，
而两个线程之间只通过 `@Volatile` / `AtomicReference` 交换状态。
这条边界正好是 `CLAUDE.md` 的「线程模型」一节在讲的那条，也是 demo 最该示范对的地方。

**入口**：demo 自己的顶层函数加 `@JvmName("main")`（同包已有顶层 `main()`，
同名会报重载歧义——这是 `ClickExample.kt:420` 已经踩过并写明的坑）。
**不改 `Main.kt`**，跑法：

```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.demo.JfglDemoKt"
```

---

## 9. 线程模型（demo 自己必须守住的那条线）

| 状态 | 写者 | 读者 | 手段 |
|---|---|---|---|
| `shapes: List<Shape>` | JavaFX 线程（落字 / 提交图形 / 删除）**＋ 自检模式下 GL 线程的钩子**（见下） | GL 线程（每帧遍历） | `AtomicReference<不可变 List>`（整表替换，不做原地改） |
| `selection: Set<Int>`（pickId） | JavaFX 线程（点击回调）、GL 线程（框选结果）**＋ 自检模式下 GL 线程的钩子**（见下） | GL 线程（画高亮） | `AtomicReference<Set<Int>>` |
| 拖拽预览（起止点） | JavaFX 线程 | GL 线程 | `@Volatile` 的 float **字段**（float 读写是原子的） |
| 拖拽轨迹点集 | JavaFX 线程 | GL 线程 | `@Volatile var trajectory: FloatArray`，**每次追加都新建数组**（见下） |
| 待框选矩形 | JavaFX 线程 | GL 线程（**`onRender`**） | `@Volatile` 的 float 字段（同上） |
| 当前模式 / 图形 / 颜色 / 字号 | JavaFX 线程（菜单） | GL 线程 | `@Volatile` |

**★ 前两行的写者如实有两处，不是一处**（终审补准的）：自检第 ⑦ 条要在
**GL 线程**上删图形（"读回落地之前按 Delete"，时序是那条判据的全部意义），
它调的是生产代码 `removeShapes`，而后者做的是**读-改-写**
（`shapes.set(shapes.get().filter { … })`）——与 JavaFX 线程那侧交错会丢一次更新。
**这不是生产缺陷**：GL 线程那一侧被 `if (SELFTEST && selfTestDeleteAfterReadback)` 挡着，
生产路径走不到；而真丢了更新会让第 ⑦ 条的判据响亮失败，不是静默错画。
**不改结构**（改成 `Platform.runLater` 会把那条时序判据从确定性变成竞态），只把写者写准。

**轨迹为什么每次新建数组而不是原地追加**：`@Volatile` 只保证**引用**的可见性，
不保证**数组内容**的可见性。原地 `append` 时 GL 线程可能读到"长度已经改了、
元素还没写完"的半截数组——表现是画出来的曲线末尾少几个点或有杂点，
**而它只在拖拽时偶发**，停了就不复现。抽稀在 JavaFX 线程做（每来一个 drag 事件做一次），
产出一个**新数组**再赋给那个 `@Volatile` 字段，就没有这个窗口。
轨迹点数上限几百，重分配的代价可忽略。

**`pickRegistry` 是唯一例外**：它自己线程安全，所以在 JavaFX 线程随数据变化直接
`register` / `unregister`（`Gc.kt:104-108` 明确说这是刻意的例外，不该把它也塞进 `onFrame`）。

**不在 `onFrame` / `onInit` 里碰任何 JavaFX 控件**；状态栏更新一律在 JavaFX 线程的回调里做。

---

## 10. 验证

| 项 | 命令 | 期望 |
|---|---|---|
| 编译三个模块 | `mvn -o compile` | 成功 |
| 全量单测 | `mvn -o test` | **370 通过 / 0 失败 / 2 跳过**（本次只改一个方法的可见性，不该动任何断言） |
| 点击校验器 | `exec:exec ... ClickVerifierKt` | 退出码 0（**全绿 76 条**）。**必须重跑**：`deviceScale` 改名动的是 `pickAsyncAtNode` / `clickAsyncAtNode` 的坐标换算路径，正是它那对 ★ 探针守的东西（"同一局部坐标，换算后点中 P、不换算点中 Q"） |
| 图表校验器 | `exec:exec ... ChartVerifierKt` | 退出码 0（未动图表代码，属回归确认） |
| **★ demo 自检模式** | `exec:exec ... -Djfgl.demo.selftest=1 ... demo.JfglDemoKt` | **十一条合成事件断言全过 + `[自检-合成] 全部通过` + 退出码 0**。它**自己退出**，不会开窗不走。跑法见 `CLAUDE.md` 那条 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`（**两个都要**） |
| 手工跑 demo | `exec:exec ... demo.JfglDemoKt` | 见下 |

> **★ 自检模式是 demo 唯一的行为级验证**，它用 `node.fireEvent(MouseEvent(...))` 合成的
> 鼠标事件驱动完整交互闭环（画图形 → 点击选中 → 重叠取后画的 → 只描边不命中 → 框选 →
> Delete → 文本落字 → 图表模式 → 遍历四种图型）。
> **它不该替代下面这份手工清单**——它验的是"接线通不通、状态对不对"，
> 验不了"画出来的形状好不好看、字号换了会不会挤"。
> **反过来也成立**：手工清单验不了下面那三条判别式（它们的时序人手抓不住）。

### 手工验收清单（每一项都要真的动手，不看代码推断）

**绘图**

1. 六种图形各拖一个：矩形 / 圆 / 椭圆 / 直线 / 多边形 / 贝塞尔。
   - **多边形**拖成凹形状 → 凹处**真的凹**（验耳切三角化，不是凸包）
   - **贝塞尔**拖成 S 形 → 曲线**平滑**（验 `quadTo` + `Flattener`，不是折线）
   - **圆内切于**拖拽框（与椭圆同一条尺规）；**纯水平或纯竖直**地拖会得到"拖得太短"的提示
     （面状图形要求两轴都不为零——与多边形拒绝退化的口径一致）
2. 左键单击已画图形 → 状态栏显示"命中：…"且高亮框出现；点空白 → 选中清空。
   - **只描边**的图形：点它的**内部**（离边框 8px 以上）**不**命中——拾取只看几何
   - 两个图形**重叠**处点击 → 命中**后画的**那个（z 序）
3. 右键拖出选框 → 框内图形同时高亮；`Delete` 删掉它们，状态栏计数跟着减；
   **删完再点原位置 → 不命中**。

**文本**

4. 文本模式点三处，字号依次 14 / 22 / 32/ 48 → 三个十字**正好落在文字左端的基线上**。
   - **判据**：十字的**横线贴着文字的基线**。横线穿过文字中部 ⇒ `y` 被当成了中心；
     横线在文字上方 ⇒ 被当成了左上角。
   - 中文**不是豆腐块**（`simhei.ttf` 有这些字）。
   - 切回绘图模式再点 → 落下的是**图形**不是文字（模式没串）。

**图表**

5. 四种图型（折线 / 柱状 / 面积 / 散点）各切一次。
   - 标题在顶部（**左对齐**——库的行为，`ChartDecorations` 用带子左边缘；
     **只有轴标题居中**）、图例在底部、轴标题「金额 (万元)」在左侧
   - x 轴刻度数字与**竖网格线对齐**、y 轴刻度与横网格线对齐
   - 柱状的两端那两根（1 月 / 12 月）**各被裁掉一半**——这是已知的（x 窗口取 `[0, 11]`，
     柱心落在绘图区边界上）。**它不是"柱宽不一致"**，两端的柱宽是对的。
6. **缩放窗口** → 图表跟着重排（外框每帧按 `gc.width`/`gc.height` 重算），
   而**已画的图形不动**（它们存的是设备像素坐标，是用户放的内容、不是布局的一部分）。
   代价：窗口**缩小时**画到外面的图形被裁掉，但**不会被删除**（缩回去又出现）。

### ★ 三条只有人工能抓的判别式（合成事件已覆盖前两条，但人工各跑一次值得）

7. **LIFO 复用那条**：框住一个图形 → **立刻**按 `Delete` → 再画一个新图形
   → 确认**新图形没有被高亮框住**。
   （`PickRegistry` 注销后把号 `push` 进空闲表、`register` 优先 `pop`——**LIFO 复用**。
   少了那道过滤，新图形会拿到被删的号、**立刻被高亮**，再按 Delete 删掉的是**它**。）
8. **按住左键切模式那条**：绘图模式按下并拖动 → **按住不放**、用键盘（Alt/F10）切到文本模式
   → 松开 → **在松开处落下一段文字**。
   （这是 Task 8 那个更窄写法**唯一可见的行为变化**：`modeItem` 的 `resetDragState()`
   把 `moved` 归零，于是走 `commitText`。**已知且接受**，记在这里是为了别把它当 bug 去改。）
9. **拖矮窗口那条**：把窗口拖到很矮（`gc.height` 落到 160~194 之间）→
   **图表不画，但应用不冻屏**（拖回去照画）。
   （`ChartLayout` 会把绘图区钳成 0，而 `Axis.setDisplayLength(0.0)` **会抛**；
   而 `FXGLTransfer` 的帧回调**没有 `try/finally`** ⇒ 抛一次就**永久冻屏且不报错**。
   守卫必须按**算完的布局**判，不能按常量——阈值由实测文字宽决定。）

**已知行为（不是 bug）**：文本**不参与拾取**（`pickId = 0`）⇒ 它只能靠「清空画布」移除，
而清空会连所有图形一起清掉。框架本身支持文本拾取（`Gc` 文档说文本"裁剪、z 序、合批、
GPU 拾取全部自动成立"），本期不做是取舍。

**手工验收清单**（每一项都要真的动手，不看代码推断）：

1. 绘图模式：六种图形各拖一个；多边形拖成凹形状（验耳切）；贝塞尔拖成 S 形（验平坦化）；
2. 左键单击已画图形 → 状态栏显示命中项且高亮出现；点空白 → 选中清空；
3. 右键拖出选框 → 框内多个图形同时高亮；`Delete` 删掉它们，状态栏计数跟着减；
4. 文本模式：点三处落字，三个十字**正好落在文字左端基线上**（不是文字的上边缘）；
5. 图表模式：四种图型各切一次，网格 / 刻度 / 标题 / 图例都在正确位置；
6. 缩放窗口 → **图表跟着重排**（它的外框每帧按 `gc.width` / `gc.height` 重算，
   刻度与网格一起走），而**已画的图形不动**。

   后一条是刻意的：[Shape] 存的是**设备像素**坐标，它是用户放的内容，不是布局的一部分
   ——就像画布上画过的东西不会因为窗口变大而自己挪位置。代价是窗口**缩小时**画在外面的
   图形会被裁掉（`glScissor` 与视口都变了），而它们**不会被删除**（缩回去又出现）。
   要"跟着重排"就得把坐标存成逻辑像素再每帧换算，那会让"画在哪就是哪"这条
   立即模式的基本手感消失。**这条与图表的行为不同，是设计选择不是不一致。**

**不做的验证**：不给 demo 写像素校验器。demo 的价值在"人能用鼠标真的操作一遍"，
而校验器要求场景可复现、期望值有出处——一个交互式应用的画面取决于用户点了哪儿，
没有可断言的判据。**框架那侧的回归由现有七个校验器守，demo 不替代它们。**

---

## 11. 实施顺序

1. `FXGLTransfer.deviceScale` 公开（§5）→ `mvn -o compile` → 重跑 `ClickVerifier` 确认 76 条全绿；
2. `DemoShapes.kt`：`sealed Shape` + 六种 `draw` + 轨迹抽稀；
3. `JfglDemo.kt`：窗口 + 菜单栏 + 状态栏 + 鼠标接线（先只做绘图模式）；
4. 拾取闭环：选中高亮 → 框选 → 删除；
5. 文本模式（含基线十字）；
6. `DemoChart.kt`：数据 + 轴 + 图表 + 网格 / 刻度 / `drawChart`；
7. 手工验收清单走一遍（§10）；
8. 把 §6 的缺口写进 `CLAUDE.md`。
