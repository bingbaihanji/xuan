package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.renderer.VertexWriter;
import com.bingbaihanji.xuan.renderer.ViewTransform;

import java.util.List;

/**
 * 把一串字形槽位摆成四边形顶点，追加进 {@link VertexWriter}。
 *
 * <h2>纯算术</h2>
 * <p>本类<strong>不碰 GL、不碰 stb、不碰图集</strong>：输入是一串普通数值
 * （{@link GlyphSlot}）、起点、缩放、颜色、拾取 ID 与变换，输出是顶点。
 * 因此可以用真的 {@code VertexWriter} 与真的 {@code ViewTransform} 直接单测。
 *
 * <h2>{@code (x, y)} 是基线起点，不是文本框左上角</h2>
 * <p>{@code y} 是文字<strong>基线</strong>所在的像素行，{@code x} 是第一个字形的笔位置。
 * 选基线而不是左上角，是因为只有基线是排版的稳定参照——图表的刻度文字要沿轴线对齐时，
 * 基线对齐才是想要的；而"左上角对齐"会让不同高度的字符视觉上跳来跳去。
 * <strong>这条是调用方最容易猜错、且猜错后"看起来只是位置偏了一点"的那类约定。</strong>
 *
 * <h2>空槽位</h2>
 * <p>宽或高为 0 的槽位（空格等）<strong>只推进笔，不发顶点</strong>。
 * 这是正常情况而不是错误。
 *
 * <h2>缩放</h2>
 * <p>槽位里的偏移、尺寸、推进全部是 em 尺寸下的像素，<strong>不预乘字号缩放</strong>
 * （见 {@link GlyphSlot}）。本方法在发射时把它们统一乘以 {@code scale}。
 * 于是同一个字形槽位可以被任意字号复用，图集只需按字形缓存一次。
 *
 * <h2>关于顶点绕向</h2>
 * <p>四个角点按左上、右上、右下、左下给出，两个三角形共用 0-2 对角线——
 * 这与 {@link VertexWriter#quad} 的约定一致，本方法直接复用它而不是自己写六个顶点，
 * 免得出现第二份"四边形怎么拆成三角形"的实现。
 *
 * <p>顺带记一笔：本项目<strong>没有开启背面剔除</strong>（全工程没有
 * {@code GL_CULL_FACE}），所以绕向目前不影响可见性。真正会出错的是把四个角点的
 * 顺序打乱——那会让两个三角形交叉成一个蝴蝶结，四个角还在，中间却缺一块。
 */
public final class TextLayout {

    private TextLayout() {
    }

    /**
     * 把槽位序列发射成四边形顶点。
     *
     * <p><strong>前置条件</strong>：调用方必须已经对 {@code writer} 调用过
     * {@code setState}（纹理设成图集、材质设成 SDF 文本），并且已经检查过
     * {@link VertexWriter#isFlushRequested()}。本方法只管写顶点，不管 GL 状态。
     *
     * @param slots              字形槽位，按绘制顺序
     * @param x                  起点 x（用户坐标）：第一个字形的笔位置
     * @param y                  起点 y（用户坐标）：<strong>基线</strong>所在的像素行
     * @param scale              字号缩放因子，{@code fontSize / GlyphRasterizer.EM_SIZE}
     * @param premultipliedRgba  预乘 alpha 后的颜色
     * @param pickId             拾取 ID，0 表示不参与拾取
     * @param writer             顶点写入器
     * @param transform          当前变换（含像素→NDC 的基础矩阵）
     * @return 总推进宽度（<strong>已乘缩放</strong>），即笔最终走到 {@code x + 返回值}
     */
    public static float layout(List<GlyphSlot> slots, float x, float y, float scale,
                               int premultipliedRgba, int pickId,
                               VertexWriter writer, ViewTransform transform) {
        float penX = x;
        for (int i = 0; i < slots.size(); i++) {
            GlyphSlot slot = slots.get(i);
            if (!slot.isEmpty()) {
                // 位图矩形（用户坐标，y 向下）。offsetY 通常是负数：字形在基线之上。
                float left = penX + slot.offsetX() * scale;
                float top = y + slot.offsetY() * scale;
                float right = left + slot.width() * scale;
                float bottom = top + slot.height() * scale;

                // 四个角各自过一遍变换：仿射变换下矩形变成平行四边形，
                // 而 writer.quad 收的本来就是任意四角，所以旋转/斜切也成立。
                float nx0 = transform.transformX(left, top);
                float ny0 = transform.transformY(left, top);
                float nx1 = transform.transformX(right, top);
                float ny1 = transform.transformY(right, top);
                float nx2 = transform.transformX(right, bottom);
                float ny2 = transform.transformY(right, bottom);
                float nx3 = transform.transformX(left, bottom);
                float ny3 = transform.transformY(left, bottom);

                writer.quad(nx0, ny0, nx1, ny1, nx2, ny2, nx3, ny3,
                        slot.u0(), slot.v0(), slot.u1(), slot.v1(),
                        premultipliedRgba, pickId);
            }
            // 空槽位也推进——空格的存在感就体现在这里。
            penX += slot.advance() * scale;
        }
        return penX - x;
    }
}
