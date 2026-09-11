package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.math.Transform;
import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.style.Style;
import com.bingbaihanji.jfgl.util.Disposable;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.Objects;

/**
 * 2D 场景图中所有节点的抽象基类。
 * <p>
 * 每个节点都有一个 {@link Transform}，定义其相对于父节点的位置、旋转和缩放。
 * 节点形成树状层次结构，支持局部空间、父空间和场景（根）空间之间的坐标转换。
 * <p>
 * 子类必须实现 {@link #render(RenderContext)} 来绘制自身，
 * 实现 {@link #getBounds()} 来定义局部空间的边界矩形。
 */
public abstract class Node implements Disposable {

    /** 此节点的局部变换（位置、旋转、缩放） */
    protected final Transform transform;

    /** 渲染此节点时使用的组合样式 */
    protected Style style;

    /** 此节点是否可见并应该被渲染 */
    protected boolean visible = true;

    /** 此节点（或其后代）自上次渲染以来是否被修改过 */
    protected boolean dirty = true;

    /** 此节点的可选标识符 */
    protected String id;

    /** 场景图中的父节点，根节点为 {@code null} */
    protected Node parent;

    /**
     * 创建一个具有单位变换且无样式的新节点。
     */
    protected Node() {
        this.transform = new Transform();
    }

    /**
     * 创建一个具有指定局部变换的新节点。
     *
     * @param transform 局部变换（不能为 {@code null}）
     */
    protected Node(Transform transform) {
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    // ---- 抽象契约 -----------------------------------------------------------

    /**
     * 使用提供的渲染上下文渲染此节点。
     *
     * @param context 当前渲染上下文
     */
    public abstract void render(RenderContext context);

    /**
     * 获取此节点在局部坐标系中的轴对齐边界矩形。
     *
     * @return 局部空间的边界矩形
     */
    public abstract Rect getBounds();

    // ---- 变换 ---------------------------------------------------------------

    /**
     * 获取此节点的局部变换。
     *
     * @return 局部变换
     */
    public Transform getTransform() {
        return transform;
    }

    // ---- 样式 ---------------------------------------------------------------

    /**
     * 获取此节点的组合样式。
     *
     * @return 当前样式，如果未设置则返回 {@code null}
     */
    public Style getStyle() {
        return style;
    }

    /**
     * 设置此节点的组合样式。
     *
     * @param style 新样式，{@code null} 表示清除
     */
    public void setStyle(Style style) {
        this.style = style;
        markDirty();
    }

    // ---- 可见性 -------------------------------------------------------------

    /**
     * 获取此节点是否可见。
     *
     * @return 如果此节点应该被渲染则返回 {@code true}
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * 设置此节点的可见性。
     *
     * @param visible {@code true} 使此节点可见
     */
    public void setVisible(boolean visible) {
        if (this.visible != visible) {
            this.visible = visible;
            markDirty();
        }
    }

    // ---- 标识符 -------------------------------------------------------------

    /**
     * 获取此节点的标识符。
     *
     * @return 标识符，如果未设置则返回 {@code null}
     */
    public String getId() {
        return id;
    }

    /**
     * 设置此节点的标识符。
     *
     * @param id 新标识符，{@code null} 表示清除
     */
    public void setId(String id) {
        this.id = id;
    }

    // ---- 父节点 -------------------------------------------------------------

    /**
     * 获取场景图中的父节点。
     *
     * @return 父节点，根节点返回 {@code null}
     */
    public Node getParent() {
        return parent;
    }

    /**
     * 设置此节点的父节点。
     * <p>
     * 通常由容器或场景管理，不应由用户代码直接调用。
     *
     * @param parent 新的父节点
     */
    public void setParent(Node parent) {
        this.parent = parent;
    }

    // ---- 脏标记 -------------------------------------------------------------

    /**
     * 获取此节点是否为脏状态，需要重新渲染。
     *
     * @return 如果此节点自上次渲染以来被修改过则返回 {@code true}
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 将此节点标记为脏状态，表示需要重新渲染。
     * <p>
     * 同时将脏标记传播到祖先节点，以便场景可以高效地检测哪些子树需要更新。
     */
    public void markDirty() {
        if (!dirty) {
            dirty = true;
            if (parent != null) {
                parent.markDirty();
            }
        }
    }

    // ---- 坐标转换 -----------------------------------------------------------

    /**
     * 将点从此节点的局部空间转换到父空间。
     *
     * @param localPoint 局部坐标点
     * @return 父坐标点
     */
    public Vec2 localToParent(Vec2 localPoint) {
        return transform.transformPoint(localPoint);
    }

    /**
     * 将点从父空间转换到此节点的局部空间。
     *
     * @param parentPoint 父坐标点
     * @return 局部坐标点
     */
    public Vec2 parentToLocal(Vec2 parentPoint) {
        return transform.inverseTransform(parentPoint);
    }

    /**
     * 将点从此节点的局部空间转换到场景（根）空间。
     * <p>
     * 沿父链向上遍历，依次应用每个祖先的局部到父空间变换，直到到达根节点。
     *
     * @param localPoint 局部坐标点
     * @return 场景（根）坐标点
     */
    public Vec2 localToScene(Vec2 localPoint) {
        Vec2 point = localToParent(localPoint);
        if (parent != null) {
            return parent.localToScene(point);
        }
        return point;
    }

    /**
     * 将点从场景（根）空间转换到此节点的局部空间。
     * <p>
     * 从根节点沿父链向下遍历，按相反顺序应用每个祖先的父空间到局部空间变换。
     *
     * @param scenePoint 场景（根）坐标点
     * @return 局部坐标点
     */
    public Vec2 sceneToLocal(Vec2 scenePoint) {
        Vec2 point = scenePoint;
        if (parent != null) {
            point = parent.sceneToLocal(point);
        }
        return parentToLocal(point);
    }

    // ---- 碰撞检测 -----------------------------------------------------------

    /**
     * 测试场景坐标中的给定点是否与此节点的边界相交。
     * <p>
     * 点被转换到局部空间，然后与 {@link #getBounds()} 进行测试。
     * 如果此节点不可见或没有边界则返回 {@code false}。
     *
     * @param point 场景坐标点
     * @return 如果点命中此节点则返回 {@code true}
     */
    public boolean hitTest(Vec2 point) {
        if (!visible) {
            return false;
        }
        Vec2 localPoint = sceneToLocal(point);
        Rect bounds = getBounds();
        return bounds != null && bounds.contains(localPoint);
    }

    // ---- 资源释放 -----------------------------------------------------------

    /**
     * 释放此节点并释放所有资源。
     * <p>
     * 持有原生或 GPU 资源的子类应重写此方法并调用 {@code super.dispose()}。
     */
    @Override
    public void dispose() {
        // 默认空操作；子类释放自己的资源
    }

    // ---- Object 重写 --------------------------------------------------------

    @Override
    public String toString() {
        return "Node{id='%s', visible=%b, dirty=%b, transform=%s}"
                .formatted(id, visible, dirty, transform);
    }
}
