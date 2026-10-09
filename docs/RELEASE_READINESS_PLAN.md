# Xuan 发布级优化与架构评审方案

> 评审对象：`xuan`（`xuan-core`、`xuan-render-gl`、`xuan-javafx`）
>
> 评审依据：当前源码、README、Maven 配置及 `mvn test` 基线结果。

## 执行进度

- **阶段 A / 第 1 批已完成**：`FXGLTransfer` 释放幂等、关闭后请求拒绝、拾取请求取消回调、click 队列溢出回调、X/Y 独立 DPI 缩放，以及 `DeviceScaleTest`。
- **阶段 A / 第 2 批已完成**：引入 `ACTIVE -> DISPOSING -> DISPOSED` 生命周期状态机，覆盖重复关闭与并发关闭，并增加 `TransferLifecycleTest`。
- **阶段 A / 第 3 批已完成**：增加 `ThreadAffinity`，在 GL 初始化、渲染、reshape 和销毁回调处校验线程，并增加 `ThreadAffinityTest`。
- **阶段 A / 第 4 批已完成**：拾取请求增加一次性完成门闩，GPU 返回、队列溢出和销毁路径不会重复回调，并增加 `RequestCompletionTest`。
- **阶段 B / 第 1 批已完成**：增加 `JavaFxTestSupport` Toolkit 桥接和 `JavaFxIntegrationSmokeTest`，验证 JavaFX 线程上的视图构造。
- **验证结果**：`mvn test` 通过；核心 187、渲染 235、JavaFX 8 个测试通过，原有 11 个跳过项保持不变。
- **下一批**：在集成基座上覆盖窗口关闭、resize、DPI 变化和 GL 初始化失败路径。

## 1. 结论摘要

项目已经具备“可用的技术预览版”基础，但还不建议直接作为稳定版发布。当前最值得保留的是模块边界：`xuan-core` 不依赖 GL，`xuan-render-gl` 通过 `GLAbstraction` 隔离 LWJGL，`xuan-javafx` 负责 JavaFX 生命周期和线程桥接。批处理、SDF 文本、GPU 图表/FFT、异步拾取和资源 `dispose` 也说明底层设计不是简单 Demo。

主要发布风险集中在四处：

1. JavaFX/GL 运行时强绑定 Windows 原生依赖，尚未形成平台矩阵和可复现发行包。
2. JavaFX 集成层缺乏自动化测试；当前测试主要覆盖纯算法和可模拟的 GL 调用，真实窗口、DPI、MSAA、驱动差异仍靠手工 verifier。
3. Canvas API 仍是子集，文本、图像、渐变、路径样式和组合操作不足以替代通用 2D Canvas。
4. 公共 API 的线程、生命周期、坐标/DPI、错误和能力降级契约分散在实现注释中，尚未固化为稳定的文档和类型约束。

因此建议先发布 `0.1.x-preview`，完成下文 P0 后再考虑 `1.0`。

## 2. 当前架构评价

### 保留的设计

- **分层架构合理**：几何/数学/图表模型、GL 资源/渲染、JavaFX 适配职责清楚，依赖方向基本单向。
- **抽象与策略使用恰当**：`GLAbstraction` 便于 Fake GL 测试；`SeriesRenderer` + 不同系列 renderer 适合扩展图表类型；`Material`、`RenderBatch` 适合统一批提交。
- **资源管理方向正确**：GL 对象实现 `Disposable`，`FXGLTransfer` 在 GL 线程释放资源，图表 buffer 有延迟回收，避免了跨线程直接删 GL 资源。
- **数据并发模型有基础**：`RingChartData` 的单写单读/volatile 发布、拾取 PBO + fence 都是合理的性能取舍。

### 需要调整的设计

- `XuanApplication.config` 是全局可变单例，导致应用不可重入、测试难以并行；改为实例化的 `XuanApplication` 配置传递，至少移除静态业务状态。
- `Gc` 同时承担 Canvas API、路径展平、拾取、文本、图表入口和帧状态，已接近“上帝对象”。保留外观 API，但将内部拆成 `PathRecorder`、`TextPainter`、`PickPass`、`ChartPass`，由 `Gc` 编排。
- 线程约束主要靠注释和运行时异常。增加 `RenderThreadGuard`/`LifecycleState`，对 `NEW/READY/FRAME/DISPOSED` 和 GL 线程统一检查。
- `GLAbstraction` 仍暴露较底层的绑定状态操作。增加 `GLCapabilities`、`GLStateCache` 和结构化 `RenderPass`，减少调用方依赖隐式 GL 状态。
- 回调异常目前按帧报告并继续渲染。生产模式应支持 `FAIL_FAST`、`DISABLE_CALLBACK`、`LOG_AND_CONTINUE` 三种策略，并提供结构化错误事件。

## 3. 发布前必须完成的 P0

### 3.1 平台与依赖

- 将 JavaFX、LWJGL、OpenGLFX、Kotlin、JDK 版本集中在父 POM，并建立兼容矩阵；至少明确 Windows x64 的支持范围。
- 将 natives 按平台拆分为 classifier/profile（`windows-x86_64`、Linux、macOS），禁止把 Windows native 依赖写成唯一默认路径。
- 添加 Maven Wrapper、锁定依赖版本、SBOM 和依赖漏洞扫描；配置 `maven-source-plugin`、`maven-javadoc-plugin`、签名和发布仓库。
- 发布前验证干净机器：`mvn -U -DskipTests package`、示例启动、关闭窗口、重复启动/退出 50 次。

### 3.2 生命周期与线程安全

- 明确所有公开方法的线程：`Gc`/GL 资源只允许 GL 线程；JavaFX Node/回调只允许 FX 线程；数据提交使用快照或无锁队列。
- `dispose()` 必须幂等，禁止在销毁后提交帧、排队拾取或访问字体/图表资源；为每个入口补充失败状态测试。
- 统一处理窗口 resize、DPI 变化、上下文丢失和重新初始化；当前 `deviceScale` 只读取 `outputScaleY`，需要同时处理 X/Y 非等比缩放。
- 拾取请求必须有取消/超时/关闭语义；窗口销毁时回调队列中的请求应全部以取消或 `null` 结束，不能静默丢失。

### 3.3 渲染正确性与稳定性

- 把真实 GPU 验证纳入 CI 的可选硬件 job：普通颜色、MSAA、SDF 文本、拾取、图表、FFT、resize、透明度和裁剪各有最小场景。
- 修复或隔离当前被 `@Disabled` 的 2 个 Tessellator 回归用例；字体相关 9 个跳过用例必须提供受许可的测试字体或明确标记为环境测试。
- 对所有 shader 编译、FBO 完整性、GL error、SSBO barrier 失败返回结构化诊断，包含驱动、GL 版本、能力和资源尺寸。
- 增加超大坐标、空/NaN/Infinity、超长文本、超大 `pickRect`、超长路径和显存不足的边界测试，限制单次分配，避免 OOM 或整数溢出。

## 4. API 功能缺口与优先级

### P0：稳定契约

- 独立的 `CanvasContext`/`RenderSurface` 生命周期 API，不强制用户只能调用 `xuan {}`；提供 Java/Kotlin 两套入口。
- `CanvasCapabilities` 查询：OpenGL 版本、compute shader、MSAA、最大纹理/SSBO、可读回能力和降级模式。
- 坐标系统统一：逻辑像素、设备像素、Node 局部坐标、Canvas 世界坐标明确转换函数，并提供 `resize`/DPI 事件。
- 完整状态对象：`save/restore` 应覆盖 `lineCap`、`lineJoin`、`miterLimit`、`textAlign`、`textBaseline`、合成模式、裁剪和字体属性。

### P1：常用绘制能力

- `clearRect`、`arc/arcTo`、椭圆弧、`roundRect`、`Path2D`/路径复用。
- 线帽/连接样式、渐变（线性/径向）、图案填充、图像/纹理绘制、裁剪路径和离屏 Canvas。
- 文本 `font family/style/weight`、对齐/基线、换行、字距、字体 fallback、OTF/CFF 和 emoji/复杂脚本 shaping（可接 ICU/HarfBuzz）。
- `globalCompositeOperation`、阴影、颜色空间/预乘 alpha 的明确配置。

### P2：工程化能力

- 场景/节点树、脏矩形、批次统计、GPU/CPU 时间线、显存统计和 debug overlay。
- 图表补充时间/日期轴、对数轴、堆叠/误差线、交互缩放平移、可访问数据表和导出 PNG/SVG/CSV。
- 输入事件统一抽象：move、drag、wheel、touch、keyboard、capture/bubble、hover 取消。

## 5. 测试与质量门槛

当前 `mvn test` 通过，但这只能证明单元和 Fake GL 路径。建议建立三层测试：

1. **纯单元测试**：几何、矩阵、文本布局、坐标转换、图表布局、环形数据，目标行覆盖率 ≥ 85%。
2. **Fake GL 契约测试**：资源创建/释放、状态切换、批次合并、shader/FBO 错误，禁止未声明 GL 调用。
3. **真实渲染测试**：固定 800×600、125%/150% DPI、MSAA 0/4、至少 Windows/NVIDIA 与软件渲染；用截图金字塔或像素容差比较，并记录 GPU 信息。

所有渲染回归都应包含“连续帧、改变数据、改变窗口、销毁后调用”场景，避免只验证静态首帧。

## 6. 分阶段执行计划

### 阶段 A：发布阻塞（1～2 周）

生命周期状态机、DPI X/Y、取消拾取、错误诊断、补齐跳过测试、Maven Wrapper/依赖锁定、Windows 干净机启动关闭测试。

### 阶段 B：稳定 API（2～4 周）

提取 `CanvasContext`，冻结坐标和线程契约；补齐路径样式、文本属性、图像/渐变；为 JavaFX 集成建立 TestFX 或可控 headless harness。

### 阶段 C：性能与跨平台（4～8 周）

GL 状态缓存、持久映射/分段 VBO、脏区上传、离屏渲染、Linux/macOS natives、性能基准和显存/帧时间监控。

### 阶段 D：1.0 发布

API/ABI 兼容策略、CHANGELOG、迁移指南、示例工程、许可证与第三方声明、签名制品、版本矩阵和故障排查手册齐全后再发布。

## 7. 验收标准

达到 1.0 前至少满足：干净机器可按文档运行；支持平台的真实渲染测试无 P0/P1 缺陷；无未解释的跳过测试；连续运行 8 小时无 GL 资源增长；resize/DPI/关闭可重复 100 次；公开 API 的线程、生命周期、坐标和错误行为都有测试与文档；`xuan-core` 可在无 JavaFX/OpenGL 环境下独立使用。
