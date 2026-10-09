package com.bingbaihanji.xuan.util;

/**
 * 表示可以释放以释放关联资源的资源
 */
public interface Disposable {

    /**
     * 处置此资源，释放任何底层资源
     */
    void dispose();
}
