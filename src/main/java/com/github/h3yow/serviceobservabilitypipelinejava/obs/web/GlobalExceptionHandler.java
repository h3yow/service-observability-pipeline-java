package com.github.h3yow.serviceobservabilitypipelinejava.obs.web;

import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.BusinessRejectedException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.DownstreamClientException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.DownstreamTimeoutException;
import com.github.h3yow.serviceobservabilitypipelinejava.demo.web.InvalidRequestException;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.correlation.CorrelationContext;
import com.github.h3yow.serviceobservabilitypipelinejava.obs.metrics.FailureClass;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single place that converts exceptions into stable, bounded error responses.
 *
 * <p>Principles:
 * <ul>
 *   <li>The response body carries only: correlation id, a closed error code and a
 *       fixed generic message. Exception messages/class names/stack traces — which may
 *       contain hostnames, SQL or user data — are logged server-side only and never
 *       serialized to the caller.</li>
 *   <li>The resolved {@link FailureClass} is published as a request attribute so the
 *       metrics filter records the same category the client actually saw.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorView> handleValidation(HttpServletRequest request,
                                                      InvalidRequestException ex) {
        return respond(request, FailureClass.VALIDATION, HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR", "请求参数不合法", ex, false);
    }

    @ExceptionHandler(BusinessRejectedException.class)
    public ResponseEntity<ErrorView> handleBusiness(HttpServletRequest request,
                                                    BusinessRejectedException ex) {
        return respond(request, FailureClass.BUSINESS_REJECTED, HttpStatus.UNPROCESSABLE_ENTITY,
                "BUSINESS_REJECTED", "请求被业务规则拒绝", ex, false);
    }

    @ExceptionHandler(DownstreamTimeoutException.class)
    public ResponseEntity<ErrorView> handleTimeout(HttpServletRequest request,
                                                   DownstreamTimeoutException ex) {
        return respond(request, FailureClass.TIMEOUT, HttpStatus.GATEWAY_TIMEOUT,
                "DOWNSTREAM_TIMEOUT", "下游处理超时", ex, true);
    }

    @ExceptionHandler(DownstreamClientException.class)
    public ResponseEntity<ErrorView> handleDownstream(HttpServletRequest request,
                                                      DownstreamClientException ex) {
        return respond(request, FailureClass.DOWNSTREAM, HttpStatus.BAD_GATEWAY,
                "DOWNSTREAM_ERROR", "下游服务异常", ex, true);
    }

    /** Unmapped paths: classified as NOT_FOUND rather than a generic 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorView> handleNotFound(HttpServletRequest request,
                                                    NoResourceFoundException ex) {
        return respond(request, FailureClass.NOT_FOUND, HttpStatus.NOT_FOUND,
                "NOT_FOUND", "资源不存在", ex, false);
    }

    /** Catch-all: unknown failures become a generic 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> handleUnexpected(HttpServletRequest request, Exception ex) {
        return respond(request, FailureClass.UNCLASSIFIED, HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "服务内部错误", ex, true);
    }

    private ResponseEntity<ErrorView> respond(HttpServletRequest request, FailureClass result,
                                              HttpStatus status, String errorCode,
                                              String clientMessage, Exception ex,
                                              boolean stackTrace) {
        request.setAttribute(RequestMetricsFilter.FAILURE_CLASS_ATTRIBUTE, result);
        String correlationId = CorrelationContext.current();
        if (stackTrace) {
            log.error("request failed class={} code={} status={}", result, errorCode,
                    status.value(), ex);
        } else {
            log.warn("request rejected class={} code={} status={} detail={}", result, errorCode,
                    status.value(), ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(new ErrorView(correlationId, errorCode, clientMessage));
    }
}
