# Distilly 团队经验共享平台（第一版）

这是独立的本地共享平台项目，包含 Spring Boot API、Python Worker 和网页。只蒸馏工作经验，不自动读取会话或模拟员工性格。
需求、数据权限与验收定义见 [需求](docs/REQUIREMENTS.md)、[架构](docs/ARCHITECTURE.md)、[实施进度](docs/PROGRESS.md)。

## 本地体验

要求 Java 17+、Maven 3.9、Python 3.11+。Worker 无第三方 Python 依赖。需要安装的组件请使用公司批准的工具渠道。

从 distilly-platform 项目根目录运行：

```bash
mvn -B -ntp -f server/pom.xml package
python3 run_local.py --demo
```

如果默认 java 是 Java 8，设置 `JAVA_BIN` 指向已安装的 Java 17+ 可执行文件。
本机已验证 Maven 使用 Java 25；可用 `JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java python3 run_local.py --demo`（其他机器使用自身路径）。
打开 http://127.0.0.1:8080。令牌为 `demo-owner`、`demo-editor`、`demo-viewer`。
演示必须通过 `--demo` 显式开启，模型是可核验证据的简单模拟实现，不能用来评价实际蒸馏质量。

1. owner 创建项目，添加 editor / viewer 成员。
2. 上传 UTF-8 `.md` / `.txt`，勾选材料，创建经验主题。
3. 生成候选经验，查看每条知识的原文依据。
4. owner 批准发布；viewer 可查看、提问、下载 Work-only Skill。
5. 追加材料生成新候选；已批准历史版本可切换为当前版本。

停止：Ctrl+C，同时停止 API 与 Worker。H2 数据保存在 `data/`，不进 Git。

## 接真实模型

不带 `--demo` 启动时，必须配置 `PLATFORM_USERS`、独立 `PLATFORM_WORKER_TOKEN`、`MODEL_BASE_URL`、`MODEL_NAME`、`MODEL_API_KEY`。
格式见 `.env.example`。`run_local.py` 自动读取 `.env` 的 KEY=value 配置，也可通过 `--env-file` 指定；进程环境优先。配置文件作为数据解析，不作为脚本执行。
个人令牌与 Worker 凭证至少 32 个字符，分别生成。个人令牌在服务端只保存 SHA-256 哈希。
模型网关协议是 POST `{MODEL_BASE_URL}/chat/completions`，Bearer 密钥、messages 输入，返回 `choices[0].message.content`，其中 content 必须是 JSON 字符串。
不支持的网关需增加适配器，不会自动退化成模拟模型。默认要求 HTTPS；本地协议测试才显式启用 `MODEL_ALLOW_HTTP=true`。

### DeepSeek 配置

用户选择 DeepSeek。2026-10-03 通过官方 `/models` 实际查询本次账号可用 `deepseek-flash`、`deepseek-v4-pro`，并使用 `deepseek-flash` 通过端到端验证。其他账号以自身模型列表为准。
`.env.example` 已设置官方地址 `https://api.deepseek.com/v1`、`MODEL_NAME=deepseek-flash`、`MODEL_JSON_MODE=true`；Worker 向 `/chat/completions` 请求 `response_format={"type":"json_object"}`，同时校验 JSON 结构和原文证据。
协议参考 [DeepSeek 官方文档](https://api-docs.deepseek.com/)。公司内部 DeepSeek 网关可替换地址和模型名。
API Key 只放 Worker 环境，不能进入材料、Git 或客户端。当前按用户要求保存到本地 `.env`（权限600，Git忽略）；不得提交到版本库。

已在环境变量配置密钥后，可运行真实模型验收（会调用模型，材料为脚本内虚构内容）：

```bash
python3 tests/e2e.py --real-model
```

## 验证命令

```bash
python3 -m unittest discover -s worker -p 'test_*.py'
mvn -B -ntp -f server/pom.xml package
node tests/question_ui_test.cjs
python3 tests/e2e.py
```

e2e 需要 Java 17+（可设 JAVA_BIN）以及本机回环网络权限。它启动真实 Java API、Python Worker 与本地 HTTP 模型测试网关；验证上传、蒸馏、审核、发布、问答、下载、权限拒绝和重启持久性，退出后自动清理进程和临时数据。
HTTP 测试网关验证接口协议与工作流，不证明企业模型回答质量。DeepSeek 已用虚构材料验证真实链路；其他网关、业务质量与 SSO 需要独立验证。

## 容器试点

复制 `.env.example` 为 `.env`，配置真实独立密钥、用户及模型地址，随后从独立项目根目录执行：

```bash
docker compose --env-file .env config
docker compose --env-file .env up --build -d
docker compose logs --tail=100 api worker
```

三个服务：API、Worker、PostgreSQL；数据库不暴露宿主端口，API 默认只绑定宿主回环。内部入口通过已有 HTTPS 反向代理接入，不直接开放演示账号。
API `/health` 仅表示服务可用；Worker 完成测试任务才能验证模型链路。
企业 SSO 未接入。第一版仅使用预配置个人令牌，不能将客户端 user_id 或未验证的 HTTP 身份头当作登录身份。

## 备份与恢复

PostgreSQL 定期使用 `pg_dump` 备份整个 distilly 数据库（包括材料、候选、发布版本和审计），备份放在受权限控制的存储中。
恢复前停止 API 与 Worker，在独立空数据库运行 `pg_restore` / SQL 恢复，确认项目、版本与发布指针后再切换。
H2 本地模式停止 API 后复制 `data/`；不要在线复制正在写入的数据库文件。
不将 `.env`、访问令牌、模型密钥或备份加入 Git。

## 已知边界

第一版限制单文件 1 MiB、任务材料 200k 字符、300 条知识。材料按 6000 字符切块；问答最多选择 40 条、40k 字符的工作知识，超过范围明确说明缺口。
追加知识采用去重追加，自动冲突判断与逐条修改尚未实现；审核者对冲突候选应拒绝，并补充明确材料。
采用数据库租约和最多三次自动尝试，网络失败可重试；未实现 token 配额、复杂退避或模型成本看板。
版本内容不可变，审核和发布有事务与基础版本检查。真实模型的语义可靠性仍需负责人审核与业务评测。
配置用户列表用于启动时配置/轮换令牌；从列表移除账号会在重启后撤销其令牌，并保留历史成员与审计记录。退出演示模式也会撤销未配置的演示账号。项目成员角色可由 OWNER 在网页调整。
数据库初始化是 v0.1 建表脚本；以后升级表结构应先增加 Flyway/Liquibase 迁移，不能直接修改线上建表脚本。

本次测试证据与未验证项见 [验收记录](docs/VERIFICATION.md)。

## 后台管理更新（2026-10-03）

左侧菜单分为经验管理、材料管理、生成任务、项目设置和操作记录，右侧展示所选功能。
材料管理上传并勾选材料；生成任务选择主题、提交任务，列表每3秒刷新，支持状态筛选、失败重试和打开结果。
进度由Worker每完成一段材料向服务端上报，校验保存前最多95%，落库成功后100%；不估算模型生成中的token进度。
OWNER可删除主题，删除停止共享且保留历史版本，可从已删除主题恢复；排队/运行任务未完成时拒绝删除。原材料与审计不随主题删除。
新增profile_archive/job_progress表使用幂等建表初始化，兼容现有H2文件数据；生产环境仍需纳入正式数据库迁移管理。

项目已独立于上游，目录分工和升级流程见 [UPSTREAM_UPGRADE.md](docs/UPSTREAM_UPGRADE.md)。

当前本机真实模型启动：`JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java python3 run_local.py --port 8089`。

## 角色与任务控制

“成员与角色”展示负责人OWNER、编辑者EDITOR、阅读者VIEWER及权限，侧栏展示当前项目角色。负责人可以设置已配置账号的项目角色，不能修改自己的角色。
负责人可管理全部项目任务；编辑者/阅读者只能暂停、继续、停止、删除、恢复自己提交的任务，阅读者仅能访问自己的问答任务。
排队或执行中的任务可暂停；已暂停任务可继续（从头重新执行，尝试次数和进度重置）或停止。停止后不能继续；要再次执行需新建任务。
暂停/停止立即使后台租约失效，旧Worker的进度、完成写入都被拒绝。已发出的HTTP模型请求不保证即时取消，可能继续产生费用；单Worker需等当前请求结束或超时后才领取新任务。
已完成、失败、已停止任务可以删除，使用可恢复归档；从“已删除的历史任务”恢复只恢复列表记录，不自动执行，也不改变原状态。删除任务不删除材料、候选或发布版本。
已暂停任务仍可继续，因此不能直接删除，须先停止；其所属经验主题也不能删除。状态改变时服务端检查权限和状态，避免旧Worker结果覆盖。
