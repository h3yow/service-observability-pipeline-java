package com.github.h3yow.serviceobservabilitypipelinejava.observability;

/** 采样器：决定单次请求是否输出详细日志（骨架）。 */
public interface Sampler {
    boolean tryAcquire();
}
