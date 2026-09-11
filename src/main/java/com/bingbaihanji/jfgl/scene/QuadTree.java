package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.util.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * A quadtree is a tree data structure in which each internal node has exactly four children.
 * Quadtrees are most commonly used to partition a two-dimensional space by recursively
 * subdividing it into four quadrants or regions.
 *
 * @param <T> the type of objects stored in this quadtree
 */
public class QuadTree<T> {

    private static final int MAX_OBJECTS = 10;

    private static final int MAX_LEVELS = 5;

    private final int level;

    private final List<T> objects;

    private final Rect bounds;

    private QuadTree<T>[] nodes;

    /**
     * Creates a new QuadTree with the specified level and bounds.
     *
     * @param level  the depth level of this node in the tree
     * @param bounds the rectangular area that this node covers
     */
    @SuppressWarnings("unchecked")
    public QuadTree(int level, Rect bounds) {
        this.level = level;
        this.bounds = bounds;
        this.objects = new ArrayList<>();
        this.nodes = null;
    }

    /**
     * Clears the quadtree by recursively clearing all objects and child nodes.
     */
    public void clear() {
        objects.clear();

        if (nodes != null) {
            for (QuadTree<T> node : nodes) {
                if (node != null) {
                    node.clear();
                }
            }
            nodes = null;
        }
    }

    /**
     * Splits the node into four subnodes by dividing the area into four equal parts.
     */
    @SuppressWarnings("unchecked")
    public void split() {
        int subWidth = (int) (bounds.width / 2);
        int subHeight = (int) (bounds.height / 2);
        int x = (int) bounds.x;
        int y = (int) bounds.y;

        nodes = new QuadTree[4];
        nodes[0] = new QuadTree<>(level + 1, new Rect(x + subWidth, y, subWidth, subHeight));
        nodes[1] = new QuadTree<>(level + 1, new Rect(x, y, subWidth, subHeight));
        nodes[2] = new QuadTree<>(level + 1, new Rect(x, y + subHeight, subWidth, subHeight));
        nodes[3] = new QuadTree<>(level + 1, new Rect(x + subWidth, y + subHeight, subWidth, subHeight));
    }

    /**
     * Determines which node the object belongs to. -1 means the object cannot
     * completely fit within a child node and is part of the parent node.
     *
     * @param rect the bounding box of the object to find the index for
     * @return the index of the subnode (0-3), or -1 if it doesn't fit completely
     */
    public int getIndex(Rect rect) {
        int index = -1;
        double verticalMidpoint = bounds.x + (bounds.width / 2);
        double horizontalMidpoint = bounds.y + (bounds.height / 2);

        boolean topQuadrant = (rect.y < horizontalMidpoint
                && rect.y + rect.height < horizontalMidpoint);
        boolean bottomQuadrant = (rect.y > horizontalMidpoint);

        if (rect.x < verticalMidpoint
                && rect.x + rect.width < verticalMidpoint) {
            if (topQuadrant) {
                index = 1;
            } else if (bottomQuadrant) {
                index = 2;
            }
        } else if (rect.x > verticalMidpoint) {
            if (topQuadrant) {
                index = 0;
            } else if (bottomQuadrant) {
                index = 3;
            }
        }

        return index;
    }

    /**
     * Inserts the object into the quadtree. If the node exceeds the capacity,
     * it will split and add all objects to their corresponding nodes.
     *
     * @param object the object to insert
     * @param rect   the bounding box of the object
     */
    public void insert(T object, Rect rect) {
        if (nodes != null) {
            int index = getIndex(rect);

            if (index != -1) {
                nodes[index].insert(object, rect);
                return;
            }
        }

        objects.add(object);

        if (objects.size() > MAX_OBJECTS && level < MAX_LEVELS) {
            if (nodes == null) {
                split();
            }

            int i = 0;
            while (i < objects.size()) {
                int index = getIndex(rect);
                if (index != -1) {
                    nodes[index].insert(objects.remove(i), rect);
                } else {
                    i++;
                }
            }
        }
    }

    /**
     * Returns all objects that could collide with the given rectangle.
     *
     * @param returnList the list to populate with candidate objects
     * @param rect       the bounding box to check for collisions
     * @return the list of candidate objects
     */
    public List<T> retrieve(List<T> returnList, Rect rect) {
        int index = getIndex(rect);

        if (nodes != null && index != -1) {
            nodes[index].retrieve(returnList, rect);
        }

        returnList.addAll(objects);

        return returnList;
    }
}
