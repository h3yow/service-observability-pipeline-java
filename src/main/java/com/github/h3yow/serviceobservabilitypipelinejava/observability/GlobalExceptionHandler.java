package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.BusinessRejectedException;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.DownstreamServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理。
 *
 * <p>对外只返回收敛后的 {@code code}（INVALID_ARGUMENT / BUSINESS_REJECTED:&lt;reason&gt; /
 * DOWNSTREAM_ERROR / TIMEOUT / INTERNAL_ERROR）与固定文案，<b>不回传底层异常类名、message、堆栈</b>；
 * 底层细节只出现在服务端日志。请求结果归类由 Filter 在收尾时按响应状态统一完成，指标只有一处落点。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ResultClassifier classifier;

    public GlobalExceptionHandler(ResultClassifier classifier) {
        this.classifier = classifier;
    }

    @ExceptionHandler(BusinessRejectedException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessRejectedException ex,
                                                        HttpServletRequest request) {
        ResultClassifier.Result r = classifier.classify(ex);
        return respond(request, r, safeMessage(ex), ex);
    }

    @ExceptionHandler(DownstreamServiceException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamServiceException ex,
                                                          HttpServletRequest request) {
        ResultClassifier.Result r = classifier.classify(ex);
        log.warn("downstream failure requestId={} service={} timeout={}", RequestIdContext.get(),
                ex.getService(), ex.isTimeout(), ex);
        // 不向调用方透出下游服务名与 cause；超时与普通下游失败用不同固定文案区分
        String message = r.outcome() == Outcome.TIMEOUT
                ? "request timed out" : "downstream service unavailable";
        return respond(request, r, message, ex);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalid(Exception ex, HttpServletRequest request) {
        ResultClassifier.Result r = classifier.classify(ex);
        return respond(request, r, "request validation failed", ex);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex,
                                                      HttpServletRequest request) {
        ResultClassifier.Result r = classifier.classify(ex);
        return respond(request, r, "method not allowed", ex);
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(TimeoutException ex, HttpServletRequest request) {
        ResultClassifier.Result r = classifier.classify(ex);
        return respond(request, r, "request timed out", ex);
    }

    /** 兜底：所有未预期异常。 */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleAny(Throwable ex, HttpServletRequest request) {
        ResultClassifier.Result r = classifier.classify(ex);
        log.error("unhandled exception requestId={} outcome={} reason={}",
                RequestIdContext.get(), r.outcome(), r.reason(), ex);
        return respond(request, r, "internal server error", ex);
    }

    private ResponseEntity<ErrorResponse> respond(HttpServletRequest request,
                                                  ResultClassifier.Result r, String message,
                                                  Throwable cause) {
        Object attr = request.getAttribute(RequestContextFilter.OBS_ATTR);
        if (attr instanceof RequestObservation obs) {
            // 把基于真实异常的精确分类交给 Filter 收尾使用
            obs.markClassified(r, cause);
        }
        String code = r.outcome() == Outcome.BUSINESS_REJECTED
                ? r.outcome().name() + ":" + r.reason()
                : r.outcome().name();
        ErrorResponse body = new ErrorResponse(RequestIdContext.get(), code, message);
        return ResponseEntity.status(HttpStatus.valueOf(r.httpStatus())).body(body);
    }

    private String safeMessage(BusinessRejectedException ex) {
        // 业务拒绝 message 属于业务文案，允许透出；底层技术异常信息不会进入这里
        return ex.getMessage() == null ? "business rejected" : ex.getMessage();
    }
}
