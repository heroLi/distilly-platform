# SUP-001：兼容性与发布边界工程评审

日期：2026-11-11。源码基线：`8674c1efb6ad92aaa0fc2fe25008ab9fc4fbf89c`。本评审承接[离线合同设计](MEMORY_SUPPORT_CONTRACT_DESIGN_2026-11-11.md)及其[冻结设计清单](contracts/memory_support_reference_v1.design.json)。**设计/工程评审完成，事实支持判定仍未实现。**

## 结论与本轮范围

工程选择是：**继续复用现有显式原文参考入口，不新增运行时代码，不将设计提议接入默认聊天。** 该入口已有严格请求/响应合同、SQL权威来源、授权快照、NOT_ASSESSED和安全显示，不需要再造平行API、页面或模型开关。

本轮核对源码、既有测试合同和当前backlog，并执行直接相关的既有后端/前端回归。没有发现违反现行参考合同、可在本轮最小修复的新增运行时缺口；不因此声称全量审计完成。结构化声明方案仍缺真正的可答定义/支持判定及独立验证，仅加ID、引文或supported字段不构成可实施的事实判定闭环。

没有修改默认CHAT、已有参考事务快照、读取发布检查点、数据库/检索、模型出口或运行时源码。设计JSON保持原hash和DESIGNED_NOT_IMPLEMENTED，不把评审结果当运行时启用信号。SUP-001清单状态更新为DESIGN_REVIEWED_NOT_IMPLEMENTED；其它16项任务不变。

## 兼容矩阵

| 项目 | 源码事实与兼容要求 | 当前证据/结论 |
|---|---|---|
| 默认聊天请求 | 现有messages路径接收原聊天字段；SOURCE_REFERENCE模式不能作为额外字段偷渡 | 后端对mode/answer_mode注入拒绝；正常聊天对照保留；无新默认或自动切换 |
| 显式参考请求 | POST /api/personas/{id}/memory-references；精确mode/query，SOURCE_REFERENCE，查询上限4000 | 多余answer/support_status/stream/memory_ids/bank_id/user_id拒绝422，不产生CHAT任务 |
| 传输与响应 | 只产生JSON，MEMORY_REFERENCE_V1、SOURCE_REFERENCE、NOT_ASSESSED、notice、references；no-store | SSE Accept拒绝406；前后端严格字段白名单，不接模型answer或新状态 |
| 参考条目 | 最多12项，memory_id/source_message_id/record_text/record_fingerprint/quote_kind | 字段、重复ID、指纹格式和CONFIRMED_MEMORY_RECORD核对；不把record_text当原聊天 |
| 权限与确认 | 当前actor、成员和人物访问；只取本人CONFIRMED且来源归属合法的记录 | 跨用户/人物/空间、PENDING/REJECTED、失效来源不进入合法参考 |
| 来源身份 | SQL重新读取记录，比较MemoryProvider.reference指纹 | 不一致409；这是记录身份，不是事实真值、供应商ACK或单调版本 |
| 参考快照 | 成员→人物锁序，在事务授权快照内选择/复核SQL记录 | 正常纠错/撤权与读取按既有锁排序；本次已获准旧快照可完成，刷新用新状态 |
| 普通读取检查点 | 会话详情/记忆管理/原始来源的最终SQL复核是另一个既有合同 | 不把其规则移植为参考API的HTTP发送时零窗口，不新增锁或改变隔离级别 |
| 流式发布 | 原文参考不创建CHAT，不调用模型或delta；默认CHAT仍有原增量通道 | 两路径分离；不能将默认CHAT的增量说成通过事实支持检查 |
| 引用语义 | 有效ID、逐字引文、用户确认、相似度和模型自报supported都不是蕴含证明 | NOT_ASSESSED保持；不能借评审改为SUPPORTED/VERIFIED |
| 安全显示 | 参考前端用textContent，固定说明，不把server notice当答案执行 | HTML按文本展示，错误正文不回显；没有本轮UI/视觉改动 |
| 生命周期 | epoch/project/persona/请求generation/页面状态控制晚响应；已知记忆列表变更清参考 | 关闭、离开、失权、换scope和旧错误不能写回；没有自动重发 |

## 版本复核不能混为一种保证

1. **记忆记录指纹**：MemoryProvider.reference用memory ID、source ID和body生成当前内容指纹。参考接口比较这个指纹，不把它称为单调revision；A-B-A可能回到相同指纹。
2. **来源归属**：参考SQL检查来源为本人、同人物的user消息；不输出或比较来源会话的version_id。当前参考是跨会话已确认记忆记录，不要求来源会话版本等于当前发布人格版本。擅加这种条件会改变现有合法读取，不是本轮已证实的缺陷修复。
3. **原始消息读取**：另一个来源读取路径有read_source_version快照复核。不能将该字段的保证冒称已经存在于MEMORY_REFERENCE_V1，也不能据参考结果扩大读取整段历史。
4. **任务/租约版本**：默认CHAT和未来若采用任务的新合同仍受原claim/complete/delta、固定会话版本、取消/失权和租约屏障约束；旧任务不能只凭相同正文指纹复活。不同领域的检查不互相替代。

以上限定来自现行SQL与接口形状，不是撤销权限或来源要求。如未来要回答某个特定会话/人格版本的事实，需先定义相应证据语义和回归，不能静默增补响应字段或把记录指纹当完整来源版本证明。

## 错误、刷新与发布边界

| 事件 | 现有处理 | 不改变的边界 |
|---|---|---|
| 非法mode/类型/额外键 | 422，不创建任务或消息 | 不回落为CHAT；输入合同不能靠旧客户端猜新字段 |
| SSE协商 | 406 | 不建立隐藏流式参考通道 |
| 401/403/404 | 参考UI清查询和旧记录，提示核对身份/人物/权限 | 不展示服务器错误正文，不自动换actor/bank重试 |
| 409来源变化 | 清旧参考，保留显式重新核对路径 | 正常串行化旧快照不一律当409；后续读取当前记录 |
| 连接/其它失败 | 清旧参考，保留可编辑查询和手动重新核对 | 不用已有缓存冒充新成功，不自动生成答案 |
| 响应结构或NOT_ASSESSED不符 | 前端拒绝展示 | 自由answer、新support状态、未知字段和重复ID不能混入记录展示 |
| 无匹配 | 空参考状态明确“未匹配不表示从未发生” | 不制造否定事实结论，不越权补充来源 |
| 快照后才修改/撤权 | 当前合法快照可能完成，新请求读取新状态 | 不承诺撤回已发送/已排队字节、其它端副本或HTTP零窗口 |
| CHAT已发增量 | 沿原权限/租约规则；语义支持未判定 | 不能事后清空/报错便声称从未曝光；新窄模式如有须先隔离发布 |

前端对记录列表的已知变化还会清除进行中的参考快照，这是现有保守生命周期处理，不是新服务端快照或事实判定。当前26项模块测试不是原生BFCache、后台标签、生产浏览器或跨设备验收。

## 本轮实际检查

资源密集步骤串行，使用已有Java17/离线Maven和已有Node，不安装组件、不调用外部服务：

```sh
mvn -B -ntp -f server/pom.xml -Dtest=MemoryReferenceTest test
node tests/memory_reference_ui_test.cjs
```

- 后端11/11，失败/错误/跳过0，默认JVM；外层23.715秒。MockMvc、实际控制器/服务/SQL、独立合成H2，包含字段/只读/模式隔离、权限/确认/来源与纠错/撤权快照对照。不是浏览器或生产PG测试。
- 前端26项通过，0.215秒；实际persona-memory-reference.js在Node VM/模拟DOM中执行，包含严格合同、文字显示、空态、错误态、显式重试、晚响应及scope/列表变化清理。不是原生视觉验收。
- 新文档/状态/来源、Markdown与CSV一致性、链接、源文件与冻结清单保护以及安全/diff另做本轮检查，不借历史全套替代。

没有改变运行时代码，因此本轮没有重跑完整Java/Worker/前端/E2E入口，没有新模型、Colab、语义或独立盲测。11/10默认全套467/103/20/28/22仍是历史记录；本轮11和26仅用于本次兼容评审，不能推广为整个产品重新验收。

## 可执行结论与真实缺口

**立即保留的选择**：沿既有明确原文参考入口提供可追溯记录；不新增平行接口、不改变默认聊天、不添加“已支持”布尔值或隐藏重试。这个安全参考切片已经实现，本轮没有必要重复开发。

**不具备最小实现条件的部分**：把任意自由中文回答判为受支持，目前没有可信可答规则/判定器；结构化引用只解决形状和身份。只针对5个旧断言补关键词会过拟合，不能成为通用支持闭环，也可能损坏合法回答。当前没有复现违反既有参考合同的新增缺口，因此不为凑提交改运行时。

**下一次进入新实现的条件**：有明确、可验证的窄可答定义或支持规则；说明是否/如何新增opt-in聊天版本、服务端模式绑定、持久化/增量发布、失败/重试和旧客户端兼容，并先写真实红测/合法对照。若这些条件仍不具备，就保留参考而不声称事实回答成熟。实现后必须按仓库要求做本轮新回归和适用完整默认验收，不能用本文替代。

Colab中的embedding/reranker即使未来提高召回，也不自动满足事实支持规则；当前合成包未实际运行，模型/revision/许可/资源及独立评测条件仍待分别落实。CI未核验不阻止本轮本地工程评审，但阻止声称远端验收通过；既有Forbidden、官方源及许可拒绝没有新恢复证据，不重试或换路。

## 可复核来源

- [设计与14项边界](MEMORY_SUPPORT_CONTRACT_DESIGN_2026-11-11.md)、[原支持缺口](MEMORY_SUPPORT_BOUNDARY_2026-11-03.md)、[原文模式](MEMORY_REFERENCE_MODE_2026-11-03.md)。
- [参考Controller](../server/src/main/java/internal/distilly/modules/memory/controller/MemoryReferenceController.java)、[服务](../server/src/main/java/internal/distilly/modules/memory/service/MemoryReferenceService.java)、[参考SQL](../server/src/main/java/internal/distilly/modules/memory/repository/MemoryReferenceRepository.java)、[记录接口/指纹](../server/src/main/java/internal/distilly/modules/memory/domain/MemoryProvider.java)、[普通记忆SQL](../server/src/main/java/internal/distilly/modules/memory/repository/MemoryRepository.java)。
- [默认聊天服务](../server/src/main/java/internal/distilly/modules/task/service/PersonaTaskService.java)、[Worker](../worker/persona.py)、[参考前端](../server/src/main/resources/static/persona-memory-reference.js)。
- [后端11项测试](../server/src/test/java/internal/distilly/MemoryReferenceTest.java)、[前端26项模块测试](../tests/memory_reference_ui_test.cjs)、[已有原生场景源码](../tests/memory_reference_ui_e2e.py)（本轮未执行）。
- [普通读取发布检查点](PRIVATE_READ_PUBLICATION_2026-11-03.md)、[现行backlog](PROJECT_BACKLOG_2026-10-15.md)。本地本轮结果在忽略目录`data/resume-20261111/support-compatibility-review/`，不提交日志或数据库。
