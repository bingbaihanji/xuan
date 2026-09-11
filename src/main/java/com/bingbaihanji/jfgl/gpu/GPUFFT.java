package com.bingbaihanji.jfgl.gpu;

import com.bingbaihanji.jfgl.util.Disposable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * GPU-accelerated Fast Fourier Transform using OpenGL compute shaders.
 * <p>
 * Implements the Cooley-Tukey radix-2 decimation-in-time FFT algorithm
 * entirely on the GPU via a GLSL compute shader. Input arrays are uploaded
 * to Shader Storage Buffer Objects (SSBOs), dispatched through the compute
 * pipeline, and results are read back to the CPU.
 * <p>
 * Requires OpenGL 4.3+ for compute shader support.
 */
public class GPUFFT implements Disposable {

    // ──────────────────────────────────────────────────────────────────────
    // Default GLSL compute shader – Cooley-Tukey radix-2 DIT FFT
    // ──────────────────────────────────────────────────────────────────────

    private static final String DEFAULT_FFT_SHADER = """
                                                     #version 430
                                                     
                                                     layout(local_size_x = 256) in;
                                                     
                                                     layout(std430, binding = 0) buffer RealBuffer  { float real[]; };
                                                     layout(std430, binding = 1) buffer ImagBuffer  { float imag[]; };
                                                     
                                                     uniform uint u_N;        // transform length (must be power of 2)
                                                     uniform uint u_stage;    // current butterfly stage (0 .. log2(N)-1)
                                                     uniform uint u_direction; // 0 = forward FFT, 1 = inverse FFT
                                                     
                                                     shared float s_real[gl_WorkGroupSize.x * 2];
                                                     shared float s_imag[gl_WorkGroupSize.x * 2];
                                                     
                                                     #define PI 3.14159265358979323846
                                                     
                                                     void main() {
                                                         uint tid   = gl_LocalInvocationID.x;
                                                         uint gid   = gl_GlobalInvocationID.x;
                                                         uint halfN = u_N >> 1;
                                                     
                                                         if (gid >= halfN) return;
                                                     
                                                         // Bit-reversal permutation index
                                                         uint logN = 0;
                                                         uint tmp  = u_N;
                                                         while (tmp > 1u) { tmp >>= 1u; logN++; }
                                                     
                                                         uint rev = 0u;
                                                         uint idx = gid;
                                                         for (uint i = 0u; i < logN; i++) {
                                                             rev = (rev << 1u) | (idx & 1u);
                                                             idx >>= 1u;
                                                         }
                                                     
                                                         // Butterfly stride for this stage
                                                         uint m    = 1u << (u_stage + 1u);
                                                         uint half = 1u << u_stage;
                                                         uint group = gid / half;
                                                         uint pos   = gid % half;
                                                     
                                                         uint k1 = group * m + pos;
                                                         uint k2 = k1 + half;
                                                     
                                                         if (k2 >= u_N) return;
                                                     
                                                         // Twiddle factor
                                                         float angle = -2.0 * PI * float(pos) / float(m);
                                                         if (u_direction == 1u) angle = -angle;
                                                     
                                                         float wr = cos(angle);
                                                         float wi = sin(angle);
                                                     
                                                         float tReal = wr * real[k2] - wi * imag[k2];
                                                         float tImag = wr * imag[k2] + wi * real[k2];
                                                     
                                                         float uReal = real[k1];
                                                         float uImag = imag[k1];
                                                     
                                                         real[k1] = uReal + tReal;
                                                         imag[k1] = uImag + tImag;
                                                         real[k2] = uReal - tReal;
                                                         imag[k2] = uImag - tImag;
                                                     }
                                                     """;

    private static final int WORK_GROUP_SIZE = 256;

    // ──────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────

    private final ComputeShader shader;

    private int inputBuffer;

    private int outputBuffer;

    // ──────────────────────────────────────────────────────────────────────
    // Constructors
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Creates a new GPUFFT instance with the default Cooley-Tukey compute shader.
     */
    public GPUFFT() {
        this(DEFAULT_FFT_SHADER);
    }

    /**
     * Creates a new GPUFFT instance with a custom compute shader source.
     *
     * @param shaderSource GLSL compute shader source code implementing the FFT butterfly
     */
    public GPUFFT(String shaderSource) {
        this.shader = new ComputeShader(shaderSource);
        this.inputBuffer = 0;
        this.outputBuffer = 0;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Performs an in-place FFT on the supplied real and imaginary arrays.
     * <p>
     * Both arrays must have the same length, and the length must be a power of two.
     * The arrays are modified in-place with the FFT result.
     *
     * @param real real components of the input signal (modified in-place)
     * @param imag imaginary components of the input signal (modified in-place)
     * @throws IllegalArgumentException if the arrays have different lengths or the length
     *                                  is not a power of two
     */
    public void execute(float[] real, float[] imag) {
        if (real.length != imag.length) {
            throw new IllegalArgumentException(
                    "Real and imaginary arrays must have the same length: real=" + real.length + ", imag=" + imag.length);
        }

        int n = real.length;
        if (n == 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Array length must be a power of two, got " + n);
        }

        ensureBuffers(n);
        uploadData(real, imag);
        dispatchFFT(n, false);
        readbackData(real, imag, n);
    }

    /**
     * Performs an inverse FFT (IFFT) on the supplied real and imaginary arrays.
     * <p>
     * The result is not normalized; divide each element by {@code n} after calling this method
     * to obtain the true inverse transform.
     *
     * @param real real components of the frequency-domain signal (modified in-place)
     * @param imag imaginary components of the frequency-domain signal (modified in-place)
     * @throws IllegalArgumentException if the arrays have different lengths or the length
     *                                  is not a power of two
     */
    public void executeInverse(float[] real, float[] imag) {
        if (real.length != imag.length) {
            throw new IllegalArgumentException(
                    "Real and imaginary arrays must have the same length: real=" + real.length + ", imag=" + imag.length);
        }

        int n = real.length;
        if (n == 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Array length must be a power of two, got " + n);
        }

        ensureBuffers(n);
        uploadData(real, imag);
        dispatchFFT(n, true);
        readbackData(real, imag, n);
    }

    @Override
    public void dispose() {
        shader.dispose();
        if (inputBuffer != 0) {
            glDeleteBuffers(inputBuffer);
            inputBuffer = 0;
        }
        if (outputBuffer != 0) {
            glDeleteBuffers(outputBuffer);
            outputBuffer = 0;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Ensures SSBOs exist and are large enough for the given transform size.
     */
    private void ensureBuffers(int n) {
        int requiredBytes = n * Float.BYTES;

        if (inputBuffer == 0) {
            inputBuffer = glGenBuffers();
        }
        if (outputBuffer == 0) {
            outputBuffer = glGenBuffers();
        }

        // Allocate (or reallocate) buffer storage
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, requiredBytes, GL_DYNAMIC_COPY);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, requiredBytes, GL_DYNAMIC_COPY);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Uploads real and imaginary data to the GPU SSBOs.
     */
    private void uploadData(float[] real, float[] imag) {
        int n = real.length;

        // Upload real part → inputBuffer (binding 0)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(n);
            fb.put(real).flip();
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, fb);
        }

        // Upload imaginary part → outputBuffer (binding 1)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(n);
            fb.put(imag).flip();
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, fb);
        }

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Dispatches the compute shader for each stage of the Cooley-Tukey FFT.
     */
    private void dispatchFFT(int n, boolean inverse) {
        int logN = Integer.numberOfTrailingZeros(n);

        shader.bind();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int nLoc = glGetUniformLocation(shader.programId(), "u_N");
            int stageLoc = glGetUniformLocation(shader.programId(), "u_stage");
            int dirLoc = glGetUniformLocation(shader.programId(), "u_direction");

            glUniform1i(nLoc, n);

            // Bind SSBOs
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, inputBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, outputBuffer);

            int halfN = n / 2;
            int numWorkGroups = (halfN + WORK_GROUP_SIZE - 1) / WORK_GROUP_SIZE;

            // Execute each butterfly stage
            for (int stage = 0; stage < logN; stage++) {
                glUniform1i(stageLoc, stage);
                glUniform1i(dirLoc, inverse ? 1 : 0);

                glDispatchCompute(numWorkGroups, 1, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            }
        }

        shader.unbind();
    }

    /**
     * Reads back FFT results from the GPU into the provided arrays.
     */
    private void readbackData(float[] real, float[] imag, int n) {
        // Read real part from inputBuffer (binding 0)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_READ_ONLY, (long) n * Float.BYTES, stack.malloc((int) ((long) n * Float.BYTES)));
            if (bb != null) {
                FloatBuffer fb = bb.asFloatBuffer();
                fb.get(real);
                glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
            }
        }

        // Read imaginary part from outputBuffer (binding 1)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_READ_ONLY, (long) n * Float.BYTES, stack.malloc((int) ((long) n * Float.BYTES)));
            if (bb != null) {
                FloatBuffer fb = bb.asFloatBuffer();
                fb.get(imag);
                glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
            }
        }

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // ComputeShader – lightweight wrapper for an OpenGL compute shader
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Wraps an OpenGL compute shader program, handling compilation, linking,
     * and lifecycle management.
     */
    static class ComputeShader implements Disposable {

        private final int programId;

        /**
         * Compiles and links a compute shader from the given GLSL source.
         *
         * @param source GLSL compute shader source code
         * @throws RuntimeException if compilation or linking fails
         */
        ComputeShader(String source) {
            programId = glCreateProgram();
            if (programId == 0) {
                throw new RuntimeException("Failed to create compute shader program");
            }

            int shaderId = glCreateShader(GL_COMPUTE_SHADER);
            if (shaderId == 0) {
                throw new RuntimeException("Failed to create compute shader");
            }

            glShaderSource(shaderId, source);
            glCompileShader(shaderId);

            if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
                String log = glGetShaderInfoLog(shaderId);
                glDeleteShader(shaderId);
                glDeleteProgram(programId);
                throw new RuntimeException("Compute shader compilation failed:\n" + log);
            }

            glAttachShader(programId, shaderId);
            glLinkProgram(programId);

            if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
                String log = glGetProgramInfoLog(programId);
                glDeleteShader(shaderId);
                glDeleteProgram(programId);
                throw new RuntimeException("Compute shader program linking failed:\n" + log);
            }

            glDeleteShader(shaderId);
        }

        /**
         * Activates this compute shader program for subsequent dispatch calls.
         */
        void bind() {
            glUseProgram(programId);
        }

        /**
         * Deactivates any active shader program.
         */
        void unbind() {
            glUseProgram(0);
        }

        /**
         * Returns the OpenGL program id for uniform lookups.
         *
         * @return the program id
         */
        int programId() {
            return programId;
        }

        @Override
        public void dispose() {
            glDeleteProgram(programId);
        }
    }
}
