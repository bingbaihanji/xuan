package com.bingbaihanji.birdsflock;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * Boids 鸟群模拟算法（Craig Reynolds, 1987），带空间网格加速 + 多线程并行。
 *
 * <p>三条核心规则：
 * <ul>
 *   <li><b>分离（Separation）</b>：避免与邻近个体碰撞，远离过近邻居。</li>
 *   <li><b>对齐（Alignment）</b>：朝向邻近个体的平均速度方向。</li>
 *   <li><b>聚合（Cohesion）</b>：向邻近个体平均位置移动。</li>
 * </ul>
 *
 * <p>每个鸟群内部独立计算三规则；不同鸟群之间互不感知。
 *
 * <h2>空间网格加速</h2>
 * <p>均匀空间网格（cell 大小 = 感知范围）把邻居查找限制在当前格 + 8 邻格内。
 *
 * <h2>多线程并行</h2>
 * <p>邻居搜索是只读的（读其他鸟的位置），只写自己的速度/位置——
 * 每只鸟恰好被一个线程处理，无数据竞争。用 {@link ForkJoinPool} 把鸟群分段并行。
 */
public final class BoidsSimulation {

    // ---- 可调参数 ----

    /** 每段最少处理的鸟数，低于此值改用单线程。 */
    private static final int PARALLEL_THRESHOLD = 512;

    /** 共享的 ForkJoinPool，所有鸟群共用。 */
    private final ForkJoinPool pool = new ForkJoinPool();

    /** 感知范围半径（像素）：在此范围内的邻居参与对齐与聚合计算。 */
    public float visualRange = 100f;

    /** 受保护范围半径（像素）：在此范围内的邻居触发分离反应。 */
    public float protectedRange = 25f;

    /** 分离力权重：越大则鸟群越松散。 */
    public float separationWeight = 0.05f;

    /** 对齐力权重：越大则鸟群飞行方向越一致。 */
    public float alignmentWeight = 0.05f;

    /** 聚合力权重：越大则鸟群越紧密。 */
    public float cohesionWeight = 0.005f;

    /** 最大飞行速度（像素/秒）。 */
    public float maxSpeed = 300f;

    /** 最小飞行速度（像素/秒）：防止鸟完全停下。 */
    public float minSpeed = 100f;

    // ---- 空间网格 ----

    /** 边界转向力权重：越靠近边界，向中心偏转的力越强。 */
    public float turnFactor = 0.2f;

    /**
     * 边界边距（像素）：距窗口边缘多少像素时开始转向。
     */
    public float margin = 50f;

    /** 网格单元边长（取感知范围值，保证邻格覆盖全部可能邻居）。 */
    private float cellSize;

    /** 网格列数。 */
    private int cols;

    /** 网格行数。 */
    private int rows;

    /**
     * 链式桶：birdIndex → 同一格内上一只鸟的下标（-1 表示桶空）。
     * 比 {@code List<List<Integer>>} 快——无装箱、无间接寻址。
     */
    private int[] next;

    /** 每个格子的头节点下标（-1 表示空格）。 */
    private int[] head;

    /** 临时：每只鸟所属的格子线性索引（避免每帧重算两次）。 */
    private int[] cellOf;

    // ---- 并行 ----

    /** 临时：每只鸟所属格子的列号（避免主循环里做除法/取模）。 */
    private int[] cellColOf;

    /** 临时：每只鸟所属格子的行号。 */
    private int[] cellRowOf;

    // ---- 内部复用 ----

    /** 最近一次 {@link #step} 的画布宽度，供 {@link #buildGrid} 使用。 */
    private float lastWidth;

    /** 最近一次 {@link #step} 的画布高度，供 {@link #buildGrid} 使用。 */
    private float lastHeight;

    /**
     * 快速反平方根（Quake III 算法的 float 版本）。
     * 精度约 ±1%，对鸟群模拟足够。
     */
    private static float fastInvSqrt(float x) {
        float xhalf = 0.5f * x;
        int i = Float.floatToRawIntBits(x);
        i = 0x5f3759df - (i >> 1);
        x = Float.intBitsToFloat(i);
        x = x * (1.5f - xhalf * x * x);
        return x;
    }

    /**
     * 用三条规则更新一群鸟的位置与速度。
     *
     * @param birds  一群鸟的数组
     * @param dt     帧间隔（秒），用于将速度积分到位置
     * @param width  画布宽度（像素），用于边界环绕与网格尺寸
     * @param height 画布高度（像素），用于边界环绕与网格尺寸
     */
    public void step(Bird[] birds, float dt, float width, float height) {
        if (dt <= 0f) {
            return;
        }

        lastWidth = width;
        lastHeight = height;

        // 确保网格够大（窗口尺寸变化或首次调用时重建）
        ensureGrid(width, height);

        // 把所有鸟塞进网格
        buildGrid(birds);

        float vr2 = visualRange * visualRange;
        float pr2 = protectedRange * protectedRange;

        // 并行处理：鸟数足够时用 ForkJoinPool 分段
        if (birds.length >= PARALLEL_THRESHOLD) {
            pool.invoke(new BoidsTask(birds, 0, birds.length, vr2, pr2, dt, width, height));
        } else {
            processRange(birds, 0, birds.length, vr2, pr2, dt, width, height);
        }
    }

    /**
     * 处理 [lo, hi) 范围内的鸟——核心计算逻辑。
     */
    private void processRange(Bird[] birds, int lo, int hi,
                              float vr2, float pr2, float dt, float width, float height) {
        for (int i = lo; i < hi; i++) {
            Bird b = birds[i];

            // 三规则的累积偏移量
            float sepX = 0f, sepY = 0f;
            float aliX = 0f, aliY = 0f;
            float cohX = 0f, cohY = 0f;
            int neighbors = 0;

            // 只扫描当前格 + 8 邻格（用预计算的行列号，省掉除法/取模）
            int cellCol = cellColOf[i];
            int cellRow = cellRowOf[i];

            int rowMin = Math.max(0, cellRow - 1);
            int rowMax = Math.min(rows - 1, cellRow + 1);
            int colMin = Math.max(0, cellCol - 1);
            int colMax = Math.min(cols - 1, cellCol + 1);

            for (int r = rowMin; r <= rowMax; r++) {
                for (int c = colMin; c <= colMax; c++) {
                    int j = head[r * cols + c];
                    while (j >= 0) {
                        if (j != i) {
                            Bird o = birds[j];
                            float dx = b.x - o.x;
                            float dy = b.y - o.y;
                            float d2 = dx * dx + dy * dy;

                            // 感知范围：对齐 + 聚合（大部分邻居在这里）
                            if (d2 < vr2) {
                                aliX += o.vx;
                                aliY += o.vy;
                                cohX += o.x;
                                cohY += o.y;
                                neighbors++;

                                // 受保护范围：分离（用快速反平方根避免 Math.sqrt）
                                if (d2 < pr2 && d2 > 0f) {
                                    float invDist = fastInvSqrt(d2);
                                    sepX += dx * invDist;
                                    sepY += dy * invDist;
                                }
                            }
                        }
                        j = next[j];
                    }
                }
            }

            // 对齐：朝邻居平均速度方向偏转
            if (neighbors > 0) {
                aliX /= neighbors;
                aliY /= neighbors;
                b.vx += (aliX - b.vx) * alignmentWeight;
                b.vy += (aliY - b.vy) * alignmentWeight;

                // 聚合：朝邻居中心移动
                cohX /= neighbors;
                cohY /= neighbors;
                b.vx += (cohX - b.x) * cohesionWeight;
                b.vy += (cohY - b.y) * cohesionWeight;
            }

            // 分离：远离过近邻居
            b.vx += sepX * separationWeight;
            b.vy += sepY * separationWeight;

            // 边界转向（软边界，比硬环绕更自然）
            if (b.x < margin) {
                b.vx += turnFactor;
            }
            if (b.x > width - margin) {
                b.vx -= turnFactor;
            }
            if (b.y < margin) {
                b.vy += turnFactor;
            }
            if (b.y > height - margin) {
                b.vy -= turnFactor;
            }

            // 速度钳制（用快速反平方根避免 Math.sqrt）
            float speed2 = b.vx * b.vx + b.vy * b.vy;
            if (speed2 > maxSpeed * maxSpeed) {
                float invSpeed = fastInvSqrt(speed2);
                b.vx = b.vx * invSpeed * maxSpeed;
                b.vy = b.vy * invSpeed * maxSpeed;
            } else if (speed2 < minSpeed * minSpeed && speed2 > 0f) {
                float invSpeed = fastInvSqrt(speed2);
                b.vx = b.vx * invSpeed * minSpeed;
                b.vy = b.vy * invSpeed * minSpeed;
            }

            // 位置更新
            b.x += b.vx * dt;
            b.y += b.vy * dt;

            // 硬边界环绕（转向力失效时的最后保险）
            if (b.x < 0) {
                b.x += width;
            }
            if (b.x > width) {
                b.x -= width;
            }
            if (b.y < 0) {
                b.y += height;
            }
            if (b.y > height) {
                b.y -= height;
            }
        }
    }

    /**
     * 确保网格数组够大；窗口尺寸变化时重建。
     */
    private void ensureGrid(float width, float height) {
        cellSize = visualRange;
        int newCols = Math.max(1, (int) Math.ceil(width / cellSize));
        int newRows = Math.max(1, (int) Math.ceil(height / cellSize));

        if (head == null || newCols != cols || newRows != rows) {
            cols = newCols;
            rows = newRows;
            int numCells = cols * rows;
            head = new int[numCells];
            // head 会在 buildGrid 中被重置
        }
    }

    /**
     * 把所有鸟塞进网格（链式桶，O(n)）。
     */
    private void buildGrid(Bird[] birds) {
        int n = birds.length;
        if (next == null || next.length < n) {
            next = new int[n];
            cellOf = new int[n];
            cellColOf = new int[n];
            cellRowOf = new int[n];
        }

        // 重置桶头
        java.util.Arrays.fill(head, 0, cols * rows, -1);

        for (int i = 0; i < n; i++) {
            int c = Math.max(0, Math.min(cols - 1, (int) (birds[i].x / cellSize)));
            int r = Math.max(0, Math.min(rows - 1, (int) (birds[i].y / cellSize)));
            int idx = r * cols + c;
            cellOf[i] = idx;
            cellColOf[i] = c;
            cellRowOf[i] = r;
            next[i] = head[idx];
            head[idx] = i;
        }
    }

    /**
     * ForkJoin 任务：把鸟群分成两半递归并行。
     */
    private class BoidsTask extends RecursiveAction {

        private final Bird[] birds;

        private final int lo, hi;

        private final float vr2, pr2, dt, width, height;

        BoidsTask(Bird[] birds, int lo, int hi,
                  float vr2, float pr2, float dt, float width, float height) {
            this.birds = birds;
            this.lo = lo;
            this.hi = hi;
            this.vr2 = vr2;
            this.pr2 = pr2;
            this.dt = dt;
            this.width = width;
            this.height = height;
        }

        @Override
        protected void compute() {
            int len = hi - lo;
            if (len < PARALLEL_THRESHOLD) {
                processRange(birds, lo, hi, vr2, pr2, dt, width, height);
            } else {
                int mid = lo + (len / 2);
                invokeAll(
                        new BoidsTask(birds, lo, mid, vr2, pr2, dt, width, height),
                        new BoidsTask(birds, mid, hi, vr2, pr2, dt, width, height)
                );
            }
        }
    }
}
