package io.dcloud.feature.uniapp.bridge;

public interface JSCallback {
    void invoke(Object result);
    void invokeAndKeepAlive(Object result);
}
