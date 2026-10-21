# Distilly 虚拟人生成平台

2026-10-21：本分支从现存旧Git基线续做，不代表找回旧247项。真实文字供应商出口因费用不可核验保持关闭，mock与显式本机合成fixture仍可验证；范围、命令及未恢复能力见[续做记录](docs/RESUME_TEXT_COST_GATE_2026-10-21.md)。

真实文字接入已核对历史 DeepSeek / `deepseek-flash` 目标；[接入准备与准确门槛](docs/REAL_TEXT_READINESS_2026-10-21.md)区分公开用量估算、真实账单、全局预算和安全凭据配置，当前不发起真实调用。 [全局合成执行预算](docs/TEXT_EXECUTION_BUDGET_2026-10-21.md)已关联实际 Worker 文字阶段和重试／未知占款，真实出口仍关闭。

当前开发入口：[实施总纲](docs/PROJECT_BLUEPRINT.md) · [统一设计与交互](docs/DESIGN_SYSTEM.md) · [2026-10-08 审查](docs/PROJECT_REVIEW_2026-10-08.md)。

[Hindsight 与 VoiceStudio 接入方案](docs/HINDSIGHT_VOICESTUDIO_PLAN.md)（2026-10-10）设计长期关系记忆检索、人物专属声音及统一语音回复；当前为待实现方案，含固定上游版本、接口差异、许可记录与验收门槛。

基于 Distilly `dot-skill` 的 Persona 分析与生成流程，创建具有稳定人格、情感表达、持续对话和用户关系记忆的虚拟人。支持朋友式陪伴、原创角色、授权材料人物和工作伙伴。

同级 `distilly-platform-jd` 面向共享工作知识库和工作 Skill；本项目以虚拟人的创建、调试、发布和交流为核心。同平台的「虚拟经历台」位于 `/knowledge.html`，与人物工作室共用登录和当前工作空间；历史经验主题在界面改称「知识专题」，原数据与API保留。

## 当前可用功能

- 人物模板与编辑；`relationship`、`celebrity`、`colleague` 三类上游提示词，两阶段分析和生成。
- 授权材料选取、来源短片段校验、原创设定与推断区分；候选预览、人工纠正、审核发布、不可变版本与切换。
- 私有多轮交流、固定人格版本、相关确认记忆与经历来源筛选、统一保守文本预算、情感状态及变化原因；模型 SSE 回复增量，网页每两秒获取最新内容。
- 待确认关系记忆、确认、纠正、不采用、删除和关闭；按人物及用户隔离。记忆变化会停止仍在使用旧记忆的回应。
- 浏览器声音选择、朗读、语气语速调整、打断与麦克风输入；可配置模型语音合成及音频文件转写。
- 上传人物形象、配置模型生成形象、图片交流；形象随情感及朗读进行轻量动画。
- 同工作空间共享已发布知识，每个人物配置可读知识专题，交流按问题检索并显示专题与版本来源；不把知识写成亲身经历。
- 工作空间与成员角色、任务记录、失败重试、停止、人物归档恢复、已发布 Persona Skill 导出。
- H2 / PostgreSQL 持久性、独立 Worker 凭证、租约恢复、幂等完成、追加式数据库迁移、容器配置与 CI。

“有人的感情”表现为人格、情绪表达和关系上下文，平台不宣称虚拟人具有真实主观感受。当前轻量动画不包含三维建模、真人级视频驱动或模型生成的精确口型。

## 后端代码结构

Spring Boot 按工作空间、材料、知识、人物、人格资产、交流、记忆、媒体和任务划分模块，模块内采用 Controller / DTO / Service / Domain / Repository 分层。结构、依赖和开发规则见 [Spring Boot 代码结构](docs/SPRING_BOOT_STRUCTURE.md)。

## 启动

本项目本地启动与预览统一固定为 **18080**：人物工作室 `http://127.0.0.1:18080/`，虚拟经历台 `http://127.0.0.1:18080/knowledge.html`。`run_local.py` 默认使用此端口，Docker 也映射到同一地址；端口被占用时排查占用，不自动换端口。端到端测试使用固定独立端口：虚拟人18082/18083、知识18084/18085。

需要 Java 17+、Maven、Python 3.11+。Worker 使用标准库。

```bash
mvn -B -ntp -f server/pom.xml package
python3 run_local.py --demo --port 18080
```

打开 http://127.0.0.1:18080。演示账号令牌为 `demo-owner`、`demo-editor`、`demo-viewer`；默认演示模型仅验证流程。

已有真实文本模型 `.env` 配置时，允许本机演示账号使用真实模型：

```bash
JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java python3 run_local.py --demo --demo-model gateway --port 18080
```

其他机器用自己的 Java 17+ 路径。正式个人令牌模式不带 `--demo`：

```bash
python3 run_local.py --port 18080
```

启动器将构建 JAR 复制到临时运行目录，避免后续构建覆盖正在加载的文件；停止后清理副本。启动器读取 `.env` 的 KEY=value，进程环境优先，不执行配置内容。正式模式需要 `PLATFORM_USERS`、独立 `PLATFORM_WORKER_TOKEN` 和文本模型配置；演示仅监听本机，不作为企业登录机制。

## 从创建到交流

1. 登录并新建工作空间；负责人可为已配置账号添加成员。
2. 创建虚拟人，选择模板、人物类型，填写背景、性格、表达、关系和边界。
3. 进入虚拟经历台的「人格生成」，创建独立人格设定；材料在「材料管理」上传 UTF-8 `.md` / `.txt`，确认建模授权后可选择多份材料（最多20份、共60000字符），也可直接按原创设定生成。
4. 查看人格、分析和材料依据，可编辑为新候选；负责人审核发布后选择虚拟人绑定，也可回人物工作室选择已发布人格绑定。
5. 到虚拟经历台发布知识专题，在人物工作室勾选人物可读的专题并保存；切换模块无需重复登录。
6. 一个人格可以绑定多个虚拟人；绑定固定来源版本，后续人格发布不自动更换绑定。已有交流固定原版本，新交流使用新绑定。
7. 在交流界面发送文字或图片，确认值得保留的候选记忆；到声音与形象设置浏览器或模型媒体能力。

记忆删除移除长期检索项，既有聊天消息仍保留；删除本次交流会移除消息和源自这段交流的记忆。备份中的历史副本须依备份保留策略清理。

## 模型与媒体配置

完整字段见 [.env.example](.env.example)，接口与请求格式见 [媒体接入](docs/MEDIA_GATEWAYS.md)。密钥仅在本地 `.env` 或服务端环境，不进入浏览器、Git、材料或日志。

| 能力 | 配置 | 未配置行为 |
|---|---|---|
| 人格 / 文字交流 | MODEL_BASE_URL、MODEL_NAME、MODEL_API_KEY | 正式模式启动拒绝；显式 mock 演示可验证流程 |
| 形象生成 | IMAGE_BASE_URL、IMAGE_MODEL、IMAGE_API_KEY | 任务明确失败；本地上传仍可使用 |
| 语音合成 / 转写 | SPEECH_BASE_URL、SPEECH_API_KEY、TTS_MODEL / ASR_MODEL | 模型语音任务明确失败；浏览器语音仍可使用 |
| 图片理解 | VISION_BASE_URL、VISION_MODEL、VISION_API_KEY | 带图片交流明确失败，不悄悄忽略图片 |

文本网关支持 Chat Completions + JSON；不兼容 `stream=true` 时设 `MODEL_STREAMING=false`。浏览器语音依其支持和权限，部分识别服务会向浏览器供应商传输音频，仅用户点击授权后开始。

## 验证

```bash
mvn -B -ntp -f server/pom.xml package
python3 -m unittest discover -s worker -p 'test_*.py'
node --check server/src/main/resources/static/persona.js
node tests/question_ui_test.cjs
node tests/persona_login_test.cjs
node tests/platform_session_test.cjs
JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java python3 tests/persona_e2e.py
JAVA_BIN=/opt/homebrew/opt/openjdk/bin/java python3 tests/e2e.py
```

`persona_e2e.py` 默认使用真实 Java API、Python Worker 和 HTTP 测试网关，覆盖三类人物、SSE、记忆隐私、图像 / 视觉 / 语音协议、发布、重启与删除；不会使用个人材料。
设置文本模型环境变量后，`python3 tests/persona_e2e.py --real-model` 使用虚构人物验证真实文本模型；媒体依然使用测试网关，不能据此声称真实媒体质量已验收。

已完成本地验证，具体证据与限制见 [虚拟人验收](docs/PERSONA_VERIFICATION.md)。真实媒体供应商、麦克风识别效果、音质及形象质量尚待实际配置验证。企业 SSO、配额、计费、生产规模、多实例迁移和三维 / 视频数字人未实现。

## 部署与升级

复制 `.env.example` 为 `.env`，配置真实独立凭证与模型，再执行：

```bash
docker compose --env-file .env config --quiet
docker compose --env-file .env up --build -d
```

API、Worker、PostgreSQL 三服务；仅 API 暴露宿主回环端口，正式入口使用已有 HTTPS 代理。当前环境未运行 Docker / PostgreSQL 验收。
H2 停止服务后备份 `data/`；PostgreSQL 使用 `pg_dump`。首次应用人物迁移前备份，单 API 执行迁移后再扩容，不修改已应用的 `migrations/V*.sql`。恢复时用独立空库检查版本和人物发布指针。

[需求](docs/REQUIREMENTS.md) · [架构](docs/ARCHITECTURE.md) · [进度](docs/PROGRESS.md) · [上游升级](docs/UPSTREAM_UPGRADE.md) · [历史工作知识验收](docs/VERIFICATION.md)

[工作空间与知识互通架构](docs/WORKSPACE_KNOWLEDGE_ARCHITECTURE.md)说明人物专题绑定、权限、检索、来源和登录共享。

[蒸馏知识全流程复核与持续优化方案](docs/KNOWLEDGE_DISTILLATION_REVIEW_2026-10-09.md)记录上传、任务、候选、审核、发布的现场检查及后续验收门槛。

[文字人格与相关经历稳定性（2026-10-12）](docs/TEXT_CONTEXT_VERIFICATION_2026-10-12.md)记录本轮实现、上下文配置及合成验证；真实人物说话方式和长期体验仍需人工评估。

任务准入默认每用户32个未结束任务、每用户60次/分钟、单API进程总计120次/分钟；可分别配置PLATFORM_MAX_ACTIVE_TASKS_PER_USER、PLATFORM_TASK_SUBMISSIONS_PER_MINUTE、PLATFORM_TOTAL_TASK_SUBMISSIONS_PER_MINUTE，0关闭对应限制。暂停占用名额，取消不退已提交频率，人工重试/恢复仍受原创建者额度约束。窗口随API重启清零，不能视为持久用量或人民币费用上限。详情见[任务准入限制](docs/TASK_ADMISSION_LIMITS_2026-10-12.md)。

文字模型各阶段有显式输出token上限，参数可选max_tokens/max_completion_tokens、temperature可省略；截断为MODEL_OUTPUT_LIMIT，不生成半截候选或版本。旧蒸馏断点指纹需与新生成设置一致，详见[输出预算与协议](docs/MODEL_OUTPUT_BOUNDS_2026-10-12.md)。
