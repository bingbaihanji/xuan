package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.gl.VertexBuffer;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glVertexAttribIPointer;

/**
 * GL 侧的批处理提交器：把 {@link VertexWriter} 收集的顶点上传并执行绘制命令。
 *
 * <p>本类只负责"把已收集的数据画出来"，不做任何几何计算。
 * 全部方法必须在 GL 线程上调用。
 *
 * <h2>一次提交做了什么</h2>
 * <ol>
 *   <li>把 {@link VertexWriter#buffer()} 的顶点字节整块上传到 {@link VertexBuffer}（必要时扩容）；</li>
 *   <li>绑定 VAO 与 VBO，<strong>无条件</strong>重设顶点属性指针（原因见
 *       {@link #configureVaoAttributes()}）；</li>
 *   <li>按 {@link VertexWriter#commands()} 的顺序逐条设置纹理与裁剪矩形，发出一连串
 *       {@code glDrawArrays}——整帧的 draw call 数量就是命令条数，这才是批处理的意义所在
 *       （JavaFX Canvas 是逐图元 CPU 光栅化，这里是"一次上传 + 个位数 draw call"）。</li>
 * </ol>
 *
 * <h2>可以在同一帧内多次调用</h2>
 * <p>{@link #submit(VertexWriter)} 是幂等的外观操作，不持有"本帧已提交"之类的状态：
 * 帧中途 flush（见 {@link VertexWriter#isFlushRequested()} 的消费方契约）与帧末提交走的是同一条路径。
 * 每次提交结束时都会把 GL 状态收回到中性（解绑 VAO、解绑着色器、关闭混合与裁剪测试），
 * 因此上一批的残留不会影响下一批。
 *
 * <h2>混合与颜色</h2>
 * <p>颜色由 {@link VertexFormat#packPremultiplied} 打包为<strong>预乘 alpha</strong>，
 * 因此混合因子必须是 {@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA}。
 * {@link GLAbstraction#enableBlend()} 会先把混合因子设成非预乘的
 * {@code GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA}，所以本类紧接着必须再设一次——
 * 这两行的<strong>先后顺序不能调换</strong>，否则半透明边缘会出现二次混合的暗缝。
 */
public final class RenderBatch implements Disposable {

    /** 顶点着色器：位置已在 CPU 端烘焙到 NDC，这里只做透传。 */
    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;
            layout(location = 3) in uint aId;
            out vec2 vUV;
            out vec4 vColor;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = aUV;
                vColor = aColor;
            }
            """;

    /**
     * 片段着色器：采样纹理后与顶点色相乘。
     *
     * <p>{@code uTex} 在纯色绘制时绑定的是 1×1 的白色纹理，于是结果正好是顶点色本身，
     * 全管线只需要这一个 fragment shader。
     */
    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUV) * vColor;
            }
            """;

    /** GL 抽象层，资源类操作（VAO/VBO/纹理/着色器）都经它转发。 */
    private final GLAbstraction gl;

    /** 唯一的着色器程序。 */
    private final ShaderProgram shader;

    /** 可增长的顶点缓冲，每帧整体覆盖上传。 */
    private final VertexBuffer vertexBuffer;

    /** 顶点数组对象，记录属性指针与（未来的）索引缓冲绑定。 */
    private int vao;

    /** 1×1 不透明白色纹理的 ID，纯色绘制时绑定，使 {@code texture(uTex, vUV)} 恒为 1。 */
    private int whiteTexture;

    /** 视口高度，用于把裁剪矩形从 y 向下翻转为 GL 的 y 向上。 */
    private int viewportHeight;

    /** 是否已释放，保证 {@link #dispose()} 幂等。 */
    private boolean disposed = false;

    /**
     * 创建批处理提交器：编译着色器、分配 VBO、生成白色纹理与 VAO。
     *
     * <p>必须在 GL 线程（且 GL 上下文已 current）上调用。
     *
     * @param gl                     GL 抽象层
     * @param initialVertexCapacity  初始顶点容量，内部按
     *                               {@link VertexFormat#STRIDE_BYTES} 换算成字节数
     */
    public RenderBatch(GLAbstraction gl, int initialVertexCapacity) {
        this.gl = gl;
        this.shader = gl.createShader(VERTEX_SHADER, FRAGMENT_SHADER);
        this.vertexBuffer = new VertexBuffer(gl, initialVertexCapacity * VertexFormat.STRIDE_BYTES);
        this.whiteTexture = gl.createTexture(1, 1, new int[]{0xFFFFFFFF});
        createVao();
    }

    /**
     * 生成 VAO。
     *
     * <p>此处只生成对象名，属性指针留给 {@link #configureVaoAttributes()}——
     * 那一步必须发生在"VAO 与最终使用的 VBO 都已绑定"之后，不能提前到构造期。
     */
    private void createVao() {
        vao = gl.createVao();
    }

    /**
     * 在当前已绑定的 VAO 与 VBO 上设置顶点属性指针。
     *
     * <p><strong>前置条件</strong>：调用方必须已经绑定 {@code vao} 与
     * {@code vertexBuffer.id()}。本方法<strong>不做任何绑定，也不解绑</strong>——
     * 绑定职责完整地留给调用方，避免"谁负责解绑"这种含糊契约。
     *
     * <p><strong>为什么每帧都要重跑</strong>：{@code glVertexAttribPointer} 会把
     * <strong>调用时绑定的 GL_ARRAY_BUFFER</strong> 记录进 VAO。而
     * {@link VertexBuffer#grow()} 扩容时会删除旧 VBO 并新建一个 ——
     * 此时 VAO 的属性指针就指向了一个已删除的缓冲，绘制会报错或输出空白。
     * 事后重新 {@code glBindBuffer} <strong>无法</strong>修复，因为 GL_ARRAY_BUFFER
     * 绑定不是 VAO 状态（只有 GL_ELEMENT_ARRAY_BUFFER 是）。
     *
     * <p><strong>不要试图用"VBO 的 ID 有没有变"来跳过这次调用。</strong>
     * {@code glGenBuffers} 返回的是 GL 对象<strong>名</strong>，而名字是会被回收复用的。
     * {@code grow()} 恰好是"先 delete 再 gen"的顺序，被释放的名字是驱动空闲列表里最新的一项，
     * 极可能原样发回来。于是 ID 比较会得到"没变"的结论而跳过重配置 ——
     * 正好在需要它的场景下失效。用代数或标志位判断同样是在维护一份容易失同步的平行状态。
     *
     * <p>每帧多跑 8 次 GL 调用（4 次 VertexAttribPointer + 4 次 EnableVertexAttribArray）
     * 相对整帧开销完全可以忽略，因此这里选择**无条件重跑**，把问题彻底消掉。
     *
     * <p>属性布局与 {@link VertexFormat} 的常量一一对应（步长 24 字节）：
     * <ul>
     *   <li>location 0：{@code vec2 float}，偏移 {@link VertexFormat#OFFSET_POSITION}＝0</li>
     *   <li>location 1：{@code vec2 float}，偏移 {@link VertexFormat#OFFSET_UV}＝8</li>
     *   <li>location 2：{@code vec4 ubyte normalized}，偏移 {@link VertexFormat#OFFSET_COLOR}＝16</li>
     *   <li>location 3：{@code uint}，偏移 {@link VertexFormat#OFFSET_ID}＝20</li>
     * </ul>
     *
     * <p><strong>location 3 必须用 {@code glVertexAttribIPointer} 而不是
     * {@code glVertexAttribPointer}。</strong>拾取 ID 是整数属性，整数属性只能由
     * IPointer 建立；用浮点路径（哪怕 {@code normalized} 传 false）去读一个整数属性，
     * 着色器读到的是未定义值——而且不会有任何报错，只会安静地读到垃圾。
     */
    private void configureVaoAttributes() {
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_POSITION);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, 2, GL_FLOAT, false, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_UV);
        glEnableVertexAttribArray(1);

        // 颜色是 4 个归一化字节：normalized 必须为 true，
        // 否则 0..255 的字节会被当成 0..255 的浮点数直接送进着色器。
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, VertexFormat.STRIDE_BYTES,
                VertexFormat.OFFSET_COLOR);
        glEnableVertexAttribArray(2);

        // ID 是整数属性，必须用 IPointer，不能用归一化的 VertexAttribPointer
        glVertexAttribIPointer(3, 1, GL_UNSIGNED_INT,
                VertexFormat.STRIDE_BYTES, VertexFormat.OFFSET_ID);
        glEnableVertexAttribArray(3);
    }

    /**
     * 设置本帧的视口高度，用于裁剪坐标换算。
     *
     * <p>必须在 {@link #submit(VertexWriter)} 之前调用（{@code Gc.beginFrame} 负责）。
     * 未设置时高度为 0，裁剪矩形会被翻转到帧缓冲之外，表现为整帧空白。
     *
     * @param height 帧缓冲高度（像素）
     */
    public void setViewportHeight(int height) {
        this.viewportHeight = height;
    }

    /**
     * 返回 1×1 白色纹理的 ID，纯色绘制时绑定它。
     *
     * <p>该纹理是 {@code 0xFFFFFFFF} 的一个像素，即 RGBA 全为 255 的不透明白色；
     * 由于是 1×1 且环绕方式为默认的 {@code GL_REPEAT}，无论 UV 取什么值，
     * {@code texture(uTex, vUV)} 都恰好返回 {@code (1,1,1,1)}，因此「采样结果 = 顶点色」。
     *
     * @return 白色纹理的 ID
     */
    public int whiteTextureId() {
        return whiteTexture;
    }

    /**
     * 提交并绘制一批已收集的顶点。
     *
     * <p><strong>同一帧内可以被调用任意多次</strong>：帧中途 flush
     * （{@link VertexWriter#isFlushRequested()} 置位时，由 {@code Gc} 调用）与帧末提交
     * 走的是同一条路径。本方法不假设自己是"本帧最后一次提交"，每次调用都完整地
     * 绑定 → 配置属性 → 绘制 → 复位状态，因此上一批不会污染下一批。
     *
     * <p>{@code writer.vertexCount() == 0} 时直接返回：没有顶点就没有命令，
     * 上传 0 字节只会白白走一趟 GL，也会让 {@code glBufferData} 把缓冲重新分配成 0 字节。
     *
     * <p>调用方负责在提交后调用 {@link VertexWriter#reset()} 并重新
     * {@link VertexWriter#setState}（契约见 {@link VertexWriter#isFlushRequested()}），
     * 本方法<strong>不会</strong>替调用方清空写入器。
     *
     * <p>结束时 GL 状态回到中性：裁剪测试与混合关闭、VAO 解绑为 0、着色器程序解绑。
     *
     * @param writer 已收集好的顶点与命令
     */
    public void submit(VertexWriter writer) {
        if (writer.vertexCount() == 0) {
            return;
        }
        vertexBuffer.upload(writer.buffer());

        shader.use();
        gl.bindVao(vao);
        gl.bindVbo(vertexBuffer.id());

        // 无条件重新配置属性指针。原因见 configureVaoAttributes 的说明：
        // 扩容会替换底层 VBO，而 GL 名字会被回收，无法靠比较 ID 可靠地检测到替换。
        // 必须在这里调用：此时 VAO 与新 VBO 都已绑定，且紧接着就是绘制。
        configureVaoAttributes();

        gl.enableBlend();
        // 顶点色是预乘的，混合因子必须配套；这一行必须在 enableBlend() 之后，
        // 因为 GLAbstraction.enableBlend() 会顺手把混合因子设成非预乘的那一组。
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        // LWJGL 3 没有 glScissorTest 这个便捷函数，只有 glEnable/glDisable(GL_SCISSOR_TEST)。
        glEnable(GL_SCISSOR_TEST);

        List<DrawCommand> commands = writer.commands();
        for (DrawCommand command : commands) {
            if (command.vertexCount() == 0) {
                continue;
            }
            // uTex 采样的是 0 号纹理单元：sampler 默认值就是 0，本类从不改动它，
            // 这里显式 glActiveTexture(GL_TEXTURE0) 是为了保证下面这行 glBindTexture
            // 绑定到的确实是 0 号单元（纹理单元的"当前"状态是全局的，不能假设它没被别人动过）。
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, command.textureId());
            applyScissor(command);
            glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
        }

        glDisable(GL_SCISSOR_TEST);
        gl.bindVao(0);
        shader.unuse();
        gl.disableBlend();
    }

    /**
     * 按命令里的裁剪矩形设置裁剪盒。
     *
     * <p><strong>y 要翻转</strong>：{@code glScissor} 的原点在帧缓冲<strong>左下角</strong>、
     * y 向上，而本管线的用户空间是"像素、原点左上、y 向下"，{@link DrawCommand} 里的
     * scissorY 记的是矩形<strong>顶边</strong>。因此 GL 侧的矩形下边 =
     * {@code viewportHeight - scissorY - scissorHeight}。
     * 漏掉这一步的后果是裁剪区域上下镜像（画在每个裁剪区的下半部分时被裁掉、上半部分却露出来），
     * 而顶点本身是对的，所以肉眼很容易误判成别的问题。
     *
     * @param command 待执行的绘制命令
     */
    private void applyScissor(DrawCommand command) {
        int y = viewportHeight - command.scissorY() - command.scissorHeight();
        glScissor(command.scissorX(), y, command.scissorWidth(), command.scissorHeight());
    }

    /**
     * 释放本类创建的全部 GL 资源：着色器程序、VBO、VAO、白色纹理。
     *
     * <p>幂等：重复调用无副作用。释放后本对象不可再用于绘制
     * （{@link #whiteTextureId()} 会返回一个已被删除的名字）。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        shader.dispose();
        vertexBuffer.dispose();
        gl.deleteVao(vao);
        glDeleteTextures(whiteTexture);
        disposed = true;
    }
}
