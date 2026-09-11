package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.math.Vec2;

public final class DragEvent {

    private final Type type;

    private final Vec2 position;

    private final Vec2 delta;

    private final int button;

    private boolean consumed;

    public DragEvent(Type type, Vec2 position, Vec2 delta, int button) {
        this.type = type;
        this.position = position;
        this.delta = delta;
        this.button = button;
    }

    public Type getType() {
        return type;
    }

    public Vec2 getPosition() {
        return position;
    }

    public Vec2 getDelta() {
        return delta;
    }

    public int getButton() {
        return button;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void consume() {
        this.consumed = true;
    }

    public enum Type {
        STARTED,
        DRAGGING,
        ENDED
    }
}
