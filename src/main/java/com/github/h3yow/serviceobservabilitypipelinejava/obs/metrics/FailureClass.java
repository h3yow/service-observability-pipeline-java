package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

/**
 * Bounded enumeration of failure categories used as a metric dimension.
 */
public enum FailureClass {
    SUCCESS,
    VALIDATION,
    BUSINESS_REJECTED,
    UNAUTHENTICATED,
    NOT_FOUND,
    DOWNSTREAM,
    TIMEOUT,
    UNCLASSIFIED
}
