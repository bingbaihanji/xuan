package com.bingbaihanji.birdsflock;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.glview.FXGLTransfer;
import com.bingbaihanji.xuan.renderer.Gc;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import kotlin.Unit;

/**
 * 鸟群模型（Boids）GPU 计算版本：3 个鸟群，各 BIRDS_PER_FLOCK 只，RGB 三色。
 *
 * <p>与 CPU 版本不同，本版本使用 GPU compute shader 执行 Boids 模拟。
 * 每只鸟由一个 GPU 线程处理，遍历所有其他鸟计算分离/对齐/聚合三规则。
 * 虽然是 O(n²) 的邻居搜索，但 GPU 的数千个核心并行执行，实际吞吐量极高。
 *
 * <h2>性能特征</h2>
 * <ul>
 *   <li>GPU 着色器做 O(n²) 暴力邻居搜索（无空间哈希）</li>
 *   <li>每帧需从 SSBO 下载位置数据到 CPU 用于 fillRect 渲染</li>
 *   <li>适合中小规模鸟群（~15000 只时 ~60 FPS）</li>
 *   <li>大规模鸟群建议使用 CPU 多线程版本（BoidsSimulation + 空间网格）</li>
 * </ul>
 *
 * <h2>运行</h2>
 * <pre>
 * cd xuan-javafx
 * mvn -o test-compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=test" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.birdsflock.BirdsFlockApp"
 * </pre>
 */
public class BirdsFlockApp extends Application {

    // ---- 鸟群配置 ----
    private static final int BIRDS_PER_FLOCK = 26667;

    // ---- 窗口参数 ----
    private static final int WIDTH = 1000;

    private static final int HEIGHT = 700;

    private static final String TITLE = "鸟群模型 (GPU) — 3×" + BIRDS_PER_FLOCK + " Boids";

    /** 红色鸟群（暖色调）。 */
    private static final int COLOR_RED = 0xFFFF4444;

    /** 绿色鸟群（冷色调）。 */
    private static final int COLOR_GREEN = 0xFF44FF44;

    /** 蓝色鸟群（中性色）。 */
    private static final int COLOR_BLUE = 0xFF4488FF;

    private static final int[] FLOCK_COLORS = {COLOR_RED, COLOR_GREEN, COLOR_BLUE};

    private static final int[] FLOCK_COUNTS = {BIRDS_PER_FLOCK, BIRDS_PER_FLOCK, BIRDS_PER_FLOCK};

    // ---- 三角形"鸟"的尺寸 ----
    private static final float BIRD_SIZE = 9f;

    // ---- 运行时状态 ----
    private final FXGLTransfer bridge = new FXGLTransfer();

    /** Boids 参数。 */
    private final BoidsGpuKernel.BoidsParams params = new BoidsGpuKernel.BoidsParams();

    /** 三群鸟的数据（位置、速度、颜色）。 */
    private Bird[][] flocks;

    /** GPU 计算核（在第一帧创建）。 */
    private BoidsGpuKernel gpuKernel;

    /** 是否已初始化 GPU 内核。 */
    private boolean gpuInitialized = false;

    /** 上一帧的纳秒时间戳，用于计算帧间隔。 */
    private long lastNanos = 0L;

    /** FPS 统计：帧计数器。 */
    private int frameCount = 0;

    /** FPS 统计：上次报告 FPS 的纳秒时间戳。 */
    private long fpsNanos = 0L;

    /** FPS 统计：当前 FPS（每秒更新一次）。 */
    private double currentFps = 0.0;

    /** 分段计时：GPU 模拟耗时累计（纳秒）。 */
    private long simNanos = 0L;

    /** 分段计时：渲染耗时累计（纳秒）。 */
    private long drawNanos = 0L;

    public static void main(String[] args) {
        Application.launch(args);
    }

    @Override
    public void start(Stage stage) {
        initFlocks(WIDTH, HEIGHT);

        bridge.onFrame(gc -> {
            renderFrame(gc);
            return Unit.INSTANCE;
        });

        BorderPane root = new BorderPane();
        root.setCenter(bridge.createGlFXView());

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        stage.setTitle(TITLE);
        stage.setScene(scene);
        stage.show();

        stage.setOnCloseRequest(e -> {
            if (gpuKernel != null) {
                gpuKernel.dispose();
            }
            bridge.dispose();
        });
    }

    /**
     * 初始化所有鸟群：随机位置（画布中心区域）、随机速度（有方向性）。
     */
    private void initFlocks(int w, int h) {
        flocks = new Bird[FLOCK_COLORS.length][];

        java.util.Random rng = new java.util.Random(42);
        float cx = w / 2f;
        float cy = h / 2f;
        float spread = Math.min(w, h) * 0.3f;
        float initSpeed = (params.minSpeed + params.maxSpeed) * 0.5f;

        for (int g = 0; g < FLOCK_COLORS.length; g++) {
            flocks[g] = new Bird[FLOCK_COUNTS[g]];
            // 每个鸟群的初始飞行方向略有不同（分散开，避免一开始就重叠）
            float baseAngle = (float) (g * 2.0 * Math.PI / FLOCK_COLORS.length);

            for (int i = 0; i < FLOCK_COUNTS[g]; i++) {
                float x = cx + (rng.nextFloat() - 0.5f) * spread;
                float y = cy + (rng.nextFloat() - 0.5f) * spread;
                float angle = baseAngle + (rng.nextFloat() - 0.5f) * 0.8f;
                float vx = (float) Math.cos(angle) * initSpeed;
                float vy = (float) Math.sin(angle) * initSpeed;

                flocks[g][i] = new Bird(x, y, vx, vy, FLOCK_COLORS[g]);
            }
        }
    }

    /**
     * 每帧回调：GPU 模拟 + CPU 渲染。
     */
    private void renderFrame(Gc gc) {
        // 第一帧：初始化 GPU 内核
        if (!gpuInitialized) {
            gpuInitialized = true;
            try {
                GLAbstraction gl = gc.glAbstraction();
                int totalBirds = 0;
                for (Bird[] flock : flocks) {
                    totalBirds += flock.length;
                }
                gpuKernel = new BoidsGpuKernel(gl, totalBirds);
                gpuKernel.upload(flocks);
                System.out.printf("[Boids] GPU kernel initialized: %d birds%n", totalBirds);
            } catch (Exception e) {
                System.err.println("[Boids] Failed to initialize GPU kernel: " + e.getMessage());
                e.printStackTrace();
            }
            lastNanos = System.nanoTime();
            fpsNanos = lastNanos;
            return;
        }

        // 帧间隔计算
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            fpsNanos = now;
            return;
        }
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;

        // 防止最小化/切后台时 dt 暴涨导致鸟飞出屏幕
        dt = Math.min(dt, 0.05f);

        float w = gc.getWidth();
        float h = gc.getHeight();

        // 深色背景
        gc.setFill(0xFF0A0A1A);
        gc.fillRect(0f, 0f, w, h);

        // GPU 模拟
        if (gpuKernel != null) {
            long t0 = System.nanoTime();
            gpuKernel.execute(dt, w, h, params);
            gpuKernel.download(flocks);
            long t1 = System.nanoTime();
            simNanos += (t1 - t0);

            // 渲染
            long t2 = System.nanoTime();
            for (Bird[] flock : flocks) {
                drawFlock(gc, flock);
            }
            long t3 = System.nanoTime();
            drawNanos += (t3 - t2);
        }

        // FPS 统计（每秒更新一次，打印到控制台）
        frameCount++;
        long elapsed = now - fpsNanos;
        if (elapsed >= 1_000_000_000L) {
            currentFps = frameCount * 1_000_000_000.0 / elapsed;
            int totalBirds = 0;
            for (Bird[] flock : flocks) {
                totalBirds += flock.length;
            }
            System.out.printf("[Boids] %.1f FPS | %d birds (GPU) | sim=%.1fms draw=%.1fms%n",
                    currentFps, totalBirds, simNanos / 1_000_000.0, drawNanos / 1_000_000.0);
            frameCount = 0;
            fpsNanos = now;
            simNanos = 0L;
            drawNanos = 0L;
        }
    }

    /**
     * 绘制一群鸟：轴对齐小方块，跳过旋转与 save/restore。
     */
    private void drawFlock(Gc gc, Bird[] birds) {
        if (birds.length == 0) {
            return;
        }

        gc.setFill(birds[0].color);
        float half = BIRD_SIZE * 0.5f;

        for (Bird b : birds) {
            gc.fillRect(b.x - half, b.y - half, BIRD_SIZE, BIRD_SIZE);
        }
    }
}