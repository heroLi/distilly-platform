# 合成费用预留与结算账本

仅用于演示与自动化回归。没有供应商连接、真实费率、账单查询、支付、真实模型请求或 Worker 任务集成。所有成功账本响应标记 `synthetic: true` 和 `real_egress_authorized: false`。仅 `platform.demo=true` 且当前空间 OWNER 可用，非演示模式返回 404；EDITOR、VIEWER 和无空间权限用户返回 403。响应 `Cache-Control: no-store`，不收集提示词、材料正文、凭据或私人资料。

每个演示空间独立有固定 1000 分（10.00 元）的**合成额度**，不是给每个空间增加真实调用授权。真实模型累计授权仍最多 10 元，而且费用无法核验就不能调用；当前真实文字出口保持关闭，账本成功结算不会解除该门禁。

金额只接受整数分，不接受小数、浮点数、字符串、负数或客户端币种。摘要满足：`held_fen + spent_fen + available_fen = 1000`。每次写入都依次锁当前 OWNER 成员行和空间行，在同一事务中变更预留及追加事件。多个负责人也共用空间锁，不会并发超额。金额从持久记录计算，不依赖内存计数器；事件按每笔单调序号排序。

| 操作 | 状态与金额 |
| --- | --- |
| 预留 | RESERVED，占用 1 至 1000 分；可用余额不足则 409，不写记录 |
| 模拟成功 | SETTLED，实际分值必须在 0 至原预留之间；只累计实际值，差额释放 |
| 已模拟确认未计费的失败 | RELEASED，实际 0，释放全部预留 |
| 超时、崩溃或结果不明 | 保留 RESERVED，或标记 UNCERTAIN；全部额度继续占用，不按时间自动释放 |
| 恢复 | 重启后读取原记录；用明确的模拟账单结算，或模拟零账单释放 |
| 超过预留的结算 | 409，保留原占款及事件，不能通过释放或部分记账掩盖异常 |

API 基址 `/api/projects/{project}/synthetic-costs`：

- `GET`：摘要契约 `SYNTHETIC_CNY_LEDGER_V1`，包含上限、已结算、占款、可用、预留总数及未解决数（RESERVED + UNCERTAIN）。
- `POST /reservations`：`{"request_key":"synthetic-001","reserved_fen":600}`。
- `GET /reservations/{id}`：当前预留及按序事件。
- `POST /reservations/{id}/outcomes`：以下三种严格输入之一。

```json
{"callback_key":"callback-001","outcome":"SETTLED","evidence":"SIMULATED_BILL","actual_fen":250}
```
```json
{"callback_key":"callback-002","outcome":"RELEASED","evidence":"SIMULATED_ZERO_BILL"}
```
```json
{"callback_key":"callback-003","outcome":"UNCERTAIN","evidence":"SIMULATED_UNKNOWN"}
```

`request_key`、`callback_key` 限 1 至 80 个 ASCII 字母、数字、下划线或连字符。预留键在空间内唯一：同键同金额返回原 ID 当前状态，不重新占款；同键不同金额返回 409。回调键在整个空间内唯一：同键、同预留、同结果和同金额重放返回当前状态，不追加事件或重复扣费；修改载荷或跨预留复用键返回 409。终态后新回调键也返回 409。UNCERTAIN 旧回调在后来结算后重放，只返回当前终态，不回退。每笔最多有预留、可选不明、最终处理三个事件。

`SIMULATED_*` 是负责人提交的演示标签，没有验真能力，不能证明真实供应商未计费。真实超时可能已计费，未来必须接入可信账单或用量回执、核验费率与完整调用成本，并建立真实全局累计预算，才可另行考虑真实模型出口。当前各演示空间额度不可直接拼成真实全局预算。

回归：`mvn -B -ntp -f server/pom.xml '-Dtest=SyntheticCostLedger*Test' test`；隔离默认 JVM / 文件 H2 E2E：`python3 tests/synthetic_cost_ledger_e2e.py --output server/target/synthetic-cost-e2e`。完整入口 `python3 scripts/verify.py --e2e`。测试仅使用合成标识和账单数值，不读取真实凭据或模型账单。

恢复验证发现并修复的边界：最初的隔离 H2 默认配置在 UNCERTAIN 成功应答后立即 SIGKILL，重启后原负责人看不到原空间，账本为 403；带额外项目计数诊断的第二次也复现（不能仅凭 HTTP 区分项目行还是成员行丢失）。这两次不能计为恢复通过。H2 默认延迟刷新已提交日志；当前启动流程在迁移事务前识别 H2 并执行 `SET WRITE_DELAY 0`，其余数据库不执行此设置。设置用于消除应用层提交刷盘延迟；SIGKILL 实测不等同于机器断电、存储故障或云工作区销毁保障。参考：[H2 WRITE_DELAY 官方说明](https://www.h2database.com/html/commands.html#set_write_delay)。原失败结果保留，修复后的结果须以重新打包、独立目录的新执行证据为准。

本轮新回归实测：Java 20 项（账本主类 19 项、非演示模式 1 项）通过；其中包括两个不同 OWNER 并发预留、同键并发、相反终态竞争、重复回调及两处事件写失败注入回滚。修复后独立 HTTP E2E 7 组通过：成功应答后 0.107 秒 SIGKILL，恢复后空间可见、占款与事件保留，继续结算成功。新包三次默认 JVM 启动分别 3.677、2.949、3.053 秒，无 Worker、真实模型调用为 0，全部进程已清理。两次修复前失败及一次修复后成功均保留在本地忽略目录，未覆盖或推送。

最终全量入口实测通过：Java 152 项、Worker 50 项、14 组 Node 测试、11 个 JS 语法检查，以及 5 套 HTTP E2E（包含记录卡闭环和合成账本 7 组）。Java 17 环境运行，Python 3.12 / Node 24；没有冒称 CI 的 Python 3.11 / Node 22 矩阵已在本机复验。PostgreSQL 没有在本轮启动或验收；旧四项语义失败与 247 项缺失状态保持不变。
