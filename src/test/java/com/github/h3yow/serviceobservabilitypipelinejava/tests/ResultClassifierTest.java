package com.github.h3yow.serviceobservabilitypipelinejava.tests;

import com.github.h3yow.serviceobservabilitypipelinejava.observability.Outcome;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.ResultClassifier;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.BusinessRejectedException;
import com.github.h3yow.serviceobservabilitypipelinejava.observability.exception.DownstreamServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 失败原因归类测试：覆盖 4 类失败 + 成功 + reason 白名单收敛。
 * 每条用例打印输入异常与判定依据。
 */
class ResultClassifierTest {

    private static final Logger log = LoggerFactory.getLogger(ResultClassifierTest.class);

    private final ResultClassifier classifier =
            new ResultClassifier(Set.of("STOCK_LIMIT", "RISK_BLOCK"));

    @Test
    @DisplayName("异常按原因分类")
    void classifyExceptions() {
        record Case(String name, Throwable t, Outcome outcome, String reason, int httpStatus) {
        }
        var missingParam = new MissingServletRequestParameterException("itemId", "String");
        List<Case> cases = List.of(
                new Case("参数缺失", missingParam, Outcome.INVALID_ARGUMENT, "invalid_request", 400),
                new Case("白名单业务拒绝",
                        new BusinessRejectedException("STOCK_LIMIT", "no stock"),
                        Outcome.BUSINESS_REJECTED, "STOCK_LIMIT", 422),
                new Case("白名单外业务原因收敛为 other",
                        new BusinessRejectedException("WEIRD_REASON_99", "x"),
                        Outcome.BUSINESS_REJECTED, "other", 422),
                new Case("下游异常",
                        new DownstreamServiceException("inventory", "fail",
                                new RuntimeException("socket reset 10.0.0.9")),
                        Outcome.DOWNSTREAM_ERROR, "downstream_INVENTORY", 502),
                new Case("直接超时", new TimeoutException("slow"),
                        Outcome.TIMEOUT, "downstream_timeout", 504),
                new Case("包装的下游超时",
                        DownstreamServiceException.timeout("inventory", 200,
                                new TimeoutException("read timed out")),
                        Outcome.TIMEOUT, "downstream_timeout", 504),
                new Case("cause 链中的超时",
                        new RuntimeException(new TimeoutException("deep")),
                        Outcome.TIMEOUT, "downstream_timeout", 504),
                new Case("未知异常", new IllegalStateException("boom"),
                        Outcome.UNKNOWN_ERROR, "internal_error", 500)
        );
        for (Case c : cases) {
            ResultClassifier.Result r = classifier.classify(c.t());
            log.info("输入[{}] exception={} => 判定 outcome={} reason={} http={}；期望 outcome={} reason={} http={}",
                    c.name(), c.t().getClass().getSimpleName(), r.outcome(), r.reason(), r.httpStatus(),
                    c.outcome(), c.reason(), c.httpStatus());
            assertEquals(c.outcome(), r.outcome(), c.name());
            assertEquals(c.reason(), r.reason(), c.name());
            assertEquals(c.httpStatus(), r.httpStatus(), c.name());
        }
    }

    @Test
    @DisplayName("HTTP 状态映射：成功与常见失败")
    void classifyStatuses() {
        record Case(int status, Outcome outcome, String reason) {
        }
        List<Case> cases = List.of(
                new Case(200, Outcome.SUCCESS, "none"),
                new Case(201, Outcome.SUCCESS, "none"),
                new Case(400, Outcome.INVALID_ARGUMENT, "invalid_request"),
                new Case(404, Outcome.INVALID_ARGUMENT, "not_found"),
                new Case(405, Outcome.INVALID_ARGUMENT, "method_not_allowed"),
                new Case(422, Outcome.BUSINESS_REJECTED, "other"),
                new Case(504, Outcome.TIMEOUT, "downstream_timeout"),
                new Case(502, Outcome.DOWNSTREAM_ERROR, "gateway_error"),
                new Case(500, Outcome.UNKNOWN_ERROR, "internal_error")
        );
        for (Case c : cases) {
            ResultClassifier.Result r = classifier.classifyStatus(c.status());
            log.info("输入 httpStatus={} => 判定 outcome={} reason={}；期望 outcome={} reason={}",
                    c.status(), r.outcome(), r.reason(), c.outcome(), c.reason());
            assertEquals(c.outcome(), r.outcome());
            assertEquals(c.reason(), r.reason());
        }
    }

    @Test
    @DisplayName("下游名中非法字符被净化，不能作为任意维度值进入指标")
    void sanitizeServiceName() {
        DownstreamServiceException ex = new DownstreamServiceException("inv#/../x", "m", null);
        ResultClassifier.Result r = classifier.classify(ex);
        log.info("输入下游名 'inv#/../x' => 判定 reason={}（只能含大写字母数字下划线）", r.reason());
        assertEquals(Outcome.DOWNSTREAM_ERROR, r.outcome());
        assertEquals("downstream_INV_____X", r.reason());
    }
}
