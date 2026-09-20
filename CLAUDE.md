# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

JFGL 是一个基于 **JavaFX + OpenGL** 的 2D 绘图框架。OpenGL 上下文由
[openglfx-lwjgl](https://github.com/husker-dev/openglfx) 的 `GLCanvas` 承载，`GLCanvas`
本身是 JavaFX 场景图中的一个 `Node`，因此 OpenGL 渲染结果直接嵌入 JavaFX 布局。

目标：做一套**用起来像 canvas**（立即模式、像素坐标、`fillRect`/`strokePath` 这类方法形状）
但**内部按最优方案实现**的 2D 绘图 API。注意"像 canvas"指的是 API 手感，不是实现——
不要以"JavaFX 是这么做的"作为设计理由，除非行为差异会让用户困惑。

语言分工：**Java 写几何与渲染热路径（`geom/`、`renderer/` 的顶点侧），Kotlin 写上层门面与
JavaFX 胶水层**（`Gc`、`ViewTransform`、`FXGLTransfer`、DSL、示例）。代码注释和 Javadoc
一律使用中文。

## 常用命令

```bash
mvn compile                      # 编译（Java 21 + Kotlin 17 混合编译）
mvn -o compile                   # 离线编译（依赖已缓存时可用）
mvn test                         # 运行测试
mvn -o clean test                # 干净重建 + 全量测试
mvn package                      # 打包，并额外把 jar + 依赖复制到 bin/ 和 bin/libs/
```

### ⚠️ 运行应用：必须用 `exec:exec`，不能用 `exec:java`

`mvn exec:java` 在本项目**不可用**：该插件的类加载器会让 openglfx 链接到另一份
`com.sun.prism.GraphicsPipeline`（其 `thePipeline` 静态字段永远为 null），启动即抛
`UnsupportedOperationException: Could not detect pipeline`。必须 fork 出独立 JVM：

```bash
# 运行示例（打开窗口，需手动关闭）
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.MainKt"

# 运行像素校验器（自动关窗，退出码 0=通过 / 1=有断言失败）
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
```

`exec-maven-plugin` **未在 `pom.xml` 中声明**，但 3.6.3 已缓存，`-o` 离线可用。
某些 shell 会把 `-D` 前缀吃掉（表现为 Maven 报 `Unknown lifecycle phase '.executable=java'`），
把每个 `-D...` 参数**加引号**可以规避。

另一个构造时机的坑：`FXGLTransfer` **不能**在 `Application.launch` 之前构造——
`GLInteropType.auto` 在类初始化时要向 Prism 询问渲染管线，工具包没起来就抛
`Could not detect pipeline`。所以入口必须是 JavaFX 应用，不能是普通 main。

### 测试

```
src/test/java/com/bingbaihanji/jfgl/geom/       PathTest、FlattenerTest、TessellatorTest、
                                                TessellatorHoleTest、TessellatorRegressionTest、
                                                StrokeGeneratorTest、StrokeDashTest、
                                                GeomPackageIsolationTest
src/test/java/com/bingbaihanji/jfgl/renderer/   VertexFormatTest、VertexWriterTest、ViewTransformTest、
                                                PickRegistryTest、PickBufferTest
src/test/java/com/bingbaihanji/jfgl/gl/         FramebufferTest、LwjglGLAbstractionTest
src/test/java/com/bingbaihanji/jfgl/text/       SdfGeneratorTest、GlyphAtlasTest、FontFileTest、
                                                GlyphRasterizerTest、TextLayoutTest
src/test/java/com/bingbaihanji/jfgl/chart/      TickGeneratorTest、AxisTest、ArrayChartDataTest、
                                                RingChartDataTest、ChartDataConcurrencyTest、
                                                ColorMappingTest、ChartTest、
                                                ChartPackageIsolationTest
```

当前 **261 个测试，0 失败，2 跳过**（2 个跳过是 `TessellatorRegressionTest` 里两条
`@Disabled` 的已知缺陷）。单测命令：`mvn test -Dtest=类名`。

`geom/`、`math/`、`util/`、`ViewTransform`、`text/{SdfGenerator, TextLayout}`、`chart/`
都是纯计算、不依赖 GL 上下文，最适合写单测。`gl/Framebuffer`、`renderer/PickBuffer`、
`text/GlyphAtlas` 只依赖 `GLAbstraction` **接口**，用 `src/test/.../gl/FakeGLAbstraction`
这个假实现也能零 GL 上下文单测。`text/{FontFile, GlyphRasterizer}` 依赖 stb 的本地库
（已实测能在 surefire 里加载）。`RenderBatch` 的着色器与 `Gc` 则必须靠校验器。

**`chart/` 的边界是机器强制的**：它连 `renderer/` 也不依赖，由
`ChartPackageIsolationTest` 递归遍历源码、按**包名白名单**守卫——白名单只放行
`chart/`、`math/`、`util/`，引用 `gl/`、`renderer/`、`text/`、`geom/` 中的任何一个
都会让测试失败。**注意目前 `chart/` 连 `math/` 与 `util/` 也一处没用**：16 个源文件
除了 `java.*` 之外没有任何 import（守卫放行 ≠ 已经用了）。
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
compute 用不了**——`gpu/GPUFFT.java`（`#version 430`，Cooley-Tukey radix-2 + SSBO）
就是这么被埋掉的：它一直可用，只是没人引用。

先前的 `CLAUDE.md` 里**没有任何一处写过上下文是几**——唯一沾边的 "3.3" 是架构图里
`LWJGL 3.3.6`，那是**库**的版本。这一节就是为了补上这个缺口。

## 架构

### 单一批处理管线

```
L3  DSL / 门面      com.bingbaihanji.jfgl.dsl.JFGL、renderer.Gc      用户 API
L2  提交            renderer.RenderBatch                              着色器 / VAO / draw call
L1  CPU 顶点侧      renderer.VertexWriter / VertexFormat / DrawCommand
                    renderer.ViewTransform                            变换与裁剪（无 GL）
L0  几何            geom.Path / Flattener / Tessellator / StrokeGenerator   纯计算，零 GL 依赖
    文本            text.SdfGenerator / text.TextLayout                     纯计算；
                    text.FontFile（stb）/ text.GlyphAtlas（R8 图集）        依赖 stb 与 GL
    图表            chart.*                                          纯计算，零 GL 依赖
    GL 抽象         gl.*                                             LWJGL 3.3.6 + openglfx
```

**`geom/` 对 `gl/` 零依赖**，由 `GeomPackageIsolationTest` 强制。

**`chart/` 对 `gl/`、`renderer/`、`text/`、`geom/` 零依赖**，由 `ChartPackageIsolationTest`
强制。① 与渲染后端（②）的接缝只有两个类型：`chart/RenderContext`（空接口，② 定义
子接口扩展它）与 `chart/SeriesRenderer`（纯函数：数据 + 轴 → 顶点）。

### 启动链路

```
Main.kt                     设置 prism.* 系统属性
  └─ PipelineExample.main   jfgl { ... }
       └─ JFGLApplication   JavaFX Application，搭窗口
            └─ FXGLTransfer GLCanvas 的 GL 回调里创建 RenderBatch + Gc
                 └─ onFrame { gc -> ... }   每帧绘制回调
```

### 绘制模型

绘制调用（`fillRect`、`fillCircle`、`strokePath` …）**不做任何 GL 工作**：它们把三角形
顶点追加进一块直接 `ByteBuffer`。每帧结束时 `Gc.endFrame()` 一次性提交，按少量 draw call 重放。

- **顶点格式 24 字节**（`VertexFormat`）：`vec2 pos`(0) + `vec2 uv`(8) +
  `vec4 ubyte normalized 预乘色`(16) + `uint id`(20)，stride 24。
- **不用索引缓冲**：每个三角形 3 个顶点，`glDrawArrays` 而非 `drawElements`。
- **只合并相邻且同状态的绘制**。绘制顺序即 2D 的 z 序，**绝不重排**。
- **变换在 CPU 侧烘焙进顶点**，因此改变换不会打断合批。
- **预乘 alpha**，混合用 `GL_ONE` / `GL_ONE_MINUS_SRC_ALPHA`。
- **裁剪只用 `glScissor`**（矩形）；任意路径裁剪不在范围内。
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
- **组件在 JavaFX 线程响应鼠标事件时用 `FXGLTransfer.pickAsync`**，不要直接调 `Gc.pick`
  ——那是跨线程 GL 调用，崩得毫无规律。
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
- **字体**：默认从 classpath 的 `/fonts/simhei.ttf` 加载（`FontFile.DEFAULT_RESOURCE`），
  启动时解析失败会**抛异常**（不退化成"一个字都画不出来"）。换字体见
  `src/main/resources/fonts/README.md`——**这个仓库公开分发前必须换掉它**，
  黑体是微软/中易的专有字体。
- 本期**不做**字距/连字/bidi、多行与对齐、富文本、多字体回退、MSDF。这些是刻意
  不做，不是漏了。

### 图表

图表框架（子项目 D-①）在 `chart/` 下，**纯计算、零 GL 依赖**：数据容器（`ArrayChartData`
静态 / `RingChartData` 流式）、轴与刻度（`Axis` / `TickGenerator` / `AxisType`）、
配色 LUT（`ColorMapping`）、装配（`Chart` / `Layer` / `Series` / `ChartType`）。
**绘制后端（子项目 ②）尚未实现**，所以现在还没有"画出来"的能力。

三块分解：**① 图表类框架（已完成，`chart/`）→ ② GPU 绘制后端 → ③ GPU 计算。**
**分界判据是"能不能脱离 GL 上下文跑测试"**——① 里每一个类都能，② 里的一个都不能，
`chart/` 的边界正是这么画出来的（也是 `ChartPackageIsolationTest` 在守的那条线）。

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

### 线程模型

所有 `gl*` 调用与 GL 资源生命周期**必须**发生在 `GLCanvas` 的 GL 线程上，即
`onInit` / `onRender` / `onReshape` / `onDispose` 回调内部。JavaFX 应用线程上只能做布局和
事件注册。`jfgl {}` 的 `onInit` / `onRender` 都在 GL 线程上调用，可以直接用 `Gc`，
但**不要**在其中操作 JavaFX 场景图。

不要在 JavaFX 的 `stop()` 里清理 GL 资源——那时上下文可能已失效。

### 资源释放

带 GL 资源的类统一实现 `com.bingbaihanji.jfgl.util.Disposable`。
所有权：`FXGLTransfer.onDispose` → `RenderBatch.dispose()`。

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

   改**拾取**路径后跑 `PickVerifier`（同样回读像素、断言精确 ID，退出码 0/1）：

   ```bash
   mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
       -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
   ```

   拾取尤其危险：**错误的拾取不会让任何画面变坏**，只会让点击落在错误的对象上。

   改**文本**路径后跑 `TextVerifier`（退出码 0/1）。它的核心断言是：
   同一个字以 24px 与 192px 绘制时，**边缘过渡带宽度大致恒定**——
   位图被放大时过渡带会随缩放线性变宽，**这是唯一能把"SDF 生效"与
   "位图被放大"区分开的断言**：

   ```bash
   mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
       -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.TextVerifierKt"
   ```
2. **改了断言或修了 bug，做变异验证**：把 bug 重新注入，确认校验器真的失败。
   （校验器里那条"反证"断言就是这么来的——避免覆盖性检查恒真、变成橡皮图章。）
3. 校验器依赖"用户坐标 1:1 映射到设备像素"这一前提。若将来引入真正的 DPI 缩放，
   它的期望值需要乘以缩放系数——那时它会失败，正是它该提醒的。

## 已实现 vs 未实现

**可用（纯计算，无需 GL，最适合写测试）**
`geom/Path`（含零分配变更器）、`geom/Flattener`（二次/三次贝塞尔细分）、
`geom/Tessellator`（凸扇形 + 凹耳切 + 孔洞桥接）、`geom/StrokeGenerator`（端点/接头/虚线）、
`math/{Vec2,Mat3,Transform}`、`util/{Color,Rect}`、`renderer/ViewTransform`、
`text/SdfGenerator`（覆盖度位图 → 有符号距离场）、`text/TextLayout`（槽位序列 → 四边形顶点）、
`chart/` 全部（数据容器、轴与刻度、配色 LUT、图表装配——见「图表」一节）

**可用（依赖 GL 上下文）**
`gl/ShaderProgram`、`gl/Texture`、`gl/LwjglGLAbstraction`（`initialize()`/`dispose()` 是诚实的
no-op，因为上下文已由 `GLCanvas` 置为当前）、`renderer/RenderBatch`、
`renderer/Gc` 的形状与路径方法、`gpu/ComputeShader`、
`renderer/PickRegistry`（ID 分配与 `id→对象` 映射，纯内存可单测）、
`renderer/PickBuffer`、`PickHit`、`Gc` 的 `pickId` / `pickable` / `pick` / `pickRect`、
`FXGLTransfer.pickAsync`、`renderer/Material`（材质选择位）、
`text/FontFile`（stb 的字体与度量封装）、`text/GlyphRasterizer`、`text/GlyphAtlas`（R8 图集）、
`Gc` 的 `fontSize` / `drawText` / `measureText`

**未实现 / 待办**
- **图表绘制后端**（子项目 D-②）：把 `chart/` 的产物变成 GL 顶点与 draw call
  （波形、散点、柱、热力图、瀑布）。**图表框架本身（D-①）已经做完，但还没有任何
  绘制能力**——`chart/` 只算不画。
- **GPU 计算**（子项目 D-③）：FFT、降采样、包络、密度累积（数字荧光）。
  `gpu/GPUFFT.java` 已经在那儿且**可用**（`#version 430`，见「前置事实」一节），
  但它现在**没有任何人引用**——是死代码，不是废代码。
- **误差棒、等高线、眼图**：`ChartType` 目前没有覆盖，属于 ③ 或更后面的事。
- **Paint / 渐变**：所有绘制只接受纯色整数。设计意图是**所有 Paint 归一化为纹理**
  （纯色 = 超白色纹理 + 顶点颜色，渐变 = 1×256 LUT）。
- **`createTexture` 缺少 ARGB→RGBA 通道转换**——**实现 Paint/渐变之前必须先修**。
- `Gc.strokePath()` 的闭合子路径在收尾顶点处不生成接头（线段本身不缺）。
  修法需要去看 `Path` 的命令表判断末条命令是否为 `CLOSE`，见该方法的 Javadoc。
- `Gc.strokePath()` 会把所有子路径当成**一条**折线描边，多条子路径之间会多出一段连线。
- 没有黄金图像测试。

**声明了但完全没用到的依赖**
JOML（数学全是手写的）、`lwjgl-glfw`、jspecify、logback、byte-buddy(+agent)、JNA。
app 的窗口完全由 JavaFX 管理，GLFW 不参与。

## 构建配置须知

- **Java 版本自相矛盾**：`<java.version>17</java.version>` 属性实际未被编译插件使用；
  `maven-compiler-plugin` 硬编码 `source/target = 21`；Kotlin `jvmTarget = 17`。
  改动编译配置时注意这几处。
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
- `pom.xml` 的 manifest `<mainClass>` 是 `com.bingbaihanji.jfgl.MainKt`。
  但 `java -jar bin/jfgl-1.0-SNAPSHOT.jar` 仍可能因 JavaFX/openglfx 的原生库路径问题失败，
  优先用上面的 `exec:exec` 或 IDE 运行配置。

## 文档与生成物

- `docs/superpowers/specs/2026-09-11-jfgl-render-pipeline-design.md` — 子项目 A 的设计规格。
- `docs/superpowers/plans/2026-09-11-jfgl-render-pipeline.md` — 实施计划（15 个任务）。
  **注意计划里有若干已知缺陷**，执行前先核对；文件内已就地标注了多处更正。
- `docs/superpowers/specs/2026-09-09-jfgl-drawing-engine-design.md` — **已过时**，
  描述的是被删除的保留模式场景图架构，仅作历史参考。
- `jfgl-workflow.js` — 生成此代码库的多智能体 Workflow 脚本。
- `.xcodemap/` — xcodemap 插件配置。本项目**未**建立 codegraph 索引，codegraph 工具不可用。
