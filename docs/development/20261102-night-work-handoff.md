# 2026-11-02 夜间全页工作交接

本交接的业务代码基准为 `a7dd297725fd9e9016e2e5c9264eba22dc250be7`；所在后续提交仅补测试矩阵真实统计与本文件。正常拉取既有 `codex/stage-handoff-20261004`，用 `git rev-parse HEAD` 核实实际续接提交，不能用本机未提交文件代替远端基准。当前38件增量已推送；后续全页优化由父任务另派，不能与本机writer并发写同一目录。

## 已改与未完成范围

- 首页统计数字/文字间距；异常原订单与客户资产入口；欠桶台账进入既有实际还桶登记（前批40b5）。
- 本批：无桶水差异且无待执行补偿的异常可写理由核实结案，站点/状态/版本/CAS/幂等留痕；普通反馈可回复、按本站结案，所属顾客可查看，各站独立办理；蓝白卡片、主次操作和安全区样式。没有执行资金、桶、票冲销，没有外部通知。
- 待查：下方全部路由的角色可达性、待处理状态是否有真实合法操作、取消/重复/超时未知结果/回页刷新、空态/失败重试、金额未知及键盘/小屏/字体放大。列入路由不代表已逐页真机验收。
- 先查现有入口、接口、服务与账本，再接线。终态无操作可能合法；缺业务动作需真实权限、理由、幂等/并发/审计，不把“忽略”换名为“解决”，不自动补钱或抹账。

## 数据库与配置边界

v88是此前报价版本字段，用户已核对通过。v89新增 `case_handling_action`，精确DDL在 `AquaFlow-backend/sql/migration_v89_case_handling_action.sql`；只读预检在 `deployment/limited-trial/schema-preflight-v89-readonly.sql`。v89只在本机专用测试库验证后恢复，未替用户在云端执行；先缺表检查，再单条CREATE，再核对字段和唯一键。不能依赖DMC跨语句会话变量，不能DROP重建或覆盖旧记录。

用户已手动导入的215条云端演示数据已核验，不得重导、清理或创建重复账号。当前体验环境用户已确认全部测试，但这不授权任意写入/切流/改权限；不将体验版等同隔离数据源。云端版本、流量、v89及手机状态仍需实际证据。不要复制本机凭据、测试密码配置、application-local.yml、私有项目配置、备份或签名URL到仓库/云工作区。

原4件 `tests/js/sdk-synthetic-fixtures/{common,order}-runtime-body.{bin,json}` 是用户既存脏夹具，不在本批提交，不带入云端续接。JS云上传测试会重新生成时间戳；在本机结束后按本机原始快照恢复，不用HEAD覆盖用户脏状态。云端干净克隆使用版本库夹具，测试生成的变化也不要提交。其余未跟踪截图、预览、临时输出均保留原地且不归档。

## 测试与环境

已读本仓 `AGENTS.md` 及 `.dsh/skills/aquaflow-known-traps/SKILL.md` 后再修改。Java17、Node、Python3、MySQL8.4、Bash；真实CI定义在 `.github/workflows/ci.yml`。云端不要凭库名含test就允许重建。

无数据库的相关命令（仓库根目录）：

```sh
node tests/js/run-all.js
python3 audit_wxml_handlers.py
python3 audit_comments.py
python3 audit_js_syntax.py
python3 audit_wxss_selectors.py
python3 audit_scenario_matrix.py
node scripts/check-tracked-inputs.js
node scripts/check-miniapp-requires.js
node scripts/check-miniapp-text.js
node scripts/check-prod-config.js
node scripts/check-api-doc.js
node scripts/check-sql-catalog.js
node scripts/check-ledger-claims.js
node scripts/check-gate-parity.js
node scripts/check-pending-decisions.js
bash scripts/scan-secrets.sh
```

Windows需使用已安装Git Bash，设置 `AQUAFLOW_TEST_SH` 并把Git bin/usr/bin加入PATH；不能用WindowsApps里的bash冒充已成功运行。完整本地JS225套/4722项已通过。真实CI #41466028708 后端257类2048项全过，唯一失败为新增测试后的旧矩阵件数；已按原始XML及原统计函数补正，完整后续CI须单独核实，不能把定向15+4当完整全量。

后端集成测试会清空目标库。只允许新建的隔离MySQL容器或已核实安全空闲、可恢复备份的专用测试库；本机原库已恢复并核验记录数与checksum。照CI共享同一精确目标的测试护栏准备schema；在后端目录运行 `./gradlew test --no-daemon`，本批定向可用 `--tests '*CaseHandlingServiceTest' --tests '*CaseHandlingIntegrationTest'`。不要直接运行会重建库的 `scripts/verify.sh` 或手工填允许变量来绕过目标核对。并发Gradle必须隔离build及project-cache目录，不删除他人的build。CI还验证bootJar无本地配置及真实生产配置启动矩阵。

## 品牌与业务不变量

保留艾泉原A/Q与艺术字；清爽蓝白，主蓝约#1677FF、浅灰底、浅分组，禁止重设计logo或改为商城大海报。金额未知不当0；付款/桶票/押金唯一账本入口、权限站别、订单状态与库存预留不变量以代码和AGENTS为准。不因美术修改金额/资产/付款方式/退款规则，不新增免鉴权登录或扩大平台文件权限。

更新顺序为v89核验准备→后端全部承流实例→顾客端和员工端分别编译上传。源码包体只是估算，顾客主包接近预算，实际限制必须由微信工具确认。当前未完成原生真机的小屏、多位数字、放大字体、键盘、安全区、图片及实际账号操作验收；不能写“已真机验证”。

## 完整注册路由清单

顾客端为顾客/公共登录等页面；员工端同时包含站长、配送员与未绑定登录流程。`pages/station-mgmt/`按现有站长守卫处理，其余角色必须逐页核对实际守卫与接口，不能凭端名将47页全部开放给配送员。

## miniapp-user（27页）

- `pages/home/index`
- `pages/home/notice`
- `pages/order/list`
- `pages/order/detail`
- `pages/order/create`
- `pages/order/success`
- `pages/mine/index`
- `pages/login/index`
- `pages/mine/about`
- `pages/mine/edit`
- `pages/mine/agreement/index`
- `pages/mine/account-data/index`
- `pages/mine/company`
- `pages/mine/asset-claims/index`
- `pages/address/list`
- `pages/address/edit`
- `pages/barrel/index`
- `pages/barrel/purchase`
- `pages/deposit/records/index`
- `pages/exception/list/list`
- `pages/service/index`
- `pages/shop/index`
- `pages/template/index`
- `pages/ticket/index`
- `pages/notice/index`
- `pages/payment/records`
- `pages/case-handling/index`

## miniapp-delivery（47页）

- `pages/login/index`
- `pages/mine/agreement/index`
- `pages/mine/account-data/index`
- `pages/role-select/index`
- `pages/bind-wait/index`
- `pages/home/index`
- `pages/order/detail`
- `pages/order/complete`
- `pages/mine/index`
- `pages/coordination/index`
- `pages/barrel-records/index`
- `pages/my-earnings/index`
- `pages/history/index`
- `pages/transfer/index`
- `pages/report/index`
- `pages/settings/index`
- `pages/mine/edit`
- `pages/station-mgmt/create-station/index`
- `pages/station-mgmt/apply-bind/index`
- `pages/station-mgmt/index`
- `pages/station-mgmt/business-waiting/index`
- `pages/station-mgmt/dashboard/index`
- `pages/station-mgmt/orders/index`
- `pages/station-mgmt/customers/index`
- `pages/station-mgmt/customers/detail/index`
- `pages/station-mgmt/customers/adjust/index`
- `pages/station-mgmt/customers/adjust/edit/index`
- `pages/station-mgmt/customers/adjust/detail/index`
- `pages/station-mgmt/products/index`
- `pages/station-mgmt/station-status/index`
- `pages/station-mgmt/station-info/index`
- `pages/station-mgmt/delivery-config/index`
- `pages/station-mgmt/place-order/index`
- `pages/station-mgmt/receivables/index`
- `pages/station-mgmt/inter-station/index`
- `pages/station-mgmt/payroll/index`
- `pages/station-mgmt/ticket-packages/index`
- `pages/station-mgmt/gross-profit/index`
- `pages/station-mgmt/barrel-return/index`
- `pages/station-mgmt/owed-barrels/index`
- `pages/station-mgmt/exceptions/index`
- `pages/station-mgmt/customer-feedback/index`
- `pages/station-mgmt/payments/index`
- `pages/station-mgmt/staff/index`
- `pages/station-mgmt/staff/profile/index`
- `pages/station-mgmt/asset-claims/index`
- `pages/case-handling/index`

## 云端接续批次一（2026-11-02）

远端文档提交 eae65ef 已核对并正常快进；云端成为唯一业务写入方。修复异常补偿成功后重复调用执行接口而误报失败：现在仅发送一次 handle，网络结果未知只查原异常，不追加补偿；员工画像增加返回刷新、读取代次和身份失效保护；顾客无需补偿结案显示后端明确结果及完成进度。定向 night-workflow-regressions 7项、df-package-flows 31项、workflow-entry-regression 8项通过。首次完整JS运行有3套在既有WXML表达式求值100ms上限超时，非业务断言失败，正在独立复跑，不登记为完整通过。尚无本批数据库变更。

## 云端最终续接结果（2026-11-02）

逐页结果与真实保留理由见[74页报告](../audit/20261102-night-pages.md)，操作人员使用[一页更新说明](../../deployment/limited-trial/NIGHT-UPDATE-20261102.md)。74页均审查，25页有本轮源码改动，49页保留其已有任务分组、异常/空态和恢复能力；原生视觉仍0/74，不把源码回归当真机通过。

已落实补偿一次事务/未知结果只读核对/并发拒绝刷新、员工画像返回与代次保护、VERIFIED顾客展示、跨站现场责任提醒、匿名安全的原事项摘要/阻断原因、历史收益日期和本人工资单游标/明细；20页补安全区，具体数字和文字样式见逐页表。无新增迁移，不修改金额、赔付、桶账入口或访问权限。

本地最终前端227套/4740计数项通过；后端cleanTest全量258类/2058项通过，失败/错误/跳过均0，JAR不含本机开发配置。15项独立静态门禁、208个JS解析、事件绑定、WXSS和敏感信息扫描通过，矩阵由本轮完整XML正常生成。初次自建测试库名不满足模拟支付护栏，整轮未计通过；随后在允许命名的新隔离MySQL8.4内存容器中重新cleanTest全量通过，不改护栏、不使用用户业务库，专用测试使用本轮合成凭据。

已推送第一批43f34dc的[完整CI](https://github.com/rumiasu/AquaFlow/actions/runs/41472043591)成功；后续0b5f66c、b13d7f6、7df3d42、8533744、b2c0378分别记录处理上下文、历史工资、有限投影预算、页面安全区和并发拒绝刷新。最终提交与最终CI/文件哈希以交付包MANIFEST为准，不能用第一批结果代替。

交付只准备准确源码、JAR、v89只读预检/单条建表和更新步骤；不自动部署、不操作云端数据库、不重导215条演示数据。

矩阵方法引用核对发现三处同一行的@Test注解未被索引，已统一为独立注解行；门禁保持原样，相关两类后端定向复验17项通过。完整258类/2058项XML保留，不用定向XML覆盖全量。

## 2026-11-03 两端申请入口收敛

给接手续修与更新两端体验版的工程师：本批从2e205c28b5db继续，只改两端前端与回归。

- 两端“我的”移除常驻“个人信息与账号申请”；顶部普通资料编辑不变。
- 顾客在“关于”、员工在“设置”进入时才查询本人历史，有记录显示“申请记录”；没有记录不显示，失败明确提供重试。主“我的”页不增加请求，不缓存跨身份的历史结果。
- 历史入口使用原路由的`?view=records`只读模式，只读取列表/详情；不请求受理设置、不展示申请表、不提交申请。返回刷新、离页/换身份回执隔离，退出后清空并提示登录。
- 原页面、接口、服务配置与数据库记录保留，没有后端修改、没有迁移；既有申请登记和草稿流程仍由原回归覆盖。
- 新增两端定向回归16项；完整前端228套/4756项通过，之后补充退出只读页保护的定向回归通过。静态、绑定、模块依赖及场景矩阵检查通过。最终提交CI结果以交付回执准确链接为准。
- 发布只需更新两端开发者工具原项目；此批不需要替换后端或执行SQL。尚未进行微信开发者工具/真机验收，未部署。
- 本机同步前先检查工作树；保留自己的未提交内容，确认本地HEAD是远端开发分支祖先后正常快进，不重置、不强推。旧2e205交付包/晨间还原包不含本批前端改动，不能据此称已同步。
