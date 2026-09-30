package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 入口 Filter，负责请求 ID 与观测生命周期。
 *
 * <ol>
 *   <li>解析 {@code X-Request-Id}：缺失 {@code gen-}、非法 {@code bad-}，并回写响应头；</li>
 *   <li>绑定 {@link RequestIdContext} 与 MDC，finally 中还原，线程池化下也不残留；</li>
 *   <li>创建 {@link RequestObservation}，异常时统一归类，正常时按响应状态归类；</li>
 *   <li>endpoint 使用 Controller 的路由模板（非原始 URI），避免把路径变量带进指标维度；</li>
 *   <li>对 Servlet 异步请求挂 {@link AsyncListener}，在异步真正完成/超时/出错时收尾。</li>
 * </ol>
 */
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String OBS_ATTR = RequestContextFilter.class.getName() + ".obs";

    private final ObservationProperties properties;
    private final RequestMetrics metrics;
    private final ResultClassifier classifier;
    private final Sampler sampler;

    public RequestContextFilter(ObservationProperties properties, RequestMetrics metrics,
                                ResultClassifier classifier, Sampler sampler) {
        this.properties = properties;
        this.metrics = metrics;
        this.classifier = classifier;
        this.sampler = sampler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestIdContext.Resolved resolved =
                RequestIdContext.resolveInbound(request.getHeader(properties.getHeaderName()));
        String requestId = resolved.id();
        String previousId = RequestIdContext.attach(requestId);

        response.setHeader(properties.getHeaderName(), requestId);

        RequestObservation obs = RequestObservation.start(
                metrics, sampler, requestId, resolved.generated(),
                request.getMethod(), request.getRequestURI());
        request.setAttribute(OBS_ATTR, obs);

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            resolveEndpoint(request, obs);
            if (request.isAsyncStarted()) {
                // 异步：入口线程先释放上下文，由监听器在完成时收尾
                request.getAsyncContext().addListener(
                        new ObservationAsyncListener(obs, requestId));
                RequestIdContext.restore(previousId);
            } else {
                try {
                    if (failure != null) {
                        obs.endWith(metrics, classifier, failure);
                    } else {
                        obs.end(metrics, classifier, response.getStatus());
                    }
                } finally {
                    RequestIdContext.restore(previousId);
                }
            }
        }
    }

    private void resolveEndpoint(HttpServletRequest request, RequestObservation obs) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isBlank()) {
            // 路由模板而非原始路径：/orders/123 与 /orders/456 收敛为同一条序列
            obs.setEndpoint(metrics, request.getMethod() + " " + s);
        }
        // 未匹配处理器时保持启动时的 unknown，不使用原始 URI
    }

    /** 异步完成监听器：在异步线程恢复上下文后完成观测。 */
    private final class ObservationAsyncListener implements AsyncListener {
        private final RequestObservation obs;
        private final String requestId;

        ObservationAsyncListener(RequestObservation obs, String requestId) {
            this.obs = obs;
            this.requestId = requestId;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            RequestIdContext.runWith(requestId, () -> {
                Throwable t = event.getThrowable();
                if (t != null) {
                    obs.endWith(metrics, classifier, t);
                } else {
                    obs.end(metrics, classifier,
                            ((HttpServletResponse) event.getSuppliedResponse()).getStatus());
                }
            });
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            RequestIdContext.runWith(requestId,
                    () -> obs.endWith(metrics, classifier,
                            new TimeoutException("async request timeout")));
        }

        @Override
        public void onError(AsyncEvent event) {
            Throwable t = event.getThrowable();
            if (t != null) {
                RequestIdContext.runWith(requestId,
                        () -> obs.endWith(metrics, classifier, t));
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
        }
    }
}
