package com.bingbaihanji.xuan.util;

/**
 * Represents a resource that can be disposed to release associated resources.
 */
public interface Disposable {

    /**
     * Disposes of this resource, releasing any underlying resources.
     */
    void dispose();
}
