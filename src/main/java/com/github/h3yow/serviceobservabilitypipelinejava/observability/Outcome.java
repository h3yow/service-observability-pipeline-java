package com.github.h3yow.serviceobservabilitypipelinejava.observability;

/** 请求结果归类，取值固定收敛，共 6 类。 */
public enum Outcome {
    SUCCESS,
    INVALID_ARGUMENT,
    BUSINESS_REJECTED,
    DOWNSTREAM_ERROR,
    TIMEOUT,
    UNKNOWN_ERROR
}
