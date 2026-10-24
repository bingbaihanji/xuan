package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.gpu.ComputeShader
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL42.*
import org.lwjgl.opengl.GL43.*
import kotlin.system.exitProcess

/**
 * step 0 探针：本机驱动是否支持 compute shader 里用 `imageStore` 往纹理写。
 *
 * <p>这是"声谱图"（时间 × 频率 × 颜色瀑布）的命门——方案是 compute shader 读 FFT 输出的
 * SSBO，映射颜色后 `imageStore` 把结果写进一张纹理的某一列。**本仓库从没实测过
 * `imageStore`**：`CLAUDE.md` 里那句"`imageStore` 可用"是**能力推断**（由 GL 4.6 上下文
 * 推出来的），不是实测读数。所以先测——测不过就得退回"CPU 回读一列"的方案。
 *
 * <p>通过判据（缺一不可）：
 * ① `#version 430` + `layout(rgba8, binding = 0) uniform writeonly image2D` 能编译并链接；
 * ② `glBindImageTexture` 把纹理绑上 image unit 0 后 dispatch，回读的**每一个**纹素
 *    都等于着色器写进去的那条可预测公式（**先把纹理填成哨兵色**，于是"写了"与
 *    "什么都没写"在回读上分得开）；
 * ③ 全程 `glGetError() == 0`。
 * 顺带打印 `GL_MAX_IMAGE_UNITS` 与 `GL_MAX_COMPUTE_IMAGE_UNIFORMS`——前者决定能同时
 * 绑几张 image，后者决定一个 compute 程序里能声明几个 image uniform。
 *
 * <p>**它不往画布 FBO 画任何东西**：读的是自己建的那张纹理（`glGetTexImage`），
 * 所以它不受画布采样数影响；但按本仓库的纪律仍然挂在同一道 `requirePixelReadback` 守卫上。
 */
@JvmName("main")
fun imageStoreProbeMain() {
    Application.launch(ImageStoreProbeApp::class.java)
}

/** 探针纹理的边长（16×16 = 256 个纹素，dispatch 一个 16×16 的 workgroup 正好盖满）。 */
private const val TEX_SIZE = 16

class ImageStoreProbeApp : Application() {

    private var transfer: FXGLTransfer? = null

    private var finished = false

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer(msaa = readRequestedMsaa())
        requirePixelReadback(bridge)
        bridge.onRender { runOnce() }
        transfer = bridge
        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "imageStore 探针"
        stage.scene = Scene(mainView.createMainView(), 320.0, 240.0)
        stage.show()
    }

    private fun runOnce() {
        if (finished) return
        finished = true
        val code = try {
            probe()
        } catch (t: Throwable) {
            println("\n=== 探针抛出异常，判为失败 ===")
            t.printStackTrace()
            1
        }
        Platform.exit()
        exitProcess(code)
    }

    override fun stop() {
        transfer?.dispose()
    }
}

private fun probe(): Int {
    println("=== step 0 探针：compute shader 的 imageStore 往纹理写 ===")
    println("GL_VERSION          = ${glGetString(GL_VERSION)}")
    println("GL_RENDERER         = ${glGetString(GL_RENDERER)}")
    println("GLSL_VERSION        = ${glGetString(GL_SHADING_LANGUAGE_VERSION)}")

    // 这两个数是这次探针顺带要交的读数：前者决定能同时绑几张 image（多路声谱图要几列），
    // 后者决定一个 compute 程序里能声明几个 image uniform。
    val maxImageUnits = glGetInteger(GL_MAX_IMAGE_UNITS)
    val maxComputeImageUniforms = glGetInteger(GL_MAX_COMPUTE_IMAGE_UNIFORMS)
    println("GL_MAX_IMAGE_UNITS                  = $maxImageUnits")
    println("GL_MAX_COMPUTE_IMAGE_UNIFORMS       = $maxComputeImageUniforms")

    var failures = 0
    fun report(label: String, ok: Boolean, detail: String) {
        println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
        if (!ok) failures++
    }

    /** 抽干错误队列，把"第一次出现的错误码"记在 firstError 里（只读一次会把错误留给下一步）。 */
    var firstError = GL_NO_ERROR
    fun sinkErrors(tag: String): Int {
        var e = glGetError()
        while (e != GL_NO_ERROR) {
            if (firstError == GL_NO_ERROR) {
                firstError = e
                println("  [错误] $tag 处首次出现 GL 错误 0x${e.toString(16)}")
            }
            e = glGetError()
        }
        return firstError
    }

    // ---- 1. 建纹理：先填哨兵色，这样"imageStore 写了"与"什么都没写"分得开 ----
    // 哨兵 = (x*3, y*5, 7, 255)，与期望 (x, y, 0, 255) 每一个分量都不同。
    val tex = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, tex)
    val sentinel = BufferUtils.createByteBuffer(TEX_SIZE * TEX_SIZE * 4)
    for (y in 0 until TEX_SIZE) {
        for (x in 0 until TEX_SIZE) {
            sentinel.put((x * 3).toByte())
            sentinel.put((y * 5).toByte())
            sentinel.put(7.toByte())
            sentinel.put(255.toByte())
        }
    }
    sentinel.flip()
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, TEX_SIZE, TEX_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, sentinel)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    glBindTexture(GL_TEXTURE_2D, 0)
    sinkErrors("建纹理 / glTexImage2D")

    // ---- 2. 编译 + 链接 compute 着色器 ----
    // 写进去的值刻意取 float(x)/255.0：它是 1/255 的整数倍，unorm8 转换里
    // round(v * 255) 恰好回到整数，**不踩 .5 那种舍入歧义**（x/16.0 在 x=8 时正好是 .5）。
    val src = """
        #version 430
        layout(local_size_x = $TEX_SIZE, local_size_y = $TEX_SIZE) in;
        layout(rgba8, binding = 0) uniform writeonly image2D img;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            imageStore(img, p, vec4(float(p.x) / 255.0, float(p.y) / 255.0, 0.0, 1.0));
        }
    """.trimIndent()

    var shader: ComputeShader? = null
    var compileError: Throwable? = null
    try {
        shader = ComputeShader(src)
    } catch (t: Throwable) {
        compileError = t
    }
    // 失败时把**完整**的编译/链接日志打出来（那是结论的一部分，不做截断）。
    report(
        "imageStore 着色器能编译并链接",
        shader != null,
        if (shader == null) "见下方完整日志" else "编译并链接通过"
    )
    val program = shader
    if (program == null) {
        println("  ---- 着色器编译/链接原始日志开始 ----")
        println(compileError?.message)
        println("  ---- 着色器编译/链接原始日志结束 ----")
        glDeleteTextures(tex)
        sinkErrors("清理")
        println()
        println("=== step 0 失败 $failures 项：imageStore 在本机不可用，声谱图要退回「CPU 回读一列」 ===")
        return 1
    }
    sinkErrors("编译 / 链接")

    // ---- 3. 绑 image unit 0，dispatch 盖满整张纹理 ----
    glBindImageTexture(0, tex, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
    sinkErrors("glBindImageTexture")

    program.use()
    program.dispatch(1, 1, 1)
    // 全屏障：既有 GL_TEXTURE_FETCH_BARRIER_BIT（让后续纹理读取看到写入），
    // 也有 GL_SHADER_IMAGE_ACCESS_BARRIER_BIT（image 访问的可见性）。
    glMemoryBarrier(GL_ALL_BARRIER_BITS)
    program.unuse()
    glBindImageTexture(0, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
    sinkErrors("dispatch / glMemoryBarrier")

    // ---- 4. 回读纹理并逐像素比对 ----
    glBindTexture(GL_TEXTURE_2D, tex)
    val pixels = BufferUtils.createByteBuffer(TEX_SIZE * TEX_SIZE * 4)
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
    glBindTexture(GL_TEXTURE_2D, 0)
    sinkErrors("glGetTexImage")

    var mismatch = 0
    var maxDelta = 0
    var sentinelLeft = 0
    val detailLines = StringBuilder()
    for (y in 0 until TEX_SIZE) {
        for (x in 0 until TEX_SIZE) {
            val i = (y * TEX_SIZE + x) * 4
            val gotR = pixels.get(i).toInt() and 0xFF
            val gotG = pixels.get(i + 1).toInt() and 0xFF
            val gotB = pixels.get(i + 2).toInt() and 0xFF
            val gotA = pixels.get(i + 3).toInt() and 0xFF
            val wantR = x
            val wantG = y
            val wantB = 0
            val wantA = 255
            val d = maxOf(
                Math.abs(gotR - wantR), Math.abs(gotG - wantG),
                Math.abs(gotB - wantB), Math.abs(gotA - wantA)
            )
            if (d > maxDelta) maxDelta = d
            if (d != 0) {
                mismatch++
                if (detailLines.length < 400) {
                    detailLines.append("($x,$y) 期望($wantR,$wantG,$wantB,$wantA) 实测($gotR,$gotG,$gotB,$gotA)  ")
                }
            }
            if (gotR == x * 3 && gotG == y * 5 && gotB == 7) sentinelLeft++
        }
    }
    report(
        "★ imageStore 写进去的值被 glGetTexImage 逐像素读回后与期望完全相等",
        mismatch == 0,
        if (mismatch == 0) {
            "${TEX_SIZE * TEX_SIZE} 个纹素全部精确相等，最大分量差 $maxDelta（期望 R=x, G=y, B=0, A=255）"
        } else {
            "$mismatch / ${TEX_SIZE * TEX_SIZE} 个纹素不等，最大分量差 $maxDelta；样例：$detailLines"
        }
    )
    // 反证：哨兵还在 ⇒ imageStore 其实没写成功（否则上面那条与这条会一起倒）。
    report(
        "哨兵色已被覆盖（证明读回的不是建纹理时那份初始数据）",
        sentinelLeft == 0,
        "仍留有哨兵色的纹素 $sentinelLeft 个（0 = 全部被 imageStore 覆盖）"
    )

    // ---- 5. 顺带断言那两个容量不为 0（声明了 image uniform 就至少要有 1 个） ----
    report(
        "image unit 容量可用（GL_MAX_IMAGE_UNITS / GL_MAX_COMPUTE_IMAGE_UNIFORMS > 0）",
        maxImageUnits >= 1 && maxComputeImageUniforms >= 1,
        "MAX_IMAGE_UNITS=$maxImageUnits, MAX_COMPUTE_IMAGE_UNIFORMS=$maxComputeImageUniforms"
    )

    sinkErrors("回读")
    report("全程没有 GL 错误（glGetError == 0）", firstError == GL_NO_ERROR, "首个错误码 0x${firstError.toString(16)}")

    program.dispose()
    glDeleteTextures(tex)
    sinkErrors("清理")

    println()
    println(
        if (failures == 0) "=== step 0 通过：imageStore 可用，声谱图可以按「compute 写纹理」做 ==="
        else "=== step 0 失败 $failures 项：imageStore 在本机不可用，声谱图要退回「CPU 回读一列」 ==="
    )
    return if (failures == 0) 0 else 1
}
