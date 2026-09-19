package com.bingbaihanji.jfgl.renderer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取 ID 的分配器与 {@code id → payload} 映射表。
 *
 * <p>纯内存实现，<strong>不做任何 GL 调用</strong>，因此可以脱离窗口单测——
 * 这是拾取子系统里<strong>本期唯一</strong>能被常规单测覆盖的部分。
 * 这句话只描述现状，不构成「其余部分不必写单测」的许可：后续的
 * {@code Framebuffer} / {@code PickBuffer} 只依赖 {@code GLAbstraction} 这个
 * <strong>接口</strong>，用假实现就能做到零 GL 上下文的单测。
 *
 * <h2>ID 空间的规则</h2>
 * <ol>
 *   <li><strong>0 永不分配</strong>，恒定保留给「什么都没命中」。分配从 1 开始。</li>
 *   <li><strong>绝不环绕</strong>。用尽到 {@link #maxId} 时抛
 *       {@link IllegalStateException}。环绕意味着新对象复用了一个仍被引用的 ID，
 *       表现为<strong>拾取到毫不相干的对象</strong>，而且是概率极低的偶发，宁可炸。</li>
 *   <li><strong>回收复用</strong>：{@link #unregister} 把 ID 放进空闲表（LIFO），
 *       下次 {@link #register} 优先复用。否则长生命周期应用反复注册/注销会把 ID 空间磨光。</li>
 * </ol>
 *
 * <h2>生命周期由调用方负责</h2>
 * <p>本类持有 payload 的<strong>强引用</strong>，调用方必须 {@link #unregister} 或
 * {@link #clear}。不用弱引用：被回收后表现为「什么都拾取不到」，又是一个静默失败。
 *
 * <h2>用法约定</h2>
 * <p>注册发生在<strong>数据变化时，不是每帧</strong>。本表是跨帧稳定的映射，
 * 每帧重新注册会耗尽 ID 空间并让 {@code unregister} 的语义失效。
 *
 * <h2>线程安全</h2>
 * <p>注册（随数据变化，通常发生在 JavaFX 线程）与解析（拾取查询，在 GL 线程）
 * 天然分处两个线程，所以本类<strong>必须</strong>线程安全，而不是把「只能在 GL 线程
 * 调用」写进文档了事——那条约束与自然用法相悖，迟早被违反，而违反的表现是
 * {@link #resolve} 静默返回 {@code null}（表现为「什么都没拾取到」），
 * 又是一个不崩的静默失败。
 *
 * <p>分工：{@code payloads} 用 {@link ConcurrentHashMap}，使 {@link #resolve}
 * 保持<strong>无锁</strong>——{@code pickRect} 要逐像素解析，这是查询热路径；
 * ID 分配状态（{@code freeIds} / {@code nextId}）由 {@code lock} 护住，
 * 整个 {@link #register} / {@link #unregister} / {@link #clear} 在同一把锁内完成
 * （分配是「取空闲表 → 递增计数器 → 写入映射」的复合操作，
 * 单靠并发容器不足以保证原子性）。按上面的用法约定，注册不是热路径，
 * 锁的开销无关紧要。
 */
public final class PickRegistry {

    /**
     * 默认 ID 上界。
     *
     * <p>取 {@code Integer.MAX_VALUE - 1} 而不是 {@code MAX_VALUE}：上界是「含」的，
     * 配 {@code nextId++} 用的话，{@code MAX_VALUE} 那一格自增后会溢出成负数，
     * 而 {@code nextId > maxId} 对负数为假——本类承诺的「绝不环绕」就破了。
     * 留一格余量让这条不变式在算术上真的成立。
     *
     * <p>顺带一提：这个边界在实际中不可达。每条登记要占一个 {@code HashMap} 条目
     * 加一个装箱的 {@code Integer}，约 48 字节，2^31 条第 100 GB 量级——
     * 内存会先炸。但「不可达」和「不成立」是两回事，而修正的代价是一个常量。
     */
    private static final int DEFAULT_MAX_ID = Integer.MAX_VALUE - 1;

    /**
     * {@code null} payload 的占位符。
     *
     * <p>{@link ConcurrentHashMap} <strong>不接受 {@code null} 值</strong>——这是它与
     * {@code HashMap} 的一处语义差异，{@code put(id, null)} 会直接抛
     * {@link NullPointerException}。而本类承诺 payload 可为 {@code null}
     * （见 {@link #register}），且 {@link #unregister} 的判据是「条目在不在」
     * 而不是「值是不是 null」。两者一冲突，用私有哨兵占位：入口把 {@code null}
     * 换成它，出口再翻译回 {@code null}。
     *
     * <p>哨兵不出本类，调用方永远看不到它。<strong>不能</strong>改用
     * {@code Collections.synchronizedMap} 绕开——那会把锁加回 {@link #resolve}，
     * 而 {@link #resolve} 是 {@code pickRect} 逐像素解析的热路径，无锁正是本类的要求。
     */
    private static final Object NULL_PAYLOAD = new Object();

    /**
     * 已分配的 ID → payload。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 {@code HashMap}：{@link #resolve}
     * 是查询热路径（{@code pickRect} 逐像素解析），必须无锁且不被撕裂。
     * 分配侧的原子性由 {@link #lock} 负责，不靠这个容器。
     */
    private final Map<Integer, Object> payloads = new ConcurrentHashMap<>();

    /** 已回收、可供复用的 ID（LIFO）。由 {@link #lock} 护住。 */
    private final Deque<Integer> freeIds = new ArrayDeque<>();

    /** 下一个待分配的 ID。由 {@link #lock} 护住。 */
    private int nextId = 1;

    /** ID 上界（含）。 */
    private final int maxId;

    /**
     * 护住 ID 分配状态的锁。
     *
     * <p>只锁「取空闲表 → 递增计数器 → 写入映射」这个复合操作。
     * 不加在 {@link #resolve} 上——那是热路径，靠 {@link ConcurrentHashMap} 本身就够。
     */
    private final Object lock = new Object();

    /** 用默认上界创建注册表。 */
    public PickRegistry() {
        this(DEFAULT_MAX_ID);
    }

    /**
     * 指定 ID 上界创建注册表，仅供测试使用。
     *
     * @param maxId ID 上界（含），小于 1 时按 1 处理
     */
    PickRegistry(int maxId) {
        this.maxId = Math.max(1, maxId);
    }

    /**
     * 注册一个 payload 并返回它的拾取 ID。
     *
     * @param payload 命中时要取回的对象，可为 {@code null}
     * @return 非零的拾取 ID
     * @throws IllegalStateException ID 空间耗尽时
     */
    public int register(Object payload) {
        synchronized (lock) {
            int id;
            if (!freeIds.isEmpty()) {
                id = freeIds.pop();
            } else {
                if (nextId > maxId) {
                    throw new IllegalStateException(
                            "拾取 ID 已耗尽（上界 " + maxId + "）：请检查是否在每帧重复注册，"
                                    + "或对不再需要的对象调用 unregister");
                }
                id = nextId++;
            }
            payloads.put(id, payload == null ? NULL_PAYLOAD : payload);
            return id;
        }
    }

    /**
     * 注销一个 ID 并把它归还到空闲表。注销未注册的 ID 是无副作用的。
     *
     * @param id 要注销的 ID
     */
    public void unregister(int id) {
        if (id == 0) {
            // 双保险，不是唯一防线：0 从不会被 register 分配，所以下面那道
            // containsKey 守卫本身就让 unregister(0) 成了 no-op。
            // 留着这行是为了把「0 是保留值」的意图写在方法开头。
            return;
        }
        // 守卫是承重的，不能省：没有它，unregister(999) 会把一个从未分配过的 ID
        // 塞进空闲表，重复注销同一个 ID 会把它压入两次——此后两次 register 弹出
        // 同一个 ID，两个活对象共用一个拾取 ID，payloads.put 静默覆盖前者。
        //
        // 判据只能是 containsKey，且必须「先判后删」：payload 允许为 null，
        // 单看 remove() 的返回值（或 resolve(id) != null）无法区分「注册过一个空对象」
        // 与「根本没注册过」，那个 ID 就永远回收不了。也不能写成
        // remove() != null || containsKey()——remove() 已经先把条目删掉，
        // 后面的 containsKey() 恒为 false。
        synchronized (lock) {
            if (payloads.containsKey(id)) {
                payloads.remove(id);
                freeIds.push(id);
            }
        }
    }

    /**
     * 解析 ID 对应的 payload。
     *
     * @param id 拾取 ID
     * @return 对应的 payload；未注册时返回 {@code null}
     */
    public Object resolve(int id) {
        Object payload = payloads.get(id);
        return payload == NULL_PAYLOAD ? null : payload;
    }

    /**
     * 清空全部映射与空闲表，并把 ID 计数归零。
     *
     * <p><strong>警告：此前发出的所有 ID 立刻失效，并且会被重新发给别的 payload。</strong>
     * 计数器归 1 之后，下一次 {@link #register} 就会发出 1——那很可能正是调用方
     * 手里缓存着的某个旧 ID。它现在指向一个毫不相干的对象，而且 {@code resolve}
     * 不会报错，只会安静地返回错的对象。
     *
     * <p>这与「绝不环绕」的承诺并不矛盾：环绕是分配器<em>自己</em>把仍活着的 ID
     * 复用出去，而 {@code clear} 是调用方主动宣告旧映射整体作废。因此调用方在
     * {@code clear()} 之后<strong>必须丢弃手上缓存的每一个 ID</strong>，
     * 不能拿旧 ID 去 {@link #resolve}，也不能拿它去 {@link #unregister}。
     */
    public void clear() {
        synchronized (lock) {
            payloads.clear();
            freeIds.clear();
            nextId = 1;
        }
    }

    /**
     * 返回当前已注册的 ID 数量。
     *
     * @return 已注册数量
     */
    public int size() {
        return payloads.size();
    }
}
