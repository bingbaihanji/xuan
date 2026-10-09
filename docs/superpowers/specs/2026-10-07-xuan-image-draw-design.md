# Xuan 图片绘制（drawImage / createImage）设计

日期：2026-10-07
状态：**已实现并通过验收**（`ImageVerifier` 24 条断言，退出码 0；单测 +20 条）

## 1. 问题

`Gc` 里没有任何绘制图片的方法，而底层管线其实已经为它准备好了一大半：

| 环节 | 现状 |
|---|---|
| 顶点格式 | 已有 `vec2 uv`（`VertexFormat.OFFSET_UV` = 8） |
| 顶点写入 | `VertexWriter.quad(..., u0,v0,u1,v1, color, id)` 现成，文本在用 |
| 片段着色器 | `texture(uTex, vUV) * vColor * (ac * aa)` —— 本来就是"纹理 × 顶点色" |
| 命令状态 | `DrawCommand.textureId` 已是逐命令状态，合批判据里包含纹理 |
| 上传 | `GLAbstraction.createTexture(w, h, int[] argb)`，而 `Gc.glAbstraction()` 是公开的 |

缺的是三件事：一个公开入口、一条**预乘**上传路径、一套纹理生命周期。

## 2. 决策与理由

### 2.1 两条入口，一套机制（用户选定）

```kotlin
gc.drawImage(pixels, w, h, dx, dy)
gc.drawImage(pixels, w, h, dx, dy, dw, dh)
val img = gc.createImage(pixels, w, h)
gc.drawImage(img, dx, dy)
gc.drawImage(img, dx, dy, dw, dh)
img.dispose()
```

**纹理缓存的键就是那个 `int[]` 对象本身**（`IdentityHashMap`），句柄只是把同一个键
包一层。于是两条入口不是两套机制——同一张图用哪种方式画，命中同一个纹理槽。

- 备选：只做裸数组版（零负担，但无法确定性释放）；
- 备选：只做句柄版（上传时机明确、内容可变，但多一个"要记得调的 API"，
  而本仓库在 `ChartRenderer` 那处明确论证过反对它：忘记的症状是静默的）。
- 取两者，用同一个键把机制收敛成一份。

**代价（已写进 KDoc 并由断言钉住）**：同一个数组改了内容不会被感知。
按内容比较要在每次绘制时扫一遍整张图（100 万像素 = 每帧 4 MB 的读），
收益只是省下"用户把同一张图读了两遍"那点显存。**不划算，所以不做，但要写清楚。**

### 2.2 只做目标矩形，不做源子矩形

四个重载（两个入口 × 原尺寸/缩放）。精灵图请自己裁数组，或每个子图建一个句柄——
能力没丢，而源矩形越界/子像素采样是新的静默错误面。

### 2.3 预乘在上传时做

混合因子是 `GL_ONE / GL_ONE_MINUS_SRC_ALPHA`（预乘），而纹素是**直接采样**的，
着色器不会替它补上预乘。不做的话半透明像素**过亮一倍**
（50% 透明纯红画在黑底上读 `(255,0,0)` 而不是 `(128,0,0)`），而画面只是"颜色艳了点"。

- 做在上传时而不是着色器里：上传只发生一次，着色器里做会让每个像素每帧多乘一次。
- 取整是**四舍五入** `(c * a + 127) / 255`：截断会让极低 alpha 的图系统性偏暗。
- **与既有的 `argbToRgba` 共用唯一一份字节序核心**（一个私有核心 + 两个具名入口），
  不是两份拷贝——本仓库为"同一份契约两份实现"吃过亏（`gl/Texture` 里那份独立拷贝
  把 RGB 写成了 BGR，没有任何东西发现它）。

### 2.4 顶点色恒白，只乘 `globalAlpha`

**不取 `fill`**：`fill` 是环境状态，给文字设了 `fill = 黑` 不该让图片跟着变黑——
那种染色错了画面"只是颜色不对"，极难归因。染色需求留给将来的显式重载。

### 2.5 缓存按对象身份 + 代际自动回收

照搬 `ChartRenderer` 对 `Series` 的处置（含 `GRACE_GENERATIONS = 2` 及其理由）：
在帧 k 画过、之后不再画 ⇒ 第 k+3 帧的帧首释放。

**宽限两代是承重的**：取 1 的话"每隔一帧画一次"那种用法会**每画一次就销毁又重建**
（= 重传整张图），而画面逐像素相同。

于是**没有 `retain`/`release` 这类要记得调的 API**：忘记 `dispose()` 的后果退化成
"多占两帧"，不是泄漏显存。`dispose()` 只做两件事（删 GPU 副本、丢弃 CPU 像素引用），
**幂等**，且**必须在 GL 线程调**（文档明说；JavaFX 线程上干脆别调，交给自动回收）。

### 2.6 不新增 `Material` 常量

`COLOR` 那一支本来就是 `texture × vColor`，而图片四边形写 `aEdge = (0,0)`
⇒ `fwidth == 0` ⇒ 两个覆盖率都是 1。`Material` 的类注释曾把"图像"列成将来的材质常量，
**那条预期是错的**——在文档里点明，免得下一个人照着它去"补全"。

## 3. 接线（三层，各干一件事）

| 层 | 位置 | 职责 |
|---|---|---|
| GL 上传 | `GLAbstraction.createPremultipliedTexture` + `LwjglGLAbstraction` | 预乘 + 上传；与 `argbToRgba` 共用字节序核心 |
| 缓存/回收 | 新 `renderer/ImageStore`（持有 `GLAbstraction`，照 `PickBuffer`/`GlyphAtlas`） | 查/传/删、代际回收、两个观测计数；由 `RenderBatch` 持有并在 `beginFrame`/`dispose` 挂钩 |
| 发射顶点 | `Gc.emitImageQuad` | `flushIfNeeded` → `syncState(texId, COLOR)` → `writer().quad(...)` |

另加公开句柄 `renderer/ImageHandle`（构造是纯 CPU，不碰 GL）。

## 4. 错误处理

| 情形 | 处置 |
|---|---|
| 像素数与面积不匹配、尺寸非正 | `IllegalArgumentException`。判据在 `ImageStore.requireValidPixels` **一份**，句柄构造器与绘制两条路共用 |
| 同一个数组换尺寸 | `IllegalArgumentException`（否则按错误跨距解释，画出一张扭曲但正常的图） |
| 目标矩形含 NaN/±Infinity | `IllegalArgumentException`（NaN 顶点让整个图元静默消失，与 `dashPhase` 同一条理由） |
| `dw <= 0` 或 `dh <= 0` | 不画，**且不触发上传**（零面积），与 `strokeOutline` 的 `lineWidth <= 0` 同口径 |
| 用已 `dispose()` 的句柄 | `IllegalStateException`（取出像素只有 `requirePixels()` 一个入口，判据不必写两遍） |
| 校验失败时 | 发生在创建任何 GL 对象**之前**（`Disposable` 契约第 3 条） |

## 5. 坐标与方向约定

- `(dx, dy)` 是目标矩形的**左上角**（用户坐标、受变换影响），**不是基线**
  ——与 `drawText` 的 `y` 对照着看。
- **数组第 0 行 = 图像顶行**，`v = 0 ↔ pixels[0..w-1]`，**不需要翻转**。
- 上传的像素契约是 `0xAARRGGBB`，与 `util/Color` 一致，JavaFX 的
  `PixelReader.getPixels(..., getIntArgbInstance(), ...)` 逐位相同。

## 6. 验收

**单元测试（+20，零 GL 上下文，用 `FakeGLAbstraction`）**
- `LwjglGLAbstractionTest` +3：预乘的字节序与三个端点（a=128 / a=255 / a=0）、
  取整方向（两个 floor 与 round 会给出不同答案的点）、尺寸校验与不预乘那份一致。
- `renderer/ImageStoreTest` 10：同数组只传一次、不同数组是两张、同数组换尺寸抛异常、
  像素数不匹配不创建纹理、回收的三个帧边界（第 k+1 / k+2 帧**不**释放、k+3 帧释放）、
  "每隔一帧画"一次都不重传、回收后重建、显式释放只影响自己的键、`dispose` 幂等、
  释放后继续使用抛异常。
- `renderer/ImageHandleTest` 7：尺寸、像素校验、dispose 释放纹理并置位、幂等、
  没画过就 dispose 是空操作、释放后任何使用都抛、底层已释放时句柄 dispose 仍成功。
- `FakeGLAbstractionGuardTest` 的清单随之更新（新方法进 `IMPLEMENTED` 并写明理由）。

**新校验器 `ImageVerifier`（第十个，24 条，不需要字体）**
纹素逐点（3×2 六格整 21 倍放大后按格心回读，逐格精确相等）、方向、alpha、
预乘、`globalAlpha`、裁剪、拾取（含全透明格）、以及两条与像素无关的
（首帧 5 张、第二帧 0 张；隔一帧不重传；隔两帧必须重传）。

**变异验证（每条只让定向的那几条倒）**

| 注入 | 倒几条 | 倒的是哪些 |
|---|---|---|
| UV 写成常量 | 7 | 六格里除 (0,0) 外的五格 + 两条方向（(0,0) 照过——它本来就该采到纹素 0） |
| `v0`/`v1` 对调 | 8 | 六格 + 两条方向 |
| 上传漏掉预乘 | 2 | 预乘探针（读 `FF0000`）+ "全透明但 RGB 非零"那一格（露品红） |
| `GRACE_GENERATIONS` 2 → 1 | 1 | 宽限那条（回收那条照过——两者合起来才钉死 `GRACE == 2`） |
| 完全不回收 | 1 | 回收那条（**唯一**能发现这个泄漏的判据） |
| 顶点色写死成不透明白 | 1 | `globalAlpha` 那条 |
| 零宽矩形的早退写成 `&&` | 2 | 本帧上传数 + 缓存数（退化的那张图真的上传了、并占住缓存） |

**回归**：`PipelineVerifier` 59、`PathVerifier` 74、`ChartVerifier` 168、`TextVerifier` 22
（CJK 字体下）全部 0 失败；全量单测 442 / 0 失败。

## 7. 实现时踩的坑（写给下一个人）

**`VertexWriter.quad` 收的是 NDC，不是用户坐标。** 位置在别处已经烘焙好（`emitTriangles`
逐顶点过 `state.transformX/Y`，文本那条路由 `TextLayout` 带着 state 做）。
把 `dx/dy` 直接传进去，一个画在 (40,40) 的四边形会被当成 NDC 的 (40,40)——
**整个落在裁剪体之外，一个片元都不产生**，而画面上"什么都没画"与"背景色"逐像素相同。

当时的表现是**所有像素断言一起读到 `000000`**，与"回读坏了"完全分不开。
把它们分开的是两样诊断，**刻意留着**（与 `AxisVerifier` 同一条理由）：
一个纯色 `fillRect` 对照块（证回读本身可用）与一行颜色直方图（证画面里到底有什么）。

**探针的放大倍数必须是奇数（取 21）。** 纹素中心落在 `dx + S*(k+0.5)`，设备像素中心
落在"整数 + 0.5"——只有 `S` 为奇数时两者才重合、线性过滤的权重才是 1.0/0.0，
读到的才是纯纹素；取偶数时每个采样点落在两个纹素交界（97.5% / 2.5%），
"颜色对不对"会退化成"两个颜色混得像不像"。

**全透明那一格的 RGB 刻意取非零（`0x00FF00FF`）。** 写 `0x00000000` 的话，
"alpha 没生效"与"alpha 生效了、露出的正好是黑底"**逐位相同**，那条断言是橡皮图章。

**计数器的取走时机是个坑。** `takeUploadedImageCount()` 是"自上次取走以来"的累计量，
不取走会**跨帧攒着**——初版没在首帧取，于是第 1 帧那条"上传 0 张"读到的是
**第 0 帧攒下的 5**，一条本该校验"不重传"的断言变成了"首帧传了 5 张"。
同理，探针里**不能**顺手取一次，那会把帧内的计数提前吃掉，让后面的断言变成恒真。

## 8. 本期不做

源子矩形（九参形式）、染色重载、过滤模式选择与 mipmap（已知降级：一律 `GL_LINEAR`
且不生成 mipmap ⇒ 大幅缩小时有摩尔纹）、从文件/流加载、图集打包、翻转参数
（`gc.scale(-1, 1)` 能做）、图片的解析式抗锯齿（`Gc.antialias` 对它无效，
填充类几何没有中心线；要 AA 请用 `msaa`）。
