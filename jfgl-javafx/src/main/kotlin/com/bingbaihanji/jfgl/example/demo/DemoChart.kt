package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.chart.*
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.util.Rect

/** 绘图区底色 / 网格线 / 刻度文字 / 数据线。 */
private const val PLOT_BG = 0xFF1A1D22.toInt()
private const val GRID = 0xFF3A3F47.toInt()
private const val TICK_TEXT = 0xFFC0C6CF.toInt()

/**
 * 刻度文字的字号。
 *
 * <p>**单独抽出来是因为它有三个隐式消费者**：刻度文字本身、`tickLabelReserve` 声明的预留量、
 * 以及四处对齐偏移（y 标签右端留白、y 标签的半字高、x 标签的基线偏移）。
 * 字号一改，只有写字的那一处会跟着改，**另外几处会静默错位**——而画面只是"刻度字
 * 压到了轴标题上 / 没对齐"，不像一个字号问题。所以让它们共用这一个常量。
 */
private const val TICK_FONT = 12f

/** 刻度文字与绘图区之间的缝（像素）。y 标签的右端留它，x 标签的基线也按它错开。 */
private const val TICK_GAP = 6f

/**
 * 刻度文字的预留量，交给 `Chart.tickLabelReserve`。
 *
 * <p>**它必须 ≥ `TICK_GAP + 最宽标签的宽度`**：y 标签是**右对齐**画的
 * （`plot.x - w - TICK_GAP`），所以预留小于这个和，标签的**左端会压进左侧的轴标题带**。
 * 12px 下三字符标签（`"120"`）约 18px，故 `6 + 18 = 24`。
 *
 * <p>**早先这里是 `18f`——那正是"标签压进「金额 (万元)」带约 2px"的成因**
 * （质量评审算出来的：标签左缘落在 `plot.x - 24`，而轴标题带右缘是 `plot.x - 18 - 4`）。
 * 更彻底的做法是运行时拿 `measureText` 量最宽标签再设（demo 手里就有这把尺），
 * 但那要求每帧改缓存住的 `Chart`，代价与收益不成比例——**12px 固定字号下 24f 够用**，
 * 而字号一旦要可变，这里就是第一个该改成动态量的地方。
 */
private const val TICK_RESERVE = TICK_GAP + 18f

/** 图表外框相对画布的边距。给标题带、图例带与状态栏留的地方。 */
private const val FRAME_MARGIN = 40f

/** 两条系列的配色。 */
private const val COLOR_SALES = 0xFF4FC3F7.toInt()
private const val COLOR_COST = 0xFFFF8A65.toInt()

/**
 * 本进程是否处于合成事件自检模式（`-Djfgl.demo.selftest=1`）。
 *
 * <p><strong>★ 判定走 [selfTestEnabled]，不是在这里再解析一遍属性</strong>
 * （[SELFTEST_PROPERTY] 那个常量在 `JfglDemo.kt` 里，两处引用同一个常量）。
 * 这里本来写的是 `System.getProperty(SELFTEST_PROPERTY) == "1"`——**属性名一样、
 * 认得的值不一样**：`JfglDemo` 那一侧认 `1` 与 `true`，这一侧只认 `1`。
 * 于是 `-Djfgl.demo.selftest=true` 下脚本照跑、而**本文件的三个观测值一个都不写**，
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
     * **只给启动一致性自检读**（`JfglDemo.kt` 的 `verifySelfTestFlagsAgree`）：
     * 本文件那个 `SELFTEST` 的当前值，用来和 `JfglDemo` 那一侧的开关比对。
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
     * **回到原来那个**（[cachedCharts] 那张表生效），否则每绕一圈就漏两块 GPU 缓冲。
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
     * 已建好的图表，**按图型下标各留一份**。
     *
     * <p><strong>★ 图表必须缓存，不能每帧 `build()`。</strong>
     * `ChartRenderer` 用 `IdentityHashMap` 按 **`Series` 对象身份**缓存每个系列的 GPU 缓冲
     * 与拾取号（`ChartRenderer` 里那两处 `computeIfAbsent`：`buffers` 与
     * `pickIds.computeIfAbsent(series, pickRegistry::register)`），而这两张 map
     * **只在 `ChartRenderer.dispose()` 里清空，没有任何"这个系列不再画了"的回收接口**。
     *
     * <p>于是每帧新建 `Series` 的后果是：**每帧泄漏一块 GPU 缓冲**（显存不回），
     * 并且**每帧消耗两个拾取号**。耗尽时 `PickRegistry` 会抛异常——而 GL 线程上的异常
     * 在本项目是**静默吞掉**的，所以表现是"图表忽然不画了，没有任何报错"。
     * 这正是本仓库头号敌人的那类缺陷，而在画面上完全看不出来（前几百帧一切正常）。
     *
     * <p><strong>★ 为什么是一张表、而不是"只留最后一个"</strong>：单槽版本下，
     * `LINE → BAR → LINE` 每绕一圈，切回 `LINE` 时槽里装的是 `BAR` ⇒ **又 `build()` 一次**
     * ⇒ 造出**两个新的 `Series` 对象**（身份不同 ⇒ `IdentityHashMap` 各留一份）
     * ⇒ 多两块 GPU 缓冲 + 两个拾取号。
     * **所以泄漏量随"切换次数"增长，而不是随"有几种图型"封顶**——
     * 单槽版本里写的那句"菜单只有四项、上限是 8 个"是**假的**。
     * 用表之后那个上限才真的成立：**4 种图型 × 2 条系列 = 8 个**，
     * 而且来回切**不再分配**（这正是 Task 11 第 ⑭ 条要断言的东西）。
     *
     * <p>要根治需要 `ChartRenderer` 提供"释放这些系列"的接口——
     * 那是扩库的 API 面，**记进 `CLAUDE.md` 的待办**（对应设计文档 §6.6），不在本 demo 里做。
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
        // `getOrPut` 把**同一个** Chart 一直发给同一个图型——这正是 ChartRenderer 要的：
        // 它按 Series 的**对象身份**缓存，只要对象不变，那两张 map 就不增长。
        return cachedCharts.getOrPut(k) { build(k) }
    }

    /**
     * 造出图表（纯计算）。**不持有 GL 资源**，所以可以随便什么时候调——但**别每帧调**，
     * 理由见 [cachedChart]。
     *
     * @param kindIndex [KINDS] 里的下标
     */
    private fun build(kindIndex: Int): Chart {
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
            .tickLabelReserve(ChartSide.BOTTOM, TICK_RESERVE)
            .tickLabelReserve(ChartSide.LEFT, TICK_RESERVE)
            .padding(ChartInsets(8f, 8f, 8f, 8f))
            .apply {
                addLayer("数据").add(styleFor(Series("销售额", data, type).color(COLOR_SALES)))
                    .add(styleFor(Series("成本", data2, type).color(COLOR_COST)))
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
    private fun styleFor(s: Series): Series = when (s.type()) {
        ChartType.BAR -> s.baseline(0f).categoryGap(0.25f).barGap(0.2f)
        ChartType.AREA -> s.baseline(0f).fillAlpha(0.35f)
        ChartType.SCATTER -> s.markerSize(4f)
        else -> s.lineWidth(2f)
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
        val xTicks = chart.axis(0).ticks()
        val yTicks = chart.axis(1).ticks()

        // 绘图区底色。**放在 save/restore 里**——`gc.fill` 是**可变状态**，
        // 写在外面会泄漏给本帧后续的图元（今天无害，只因为 `drawScene` 紧接着就 return）。
        // 库自己的 `drawChart` 是刻意做成状态中立的，这个入口跟上更稳。
        gc.save()
        gc.fill = PLOT_BG
        gc.fillRect(plot.x, plot.y, plot.width, plot.height)

        // 网格。**库没有辅助**，全仓唯一一份手写循环在 README.md
        gc.lineWidth = 1f
        gc.stroke = GRID
        for (t in yTicks) {         // y 轴要翻：值越大越靠上
            if (!t.isMajor()) continue
            val sy = plot.y + plot.height - t.position().toFloat()
            // 0 那条横线**也不画**，理由与下面 x 轴那条**逐字相同**（终审指出的一处不对称）：
            // y 轴的 0 刻度映射到绘图区**下边缘**（`position() == 0` ⇒ `sy == plot.y +
            // plot.height`），画了只是把那条边加重一道。刻度文字照旧画（它在下面那一段里），
            // 所以"0"这个读数不会丢——丢的只是与边界重合的那条线。
            if (t.value() == 0.0) continue
            gc.drawLine(plot.x, sy, plot.x + plot.width, sy)
        }
        for (t in xTicks) {
            if (!t.isMajor()) continue
            val sx = plot.x + t.position().toFloat()
            // 0 那条竖线**不画**：它与绘图区左边缘重合，画了只是把网格加重一道。
            // （注意：这里**不是**"让给 y 轴"——本 demo 与 `ChartDecorations` 都**不画轴线**，
            //   绘图区没有左/下边框线。早年的注释说"让给 y 轴"，那个"对象"并不存在。）
            // ★ 上面 y 轴那条**同理**——这一条早先只写了这半边，y 轴那条一直在画
            //   （终审指出的不对称），本轮补齐。
            if (t.value() == 0.0) continue
            gc.drawLine(sx, plot.y, sx, plot.y + plot.height)
        }
        gc.restore()

        // ★ 网格落定。数据系列是当场就画的，不 flush 就没有"网格 → 数据 → 标注"的夹心 z 序
        gc.flush()

        // 装饰（标题 / 图例 / 轴标题）+ 数据系列。**不能带着变换调用**（会抛）。
        gc.charts.drawChart(chart, frame, gc.width, gc.height)

        // 刻度文字画在数据之上。只有主刻度的 label 非空，中/次是空串。
        // **三个偏移都由 `TICK_FONT` 推出来**，不写死——否则改字号时只有 `fontSize` 跟着改，
        // 另三处静默错位，而画面只是"刻度字压到轴标题上 / 没对齐"，不像字号问题。
        gc.save()
        gc.fontSize = TICK_FONT
        gc.fill = TICK_TEXT
        for (t in yTicks) {
            if (!t.isMajor()) continue
            val sy = plot.y + plot.height - t.position().toFloat()
            val w = gc.measureText(t.label())
            // 右对齐、右端留 `TICK_RESERVE` 里那个 6px 的缝；`+0.35em` 让基线落在半个字面高处
            gc.drawText(t.label(), plot.x - w - TICK_GAP, sy + TICK_FONT * 0.35f)
        }
        for (t in xTicks) {
            if (!t.isMajor()) continue
            val sx = plot.x + t.position().toFloat()
            val w = gc.measureText(t.label())
            gc.drawText(t.label(), sx - w / 2f, plot.y + plot.height + TICK_FONT * 1.3f)
        }
        gc.restore()

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
