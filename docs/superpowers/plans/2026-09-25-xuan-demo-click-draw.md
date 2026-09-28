# Xuan demo 绘图手势改"点两下" 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把两点定义的四种图形（矩形 / 圆 / 椭圆 / 直线）从"拖拽"改成"点 1 定第一点 → **虚线预览**跟随鼠标 → 点 2 完成"，并把"拾取选中"从"左键单击"移到"右键只按不拖"。

**Architecture:** 全线改动都在 `example/demo/` 这一个包里（**不动库**）。虚线是 demo 自己按弧长切分的描边助手（库里的 `StrokeGenerator.strokeDashed` 存在但 `Gc` 没暴露）；两阶段状态靠一对**独立的新字段** `anchorX/anchorY`，不复用 `dragStartX/Y`（按下会覆盖它）；预览跟随鼠标需要**新注册 `MOUSE_MOVED`**。

**Tech Stack:** Kotlin 21 / JavaFX 25（JDK 内置）/ Xuan 的 `Gc`。

**设计依据：** `docs/superpowers/specs/2026-09-24-xuan-demo-design.md` 的 **§4.2**（手势表、尺规表、§4.2.1 三处连带决定、**§4.2.2 状态机**）与 **§10**（验收清单）。术语与取舍理由都在那里，本计划不重复论证。

---

## 本仓库的元规则（先读，它们每一条都踩过）

1. **改已完工任务的代码块 = 产生一个「挂账项」**：改了某人已完工的代码块之后，
   **必须在会碰同一个文件的后续任务里写明"应用这个改动"的步骤**，否则计划与文件静默差几行。
2. **抽代码块/引用一律按内容定位，不要按行号**——计划是活的，行号会漂。
   写死行号的脚本会拼出乱文件、而 `diff` 还可能**假阳性**。
3. **`grep stderr | grep -i exception` 在本项目几乎证明不了任何事**：GL 线程的异常
   被 openglfx 的原生回调吞掉，**我们代码里一处 `catch` 都没有**。
   要证明"这段代码跑通了"，**在它末尾打一行**——加在末尾才证明"整段函数体都返回了"。
4. **跑 demo 要按 PID 清残留 JVM**（`timeout mvn exec:exec` 只杀 maven，fork 出来的 java 会变孤儿，
   而留下的窗口可能被**人**点过）。**不抢前台、不截屏**。
   **★ 但这句有它自己的失败模式**：本机常驻的 java 进程里有**用户自己的 IDE**
   （IDEA 的 Kotlin 守护进程）与 **Maven 拉起的 Kotlin 编译守护进程**（后者是**设计上常驻**的，
   杀掉只会让下次构建变慢）。所以"清残留"**必须先看命令行确认那是你起的那个**
   （找 `XuanDemoKt` / `*VerifierKt`），**绝不要写成"杀光 `java.exe`"**——
   那会杀掉用户正在用的编辑器。2026-09-26 本机正好是这个局面（一个 Maven Kotlin daemon + 一个 IDEA），
   实现者核实了两个 PID 的用途才决定一个都不动，**这个判断是对的**。
5. **手工跑：** `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` **两个都要**
   （后者管的是 stderr，而诊断走 stderr）。

---

## 文件结构

| 文件 | 本计划改什么 |
|---|---|
| `example/demo/DemoShapes.kt` | 加**一个**文件级助手 `strokeDashedPolyline`（虚线描边） |
| `example/demo/XuanDemo.kt` | 两阶段状态机、右键拾取、`Esc`、`MOUSE_MOVED`、四种预览轮廓、状态栏文案 |
| `example/demo/DemoShapeMath.kt` | **改**（自检的纯计算部分不动，但要补"圆的尺规"那条断言——旧的那条是"内切于框"） |
| `CLAUDE.md` | 加一条缺口：`Gc` 没暴露虚线描边 |

---

## Task 1: 虚线描边助手（`DemoShapes.kt`）

**Files:**
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/DemoShapes.kt`

- [ ] **Step 1: 加助手函数**

追加到 `DemoShapes.kt` 的文件末尾（与 `boundsOf` 并列，同为文件级 `internal`）：

```kotlin
/**
 * 用**虚线**描一条折线。**预览专用。**
 *
 * <p><strong>为什么在 demo 里手算</strong>：库里的 `StrokeGenerator.strokeDashed(...)`
 * **存在且有单测**（`StrokeDashTest`），但 **`Gc` 没有把它暴露出来**——`Gc` 的描边路径
 * 只调实线那个 `stroke(...)`。本 demo 因此自己按**弧长**把折线切成实段。
 * （这条缺口记在 `CLAUDE.md` 的「未实现 / 待办」里。）
 *
 * <p><strong>它只用于预览</strong>：提交时仍然走 `Gc` 的
 * `fillRect` / `fillCircle` / `fillEllipse` / `drawLine`，
 * 所以**画出来的东西与这个助手无关**——它坏掉最多是预览难看。
 *
 * <p>代价照实记：预览轮廓由本文件与 `XuanDemo` 各算一遍，
 * 与 `Gc` 内部那份（`rectOutline` / `circleOutline` / `ellipseOutline`，
 * 都是 `private`）**不是同一份代码**，因此**预览的圆与提交的圆在细分段数上可能不同**。
 *
 * @param gc      绘制上下文（调用方负责设好 `stroke` 与 `lineWidth`）
 * @param points  扁平顶点数组 `[x0,y0, x1,y1, ...]`
 * @param closed  是否首尾相接
 * @param dashOn  实段长度（设备像素），≤0 时退化成实线
 * @param dashOff 空段长度（设备像素），≤0 时退化成实线
 */
internal fun strokeDashedPolyline(
    gc: Gc, points: FloatArray, closed: Boolean, dashOn: Float, dashOff: Float
) {
    val n = points.size / 2
    if (n < 2) return
    val pattern = dashOn + dashOff
    if (dashOn <= 0f || dashOff <= 0f || pattern <= 0f) {
        // 退化：按实线画。**不静默什么都不画**——虚线的参数错不该让预览消失。
        gc.strokePolyline(points, closed)
        return
    }
    val segCount = if (closed) n else n - 1
    var s = 0f                                  // 折线起点算起的累计弧长
    for (i in 0 until segCount) {
        val j = (i + 1) % n
        val ax = points[i * 2]
        val ay = points[i * 2 + 1]
        val bx = points[j * 2]
        val by = points[j * 2 + 1]
        val len = hypot(bx - ax, by - ay)
        if (len <= 1e-6f) continue
        // 本段覆盖全局弧长 [s, s+len)。逐个 dash 实区间与它求交：
        // 一个实区间可能横跨多个折线段，那就在每段里各画一段（拐角处留一个亚像素的缝，
        // 预览上不可见）。
        var k = floor((s / pattern).toDouble()).toInt()
        while (k * pattern <= s + len) {
            val from = maxOf(k * pattern, s)
            val to = minOf(k * pattern + dashOn, s + len)
            if (to > from) {
                val t0 = (from - s) / len
                val t1 = (to - s) / len
                gc.drawLine(
                    ax + (bx - ax) * t0, ay + (by - ay) * t0,
                    ax + (bx - ax) * t1, ay + (by - ay) * t1
                )
            }
            k++
        }
        s += len
    }
}
```

- [ ] **Step 2: 补 import**

`DemoShapes.kt` 顶部现在只有 `import com.bingbaihanji.xuan.renderer.Gc`、
`import com.bingbaihanji.xuan.util.Rect`、`import kotlin.math.abs`。追加三个：

```kotlin
import kotlin.math.floor
import kotlin.math.hypot
```

（`abs` 已有。）

- [ ] **Step 3: 编译**

Run: `mvn -o -pl xuan-javafx -am compile`
Expected: `BUILD SUCCESS`。

- [ ] **Step 4: 提交**

```bash
git add xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/DemoShapes.kt
git commit -m "feat(demo): 虚线描边助手——Gc 没暴露 strokeDashed，预览在 demo 层手算"
```

---

## Task 2: 两阶段状态机 + 右键拾取 + `Esc` + `MOUSE_MOVED`

**Files:**
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/XuanDemo.kt`

> **★ 读设计文档 §4.2.2 再动手。** 那节里有一条**初稿写错、后来修掉**的记录
> （锚点不能复用 `dragStartX/Y`——按下会覆盖它），照本节写就不会踩。

- [ ] **Step 1: 加四个字段**

在 `trajectory` 那个字段附近（"三类状态"的注释之后）加：

```kotlin
    /**
     * **已定第一点**的锚点（两点式图形专用）。`NaN` 表示没有。
     *
     * <p><strong>为什么是独立的字段、不复用 [dragStartX]</strong>：`onPress` 会**覆盖**
     * `dragStartX`，而锚点必须**跨两次点击活着**。初稿把它挂在 `dragStartX` 上，
     * 那会让"第二次点击提交之后紧接着的那次抬起"又被当成"第一点"——
     * 因为提交时 `dragStartX` 被清成 NaN、抬起时 `moved` 就是 0。
     *
     * <p>只有四条路径能清它：**提交**、`Esc`、**切模式**、**切图形**（后三条走 [resetDragState]）。
     */
    @Volatile private var anchorX = Float.NaN
    @Volatile private var anchorY = Float.NaN

    /**
     * 当前鼠标位置（设备像素）。**只为虚线预览存在。**
     *
     * <p>它由新注册的 `MOUSE_MOVED` 写——**没有键按下时也要能跟着鼠标走**，
     * 而 `MOUSE_DRAGGED` 只在按键期间才有。鼠标移动事件可以高达 1000 Hz，
     * 但**一次 volatile float 写是零成本的**，不构成热路径问题。
     * 只在"已定第一点"时被读；其余时候写了没人看。
     */
    @Volatile private var previewX = Float.NaN
    @Volatile private var previewY = Float.NaN
```

- [ ] **Step 2: `resetDragState` 一并清锚点与预览**

现在它是清 `dragStartX/Y` / `trajectory` / `marqueeW`。加两行（**这是"四条取消路径"的落点**）：

```kotlin
    private fun resetDragState() {
        dragStartX = Float.NaN
        dragStartY = Float.NaN
        trajectory = FloatArray(0)
        marqueeW = Float.NaN
        // ★ 锚点也必须清——"切模式会留下脏状态 ⇒ 凭空画一个用户没拖过的图形"那条
        //   教训的另一半：换了图形种类还在等第二点，用户一点就会画出一个他没想要的东西。
        anchorX = Float.NaN
        anchorY = Float.NaN
    }
```

并在它的 KDoc 里补一句（**照 plan 的元规则：改已完工的代码块要留痕**）——

```kotlin
     * <p><strong>2026-09-25 起它还清"已定第一点"的锚点</strong>：两点式图形改成"点两下"之后
     * 多了一个跨点击的状态，而它**只有这一条清理路径**（四条调用点都在这儿）。
```

- [ ] **Step 3: `wireMouse` 注册 `MOUSE_MOVED`**

在现有的三行 `addEventHandler` 之后加第 4 行：

```kotlin
        // 虚线预览要"没有键按下也跟着鼠标走"，所以必须再注册 MOUSE_MOVED——
        // MOUSE_DRAGGED 只在按键期间才有。
        node.addEventHandler(MouseEvent.MOUSE_MOVED) { e -> onMove(bridge, node, e) }
```

并加处理函数：

```kotlin
    /**
     * 鼠标移动（**没有键按下时也来**）。只做一件事：把当前位置记给虚线预览。
     *
     * <p>它**不改任何绘制状态**，所以无论此刻在哪个模式、哪个阶段都安全。
     */
    private fun onMove(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        previewX = (e.x * s).toFloat()
        previewY = (e.y * s).toFloat()
    }
```

- [ ] **Step 4: `onPress` —— 右键不变，左键只记起点**

`onPress` 现在是这样（**只改左键那半**，右键与图表模式守卫**一字不动**）：

```kotlin
    private fun onPress(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        if (e.button == MouseButton.SECONDARY) {
            if (mode != Mode.DRAW) return
            marqueeX = dx; marqueeY = dy; marqueeW = 0f; marqueeH = 0f
            return
        }
        if (e.button != MouseButton.PRIMARY) return
        when (mode) {
            Mode.DRAW -> {
                dragStartX = dx; dragStartY = dy
                trajectory = floatArrayOf(dx, dy)
            }
            Mode.TEXT -> Unit
            Mode.CHART -> Unit
        }
    }
```

**它不用改**——`trajectory` 那行对两点式无害（两点式不看它，而 `resetDragState` 会清）。
`previewX/Y` 也顺手设一下，让"按下"这一下立刻定住预览：

```kotlin
            Mode.DRAW -> {
                dragStartX = dx; dragStartY = dy
                trajectory = floatArrayOf(dx, dy)
                previewX = dx; previewY = dy      // 按下时预览立刻定住，不等下一次 MOUSE_MOVED
            }
```

- [ ] **Step 5: `onRelease` —— 左键那半按"抬起时位移"分三路**

把 `onRelease` 里 `Mode.DRAW` 那一支换成：

```kotlin
            Mode.DRAW -> {
                if (kind.isTrajectory) {
                    // 轨迹型：与第一版完全一致
                    if (moved < CLICK_SLOP) {
                        resetDragState()
                        bridge.clickAsyncAtNode(node, e.x, e.y) { hit -> onPick(hit, dx, dy) }
                    } else {
                        commitShape(dx, dy)
                        resetDragState()
                    }
                } else if (moved < CLICK_SLOP) {
                    // 两点式的一次"点击"：没有锚点就定锚点，有锚点就提交。
                    // **判据是 anchorX 是不是 NaN**——不是 dragStartX（按下刚覆盖过它）。
                    if (anchorX.isNaN()) {
                        // ★ 顺序不能反：先 resetDragState()（清掉这次按下留下的
                        //   dragStartX / trajectory），**再**把锚点设上。
                        //   反过来说就是"清掉刚设的锚点"，而那看起来只是"第一点没记住"。
                        val ax = dx
                        val ay = dy
                        resetDragState()
                        anchorX = ax
                        anchorY = ay
                        status.text = "${kind.label}：已定第一点 (${ax.toInt()},${ay.toInt()})，" +
                            "再点一下完成（Esc 取消）"
                    } else {
                        val before = shapes.get().size
                        commitTwoPointShape(dx, dy)
                        resetDragState()
                        // 只在**真的画出来了**的时候报"已画"——两点重合会被拒，
                        // 那时 commitTwoPointShape 自己写了原因，别把它盖掉。
                        if (shapes.get().size > before) {
                            status.text = "已画：${shapes.get().last().shape.describe()} · " +
                                "共 ${shapes.get().size} 个"
                        }
                    }
                } else {
                    // 两点式上"拖拽"：**刻意什么都不做**（同一个图形不能既靠拖又靠点）
                    resetDragState()
                    status.text = "${kind.label}请点两下：第一下定起点、第二下完成"
                }
            }
```

- [ ] **Step 6: `Mode.TEXT` 那支也要清锚点**

`onRelease` 的 `Mode.TEXT` 那支现在是 `Unit`（Task 8 时留的，后来接了 `commitText`）。
**它必须也 `resetDragState()`**——否则"在绘图模式点了第一点、切到文本模式点一下"会
留下锚点，切回绘图模式再点一下就会**凭空画出一个用旧锚点的图形**（这正是
`resetDragState` 的 KDoc 里那条教训）。**确认它有**；没有就加。

- [ ] **Step 7: `commitTwoPointShape` —— 两点式的提交**

新增（**与 `commitShape` 并列**，不动 `commitShape`——那是轨迹型与旧路径用的）：

```kotlin
    /**
     * 用**锚点 + 这一下**提交一个两点式图形。**只在 JavaFX 线程调用。**
     *
     * <p>尺规见设计文档 §4.2 的那张表。**与第一版的差别只有圆**：
     * 它是"**圆心 + 半径**"（`r = 锚点到这一下的距离`），而第一版是"内切于拖拽框"。
     * 那是刻意的改动，不是回归。
     */
    private fun commitTwoPointShape(x1: Float, y1: Float) {
        val x0 = anchorX
        val y0 = anchorY
        val w = x1 - x0
        val h = y1 - y0
        val s: Shape? = when (kind) {
            ShapeKind.RECT ->
                if (abs(w) > 0f && abs(h) > 0f)
                    Shape.RectShape(minOf(x0, x1), minOf(y0, y1), abs(w), abs(h), color, style, lineWidth)
                else null

            ShapeKind.CIRCLE -> {
                val r = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                if (r > 0f) Shape.CircleShape(x0, y0, r, color, style, lineWidth) else null
            }

            ShapeKind.ELLIPSE ->
                if (abs(w) > 0f && abs(h) > 0f)
                    Shape.EllipseShape(x0, y0, abs(w), abs(h), color, style, lineWidth)
                else null

            ShapeKind.LINE -> Shape.LineShape(x0, y0, x1, y1, color, style, lineWidth)
            else -> null       // 轨迹型不走这条路
        }
        if (s == null) {
            status.text = "两点重合，没有形成图形（${kind.label} 要求两点不重合）"
            return
        }
        val id = transfer?.gc()?.pickRegistry?.register(s) ?: 0
        shapes.set(shapes.get() + Placed(s, id))
    }
```

**注意**：它**不设 `status.text`**——那句话由调用方按"已画：…"的既有格式写
（见 Step 5 里那两处 `status.text`），避免两处各写一半。

- [ ] **Step 8: `drawDragPreview` —— 两点式改画虚线预览**

现在它只画轨迹型的实线预览。改成先判锚点：

```kotlin
    /**
     * 预览。**两种形态**：
     * - **两点式**（矩形/圆/椭圆/直线）在"已定第一点"时画**虚线轮廓** + 锚点上的十字；
     * - **轨迹型**按住拖拽时画**实线轨迹**（与第一版一致）。
     *
     * <p>`gc.pickId = 0` 与不加 `try/finally` 的理由见原来的 KDoc（**别删那段**）。
     */
    private fun drawDragPreview(gc: Gc) {
        if (mode != Mode.DRAW) return
        gc.save()
        gc.pickId = 0
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f

        val ax = anchorX
        if (!ax.isNaN() && !kind.isTrajectory) {
            // ① 两点式：虚线轮廓。鼠标位置取 previewX/Y；
            //    还没收到过 MOUSE_MOVED（刚进这个状态）时退回锚点本身，
            //    此时轮廓退化成一个点——**锚点十字就是那个状态下唯一的可见反馈**。
            val px = if (previewX.isNaN()) ax else previewX
            val py = if (previewY.isNaN()) anchorY else previewY
            val outline = twoPointPreviewOutline(kind, ax, anchorY, px, py)
            if (outline != null) {
                strokeDashedPolyline(gc, outline, closed = kind != ShapeKind.LINE,
                    dashOn = PREVIEW_DASH_ON, dashOff = PREVIEW_DASH_OFF)
            }
            // 锚点十字：与鼠标重合时虚线退化成零长、什么都看不见，
            // 没有它就分不出"还没有第一点"与"第一点正好在鼠标下"。
            gc.stroke = PEN_CROSS
            gc.drawLine(ax - 8f, anchorY, ax + 8f, anchorY)
            gc.drawLine(ax, anchorY - 8f, ax, anchorY + 8f)
        } else {
            // ② 轨迹型：与第一版一致
            val sx = dragStartX
            if (sx.isNaN()) { gc.restore(); return }
            val pts = trajectory
            if (pts.size >= 4) gc.strokePolyline(pts, closed = false)
        }
        gc.restore()
    }

    /**
     * 两点式的预览轮廓。**中心/半径/半轴的尺规必须与 [commitTwoPointShape] 一致**——
     * 两处各持一半解释的话，预览与提交结果会不一样，而"预览只是稍微偏一点"最难发现。
     *
     * @return `[x0,y0, x1,y1, ...]`；退化输入返回 null
     */
    private fun twoPointPreviewOutline(
        kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float
    ): FloatArray? = when (kind) {
        ShapeKind.RECT -> if (abs(x1 - x0) > 0f && abs(y1 - y0) > 0f)
            floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1) else null

        ShapeKind.CIRCLE -> {
            val r = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
            if (r <= 0f) null else circlePoints(x0, y0, r)
        }

        ShapeKind.ELLIPSE -> if (abs(x1 - x0) > 0f && abs(y1 - y0) > 0f)
            ellipsePoints(x0, y0, abs(x1 - x0), abs(y1 - y0)) else null

        ShapeKind.LINE -> if (x1 != x0 || y1 != y0) floatArrayOf(x0, y0, x1, y1) else null
        else -> null
    }

    /** 预览用的圆周折线。段数是**固定的**（预览不追求与 `Gc` 的细分规则一致，见 `strokeDashedPolyline` 的说明）。 */
    private fun circlePoints(cx: Float, cy: Float, r: Float): FloatArray =
        ellipsePoints(cx, cy, r, r)

    /** 预览用的椭圆折线。 */
    private fun ellipsePoints(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val seg = PREVIEW_CURVE_SEGMENTS
        val out = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            out[i * 2] = cx + rx * cos(a)
            out[i * 2 + 1] = cy + ry * sin(a)
        }
        return out
    }
```

- [ ] **Step 9: 常量与 import**

在 `XuanDemo.kt` 的常量区加：

```kotlin
/** 虚线预览的实段/空段长度（设备像素）。见设计文档 §4.2.2 的"预览的虚线参数"。 */
private const val PREVIEW_DASH_ON = 6f
private const val PREVIEW_DASH_OFF = 4f

/** 预览曲线（圆/椭圆）的折线段数。固定值——预览不追求与 `Gc` 的细分规则一致。 */
private const val PREVIEW_CURVE_SEGMENTS = 48
```

import 追加（若缺）：`kotlin.math.cos`、`kotlin.math.sin`（`abs`/`hypot` 已有）。

- [ ] **Step 10: `Esc` 取消**

`stage.scene.setOnKeyPressed` 现在处理 `DELETE` / `BACK_SPACE`。加一支：

```kotlin
            if (e.code == KeyCode.ESCAPE) {
                // 取消"已定第一点"。**走 resetDragState**——它是四条取消路径的唯一落点。
                resetDragState()
                status.text = "已取消"
                return@setOnKeyPressed
            }
```

- [ ] **Step 11: 编译**

Run: `mvn -o -pl xuan-javafx -am compile`
Expected: `BUILD SUCCESS`。

- [ ] **Step 12: 提交**

```bash
git add xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/XuanDemo.kt
git commit -m "feat(demo): 两点式改点两下——虚线预览、锚点独立成字段、拾取挪到右键、Esc 取消"
```

---

## Task 3: 自检模式按新手势重写

**Files:**
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/XuanDemo.kt`
- Modify: `xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/DemoShapeMath.kt`

> **★ 这不是"跑一遍看红不红"。** 旧的 11 条里 ①②③④ 是按**拖拽**写的
> （`dragFromTo` 合成一次 press→drag→release 就得到一个图形）。
> 手势一改之后它们**不是报错、是断言不再成立**——`shapes` 不会增加，
> 而 ②③④ 会跟着连锁倒。所以是**按新手势重写**，不是修修补补。

- [ ] **Step 1: 事件合成的两个新助手**

自检文件里已经有 `fireMouse` / `dragFromTo` / `clickAt` / `marqueeFromTo` / `fireDeleteKey`
（**先读它们的签名**——本步要用同一个构造器，不另起一套）。

**加两个薄封装**：

```kotlin
    /**
     * 合成一次"左键单击"——两点式的一次点击。
     * **按下与抬起用同一个坐标**（位移 0 ⇒ `moved < CLICK_SLOP` ⇒ 走"点击"那一支）。
     */
    private fun clickLeft(node: Node, x: Double, y: Double) {
        fireMouse(node, MouseEvent.MOUSE_PRESSED, x, y, MouseButton.PRIMARY)
        fireMouse(node, MouseEvent.MOUSE_RELEASED, x, y, MouseButton.PRIMARY)
    }

    /**
     * 合成一次"右键单击"——拾取选中（2026-09-25 起拾取从"左键单击"移到这里）。
     * 同样是**按下与抬起同一点**，否则会变成框选。
     */
    private fun clickRight(node: Node, x: Double, y: Double) {
        fireMouse(node, MouseEvent.MOUSE_PRESSED, x, y, MouseButton.SECONDARY)
        fireMouse(node, MouseEvent.MOUSE_RELEASED, x, y, MouseButton.SECONDARY)
    }

    /** 合成一次"鼠标移动"（**没有键按下**）——虚线预览的输入源。 */
    private fun moveTo(node: Node, x: Double, y: Double) {
        fireMouse(node, MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE)
    }
```

> **若现有 `fireMouse` 的签名不是 `(Node, EventType, Double, Double, MouseButton)`**，
> **按它的实际签名写这三个封装**——**不要改 `fireMouse` 本身**（它被其它断言用着）。
> `MOUSE_MOVED` 用**同一个构造器**，只换 `EventType` 与 `button = NONE`。
> 字段口径与仓库既有校验器一致（`ClickVerifier`：`scene.x/scene.y` +
> `pickResult = null` + `stillSincePress = true`），**照抄那套**。

- [ ] **Step 1b: 给虚线加**两个**探针计数器（不是"末尾一行"）**

> **★ 这一处是 Task 1 的质量审查改过的**：原设计写的是"在函数**最后一行**加一个计数器"，
> 而 `strokeDashedPolyline` 有**两个出口**——退化那条（参数不可用 ⇒ 画实线）会在到达末行**之前** `return`。
> 只放末行的话，**退化路径永远不计数**，而那恰恰是守卫存在的理由所在。
> 改成两个计数器，**两条出口各自可见**。

`strokeDashedPolyline` 画在 GL 侧、没有读数，所以加两个自检专用计数器：

```kotlin
/**
 * 自检专用的探针：`strokeDashedPolyline` **真的按虚线画了**的次数
 * （即走完主循环、没有落到退化分支）。
 *
 * <p>它只在 `SELFTEST` 打开时自增；**没有任何绘制逻辑读它**。
 * 断言"虚线预览真的画了"只能靠它——帧缓冲读不回来，而
 * "走完了主循环"证明的是**整段函数体都正常返回了**
 * （GL 线程的异常被 openglfx 的原生回调吞掉，我们代码里一处 `catch` 都没有）。
 */
@Volatile internal var dashedSegmentsDrawn: Int = 0

/**
 * 自检专用的探针：`strokeDashedPolyline` **落进退化分支**（参数不可用 ⇒ 画实线）的次数。
 *
 * <p>与 [dashedSegmentsDrawn] 分开计是刻意的：**一个非 0 的退化计数**
 * 说明调用方传了不可用的 dash 参数——而那正是那条守卫存在的理由。
 * 合成到一起的话，"虚线画了"与"退化成实线了"就分不出来，而两者在画面上
 * **都可能看起来像一条正常的预览线**。
 */
@Volatile internal var dashedFallbacks: Int = 0
```

两处各自自增（**退化那一支在 `return` 之前**）：

```kotlin
    if (!usable) {
        if (SELFTEST) dashedFallbacks++
        gc.strokePolyline(points, closed)
        return
    }
    // ……主循环……
    if (SELFTEST) dashedSegmentsDrawn++
```

**Task 3 的断言因此是两条**：`dashedSegmentsDrawn > 基准` **且** `dashedFallbacks == 基准`
（后者顺带证明调用方传的 `6f`/`4f` 真的可用）。

> `SELFTEST` 在 `DemoShapes.kt` 里还没有——**用 `DemoChart.kt` 那个写法**：
> `private val SELFTEST = selfTestEnabled()`（`selfTestEnabled()` 是共用判定，
> 与 `SELFTEST_PROPERTY` 同处）。**别另写一份解析**——同一个属性名两份解析
> 正是 2026-09-25 那次"被推翻的间歇性缺陷"的真因。

- [ ] **Step 2: 重写 ①②③④**

四条的目标不变（形态），**手势换成"点两下"**：

| # | 旧（拖拽） | 新（点两下） |
|---|---|---|
| ① | `dragFromTo` 得到一个矩形 | `clickLeft` 定第一点 → `moveTo` 到对角（**顺带断言虚线预览存在**）→ `clickLeft` 完成 |
| ② | 点图形内部命中 | **不变**（但拾取改成**右键单击**） |
| ③ | 重叠取后画的 | 同 ①，画两个 |
| ④ | 只描边不命中 | **不变**（拾取改右键） |

**新增三条**：

| # | 验什么 | 判据 |
|---|---|---|
| ⑤ | **圆的尺规是"圆心 + 半径"** | 锚点 (cx,cy)、第二点 (cx+50,cy) ⇒ `CircleShape.r ≈ 50`（**不是第一版的"内切于框"**） |
| ⑥ | **`Esc` 取消** | 点第一点 → `Esc` → `shapes` **不变**；再点一下**不会**接着上一点（`shapes` 只多一个） |
| ⑦ | **两点式上"拖拽"什么都不做** | `dragFromTo` 一个矩形轨迹 ⇒ `shapes` **不变**，且状态栏含"请点两下" |

> **① 里那条"虚线预览存在"怎么断言**：用 Step 1b 那个末尾探针——
> **定完第一点、`moveTo` 到对角之后，`dashedSegmentsDrawn > 基准`**。
> （断言"跑到了末尾"而不是"画得对不对"：那正是本项目唯一可靠的证据形式，见元规则 3。）

- [ ] **Step 3: 保留原有的其它断言**

⑤ 框选、⑥ Delete、⑦ LIFO、⑧ 文本、⑨ 切模式、⑩ 图表、⑪ 四种图型
——**它们的编号会变**（因为插进了新的三条），**内容不变**。
`DemoShapeMath.kt` 的 12 条纯计算断言**一条不改**（它们不碰手势）。

- [ ] **Step 4: 跑自检**

```bash
cd xuan-javafx && mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dxuan.demo.selftest=1 -cp %classpath com.bingbaihanji.xuan.example.demo.XuanDemoKt"
```
Expected: 全部断言过 + `[自检-合成] 全部通过` + **退出码 0**（它自己退出，不用超时杀）。

- [ ] **Step 5: ★ 三条变异（都必须让对应的断言倒下）**

| 变异 | 必须倒 |
|---|---|
| 把 `onRelease` 里"两点式 + 点击"那支的 `anchorX.isNaN()` 改成恒 `true`（永远只定锚点、不提交） | ① |
| 把 `commitTwoPointShape` 里 `CIRCLE` 的 `r` 改回"到框的边距"（第一版尺规） | ⑤ |
| 把 `Esc` 那支删掉 | ⑥ |

三条都用 `trap … EXIT` + **绝对路径**还原，还原后 `cmp` 逐字节核对 + 重跑回到退出码 0。

- [ ] **Step 6: 提交**

```bash
git add xuan-javafx/src/main/kotlin/com/bingbaihanji/xuan/example/demo/XuanDemo.kt
git commit -m "test(demo): 自检按新绘图手势重写——点两下、虚线预览、Esc、圆的新尺规"
```

---

## Task 4: `CLAUDE.md` 补缺口 + 回归

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 加一条缺口**

在「未实现 / 待办」的 demo 那组条目里追加（放在 `Gc` 没公开 `ChartTextMetrics` 那条附近，
它们是同一族：**能力在库里、但没接到公开 API 上**）：

```markdown
  - **`Gc` 没有暴露虚线描边**：`StrokeGenerator.strokeDashed(...)`（弧长切分、dash 模式 + 相位）
    **存在且有单测**（`StrokeDashTest`），但 `Gc` 的描边路径只调实线那个 `stroke(...)`。
    于是任何想画虚线的应用都得在应用层重写一遍弧长切分——`example/demo/` 的
    `strokeDashedPolyline` 就是这么做的（**只用预览，见下**）。
    与「`Gc` 没公开 `ChartTextMetrics`」同族：**能力在库里、但没接到公开 API 上**。
```

- [ ] **Step 2: 更新 demo 的手势描述**

`CLAUDE.md` 里若有描述 demo 交互的地方（grep `点两下`/`拖拽画`），按 §4.2 改。
（若没有，跳过。）

- [ ] **Step 3: 回归**

```bash
mvn -o clean test
```
Expected: **370 通过 / 0 失败 / 2 跳过**（本计划只碰 `xuan-javafx`，那个模块没有 surefire 测试）。

再跑一次自检模式确认退出码 0；再跑 `PathVerifier` / `PickVerifier` 确认退出码 0
（**不跑 `ClickVerifier`**——它那节 `Robot` 在机器被占用时必然红，与本次改动无关）。

- [ ] **Step 4: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 记下 Gc 没暴露虚线描边这条缺口"
```

- [ ] **Step 5: 把"人工验收"交给人（**你不做**）**

设计文档 §10 的**手工验收清单**已经按新手势改好了（绘图那 4 条 + 新增的 `Esc`）。
**它需要一个真的鼠标**，所以**你做不到，也不要用 `Robot` 去凑**
（demo 在另一个进程里，窗口位置与布局都要猜，猜错会得出"点了没反应"这种**错误结论**，
比没有结论更坏）。请在报告里**明确说明你没做这一步**。

**自检模式覆盖了其中一部分**（①②③④⑤⑥⑦），但它验的是"接线通不通、状态对不对"，
**验不了**"虚线好不好看、圆的半径手感对不对、文字位置准不准"——那些只有人能做。

---

## 自检清单

- [ ] 每次接鼠标事件都乘了 `bridge.deviceScale(node)`（**包括新加的 `MOUSE_MOVED`**）
- [ ] 锚点只在四条路径上被清：提交 / `Esc` / 切模式 / 切图形
- [ ] 预览的尺规与 `commitTwoPointShape` **逐条一致**（圆：圆心 + 到鼠标的距离）
- [ ] `gc.save()` / `restore()` 在 `drawDragPreview` 的两个分支上都配对
- [ ] 临时改动（种子、探针、变异）全部删净
- [ ] 残留 JVM = 0；没抢前台、没截屏
- [ ] 提交信息中文，且说明了"为什么"
