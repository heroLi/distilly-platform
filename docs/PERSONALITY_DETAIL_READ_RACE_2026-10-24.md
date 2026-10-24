# 首次旅程超时：人格详情的完成快照竞态

从 `c569644e5e162cb34183e1abb00e3f294c9f5540` 的原工作区继续定位。首次统一入口失败仍保留在 `data/resume-20261024/source-delete-browser-kill/walkthrough-failed/`，没有覆盖截图、结果或 API/Worker 日志；原 `result.json` SHA-256 为 `1f67c6afe6dc3cc09e4df74e9d9c7f18081a55b2880e357c970ce7d63c26abe8`。

## 确认的问题

原人格详情使用 `Promise.all` 并行读取人格和任务，再按任务中是否还有 QUEUED/RUNNING 决定启动轮询。存在如下合法时序：

1. 人格 GET 先读到无版本的旧快照。
2. Worker 完成生成，候选版本和任务 SUCCEEDED 在同一事务提交。
3. 任务 GET 读到 SUCCEEDED，详情因此显示旧的空版本列表，又不再轮询。批准按钮一直不出现，旅程最终超时。

新增行为测试直接执行实际 `renderDetail` 读取代码，在两次读取之间提交合成版本，旧代码断言失败。随后在原生 Chromium 中用实际 API 和实际 mock Worker 固定这一时序，旧 JAR 稳定复现 `await-first-candidate` 超时：

| 证据 | 实际值 |
| --- | --- |
| 浏览器取得的人格版本数 | 0 |
| 控制执行前 / 后任务状态 | QUEUED / SUCCEEDED |
| Worker 退出码 | 0 |
| 超时后的真实 API | 版本数 1，任务 SUCCEEDED、无错误、attempt 1 |
| UI | 与首次失败相同的空人格详情，没有批准按钮 |

受控红测结果 `data/resume-20261024/detail-snapshot/native-red/result.json` SHA-256：`9cf3c4a845b7922b300cea3ad22c10b33989d83cda5f92126ae853ddd0fecff4`。这确认了产品读取竞态，不能仅归为环境瞬态。原首次失败缺少逐步标记和双 GET 时间线，临时库也已按原规范清理，因此不能唯一还原那一次的具体请求交错；本轮复现与原失败分别保存，不把新观测冒充原始输出。

## 最小修复及验证方式

详情先读取任务，再读取人格。若已观察到任务完成，后读的人格便能看到该次已提交版本；若任务仍进行中，既有轮询继续生效。沿用原作用域和弹窗有效性检查，请求数不变，顺序读取多等待一次网络往返；没有新接口、锁、记忆架构或依赖。知识台仅更新脚本缓存版本，没有调整页面风格。

首次旅程增加 `--completion-between-detail-reads`，统一入口最后一套启用该模式：只停止自己启动的空闲 mock Worker，在浏览器读取任务时用原 Worker 真实领取并完成唯一生成任务，再返回同一个 API 的真实读取结果。没有伪造版本、删除检查、扩大 UI 等待时限或用睡眠等待“变稳定”。原候选按钮仍限时 20 秒、默认 JVM 启动仍限时 30 秒；恢复后台 Worker 后继续全部绑定、首聊、TXT、纠错和权限步骤。普通后台执行模式仍可独立运行。

新增分步标记、失败时版本计数、任务状态/错误/attempt、Worker 退出码诊断，不保存材料正文、请求头或凭据。新行为测试覆盖完成恰在两次读取之间及读取前已完成的两种情况。

```sh
node tests/personality_detail_snapshot_test.cjs
python3 scripts/verify.py --e2e
python3 tests/first_text_walkthrough_e2e.py --output server/target/first-text-normal
```

最终候选的本机统一入口 **退出 0**：Java 249、Worker 86、24 组 Node、16 个 JS、架构守卫、启动器/冻结契约及 16 套 E2E 全部通过，冻结 30/30 未改写。最后一套首次旅程使用受控完成模式，5 组通过；首个人格快照版本数为 1，真实任务由 QUEUED 变为 SUCCEEDED，Worker 退出 0。普通后台 Worker 模式也在同一 JAR 上单独 5 组通过；不是用后者替代统一入口。

统一入口的首次旅程结果 SHA-256：`2ee1f89505fb9ffb7c1b15b229fa648fa22a7144fb80fe5adf1a8d359700555c`。候选 JAR SHA-256：`ae17911944922c6077a4a85d9707a5a2beda771084ff93266bce0ed8e6e11493`。本机完整证据位于 `data/resume-20261024/detail-snapshot/`，命令日志以 `data/resume-20261021/text-egress/detail-snapshot-*-20261024*` 单独保留，原失败与受控红测没有覆盖。完整通过不代表已执行远程 CI、真实模型质量或生产运行验收。

本轮仅使用专用临时 H2、回环端口、合成账号/TXT 和 mock，真实调用为 0。真实质量未测、窄语义 0/4 与旧 247 项未恢复事实不变；无生产数据库、部署、新权限或收费研究。
