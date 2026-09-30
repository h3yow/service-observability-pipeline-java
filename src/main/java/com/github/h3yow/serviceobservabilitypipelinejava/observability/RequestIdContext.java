package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 请求 ID 的线程上下文。
 *
 * <p>传播规则：
 * <ul>
 *   <li>HTTP 入口：读取 {@code X-Request-Id}；缺失时生成 {@code gen-} 前缀 ID，
 *       存在但非法时生成 {@code bad-} 前缀 ID，两种兜底互相可区分，也与上游 ID 可区分。</li>
 *   <li>后台任务（无入口请求）触发时：生成 {@code bg-} 前缀 ID。</li>
 *   <li>同步调用：同一线程 ThreadLocal 自然贯穿；异步/线程池：必须经 {@link #wrap} 显式快照传播，
 *       不依赖 InheritableThreadLocal，避免线程复用串号。</li>
 *   <li>ID 同时写入 SLF4J MDC（key={@link #MDC_KEY}），日志模板可直接引用。</li>
 * </ul>
 */
public final class RequestIdContext {

    /** MDC 中的 key，与 logback pattern 对应。 */
    public static final String MDC_KEY = "requestId";

    /** 入口缺失标识时的兜底前缀。 */
    public static final String GEN_PREFIX = "gen-";
    /** 入口标识非法时的兜底前缀。 */
    public static final String BAD_PREFIX = "bad-";
    /** 无入口请求的后台任务前缀。 */
    public static final String BG_PREFIX = "bg-";

    /** 上游 ID 允许的最大长度。 */
    public static final int MAX_HEADER_LENGTH = 64;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private RequestIdContext() {
    }

    /** 入口解析结果：{@code id} 为最终使用的请求 ID，{@code generated} 为 true 表示是兜底生成。 */
    public record Resolved(String id, boolean generated) {
    }

    /** 当前线程绑定的请求 ID，未绑定时为 {@code null}。 */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * 解析入口请求头：
     * <ol>
     *   <li>{@code null} / 空白 → {@code gen-} 兜底（缺失）；</li>
     *   <li>非空白但字符或长度非法 → {@code bad-} 兜底（非法）；</li>
     *   <li>合法 → trim 后原样使用。</li>
     * </ol>
     *
     * @return 解析结果
     */
    public static Resolved resolveInbound(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return new Resolved(generate(GEN_PREFIX), true);
        }
        String trimmed = rawHeader.trim();
        if (trimmed.length() > MAX_HEADER_LENGTH || !VALID.matcher(trimmed).matches()) {
            return new Resolved(generate(BAD_PREFIX), true);
        }
        return new Resolved(trimmed, false);
    }

    /** 为无入口请求的后台任务生成 ID。 */
    public static String generateBackgroundId() {
        return generate(BG_PREFIX);
    }

    private static String generate(String prefix) {
        // 进程内单调递增序列号，确定且无碰撞；拼接短随机片段避免多实例猜测冲突
        long seq = SEQUENCE.incrementAndGet();
        return prefix + seq + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 显式绑定到当前线程（供入口 Filter 使用），返回绑定前的请求 ID。
     * 调用方必须在请求结束时用 {@link #restore(String)} 还原。
     */
    public static String attach(String requestId) {
        String previous = HOLDER.get();
        bind(requestId);
        return previous;
    }

    /** 还原到 {@link #attach} 之前的状态；{@code previous} 为 null 表示清空。 */
    public static void restore(String previous) {
        String previousMdc = previous == null ? null : previous;
        if (previous == null) {
            HOLDER.remove();
            MDC.remove(MDC_KEY);
        } else {
            HOLDER.set(previous);
            MDC.put(MDC_KEY, previousMdc);
        }
    }

    /** 在指定请求 ID 下执行同步任务，结束后恢复原上下文（可重入、可嵌套）。 */
    public static void runWith(String requestId, Runnable task) {
        String previous = HOLDER.get();
        String previousMdc = MDC.get(MDC_KEY);
        try {
            bind(requestId);
            task.run();
        } finally {
            restore(previous, previousMdc);
        }
    }

    /** 在指定请求 ID 下执行有返回值任务，结束后恢复原上下文。 */
    public static <T> T callWith(String requestId, Callable<T> task) throws Exception {
        String previous = HOLDER.get();
        String previousMdc = MDC.get(MDC_KEY);
        try {
            bind(requestId);
            return task.call();
        } finally {
            restore(previous, previousMdc);
        }
    }

    /**
     * 包装 Runnable：在<b>提交线程</b>快照当前请求 ID，在<b>执行线程</b>绑定后运行，
     * 运行结束恢复执行线程原上下文，杜绝线程池复用导致的串号。
     * 若提交时不存在上下文（纯后台提交），使用 {@code bg-} 兜底。
     */
    public static Runnable wrap(Runnable task) {
        String captured = HOLDER.get();
        String effective = captured != null ? captured : generateBackgroundId();
        return () -> runWith(effective, task);
    }

    /** 同 {@link #wrap(Runnable)}，用于 Callable。 */
    public static <T> Callable<T> wrap(Callable<T> task) {
        String captured = HOLDER.get();
        String effective = captured != null ? captured : generateBackgroundId();
        return () -> callWith(effective, task);
    }

    private static void bind(String requestId) {
        HOLDER.set(requestId);
        MDC.put(MDC_KEY, requestId);
    }

    private static void restore(String previous, String previousMdc) {
        if (previous == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(previous);
        }
        if (previousMdc == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previousMdc);
        }
    }
}
