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
 * 所以同一个 y 只存一次。缓冲要比环容量多留余量——<b>那些余量不是垃圾</b>：
 * 物理槽位 {@code capacity} 就是槽位 0（环的本质），于是"最后一个槽位上的实例的后继"
 * 必然落在槽位 0。见 {@link #mirrors()} 与 {@link SeriesLayout}。
 *
 * <h2>★ 两种布局：普通（1 个镜像）与平滑（3 个镜像）</h2>
 * <p>布局由 {@link SeriesLayout} 声明。普通布局的镜像算术<b>逐字节等于改动前</b>
 * （连"环没绕满时不写镜像"那条保守的提前返回都留着）；平滑布局多维护两个邻居镜像，
 * 见 {@link #mirrors()}。
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

    private final List<Mirror> mirrors;

    private SeriesUploadPlan(List<Range> ranges, int totalBytes, List<Mirror> mirrors) {
        this.ranges = ranges;
        this.totalBytes = totalBytes;
        this.mirrors = mirrors;
    }

    /**
     * 计算 {@code uploadedCount} 之后新写入的样本要传哪些字节（{@link SeriesLayout#PLAIN}）。
     *
     * @param uploadedCount 上一次上传时的写入总数
     * @param writtenCount  本次的写入总数
     * @param capacity      环容量，<strong>必须是 2 的幂</strong>
     * @return 上传计划
     * @throws IllegalArgumentException 容量不是 2 的幂
     */
    public static SeriesUploadPlan between(long uploadedCount, long writtenCount, int capacity) {
        return between(uploadedCount, writtenCount, capacity, SeriesLayout.PLAIN);
    }

    /**
     * 计算 {@code uploadedCount} 之后新写入的样本要传哪些字节。
     *
     * @param uploadedCount 上一次上传时的写入总数
     * @param writtenCount  本次的写入总数
     * @param capacity      环容量，<strong>必须是 2 的幂</strong>
     * @param layout        缓冲布局（决定区间偏移的前置量，以及要维护几个镜像）
     * @return 上传计划
     * @throws IllegalArgumentException 容量不是 2 的幂
     */
    public static SeriesUploadPlan between(long uploadedCount, long writtenCount, int capacity,
                                           SeriesLayout layout) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + capacity);
        }
        long newCount = writtenCount - uploadedCount;
        if (newCount <= 0) {
            return new SeriesUploadPlan(List.of(), 0, List.of());
        }
        // 超过一圈的部分已经被覆盖，传了也是白传。
        long effective = Math.min(newCount, capacity);
        long firstWritten = writtenCount - effective;

        int firstSlot = (int) (firstWritten & (capacity - 1));
        int points = (int) effective;
        // 前置余量把整块数据向后挪了 leadingFloats 个 float：区间偏移要跟着加。
        // 普通布局的 leadingFloats = 0，所以这一项在那边恒为 0（逐字节不变）。
        int bias = layout.byteOffsetOfSlot(0);
        List<Range> ranges = new ArrayList<>(2);
        if (firstSlot + points <= capacity) {
            ranges.add(new Range(bias + firstSlot * Float.BYTES, points * Float.BYTES));
        } else {
            int headPoints = capacity - firstSlot;
            ranges.add(new Range(bias + firstSlot * Float.BYTES, headPoints * Float.BYTES));
            ranges.add(new Range(bias, (points - headPoints) * Float.BYTES));
        }
        return new SeriesUploadPlan(List.copyOf(ranges), points * Float.BYTES,
                mirrorPlan(newCount, writtenCount, capacity, layout, firstWritten));
    }

    /**
     * 算出本次要写哪几个镜像。
     *
     * <h2>普通布局：与改动前逐字相同的单个镜像</h2>
     * <p>只维护扩展槽位 {@code capacity}（= 槽位 0 的镜像）：环绕过之后，
     * 最后一个槽位上的那个实例的第二端读的就是它。
     *
     * <p><b>为什么这里刻意"少写"。</b>当 {@code writtenCount < capacity} 时槽位
     * {@code capacity-1} 还是空的、那个实例压根画不出来；而 {@code writtenCount} 是容量的
     * 整数倍时它还没有后继。两种情况都不写——<b>这条保守的规则是既有字节数断言的一部分</b>
     * （"流式系列每帧恰好 K×4 字节"），所以它原样留着，没有跟着平滑那条一起被"泛化"。
     *
     * <h2>平滑布局：三个镜像，各自"只要它的源样本被写了就写"</h2>
     * <p>扩展槽位 {@code -1}、{@code capacity}、{@code capacity + 1}——即槽位
     * {@code capacity-1}、{@code 0}、{@code 1} 的镜像。判据只有一条：
     * <b>该槽位里"最新的那个样本"是不是本次上传写的</b>。是就写，不是就说明值没变、
     * 上一次写过的还成立。
     *
     * <p>这里<b>不做</b>"这个镜像有没有实例会读"的精算：写进去的值恒等于
     * "该槽位里最新的样本"，也就是那个位置<b>本来就应该有的值</b>——
     * 多写一个只是重复确认一条本来就成立的不变量，不会让任何一个实例读到错的值。
     * 代价是最多 12 字节／圈，而收益是"任何时刻的缓冲都自洽"（否则就得分
     * "现在该不该写"与"以后读到了怎么办"两处推理，而两处迟早会分叉）。
     */
    private static List<Mirror> mirrorPlan(long newCount, long writtenCount, int capacity,
                                          SeriesLayout layout, long firstWritten) {
        if (layout.smooth()) {
            return smoothMirrors(writtenCount, capacity, layout, firstWritten);
        }
        long single = plainMirrorSourceIndex(newCount, writtenCount, capacity);
        return single == NO_MIRROR
                ? List.of()
                : List.of(new Mirror(layout.byteOffsetOfSlot(capacity), single));
    }

    /**
     * 普通布局那<b>唯一</b>一个镜像的源样本绝对号。
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
    private static long plainMirrorSourceIndex(long newCount, long writtenCount, int capacity) {
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

    /**
     * 平滑布局的三个镜像。
     *
     * <p>对每个扩展槽位 {@code A ∈ {-1, capacity, capacity+1}}：
     * 槽位 {@code slot = A & (capacity-1)}（注意 {@code -1 & (capacity-1) == capacity-1}，
     * 二补码下直接就是那个结果，不需要特判），它里面"最新的样本"是
     * <b>不超过 {@code writtenCount-1} 的最大同余数</b>。这个样本若落在本次上传的范围里，
     * 它的值就刚被改写，镜像必须跟着写。
     *
     * <p>取不到负数样本时（环还没绕满，例如槽位 {@code capacity-1} 上还没有样本）算出来是
     * 负数，而 {@code firstWritten ≥ 0}，所以它自动被下面的判断排除——<b>不需要额外一条
     * "环没绕满就不写"的特判</b>（普通布局那条特判是历史遗留，见上面）。
     */
    private static List<Mirror> smoothMirrors(long writtenCount, int capacity,
                                             SeriesLayout layout, long firstWritten) {
        long last = writtenCount - 1;
        // 三个扩展槽位：槽位 capacity-1、槽位 0、槽位 1 的镜像。
        int[] addresses = {-1, capacity, capacity + 1};
        List<Mirror> out = new ArrayList<>(addresses.length);
        for (int address : addresses) {
            int slot = address & (capacity - 1);
            long newest = last - Math.floorMod(last - slot, capacity);
            if (newest >= firstWritten) {
                out.add(new Mirror(layout.byteOffsetOfSlot(address), newest));
            }
        }
        return List.copyOf(out);
    }

    /** 要上传的字节区间，顺序即执行顺序（跨环绕时有两段）。<b>不含</b>镜像那几个 float。 */
    public List<Range> ranges() {
        return ranges;
    }

    /**
     * <b>样本</b>要上传的总字节数（不含镜像，见 {@link #totalUploadBytes()}）。
     *
     * <p>它是"每帧只上传新增的点"那条主张的度量点，因此口径是"样本"——
     * 调用方拿它算"这次传了几个点"（{@code totalBytes / 4}）。
     */
    public int totalBytes() {
        return totalBytes;
    }

    /**
     * 本次要写的<b>全部</b>镜像（0、1 或 3 个）。
     *
     * <p>每个元素是"往哪个字节偏移写、写哪个样本"。调用方（{@code SeriesBuffer}）
     * 逐个取值、各写 4 个字节。理由见 {@link #plainMirrorSourceIndex} 与
     * {@link #smoothMirrors}：<b>物理槽位 {@code capacity} 就是槽位 0</b>，
     * 环写满之后跨环绕点的那一个实例要读它。
     */
    public List<Mirror> mirrors() {
        return mirrors;
    }

    /**
     * 本次第一个镜像的源样本绝对号；没有镜像时返回 {@link #NO_MIRROR}。
     *
     * <p>它是 {@link #mirrors()} 的便捷读法，保留下来是因为既有的调用点与断言都在问
     * "那一个镜像"。平滑布局下它只回答"第一个"，要看全部请用 {@link #mirrors()}。
     */
    public long mirrorSourceIndex() {
        return mirrors.isEmpty() ? NO_MIRROR : mirrors.get(0).sourceIndex();
    }

    /**
     * 本次上传真正会写进缓冲的字节数：样本 {@link #totalBytes()}，加上每个镜像 4 字节。
     *
     * <p>{@code SeriesBuffer.uploadedBytesThisFrame()} 用的就是它——那个观测口的全部价值
     * 是"如实反映实际传了多少字节"，所以镜像必须计进去，否则它就在说谎。
     */
    public int totalUploadBytes() {
        return totalBytes + mirrors.size() * Float.BYTES;
    }

    /**
     * 一段连续的上传。
     *
     * @param byteOffset 相对缓冲起点的字节偏移（已含布局的前置余量）
     * @param byteLength 字节数
     */
    public record Range(int byteOffset, int byteLength) {
    }

    /**
     * 一个镜像要写的位置与内容。
     *
     * @param byteOffset  相对缓冲起点的字节偏移（由 {@link SeriesLayout#byteOffsetOfSlot} 换算）
     * @param sourceIndex 要写进去的那个样本的<strong>绝对号</strong>
     */
    public record Mirror(int byteOffset, long sourceIndex) {
    }
}

