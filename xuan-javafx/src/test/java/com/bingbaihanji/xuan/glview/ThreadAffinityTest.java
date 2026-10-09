package com.bingbaihanji.xuan.glview;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreadAffinityTest {

    @Test
    void bindsFirstThreadAndRejectsAnotherThread() throws Exception {
        ThreadAffinity affinity = new ThreadAffinity();
        affinity.bindCurrentThread("init");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> pool.submit(() -> affinity.checkCurrentThread("render")).get());
            assertEquals(IllegalStateException.class, failure.getCause().getClass());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void allowsTheBoundThreadToContinue() {
        ThreadAffinity affinity = new ThreadAffinity();
        affinity.bindCurrentThread("init");
        affinity.checkCurrentThread("render");
        assertEquals(Thread.currentThread(), affinity.owner());
    }
}
