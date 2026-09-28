package com.bingbaihanji.xuan.gl;

import com.bingbaihanji.xuan.util.Color;
import com.bingbaihanji.xuan.util.Disposable;

import java.nio.ByteBuffer;

/**
 * OpenGL 抽象层接口。
 * <p>
 * 提供平台无关的 OpenGL 操作封装，便于在不同 OpenGL 实现之间切换。
 */
public interface GLAbstraction extends Disposable {

    /**
     * {@code GL_FRAMEBUFFER_COMPLETE} 的枚举值。
     *
     * <p>放在接口上是为了让调用方不必为了比较一个状态码而引入 LWJGL 的常量。
     */
    int FRAMEBUFFER_COMPLETE = 0x8CD5;

    /**
     * 初始化 OpenGL 状态。
     */
    void initialize();

    /**
     * 使用指定颜色清除屏幕。
     *
     * @param color 清除颜色
     */
    void clear(Color color);

    /**
     * 设置视口。
     *
     * @param x      视口左下角 x 坐标
     * @param y      视口左下角 y 坐标
     * @param width  视口宽度
     * @param height 视口高度
     */
    void setViewport(int x, int y, int width, int height);

    /**
     * 创建顶点数组对象（VAO）。
     *
     * @return VAO 的 ID
     */
    int createVao();

    /**
     * 创建顶点缓冲对象（VBO）。
     *
     * @return VBO 的 ID
     */
    int createVbo();

    /**
     * 绑定顶点数组对象。
     *
     * @param vao VAO 的 ID
     */
    void bindVao(int vao);

    /**
     * 绑定顶点缓冲对象。
     *
     * @param vbo VBO 的 ID
     */
    void bindVbo(int vbo);

    /**
     * 上传浮点数组数据到当前绑定的 VBO。
     *
     * @param data 要上传的浮点数据
     */
    void uploadVboData(float[] data);

    /**
     * 上传整数数组数据到当前绑定的 VBO。
     *
     * @param data 要上传的整数数据
     */
    void uploadVboData(int[] data);

    /**
     * 上传字节数据到当前绑定的 VBO。
     *
     * <p>与浮点/整数重载不同，本方法通常用于直接上传已经打包好的顶点字节，
     * 上传量为 {@code data} 的剩余字节数（{@code data.remaining()}）。
     *
     * @param data 要上传的字节数据
     */
    void uploadVboBytes(ByteBuffer data);

    /**
     * 把数据写到 VBO 的指定字节偏移处，<strong>不重新分配缓冲</strong>。
     *
     * <p><strong>前置条件：目标 VBO 的容量必须已经够大。</strong>本方法不扩容——
     * {@code VertexBuffer.grow()} 是"删旧建新"，而扩容会让 VAO 里记录的数据缓冲绑定失效
     * （见 {@code RenderBatch.configureVaoAttributes} 里的说明）。因此调用方必须在创建时
     * 就定死容量，运行期永不增长。
     *
     * <p>越界写入是未定义行为：驱动可能报错，也可能静默损坏别的数据。
     *
     * @param offsetBytes 相对缓冲起点的字节偏移，必须 ≥ 0
     * @param data        数据，position 为 0、limit 为有效字节数
     */
    void uploadVboSubData(int offsetBytes, ByteBuffer data);

    /**
     * 设置某个顶点属性的实例除数。
     *
     * <p>0 = 每顶点取一次（默认），1 = 每实例取一次。图表的数据缓冲靠它把
     * "每个线段一份的两个端点 y 值"供给每个实例。
     *
     * @param index   顶点属性位置
     * @param divisor 除数，必须 ≥ 0
     */
    void setVertexAttribDivisor(int index, int divisor);

    /**
     * 实例化绘制，并指定<strong>实例属性的起始实例号</strong>。
     *
     * <p><strong>为什么不能只用 {@code glDrawArraysInstanced}</strong>：实例属性是按
     * {@code gl_InstanceID} 取的，而它<strong>每次都从 0 开始</strong>。环形缓冲里
     * "环绕点之后那一小段"的物理槽位不从 0 开始，普通版本没有任何办法把属性偏移过去——
     * 于是会取到错误的实例数据，<strong>而且不报错</strong>，只会画出一条乱线。
     * {@code baseInstance} 正是补这个偏移用的。
     *
     * @param mode          图元类型（如 {@code GL_TRIANGLE_STRIP}）
     * @param first         顶点数组的起始下标
     * @param count         顶点数
     * @param instanceCount 实例数
     * @param baseInstance  实例属性的起始实例号
     */
    void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                         int instanceCount, int baseInstance);

    /**
     * 删除顶点数组对象。
     *
     * @param vao VAO 的 ID
     */
    void deleteVao(int vao);

    /**
     * 删除顶点缓冲对象。
     *
     * @param vbo VBO 的 ID
     */
    void deleteVbo(int vbo);

    /**
     * 创建一个通用缓冲对象。
     *
     * <p>与 {@link #createVbo()} 分开，是因为用途不同：那个固定绑 {@code GL_ARRAY_BUFFER}
     * 当顶点缓冲用，这个可以绑 {@code GL_SHADER_STORAGE_BUFFER} 给 compute 用。
     *
     * <p><strong>一个缓冲对象可以同时绑到多个靶子上</strong>——本项目的 FFT 正是靠这一点
     * 让 compute 写的缓冲直接被实例属性读走，不需要任何 GPU 侧拷贝。
     *
     * <p><strong>但"不拷贝"不等于"不用同步"。</strong>compute 写完到顶点属性读走之间
     * <strong>仍必须插一次 {@code glMemoryBarrier}</strong>
     * （{@code gpu/ComputeShader.memoryBarrier()} 用的是 {@code GL_ALL_BARRIER_BITS}，够用）。
     * 漏掉它的表现是<b>读到旧值</b>——<strong>数值错，而不会报任何 GL 错误</strong>，
     * 正是本仓库最警惕的那种失败。
     *
     * <p>缓冲的同步<strong>不属于本抽象</strong>：它跨越"compute 写"与"绘制读"两个 pass，
     * 由调用方按自己的 pass 结构安排。
     *
     * @return 缓冲对象的名字
     */
    int createBuffer();

    /** 把缓冲绑到 {@code GL_SHADER_STORAGE_BUFFER} 靶子。 */
    void bindShaderStorageBuffer(int buffer);

    /**
     * 为当前绑定的缓冲分配存储。
     *
     * <p>用法固定为 {@code GL_DYNAMIC_COPY}（compute 写、compute 读）。
     *
     * @param sizeBytes 字节数
     */
    void allocateBufferStorage(long sizeBytes);

    /**
     * 写到当前绑定的缓冲的指定偏移处，<strong>不重新分配</strong>。
     *
     * <p>与 {@code uploadVboSubData} 同理：越界写入是未定义行为。
     */
    void uploadBufferSubData(long offsetBytes, ByteBuffer data);

    /**
     * 把缓冲绑到某个 SSBO 绑定点（{@code glBindBufferBase}）。
     *
     * @param bindingIndex 着色器里 {@code layout(std430, binding = N)} 的那个 N
     */
    void bindBufferBase(int bindingIndex, int buffer);

    /** 删除缓冲对象。重复删除同一名字是未定义行为，调用方负责只删一次。 */
    void deleteBuffer(int buffer);

    /**
     * 执行数组绘制调用。
     *
     * @param mode  图元类型（如 GL_TRIANGLES）
     * @param offset 起始索引
     * @param count  顶点数量
     */
    void drawArrays(int mode, int offset, int count);

    /**
     * 执行索引绘制调用。
     *
     * @param mode  图元类型（如 GL_TRIANGLES）
     * @param count 索引数量
     */
    void drawElements(int mode, int count);

    /**
     * 启用混合。
     */
    void enableBlend();

    /**
     * 禁用混合。
     */
    void disableBlend();

    /**
     * 设置混合函数。
     *
     * @param srcFactor 源混合因子
     * @param dstFactor 目标混合因子
     */
    void setBlendFunc(int srcFactor, int dstFactor);

    /**
     * 创建着色器程序。
     *
     * @param vertexSource   顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 着色器程序
     */
    ShaderProgram createShader(String vertexSource, String fragmentSource);

    /**
     * 创建纹理。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @param pixels 像素数据，按项目统一的 {@code 0xAARRGGBB} 格式传入；
     *                实现负责转换成 OpenGL 所需的 RGBA 字节序
     * @return 纹理的 ID
     */
    int createTexture(int width, int height, int[] pixels);

    /**
     * 创建一张 {@code GL_R8} 单通道归一化纹理，内容未初始化。
     *
     * <p>与拾取用的 {@code GL_R32UI} 只差两个字母，但语义相反：
     * {@code R8} 是<strong>归一化</strong>格式，可以用 {@code GL_LINEAR} 过滤
     * （SDF 正需要靠插值得到平滑边缘）；{@code R8UI} 是<strong>整数</strong>格式，
     * 必须用 {@code GL_NEAREST}。混用不会报错，只会得到全糊或全锯齿的画面。
     *
     * @param width  宽度（像素）
     * @param height 高度（像素）
     * @return 纹理 ID
     */
    int createR8Texture(int width, int height);

    /**
     * 把一块 8 位单通道数据上传到纹理的指定矩形区域。
     *
     * <p><strong>实现必须处理 {@code GL_UNPACK_ALIGNMENT}</strong>：它的默认值是 4，
     * 而单通道每行只有 {@code width} 个字节，{@code width} 不是 4 的倍数时
     * GL 会按 4 字节对齐去读，<strong>从第二行起整行错位</strong>，
     * 表现为字形被斜切。上传前后必须设/恢复该状态。
     *
     * @param texture 目标纹理
     * @param x       目标矩形左边缘
     * @param y       目标矩形上边缘
     * @param width   矩形宽度
     * @param height  矩形高度
     * @param pixels  数据，长度必须为 {@code width * height}，按行存储
     */
    void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels);

    /**
     * 创建帧缓冲对象（FBO）。
     *
     * @return FBO 的 ID
     */
    int createFramebuffer();

    /**
     * 绑定帧缓冲。0 表示默认帧缓冲。
     *
     * @param framebuffer FBO 的 ID
     */
    void bindFramebuffer(int framebuffer);

    /**
     * 删除帧缓冲对象。
     *
     * @param framebuffer FBO 的 ID
     */
    void deleteFramebuffer(int framebuffer);

    /**
     * 返回当前绑定的帧缓冲 ID。
     *
     * <p>openglfx 渲染到它<strong>自己的</strong> FBO，因此正常运行时这个值通常<strong>非 0</strong>。
     * 任何临时切换帧缓冲的操作都必须先取这个值、事后再恢复回去。
     *
     * @return 当前绑定的帧缓冲 ID
     */
    int currentFramebufferBinding();

    /**
     * 创建一张 {@code R32UI} 整数纹理。
     *
     * <p>整数纹理的过滤器<strong>必须</strong>是 {@code GL_NEAREST}：{@code GL_LINEAR}
     * 对整数纹理非法。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @return 纹理的 ID
     */
    int createIntegerTexture(int width, int height);

    /**
     * 删除纹理。
     *
     * @param texture 纹理的 ID
     */
    void deleteTexture(int texture);

    /**
     * 把一张 2D 纹理挂到<strong>当前绑定的</strong>帧缓冲的 0 号颜色附件上。
     *
     * @param texture 纹理的 ID
     */
    void attachTextureToColor0(int texture);

    /**
     * 返回当前绑定的帧缓冲的完整性状态，等于 {@link #FRAMEBUFFER_COMPLETE} 表示可用。
     *
     * @return 帧缓冲状态码
     */
    int framebufferStatus();

    /**
     * 用一个整数清除值清空当前绑定的帧缓冲的 0 号颜色附件。
     *
     * <p>整数附件<strong>不能</strong>用 {@code glClearColor} + {@code glClear}：
     * 那对整数附件是未定义行为。本方法内部走 {@code glClearBufferuiv}。
     *
     * @param value 清除值（写进 R 通道，其余通道为 0）
     */
    void clearIntegerColor(int value);

    /**
     * 返回裁剪测试是否已启用。
     *
     * @return 已启用时为 true
     */
    boolean isScissorEnabled();

    /**
     * 启用或关闭裁剪测试。
     *
     * @param enabled 是否启用
     */
    void setScissorEnabled(boolean enabled);

    /**
     * 读回一个无符号整数像素。
     *
     * <p>坐标是 <strong>GL 约定</strong>：原点在帧缓冲左下角、y 向上。
     * 调用方负责从「原点左上、y 向下」的用户坐标换算过来。
     *
     * @param x 像素 x（GL 约定）
     * @param y 像素 y（GL 约定）
     * @return 该像素的整数值
     */
    int readUnsignedIntPixel(int x, int y);

    /**
     * 读回一块无符号整数像素。
     *
     * <p>坐标同样是 GL 约定。读回的行序<strong>自下而上</strong>，
     * 即 {@code out} 的第 0 行对应 GL 坐标系里最下面那一行。
     *
     * @param x      左上角 x（GL 约定）
     * @param y      左上角 y（GL 约定）
     * @param width  宽度
     * @param height 高度
     * @param out    结果数组，长度至少为 {@code width * height}
     */
    void readUnsignedIntPixels(int x, int y, int width, int height, int[] out);

    /** 创建一个供 {@code glReadPixels} 异步写入的像素包缓冲（PBO）。 */
    int createPixelPackBuffer();

    /** 删除像素包缓冲。 */
    void deletePixelPackBuffer(int buffer);

    /**
     * 提交一个 32 位无符号整数像素的异步读回到指定 PBO。
     * 坐标使用 GL 约定；调用返回时 GPU 尚可继续执行，结果由 fence 确认后读取。
     */
    void enqueueUnsignedIntPixelRead(int x, int y, int buffer);

    /** 在当前 GL 命令流位置插入 GPU 完成 fence，返回其非零句柄。 */
    long fenceSync();

    /** 不等待地检查 fence 是否已经完成。 */
    boolean isSyncSignaled(long sync);

    /** 删除 fence。 */
    void deleteSync(long sync);

    /**
     * 从已经由 {@link #isSyncSignaled(long)} 确认完成的 PBO 映射并读取一个整数像素。
     */
    int readUnsignedIntPixelFromPixelPackBuffer(int buffer);
}
