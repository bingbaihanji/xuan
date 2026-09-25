# JFGL 架构评审（框架层面）

> 日期：2026-09-24
> 评审人：以项目经理 / 架构评审的视角
> 输入：`jfgl` 三个模块的源码、`CLAUDE.md`、`README.md`、七个校验器、370 条单测的组织方式
> 基线：分支 `feat/render-pipeline`，提交 `06ab309`

---

## 1. 评审范围与证据口径

**直接读过源码的**（本评审的结论只建立在它们之上）：

| 文件 | 规模 |
|---|---|
| `jfgl-render-gl/.../renderer/Gc.kt` | 1436 行，全文 |
| `jfgl-javafx/.../glview/FXGLTransfer.kt` | 543 行，全文 |
| `jfgl-javafx/.../dsl/JFGL.kt` | 311 行，全文 |
| `jfgl-javafx/.../view/MainView.kt` | 52 行，全文 |
| `jfgl-javafx/.../example/ClickExample.kt` | 424 行，全文（作为"用户真实写法"的样本） |
| `jfgl-core/.../chart/{Chart,Axis}.java` | 全文 |
| `jfgl-core/.../chart/ChartLayout`、`Tick`、`Series`、`ArrayChartData` | 通过一次定向勘察取签名与契约 |
| 全仓文件树、`pom.xml` 的依赖声明、`Main.kt` | — |

**从 `CLAUDE.md` 采信的第二手事实**（可信但未复核）：370 条单测的分布、七个校验器的断言条数、
`GPUFFT.java` 的三层缺陷、③-2 / ③-3 经实测决定不做。凡涉及这些的结论我都标注了来源。

**本次复核过的三条断言**：`GPUFFT.java` 的引用点（**零代码引用**）、仓库有无 CI（**无**）、
JavaFX 是否 Windows 专用（**是**，带 `win` classifier）。

---

## 2. 总体结论

**这是一个在"正确性纪律"上远超同类项目的代码库，但在"工程化外壳"上基本空白。**

具体说：它把最难的那部分做对了——**知道自己的错误会以什么形式出现**（静默错误输出），
并且为每一种静默错误建了对应的机器判据（像素校验器、变异验证、期望值的出处推导）。
`CLAUDE.md` 里「怎么验证改动」那一节的成熟度，比多数商业项目的内部文档高一个数量级。

但它的**交付物形态、入口管理、自动化守卫**三个环节基本没做：
没有 CI、校验器住在生产源码集里、主入口是编译期硬编码的、七个校验器靠人记得跑。
这类问题单独看都不致命，合起来的效果是——**这套纪律的存续依赖于"下一个接手的人
也愿意手动跑完七个 main"**，而这是最不可靠的依赖。

一句话：**内核是可信的，围绕它的"让它继续可信的机制"还没建。**

---

## 3. 做得好的地方（带证据）

这些不是客套，每一条都有可核对的判据。

### 3.1 模块边界是机器强制的，不是文档承诺的

`GeomPackageIsolationTest` 与 `ChartPackageIsolationTest` **递归遍历源码**，
按包名白名单守卫依赖方向。`chart/` 连 `renderer/` 都不许依赖，
白名单只放行 `chart/`、`math/`、`util/`。同一个测试还断言 `RenderContext` 是空接口
（0 方法 / 0 字段 / 0 嵌套类型）。

**这一条的价值在于它把架构决策变成了编译通过的判据。** 绝大多数项目的分层图只是
一张会腐烂的图片；这里的会红。

### 3.2 接缝窄到可以一眼看完

`chart/`（纯计算）与 `chartrender/`（GL 后端）之间的契约**只有两个类型**：

- `chart/RenderContext` —— **空接口**，② 用 `chartrender/GLRenderContext` 扩展它；
- `chart/SeriesRenderer` —— 纯函数：数据 + 轴 → 顶点。

这是依赖倒置的教科书用法：**上层定义它需要什么（一个空接口是一个"我什么都不要"的极端声明），
下层去实现**。于是 `chart/` 可以完全不知道 GL 的存在，且这一点由测试强制。

### 3.3 "能不能脱离 GL 上下文跑测试"作为模块分界判据

这条规则本身可复用：它不问"这个类属于哪个功能"，只问"它能不能在**没有 GPU** 的环境里
被验证"。判据客观、无争议、且直接决定了可测性。
`chart/` 的边界就是这么画出来的，`gpu/FftKernel`（着色器源码是纯字符串运算）也归到了可单测那一侧。

### 3.4 失败要响亮——全项目同一套哲学

`LOGARITHMIC` / `TEXT` 轴抛异常、`HEATMAP` / `WATERFALL` 抛异常、标题 `LEFT/RIGHT` 抛异常、
缺字体抛异常、`Axis.tickLabelReserve(TOP)` 抛异常。理由一致且写下来了：
**"按线性去画对数轴，曲线的形状是错的，而画面看起来完全正常"**。

更难得的是它连**反向**也守住了：`ChartLayout` 的"装饰画不下就裁到带子里"明确**拒绝**抛异常，
理由是「`ChartLayout.compute` 在绘制路径上每帧被调用，而 GL 线程上的异常在本项目是**静默吞掉**的
——用静默的坏事去修静默的坏事没有意义」。这是一条有原则的一致，不是"能抛就抛"。

### 3.5 验证文化：期望值必须有出处

`PipelineVerifier` 那条蓝描边像素数 `3084` 的推导（连跑 8 次极差 0 → 解析值 3088.98 的
28 边形修正 → 4.98 px 的像素中心采样偏移由独立参考实现复算 → 无边界像素故不需容差）
是**教科书级别**的。同一节还明确记录了**为什么不用容差**：
旧的 `772.5×4±5%` 藏住了一个真缺陷（MITER 漏半块，偏离 0.7%，照样通过）。
「一条宽到能藏住真缺陷的断言比没有断言更坏」——这句话值得抄进任何团队的标准。

配套的自觉还有：「**被静默跳过的断言比失败的断言更坏**」
（PBO 校验器第一版报告"全部通过"而 `seq10` 根本没提交过）。

### 3.6 文档即设计记录

每一处"为什么不是别的做法"都留在注释里，而不是只留"是什么"。例如
`Gc.fillPath` 的注释记下了**为什么不按位置约定分类子路径**
（一条路径里画两个不相交的圆时，第二个会被当成洞，落在轮廓外 → 静默丢掉一块面积）。
这类注释的价值随时间递增。

---

## 4. 问题清单（按严重度）

### P0-1 ｜没有 CI，而整套价值主张建立在"测试真的被跑过"上

**现象**：仓库根目录没有 `.github/`、没有 `Jenkinsfile`、没有任何 CI 配置（已复核）。

**后果**：
- 370 条单测是纯计算、可以在任何环境跑，**却没有任何机制保证它们被跑**；
- `CLAUDE.md` 自己记录了一次真实的踩坑：变异注入后忘记 `mvn -o install`，
  导致本地仓库留着变异版本，"还原源码后连跑三次全红、且三次输出逐字符相同"。
  **这正是一个 CI（或至少一个固定脚本）会当场抓住的错误**；
- 七个校验器需要 GL 上下文与窗口，确实进不了常规 CI——但"进不了 CI"和
  "没有一个统一的跑法"是两回事。现在是**七个独立的 main，靠人记得**。

**建议**（成本递增）：
1. 加一个 `verify-all.sh`：依次跑七个校验器，**聚合退出码**，任何一个非零则整体非零。
   把"记得跑七个"降为"跑一个命令"。这是本清单里**性价比最高的一条**；
2. 加最小 CI 跑 `mvn -o clean test`（370 条全绿是强信号）；
3. 若要覆盖校验器，需要一个带 GL 的 self-hosted runner——对当前阶段不必。

---

### P0-2 ｜校验器住在生产源码集里，且主入口是编译期硬编码

**现象**：
- 七个 `*Verifier.kt` 与示例同在 `jfgl-javafx/src/main/kotlin/.../example/`，**在生产 classpath 上**；
- `Main.kt` 第 3 行 `import com.bingbaihanji.jfgl.example.main as runPipelineExample` ——
  主入口是**编译期决定**的，没有参数分发（已复核）。

**后果**：
- 断言工具与示例的字节码会进发布包，"哪些是 API、哪些不是"在外壳上分不开；
- 校验器不会被 `mvn test` 触发，**没有东西提醒它们已经腐烂**；
- 想跑任何一个非默认示例，都要改 `Main.kt` 或手写一条长 `exec:exec` 命令。

**建议**：
1. `Main.kt` 加参数分发（`args.getOrNull(0)` → 反射或一个显式注册表）。
   一两行的事，消掉"改源码才能换示例"；
2. 把 `*Verifier.kt` 移到一个独立包（如 `.../verify/`）或独立模块，与示例分开。
   **不必**移进 `src/test`——它们要开窗、要 GL 上下文，surefire 里跑不了，
   `CLAUDE.md` 对此的判断是对的。

---

### P1-1 ｜`Gc` 是上帝类：1436 行、6 项职责

**现象**（逐项清点，都在 `Gc.kt` 里）：

| 职责 | 代表成员 |
|---|---|
| 帧生命周期 | `beginFrame` / `endFrame` / `flush` |
| 状态栈 | `save` / `restore` + 两个平行数组 `styleInts` / `styleFloats` |
| 变换与裁剪 | `translate` / `scale` / `rotate` / `clipRect`（转发 `ViewTransform`） |
| 形状 | `fillRect` / `strokeRect` / `fillCircle` / `strokeEllipse` / `fillPolygon` / `drawLine` |
| 路径 | `beginPath` … `fillPath` / `strokePath` + 平坦化/三角化/描边三块 scratch 缓冲 |
| 文本 | `drawText` / `measureText` |
| 拾取 | `pick` / `pickRect` / `pickable` / `enqueueAsyncPick` / `pollAsyncPick` |
| **图表适配器** | `chartPainter`（60 行匿名对象，实现的是 `chartrender/ChartPainter`） |

**后果**：
- 任何一处改动都要在 1436 行里定位；新能力（渐变、图像、误差棒）天然想往这里加；
- `chartPainter` 有一段**跨层错位**：它是 `chartrender/ChartPainter` 的实现，
  却定义在 `renderer/Gc.kt` 里，读的是 `Gc` 的 5 个成员。

**建议**——**不要拆公开 API**。「像 canvas 的一个入口」是本项目刻意的设计取舍
（`CLAUDE.md` 开头就写了"像 canvas 指的是 API 手感"），拆成 `ShapeOps` / `TextOps`
会让调用方第一次用就困惑。低风险可做的两件：

1. **把 `chartPainter` 抽成 `chartrender/GcChartPainter`**（构造时把 5 个操作作为函数传进去）。
   `Gc` 少 60 行，适配器回到它该在的包，且这一段本来就与 `Gc` 的其余职责无关；
2. 守住增量：下一个能力进来前先问"它该不该住 `Gc`"。
   判据可以是——**它需不需要 `Gc` 的私有状态？** 需要（如路径的三个 scratch 缓冲）就留下；
   只需要公开操作（如图表装饰那 5 个）就该出去。

---

### P1-2 ｜DSL 与 `FXGLTransfer` 的能力落差没有护栏

**现象**：
- `JFGL` 暴露 `onInit` / `onRender` / `onClick` / `onScene` / `overlay`，**鼠标入口只有 `onClick`**
  （按下 / 拖拽 / 抬起 / 滚轮都没有）；
- 画布节点本身**拿不到**：`onScene` 给的是 `Scene`，画布是 `StackPane(view, overlay)`
  的第一个孩子，只能 `overlay.parent` → `children[0]` 掏出来；
- `FXGLTransfer.nodeScale(node)`（局部坐标 → 设备像素的**唯一**换算点）是 **`private`**。

**后果**：
- 只要需求超出"点一下"——菜单栏之外的拖拽、hover、滚轮缩放——
  就必须**整体放弃 DSL**，掉回手写 `Application` + `Stage` + `Scene` + `MainView`。
  `ClickExample.kt` 424 行里绝大部分是这个样板，而它演示的只是一次点击；
- 掉回去的人**必然**要自己重新推导那一乘坐标换算（因为 `nodeScale` 是 private），
  而那一乘漏掉的症状是"点 A 命中 B，画面完全正常"——在 100% 缩放的机器上还一切正常。
  **这是本项目的核心陷阱，而 DSL 在这条路上没有护栏。**

**需要说清楚的边界**：菜单栏本身**能**通过 DSL 加
（`onScene { (it.root as BorderPane).top = menuBar }`——`createMainView()` 返回的正是那个
`BorderPane`，它就是 `Scene` 的根）。所以这不是硬墙，是**没有一等入口**。

**建议**：
1. **公开 `deviceScale(node)`**（本仓库的 demo 分支已在做，见
   `2026-09-24-jfgl-demo-design.md` §5）；
2. `JFGL` 加一个 `val canvas: Node` 属性。一行，消掉"挖场景图"这一步；
3. 更低优先级：DSL 的 `onScene` 文档里正面写出"菜单栏怎么加"（现在只说了 `overlay` 放控件）。

---

### P1-3 ｜生命周期所有权跨三层泄漏：`gc.disposeCharts()`

**现象**：`FXGLTransfer.kt:175` 在 `onDispose` 里调 `gc?.disposeCharts()`；
而 `Gc.kt:229` 的注释自己承认了这个问题的形状——

> **不在 `RenderBatch.dispose` 里调用**：那是下层，不认识上层的 `ChartRenderer`。

**后果**：桥接层必须知道"`Gc` 的下游还挂着一个持有 GL 资源的懒后端"。
将来再加一个（比如 ③ 若实施的降采样缓冲），这里就要再加一行；
**忘掉的症状是 GL 资源泄漏**——窗口正常关闭、不报任何错，只是显存不回。
这正是本项目最擅长制造的那类缺陷：静默、无报错、只在长时间运行后才显形。

**建议**：`RenderBatch` 上加一个 `Disposable` 子项列表：

```java
public void registerDisposable(Disposable d)   // 懒创建的后端在这里登记
// dispose() 里逆序释放全部登记项（下游先释放）
```

`Gc.charts` 懒创建时把 `ChartRenderer` 登记进去，`FXGLTransfer` 那一行删掉。
这是标准的 Composite 释放模式，改动小，但**消掉了一整类未来的静默泄漏**。

---

### P1-6 ｜★ 用户的渲染回调抛一次异常，应用就**永久冻屏且不报错**

**现象**（已复核源码）。`FXGLTransfer` 渲染事件的骨架是：

```kotlin
addOnRenderEvent {
    glClear(...)
    if (context != null && scaledWidth > 0 && scaledHeight > 0) {
        context.beginFrame(scaledWidth, scaledHeight)
        onFrameCallback?.invoke(context)      // ← 用户的绘制回调
        context.endFrame()                    // ← 这一行不是 finally
        resolvePendingPick(context)
    }
    onRenderCallback?.invoke()
}
```

`onFrameCallback` 与 `onRenderCallback` 都是**用户代码**，而它们外面没有任何 `try` / `finally`。

**后果链**：用户回调里抛任何异常 → 穿过 `onFrameCallback` → **`context.endFrame()` 整个被跳过**
→ `Gc.frameActive` 停在 `true`、`state.clearStack()` 与 `styleDepth = 0` 都不执行 →
下一帧 `beginFrame` 撞上 `check(!frameActive)` 又抛 → **此后每一帧都在同一处抛**。

于是最终状态是：**画面永久冻结在最后一帧、应用看起来"卡的"、没有任何错误信息**。
而异常的出口取决于 openglfx 的渲染循环怎么处理它——本项目一直在说"GL 线程上的异常是
静默吞掉的"，这条正是那个说法的来源之一。

**为什么这是本评审里最该修的一条**：

- **触发条件极低**。任何用户回调里的一次下标越界、一次 `require` 失败、甚至一次
  `NullPointerException`，都把应用变成一具会呼吸的尸体。用户回调是本库**唯一**给应用
  自由发挥的地方，而它没有护栏；
- **症状最强误导性**。"画面不动了"会被归因为 GPU 驱动、vsync、窗口管理器——**没有一个
  方向指向"你的回调第三分钟抛过一次"**。而且它不发生在抛异常的那一刻，
  是**从那一刻起永久如此**；
- **与项目的自我认知矛盾**。`Gc.beginFrame` / `endFrame` 的配对检查写得很认真、
  注释也解释了为什么"选择抛而不是安静地兜底"（"漏掉 restore 会让栈无限增长并污染
  后续每一帧，属于必须暴露的缺陷"）。**这个判断是对的，但它默认了 `endFrame` 会被调用**——
  而在今天这条链上，恰恰是"回调抛了"的那种情况让 `endFrame` 不被调用。

**建议**：把帧回调包起来，保证 `endFrame` 一定执行、并把异常**报出去**：

```kotlin
context.beginFrame(scaledWidth, scaledHeight)
try {
    onFrameCallback?.invoke(context)
} finally {
    context.endFrame()
}
```

**光加 `finally` 还不够**——`endFrame` 自己不抛了，但用户的异常仍然要有个出口。
建议照本项目一贯的做法把它变成**可观测的**：捕获后打印到 stderr（含栈）、
并计入一个 `renderErrors()` 计数器（与 `droppedClicks()` 同一个手法：
"丢弃既然是必然可达的一条路径，就不能是静默的"）。
**要不要顺带把帧循环继续下去**，是个需要定的策略——但"静默永久冻屏"不能是默认答案。

> 本条由 `DemoShapes` 的质量评审挖出：评审者在分析"`draw` 抛异常会怎样"时，
> 顺着调用链走到了 `endFrame` 被跳过这一步。它比我在派活时预设的那个后果
> （"样式栈少弹一层"）严重得多。

---

### P1-5 ｜`JFGL.onRender` 与 `FXGLTransfer.onRender` 同名而时机相反

**现象**（已复核源码）：

| 公开方法 | 实际转发到 | 时机 |
|---|---|---|
| `JFGL.onRender { gc -> }`（DSL） | `bridge.onFrame` （`JFGL.kt:271`） | `FXGLTransfer.kt:151`，**在 `endFrame()` 之前** |
| `FXGLTransfer.onRender { }`（桥接层） | 它自己 | `FXGLTransfer.kt:157`，**在 `endFrame()` 之后** |

两个同名方法，一个在帧**中**、一个在帧**末**。

**后果**（不是理论推演——本轮设计 demo 时真的踩了）：想"帧末、手里有 `Gc`、干点什么"
的应用会按名字找 `onRender`，在 DSL 上拿到的是 `endFrame` **之前**那个。而在那之后调用
`Gc.pick` / `Gc.pickRect` 会**恒返回空**——因为 `RenderBatch.beginFrame` 刻意把
`pickBufferValid` 置为 `false`（理由见下），只在 `endFrame` → `submit` 时才置回 `true`。

于是症状是**"框选永远选不中任何东西"**，而它读起来像业务逻辑的问题，不像时机问题。
**这是本项目头号敌人的教科书样本**：不报错、画面正常、代码看着对。

**顺带一条设计上是对的、但没写进公开文档的取舍**：`beginFrame` 拒绝把上一帧的 ID
当作本帧的答案（"不拿陈旧的 ID 去注册表里查——那会拾取到早已消失的对象"）。
这个取舍本身很正确，但它意味着**"帧内拾取"这条路根本不存在**，而这一点
只写在 `RenderBatch` 的私有注释里，公开文档（`Gc.pick` 的 KDoc）没提。

**建议**：

1. **给 `JFGL` 的那个方法改名**（`onFrame` 或 `onDraw`），或者让它转发到真正的帧末回调。
   改名的成本是一处，收益是消掉一个会误导所有 DSL 用户的同名陷阱；
2. 在 `Gc.pick` / `Gc.pickRect` 的 KDoc 里正面写出"**只能在 `endFrame` 之后调用；
   帧内调用恒返回空**"，并指向 `FXGLTransfer.onRender` 这个可用时机。
   现在那条约束只活在 `RenderBatch` 的私有字段注释里。

---

### P1-4 ｜`ChartRenderer` 的系列缓冲只增不减，没有回收接口

**现象**（已复核源码）：

```java
// ChartRenderer.java，draw() 的系列循环里
ctx.setCurrentBuffer(buffers.computeIfAbsent(series, s -> new SeriesBuffer(gl, s.data())));
ctx.setPickId(pickIds.computeIfAbsent(series, pickRegistry::register));
```

`buffers` 与 `pickIds` 都是按 **`Series` 对象身份**索引的 `IdentityHashMap`，
而它们**只在 `dispose()` 里被清空**（`ChartRenderer.java:510-513`）。
两次 `computeIfAbsent` 之间没有任何"这个系列本帧没出现"的检查或回收路径。

**后果**：调用方只要重建一次 `Chart`（或重建任何 `Series` 对象），
旧系列的 `SeriesBuffer`（GPU 缓冲）与拾取号就永久留在 map 里。
麻烦在于**"重建 Chart"是最自然的写法**——`Chart` 看起来是个纯计算对象
（装配数据与轴），没有任何迹象提示它被身份索引的 GL 资源绑着。
后果是两重泄漏：**显存不回**，以及**拾取号被吃掉**——而后者耗尽时
`PickRegistry` 抛的异常**落在 GL 线程上，在本项目是静默吞掉的**，
症状是"前几百帧完全正常，然后图表忽然不画了，没有任何报错"。

这是本项目最擅长制造、也最该防的那类缺陷：**静默、延迟、无报错、画面看起来正常**。

**建议**：`ChartRenderer` 加一个每帧调用的保留/回收入口。两种形态：

1. `retainSeries(Collection<Series> kept)` —— `draw` 结束时由调用方声明"本帧只保留这些"，
   其余的在 map 里删掉并 `dispose` 缓冲 + `unregister` 拾取号。语义直白、不易用错；
2. `releaseSeries(Series series)` —— 显式释放单个。灵活，但调用方容易忘（正是当前的处境）。

倾向 1：它把"漏掉一个系列"从**静默泄漏**变成**不可能**（本帧没声明就是没保留），
与本项目"用 API 形状消掉一类错误"的一贯做法一致（对比 `Gc.strokeOutline` 那个
"没有默认值的 `closed` 布尔量，正是为了避免闭合轮廓忘了传 true"）。

**顺带一条文档缺口**：`Chart` 的类文档只说"它不是场景图节点、顺序就是一切"，
**没有一个字**提示"同一个 `Chart`/`Series` 实例必须跨帧复用"。这条要么写进 KDoc，
要么由上面的回收接口让它变得无关紧要。

---

### P2-5 ｜`ClickVerifier` 的 `robotProbe` 泄漏会把**一处**环境问题放大成**八条**失败

**现象**（做 demo 的 Task 10 时，重跑校验器发现的）：

`ClickVerifier` 的「★ 真实鼠标点击（Robot）」一节依赖 `Robot` 把点击投到**屏幕坐标**上。
当机器被别的窗口盖住时（实测：用户在跑 Minecraft + 放视频），画布收不到点击。
**问题不在那条探针本身**——它有超时、也会报失败，这是对的。
问题在**它的清理**：首条 Robot 点击失败之后 `robotDone` 置了真，而 **`robotProbe` 没有清空**，
于是**后面 FIFO 与溢出两节的合成点击也被标成 `ROBOT`** ⇒ 一次环境问题变成 **8 条连锁失败**，
而其中 6 条**与 Robot 毫无关系**。

**后果**：读报告的人会以为"点击队列坏了"，而实际是"游戏窗口盖住了画布"。
**这正是本项目最在意的那类失真**：不是断言错了，是**断言之间的隔离被破坏了**——
一条失败污染了它下游的判定。

**建议**：`robotProbe` 在**每次** Robot 阶段开始前显式清空（或改成"Robot 段结束时无条件重置"），
让"Robot 不可用"只影响它自己那两条。
**判据**：改完之后，在机器被占用的条件下重跑，应当**只**看到 Robot 那两条红，
而 FIFO / 溢出那两节照常绿。

**顺带一条环境事实，值得写进 `CLAUDE.md` 的跑法**：**这台机器上 `ClickVerifier` 的 Robot 一节
在用户占用屏幕时必然红**——重跑它应当挑机器空闲的时候，**不是改代码能解决的**。

---

### P2-4 ｜图表标题左对齐，而轴标题居中——两种对齐方式并存且没有文档

**现象**（做 demo 的 Task 9 时由"种子 + 截图"逼出来的）：

- **图表标题**：`ChartDecorations.paint` 画在 `layout.titleRect().x`，即**带子的左边缘**；
- **轴标题**：走 `ChartLayout` 算好的居中值（`left + (band.width - textW) * 0.5f`）。

所以同一张图上，`标题` 靠左、`金额 (万元)` 居中。**两者都是"刻意的"，但没有一处文档说清
这个区别**——`Chart.title(...)` 的 KDoc 只说"空串表示不显示"，`ChartLayout` 也没提。

**后果**：任何按常识假设"图表标题居中"的人（包括本评审的作者——我在 demo 的验收表里
就是这么写的，被实测打回）都会得到一个错误的期望。**而"标题没居中"在画面上看起来
只是"排版有点随意"，不像一个功能没做。**

**建议**：二选一，**但必须选一个并写下来**：

1. **保持现状**（左对齐）→ 在 `Chart.title(...)` 与 `ChartLayout` 的 KDoc 里写明
   "标题左对齐于带子；轴标题居中"——**成本几乎为零，收益是消掉一个必然被踩的期望差**；
2. **让它居中** → 那是**改库行为**，而现有像素期望（`ChartVerifier` 里那些）会跟着倒，
   需要连带重算。**只有在确实想要居中时才走这条。**

本评审倾向 **1**：左对齐在图表里是个站得住的选择，而这条的真问题从来不是对齐方式，
**是没人说清它是什么**。

---

### P2-1 ｜`GPUFFT.java` 是零引用的死代码，且是活的陷阱

**现象**（已复核）：全仓对 `GPUFFT` 的**代码引用为 0**；
它只出现在 `FftKernel.java` 与 `FftVerifier.kt` 的**注释**里，作为反面教材被提及。

**后果**：`CLAUDE.md` 必须花一整节解释它为什么不能当参考实现
（`#version 430`、看起来完整的 Cooley-Tukey、**但从未成功运行过一次**）。
这份文档成本已经付了；**代码留着只是多一个陷阱**——
一个不知道这段历史的人（或者一个检索"FFT"的 AI）会先找到它。

**建议**：删除。历史在 git 里（`adbb313`／`adab313` 那条线）。
注释里那几处"反面教材"的论述**保留**——它们不依赖文件是否存在。

---

### P2-2 ｜平台绑定与发布流程未定

**现象**（已复核）：`jfgl-javafx/pom.xml` 把 JavaFX 声明了**两遍**，其中一遍带
`<classifier>win</classifier>`——即 Windows 专用。`CLAUDE.md` 也确认
"项目当前是 Windows 专用"，且 `java -jar bin/...jar` 仍可能失败。

**后果**：不致命，但"支持哪些平台"这条信息散落在 pom 与文档里，没有一处正面声明。

**建议**：在 `README` 里加一行明确的"支持平台：Windows（x64）+ 需要 GPU"。
要跨平台再谈 classifier 与原生库打包——**先把它写下来，而不是先做**。

---

### P2-3 ｜"已声明的降级"散落各处，没有索引

**现象**：`CLAUDE.md` 里至少有十条"这是刻意的，不是缺陷"：
旋转裁剪退化为包围盒、拾取不看 alpha 与透明度、文本热区比墨迹大一圈、
热区容差 4px、`Series.markerSize` 是半径而着色器收的是边长、频谱的 x 轴单位是 bin、
时间轴按 UTC 格式化、`Chart` 的轴标题默认关着、`drawChart` 不能带变换……
每一条**理由都写得很充分**，但**分散在各自的章节里**。

**后果**：新用户（或新 AI 会话）要读完整份 `CLAUDE.md` 才知道哪些"怪行为"是设计。
在那之前，每一条都会先被当成 bug 报一次。这份文档已经把最容易变成 issue 的东西
写下来了，只是**没有做检索入口**。

**建议**：`CLAUDE.md` 顶部加一节「已知降级一览」，
三列表格：**条目 / 一句话 / 钉着它的测试**。
成本约 20 行，收益是它把设计意图前置到第一次阅读。

---

## 4.5 与两个参考项目的对比（`chart-fx` / `fxcharts`）

JFGL 自述参考过 `D:\javaProject\javafx\chart-fx`（717 个 `.java`）与
`D:\javaProject\bingbaihanji\javaui\fxcharts`（275 个）。**这一节是读它们的一手源码后写的，
不是照抄 JFGL 文档里的转述**——而那个决定当场就还了本：**JFGL 文档对它们的转述有三处是错的**
（见 4.5.4）。

对比按七条轴做（渲染模型 / 数据与轴 / 扩展点 / 配色 / 性能策略 / 线程模型 / API 形状），
下面是**结论与可行动的条目**；完整的逐轴对照在实施时的报告中。

### 4.5.1 ★ 最重要的那条：**③-2 降采样立项时不要拿 chart-fx 当理由**

chart-fx 有**完整的降采样子系统**（`datareduction/` 5 个类，阈值默认 5 点、
像素距离默认 6px），所以"看看参考项目怎么做"必然得出"该做"。
**但逐行读下来它是有损的**：

```java
// DefaultDataReducer —— 缩减时把 min/max 塞进误差棒数组
xValues[count] = (int)(meanX/ncount);  yValues[count] = (int)(meanY/ncount);
yPointNegErrors[count] = maxY;  yPointPosErrors[count] = minY;
```

而**折线只读 `xValues`/`yValues`**（`ErrorDataSetRenderer` 里
`gc.strokePolyline(xValues, yValues, count)`）。

> **★★ 这一节初稿写错了一处，而且是写在本节最值钱的那个结论上——照实记下来。**
> 初稿写的是"**在默认的纯折线形态下**，一个 1 个样本宽的尖峰……**在画面上消失**，
> 而画面看起来完全正常"，并把"`ErrorStyle.NONE` 是默认"当作三条支撑证据之一。
> **实测那条是错的**：`AbstractErrorDataSetRendererParameter.java:40` 的默认值是
> **`ErrorStyle.ERRORCOMBO`（不是 `NONE`）**，而 `ERRORCOMBO` 分支会去画误差棒/误差面
> （`ErrorDataSetRenderer` 的 `switch (getErrorType())`，且 `drawErrorSurface` 读的正是
> `errorYNeg`/`errorYPos` 那两个数组）。
>
> ⇒ **"折线只读均值"成立**（机制那半是真的），**但"所以尖峰在画面上消失"未证、且已有反证**
> ——在默认配置下，尖峰至少还会以**误差面/误差棒**的形式出现，除非调用方显式设 `ErrorStyle.NONE`。
> **纠正来源**：实施这一节的修改时，实现者独立去核了 `ErrorStyle` 的默认值并报回来——
> **而这三条数字/事实错误，都是我直接照抄对比报告、没有自己验造成的**
> （另两条：`datareduction/` 是 **6 个 `.java`**（5 个缩减器 + `ReductionType` 枚举）不是"5 个类"；
> `ChartBits` 是 **24 个常量**不是 23，`sed -n '/^public enum ChartBits/,/^\s*;/p'` 可逐个数）。
> **这一条本身就是"转述不可信、要回到一手材料"的又一次实例**，只是这次犯错的是这份评审。

**所以那条行动项的适用面要收窄**（但它本身不变，两种情形下都成立）：
"**真做 ③-2 时必须保留每列 min–max、不许取均值或抽稀，且必须有一条「单样本尖峰不被吃掉」
的像素断言**"——**它防的是"折线画的是均值、而 min/max 在另一条消费路径上"这个结构**，
而不是"尖峰一定看不见"。**要立"尖峰会消失"这条规矩，得先补一条像素级复现。**

**可行动**：
1. ③-2 真做时，**必须保留每列 min–max，不许取均值或抽稀**，并且要有
   「**单样本尖峰不被吃掉**」的像素断言。否则会把 chart-fx 的缺陷原样抄进来。
2. `CLAUDE.md` 的「未实现 / 待办」里 ③-2 那条补一句：
   **参考项目的降采样是有损的，不能作为立项依据。**
3. 顺带记下 chart-fx 的 `RollingDataSet.add()` 每追加一段就把**全部保留点的 x 平移一次**
   （`shift(-lastLength)`）——那正是本项目用 uniform 滚动（`uScrollOffset`）规避掉的东西，
   **是 JFGL 对它的实质改进，不是照抄。**

### 4.5.2 `Gc` 那个"上帝类"的判断，两个参考项目都是它的**反面证据**

| | 主入口 | 规模与形状 |
|---|---|---|
| fxcharts | **没有统一入口** | `Axis` **2086 行**——**刻度分级 + 标签格式化 + 自带一个 Canvas 自己画轴**，全在一个类里；另有 23 个枚举值 + 29 个各自独立的 `Region` 子类 |
| chart-fx | `XYChart extends Chart` | `Chart` 961 行 + **21 个渲染器类** + CRTP 自递归泛型 + 1128 行的参数基类 + 824 行的 CSS 工厂 |
| JFGL | `Gc`（单一、像 canvas） | 1436 行、51 个公开成员 |

**它们的主入口都在不可逆地变胖，而且胖的方式恰好都是"模型与绘制同居一个类"。**
所以 §4 里那条"**不要拆 `Gc` 的公开 API**"**站得住**——拆成 `ShapeOps`/`TextOps`
会让调用方第一次用就困惑，而不拆的代价在两个参考项目里都有更严重的先例。

**但 `Gc` 的真问题不是它大**，是这两条：
1. **它混了两种性能模型**（本条**新增**，§4 里没记）：同一个入口上，
   `fillPath` 每帧重传 **21.6 MB**，`charts.draw` **永不重传**——**而 API 上看不出来**。
   这不是"模仿 canvas"本身有害，是**给了两个数量级不同的性能模型却不告诉用户**。
   **可行动**：`VertexWriter` 已经有 `overflowed()` / 帧中途 flush 这条可观测路径，
   在顶点数超阈值时**一次性**给个可观测信号（stderr 一行 + 计数器），
   并在 `Gc.strokePath` / `fillPath` 的 KDoc 顶部写明
   "**静态大几何请走 `charts`；本路径每帧全量重传**"。
   这不是加限制，是把一个**已经在库里、只是没人知道**的性能悬崖前置。
2. `chartPainter` 的跨层错位（见 P1-1）。

### 4.5.3 JFGL 有**唯一一处**参考项目连等价物都没有的工程机制

> chart-fx 的 `chartfx-dataset` 零 JavaFX 只是"模块 + 没人写"——**没有任何守卫**
> （无 `module-info.java`，靠 `Automatic-Module-Name`）。
> 而 JFGL 有 `ChartPackageIsolationTest` **递归遍历源码、按包名白名单**守卫。

**这是本项目在工程机制上最值得保留的东西，两个参考项目都拿不出来。**

另外两处对比结论：**扩展点**是"更清晰但有三处退化"——
更清晰的一面由 fxcharts 反证（它的枚举 + 分散 6 处 `switch` **已经真漏了 3 个图型**：
`BAR`/`NESTED_BAR`/`PARALLEL_COORDINATES` 零 case ⇒ **用户设了它，静默什么都不画**，
而 JFGL 的 `rendererFor` 末尾是**显式抛异常**）；
退化的一面是**不可扩展**（`rendererFor` 私有、无注册 API，用户想加自定义图型只能改库，
而 chart-fx 的 `getRenderers()` 是公开可改列表）与**没有共享基类 ⇒ 6 份重复的 GL 状态收尾**。
**配色**是三方里唯一做对的（另两个都逐像素算色；chart-fx 的缓存还建在**错误的粒度**上：
本该 256 级、实际每个浮点值一个 entry）——但 `toLut()` **目前零消费者**，
"换配色 = 换纹理"是**预期收益、尚未兑现**。

### 4.5.4 ★ JFGL 文档对参考项目的**三处错误转述**（都该改）

| # | JFGL 的说法 | 实际 | 性质 |
|---|---|---|---|
| 1 | `2026-09-20-jfgl-chart-framework.md` 称 chart-fx 有 `getXAxisId` / `getYAxisId`（按 id 选轴） | **`AxisId` 在 chart-fx 全仓 0 命中**；它的选轴是**方向 + 第一个匹配 + 对象身份**（`Chart.getFirstAxis(Orientation)`） | **硬错误** |
| 2 | `chart/AxisRange.java` / `Axis.java` 说"抄 chart-fx……轴只持有 `AxisRange`" | chart-fx 里"数据声明的域"叫 **`AxisDescription`**；而它的 **`AxisRange`** 是**轴的窗口**（min/max + axisLength + scale + tickUnit）。**实质对，名字反了** | 名字撞车 |
| 3 | `chart/DirtyRange.java` 与 `2026-09-20-jfgl-chart-framework-design.md` §5.1 说"chart-fx 的脏位是**图表级的一个全局标志**" | 实际是**每个 `DataSet` 自己的一张位掩码**（`io.fair_acc.dataset.events.ChartBits`，**24 个常量**，按**类别**：加/删/范围/名字/样式/元数据/置换…，位宽有 `> 32 → AssertionError` 钉死）。**"没有脏区间"这个实质结论完全成立**，但描述不准 | 稻草人 |

**第 3 条最该改**：把它描述成"一个全局布尔"，会让熟悉 chart-fx 的人**一眼看出转述不准、
从而怀疑整段论证**——哪怕结论是对的。
**改成**"它的脏位是**类别**（哪一类变了），不是**区间**（哪一段变了）"，
而且顺势给出一个**该抄没抄**的改进方向：
**`dirtyRange` 可以升级成"类别 + 区间"两级判据**——"只改了颜色"这类变更就根本不必去问区间。

### 4.5.5 这一节的诚实边界

- 两个参考项目**没有跑构建**，JFGL 也没跑；对比结论全部来自读源码。
- chart-fx 读了约 25 个文件的关键段（含代理），**`chartfx-math`（100+ 文件）、
  `chartfx-samples`、`plugins/`、`hexagon` / `marchingsquares` / `financial` 渲染器、
  所有测试都没读**；fxcharts 读了约 20 个文件（共 275），
  **`eu.hansolo.fx.geometry`（约 4000 行的自有几何库）没读**。
- **4.5.1 那条结论的强度：只有一半站得住，另一半已被反证**（见 4.5.1 里那个 ★★ 块）。
  **"均值写回几何、min/max 进误差数组、折线只读均值"这三条机制是从代码路径读出来的、
  且已逐行核实**；但**"所以在画面上看不见"没有像素证据，而且默认 `ErrorStyle.ERRORCOMBO`
  会把那两个数组画出来**——所以初稿里"三条独立证据"之一是错的。
  **据它立规矩（"不许取均值"）是对的，但依据是"结构"而不是"尖峰会消失"。**
- 轴 1 里"索引缓冲能省 1/3 顶点处理"是从 `VertexFormat` 24 字节 + 无 `drawElements`
  **推的，没有实测**——而 JFGL 自己的实测结论是"瓶颈在前端（图元装配/光栅化）"，
  那正是顶点数的线性函数。**这条与它自己的测量方向一致，值得实测一次**，但只列为候选、不是结论。

---

## 5. 我**没有**评审的（诚实边界）

写清楚这个比多写三条优点有用——下面的结论我都没有证据。

| 领域 | 为什么没评 |
|---|---|
| **性能** | 只采信了 `CLAUDE.md` 里的实测数字（可见点成本、GPU 时钟陷阱）。**没有自己测过**，不评价其充分性 |
| **着色器质量** | `Renderer/RenderBatch` 的 5 个 `#version 330 core`、`SeriesShaders` 的 10 个程序、`FftKernel` 的 compute，**一行 GLSL 都没读**。着色器正确性完全依赖现有校验器 |
| **`geom/` 的算法正确性** | `Tessellator`（耳切 + 孔洞桥接）、`StrokeGenerator`、`Flattener` **没读实现**，只看到测试名字与 `CLAUDE.md` 的记录。它们的正确性判据（含两条 `@Disabled` 的已知缺陷）我没有独立核对 |
| **纹理与图集** | `Texture` / `GlyphAtlas` / `SdfGenerator` 没读；`createTexture` 的 ARGB→RGBA 转换只从 `CLAUDE.md` 得知 |
| **构建配置的细节** | Java/Kotlin 混合编译那段（禁用 `default-compile` 再重绑定、`sourceDirs` 包含 `src/main/java`）只确认了它存在且被文档要求"不要清理"，没有验证它是否仍是最优解 |
| **第三方选型** | openglfx 之外没有比较别的 JavaFX+GL 互操作方案；`CLAUDE.md` 列的"声明了但没用到的依赖"（JOML、GLFW、logback、JNA…）我只采信了文档，没有逐个核对 |
| **测试覆盖的质量** | 370 条单测**没有逐条读过**。第 3.5 节夸的是"验证方法论"，不是"覆盖率"——两者不是一回事 |

**特别说明**：第 3 节那六条是"方法论层面"的肯定。
一个方法论正确但实现有 bug 的系统，仍然是坏的；
本评审**没有**对"实现是否正确"给出结论，那要靠跑校验器（`CLAUDE.md` 的
「怎么验证改动」一节已经给出了正确的方法）。

---

## 6. 处置优先级建议

按"性价比"排，不按严重度——P0 的两条恰好也是最便宜的。

| 序 | 动作 | 成本 | 收益 |
|---|---|---|---|
| 1 | `verify-all.sh` 聚合七个校验器的退出码 | 半小时 | 把"记得跑七个"变成"跑一个"；消掉最不可靠的人工依赖 |
| 2 | `Main.kt` 加参数分发 | 十分钟 | 消掉"改源码才能换示例" |
| 3 | 公开 `deviceScale(node)` | 十分钟 | 消掉本项目的核心陷阱在 DSL 路径上无护栏的问题（demo 分支已在做） |
| 4 | `JFGL` 加 `val canvas: Node` | 十分钟 | 消掉"从场景图挖画布" |
| 5 | `RenderBatch.registerDisposable` | 一两小时 | 消掉一整类未来的静默 GL 泄漏 |
| 5b | `ChartRenderer.retainSeries(...)` | 两三小时 | 把"重建 Chart 就泄漏"从**静默**变成不可能；顺带补 `Chart` 的"必须跨帧复用"文档 |
| 5c | `JFGL.onRender` 改名 + `Gc.pick` KDoc 写明"帧内恒返回空" | 半小时 | 消掉一个同名不同时机的陷阱；这一条本轮真的踩了（框选永远选不中） |
| **5a** | **`FXGLTransfer` 给帧回调加 `try/finally` + 异常计数器** | **半小时** | **本表里最该先做的一条**：现在用户回调抛一次异常就永久冻屏且不报错，而症状会被归因到 GPU/驱动上去（P1-6） |
| 6 | 最小 CI：`mvn -o clean test` | 一小时 | 370 条纯计算测试获得自动守卫 |
| 7 | 删 `GPUFFT.java` | 五分钟 | 移走一个活的陷阱 |
| 8 | `CLAUDE.md` 加「已知降级一览」 | 半小时 | 把设计意图前置，减少重复的"这看起来像 bug" |
| 9 | 抽 `chartrender/GcChartPainter` | 一两小时 | `Gc` 减 60 行，跨层错位归位 |
| 10 | README 声明支持平台 | 五分钟 | 信息前置 |

**不建议现在做的**：拆 `Gc` 的公开 API、给 DSL 加拖拽回调、给 `Gc` 加 `pickRectAsync`。
前两个会破坏"一个入口 / 像 canvas"的刻意取舍，第三个要先回答"异步框选的结果过期了怎么办"
——而这个问题目前没有需求逼着回答。

---

## 7. 一句话总结

**内核是可交付的，纪律是这个项目最值钱的资产；下一步该做的不是再加能力，
而是给这份纪律装上自动化的外壳——让它不依赖于"下一个接手的人也愿意手动跑完七个 main"。**
