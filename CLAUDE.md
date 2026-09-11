# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

JFGL 是一个基于 **JavaFX + OpenGL** 的 2D 绘图框架。OpenGL 上下文由 [openglfx-lwjgl](https://github.com/husker-dev/openglfx) 的 `GLCanvas` 承载，`GLCanvas` 本身是 JavaFX 场景图中的一个 `Node`，因此 OpenGL 渲染结果直接嵌入 JavaFX 布局。

语言分工是本项目的核心约定：**Java 写核心实现（`src/main/java`），Kotlin 写上层 API 与 JavaFX 胶水层（`src/main/kotlin`）**。代码注释和 Javadoc 一律使用中文。

## 常用命令

```bash
mvn compile                  # 编译（Java 21 + Kotlin 17 混合编译）
mvn -o compile               # 离线编译（依赖已缓存时可用）
mvn package                  # 打包，并额外把 jar + 依赖复制到 bin/ 和 bin/libs/
mvn test                     # 运行测试（见下方"测试"说明）

# 运行 JavaFX 彩虹圆环演示（IDEA 中的 MainKt 运行配置）
mvn exec:java -Dexec.mainClass="com.bingbaihanji.jfgl.MainKt"

# 运行 DSL 绘图演示（README 中的示例入口）
mvn exec:java -Dexec.mainClass="com.bingbaihanji.jfgl.example.DrawExampleKt"
```

`exec-maven-plugin` **未在 `pom.xml` 中声明**，`mvn exec:java` 依赖 Maven 的插件前缀解析，首次使用需要联网下载。

### 测试

`src/test/{java,kotlin}/` 目录存在但**为空**——目前项目没有任何测试文件。JUnit 5（`junit-jupiter`）和 `kotlin-test` 已在 `pom.xml` 中配置好，`mvn test` 可以直接运行新增的测试。单测命令：`mvn test -Dtest=类名`。

`docs/superpowers/plans/2026-09-09-jfgl-drawing-engine.md` 为每个任务列出了对应的测试类和 `mvn test -Dtest=XxxTest` 命令，但那些测试尚未落地，可作为编写测试的参考。`math/`、`util/`、`renderer/Path` 这几个模块是纯计算、不依赖 GL 上下文，最适合先补单元测试。

### 打包产物的坑

`pom.xml:269` 中 `maven-jar-plugin` 的 manifest `mainClass` 是 `com.bingbaihanji.fxgl3d.AppKt`——**这个类在本仓库中不存在**（疑似从其他项目复制而来）。因此 `java -jar bin/jfgl-1.0-SNAPSHOT.jar` 无法运行，请用 `mvn exec:java` 或在 IDE 中运行实际的 main 类。

## 架构

### 分层设计（目标架构）

设计文档（`docs/superpowers/specs/2026-09-09-jfgl-drawing-engine-design.md`）定义了四层架构：

```
L4  Kotlin DSL      com.bingbaihanji.jfgl.dsl        用户 API
L3  Scene Graph     com.bingbaihanji.jfgl.scene      节点树 / QuadTree / 脏标记
L2  Renderer        com.bingbaihanji.jfgl.renderer   批量渲染 / 变换 / 文本
L1  GL Abstraction  com.bingbaihanji.jfgl.gl         着色器 / 缓冲 / 状态封装
L0  OpenGL          LWJGL 3.3.6 + openglfx-lwjgl
```

`engine/`（引擎核心）、`event/`（交互）、`style/`、`chart/`、`gpu/`、`math/`、`util/` 是横切这些层的支撑模块。

### 启动链路

```
Main.kt (main)              设置 prism.* 系统属性，Application.launch(App)
  └─ App.kt                 JavaFX Application：组装 FXGLTransfer + MainView + MouseEvent 处理
       └─ FXGLTransfer.kt   创建 GLCanvas，在 GL 回调中构建/驱动 DrawEngine
            └─ DrawEngine   LwjglGLAbstraction / BatchRenderer / TextRenderer / Scene / 2D 相机
```

`dsl/JFGL.kt` 中的 `jfgl { ... }` DSL 是另一条等价的启动路径：内部同样创建 `FXGLTransfer`，但用 `JFGLApplication` 承载窗口，并把渲染回调交给 `DrawDSL`。

`MainView.kt` 只是一个 `BorderPane` 包装，把 `GLCanvas` 放到 center 区域。

### ⚠️ 两条互不相通的绘制路径

这是阅读本代码库时最大的坑。**仓库里存在两套完全独立的渲染栈，只有一套真正会画出东西。**

**Stack A：场景图 + `BatchRenderer`（Java 框架层）——已接线，但画不出任何东西**

`FXGLTransfer` → `DrawEngine` → `Scene`/`Node`/`ShapeNode`/`Chart` 这条链路。对象图、相机矩阵（`DrawEngine.updateViewProjectionMatrix` / `computeInverseViewProjection`）、路径贝塞尔细分（`Path.toVertices`）、字模图集（`TextRenderer`）、各图表的坐标映射几何生成（`LineChart` 等）都是**真实且完整**的实现，但渲染末端是断的：

- `DrawEngine.render()` 只调用 `scene.render(renderContext)`，**从不调用 `batchRenderer.begin()` / `flush()` / `end()`**；
- `ShapeNode.render()` 内部的 `renderFilled()` / `renderStroked()` 方法体只有注释，是空实现——**所有形状和图表最终都渲染不出任何像素**；
- `TextNode.render()` 同理为空，`TextRenderer.drawText(batch, ...)` 没有任何调用方；
- `BatchRenderer` 类本身是完整 GL 代码，但**零调用点**；且它的顶点着色器声明了 `uniform mat4 uViewProjection`，而全代码库没有任何地方给它设过 uniform；
- `RenderContext.setViewProjectionMatrix()` 设进去的矩阵没有被任何渲染器读取。

**Stack B：`DrawDSL` 立即模式（Kotlin）——实际生效的路径**

`jfgl { ... }` / `DrawDSL` / `App.kt` 走这条路。它自带一套 GLSL 330 着色器，在 CPU 侧拼好顶点数组后直接调用 LWJGL 静态方法，**完全绕过 `GLAbstraction`、`DrawEngine` 和 `Scene`**。坐标是标准 NDC：`-1..1`，原点在窗口中心，y 轴向上。

两套栈只共享 `FXGLTransfer` 和 `LwjglGLAbstraction`。**修改渲染相关代码前，先确认自己站在哪一套栈上**——在 `Scene`/`ShapeNode` 上做的工作不会影响 `jfgl {}` 的输出，反之亦然。

若要把 Stack A 打通，至少需要：在 `DrawEngine.render()` 中包裹 `begin()/end()`、把 VP 矩阵以 **mat4** 形式推给 `BatchRenderer`、填充 `ShapeNode`/`TextNode` 的 render 实现、并把纹理采样接上（`BatchRenderer` 的片元着色器目前直接输出 `vColor`，完全忽略纹理，而顶点属性里的 `aTexId` 被硬编码为 `0f`）。

### 坐标与单位约定

| 栈 | 坐标系 | 原点 | y 轴 | 旋转单位 |
|----|--------|------|------|----------|
| Stack A（`DrawEngine`/场景图/图表） | 像素 | 左上角 | 向下 | 弧度 |
| Stack B（`DrawDSL`/`App.kt`） | NDC `-1..1` | 窗口中心 | 向上 | — |

`JFGLApplication` 处理鼠标事件时，用**创建窗口时声明的 `width`/`height`**（而非实际画布尺寸）把 JavaFX 场景坐标换算成 NDC；而 `DrawEngine.screenToWorld` 用的是像素坐标加相机矩阵。两条路径各用各的换算，不要混用。

注意 Kotlin DSL 层的 `Shapes.kt` 里 `rotate(degrees)` 对外暴露的是**角度**，内部转成弧度传给 `Transform`；而 `Transform`/`DrawEngine` 原生 API 用的是弧度。

### 渲染调度

`RenderScheduler` 支持 `CONTINUOUS`（默认，每帧都画）和 `ON_DEMAND`（只有 `requestRender()` 后才画一帧）。`DrawEngine` 中所有 setter（相机、场景、resize）都会调 `requestRender()`。`FXGLTransfer.repaint()` 转发到 `GLCanvas.repaint()`，是手动触发重绘的入口——在 `fps = 0` 时尤其需要。目前 `FXGLTransfer` 从不把调度器切到 `ON_DEMAND`。

### 线程模型

所有 `gl*` 调用和 GL 资源生命周期**必须**发生在 `GLCanvas` 的 GL 线程上，即 `onInit` / `onRender` / `onDispose` / `addOnReshapeEvent` 回调内部。JavaFX 的 Application 线程上只能做布局和事件注册。`App.stop()` 里有注释明确提醒不要在 `stop()` 中清理 GL 资源，因为那时上下文可能已失效。

`RenderScheduler` 用 `volatile boolean` 支持跨线程 `requestRender()`；但场景图的可变 API（`Scene.add`、`setVisible`、`markDirty`）是普通字段，无锁、也不做 JavaFX 线程调度。

### 资源释放

带 GL 资源的类统一实现 `com.bingbaihanji.jfgl.util.Disposable`。所有权自上而下：`FXGLTransfer.onDispose` → `DrawEngine.dispose()` → `Scene` / `TextRenderer` / `BatchRenderer` / `GLAbstraction` 依次释放。`DrawEngine.setScene()` 不会自动释放旧场景，调用方负责。

已知隐患：`Scene` 用一份扁平的 `allNodes` 列表兜底 dispose，而图表的 `rebuild()` 各自用不同方式清空子节点（`LineChart.rebuild()` 甚至直接 `dispose()` 自身），存在重复释放 / use-after-dispose 的风险。

## 已实现 vs 未实现速查

改动前先看这张表，避免在空实现上浪费时间。

**真实可用（纯计算，无需 GL 上下文，最适合写测试）**
`math/Vec2`、`math/Mat3`、`math/Transform`、`util/Color`、`util/Rect`、`renderer/Path`（含二次/三次贝塞尔细分）、`scene/QuadTree`

**真实可用（依赖 GL 上下文）**
`gl/ShaderProgram`、`gl/Texture`、`gl/LwjglGLAbstraction`（`initialize()`/`dispose()` 是诚实的 no-op，因为上下文已由 `GLCanvas` 置为当前）、`gpu/ComputeShader`、`renderer/TextRenderer` 的图集构建、`DrawDSL` 全部绘图方法

**已接线但末端为空 / 无调用方**
`ShapeNode` / `TextNode` 的 `render()`、`BatchRenderer`（零调用点）、`EventDispatcher`（零调用方）、`RenderContext`

**交互链路是断的**
`event/EventDispatcher` 的分发逻辑完整，但没有任何 JavaFX→`EventDispatcher` 的桥接——`dsl/Interactions.kt` 注册进去的 handler 永远不会触发。实际交互（如 `App.kt`）全部直接在 JavaFX `MouseEvent` handler 里手写。

**图表**
坐标映射与几何生成真实，但因 `ShapeNode.render()` 为空而**画不出来**；且轴/网格/刻度/图例/标题完全没有实现（`title`/`xAxisLabel`/`yAxisLabel` 只存不画）。Kotlin `Charts.kt` 里各 builder 的配置项（`lineWidth`、`stacked`、`showLegend`、`startAngle` 等）被静默丢弃——`build()` 只调用 `applyCommonProperties` 和 `rebuild()`。图表也不会自动加入场景，需调用方自行 `add`。

**声明了但完全没用到的依赖**
JOML（所有数学都是手写的）、`lwjgl-glfw`、`lwjgl-stb`、jspecify、logback、byte-buddy(+agent)、JNA。app 的窗口完全由 JavaFX 管理，GLFW 不参与。

## 构建配置须知

`pom.xml` 有几处不直观的地方：

- **Java 版本自相矛盾**：`<java.version>17</java.version>` 属性实际未被编译插件使用；`maven-compiler-plugin` 硬编码 `source/target = 21`（`pom.xml:325-327`）；Kotlin `jvmTarget = 17`。实际构建用 JDK 25（`.idea/misc.xml` 配置 `ibm-25-jdk-25.0.1-openj9`，`mvn -v` 显示 Maven 3.9.9）。改动编译配置时注意这三处。
- **Java/Kotlin 混合编译**：`default-compile` 和 `default-testCompile` 执行被显式禁用（`<phase>none</phase>`）并重新绑定，同时 `kotlin-maven-plugin` 的 `sourceDirs` 把 `src/main/java` 也包含进来（`pom.xml:362-365`）——这样 Kotlin 才能编译 Java 源码、Java 也能引用 Kotlin 类。不要"清理"这个配置。
- **JavaFX 依赖声明了两遍**：一次不带 classifier，一次带 `<classifier>win</classifier>`。带 classifier 的是 Windows 原生库，项目当前是 **Windows 专用**。
- `openglfx-lwjgl` 显式排除了 `kotlin-stdlib-jdk8`，避免与 `kotlin-stdlib` 2.3.0 冲突。
- `<mainClass>` 过时（见上文"打包产物的坑"）。

## 文档与生成物

- `docs/superpowers/specs/2026-09-09-jfgl-drawing-engine-design.md` — 设计规格，各层 API 意图的权威来源（含尚未实现的 `CoordinateSystem`、`GPUConvolution`、`ResourceManager` 等）。
- `docs/superpowers/plans/2026-09-09-jfgl-drawing-engine.md` — 实施计划，含每个文件的目标代码与测试命令。
- `jfgl-workflow.js` — 一个多智能体 Workflow 脚本，本代码库即由它生成；修改架构时可参考其 phase 划分。
- `.xcodemap/` — xcodemap 插件配置。该项目**未**建立 codegraph 索引，codegraph 工具在本工作区不可用。
- 本仓库**不是** git 仓库。
