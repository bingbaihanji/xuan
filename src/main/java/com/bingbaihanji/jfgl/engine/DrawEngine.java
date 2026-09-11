package com.bingbaihanji.jfgl.engine;

import com.bingbaihanji.jfgl.event.EventDispatcher;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.math.Mat3;
import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.BatchRenderer;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.renderer.TextRenderer;
import com.bingbaihanji.jfgl.scene.Scene;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

/**
 * 核心绘图引擎，负责协调渲染、相机变换和事件分发。
 * <p>
 * 引擎拥有 {@link BatchRenderer}（批量渲染器）、{@link TextRenderer}（文本渲染器）和
 * {@link RenderContext}（渲染上下文）用于绘制，{@link Scene}（场景）用于保留模式的场景图，
 * {@link EventDispatcher}（事件分发器）用于输入处理。内部通过位置、缩放和旋转管理一个简单的
 * 2D 相机，每帧将计算得到的视图-投影矩阵推送到渲染上下文。
 *
 * <p>使用示例：
 * <pre>{@code
 * DrawEngine engine = new DrawEngine(gl, 1280, 720);
 * engine.initialize();
 *
 * // 在渲染循环中：
 * engine.getRenderScheduler().requestRender();
 * engine.render();
 *
 * // 窗口大小改变时：
 * engine.resize(newWidth, newHeight);
 *
 * // 关闭时释放资源：
 * engine.dispose();
 * }</pre>
 */
public class DrawEngine implements Disposable {

    /** OpenGL 抽象层 */
    private final GLAbstraction gl;

    /** 批量渲染器 */
    private final BatchRenderer batchRenderer;

    /** 文本渲染器 */
    private final TextRenderer textRenderer;

    /** 渲染上下文 */
    private final RenderContext renderContext;

    /** 事件分发器 */
    private final EventDispatcher eventDispatcher;

    /** 渲染调度器 */
    private final RenderScheduler renderScheduler;

    /** 当前场景 */
    private Scene scene;

    /** 清屏颜色 */
    private Color clearColor;

    /** 视口宽度（像素） */
    private int width;

    /** 视口高度（像素） */
    private int height;

    // 相机状态
    /** 相机位置（世界坐标） */
    private Vec2 cameraPosition;

    /** 相机缩放因子 */
    private float cameraZoom;

    /** 相机旋转角度（弧度） */
    private float cameraRotation;

    // 缓存的视图-投影矩阵
    private Mat3 viewProjectionMatrix;

    /**
     * 创建一个新的绘图引擎实例。
     * <p>
     * 清屏颜色默认为黑色，相机位于原点，无缩放和旋转。
     *
     * @param gl     OpenGL 抽象层
     * @param width  初始视口宽度（像素）
     * @param height 初始视口高度（像素）
     */
    public DrawEngine(GLAbstraction gl, int width, int height) {
        this.gl = gl;
        this.width = width;
        this.height = height;

        this.batchRenderer = new BatchRenderer(gl);
        this.textRenderer = new TextRenderer(gl, 16);
        this.renderContext = new RenderContext(gl);
        this.eventDispatcher = new EventDispatcher();
        this.renderScheduler = new RenderScheduler();

        this.scene = new Scene();
        this.clearColor = Color.BLACK;
        this.cameraPosition = Vec2.ZERO;
        this.cameraZoom = 1.0f;
        this.cameraRotation = 0.0f;

        this.viewProjectionMatrix = Mat3.identity();
    }

    /**
     * 初始化引擎。
     * <p>
     * 设置 OpenGL 视口、混合状态，并计算初始视图-投影矩阵。
     */
    public void initialize() {
        gl.initialize();
        gl.setViewport(0, 0, width, height);
        gl.enableBlend();
        updateViewProjectionMatrix();
    }

    /**
     * 渲染一帧。
     * <p>
     * 如果渲染调度器指示不需要渲染（按需模式），则立即返回。
     * <p>
     * 每帧执行以下操作：
     * <ol>
     *   <li>根据相机状态重新计算视图-投影矩阵</li>
     *   <li>使用当前清屏颜色清除视口</li>
     *   <li>通过渲染上下文渲染场景图</li>
     * </ol>
     */
    public void render() {
        if (!renderScheduler.shouldRender()) {
            return;
        }

        updateViewProjectionMatrix();
        renderContext.setViewProjectionMatrix(viewProjectionMatrix);

        gl.clear(clearColor);

        if (scene != null) {
            scene.render(renderContext);
        }
    }

    /**
     * 调整视口大小并更新内部尺寸。
     *
     * @param w 新的宽度（像素）
     * @param h 新的高度（像素）
     */
    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
        gl.setViewport(0, 0, w, h);
        renderScheduler.requestRender();
    }

    // -----------------------------------------------------------------------
    // 场景管理
    // -----------------------------------------------------------------------

    /**
     * 获取当前场景。
     *
     * @return 当前活动场景，如果未设置则返回 {@code null}
     */
    public Scene getScene() {
        return scene;
    }

    /**
     * 设置活动场景。
     * <p>
     * 之前的场景不会被自动释放，调用者需要负责释放不再需要的场景。
     *
     * @param scene 新场景（可以为 {@code null}）
     */
    public void setScene(Scene scene) {
        this.scene = scene;
        renderScheduler.requestRender();
    }

    // -----------------------------------------------------------------------
    // 事件分发
    // -----------------------------------------------------------------------

    /**
     * 获取用于注册和分发输入事件的事件分发器。
     *
     * @return 事件分发器
     */
    public EventDispatcher getEventDispatcher() {
        return eventDispatcher;
    }

    // -----------------------------------------------------------------------
    // 相机控制
    // -----------------------------------------------------------------------

    /**
     * 获取相机在世界坐标系中的位置。
     *
     * @return 相机位置
     */
    public Vec2 getCameraPosition() {
        return cameraPosition;
    }

    /**
     * 设置相机在世界坐标系中的位置。
     * <p>
     * 相机将注视此位置，该位置位于视口中心。
     *
     * @param position 新的相机位置
     */
    public void setCameraPosition(Vec2 position) {
        this.cameraPosition = position;
        renderScheduler.requestRender();
    }

    /**
     * 获取相机缩放因子。
     * <p>
     * 值为 1.0 表示无缩放；大于 1.0 放大，小于 1.0 缩小。
     *
     * @return 缩放因子
     */
    public float getCameraZoom() {
        return cameraZoom;
    }

    /**
     * 设置相机缩放因子。
     * <p>
     * 缩放值会被限制为正数。
     *
     * @param zoom 新的缩放因子（必须大于零）
     */
    public void setCameraZoom(float zoom) {
        this.cameraZoom = Math.max(zoom, Float.MIN_VALUE);
        renderScheduler.requestRender();
    }

    /**
     * 获取相机旋转角度（弧度）。
     * <p>
     * 正值表示逆时针旋转。
     *
     * @return 旋转角度（弧度）
     */
    public float getCameraRotation() {
        return cameraRotation;
    }

    /**
     * 设置相机旋转角度（弧度）。
     *
     * @param rotation 新的旋转角度
     */
    public void setCameraRotation(float rotation) {
        this.cameraRotation = rotation;
        renderScheduler.requestRender();
    }

    // -----------------------------------------------------------------------
    // 坐标转换
    // -----------------------------------------------------------------------

    /**
     * 将屏幕（像素）坐标转换为世界坐标。
     * <p>
     * 屏幕坐标以左上角为原点 (0, 0)，y 轴向下增长。
     * 世界坐标是场景的坐标空间。
     *
     * @param screenPoint 屏幕坐标点
     * @return 对应的世界坐标点
     */
    public Vec2 screenToWorld(Vec2 screenPoint) {
        Mat3 inverse = computeInverseViewProjection();
        return inverse.transform(screenPoint);
    }

    /**
     * 将世界坐标转换为屏幕（像素）坐标。
     *
     * @param worldPoint 世界坐标点
     * @return 对应的屏幕坐标点
     */
    public Vec2 worldToScreen(Vec2 worldPoint) {
        updateViewProjectionMatrix();
        return viewProjectionMatrix.transform(worldPoint);
    }

    // -----------------------------------------------------------------------
    // 渲染器访问
    // -----------------------------------------------------------------------

    /**
     * 获取用于绘制四边形和形状的批量渲染器。
     *
     * @return 批量渲染器
     */
    public BatchRenderer getBatchRenderer() {
        return batchRenderer;
    }

    /**
     * 获取用于绘制文本的文本渲染器。
     *
     * @return 文本渲染器
     */
    public TextRenderer getTextRenderer() {
        return textRenderer;
    }

    /**
     * 获取保存共享渲染状态的渲染上下文。
     *
     * @return 渲染上下文
     */
    public RenderContext getRenderContext() {
        return renderContext;
    }

    /**
     * 获取控制帧生成的渲染调度器。
     *
     * @return 渲染调度器
     */
    public RenderScheduler getRenderScheduler() {
        return renderScheduler;
    }

    // -----------------------------------------------------------------------
    // 其他访问器
    // -----------------------------------------------------------------------

    /**
     * 获取当前视口宽度（像素）。
     *
     * @return 宽度
     */
    public int getWidth() {
        return width;
    }

    /**
     * 获取当前视口高度（像素）。
     *
     * @return 高度
     */
    public int getHeight() {
        return height;
    }

    /**
     * 获取当前清屏颜色。
     *
     * @return 清屏颜色
     */
    public Color getClearColor() {
        return clearColor;
    }

    /**
     * 设置每帧开始时使用的清屏颜色。
     *
     * @param clearColor 新的清屏颜色
     */
    public void setClearColor(Color clearColor) {
        this.clearColor = clearColor;
    }

    /**
     * 获取底层的 GL 抽象层。
     *
     * @return GL 抽象层
     */
    public GLAbstraction getGl() {
        return gl;
    }

    // -----------------------------------------------------------------------
    // 资源释放
    // -----------------------------------------------------------------------

    /**
     * 释放此引擎拥有的所有资源，包括渲染器、场景和 GL 资源。
     */
    @Override
    public void dispose() {
        if (scene != null) {
            scene.dispose();
            scene = null;
        }
        textRenderer.dispose();
        batchRenderer.dispose();
        gl.dispose();
    }

    // -----------------------------------------------------------------------
    // 内部辅助方法
    // -----------------------------------------------------------------------

    /**
     * 根据当前相机状态重新计算视图-投影矩阵。
     * <p>
     * 该矩阵将世界坐标转换为屏幕坐标：
     * <pre>
     * VP = 平移(width/2, height/2) * 缩放(zoom) * 旋转(-rotation) * 平移(-cameraPos)
     * </pre>
     * 这将相机位置置于视口中心，应用缩放，然后应用旋转。
     */
    private void updateViewProjectionMatrix() {
        // 步骤1：平移使相机位置位于原点
        Mat3 translateToOrigin = Mat3.translation(-cameraPosition.x(), -cameraPosition.y());

        // 步骤2：应用反向旋转（将世界向相机的反方向旋转）
        Mat3 rotate = Mat3.rotation(-cameraRotation);

        // 步骤3：应用缩放
        Mat3 zoom = Mat3.scale(cameraZoom, cameraZoom);

        // 步骤4：平移到屏幕中心
        Mat3 translateToCenter = Mat3.translation(width / 2.0f, height / 2.0f);

        // 组合：VP = T(center) * S(zoom) * R(-rotation) * T(-position)
        viewProjectionMatrix = translateToCenter.multiply(zoom.multiply(rotate.multiply(translateToOrigin)));
    }

    /**
     * 计算当前视图-投影矩阵的逆矩阵，用于屏幕坐标到世界坐标的转换。
     * <p>
     * 由于矩阵由简单的仿射变换组成，我们对每个分量求逆并按相反顺序应用：
     * <pre>
     * VP^-1 = 平移(position) * 旋转(rotation) * 缩放(1/zoom) * 平移(-center)
     * </pre>
     *
     * @return 逆视图-投影矩阵
     */
    private Mat3 computeInverseViewProjection() {
        // 平移到中心的逆 = 平移 -center
        Mat3 invTranslateToCenter = Mat3.translation(-width / 2.0f, -height / 2.0f);

        // 缩放 zoom 的逆 = 缩放 1/zoom
        float invZoom = 1.0f / Math.max(cameraZoom, Float.MIN_VALUE);
        Mat3 invZoomMat = Mat3.scale(invZoom, invZoom);

        // 旋转 -rotation 的逆 = 旋转 +rotation
        Mat3 invRotate = Mat3.rotation(cameraRotation);

        // 平移 -position 的逆 = 平移 +position
        Mat3 invTranslateToOrigin = Mat3.translation(cameraPosition.x(), cameraPosition.y());

        // 反向顺序：T(pos) * R(rot) * S(1/zoom) * T(-center)
        return invTranslateToOrigin.multiply(invRotate.multiply(invZoomMat.multiply(invTranslateToCenter)));
    }

}
