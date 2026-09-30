package com.github.h3yow.serviceobservabilitypipelinejava.demo.web;

/**
 * Simulated downstream dependency failure (skeleton).
 */
public class DownstreamClientException extends RuntimeException {

    public DownstreamClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
