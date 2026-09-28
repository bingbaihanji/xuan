# 坐标系入库（网格 / 轴线 / 箭头 / 刻度）设计规格

**日期**：2026-09-28
**范围**：把"网格线、坐标轴、箭头、刻度线、刻度文字"的绘制从应用层收进图表后端。
**性质**：**纯增量**——不改 `Series` / `ChartType` 模型，不删任何公开 API。

---

## 1. 问题

今天每一个用图表的应用都要自己画这一套。实测两份：

| 位置 | 内容 |
|---|---|
| `xuan-javafx/src/test/java/.../Main.java` | `drawChartGridAndAxes` + `drawChartTickLabels`，约 70 行 |
| `example/demo/XuanDemo.kt` | **另一份**，写法还不一样 |

两份都是应用层代码，却没有任何业务语义——它们只是"照着 `Axis.ticks()` 把刻度画出来"。

**而且它已经画错了**：`Main.java` 的 `createChart()` **从未调用 `tickLabelReserve`**，所以它的
`plot` 没有为刻度文字留任何空间，而文字被画在 `plot.y + plot.height + 22f`（绘图区之外）
——它会压到轴标题带上。这个错误**不会报任何警**，只是"看着有点挤"。
这正是"布局信息散在应用侧"的必然结果。

## 2. 非目标

- **不做** Series 继承层次重构（已单独否决）。
- **不做** `Gc` 公开 `ChartTextMetrics`（本次不需要：库内部已有 `ChartPainter`，
  它本身就是 `ChartTextMetrics`）。
- **不做** `Gc` 公开虚线描边（那是另一条独立的增量）。
- **不新增**绘图区内的交互元素（十字线已有，见 `ChartInteraction`）。

## 3. 配置面

新增 `com.bingbaihanji.xuan.chart.AxisStyle`（record + 逐字段 wither，
与 `ChartInteractionConfig` / `ChartInsets` 同一风格），经 `Chart.axisStyle(...)` 装配：

```java
chart.axisStyle(AxisStyle.defaults()
        .visible(true)
        .gridVisible(true).gridColor(0xFF394452).gridWidth(1f)
        .axisColor(0xFFE3E8EF).axisWidth(1.5f)
        .arrowsVisible(true).arrowSize(8f)
        .tickMarksVisible(true).tickLength(5f)
        .tickLabelsVisible(true).tickLabelFontSize(12f).tickLabelColor(0xFFC7D0DB));
```

尺寸量（线宽、箭头、刻度长、字号）一律在紧凑构造器里校验**有限且为正**，
与 `ChartInteractionConfig` 同一条口径。颜色用项目统一的 `0xAARRGGBB`。

### ★ `visible` 默认 `false`——这是承重约束，不是偏好

开了轴，绘图区就要让出带子；而 `ChartVerifier` 现有的 168 条像素断言**全部**建立在
"不设标题 / 图例、外边距为 0 时绘图区与外框逐字段相等"这条不变式上。
默认关 ⇒ 现有画面**逐像素不变** ⇒ 非破坏性成立。
**这条由"把默认值改成 true，现有断言必须倒"来反向钉住。**

## 4. 刻度预留：库算的**覆盖**调用方声明的

`ChartLayout.compute` 里那两处 `chart.tickLabelReserve(...)` 改成

- `visible == false` ⇒ **照旧**用调用方声明的量（既有行为一字不变）；
- `visible == true && tickLabelsVisible` ⇒ 用库自己算的：
  `tickLength + tickLabelFontSize × LINE_HEIGHT_FACTOR`，**忽略**调用方声明的量。

**为什么不取 "max" 或 "相加"**：两者都会让"绘图区到底多大"有两个来源，而绘图区算错
只表现为"图小了一圈"（无报错）。覆盖只有一条规则，一眼能算出来。

**已知代价**：y 轴上一个很宽的文字（`1000000`）会在预留带边缘被**切断**。
这与本仓库既有的"装饰裁到带子里、不折行不省略"是同一条取舍（`ChartPainter.begin(Rect)` 已经这么做），
并且**看得见**。要更多空间请给整张图加 `Chart.padding`。

**刻度文字取 `Tick.label()`**，所以业务格式化仍然留在应用侧配 `Axis`。

## 5. 绘制：一次调用，接在数据之前

新增 `chartrender/ChartAxes`（照 `ChartDecorations` 的形状写：布局算好，它只照着画）。
`ChartRenderer.drawChart` 里**接一行**：

```
painter.begin(frame)
  → ChartDecorations.paint      （标题 / 图例 / 轴标题）
  → ChartAxes.paint             （新增：网格 → 轴线 → 箭头 → 刻度线 → 刻度文字）
  → draw(chart, plotRect, …)    （数据系列）
  → drawInteraction             （十字线 / 提示框）
```

**刻度文字也能一起画在数据之前**——理由是数据系列被 `glScissor` **裁在绘图区之内**，
而刻度文字在绘图区**之外**，数据**不可能盖住**它。所以不需要像 `Main.java` 那样分两趟、
也不需要中间插一次 `flush()`。**这一条核对过渲染器的裁剪，不是想当然。**

`ChartAxes` 只开**一层** `begin(layout.frame())`（不像 `ChartDecorations` 每个带子各开一层）：
轴与刻度跨越绘图区边界、文字在绘图区外，没有"一个带子装得下"的矩形。裁到整块外框
已经足够拦住"画到外框之外"。

### 网格线只画内部的那些

判据是 `0 < tick.position() < displayLength`（**严格在绘图区内部**），
而不是 `Main.java` 那种"跳过 `value == 0`"。前者自然跳过与轴线重合的两条边，
且**保留了 y 窗口包含 0 时的中间那条零线**（`Main.java` 的写法会把中间那条也吃掉）。

### 垂直居中复用既有的常量

y 轴刻度文字的基线偏移用 `ChartLayout.CENTER_BASELINE_FACTOR`
（与轴标题居中同一口径），不另立一个"差不多"的数。

## 6. 验收

`ChartVerifier` 新增一节：

| 判据 | 说明 |
|---|---|
| ★ 默认关时画面**逐像素不变** | 现有 168 条断言一条不改，全绿即是证据 |
| 开轴后网格线列数 == 主刻度数（内部那些） | 少画/多画都会倒 |
| 箭头像素出现在轴端 | 像素计数 |
| 刻度文字不越出整块外框 | 逐边扫 |
| 开轴后绘图区**比不开轴小**（预留生效） | 纯计算，`ChartLayout` 层面 |
| `AxisStyle.defaults().visible() == false` | 纯计算，直接钉住默认值 |

**变异验证**：把 `AxisStyle.defaults()` 的 `visible` 改成 `true`
⇒ 现有那批像素断言必须倒（证明"默认关"承重）。

## 7. 风险

1. **改动落在 `ChartVerifier` 与被测代码两侧**。这是本仓库最忌讳的组合（判据可能被
   顺手改歪）。做法：先只**新增**一节，**现有断言一条不动**；若现有断言倒了，
   那是重构引入了行为差异，必须查清而不是改期望值。
2. **`ChartLayout` 的预留算术**同时被 `ChartLayoutTest`（纯计算）与像素断言两侧钉着。
3. 应用层的两份手绘**在本次一并删掉**（`Main.java` 与 demo），否则"库里有一套、
   外面还有两套"会让下一份示例继续抄错的。

## 8. 文档

- `CLAUDE.md`：把"刻度文字仍由调用方画"那条改写为"已入库、默认关"，
  并记下"默认关承重"与"覆盖语义"两条理由。
- `README.md` 与 `docs/Xuan-DEVELOPER-GUIDE.md`：图表一节加配置示例。
