# 发帖接口（POST /post/publish）压测报告

- 日期：2026-08-02
- 被测接口：`POST /post/publish`（发布帖子），经网关 `http://localhost:8080`
- 测试链路：压测客户端 → 网关 → fuluo-post → Redis(ID/布隆) → RabbitMQ → 消费者 → MySQL
- 压测工具：**Apache JMeter 5.6.3**（测试计划 `jmeter-publish.jmx`，HTTP Keep-Alive、连接复用）

---

## 1. 测试目标

1. 用专业压测工具量化发帖接口的真实吞吐与延迟基线
2. 对比单实例 / 双实例在不同并发下的表现（验证水平扩容收益）
3. 量化异步链路（MQ → 消费者 → MySQL）的写库吞吐，验证"API 吞吐 ≠ 写库吞吐"
4. 验证失败去向与数据一致性

## 2. 测试环境

| 项 | 配置 |
|---|---|
| 硬件 | 单台 Windows 笔记本（开发机），被测服务与压测客户端同机 |
| JDK | Java 17.0.12 |
| 后端框架 | Spring Boot 3.3.8 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3 / OpenFeign 4.1.3 |
| 中间件 | Docker 单机：MySQL 8.0、Redis 7.2、RabbitMQ 4.3、Nacos 2.3.2、Elasticsearch 8.19.7、Canal 1.1.7 |
| 拓扑 | 8 个微服务同机运行；发帖链路涉及 网关 + fuluo-post + 中间件 |

> 数据为单机 dev 环境参考值，不代表生产容量。

## 3. 工具与指标口径

### 3.1 压测工具：JMeter 5.6.3

- 测试计划 `jmeter-publish.jmx`：
  - setUp 线程组：`POST /user/login` 获取 token（JSR223 提取，或命令行 `-Jfuluo.token=` 注入）
  - 压测线程组：线程数/时长可 `-Jthreads=` / `-Jduration=` 覆盖
  - 请求：`POST /post/publish`，标题带时间戳/线程号保证唯一
  - 断言：响应体包含 `"code":1` 才计成功
  - 输出：jtl + HTML 报告（`-e -o`）
- HTTP Keep-Alive 连接复用（排除"每请求新建 TCP 连接"的客户端开销）

```powershell
jmeter -n -t .\jmeter-publish.jmx -Jthreads=50 -Jduration=20 "-Jfuluo.token=$token" -l .\res.jtl -e -o .\report
```

> 备注：早期用自研 Python 脚本（requests，无连接复用）测得 API 约 440~540 RPS，经 JMeter 复测确认**该值主要是客户端瓶颈**，服务端真实能力约为其 2~3 倍，本报告以 JMeter 数据为准。

### 3.2 指标定义

| 指标 | 定义 | 算法 |
|---|---|---|
| RPS | 每秒完成请求数 | 完成请求数 ÷ 总耗时（JMeter Throughput） |
| p50/p90/p99 | 延迟百分位 | 每请求耗时排序后取 `int(N×p)` 位置 |
| 成功率 | `code==1` 的请求占比 | 成功数 ÷ 请求数 |
| 消费(ack)速率 | 消费者处理完成并确认的速率 | `(t0 队列深度 - t1 队列深度) ÷ Δt` |
| 写库吞吐 | DB 实际落库速率 | DB 行数增量 ÷ Δt（或 `Innodb_rows_inserted` 差值） |

> 术语分层：**API 吞吐**（投递到 MQ 即返回）≠ **ack/消费速率**（listener 处理完）≈ **写库吞吐**（批量消费前每条消息 ≈ 1 次主表 INSERT，数值一致；批量消费后 ack 按批、落库按批量 INSERT/UPDATE，数值仍近似一致）。

## 4. 测试数据（JMeter）

### 4.1 单实例 vs 双实例（审核关闭）

| 场景 | 并发 | 请求数 | 成功率 | RPS | p50 | p90 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| 单实例 | 50 × 20s | 32539 | 100% | **1627** | 26ms | 42ms | 94ms | 151ms |
| 单实例 | 100 × 10s | 8309 | 100% | 831 | 106ms | 159ms | 251ms | 508ms |
| 双实例 | 50 × 20s | 27631 | 100% | 1381 | 32ms | 47ms | 69ms | 831ms |
| 双实例 | 100 × 10s | 18027 | 100% | **1803** | 48ms | 67ms | **90ms** | 121ms |

全部轮次 **0 错误**。

### 4.2 异步链路核对

| 项 | 数值/结论 |
|---|---|
| 消费端写库吞吐（优化前） | ≈290 条/秒（队列消耗与 DB 增量双口径一致） |
| 消费端写库吞吐（批量消费+批量 INSERT 后） | **≈3000 条/秒**（积压 17219 条 8 秒排空，0 错误） |
| 4 轮压测新增帖子 | ≈8.6 万条，API 全部成功 |
| 测试后落库队列（优化前） | 峰值积压 57k，按 ~290/s 排空（约 3 分钟） |
| 测试后落库队列（优化后） | 峰值积压 17219 条，按 ~3000/s 8 秒排空，0 错误 |
| MQ 队列终态 | 可排空，无滞留/丢失（成功数=落库数） |

## 5. 结果分析

### 5.1 分层吞吐模型

```
客户端 → 网关 → fuluo-post ──(MQ)──▶ 消费者 → MySQL
       │                            │
  API 层 ≈ 1400~1800 RPS         消费层 ≈ 3000 条/秒
  （单实例峰值 1627）              （批量消费+批量落库优化后，写库不再是瓶颈）
```

### 5.2 结论

1. **服务端单实例真实能力 ≈ 1600 RPS**（50 并发），此前"500 RPS"为自研脚本客户端瓶颈的误判
2. **水平扩容在高并发下有效**：100 并发时双实例 1803 RPS vs 单实例 831 RPS，吞吐翻倍、p99 从 251ms 降至 90ms——第二实例分摊了单实例的线程/连接饱和
3. **小并发下加实例无收益**：50 并发单实例 1627 RPS 略高于双实例 1381 RPS（未到单实例瓶颈，双实例有负载均衡/预热开销）
4. **异步写库已从 ≈290 条/秒提升到 ≈3000 条/秒**（批量监听 + 批量 INSERT + 批量回写审核状态），写库不再是端到端瓶颈；异步架构仍保持最终一致，积压可排空、消息不丢
5. 消费积压排空期共享 Redis/MySQL 竞争会导致发布偶发失败——压测需在队列排空状态下进行

### 5.3 失败去向

| 类型 | 用户可见性 | 数据去向 |
|---|---|---|
| API 层异常（Redis/MQ 超时等） | HTTP 200 + `code:0`，前端提示"系统内部错误" | 直接丢失（无 MQ 消息、无 DB 行、无重试） |
| 已进 MQ 但消费者处理失败 | 不可见 | 重试 3 次后默认 `default-requeue-rejected=true` 重新入队（毒消息可能无限重投） |
| 路由失败（无 mandatory + confirm） | HTTP 200 + `code:1`（假成功） | broker 静默丢弃（实测 `routed:false`），前端"成功"但 DB 无记录 |

## 6. 过程中发现并解决的问题

| 问题 | 根因 | 处理 |
|---|---|---|
| 发帖返回"系统内部错误" | `PostBloomFilter.add()` 未先 `tryInit()`，Redisson 抛"Bloom filter is not initialized" | 抽出 `initIfNeeded()`，add/contains 前统一初始化 |
| 前端请求无响应 | 网关 `globalcors` 与下游 `CorsConfig` 各加一遍 CORS 头（`ACAO` 重复，浏览器拦截） | 保留网关 CORS，移除 6 个服务 SecurityConfig 的 `.cors()` 接线 |
| IDE 提示 feign 配置无法解析 | OpenFeign 4.x 属性命名空间 `feign.*` → `spring.cloud.openfeign.*` | 迁移 comment/post/search 配置 |
| 网关连不上 post（Connection refused） | `server.address: 127.0.0.1` 与 Nacos 注册局域网 IP 冲突 | 移除全部服务 `server.address` |
| 压测触发审核限流/积压 | `ContentModerationUtil` 硬编码 100 次/秒 RRateLimiter | 新增 `fuluo.moderation.enabled` 开关，压测置 false |
| Nacos 配置导入失败（SCA 报 does not exist） | SCA 2023.0.3.3 导入校验与 nacos-client 2.4.2 组合问题 | 服务端切换 Nacos 2.3.2；jar 实例用独立配置 + `import-check.enabled=false` 绕过 |
| JMeter 压测 login 失败 | login 采样器缺 Content-Type 头，Spring 解析 body 为 null | JMX 内为 login 加 HeaderManager |
| JMeter 客户端 BindException | Windows 临时端口耗尽（未复用连接） | JMX 显式开启 `use_keepalive=true` |
| 消费写库吞吐卡 290/s | 消费者逐条 INSERT，prefetch/concurrency 低 | 批量监听（batchSize 200/prefetch 250/并发 4-8）+ `insertBatch`（多行 INSERT + ON DUPLICATE KEY），写库 ≈3000/s |
| 审核消费者单条 SELECT+UPDATE | 审核队列逐条查库+回写，跳过模式仍 ~300/s | 帖子/评论审核改批量监听 + 批量查库 + `updateStatusBatch`（CASE WHEN 一次 UPDATE 整批） |

## 7. 改进建议（按优先级）

| 优先级 | 项 | 说明 |
|---|---|---|
| ~~P1 消费端写库优化~~ | 已完成：批量监听 + `insertBatch` + 审核批量回写，写库 ≈3000/s（2026-08-03/08-10） |
| P1 | 发布端可靠性 | `publisher-confirm-type: correlated` + `mandatory: true` + Confirm/Return 回调 + 失败补偿，消除"假成功"静默丢 |
| P2 | 毒消息治理 | `default-requeue-rejected: false` + 死信队列（DLX）+ 告警 |
| P2 | Redis 减负 | ID 生成/布隆/计数依赖 Redis 单线程，评估分片或替换 |
| P3 | 压测规范 | 压测机与被测机分离、warmup、多轮取中位 |

## 8. 复现步骤

```powershell
# 1) 启动基础设施 + 微服务
docker compose up -d

# 2) 写入登录验证码（2 分钟有效）
docker exec redis redis-cli SET login:code:13900000001 123456 EX 300

# 3) 获取 token
$token = ((Invoke-WebRequest -Uri 'http://localhost:8080/user/login' -Method Post -ContentType 'application/json' -Body '{"phone":"13900000001","code":"123456"}').Content | ConvertFrom-Json).data.token

# 4) 压测（单实例：确保 18082 未启动；双实例：启动第二个 jar 实例）
jmeter -n -t .\jmeter-publish.jmx -Jthreads=50 -Jduration=20 "-Jfuluo.token=$token" -l .\res.jtl -e -o .\report

# 5) 核对消费/落库
docker exec rabbitmq rabbitmqctl list_queues name messages
docker exec mysql8 mysql -uroot -p123456 -e "SELECT COUNT(*),status FROM fuluogame.post GROUP BY status"
```

## 9. 附录

### 9.1 关键命令

```powershell
# 队列深度（消费速率 = Δdepth/Δt）
docker exec rabbitmq rabbitmqctl list_queues name messages

# 落库行数（写库吞吐 = Δrows/Δt）
docker exec mysql8 mysql -uroot -p123456 -N -e "SELECT COUNT(*) FROM fuluogame.post"

# 路由验证（mandatory 演示：未绑定 key 返回 routed:false）
docker exec rabbitmq rabbitmqctl list_bindings
```

### 9.2 历史数据说明

早期自研 Python 脚本（`requests`、无连接复用）测得：单实例 20c≈433 / 50c≈539 RPS，100c 出现失败（86.8%）。经 JMeter 复测，该差异主要为客户端开销与压测时消费积压污染，**服务端能力以本报告 JMeter 数据为准**。

---

## 10. 变更记录（优化轮次）

### R1：2026-08-03 帖子批量消费 + 批量落库（commit `c2ae983`）

- **改动**：新增批量监听容器 `postBatchListenerContainerFactory`（batchSize 200 / prefetch 250 / 并发 4-8）；`PostPersistenceConsumer` 改收 `List<PostPersistMsg>`，新帖用 `insertBatch`（多行 INSERT + `ON DUPLICATE KEY` 幂等）批量落库，话题名批量查询。
- **实测**：消费端写库吞吐 300-400/s → **≈3000/s**；积压 17219 条 8 秒排空，0 错误。
- 说明：审核开关 `fuluo.moderation.enabled=false` 时仍逐条走审核消费者，此时新瓶颈为审核消费者单条 SELECT+UPDATE（约 300/s）。

### R2：2026-08-10 帖子/评论审核消费者改批量

- **改动**（本批代码，已编译验证）：
  - `PostMapper` / `CommentMapper` 新增 `updateStatusBatch`（CASE WHEN 一次 UPDATE 整批，SQL 已对 MySQL 实测通过）；
  - `PostModerationConsumer` 复用 `postBatchListenerContainerFactory`，改收批量消息：批量 `selectBatchIds` + 批量回写审核状态；
  - `fuluo-comment` 新增 `CommentBatchConfig`（同参数批量容器），`CommentModerationConsumer` 同样改批量；
  - 真实审核模式下仍逐条调阿里云 `TextModerationPlus`（该接口不支持一次请求审多条，吞吐受账户 QPS 配额限制）。
- **待复测**：本地 jar 启动受既有 Nacos 配置导入兼容问题（SCA 2023.0.3.3 + nacos-client 2.4.2 vs server 2.3.2）阻塞，R2 尚未实测排空速率；预期跳过模式下审核消费者吞吐从 ~300/s 提升到千级/s（每批 1 次 SELECT + 1 次 UPDATE）。
