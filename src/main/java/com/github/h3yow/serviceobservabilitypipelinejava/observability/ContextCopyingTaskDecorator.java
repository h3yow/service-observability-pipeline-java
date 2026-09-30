package com.github.h3yow.serviceobservabilitypipelinejava.observability;

import org.springframework.core.task.TaskDecorator;

/**
 * 线程池任务装饰器：提交时快照请求 ID（{@link RequestIdContext#wrap(Runnable)}），
 * 执行时绑定到工作线程并写入 MDC，结束还原，确保线程复用不串号、无入口上下文时使用 bg- 兜底。
 */
public class ContextCopyingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return RequestIdContext.wrap(runnable);
    }
}
