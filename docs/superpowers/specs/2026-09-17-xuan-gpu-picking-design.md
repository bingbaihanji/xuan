# Xuan GPU 拾取设计（子项目 C：拾取与交互）

> 日期：2026-09-17
> 状态：已确认
> 范围：本文档只覆盖**子项目 C（GPU ID 缓冲拾取）**。依赖子项目 A（已完成），不依赖 B。
> 父 spec：`2026-09-11-xuan-render-pipeline-design.md`

---

## 1. 背景与目标

子项目 A 已经落地单一批处理管线，并且**顶点格式从第一期就预留了拾取 ID**
（偏移 20 处的 `uint`，location 3）。但该通道至今从未被使用：`Gc.emitTriangles`
硬编码写入 `0`，顶点着色器声明了 `aId` 却从不输出——读进来就丢掉。

本设计把这个预留通道接上，实现完整可用的 GPU 拾取。

**目标**

- 任意图元都能被打上 ID 并被精确拾取（按像素，不是按包围盒）
- 拾取结果直接解析回调用方的对象，组件侧零样板代码
- 提供点查询与矩形区域查询，支撑图表的框选/刷选
- 跨线程可用：组件的鼠标事件在 JavaFX 线程，而 `Gc` 只能在 GL 线程
- **拾取结果永不与画面不一致**

**非目标（本期不做）**

- 同一点下"全部重叠对象"的列表（GPU 方案做不到，需要逐对象多趟渲染）
- 套索/任意多边形区域查询
- PBO 异步读回（见第 11 节偏差说明）
- 拾取结果的可视化调试视图
- 光标形状管理、拖拽状态机等交互框架（属于组件层）

---

## 2. 与父 spec 的关系

**继承的决策**（父 spec 已定，本文档不推翻）

- 拾取复用同一条管线，"只是换个 fragment shader"（父 spec 第 115/119 行）
- 逐顶点 ID 属性，已在顶点格式中

**本次作答的开放问题**

> 父 spec 第 371 行："MSAA 与拾取 FBO 的交互——拾取不需要抗锯齿（需要精确 ID），
> 但 1px 细线的可拾取性会受影响。子项目 C 需决定拾取 FBO 是否匹配采样数。"

**答复：拾取 FBO 恒为单采样，不匹配颜色 FBO 的采样数。**

依据是一个实测事实：`GLCanvas.Defaults.MSAA` 的常量值是 **0**，而 `FXGLTransfer`
把它作为 `msaa` 参数的默认值透传。也就是说**当前默认配置下颜色 pass 本身就是单采样的**，
两者采样数相同，父 spec 担心的"1px 细线看得见却点不中"在默认配置下**不存在**。

若使用方显式把 `msaa` 设为大于 0，则颜色 pass 有抗锯齿而拾取 pass 没有，
此时最边缘 1px 的几何存在"看得见、点不中"的可能。这是**已知且可接受**的取舍：
拾取需要精确的 ID，抗锯齿产生的部分覆盖像素把 ID 插值成"零点几个对象"没有意义。
推荐用第 10 节的"隐形热区"手法消除影响，而不是给拾取 FBO 也上 MSAA。

**偏差说明**

父 spec 第 45 行提到拾取用 "PBO 异步读回"。本设计**第一期用同步读回**，理由见第 11 节。

---

## 3. 架构

### 3.1 包结构

```
com.bingbaihanji.xuan
├── gl/           Framebuffer【新增】     FBO 封装（子项目 A 的 spec 已预留此名）
│                 GLAbstraction【扩充】   补 FBO / 整数纹理 / 读回方法
├── renderer/     PickBuffer【新增】       持有 FBO，清空、读点、读区域
│                 PickRegistry【新增】     ID 分配与 id→payload 映射（纯内存，可单测）
│                 RenderBatch【扩充】      增加 ID 着色器与 drawPickPass
│                 Gc【扩充】               pickId 状态、pickable、pick/pickRect
├── glview/       FXGLTransfer【扩充】     pickAsync 线程安全入口、reshape 重建
└── util/         Disposable【沿用】
```

### 3.2 组件职责

| 组件 | 做什么 | 依赖 | 能否单测 |
|---|---|---|---|
| `gl/Framebuffer` | 一个 FBO + 一张 `R32UI` 纹理；创建、绑定、查完整性、销毁 | `GLAbstraction` | 否（需 GL） |
| `PickBuffer` | 按帧缓冲尺寸持有 Framebuffer；`clear()` / `readPixel(x,y)` / `readRect(...)` | `Framebuffer` | 否（需 GL） |
| `PickRegistry` | 分配非零 ID、映射 payload、回收、解析 | 无 | **是** |
| `RenderBatch` | ID 着色器；`drawPickPass(commands)`；跳过优化 | 已有 | 否（需 GL） |
| `Gc` | `pickId` 进 save/restore 栈；`pickable{}`；`pick`/`pickRect` | 以上 | 否（需 GL） |

**`PickRegistry` 是唯一纯内存的单元**，也是本设计里唯一能被常规单测完整覆盖的部分。
ID 分配的正确性（0 不可分配、不重复、溢出不环绕）全部落在它身上，见第 12 节。

### 3.3 为什么是独立 FBO 而不是 MRT

考虑过 MRT（ID 作为第二个颜色附件，与颜色 pass 同批出，几何完全不重画）。否决理由：
它要求接管整个输出路径——自建 FBO 渲染再把颜色 blit 回 openglfx 的 FBO。而这会与
openglfx 画布的 MSAA 配置、像素格式假设纠缠，改动面从"加一个子系统"变成"改写主渲染路径"。

独立 FBO 的 ID pass **复用同一份 VBO 与同一张命令表，只换一个 shader**，
因此它永远不可能与画面不一致——不会出现"看到的位置与可点的位置对不上"这类最难查的 bug。
这个性质比省一趟几何更值钱。

---

## 4. 渲染侧

### 4.1 顶点格式不变

**本次不需要改动 `VertexFormat`。** `id` 已在偏移 20（location 3），
`RenderBatch.configureVaoAttributes` 已用 `glVertexAttribIPointer` 正确建立该属性
（整数属性必须用 IPointer，不能用浮点路径——这一点子项目 A 已经做对）。
唯一缺的是 `Gc.emitTriangles` 把它写死成 0，以及着色器没有把它送出去。

### 4.2 独立的 ID 着色器程序

新增第二个 `ShaderProgram`，不修改现有颜色着色器：

```glsl
// 顶点
#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 3) in uint aId;
flat out uint vId;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); vId = aId; }

// 片段
#version 330 core
flat in uint vId;
out uint fragId;
void main() { fragId = vId; }
```

`flat` 不可省略：ID 是整数，跨三角形插值出来的中间值对应不存在的对象。
不修改颜色着色器，是为了不在热路径上多背一个用不到的 out 变量。

### 4.3 ID pass 的 GL 状态

| 状态 | 值 | 理由 |
|---|---|---|
| 混合 | **关闭** | 对整数附件开混合是无效操作；ID 被插值无意义 |
| 裁剪 | **保持开启** | 被裁掉的部分不可拾取，见第 10 节 |
| 视口 | 不变 | 依赖"ID FBO 与主帧缓冲同尺寸"这一不变式 |
| 纹理绑定 | 无所谓 | ID 片段着色器没有 sampler |

### 4.4 清空：必须在每批判断，不能每帧判断

ID buffer 每帧必须清空一次，但**不能在 `beginFrame` 清**——那一刻还不知道本帧会不会用到拾取。
两种看似合理的做法都是错的：

- **无条件每帧清**：不用拾取的应用每帧白付一次全屏清空（988×738×4 ≈ 2.9 MB）；
- **"上帧用过才清"**：本帧首次引入拾取对象时，缓冲里是上次清空时的陈旧数据，
  表现为**拾取到早已消失的对象**，而画面完全正常。典型的静默错误。

解法是把判断粒度下沉到**每批**：`VertexWriter` 在 `vertex(...)` 收到非零 ID 时置标志，
`reset()` 时清掉（它已经管着 flush 生命周期，天然按批复位）。于是：

```
submit(writer):
    颜色 pass 逐条绘制
    if writer.hasPickableVertices():
        if not pickBufferClearedThisFrame:
            pickBuffer.clear()                    // 每帧最多一次，懒执行
            pickBufferClearedThisFrame = true
        drawPickPass(commands)                    // 同一份 VBO、同一张命令表
    复位 GL 状态
```

**这个结构天然兼容帧中途 flush**：每批各自贡献自己的 ID，没有任何一方需要回放历史。
而完全不用拾取的帧，**一次额外 GL 调用都没有**。

"跳过优化"本身需要可观测才能验证，因此 `RenderBatch` 额外暴露一个内部诊断计数器
`pickPassCount`，仅供校验器断言（见第 12 节）。

### 4.5 清空用 `glClearBufferuiv` 而不是 `glClear`

对整数颜色附件，`glClearColor` + `glClear` 是**未定义行为**。
必须用 `glClearBufferuiv(GL_COLOR, 0, {0,0,0,0})`。

写成前者在多数驱动上"看起来能跑"（正好清成 0），但不保证。
这属于本仓库最警惕的那类缺陷：不报错、不崩溃、换个驱动才暴露。

---

## 5. ID 注册表

```kotlin
class PickRegistry {
    fun register(payload: Any?): Int      // 返回非零 ID
    fun unregister(id: Int)
    fun resolve(id: Int): Any?            // 未注册返回 null
    fun clear()
    val size: Int
}
```

**规则**

1. **ID 0 永不分配**，恒定保留给"什么都没命中"。分配器从 1 开始。
2. **ID 绝不环绕**。计数到上界时抛异常，绝不回绕。
   环绕意味着新对象复用了一个仍被引用的 ID——**拾取到毫不相干的对象**，
   而且是概率极低的偶发，宁可炸。

   **上界是 `Integer.MAX_VALUE - 1`（`0x7FFFFFFE`），不是 ID 通道的物理容量
   `0xFFFFFFFF`。** 两个原因叠加：其一，注册表对外发的是 `Int`（有符号），
   而顶点属性是 `uint`——一个负 ID 写进顶点属性会变成一个巨大的无符号值，
   所以注册表**有意**只使用正半区；其二，上界是含的，`maxId` 若取到
   `Integer.MAX_VALUE`，发完最后一个 ID 后 `nextId++` 会溢出成负数，
   而 `nextId > maxId` 对负数恒为假，"绝不环绕"就成了一句空话。
   代价是可用 ID 空间减半（仍约 21 亿个），对这个子系统无实际影响。
3. **回收复用**：`unregister` 把 ID 放进空闲表（LIFO），下次 `register` 优先复用。
   否则长生命周期应用反复注册/注销会把 ID 空间磨光。
4. **强引用**：注册表持有 payload 的强引用，调用方必须 `unregister`
   （或对整组对象调用 `clear()`）。不用弱引用——弱引用被回收后表现为"什么都拾取不到"，
   又是一个静默失败。生命周期由调用方显式负责。
5. **线程安全**。注册（随数据变化，通常发生在 JavaFX 线程）与解析（拾取查询，
   在 GL 线程）天然分处两个线程，所以注册表本身**必须**线程安全，
   而不是把"只能在 GL 线程调用"写进文档了事——那条约束与自然用法相悖，
   迟早被违反，而违反的表现是 `resolve` 静默返回 `null`
   （表现为"什么都没拾取到"），又是一个不崩的静默失败。

   分工：`payloads` 用 `ConcurrentHashMap`，使 `resolve` 保持**无锁**——
   `pickRect` 要逐像素解析，这是查询热路径；ID 分配状态（`freeIds` / `nextId`）
   用一把锁护住，整个 `register` / `unregister` / `clear` 在同一把锁内完成
   （分配是"取空闲表 → 递增计数器 → 写入映射"的复合操作，
   单靠并发容器不足以保证原子性）。按下面的用法约定，注册不是热路径，
   锁的开销无关紧要。

   ⚠️ **这里有个坑，别照字面实现**：`ConcurrentHashMap` **不接受 `null` 值**，
   而规则 4 要求 payload 可为 `null`（"注册过一个空对象"必须能和"没注册过"区分开，
   否则那个 ID 永远回收不了）。两者冲突，解法是**私有哨兵对象**：
   `register` 入口把 `null` 换成哨兵，`resolve` 出口翻译回 `null`，哨兵不出本类。

   **不要**改用 `Collections.synchronizedMap` 绕开——那会把锁加回 `resolve`，
   正是本条要避免的。

**用法约定**：注册发生在数据变化时，**不是每帧**。注册表是跨帧稳定的 id↔对象映射，
每帧重新注册是错误的用法（会耗尽 ID 空间并让 unregister 语义失效）。

---

## 6. 用户 API

```kotlin
// —— 打标 ——
var pickId: Int = 0                                  // 0 = 不参与拾取（默认）
fun pickable(id: Int, block: () -> Unit)

// —— 注册表 ——
val pickRegistry: PickRegistry

// —— 查询（GL 线程）——
fun pick(x: Float, y: Float): PickHit?
fun pickRect(x: Float, y: Float, w: Float, h: Float): List<PickHit>

class PickHit(val id: Int, val payload: Any?, val x: Float, val y: Float)
```

### 6.1 `pickId` 进 save/restore 栈

`Gc.save()/restore()` 已经覆盖变换、裁剪、填充色、描边色、线宽、全局不透明度。
`pickId` 作为**第七项**加入同一个栈（`styleInts` 每层从 2 个 int 扩到 3 个）。

于是 `pickable` 直接就一行：

```kotlin
fun pickable(id: Int, block: () -> Unit) {
    save(); pickId = id; block(); restore()
}
```

复用已经测过的栈，不新造一套作用域机制。**副作用是块内的变换/裁剪改动也会在块尾回滚**，
与 `save/restore` 语义完全一致——这是有意的，块因此是自包含的。

### 6.2 查询语义

| 情况 | `pick` 返回 | `pickRect` 返回 |
|---|---|---|
| 命中已注册对象 | `PickHit(id, payload, x, y)` | 该列表 |
| 命中未注册的 ID | `PickHit(id, null, x, y)` | 含该项 |
| 落在背景（ID 0） | `null` | 不含该 ID |
| 从未渲染过拾取 pass | `null` | 空列表 |

**"查不到 payload"不等于"没命中"**：ID 仍然是真实的命中信息，payload 为空是诚实的表达。
静默降级成 `null` 会让人以为没点到。

`pickRect` **去重**后返回，顺序按 ID 升序（保证可预测，便于断言）。
代价与区域面积成正比（`w×h×4` 字节的读回），大面积刷选要意识到这一点。

**`PickHit.x/y` 的确切含义**（消除歧义）：

- `pick` 返回的 `x`/`y` 就是传入的查询点坐标。
- `pickRect` 返回的 `x`/`y` 是该 ID **在区域内按行扫描首次出现的像素坐标**
  （从区域左上角起、逐行向右）。这个取值是确定性的、可复现的，
  便于调试"它到底命中在区域的哪个位置"，也便于断言。
  它**不是**该图元的中心或任何几何代表点。

**越界行为**：

- `pick(x, y)` 的点落在绘制区之外 → 返回 `null`，不做读回。
  越界读 `glReadPixels` 是未定义行为，必须在这里挡掉。
- `pickRect` 的矩形与绘制区求交后再读回；交集为空 → 返回空列表。
  **不抛异常**——刷选拖到窗口外是正常操作。

### 6.3 拾取由几何决定，与颜色和透明度完全无关

ID pass 不看颜色、不看 alpha。因此 `globalAlpha = 0` 或全透明的图元**照样能被拾取**。

这是**有意保留**的行为：图表的"隐形热区"（比数据点大一圈、方便点中的透明矩形）
正是靠它实现的。但它反直觉，必须写进文档，并且**用测试钉死**（见第 12 节）——
否则将来有人"顺手修好"这个"bug"，隐形热区会静默失效。

---

## 7. 线程模型

`Gc` 只能在 GL 线程使用（所有 `gl*` 调用都必须在 `GLCanvas` 的回调内部）。
而组件响应鼠标事件是在 **JavaFX 应用线程**。

让每个组件自己写这个跳转，迟早有人写错——直接调用就是跨线程 GL 调用，
崩得毫无规律且难以复现。所以由框架提供一个线程安全入口：

```kotlin
// FXGLTransfer
fun pickAsync(x: Float, y: Float, callback: (PickHit?) -> Unit)
```

**实现要点**

1. 请求塞进一个 **volatile 的"最新覆盖旧的"槽位**，不是队列。
   鼠标拖拽每秒几十个事件而帧率只有 60，排队毫无意义且会累积延迟。
2. 在 GL 线程的帧末（`endFrame` 之后）取出槽位解析。
3. 回调经 `Platform.runLater` 回到 **JavaFX 线程**送达——
   这样组件在回调里能安全地碰 JavaFX 状态，不需要自己再跳一次。

**注册侧**：上面只解决了**查询**的跨线程问题，注册侧同样要交代。
注册表本身线程安全（见 §5 规则 5），所以在 JavaFX 线程上随数据变化直接
`register` / `unregister` 是**合法**的，不需要再跳一次线程、
也不需要走 `pickAsync` 那套槽位。由此产生的两个可见后果都是规格内的，
**不是缺陷**，消费方不要当成 bug 去"修"：

- 刚注册的对象当帧可能还没被画出来，于是拾取不到——差一帧。
- 刚注销的对象当帧可能仍被画着，于是命中 `PickHit(id, null, x, y)`——
  这正是 §6 表中"命中未注册的 ID"那一行，消费方必须处理 `payload` 为 `null`。

`Gc.pick` 本身的合法调用时机是**宽松的**：`endFrame` 之后读是正常用法
（`pickAsync` 正是如此）。从未渲染过拾取 pass 时返回 `null` 而不抛异常——
"还没画过"是正常状态，不是错误。

---

## 8. 错误处理

三个必须**显式失败**的地方：

1. **FBO 不完整**：创建后 `glCheckFramebufferStatus` 检查，不是 `GL_FRAMEBUFFER_COMPLETE`
   就抛异常并带上状态码。不完整的 FBO 读回来全是 0，表现为"什么都拾取不到"——
   一个安静的、看起来像业务逻辑问题的失败。
2. **整数附件清空**：见 4.5，用 `glClearBufferuiv`。
3. **ID 溢出**：见第 5 节，抛异常而不环绕。

第 4.4 节的"陈旧数据"问题不靠抛异常解决——它是设计上消除的（懒清空 + 每批判断）。

---

## 9. 不变式

1. **ID FBO 与主帧缓冲同尺寸**，且在 reshape 时重建。
   裁剪靠 `glScissor`，而 `RenderBatch.applyScissor` 的 y 是拿 `viewportHeight` 换算的。
   只要两者同尺寸，ID pass 就能直接复用同一套 scissor 换算。
   尺寸不同会让裁剪错位——**被裁掉的部分仍可拾取**，用户点看不见的地方却命中。
2. **拾取 FBO 恒为单采样**，与颜色 FBO 的采样数无关（见第 2 节）。
3. **`GL_FRAMEBUFFER_BINDING` 必须原样恢复**。openglfx 渲染到它自己的 FBO（非 0），
   ID pass 绑定自己的 FBO 后若不恢复，下一帧的颜色会画进拾取缓冲——
   表现为窗口空白或停留在上一帧。
4. **读回的 y 必须翻转**：`glReadPixels` 原点在左下、y 向上，
   而用户坐标原点在左上、y 向下。单像素读 `readY = height - 1 - y`；
   矩形读 `glY = height - y - h`。漏掉翻转会得到一个**上下镜像的、部分正确的**拾取结果——
   恰恰是本项目最擅长产生的那类缺陷。

---

## 10. 已知限制

- **1px 细线的可拾取性**：`msaa = 0`（默认）时无此问题。显式开启 MSAA 后，
  颜色 pass 抗锯齿而拾取 pass 不抗锯齿，最边缘 1px 可能看得见点不中。
  推荐解法是给细线/小点单独叠一个透明热区，而不是改拾取 FBO 的采样数。
- **更小的几何同理**：亚像素大小的三角形在单采样下可能完全不产生片段。
  图表的小数据点应配热区（这也是"隐形热区"被设计成可行行为的原因）。
- **只能拿到最上层对象**：后画的覆盖先画的，GPU ID buffer 天然如此。
  "同一点下所有重叠对象"需要逐对象多趟渲染或另外维护层次结构，本期不做。
- **`pickRect` 的读回是同步点**：会强制刷新管线。大面积刷选有可感知的代价。

---

## 11. 偏差说明：PBO 异步读回延后

父 spec 第 45 行提到拾取用 "PBO 异步读回"。本期**用同步读回**，理由：

1. **单像素读回的代价很小**。PBO 的价值在于隐藏大块读回的延迟；
   而 `pick` 每次只读 4 个字节，收益有限。
2. **`pickRect` 才是 PBO 的真正受益者**，但它是按需调用（刷选），不是每帧调用。
   在确实测出瓶颈之前引入 PBO 会显著增加复杂度（双缓冲 PBO、跨帧配对、
   读回就绪判定），并把一个简单子系统变成有状态机的东西。
3. **API 不变**。将来在 `PickBuffer` 内部换成 PBO，`pick`/`pickRect` 的签名与语义不受影响，
   调用方零改动。

因此这是一个**实现细节的延后，不是能力的缩水**。

---

## 12. 测试策略

本项目的缺陷多数属于"静默错误输出"：编译通过、单元测试全绿、画面却是错的。
拾取子系统尤其危险——一个错误的拾取实现**不会让任何画面变坏**，
只会让点击落在错误的对象上，而这在肉眼看来完全正常。

### 12.1 纯 CPU 单测：`PickRegistryTest`

- 分配的第一个 ID 不为 0，且绝不返回 0
- 多次分配 ID 互不重复
- `unregister` 后 `resolve` 返回 `null`
- 回收的 ID 会被后续 `register` 复用
- `resolve` 一个从未注册的 ID 返回 `null`
- `clear()` 后全部解析为 `null`
- **ID 溢出抛异常而不是环绕**（用小上限构造注入，参考 `VertexWriter` 的测试构造器先例）

### 12.2 像素校验器扩展：`PipelineVerifier` 增加拾取章节

沿用既有的像素级口径：

- 已知几何 + 已知 ID → 在图形内部取点，断言**精确命中**那个 ID
- 背景下点 → `null`
- **重叠区域取最上层**（后画的赢）
- 图形边缘外 1px → `null`（防止"包围盒式"的假拾取）
- `pickRect` 覆盖多个图元 → 返回集合**恰好等于**期望集合（不多不少）
- 被 `clipRect` 裁掉的区域 **不可拾取**
- **透明图元仍可拾取**（把 6.3 的行为钉死）
- 整帧无拾取对象 → `pick` 返回 `null`，且 **ID pass 一次都没跑**

最后一条需要可观测性才能断言，"跳过优化"否则无法验证。`RenderBatch` 因此暴露一个
内部诊断计数器 `pickPassCount`（累计执行过的 ID pass 次数），仅供校验器读取。
没有它，跳过优化就只是一句注释里的承诺——而"优化悄悄失效"正是本项目最该防的那类问题。

### 12.3 变异验证（必须做）

改了断言就要证明断言有区分力，否则是橡皮图章。至少两条：

1. **去掉 y 翻转** → 校验器必须失败。
   这条尤其重要：漏翻转会得到一个**上下镜像但部分正确**的结果，
   在矩形对称的测试场景里甚至可能碰巧通过。
2. **让 ID pass 忽略 scissor** → 被裁掉区域必须从"不可拾取"变为"可拾取"，
   校验器必须失败。这条把第 9 节的不变式钉死。

每条变异都要**实际注入并跑一遍**，确认失败，再回滚。

---

## 13. 建议的任务分解（供 writing-plans 细化）

1. `gl/Framebuffer` + `GLAbstraction` 的 FBO 扩充（含完整性检查）
2. `PickRegistry` + 完整单测（纯 CPU，可独立验证）
3. `PickBuffer`（清空、读点、读区域、尺寸重建）
4. `RenderBatch`：ID 着色器、`drawPickPass`、跳过优化、`VertexWriter.hasPickableVertices`
5. `Gc`：`pickId` 进栈、`pickable`、`pick`、`pickRect`
6. `FXGLTransfer`：reshape 重建、`pickAsync`
7. 校验器扩展 + **两条变异验证**
8. 文档更新（CLAUDE.md / README.md）

依赖顺序：1 → 2/3 → 4 → 5 → 6 → 7。第 2 步不依赖任何 GL 工作，可以最先跑通。

---

## 14. 后续可扩展（不在本期）

- PBO 异步读回（第 11 节）
- 套索/多边形区域查询
- 逐对象多趟渲染以支持"全部重叠对象"
- 拾取缓冲的可视化调试视图
- 光标形状与拖拽状态机
