# JFGL - JavaFX OpenGL 2D 绘图框架

一个基于 JavaFX + OpenGL 的轻量级 2D 绘图框架，提供简洁的 Kotlin DSL API。

## 特性

- 🎨 **丰富的绘图工具**：圆形、矩形、三角形、线段、椭圆、弧线、多边形
- 🖱️ **交互支持**：鼠标点击、移动事件处理
- 📦 **简洁的 DSL**：Kotlin 风格的链式调用 API
- ⚡ **高性能**：基于 OpenGL 硬件加速渲染
- 🔧 **易于扩展**：模块化设计，便于添加新功能

## 快速开始

### 1. 使用 DSL 方式（推荐）

```kotlin
import com.bingbaihanji.jfgl.dsl.jfgl

fun main() {
    jfgl {
        title = "我的应用"
        width = 800.0
        height = 600.0

        // 初始化回调
        onInit { draw ->
            println("初始化完成！")
        }

        // 渲染回调 - 每帧调用
        onRender {
            // 绘制红色圆形
            drawCircle(0f, 0f, 0.5f, r = 1f, g = 0f, b = 0f)

            // 绘制绿色矩形
            drawRect(-0.5f, -0.5f, 0.3f, 0.3f, r = 0f, g = 1f, b = 0f)

            // 绘制蓝色三角形
            drawTriangle(
                -0.3f, -0.2f,
                0.3f, -0.2f,
                0f, -0.6f,
                r = 0f, g = 0f, b = 1f
            )
        }

        // 鼠标点击回调
        onClick { x, y, button ->
            println("点击位置: ($x, $y)")
        }

        // 鼠标移动回调
        onMove { x, y ->
            // 鼠标移动处理
        }
    }
}
```

### 2. 使用传统方式

```kotlin
import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.dsl.DrawDSL

fun main() {
    val transfer = FXGLTransfer()
    var draw: DrawDSL? = null

    transfer.onInit {
        draw = DrawDSL(transfer).apply { init() }
    }

    transfer.onRender {
        draw?.drawCircle(0f, 0f, 0.5f)
    }

    // 启动 JavaFX 应用...
}
```

## API 文档

### 绘制方法

#### drawCircle
绘制圆形

```kotlin
drawCircle(
    cx: Float,          // 圆心 x 坐标 (-1 到 1)
    cy: Float,          // 圆心 y 坐标 (-1 到 1)
    radius: Float,      // 半径
    r: Float = 1f,      // 红色分量 (0-1)
    g: Float = 1f,      // 绿色分量 (0-1)
    b: Float = 1f,      // 蓝色分量 (0-1)
    a: Float = 1f,      // alpha 分量 (0-1)
    segments: Int = 64  // 线段数（越大越平滑）
)
```

#### drawRect
绘制矩形

```kotlin
drawRect(
    x: Float,           // 左上角 x 坐标
    y: Float,           // 左上角 y 坐标
    width: Float,       // 宽度
    height: Float,      // 高度
    r: Float = 1f,      // 红色分量
    g: Float = 1f,      // 绿色分量
    b: Float = 1f,      // 蓝色分量
    a: Float = 1f       // alpha 分量
)
```

#### drawLine
绘制线段

```kotlin
drawLine(
    x1: Float,          // 起点 x 坐标
    y1: Float,          // 起点 y 坐标
    x2: Float,          // 终点 x 坐标
    y2: Float,          // 终点 y 坐标
    r: Float = 1f,      // 红色分量
    g: Float = 1f,      // 绿色分量
    b: Float = 1f,      // 蓝色分量
    a: Float = 1f,      // alpha 分量
    lineWidth: Float = 1f  // 线宽
)
```

#### drawTriangle
绘制三角形

```kotlin
drawTriangle(
    x1: Float, y1: Float,  // 第一个顶点
    x2: Float, y2: Float,  // 第二个顶点
    x3: Float, y3: Float,  // 第三个顶点
    r: Float = 1f,
    g: Float = 1f,
    b: Float = 1f,
    a: Float = 1f
)
```

#### drawEllipse
绘制椭圆

```kotlin
drawEllipse(
    cx: Float,          // 中心 x 坐标
    cy: Float,          // 中心 y 坐标
    radiusX: Float,     // x 方向半径
    radiusY: Float,     // y 方向半径
    r: Float = 1f,
    g: Float = 1f,
    b: Float = 1f,
    a: Float = 1f,
    segments: Int = 64
)
```

#### drawArc
绘制弧线

```kotlin
drawArc(
    cx: Float,          // 中心 x 坐标
    cy: Float,          // 中心 y 坐标
    radius: Float,      // 半径
    startAngle: Float,  // 起始角度（弧度）
    endAngle: Float,    // 结束角度（弧度）
    r: Float = 1f,
    g: Float = 1f,
    b: Float = 1f,
    a: Float = 1f,
    segments: Int = 32
)
```

#### drawPolygon
绘制多边形

```kotlin
drawPolygon(
    points: List<Float>,  // 顶点列表 [x1, y1, x2, y2, ...]
    r: Float = 1f,
    g: Float = 1f,
    b: Float = 1f,
    a: Float = 1f
)
```

### 坐标系统

- 坐标范围：-1.0 到 1.0
- 原点 (0, 0) 在窗口中心
- x 轴向右为正
- y 轴向上为正

### 颜色系统

- RGBA 颜色空间
- 每个分量范围：0.0 到 1.0
- 默认颜色为白色 (1, 1, 1, 1)

## 运行示例

```bash
# 编译项目
mvn compile

# 运行绘制示例
mvn exec:java -Dexec.mainClass="com.bingbaihanji.jfgl.example.DrawExampleKt"
```

## 项目结构

```
src/main/
├── java/com/bingbaihanji/jfgl/
│   ├── engine/        # 绘图引擎
│   ├── gl/            # OpenGL 抽象层
│   ├── math/          # 数学工具（向量、矩阵）
│   ├── renderer/      # 渲染器
│   ├── scene/         # 场景图
│   └── util/          # 工具类
└── kotlin/com/bingbaihanji/jfgl/
    ├── dsl/           # Kotlin DSL API
    ├── example/       # 示例代码
    ├── glview/        # JavaFX 集成
    └── view/          # 视图组件
```

## 依赖

- Java 17+
- JavaFX 17.0.6
- LWJGL 3.3.6
- Kotlin 2.3.0

## 许可证

MIT License
