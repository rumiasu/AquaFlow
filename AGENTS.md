# AGENTS.md — AquaFlow 仓库级 AI 协作指令

> 适用范围：仓库根及全部子目录。DSH/Claude 系 agent 读本文件作为**操作型契约**（命令、入口、禁改、坑）。
>
> 冲突优先级（高 → 低）：**§0 事实基准 + §1 领域不变量 → 本文件其余 → 根 `README.md` 与 `docs/**`（`architecture/`、`development/` 已逐条核对；`design/` 规格与其余仅作线索）**；`docs/` 下**已无**其他自动注入的指令文件（`docs/AGENTS.md` 已作废移出，见 §10）。
> 证据标注：【仓】= 已在仓库文件中直接核对；【会】= 历史会话日志或 `.workbuddy` 记忆。
> **`§8.N` 的落点**：本文引用的 `§8.15` / `§8.26` 这类编号指 skill **`aquaflow-known-traps`** 的条目（§8 全文已搬去，内容与编号都没变；本文 §8 只留指针）。

## 0. 事实基准（最高优先级）

1. **一切以代码为准**。`README.md`、`docs/**` 用于说明现状与目标，现行正文已按 2026-10-01 重核；历史文档与机器记忆仍可能过期。判断实际行为以源码、表结构和验证为准；未实施产品目标不得伪称已实现。【仓】
2. 唯一可信来源顺序：**Java/WXML/JS 源码 → `AquaFlow-backend/sql/schema.sql` → 通过的集成测试与实际接口行为 → 仓库 Markdown**（本文 §0）。【仓】
3. **删除类改动协议（强制）**：删代码 / 端点 / 文件前先只读排查，**报备必须给六项**：① 核实到哪一步（几遍、什么方法）；② 证据（逐条 `文件:行号`）；③ **原来为什么存在**；④ **删掉会怎样**（连带测试 / 文档 / 常量 / mapper）；⑤ **推荐删或留 + 理由**；⑥ 风险等级。**登记表正本**：`docs/audit/删除登记表.md`（含已确认删除的端点清单；旧判定会过期）。**核实纪律**：光跑 `api_reverse_audit.py` 不算（既有假阳性也有假阴性）。**判据：自己 grep 出所有出现位置，逐个确认是"真调用"还是仅"常量定义 / 文档 / 注释 / 测试断言"—— 定义 ≠ 调用**；零引用确认后再跑全量测试留基线。**查引用必须覆盖 `.js` / `.wxml` / `.java` / `*.md`**：小程序调用常只写在 `.wxml`（`data-url` + `bindtap`），只 grep `*.js` 会把有入口的页面误判成孤儿页；还要**顺 handler 往下看一跳**确认真的会跳。破坏性操作（删文件、改 git 历史、清库、跑历史迁移 SQL）先报证据与影响并等确认。
4. **数值 SSOT —— 有正本就不要在本文件重述**：表定义 `sql/schema.sql`、迁移顺序 `sql/README.md`、枚举值 `constant/*.java`、API 实路径 `controller/**` 注解、测试件数 `build/test-results/test/*.xml`、**结构问 `code_map`（别手写会漂移的计数）**。【仓】
5. 仓库内文档的入口是 `docs/README.md`；当前与历史状态查该索引及 `docs/documentation-status.md`；核心待拍板正本是 design/16 §12，C-01 详情仍在 §9.3。【仓】
6. **「待拍板」必须当场落成 TODO（强制）**：改动 / 结论**取决于产品拍板**时，不能只在对话里问一句。当场三件事：① 在**代码或文档对应位置**留 `TODO(待拍板)`，写清**问什么 / 两种选择的差别 / 拍板后改哪里**；② 在文档「待拍板」清单登记（同一问题只留**一处正本**）；③ 回复用户时明确列出。**判据：只读代码与文档就知道"悬着一个决定、卡在哪、怎么落地"。** 典型悬置点：营收与计价口径、状态机语义、是否新增字段 / 状态、跨模块取舍。

## 1. 项目概览与领域不变量

- **业务**：桶装水（18.9L）配送管理系统，面向**水站**（站长 + 配送员）；目标是可真实上线的企业级系统。
- **在维护的端只有两个原生微信小程序**：`miniapp-user`（客户端）、`miniapp-delivery`（站长 + 配送员）。**没有可维护的 Vue 管理后台**（`AquaFlow-frontend` 已删，只剩 `archive/legacy-web-frontend` 留档）。
- **角色**：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）、客户（微信 openid）。`staff.role` 只有前两个；`FACTORY_ADMIN` 与整个水厂端已在 DB/后端/小程序三处彻底移除。

### 1.1 不可凭直觉改写的领域不变量

> **2026-10-01 v71 调整（优先于下列旧首单/权益/退款限制）**：用户已授权 design/16 §11 全部实施。新增押金由归属站实际收款后生效；2026-10-03 v74 修正为水与新增押金可同次付款、分项记账，也保留只买押金路径；E=已付桶容量、H=E+over 实物，买 q 份令 E+q、over−q、H 不变；订单/退还按数量分配，购票看 E，兑水看可用容量，默认回桶来自分配的 pickup_qty。新申请须批准→必要的新增费用/站方新安排授权→实际交桶→原渠道实际退款；2026-10-08 已批免费原安排免二次确认，不补写同意时间，闲置未领容量无需交桶；消费退款不撤独立资产，交付前退款取消履约。确认拒付保留欠款、不扣押金，跨站资产冻结须资产站核实。外派本单总报酬接单冻结，逐商品净桶与桶款另交接；已付款冲销留追收。剩余真实购票批次可按原款退出，赠票/推断批次人工核实。新旧按永久保留的分配/申请凭据区分，历史单继续原规则；下述“权益=已到手”“首单送达建权益”“无订单购票不能退”仅限历史路径。正本 architecture/02 现行正文、design/16 §11–12、sql/README.md；发生新业务后不得直接回旧代码或删新表。真实业务库尚未迁移，真实微信渠道仍未接入。


- **客户是全局身份**：`customer` 表**没有也永远不加** `station_id`；订单/桶/水票/押金一律按 `(customer_id, station_id)` 隔离。**经营归属** = 「绑定（`customer_station_config`）∪ 本站订单」并集（口径不是字段）；`owner_station_id` 只属于 `product`（NULL = 通用商品库）。【仓】
- **三站语义**：`orders.station_id` = **归属站**（客户选定、**定价方**，`DeliveryFeeUtil` 按它算费）；`delivery_station_id` = **履约站**（库存、配送员清单、计件工钱记它）；`settle_station_id` = **结算站** = 本单营收（水费+配送费+楼层费）归谁（v47，正本 `sql/migration_v47_order_settle_station.sql`）。取值：下单 = 归属站；**抢单/定向外派成功 = 履约站**；召回/退回池/指定退回-同意 = 回归属站。【仓 `StationUtil.settleStation`】
  - **钱认结算站**：看板、毛利（**成本 join 也按结算站**）、应收与核销、确认收款判权、退款判权（`PaymentController.requireRefundStation`，旧名已废）、`payment_record.station_id`（`recordCashCollection`）。⚠️ **客户资产认归属站**：押金账户/水票/桶权益（`customer_deposit_account`/`ticket_*`/`customer_barrel_*`）一律不动。**SQL 读 `coalesce(o.settle_station_id, o.delivery_station_id, o.station_id)`**（末级防御；写入必须落 `settle_station_id`）。
  - **抢单池 / 指定外派 = 跨租户可见面**：下发给别站站长的**只许**「钱货去向」文案与快照金额（`DeliveryConsoleServiceImpl.feeInfoOf`），**不许**带归属站成本/库存/联系方式，**不许**下发客户画像（`customerName`/`customerPhone`）；既无绑定又无本站订单的客户，画像端点一律不可见。**含押金/桶权益的单禁止进抢单池**（直接拒）；**定向外派双方确认**（外派方勾知悉风险 → 接收站接单再确认，两处留痕），不涉押金的普通单不加摩擦。**被接单后这单归接单站管**：归属站只能**未被接单**时召回（`cancelDispatch` 只收 `待配送(1)`，放行 `配送中(2)` = 状态倒滚）；接单后拒单/解决/送达/收款按**履约站**判权，归属站只剩「指定退回」审批。**联系客户由接单站执行**：快照 `receiverName`/`receiverPhone` 照发，抹的只是**归属站客户档案**。**认领后同样不含画像**：履约站配送员面（待接单/配送中/今日完成/历史/回桶记录/转给我的单）与站长端「员工画像 · 当前进行中」只带订单快照，**唯一实现** `util/CustomerProfileMask`（只抹 `customerName`/`customerPhone`；Map 形态调用方 SQL **必须显式 as 出** `stationId`/`deliveryStationId`，读不到即静默不抹）。
  - **`payment_record` 待收款流水跟着结算站走**：站别在「发起收款」时写死，换站由 `movePendingToStation` 搬（**只搬 `PENDING`**）；收款走 `confirmPendingToPaid` **就地确认**。`uk_payment_active_order` = **一单一条活跃流水**，已有待收款再插 PAID 必撞 1062、整笔送达回滚。
  - **只有收到钱的单才进站长/配送员视野**：判据两条 —— `payment_status = 2`，或 `payment_method = 2`（现金＝货到付款；没开通时**下单即被拒**）。水票在支付请求里扣，**别**改成下单时扣票（票不够的客户连单都下不出）。**判据三处必须一致**（两张列表 SQL + 接单/分配闸门）；未付微信单"当不存在"，回调置 2 即出现（`TODO(微信支付接入)` 在那三处）。转单状态一律查 `order_transfer`，`special_note LIKE` 只许出现在「外派」列表（`listDispatchedOrders`，且必须排除 `[指定退回待确认]`）
  - **未分配的单不进配送员视野，配送员只能接「派给自己」的单**：`GET /api/delivery/orders/pending`（本站未分配）**站长专属**；配送员「待配送」只有 `assigned-to-me`。**接单闸门与列表一起守**：`OrderWorkflowServiceImpl.acceptOrder` 对 `AuthContext.isDelivery()` 要求 `delivery_staff_id` 非空（否则拿 id 编造照样接走）；**站长不在此限**。用例 `PaidBeforeDispatchIntegrationTest` + `DeliveryConsoleAndSelfServiceIntegrationTest.unassignedOrderCannotBeSeenOrTakenByDelivery`；链路用例里配送员接单必须先 `assign`（`CashOrderLifecycleScenarioTest`）。
- **订单状态**以 `constant/OrderStatus.java` 为准：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`（连续编号；历史 1/3/4/5/6 已废弃）；`isValidTransition` 定义合法迁移，但并非所有命令已接线；实际守卫看专用 CAS 与流程门槛，F-10 仍 OPEN，不能宣称统一拒绝。
- **支付方式**以 `constant/PayMethod.java` 为准：`1 微信 / 2 现金(货到付款) / 3 水票`（**水票 = 扣票成功即视同已付**）。**微信渠道未接入**：本地只有模拟渠道（`app.payment.mock-wechat-pay`，生产关闭）= **新客户第一单唯一自助通道**；判据见 `PayMethod.availableMethods`（**别再当"新客户死锁"报**）。
- **支付状态**以 `constant/PaymentStatus.java` 为准：`0 未付 / 1 待收款 / 2 已付 / 3 已退款 / 4 已取消`；唯一真值是 `orders.payment_status`。
- **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源 `customer_barrel_lot.remain_qty`，`customer_barrel_over` 可为负（= 水站暂存）。
- **新模型桶数**：E=已付有效权益，H=E+over=客户实物，R=订单/退还数量占用，可用量扣除活跃用途及遗留占用。买 q 份令 E+q、over−q，H 不变；购票看有效权益，兑水看可用量；可用容量不足时，客户明确确认逐商品数量和价格后随水单补购押金，也可独立办理或等待已有占用释放。未收到押金不形成生效容量，现金到门必须收齐后激活并交付；已付未交付取消只退本单新增且尚未被占用的容量，先前独立资产保留。旧“权益送达入账/持有含配送中/shortage 收押金”仅用于未采用新凭据的历史路径，不能指导新单。正本 `docs/architecture/02-领域模型.md`。
- **按商品桶汇总**必须包含权益、遗留配送中和 over 的并集，不能只遍历资产行；还桶物理上限认 H，不认展示的含配送中持有数（历史陷阱 §8.16）。只买权益未下水单也有资产，不得因没有本站订单拒绝已绑定客户查询。
- **退桶与欠桶互斥**：按商品先还清欠桶，不跨商品抵。新申请占用闲置数量，批准→必要的新增费用/站方新安排授权→实际收桶→原渠道实际退款；免费原安排免二次确认，不将未操作记作同意；未领取容量无需交桶。存量申请仍走 `1→2→3`，收到桶不等于退钱。`BarrelReturnGuardIntegrationTest` 守旧路径；人工撤权益仍须异常/调整单留痕。实际退款登记/争议口径见 C-07，不新增强制资金审核或上传。
- **首单标记仅属历史模型**：`first_barrel_order` 写入须看桶权益，不用票/押金等任意资产替代；旧 `completeDelivery` 为真跳回桶核对，查写点覆盖 XML。新单按分配的领取容量免回桶，不按客户整单“第一次”跳过。见 `OrderEntryAndInjectionIntegrationTest` 与独立业务用例。
- **回桶默认值逐行算**（2026-09-26 产品口径：混合单只填已有的桶）：每行桶装水明细 `默认回桶数 = min(送出桶数, 占用)`；**本批新买押金的桶在 PENDING 里、不算**。唯一实现 `BarrelService#returnPlanOfOrder`，经订单详情下发（`barrelItem` / `suggestedReturnQty`），**前端不得自己推**、**不许拿送出桶数当"该回数"**（逼出"少回收"假异常）。回桶只对**桶装水**成立（`util/BarrelScope`）。正本 `docs/design/18` §2.4。
- **支付状态只前进、不倒滚**：`0/1` = 钱还没到手，`2` = 收钱的结果，`3/4` 是终态。现金单**下单即 待收款(1)**；**发起收款**（`createPayment`）与**送达未收款**（`completeDelivery`）**不许改写它**；唯一写 2 的入口是 `OrderMapper.markPaidIfCollectable`（从 0 或 1 迁入，绝不复活 3/4）。**禁止 `updatePaymentStatusIf(..., UNPAID, PAID)`**（现金单现值 1，CAS `expected=0` 恒不命中），未收款时不动 `payment_status`。`DashboardMapper` 待收款口径 = `payment_status=1 且未取消`。
- **CAS 改状态有「两派」参数顺序，靠名字区分**：`updateStatusIf(id, 期望, 新)`（`OrderMapper` / `OrderBarrelExceptionMapper`）vs **`updateStatusTo(id, 新, 期望)`**（`PaymentRecordMapper` / `StaffPayrollMapper`，原同名方法顺序相反）。**`To` 第二参是目标，`If` 第二参是期望；写前核对 SQL，传反会静默命中 0 行。**
- **登录类端点必须按 IP 限流**（`interceptor/RateLimitInterceptor`）：锁定按**用户名**计数可被换用户名绕开（撞库/枚举），**两层正交、按来源 IP 那层不可省**。覆盖 `/api/auth/{login,wx-login,wx-login-staff,dev-login,refresh,change-password}`；计数**按 IP 聚合**；超限 **HTTP 429 + `{code:1,message}`**，默认 20 次/分钟（`RATE_LIMIT_*`）；多实例须先换集中式计数器（Redis）。**集成测试默认关闭**，只有 `RateLimitIntegrationTest` 用 `@TestPropertySource` 打开。
- **告警分级投递：系统故障 → 系统管理员，运营故障 → 该站站长**（`AlertRoutingIntegrationTest`）：`alert_log`（v30）+ `AlertService`，方向由 `constant/AlertType`（`SYSTEM`/`OPERATION`）决定，**不靠字符串比较或调用方自觉**。SYSTEM（`station_id` 必须 NULL）= 对账不平/桶异常补偿失败/未预期 500 → **不发站长**；OPERATION（`station_id` 必填）= 桶异常待处置/补偿已执行/异常被忽略 → 站长端只读 `GET /api/manager/alerts`。**三条硬约束**：① 先落库再谈渠道（没配则 `notify_status=LOGGED`）；② 落库走**独立事务**（`REQUIRES_NEW`，必须经代理调用）；③ 失败**绝不连累业务**。**红线**：系统告警没有 HTTP 入口（漏给站长 = 跨租户 + 越权知情），运维 `select * from alert_log where alert_type='SYSTEM' order by id desc limit 50;`
- **押金/桶记录方向由类型决定，调用方金额一律传正数**：`DepositType` 收敛为 `isIncrease`（`1/5/9`）/ `isDecrease`（`2/3/4/6/7/8`），**扣减类以负数落库**（`DepositRecordServiceImpl.java:56-64`）= 对账等式1（`balance == SUM(deposit_record.amount)`）的前提。`BarrelRecordType` = `6 人工调整(增)` / `9 人工调整(减)` 已纳入守恒对账 E5（`ReconciliationService.java:258-259`），不纳入则每次补录都误报。**`BarrelRecord.getStatusText()` 只对 `type=2` 下发**（其它类型照原样映射会让配送流水显示"已退押金"）。
- **三张流水表的调整场景唯一键**：`uk_deposit_adjustment`、`uk_record_adjustment`、`uk_ticket_adjustment`，配套可空列 `adjustment_id`。`ticket_record` 的 `uk_ticket_consume(order_id, product_id, source)` 在 `order_id IS NULL` 时**零保护** —— 该形状的两条活路径各自另有兜底：**站长手工扣票**（`POST /api/tickets/consume`）靠 **`idempotencyKey` 必传 + `uk_ticket_consume_idem(customer_id, idempotency_key)`**（v70，台账 F-24，与 v33/v62 同判据）；**资产调整单**靠 `uk_ticket_adjustment`。⚠️ 判据是「有没有客户端幂等键」而不是「有没有订单」：端点允许传 `orderId`，而 `uk_ticket_consume` 只挡"插第二条流水"、**挡不住已经发生的扣减**。用例 `TicketConsumeIdempotencyIntegrationTest`。
- **历史订单退款有两个编排入口，原路返回；新消费退款/票退出另见现行模块规格**（正本 `docs/architecture/02-领域模型.md` §5）：① **取消订单** → `refundOrder`（门槛 `OrderStatus.isCancellable`：**已完成/已取消不得再取消**），即取消/拒单唯一编排入口（退水票→退流水→退押金→清配送中桶→回补库存→置已取消）；② **只退这一笔钱** → `refundPayment`，**不取消订单**。**原路径返回**：水票回补 `ticket_lot` 批次（不过批次账 E8 就平不了）；现金记**负金额**冲正流水；**微信未接入 → 不许假装已退**（手工退款直接拒；取消链不阻断但 `note` 须写明需线下退款）。两条路径共用 `insertRefundRecord` / `restoreTicketsForOrder`；无订单在线购票的旧总拒绝仅限历史路径；v71 已支持真实原款批次未用票退出，赠票/未知来源不伪造原渠道。⚠️ **收款与退款都认结算站**（原 [AQ-043] 判定**已作废**）。⚠️ **押金与欠桶仍记归属站**。
- **客户端与员工端是「两套代码 / 两个小程序」，appid 各自独立**（顾客端 `wx12632a1cdca9fbcc`、员工端 `wxc6211615c79da9f9`）：`wx.login` 的 code 只能用**签发它的那个 appid+secret** 换 openid（配错只回 `40013 invalid appid`、日志无指向性），故 `WeChatLoginService.code2Session(WeChatApp, code)` **强制显式传端**（`CUSTOMER`/`STAFF`）。配置键：客户端 `wechat.miniapp.appid/secret`、员工端 `wechat.miniapp.staff-appid/staff-secret`；**员工端这对本地缺失只 `log.warn`（dev-login 兜底），`prod` 必填（缺即拒启）**。⚠️ **别照旧文档把两端 appid 混用**（正本 §9.4/§9.5）。
- **站长治理类入口**：资产调整单 `/api/manager/adjustments`（6 端点，类级 `@RequireRole("STATION_MANAGER")`）；`/api/manager/reconciliation` **只读**本站即时对账（**只有 `GET /`**；原 `POST /run` = 跨租户泄露，已删除、**不要加回**；运维记录由 03:00 定时任务落表），结果落 `reconciliation_result`。
- **公告（v32 起站长可发）**：`notice.status` = `0 下架 / 1 发布`，`type` = `1 系统公告 / 2 水站通知 / 3 活动`。**站长端管理列表必须含草稿与已下架**（带 `status = 1` 会"存草稿后列表没有它"）。状态文案由 `Notice.getStatusText()` 下发（正本 `constant/NoticeStatus.java`），前端禁止自带映射表。⚠️ **`GET /api/notices`（顾客端列表）不做站过滤**，任何顾客能看到**所有水站**的已发布公告 —— **产品裁定（2026-09-30）：暂时不做站隔离**（保持全平台可见），见 `docs/design/13` §9。
- **欠桶「只提醒、不阻断」**：原硬拦 `MAX_OWED_BUCKETS = 5` 已**移除**、**不要再加回**；下单响应 `warnings` 每次都提醒（幂等命中路径同样下发），**物理护栏不变**（占用 = 权益 + over ≥ 0）。`customer_barrel_over.owed_since`（v29）只用于展示、**不参与任何校验**；唯一维护点 `CustomerBarrelOverMapper.syncOwedSince`。站长端 `GET /api/manager/owed-barrels`（只读）。
- **迁移清单正本是 `sql/README.md`，本文件不复述；新建迁移前先 `ls sql/` 看编号，别照任何清单的最后一个数字 +1**。跨条目判据：**v32 是软状态：不阻断下单、只提示**；**v41 是唯一破坏性 DROP**（先上代码再执行 SQL；脚本自带"列内有值就中止"护栏，删列前先 `mysqldump`）；**MySQL 解析期校验列名**：删列时必须把**历史迁移里对该列的引用一并清掉**（否则解析报 1054，v41 即因此改了 v24）。
- **配送员计件工资是站长台账，走独立对账**（v37；正本 `docs/design/18`）：`staff_piece_rate`（站级单价，`product_id=0` = 该站默认价）+ `staff_earning` + `staff_payroll`（草稿→已确认→已发放）；**写入口是 `StaffEarningService`**。① 发钱的是站长不是平台（不做打款/提现，只落 `paid_time` + `operator_id`）；② 计件单位是桶不是单，按 `order_item` 逐商品计（`auto_uk` 含 `product_id`）；③ 方向由 `kind` 决定、调用方传正数，唯一例外 `ADJUST`；④ 归属站 = 履约站（`delivery_station_id`）。**收益只在 `completeDelivery` 的状态 CAS 成功之后产生**；**工钱不进客户对账**（独立等式 **E-PAY**，告警 **OPERATION**）；`staff_earning.auto_uk` 的 **NULL 是有意的**，幂等由 `uk_earning_auto`（生成列）兜底 —— 与 `uk_ticket_consume` 的"NULL 零保护"**语义相反**，别照抄。用例 `StaffEarningAndPayrollIntegrationTest`。
  - **v44「自定义工资条目」只是人工调整流水上的标签**（`EarningItemDirection`：1 加项 / 2 扣项）：不参与自动计算、不进对账；**传了 `itemId` 只收正数**、**用过的条目只能停用不能删**、`item_name` 是写入时快照；`itemSummary` **不等于**未结合计。`POST /payroll/adjust` 按「本站员工 **或** 在本站有过收益」并集放行（「我的工资」只按 `staff_id` 过滤）。用例 `StaffEarningItemIntegrationTest`。
  - **结算单期间上界必须用「结束日 + 1 天」**（`attachToPayroll` 的 `endExclusive`）：写 `<= 结束日` 会让**当天收益一条都结算不到**（同 §8.19）。
- **水票余额真相源是 `ticket_lot`，`ticket_account` 只是派生汇总**（v36，同构 `customer_barrel_lot` → `customer_barrel_asset`；正本 `docs/design/19`）：`remain_quantity == Σ lot.remain_qty`、`right_amount == Σ remain_qty × unit_price`，对账 **E8** 校验；**批次唯一写入口 `TicketLotService`**（`createLot` / `consumeFifo`）。① **单价取实付均价**（`payment_record.amount / ticket_qty`）；② **消耗按 FIFO**，退款回补按**流水当时单价**（`ticket_record.unit_price`）；③ **任何改动水票数量的路径都必须过批次账**（在线购票入账、站长加票、用票支付、订单取消回补、资产调整单），漏一条 E8 就不平。用例 `TicketPackageAndLotIntegrationTest`。**夹具 `createTicketAccount` 也必须建批次**（只插账户会打红 10 个现有用例）。
  - **MySQL 的 `SET` 从左到右求值，后面的表达式看到的是已更新的列值** —— `SET remain_qty = remain_qty - q, status = CASE WHEN ... remain_qty = 0 ...` 里的 `remain_qty` 已是 0，CASE 永远算不出 0。**要把 `status` 赋值排在 `remain_qty` 前面。**
- **库存有两个量，别混（v63；正本 `docs/design/28`）**：`inventory.quantity` = **在库实物**；**可用量** = `quantity − Σ(reserved_qty where status=1)`。**下单只"预留"不动实物**（`reserveForItem`）→ **入库/盘点增加后按 FIFO 补预留**（**`backfillReservations`**，全仓唯一分配算法，**取消/换站释放后也调它**）→ **完成配送才出库**（`shipForOrder(orderId, 当前履约站)`：站别不符/预留不足/实物不够**直接拒绝完成**，不许静默少扣）→ **换站搬凭据**（旧站释放 + 新站按可用量重建）→ **取消只释放、不回补库存**（`refundOrder` 里 `increaseStock` + `REFUND_RESTORE` 已删，**别加回来**）。`uk_reservation_active_item`（生成列）= **一明细至多一份活跃凭据**；`order_item.deducted_qty` **唯一语义** = 该明细活跃凭据 `reserved_qty` 的镜像（无凭据 = 0，写入点只有 `syncDeductedQty`）。**判据：按站扣/补库存前先答"这份货当初记在哪个站"—— 订单在某站履约 ≠ 该站扣过货**（否则缺货部分永不落账）。用例 `InventoryReservationIntegrationTest`（E1–E9）+ `InventoryBackfillIntegrationTest`（V03/V04/V06/V10）+ `InventoryReservationBarrierIntegrationTest`（V01/V02/V05）+ `InventoryReconciliationIntegrationTest`（E11–E14/SE7–SE10）。⚠️ 迁移 **v63**（返工版）与补偿脚本 **v64**（已跑过首版的库）都**先上代码再执行**、执行前 `mysqldump`（细节 `sql/README.md`）。
- **配送计费（起送量 / 配送范围 / 运费 / 楼层费）只有一份实现**（v35；正本 `docs/design/17`）：纯规则在 `util/DeliveryFeeUtil`，IO 在 `service/DeliveryFeeService`；**`PaymentServiceImpl.quote` 与 `OrderServiceImpl.createOrder` 必须调同一个 `calcForOrder`、传同样口径**（桶数只数 `category=1`）；一侧内联算费用 = 重演"计价双轨"。① **费用绝不并入 `water_amount` 或 `deposit_amount`**（可退，混入会多退钱）；② **门槛默认 WARN 不是 REJECT**；③ **拿不准就不收/不判**。用例 `DeliveryFeeUtilTest` + `DeliveryFeeIntegrationTest`（报价 `totalAmount` 必须等于下单 `total_amount`）。
- **无订单支付（在线购票）必须带客户端幂等键**（v33）：`order_id` 为 NULL 时 `createPayment` 的重复流水检查被跳过，而 `uk_payment_active_order` 建在生成列上、**NULL 互不冲突 → 零保护**。`POST /api/tickets/purchase` 的 `idempotencyKey` **必传**；`uk_payment_idempotency` **必须带 `customer_id`**。`confirmPayment` 的乐观锁只管**单条**流水。用例 `TicketPurchaseIdempotencyIntegrationTest`。
- **下单幂等键同样必传，作用域 `(customer_id, idempotency_key)`**（v62）：① 缺键即拒（**不再**服务端代生成 UUID —— 等于"每次重试都是新键"）；② 命中查询与唯一键 `uk_orders_idem_customer` 都带 `customer_id`（只按 key 全局查会把**别人的订单 id** 返回给调用者）；③ 同键**不同内容**拒绝（比对 `orders.request_digest`，只覆盖业务字段，**不含**服务端算的金额与 `confirmShortage` —— 缺货弹窗确认须用同一个键重提）；④ 命中时机在**鉴权之后、商品/库存校验之前**（重试时商品下架仍应拿回原单）。用例 `OrderCreationIntegrationTest`。
- **未选身份的员工会话（`role=UNSELECTED`）只能访问引导端点**：`wx-login-staff` 对"还没 staff 记录"的 openid 签发（`userId` 负数占位、`stationId=null`），**既没有员工行也没有站别**。判据正本 = `AuthInterceptor.UNSELECTED_ALLOWED_PATHS`（只有 select-role / me / logout / bind-status），其余一律 code=1 拒绝。**不能靠各端点自觉**：`RequireRoleAspect` 是"无注解即放行"，按身份分支过滤的端点无 else 时退化成 `stationId=null ⇒ 不加归属限制`（实测泄露**全站订单**含客户姓名/电话）。**新增业务端点不必再记得补注解**。
- **员工代客下单的站别一律取登录态**（产品裁定「跨站代客下单不合法」）：`OrderServiceImpl.createOrder` 对 `userType=staff` 先要求 `AuthContext.getStationId() == dto.stationId`（不等直接拒），再校验"客户属于该站"。只校验后者会被绕开：`stationId` 由请求体传入，A 站员工挑"与该站有关系的客户+地址"就能扣他站库存、动他站客户的票与押金。用例 `ArchReviewFixesIntegrationTest`。
- **订单没有"实体整行更新"入口**：`POST /api/orders`（裸 `Orders` + `OrderMapper.update` 整行写）**已删除**（零调用方，登记见 `docs/audit/删除登记表.md`）；改订单只能走 `OrderWorkflowService` 具名命令或带 expected-state 的专用列更新。⚠️ 同路径仍有 `GET`，再发 POST 得到"方法不支持"而**不是 404**，守护用例断言"订单零变化"。
- **客户端的请求方式/Content-Type 错误不报成系统故障**：`GlobalExceptionHandler` 对 `HttpRequestMethodNotSupportedException` / `HttpMediaTypeNotSupportedException` → code=1 可读拒绝（列出该路径支持的方法）。此前落进 `Exception` 兜底 ⇒ **HTTP 200 + code=500 + 一条 SYSTEM 告警**，客户端写错方法却惊动系统管理员（同 §8.21）。
- **对账等式不能把「合法业务状态」算成差异**（三处均表现为**日结永远不平**；正本 `docs/architecture/03` §9）：① **等式2 `p2a`**：核销只置 `payment_status=2` 而不补 PAID 流水 → 每核销一单就报不平；**收款必须两步**：先 `recordCashCollection(orderId, note)`（幂等）再 `markPaidIfCollectable`，**不许另写"补流水"实现**。② **等式2 `p2c`**：`order_id IS NULL` 不等于孤儿（**在线购票无订单**），判据是 `AND p.ticket_qty IS NULL`。③ **等式3 `b3a`**：`customer_barrel_in_transit.status='DELIVERED'` 是**合法终态**，改为「标了已送达却没有权益批次」。**写等式前先问"这个状态正常经营里会不会合法出现"，会就不能进差异计数。** 用例 `ReconciliationAfterNewFeaturesIntegrationTest`。
- **应收账款 = 给「待收款」加账期维度，不新造金额口径**：金额真相源仍是 `payment_status = 1 AND status <> 5`（同 `DashboardMapper`）；**没建新列**，激活挂空列 `orders.settlement_status` / `orders.due_date`；账期由 `ReceivableService.resolveDueDate` **下单时快照一次**（**只有现金单有应付日期**）。端点 `/api/manager/receivables*`、`/customers/{id}/credit-terms`。① **核销 ⟹ 已收款**（`OrderMapper.settleIfCollected` 的 CAS 带 `payment_status = 2`），收款仍**两步**；② **逾期只提醒、不改金额**；③ 对账 **E10**（`settlement_status = 2` 且 `payment_status ≠ 2`）**只查单向**（反向是**正常经营状态**）；④ **账期自 v60 起是「站级」**（存 `customer_station_config.due_days` / `settlement_cycle`），设置用**只改这一张表**的专用 mapper，**不要**用 `CompanyInfoMapper.updateByCustomerId`（整行覆盖会抹掉企业资料）；**`company_info.due_days` 已无人读取**。**归属判据只认并集 `CustomerMapper.countCustomerOfStation`（绑定 ∪ 本站订单），不要用 `getStationCustomer`**（会把**没下过单的新客户**判成不属于本站）。**代客下单复用 `POST /api/orders/create`**（两条建单路径算金额 = 计价双轨）；读接口 `/api/manager/order-assist/*`。用例 `ReceivableIntegrationTest`、`EmployeePlaceOrderIntegrationTest`。

## 2. 目录结构与关键入口

- **仓库结构问 `code_map`，不要照抄任何手写清单**（见 §0.4）。三条常年有效的位置判据：
  - 后端唯一入口 `AquaFlow-backend/`（Spring Boot + MyBatis；集成测试在 `src/test/java/.../integration/`）。
  - **CI 门禁 `.github/workflows/ci.yml` 必须在仓库根** —— 放进 `AquaFlow-backend/` 下 GitHub 不读。
  - `archive/**` 不维护；`backup/` 是本机备份、已被 gitignore，**勿提交**。
- 后端 API 统一响应 `{ code, message, data }`：`code 0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常；**业务错误 HTTP 状态仍是 200**（唯一例外：未认证返回真 401）。【仓 `common/Result.java`】
- 小程序 API 基址在各自的 `config/api.js`；**两端 `prod.baseUrl` 目前都是占位符** `https://your-domain.com`（见 §8.10）。
- **`miniapp-delivery` 的底栏是自绘的**（`app.json` 的 `tabBar.custom = true` + `custom-tab-bar/`，为让「首页」只对站长显示）。**新增 tab 页要动两处**：`app.json` 的 `tabBar.list` **和** `custom-tab-bar/index.js` 的 `TABS_*`；tab 页根容器加 `custom-tabbar-page`、`onShow` 调 `syncTabBar(this, '<路径>')`（组件按页各一份）；站长 P0 红点由组件画（`utils/pending-reminder.js#syncTabBarDot`）。判据与踩坑见 `docs/design/24` §2.6。
- **「不校验合法域名」= 两个位置，缺一不可**：① `project.config.json` 的 `urlCheck` —— **两端都已是 `false`**（旧文档写"仍是 `true`、靠 `project.private.config.json` 覆盖"**已过期**）；② 真机上右上角 `…` →「打开调试」。少任何一个，本机 `http://<局域网IP>:8080` 的请求都会报「不在以下 request 合法域名列表中」。【仓】

## 3. 技术栈与本地运行 / 构建命令

| 项 | 值 |
|---|---|
| 后端 | Java 17（toolchain）、Spring Boot **4.0.6**、MyBatis-Spring-Boot 4.0.1、Jackson 3 |
| 构建 | Gradle Wrapper **9.4.1**（`gradlew.bat`）、Lombok、腾讯云 COS SDK |
| 数据库 | MySQL 8.x（本机 CLI：`D:\backend\MySQL\bin\mysql.exe`），库 `aquaflow` / 测试库 `aquaflow_test` |
| 小程序 | 微信原生（libVersion 3.17.0），无框架、无分包；**两端 appid 各自独立**（顾客端 `wx12632a1cdca9fbcc` / 员工端 `wxc6211615c79da9f9`，正本见 `miniapp-*/project.config.json`；沿革见 §1.1） |

```powershell
# —— 后端：编译 / 启动（端口 8080）——
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat clean compileJava
.\gradlew.bat bootRun
```

- **环境变量**：清单权威来源是 **`AquaFlow-backend/.env.example`**（⚠️ 注意在**后端子目录**下，不在仓库根 —— 只查根目录会误判成"文件不存在"）。**启动期硬校验只有 3 项**（`config/RequiredConfigChecker.java`）：`JWT_SECRET`（**长度 < 32 也拒绝启动**）、`WX_APP_ID`、`WX_APP_SECRET`，缺一跳 `IllegalStateException` 拒绝启动。⚠️ **另有两道只在 `prod` profile 生效的启动期校验**（同一个 `RequiredConfigChecker`，非 prod 一律跳过）：① `checkProdSafetySwitches()` —— `app.payment.mock-wechat-pay` 与 `app.dev-login-enabled` 必须为 `false`（读**解析后**的值 ⇒ 写死 yml 也拦不住命令行 / `SPRING_APPLICATION_JSON` 覆盖）；② `checkProdDatasource()` —— `spring.datasource.url/username/password` 必须解析出真值（Spring 7 起 Hikari 懒初始化，缺 `DB_URL` 照样 `Started`，故显式兜底）。【仓】
- **其余变量的缺失后果由「profile + 各自组件」决定 —— 别写成一句「缺一即启动失败」**：`application-prod.yml` 里凡写成 `${VAR}`（**无默认值**）的，Spring 解析占位符时就失败。**发布物 jar 逐项实测（13 场景，门禁 `scripts/prod-startup-check.js`）**：**必需** = `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`JWT_SECRET`/`WX_APP_ID`/`WX_APP_SECRET`/`WX_STAFF_APP_ID`/`WX_STAFF_APP_SECRET`/`CORS_ALLOWED_ORIGINS`（JWT 另有长度 ≥ 32 那道）；**可选** = **COS 四件套**（缺任一项只降级：上传回"对象存储未配置"，**不阻断启动**；`COS_REGION`/`COS_BUCKET_NAME` 无人读、声明了也不失败）；`DEV_LOGIN_ENABLED`（**生产必须 `false`**，且 prod 下该 Bean 不存在 ⇒ 路径 404）/ `MYBATIS_LOG_IMPL` / `RATE_LIMIT_*` 同理不影响启动。【仓】
- 本地默认 profile `local`，密钥读 `src/main/resources/application-local.yml`（**已 gitignore，含真实密钥，禁止提交、禁止回显**）；生产用 `--spring.profiles.active=prod` + 纯环境变量。该文件还开着 `dev-login` 与微信**模拟支付渠道**、并按 IP **关掉了登录限流**（真机联调与手机共用出口 IP）；仅本地如此，生产不受影响。
- 小程序：微信开发者工具分别打开 `miniapp-user` / `miniapp-delivery` 目录（无 npm 构建步骤）。
- PowerShell 里**用 `;` 分隔多条命令，不要用 `&&`**。长任务（Gradle 构建、测试）放后台任务。【仓】

## 4. 数据库与迁移流程

- **Flyway 未启用**（无依赖、无配置）。`sql/**` **全部靠手工执行**，没有版本表、没有自动校验。（`src/main/resources/db/migration/` **不存在**，勿按该路径找脚本。）
- **新建库的权威基线是 `sql/schema.sql`**（全 `CREATE TABLE IF NOT EXISTS`，可重复执行）；`init.sql` 只建结构、不含种子数据，且 `SOURCE schema.sql` 依赖相对路径，**必须在 `sql/` 目录下执行**。表数以 `Select-String -Pattern '^CREATE TABLE'` 实测为准。
- **`schema.sql` 导入必须保留文件字节**：用 MySQL 客户端 `source`（PowerShell 见下例），或字节级重定向 `cmd /c "mysql -uroot --default-character-set=utf8mb4 库名 < schema.sql"`（CI 上是 bash 的 `<`）。**不要用 PowerShell 管道**（`Get-Content -Raw | mysql` 按控制台代码页重编码会把中文注释变乱码）。⚠️ **核对是否写坏要比字节（`HEX(TABLE_COMMENT)`），不要看控制台**。【仓】
- **老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑（**该清单为唯一权威**）。执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。⚠️ **别去找 `v3`**：`sql/` 顶层**没有** `migration_v3.sql`（它只在 `sql/archive/`，README 记 SUPERSEDED，产出早已被 `schema.sql` 吸收）；**真正不在必跑清单里、且只对"基线之前就存在的老库"有意义**的是 V1 收敛三件套 `migration_v1_alignment.sql` / `migration_v1_converge_inventory.sql` / `migration_v1_cos_converge.sql` 与 `migration_fix_schema_alignment.sql`（登记在 README 的「未列入迁移表的脚本（**不是必跑项**）」B 节）。⚠️ 顺序坑 **2026-09-30 起由脚本自带门禁兜住**（`_ddl`/`_backfill` 在旧欠桶表已退役时整体 skip 且退出码 0；`aq056` 建外键改预检幂等），但仍请执行前人工核对清单与自己的库状态。【仓】
- **迁移脚本必须幂等**：MySQL 8.4 无 `DROP ... IF EXISTS`，统一用 `information_schema` 预检 + `PREPARE`。**破坏性 DROP 必须先上代码、再执行 SQL**；新建脚本前先 `ls sql/` 看命名是否占用。【会】
- **严禁在生产执行**：`sql/reset_data.sql`（TRUNCATE 多表）、`sql/clear_data.sql`、`reconcile_order_814.sql`、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`（已过期且不可执行）。**`sql/` 里大量脚本是 SUPERSEDED/DUPLICATE**（见 `sql/README.md` 表格），已被 `schema.sql` 吸收，不要在新环境执行。【仓】

```powershell
# —— 全新库初始化（只建结构，空库）——
cd D:\backend\project\AquaFlow\AquaFlow-backend\sql
& 'D:\backend\MySQL\bin\mysql.exe' --default-character-set=utf8mb4 -u root -p -e "source init.sql"
```

## 5. 测试与验证方式

- 集成测试基类 `support/AbstractIntegrationTest` 启完整 Spring 容器、发真实 HTTP，每例前清表。**允许目标只认 `TestDatabaseTargetGuard`：`aquaflow_test` 或允许的会话后缀，拒绝业务/备份标记及泛化的 `*_test`；必须有精确库名和 IPv4/端口/库确认**。启动前核配置，清表前在同一实际连接核 URL、catalog 与当前库；名字像测试库不授权删除数据。配置见 CONTRIBUTING §4，件数读实际构建目录 `test-results/test/*.xml`。
- **本机无 Docker**，不用 Testcontainers；测试库 `aquaflow_test` 是独立可重建库。
- **运行前置（硬要求）**：必须显式设置 `GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'`，否则 Gradle 往沙箱外的用户目录写缓存、被拒后直接失败；`--project-cache-dir .gradle_alt` **不足以**解决。【仓】
- **Gradle 锁坑**：`bootRun`（8080）在跑时直接 `gradlew` 会因 `fileHashes.lock` 失败 —— 统一加 `--no-daemon`；换 `--project-cache-dir` 可与在跑的 `bootRun` 并存（必要时先停后端）。
- **并发构建互删 `build/`（2026-09-30 实测，5 个独立会话各撞一次）**：`AquaFlow-backend/build/` 是**多会话共用**的，一个进程删掉 `in-progress-results-generic*.bin` 会让另一个正在跑的整套测试以 `NoSuchFileException` **整体 FAILED** —— 看着像"代码改坏了"，其实是被邻居删了构建目录。**判据：同一台机器上只要不止一个会话会跑 Gradle，就必须用 init script 把 `layout.buildDirectory` 指到会话专属目录**（如 `AquaFlow-backend/build_cont`）。详见 skill §8.34。
- **bash 用 Git 自带的** `D:\backend\Git\bin\bash.exe`（`Get-Command bash` 解析到 `WindowsApps\bash.exe` 存根，报 `E_ACCESSDENIED`）；**受限沙箱下 Cygwin 起不来**（`couldn't create signal pipe, Win32 error 5`）。`python` 用 `D:\agent\python\python.exe`。⚠️ **含 `↔` 等非 GBK 字符的 python 脚本先设 `$env:PYTHONIOENCODING='utf-8'`**（否则 `print` 抛 `UnicodeEncodeError`，表现为"脚本没问题却 exit=1"）；**别把脚本路径写进自定义函数的 `$args`**（自动变量，会让 python 无参启动、进 REPL 后 exit 0）。【仓】
- **受限沙箱下「拿不到子进程输出」是环境限制，不是测试挂了**：进程建不了 named pipe ⇒ `spawnSync` 回 `status=null` + `EPERM`、`Start-Process -RedirectStandardOutput` 报"拒绝访问"、`bash scripts/*.sh` 起不来 —— 同一条。`tests/js/run-all.js` 已内置降级（管道 → 文件描述符重定向），跑流程测试仍是一条命令；**判据必须建在 ASCII 哨兵上**（`AQUAFLOW_SUITE_OK`，中文在 fd 里会被编码毁成 `?`）。**bash 脚本（`scan-secrets.sh`、`verify.sh`）在本机跑不了时用等价实现逐条复刻并说明"哪条没跑到"，不许当成通过。** 详见 skill §8.31。【仓】
- **判端口占用用 `netstat -ano | Select-String ':8080'`**（看 `0.0.0.0:8080 … LISTENING`），**别用 `Get-NetTCPConnection`** —— 它静默返回空集，据此起 `bootRun` 只会白跑一次并拿到 `Port 8080 was already in use`（见 skill §8.32）。【仓】

```powershell
# —— 后端集成测试（本机唯一可跑通的入口）——
$env:GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat test --no-daemon --project-cache-dir .gradle_eval
```

- **断言看响应体 `code`，不看 HTTP 状态**（业务错误仍 200；未认证才是 401）。新增用例：继承 `AbstractIntegrationTest`，用 `createStation/createStaff/createCustomer/createProduct/createOrderFull/createOrderCrossStation` 造数，`customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete` 发请求，`intOf/decimalOf` 查库；并发参考 `ConcurrencyIntegrationTest.fireTogether`。【仓】
- **分层门禁 `architecture/LayeringArchitectureTest` 违规即红**：Controller 不得新增注入 Mapper / 直写库 / `@Transactional`（基线 **33 类注入 Mapper（62 处字段声明）** / 15 写调用 / **0 注解**，只减不增），`BarrelController` 零容忍（2026-09-29 下沉后不许回潮）。改基线前先证明是"又下沉了一批"，不是"又多了一处违规"。2026-09-29 第二批：登录/绑定编排入 `AuthTokenService` / `StaffStationApplicationService`，HTTP 层事务已清零（**再出现控制器 `@Transactional` 即红**）。2026-09-30 F-18：`DeliveryController` 拆成 `DeliveryTaskController` / `StationDeliveryConsoleController` / `CrossStationDispatchController`，只读取数下沉 `DeliveryConsoleService`，34→33 类、68→62 处。
  - ⚠️ **2026-09-30 加固（原"基线 32"是假的）**：注入检测的原正则 `private\s+\w+Mapper\s+\w+` **漏 `private final XxxMapper`（构造函数注入）与全限定名写法** ⇒ `ManagerReceivableController` / `ManagerExceptionController` 两个类既不在基线里、也永远不红。现覆盖 `final`/`static`/全限定名/泛型容器字段，并给"字段声明处数"加了上限（62，防同类内多处新增却因类集合不变而漏报）。
  - **写调用的判据不再是手写动词表**：旧 `WRITE_VERBS` 漏 `settleIfCollected` 这类"名字里没有动词"的 `@Update`（`settle/claim/dispatch/reassign/outsource/append` 全都不在表里）；现改为扫 Mapper 源码的 `@Insert/@Update/@Delete`（**含全限定写法**）**加上** `resources/mapper/*.xml` 的 `<insert|update|delete id=...>`，运行时建"真写方法集合"。**别把词表那条老路加回来。**
- **统计测试件数**：把所有 XML 相加，用 `$d = New-Object System.Xml.XmlDocument; $d.Load($path)`；**不要 `Get-Content -Raw` 再转 `[xml]`**（按 ANSI 解码弄坏测试名、**静默少算**）。【仓】
- **验证 CI 会不会绿，就在本机复现 CI 三步**：① `DROP DATABASE aquaflow_test; CREATE DATABASE aquaflow_test;` 后**字节级重定向**导入 `sql/schema.sql`；② `.gradlew.bat cleanTest test`（`cleanTest` 不可省：不加会报 `:test UP-TO-DATE` 而**根本没跑**）；③ **把 CI 环境变量也照抄一遍**（尤其 `DEV_LOGIN_ENABLED=false` —— 本机是 `true`，不一致会让"只在 CI 上红"的用例长期隐身，见 §8.27 末段）。【仓】
- **按业务场景组织的覆盖地图见 `docs/audit/2026-09-16-场景测试矩阵.md`** —— 新增用例前先看它找空白。

## 6. 代码约定与风格

- 后端分层：`Controller` 只做认证 + DTO 校验 + 调服务 + 返回 `Result<T>`；**禁止 Controller 直接写 `orders` / `payment_record` / 库存 / 桶资产表**，订单状态与副作用只能经 `OrderWorkflowServiceImpl` 这类编排服务完成。
- **所有状态改写必须 CAS 并检查受影响行数**（`updateStatusIf` / `updatePaymentStatusIf`）；无 expected-state 的 `updateStatus` / `updatePaymentStatus` 属于待清除的旧路径。
- 业务前置不满足一律抛 `BusinessException`（→ `code=1`），**不要用 `RuntimeException`**（会被兜成 500）；**别在 `@Transactional` 方法内 catch 业务异常**（会抛 `UnexpectedRollbackException`，把业务拒绝伪装成 500）。
- 权限：`@RequireRole({"STATION_MANAGER"})` + `@RequireStation`，由 AOP 切面 `execution(public * controller..*.*(..))` 统一保护，新增方法自动生效。**跨站校验一律以 `AuthContext` 中服务端刷新的 `stationId` 为准，不信任请求参数**；客户 ID 必须由登录态覆盖或与订单所有者严格比对。
- MyBatis：注解 SQL 用下划线列名（配 `map-underscore-to-camel-case: true`）；**注解 SQL 无编译期校验，新写必须手工在真实 MySQL 上跑过**。
- 金额、客户、订单归属、水票数量一律服务端推导或强校验。展示文案（`statusText` / `payMethodText` / `payStateText`）由后端下发，**前端禁止自带 1/2/3 映射表**（两端各写一套曾致新客下单全失败）。
- **请求体的枚举入参必须白名单校验**（`OrderCreateDTO.paymentMethod` 传 99 也建单成功），且**兜底文案不许把未知值说成某个已知值**（`PayMethod.textOf` 原 `default` 返回「现金」，幽灵单显示成货到付款）。**判据：注解边界会被新调用路径绕过，服务端白名单不会** —— `OrderServiceImpl` / `PaymentServiceImpl` 各留一道 `PayMethod.isValid`。
- 写库顺序：先 `getByClientToken` 判断幂等再动手；Controller 调 service 后再写库必须 `@Transactional`。
- 日志禁止记录密码、JWT、微信授权码、完整手机号/地址、任何密钥。**回复中也不回显密钥**（用 `<redacted>`）。
- 术语统一：**配送中**（订单执行状态或遗留未交付记录；新有效权益不等于实物已领，旧称「在途」避免面向客户使用）、**进行中**（= 待配送 1 + 配送中 2）。表名 `customer_barrel_in_transit` / 类名 `CustomerBarrelInTransit` 仅为兼容历史命名保留，注释与文案一律写「配送中」。`customer_owed_barrel` 已停止写入，欠桶改读 `customer_barrel_over`。
- 小程序：`wxml` 内禁止调用 Page 方法 / `Math.` / `Date.`；`wxml` 绑定的事件处理函数必须真实存在，否则点击**静默无反应**；注意 `require` 相对层级；后端 `/api/delivery/orders/{id}/xxx` 用模板串拼接。
  - ⚠️ **`{{}}` 里不要做带 `\n` 的字符串拼接**：`text="{{a}}{{b ? '\n\n' + b : ''}}"` 会**直接编译报错**（`Bad attr 'text'`），**本地无门禁能发现**（`audit_wxml_handlers.py` 只查事件绑定，要等微信开发者工具编译才炸）。拼接一律在 js 里算好再下发；**单行**的 `'…' + x`（如 `{{n > 0 ? '¥' + n : '—'}}`）是支持的，别一并禁掉。
- 小程序**面向站长/顾客的文案禁止出现开发词**（2026-09-24 立规）：`接口` / `后端` / `前端` / `服务端` / `落库` / `端点` / `字段` / `部署` / `重新构建` 一律不许出现在 `<text>` 里（**注释里随便写**）。要么换成"他没拉到数据，刷新重试"，要么收进 `<help-tip>`。典型历史事故：客户画像页的排障提示原文是「后端未部署画像接口，请重新构建并启动后端」——**写给开发看的话，却渲染给了站长**。

### 6.1 注释契约（2026-09-14 立规，强制）

本仓库的注释**不是可选项**，也是下一个 AI 的**操作依据**。

1. **改代码必须同步改注释**：注释与代码不符**比没有注释更危险**（注释错了人会直接照做；事故见 §8.14）。**作废的 javadoc 必须删除，不能悬空留着占位。**
2. **分工：流程 / 规则写文档，代码注释只写「改这里会踩什么坑」。** 业务规则 / 领域模型 / 状态机 / 资金口径已写在 `docs/design/`，**代码注释不要复述**。
   - **该写**：反直觉约束、历史事故、"别加回来"的护栏、并发与加锁顺序、唯一键 / 幂等陷阱、"本类不是桶账写入口"这类边界声明。**该写文档**：业务流程 / 状态流转 / 字段口径 / 交互。**都不写**：`getXxx` / `setXxx`、直白循环与判空。提业务规则就**一行指向文档**（如"见 `docs/architecture/02-领域模型.md`"）。
   - **正面样本**：`constant/PayMethod.java`（"前端曾把 2/3 写反导致下单必失败"）、`BarrelLedgerService`（加锁顺序）。**反面样本**：把整段业务背景抄进 Controller。
3. **修完缺陷就地留评论**：在**出问题的源头**（不只写在测试里）注明「原来是什么 / 为什么错 / 后果是什么 / 正确做法」并标日期。
4. **新增端点必须写明归属与调用方**：员工端点标 `@RequireRole`；顾客自助端点写明身份取自 `AuthContext`（见 `aspect/RequireRoleAspect.java`）；小程序侧注明「顾客端能不能调」。
5. **参数必填性、枚举取值、接口路径、表结构**四类说明最易过期，改动必须当场同步。
6. 新增类 / 公开方法 / 非直觉分支补 javadoc；**纯 getter/setter、显而易见的循环不补** —— 注释的价值是"降低误用概率"，不是覆盖率。

### 6.2 概念引用契约（2026-09-21 立规，强制）

**引用一个「本次对话 / 本轮工作里临时造出来的代号或概念」时，必须当场再给一次定义或一句话说明，不许裸用。**

- **对象**：自己起的编号与代号（`T1`/`T2`、`场景 B`、`层 A`……）、只在会话里出现过的新名词、自己发明的简称。
- **为什么**：上下文有限，裸用代号 = 让对方回翻聊天记录或**凭猜测理解**，猜错直接做错事；与 §6.1 第 1 条同源：**被信任的文本比没有文本更危险**。
- **判据**：把那句话单独摘出来给一个**没看过前文**的人读，他能不能懂？不能就必须补定义。
- **写法**：`T2（取消已送达订单时，客户手上的桶权益没有被撤销）` —— 代号 + 一句话，别只写代号。
- **落在哪**：对话、文档、代码注释都适用；文档里给代号时同样要带定义（文档会被单独打开）。
- **不等于啰嗦**：同一段话里连续引用同一个概念，第一次给定义后可用简称；**跨段落、跨回复就必须重新给**。

### 6.3 文档契约（2026-09-24 立规，强制）

**一份文档只服务一类读者、只干一个活**（原子化）。判据三条：

1. 文档开头能用一句话写明「本文件写给谁」；
2. 同一文件里若**同时**有面向两类读者的内容（典型反例：README 既写"怎么装怎么跑"又写"业务难点与方案取舍"）→ 不合格，拆成两份并互相链接；
3. **面向外部评审的文档与面向接手工程师的文档不得合并** —— 前者要 3 分钟看懂"价值与难度"，后者要 30 秒能跑起来。

**本仓库的落点**（对照表正本在 `docs/README.md`）：`README.md` = 面试官/评审者 · `CONTRIBUTING.md` = 接手工程师 · `docs/architecture/**` = 规格 · 本文 = AI 动手前的判据 · `docs/audit/**` = 历史审计记录（只追加，**不当规范读**）。
新增文档前先查 `docs/README.md` 的索引，**同职责的文件不要建第二份**（本仓已有"同一条规则两处定义"的多次教训，见 §6.1 第 1 条）。

⚠️ **本机件（有意不入库）不得被已入库文件点名**：本仓库用 **`.git/info/exclude`**（**不是** `.gitignore`）维护一份"只留本机、不推远端"的清单，含日式 `AQ-*` 体系、`docs/_archive/frozen-ja/process/00|01`、若干汇报/评价类文档等。
**判据**：写进 `README.md` / `AGENTS.md` / `CONTRIBUTING.md` 这类**已入库**文件的路径，必须是 `git ls-files --error-unmatch <路径>` 能命中的；否则推上去就是死链。
⚠️ **`.git/info/exclude` 不进版本库**：`git init` / 重建仓库 / 换机器都会**弄丢它**，一丢本机件就会被 `git add -A` 卷进提交（2026-09-24 重建仓库时**真的发生过一次**）。**重建仓库后第一件事就是恢复它，然后再 `git add`。**
⚠️ **日式规范体系已冻结（2026-09-24 产品裁定）**：原话「日式文档在我再次动之前，你可以一直不用管他了，当他不存在了」。**它们不是规格、不是判据来源**：不要引用、不要按它们改代码、也不要为满足它们的写作规范改别的文档。**本节判据自包含，不依赖任何一份日式文档。**

## 7. 协作注意：不要动 / 属于生成物

- **动手前先 `git status` 核对；不要顺手混入无关改动，也不要替用户提交**；工作区常有大量未提交改动，先 review。
- 未获明确要求**不要 `git commit` / `git push`**；改动留在工作区供 review。当前分支 **`main`**；用户已授权在独立仓库汇总全部可恢复历史线，并把作者/提交日期对齐原记录（2026-10-02 修正）；先备份上一版，远端替换须用本次核对的精确 force-with-lease。原工作区和旧历史保留，远端默认分支以 `git ls-remote --symref origin HEAD` 的本次结果为准；**提交数不要硬编码**（以 `git rev-list --count main` 为准）。
- **提交粒度与信息**：一次提交只做一件事；message 写「改了什么 + 为什么」，**不写过程叙述**，**不记录工具、环境或个人账号变动**。判据：对三个月后排查问题的人有用吗？
- **提交规范 hook 已入库但默认未启用**（`.githooks/`）：`commit-msg` 强制 `<type>(<scope>): <subject>`；`pre-commit` 暂存文件数 > 30 拒绝提交。启用：`git config core.hooksPath .githooks`。⚠️ 受限沙箱下 Git 自带 `sh.exe` 起不来，启用会让 commit 失败。
- **不要改 `archive/**`**（`legacy-web-frontend`、`miniapp-station` 均历史留档，后者缺 `app.js`、页面残缺）。**不要引用 `miniapp-station`**。
- **生成物 / 勿手改**：`AquaFlow-backend/build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`docs/design/*.html`、`tabbar-icons-preview.png`、`project.private.config.json`。`.gradlehome/`、`.gradle_alt2/`、`.gradle-user`、`.gradle_alt3`、`.dsh-code-index/`、`.openvisio/` 是工具缓存。
- **`.gitattributes` 已加入**（`* text=auto` + `*.sh/*.py/*.yml/*.sql eol=lf`）：blob 一律存 LF，防 Windows 检出 CRLF 后 `bash scripts/*.sh` 在 CI（Linux）因 `\r` 失败。
- **根目录 `*.py` 分两类**：**7 个已入库**（`audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py`、`audit_js_syntax.py`、`audit_scenario_matrix.py`、`audit_wxss_selectors.py` —— CI 与 `scripts/verify.sh` 门禁，`.gitignore` 对 `*.py` 开了 `!` 例外，**不入库则新克隆的 CI 必然失败**；前两个需传端名；最后一个查「同元素两个类被写成子孙选择器」（`.A .B`）—— 2026-09-26 实测它让首次资产弹窗卡片留在 `translateY(100%)` 外、只剩遮罩，客户首单被挡死，其余门禁全绿）；另若干（`e2e_user_test.py`、`gen_tabbar_icons.py`、`api_reverse_audit.py`）属调试/审计残留，不可作项目入口或规范依据。
- **`api_reverse_audit.py` 的已知偏差**：① 行号按**剥离注释后**计、**系统性偏早**（报 `:182`／实际 `:198`），引用前回原文件核对；② **只看路径、不看 HTTP 方法**，报的是**下界**；③ 认不出「**页面内路径常量拼接**」与「路径段由**函数参数**传入」（前者已修：45→34，后者看不见）⇒ **在用端点会被判死**。**清单是候选不是结论** —— 删端点前必须自己重证一遍零引用（见 §0.3）。

## 8. 已知坑与历史教训

> ### ⚠️ 这 30 条的**正本已移出本文件**（2026-09-22 第三轮瘦身）
>
> 体积原因（≤ 65536 字节，超出会被**静默削尾**），按 §9 先例搬成 skill：
> **`.dsh/skills/aquaflow-known-traps/`** —— **动手改代码前加载**，别靠记忆。
> 条目号没变：`docs/design/**` 与测试注释的 `§8.15` / `§8.16` / `§8.19` / `§8.20` 仍指那些条目；覆盖面见 §11 表。

## 9. 待确认 / 未验证清单

> 完整清单在 skill **`aquaflow-open-questions`**（动手前先加载）；只留仍生效的判据。

以下条目**未确证，执行前必须自行核实**：

1. **「水厂端已彻底移除」是 2026-09-11 的复核结论**，此后未重新全库检索 `factory` 残留。
2. **已弃用表的实际停写状态未逐一复核调用链**：`customer_owed_barrel`（已停止写入）与 `customer_barrel_in_transit` 写入点。
3. **`ManagerOrderController` 确已删除**（文件不存在，有 `ManagerOrderControllerRemovedIntegrationTest`），但 `.workbuddy/memory/MEMORY.md` 仍列为「仍未做」高危项 —— **该记忆已过期**，也不排除有其他等效写入口。
4. ~~**开发者工具「测试号」是否支持 `wx.login` / `jscode2session`**~~ —— **已实测（2026-09-22）：换不出 openid，已弃用**（真机 `wx.login` 拿得到 code，`jscode2session` 回 `invalid code`／40029）；顾客端先借员工端那对 appid 过渡，**2026-09-23 换成自有的 `wx12632a1cdca9fbcc`**（见 §1.1）。测试号**不能上传代码 / 发布 / 设为体验版**；**`dev-login` 是登录不通时的退路**（代码注释 `AGENTS §9.4` 指本条）。
5. **测试号分「小程序」与「小游戏」两种，不可混用**：把**小游戏**测试号的 appid 填进小程序项目（`compileType: "miniprogram"`）会**编译失败**。
6. **微信订阅消息对本项目不可行**：除少数行业（政务/医疗/交通等）外都是**一次性授权**，`wx.requestSubscribeMessage` **无法静默获取**（推一条要当面点「允许」）；水站高频提醒摩擦大，**产品裁定不做**（站长端只有应用内红点 `miniapp-delivery/utils/pending-reminder.js`）。另注：原 `WeChatNotifyService` 骨架（读客户端 appid）已于 2026-09-29 删除（零调用，登记 `docs/audit/删除登记表.md` §6.2），真做推送按员工端那对 appid 新写。**同族缺口**：客户侧进度（接单 / 配送中 / 已送达 / 已收款 / 退桶结果 / 水票到账）**一条通知都没有** —— `OrderWorkflowServiceImpl` 只写"被拒单"与"临时外派"两种负面通知，`NotificationServiceImpl` 六个方法全是拼文案写 log 的空壳。

## 10. 本文件的来源与维护

- 原则：**能验证才写，不能验证就放进 §9**。任一条若与仓库代码冲突，**以代码为准**，并回来改本文件。
- 维护建议：做大改动/重构后核对 §1 常量清单（`OrderStatus` / `PayMethod` / `PaymentStatus` / `DepositType` / `BarrelRecordType`）与 `sql/README.md` 迁移清单；**新增枚举或迁移后必须同步 §1**（枚举值正本 `constant/*.java`）；§9 被证实的条目应上移进正文并删除。
- **体积约束**：≤ 65536 字节，超出会被**静默削尾**。**规则**：① 加段前先量体积（`node`/`read`，别用 `Get-Content`），**余量 < 4 KB 先瘦身**；② 瘦身**只压正文、不删条目、不重排 §8 编号**，瘦身前留全文字节级存档（仓库外工作记录，已三份）；③ 叙事写工作记录、规格写 `docs/architecture/`；④ 别手写会漂移的计数。
- **历次瘦身（判据留、叙事外移）**：第二轮 66579 字节超预算被**静默削尾**；第三轮（2026-09-22）把 **§8 的 30 条整体搬进 skill**（逐条校验「内容零丢失、编号不变」，编号仍是 `docs/design/**` 与测试注释的引用锚点），本文 §8 只留**指针**；2026-09-27 加两条环境判据又顶到 66060 ⇒ 就地压正文（**余量已是硬约束，加段必先量体积**）；第四轮（2026-09-29）61547⇒~59000 就地压 header/§0/§1.1/§2–§6.1 历史叙述（字节级存档在仓库外工作目录，用户裁定到此为止、**不再为凑数删条目**）。
  ⚠️ **下一次要瘦就动 §1（约占 36%）** —— 但那是领域不变量，压之前先逐条确认判据没被压没。
- 待决策项：工作区未提交改动是否先 review 再拆成数个提交（见 §7）。
- **`docs/AGENTS.md` 已作废并移出仓库**：口径是**过期文档要"离开会被自动加载的位置"**（DSH 会把 `docs/AGENTS.md` 当附加指令注入，改名等于留在原地）—— **该待决策项已关闭：不改名**。

## 11. 去哪找（工具与按需加载的 skill）

**代码结构别问文档，问工具**（DSH 的 `dsh-code-index`，索引缓存 `.dsh-code-index/`）：

| 想知道 | 用什么 |
|---|---|
| 仓库全貌、最重文件 | `code_map` |
| "符号在哪（带 `file:line`）" | `code_search` / `code_symbols` |
| "谁调用了 `BarrelLedgerService.checkout`" | `code_refs`（callers / callees） |
| 环依赖 / 孤儿模块 | `code_health`（需插件开 `codeHealth: true`） |
| 结构图（Atlas / City 3D） | `openvisio view . --no-open` → <http://127.0.0.1:7077>；重建 `openvisio index .` |

**按需加载的领域 skill**（放在 `.dsh/skills/`）：

| skill | 何时加载 |
|---|---|
| `aquaflow-known-traps` | **动手写 / 改代码前** —— 坑与判据正本（`§8.1`–`§8.34`：状态倒滚、桶汇总并集、时间区间上界、DTO 丢字段、隔离 worktree 假绿、小程序 js/BOM、弹窗确认没接上、沙箱拿不到子进程输出 / `Get-NetTCPConnection` 漏报端口、并发构建互删 `build/`…） |
| `aquaflow-open-questions` | 动已弃用表 / 水厂端残留 / 微信测试号登录 / 判断 `.workbuddy` 记忆是否过期时 |

> ⚠️ skill 名（frontmatter 的 `name`）必须是 **ASCII 小写 + 连字符**（DSH 校验 `^[a-z0-9]+(?:-[a-z0-9]+)*$`）—— 中文名**不报错，只被静默忽略**（仅留一条 log warning）。

> 其余领域知识见 `docs/architecture/**`（领域模型与数据模型）—— 只放"动手前必须知道的判据"，不写流程。
