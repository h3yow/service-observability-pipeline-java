package com.github.h3yow.serviceobservabilitypipelinejava.obs.web;

/**
 * Generic error body. Never carries raw internal exception text.
 */
public record ErrorView(String correlationId, String errorCode, String message) {
}
