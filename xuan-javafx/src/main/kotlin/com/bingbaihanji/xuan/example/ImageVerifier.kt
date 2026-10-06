package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/** 窗口的**逻辑**尺寸。绘制区的设备像素尺寸由系统缩放决定。 */
private const val SCENE_W = 820.0
private const val SCENE_H = 620.0

/** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是 (0.2,0.2,0.2)。 */
private const val BG = 0xFF18212B.toInt()

/** 探针图被铺到屏幕上的**放大倍数**。 */
private const val SCALE = 21

/**
 * ★ 这个数必须是**奇数**，否则每条判据都会读到一个混合色，而"看起来只是颜色不太对"。
 *
 * <p>原因是一次纹理坐标与像素中心的相位问题：纹素中心落在 `dx + SCALE*(k+0.5)`
 * 处，而设备像素中心落在 `整数 + 0.5` 处。只有 `SCALE` 为奇数时，`SCALE/2` 才是
 * 半整数、纹素中心才**恰好**落在某个像素的中心上——那一点上线性过滤的权重是 1.0/0.0，
 * 读到的是纯纹素。`SCALE` 取偶数时每个采样点都落在两个纹素交界附近（97.5%/2.5%），
 * 于是"颜色对不对"这条判据会退化成"两个颜色混得像不像"。
 */
private val CELL = SCALE

/**
 * 探针图：3x2，**第 0 行是图像的顶行**，最后一格是"全透明但 RGB 非零"的一格。
 *
 * <p>那一格的 RGB **刻意不是 0**：写 `0x00000000` 的话，"alpha 没生效"与
 * "alpha 生效了、露出的正好是黑底"两种情况读出来**逐位相同**，那条断言就成了橡皮图章。
 * 取 `0x00FF00FF`（品红、全透明）之后，alpha 一旦被忽略，那一格会画出品红。
 */
private val GRID_COLORS = intArrayOf(
    0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),   // 顶行：红 绿 蓝
    0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0x00FF00FF.toInt())  // 底行：黄 青 **全透明(品红)**

/** 对照块：一个纯色 `fillRect`。用来把"回读坏了"与"图片没画出来"分开。 */
private const val CONTROL = 0xFFFF00FF.toInt()

/** 50% 透明的纯红，用来钉预乘。 */
private const val HALF_RED = 0x80FF0000.toInt()

/** 不透明的纯绿，用来钉 globalAlpha 与裁剪。 */
private const val GREEN = 0xFF00FF00.toInt()

/** 拾取探针的 ID。 */
private const val PICK_ID = 77

/** 回收探针画在视口之外：画面不受影响，但"被画到了"这件事照常发生。 */
private const val OFF_X = 2000f
private const val OFF_Y = 2000f

/**
 * 图片绘制（[Gc.drawImage] / [Gc.createImage]）的像素校验。
 *
 * <h2>它证的三类事</h2>
 *
 * 1. **画面**：纹素逐点画对了、方向没翻、alpha 生效、裁剪生效、拾取生效。
 * 2. **预乘**：50% 透明纯红画在黑底上是 `(128,0,0)` 而不是 `(255,0,0)`。
 *    漏掉预乘的图在画面上只是"颜色艳了点"，**没有任何一处会报错**。
 * 3. **只传一次 / 不再画就释放**：这两件事在画面上**没有痕迹**，所以判据只能是
 *    `ImageStore` 的两个计数（见 {@link #verify}）。第三帧那一条是刻意的：
 *    它同时钉住"宽限不止一代"（第二帧不重传）与"宽限只有两代"（第三帧重传）——
 *    两个方向合起来才把 `GRACE_GENERATIONS = 2` 钉死。
 *
 * <h2>为什么探针图只建一次</h2>
 * <p>那五个 `IntArray` 是**字段**而不是每次绘制现 new 的：缓存按数组的
 * <b>对象身份</b>索引，每帧 new 一个新数组会让每帧都重传一次
 * ——而那正是"只传一次"这条断言要抓的东西，探针自己先把水搅浑就没法判了。
 *
 * <h2>本校验器不需要字体</h2>
 * <p>它一个字符都不画，所以 `-Dxuan.text.font` 对它是可选的。
 */
@JvmName("main")
@Suppress("unused")
fun imageVerifierMain() {
    Application.launch(ImageVerifierApp::class.java)
}

class ImageVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 当前帧号。在绘制那一侧自增，读数那一侧用它取出本帧的期望值。 */
    private var frame = 0

    private val failures = ArrayList<String>()

    // ---- 探针图（**字段**，不是每帧新建；理由见类文档）----

    /** 六格探针图（最后一行最后一格全透明）。 */
    private val grid = IntArray(6) { GRID_COLORS[it] }

    /** 1x1：50% 透明纯红。 */
    private val halfRed = intArrayOf(HALF_RED)

    /** 1x1：不透明纯绿。 */
    private val green = intArrayOf(GREEN)

    /** 只在第 0、3 帧画的回收探针（隔两帧 ⇒ 应当被释放并重传）。 */
    private val recycleProbe = intArrayOf(0xFF445566.toInt())

    /** 只在第 0、2 帧画的宽限探针（只隔一帧 ⇒ **不**该被释放）。 */
    private val graceProbe = intArrayOf(0xFF667788.toInt())

    /** 句柄探针用的数组；从不绘制，只用来验证"已释放的句柄不能再画"。 */
    private val handleProbe = intArrayOf(0xFFAABBCC.toInt())

    // ---- 第 1 帧那几个错误探针的结果（在 GL 线程写入、在同一帧的读数里断言）----

    private var degenerateNoThrow = false
    private var nanThrew = false
    private var badSizeThrew = false
    private var disposedThrew = false

    override fun start(stage: Stage) {
        // 采样数走同一个系统属性（解析与 MsaaVerifier 共用），否则下面那道守卫是死代码。
        // 不需要字体：本校验器一个字符都不画。
        val bridge = FXGLTransfer(msaa = readRequestedMsaa())
        // 本校验器的读数全部来自 glReadPixels，多采样画布上那次调用是非法操作。
        requirePixelReadback(bridge)
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyFrame() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "Xuan Image Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.show()
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /** 画一帧。**在 GL 线程上执行**。帧号在这里自增，绘制与读数因此严格配对。 */
    private fun drawScene(gc: Gc) {
        val current = frame

        gc.pickId = 0
        gc.fill = BG
        gc.fillRect(0f, 0f, gc.width.toFloat(), gc.height.toFloat())

        // ---- 1. 六格探针：像素逐点 + 方向 + 透明格 ----
        // 铺满 (IMG_W × CELL) × (IMG_H × CELL)：整数倍放大，纹素中心才落在像素中心上
        // **直接画在背景上、不垫黑底**：全透明那一格因此应当露出背景色（判据见下）
        gc.drawImage(grid, IMG_W, IMG_H, 40f, 40f, GRID_W, GRID_H)

        // ---- 0. 对照块：证明"回读"本身是好的 ----
        val savedFill = gc.fill
        gc.fill = CONTROL
        gc.fillRect(600f, 100f, 20f, 20f)
        gc.fill = savedFill

        // ---- 2. 预乘探针：50% 透明纯红铺满 40x40 ----
        backdrop(gc, 140f, 40f, 40f, 40f)
        gc.drawImage(halfRed, 1, 1, 140f, 40f, 40f, 40f)

        // ---- 3. globalAlpha 探针：不透明绿 × 0.25 ----
        backdrop(gc, 220f, 40f, 40f, 40f)
        gc.save()
        gc.globalAlpha = 0.25f
        gc.drawImage(green, 1, 1, 220f, 40f, 40f, 40f)
        gc.restore()

        // ---- 4. 裁剪探针：只画一半，另一半留黑底 ----
        backdrop(gc, 300f, 40f, 80f, 40f)
        gc.save()
        gc.clipRect(300f, 40f, 40f, 40f)
        gc.drawImage(green, 1, 1, 300f, 40f, 80f, 40f)
        gc.restore()

        // ---- 5. 拾取探针：同一张图再画一份，带拾取号 ----
        // 用**同一个数组**：同一张纹理画两次，总共只上传一次
        gc.pickable(PICK_ID) {
            gc.drawImage(grid, IMG_W, IMG_H, 400f, 40f, GRID_W, GRID_H)
        }

        // ---- 6. 回收 / 宽限探针：画在视口之外，画面不受影响，但上传照常发生 ----
        when (current) {
            0 -> {
                gc.drawImage(recycleProbe, 1, 1, OFF_X, OFF_Y, 8f, 8f)
                gc.drawImage(graceProbe, 1, 1, OFF_X, OFF_Y + 20f, 8f, 8f)
            }
            // 只隔一帧：仍在宽限之内，必须命中的是缓存
            2 -> gc.drawImage(graceProbe, 1, 1, OFF_X, OFF_Y, 8f, 8f)
            // 隔了两帧：第 3 帧的帧首应当已经把它释放了，这一次必须重传
            3 -> gc.drawImage(recycleProbe, 1, 1, OFF_X, OFF_Y, 8f, 8f)
        }

        // ---- 7. 错误路径（第 1 帧做，那一帧的期望上传数是 0，于是它顺带被这条盖住）----
        if (current == 1) {
            errorProbes(gc)
        }

        frame = current + 1
    }

    /** 一块不透明黑底：让上面那几个探针的混合结果可以手算。 */
    private fun backdrop(gc: Gc, x: Float, y: Float, w: Float, h: Float) {
        val saved = gc.fill
        gc.fill = 0xFF000000.toInt()
        gc.fillRect(x, y, w, h)
        gc.fill = saved
    }

    /**
     * 四条"不该画 / 该抛异常"的路径。
     *
     * <p>它们全部在第 1 帧执行，而那一帧断言的上传数是 0——于是
     * "退化矩形不触发上传"这条不需要单独的计数器，它已经在那条断言里了。
     */
    private fun errorProbes(gc: Gc) {
        // 零宽：几何上是零面积，什么都不画。**而且必须不触发上传**
        val fresh = IntArray(4)
        gc.drawImage(fresh, 2, 2, 600f, 40f, 0f, 40f)
        degenerateNoThrow = true
        // ⚠️ 这里**不能**去取上传计数：取走会把本帧的计数清零，
        // 而下面那条"本帧上传 0 张"正好要靠它。判据交给那一帧的总数即可
        // ——它同时也是"退化矩形不触发上传"的判据（那张 fresh 数组从没被画过）。

        nanThrew = threw<IllegalArgumentException> {
            gc.drawImage(grid, IMG_W, IMG_H, Float.NaN, 0f, 10f, 10f)
        }
        badSizeThrew = threw<IllegalArgumentException> { gc.createImage(IntArray(5), IMG_W, IMG_H) }

        val handle = gc.createImage(handleProbe, 1, 1)
        handle.dispose()
        disposedThrew = threw<IllegalStateException> { gc.drawImage(handle, 0f, 0f) }
    }

    private inline fun <reified T : Throwable> threw(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (t: Throwable) {
        t is T
    }

    // ------------------------------------------------------------------
    // 读数与断言
    // ------------------------------------------------------------------

    private fun verifyFrame() {
        val bridge = transfer ?: return
        val just = frame - 1
        try {
            when (just) {
                0 -> verifyScene(bridge)
                1 -> verifyNoReupload(bridge)
                2 -> verifyGraceWindow(bridge)
                3 -> verifyRecycle(bridge)
            }
        } catch (t: Throwable) {
            // 校验代码本身抛异常时必须以非零码退出：它在 GL 线程上跑，抛出去会让
            // 汇总行与 exitProcess 都走不到，JVM 以 0 退出。
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
        if (just >= 3) {
            finish()
        }
    }

    private fun verifyScene(bridge: FXGLTransfer) {
        println("=== Xuan 图片绘制像素校验（帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}）===")
        val shot = ImageShot(ImageShot.Size(bridge.scaledWidth, bridge.scaledHeight))

        // ★ 诊断：把"画面里到底有什么颜色"直接印出来。
        //
        // 这两行**刻意留着**（与 AxisVerifier 同一条理由）：本校验器初版所有像素断言
        // 都读到 000000，而"图片压根没画"与"回读坏了/读错了区域"在断言层面
        // **看起来一模一样**。能把这几种情况分开的，只有"直接看看画面里有什么"。
        println(
            "  [诊断] BG=${shot.count(BG)} CONTROL=${shot.count(CONTROL)} " +
                    "缓存纹理=${bridge.gc()?.imageStore?.imageTextureCount()}"
        )
        println("  [诊断] 最多的 6 种颜色：" + shot.topColors(6).joinToString(" ") { "%06X:%d".format(it.first, it.second) })
        println("\n-- 0. 对照：纯色 fillRect --")
        report(
            "前提：回读可用（纯色 fillRect 读得到）", shot.at(610, 110) == CONTROL and 0xFFFFFF,
            "(610,110) 实测 %06X 期望 %06X —— 这一条不过的话，下面所有失败都不必当真"
                .format(shot.at(610, 110), CONTROL and 0xFFFFFF)
        )

        // ★ 首帧的计数必须在这里取走。
        //
        // 这个计数器是"自上次取走以来"的累计量，**不取走就会跨帧攒着**——
        // 初版没在这里取，于是第 1 帧那条"上传 0 张"读到的是**第 0 帧攒下的 5**，
        // 一条本该校验"不重传"的断言变成了"首帧传了 5 张"，两个方向都错。
        val firstFrameUploads = bridge.gc()?.imageStore?.takeUploadedImageCount() ?: -1L
        report(
            "★ 首帧把 5 张图各上传一次", firstFrameUploads == 5L,
            "实测 $firstFrameUploads 张（grid / halfRed / green / recycleProbe / graceProbe；" +
                    "拾取探针复用 grid，不另算一张）"
        )

        // ---- 1. 六格：逐点等于源纹素 ----
        println("\n-- 1. 纹素逐点对照（目标矩形是源图的 ${CELL} 倍整数放大）--")
        for (row in 0 until IMG_H) {
            for (col in 0 until IMG_W) {
                // 纹素中心恰好落在设备像素中心上（SCALE 为奇数的理由，见它的说明）
                val px = 40 + CELL * col + CELL / 2
                val py = 40 + CELL * row + CELL / 2
                val expected = GRID_COLORS[row * IMG_W + col] and 0xFFFFFF
                val actual = shot.at(px, py)
                val isLast = row == IMG_H - 1 && col == IMG_W - 1
                report(
                    if (isLast) "★ 全透明格露出的是**背景色**(alpha 真的生效)"
                    else "★ 第 ${row} 行第 ${col} 格的像素 = 源纹素",
                    actual == (if (isLast) BG and 0xFFFFFF else expected),
                    "($px,$py) 实测 %06X 期望 %06X".format(
                        actual, if (isLast) BG and 0xFFFFFF else expected
                    )
                )
            }
        }

        // ---- 2. 方向 ----
        println("\n-- 2. 方向（数组第 0 行 = 图像顶行）--")
        report(
            "★ 上半是**红绿蓝**（数组第 0 行）", shot.at(50, 50) == 0xFF0000 && shot.at(71, 50) == 0x00FF00,
            "上半两格实测 %06X、%06X；翻错方向这里会是黄/青".format(shot.at(50, 50), shot.at(71, 50))
        )
        report(
            "★ 下半是**黄青**（数组第 1 行）", shot.at(50, 71) == 0xFFFF00 && shot.at(71, 71) == 0x00FFFF,
            "下半两格实测 %06X、%06X".format(shot.at(50, 71), shot.at(71, 71))
        )

        // ---- 3. 预乘 ----
        println("\n-- 3. 预乘（画在不透明黑底上）--")
        // 50% 透明纯红：预乘 ⇒ (128,0,0)；不预乘 ⇒ (255,0,0)，一倍亮而画面"只是艳了点"
        report(
            "★ 50% 透明纯红 × 黑底 = (128,0,0)", shot.at(160, 60) == 0x800000,
            "实测 %06X，期望 800000；若读到 FF0000 说明上传漏了预乘".format(shot.at(160, 60))
        )

        // ---- 4. globalAlpha ----
        println("\n-- 4. globalAlpha --")
        // 不透明绿 × 0.25：顶点色只乘 alpha ⇒ (0,64,0)；若图片不吃 globalAlpha 会是 (0,255,0)
        report(
            "★ 不透明绿 × globalAlpha 0.25 = (0,64,0)", shot.at(240, 60) == 0x004000,
            "实测 %06X，期望 004000".format(shot.at(240, 60))
        )

        // ---- 5. 裁剪 ----
        println("\n-- 5. 裁剪 --")
        report(
            "★ clipRect 之内画出来了", shot.at(320, 60) == 0x00FF00,
            "实测 %06X".format(shot.at(320, 60))
        )
        report(
            "★ clipRect 之外没有画（留着黑底）", shot.at(360, 60) == 0x000000,
            "实测 %06X；若也是绿说明裁剪没生效".format(shot.at(360, 60))
        )

        // ---- 6. 拾取 ----
        println("\n-- 6. 拾取 --")
        val gc = bridge.gc()
        if (gc == null) {
            report("前提：能拿到 Gc", false, "bridge.gc() 为 null")
            return
        }
        report(
            "★ 图片矩形内命中拾取号", gc.pick(410f, 50f)?.id == PICK_ID,
            "实测 ${gc.pick(410f, 50f)?.id}"
        )
        // ID pass 不看 alpha ⇒ 全透明的那一格照样命中。与"全透明图元照样能命中"同一条已声明行为。
        report(
            "★ **全透明**的格子也命中同一个拾取号", gc.pick(452f, 71f)?.id == PICK_ID,
            "实测 ${gc.pick(452f, 71f)?.id}（ID pass 不看 alpha，这是已声明的行为）"
        )
    }

    private fun verifyNoReupload(bridge: FXGLTransfer) {
        println("\n-- 7. 只上传一次 / 错误路径 --")
        val uploads = bridge.gc()!!.imageStore.takeUploadedImageCount()
        report(
            "★ 同一批图连续画第二帧：上传 0 张",
            uploads == 0L,
            "实测 $uploads 张（每帧全量重传与只传一次在画面上逐像素相同，只有这个数分得开）"
        )
        report("零宽矩形不抛异常", degenerateNoThrow, "实测 ${if (degenerateNoThrow) "没抛" else "抛了"}")
        report("★ NaN 的目标矩形明确抛 IllegalArgumentException", nanThrew, "实测 $nanThrew")
        report("★ 像素数与面积不匹配时 createImage 明确抛异常", badSizeThrew, "实测 $badSizeThrew")
        report("★ 已释放的句柄再画明确抛异常", disposedThrew, "实测 $disposedThrew")
    }

    private fun verifyGraceWindow(bridge: FXGLTransfer) {
        println("\n-- 8. 宽限：只隔一帧不重传 --")
        val uploads = bridge.gc()!!.imageStore.takeUploadedImageCount()
        report(
            "★ 有一张图只隔了一帧没画：仍然命中缓存，上传 0 张",
            uploads == 0L,
            "实测 $uploads 张（宽限只有一代时这里会变成 1——那种实现会把" +
                    "\"每隔一帧画一次\"变成每画一次就重传整张图）"
        )
    }

    private fun verifyRecycle(bridge: FXGLTransfer) {
        println("\n-- 9. 回收：隔两帧后重新画必须重传 --")
        val store = bridge.gc()!!.imageStore
        val uploads = store.takeUploadedImageCount()
        report(
            "★ 隔了两帧的那张图已被释放：重新画时上传 1 张",
            uploads == 1L,
            "实测 $uploads 张。这一条是\"回收真的发生了\"的**唯一**判据——" +
                    "不回收与回收在画面上逐像素相同（显存泄漏要几千帧后才显形）"
        )
        // 五张：grid、halfRed、green、recycleProbe（刚重建）、graceProbe（第 2 帧画过，仍在宽限内）
        report(
            "★ 第 3 帧的缓存里恰好 5 张纹理", store.imageTextureCount() == 5,
            "实测 ${store.imageTextureCount()} 张"
        )
    }

    private fun report(label: String, ok: Boolean, detail: String) {
        println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
        if (!ok) failures.add(label)
    }

    private fun finish() {
        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项 ===")
            failures.forEach { println("  ✗ $it") }
        }
        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    override fun stop() {
        transfer?.dispose()
    }
}

private const val IMG_W = 3
private const val IMG_H = 2

/** 六格探针铺到屏幕上的尺寸（整数倍放大：每格 `CELL` x `CELL` 设备像素）。 */
private val GRID_W = (IMG_W * CELL).toFloat()
private val GRID_H = (IMG_H * CELL).toFloat()

/**
 * 一次帧缓冲回读。
 *
 * <p>`glReadPixels` 的行序**自下而上**，而本类对外一律用"原点左上、y 向下"的行号
 * （与整条管线一致），只在一处翻——漏掉翻转的症状是"读数恒为 0"，看起来像画都没画。
 */
private class ImageShot(val size: Size) {

    class Size(val w: Int, val h: Int)

    private val w = size.w
    private val h = size.h
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4)

    init {
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)
    }

    /** 取某点的 24 位 RGB（**忽略 alpha**：本场景的背景是不透明的）。 */
    fun at(x: Int, yTopDown: Int): Int {
        if (x < 0 || yTopDown < 0 || x >= w || yTopDown >= h) return -1
        val i = ((h - 1 - yTopDown) * w + x) * 4
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * 整幅里等于该颜色（`0xAARRGGBB`，只比 RGB）的像素数。
     *
     * <p>⚠️ 比较的两边口径必须一致：本类给的是 24 位，而调用方手里的常量是 ARGB
     * ——直接比会**恒不相等**，而症状是"读数恒为 0"，看起来像功能没实现。
     */
    fun count(argb: Int): Int {
        val k = argb and 0xFFFFFF
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (at(x, y) == k) n++
        return n
    }

    /** 出现次数最多的几种颜色（诊断用）。 */
    fun topColors(n: Int): List<Pair<Int, Int>> {
        val m = HashMap<Int, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val c = at(x, y)
            m[c] = (m[c] ?: 0) + 1
        }
        return m.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }
}
