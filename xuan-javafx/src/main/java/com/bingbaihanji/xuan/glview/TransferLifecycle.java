package com.bingbaihanji.xuan.glview;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe lifecycle gate for the JavaFX/GL bridge. */
final class TransferLifecycle {

    enum State { ACTIVE, DISPOSING, DISPOSED }

    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    boolean beginDispose() {
        return state.compareAndSet(State.ACTIVE, State.DISPOSING);
    }

    void markDisposed() {
        state.set(State.DISPOSED);
    }

    boolean isActive() {
        return state.get() == State.ACTIVE;
    }

    boolean isClosed() {
        return state.get() != State.ACTIVE;
    }

    State state() {
        return state.get();
    }
}
