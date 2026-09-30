package com.github.h3yow.serviceobservabilitypipelinejava.observability.exception;

/**
 * 下游依赖异常基类。
 *
 * <p>用于包装对下游（HTTP/RPC/DB 等）调用失败，{@code service} 为下游逻辑名（固定枚举式取值），
 * 超时场景使用 {@link #timeout} 工厂构造。底层 cause 仅记录日志，不回传调用方。
 */
public class DownstreamServiceException extends RuntimeException {

    private final String service;
    private final boolean timeout;

    public DownstreamServiceException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
        this.timeout = false;
    }

    private DownstreamServiceException(String service, String message, Throwable cause, boolean timeout) {
        super(message, cause);
        this.service = service;
        this.timeout = timeout;
    }

    public static DownstreamServiceException timeout(String service, long timeoutMillis, Throwable cause) {
        return new DownstreamServiceException(service,
                "downstream call timeout after " + timeoutMillis + "ms", cause, true);
    }

    public String getService() {
        return service;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
