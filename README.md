# JFGL - JavaFX OpenGL 2D 绘图框架

一个基于 JavaFX + OpenGL 的 2D 绘图框架。API 手感类似 HTML Canvas（立即模式、像素坐标、
`fillRect` / `strokePath` 这类方法形状），内部走 **GPU 批处理管线**：绘制调用只往顶点缓冲里
追加数据，每帧一次性提交重放。

## 特性

- ⚡ **批处理渲染**：一帧内只做少量 draw call，变换在 CPU 侧烘焙进顶点
- 🎨 **完整几何**：矩形（圆角）、圆、椭圆、线段、多边形、贝塞尔路径，可填充可描边
- ✂️ **状态栈与裁剪**：`save` / `restore` / `translate` / `scale` / `rotate` / `clipRect`
- 🖼️ **嵌入 JavaFX 场景图**：GL 画布就是一个普通 `Node`，与 JavaFX 布局共存
- 📐 **纯计算几何层**：`geom/` 不依赖 GL 上下文，可脱离 OpenGL 单独测试
- 📈 **图表**：折线、散点与**频谱**（GPU 上跑 FFT）走实例化绘制，
  数据常驻显存、每帧只上传新增的点，滚动缩放零重传

## 快速开始

```kotlin
import com.bingbaihanji.jfgl.dsl.jfgl

fun main() {
    jfgl {
        title = "我的应用"
        width = 800.0
        height = 600.0

        onRender {
            // 红色矩形
            fill = 0xFFFF0000.toInt()
            fillRect(50f, 50f, 200f, 120f)

            // 绿色圆（segments 省略时按半径自适应分段）
            fill = 0xFF00FF00.toInt()
            fillCircle(500f, 200f, 80f)

            // 蓝色圆角矩形描边
            stroke = 0xFF0000FF.toInt()
            lineWidth = 4f
            strokeRect(100f, 300f, 250f, 150f, radius = 16f)

            // 品红贝塞尔曲线
            stroke = 0xFFFF00FF.toInt()
            lineWidth = 3f
            beginPath()
            moveTo(50f, 550f)
            bezierCurveTo(200f, 450f, 300f, 650f, 450f, 550f)
            strokePath()
        }
    }
}
```

`onRender` 的接收者是 `Gc`，所以块内可以直接写 `fill = ...`、`fillRect(...)`。

## 运行

```bash
# 编译
mvn compile

# 运行示例窗口
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.MainKt"

# 跑像素级端到端校验（自动关窗，退出码 0=通过 / 1=断言失败）
# 渲染管线
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"

# 拾取
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"

# 文本
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.TextVerifierKt"

# 图表
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.ChartVerifierKt"

# FFT / 频谱（不画任何东西，只测数值）
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.FftVerifierKt"
```

**改哪条路径就跑哪个校验器**：动了顶点/几何/描边跑 `PipelineVerifier`，
动了拾取（`pickId`、ID pass、`PickBuffer`）跑 `PickVerifier`，
动了文本（`text/`、`fontSize`、`drawText`）跑 `TextVerifier`，
动了图表绘制（`chartrender/`、`Gc.charts`、`Gc.flush`）跑 `ChartVerifier`，
动了 FFT 或频谱的数据来源（`gpu/FftKernel`、`FftWindow`、`SpectrumSeriesRenderer`）
跑 `FftVerifier`（数值）**和** `ChartVerifier`（频谱画出来的位置）。
五个都过不代表没漏——它们只证明自己断言过的那些点，见文末「测试」一节。

> **Windows 下要带 `-Dstdout.encoding=UTF-8`**（放在 `-cp` 之前）：JVM 的
> `stdout.encoding` 默认取系统编码（实测本机是 GBK），而 `exec:exec` 不会替你设置它，
> 于是校验器打印的中文断言、尤其是**失败清单**，全是乱码。实测：不加时整份输出不可读，
> 加上之后逐行可读。
>
> **不要用 `mvn exec:java`**：该插件的类加载器会让 openglfx 链接到另一份
> `com.sun.prism.GraphicsPipeline`（静态字段永远为 null），启动即抛
> `UnsupportedOperationException: Could not detect pipeline`。
> 必须用 `exec:exec` fork 出独立 JVM。某些 shell 会吃掉 `-D` 前缀，把每个 `-D...` 加引号可规避。

## API

### 状态

| 成员 | 说明 |
|------|------|
| `fill: Int` | 填充色，ARGB（`0xAARRGGBB`），如 `0xFFFF0000.toInt()` |
| `stroke: Int` | 描边色，ARGB |
| `lineWidth: Float` | 线宽（用户坐标单位），默认 `1f` |
| `globalAlpha: Float` | 全局透明度，`0..1` |
| `width: Int` / `height: Int` | **绘制区设备像素尺寸**（见下方"高 DPI"） |

### 变换与裁剪

```kotlin
save()                       // 压栈当前变换、裁剪与绘制状态
restore()                    // 弹栈
translate(tx: Float, ty: Float)
scale(sx: Float, sy: Float)
rotate(degrees: Float)       // 度；正值在屏幕上是顺时针
clipRect(x: Float, y: Float, w: Float, h: Float)   // 与已有裁剪求交
```

### 形状

```kotlin
fillRect(x, y, w, h, radius = 0f)      // radius > 0 即圆角
strokeRect(x, y, w, h, radius = 0f)

fillCircle(cx, cy, radius, segments = 0)      // segments = 0 表示自适应
strokeCircle(cx, cy, radius, segments = 0)

fillEllipse(cx, cy, rx, ry, segments = 0)
strokeEllipse(cx, cy, rx, ry, segments = 0)

drawLine(x1, y1, x2, y2)

fillPolygon(points: FloatArray)                          // [x1,y1,x2,y2,...]，耳切三角化，凹多边形也正确
strokePolyline(points: FloatArray, closed = false)
```

### 路径

```kotlin
beginPath()
moveTo(x, y)
lineTo(x, y)
quadTo(cx, cy, x, y)                                  // 二次贝塞尔
bezierCurveTo(c1x, c1y, c2x, c2y, x, y)               // 三次贝塞尔
closePath()

fillPath()
strokePath()
```

### 拾取

```kotlin
// 注册：数据变化时做一次，不是每帧
val id = gc.pickRegistry.register(myDataPoint)

// 打标：状态字段（进 save/restore 栈）
gc.pickId = id
gc.fillCircle(x, y, 4f)

// 或作用域块，块结束自动复原
gc.pickable(id) {
    gc.fillCircle(x, y, 4f)
}

// 查询（GL 线程）
val hit = gc.pick(mouseX, mouseY)          // 最上层命中，PickHit?
val hits = gc.pickRect(x, y, w, h)         // 区域内的全部命中

// JavaFX 线程（鼠标事件里）用异步版本
bridge.pickAsync(mouseX, mouseY) { hit ->
    // 回调在 JavaFX 线程上执行
    label.text = hit?.payload?.toString() ?: "无"
}
```

拾取是**像素级**的（判定用 GPU 实际光栅化的结果，与所见一致），且**只看几何**——
全透明的图元照样能命中，图表的隐形热区正是靠这个行为。

### 文本

```kotlin
gc.fontSize = 32f                    // 状态字段，进 save/restore 栈
gc.fill = 0xFFFFFFFF.toInt()

// (x, y) 是**基线**的起点，不是文本框左上角
val advance = gc.drawText("中文 Hello", 40f, 100f)

// 量宽度：不绘制、也不生成字形，可以放心用于布局
val width = gc.measureText("中文 Hello")

// 文本天然可拾取
gc.pickId = id
gc.drawText("可点的标签", 40f, 200f)
```

文本走的是**现有**批处理管线，所以裁剪、z 序、合批、GPU 拾取全部自动成立；
连续的一段文本通常合并成一条 draw call。

- **基线对齐**：`y` 是基线所在的像素行。刻度文字沿轴线对齐时，基线对齐才是
  想要的；"左上角对齐"会让不同高度的字符视觉上跳来跳去。
- **任意缩放清晰**：字形只在 48px 的 em 尺寸下光栅化一次，之后由距离场在屏幕空间
  重算边缘——同一个字在 10px 和 200px 下都不会是拉伸的糊图。
  改 `fontSize` 不触发任何重新光栅化，字形按字形索引缓存。
- **缺字不跳过**：字体里没有的码点画成 `.notdef`（豆腐块），不静默省略。
- **可拾取范围比墨迹大一圈**：文本的四边形覆盖整个 SDF 位图矩形（含四周各
  `SPREAD` = 8 像素的外扩）。与"全透明图元仍可拾取"同类，是**刻意**行为。
- **字体**：默认用 `/fonts/simhei.ttf`（黑体）。换字体见
  `src/main/resources/fonts/README.md`——**公开分发前必须换掉它**，
  黑体是微软/中易的专有字体。

### 图表

图表分两层：**`chart/` 是纯计算**（数据容器、轴与刻度、配色 LUT、图表装配，
零 GL 依赖，可脱离 OpenGL 单测），**`chartrender/` 是 GPU 绘制后端**（把装配结果画成
实例化 draw call）。两者是**兄弟包**，`chart/` 里一条 GL 依赖都没有，由
`ChartPackageIsolationTest` 递归守卫。

`chart/` 能算不能画，`chartrender/` 只管画——数据系列是**当场就画**的
（不在 `Gc` 的批处理里），所以夹在"网格"与"标注"之间的 z 序要靠 `gc.flush()`：

```java
// ── ① 装配（纯计算，任意线程；也可以只算不画）──
ArrayChartData data = new ArrayChartData(
        new AxisRange[]{new AxisRange(0, 10, "时间", "s"), new AxisRange(-1, 1, "电压", "V")},
        new double[][]{{0, 1, 2, 3}, {0.1, -0.2, 0.3, 0.0}});

Axis x = new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(600);  // = 绘图区宽
Axis y = new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(400);  // = 绘图区高

Chart chart = new Chart(x, y);
chart.addLayer("主").add(new Series("电压", data, ChartType.LINE).color(0xFF00FF00));

Tick[] ticks = x.ticks();               // 主/中/次三级刻度，position 已按本轴换算好
```

```kotlin
// ── ② 画出来（每帧、GL 线程，即 onRender 的 gc 回调里）──
val plot = Rect(100f, 100f, 600f, 400f)

gc.fill = 0xFF101020.toInt()
gc.fillRect(plot.x, plot.y, plot.width, plot.height)   // 绘图区底色
gc.stroke = 0xFF404040.toInt()
gc.lineWidth = 1f
for (t in y.ticks()) {                                  // 横网格线（值越大越靠上）
    val sy = plot.y + plot.height - t.position().toFloat()
    gc.drawLine(plot.x, sy, plot.x + plot.width, sy)
}
for (t in x.ticks()) {                                  // 竖网格线
    val sx = plot.x + t.position().toFloat()
    gc.drawLine(sx, plot.y, sx, plot.y + plot.height)
}

gc.flush()                                              // ★ 网格先落定
gc.charts.draw(chart, plot, gc.width, gc.height)         // ★ 数据系列

gc.fill = 0xFFC0C0C0.toInt()                             // 标注画在数据之上（顺序即 z 序）
gc.fontSize = 12f
for (t in x.ticks()) {
    gc.drawText(t.label(), plot.x + t.position().toFloat(), plot.y + plot.height + 16f)
}
```

- **不支持的图型会明确抛异常**，不会静默不画：本期只有 `LINE`、`LINE_AND_MARKERS`
  （只画折线那半）与 `SCATTER` 三种；`STEP` / `AREA` / `BAR` / `HEATMAP` / `WATERFALL`
  还没有渲染器。`LOGARITHMIC` / `TEXT` 轴同样抛异常（GPU 路径只支持线性换算）。
- **脏区间**：`dirtyRange(sinceRevision)` 让渲染器只上传新增的那一段；
  `revision` 不变时报空，静态数据一次上传后永不重传。
  数据在 GPU 里存的是**数值不是屏幕坐标**，所以滚动/缩放/改窗口尺寸只是改 uniform、
  **零重传**；每点 4 字节。
- **缺口是 NaN**：丢包与传感器故障是同一种表示，渲染器的规则只有一条——遇到 NaN 就断开折线。
  **不能连过去**：连过去的那条直线显示的信号并不存在，比不显示更糟。
- **流式数据只有一个写者**：`RingChartData` 是 SPSC 无锁环形缓冲。
  数据源若变成网络/串口回调（回调线程不固定），这个前提就不成立，必须换设计。
- **轴不持有数据**：范围由数据自己声明，轴只是显示窗口，多 Y 轴因此是自然结果。
- **时间轴按 UTC 格式化**；配色是 1×256 的 LUT（换配色 = 换一张纹理）。

流式数据的写法（采集线程写、GL 线程读）：

```java
RingChartData stream = new RingChartData(new AxisRange[]{AxisRange.of(0, 1)}, 1 << 16);
stream.append(0.5);                     // 采集线程
double v = stream.value(0, 0);          // GL 线程；窗口之外返回 NaN（缺口）
```

#### 频谱（GPU 上的 FFT）

`ChartType.SPECTRUM` 的横轴不是时间，是 **bin 下标**（`x = bin 索引`，
`Δf = fs / N`）——**要显示 Hz 由应用自己换算轴标签**，渲染层不知道采样率，也不该猜。
数据照样是 `RingChartData`（采集线程写、GL 线程读）；渲染时每帧对环里**最近 N 个样本**
跑一次 GPU 上的 FFT（`N = min(2048, 环容量)`，环容量必须是 2 的幂），
再把得到的 `N/2+1` 个幅度画成一条折线：

```kotlin
val spec = RingChartData(
    arrayOf(AxisRange(0.0, 512.0, "样本", ""), AxisRange(0.0, 1.2, "幅度", "")),
    512                                   // 环容量（2 的幂）= FFT 的变换长度
)

// 采集线程：写进环里（这里是一个 bin 52 上的纯正弦）
for (i in 0 until 512) {
    spec.append(i.toDouble(), cos(2.0 * PI * 52 * i / 512).toFloat().toDouble())
}

// GL 线程（onRender 里）：x 轴的单位是 bin，窗口取 bin 44..68
val x = Axis(AxisType.LINEAR, spec.axisRange(0)).setDisplayLength(600.0).setWindow(44.0, 68.0)
val y = Axis(AxisType.LINEAR, spec.axisRange(1)).setDisplayLength(400.0)   // 窗口 [0, 1.2]
val spectrum = Chart(x, y)
spectrum.addLayer("频谱").add(
    Series("频谱", spec, ChartType.SPECTRUM).color(0xFF9F00FF.toInt())
)

gc.flush()                                                       // 网格先落定
gc.charts.draw(spectrum, Rect(100f, 100f, 600f, 400f), gc.width, gc.height)
```

- 窗函数固定为 Blackman-Harris（旁瓣 −92 dB），幅度**已做窗的相干增益补偿**：
  单位幅度正弦的谱峰读回 `1.0`，换窗不改变读数。
- **环里还没攒够一个完整的窗时不画**（也**不补零**——补零会给出一个看起来正常、
  峰位与旁瓣却全是假的谱）。
- **NaN 输入**（丢包、传感器故障）什么都不画：着色器把整段退化到裁剪空间之外。
- 频谱**复用折线的整条绘制路径**（同一套顶点着色器与实例化机制），
  所以拾取、裁剪、`baseInstance` 全都自动成立。

### 坐标系与颜色

- 坐标单位是**像素**，原点在**左上角**，**y 轴向下**。
- 旋转单位是**度**，正值顺时针（因为 y 向下，与 HTML Canvas 一致）。
- 颜色是 `Int` 的 ARGB，与 Java 的 `Color.getRGB()`、`0xAARRGGBB` 字面量一致。
- Alpha 走**预乘混合**（`GL_ONE` / `GL_ONE_MINUS_SRC_ALPHA`），半透明叠加结果与 CSS 一致。

### ⚠️ 高 DPI

用户坐标 **1:1** 映射到**设备像素**，而设备像素尺寸受系统缩放影响，**不等于**创建窗口时
声明的逻辑尺寸。125% 缩放下，`width = 800` 的窗口实际帧缓冲是 **988×738** 设备像素，
画到 `x = 800` 只覆盖约 81% 宽度。

需要铺满整屏时请用 `Gc.width` / `Gc.height`，不要用 `jfgl { width = ... }` 里配的逻辑尺寸。

## 项目结构

```
src/main/java/com/bingbaihanji/jfgl/
├── geom/        # 纯几何：Path、Flattener（曲线细分）、Tessellator（三角化）、StrokeGenerator
├── gl/          # OpenGL 抽象：ShaderProgram、Texture、VertexBuffer
├── gpu/         # GPU 计算：ComputeShader、FftKernel（FFT）、FftWindow（窗与增益补偿）
├── math/        # Vec2、Mat3、Transform
├── renderer/    # 顶点侧热路径：VertexFormat、VertexWriter、DrawCommand、RenderBatch
├── chart/       # 图表框架（纯计算）：ChartData、Axis、TickGenerator、ColorMapping、Chart
├── chartrender/ # 图表 GPU 绘制后端：ChartRenderer、Line/Scatter/SpectrumSeriesRenderer、SeriesBuffer
├── text/        # SDF 文本：FontFile(stb)、GlyphRasterizer、SdfGenerator、GlyphAtlas、TextLayout
└── util/        # Color、Rect、Disposable

src/main/kotlin/com/bingbaihanji/jfgl/
├── dsl/         # jfgl { } 应用入口
├── example/     # 示例与像素校验器
├── glview/      # FXGLTransfer：JavaFX 与 OpenGL 的桥接
├── renderer/    # Gc 门面、ViewTransform
└── view/        # JavaFX 视图组件
```

`geom/` 对 `gl/` **零依赖**（由 `GeomPackageIsolationTest` 强制），因为它是纯计算，
可以脱离 GL 上下文单独测试。

`chartrender/` 与 `chart/` 是**兄弟包**——`chart/` 只放能单测的纯计算，
绘制后端单独一个包，也是同样的理由（`ChartPackageIsolationTest` 递归遍历 `chart/`
整棵子树，按包名白名单守卫）。

`src/main/resources/fonts/` 放着字体文件与它的授权/换字体说明（见该目录的 README）。

## 测试

```bash
mvn test                     # 全部测试
mvn test -Dtest=PathTest     # 单个测试类
```

当前 **323 个测试，0 失败，2 跳过**（2 个跳过是 `TessellatorRegressionTest` 里两条
`@Disabled` 的已知缺陷）。分布：`geom/` 69、`renderer/` 97、`gl/` 10、`text/` 32、
`chart/` 57、`chartrender/` 44、`gpu/` 14。

`geom/`、`renderer/` 的顶点侧、`math/`、`util/`、`chart/` 都是纯计算，不依赖 GL 上下文；
`chartrender/` 里 `WindowRange`、`SeriesUploadPlan`、`ChartRenderLayout` 是纯算术，
`SeriesBuffer` 只依赖 `GLAbstraction` 接口（用 `FakeGLAbstraction` 就能测）；
`gpu/FftWindow` 是纯算术，`gpu/FftKernel` 的**着色器源码字符串**也能脱离 GL 单测
（共享内存长度有没有与 `MAX_N` 各写一份、循环步长是不是又写回了字面量，都属于
"不看字符串就发现不了"的那类）。

**改渲染路径跑 `PipelineVerifier`，改拾取跑 `PickVerifier`，改文本跑 `TextVerifier`，
改图表绘制跑 `ChartVerifier`，改 FFT 跑 `FftVerifier`**（见上面「运行」一节）。
本管线的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
校验器把**最终像素**作为口径，回读帧缓冲后断言，这是唯一拦得住这类缺陷的办法——
它曾据此发现描边丢失整条闭合边的 bug，而当时的单元测试没有一个发现。

`ChartVerifier` 除此之外还断言了一件**像素拦不住**的事：整个图表后端的主张是
「每帧只上传新增的点」，而增量上传与每帧全量重传**画出来的图逐像素相同**——
所以那条断言观测的是**上传字节数**（`ChartRenderer.takeUploadedBytes`），
判据是"每一帧恰好 K×4 字节"。改图表绘制路径时它也必须过。

频谱那一组则是"判别式必须选对量"的又一个例子：**"峰值在正确的 bin、高度也对"
抓不住"第二个实例属性指向同一个 bin"**——那样每一段退化成**水平小横线**，
而**峰那一列的最高有色行仍然在顶边**。唯一能把两种实现分开的量是
「**相邻两个 bin 之间那一列上的墨迹落在哪一行**」（两个 bin 幅值的中点，还是只落在
左边那个的高度上）。实测把它反向注入（`SpectrumSeriesRenderer` 里那个偏移 4 → 0）时，
**只有那两条断言失败**，其余 63 条照常通过。
FFT 本身的**数值**（峰值落在正确的 bin、与朴素 O(N²) DFT 逐 bin 比对、换窗不改变幅度读数、
跨环绕取样本、两个端点长度）由 `FftVerifier` 守着——它**不画任何东西**。

**但校验器也有盲区**：它只证明自己断言过的那些点。开发拾取校验器时就撞上一次——
当时的 24 条断言全绿，却漏掉了一个真缺陷（`PickBuffer.clear()` 受 `GL_SCISSOR_TEST` 影响，
只清掉了裁剪盒内那部分，盒外保留上一帧的 ID → 拾取到已消失的对象）。
原因是**它的场景每帧完全相同**，陈旧 ID 与新鲜 ID 恰好一致。
所以校验器的场景要会变：至少包含「某个图元在后续帧消失／移动」，
并专门断言「不该有东西的地方是干净的」，而不只是「该有东西的地方是对的」。
`ChartVerifier` 就是这么写的（有几张实验图只在观察期画、有一条系列中途整条消失）。

## 依赖

- JDK 21+（编译目标 21；本机实测 JDK 25）
- JavaFX 17.0.6（pom 声明；若 JDK 自带 JavaFX 25，运行时会遮蔽 pom 里的版本）
- LWJGL 3.3.6 + openglfx-lwjgl（注意：`3.3.6` 是 **LWJGL 库**的版本，
  实际拿到的 GL 上下文是 **4.6**（compatibility profile）——实测
  `GL_VERSION = 4.6.0 NVIDIA 581.29`，compute shader 端到端可用。
  着色器里写着 `#version 330 core` 只是向后兼容，不代表上下文是 3.3）
- lwjgl-stb 3.3.6（字形光栅化）
- Kotlin 2.3.0

当前仅支持 **Windows**（JavaFX 原生库以 `<classifier>win</classifier>` 声明）。

## 许可证

MIT License
