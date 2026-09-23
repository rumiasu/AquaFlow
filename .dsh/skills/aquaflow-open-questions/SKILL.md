---
name: aquaflow-open-questions
description: AquaFlow 的「待确认 / 未验证 / 待拍板」清单与核实手法 —— 已弃用表的实际停写状态、水厂端残留、ManagerOrderController 的记忆冲突、微信测试号是否支持 wx.login、测试号「小程序 / 小游戏」之分、工作区那批未提交改动，以及对账「押金穿底」与订单取消链两条口径的**最终结论**（都已收口：前者按收窄判据实施、后者裁定不实现只留护栏注释；唯一残留一问是"配送员/站长能否取消已送达单"）。当你要动这些区域，或某个结论只在旧会话记忆 / .workbuddy 里出现过时加载。
whenToUse: 要改已弃用表(customer_owed_barrel / customer_barrel_in_transit)的写入点；要全库检索 factory 水厂端残留；要接微信测试号登录或排查 40013；要判断 .workbuddy/memory 里的结论是否还成立；要动工作区那批未提交改动时；要动 ReconciliationService 的对账等式（尤其 b3d / E6 押金穿底）或排查「日结天天报不平」时；要动订单取消链（PaymentServiceImpl.refundOrder / 取消申请审批 / isCancellable）或客户桶权益撤销时。
---

# AquaFlow 待确认清单

> 从仓库根 `AGENTS.md` §9 拆出来（2026-09-17）。**AGENTS.md §9 只留仍在生效的判据**，完整条目（含已结案的结论）在这里。
> 维护规则：**能验证才写进 `AGENTS.md` 正文，不能验证就留在这里**；证实一条就上移进正文、并删掉这里那一条。
>
> ⚠️ 之所以拆出来，是因为 `AGENTS.md` 有体积预算（≤ ~20 KB，超出会被 agent 的指令预算截断）——
> 这里的内容是"执行前才需要"的，不是"每次动手都必须知道"的判据。

## A. 仍未取得确证（执行前必须自行核实）

### A1. 「水厂端已彻底移除」是 2026-09-11 的复核结论

依据是 `sql/README.md` + `docs/README.md`，**此后未重新全库检索 `factory` 残留**。

**核实手法**：`grep -ri "factory" --include=*.java --include=*.xml --include=*.sql --include=*.js --include=*.wxml` 覆盖后端、两个小程序、`sql/`；注意 `FACTORY_ADMIN` 角色与整条水厂端链路（控制器 / 服务 / 表 / 小程序页）要一起看。证实后把结论写进 `AGENTS.md` §1。

### A2. 已弃用表的实际停写状态未逐一复核调用链

对象：`customer_owed_barrel`（旧文档称已停止写入；该文档已归档，已移出仓库）与 `customer_barrel_in_transit` 的**写入点**。

**注意**：`customer_barrel_in_transit` 是**仍在用**的（桶的「配送中」口径就读它），要确认的不是"是否停写"，而是**写入点是否只在 `BarrelLedgerService` 这条唯一入口**。`customer_owed_barrel` 才是打算停写的那个，欠桶改读 `customer_barrel_over`。

**核实手法**：`code_refs` 查 `CustomerOwedBarrelMapper` / `CustomerBarrelInTransitMapper` 的 callers，再看 XML mapper 里的 `insert` / `update`（**注解 SQL 与 XML 都要查** —— `AGENTS.md` §1「首单」那条就是只 grep `*.java` 漏掉 `OrderMapper.xml` 造成的误判）。

### A3. `ManagerOrderController` 的记忆冲突

文件确已删除（不存在，且有 `ManagerOrderControllerRemovedIntegrationTest` 守着），但 `.workbuddy/memory/MEMORY.md` 仍把它列为「仍未做」的高危项。

**判据**：**该记忆条目已过期，不代表当前存在该风险**；但也不排除有其他等效写入口（例如绕过编排服务直接改订单状态的新路径）。

**核实手法**：确认它真的没有"换个名字回来"—— 搜所有 `@RequestMapping` 里对订单状态/支付状态的直接写库，而不是只看类名。

### A4. 开发者工具「测试号」是否支持 `wx.login` / `jscode2session`，尚未实测

官方对测试号只承诺「开发测试 + 真机预览」（<https://developers.weixin.qq.com/miniprogram/dev/devtools/sandbox.html>），**没有明文承诺登录能力**。当前本地**只有客户端**用测试号（`miniapp-user`），员工端 `miniapp-delivery` 用的仍是项目原有 appid（正本见 `miniapp-*/project.config.json`）。

**核实手法**：把两对 appid/secret 填进配置后，**真机**点微信登录，看后端日志里 `微信code2Session响应[CUSTOMER]` / `[STAFF]` 的返回。

**已知**：测试号**确定不能上传代码 / 发布 / 设为体验版**。**若不支持登录，`dev-login` 是唯一可用登录路径**（它不经过微信，不受影响）。

### A5. 测试号分「小程序」与「小游戏」两种，不可混用

申请测试号页面会各给一个 appid。把**小游戏**测试号的 appid 填进小程序项目（`compileType: "miniprogram"`），开发者工具会**报错、无法正常编译**。

**判据：填之前先确认拿到的是「小程序测试号」。**（配送端曾误填小游戏测试号，已改回原 appid。）

## B. 已结案（保留结论，避免重复讨论）

| 原待确认项 | 结论 |
|---|---|
| `scripts/verify.sh` 引用的审计脚本被 `.gitignore` 忽略 | **已解决（2026-09-14）**：已入库 4 个并开 `!` 例外，都有真实退出码 —— `audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py`（悬空 javadoc 检测）。`api_audit.py` 确不存在，不必再找。 |
| `docs/README.md` 称归档文档在 `.docs_trash/`，但该目录不存在 | **已解决（2026-09-15）**：`docs/README.md` 已重写为只索引**实际存在**的文档，移除了 `.docs_trash/`、`roadmap/roadmap.md`、`测试阶段问题清单.md` 等悬空条目。该目录确认不存在，不再恢复。 |
| 根 `README.md` 仍把某端列为在维护的第三端 | **已解决（2026-09-14）**：已改为「两端原生小程序」，并删除 Vue3 / `station_payment_config` 等过期内容。 |
| 真实库表数与基线的一致性未复核 | **已完全对齐（2026-09-16）**：真实库 39 个对象 = 基线 39 张表，0 视图 / 0 备份表，两个方向差额均为 0（见 `AGENTS.md` §8.13）。计数以 `Select-String -Pattern '^CREATE TABLE' sql/schema.sql` 实测为准。 |
| 测试件数口径混乱（曾记 28 例 / 77 例 / 90 例） | **已更新（2026-09-17）**：在按 `schema.sql` 全新重建的库上实测 215 用例 / 44 类 / 0 失败。`docs/audit/test-harness.md` 的 28 例、更早的评测（已移出仓库）的 90 例均为更早口径，仅作历史。**正本是 `build/test-results/test/*.xml`**（`AGENTS.md` §5 有统计口径的坑）。 |
| 真实库是否含真实业务数据、能否直接验证迁移 | **已探测（2026-09-14）**：`aquaflow` 有数据（`payment_record` 17 行 / `barrel_record` 4 / `deposit_record` 7 / `ticket_record` 4），**可直接用于验证迁移**；v23/v27/v28 即在其上跑完，全程先 `mysqldump` 备份、后用「事务内造数据 → 验证 → ROLLBACK」，数据零改动。 |
| `application.yml` 曾被历史提交且含真实密钥 | **已结案**：真实密钥已轮换（仅公开的 AppID 与历史相同，不构成凭据泄露）；`application.yml` 已改为 `${ENV:}` 占位并解除 gitignore 入库，真凭据只留本机 `application-local.yml`（gitignore，**禁止回显**）。 |
| 「新客户下不了第一单」（2026-09-20 第二轮实测又报了一次） | **已结案（2026-09-20，用户裁定）——不要再当 P0 报**：新客户的**自助首单走微信支付**（本地 `app.payment.mock-wechat-pay` 点击即成功；生产为真实渠道接入位）。货到付款的定位是「给已建立关系的客户赊账」，**不承担首单**；水票要先线下确认收款。所谓"三通道全灰"只发生在「模拟渠道关 **且** 真实渠道未接入」的**部署状态**，不是判据缺陷。判据注释正本：`PayMethod.availableMethods` 的微信项 + `CustomerController.updateOfflinePaymentConfig` 的 javadoc。 |
| 水票支付时结算页水费/总价与现金、微信不同（曾判为"计价双轨"） | **已裁定：不改（2026-09-20，用户口径）**：水票的钱在**买水票时**就付过了，下单用水票**不再付钱**（不产生第二次收款），所以水票口径的水费本来就跟现金/微信不是一个数 —— 这是设计。**不改显示、不改 `PriceUtil` 的水票单价链**。别再报「切换到水票支付总价会变」。 |
| 货到付款点「确认」下不了单 | **已修（2026-09-20）**：`miniapp-user/pages/order/create.js` 的 `onOfflineConfirmOk()` 原来只 `setData({showOfflineConfirm:false})`、不继续提交，而 `onSubmit` 的闸门又要求该标记为 true ⇒ 现金单永远提交不出去（闭环）。现改为直接 `this.onSubmit()`（标记在真正开始提交时才清零）。 |

## C. 待拍板（需产品裁定）

> 规则见 `AGENTS.md` §0.6：**同一问题只保留一处正本**。本节每条只写「问题一句话 + 正本在哪」，
> 细节、两种选择的差别、拍板后的落地位置都在正本里 —— 不在这里复述，免得两处各自漂移。

| # | 悬着的问题（一句话） | 正本 | 现状 |
|---|---|---|---|
| C1 | 对账「押金穿底」（V1 `b3d` / V2 `E6`）要不要排除「现金单送达未收款」窗口？ | 当时的交付报告（已移出仓库）§6 **T1** | ✅ **已收口（2026-09-21 实施）**：按用户口径「货到付款才会出现；货已送到钱还没收是可以允许的」取方案 A 的**收窄版** —— 判据 = 「权益 > 押金余额 **且** 该客户有已过应付日期、仍未结清的账单」，只排除"仍在履约且未收款"的在途情形，**没有一刀切**（一刀切会把真穿底一起遮掉）。代码里**已无** `TODO(待拍板)`，三处 SQL 抽成 `depositShortfallSql`，用例侧的**自失效豁免已删除**并改用例 `overdueUnsettledOrderIsReportedAsShortfall` 防回退。**不再是待拍板项。** |
| C2 | 取消一张**已送达**的单时，已经交付出去的桶权益怎么记账？ | 同上 §6 **T2** | ⛔ **已收口：不实现**（不是漏做）。T3 已把入口堵死（`isCancellable` 只剩 `{待配送(1), 配送中(2)}`）⇒ 场景**不可达**，补代码会是永远执行不到的死代码。改为在 `PaymentServiceImpl.refundOrder` 的桶账块留**护栏注释**：将来若放松取消门槛，必须同时补 ① `consumeLots` 撤销送达时建的权益 ② `customer_barrel_over` 加 delivered（记欠桶），并指明哪两条用例会因此变红。**用户已裁定（2026-09-22）**：「只有配送端可以取消配送中的订单」⇒ 已送达不可取消、配送中的实际取消动作只能由配送端做，**T2 确认不做**。 |
| C3 | 归属站能不能对「已被别站接单、**配送中(2)**」的单「取消外派」（召回）？ | 同上 §6.11 | ✅ **已收口（2026-09-22 产品裁定）**：「外派出去的本单就不归本站管了，只能接单站管，联系等都是接单站执行」⇒ **不能召回**。`OrderWorkflowServiceImpl.cancelDispatch` 已改为只收 `待配送(1)`（在池中 / 已指定但对方未接单），拒绝文案指向"请与接单站联系"；用例 `CrossStationDispatchIntegrationTest.recallIsRefusedAfterTheOtherStationAccepts` 钉住。§8.18「状态只前进」的告警同时消除。**联系客户由接单站执行这条本来就成立**：`CustomerProfileMask` 只抹归属站的客户档案，接单站拿得到订单快照里的 `receiverName`/`receiverPhone`。 |

