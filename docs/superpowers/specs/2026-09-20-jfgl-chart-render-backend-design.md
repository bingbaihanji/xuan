# JFGL 图表子系统设计（子项目 D-② GPU 绘制后端）

**日期**：2026-09-20
**状态**：待评审
**前置**：子项目 A（单一批处理管线）、C（GPU 拾取）、B（SDF 文本）、D-①（图表类框架）均已完成

---

## 0. 这份规格覆盖三块中的第二块

| | 内容 | 判据 | 规格 |
|---|---|---|---|
| **① 图表类框架** | 数据容器、轴与刻度、脏区间、颜色 LUT、图表装配 | 能否脱离 GL 上下文单测 | 已完成（另见 `2026-09-20-jfgl-chart-framework-design.md`） |
| **② GPU 绘制后端** | 把 ① 的产物变成 GL 顶点与 draw call | 需要 GL 上下文 | **本文档** |
| **③ GPU 计算** | FFT、降采样、包络、密度累积（数字荧光） | 需要 GL 上下文 | 另出 |

① 已完成、已验收：全量 261 通过 / 0 失败 / 2 跳过，`chart/` 下 16 个类，`ChartPackageIsolationTest` 机械地守着它的边界。

---

## 1. 目标

让 `chart/` 算出来的东西**真正出现在屏幕上**，并且满足立项时的性能要求：**底层直接用 GPU 绘制，而不是像 JavaFX Canvas 那样逐像素 CPU 光栅化。**

**第一优先级是实时路径**（示波器：数据持续高速涌入、窗口每帧滑动、要求实时显示）。

**第二优先级是静态科学绘图**（几十万点的曲线、热力图），同架构支持但排在后面。

## 2. 非目标（本规格明确不做）

| 不做 | 理由 |
|------|------|
| GPU 计算（FFT / 降采样 / 密度累积） | 那是 ③。② 只负责"把已有的点画出来" |
| 误差棒、等高线、眼图 | `ChartType` 目前没有覆盖，属于 ③ 或更后面 |
| 3D | 2D 管线的顶点格式没有 z |
| 富文本标注、多行刻度标签 | 子项目 B 的文本只有单行 |
| 图例、动画、交互手势（缩放/平移的**输入**处理） | 应用层的事。② 提供"窗口能滚动"的**能力**，不提供滚轮事件 |
| 改 `chart/` 任何一行 | **这是硬约束**，见 §3.1 |

## 3. 前置事实（已实测，不要重新猜）

### 3.1 ② 的代码必须放在 `chart/` 外面——这是机器强制的

`ChartPackageIsolationTest` **递归遍历** `src/main/java/com/bingbaihanji/jfgl/chart` 下的全部 `.java`，用正则扫出每一行里形如 `com.bingbaihanji.jfgl.<包名>` 的引用，只放行 `chart` / `math` / `util`。

因此：

- **`chart/gl/` 这种子包的方案是死的**——它落在递归范围内，一旦 `import com.bingbaihanji.jfgl.gl.GLAbstraction` 就会让守卫失败。
- ② 的包名选 **`com.bingbaihanji.jfgl.chartrender`**（`chart` 的**兄弟目录**，不在递归范围内）。
- **`chart/` 一行都不改**。若发现非改不可，说明 ① 的分界线画错了，应当停下来重新讨论。

> 这条守卫不是障碍，是**设计的一部分**：它保证 ① 永远能脱离 GL 上下文单测。

### 3.2 渲染层的现状（全部读代码核实，2026-09-20）

| 事实 | 出处 | 对 ② 的含义 |
|---|---|---|
| `Gc` 的 `writer()` / `syncState()` / `currentMatrix` / `packColor()` 是 Kotlin `internal`，`Gc` 构造器也是 `internal`，`emitTriangles` 是 **private** | `Gc.kt:966,986,969,1006,52,937` | Java 侧能调 `internal`（编译后是 `writer$jfgl()`），但那是**绕过封装**，不作为 ② 的路径 |
| `beginFrame` → `writer.reset()` 丢弃全部顶点；`endFrame` → `batch.submit(writer)` | `Gc.kt:142-166` | **每帧全量重传**，这是 ② 存在的理由 |
| `VertexBuffer.upload` 走 `glBufferData`（整块重传），扩容时**删旧建新** | `VertexBuffer.java:77-108` | 增量上传需要新的 GL 入口（§5.2） |
| **`glBufferSubData` 在 `gl/` 与 `renderer/` 里零命中** | grep 实测 | 只有死代码 `gpu/GPUFFT.java` 里有裸调用 |
| `GLAbstraction` 有 31 个方法，**没有 SSBO、没有 glBufferSubData、没有 instancing** | `GLAbstraction.java` | §5.2 要补三个 |
| `ShaderProgram` 有 `setUniform(String,float)` / `(String,float,float)` / `(String,float[])`(mat3) / `(String,int)`；**没有 vec3/vec4/mat4**；每次 `setUniform` 都现做一次 `glGetUniformLocation` 字符串查找 | `ShaderProgram.java:98-145` | §5.3 要补 vec4 并缓存 location |
| `RenderBatch.drawPickPass` **遍历命令表、用当前绑定的 VAO/VBO 画**，自己不绑定任何 VAO/VBO | `RenderBatch.java:498-525` | **这是好消息**：图表自己的 VAO 走同一个 ID pass 只要"换绑定"，见 §6 |
| `pickBufferCleared` / `pickBufferValid` 是 `private`，复位点**唯一**是 `beginFrame` | `RenderBatch.java:193-197` | §6 要把这套脚手架安全地开放出去，且**不能破坏**「每帧恰好一次 beginFrame」的前提 |
| `pickPassCount` **跨帧累计、从不复位**，是校验器断言"跳过优化生效"的计数器 | `RenderBatch.java:199-200,64-67` | **图表路径绝不能碰它**，否则 `PickVerifier` 的断言会被污染（§6.3） |
| `VertexWriter.MAX_VERTEX_CAPACITY = 1 << 20` | `VertexWriter.java:42` | 折线走 `Gc` 的上限约 11.65 万点，超过**抛异常** |
| `VertexFormat` 是 24 字节：pos(0) + uv(8) + color(16) + id(20) | `VertexFormat.java:26-38` | 图表数据用**另一套顶点布局**，见 §5.1 |
| `ChartType.polylineFamily()` = `connectsSamples() \|\| drawsMarkers() \|\| drawsBars()`，**HEATMAP 与 WATERFALL 的 flags = 0** | `ChartType.java:98-100` | 热力图与瀑布图**必须由独立渲染器实现**，不能靠属性组合 |
| `SeriesRenderer.render(RenderContext, ChartData, Series, Axis[])`，`@FunctionalInterface` | `SeriesRenderer.java:43` | ② 的接缝，签名已冻结 |
| `RenderContext` 必须保持**空接口**（0 方法 / 0 字段 / 0 嵌套类型） | `ChartPackageIsolationTest:78-94` | ② 定义子接口，**不在原地加成员** |

### 3.3 GL 上下文是 4.6 compatibility

`GL_VERSION = 4.6.0 NVIDIA 581.29`，`GL_CONTEXT_PROFILE_MASK = 2`（**compatibility**，core 是 1），compute shader 端到端可用。`#version 330 core` 的着色器能跑是**向后兼容**，不是版本证据。

**本规格用到的最高版本是 GL 4.2**（`glDrawArraysInstancedBaseInstance`，理由见 §5.1），实测上下文是 4.6，可用。**SSBO 留给 ③**——理由见 §5.1。

---

## 4. 架构

### 4.1 核心判断：数据在 GPU 里存「数值」，不存「屏幕坐标」

这是整个 ② 最重要的一个取舍，**其余设计都是它的推论**。

| | 存屏幕坐标（照现有管线的做法） | **存原始数值（本规格的选择）** |
|---|---|---|
| 窗口滚动 | 所有点的 x 都变了 → 整块重传 | 改一个 uniform → **零重传** |
| 缩放 / 自动量程 | 所有点的 y 都变了 → 整块重传 | 改一个 uniform → **零重传** |
| 窗口尺寸变化 | 重传 | 改一个 uniform → **零重传** |
| 代价 | —— | 几何必须在着色器里生成，CPU 侧生成不了 |

**为什么这是决定性的**：示波器的窗口**每帧都在滑动**。如果滑动要重传，"GPU 常驻"就白做了，做出来的东西与现在走 `Gc` 没有区别。**这条判断就是 ② 存在的理由本身。**

**推论**：既然几何在着色器里生成，那么 CPU 侧生成顶点的那套（`StrokeGenerator` 的 miter 接头、`Tessellator`）**在这条路径上一律用不上**——不是浪费，是两条路服务两类东西（见 §4.3）。

### 4.2 核心判断：等间隔采样只存 y，不存 x

示波器数据是等间隔采样的，第 n 个点的 x 就是 n。**x 不需要存**，着色器从实例序号自己算。

于是 **x 一个字节都不用存**，数据侧每个采样点只占 **4 字节**（一个 float 的 y 值）。

| | 现在走 `Gc` | ② 的新路 |
|---|---|---|
| 10 万点 | 90 万顶点 / **21.6 MB，每帧重传** | **0.4 MB，永不重传** |
| 100 万点 | **直接抛异常**（上限 11.65 万点） | **4 MB，永不重传** |
| 每帧 CPU→GPU | 整个窗口 | **新增的那几个点**（每点 4 字节） |

> **怎么做到 4 字节而不是 8**：一个线段要拿到两端，直觉上得成对存（8 字节/段）。
> 但可以用**同一个缓冲、两个不同的字节偏移**建两个实例属性——`aY0` 读偏移 0，`aY1` 读偏移 4。
> 于是实例 k 拿到的就是 `(y[k], y[k+1])`，而缓冲里每个点**只存一次**。
>
> **这是本设计里最省事的一处**：既拿到了 4 字节/点，又不需要 SSBO。
>
> 缓冲要比环容量**多留一个 float 的余量**：最后一个实例的 `aY1` 会指到界外。虽然那个实例永远不画，但别让 GPU 有机会去读越界地址。

> **不等间隔的数据怎么办**：`ArrayChartData` 的 x 可以是任意值（散点图、静态科学图）。本期对这类数据**存成对的值**（x 与 y 各 4 字节，8 字节/点），走同一条着色器路径，只是 x 从属性来而不是从序号来。用一个 uniform 开关切换。**示波器路径不受影响。**

### 4.3 核心判断：轴、网格、刻度文字继续走 `Gc`

它们数据量小、不常变，现成的东西已经够好——而且**文本的 SDF 渲染、GPU 拾取、合批全是白送的**。为它们另起一条路是纯浪费。

于是最终画面由两类东西拼成：

```
网格（Gc） → 数据系列（② 新路） → 刻度与标注文字（Gc）
```

### 4.4 这带来一个必须先解决的小问题：帧内 z 序

`Gc` 的东西是**帧末一次性提交**的，而数据系列是**当场就画**的。如果不管，数据系列只能整个画在网格之上或之下，"夹在中间"做不到。

解法：给 `Gc` 加一个**公开的提交点**（`flush()`）。这个能力**内部早就有**（`flushIfNeeded` 在缓冲区写满时走的就是它，`RenderBatch` 也明确支持一帧内多次 `submit`），只是没开放出来。

```
gc.beginFrame(w, h)
   画网格
gc.flush()               ← 把网格提交掉
charts.draw(chart, plotRect)   ← 数据系列，当场画
   画刻度文字
gc.endFrame()
```

z 序因此**完全可控**，而不是碰运气。

---

## 5. 组件

### 5.1 数据缓冲：instancing，不引入 SSBO

**几何生成方式**：每个线段一个实例；顶点侧是一个共享的单位四边形（4 个顶点，divisor = 0），两端的 y 值按 divisor = 1 供给——用 §4.2 的双偏移技巧，**同一个缓冲、两个偏移**：

```java
// 同一个 VBO，绑两次，只差 4 个字节的偏移
glVertexAttribPointer(0, 1, GL_FLOAT, false, 4, 0);  // aY0 -> y[k]
glVertexAttribDivisor(0, 1);
glVertexAttribPointer(1, 1, GL_FLOAT, false, 4, 4);  // aY1 -> y[k+1]
glVertexAttribDivisor(1, 1);
```

顶点着色器把"数值 → 屏幕 → NDC"整个算出来，并沿屏幕空间法线把四边形撑成有粗细的线段。

```glsl
// 概念示意，不是最终代码
float y0 = aY0;                      // 本段左端点的数值
float y1 = aY1;                      // 本段右端点的数值
float i  = float(gl_InstanceID) + uFirstIndex;   // 数据下标
float sx0 = uPlotX + (i      - uWindowStart) * uPxPerSample;
float sx1 = uPlotX + (i + 1.0 - uWindowStart) * uPxPerSample;
vec2 p0 = vec2(sx0, valueToScreenY(y0));
vec2 p1 = vec2(sx1, valueToScreenY(y1));
vec2 dir = normalize(p1 - p0);
vec2 nrm = vec2(-dir.y, dir.x) * uHalfWidth;
vec2 p = (aCorner.x < 0.5 ? p0 : p1) + (aCorner.y < 0.5 ? -nrm : nrm);
gl_Position = toNdc(p);
```

**为什么是 instancing 而不是 SSBO**：

| | instancing（本规格） | SSBO + 顶点拉取 |
|---|---|---|
| 每点内存 | **4 字节/点**（§4.2 的双偏移技巧） | 4 字节/点 |
| 新增 GL 入口 | `glVertexAttribDivisor` + `glDrawArraysInstancedBaseInstance` + `glBufferSubData`（**3 个**） | 创建/绑定/上传/子上传/绑定基址/删除 SSBO（**6 个以上**）+ 一套 GLSL buffer 封装 |
| 所需 GL 版本 | **4.2**（`...BaseInstance`）——实测上下文是 4.6，可用 | 4.3+ |
| 接头质量 | 每个实例独立，接头是平接（butt join） | 可以做 miter 接头 |
| 是否 ③ 的前提 | 否 | **是**（FFT 的 gather/scatter 非它不可） |

**结论**：② 用 instancing，**SSBO 留给 ③**。理由是"用最小的新机制拿到架构上的全部好处"——滚动免费、几何在 GPU 生成、CPU 每帧只上传新增点，这三条 instancing 全都给到了。接头质量是唯一的代价，而示波器的线宽通常在 1–2 px，平接的缺口是亚像素的。

> **接头质量的诚实话**：`lineWidth` 大于约 3 px 时，转弯处的平接缺口会开始可见。**这是已知限制，不是 bug。** 修法是给每个顶点额外画一个小四边形做接头（`Gc.strokePolyline` 在 CPU 侧用的就是这一招），留给后续任务；或者等 ③ 引入 SSBO 之后改成顶点拉取 + miter。**不要在没有实测到可见缺口之前去优化它。**

**环形结构**：GPU 缓冲镜像 `RingChartData` 的环。新点写在 `writeIndex & (cap-1)`，**一次 4 字节的子里上传**。可见窗口若跨过环绕点，**发两段 draw**，不做双倍宽镜像——双倍宽是瀑布图为了纹理滚动无缝才需要的（见 scope 项目的做法），这里两段 draw 更省内存。

> **为什么必须用 `...BaseInstance` 而不是普通的 `glDrawArraysInstanced`**：
> 实例属性是按 `gl_InstanceID` 取的，而 `gl_InstanceID` **每次都从 0 开始**。
> 普通版本没有任何办法让属性从"环绕点之后那一小段的物理槽位"开始取——
> 于是第一段（物理槽位 `[p, cap)`）会取到**错误的实例数据**，而且不报错，只会画出一条乱线。
> `glDrawArraysInstancedBaseInstance` 的 `baseInstance` 正好补上这个偏移。

### 5.2 `GLAbstraction` 要补的三个方法

```java
/** 把数据写到 VBO 的指定字节偏移处，不重新分配。 */
void uploadVboSubData(int offsetBytes, ByteBuffer data);

/** 设置某个顶点属性的实例除数（0 = 每顶点，1 = 每实例）。 */
void setVertexAttribDivisor(int index, int divisor);

/** 实例化绘制，并指定实例属性的起始实例号（见 §5.1 的说明）。 */
void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                     int instanceCount, int baseInstance);
```

前两个是 GL 3.3 core、第三个是 GL 4.2 的内建功能，`LwjglGLAbstraction` 里各一行转发。

**`uploadVboSubData` 的前置条件必须写进 Javadoc**：目标 VBO 的容量必须**已经**够大。它不扩容——`VertexBuffer.grow()` 是"删旧建新"，而扩容会**让 VAO 里记录的数据缓冲绑定失效**（`RenderBatch.configureVaoAttributes` 的注释里已经踩过这个坑）。因此图表缓冲的容量在**创建时一次定死**（按环容量算），**运行期永不扩容**。这是一条硬约束，不是优化。

### 5.3 `ShaderProgram` 要补的

```java
/** 设置四分量浮点 uniform（vec4）。 */
void setUniform(String name, float x, float y, float z, float w);
```

**并且缓存 uniform 位置。** 现在每次 `setUniform` 都做一次 `glGetUniformLocation`（按名字做字符串查找）。图表每个系列每帧要设约 8 个 uniform，多个系列叠加，这个字符串查找会变成热路径上的可见开销。加一个 `Map<String,Integer>` 缓存即可。

> **注意**：`ShaderProgram` 目前的 `setUniform(String, float[])` 是 **mat3**（`glUniformMatrix3fv`），不要拿它去传 vec4 数组——名字像但语义完全不同，而且不会有任何报错，只会安静地传错。

### 5.4 新包 `com.bingbaihanji.jfgl.chartrender`

| 类 | 职责 |
|---|---|
| `ChartRenderer` | 入口。持每个 `Series` 的 GPU 缓冲与渲染状态，`draw(Chart, Rect plotRect)` 一次画完所有层与系列 |
| `GLRenderContext` | `extends RenderContext`（**空接口的子接口**，见 §3.2）。承载渲染一个系列时需要的全部东西：GL 抽象、着色器、该系列的 `SeriesBuffer`、绘图区矩形、视口尺寸、本帧的可见窗口区间。`SeriesRenderer` 的实现**第一行向下转型**取它 |
| `SeriesBuffer` | 一个系列的 GPU 常驻缓冲：环形 + 增量上传 + `revision` 跟踪 + **本帧上传字节数计数**（校验器要用，见 §9） |
| `SeriesShaders` | GLSL 源码常量（线 / 散点 / 各自对应的 pick 变体） |
| `LineSeriesRenderer` | 折线族：`LINE` / `LINE_AND_MARKERS` |
| `ScatterSeriesRenderer` | 散点：`SCATTER`。标记点用 instancing 画四边形 |

**`ChartRenderer.draw` 的流程**：

```
对每个 Layer（按添加顺序，顺序即 z 序）
  对每个 Series
    1. 方向转型 ctx 为 GLRenderContext（SeriesRenderer 的第一行）
    2. 按 dirtyRange 增量上传新点（只传新增的那几十个）
    3. 算可见窗口 [windowStart, windowEnd)（O(1) 算术，不遍历数据）
    4. 设置 uniform，发 1～2 次 glDrawArraysInstanced
    5. 若 pickId != 0，走 ID pass（§6）
```

**CPU 每帧的工作量是固定的**：算一个整数区间 + 上传新增点。**与数据总量无关。**

### 5.5 用户 API

```kotlin
onRender {
    // 网格
    stroke = 0xFF303030.toInt()
    drawLine(...)
    flush()                       // ← 新增：提交网格，保证它在数据系列之下

    charts.draw(chart, Rect(60f, 20f, 800f, 500f))   // ← 数据系列

    // 刻度文字
    fill = 0xFFFFFFFF.toInt()
    fontSize = 12f
    for (t in x.ticks()) if (t.label().isNotEmpty()) drawText(t.label(), t.position(), 540f)
}
```

- `Gc.flush()`：公开的帧内提交点。契约见 §5.6。
- `Gc.charts`：`ChartRenderer`，**懒创建**（第一次访问时建），与 `RenderBatch` 同生命周期、一起 dispose。

### 5.6 `Gc.flush()` 的契约

**必须写进 Javadoc，并且有测试钉住**：

1. 提交当前已积累的顶点，重置顶点写入器。
2. **之后的下一次绘制会重新建立状态**——调用方不需要、也不应该手动 `setState`。这条靠的是现有的 `flushIfNeeded` 路径（`Gc.kt:914-917`）：`reset()` 之后下一个图元经由 `syncState` 重新 `setState`。
3. 可以在 `beginFrame` 与 `endFrame` 之间的**任意位置**调用任意次。
4. **不得**在 `beginFrame` 之前或 `endFrame` 之后调用（与这两个方法同样的前置条件检查）。
5. **它会让 ID pass 多跑几趟**（`RenderBatch.submit` 在 `hasPickableVertices()` 时每次都跑一趟 ID pass）。这是设计使然：z 序要求分批提交，分批就意味着分趟拾取。`pickPassCount` 的语义应重新表述为「`submit` 中执行 ID pass 的次数」，而不是「每帧一趟」。

**为什么不用「Gc 提供一个 flushIfNeeded 的公开版」**：那个方法的语义是"缓冲区满了才提交"，是**容量驱动**的；而这里要的是"**我说了算**的提交点"，是**顺序驱动**的。两者名称相近但意图完全不同，合并会让两边都说不清。

---

## 6. 拾取

### 6.1 已定的方案：系列级 ID + CPU 定位到点

顶点着色器里**没有** `aId` 属性（图表的数据布局是另一套）。拾取 ID 走 **uniform**：

```glsl
uniform uint uPickId;
flat out uint vId;
...
vId = uPickId;
```

于是：

- **发号成本与点数无关**：一条 100 万点的曲线只注册**一个** ID（`PickRegistry` 压力是 O(系列数)）。
- 拾取告诉你"鼠标压在哪条曲线上"，**"具体是哪个点"由 CPU 在那一系列的数据里找最近的**——用 `Axis.dataToDisplay` 做同一套映射，几万点的一次线性扫描在这个场景下可以忽略。
- `pickId == 0` 的系列**整趟跳过 ID pass**（`0` 是「不参与拾取」，见 CLAUDE.md 的拾取一节）。

### 6.2 拾取容差

线只有 1–2 px 宽，要求用户精确点中是不合理的。ID pass 的顶点着色器加一个 uniform：

```glsl
float halfWidth = max(uHalfWidth, uPickTolerance);
```

**拾取用的线比画出来的宽**（默认 4 px 半宽，即 8 px 带宽）。这是**刻意**行为，与 CLAUDE.md 里已经钉死的两条同类：
- "全透明图元仍可拾取"
- "文本的可拾取范围比墨迹大一圈"

**都要有测试钉着，不要当成 bug 修。** 注意：容差只影响 ID pass，**不影响画面**。

### 6.3 复用 `RenderBatch` 的 ID pass：把脚手架开放出来

`drawPickPass` 已经写成"用**当前绑定的** VAO/VBO 画"，所以图表走同一趟 ID pass 只需要换绑定。但 `pickBufferCleared` / `pickBufferValid` 是 private，且它们的复位点必须**唯一**是 `beginFrame`。

**做法**：在 `RenderBatch` 上加一个**回调式**的公开方法，而不是暴露内部状态：

```java
/**
 * 在拾取缓冲上执行一段绘制。
 * <p>
 * 负责：本帧首次调用时清空缓冲、绑定拾取 FBO、结束后恢复原 FBO 并标记缓冲有效。
 * 调用方负责：绑定自己的 VAO/VBO、配置属性指针、设置 scissor、发出 draw call。
 */
public void withPickPass(Runnable body);
```

**回调式而不是 `begin()/end()` 成对**——成对 API 会有人忘了 `end()`，而忘掉的表现是"下一帧画进了拾取缓冲"（画面正常，只是拾取全错）。回调式让编译器替他记住。

**三条硬约束**：

1. **不得改动 `pickPassCount`。** 它跨帧累计、从不复位，是 `PickVerifier` 用来断言"无拾取对象时整趟跳过"的计数器。图表路径往里加计数会**污染已有的断言**，而症状是"拾取校验器突然失败"，排查方向会指向 ID pass 本身。图表若需要计数，用自己的。
2. **`pickBufferValid` 必须被图表路径也置为 true**，否则上层拾取查询会诚实地返回"没命中"——于是图表永远点不中，而画面完全正常。这正是那种"看起来像没实现"的静默错误。
3. **裁剪必须保持开启**：被 `clipRect` 裁掉的部分不可拾取，与画面一致。

---

## 7. 缺口（NaN）的处理

① 已钉死：`RingChartData.GAP = NaN`，窗口之外与丢包都是 NaN，**渲染器遇到 NaN 必须断开折线**。

**在 instancing 路径上怎么断**：

顶点着色器里，**只要有一个端点是 NaN，就把整个四边形退化成一个点**（四个角算出来是同一个位置 → 面积为零 → GPU 直接丢弃，不产生任何像素）：

```glsl
bool bad = isnan(y0) || isnan(y1);
if (bad) { gl_Position = vec4(2.0, 2.0, 0.0, 1.0); return; }   // NDC 外，必定被裁掉
```

> **为什么不能靠"NaN 自然传播"**：NaN 位置的光栅化行为是**未定义**的——驱动可能丢掉它，也可能产生垃圾像素。而且更糟的是**它不保证丢掉**。必须显式退化。

**并且要有像素断言钉住它**（§9）：数据里插一个 NaN，断言缺口处**没有**被连起来。这是本规格里最容易被写成橡皮图章的一条——断言必须落在**"缺口那一段的像素是背景色"**上，而不是"画出来了东西"。

**代价（诚实说明）**：一个 NaN 会吃掉**两段**（左邻居的段和右邻居的段）。视觉上是缺口比实际宽一个采样间隔。在一屏几千个点的尺度上不可察觉，但**这是真实的行为**，写进 `SeriesBuffer` 的类文档。

---

## 8. `ChartType` 的支持矩阵

| ChartType | `polylineFamily()` | 本期 | 渲染器 |
|---|---|---|---|
| `LINE` | ✔ | **做** | `LineSeriesRenderer` |
| `SCATTER` | ✔ | **做** | `ScatterSeriesRenderer` |
| `LINE_AND_MARKERS` | ✔ | **做** | `LineSeriesRenderer` |
| `STEP` | ✔ | 后续 | `LineSeriesRenderer` 的阶梯模式（着色器的取 x 方式不同） |
| `AREA` | ✔ | 后续 | 折线 + 填充，需要 `Tessellator` 或一条 stencil 路径 |
| `BAR` | ✔ | 后续 | 单独的 `BarSeriesRenderer`（柱的几何与折线不同） |
| `HEATMAP` | ✘ | **另出** | 独立渲染器，往纹理里画，不走数据缓冲这条路 |
| `WATERFALL` | ✘ | **另出** | 同上；滚动用 scope 项目的"双倍宽环形纹理"手法 |

**遇到不支持的 `ChartType` 必须明确报错**（`SeriesRenderer` 的类文档已钉死）。**静默不画**是本项目最典型的静默错误输出：画面里少一条曲线，与"这条曲线没数据"视觉上完全一样。

---

## 9. 测试策略

### 9.1 单元测试（不需要 GL 上下文）

`chartrender/` 里**能脱离 GL 的部分要尽量多**，这是它相对 `chart/` 的唯一让步——`chart/` 是零 GL，`chartrender/` 做不到，但可以把**算术**挤出来：

| 测试类 | 覆盖 |
|---|---|
| `SeriesBufferTest` | 环形写入位置、`revision` 跟踪、**增量字节数**（见 9.3）、容量与数据的环容量不一致时抛异常、`NaN` 端点被标为退化 |
| `WindowRangeTest` | 可见窗口 → 数据索引区间的算术：**不跨环绕**、**跨环绕时给出两段**、空窗口、窗口大于数据量、`windowStart` 为负 |
| `ChartRenderLayoutTest` | 数值 → 屏幕的映射（与 `Axis.dataToDisplay` **必须一致**，这是双实现，容易不一致） |

`SeriesBuffer` 与 `GLAbstraction` 的交互用**已有的 `FakeGLAbstraction`**（在 `src/test/.../gl/`）单测——`gl/Framebuffer`、`renderer/PickBuffer`、`text/GlyphAtlas` 都是这么测的，先例现成。

### 9.2 像素校验器 `ChartVerifier`

新增 `src/main/kotlin/.../example/ChartVerifier.kt`，与三个已有校验器同样的形状（回读帧缓冲、断言、退出码 0/1）。

**断言清单**：

1. **映射正确**：画一条已知的斜坡（y 从 0 到 1），断言若干**具体像素坐标**处的颜色与轴映射算出来的位置一致。
2. **裁剪生效**：`clipRect` 之外没有像素。
3. **`flush()` 的 z 序**：网格画在数据系列之下——断言两者重叠处是系列的颜色，不是网格的颜色。
4. **缺口断开**（§7）：插入 NaN，断言缺口段是背景色。
5. **拾取命中**：点在线上的像素拾取到该系列 ID。
6. **拾取容差生效**：点在线**旁边 3 px**（画出 1 px 线）仍然命中。
7. **拾取不越界**：点在线旁边 **20 px** 不命中。
8. **系列级 ID 正确**：同一条曲线的不同位置拾取到**同一个** ID。
9. **两道曲线不串号**：两条曲线各自的 ID 互不串。
10. **`pickId == 0` 的系列不可拾取**。

### 9.3 最要紧的一条断言：**本帧上传了多少字节**

整个 ② 的性能主张是「**每帧只上传新增的点，不是整个窗口**」。前面 10 条断言**没有一条**能证明这一点——它们全都只看画面，而画面在两种实现下**完全一样**。

所以必须有：

```java
// SeriesBuffer 暴露本帧的实际上传字节数（仅供校验器）
int uploadedBytesThisFrame();
```

断言：**滚动 100 帧、每帧新增 10 个点之后，`uploadedBytesThisFrame()` 恒等于 `10 * 8`，而不是 `windowSize * 8`。**

**这是唯一能把"GPU 常驻 + 增量上传"与"每帧全量重传"区分开的断言**——与 `TextVerifier` 里那条"24px 与 192px 的过渡带宽度都是 1px"是同一类东西：**它观察的量就是那件事本身**。

**变异验证**：把增量上传改回全量上传，这条断言必须失败。如果它不失败，说明计数器数的是别的东西（本项目已有四次"断言观察的是另一个量"的教训，见记忆 `jfgl-silent-output-defects`）。

### 9.4 校验器的场景必须会变

`PickVerifier` 出过一次真实的盲区：**24 条断言全绿，却漏掉了 `PickBuffer.clear()` 受 `GL_SCISSOR_TEST` 影响**——因为它的场景每帧完全相同，陈旧 ID 与新鲜 ID 恰好一致。

**`ChartVerifier` 必须包含跨帧变化**：

- 某条曲线在第 3 帧**消失**（`Series` 被移除），断言第 3 帧之后它的 ID **不再命中**。
- 窗口**滚动了若干帧**，断言画面确实变了（且是按预期平移），而不是一帧静态图。
- 数据里**新插入一个 NaN**，断言缺口出现。

### 9.5 退出码完整性

三个已有校验器都修过一个 bug：**断言抛异常会让 GL 线程死亡，于是 `exitProcess` 从未运行——打印了 FAIL 却报告退出码 0。** `ChartVerifier` 从一开始就按修好的形状写（`try/finally` 里保证退出码）。

---

## 10. 已知风险

**R1：`SeriesBuffer` 的容量在创建时定死，运行期永不扩容。** 这是 §5.2 那条硬约束的直接结果。环容量取 2 的幂，与 `RingChartData` 的容量一致。**容量估算错了的表现是"数据被静默丢弃"或"构造时抛异常"**——前者是静默错误，所以构造时要有一条明确的容量下界检查。

**R2：双实现的不一致。** 数值 → 屏幕的映射在 `Axis.dataToDisplay`（CPU，`chart/`）与顶点着色器（GPU）里各有一份。**两份必须一致**，否则刻度线会与数据点错开——而这是"看起来只是没对齐"，最难查的那类。§9.1 的 `ChartRenderLayoutTest` 专门守这条：**用同一组输入跑两份实现，断言结果逐一相等**。

**R3：接头质量是已知限制**（§5.1）。`lineWidth > 3` 时可见。

**R4：float 精度。** 数据存成 float32。若数据动态范围极大（例如量级 1e9 上要分辨 1e-3 的差别），float32 表示不了，表现为**曲线是平的或有台阶**。本期不做处理，但 `SeriesBuffer` 的类文档要写明这条，以及逃生口：**上传前先减去一个基准值**（存相对值，着色器里再加回来）。

**R5：`glGetUniformLocation` 的字符串查找**（§5.3）。修法是加缓存，属于本规格范围内。

**R6：`ChartRenderer` 与 `Gc` 的生命周期耦合。** `charts` 懒创建于 `Gc`，但 GL 资源的所有权链条目前是 `FXGLTransfer.onDispose → RenderBatch.dispose()`。**`ChartRenderer` 必须挂进这条链**，否则帧缓冲尺寸变化或窗口关闭时泄漏。这一条要在实现时明确，不能靠"反正程序要退出了"。

---

## 11. 交付物

**新建**：`src/main/java/com/bingbaihanji/jfgl/chartrender/` 下的 §5.4 所列全部类；
`src/test/java/com/bingbaihanji/jfgl/chartrender/` 下的 §9.1 测试类；
`src/main/kotlin/com/bingbaihanji/jfgl/example/ChartVerifier.kt`。

**修改**：
- `src/main/kotlin/com/bingbaihanji/jfgl/renderer/Gc.kt` —— 加 `flush()`（§5.6）与 `charts`（§5.5）
- `src/main/java/com/bingbaihanji/jfgl/renderer/RenderBatch.java` —— 加 `withPickPass(Runnable)`（§6.3），**不动** `pickPassCount` 的语义
- `src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java` + `LwjglGLAbstraction.java` —— 加 §5.2 的三个方法
- `src/main/java/com/bingbaihanji/jfgl/gl/ShaderProgram.java` —— 加 vec4 uniform + location 缓存
- `CLAUDE.md` —— 更新「图表」一节（② 已完成部分）、测试计数、`glBufferSubData`/instancing 的事实
- `README.md` —— 补图表绘制用法

**不改**：`chart/` 下的**任何一行**（§3.1 的硬约束）。`text/`、`geom/`、`math/`、`util/` 不动。

---

## 12. 交付分解

| 步骤 | 内容 | 交付后能做什么 |
|---|---|---|
| **②-1** | `Gc.flush()` + `RenderBatch.withPickPass` + `GLAbstraction` 三个方法 + `ShaderProgram` 的 vec4/location 缓存 | （地基，画面无变化；但 `flush()` 可被 `PipelineVerifier` 单独验证） |
| **②-2** | `SeriesBuffer` + 着色器 + `LineSeriesRenderer` + 窗口区间算术 + `ChartRenderer.draw` | **屏幕上出现第一条流动的曲线** |
| **②-3** | `ScatterSeriesRenderer` + 拾取接通 + `ChartVerifier` 全量断言 | 曲线可拾取、散点可用 |
| **②-4** | （另出）STEP / AREA / BAR / HEATMAP / WATERFALL | 图型补齐 |

**依赖方向**：②-2 的绘制依赖 ②-1 的 `GLAbstraction` 三个方法与 `ShaderProgram` 的补充，因此**两者不能完全并行**。可以并行的是 ②-2 内部**不碰 GL 的那一半**——`WindowRange` 的区间算术与 `ChartRenderLayout` 的映射（§9.1 的前两个测试类），它们纯算术、依赖 `chart/` 的 `Axis` 即可开工，等 ②-1 落地再接上绘制。

**②-1 与 ②-3 的拾取部分可以完全并行**（②-3 只依赖 `withPickPass` 这个签名，签名在 ②-1 里冻结）。

**每个步骤都必须带自己的变异验证**，规则照旧：**报告「变异存活 + 为什么」比报告「全部杀死」更有价值**，并对存活的变异**做对照实验证明变异是活的**。
