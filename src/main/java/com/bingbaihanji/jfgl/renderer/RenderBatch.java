package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.gl.VertexBuffer;
import com.bingbaihanji.jfgl.text.FontFile;
import com.bingbaihanji.jfgl.text.FontGlyphSource;
import com.bingbaihanji.jfgl.text.GlyphAtlas;
import com.bingbaihanji.jfgl.text.GlyphRasterizer;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.*;
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
 * <p>{@link #submit(VertexWriter)} 每次调用都做同样的事，帧中途 flush
 * （见 {@link VertexWriter#isFlushRequested()} 的消费方契约）与帧末提交走的是同一条路径。
 * 每次提交结束时都会把 GL 状态收回到中性（解绑 VAO、解绑着色器、关闭混合与裁剪测试），
 * 因此上一批的残留不会影响下一批。
 *
 * <p><strong>但它并非无状态</strong>：{@code pickBufferCleared} 与 {@code pickBufferValid}
 * 跨同一帧内的多次提交存活（{@code Gc} 一帧内会多次调用本方法——帧末一次 +
 * 每次 {@code flushIfNeeded} 一次）。这两个标志的<strong>唯一</strong>复位点是
 * {@link #beginFrame(int, int)}——每帧必须恰好调用一次，且要在本帧第一次
 * {@code submit} 之前。漏调或不调，表现为「只有最后一批可拾取」或
 * 「拾取到上一帧已消失的对象」，两者都不报错。
 *
 * <p><strong>{@code pickPassCount} 不在此列，它跨帧累计、从不复位。</strong>
 * 它是给校验器用的计数器（断言「若干帧恰好各跑了一趟 ID pass」），
 * <strong>不要</strong>往 {@link #beginFrame(int, int)} 里加复位——加了之后计数最多到 1，
 * 那条校验永远不可能通过，而症状是断言失败，排查方向会指向 ID pass 本身。
 *
 * <h2>混合与颜色</h2>
 * <p>颜色由 {@link VertexFormat#packPremultiplied} 打包为<strong>预乘 alpha</strong>，
 * 因此混合因子必须是 {@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA}。
 * {@link GLAbstraction#enableBlend()} 会先把混合因子设成非预乘的
 * {@code GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA}，所以本类紧接着必须再设一次——
 * 这两行的<strong>先后顺序不能调换</strong>，否则半透明边缘会出现二次混合的暗缝。
 *
 * <h2>文本与拾取</h2>
 * <p>ID pass 用的是同一张命令表，因此文本天然可拾取。但要注意
 * {@link #drawPickPass} <strong>不看 alpha</strong>（对整数附件而言，颜色没有意义），
 * 而文本的四边形覆盖的是整个 SDF 位图矩形——包含四周各 {@code SdfGenerator.SPREAD}
 * 像素的外扩。所以<strong>文本的可拾取范围比墨迹大一圈</strong>，
 * 这跟"全透明图元仍可拾取"是同一类行为，是刻意的、有测试钉着的，
 * 不要当成 bug"顺手修好"。
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

    /**
     * ID pass 的顶点着色器：只把拾取 ID 透传下去，位置同样已在 CPU 端烘焙好。
     *
     * <p>与颜色着色器分开、而不是在颜色着色器里多输出一个变量：
     * 颜色 pass 是热路径，不该为它用不到的东西多背一个 varying。
     *
     * <p>{@code flat} 不可省略——ID 是整数，跨三角形插值出来的中间值
     * 对应不存在的对象。
     */
    private static final String PICK_VERTEX_SHADER = """
                                                     #version 330 core
                                                     layout(location = 0) in vec2 aPos;
                                                     layout(location = 3) in uint aId;
                                                     flat out uint vId;
                                                     void main() {
                                                         gl_Position = vec4(aPos, 0.0, 1.0);
                                                         vId = aId;
                                                     }
                                                     """;

    /** ID pass 的片段着色器：直接写出 ID，不看颜色、不看 alpha、不采样纹理。 */
    private static final String PICK_FRAGMENT_SHADER = """
                                                       #version 330 core
                                                       flat in uint vId;
                                                       out uint fragId;
                                                       void main() {
                                                           fragId = vId;
                                                       }
                                                       """;

    /**
     * SDF 文本的片段着色器。
     *
     * <p>把图集的红通道当有符号距离（0 = 远在字形外，128 = 边界，255 = 远在字形内），
     * 用屏幕空间导数把边缘重新求一遍——<strong>平滑宽度必须由屏幕空间决定</strong>，
     * 预先烘进纹理是不可能的，因为同一个字形会在不同字号下被绘制。
     * 这正是 SDF 相对位图拉伸的全部价值。
     *
     * <p>{@code fwidth} 是 GLSL 3.3 core 的内建函数，不需要扩展。
     *
     * <p><strong>最后一行必须是 {@code vColor * a}，不能写成
     * {@code vec4(vColor.rgb, vColor.a * a)}。</strong>
     * 顶点色是<strong>预乘</strong>的，乘一个标量不破坏预乘性；
     * 而后者会破坏它——被覆盖的像素里 {@code rgb} 不再随 alpha 衰减，
     * 结果是每个被覆盖的像素都饱和到全白，边缘的过渡带整个消失
     * （与渲染管线文档里那条"混合因子顺序不能调换"是同一类问题）。
     */
    private static final String SDF_FRAGMENT_SHADER = """
                                                      #version 330 core
                                                      in vec2 vUV;
                                                      in vec4 vColor;
                                                      uniform sampler2D uTex;
                                                      out vec4 fragColor;
                                                      void main() {
                                                          float d  = texture(uTex, vUV).r;
                                                          float sd = d - 0.5;
                                                          float w  = fwidth(d);
                                                          float a  = smoothstep(-w, w, sd);
                                                          fragColor = vColor * a;
                                                      }
                                                      """;

    /** GL 抽象层，资源类操作（VAO/VBO/纹理/着色器）都经它转发。 */
    private final GLAbstraction gl;

    /** 唯一的着色器程序。 */
    private final ShaderProgram shader;

    /** ID pass 使用的着色器程序。 */
    private final ShaderProgram pickShader;

    /** 拾取缓冲，与颜色 pass 同尺寸。 */
    private final PickBuffer pickBuffer;

    /** 可增长的顶点缓冲，每帧整体覆盖上传。 */
    private final VertexBuffer vertexBuffer;

    /** SDF 文本使用的着色器程序。 */
    private final ShaderProgram sdfShader;

    /** 字体。与 glyphSource 同时创建，同时释放。 */
    private final FontFile font;

    /** 字形来源：字体 + 光栅化器 + 距离场。 */
    private final FontGlyphSource glyphSource;

    /** 字形图集，与颜色 pass 用同一张纹理。 */
    private final GlyphAtlas glyphAtlas;

    /** 本帧的拾取缓冲是否已被清空（每帧最多清一次，懒执行）。 */
    private boolean pickBufferCleared = false;

    /** 本帧渲染过 ID pass 后为 true；{@link #beginFrame} 时复位。 */
    private boolean pickBufferValid = false;

    /** 累计执行过的 ID pass 次数，仅供校验器断言「跳过优化」确实生效。 */
    private int pickPassCount = 0;

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
        this.pickShader = gl.createShader(PICK_VERTEX_SHADER, PICK_FRAGMENT_SHADER);
        // 尺寸先给 1x1 占位，第一帧 beginFrame 时会按真实尺寸重建。
        this.pickBuffer = new PickBuffer(gl, 1, 1);

        this.sdfShader = gl.createShader(VERTEX_SHADER, SDF_FRAGMENT_SHADER);
        // 字体在启动时就加载并解析：规格 §7 要求"字体缺失 / 无法解析"在启动时抛异常，
        // 而不是退化成"一个字都画不出来"——后者的表现是屏幕一片空白，
        // 排查方向会指向 GL 而不是字体。
        //
        // 这一步会分配 9.7 MB 的堆外内存并在 dispose 里归还，见 FontFile 的说明。
        this.font = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
        this.glyphSource = new FontGlyphSource(font, new GlyphRasterizer(font));
        this.glyphAtlas = new GlyphAtlas(gl, glyphSource);
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
     * 开始一帧：设置视口高度、把拾取缓冲调整到帧缓冲尺寸、复位本帧的拾取状态。
     *
     * <p>取代了早先的 {@code setViewportHeight}：尺寸调整与状态复位必须在同一处发生，
     * 分成两个方法迟早会有人只调其中一个。
     *
     * <p>拾取缓冲的尺寸检查放在这里而不是 reshape 回调里，是为了让任何来源的尺寸变化
     * 都被覆盖到——多一条路径就多一次漏掉的机会，而这里的开销只是一次整数比较。
     *
     * @param width  帧缓冲宽度（像素），必须为正
     * @param height 帧缓冲高度（像素），必须为正
     */
    public void beginFrame(int width, int height) {
        this.viewportHeight = height;
        pickBuffer.ensureSize(width, height);
        pickBufferCleared = false;
        // 本帧还没渲染 ID pass 之前，缓冲里装的是上一帧的结果。标为无效，
        // 这样上层的拾取查询（Gc.pick / Gc.pickRect）会诚实地返回「没命中」，
        // 而不是拿陈旧的 ID 去注册表里查——那会拾取到早已消失的对象，而画面完全正常。
        pickBufferValid = false;
        // 图集的帧边界重置钩子：只有在上一帧判定过"货架装不下"时才会真的重置，
        // 因此正常帧是零开销。整体重置只能发生在帧边界——见 GlyphAtlas 的类说明。
        glyphAtlas.beginFrame();
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
     * 返回字形图集。
     *
     * <p>{@code Gc.drawText} 需要它来取 uv 与绑定纹理。
     *
     * @return 字形图集
     */
    public GlyphAtlas glyphAtlas() {
        return glyphAtlas;
    }

    /**
     * 返回字形来源。
     *
     * <p>{@code Gc} 需要它来查字形索引与度量（{@code measureText} 走的是字体度量，
     * 不生成字形、不碰图集）。
     *
     * @return 字形来源
     */
    public FontGlyphSource glyphSource() {
        return glyphSource;
    }

    /**
     * 返回本批处理使用的 GL 抽象层。
     *
     * <p>图表后端需要它来建自己的着色器与 VAO。它是无状态的转发层，共享是安全的。
     *
     * @return GL 抽象层
     */
    public GLAbstraction glAbstraction() {
        return gl;
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

        // 材质按命令切换。连续文本是一片相邻的同材质命令，因此通常只切两次
        // （进入文本、离开文本），不会退化成每个字形一次切换。
        ShaderProgram current = shader;
        current.use();

        List<DrawCommand> commands = writer.commands();
        for (DrawCommand command : commands) {
            if (command.vertexCount() == 0) {
                continue;
            }
            ShaderProgram wanted = command.material() == Material.SDF_TEXT ? sdfShader : shader;
            if (wanted != current) {
                current.unuse();
                wanted.use();
                current = wanted;
            }
            // uTex 采样的是 0 号纹理单元：sampler 默认值就是 0，本类从不改动它，
            // 这里显式 glActiveTexture(GL_TEXTURE0) 是为了保证下面这行 glBindTexture
            // 绑定到的确实是 0 号单元（纹理单元的"当前"状态是全局的，不能假设它没被别人动过）。
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, command.textureId());
            applyScissor(command);
            glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
        }

        // 颜色 pass 画完后再走 ID pass：复用同一份 VBO、同一张命令表，只换程序。
        // 放在这里而不是另起一趟，是因为此刻 VAO/VBO/属性指针与 scissor 都正好是
        // 绘制所需的状态。
        if (writer.hasPickableVertices()) {
            // ID pass 自己会切程序并在结束时解绑；先把颜色 pass 的程序解绑，
            // 免得留下"已绑定但随后被换掉"的悬空状态。
            current.unuse();
            drawPickPass(commands);
        }

        glDisable(GL_SCISSOR_TEST);
        gl.bindVao(0);
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
     * 用 ID 着色器把同一批命令重画进拾取缓冲。
     *
     * <p><strong>前置条件</strong>：调用方已绑定 VAO/VBO、已配置属性指针、
     * 已启用裁剪测试，且颜色 pass 的绘制循环刚刚结束。
     *
     * <p><strong>混合必须关闭</strong>：对整数附件开混合是无效操作。
     *
     * <p><strong>裁剪必须保持开启</strong>：ID pass 复用同一套 scissor 换算，
     * 因此被裁掉的部分不可拾取。否则用户点了看不见的地方却命中，
     * 而画面完全正常——典型的静默错误。
     *
     * @param commands 本批的绘制命令
     */
    private void drawPickPass(List<DrawCommand> commands) {
        pickShader.use();
        try {
            withPickPass(() -> {
                for (DrawCommand command : commands) {
                    if (command.vertexCount() == 0) {
                        continue;
                    }
                    applyScissor(command);
                    glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
                }
            });
        } finally {
            pickShader.unuse();
        }
        pickPassCount++;
    }

    /**
     * 在拾取缓冲上执行一段绘制。
     *
     * <p>本方法负责：本帧首次调用时清空拾取缓冲、绑定拾取 FBO、结束后恢复原 FBO
     * 并把拾取缓冲标记为有效。调用方负责：绑定自己的 VAO/VBO、配置属性指针、
     * 设置 scissor、发出 draw call。
     *
     * <p><strong>为什么是回调而不是 {@code begin()}/{@code end()} 成对</strong>：
     * 成对的 API 一定有人忘了调 {@code end()}，而忘掉的表现是"下一帧画进了拾取缓冲"——
     * 画面完全正常，只是拾取全错。回调式让编译器替他记住。
     *
     * <p><strong>调用方不得改动 {@link #pickPassCount()}</strong>。它跨帧累计、从不复位，
     * 是 {@code PickVerifier} 用来断言"无拾取对象时整趟跳过"的计数器。往它里面加计数会
     * 污染已有的断言，而症状是"拾取校验器突然失败"，排查方向会指向 ID pass 本身。
     *
     * <p><strong>调用方必须自己设置 scissor</strong>：被裁掉的部分不可拾取，与画面一致。
     * 本方法不做这件事，因为裁剪矩形取决于调用方的几何。
     *
     * @param body 要执行的绘制，不得为 null
     */
    public void withPickPass(Runnable body) {
        Objects.requireNonNull(body, "body");
        if (!pickBufferCleared) {
            pickBuffer.clear();
            pickBufferCleared = true;
        }

        int previousFramebuffer = gl.currentFramebufferBinding();
        gl.bindFramebuffer(pickBufferId());

        // 整数附件不能开混合；ID 被插值成「零点几个对象」也没有意义。
        gl.disableBlend();
        try {
            body.run();
        } finally {
            // 必须恢复：openglfx 渲染到它自己的 FBO，不恢复的话下一帧会画进拾取缓冲。
            gl.bindFramebuffer(previousFramebuffer);
        }

        // 标记缓冲有效：不置的话上层拾取查询会诚实地返回「没命中」，
        // 于是图表永远点不中，而画面完全正常——那种「看起来像没实现」的静默错误。
        pickBufferValid = true;
    }

    /**
     * 返回拾取缓冲的 FBO ID。
     *
     * @return FBO 的 ID
     */
    private int pickBufferId() {
        return pickBuffer.framebufferId();
    }

    /**
     * 读回一个像素的拾取 ID。
     *
     * <p>本帧没有渲染过 ID pass 时返回 0（「未命中」）而不做读回：
     * 此时缓冲里是上一帧的陈旧数据，读出来会拾取到早已消失的对象。
     *
     * @param x 用户坐标 x
     * @param y 用户坐标 y
     * @return 命中的 ID，未命中或本帧无拾取内容时为 0
     */
    public int readPickPixel(int x, int y) {
        if (!pickBufferValid) {
            return 0;
        }
        return pickBuffer.readPixel(x, y);
    }

    /**
     * 读回一块区域内的拾取命中（按 ID 升序，每个附带首次出现坐标）。
     *
     * @param x 用户坐标左边缘
     * @param y 用户坐标上边缘
     * @param w 宽度
     * @param h 高度
     * @return 区域内出现过的命中，按 ID 升序；本帧无拾取内容时为空列表
     */
    public List<PickPixel> readPickRect(int x, int y, int w, int h) {
        if (!pickBufferValid) {
            return List.of();
        }
        return pickBuffer.readRect(x, y, w, h);
    }

    /**
     * 返回累计执行过的 ID pass 次数。
     *
     * <p>仅供校验器断言「无拾取对象时整趟跳过」确实生效。没有它，
     * 那条优化就只是注释里的一句承诺。
     *
     * @return ID pass 执行次数
     */
    public int pickPassCount() {
        return pickPassCount;
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
        pickShader.dispose();
        pickBuffer.dispose();
        sdfShader.dispose();
        glyphAtlas.dispose();
        font.dispose();
        vertexBuffer.dispose();
        gl.deleteVao(vao);
        glDeleteTextures(whiteTexture);
        disposed = true;
    }
}
