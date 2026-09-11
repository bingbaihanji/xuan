package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 场景图，包含节点的层次结构。
 * <p>
 * 场景拥有一个根 {@link GroupNode}，并维护一个所有节点的扁平列表，
 * 用于遍历和释放资源。
 */
public class Scene implements Disposable {

    /** 根节点组 */
    private final GroupNode root;

    /** 所有节点的扁平列表 */
    private final List<Node> allNodes;

    /**
     * 创建一个新的空场景。
     */
    public Scene() {
        this.root = new GroupNode("root");
        this.allNodes = new ArrayList<>();
        this.allNodes.add(this.root);
    }

    /**
     * 获取此场景的根节点组。
     *
     * @return 根 {@link GroupNode}
     */
    public GroupNode getRoot() {
        return root;
    }

    /**
     * 向此场景添加节点。
     * <p>
     * 节点将被注册到扁平节点列表中，如果没有父节点则附加到根节点组。
     *
     * @param node 要添加的节点
     * @throws IllegalArgumentException 如果节点为 null
     */
    public void add(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("不能向场景添加空节点。");
        }
        if (!allNodes.contains(node)) {
            allNodes.add(node);
        }
        if (node.getParent() == null) {
            root.add(node);
        }
    }

    /**
     * 从此场景中移除节点。
     * <p>
     * 节点将从扁平节点列表中移除，并从其父节点中分离。
     *
     * @param node 要移除的节点
     */
    public void remove(Node node) {
        if (node == null) {
            return;
        }
        allNodes.remove(node);
        if (node.getParent() instanceof GroupNode) {
            ((GroupNode) node.getParent()).remove(node);
        }
    }

    /**
     * 从根节点开始渲染整个场景图。
     *
     * @param renderContext 当前渲染上下文
     */
    public void render(RenderContext renderContext) {
        root.render(renderContext);
    }

    /**
     * 获取此场景中所有节点的不可修改视图。
     *
     * @return 所有节点的不可修改列表
     */
    public List<Node> getAllNodes() {
        return Collections.unmodifiableList(allNodes);
    }

    /**
     * 释放此场景中的所有节点，释放其资源。
     * <p>
     * 释放后不应再使用此场景。
     */
    @Override
    public void dispose() {
        for (int i = allNodes.size() - 1; i >= 0; i--) {
            allNodes.get(i).dispose();
        }
        allNodes.clear();
    }
}
