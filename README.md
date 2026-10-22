# 福洛社区 · 微服务（主线仓库）

> 本仓库是**功能迭代主线**。单体版本单独维护在 `monolith` 仓库，冻结在功能对等版本，只接受安全与紧急修复。

## 模块

| 模块 | 端口 | 职责 |
|------|------|------|
| `fuluo-gateway` | 8080 | 网关：路由、鉴权、限流 |
| `fuluo-user` | 8081 | 用户、登录、关注关系 |
| `fuluo-post` | 8082 | 帖子、话题、点赞、收件箱 |
| `fuluo-comment` | 8083 | 评论、评论点赞 |
| `fuluo-admin` | 8084 | 后台：轮播/公告/话题/审核 |
| `fuluo-ai` | 8085 | AI 总结与润色 |
| `fuluo-search` | 8086 | Elasticsearch 搜索 |
| `fuluo-notification` | 8087 | 站内通知（互动/系统） |
| `fuluo-common` | — | 通用组件：Redis、MQ、缓存、Lua |
| `fuluo-common-data` | — | 实体、Mapper |
| `fuluo-common-model` | — | DTO / VO / 事件模型 |
| `fuluo-api` | — | 服务间 Feign 接口 |

## 构建与运行

```bash
mvn -T 1C clean package -DskipTests
# 按依赖顺序启动：user → post/comment → notification/search/ai → admin → gateway
```

## 本地依赖

MySQL 8、Redis（Redisson）、RabbitMQ、Elasticsearch 8.x、Nacos（见 `nacos-config/`）。
`docker-compose.yml` 提供中间件编排，`rabbitmq.conf` 为 broker 配置。

## 配置与密钥

各服务 `src/main/resources/application.yaml` 只放非敏感默认值；
敏感项走环境变量或 Nacos 公共配置，模板见 `.env.example`（真实 `.env` 已被忽略）。

## 文档

- 路线图（V2.0 前）：[`docs/roadmap.md`](docs/roadmap.md)
- 压测报告：`LOADTEST_REPORT.md`
