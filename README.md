# 多租户 API 网关（Java / Spring Boot）

在一个 Spring Boot 服务内实现面向多租户的接口访问控制，三件事彼此独立、结果稳定可解释：

1. **认证与鉴权**：按 API Key 识别租户与角色，区分多种拒绝原因；越权访问不泄漏目标资源是否存在。
2. **每租户独立配额限流**：固定时间窗口、按 epoch 对齐，高并发下用 CAS 保证计数准确。
3. **写请求幂等**：`Idempotency-Key` + 请求体指纹，重复提交只生效一次且结果一致，同键不同体明确拒绝。

所有成功与拒绝路径都写**审计记录**（内存留存 + 结构化日志）。

---

## 1. 启动

```bash
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/multi-tenant-api-gateway-java-1.0-SNAPSHOT.jar
```

默认端口 `8080`（`application.properties` 中 `server.port`）。

### 预置租户、密钥与资源

| 租户 | 每窗口配额 | 密钥（X-Api-Key） | 角色 |
| --- | --- | --- | --- |
| `acme` | 5 | `acme-admin-key-2026` | ADMIN |
| `acme` | 5 | `acme-writer-key-2026` | WRITER |
| `acme` | 5 | `acme-reader-key-2026` | READER |
| `globex` | 3 | `globex-admin-key-2026` | ADMIN |
| `globex` | 3 | `globex-writer-key-2026` | WRITER |
| `globex` | 3 | `globex-reader-key-2026` | READER |
| `acme` | 5 | `acme-expired-key-2025` | 已过期（WRITER） |
| `acme` | 5 | `acme-revoked-key-2026` | 已吊销（WRITER） |

预置资源：`res-acme-1`、`res-acme-2` 属于 `acme`；`res-globex-1` 属于 `globex`。

---

## 2. 认证与角色模型

所有 `/api/**` 请求必须携带头 `X-Api-Key`。认证失败统一返回 `401`，但 `error.code` 严格区分原因：

| 场景 | HTTP | error.code |
| --- | --- | --- |
| 未携带 X-Api-Key | 401 | `missing_api_key` |
| 密钥不存在 | 401 | `unknown_api_key` |
| `expiresAt` 已过 | 401 | `api_key_expired` |
| 密钥 `revoked=true` | 401 | `api_key_revoked` |

> 判定顺序为 **过期先于吊销**：一把已过期且被吊销的密钥仍报“过期”，使生命周期状态解释单一、可复现。

### 角色与可访问范围

| 操作 | READER | WRITER | ADMIN |
| --- | --- | --- | --- |
| `GET /api/resources/{id}`（本租户） | ✅ | ✅ | ✅ |
| `POST /api/resources` | ❌ 403 | ✅ | ✅ |
| `DELETE /api/resources/{id}`（本租户） | ❌ 403 | ❌ 403 | ✅ |

### 越权不泄漏资源存在性

- **角色不足**（如 READER 写、WRITER 删）：资源尚未定位，直接 `403 forbidden_role`。
- **跨租户访问单项资源**：`“属于其它租户”` 与 `“不存在”` 走**完全相同的 404 响应体**
  （`{"error":{"code":"error_404","message":"resource not found"}}`），
  调用方无法据此判断目标资源是否存在、属于谁。DELETE 也一样：先做租户定位再判角色，
  非本租户资源一律 404 而不是 403。

错误响应统一结构：

```json
{ "error": { "code": "forbidden_role", "message": "reader role cannot write resources" } }
```

---

## 3. 限流：固定时间窗口语义

- 每个租户一份独立配额，互不影响。认证失败的请求不计数。
- 窗口长度由 `gateway.rate-limit-window-millis` 配置，默认 **60000ms**。
- **窗口按 epoch 对齐（刷新语义可复现）**：

  ```
  windowStart = floor(nowMillis / windowMillis) * windowMillis
  resetAt     = windowStart + windowMillis
  ```

  无论进程何时启动、请求何时到达，同一物理时刻必落在同一窗口；到达 `resetAt` 即整窗清零、配额完整恢复。

- 响应头（放行与拒绝都返回）：

  | 头 | 含义 |
  | --- | --- |
  | `X-RateLimit-Limit` | 当前窗口配额 |
  | `X-RateLimit-Remaining` | 本次判定后剩余额度（拒绝时为 0） |
  | `X-RateLimit-Reset` | 当前窗口刷新时间点（epoch 毫秒） |
  | `Retry-After` | （仅 429）建议重试的秒数 |

- 超限返回 `429 rate_limit_exceeded`，响应体里带 `remaining` 与 `resetAt`。

### 并发正确性

每租户一个 `AtomicReference<Window>`，窗口翻转与计数都用 CAS；
**“是否超限的判断”与“计数 +1”在同一个 CAS 重试循环内**，
因此高并发下成功放行数恰好等于配额——不少算（丢计数）、不多算（超发），被拒请求不占额度。

---

## 4. 幂等标识规则

- 仅对**写请求**（`POST/PUT/PATCH/DELETE`）且携带 `Idempotency-Key` 头时生效。
- 幂等键**按租户隔离**：`(tenantId, idempotencyKey)` 唯一；不同租户复用同一键名互不影响。
- 请求指纹 = `SHA-256(method | path | 原始请求体)`，用于判断“同键是否同体”。

| 情形 | 结果 |
| --- | --- |
| 首次（键不存在） | 真正执行，固化响应 |
| 同键 + **同体** 重放 | 不重复执行业务，返回首次的状态码与响应体，并加响应头 `Idempotent-Replay: true` |
| 同键 + **不同体** | `409 idempotency_key_conflict`，**旧结果保留不被覆盖** |
| 同键请求仍在执行中（并发） | `409 idempotency_key_in_use`，同一时刻只有一个请求真正执行 |

要点：

- **成功和业务失败都固化**：首次若进入控制器并产生确定性结果（含 403/404 等业务失败），重放返回完全一致的结果。
- **前置限流等短路不算业务执行**：若首次请求在到达控制器前被限流（429），会释放幂等占位，
  允许稍后用同键同体重试，避免一次偶然限流把键永久卡在 in-use。
- 并发同键同体最终只会创建一个资源，所有 201 重放都指向同一资源 id。

---

## 5. 审计

每个 `/api/**` 请求在最外层审计过滤器收尾时落一条记录（成功与所有拒绝路径都包含），字段：

`timestamp, tenantId, apiKey(脱敏尾号), role, method, path, httpStatus, decision(ALLOW/REJECT/REPLAY), reason, idempotencyKey, replay`

同时写结构化日志（logger 位于 `...gateway` 包），控制台可见例如：

```
audit tenant=acme role=READER keyTail=****2026 GET /api/resources/res-acme-1 status=200 decision=ALLOW reason=none idempotencyKey=null replay=false
ratelimit tenant=globex decision=REJECT limit=3 used=3 remaining=0 ...
idempotency tenant=acme key=k1 decision=CONFLICT storedHash=... incomingHash=...
```

---

## 6. 本地验证

### 运行测试（含并发限流、越权、幂等重放/冲突、并发幂等、审计）

```bash
mvn test
```

判定依据与计数过程会直接打印在控制台（测试 logger：`TEST-RATELIMIT` / `TEST-AUTH` /
`TEST-AUTHZ` / `TEST-IDEMPOTENCY` / `TEST-IDEMPOTENCY-CONC` / `TEST-AUDIT`）。

关键用例：

- `RateLimitConcurrencyTest`：100 线程抢配额 20，断言**恰好 20 个 200、80 个 429**；
  并推进可变时钟验证窗口在对齐边界精确刷新。
- `AuthorizationTest`：断言跨租户 404 与“不存在”404 的响应体字节级一致；角色越权 403。
- `IdempotencyTest`：重放返回首次一致结果（含 `Idempotent-Replay` 头）；同键不同体 409 且不覆盖旧结果。
- `IdempotencyConcurrencyTest`：24 并发同键同体，只真正创建一次，最终重放收敛到同一资源。
- `AuditTest`：认证/鉴权/幂等冲突拒绝路径与成功路径都有审计记录。

### curl 手工验证

```bash
# 认证：过期 vs 吊销 原因不同
curl -s -H 'X-Api-Key: acme-expired-key-2025' http://localhost:8080/api/resources/res-acme-1
curl -s -H 'X-Api-Key: acme-revoked-key-2026' http://localhost:8080/api/resources/res-acme-1

# 越权：他租户资源与不存在返回同样的 404
curl -s -H 'X-Api-Key: acme-reader-key-2026' http://localhost:8080/api/resources/res-globex-1
curl -s -H 'X-Api-Key: acme-reader-key-2026' http://localhost:8080/api/resources/no-such

# 限流：连续请求观察 X-RateLimit-* 头与 429
curl -i -H 'X-Api-Key: globex-reader-key-2026' http://localhost:8080/api/resources/res-globex-1

# 幂等：首次 / 重放 / 冲突
curl -i -X POST -H 'X-Api-Key: acme-writer-key-2026' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k1' -d '{"name":"n","content":"A"}' http://localhost:8080/api/resources
curl -i -X POST -H 'X-Api-Key: acme-writer-key-2026' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k1' -d '{"name":"n","content":"A"}' http://localhost:8080/api/resources   # 重放
curl -i -X POST -H 'X-Api-Key: acme-writer-key-2026' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k1' -d '{"name":"n","content":"B"}' http://localhost:8080/api/resources   # 409 冲突
```

---

## 7. 代码结构

```
gateway/
  config/        时钟(可替换)、配置属性、种子数据、MVC 拦截器注册
  security/      ApiKey 认证、角色授权、租户目录、路径规则、认证过滤器
  ratelimit/     固定窗口限流服务 + 限流过滤器（CAS 计数）
  idempotency/   幂等存储、幂等过滤器、请求/响应缓存包装、处理器标记拦截器
  audit/         审计服务 + 最外层审计过滤器
  resource/      受保护资源的控制器/仓储/种子
  web/           统一 JSON 错误响应、异常处理、过滤器间属性常量
```

> 当前租户目录、配额计数、幂等记录、审计日志均为**内存实现**（线程安全），便于自包含验证；
> 生产替换为数据库 / Redis 时保持各接口契约不变即可。
