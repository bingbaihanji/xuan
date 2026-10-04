package com.bingbaihanji.birdsflock;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.gpu.ComputeShader;
import com.bingbaihanji.xuan.util.Disposable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GPU 上的 Boids 鸟群模拟：一次 dispatch 更新全部鸟的位置与速度。
 *
 * <p>每只鸟由一个 GPU 线程处理，遍历所有其他鸟计算分离/对齐/聚合三规则。
 * 虽然是 O(n²) 的邻居搜索，但 GPU 的数千个核心并行执行，实际吞吐量极高。
 *
 * <h2>数据布局</h2>
 * <p>SSBO 里每只鸟占 4 个 float：{@code [x, y, vx, vy]}。
 * 总大小 = {@code birdCount × 4 × 4} 字节。
 *
 * <h2>用法</h2>
 * <pre>
 * BoidsGpuKernel kernel = new BoidsGpuKernel(gl, 15000);
 * kernel.upload(birds);           // 上传初始数据
 * kernel.execute(dt, w, h);       // 每帧执行
 * kernel.download(birds);         // 可选：回读到 CPU（渲染用 SSBO 则不需要）
 * </pre>
 */
public final class BoidsGpuKernel implements Disposable {

    /** 每个 workgroup 的线程数。 */
    private static final int LOCAL_SIZE = 256;

    /**
     * 着色器源码。
     *
     * <p>每只鸟一个线程，遍历所有其他鸟计算三规则。
     * 用 {@code shared} 缓存当前 workgroup 处理的鸟的数据，减少全局内存读取。
     */
    private static final String SHADER_SOURCE = """
                                                #version 430
                                                layout(local_size_x = %d) in;
                                                
                                                layout(std430, binding = 0) buffer BirdBuffer { float birds[]; };
                                                
                                                uniform int   u_BirdCount;
                                                uniform float u_Dt;
                                                uniform float u_Width;
                                                uniform float u_Height;
                                                
                                                // Boids 参数
                                                uniform float u_VisualRange;
                                                uniform float u_ProtectedRange;
                                                uniform float u_SeparationWeight;
                                                uniform float u_AlignmentWeight;
                                                uniform float u_CohesionWeight;
                                                uniform float u_MaxSpeed;
                                                uniform float u_MinSpeed;
                                                uniform float u_TurnFactor;
                                                uniform float u_Margin;
                                                
                                                // shared memory 缓存一个 workgroup 的鸟数据
                                                shared float sBirds[%d * 4]; // LOCAL_SIZE 只鸟 × 4 floats
                                                
                                                float fastInvSqrt(float x) {
                                                    float xhalf = 0.5 * x;
                                                    int i = floatBitsToInt(x);
                                                    i = 0x5f3759df - (i >> 1);
                                                    x = intBitsToFloat(i);
                                                    x = x * (1.5 - xhalf * x * x);
                                                    return x;
                                                }
                                                
                                                void main() {
                                                    int gid = int(gl_GlobalInvocationID.x);
                                                    if (gid >= u_BirdCount) return;
                                                
                                                    // 读取当前鸟的数据
                                                    float bx = birds[gid * 4 + 0];
                                                    float by = birds[gid * 4 + 1];
                                                    float bvx = birds[gid * 4 + 2];
                                                    float bvy = birds[gid * 4 + 3];
                                                
                                                    float vr2 = u_VisualRange * u_VisualRange;
                                                    float pr2 = u_ProtectedRange * u_ProtectedRange;
                                                
                                                    float sepX = 0.0, sepY = 0.0;
                                                    float aliX = 0.0, aliY = 0.0;
                                                    float cohX = 0.0, cohY = 0.0;
                                                    int neighbors = 0;
                                                
                                                    int tid = int(gl_LocalInvocationID.x);
                                                    int groupSize = int(gl_WorkGroupSize.x);
                                                
                                                    // 遍历所有鸟（按 workgroup 分块，用 shared memory 缓存）
                                                    for (int block = 0; block < u_BirdCount; block += groupSize) {
                                                        // 协作加载一个 workgroup 的鸟数据到 shared memory
                                                        int loadIdx = block + tid;
                                                        if (loadIdx < u_BirdCount) {
                                                            sBirds[tid * 4 + 0] = birds[loadIdx * 4 + 0];
                                                            sBirds[tid * 4 + 1] = birds[loadIdx * 4 + 1];
                                                            sBirds[tid * 4 + 2] = birds[loadIdx * 4 + 2];
                                                            sBirds[tid * 4 + 3] = birds[loadIdx * 4 + 3];
                                                        }
                                                        barrier();
                                                
                                                        // 计算当前块里有多少只鸟
                                                        int blockSize = min(groupSize, u_BirdCount - block);
                                                
                                                        // 遍历块里的每只鸟
                                                        for (int j = 0; j < blockSize; j++) {
                                                            int jGlobal = block + j;
                                                            if (jGlobal == gid) continue; // 跳过自己
                                                
                                                            float ox = sBirds[j * 4 + 0];
                                                            float oy = sBirds[j * 4 + 1];
                                                            float ovx = sBirds[j * 4 + 2];
                                                            float ovy = sBirds[j * 4 + 3];
                                                
                                                            float dx = bx - ox;
                                                            float dy = by - oy;
                                                            float d2 = dx * dx + dy * dy;
                                                
                                                            // 感知范围：对齐 + 聚合
                                                            if (d2 < vr2) {
                                                                aliX += ovx;
                                                                aliY += ovy;
                                                                cohX += ox;
                                                                cohY += oy;
                                                                neighbors++;
                                                
                                                                // 受保护范围：分离
                                                                if (d2 < pr2 && d2 > 0.0) {
                                                                    float invDist = fastInvSqrt(d2);
                                                                    sepX += dx * invDist;
                                                                    sepY += dy * invDist;
                                                                }
                                                            }
                                                        }
                                                        barrier();
                                                    }
                                                
                                                    // 对齐：朝邻居平均速度方向偏转
                                                    if (neighbors > 0) {
                                                        float invN = 1.0 / float(neighbors);
                                                        aliX *= invN;
                                                        aliY *= invN;
                                                        bvx += (aliX - bvx) * u_AlignmentWeight;
                                                        bvy += (aliY - bvy) * u_AlignmentWeight;
                                                
                                                        cohX *= invN;
                                                        cohY *= invN;
                                                        bvx += (cohX - bx) * u_CohesionWeight;
                                                        bvy += (cohY - by) * u_CohesionWeight;
                                                    }
                                                
                                                    // 分离：远离过近邻居
                                                    bvx += sepX * u_SeparationWeight;
                                                    bvy += sepY * u_SeparationWeight;
                                                
                                                    // 边界转向
                                                    if (bx < u_Margin)           bvx += u_TurnFactor;
                                                    if (bx > u_Width - u_Margin) bvx -= u_TurnFactor;
                                                    if (by < u_Margin)           bvy += u_TurnFactor;
                                                    if (by > u_Height - u_Margin) bvy -= u_TurnFactor;
                                                
                                                    // 速度钳制
                                                    float speed2 = bvx * bvx + bvy * bvy;
                                                    if (speed2 > u_MaxSpeed * u_MaxSpeed) {
                                                        float invSpeed = fastInvSqrt(speed2);
                                                        bvx = bvx * invSpeed * u_MaxSpeed;
                                                        bvy = bvy * invSpeed * u_MaxSpeed;
                                                    } else if (speed2 < u_MinSpeed * u_MinSpeed && speed2 > 0.0) {
                                                        float invSpeed = fastInvSqrt(speed2);
                                                        bvx = bvx * invSpeed * u_MinSpeed;
                                                        bvy = bvy * invSpeed * u_MinSpeed;
                                                    }
                                                
                                                    // 位置更新
                                                    bx += bvx * u_Dt;
                                                    by += bvy * u_Dt;
                                                
                                                    // 硬边界环绕
                                                    if (bx < 0.0)        bx += u_Width;
                                                    if (bx > u_Width)    bx -= u_Width;
                                                    if (by < 0.0)        by += u_Height;
                                                    if (by > u_Height)   by -= u_Height;
                                                
                                                    // 写回
                                                    birds[gid * 4 + 0] = bx;
                                                    birds[gid * 4 + 1] = by;
                                                    birds[gid * 4 + 2] = bvx;
                                                    birds[gid * 4 + 3] = bvy;
                                                }
                                                """.formatted(LOCAL_SIZE, LOCAL_SIZE);

    private final GLAbstraction gl;

    private final ComputeShader shader;

    private final int ssbo;

    private final int birdCount;

    private boolean disposed;

    /**
     * 创建 GPU Boids 计算核。
     *
     * @param gl         GL 抽象层
     * @param birdCount  鸟的总数
     */
    public BoidsGpuKernel(GLAbstraction gl, int birdCount) {
        this.gl = gl;
        this.birdCount = birdCount;
        this.shader = new ComputeShader(SHADER_SOURCE);

        // 创建 SSBO
        int buf = 0;
        try {
            buf = gl.createBuffer();
            gl.bindShaderStorageBuffer(buf);
            gl.allocateBufferStorage((long) birdCount * 4 * Float.BYTES);
        } catch (RuntimeException e) {
            shader.dispose();
            if (buf != 0) {
                gl.deleteBuffer(buf);
            }
            throw e;
        }
        gl.bindShaderStorageBuffer(0);
        this.ssbo = buf;
    }

    /** 鸟的数量。 */
    public int birdCount() {
        return birdCount;
    }

    /** SSBO 的 GL 名字，可直接用于渲染（零拷贝）。 */
    public int bufferId() {
        return ssbo;
    }

    /**
     * 上传鸟群数据到 GPU。
     *
     * @param flocks 三群鸟的数组
     */
    public void upload(Bird[][] flocks) {
        ByteBuffer buf = ByteBuffer.allocateDirect(birdCount * 4 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        FloatBuffer fb = buf.asFloatBuffer();
        for (Bird[] flock : flocks) {
            for (Bird b : flock) {
                fb.put(b.x);
                fb.put(b.y);
                fb.put(b.vx);
                fb.put(b.vy);
            }
        }
        fb.flip();

        gl.bindShaderStorageBuffer(ssbo);
        gl.uploadBufferSubData(0, buf);
        gl.bindShaderStorageBuffer(0);
    }

    /**
     * 执行一步 GPU 模拟。
     *
     * @param dt     帧间隔（秒）
     * @param width  画布宽度
     * @param height 画布高度
     * @param params Boids 参数
     */
    public void execute(float dt, float width, float height, BoidsParams params) {
        shader.use();
        shader.setUniform("u_BirdCount", birdCount);
        shader.setUniform("u_Dt", dt);
        shader.setUniform("u_Width", width);
        shader.setUniform("u_Height", height);
        shader.setUniform("u_VisualRange", params.visualRange);
        shader.setUniform("u_ProtectedRange", params.protectedRange);
        shader.setUniform("u_SeparationWeight", params.separationWeight);
        shader.setUniform("u_AlignmentWeight", params.alignmentWeight);
        shader.setUniform("u_CohesionWeight", params.cohesionWeight);
        shader.setUniform("u_MaxSpeed", params.maxSpeed);
        shader.setUniform("u_MinSpeed", params.minSpeed);
        shader.setUniform("u_TurnFactor", params.turnFactor);
        shader.setUniform("u_Margin", params.margin);

        gl.bindBufferBase(0, ssbo);
        int groupsX = (birdCount + LOCAL_SIZE - 1) / LOCAL_SIZE;
        shader.dispatch(groupsX, 1, 1);
        shader.memoryBarrier();
        gl.bindBufferBase(0, 0);
        shader.unuse();
    }

    /**
     * 从 GPU 回读鸟群数据（用于调试或 CPU 渲染路径）。
     *
     * @param flocks 三群鸟的数组（会被覆盖）
     */
    public void download(Bird[][] flocks) {
        ByteBuffer buf = ByteBuffer.allocateDirect(birdCount * 4 * Float.BYTES)
                .order(ByteOrder.nativeOrder());

        gl.bindShaderStorageBuffer(ssbo);
        // 用 glGetBufferSubData 读回（LWJGL 直接调用，不走抽象层）
        org.lwjgl.opengl.GL15.glGetBufferSubData(
                org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        gl.bindShaderStorageBuffer(0);

        FloatBuffer fb = buf.asFloatBuffer();
        for (Bird[] flock : flocks) {
            for (Bird b : flock) {
                b.x = fb.get();
                b.y = fb.get();
                b.vx = fb.get();
                b.vy = fb.get();
            }
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        shader.dispose();
        gl.deleteBuffer(ssbo);
    }

    /**
     * Boids 算法参数（与 CPU 版 BoidsSimulation 一致）。
     */
    public static class BoidsParams {

        public float visualRange = 100f;

        public float protectedRange = 25f;

        public float separationWeight = 0.05f;

        public float alignmentWeight = 0.05f;

        public float cohesionWeight = 0.005f;

        public float maxSpeed = 300f;

        public float minSpeed = 100f;

        public float turnFactor = 0.2f;

        public float margin = 50f;
    }
}
