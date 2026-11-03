# 原文参考模式：最小 API 切片

基线 `9558eff6753e5c3f56c9e799cbae790a19cf10c8`，分支 `resume/text-cost-gate-20261021`。本批新增显式 SQL 原文读取，不替换普通聊天，不创建任务/消息/记忆，不调用 Worker、模型或外部 MemoryProvider，也不判定事实支持。

## 请求与响应

```http
POST /api/personas/{persona_id}/memory-references
Authorization: Bearer <existing authorized token>
Content-Type: application/json
Accept: application/json

{"mode":"SOURCE_REFERENCE","query":"需要查阅的记录词面"}
```

这是独立入口，mode 和 query 必填且只能有这两个字段。query 沿用已有 4000 字符上限。body 中的 answer、support_status、stream、memory_ids、bank_id、user_id 等均拒绝 422；请求 SSE 返回 406。新旧路由不自动互转：普通聊天接口收到 mode 或 answer_mode 明确拒绝 422；未带这些字段的原聊天协议保持不变。

成功响应只有以下字段：

- contract=`MEMORY_REFERENCE_V1`，mode=`SOURCE_REFERENCE`。
- support_status 永远为 `NOT_ASSESSED`。
- notice 为固定服务端提示：“以下是当前已确认记忆记录原文；相关不等于支持，是否满足问题条件尚未判定。”
- references 为至多 12 个记录：memory_id、source_message_id、record_text、record_fingerprint、quote_kind=`CONFIRMED_MEMORY_RECORD`。

没有 answer、模型断言、情绪、部分回答或 Worker 输出字段。references 为空也不表示事情从未发生；本接口没有做语义判断。成功及控制器内拒绝响应带 `Cache-Control: no-store`，不提供 SSE/异步输出。

**“原文”在这里明确指当前已确认的记忆记录正文**，可能经过用户纠错或确认编辑；不冒称最初聊天消息的逐字原文。record_text 精确复制当前 SQL 记录，不改写、不去空格、不删否定。source_message_id 仅标明关联来源；若需查看最初消息，继续使用现有、单独鉴权的记忆 source 接口。record_fingerprint 是当前 `(memory_id,source_id,body)` 内容指纹，**不是单调修订号、历史版本或事实真实性证明**。

## 权限和来源复核

新增 MemoryReferenceService / Repository / Controller，默认 SqlMemoryProvider、MemoryRetrieval、TextRelevance 和 Worker 源码均未改。

1. 从认证 actor 和路径 persona 推导范围，不接受客户端指定用户、空间、bank 或来源 ID。
2. 先校验人物访问，按现有记录读取的顺序锁当前成员行，再锁 persona，再校验访问。普通记忆纠错/拒绝/遗忘、来源会话删除和人物归档也使用 persona 锁；成员撤销修改同一成员行，从而将本次读取与这些变更排序。
3. 直接调用已有 **SqlMemoryProvider** 的同一词法选择逻辑，不走可能替换的外部适配器。候选 ID、来源和内容指纹不是授权。
4. 再以 SQL 复核当前用户、人物、CONFIRMED 状态、来源会话归属及 user 消息角色，比较候选与当前来源/正文指纹。任一已选来源不一致，整个请求 409 `memory_reference_changed`，不输出部分卡片；响应前再次校验人物访问。
5. 返回的是事务内授权的记录快照；纠错或撤权提交后的新读取使用新状态。已经交付的字节无法追溯撤回，不能将引用或指纹当作持续访问权。该切片没有新增客户端缓存、持久引用或页面状态。

本入口拥有独立返回通道，根本没有聊天 job 或完成回调 ID；因此无法混入旧 Worker 的自由回答和 delta。旧普通聊天仍保留原协议和其已披露的语义局限，不被此接口自动“升级为可信问答”。

## 新回归与实测

- 实现前新增 9 项 HTTP/服务测试全部失败，退出 1，原日志保留；覆盖缺失入口和新模式误入旧聊天的风险。
- 最终新增 11 项：精确 SQL 文本/指纹/固定状态、无写入和无异步流、严格输入、旧聊天路由防回退、私人范围/确认状态、纠错/遗忘、来源删除、撤权、选择后内容或权限变化、非法 assistant 来源，以及独立事务中纠错/撤权与读取的锁序。
- 新 11 项和现有上下文、引用合同、消息幂等共 **59 项通过**。定向构建再执行 11 项并产出测试 JAR，未跳过测试。
- 实际 HTTP/Worker 定向运行退出 0，38.013 秒：新入口只输出原文，owner 无法读取 viewer 私有记忆；读取前后会话/消息不增加、没有可领取 Worker job；拒绝 answer/stream 注入和错路由；纠错刷新、遗忘、原历史保留、归档后拒绝均验证。另在既有 6 个支持边界案例上核对新模式精确来源与 NOT_ASSESSED。
- 模拟/脚本调用、权限/来源与新协议通过不等于真实回答质量通过。原聊天仍接受 5 个带有效 ID 的不支持脚本断言，旧报告继续输出该未解决观察；旧 30 题不变，原 12/21、0/4 与旧 247 项未恢复保持。

本轮默认统一入口 `python3 scripts/verify.py --e2e` 全过，退出 0、592.633 秒：Java 390、Worker 98、JS 语法 19、Node 26 组、E2E 18 套及冻结工具 17 项。新精确提交 CI 另外核验，不引用旧结果代替。本地默认 JVM、30 秒启动期限均不变；新增 API 未做页面或新按钮，没有原生浏览器的“新模式页面验收”，不能把 HTTP 通过当视觉验收。

## 使用范围和后续

当前可由已有授权客户端在原私有开发环境明确调用此 API；没有部署、公开开放或新 UI。账号权限沿用记忆列表/来源读取，未新建凭据或扩大角色权限。不要求为此新生成人格、调用模型或开启真实出口。

它交付来源查阅，不回答“这些来源是否支持我的结论”。若后续接入页面，应明确独立模式并以纯文本呈现 record_text，处理退出/失权/晚响应，不默认替换聊天；本批未实现该界面。受限结构化事实问答仍是另一项协议/产品工作，不能用本切片宣称通用语义支持已解决。

证据位于忽略目录 `data/resume-20261103/memory-reference-mode/`：首次失败、定向测试、HTTP 报告、默认入口、保护哈希及审查回执。规划草案、所有旧失败与恢复证据保留，不提交 data、target 或临时数据库。遗忘对原聊天历史的产品语义保持待确认，真实模型调用 0、无外部模型活动、无新资源/费用/部署。
