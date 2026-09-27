# 路径命中判定（`isPointInPath` / `isPointInStroke`）设计规格

**日期**：2026-09-28
**范围**：给 `Gc` 的 Path API 加两个 CPU 侧的命中判定。**纯增量**，不改任何既有行为。
**性质**：延续 2026-09-28 那批 Canvas 命名对齐（`quadTo`→`quadraticCurveTo` 等）。

---

## 1. 问题

JFGL 现在只有一条"这一点在不在我的图形上"的路：**GPU 拾取**
（`pickId` / `pickable` / `pick`）。它很准（像素精确），但要求：

- 图形得有 `pickId` 并**注册进注册表**；
- 查询要走 GL 线程 / 异步 PBO（JavaFX 线程上只能用 `pickAsync`，回调晚一帧）；
- 它回答的是"**哪个注册对象**"，不是"**当前这条路径**"。

而作图和命中测试里有一类很常见的问法用不上上面任何一条：
"我这条**正在构造的**路径，包不包含这一点？"——比如拖拽预览的自命中、
把路径当裁剪/区域用时、或者在非 GL 线程里做几何判断。

## 2. 非目标

- **不做** `pathBoundsOf()`、`isPointNearPath()` 等（用户 2026-09-28 明确只要这两个）。
- **不做**填充规则参数（`nonzero` / `evenodd` 二选一）：JFGL 只有**一种**填充语义，
  加一个改不了渲染结果的参数就是"设了没用"。
- **不改** GPU 拾取。

## 3. API

```kotlin
gc.beginPath(); gc.moveTo(…); gc.lineTo(…); gc.fillPath()
gc.isPointInPath(x, y)      // Boolean
gc.isPointInStroke(x, y)    // Boolean
```

- 针对**当前路径**（`fillPath` 之后路径仍在，与 Canvas 一致——`beginPath` 才清空）。
- 路径为空 / 点数不足 / `lineWidth <= 0` ⇒ `false`。
- **点按设备像素解释，路径按当前变换（CTM）变换后再判定**——Canvas 语义。
  ⚠️ 这是 Canvas 的著名反直觉点：**点不受变换影响，路径受**。

## 4. 两个判定各自的算法

### 4.1 `isPointInPath`：交叉计数（奇偶规则）

把当前路径按 `matrixScale()` 平坦化（与 `fillPath` **同一份**容差），逐点施加
`state.transformX/Y`（与 `emitTriangles` **同一个**变换函数），然后在设备像素空间里
对**全部子路径**做交叉计数：奇数 ⇒ 在内部。

**为什么是奇偶而不是另立一套**：JFGL 的填充是"按**包含关系**定洞"
（`Tessellator.tessellateContours`，嵌套深度偶数为外轮廓、奇数为洞）。
对良构路径（`Tessellator` 写明的**前提**：各轮廓是简单多边形、无自相交），
**嵌套深度奇偶 ≡ 交叉计数奇偶**，两者恒等。分歧只可能出现在"部分重叠但不包含"的
轮廓上，而那已经踩了前提。

**为什么复用平坦化结果而不是走 `Tessellator`**：走三角化虽然"就是画出来的那块"，
但它有前提耦合、且每次查询要跑一遍耳切（O(n²) 量级）。交叉计数是纯算术、零分配、
无前提，且与填充的等价性是一条**可证**的命题。

### 4.2 `isPointInStroke`：**复用 `StrokeGenerator` 本身**

把平坦化后的点交给 `StrokeGenerator.stroke(...)`，用**与 `strokeOutline` 完全相同的
参数**（`Cap.BUTT` / `Join.MITER` / `MITER_LIMIT` / `width = lineWidth` / `capExtension = 0`），
取它产出的三角形，判定"点落在其中**任一个**三角形里"。

**★ 这是本规格最重要的一条设计**：`isPointInStroke` 要"与画面一致"（含 miter 尖角、
含超限回退 bevel），而**接头那套几何相当繁复**。把它抄一遍等于制造**同一条尺规的两份
实现**——本仓库已经为这件事吃过亏（`ChartInteraction` 与 `ChartLayout`、
`Texture` 与 `LwjglGLAbstraction` 都漂移过）。喂同一份代码就没有漂移的余地。

三角形之间是**重叠**的，而"在任一个里"取的是**并集**——那正是墨迹覆盖的区域。

⚠️ **`capExtension = 0`，即不含 AA 的 1 设备像素外扩**：那个外扩是**渲染期**为了让边缘
有外侧片元而加的，不是几何。所以 **AA 开启时，`isPointInStroke` 会比拾取窄约 1 像素**
（拾取的 ID pass 复用加宽过的几何，这是已声明的行为）。

## 5. 与 GPU 拾取的关系（**文档必须写清——两者会给出不同答案**）

| | GPU 拾取 | 本功能 |
|---|---|---|
| 对象 | **注册过**的对象（要 `pickId`） | **当前路径**（无需注册） |
| 线程 | 必须 GL 线程 / 异步晚一帧 | **任意线程**（纯计算） |
| `clipRect` | **影响**（ID pass 走同一个裁剪） | **不影响**（与 Canvas 一致） |
| 判定 | **像素精确**（光栅化） | **解析**（奇偶规则 / 解析描边几何） |
| AA 开启时 | 范围**大 1 设备像素** | **不含**那个外扩 |

⇒ 两者都能回答"这一点在不在我的图形上"，而在**图形边缘那一个像素**、
以及**开着 `clipRect` / `antialias`** 时会分家。这不是缺陷，但**必须写下来**，
否则下一个人会以为其中之一错了。

## 6. 分层

- **`jfgl-core`（纯计算）**：新增 `geom/PathHit`，两个静态函数：
  - `isPointInContours(float[] points, int[] offsets, int[] counts, int contourCount, float x, float y)`
  - `isPointInTriangles(float[] triangles, int floatCount, float x, float y)`
  两者都只依赖 `float[]`，**不依赖 `Path` / `Flattener` / GL**，因此可以脱离一切单测。
- **`jfgl-render-gl`（门面）**：`Gc.isPointInPath` / `Gc.isPointInStroke` 负责
  "平坦化 → 变换 → 调用"。变换用 `state.transformX/Y`，与 `emitTriangles` 同源。

## 7. 错误处置

- 路径为空、`pointCount < 3`（路径）⇒ `false`。
- `lineWidth <= 0`（描边）⇒ `false`（与"负线宽不画线"同一处置）。
- **非有限数的查询点** ⇒ `false`。NaN 与任何几何比较都是 false，但显式判掉更清楚，
  且能避免"NaN 点恰好让某个比较分支反过来"这类想象空间。

## 8. 验收

### 8.1 `jfgl-core` 单测（`PathHitTest`）
偶奇规则的边界：三角形内/外/边上、环（嵌套轮廓，洞里的点必须为 false）、
多轮廓（两块之间为 false）、退化（共线点、重复点）、空输入、NaN 查询点。

### 8.2 ★ `PathVerifier` 加一节：**与画面逐点对照**

把若干查询点与那一帧**实际被画出来的像素**对照：`isPointInStroke` 判为 `true` 的点，
其像素必须是描边色。

判据点分**两类，缺一不可**：

- **对照点（带内）**：折线的直段中间、离中心线半线宽以内 ⇒ 两种实现（半带宽 /
  复刻接头）**都判 true**。它钉住"判定不是整个反了"。
- **判别点（miter 尖角）**：拐角外侧、距顶点超过半线宽但在尖角之内 ⇒
  **只有复刻接头会判 true**。它是唯一能把两种实现分开的地方。

**两类一起看**才成立：只有判别点的话，"判定整个取反"会以同样的方向让断言倒，
读起来像"接头没复刻"，而其实连带内都错了。这与 `PathVerifier` 变体 ③/④
用**像素总数之差**而不是"外角有没有像素"是同一条道理。

**这一条是"复刻接头几何"那个选择的唯一判据**——没有它，"与画面一致"就只是
设计文档里的一句话。

### 8.3 变异
- `isPointInStroke` 改成半带宽（丢掉接头）⇒ 8.2 那条**必须倒**。
- `isPointInPath` 的交叉计数判据取反 ⇒ 8.1 的"洞里为 false"必须倒。

## 9. 风险

1. **`Gc` 的 `strokeGenerator` 是共享实例**（`strokeOutline` 也在用）。查询会
   `reset()` 它——必须确认查询与描边不会交错（`strokeOutline` 是
   reset→generate→emit 同步跑完的，所以安全，但要写下来）。
2. **`Tessellator` 的前提不适用于 `isPointInPath`**：交叉计数对自相交路径也有定义
   （奇偶），而填充对那种输入无定义。**这是刻意的**：查询给出一个确定答案，
   而渲染给不出——两者在那类输入上不一致**不是缺陷**，但要在文档里点明。
