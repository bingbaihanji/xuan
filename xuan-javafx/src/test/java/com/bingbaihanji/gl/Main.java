package com.bingbaihanji.gl;

import com.bingbaihanji.xuan.chart.ArrayChartData;
import com.bingbaihanji.xuan.chart.Axis;
import com.bingbaihanji.xuan.chart.AxisRange;
import com.bingbaihanji.xuan.chart.AxisStyle;
import com.bingbaihanji.xuan.chart.AxisType;
import com.bingbaihanji.xuan.chart.Chart;
import com.bingbaihanji.xuan.chart.ChartInsets;
import com.bingbaihanji.xuan.chart.ChartInteractionConfig;
import com.bingbaihanji.xuan.chart.ChartLayout;
import com.bingbaihanji.xuan.chart.ChartTextMetrics;
import com.bingbaihanji.xuan.chart.ChartType;
import com.bingbaihanji.xuan.chart.Series;
import com.bingbaihanji.xuan.glview.FXGLTransfer;
import com.bingbaihanji.xuan.renderer.Gc;
import com.bingbaihanji.xuan.renderer.PickHit;
import com.bingbaihanji.xuan.text.FontFile;
import com.bingbaihanji.xuan.util.Rect;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import kotlin.Unit;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Xuan Java API 示例。
 *
 * <p>窗口顶部的下拉框用于选择当前演示模块。每一帧只调用一个绘制方法，
 * 因此几何图形、文字和图表不会同时显示。
 *
 * <h2>「几何图形」档：两点式画圆 + 点击拾取</h2>
 *
 * <p>这一档演示两块能力，它们也是本示例里唯一需要鼠标交互的部分：
 *
 * <pre>
 * 左键点第一下 → 记下圆心，虚线圆跟随鼠标（圆心处画十字）
 * 左键点第二下 → 落圆，半径 = 圆心到这一下的距离
 * Esc          → 取消已定的圆心
 * 右键单击     → 拾取（点中哪个圆，控制台就打一行）
 * 右键点空白   → 清空选中
 * </pre>
 *
 * <p><strong>为什么拾取在右键</strong>：左键已经被两点式绘制占满了——它既要"定圆心"
 * 又要"定半径"，没有第三个位置留给"选中"。这与 {@code example/demo/XuanDemo.kt} 的
 * 惯用一致（那边也是"左键画、右键只按不拖 = 拾取"）。
 *
 * <h2>三处最容易抄错的接线</h2>
 *
 * <ol>
 *   <li><strong>坐标换算</strong>：鼠标事件给的是节点的<em>逻辑</em>局部坐标，
 *       而 {@code Gc} 要的是<em>设备像素</em>，两者差一个窗口输出缩放系数
 *       （本机 125% 下是 1.25）。{@code clickAsyncAtNode} 内部替你乘好了，
 *       但<strong>预览要自己乘</strong>（{@link FXGLTransfer#deviceScale(Node)}）——
 *       漏乘的表现是"预览圆与落地圆错开"，在 100% 缩放的机器上完全看不出来。</li>
 *   <li><strong>拾取走的是点击队列</strong>（有界 FIFO、按序交付），不是
 *       {@code pickAsync} 的"最新覆盖旧的"——后者给 hover/拖拽用，
 *       把点击接在上面会<strong>静默丢点击</strong>。这里用
 *       {@code clickAsyncAtNode}（一次点击语义）。</li>
 *   <li><strong>注册表只在 GL 线程碰</strong>：见 {@link #drainRequests}。
 *       {@code FXGLTransfer.gc} 字段不是 volatile，XuanDemo 在自己的
 *       {@code commitTwoPointShape} 里从 JavaFX 线程直接读它注册（那边的 KDoc 承认
 *       那是数据竞争）；示例代码会被照抄，所以这里走"并发队列 + GL 线程排空"。</li>
 * </ol>
 *
 * <p><strong>运行方式</strong>：和 {@code CLAUDE.md} 里那几条**不一样**，照抄会
 * {@code ClassNotFoundException}——因为这个类在 {@code src/test/java} 下：
 *
 * <ol>
 *   <li>{@code mvn -o compile} **不编译**它（编译 test 源码是 {@code test-compile} 阶段的事），
 *       所以必须先 {@code mvn -o test-compile}；</li>
 *   <li>{@code classpathScope} 必须是 {@code test} 而不是 {@code runtime}——
 *       后者的 classpath 里**没有** {@code target/test-classes}。</li>
 * </ol>
 *
 * <pre>
 * mvn -o install -DskipTests        # 先在仓库根执行一次
 * cd xuan-javafx
 * mvn -o test-compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.gl.Main"
 * </pre>
 *
 * <p>仍然必须是 {@code exec:exec}：{@code exec:java} 的类加载器会让 openglfx 链接到另一份
 * {@code GraphicsPipeline}，启动即抛 {@code Could not detect pipeline}。
 */
public final class Main extends Application {

    private static final int WIDTH = 1100;

    private static final int HEIGHT = 760;

    /** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是这个值。 */
    private static final int BG = 0xFF18212B;

    /** 已落地圆的半透明填充。 */
    private static final int CIRCLE_FILL = 0x554FC3F7;

    /** 已落地圆的描边。 */
    private static final int CIRCLE_STROKE = 0xFF4FC3F7;

    /** 两点式预览的颜色（虚线圆与圆心十字共用）。 */
    private static final int PREVIEW = 0x99D7E3F4;

    /** 选中高亮框。 */
    private static final int HIGHLIGHT = 0xFFFFEB3B;

    /** 预览虚线的单段长度（设备像素）。 */
    private static final float PREVIEW_DASH = 6f;

    /** 圆心十字的臂长（设备像素）。 */
    private static final float CENTER_CROSS = 10f;

    /** JavaFX 线程写入、GL 线程在下一帧排空。 */
    private final Queue<Circle> pendingCircles = new ConcurrentLinkedQueue<>();

    // ------------------------------------------------------------------
    // 绘制状态
    //
    // 分工是**按线程**划的，不是按"谁方便"：
    //  - 下面这几个标量由 JavaFX 线程写、GL 线程读，所以一律 volatile；
    //  - circles 列表**只有 GL 线程碰**，JavaFX 线程通过 pendingCircles
    //    （并发队列）与 clearRequested（volatile 标志）把意图交过去。
    //    这样 unregister 与列表增删都只发生在 GL 线程上。
    // ------------------------------------------------------------------

    /** 已落地的圆。**只有 GL 线程读写**（见上面的分工说明）。 */
    private final List<Circle> circles = new ArrayList<>();

    /** JavaFX 线程修改，GL 线程读取。 */
    private volatile DemoMode selectedMode = DemoMode.GEOMETRY;

    /** 两点式的第一点（圆心）；NaN 表示"手上没有已定的圆心"。 */
    private volatile float anchorX = Float.NaN;

    private volatile float anchorY = Float.NaN;

    /** 预览用的鼠标位置（设备像素）；NaN 表示指针不在画布上。 */
    private volatile float pointerX = Float.NaN;

    private volatile float pointerY = Float.NaN;

    /** 当前选中的拾取号；0 表示没有选中（0 也是"什么都没命中"的返回值）。 */
    private volatile int selectedId = 0;

    /** JavaFX 线程置位、GL 线程消费：请求清空画布。 */
    private volatile boolean clearRequested = false;

    private FXGLTransfer bridge;

    private Chart chart;

    private Node canvas;

    private Label status;

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }

    /** 清理当前帧背景，保证切换下拉框后不会残留上一种组件。 */
    private static void clearBackground(Gc gc) {
        gc.setPickId(0);        // 背景不属于任何可拾取对象
        gc.setFill(BG);
        gc.fillRect(0f, 0f, gc.getWidth(), gc.getHeight());
    }

    // ------------------------------------------------------------------
    // 鼠标接线
    // ------------------------------------------------------------------

    /** 画一个已落地的圆：半透明填充 + 实线描边。 */
    private static void drawOneCircle(Gc gc, Circle circle) {
        gc.setFill(CIRCLE_FILL);
        gc.fillCircle(circle.cx, circle.cy, circle.r);
        gc.setStroke(CIRCLE_STROKE);
        gc.setLineWidth(2f);
        gc.strokeCircle(circle.cx, circle.cy, circle.r);
    }

    /** 绘制文字，y 坐标是文本基线。 */
    public static void drawText(Gc gc) {
        gc.setFill(0xFFF4F7FB);
        gc.setFontSize(34f);
        gc.drawText("Xuan Java API", 90f, 180f);

        gc.setFill(0xFFB7C4D3);
        gc.setFontSize(18f);
        gc.drawText("SDF Text Rendering", 90f, 225f);
        gc.drawText("中文文本 / Unicode / GPU Atlas", 90f, 280f);
    }

    /** 构造粉色面积图及其 tooltip 配置。 */
    private static Chart createChart() {
        double[] xValues = new double[12];
        double[] values = {42, 48, 51, 47, 62, 75, 81, 78, 88, 95, 102, 118};
        for (int i = 0; i < xValues.length; i++) {
            xValues[i] = i;
        }

        AxisRange xRange = new AxisRange(0, 11, "月份", "月");
        AxisRange yRange = new AxisRange(0, 130, "数量", "件");
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{xRange, yRange},
                new double[][]{xValues, values});

        Chart chart = new Chart(
                new Axis(AxisType.LINEAR, xRange),
                new Axis(AxisType.LINEAR, yRange));

        chart.title("月度数量统计")
                .axisTitlesVisible(true)
                .legendVisible(true)
                .padding(ChartInsets.uniform(8f))
                // ★ 网格 / 轴线 / 箭头 / 刻度线 / 刻度文字**全部由库画**。
                //   `AxisStyle.defaults()` 的 visible 是 false（默认关是承重约束，
                //   见它的类文档），所以要显式打开。
                //
                //   打开之后**刻度预留也由库自己算**，`chart.tickLabelReserve(...)` 会被
                //   覆盖——所以这里不需要（也不应该）再声明一次。
                //   以前这里没声明过预留，于是刻度文字是画在没留过位置的绘图区之外的。
                .axisStyle(AxisStyle.defaults().visible(true));

        chart.addLayer("统计")
                .add(new Series("数量", data, ChartType.AREA)
                        .color(0xFFFF5C9A)
                        .lineWidth(2f)
                        .baseline(0f)
                        .fillAlpha(0.42f)
                        // ★ 平滑曲线：框架里已有（着色器侧 Catmull-Rom），不是应用层要画的东西。
                        //   对 AREA 而言**只有顶边**平滑，基线那一条照旧是直的。
                        //   认它的图型：LINE / LINE_AND_MARKERS / AREA；
                        //   忽略它的是 STEP / SPECTRUM / SCATTER / BAR（不报错，
                        //   因为"阶梯的平滑该长什么样"没有答案）。
                        .smooth(true));

        chart.interaction().setConfig(
                ChartInteractionConfig.defaults()
                        .crosshairColor(0xC8FF9BC0)
                        .tooltipColors(0xF02D1F2B, 0xFFFF7CAB, 0xFFFFF3F8)
                        .tooltipFontSize(18)
                        .formatter((value, range) -> String.format(Locale.ROOT, "%.2f", value))

        );
        // 设置文字大小
        chart.axisTitleFontSize(18);

        return chart;
    }

    @Override
    public void start(Stage stage) {
        // 字体由本库**不再自带**（2026-09-28）：从 `-Dxuan.text.font=<路径>` 取。
        // 没设就传 null —— 那时 drawText 会抛出并说明怎么补（不是静默不画）。
        try (InputStream fontInputstream = new FileInputStream("D:\\字体\\Fira_Code_v6.2\\ttf\\FiraCode-Light.ttf")) {
            bridge = new FXGLTransfer(FontFile.load(fontInputstream));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        chart = createChart();

        // GL 线程每帧只执行当前下拉框对应的绘制方法。
        bridge.onFrame(gc -> {
            // ★ 抗锯齿（解析式）。**这一个开关管两拨消费者**：① Gc 自己的描边；
            //   ② 六个图表渲染器（经 `Gc.charts` 那个 supplier 透传给每个系列的 uAntialias）。
            //   图表曲线的锯齿就是它管的。
            //
            //   **默认是关的**（`Gc.antialias` 初值 false），所以"看着有锯齿"是默认行为。
            //
            //   ⚠️ 它必须**每帧设**：`antialias` 是 `save`/`restore` 状态栈里的一员，
            //   而本帧的绘制里到处都有 save/restore（选中高亮、预览、图表装饰…），
            //   任何一次 restore 都会把值还原回压栈时的样子。
            //   （`XuanDemo` 为此每帧把菜单开关拷进 gc，同一条理由。）
            //
            //   ⚠️ **它对"填充"无效**：`fillRect`/`fillCircle`/面积图的填充这类**面**，
            //   几何里没有"中心线"，解析式算不出边距——那要靠构造时的 `msaa`
            //   （见类文档里 MSAA 那段）。所以面积图的**顶边**（描边）会变平滑，
            //   而它的**填充边缘**不会——两者是两套机制。
            gc.setAntialias(true);
            // 先兑现 JavaFX 线程交过来的意图，再画——否则刚落下的圆要等下一帧才看得见。
            drainRequests(gc);
            clearBackground(gc);
            switch (selectedMode) {
                case GEOMETRY -> drawGeometry(gc);
                case TEXT -> drawText(gc);
                case CHART -> drawChart(gc);
            }
            return Unit.INSTANCE;
        });
        bridge.onError(error -> {
            // ★ 这个处理器**每一次失败都会被调用**，而帧回调里的失败会**每帧重演**
            //   （漏一个 restore() 就每帧都抛）——所以**不要**在这里无条件
            //   `printStackTrace()`：那是每秒 60 段同样的栈，真正有用的第一段立刻被冲走。
            //   库自己已经做了节流（第 1 次与每 60 次打一行摘要到 stderr），
            //   这里再只处理"第一次"，把逐帧重复的噪音交给计数。
            if (bridge.renderFailureCount() == 1) {
                error.printStackTrace();
            }
            return Unit.INSTANCE;
        });

        // 控件先建好再接鼠标：onClick 会写 status，反过来的话
        // "事件先于赋值到达"就是一个 NPE（实践中窗口还没显示，但顺序不该靠这个前提）。
        status = new Label("左键点两下画圆 · 右键点选 · Esc 取消");
        status.setPadding(new Insets(6.0, 10.0, 6.0, 10.0));

        canvas = bridge.createGlFXView();
        bridge.trackChartHover(canvas, chart);
        wireMouse(canvas);

        // JavaFX 控件放在 GL 画布上方，不参与 GL 绘制。
        ComboBox<DemoMode> selector = new ComboBox<>(FXCollections.observableArrayList(DemoMode.values()));
        selector.setValue(DemoMode.GEOMETRY);
        selector.setOnAction(event -> {
            selectedMode = selector.getValue();
            cancelAnchor();          // 切走时丢掉未完成的圆心，免得切回来"手里还攥着一点"
            bridge.repaint();
        });
        selector.setPrefWidth(180);

        Button clear = new Button("清空");
        clear.setOnAction(event -> {
            // 只置一个标志：列表与注册表都归 GL 线程管，见 drainRequests。
            // 状态栏在**本线程**（JavaFX 线程）写，与 GL 线程无关。
            clearRequested = true;
            cancelAnchor();
            selectedId = 0;
            status.setText("已清空");
            bridge.repaint();
        });

        HBox top = new HBox(8, selector, clear);
        top.setPadding(new Insets(8));

        BorderPane root = new BorderPane(canvas);
        root.setTop(top);
        root.setBottom(status);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // Esc 挂在 Scene 上而不是画布上：GLCanvas 默认不可获得焦点，
        // 挂在它上面的话按键先被别的控件吃掉，表现为"Esc 时灵时不灵"。
        scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !Float.isNaN(anchorX)) {
                cancelAnchor();
                status.setText("已取消圆心");
                bridge.repaint();
            }
        });

        stage.setTitle("Xuan Java API Demo");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> bridge.dispose());
        stage.show();
    }

    /**
     * 把鼠标事件接到画布上。
     *
     * <p>{@code trackChartHover} 已经在同一个节点上挂了 {@code MOUSE_MOVED}——
     * JavaFX 的处理器是**可叠加**的，两者互不影响（一个喂图表 hover，一个喂预览圆）。
     */
    private void wireMouse(Node node) {
        node.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMove);
        node.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            // 指针离开画布：清掉预览位置，否则那个虚线圆会僵在最后的位置上。
            pointerX = Float.NaN;
            pointerY = Float.NaN;
            if (!Float.isNaN(anchorX)) {
                bridge.repaint();
            }
        });
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClick);
    }

    // ------------------------------------------------------------------
    // 帧内的请求兑现
    // ------------------------------------------------------------------

    /**
     * 鼠标移动：更新预览位置。
     *
     * <p><strong>这里的 {@code * deviceScale} 是必需的</strong>：预览与落地圆必须落在
     * 同一个坐标系里（设备像素），少乘这一下，两者会相差一个窗口缩放系数——
     * 而这是"看起来只是有点偏"的那类错误。拾取那一侧不用管，{@code clickAsyncAtNode}
     * 内部已经乘过了。
     */
    private void onMove(MouseEvent event) {
        if (selectedMode != DemoMode.GEOMETRY) {
            return;
        }
        double scale = bridge.deviceScale(canvas);
        pointerX = (float) (event.getX() * scale);
        pointerY = (float) (event.getY() * scale);
        if (!Float.isNaN(anchorX)) {
            bridge.repaint();
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 鼠标点击：左键画圆（两点式），右键拾取。
     *
     * <p>两件事的坐标来源不同，别写混：
     * <ul>
     *   <li><strong>绘制</strong>要的坐标得当场算出来（下面那个 {@code * scale}），
     *       因为"这一下点在哪"必须立刻决定圆心/半径；</li>
     *   <li><strong>拾取</strong>把**原始逻辑坐标**交给 {@code clickAsyncAtNode}，
     *       换算归它做——这里再乘一次就错了（乘两遍 = 点 A 命中 B）。</li>
     * </ul>
     */
    private void onClick(MouseEvent event) {
        if (selectedMode != DemoMode.GEOMETRY) {
            return;
        }

        if (event.getButton() == MouseButton.SECONDARY) {
            // 右键只按不拖 = 拾取。回调不在本方法里执行：请求交给 GL 线程，
            // 结果经 Platform.runLater 送回 JavaFX 线程，通常晚一帧。
            bridge.clickAsyncAtNode(canvas, event.getX(), event.getY(), this::onPick);
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }

        double scale = bridge.deviceScale(canvas);
        float dx = (float) (event.getX() * scale);
        float dy = (float) (event.getY() * scale);

        if (Float.isNaN(anchorX)) {
            // 第一下：定圆心。此时还画不出圆——半径要等第二下。
            anchorX = dx;
            anchorY = dy;
            status.setText(String.format(Locale.ROOT,
                    "已定圆心 (%.0f,%.0f)，再点一下确定半径（Esc 取消）", dx, dy));
        } else {
            // 第二下：半径 = 圆心到这一下的距离。
            // 与 XuanDemo.kt 的 commitTwoPointShape 的 CIRCLE 分支**同一条尺规**
            // （那边写的是 hypot(x1-x0, y1-y0)）——改一处要同时改另一处。
            float radius = (float) Math.hypot(dx - anchorX, dy - anchorY);
            if (radius > 0f) {
                pendingCircles.add(new Circle(anchorX, anchorY, radius));
                status.setText(String.format(Locale.ROOT, "已画：圆 r=%.0f", radius));
            } else {
                // 两点重合不算一个圆。明确说原因，而不是静默什么都不做。
                status.setText("两点重合，没有形成圆（圆要求圆心与半径点不重合）");
            }
            cancelAnchor();
        }
        bridge.repaint();
    }

    /**
     * 拾取回调。<strong>在 JavaFX 应用线程上被调用</strong>，可以安全地改控件。
     *
     * <p><strong>返回 {@link Unit} 不是笔误</strong>：Kotlin 的
     * {@code (PickHit?) -> Unit} 在 Java 侧是 {@code Function1<PickHit, Unit>}，
     * 写成 {@code void} 会编译不过（{@code void 无法转换为 kotlin.Unit}）——
     * 这是本仓库所有 Kotlin 回调在 Java 里的统一形状，{@code onFrame} / {@code onError}
     * 也一样，末尾都得 {@code return Unit.INSTANCE}。
     *
     * @param hit 命中结果；未命中时为 null（右键点在空白处）
     * @return {@link Unit#INSTANCE}，仅用于满足 Kotlin 函数类型的签名
     */
    private Unit onPick(PickHit hit) {
        Circle circle = hit == null ? null : (Circle) hit.payload();
        selectedId = circle == null ? 0 : hit.id();

        if (circle == null) {
            status.setText("未命中：那里没有可拾取的圆（选中已清空）");
            return Unit.INSTANCE;
        }
        status.setText("命中：" + circle.describe());
        // ★ 这行 stdout 是"鼠标真的点下去、且命中了对的东西"**唯一能被自动核对**的出口
        //   ——画布上的高亮框既截不了图（没有像素校验器），也分不出"点中了哪一个"。
        //   `ClickDslExample.kt` / `XuanDemo.kt` 都是为这个理由打这一行。
        System.out.println("[点击] 图形已点击：" + circle.describe());
        return Unit.INSTANCE;
    }

    /** 取消"已定的圆心"。四条语义路径共用：提交后、Esc、切模式、清空。 */
    private void cancelAnchor() {
        anchorX = Float.NaN;
        anchorY = Float.NaN;
    }

    /**
     * 把 JavaFX 线程交过来的意图**在 GL 线程上**兑现。每帧开头调一次。
     *
     * <p>为什么要这么绕：{@code FXGLTransfer.gc} 是个**普通字段**（连 volatile 都没有），
     * 而 {@code Gc} 的文档只保证"返回的 {@code Gc} 只能在 GL 线程上使用"。
     * XuanDemo 在 JavaFX 线程上读 {@code transfer.gc().pickRegistry.register(...)} 注册
     * ——它自己的 KDoc 把那称作数据竞争。示例代码会被照抄，所以这里把两件事分干净：
     * <strong>JavaFX 线程只投递意图（并发队列 / volatile 标志），
     * 列表与注册表的一切读写都发生在这一侧。</strong>
     *
     * <p>注册发生在**数据变化时**（这里就是"新落下一个圆"），不是每帧；
     * 清空时对每个圆调 {@code unregister}——不注销的话 {@code pickRegistry}
     * 会一直强引用着它们。
     */
    private void drainRequests(Gc gc) {
        if (clearRequested) {
            clearRequested = false;
            for (Circle circle : circles) {
                if (circle.pickId != 0) {
                    gc.getPickRegistry().unregister(circle.pickId);
                }
            }
            circles.clear();
            selectedId = 0;
        }
        Circle arrived;
        while ((arrived = pendingCircles.poll()) != null) {
            // 载荷就是圆本身：命中回调里拿到的 payload 不需要再查一次表
            // （见 onPick）。号从 1 开始，0 永远表示"不参与拾取"。
            arrived.pickId = gc.getPickRegistry().register(arrived);
            circles.add(arrived);
        }
    }

    /**
     * 「几何图形」档：已落地的圆 → 选中高亮 → 两点式预览（顺序即 z 序）。
     *
     * <p><strong>在 GL 线程上执行</strong>，不要在这里碰任何 JavaFX 控件。
     */
    private void drawGeometry(Gc gc) {
        for (Circle circle : circles) {
            if (circle.pickId != 0) {
                // pickable 是 save/pickId/restore 的作用域版本：块内改的颜色、线宽
                // 在块结束时全部回滚，因此"忘了复位 pickId"这类错误不可能发生。
                gc.pickable(circle.pickId, () -> {
                    drawOneCircle(gc, circle);
                    return Unit.INSTANCE;
                });
            } else {
                // 尚未注册（理论上只在排空之前的那一帧）——画出来但点不中。
                drawOneCircle(gc, circle);
            }
        }

        // 选中高亮画在所有圆之后，此时 pickId 已被 pickable 复原成 0。
        int sel = selectedId;
        if (sel != 0) {
            for (Circle circle : circles) {
                if (circle.pickId == sel) {
                    gc.save();
                    gc.setPickId(0);
                    gc.setStroke(HIGHLIGHT);
                    gc.setLineWidth(3f);
                    float half = circle.r + 6f;
                    gc.strokeRect(circle.cx - half, circle.cy - half, half * 2f, half * 2f);
                    gc.restore();
                    break;
                }
            }
        }

        drawPreview(gc);
    }

    /**
     * 两点式预览：已定圆心时画**虚线圆**（跟随鼠标）+ 圆心十字。**GL 线程**。
     *
     * <p>{@code pickId = 0} 写在 {@code save()} 之后：{@code save} 会保存并恢复
     * {@code pickId}，所以"块内不设"等于**继承外层的值**——不显式置 0 的话，
     * 预览会带着循环里最后一个圆的 ID 被画出来，于是"点预览也能选中它"。
     */
    private void drawPreview(Gc gc) {
        float ax = anchorX;
        float ay = anchorY;
        if (Float.isNaN(ax) || Float.isNaN(ay)) {
            return;
        }
        // 还没动过鼠标时半径取 0：只画圆心十字，不画圆。
        float px = pointerX;
        float py = pointerY;
        if (Float.isNaN(px) || Float.isNaN(py)) {
            px = ax;
            py = ay;
        }

        gc.save();
        gc.setPickId(0);

        gc.setStroke(PREVIEW);
        gc.setLineWidth(1f);
        gc.drawLine(ax - CENTER_CROSS, ay, ax + CENTER_CROSS, ay);
        gc.drawLine(ax, ay - CENTER_CROSS, ax, ay + CENTER_CROSS);

        float radius = (float) Math.hypot(px - ax, py - ay);
        if (radius > 0f) {
            // ★ 虚线圆：**一行状态、一次普通描边**，弧长切分由 Gc 自己做
            //   （`StrokeGenerator` 的虚线能力，2026-09-28 接到公开 API 上）。
            //
            //   在此之前这里有一段手写的弧长切分（约 30 行，还得自己防除零），
            //   `example/demo/DemoShapes.kt` 里还有第二份。两处都是为同一个缺口写的绕法，
            //   现在都删了。
            gc.setDashPattern(new float[]{PREVIEW_DASH, PREVIEW_DASH});
            gc.setStroke(PREVIEW);
            gc.setLineWidth(1f);
            gc.strokeCircle(ax, ay, radius);
            // `dashPattern` 是 **save/restore 状态栈里的一员**（与 lineWidth 并列），
            // 而这里本来就在 drawPreview 的 save/restore 里，所以其实不复位也安全。
            // 显式复位是为了让"这一段用虚线、之后不用"在读代码时一眼可见。
            gc.setDashPattern(null);
        }
        gc.restore();
    }

    /**
     * 绘制粉色面积图。
     *
     * <p>轴长度必须使用 ChartLayout 计算出的真实绘图区尺寸，窗口缩放后刻度才能保持正确。
     */
    public void drawChart(Gc gc) {
        float x = 60f;
        float y = 70f;
        float width = Math.max(1f, gc.getWidth() - 120f);
        float height = Math.max(1f, gc.getHeight() - 130f);
        Rect frame = new Rect(x, y, width, height);

        ChartTextMetrics metrics = new ChartTextMetrics() {

            @Override
            public float width(String text, float fontSize) {
                float old = gc.getFontSize();
                gc.setFontSize(fontSize);
                float result = gc.measureText(text);
                gc.setFontSize(old);
                return result;
            }

            @Override
            public float lineHeight(float fontSize) {
                return fontSize * ChartLayout.LINE_HEIGHT_FACTOR;
            }
        };

        Rect plot = ChartLayout.compute(chart, frame, metrics).plotRect();
        // displayLength 仍然要设：它是"数据值 → 显示位置"的比例基准，
        // 轴与数据系列都靠它（渲染器用的是绘图区边长，本库按比例换算，两者一致）。
        chart.axis(0).setDisplayLength(Math.max(1f, plot.width));
        chart.axis(1).setDisplayLength(Math.max(1f, plot.height));

        // 图表数据是立即绘制的，而背景在 Gc 的批次中等待提交。
        // 先 flush 才能保证 z 序为：背景 -> 图表 -> tooltip，
        // 否则帧末提交的背景会把面积和折线覆盖掉，只留下后画的 tooltip。
        //
        // ★ 从前这里还要在 flush 之前手绘一遍网格与坐标轴、之后手绘一遍刻度文字
        //   （共约 70 行，且别处还有第二份）。现在那三样都由 `chart.axisStyle` 打开
        //   的库内绘制负责，且**画在数据系列之前**——刻度文字在绘图区之外，
        //   而数据被裁在绘图区之内，所以数据盖不住它，不必再分两趟。
        gc.flush();
        gc.getCharts().drawChart(chart, frame, gc.getWidth(), gc.getHeight());
    }

    /** 下拉框中的三个演示模块。 */
    private enum DemoMode {
        GEOMETRY("几何图形"),
        TEXT("文字"),
        CHART("粉色面积图");

        private final String label;

        DemoMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 一个已落地的圆
     *
     * <p>{@code pickId} **由 GL 线程在排空队列时写入**（见 {@link #drainRequests}），
     * 所以它是个可变字段而不是 record 的成员：注册要拿到 {@code Gc}，
     * 而 {@code Gc} 只在 GL 线程上可用。
     */
    private static final class Circle {

        final float cx;

        final float cy;

        final float r;

        /** 拾取号；0 表示尚未注册，此时不参与拾取。 */
        int pickId;

        Circle(float cx, float cy, float r) {
            this.cx = cx;
            this.cy = cy;
            this.r = r;
        }

        String describe() {
            return String.format(Locale.ROOT, "圆 r=%.0f @(%.0f,%.0f)", r, cx, cy);
        }
    }
}
