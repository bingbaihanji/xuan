package com.bingbaihanji.gl;

import com.bingbaihanji.xuan.glview.FXGLTransfer;
import com.bingbaihanji.xuan.renderer.Gc;
import javafx.application.Application;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import kotlin.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 「矩形」画布示例：左键两点式画矩形、右键拾取（支持重叠矩形循环选中）。
 *
 * <p>本类同时被两类线程触碰，读代码时务必先分清：
 * <ul>
 *   <li><strong>JavaFX 应用线程</strong>：鼠标事件、拾取决策、改 {@code selectedId}、
 *       写 {@code pendingRectangles}；</li>
 *   <li><strong>GL 渲染线程</strong>：{@code onFrame} 回调里排空 {@code pendingRectangles}、
 *       读写 {@code rectangles}、绘制。</li>
 * </ul>
 * 两者之间只通过 <em>volatile 字段</em> 与 <em>并发队列</em> 交接，
 * 任何"直接在 JavaFX 线程改 {@code rectangles}"或"在 GL 线程改控件"的写法都是竞态。
 *
 * @author bingbaihanji
 * @date 2026-09-30 17:41:05
 */
public class CanvasRectangle extends Application {

    // ---------- 可调参数 ----------

    /** 同位置点击的容差（设备像素）：手抖一点点不应重置循环。 */
    private static final float SAME_SPOT_EPS = 3f;

    /** 拾取时允许点在矩形外侧一点点仍算命中（设备像素）。 */
    private static final float PICK_PADDING = 2f;

    /** 选中高亮相对矩形边缘外扩的距离（设备像素）：留一点缝，看得更清楚。 */
    private static final float SELECT_OUTSET = 6f;

    private static final int glBackgroundColor = formatColor(Color.valueOf("#23475f"));

    // ---------- 尺寸 ----------

    private final static FXGLTransfer fxglTransfer = new FXGLTransfer();

    /** JavaFX 线程写入、GL 线程在下一帧排空。 */
    private final Queue<Rectangle> pendingRectangles = new ConcurrentLinkedQueue<>();

    // ---------- 预览状态（GL 线程读，JavaFX 线程写） ----------

    /** 已落地的矩形。**只有 GL 线程读写**。 */
    private final List<Rectangle> rectangles = new ArrayList<>();

    private Double WIDTH;

    private Double HEIGHT;

    /** 预览用的鼠标位置（设备像素）；NaN 表示指针不在画布上。 */
    private volatile float pointerX = Float.NaN;

    private volatile float pointerY = Float.NaN;

    // ---------- 矩形集合 ----------

    /** 两点式的第一角（设备像素）；NaN 表示"手上还没有已定的角"。 */
    private volatile float anchorX = Float.NaN;

    private volatile float anchorY = Float.NaN;

    /** 当前选中的拾取号；0 表示没有选中。 */
    private volatile int selectedId = 0;

    // ---------- 循环选择状态（只在 JavaFX 线程访问） ----------

    /**
     * {@link #rectangles} 的只读快照，供 JavaFX 线程做几何拾取。
     * <p>内容由 GL 线程在每次 drain 之后整体替换（volatile 写），
     * JavaFX 线程整份读取 —— 不需要对单个矩形加锁。
     */
    private volatile List<Rectangle> rectanglesView = List.of();

    /** 上一次右键点击的位置（设备像素）；NaN 表示还没有过一次拾取。 */
    private float lastPickX = Float.NaN;

    private float lastPickY = Float.NaN;

    /**
     * 程序入口。JavaFX 规定 {@code launch} 只能从 {@code main} 里调，
     * 且不会返回 —— 真正的初始化在 {@link #init()} / {@link #start(Stage)}。
     */
    public static void main(String[] args) {
        Application.launch(args);
    }

    /**
     * 清理当前帧背景。每帧开头都要清，否则上一帧的图形会叠加残留。
     * <p>先把 {@code pickId} 置 0：背景不属于任何可拾取对象，
     * 这样"点空白处"才能得到"未命中"而不是误中前一帧遗留的 ID。
     *
     * @param gc 当前帧的绘制上下文，<strong>只在 GL 线程使用</strong>
     */
    private static void clearBackground(Gc gc) {
        gc.setPickId(0);
        gc.setFill(glBackgroundColor);
        gc.fillRect(0f, 0f, gc.getWidth(), gc.getHeight());
    }

    /**
     * 把 JavaFX 的 {@link Color} 压成框架用的 32 位 ARGB（A 在高位）。
     * <p>与 {@code Gc} 的取色约定一致：{@code 0xAARRGGBB}。
     * 之所以自己转而不直接用 {@code Color}：Gc 的接口是给 GL 用的整数色，
     * 这样可以避免每帧把对象拆成浮点四元组。
     */
    private static int formatColor(Color color) {
        int a = (int) Math.round(color.getOpacity() * 255.0);
        int r = (int) Math.round(color.getRed() * 255.0);
        int g = (int) Math.round(color.getGreen() * 255.0);
        int b = (int) Math.round(color.getBlue() * 255.0);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 在候选列表里找某个拾取号的下标。
     *
     * @param list 候选列表
     * @param id   目标拾取号
     * @return 下标；找不到返回 -1（调用方据此回卷到开头）
     */
    private static int indexOfPickId(List<Rectangle> list, int id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).pickId == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 画一个已落地的矩形：半透明填充 + 实线描边。
     *
     * <p>画的是<strong>归一化</strong>后的坐标（left/top/width/height），不是原始起终点：
     * {@code strokeRect} / {@code fillRect} 通常不接受负宽高，用户从右下往左上拖时
     * 直接传 {@code ending - starting} 会得到一个反着的、甚至看不见的矩形。
     * 归一化后，方向由 {@code Rectangle} 内部消化，绘制这边不用再判方向。
     *
     * <p>顺序：先填充再描边 —— 描边压在半透明填充之上，边缘才清晰；
     * 反过来会被填充的 alpha 糊掉一圈。
     *
     * <p>此方法会被 {@code gc.pickable(...)} 包着调用，所以
     * <strong>不要在这里改 pickId</strong>，交给外层的 save/restore 管。
     *
     * @param gc        当前帧的绘制上下文
     * @param rectangle 目标矩形
     */
    private static void drawOneRectangle(Gc gc, Rectangle rectangle) {
        float x = (float) rectangle.left();
        float y = (float) rectangle.top();
        float w = (float) rectangle.width();
        float h = (float) rectangle.height();

        gc.setFill(0x554FC3F7);
        gc.fillRect(x, y, w, h);

        gc.setStroke(0xFF4FC3F7);
        gc.setLineWidth(2f);
        gc.strokeRect(x, y, w, h);
    }

    /**
     * JavaFX 生命周期钩子：{@code start} 之前、JavaFX 线程上执行。
     * <p>这里只放与窗口无关的常量初始化；涉及 GL 的资源（如 {@code fxglTransfer}）
     * 要留到 {@link #start(Stage)} 或首次 {@code onFrame} 里再动。
     */
    @Override
    public void init() throws Exception {
        super.init();
        WIDTH = 800.0;
        HEIGHT = 600.0;
    }

    /**
     * JavaFX 生命周期钩子：窗口关闭时调用。释放 GL 侧资源（上下文、缓冲等）。
     * <p>不释放的话，LWJGL 的本地内存会一直挂着到进程退出，
     * 反复开关窗口时能看到显存/句柄缓慢上涨。
     */
    @Override
    public void stop() throws Exception {
        super.stop();
        fxglTransfer.dispose();
    }

    /**
     * 组装场景：一块铺满的 GL 画布 + 鼠标事件接线。
     * <p>GL 画布用 {@code AnchorPane} 四边锚定，因此窗口缩放时它会跟着变，
     * 不需要手动监听 size 变化。
     */
    @Override
    public void start(Stage primaryStage) throws Exception {
        AnchorPane root = new AnchorPane();
        root.setStyle("-fx-background-color: #23475f");

        Node canvas = canvasGl(fxglTransfer);
        AnchorPane.setTopAnchor(canvas, 0.0);
        AnchorPane.setLeftAnchor(canvas, 0.0);
        AnchorPane.setRightAnchor(canvas, 0.0);
        AnchorPane.setBottomAnchor(canvas, 0.0);
        root.getChildren().add(canvas);

        wireMouse(canvas, fxglTransfer);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /**
     * 建立 GL 画布并挂上逐帧回调。
     *
     * <p>{@code onFrame} 的 lambda <strong>在 GL 线程执行</strong>，每帧按固定顺序做三件事：
     * 把 JavaFX 线程投递过来的新矩形注册并加入场景 → 清背景 → 画所有几何。
     * 顺序不能变：先 drain 才能保证"这一帧点下去，这一帧就能被拾取"。
     *
     * @param fxglTransfer 桥接对象，负责 GL 上下文与 JavaFX 节点之间的生命周期
     * @return 可直接放进场景图的 JavaFX 节点
     */
    private Node canvasGl(FXGLTransfer fxglTransfer) {
        fxglTransfer.onFrame(gc -> {
            gc.setAntialias(true);
            drainRequests(gc);
            clearBackground(gc);
            drawGeometry(gc);
            return Unit.INSTANCE;
        });
        return fxglTransfer.createGlFXView();
    }

    /**
     * 把鼠标事件接到画布节点上。
     *
     * <p>JavaFX 的事件处理器是<strong>可叠加</strong>的：如果框架或其它组件
     * 也在这个节点上挂了同名事件（比如 {@code trackChartHover}），
     * 两者都会各自收到事件，互不影响 —— 不必担心"把别人的覆盖掉"。
     */
    private void wireMouse(Node node, FXGLTransfer bridge) {
        node.addEventHandler(MouseEvent.MOUSE_MOVED, e -> onMove(e, bridge, node));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            // 指针离开画布：清掉预览位置，否则那个虚线矩形会僵在最后的位置上。
            pointerX = Float.NaN;
            pointerY = Float.NaN;
            // 只有"手上正握着第一角"时才需要重绘：否则屏幕上没有任何东西随指针消失。
            if (!Float.isNaN(anchorX)) {
                bridge.repaint();
            }
        });
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> onClick(e, bridge, node));
    }

    // ---------- 拾取 ----------

    /**
     * 鼠标移动：更新预览位置（设备像素）。
     *
     * <p><strong>这里的 {@code * scale} 是必需的</strong>：预览与落地矩形必须落在
     * 同一个坐标系里（设备像素）。少乘这一下，两者会相差一个窗口缩放系数 ——
     * 这正是"看起来只是有点偏"的那类错误。
     *
     * <p>只在"已定第一角"时触发重绘：光标移动本身不改变画面（没有 hover 效果），
     * 每动一下都重绘纯属浪费。
     */
    private void onMove(MouseEvent event, FXGLTransfer bridge, Node canvas) {
        double scale = bridge.deviceScale(canvas);
        pointerX = (float) (event.getX() * scale);
        pointerY = (float) (event.getY() * scale);
        if (!Float.isNaN(anchorX)) {
            bridge.repaint();
        }
    }

    /**
     * 鼠标点击分派：左键画矩形（两点式），右键拾取。
     *
     * <p>两件事的坐标来源不同，别写混：
     * <ul>
     *   <li><strong>绘制</strong>要的坐标得当场算出来（下面的 {@code * scale}），
     *       因为"这一下点在哪"必须立刻决定两个对角；</li>
     *   <li><strong>拾取</strong>同样用 {@code * scale} 后的设备像素 ——
     *       本类改成几何自解析，坐标必须与 {@code rectangles} 里的存储一致。</li>
     * </ul>
     *
     * <p>左键两点式：
     * <ol>
     *   <li>第一次点：定第一个角（此时不提交任何矩形，另一个角要等第二下）；</li>
     *   <li>第二次点：与第一角一起构成矩形，提交后自动 {@link #cancelAnchor()}；</li>
     *   <li>两点重合或共线 → 面积 0，明确报错而不是静默什么都不做。</li>
     * </ol>
     */
    private void onClick(MouseEvent event, FXGLTransfer bridge, Node canvas) {
        double scale = bridge.deviceScale(canvas);

        if (event.getButton() == MouseButton.SECONDARY) {
            // 拾取用几何自解析：框架的 clickAsyncAtNode 是"单点单 ID"，
            // 表达不了"叠在一起的多个矩形"，而这正是同点轮换选择的前提。
            float dx = (float) (event.getX() * scale);
            float dy = (float) (event.getY() * scale);
            handlePickAt(dx, dy);
            bridge.repaint(); // 让选中高亮本帧生效
            return;
        }

        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        float dx = (float) (event.getX() * scale);
        float dy = (float) (event.getY() * scale);

        if (Float.isNaN(anchorX)) {
            // 第一下：定第一个角。此时还画不出矩形 —— 另一个角要等第二下。
            anchorX = dx;
            anchorY = dy;
            System.out.printf(Locale.ROOT,
                    "已定第一角 (%.0f,%.0f)，再点一下确定对角（Esc 取消）%n", dx, dy);
        } else {
            // 第二下：与第一角共同确定矩形。
            // 谁在左上、谁在右下由用户决定，几何归一化交给 Rectangle 内部消化。
            float w = Math.abs(dx - anchorX);
            float h = Math.abs(dy - anchorY);
            if (w > 0f && h > 0f) {
                pendingRectangles.add(new Rectangle(
                        new Point2D(anchorX, anchorY),
                        new Point2D(dx, dy)));
                System.out.printf(Locale.ROOT, "已画：矩形 %.0f×%.0f%n", w, h);
            } else {
                // 两点在同一水平线或同一竖直线 → 面积 0，不是一个矩形。
                System.out.println("两点重合或共线，没有形成矩形（矩形要求宽和高都大于 0）");
            }
            cancelAnchor();
        }
        bridge.repaint();
    }

    /**
     * 处理右键拾取：找出点击位置下所有矩形，并在"同一点重复点击"时循环切换。
     * <strong>在 JavaFX 线程上执行</strong>。
     *
     * <p>命中列表按 z 序排好：{@code index 0} 是最后画的那一个（视觉上最上面）。
     * 第一次点某个位置 → 选最上面的；再点同一个位置 → 选列表里的下一个；
     * 到末尾回卷到第一个。换位置则重新从最上面的开始。
     *
     * @param dx 点击位置 X（设备像素）
     * @param dy 点击位置 Y（设备像素）
     */
    private void handlePickAt(float dx, float dy) {
        List<Rectangle> hits = collectHits(dx, dy);

        if (hits.isEmpty()) {
            selectedId = 0;
            resetPickCycle();
            System.out.println("未命中：那里没有可拾取的矩形（选中已清空）");
            return;
        }

        boolean sameSpot = !Float.isNaN(lastPickX)
                && Math.hypot(dx - lastPickX, dy - lastPickY) <= SAME_SPOT_EPS;

        int nextIdx;
        if (sameSpot) {
            // 在当前选中项之后选下一个；如果当前选中已不在候选里（比如新建矩形后
            // 覆盖了位置），indexOfPickId 返回 -1，则回到 0。
            int cur = indexOfPickId(hits, selectedId);
            nextIdx = (cur + 1) % hits.size();
        } else {
            // 新位置：取最上面的那个。
            nextIdx = 0;
        }

        Rectangle chosen = hits.get(nextIdx);
        selectedId = chosen.pickId;
        lastPickX = dx;
        lastPickY = dy;

        if (hits.size() > 1) {
            System.out.printf(Locale.ROOT,
                    "[点击] 图形已点击（%d/%d）：%s%n",
                    nextIdx + 1, hits.size(), chosen.describe());
        } else {
            System.out.println("[点击] 图形已点击：" + chosen.describe());
        }
    }

    /**
     * 收集点击位置下的所有可拾取矩形。返回值按 z 序排列：
     * {@code index 0} 是最后画的（即视觉上在最上面），
     * 因为 {@link #rectangles} 按绘制先后追加，倒着遍历即可。
     *
     * <p>在 JavaFX 线程读 {@link #rectanglesView}：它是 GL 线程整份发布的 volatile 快照，
     * 整份读取天然一致，不需要加锁；<strong>不要</strong>改成读 {@link #rectangles}。
     *
     * @param dx 点击位置 X（设备像素）
     * @param dy 点击位置 Y（设备像素）
     * @return 命中列表（z 序，最上面在前）；无命中时返回空列表而非 null
     */
    private List<Rectangle> collectHits(float dx, float dy) {
        List<Rectangle> view = rectanglesView;
        List<Rectangle> hits = new ArrayList<>();
        for (int i = view.size() - 1; i >= 0; i--) {
            Rectangle r = view.get(i);
            if (r.pickId != 0 && r.contains(dx, dy, PICK_PADDING)) {
                hits.add(r);
            }
        }
        return hits;
    }

    /**
     * 清掉"上一次拾取位置"。
     * <p>未命中后调用：避免下次再点同一点时又"接着上一个位置切"。
     * 将来切模式、清空场景时也应调用同一处。
     */
    private void resetPickCycle() {
        lastPickX = Float.NaN;
        lastPickY = Float.NaN;
    }

    // ---------- 渲染 ----------

    /**
     * 取消"已定的第一角"。四条语义路径共用：提交后、Esc、切模式、清空。
     * <p>设计成单一入口，是为了将来加"画矩形中途切工具要联动取消"这类逻辑时，
     * 只有一处需要改。
     */
    private void cancelAnchor() {
        anchorX = Float.NaN;
        anchorY = Float.NaN;
    }

    /**
     * 「几何图形」档的绘制总入口：已落地的矩形 → 选中高亮 → 两点式预览
     * （顺序即 z 序，后画的盖在前面）。
     *
     * <p><strong>在 GL 线程上执行</strong>，不要在这里碰任何 JavaFX 控件。
     * 这里的 {@code selectedId} 是 volatile 读：JavaFX 线程改了它，
     * 下一帧就能看到，不需要额外的同步。
     *
     * @param gc 当前帧的绘制上下文
     */
    private void drawGeometry(Gc gc) {
        for (Rectangle rectangle : rectangles) {
            if (rectangle.pickId != 0) {
                // pickable 是 save/pickId/restore 的作用域版本：块内改的颜色、线宽
                // 在块结束时全部回滚，因此"忘了复位 pickId"这类错误不可能发生。
                gc.pickable(rectangle.pickId, () -> {
                    drawOneRectangle(gc, rectangle);
                    return Unit.INSTANCE;
                });
            } else {
                // 尚未注册（理论上只在排空之前的那一帧）——画出来但点不中。
                drawOneRectangle(gc, rectangle);
            }
        }

        // 选中高亮画在所有矩形之后，此时 pickId 已被 pickable 复原成 0。
        // 高亮本身绝不能参与拾取，所以显式 setPickId(0)。
        int sel = selectedId;
        if (sel != 0) {
            for (Rectangle rectangle : rectangles) {
                if (rectangle.pickId == sel) {
                    gc.save();
                    gc.setPickId(0);
                    gc.setStroke(0xFFFFEB3B);
                    gc.setLineWidth(3f);
                    gc.setDashPattern(null);  // 防止继承上一帧末态的虚线样式
                    gc.strokeRect(
                            (float) (rectangle.left() - SELECT_OUTSET),
                            (float) (rectangle.top() - SELECT_OUTSET),
                            (float) (rectangle.width() + SELECT_OUTSET * 2f),
                            (float) (rectangle.height() + SELECT_OUTSET * 2f));
                    gc.restore();
                    break;
                }
            }
        }

        drawPreview(gc);
    }

    /**
     * 两点式预览：已定第一角时画<strong>虚线矩形</strong>（跟随鼠标）+ 第一角十字。
     * <strong>GL 线程</strong>。
     *
     * <p>矩形由"第一角"和"当前指针"两点确定，<em>不是</em>像圆那样由半径决定。
     * 所以这里算的是归一化后的 left/top/width/height，指针在锚点哪一侧都不影响。
     *
     * <p>{@code pickId = 0} 写在 {@code save()} 之后：{@code save} 会保存并恢复
     * {@code pickId}，所以"块内不设"等于<strong>继承外层的值</strong>——
     * 不显式置 0 的话，预览会带着循环里最后一个矩形的 ID 被画出来，
     * 于是"点预览也能选中它"。
     *
     * <p>指针还没进过画布时（{@code pointerX/Y} 为 NaN）退化成"第一角 + 指针同点"：
     * 只画十字，不画矩形 —— 此时矩形应该"还没来得及长出来"。
     */
    private void drawPreview(Gc gc) {
        float ax = anchorX;
        float ay = anchorY;
        if (Float.isNaN(ax) || Float.isNaN(ay)) {
            return;
        }
        // 还没动过鼠标时宽高取 0：只画第一角十字，不画矩形。
        float px = pointerX;
        float py = pointerY;
        if (Float.isNaN(px) || Float.isNaN(py)) {
            px = ax;
            py = ay;
        }

        gc.save();
        gc.setPickId(0);

        // 第一角十字：让用户随时知道"手上那个角在哪"。
        gc.setStroke(0x99D7E3F4);
        gc.setLineWidth(1f);
        gc.drawLine(ax - 10f, ay, ax + 10f, ay);
        gc.drawLine(ax, ay - 10f, ax, ay + 10f);

        // 归一化：指针不管在锚点的哪一侧，都得到"左上角 + 正宽高"。
        float l = Math.min(ax, px);
        float t = Math.min(ay, py);
        float w = Math.abs(px - ax);
        float h = Math.abs(py - ay);
        if (w > 0f && h > 0f) {
            gc.setDashPattern(new float[]{6f, 6f});
            gc.setStroke(0x99D7E3F4);
            gc.setLineWidth(1f);
            gc.strokeRect(l, t, w, h);
            // `dashPattern` 是 **save/restore 状态栈里的一员**（与 lineWidth 并列），
            // 而这里本来就在 drawPreview 的 save/restore 里，所以其实不复位也安全。
            // 显式复位是为了让"这一段用虚线、之后不用"在读代码时一眼可见。
            gc.setDashPattern(null);
        }
        gc.restore();
    }

    /**
     * 把 JavaFX 线程投递过来的新矩形"落盘"：注册拾取号、加入 {@link #rectangles}、
     * 刷新 {@link #rectanglesView} 快照。<strong>GL 线程</strong>，每帧在绘制前调用一次。
     *
     * <p>为什么这一帧才注册拾取号：拾取注册表在 GL 侧，必须由 GL 线程访问。
     * 号从 1 开始（0 永远表示"不参与拾取"，见 {@code selectedId} 与
     * {@link #clearBackground} 的约定）。
     *
     * <p>快照只在<strong>真的有变化时</strong>才重新发布，避免每帧都做一次
     * {@code List.copyOf} 的无谓分配。
     */
    private void drainRequests(Gc gc) {
        Rectangle arrived;
        boolean changed = false;
        while ((arrived = pendingRectangles.poll()) != null) {
            arrived.pickId = gc.getPickRegistry().register(arrived);
            rectangles.add(arrived);
            changed = true;
        }
        if (changed) {
            // 发布只读快照给 JavaFX 线程做几何拾取。
            // 浅拷贝即可：Rectangle.pickId 注册后不再改动，元素本身不会在 GL 线程被改字段。
            // volatile 写 + 之后的 volatile 读保证可见性。
            rectanglesView = List.copyOf(rectangles);
        }
    }

    // ---------- 矩形 ----------

    /**
     * 矩形的几何与身份。
     *
     * <p>字段存的是<strong>起终点</strong>（用户实际按下的那两点），不是 left/top/width/height。
     * 原因有两层：
     * <ul>
     *   <li>"从哪拖到哪"是用户意图的原始记录，将来做"选中后可以拖角改大小"时，
     *       需要知道哪个角是原来的锚点；</li>
     *   <li>left/top/width/height 是<em>派生量</em>——留成 {@code min/max} 之类的计算方法，
     *       就不会出现"改了起点忘了同步 left"这类必须靠纪律维持的一致性。</li>
     * </ul>
     *
     * <p>几何字段全部 {@code final}（除 {@link #pickId}）：一旦落地就不该再变，
     * 要动就整个换一个新对象 —— 这样跨线程共享快照时没有任何隐患，
     * 与 {@code Circle} 的理由相同。
     */
    private static final class Rectangle {

        /** 起点（设备像素）：用户按下的那一点。 */
        private final Point2D startingPoint;

        /** 终点（设备像素）：用户第二次点击的那一点。 */
        private final Point2D endingPoint;

        /** 拾取号；0 表示尚未注册，此时不参与拾取。 */
        int pickId;

        /**
         * @param startingPoint 起点（设备像素），不能为 null
         * @param endingPoint   终点（设备像素），不能为 null
         */
        Rectangle(Point2D startingPoint, Point2D endingPoint) {
            this.startingPoint = startingPoint;
            this.endingPoint = endingPoint;
        }

        /** 左边界（设备像素）：两点 X 的较小者。矩形方向由用户决定，不假定"起点在左上"。 */
        private double left() {
            return Math.min(startingPoint.getX(), endingPoint.getX());
        }

        /** 上边界（设备像素）：两点 Y 的较小者。 */
        private double top() {
            return Math.min(startingPoint.getY(), endingPoint.getY());
        }

        /** 右边界（设备像素）：两点 X 的较大者。 */
        private double right() {
            return Math.max(startingPoint.getX(), endingPoint.getX());
        }

        /** 下边界（设备像素）：两点 Y 的较大者。 */
        private double bottom() {
            return Math.max(startingPoint.getY(), endingPoint.getY());
        }

        /** 宽度（设备像素）：恒 ≥ 0。绘制与拾取都吃这个派生量，方向已在 {@link #left()} 里消化。 */
        private double width() {
            return right() - left();
        }

        /** 高度（设备像素）：恒 ≥ 0。 */
        private double height() {
            return bottom() - top();
        }

        /**
         * 点是否落在矩形内（{@code padding} 是给拾取留的额外容差）。
         *
         * <p>用<strong>四边比较</strong>而不是"先算中心再算半宽半高"：
         * 前者对"起终点方向相反"的情形（比如从右下往左上拖）天然免疫，
         * 后者需要额外判断是正宽还是负宽，多一处出错机会。
         *
         * <p>与 {@code Circle.contains} 一样，容差是"各方向都向外扩 {@code padding}"：
         * 圆是半径加 {@code padding}，矩形是四条边各向外推 {@code padding}，
         * 两者在贴边点击时的手感才一致。
         *
         * @param x       待测点 X（设备像素）
         * @param y       待测点 Y（设备像素）
         * @param padding 容差（设备像素）；取边框线宽的一半左右即可
         * @return 命中返回 {@code true}
         */
        boolean contains(float x, float y, float padding) {
            double l = left() - padding;
            double t = top() - padding;
            double r = right() + padding;
            double b = bottom() + padding;
            return x >= l && x <= r && y >= t && y <= b;
        }

        /**
         * 供日志/调试用的可读描述，不参与任何逻辑。
         *
         * <p>打印的是归一化后的 (left, top)-(right, bottom)，而不是原始起终点：
         * 日志的用途是"知道这块矩形在哪"，归一化坐标才是画布上的实际位置；
         * 原始起终点是交互过程，调试交互流程时再另说。
         *
         * <p>{@code Locale.ROOT} 与 {@code Circle.describe} 同因：
         * 让小数点永远是 "."，免得在德语/法语环境下日志里的 {@code 12,5} 看起来像两个数。
         */
        String describe() {
            return String.format(Locale.ROOT, "矩形 (%.0f,%.0f)-(%.0f,%.0f)",
                    left(), top(), right(), bottom());
        }
    }
}