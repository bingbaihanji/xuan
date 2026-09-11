package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.scene.Node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized event dispatcher for managing and distributing events to registered handlers.
 * Provides thread-safe event handling with support for mouse, drag, and scroll events.
 */
public class EventDispatcher {

    private final Map<Node, List<MouseHandler>> mouseHandlers = new ConcurrentHashMap<>();

    private final Map<Node, List<DragHandler>> dragHandlers = new ConcurrentHashMap<>();

    private final Map<Node, List<ScrollHandler>> scrollHandlers = new ConcurrentHashMap<>();

    /**
     * Registers a mouse event handler for the specified node.
     *
     * @param node    the target node
     * @param handler the mouse handler to register
     */
    public void addMouseListener(Node node, MouseHandler handler) {
        mouseHandlers.computeIfAbsent(node, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Registers a drag event handler for the specified node.
     *
     * @param node    the target node
     * @param handler the drag handler to register
     */
    public void addDragListener(Node node, DragHandler handler) {
        dragHandlers.computeIfAbsent(node, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Registers a scroll event handler for the specified node.
     *
     * @param node    the target node
     * @param handler the scroll handler to register
     */
    public void addScrollListener(Node node, ScrollHandler handler) {
        scrollHandlers.computeIfAbsent(node, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Removes a mouse event handler from the specified node.
     *
     * @param node    the target node
     * @param handler the mouse handler to remove
     */
    public void removeMouseListener(Node node, MouseHandler handler) {
        List<MouseHandler> handlers = mouseHandlers.get(node);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                mouseHandlers.remove(node);
            }
        }
    }

    /**
     * Dispatches a mouse event to all handlers registered for the specified node.
     *
     * @param event the mouse event to dispatch
     * @param node  the target node
     */
    public void dispatchMouseEvent(MouseEvent event, Node node) {
        List<MouseHandler> handlers = mouseHandlers.get(node);
        if (handlers != null) {
            for (MouseHandler handler : handlers) {
                handler.handle(event);
                if (event.isConsumed()) {
                    break;
                }
            }
        }
    }

    /**
     * Dispatches a drag event to all handlers registered for the specified node.
     *
     * @param event the drag event to dispatch
     * @param node  the target node
     */
    public void dispatchDragEvent(DragEvent event, Node node) {
        List<DragHandler> handlers = dragHandlers.get(node);
        if (handlers != null) {
            for (DragHandler handler : handlers) {
                handler.handle(event);
                if (event.isConsumed()) {
                    break;
                }
            }
        }
    }

    /**
     * Dispatches a scroll event to all handlers registered for the specified node.
     *
     * @param event the scroll event to dispatch
     * @param node  the target node
     */
    public void dispatchScrollEvent(ScrollEvent event, Node node) {
        List<ScrollHandler> handlers = scrollHandlers.get(node);
        if (handlers != null) {
            for (ScrollHandler handler : handlers) {
                handler.handle(event);
                if (event.isConsumed()) {
                    break;
                }
            }
        }
    }

    /**
     * Removes all event handlers registered for the specified node.
     *
     * @param node the target node
     */
    public void clear(Node node) {
        mouseHandlers.remove(node);
        dragHandlers.remove(node);
        scrollHandlers.remove(node);
    }

    /**
     * Removes all registered event handlers for all nodes.
     */
    public void clearAll() {
        mouseHandlers.clear();
        dragHandlers.clear();
        scrollHandlers.clear();
    }

    @FunctionalInterface
    public interface MouseHandler {

        void handle(MouseEvent event);
    }

    @FunctionalInterface
    public interface DragHandler {

        void handle(DragEvent event);
    }

    @FunctionalInterface
    public interface ScrollHandler {

        void handle(ScrollEvent event);
    }
}
