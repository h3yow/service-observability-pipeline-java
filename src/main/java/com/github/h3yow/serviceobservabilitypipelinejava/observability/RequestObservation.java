package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单次请求的观测记录。
 *
 * <p>生命周期：Filter 入口 {@link #start} → 业务执行 → Filter/异常处理
 * 调用 {@link #end} / {@link #endWith}。负责：
 * <ol>
 *   <li>采样判定（进入时一次性决定，结果贯穿整次请求）；</li>
 *   <li>耗时统计与按 endpoint × outcome × reason 记录指标（<b>无论是否采样都记录</b>）；</li>
 *   <li>采样命中时输出一条结构化访问日志；异常堆栈仅在服务端日志里记录，不回传调用方；</li>
 *   <li>在途计数 +/-，便于观测并发。</li>
 * </ol>
 *
 * <p>endpoint 在请求结束时才确定（路由匹配后写入），Filter 执行前为 UNKNOWN。
 */
public class RequestObservation {

    private static final Logger log = LoggerFactory.getLogger(RequestObservation.class);

    public static final String UNKNOWN_ENDPOINT = "unknown";

    private final String requestId;
    private final String method;
    private final String rawPath;
    private final boolean generated;
    private final long startNanos;
    private final Sampler sampler;
    private boolean sampled;

    private volatile String endpoint = UNKNOWN_ENDPOINT;
    private volatile ResultClassifier.Result result;
    private volatile Throwable thrown;
    /** 全局异常处理器基于异常给出的分类；优先级高于按状态码反推。 */
    private volatile ResultClassifier.Result preClassified;
    private volatile boolean finished;

    private RequestObservation(String requestId, boolean generated, String method, String rawPath,
                               Sampler sampler) {
        this.requestId = requestId;
        this.generated = generated;
        this.method = method;
        this.rawPath = rawPath;
        this.sampler = sampler;
        this.startNanos = System.nanoTime();
    }

    /** 请求进入：仅增加在途计数；采样判定推迟到收尾，与总计数同一刻递增，保证计数恒等式成立。 */
    public static RequestObservation start(RequestMetrics metrics, Sampler sampler,
                                          String requestId, boolean generated,
                                          String method, String rawPath) {
        // 在途先按 unknown 记，endpoint 匹配后再迁移，保证进入即被观察
        metrics.activeChange(UNKNOWN_ENDPOINT, +1);
        return new RequestObservation(requestId, generated, method, rawPath, sampler);
    }

    /** 路由匹配后设置收敛后的接口名（如 {@code POST /orders/{id}}），并迁移在途计数。 */
    public void setEndpoint(RequestMetrics metrics, String matchedEndpoint) {
        if (matchedEndpoint != null && !matchedEndpoint.isBlank()
                && !matchedEndpoint.equals(this.endpoint)) {
            metrics.activeChange(this.endpoint, -1);
            this.endpoint = matchedEndpoint;
            metrics.activeChange(this.endpoint, +1);
        }
    }

    /**
     * 全局异常处理器把异常消化成响应体时调用：把基于异常的精确分类（含 reason）
     * 预先标记到本次观测，Filter 收尾时优先采用，避免只按 HTTP 状态反推而丢失原因维度。
     */
    public void markClassified(ResultClassifier.Result classified, Throwable t) {
        this.preClassified = classified;
        if (t != null) {
            this.thrown = t;
        }
    }

    /** 正常结束（无异常），按实际 HTTP 状态归类。 */
    public void end(RequestMetrics metrics, ResultClassifier classifier, int httpStatus) {
        finish(metrics, classifier.classifyStatus(httpStatus), null);
    }

    /** 异常结束，由分类器收敛结果。 */
    public void endWith(RequestMetrics metrics, ResultClassifier classifier, Throwable t) {
        this.thrown = t;
        finish(metrics, classifier.classify(t), t);
    }

    private synchronized void finish(RequestMetrics metrics, ResultClassifier.Result r, Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        // 异常处理器已基于真实异常分类时优先使用（状态码一致，reason 更精确）
        ResultClassifier.Result effective = preClassified != null ? preClassified : r;
        this.result = effective;
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // 采样判定与总计数在同一处递增：任意快照都满足 sampledIn + sampledOut == totalRequests
        this.sampled = sampler.tryAcquire();
        try {
            metrics.markSampled(sampled);
            metrics.record(endpoint, effective.outcome(), effective.reason(), durationMs);
        } finally {
            metrics.activeChange(endpoint, -1);
        }
        writeLog(durationMs, effective, this.thrown != null ? this.thrown : t);
    }

    private void writeLog(long durationMs, ResultClassifier.Result r, Throwable t) {
        if (!sampled) {
            // 被采样丢弃：不打印每请求日志（计数已在指标中全量保留）
            return;
        }
        if (r.outcome() == Outcome.SUCCESS) {
            log.info("access requestId={} generated={} method={} path={} endpoint={} "
                            + "status={} outcome={} reason={} durationMs={} sampled={}",
                    requestId, generated, method, rawPath, endpoint, r.httpStatus(),
                    r.outcome(), r.reason(), durationMs, true);
        } else {
            // 只记录收敛后的 outcome/reason 与服务端异常堆栈；对外错误体不含底层细节
            log.warn("access requestId={} generated={} method={} path={} endpoint={} "
                            + "status={} outcome={} reason={} durationMs={} sampled={} errorType={}",
                    requestId, generated, method, rawPath, endpoint, r.httpStatus(),
                    r.outcome(), r.reason(), durationMs, true,
                    t == null ? r.reason() : t.getClass().getSimpleName(), t);
        }
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isGenerated() {
        return generated;
    }
}
