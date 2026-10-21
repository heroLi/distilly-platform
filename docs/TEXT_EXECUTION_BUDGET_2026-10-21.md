# 全局合成文字执行预算契约

本轮基线 `4ecfa40bdc840b67e39ea7abc09811f35eaa2015`。这是执行路径的合成适配工程，不是真实预算开户或供应商扣费授权；不重置或推定任何历史真实费用。真实出口仍由原文字门禁关闭。目标只限 DeepSeek / `deepseek-flash`、文字阶段，媒体和真实凭据不在本轮范围。

## 作用域和状态

单一数据库的 `SYNTHETIC_TEXT_GLOBAL_V1` guard 行，所有空间、用户、WORK/PERSONA 队列及阶段共用。固定模拟累计上限 10 CNY，即 `10000000000` 整数纳元（1 元 = 10^9 纳元），每次调用固定预留 `1000000000` 纳元。这是测试容量政策，不是从价格估算证明的真实调用上界。迁移仅首次建行，启动、租约更新、取消、重试不清零。

所有写操作先锁全局行；预留再检查并锁当前任务租约。记录持久绑定 queue/job/project、原租约、stage、unit_digest、request_key。只存摘要和协议元数据，不存提示词/材料/凭据。当前有效租约才能新预留/领取发送权；同任务有任何未决预留时不得另开请求（跨阶段、新租约也一样），其他任务仍受剩余额度约束。

`RESERVED → DISPATCHED → [UNCERTAIN] → SETTLED`；未发送的 `RESERVED → RELEASED`。DISPATCHED/UNCERTAIN 都全额占款；崩溃、断流、超时、取消、重试不自动释放。服务端发放发送权仅一次，重复领取返回409；确认丢失不能重发模型请求。回执晚到允许用该预留原租约做记账，即使任务已失租/结束，但不能恢复任务状态。已结算费用永久累计，差额释放；超过预留不部分记账，不释放占款。

## API（只在 demo 模式可用；均 no-store）

内部接口沿用已有 Worker 身份认证，用户令牌不得访问；没有新凭据或新权限开关：

- `POST /internal/text-budget/reservations`，严格字段 `queue`（WORK/PERSONA）、`job_id`、`lease_token`、`stage`、`unit_digest`（64位小写SHA-256）、`request_key`（1–80 ASCII字母数字下划线短横线）、`model`（deepseek-flash）。WORK DISTILL 仅 distill；WORK ANSWER 仅 knowledge_answer；PERSONA PERSONA 仅 persona_analysis/persona_build；PERSONA CHAT 仅 default。其他种类拒绝。成功返回 `id,status,reserved_nano_cny` 等公开元数据，不返回租约。
- `POST /internal/text-budget/reservations/{id}/dispatch`，仅 `{lease_token}`。只允许当前有效原租约将 RESERVED 改为 DISPATCHED，返回 `dispatch_permitted:true`；重复/晚到/其他状态409。
- `POST /internal/text-budget/reservations/{id}/outcomes`，严格载荷之一：
  - `{lease_token,outcome:"SETTLED",evidence:"SYNTHETIC_USAGE_V1",receipt_key,actual_nano_cny}`；金额整数0..预留。receipt_key 全局唯一，1–160 ASCII字母数字下划线短横线。相同预留/金额重放不追加事件；跨预留或变更金额409。price_review 在预留时固化，本轮固定离线报价观察标签。
  - `{lease_token,outcome:"UNCERTAIN",evidence:"SYNTHETIC_UNKNOWN"}`；RESERVED/DISPATCHED 可转未知，重复未知无新事件；已终态后迟到未知返回当前终态，不倒退。
  - `{lease_token,outcome:"RELEASED",evidence:"SYNTHETIC_NOT_DISPATCHED"}`；仅尚未发送的 RESERVED，重复同释放幂等。发送后只有显式合成零用量 SETTLED 可记0，不能凭失败释放。
- `GET /internal/text-budget`：全局 summary + 最近100条脱敏记录及各笔最多4条事件（仅序号、结果、合成证据标签、金额、时间），所有未决计入摘要。只供 Worker 合成验证，不公开跨空间剩余金额给用户。
- `GET /api/projects/{project}/text-budget`：demo OWNER 可看自己空间摘要与最近100条脱敏记录；不包含其他空间记录、全局实际剩余额或原租约。EDITOR/VIEWER/非成员403。

响应标记 `contract: SYNTHETIC_TEXT_EXECUTION_BUDGET_V1`、`scope: INSTANCE_DATABASE_GLOBAL`、`currency: CNY`、`unit: nano_cny`、`synthetic:true`、`is_bill:false`、`real_egress_authorized:false`；全局 `spent + held + available = 10000000000`。预留/发送/未知/结算/释放事件与状态在一个事务内写入，不提供删除、重置或提额接口。

## Worker 集成约束

新非敏感开关 `MODEL_SYNTHETIC_GLOBAL_BUDGET=true` 仅用于显式 literal loopback fixture + gateway + deepseek-flash。未设置时原 mock 和协议 fixture 行为保持；真实远端即使设置开关也在请求构造/预算操作前被原出口门禁拒绝。启用后必须在 Worker 领取的执行上下文中调用，不允许裸 Model.call 跳过 task/lease 绑定。

每次 Model.call 自动从执行上下文绑定 queue/job/lease 与 output_stage，unit_digest 包括 system/payload/输出策略的摘要，不保存正文。每次物理调用生成一个 request_key，仅重传预算 RPC，不重复模型 HTTP。必须收到新发送权才发请求；所有异常先尝试记录 UNKNOWN，不能自动把网络失败变为零账单。收到完整 usage + DONE／非流式终态后，用离线校验器估算并提交 **SYNTHETIC_USAGE_V1**；它不是供应商可信回执。成功记账后业务输出校验仍独立，截断/非法JSON也可能已消耗模拟用量。记账确认丢失不能再调模型。

失败使用终态 `MODEL_EXECUTION_BUDGET_UNVERIFIED`，不会走 MODEL_UNAVAILABLE 自动重试；任务层显式重试或租约恢复再次尝试也会被持久未决预留拦下。既有任务完成输出重传不产生新的预算/模型请求。一次 Worker 上下文必须 finally 清理，避免下一任务错误继承。取消和退出不能清理账本占款。

此开关不会注册真实供应商、不会改变安全凭据设置，也不会把已有演示空间账本相加为真实费用。当前工程仅同一文件数据库/单数据库锁语义，生产 PostgreSQL、多API实际部署和工作区销毁后的持久性另需验收。

## 当前已完成与剩余依赖

Java 模块化单体新增追加迁移 V008、预算 Repository/Service/Controller；Python 标准库 Worker 在现有文字入口接入，不增加常驻进程或框架。事件和账目用同一事务提交；内部全局快照可直接验证最多4条事件。负责人只看到自己空间的金额及记录状态，不暴露私有聊天 task ID、输入摘要或其他空间剩余额。双端任务失败提示说明合成占款可能待核对，避免连续重试。

已完成的工程限于**合成执行适配**：跨空间／队列／阶段单池、逐调用绑定、一次发送权、RPC幂等、未知占款、迟到记账及同库重启。服务器在非demo环境隐藏接口；Worker 的原真实出口门禁源码未改。每次1元是固定模拟测试政策，真实调用不能沿用这个上界；公开报价估算不能升格为可信回执。

仍需非秘密外部依据：DeepSeek 能关联请求／completion ID 的实际扣费或用量对账格式与核验渠道、实际币种／时段／舍入规则、未知请求对账办法，以及可核验的云安全秘密注入能力。没有这些依据不要求用户提交密钥、不重问已批准的累计10元预算。真实授权初始化必须核对历史累计消费，不能把本模拟池从0开始误当真实剩余10元。还需真实价格／模型版本变更失效策略、可信回执适配及真实出口授权器的限定验证，不能通过本测试开关开放真实调用。

## 复验与演示

构建后执行 `python3 tests/text_execution_budget_e2e.py --output server/target/text-execution-budget-e2e`，自动创建隔离文件H2、字面回环网关和受控Worker，结束清理进程。测试只传代码内合成文本／回执，不读取 `.env` 或继承供应商配置。全量入口为 `python3 scripts/verify.py --e2e`。

普通无密钥演示仍是 `python3 scripts/run_synthetic_demo.py --port 18080`，浏览器本机 `http://127.0.0.1:18080/` 使用公开 `demo-owner`。默认mock不产生调用费用或新执行账目；TXT→审核→人物选资料→首问→纠错→重核仍可用。没有遗留对外预览，不部署公网。

本轮新测试：Java 31项覆盖跨项目／队列、输入／权限、争用最后额度、过期租约记账、回执重放与冲突、4处事件写失败回滚；Worker新增23项覆盖各阶段绑定、丢ACK、严格响应绑定、流式用量／取消、未决终态和上下文清理。完整 `scripts/verify.py --e2e` 退出0：Java205、Worker86、Node15组、JS语法12个、启动器2项及8套E2E全部通过；其中执行预算初版E2E为7组。随后最终脚本新增实际HTTP SSE成功和缺DONE边界，另用新目录执行8组全部通过；产品源码不因这次补测改变。缺DONE即使收到终用量也不结算，保留占款，换租约手动重试无新增模型HTTP。

最终执行预算E2E有18次实际Worker执行、15次本机合成请求、真实供应商请求0。默认JVM三次启动为3.347/5.551/3.600秒；收到未知账目快照后0.000306秒SIGKILL，重启后账目和事件完整快照一致，最后再正常重启一致。最终合成摘要 held=9000000000、spent=3528800、available=996471200 纳元，总和为10000000000；不表示花费了真实人民币。全部测试自建进程与网关已清理。

首个E2E因合成人物提交材料但consent=false被现有授权校验422拒绝，只修测试授权为true；该原失败目录和后续通过目录都保留。源码review发现并在首次测试前修正SQL CHECK的NULL边界及PERSONA反向父行锁；Worker回执绑定加固后重跑。原V007、真实出口门禁、旧开发／保留集及失败证据哈希复查不变。四组新窄语义目标仍0/4，不是恢复原缺失历史样本。证据位于忽略目录 `data/resume-20261021/execution-budget/`，不随Git推送；同库重启不证明存储故障或工作区销毁后的持久性。
