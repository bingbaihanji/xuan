package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.math.Vec2;

/**
 * Event representing a scroll action with position and delta information.
 */
public class ScrollEvent {

    private final Vec2 position;

    private final double deltaX;

    private final double deltaY;

    private boolean consumed;

    /**
     * Creates a new ScrollEvent.
     *
     * @param position the position where the scroll occurred
     * @param deltaX   the horizontal scroll delta
     * @param deltaY   the vertical scroll delta
     */
    public ScrollEvent(Vec2 position, double deltaX, double deltaY) {
        this.position = position;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.consumed = false;
    }

    /**
     * Returns the position where the scroll occurred.
     *
     * @return the scroll position
     */
    public Vec2 getPosition() {
        return position;
    }

    /**
     * Returns the horizontal scroll delta.
     *
     * @return the horizontal delta
     */
    public double getDeltaX() {
        return deltaX;
    }

    /**
     * Returns the vertical scroll delta.
     *
     * @return the vertical delta
     */
    public double getDeltaY() {
        return deltaY;
    }

    /**
     * Returns whether this event has been consumed.
     *
     * @return true if consumed, false otherwise
     */
    public boolean isConsumed() {
        return consumed;
    }

    /**
     * Marks this event as consumed, preventing further processing.
     */
    public void consume() {
        this.consumed = true;
    }
}
