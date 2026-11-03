# 浏览器测试依赖与全新 CI

运行Worker不需要第三方Python库；以下依赖只用于合成浏览器验收。`requirements-test.txt`固定Playwright 1.62.0、greenlet 3.5.5、pyee 13.0.1和typing_extensions 4.16.0，并列出官方PyPI wheel的SHA-256。选择这些版本是为了复用已验证云环境的具体版本，不能依赖全局环境恰好装过它们。

全新Linux开发或CI环境先执行：

```sh
python3 -m pip install --index-url https://pypi.org/simple --only-binary=:all: --require-hashes -r requirements-test.txt
python3 -m playwright install --with-deps chromium
python3 scripts/verify.py --e2e
```

使用[Playwright官方CI安装方式](https://playwright.dev/python/docs/ci)，不跳过浏览器测试。CI固定Ubuntu24.04，与原失败运行实际使用的系统一致；保留Java17/Python3.11/Node22。依赖安装和浏览器安装是每次标准入口的前置步骤，没有新增依赖缓存命中条件。

Playwright版本决定它管理的Chromium revision。本版包内manifest为revision1234、Chromium151.0.7922.34；测试默认由Playwright选择匹配的浏览器，而不是猜测`/usr/bin/chromium`是否存在。所有E2E通过同一启动器，保留原headless/启动参数。`verify.py --e2e`在耗时Java测试前实际启动浏览器、检查本地合成DOM，缺包或缺浏览器必须直接失败。

原云环境如需明确复用已安装的系统Chromium，可仅在测试命令上指定：

```sh
DISTILLY_TEST_CHROMIUM=/usr/bin/chromium python3 scripts/verify.py --e2e
```

覆盖值须是现有绝对可执行文件；无效值报错，不能自动回退。CI不设置此覆盖值，验证的是本次安装的匹配浏览器。本地覆盖测试不等于全新CI安装或匹配浏览器验收。

## 来源和许可边界

| 组件 | 固定版本 | 官方元数据声明 |
|---|---|---|
| [Playwright](https://pypi.org/pypi/playwright/1.62.0/json) | 1.62.0 | Apache-2.0 |
| [greenlet](https://pypi.org/pypi/greenlet/3.5.5/json) | 3.5.5 | MIT AND PSF-2.0 |
| [pyee](https://pypi.org/pypi/pyee/13.0.1/json) | 13.0.1 | MIT |
| [typing_extensions](https://pypi.org/pypi/typing_extensions/4.16.0/json) | 4.16.0 | PSF-2.0 |

浏览器由官方Playwright安装器下载Chromium，保留其随包通知；[Chromium核心许可](https://chromium.googlesource.com/chromium/src/+/main/LICENSE)为BSD三条款，第三方组件另有随包通知。这里核对来源和已声明许可，不声称完成所有浏览器第三方依赖的法律审计。不安装商业Chrome channel、不创建账号/密钥、不接付费模型。

## 已核实的原失败

[`2b8fb6c211020fde27d2dc5dba4d2422142ab3c4`的push CI](https://github.com/heroLi/distilly-platform/actions/runs/41563349514)实际失败：Java365、Worker91、JS19、Node26及前5套E2E通过后，`synthetic_cost_page_e2e.py`导入Playwright报`ModuleNotFoundError`。旧本地完整通过仍是本地证据，不能改称CI通过。

本修正只补测试依赖、浏览器选择和前置检查；默认JVM、30秒启动期限和所有原验收断言保持。新提交必须以其精确SHA对应的完整CI结果验收；安装步骤成功或本地局部通过都不能替代它。历史尾延迟、中文12/21、旧窄语义0/4和247项未恢复的边界不变。
