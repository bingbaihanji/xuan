# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Xuan 是一个基于 **JavaFX + OpenGL** 的 2D 绘图框架。OpenGL 上下文由
[openglfx-lwjgl](https://github.com/husker-dev/openglfx) 的 `GLCanvas` 承载，`GLCanvas`
本身是 JavaFX 场景图中的一个 `Node`，因此 OpenGL 渲染结果直接嵌入 JavaFX 布局。

目标：做一套**用起来像 canvas**（立即模式、像素坐标、`fillRect`/`strokePath` 这类方法形状）
但**内部按最优方案实现**的 2D 绘图 API。注意"像 canvas"指的是 API 手感，不是实现——
不要以"JavaFX 是这么做的"作为设计理由，除非行为差异会让用户困惑。

语言分工：**Java 写几何与渲染热路径（`geom/`、`renderer/` 的顶点侧），Kotlin 写渲染门面与
JavaFX 胶水层**（`Gc`、`ViewTransform`、`FXGLTransfer`、DSL、示例）。代码注释和 Javadoc
一律使用中文。

模块依赖只允许向上：`xuan-core`（纯计算）→ `xuan-render-gl`（OpenGL 后端）→
`xuan-javafx`（场景图桥接与示例）。禁止将 JavaFX、LWJGL 或 OpenGL 依赖带回 `xuan-core`。

## 常用命令

```bash
mvn compile                      # 编译全部三个模块（Java 21 + Kotlin 21）
mvn -pl xuan-javafx -am compile  # 编译 JavaFX 模块及其依赖
mvn -o compile                   # 离线编译（依赖已缓存时可用）
mvn test                         # 运行测试
mvn -o clean test                # 干净重建 + 全量测试
mvn package                      # 构建全部模块
```

### ⚠️ 运行应用：必须用 `exec:exec`，不能用 `exec:java`

`mvn exec:java` 在本项目**不可用**：该插件的类加载器会让 openglfx 链接到另一份
`com.sun.prism.GraphicsPipeline`（其 `thePipeline` 静态字段永远为 null），启动即抛
`UnsupportedOperationException: Could not detect pipeline`。必须 fork 出独立 JVM：

```bash
# 运行示例（打开窗口，需手动关闭）
# 先从仓库根执行：mvn -o install -DskipTests
# 然后进入 xuan-javafx 目录执行：
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.xuan.MainKt"

# 运行像素校验器（自动关窗，退出码 0=通过 / 1=有断言失败）
# ★ -Dstdout.encoding=UTF-8 放在 -cp **之前**：不加的话中文断言全是乱码，见下。
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt"
```

> **⚠️ Windows 下必须带 `-Dstdout.encoding=UTF-8`。**
> JVM 的 `stdout.encoding` 默认取系统编码（本机实测是 **GBK**），而 `exec:exec`
> **不会**替你把它设成 UTF-8——于是八个校验器打印的中文断言（含**失败清单**）
> 全是乱码。**实测**：同一支 `ChartVerifier`，不加时整份输出不可读，加上之后逐行可读；
> 失败信息可读恰恰是这些校验器存在的一半理由（一个读不出原因的 FAIL 与没有断言差不多）。
> 它必须写在 `-Dexec.args` 的值里（即分给那个 fork 出来的 JVM），放在 `-cp` 之前。

> **⚠️ `-Dstderr.encoding=UTF-8` 是**另一个**开关，只写 stdout 那个不够。**
> 上面那条讲的是 `stdout.encoding`（默认 GBK，会让八个校验器的中文断言全乱）。
> 但 `stderr.encoding` 是**独立的**系统属性，默认同样是 GBK——而**诊断信息走的是 stderr**。
> 典型受害者是本仓库现有的自检：`ClickDslExample.kt` 的两处
> `System.err.println("[叠加层] 自检失败：…")`（中文），`XuanDemo.kt` 的四条
> ——「属性 … 不是能识别的真值」「两份自检开关的值不一致」「[自检] 失败 N 项，demo
> 不可信，退出」「★ 警告：Platform.exit() 之后 … 没跑完」——以及
> **`DemoShapeMath.kt` 的异常定位信息**
> `[自检] 在「$lastName」之后、下一条断言求值时抛异常：…`。
> **最后这条尤其要紧**：它正是下面那个乱码样本的出处（`simplify` 就在它的上一句），
> 而它存在的全部意义就是"断言崩了时能指到是哪一条"——读不出来时，
> 一次崩溃就退化成一句"自检失败"。
> **表现是**：程序行为完全正确（照常报错、照常以非 0 退出），只有**报告**不可读——
> 而"读不出原因"恰恰是这些自检存在的一半理由。
> 实测（2026-09-24）：只带 stdout 开关时该行是 `[�Լ�] �ڡ�simplify …`，
> 两个都带上之后逐字可读。所以跑法统一写成
> `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。

### 交互式 demo 的自检模式（`-Dxuan.demo.selftest=1`）

`example/demo/` 那个交互式 demo **没有像素校验器**（画面取决于用户点了哪儿，没有可断言的
判据），所以它自带一支**合成事件自检**——驱动窗口的是真事件处理器，但事件由程序合成：

```bash
cd xuan-javafx
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dxuan.demo.selftest=1 -cp %classpath com.bingbaihanji.xuan.example.demo.XuanDemoKt"
```

**退出码 0 要过两道闸门，两道都不可省**（一条命令里跑完）：

1. **`onInit` 里的纯计算自检**（`selfCheckShapeMath`，输出前缀 `[自检]`）——
   **12 条**（原 21 条；2026-09-28 删掉 demo 那份虚线绕法时，连带删了钉它的 9 条——
   那 9 条钉的是 `isUsableDashPattern`，而同一个死循环现在在库里被修掉并有自己的判据了），
   末行也打 `[自检] 全部通过`。它**与自检模式无关**，正常跑 demo 也执行；
   失败时打 `[自检] 失败 N 项，demo 不可信，退出` 并退 1，
   **不会**打「已评估 k/14」（那句话属于第二道闸门，别把它当成唯一的判据）。
   它崩在断言里时另有一行 stderr 定位（见上面 `stderr.encoding` 那条）。
2. **合成事件脚本**（输出前缀 `[自检-合成]`）——**14 条**，末行 `[自检-合成] 全部通过`。
   失败或超时（看门狗 60 秒 / 脉冲上限 900）退 1，并打 **已评估 k/14**——
   `k` 是"真的被求过值"的条数，不是"总共"（被静默跳过的断言比失败的更坏）。

它证的是**合成事件真能到达 `wireMouse` 那四个处理器**（PRESSED / DRAGGED / RELEASED /
**MOVED**——最后一个只有虚线预览用，而它是"没有键按下也要跟鼠标"的唯一输入源），
以及坐标换算、拾取往返、回调交付、状态更新这四件事；它**没有**证"真实鼠标事件能到达画布"
（合成事件绕过 JavaFX 拾取，那条由 `ClickVerifier` 的 `Robot` 探针管），也**没有**证画面对不对。

> **★ 2026-09-26：两点式图形（矩形 / 圆 / 椭圆 / 直线）从"拖拽"改成"点两下"**，
> 拾取选中从"左键单击"挪到**右键只按不拖**，新增 `Esc` 取消、虚线预览跟随鼠标、
> 以及**圆的尺规变更**（从"内切于拖拽框"改成"圆心 = 第一点、半径 = 到第二点的距离"）。
> 自检因此从 11 条改写成 **14 条**（①②③④ 由 `clickAt`/`clickRight` 驱动，
> 新增 ⑤ 圆的尺规 / ⑥ `Esc` / ⑦ 两点式上拖拽什么都不做）。
> `dragFromTo` 现在只剩一处用途：⑦ 那条**反面**断言。
> 术语与取舍理由见 `docs/superpowers/specs/2026-09-24-xuan-demo-design.md` §4.2。

> **★ JavaFX + GL 应用的退出路径：`exitProcess` 会跑关闭钩子，而钩子会与 JavaFX 自己的
> 关停并发碰 GL/D3D——实测撞出过原生崩溃。** 任何人写这类应用都会踩，与自检无关。
> **现象**：`Platform.exit()` 之后调 `System.exit(code)`（`exitProcess`），进程以
> **`0xC0000005`（ACCESS_VIOLATION）** 死掉，maven 报 `Process exited with an error:
> -1073741819`——**而这一次运行的所有断言都是绿的**。它把"全过"报成了非 0 退出，
> 正是本仓库最防的"报告与事实相反"。
> **机制（与代码事实吻合，但未见原生栈）**：此刻 JavaFX 线程正在 `stop()` 之后继续它自己的
> 关停（`Platform.exit()` 只是**开始**拆），而 `System.exit` 要跑的关闭钩子里有碰
> GL/JavaFX 的那些——两边**并发**拆同一批 D3D 资源。
> **证据强度照实说**：`exitProcess` 那条路径 **5 次里崩 1 次**；改成
> `Runtime.getRuntime().halt(code)` 之后**连跑 8 次全干净**（8/8 退出码 0、8/8 断言全过、
> 8/8 打出 dispose 已完成）。**这是"支持度足够但未证明"**——没有拿到 `hs_err_pid*.log`，
> 机制是从"钩子 + 关停并发"推出来的，不是从栈里读出来的。样本也不大（1/5 vs 0/8）。
> **做法**：退出前先把要自己释放的东西释放掉（这里等 `stop()` → `dispose()` 跑完），
> **然后用 `halt(code)` 落退出码**——退出码一样，但不跑钩子、也不引入新的并发。
> 本文件里 `XuanDemo` 的看门狗早就写着同一条理由（"`exitProcess` 会跑关闭钩子，
> 而钩子里再去碰 GL/JavaFX，就是把一次有报告的失败换成一个没报告的挂死"）；
> 这条把那个局部经验推广成了退出路径的通用写法。
> ⚠️ 若哪天要把它升级成"已证明"，取证的入口是**别让 `halt` 把现场抹掉**：
> 先复现一次崩溃并留下 `hs_err_pid*.log`（JVM 崩溃时默认会写，`halt` 那条路径看不到），
> 或者用 `-XX:+CreateCoredumpOnCrash`。**在拿到栈之前，别在文档里把它写成定论。**

> **★ 帧回调里的异常现在可观测了（架构评审 P1-6 / 建议 5a 已落地，工作区未提交）。**
> 评审的原话是"用户的渲染回调抛一次异常，应用就**永久冻屏且不报错**"，
> 建议（5a，它自称"本表里最该先做的一条"）是"**`try/finally` + 异常计数器**"。
> 这条以前写的是"**本期只记录、不做**"——**那已经过期**，落地的是：
> - `Gc` 新增 **`abortFrame()`**：`frameActive = false` + `writer.reset()` +
>   `state.clearStack()` + `styleDepth = 0`，把一帧中途崩掉的状态**恢复到能继续渲染**；
>   且第一句就是 `if (!frameActive) return`（可重入）。
> - `Gc.endFrame()` 里 `batch.submit(writer)` 包进 `try/catch`，**清理放在 `finally`**
>   ——所以提交抛异常时 `frameActive` 与 `save` 栈**照样归零**（这两件事以前一起
>   卡住，此后每帧都抛、画布永久冻结）。
> - `FXGLTransfer` 的 `addOnRenderEvent` 把 `onFrameCallback` / `endFrame()` /
>   `resolvePendingPick()` 整段包进 `try/catch`，捕获后调 `context.abortFrame()` 再
>   走 **`reportRenderFailure`**；`onRenderCallback` 与 `onDisposeCallback` 各自也包了。
> - 新增公开入口 **`FXGLTransfer.onError(callback)`**（回调在 **GL 渲染线程**上），
>   没设处理器时 `printStackTrace()`——**默认不再静默**。
>   `onDispose` 那条用 `finally` 保证 `disposeCharts()` → `RenderBatch.dispose()`
>   的释放链照样走完，回调抛出的异常**释放完之后**才报。
> - **异常计数器补上了，而且它同时解决了"刷屏"**：新增 `@Volatile renderFailureCount`
>   与 `renderFailureCount()`；`reportRenderFailure` **只在第 1 次与每 60 次**
>   打完整栈（约每秒一行），其余只累加计数。这一条是**必需的、不是锦上添花**：
>   帧回调里的失败会**每帧重演**（漏一个 `restore()` 就每帧都抛），
>   不节流的话控制台 60 段/秒刷同一段栈，把有用的第一段立刻冲走——
>   而本文件在零尺寸那个分支上早就写着同一条口径（"这里跳过而不是抛异常，
>   **否则渲染线程会每帧刷一次栈**"）。
>   `onError` 处理器**每次都会被调用**（它可能有自己的计数/上报），
>   但**它自己抛出的异常只报第一次**——否则处理器坏掉会变成新的刷屏源。
> - `onErrorCallback` 加了 `@Volatile`：它由 JavaFX 线程写、**GL 线程读**，
>   不加的话 JVM 允许 GL 线程一直读到 `null` ⇒ 处理器注册了却永不生效，
>   表现是"我设了 onError，它却还在刷栈"。
> **证据强度照实说**：这是照着代码读出来的，**没有任何断言或校验器盖着它**
> ——`abortFrame()` 的"恢复后还能继续渲染"没有被测过，"回调抛异常后画布不再冻结"
> 也没有一条端到端的断言。按本仓库的口径，这属于**已实现、未验证**。
> 要给它立一条，最自然的是合成事件那套：让 `onFrame` 在第 N 帧抛一次，
> 断言"第 N+1 帧照常出画、`onError` 收到的正是那个异常"。
> `fwidth`/`fwidth==0` 那类"画面上长得一样"的静默错误**仍然**只有校验器拦得住。

> **★ 一次被推翻的"间歇性缺陷"值得记下来，因为它的真因是"同一属性名下两份解析"。**
> 现象：`-Dxuan.demo.selftest=true` 时 ⑩⑪ 全倒（末尾探针恒 +0、身份恒 0），
> 而绘制与拾取号完全正常；一批运行里约 **1/17** 复发，一度被推断成
> "帧在文字那段抛异常被 openglfx 静默吞掉"。
> **真因**：`XuanDemo` 那一侧的判据被放宽成认 `1` **与** `true`，而 `DemoChart` 那一侧
> **没跟着改**（仍是 `== "1"`）——于是 `=true` 下**脚本照跑，而图表那三个观测一个都不写**。
> 那一批运行里**只有一个**是 `=true` 跑的，所以"1/17"是这么来的，**不是随机性**。
> **判据**：那次失败的日志里有 **6 行「等待超预算」**（⑩ 1 行 + ⑪ 5 行）——
> 而"异常被吞"的假说下**不可能有它们**：帧一停，`frameCount` 就不再涨，等待条件既不会成立、
> 也永远用不满帧预算，脚本只会挂在看门狗那一条上（实测：日志里 0 行「脚本超时」）。
> **收口**：解析收成 `internal fun selfTestEnabled()` 一个判定（两侧都用它），
> 并在启动时比一次两份开关的值（不一致就打印并退 1）。
> **可复用的教训**：**共用属性名 ≠ 共用判定**；跨文件的同名开关，判据也必须是同一份代码。

`exec-maven-plugin` **未在 `pom.xml` 中声明**，但 3.6.3 已缓存，`-o` 离线可用。
某些 shell 会把 `-D` 前缀吃掉（表现为 Maven 报 `Unknown lifecycle phase '.executable=java'`），
把每个 `-D...` 参数**加引号**可以规避。

另一个构造时机的坑：`FXGLTransfer` **不能**在 `Application.launch` 之前构造——
`GLInteropType.auto` 在类初始化时要向 Prism 询问渲染管线，工具包没起来就抛
`Could not detect pipeline`。所以入口必须是 JavaFX 应用，不能是普通 main。

### 测试

```
xuan-core/src/test/.../geom/       PathTest、FlattenerTest、TessellatorTest、
                                                TessellatorHoleTest、TessellatorRegressionTest、
                                                StrokeGeneratorTest、StrokeDashTest、
                                                GeomPackageIsolationTest
xuan-core/src/test/.../chart/      TickGeneratorTest、AxisTest、ArrayChartDataTest、
                                                RingChartDataTest、ChartDataConcurrencyTest、
                                                ColorMappingTest、ChartTest、
                                                ChartLayoutTest、ChartPackageIsolationTest
xuan-render-gl/src/test/.../renderer/   VertexFormatTest、VertexWriterTest、ViewTransformTest、
                                                PickRegistryTest、PickBufferTest
xuan-render-gl/src/test/.../gl/         FramebufferTest、LwjglGLAbstractionTest、
                                                FakeGLAbstractionGuardTest
xuan-render-gl/src/test/.../text/       SdfGeneratorTest、GlyphAtlasTest、FontFileTest、
                                                GlyphRasterizerTest、TextLayoutTest
xuan-render-gl/src/test/.../chartrender/ ChartRenderLayoutTest、SeriesBufferTest、
                                                SeriesUploadPlanTest、WindowRangeTest、
                                                BarLayoutTest
                                                （夹具类 ChartDataFixtures 本身没有测试）
xuan-render-gl/src/test/.../gpu/        FftWindowTest、FftKernelTest
```

（`...` 是 `java/com/bingbaihanji/xuan`。`xuan-javafx` 没有 surefire 测试——它的
`example/` 里那**九个**校验器是**手动跑的 main**，不是单测：
**七个像素校验器**（`Pipeline` / `Path` / `Pick` / `Click` / `Text` / `Chart` / `Axis`
——靠 `glReadPixels` 从**画布 FBO** 回读；`Axis` 是 2026-09-28 坐标系入库时加的）
+ `FftVerifier`（**不画任何东西**，读的是 SSBO，不是像素校验器）
+ `MsaaVerifier`（**要跑三次**：`msaa=0` / `4` / `-1`，由 `xuan-javafx/scripts/msaa-verify.sh` 比对）。
后两者的处境与那七个的区别见「抗锯齿」一节。）

当前 **422 个测试，0 失败**；**跳过数取决于有没有给字体**（2026-09-28 起本库不再自带）：
**给了 `-Dxuan.text.font=<路径>` ⇒ 2 跳过**（`TessellatorRegressionTest` 里两条 `@Disabled`）；
**没给 ⇒ 11 跳过**（多出的 9 条是 `FontFileTest` 5 + `GlyphRasterizerTest` 4，
**记成 `Skipped` 而不是消失**——理由见「发布」一节里那个 `@BeforeAll` 的坑）。单测命令：`mvn test -Dtest=类名`（跨模块加 `-pl 模块名`）。
分布：`geom/` 102、`renderer/` 107、`gl/` 13、`text/` 32、`chart/` 85、`chartrender/` 69、
`gpu/` 14（合计 422 = `xuan-core` 187 + `xuan-render-gl` 235；
路径命中那一步给 `geom/` 加了 10 条——`PathHitTest`，理由见「已实现 vs 未实现」里那一条；
虚线那一步又给 `geom/` 加了 1 条——`StrokeDashTest`
「每一项都低于阈值的模式不产生三角形而不是死循环」，理由见「已实现 vs 未实现」里那条；
里程碑 `0.1.0` 那一步又给 `chart/` 加了 3 条——`ChartInteractionTest` 的
「窗口外的样本不可命中」「窗口边界上的样本仍可命中」「提示框文本只构造一次」，
它们是 chart hover 那两条缺陷的定向断言：注入变异后**各自只有对应的那一条倒**；
子项目 A 抗锯齿那一步加了 15 条——`geom/` +12（`StrokeGenerator`
的 `aEdge`）、`renderer/` +3（`VertexFormatTest` +1 与 `VertexWriterTest` +2）；
平滑曲线那一步（`deca1a7`）给 `chartrender/` 加了 20 条
——`SmoothCurveTest` / `SeriesBufferTest` / `SeriesUploadPlanTest`；
图表交互那一步（工作区未提交）加了 3 条——`chart/` +2（新的 `ChartInteractionTest`：
「命中最近点并生成轴名称单位和系列名称」与「指针离开绘图区或遇到 NaN 时不命中」）、
`gl/` +1（`LwjglGLAbstractionTest.公开Texture封装也遵守ARGB到RGBA契约`，
它把 `Texture.argbToRgba` 提成包级可见以便脱离 GL 上下文测）。
**数这几个数请从 surefire 报告里数**（`*/target/surefire-reports/TEST-*.xml` 的
`tests=` 属性求和）：`mvn -q test` 把汇总行吃掉了，而 `README.md` 里那个
「357 个测试」是**另一份更陈旧的读数**（与本文档对不上，以本行为准）。
⚠️ **`renderer/` 里没有任何一条覆盖"`Gc.antialias` 的样式栈"**——全仓没有 `GcTest`，
`Gc` 只能靠校验器（见「测试」节开头与「抗锯齿」一节）。

`geom/`、`math/`、`util/`、`ViewTransform`、`text/{SdfGenerator, TextLayout}`、`chart/`、
`gpu/FftWindow`（窗系数与相干增益补偿，纯算术）都是纯计算、不依赖 GL 上下文，最适合写单测。
`gl/Framebuffer`、`renderer/PickBuffer`、`text/GlyphAtlas`、`chartrender/{WindowRange,
SeriesUploadPlan, ChartRenderLayout, SeriesBuffer}` 只依赖 `GLAbstraction` **接口**，
用 `src/test/.../gl/FakeGLAbstraction` 这个假实现也能零 GL 上下文单测。
`gpu/FftKernel` 也一样：**它的着色器源码是纯字符串运算**（`shaderSource()` 包级可见），
所以"共享内存数组的长度有没有与 `MAX_N` 各写一份""步长是不是又写回了字面量 1024"
这类只看字符串才发现的缺陷都有单测钉着。`text/{FontFile, GlyphRasterizer}` 依赖 stb 的本地库
（已实测能在 surefire 里加载）。`RenderBatch` 的着色器与 `Gc` 则必须靠校验器，
`chartrender/` 的着色器与实例属性配置必须靠 `ChartVerifier`，
`gpu/FftKernel` 的**输出数值**必须靠 `FftVerifier`（着色器能编译 ≠ 算得对）。

**`chart/` 的边界是机器强制的**：它连 `renderer/` 也不依赖，由
`ChartPackageIsolationTest` 递归遍历源码、按**包名白名单**守卫——白名单只放行
`chart/`、`math/`、`util/`，引用 `gl/`、`renderer/`、`text/`、`geom/` 中的任何一个
都会让测试失败。**注意 `chart/` 的白名单虽然放行 `math/` 与 `util/`，实际只用了后者**
（`util/Rect`，20 个源文件里只有 `ChartLayout` 一个 import 它；`math/` 仍然一处没用）。
守卫放行 ≠ 已经用了，也 ≠ 可以随便用。
同一个测试还断言 `RenderContext` 是**空接口**（0 方法 / 0 字段 / 0 嵌套类型）。

## 前置事实（已实测，不要重新猜）

**实际 GL 上下文是 4.6（compatibility profile），不是 3.3。**
开窗探针实测 `GL_VERSION = 4.6.0 NVIDIA 581.29`
（`GL_RENDERER = NVIDIA GeForce RTX 3060 Laptop GPU/PCIe/SSE2`，RTX 3060 Laptop），
`GL_MAJOR_VERSION = 4` / `GL_MINOR_VERSION = 6`，
`GL_SHADING_LANGUAGE_VERSION = 4.60 NVIDIA`。
同一支探针还**实际编译 + 链接 + dispatch 了一个最小 compute shader**
（`#version 430`，`local_size_x = 8`，SSBO 写入，输入 `1..8` 输出 `2..16`，
回读数值恰好两倍）。即：**计算着色器、SSBO、`imageStore`、shared memory 原子操作全部可用。**

> **是 compatibility，不是 core**：`GL_CONTEXT_PROFILE_MASK = 2`，即
> `GL_CONTEXT_COMPATIBILITY_PROFILE_BIT`（`GL_CONTEXT_CORE_PROFILE_BIT` 是 1），
> `GL_CONTEXT_FLAGS = 0`。写文档时别顺手写成 "4.6 core"——那是两个不同的上下文，
> 实测值就是 compatibility。

仓库里那 5 个 `#version 330 core` 着色器（都在 `renderer/RenderBatch.java`）能跑，
是因为 **4.6 向后兼容**，**不是因为上下文是 3.3**。**不要因为版本号写着 330 就以为
compute 用不了**——`gpu/GPUFFT.java` 就是被这个假设埋掉的（`#version 430`，
Cooley-Tukey radix-2 + SSBO，版本与上下文能力其实都够）。

> ⚠️ **但不要把它当成"现成可用的 FFT"**：当年的 `GpuFftVerifier`（提交 `adab313`，
> 后来被改造成今天的 `FftVerifier`）实测揭出 `GPUFFT.java` **三层缺陷**，
> 出厂那份**连编译都过不了**——它**从未成功运行过一次**。修好的实现是另外写的
> `gpu/FftKernel.java`（见「未实现 / 待办」里的那一条）。
> **"能编译 / 能 dispatch"与"输出对"是两件事**——这条曾经被写反过：
> 文档里一度说它"一直可用，只是没人引用"。

先前的 `CLAUDE.md` 里**没有任何一处写过上下文是几**——唯一沾边的 "3.3" 是架构图里
`LWJGL 3.3.6`，那是**库**的版本。这一节就是为了补上这个缺口。

## 架构

### 单一批处理管线

```
L3  DSL / 门面      com.bingbaihanji.xuan.dsl.Xuan、renderer.Gc      用户 API
L2  提交            renderer.RenderBatch                              着色器 / VAO / draw call
L1  CPU 顶点侧      renderer.VertexWriter / VertexFormat / DrawCommand
                    renderer.ViewTransform                            变换与裁剪（无 GL）
L0  几何            geom.Path / Flattener / Tessellator / StrokeGenerator   纯计算，零 GL 依赖
    文本            text.SdfGenerator / text.TextLayout                     纯计算；
                    text.FontFile（stb）/ text.GlyphAtlas（R8 图集）        依赖 stb 与 GL
    图表            chart.*                                          纯计算，零 GL 依赖
    图表后端        chartrender.*                                    实例化绘制，依赖 GL
    GL 抽象         gl.*                                             LWJGL 3.3.6 + openglfx
```

**`geom/` 对 `gl/` 零依赖**，由 `GeomPackageIsolationTest` 强制。

**`chart/` 对 `gl/`、`renderer/`、`text/`、`geom/` 零依赖**，由 `ChartPackageIsolationTest`
强制。① 与渲染后端（②）的接缝只有两个类型：`chart/RenderContext`（空接口，② 定义
子接口扩展它）与 `chart/SeriesRenderer`（纯函数：数据 + 轴 → 顶点）。

### 启动链路

```
Main.kt                     设置 prism.* 系统属性
  └─ PipelineExample.main   xuan { ... }
       └─ XuanApplication   JavaFX Application，搭窗口
            └─ FXGLTransfer GLCanvas 的 GL 回调里创建 RenderBatch + Gc
                 └─ onFrame { gc -> ... }   每帧绘制回调
```

### 绘制模型

绘制调用（`fillRect`、`fillCircle`、`strokePath` …）**不做任何 GL 工作**：它们把三角形
顶点追加进一块直接 `ByteBuffer`。每帧结束时 `Gc.endFrame()` 一次性提交，按少量 draw call 重放。

- **顶点格式 32 字节**（`VertexFormat`）：`vec2 pos`(0) + `vec2 uv`(8) +
  `vec4 ubyte normalized 预乘色`(16) + `uint id`(20) + `vec2 aEdge`(24)，stride 32。
  `aEdge` 是**抗锯齿边距**：`x` **横向**（到中心线的有符号距离，±1 = 两条真实外缘）、
  `y` **沿向**（到最近端帽的距离，0 = 端帽线、带外为负）。**填充与文本两个分量都写 0**
  ——它们靠 `fwidth == 0`（而不是靠沿向的符号）被片元认出来。见「抗锯齿」一节。
- **不用索引缓冲**：每个三角形 3 个顶点，`glDrawArrays` 而非 `drawElements`。
- **只合并相邻且同状态的绘制**。绘制顺序即 2D 的 z 序，**绝不重排**。
- **变换在 CPU 侧烘焙进顶点**，因此改变换不会打断合批。
- **预乘 alpha**，混合用 `GL_ONE` / `GL_ONE_MINUS_SRC_ALPHA`。
- **裁剪只用 `glScissor`**（矩形）；任意路径裁剪不在范围内。
  **推论（已声明的降级，不是缺陷）**：变换含**旋转**时，`clipRect` 实际生效的是旋转后
  矩形的**轴对齐包围盒**，不是旋转矩形本身——底层手段按定义只能是轴对齐矩形，旋转裁剪要
  模板缓冲或着色器遮罩，两者都没有。后果是**裁少了**（旋转矩形之外、包围盒之内的内容照样
  画、照样可拾取，而拾取用的是同一个矩形，两者不会互相矛盾），但方向单向：包围盒恒包含
  旋转矩形，**不会吞掉**本该显示的内容。**不要试图"修好"它**（那要引入第二种裁剪机制，
  属于新特性）。只含平移与轴对齐缩放时包围盒与矩形重合，没有降级。
  数值由 `ViewTransformTest.rotatedClipRectBecomesAxisAlignedBoundingBox` 钉着，
  方向由 `ViewTransformTest.旋转裁剪的包围盒只多不少` 钉着。
- `id` 属性（location 3）用于 GPU 拾取，见「拾取」一节。

### 坐标与单位约定

| 项目 | 约定 |
|------|------|
| 坐标系 | 像素，原点**左上角**，**y 向下** |
| 与设备像素的关系 | 用户坐标 **1:1** 映射到设备像素 |
| 旋转单位 | **度**（`Gc.rotate(degrees)`），正值在屏幕上是**顺时针** |
| 帧缓冲尺寸 | 见 `Gc.width` / `Gc.height` |

**高 DPI 的坑**：绘制区尺寸受系统缩放影响，**不等于**创建窗口时声明的逻辑尺寸。
125% 缩放下，`width = 800` 的窗口实际帧缓冲是 **988×738** 设备像素，画到 `x = 800`
只覆盖约 81% 宽度。需要铺满时用 `Gc.width` / `Gc.height`。

### 拾取

`gc.pickId = n` 给后续图元打标，`gc.pickable(n) { ... }` 是它的作用域版本（等价于
`save/pickId/restore`，块内的变换与裁剪改动也会回滚）。`gc.pick(x, y)` / `pickRect` 查询。

- **ID 0 表示不参与拾取**，也是「什么都没命中」的返回值。注册表 `pickRegistry`
  分配的 ID 从 1 开始，永不返回 0。
- **拾取只由几何决定，与颜色和透明度无关**：`globalAlpha = 0` 的图元照样能命中。
  图表的「隐形热区」（比数据点大一圈的透明矩形）就是靠这个行为。**这是刻意保留的，
  不要"顺手修好"它**——有测试钉着。
- **裁剪生效**：被 `clipRect` 裁掉的部分不可拾取，与画面一致。
- **只返回最上层**：重叠时后画的赢。要"全部重叠对象"需要逐对象多趟渲染，不在范围内。
- **组件在 JavaFX 线程响应鼠标事件时用 `FXGLTransfer` 的那几个入口**，不要直接调 `Gc.pick`
  ——那是跨线程 GL 调用，崩得毫无规律。它们都使用双 PBO + fence，通常下一帧回调，
  fence 未完成时不等待。**分两条语义，别接错**：
  - `pickAsync`（`pickAsyncAtNode`）：**最新覆盖旧的**，给 hover / 拖拽这类连续量；
  - `clickAsync`（`clickAsyncAtNode` / `onClick`）：**有界 FIFO、按序交付**，给**点击**。
    把点击接在 `pickAsync` 上会**静默丢点击**（实测「点一下、几微秒后移动鼠标」时
    8 次真实点击 0 次交付——见「怎么验证改动」一节）。
  - 坐标换算（局部 → 设备像素）由 `*AtNode` 与 `onClick` 替你做完；**自己调 `pickAsync`
    就必须自己乘窗口缩放**，漏乘的表现是"点 A 命中 B"而画面完全正常。
- 注册发生在**数据变化时而非每帧**；不再用的对象要 `unregister`，否则一直被强引用着。

### 文本

`gc.fontSize = 32f` 设字号（状态字段，进 save/restore 栈），
`gc.drawText(text, x, y)` 绘制并返回推进宽度，`gc.measureText(text)` 只量不画。

- **`(x, y)` 是基线的起点，不是文本框左上角。** `y` 是文字**基线**所在的像素行。
  选基线是因为只有它是排版的稳定参照——刻度文字沿轴线对齐靠的就是它；
  当成左上角的话画面"只是位置偏了一点"，最难查。**这条最容易被调用方猜错**。
- **文本是又一类普通图元**：走现有的批处理管线，因此裁剪、z 序、合批、GPU 拾取
  全部自动成立。连续的一段文本通常合并成一条 draw call。
- **任意缩放清晰**：字形只在 48px em 下光栅化一次（`GlyphRasterizer.EM_SIZE`），
  之后由距离场在屏幕空间重算边缘。改 `fontSize` 不触发任何重新光栅化——
  字形按字形缓存（图集的 key 是字形索引），不按 (字形, 字号)。
- **缺字不跳过**：字体里没有的码点画成 `.notdef`（豆腐块）。静默跳过会让人
  以为排版出了 bug。
- **文本的可拾取范围比墨迹大一圈**：ID pass 不看 alpha，而文本的四边形覆盖的是
  整个 SDF 位图矩形（含四周各 `SdfGenerator.SPREAD` = 8 像素的外扩）。与
  "全透明图元仍可拾取"同类，**是刻意的，有测试钉着，不要当成 bug 修**。
- ★ **字体：本库不再自带（2026-09-28 移除），由调用方在构造时给**。
  `xuan { font = File(…) }` 或 `FXGLTransfer(font = FontFile.load(…))`。
  **没给字体时 `drawText` / `measureText` 抛 `IllegalStateException`**，消息里写着怎么补
  ——**不是静默不画**（静默不画与“这一帧在文字那段抛了”在画面上逐像素相同）。
  为什么移除：原先那份 `simhei.ttf` 与系统字体目录里那份 **逐字节相同**，
  是微软/中易的专有字体，与 MIT 声明冲突。
  选字体的两条硬约束（优先 TTF、避开可变字体）与“哪些验收要字体”见
  `xuan-render-gl/src/main/resources/fonts/README.md`。
- 本期**不做**字距/连字/bidi、多行与对齐、富文本、多字体回退、MSDF。这些是刻意
  不做，不是漏了。

### 图表

图表框架（子项目 D-①）在 `chart/` 下，**纯计算、零 GL 依赖**：数据容器（`ArrayChartData`
静态 / `RingChartData` 流式）、轴与刻度（`Axis` / `TickGenerator` / `AxisType`）、
配色 LUT（`ColorMapping`）、装配（`Chart` / `Layer` / `Series` / `ChartType`）、
**装配与布局**（`ChartLayout` 把外框切成标题带 / 图例带 / 绘图区，
`ChartInsets` / `ChartSide` / `ChartTextMetrics`）。

GPU 绘制后端（子项目 D-②）在 `chartrender/` 下，**已完成**：`ChartRenderer`（入口，
经 `Gc.charts` 懒创建）、`LineSeriesRenderer` / `ScatterSeriesRenderer` /
`StepSeriesRenderer` / `AreaSeriesRenderer` / `BarSeriesRenderer` / `SpectrumSeriesRenderer`
（折线、散点、阶梯、面积、柱状、频谱，各自两个 pass：颜色的与 ID 的）、
`SeriesBuffer`（每系列一块 GPU 常驻缓冲）、`BarLayout`（柱宽与柱心偏移，纯算术可单测）、
`ChartPainter` / `ChartDecorations`（标题与图例那支笔）、
`SeriesShaders`（GLSL：`{折线, 散点, 阶梯, 面积, 柱状} × {绘制, 拾取}`，共 10 个程序）。
**② 与 ① 的接缝只有两个类型**：`chart/RenderContext`（空接口，② 用
`chartrender/GLRenderContext` 扩展它）与 `chart/SeriesRenderer`（纯函数：数据 + 轴 → 顶点）。

三块分解：**① 图表类框架（已完成，`chart/`）→ ② GPU 绘制后端（已完成，`chartrender/`）
→ ③ GPU 计算。** **分界判据是"能不能脱离 GL 上下文跑测试"**——① 里每一个类都能，
② 里的一个都不能，`chart/` 的边界正是这么画出来的（也是 `ChartPackageIsolationTest`
在守的那条线）。

**`chartrender/` 与 `chart/` 是兄弟包，不是子包**（`chart/` 只放能单测的纯计算，
`chartrender/` 放必须挂在 GL 线程上的绘制后端）。`ChartPackageIsolationTest` **递归**
遍历 `chart/` 整棵子树、按**包名白名单**（只放行 `chart/`、`math/`、`util/`）守卫——
把 ② 的任何一个类放进 `chart/` 下都会让它立刻失败。

```java
// 静态数据：一次性给出，之后整体替换
ArrayChartData data = new ArrayChartData(
        new AxisRange[]{new AxisRange(0, 10, "时间", "s"), new AxisRange(-1, 1, "电压", "V")},
        new double[][]{{0, 1, 2, 3}, {0.1, -0.2, 0.3, 0.0}});

Axis x = new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(800);
Axis y = new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(600);

Chart chart = new Chart(x, y);
chart.addLayer("主").add(new Series("电压", data, ChartType.LINE).color(0xFF00FF00));

Tick[] ticks = x.ticks();          // 主/中/次三级刻度，位置已经装配好
```

```kotlin
// ② 画出来：每帧在 GL 线程上，z 序是「网格 → 数据 → 标注」（与 Gc.charts 的文档一致）
gc.beginFrame(gc.width, gc.height)                     // xuan { } 的 onRender 已代为调用
gc.fillRect(plot.x, plot.y, plot.width, plot.height)   // 绘图区底色（普通 Gc 图元）
// ……网格与坐标轴……
gc.flush()                                             // ★ 网格落定
gc.charts.draw(chart, plot, gc.width, gc.height)       // ★ 数据系列（当场就画）
// ……刻度文字等标注：画在数据之上……
gc.endFrame()
```

- **脏区间是一等公民**：`ChartData.dirtyRange(sinceRevision)` 返回 `[firstDirty, lastDirty)`。
  `revision` 不变时报空（`DirtyRange.EMPTY`）——静态数据一次上传后**永不重传**；
  流式数据只报"新加了 N 个"。这是 ② 能做到"GPU 常驻 + 增量上传"的前提，**不是可选优化**。
- **流式数据是 SPSC 环形缓冲**：`RingChartData` 只允许**一个写者**（采集线程）。
  **若数据源改成网络/串口回调**（回调线程可能是 IO 线程池里的任意一个），
  **单生产者前提就不成立，整个无锁设计必须换掉。**
- **缺口用 NaN 表示**：窗口之外的 `value()` 返回 `NaN`（即 `RingChartData.GAP`），
  与"传感器自己吐的 NaN"是同一种东西。渲染器只需要一条规则——**遇到 NaN 就断开折线**。
  不提供也不该提供 `isGap(index)`。
  **缺口不能连过去**——不插标记的话波形会拉一条直线穿过缺口，**那条直线是假的**：
  它显示了一个不存在的信号，比不显示更糟，而且看起来完全正常。
- **轴不持有数据**：范围由数据自己声明（`AxisRange`），轴只是显示窗口 + 换算器，
  于是多 Y 轴是自然结果。退化范围与对数轴上的 ≤0 值都被稳定化（`withMinimumSpan()` /
  `withPositiveMin()`，全项目唯一的一份），**不会产生 NaN**。
- **刻度用 double 算术，不用 `BigDecimal`**（fxcharts 用它是反面教材）。
  三级刻度的包含关系体现在**格**上：主刻度的值都落在中刻度的格上，中刻度的值都落在
  次刻度的格上。**一个值只发射一次**（取最粗的级别），别去列表里数重复项。
- **时间轴标签按 UTC 格式化**（`AxisType.TIME` 的值是 Unix 纪元秒，
  `TickGenerator` 的 formatter 全部 `.withZone(ZoneOffset.UTC)` + `Locale.ROOT`）。
  要显示本地时间请在应用层转换——① 不读系统时区，否则同一段代码在不同机器上给出不同结果。
- **配色归一化成 1×256 LUT**（`ColorMapping.toLut()` 返回 `byte[1024]`，RGBA）。
  热力图换配色 = 换一张纹理，与数据量无关。

#### 坐标系：网格 / 轴线 / 箭头 / 刻度（2026-09-28）

**网格、坐标轴、箭头、刻度线、刻度文字现在由库画**，配置在 `chart/AxisStyle`
（record + 逐字段 wither），经 `Chart.axisStyle(...)` 装配。在这之前，
每一个用图表的应用都要自己抄一遍这段（`Main.java` 一份约 70 行、`XuanDemo` 另一份
约 40 行，写法还不一样），而且**已经抄错过**：`Main.java` 从没声明过
`tickLabelReserve`，于是它的刻度文字一直画在没留过位置的绘图区之外。

- ★ **`visible` 默认 `false`，这是承重约束不是偏好。** 开了轴绘图区就要让出带子，
  而 `ChartVerifier` 现有 168 条像素断言**全部**建立在"不设装饰时绘图区 = 外框"上。
  这条由**两个方向**钉着：默认关时那 168 条一条不动（实测 168 PASS / 0 FAIL），
  而把默认值改成 `true` ⇒ **10 条现有断言倒**（其中一条直接报"3502 px 不同——
  `ChartLayout` 在没有装饰时也动了绘图区"）。
- **`ChartLayout` 里的刻度预留改成"二选一"**：关着用调用方声明的
  `chart.tickLabelReserve(...)`（**既有行为一字不变**）；开着用库自己算的
  `tickLength + 字号 × LINE_HEIGHT_FACTOR`，**调用方声明的那个被忽略**。
  选**覆盖**而不是相加/取大，是因为后两者会让"绘图区到底多大"有两个来源，
  而绘图区算错只表现为"图小了一圈"、没有任何报错。
  ⚠️ **代价照实说**：y 轴上很宽的文字（`1000000`）会在预留带边缘被切断——
  与"装饰裁到带子里、不折行"同一条取舍，**看得见**。要更多空间请加 `Chart.padding`。
  按实际字宽算是不行的：`Axis.ticks()` 依赖 `displayLength`，而那个来自本函数算出的
  绘图区——**循环**。所以用与字体无关的可预测口径（与 `LINE_HEIGHT_FACTOR` 那条同源）。
- **`ChartAxes` 只开一层 `begin(frame)`**（不像 `ChartDecorations` 每个带子各开一层）：
  轴与刻度跨越绘图区边界、文字在绘图区外，没有"一个带子装得下"的矩形。
- **坐标系画在数据系列之前**，而刻度文字在绘图区**之外**——数据被 `glScissor`
  裁在绘图区之内，**盖不到它**。所以应用层不再需要"网格 → flush → 数据 → 刻度"
  那个两趟写法，`DemoChart` 的那句"顺序是硬约束"随之作废（只剩"背景要 flush 在
  `drawChart` 之前"这一条）。
- **网格线只画严格在绘图区内部的主刻度**（`0 < position < displayLength`），
  两个端点上的与坐标轴重合。⚠️ 这**不是**"跳过 `value == 0`"（应用层的常见写法）——
  那样会把"y 窗口跨过 0 时中间那条零线"也吃掉。
- **坐标映射不依赖调用方守约**：`ChartAxes` 用 `tick.position() / displayLength × 绘图区边长`，
  而**不是**把 `position` 当像素直接加。后者只在调用方把 `setDisplayLength` 设成
  绘图区尺寸时才对（那是 `ChartRenderLayout` 写明的调用方义务）；前者换一种算法就绕开了它。
- **验收**：新增第九个校验器 **`AxisVerifier`**（11 条，退出码 0/1）。
  它证的：默认关时三个轴色**一个像素都没有**（纯增量）；开轴后**网格竖线数 ==
  内部主 x 刻度数、横线数 == 内部主 y 刻度数**（两个数都从**同一份 `ticks()`** 算出来，
  不是写死的）；箭头在轴端**上方**（轴线本身伸不到那里）；刻度文字落在预留带里；
  预留两个方向都恰好 21.8。
  **变异**：让 `ChartAxes.paint` 直接 return ⇒ **恰好那 4 条像素断言倒**。
  ⚠️ **写这个校验器时我自己踩了两个坑，都表现为"读数恒为 0"**（四条本来通过的断言
  被报成失败）：① `glReadPixels` 行序自下而上，行号忘了翻；
  ② `rgbAt` 返回 24 位 RGB 而常量是 ARGB，**比较两边口径不同**。
  第二个只有靠"把画面里到底有什么颜色直接印出来"才看得出来——所以
  `AxisVerifier` 里那两行颜色直方图**是刻意留着的**，见它的注释。

#### 路径命中判定（`isPointInPath` / `isPointInStroke`，2026-09-28）

`Gc` 上加了两个 CPU 侧的命中判定，规格在
`docs/superpowers/specs/2026-09-28-xuan-path-hit-design.md`：

```kotlin
gc.beginPath(); gc.moveTo(…); gc.fillPath()
gc.isPointInPath(x, y)      // 在填充区域里？
gc.isPointInStroke(x, y)    // 在描边上？
```

- **(x, y) 是设备像素**（**Canvas 语义**：点不受变换影响、路径受）。与拾取同口径。
- 针对**当前路径**，无需 `pickId`、不走 GPU、**任意线程**可调。`fillPath` 之后路径仍在。
- **`isPointInPath` 用交叉计数（奇偶规则）**，不是复用 `Tessellator`。理由：Xuan 的填充
  是"按包含关系定洞"，而对良构路径（`Tessellator` 写明的前提：各轮廓是简单多边形）
  **嵌套深度奇偶 ≡ 交叉计数奇偶**，两者恒等；而走三角化有前提耦合、每次查询还要跑一遍耳切。
- ★ **`isPointInStroke` 复用 `StrokeGenerator` 本身**，不抄接头几何：把平坦化后的点按
  **与 `strokeOutline` 完全相同的参数**（只有 `capExtension` 取 0，那是渲染期的 AA 余量）
  喂进去，取它产出的三角形，"点在任一三角形里"即命中（三角形互相重叠，取**并集**）。
  抄一遍等于制造"同一条尺规的两份实现"，而本仓库为漂移吃过亏。
- ★ **实现时踩的坑**：`state.transformX/Y` 给的是 **NDC 不是设备像素**
  （基矩阵是 `translate(-1,1) × scale(2/W, -2/H)`）。绘制那条路看不出这一点，
  因为 GL 的视口会把 NDC 映成像素——**只有做命中判定才需要那个逆映射**。
  症状是**判定恒为 false**（几何被算进 [-1,1] 的小方块，而查询点是三位数的像素坐标）。
  实测：x=200 变换后是 -0.595 = 200/988*2-1。修法是 `ndcToDeviceX/Y` 两个辅助。
- **与 GPU 拾取的四处差别**（会给出不同答案，**不是缺陷，但必须知道**）：
  拾取要注册过的对象、必须 GL 线程、**受 `clipRect` 影响**、像素精确；
  本判定问当前路径、任意线程、**不看裁剪**、解析判定。**AA 开启时它比拾取窄约 1 像素**
  （拾取的 ID pass 复用加宽过的几何，那是已声明的行为）。
- **边界上的点没有约定**（`PathHit` 的类文档写明了）：浮点比较在边界上本来就没有稳定答案，
  硬定一个只会给出一种"看起来确定"的行为。要用在边界上时调用方自己留余量。
- 验收：`PathHitTest` 10 条（纯计算）+ `PathVerifier` 变体 ⑨（与画面逐点对照，见
  「怎么验证改动」一节）。**变异**（`MITER`→`BEVEL`）⇒ 恰好尖角判别点那条倒。

#### 绘制后端（②，`chartrender/`）

- **GPU 里存的是数值，不是屏幕坐标。** 位置在顶点着色器里算
  （`SeriesShaders`：`uPlotRect` / `uValueRange` / `uPxPerSample` …），
  于是**滚动、缩放、自动量程、窗口尺寸变化全都是改 uniform，零重传**。
  这是 ② 的全部性能前提。
- **每点 4 字节**：`SeriesBuffer` 只存 y（`float32`），x 由"样本在缓冲里的位置"
  隐含给出（等距采样），所以不占缓冲。
- **线段的两端靠"同一个 VBO、两个不同的字节偏移"的两个实例属性拿到**：
  `aY0` 偏移 0、`aY1` 偏移 4（步长都是 4，由 `baseInstance` 挪到环里正确那一段）。
  于是同一个 y 只存一次。散点只配一个属性 `aY`——点自己就是完整的，没有"第二端"。
  偏移写成 0（两个属性指向同一个 y）会让每个线段退化成**水平小横线**，
  而线条看起来仍然连贯。
- **缓冲比环容量多留一个 float，那个位置有确切语义，不是垃圾**：物理槽位
  `capacity` 就是槽位 0（环的本质），所以"最后一个槽位上的那个实例"的第二端
  必须读到**槽位 0 的值**。每写一次槽位 0 就同步一次那个余量
  （`SeriesUploadPlan.mirrorSourceIndex()`）。不写它，跨环绕点的一条曲线会多出
  一段**从正常值掉到 0 的斜线**——不报错、不是乱码，看着还挺像一条信号。
- **必须用 `glDrawArraysInstancedBaseInstance`**（`GLAbstraction` 里唯一一处
  `GL42` 调用），不能用普通的 `drawArraysInstanced`：实例属性按 `gl_InstanceID` 取，
  而它**每次从 0 开始**（`baseInstance` 不影响它，只影响属性取哪一份数据）。
  可见窗口跨过环绕点时 `WindowRange` 会切成两段、第二段的槽位从 0 开始——
  少了 `baseInstance`，第一段会取到环里别的槽位的数据，**线照样平滑、值全错**。
- **数据系列是当场就画的**（instanced draw call，不攒进 `RenderBatch` 的顶点缓冲），
  所以"网格 → 数据 → 标注"这种夹心 z 序要靠 **`Gc.flush()`**（帧内提交点）；
  也因此 `LineSeriesRenderer` / `ScatterSeriesRenderer` 自己负责进出时的 GL 状态
  （`glScissor` 的启用与还原、预乘混合因子、VAO 与程序的解绑）——它们不在
  `RenderBatch.submit` 里，没有那套"进中性状态、出来还原"的收尾可依赖。
- **拾取按系列发号**：ID 走 `uPickId` 这个 **int** uniform（不能是 uint——
  `glUniform1i` 对 uint uniform 报 `GL_INVALID_OPERATION` 且**值保持 0**，
  而 0 正是"什么都没命中"），号从 `Gc` 的那本 `pickRegistry` 取（两本注册表会撞号），
  `PickHit.payload()` 就是那个 `Series`。发号成本与点数无关。
- **热区容差 4px（半宽）是刻意的**：画出来的线只有 1~2px 宽，要求用户精确点中不合理。
  它只影响 ID pass，**不影响画面**，与"全透明图元仍可拾取"同类，有断言钉着。
- **`Series.markerSize()` 是半径**（用户坐标单位），而着色器的 `uMarkerSize` 是**边长**；
  换算（×2）只在 `ScatterSeriesRenderer.markerEdge` 一处。两处各持一半解释的话，
  用户设半径 5 会拿到宽 5 的方块，**画面上没有任何症状**。
- **不支持的要明确抛异常，不许静默不画**。本期实现了七种图型：
  `LINE`、`LINE_AND_MARKERS`（折线 + 标记点：标记点那半复用散点渲染器的几何，
  见下）、`SCATTER`、`STEP`、`AREA`、`BAR`、`SPECTRUM`（独立渲染器，见下）。
  `HEATMAP` / `WATERFALL` 一律抛异常——它们的顶点不是"每个样本一个点"，
  要各自的独立渲染器。
- **每个图型一个渲染器，哪怕顶点来自同一批实例属性**。折线族（`polylineFamily()`）
  只说明"顶点来自逐样本的点"，**不等于"折线渲染器画得出来"**：阶梯要拐角、
  面积要基线、柱状是矩形。把它们按属性组合推给折线渲染器会画成：阶梯被拉成斜线、
  面积图整个填充消失、柱状变成一串方块——**三种都是"画面完全正常"**。
  所以 `ChartRenderer.rendererFor` 是逐图型的显式枚举，各渲染器的
  `requireSupported` 是第二张（冗余的）白名单。
- **标题 / 图例 / 外边距：模型在 `chart/`，绘制在 `chartrender/`**。
  `ChartLayout`（纯计算，可单测）把**一整块外框**切成标题带 / 图例带 / 绘图区，
  尺寸规则只有两条且**与字体无关**（带子高 = 字号 × 1.4、基线 = 带子顶 + 字号）——
  读字体的真实 ascent 会让"同一个外框 + 同一个配置"在不同字体下给出不同的绘图区，
  逐像素的期望值就没地方写了。文字要占多宽必须问度量，于是有一个
  `chart/ChartTextMetrics` 接口（② 的 `ChartPainter extends` 它）。
- **★ 不设标题、不设图例、外边距为 0 时，绘图区与外框逐字段相等**。这是
  "给装饰留位置"不改变既有行为的那条底线，`ChartLayoutTest` 与 `ChartVerifier`
  两头钉着（后者按像素：同一张图走 `drawChart` 与走 `draw` 各一帧，逐像素相同）。
  **改动 `ChartLayout` 的默认值就会撞响它**——实测把默认外边距改成 2px 只倒那一条。
- **`draw(chart, plotRect, w, h)` 与 `drawChart(chart, frame, w, h)` 是两层**：
  前者要"已经算好的绘图区"（网格、刻度、轴全由调用方安排），后者要"整块外框"、
  装饰交给 `ChartLayout`。前者一字未改，两条路径画数据系列的代码是同一段。
- **标题只支持上下**（左右要转 90°，而绘制入口只有横排文字）：`titleSide(LEFT/RIGHT)`
  明确抛异常。图例四个方向都支持。
- **轴标题与刻度预留带**：`axisTitlesVisible(true)` 打开后 `AxisRange` 的 name/unit
  被画出来（轴 0 → x 轴标题、轴 1 → y 轴标题，形如 `电压 (V)`），**默认关着**——
  打开会让绘图区让出两条带子，而"绘图区变了就是画面变了"（既有图不该被新开关挪像素）。
   带子从外向里是 **图例 → 轴标题带 → 刻度预留 → 绘图区**；
  x 轴标题带横跨**绘图区**（不是整条内框），所以它不会与 y 轴标题带在左下角重叠。
- ★ **坐标系（网格 / 轴线 / 箭头 / 刻度线 / 刻度文字）已入库，默认关**（2026-09-28）。
  见下面单独一节「坐标系」——那一段取代了本文档先前"刻度文字仍由调用方画"的说法。
- **装饰被裁到各自的带子里**（`ChartPainter.begin(Rect band)`）：一项文字比带子宽时，
  后面的部分在带子边缘被切断，而不是越过边界画到别处。折行/省略号都不做（排版决策）。
  **抛异常被明确否掉**：`ChartLayout.compute` 在绘制路径上每帧被调用，而"每帧抛一次"
  意味着每帧丢一帧画面。
  ⚠️ **原来的理由写的是"GL 线程上的异常在本项目是静默吞掉的"，那句现在只对一半**：
  P1-6 之后帧回调里的异常会走到 `reportRenderFailure`（见「常用命令」里那段），
  **不再静默**；但"抛"仍然会让**那一帧**整个作废（`abortFrame`），而裁切只会让一项文字
  短一截。**结论没变、理由换了**——"用静默的坏事去修静默的坏事"这个说法已经不成立。
- **`drawChart` 不能带着变换**：布局算的是设备像素，带着 `translate/scale/rotate`
  会让装饰落到没算过的位置上。`Gc` 的实现里有一条**会抛 `IllegalStateException`** 的守卫
  （`ViewTransform.isBaseTransform()`，判据是"有没有被动过"而不是"矩阵等不等于基础矩阵"），
  由 `ChartVerifier` 的探针钉着。
- **饼图塞不进现在的 `Chart` 模型**：`Chart` 至少要一根轴、`ChartRenderer.draw`
  至少要两根，而饼图没有笛卡尔轴；扇区的标签与颜色也没有地方放
  （`Series` 只有名字与主色，`AxisRange` 的 name/unit 是**按维度**的）。
  要做得先回答"扇区标签放哪"——那是数据模型上的决定，不该硬塞。
- **`LOGARITHMIC` / `TEXT` 轴明确抛异常**：本期 GPU 路径只支持线性换算
  （`LINEAR` 与 `TIME`——时间轴的值是纪元秒，本身就是线性的）。
  按线性去画对数轴，曲线的形状是错的，而画面看起来完全正常。

##### 频谱（③-1，`SpectrumSeriesRenderer`）

- **它复用折线的整条绘制路径，没有自己的着色器**：实例布局与折线**逐项相同**
  （每实例两个 float：`mag[k]` 与 `mag[k+1]`，同一个 VBO、偏移差 4 字节），
  所以顶点程序、拾取程序、双偏移属性、`baseInstance`、拾取容差整套照用
  `LineSeriesRenderer` 那一套。差别只有三处：**数据源是 FFT 的输出缓冲**
  （绘制前先跑一次 `FftKernel.execute`）、实例区间的单位是 **bin**、
  可见点数是 `N/2+1`。
- **`ChartType.SPECTRUM` 的 `polylineFamily()` 是 `false`**（它的顶点不是"每个样本一个点"，
  而是 FFT 算出来的 bin），所以它属于"独立渲染器"那一类。`ChartRenderer.rendererFor`
  里那条 `SPECTRUM` 判断因此**必须排在 `polylineFamily()` 那道守卫之前**——
  排在后面的话它先被"本期还没有渲染器"抛掉，频谱永远画不出来。
- **★ 频谱的 x 轴窗口单位是 bin，不是 Hz**：渲染器拿 `axes[0].windowMin/Max` 直接当
  bin 下标用（`x = bin 索引`）。**要显示 Hz 由应用自己换算轴标签**（`Δf = fs/N`）——
  渲染层不知道采样率，也不该猜。
- **compute 写、顶点属性读，同一个缓冲、零拷贝，但中间必须有 memory barrier**：
  FFT 的输出缓冲**同时**是 SSBO 与 VBO（`glVertexAttribPointer` 记的是调用时绑在
  `GL_ARRAY_BUFFER` 上的那个缓冲，与 SSBO 绑定互不干扰），所以 compute 写完不需要任何
  GPU 侧拷贝。但 `FftKernel.execute` 内部那次 `memoryBarrier()` 是"compute 写 →
  顶点属性读"之间**唯一**那道屏障：渲染器**必须先 `execute` 再绘制**，顺序反了会读到旧值
  ——**数值错，不报任何 GL 错误**。
- **预热期不画，也不补零**：环里还没攒够一个完整的窗时直接返回。**不补零**是刻意的
  ——zero-padding 是另一个特性，静默补零会让用户看到一条"看起来正常"的错谱
  （峰位与旁瓣全是假的）；**也不抛异常**，因为那是采集刚开始的暂态（与折线在可见区间
  为空时直接返回同一种处理）。真正不成立的配置——环容量 < `FftKernel.MIN_N`——
  才**响亮报错**：那个系列永远算不出频谱。
- **实例化绘制的"容量"与缓冲实际大小不是一回事**：FFT 的输出缓冲只有 `N/2+1` 个 float，
  而 `WindowRange` 的槽位算术要求容量是 2 的幂，所以传进去的是
  `binCapacityFor(binCount)`（**向上取到的下一个 2 的幂**，只用于算术）。
  容量取小了会让 `bin k` 与 `bin (k-容量)` 共用槽位，画出来的是**错位的谱**——
  谱形完全正常，所以那条路只有"取下一个 2 的幂"这一种。

#### 图表交互：hover 十字线与提示框（工作区未提交）

**交互状态属于 `Chart`，不属于 JavaFX，也不属于系列渲染器**——这是这一节的立意。
新增四个类型，全在 **`chart/`（纯计算、零 GL 依赖，`ChartPackageIsolationTest` 的白名单
不用改）**：`ChartInteraction`（状态 + 纯计算命中器）、`ChartHover`（一次命中的结果
record）、`ChartInteractionConfig`（外观与行为，13 个字段的 record + 逐字段 wither）、
`ChartValueFormatter`（`@FunctionalInterface`，带 `DEFAULT`）。

三段接线，**每一段在不同的层**：

| 层 | 入口 | 干什么 |
|---|---|---|
| `chart/` | `Chart.interaction()` | 持有指针与配置；`probe(chart, plot)` 算出命中的点与提示框文本行 |
| `chartrender/` | `ChartRenderer.drawChart` 末尾调 `drawInteraction` | 画虚线十字、命中点方块、提示框 |
| `xuan-javafx` | `FXGLTransfer.trackChartHover(node, chart)` | 把 `MOUSE_MOVED`（乘缩放）与 `MOUSE_EXITED` 接到 `updatePointer` / `clearPointer` |

- **`probe` 是逐样本线性扫描**（`chart.allSeries()` × `itemCount()`，每帧一次），
  **已限定在可见窗口内的样本**（见下一条）。它的 KDoc 明说大数据量该在这里换
  二分/LOD 索引，换的时候不需要动 GL 后端与 JavaFX 层。
  **残余成本照实说**：窗口判据在最前面、两次比较就能跳过，所以扫描本身仍是
  `O(itemCount())`——"只遍历窗口内那一段下标"要求"x 值随下标单调"，
  而 `ChartData` 没有这条契约，所以**没做**。这与折线渲染"只画可见实例"
  的成本模型**仍然不是一回事**。
- **配置 record 在紧凑构造器里校验，NaN/Inf 一律抛 `IllegalArgumentException`**
  （`snapRadius` 可 0、`crosshairWidth`/`dashLength`/`tooltipFontSize` 必须 > 0、
  `tooltipPadding`/`tooltipOffset` 可 0）。这与 `Series` 的 `fillAlpha`/`lineWidth` 那组
  「NaN 抛异常、越界但有限的量照旧收下」**是同一条口径**。
- **只扫 `polylineFamily()` 的系列**（`!series.type().polylineFamily() → continue`）。
  按 `ChartType` 的定义这是 `connectsSamples() || drawsMarkers() || drawsBars()`
  ⇒ **`LINE` / `LINE_AND_MARKERS` / `STEP` / `AREA` / `SCATTER` / `BAR` 都参与**，
  **只有 `SPECTRUM` 被排除**（它的顶点不是逐样本点，是 FFT 的输出 bin——
  与「频谱的独立渲染器」那一条同源）。**所以"交互只对折线有效"是错的**。
- **NaN 缺口是"这个点不参与命中"，不是"整次不命中"**：
  `!Double.isFinite(x) || !Double.isFinite(y) → continue` —— 跳过**那个点**、继续扫别的点。
  所以指针落在缺口正上方时，只要 `snapRadius` 内有别的有效点，**照样会吸附到它**
  （吸附是按屏幕距离算的，缺口两侧的邻居往往就在半径内）；
  `probe` 返回 null 只在**半径内一个有效点都没有**时发生。
  ⚠️ **别把它读成"悬在缺口上就什么都不画"**——那是错的，也会让据此写的断言
  变成一条恒假断言。它与「缺口用 NaN 表示、渲染器遇到 NaN 就断开折线」是
  **同一份数据约定的两个消费者**，但**处置方式不同**：渲染器断开，命中器只是无视那个点。
- **提示框的坐标是"先偏移、再夹回绘图区"**：
  `x = screenX + offset`，右边越界就翻到左侧，`y` 上边越界就翻到下边，
  最后再 `clamp` 进 `plot`。所以**箭头/指向线不做**——只有位置。
- **绘制那支笔是新长出来的**：`ChartPainter` 接口加了 `strokeLine` / `strokeDashedLine` /
  `strokeRect` 三个方法，`Gc` 里那个**私有**的 `chartPainter` 对象实现它们
  （用 `save/restore` 那套状态字段临时改 `stroke`/`lineWidth` 再改回来）。
  ⚠️ **`Gc` 自己仍然没有公开虚线描边**——`strokeDashedLine` 只活在图表装饰那支**私有**的笔上，
  应用层要画虚线仍然得自己切弧长（见「已实现 vs 未实现」里那条）。
  `strokeDashedLine` 的切分是**按 `dashLength` 等长交替、逐段调 `drawLine`**，
  没有相位参数、也不复用 `StrokeGenerator.strokeDashed`。
- ★ **只考虑 x 在可见窗口内的样本**（`x ∈ [xAxis.windowMin(), windowMax()]`）。
  这条是**正确性**，不是优化：`Axis.dataToDisplay` 不夹取，窗口外的样本会被映射到
  绘图区**之外**，而十字线与命中点都被 `painter.begin(plot)` 裁在绘图区内 ⇒
  命中一个窗口外的样本时，画面上的后果是**纵线被整条裁掉、命中点整块被裁掉
  （看上去只有横线、没有命中点）、而提示框照常报出一个看不见的样本的读数**，全程无报错。
  可达条件很普通：**窗口起点不是采样间隔的整数倍**（流式滚动图正是如此）。
  实测复现：x 窗口 `[5.05, 15.05]`，x=5 那个样本落在 `sx = -3`，它比任何可见样本
  都更靠近指针 ⇒ 修复前命中它（`screenX = -3`）。
  **y 不做这个过滤是刻意的**：值出 y 窗口只是"现在在视野上下之外"，
  "这个 x 上的值是多少"仍然有意义（纵轴放大时尤其如此）；而出 x 窗口意味着
  这个样本根本不在当前视图里。两者处置不同是有理由的。
- ★ **提示框文本只在循环结束后构造一次**。它以前在**每一个"更近的候选"**上都要构造
  （建 `ArrayList` + 调 formatter），密集数据下那是这趟扫描里最贵的一块，
  而且 `tooltipVisible == false` 时照样执行（`probe` 不看那个开关）。
  判据用 **formatter 的调用次数**——两者的返回值完全相同，任何只看结果的断言都抓不住它。
- **扫描仍是 `O(itemCount())` 全量**（只是窗口判据在最前面，两次比较就能跳过，
  在算屏幕坐标那两次除法之前）。**没做成"只遍历窗口内那一段下标"**：那要求
  "x 值随下标单调"，而 `ChartData` 没有这条契约（`value(0, i)` 返回用户 append 的
  原始值，可以是时间戳、也可以是任意数）。要真做需要先立这条契约。
- **⚠️ 绘制那一半仍然没有校验器覆盖**（写在这里是为了下一个人不必重新判定）。
  `ChartVerifier` 里搜不到 `interaction` / `crosshair` / `tooltip` / `hover` 任何一个词
  （实测 grep 为空）⇒ 十字线与提示框的**像素**、提示框夹取、`trackChartHover` 的**坐标换算**
  **都只有眼睛能判**。`ChartInteraction.probe` 的**计算**那一侧现在由
  `ChartInteractionTest` 的 **5 条**盖着（含窗口外不吸附、窗口边界可命中、文本只构造一次）。
  按本仓库"静默错误输出"的口径，**绘制那一半仍是新渲染路径里最该补的一条**。
  可参照的判别式：夹取用「把指针推到绘图区四角，断言提示框矩形仍逐边在 `plot` 内」、
  缺口用「悬在缺口**且**半径内无有效点时该帧的十字线像素数为 0」
  （⚠️ **不能**写成"悬在缺口上就为 0"——见上面那条，那是恒假的）。
- **⚠️ `trackChartHover` 不能解绑、也不去重（已知缺口，本期不做）。**
  它每次都往同一个节点上**再加 2 个处理器**，而处理器闭包**强引用住那个 `Chart`**
  （`Chart → Layer → Series → ChartData`，`RingChartData` 可以是一整块环）。
  于是"数据刷新后重建 `Chart` 再 `trackChartHover` 一次"这种写法（很自然——
  `Chart` 看起来就是个纯计算对象）会造成：① 处理器列表无界增长，每次 `MOUSE_MOVED`
  触发 N 次 `updatePointer` + N 次 `repaint()`；② 旧 `Chart` 永远回收不掉。
  当前两个调用点都只挂一次，所以是**潜在**缺陷——但 API 形状上没有任何东西拦住它。
  **没修的理由**：修法要么给 `untrackChartHover`、要么返回一个句柄，
  **那是新增公开 API**，要自己的一轮设计（本期只做低风险的局部改动）。
  ⚠️ 它与 `ChartRenderer` 的自动回收**不是**同一件事：那个管 GPU 缓冲与拾取号，
  这个管 JavaFX 事件处理器与 `Chart` 对象本身。

### 抗锯齿

**两个开关，默认都关**，因为它们管的是两件不同的事：

| 开关 | 时机 | 住哪 | 管哪些图元 |
|------|------|------|-----------|
| `Gc.antialias: Boolean` | **运行期**，进 `save`/`restore` 栈 | `Gc` 的样式栈（与 `lineWidth` 并列） | 一切**描边** + **六个图表渲染器** |
| `FXGLTransfer(msaa = N)`／DSL `xuan { antialias { msaa = 4 } }` | **构造时** | `GLCanvas` 的帧缓冲 | **填充**的边缘 |

- **分工判据是「几何知不知道自己的中心线在哪」**，不是"解析 vs 非解析"。
  描边是**一条带**：`StrokeGenerator` 从中心线向两侧偏移，每个顶点到中心线的距离是
  它自己算出来的；图表系列同理（顶点着色器**已经**算好了法向与半线宽）。
  而**填充是一块面**：`Tessellator` 产出三角形汤，"内部顶点到边界的距离"**没有定义**
  ⇒ 解析式要做就得引入 nanovg 那一路（沿边界铺 fringe quad / stencil / 距离场），
  是另一个量级。MSAA 恰好**擅长填充、弱在细线**（4 个子样本只有 5 级覆盖）
  ⇒ 各用各擅长的，而不是强行用一种机制覆盖全部。
  **推论**：`fillRect` / `fillCircle` / `fillPath` 这类**填充**开 `Gc.antialias` 无效
  （它只对描边与图表系列生效），要给它们抗锯齿只能靠 `msaa`。
- ★ **"默认关"是承重的，不是摆设**：本仓库有一批**精确到 ±0 的像素期望**
  （蓝圆角矩形描边 = 3084，从子项目 A 第一步到现在**一次没动过**）。变异实测
  （把 `uAntialias` 恒传 `1f`）⇒ **12 条倒，其中 9 条是既有断言**
  （"画面只有这 7 种颜色"、图例/标题/外边距那几组的数据线行号）——那些期望本来就
  建立在"默认关"上。**改这两个默认值等于重立那批断言。**
- **`msaa` 只能在建窗口时给**：采样数是**帧缓冲**的属性，`GLCanvas` 只有 `getMsaa()`、
  没有 setter（实测 jar 的公开签名）。`xuan { }` 的配置块跑在 `Application.launch`
  **之前**，正好是这个时机；**运行期改它没有任何效果**（API 上拦不住，所以写在文档里）。
- ★ **`msaa` 非 0 时像素回读失效，拾取不受影响**——两件事，别混：
  （**"非 0"包含负数**：openglfx 的约定是 `-1 = 最大采样数`，**不是"关"**——
  实现 `msaa < 0 -> Framebuffer.MultiSampled(..., GL_MAX_SAMPLES)`，实测 `msaa=-1`
  给出 `GL_SAMPLES=32` 的多采样画布、`glReadPixels` 同样非法。所以判据只能是 `== 0`。）
  - 多采样 FBO 上 `glReadPixels` 是**非法操作**（实测 `GL_INVALID_OPERATION`，读回全 0。
    注意：不是"读到旧帧"、也不是"读到黑"，是**那次调用整个非法**），而本仓库
    **靠画布 FBO 回读的那七个像素校验器全部靠它回读**（`Pipeline` / `Path` / `Pick` /
    `Click` / `Text` / `Chart` / `Axis`）。所以有 `FXGLTransfer.canReadPixels`（= **`msaa == 0`**
    ——**不是 `<= 0`**：负数与 1 都会被驱动变成多采样，见本节开头），
    八个校验器入口各调一次 `requirePixelReadback(bridge)`——**一份共享判定，不是八份抄写**
    （"共用属性名 ≠ 共用判定"是本仓库刚吃过的亏）。`msaa` 非 0 时**明确拒绝**，
    而不是读回全 0 之后报一堆"画面全黑"式的**假失败**：假失败会让人去查渲染，
    而真因在配置里。
    ⚠️ **`FftVerifier` 是例外，就地写在这里**（**别按序号数**——本仓库的校验器数目已经
    改过两回，序号一律会过期；认名字）：它
    **不画像素**，读的是 SSBO（`glGetBufferSubData`），那条路与画布 FBO 的采样数
    **无关** ⇒ `msaa` 非 0 **不影响它**（实测：挂在 `msaa=4` 下跑，50 条断言全过、
    与 `msaa=0` 逐行相同）。它也挂同一道守卫，但那是**纪律**（七个一律 `msaa=0`
    这条口径比"每个各自判断自己受不受影响"更不容易出错），**不是实测的必要性**。
    **这条守卫的前提由 `MsaaVerifier` 钉着**：它断言 `canReadPixels == (glGetError == 0)`，
    也就是说守卫的判据与实测**等价**（在 `msaa=0` 与 `msaa=4` 下各跑一次）。
  - 而**拾取完全不受影响**（实测 `msaa=4` 下 `pick` / `pickRect` 与 `msaa=0` **逐项相同**）：
    拾取 FBO 是**独立创建的单采样** FBO。所以"开了 msaa"与"拾取坏了"是两回事。
- **想要细线质量请用 `Gc.antialias`**（运行期、且不牺牲回读）；`msaa` 留给填充边缘。

#### 解析式那一半（`Gc.antialias`）

- **两个消费者、合计七路**：① `Gc` 自己的**描边**；② **六个图表渲染器**
  （折线 / 散点 / 阶梯 / 面积 / 柱状 / 频谱），经 `Gc.charts` 里那个 supplier 把开关
  透传给每个系列的 `uAntialias`。**不要把 ② 当成"也是描边"**：面积图的填充、柱状图的柱
  都是**填充**——所以"`Gc.antialias` 只影响描边"这句话只对 ① 成立。
  给图表数据系列开 AA 设的就是这个属性，**不要另加一个"图表专用开关"**（同一件事摊成
  两处，迟早一处开一处没开，而画面上只表现为"某些图型没那么细腻"）。
- **`Gc` 那条路没有 `uAntialias` uniform**：填充/文本与描边靠 `fwidth == 0` 区分
  （填充与文本写 `aEdge = (0,0)`）。**图表那条路恰好相反**：顶点几何完全不动，
  开关就是一个 per-draw 的 `uAntialias`（一个系列一条 draw call，不涉及合批）。
  **两条路的实现选择不同，别把一条的说明套到另一条上。**
- **几何双向外扩 1 个设备像素是必需的**：片元的覆盖率斜坡是"带宽外侧半个像素"，
  不外扩的话那一半**没有片元** ⇒ **外缘那半条斜坡的墨量整个没了、边缘退化成半硬边**。
  ★ **方向是"更细"，不是"更厚"**——外扩只是**加上**真外缘之外的片元，拿掉它只可能让墨量
  **减少**；实测把外扩量改成 `0f` ⇒ 墨量 115400 → **98400（4.823 px/列）**，
  而**硬边对照组是 102000（5.0 px/列）** ⇒ 它比硬边**还细**。
  外扩量取 `px = 1 / matrixScale()`，由 CPU 侧算一次。
  ⚠️ 这句原来写的是"边缘会**偏厚**"——**方向相反**，而它恰好被引在"要不要外扩"
  那个决策点上（源头是规格 §4.1，那边已由规格作者改）。
- **`Gc.antialias = true` 之后描边的可拾取范围比可见线宽大 1 个设备像素**
  （ID pass 复用同一份顶点缓冲，几何被横向加宽过 ⇒ 拾取如实跟着几何走）。
  判定为**不是缺陷**，理由与「全透明图元照样能命中」同类：最外那一圈本来就半可见
  （覆盖率 0..0.5）。**不要去"修"它**（让 ID pass 用未加宽的几何会让画面与拾取
  互相矛盾，那更糟）。这条**目前没有断言盖着**（`PickVerifier` 跑的是 AA 关的路径）：
  它是"已声明的行为"，不是"已验证的行为"。

#### 已声明的降级（不是缺陷，别试图"修好"）

1. **miter 拐角是硬边（不羽化）。** `emitJoin` 给接头三角形的横向一律取 `0f`
   ⇒ `fwidth == 0` ⇒ 走"完全覆盖"分支。三个选项——**硬边 / 半透明边 / 缺口**——
   里硬边是唯一"画对了形状"的那个（另两个各错一半：半透明边会把拐角画淡，
   缺口直接少一块）。真要羽化 miter 需要每条边各一个距离分量（`aEdge` 从 `vec2`
   扩到 `vec3` 以上），**当前没有证据说明它值得**。
2. **图表那条路径的几何不外扩** ⇒ 真外缘**之外**缺半条斜坡（覆盖率 0.5 到 0 那一段
   没有片元）。后果是每列固定少一点墨量：本相位下实测 **−0.25 px/列**
   （所以图表 ④ 的解析值是 **2.75** 而不是 3）。**方向单向**（只会变薄，不会画错形状、
   也不会多画墨量），与"旋转 `clipRect` 退化成包围盒"同类。修它要把外扩搬到
   顶点着色器里，而图表系列的顶点是 instanced 的，**本期不做**。
3. **非等比缩放下，缩放较小的那条轴真外缘之外的羽化被切掉**：外扩量取的是
   `matrixScale()`（**两轴长度的平均**），对整个坐标系只有一个标量。
   `gc.scale(1f, 10f)` 下压缩轴只外扩 0.18 个设备像素 ⇒ 外缘之外那一档没有片元。
   **上界是 0.5**（最多把外缘之外的斜坡切光），带内羽化照常画。
   ⚠️ **这不叫"退化成硬边"**：实测剖面是 `98:#CDCDCD 99..101:#FFFFFF 102:#333333`，
   那个 `#CDCDCD` 就是覆盖率 0.75 的过渡像素；**真正硬边**的对照在同位置是**全白**。
   把它说成"硬边"会让人照着错方向去查。
4. ★ **图表那条路径**只有横向**有羽化，**沿向那一刀是硬边**（带的端点是硬切）**。
   四个着色器的 `vEdge.y` 恒为 0（`LINE` / `STEP` / `AREA`，以及复用折线的 `SPECTRUM`），
   只有 `SCATTER` 与 `BAR` 两个分量都真 ⇒ 片元里 `fwidth(0) = 0 ⇒ cov.y = 1`。
   **条件（别读成"任何时候"）**：**窗口比数据范围宽**时，带的左/右端就是序列的
   **真实几何端点**，而它**落在绘图区内部**——`chart/Axis` 明写"轴不做裁剪"，而
   `sx = plotX + rel·pxPerSample` 里的 `rel` 是相对**窗口左端**算的 ⇒ 窗口越宽，
   真实端点越往绘图区里缩。（窗口**不宽于**数据范围时，端点落在裁剪边界上，
   被 `glScissor` 切掉的那条边按定义就是硬的，**没有降级**。）
   **这正是 `Gc` 那条路花整条机制消灭的东西**：`VertexFormat.OFFSET_EDGE` 的 KDoc
   原话就是「只用横向的后果是：一条 4px 横线，上下长边有抗锯齿、**左右两端是硬角**」
   ——`Gc` 为此加了第二个分量、`capExtension`、`joinHalf` 与一个 8 参重载。
   **要修得给图表侧补一个沿向分量，属新特性，本期不做。**
   ⚠️ **当前没有任何断言照得到它**：AA 探针全部取 `setWindow(0.0, 点数−1)`,
   正好落在"窗口不宽于数据范围"那个前提里 ⇒ 探针自己的几何就是那个降级不成立的场合。
   （发现它的是终审，代码标注与本节都据此改了：`SeriesShaders` 的 `AREA_VERTEX` 注释
   原来把"左右两端落在裁剪边界上"写成了**无条件**陈述。）

#### 已验证 / 已声明（照实分级）

**已验证**（每条都有读数出处）：

- **四条判据的解析值**（45°、线宽 4；A = AA 关、B = AA 开，两者是整数平移 ⇒ 相位逐项相同）：
  ① AA 关的过渡像素 **0**；② AA 开的过渡像素 **400 = 4W**（每列 4 个：`m = ±2` 与 `±3` 各两个）；
  ③ 线心（`|m| ≤ 1`）纯色像素数 **关 300 / 开 300**（`3W`，两模式精确相等）；
  ④ 墨量 **115400**，解析 `4√2 × 204 × W = 115399.8`（**相差 +0.2**）；
  ④ 对照 **102000 = 5W × 204**（精确）；④b（1px 细线）四种组合**都是 81600 = 1.000 px/列**，
  比值 **1.0000**（那个 `[0.9, 1.1]` 容差在这里**没兜住任何东西**）。
  ⚠️ **④ 的参照系是解析面积、不是对照组**：45° 直线下硬边**本来就少 11.6%**
  （阶梯**内接**于斜带），写成"AA 开 == AA 关"是**恒假断言**。
- **`C ≠ A` 那条反证是必需的**：探针一画三条几何相同的线，A、B 两种 AA 来路不同、
  C 反过来。`A ≡ B` 单独用是**恒真**的（把 `antialias` 字段整个删掉也满足它）。
  实测：两处 `if (antialias)` → `if (false)` ⇒ **15 条倒**，且 **`C ≠ A` 倒而 `A ≡ B`
  照常通过**——反证的有效性被独立证实（正是要的那个方向）。
- **六个图表渲染器逐个被独立覆盖**。变异一律是"把该渲染器的 `uAntialias` 恒传 `0f`"
  （等价于删掉那一行），**逐个重测过**（`mvn -o install -pl xuan-render-gl -am` + `ChartVerifier`）：

  | 注入到哪个渲染器 | 倒几条 | 倒的是哪些 |
  |---|---|---|
  | `LineSeriesRenderer` | **3** | `★ AA 折线②`、`★ AA 折线④`（墨量 76500 vs 解析 70125）、`★ AA 交叉`（AA 开那一对） |
  | `ScatterSeriesRenderer` | **1** | `★ AA 散点 SCATTER ②` |
  | `StepSeriesRenderer` | **2** | `★ AA 阶梯 STEP ②`、`★ AA 交叉`（AA 开那一对） |
  | `AreaSeriesRenderer` | **1** | `★ AA 面积 AREA（基线那一段，只可能来自填充） ②` |
  | `BarSeriesRenderer` | **1** | `★ AA 柱状 BAR ②` |
  | `SpectrumSeriesRenderer` | **1** | `★ AA 频谱 SPECTRUM ②` |

  ⇒ "漏掉某个渲染器会活下来"这个担心**不成立**（六条各自都倒）。
  ⚠️ **但不是"各自只让一条倒"**（原书写的是"各自只让对应那条 ② 倒"）：折线牵着 **3** 条、
  阶梯牵着 **2** 条——因为④（墨量）与「交叉」那两条也挂在它们各自的几何上。
  **"各自只倒一条"读起来像结论，其实是没数过**。
  （原书那六个编号 M4/M5/M6/M7/M8/M9 **在整条分支里没有定义**、且与别的子项目的
  M 编号撞车 ⇒ 已改成上面这种**自述式**：写明注入到哪个文件。原来只有 M5 有一句
  括号解释，其余五个查不到是什么变异。）
- **`ChartVerifier` 146 PASS / `PipelineVerifier` 59 PASS / `mvn -o test` 385-0-2**，
  蓝描边 **3084** 从第一步到现在**一次没动过**（两个开关默认关的直接后果）。
- **两种相位不是冗余**：`abs(vEdge) → vEdge`（斜坡变单侧）时**相位 A 的 ② 照过**、
  **只有相位 B 倒**（100 → 0）——"一个相位验得出羽化**存在**，两个相位才验得出羽化是
  **对称的**"。
- **`Gc` 的"细带公式"没有被证伪**：1px 线在两种相位下墨量都恰好 1.000 px/列
  ⇒ 规格 §4.3 说的"可能偏厚"**实测没有发生**，`aEdge` 不必从 `vec2` 扩到 `vec3`。

**已声明但未验证**（写在这里是为了下一个人不必重新判定"能不能验"）：

- **`matrixScale()` 的方向**（`px = 1 / matrixScale()` 而不是 `×`）：
  在**当前探针集合下已可验**——`gc.scale(1f, 10f)` 那条探针立起来之后，
  写成 `1f * matrixScale()` 会让压缩轴那条断言倒（**只倒定向的那一条**，其余照过）。
  ⚠️ 它曾经被记成"**无法**用变异验证"（恒等变换下 `1 * x` 与 `1 / x` 都是 1）——
  **那句话在当时为真、但不是原理性的**：写"未验证"时要连着写"**在当前探针集合下**"。
- **非等比缩放的降级只钉方向、不钉数值**（没有断言"切掉了 0.318 个设备像素的覆盖"
  这类量）；它的两个相位是**挑过的**（斜坡只有 1 个设备像素宽、像素中心间距也是 1
  ⇒ "真外缘之外有没有片元"是相位决定的），**挪动线的位置就要重新核那两个相位**。
- **`vEdge.y = 0` 的隐式契约**：图表侧的 `vEdge` 是 **`vec2`**（**不是 `float`**——
  `SeriesShaders` 的 KDoc 明写这一点，理由是**标量装不下盒子**：散点与柱状四条边都要），
  而单轴图型（折线 / 阶梯 / 面积 / 频谱）**第二个分量恒为 0**。
  把那个常量写成非 0 会怎样**没有任何断言能发现**（实测 `0.0` → `1.0` ⇒ 146 PASS、
  AA 段逐字节相同——常量分量的 `fwidth` 仍是 0 ⇒ 覆盖率仍是 1）。
  **"必须写 0"只是约定，不是被钉住的契约。**
  ⚠️ 本条原来写的是"`vEdge` 是 `float`（只有横向）"——**与代码和规格都相反**，
  而它挂在"下一个人照着判断能不能验"的清单里：信它的人会去找一个不存在的 `float`
  varying，甚至以为第二个分量可以省——而省掉它正是那个"一点 AA 都没有"的坑。
- **`lineWidth <= 0f` 仍无断言覆盖**（AA 开时它不再画出淡淡一条，但八个校验器里
  没有这个场景）。与 Task 3 记的是同一条。
- **`ChartRenderer` 的 3 参构造无任何调用点** ⇒ "不注入 `antialias` 时恒按 AA 关画"
  这条**没有断言盖着**。
- **回读守卫的"接线"没有闸门**：八处 `FXGLTransfer(msaa = readRequestedMsaa())` 里
  任何一处退回 `FXGLTransfer()`，**那处的守卫就又变成死代码，而没有任何断言会响**
  （`MsaaVerifier` 只验"属性 → `msaa` 值"这条链与守卫的前提，它验不到"八个入口都读了它"）。
- **`MSAA_READING` 的字段与进程内被断言的变量没有对过**：它由**手写字符串**拼出
  （`fringe=$fringe core=$coreWhite …`），若把两个字段**写成一致的偏移**（例如
  `fringe=$coreWhite core=$coreWhite`），脚本的跨进程比较照样成立、**两边都看不见**；
  字段互换只因两次运行的读数不同才被偶然抓住。要真钉住得让脚本之外的**一条进程内断言**
  比对"读数行里的值与被断言的变量"，或把读数行改成由同一份数据生成。
- **DSL 的 `xuan { antialias { msaa = N } }` 没有端到端证据**：接线读码正确
  （`Xuan.antialiasConfig.msaa` → `XuanApplication.start` 里那次 `FXGLTransfer(...)`，
  它是**唯一**经 DSL 构造桥接对象的入口），但**没有任何校验器或 demo 走这条路径**
  （八个校验器与 `MsaaVerifier` 都直接 `new FXGLTransfer(...)`）。
  ⇒ "DSL 里设的 msaa 真的会生效"是**已声明、未验证**——要验它得写一个
  `xuan { antialias { msaa = 4 } }` 的探针应用（或让 demo 支持该配置）。
- ⚠️ **上面这批实测都在本机（NVIDIA 4.6）**：MSAA 的样本位置、`fwidth` 的行为都是
  **驱动/硬件相关**的量，换机器要把那几条"精确相等"的期望重新量一遍。

##### 平滑曲线（`Series.smooth(boolean)`）

**按系列**开关（一张图里"一条平滑 + 一条折线"是正当用法），**默认关**。

- **它是着色器侧的 Catmull-Rom**，四控制点 `y[k-1], y[k], y[k+1], y[k+2]`。
  邻居靠**两个额外的 `divisor=1` 实例属性**取（字节偏移 **−4** / **+8**）——
  **数据布局一个字节都不用改**，`baseInstance` 照样滑。
- **平滑的系列用 `capacity + 3` 的缓冲布局**（**前 1 后 2** 个余量：前置余量镜像槽位
  `capacity-1`、尾部两个镜像槽位 0 与槽位 1）；**普通系列保持 `capacity + 1`**。
  ⇒ 既有那条**"每一帧恰好 K×4 字节"的上传字节数断言一字未动**——它是 ② 的核心判据之一。
  镜像的字节级语义由 `SeriesUploadPlanTest` / `SeriesBufferTest` 钉着。
- **★ 边界处环里那个位置是**陈旧数据**，而它不是 NaN** ⇒ 越界必须**显式**传有效区间
  （`uSmoothFrom`/`uSmoothTo`，由 CPU 按"已上传数 − 容量"与"已上传数 − 2"算好再夹到本段），
  **绝不允许靠"读到的 NaN"来判断**。不这么做的话曲线会**弯向垃圾值**，而画面看起来完全正常。
- **缺口（NaN）退回直线**，与"缺口不能连过去"同一条规则；四控制点里任一为 NaN ⇒ 该段走直线。
- **`STEP` / `SPECTRUM` / `SCATTER` / `BAR` 一律忽略它**。`STEP` 是"平滑一个阶梯没有意义"；
  **`SPECTRUM` 是硬理由**：它的实例属性指向 FFT 的输出缓冲，那个缓冲**没有邻居余量**，
  末尾实例的 `aY2` 会读到缓冲之外——而**越界读在 GL 里既不报错也非法**。
  频谱那条**必须显式**设 `uSmooth=0`（它与折线共用顶点程序，不设会把上一个平滑折线的
  uniform 串过来，画出一条"形状完全合理的假谱"）。
- **中途改 `smooth()` 会重建缓冲**（布局在创建时定死、它决定四个属性的字节偏移）。
  不重建的话**属性偏移与 `uSmooth` 各说各话** ⇒ 曲线弯向别的样本，
  而画面只是"一条形状略有出入的曲线"。代价是每次切换重传一次整环（≤ 容量 × 4 字节）。
- **实时流（环还在写）的最后一段永远是直的**：`y[k+2]` 那个样本还没采到。
  这是"前 1 后 2"布局的**必然结果**，不是取巧；表现为数据右端最多一个采样间隔的直线段。
- 拾取热区**跟着曲线走**（ID pass 与绘制共用同一份顶点程序、同一批 uniform 与同一个区间），
  这条**没有像素断言**：Catmull-Rom 的偏差只有约 1.65 px，而拾取容差是 4 px，
  任何探针点都分不开"热区跟着曲线"与"热区跟着弦"。**属已声明、未验证。**

**验收**：`ChartVerifier` 第 23 节（6 条：开关真的生效 / 首末段不弯曲 / 跨缺口逐像素相同 /
改开关要重建 / **跨环绕三个镜像都生效** / 面积顶边），加 5 条变异。
其中 **"丢掉新增的两个镜像位置"只让第 ⑤ 条倒**（既有那两条跨环绕断言照过）。

### 线程模型

所有 `gl*` 调用与 GL 资源生命周期**必须**发生在 `GLCanvas` 的 GL 线程上，即
`onInit` / `onRender` / `onReshape` / `onDispose` 回调内部。JavaFX 应用线程上只能做布局和
事件注册。`xuan {}` 的 `onInit` / `onRender` 都在 GL 线程上调用，可以直接用 `Gc`，
但**不要**在其中操作 JavaFX 场景图。

不要在 JavaFX 的 `stop()` 里清理 GL 资源——那时上下文可能已失效。

### 资源释放

带 GL 资源的类统一实现 `com.bingbaihanji.xuan.util.Disposable`。
所有权：`FXGLTransfer.onDispose` → `RenderBatch.dispose()`。

**`Disposable` 的四条契约**（`docs/Xuan-DEVELOPER-GUIDE.md` §5.3；本轮在
`ShaderProgram` 与 `Texture` 上落地，两者此前**一条都不满足**）：

1. **`dispose()` 幂等**——都加了一个 `disposed` 标志，第二次数直接返回
   （以前第二次会拿着已经 `glDeleteProgram` 掉的 id 再删一遍）。
2. **释放后继续使用明确抛异常**——`checkNotDisposed()` 挂在 `use()` / `getUniformLocation()`
   （着色器）与 `bind()` / `unbind()` / `getTextureId()`（纹理）上，抛
   `IllegalStateException`。**不是默默用 id 0 继续跑**：那正是"静默错误输出"的形态。
3. **构造失败时回收已创建资源**——`Texture` 的构造器现在先校验
   （尺寸必须为正、`pixels.length == width * height`）**再**建 GL 对象，
   校验失败时还没创建任何东西。
4. **所有权与释放顺序明确**——`FXGLTransfer.onDispose` 用 `finally` 保证
   `disposeCharts()` → `RenderBatch.dispose()` 走完（图表后端挂在这条链下游）。

**顺带一条可测性的做法**：`Texture.argbToRgba(width, height, pixels)` 被提成
**包级可见的静态方法**（原来那段字节序转换内联在构造器里），于是"ARGB→RGBA 的通道顺序"
这条**公共 API 契约**能脱离 GL 上下文单测——`LwjglGLAbstractionTest` 里两条断言
（一条是既有的 `LwjglGLAbstraction.argbToRgba`，一条是新的 `Texture.argbToRgba`）
**钉的是同一份契约的两份实现**。这也解释了为什么它会从 `private` 变成包级可见——
不是为了复用，是为了**能被验证**。

> ⚠️ **但那个类本身是死的，两份拷贝也没收敛。** 全仓（含测试）grep 不到任何
> `new Texture(...)` 或 `import com.bingbaihanji.xuan.gl.Texture` —— **`gl/Texture` 零引用**，
> 真正在用的是 `GLAbstraction` 那条路（`createTexture` / `createR8Texture` /
> `createIntegerTexture`）。也就是说这份契约现在有**两份实现**，
> 而它们**已经漂移过一次**：`Texture` 里原先那段写的是 `[B,G,R,A]`（蓝红互换，
> 注释还写着"红/绿/蓝/透明"），抽象层那份是对的，**没有任何东西发现它**。
> 今天两边的测试都钉同样的字节序，风险低；但**别以为"两份都有测试"就等于"不会分家"**
> ——两条断言钉的是各自的实现，不是一个共享的源。
> 收敛它们的入口是"删掉 `Texture`"（它没人用），不是"再补一条交叉断言"。

## 怎么验证改动

**这是本仓库最重要的一节。**

本项目多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
已有的教训：`Gc` 的 `strokeRect/strokeCircle/strokeEllipse` 曾经把闭合轮廓按**开放**折线描边，
导致矩形整整少一条边——而 `StrokeGenerator` 自己是对的，它的单元测试断言"闭合面积大于开放面积"
也确实通过。**当时的单元测试没有一个发现**，因为测试口径与几何无关。

所以：

1. **改渲染路径后，跑 `PipelineVerifier`，不能只靠人眼看窗口。**
   它回读帧缓冲，逐项断言像素数、包围盒、描边四条边的对称性，失败以非零码退出。
   它是上述缺陷被发现的原因。

   **它的期望值不许用"没出处"的百分比**。蓝圆角矩形描边那条曾经是 `772.5 * 4 = 3090`
   （±5%，即 ±154 px），而**那个 5% 藏住了一个真缺陷**：MITER 接头漏半块时实测 3068
   ——偏离 0.7%，照样通过。一条宽到能藏住真缺陷的断言比没有断言更坏。
   现在它是**精确相等**，期望值 3084 的出处写在断言注释里，摘要：
   ①同一构建连跑 **8 次**（每次新 JVM）都是 3084，**极差 0**；
   ②旧期望 3090 用的是**光滑圆弧**周长，而实现里的中心线是 **28 边形**（24 段 15° 弦），
   凸闭合折线的描边带面积恰为 `2×L×半线宽` = 3088.98（角上二阶项相消）；
   ③剩下的 **4.98 px 是"像素中心采样"的确定偏移**——另写一份独立的参考量算
   （构造外/内 miter 偏移多边形 + 逐点判中心在内）复算出 3084，与 GPU 回读逐位相同；
   ④这条带子上没有"中心正好落在边界"的像素（直边在整数坐标、斜边是无理斜率），
   所以它不依赖光栅化 tie 规则，**不需要留余量**。
   要改这条期望值，请照这四步重新量一遍，**不要凭"收个容差应该够"去调**。

   改 **`Gc` 的路径方法**（`strokePath` / `fillPath` / 子路径处理 / **`dashPattern` 那条分派**）
   后跑 `PathVerifier`（退出码 0/1，**74 条**断言）。它有**九个变体**、逐帧轮转、逐个断言：
   ①一条路径里画两条互不相连的横线；②只画第一条（钉住"上一帧的顶点留在缓冲里"这类**跨帧残留**）；
   ③闭合的直角方框（粗线宽，把收尾接头放大成看得见的缺口）；④**同一份几何**的开放对照；
   ⑤**开放**的直角折线（钉住反面——开放子路径不该被当成闭合而多出一段收尾连线）；
   ⑥带孔填充（环 + 两个互不相交的正方形）；⑦**半透明**描边的退化接头（见下）；
   ⑧**虚线**（2026-09-28 加）——判据是「**段数 + 墨迹总宽**」这一对：几何取 400 px 长、
   模式 `[10 实, 10 空]`，于是期望精确到 **20 段 / 200 px**（同一段几何的实线是 1 段 400 px，
   **段数差 20 倍**）。只看总宽拦不住"两段各 20px、中间空 20px"，只看段数拦不住
   "每段 1px、空 19px"——**两个一起看**才钉住"周期 20、实段占一半"。
   变异（让 `strokeOutline` 忽略模式）⇒ 实测 **1 段 / 400 px**，**恰好那两条倒**。
   ⑨**路径命中判定**（2026-09-28 加）——`isPointInStroke` 与**画面**逐点对照。
   判据是「**函数的结论**」与「**那一点上的像素**」这一对，两个都要对：只比像素抓不住
   "函数整个写反了"（像素与函数无关），只比结论抓不住"函数与画面不一致"——而那正是
   本变体存在的**全部理由**（它钉的是"复刻接头几何"那条选择）。
   探针**分两类、缺一不可**：**带内对照点**（两种实现都判 true，钉"没整个反"）+
   **尖角判别点**（距折线 21.21px > 半线宽 20px，**只有复刻接头会判 true**）。
   变异（`strokeAndHit` 的 `Join.MITER` → `Join.BEVEL`）⇒ **恰好 ② 那一条倒**，
   读数是"函数说不在、而画面在那儿有墨迹"。
   ③ 与 ④ 之间还断言**像素总数之差**为 100 px（直角处完整接头恰好是 10x10 整块）：
   只断言"外角有像素"会被"整块都画错了"骗过去，差值才能把"接头补上了"与"别的地方也变了"
   分开。
   ⑥ 的判别式同理是"**该露背景的地方有没有露**"（环心）与"**该满的地方有没有满**"
   （每个正方形各自计数）——两者都要，只断言其中之一都拦不住另一类错法。
   ⑦ 守的是"**接头没有被画两遍**"：重复覆盖在**不透明**描边下毫无症状（同色画两遍还是
   那个颜色），唯一的后果是**半透明**时叠加两次更深。场景里同时有**单层**（一次同色同
   alpha 的填充，读出单层色 `#999919`）与**两层**（两段描边带在尖角内侧自相交，
   `#CCCC0C`）——后者在同一帧里证明"两层 ≠ 一层"看得见，于是"退化接头内部必须是单层色"
   那条不是恒真。变异实测：把补发提到限值判断之前 ⇒ 接头框 9 px 全变成两层色，0/9 通过。

   ```bash
   mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
       "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.PathVerifierKt"
   ```

   改**拾取**路径后跑 `PickVerifier`（同样回读像素、断言精确 ID，退出码 0/1）：

   ```bash
   mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
       "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.PickVerifierKt"
   ```

   拾取尤其危险：**错误的拾取不会让任何画面变坏**，只会让点击落在错误的对象上。

   **异步拾取（PBO）那一节（`-- 异步拾取（PBO 读回） --`）**守的是 `pickAsync` 这条
   新路径。`PickBufferTest` 那两条用的是**假 GL**，它把"PBO 读发生在 ID pass 之后"
   当**前提**直接抄像素；而真实 GL 里那是**命令流顺序**。只有真上下文能证的三件事
   ——fence 会不会真的 signal、从 PBO 读回的是不是那个 ID、回调落在哪个线程
   ——都在这里。实测已杀掉的四个变异（每条只让**定向的**几条断言倒，不误伤全篇）：

   | 变异 | 被杀的断言 |
   |---|---|
   | `PickBuffer.enqueueAsyncPixel` 去掉 y 翻转 | 10 条（非零期望全读到 0；期望 0 的照过） |
   | `pickAsync` 的 `set` 改成 `compareAndSet(null,…)`（第一条赢） | 「被覆盖的请求不交付」+「seq7 未交付」 |
   | `resolvePendingPick` 回调不走 `Platform.runLater` | 「全部异步回调都在 JavaFX 线程上」 |
   | `RenderBatch.enqueueAsyncPickPixel` 去掉 `pickBufferValid` 守卫 | 「seq10」读到上一帧残留的 ID |

   ⚠️ **帧计划里所有期望值都按「提交帧 + 1」的场景算**（提交发生在 `resolvePendingPick`
   之后，下一帧才入队），而且**结算要等最后一次提交发生之后**——第一版少了后者，
   报告写着「全部通过」而 `seq10` 那条**根本没提交**。这类"被静默跳过的断言"
   比失败的断言更坏。

   改**鼠标点击闭环**（坐标换算 / 事件接线 / 回调更新界面 / 点击队列）后跑 `ClickVerifier`
   （合成 `MouseEvent` 走 `Node.fireEvent` 的真实事件路径，外加一次 `Robot` 真实点击，
   退出码 0/1）：

   ```bash
   mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
       "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.ClickVerifierKt"
   ```

   它守的核心是**坐标换算**：`MouseEvent.getX()/getY()` 给的是画布节点的**逻辑**局部坐标，
   而 `Gc` 要的是**设备像素**，两者差一个**窗口输出缩放系数**
   （`scene.window.outputScaleY`，即 `GLCanvas.dpi`；本机 125%）。**少乘它，点击会落在
   另一个对象上而画面完全正常**——它的 ★ 一对探针就是为这条设的：同一个局部坐标，
   换算后点中 P、不换算点中 Q，而 P/Q 是两个不同的对象。它另外钉住「纯描边内部不命中」
   「圆的外接框角上不命中」「后画的赢」「消失的对象不再命中」「命中对象带回来的样式
   （是否填充/填充色/边框色/字号）」「回调在 JavaFX 线程且真的更新了界面」、
   **点击队列**（一帧 8 下连点全部交付、按序、每条都对上自己的对象；队列满时丢最旧
   且计入计数器），以及**真实鼠标事件能到达画布**（`Robot` 那一条；合成事件绕过
   JavaFX 的拾取，单靠它证明不了这一点）。变异实测：把换算里的 ×缩放 去掉 ⇒ 41 条倒下；
   把重叠的一对换个绘制顺序 ⇒ 只倒 2 条；改成从 GL 线程发事件 ⇒ 只倒 1 条；
   **把点击退回「最新覆盖旧的」⇒ 恰好倒 7 条**（全是点击队列那两节，18 条探针与
   `Robot` 那条照常通过——说明新断言对这条改动是**定向**敏感的）。
   `NO_FREE_SLOT`（两个 PBO 都忙）在 20 连发下**实测会走到**，且一条点击都不丢：
   取队列用的是 `peek`，只有提交成功才 `poll`。

   > **⚠️ 它的 ★ Robot 那一条是环境敏感的，会偶发假红——别把它误判成回归。**
   > 失败形态很干净：**恰好 1 条 FAIL**，内容是
   > 「真实点击到达画布并命中正确的对象 — 期望=A_FILL_RECT 实际=未收到回调，命中ID=-1」，
   > 而**同一份日志里 FIFO 那 8 下合成点击全部交付**。
   > （恰好 1 条而不是 8 条，是因为 `7a3325b` 修掉了"一次落空污染七条"那件事，
   > 见 `robotPhase` 的注释——**那个条数是这条提示的判据**。）
   > **实测（2026-09-27）**：同一份二进制**先连跑 3 次全红、后连跑 3 次全绿**；
   > 我拿"把 `reportRenderFailure` 的节流还原"做过单变量实验，**两种状态下都是 3/3 同向**
   > ——即**与代码无关**。红的那批紧跟在 MSAA（连开三个窗口）之后，
   > 所以成因是**窗口焦点/叠放**：`fireRobotClick` 过了 `stage.isFocused` 那道闸，
   > 但真实点击在那之后落到了别的窗口上。
   > ⚠️ **别做的事**：看到它红就去 diff 渲染路径。先**单独重跑一次**；
   > 把它排在别的开窗校验器之后跑，它更容易红。

   ⚠️ **两条交付语义不能混**：`pickAsync` 是「最新覆盖旧的」，给 **hover / 拖拽**这类
   **连续量**用；**离散的点击必须走 `clickAsync` / `clickAsyncAtNode` / `onClick`**，
   它们是有界 FIFO、按序交付。曾经把点击也接在 `pickAsync` 上，实测「点一下、几微秒后
   移动鼠标」（真实用户点完往往就会动一下）时 `点击回调被交付 0/8 次`——**8 次真实点击
   全部静默消失**（回调不执行、界面毫无反应、没有任何错误）。队列容量 16，**满时丢最旧
   并计入 `droppedClicks()`**：丢最旧是因为积压 16 帧的那一下早就是过期意图，
   而"点得比帧率快"是正常压力不是程序错误，所以不抛异常——但绝不静默。

   `ClickExample.kt`（`FXGLTransfer` 版，JavaFX Label 反馈）与 `ClickDslExample.kt`
   （`xuan { onClick { } }` 版）是两个可跑的示例。后者的存在是为了证明**从 DSL 那个入口
   也能接上点击**：它没有一处手工搭 `Scene`/`Stage`，也没有一处手写坐标换算。
   它另外钉住 DSL 的**叠加层**（`xuan { onScene { overlay.children.add(label) } }`）：
   命中结果既画在画布上、也写进一个真的 `Label`，启动时做一次结构自检
   （控件在场景图里、叠加层在画布之上），失败就 `exitProcess(1)`——
   "窗口看起来正常"与"控件没接上"在截图里分不出来（控件本来就是空的）。
   **能安全碰场景图的只有 `onScene`（JavaFX 线程、一次）与 `onClick`（JavaFX 线程）**；
   `onInit` / `onRender` 都在 GL 线程上。

   改**文本**路径后跑 `TextVerifier`（退出码 0/1）。它的核心断言是：
   同一个字以 24px 与 192px 绘制时，**边缘过渡带宽度大致恒定**——
   位图被放大时过渡带会随缩放线性变宽，**这是唯一能把"SDF 生效"与
   "位图被放大"区分开的断言**：

   ```bash
   mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
       "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
   ```

   改**图表绘制**路径后跑 `ChartVerifier`（同样回读像素、退出码 0/1）。

   ```bash
   mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
       "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.ChartVerifierKt"
   ```

   它守的核心是那一组**与像素无关**的：整个 ② 的性能主张是「每帧只上传新增的点」，
   而**增量上传与每帧全量重传画出来的图逐像素相同**——所以那条断言观测的是
   **上传字节数**（`ChartRenderer.takeUploadedBytes`），不是像素，
   判据是"每一帧恰好 K×4 字节"。它另外钉住"NaN 必须断开折线""跨环绕时 `baseInstance`
   真的生效（含槽位 0 的镜像）""散点不连线""拾取容差与裁剪""markerSize 退化"、
   以及**频谱**（见下），并且**场景逐帧在变**（有几张实验图只在观察期画、
   有一条系列中途整条消失）——静态场景的校验器有盲区（`PickVerifier` 当时 24 条全绿
   仍漏掉一个真缺陷，见 README 的「测试」一节）。

   > ⚠️ **改「图表交互」（`ChartInteraction` / `drawInteraction` / `trackChartHover` /
   > `ChartPainter` 那三个新方法）时——没有校验器可跑。**
   > `ChartVerifier` 里搜不到 `interaction` / `crosshair` / `tooltip` / `hover`
   > 任何一个词（实测 grep 为空），单测只有 `chart/ChartInteractionTest` 那 2 条
   > （命中最近点、离开绘图区/NaN 不命中）。
   > 也就是说**提示框与十字线的像素、"吸附半径的边界"、"提示框夹取"、
   > "`trackChartHover` 的坐标换算"全都没有闸门**——而坐标换算这一条尤其危险：
   > 漏乘窗口缩放的表现是"十字线落在别的点上"而画面完全正常，
   > 与 `ClickVerifier` 守的那个 ★ 缺陷**是同一类**（那条的变异实测能让 41 条倒下）。
   > 动这块之前，先按「图表交互」一节末列的三条判别式补上断言。

   **左/右图例、底部标题、轴标题、带子边界那一节**（"★ 左/右图例…"）是另一组此后要维护的：
   五个变体共用一个 108×38 的矩形、**按帧轮换**（一块地方只装得下一个变体），
   各自在自己那一段的最后一帧抓快照。判别式都是手算的、且刻意只用与字体无关的量
   （带子高、色块位置、绘图区上下边缘）——左右图例的**带宽**与字体有关，所以那几条
   只钉"色块贴哪条边""文字在色块右边""绘图区从带子之后开始"，不写固定的列号。
   这一节同时钉住了新能力：`LINE_AND_MARKERS` 的两个半边（点的两块 6×6 与线的
   52×2 列）、轴标题居中、刻度预留真的挤过绘图区、以及超长系列名**被切断而不是画到界外**。

   **频谱那一组（`ChartVerifier` 的"★ 频谱"一节）值得单独说一句**：它的判别式是
   「相邻两个 bin 之间那一列上的墨迹落在**哪一行**」——两个 bin 的幅值之间的中点，
   还是只落在左边那个的高度上。**"峰值在正确的 bin、高度也对"这类断言抓不住**
   "第二个实例属性的偏移写成 0"：那样每一段退化成**水平小横线**，而**峰那一列的最高
   有色行仍然在顶边**。这与折返图那条判据（见 `ZIG_VALUES`）是同一件事。
   实测：把 `SpectrumSeriesRenderer` 里第二个属性的偏移从 4 改成 0，
   **只有这两条 ★ 断言失败**（其余 63 条照常通过）。

   改**抗锯齿**路径（`Gc.antialias` / `aEdge` / `vEdge` / `uAntialias` / 描边的几何外扩）
   后跑**三处**，缺一不可：

   - `PipelineVerifier` 的 **★ 抗锯齿一节**（四条判据 + 1px 细线，帧号 `AA_FRAME`）
     与 **★ 非等比缩放一节**（帧号 `ANISO_FRAME`，钉降级的方向）；
   - `ChartVerifier` 的 **★ 图表系列的解析式抗锯齿一节**（六个图型的 `uAntialias`
     + 折线的四条判据 + 交叉验证）；
   - `MsaaVerifier`——它**要跑三次**（`-Dxuan.probe.msaa=0`、`4`、**`-1`**）
     ，**由脚本比对**（`-1` 那一档见脚本顶部：按 openglfx 的约定负数 = 最大采样数，
     所以它又是一条多采样路径，也是**守卫最容易判错**的一档）：

   ```bash
   bash xuan-javafx/scripts/msaa-verify.sh        # 退出码 0/1；内部用 trap ... EXIT 清理
   ```

   `MsaaVerifier` 守的是**另外三件事**：① 快照路径与 `glReadPixels` 路径的交叉印证
   （`msaa` 非 0 时后者非法，前者照常）；② **回读守卫的前提**
   （`canReadPixels == (glGetError == 0)`）；③ 跨进程的两条读数
   （`msaa=4` 与 `msaa=-1` **各比一次**：过渡像素 **1782 > `msaa=0` 的 0**、
   线心纯色数 **2673 == 2673**——两个多采样档的读数逐项相同）。
   ⚠️ **它的快照必须按设备缩放取**（`SnapshotParameters` 给 `Scale(deviceScale)`）：
   不这么做的话快照是**逻辑尺寸**（892×692），对设备分辨率的纹理做**重采样**，
   而**重采样自己会产生中间值**——实测那一版的整幅图有 **`msaa=0` 36 种**、
   **`msaa=4` 173 种** RGB 值，而正确版是 **`msaa=0` 3 种**（纯色三样）、
   **`msaa=4` 6 种**（多出 0.25/0.5/0.75 三个四分档的中间值）
   ⇒ 判据分不清"过渡像素"是 MSAA 的还是重采样的。
   ⚠️ **四个数都得带条件**：写成"正确版是 3 种"而不写 `msaa=0`，就是把两次读数混成了一句
   （本条的上一版正是这么写的：限定词只加在了后一半上）。

   ★ 八个校验器的采样数都从**同一个系统属性** `-Dxuan.probe.msaa` 读（解析与
   `MsaaVerifier` **共用一份**，见 `example/MsaaVerifier.kt` 的 `readRequestedMsaa`）
   ⇒ **`-Dxuan.probe.msaa=4` 会让它们在那道守卫上明确拒绝并以 1 退出**。
   这一条是刻意的：在那之前八个入口全写 `FXGLTransfer()`，`msaa` 恒为默认 0，
   于是"明确拒绝"**只在有人改源码时才可能触发**——守卫是**死代码**。

   改 **FFT / 频谱的数据来源**后跑 `FftVerifier`（退出码 0/1）。
   它**不画任何东西**——要的是 GL 上下文，不是像素：

   ```bash
   mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
       "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.FftVerifierKt"
   ```

   它守的是"**FFT 算对了**"（**50 条**断言）：已知频率的纯正弦峰值落在正确的 bin、
   与一份**朴素 O(N²) DFT 逐 bin 比对**（那份参考刻意不写成 FFT——它显然是对的，
   拿另一份可能同样写错的 FFT 当参考就成了两个未知数互相印证）、
   单位幅度读回 1.0、**换窗不改变幅度读数**、跨环绕取样本仍然正确、
   `MIN_N` 与 `MAX_N` **两个端点**都跑。另有两条是给"已知盲区"正面封堵的：
   **矩形窗下的绝对下限**（"逐 bin 比对"的分母是峰值，于是真值远小于门槛的 bin
   完全不设防——删掉 Nyquist 那一处写入时，没有它那 47 条可以全绿）与
   **"非矩形窗 × 跨接缝"的组合**（矩形窗的 `windowAt` 恒等于 1，窗的下标取哪个都对，
   单独跑"只用窗"或"只跨接缝"都盖不住它）。
   **像素那一半不在它这里**——频谱画出来的位置由 `ChartVerifier` 钉（见上）。
2. **改了断言或修了 bug，做变异验证**：把 bug 重新注入，确认校验器真的失败。
   （校验器里那条"反证"断言就是这么来的——避免覆盖性检查恒真、变成橡皮图章。）
3. **⚠️ 变异注入在 `xuan-render-gl` 上时，还原之后必须 `mvn -o install -DskipTests -pl xuan-render-gl`。**
   校验器是用 `-f xuan-javafx/pom.xml` 跑的，`xuan-render-gl` 从**本地仓库**解析；
   注入时 install 过，**还原时不 install 就还在跑变异版本**。实测踩过：还原源码后连跑
   三次全红、且三次输出**逐字符相同**，看起来像校验器"飘"，其实是本地仓库里的
   残留变异。判据：输出完全一致地失败 ⇒ 先怀疑产物，不是怀疑随机性。
   （`xuan-javafx` 自己的改动不必 install——那是当场编译的。）
4. 校验器依赖"用户坐标 1:1 映射到设备像素"这一前提。若将来引入真正的 DPI 缩放，
   它的期望值需要乘以缩放系数——那时它会失败，正是它该提醒的。
5. **做性能测量时，绝对毫秒数在这台机器上单独拿出来不可比。** 笔记本 GPU 是负载驱动
   升频（SM 270→2002 MHz 随负载游走，温度只有 50–65 °C）。**同一个 N 在不同时钟状态下
   能差 1.5 倍以上**，而它制造的最典型的假象是"实例数翻 3 倍、时间只涨 4%"这样一个
   **看起来像物理饱和的平顶**——实测第一轮就被它骗过一次。要比就比**同一轮内**的相对
   关系，或同时记录 `nvidia-smi` 的 SM 时钟；`example/ChartPerfProbe.kt` 支持
   `-Dxuan.probe.rounds=2` 做倒序第二轮，专门用来把"N 越大"与"跑得越晚"拆开。

## 已实现 vs 未实现

**可用（纯计算，无需 GL，最适合写测试）**
`geom/Path`（含零分配变更器）、`geom/Flattener`（二次/三次贝塞尔细分）、
`geom/Tessellator`（凸扇形 + 凹耳切 + 孔洞桥接 + 按包含关系分类的多轮廓）、
`geom/StrokeGenerator`（端点/接头/虚线）、
`math/{Vec2,Mat3,Transform}`、`util/{Color,Rect}`、`renderer/ViewTransform`、
`text/SdfGenerator`（覆盖度位图 → 有符号距离场）、`text/TextLayout`（槽位序列 → 四边形顶点）、
`chart/` 全部（数据容器、轴与刻度、配色 LUT、图表装配——见「图表」一节）、
**图表交互**：`chart/ChartInteraction`（指针状态 + 逐样本命中器）、`ChartHover`、
`ChartInteractionConfig`（13 字段 record，紧凑构造器校验）、`ChartValueFormatter`
（`@FunctionalInterface` + `DEFAULT`）——见「图表交互」一节；
`gpu/FftWindow`（四种窗 + 相干增益补偿，**全项目唯一的一份**，纯算术）

**可用（依赖 GL 上下文）**
`gl/ShaderProgram`、`gl/Texture`、`gl/LwjglGLAbstraction`（`initialize()`/`dispose()` 是诚实的
no-op，因为上下文已由 `GLCanvas` 置为当前）、`renderer/RenderBatch`、
`renderer/Gc` 的形状与路径方法、
`gpu/ComputeShader`、`gpu/FftKernel`（一次 dispatch 干完全部：取数 → 加窗 → 位反转 →
`log2(N)` 级蝶形 → 幅度，单 workgroup 全在 shared memory 里做；输出是 `N/2+1` 个 bin 的
半谱）、
`chartrender/` 的装饰（`ChartLayout` 的轴标题带与刻度预留、`ChartPainter.begin(Rect)` 的
带子裁剪）、`dsl/Xuan` 的 `overlay` / `onScene`（把 JavaFX 控件放进 DSL 应用）、
**路径命中判定**：`Gc.isPointInPath` / `Gc.isPointInStroke`（CPU、任意线程、无需 `pickId`）
+ `geom/PathHit`（纯计算，见「路径命中判定」一节）、
`renderer/PickRegistry`（ID 分配与 `id→对象` 映射，纯内存可单测）、
`renderer/PickBuffer`、`PickHit`、`Gc` 的 `pickId` / `pickable` / `pick` / `pickRect`、
`FXGLTransfer` 的 `pickAsync` / `pickAsyncAtNode`（hover，最新覆盖旧的）与
`clickAsync` / `clickAsyncAtNode` / `onClick`（点击，有界 FIFO + 丢弃计数），
`renderer/Material`（材质选择位）、
`text/FontFile`（stb 的字体与度量封装）、`text/GlyphRasterizer`、`text/GlyphAtlas`（R8 图集）、
`Gc` 的 `fontSize` / `drawText` / `measureText`、
`chartrender/` 全部（`ChartRenderer`——入口是 `Gc.charts`、`LineSeriesRenderer`、
`ScatterSeriesRenderer`、`SpectrumSeriesRenderer`（频谱，`ChartType.SPECTRUM`）、
`SeriesBuffer`、`SeriesShaders`；用法见「图表」一节）、
**图表交互的绘制与接线**：`ChartRenderer.drawInteraction`、`ChartPainter` 的
`strokeLine` / `strokeDashedLine` / `strokeRect`、`FXGLTransfer.trackChartHover`
（**没有任何校验器覆盖**，见「图表交互」一节末）、
**`FXGLTransfer.onError`**（GL 线程上的渲染异常处理器）、**`Gc.abortFrame()`**
（帧中途失败后恢复，供桥接层调用）、
**抗锯齿**：`Gc.antialias`（描边 + 六个图表渲染器，默认关）、顶点属性 `aEdge`
（横向/沿向，`VertexFormat.OFFSET_EDGE` = 24）、图表侧的 `vEdge` + `uAntialias`、
`FXGLTransfer(msaa = N)` 与 `FXGLTransfer.canReadPixels`（回读拒绝守卫）、
DSL 的 `xuan { antialias { msaa = 4 } }`——见「抗锯齿」一节

**未实现 / 待办**
- **其余图型的渲染器**：`HEATMAP` / `WATERFALL` 目前一律**抛异常**
  （`chart/` 里有这两个 `ChartType`，但没有渲染器）。属于 ③ 或更后面的事。
  （`LINE_AND_MARKERS` 的标记点那半**已经接上**了：`LineSeriesRenderer` 持有
  `ScatterSeriesRenderer` 并调它的 `renderMarkers`，复用的是同一份几何与拾取热区。）
- **饼图没有 `ChartType` 常量，也没有渲染器**，而且它塞不进现在的 `Chart` 模型：
  `Chart` 至少要一根轴、`ChartRenderer.draw` 至少要两根（0 号是数据下标、1 号是数值），
  而饼图没有笛卡尔轴；每个扇区的**标签与颜色**也没有地方放（`Series` 只有名字与
  主色，`AxisRange` 的 name/unit 是**按维度**而不是按数据点的，`ArrayChartData`
  的 name 又是全局的）。真要做得先回答"扇区标签放哪"，
  那是数据模型上的一个决定，不该硬塞。
- **轴标题已经实现**（`Chart.axisTitlesVisible` + `Chart.tickLabelReserve`，
  模型在 `chart/`、绘制在 `chartrender/`），见「图表」一节。
  它**默认关着**，理由见那里。刻度文字仍由调用方画。
- **非线性轴的 GPU 路径**：`LOGARITHMIC` / `TEXT` 轴在 `ChartRenderLayout` 里明确抛异常。
- **填充的解析式 AA：不做**。`fillRect` / `fillCircle` / `fillPolygon` / `fillPath`
  这类**填充**没有解析式抗锯齿（`Gc.antialias` 对它们**无效**），它们的手段是 `msaa`；
  判据是"几何知不知道自己的中心线"（三角形汤里没有中心线，见「抗锯齿」一节）。
  **★ 一条从未验证过的推断，现在被实测取代了**——记在这里因为它是这条待办的立项理由：
  `docs/superpowers/specs/2026-09-11-xuan-render-pipeline-design.md` §9 写着
  "抗锯齿：MSAA。`GLCanvas` 构造函数已提供 `msaa` 参数，**零额外实现成本**"。
  **那是推断，不是实测**：`GLCanvas.Defaults.MSAA` 是 0，分支起点（`18bcf33`）全仓
  **11 处构造点**（10 个示例/校验器各一处 + `Xuan.kt` 一处）**没有一处传过 `msaa`**，
  这条路**一次都没跑起来过**（本仓库有前科：`GPUFFT.java`
  当年也是"能编译"被当成"现成可用"）。实测结论（`example/MsaaVerifier.kt` 钉着）：
  - ✅ **"开启"那一侧成立**：`msaa=4` 真的建出多采样 FBO（`GL_SAMPLE_BUFFERS=1` /
    `GL_SAMPLES=4`，本机 `GL_MAX_SAMPLES=32`）、画面正确（4px 探针线的两个过渡行是
    `#CCCCCC` 与 `#666666`——正好是 4 个子样本的四分档 0.75 / 0.25）、
    **拾取逐项不受影响**。
  - ⚠️ **但有一笔没人记过的连带成本**：**`msaa` 非 0 时画布 FBO 上 `glReadPixels` 非法**
    （`GL_INVALID_OPERATION`，读回全 0）⇒ **靠画布 FBO 回读的那七个像素校验器立刻
    全部失去读数能力**（`Pipeline` / `Path` / `Pick` / `Click` / `Text` / `Chart` / `Axis`；
    **`FftVerifier` 不在此列**，理由见下一条）。所以 `msaa` 默认必须是 0，
    且八个校验器入口都有 `requirePixelReadback` 守卫——**统一挂是刻意的**：
    那个例外将来若改成画像素，守卫已经在了。
  - ⚠️ **"零额外实现成本"假在哪里**：成本不在实现，在**测量**——`msaa` 非 0 之后本仓库
    最重要的那套验收手段（那七个像素校验器的读数能力）整个失效。
    **"零成本"是只算了实现那一半。**
  - ⚠️ **"全部七个"是一句过度概括**（设计文档 §6.2 原来就是这么写的，
    2026-09-26 已**就地更正为"六个"**；这里留一句是因为那个概括很容易再被推一遍）：
    `FftVerifier` **不画像素**，它读的是 SSBO（`glGetBufferSubData`），那条路与画布的
    采样数无关 ⇒ 把它挂在 `msaa=4` 下跑，**50 条断言全过、输出与 `msaa=0` 逐行相同**
    （只有耗时读数会漂，那是 GPU 时钟）。它挂同一道守卫是**口径统一**
    （七个一律 `msaa=0`），**不是实测的必要性**——两者别混。
    **一般化**：把一个在若干实例上观察到的关系推广成全体，是"没把最后一个实例也查一遍"；
    本会话里同类的还有"方形窗多出角项"与"AA 关的对照组"两处。
- **GPU 计算**（子项目 D-③）：FFT（**③-1 已完成**）、降采样、包络、密度累积（数字荧光）。
  - **③-1 的实现是 `gpu/FftKernel.java`**（`#version 430`，Cooley-Tukey radix-2 + SSBO，
    单 workgroup、全在 shared memory 里做），配 `gpu/FftWindow`；**`FftVerifier` 50 条全绿**
    （退出码 0）。绘制那一半是 `chartrender/SpectrumSeriesRenderer` +
    `ChartType.SPECTRUM`，见「图表」一节的「频谱」。
  - **`gpu/GPUFFT.java` 仍然在那儿，仍然是死的（没人引用），不要拿它当参考实现。**
    当年的 `GpuFftVerifier`（已改造成今天的 `FftVerifier`）实测它有**三层缺陷**：
    1. **默认着色器编译不过**：`uint half = 1u << u_stage;` 里 `half` 是 GLSL **保留字**，
       于是 `new GPUFFT()` 直接抛 `RuntimeException`——**它从来没有成功运行过一次**。
    2. **改掉①之后仍然空转**：三个 uniform 声明成 `uniform uint`，而 `dispatchFFT` 用
       `glUniform1i` 赋值 → `GL_INVALID_OPERATION` 且**值不生效** → `u_N` 恒为 0 →
       每次都提前返回，输出**逐位等于输入**。
       （同一个坑 ② 在 `uPickId` 上踩过一次，已写进「绘制后端」一节。）
    3. **改掉①②之后算法仍错**：DIT 蝶形要求输入先做位反转，而那 6 行算出来的 `rev`
       **从头到尾没被引用过** → 输出等于 `DFT(输入按位反转)`，峰值落在错误的 bin 上。
  - **③-2 降采样与包络、③-3 密度累积（数字荧光）：明确不做**，留待下个版本后期。
    **这不是"忘了"，是实测之后决定不做**，理由如下（2026-09-24 实测，见
    `example/ChartPerfProbe.kt`）。

    **降采样的两个立项理由都被测量否掉了：**

    1. **性能理由不成立。** 在 GPU 时钟走平的条件下（SM 恒 1980 MHz），成本**精确正比于
       可见实例数**：300k 可见 = **0.76 ms**、1M = **2.16 ms**、3M = **7.09 ms**
       （GPU 之比 1 : 0.305 : 0.107，实例数之比 1 : 0.333 : 0.100；
       都是 `GL_TIME_ELAPSED` 只包 `charts.draw` 的 GPU 时间）。
       **100 万可见点占 16.7 ms 预算的约 13%**，也就是说"让百万点能显示"**已经是真的**。
    2. **画面理由也不成立。** 100 万样本摊在 600 px 绘图区上，**每列约 1667 个样本**，
       折线本来就把完整的 min–max 包络画出来了（斜坡实验里那道 6~7 px 宽的墨迹带
       就是证据）。换成每列 min/max 竖条，**画出来是同一张图**。

    **★ 立项时不要拿参考项目当理由。** chart-fx 有**完整的降采样子系统**
    （`datareduction/`：5 个缩减器实现 + `ReductionType` 枚举；实测两个默认值——
    `minRequiredReductionSize` = **5 点**、`minPointPixelDistance` = **6px**），
    所以"看看它怎么做"必然得出"该做"。**但它是<u>有损</u>的**：
    `DefaultDataReducer` 把**均值**写回几何（`yValues[count] = (int)(meanY / ncount)`），
    而 min/max 被塞进**误差棒数组**（`yPointErrorsNeg[count] = maxY;
    yPointErrorsPos[count] = minY`）；**折线只读均值**（`ErrorDataSetRenderer` 的
    `gc.strokePolyline(points.xValues, points.yValues, points.actualDataCount)`）。
    ⇒ **折线画的是"取整后的均值"**——被吸收的那一段上，尖峰不再出现在折线上。
    这与本项目最在意的"缺口不能连过去，那条直线是假的"是同一种病。
    **所以：真要做 ③-2，必须保留每列 min–max、不许取均值或抽稀，
    且必须有一条「单样本尖峰不被吃掉」的像素断言。**

    **⚠️ 证据强度照实说（2026-09-25 复核）**：
    - 上面全部是**从代码路径读出来的，没有跑出像素证据**——据它立规矩之前
      **应先补一条复现**。
    - 评审 §4.5.1 的三条支撑里有一条**站不住**：它写"`ErrorStyle.NONE` 是默认"，
      而实测默认是 **`ErrorStyle.ERRORCOMBO`**，该模式会拿**那两个误差数组**去画
      误差棒/误差面（`drawErrorSurface` 读的就是 `points.errorYNeg`/`errorYPos`）
      ⇒ **默认配置下那个尖峰（作为误差面）仍可能画出来，未必"在画面上消失"**。
      即"折线读均值"成立，"所以尖峰看不见"这条**未证、且已有反证**。
    - **结论里不变的部分**：它的降采样**是有损的**（几何 = 均值），
      "照它做"确实会把均值化抄进来。要改的是证据强度，不是结论。
      出处：`docs/superpowers/specs/2026-09-24-xuan-architecture-review.md`
      §4.5.1、§4.5.5。

    **什么时候该重新考虑**：可见点到 **3M 就是 7.09 ms**（吃掉近一半预算），那才是
    阈值该出现的地方——那时做的是"可见点 > 某个大于 1M 的值才切竖条"，不是原设计的
    "可见点 > 绘图区像素宽"。③-3 数字荧光是**另一件事**（强度分级的显示是一个产品特性），
    它不靠"画不起"立命，要做时按它自己的理由做。

    **⚠️ 这批数字怎么读。** 这台笔记本 GPU 是**负载驱动升频**：SM 时钟在 270→2002 MHz
    之间随负载游走（温度只有 50–65 °C，`Thermal Slowdown` 全程 Not Active）。
    **同一个 N 在不同时钟状态下能差 1.5 倍以上**，而"实例数翻 3 倍、时间只涨 4%"
    这种平顶正是它造出来的假象——**不要把它读成物理饱和**。实测第一轮就撞上过这个坑。
    所以：**这台机器上，绝对毫秒数单独拿出来不可比**；要比就比**同一轮内**的相对关系，
    或者同时记录 `nvidia-smi` 的 SM 时钟。探针跑法见文件头，三种模式（像素斜坡 /
    冷热对照 / 全量扫描）都支持 `-Dxuan.probe.rounds=2` 做倒序第二轮。

    **顺带一个与图形管线有关的结论**：瓶颈在**前端**（图元装配/光栅化），不在 SM
    ——同一轮里 SM 时钟差 1.76 倍而时间只差 1.25 倍。**加着色器工作量便宜，加实例数贵。**
    （只有两点支撑，当提示用，别当定论。）
- **误差棒、等高线、眼图**：`ChartType` 目前没有覆盖，属于 ③ 或更后面的事。
- **Paint / 渐变**：所有绘制只接受纯色整数。设计意图是**所有 Paint 归一化为纹理**
  （纯色 = 超白色纹理 + 顶点颜色，渐变 = 1×256 LUT）。
- **`createTexture` 已在 LWJGL 入口完成 ARGB→RGBA 通道转换**；新增纹理类型时继续沿用
  `0xAARRGGBB` 公共 API 契约，并为新上传路径补充字节序测试。
- ✅ **已修**：`Gc.strokePath()` 现在按 `Flattener` 的子路径**逐段独立描边**，
  多条子路径之间那段并不存在的连线没有了。判据（末条命令是否为 `CLOSE`）**查的是
  `Path` 的命令表**，不是看点集——平铺后的点集里，`CLOSE` 追加的起点与"用户自己
  `lineTo` 回起点"产生的末点**逐位相同**，光看点分不出来。
  **`PathVerifier` 钉着它**（详见「怎么验证改动」一节），变异实测（每条只让**定向的**几条倒下）：
  ①把 `subPaths == 1` 改成 `subPaths >= 1` ⇒ 3 条倒下，其中一条直接量到那段假连线（84 px）；
  ②把 `lastCommandIsClose()` 的 `CLOSE` 分支改成 `false`（等价于回到改动前的开放收尾）
  ⇒ 恰好 2 条倒下——「收尾顶点外侧的 4x4 被填满」实测 0/16、「两个变体只差那一个接头」
  实测差 0（应为 45）。
- **闭合子路径的收尾接头已修且有端到端断言**。同一处改动让闭合子路径按 `closed = true`
  收尾（以前只落平头封口，尖角外侧留小缺口）。它的**反面**也有断言：把单子路径那一路的
  `closed` 改成恒 `true` ⇒ 5 条倒下，假收尾斜线被量到 84 px。
  `StrokeGeneratorTest`（"末点重复起点时闭合描边与去重后等价"）只钉住生成器那一侧，
  `Gc.strokePath` 这层的接线由 `PathVerifier` 的变体 ③④⑤ 钉。
- ✅ **已修**：`StrokeGenerator` 的 **MITER 接头曾只发"接头底边之外"那半个三角形**，
  漏掉"顶点与接头底边之间"那半个（= BEVEL 发的那个），于是每个尖角内侧缺一块，
  面积 = 半线宽²/2（直角处）。线宽 20 时每角 50 px²；线宽 1 时只有 0.125 px²，
  所以细线几乎看不出来，缺陷活了很久。**面积断言也拦不住它**：闭合直角方框四角合计
  200 px² 相对 16000 只有 1.25%，落在 `PipelineVerifier` 那条 5% 容差里。
  现在补全成**风筝形四边形**（顶点→偏移点→尖角→偏移点，两个三角形）。
  两条分支（正常 miter 与超限回退 bevel）各自只发一次、共享的只有底边那条线，
  **不会重复覆盖**；`miter超限回退斜接时不会把底边三角形画两遍` 钉着这一点
  （判据取三角形**个数**——重复的三角形面积不变，只有半透明描边会叠加两次颜色）。
  "不会画两遍"另有两条**覆盖层数/像素**判据（个数只是代理）：
  `miter超限回退斜接时底边三角形的内部只被覆盖一次`（逐点扫接头内部，层数恒为 1）
  与 `PathVerifier` 变体 ⑦（半透明下接头框必须是单层色）。变异（把补发提到限值判断
  之前）实测：个数判据 5 vs 6、层数判据报"层数 2"、像素判据 0/9 单层色——三条同时倒。
  变异实测：①删掉补发那一行 ⇒ 单测 1 条 + `PathVerifier` 3 条倒下（方框总面积
  15800→16000、跨变体差值 45→100；`PipelineVerifier` 的蓝描边 3068→3084，
  解析期望 3090，即向解析值收敛）；②把补发提到限值判断**之前**（模拟"无条件补发"
  的错误修法）⇒ 恰好 1 条倒下（`miter超限回退斜接时不会把底边三角形画两遍`，
  5 vs 6 个三角形）。
  **顺带一条容易算错的公式**：miter 长度是 `half / sin(内角/2)`，其中的角是
  **两段之间的内角**（不是转向角）——按转向角算会把浅转角误判成远超限值。
  实测：圆角矩形描边（线宽 4）的 28 个接头内角 165°（转向仅 15°），
  miter 长度只有 2.02 ≪ 阈值 8，所以它们**全部走 MITER**。
- ✅ **已修**：`Gc.fillPath()` 现在把**每个子路径当成一条独立轮廓**，谁是外轮廓、谁是洞
  由 `Tessellator.tessellateContours` 按**包含关系**判定（嵌套深度为偶数的是外轮廓、
  奇数的是洞，洞归给"深度正好比它小 1"的那个外轮廓）。
  以前是所有子路径拼成**一个**多边形：环图被填成实心，而排在后面的块会整块消失
  ——两者都不报错、画面只是"多了一块/少了一块颜色"。
  **为什么不按位置约定**（"第一个子路径作外轮廓、其余作洞"）：一条路径里画两个**互不
  相交**的圆时，第二个圆会被当成洞，而它落在外轮廓之外——那是 `tessellateWithHoles`
  契约里的病态输入，结果是**静默丢掉一块面积**。
  变异实测：①按位置约定分类 ⇒ 4 条单测 + 4 条像素断言倒下（两个正方形整块消失）；
  ②忽略嵌套（每个轮廓各自成块、不收洞）⇒ 4 条单测 + 3 条像素断言倒下（环心被填实）。
- 没有黄金图像测试。
- **`example/demo/` 的交互式 demo 暴露、但本期只记录的缺口**（`XuanDemo.kt` 是它们的绕法样板）：
  - **`xuan { }` 只暴露 `overlay`，不暴露画布节点**：菜单栏**能**加
    （`onScene { (it.root as BorderPane).top = menuBar }`——`createMainView()` 返回的正是
    `Scene` 的根 `BorderPane`），但画布是 `StackPane(view, overlay)` 的第一个孩子，
    只能从 `overlay.parent` 掏。拖拽 / hover / 滚轮都要接在它上面。
    鼠标入口也只有 `onClick` 一个。
  - **没有异步 `pickRect`**：框选只能同步调（`w×h×4` 字节的 `glReadPixels`），
    不能在 JavaFX 线程做。demo 的绕法是 `onFrame` 里读 volatile 标志。
    **★ 附带一条：`onFrame` 跑在 `endFrame()` 之前，那里读到的 ID 缓冲是上一帧的**
    ——场景静态时等价，动态时必须走 `pickAsync`。
    **★ 更准的说法是"库明确拒绝给"，不是"给陈旧的"**：`RenderBatch.beginFrame` 会刻意把
    `pickBufferValid` 置回 `false`，所以 `onFrame` 里调 `pickRect` / `pick` 是**恒返回空**
    ——框选会永远选不中任何东西且不报错。正确位置是 `FXGLTransfer.onRender`
    （跑在 `endFrame()` **之后**、`pickBufferValid` 为 `true`）。
    **★ 顺带一条命名陷阱（比缺口本身更值得记）**：`Xuan.onRender` 转发到的是
    `bridge.onFrame`，即 `endFrame()` **之前**那个——与 `FXGLTransfer.onRender`
    **同名不同时机**。照名字找"帧末回调"的 DSL 用户会拿到早一步的那个，
    于是**区域拾取在 DSL 路径上恒返回空**（单像素有 `pickAsync` / `clickAsync` 兜着，区域没有）。
  - **`Gc` 没有公开 `ChartTextMetrics`**：自己算布局要重写一遍口径
    （`lineHeight = fontSize × ChartLayout.LINE_HEIGHT_FACTOR`）。重写歪了网格与标题带就
    对不上，而画面只是"看着有点挤"。demo 里的 `GcTextMetrics` 就是这份重写。
  - ✅ **已修（2026-09-28）：`Gc` 公开虚线描边**——见下面新起的那一条；
    原文保留是为了说明它当时是什么样子。**现在应用层不需要再重写弧长切分了。**
    历史：`StrokeGenerator.strokeDashed(...)`（弧长切分、dash 模式 + 相位）
    **存在且有单测**（`StrokeDashTest`），但 `Gc` 的描边路径只调实线那个 `stroke(...)`，
    于是任何想画虚线的应用都得在应用层重写一遍——`example/demo/DemoShapes.kt` 的
    `strokeDashedPolyline`、`Main.java` 的 `strokeDashedCircle`，两份。
    与「`Gc` 没公开 `ChartTextMetrics`」同族：**能力在库里、但没接到公开 API 上**。
    **那两份现在都已删除**（见下面那条），`ChartTextMetrics` 那条**仍然在**。
  - **没有"可拾取图元列表"抽象**：每个应用都要自己维护
    `列表 + 可变 pickId + register/unregister 配对 + 选中集的跨线程可见性`。
  - ✅ **已修（2026-09-28）：`Gc` 公开虚线描边——状态字段，不是每个形状加一个重载。**
    `gc.dashPattern = floatArrayOf(6f, 4f)`（偶数下标实线、奇数下标空白；`null` = 实线）
    与 `gc.dashPhase = 0f`，**两个都进 `save`/`restore` 栈**，与 `lineWidth` / `antialias` 并列。

    **为什么是字段**：本类所有描边入口（`strokePath` / `strokePolyline` / `strokeRect` /
    `strokeCircle` / `strokeEllipse` / `drawLine`）**都汇进同一个 `strokeOutline`**
    ⇒ 一个字段让它们**全部**支持虚线；做成 `strokeXxxDashed` 要加六份、还会漏
    （漏掉的那个不报错，只是一条实线）。这也与 HTML Canvas 的 `setLineDash` 同形。

    **三处必须接对的地方**（都是"错了不报错"的那种）：
    1. **setter 要拷贝数组**：不拷的话调用方改自己那个数组会**静默改掉已压进栈的历史状态**，
       `restore()` 恢复出来的不是当时那个模式，而画面只是"虚线看着不太对"。
    2. **`restore()` 不能走 setter**：那会在每次恢复时拷贝+重新校验，把
       "save/restore 稳态零分配"这条承诺作废。所以有一个私有后备字段
       `dashPatternValue`，内部读写一律走它。
    3. **AA 的 `capExtension` 要一起传**：虚线每一格是**独立的开放两点折线**，
       沿向在整格上是常量 0 ⇒ 不外扩时端头没有沿向羽化、会退化成硬边。
       实线那条路靠的是同名参数，两条路是同一个。

    **栈里多了一个数组**（`styleDashPatterns`，存引用不存拷贝——模式在 setter 里已经拷过），
    所以 `ensureStyleCapacity` 多了一处扩容。⚠️ **漏掉它不会立刻出错**（只是 save 到第 8 层
    之后越界抛异常），最容易在改动里被忘记。

    **应用层的两份绕法都删了**（它们正是为同一个缺口写的）：
    - `Main.java` 的 `strokeDashedCircle`（约 30 行，还得自己防除零）⇒
      `setDashPattern(...)` + 一次普通 `strokeCircle`；
    - `DemoShapes.strokeDashedPolyline`（约 100 行）+ 它自带的两道守卫
      （`isUsableDashPattern` / `MAX_DASH_SEGMENTS`）+ `DemoShapeMath` 里钉那两道守卫的
      **9 条断言** ⇒ `gc.dashPattern = floatArrayOf(on, off)` + 一次普通 `strokePolyline`。
      `DemoShapes.kt` 507 → 373 行；`selfCheckShapeMath` 的断言 21 → **12 条**。

    ⚠️ **那两道守卫不是白删的，是被库里的修复取代了**：它们防的是"pattern 太小 ⇒
    迭代无上界 ⇒ GL 线程卡死"，而同一个死循环在 `StrokeGenerator` 里已经修掉、
    并且有了自己的判据（`StrokeDashTest` 那条带 `@Timeout` 的）。
    **顺序不能反**：先删守卫再接公开 API 的话，那段窗口里演示程序是可以被一行配置卡死的。

    ⚠️ **`previewDashedFrames` 那个末尾探针保留**：它证的是"这一段**接线**通了"
    （锚点 → onMove → 轮廓 → 描边返回了），而 `PathVerifier` 第 8 个变体证的是
    "虚线**画得对**"——两件事。`drawDragPreview` 跑在 GL 线程上、帧缓冲读不回来，
    所以只有那个计数能证明这一帧跑到了底。

    ### ★ 接上公开 API 时**暴露出的一个死循环**（根因已修）
    `StrokeGenerator.strokeDashed` 的内层循环有一支是
    `if (dashLen <= 1e-6f) { consumed = 0; patternIndex++; continue; }`
    ——"本格太短、不占弧长，跳到下一格"。它**只推进 `patternIndex`、不动 `cursor`**。
    若模式里**每一项都 ≤ 1e-6 而总和恰好 > 1e-6**（例如 `[6e-7, 6e-7]`），
    那条"全零模式"的早退**不触发**，于是这一支**永远转下去**：`cursor` 一步不动、
    循环永不退出 ⇒ **GL 线程整个卡死、画布永久冻结，且没有任何报错**。

    修法是给那条早退加**第二个条件**：`|| longestDash <= 1e-6f`
    （`longestDash` 是模式里最大的那一项）。语义上也对——每一项都小于阈值 ⇒
    一格实线都发不出来 ⇒ 什么都不画。

    ⚠️ **可达性是这次改动带来的**：在那之前唯一的调用方是图表十字线，dashLength 由
    `ChartInteractionConfig` 强制为正 ⇒ 这一支**不可达**；`Gc.dashPattern` 一公开，
    `gc.dashPattern = floatArrayOf(6e-7f, 6e-7f)` 就够得着它了。
    **这正是"接一根线"类改动的典型风险：被绕过去的守卫会跟着一起失效。**
    demo 那份 `isUsableDashPattern` / `MAX_DASH_SEGMENTS` 当初就是为同一件事写的
    ——绕法自带守卫，而接到公开 API 之后守卫没了。

    判据：`StrokeDashTest.每一项都低于阈值的模式不产生三角形而不是死循环`，
    **带 `@Timeout(5)`**——因为它在修复前的行为是**挂死**而不是失败，不带超时会把
    `mvn test` 整个吊住（"测试挂住"比"测试失败"难查得多：没有栈、没有读数）。
    变异（去掉 `longestDash` 那一半）⇒ 该条报错，其余 5 条照过。
  - **`xuan-javafx` 跑不了单测**：pom 里没有 junit、没有 surefire，kotlin 插件也只配了
    `src/main/kotlin`。所以本模块的纯计算只能靠"启动自检 + 非 0 退出"
    （`ClickDslExample` 与 `XuanDemo` 都是这个模式）。
    ⚠️ **`src/test` 不再是空的，但那不是单测**：`src/test/java/com/bingbaihanji/gl/Main.java`
    是一个**手动跑的 Java 示例**（顶栏 `ComboBox` 切几何/文字/图表；几何档是
    **两点式画圆 + 右键拾取**，见它自己的类文档），surefire **不会**碰它——
    本模块仍然只有 `src/main/kotlin` 一处源码根。
    **它的跑法与上面那些示例不同，照抄会 `ClassNotFoundException`**：
    ```bash
    cd xuan-javafx
    mvn -o test-compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" \
        "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.gl.Main"
    ```
    两处差别都是必需的：`compile` **不编译** test 源码（那是 `test-compile` 的事），
    而 `classpathScope` 必须是 **`test`**——`runtime` 的 classpath 里没有 `target/test-classes`。`xuan-javafx/pom.xml` 与根 `pom.xml` 在本轮都**只是
    重排格式**（把一行挤在一起的 `<dependency>` 拆成多行），**没有任何依赖或插件变化**。
  - ✅ **已修（2026-09-26）：`ChartRenderer` 的系列资源现在自动回收**（设计文档 §6.6）。
    原先它用 `IdentityHashMap` 按 **`Series` 对象身份**缓存 GPU 缓冲与拾取号
    （`buffers` 与 `pickIds.computeIfAbsent(series, pickRegistry::register)` 两处），
    而这两张 map **只在 `dispose()` 里清空**、中间没有任何回收路径 ⇒
    任何"每帧重建 `Chart`"的写法（最自然的写法，因为 `Chart` 看起来是个纯计算对象）
    都会**每帧泄漏一块 `SeriesBuffer` 并消耗两个拾取号**；号耗尽时 `PickRegistry`
    抛异常 ⇒ 症状是"前几百帧完全正常，然后图表忽然不画了"。
    ⚠️ **当时那句"没有任何报错"现在只对一半**：P1-6 之前帧回调里的异常被 openglfx
    静默吞掉，"静默"是这条缺陷的形态里最坏的一环；现在它会走到 `reportRenderFailure`
    （`onError` 或 `printStackTrace`），**但图表照样不画了**——报错只解决了"能不能查"，
    没解决"会不会坏"。所以"用自动回收而不是 `retainSeries`"那条理由**不变**。
    **做法是自动回收，不是 `retainSeries` / `releaseSeries`**：一个要记得调的 API
    仍然会被忘记，而忘记的症状是静默的——正是这条缺陷本身的形态。
    机制：`ChartRenderer` 记一个**代**（每帧 +1）与一张 `IdentityHashMap<Series, Long> lastSeen`
    （`draw` 把每个画到的系列标成当前代），帧首回收**连续两帧没被画过**的系列
    （`buffer.dispose()` + `pickRegistry.unregister(id)` + 三张表一并移除）；
    时机由帧的所有者给——`Gc.beginFrame` 每帧调一次 `releaseUnused()`
    （`charts` 是懒创建的，没建过图表的应用一行都不受影响），**没有新增公开的帧边界 API**。
    **★ 宽限两代是刻意的，不是随手取的**：改成"上一帧没画就收"之后，
    "**每隔一帧画一次**"的用法会**每画一次就销毁又重建一次缓冲**——而重建意味着
    **重传整个环**（1M 点 = 4 MB），画面却逐像素相同（实测：变异后 B 组 14 次画
    每次都传 44 字节，拾取号在 {7, 9, 19} 之间循环）。
    两帧的宽限让那种用法的最大间隔恰好是 1 代，一次都不回收；
    而"真的不再画了"仍在**连续两帧**之内释放（第 k 帧之后不再画 ⇒ 第 k+3 帧的帧首释放）。
    `ChartVerifier` 的**「回收实验」一节 7 条**钉着它（三路探针：A 每帧 new 两个
    `Series` ⇒ 台账不增长；B 同一个对象每隔一帧画 ⇒ 宽限挡得住；C 每帧都画 ⇒ 不误收），
    两条变异各自定向：删掉 `Gc.beginFrame` 里那句 ⇒ 只剩 ① 两条倒（size 48 → 100，
    每帧恰好 +2）；`GRACE_GENERATIONS` 2 → 1 ⇒ 只剩 ② 两条倒。
    `DemoChart.cachedCharts` **保留但换了理由**：它不再防泄漏（库负责了），
    现在的理由是"停在同一个图型上时零重传"（重建必然重传整环）；
    代价是切走超过两帧再切回来仍要重传一次——那是刻意的取舍。

**声明了但完全没用到的依赖**
JOML（数学全是手写的）、`lwjgl-glfw`、jspecify、logback、byte-buddy(+agent)、JNA。
app 的窗口完全由 JavaFX 管理，GLFW 不参与。

## 构建配置须知

- **Java/Kotlin 编译目标统一为 Java 21**：`pom.xml` 通过
  `maven.compiler.release=${java.version}` 和 `kotlin.compiler.jvmTarget=${java.version}`
  共享同一属性。代码使用 record pattern 等 Java 21 语法，发布包不能再宣称 Java 17 兼容。
- **Java/Kotlin 混合编译**：`default-compile` 和 `default-testCompile` 执行被显式禁用
  （`<phase>none</phase>`）并重新绑定，同时 `kotlin-maven-plugin` 的 `sourceDirs` 把
  `src/main/java` 也包含进来——这样 Kotlin 才能编译 Java 源码、Java 也能引用 Kotlin 类。
  **不要"清理"这个配置。**
- **JavaFX 实际版本是 25，不是 pom 里写的 17**：本机 JDK（Liberica-NIK 25.0.3）把
  JavaFX 25.0.3 作为 **boot modules** 打进 JDK 镜像，**优先于 classpath 上的 jar**。
  因此 pom 声明的 `javafx.version=17.0.6` 在运行时完全被遮蔽。排查 JavaFX 行为时以 **25** 为准。
- **JavaFX 依赖声明了两遍**：一次不带 classifier，一次带 `<classifier>win</classifier>`。
  带 classifier 的是 Windows 原生库，项目当前是 **Windows 专用**。
- `openglfx-lwjgl` 显式排除了 `kotlin-stdlib-jdk8`，避免与 `kotlin-stdlib` 冲突。
- `pom.xml` 的 manifest `<mainClass>` 是 `com.bingbaihanji.xuan.MainKt`。
  但 `java -jar bin/xuan-0.1.0.jar` 仍可能因 JavaFX/openglfx 的原生库路径问题失败，
  优先用上面的 `exec:exec` 或 IDE 运行配置。

## 发布（里程碑 0.1.0）

**版本号**：三个模块统一 `0.1.0`（原为 `1.0-SNAPSHOT`）。Bump 时**四处都要改**——
根 `pom.xml` 的 `<version>`，以及三个子模块 `<parent>` 里的 `<version>`
（`xuan-core` 那个写在一行里）。子模块之间用的是 `${project.version}`，不必单独改。

**发布范围**：**内部发布**（2026-09-27 定）。这一条决定了下面几件事的处置。

### ✅ 字体：已于开源前移除（原法务拦截项）

原先仓库自带一份 `simhei.ttf`，实测与系统字体目录里那份 **逐字节相同**
（md5 `4093871a7f48e43b9ce7c38da0c34809`）——微软/中易的专有字体，与 MIT 声明冲突。
**2026-09-28 已删除**，字体改为**调用方构造时指定**（见「文本」一节）。

去掉之后有**两处连带代价**，都已处置：

- **跑校验器要带 `-Dxuan.text.font=<路径>`**：大部分校验器都会间接碰文字
  （`ChartVerifier` 经 `ChartPainter.width`、`AxisVerifier` 经它自己的度量、
  `ClickVerifier` 要画标签…）。**没给时它们明确失败并退 1**，不是静默跳过。
- **`FontFileTest` / `GlyphRasterizerTest` 共 9 条**同理：没给属性时记成 **`Skipped`**。
  ⚠️ **实现这条时踩过一个坑**：把 assumption 放在 `@BeforeAll` 里时，整个类会在计数之前
  中止，surefire 报 `tests="0"` ——**类从报告里消失了**，既不算通过也不算跳过
  （实测 235 → 226 而 `skipped` 仍是 0，正是最坏的那种“静默跳过”）。
  放进 `@BeforeEach` 才会计进 `Skipped`，看得见。

### 仓库卫生（0.1.0 时补的）

- **`LICENSE`**：原先 README 声明 MIT 但**根目录没有这个文件**。已补，并在末尾加
  "例外（不在 MIT 授权范围内）"一段点明字体。
- **`.gitignore`**：原先只逐条忽略 `.claude/` 的两个子路径 ⇒ `.claude/runs/`（177K
  的 GPU 探针数据）与 `gpuwatch.sh` **会被误提交**。已改成整目录忽略 `.claude/`
  （它里面全是本机工具与产物）。
- **没有 `.gitattributes`**：每次 `git status` 都在报 "LF will be replaced by CRLF"。
  **本期没加**（加它会让所有文本文件重新归一化，是一次全仓 diff）——
  要加就单开一次提交，别混进功能改动里。

### 发布时的验收口径（0.1.0 实测全过）

```bash
mvn -o install -DskipTests     # 先在仓库根：子模块从本地仓库解析依赖，
                               # 不 install 的话跨模块改动会"编译不过"（见下面那条坑）
mvn -o test                    # 411 / 0 失败 / 2 跳过
```

再加**九个校验器**（`PipelineVerifier` / `PathVerifier` / `PickVerifier` /
`ClickVerifier` / `TextVerifier` / `ChartVerifier` / `FftVerifier` / **`AxisVerifier`**，
逐个退出码 0；`AxisVerifier` 是 2026-09-28 坐标系入库时新增的第九个，见「坐标系」一节）、
**demo 合成事件自检**（`-Dxuan.demo.selftest=1`，21 + 14 条）、
**MSAA 三档跨进程比对**（`bash xuan-javafx/scripts/msaa-verify.sh`）。

> **⚠️ 跨模块的未提交改动会让 `xuan-javafx` 编译不过，报错却指向一个不存在的引用。**
> 实测：`xuan-core` 里新加了 `Chart.interaction()` 但没 install，
> 于是 `DemoChart.kt` 报 `Unresolved reference 'interaction'`——**看起来像代码写错了**。
> 触发条件比这里原先记的更宽：**任何**跨模块的未提交改动都会撞它
> （原先只记了 `xuan-render-gl` 的变异残留那一种）。**判据：报错的符号明明存在 ⇒ 先 install。**

## 文档与生成物

**`docs/superpowers/` 下是规格（specs）与计划（plans）成对的一组，各 10 份**，
按子项目推进的时间顺序编号。**这一节以前只列了 render-pipeline 那两份**——
那份清单早就不是全部了，找文档时直接看目录：

```
specs/                                        plans/
2026-09-09-xuan-drawing-engine-design.md      2026-09-09-xuan-drawing-engine.md
2026-09-11-xuan-render-pipeline-design.md     2026-09-11-xuan-render-pipeline.md   （15 个任务）
2026-09-17-xuan-gpu-picking-design.md         2026-09-17-xuan-gpu-picking.md
2026-09-20-xuan-chart-framework-design.md     2026-09-20-xuan-chart-framework.md
2026-09-20-xuan-chart-render-backend-design.md 2026-09-20-xuan-chart-render-backend.md
2026-09-20-xuan-sdf-text-design.md            2026-09-20-xuan-sdf-text.md
2026-09-23-xuan-gpu-fft-design.md             2026-09-23-xuan-gpu-fft.md
2026-09-24-xuan-architecture-review.md        （评审，无对应计划）
2026-09-24-xuan-demo-design.md                2026-09-24-xuan-demo.md
                                              2026-09-25-xuan-demo-click-draw.md
2026-09-26-xuan-antialias-design.md           2026-09-26-xuan-antialias.md
```

- **`2026-09-09-xuan-drawing-engine-design.md` 已过时**：它描述的是被删除的保留模式
  场景图架构，仅作历史参考。**别照着它理解现状**。
- **`2026-09-11-xuan-render-pipeline.md` 里有若干已知缺陷**，执行前先核对；
  文件内已就地标注了多处更正。
- **`2026-09-24-xuan-architecture-review.md` 是评审报告的出处**：本文件里的 `P1-6`
  等编号、以及 §4.5.1 关于 chart-fx 降采样的那几段，都引它。
  ⚠️ 它的 §4.5.1 有一条**已被实测推翻**（"尖峰看不见"），**引它之前先读本文件里
  「③-2 降采样」那一段的更正**。
- **`docs/Xuan-DEVELOPER-GUIDE.md`**（新，`README.md` 第 3 行指向它）是**面向使用者**的
  手册——分层、设计模式、三大模块 API、`Disposable` 四条契约。它与本文件**受众不同**：
  本文件写给"要改这个库的人"（陷阱、变异验证、证据强度分级），手册写给"要用这个库的人"。
  **手册里没有的东西才是本文件存在的理由**，所以两边的重复不必去消除；
  但**手册里出现了本文件没有的断言时要当心**——它写着"回调异常会通过 `abortFrame`
  清理当前状态"（§1.5），而那条**没有断言盖着**（见「常用命令」里 P1-6 那段）。
- `xuan-workflow.js` — 生成此代码库的多智能体 Workflow 脚本。
- `.claude/` — 本机配置（`settings.local.json`、`skills/`、`worktrees/`、`runs/`、
  `gpuwatch.sh`）。**未纳入版本控制的意图不明，改它之前先问**。
- `.xcodemap/` — xcodemap 插件配置。本项目**未**建立 codegraph 索引，codegraph 工具不可用。
