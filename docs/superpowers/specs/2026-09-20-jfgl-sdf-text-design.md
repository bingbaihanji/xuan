# JFGL SDF 文本子系统设计（子项目 B）

**日期**：2026-09-20
**状态**：待评审
**前置**：子项目 A（单一批处理管线）、子项目 C（GPU 拾取）已完成

---

## 1. 目标

给 JFGL 加上文本绘制能力，要求：

1. **支持中文**——不是"能显示几个汉字"，而是完整覆盖 CJK 常用区。
2. **任意缩放清晰**——同一个字形在 10px 和 200px 下都不应是拉伸的糊图。
3. **融入现有批处理管线**——文本是又一类图元，不该另起一条渲染路径。
4. **能被拾取**——顶点格式的 `id` 属性已经就位，文本应免费获得这个能力。

**驱动场景**：子项目 D（科学图表）的刻度、图例、标题、坐标轴标签。即"几十个不同字符、若干固定字号"。

## 2. 非目标（本期明确不做）

写在这里是为了让后来者知道"这是刻意不做，不是漏了"，而不是当成缺陷去"修"。

| 不做 | 理由 |
|------|------|
| **字距与复杂整形**（kerning / ligature / bidi） | `stb_truetype` 只读传统 `kern` 表，拿不到 GPOS。中文不需要；拉丁文会略紧（"AV" 这类）。做对需要 HarfBuzz 级工程，是另一个量级。 |
| **多行、换行、对齐** | 本期只做单行 + 定位。图表标签够用。 |
| **富文本**（同段混排字号/颜色/字体） | 同上。 |
| **多字体与字体回退** | 本期**全局单字体**。缺字渲染成 `.notdef`（豆腐块），不静默跳过。 |
| **多档 em 尺寸桶** | 见 §9 已知风险。单桶起步。 |
| **异步字形生成** | 见 §9。本期同步。 |
| **MSDF** | 8SSEDT 单通道对本期的质量要求足够，实现量低一个数量级。 |
| **斜体 / 粗体合成** | 用不同字体文件解决，本期不做字形变换合成。 |

## 3. 架构

沿用项目既有的分层。**关键点：SDF 生成是纯数组运算，没有任何 GL 依赖**——所以它落在 L0，能写扎实的单测。这是本设计最重要的一个切分。

```
L3  门面        renderer.Gc 的 drawText / measureText
L2  提交        renderer.RenderBatch 的 SDF 着色器；DrawCommand 的材质选择
L1  CPU 顶点    renderer.VertexWriter（文本走同一条出口）
    GL 抽象     gl.GLAbstraction 新增 R8 纹理创建与局部上传
L0  文本计算    text.FontFile       —— stb 封装：字形索引与度量
                text.GlyphRasterizer —— 字形 → 8 位覆盖度位图
                text.SdfGenerator    —— 覆盖度 → 有符号距离场  ★ 纯数组
                text.TextLayout      —— 度量序列 → 四边形顶点    ★ 纯算术
    GL 资源     text.GlyphAtlas      —— R8 纹理 + 货架分配 + LRU
```

### 3.1 单元职责

**`text/FontFile`（Java）**
加载 TTF 字节，持有 `STBTTFontinfo`。提供：字形索引（码点 → glyph index）、`advance`/`lsb`/`bbox`、`scaleForPixelHeight`。
实现 `Disposable`：stb 要求字体字节在 `STBTTFontinfo` 整个生命周期内保持有效，所以本类持有 `MemoryUtil.memAlloc` 的内存块并在 `dispose()` 释放。**不要**用 `MemoryStack`——字体数据远超 64 KB（参见记忆里那条 LWJGL 陷阱）。

**`text/GlyphRasterizer`（Java）**
`FontFile` + glyphIndex + 像素尺寸 → `GlyphBitmap`（宽高 + 8 位覆盖度 + 左旁距/上偏移）。内部用 `stbtt_MakeGlyphBitmap`。

**`text/SdfGenerator`（Java，纯）**
输入覆盖度位图，输出 8 位距离场。算法：阈值化 → 外扩 `spread` 像素的空白边 → 8SSEDT（两次扫描：先算到最近"内部"像素的距离，再算到最近"外部"像素的距离，相减得到有符号距离）→ 编码。
编码约定：**0 = 远在字形外，128 = 边界，255 = 远在字形内**。
**这是纯函数**：输入输出都是数组，零依赖，是本期的重点单测对象。

**`text/TextLayout`（Java，纯）**
输入：`List<GlyphSlot>`（每个含 uv 矩形、像素偏移、尺寸、advance）+ 起点 + 缩放 + 颜色 + 拾取 ID。
输出：写进 `VertexWriter` 的四边形顶点。
**不碰 GL、不碰 stb**，输入全是普通数值，因此可用 `VertexWriter` 直接单测。

**`text/GlyphAtlas`（Java）**
R8 纹理 + 货架（shelf）分配器 + LRU 淘汰。

**它不认识字体，也不认识 SDF。** 它是一个通用的「小位图图集」：给一个 key，返回一个 uv 矩形；未命中时通过一个**函数式接口 `GlyphSource`**（`byte[] pixelsFor(key)` → 位图 + 尺寸）索取像素。

这个切分是**为了可测**：`GlyphAtlasTest` 喂一个返回固定尺寸位图的假 `GlyphSource`，就能在不加载 stb、不开 GL 上下文的前提下测全部分配与淘汰逻辑。真实实现 `FontGlyphSource`（把 `FontFile` + `GlyphRasterizer` + `SdfGenerator` 串起来）则由校验器覆盖。

只依赖 `GLAbstraction` **接口**，因此和 `PickBuffer` 一样**能用 `FakeGLAbstraction` 零 GL 上下文单测**。

**`gl/GLAbstraction` 新增**
```java
int createR8Texture(int width, int height);
void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels);
void deleteTexture(int texture);   // 已存在
```

**`renderer/DrawCommand` 变更**
新增 `Material` 选择位（`COLOR` / `SDF_TEXT`）。`VertexWriter.setState` 多一个参数，合批判据多一项。**这是本期唯一改动现有管线契约的地方。**

**`renderer/Gc` 新增**
```kotlin
var fontSize: Float = 16f    // 进 save/restore 栈（浮点部分）
fun drawText(text: String, x: Float, y: Float): Float   // 返回推进宽度
fun measureText(text: String): Float
```

**`(x, y)` 是基线的起点，不是文本框左上角。** 即 `y` 是文字**基线**所在的像素行，`x` 是第一个字形的笔位置。
选基线而不是左上角，是因为只有基线是排版的稳定参照——图表的刻度文字要沿轴线对齐时，基线对齐才是想要的；而"左上角对齐"会让不同高度的字符视觉上跳来跳去。
这条**必须写进 KDoc**：它是调用方最容易猜错、且猜错后"看起来只是位置偏了一点"的那类约定。

`pickId` 对文本同样生效——文本天然可拾取。

**样式栈变更**：`fontSize` 加入浮点部分，`FLOATS_PER_STYLE_LEVEL` 由 **2 变 3**。
这是子项目 C 里 `pickId` 加入整数栈时**同一个坑**：`save` / `restore` / `ensureStyleCapacity`
**三处的下标算术必须同步改**，漏改不会报错，只会让相邻两层互相覆盖（`restore` 后读回上一个值）。**改动时三处一起看。**

### 3.2 三个必须钉死的常量

这三个值决定图集能不能装下、字形会不会被裁，写死在类里但在**同一处**定义，调参只改一处：

| 常量 | 值 | 含义 |
|------|-----|------|
| `GLYPH_EM_SIZE` | 48 | SDF 生成时的 em 高度（像素）。字形在此尺寸下光栅化，之后靠 SDF 任意缩放 |
| `SDF_SPREAD` | 8 | 距离场向外/向内延伸的像素数。决定了"多粗的笔画边缘能被平滑处理"，也决定单字形位图的边长 = 字形尺寸 + 2×spread |
| `ATLAS_MAX_SIZE` | 4096 | 图集边长上限（达到后不再扩容，装不下就抛异常）。4096×4096 的 R8 是 16 MB 显存 |

**单字形位图的最大边长 = 48 + 2×8 = 64 像素**（假设字形不超过 em 框）。图集宽度取 64 的整数倍（如 1024），否则货架分配会浪费整行。

## 4. 数据流

```
gc.drawText("中文", x, y)
  └─ 逐码点：
       FontFile.glyphIndex(cp) → 度量(advance/lsb/bbox, 以 font units 计)
       GlyphAtlas.acquire(glyphIndex)      ← 未命中则：
            GlyphRasterizer.rasterize(...)        覆盖度位图（48px em）
            SdfGenerator.generate(...)            距离场
            gl.uploadR8SubImage(...)              上传到图集
       返回 GlyphSlot（uv 矩形 + 偏移 + advance）
  └─ TextLayout 把 slot 序列写成四边形顶点 → VertexWriter
  └─ 一次 submit：图集是一张纹理，连续文本合并成一条 draw call
```

**同步**：整个 `drawText` 在 GL 线程上完成，包括首次字形的光栅化与 SDF 计算。理由见 §9。

## 5. 必须做对的细节（会静默出错的地方）

这一节是本设计最该被认真读的部分。以下每一条错了都不会报错，只是画面悄悄不对。

### 5.1 `GL_UNPACK_ALIGNMENT` —— R8 上传的头号陷阱
`glPixelStorei(GL_UNPACK_ALIGNMENT)` 默认是 **4**。R8 纹理每行是 `width` 个**字节**，当 `width` 不是 4 的倍数时，GL 会按 4 字节对齐去读，**从第二行起整行错位**，表现为字形被斜切。
上传前必须设 `glPixelStorei(GL_UNPACK_ALIGNMENT, 1)`，**并在上传后恢复**（其他纹理上传依赖默认值）。

### 5.2 `GL_R8` 不是 `GL_R8UI`
拾取用的是 `GL_R32UI`——**整数**格式，必须 `GL_NEAREST`、不能用 `glClearColor` 清。
SDF 用的是 `GL_R8`——**归一化**格式，可以用 `GL_LINEAR` 过滤，这恰恰是我们要的（SDF 插值出平滑边缘）。
两者只差两个字母，混用会得到"要么全糊、要么锯齿"且不报错。**写代码时看清。**

### 5.3 LRU 不能淘汰本帧用过的槽
淘汰一个槽之后，**本帧早先已经写进顶点缓冲的四边形仍然引用那个 uv**——于是它会采样到别人的字形。这在**同一帧内**就会发生，不是跨帧问题。
约束：淘汰只针对**本帧未被使用**的槽（每个槽记一个"最后使用帧号"，与当前帧号比较）。

### 5.4 uv 的半点偏移
纹理采样点落在纹素中心（`i + 0.5`）。字形矩形的 uv 必须按 `(x + 0.5) / atlasWidth` 计算，否则会采到相邻纹素——表现为边缘发虚或字形轻微错位。**这是"看起来只是有点糊"的那类错误**，很难靠肉眼定位。

### 5.5 预乘 alpha 的合成方式
顶点色是**预乘**的。SDF 着色器应当是：

```glsl
float d  = texture(uTex, vUV).r;
float sd = d - 0.5;
float w  = fwidth(d);
float a  = smoothstep(-w, w, sd);
fragColor = vColor * a;        // vColor 已是预乘色，乘一个标量不破坏预乘性
```

**不要**写成 `vec4(vColor.rgb, vColor.a * a)`——那会破坏预乘关系，半透明文字的边缘会出现暗缝（与渲染管线文档里那条"混合因子顺序不能调换"是同一类问题）。

`fwidth` 是 GLSL 内建导数函数，GL 3.3 core 直接可用，不需要扩展。

### 5.6 缺字要可见
字体没有某个码点时，`stbtt_FindGlyphIndex` 返回 0（`.notdef`）。**必须照常画 `.notdef`**（通常是个方框），不能静默跳过——静默跳过的表现是"这段文字少了几个字"，用户会以为是排版 bug。

### 5.7 图集的边界条件
- 单字形的 SDF 位图（48 + 2×8 = 64 像素见方）**必须能装进一个货架行**，否则分配会死循环。图集宽度必须 ≥ 最大字形宽度。
- 图集扩容到上限后若仍装不下：**抛异常**，并在消息里说明如何调大上限。不静默不画。

## 6. 字体文件

**本期做法**：把 `C:\Windows\Fonts\simhei.ttf`（黑体，9.7 MB，**纯静态 TrueType**，只有 `glyf` 表）复制到 `src/main/resources/fonts/simhei.ttf`。

选它的理由：它是 `stb_truetype` 最不容易出岔子的输入。同机可选的 `NotoSansSC-VF.ttf` 是**可变字体**（带 `fvar`/`gvar`），stb 会忽略变体轴、只渲染默认实例——原则上能用，但第一次实现时不该同时跟可变字体较劲。

**路径可配置**：`Gc` 提供替换字体的入口，默认从 classpath 的 `/fonts/simhei.ttf` 加载。换字体不应改代码。

**授权备注**：黑体是微软/中易的专有字体，本机自用没有问题；**若这个仓库要公开分发，应换成 OFL 授权的字体**（`NotoSansSC-VF.ttf` 就在同一目录，或从网上取静态版 Noto Sans SC）。这一点写进 `fonts/README.md`。

**注意**：把一个 9.7 MB 的二进制文件提交进 git 是**不可逆的仓库增重**（历史里永久保留）。确认这符合预期再提交。

## 7. 错误处理

| 情况 | 行为 |
|------|------|
| 字体文件缺失 / 无法解析 | **启动时抛异常**，消息里给出期望路径与配置方式。不退化成"画不出任何字"。 |
| 字形位图为空（如空格） | 只推进 advance，不发顶点。**这是正常的，不是错误。** |
| 码点不在字体里 | 画 `.notdef`（见 §5.6） |
| 图集装不下 | 抛异常，消息含当前上限与调整方法（见 §5.7） |
| `drawText` 遇到非法码点（孤立代理项） | 画 `.notdef`，不抛异常——文本可能来自用户输入 |

## 8. 测试与验证

沿用本项目的纪律：**纯计算的写单测，需要 GL 的写像素校验器，两者都要做变异验证。**

### 8.1 单测（无 GL 上下文）

| 测试类 | 覆盖 |
|--------|------|
| `SdfGeneratorTest` | **重点**。手绘输入断言距离值：单像素点、直线、方块、L 形（验证角点的距离比边中点远，即真欧氏距离而非切比雪夫）；`spread` 边界；全空与全满的退化输入 |
| `TextLayoutTest` | 用假 `GlyphSlot` 序列 + 真 `VertexWriter`，断言顶点坐标、uv、推进总量、空串不发顶点 |
| `GlyphAtlasTest` | 用 `FakeGLAbstraction` + 假 `GlyphSource`（返回固定尺寸位图）：分配不重叠、跨行、满时扩容、LRU 淘汰顺序、**本帧用过的槽不被淘汰**（§5.3）、装不下时抛异常 |
| `FontFileTest` | 度量换算与坐标约定。（会加载 stb native 库；若 surefire 环境加载失败，则把 stb 调用隔离到一个薄接口后面，用假实现测上层——**这条不确定性要在实现时验证，不要假设**） |

### 8.2 像素校验器 `TextVerifier`

新建 `example/TextVerifier.kt`，与 `PipelineVerifier` / `PickVerifier` 同构（回读帧缓冲、逐条断言、退出码 0/1）。

要断言的：

1. **推进宽度**：已知字符串的墨迹右边界在预期范围内（`advance` 累加正确）。
2. **基线位置**：不同高度的字符（如 "一" 与 "国"）的相对位置符合度量。
3. **★ SDF 的定义性特征**：同一个字以 12px 与 96px 绘制，**边缘过渡带宽度应大致恒定**（约 1 个屏幕像素）。位图拉伸时过渡带会随缩放线性变宽——**这是唯一能把"SDF 生效"与"位图被放大"区分开的断言**，也是这个子系统的核心验收点。
4. **空串不画任何东西**：与背景像素完全一致。
5. **新字形首次出现后位置稳定**：第 N 帧与第 N+1 帧同一字符串的墨迹包围盒一致（缓存生效，没有重复分配导致的漂移）。
6. **文本可被拾取**：给文本设 `pickId`，在其墨迹上 `pick` 应命中（复用已完成的拾取能力）。

### 8.3 变异验证（每条做完立刻还原）

| 变异 | 应失败 |
|------|--------|
| `SdfGenerator` 内外距离相减的顺序反过来（编码反相） | 校验器 1/2，字形反相 |
| uv 去掉 `+0.5` 纹素偏移 | 校验器 3（过渡带异常） |
| 上传前不设 `GL_UNPACK_ALIGNMENT = 1`（用一个宽度非 4 倍数的图集） | 校验器 1/2，字形斜切 |
| LRU 允许淘汰本帧用过的槽 | `GlyphAtlasTest` 的 §5.3 那条 |
| SDF 着色器写成 `vec4(vColor.rgb, vColor.a * a)` | 半透明文字边缘出现暗缝（若难以断言，如实记录"未能覆盖"，不要假装覆盖了） |

## 9. 已知风险与取舍

**R1（最重要）：小字号质量。** SDF 的已知弱点是**小字号不如直接位图清晰**——而图表刻度恰恰是 10–12px。单桶（48px em）缩到 10px 时，SDF 的双线性插值会丢掉一些笔画细节。

应对顺序：先实测（校验器第 3 条会给出客观数据），若不可接受，再考虑加一个"小字号走独立位图桶"的路径。**先不要预先优化**——那会显著增加复杂度（多桶意味着缓存键变成 (glyph, bucket)，淘汰与扩容都要按桶管理）。

**R2：同步生成的首帧卡顿。** 首次显示一整屏新汉字可能停顿几十毫秒。驱动场景（图表几十个字符）下约几毫秒。**异步生成留作明确的后续路径**：CPU 侧（光栅化 + SDF）本就是纯计算，搬到工作线程无需改动任何 GL 代码，GL 线程只需每帧排空队列做上传。真要换时，代价主要在"字形未就绪时这一帧画什么"的取舍上。

**R3：`stb_truetype` 对 CFF/PostScript 轮廓（`.otf`）支持较弱。** 这是选 `simhei.ttf`（纯 `glyf`）的直接原因。用户换字体时应优先选 TTF。

**R4：无字体回退。** 单字体意味着该字体没有的字符一律 `.notdef`。CJK 字体通常同时覆盖拉丁与标点，实际影响有限。

## 10. 交付物清单

**新建**
- `src/main/java/com/bingbaihanji/jfgl/text/{FontFile, GlyphRasterizer, GlyphBitmap, SdfGenerator, GlyphSlot, TextLayout, GlyphAtlas, GlyphSource}.java`
  （`GlyphAtlas` 是通用小位图图集，不认识字体；`GlyphSource` 是它索取像素的函数式接口，真实实现 `FontGlyphSource` 把 `FontFile` + `GlyphRasterizer` + `SdfGenerator` 串起来）
- `src/main/resources/fonts/simhei.ttf` + `src/main/resources/fonts/README.md`
- `src/main/kotlin/com/bingbaihanji/jfgl/example/TextVerifier.kt`
- `src/test/java/com/bingbaihanji/jfgl/text/{SdfGeneratorTest, TextLayoutTest, GlyphAtlasTest, FontFileTest}.java`

**修改**
- `gl/GLAbstraction.java`、`gl/LwjglGLAbstraction.java` —— R8 纹理创建与局部上传（含 `GL_UNPACK_ALIGNMENT` 处理）
- `renderer/DrawCommand.java` —— 材质选择
- `renderer/VertexWriter.java` —— `setState` 多一个参数，合批判据多一项
- `renderer/RenderBatch.java` —— SDF 着色器、按材质切换
- `renderer/Gc.kt` —— `fontSize` 进样式栈、`drawText` / `measureText`
- `CLAUDE.md`、`README.md` —— 与子项目 C 同样的收尾
