package com.bingbaihanji.xuan.glview;

import java.util.concurrent.atomic.AtomicBoolean;

/** Ensures an asynchronous request is completed at most once. */
final class RequestCompletion {

    private final AtomicBoolean completed = new AtomicBoolean();

    boolean tryComplete() {
        return completed.compareAndSet(false, true);
    }

    boolean isCompleted() {
        return completed.get();
    }
}
