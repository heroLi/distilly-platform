# 长期记忆 Provider 边界与中文基线

日期：2026-10-26。原工作区和 `resume/text-cost-gate-20261021`，本地与远端起点均为 `7b4f575454392e268b5b5d5af48f990c62da8c28`，工作树干净。按用户最新优先级只推进长期记忆，保留声音实现，不新增声音功能、视觉或部署。原指定 10/15 路线图、技术基线、backlog MD/CSV 仍缺失，不编造原计划或恢复成果。

2026-10-27 后续已补本地持久意图、顺序阻挡与离线恢复验证，见 [后续记录](MEMORY_SYNC_INTENT_2026-10-27.md)。真实消费者、终结回执核验及远端删除仍未接入；以下保留 10/26 时点的能力与评测。

## 本批实际实现

- `MemoryProvider` 是来源候选接口，运行时默认实现仍为 SQL 词法检索。原流式扫描、top12、覆盖门槛、排序与概览识别原样移到 `SqlMemoryProvider`；未增加同义词、翻译或测试题特例。
- Java 从当前人物真实项目、当前用户构造 Scope，服务端生成 bank 路由标识，不提供客户端选择 bank/provider 的入口。bank 标识是路由，不是凭据或权限证明。
- Provider 仅返回 memory ID、原消息 ID、当前状态指纹。`MemoryRetrieval` 重新检查当前成员权限，再按人物、用户、确认状态和消息/会话归属读取 SQL。指纹或来源不符、已拒绝、已遗忘、删除来源的候选不进入上下文；返回的是 SQL 原文，不是供应商 fact、chunk、推断或 reflect 文本。
- 只有明确的 Provider 不可用/协议错误触发 SQL 回退，真实权限和数据库/程序异常不吞掉。正常无匹配不自动制造命中，仍返回空结果。默认运行路径没有外部网络。
- `HindsightMemoryProvider` 是离线候选适配器，**没有 Spring bean、网络实现、安装依赖、服务地址或凭据配置**。它构造 retain/recall/delete_document 的参数描述并解析协议 fixture；无参构造只报不可用。没有启用索引同步、外部 Worker 或外部数据库。

SQL 仍是唯一业务权威，现有候选提取→用户确认→跨会话召回→纠错/拒绝/遗忘、来源会话删除、任务取消及最终完成屏障保留。`ContextAssembler` 和 Python Worker 本轮不变。真实提取和回答质量仍未测。

## 官方契约核对与采用范围

只读核对官方 [GitHub 仓库](https://github.com/vectorize-io/hindsight)、[retain](https://hindsight.vectorize.io/developer/api/retain)、[recall](https://hindsight.vectorize.io/developer/api/recall)、[documents](https://hindsight.vectorize.io/developer/api/documents)、[extensions](https://hindsight.vectorize.io/developer/extensions) 和 [中文/多语言配置](https://hindsight.vectorize.io/developer/multilingual)。这是文档时点核对，不是固定版本服务的实测。

采用的协议：同一记忆稳定 document_id，纠错使用 replace；字符串 metadata 保存本地来源及状态指纹，recall 只接受能回溯的 world/experience 候选。异步 retain 必须由调用方先持久化 operation_id、同一操作重试沿用，新修订用新操作；描述不提供 reflect。删除参数单独构造，不假定“本地删除”已获得远端清理回执。官方文档说明 metadata 随 recalled fact 返回；本地仍完全不信任它来授权或提供正文。

本批 Provider 指纹包含记忆 ID、source_id 和正文，与页面纠错的 expected_revision 是不同用途，须通过 `MemoryProvider.reference` 构造。它是 SHA-256 当前状态标识，**不是单调修订号或外部任务日志**。A→B→A 的相同正文可有相同指纹：只允许返回当前确认的同一 SQL 原文，不证明旧异步操作仍可重新发送。尚未实现持久 outbox、修订顺序控制、operation_id 日志、超时不确定占款/成本核验、远端删除追踪和补偿。真正接入前必须补齐这些部分；不能用相同 document_id 的替换语义推导并发、晚到写入自动安全。

本轮 GitHub API 的只读 HEAD 元数据请求被执行环境代理以 `Tunnel connection failed: 403 Forbidden` 阻止，未重试该端点或申请凭据。官方 GitHub 页面及文档通过网页工具可读，当前项目远端正常。未取得/伪造候选上游部署 commit；实际 PoC 前仍需固定可核验的版本/镜像 digest 与许可，不使用滚动 latest。

## 独立中文评测与冻结失败

新 `memory_provider_zh_v1.json` 在 Provider 重构前冻结，SHA-256：`69d43c2e13a1711e86f820c8c415f79594f42d418917e3e7701b9653125b6603`。200 条当前用户已确认记忆（12 条主要事实、188 条确定性中性干扰），另加跨用户、跨人物、PENDING 和 REJECTED 项。30 个问题：29 个有质量预期，概览 1 个只检查范围/来源/上限，不假定答案为空。

该集独立于旧 247 项与原资料记录卡评测，由本轮实现者编写并已暴露，**不是盲法保留集**；部分诊断沿用已知词汇家族。没有拿结果调整检索规则，也不把它重新命名为未暴露验收集。干扰记录规律较强，不代表真实分布。

重构前真实 `MemoryRetrieval` 基线与重构后逐例 actual/expected 及指标完全相同：

| 指标 | 重构前 | 重构后 |
|---|---:|---:|
| 正例 hit@12 | 12/21 | 12/21 |
| 文档 TP / FP / FN | 12 / 0 / 9 | 12 / 0 / 9 |
| precision / recall | 100% / 57.14% | 100% / 57.14% |
| 空预期误返回 | 0/8 | 0/8 |
| 范围/当前正文/来源违例 | 0 | 0 |
| 语义质量状态 | UNRESOLVED | UNRESOLVED |
| 真实 Hindsight 质量 | 未运行 | 未运行 |

旧四组 0/4 属于共享工作资料记录卡，不混进私人记忆的分母；旧原始结果哈希和新增纠错改写失败在 `memory_provider_known_failures_v1.json` 冻结。旧四组本轮未复跑、没有恢复旧 247 项。新的记忆集中“海边拍照偏好是什么？”仍未命中纠错目标；原文对照命中不能替代该改写失败。

## 验证证据

新增 10 条合成服务/协议回归，检查参数与 operation_id 保持、纠错 replace 描述、原文只来自 SQL、修改/拒绝/遗忘/删除源会话后的晚到引用、另一用户和 bank 范围、调用期间撤权、缺失/伪造来源、不可用回退、去重及不接收 observation。这里的“晚到”是受控 fixture 返回，不是运行中的真实 Hindsight 任务；operation_id 与删除描述测试不是远端幂等或删除已完成的证明。

另 1 条真实 SQL 中文评测执行来源/范围/只读安全门槛；JUnit 通过不等于 9 个质量漏召通过。原有 30 条上下文回归与两项新测试类合计定向 41 条通过。最终代码本轮新执行 `python3 scripts/verify.py --e2e`，**退出 0**：Java 288（新增 11）、Worker 91、25 组 Node、18 个 JS、架构守卫 104 个 Java 源文件和全部 17 套 E2E 通过；原冻结情境 30/30，首次旅程 5 组与已有记忆/声音场景 7 组通过。最终中文报告仍为上述未解决结果，与基线逐例一致。基线、重构后及最终中文报告、汇总在忽略目录 `data/resume-20261026/memory-provider/`；全套原始日志为 `data/resume-20261021/text-egress/memory-provider-full-20261026.log`。

## 真实中文 PoC 的最小批准边界

1. **模型与质量**：选择能处理中文的 retain 提取 LLM；官方列出的多语候选如 `BAAI/bge-m3` embedding、`BAAI/bge-reranker-v2-m3` reranker 仅是待验证候选，不是已选或已下载模型。还需核对各权重许可、来源、校验及目标硬件 RAM/VRAM、冷/热延迟、磁盘占用；当前没有可靠实测容量，不猜测资源数值。
2. **独立计算与存储**：批准固定版本 Hindsight API/Worker 与独立测试数据库，不接管平台业务表。中文全文检索需另选并验证 CJK 路径；默认英文 embedding/reranker/native English FTS 不能算中文配置。官方中文文档列出的 pgroonga/pg_search 方案依赖外部 PostgreSQL 扩展，不能假定默认内嵌数据库已具备。未创建这些资源或安装扩展。
3. **安全与同步**：私有服务入口、服务鉴权/租户边界、Java bank 映射，以及上述持久同步/顺序/删除补偿必须先验。真实传输还需有限超时、响应大小与取消约束，不能在持有人物锁的领取事务里直接添加无界外呼。不得开放一个任意 bank 可读写的公共入口；不需要聊天里输入密钥。任何凭据只走后续明确批准的安全设置，不授予额外持久权限。
4. **可核费用**：retain 的提取、embedding/rerank 和潜在后台归纳可能占用计算或模型费用；“不调用 reflect”不等于所有后台 LLM 消耗为零。须固定实际后台配置、调用阶段和用量/收费依据，再批准合成 PoC 的开销。此轮费用后置、真实出口关闭，不借旧预算假定可开通新服务；历史真实消费未知不当作 0。
5. **退出指标**：先冻结新的未暴露中文验收集和模型/配置，分别量化真实候选 hit/precision/recall、误召回、拒答、来源/版本违例、隔离、纠错/删除/重启晚到、真实调用次数、费用与 p50/p95。协议 mock 不参与真实召回改善分数。先 retain/recall，不 reflect；未达门槛保留 SQL 路径。

当前没有批准上述模型安装、外部调用或部署，所以本批不跨过它们。合成文字演示沿用原启动方式，声音保持已有状态；不因 PoC 未批准而撤销已完成的代码边界与评测准备，也不无限扩建首批。
