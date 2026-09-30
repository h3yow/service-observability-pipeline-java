package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.BusinessRejectedException;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.DownstreamServiceException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 把异常 / HTTP 状态收敛为固定结果类别 {@link Outcome} 与受控原因码 {@code reason}。
 *
 * <p>收敛规则（保证指标维度有界）：
 * <ul>
 *   <li>outcome 固定 6 个取值；</li>
 *   <li>业务拒绝 reason：仅白名单内取值原样保留，其余一律 {@code other}；</li>
 *   <li>其余 outcome 的 reason 由分类器内部固定映射，不使用异常 message / 类名作为维度值；</li>
 *   <li>超时判定覆盖 {@link TimeoutException} 及包装它的 {@link DownstreamServiceException}
 *       和常见 JDK 超时异常的 cause 链。</li>
 * </ul>
 */
public final class ResultClassifier {

    /** 白名单外或无法归类时的统一原因码。 */
    public static final String OTHER = "other";
    public static final String NONE = "none";

    private final Set<String> allowedBusinessReasons;

    public ResultClassifier(Set<String> allowedBusinessReasons) {
        this.allowedBusinessReasons = Set.copyOf(allowedBusinessReasons);
    }

    /** 分类结果：HTTP 状态、结果类别、原因码（已收敛）。 */
    public record Result(int httpStatus, Outcome outcome, String reason) {
    }

    /** 根据异常分类。 */
    public Result classify(Throwable throwable) {
        if (isTimeout(throwable)) {
            return new Result(504, Outcome.TIMEOUT, "downstream_timeout");
        }
        if (throwable instanceof BusinessRejectedException bre) {
            return new Result(422, Outcome.BUSINESS_REJECTED, businessReason(bre.getReason()));
        }
        if (throwable instanceof DownstreamServiceException dse) {
            return new Result(502, Outcome.DOWNSTREAM_ERROR, "downstream_" + sanitize(dse.getService()));
        }
        if (throwable instanceof MethodArgumentNotValidException
                || throwable instanceof HttpMessageNotReadableException
                || throwable instanceof MissingServletRequestParameterException
                || throwable instanceof MethodArgumentTypeMismatchException
                || throwable instanceof IllegalArgumentException) {
            return new Result(400, Outcome.INVALID_ARGUMENT, "invalid_request");
        }
        if (throwable instanceof HttpRequestMethodNotSupportedException) {
            return new Result(405, Outcome.INVALID_ARGUMENT, "method_not_allowed");
        }
        return new Result(500, Outcome.UNKNOWN_ERROR, "internal_error");
    }

    /**
     * 根据 HTTP 状态分类（无异常抛出时使用，如 Filter 链直接返回的 4xx/5xx）。
     * 4xx 统一归 INVALID_ARGUMENT（具体原因按状态固定映射），5xx 归 UNKNOWN_ERROR。
     */
    public Result classifyStatus(int httpStatus) {
        if (httpStatus < 400) {
            return new Result(httpStatus, Outcome.SUCCESS, NONE);
        }
        return switch (httpStatus) {
            case 400 -> new Result(httpStatus, Outcome.INVALID_ARGUMENT, "invalid_request");
            case 404 -> new Result(httpStatus, Outcome.INVALID_ARGUMENT, "not_found");
            case 405 -> new Result(httpStatus, Outcome.INVALID_ARGUMENT, "method_not_allowed");
            case 422 -> new Result(httpStatus, Outcome.BUSINESS_REJECTED, OTHER);
            case 504 -> new Result(httpStatus, Outcome.TIMEOUT, "downstream_timeout");
            case 502, 503 -> new Result(httpStatus, Outcome.DOWNSTREAM_ERROR, "gateway_error");
            default -> httpStatus < 500
                    ? new Result(httpStatus, Outcome.INVALID_ARGUMENT, OTHER)
                    : new Result(httpStatus, Outcome.UNKNOWN_ERROR, "internal_error");
        };
    }

    private String businessReason(String raw) {
        if (raw != null && allowedBusinessReasons.contains(raw)) {
            return raw;
        }
        return OTHER;
    }

    /** 下游名只允许大写字母/数字/下划线，防止把任意字符串带进指标维度。 */
    private String sanitize(String service) {
        if (service == null || service.isBlank()) {
            return OTHER;
        }
        String upper = service.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        return upper.isBlank() ? OTHER : upper;
    }

    private boolean isTimeout(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof TimeoutException
                    || (cur instanceof DownstreamServiceException dse && dse.isTimeout())) {
                return true;
            }
            if (cur == cur.getCause()) {
                break;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
