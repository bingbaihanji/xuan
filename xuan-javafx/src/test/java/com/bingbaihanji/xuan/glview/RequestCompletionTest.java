package com.bingbaihanji.xuan.glview;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestCompletionTest {

    @Test
    void completesOnlyOnceAcrossThreads() throws Exception {
        RequestCompletion completion = new RequestCompletion();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(completion::tryComplete);
            Future<Boolean> second = pool.submit(completion::tryComplete);
            int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, winners);
            assertTrue(completion.isCompleted());
        } finally {
            pool.shutdownNow();
        }
    }
}
