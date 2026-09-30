package com.github.h3yow.serviceobservabilitypipelinejava.observability.exception;

/**
 * 业务拒绝类异常基类。
 *
 * <p>{@code reason} 必须来自配置的业务原因白名单（如 STOCK_LIMIT、RISK_BLOCK），
 * 白名单外的取值会在分类阶段统一收敛为 {@code other}，保证指标维度不爆炸。
 */
public class BusinessRejectedException extends RuntimeException {

    private final String reason;

    public BusinessRejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
