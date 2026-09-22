package com.bingbaihanji.jfgl.chartrender;

import java.util.ArrayList;
import java.util.List;

/**
 * 增量上传的字节区间计划。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>它是 ② 的性能主张的度量点</h2>
 * <p>整个 ② 的主张是"<b>每帧只上传新增的点，不是整个窗口</b>"。
 * 这个类算的就是"要传哪几个字节"，而 {@link #totalBytes()} 是那个主张的<b>可观测形式</b>。
 *
 * <p>为什么需要一个可观测形式：画面在"增量上传"与"每帧全量重传"这两种实现下
 * <b>完全一样</b>。只看像素的断言一条都区分不出来。所以最终的像素校验器断言的是
 * "滚动若干帧之后，每帧上传字节数恒等于新增点数 × 4"——与文本校验器里那条
 * "24px 与 192px 的过渡带宽度都是 1px"同类：<b>断言的量就是那件事本身</b>。
 *
 * <h2>每个点 4 字节</h2>
 * <p>缓冲里只存 y 值（float）。线段的两端靠"同一个缓冲、偏移差 4 字节"的两个实例属性拿到，
 * 所以同一个 y 只存一次。缓冲要比环容量多留一个 float 的余量——<b>那个余量不是垃圾</b>：
 * 物理槽位 {@code capacity} 就是槽位 0（环的本质），所以"最后一个槽位上的实例的后继样本"
 * 必然落在槽位 0。见 {@link #mirrorSourceIndex()}。
 *
 * <h2>为什么要把"超出一圈"的部分丢掉</h2>
 * <p>那些样本<b>已经被新数据覆盖了</b>，传了也是写进不再持有它们的槽位。
 * 所以上限是"环里还留着的那些"，而不是"新增了多少"。
 */
public final class SeriesUploadPlan {

    /**
     * {@link #mirrorSourceIndex()} 表示"本次不需要写镜像"时的返回值。
     *
     * <p>它必须是负数：绝对号从 0 开始，0 是一个完全合法的样本号。
     */
    public static final long NO_MIRROR = -1L;

    private final List<Range> ranges;

    private final int totalBytes;

    /** 见 {@link #mirrorSourceIndex()}。 */
    private final long mirrorSourceIndex;

    private SeriesUploadPlan(List<Range> ranges, int totalBytes, long mirrorSourceIndex) {
        this.ranges = ranges;
        this.totalBytes = totalBytes;
        this.mirrorSourceIndex = mirrorSourceIndex;
    }

    /**
     * 计算 {@code uploadedCount} 之后新写入的样本要传哪些字节。
     *
     * @param uploadedCount 上一次上传时的写入总数
     * @param writtenCount  本次的写入总数
     * @param capacity      环容量，<strong>必须是 2 的幂</strong>
     * @return 上传计划
     * @throws IllegalArgumentException 容量不是 2 的幂
     */
    public static SeriesUploadPlan between(long uploadedCount, long writtenCount, int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + capacity);
        }
        long newCount = writtenCount - uploadedCount;
        if (newCount <= 0) {
            return new SeriesUploadPlan(List.of(), 0, NO_MIRROR);
        }
        // 超过一圈的部分已经被覆盖，传了也是白传。
        long effective = Math.min(newCount, capacity);
        long firstWritten = writtenCount - effective;

        int firstSlot = (int) (firstWritten & (capacity - 1));
        int points = (int) effective;
        List<Range> ranges = new ArrayList<>(2);
        if (firstSlot + points <= capacity) {
            ranges.add(new Range(firstSlot * Float.BYTES, points * Float.BYTES));
        } else {
            int headPoints = capacity - firstSlot;
            ranges.add(new Range(firstSlot * Float.BYTES, headPoints * Float.BYTES));
            ranges.add(new Range(0, (points - headPoints) * Float.BYTES));
        }
        return new SeriesUploadPlan(List.copyOf(ranges), points * Float.BYTES,
                mirrorSourceIndex(newCount, writtenCount, capacity));
    }

    /**
     * 计算本次要**镜像**到偏移 {@code capacity * 4} 上的那个样本的绝对号。
     *
     * <h2>为什么缓冲末尾那个 float 不是垃圾</h2>
     * <p>实例属性是"同一个缓冲、偏移差 4 字节"，于是物理槽位 {@code capacity - 1} 上的
     * 那个实例，它的第二端读的是偏移 {@code capacity * 4}。而环的槽位 {@code capacity}
     * <b>就是槽位 0</b>——所以那个位置必须放着"槽位 0 上那个样本"的值。
     *
     * <p><strong>不写它会怎样。</strong>缓冲初始化为全 0、而那个位置从来没有被写过，
     * 于是环写满之后，跨环绕点的那一个实例会画一条<b>从正常值掉到 0 的斜线</b>。
     * 它不是乱码、不报错、看着还挺像一条信号——本项目最警惕的那种形状。
     *
     * <h2>什么时候必须写</h2>
     * <p>只有"最后一个槽位上的实例画得出来"时才需要，即：环已经绕过（
     * {@code writtenCount >= capacity}）且那个实例有右端（{@code writtenCount} 不是
     * 容量的整数倍时，槽位 {@code capacity - 1} 上的样本是 {@code writtenCount - 1}，
     * 它的后继还不存在）。环还没绕满时槽位 {@code capacity - 1} 是空的，不需要。
     *
     * <p>而镜像的值只会在"写进槽位 0"那一次变——也就是本次上传的范围里含
     * {@code writtenCount - 1} 以下最大的那个容量倍数时。那时它<b>正好在这个范围里</b>
     * （一次上传最多覆盖一圈，所以这样的数最多一个）。
     *
     * @return 要镜像的样本绝对号；本次不需要写时返回 {@link #NO_MIRROR}
     */
    private static long mirrorSourceIndex(long newCount, long writtenCount, int capacity) {
        if (newCount <= 0 || writtenCount < capacity) {
            return NO_MIRROR;
        }
        // 容量整数倍时，最后一个槽位上的样本还没有后继，那个实例画不出来。
        if (writtenCount % capacity == 0) {
            return NO_MIRROR;
        }
        long effective = Math.min(newCount, capacity);
        long firstWritten = writtenCount - effective;
        // 槽位 0 上现在放着的是"最后一个不超过 writtenCount-1 的容量倍数"那个样本。
        long slotZero = ((writtenCount - 1) / capacity) * capacity;
        // 本次上传没写到它：值没变，上一次写过的镜像还是对的。
        return slotZero >= firstWritten ? slotZero : NO_MIRROR;
    }

    /** 要上传的字节区间，顺序即执行顺序（跨环绕时有两段）。<b>不含</b>镜像那 4 字节。 */
    public List<Range> ranges() {
        return ranges;
    }

    /**
     * <b>样本</b>要上传的总字节数（不含镜像那 4 字节，见 {@link #totalUploadBytes()}）。
     *
     * <p>它是"每帧只上传新增的点"那条主张的度量点，因此口径是"样本"——
     * 调用方拿它算"这次传了几个点"（{@code totalBytes / 4}）。
     */
    public int totalBytes() {
        return totalBytes;
    }

    /**
     * 要镜像到偏移 {@code capacity * 4} 上的那个样本的绝对号；不需要写时返回 {@link #NO_MIRROR}。
     *
     * <p>调用方（{@code SeriesBuffer}）拿它取一次值、写 4 个字节到那个偏移上。
     * 理由见上面那个私有同名的说明：<b>物理槽位 {@code capacity} 就是槽位 0</b>，
     * 环写满之后跨环绕点的那一个实例要读它。
     */
    public long mirrorSourceIndex() {
        return mirrorSourceIndex;
    }

    /**
     * 本次上传真正会写进缓冲的字节数：样本 {@link #totalBytes()}，加上镜像那 4 字节（若写）。
     *
     * <p>{@code SeriesBuffer.uploadedBytesThisFrame()} 用的就是它——那个观测口的全部价值
     * 是"如实反映实际传了多少字节"，所以镜像必须计进去，否则它就在说谎。
     */
    public int totalUploadBytes() {
        return totalBytes + (mirrorSourceIndex == NO_MIRROR ? 0 : Float.BYTES);
    }

    /**
     * 一段连续的上传。
     *
     * @param byteOffset 相对缓冲起点的字节偏移
     * @param byteLength 字节数
     */
    public record Range(int byteOffset, int byteLength) {
    }
}
