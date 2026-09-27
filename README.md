# JFGL - JavaFX OpenGL 2D 绘图框架

开发者设计、模块边界和扩展说明见 [JFGL 开发者手册](docs/JFGL-DEVELOPER-GUIDE.md)。

一个基于 JavaFX + OpenGL 的 2D 绘图框架。API 手感类似 HTML Canvas（立即模式、像素坐标、
`fillRect` / `strokePath` 这类方法形状），内部走 **GPU 批处理管线**：绘制调用只往顶点缓冲里
追加数据，每帧一次性提交重放。

## ⚠️ 公开分发前必须先做这一件事：换掉自带字体

本仓库自带 `jfgl-render-gl/src/main/resources/fonts/simhei.ttf`，它是
**微软 / 中易的专有字体**（Windows 系统自带的「黑体」SimHei）——**不是自由字体，
不在本项目的 MIT 授权范围内**（见 [LICENSE](LICENSE) 末尾的例外条款）。

- **本机自用 / 内部开发 / 跑测试**：没有问题，这份字体就是为此放进来的。
- **要公开分发（开源、发 jar、镜像到别的平台）**：**必须先换掉它**，
  否则是在按 MIT 分发一份专有字体。

替代品要用 **OFL 授权的静态（非可变）** 中文字体，如 **Noto Sans SC / 思源黑体**。
换完**必须重跑 `TextVerifier`**：不同字体的度量不同，那里与字体相关的期望值要重新核对。
完整约束（为什么不能用 OTF/CFF、为什么不能用可变字体、怎么核对）见
[`jfgl-render-gl/src/main/resources/fonts/README.md`](jfgl-render-gl/src/main/resources/fonts/README.md)。

## 特性

- ⚡ **批处理渲染**：一帧内只做少量 draw call，变换在 CPU 侧烘焙进顶点
- 🎨 **完整几何**：矩形（圆角）、圆、椭圆、线段、多边形、贝塞尔路径，可填充可描边
- ✂️ **状态栈与裁剪**：`save` / `restore` / `translate` / `scale` / `rotate` / `clipRect`
- 🖼️ **嵌入 JavaFX 场景图**：GL 画布就是一个普通 `Node`，与 JavaFX 布局共存
- 📐 **纯计算几何层**：`geom/` 不依赖 GL 上下文，可脱离 OpenGL 单独测试
- 📈 **图表**：折线、散点、阶梯、面积、柱状与**频谱**（GPU 上跑 FFT）走实例化绘制，
  数据常驻显存、每帧只上传新增的点，滚动缩放零重传；
  轴、标题、图例、轴标题、间距都可配置（`ChartLayout` / `Chart.title` / `Chart.legendVisible` /
  `Chart.axisTitlesVisible` / `Chart.axisStyle`（网格 / 轴线 / 箭头 / 刻度，一台配齐）/
  `Chart.tickLabelReserve` / `Chart.padding`）

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

要放 `Label` / `Button` 这类 JavaFX 控件，用 `onScene` + `overlay`（一个叠在画布**之上**的透明容器）：

```kotlin
jfgl {
    onScene {                                  // JavaFX 线程，窗口显示之前调一次
        val label = Label("在图形上点一下")
        overlay.children.add(label)            // 控件浮在画面上
    }
    onClick { hit -> /* 这里也在 JavaFX 线程上，可以直接改控件 */ }
}
```

- **别在配置块里 new 控件**：那个块在 `Application.launch` 之前跑，JavaFX 工具包还没起来。
  能安全碰场景图的两个时机是 `onScene`（JavaFX 线程、一次）与 `onClick`（JavaFX 线程）；
  `onInit` / `onRender` 都在 **GL 线程**上，在那里加控件是跨线程操作场景图。
- `overlay` 自己**不吃鼠标事件**，所以画布上的点击照旧落到画布上；但控件占的那块地方
  会吃掉点击（点在按钮上不该同时命中画布）。
- 控件用的是 JavaFX 坐标系（逻辑像素），`Gc` 用的是设备像素，两者在高 DPI 下差一个缩放系数。

## 点击事件

给图形接点击只要加两处：`onRender` 里用 `pickable(id) { }` 打标，`onClick` 里拿回对象。

```kotlin
jfgl {
    title = "点一下"
    width = 800.0
    height = 600.0

    var rectId = 0

    onInit { gc ->
        // 注册载荷：命中时回调里拿到的就是这个对象本身（注册发生在数据变化时，不是每帧）
        rectId = gc.pickRegistry.register("红色矩形")
    }
    onRender {
        fill = 0xFFFF0000.toInt()
        pickable(rectId) { fillRect(50f, 50f, 200f, 120f) }
    }
    onClick { hit ->
        // 在 JavaFX 应用线程上回调；未命中时 hit 为 null
        println(if (hit == null) "点空了" else "点中了 ${hit.payload()}")
    }
}
```

- **`(x, y)` 的坐标系由库负责换算**：鼠标事件给的是节点的逻辑坐标，而拾取要的是设备像素，
  两者差一个窗口缩放系数。`onClick` / `clickAsyncAtNode` 内部替你乘好了，**不要自己调
  `FXGLTransfer.pickAsync` 再手工换算**——漏乘的表现是"点 A 命中 B"，而画面完全正常
  （在 100% 缩放的机器上还一切正常）。
- **点击走的是有界队列**（按序交付，队列满时丢最旧并计入 `droppedClicks()`）。
  hover / 拖拽那类连续量请用 `FXGLTransfer.pickAsync`——它是"最新覆盖旧的"，
  把点击接在它上面会**静默丢点击**。
- 回调里不要碰 `Gc`（它只能在 GL 线程用）。要"点中之后改画面"，把结果存进字段，
  在 `onRender` 里读。
- 完整可跑的例子见 `example/ClickDslExample.kt`（DSL 版）与 `example/ClickExample.kt`
  （`FXGLTransfer` 版，带 JavaFX 控件反馈）。

## 运行

```bash
# 编译全部模块
mvn compile

# 只编译 JavaFX 集成层及其依赖
mvn -pl jfgl-javafx -am compile

# 以下运行命令在 jfgl-javafx 模块目录执行；先从根目录构建并安装一次三个模块
mvn -o install -DskipTests
cd jfgl-javafx

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

# 点击闭环（合成鼠标事件走真实事件路径 + 一次 Robot 真实点击）
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.ClickVerifierKt"
```

**改哪条路径就跑哪个校验器**：动了顶点/几何/描边跑 `PipelineVerifier`，
动了拾取（`pickId`、ID pass、`PickBuffer`）、**尤其是动了 `pickAsync` / PBO 那条异步路径**
（`FXGLTransfer.resolvePendingPick`、`LwjglGLAbstraction` 的 PBO 与 fence 方法）就**必须**跑
`PickVerifier`——它那一节是唯一在真 GL 上验证"fence 真的 signal、PBO 里读回的是那个 ID、
回调落在 JavaFX 线程"的地方，`PickBufferTest` 用的是假 GL、碰不到这三件事，
动了文本（`text/`、`fontSize`、`drawText`）跑 `TextVerifier`，
动了图表绘制（`chartrender/`、`Gc.charts`、`Gc.flush`）跑 `ChartVerifier`，
动了 FFT 或频谱的数据来源（`gpu/FftKernel`、`FftWindow`、`SpectrumSeriesRenderer`）
跑 `FftVerifier`（数值）**和** `ChartVerifier`（频谱画出来的位置），
动了鼠标点击那条闭环（`FXGLTransfer` 的 `pickAsync` / `clickAsync` / `onClick`、
`resolvePendingPick` 的点击队列、坐标换算）跑 `ClickVerifier`。
七个都过不代表没漏——它们只证明自己断言过的那些点，见文末「测试」一节。

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

> `clipRect` 底层是 `glScissor`（只能是轴对齐矩形），因此**变换含旋转时按旋转后矩形的
> 轴对齐包围盒裁剪**：旋转矩形之外、包围盒之内的内容仍会显示、仍可拾取。这个降级是
> **单向**的——只会裁少，不会吞掉本该显示的内容。要精确裁剪旋转区域，请自己算出想要的
> 轴对齐范围，或在旋转之前设好裁剪。

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

### 虚线

虚线是**状态字段**，不是每个形状各一个方法——与 `lineWidth` 并列，进 `save`/`restore` 栈：

```kotlin
gc.dashPattern = floatArrayOf(6f, 4f)          // 6 实 4 空
gc.strokeCircle(400f, 300f, 120f)              // 所有描边方法都吃它
gc.dashPattern = floatArrayOf(8f, 3f, 2f, 3f)  // 长划-点 也支持（任意长度）
gc.dashPhase = 10f                             // 起始相位：逐帧加一点就是"流动的虚线"
gc.dashPattern = null                          // 回到实线
```

- **一个字段管全部**：`strokePath` / `strokePolyline` / `strokeRect` / `strokeCircle` /
  `strokeEllipse` / `drawLine` 都汇进同一条描边路径，所以没有"某个形状不支持虚线"这回事。
- 偶数下标是实线、奇数下标是空白，单位与 `lineWidth` 一样是用户坐标。
- **setter 会拷贝数组**：不拷的话你改自己那个数组会静默改掉已压进状态栈的历史值。
- 空数组、含 `NaN`/`Infinity`/负数的项、总和为 0 的模式都会被拒绝——
  全零模式在渲染侧是"什么都不画"，而那与"这条线不存在"逐像素相同。

### 路径

```kotlin
beginPath()
moveTo(x, y)
lineTo(x, y)
quadTo(cx, cy, x, y)                                  // 二次贝塞尔
bezierCurveTo(c1x, c1y, c2x, c2y, x, y)               // 三次贝塞尔
closePath()

fillPath()      // 每个子路径是一条轮廓：按**包含关系**判定外轮廓与洞（环图、空心饼图、多块图形）
strokePath()    // 每个子路径**独立**描边；末条命令是 CLOSE 的子路径补首尾接头
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

`Gc.pick()` / `pickRect()` 保留同步语义，适合 GL 线程内确实需要当前帧结果的少数场景。
`FXGLTransfer.pickAsync()` 使用双 PBO 和 GPU fence：回调通常在下一帧到达，GPU 未完成时不阻塞
渲染线程；连续 hover 请求仍只保留最新位置。

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

#### Hover 十字线与数据提示框

图表交互状态属于 `Chart`，不依赖 JavaFX。应用只需在 JavaFX 节点上绑定 hover，
渲染帧中调用 `drawChart` 时会自动画十字虚线、命中点和提示框：

```kotlin
val chart = buildChart()
chart.interaction().setConfig(
    ChartInteractionConfig.defaults()
        .crosshairVisible(true)
        .tooltipVisible(true)
        .formatter { value, _ -> "%.2f".format(value) }
)
bridge.trackChartHover(canvasNode, chart)

// onFrame 中：
gc.charts.drawChart(chart, frame, gc.width, gc.height)
```

提示框默认显示 `x 轴名称 (单位)`、`系列名称 / y 轴名称 (单位)` 和对应值。
可以通过 `enabled`、`crosshairVisible`、`tooltipVisible`、吸附半径、颜色、线宽、虚线长度、
字体大小、内边距、偏移量和 `formatter` 完整配置。鼠标坐标会自动按 JavaFX 窗口缩放转换为设备像素。

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

- **不支持的图型会明确抛异常**，不会静默不画：本期有 `LINE`、`LINE_AND_MARKERS`
  （折线 + 标记点，标记点那半复用散点渲染器的几何）、`SCATTER`、`STEP`、`AREA`、`BAR`
  与 `SPECTRUM`；
  `HEATMAP` / `WATERFALL` 还没有渲染器。`LOGARITHMIC` / `TEXT` 轴同样抛异常
  （GPU 路径只支持线性换算）。
- **样式值里的 NaN / Infinity 会抛异常**（`Series.fillAlpha` / `lineWidth` / `markerSize` /
  `baseline` / `categoryGap` / `barGap`）：NaN 没有"明确的处置"——`fillAlpha(NaN)` 会
  `Math.round(NaN) = 0`，填充**全透明**，与"用户把不透明度设成 0"逐像素相同。
  越界但**有限**的量照旧收下（`fillAlpha(2)` 钳成实心、负线宽不画线）。
- **柱状图的柱宽与位置**：`Series.categoryGap` / `Series.barGap` 都是**比例**而不是像素
  （横轴可缩放，像素间距在缩小时会把柱子挤成零宽，而"整张图没了"与"数据没来"分不开）；
  同一层里的多个柱状系列**并排**，间距不一致时 `ChartRenderer` 抛异常。
  柱心落在样本的屏幕 x 上，所以窗口取 `[0, N-1]` 时首尾两根各有一半在绘图区外——
  要每根都完整，把 x 轴窗口左右各放半格（`setWindow(-0.5, N-0.5)`）。
- **面积图的下沿是 `Series.baseline()` 那个数值**（默认 0，对应 JavaFX 的
  `forceZeroInRange`），不是"绘图区下边缘"——写成下边缘的话，y 轴一放大柱/填充的
  高度就跟着轴走，而画面完全正常。填充默认半透明（`Series.fillAlpha` = 0.5），
  轮廓线由折线路径画。

### 标题 / 图例 / 轴标题 / 外边距

`Chart` 上有一组装配配置（`title` / `titleSide` / `legendVisible` / `legendSide` /
`axisTitlesVisible` / `tickLabelReserve` / `padding` 等），`ChartLayout` 负责把
**一整块外框**切成标题带、图例带、轴标题带、刻度预留带与绘图区，
`Gc.charts.drawChart(chart, frame, gc.width, gc.height)` 一步画完：

```kotlin
val frame = Rect(20f, 20f, 760f, 560f)
chart.title("电压监测").titleFontSize(16f)
chart.legendSide(ChartSide.BOTTOM)
gc.charts.drawChart(chart, frame, gc.width, gc.height)   // 装饰 + 数据系列
```

- **不设标题、不设图例、外边距为 0 时，绘图区与外框逐字段相等**，于是画面与
  "自己算好绘图区再调 `draw`"**逐像素相同**（这条由 `ChartVerifier` 按像素钉着：
  同一张图两条路径各画一帧再比对）。
- 两个入口的分工：`draw(chart, plotRect, w, h)` 要**已经算好的绘图区**
  （网格、刻度、坐标轴全由调用方安排，见上面的例子）；`drawChart` 要**整块外框**，
  装饰的排布交给 `ChartLayout`。装饰件占的是绘图区**之外**的带子，所以两条路径
  画数据系列的代码是同一段。
- **标题只支持上下**（左右放的标题要转 90°，绘制入口只有横排文字）——传 LEFT/RIGHT
  会明确抛异常，而不是把标题画成横的。图例四个方向都支持。
- 绘制入口是 `ChartPainter`（`Gc` 里有一个转发实现）：量文字、画文字、填色块。
  它同时是 `ChartTextMetrics`，所以布局能脱离 GL 单测。

#### 坐标系（网格 / 轴线 / 箭头 / 刻度）

这一套不用自己画了，配一次即可：

```kotlin
chart.axisStyle(AxisStyle.defaults()
        .visible(true)                     // ★ 必须显式打开：默认是关的
        .gridVisible(true).gridColor(0xFF394452).gridWidth(1f)
        .axisColor(0xFFE3E8EF).axisWidth(1.5f)
        .arrowsVisible(true).arrowSize(8f)
        .tickMarksVisible(true).tickLength(5f)
        .tickLabelsVisible(true).tickLabelFontSize(12f).tickLabelColor(0xFFC7D0DB))

gc.charts.drawChart(chart, frame, gc.width, gc.height)   // 一步画完：装饰 + 坐标系 + 数据
```

- **默认关**（`visible = false`）：开了轴绘图区就要让出带子，而"绘图区变了就是画面变了"
  ——既有图不该被一个新开关悄悄挪几像素。所以这个特性是**纯增量**的。
- **打开之后刻度预留由库自己算**，`chart.tickLabelReserve(...)` 会被**覆盖**
  （不是相加）。要更多空间请给整张图加 `padding`。
- **坐标系画在数据系列之前**：网格在数据之下；刻度文字虽在绘图区外，
  但数据被裁在绘图区之内、**盖不到它**，所以不需要"网格 → flush → 数据 → 刻度"那种两趟写法。
- 网格线只画**严格在绘图区内部**的主刻度（两端点上的与坐标轴重合）。
- **轴标题**：`chart.axisTitlesVisible(true)` 打开后，`AxisRange` 的 name/unit 会被画出来
  （`轴 0` 的名字/单位 → x 轴标题、`轴 1` → y 轴标题，文字形如 `电压 (V)`；
  单位为空时只画名字）。它默认**关着**——打开会让绘图区让出两条带子，而"绘图区变了
  就是画面变了"，既有图不该被一个新开关悄悄挪几像素。
- **刻度文字**：现在可以交给库画（见下一条「坐标系」）。仍然自己画时，
  图表层不知道它占多高，所以由调用方声明：`chart.tickLabelReserve(ChartSide.BOTTOM, 18f)`，
  图表层负责把它从绘图区里扣掉。预留带紧贴绘图区，轴标题带在它**外面**。
- **装饰被裁到各自的带子里**：一项文字比带子宽时（系列名很长、外框很窄），
  后面的部分在带子边缘被切断，而不是越过边界画到别处。折行与省略号都不做
  （那要在"哪里断"上做决定，是排版决策，不该由布局替调用方做）。
- **`drawChart` 不能带着变换调用**（`translate` / `scale` / `rotate`）：布局算出来的矩形是
  设备像素，带着变换会让装饰落到布局没算过的位置上。带着变换调用会抛
  `IllegalStateException`，消息里说清了原因。
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
jfgl-core/                 # 零 GL / JavaFX 依赖的计算层
├── chart/                 # ChartData、Axis、Tick、ColorMapping
├── geom/                  # Path、Flattener、Tessellator、StrokeGenerator
├── math/                  # Vec2、Mat3、Transform
└── util/                  # Color、Rect、Disposable

jfgl-render-gl/            # OpenGL 资源、渲染管线与 GPU 后端
├── gl/ renderer/ text/    # GL 抽象、批处理、GPU 拾取、SDF 文本
├── chartrender/ gpu/      # 图表绘制后端、FFT
└── renderer/              # Kotlin Gc、ViewTransform

jfgl-javafx/               # JavaFX 场景图集成与应用层
├── glview/ dsl/ view/     # FXGLTransfer、DSL、布局
└── example/                # 示例和端到端像素校验器
```

`geom/` 对 `gl/` **零依赖**（由 `GeomPackageIsolationTest` 强制），因为它是纯计算，
可以脱离 GL 上下文单独测试。

`chartrender/` 与 `chart/` 是**兄弟包**——`chart/` 只放能单测的纯计算，
绘制后端单独一个包，也是同样的理由（`ChartPackageIsolationTest` 递归遍历 `chart/`
整棵子树，按包名白名单守卫）。

`jfgl-render-gl/src/main/resources/fonts/` 放着字体文件与它的授权/换字体说明（见该目录的 README）。

## 测试

```bash
mvn test                                # 全部模块测试
mvn -pl jfgl-core -Dtest=PathTest test  # 单个 core 测试类
mvn -pl jfgl-render-gl -Dtest=PickBufferTest test
```

当前 **412 个测试，0 失败，2 跳过**（2 个跳过是 `TessellatorRegressionTest` 里两条
`@Disabled` 的已知缺陷）。分布：`geom/` 92、`renderer/` 107、`gl/` 13、`text/` 32、
`chart/` 85、`chartrender/` 69、`gpu/` 14。

> 这几个数请从 surefire 报告里数（`*/target/surefire-reports/TEST-*.xml` 的 `tests=` 求和），
> 不要凭印象写：`mvn -q test` 会把汇总行吃掉，而这里的数字以前就因为这样落后过两轮
> （写过 357，当时实际已经是 405）。

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

**MIT License**，全文见 [LICENSE](LICENSE)。

⚠️ **有一处例外**：仓库自带的 `fonts/simhei.ttf` 是第三方专有字体，
**不在 MIT 授权范围内**，公开分发前必须换掉——详见上面「公开分发前必须先做这一件事」
与 [LICENSE](LICENSE) 末尾的例外条款。
