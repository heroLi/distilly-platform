# 技术基线：现行重建版

重建日期：**2026-11-11（北京时间）**；内容类型：**REBUILT_CURRENT_NOT_ORIGINAL**。保留文件名中的 `2026-10-15` 仅为兼容既有引用，**不是该日原文，也不是备份恢复**。重建时源码基线：`882dd4409882bcc2b05dd830ac09598413d3cdca`，分支 `resume/text-cost-gate-20261021`。后续提交不自动改变这里注明的证据日期。

原四文件在本工作区缺失，当前本地可达 Git 历史也没有其对应提交；未据此断言其它历史副本永久丢失。原始文本、原责任人、原排期、旧清单ID及未获证据的验收内容均无法恢复。旧247候选仍 **NOT_RECOVERED**，不能将本次重建说成已找回。两份旧草案与恢复证据不覆盖、不纳入本次提交。

导航：[路线](PROFESSIONAL_TECH_ROADMAP_2026-10-15.md) · [技术基线及来源](TECH_BASELINE_2026-10-15.md) · [Backlog](PROJECT_BACKLOG_2026-10-15.md) · [CSV](PROJECT_BACKLOG_2026-10-15.csv)

## 事实时间轴

| 时间/对象 | 可核事实 | 不能推出 |
|---|---|---|
| 2026-11-10，源码基线882dd440 | 本地默认完整入口退出0，595.446秒；Java467/467、Worker103/103、JS语法20、Node28套、22套合成E2E，diff通过 | 根因已修、真实模型质量达标、生产PG或CI已通过 |
| 同日首次入口 | 2.221秒在浏览器预检失败，未进入Java/Worker/前端/E2E；指定仓库支持的既有浏览器后才完整通过 | 隐去失败、把两次入口写成首次即成功 |
| 精确SHA远端核验 | 原独立分支正常push与Git只读核对一致 | Git访问证明Actions API已恢复 |
| Actions读取 | 11/10精确head_sha的请求返回Forbidden，未获得run数据；后续未重试 | CI运行成功、失败或当前正在运行 |
| 本轮2026-11-11 | 只重建四文档并做新的文档检查 | 新跑了467项、浏览器、模型或恢复测试 |

本地实际Java17.0.20.1、Python3.12.14、Node24.19.0、Playwright1.62.0和系统Chromium154.0.8037.57；CI定义Java17/Python3.11/Node22并安装匹配浏览器（既有记录为151.0.7922.34）。本地覆盖浏览器不等于CI匹配浏览器验收。见S04、S15、S16。

## 实际架构与数据流

| 层 | 当前实现依据 | 边界 |
|---|---|---|
| API | Spring Boot3.5.16，Java17目标；模块化单体，Controller/DTO/Service/Domain/Repository | 不拆新微服务，不新建环境或生产资源 |
| Worker | Python标准库运行时；任务领取、分阶段输出、回调/重试/租约处理 | 浏览器验收依赖另列requirements-test.txt；不是Worker依赖升级 |
| 前端 | 原生HTML/CSS/JavaScript，人物工作室与知识页面 | 当前不是新手机/PC视觉重构；长来源只做局部安全折行 |
| 开发存储 | 独立H2合成库及文件重启测试；有追加式迁移 | 不改历史迁移checksum掩盖未知schema；H2不等于生产PG验收 |
| PostgreSQL | 现有Docker/Compose指向PG17；有隔离实验旧证据 | 未做生产部署、业务库迁移或生产备份恢复 |
| 记忆权威 | SQL保存actor/人物/确认状态/来源；默认SqlMemoryProvider | 向量、Hindsight派生数据不能授予权限或成为正文权威 |

当前记忆提取：mock以明确记住指令模拟；既有真实聊天协议可提议候选，但真实生成质量未测。候选为PENDING，用户确认/纠错后才进入已确认检索。quote的逐字来源/UTF-16上限校验不证明候选body被事实支持；原始来源与用户纠错后的正文分别保留。见S05、S08、S16。

MemoryProvider返回SourceRef(memoryId,sourceId,revision)，其中当前reference是内容/来源指纹，不是单调修订。Scope(project,persona,user)派生bank仅用于路由，不接受客户端任意bank，bank不是认证。MemoryRetrieval最多12条；在provider前后复核当前权限，重读本人CONFIRMED且有效来源，核指纹再取SQL正文。只有可用性/协议错误允许本地fallback，不能吞权限/SQL错误或无匹配时越权补源。同步日志的单调revision与此内容指纹不能混淆。

上下文装配含固定人格、本轮输入、最多24条近期候选、记忆/经历/知识及省略元数据；统一保守字节预算不是精确token计数。纠错、来源变化、撤权和租约变化沿既有屏障取消或拒绝旧结果；读取发布前也有当前快照复核。已发给客户端的字节和其它端副本不承诺可撤回。见S05、S10。

## 当前真实缺口：不能被流程通过冲抵

| 集合/机制 | 既有记录 | 解释 |
|---|---|---|
| 私有记忆中文冻结30题 | 正例命中12/21，原锁保留 | 不是21道全为新测试，也非本轮复测 |
| 共享记录卡窄探针 | 0/4；和私有记忆集不同 | 保留4项语义失败，不混分母 |
| 纠错改写 | 已知例仍漏召 | 保存原记录，不假称普遍语义已解 |
| 暴露开发shadow | A8/16、B10/16；严格支持负例3/3仍返回 | 只验证单一词面组合补召；B未启用默认主链，非盲测 |
| 事实支持 | 既有5/5不受支持的离线断言可通过ID-only校验 | 语义支持判定器未实现；不是安全权限测试失效，也不是质量通过 |
| 原文参考 | 显式选择，只取当前SQL已确认正文并提示支持关系未判定 | 不混模型自由断言，不称原始聊天全文或事实蕴含证明 |

上述记录来自S06–S10；新增原生流程和467项通过不改变其真实状态。用户确认、真实来源ID、关键词相关性、提示词和mock输出均不等于事实真或回答受支持。共享环境未验证独立封存隔离；当前新题只能称开发/回归。

## 持久同步与可靠性

V012有序意图/稳定operation UUID/单调修订与V013离线消费者、投影及结构化回执已实现。确认/纠错/遗忘在SQL权威下追踪，发送不确定保留UNKNOWN；晚到旧回执不把旧正文写回。模拟器用本地同锁回执和MOCK_FENCED取消契约演示恢复，不是供应商exactly-once或真实删除证明。默认同步关闭，显式offline才跑本地模拟。见S05。

锁外检索和领取/输出复核已实现，不能继续把旧“锁内召回待拆”当当前现状。H2 V005迁移恢复有窄兼容实现与回归；不能据此自动修真实/历史未知数据库。主要源码可从[领取服务](../server/src/main/java/internal/distilly/modules/task/service/PersonaTaskService.java)、[H2恢复](../server/src/main/java/internal/distilly/infrastructure/migration/H2V005Recovery.java)核对。

第三JVM历史超时尚无根因。11/10对照重建24个父上下文后分别保留/关闭，原三进程方法10.253/10.783秒通过；未重放全部前置业务状态，也未清空H2/堆引用。最终完整入口两恢复方法均通过，顺序仍先两进程再三进程。阶段记录异步、有界且可能只有前缀；两采样任务不串行等待，仍不保证OS/JVM/IO实时性；父子时钟不同。缺线程样本不能排除锁或IO。该历史风险不能反复充作无依据复跑理由。见S14、S16。

## Hindsight与中文组件：固定的与未知的

以下仅复述10/27已保存来源，不是本轮重新拉取、读取镜像或当前容量验证（S11）：

| 项目 | 已记录值 | 未完成 |
|---|---|---|
| API镜像 | ghcr.io/vectorize-io/hindsight-api:1.1.0-slim；amd64 digest sha256:2dac29bcf29a25f71daa13e51f7c6388f680caf207ef344f22bab2d74c0f6f53 | 服务/真实链路未运行；不是可复现构建或独立签名认证 |
| 镜像源码label | a5a619b5ca59c5c39e0f96be6f7a31fabd136bd5 | 不用研究HEAD冒充实际镜像源版本 |
| 测试数据库镜像 | groonga/pgroonga:4.0.8-debian-17；digest sha256:d1fa2f19a39cea3d68957ed3c77ac749daa2d7920516a1296bb586a1d0bf9b8f | 合成词法探针已过不等于语义/Java权限/真实PG生产验收 |
| pgvector | 计划包postgresql-17-pgvector=0.8.6-1.pgdg13+1 | 包文件/hash未取得，未安装；配方不证明包已取得 |
| 两款中文模型 | BAAI/bge-m3、BAAI/bge-reranker-v2-m3 | revision、精确体积和下载文件hash未取得，未加载，不能伪造pin |
| 许可 | 196包有版本/元数据，22项所采许可字段缺失 | 需要随包LICENSE/NOTICE正常读取；不等于22项无许可，也非完整法律认证 |

旧失败：huggingface.co元数据CONNECT代理403；apt.postgresql.org宿主403、容器APT DNS失败；其它依赖闭包未取得。正常镜像缓存路径读取曾PermissionError。没有新的恢复证据则不重试、不换源、不猜CDN、不提权。模型卡声明不能代替未下载revision的核验。

容量只能按日期解释：10/27记录Docker池空闲18.24GiB，11/10可读配额4CPU/16GiB；均不是11/11装模资格复测。取得revision/文件大小/依赖闭包后必须重新核查原Docker池、CPU/RAM及保留现场的余量；不足安全停止，不买扩容或换机器。本次不重复索要已经批准的具体隔离安装许可，不执行安装。

现有Hindsight适配仍是可替换接口/离线fixture，未接真实服务。embedding、cross-encoder reranker和生成式LLM是不同组件。未获准的retain/consolidation/reflect活动关闭；滚动chunks说明不能推定固定1.1.0整链无LLM。先shadow，不直接写Hindsight内部表或替代SQL权威。

## 真实模型、费用与隐私边界

已记录唯一文字目标为DeepSeek/deepseek-flash（非永久权重pin、非当前开通证明）。真实出口由代码关闭，环境开关不能自行放行。已有合成全局预算、usage校验及价格估算不是真实可信账单。10元累计合成资料真实调用授权不扩成Hindsight新服务；历史实耗未知不能默认剩余10元。用户已后置收费，不反复审账单阻塞独立mock/设计工作。见S12。

不读取或输出秘密/真实账号状态，不聊天收密钥；不上传私人记忆、真人媒体或数据库。新增凭据/持久权限、资源费用、真实数据、邀请和部署必须分别明确。仓库公开状态不由本任务改变；本次提交仅现行重建文档，无原始日志、恢复现场、个人路径或支持私密资料。

## 已有安全演示和验收命令（本轮未执行）

现有工具链/依赖就绪后，在仓库根目录：

```sh
mvn -B -ntp -f server/pom.xml package
python3 scripts/run_synthetic_demo.py --port 18080
```

脚本显式mock、loopback、独立data/synthetic-demo H2，并向run_local传空env-file，不读取.env或继承供应商/业务库设置。可选`--memory-sync-offline`仅开启模拟同步。浏览器本机访问127.0.0.1:18080，不公开预览、不声明跨设备签收。本次不启动或改现有演示数据。

代码变更的既有统一入口：

```sh
DISTILLY_TEST_CHROMIUM=/usr/bin/chromium python3 scripts/verify.py --e2e
```

该覆盖只在现有绝对浏览器可执行路径有效时使用，是仓库既有本地规范；不是安装命令或绕过测试。CI使用其匹配浏览器。文档改动只做适用文档检查，不能把这条命令写成11/11已执行。

<a id="sources"></a>
## 证据来源登记

以下路径均按重建时基线解释。仓库链接可供复核；标注本地的忽略证据/未提交草案不会随本次提交公开。S00是当前授权摘要，不复制私密会话。

| ID | 来源与日期 | 证明范围/限制 |
|---|---|---|
| S00 | 当前可见用户授权，11/11重建四文件及既有持续推进边界 | 允许重建与合格文档提交；不解除Actions/官方源/许可拒绝，不扩费用/数据范围 |
| S01 | 基线882dd440；[pom](../server/pom.xml)、[CI定义](../.github/workflows/ci.yml)、[安全演示脚本](../scripts/run_synthetic_demo.py)、[统一入口](../scripts/verify.py) | 当前代码/命令，不证明实际CI或生产运行 |
| S02 | [总纲](PROJECT_BLUEPRINT.md)、[需求](REQUIREMENTS.md)、[进展](PROGRESS.md)、[续做记录](RESUME_TEXT_COST_GATE_2026-10-21.md) | 带日期历史和当前授权方向，较新具体证据优先；不照抄旧待办当未完成 |
| S03 | 本地未提交`docs/TECHNICAL_ROADMAP_DRAFT_2026-11-03.md`，hash dd948fbab9c88b7987d1d46328724449c9041cabc01e79a07059ef142ff0bfd2 | 仅建议/线索；未随本次提交，不当作批准阈值或原10/15原文 |
| S04 | 本地11/10 `data/resume-20261110/final-candidate-default/formal-summary.json`、`final-state.json`及`ci-blocked-receipt.json`；[已提交收口](MEMORY_SOURCE_WRAP_2026-11-04.md) | 默认467/103/20/28/22本地通过与CI拒绝分开，不能冒充本轮新运行 |
| S05 | [Provider](MEMORY_PROVIDER_CANDIDATE_2026-10-26.md)、[意图](MEMORY_SYNC_INTENT_2026-10-27.md)、[消费者](MEMORY_SYNC_CONSUMER_2026-10-27.md)、[领取隔离](CLAIM_RECALL_ISOLATION_2026-11-03.md)、[恢复](CLAIM_RECALL_RECOVERY_2026-11-03.md) | 历史文档不重写；最新代码和11/10组合验收解释旧未提交/失败状态 |
| S06 | [M0基线](MEMORY_M0_BASELINE_2026-11-03.md)、[单候选shadow](MEMORY_M0_SHADOW_2026-11-03.md) | 暴露开发比较，非生产语义晋级/独立盲测 |
| S07 | [已知失败fixture](../tests/fixtures/memory_provider_known_failures_v1.json)、[中文冻结集](../tests/fixtures/memory_provider_zh_v1.json) | 0/4共享与12/21私有集分开，不是旧247恢复 |
| S08 | [支持边界](MEMORY_SUPPORT_BOUNDARY_2026-11-03.md)、[参考模式](MEMORY_REFERENCE_MODE_2026-11-03.md)、[参考UI](MEMORY_REFERENCE_UI_2026-11-03.md) | ID-only支持缺口与原文模式保护分别记录，真实质量仍未测 |
| S09 | [评测完整性](MEMORY_EVAL_INTEGRITY_2026-11-03.md)、[校验源码](../scripts/memory_eval_integrity.py) | 完整性不证明独立性/评分正确/真实运行，独立交接未完成 |
| S10 | [读取发布复核](PRIVATE_READ_PUBLICATION_2026-11-03.md)、[多页权限](MULTI_PAGE_PRIVATE_ACCESS_2026-11-03.md)、[来源边界](MEMORY_CANDIDATE_TEXT_BOUNDARY_2026-11-04.md) | 并发检查点不是撤回已发送字节，不等于语义判定 |
| S11 | 本地10/27 `data/resume-20261027/hindsight-isolated-poc/EXPERIMENT_REPORT.md`及其固定来源/许可/失败记录 | 固定镜像与旧拒绝事实，不冒充11/11安装、许可读取或容量复测 |
| S12 | [真实文字边界](REAL_TEXT_READINESS_2026-10-21.md)、[执行预算](TEXT_EXECUTION_BUDGET_2026-10-21.md)、[出口代码](../worker/text_egress.py) | 唯一目标已有依据；估算和合成预算不是可信真实扣费，不重新索要已有额度 |
| S13 | [候选验收历史](MEMORY_CANDIDATE_ACCEPTANCE_2026-11-03.md)及本地保护哈希清单 | 恢复证据保护、不等于旧247恢复；不公开原始支持或私人资料 |
| S14 | 本地11/10 `data/resume-20261110/context-retention-ab/REPORT.md`、`diagnostic-summary.json` | 单次有限A/B不重放完整前置状态、不排除所有内存/调度/IO原因 |
| S15 | [浏览器规范](BROWSER_TEST_SETUP.md)、[依赖声明](../requirements-test.txt)、S04中Actions拒绝回执 | 本地覆盖是既有规范；没有新访问恢复证据，不重试CI |
| S16 | [长来源及11/10收口](MEMORY_SOURCE_WRAP_2026-11-04.md)、[读取服务](../server/src/main/java/internal/distilly/modules/memory/service/MemoryRetrieval.java)、[Provider接口](../server/src/main/java/internal/distilly/modules/memory/domain/MemoryProvider.java) | 已有功能和观测限制；新文档不等于重新通过这些测试 |
| S17 | [SUP-001离线设计](MEMORY_SUPPORT_CONTRACT_DESIGN_2026-11-11.md)、[设计JSON](contracts/memory_support_reference_v1.design.json)，源码基线70f20db0 | 14项设计场景/机械边界清单，不是运行时分类器、模型实验、独立评测或默认协议变更 |

## 本地原始证据指纹

原始日志、数据库、截图和支持资料不随本次提交公开；下面只保存已授权可读的本地报告指纹。路径用于原工作区复核，不承诺克隆仓库后这些忽略文件仍可取得，也不把哈希当独立真实性或法律认证。

- `data/resume-20261110/final-candidate-default/formal-summary.json`：SHA-256 `faa5121ac74ecebe7d00e5eb06127d47e4d9b11bd4d1025f36ba3a16dfc01f94`。
- `data/resume-20261110/final-candidate-default/ci-blocked-receipt.json`：SHA-256 `ae1b6287d481902197f47ee7fd0e247889e8f0cbda6bcb10776b9353b0895690`。
- `data/resume-20261110/context-retention-ab/diagnostic-summary.json`：SHA-256 `a7e54ff6bbf779a6bfd21b390fd5e4b51592f31404643358d241e590983da38d`。
- `data/resume-20261027/hindsight-isolated-poc/EXPERIMENT_REPORT.md`：SHA-256 `c55f014aa3837cc8fd1949913cefb19383abc8568e4e065b900a020a7f7e6090`。

## 2026-11-11 SUP-001设计续做

基线70f20db0的现行源码、既有5个不受支持断言和参考API快照合同已只读核对；离线设计及14项场景一致性清单形成，见S17。默认聊天和运行时支持判定均未改变，旧5/5、12/21、0/4及真实质量未测状态继续保留。本轮仅做设计/文档检查，无Colab、模型、应用或恢复测试。
