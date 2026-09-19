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
```

当前 **170 个测试，0 失败，2 跳过**。单测命令：`mvn test -Dtest=类名`。

`geom/`、`math/`、`util/`、`ViewTransform` 都是纯计算、不依赖 GL 上下文，最适合写单测。
`gl/Framebuffer` 与 `renderer/PickBuffer` 只依赖 `GLAbstraction` **接口**，
用 `src/test/.../gl/FakeGLAbstraction` 这个假实现也能零 GL 上下文单测——
这是拾取子系统里唯一能做到这一点的一层。`RenderBatch` 的颜色/ID pass 与 `Gc` 则必须靠校验器。

## 架构

### 单一批处理管线

```
L3  DSL / 门面      com.bingbaihanji.jfgl.dsl.JFGL、renderer.Gc      用户 API
L2  提交            renderer.RenderBatch                              着色器 / VAO / draw call
L1  CPU 顶点侧      renderer.VertexWriter / VertexFormat / DrawCommand
                    renderer.ViewTransform                            变换与裁剪（无 GL）
L0  几何            geom.Path / Flattener / Tessellator / StrokeGenerator   纯计算，零 GL 依赖
    GL 抽象         gl.*                                             LWJGL 3.3.6 + openglfx
```

**`geom/` 对 `gl/` 零依赖**，由 `GeomPackageIsolationTest` 强制。

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
- `id` 属性（location 3）是为将来的 GPU 拾取预留的。

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
2. **改了断言或修了 bug，做变异验证**：把 bug 重新注入，确认校验器真的失败。
   （校验器里那条"反证"断言就是这么来的——避免覆盖性检查恒真、变成橡皮图章。）
3. 校验器依赖"用户坐标 1:1 映射到设备像素"这一前提。若将来引入真正的 DPI 缩放，
   它的期望值需要乘以缩放系数——那时它会失败，正是它该提醒的。

## 已实现 vs 未实现

**可用（纯计算，无需 GL，最适合写测试）**
`geom/Path`（含零分配变更器）、`geom/Flattener`（二次/三次贝塞尔细分）、
`geom/Tessellator`（凸扇形 + 凹耳切 + 孔洞桥接）、`geom/StrokeGenerator`（端点/接头/虚线）、
`math/{Vec2,Mat3,Transform}`、`util/{Color,Rect}`、`renderer/ViewTransform`

**可用（依赖 GL 上下文）**
`gl/ShaderProgram`、`gl/Texture`、`gl/LwjglGLAbstraction`（`initialize()`/`dispose()` 是诚实的
no-op，因为上下文已由 `GLCanvas` 置为当前）、`renderer/RenderBatch`、
`renderer/Gc` 的形状与路径方法、`gpu/ComputeShader`、
`renderer/PickRegistry`（ID 分配与 `id→对象` 映射，纯内存可单测）、
`renderer/PickBuffer`、`PickHit`、`Gc` 的 `pickId` / `pickable` / `pick` / `pickRect`、
`FXGLTransfer.pickAsync`

**未实现 / 待办**
- **SDF 文本**（子项目 B）：目前完全没有文本绘制能力。需支持中文与任意缩放清晰度。
- **科学绘图级图表**（子项目 D）：对数轴、多 Y 轴、误差棒、热力图、等高线。图表目前完全不存在。
- **Paint / 渐变**：所有绘制只接受纯色整数。设计意图是**所有 Paint 归一化为纹理**
  （纯色 = 超白色纹理 + 顶点颜色，渐变 = 1×256 LUT）。
- **`createTexture` 缺少 ARGB→RGBA 通道转换**——**实现 Paint/渐变之前必须先修**。
- `Gc.strokePath()` 的闭合子路径在收尾顶点处不生成接头（线段本身不缺）。
  修法需要去看 `Path` 的命令表判断末条命令是否为 `CLOSE`，见该方法的 Javadoc。
- `Gc.strokePath()` 会把所有子路径当成**一条**折线描边，多条子路径之间会多出一段连线。
- 没有黄金图像测试。

**声明了但完全没用到的依赖**
JOML（数学全是手写的）、`lwjgl-glfw`、`lwjgl-stb`、jspecify、logback、byte-buddy(+agent)、JNA。
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
