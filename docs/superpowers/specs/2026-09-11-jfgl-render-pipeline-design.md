# JFGL 绘制管线设计（子项目 A：引擎核心）

> 日期：2026-09-11
> 状态：已确认
> 范围：本文档只覆盖**子项目 A（引擎核心绘制管线）**。B/C/D 见第 2 节路线图，各自单独出 spec。

---

## 1. 背景与目标

JFGL 是基于 JavaFX + OpenGL（openglfx-lwjgl）的 2D 绘制引擎。当前代码库存在两套互不相通的渲染栈，且只有一套能真正画出东西（详见 `CLAUDE.md`）。

本设计**确立单一渲染栈**：以 GPU 批处理的立即模式绘制为核心，对外提供类似 Canvas 的 `gc` API，内部实现按性能与正确性最优选择。

**目标**

- 提供简单易用的 2D 几何图形 / 图表绘制 API
- 支持完整样式系统（纯色、渐变、图像填充、描边、文本）
- 渲染性能显著优于 JavaFX Canvas——后者的瓶颈是逐图元 CPU 光栅化，本设计把成千上万图元压成个位数 draw call
- 为文本（SDF）、GPU 拾取、科学绘图图表提供稳定的地基

**非目标（本期不做）**

- 保留模式场景图 / 节点树
- 任意路径裁剪（需模板缓冲）
- GPU compute 曲线细分
- 图表本身（属于子项目 D）

---

## 2. 子项目拆分与路线图

```
┌─ A. 引擎核心（绘制管线）──────────── 无依赖，必须最先   ← 本文档
├─ B. SDF 文本 ─────────────────────── 依赖 A
├─ C. GPU 拾取与交互 ───────────────── 依赖 A
└─ D. 科学绘图图表 ─────────────────── 依赖 A + B（+C 做交互）
```

**B 与 C 可并行**，均只依赖 A。D 是建立在前三者之上的应用层。

已确认的后续能力需求（供 B/C/D 的 spec 参考，本文档不展开）：

- 文本：**SDF 方案**，中文支持，任意缩放清晰，支持描边/阴影/发光
- 拾取：**GPU ID 缓冲方案**，逐顶点 ID 属性，PBO 异步读回，支持重叠遮挡精确拾取
- 图表：**科学绘图级**，含对数轴/时间轴、多 Y 轴、误差棒、箱线图、热力图 colormap、等值线/向量场

---

## 3. 架构

### 3.1 包结构

```
com.bingbaihanji.jfgl
├── gl/           L1  GL 资源封装      保留，扩充 Framebuffer / VertexBuffer
├── geom/         L1.5 纯几何，零 GL 依赖，可单测      新增
│                   Path（自 renderer/ 移入并扩充）
│                   Flattener（贝塞尔平坦化）
│                   Tessellator（填充三角化）
│                   StrokeGenerator（描边生成）
├── style/        L2  样式             重做
│                   Paint = Solid | LinearGradient | RadialGradient | ImagePattern
│                   StrokeStyle / Font
├── renderer/     L2  绘制管线核心      重做
│                   RenderBatch（顶点缓冲 + 命令列表 + flush）
│                   Gc（用户调用的 GraphicsContext 门面）
├── text/         L2  SDF 文本          子项目 B
├── pick/         L2  GPU 拾取          子项目 C
├── chart/        L3  科学绘图          子项目 D
├── math/ util/   原样保留
├── glview/       FXGLTransfer 保留
└── dsl/          重写，jfgl {} 入口
```

**`geom/` 独立成包是刻意的**：三角化、描边、平坦化都是纯计算，不碰 GL 上下文，因此可以脱离窗口跑单元测试。这是本层最值得测试的部分。

### 3.2 语言分工

沿用现有约定：**Java 写 `geom/` 与 `renderer/` 的热路径**，**Kotlin 写 `Gc` 门面与 `dsl/`**。Kotlin 的默认参数让 `fillRect(..., radius = 4f)` 这类 API 更干净，且编译后字节码与 Java 等价，无性能损失。

### 3.3 依赖方向

```
dsl/  →  renderer/  →  geom/  →  math/
              ↓          ↓
           style/      util/
              ↓
            gl/
```

依赖严格单向向下。`geom/` 不得 import `gl/`——这是它可单测的前提，需要由构建或评审保证。

---

## 4. 绘制管线

### 4.1 帧生命周期

```
gc.beginFrame(w, h)    // 顶点指针归零、命令列表清空、设置基础矩阵
   ... 全部绘制调用，只往数组尾部追加，无任何 GL 调用 ...
gc.endFrame()          // 一次上传顶点 → 按命令列表执行 draw call
```

**绘制调用不做 GL 交互**是设计的核心：GL 状态切换集中在一帧一次，而非每次绘制。

### 4.2 顶点格式（24 字节，无填充）

| location | 类型 | 字节偏移 | 说明 |
|---|---|---|---|
| 0 | `vec2` float | 0 | 位置（已烘焙到 NDC，见 5.1） |
| 1 | `vec2` float | 8 | 纹理坐标 |
| 2 | `vec4` ubyte normalized | 16 | 颜色（**预乘 alpha**） |
| 3 | `uint` | 20 | 拾取 ID |

stride = 24。location 3 用 `glVertexAttribIPointer` 传递整数而非归一化浮点。

**`id` 属性从第一期就纳入格式**：拾取（子项目 C）复用同一条管线，只是换个 fragment shader。若后期再加，就要改动整个顶点格式与所有图元发射代码，代价高得多。

### 4.3 着色器

单顶点着色器 + 单片元着色器即可覆盖全部样式：

```glsl
// vertex —— 位置已是 NDC，无需矩阵 uniform
#version 330 core
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
layout(location = 3) in uint aId;
out vec2 vUV;
out vec4 vColor;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
}

// fragment
#version 330 core
in vec2 vUV;
in vec4 vColor;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV) * vColor;
}
```

### 4.4 无索引缓冲

每个三角形直接追加 3 个顶点，**不使用 EBO**：

| | 每矩形顶点数 | 每矩形字节 |
|---|---|---|
| 索引模式 | 4 顶点 + 6 索引 | 120 B |
| 无索引 | 6 顶点 | 144 B |

仅多 20% 带宽，但省掉索引缓冲上传、EBO 绑定状态，以及"四边形索引"与"路径三角形索引"两套代码路径。图表规模下带宽差异可忽略，**简单性优先**。使用 `drawArrays`。

> 若将来性能剖析显示顶点带宽成为瓶颈，把矩形/字形发射改为"静态索引缓冲 + baseVertex"是一处局部改动，不影响其余设计。

### 4.5 命令列表

```java
final class DrawCommand {
    int textureId;
    int firstVertex, vertexCount;
    int scissorX, scissorY, scissorW, scissorH;
}
```

**禁止帧内排序**——2D 中绘制顺序即 z 序，排序会破坏图层关系。因此只合并**相邻的**同状态命令：新绘制调用到来时，若 `(texture, scissor)` 与上一条命令一致则继续追加，否则开启新命令。

推论：同纹理/同裁剪的图元连续绘制合批效果最好。文本天然满足（所有 SDF 字形共用图集）；图表需按样式分组组织绘制顺序（子项目 D 的职责）。

### 4.6 缓冲增长

初始容量 65536 顶点（约 1.5 MB），超出则**容量翻倍**（重新分配 Java 数组 + 新建 GL 缓冲）。

同时设置硬上限（建议 1M 顶点，约 24 MB）。达到上限时执行**帧中途 flush** 后继续追加——该路径复用与 `endFrame()` 完全相同的命令重放逻辑（每条命令自带纹理与裁剪状态，不依赖全局状态），因此无需任何额外处理，内存也就有了确定的上界。

正常路径下扩容几乎不会触发；帧中途 flush 属于兜底路径，需测试覆盖。

### 4.7 渲染调度

保留现有 `engine/RenderScheduler` 的 `CONTINUOUS` / `ON_DEMAND` 双模式：画面静止时切到 `ON_DEMAND` 可避免持续占用 GPU 与电量，有动画时用 `CONTINUOUS`。

注意一个立即模式特有的行为：`Gc` 的绘制调用只是追加顶点（纯 CPU 内存操作），因此**跳过整帧不会影响正确性**，但用户写在 `onRender` 里的绘制代码仍会逐帧执行。若追求极致，可让 `beginFrame()` 返回一个"本帧是否需要绘制"的布尔值，由用户自行短路重活。

---

## 5. 坐标系统与变换

### 5.1 用户坐标：像素、原点左上、y 向下

这是 2D 绘制 API 的事实标准（HTML Canvas、Skia、Cairo、CoreGraphics 均如此），对使用者最直观，图表代码尤其受益（"绘图区从 (60, 40) 到 (740, 540)"）。

内部在烘焙变换时乘一个基础矩阵转成 NDC。窗口 resize 时更新基础矩阵。

### 5.2 变换在 CPU 端烘焙

`save() / translate() / rotate() / scale() / transform() / restore()` 维护一个 `Mat3` 栈（复用现有 `math/Mat3`）。顶点写入缓冲前已是最终 NDC 坐标。

**好处：换变换不产生任何状态切换，不破坏合批。** 代价是缩放时每帧需重新平坦化路径——图表规模下可忽略。

---

## 6. 裁剪

使用 `glScissor`，仅支持矩形裁剪。嵌套裁剪在 CPU 端求交集。

这正好覆盖图表最核心的需求——把折线系列裁剪在绘图区内。`glScissor` 是纯状态、无 shader 开销。

任意路径裁剪（需模板缓冲）**明确不在本期范围**。

> 实现注意：scissor 使用帧缓冲像素坐标且 y 轴向下，与 NDC 相反，换算时易出错，需重点测试。

---

## 7. 样式系统

### 7.1 Paint 类型

```kotlin
sealed class Paint {
    object White : Paint()                              // 纯色走 vColor
    class Linear(x0, y0, x1, y1, stops) : Paint()       // → 1×256 LUT 纹理
    class Radial(cx, cy, r, stops) : Paint()
    class Image(bitmap, repeat) : Paint()
}
```

### 7.2 全部归一化为纹理

| Paint | 实现 | UV 来源 |
|---|---|---|
| 纯色 | 绑 1×1 白纹理，颜色走 `vColor` | 常量 |
| 线性/径向渐变 | 烘焙为 1×256 RGBA8 LUT 纹理 | CPU 端把顶点投影到渐变轴/半径 |
| 图像填充 | 真实纹理 | 映射到形状包围盒 |

渐变的 UV 在 CPU 端算好，因此**渐变不破坏合批**，只是切换纹理。这避免了"每个渐变重设 uniform 打断合批"这一常见陷阱。

### 7.3 纹理延迟上传（解决线程约束）

**问题**：LUT 纹理与图像纹素需要 GL 上下文，但用户常在 JavaFX 线程构造 `Paint`（例如按钮回调里改样式），那里没有当前 GL 上下文。

**解法**：纹理上传延迟到 GL 线程。

```
用户线程（JavaFX）                 GL 线程（beginFrame）
──────────────────                ─────────────────────
new LinearGradient(...)
   ↓
只登记像素数据，无 GL 调用   ──→   遍历待上传队列
                                   glGenTextures / glTexImage2D
                                   回填 textureId 到 Paint
                                   ↓
                                   后续绘制可直接引用
```

`Paint` 在**首次被绘制的那一帧**完成真正上传。用户无需了解线程模型，也无需手动 `Disposable`——引用计数归零后由引擎回收。

图像填充同理，顺带接入 `lwjgl-stb`（已在 pom 中声明但从未使用）做图片解码。

---

## 8. 几何

### 8.1 路径与平坦化

`Path` 支持 `moveTo / lineTo / quadTo / cubicTo / arc / closePath`，自现有 `renderer/Path.java` 移入 `geom/` 并补充 `arc`、包围盒、平坦度控制。

**平坦化容差绑定当前变换，且以设备像素为单位**（建议 0.25 px）：

```
贝塞尔曲线 → 折线（flatten）→ 三角化
                ↑
        容差 0.25 设备像素
```

放大 10 倍时曲线自动细分更密，缩小时不浪费顶点。若容差写死在世界单位，放大后曲线会露出折线棱角——这是许多简易 2D 库的通病。

### 8.2 填充三角化

采用**耳切法 + 孔洞桥接**，覆盖矩形、凸/凹多边形、环形（饼图中心挖空）等图表常见形状。

病态输入（自相交、退化）**不崩溃、不静默出错**：检测到即跳过并记录警告，留待将来由模板缓冲方案兜底。

### 8.3 描边生成

折线两侧偏移生成三角带，需支持：

- **接头**：miter（含 miter limit 超限回退 bevel）/ round / bevel
- **端点**：butt / round / square
- **虚线**：按 dash pattern 先切分折线再生成

两个必须明确的行为：

1. **在局部空间生成描边轮廓**，再由 CPU 变换处理。因此非均匀缩放（`scale(2, 1)`）下，圆的描边会正确变成椭圆环，而非宽度不均的怪异形状。这是正确性问题，不是优化。
2. **凹角处自相交会产生重叠三角形**。配合预乘 alpha，不透明描边无影响；半透明描边会出现颜色叠加。

   **取舍：接受该瑕疵，不做 stencil strobing，并在文档中说明。** 为一个边缘场景引入第三遍渲染不划算。

---

## 9. 混合与抗锯齿

- **混合模式：预乘 alpha**，`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`。
  用户 API 仍收直觉的直通 alpha，由 `Gc` 在打包顶点时预乘。这样重叠的半透明 AA 边缘不会出现二次混合的暗缝——直通 alpha 在此场景下会产生该经典瑕疵。

- **抗锯齿：MSAA**。`GLCanvas` 构造函数已提供 `msaa` 参数，零额外实现成本。
  解析式 AA（nanovg 风格，shader 内羽化）对细线质量更好，但需为每个顶点附带边缘距离属性，**留作第二阶段优化**；顶点格式已预留扩展空间。

---

## 10. 错误处理

- **着色器编译/链接失败** → `ShaderCompileException`，**必须包含 info log**。现有 `ShaderProgram` 只抛一句 `"Shader compile failed"`，无编译日志，排查时等于没有信息，需一并修复。
- **状态误用**（无路径时 `stroke()`、`endFrame()` 未配对 `beginFrame()`、空栈 `restore()`）→ `IllegalStateException`，附当前状态栈深度。
- **GL 回调中的异常不得被静默吞掉**。openglfx 的回调异常可能只进日志，表现为"窗口全黑且无任何报错"。需要明确的上报通道：异常转存后于下一次 `endFrame()` 重新抛出并终止渲染循环，让用户看到真实堆栈。

---

## 11. 测试策略

当前项目零测试。本设计的包划分刚好把最易出错的部分切成可测单元。

| 层 | 手段 | 覆盖内容 |
|---|---|---|
| `geom/` | JUnit 单元测试 | 三角化：面积守恒、无退化三角形、顶点数；描边：顶点位置、闭合性、接头/端点/虚线；平坦化：容差与分段数 |
| `renderer/` | 黄金图测试 | 渲染到 FBO → 读回像素 → 与基准图比对 |
| `gl/` | 离屏上下文集成测试 | 着色器编译、缓冲生命周期 |

**黄金图测试是渲染引擎最有效的回归手段**：批处理、混合模式、AA、裁剪改动的视觉后果无法通过断言顶点数据发现，但比对像素能立即暴露。建议对每个关键特性（填充 / 描边 / 渐变 / 裁剪 / 图像）各保留一张基准图。

`geom/` 层应追求高覆盖率单测；渲染层用少量但关键的黄金图覆盖。

---

## 12. 与现有代码的迁移

**直接复用（不改动）**
`math/`（Vec2 / Mat3 / Transform）、`util/`（Color / Rect / Disposable）、`gl/ShaderProgram`、`gl/Texture`、`glview/FXGLTransfer`

**移动并扩充**
`renderer/Path.java` → `geom/Path.java`

**重写**
- `renderer/BatchRenderer.java`——现有设计（固定 10000 四边形上限、每帧 `Arrays.copyOf` 整个缓冲、无命令列表、无裁剪、无纹理）撑不起本 API，但顶点格式思路可沿用
- `dsl/DrawDSL.kt`——现每次绘制都 `glGenVertexArrays` + `glDeleteVertexArrays`，正是要消灭的模式
- `style/FillStyle.java`——由 Paint 体系取代

**删除**
`scene/`（Node / ShapeNode / GroupNode / TextNode / Scene / QuadTree）、`event/EventDispatcher`、`chart/` 四个图表类。

**取代**
- `engine/DrawEngine` —— 职责由 `renderer/RenderBatch` + `Gc` 接管。其中的**相机（pan / zoom / rotate）不保留在引擎层**：立即模式下平移缩放由用户在 `gc` 上直接 `translate / scale` 更直观。图表的"数据坐标 → 屏幕坐标"映射属于子项目 D，按需自行实现。
- `engine/RenderScheduler` —— **保留复用**（见 4.7）。

理由：被删部分的 `render()` 为空实现，且已确定走立即模式。保留一套"看似可用、实际画不出东西"的保留模式 API，会让每次改动都需要先判断"这是哪套栈"。被删的图表数据→屏幕映射数学在子项目 D 中可复用（文档有记录）。

---

## 13. 未决事项

以下问题不阻塞本设计，但需在实现中验证：

1. **模板缓冲可用性**——若将来要用 stencil 兜底填充或做路径裁剪，需确认 openglfx 的 `GLCanvas` 默认帧缓冲是否带 stencil 位；不带则需自建带 stencil 的 FBO。
2. **MSAA 与拾取 FBO 的交互**——拾取不需要抗锯齿（需要精确 ID），但 1px 细线的可拾取性会受影响。子项目 C 需决定拾取 FBO 是否匹配采样数。
3. **Paint 引用计数的回收时机**——需确认在 JavaFX 线程释放、GL 线程删除纹理的具体协议，避免竞态。

---

## 14. 下一步

本文档经review确认后，交由 writing-plans 技能生成实施计划（子项目 A），再进入实现。
