package com.bingbaihanji.xuan.glview;

import java.util.concurrent.atomic.AtomicReference;

/** Small runtime guard for callbacks that must stay on one GL executor thread. */
final class ThreadAffinity {

    private final AtomicReference<Thread> owner = new AtomicReference<>();

    void bindCurrentThread(String operation) {
        Thread current = Thread.currentThread();
        Thread previous = owner.get();
        if (previous == null && owner.compareAndSet(null, current)) {
            return;
        }
        checkCurrentThread(operation);
    }

    void checkCurrentThread(String operation) {
        Thread expected = owner.get();
        if (expected != null && expected != Thread.currentThread()) {
            throw new IllegalStateException(
                    operation + " 必须在 GL 线程执行；期望 " + expected.getName()
                            + "，实际 " + Thread.currentThread().getName());
        }
    }

    Thread owner() {
        return owner.get();
    }
}
