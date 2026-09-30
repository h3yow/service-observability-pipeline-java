# 请求级可观测性能力说明

本文档说明本服务的请求关联标识（correlation id）传播规则、指标维度的收敛与采样策略，以及本地复现与验证方式。代码位于 `obs/`（能力层）与 `demo/`（演示链路）两个包。

---

## 1. 关联标识（Correlation ID）

### 1.1 标识从哪来：入口的确定规则

每个 HTTP 请求在 `CorrelationFilter`（过滤器顺序最先）里拿到且仅拿到一个标识，规则确定、可区分：

1. 请求头 `X-Correlation-Id` **合法**时原样沿用；
2. 缺失、为空、包含空白/控制字符、字符集不合法（只允许 `[A-Za-z0-9._:-]`）或长度超过配置上限（默认 64）时，**丢弃入站值并生成兜底标识**，同时打一条 info 日志说明是“缺失”还是“被拒绝”（拒绝时不回显原始脏值）。

兜底标识形如 `auto-<nanoTime 的 36 进制>-<进程内单调递增序号的 36 进制>`，例如 `auto-4yqfog0f10-1`：

- `auto-` 前缀让“本服务生成”与“上游传入”在日志里一眼可分；
- 时间分量 + 进程全局 `AtomicLong` 序号保证同一纳秒内并发进入的多个请求也不重复。

标识会通过响应头 `X-Correlation-Id` 回传给调用方。

### 1.2 同步链路

标识绑定在请求线程的 `ThreadLocal` 与 SLF4J `MDC` 上（`CorrelationContext`），日志 pattern 用 `%X{correlationId:-}` 让每一行日志自动带上它：

```
%d ... %5p [%X{correlationId:-}] %logger : %m%n
```

请求结束在 `finally` 中清理，线程归还容器后不会残留上一个请求的标识。

### 1.3 异步处理（线程池）

`obsAsyncExecutor` 配置了 `CorrelationTaskDecorator`：

- 装饰发生在**任务提交时刻（调用方线程）**，在那里快照标识；
- 工作线程执行任务前重新绑定、任务结束后恢复线程原有的绑定（通常为空），因此池化线程复用也不会把 A 请求的标识串到 B 请求；
- 提交时没有上下文（例如自触发任务）时，装饰器生成一个 `auto-` 兜底，后台工作永不“匿名”。

线程池本身有界（core 4 / max 16 / queue 200），拒绝策略为 `CallerRunsPolicy`，过载时退化为调用方执行，保证资源不膨胀。

### 1.4 请求触发的后台任务

`POST /demo/jobs` 把触发请求的标识显式传给后台作业，作业体用 `CorrelationContext.runWith(triggerId, ...)` 包裹，作业内日志与该请求同标识，作业结束恢复现场。

### 1.5 自触发的定时任务

没有请求上下文，定时心跳 `AsyncJobs#scheduledHeartbeat` 用 `CorrelationContext.runWithGenerated(...)` 自生成 `auto-` 标识（演示里 60s 一次），同样全程可追踪。

### 1.6 并发不串号

标识保存在线程私有存储中，跨线程只通过“提交时快照值”显式传递，从不使用任何共享的可变“当前请求”。并发安全由 `ThreadLocal`、任务装饰器的恢复语义、以及兜底 ID 的原子序号共同保证（见并发测试）。

---

## 2. 指标：维度、收敛与失败归类

每个请求在 `RequestMetricsFilter` 里**恰好记录一条**指标，key 为 `(endpoint, resultClass)`。

### 2.1 结果维度：有界的失败归类

`FailureClass` 是一个封闭枚举，只有 8 个取值：

| 类别 | 含义 | 典型触发 |
| --- | --- | --- |
| `SUCCESS` | 成功 | 2xx/3xx |
| `VALIDATION` | 参数问题 | 400/422、`InvalidRequestException`、Spring 绑定异常 |
| `BUSINESS_REJECTED` | 业务拒绝 | 403/409、`BusinessRejectedException`（422 演示） |
| `UNAUTHENTICATED` | 未认证 | 401 |
| `NOT_FOUND` | 资源不存在 | 404、`NoResourceFoundException` |
| `DOWNSTREAM` | 下游异常 | 502/503、`DownstreamClientException`、`ResourceAccessException` |
| `TIMEOUT` | 超时 | 504、`TimeoutException`、`SocketTimeoutException` |
| `UNCLASSIFIED` | 未归类 | 其他异常 / 其他状态码 |

归类只依据**异常类型与 HTTP 状态码，不依据异常 message**（message 不可信、可能敏感、且会造成取值爆炸）。会沿异常 cause 链查找，因此被包装的 `TimeoutException` 仍判为 `TIMEOUT`。异常处理器（`GlobalExceptionHandler`）把判定结果写入请求属性，指标过滤器与返回给调用方的错误码使用同一结论。

### 2.2 接口维度：模板化 + 收敛

绝不使用原始 URI 作为标签（路径/查询串可携带任意用户输入，会让标签无限增长）。`DimensionSanitizer` 的规则：

1. 命中 Spring MVC 处理器时使用其模板，如 `/demo/orders/{id}`——所有订单 ID 折叠到同一标签；
2. 没有真实模板（未匹配路径、静态资源）时，所有请求折叠到单一标签 `UNMATCHED`（资源处理器的 `/**` 通配模式也视为无模板）；
3. URI/模板都拿不到时用 `UNKNOWN`；
4. 标签长度硬上限（默认 64），超长截断并加 `~` 哨兵，仍是同一个标签。

### 2.3 指标基数的双重上限

- 构造上：标签数 ≤ （模板数 + 2）× 8（封闭枚举）；
- 防御性硬上限：`observability.metrics-max-endpoints`（默认 128）。一旦不同端点数超过上限，新端点统一折叠进 `__OTHER__` 桶，被折叠的请求数由 `overflowFoldedRequests` 单独计数并打 warn 日志。内存占用因此有确定上界。

### 2.4 底层异常不透出

`GlobalExceptionHandler` 对调用方只返回三字段：`correlationId`、封闭错误码（如 `DOWNSTREAM_ERROR`）、固定中文提示。异常类型、message、堆栈、主机名等只在服务端日志记录，绝不序列化给调用方（集成测试断言响应体不含模拟的内网 IP 与 `IOException` 字样）。

---

## 3. 采样策略：计数始终准确、结论可对账

### 3.1 计数与采样分离

`InMemoryMetricsRegistry` 对每个请求：

- **总是** 递增该桶的 `totalCount`；
- 另外根据采样器判定，递增 `sampledCount`（并记录耗时的 sum/min/max）或 `droppedCount`。

因此恒成立：

```
totalCount == sampledCount + droppedCount
```

被采样丢弃的请求**一个不少地仍被计数**，只是不保留其耗时明细。`/internal/metrics` 会直接输出 `countInvariantHolds` 布尔值。

### 3.2 确定性采样

`DeterministicSampler` 对关联标识做 FNV-1a 哈希：

```
保留  ⇔  bucket(id) ∈ [0, 10000) 且 bucket < round(rate × 10000)
```

- **确定性**：同一标识在同一速率下结论恒定，重试、跨组件上报结论一致；
- **单调**：提高采样率只会“增加”被保留的标识集合（集合是低速率集合的超集），降低只会移除一部分。调整前后的两段数据在“计数”维度始终可对账，在“耗时明细”维度可解释地增减；
- **低成本**：每次采样只有一次短字符串哈希 + 一次 `volatile` 读，无随机数、无共享计数器、无锁。

速率可静态配置（`observability.metrics-sampling-rate`），也可运行时通过 `POST /internal/sampler?rate=0.3` 调整（取值 `[0,1]`，非法值返回 400）。改速率不清空已有聚合，所以历史总量不受影响。

### 3.3 高并发下的开销

热路径全部使用 `LongAdder`（分段计数，高并发下基本无竞争）；新 key 创建极少，且由一把短锁 + 上限保护。读取（snapshot）不在请求热路径上。指标记录自身被 try/catch 保护，采集失败绝不影响业务请求。

### 3.4 指标接口不观测自己

`/internal/**` 在 `shouldNotFilter` 中被排除，抓取指标不会污染指标（有测试断言读取前后总数不变）。

`GET /internal/metrics` 返回：当前采样率、全局合计（含不变量布尔值）、因基数上限被折叠的请求数、以及每个桶的计数与耗时统计。

---

## 4. 配置项（`application.properties`，前缀 `observability`）

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `correlation-id-max-length` | 64 | 入站 `X-Correlation-Id` 允许的最大长度 |
| `endpoint-max-length` | 64 | 端点标签长度硬上限 |
| `metrics-sampling-rate` | 1.0 | 保留耗时明细的概率 `[0.0,1.0]`；不影响计数 |
| `metrics-max-endpoints` | 128 | 端点数硬上限，超出折叠进 `__OTHER__` |
| `internal-path-prefix` | `/internal/` | 不采集指标的内部路径前缀 |

---

## 5. 演示端点

| 方法/路径 | 说明 | 结果类别 |
| --- | --- | --- |
| `GET /demo/orders/{id}` | 成功，路径变量被模板化 | SUCCESS |
| `GET /demo/orders?id=...` | `id` 非 1..18 位数字即参数错误 | VALIDATION |
| `POST /demo/orders/reject` | 业务规则拒绝 | BUSINESS_REJECTED |
| `GET /demo/downstream` | 模拟下游连接异常（cause 链） | DOWNSTREAM |
| `GET /demo/timeout` | 模拟下游超时 | TIMEOUT |
| `GET /demo/async` | 异步线程上回读关联标识 | SUCCESS |
| `POST /demo/jobs` | 触发后台作业并校验标识传播 | SUCCESS |
| `GET /internal/metrics` | 指标读数（不计费指标） | — |
| `POST /internal/sampler?rate=x` | 动态调整采样率 | — |

---

## 6. 本地复现与验证

### 6.1 跑测试

```bash
mvn test
```

共 19 个用例，覆盖：

- `CorrelationIdsTest`：16 线程并发生成 8 万个兜底 ID 全局唯一、`auto-` 前缀可区分；入站值字符集/长度校验；
- `CorrelationContextTest`：32 线程 × 2000 次绑定，断言 ThreadLocal/MDC 不串号、用完即恢复；任务装饰器“提交线程快照、工作线程恢复、无线程泄漏”，无上下文时生成兜底；
- `SamplingAndRegistryTest`：采样确定性、提升速率集合只增不减、`total == kept + dropped`、端点基数硬上限折叠；
- `ClassificationAndDimensionsTest`：各异常（含 cause 链）与状态码归类、端点模板化/`UNMATCHED`/长度收敛；
- `ObservabilityIntegrationTest`：32 并发 × 25 请求端到端标识不串号且每个请求计数一次；缺/脏标识回退；四类失败链路状态码+错误码+指标桶一一对应；响应体不含内部细节；异步与后台作业传播；采样下计数精确；未知路径全部折叠到 `UNMATCHED/NOT_FOUND`；
- `SamplerControlIntegrationTest`：运行时把速率从 1.0 调到 0.0，跨速率两段数据计数可对账；非法速率 400；读指标不产生新指标。

> 单测/集成测试的日志都会打印“输入是什么、判定依据是什么”（形如 `input: ... judgement: ...`）。

### 6.2 起服务手工验证

```bash
mvn package -DskipTests
java -jar target/service-observability-pipeline-java-1.0-SNAPSHOT.jar --server.port=29090
```

另开终端：

```bash
B=http://127.0.0.1:29090

# 1) 传入标识，响应头与响应体回显同一值
curl -s -D - -H 'X-Correlation-Id: trace-abc-123' $B/demo/orders/13900037460

# 2) 不带 / 带脏标识 -> 得到 auto- 兜底
curl -s -D - $B/demo/orders/42 -o /dev/null | grep X-Correlation-Id
curl -s -D - -H 'X-Correlation-Id: bad id' $B/demo/orders/42 -o /dev/null | grep X-Correlation-Id

# 3) 各失败类别（注意返回体不含底层异常细节）
curl -s "$B/demo/orders?id=abc"
curl -s -X POST $B/demo/orders/reject
curl -s $B/demo/downstream
curl -s $B/demo/timeout

# 4) 异步 / 后台传播（propagated=true）
curl -s -H 'X-Correlation-Id: async-trace-7' $B/demo/async
curl -s -X POST -H 'X-Correlation-Id: job-trace-7' $B/demo/jobs

# 5) 看指标：totalCount == sampledCount + droppedCount
curl -s $B/internal/metrics

# 6) 调整采样率后再压一批，计数仍精确，仅耗时明细被丢弃
curl -s -X POST "$B/internal/sampler?rate=0"
for i in $(seq 1 100); do curl -s -o /dev/null $B/demo/orders/$i; done
curl -s $B/internal/metrics
```

并发压测示例：

```bash
seq 1 200 | xargs -P 32 -I{} curl -s -o /dev/null \
  -H "X-Correlation-Id: par-{}" http://127.0.0.1:29090/demo/orders/{}
curl -s http://127.0.0.1:29090/internal/metrics
```

观察服务端日志可验证：每行都带 `[correlationId]`，异步线程（`obs-async-*`）日志携带的是提交请求的标识，定时线程（`obs-sched-1`）使用 `auto-` 标识。
