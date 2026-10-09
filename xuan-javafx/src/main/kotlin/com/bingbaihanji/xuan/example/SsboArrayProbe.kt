package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.gl.LwjglGLAbstraction
import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.gpu.ComputeShader
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL43.*
import kotlin.system.exitProcess

/**
 * R1 探针：本机驱动是否支持"运行时下标访问 SSBO 数组块"。
 *
 * <p>这是多路频谱批处理（交付物 C）的命门——C 要"每个系列保留自己的 VBO，分别
 * `glBindBufferBase` 到绑定点 0..C-1，着色器里 `ch[gl_WorkGroupID.x]` 取"。
 * `ARB_shader_storage_buffer_object`（OpenGL 4.3 核心）规范上支持数组块，且
 * `gl_WorkGroupID.x` 是 dynamically uniform 表达式，但本机没被实测过，所以先测。
 *
 * <p>通过判据（缺一不可）：① 数组块着色器能编译并链接；② `ch[gl_WorkGroupID.x]` 读到的
 * 确实是各自绑定点上那个缓冲的数据（两路数据不同，串了立刻现形）；③ 全程 `glGetError() == 0`。
 * 顺带打印 `GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS`——它决定 C 里 `MAX_BATCH` 能取多大。
 */
@JvmName("main")
fun ssboArrayProbeMain() {
    Application.launch(SsboArrayProbeApp::class.java)
}

private const val CH = 2

private const val LEN = 8

class SsboArrayProbeApp : Application() {

    private var transfer: FXGLTransfer? = null

    private var finished = false

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer(msaa = readRequestedMsaa())
        requirePixelReadback(bridge)
        bridge.onRender { runOnce() }
        transfer = bridge
        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "SSBO 数组块探针"
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
    val gl = LwjglGLAbstraction()
    println("=== R1 探针：SSBO 数组块（ARB_shader_storage_buffer_object）===")
    println("GL_VERSION  = ${glGetString(GL_VERSION)}")
    println("GL_RENDERER = ${glGetString(GL_RENDERER)}")
    println("GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS = ${glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)}")
    // 真正决定数组块能开多大的不是上面那个（96 个绑定点），而是这个：
    // "一个 compute 着色器里最多有几个 SSBO 块"。数组块 ch[N] 算 N 个块。
    println("GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS = ${glGetInteger(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)}")
    println("GL_MAX_SHADER_STORAGE_BLOCK_SIZE    = ${glGetInteger(GL_MAX_SHADER_STORAGE_BLOCK_SIZE)}")

    var failures = 0
    fun report(label: String, ok: Boolean, detail: String) {
        println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
        if (!ok) failures++
    }

    // 两个输入缓冲：第 c 路的数据是 c*100 + i（两路明显不同，串了一眼就看得出来）。
    val inBufs = IntArray(CH)
    for (c in 0 until CH) {
        val b = gl.createBuffer()
        gl.bindShaderStorageBuffer(b)
        gl.allocateBufferStorage(LEN.toLong() * Float.SIZE_BYTES)
        gl.bindShaderStorageBuffer(0)
        val data = FloatArray(LEN) { (c * 100 + it).toFloat() }
        val bytes = BufferUtils.createByteBuffer(LEN * Float.SIZE_BYTES)
        bytes.asFloatBuffer().put(data)
        gl.bindShaderStorageBuffer(b)
        gl.uploadBufferSubData(0L, bytes)
        gl.bindShaderStorageBuffer(0)
        inBufs[c] = b
    }
    val outBuf = gl.createBuffer()
    gl.bindShaderStorageBuffer(outBuf)
    gl.allocateBufferStorage((CH * LEN).toLong() * Float.SIZE_BYTES)
    gl.bindShaderStorageBuffer(0)

    // 数组块声明 `ch[2]` 配 `binding = 0` ⇒ 元素 c 落在绑定点 c。
    // 输出放绑定点 2，避开输入占的 0..CH-1。
    val src = """
        #version 430
        layout(local_size_x = 8) in;
        layout(std430, binding = 0) readonly  buffer In  { float y[]; } ch[2];
        layout(std430, binding = 2) writeonly buffer Out { float o[]; };
        void main() {
            int c   = int(gl_WorkGroupID.x);
            int lid = int(gl_LocalInvocationID.x);
            o[c * 8 + lid] = ch[c].y[lid] * 2.0;
        }
    """.trimIndent()

    var shader: ComputeShader? = null
    var compileError: Throwable? = null
    try {
        shader = ComputeShader(src)
    } catch (t: Throwable) {
        compileError = t
    }
    report(
        "数组块着色器能编译并链接",
        shader != null,
        compileError?.message?.trim()?.take(400) ?: "编译并链接通过"
    )
    if (shader == null) {
        gl.deleteBuffer(outBuf)
        inBufs.forEach { gl.deleteBuffer(it) }
        return 1
    }

    shader.use()
    for (c in 0 until CH) gl.bindBufferBase(c, inBufs[c])
    gl.bindBufferBase(2, outBuf)
    shader.dispatch(CH, 1, 1)
    shader.memoryBarrier()
    for (c in 0 until CH) gl.bindBufferBase(c, 0)
    gl.bindBufferBase(2, 0)
    shader.unuse()

    val fb = BufferUtils.createFloatBuffer(CH * LEN)
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf)
    glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, fb)
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

    var dataOk = true
    val seen = StringBuilder()
    for (c in 0 until CH) {
        for (i in 0 until LEN) {
            // 期望：(c*100 + i) * 2。串了的话第 1 路会是第 0 路的值。
            val want = (c * 100 + i) * 2.0f
            val got = fb.get(c * LEN + i)
            if (got != want) dataOk = false
        }
        seen.append("第 $c 路前 3 个：")
        for (i in 0 until 3) seen.append("${fb.get(c * LEN + i)} ")
    }
    report(
        "ch[gl_WorkGroupID.x] 读到的是各自绑定点上缓冲的数据（两路不串）",
        dataOk,
        "$seen（期望各路是 (c*100+i)*2）"
    )

    val err = drainErrors()
    report("全程没有 GL 错误（glGetError == 0）", err == 0, "错误码 $err")

    shader.dispose()
    gl.deleteBuffer(outBuf)
    inBufs.forEach { gl.deleteBuffer(it) }

    println()
    println(if (failures == 0) "=== R1 通过：数组块可用，C 可以按 §6.2 做 ===" else "=== R1 失败 $failures 项：C 要退回「共享 VBO 池」方案 ===")
    return if (failures == 0) 0 else 1
}

/** 抽干 GL 错误队列，返回第一个非 0 的错误码（必须抽干——只读一次会把错误留给下一次检查）。 */
private fun drainErrors(): Int {
    var first = GL_NO_ERROR
    var e = glGetError()
    while (e != GL_NO_ERROR) {
        if (first == GL_NO_ERROR) first = e
        e = glGetError()
    }
    return first
}
