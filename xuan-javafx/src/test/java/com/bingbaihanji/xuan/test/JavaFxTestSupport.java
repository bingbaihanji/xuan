package com.bingbaihanji.xuan.test;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Minimal JavaFX toolkit bridge for integration tests. */
public final class JavaFxTestSupport {

    private static final long TIMEOUT_SECONDS = 5;
    private static volatile boolean started;
    private static volatile Throwable startupFailure;

    private JavaFxTestSupport() {
    }

    public static synchronized boolean isAvailable() {
        if (started) {
            return true;
        }
        if (startupFailure != null) {
            return false;
        }
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
            started = true;
            return true;
        } catch (IllegalStateException alreadyStarted) {
            started = true;
            return true;
        } catch (Throwable failure) {
            startupFailure = failure;
            return false;
        }
    }

    public static Throwable startupFailure() {
        return startupFailure;
    }

    public static <T> T call(Callable<T> task) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("JavaFX Toolkit 不可用", startupFailure);
        }
        if (Platform.isFxApplicationThread()) {
            return task.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
