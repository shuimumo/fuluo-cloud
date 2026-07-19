# fuluogame-cloud — 福洛游戏微服务

单体 `backed/` 的微服务拆分（聚合多模块 Maven）。采用 **strangler-fig**：单体保持不动作为参照/回退，`cloud/` 逐步接管，全部迁完后再退役单体。

## 技术栈

| 组件 | 版本 |
|---|---|
| Spring Boot | 3.3.8 |
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba (Nacos) | 2023.0.3.3 |
| JDK | 17 |

注册中心 + 配置中心：**Nacos**；网关：**Spring Cloud Gateway**；服务间调用：**OpenFeign**；鉴权：**Spring Security**（网关校验 token 注入身份头，下游信任头恢复 `UserVO`）。

## 模块

| 模块 | 类型 | 端口 | 说明 |
|---|---|---|---|
| `fuluo-common` | 库 | - | Result/异常/工具/常量/MQ 契约/`UserVO`/`UserContext`/`HeaderAuthenticationFilter` |
| `fuluo-common-data` | 库 | - | 11 实体 + 11 Mapper + XML + `PostAssembler` + ID 生成 |
| `fuluo-api` | 库 | - | `UserApi` Feign 契约 + 降级工厂 |
| `fuluo-gateway` | 应用 | 8080 | 路由 + `AuthGlobalFilter`（token→身份头，Caffeine 缓存，剥离伪造头） |
| `fuluo-user` | 应用 ★ | 8081 | 用户/登录/关注/统计/收件箱（完整迁移） |
| `fuluo-post` | 应用 ★ | 8082 | 帖子/话题 + 帖子消费者（完整迁移；ES 已移出，发帖仍发 ES 索引 MQ 消息） |
| `fuluo-comment` | 应用 ★ | 8083 | 评论/回复/点赞 + 评论审核/持久化消费者 |
| `fuluo-admin` | 应用 ★ | 8084 | 横幅/公告 CRUD + 公开读(`/banners`,`/announcements`) + 文件上传(`/common/upload`) + 内容人工审核 |
| `fuluo-ai` | 应用 ★ | 8085 | AI 摘要/润色（纯 Redis 读 + DeepSeek API，零 DB/Feign） |
| `fuluo-search` | 应用 ★ | 8086 | ES 索引消费(`EsSyncConsumer`)+索引初始化+搜索读(`GET /search`) |

★ = 已完整迁移。

## 前置依赖（本地）

Nacos 8848、MySQL 3306（库 `fuluogame`，schema 见 `../src/main/resources/schema.sql`）、Redis 6379、RabbitMQ 5672、Elasticsearch 9200。连接信息可用环境变量覆盖（`NACOS_ADDR/DB_HOST/REDIS_HOST/RABBIT_HOST/ES_HOST` 等）。

## 构建与运行

```bash
cd cloud
mvn clean install -DskipTests            # 全 10 模块

# 可选：把公共配置导入 Nacos（配置中心）
#   Data ID=fuluo-common.yaml Group=DEFAULT_GROUP 格式=YAML，内容见 nacos-config/fuluo-common.yaml

# 分别启动（各模块目录下）
mvn -pl fuluo-gateway spring-boot:run
mvn -pl fuluo-user    spring-boot:run
mvn -pl fuluo-post    spring-boot:run
mvn -pl fuluo-comment spring-boot:run    # 及 admin/ai/search
```

启动后在 Nacos 控制台「服务列表」应看到 7 个服务实例。前端仍指向单一入口（网关 `http://localhost:8080`）；唯一路径变更：搜索由 `/post/search` 改为 `/search`（`fronded/src/api/index.js` 已同步）。

## 验证链路（经网关 8080）

1. `POST /user/sendCode` + `POST /user/login` → 拿 token。
2. `Authorization: Bearer <token>` `GET /post/list` → 网关注入身份头；post 放行；`PostAssembler` 经 `UserApi` **Feign** 填充作者信息。
3. `POST /post`（需登录）→ `UserContext` 从网关头恢复。
4. 停掉 `fuluo-user` 再 `GET /post/list` → `UserApi` **降级**返回无作者信息而非 500（HA）。
5. 起两个 `fuluo-post` 实例 → `PostStatsSyncTask` 仅一个实例抢到 Redisson 锁执行（调度幂等，HA）。
6. `GET /search?q=xxx` → 经网关到 `fuluo-search`：查 ES → 组装帖子 → 经 `UserApi` Feign 填作者。

## 高可用要点（拆分时加固）

- **保留**：两级缓存（Caffeine+Redis）、`CacheClient` Redisson 互斥锁、空值缓存防穿透、`PostBloomFilter`、`RTopic` 跨实例失效广播。
- **调度幂等**：`UserStatsSyncTask`/`PostStatsSyncTask` 用 Redisson 分布式锁抢主，多实例不重复执行。
- **Feign 降级**：`UserApi` 配 `fallbackFactory` + 连接/读超时（1s/2s）+ resilience4j 断路器。
- **网关鉴权缓存**：token→user 30s Caffeine 本地缓存削 Redis 峰值；剥离客户端伪造的 `X-User-*` 头。

## 关键设计说明

- **共享库**：所有服务连同一 MySQL/Redis/RabbitMQ。实体+Mapper 抽到 `fuluo-common-data` 复用；跨域**同步读**走 Feign（`post → user` 的作者填充为示范）。
- **包名保持** `com.shuimumu.fuluogame.*` 不变，多数文件平移即可复用（同类路径经 classpath 解析）。
- **RabbitMQ 拓扑**：队列/绑定声明幂等，`fuluo-common` 的 `RabbitMQConfig` 在各服务声明全部队列（无害），但 `@RabbitListener` 消费者只存在于其归属服务。
- **审核扇出保留 Canal CDC**：帖子/评论的审核通过和删帖先落库，再由 Canal 监听 `fuluogame.post/comment` 的 binlog 变更，分别扇出缓存、ES 和收件箱消息。审核消费者只负责修改状态，避免 DB 成功而 MQ 扇出失败时的双写不一致。

### Canal（WSL/Docker）

`cloud/canal-compose.yml` 固化了 1.1.7 SNAPSHOT RabbitMQ 模式所需的配置。这个版本必须显式配置 `rabbitmq.queue` 和 `rabbitmq.routingKey`，否则 RabbitMQ 客户端会在 `queueDeclare(null, ...)` 处抛出 `validateQueueNameLength` NPE；它还把名为 `deliveryMode` 的值当作 exchange type 使用，因此这里必须填 `topic`。

在 WSL 中运行：

```bash
docker network inspect fuluogame >/dev/null 2>&1 || docker network create fuluogame
export CANAL_DB_PASSWORD='<the canal MySQL user password>'
cd /path/to/backed/cloud
docker rm -f canal-server 2>/dev/null || true
docker compose -f canal-compose.yml up -d
docker compose -f canal-compose.yml ps
docker compose -f canal-compose.yml logs -f canal-server
```

RabbitMQ、MySQL 和 Canal 必须加入同一个 `fuluogame` Docker network，并使用 `rabbitmq`、`mysql8` 作为容器别名。启动脚本会等待两个依赖就绪；如果 Canal Java 进程退出，容器会退出并由 Docker 自动重试，而不会停留在“容器 Up、CDC 已失效”的假运行状态。

## 后续待办

- `user→post/comment`、`admin→各域` 反向读全量转 Feign（`PostApi`/`CommentApi`）
- 网关聚合 Knife4j 文档；引入 Sentinel 限流熔断
- 退役单体 `backed/`（`src` + 根 `pom.xml`）
