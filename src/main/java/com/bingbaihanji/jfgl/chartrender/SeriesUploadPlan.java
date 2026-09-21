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
 * 所以同一个 y 只存一次。缓冲要比环容量多留一个 float 的余量（最后一个实例的第二端
 * 会指到界外，虽然那个实例永远不画）。
 *
 * <h2>为什么要把"超出一圈"的部分丢掉</h2>
 * <p>那些样本<b>已经被新数据覆盖了</b>，传了也是写进不再持有它们的槽位。
 * 所以上限是"环里还留着的那些"，而不是"新增了多少"。
 */
public final class SeriesUploadPlan {

    private final List<Range> ranges;

    private final int totalBytes;

    private SeriesUploadPlan(List<Range> ranges, int totalBytes) {
        this.ranges = ranges;
        this.totalBytes = totalBytes;
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
            return new SeriesUploadPlan(List.of(), 0);
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
        return new SeriesUploadPlan(List.copyOf(ranges), points * Float.BYTES);
    }

    /** 要上传的字节区间，顺序即执行顺序（跨环绕时有两段）。 */
    public List<Range> ranges() {
        return ranges;
    }

    /** 要上传的总字节数。 */
    public int totalBytes() {
        return totalBytes;
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
