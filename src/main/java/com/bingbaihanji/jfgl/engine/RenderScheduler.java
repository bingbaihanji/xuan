package com.bingbaihanji.jfgl.engine;

/**
 * Manages render scheduling, supporting both continuous and on-demand rendering modes.
 * <p>
 * In continuous mode, every call to {@link #shouldRender()} returns {@code true}.
 * In on-demand mode, rendering only occurs after {@link #requestRender()} has been
 * called since the last frame was consumed.
 */
public class RenderScheduler {

    private Mode mode;

    private volatile boolean renderRequested;

    /**
     * Creates a new {@code RenderScheduler} in {@link Mode#CONTINUOUS} mode.
     */
    public RenderScheduler() {
        this(Mode.CONTINUOUS);
    }

    /**
     * Creates a new {@code RenderScheduler} with the specified mode.
     *
     * @param mode the initial rendering mode
     */
    public RenderScheduler(Mode mode) {
        this.mode = mode;
        this.renderRequested = false;
    }

    /**
     * Requests that a render frame be produced. In {@link Mode#ON_DEMAND} mode,
     * the next call to {@link #shouldRender()} will return {@code true} and
     * the flag will be consumed. In {@link Mode#CONTINUOUS} mode this is a no-op.
     */
    public void requestRender() {
        renderRequested = true;
    }

    /**
     * Checks whether a render should occur this frame and consumes the request
     * in on-demand mode.
     *
     * @return {@code true} if the engine should render a frame
     */
    public boolean shouldRender() {
        if (mode == Mode.CONTINUOUS) {
            return true;
        }
        if (renderRequested) {
            renderRequested = false;
            return true;
        }
        return false;
    }

    /**
     * Returns the current rendering mode.
     *
     * @return the rendering mode
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Sets the rendering mode.
     *
     * @param mode the new rendering mode
     */
    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.CONTINUOUS) {
            renderRequested = false;
        }
    }

    /**
     * Returns whether a render has been requested but not yet consumed.
     *
     * @return {@code true} if a render is pending
     */
    public boolean isRenderPending() {
        return renderRequested;
    }

    /**
     * Rendering mode for the scheduler.
     */
    public enum Mode {
        /** Renders every frame unconditionally. */
        CONTINUOUS,
        /** Renders only when a frame has been explicitly requested. */
        ON_DEMAND
    }
}
