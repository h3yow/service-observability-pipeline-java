package com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics;

import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.BusinessRejectedException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.DownstreamClientException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.DownstreamTimeoutException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.InvalidRequestException;

/**
 * Maps throwables and HTTP statuses onto the closed {@link FailureClass} set.
 *
 * <p>Classification is deliberately shallow and stable: it depends only on exception
 * type and HTTP status, never on an exception message (messages can be arbitrary,
 * possibly sensitive, and would explode the label space).
 *
 * <p>Exception precedence (first match wins while walking the cause chain):
 * <ol>
 *   <li>{@link DownstreamTimeoutException} / {@code java.util.concurrent.TimeoutException}
 *       / {@code java.net.SocketTimeoutException} → {@link FailureClass#TIMEOUT}</li>
 *   <li>{@link DownstreamClientException} /
 *       {@code org.springframework.web.client.ResourceAccessException} →
 *       {@link FailureClass#DOWNSTREAM}</li>
 *   <li>{@link InvalidRequestException} / Spring MVC binding exceptions →
 *       {@link FailureClass#VALIDATION}</li>
 *   <li>{@link BusinessRejectedException} → {@link FailureClass#BUSINESS_REJECTED}</li>
 *   <li>anything else → {@link FailureClass#UNCLASSIFIED}</li>
 * </ol>
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    public static FailureClass classify(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof DownstreamTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return FailureClass.TIMEOUT;
            }
            if (t instanceof DownstreamClientException
                    || t instanceof org.springframework.web.client.ResourceAccessException) {
                return FailureClass.DOWNSTREAM;
            }
            if (t instanceof InvalidRequestException
                    || t instanceof org.springframework.web.bind.MethodArgumentNotValidException
                    || t instanceof org.springframework.validation.BindException
                    || t instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
                    || t instanceof org.springframework.web.bind.MissingServletRequestParameterException) {
                return FailureClass.VALIDATION;
            }
            if (t instanceof BusinessRejectedException) {
                return FailureClass.BUSINESS_REJECTED;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return FailureClass.UNCLASSIFIED;
    }

    /**
     * Fallback classification used when no exception escaped but the response status
     * indicates failure (e.g. errors handled inside the container).
     */
    public static FailureClass fromHttpStatus(int httpStatus) {
        if (httpStatus < 400) {
            return FailureClass.SUCCESS;
        }
        return switch (httpStatus) {
            case 400, 422 -> FailureClass.VALIDATION;
            case 401 -> FailureClass.UNAUTHENTICATED;
            case 403, 409 -> FailureClass.BUSINESS_REJECTED;
            case 404 -> FailureClass.NOT_FOUND;
            case 504 -> FailureClass.TIMEOUT;
            case 502, 503 -> FailureClass.DOWNSTREAM;
            default -> FailureClass.UNCLASSIFIED;
        };
    }
}
