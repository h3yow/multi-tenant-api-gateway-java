# Multi-Tenant API Gateway（多租户接口访问控制）

一个基于 Spring Boot 3 / Java 21 的演示服务，围绕“多租户”提供三件事：

1. **认证与角色鉴权**：识别租户身份，密钥无效 / 过期 / 吊销分别给出不同拒绝原因；同租户内角色范围分离，越权访问不泄漏资源是否存在。
2. **每租户独立限流**：固定时间窗口配额，高并发下计数精确（不超发、不漏扣），拒绝时告知剩余额度与窗口刷新时刻。
3. **写请求幂等**：带 `Idempotency-Key` 的写请求只真正生效一次；同键不同体明确冲突拒绝；成功与失败的首次结果都会被重放。
4. **全路径审计**：成功、业务失败、各层拒绝路径都落审计记录。

所有状态保存在内存中（`DataStore` / `RateLimitService` / `IdempotencyService` / `AuditLog`），重启即清空，便于本地演示与可复现测试。

---

## 1. 认证与角色模型

### 1.1 请求如何识别租户

所有 `/api/**` 请求都必须携带请求头：

```
X-Api-Key: <密钥>
```

`AuthenticationFilter`（过滤器链最前）用密钥在 `DataStore` 中查找登记信息，认证通过后把
`Principal(tenantId, keyId, role)` 绑定到请求属性，后续限流、幂等、业务都以 `tenantId` 做隔离。

### 1.2 三种密钥问题，三种拒绝原因

认证失败统一返回 `401`，但响应体的 `error` 字段明确区分原因，便于调用方排障与差异化处理：

| 情况 | HTTP | `error` | 判定依据 |
| --- | --- | --- | --- |
| 未携带 / 空密钥 | 401 | `MISSING_KEY` | `X-Api-Key` 缺失或空白 |
| 密钥不存在 | 401 | `INVALID_KEY` | 存储中无匹配登记 |
| 密钥已过期 | 401 | `EXPIRED_KEY` | `now >= expiresAt` |
| 密钥被主动吊销 | 401 | `REVOKED_KEY` | 登记记录 `revoked = true` |

> 吊销与过期同时成立时优先判定为 `REVOKED_KEY`：吊销是显式安全动作，应立即生效。

### 1.3 角色模型与可访问范围

每个 key 绑定一个租户内角色，目前有：

- `ADMIN`：可读普通资源与受限资源（`scoped=true`），允许创建订单；
- `VIEWER`：只能读普通资源（`scoped=false`），不能创建订单。

资源（演示中为“订单”）带 `scoped` 标记。鉴权规则：

- `GET /api/orders`：受限资源不会出现在 VIEWER 的列表中（名称、ID 都不出现）；
- `GET /api/orders/{id}`：VIEWER 访问受限资源得到的响应，与访问一个根本不存在的 ID **完全一致**
  （同为 `404 NOT_FOUND`、同一固定 message，不回显目标 ID），因此无法通过响应差异推断资源是否存在；
- `POST /api/orders`：VIEWER 得到 `403 FORBIDDEN`（这是端点级操作权限，不涉及资源存在性）；
- 所有资源查找都限定在调用方自己的 `tenantId` 内，跨租户访问同样表现为 `404`。

### 1.4 内置演示密钥

| 密钥 | 租户 | 角色 | 状态 |
| --- | --- | --- | --- |
| `secret-tenant-a-admin` | tenant-a | ADMIN | 有效 |
| `secret-tenant-a-viewer` | tenant-a | VIEWER | 有效 |
| `secret-tenant-a-expired` | tenant-a | ADMIN | 已过期 |
| `secret-tenant-a-revoked` | tenant-a | ADMIN | 已吊销 |
| `secret-tenant-b-admin` | tenant-b | ADMIN | 有效 |
| `secret-tenant-b-viewer` | tenant-b | VIEWER | 有效 |

---

## 2. 限流：每租户独立的固定时间窗口

### 2.1 语义

- 窗口长度固定为 60 秒（`RateLimitService.WINDOW_MILLIS`）。
- 某时刻 `now` 所属窗口为左闭右开区间：

  ```
  windowStart = floor(now / 60000) * 60000
  windowEnd   = windowStart + 60000
  ```

  因此给定当前时间，窗口边界与“刷新时刻”都可精确复现，与请求到达顺序无关。
- 每个租户在**自己的窗口**内拥有独立配额（演示：tenant-a = 5，tenant-b = 3），互不影响。
- 窗口内每放行一个请求计数加 1；计数达到配额后，窗口内其余请求一律拒绝。跨过 `windowEnd`
  后计数归零，重新计满。

### 2.2 高并发下为什么计数是准确的

窗口状态保存在 `AtomicReference<Window>` 中，`Window(startEpoch, used)` 不可变。
每次扣减用 CAS 自旋完成：

1. 读到当前窗口；若已跨窗口则先 CAS 换成新的空窗口；
2. `used >= limit` → 拒绝，不做任何修改；
3. 否则构造 `used+1` 的新窗口做 CAS；失败说明有并发线程刚扣减成功，自旋重读再判。

每次成功的 CAS 恰好对应一次真实放行，因此：

- **不会多算（超发）**：两个线程不可能都基于同一份旧计数同时放行；
- **不会少算（漏扣）**：每次放行都伴随一次成功的计数提交。

### 2.3 拒绝时调用方能看到什么

放行与拒绝都会回写以下响应头：

| 响应头 | 含义 |
| --- | --- |
| `X-RateLimit-Limit` | 当前窗口总配额 |
| `X-RateLimit-Remaining` | 本次判定后窗口剩余额度（拒绝时为 `0`） |
| `X-RateLimit-Reset` | 窗口刷新时刻（epoch 毫秒，HTTP 日期格式） |
| `Retry-After` | （仅拒绝时）建议等待秒数 |

拒绝响应：`429 Too Many Requests`，响应体 `error = RATE_LIMITED`。

---

## 3. 幂等标识规则（写请求）

### 3.1 如何使用

- 仅对 **`POST /api/**` 且携带** `Idempotency-Key: <调用方生成的字符串>` 请求头的请求生效。
- 幂等键的作用域是 **(租户, 键)**：不同租户使用相同字符串互不影响。
- 请求指纹 = `SHA-256(Content-Type + "\n" + 原始请求体字节)`。

### 3.2 判定规则

| 情形 | 行为 |
| --- | --- |
| 首次出现的键 | 真正执行业务，完成后缓存“状态码 + 响应体” |
| 同键 + 同指纹（重放） | 不再次执行业务，原样返回首次的状态码与响应体 |
| 同键 + 不同指纹 | 返回 `409 IDEMPOTENCY_CONFLICT`，**绝不覆盖**旧结果；旧键之后仍可正常重放 |
| 同键并发（首次仍在执行） | 并发请求等待首次完成，然后重放首次结果（同样只真正执行一次） |

要点：

- **失败也缓存**：首次若返回业务失败（例如 `403`），后续同键同体重放的是同一个失败结果，
  不会重复进入业务逻辑。
- 响应体按字节缓存重放，因此重复请求拿到的结果与首次一致。

---

## 4. 审计

`AuditFilter` 位于过滤器链最外层，对每个 `/api/**` 请求记录一条终态事件
（`REQUEST_OK` / `REQUEST_DENIED` / `REQUEST_FAILED`）；各内层组件另外记录判定级事件：

- 认证：`AUTH_PASS` / `AUTH_DENY`（含拒绝原因）
- 限流：`RATE_LIMIT_PASS` / `RATE_LIMIT_DENY`（含 limit / remaining / 刷新时刻）
- 幂等：`IDEMPOTENCY_REPLAY` / `IDEMPOTENCY_CONFLICT` / `COMPLETE`

每条事件包含 `timestamp, tenantId, keyId, method, path, action, httpStatus, detail`，
同时输出到应用日志（`logger=...audit.AuditLog`）并保存在内存 `AuditLog` 中。
认证/限流/幂等拒绝以及服务端异常路径都保证有记录，不会漏记。

---

## 5. 过滤器链顺序

```
AuditFilter (最外层, 记录终态)
  └─ AuthenticationFilter   识别租户/密钥，401 分类拒绝
       └─ RateLimitFilter   每租户固定窗口配额，429 + 额度头
            └─ IdempotencyFilter  仅命中写请求+幂等键，重放/冲突/缓存
                 └─ Controller (角色鉴权在控制器内完成)
```

---

## 6. 本地验证方法

### 6.1 运行测试

```bash
mvn test
```

覆盖场景（共 25 个用例）：

- `AuthenticationTest`：密钥缺失 / 无效 / 过期 / 吊销四种原因、有效 key 通过；
- `AuthorizationTest`：ADMIN/VIEWER 列表与单资源范围、越权响应与不存在响应字节级一致、
  VIEWER 写入 403、跨租户隔离；
- `RateLimitTest`：串行配额耗尽（第 6 个请求 429、额度头正确）、
  **200 线程抢 100 配额恰好 100 放行 / 100 拒绝**、小配额 40 轮并发精确、租户配额独立；
- `IdempotencyTest`：同键同体重放一致且只生效一次、同键不同体 409 且不覆盖、
  同键 16 线程并发只创建一条、首次失败也被缓存重放、幂等键租户隔离；
- `AuditTest`：成功 / 认证拒绝 / 限流拒绝 / 幂等冲突路径均有审计记录。

测试运行时的日志会打印**租户、判定依据与计数过程**，例如：

```
auth decision tenant=tenant-a keyId=key-a-viewer reason=PASS basis=registered, not revoked, not expired role=VIEWER
rate-limit tenant=tenant-a decision=ALLOW basis=usedBefore=0<limit=5 count=0->1 remaining=4 windowEnd=...
rate-limit tenant=tenant-a decision=DENY  basis=usedBefore=5>=limit=5 count=5->5 remaining=0 windowEnd=...
idempotency tenant=tenant-a key=k3 decision=FIRST basis=no slot exists hash=...
idempotency tenant=tenant-a key=k3 decision=REPLAY basis=slot completed same hash=... status=201
audit tenant=tenant-a keyId=... GET /api/orders action=REQUEST_OK status=200 detail=completed
```

想看判定过程：`mvn test -Dtest=RateLimitTest`（日志中搜索 `rate-limit tenant=`）。

### 6.2 启动服务手工验证

```bash
mvn spring-boot:run
```

另开终端（默认 8080 端口；`server.port=0` 仅测试时随机端口）：

```bash
# 有效请求
curl -i -H 'X-Api-Key: secret-tenant-a-viewer' http://localhost:8080/api/orders

# 三种不同的拒绝原因
curl -i                                                    http://localhost:8080/api/orders   # MISSING_KEY
curl -i -H 'X-Api-Key: nope'                               http://localhost:8080/api/orders   # INVALID_KEY
curl -i -H 'X-Api-Key: secret-tenant-a-expired'            http://localhost:8080/api/orders   # EXPIRED_KEY
curl -i -H 'X-Api-Key: secret-tenant-a-revoked'            http://localhost:8080/api/orders   # REVOKED_KEY

# 连续请求观察限流头（tenant-a 配额 5）
for i in $(seq 1 6); do
  curl -s -o /dev/null -D - -H 'X-Api-Key: secret-tenant-a-viewer' \
    http://localhost:8080/api/orders | grep -Ei 'HTTP/|X-RateLimit|Retry-After'
done

# 幂等写：重复提交只生效一次
for i in 1 2; do
  curl -i -X POST http://localhost:8080/api/orders \
    -H 'X-Api-Key: secret-tenant-a-admin' \
    -H 'Idempotency-Key: order-123' \
    -H 'Content-Type: application/json' \
    -d '{"name":"book","content":"desc"}'
done

# 同键不同体 → 409 冲突
curl -i -X POST http://localhost:8080/api/orders \
  -H 'X-Api-Key: secret-tenant-a-admin' \
  -H 'Idempotency-Key: order-123' \
  -H 'Content-Type: application/json' \
  -d '{"name":"different"}'
```

---

## 7. 代码结构

```
domain/       ApiKeyInfo、Principal、Order、TenantRole
store/        DataStore：内存 key/配额/资源（含种子数据）
auth/         ApiKeyAuthenticator、AuthorizationService、AuthenticationFilter
ratelimit/    RateLimitService（CAS 固定窗口）、RateLimitFilter
idempotency/  IdempotencyService（槽位三态）、IdempotencyFilter、请求体缓存包装
audit/        AuditLog、AuditEvent、AuditFilter
web/          RequestContext、统一错误响应、全局异常处理
api/          OrderController（演示资源端点）
config/       统一 Clock（测试可替换时间源）
```
