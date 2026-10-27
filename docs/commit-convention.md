# 提交信息规范

采用 Conventional Commits 的简化变体：`<type>(<scope>): <描述>`，描述用中文。

## 格式

```
<type>(<scope>): <描述>

<可选正文：动机 / 方案 / 影响面，每行 ≤ 72 字>

<可选尾注：BREAKING CHANGE、Closes #12>
```

- `type` 必填、小写；`scope` 可选、小写。
- 描述用祈使句，不加句号，建议 ≤ 50 字。
- 一次提交只做一件事；同一次提交不要混入无关的格式化或重构。

## type 取值

| type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `perf` | 性能优化（不改变接口语义） |
| `refactor` | 重构（不改变外部行为） |
| `docs` | 文档、注释 |
| `style` | 格式调整（不影响逻辑） |
| `test` | 测试 |
| `build` | 构建脚本、依赖 |
| `ci` | CI/CD 配置 |
| `chore` | 杂项（脚手架、脚本、配置） |

## scope 约定

取业务域或模块名，小写：

```
post / comment / user / notification / search / admin / gateway / ai
common / mq / cache / inbox / sync / cdc / stats / auth / web
persistence / read / deploy / config
```

跨多个模块时省略 scope，或在正文里说明。

## 示例

```
feat(post): 新增收藏列表接口
fix(mq): 毒消息进入死信后不再自动重投
perf(comment): 评论列表点赞状态 pipeline 化
refactor(common): 抽取 fuluo-common-model 纯数据模块
docs: 补 README 与 V2.0 前路线图
chore(deploy): 补 docker-compose 编排
```

## 校验

仓库已启用提交信息校验：

```bash
git config core.hooksPath .githooks   # 已配置，换机器后需重配
```

`.githooks/commit-msg` 会在 `git commit` 时校验首行，不符合规范则提交失败。
紧急情况可 `git commit --no-verify` 跳过（请事后说明原因）。

## 其他约定

- 多行信息：`git commit -m "标题" -m "正文"`，或 `git commit` 打开编辑器。
- 破坏性变更：在正文写 `BREAKING CHANGE: 说明`，并在 type 后加 `!`（如 `feat(api)!: ...`）。
- 关联 issue：尾部写 `Closes #12`。