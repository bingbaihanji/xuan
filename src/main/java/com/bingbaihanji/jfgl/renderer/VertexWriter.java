package com.bingbaihanji.jfgl.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯 CPU 的顶点收集器：往一个直接缓冲区追加顶点，并把连续的绘制拆成绘制命令。
 *
 * <p>此类<strong>不进行任何 OpenGL 调用</strong>，可在无 GL 上下文的环境下单元测试。
 * 缓冲区使用直接内存并保持小端序，因此可被 {@code glBufferData} 直接消费，无需再复制一次。
 *
 * <p>合批规则：只有<strong>相邻</strong>且状态（纹理、裁剪）相同的绘制才会合并为一条命令。
 * 不允许跨命令合并，因为 2D 中绘制顺序即 z 序。
 *
 * <p>实现上，当前命令在状态切换时才「定稿」写入 {@link #finishedCommands}；最后一条尚未定稿的
 * 命令在读取时按「已写顶点数 − 起始顶点索引」现算。这样每个顶点只做一次缓冲区写入，
 * 不产生任何中间对象。
 *
 * <h2>消费方契约（帧中途 flush）</h2>
 *
 * <p><strong>着色器与 VBO 一侧的 {@code RenderBatch} 是这条契约的读者</strong>：
 * <ol>
 *   <li>必须在<strong>每个图元之前</strong>检查 {@link #isFlushRequested()}。既不是每帧一次，
 *       也不能在图元中间检查：标志置位后缓冲区只剩 {@link #PRIMITIVE_RESERVE_VERTICES} 个顶点的
 *       余量，恰好够写完一个完整图元，一旦开始写某个图元就必须把它写完。</li>
 *   <li>读到标志置位时立即提交已收集的顶点：{@link #buffer()} 取出当前内容上传，
 *       然后调用 {@link #reset()} 开始新的一批。{@link #reset()} 是<strong>唯一</strong>能退出
 *       「已请求 flush」状态的方法，它同时清掉标志。</li>
 *   <li>{@link #reset()} 会把写入器带回「尚未设置状态」的初始条件，因此提交完继续画之前
 *       <strong>必须重新调用 {@link #setState}</strong>；否则下一次 {@link #vertex} 会抛
 *       {@code IllegalStateException("写入顶点前必须先调用 setState()")}。</li>
 *   <li>忽略标志继续写不会安静地绕过去：缓冲区写满之后 {@link #vertex} 会抛出点名补救办法的
 *       {@link IllegalStateException}，而不是一个消息为 {@code null} 的越界异常。</li>
 * </ol>
 */
public final class VertexWriter {

    /** 顶点数硬上限，约 24 MB。 */
    public static final int MAX_VERTEX_CAPACITY = 1 << 20;

    /**
     * 单个图元最多占用的顶点数（四边形展开为两个三角形，共 6 个）。
     * 容量阈值必须为最大的单个图元预留这么多顶点。
     */
    public static final int PRIMITIVE_RESERVE_VERTICES = 6;

    /** {@link #currentFirstVertex} 的哨兵值，表示当前没有未定稿的命令（即状态尚未设置）。 */
    private static final int NO_COMMAND = -1;

    /** 已定稿的命令，顺序即提交顺序。 */
    private final List<DrawCommand> finishedCommands = new ArrayList<>();

    /** 顶点缓冲区（直接内存、小端序）。 */
    private ByteBuffer buffer;

    /** 已写入的顶点数。 */
    private int vertexCount = 0;

    /** 缓冲区可容纳的顶点数。 */
    private int capacityVertices;

    /** 顶点数上限；生产环境为 {@link #MAX_VERTEX_CAPACITY}，测试可调小以便覆盖兜底路径。 */
    private final int maxVertexCapacity;

    /**
     * 达到此顶点数时触发扩容或帧中途 flush（置位 {@link #flushRequested}）。
     * <p>
     * 取值比容量少 {@link #PRIMITIVE_RESERVE_VERTICES} + 1：多减的那个 1 是触发置位的本次写入
     * 自身消耗的顶点，这样置位之后仍恰好写得下整个图元。
     * <p>
     * 消费方要遵守的契约写在 {@link #isFlushRequested()} 与类说明里。
     */
    private int flushThresholdVertices;

    /** 本帧是否已发生过帧中途 flush 请求。 */
    private boolean flushRequested = false;

    /** 当前这批顶点里是否出现过非零的拾取 ID。{@link #reset()} 时复位。 */
    private boolean hasPickable = false;

    /** 当前状态的材质。 */
    private Material material = Material.COLOR;

    /** 当前状态的纹理 ID。 */
    private int textureId;

    /** 当前状态的裁剪矩形左边缘 x。 */
    private int scissorX;

    /** 当前状态的裁剪矩形上边缘 y（y 向下，与 Gc 的用户坐标一致；glScissor 的换算见 DrawCommand）。 */
    private int scissorY;

    /** 当前状态的裁剪矩形宽度。 */
    private int scissorWidth;

    /** 当前状态的裁剪矩形高度。 */
    private int scissorHeight;

    /** 当前（尚未定稿）命令的起始顶点索引；{@link #NO_COMMAND} 表示状态尚未设置。 */
    private int currentFirstVertex = NO_COMMAND;

    /**
     * 创建一个顶点收集器，上限为 {@link #MAX_VERTEX_CAPACITY}。
     *
     * @param initialVertexCapacity 初始顶点容量
     */
    public VertexWriter(int initialVertexCapacity) {
        this(initialVertexCapacity, MAX_VERTEX_CAPACITY);
    }

    /**
     * 指定上限构造，仅供测试使用。
     *
     * @param initialVertexCapacity 初始顶点容量
     * @param maxVertexCapacity     顶点数上限
     */
    VertexWriter(int initialVertexCapacity, int maxVertexCapacity) {
        this.maxVertexCapacity = Math.max(1, maxVertexCapacity);
        this.capacityVertices = Math.min(Math.max(1, initialVertexCapacity), this.maxVertexCapacity);
        this.buffer = allocate(this.capacityVertices);
        this.flushThresholdVertices = this.capacityVertices - PRIMITIVE_RESERVE_VERTICES - 1;
    }

    /**
     * 设置当前状态为纯色材质。等价于
     * {@code setState(Material.COLOR, textureId, ...)}。
     *
     * <p>保留这个重载是为了让纯色绘制的调用点不必每处都写一遍
     * {@code Material.COLOR}——它同时把"纯色绘制用 COLOR"这条约定固定在一个地方，
     * 而不是散落在几十个调用点上。
     *
     * @param textureId     纹理 ID
     * @param scissorX      裁剪矩形左边缘 x
     * @param scissorY      裁剪矩形上边缘 y（y 向下；glScissor 的 y 换算见 {@link DrawCommand}）
     * @param scissorWidth  裁剪矩形宽度
     * @param scissorHeight 裁剪矩形高度
     */
    public void setState(int textureId, int scissorX, int scissorY,
                         int scissorWidth, int scissorHeight) {
        setState(Material.COLOR, textureId, scissorX, scissorY, scissorWidth, scissorHeight);
    }

    /**
     * 设置当前状态。与上一条命令状态不同时会结束当前命令。
     *
     * <p><strong>材质是合批判据的一部分</strong>：只有相邻且材质、纹理、裁剪都相同的
     * 绘制才会合并。漏判的表现是文本被当成纯色画（采样到 1×1 白色纹理，
     * 整片文字糊成方块），而且不报错。
     *
     * @param material      材质，决定用哪个片段着色器
     * @param textureId     纹理 ID
     * @param scissorX      裁剪矩形左边缘 x
     * @param scissorY      裁剪矩形上边缘 y（y 向下；glScissor 的 y 换算见 {@link DrawCommand}）
     * @param scissorWidth  裁剪矩形宽度
     * @param scissorHeight 裁剪矩形高度
     */
    public void setState(Material material, int textureId, int scissorX, int scissorY,
                         int scissorWidth, int scissorHeight) {
        if (currentFirstVertex != NO_COMMAND
                && this.material == material
                && this.textureId == textureId
                && this.scissorX == scissorX && this.scissorY == scissorY
                && this.scissorWidth == scissorWidth && this.scissorHeight == scissorHeight) {
            return;
        }
        // 状态变了：把上一条命令定稿（顶点数 = 已写顶点数 − 它的起始顶点索引）。
        // 只有相邻且同状态的绘制才会落进同一条命令，绝不跨命令合并——绘制顺序即 z 序。
        sealCurrentCommand();

        this.material = material;
        this.textureId = textureId;
        this.scissorX = scissorX;
        this.scissorY = scissorY;
        this.scissorWidth = scissorWidth;
        this.scissorHeight = scissorHeight;
        this.currentFirstVertex = vertexCount;
    }

    /**
     * 追加一个顶点。必须先调用 {@link #setState}。
     *
     * @param x                位置 x（已烘焙到 NDC）
     * @param y                位置 y（已烘焙到 NDC）
     * @param u                纹理坐标 u
     * @param v                纹理坐标 v
     * @param premultipliedRgba 预乘 alpha 后的 RGBA 颜色
     * @param id               拾取 ID
     * @throws IllegalStateException 尚未调用 {@link #setState} 时；
     *                               或缓冲区已写满、消费方始终没有执行帧中途 flush 时
     */
    public void vertex(float x, float y, float u, float v, int premultipliedRgba, int id) {
        if (currentFirstVertex == NO_COMMAND) {
            throw new IllegalStateException("写入顶点前必须先调用 setState()");
        }
        if (vertexCount >= flushThresholdVertices) {
            grow();
        }
        // 缓冲区已经写满 = 消费方没有遵守「每个图元之前检查 isFlushRequested()」的契约
        // （grow() 在容量耗尽时正是置位该标志的地方，所以这个状态下标志必然是 true）。
        // 旧行为是让绝对定位写入抛一个 msg 为 null 的 IndexOutOfBoundsException——
        // 帧中途一个没有消息的越界异常根本无从定位；这里换成点名补救办法的异常。
        if (vertexCount >= capacityVertices) {
            throw new IllegalStateException(
                    "顶点缓冲已满：必须在每个图元之前检查 isFlushRequested() 并执行帧中途 flush");
        }
        int offset = vertexCount * VertexFormat.STRIDE_BYTES;
        buffer.putFloat(offset, x);
        buffer.putFloat(offset + 4, y);
        buffer.putFloat(offset + 8, u);
        buffer.putFloat(offset + 12, v);
        buffer.putInt(offset + 16, premultipliedRgba);
        buffer.putInt(offset + 20, id);
        if (id != 0) {
            hasPickable = true;
        }
        vertexCount++;
    }

    /**
     * 追加一个四边形（两个三角形，顶点顺序为 0-1-2 与 0-2-3，共用边 0-2）。
     * <p>
     * 两个三角形绕向相同，且不依赖背面剔除，因此与写成 2-3-0 的等价；此处从角点 0 起算，
     * 使第 6 个顶点落在左下角。四个角点按左上、右上、右下、左下给出；UV 的两个角为 (u0,v0) 与 (u1,v1)。
     *
     * @param x0                左上角 x
     * @param y0                左上角 y
     * @param x1                右上角 x
     * @param y1                右上角 y
     * @param x2                右下角 x
     * @param y2                右下角 y
     * @param x3                左下角 x
     * @param y3                左下角 y
     * @param u0                纹理坐标左边界
     * @param v0                纹理坐标上边界
     * @param u1                纹理坐标右边界
     * @param v1                纹理坐标下边界
     * @param premultipliedRgba 预乘 alpha 后的 RGBA 颜色
     * @param id                拾取 ID
     */
    public void quad(float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3,
                     float u0, float v0, float u1, float v1,
                     int premultipliedRgba, int id) {
        vertex(x0, y0, u0, v0, premultipliedRgba, id);
        vertex(x1, y1, u1, v0, premultipliedRgba, id);
        vertex(x2, y2, u1, v1, premultipliedRgba, id);
        // 第二个三角形同样以角点 0 起算（0-2-3），第 6 个顶点即左下角。
        vertex(x0, y0, u0, v0, premultipliedRgba, id);
        vertex(x2, y2, u1, v1, premultipliedRgba, id);
        vertex(x3, y3, u0, v1, premultipliedRgba, id);
    }

    /**
     * 清空所有顶点与命令，保留缓冲区容量。
     * <p>
     * 这是<strong>唯一</strong>能退出「已请求帧中途 flush」状态的方法：它同时清掉
     * {@link #flushRequested} 标志（因此不需要、也没有单独的清除方法）。
     * <p>
     * 清空后状态视为未设置，需重新调用 {@link #setState} 才能继续写入顶点——
     * 帧中途 flush 之后继续画下一个图元时最容易忘的就是这一步，忘了会在下一次
     * {@link #vertex} 抛 {@code IllegalStateException("写入顶点前必须先调用 setState()")}。
     * <p>
     * 内部缓冲区的 limit 不在此处恢复，也无需恢复：{@link #buffer()} 不再改动内部缓冲区。
     */
    public void reset() {
        vertexCount = 0;
        finishedCommands.clear();
        currentFirstVertex = NO_COMMAND;
        flushRequested = false;
        hasPickable = false;
    }

    /**
     * 返回已写入的顶点数。
     *
     * @return 顶点数
     */
    public int vertexCount() {
        return vertexCount;
    }

    /**
     * 返回命令条数（含尚未定稿的最后一条）。
     *
     * @return 命令条数
     */
    public int commandCount() {
        return finishedCommands.size() + (currentFirstVertex == NO_COMMAND ? 0 : 1);
    }

    /**
     * 返回第 {@code i} 条命令。
     *
     * @param i 命令索引
     * @return 该条命令
     * @throws IndexOutOfBoundsException 索引越界时
     */
    public DrawCommand command(int i) {
        int finished = finishedCommands.size();
        if (i < finished) {
            return finishedCommands.get(i);
        }
        if (i == finished && currentFirstVertex != NO_COMMAND) {
            return currentCommand();
        }
        throw new IndexOutOfBoundsException("命令索引越界: " + i + "，共 " + commandCount() + " 条");
    }

    /**
     * 返回本帧已写入的全部命令（只读视图）。最后一条命令的顶点数按当前已写顶点数现算。
     *
     * @return 全部命令
     */
    public List<DrawCommand> commands() {
        if (currentFirstVertex == NO_COMMAND) {
            return Collections.unmodifiableList(finishedCommands);
        }
        List<DrawCommand> all = new ArrayList<>(finishedCommands.size() + 1);
        all.addAll(finishedCommands);
        all.add(currentCommand());
        return Collections.unmodifiableList(all);
    }

    /**
     * 返回顶点缓冲区的一张只读视图（{@link ByteBuffer#duplicate()} 副本），
     * position 为 0，limit 为已写入字节数（{@code vertexCount * }{@value VertexFormat#STRIDE_BYTES}），
     * 字节序为小端，可直接交给 {@code glBufferData}。
     *
     * <p><strong>不变量：limit == capacity，且只有副本会被收窄。</strong>
     * 返回的是副本，内部缓冲区的 position/limit 一律不受影响，因此本方法可以被反复调用
     * （每一帧、或帧中途 flush 时），也不会给下一次写入留下任何越界隐患：
     * 绝对定位写入 {@code putFloat(int,float)} 是按 <strong>limit</strong> 而非 capacity 判定越界的，
     * 若像早期实现那样直接返回内部缓冲区并收窄它的 limit，下一帧只要顶点数超过本帧，
     * 第一次写入就会抛 {@code IndexOutOfBoundsException}。
     *
     * <p><strong>返回值不得保留</strong>：副本与内部缓冲区共享同一段直接内存，
     * 且下一次 {@link #grow()} 会换一块新分配的内存，旧副本随即指向废弃的数据。
     * 消费方应在拿到后立即上传，不要缓存。
     *
     * @return 顶点缓冲区的小端副本，position 为 0、limit 为已写入字节数
     */
    public ByteBuffer buffer() {
        ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        view.position(0);
        view.limit(vertexCount * VertexFormat.STRIDE_BYTES);
        return view;
    }

    /**
     * 返回当前顶点容量。
     *
     * @return 可容纳的顶点数
     */
    public int vertexCapacity() {
        return capacityVertices;
    }

    /**
     * 返回是否因达到容量上限而请求了帧中途 flush。
     *
     * <p><strong>消费方契约</strong>：{@code RenderBatch} 必须在<strong>每个图元之前</strong>
     * 调用本方法——既不是每帧一次，也不能在图元中间调用。标志置位后缓冲区只剩
     * {@link #PRIMITIVE_RESERVE_VERTICES} 个顶点的余量，恰好够写完一个完整图元；
     * 一旦开始写某个图元就必须把它写完。
     *
     * <p>读到 {@code true} 时的完整处理步骤：
     * <ol>
     *   <li>{@link #buffer()} 取出当前内容并提交（上传 + 按 {@link #commands()} 执行 draw call）；</li>
     *   <li>{@link #reset()}——它清空顶点与命令，<strong>并清掉本标志</strong>；
     *       这是退出「已请求 flush」状态的唯一途径，没有单独的清除方法。</li>
     *   <li>{@link #setState}——{@code reset()} 把写入器带回「尚未设置状态」的初始条件，
     *       不重新设状态就写顶点会抛 {@code IllegalStateException("写入顶点前必须先调用 setState()")}。</li>
     * </ol>
     *
     * <p>若消费方忽略标志继续写入，缓冲区写满后 {@link #vertex} 会抛出消息中点明补救办法的
     * {@link IllegalStateException}，不会一直安静地写下去。
     *
     * @return 是否已请求帧中途 flush
     */
    public boolean isFlushRequested() {
        return flushRequested;
    }

    /**
     * 返回当前这批顶点里是否出现过非零的拾取 ID。
     *
     * <p>供 {@code RenderBatch} 决定是否需要渲染 ID pass：整帧都没用到拾取时
     * 连清空带重画都省掉，不用拾取的应用因此零开销。
     *
     * <p>粒度是<strong>每批</strong>而不是每帧——{@link #reset()} 会清掉它，
     * 而 {@code reset()} 正是帧中途 flush 的分批边界。这样每批各自贡献自己的 ID，
     * 谁都不用回放历史。
     *
     * @return 本批是否含可拾取顶点
     */
    public boolean hasPickableVertices() {
        return hasPickable;
    }

    /**
     * 把当前命令定稿并移入 {@link #finishedCommands}，随后清空当前命令。
     */
    private void sealCurrentCommand() {
        if (currentFirstVertex == NO_COMMAND) {
            return;
        }
        finishedCommands.add(currentCommand());
        currentFirstVertex = NO_COMMAND;
    }

    /**
     * 按「已写顶点数 − 起始顶点索引」现算当前命令。
     *
     * @return 当前命令
     */
    private DrawCommand currentCommand() {
        return new DrawCommand(textureId, material, currentFirstVertex,
                vertexCount - currentFirstVertex,
                scissorX, scissorY, scissorWidth, scissorHeight);
    }

    /**
     * 容量翻倍，并保留已写入的顶点数据。若已达到硬上限仍未满足，则改为请求帧中途 flush 并复用已有缓冲区。
     */
    private void grow() {
        if (capacityVertices >= maxVertexCapacity) {
            flushRequested = true;
            return;
        }
        int oldCapacityBytes = buffer.capacity();
        capacityVertices = Math.min(capacityVertices * 2, maxVertexCapacity);
        ByteBuffer old = buffer;
        buffer = allocate(capacityVertices);
        // 拷贝整段旧数据（未写入区域的内容永不被读取）。
        // 内部缓冲区的 limit 恒等于 capacity——buffer() 只返回副本、不碰内部缓冲区，
        // 所以这里无需再像早期实现那样先把 limit 恢复回 capacity 才能搬运。
        // 注意 put(int,ByteBuffer,int,int) 是按源缓冲区的 limit 而非 capacity 校验长度的。
        buffer.put(0, old, 0, Math.min(oldCapacityBytes, buffer.capacity()));
        flushThresholdVertices = capacityVertices - PRIMITIVE_RESERVE_VERTICES - 1;
    }

    /**
     * 分配一块直接内存缓冲区，小端序。
     *
     * @param vertices 可容纳的顶点数
     * @return 新分配的缓冲区
     */
    private static ByteBuffer allocate(int vertices) {
        return ByteBuffer.allocateDirect(vertices * VertexFormat.STRIDE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
    }
}
