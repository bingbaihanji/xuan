package com.bingbaihanji.xuan.glview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceScaleTest {

    @Test
    void acceptsIndependentPositiveAxisScales() {
        DeviceScale scale = new DeviceScale(1.25, 1.5);

        assertEquals(1.25, scale.getX());
        assertEquals(1.5, scale.getY());
    }

    @Test
    void rejectsInvalidScales() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceScale(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DeviceScale(1, Double.NaN));
    }
}
