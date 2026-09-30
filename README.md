# 请求级可观测性能力（Request Observability）

为 Spring Boot 服务提供**请求级关联标识（requestId）**与**稳定、可解释、有界**的日志/指标能力，
覆盖同步调用、异步处理、后台定时任务三类链路，并在高并发与异常场景下保证不丢数、不串号、不泄露底层异常。

- 技术栈：Java 21、Spring Boot 3.4（Web / Validation）、SLF4J MDC、JDK 并发原语（无额外监控中间件依赖）。
- 核心代码：`src/main/java/com/github/h3yow/serviceobservabilitypipelinejava/observability/`
- 演示代码：`.../demo/`（下单接口，含各类故障注入、异步、后台任务）
- 测试：`src/test/.../tests/`，共 20 个用例，覆盖并发与异常链路，单测日志打印输入与判定依据。

---

## 1. 关联标识（requestId）传播规则

### 1.1 ID 来源与兜底（确定且可区分）

入口 `RequestContextFilter` 读取请求头 `X-Request-Id`（头名可配 `observability.header-name`），
并把最终 ID 回写到响应头，规则如下：

| 入口情况 | 规则 | ID 形态 | 示例 |
|---|---|---|---|
| 携带合法 ID | 校验通过（`[A-Za-z0-9._-]{1,64}`）后原样使用 | 上游值 | `upstream-demo-1` |
| **缺失 / 空白** | 兜底生成，前缀 `gen-` | `gen-<进程内单调序号>-<8位随机>` | `gen-2-2c8de012` |
| **存在但非法**（含空格等非法字符或超长） | 兜底生成，前缀 `bad-` | `bad-...` | `bad-3-7069d5e9` |
| **无入口的后台定时任务** | 兜底生成，前缀 `bg-` | `bg-...` | `bg-1-cde1f83b` |

三种兜底前缀彼此可区分，也与上游 ID 可区分，日志里可直接判断一条链路的来源。

- 校验 + 长度上限（默认 64）防止伪造超长/脏值污染日志与存储。
- 生成值含进程内单调递增序号（`AtomicLong`），确定、无碰撞；短随机片段用于区分多实例。
- 同一个被调度的后台任务在各次执行间使用同一个 `bg-` ID（提交时捕获一次），便于把周期任务的多次 tick 关联起来；不同后台任务各自独立。

### 1.2 三种链路的传播

| 链路 | 传播方式 | 是否可能串号 |
|---|---|---|
| 同步调用 | 入口线程绑定 `ThreadLocal` + SLF4J MDC，同线程自然贯穿 | 否 |
| 异步处理（`@Async` / `CompletableFuture` / 线程池） | **提交时**由 `RequestIdContext.wrap(...)`（经线程池 `TaskDecorator`）快照 ID，**执行时**绑定到工作线程，执行完**还原工作线程原上下文** | 否（不使用 `InheritableThreadLocal`，避免线程池复用串号） |
| 后台定时任务（`@Scheduled`） | 调度器配置了同一个 `TaskDecorator`；提交线程无上下文时自动赋予 `bg-` | 否 |

关键约束：

- 业务线程池必须使用带 `ContextCopyingTaskDecorator` 的 `observationExecutor`
  （`@Async("observationExecutor")`）；裸 `new Thread`/未装饰线程池不会自动传播，这是有意的显式设计。
- 异步接续（`thenApplyAsync`）也要传 `observationExecutor`，否则接续动作可能落在无上下文线程。
- Filter 在 `finally` 中还原/清理 ThreadLocal 与 MDC，Servlet 容器线程复用后不会残留上一请求的 ID。
- Servlet 异步请求（返回 `CompletableFuture`/`Callable`）通过 `AsyncListener` 在异步真正完成/超时/出错时收尾。

### 1.3 日志

`application.properties` 的日志模板包含 MDC 中的 requestId：

```
%d{HH:mm:ss.SSS} %-5level [%thread] [req=%X{requestId:-}] %logger{36} - %msg%n
```

任意线程（含 `obs-async-*`、`obs-sched-*`）输出形如 `[req=upstream-async-9]`，无上下文时为 `[req=]`。

---

## 2. 指标：维度收敛、有界、采样与对账

### 2.1 指标维度（取值全部收敛，杜绝基数爆炸）

每条时间序列的 key 为 `endpoint × outcome × reason`：

- **endpoint（接口）**：使用 Spring MVC **路由模板**而非原始 URI。
  `/orders/100`、`/orders/200` 都收敛为 `GET /orders/{id}`；未匹配路由的请求收敛为 `unknown`。
- **outcome（结果，固定 6 个取值）**：
  `SUCCESS`、`INVALID_ARGUMENT`（参数问题）、`BUSINESS_REJECTED`（业务拒绝）、
  `DOWNSTREAM_ERROR`（下游异常）、`TIMEOUT`（超时）、`UNKNOWN_ERROR`（未知/兜底）。
- **reason（原因，受控取值）**：
  - 业务拒绝只接受配置白名单 `observability.allowed-business-reasons`
    （默认 `STOCK_LIMIT`、`RISK_BLOCK`）内的值；白名单外**一律收敛为 `other`**；
  - 下游名做字符净化（仅保留 `A-Z0-9_`）并加 `downstream_` 前缀，不把异常 message/任意字符串带入维度；
  - 其余映射为分类器内部固定码（`invalid_request` / `not_found` / `method_not_allowed` /
    `gateway_error` / `downstream_timeout` / `internal_error`）。

每条序列记录：次数 `count`、总耗时、最大耗时、**固定桶位延迟直方图**
（桶边界 `10/50/100/250/500/1000/2500/5000ms` + 一个溢出桶）、以及在途请求数。
单条序列内存恒定，不随请求体/输入变化。

### 2.2 内存有界（序列硬上限 + overflow 桶）

`observability.metrics.max-series`（默认 200）限制序列数量。超过上限的**新** key 不新建序列，
全部并入固定的 `overflow` 序列，且 `overflowCount` 单独计数。即使出现异常的高基数取值，
内存占用也有严格上界；被并入 overflow 的请求**计数不丢**。

### 2.3 采样策略（只影响详细日志，计数始终全量）

- 采样率 `observability.sample.rate` ∈ [0,1]，默认 1.0。
- **被采样丢弃的请求不打印每请求详细日志，但次数/耗时指标照常记录（全量）。**
- 判定为确定性整数规则，无随机：对第 n 个请求，当且仅当 `floor(n*rate) > floor((n-1)*rate)` 时命中。
  rate=0.25 时每 4 个命中 1 个，长期命中数严格等于 `round(N*rate)`，且命中位置均匀。
- 可运行期调整（无需重启）：`POST /internal/sampler?rate=0.1`（也支持 form 表单 `rate=0.1`）。
- **对账恒等式**：任意时刻的快照都满足

  ```
  sampledIn + sampledOut == totalRequests
  ```

  采样判定与总计数在请求收尾的同一处递增，避免"请求在途"造成快照两侧口径不一致。调整采样率前后结论可对齐。

### 2.4 高并发开销与线程安全

- 所有计数使用 `LongAdder`（热点分散、高并发写开销低），序列表用 `ConcurrentHashMap`，
  序列序号用 `AtomicInteger`，采样序号用 `AtomicLong`。采集路径无锁、无 IO、无每条对象堆积。
- 快照读使用 LongAdder 的弱一致求和，适合监控聚合场景；写路径不被读阻塞。

### 2.5 内部端点

| 方法与路径 | 说明 |
|---|---|
| `GET  /internal/metrics` | 当前指标快照（序列、总数、采样计数、overflow、配置上限） |
| `GET  /internal/sampler` | 查看当前采样率 |
| `POST /internal/sampler?rate=` | 运行期调整采样率（自动截断到 [0,1]） |

---

## 3. 异常分类与对外响应

- `GlobalExceptionHandler`（`@RestControllerAdvice`）统一捕获，依据 `ResultClassifier` 归类。
  超时识别覆盖 `TimeoutException`、`DownstreamServiceException.timeout(...)` 以及 cause 链（最多 8 层）中的超时。
- 对外错误体 `ErrorResponse{requestId, code, message}` 只包含收敛后的错误码与固定/安全文案，例如：
  - 参数问题 → HTTP 400 `INVALID_ARGUMENT`
  - 业务拒绝 → HTTP 422 `BUSINESS_REJECTED:STOCK_LIMIT`
  - 下游异常 → HTTP 502 `DOWNSTREAM_ERROR`（不透出下游服务名/IP/连接信息）
  - 超时     → HTTP 504 `TIMEOUT`
  - 未知     → HTTP 500 `INTERNAL_ERROR`
- **底层异常的类名、message、堆栈只出现在服务端日志**，不回传调用方；调用方可凭 `requestId` 报障，服务端按 ID 检索完整链路。
- 指标归因单一落点：异常处理器把"基于真实异常的精确分类"标记到本次观测，
  Filter 收尾时优先采用（无异常逃逸时才按 HTTP 状态反推），保证 reason 精确且不重复计数。

---

## 4. 配置项（`application.properties`）

```properties
observability.header-name=X-Request-Id        # 入口 ID 请求头
observability.sample.rate=1.0                 # 详细日志采样率 [0,1]（计数不受影响）
observability.metrics.max-series=200          # 指标序列硬上限，超限并入 overflow
observability.allowed-business-reasons[0]=STOCK_LIMIT
observability.allowed-business-reasons[1]=RISK_BLOCK
observability.background-job.enabled=false    # 演示后台任务开关（观察 bg- 链路时置 true）
observability.background-job.fixed-delay-ms=5000
```

---

## 5. 本地复现与验证

### 5.1 构建与测试

```bash
mvn test                 # 运行全部 20 个用例（含并发与异常链路），日志打印每条输入与判定依据
mvn package -DskipTests  # 打包可执行 jar
```

测试构成：

| 测试类 | 覆盖点 |
|---|---|
| `RequestIdContextTest` | 缺失/非法/合法解析、`gen-/bad-/bg-` 兜底、跨线程传播、线程复用清理、64×200 并发隔离 |
| `ResultClassifierTest` | 4 类失败 + 成功归类、reason 白名单收敛、cause 链超时、下游名净化、HTTP 状态映射 |
| `SamplerTest` | 各采样率精确命中、越界截断、32 线程并发不重不漏、运行期调参后守恒 |
| `RequestMetricsTest` | 聚合/对账、模板收敛、超上限 overflow（计数不丢）、50×500 并发总数精确 |
| `RequestObservabilityIntegrationTest` | 端到端：ID 贯穿同步/异步、兜底头、四类失败错误体且不泄密、200 并发不串号且计数精确 |

### 5.2 启动并用 HTTP 复现

```bash
# 固定端口 + 开启后台任务以观察 bg- ID
java -jar target/service-observability-pipeline-java-1.0-SNAPSHOT.jar \
     --server.port=28080 \
     --observability.background-job.enabled=true \
     --observability.background-job.fixed-delay-ms=2000
```

另开终端：

```bash
B=http://localhost:28080

# 正常同步（响应头原样回传 requestId）
curl -i -X POST $B/orders -H 'X-Request-Id: upstream-demo-1' \
  -H 'Content-Type: application/json' -d '{"itemId":"item-7"}'

# 缺失 -> gen- / 非法 -> bad-
curl -D - -o /dev/null $B/orders/42
curl -D - -o /dev/null $B/orders/42 -H 'X-Request-Id: bad id!!'

# 四类失败（注意错误体不含底层细节）
curl -s -X POST $B/orders -H 'Content-Type: application/json' -d '{"itemId":"i","mode":"risk"}'
curl -s -X POST $B/orders -H 'Content-Type: application/json' -d '{"itemId":"i","mode":"downstream"}'
curl -s -X POST $B/orders -H 'Content-Type: application/json' -d '{"itemId":"i","mode":"timeout"}'
curl -s -X POST $B/orders -H 'Content-Type: application/json' -d '{"itemId":""}'

# 异步（requestId 跨线程池传播）
curl -s -X POST "$B/orders/async?itemId=item-2" -H 'X-Request-Id: upstream-async-9'

# 运行期调采样率（详细日志变化，计数仍全量）
curl -X POST "$B/internal/sampler" --data 'rate=0.25'

# 查看指标，校验 sampledIn + sampledOut == totalRequests
curl -s $B/internal/metrics
```

后台任务日志示例（无入口，自动 `bg-`）：

```
INFO [obs-sched-1] [req=bg-1-cde1f83b] ...demo.BackgroundJobs - background cleanup tick ...
```

### 5.3 验证维度收敛

对 `/orders/100`、`/orders/200`、`/orders/300` 各发请求后，`/internal/metrics` 中
只会出现一条 `GET /orders/{id}` 序列，`count` 累加而不是每个路径值一条序列。

---

## 6. 关键类速览

| 类 | 职责 |
|---|---|
| `RequestIdContext` | ThreadLocal + MDC 持有 ID；入口解析、`gen-/bad-/bg-` 兜底、`wrap/callWith/attach/restore` 跨线程传播 |
| `RequestContextFilter` | 入口解析/回写 ID、绑定上下文、驱动观测生命周期、Servlet 异步收尾 |
| `ResultClassifier` | 异常/状态 → 6 类 outcome + 受控 reason（白名单/净化/固定映射/超时 cause 链） |
| `GlobalExceptionHandler` | 统一错误体，隐藏底层细节，把精确分类交给观测 |
| `RateSampler` | 确定性比率采样，运行期可调，仅作用于详细日志 |
| `RequestMetrics` / `MetricsSnapshot` | LongAdder 有界聚合、固定延迟桶、overflow、一致性对账 |
| `RequestObservation` | 单请求计时、采样、归类、指标落地、结构化访问日志 |
| `ContextCopyingTaskDecorator` | 线程池/调度器任务装饰，提交时快照、执行时绑定、结束还原 |
