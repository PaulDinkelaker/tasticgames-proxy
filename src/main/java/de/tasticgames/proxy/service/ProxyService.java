package de.tasticgames.proxy.service;

public interface ProxyService {

    String id();

    void start()
            throws Exception;

    void stop()
            throws Exception;
}
