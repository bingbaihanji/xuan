package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.math.Vec2;

/**
 * Represents a mouse event with type, position, button, and click count information.
 */
public final class MouseEvent {

    private final Type type;

    private final Vec2 position;

    private final int button;

    private final int clickCount;

    private boolean consumed;

    /**
     * Constructs a new MouseEvent.
     *
     * @param type       the type of mouse event
     * @param position   the position of the mouse
     * @param button     the button that was pressed/released (0 for none, 1 for left, 2 for middle, 3 for right)
     * @param clickCount the number of clicks (1 for single click, 2 for double click, etc.)
     */
    public MouseEvent(Type type, Vec2 position, int button, int clickCount) {
        this.type = type;
        this.position = position;
        this.button = button;
        this.clickCount = clickCount;
        this.consumed = false;
    }

    /**
     * Returns the type of this mouse event.
     *
     * @return the event type
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the position of the mouse when this event occurred.
     *
     * @return the mouse position
     */
    public Vec2 getPosition() {
        return position;
    }

    /**
     * Returns the button involved in this event.
     *
     * @return the button code (0 for none, 1 for left, 2 for middle, 3 for right)
     */
    public int getButton() {
        return button;
    }

    /**
     * Returns the click count for this event.
     *
     * @return the number of clicks (1 for single click, 2 for double click, etc.)
     */
    public int getClickCount() {
        return clickCount;
    }

    /**
     * Returns whether this event has been consumed.
     *
     * @return true if the event has been consumed, false otherwise
     */
    public boolean isConsumed() {
        return consumed;
    }

    /**
     * Marks this event as consumed, preventing further processing by other handlers.
     */
    public void consume() {
        this.consumed = true;
    }

    @Override
    public String toString() {
        return "MouseEvent[" +
                "type=" + type +
                ", position=" + position +
                ", button=" + button +
                ", clickCount=" + clickCount +
                ", consumed=" + consumed +
                ']';
    }

    /**
     * The type of mouse event.
     */
    public enum Type {
        PRESSED,
        RELEASED,
        MOVED,
        CLICKED,
        ENTERED,
        EXITED
    }
}
