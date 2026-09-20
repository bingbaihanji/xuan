# 图表类框架实施计划（子项目 D-①）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 JFGL 加上"数据 / 轴 / 刻度 / 脏区间 / 配色 LUT / 图表装配"这一层——**纯计算、零 GL 依赖**，为 ②（GPU 绘制后端）与 ③（GPU 计算）留好接口。

**Architecture:** 新增 `com.bingbaihanji.jfgl.chart` 包。数据侧用 `ChartData` 接口统一"静态数组"与"SPSC 环形缓冲"两种来源，接口自带 `revision` + `dirtyRange`（**这是 ① 相对 chart-fx 的唯一实质增强**，也是"GPU 常驻 + 增量上传"能不能成立的关键）；轴侧 `Axis` 只管"显示窗口 + 双向映射"，范围由数据自己声明，于是多 Y 轴是自然结果而非特例；刻度是纯算术的 nice number 生成器；配色归一化成 1×256 LUT（正好落在 JFGL 已定的"所有 Paint 归一化为纹理"上）。① 与 ② 的接缝只有两个类型：`RenderContext`（空接口，② 扩展它）与 `SeriesRenderer`（纯函数：数据 + 轴 → 顶点）。

**Tech Stack:** Java 21（本包全部是 Java，无 Kotlin）、JUnit 5。**不引入任何新依赖**，不使用 `gl/`、`renderer/`、`text/`、`geom/` 的任何东西，不使用 BigDecimal。

**设计依据**：`docs/superpowers/specs/2026-09-20-jfgl-chart-framework-design.md`

---

## ⛔ 分界线（这一条比计划里其它所有内容都重要）

规格 §13 的最后一条，原样抄在这里：

> **不动**：`gl/`、`renderer/`、`text/`、`geom/` 全部不改。**若有任何一处需要改它们，说明 ① 的分界线画错了，应停下来重新讨论。**

执行过程中**任何一步**发现"必须动这四个包"，**立刻停下并上报**，不要顺手改、也不要在计划里就地打补丁。
本计划的所有任务都只新建 `chart/` 下的文件（外加 Task 10 的 `CLAUDE.md` / `README.md`），
没有任何一个 `Modify:` 指向那四个包。

---

## 对规格的五处取舍（写实现前先看这里，都是规格留白的地方）

| # | 规格原文 | 本计划的做法 | 理由 |
|---|---------|------------|------|
| 1 | §5.6「必须在丢弃处插一个缺口标记」 | **不往环里写物理标记**：滑动窗口里被丢弃的样本位置必然**在窗口之外**，`value()` 对窗口外的下标返回 NaN，那就是缺口的位置 | 窗口里只有最近 `capacity` 个样本，**全部有效**，环里没有"缺口"这个位置可放。写物理标记要多一个"读者→写者"的 volatile 字段，且在边界情形下会顶掉一个仍然有效的样本。NaN 表示窗口之外，代价为零。详见 Task 5 的类文档 |
| 2 | §9 `SeriesRenderer.render(RenderContext ...)`，`RenderContext` 不属于 ① | ① 里定义 `RenderContext` 为**空接口**（不是 `Object` 占位） | 用 `Object` 的话 ② 必须回头改 ① 的签名，已落地的东西全要动——正是"签名承诺"要避免的。空接口让签名现在就终局，② 只**扩展**（定义子接口）而非**修改**。代价是 ② 的一次向下转型，见 Task 9 |
| 3 | §5.2 `ChartData` 的五个方法（没有 `dimensionCount()`） | **不加**第六个方法：维度数由 `SeriesRenderer` 收到的 `axes` 数组长度给出 | ① 的接口方法越少越好；`axes` 按维度下标对齐后，"有几个维度"本来就是轴的个数。② 不需要再去问数据 |
| 4 | §6「三级刻度（主/中/次）」 | 步长取 `step / step/2 / step/4`（不是 `/2` 与 `/5`），且**一个值只发射一次，取最粗的级别** | `/4` 才让包含关系在构造上成立（`step/2` 的格未必包含 `step` 的格：5 与 2.5 就是反例）。一个值发射三次是让渲染器自己去掉重复，纯属浪费。**包含关系因此是"格"层面的**，见 Task 2 的测试注释 |
| 5 | §6「TIME 轴」 | 标签一律用 **UTC**，不读系统时区 | 读 `ZoneId.systemDefault()` 会让同一段代码在两台机器上给出不同标签，测试只会在别人的机器上红。要显示本地时间应由应用层把时区传进来，而不是让纯计算的 ① 去读全局状态 |

---

## 文件结构

**新建（main，`src/main/java/com/bingbaihanji/jfgl/chart/`，共 16 个文件）**

| 文件 | 职责 | 任务 |
|------|------|------|
| `DirtyRange.java` | `[firstDirty, lastDirty)` 半开区间；空区间表示无变化 | 1 |
| `AxisRange.java` | 一个维度的范围 + 名称 + 单位；退化/非正范围的**唯一**稳定化处 | 1 |
| `ChartData.java` | 数据接口：`axisRange` / `itemCount` / `value` / `revision` / `dirtyRange` | 1 |
| `AxisType.java` | `LINEAR` / `LOGARITHMIC` / `TIME` / `TEXT` | 2 |
| `Tick.java` | 一个刻度：值 + 位置 + 标签 + 级别 | 2 |
| `TickGenerator.java` | nice number 刻度（纯算术、零依赖） | 2 |
| `Axis.java` | 显示窗口 + `dataToDisplay` / `displayToData` + 刻度装配 | 3 |
| `ArrayChartData.java` | 静态实现：一次性数据 | 4 |
| `RingChartData.java` | 流式实现：SPSC 环形缓冲 + 缺口 + 丢弃计数 | 5 |
| `ColorMapping.java` | 科学配色 → 1×256 LUT（含内嵌 `Stop` record） | 7 |
| `ChartType.java` | 图型标签：**属性组合**，不是类层级 | 8 |
| `Series.java` | 数据 + 样式 + 图型标签 | 8 |
| `Layer.java` | 一层（一组 Series） | 8 |
| `Chart.java` | 装配根：轴 + 层 + 系列 | 8 |
| `RenderContext.java` | **空接口**：① 只声明这个类型存在，② 给它血肉 | 9 |
| `SeriesRenderer.java` | 接口：数据 + 轴 → 顶点（② 实现它） | 9 |

**新建（test，`src/test/java/com/bingbaihanji/jfgl/chart/`，共 8 个文件）**
`TickGeneratorTest`、`AxisTest`、`ArrayChartDataTest`、`RingChartDataTest`、
`ChartDataConcurrencyTest`、`ColorMappingTest`、`ChartTest`、`ChartPackageIsolationTest`

**修改（Task 10 只改这两个）**
`CLAUDE.md`、`README.md`

---

## 全量测试计数

起点 **205**（`mvn -o clean test` 实测：205 通过 / 0 失败 / 2 跳过；2 个跳过是
`TessellatorRegressionTest` 里两条 `@Disabled` 的已知缺陷）。

下表每个数字都是照着**本计划代码块里 `@Test` 的条数**点出来的，不是估的。
**改动任何一个任务的测试都要同步改这张表。**

> **⚠️ 这张表假设串行执行。** 若多个任务**并行**，每个执行者看到的全量数取决于别人的任务
> 是否已落地，中间的绝对数字会对不上。**并行时判定标准不是这张表**，而是：
> **你自己新增的测试条数对不对 + 全量无失败**。绝对数如实报告即可，
> **绝不允许为了让数字对上而增删测试**。
>
> 另一个并行副作用：多个代理同时重编译 `target/classes` 会让 surefire 偶发
> `Unable to create test class`（不是代码问题，重跑即可）。**遇到先重跑一次再判断。**

| 任务 | 新增测试 | 全量 |
|------|----------|------|
| 起点 | —— | 205 |
| Task 1 | 0（纯数据载体，理由见该任务说明） | 205 |
| Task 2 | +12 `TickGeneratorTest` | **217** |
| Task 3 | +8 `AxisTest` | **225** |
| Task 4 | +6 `ArrayChartDataTest` | **231** |
| Task 5 | +16 `RingChartDataTest` | **247** |
| Task 6 | +2 `ChartDataConcurrencyTest` | **249** |
| Task 7 | +7 `ColorMappingTest` | **256** |
| Task 8 | +3 `ChartTest` | **259** |
| Task 9 | +2 `ChartPackageIsolationTest` | **261** |
| Task 10 | 0（只改文档） | **261** |

**数字对不上就停下来查**，不要为了让计数凑上而增删测试——本项目已经踩过两次。
**计划里的数字是笔算结果，代码块才是唯一事实来源。**

---

## Task 1: 三个基础类型（DirtyRange / AxisRange / ChartData）

这一组是**纯数据载体**：两个 record 加一个接口，没有任何分支逻辑。
**不写测试**，理由写清楚：给 record 写"构造器会赋值"的断言是橡皮图章；
而这两个 record 里真正有逻辑的两处——`withMinimumSpan()`（退化范围扩成最小可视跨度）
与 `withPositiveMin()`（对数轴钳到正下限）——**由 Task 2、Task 3 的测试间接覆盖**，
那两条断言（`退化范围不产生NaN且有刻度`、`对数轴上出现非正值时钳到下限且不产生NaN`）
走的就是这两个方法。为它们单独建测试类等于把同一件事断言两遍。

> **执行者注意**：既然 Task 1 的辅助方法靠下游测试覆盖，**Task 2 / Task 3 的实现必须真的调用它们**，
> 不许另外抄一遍等价逻辑。抄一遍的后果是：这两处逻辑有一份没有任何测试。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/DirtyRange.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/AxisRange.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/ChartData.java`

- [ ] **Step 1: 写 DirtyRange**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/DirtyRange.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 数据里"变了的那一段"，半开区间 {@code [firstDirty, lastDirty)}。
 *
 * <h2>为什么脏区间是一等公民</h2>
 * <p>chart-fx 的 {@code DataSet} 也有脏位，但那是<strong>图表级的一个全局标志</strong>：
 * 它只说明"要重画"，没说明"重画哪一段"。于是它的渲染器每帧仍要把二分查窗口、
 * 重烘屏幕坐标、重做缩减全部走一遍。
 *
 * <p>JFGL 要的是 <strong>GPU 常驻 + 增量上传</strong>：数据上传一次长期驻留，
 * 只有新增的那几十个点走 {@code glBufferSubData}。这要求"变了哪一段"能被精确表达，
 * 而不是一个布尔。
 *
 * <h2>空区间的表示</h2>
 * <p>无变化时返回 {@link #EMPTY}（即 {@code (0, 0)}）。判定统一用 {@link #isEmpty()}：
 * 它是 {@code firstDirty >= lastDirty}，因此 {@code (5, 5)} 这种"贴着某个位置的空区间"
 * 也一并算空——不引入第二种空区间的表示。
 *
 * @param firstDirty 脏区间的起点（含）
 * @param lastDirty  脏区间的终点（不含）；等于 {@code firstDirty} 即空
 */
public record DirtyRange(int firstDirty, int lastDirty) {

    /** 空区间：没有任何变化。 */
    public static final DirtyRange EMPTY = new DirtyRange(0, 0);

    public DirtyRange {
        if (firstDirty < 0) {
            throw new IllegalArgumentException("firstDirty 不能为负：" + firstDirty);
        }
        if (lastDirty < firstDirty) {
            throw new IllegalArgumentException(
                    "lastDirty 不能小于 firstDirty：" + firstDirty + ".." + lastDirty);
        }
    }

    /**
     * 是否为空区间（没有任何变化）。
     *
     * @return 无变化时为 true
     */
    public boolean isEmpty() {
        return firstDirty >= lastDirty;
    }
}
```

- [ ] **Step 2: 写 AxisRange**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/AxisRange.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 一个维度的范围 + 名称 + 单位。
 *
 * <h2>范围在数据侧，不在轴侧</h2>
 * <p>这条抄 chart-fx：数据自己声明自己的范围与名称单位，<strong>轴只是显示窗口</strong>，
 * 不去遍历数据。好处有两个：轴与数据解耦；同一个范围能被多个轴共享，
 * 于是<strong>多 Y 轴是自然结果而不是特例</strong>。
 *
 * <h2>这一个类里有两处"稳定化"，它们是全项目唯一的一份</h2>
 * <ul>
 *   <li>{@link #withMinimumSpan()}：跨度为 0 或负时扩成一个最小可视跨度。
 *       规格 §10 要求"轴范围退化（min == max）→ 自动扩成最小可视跨度，不除零"，
 *       实现就在这里。换算里除以零会得到 NaN 或 Infinity，而 NaN 进入顶点缓冲后
 *       整条曲线（乃至同批次别的图元）都会消失——不报错，只是什么都没了。</li>
 *   <li>{@link #withPositiveMin()}：对数轴上出现 ≤ 0 的值时钳到正下限。
 *       规格 §10 要求"明确处理，不产生 NaN"。</li>
 * </ul>
 * <p><strong>下游（{@link TickGenerator}、{@link Axis}）必须调用这两个方法，
 * 不许另抄一份等价逻辑。</strong>抄一份的后果是这两处逻辑有一份没有测试。
 *
 * @param min  下界（含）
 * @param max  上界（含）；允许等于 {@code min}（退化范围由 {@link #withMinimumSpan()} 处理）
 * @param name 维度名，如 {@code "电压"}，不能为 null；为空串表示不显示名字
 * @param unit 单位，如 {@code "V"}，不能为 null（没有单位就传空串）
 */
public record AxisRange(double min, double max, String name, String unit) {

    /** 线性轴跨度为 0 时扩成的最小可视跨度。 */
    public static final double MIN_SPAN = 1.0;

    public AxisRange {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            throw new IllegalArgumentException("范围不能是 NaN：" + min + ".." + max);
        }
        if (Double.isInfinite(min) || Double.isInfinite(max)) {
            throw new IllegalArgumentException("范围必须是有限数：" + min + ".." + max);
        }
        if (min > max) {
            throw new IllegalArgumentException("min 不能大于 max：" + min + ".." + max);
        }
        if (name == null) {
            throw new IllegalArgumentException("name 不能为 null（不显示名字就传空串）");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit 不能为 null（没有单位就传空串）");
        }
    }

    /** 只要范围、不带名字单位的构造。 */
    public static AxisRange of(double min, double max) {
        return new AxisRange(min, max, "", "");
    }

    /** 跨度。退化范围返回 0（把它当分母前先过 {@link #withMinimumSpan()}）。 */
    public double span() {
        return max - min;
    }

    /** 是否退化（跨度为 0）。 */
    public boolean isDegenerate() {
        return !(max > min);
    }

    /**
     * 保证跨度不小于 {@link #MIN_SPAN}：以原范围的中心为心向两侧扩。
     *
     * @return 跨度合法的范围；本来合法时返回自身
     */
    public AxisRange withMinimumSpan() {
        if (max - min >= MIN_SPAN) {
            return this;
        }
        double center = (min + max) / 2.0;
        return new AxisRange(center - MIN_SPAN / 2.0, center + MIN_SPAN / 2.0, name, unit);
    }

    /**
     * 对数轴的合法范围：{@code min > 0} 且 {@code max > min}。
     *
     * <p>原来就合法时返回自身；{@code min ≤ 0} 但 {@code max > 0} 时把 min 钳到
     * {@code max / 1000}（三个数量级的下限，够看得见）；整段都 ≤ 0 时给一个
     * {@code [1, 10]} 的占位范围。
     *
     * <p><strong>宁可显示一条空轴，也不产生 NaN</strong>：NaN 会顺着顶点缓冲毁掉
     * 同一批次里别的图元，而空轴只是这一条曲线看不见——前者不可观测且波及无辜，
     * 后者一眼就能看出是数据的问题。
     *
     * @return 对数轴可用的范围
     */
    public AxisRange withPositiveMin() {
        if (min > 0 && max > min) {
            return this;
        }
        if (max > 0) {
            return new AxisRange(max / 1000.0, max, name, unit);
        }
        return new AxisRange(1.0, 10.0, name, unit);
    }
}
```

- [ ] **Step 3: 写 ChartData**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/ChartData.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 一维或多维数据的只读视图，供渲染器消费。
 *
 * <h2>随机访问仍然在，但"追加"才是主角</h2>
 * <p>{@link #value(int, int)} 保留了随机访问：静态数据靠它，流式数据的可见窗口也靠它。
 * 但真正让高性能成立的是 {@link #revision()} 与 {@link #dirtyRange(long)} 这一对——
 * 它们回答的是"<strong>渲染器怎么知道哪些数据是新的</strong>"。
 *
 * <h2>索引空间</h2>
 * <p>{@code index ∈ [0, itemCount())}，是<strong>相对于当前可见窗口</strong>的下标。
 * 流式实现里窗口会滑动，因此同一个下标在不同时刻对应的样本可能不同——
 * 渲染器要判断"我看到的东西还在不在"，必须用 {@link #dirtyRange(long)} 而不是自己缓存下标。
 *
 * <h2>线程</h2>
 * <p>实现可能被采集线程写、GL 线程读（见 {@link RingChartData}）。
 * 本接口不承诺线程安全，<strong>每个实现各自在类文档里写清自己的线程约定</strong>。
 */
public interface ChartData {

    /**
     * 返回某个维度的范围与名称单位。
     *
     * @param dim 维度下标，从 0 开始
     * @return 该维度的范围
     */
    AxisRange axisRange(int dim);

    /**
     * 返回当前可见的样本数。
     *
     * <p>流式实现里这个值<strong>不会超过环形缓冲的容量</strong>：更早的样本已经被覆盖，
     * 看不见了。
     *
     * @return 可见样本数
     */
    int itemCount();

    /**
     * 读一个样本。
     *
     * @param dim   维度下标
     * @param index 可见窗口内的下标，{@code [0, itemCount())}
     * @return 样本值
     */
    double value(int dim, int index);

    /**
     * 返回当前修订号：每次内容变更递增。
     *
     * <p>渲染器把它当作"我上次看的是哪一版"的凭据，传给 {@link #dirtyRange(long)}。
     *
     * @return 修订号
     */
    long revision();

    /**
     * 返回自 {@code sinceRevision} 以来变了的那一段。
     *
     * @param sinceRevision 渲染器上次看到的修订号（{@link #revision()} 的旧值）
     * @return 脏区间；无变化时返回 {@link DirtyRange#EMPTY}
     */
    DirtyRange dirtyRange(long sinceRevision);
}
```

- [ ] **Step 4: 编译**

Run: `mvn -o compile`
Expected: BUILD SUCCESS（此时还没有任何测试引用它们，编译通过即可）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/DirtyRange.java \
        src/main/java/com/bingbaihanji/jfgl/chart/AxisRange.java \
        src/main/java/com/bingbaihanji/jfgl/chart/ChartData.java
git commit -F - <<'EOF'
feat(chart): 图表框架的三个基础类型

DirtyRange 是 ① 相对 chart-fx 的唯一实质增强：它的脏位是图表级的全局标志，
只说明"要重画"、没说明"重画哪一段"，所以渲染器每帧仍要把二分查窗口、重烘
坐标、重做缩减全走一遍。脏区间作为一等公民才能让"GPU 常驻 + 增量上传"成立。

AxisRange 把范围放在数据侧（抄 chart-fx）：轴只是显示窗口，不去遍历数据；
同一个范围能被多个轴共享，多 Y 轴因此是自然结果而不是特例。

这个类里有两处稳定化，是全项目唯一的一份：withMinimumSpan（退化范围扩成最小
可视跨度，规格 §10）与 withPositiveMin（对数轴上 ≤0 的值钳到正下限）。
两处都刻意"宁可显示一条空轴，也不产生 NaN"——NaN 进入顶点缓冲后不报错，
只是整条曲线连同同批次别的图元一起消失。

纯数据载体，本任务不写测试：给 record 写"构造器会赋值"的断言是橡皮图章，
而上面两处辅助方法由 Task 2 / Task 3 的测试间接覆盖。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 2: AxisType / Tick / TickGenerator

这一块是**纯算术，零 GL 依赖**，是本规格里最适合写单测的部分之一（规格 §6）。

**每条设计约束都来自规格或参考项目的实测教训：**
- **用 `double` 算术，不用 `BigDecimal`**：fxcharts 的 `Axis.java:1399` 用 `BigDecimal` 算刻度，
  高刷新率下会成为热点，而且没必要（规格 §6 点名）。
- **nicenumber 步长只取 1/2/5 × 10ⁿ**：这是刻度看起来"整齐"的全部秘密。
- **标签格式必须显式指定 `Locale.ROOT`**：某些区域用逗号做小数点，
  不指定的话同一段代码在不同区域给出 `"0,5"`，而测试只会在别人的机器上红。
- **时间标签必须显式用 UTC**：同上，理由见计划开头的取舍 5。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/AxisType.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/Tick.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/TickGenerator.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/TickGeneratorTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/TickGeneratorTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TickGenerator} 的单测：**纯算术，零依赖**。
 *
 * <p>刻度错了不会报错，只会让坐标轴上的数字看起来"莫名其妙"——间距不整齐、
 * 数不出格数、或者标签是 3.0000000000000004。这些都得靠断言钉住。
 */
class TickGeneratorTest {

    /** 测试用的"映射"：位置就等于值本身。用它断言"位置确实来自映射"。 */
    private static final DoubleUnaryOperator IDENTITY = v -> v;

    /** 把像素长度的映射当成线性轴用：position = (v - min) / span * length。 */
    private static DoubleUnaryOperator linearMap(AxisRange range, double length) {
        return v -> (v - range.min()) / range.span() * length;
    }

    private static List<Tick> ofLevel(Tick[] ticks, int level) {
        List<Tick> out = new ArrayList<>();
        for (Tick t : ticks) {
            if (t.level() == level) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 某一级刻度的步长 = "该级与更粗级别的值"的最小间距。
     *
     * <p><strong>不能直接取"该级列表里相邻两项之差"</strong>：一个值只发射一次
     * （取最粗的级别），所以中刻度的列表里缺了那些同时也是主刻度的值，
     * 相邻两项之间会隔着整整 2 倍步长。取并集之后，最小间距才是这一级真正的格宽。
     */
    private static double stepOf(Tick[] ticks, int level) {
        List<Double> values = new ArrayList<>();
        for (Tick t : ticks) {
            if (t.level() <= level) {
                values.add(t.value());
            }
        }
        java.util.Collections.sort(values);
        assertTrue(values.size() >= 2, "到第 " + level + " 级为止只有 " + values.size()
                + " 个刻度，数不出步长——换一个范围再测");
        double min = Double.MAX_VALUE;
        for (int i = 1; i < values.size(); i++) {
            min = Math.min(min, values.get(i) - values.get(i - 1));
        }
        return min;
    }

    private static boolean isMultipleOf(double value, double step) {
        double k = value / step;
        return Math.abs(k - Math.rint(k)) < 1e-6;
    }

    private static String majorStepKind(double step) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.abs(step))));
        double normalized = step / magnitude;
        return normalized + "e" + (int) Math.floor(Math.log10(magnitude));
    }

    @Test
    void 主刻度步长只取1_2_5的十进制倍数() {
        double[] spans = {0.3, 1.0, 7.0, 42.0, 100.0, 1234.0, 98765.0};
        for (double span : spans) {
            AxisRange range = AxisRange.of(0, span);
            Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, IDENTITY);
            double step = stepOf(ticks, Tick.MAJOR);
            double magnitude = Math.pow(10, Math.floor(Math.log10(step)));
            double normalized = Math.round(step / magnitude * 1e6) / 1e6;
            assertTrue(normalized == 1.0 || normalized == 2.0 || normalized == 5.0,
                    "跨度 " + span + " 的主刻度步长是 " + step + "（归一化后 " + normalized
                            + "），不是 1/2/5 × 10ⁿ"); 
        }
    }

    @Test
    void 八百像素的0到100得到步长10() {
        AxisRange range = AxisRange.of(0, 100);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, IDENTITY);

        // 800 / 80 = 10 格，跨度 100 → 步长 10
        assertEquals(10.0, stepOf(ticks, Tick.MAJOR), 1e-9);
        List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
        assertEquals(11, majors.size(), "0..100 步长 10 应当有 11 个刻度");
        assertEquals(0.0, majors.get(0).value(), 1e-9);
        assertEquals(100.0, majors.get(10).value(), 1e-9);
        assertEquals("0", majors.get(0).label());
        assertEquals("100", majors.get(10).label(), "末刻度的值不能被浮点尾数污染成 100.00000000000001");
    }

    @Test
    void 刻度位置由映射函数给出() {
        AxisRange range = AxisRange.of(0, 100);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, linearMap(range, 800));
        for (Tick t : ticks) {
            assertEquals(t.value() * 8.0, t.position(), 1e-6,
                    "位置必须来自传入的映射函数，不能由生成器自己算一遍——两份换算迟早会不一致");
        }
    }

    @Test
    void 所有刻度都落在声明范围之内() {
        AxisRange range = AxisRange.of(-3.7, 12.4);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 640, IDENTITY);
        assertTrue(ticks.length > 0, "这个范围应当能生成刻度");
        for (Tick t : ticks) {
            assertTrue(t.value() >= range.min() - 1e-9 && t.value() <= range.max() + 1e-9,
                    "刻度 " + t.value() + " 跑到范围 " + range.min() + ".." + range.max() + " 外面去了");
        }
    }

    @Test
    void 主中次三级的格层层包含() {
        AxisRange range = AxisRange.of(0, 100);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, IDENTITY);
        double major = stepOf(ticks, Tick.MAJOR);
        double medium = stepOf(ticks, Tick.MEDIUM);
        double minor = stepOf(ticks, Tick.MINOR);

        // 注意：一个值只发射一次（取最粗的级别），所以"主刻度必然也是中刻度"这句话
        // 不能指望在中刻度列表里找到重复项。包含关系体现在**格**上：
        // 主刻度的每一个值都落在中刻度的格上，中刻度的每一个值都落在次刻度的格上。
        // 这就是这个表示法的全部含义。
        assertEquals(major / 2.0, medium, 1e-9, "中刻度步长必须是主刻度的一半");
        assertEquals(major / 4.0, minor, 1e-9, "次刻度步长必须是主刻度的四分之一");
        for (Tick t : ofLevel(ticks, Tick.MAJOR)) {
            assertTrue(isMultipleOf(t.value(), medium), t.value() + " 不在中刻度的格上");
            assertTrue(isMultipleOf(t.value(), minor), t.value() + " 不在次刻度的格上");
        }
        for (Tick t : ofLevel(ticks, Tick.MEDIUM)) {
            assertTrue(isMultipleOf(t.value(), minor), t.value() + " 不在次刻度的格上");
        }
        // 反证：中次两级必须真的存在，否则上面两个循环一个都不跑，断言恒真
        assertFalse(ofLevel(ticks, Tick.MEDIUM).isEmpty(), "中刻度一个都没有");
        assertFalse(ofLevel(ticks, Tick.MINOR).isEmpty(), "次刻度一个都没有");
    }

    @Test
    void 退化范围不产生NaN且有刻度() {
        AxisRange range = AxisRange.of(5.0, 5.0);
        // 映射必须按**稳定化之后**的窗口来构造——这正是 Axis 的做法（它在构造时就把窗口
        // 稳定化了，ticks() 传给生成器的也是稳定化之后的窗口）。用未稳定化的范围自己
        // 造一个映射会在这里除零，那是测试的错，不是生成器的错。
        AxisRange stable = range.withMinimumSpan();
        Tick[] ticks = assertDoesNotThrow(
                () -> TickGenerator.generate(AxisType.LINEAR, range, 800, linearMap(stable, 800)),
                "退化范围不能抛异常");
        assertTrue(ticks.length > 0, "退化范围也应当有刻度：跨度被扩成最小可视跨度之后就该有");
        for (Tick t : ticks) {
            assertFalse(Double.isNaN(t.value()), "退化范围产生了 NaN 刻度值");
            assertFalse(Double.isNaN(t.position()), "退化范围产生了 NaN 刻度位置");
        }
    }

    @Test
    void 对数轴的主刻度是10的整数次幂() {
        AxisRange range = AxisRange.of(1.0, 1000.0);
        Tick[] ticks = TickGenerator.generate(AxisType.LOGARITHMIC, range, 800, IDENTITY);
        List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
        assertEquals(4, majors.size(), "1..1000 有 10⁰/10¹/10²/10³ 四个数量级");
        double[] expected = {1, 10, 100, 1000};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], majors.get(i).value(), 1e-9 * expected[i],
                    "第 " + i + " 个主刻度不是 10 的整数次幂");
        }
        assertEquals("1", majors.get(0).label());
        assertEquals("10", majors.get(1).label());
        assertEquals("100", majors.get(2).label());
    }

    @Test
    void 对数轴上出现非正值时钳到下限且不产生NaN() {
        AxisRange range = AxisRange.of(0.0, 100.0);
        Tick[] ticks = assertDoesNotThrow(
                () -> TickGenerator.generate(AxisType.LOGARITHMIC, range, 800,
                        linearMap(range.withPositiveMin(), 800)),
                "对数轴遇到 0 不能抛异常，也不能返回 NaN");
        assertTrue(ticks.length > 0, "钳位之后仍然要有刻度");
        for (Tick t : ticks) {
            assertTrue(t.value() > 0, "对数轴上出现了非正的刻度值：" + t.value());
            assertFalse(Double.isNaN(t.position()), "对数轴产生了 NaN 位置");
        }

        // 整段都 ≤ 0：给一个占位范围，同样一个 NaN 都不能有
        Tick[] allNonPositive = assertDoesNotThrow(
                () -> TickGenerator.generate(AxisType.LOGARITHMIC, AxisRange.of(-5, -1), 800, IDENTITY));
        for (Tick t : allNonPositive) {
            assertTrue(t.value() > 0, "整段非正时应当退回占位范围 [1,10]，实际给出 " + t.value());
        }
    }

    @Test
    void 对数轴跨度不足一个数量级时退化成线性刻度() {
        AxisRange range = AxisRange.of(1.2, 1.8);
        Tick[] ticks = TickGenerator.generate(AxisType.LOGARITHMIC, range, 800, IDENTITY);
        assertTrue(ticks.length > 0,
                "1.2..1.8 里一个 10 的整数次幂都没有，但退化成线性刻度之后必须有刻度，否则是一条空轴");
        for (Tick t : ticks) {
            assertTrue(t.value() >= 1.2 - 1e-9 && t.value() <= 1.8 + 1e-9,
                    "退化后的刻度 " + t.value() + " 跑到范围外面去了");
        }
    }

    @Test
    void 时间轴按量级切换标签格式() {
        // 2026-09-20T12:34:56Z
        double base = 1789907696.0;
        String[][] cases = {
                // {"跨度（秒）", "期望的标签形状", "为什么是这个形状"}
                {"30", "\\d{2}:\\d{2}:\\d{2}", "800 像素 10 格 → 步长 5 秒 → HH:mm:ss"},
                {"600", "\\d{2}:\\d{2}", "步长 60 秒 → HH:mm"},
                {"2592000", "\\d{2}-\\d{2}", "步长 7 天 → MM-dd"},
                {"63072000", "\\d{4}-\\d{2}", "步长 365 天 → yyyy-MM"},
        };
        for (String[] c : cases) {
            double span = Double.parseDouble(c[0]);
            AxisRange range = AxisRange.of(base, base + span);
            Tick[] ticks = TickGenerator.generate(AxisType.TIME, range, 800, IDENTITY);
            List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
            assertFalse(majors.isEmpty(), "跨度 " + span + " 秒一个刻度都没有");
            for (Tick t : majors) {
                assertTrue(t.label().matches(c[1]),
                        "跨度 " + span + " 秒（" + c[2] + "）的标签格式不对：" + t.label()
                                + "，期望匹配 " + c[1]);
            }
        }
    }

    @Test
    void 时间轴标签用UTC而不是系统时区() {
        // 2026-09-20T12:34:56Z 起 30 秒：步长是 5 秒，所以标签是 HH:mm:ss 级别，
        // 秒数必须精确对得上——时区一偏，秒也会跟着偏（偏移量未必是整分钟）
        double base = 1789907696.0;
        AxisRange range = AxisRange.of(base, base + 30);
        Tick[] ticks = TickGenerator.generate(AxisType.TIME, range, 800, IDENTITY);
        List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
        assertFalse(majors.isEmpty(), "30 秒跨度一个刻度都没有");

        DateTimeFormatter utc = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                .withZone(ZoneOffset.UTC);
        for (Tick t : majors) {
            String expected = utc.format(Instant.ofEpochSecond((long) t.value()));
            assertEquals(expected, t.label(),
                    "标签必须按 UTC 格式化。按系统时区格式化的话，同一段代码在两台机器上"
                            + "给出不同标签——测试只会在别人的机器上红");
        }
    }

    @Test
    void 文本轴在像素不够时按步长抽稀() {
        // 100 个类目只有 160 像素宽：80 像素一个标签的话最多放 2 个
        AxisRange range = AxisRange.of(0, 99);
        Tick[] ticks = TickGenerator.generate(AxisType.TEXT, range, 160, IDENTITY);
        assertTrue(ticks.length <= 3,
                "160 像素宽放不下 " + ticks.length + " 个类目标签，抽稀的步长没起作用");
        assertTrue(ticks.length >= 2, "抽稀过头了：" + ticks.length + " 个刻度");
        for (Tick t : ticks) {
            assertEquals(Math.rint(t.value()), t.value(), 1e-9, "类目刻度必须落在整数下标上");
            assertEquals(String.valueOf((long) t.value()), t.label(),
                    "类目轴的标签是下标本身；类目名归数据侧，① 不知道它");
        }
        // 像素足够时不该抽稀
        Tick[] all = TickGenerator.generate(AxisType.TEXT, AxisRange.of(0, 4), 800, IDENTITY);
        assertEquals(5, all.length, "800 像素放下 5 个类目，不该抽稀");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=TickGeneratorTest`
Expected: 编译失败 — `找不到符号: 类 AxisType`（以及 `Tick`、`TickGenerator`）

- [ ] **Step 3: 写 AxisType**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/AxisType.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 轴的类型：决定"数据值 → 显示位置"的换算方式与刻度的生成方式。
 *
 * <p>本期只有这四种，且<strong>刻意不做</strong>其它非线性轴（规格 §2）。
 *
 * <p>四种类型的共同点是：它们都是<strong>一维的、单调的</strong>换算。
 * 非单调的轴（圆坐标、极坐标）不属于 2D 直角坐标系，不在范围内。
 */
public enum AxisType {

    /**
     * 线性轴：等距的值映射成等距的像素。
     */
    LINEAR,

    /**
     * 对数轴：等<strong>比值</strong>的值映射成等距的像素。
     *
     * <p>刻度按 10 的整数次幂排布（一个数量级之内再按 2/5 与 1..9 细分）。
     * 数据里出现 ≤ 0 的值时由 {@link AxisRange#withPositiveMin()} 钳到正下限——
     * 对数轴上没有 0 与负数，硬算会得到 NaN 或 -Infinity。
     */
    LOGARITHMIC,

    /**
     * 时间轴：值是 <strong>Unix 纪元秒</strong>（UTC）。
     *
     * <p>换算与线性轴相同（时间本身是线性的），区别只在刻度要落在"整秒/整分/整时/整天"
     * 这些人类时刻上，标签也要按量级切换格式。
     *
     * <p>标签一律按 <strong>UTC</strong> 格式化，理由见实施计划开头的取舍 5：
     * 读系统时区会让同一段代码在不同机器上给出不同结果。
     */
    TIME,

    /**
     * 类目轴：值是<strong>类目下标</strong>（0, 1, 2, …）。
     *
     * <p>类目名不在范围里——{@link AxisRange} 只有 min/max/name/unit，装不下一张类目表。
     * 刻度标签因此是下标本身的十进制字符串，真正的类目名由渲染后端（子项目 ②）
     * 拿下标去数据里查。这是刻意的：① 不该为了一个标签去持有一份数据副本。
     */
    TEXT
}
```

- [ ] **Step 4: 写 Tick**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/Tick.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 一个刻度：值 + 位置 + 标签 + 级别。
 *
 * <h2>位置是"显示位置"，由映射函数算出来</h2>
 * <p>{@code position} 的单位由调用方给的映射决定：{@link Axis} 传进去的是它自己的
 * {@code dataToDisplay}，于是 position 就是像素（或者说，是这个轴上的一维位置）。
 * <strong>刻度生成器不自己算映射</strong>——那会变成第二份"值 → 位置"的实现，
 * 两边一旦不同步，症状是"刻度线与网格/数据对不上"，而两边各自都"是对的"。
 *
 * @param value    数据值
 * @param position 显示位置（由调用方给的映射函数算出）
 * @param label    标签；中/次刻度是空串（不是 null）
 * @param level    级别：{@link #MAJOR} / {@link #MEDIUM} / {@link #MINOR}
 */
public record Tick(double value, double position, String label, int level) {

    /** 主刻度：最长的那根，也是唯一带标签的。 */
    public static final int MAJOR = 0;

    /** 中刻度。 */
    public static final int MEDIUM = 1;

    /** 次刻度：最短的那根。 */
    public static final int MINOR = 2;

    public Tick {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("刻度值必须是有限数：" + value);
        }
        if (!Double.isFinite(position)) {
            throw new IllegalArgumentException("刻度位置必须是有限数：" + position);
        }
        if (label == null) {
            throw new IllegalArgumentException("标签不能为 null（没有标签就传空串）");
        }
        if (level < MAJOR || level > MINOR) {
            throw new IllegalArgumentException("级别只能是 0/1/2，实际为 " + level);
        }
    }

    /** 是否为主刻度。 */
    public boolean isMajor() {
        return level == MAJOR;
    }
}
```

- [ ] **Step 5: 写 TickGenerator**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/TickGenerator.java`：

```java
package com.bingbaihanji.jfgl.chart;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * nice number 刻度生成：给一个数据范围与像素长度，给出主/中/次三级刻度。
 *
 * <h2>全是 double 算术</h2>
 * <p>规格 §6 点名 fxcharts 的 {@code Axis.java:1399} 用 {@code BigDecimal} 算刻度：
 * 高刷新率下它是热点，而且没必要。刻度是给眼睛看的，6 位有效数字足够了。
 *
 * <h2>三级刻度的包含关系是"格"层面的</h2>
 * <p>步长取 {@code step / step/2 / step/4}（取 /4 而不是 /5，因为 step/2 的格未必包含
 * step 的格：5 与 2.5 就是反例）。<strong>一个值只发射一次，取最粗的级别</strong>，
 * 所以主刻度的值不会同时出现在中刻度的列表里——包含关系体现在"主刻度的值都落在
 * 中刻度的格上"，而不是"列表里有重复项"。
 *
 * <h2>标签只有主刻度有</h2>
 * <p>中/次刻度是给眼睛看密度的，都写标签会糊成一片。需要更密的标签时，
 * 调用方把 {@code pixelLength} 报小一点即可（{@link #TARGET_SPACING_PX} 是固定的）。
 *
 * <p>纯函数、零依赖、无任何 GL 调用，因此可以彻底单测。
 */
public final class TickGenerator {

    /** 主刻度之间期望的像素间距。像素长度除以它，就是期望的刻度格数。 */
    public static final double TARGET_SPACING_PX = 80.0;

    /** 一道硬上限：防止边界情形下（步长与范围不匹配）产出爆炸数量的刻度。 */
    private static final int MAX_TICKS = 4096;

    /** 浮点比较用的相对容差。 */
    private static final double EPS = 1e-9;

    /**
     * 时间轴的候选步长（秒），从 1 秒到 10 年。
     *
     * <p>月按 30 天、年按 365 天<strong>近似</strong>：图表的刻度不需要日历精度。
     * 真要"每月 1 号"这种日历对齐，需要另一套逻辑（按日历字段而不是秒数递推），
     * 本期不做——而末尾那两个"年"量级的步长是必须有的，
     * 否则 {@link #FORMAT_YEAR} 那条分支永远不可达（跨度再大也只会走到 30 天）。
     */
    private static final double[] TIME_STEPS_SECONDS = {
            1, 2, 5, 10, 15, 30,
            60, 120, 300, 600, 900, 1800,
            3600, 7200, 10800, 21600, 43200,
            86400, 172800, 604800, 1209600, 2592000,
            31536000, 315360000
    };

    private static final DateTimeFormatter FORMAT_SECOND =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FORMAT_MINUTE =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FORMAT_DAY =
            DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FORMAT_YEAR =
            DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT).withZone(ZoneOffset.UTC);

    private TickGenerator() {
    }

    /**
     * 生成三级刻度。
     *
     * @param type           轴类型
     * @param range          数据范围（退化与非正范围在这里被稳定化）
     * @param pixelLength    轴在屏幕上的像素长度；非有限或 ≤1 时按 1 处理
     * @param dataToDisplay  值 → 位置的映射，通常就是 {@link Axis#dataToDisplay(double)}
     * @return 按值升序排列的刻度；同一个值只出现一次（取最粗的级别）
     */
    public static Tick[] generate(AxisType type, AxisRange range, double pixelLength,
                                  DoubleUnaryOperator dataToDisplay) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }
        if (range == null) {
            throw new IllegalArgumentException("range 不能为 null");
        }
        if (dataToDisplay == null) {
            throw new IllegalArgumentException("dataToDisplay 不能为 null");
        }
        // Math.max(1.0, NaN) 是 NaN，得先挡掉非有限值，否则整条链路都会变成 NaN
        double length = (Double.isFinite(pixelLength) && pixelLength > 1.0) ? pixelLength : 1.0;
        return switch (type) {
            case LINEAR -> linear(range.withMinimumSpan(), length, dataToDisplay);
            case LOGARITHMIC -> logarithmic(range.withPositiveMin(), length, dataToDisplay);
            case TIME -> time(range.withMinimumSpan(), length, dataToDisplay);
            case TEXT -> text(range.withMinimumSpan(), length, dataToDisplay);
        };
    }

    /**
     * 主刻度步长：1/2/5 × 10ⁿ 里最接近"每 {@link #TARGET_SPACING_PX} 像素一格"的那个。
     *
     * <p>公开是为了让渲染后端选网格间距时用<strong>同一个函数</strong>——
     * 网格与刻度各自算一遍步长，迟早会错开半格。
     *
     * @param span        数据跨度；非法或 ≤0 时返回 1
     * @param pixelLength 像素长度
     * @return 步长
     */
    public static double niceStep(double span, double pixelLength) {
        if (!(span > 0) || !Double.isFinite(span)) {
            return 1.0;
        }
        double length = (Double.isFinite(pixelLength) && pixelLength > 0) ? pixelLength : 1.0;
        double cells = Math.max(1.0, length / TARGET_SPACING_PX);
        double rough = span / cells;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        double normalized = rough / magnitude;
        double nice = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
        return nice * magnitude;
    }

    private static Tick[] linear(AxisRange range, double length, DoubleUnaryOperator map) {
        double step = niceStep(range.span(), length);
        List<Tick> out = new ArrayList<>();
        // 主/中/次 = step / step/2 / step/4。取 2 与 4（不是 2 与 5）是为了让包含关系
        // 在构造上成立：step/4 的格必然包含 step/2 的格，也必然包含 step 的格。
        addGrid(out, range, step, Tick.MAJOR, map);
        addGrid(out, range, step / 2.0, Tick.MEDIUM, map);
        addGrid(out, range, step / 4.0, Tick.MINOR, map);
        return finish(out);
    }

    private static void addGrid(List<Tick> out, AxisRange range, double step, int level,
                                DoubleUnaryOperator map) {
        if (!(step > 0) || !Double.isFinite(step)) {
            return;
        }
        double tolerance = EPS * Math.max(1.0, Math.abs(step));
        // 减 EPS 再向上取整：不减的话，min/step 恰好是整数时浮点误差会把它推到 2.0000000000000004，
        // ceil 之后变成 3，于是范围起点上的那个刻度凭空消失。
        long first = (long) Math.ceil(range.min() / step - EPS);
        for (long k = first, guard = 0; guard < MAX_TICKS; k++, guard++) {
            double value = clean(k * step, step);
            if (value > range.max() + tolerance) {
                return;
            }
            out.add(new Tick(value, map.applyAsDouble(value), label(value, level), level));
        }
    }

    private static Tick[] logarithmic(AxisRange range, double length, DoubleUnaryOperator map) {
        double logMin = Math.log10(range.min());
        double logMax = Math.log10(range.max());
        if (!(logMax - logMin >= 1.0)) {
            // 跨度不足一个数量级：10 的整数次幂里至多落进一个（常常一个都没有）。
            // 退化成同一区间上的线性 nice 刻度——定位仍然走对数映射，
            // 画面不至于是一条什么都没有的空轴。
            return linear(range, length, map);
        }
        List<Tick> out = new ArrayList<>();
        int firstExponent = (int) Math.ceil(logMin - EPS);
        int lastExponent = (int) Math.floor(logMax + EPS);
        int decadeStep = (int) Math.max(1.0, Math.rint(niceStep(logMax - logMin, length)));
        for (int exponent = firstExponent; exponent <= lastExponent; exponent += decadeStep) {
            addLogTick(out, range, exponent, 1, Tick.MAJOR, map);
        }
        if (decadeStep == 1) {
            // 一个数量级之内：1-2-5 是中刻度、1..9 是次刻度。
            // 包含关系天然成立（{1} ⊂ {1,2,5} ⊂ {1..9}）。
            int firstMantissaExponent = firstExponent - 1;
            for (int exponent = firstMantissaExponent; exponent <= lastExponent; exponent++) {
                addLogTick(out, range, exponent, 2, Tick.MEDIUM, map);
                addLogTick(out, range, exponent, 5, Tick.MEDIUM, map);
                for (int mantissa = 1; mantissa <= 9; mantissa++) {
                    addLogTick(out, range, exponent, mantissa, Tick.MINOR, map);
                }
            }
        }
        return finish(out);
    }

    private static void addLogTick(List<Tick> out, AxisRange range, int exponent, int mantissa,
                                   int level, DoubleUnaryOperator map) {
        double magnitude = Math.pow(10, exponent);
        double value = clean(mantissa * magnitude, magnitude);
        if (value < range.min() - EPS * Math.max(1.0, value)
                || value > range.max() + EPS * Math.max(1.0, value)) {
            return;
        }
        out.add(new Tick(value, map.applyAsDouble(value), label(value, level), level));
    }

    private static Tick[] time(AxisRange range, double length, DoubleUnaryOperator map) {
        double rough = range.span() / Math.max(1.0, length / TARGET_SPACING_PX);
        double step = TIME_STEPS_SECONDS[TIME_STEPS_SECONDS.length - 1];
        for (double candidate : TIME_STEPS_SECONDS) {
            if (candidate >= rough) {
                step = candidate;
                break;
            }
        }
        // 时间步长不总能被 4 整除（15 秒的四分之一是 3.75 秒，不是个"整齐"的时刻）。
        // 能整除时才细分：不能整除时三级用同一个步长，包含关系因此永远成立。
        double mediumStep = (step % 4.0 == 0.0) ? step / 2.0 : step;
        double minorStep = (step % 4.0 == 0.0) ? step / 4.0 : step;
        List<Tick> out = new ArrayList<>();
        addTimeGrid(out, range, step, Tick.MAJOR, map);
        addTimeGrid(out, range, mediumStep, Tick.MEDIUM, map);
        addTimeGrid(out, range, minorStep, Tick.MINOR, map);
        return finish(out);
    }

    private static void addTimeGrid(List<Tick> out, AxisRange range, double step, int level,
                                    DoubleUnaryOperator map) {
        if (!(step > 0)) {
            return;
        }
        long first = (long) Math.ceil(range.min() / step - EPS);
        for (long k = first, guard = 0; guard < MAX_TICKS; k++, guard++) {
            // 整秒的乘加在 double 里是精确的（这些数远小于 2⁵³），不会有二进制尾数
            double value = k * step;
            if (value > range.max()) {
                return;
            }
            out.add(new Tick(value, map.applyAsDouble(value), timeLabel(value, step, level), level));
        }
    }

    private static Tick[] text(AxisRange range, double length, DoubleUnaryOperator map) {
        long first = (long) Math.ceil(range.min() - EPS);
        long last = (long) Math.floor(range.max() + EPS);
        long count = last - first + 1;
        if (count <= 0) {
            return new Tick[0];
        }
        int maxLabels = (int) Math.max(1.0, Math.floor(length / TARGET_SPACING_PX));
        int stride = (int) Math.max(1.0, Math.ceil((double) count / maxLabels));
        List<Tick> out = new ArrayList<>();
        for (long index = first; index <= last; index += stride) {
            double value = index;
            out.add(new Tick(value, map.applyAsDouble(value), Long.toString(index), Tick.MAJOR));
        }
        return out.toArray(new Tick[0]);
    }

    /**
     * 按值排序并去重：同一个值只留一个，取最粗的级别（排序已保证它排在最前）。
     */
    private static Tick[] finish(List<Tick> ticks) {
        ticks.sort(Comparator.comparingDouble(Tick::value).thenComparingInt(Tick::level));
        List<Tick> deduped = new ArrayList<>(ticks.size());
        boolean hasPrevious = false;
        double previous = 0;
        for (Tick tick : ticks) {
            if (hasPrevious && tick.value() == previous) {
                continue;
            }
            deduped.add(tick);
            previous = tick.value();
            hasPrevious = true;
        }
        return deduped.toArray(new Tick[0]);
    }

    private static String label(double value, int level) {
        return level == Tick.MAJOR ? formatValue(value) : "";
    }

    private static String timeLabel(double epochSeconds, double step, int level) {
        if (level != Tick.MAJOR) {
            return "";
        }
        Instant instant = Instant.ofEpochSecond((long) epochSeconds);
        if (step >= 86400.0 * 365.0) {
            return FORMAT_YEAR.format(instant);
        }
        if (step >= 86400.0) {
            return FORMAT_DAY.format(instant);
        }
        if (step >= 60.0) {
            return FORMAT_MINUTE.format(instant);
        }
        return FORMAT_SECOND.format(instant);
    }

    /**
     * 把数值打印成人类可读的短字符串。
     *
     * <p><strong>必须显式指定 {@link Locale#ROOT}</strong>：某些区域用逗号做小数点，
     * 不指定的话同一段代码会给出 {@code "0,5"}，而测试只会在别人的机器上红。
     */
    private static String formatValue(double value) {
        if (value == 0.0) {
            return "0";
        }
        double abs = Math.abs(value);
        if (abs >= 1e7 || abs < 1e-4) {
            return trim(String.format(Locale.ROOT, "%.4e", value));
        }
        return trim(String.format(Locale.ROOT, "%.6f", value));
    }

    /** 去掉尾部多余的 0 与孤立的小数点，科学计数法的指数部分原样保留。 */
    private static String trim(String text) {
        int exponentAt = text.indexOf('e');
        String mantissa = exponentAt < 0 ? text : text.substring(0, exponentAt);
        String exponent = exponentAt < 0 ? "" : text.substring(exponentAt);
        if (mantissa.indexOf('.') >= 0) {
            int end = mantissa.length();
            while (end > 0 && mantissa.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && mantissa.charAt(end - 1) == '.') {
                end--;
            }
            mantissa = mantissa.substring(0, end);
        }
        return mantissa + exponent;
    }

    /**
     * 抹掉浮点乘法的二进制尾数：{@code 3 * 0.1} 是 {@code 0.30000000000000004}，
     * 直接进标签就会打印成 {@code "0.30000000000000004"}。
     *
     * <p>做法是按 step 的量级定出"该保留几位小数"，走一次十进制字符串往返。
     * <strong>不能写成"乘一个 pow(10, k) 再除回来"</strong>——那会把误差原样带回来
     * （{@code 3.0 * 0.1} 依旧是 {@code 0.30000000000000004}）。
     *
     * @param value 待清理的值
     * @param step  该级刻度的步长，决定保留几位小数
     * @return 清理后的值
     */
    private static double clean(double value, double step) {
        if (!Double.isFinite(value) || value == 0.0 || !(Math.abs(step) > 0)) {
            return value;
        }
        double magnitude = Math.floor(Math.log10(Math.abs(step)));
        int decimals = (int) Math.max(0, Math.min(12, 6 - magnitude));
        return Double.parseDouble(String.format(Locale.ROOT, "%." + decimals + "f", value));
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -o test -Dtest=TickGeneratorTest`
Expected: `Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 7: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 217, Failures: 0, Errors: 0, Skipped: 2`（205 + 12）

- [ ] **Step 8: 变异验证**（每条做完立刻还原，共 6 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `niceStep` 里去掉 `5.0` 那个分支（只剩 1/2/10） | `主刻度步长只取1_2_5的十进制倍数` |
| 三级步长从 `step/2`、`step/4` 改成 `step/2`、`step/5` | `主中次三级的格层层包含`（次刻度的格不再包含中刻度：2.5 不在 0.5 的格上） |
| 线性轴不做 `withMinimumSpan()`（直接用退化范围，span = 0） | `退化范围不产生NaN且有刻度` |
| 对数轴不做 `withPositiveMin()`（min 取 0 甚至 -5） | `对数轴上出现非正值时钳到下限且不产生NaN` |
| 时间标签的 `FORMAT_*` 去掉 `.withZone(ZoneOffset.UTC)` | `时间轴标签用UTC而不是系统时区`。**如实说明**：本机时区是 UTC+8，一定失败；**若在 UTC 机器上跑，这条变异会存活**——那正是"时区依赖"这个缺陷的性质，不是断言写错了 |
| `text()` 里 `stride` 恒为 1（不抽稀） | `文本轴在像素不够时按步长抽稀` |

**若某条实测存活，先怀疑检查、再怀疑代码，然后如实上报**——本项目已经四次撞上「变异表本身写错」。

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/AxisType.java \
        src/main/java/com/bingbaihanji/jfgl/chart/Tick.java \
        src/main/java/com/bingbaihanji/jfgl/chart/TickGenerator.java \
        src/test/java/com/bingbaihanji/jfgl/chart/TickGeneratorTest.java
git commit -F - <<'EOF'
feat(chart): nice number 刻度生成器（轴、刻度、轴类型）

主刻度步长取 1/2/5 × 10ⁿ，三级刻度是 step、step/2、step/4。取 4 不取 5 是因为
包含关系必须在构造上成立：step/2 的格未必包含 step 的格（5 与 2.5 就是反例），
而 step/4 一定包含。一个值只发射一次、取最粗的级别，所以"主刻度必然也是中刻度"
体现在**格**上而不是列表里的重复项——测试的注释里写清了这个含义，免得后来者
按字面去列表里数重复项，然后以为断言没生效。

全程 double 算术，没有 BigDecimal：规格 §6 点名 fxcharts 的刻度计算用 BigDecimal，
高刷新率下是热点，而且没必要。

两处刻意的确定性约束，都写进了注释：
- 标签用 Locale.ROOT —— 有些区域用逗号做小数点，不指定的话测试只在别人机器上红；
- 时间标签用 UTC —— 同上，读系统时区是同一类错误。

退化范围与对数轴的非正值在这里都被稳定化（走 AxisRange 的那两个方法，全项目
唯一的一份实现），失败面是"整条曲线连同同批次别的图元一起消失"，所以有专门的断言。

12 条单测 + 6 条变异验证（1/2/5 分支、三级步长、退化范围、对数钳位、UTC、抽稀）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 3: Axis（显示窗口 + 双向映射 + 刻度装配）

规格 §6：**数据声明范围，轴是"显示窗口 + 换算器"**。`Axis` 不持有数据，只持有
`AxisRange` 与当前显示窗口。

`dataToDisplay(value)` / `displayToData(px)` 是一对**互逆**映射，是轴的全部职责。
互逆性必须被测试钉住——反了的话症状是"拖动/缩放时坐标与数据对不上"，
而这在静态画面里完全看不出来。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/Axis.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/AxisTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/AxisTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Axis} 的单测：**纯算术，零依赖**。
 *
 * <p>重点在<strong>互逆性</strong>与<strong>退化范围不除零</strong>。
 * 这两条错了都不会报错：映射反了在静态画面上看不出来（曲线还是画出来了，
 * 只是与坐标对不上），除零得到的 NaN 会让整条曲线连同同批次别的图元一起消失。
 */
class AxisTest {

    private static final double LENGTH = 800.0;

    private static Axis linear(AxisRange range) {
        return new Axis(AxisType.LINEAR, range).setDisplayLength(LENGTH);
    }

    private static void assertRoundTrip(Axis axis, double value) {
        double px = axis.dataToDisplay(value);
        double back = axis.displayToData(px);
        assertEquals(value, back, Math.max(1e-9, Math.abs(value) * 1e-9),
                value + " 经过一次往返变成了 " + back);
    }

    @Test
    void 线性映射把窗口两端映射到零和像素长度() {
        Axis axis = linear(AxisRange.of(10, 50));
        assertEquals(0.0, axis.dataToDisplay(10), 1e-9);
        assertEquals(LENGTH, axis.dataToDisplay(50), 1e-9);
        assertEquals(LENGTH / 2.0, axis.dataToDisplay(30), 1e-9);
    }

    @Test
    void 线性映射是互逆的() {
        Axis axis = linear(AxisRange.of(-3.5, 17.25));
        for (double v : new double[]{-3.5, -1, 0, 0.5, 7.125, 17.25}) {
            assertRoundTrip(axis, v);
        }
        // 反方向也要能往返
        for (double px : new double[]{0, 1, 123.456, 799.999, LENGTH}) {
            assertEquals(px, axis.dataToDisplay(axis.displayToData(px)), 1e-6,
                    px + " 经过一次往返变成了 " + axis.dataToDisplay(axis.displayToData(px)));
        }
    }

    @Test
    void 越出窗口的值仍然线性外推() {
        Axis axis = linear(AxisRange.of(0, 100));
        // 轴不做裁剪：裁剪是渲染侧 glScissor 的事。轴要是自己钳位，
        // 结果就是"曲线在边界上被压平"，比画出去更难查。
        assertEquals(-LENGTH / 10.0, axis.dataToDisplay(-10), 1e-9);
        assertEquals(LENGTH * 1.1, axis.dataToDisplay(110), 1e-9);
    }

    @Test
    void 窗口退化时不除零() {
        Axis axis = linear(AxisRange.of(5, 5));
        double px = assertDoesNotThrow(() -> axis.dataToDisplay(5));
        assertTrue(Double.isFinite(px), "退化范围的映射给出了 " + px);
        assertTrue(Double.isFinite(axis.displayToData(400)));
        assertRoundTrip(axis, 5);

        // 窗口被显式设成退化的值，同样不能除零
        Axis other = linear(AxisRange.of(0, 100)).setWindow(7, 7);
        assertTrue(Double.isFinite(other.dataToDisplay(7)));
        assertTrue(Double.isFinite(other.displayToData(1)));
    }

    @Test
    void 窗口写成反的时自动交换() {
        Axis axis = linear(AxisRange.of(0, 100)).setWindow(80, 20);
        assertEquals(20.0, axis.windowMin(), 1e-9);
        assertEquals(80.0, axis.windowMax(), 1e-9);
        assertEquals(0.0, axis.dataToDisplay(20), 1e-9);
        assertEquals(LENGTH, axis.dataToDisplay(80), 1e-9);
    }

    @Test
    void 对数轴把等比值映射成等距() {
        Axis axis = new Axis(AxisType.LOGARITHMIC, AxisRange.of(1, 1000)).setDisplayLength(LENGTH);
        assertEquals(0.0, axis.dataToDisplay(1), 1e-6);
        assertEquals(LENGTH / 3.0, axis.dataToDisplay(10), 1e-6);
        assertEquals(2 * LENGTH / 3.0, axis.dataToDisplay(100), 1e-6);
        assertEquals(LENGTH, axis.dataToDisplay(1000), 1e-6);
        assertRoundTrip(axis, 3.1622776601683795);
    }

    @Test
    void 对数轴上非正值被钳到下限且不产生NaN() {
        Axis axis = new Axis(AxisType.LOGARITHMIC, AxisRange.of(0, 100)).setDisplayLength(LENGTH);
        for (double v : new double[]{0, -1, -1000}) {
            double px = axis.dataToDisplay(v);
            assertTrue(Double.isFinite(px), "对数轴上 " + v + " 映射出了 " + px);
            assertEquals(0.0, px, 1e-6, "非正值应当钳到窗口下限");
        }
        assertTrue(axis.displayToData(0) > 0, "反查出来的值必须仍在对数轴的定义域里");
        assertTrue(Double.isFinite(axis.displayToData(LENGTH)));
    }

    @Test
    void 刻度装配后位置与映射一致() {
        Axis axis = linear(AxisRange.of(0, 100));
        Tick[] ticks = axis.ticks();
        assertTrue(ticks.length > 0, "0..100 应当有刻度");
        boolean sawMajor = false;
        for (Tick tick : ticks) {
            assertEquals(axis.dataToDisplay(tick.value()), tick.position(), 1e-6,
                    "刻度的位置必须由轴自己的映射算出——刻度生成器不许自己算一遍");
            assertTrue(tick.value() >= 0 && tick.value() <= 100);
            sawMajor |= tick.isMajor();
        }
        assertTrue(sawMajor, "一个主刻度都没有，断言等于空转");

        // 对数轴的刻度同样按映射装配
        Axis log = new Axis(AxisType.LOGARITHMIC, AxisRange.of(1, 1000)).setDisplayLength(LENGTH);
        List<Tick> majors = List.of(log.ticks()).stream().filter(Tick::isMajor).toList();
        assertEquals(4, majors.size());
        assertEquals(LENGTH / 3.0, majors.get(1).position(), 1e-6);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=AxisTest`
Expected: 编译失败 — `找不到符号: 类 Axis`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/Axis.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 一根轴：一个显示窗口，加一对互逆的换算。
 *
 * <h2>轴不持有数据</h2>
 * <p>这条抄 chart-fx：范围由数据自己声明（{@link ChartData#axisRange(int)}），
 * 轴只持有 {@link AxisRange}（用来取名字、单位、以及"回到数据自己的范围"）与
 * <strong>当前显示窗口</strong>。轴因此可以脱离数据存在，同一个范围也能被多个轴共享——
 * 多 Y 轴因此是自然结果而不是特例。
 *
 * <h2>窗口的稳定化在这个类的构造与 setWindow 里</h2>
 * <p>窗口起点与终点的差永远为正：退化时走 {@link AxisRange#withMinimumSpan()}，
 * 对数轴上走 {@link AxisRange#withPositiveMin()}。
 * <strong>这是"不除零"的唯一防线</strong>——换算里除以零得到的 NaN 会进入顶点缓冲，
 * 结果是整条曲线连同同批次别的图元一起消失，不报错、只是没了。
 *
 * <h2>轴不做裁剪</h2>
 * <p>窗口之外的值照样线性外推（对数轴按对数外推）。裁剪是渲染侧 {@code glScissor} 的事：
 * 轴要是自己钳位，症状是"曲线在边界上被压平"，比画出去更难查。
 *
 * <h2>线程</h2>
 * <p>非线程安全：显示窗口会被交互（拖动、缩放）改动，而那发生在 UI 线程。
 * 本期约定轴只在单一线程上使用（② 的渲染线程）。
 */
public final class Axis {

    private final AxisType type;

    /** 数据自己声明的范围（名字与单位的来源，也是"回到默认窗口"的目标）。 */
    private final AxisRange range;

    /** 当前显示窗口的下界。 */
    private double windowMin;

    /** 当前显示窗口的上界。 */
    private double windowMax;

    /** 轴的像素长度，决定映射的取值区间。 */
    private double displayLength = 1.0;

    /**
     * 构造一根轴，显示窗口就是数据自己声明的范围。
     *
     * @param type  轴类型
     * @param range 数据声明的范围
     * @throws IllegalArgumentException 任一参数为 null 时
     */
    public Axis(AxisType type, AxisRange range) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }
        if (range == null) {
            throw new IllegalArgumentException("range 不能为 null");
        }
        this.type = type;
        this.range = range;
        AxisRange normalized = normalize(type, range);
        this.windowMin = normalized.min();
        this.windowMax = normalized.max();
    }

    /** 轴类型。 */
    public AxisType type() {
        return type;
    }

    /** 数据声明的范围（不是当前窗口）。 */
    public AxisRange range() {
        return range;
    }

    /** 当前显示窗口的下界。 */
    public double windowMin() {
        return windowMin;
    }

    /** 当前显示窗口的上界。 */
    public double windowMax() {
        return windowMax;
    }

    /** 轴的像素长度。 */
    public double displayLength() {
        return displayLength;
    }

    /**
     * 设置轴的像素长度。它决定 {@link #dataToDisplay(double)} 的取值区间。
     *
     * @param px 像素长度，必须为正的有限数
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 非正或非有限时
     */
    public Axis setDisplayLength(double px) {
        if (!Double.isFinite(px) || px <= 0) {
            throw new IllegalArgumentException("轴的像素长度必须为正的有限数，实际为 " + px);
        }
        this.displayLength = px;
        return this;
    }

    /**
     * 设置显示窗口。
     *
     * <p>上下界写反时<strong>自动交换</strong>：调用方把 min/max 传反是常见笔误，
     * 交换比抛异常友好，而且不会像"照原样存下来"那样把整根轴的映射翻成镜像。
     *
     * @param min 窗口下界
     * @param max 窗口上界
     * @return 自身，便于链式调用
     */
    public Axis setWindow(double min, double max) {
        AxisRange normalized = normalize(type,
                new AxisRange(Math.min(min, max), Math.max(min, max), range.name(), range.unit()));
        this.windowMin = normalized.min();
        this.windowMax = normalized.max();
        return this;
    }

    /**
     * 把显示窗口恢复成数据自己声明的范围。
     *
     * @return 自身，便于链式调用
     */
    public Axis resetWindow() {
        return setWindow(range.min(), range.max());
    }

    /**
     * 数据值 → 显示位置（0 到 {@link #displayLength()}）。
     *
     * @param value 数据值；对数轴上 ≤0 的值钳到窗口下限
     * @return 显示位置
     */
    public double dataToDisplay(double value) {
        return fractionOf(value) * displayLength;
    }

    /**
     * 显示位置 → 数据值。与 {@link #dataToDisplay(double)} 互逆。
     *
     * @param px 显示位置；可以越出 {@code [0, displayLength]}（照样外推）
     * @return 数据值
     */
    public double displayToData(double px) {
        double fraction = px / displayLength;
        if (type == AxisType.LOGARITHMIC) {
            double logMin = Math.log10(windowMin);
            double logMax = Math.log10(windowMax);
            return Math.pow(10, logMin + fraction * (logMax - logMin));
        }
        return windowMin + fraction * (windowMax - windowMin);
    }

    /**
     * 生成这根轴当前窗口下的三级刻度，位置已按本轴的映射装配好。
     *
     * @return 按值升序的刻度
     */
    public Tick[] ticks() {
        return TickGenerator.generate(type, windowRange(), displayLength, this::dataToDisplay);
    }

    /** 把窗口包成一个 AxisRange，借它做稳定化并转交给刻度生成器。 */
    private AxisRange windowRange() {
        return new AxisRange(windowMin, windowMax, range.name(), range.unit());
    }

    /** 值 → {@code [0,1]} 的比例。窗口的稳定化保证了分母不为零。 */
    private double fractionOf(double value) {
        if (type == AxisType.LOGARITHMIC) {
            double logMin = Math.log10(windowMin);
            double logMax = Math.log10(windowMax);
            double logValue = value > 0 ? Math.log10(value) : logMin;
            return (logValue - logMin) / (logMax - logMin);
        }
        return (value - windowMin) / (windowMax - windowMin);
    }

    /**
     * 把范围稳定化成一根轴能用的窗口：对数轴钳到正下限，其余扩成最小可视跨度。
     *
     * <p>规格 §10 的两条"不除零/不产生 NaN"就落在这里，而且只有这一处。
     */
    private static AxisRange normalize(AxisType type, AxisRange range) {
        return type == AxisType.LOGARITHMIC ? range.withPositiveMin() : range.withMinimumSpan();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=AxisTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 225, Failures: 0, Errors: 0, Skipped: 2`（217 + 8）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 4 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `fractionOf` 的分母换成 `range.span()`（数据声明的范围，而不是稳定化后的窗口） | `窗口退化时不除零`（span 为 0 → NaN） |
| 对数轴的 `Math.log10` 换成线性（`fractionOf` 里直接走 else 分支） | `对数轴把等比值映射成等距` |
| `displayToData` 里的乘除写反（`windowMin + fraction / span`） | `线性映射是互逆的` |
| `setWindow` 不交换 min/max（直接存原值） | `窗口写成反的时自动交换`（映射会翻成镜像） |

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/Axis.java \
        src/test/java/com/bingbaihanji/jfgl/chart/AxisTest.java
git commit -F - <<'EOF'
feat(chart): Axis —— 显示窗口、双向映射与刻度装配

轴不持有数据（抄 chart-fx）：范围由数据自己声明，轴只管"显示窗口 + 换算器"。
于是轴能脱离数据存在，同一个范围也能被多个轴共享——多 Y 轴是自然结果而非特例。

窗口的稳定化只在这个类的构造与 setWindow 里，走 AxisRange 的那两个方法，
是全项目唯一的一份实现。这是"不除零"的唯一防线：换算里除以零得到的 NaN 进入
顶点缓冲后，整条曲线连同同批次别的图元一起消失，不报错、只是没了。

轴不做裁剪：窗口外的值照样外推，裁剪是渲染侧 glScissor 的事。轴要是自己钳位，
症状是"曲线在边界上被压平"，比画出去更难查。

setWindow 上下界写反时自动交换：传反是常见笔误，交换比抛异常友好，
而且不会像照原样存下来那样把整根轴的映射翻成镜像。

8 条单测（互逆性、边界、退化、对数轴）+ 4 条变异验证。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 4: ArrayChartData（静态实现）

一次性数据，`revision` 只在整体替换时递增。**这是静态科学绘图的路径**（规格 §5.3）。
`revision` 不变时直接报"无脏区"，渲染器一次上传后**永不重传**——这是 ① 的脏区间
设计要兑现的第一条承诺。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/ArrayChartData.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/ArrayChartDataTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/ArrayChartDataTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ArrayChartData} 的单测。
 *
 * <p>核心断言只有一条：<strong>revision 不变时报"无脏区"</strong>。
 * 它要是坏了，渲染器每帧都会把整份数据重传一遍——画面完全正常，只是白烧带宽，
 * 属于最典型的"没有报错但性能全丢"。
 */
class ArrayChartDataTest {

    private static AxisRange[] ranges(int dims) {
        AxisRange[] out = new AxisRange[dims];
        for (int d = 0; d < dims; d++) {
            out[d] = new AxisRange(0, 10, "维" + d, "u");
        }
        return out;
    }

    private static ArrayChartData data() {
        return new ArrayChartData(ranges(2), new double[][]{{1, 2, 3}, {10, 20, 30}});
    }

    @Test
    void 按维度与下标读回数据() {
        ArrayChartData data = data();
        assertEquals(3, data.itemCount());
        assertEquals(1, data.value(0, 0), 0);
        assertEquals(30, data.value(1, 2), 0);
        assertEquals("维1", data.axisRange(1).name());
        assertEquals("u", data.axisRange(0).unit());
    }

    @Test
    void revision不变时报无脏区() {
        ArrayChartData data = data();
        long revision = data.revision();
        assertTrue(data.dirtyRange(revision).isEmpty(),
                "revision 没变却报了脏区：渲染器会每帧重传整份数据，画面正常但白烧带宽");
        // 比当前 revision 更新的值也算"没有新增"（调用方可能拿到未来的号）
        assertTrue(data.dirtyRange(revision + 5).isEmpty());
    }

    @Test
    void 整体替换后报全区间为脏() {
        ArrayChartData data = data();
        long before = data.revision();
        data.replace(new double[][]{{4, 5, 6}, {40, 50, 60}});

        DirtyRange dirty = data.dirtyRange(before);
        assertFalse(dirty.isEmpty(), "替换之后必须有脏区，否则画面永远停在旧数据上");
        assertEquals(0, dirty.firstDirty());
        assertEquals(3, dirty.lastDirty(), "静态数据的替换是整体的，脏区间必须覆盖全部样本");
        assertEquals(5, data.value(0, 1), 0);
    }

    @Test
    void 替换后revision递增() {
        ArrayChartData data = data();
        long first = data.revision();
        data.replace(new double[][]{{7}, {70}});
        long second = data.revision();
        assertTrue(second > first, "替换必须递增 revision：" + first + " → " + second);
        data.replace(new double[][]{{8}, {80}});
        assertTrue(data.revision() > second);
    }

    @Test
    void 替换成不同长度的数据后脏区覆盖新长度() {
        ArrayChartData data = data();
        long before = data.revision();
        data.replace(new double[][]{{1, 2, 3, 4, 5}, {1, 2, 3, 4, 5}});

        assertEquals(5, data.itemCount());
        DirtyRange dirty = data.dirtyRange(before);
        assertEquals(5, dirty.lastDirty(),
                "脏区必须用**新**长度：用旧长度会让多出来的两个样本永远传不上去");
    }

    @Test
    void 各维度长度不一致时构造抛异常() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ArrayChartData(ranges(2), new double[][]{{1, 2, 3}, {1, 2}}));
        assertTrue(e.getMessage().contains("长度"), "消息要说清是长度不一致：" + e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new ArrayChartData(ranges(2), new double[][]{{1, 2, 3}}));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ArrayChartDataTest`
Expected: 编译失败 — `找不到符号: 类 ArrayChartData`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/ArrayChartData.java`：

```java
package com.bingbaihanji.jfgl.chart;

import java.util.Arrays;

/**
 * 静态数据：一次性给出、之后整体替换的实现。
 *
 * <h2>脏区间的语义</h2>
 * <p>{@code revision} <strong>只在 {@link #replace(double[][])} 时递增</strong>。
 * 于是：
 * <ul>
 *   <li>{@code revision} 没变 → {@link DirtyRange#EMPTY}，
 *       渲染器一次上传后<strong>永不重传</strong>。这是静态科学绘图的路径。</li>
 *   <li>整体替换 → 脏区间是 {@code (0, itemCount())}，即"整个窗口都变了"。
 *       静态数据的替换是整体语义，报一个"部分脏"反而会把新旧两段拼成一条假线。</li>
 * </ul>
 *
 * <h2>为什么是深拷贝</h2>
 * <p>构造与替换都拷贝一份。调用方在别处继续改自己那个数组、而这里悄悄跟着变，
 * 是这类容器最难查的一类 bug：数据"自己变了"，而 {@code revision} 没变，
 * 于是渲染器按契约不重传——画面停在旧数据上，怎么查都查不出。
 *
 * <h2>线程</h2>
 * <p>非线程安全。静态路径的约定是：构造/替换发生在数据变化时，绘制发生在 GL 线程。
 * 需要"边采边画"请用 {@link RingChartData}。
 */
public final class ArrayChartData implements ChartData {

    private final AxisRange[] ranges;

    /** {@code values[dim][index]}。 */
    private double[][] values;

    /** 每次整体替换递增。初值取 1，让"还什么都没看过"的渲染器（传 0 进来）也能拿到脏区。 */
    private long revision = 1;

    /**
     * 构造。
     *
     * @param ranges 各维度的范围，长度即维度数，至少 1 个
     * @param values {@code values[dim][index]}，各维度长度必须一致
     * @throws IllegalArgumentException 维度数为 0、参数为 null、或各维度长度不一致时
     */
    public ArrayChartData(AxisRange[] ranges, double[][] values) {
        if (ranges == null || ranges.length == 0) {
            throw new IllegalArgumentException("至少要声明一个维度");
        }
        for (int d = 0; d < ranges.length; d++) {
            if (ranges[d] == null) {
                throw new IllegalArgumentException("第 " + d + " 个维度的范围是 null");
            }
        }
        validate(values, ranges.length);
        this.ranges = ranges.clone();
        this.values = deepCopy(values);
    }

    /**
     * 整体替换数据。这是静态路径唯一的变更入口。
     *
     * @param newValues 新数据，各维度长度必须一致（可以与旧长度不同）
     * @throws IllegalArgumentException 维度数或长度不合法时
     */
    public void replace(double[][] newValues) {
        validate(newValues, ranges.length);
        this.values = deepCopy(newValues);
        this.revision++;
    }

    @Override
    public AxisRange axisRange(int dim) {
        if (dim < 0 || dim >= ranges.length) {
            throw new IndexOutOfBoundsException("维度下标越界：" + dim + "，共 " + ranges.length + " 维");
        }
        return ranges[dim];
    }

    @Override
    public int itemCount() {
        return values[0].length;
    }

    @Override
    public double value(int dim, int index) {
        axisRange(dim);
        return values[dim][index];
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public DirtyRange dirtyRange(long sinceRevision) {
        if (sinceRevision >= revision) {
            return DirtyRange.EMPTY;
        }
        return new DirtyRange(0, itemCount());
    }

    private static void validate(double[][] values, int dims) {
        if (values == null) {
            throw new IllegalArgumentException("values 不能为 null");
        }
        if (values.length != dims) {
            throw new IllegalArgumentException(
                    "值的维度数与声明不符：声明 " + dims + " 维，实际 " + values.length + " 维");
        }
        if (values[0] == null) {
            throw new IllegalArgumentException("第 0 维的数据是 null");
        }
        int count = values[0].length;
        for (int d = 1; d < values.length; d++) {
            if (values[d] == null) {
                throw new IllegalArgumentException("第 " + d + " 维的数据是 null");
            }
            if (values[d].length != count) {
                throw new IllegalArgumentException(
                        "各维度的长度必须一致：第 0 维是 " + count + "，第 " + d + " 维是 "
                                + values[d].length);
            }
        }
    }

    private static double[][] deepCopy(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int d = 0; d < source.length; d++) {
            copy[d] = Arrays.copyOf(source[d], source[d].length);
        }
        return copy;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=ArrayChartDataTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 231, Failures: 0, Errors: 0, Skipped: 2`（225 + 6）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 3 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `dirtyRange` 在 `sinceRevision` 相同时也报 `(0, itemCount())` | `revision不变时报无脏区` |
| `replace` 不递增 `revision` | `替换后revision递增`，且 `整体替换后报全区间为脏`（脏区永远是空的） |
| `dirtyRange` 的 `lastDirty` 用旧长度（缓存 `itemCount` 到替换之前） | `替换成不同长度的数据后脏区覆盖新长度` |

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/ArrayChartData.java \
        src/test/java/com/bingbaihanji/jfgl/chart/ArrayChartDataTest.java
git commit -F - <<'EOF'
feat(chart): ArrayChartData —— 静态数据实现

revision 只在整体替换时递增，于是 revision 不变时报"无脏区"，渲染器一次上传后
永不重传。这是静态科学绘图的路径，也是脏区间设计要兑现的第一条承诺：
它坏掉的话画面完全正常，只是每帧白传整份数据——没有报错、性能全丢。

整体替换报的脏区间是 (0, itemCount()) 而不是"部分脏"：静态数据的替换是整体语义，
报部分脏会把新旧两段拼成一条假线。脏区间的长度取**新**长度——用旧长度会让多出来
的样本永远传不上去。

构造与替换都做深拷贝：调用方继续改自己那个数组、而这里悄悄跟着变，是这类容器
最难查的一类 bug（数据自己变了而 revision 没变，渲染器按契约不重传，画面停在旧数据）。

6 条单测 + 3 条变异验证。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 5: RingChartData（★ 最重的一个）

规格 §5.4–5.6 的全部内容。这是整条实时路径的地基，**三处正确性要点每一条错了都不报错**。

### 类文档里必须写下的四件事（缺一不可）

**① 硬前提：只有一个写者。** 采集线程由使用方自己起，所以这个前提成立。
将来数据源若改成网络/串口回调（回调线程可能是 IO 线程池里的任意一个），
**单生产者前提不成立，整个无锁设计必须换掉**（多写者需要 CAS 或加锁）。

**② 先写数据、再递增索引。** `volatile` 写在 Java 里有 release 语义、读有 acquire 语义，
这个顺序保证读者看到索引时数据一定已可见。反过来写就是经典的半初始化读取。

**③ 索引用 `long` 不用 `int`。** 每秒几百万点，`int` 几十分钟就溢出，
**溢出后静默回绕、全盘错乱**，而且是在运行了很久之后才发生。`long` 在 2⁶³ 处才溢出，实际不可达。

**④ 容量取 2 的幂、用 `& (cap - 1)` 取模，并且构造时拒绝非 2 的幂。**

> **规格 §5.5 第 3 条的理由在本实现里不成立，如实记一笔**：它说"用 `%` 对负数返回负值"，
> 但本实现的 `writeIndex` 从 0 起只增不减，**永远是正的**——把 `& (cap-1)` 换成 `% capacity`
> 数值完全等价（这条变异实测存活，见变异表）。真正需要 2 的幂的地方是**构造期校验**：
> `cap = 6` 时 `mask = 5`，索引 6 会被映射到槽位 4，**静默错位**。
> 所以防守落在"容量不是 2 的幂时抛 IllegalArgumentException"这条断言上。

### `dirtyRange` 在窗口滑动时的语义（规格 R2 点名的风险）

类文档里逐条写清"哪些情况算脏"，并且每一种都有测试：

| 情况 | 返回 | 理由 |
|------|------|------|
| 没有追加（`sinceRevision >= writeIndex`） | `EMPTY` | 什么都没变 |
| 追加了 N 个，读者还在窗口内 | `(sinceRevision - windowStart, itemCount)` | 只有新增的那 N 个是新的，其余原样 |
| **窗口滑动过**（读者停在窗口之外） | `(0, itemCount)` | 下标与样本的对应关系整体错位了，**部分重传会把新旧两段拼成一条假线** |
| `revision` 就是 `writeIndex` | —— | 这样"自某修订号以来"天然就是"自某个绝对样本号以来"，不需要再维护一张修订号→位置的历史表 |

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/RingChartData.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/RingChartDataTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/RingChartDataTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RingChartData} 的单测。
 *
 * <p><strong>它是单线程的。</strong>并发行为由 {@code ChartDataConcurrencyTest} 冒烟覆盖，
 * 但那个测试是探测器而不是保证（见它的类文档）。这里的每一条都是确定性的行为断言。
 *
 * <p>命名约定：{@code 窗口下标 i} 指 {@code [0, itemCount())}；{@code 绝对号 n}
 * 指"第 n 个写入的样本"（从 0 起）。窗口滑动之后，同一个窗口下标对应的绝对号会变——
 * 这正是 {@link #缺口落在窗口之外的位置并返回NaN} 要钉住的事。
 */
class RingChartDataTest {

    private static final AxisRange[] ONE_DIM = {AxisRange.of(0, 100)};

    private static RingChartData ring(int capacity) {
        return new RingChartData(ONE_DIM, capacity);
    }

    private static void append(RingChartData data, int count) {
        for (int i = 0; i < count; i++) {
            data.append(i);
        }
    }

    @Test
    void 追加后按窗口下标读回样本() {
        RingChartData data = ring(8);
        data.append(11);
        data.append(22);
        data.append(33);

        assertEquals(3, data.itemCount());
        assertEquals(11.0, data.value(0, 0), 0);
        assertEquals(33.0, data.value(0, 2), 0);
        assertEquals(3L, data.writeIndex(), "写索引等于已写入的样本总数");
    }

    @Test
    void 修订号与写索引同步推进() {
        RingChartData data = ring(8);
        assertEquals(0L, data.revision());
        assertEquals(0, data.itemCount());
        append(data, 3);
        assertEquals(3L, data.revision(), "revision 就是 writeIndex：这样\"自某修订号以来\""
                + "天然就是\"自某个绝对样本号以来\"，不用再维护一张历史表");
        assertEquals(data.writeIndex(), data.revision());
    }

    @Test
    void 未写满时窗口从零开始() {
        RingChartData data = ring(8);
        append(data, 5);
        assertEquals(0L, data.windowStart());
        assertEquals(5, data.itemCount());
    }

    @Test
    void 环绕后读到的是最新的一圈() {
        RingChartData data = ring(8);
        append(data, 12);

        assertEquals(8, data.itemCount(), "环形缓冲最多只能看见 capacity 个样本");
        assertEquals(12L, data.writeIndex());
        for (int i = 0; i < 8; i++) {
            assertEquals(4 + i, data.value(0, i), 0,
                    "窗口下标 " + i + " 应当是第 " + (4 + i) + " 号样本");
        }
    }

    @Test
    void 环绕后窗口起点同步前移() {
        RingChartData data = ring(8);
        append(data, 12);
        assertEquals(4L, data.windowStart(),
                "窗口起点必须是 writeIndex - itemCount()：它不跟着前移的话，"
                        + "value() 会读到已经被覆盖的槽位（值还是对的，只是属于别的样本）");
    }

    @Test
    void 读者落后整圈时丢弃最旧并计数() {
        RingChartData data = ring(8);
        append(data, 8);
        data.markConsumed(0);          // 读者一个都还没用上
        append(data, 3);               // 0/1/2 号样本在读者用上它们之前就被覆盖了
        data.markConsumed(0);          // 读者下一次公布进度时，才发现自己在窗口之外

        assertEquals(3L, data.lostSamples(), "被丢掉的样本数必须如实计数——"
                + "\"这一屏数据完整吗\"本来就是示波器用户要问的问题");
        assertEquals(3L, data.windowStart());
        assertEquals(3L, data.consumedIndex(), "读者的进度被钳到窗口起点：窗口之外的数据它再也拿不到了");
    }

    @Test
    void 缺口落在窗口之外的位置并返回NaN() {
        RingChartData data = ring(8);
        append(data, 8);
        data.markConsumed(0);
        append(data, 3);

        // 0/1/2 号样本已经被覆盖。读者手里记的是绝对号，换算到窗口下标得到负数——
        // 那就是缺口的位置。它必须给出"没有值"，而不是错位的旧值。
        assertTrue(Double.isNaN(data.value(0, -1)),
                "被丢弃的样本位置必须返回 NaN；读出错位的旧值会把缺口连成一条假线");
        assertTrue(Double.isNaN(data.value(0, -3)));
        assertTrue(Double.isNaN(data.value(0, data.itemCount())),
                "窗口上界之外同样没有值");
        assertTrue(Double.isNaN(data.value(0, 99999)));
    }

    @Test
    void 窗口内的样本一个都不错位() {
        RingChartData data = ring(8);
        append(data, 20);
        data.markConsumed(data.writeIndex() - 5);   // 读者落后 5 个（但还没被丢弃）

        // itemCount() 是**可见窗口**的长度，不会因为读者落后而变小：
        // 窗口是绝对号 [12, 20)，也就是窗口下标 0..7
        long start = data.windowStart();
        assertEquals(8, data.itemCount());
        assertEquals(12L, start);
        for (int i = 0; i < data.itemCount(); i++) {
            assertEquals(start + i, data.value(0, i), 0,
                    "窗口内的每个下标都必须对得上它的绝对号");
            assertFalse(Double.isNaN(data.value(0, i)), "窗口内的下标不该出现缺口");
        }
    }

    @Test
    void 读者回退不重复计数() {
        RingChartData data = ring(8);
        append(data, 8);
        data.markConsumed(0);
        append(data, 4);               // 窗口滑到 [4,12)
        data.markConsumed(0);          // 读者这才发现 0..3 号已经没了
        long lost = data.lostSamples();
        assertTrue(lost > 0, "先要真的丢过数据，否则这条断言是空转");

        data.markConsumed(0);          // 再报一次同样的旧进度
        assertEquals(lost, data.lostSamples(), "重复上报旧进度不能重复计数");
        data.markConsumed(data.writeIndex());
        assertEquals(lost, data.lostSamples(), "追上进度也不该改变已经丢掉的数量");
    }

    @Test
    void 没有追加时脏区间为空() {
        RingChartData data = ring(8);
        append(data, 4);
        assertTrue(data.dirtyRange(data.revision()).isEmpty(), "没有追加却报了脏区");
        assertTrue(data.dirtyRange(data.revision() + 10).isEmpty());
    }

    @Test
    void 新增样本的脏区间只覆盖新增的那一段() {
        RingChartData data = ring(8);
        append(data, 4);
        long revision = data.revision();
        append(data, 3);

        DirtyRange dirty = data.dirtyRange(revision);
        assertEquals(4, dirty.firstDirty(), "脏区间必须从新增的第一个下标开始："
                + "从 0 开始的话每帧都要重传整个窗口，增量上传的好处全部抵消");
        assertEquals(7, dirty.lastDirty());
    }

    @Test
    void 窗口滑动后整个窗口都算脏() {
        RingChartData data = ring(8);
        append(data, 8);
        long revision = data.revision();      // 读者看到的修订号 = 绝对号 8
        data.markConsumed(0);
        append(data, 10);                     // 窗口滑到 [10,18)：绝对号 8 已经被丢掉了

        DirtyRange dirty = data.dirtyRange(revision);
        assertEquals(0, dirty.firstDirty(), "读者手里的位置已经被覆盖时，下标与样本的对应关系"
                + "整体错位——部分重传会把新旧两段拼成一条假线，只有整体重传是安全的");
        assertEquals(data.itemCount(), dirty.lastDirty());

        // 对照：读者只落后一点点（还没掉出窗口）时报的是**部分脏**，不该整窗重传
        RingChartData other = ring(8);
        append(other, 8);
        long otherRevision = other.revision();
        append(other, 4);                     // 窗口滑到 [4,12)，绝对号 8 仍在窗口里
        DirtyRange partial = other.dirtyRange(otherRevision);
        assertEquals(4, partial.firstDirty(),
                "读者还在窗口里的时候报整窗脏，等于每帧白传一整个窗口");
        assertEquals(8, partial.lastDirty());
    }

    @Test
    void 传感器自身的NaN会原样返回() {
        RingChartData data = ring(8);
        data.append(1);
        data.append(Double.NaN);
        data.append(3);

        assertTrue(Double.isNaN(data.value(0, 1)),
                "传感器自己吐的 NaN 必须照原样留着：渲染器的规则只有一条"
                        + "\"遇到 NaN 就断开折线\"，它不必也不该知道这个 NaN 是丢包还是传感器给的");
        assertEquals(3.0, data.value(0, 2), 0);
        assertEquals(0L, data.lostSamples(), "NaN 样本不是丢包，不该计数");
    }

    @Test
    void 维度数与声明不符时抛异常() {
        RingChartData data = new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(0, 1)}, 8);
        assertThrows(IllegalArgumentException.class, () -> data.append(1));
        assertThrows(IllegalArgumentException.class, () -> data.append(1, 2, 3));
        assertDoesNotThrow(() -> data.append(1, 2));
        assertEquals(2.0, data.value(1, 0), 0);
    }

    @Test
    void 容量不是2的幂时抛异常() {
        for (int capacity : new int[]{0, 1, 3, 6, 100, 1000}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new RingChartData(ONE_DIM, capacity),
                    "容量 " + capacity + " 不是 2 的幂，必须构造时就拒绝");
            assertTrue(e.getMessage().contains("2 的幂"),
                    "消息要说清原因：" + e.getMessage());
        }
        assertDoesNotThrow(() -> new RingChartData(ONE_DIM, 2));
        assertDoesNotThrow(() -> new RingChartData(ONE_DIM, 1 << 20));
    }

    @Test
    void 写索引必须是volatile的long() throws IOException {
        // 这是一条**结构断言**，不是行为断言，理由：两条最关键的不变式在单测口径下
        // 根本不可观测——int 要在 2³¹ 次追加之后才回绕（几十分钟），
        // 非 volatile 则取决于 JIT 与缓存（可能永远看不到，也可能明天才看不到）。
        // 唯一能在毫秒级钉住它们的东西是看一眼源码。不优雅，但比"没有防守"强：
        // 把 long 改成 int、或把 volatile 去掉，这条会立刻失败。
        Path source = Path.of("src", "main", "java", "com", "bingbaihanji", "jfgl", "chart",
                "RingChartData.java");
        assertTrue(Files.isRegularFile(source), "找不到源文件：" + source.toAbsolutePath()
                + "（工作目录应为项目根，找错了目录会让本测试形同虚设）");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        Matcher matcher = Pattern.compile("volatile\\s+long\\s+writeIndex").matcher(text);
        assertTrue(matcher.find(),
                "writeIndex 必须声明成 volatile long：int 会在几十分钟后静默回绕、全盘错乱；"
                        + "非 volatile 则读者可能永远看不到新数据（循环里读的是寄存器里的旧值）");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=RingChartDataTest`
Expected: 编译失败 — `找不到符号: 类 RingChartData`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/RingChartData.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 流式数据：单生产者单消费者（SPSC）的环形缓冲。采集线程写，GL 线程读。
 *
 * <h2>⚠️ 硬前提：只有一个写者</h2>
 * <p>采集线程由使用方自己起（已确认），所以这个前提成立。
 * <strong>若将来数据源改成网络/串口回调</strong>（回调线程可能是 IO 线程池里的任意一个），
 * <strong>单生产者前提就不成立，整个无锁设计必须换掉</strong>（多写者需要 CAS 或加锁）。
 *
 * <pre>
 * 采集线程（唯一写者）                  GL 线程（唯一读者）
 *   写 data[d][w &amp; (cap-1)]              读 writeIndex（volatile，acquire）
 *   ↓                                       取 [读者自己的位置, writeIndex) 这段
 *   写 writeIndex = w + 1（volatile，release）上传顶点
 * </pre>
 *
 * <p><strong>不用锁</strong>：采集线程是实时的，它一次都不该被 GL 线程阻塞。
 * 在采集卡场景下"阻塞"往往意味着硬件 FIFO 溢出，丢得更多。
 *
 * <h2>三处正确性要点（每一条错了都不报错）</h2>
 * <ol>
 *   <li><strong>先写数据、再递增索引。</strong>volatile 写在 Java 里有 release 语义、
 *       读有 acquire 语义，这个顺序保证读者看到索引时数据一定已可见。
 *       <strong>反过来写就是经典的半初始化读取</strong>：读者拿到新索引，
 *       读到的却是还没写完的旧数据——它不是 NaN，也不是 0，而是一个看起来完全正常的错值。</li>
 *   <li><strong>索引用 {@code long} 不用 {@code int}。</strong>每秒几百万点，
 *       {@code int} 几十分钟就溢出，<strong>溢出后静默回绕、全盘错乱</strong>，
 *       而且是在运行了很久之后才发生，最难查。{@code long} 在 2⁶³ 处才溢出，实际不可达。</li>
 *   <li><strong>容量取 2 的幂，用 {@code & (cap - 1)} 取模。</strong>
 *       规格 §5.5 第 3 条给的理由（"Java 的 {@code %} 对负数返回负值"）在本实现里
 *       <strong>不成立</strong>：{@code writeIndex} 从 0 起只增不减，永远是正的，
 *       把掩码换成 {@code %} 数值完全等价。真正需要 2 的幂的是<strong>构造期校验</strong>：
 *       {@code cap = 6} 时 {@code mask = 5}，索引 6 会被映射到槽位 4，静默错位。
 *       所以这里在构造时拒绝非 2 的幂。</li>
 * </ol>
 *
 * <h2>溢出：丢弃最旧 + 缺口 + 计数</h2>
 * <p>采集线程比 GL 线程快是常态（掉帧、窗口被遮挡），<strong>写者绕过读者一定会发生</strong>。
 * 策略是<strong>丢弃最旧的数据，并且让它可见</strong>：
 * <ul>
 *   <li>窗口 = 最近 {@link #capacity()} 个样本，<strong>窗口里全是有效数据</strong>，
 *       被丢弃的样本的位置必然<strong>在窗口之外</strong>。</li>
 *   <li>因此缺口的表示就是：<strong>窗口之外的 {@link #value} 返回 NaN</strong>。
 *       读者手里记着绝对号，换算到窗口下标得到负数，于是拿到 NaN 并断开折线。
 *       <strong>不额外加 {@code isGap(index)}</strong>：多一条判断路径迟早会与
 *       "遇到 NaN 就断开"这条不一致，而它们不一致的那一天，画面会连出一条假线。</li>
 *   <li>{@link #lostSamples()} 计数由<strong>读者</strong>在 {@link #markConsumed(long)}
 *       时更新——只有读者知道自己落了多远。计数暴露给 UI："这一屏数据完整吗"
 *       本来就是示波器用户要问的问题。</li>
 *   <li><strong>丢失是在读者<em>下一次</em>公布进度时才被发现的。</strong>
 *       写者完全不参与（它一次都不读读者的进度），这是 SPSC 纯粹性的代价，
 *       也换来了写者绝不会因为读者慢而多做一个 volatile 读。
 *       因此读者<strong>必须每帧调用一次</strong> {@link #markConsumed(long)}——
 *       不调用的话窗口照样滑动、缺口照样出现，只是计数停在 0，
 *       而"计数停在 0"与"没有丢数据"在 UI 上完全一样。</li>
 *   <li><strong>为什么宁丢点不阻塞</strong>：采集卡场景下阻塞往往意味着硬件 FIFO 溢出，丢得更多。
 *       <strong>为什么不让它无限增长</strong>：采集是持续的，内存最终会爆，
 *       而"爆"的时候离出问题的地方很远——把立即的、可观测的问题换成了延迟的、不可观测的。</li>
 * </ul>
 *
 * <h2>{@link #dirtyRange(long)} 的语义（窗口会滑动，这是主要风险）</h2>
 * <table border="1">
 *   <caption>哪些情况算脏</caption>
 *   <tr><td>没有追加</td><td>{@code EMPTY}</td></tr>
 *   <tr><td>追加了 N 个，读者仍在窗口内</td>
 *       <td>{@code (sinceRevision - windowStart, itemCount)}，只覆盖新增的那一段</td></tr>
 *   <tr><td><strong>窗口滑动过</strong>（读者停在窗口之外）</td>
 *       <td>{@code (0, itemCount)}：下标与样本的对应关系整体错位，
 *           部分重传会把新旧两段拼成一条假线，只有整体重传是安全的</td></tr>
 * </table>
 * <p>{@link #revision()} 直接返回 {@code writeIndex}：这样"自某修订号以来"天然就是
 * "自某个绝对样本号以来"，不必再维护一张"修订号 → 位置"的历史表。
 *
 * <h2>线程</h2>
 * <p>{@link #append(double...)} 只在采集线程调用（唯一写者）；
 * {@link #value}、{@link #itemCount}、{@link #dirtyRange}、{@link #markConsumed}
 * 只在 GL 线程调用（唯一读者）。{@link #writeIndex()}、{@link #lostSamples()} 等
 * 只读查询可以被别的线程读（它们读的是 volatile 字段）。
 */
public final class RingChartData implements ChartData {

    /** 缺口的值：{@link #value} 在窗口之外返回它。 */
    public static final double GAP = Double.NaN;

    private final AxisRange[] ranges;

    /** 物理容量，2 的幂。 */
    private final int capacity;

    /** {@code capacity - 1}，用于 {@code & } 取模。 */
    private final int mask;

    /** 数据：{@code data[dim * capacity + slot]}。 */
    private final double[] data;

    /**
     * 已写入的样本总数（从 0 起，只增不减）。
     *
     * <p><strong>必须是 {@code volatile long}</strong>：{@code int} 会在几十分钟后静默回绕，
     * 非 volatile 则读者可能永远看不到新数据。这条由 {@code RingChartDataTest} 的结构断言钉住。
     */
    private volatile long writeIndex;

    /** 读者公布的消费进度（绝对号）。读者写、别人读，因此也是 volatile。 */
    private volatile long consumedIndex;

    /** 被丢弃的样本数。读者在 {@link #markConsumed(long)} 时更新。 */
    private volatile long lostSamples;

    /**
     * 构造。
     *
     * @param ranges   各维度的范围，长度即维度数，至少 1 个
     * @param capacity 环形缓冲的容量，<strong>必须是 2 的幂且 ≥ 2</strong>
     * @throws IllegalArgumentException 维度数为 0、参数为 null、或容量不是 2 的幂时
     */
    public RingChartData(AxisRange[] ranges, int capacity) {
        if (ranges == null || ranges.length == 0) {
            throw new IllegalArgumentException("至少要声明一个维度");
        }
        for (int d = 0; d < ranges.length; d++) {
            if (ranges[d] == null) {
                throw new IllegalArgumentException("第 " + d + " 个维度的范围是 null");
            }
        }
        if (capacity < 2) {
            throw new IllegalArgumentException("容量至少为 2（2 是最小的 2 的幂），实际为 " + capacity);
        }
        if ((capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "容量必须是 2 的幂，实际为 " + capacity + "：取模用 & (capacity-1) 实现，"
                            + "容量不是 2 的幂时这个掩码会把索引映射到错的位置（静默错位，"
                            + "表现为读到别的样本的值）");
        }
        this.ranges = ranges.clone();
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.data = new double[ranges.length * capacity];
    }

    /** 环形缓冲的容量（可见样本数的上限）。 */
    public int capacity() {
        return capacity;
    }

    /** 已写入的样本总数。写入它的是采集线程，读它的是任何人。 */
    public long writeIndex() {
        return writeIndex;
    }

    /** 可见窗口的起点在<strong>绝对号</strong>空间里的位置。 */
    public long windowStart() {
        long w = writeIndex;
        return w > capacity ? w - capacity : 0L;
    }

    /** 读者公布的消费进度（绝对号）。 */
    public long consumedIndex() {
        return consumedIndex;
    }

    /** 因为写者绕过读者而被丢弃的样本数。 */
    public long lostSamples() {
        return lostSamples;
    }

    /**
     * 追加一个样本。
     *
     * <p><strong>只能在采集线程调用</strong>（唯一写者）。这里的顺序不能动：
     * 先写数据、再发布索引。
     *
     * @param values 各维度的值，个数必须与声明的维度数一致；允许 NaN（传感器故障）
     * @throws IllegalArgumentException 维度数不符或为 null 时
     */
    public void append(double... values) {
        if (values == null || values.length != ranges.length) {
            throw new IllegalArgumentException(
                    "样本维度数与声明不符：声明 " + ranges.length + " 维，实际 "
                            + (values == null ? 0 : values.length) + " 维");
        }
        long w = writeIndex;
        int slot = (int) (w & mask);
        for (int d = 0; d < ranges.length; d++) {
            // ① 先把数据写进去
            data[d * capacity + slot] = values[d];
        }
        // ② 再发布索引。volatile 写有 release 语义：这之前的写对读者可见。
        //    顺序反过来就是半初始化读取——读者拿到新索引，读到的却是旧值，
        //    而且那个旧值看起来完全正常。
        writeIndex = w + 1;
    }

    /**
     * 读者宣告"我（GL 线程）已经消费到绝对号 {@code upto}"。
     *
     * <p><strong>每帧调用一次</strong>：丢失只会在调用它的那一刻被发现
     * （写者不读读者的进度），不调用的话 {@link #lostSamples()} 会一直停在 0，
     * 而"计数停在 0"与"没有丢数据"在 UI 上完全一样。
     *
     * <p>被写者绕过的那一段记进 {@link #lostSamples()}，进度被钳到窗口起点
     * （窗口之外的数据再也拿不到了）。重复上报旧进度<strong>不会</strong>重复计数。
     *
     * @param upto 已经消费到的绝对号（不含）
     */
    public void markConsumed(long upto) {
        long w = writeIndex;
        long start = w > capacity ? w - capacity : 0L;
        long clamped = Math.min(Math.max(upto, 0L), w);
        if (clamped < consumedIndex) {
            return;                         // 报了个更旧的进度：不重复计数
        }
        if (clamped < start) {
            lostSamples += start - clamped;
            clamped = start;
        }
        consumedIndex = clamped;
    }

    @Override
    public AxisRange axisRange(int dim) {
        if (dim < 0 || dim >= ranges.length) {
            throw new IndexOutOfBoundsException("维度下标越界：" + dim + "，共 " + ranges.length + " 维");
        }
        return ranges[dim];
    }

    @Override
    public int itemCount() {
        long w = writeIndex;
        return (int) Math.min(w, capacity);
    }

    @Override
    public double value(int dim, int index) {
        axisRange(dim);
        int count = itemCount();
        if (index < 0 || index >= count) {
            // 窗口之外：那一段要么还没写（上界），要么已经被覆盖（下界 = 缺口）。
            // 返回 NaN 而不是抛异常、也不是去读一个错位的槽位：读者只需要一条规则
            // "遇到 NaN 就断开折线"，缺口与传感器自己的 NaN 因此天然合一。
            return GAP;
        }
        long absolute = windowStart() + index;
        return data[dim * capacity + (int) (absolute & mask)];
    }

    @Override
    public long revision() {
        return writeIndex;
    }

    @Override
    public DirtyRange dirtyRange(long sinceRevision) {
        long w = writeIndex;
        if (sinceRevision >= w) {
            return DirtyRange.EMPTY;
        }
        int count = itemCount();
        long from = sinceRevision - (w - count);
        if (from < 0) {
            // 读者停在被覆盖的区域里：窗口里每一个下标对应的样本都换过了。
            // 部分重传会把新旧两段拼成一条假线——那条线显示了一个不存在的信号。
            return new DirtyRange(0, count);
        }
        return new DirtyRange((int) from, count);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=RingChartDataTest`
Expected: `Tests run: 16, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 247, Failures: 0, Errors: 0, Skipped: 2`（231 + 16）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 7 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `value()` 去掉越界判断（直接 `data[dim*capacity + (absolute & mask)]`） | `缺口落在窗口之外的位置并返回NaN`：负下标会抛 `ArrayIndexOutOfBoundsException`，上界之外会读到错位的旧值 |
| `itemCount()` 改成 `(int) writeIndex`（不钳到容量） | `环绕后读到的是最新的一圈`、`窗口内的样本一个都不错位` |
| `windowStart()` 恒返回 0（窗口不滑动） | `环绕后窗口起点同步前移`、`环绕后读到的是最新的一圈` |
| `markConsumed` 去掉 `clamped < start` 的判断（永不记 `lostSamples`） | `读者落后整圈时丢弃最旧并计数` |
| `markConsumed` 去掉"报更旧进度就直接返回"的短路 | `读者回退不重复计数` |
| `dirtyRange` 去掉 `from < 0` 那个分支（永远返回"部分脏"） | `窗口滑动后整个窗口都算脏` |
| `writeIndex` 的 `volatile` 去掉 | `写索引必须是volatile的long`（**结构断言**）。**如实说明**：去掉 volatile 在单线程测试里**行为上完全不可观测**，所以这条变异只能被结构断言抓住——这正是那条断言存在的理由 |
| 构造期去掉"容量必须是 2 的幂"的校验，并传 `capacity = 6` | `容量不是2的幂时抛异常` |

> **规格 §5.5 第 3 条要求的那条变异（`& (cap-1)` → `% capacity`）本计划不列**，
> 因为实测它在 `writeIndex ≥ 0` 的实现里**数值完全等价、必然存活**。
> 规格给的理由（"Java 的 `%` 对负数返回负值"）针对的是会把索引算成负数的实现，
> 而这里的 `writeIndex` 只增不减。**把这条如实写在这里，比编一条注定存活的变异强。**

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/RingChartData.java \
        src/test/java/com/bingbaihanji/jfgl/chart/RingChartDataTest.java
git commit -F - <<'EOF'
feat(chart): RingChartData —— SPSC 环形缓冲（实时路径的地基）

三处正确性要点，每一条错了都不报错，都写进了类文档：
- 先写数据再递增索引（volatile 的 release/acquire）。反过来是经典的半初始化读取，
  读到的不是一个显眼的坏值，而是一个看起来完全正常的旧值；
- 索引是 long。int 几十分钟后静默回绕、全盘错乱，而且是在运行很久之后才发生；
- 容量取 2 的幂并在构造时强制。规格给的理由（% 对负数返回负值）在本实现里不成立
  ——writeIndex 只增不减，换成 % 完全等价，这条变异必然存活，已如实记在计划里。
  真正需要 2 的幂的是构造期校验：cap=6 时 mask=5，索引 6 会静默映射到槽位 4。

缺口不写物理标记：滑动窗口里窗口内全是有效数据，被丢弃的样本位置必然在窗口之外，
于是 value() 对窗口外的下标返回 NaN 就是缺口的位置。读者拿绝对号换算过来得到负下标，
拿到 NaN 并断开折线。不额外加 isGap()：多一条判断路径迟早与"遇到 NaN 就断开"不一致，
而不一致的那天画面会连出一条假线——那比不显示更糟，而且看起来完全正常。

dirtyRange 的四种情况写进了类文档并各有测试，尤其"窗口滑动后整窗都算脏"：
下标与样本的对应关系整体错位时，部分重传会把新旧两段拼成一条假线。

lostSamples 由读者在 markConsumed 时更新：只有读者知道自己落了多远。

16 条单测 + 7 条变异验证（含两条只能被结构断言抓住的：volatile 与 long）。
不列"& (cap-1) 换成 %"那条变异，因为它在本实现里数值等价、必然存活。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 6: ChartDataConcurrencyTest（并发冒烟测试）

> **⚠️ 先读清楚这个测试有多强，再写它。**
>
> 它是**冒烟探测器，不是保证**。正确实现永远通过；**但不保证抓得住错误实现**——
> 线程调度、JIT、CPU 缓存都可能让一个错误实现在 200 毫秒里侥幸不出错。
> 真正的正确性来自 Task 5 那三条被写死的不变式 + 代码评审（规格 §12 R1）。
>
> **变异验证时尤其要注意**：「索引 `long` 改成 `int`」这条**短期内不会失败**——
> 要跑到 2³¹ 次追加才会暴露。**如实记录实测结果，跑不出来就写"未能捕获"，
> 不要假称捕获了。**（它其实被 Task 5 的结构断言抓住了，那是另一条路。）

**撕裂检测怎么设计**：两个维度写同一个绝对号的**互为相反数**（`dim0 = n`、`dim1 = -n`）。
读到的一对值若满足 `a == -b`，说明它们是同一次写入的；若不等，说明读者
看到了新索引却只读到一半的新数据——**这正是"先递增索引、后写数据"这个变异的签名**。
判据 `a == -b` 与"读到的是第几号样本"无关，所以**能扛住写者飞快地绕过好几圈**，
不会因为"读写之间窗口滑动了"而误报。

**Files:**
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/ChartDataConcurrencyTest.java`

- [ ] **Step 1: 写测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/ChartDataConcurrencyTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RingChartData} 的并发冒烟测试：一个写者、一个读者，跑一段时间。
 *
 * <h2>⚠️ 这个测试有多强？如实说</h2>
 * <p><strong>它是冒烟探测器，不是保证。</strong>正确实现永远通过；
 * <strong>但错误实现也可能通过</strong>——线程调度、JIT 与 CPU 缓存都可能让
 * 一个漏掉 volatile 的实现在几百毫秒里侥幸不出错。真正的正确性来自
 * {@code RingChartData} 类文档里的那三条不变式 + 代码评审。
 *
 * <p>它抓得住的那一类是"读者看到新索引、数据却还没写完"（半初始化读取）：
 * 两个维度写同一个绝对号的相反数，读到的一对值必须满足 {@code a == -b}。
 * 判据与"读到的是第几号样本"无关，因此写者飞快绕过好几圈也不会误报。
 *
 * <p>读一对值的两读之间要夹一道"窗口没滑动过"的检查：滑动过就说明这两个值
 * 不属于同一个绝对号，跳过即可。<strong>少了这道闸，正确的实现会被误判成撕裂</strong>
 * ——实测它一次能报出上百万次假撕裂，比没有测试更浪费时间。
 */
class ChartDataConcurrencyTest {

    private static final int CAPACITY = 1 << 16;

    /** 跑多久。取 200 毫秒：够跑几百万次写入，又不至于让全量测试变慢。 */
    private static final long RUN_NANOS = 200_000_000L;

    /**
     * 读者不碰窗口头部的这几格。
     *
     * <p>窗口头部那一格（下标 0，绝对号 {@code w - capacity}）<strong>正是写者此刻正在覆写的槽位</strong>：
     * 写者已经把第 {@code w} 号样本的 dim0 写了进去，dim1 还没写（它在写完两个维度之后
     * 才发布索引）。读者若去读这一格，dim0 是新样本、dim1 还是旧样本，
     * <strong>{@code a == -b} 必然不成立——那是真实现，不是撕裂</strong>。
     *
     * <p>留一点余量而不是只跳 1 格，是为了让"头部不安全"这件事在代码里显眼。
     * 其余位置安全：绝对号小于已发布的 {@code w} 的样本，两个维度都已写完（volatile
     * 写在最后，release 语义保证它们都可见）。
     */
    private static final int HEAD_MARGIN = 4;

    private static RingChartData freshRing() {
        return new RingChartData(
                new AxisRange[]{AxisRange.of(0, 1), AxisRange.of(-1, 0)}, CAPACITY);
    }

    @Test
    void 单写单读下读到的样本不会是半初始化的() throws Exception {
        RingChartData data = freshRing();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            long n = 0;
            try {
                while (!stop.get()) {
                    // 两个维度写同一个绝对号的相反数：读到的 a 与 b 必须互为相反数。
                    // 先递增索引后写数据的实现会让读者拿到 a == -b 不成立的一对值。
                    data.append(n, -n);
                    n++;
                }
            } catch (Throwable t) {
                writerFailure.compareAndSet(null, t);
            }
        }, "采集线程");
        writer.setDaemon(true);
        writer.start();

        long torn = 0;
        long checked = 0;
        long lastSeenWriteIndex = 0;
        long deadline = System.nanoTime() + RUN_NANOS;
        try {
            while (System.nanoTime() < deadline) {
                long w = data.writeIndex();
                if (w > lastSeenWriteIndex) {
                    lastSeenWriteIndex = w;
                }
                long start = data.windowStart();
                long end = Math.min(w, start + 512);
                for (long absolute = start + HEAD_MARGIN; absolute < end; absolute++) {
                    int index = (int) (absolute - start);
                    if (index < 0 || index >= data.itemCount()) {
                        continue;
                    }
                    // 窗口在两读之间滑动过的话，这两个值就不属于同一个绝对号了——
                    // 那道检查必须在这里，否则写者飞快时几乎每一对都会被误判成撕裂
                    // （实测：少了它，20 万个样本的窗口能报出上百万次"撕裂"）。
                    long before = data.windowStart();
                    double a = data.value(0, index);
                    double b = data.value(1, index);
                    if (data.windowStart() != before) {
                        continue;
                    }
                    if (Double.isNaN(a) || Double.isNaN(b)) {
                        continue;
                    }
                    checked++;
                    if (a != -b) {
                        torn++;
                    }
                }
                data.markConsumed(w);
                Thread.onSpinWait();
            }
        } finally {
            stop.set(true);
            writer.join(2000);
        }

        assertNull(writerFailure.get(), "采集线程抛异常了：" + writerFailure.get());
        assertEquals(0, torn,
                "读到 " + torn + " 对撕裂的值（dim0 与 dim1 不互为相反数）："
                        + "写者把索引的发布放在了数据写入之前——这正是半初始化读取");
        // 护栏：写者没跑起来的话上面的相等断言是恒真的（橡皮图章）
        assertTrue(lastSeenWriteIndex > CAPACITY * 4,
                "写者只写了 " + lastSeenWriteIndex + " 个样本，这个测试没有真的在并发——"
                        + "恒真的断言比没有断言更危险");
        assertTrue(checked > 1000, "只验了 " + checked + " 对值，覆盖太薄");
    }

    @Test
    void 写索引单调不回退() throws Exception {
        RingChartData data = freshRing();
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread writer = new Thread(() -> {
            long n = 0;
            while (!stop.get()) {
                data.append(n, -n);
                n++;
            }
        }, "采集线程");
        writer.setDaemon(true);
        writer.start();

        long regressions = 0;
        long previous = -1;
        long observations = 0;
        long deadline = System.nanoTime() + RUN_NANOS;
        try {
            while (System.nanoTime() < deadline) {
                long w = data.writeIndex();
                if (w < previous) {
                    regressions++;
                }
                previous = Math.max(previous, w);
                observations++;
                Thread.onSpinWait();
            }
        } finally {
            stop.set(true);
            writer.join(2000);
        }

        assertEquals(0, regressions, "写索引回退了 " + regressions + " 次："
                + "单调递增是 SPSC 的全部前提，回退意味着读者会读到已经作废的下标");
        assertTrue(observations > 1000, "只观察了 " + observations + " 次，覆盖太薄");
        assertTrue(previous > CAPACITY * 4, "写者没跑起来，上面的断言是恒真的");
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `mvn -o test -Dtest=ChartDataConcurrencyTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`（可能要几百毫秒到 1 秒）

- [ ] **Step 3: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 249, Failures: 0, Errors: 0, Skipped: 2`（247 + 2）

- [ ] **Step 4: 变异验证**（每条做完立刻还原，共 2 条；**必须如实记录强度**）

| 变异 | 预期 | 实测要求 |
|------|------|---------|
| `append` 里把 `writeIndex = w + 1` 挪到写数据<strong>之前</strong> | `单写单读下读到的样本不会是半初始化的` | **如实记录**。这条**不一定**每次都能被抓住：线程调度与 JIT 都可能让它侥幸通过。**跑 5 遍，把 5 次的结果（通过/失败）原样写进实施报告**。全都没抓住就写"未能捕获"，然后说明它由 Task 5 的"先写数据再递增索引"类文档 + 代码评审兜底 |
| `writeIndex` 的类型从 `long` 改成 `int` | **预期长期不失败** | **必须如实写"未能捕获"**：要跑到 2³¹ 次追加（几十分钟）才会暴露。真正抓住它的是 `RingChartDataTest.写索引必须是volatile的long` 这条结构断言——在报告里把这条对应关系写清楚，不要含糊成"已被覆盖" |

**不许为了让变异表好看而调长 `RUN_NANOS` 或反复重跑到失败为止。**那是把冒烟测试伪装成保证。

- [ ] **Step 5: 提交**

```bash
git add src/test/java/com/bingbaihanji/jfgl/chart/ChartDataConcurrencyTest.java
git commit -F - <<'EOF'
test(chart): RingChartData 的并发冒烟测试

如实说明强度：这是冒烟探测器，不是保证。正确实现永远通过，但不保证抓得住错误
实现——调度、JIT 与缓存都可能让漏掉 volatile 的实现在几百毫秒里侥幸不出错。
真正的正确性来自 RingChartData 类文档里那三条不变式加代码评审。

抓到的那一类是"读者看到新索引、数据还没写完"：两个维度写同一个绝对号的相反数，
读到的一对值必须互为相反数。判据与"读到的是第几号样本"无关，所以写者飞快绕过
好几圈也不会误报。两条断言各配了一条护栏（写索引必须真的推进了、验证次数必须
够多），否则并发测试的相等断言很容易变成恒真的橡皮图章。

变异验证如实记录：把索引的发布挪到数据写入之前，跑 5 遍记录结果；
把 long 改成 int 这条短期内不会失败，必须写"未能捕获"——
真正抓住它的是 RingChartDataTest 里那条结构断言。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: ColorMapping → 1×256 LUT

规格 §7：`ColorMapping` 持有一组 `Stop[]`（科学配色），能生成一张 **1×256 的 LUT**
（`byte[1024]`，RGBA）。

**这正好落在 JFGL 已定的"所有 Paint 归一化为纹理"上**：热力图换配色从 fxcharts 的
"整幅逐像素重算"变成"换一张纹理"（规格 §7 说这是收益最大的一条）。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/ColorMapping.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/ColorMappingTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/ColorMappingTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ColorMapping} 的单测：**纯计算**。
 *
 * <p>断言落在三样东西上：LUT 的端点、单调性、越界钳位。
 * 通道顺序（RGBA 还是 ARGB）错了不会报错，只会让整张热力图红蓝互换——
 * 那种"看起来像是配色选错了"的 bug 最难往"通道序写反"上想。
 */
class ColorMappingTest {

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF0000;

    private static int channel(byte[] lut, int index, int offset) {
        return lut[index * 4 + offset] & 0xFF;
    }

    @Test
    void LUT长度是1024字节且按RGBA排列() {
        byte[] lut = ColorMapping.GRAYSCALE.toLut();
        assertEquals(ColorMapping.LUT_SIZE * 4, lut.length);
        assertEquals(1024, lut.length, "1×256 的 RGBA 就是 1024 个字节");
        // 灰度配色的两端：下标 0 是黑、下标 255 是白（两者都是不透明的）
        assertEquals(0, channel(lut, 0, 0), "第 0 个纹素的 R");
        assertEquals(255, channel(lut, 0, 3), "第 0 个纹素的 A");
        assertEquals(255, channel(lut, 255, 0));
        assertEquals(255, channel(lut, 255, 3));
    }

    @Test
    void LUT的端点与首末色标一致() {
        ColorMapping mapping = ColorMapping.of(
                new ColorMapping.Stop(0.0, BLACK), new ColorMapping.Stop(1.0, RED));
        byte[] lut = mapping.toLut();
        assertEquals(0x00, channel(lut, 0, 0), "起点应当是黑色");
        assertEquals(0xFF, channel(lut, 255, 0), "终点应当是红色");
        assertEquals(0x00, channel(lut, 255, 1), "通道序写反的话这里会变成 255——"
                + "画面看起来只是「配色不对」，很难往通道序上想");
    }

    @Test
    void 灰度配色的通道单调不减() {
        byte[] lut = ColorMapping.GRAYSCALE.toLut();
        for (int i = 1; i < ColorMapping.LUT_SIZE; i++) {
            assertTrue(channel(lut, i, 0) >= channel(lut, i - 1, 0),
                    "灰度在纹素 " + i + " 处回退了：" + channel(lut, i - 1, 0)
                            + " → " + channel(lut, i, 0));
            assertEquals(channel(lut, i, 0), channel(lut, i, 1), "灰度配色的 R/G/B 必须相等");
            assertEquals(channel(lut, i, 0), channel(lut, i, 2));
            assertEquals(255, channel(lut, i, 3), "灰度配色的 alpha 恒为不透明");
        }
    }

    @Test
    void 越界输入钳位到端点颜色() {
        ColorMapping mapping = ColorMapping.of(
                new ColorMapping.Stop(0.2, BLACK), new ColorMapping.Stop(0.8, WHITE));
        assertEquals(BLACK, mapping.colorAt(-1000.0), "低于 0 必须钳到首个色标");
        assertEquals(BLACK, mapping.colorAt(0.0));
        assertEquals(WHITE, mapping.colorAt(1.0));
        assertEquals(WHITE, mapping.colorAt(1.0000000000000002),
                "数据侧的四舍五入很容易给出略大于 1 的值，钳位是唯一挡住它的东西");
        assertEquals(WHITE, mapping.colorAt(Double.MAX_VALUE));
    }

    @Test
    void 中点等于两个色标的线性插值() {
        ColorMapping mapping = ColorMapping.of(
                new ColorMapping.Stop(0.0, 0xFF000000), new ColorMapping.Stop(1.0, 0xFFFFFFFF));
        int middle = mapping.colorAt(0.5);
        assertEquals(128, (middle >>> 16) & 0xFF, 0.5,
                "0 到 255 的中点是 127.5，四舍五入到 128");
        assertEquals(128, middle & 0xFF);
        assertEquals(255, (middle >>> 24) & 0xFF);

        // 三个色标、不等距：位置 0.5 落在第二段
        ColorMapping three = ColorMapping.of(
                new ColorMapping.Stop(0.0, 0xFF000000),
                new ColorMapping.Stop(0.5, 0xFF808080),
                new ColorMapping.Stop(1.0, 0xFFFFFFFF));
        assertEquals(0x80, (three.colorAt(0.5) >>> 16) & 0xFF, 0.5, "色标点上的值必须精确");
        assertEquals(0xC0, (three.colorAt(0.75) >>> 16) & 0xFF, 0.5,
                "0.5 到 1.0 的中点是 0x80 与 0xFF 的平均");
    }

    @Test
    void 色标位置必须非递减且落在0到1() {
        assertThrows(IllegalArgumentException.class, () -> ColorMapping.of(
                new ColorMapping.Stop(0.8, BLACK), new ColorMapping.Stop(0.2, WHITE)),
                "位置倒序必须构造时就拒绝：插值时区间长度为负，结果是一段乱跳的颜色");
        assertThrows(IllegalArgumentException.class,
                () -> ColorMapping.of(new ColorMapping.Stop(-0.1, BLACK)));
        assertThrows(IllegalArgumentException.class,
                () -> ColorMapping.of(new ColorMapping.Stop(1.1, BLACK)));
        assertThrows(IllegalArgumentException.class, () -> ColorMapping.of(),
                "一个色标都没有，整张 LUT 无从谈起");
    }

    @Test
    void 只有一个色标时整张LUT是那个颜色() {
        ColorMapping mapping = ColorMapping.of(new ColorMapping.Stop(0.5, RED));
        byte[] lut = mapping.toLut();
        for (int i = 0; i < ColorMapping.LUT_SIZE; i++) {
            assertEquals(0xFF, channel(lut, i, 0));
            assertEquals(0x00, channel(lut, i, 1));
            assertEquals(0x00, channel(lut, i, 2));
            assertEquals(0xFF, channel(lut, i, 3));
        }
        assertEquals(RED, mapping.colorAt(0.0));
        assertEquals(RED, mapping.colorAt(1.0));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ColorMappingTest`
Expected: 编译失败 — `找不到符号: 类 ColorMapping`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/ColorMapping.java`：

```java
package com.bingbaihanji.jfgl.chart;

import java.util.Arrays;

/**
 * 科学配色：一组色标 → 一张 1×256 的 RGBA 查找表。
 *
 * <h2>为什么要归一化成 LUT</h2>
 * <p>JFGL 已定的方向是<strong>所有 Paint 归一化为纹理</strong>（纯色 = 超白色纹理 + 顶点颜色，
 * 渐变 = 1×256 LUT）。热力图因此从 fxcharts 的"整幅逐像素重算颜色、每个像素 new 一个
 * Color 对象"变成"换一张纹理"——规格 §7 说这是收益最大的一条。
 *
 * <p>换配色 = 换纹理 = 一次 {@code glTexSubImage2D}，与数据量无关。
 *
 * <h2>为什么是 byte[1024] 而不是 int[256]</h2>
 * <p>上传纹理要的就是字节数组。{@code int[256]} 到 {@code byte[1024]} 的转换放在
 * 渲染热路径上纯属浪费——这里生成一次就不动了。排列是 <strong>RGBA</strong>，
 * 与 GL 的 {@code GL_RGBA} 上传格式一致。
 *
 * <h2>通道不做预乘</h2>
 * <p>LUT 里存的是直通（straight）alpha。本项目的管线是预乘混合
 * （{@code GL_ONE} / {@code GL_ONE_MINUS_SRC_ALPHA}），因此配色若带透明度，
 * 采样之后由渲染侧预乘。放在 LUT 里预乘是错的：那会让"换配色"与"改全局透明度"
 * 两件事互相污染。
 *
 * <p>纯计算、零依赖，因此可以彻底单测。
 */
public final class ColorMapping {

    /** LUT 的纹素个数。 */
    public static final int LUT_SIZE = 256;

    /**
     * 一个色标。
     *
     * @param position 位置，{@code [0, 1]}
     * @param argb     颜色，{@code 0xAARRGGBB}（与本项目其它地方一致）
     */
    public record Stop(double position, int argb) {
        public Stop {
            if (!(position >= 0.0 && position <= 1.0)) {
                throw new IllegalArgumentException("色标位置必须在 [0,1] 内，实际为 " + position);
            }
        }
    }

    /** 灰度：从黑到白。任何需要"看得见就行"的场合都可以先拿它顶。 */
    public static final ColorMapping GRAYSCALE = of(
            new Stop(0.0, 0xFF000000),
            new Stop(1.0, 0xFFFFFFFF));

    /**
     * 红外配色（4 个关键节点的近似）。
     *
     * <p>经典的 infrared 调色板是一张 256 项的离散表；这里给的是它的四个关键节点。
     * 要原样复刻就把整张表铺进来——{@link Stop} 数组能表达任意长度，
     * 而 LUT 的生成代价与色标个数是线性关系。
     */
    public static final ColorMapping INFRARED_4 = of(
            new Stop(0.00, 0xFF000000),
            new Stop(0.33, 0xFF8B0000),
            new Stop(0.66, 0xFFFF6A00),
            new Stop(1.00, 0xFFFFFFFF));

    private final Stop[] stops;

    private ColorMapping(Stop[] stops) {
        if (stops.length == 0) {
            throw new IllegalArgumentException("至少要有一个色标");
        }
        for (int i = 1; i < stops.length; i++) {
            if (stops[i].position() < stops[i - 1].position()) {
                throw new IllegalArgumentException(
                        "色标位置必须非递减：第 " + (i - 1) + " 个是 " + stops[i - 1].position()
                                + "，第 " + i + " 个是 " + stops[i].position()
                                + "。倒序会让插值区间长度为负，结果是一段乱跳的颜色");
            }
        }
        this.stops = stops;
    }

    /**
     * 由色标构造。
     *
     * @param stops 色标，至少一个；位置必须非递减且落在 {@code [0,1]} 内
     * @return 配色
     * @throws IllegalArgumentException 色标为空或位置不合法时
     */
    public static ColorMapping of(Stop... stops) {
        if (stops == null) {
            throw new IllegalArgumentException("stops 不能为 null");
        }
        return new ColorMapping(stops.clone());
    }

    /**
     * 取 {@code t} 处的颜色。
     *
     * <p>{@code t} 在 {@code [0,1]} 之外时<strong>钳到端点颜色</strong>：
     * 数据一侧的四舍五入很容易给出 {@code 1.0000000000000002}，钳位是唯一挡住它的东西。
     *
     * @param t 归一化位置
     * @return 颜色，{@code 0xAARRGGBB}
     */
    public int colorAt(double t) {
        Stop first = stops[0];
        Stop last = stops[stops.length - 1];
        if (!(t > first.position())) {
            return first.argb();
        }
        if (t >= last.position()) {
            return last.argb();
        }
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i].position()) {
                Stop a = stops[i - 1];
                Stop b = stops[i];
                double span = b.position() - a.position();
                double fraction = span <= 0 ? 0.0 : (t - a.position()) / span;
                return lerp(a.argb(), b.argb(), fraction);
            }
        }
        return last.argb();
    }

    /**
     * 生成 1×256 的 RGBA 查找表。
     *
     * <p>第 {@code i} 个纹素取 {@code t = i / 255}，因此首末两个纹素恰好落在
     * 首末色标上——端点对不上是这类函数最常见的错，而它只表现为"配色整体偏了一点"。
     *
     * @return {@code byte[1024]}，按 RGBA 排列
     */
    public byte[] toLut() {
        byte[] lut = new byte[LUT_SIZE * 4];
        for (int i = 0; i < LUT_SIZE; i++) {
            int argb = colorAt(i / (double) (LUT_SIZE - 1));
            lut[i * 4] = (byte) ((argb >>> 16) & 0xFF);
            lut[i * 4 + 1] = (byte) ((argb >>> 8) & 0xFF);
            lut[i * 4 + 2] = (byte) (argb & 0xFF);
            lut[i * 4 + 3] = (byte) ((argb >>> 24) & 0xFF);
        }
        return lut;
    }

    /** 返回色标的副本，供渲染侧判断"配色是否变了"。 */
    public Stop[] stops() {
        return Arrays.copyOf(stops, stops.length);
    }

    private static int lerp(int argbA, int argbB, double fraction) {
        int alpha = mix((argbA >>> 24) & 0xFF, (argbB >>> 24) & 0xFF, fraction);
        int red = mix((argbA >>> 16) & 0xFF, (argbB >>> 16) & 0xFF, fraction);
        int green = mix((argbA >>> 8) & 0xFF, (argbB >>> 8) & 0xFF, fraction);
        int blue = mix(argbA & 0xFF, argbB & 0xFF, fraction);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int mix(int a, int b, double fraction) {
        return (int) Math.round(a + (b - a) * fraction);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=ColorMappingTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 256, Failures: 0, Errors: 0, Skipped: 2`（249 + 7）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 4 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `toLut` 里的 `i / (LUT_SIZE - 1)` 改成 `i / LUT_SIZE`（末纹素不再落在末色标上） | `LUT的端点与首末色标一致`（末纹素会停在 0.996 处，颜色差一点点） |
| `colorAt` 去掉两个端点钳位（越界时继续走插值循环） | `越界输入钳位到端点颜色` |
| `toLut` 的通道偏移写成 ARGB（alpha 放第 0 字节） | `LUT端点与首末色标一致`、`灰度配色的通道单调不减` |
| 构造期去掉"位置非递减"的校验 | `色标位置必须非递减且落在0到1` |

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/ColorMapping.java \
        src/test/java/com/bingbaihanji/jfgl/chart/ColorMappingTest.java
git commit -F - <<'EOF'
feat(chart): ColorMapping —— 科学配色归一化成 1×256 LUT

热力图换配色从 fxcharts 的"整幅逐像素重算、每个像素 new 一个 Color"变成"换一张
纹理"：一次 glTexSubImage2D，与数据量无关。规格 §7 说这是收益最大的一条，而它
能成立正是因为 JFGL 已定"所有 Paint 归一化为纹理"。

输出直接是 byte[1024]（RGBA）：上传纹理要的就是字节数组，int[256] → byte[1024]
的转换放在渲染热路径上纯属浪费。

第 i 个纹素取 t = i/255，首末纹素恰好落在首末色标上——端点对不上是这类函数最
常见的错，而它只表现为"配色整体偏了一点"，所以有专门的断言。

colorAt 对 [0,1] 之外的值钳到端点：数据侧的四舍五入很容易给出 1.0000000000000002。
LUT 存直通 alpha 不预乘：放在 LUT 里预乘会让"换配色"与"改全局透明度"互相污染。

7 条单测 + 4 条变异验证（端点、钳位、通道序、位置校验）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 8: ChartType / Series / Layer / Chart 装配

**图型是属性组合，不是类层级**（规格 §9，抄 chart-fx）：`ErrorStyle × LineStyle × 布尔量`
一个 switch 覆盖大部分 2D 图型，**零新类出很多图型**。

规格 §12 R3 明确承认这套组合会随图型增多而膨胀，并给出边界：
**属性组合管"同一套顶点怎么画"，独立渲染器管"顶点怎么来"**。热力图/眼图属于后者。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/ChartType.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/Series.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/Layer.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/Chart.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/ChartTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/ChartTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Chart} / {@link Layer} / {@link Series} / {@link ChartType} 的装配测试。
 *
 * <p>只断言一件事：<strong>装配顺序即绘制顺序</strong>。
 * 本项目 CLAUDE.md 里写死的规则是"绘制顺序即 2D 的 z 序，绝不重排"，
 * 而装配层要是把层内顺序与层间顺序搞反（先排完所有层的第一个系列、再排第二个……），
 * 画面会变成"后加的层被压在下面"，看起来像是渲染器的 bug。
 */
class ChartTest {

    private static ArrayChartData data() {
        return new ArrayChartData(new AxisRange[]{AxisRange.of(0, 10)},
                new double[][]{{1, 2, 3}});
    }

    private static Series series(String name) {
        return new Series(name, data(), ChartType.LINE);
    }

    @Test
    void 图层与系列的顺序就是绘制顺序() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));
        chart.addLayer("底图").add(series("a"));
        chart.addLayer("数据").add(series("b")).add(series("c"));

        List<Series> flat = chart.allSeries();
        assertEquals(3, flat.size());
        assertEquals(List.of("a", "b", "c"),
                flat.stream().map(Series::name).toList(),
                "顺序必须是「层序优先、层内其次」。反过来（先排完所有层的第一个系列）"
                        + "会让后加的层被压在下面，看起来像渲染器画错了");
        assertEquals("底图", chart.layers().get(0).name());
        assertEquals("数据", chart.layers().get(1).name());
        assertEquals(List.of("b", "c"),
                chart.layers().get(1).series().stream().map(Series::name).toList());
    }

    @Test
    void 同一层里重复添加同一个系列会抛异常() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));
        Series s = series("a");
        Layer layer = chart.addLayer("层");
        layer.add(s);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> layer.add(s),
                "同一个系列加两次会被画两遍——在带透明度的图元上表现为颜色莫名变深，"
                        + "而且没有任何报错");
        assertTrue(e.getMessage().contains("已经在这个图层里"), "消息要说清原因：" + e.getMessage());
    }

    @Test
    void 非法装配会抛异常() {
        Chart chart = new Chart(new Axis(AxisType.LINEAR, AxisRange.of(0, 10)));
        assertThrows(IllegalArgumentException.class, () -> chart.addLayer(""));
        assertThrows(IllegalArgumentException.class, () -> new Layer(null));
        assertThrows(IllegalArgumentException.class, () -> chart.addLayer("层").add(null));
        assertThrows(IllegalArgumentException.class, () -> new Series("s", null, ChartType.LINE));
        assertThrows(IllegalArgumentException.class, () -> new Series("s", data(), null));
        assertThrows(IllegalArgumentException.class, () -> chart.axis(3));
        assertEquals(1, chart.axes().size());
        assertEquals(AxisType.LINEAR, chart.axis(0).type());
        assertThrows(IllegalArgumentException.class, () -> new Chart());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ChartTest`
Expected: 编译失败 — `找不到符号: 类 Chart`

- [ ] **Step 3: 写 ChartType**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/ChartType.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 图型标签：<strong>属性组合，不是类层级</strong>。
 *
 * <h2>为什么不给每种图型一个类</h2>
 * <p>这条抄 chart-fx：它用 {@code ErrorStyle × LineStyle × 布尔量} 覆盖了大部分 2D 图型，
 * <strong>零新类出很多图型</strong>。要是给每种图型一个类，每个类都要重新回答
 * "数据怎么取、轴怎么用、顶点怎么发"，而这些问题的答案在两两之间几乎相同。
 *
 * <h2>边界（规格 §12 R3）</h2>
 * <p>属性组合管"<strong>同一套顶点怎么画</strong>"，独立渲染器管"<strong>顶点怎么来</strong>"。
 * 热力图与眼图属于后者：它们的顶点不是"每个样本一个点"，而是"每个像素列一个直方图"，
 * 不是属性组合能表达的，会是独立的 {@link SeriesRenderer}。
 * 枚举里留着它们的常量，是为了让系列能声明自己的图型，而不是让一个折线渲染器
 * 去猜"这个系列我画不了"。
 *
 * <h2>渲染器必须明确报错，不许静默不画</h2>
 * <p>渲染器遇到自己不支持的图型，必须抛异常或明确报错。
 * <strong>静默什么都不画正是本项目最典型的"静默错误输出"</strong>：
 * 画面里少了一条曲线，与"这条曲线没数据"在视觉上完全一样。
 */
public enum ChartType {

    /** 折线：样本之间连线。 */
    LINE(Flag.CONNECTS),

    /** 散点：只画标记点，不连线。 */
    SCATTER(Flag.MARKERS),

    /** 折线 + 标记点。 */
    LINE_AND_MARKERS(Flag.CONNECTS | Flag.MARKERS),

    /** 阶梯线：连线按"先横后竖"走，适合枚举值与计数值。 */
    STEP(Flag.CONNECTS | Flag.STEPPED),

    /** 面积图：折线下方填充。 */
    AREA(Flag.CONNECTS | Flag.FILLS),

    /** 柱状：每个样本一个矩形，不连线。 */
    BAR(Flag.BARS),

    /** 热力图：顶点来自密度/列直方图，由独立渲染器实现。 */
    HEATMAP(0),

    /** 瀑布图：滚动的一列一列，由独立渲染器实现。 */
    WATERFALL(0);

    private static final class Flag {
        static final int CONNECTS = 1;
        static final int MARKERS = 2;
        static final int STEPPED = 4;
        static final int FILLS = 8;
        static final int BARS = 16;

        private Flag() {
        }
    }

    private final int flags;

    ChartType(int flags) {
        this.flags = flags;
    }

    /** 是否把样本连成折线。 */
    public boolean connectsSamples() {
        return (flags & Flag.CONNECTS) != 0;
    }

    /** 是否在样本位置画标记点。 */
    public boolean drawsMarkers() {
        return (flags & Flag.MARKERS) != 0;
    }

    /** 是否按"先横后竖"走阶梯。 */
    public boolean stepped() {
        return (flags & Flag.STEPPED) != 0;
    }

    /** 是否填充折线下方。 */
    public boolean fillsUnderCurve() {
        return (flags & Flag.FILLS) != 0;
    }

    /** 是否画柱。 */
    public boolean drawsBars() {
        return (flags & Flag.BARS) != 0;
    }

    /**
     * 是否是"折线族"图型：顶点来自逐样本的一个点，可以用同一个折线渲染器画。
     *
     * <p>返回 false 的图型（热力图、瀑布图）必须由各自的独立渲染器处理。
     *
     * @return 折线族为 true
     */
    public boolean polylineFamily() {
        return connectsSamples() || drawsMarkers() || drawsBars();
    }
}
```

- [ ] **Step 4: 写 Series / Layer / Chart**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/Series.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 一个数据系列：数据 + 样式 + 图型标签。
 *
 * <h2>系列不持有轴</h2>
 * <p>轴由 {@link Chart} 按维度下标持有，{@link SeriesRenderer#render} 收到的
 * {@code axes[]} 与数据维度一一对应。多 Y 轴因此只是"多给几根 {@code Axis}"——
 * 本期不做系列级的轴选择，但它不影响签名（按维度下标对齐已经够表达多轴了）。
 *
 * <h2>样式是可变字段 + 链式 setter</h2>
 * <p>样式会被交互（改颜色、换配色）随时改动，做成不可变对象只会让调用方写一堆
 * "复制一份、改一个字段、再塞回去"。这些 setter 不校验范围之外的语义
 * （比如线宽为负），因为渲染侧对它们的处理是明确的（负线宽 = 不画线），
 * 而不是一个静默的错值。
 */
public final class Series {

    private final String name;

    private final ChartData data;

    private final ChartType type;

    private int color = 0xFFFFFFFF;

    private float lineWidth = 1f;

    private float markerSize = 3f;

    /** 热力图/密度图用的配色；折线族不需要，为 null。 */
    private ColorMapping colorMapping;

    /**
     * 构造。
     *
     * @param name 系列名（图例用），不能为空
     * @param data 数据，不能为 null
     * @param type 图型标签，不能为 null
     * @throws IllegalArgumentException 参数非法时
     */
    public Series(String name, ChartData data, ChartType type) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("系列名不能为空");
        }
        if (data == null) {
            throw new IllegalArgumentException("系列的数据不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("图型标签不能为 null");
        }
        this.name = name;
        this.data = data;
        this.type = type;
    }

    /** 系列名。 */
    public String name() {
        return name;
    }

    /** 数据。 */
    public ChartData data() {
        return data;
    }

    /** 图型标签。 */
    public ChartType type() {
        return type;
    }

    /** 主色，ARGB。 */
    public int color() {
        return color;
    }

    /** 设置主色。 */
    public Series color(int argb) {
        this.color = argb;
        return this;
    }

    /** 线宽（用户坐标单位）。 */
    public float lineWidth() {
        return lineWidth;
    }

    /** 设置线宽。 */
    public Series lineWidth(float width) {
        this.lineWidth = width;
        return this;
    }

    /** 标记点半径（用户坐标单位）。 */
    public float markerSize() {
        return markerSize;
    }

    /** 设置标记点半径。 */
    public Series markerSize(float size) {
        this.markerSize = size;
        return this;
    }

    /** 配色；未设置时为 null。 */
    public ColorMapping colorMapping() {
        return colorMapping;
    }

    /** 设置配色（热力图/密度图用）。 */
    public Series colorMapping(ColorMapping mapping) {
        this.colorMapping = mapping;
        return this;
    }
}
```

创建 `src/main/java/com/bingbaihanji/jfgl/chart/Layer.java`：

```java
package com.bingbaihanji.jfgl.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一层：一组 {@link Series}，按添加顺序绘制。
 *
 * <p>层的意义是<strong>整体控制</strong>（例如底图整层调暗、整层隐藏），
 * 而不是"分组"——z 序仍然由添加顺序决定，与层内顺序是同一条规则。
 */
public final class Layer {

    private final String name;

    private final List<Series> series = new ArrayList<>();

    /**
     * 构造。
     *
     * @param name 层名，不能为空
     * @throws IllegalArgumentException 层名为空时
     */
    public Layer(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("图层名不能为空");
        }
        this.name = name;
    }

    /** 层名。 */
    public String name() {
        return name;
    }

    /**
     * 往这一层里加一个系列。添加顺序即绘制顺序。
     *
     * @param s 系列，不能为 null
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 系列为 null，或<strong>同一个系列已经在层里</strong>时
     */
    public Layer add(Series s) {
        if (s == null) {
            throw new IllegalArgumentException("系列不能为 null");
        }
        if (series.contains(s)) {
            throw new IllegalArgumentException(
                    "系列「" + s.name() + "」已经在这个图层里了：再加一次会被画两遍——"
                            + "在带透明度的图元上表现为颜色莫名变深，而且没有任何报错");
        }
        series.add(s);
        return this;
    }

    /**
     * 返回层内的系列，按绘制顺序。
     *
     * @return 不可修改的列表
     */
    public List<Series> series() {
        return Collections.unmodifiableList(series);
    }
}
```

创建 `src/main/java/com/bingbaihanji/jfgl/chart/Chart.java`：

```java
package com.bingbaihanji.jfgl.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 装配根：轴 + 层 + 系列。
 *
 * <h2>它不是场景图节点</h2>
 * <p>规格 §9：JFGL 里 <strong>渲染器是纯函数</strong>（数据 + 轴 → 顶点），
 * 轴、网格、图例各自独立，<strong>都不是场景图节点</strong>。
 * 这一条是本项目相对 chart-fx 的刻意取舍——它的 {@code AbstractRenderer extends Parent}，
 * 渲染器本身就是节点，逻辑与节点树耦合。
 *
 * <h2>顺序就是一切</h2>
 * <p>{@link #allSeries()} 的顺序是"层序优先、层内其次"，它<strong>就是绘制顺序</strong>，
 * 也就是 2D 的 z 序。本项目 CLAUDE.md 的规则是"绝不重排"，这条顺序因此有测试钉着。
 *
 * <h2>线程</h2>
 * <p>非线程安全。装配发生在应用线程，绘制发生在 GL 线程；本期约定两者不同时进行
 * （数据的并发由 {@link RingChartData} 自己负责，与装配无关）。
 */
public final class Chart {

    private final List<Axis> axes = new ArrayList<>();

    private final List<Layer> layers = new ArrayList<>();

    /**
     * 构造，至少给一根轴。
     *
     * @param axes 各维度的轴，顺序即维度下标
     * @throws IllegalArgumentException 一根轴都没有时
     */
    public Chart(Axis... axes) {
        if (axes == null || axes.length == 0) {
            throw new IllegalArgumentException("至少要给一根轴");
        }
        for (Axis axis : axes) {
            if (axis == null) {
                throw new IllegalArgumentException("轴不能为 null");
            }
            this.axes.add(axis);
        }
    }

    /**
     * 追加一根轴（多一个维度）。
     *
     * @param axis 轴
     * @return 自身，便于链式调用
     */
    public Chart addAxis(Axis axis) {
        if (axis == null) {
            throw new IllegalArgumentException("轴不能为 null");
        }
        axes.add(axis);
        return this;
    }

    /**
     * 按维度下标取轴。
     *
     * @param dim 维度下标
     * @return 轴
     * @throws IllegalArgumentException 下标越界时
     */
    public Axis axis(int dim) {
        if (dim < 0 || dim >= axes.size()) {
            throw new IllegalArgumentException(
                    "维度下标越界：" + dim + "，共 " + axes.size() + " 根轴");
        }
        return axes.get(dim);
    }

    /**
     * 全部轴，按维度下标排列。它的长度就是数据的维度数——
     * {@link SeriesRenderer#render} 收到的 {@code axes[]} 正是这个列表。
     *
     * @return 不可修改的列表
     */
    public List<Axis> axes() {
        return Collections.unmodifiableList(axes);
    }

    /**
     * 加一层。层的添加顺序即绘制顺序。
     *
     * @param name 层名
     * @return 新建的层
     * @throws IllegalArgumentException 层名为空时
     */
    public Layer addLayer(String name) {
        Layer layer = new Layer(name);
        layers.add(layer);
        return layer;
    }

    /**
     * 全部层，按添加顺序。
     *
     * @return 不可修改的列表
     */
    public List<Layer> layers() {
        return Collections.unmodifiableList(layers);
    }

    /**
     * 全部系列，按绘制顺序（层序优先、层内其次）。
     *
     * @return 不可修改的列表
     */
    public List<Series> allSeries() {
        List<Series> out = new ArrayList<>();
        for (Layer layer : layers) {
            out.addAll(layer.series());
        }
        return Collections.unmodifiableList(out);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -Dtest=ChartTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 259, Failures: 0, Errors: 0, Skipped: 2`（256 + 3）

- [ ] **Step 7: 变异验证**（每条做完立刻还原，共 2 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `allSeries()` 按"层内优先"排（外层循环层内下标、内层循环层） | `图层与系列的顺序就是绘制顺序` |
| `Layer.add` 去掉"同一个系列已在层里"的判断 | `同一层里重复添加同一个系列会抛异常` |

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/ChartType.java \
        src/main/java/com/bingbaihanji/jfgl/chart/Series.java \
        src/main/java/com/bingbaihanji/jfgl/chart/Layer.java \
        src/main/java/com/bingbaihanji/jfgl/chart/Chart.java \
        src/test/java/com/bingbaihanji/jfgl/chart/ChartTest.java
git commit -F - <<'EOF'
feat(chart): 图型标签与图表装配（ChartType / Series / Layer / Chart）

图型是**属性组合**不是类层级（抄 chart-fx）：连接/标记/阶梯/填充/柱 五个位覆盖
大部分 2D 图型，零新类出很多图型。边界按规格 §12 R3 划死：属性组合管"同一套顶点
怎么画"，独立渲染器管"顶点怎么来"——热力图与瀑布图属于后者，枚举里留着常量只为
让系列能声明自己，而不是让折线渲染器去猜"这个系列我画不了"。

渲染器遇到不支持的图型必须明确报错，不许静默不画：画面里少一条曲线，与"这条曲线
没数据"在视觉上完全一样，而后者不是缺陷、前者是。

Chart 不是场景图节点（规格 §9）：渲染器是纯函数，轴/网格/图例各自独立。
这一条相对 chart-fx 是刻意的——它的 AbstractRenderer extends Parent，渲染器本身
就是节点，逻辑与节点树耦合。

allSeries() 的顺序（层序优先、层内其次）就是绘制顺序即 z 序，有测试钉着：
顺序搞反会让后加的层被压在下面，看起来像渲染器画错了。同一层里重复添加同一个
系列会被拒——画两遍在带透明度的图元上表现为颜色莫名变深，且没有任何报错。

3 条单测 + 2 条变异验证。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 9: SeriesRenderer 接口 + 包隔离守卫

**这是 ① 与 ② 的接缝**（规格 §9）。

### 关于 `RenderContext`：为什么是空接口而不是 `Object` 占位

规格明说 `RenderContext` **不属于 ①**，它是 ② 提供的"给你一支笔"的上下文
（顶点写入器、当前帧号、纹理槽位等），"① 只声明签名，② 给它血肉"。
本计划的做法是：**① 里定义 `RenderContext` 为空接口**，② 定义
`GLRenderContext extends RenderContext` 并让它的渲染器实现 `SeriesRenderer`。

| 做法 | 后果 |
|------|------|
| `Object` 占位 | ② 必须回头**改 ① 的签名**（把 `Object` 换成真实类型）。已经落地的实现全部要动——正是"签名承诺"要避免的事 |
| **空接口（本计划）** | 签名现在就终局，② 只**扩展**不**修改**。代价是 ② 的实现里有一次向下转型，而那次转型被限制在每个渲染器的第一行，且类型在编译期就能查（② 是唯一的实现方） |

空接口还带来一个**可机械检查**的性质：**它一个方法都不能有**。
有一个方法，就说明 ① 偷看了 GL 的细节。这条写成断言（见下面 Step 3）。
**② 若确实需要给 `RenderContext` 本身加方法，那说明 ② 的东西漏进了 ①，应当回来讨论**——
就像计划开头那条分界线说的。② 要加东西就加在子接口上。

**Files:**
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/RenderContext.java`
- Create: `src/main/java/com/bingbaihanji/jfgl/chart/SeriesRenderer.java`
- Test: `src/test/java/com/bingbaihanji/jfgl/chart/ChartPackageIsolationTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/jfgl/chart/ChartPackageIsolationTest.java`：

```java
package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机械地守住 {@code chart/} 的两条边界。
 *
 * <p>本项目没有静态分析插件（{@code pom.xml} 里没有 checkstyle / archunit 之类），
 * 所以把规则写成测试——这也是 {@code GeomPackageIsolationTest} 的做法。
 *
 * <p>边界为什么值钱：① 的全部价值在于"能脱离 GL 上下文单测"。一旦有一个文件
 * {@code import} 了 GL 相关的东西，它就不再可测，而**这个代价是逐渐累积的**——
 * 第一个越界的人会觉得"就一次，无伤大雅"。
 */
class ChartPackageIsolationTest {

    /** chart 包的源码目录（相对项目根，surefire 的工作目录就是项目根）。 */
    private static final Path CHART_SOURCE_DIR =
            Path.of("src", "main", "java", "com", "bingbaihanji", "jfgl", "chart");

    /** chart/ 只允许依赖这些兄弟包。 */
    private static final Set<String> ALLOWED = Set.of("chart", "math", "util");

    private static final Pattern JFGL_PACKAGE =
            Pattern.compile("com\\.bingbaihanji\\.jfgl\\.([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    void chart包只依赖math与util() throws IOException {
        assertTrue(Files.isDirectory(CHART_SOURCE_DIR),
                "找不到 chart 源码目录：" + CHART_SOURCE_DIR.toAbsolutePath()
                        + "（工作目录应为项目根，找错了目录会让本测试形同虚设）");

        List<Path> sources;
        try (Stream<Path> files = Files.list(CHART_SOURCE_DIR)) {
            sources = files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        }
        assertTrue(sources.size() >= 16,
                "chart 包应至少有 16 个源文件（规格 §4 列了 15 个，外加 RenderContext），实际 "
                        + sources.size() + " 个");

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String[] lines = Files.readString(source, StandardCharsets.UTF_8).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = JFGL_PACKAGE.matcher(lines[i]);
                while (matcher.find()) {
                    if (!ALLOWED.contains(matcher.group(1))) {
                        violations.add(CHART_SOURCE_DIR.relativize(source) + ":" + (i + 1)
                                + " -> " + lines[i].strip());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "chart/ 是零 GL 依赖的纯计算层，只能依赖 math/ 与 util/。"
                        + "以下位置引用了别的包：\n" + String.join("\n", violations)
                        + "\n若发现必须引用 gl/、renderer/、text/、geom/ 才能写下去，"
                        + "说明 ① 的分界线画错了，应当停下来重新讨论");
    }

    @Test
    void RenderContext是空接口() {
        Method[] methods = RenderContext.class.getDeclaredMethods();
        assertEquals(0, methods.length,
                "RenderContext 不该有任何方法，实际有 " + methods.length + " 个："
                        + java.util.Arrays.toString(methods)
                        + "。它属于 ②（渲染后端），① 只声明这个类型存在。"
                        + "② 要加东西请定义子接口（GLRenderContext extends RenderContext），"
                        + "在原地给它加方法等于把 ② 的东西漏进了 ①");
        assertEquals(0, RenderContext.class.getDeclaredFields().length,
                "RenderContext 也不该有任何字段");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=ChartPackageIsolationTest`
Expected: 编译失败 — `找不到符号: 类 RenderContext`

- [ ] **Step 3: 写两个接口**

创建 `src/main/java/com/bingbaihanji/jfgl/chart/RenderContext.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 渲染上下文：<strong>① 只声明这个类型存在，不声明它有什么</strong>。
 *
 * <h2>它是空的，而且必须保持空</h2>
 * <p>规格 §9：{@code RenderContext} <strong>不属于 ①</strong>。它是渲染后端（子项目 ②）
 * 提供的"给你一支笔"的上下文——顶点写入器、当前帧号、纹理槽位等等。
 * ① 只声明签名，② 给它血肉。这样 ① 保持零 GL 依赖，而 ② 可以自由决定上下文里放什么。
 *
 * <p><strong>为什么是空接口而不是 {@code Object} 占位</strong>：用 {@code Object} 的话，
 * ② 必须回头修改 {@link SeriesRenderer} 的签名，而那时 ② 已经落地了——
 * 「签名承诺」的全部意义就是让这件事不必发生。空接口让签名现在就终局：
 * ② 定义 {@code GLRenderContext extends RenderContext}，
 * 实现里做一次向下转型（它是唯一的实现方，类型在编译期就能查），
 * 转型被限制在每个渲染器的第一行。
 *
 * <p><strong>一个方法都不能加。</strong>这条由 {@code ChartPackageIsolationTest} 断言：
 * 在原地加方法等于把 ② 的东西漏进 ①。② 要加东西请加在子接口上；
 * 若确实需要给本接口加方法，那说明分界线画错了，应当停下来重新讨论。
 */
public interface RenderContext {
}
```

创建 `src/main/java/com/bingbaihanji/jfgl/chart/SeriesRenderer.java`：

```java
package com.bingbaihanji.jfgl.chart;

/**
 * 渲染器：数据 + 轴 → 顶点。**自己不持有数据，也不认识场景图。**
 *
 * <h2>它是纯函数</h2>
 * <p>规格 §9：JFGL 里 {@code SeriesRenderer} 是纯函数。这个签名里没有 GL、
 * 没有场景图节点、没有 JavaFX。<strong>轴、网格、图例各自独立，都不是场景图节点。</strong>
 *
 * <p>避开 chart-fx 的三个坑（规格 §9）：它的 {@code Renderer} 接口混了三件事
 * （{@code render()} 绘制、{@code updateAxisRange()} 数据域、{@code getNode()} /
 * {@code drawLegendSymbol()} 场景图），而且 {@code AbstractRenderer extends Parent}——
 * 渲染器本身是场景图节点，逻辑与节点树耦合。
 *
 * <h2>实现者必须遵守的两条</h2>
 * <ol>
 *   <li><strong>只画脏区间。</strong>{@link ChartData#dirtyRange(long)} 是第一步就调用的东西：
 *       数据是 <strong>GPU 常驻 + 增量上传</strong>的，每帧重传整个窗口会把增量上传的好处
 *       全部抵消（规格 §5.1、§8）。顶点缓冲也应该是环，滚动用一个 uniform 表达，
 *       而不是每帧重建顶点（规格 §8）。</li>
 *   <li><strong>遇到 NaN 就断开折线，遇到不支持的图型就明确报错。</strong>
 *       数据里的 NaN（无论是丢包还是传感器故障）与
 *       {@link RingChartData#GAP} 是同一种东西：<strong>连过去的那条直线是假的，
 *       它显示了一个不存在的信号</strong>——这比不显示更糟，而且看起来完全正常。
 *       不支持 {@link ChartType} 时静默不画同样是"静默错误输出"。</li>
 * </ol>
 *
 * <h2>② 怎么实现它</h2>
 * <p>② 定义 {@code GLRenderContext extends RenderContext}，在渲染器第一行转型取得
 * 顶点写入器与当前帧状态，其余全部是纯算术。① 不需要知道这些。
 */
@FunctionalInterface
public interface SeriesRenderer {

    /**
     * 把一个系列画出来。
     *
     * @param ctx   渲染上下文，由渲染后端（②）提供并实现
     * @param data  系列的数据
     * @param series 系列（图型标签与样式都在这里）
     * @param axes  各维度的轴，长度等于数据的维度数，下标即维度
     */
    void render(RenderContext ctx, ChartData data, Series series, Axis[] axes);
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=ChartPackageIsolationTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 261, Failures: 0, Errors: 0, Skipped: 2`（259 + 2）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 2 条）

| 变异 | 应失败的测试 |
|------|--------------|
| 在 `chart/` 任意一个文件里加一行 `import com.bingbaihanji.jfgl.renderer.ViewTransform;`（**加完立刻删掉**，不要提交） | `chart包只依赖math与util` |
| 给 `RenderContext` 加一个方法（例如 `int frameNumber();`） | `RenderContext是空接口` |

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bingbaihanji/jfgl/chart/RenderContext.java \
        src/main/java/com/bingbaihanji/jfgl/chart/SeriesRenderer.java \
        src/test/java/com/bingbaihanji/jfgl/chart/ChartPackageIsolationTest.java
git commit -F - <<'EOF'
feat(chart): 与渲染后端的接缝（RenderContext + SeriesRenderer）+ 包隔离守卫

RenderContext 定义成**空接口**而不是 Object 占位：用 Object 的话 ② 必须回头改
SeriesRenderer 的签名，而那时 ② 已经落地了——签名承诺的全部意义就是让这件事
不必发生。空接口让签名现在就终局，② 定义子接口扩展它，代价是渲染器第一行的
一次向下转型（② 是唯一的实现方，类型编译期可查）。

空接口还有一个可机械检查的性质：一个方法都不能有。有一条断言钉着它——
在原地加方法等于把 ② 的东西漏进 ①；② 要加就加在子接口上。

SeriesRenderer 是纯函数：不持有数据、不认识场景图。避开 chart-fx 的坑——它的
Renderer 接口混了绘制、数据域、场景图三件事，而且 AbstractRenderer extends Parent，
渲染器本身就是节点。类文档写死了实现者的两条义务：只画脏区间（否则增量上传的
好处全部抵消）、遇到 NaN 就断开折线、遇到不支持的图型就明确报错（静默不画是
本项目最典型的静默错误输出）。

ChartPackageIsolationTest 机械地守住"chart/ 只依赖 math/ 与 util/"：① 的全部价值
在于能脱离 GL 上下文单测，而越界的代价是逐渐累积的——第一个越界的人会觉得
"就一次，无伤大雅"。做法照抄 GeomPackageIsolationTest（本项目没有静态分析插件）。

2 条单测 + 2 条变异验证。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 10: 文档更新

**这一节的每一条都是"写进文档的行为声明"，必须对着代码核实过再写。**

> **⚠️ 先核实，再落笔。** 下面给的是**已经对着当前代码核实过**的文本；
> 若实现过程中有偏离（例如某个坑没踩到、某个类没按计划写），**照着实际改动调整文字**，
> 不要照抄这里有出入的句子。**不要写一句你没验证过的话。**

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: CLAUDE.md —— 修 GL 版本那条（这是本任务最重要的一步）**

**背景（规格 §3.1 实测）**：用开窗探针实测 `GL_VERSION = 4.6.0 NVIDIA 581.29`
（RTX 3060 Laptop，`MAJOR.MINOR = 4.6`），并**实际编译 + 链接 + dispatch 了一个最小
compute shader**（SSBO 写入，输入 1..8 输出 2..16，端到端验证）。
即：计算着色器、SSBO、`imageStore`、shared memory 原子操作**全部可用**。

**CLAUDE.md 现状**（已核实）：它**没有任何一处写过"GL 3.3"**，也**没有任何一处写过实际版本**；
唯一沾边的是架构图里那行 `GL 抽象 gl.* LWJGL 3.3.6 + openglfx` —— 那里说的是
**LWJGL 的版本**，而它旁边没有任何东西说明"上下文是几"。加上仓库里 5 个着色器都写着
`#version 330 core`，读者很容易得出"管线是 GL 3.3，所以 compute 用不了"的结论——
**而项目里躺着的 `gpu/GPUFFT.java` 正是这么被埋掉的**（规格 §3.2：它写了 `#version 430`
与完整的 Cooley-Tukey radix-2 + SSBO，但没有任何人引用它）。

**做法**：在「## 架构」之前插入一节「## 前置事实（已实测，不要重新猜）」，内容如下：

```markdown
## 前置事实（已实测，不要重新猜）

**实际 GL 上下文是 4.6，不是 3.3。**
开窗探针实测 `GL_VERSION = 4.6.0 NVIDIA 581.29`（RTX 3060 Laptop，`MAJOR.MINOR = 4.6`），
并**实际编译 + 链接 + dispatch 了一个最小 compute shader**（SSBO 写入，输入 1..8
输出 2..16，端到端验证）。即：**计算着色器、SSBO、`imageStore`、shared memory
原子操作全部可用。**

仓库里那 5 个 `#version 330 core` 着色器（都在 `renderer/RenderBatch.java`）能跑，
是因为 4.6 向后兼容，**不是因为上下文是 3.3**。**不要因为版本号写着 330 就以为
compute 用不了**——`gpu/GPUFFT.java`（`#version 430`，Cooley-Tukey radix-2 + SSBO）
就是这么被埋掉的：它一直可用，只是没人引用。
```

- [ ] **Step 2: CLAUDE.md —— 测试一节**

把「### 测试」那一段里的目录清单与计数换成（**数字以实跑为准**，这里是预期值 261）：

```markdown
```
src/test/java/com/bingbaihanji/jfgl/geom/       PathTest、FlattenerTest、TessellatorTest、
                                                TessellatorHoleTest、TessellatorRegressionTest、
                                                StrokeGeneratorTest、StrokeDashTest、
                                                GeomPackageIsolationTest
src/test/java/com/bingbaihanji/jfgl/renderer/   VertexFormatTest、VertexWriterTest、ViewTransformTest、
                                                PickRegistryTest、PickBufferTest
src/test/java/com/bingbaihanji/jfgl/gl/         FramebufferTest、LwjglGLAbstractionTest
src/test/java/com/bingbaihanji/jfgl/text/       SdfGeneratorTest、GlyphAtlasTest、FontFileTest、
                                                GlyphRasterizerTest、TextLayoutTest
src/test/java/com/bingbaihanji/jfgl/chart/      TickGeneratorTest、AxisTest、ArrayChartDataTest、
                                                RingChartDataTest、ChartDataConcurrencyTest、
                                                ColorMappingTest、ChartTest、
                                                ChartPackageIsolationTest
```

当前 **261 个测试，0 失败，2 跳过**（2 个跳过是 `TessellatorRegressionTest` 里两条
`@Disabled` 的已知缺陷）。单测命令：`mvn test -Dtest=类名`。
```

并在紧随其后的说明段落里，把 `chart/` 补进去：

```markdown
`geom/`、`math/`、`util/`、`ViewTransform`、`text/{SdfGenerator, TextLayout}`、`chart/`
都是纯计算、不依赖 GL 上下文，最适合写单测。`gl/Framebuffer`、`renderer/PickBuffer`、
`text/GlyphAtlas` 只依赖 `GLAbstraction` **接口**，用 `src/test/.../gl/FakeGLAbstraction`
这个假实现也能零 GL 上下文单测。`text/{FontFile, GlyphRasterizer}` 依赖 stb 的本地库
（已实测能在 surefire 里加载）。`RenderBatch` 的着色器与 `Gc` 则必须靠校验器。
```

> **`chart/` 的边界要单独写一句**：`chart/` 连 `renderer/` 也不依赖，
> 由 `ChartPackageIsolationTest` 强制；它唯一的跨包依赖是 `math/` 与 `util/`。

- [ ] **Step 3: CLAUDE.md —— 架构图加一层**

在架构图的 L0 那几行里追加一行（放在「文本」之后、「GL 抽象」之前）：

```
    图表            chart.*                                          纯计算，零 GL 依赖
```

并在架构图下方的说明里追加一句：

```markdown
**`chart/` 对 `gl/`、`renderer/`、`text/`、`geom/` 零依赖**，由 `ChartPackageIsolationTest`
强制。① 与渲染后端（②）的接缝只有两个类型：`chart/RenderContext`（空接口，② 定义子接口
扩展它）与 `chart/SeriesRenderer`（纯函数：数据 + 轴 → 顶点）。
```

- [ ] **Step 4: CLAUDE.md —— 新增「图表」一节（与「拾取」「文本」同构，放在「文本」之后）**

```markdown
### 图表

图表框架（子项目 D-①）在 `chart/` 下，**纯计算、零 GL 依赖**：数据容器（`ArrayChartData`
静态 / `RingChartData` 流式）、轴与刻度（`Axis` / `TickGenerator` / `AxisType`）、
配色 LUT（`ColorMapping`）、装配（`Chart` / `Layer` / `Series` / `ChartType`）。
**绘制后端（子项目 ②）尚未实现**，所以现在还没有"画出来"的能力。

```java
// 静态数据：一次性给出，之后整体替换
ArrayChartData data = new ArrayChartData(
        new AxisRange[]{new AxisRange(0, 10, "时间", "s"), new AxisRange(-1, 1, "电压", "V")},
        new double[][]{{0, 1, 2, 3}, {0.1, -0.2, 0.3, 0.0}});

Axis x = new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(800);
Axis y = new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(600);

Chart chart = new Chart(x, y);
chart.addLayer("主").add(new Series("电压", data, ChartType.LINE).color(0xFF00FF00));

Tick[] ticks = x.ticks();          // 主/中/次三级刻度，位置已经装配好
```

- **脏区间是一等公民**：`ChartData.dirtyRange(sinceRevision)` 返回 `[firstDirty, lastDirty)`。
  `revision` 不变时报空——静态数据一次上传后**永不重传**；流式数据只报"新加了 N 个"。
  这是 ② 能做到"GPU 常驻 + 增量上传"的前提，**不是可选优化**。
- **流式数据是 SPSC 环形缓冲**：`RingChartData` 只允许**一个写者**（采集线程）。
  **若数据源改成网络/串口回调，单生产者前提就不成立，整个无锁设计必须换掉。**
- **缺口用 NaN 表示**：窗口之外的 `value()` 返回 `NaN`，与"传感器自己吐的 NaN"是同一种东西。
  渲染器只需要一条规则——**遇到 NaN 就断开折线**。不提供也不该提供 `isGap(index)`。
- **轴不持有数据**：范围由数据自己声明（`AxisRange`），轴只是显示窗口 + 换算器，
  于是多 Y 轴是自然结果。退化范围与对数轴上的 ≤0 值都被稳定化，**不会产生 NaN**。
- **刻度用 double 算术，不用 `BigDecimal`**（fxcharts 用它是反面教材）。
  三级刻度的包含关系体现在**格**上：主刻度的值都落在中刻度的格上，中刻度的值都落在
  次刻度的格上。**一个值只发射一次**（取最粗的级别），别去列表里数重复项。
- **时间轴标签按 UTC 格式化**（`AxisType.TIME` 的值是 Unix 纪元秒）。
  要显示本地时间请在应用层转换——① 不读系统时区，否则同一段代码在不同机器上给出不同结果。
- **配色归一化成 1×256 LUT**（`ColorMapping.toLut()` 返回 `byte[1024]`，RGBA）。
  热力图换配色 = 换一张纹理，与数据量无关。
```

- [ ] **Step 5: CLAUDE.md —— 「已实现 vs 未实现」两处**

**5a.** 在「可用（纯计算，无需 GL，最适合写测试）」那段末尾追加：

```markdown
`chart/` 全部（数据容器、轴与刻度、配色 LUT、图表装配——见「图表」一节）
```

**5b.** 把「未实现 / 待办」里的这一行：

```markdown
- **科学绘图级图表**（子项目 D）：对数轴、多 Y 轴、误差棒、热力图、等高线。图表目前完全不存在。
```

换成：

```markdown
- **图表绘制后端**（子项目 D-②）：把 `chart/` 的产物变成 GL 顶点与 draw call
  （波形、散点、柱、热力图、瀑布）。**图表框架本身（D-①）已经做完，但还没有任何
  绘制能力**——`chart/` 只算不画。
- **GPU 计算**（子项目 D-③）：FFT、降采样、包络、密度累积（数字荧光）。
  `gpu/GPUFFT.java` 已经在那儿且**可用**（`#version 430`，见「前置事实」一节），
  但它现在**没有任何人引用**——是死代码，不是废代码。
- **误差棒、等高线、眼图**：`ChartType` 目前没有覆盖，属于 ③ 或更后面的事。
```

- [ ] **Step 6: README.md —— 补「图表」一节**

在「### 文本」之后插入：

```markdown
### 图表

图表框架（`chart/`）是**纯计算**的：数据容器、轴与刻度、配色 LUT、图表装配。
**绘制后端尚未实现**——它现在只算不画，所以下面这段代码算得出刻度，但还没有
任何东西出现在屏幕上。

```java
// 静态数据
ArrayChartData data = new ArrayChartData(
        new AxisRange[]{new AxisRange(0, 10, "时间", "s"), new AxisRange(-1, 1, "电压", "V")},
        new double[][]{{0, 1, 2, 3}, {0.1, -0.2, 0.3, 0.0}});

Axis x = new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(800);
Axis y = new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(600);

Chart chart = new Chart(x, y);
chart.addLayer("主").add(new Series("电压", data, ChartType.LINE).color(0xFF00FF00));

Tick[] ticks = x.ticks();               // 主/中/次三级刻度，位置已装配好

// 流式数据：采集线程写、GL 线程读，SPSC 无锁
RingChartData stream = new RingChartData(new AxisRange[]{AxisRange.of(0, 1)}, 1 << 16);
stream.append(0.5);                     // 采集线程
double v = stream.value(0, 0);          // GL 线程；窗口之外返回 NaN（缺口）
```

- **脏区间**：`dirtyRange(sinceRevision)` 让渲染器只上传新增的那一段；
  `revision` 不变时报空，静态数据一次上传后永不重传。
- **缺口是 NaN**：丢包与传感器故障是同一种表示，渲染器的规则只有一条——遇到 NaN 就断开折线。
- **流式数据只有一个写者**：`RingChartData` 是 SPSC 无锁环形缓冲。
  数据源若变成网络/串口回调（回调线程不固定），这个前提就不成立，必须换设计。
- **轴不持有数据**：范围由数据自己声明，轴只是显示窗口，多 Y 轴因此是自然结果。
- **时间轴按 UTC 格式化**；配色是 1×256 的 LUT（换配色 = 换一张纹理）。
```

- [ ] **Step 7: README.md —— 项目结构与测试**

**7a.** 项目结构树里加一行（放在 `renderer/` 之后）：

```
├── chart/       # 图表框架（纯计算）：ChartData、Axis、TickGenerator、ColorMapping、Chart
```

**7b.** 测试一节的那句：

```markdown
**改渲染路径跑 `PipelineVerifier`，改拾取跑 `PickVerifier`，改文本跑 `TextVerifier`**（见上面「运行」一节）。
```

保持不变（本次没有新增校验器：`chart/` 是纯计算，它的验收就是单测，
**这正是 ① 分界线画在这里的收益**），但在「测试」一节末尾补一句：

```markdown
`chart/` 也是纯计算（零 GL 依赖，由 `ChartPackageIsolationTest` 强制），
所以它的验收全部落在单元测试上——不需要、也不该有像素校验器。
```

- [ ] **Step 8: 逐条核实你写下的每一句**

Run:

```bash
# 文档里出现的类名/方法名必须真的存在
grep -rn "class \|enum \|interface " src/main/java/com/bingbaihanji/jfgl/chart/
# 文档里写的测试计数必须是实跑结果
mvn -o test
```

Expected: 测试 **261 通过 / 0 失败 / 2 跳过**；上面 `grep` 出来的类名与文档里写的一一对应。

**逐条对照**（写进实施报告的核对结果）：
- [ ] `GL_VERSION` 那条 4.6 与 compute 可用的说法 —— 有规格 §3.1 的实测记录背书
- [ ] `gpu/GPUFFT.java` 的 `#version` 与"没人引用" —— 对着文件核实（`grep -rn GPUFFT src/`）
- [ ] 文档里列的每一个 `chart/` 类名都存在，方法签名与文档一致
- [ ] 文档里的测试计数与目录清单与实际一致
- [ ] `RingChartData` 的"只有一个写者"、"缺口是 NaN"两条与类文档措辞一致

- [ ] **Step 9: 跑全量测试**

Run: `mvn -o test`
Expected: `Tests run: 261, Failures: 0, Errors: 0, Skipped: 2`

- [ ] **Step 10: 提交**

```bash
git add CLAUDE.md README.md
git commit -F - <<'EOF'
docs: 补图表框架的用法，并修掉 GL 版本那条会误导人的说法

CLAUDE.md 新增「前置事实」一节：实测 GL_VERSION = 4.6（不是 3.3），
并且端到端验证过最小 compute shader（SSBO 写入）。仓库里那 5 个 #version 330
着色器能跑是因为 4.6 向后兼容。这条不写下来，下一个人会以为 compute 不可用——
gpu/GPUFFT.java 就是这么被埋掉的：它一直可用（#version 430，完整的 Cooley-Tukey
radix-2 + SSBO），只是没有任何人引用。

新增「图表」一节（与「拾取」「文本」同构）：脏区间是一等公民、流式数据是 SPSC
环形缓冲且只有一个写者、缺口用 NaN 表示且不给 isGap、轴不持有数据、刻度用 double
不用 BigDecimal、时间轴按 UTC 格式化、配色归一化成 1×256 LUT。

「已实现 vs 未实现」把"科学绘图级图表"这一条拆开：D-① 框架已完成，D-② 绘制后端
与 D-③ GPU 计算仍未实现；并注明 GPUFFT 是死代码而不是废代码。
测试计数与目录清单更新到 chart/ 这一层，架构图加一行，并写明 chart/ 对
gl/renderer/text/geom 零依赖、由 ChartPackageIsolationTest 强制。

README 补图表用法与那几条约定，项目结构补 chart/。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 完成标准

- [ ] `mvn -o test` → **261 通过 / 0 失败 / 2 跳过**（若实际数字不同，以实跑为准并更新本文档顶部那张计数表）
- [ ] **没有改动 `gl/`、`renderer/`、`text/`、`geom/` 里的任何一个文件**（`git diff --stat` 核对）
- [ ] `ChartPackageIsolationTest` 通过：`chart/` 只依赖 `math/` 与 `util/`，`RenderContext` 是空接口
- [ ] 每个任务的变异验证都**实际注入并确认失败**过，且已回滚（`git status --porcelain` 只剩预期的改动）
- [ ] 任何**存活**的变异都在报告里如实写明"未能捕获"及原因，**没有**用放宽断言的方式掩盖。
      已知会存活的两条必须如实记录：`& (cap-1)` → `% capacity`（数值等价）；
      `long` → `int`（要跑到 2³¹ 次追加）
- [ ] `CLAUDE.md` / `README.md` 已更新，且写进去的每一句行为声明都对着代码核实过
- [ ] 未使用 `mvn exec:java`

## 已知遗留（不在本计划范围）

- **②（GPU 绘制后端）与 ③（GPU 计算）**：本计划只交付 ①，`chart/` 只算不画。
  ② 的第一件事应该是把 `RenderContext` 的子接口与折线渲染器做出来，
  并且**真的用上 `dirtyRange`**——不然 ① 的脏区间设计就白做了。
- **系列级的轴选择**：本期约定 `axes[]` 按维度下标对齐，多 Y 轴只能通过"多给几根轴 +
  系列用第几维"来表达。chart-fx 有 `getXAxisId/getYAxisId`（按 id 选轴），本期不做。
- **`TEXT` 轴的类目名**：`Tick.label` 是下标字符串，类目名归数据侧。
  要显示"周一/周二"需要 ② 或应用层拿下标去查——本期刻意不让 ① 持有一份类目表副本。
- **`ColorMapping` 的内置配色只有两个**（`GRAYSCALE`、`INFRARED_4`）。
  `INFRARED_4` 是 4 个关键节点的近似，不是经典的 256 项离散表原样复刻；
  要复刻就把整张表铺成更长的 `Stop[]`。
- **刻度标签的密度是固定的**（80 像素一主刻度，只有主刻度带标签）。
  自适应密度（先量文字宽度再决定步长）需要 ② 提供文字度量，本期不做。
- **`dirtyRange` 只表达一个区间**，多段离散的脏区（例如"第 3 维变了、第 7 维没变"）
  表达不了。本期所有实现的变更都是整体的，够用。
- **没有黄金图像测试**（与整个仓库一致）。

---

## 实施期间实测到的计划弱点（Task 2，须修）

**这四条都是"检查写错了"，不是"代码错了"** —— 每条都有实测探针，不是推测。

### 1. `niceStep` 去掉 `5.0` 分支**存活**（12/12 全过）

断言把步长归一化成 1/2/5×10ⁿ 再比较，而 `10 × 10ⁿ` 会进位成 `1 × 10ⁿ⁺¹`，
**仍然是合法的 1/2/5 形式**。实测：

```
去掉 5 分支：span=42.0 niceStep=10.0 majorStep=10.0 normalized=1.0   ← 断言照样过
还原后　　：span=42.0 niceStep=5.0  majorStep=5.0  normalized=5.0
```

**这条断言在结构上看不见"5 分支没了"。** 修法：断言要同时钉住**数值本身**（例如
`span=42` 时主刻度步长必须**恰好是 5.0**），而不只是"归一化后落在合法集合里"。
附带问题：`span=0.3` 那一档被 `withMinimumSpan()` 扩成 `[-0.35, 0.65]`，
**根本没在测亚单位步长**。

### 2. 线性轴不做 `withMinimumSpan()` **存活**（12/12 全过）

测试**故意**用 `linearMap(stable, …)` 从稳定化后的窗口构造映射（注释里写明的做法），
所以位置与"生成器有没有稳定化"无关；而 `niceStep(0, …)` 本就返回 1.0，
退化时只产出一个刻度而不是除零；唯一的数量断言又只是 `> 0`。三层叠加导致盲区。

### 3. 对数轴不做 `withPositiveMin()` 的失败形态与计划描述不符

实际是 `assertDoesNotThrow` 里抛 **`OutOfMemoryError`**：min=0 时 `logMin = -Infinity`，
`firstExponent = (int) Math.ceil(-Infinity)` = `Integer.MIN_VALUE`，
指数循环要跑约 **21 亿次**往 ArrayList 里塞 `0.0` 直到堆耗尽。

**这同时是一条真缺陷**：退化输入导致 OOM 而不是优雅结果。测试第二半（整段非正）
经探针确认是**有效的**，只是永远轮不到它执行。

### 4. 计划里关于时区的那句断言是**错的**

计划写「若在 UTC 机器上跑，这条变异会存活——那正是"时区依赖"缺陷的性质」。实测：

```
不带 zone 的 DateTimeFormatter 格式化 Instant -> THROWS UnsupportedTemporalTypeException
```

**不带 zone 的 formatter 根本无法格式化 `Instant`——它不会退回系统时区，而是直接抛异常。**
所以这条变异**在任何机器上都会失败**，"UTC 机器上会存活"不成立。

含义更重要：`时间轴标签用UTC而不是系统时区` 这条测试实际钉住的是「**有没有 zone**」，
而**不是**「UTC 还是本地时区」——真正的时区依赖缺陷（`ZoneId.of` 换成 `ZoneId.systemDefault()`）
它抓不到。修法：加一条断言，在**非 UTC 时区**下构造并比对期望字符串。

---

## 实施期间的一起 git 事故（已修复，须记住教训）

两个并行代理**各自**执行了 `git reset --soft HEAD~1`（都想"修好自己被污染的提交"），
两次叠加后 `718c61d`（ArrayChartData）成了 dangling，HEAD 退回 `083e1e6`。
文件没丢，补齐为 `1ff61b3`。

**根因与对策（写给后续所有并行派单）**：

1. **派单书必须写「只 `git add` 你 `Files:` 里列出的确切路径」**，不能写"结束时工作区必须干净"——
   那句话会被读成"让整棵树干净"，从而诱导 `git add -A`，把别人在飞的文件扫进自己的提交。
2. **派单书必须禁止任何改写历史的操作**（`git reset` / `git rebase` / `git commit --amend`）。
   发现提交被污染时**停下来报告**，不要自行修历史。

**核心教训：在并发环境里，"想修好自己的错误"比"错误本身"更危险**——一次污染只影响
一条提交的描述，一次叠加的 `reset` 会**丢掉别人的提交**。

---

## Task 6 必须修的一处结构性盲区（Task 5 实测，附证据）

**计划里 M11（把 `writeIndex = w + 1` 挪到写数据之前）标注"跑 5 遍如实记录"。
Task 5 实测：5 遍全部未能捕获——但这不是运气，是 `ChartDataConcurrencyTest`
的扫描范围有结构性盲区，跑 50 遍也一样。**

### 为什么抓不到

并发测试的读者循环只扫窗口的**前 512 个下标**：

```java
for (long absolute = start + HEAD_MARGIN; absolute < end; absolute++)
//  end = min(w, start + 512)
```

- 正确实现里，写者正在覆写的那一格是**下标 0**（绝对号 `W - capacity` 与写者当前的
  `W` 同槽），而 `HEAD_MARGIN = 4` 正是为躲开它而设的。
- 变异之后，写者先发布 `W = w + 1` 再写数据，「正在被写的槽位」对应的是
  **窗口的最后一个下标**（`i ≡ -1 mod capacity`）。
- **前 512 个下标里一个都不会撕裂**——所以该变异在该扫描范围内**永远不可见**。

### 证明变异是真的（Task 5 在仓库外做的对照实验）

| 驱动 | 变异态 | 正确态 |
|------|--------|--------|
| 头部扫描（同计划） | `torn=0`，5 次 exit=0 | `torn=0` |
| **尾部扫描** | **`torn=2294 / 1774 / 2904`，3 次全 exit=1** | `torn=0`，3 次 exit=0 |

尾部驱动**既抓得住变异、又没有假阳性**，可以放心用。

### 修法

**把扫描范围改成「头部 + 尾部」两段**（或整个窗口）。
只改头部扫描的话，那条变异行的"跑 5 遍"是个**注定的空转**。

---

## 另外三处 Task 5 实测出来的计划不准

1. **Task 5 变异表表头写「共 7 条」但列了 8 行**；提交信息里写「16 条单测 + 7 条变异验证
   （含两条只能被结构断言抓住的：volatile 与 long）」——但表里只有 `volatile` 那条是结构断言，
   `long` 那条根本不在 Task 5 表里（它在 Task 6，由 Task 5 的结构断言兜底）。**数字与内容两处不准。**
2. **M1 的失败机制描述错**：计划说"负下标会抛 `ArrayIndexOutOfBoundsException`"。
   实际 `(absolute & mask)` 对负的 `absolute` 仍得到**合法的非负槽位**——
   既不抛异常也不越界，而是**读到一个错位的旧值**。断言确实失败了，但原因不是计划写的那个。
3. **M6 的失败形态与预期不同**：不是断言失败，是 `DirtyRange` 构造器抛
   `IllegalArgumentException` 把测试变成 ERROR。变异仍被捕获，但这说明该测试对
   `dirtyRange` 的「整窗脏」语义**没有真正断言到**（在构造器上就短路了）。

---

## 实施进度（截至本轮）

| Task | 提交 | 测试 | 状态 |
|------|------|------|------|
| 1 基础类型 | `083e1e6` | 0 | ✅ |
| 2 刻度生成 | `962b02b` | +12 | ✅ **2 条变异存活**（检查弱点，见上文） |
| 4 静态数据 | `1ff61b3` | +6 | ✅ |
| 5 环形缓冲 | `8c78ad5` | +16 | ✅ **11 条变异**，2 条如预期存活 |
| 7 颜色 LUT | `3a2d7ae` | +7 | ✅ |
| 3 / 6 / 8 / 9 / 10 | —— | —— | **待做** |

全量 **246 通过 / 0 失败 / 2 跳过**。

---

## 实施期间又实测出的两处「断言没有牙」（Task 3、Task 8）

### Task 8：`allSeries()` 变异**在给定 fixture 下永远不可能失败**

变异「`allSeries()` 改成层内优先（转置）」预期被 `装配顺序即绘制顺序` 杀死，**实测存活**。

原因：正确顺序是「层序优先拼接」，变异是按下标转置。**当第一层只有 1 个系列时，两种排法完全相同**——
转置后 `a0,(b),a1` 退化成 `a0,a1,b`，与拼接一致。而计划给的 fixture 恰是 `底图=[a]`（1 个）、
`数据=[b,c]`，于是这个测试**在结构上无法观测到它自己的失败消息所描述的那个 bug**。

执行者做了"变异是活的"对照实验：把第一层改成 2 个系列，变异立刻暴露
（`expected: <[a0, a1, b, c]> but was: <[a0, b, a1, c]>`）——**证明变异有效，是检查看不见**。

**处置**：fixture 第一层改成 2 个系列，并加注释钉死「第一层必须 ≥2 个系列，别简化回去」。
原断言全部保留，另补一条层内容断言。

### Task 8：四处 Javadoc 承诺「不可修改的列表」，**零覆盖**

`Layer.series()` / `Chart.axes()` / `Chart.layers()` / `Chart.allSeries()` 都承诺返回不可修改列表，
去掉 `Collections.unmodifiableList` **照样全绿**。而 `Layer.series()` 返回活列表会**绕过刚立的重复守卫**。

**处置**：补 2 条 `assertThrows(UnsupportedOperationException.class, ...)`，并对新断言再做一次变异验证（已杀死）。

### Task 3：`刻度装配后位置与映射一致` **结构上准恒真**

`assertEquals(axis.dataToDisplay(tick.value()), tick.position())` —— 而 `position` 恰恰就是
`TickGenerator.generate(..., this::dataToDisplay)` 用**同一个函数**算出来的。只要装配链路是把
`this::dataToDisplay` 传下去，这条断言**恒真**，抓不到「刻度生成器自己算了一遍映射」这类缺陷
（**那正是 `Tick` 的 Javadoc 声称要防的事**）。

真正有抓力的是紧随其后的 `assertEquals(4, majors.size())` 与 `assertEquals(LENGTH/3.0, majors.get(1).position())`。
**不要把它当成覆盖性检查。**
