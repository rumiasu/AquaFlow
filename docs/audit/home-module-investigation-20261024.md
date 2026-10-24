# 顾客首页模块白屏调查 · 2026-10-24

本记录供本地调试与同步工程师使用。调查基线为 `3fba0d505415aebc7f9d823488570c5d864e1b26`。**白屏仍阻断原生验收，本批未找到已证实的应用源码缺陷；没有宣称白屏已修复。** 本次提交只增加依赖门禁、测试及调查证据，双端业务源码、样式、配置、后端和数据保持3fba。

## 已证实与尚未证实

| 证据 | 能得出的结论 |
| --- | --- |
| 用户截图及188行工具日志：`module 'utils/station-view.js' is not defined, require args is '../../utils/station-view'`，位于首页注册前 | 顾客首页的运行时模块解析失败；这足以阻止Page注册并造成白屏。不是后端8080拒连造成该require错误。 |
| 云仓库文件已tracked，路径/大小写正确，1122字节LF，无打包忽略；本地三树均有1136字节CRLF且hash相同 | 不能说同步漏文件。云文件转换CRLF后，SHA256恰为父任务本地核对的 `66eac113e2bfc11407c4cee6ebf9d3fe37e427f5c342b2da520ac8c2a4b97407`；文本内容一致，14字节差来自换行。 |
| 双端项目 ignore/include/Babel ignore 空，无miniprogramRoot；字面require闭包检查通过 | 没发现所查源码和项目配置排除该文件。不能据此证明实际开发工具编译产物完整。 |
| 完整实际首页JS闭包在原生式模块注册模型中加载22个真实模块，Page注册成功，0网络请求；不使用loadPage或业务API模块替身 | 源码可在该模型中完成注册；比仅Node语法/处理器mock多覆盖模块解析，但仍不是微信编译器或原生运行验收。 |
| 只从模块注册表移除station-view，真实首页源码立即报同一条missing-module错误，Page未注册 | 可重现相同失败形状。**不证明用户实际bundle确实缺该定义**，因为尚未取到实际JS。 |
| 父任务按日志精确bundle地址读取两次，均HTTP500、空text/javascript body，随后停止 | 没有拿到实际bundle内容。不能据此判模块缺失、构建缓存损坏或灰度基础库有bug；不能换端口/参数或认证绕过。 |

20:01重新启动与19:54截图的bundle参数 `v=e010a4252d77aae61b535dea9f3a7ffc` 相同。源码未变时相同值也可能正常，**同值本身不是缓存问题实锤**。没有更早SyntaxError只是日志事实，不等于已排除所有编译问题。工具窗口标题为miniapp-delivery、根目录另有员工AppID配置等仅为线索；当前project目录未通过受支持通道读到，不能断言用户打开错项目。

## 本批有效改动

- `scripts/check-miniapp-requires.js`：检查双端实际tracked包树中的字面require、文件存在性、精确大小写、Git入库、包外路径/Node文件加载、项目忽略规则及app/page入口。缺Git或不支持的规则明确失败，不冒充通过。当前 **2包、188 JS、452 require** 全部通过；扫描结果另用本机Acorn只读核对，与实际452个字面调用一致。
- `tests/js/native-home-module-closure.test.js`：完整实际首页模块加载、同报错反例，以及新模块漏文件、大小写错误、打包排除、未入库等8项回归。只有微信平台/App注册边界被模拟，业务模块均执行真实源码。正式测试不依赖历史Git对象，适用于浅克隆CI。
- 门禁已接入 `scripts/verify.sh`、`scripts/verify-local.js`、CI定义及流程测试入口。**本任务未查询Actions、未声称远端CI成功。**

曾准备将站名展示函数移回已有display-message模块以规避新增require。收到三树文件完整的本地证据后已撤回，**没有写入本提交**。这是可选的依赖规避思路，不是已证根因修复；不能用它替代实际工具产物验证。

## 最小的下一步实际验证

1. 由本地受支持的项目详情/自动化通道读取**当前实际项目目录、AppID和编译目标**，与顾客根目录 `miniapp-user` 及其 `wx12632a1cdca9fbcc` 核对。目录/项目身份未核清前，不再让用户普通编译重试，不清小程序storage，不改后端数据。
2. 取得当前实际编译日志或可读取的JS产物后，区分：是否有 `utils/station-view.js` 的模块定义、首页require实际解析的ID是否相同、定义存在时是否更早初始化失败。需要的是模块注册/构建证据，不是再次证明磁盘文件存在。可对照 [完整源码闭包](home-module-investigation-20261024/source-closure.json)和[22模块加载记录](home-module-investigation-20261024/registration-model.json)。产物应仅本地保存检查，勿回显凭据或上传完整运行包。
3. 精确已知产物URL是 `http://127.0.0.1:37926/appservice/getmainpackagebundle.js?t=wechat&s=&v=e010a4252d77aae61b535dea9f3a7ffc`；父任务已遇HTTP500空body并停止，本云环境与该localhost不是同一机器，未尝试读取或猜测替代地址。若当前通道无法提供项目/产物，继续标记原生工具证据缺口，不能靠改源码、换基础库或上传试错宣称解决。

`GET http://127.0.0.1:8080/api/auth/me net::ERR_CONNECTION_REFUSED` 是独立的本地后端未运行现象；本批没有启动后端，也不将它作为模块白屏的解释。

## 验证与交付边界

[6个定向套件85项通过](home-module-investigation-20261024/verification.json)，包含本轮8项模块回归；站名、首页反馈、注册期和展示文案回归保持。门禁脚本/测试JS和shell入口语法通过，`git diff --check`通过。没有再跑全站视觉或财务测试，也没有执行真实登录、支付、退款、数据库操作。

受影响运行端为顾客端首页；员工端只纳入通用依赖门禁，业务源码未改。**本提交不需要重新上传小程序或后端，不应把上传当修复尝试。** 实际白屏仍待本地项目/构建模块证据定位；原生验收保持暂停。
