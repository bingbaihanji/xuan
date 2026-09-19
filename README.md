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
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"

# 拾取
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
```

**改哪条路径就跑哪个校验器**：动了顶点/几何/描边跑 `PipelineVerifier`，
动了拾取（`pickId`、ID pass、`PickBuffer`）跑 `PickVerifier`。两个都过不代表没漏——
它们只证明自己断言过的那些点，见文末「验证」一节。

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
├── gpu/         # 计算着色器
├── math/        # Vec2、Mat3、Transform
├── renderer/    # 顶点侧热路径：VertexFormat、VertexWriter、DrawCommand、RenderBatch
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

## 测试

```bash
mvn test                     # 全部测试
mvn test -Dtest=PathTest     # 单个测试类
```

`geom/`、`renderer/` 的顶点侧、`math/`、`util/` 都是纯计算，不依赖 GL 上下文。

**改渲染路径后跑 `PipelineVerifier`，改拾取路径后跑 `PickVerifier`**（见上面「运行」一节）。
本管线的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
校验器把**最终像素**作为口径，回读帧缓冲后断言，这是唯一拦得住这类缺陷的办法——
它曾据此发现描边丢失整条闭合边的 bug，而当时的单元测试没有一个发现。

**但校验器也有盲区**：它只证明自己断言过的那些点。开发拾取校验器时就撞上一次——
24 条断言全绿，却漏掉了一个真缺陷（`PickBuffer.clear()` 受 `GL_SCISSOR_TEST` 影响，
只清掉了裁剪盒内那部分，盒外保留上一帧的 ID → 拾取到已消失的对象）。
原因是**它的场景每帧完全相同**，陈旧 ID 与新鲜 ID 恰好一致。
所以校验器的场景要会变：至少包含「某个图元在后续帧消失／移动」，
并专门断言「不该有东西的地方是干净的」，而不只是「该有东西的地方是对的」。

## 依赖

- JDK 21+（编译目标 21；本机实测 JDK 25）
- JavaFX 17.0.6（pom 声明；若 JDK 自带 JavaFX 25，运行时会遮蔽 pom 里的版本）
- LWJGL 3.3.6 + openglfx-lwjgl
- Kotlin 2.3.0

当前仅支持 **Windows**（JavaFX 原生库以 `<classifier>win</classifier>` 声明）。

## 许可证

MIT License
