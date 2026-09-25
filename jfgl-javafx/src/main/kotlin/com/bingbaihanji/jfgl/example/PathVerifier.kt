package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * 路径几何（`beginPath` / `moveTo` / `lineTo` / `close` / `fillPath` / `strokePath`）的
 * **像素校验器**：画几个几何量完全已知的多子路径图形，回读帧缓冲，逐项比对像素，
 * 失败则以非零码退出。
 *
 * <h2>它为什么存在</h2>
 *
 * <p>本仓库的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
 * 而**多子路径**这一类缺陷正好全都落在 `Gc` 里——`geom/` 那一侧各自都对，
 * 错的是调用方**怎么把子路径喂给它们**。单元测试的口径与这个"怎么喂"无关：
 * `StrokeGenerator` 的测试断言"闭合面积大于开放面积"，它自己是对的；
 * 错的是 `Gc.strokePath` 忘了传 `closed = true`。这类缺陷只有把**最终像素**当口径才拦得住。
 *
 * <p>两个场景各对准一个"看起来完全正常"的错法：
 *
 * <ol>
 *   <li><b>把一条路径的所有子路径当成一条折线描边</b>：两条互不相连的横线之间会被连上
 *       一条斜线——那条线**显示了一个不存在的图形**（与本仓库"缺口不能连过去"那条同理），
 *       而画面看上去"就是画了条折线"。判别式是**两条横线之间那个方框里没有像素**，
 *       而不是"两条横线都在"（后者对"多连了一条线"同样成立）。</li>
 *   <li><b>闭合子路径的收尾顶点缺接头</b>：整条闭合边都在，唯独收尾顶点外侧少一个接头，
 *       线上只落平头封口。细线宽下几乎不可见（本目录的 `PipelineVerifier` 用线宽 3~4，
 *       就是这么漏掉的），所以这里用**线宽 20 的直角方框**把它放大成一个看得见的缺口。
 *       判别式是**收尾顶点外侧那一小块 6x6 被不被填满**。</li>
 * </ol>
 *
 * <p>场景 ② 另有一个**同色同几何的对照变体**：同一份点集、只把"闭合"这一位换成
 * `strokePolyline(closed = false)`。于是两个变体之间唯一的差异就是收尾那个接头，
 * 断言可以拿两个变体的**像素总数做差**（应当恰好是接头三角形的面积 ≈50 px²）——
 * 只断言"有像素"会被"整块都画错了"骗过去，而差值把"接头补上了"与"别的地方也变了"分开。
 *
 * <h2>场景为什么会变</h2>
 *
 * <p>[PickVerifier] 的教训：它 24 条断言全绿却漏掉一个真缺陷，因为**它的场景每帧完全相同**
 * ——陈旧数据与新鲜数据恰好一致。多子路径这一条尤其危险：`Gc` 的平坦化结果与子路径切片
 * 都跨帧复用同一块缓冲，"这一帧少画一条子路径"与"上一帧的顶点没被覆盖"在画面上是两回事，
 * 只有**前后两帧画不同的东西**才分得开。所以本校验器有四个变体、逐帧轮转、逐个断言
 * （见 [PathVerifierApp.drawnVariant]）。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PathVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。它会自己关窗退出。
 *
 * <h2>坐标前提</h2>
 *
 * <p>当前管线把用户坐标**按 1:1 映射到设备像素**，因此下面的期望值可以直接用用户坐标书写。
 * 若日后引入真正的 DPI 缩放，本文件的期望值需要乘以缩放系数——那时这些断言会失败，
 * 正是它该提醒的。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 判定用的容差：折线逼近圆弧时像素数必然略少于解析值。 */
private const val TOLERANCE = 0.05

/** 第一次断言的帧号：此前画布尺寸与首帧布局尚未稳定。 */
private const val FIRST_ASSERT_FRAME = 6

/**
 * 断言的帧数：一个变体一帧，见 [PathVerifierApp.drawnVariant]。
 *
 * <p>七个变体依次是：①两条横线 / ②一条横线 / ③闭合的直角方框 / ④同一份几何的**开放对照**
 * / ⑤**开放**的直角折线（走 [Gc.strokePath]）/ ⑥带孔填充（[Gc.fillPath]）
 * / ⑦**半透明**描边的退化接头（见 [PathVerifierApp.drawTranslucentJointCase]）。
 */
private const val ASSERT_FRAME_COUNT = 7

/** 最后一次断言的帧号。 */
private const val LAST_ASSERT_FRAME = FIRST_ASSERT_FRAME + ASSERT_FRAME_COUNT - 1

/** 场景背景色，即 `FXGLTransfer` 的 `glClearColor(0.2, 0.2, 0.2, 1)`。 */
private const val BACKGROUND = 0x333333

// ---------------------------------------------------------------------------
// 多子路径描边：两条互不相连的横线
// ---------------------------------------------------------------------------

/** 两条横线的 x 范围（用户坐标，与设备像素 1:1）。 */
private const val SEG_X0 = 60f
private const val SEG_X1 = 260f

/** 第一条横线的 y。 */
private const val SEG_Y0 = 70f

/** 第二条横线的 y。 */
private const val SEG_Y1 = 170f

/**
 * 两条横线的描边色。
 *
 * <p>全画面只有这一个图形用它，因此"该色像素总数恰好等于横线面积"是一条
 * **覆盖整幅画面**的不变量：多画的（假连线、越界顶点、上一帧的残留）与少画的都拦得住。
 */
private const val SUB_PATH_RGB = 0xFFFF00

/** 断言时全画面应有的颜色种数：背景 + 上面那一个描边色。 */
private const val EXPECTED_COLORS = 2

// ---------------------------------------------------------------------------
// 闭合子路径的收尾接头：一个粗线宽的直角方框
// ---------------------------------------------------------------------------

/** 方框的四角：`[x0,y0, x1,y1, x2,y2, x3,y3]`，闭合顶点是第一个角 `(500,60)`。 */
private const val SQUARE_X0 = 500f
private const val SQUARE_Y0 = 60f
private const val SQUARE_X1 = 700f
private const val SQUARE_Y1 = 260f

/** 直角方框的线宽。取 20 是为了让收尾接头的缺口**肉眼可见**。 */
private const val SQUARE_LINE_WIDTH = 20f

/**
 * 直角方框的描边色。
 *
 * <p>与 [SUB_PATH_RGB] 一样：全画面只有这一组图形用它，"像素总数"因此是一条覆盖整幅画面的
 * 不变量。**两个变体必须用同一个颜色**——它们之间的差异只有收尾那一个接头，
 * 换个颜色就分不清"差异来自接头"还是"差异来自换了颜色"。
 */
private const val CLOSED_JOIN_RGB = 0xFF8000

// ---------------------------------------------------------------------------
// 开放子路径的反面：它不该被当成闭合
// ---------------------------------------------------------------------------

/** 开放直角折线的两个端点与拐点：`(60,400) → (260,400) → (260,500)`。 */
private const val OPEN_X0 = 60f
private const val OPEN_Y0 = 400f
private const val OPEN_X1 = 260f
private const val OPEN_Y1 = 500f

// ---------------------------------------------------------------------------
// 带孔填充：外轮廓 + 内挖空
// ---------------------------------------------------------------------------

/** 圆环（外轮廓 + 内挖空）的中心与内外半径。 */
private const val RING_CX = 200f
private const val RING_CY = 380f
private const val RING_R_OUTER = 70f
private const val RING_R_INNER = 35f

/**
 * 用多边形逼近圆的段数。
 *
 * <p>取 128 是为了让"环带面积"的期望值可以按 `π(R²−r²)` 写：128 边形的面积比圆小
 * 0.04%，远在 5% 容差之内。段数太少（例如 32）会让期望值必须改写成
 * `n/2·R²·sin(2π/n)`，而那条公式与"填充对不对"没关系，只会让断言更难读。
 */
private const val RING_SEGMENTS = 128

/** 两个**互不相交、互不包含**的正方形外轮廓的左上角与边长。 */
private const val SQ1_X = 500f
private const val SQ1_Y = 320f
private const val SQ2_X = 650f
private const val SQ2_Y = 320f
private const val SQ_SIDE = 120f

/**
 * 带孔填充的填充色。
 *
 * <p>它钉住的是 `Gc.fillPath` 的**分类**行为：外轮廓与洞由**包含关系**决定，
 * 不是靠"第一个子路径是外轮廓、后面都是洞"这种位置约定——后者对"两个互不相交的
 * 外轮廓"（下面的两个正方形）会把第二个当成洞，而那种洞落在外轮廓之外，
 * `Tessellator` 的契约里属于病态输入，会静默丢掉一块面积。
 */
private const val HOLE_FILL_RGB = 0x8000FF

// ---------------------------------------------------------------------------
// 半透明描边：退化接头处不能被画两遍
// ---------------------------------------------------------------------------

/** 折线尖转角的顶点。它必须是**尖角**：见 [JOINT_LINE_WIDTH]。 */
private const val JOINT_PX = 200f
private const val JOINT_PY = 500f

/**
 * 尖折线的线宽。
 *
 * <p>取 80（半线宽 40）有两个理由：
 *
 * <ul>
 *   <li>**必须触发退化分支**：`Gc` 里 MITER 的限值写死为 4（相对于半线宽），所以只有
 *       **内角小于约 29°** 才可能退化。这里用 160° 的转向（内角 20°）：
 *       miter 长度 = 半线宽 / sin(10°) ≈ 230 > 4×40 = 160 ⇒ 走退化分支。</li>
 *   <li>**退化接头要够厚**：退化时接头只剩下"顶点与接头底边之间"那个三角形，
 *       它的厚度 = 2×半线宽×sin(内角/2) 的量级 —— 半线宽 40、内角 20° 时约 7 px，
 *       足够放下一个 3x3 的取样框且离三条边都有 2.5 px 以上的余量。</li>
 * </ul>
 */
private const val JOINT_LINE_WIDTH = 80f

/** 单层参考块：同色、同 alpha 的**一次**填充（用来读出"只画一层"是什么颜色）。 */
private const val REF_X = 400f
private const val REF_Y = 460f

/** 半透明描边的颜色与不透明度。 */
private const val TRANSLUCENT_RGB = 0xFFFF00
private const val TRANSLUCENT_ALPHA = 0.5f

/**
 * 退化接头内部的取样框左上角（取 3x3）。
 *
 * <p>这个框整个落在"顶点与接头底边之间"那个三角形内部，离三条边都有 2.5 px 以上余量
 * —— 不是擦边，所以不依赖光栅化的 tie 规则。尖角顶点在 (200,500)，
 * 接头底边从 (200,460) 到 (213.68,537.59)。
 */
private const val JOINT_PROBE_X = 202
private const val JOINT_PROBE_Y = 498

/**
 * 两段描边带**自相交**处的取样点（必然是**两层**）。
 *
 * <p>尖折线的两段带在尖角内侧互相覆盖（半线宽 40，该点到两段的距离分别约 19.5 与 12，
 * 都远小于 40），所以它比单层更深——它在同一帧里充当"两层确实看得见"的对照。
 */
private const val DOUBLE_PROBE_X = 180
private const val DOUBLE_PROBE_Y = 520

/**
 * 校验器的启动入口。
 *
 * <p>函数名**必须**是本文件独有的：同包已有一个顶层 `main()`（[PipelineExample]），
 * 而顶层函数一旦重名，同包内无法用别名区分、`import` 也报"重载歧义"。
 * （踩过：本文件一度叫 `verifyMain`，与 `PipelineVerifier.kt` 的同名顶层函数
 * 直接让整个 `jfgl-javafx` **编译不过**。）
 *
 * <p>用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，上面文档里的
 * `java -cp %classpath com.bingbaihanji.jfgl.example.PathVerifierKt` 因此照常可用。
 */
@JvmName("main")
fun pathVerifyMain() {
    Application.launch(PathVerifierApp::class.java)
}

class PathVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 已到达的渲染帧数（[verifyAll] 里递增）。 */
    private var frame = 0

    /** 绘制次数，用来在变体之间轮换。只在 GL 线程访问。 */
    private var drawCount = 0

    /**
     * **最近一帧实际画出去的**变体下标。
     *
     * <p>断言读的是它而不是自己算一个：绘制在 `onFrame` 里、断言在 `onRender` 里，
     * 两者各数各的帧号极易错开一帧，而错开之后断言会拿"变体 B 的期望值"去比"变体 A 的画面"
     * ——那会是一次**看起来像几何错误的假失败**。这里把"画了什么"直接记下来，断言与画面
     * 就不可能对不上。
     */
    private var drawnVariant = 0

    /** 各次断言累积的失败项。退出码取它，而不是最后一帧的结果。 */
    private val failures = ArrayList<String>()

    /**
     * 变体 2（闭合方框）测到的描边像素总数。
     *
     * <p>变体 3（同一份几何的开放对照）要拿它做差——「两个变体只差收尾那一个接头」
     * 这句话只有**跨变体比较同一个量**才成立，而跨变体比较要求把它记下来。
     * 变体按 0..3 轮转，所以变体 3 那一帧它必定已经被赋值。
     */
    private var closedJoinPixels = 0

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        // ★ 回读拒绝守卫（实现见 MsaaVerifier.kt 的 requirePixelReadback）：本校验器的读数
        //   全部来自 glReadPixels，而多采样画布上那次调用是**非法操作**——它会读回全 0，
        //   然后让下面每一条断言报"画面全黑"式的假失败。
        requirePixelReadback(bridge)
        // 绘制回调也包一层：GL 线程上一个没接住的异常会让 JVM 以 0 正常退出，
        // 校验器就会报一个**静默的绿**（下面 verifyOnce 里那层是同样理由）。
        bridge.onFrame { gc -> drawFrame(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Path Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    private fun drawFrame(gc: Gc) {
        try {
            drawnVariant = drawCount++ % ASSERT_FRAME_COUNT
            when (drawnVariant) {
                0 -> drawSubPathCase(gc, twoSegments = true)
                1 -> drawSubPathCase(gc, twoSegments = false)
                2 -> drawClosedJoinCase(gc, closed = true)
                3 -> drawClosedJoinCase(gc, closed = false)
                4 -> drawOpenPolylineCase(gc)
                5 -> drawHoleFillCase(gc)
                else -> drawTranslucentJointCase(gc)
            }
        } catch (t: Throwable) {
            println("\n=== 绘制过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    /**
     * 多子路径描边的场景：一条路径里的**两条互不相连**的横线。
     *
     * <p>变体 0 画两条、变体 1 只画第一条。两个变体都断言：变体 1 里"第二条横线原来的位置
     * 必须是干净的"——那正是"上一帧的顶点留在缓冲里"这类**跨帧残留**唯一会露出来的地方。
     *
     * @param gc          当前帧的绘制上下文
     * @param twoSegments 是否画第二条横线
     */
    private fun drawSubPathCase(gc: Gc, twoSegments: Boolean) {
        gc.stroke = SUB_PATH_RGB or (0xFF shl 24)
        gc.lineWidth = 4f
        gc.beginPath()
        gc.moveTo(SEG_X0, SEG_Y0)
        gc.lineTo(SEG_X1, SEG_Y0)
        if (twoSegments) {
            gc.moveTo(SEG_X0, SEG_Y1)
            gc.lineTo(SEG_X1, SEG_Y1)
        }
        gc.strokePath()
    }

    /**
     * 闭合子路径的收尾接头：一个 `close()` 结束的直角方框，粗线宽 + MITER 接头。
     *
     * <p>两个变体画的是**逐位相同的点集**，只差一个"闭合"标志：
     *
     * <ul>
     *   <li>[closed] = true：走 [Gc.beginPath] / [Gc.closePath] / [Gc.strokePath]，
     *       平坦化结果是闭合成环的 5 个点（末点与首点重合）；</li>
     *   <li>[closed] = false：走 [Gc.strokePolyline]，喂**同一份** 5 个点、`closed = false`
     *       ——等价于"收尾接头这一处退回改动前的行为"，其余部分逐位相同。</li>
     * </ul>
     *
     * <p>于是两个变体之间**唯一**的差异被压到收尾顶点外侧那一小块：
     * 直角外侧的 MITER 接头三角形（半线宽 10 ⇒ 直角处伸到顶点外 14.14，见
     * `StrokeGenerator` 的 `emitJoin`）。断言因此能把"接头补上了"与"别的地方也变了"
     * 分开——只比较"有没有像素"，任何一处别的改动都会让两条断言同时说话。
     *
     * @param gc     当前帧的绘制上下文
     * @param closed 是否按闭合子路径描边（收尾处补接头）
     */
    private fun drawClosedJoinCase(gc: Gc, closed: Boolean) {
        gc.stroke = CLOSED_JOIN_RGB or (0xFF shl 24)
        gc.lineWidth = SQUARE_LINE_WIDTH
        if (closed) {
            gc.beginPath()
            gc.moveTo(SQUARE_X0, SQUARE_Y0)
            gc.lineTo(SQUARE_X1, SQUARE_Y0)
            gc.lineTo(SQUARE_X1, SQUARE_Y1)
            gc.lineTo(SQUARE_X0, SQUARE_Y1)
            gc.closePath()
            gc.strokePath()
        } else {
            gc.strokePolyline(
                floatArrayOf(
                    SQUARE_X0, SQUARE_Y0,
                    SQUARE_X1, SQUARE_Y0,
                    SQUARE_X1, SQUARE_Y1,
                    SQUARE_X0, SQUARE_Y1,
                    SQUARE_X0, SQUARE_Y0
                ),
                closed = false
            )
        }
    }

    /**
     * **开放**直角折线，且刻意走 [Gc.strokePath] 这条路（末条命令是 `LINE_TO`，不是 `CLOSE`）。
     *
     * <p>它是缺陷③那次修改的**反面**：给闭合子路径补接头的那句
     * `closed = lastCommandIsClose()`（以及多子路径那一路的 `closed = closed[i]`）
     * 一旦写成恒 `true`，开放折线就会被凭空补上一段首尾连线。
     *
     * <p>为什么另起一组而不是复用变体 0/1：那两个子路径都只是**两点**的横线，
     * 按闭合描边时"补"出来的那段与原有线段**完全重合**（零长度段被 `StrokeGenerator` 跳过），
     * 画面逐像素不变——拿它当反面是测不出东西的。这里用一条三段两腿的直角折线，
     * 假收尾线段会横穿画面中间，判别式是那条斜线经过的方框里没有像素。
     *
     * <p>颜色沿用 [SUB_PATH_RGB]：它与变体 0/1 从不出现在同一帧，且两组的区域不相交
     * （y≤174 与 y≥396），因此"全画面只有这一种描边色"这条不变量照旧成立。
     *
     * @param gc 当前帧的绘制上下文
     */
    private fun drawOpenPolylineCase(gc: Gc) {
        gc.stroke = SUB_PATH_RGB or (0xFF shl 24)
        gc.lineWidth = 4f
        gc.beginPath()
        gc.moveTo(OPEN_X0, OPEN_Y0)
        gc.lineTo(OPEN_X1, OPEN_Y0)
        gc.lineTo(OPEN_X1, OPEN_Y1)
        gc.strokePath()
    }

    /**
     * 带孔填充的场景：**一条路径里四条子路径**，交给一次 [Gc.fillPath]。
     *
     * <p>四条子路径分两类，各自对应一种必须成立的行为：
     *
     * <ul>
     *   <li>同心圆环（大圆 + 小圆）：小圆**整个落在大圆内部** → 它必须是洞，
     *       环心那块必须露背景。</li>
     *   <li>两个互不相交、互不包含的正方形：两个都是**外轮廓** → 两块都该被填满。
     *       这一对是"按包含关系分类"与"按位置约定分类"的分水岭：后者会把第二个正方形
     *       当成一个落在外轮廓之外的洞，`Tessellator` 对那种输入只能尽力而为
     *       （少画一块面积、且不会报错）。</li>
     * </ul>
     *
     * <p>`Tessellator` 只接受"一个外轮廓 + 若干洞"，所以多条外轮廓必须**分别**三角化；
     * 而"哪条是外轮廓"只能靠几何（包含关系）判断，不能靠子路径的先后顺序。
     *
     * @param gc 当前帧的绘制上下文
     */
    private fun drawHoleFillCase(gc: Gc) {
        gc.fill = HOLE_FILL_RGB or (0xFF shl 24)
        gc.beginPath()
        appendCircleSubPath(gc, RING_CX, RING_CY, RING_R_OUTER)
        appendCircleSubPath(gc, RING_CX, RING_CY, RING_R_INNER)
        appendSquareSubPath(gc, SQ1_X, SQ1_Y, SQ_SIDE)
        appendSquareSubPath(gc, SQ2_X, SQ2_Y, SQ_SIDE)
        gc.fillPath()
    }

    /**
     * 追加一条正多边形逼近的**圆形子路径**（`MOVE_TO` 起、`CLOSE` 收）。
     *
     * @param gc 当前帧的绘制上下文
     * @param cx 圆心 x
     * @param cy 圆心 y
     * @param r  半径
     */
    private fun appendCircleSubPath(gc: Gc, cx: Float, cy: Float, r: Float) {
        for (i in 0 until RING_SEGMENTS) {
            val a = 2.0 * Math.PI * i / RING_SEGMENTS
            val x = cx + (r * Math.cos(a)).toFloat()
            val y = cy + (r * Math.sin(a)).toFloat()
            if (i == 0) gc.moveTo(x, y) else gc.lineTo(x, y)
        }
        gc.closePath()
    }

    /**
     * 追加一条正方形的子路径（`MOVE_TO` 起、`CLOSE` 收）。
     *
     * @param gc   当前帧的绘制上下文
     * @param x    左上角 x
     * @param y    左上角 y
     * @param side 边长
     */
    private fun appendSquareSubPath(gc: Gc, x: Float, y: Float, side: Float) {
        gc.moveTo(x, y)
        gc.lineTo(x + side, y)
        gc.lineTo(x + side, y + side)
        gc.lineTo(x, y + side)
        gc.closePath()
    }

    /**
     * **半透明**描边的退化接头：把"接头有没有被画两遍"从纯几何的**个数/层数**判据
     * 补成**像素**判据。
     *
     * <p>为什么非要有像素这一条：重复覆盖在**不透明**描边下完全没有症状（同一颜色画两遍
     * 还是那个颜色），唯一的后果是**半透明**时叠加两次更深。所以"个数""层数"都只是代理，
     * 真正要看见的是颜色。
     *
     * <p>场景里同时有**单层**与**两层**两个已知点，于是这条断言不需要靠变异才有说服力：
     *
     * <ul>
     *   <li>单层：[REF_X]/[REF_Y] 处一次同色同 alpha 的填充，读出"只画一层"的颜色；</li>
     *   <li>两层：折线在尖角内侧**两段描边带自相交**（[DOUBLE_PROBE_X] 附近），
     *       那里必然被两层覆盖 —— 它证明"两层 ≠ 一层"在这个场景里真的看得见；</li>
     *   <li>判别式：退化接头**内部**那一小块只可能被接头自己覆盖（两个描边四边形都以
     *       "过顶点的横断面"收边，够不到那里），所以它必须是**单层色**。</li>
     * </ul>
     *
     * <p>⚠️ `Gc` 没有接头开关，走它只能靠"内角够尖"把 MITER 打进退化分支
     * （见 [JOINT_LINE_WIDTH]），所以这里的折线是一个很尖的拐角。
     *
     * @param gc 当前帧的绘制上下文
     */
    private fun drawTranslucentJointCase(gc: Gc) {
        // globalAlpha 属于绘制状态：用 save/restore 保证它不泄漏到下一个变体
        // （泄漏会让后面那些不透明变体的颜色全变，而那是另一条断言在管的事）
        gc.save()
        gc.globalAlpha = TRANSLUCENT_ALPHA
        val argb = TRANSLUCENT_RGB or (0xFF shl 24)
        gc.stroke = argb
        gc.fill = argb
        gc.lineWidth = JOINT_LINE_WIDTH
        // 160° 转向（内角 20°）：miter 长度 ≈ 230 > 4×半线宽 = 160 ⇒ 退化分支
        gc.beginPath()
        gc.moveTo(JOINT_PX - 100f, JOINT_PY)
        gc.lineTo(JOINT_PX, JOINT_PY)
        gc.lineTo(JOINT_PX - 46.98463f, JOINT_PY + 17.10101f)
        gc.strokePath()
        // 单层参考：同一块背景上的一次填充，颜色与描边完全相同
        gc.fillRect(REF_X, REF_Y, 24f, 24f)
        gc.restore()
    }

    /**
     * `onRender` 回调的入口：把校验体包进 try/catch。
     *
     * <p>校验过程本身抛出异常时必须**以非零码退出**：这部分代码在 GL 线程上跑，
     * 一旦抛出去，线程死掉、汇总行与 `exitProcess` 都走不到，JVM 会因为
     * 「最后一个非守护线程结束」而以 0 退出——**一个已经打印了 FAIL 的校验器报出退出码 0**，
     * 正是本仓库最忌讳的那种「静默的绿」。
     */
    private fun verifyOnce() {
        try {
            verifyAll()
        } catch (t: Throwable) {
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun verifyAll() {
        val bridge = transfer ?: return
        frame++
        if (frame < FIRST_ASSERT_FRAME) return
        if (frame > LAST_ASSERT_FRAME) return

        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return

        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        println("\n=== JFGL 路径几何像素校验（第 $frame 帧，变体 $drawnVariant）===")
        println("帧缓冲 ${w}x$h  GL_FRAMEBUFFER_BINDING=${glGetInteger(GL_FRAMEBUFFER_BINDING)}  glGetError=${glGetError()}")

        fun pixelAt(x: Int, y: Int): Int {
            // glReadPixels 行序自下而上；用户坐标 y 向下，故翻转回读行号。
            val i = ((h - 1 - y) * w + x) * 4
            return ((buf.get(i).toInt() and 0xFF) shl 16) or
                    ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                    (buf.get(i + 2).toInt() and 0xFF)
        }

        // 一次全图扫描，同时得到每色像素数与包围盒。
        val counts = HashMap<Int, Int>()
        val minX = HashMap<Int, Int>(); val minY = HashMap<Int, Int>()
        val maxX = HashMap<Int, Int>(); val maxY = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixelAt(x, y)
                counts[c] = (counts[c] ?: 0) + 1
                if ((minX[c] ?: Int.MAX_VALUE) > x) minX[c] = x
                if ((maxX[c] ?: Int.MIN_VALUE) < x) maxX[c] = x
                if ((minY[c] ?: Int.MAX_VALUE) > y) minY[c] = y
                if ((maxY[c] ?: Int.MIN_VALUE) < y) maxY[c] = y
            }
        }

        fun countIn(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                    if (pixelAt(x, y) == argb) n++
                }
            }
            return n
        }

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        fun approx(label: String, actual: Int, expected: Double, tol: Double = TOLERANCE) {
            val lo = expected * (1 - tol); val hi = expected * (1 + tol)
            report(label, actual.toDouble() in lo..hi,
                "实际 $actual，期望 ${expected.toInt()} ±${(tol * 100).toInt()}%")
        }

        fun bbox(label: String, argb: Int, ex: Int, ey: Int, ew: Int, eh: Int) {
            if ((counts[argb] ?: 0) == 0) {
                report(label, false, "无像素")
                return
            }
            val ax = minX[argb]!!; val ay = minY[argb]!!
            val aw = maxX[argb]!! - ax + 1; val ah = maxY[argb]!! - ay + 1
            report(label, ax == ex && ay == ey && aw == ew && ah == eh,
                "实际 ($ax,$ay) ${aw}x$ah，期望 ($ex,$ey) ${ew}x$eh")
        }

        println("\n-- 绘制区尺寸 --")
        val gc = bridge.gc()
        report("Gc.width 等于帧缓冲宽度", gc?.width == w, "Gc.width=${gc?.width}，帧缓冲宽=$w")
        report("Gc.height 等于帧缓冲高度", gc?.height == h, "Gc.height=${gc?.height}，帧缓冲高=$h")

        if (drawnVariant < 2) {
            println("\n-- 多子路径描边：MOVE_TO 处必须断开 --")
            val twoSegments = drawnVariant == 0
            val sx0 = SEG_X0.toInt(); val sx1 = SEG_X1.toInt()
            val sy0 = SEG_Y0.toInt(); val sy1 = SEG_Y1.toInt()

            // 一条 200 长的横线、线宽 4：四条像素行、每行 200 个像素，应当**精确**是 800。
            approx("第一条横线（y=$sy0）铺满 200x4",
                countIn(sx0 - 5, sy0 - 4, sx1 + 5, sy0 + 4, SUB_PATH_RGB), 200.0 * 4)
            if (twoSegments) {
                approx("第二条横线（y=$sy1）铺满 200x4",
                    countIn(sx0 - 5, sy1 - 4, sx1 + 5, sy1 + 4, SUB_PATH_RGB), 200.0 * 4)
                bbox("两条横线的包围盒（在一起）", SUB_PATH_RGB, sx0, sy0 - 2, sx1 - sx0, sy1 - sy0 + 4)
            } else {
                // 变体 1 不画第二条：那个位置必须**一个像素都没有**。这一句与变体 0 里那句
                // "第二条横线铺满"合起来才有意义——只断言"这里是干净的"，对"两条都没画"同样成立。
                report("变体 1：未绘制的第二条横线位置必须是干净的",
                    countIn(sx0 - 5, sy1 - 4, sx1 + 5, sy1 + 4, SUB_PATH_RGB) == 0,
                    "实际 ${countIn(sx0 - 5, sy1 - 4, sx1 + 5, sy1 + 4, SUB_PATH_RGB)} px")
                bbox("变体 1：只剩第一条横线", SUB_PATH_RGB, sx0, sy0 - 2, sx1 - sx0, 4)
            }

            // 这是本节的**判别式**。把两个子路径当成一条折线描边时，相邻两点会被连起来：
            // 从第一条的末点 (260,70) 到第二条的起点 (60,170)，那条假斜线正好穿过 (160,120)。
            // 断言"这个方框里没有像素"，而不是断言"两条横线都在"——后者对"多连了一条线"
            // 同样成立（那条线是**多出来的**，不是替掉了什么）。
            //
            // 变体 1 里第二条横线不存在，也就无从连起；那一帧这条断言退化为恒真，
            // 由变体 0 那一帧负责。
            val corridor = countIn(150, 110, 170, 130, SUB_PATH_RGB)
            report("两条子路径之间没有连线（假斜线会穿过 (160,120)）", corridor == 0,
                "实际 $corridor px")

            println("\n-- 无杂散像素（覆盖整幅画面）--")
            val expectedStrokes = if (twoSegments) 2 else 1
            // 该颜色全画面只出现在这些横线上：多画一块、少画一块、上一帧的顶点留在缓冲里，
            // 都会让这个总数变化。颜色种数同时钉住"没有出现别的颜色"。
            report("描边色像素总数恰好等于 ${expectedStrokes} 条横线的面积",
                counts[SUB_PATH_RGB] == expectedStrokes * 200 * 4,
                "实际 ${counts[SUB_PATH_RGB] ?: 0}，期望 ${expectedStrokes * 200 * 4}")
        } else if (drawnVariant < 4) {
            println("\n-- 闭合子路径的收尾接头 --")
            val half = (SQUARE_LINE_WIDTH / 2).toInt()
            val cx = SQUARE_X0.toInt() - half   // 收尾顶点（方框左上角）外侧那一块
            val cy = SQUARE_Y0.toInt() - half
            val side = (SQUARE_X1 - SQUARE_X0).toInt() + half * 2

            // 四个直角各伸出一个 MITER 尖角，顶点外侧各半个线宽，故外沿恰好是 220x220。
            bbox("直角方框描边的包围盒（含四角尖角）", CLOSED_JOIN_RGB, cx, cy, side, side)

            // 判别式：收尾顶点 (500,60) 外侧那一块。半线宽 10、直角 ⇒ MITER 尖角伸到顶点外
            // (10,10)，接头三角形是 (490,60)-(490,50)-(500,50)（斜边是直线 x+y=550）。
            //
            // 取的方框是**严格落在三角形内部**的 4x4（`(490,50)` 起、不含斜边）：
            // 取整个 10x10 的话，其中 10 个像素的中心**正好落在斜边 x+y=550 上**，
            // 它们算不算被覆盖取决于光栅化的 tie 规则（实测 36 格的 6x6 里只数到 33，
            // 差的正是这些擦边像素）。避开它们，这条断言才是确定的、可解释的。
            //
            // 有接头 ⇒ 16 格全被填满；按开放折线收尾 ⇒ 一格都没有。两条横向的描边带都够不到
            // 这里：左带是 x∈[490,510] 但 y≥60，上带是 y∈[50,70] 但 x≥500，
            // 与这个方框只在边界上相接。
            val corner = countIn(cx, cy, cx + 3, cy + 3, CLOSED_JOIN_RGB)

            if (drawnVariant == 2) {
                report("闭合子路径在收尾顶点外侧补上了接头（外角 4x4 应被填满）",
                    corner == 16, "实际 $corner / 16 px，无接头时应为 0")
                closedJoinPixels = counts[CLOSED_JOIN_RGB] ?: 0
            } else {
                // 反证：同一份几何、同一个颜色，只把"闭合"这一位去掉，收尾处就只剩平头封口。
                // 没有这一条，"有接头"那一条对"整块都画错了"同样会通过。
                report("反证：同一份几何按开放折线收尾时外角没有接头", corner == 0,
                    "实际 $corner px")
                // 两个变体之间**只该差收尾那一个接头**：直角处一个**完整**的 miter 接头
                // 恰好是 10x10 的整块（半线宽 10 的两条偏移线交于顶点外 (10,10)，
                // 与顶点围成那个正方形），所以差的正好是 **100** 个像素，
                // 而且这 100 个像素的中心都**严格**落在内部（离每条边 ≥0.5 px）——
                // 这条断言因此是确定的，与光栅化 tie 规则无关。
                //
                // ⚠️ 它曾经是 45（区间 40..50）：那时接头只发了"底边之外"那一半，
                // 另一半（底线恰好穿过 10 个像素的中心）由 tie 规则决定算不算。
                // 补全接头之后，两个半块合起来是一整块，那点不确定性随之消失。
                // 数字从 45 变成 100 是**因为缺陷修好了**。
                val diff = closedJoinPixels - (counts[CLOSED_JOIN_RGB] ?: 0)
                report("两个变体只差收尾那一个完整接头（直角处恰为 10x10 = 100 px）", diff == 100,
                    "闭合比开放多 $diff px（闭合总 $closedJoinPixels，开放总 ${counts[CLOSED_JOIN_RGB] ?: 0}）")
            }

            println("\n-- 无杂散像素（覆盖整幅画面）--")
            // 理想描边 = 外沿 220² − 内沿 180² = 16000：四个直角的接头必须是**完整**的
            // 风筝形（顶点到底边之间那一半 + 底边之外尖角那一半），四角各 10x10 = 100。
            // 开放对照缺的正是收尾那一个接头，于是 16000 − 100 = 15900。
            //
            // ⚠️ 这两条**曾经是 15800 / 15755**，差的正是"顶点到底边"那半个三角形
            // （每个直角 50 px²）：`StrokeGenerator` 的 MITER 分支曾只发底边之外那个。
            // 现在改成理想值是因为**缺陷修好了**，不是"把断言改松"——反向证据是修之前
            // 它在同一几何上稳定测到 15800，且 `StrokeGeneratorTest` 里那两条纯几何断言
            // （不依赖 GL 光栅化）同时由红转绿。
            val expectedTotal = if (drawnVariant == 2) 16000 else 15900
            val total = counts[CLOSED_JOIN_RGB] ?: 0
            report("方框描边像素总数 = 4 条边 - 4 处重叠 + 4 个完整接头（${if (drawnVariant == 2) "闭合" else "开放对照"}）",
                total == expectedTotal, "实际 $total，期望 $expectedTotal")
        } else if (drawnVariant < 5) {
            println("\n-- 开放子路径：不该被当成闭合 --")
            val ox0 = OPEN_X0.toInt(); val ox1 = OPEN_X1.toInt()
            val oy0 = OPEN_Y0.toInt(); val oy1 = OPEN_Y1.toInt()

            // 水平腿 200 长、竖直腿 100 长（`OPEN_Y1 - OPEN_Y0`），线宽都是 4。
            approx("开放折线的水平腿铺满 200x4",
                countIn(ox0 - 5, oy0 - 4, ox1 + 5, oy0 + 4, SUB_PATH_RGB), 200.0 * 4)
            approx("开放折线的竖直腿铺满 100x4",
                countIn(ox1 - 4, oy0 - 5, ox1 + 4, oy1 + 5, SUB_PATH_RGB), 100.0 * 4)
            // 拐角 (260,400) 是一个内部顶点，照常补 MITER 尖角：外沿伸到 (262,398)，
            // 于是包围盒比两条腿本身的 [60,260)x[398,500) 各多出一个像素。
            bbox("开放折线的包围盒（含拐点尖角，不含任何收尾连线）", SUB_PATH_RGB,
                ox0, oy0 - 2, ox1 - ox0 + 2, oy1 - oy0 + 2)

            // 判别式：开放子路径如果被当成闭合描边，末点 (260,500) 会与首点 (60,400)
            // 连上一条**并不存在**的斜线（斜率 -1/2，穿过 (160,450)）。
            // 两条腿都离这个方框很远（y=400 / x=260），方框里出现任何像素都只可能是它。
            val falseClose = countIn(150, 440, 170, 460, SUB_PATH_RGB)
            report("开放子路径的首尾之间没有连线（假收尾线会穿过 (160,450)）", falseClose == 0,
                "实际 $falseClose px")

            println("\n-- 无杂散像素（覆盖整幅画面）--")
            // 水平腿 800 + 竖直腿 400 - 拐角重叠 4 + 接头三角形（~1..7，擦边像素的多少
            // 取决于光栅化 tie 规则）≈ 1200。容差取 2% 而不是 5%：
            // 一段**假收尾斜线**（长 √(200²+100²)≈224、宽 4）会给总数加上近 900 px，
            // 那是数量级的差异，不需要靠容差去分辨。
            approx("开放折线像素总数 = 两条腿 - 拐角重叠 + 接头", counts[SUB_PATH_RGB] ?: 0,
                1200.0, 0.02)
        } else if (drawnVariant < 6) {
            println("\n-- 带孔填充：外轮廓按包含关系分类 --")
            val rcx = RING_CX.toInt(); val rcy = RING_CY.toInt()
            val rOut = RING_R_OUTER.toInt(); val rIn = RING_R_INNER.toInt()
            val side = SQ_SIDE.toInt()
            val sq1x = SQ1_X.toInt(); val sq1y = SQ1_Y.toInt()
            val sq2x = SQ2_X.toInt(); val sq2y = SQ2_Y.toInt()

            // 判别式之一：环心那块必须是背景。小圆整个落在大圆内部，按包含关系它是**洞**；
            // 一旦退化成"各填各的"或普通子路径，环心会被填成实心——而那个画面看上去
            // "就是个实心圆"，没有任何别的地方不对劲。方框取得比内圆小一圈（±8 < r=35），
            // 保证它完全落在洞里，不会碰到环带。
            val center = countIn(rcx - 8, rcy - 8, rcx + 8, rcy + 8, HOLE_FILL_RGB)
            report("环心被挖空（小圆是洞，不是又一块填充）", center == 0, "实际 $center px")

            // 判别式之二：两个互不相交的正方形**都**得被填满。按位置约定分类的实现
            // （"第一个子路径作外轮廓、其余作洞"）会把第二个正方形当成洞，而它落在外轮廓
            // 之外——`Tessellator` 对那种输入只能尽力而为，结果是一块面积被静默丢掉。
            for (i in 0..1) {
                val x = if (i == 0) sq1x else sq2x
                val y = if (i == 0) sq1y else sq2y
                val n = countIn(x, y, x + side - 1, y + side - 1, HOLE_FILL_RGB)
                report("第 ${i + 1} 个正方形铺满 ${side}x$side", n == side * side,
                    "实际 $n，期望 ${side * side}")
            }

            // 环带面积按 π(R²−r²) 写：128 边形的面积只比圆小 0.04%。
            val band = countIn(rcx - rOut, rcy - rOut, rcx + rOut, rcy + rOut, HOLE_FILL_RGB)
            approx("环带像素数 = π(R²−r²)", band,
                Math.PI * (RING_R_OUTER.toDouble() * RING_R_OUTER - RING_R_INNER.toDouble() * RING_R_INNER))

            // 包围盒把四块都框住：环在最左、也在最上最下（它的极值点正好落在 0°/90°/180°/270°，
            // 128 边形的顶点里有这四个方向），第二个正方形在最右。
            val bx = rcx - rOut
            val by = rcy - rOut
            bbox("带孔填充的包围盒（环 + 两个正方形）", HOLE_FILL_RGB,
                bx, by, sq2x + side - 1 - bx + 1, rcy + rOut - 1 - by + 1)

            println("\n-- 无杂散像素（覆盖整幅画面）--")
            approx("填充色像素总数 = 两个正方形 + 环带", counts[HOLE_FILL_RGB] ?: 0,
                2.0 * side * side + Math.PI * (RING_R_OUTER.toDouble() * RING_R_OUTER
                        - RING_R_INNER.toDouble() * RING_R_INNER), 0.02)
        } else {
            println("\n-- 半透明描边：退化接头处不该被画两遍 --")
            val opaque = TRANSLUCENT_RGB or (0xFF shl 24)
            val refColor = pixelAt(REF_X.toInt() + 5, REF_Y.toInt() + 5)

            // 前提 1：这个场景必须真的"混"过。不透明时两层与一层颜色完全相同，
            // 这条断言就会退化成恒真——所以先钉住"参考色既不是不透明色也不是背景"。
            report("参考色确实被 alpha 混合过（既不是不透明色、也不是背景）",
                refColor != opaque && refColor != BACKGROUND,
                "#%06X（不透明色 #%06X、背景 #%06X）".format(refColor, opaque, BACKGROUND))

            // 前提 2（同帧内的灵敏度对照）：两段带自相交处是**两层**，必须比单层更深。
            // 它在同一帧里证明了"两层 ≠ 一层"看得见，于是下面那条不是恒真。
            val doubleColor = pixelAt(DOUBLE_PROBE_X, DOUBLE_PROBE_Y)
            report("对照：两段带自相交处是两层，颜色比单层更深",
                doubleColor != refColor && doubleColor != BACKGROUND,
                "($DOUBLE_PROBE_X,$DOUBLE_PROBE_Y) = #%06X，单层 #%06X".format(doubleColor, refColor))

            // 判别式：退化接头内部只可能被接头自己覆盖（两个描边四边形都以"过顶点的
            // 横断面"收边），所以必须是**单层色**。接头被发两遍时它会变成双层色。
            val probe = countIn(JOINT_PROBE_X, JOINT_PROBE_Y,
                JOINT_PROBE_X + 2, JOINT_PROBE_Y + 2, refColor)
            report("退化接头内部 9 px 全是**单层**色（画两遍会更深）", probe == 9,
                "实际 $probe / 9 px 是单层色，接头框内像素："
                        + (0..2).joinToString(" ") { dy ->
                    (0..2).joinToString(",") { dx ->
                        "#%06X".format(pixelAt(JOINT_PROBE_X + dx, JOINT_PROBE_Y + dy))
                    }
                })

            println("\n-- 无杂散像素（覆盖整幅画面）--")
            // 本变体应当恰好 3 种颜色：背景、单层、以及两段带自相交处的双层 —— 由文件
            // 末尾那条统一的"画面只有 N 种颜色"断言按变体取 N 来钉（这里不重复断言）。
        }

        // 半透明变体（最后一个）是唯一的例外：它必然多出一种"两层"颜色
        // （两段描边带在尖角内侧自相交，那是本类已声明的限制，不是缺陷）。
        val expectedColors = if (drawnVariant == 6) 3 else EXPECTED_COLORS
        report("画面只有 $expectedColors 种颜色（无杂散像素）", counts.size == expectedColors,
            "实际 ${counts.size} 种：${counts.keys.sorted().joinToString { "#%06X".format(it) }}")
        report("背景色为 clear 色", (counts[BACKGROUND] ?: 0) > 0, "背景像素 ${counts[BACKGROUND] ?: 0}")

        println()
        if (failures.isEmpty()) {
            println("=== 第 $frame 帧全部通过 ===")
        } else {
            println("=== 第 $frame 帧为止失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        if (frame == LAST_ASSERT_FRAME) {
            Platform.exit()
            exitProcess(if (failures.isEmpty()) 0 else 1)
        }
    }

    override fun stop() {
        transfer?.dispose()
    }
}
