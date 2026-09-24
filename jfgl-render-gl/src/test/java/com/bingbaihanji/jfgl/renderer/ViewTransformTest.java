package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.math.Vec2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViewTransform} 的单测。
 *
 * <p>这些断言覆盖的全是<strong>纯坐标数学</strong>——基础矩阵、NDC↔设备像素换算、裁剪求交、
 * 变换栈语义、缩放因子。它们正是本项目反复出错的地方（裁剪在 GL 侧的 y 翻转、像素与 NDC 的
 * 单位混用、由漏算视口尺寸导致的缩放因子相差数百倍），而 {@link Gc} 持有 {@link RenderBatch}，
 * 没有 GL 上下文就无法构造，因此这些逻辑必须留在一个可脱离 GL 单测的类里。
 *
 * <p>测试用 Java 写：`renderer` 包的既有单测（{@code VertexWriterTest}、{@code VertexFormatTest}）
 * 就是 Java，且 {@code ViewTransform} 在 Kotlin 源码里声明为 {@code internal}（Kotlin 没有
 * package-private，{@code internal} 是"仅模块内可见"的等价物），Kotlin 的测试源集默认不在
 * 同一 friend 模块里；Java 测试则不受这条限制，同时也说明这个类并没有进入对外 API。
 *
 * <p>浮点容差统一取 1e-4，旋转相关的断言因为要经过 `toRadians` 的 float 截断，放宽到 1e-3。
 */
class ViewTransformTest {

    /** 浮点断言容差。 */
    private static final float EPS = 1e-4f;

    /** 构造一个已经开好帧的变换状态。 */
    private static ViewTransform frame(int width, int height) {
        ViewTransform t = new ViewTransform();
        t.beginFrame(width, height);
        return t;
    }

    private static ViewTransform frame() {
        return frame(800, 600);
    }

    /** 返回点 (x,y) 经当前变换后的 NDC 坐标。 */
    private static Vec2 ndc(ViewTransform t, float x, float y) {
        return t.getMatrix().transform(new Vec2(x, y));
    }

    // ------------------------------------------------------------------
    // 基础矩阵
    // ------------------------------------------------------------------

    @Test
    void baseMatrixMapsUserOriginToNdcTopLeft() {
        ViewTransform t = frame();
        Vec2 origin = ndc(t, 0f, 0f);
        assertEquals(-1f, origin.x(), EPS, "用户 (0,0) 必须落在 NDC 左上角 x=-1");
        assertEquals(1f, origin.y(), EPS, "用户 (0,0) 必须落在 NDC 左上角 y=+1");
    }

    @Test
    void baseMatrixMapsUserBottomRightToNdcBottomRight() {
        ViewTransform t = frame();
        Vec2 corner = ndc(t, 800f, 600f);
        assertEquals(1f, corner.x(), EPS, "用户 (w,h) 必须落在 NDC 右下角 x=+1");
        assertEquals(-1f, corner.y(), EPS, "用户 (w,h) 必须落在 NDC 右下角 y=-1");
    }

    @Test
    void baseMatrixMapsViewportCenterToNdcOrigin() {
        ViewTransform t = frame();
        Vec2 center = ndc(t, 400f, 300f);
        assertEquals(0f, center.x(), EPS);
        assertEquals(0f, center.y(), EPS);
    }

    @Test
    void baseMatrixFollowsViewportSize() {
        ViewTransform t = frame(400, 400);
        assertEquals(0f, ndc(t, 200f, 200f).x(), EPS, "400x400 视口的中心是用户 (200,200)");
        assertEquals(-1f, ndc(t, 400f, 400f).y(), EPS);
    }

    // ------------------------------------------------------------------
    // NDC ↔ 设备像素
    // ------------------------------------------------------------------

    @Test
    void ndcToDevicePixelsIsYDown() {
        ViewTransform t = frame();
        assertEquals(0f, t.deviceX(-1f), EPS);
        assertEquals(800f, t.deviceX(1f), EPS);
        // NDC 的 +1 在屏幕上方，对应设备像素 y=0；-1 在下方，对应 y=600
        assertEquals(0f, t.deviceY(1f), EPS);
        assertEquals(600f, t.deviceY(-1f), EPS);
        assertEquals(300f, t.deviceY(0f), EPS);
    }

    @Test
    void deviceBoundsTopComesFromMaxNdcY() {
        ViewTransform t = frame();
        float[] out = new float[4];
        t.toDeviceBounds(-1f, -1f, 1f, 1f, out);
        assertArrayEquals(new float[]{0f, 0f, 800f, 600f}, out, EPS, "整个 NDC 应覆盖整个帧缓冲");
    }

    @Test
    void deviceBoundsOfUpperHalfScreenStartsAtZero() {
        ViewTransform t = frame();
        float[] out = new float[4];
        // NDC y ∈ [0, 1] 是屏幕的上半部分 → 设备 y ∈ [0, 300]
        t.toDeviceBounds(-1f, 0f, 1f, 1f, out);
        assertArrayEquals(new float[]{0f, 0f, 800f, 300f}, out, EPS);
    }

    @Test
    void deviceBoundsTopIsTheSmallerY() {
        ViewTransform t = frame();
        float[] out = new float[4];
        // NDC y ∈ [-1, 0] 是屏幕的下半部分 → 设备 y ∈ [300, 600]
        t.toDeviceBounds(-1f, -1f, 1f, 0f, out);
        assertArrayEquals(new float[]{0f, 300f, 800f, 600f}, out, EPS);
    }

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    @Test
    void freshFrameClipCoversWholeFramebuffer() {
        ViewTransform t = frame();
        assertEquals(0f, t.getClipLeft(), EPS);
        assertEquals(0f, t.getClipTop(), EPS);
        assertEquals(800f, t.getClipRight(), EPS);
        assertEquals(600f, t.getClipBottom(), EPS);
        assertEquals(800, t.getClipDeviceWidth());
        assertEquals(600, t.getClipDeviceHeight());
    }

    @Test
    void clipRectYIsTheTopEdgeNotTheBottomEdge() {
        ViewTransform t = frame();
        t.clipRect(0f, 100f, 10f, 10f);
        // 顶边是 100。若误当成底边（把 y 翻转一次），会得到 600-100-10=490
        assertEquals(100, t.getClipDeviceY(), "clipDeviceY 必须是矩形顶边（y 向下）");
        assertEquals(0, t.getClipDeviceX());
        assertEquals(10, t.getClipDeviceWidth());
        assertEquals(10, t.getClipDeviceHeight());
    }

    @Test
    void clipRectFollowsCurrentTransform() {
        ViewTransform t = frame();
        t.translate(100f, 50f);
        t.clipRect(0f, 0f, 20f, 30f);
        assertEquals(100, t.getClipDeviceX());
        assertEquals(50, t.getClipDeviceY());
        assertEquals(20, t.getClipDeviceWidth());
        assertEquals(30, t.getClipDeviceHeight());
    }

    @Test
    void nestedClipRectsIntersectInsteadOfReplacing() {
        ViewTransform t = frame();
        t.clipRect(0f, 0f, 100f, 100f);
        t.clipRect(50f, 50f, 100f, 100f);
        // [0,100] ∩ [50,150] = [50,100]，宽高各 50
        assertEquals(50f, t.getClipLeft(), EPS);
        assertEquals(50f, t.getClipTop(), EPS);
        assertEquals(100f, t.getClipRight(), EPS);
        assertEquals(100f, t.getClipBottom(), EPS);
    }

    @Test
    void disjointClipRectsDegradeToZeroSize() {
        ViewTransform t = frame();
        t.clipRect(0f, 0f, 10f, 10f);
        t.clipRect(100f, 100f, 10f, 10f);
        assertEquals(0, t.getClipDeviceWidth(), "交集为空时宽度必须为 0，不能是负数");
        assertEquals(0, t.getClipDeviceHeight());
    }

    @Test
    void clipIsClampedToFramebuffer() {
        ViewTransform t = frame();
        t.clipRect(-100f, -100f, 1000f, 1000f);
        // 初始裁剪就是整个帧缓冲，超出的部分被交掉
        assertEquals(0, t.getClipDeviceX());
        assertEquals(0, t.getClipDeviceY());
        assertEquals(800, t.getClipDeviceWidth());
        assertEquals(600, t.getClipDeviceHeight());
    }

    @Test
    void clipRectRoundsOutwardToWholePixels() {
        ViewTransform t = frame();
        // 设备像素 [10.5, 14.5] × [20.5, 24.5] → 被它覆盖到的像素是 [10, 15) × [20, 25)
        t.clipRect(10.5f, 20.5f, 4f, 4f);
        assertEquals(10, t.getClipDeviceX(), "左边界向下取整");
        assertEquals(20, t.getClipDeviceY(), "上边界向下取整");
        assertEquals(5, t.getClipDeviceWidth(), "右边界向上取整后为 15，宽度 5");
        assertEquals(5, t.getClipDeviceHeight(), "下边界向上取整后为 25，高度 5");
    }

    @Test
    void rotatedClipRectBecomesAxisAlignedBoundingBox() {
        ViewTransform t = frame();
        t.translate(400f, 300f);
        t.rotate(45f);
        t.clipRect(-10f, -10f, 20f, 20f);
        // 边长 20 的正方形转 45° 后，包围盒半宽 = 10*sqrt(2) ≈ 14.1421
        float half = 10f * (float) Math.sqrt(2.0);
        assertEquals(400f - half, t.getClipLeft(), 1e-3f);
        assertEquals(300f - half, t.getClipTop(), 1e-3f);
        assertEquals(400f + half, t.getClipRight(), 1e-3f);
        assertEquals(300f + half, t.getClipBottom(), 1e-3f);
    }

    /**
     * 上一条钉住的是"旋转裁剪退化成轴对齐包围盒"的**数值**；这一条钉住它的**方向**。
     *
     * <p>降级本身是刻意的（底层只有 `glScissor`，它按定义只能是轴对齐矩形；旋转裁剪要
     * 模板缓冲或着色器遮罩，本项目都没有），但"刻意"只解释了为什么允许它存在，
     * 不解释它**可不可接受**。可接受的理由是方向单向：包围盒恒**包含**旋转后的矩形，
     * 于是这个降级只会让裁剪区变大——**裁少，不会裁多**，永远不会吞掉本该显示的内容。
     *
     * <p>这条断言就是这个不变量的形式化：把裁剪矩形的四个角点经当前变换打到设备像素上，
     * 每一个都必须落在裁剪区之内。任何"只取两个角点求包围盒""把矩形当成平行四边形继续
     * 旋转"之类的改法都会让它失败。角度取到 350°，覆盖四个象限与负角度。
     */
    @Test
    void 旋转裁剪的包围盒只多不少() {
        for (float angle = -110f; angle < 360f; angle += 23.5f) {
            ViewTransform t = frame();
            t.translate(400f, 300f);
            t.rotate(angle);
            // 刻意用一个长宽不等、且不与屏幕对齐的矩形：轴对齐时包围盒与矩形重合，
            // 分开长宽才能暴露"只算两个角点"这类错误。
            t.clipRect(-30f, -12f, 62f, 26f);

            // 与 clipRect 内部同一套换算：四个角点 → NDC → 设备像素
            float[][] corners = {
                    {-30f, -12f}, {32f, -12f}, {32f, 14f}, {-30f, 14f}
            };
            for (float[] c : corners) {
                float deviceX = t.deviceX(t.transformX(c[0], c[1]));
                float deviceY = t.deviceY(t.transformY(c[0], c[1]));
                assertTrue(deviceX >= t.getClipLeft() - 1e-3f && deviceX <= t.getClipRight() + 1e-3f,
                        "角度 " + angle + "：角点 x=" + deviceX + " 落在裁剪区 ["
                                + t.getClipLeft() + ", " + t.getClipRight() + "] 之外");
                assertTrue(deviceY >= t.getClipTop() - 1e-3f && deviceY <= t.getClipBottom() + 1e-3f,
                        "角度 " + angle + "：角点 y=" + deviceY + " 落在裁剪区 ["
                                + t.getClipTop() + ", " + t.getClipBottom() + "] 之外");
            }
        }
    }

    // ------------------------------------------------------------------
    // 变换与栈
    // ------------------------------------------------------------------

    @Test
    void translateAccumulatesAfterCurrentTransform() {
        ViewTransform t = frame();
        t.translate(100f, 50f);
        Vec2 p = ndc(t, 0f, 0f);
        assertEquals(100f / 800f * 2f - 1f, p.x(), EPS);
        assertEquals(1f - 50f / 600f * 2f, p.y(), EPS);
    }

    @Test
    void rotateIsClockwiseOnScreen() {
        ViewTransform t = frame();
        t.translate(400f, 300f);
        // 参照点必须在**旋转之前**取：用户空间的单位向量 (1,0) 指向屏幕"右"，(0,1) 指向屏幕"下"。
        float downX = deviceXOf(t, 0f, 1f);
        float downY = deviceYOf(t, 0f, 1f);
        t.rotate(90f);
        // 屏幕上的顺时针 90° 应把"右"转到"下"，所以旋转后的 (1,0) 必须落在未旋转时 (0,1) 的像素位置上。
        // 注意只能在设备像素下比较：NDC 的 x 与 y 缩放因子不同（2/w 与 2/h），
        // 直接比较 NDC 增量会把两个轴的量纲混在一起。
        assertEquals(downX, deviceXOf(t, 1f, 0f), 1e-3f, "旋转后'右'的 x 应等于未旋转时'下'的 x");
        assertEquals(downY, deviceYOf(t, 1f, 0f), 1e-3f, "旋转后'右'的 y 应等于未旋转时'下'的 y");
    }

    @Test
    void rotateIsNotCounterClockwise() {
        ViewTransform t = frame();
        t.translate(400f, 300f);
        // 逆时针会落到"上"的位置，即 (0,-1) 的像素位置上
        float upX = deviceXOf(t, 0f, -1f);
        float upY = deviceYOf(t, 0f, -1f);
        t.rotate(90f);
        float rightX = deviceXOf(t, 1f, 0f);
        float rightY = deviceYOf(t, 1f, 0f);
        assertTrue(Math.abs(rightX - upX) + Math.abs(rightY - upY) > 1f,
                "rotate(+90) 不能是逆时针（把'右'转到'上'）：实际落点 (" + rightX + "," + rightY
                        + ")，逆时针落点应为 (" + upX + "," + upY + ")");
    }

    @Test
    void saveAndRestoreRestoreTransform() {
        ViewTransform t = frame();
        t.translate(10f, 20f);
        t.save();
        t.translate(100f, 0f);
        assertEquals(110f, deviceXOfUserOrigin(t), EPS, "save 之后追加的平移应当生效");
        t.restore();
        assertEquals(10f, deviceXOfUserOrigin(t), EPS, "restore 应当回到 save 时的变换");
    }

    @Test
    void saveAndRestoreRestoreClip() {
        ViewTransform t = frame();
        t.save();
        t.clipRect(100f, 100f, 50f, 50f);
        assertEquals(100f, t.getClipLeft(), EPS);
        t.restore();
        assertEquals(0f, t.getClipLeft(), EPS);
        assertEquals(800f, t.getClipRight(), EPS);
        assertEquals(600f, t.getClipBottom(), EPS);
    }

    @Test
    void nestedSaveRestoreIsLifo() {
        ViewTransform t = frame();
        t.save();
        t.translate(1f, 0f);
        t.save();
        t.translate(0f, 2f);
        assertEquals(2, t.getStackDepth());
        t.restore();
        assertEquals(1, t.getStackDepth());
        // 回到第一层：只有 (1,0) 的平移
        Vec2 p = ndc(t, 0f, 0f);
        assertEquals(1f / 800f * 2f - 1f, p.x(), EPS);
        assertEquals(1f, p.y(), EPS);
        t.restore();
        assertEquals(0, t.getStackDepth());
        Vec2 origin = ndc(t, 0f, 0f);
        assertEquals(-1f, origin.x(), EPS);
        assertEquals(1f, origin.y(), EPS);
    }

    @Test
    void unbalancedRestoreThrowsAndKeepsState() {
        ViewTransform t = frame();
        IllegalStateException error = assertThrows(IllegalStateException.class, t::restore);
        assertTrue(error.getMessage().contains("save"),
                "异常消息应当点明是 save/restore 不配对：" + error.getMessage());
        assertEquals(0, t.getStackDepth());
        assertEquals(-1f, ndc(t, 0f, 0f).x(), EPS, "抛出异常后状态不能被破坏");
    }

    @Test
    void clearStackReturnsDroppedLevels() {
        ViewTransform t = frame();
        t.save();
        t.save();
        t.save();
        assertEquals(3, t.clearStack());
        assertEquals(0, t.getStackDepth());
        assertEquals(0, t.clearStack());
    }

    @Test
    void beginFrameResetsEverything() {
        ViewTransform t = frame();
        t.translate(100f, 100f);
        t.clipRect(10f, 10f, 10f, 10f);
        t.save();
        t.beginFrame(400, 400);
        assertEquals(0, t.getStackDepth());
        assertEquals(0f, t.getClipLeft(), EPS);
        assertEquals(400f, t.getClipRight(), EPS);
        assertEquals(400f, t.getClipBottom(), EPS);
        assertEquals(-1f, ndc(t, 0f, 0f).x(), EPS);
        assertEquals(1f, ndc(t, 0f, 0f).y(), EPS);
    }

    @Test
    void beginFrameRejectsNonPositiveViewport() {
        ViewTransform t = new ViewTransform();
        assertThrows(IllegalArgumentException.class, () -> t.beginFrame(0, 600));
        assertThrows(IllegalArgumentException.class, () -> t.beginFrame(800, 0));
    }

    // ------------------------------------------------------------------
    // 热路径辅助
    // ------------------------------------------------------------------

    @Test
    void componentTransformMatchesMat3Transform() {
        ViewTransform t = frame();
        t.translate(13f, -7f);
        t.scale(2f, 3f);
        t.rotate(25f);
        assertMatchesMat3(t, "translate/scale/rotate 之后");

        t.save();
        t.rotate(-40f);
        t.translate(5f, 9f);
        assertMatchesMat3(t, "嵌套变换之后");

        t.restore();
        assertMatchesMat3(t, "restore 之后缓存的矩阵必须同步回退");
    }

    @Test
    void componentTransformIsBaseMatrixAfterBeginFrame() {
        ViewTransform t = frame(1024, 768);
        assertMatchesMat3(t, "开帧之后");
        assertEquals(-1f, t.transformX(0f, 0f), EPS);
        assertEquals(1f, t.transformY(0f, 0f), EPS);
        assertEquals(1f, t.transformX(1024f, 768f), EPS);
        assertEquals(-1f, t.transformY(1024f, 768f), EPS);
    }

    // ------------------------------------------------------------------
    // 缩放因子
    // ------------------------------------------------------------------

    @Test
    void matrixScaleIsOneForIdentityTransform() {
        // 恒等变换下"一个用户单位就是一个设备像素"，与视口尺寸无关
        assertEquals(1f, frame(800, 600).matrixScale(), EPS);
        assertEquals(1f, frame(400, 400).matrixScale(), EPS);
        assertEquals(1f, frame(1920, 1080).matrixScale(), EPS);
    }

    @Test
    void matrixScaleIncludesBaseMatrix() {
        // 漏算基础矩阵会让结果差出约 W/2 倍：这里显式钉住"设备像素"的口径
        ViewTransform t = frame(200, 200);
        assertEquals(1f, t.matrixScale(), EPS, "基础矩阵把 2/w 的缩放抵消掉了");
    }

    @Test
    void matrixScaleFollowsUserScale() {
        ViewTransform t = frame();
        t.scale(3f, 5f);
        assertEquals(4f, t.matrixScale(), EPS, "两个轴向的平均");
    }

    @Test
    void matrixScaleIgnoresTranslationAndViewportSize() {
        ViewTransform t = frame(400, 400);
        t.translate(37f, -12f);
        assertEquals(1f, t.matrixScale(), EPS);
    }

    @Test
    void matrixScaleIsInvariantUnderRotation() {
        ViewTransform t = frame();
        t.rotate(30f);
        t.scale(2f, 2f);
        assertEquals(2f, t.matrixScale(), EPS);
    }

    @Test
    void matrixScaleAveragesBothAxesForNonUniformScale() {
        ViewTransform t = frame();
        t.scale(2f, 4f);
        // 转 90° 恰好把两个轴的缩放互换：基向量长度仍是 2 与 4，平均仍是 3
        t.rotate(90f);
        assertEquals(3f, t.matrixScale(), 1e-3f);
    }

    // ------------------------------------------------------------------
    // 「当前变换是不是本帧的基础变换」
    // ------------------------------------------------------------------

    /**
     * 基础变换是**像素 → NDC 那一趟**，不是数学单位阵。
     *
     * <p>这一条是 {@code ChartPainter.begin()} 那道守卫的口径：图表装饰的布局算的是设备像素，
     * 所以调用方带着任何 translate/scale/rotate 进 {@code drawChart} 都会让装饰落到
     * 布局没算过的位置。守卫必须放行"什么都没做"，不然每一张图都画不出来。
     */
    @Test
    void freshlyOpenedFrameIsBaseTransform() {
        assertTrue(frame(800, 600).isBaseTransform(), "刚 beginFrame 的变换就是基础变换");
        assertTrue(new ViewTransform().isBaseTransform(),
                "还没 beginFrame 时矩阵是单位阵，也算基础变换（守卫不该在帧外误报）");
    }

    @Test
    void anyTransformMakesItNotBase() {
        ViewTransform t = frame();
        t.translate(1f, 0f);
        assertFalse(t.isBaseTransform(), "哪怕是 1 像素的平移，装饰也会画错地方");

        ViewTransform s = frame();
        s.scale(1f, 1f);
        assertFalse(s.isBaseTransform(), "scale(1,1) 数值上等于基础变换，但不是同一次设置——"
                + "判据是「有没有被动过」而不是「矩阵看起来一样」");

        ViewTransform r = frame();
        r.rotate(0f);
        assertFalse(r.isBaseTransform(), "rotate(0) 同理");

        ViewTransform u = frame();
        u.translate(5f, 7f);
        u.translate(-5f, -7f);
        assertFalse(u.isBaseTransform(), "平移过去又平移回来：矩阵回到原值，但仍是「被动过」");
    }

    /**
     * 变换栈弹回之后必须**重新**算基础变换。
     *
     * <p>这才是守卫真正要拦的那种调用：{@code save(); translate(...); drawChart(...)}——
     * 装饰画在错位的地方、而 {@code restore()} 之后一切正常，缺陷只在那一次调用上可见。
     */
    @Test
    void saveRestoreReturnsToBaseTransform() {
        ViewTransform t = frame();
        t.save();
        t.translate(10f, 20f);
        assertFalse(t.isBaseTransform(), "save/translate 之内不是基础变换");
        t.restore();
        assertTrue(t.isBaseTransform(), "restore 之后必须重新算基础变换");
    }

    @Test
    void beginFrameResetsBaseTransformAfterScale() {
        ViewTransform t = frame();
        t.scale(3f, 3f);
        t.beginFrame(400, 300);
        assertTrue(t.isBaseTransform(), "新一帧从基础变换开始，与上一帧做过什么无关");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 把用户原点变换到设备像素 x，便于直接和用户坐标下的平移量比较。 */
    private static float deviceXOfUserOrigin(ViewTransform t) {
        return (ndc(t, 0f, 0f).x() + 1f) * 0.5f * t.getViewportWidth();
    }

    /** 用户坐标点 → 设备像素 x。跨 y 轴的比较必须在设备像素下做，见 rotateIsClockwiseOnScreen。 */
    private static float deviceXOf(ViewTransform t, float x, float y) {
        return t.deviceX(t.transformX(x, y));
    }

    /** 用户坐标点 → 设备像素 y（y 向下）。 */
    private static float deviceYOf(ViewTransform t, float x, float y) {
        return t.deviceY(t.transformY(x, y));
    }

    /** 逐点比较 {@link ViewTransform#transformX}/{@link ViewTransform#transformY} 与 {@code Mat3.transform}。 */
    private static void assertMatchesMat3(ViewTransform t, String message) {
        float[] points = {0f, 0f, 1f, 0f, 0f, 1f, 123.5f, -45.25f, -800f, 600f};
        for (int i = 0; i < points.length; i += 2) {
            float x = points[i];
            float y = points[i + 1];
            Vec2 m = ndc(t, x, y);
            assertEquals(m.x(), t.transformX(x, y), 1e-4f, message + " (" + x + "," + y + ")");
            assertEquals(m.y(), t.transformY(x, y), 1e-4f, message + " (" + x + "," + y + ")");
        }
    }
}
