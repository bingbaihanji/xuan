package com.bingbaihanji.xuan.glview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferLifecycleTest {

    @Test
    void transitionsOnceAndRejectsRepeatedDispose() {
        TransferLifecycle lifecycle = new TransferLifecycle();

        assertTrue(lifecycle.isActive());
        assertTrue(lifecycle.beginDispose());
        assertEquals(TransferLifecycle.State.DISPOSING, lifecycle.state());
        assertFalse(lifecycle.beginDispose());
        lifecycle.markDisposed();
        assertEquals(TransferLifecycle.State.DISPOSED, lifecycle.state());
        assertTrue(lifecycle.isClosed());
    }

    @Test
    void onlyOneConcurrentCallerWinsDispose() throws Exception {
        TransferLifecycle lifecycle = new TransferLifecycle();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ArrayList<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return lifecycle.beginDispose();
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(2, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }
}
