package com.bingbaihanji.xuan.chartrender;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SeriesUploadPlan} 的单元测试。
 *
 * <p><b>这个类存在的全部理由是"每帧只上传新增的点"。</b>它的字节数统计
 * （{@link SeriesUploadPlan#totalBytes()}）是最终像素校验器里那条
 * "滚动若干帧之后每帧上传字节数恒等于新增点数 × 4" 断言的来源——
 * 那是唯一能把"GPU 常驻 + 增量上传"与"每帧全量重传"区分开的量。
 */
class SeriesUploadPlanTest {

    private static final int CAP = 8;

    @Test
    void 空变更不产生上传() {
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 0L, CAP);
        assertTrue(plan.ranges().isEmpty());
        assertEquals(0, plan.totalBytes());
    }

    @Test
    void 连续新增是一段字节区间() {
        // 从 0 增加到 3：写槽位 0,1,2 -> 偏移 0..11，共 12 字节
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 3L, CAP);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(0, 12), plan.ranges().get(0));
        assertEquals(12, plan.totalBytes());
        // 环还没绕过：槽位 capacity-1 是空的，没有实例要读镜像。
        assertEquals(SeriesUploadPlan.NO_MIRROR, plan.mirrorSourceIndex());
        assertEquals(12, plan.totalUploadBytes());
    }

    @Test
    void 跨环绕时切成两段() {
        // 容量 8，从 6 增加到 10：槽位 6,7,0,1 -> 偏移 24..31 与 0..7
        SeriesUploadPlan plan = SeriesUploadPlan.between(6L, 10L, CAP);
        assertEquals(2, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(24, 8), plan.ranges().get(0));
        assertEquals(new SeriesUploadPlan.Range(0, 8), plan.ranges().get(1));
        assertEquals(16, plan.totalBytes());
        // 环已绕过：槽位 7 上的实例（绝对号 7）的第二端读偏移 32，那里必须是**槽位 0
        // 上的样本**（绝对号 8）——它正好在本次上传的范围里（6..9）。
        assertEquals(8L, plan.mirrorSourceIndex(), "要镜像的是槽位 0 上那个样本");
        assertEquals(20, plan.totalUploadBytes(), "样本 16 字节 + 镜像 4 字节");
    }

    @Test
    void 环装得下时字节数只与新增点数有关() {
        // 新增 10 个点，两种环容量都装得下，所以都传 40 字节——
        // 与窗口多宽、环多大**无关**（这是"每帧只传新增的点"这条主张的最小形式）。
        assertEquals(40, SeriesUploadPlan.between(100L, 110L, 1024).totalBytes());
        assertEquals(40, SeriesUploadPlan.between(100L, 110L, 1 << 16).totalBytes());
    }

    @Test
    void 新增超过环容量时只传最后一圈() {
        // 这条与上一条**不矛盾**：上限是"环里还留着的那些"。
        // 一次新增 10 个点但环只有 8——前面 2 个已经被覆盖，传了也是白传。
        assertEquals(32, SeriesUploadPlan.between(100L, 110L, CAP).totalBytes(),
                "容量 8：只传最后 8 个点");
        assertEquals(40, SeriesUploadPlan.between(100L, 110L, 1 << 16).totalBytes(),
                "容量够大：10 个点全传");
    }

    @Test
    void 一次写满整个环() {
        // 从 0 增加到 8（正好一整圈）：槽位 0..7，偏移 0..31，一段
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 8L, CAP);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(0, 32), plan.ranges().get(0));
        assertEquals(32, plan.totalBytes());
        // 正好一整圈时**不需要**镜像：槽位 7 上的样本是绝对号 7，它的后继（8）还没采到，
        // 那个实例画不出来。下一次上传写绝对号 8 时才会需要，而那一次它正好落在范围里。
        assertEquals(SeriesUploadPlan.NO_MIRROR, plan.mirrorSourceIndex());
        assertEquals(32, plan.totalUploadBytes());
    }

    @Test
    void 超过一整圈时只传最后一圈() {
        // 从 0 增加到 20，容量 8：前面的会被覆盖，只有最后 8 个还在缓冲里
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 20L, CAP);
        assertEquals(32, plan.totalBytes(), "超出容量的部分已经被覆盖，传了也是白传");
        // 槽位 0 上现在放的是绝对号 16（最后一个不超过 19 的容量倍数），它在这次范围内
        assertEquals(16L, plan.mirrorSourceIndex());
        assertEquals(36, plan.totalUploadBytes());
    }

    @Test
    void 单个点的偏移是下标乘4() {
        SeriesUploadPlan plan = SeriesUploadPlan.between(5L, 6L, CAP);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(20, 4), plan.ranges().get(0));
    }

    @Test
    void 容量非2的幂时抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> SeriesUploadPlan.between(0L, 1L, 12));
    }

    // —— 以下钉住**平滑布局**（SeriesLayout.SMOOTH）：前面多留一个 float、多两个镜像 ——
    //
    // 为什么必须逐字节钉住：布局决定了四个实例属性的偏移。偏移与 uSmooth 各说各话时
    // 曲线会**弯向别的样本**，而画面"只是一条形状略有出入的曲线"——只有字节层面的
    // 期望值能把这种错拦在单元测试里（像素校验器那条路要跑真 GL 上下文）。

    @Test
    void 平滑布局的区间偏移多一个前置余量() {
        // 容量 8、从 0 增加到 3：槽位 0,1,2 → 偏移 4..15（普通布局是 0..11）
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 3L, CAP, SeriesLayout.SMOOTH);
        assertEquals(1, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(4, 12), plan.ranges().get(0),
                "平滑布局把整块数据向后挪了一个 float：槽位 0 在字节 4");
    }

    @Test
    void 平滑布局跨环绕时第二段从前置余量之后开始() {
        // 容量 8、从 6 增加到 10：槽位 6,7 → 偏移 28..35；槽位 0,1 → 偏移 4..11
        SeriesUploadPlan plan = SeriesUploadPlan.between(6L, 10L, CAP, SeriesLayout.SMOOTH);
        assertEquals(2, plan.ranges().size());
        assertEquals(new SeriesUploadPlan.Range(28, 8), plan.ranges().get(0));
        assertEquals(new SeriesUploadPlan.Range(4, 8), plan.ranges().get(1));
    }

    @Test
    void 平滑布局写三个镜像且各自指向正确的样本() {
        // 容量 8、写满 10 个（环已经绕过）：槽位 7 / 0 / 1 里分别是样本 7 / 8 / 9
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 10L, CAP, SeriesLayout.SMOOTH);
        assertEquals(List.of(
                        new SeriesUploadPlan.Mirror(0, 7L),      // 扩展槽位 -1 ← 槽位 7
                        new SeriesUploadPlan.Mirror(36, 8L),     // 扩展槽位 8  ← 槽位 0
                        new SeriesUploadPlan.Mirror(40, 9L)),    // 扩展槽位 9  ← 槽位 1
                plan.mirrors(),
                "前置余量给槽位 0 的实例当 aYm1，后两个给最后一个槽位的实例当 aY1/aY2");
        assertEquals(44, plan.totalUploadBytes(), "8 个样本 32 字节 + 三个镜像 12 字节");
    }

    @Test
    void 平滑布局对取不到的样本不写镜像() {
        // 环还没绕满：槽位 7 上还没有样本，所以前置余量不写；槽位 0/1 上的镜像照写。
        // 判据不是"环满没满"这条特判，而是"算出来的样本号是不是负数"——见 smoothMirrors。
        SeriesUploadPlan plan = SeriesUploadPlan.between(0L, 3L, CAP, SeriesLayout.SMOOTH);
        assertEquals(List.of(
                        new SeriesUploadPlan.Mirror(36, 0L),
                        new SeriesUploadPlan.Mirror(40, 1L)),
                plan.mirrors());
        assertEquals(20, plan.totalUploadBytes(), "3 个样本 12 字节 + 两个镜像 8 字节");
    }

    @Test
    void 普通布局与不传布局的那条重载逐字节相同() {
        // "默认路径逐字节不变"是硬要求（流式系列那条"每帧恰好 K×4 字节"的断言）。
        for (long written = 0; written <= 24; written++) {
            SeriesUploadPlan a = SeriesUploadPlan.between(0L, written, CAP);
            SeriesUploadPlan b = SeriesUploadPlan.between(0L, written, CAP, SeriesLayout.PLAIN);
            assertEquals(a.ranges(), b.ranges(), "written=" + written);
            assertEquals(a.mirrors(), b.mirrors(), "written=" + written);
            assertEquals(a.totalUploadBytes(), b.totalUploadBytes(), "written=" + written);
        }
    }
}
