# Model Gateway Demo

这是一个连接 DeepSeek API 的 Spring Boot 教学项目，同时保留可控的假模型来演示 Java 大模型应用最重要的稳定性机制：有界并发、总截止时间、有限重试、指数退避与抖动、熔断、降级、取消传播和可观测性。

## 环境

- Java 17
- Maven 3.9+
- Spring Boot 3.4.13

注意：安装 JDK 21 后可以把请求执行器切换为虚拟线程，但模型并发限制仍然必须保留。

## DeepSeek配置

真实模型是默认模式，使用：

```text
base URL: https://api.deepseek.com
model: deepseek-flash
API Key环境变量: DEEPSEEK_API_KEY
```

密钥不能写入 `application.yml`、源码或提交到Git。打开新PowerShell后可用下面的命令确认环境变量是否存在，此命令不会打印密钥：

```powershell
[string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) -eq $false
```

可通过 `DEEPSEEK_MODEL` 和 `DEEPSEEK_MAX_TOKENS` 覆盖默认模型与最大输出长度。

## 运行

```powershell
cd D:\javaweb\model-gateway-demo
mvn test
mvn spring-boot:run
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

正常请求：

```powershell
$body = @{ message = '什么是信号量？' } | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8080/api/v1/chat `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

## 使用假模型进行故障模拟

启动前切换供应商：

```powershell
$env:MODEL_PROVIDER = 'fake'
mvn spring-boot:run
```

然后修改请求中的 `simulation`：

| 值 | 行为 | 预期结果 |
|---|---|---|
| `SUCCESS` | 立即成功 | `attempts=1` |
| `SLOW` | 响应时间超过剩余预算 | HTTP 504 |
| `TEMPORARY_FAILURE` | 前两次失败，第三次成功 | `attempts=3` |
| `PERMANENT_FAILURE` | 永久错误 | HTTP 502，不重试 |
| `INTERRUPTED` | 模拟线程中断 | 停止处理并保留中断状态 |

完整接口定义见 [openapi.yaml](openapi.yaml)。

## 请求是怎样被保护的

```text
HTTP请求
  ↓ 参数校验
ChatService
  ↓ 创建ModelRequest
ModelGateway
  ↓ 检查总截止时间
  ↓ 检查熔断器
  ↓ 限时获取Semaphore许可证
  ↓ DeepSeekModelClient或FakeModelClient
  ↓ 成功 / 有限重试 / 降级 / 快速失败
  ↓ finally释放许可证
HTTP响应
```

### 1. Semaphore：保护下游并发能力

`ModelGateway` 默认只有20个许可证。即使将来使用数万个虚拟线程，同时调用模型的请求仍不会超过20个。

许可证的关键写法是：

```java
boolean acquired = false;
try {
    acquired = permits.tryAcquire(wait, TimeUnit.NANOSECONDS);
    // 调用模型
} finally {
    if (acquired) {
        permits.release();
    }
}
```

只有真正获取成功才归还，否则会把信号量错误增大。

### 2. 总截止时间

排队、模型调用、退避和重试共享同一个 `request-timeout`。代码每一步都重新计算剩余时间，而不是给每一步重新分配完整超时。

### 3. 有限重试

只有 `TemporaryModelException` 可以重试。永久错误、中断、并发拒绝和熔断拒绝不会重试。退避时间使用指数增长，并加入随机抖动，避免大量请求同时重试。

### 4. 熔断器

熔断器包含三种状态：

```text
CLOSED --连续失败--> OPEN --等待恢复窗口--> HALF_OPEN
  ↑                                           |
  └--------------- 探测成功 -----------------┘
```

半开状态只允许少量探测调用。探测失败会重新打开，避免恢复中的下游再次被流量压垮。

### 5. 取消传播

捕获 `InterruptedException` 后会重新设置中断标记：

```java
Thread.currentThread().interrupt();
```

这样上游仍能知道请求已被取消，重试循环也会立即终止。

## HTTP状态

| 状态 | 含义 |
|---|---|
| 200 | 正常答案，或带 `degraded=true` 的明确降级答案 |
| 400 | 输入校验失败 |
| 429 | 并发已满，获取许可证超时 |
| 502 | 模型永久拒绝请求 |
| 503 | 熔断器打开或请求被取消 |
| 504 | 总截止时间耗尽 |

## 监控指标

访问所有Prometheus指标：

```powershell
Invoke-WebRequest http://localhost:8080/actuator/prometheus | Select-Object -Expand Content
```

核心指标：

| 指标 | 含义 |
|---|---|
| `model.gateway.active.calls` | 当前正在调用模型的数量 |
| `model.gateway.available.permits` | 剩余许可证 |
| `model.gateway.wait.duration` | 等待许可证的时间 |
| `model.gateway.call.duration` | 实际模型调用耗时 |
| `model.gateway.calls` | 按结果分类的调用次数 |
| `model.gateway.retries` | 重试次数 |
| `model.gateway.rejections` | 并发或熔断拒绝次数 |
| `model.gateway.timeouts` | 总截止时间耗尽次数 |
| `model.gateway.fallbacks` | 降级次数 |
| `model.gateway.circuit.state` | `0=CLOSED`、`1=OPEN`、`2=HALF_OPEN` |

## 类与知识点的对应关系

| 类 | 主要知识点 |
|---|---|
| `ModelGateway` | 信号量、截止时间、重试、中断、资源释放 |
| `SimpleCircuitBreaker` | 状态机、同步、熔断与半开探测 |
| `DeepSeekModelClient` | 真实HTTP调用、剩余超时预算、错误分类和安全认证 |
| `FakeModelClient` | 可控的阻塞I/O、临时错误与永久错误 |
| `GatewayMetrics` | 队列前兆、并发量和下游延迟监控 |
| `GlobalExceptionHandler` | 快速失败和明确HTTP语义 |
| `ModelGatewayConcurrencyTest` | 验证真实并发上限 |

## 验证与覆盖率

```powershell
mvn verify
```

`verify` 会执行全部测试并检查行覆盖率不低于85%。HTML报告位于：

```text
target/site/jacoco/index.html
```

## 更换模型时怎么改

修改 `DEEPSEEK_MODEL` 即可切换DeepSeek模型。`DeepSeekModelClient` 使用网关传入的剩余 `timeout`，并将供应商错误区分为临时故障与永久故障。客户端内部没有额外重试，所有重试统一由 `ModelGateway` 管理，避免绕过总截止时间和监控。

如果以后更换其他供应商，只需要新增另一个 `ModelClient` 实现；信号量、截止时间、重试、熔断和指标不需要重写。
