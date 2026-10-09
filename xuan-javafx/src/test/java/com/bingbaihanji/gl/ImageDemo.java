package com.bingbaihanji.gl;

import com.bingbaihanji.xuan.glview.FXGLTransfer;
import com.bingbaihanji.xuan.renderer.Gc;
import com.bingbaihanji.xuan.renderer.ImageHandle;
import com.bingbaihanji.xuan.renderer.PickHit;
import com.bingbaihanji.xuan.text.FontFile;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import kotlin.Unit;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 图片绘制（{@code Gc.drawImage} / {@code Gc.createImage}）的**目视**示例。
 *
 * <h2>怎么用</h2>
 * <ol>
 *   <li>把 {@link #IMAGE_PATH} 改成你的图片路径（这是整个文件里唯一要改的地方）；
 *       或者**不改源码**，直接在命令行上覆盖：{@code -Dxuan.image.path=D:/图片/test.png}
 *       ——它优先于那个常量，临时验一张图时更顺手；</li>
 *   <li>按下面那段命令跑起来；</li>
 *   <li>先看控制台的报告，再对照它逐块看画面。</li>
 * </ol>
 *
 * <h2>画面分六块，各自在证什么</h2>
 *
 * <pre>
 *  ┌──────────────┬──────────────┬──────────────┬──────────────┐
 *  │ fit          │ 1:1, clipped │ 2x           │ alpha 0.35   │
 *  ├──────────────┼──────────────┼──────────────┴──────────────┤
 *  │ black |white │ click me     │                             │
 *  └──────────────┴──────────────┴─────────────────────────────┘
 * </pre>
 *
 * <p>每一块垫的都是**棋盘格**，所以"哪些像素是透明的"一眼可见。中文说明在控制台，
 * 画面上只有 ASCII 小标签。
 *
 * <ul>
 *   <li><b>fit</b> —— 等比缩放到铺满。看<b>整张图正不正</b>：上下颠倒就是这一块看出来的
 *       （那是纹理坐标 v 写反了），那是本仓库最怕的"看起来完全正常"的一类错。</li>
 *   <li><b>1:1, clipped</b> —— 按原始像素尺寸画，多余部分被面板裁掉。
 *       看<b>1:1 有没有被偷偷缩放</b>（该是一像素宽的线就该是一像素）。</li>
 *   <li><b>2x</b> —— 放大两倍。放大是线性插值，过渡应当平滑。
 *       <b>缩小</b>到很小时出现摩尔纹是<b>已声明的降级</b>（不生成 mipmap），不是缺陷。</li>
 *   <li><b>alpha 0.35</b> —— {@code globalAlpha} 对图片生效。
 *       这一块在绘制前**故意**把 {@code fill} 设成了黑色：图片**不该**因此变黑
 *       （图片的顶点色恒白，只有 {@code globalAlpha} 乘进去；{@code fill} 是环境状态，
 *       给文字设的填充色不该顺手把图片染了）。</li>
 *   <li><b>black | white</b> —— 同一个半透明图像，左半边垫黑底、右半边垫白底。
 *       这是**预乘 alpha** 唯一能目视的地方：漏掉预乘时半透明像素会<b>过亮</b>，
 *       在黑底一侧最明显（本该是 `(128,0,0)` 的地方会糊成 `(255,0,0)`）。
 *       两块里图像的过渡都应当自然，边缘**不该发白**。</li>
 *   <li><b>click me</b> —— 这一份带拾取号，<b>左键点它</b>，控制台打印命中的 ID。
 *       拾取范围是<b>整个矩形、包括全透明的像素</b>（拾取不看 alpha）——
 *       这是**已声明的行为**，不是 bug。</li>
 * </ul>
 *
 * <h2>先看控制台，它比画面更早发现问题</h2>
 * <p>启动时会打印尺寸、颜色种数、以及**半透明像素的占比**。最后一项最要紧：
 * 整张图如果全是 {@code alpha = 255}，「black | white」那一块**看不出任何区别**，
 * 请换一张带透明通道的 PNG，否则那一块是白看。同理，纯色图看不出"上下颠倒"。
 * 这两条都会在控制台里**明确警告**，而不是让你看了一圈以为没毛病。
 *
 * <h2>图片怎么变成 {@code int[]}</h2>
 * <p>用 JDK 自带的 {@code ImageIO} + {@code BufferedImage}，不需要任何第三方库：
 * <pre>
 * BufferedImage src = ImageIO.read(file);
 * int[] argb = src.getRGB(0, 0, w, h, null, 0, w);   // 恰好就是 0xAARRGGBB
 * </pre>
 * {@code getRGB} 吐出的**永远**是默认 sRGB 的 {@code 0xAARRGGBB}
 * （不管源文件是灰度、索引色还是带 alpha 的 PNG），而且**非预乘**——
 * 预乘由库在上传时做。第 0 行是图像的顶行，所以不需要任何翻转。
 * JavaFX 的 {@code PixelReader.getPixels(..., getIntArgbInstance(), ...)} 同理。
 *
 * <h2>运行方式（与 {@code CLAUDE.md} 里那几条不一样，照抄会 ClassNotFoundException）</h2>
 * <p>本类在 {@code src/test/java} 下：{@code mvn -o compile} **不**编译它
 * （编译 test 源码是 {@code test-compile} 阶段的事），而且 {@code classpathScope}
 * 必须是 {@code test}——{@code runtime} 的 classpath 里没有 {@code target/test-classes}。
 *
 * <pre>
 * mvn -o install -DskipTests        # 先在仓库根执行一次
 * cd xuan-javafx
 * mvn -o test-compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp %classpath com.bingbaihanji.gl.ImageDemo"
 * </pre>
 *
 * <p><b>本示例不需要字体</b>：画面上一个汉字都没有（标签是 ASCII），中文全部走控制台。
 * 传 {@code -Dxuan.text.font=<路径>} 只会让标签换成中文字形，不是必需的。
 *
 * <h2>两个键盘操作</h2>
 * <ul>
 *   <li>{@code R} —— **从磁盘重新读一遍**。它演示的是一条必须知道的约定：纹理按
 *       **像素数组的对象身份**缓存，所以"就地改老数组的内容"不会被感知，
 *       而<b>换一个新数组就会重新上传</b>（重新读出来正是一个新数组）。</li>
 *   <li>{@code Esc} —— 退出。用的是"关窗 + `Platform.exit()`"，**不**调
 *       `exitProcess`：那会跑关闭钩子，而钩子里碰 GL 有可能与 JavaFX 自己的关停并发。</li>
 * </ul>
 */
public final class ImageDemo extends Application {

    // ==================================================================
    // ★★★ 唯一要改的地方：把图片路径填在这里 ★★★
    // ==================================================================
    private static final String IMAGE_PATH = "C:\\Users\\bingb\\Pictures\\me\\微信图片_20260801231455_77_50.jpg";
    // ==================================================================

    /**
     * 命令行覆盖用的系统属性：{@code -Dxuan.image.path=...}。
     *
     * <p>它**优先于** {@link #IMAGE_PATH}。加了它就不必为了换一张图改源码再重编译
     * （{@code test-compile} 一趟并不便宜），临时验一张图时尤其顺手：
     *
     * <pre>
     * ... "-Dexec.args=-Dxuan.image.path=D:/图片/test.png -cp %classpath com.bingbaihanji.gl.ImageDemo"
     * </pre>
     */
    private static final String PATH_PROPERTY = "xuan.image.path";

    /**
     * 自动退出用的系统属性：{@code -Dxuan.image.frames=N}（默认 0 = 一直跑）。
     *
     * <p>大于 0 时画满 N 帧就打印一份汇总并按 0/1 退出。它存在的理由是：这个示例平时
     * **不会自己结束**（要人看着窗口），而"能自动跑完并给出退出码"才能当冒烟测试用
     * ——{@code mvn} 里那八个校验器就是靠这个才串得起来。
     *
     * <p>退出码的判据只有一条：**帧回调里有没有抛过异常**（读 {@code renderFailureCount}）。
     * 它证明的是"这个示例的接线没坏"（图读进来了、纹理建出来了、六块都画了），
     * **不是**"画面好看"——那仍然只有眼睛能判。
     */
    private static final String FRAMES_PROPERTY = "xuan.image.frames";

    private static final int WIDTH = 1160;

    private static final int HEIGHT = 640;

    /** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是这个值。 */
    private static final int BG = 0xFF18212B;

    /** 棋盘格的两种颜色。透明像素会露出它，所以"alpha 有没有生效"一眼可见。 */
    private static final int CHECKER_A = 0xFF39424D;

    private static final int CHECKER_B = 0xFF2A323B;

    /** 面板边长、间距、外边距（用户坐标 = 设备像素）。 */
    private static final float PANEL = 250f;

    private static final float GAP = 18f;

    private static final float MARGIN = 22f;

    /** 棋盘格的格子边长。取 25 时 250 正好是整数格（不会切出半格）。 */
    private static final float CHECK_CELL = 25f;

    /** 拾取探针的 ID。点中「click me」那块时会打印它。 */
    private static final int PICK_ID = 9001;

    /** 一次读进来的图片。 */
    private record Loaded(int[] pixels, int width, int height, String name) {
    }

    private FXGLTransfer bridge;

    /** 字体；**可空**（没传 {@code -Dxuan.text.font} 时为 null，那时不画标签）。 */
    private FontFile font;

    /**
     * 当前显示的图片。
     *
     * <p><b>volatile 且整份替换</b>：{@code R} 键在 <b>JavaFX 线程</b>上换掉它，
     * 而绘制跑在 <b>GL 线程</b>上。写成"三个散字段各自 volatile"会读到一个撕裂的组合
     * （新的宽高配上旧的数组），画出来是一张<b>扭曲但完全正常</b>的图——
     * 本仓库最防的形状。换成一个不可变对象的引用，读到的就永远是某一整份。
     */
    private volatile Loaded image;

    /** 拾取那一块用的句柄。**只在 GL 线程读写**（创建与绘制都在那一侧）。 */
    private ImageHandle pickHandle;

    /** 已画帧数，只用来在首帧打一次"上传了几张"。 */
    private int frameCount;

    /** 上传计数的首帧读数，供自动退出时汇总。 */
    private long firstFrameUploads = -1L;

    /** 第二帧的上传计数。**它必须是 0**——那是"没有重传"唯一的判据。 */
    private long secondFrameUploads = -1L;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // ★ 先读图、再建桥，顺序是刻意的：读图失败下面就直接退出了，而 `System.exit`
        //   会跑关闭钩子——让它在 GL/JavaFX 起到一半之前发生，退出才是干净的。
        Loaded loaded;
        try {
            loaded = loadImage(resolvePath());
        } catch (RuntimeException e) {
            System.err.println("\n[图片示例] 无法开始：\n  " + e.getMessage() + "\n");
            System.exit(1);
            return;
        }
        image = loaded;
        reportImage(loaded);

        font = loadFontIfRequested();
        bridge = new FXGLTransfer(font);

        bridge.onFrame(gc -> {
            drawScene(gc);
            return Unit.INSTANCE;
        });
        bridge.onError(error -> {
            // 帧回调里的失败会**每帧重演**（漏一个 restore 就每帧都抛），所以只打第一次，
            // 逐帧重复的交给库自己的计数与节流。
            if (bridge.renderFailureCount() == 1) {
                error.printStackTrace();
            }
            return Unit.INSTANCE;
        });
        // onRender 跑在 endFrame() **之后**，所以这里是安全的收摊时机（见 finish）。
        bridge.onRender(() -> {
            if (pendingFinish) {
                pendingFinish = false;
                finish();
            }
            return Unit.INSTANCE;
        });

        Node canvas = bridge.createGlFXView();
        // onClick 用的是**点击**语义（有界 FIFO、按序交付），并且替你做了
        // "局部坐标 → 设备像素"的换算。不要自己接 MOUSE_CLICKED 再调 pickAsync：
        // 那条是"最新覆盖旧的"，用在点击上会静默丢点击。
        //
        // ⚠️ 这里**不能**写成方法引用 `this::onCanvasClick`：Kotlin 的
        //    `(PickHit?) -> Unit` 在 Java 侧是"返回 Unit 的函数"，而一个 `void`
        //    方法引用**不满足**它——编译器报的是 "void 无法转换为 kotlin.Unit"。
        //    凡是从 Java 传 Kotlin 回调，末尾都得 `return Unit.INSTANCE`。
        bridge.onClick(canvas, hit -> {
            onCanvasClick(hit);
            return Unit.INSTANCE;
        });

        Scene scene = new Scene(new BorderPane(canvas), WIDTH, HEIGHT);
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);

        stage.setTitle("Xuan 图片绘制示例 —— " + loaded.name());
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> bridge.dispose());
        stage.show();

        printLegend(loaded);
    }

    // ------------------------------------------------------------------
    // 读图与自检
    // ------------------------------------------------------------------

    /**
     * 把磁盘上的图片读成 {@code int[]}（{@code 0xAARRGGBB}）。
     *
     * <p>三种失败各自有**不同**的消息，因为它们的排查方向完全不同：路径没填、
     * 文件不存在、以及 <b>{@code ImageIO.read} 返回 null</b>——最后那种是
     * "这个格式没有注册 reader"，与"文件坏了"（那是 {@code IOException}）是两回事。
     * 混成一句"读不了"会让人去查错方向。
     *
     * @throws IllegalArgumentException 上述任一种
     */
    /**
     * 取图片路径：命令行属性优先，其次 {@link #IMAGE_PATH}。
     *
     * <p>两处都是空的话，{@link #loadImage} 会给出"还没有填图片路径"那条消息
     * ——消息里点名的是 {@code IMAGE_PATH}，因为那才是常态入口。
     */
    private static String resolvePath() {
        String fromProperty = System.getProperty(PATH_PROPERTY);
        return (fromProperty != null && !fromProperty.isBlank()) ? fromProperty : IMAGE_PATH;
    }

    private static Loaded loadImage(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "还没有填图片路径。请把 " + ImageDemo.class.getSimpleName() + ".java 顶部的 "
                            + "IMAGE_PATH 改成你的图片路径，例如：\n"
                            + "     private static final String IMAGE_PATH = \"D:/图片/test.png\";");
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException(
                    "找不到这个文件：" + file.getAbsolutePath() + "\n"
                            + "  （IMAGE_PATH 填的是路径本身，别把两侧的引号也填进去；"
                            + "Windows 路径用 / 或 \\\\ 都可以）");
        }
        BufferedImage src;
        try {
            src = ImageIO.read(file);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "读这个文件时出错（文件损坏或权限不足）：" + file.getAbsolutePath(), e);
        }
        if (src == null) {
            throw new IllegalArgumentException(
                    "ImageIO 没有任何 reader 认得这个格式：" + file.getName() + "\n"
                            + "  换一张 PNG / JPG / GIF / BMP。"
                            + "（这一条与「文件损坏」不同：那个会在上面那条路径里抛 IOException。）");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        // ★ 就是这一行拿到本库要的契约：getRGB 吐出的**永远**是默认 sRGB 的
        //   `0xAARRGGBB`（源文件是灰度/索引色/带 alpha 都一样），且是**非预乘**的
        //   ——预乘由库在上传时做。第 0 行是图像的顶行。
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        return new Loaded(argb, w, h, file.getName());
    }

    /**
     * 打印尺寸与三个"这张图能不能用来验证"的读数。
     *
     * <p>后两条读数是**刻意的**：一张全不透明的图看不出预乘，一张纯色图看不出方向，
     * 而那时人会把"看不出区别"读成"没问题"。所以这里提前把话说死。
     */
    private static void reportImage(Loaded img) {
        System.out.println("=== 图片绘制示例 ===");
        System.out.println("  文件      : " + img.name());
        System.out.println("  尺寸      : " + img.width() + " x " + img.height()
                + "（" + img.pixels().length + " 个像素，数组长度与面积相等 ✓）");

        Set<Integer> colors = new HashSet<>();
        int translucent = 0;
        int fullyTransparent = 0;
        for (int p : img.pixels()) {
            colors.add(p);
            int a = (p >>> 24) & 0xFF;
            if (a < 255) {
                translucent++;
            }
            if (a == 0) {
                fullyTransparent++;
            }
        }
        int total = img.pixels().length;
        System.out.println("  颜色种数  : " + colors.size());
        System.out.println("  半透明像素: " + translucent + " / " + total
                + "（" + percent(translucent, total) + "），其中全透明 " + fullyTransparent);

        if (translucent == 0) {
            System.out.println("  ⚠️ 这张图**每个像素都是不透明的** ⇒「black | white」那一块"
                    + "（验预乘 alpha）看不出任何区别。要验它请换一张带透明通道的 PNG。");
        }
        if (colors.size() <= 1) {
            System.out.println("  ⚠️ 这张图只有 " + colors.size() + " 种颜色 ⇒「fit」那一块"
                    + "看不出上下颠倒（翻过来还是同一张）。要验方向请换一张上下不同的图。");
        }
    }

    private static String percent(int part, int total) {
        return total == 0 ? "0%" : String.format("%.1f%%", 100.0 * part / total);
    }

    /**
     * 字体是**可选的**：没传 {@code -Dxuan.text.font} 就不画面板标签，其余照常。
     *
     * <p>路径给了但读不了时**打印警告后继续**——标签只是锦上添花，
     * 而"因为字体加载失败所以连图也看不成"是糟糕的取舍。但**必须打印**：
     * 静默降级会让人以为"这个示例本来就没有标签"。
     */
    private static FontFile loadFontIfRequested() {
        String path = System.getProperty("xuan.text.font");
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return FontFile.load(Path.of(path));
        } catch (RuntimeException e) {
            System.err.println("[图片示例] 字体读不了，面板标签将不显示：" + path + " —— " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    private void drawScene(Gc gc) {
        Loaded img = image;
        gc.setPickId(0);
        gc.setAntialias(true);
        gc.setFill(BG);
        gc.fillRect(0f, 0f, gc.getWidth(), gc.getHeight());
        if (img == null) {
            // 启动时已经退出了，走不到这里；留着只是免得下面到处判空。
            return;
        }

        ensurePickHandle(gc, img);
        drawFitPanel(gc, img);
        drawClipPanel(gc, img);
        drawZoomPanel(gc, img);
        drawAlphaPanel(gc, img);
        drawBackdropPanel(gc, img);
        drawPickPanel(gc, img);

        // ★ 计数必须在**画完之后**读。
        //
        //   它是"自上次取走以来"的累计量，在帧首读到的永远是上一帧的账（首帧就是 0）。
        //   初版写在帧首，于是自检报"首帧上传 0 张"并给了个非 0 退出码——
        //   画面完全正常，错的是读数时机。
        //
        //   六块面板画的是**同一份像素数组**，所以首帧恰好 1 张、第二帧起 0 张。
        //   它证的是"按数组对象身份缓存"真的生效；而"每帧重传"与"只传一次"在画面上
        //   **逐像素相同**——只有这个数分得开。
        if (frameCount == 0) {
            firstFrameUploads = gc.getImageStore().takeUploadedImageCount();
            System.out.println("  首帧上传纹理: " + firstFrameUploads
                    + " 张（六块面板共用一份像素数组 ⇒ 应为 1）");
        } else if (frameCount == 1) {
            secondFrameUploads = gc.getImageStore().takeUploadedImageCount();
            System.out.println("  第二帧上传纹理: " + secondFrameUploads
                    + " 张（同一份数组 ⇒ 应为 0，即没有重传）");
        }

        frameCount++;
        // 只**置一个标志**，真正的收摊留给 onRender——见 finish()。
        int frames = Integer.getInteger(FRAMES_PROPERTY, 0);
        if (frames > 0 && frameCount >= frames) {
            pendingFinish = true;
        }
    }

    /** 见 {@link #finish()}：置位后由 onRender 那一侧收摊。 */
    private boolean pendingFinish;

    /**
     * {@code -Dxuan.image.frames=N} 时画满 N 帧就收摊（默认 0 = 一直跑）。
     *
     * <p><b>为什么不在帧回调里直接收摊</b>：那一刻本帧还没 {@code endFrame()}，
     * 而 {@code dispose()} 会把着色器、VBO 这些从脚下抽掉——之后那次提交就是
     * 往一个已经释放的批处理里写。所以标志在这里置位、动作挪到 {@code onRender}
     * （它跑在 {@code endFrame()} <b>之后</b>，也正是既有那八个校验器落退出码的位置）。
     *
     * <p>顺序是"**先把要自己释放的释放掉，再落退出码**"：{@code dispose()} 同步走完
     * （GL 资源当场归还），之后 {@code Platform.exit()} 就没什么还能与 JavaFX
     * 自己的关停抢的东西了。
     */
    private void finish() {
        Gc gc = bridge.gc();
        int failures = bridge.renderFailureCount();
        int textures = gc == null ? -1 : gc.getImageStore().imageTextureCount();
        boolean ok = failures == 0 && firstFrameUploads == 1
                && secondFrameUploads == 0 && textures >= 1;
        System.out.println();
        System.out.println("=== 自动退出（画了 " + frameCount + " 帧）===");
        System.out.println("  首帧上传纹理数 : " + firstFrameUploads + "（期望 1）");
        System.out.println("  第二帧上传纹理 : " + secondFrameUploads + "（期望 0，即没有重传）");
        System.out.println("  当前缓存纹理数 : " + textures + "（期望 ≥ 1）");
        System.out.println("  帧回调异常次数 : " + failures + "（期望 0）");
        System.out.println(ok
                ? "  ⇒ 接线是通的。**画面好不好仍然要看眼睛**——这个自检不判像素。"
                : "  ⇒ 有硬伤，见上面几行。");
        bridge.dispose();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    /** 面板 (col,row) 的左上角。 */
    private static float panelX(int col) {
        return MARGIN + col * (PANEL + GAP);
    }

    private static float panelY(int row) {
        return MARGIN + row * (PANEL + GAP);
    }

    /** 等比缩放系数：把 {@code w×h} 装进 {@code box×box}。 */
    private static float fitScale(Loaded img, float box) {
        return Math.min(box / img.width(), box / img.height());
    }

    /** 把一块方形区域铺成棋盘格（透明像素会露出它）。 */
    private static void checker(Gc gc, float x, float y, float size) {
        int cells = (int) Math.ceil(size / CHECK_CELL);
        for (int r = 0; r < cells; r++) {
            for (int c = 0; c < cells; c++) {
                gc.setFill(((r + c) & 1) == 0 ? CHECKER_A : CHECKER_B);
                gc.fillRect(x + c * CHECK_CELL, y + r * CHECK_CELL, CHECK_CELL, CHECK_CELL);
            }
        }
    }

    /**
     * 面板外壳：{@code save} + 裁剪到面板矩形 + 铺棋盘底。
     *
     * <p>裁剪是必需的：超出面板的图元**不能**画到邻居身上（1:1 那一块的图往往比面板大）。
     * 它同时让每一块的 {@code fill} / {@code globalAlpha} 改动被 {@link #endPanel} 收回去。
     */
    private static void beginPanel(Gc gc, int col, int row) {
        float x = panelX(col);
        float y = panelY(row);
        gc.save();
        gc.clipRect(x, y, PANEL, PANEL);
        checker(gc, x, y, PANEL);
    }

    private static void endPanel(Gc gc) {
        gc.restore();
    }

    /**
     * 在面板左下角画一行标签；**没加载字体时什么都不做**。
     *
     * <p>标签一律 ASCII：它要在**任意**字体下都可读，而 CJK 字形只有 CJK 字体才有
     * （拿只含拉丁字母的字体去画中文得到的是豆腐块，那比没有标签更让人困惑）。
     * 必须先垫一条半透明压条，否则浅色标签压在浅色图上就看不见了。
     */
    private void label(Gc gc, String text, float x, float y) {
        if (font == null) {
            return;
        }
        float savedSize = gc.getFontSize();
        gc.setFontSize(15f);
        gc.setFill(0xCC000000);
        gc.fillRect(x + 6f, y + 8f, gc.measureText(text) + 14f, 24f);
        gc.setFill(0xFFF1F5F9);
        gc.drawText(text, x + 12f, y + 24f);
        gc.setFontSize(savedSize);
    }

    /** ① 等比缩放到铺满。**看整张图正不正、颜色对不对。** */
    private void drawFitPanel(Gc gc, Loaded img) {
        beginPanel(gc, 0, 0);
        float scale = fitScale(img, PANEL - 20f);
        float dw = img.width() * scale;
        float dh = img.height() * scale;
        gc.drawImage(img.pixels(), img.width(), img.height(),
                panelX(0) + (PANEL - dw) / 2f, panelY(0) + (PANEL - dh) / 2f, dw, dh);
        label(gc, "fit + checker", panelX(0), panelY(0) + PANEL - 44f);
        endPanel(gc);
    }

    /** ② 按原始像素尺寸画（1:1），多余部分被面板裁掉。**看有没有被缩放。** */
    private void drawClipPanel(Gc gc, Loaded img) {
        beginPanel(gc, 1, 0);
        // 用的是"原尺寸"那个重载：只给左上角，宽高由图像自己决定
        gc.drawImage(img.pixels(), img.width(), img.height(), panelX(1), panelY(0));
        label(gc, "1:1, clipped", panelX(1), panelY(0) + PANEL - 44f);
        endPanel(gc);
    }

    /** ③ 放大两倍（线性插值）。 */
    private void drawZoomPanel(Gc gc, Loaded img) {
        beginPanel(gc, 2, 0);
        gc.drawImage(img.pixels(), img.width(), img.height(),
                panelX(2), panelY(0), img.width() * 2f, img.height() * 2f);
        label(gc, "2x (bilinear)", panelX(2), panelY(0) + PANEL - 44f);
        endPanel(gc);
    }

    /**
     * ④ {@code globalAlpha} 对图片生效，而 {@code fill} <b>不</b>参与。
     *
     * <p>这一块在绘制前**故意**把 {@code fill} 设成黑色。它不该影响图片——
     * 图片的顶点色恒白，只有 {@code globalAlpha} 乘进去（见 {@code Gc.emitImageQuad}
     * 的说明：{@code fill} 是环境状态，给文字设的填充色不该顺手把图片染了）。
     * 若这块图明显发暗，那就是"图片被 {@code fill} 染色了"。
     */
    private void drawAlphaPanel(Gc gc, Loaded img) {
        beginPanel(gc, 3, 0);
        float scale = fitScale(img, PANEL - 20f);
        float dw = img.width() * scale;
        float dh = img.height() * scale;
        gc.save();
        gc.setGlobalAlpha(0.35f);
        gc.setFill(0xFF000000);
        gc.drawImage(img.pixels(), img.width(), img.height(),
                panelX(3) + (PANEL - dw) / 2f, panelY(0) + (PANEL - dh) / 2f, dw, dh);
        gc.restore();
        label(gc, "globalAlpha 0.35", panelX(3), panelY(0) + PANEL - 44f);
        endPanel(gc);
    }

    /**
     * ⑤ 同一个半透明图像，左半边垫<b>黑</b>底、右半边垫<b>白</b>底。
     *
     * <p>这是**预乘 alpha** 唯一能目视的地方：漏掉预乘时半透明像素会<b>过亮</b>，
     * 在黑底一侧最明显（本该 `(128,0,0)` 的地方会糊成 `(255,0,0)`）。
     * 两块里图像的过渡都应当自然，边缘**不该发白**。
     */
    private void drawBackdropPanel(Gc gc, Loaded img) {
        float x = panelX(0);
        float y = panelY(1);
        gc.save();
        gc.clipRect(x, y, PANEL, PANEL);
        gc.setFill(0xFF000000);
        gc.fillRect(x, y, PANEL / 2f, PANEL);
        gc.setFill(0xFFFFFFFF);
        gc.fillRect(x + PANEL / 2f, y, PANEL / 2f, PANEL);

        float scale = fitScale(img, PANEL - 20f);
        float dw = img.width() * scale;
        float dh = img.height() * scale;
        gc.drawImage(img.pixels(), img.width(), img.height(),
                x + (PANEL - dw) / 2f, y + (PANEL - dh) / 2f, dw, dh);
        label(gc, "black | white", x, y + PANEL - 44f);
        gc.restore();
    }

    /**
     * ⑥ 带拾取号的一份。**左键点它，控制台打印命中的 ID。**
     *
     * <p>这一份走的是 {@link Gc#createImage} 那条<em>句柄</em>入口，而它用的仍然是
     * **同一个像素数组** ⇒ 与前五块命中**同一张纹理**，六块加起来一共只上传一次。
     * 句柄在这里**不需要 dispose**：连续两代没被画到就由库自动释放
     * （忘了释放的后果只是"多占两帧"，不是泄漏显存）。
     */
    private void drawPickPanel(Gc gc, Loaded img) {
        beginPanel(gc, 1, 1);
        if (pickHandle == null) {
            endPanel(gc);
            return;
        }
        float scale = fitScale(img, PANEL - 20f);
        float dw = pickHandle.width() * scale;
        float dh = pickHandle.height() * scale;
        float x = panelX(1) + (PANEL - dw) / 2f;
        float y = panelY(1) + (PANEL - dh) / 2f;
        gc.setPickId(PICK_ID);
        gc.drawImage(pickHandle, x, y, dw, dh);
        gc.setPickId(0);   // 后面不要再有东西继承这个号
        label(gc, "click me (pick #" + PICK_ID + ")", panelX(1), panelY(1) + PANEL - 44f);
        endPanel(gc);
    }

    /**
     * 建一次拾取用的句柄。
     *
     * <p>建在**帧回调里**而不是 {@code start()} 里，是因为{@code createImage} 是
     * {@code Gc} 的方法，而 {@code Gc} 的契约是"只能在 GL 线程用"。
     * （它自己其实一个 GL 调用都不做——构造是纯 CPU 的、第一次绘制才上传——
     * 但契约就是契约，混着来迟早会在别的方法上也"反正它能用"。）
     */
    private void ensurePickHandle(Gc gc, Loaded img) {
        if (pickHandle == null) {
            pickHandle = gc.createImage(img.pixels(), img.width(), img.height());
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /** 左键点击的结果。回调在 **JavaFX 线程**上，所以这里可以随便打印/改界面。 */
    private void onCanvasClick(PickHit hit) {
        if (hit == null) {
            System.out.println("[点击] 没命中任何可拾取图元");
            return;
        }
        if (hit.id() == PICK_ID) {
            System.out.println("[点击] 命中图片那一块 —— ID=" + hit.id()
                    + "（拾取范围是整个矩形，**包括全透明的像素**；这是已声明的行为）");
        } else {
            System.out.println("[点击] 命中了别的图元，ID=" + hit.id());
        }
    }

    private void onKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            bridge.dispose();
            Platform.exit();
            return;
        }
        if (event.getCode() == KeyCode.R) {
            reloadFromDisk();
        }
    }

    /**
     * 从磁盘重新读一遍，然后请 GL 线程重画。
     *
     * <p>它演示的是缓存语义里那条**必须知道**的约定：纹理按<b>像素数组的对象身份</b>
     * 缓存，所以"就地改老数组的内容"不会被感知，而<b>换一个新数组就会重新上传</b>
     * ——重新读出来正是一个新数组。旧纹理在连续两代没被画到之后自动释放。
     *
     * <p>读失败时<b>保留旧图并打印原因</b>（比如文件被删了），不把窗口弄崩：
     * 这是"恢复现场"该有的行为，而静默什么都不做才是糟糕的那种。
     */
    private void reloadFromDisk() {
        try {
            Loaded fresh = loadImage(resolvePath());
            reportImage(fresh);
            image = fresh;      // 整份替换（volatile），理由见 image 字段的说明
            pickHandle = null;  // 旧句柄绑的是旧数组，丢掉；下一帧会按新数组重建
            System.out.println("[R] 已重新读入。新数组 ⇒ 这一帧会重传一次，"
                    + "旧纹理两代之后自动释放。");
        } catch (RuntimeException e) {
            System.err.println("[R] 重新读入失败，画面保持原样：\n  " + e.getMessage());
        }
        bridge.repaint();
    }

    private void printLegend(Loaded img) {
        System.out.println();
        System.out.println("  画面分六块（每块左下角有 ASCII 标签）：");
        System.out.println("    fit + checker   等比缩放到铺满 —— 看**整张图正不正**（上下颠倒就是这里）");
        System.out.println("    1:1, clipped    按原始像素画、被面板裁掉 —— 看**有没有被偷偷缩放**");
        System.out.println("    2x              放大两倍 —— 平滑是线性插值；缩小有摩尔纹是已声明的降级");
        System.out.println("    globalAlpha     0.35 —— 对图片生效，而 **fill 不参与**（不会被染黑）");
        System.out.println("    black | white   左黑底右白底 —— **预乘 alpha** 唯一能目视的地方");
        System.out.println("    click me        带拾取号 —— 左键点它，本控制台打印 ID");
        System.out.println();
        System.out.println("  键盘：R = 从磁盘重读一遍（演示换新数组会重传），Esc = 退出");
        System.out.println("  可选： -Dxuan.image.frames=30 画 30 帧后自检并退出（退出码 0/1），"
                + "拿它当冒烟测试");
        System.out.println("  图片 " + img.width() + "x" + img.height()
                + "；画面上不画中文，中文全部在这个控制台里");
        System.out.println();
    }
}
