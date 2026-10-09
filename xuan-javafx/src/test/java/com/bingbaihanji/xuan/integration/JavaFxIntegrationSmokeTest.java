package com.bingbaihanji.xuan.integration;

import com.bingbaihanji.xuan.test.JavaFxTestSupport;
import com.bingbaihanji.xuan.view.MainView;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFxIntegrationSmokeTest {

    @Test
    void constructsMainViewOnJavaFxThread() throws Exception {
        assumeTrue(JavaFxTestSupport.isAvailable(), () ->
                "JavaFX Toolkit unavailable: " + JavaFxTestSupport.startupFailure());

        BorderPane root = JavaFxTestSupport.call(() -> {
            assertTrue(Platform.isFxApplicationThread());
            return new MainView().createMainView();
        });

        assertNotNull(root);
    }
}
