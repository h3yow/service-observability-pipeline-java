package com.github.h3yow.serviceobservabilitypipelinejava.demo.web;

/**
 * Simulated downstream timeout (skeleton).
 */
public class DownstreamTimeoutException extends RuntimeException {

    public DownstreamTimeoutException(String message) {
        super(message);
    }
}
