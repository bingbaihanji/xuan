package com.bingbaihanji.xuan.example.demo

import com.bingbaihanji.xuan.chart.*
import com.bingbaihanji.xuan.example.demo.DemoChart.build
import com.bingbaihanji.xuan.example.demo.DemoChart.cachedCharts
import com.bingbaihanji.xuan.example.demo.DemoChart.chart
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.util.Rect

/**
 * 绘图区底色。
 *
 * <p>★ **2026-09-28 起，网格线与刻度文字的颜色/字号/预留量都不再是本文件的常量**
 * ——它们改由 `AxisStyle` 配置（见 `build()` 里的 `axisStyle(...)`），
 * 库负责画、也负责按字号算预留。这里删掉的五个常量（`GRID` / `TICK_TEXT` /
 * `TICK_FONT` / `TICK_GAP` / `TICK_RESERVE`）连同它们那段"预留量必须 ≥ 标签宽"
 * 的说明一起迁走了——那段推理本身没错，只是现在由库里同一条口径统一管
 * （`tickLength + 字号 × LINE_HEIGHT_FACTOR`），不再需要每个应用各算一遍。
 */
private const val PLOT_BG = 0xFF1A1D22.toInt()

/** 图表外框相对画布的边距。给标题带、图例带与状态栏留的地方。 */
private const val FRAME_MARGIN = 40f

/** 两条系列的配色。 */
private const val COLOR_SALES = 0xFF4FC3F7.toInt()
private const val COLOR_COST = 0xFFFF8A65.toInt()

/**
 * 本进程是否处于合成事件自检模式（`-Dxuan.demo.selftest=1`）。
 *
 * <p><strong>★ 判定走 [selfTestEnabled]，不是在这里再解析一遍属性</strong>
 * （[SELFTEST_PROPERTY] 那个常量在 `XuanDemo.kt` 里，两处引用同一个常量）。
 * 这里本来写的是 `System.getProperty(SELFTEST_PROPERTY) == "1"`——**属性名一样、
 * 认得的值不一样**：`XuanDemo` 那一侧认 `1` 与 `true`，这一侧只认 `1`。
 * 于是 `-Dxuan.demo.selftest=true` 下脚本照跑、而**本文件的三个观测值一个都不写**，
 * 自检把它读成"图表没画出来"。共用属性名不等于共用判定——详见 [selfTestEnabled]
 * 的文档，那里记着完整的那次假缺陷。
 *
 * <p>默认关着，是因为下面那两个计数器**是纯观测**——它们不该让生产路径
 * 每帧多两次 volatile 写（图型每帧都在画，那是一个真的热路径）。开关关掉之后
 * `SELFTEST` 是个静态 final 布尔，JIT 会把整个分支消掉。
 */
private val SELFTEST: Boolean = selfTestEnabled()

/** 12 个月。 */
private val MONTHS = arrayOf(
    "1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"
)

/**
 * 合成数据：销售额与成本（万元）。
 *
 * <p>**写死，不用 Random**——"这一帧画对了没有"要能复核。随机数据让每一次运行都是新画面，
 * 出了偏差也说不清是渲染错了还是数据本来如此。
 */
private val SALES = doubleArrayOf(42.0, 48.0, 51.0, 47.0, 62.0, 75.0, 81.0, 78.0, 88.0, 95.0, 102.0, 118.0)
private val COST = doubleArrayOf(31.0, 35.0, 39.0, 41.0, 46.0, 52.0, 55.0, 58.0, 61.0, 64.0, 70.0, 76.0)

/**
 * 图表模式的装配与绘制。
 *
 * <p>**坐标轴装配与绘制是两件事**：[build] 造出 [Chart]（纯计算，任意线程可调），
 * [draw] 每帧在 GL 线程上画。而 `Axis.setDisplayLength` 必须**每帧按当前绘图区重设**
 * ——窗口一缩放绘图区就变，刻度位置就跟着错开，而那看起来像数据本身的问题。
 */
internal object DemoChart {

    /**
     * **只给启动一致性自检读**（`XuanDemo.kt` 的 `verifySelfTestFlagsAgree`）：
     * 本文件那个 `SELFTEST` 的当前值，用来和 `XuanDemo` 那一侧的开关比对。
     *
     * <p>为什么要这么一个入口：那两份开关**各自私有**，谁也看不见谁，而它们一旦不一致
     * （历史上真发生过：属性名相同、解析不同），症状是"图表渲染坏了"而**不是**
     * "开关坏了"——那是本项目最费时间的一类误导。所以启动时当场比一次。
     *
     * <p>**不要在别处用它**：它不是"当前是否自检模式"的公共查询口，只是那一次比对的探针。
     */
    internal fun selfTestFlag(): Boolean = SELFTEST

    /** 菜单里的图型。第二项是 [ChartType]；`AREA` / `BAR` 需要额外的样式参数，见 [build]。 */
    val KINDS: List<Pair<String, ChartType>> = listOf(
        "折线" to ChartType.LINE,
        "柱状" to ChartType.BAR,
        "面积" to ChartType.AREA,
        "散点" to ChartType.SCATTER
    )

    /** 当前选中的图型下标（菜单在 JavaFX 线程写，绘制在 GL 线程读）。 */
    @Volatile
    var selectedKind: Int = 0

    /**
     * 平滑曲线开关（菜单「平滑曲线」）。**默认关**，与 [Series.smooth] 的默认值一致。
     *
     * <p>**它只对折线与面积生效**——`STEP` / `SPECTRUM` / `SCATTER` / `BAR` 一律忽略，
     * 见 [Series.smooth] 的 KDoc（其中 `SPECTRUM` 是硬理由：它的实例属性指向 FFT 的输出缓冲，
     * 而那个缓冲**没有邻居余量**）。
     */
    @Volatile
    var smoothOn: Boolean = false

    /** JavaFX 线程写入、GL 线程在 [chart] 取用的设备像素 hover 坐标。 */
    @Volatile
    private var hoverX: Float = Float.NaN
    @Volatile
    private var hoverY: Float = Float.NaN

    /** 更新图表 hover 指针；坐标单位是设备像素。 */
    fun updateHoverPointer(x: Float, y: Float) {
        hoverX = x
        hoverY = y
    }

    /** 清除图表 hover。 */
    fun clearHoverPointer() {
        hoverX = Float.NaN
        hoverY = Float.NaN
    }

    /**
     * 平滑开关对某个图型**是否适用**。
     *
     * <p>它决定两件事：`build` 里要不要 `Series.smooth(true)`，以及**缓存键**要不要带上平滑
     * （见 [chart]）——两处必须用**同一个**判定，否则会出现"缓存按 A 分、绘制按 B 画"。
     */
    private fun smoothApplies(type: ChartType): Boolean =
        type == ChartType.LINE || type == ChartType.AREA

    /**
     * ★ **只读观测入口**（合成事件自检第 10、11 条用）：[draw] **跑到末尾**的帧数。
     *
     * <p>为什么需要它：本项目 GL 线程上的异常是**静默吞掉**的，所以"图表画出来了"
     * 这句话在画面上和"图表抛异常后被吞掉"长得一模一样。这个计数器放在 [draw] 的
     * **最后一行**，于是它按帧增长就是"整条绘制路径跑到了末尾"的唯一直接证据
     * （它跑过的最后一段里包含了 `gc.charts.drawChart` —— 图型渲染器、实例属性、
     * 拾取号都在那一段里）。
     *
     * <p>**只在自检模式下自增**（见 [SELFTEST]）：生产路径一次都不写它。
     * **它不改变任何绘制行为**——没有一处绘制逻辑读它。
     */
    @Volatile
    var selfTestDrawnFrames: Int = 0
        private set

    /**
     * ★ **只读观测入口**：最近一帧用的那个 [Chart] 的 `identityHashCode`（0 = 还没画过）。
     *
     * <p>自检第 ⑭ 条拿它当"**真的重建了 `Chart`**"的判据：`ChartRenderer` 按
     * **`Series` 对象身份**缓存 GPU 缓冲与拾取号，所以"切了图型却仍是同一个 `Chart` 实例"
     * 就等于"切图型毫无反应"——那是画面完全正常的一类静默失效。
     * 身份**变了**才说明重建真的发生了；反过来，切回一个已经建过的图型时身份必须
     * **回到原来那个**（[cachedCharts] 那张表生效）——表要是退化成"永远返回同一个实例"，
     * 身份就不再跳变、菜单变死，这条当场倒。
     *
     * <p>同样只在自检模式下写（见 [SELFTEST]）。**它不影响绘制**。
     */
    @Volatile
    var selfTestLastChartIdentity: Int = 0
        private set

    /**
     * ★ **只读观测入口**：**这一帧实际用的是哪个图型**（= 本帧 [draw] 读到的 [selectedKind]）。
     *
     * <p>自检第 ⑭ 条拿它当"**菜单动作已经在某一帧生效**"的判据。为什么需要它：
     * 菜单在 JavaFX 线程改 `selectedKind`，而 GL 线程的那一帧**可能已经跑过 `chart()`**，
     * 于是紧接着的那一帧画的仍是**旧图型**；此时"探针涨了一帧"或"身份变了"这类信号
     * 都可能是旧图型的，读数就会把旧实例贴上新图型的标签（实测撞到过：第一段读到的
     * 身份与起点**相同**、号也没涨，⑪ 因此倒）。
     *
     * <p>它与第 ⑭ 条的断言**量的是两回事**（它量"这一帧用的哪个图型"，断言量
     * "重建了没有、号涨了没有"），所以拿它当等待条件不会让那条断言退化成恒真：
     * `chart()` 变成永远返回缓存时，它照样会变成新图型（图型字段确实改了），
     * 而断言里的"身份跳变 / 号 +2"仍然为假 ⇒ 倒的仍是断言本身，不是等待条件。
     *
     * <p>同样只在自检模式下写（见 [SELFTEST]）。**它不影响绘制**。
     */
    @Volatile
    var selfTestLastDrawnKind: Int = -1
        private set

    /**
     * 帧内临时量：本帧 `chart()` 用的是哪个图型（**只由 GL 线程在自己那一帧里写、帧末读**）。
     *
     * <p>不直接写 [selfTestLastDrawnKind] 是为了不在帧首发布它——见那里的说明。
     */
    private var selfTestFrameKind: Int = -1

    /**
     * 已建好的图表，**按（图型 × 平滑）各留一份**。
     *
     * <h3>它现在的理由是"别每帧重建"，不再是"防泄漏"</h3>
     * <p>`ChartRenderer` 用 `IdentityHashMap` 按 **`Series` 对象身份**缓存每个系列的
     * GPU 缓冲与拾取号（`ChartRenderer` 里那两处：`buffers` 与
     * `pickIds.computeIfAbsent(series, pickRegistry::register)`）。以前那两张表
     * **只在 `ChartRenderer.dispose()` 里清空、没有任何"这个系列不再画了"的回收路径**，
     * 于是"每帧新建 `Series`"会**每帧泄漏一块 GPU 缓冲并消耗两个拾取号**；号耗尽时
     * `PickRegistry` 抛异常，而 GL 线程上的异常在本项目是**静默吞掉**的 ⇒
     * "前几百帧完全正常，然后图表忽然不画了，没有任何报错"。
     *
     * <p><strong>那条缺口已经补上了</strong>：`ChartRenderer.releaseUnused` 由
     * `Gc.beginFrame` 每帧调一次，把**连续两帧没有被画过**的系列释放掉（缓冲删掉、
     * 拾取号归还）。所以"每帧重建 `Chart`"现在是**安全**的写法，
     * **本表不再是防泄漏的手段**——它防的那件事已经由库负责了。
     *
     * <h3>那为什么还留着</h3>
     * <p>因为**重建有代价，而且代价不在显存而在带宽**：每次 `build()` 都造出新的
     * `Series` ⇒ 新的 `SeriesBuffer` ⇒ 那一帧把**整个环重传一遍**。
     * 表在 ⇒ **停在同一个图型上时一次都不重传**（那正是绝大多数帧）；
     * 表不在 ⇒ 每帧重传一遍。本 demo 数据量小，两种都看不出来，
     * 但"每帧只上传新增的点"是 ② 的性能主张（见 `SeriesBuffer` 的类文档），
     * 一个演示程序没有理由把它反着演。
     *
     * <p><strong>注意它现在**不**保证"切回来零开销"</strong>：切走另一个图型超过两帧之后，
     * 库会把那个图型的系列回收掉，切回来时会重建缓冲 ⇒ 那一段的首帧仍要重传一次。
     * 这是回收与重传之间刻意的取舍（宽限只有两帧，见 `ChartRenderer.GRACE_GENERATIONS`），
     * 代价是"切回来那一下重传一次"，收益是"不再画的图型不会一直占着显存与拾取号"。
     *
     * <p><strong>★ 为什么是一张表、而不是"只留最后一个"</strong>：多留一份的成本只是
     * 一个 `Chart` 对象（纯计算，不持有 GL 资源），而单槽版本下
     * `LINE → BAR → LINE` 每绕一圈都要重新 `build()` 一次。
     * 用表之后上限是 **4 图型 × 2 平滑 = 8 个 `Chart`**（每个 2 条系列），
     * 而**真正占 GL 资源的是"这一帧画过的那些系列"**，那个数由库约束、不随切换次数增长
     * （Task 11 第 ⑭ 条断言的正是这一点）。
     */
    private val cachedCharts = HashMap<Int, Chart>()

    /**
     * 取当前图型的图表。**同一个图型永远拿到同一个 [Chart] 实例**（理由见 [cachedCharts]）。
     *
     * <p>**只在 GL 线程调用**——所以收成 `private`：`internal object` 的 public 成员
     * 对整个模块可见，而这个函数只在 [draw] 里用。
     */
    private fun chart(): Chart {
        val k = selectedKind
        val smooth = smoothApplies(KINDS[k].second) && smoothOn
        // ★ **缓存键必须带上平滑**：缓存的 Chart 里那两个 Series 在**创建时**就定下了
        //   `smooth()`，只用图型做键的话，切换平滑**静默无效**——菜单点了，画面一动不动，
        //   而没有任何报错（GL 线程的异常在本项目本来就是吞掉的，这里连异常都没有）。
        //
        //   **为什么用"再加一维"而不是"清空缓存重建"**：清空之后切回旧图型要重新 `build()`
        //   ⇒ 新的 `Series` ⇒ 新的 `SeriesBuffer` ⇒ 那一段的首帧重传整环。
        //   并进键里 ⇒ 上限 4 图型 × 2 平滑 = **8 个 Chart**（纯计算对象，不占 GL 资源），
        //   **停在同一个图型上时零重传**。
        //
        //   （这一处曾经写的是"库里没有回收接口，所以清空重建会漏号漏缓冲"。
        //     那是真的，但**已经不再成立**——`ChartRenderer.releaseUnused` 补上了那条路径，
        //     见 `cachedCharts` 的说明。现在这条判断的理由只剩"重传整环"这一条。）
        //
        // `getOrPut` 把**同一个** Chart 一直发给同一对 (图型, 平滑)——这正是 ChartRenderer 要的：
        // 它按 Series 的**对象身份**缓存，只要对象不变，那两张 map 就不增长。
        val chart = cachedCharts.getOrPut(k * 2 + if (smooth) 1 else 0) { build(k, smooth) }
        val x = hoverX
        val y = hoverY
        if (x.isFinite() && y.isFinite()) {
            chart.interaction().updatePointer(x, y)
        } else {
            chart.interaction().clearPointer()
        }
        return chart
    }

    /**
     * 造出图表（纯计算）。**不持有 GL 资源**，所以可以随便什么时候调——但**别每帧调**，
     * 理由见 [cachedChart]。
     *
     * @param kindIndex [KINDS] 里的下标
     * @param smooth    这两个系列要不要画成平滑曲线。**由 [chart] 算好传进来**
     *                  （它必须与缓存键用的是同一个判定）
     */
    private fun build(kindIndex: Int, smooth: Boolean): Chart {
        val data = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (MONTHS.size - 1).toDouble(), "月份", ""),
                AxisRange(0.0, 130.0, "金额", "万元")
            ),
            arrayOf(
                DoubleArray(MONTHS.size) { it.toDouble() },   // 维度 0 = x（数据下标）
                SALES.copyOf()                                // 维度 1 = y（销售额）
            )
        )
        val data2 = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (MONTHS.size - 1).toDouble(), "月份", ""),
                AxisRange(0.0, 130.0, "金额", "万元")
            ),
            arrayOf(DoubleArray(MONTHS.size) { it.toDouble() }, COST.copyOf())
        )

        val type = KINDS[kindIndex].second
        val x = Axis(AxisType.LINEAR, data.axisRange(0))
        val y = Axis(AxisType.LINEAR, data.axisRange(1))

        return Chart(x, y)
            .title("月度销售与成本（合成数据）")
            .titleFontSize(16f)
            .legendSide(ChartSide.BOTTOM)
            .axisTitlesVisible(true)
            .axisTitleFontSize(12f)
            // ★ 网格 / 轴线 / 箭头 / 刻度线 / 刻度文字**全部交给库**（2026-09-28 起）。
            //
            //   从前这里自己画网格与刻度文字（约 40 行），还把"算布局 → 设 displayLength
            //   → 网格 → flush → drawChart → 刻度"称作**硬约束**——那是因为网格必须垫在
            //   数据底下、而数据是当场画的。现在库把坐标系画在数据**之前**，
            //   那条顺序约束随之消失（只留下"背景要 flush 在 drawChart 之前"这一条）。
            //
            //   ⚠️ 打开之后**刻度预留由库自己算**，下面那两处 `tickLabelReserve` 会被
            //   覆盖——所以它们**已经删掉**（留着会让下一个人以为还在生效）。
            .axisStyle(AxisStyle.defaults().visible(true))
            .padding(ChartInsets(8f, 8f, 8f, 8f))
            .apply {
                addLayer("数据").add(styleFor(Series("销售额", data, type).color(COLOR_SALES), smooth))
                    .add(styleFor(Series("成本", data2, type).color(COLOR_COST), smooth))
                interaction().setConfig(
                    ChartInteractionConfig.defaults()
                        .snapRadius(24f)
                        .crosshairColor(0xB8D7E3F4.toInt())
                        .tooltipColors(
                            0xF0222933.toInt(),
                            0xFF6B7C93.toInt(),
                            0xFFF4F7FB.toInt()
                        )
                        .formatter { value, _ ->
                            if (value == value) "%.2f".format(value) else "NaN"
                        }
                )
            }
    }

    /**
     * 图型各自的样式。
     *
     * <p>**两种图型天然要求不同的装配**，不能只换 [ChartType]：柱状与面积要用 `baseline`，
     * 面积还要 `fillAlpha`。只换 type 的话柱状会以 0 为基线（默认就是 0，正好对），
     * 而面积的填充透明度会是默认的 0.5——这里显式写出来，是为了让"哪些是刻意的"可见。
     *
     * <p>`Series` 的样式 setter **拒绝 NaN / ±Infinity**（抛 `IllegalArgumentException`），
     * 所以下面的常量都必须是有限数。
     */
    private fun styleFor(s: Series, smooth: Boolean): Series = when (s.type()) {
        ChartType.BAR -> s.baseline(0f).categoryGap(0.25f).barGap(0.2f)
        // 面积：顶边按 [smooth] 画成曲线（基线边不参与平滑——它是直的）
        ChartType.AREA -> s.baseline(0f).fillAlpha(0.35f).smooth(smooth)
        ChartType.SCATTER -> s.markerSize(4f)
        // 折线是 [smooth] 唯一的另一个用武之地
        else -> s.lineWidth(2f).smooth(smooth)
    }

    /**
     * 画一帧图表。
     *
     * <p>顺序是硬约束，见设计文档 §7：**算布局 → 设 displayLength → 网格 → flush → drawChart → 刻度**。
     */
    fun draw(gc: Gc) {
        val frame = Rect(FRAME_MARGIN, FRAME_MARGIN, gc.width - 2f * FRAME_MARGIN, gc.height - 2f * FRAME_MARGIN)

        val chart = chart()          // ★ 取缓存的，**不是** build()——见 cachedChart 的说明
        // ★ 只读观测：**记住本帧用的是哪个图型**（`chart()` 刚刚读的就是它）。
        //   ⚠️ 只写进一个帧内临时量，**发布留到帧末**（与 `selfTestDrawnFrames++` 一起）——
        //   理由见 [selfTestLastDrawnKind]：在帧首就发布，等自检读到它时**本帧的
        //   `drawChart` 可能还没跑**（数据系列还没注册号），读数就会是"半帧"的。
        if (SELFTEST) selfTestFrameKind = selectedKind
        val metrics = GcTextMetrics(gc)
        val layout = ChartLayout.compute(chart, frame, metrics)
        val plot = layout.plotRect()

        // ★★ **必须在算完布局之后按 `plot` 判、不能按常量判** —— 这一行防的是
        //    "整条渲染链永久失效"，不是"少画一帧"。
        //
        //    ① `ChartLayout` 在带子吃光空间时会把绘图区**钳成 0**
        //       （`ChartLayout` 里那些 `Math.max(0f, …)`）；
        //    ② 而 `Axis.setDisplayLength` 的契约是**必须为正的有限数**，
        //       传 `0.0` 会抛 `IllegalArgumentException`；
        //    ③ 异常从 `draw` → `drawScene` → `onFrame` 抛出，而 `FXGLTransfer` 的
        //       `beginFrame / onFrame / endFrame` **没有 `try/finally`**，于是 `endFrame()`
        //       被跳过、`Gc.frameActive` 停在 `true`、**此后每一帧的 `beginFrame` 都抛**
        //       ——画布**永久死掉**，而按本项目一贯的判据（GL 线程异常在 openglfx 原生回调
        //       那层被吞）**症状是一句报错都没有**。
        //
        //    **为什么不能用常量守卫**：竖直方向要扣掉的带子总高是
        //    `padding 16 + 标题带(16×1.4) + titleGap 6 + 图例带(12×1.4) + legendGap 8
        //     + x 轴标题带(12×1.4) + axisTitleGap 4 + 刻度预留 18 ≈ 108`，
        //    而**水平方向要扣掉的是 y 轴标题带的实测文字宽**（`metrics.width` 算出来的）
        //    ——它不是常量。所以"多小的窗口排不下"**不可能预先写成一个数**。
        //    早年这里写的是 `if (frame.width < 80f || frame.height < 80f) return`：
        //    `gc.height ∈ [160, 188)` 那一段**守卫放行、而 `setDisplayLength(0.0)` 必抛**，
        //    而 Stage 没设最小尺寸——**用户把窗口拖矮就会踩到**。
        if (plot.width <= 0f || plot.height <= 0f) return

        // ★ 顺序约束：displayLength 必须等布局算完，且**每帧重设**（窗口缩放会改绘图区尺寸）
        chart.axis(0).setDisplayLength(plot.width.toDouble())
        chart.axis(1).setDisplayLength(plot.height.toDouble())

        // ★ **刻度只取一次**：`Axis.ticks()` **每次都重新生成整串刻度并格式化标签**
        //   （`TickGenerator.generate`，`Axis` 没有任何缓存），而本函数一帧里要把它们
        //   遍历**两遍**（网格一遍、刻度文字一遍）× 两根轴 = **四次生成**。
        //   同一个文件刚刚为了"每帧多两次 volatile 写"专门设了 `SELFTEST` 开关，
        //   这里的量级比那个大得多（每次生成要算三级刻度 + 格式化每个标签）。
        //   **必须在 `setDisplayLength` 之后取**——刻度的位置依赖显示长度；
        //   两次遍历之间没有东西改轴，所以取一次、用两遍是等价的。
        //
        //   ⚠️ **2026-09-28：这两行本身也删了**——网格与刻度文字改由库画之后，
        //      本文件**一处都不再读 `ticks()`**，取出来也没人用（而它每帧要跑两次）。

        // 绘图区底色。**放在 save/restore 里**——`gc.fill` 是**可变状态**，
        // 写在外面会泄漏给本帧后续的图元（今天无害，只因为 `drawScene` 紧接着就 return）。
        // 库自己的 `drawChart` 是刻意做成状态中立的，这个入口跟上更稳。
        gc.save()
        gc.fill = PLOT_BG
        gc.fillRect(plot.x, plot.y, plot.width, plot.height)
        gc.restore()

        // ★ 背景落定。数据系列（以及库现在自己画的网格）是当场就画的，
        //   不 flush 的话帧末提交的背景会把它们整个盖掉。
        //
        //   从前这里还要在 flush 之前手绘网格、之后手绘刻度文字（共约 40 行），
        //   并因此背着一句"顺序是硬约束"。现在坐标系由 `chart.axisStyle` 打开的
        //   库内绘制负责，且它画在**数据之前**，那条约束没有了。
        gc.flush()

        // 装饰（标题 / 图例 / 轴标题）+ **坐标系** + 数据系列。**不能带着变换调用**（会抛）。
        gc.charts.drawChart(chart, frame, gc.width, gc.height)

        // ★★ **末尾探针**（自检模式下才写，见 [selfTestDrawnFrames]）：这行是"整条绘制路径
        //    跑到了末尾"的唯一直接证据——本项目 GL 线程上的异常是静默吞掉的，
        //    少画了东西和抛了异常在画面上长得一样。**它必须在最后一行**：
        //    放到中间（比如 `drawChart` 之前）就证明不了后面的刻度文字那段跑过。
        //
        //    三个观测值在这里**一起发布**（帧计数 + 身份 + 图型）。这不是顺手：自检读它们
        //    是为了判"这一帧画完了没有"，而写在帧首的话，读到"身份变了"时本帧的 `drawChart`
        //    可能还没跑（数据系列还没注册号）——实测就是这样把第 ⑭ 条的读数读成了半帧的
        //    （号只涨了 1）。帧末发布之后，"图型 == k" 蕴含"用 k 的那一帧已经跑完"。
        if (SELFTEST) {
            selfTestDrawnFrames++
            selfTestLastChartIdentity = System.identityHashCode(chart)
            selfTestLastDrawnKind = selfTestFrameKind
        }
    }
}

/**
 * 给 [ChartLayout] 用的文字度量。
 *
 * <p>**为什么 demo 要自己实现**：`Gc` 内部有一支口径完全相同的笔，但它是 private 的、
 * 且 `Gc` 本身不实现 [ChartTextMetrics]。这是本 demo 记录的一条缺口（见设计文档 §6.3）。
 *
 * <p>**`lineHeight` 必须是 `字号 × ChartLayout.LINE_HEIGHT_FACTOR`，不是字体的真实行高**
 * ——布局要能被精确预测，读真实 ascent 会让"同一个外框 + 同一个配置"在不同字体下
 * 给出不同的绘图区。重写歪了的症状是"网格与标题带对不上"，而画面只是"看着有点挤"。
 */
private class GcTextMetrics(private val gc: Gc) : ChartTextMetrics {
    override fun width(text: String, fontSize: Float): Float {
        val saved = gc.fontSize
        gc.fontSize = fontSize
        val w = gc.measureText(text)
        gc.fontSize = saved
        return w
    }

    override fun lineHeight(fontSize: Float): Float = fontSize * ChartLayout.LINE_HEIGHT_FACTOR
}
