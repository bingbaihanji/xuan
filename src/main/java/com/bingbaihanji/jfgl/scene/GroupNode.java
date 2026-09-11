package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.util.Disposable;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A composite scene graph node that holds an ordered list of child nodes.
 * <p>
 * Rendering iterates children front-to-back; hit-testing walks them in
 * reverse (back-to-front) so that the topmost visually overlapping child
 * is picked first.  Bounds are the axis-aligned union of all children.
 */
public class GroupNode extends Node implements Disposable {

    private final String name;

    private final List<Node> children = new ArrayList<>();

    /**
     * Creates a group node with an empty name.
     */
    public GroupNode() {
        this("");
    }

    /**
     * Creates a group node with the given name.
     *
     * @param name a human-readable label for this group
     */
    public GroupNode(String name) {
        this.name = name;
    }

    /**
     * Returns the name of this group node.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    // ------------------------------------------------------------------
    // Child management
    // ------------------------------------------------------------------

    /**
     * Appends a child node.  Adding {@code null} or a node that is
     * already a child is a no-op.
     *
     * @param child the node to add
     */
    public void add(Node child) {
        if (child != null && !children.contains(child)) {
            children.add(child);
        }
    }

    /**
     * Removes a child node.  If the node is not a child this is a no-op.
     *
     * @param child the node to remove
     */
    public void remove(Node child) {
        children.remove(child);
    }

    /**
     * Returns an unmodifiable view of the children list.
     *
     * @return the children
     */
    public List<Node> getChildren() {
        return Collections.unmodifiableList(children);
    }

    // ------------------------------------------------------------------
    // Node overrides
    // ------------------------------------------------------------------

    /**
     * Renders all visible children in order.
     *
     * @param context the current render context
     */
    @Override
    public void render(RenderContext context) {
        for (Node child : children) {
            if (child.isVisible()) {
                child.render(context);
            }
        }
    }

    /**
     * Computes the axis-aligned bounding rectangle that encloses every
     * child.  Returns {@code null} when there are no children or when
     * every child reports a {@code null} bounds.
     *
     * @return the union of all children bounds, or {@code null}
     */
    @Override
    public Rect getBounds() {
        Rect result = null;

        for (Node child : children) {
            Rect cb = child.getBounds();
            if (cb == null) {
                continue;
            }
            if (result == null) {
                result = new Rect(cb.x, cb.y, cb.width, cb.height);
            } else {
                float minX = Math.min(result.x, cb.x);
                float minY = Math.min(result.y, cb.y);
                float maxX = Math.max(result.x + result.width, cb.x + cb.width);
                float maxY = Math.max(result.y + result.height, cb.y + cb.height);
                result = new Rect(minX, minY, maxX - minX, maxY - minY);
            }
        }

        return result;
    }

    /**
     * Tests whether the given point hits any child.  Children are
     * checked in reverse order so that the topmost (last-added) child
     * takes priority.
     *
     * @param point the point to test in local coordinates
     * @return true if any child is hit
     */
    @Override
    public boolean hitTest(Vec2 point) {
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).hitTest(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Disposes all children in reverse order and clears the list.
     */
    @Override
    public void dispose() {
        for (int i = children.size() - 1; i >= 0; i--) {
            Node child = children.get(i);
            if (child instanceof Disposable) {
                ((Disposable) child).dispose();
            }
        }
        children.clear();
    }

    @Override
    public String toString() {
        return "GroupNode{name='%s', children=%d}".formatted(name, children.size());
    }
}
