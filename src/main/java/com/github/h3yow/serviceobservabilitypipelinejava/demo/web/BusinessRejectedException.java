package com.github.h3yow.serviceobservabilitypipelinejava.demo.web;

/**
 * A rule-based rejection of an otherwise syntactically valid request
 * (e.g. insufficient balance / quota). Counted as {@code BUSINESS_REJECTED}.
 */
public class BusinessRejectedException extends RuntimeException {

    public BusinessRejectedException(String message) {
        super(message);
    }
}
