package com.github.h3yow.serviceobservabilitypipelinejava.observability;

/** 对外统一错误体，只暴露收敛后的错误码，不回传底层异常细节。 */
public record ErrorResponse(String requestId, String code, String message) {
}
