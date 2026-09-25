package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * SDF 文本的端到端**像素级校验器**。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>文本子系统的缺陷几乎全部属于「静默错误输出」：字形画歪了、被裁了、
 * 字距不对、过渡带糊了——**没有一个会报错**，只会让画面悄悄不对。
 * 单元测试能覆盖纯算术的那两块（`SdfGenerator`、`TextLayout`），
 * 但"这些顶点进了 GL 之后栅格化出来是什么样"只有回读像素才能回答。
 *
 * <h2>核心验收点：过渡带宽度不随缩放变宽</h2>
 *
 * <p>同一个字以 24px 与 192px 绘制，**边缘过渡带宽度应大致恒定**（约 1~2 个屏幕像素）。
 * 位图拉伸时过渡带会随缩放线性变宽（24→192 是 8 倍缩放，过渡带也宽 8 倍）——
 * **这是唯一能把"SDF 生效"与"位图被放大"区分开的断言**，
 * 也是这个子系统的存在理由本身。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.TextVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败。它自己关窗退出，不需要手动关闭。
 *
 * <p>**不要用 `mvn exec:java`**：本项目里它必崩（openglfx 会链接到另一份
 * `com.sun.prism.GraphicsPipeline`）。必须 `exec:exec` fork 独立 JVM。
 * 某些 shell 会把 `-D` 前缀吃掉，所以每个 `-D...` 参数都加引号。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 背景亮度：`glClearColor(0.2f, ...)` 对应的 0x33。 */
private const val BG = 0x33

/** 墨迹判定阈值：亮度超过背景这么多就算被覆盖。 */
private const val INK_THRESHOLD = BG + 8

/** 饱和阈值：达到它就算"完全在字形内部"。 */
private const val FULL_THRESHOLD = 235

/** 文本的拾取 ID。0 恒定表示"什么都没命中"。 */
private const val INK_ID = 1

/**
 * 校验器启动入口。
 *
 * <p>函数名不叫 `main`：同包已有顶层 `main()`（[PipelineExample]），
 * 两个同名顶层函数会让 `import ...example.main` 报重载歧义，而同包内无法用别名区分。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，文档里的命令行因此照常可用。
 */
@JvmName("main")
fun textVerifyMain() {
    Application.launch(TextVerifierApp::class.java)
}

class TextVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /**
     * **刚刚渲染完的那一帧**的序号（从 0 开始）。
     *
     * <p>语义是"已完成"而不是"进行中"：`onFrame` 里 [drawScene] 读到的值与
     * 随后 `onRender` 里 [verifyOnce] 读到的值相同，两边对"现在是第几帧"没有分歧。
     */
    private var rendered = 0

    /** 帧 2 量到的"中文测试"墨迹包围盒，供帧 3 比对位置稳定性。 */
    private var inkAtFrameTwo: Ink? = null

    /** `drawText("")` 的返回值，在 [drawScene] 里记下来——校验时不能重画。 */
    private var emptyAdvance = Float.NaN

    /** `drawText("国", ...)` 的返回值。 */
    private var guoDrawAdvance = Float.NaN

    // —— 场景坐标（用户坐标；校验器依赖"用户坐标 1:1 映射到设备像素"这一前提）——

    /** "国"：64px，用来量推进宽度、基线、拾取。 */
    private val guoX = 40f
    private val guoBaseline = 100f
    private val guoSize = 64f

    /** "一"：与"国"同字号同基线，用来验证不同高度的字共用一条基线。 */
    private val yiX = 160f

    /** "中文测试"：48px，用来验证"新字形第二次出现时位置稳定"。 */
    private val lineX = 40f
    private val lineBaseline = 210f
    private val lineSize = 48f

    /** 过渡带的小尺寸端。 */
    private val smallX = 40f
    private val smallBaseline = 320f
    private val smallSize = 24f

    /** 过渡带的大尺寸端。24 → 192 是 8 倍缩放，两个极端更容易拉开差距。 */
    private val bigX = 40f
    private val bigBaseline = 560f
    private val bigSize = 192f

    /** 空串的绘制点：这一带必须一个非背景像素都没有。 */
    private val emptyX = 600f
    private val emptyY = 100f
    private val emptySize = 64f

    // —— 扫描区域（用户坐标，左闭右开）——
    // 每个区域只覆盖一个字符串，彼此的墨迹不会串进来。
    private val regionGuo = intArrayOf(0, 20, 150, 140)
    private val regionYi = intArrayOf(150, 20, 300, 140)
    private val regionLine = intArrayOf(0, 140, 320, 270)
    private val regionSmall = intArrayOf(0, 280, 130, 350)
    private val regionBig = intArrayOf(0, 370, 300, 598)
    private val regionEmpty = intArrayOf(560, 30, 760, 140)

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        // ★ 回读拒绝守卫（实现见 MsaaVerifier.kt 的 requirePixelReadback）：本校验器的读数
        //   全部来自 glReadPixels，而多采样画布上那次调用是**非法操作**——它会读回全 0，
        //   然后让下面每一条断言报"画面全黑"式的假失败。
        requirePixelReadback(bridge)
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Text Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 画场景。
     *
     * <p>帧 0、1 只画一句预热文本（在画面右下方，与任何扫描区域都不重叠）：
     * 让 stb、图集首次分配与 JIT 先热起来。目标字符串在帧 2 才**第一次**出现，
     * 这样帧 2 → 帧 3 的比对才是在测"新字形第二次出现时位置稳定"，
     * 而不是"一直就在那儿的字形没动"。
     */
    private fun drawScene(gc: Gc) {
        gc.fill = 0xFFFFFFFF.toInt()
        gc.pickId = 0

        if (rendered < 2) {
            gc.fontSize = 48f
            gc.drawText("预热", 500f, 480f)
            return
        }

        // "国"：量推进宽度、基线、拾取都用它
        gc.fontSize = guoSize
        gc.pickId = INK_ID
        guoDrawAdvance = gc.drawText("国", guoX, guoBaseline)
        gc.pickId = 0

        // "一"：与"国"同基线，验证相对高度符合度量
        gc.fontSize = guoSize
        gc.drawText("一", yiX, guoBaseline)

        // "中文测试"：帧 2 首次出现，帧 3 再画一遍，比对包围盒
        gc.fontSize = lineSize
        gc.drawText("中文测试", lineX, lineBaseline)

        // 过渡带的小尺寸端
        gc.fontSize = smallSize
        gc.drawText("口", smallX, smallBaseline)

        // 过渡带的大尺寸端
        gc.fontSize = bigSize
        gc.drawText("口", bigX, bigBaseline)

        // 空串：一个顶点都不该发
        gc.fontSize = emptySize
        emptyAdvance = gc.drawText("", emptyX, emptyY)
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        val justRendered = rendered
        rendered++

        if (justRendered < 2) {
            return
        }
        val gc = bridge.gc() ?: return

        if (justRendered == 2) {
            // 只记下帧 2 的墨迹包围盒，不做任何断言——校验统一放在帧 3，
            // 这样"位置稳定"才能和其余断言用同一次回读。
            inkAtFrameTwo = inkIn(frameOf(bridge), regionLine)
            return
        }

        // 校验过程本身抛出异常时必须**以非零码退出**。
        //
        // 理由不是洁癖：这部分代码在 GL 线程上跑，一旦抛出去，线程死掉、
        // 汇总行与 exitProcess 都走不到，JVM 会因为"最后一个非守护线程结束"而
        // 以 0 退出——**一个已经打印了 FAIL 的校验器报出退出码 0**，
        // 正是本仓库最忌讳的那种"静默的绿"。
        // 实测触发方式：把 FLOATS_PER_STYLE_LEVEL 改回 2，深层嵌套的 save()
        // 会在 styleFloats[floatBase + 2] 上越界。
        try {
            verifyAll(bridge, gc)
        } catch (t: Throwable) {
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun frameOf(bridge: FXGLTransfer): Frame {
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        return Frame(w, h)
    }

    private fun verifyAll(bridge: FXGLTransfer, gc: Gc) {
        val frame = frameOf(bridge)

        println("=== JFGL 文本像素校验（帧缓冲 ${frame.w}x${frame.h}）===")

        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        val inkGuo = inkIn(frame, regionGuo)
        val inkYi = inkIn(frame, regionYi)
        val inkLine = inkIn(frame, regionLine)
        val inkSmall = inkIn(frame, regionSmall)
        val inkBig = inkIn(frame, regionBig)

        // —— 1. 推进宽度 ——
        println("\n-- 推进宽度 --")
        gc.fontSize = guoSize
        val measureGuo = gc.measureText("国")
        report("空串的推进宽度为 0", emptyAdvance == 0f,
            "drawText(\"\") 返回 $emptyAdvance")
        report("空串的 measureText 为 0", gc.measureText("") == 0f,
            "measureText(\"\")=${gc.measureText("")}")
        report("全角汉字的推进宽度约等于一个字号", abs(measureGuo - guoSize) <= guoSize * 0.1f,
            "measureText(\"国\")=$measureGuo，期望≈$guoSize（汉字是全角，1 em = 1 字号）")
        report("drawText 的返回值与 measureText 一致", abs(guoDrawAdvance - measureGuo) < 0.5f,
            "drawText=$guoDrawAdvance，measureText=$measureGuo——两者都应当等于" +
                "度量×缩放，只差浮点舍入")
        report("两个字的推进宽度是单个的两倍",
            abs(gc.measureText("国国") - 2f * measureGuo) < 0.01f,
            "measureText(\"国国\")=${gc.measureText("国国")}，期望 ${2f * measureGuo}")
        report("墨迹不超出推进宽度",
            inkGuo.count > 0 && inkGuo.minX >= guoX.toInt()
                    && inkGuo.maxX < (guoX + measureGuo).toInt(),
            "墨迹 x∈[${inkGuo.minX},${inkGuo.maxX}]，推进区间 " +
                "[${guoX.toInt()}, ${(guoX + measureGuo).toInt()})")

        // 代理对：一个增补平面字符是**一个**码点。用 charAt 逐 char 走会得到两倍。
        // 两个串在字体里都不存在，因此都是 1 个 .notdef 字形——只要码点数相同，
        // 推进宽度就必然相同，与 .notdef 的具体宽度无关。
        //
        // 两个串都用转义写、不用字面量：U+FFFF 是非字符，emoji 也不是每个编辑器
        // 都能安全保存，而这条断言的正确性不该取决于源文件的编码。
        val pairAdvance = gc.measureText("\uD83D\uDE00")   // 代理对，U+1F600
        val singleMissing = gc.measureText("\uFFFF")         // 单个 BMP 缺失码点
        report("增补平面字符按一个码点计算", abs(pairAdvance - singleMissing) < 0.01f,
            "代理对(U+1F600)=$pairAdvance，单个缺失码点(U+FFFF)=$singleMissing——" +
                "用 charAt 逐 char 迭代会得到两倍")

        // —— 2. 基线位置 ——
        println("\n-- 基线位置 --")
        // 「国」的墨迹**不是**整体在基线之上——这不是排版错误，是字体的性质：
        // simhei 里它的字形框底边本就在基线之下（glyf bbox yMin = -26，
        // unitsPerEm = 256，即下探 0.102 em）。64px 字号下约 6.5 个设备像素。
        // 这个数字用 fontTools 独立读 simhei.ttf 的 glyf 表复现过，与 stb 读到的一致。
        // 所以这条断言量的是"墨迹基本贴着基线、没有整体掉到基线之下"，上界取
        // 字号的 0.2 em：真实值 0.102 em 稳过，而把 (x,y) 当成文本框左上角
        // 会让整块墨迹下移约 0.78 em（「一」那条断言当场失败）。
        report("「国」的墨迹贴着基线而不是掉在基线之下",
            inkGuo.count > 0 && inkGuo.minY < guoBaseline.toInt()
                    && inkGuo.maxY <= guoBaseline.toInt() + (guoSize * 0.2f).toInt(),
            "墨迹 y∈[${inkGuo.minY},${inkGuo.maxY}]，基线 y=${guoBaseline.toInt()}，" +
                "允许下探到 ${guoBaseline.toInt() + (guoSize * 0.2f).toInt()}（字号的 0.2 em）；" +
                "simhei 的「国」字形框本身就低于基线 0.102 em")
        report("「一」的墨迹整体在基线之上",
            inkYi.count > 0 && inkYi.maxY <= guoBaseline.toInt() + 2,
            "墨迹 y∈[${inkYi.minY},${inkYi.maxY}]")
        report("「一」比「国」矮（相对高度符合度量）",
            inkYi.count > 0 && inkGuo.count > 0
                    && inkYi.minY > inkGuo.minY && inkYi.maxY < inkGuo.maxY,
            "「一」y∈[${inkYi.minY},${inkYi.maxY}] vs 「国」y∈[${inkGuo.minY},${inkGuo.maxY}]")

        // —— 3. ★ 核心验收点：过渡带宽度不随缩放变宽 ——
        println("\n-- ★ SDF：过渡带宽度不随缩放变宽（核心验收点）--")
        val smallBand = bandIn(frame, regionSmall, (inkSmall.minY + inkSmall.maxY) / 2)
        val bigBand = bandIn(frame, regionBig, (inkBig.minY + inkBig.maxY) / 2)
        report("大字号处存在可测的边缘过渡带", bigBand[0] >= 1,
            "192px 的过渡带=${bigBand[0]}px。测出 0 说明边缘是硬跳变——" +
                "SDF 着色器的 smoothstep 没生效（例如写成了 vec4(vColor.rgb, vColor.a * a)，" +
                "那会让每个被覆盖的像素都饱和）")
        report("★ 大字号笔画内部饱和（证明上面的测量没有空转）", bigBand[1] >= 4,
            "192px 的饱和连续段=${bigBand[1]}px，期望 ≥4")
        if (smallBand[0] < 1) {
            report("小字号处存在可测的过渡带", false,
                "24px 的过渡带测出 0px：笔画只有约 2px 宽，整条都在过渡区内，" +
                    "或边缘恰好落在像素中心上。这是**测量方法**的局限而不是缺陷——" +
                    "请把小字号端换成 32px 重测，并如实记录改了什么，" +
                    "不要直接把这条断言删掉")
        } else {
            // 24 → 192 是 8 倍缩放。SDF 的过渡带由屏幕空间的 fwidth 决定，
            // 与缩放无关；位图拉伸时它会随缩放线性变宽。
            report("★ 192px 的过渡带不随 8 倍缩放变宽",
                bigBand[0] <= smallBand[0] * 2 + 1,
                "24px=${smallBand[0]}px，192px=${bigBand[0]}px，阈值 ${smallBand[0] * 2 + 1}px。" +
                    "位图被放大时过渡带会宽约 8 倍——这条断言是唯一的区分手段")
        }

        // —— 4. 空串 ——
        println("\n-- 空串 --")
        val inkEmpty = inkIn(frame, regionEmpty)
        report("空串不画任何东西", inkEmpty.count == 0,
            "空串位置有 ${inkEmpty.count} 个非背景像素")

        // —— 5. 新字形首帧后的位置稳定 ——
        println("\n-- 新字形第二次出现时位置稳定 --")
        val stored = inkAtFrameTwo
        report("帧 3 与帧 2 的墨迹包围盒逐值一致",
            stored != null && stored.minX == inkLine.minX && stored.minY == inkLine.minY
                    && stored.maxX == inkLine.maxX && stored.maxY == inkLine.maxY,
            "帧2=$stored 帧3=$inkLine")
        // 说明：这条断言的区分力有限——命中缓存与重新分配在"uv 指向同一份位图"时
        // 会得到相同的像素。它拦得住的是 uv 漂移、图集被写坏、字形被重新光栅化成
        // 不同的形状这几类。真的"每帧重新分配"要断言的是槽位计数，那需要另外的探针。

        // —— 6. 拾取 ——
        println("\n-- 拾取 --")
        val onInk = gc.pick((inkGuo.minX + 2).toFloat(), (inkGuo.minY + 2).toFloat())?.id() ?: 0
        report("文本墨迹上可拾取", onInk == INK_ID, "实际=$onInk 期望=$INK_ID")
        val outside = gc.pick((inkGuo.minX - 30).toFloat(), (inkGuo.minY + 2).toFloat())?.id() ?: 0
        report("SDF 外扩矩形之外不可拾取", outside == 0, "实际=$outside 期望=0")
        // ID pass 不看 alpha（对整数附件而言颜色没有意义），而文本的四边形覆盖的是
        // 整个 SDF 位图矩形——含四周各 SPREAD 个 em 像素的外扩。
        // 所以文本的可拾取范围比墨迹大一圈：这是刻意的，与"全透明图元仍可拾取"同类。
        val onPadding = gc.pick((inkGuo.minX - 4).toFloat(), (inkGuo.minY + 2).toFloat())?.id() ?: 0
        report("文本的拾取范围包含 SDF 外扩（比墨迹大一圈）", onPadding == INK_ID,
            "墨迹左缘 ${inkGuo.minX} 左侧 4px 处实际=$onPadding 期望=$INK_ID")

        // —— 7. fontSize 必须进样式栈的浮点部分 ——
        println("\n-- 样式栈：fontSize --")
        gc.fontSize = 20f
        gc.save()
        gc.fontSize = 40f
        gc.save()
        gc.fontSize = 80f
        report("内层 fontSize 生效", gc.fontSize == 80f, "实际=${gc.fontSize}")
        gc.restore()
        report("restore 一层回到中间层 40", gc.fontSize == 40f,
            "实际=${gc.fontSize}——每层宽度错成 2 时这一层可能偶然正确，下一句才是关键")
        gc.restore()
        report("再 restore 回到最外层 20", gc.fontSize == 20f,
            "实际=${gc.fontSize}——每层宽度错成 2 时，第 0 层的 fontSize 槽会被第 1 层盖掉，" +
                "这里会读回 lineWidth 的值")

        // 深层嵌套：同时覆盖 ensureStyleCapacity 的扩容路径（初始 8 层 → 21 层）
        val beforeDeep = gc.fontSize
        gc.save()
        for (i in 1..20) {
            gc.fontSize = 10f + i
            gc.save()
        }
        var deepProblem: String? = null
        for (i in 20 downTo 1) {
            gc.restore()
            if (deepProblem == null && gc.fontSize != 10f + i) {
                deepProblem = "restore 到第 $i 层时 fontSize=${gc.fontSize}，期望 ${10f + i}"
            }
        }
        gc.restore()
        if (deepProblem == null && gc.fontSize != beforeDeep) {
            deepProblem = "全部 restore 之后 fontSize=${gc.fontSize}，期望 $beforeDeep"
        }
        report("21 层嵌套的 save/restore 逐层正确", deepProblem == null,
            deepProblem ?: "21 层逐层 restore 全部正确")

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    override fun stop() {
        transfer?.dispose()
    }
}

/**
 * 一帧的回读像素。
 *
 * <p>只读红通道：场景是纯白文字画在 0x333333 的背景上，红通道的灰度就是覆盖度，
 * 比再算一次亮度更省事也更可预测。
 */
private class Frame(val w: Int, val h: Int) {

    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4)

    init {
        // glReadPixels 的行序自下而上，所以 lum() 里要翻转行号。
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)
    }

    /** 用户坐标 (x, y) 处的红通道值。越界返回背景值。 */
    fun lum(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return BG
        }
        return buffer.get(((h - 1 - y) * w + x) * 4).toInt() and 0xFF
    }
}

/** 一块区域里墨迹的包围盒与像素数。 */
private data class Ink(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val count: Int)

/** 扫描一块区域，返回墨迹（亮度超过噪声阈值的像素）的包围盒与数量。 */
private fun inkIn(frame: Frame, region: IntArray): Ink {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    var count = 0
    for (y in region[1] until minOf(region[3], frame.h)) {
        for (x in region[0] until minOf(region[2], frame.w)) {
            if (frame.lum(x, y) > INK_THRESHOLD) {
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    return Ink(minX, minY, maxX, maxY, count)
}

/**
 * 沿一条水平扫描线量"过渡带"：从区域左边缘往右扫，遇到第一个墨迹像素记为 `enter`，
 * 遇到第一个饱和像素记为 `full`。
 *
 * @return `[过渡带宽度, 饱和连续段长度]`；`enter` 或 `full` 找不到时返回 `[0, 0]`
 */
private fun bandIn(frame: Frame, region: IntArray, y: Int): IntArray {
    val x1 = minOf(region[2], frame.w)
    var enter = -1
    var full = -1
    for (x in region[0] until x1) {
        val value = frame.lum(x, y)
        if (enter < 0 && value > INK_THRESHOLD) {
            enter = x
        }
        if (enter >= 0 && full < 0 && value >= FULL_THRESHOLD) {
            full = x
        }
    }
    if (enter < 0 || full < 0) {
        return intArrayOf(0, 0)
    }
    var run = 0
    var x = full
    while (x < x1 && frame.lum(x, y) >= FULL_THRESHOLD) {
        run++
        x++
    }
    return intArrayOf(full - enter, run)
}
