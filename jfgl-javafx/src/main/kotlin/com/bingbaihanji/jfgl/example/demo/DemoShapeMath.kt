package com.bingbaihanji.jfgl.example.demo

/**
 * 轨迹 → 图形的纯计算。**没有 GL 依赖，可以直接算术验证**（见 [selfCheckShapeMath]）。
 */
internal object ShapeMath {

    /** 抽稀的最小间距（设备像素）。 */
    const val SIMPLIFY_MIN_DIST = 8f

    /** 一个图形至少需要几个点：多边形 3 个（少于 3 不成面），曲线 2 个。 */
    const val MIN_POLYGON_POINTS = 3
    const val MIN_CURVE_POINTS = 2

    /**
     * 最小距离抽稀：只保留与**上一个已收下的点**距离 ≥ [minDist] 的点。
     *
     * <p>首点无条件收下；末点**也**收下（否则用户"拖到哪里为止"这个意图会丢），
     * 但**若末点正好等于最后一个已收下的点，就不再重复追加**——否则会多出一个重合顶点，
     * 让 [bezierFrom] 把一个"抖了一下又回到原地"的退化轨迹当成有效曲线，
     * 造出零长图形（占着拾取号与列表项、画面上什么都看不见）。
     * 这条有断言盯着（探针 ⑨）；在此之前的十条断言**一条都覆盖不到它**。
     *
     * @param trajectory `[x0,y0, x1,y1, ...]` 原始轨迹（每个鼠标拖拽事件一个点）
     * @param minDist    最小间距。**≤0、或点数 ≤2 时，返回的是入参本身（同一个数组引用，
     *                   不是拷贝）**——调用方别在拿到结果之后改写原数组。
     *                   本 demo 里这条别名已被封住：`Shape.PolygonShape` / `Shape.BezierShape`
     *                   构造时会 `copyOf()` 一份。
     * @return 抽稀后的扁平点数组
     */
    fun simplify(trajectory: FloatArray, minDist: Float = SIMPLIFY_MIN_DIST): FloatArray {
        val n = trajectory.size / 2
        if (n <= 2 || minDist <= 0f) return trajectory

        val out = FloatArray(trajectory.size)
        var count = 0
        var lastX = trajectory[0]
        var lastY = trajectory[1]
        out[0] = lastX; out[1] = lastY; count = 1

        // 中间点：与上一个**已收下**的点比距离（不是与上一个原始点比——
        // 那样的话缓慢拖动永远积累不到阈值，抽稀会失效）
        val minDistSq = minDist * minDist
        var i = 1
        while (i < n - 1) {
            val x = trajectory[i * 2]
            val y = trajectory[i * 2 + 1]
            val dx = x - lastX
            val dy = y - lastY
            if (dx * dx + dy * dy >= minDistSq) {
                out[count * 2] = x
                out[count * 2 + 1] = y
                count++
                lastX = x; lastY = y
            }
            i++
        }

        // 末点保留：用户"拖到哪里为止"是明确的意图，不能因为离得近就丢掉。
        // 但**已经在里面了就不再追加**——`count` 在这里恒 ≥ 1（首点在循环之前就已收下），
        // 所以不需要判空；只判"末点是否等于最后一个已收下的点"。
        val endX = trajectory[(n - 1) * 2]
        val endY = trajectory[(n - 1) * 2 + 1]
        if (out[(count - 1) * 2] != endX || out[(count - 1) * 2 + 1] != endY) {
            out[count * 2] = endX
            out[count * 2 + 1] = endY
            count++
        }
        return out.copyOf(count * 2)
    }

    /**
     * 轨迹 → 闭合多边形。点数不足 [MIN_POLYGON_POINTS]、或抽稀后的点**共线**（零面积）时
     * 返回 **null**（调用方丢弃这个图形）。
     *
     * <p>返回 null 而不是返回一个退化图形：一个 2 点的"多边形"面积恒为 0，
     * 画出来看不见，却会占一个拾取 ID 与一条列表项——用户会以为程序坏了。
     * **共线的那条同理**（它连点数都不止 2），理由见函数体里那段说明。
     */
    fun polygonFrom(trajectory: FloatArray): FloatArray? {
        val pts = simplify(trajectory)
        if (pts.size / 2 < MIN_POLYGON_POINTS) return null
        // ★ **还要判面积，不能只判点数** —— 这是终审发现的一条可复现缺陷：
        //   一条**共线**的轨迹（水平或竖直最容易：同一物理行上各事件换算出的 y 逐位相同，
        //   浮点上就是严格共线）会被 `simplify` **全部保留**（各点间距 ≥ 阈值）⇒
        //   点数够、判定通过 ⇒ 造出一个**零面积多边形**。
        //   而零面积的后果链是完整的：`fillPolygon` 零像素（`style = FILL` 时还不描边）
        //   ⇒ 画面上什么都没有；ID pass 光栅化不出任何片元 ⇒ 拾取缓冲里没有它的号
        //   ⇒ **点不中、框选也读不到、永远进不了选中集** ⇒
        //   **只能靠「清空画布」删掉它**，而那会连所有图形一起清掉。
        //   —— 这正是隔壁 [JfglDemoApp.commitShape] 的 `planar` 守卫要防的那类"幽灵对象"，
        //   而那个口径当时**只兑现在面状三兄弟上**。
        //   本函数 KDoc 原来给的理由就是**面积**（"一个 2 点的多边形面积恒为 0，画出来看不见"），
        //   判据却只写了点数——理由与判据没对齐。
        val b = boundsOf(pts)
        if (b.width <= 0f || b.height <= 0f) return null
        return pts
    }

    /** 轨迹 → 平滑曲线的控制点序列。点数不足 [MIN_CURVE_POINTS] 时返回 null。 */
    fun bezierFrom(trajectory: FloatArray): FloatArray? {
        val pts = simplify(trajectory)
        return if (pts.size / 2 < MIN_CURVE_POINTS) null else pts
    }
}

/**
 * 启动自检：用已知输入验证 [ShapeMath] 的纯计算。
 *
 * <p>**为什么不是 JUnit**：`jfgl-javafx` 没有 junit 依赖与 surefire 配置
 * （见本计划开头「关于验证口径」）。这里用仓库既有的模式——`ClickDslExample.kt:132-185`
 * 的启动自检——断言行打到 stdout。
 *
 * <p>**本函数只负责打印与计数**：失败时打 stderr、**并以非 0 退出**是**调用方**的事
 * （`JfglDemo.onInit` 里拿到返回值再 `exitProcess(1)`）。
 * 它自己不退出，是为了能当成一个普通函数被复用——一个中途结束进程的函数没法被
 * 任何别的东西调用。
 *
 * <p><strong>★ 整个函数体必须包在 try/catch 里</strong>，而这不是防御性编程的装饰：
 * 本自检**唯一的产物就是那个返回值**（调用方拿它决定退不退），而断言要查的恰恰是
 * "退化输入会不会崩"——所以**被测实现一崩，异常就在断言求值的那一刻抛出**。
 * 不接住的话它会穿出本函数、一路穿到调用方的 `if (failures != 0)` **之前**，
 * **`exitProcess(1)` 根本不执行，进程以 0 退出**——自检报"全绿"而实现是坏的。
 * 本仓库栽过同一个坑：`ChartVerifier` 明文记着"没接住的异常静默吞掉退出码
 * → 一个已经画错的校验器报退出码 0"，并把校验体整个包进了 try/catch。
 *
 * <p>`lastName` 是为了让异常**可定位**：它记着最后一条**完整跑完**的断言，
 * 所以报出来的位置是"在它之后、下一条求值时崩的"——按下面断言的固定顺序就能指到是哪条。
 * 没有它的话，一句"抛异常了"在这种自检里几乎等于没报。
 *
 * <p>**每一条都打印量到的实际值**：只打印"通过"的话，断言写反了也照样通过，
 * 那就成了橡皮图章。
 *
 * @return 失败条数（0 表示全过）；中途抛异常也算一条失败
 */
internal fun selfCheckShapeMath(): Int {
    var failures = 0

    /** 最后一条**完整跑完**的断言名。抛异常时用它定位。 */
    var lastName = "（还没开始）"

    fun check(name: String, pass: Boolean, detail: String) {
        println("[自检] $name -> $detail${if (pass) "" else "   ★ 失败"}")
        if (!pass) failures++
        lastName = name
    }

    try {
        runChecks { name, pass, detail -> check(name, pass, detail) }
    } catch (t: Throwable) {
        // 关键：把异常变成**一条失败**，而不是让它穿出去变成退出码 0。
        System.err.println(
            "[自检] 在「$lastName」之后、下一条断言求值时抛异常：" +
                "${t::class.simpleName}: ${t.message}"
        )
        failures++
    }
    return failures
}

/**
 * 全部断言。**只由 [selfCheckShapeMath] 调用**——拆成独立函数是为了让它的
 * `try/catch` 能包住**整个断言序列**（见那边的说明）。
 *
 * @param check 记一条断言的函数，由调用方提供（负责打印与计数）
 */
private fun runChecks(check: (String, Boolean, String) -> Unit) {

    // ① 三点共线且间距都 ≥ 阈值 → **三个点全保留**（抽稀不是降采样）
    val collinear = floatArrayOf(0f, 0f, 20f, 0f, 40f, 0f)
    val s1 = ShapeMath.simplify(collinear, minDist = 8f)
    check("simplify 三点共线（间距 20 ≥ 8，都该留）", s1.size == 6, "保留 ${s1.size / 2} 点：${s1.toList()}")

    // ② 三点都挤在阈值内 → 只留首末（末点无条件保留）
    val dense = floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f)
    val s2 = ShapeMath.simplify(dense, minDist = 8f)
    check("simplify 三点密集（间距 1 < 8，中间点该掉）", s2.size == 4, "保留 ${s2.size / 2} 点：${s2.toList()}")

    // ②b ★ 边界：距离**恰好等于**阈值时该保留（`>=` 而不是 `>`）
    // 没有这一条的话，"把 >= 改成 >" 这种变异一条断言都打不掉——
    // 上面所有用例的距离都远离阈值，判据写松写紧都看不出差别。
    val exact = floatArrayOf(0f, 0f, 8f, 0f, 16f, 0f)
    val s2b = ShapeMath.simplify(exact, minDist = 8f)
    check("simplify 距离恰等于阈值（应保留，判据是 >=）", s2b.size == 6, "保留 ${s2b.size / 2} 点：${s2b.toList()}")

    // ②c ★★ 判据的"分母"——距离是跟**上一个已收下的点**比，不是跟上一个**原始**点比。
    // 这是 [ShapeMath.simplify] 最容易写错、且错了看不出来的一处：跟原始点比的话，
    // 缓慢拖动永远积累不到阈值，抽稀整个失效（画面上只是"曲线的点比预想的多"）。
    //
    // 上面 ①②②b 三条**一条都区分不了**这两种语义——它们都只有 2~3 个点，
    // `i < n-1` 的循环最多跑一轮，"上一个收下的"与"上一个原始点"在循环内重合。
    // 必须有**至少两个中间点**、且第一个中间点被抽掉，才能把两者分开：
    //   真实现：5 被抽掉（离 0 只有 5 < 8）→ 10 与 0 比（10 ≥ 8）→ 收下 → 共 3 点
    //   变异版：5 被抽掉后 last 无脑前移到 5 → 10 与 5 比（5 < 8）→ 也抽掉 → 共 2 点
    val dense2 = floatArrayOf(0f, 0f, 5f, 0f, 10f, 0f, 15f, 0f)
    val s2c = ShapeMath.simplify(dense2, minDist = 8f)
    check(
        "simplify 距离比的是上一个**已收下**的点（应保留 3 点）",
        s2c.size == 6, "保留 ${s2c.size / 2} 点：${s2c.toList()}"
    )

    // ③ 退化输入不该崩
    val s3 = ShapeMath.simplify(FloatArray(0), minDist = 8f)
    check("simplify 空输入", s3.isEmpty(), "得到 ${s3.size} 个 float")

    // ④ 多边形：2 点必须被拒（不是画一个零面积图形）
    val twoPoints = floatArrayOf(0f, 0f, 10f, 10f)
    check("polygonFrom 两点应为 null", ShapeMath.polygonFrom(twoPoints) == null,
        "得到 ${ShapeMath.polygonFrom(twoPoints)?.size ?: "null"}")

    // ⑤ 多边形：3 点应被接受
    val threePoints = floatArrayOf(0f, 0f, 10f, 0f, 5f, 8f)
    check("polygonFrom 三点应通过", ShapeMath.polygonFrom(threePoints)?.size == 6,
        "得到 ${ShapeMath.polygonFrom(threePoints)?.size ?: "null"} 个 float")

    // ⑤b ★★ 多边形：**共线**的三点必须被拒（不是画一个零面积图形）。
    //
    // 这条盯的是"判据只写了点数、没写面积"那类缺陷：共线的三点**点数够**
    // （`simplify` 会全部保留，因为各点间距都 ≥ 阈值），于是只判点数的版本放行，
    // 造出一个零面积多边形——`fillPolygon` 零像素（`style = FILL` 时还不描边）
    // ⇒ 画面上什么都没有；ID pass 光栅化不出片元 ⇒ 拾取缓冲里没有它的号
    // ⇒ **点不中、框选读不到、永远进不了选中集** ⇒ 只能靠「清空画布」删掉它。
    //
    // 为什么必须有它：④ 的两点**是被点数判据挡下的**，⑤ 的三点**有面积**
    // ——**把面积判据整个删掉，这两条照样全过**。实测（变异 A）：删掉那两行 ⇒ 只有这条倒。
    val collinearThree = floatArrayOf(0f, 0f, 20f, 0f, 40f, 0f)   // 与 ① 同形的严格共线轨迹
    check(
        "polygonFrom 三点共线（零面积）应为 null",
        ShapeMath.polygonFrom(collinearThree) == null,
        "得到 ${ShapeMath.polygonFrom(collinearThree)?.let { "${it.size} 个 float：${it.toList()}" } ?: "null"}"
    )

    // ⑥ 曲线：2 点刚好够（一条直线段）
    check("bezierFrom 两点应通过", ShapeMath.bezierFrom(twoPoints)?.size == 4,
        "得到 ${ShapeMath.bezierFrom(twoPoints)?.size ?: "null"} 个 float")

    // ⑦ boundsOf 取的是外接框
    val b = boundsOf(floatArrayOf(5f, 9f, -3f, 2f, 8f, -1f))
    check("boundsOf 外接框", b.x == -3f && b.y == -1f && b.width == 11f && b.height == 10f,
        "x=${b.x} y=${b.y} w=${b.width} h=${b.height}（期望 -3,-1,11,10）")
    // ⑧ ★ **这一条也要标号**：它一直没编号，于是"照标号数断言"会数出 10 条（而实际 11 条），
    //    下面 ⑨ 的说明里也是按"⑦⑧ 查的是 boundsOf"来数的——编号补齐才对得上。
    check("boundsOf 空输入为零矩形", boundsOf(FloatArray(0)).width == 0f, "w=${boundsOf(FloatArray(0)).width}")

    // ⑨ ★ 末点去重：末点**等于**最后一个已收下的点时，不该再追加一个重合顶点。
    //
    // 这条分支不是装饰，它是有行为的：轨迹 `[100,50, 101,50, 100,50]`（原地抖了一下再回来）
    // 抽稀后 **1 个点** → `bezierFrom` 返回 null（图形被丢弃，状态栏提示"拖得太短"）；
    // 而去重一旦失效 → **2 个重合点** → `bezierFrom` 非 null → 造出一个**零长 BezierShape**，
    // 占着一个拾取号与一条列表项、画面上什么都看不见——正是 `polygonFrom` 的 KDoc
    // 要避免的那类"用户以为程序坏了"。
    //
    // 为什么必须有它：前面 ① ② ②b ②c ⑤ ⑤b 的末点都**不同于**最后一个已收下的点，
    // ④⑥ 走 `n <= 2` 提前返回，③ 是空输入，⑦⑧ 查的是 `boundsOf`。
    // 所以**删掉那条去重分支，十一条断言会全部照过**——它是全文件唯一一条没有探针盯着的实现分支。
    val jitter = floatArrayOf(100f, 50f, 101f, 50f, 100f, 50f)
    val s9 = ShapeMath.simplify(jitter, minDist = 8f)
    check(
        "simplify 末点与上一个已收下的点重合时不重复追加",
        s9.size == 2, "得到 ${s9.size / 2} 点：${s9.toList()}（期望 1 点、2 个 float）"
    )

    // ⑩ ★★ 虚线模式的**可用性**。判据是**行为**：被放行的输入，迭代有上界。
    //
    // 为什么不能只钉"参数是正的有限数"：那条**与被测的判据是同一个命题**，于是那样的表
    // 在结构上**不可能在"放行集"里找到反例**——它只是把判据重推了一遍，是橡皮图章。
    // 质量审查正是这么指出上一版的：那张 14 组表把 `1e-30f` 列在"应放行"里，而小端反例
    // （`k` 回绕 ⇒ 内层 while 永不退出 + 每段亚像素 ⇒ 预览静默消失）恰恰落在它声称
    // 已覆盖的那一侧。
    //
    // 下面第二条与第五条是**同一类输入的两端**：`NaN` 走 `> 0f` 被挡、`1e-30f` 走
    // `MAX_DASH_SEGMENTS` 被挡——**少任何一条，判据就只关掉了一半**。
    val dashNormal = isUsableDashPattern(6f, 4f, 500f)
    check("虚线模式 6f/4f 可用（500px ⇒ 50 段 ≤ 4096）", dashNormal, "得到 $dashNormal（期望 true）")

    val dashNan = isUsableDashPattern(Float.NaN, 4f, 500f)
    check("虚线模式 NaN 不可用（NaN 与任何数比较都是 false）", !dashNan, "得到 $dashNan（期望 false）")

    val dashInf = isUsableDashPattern(Float.POSITIVE_INFINITY, 4f, 500f)
    check("虚线模式 +Inf 不可用（Inf > 0f 为真，那条挡不住它）", !dashInf, "得到 $dashInf（期望 false）")

    val dashOverflow = isUsableDashPattern(Float.MAX_VALUE, Float.MAX_VALUE, 500f)
    check(
        "虚线模式 和溢出到 Inf 不可用（两参数各自都有限）",
        !dashOverflow, "得到 $dashOverflow（期望 false）"
    )

    val dashTiny = isUsableDashPattern(1e-30f, 1e-30f, 500f)
    check(
        "虚线模式 极小 pattern 不可用（k 回绕 ⇒ 死循环；旧表漏掉的反例）",
        !dashTiny,
        "得到 $dashTiny（期望 false；其段数为 ${500f / (1e-30f + 1e-30f)}，远超 $MAX_DASH_SEGMENTS）"
    )

    // ★ 边界点：`MAX_VALUE / 2 + MAX_VALUE / 2` 恰好 = `MAX_VALUE`，**不溢出** ⇒ 放行。
    // 它钉住"和溢出"那一项的位置：判据若写成 `>=` 或把上限收紧，这一条会先倒。
    val dashEdge = isUsableDashPattern(Float.MAX_VALUE / 2f, Float.MAX_VALUE / 2f, 500f)
    check(
        "虚线模式 和的边界（MAX/2 各一，和恰好 MAX 不溢出）可用",
        dashEdge, "得到 $dashEdge（期望 true；其段数为 ${500f / (Float.MAX_VALUE / 2f + Float.MAX_VALUE / 2f)}）"
    )
}
