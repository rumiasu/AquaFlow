# AGENTS.md — AquaFlow 仓库级 AI 协作指令

> 适用范围：仓库根 `D:\backend\project\AquaFlow` 及全部子目录。DSH/Claude 系 agent 读本文件作为**操作型契约**（命令、入口、禁改、坑）。
>
> **本文件瘦身过：判据全留、叙事外移** —— 瘦身前全文在 `docs/audit/2026-09-17-AGENTS-瘦身前全文存档.md`（68338 字节，超预算 65536 被截断，这是瘦身的起因）。
>
> 冲突时优先级（高 → 低）：**§0 事实基准 + §1 领域不变量 → 本文件其余部分 → 根 `README.md` 与 `docs/**` 其它文档（大面积失真，仅作线索）**。原 `docs/AGENTS.md` 已于 2026-09-18 整体作废并归档（`docs/audit/2026-09-18-docs-AGENTS-旧版归档.md`），`docs/` 下**已无**自动注入的指令文件。
> 证据标注：`【仓】`= 已在仓库文件中直接核对；`【会】`= 来自历史会话日志或 `.workbuddy` 记忆。

## 0. 事实基准（最高优先级）

1. **一切以代码为准**。`README.md`、`docs/**`、`.workbuddy/memory/**` 仅作参考且**已知大面积过期**；与代码冲突时以 `src/**`、`sql/schema.sql`、可运行测试为准。【仓】
2. 唯一可信来源顺序：**当前 Java/WXML/JS 源码 → `AquaFlow-backend/sql/schema.sql` → 通过的集成测试与实际接口行为 → 仓库 Markdown**（`docs/AI_EXECUTION_HANDOFF.md` §0）。【仓】
3. **删除类改动协议（2026-09-19 立，强制）**：删代码 / 端点 / 文件前先只读排查，**报备必须给六项**：① 核实到哪一步（几遍、什么方法）；② 证据（逐条 `文件:行号`）；③ **原来为什么存在**；④ **删掉会怎样**（连带的测试 / 文档 / 常量 / mapper）；⑤ **推荐删或留 + 理由**；⑥ 风险等级。**登记表正本**：`docs/audit/2026-09-16-死端点评估.md` 的「删除登记表」（带核实日期 —— 旧判定会随代码过期，已实测推翻过一条）。**核实纪律**：光跑 `api_reverse_audit.py` 不算，它既有假阳性、**也有假阴性**（`config/api.js` 里**有路径常量**就算"有人调"，于是"定义了没人调"的死包装函数报不出来）。**判据：自己 grep 出所有字符串出现位置，逐个确认是"真调用"还是仅"常量定义 / 文档 / 注释 / 测试断言"—— 定义 ≠ 调用**；零引用确认后再跑一遍全量测试留基线。破坏性操作（删文件、改 git 历史、清库、跑历史迁移 SQL）先报证据与影响并等确认。
4. **数値の SSOT —— 有正本就不要在本文件重述**：表定义看 `sql/schema.sql`、迁移顺序看 `sql/README.md`、枚举值看 `constant/*.java`、API 实路径看 `controller/**` 注解、测试件数看 `build/test-results/test/*.xml`、**结构看 `code_map`（别手写这类计数，必然过期）**。【仓】
5. 仓库内文档的入口是 `docs/README.md`；`docs/**` 已知过期，只作线索、不作规格。【仓】

## 1. 项目概览与领域不变量

- **业务**：桶装水（18.9L）配送管理系统，服务对象是**水站**（站长 + 配送员）；目标是可真实上线的企业级系统。
- **在维护的端只有两个原生微信小程序**：`miniapp-user`（客户端）、`miniapp-delivery`（站长 + 配送员）。**不存在可维护的 Vue 管理后台**（`AquaFlow-frontend` 已删除，仅 `archive/legacy-web-frontend` 留档）。
- **角色**：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）、客户（微信 openid）。`staff.role` 只有前两个；`FACTORY_ADMIN` 与整个水厂端已在 DB/后端/小程序三处彻底移除。

### 1.1 不可凭直觉改写的领域不变量

- **客户是全局身份**：`customer` 表**没有 `station_id` 列**；订单/桶/水票/押金一律按 `(customer_id, station_id)` 隔离。【仓】**经营归属**不是 `customer` 上的一张字段，而是「绑定（`customer_station_config`）∪ 本站订单」的并集口径 —— 业务目的是**保护水站已开发的客户不被别站抢走**。`customer` 表**永远不加** `station_id` / `owner_station_id`（`owner_station_id` 只属于 `product`：NULL = 通用商品库）。【仓 `schema.sql`】
- **三站语义（2026-09-18 起取代旧「双水站模型」）**：`orders.station_id` = **归属站**（客户主动选定的站，**定价方**，`DeliveryFeeUtil` 按它算费）、`orders.delivery_station_id` = **履约站**（谁去送：库存扣它、配送员清单、计件工钱记它）、`orders.settle_station_id` = **结算站** = **本单营收（水费 + 配送费 + 楼层费）归谁**（v47 新增列，正本 `sql/migration_v47_order_settle_station.sql` 文件头）。取值：下单 = 归属站；**抢单 / 定向外派成功后 = 履约站**；召回 / 退回池 / 指定退回-同意 = 回归属站。【仓 `StationUtil.settleStation`】**钱认结算站**：看板、毛利报表（**成本 join 也按结算站**，否则拿 A 站进价算 B 站毛利）、应收账款与核销、**确认收款判权**（原 [AQ-043] 旧口径的作废情况见下方退款那条）、**退款判权**（`PaymentController.requireRefundStation`，旧名 `requirePaymentOwnerStation` 已废）、`payment_record.station_id` 的写入（`recordCashCollection`）。⚠️ **客户资产认归属站**：押金账户 / 水票 / 桶权益（`customer_deposit_account` / `ticket_*` / `customer_barrel_*`）一律**不动** —— 那是客户在哪个站买的账，与谁去送无关。**SQL 读取一律 `coalesce(o.settle_station_id, o.delivery_station_id, o.station_id)`**（末级是**防御**，正常写入路径必须落 `settle_station_id`）。**抢单池 / 他站外派是跨租户可见面**：下发给别站站长的字段**只许带"钱货去向"文案与快照金额**（`DeliveryController` 的 `feeInfoOf`），**不许带归属站的成本 / 库存 / 联系方式**，**也不许下发客户画像**（`customerName` / `customerPhone`；只带订单自身信息 —— 收货人姓名电话快照、地址、商品、金额快照）；与本站既无绑定又无本站订单的客户，其画像端点（列表 / 详情 / 资产）对本站一律不可见。**含押金 / 桶权益的单禁止进抢单池**（直接拒单，不是"确认后可入"）；**定向外派必须双方确认**（外派方先显式勾选知悉风险 → 接收站接单时再确认一次，两处留痕）；不涉押金的普通单保持原状、不加摩擦。**认领之后同样不含画像**：履约站侧的配送员面（待接单 / 配送中 / 今日完成 / 历史 / 回桶记录 / 转给我的单）与站长端「员工画像 · 当前进行中」也只带订单快照，**唯一实现** `util/CustomerProfileMask`（只抹 `customerName` / `customerPhone`；Map 形态的调用方 SQL **必须显式 as 出** `stationId` / `deliveryStationId`，读不到即静默不抹）。**`payment_record` 的待收款流水跟着结算站走**：站别在「发起收款」时写死，订单换站时由 `movePendingToStation` 搬（**只搬 `PENDING`**）；收款走 `confirmPendingToPaid` **就地确认** —— `active_order_id` + `uk_payment_active_order` 是**一单一条活跃流水**，已有待收款再插 PAID 必撞 1062、整笔送达回滚（实测：点过「去支付」的现金单，「已收款」必失败）。**只有收到钱的单才进站长 / 配送员视野**（2026-09-18）：判据两条 —— `payment_status = 2`（微信 / 水票都在**付款成功那一刻**自动出现；水票的扣票在下单后那次支付请求里，故"没扣票的水票单进不了站长端"由判据本身保证，**别**改成下单时扣票 —— 那会让票不够的客户连单都下不出来）或 `payment_method = 2`（现金＝货到付款，钱当面收；客户没开通时**下单即被拒**，故"现金单" ≡ "允许货到付款的客户"）。**判据三处必须一致**（两张列表 SQL + 接单/分配闸门，防"列表看不到但 id 可编造"）；微信未接入期间未付微信单"当不存在"，接入后回调置 2 即自动出现（`TODO(微信支付接入)` 在那三处）。转单状态一律查 `order_transfer` 结构化表，`special_note LIKE` 只许出现在「外派追踪」列表（`OrderMapper.listDispatchedOrders`，它还必须排除 `[指定退回待确认]`）。
- **订单状态**以 `constant/OrderStatus.java` 为准：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`（连续编号；历史 1/3/4/5/6 已废弃）；非法流转由 `isValidTransition` 拒绝。
- **支付方式**以 `constant/PayMethod.java` 为准：`1 微信 / 2 现金(货到付款) / 3 水票`（**水票 = 扣票成功即视同已付**，扣票发生在下单后那次支付请求里，见本节末的派单判据）。**微信支付渠道未接入**，`availableMethods()` 中该选项恒为 disabled。
- **支付状态**以 `constant/PaymentStatus.java` 为准：`0 未付 / 1 待收款 / 2 已付 / 3 已退款 / 4 已取消`；唯一真值是 `orders.payment_status`。
- **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源 `customer_barrel_lot.remain_qty`，`customer_barrel_over` 可为负（= 水站暂存）。
- **桶的四个数**（正本 `docs/design/03-桶权益模型.md`、`11-桶空闲与在途锁定-提案.md`）：权益 = 已到手（`customer_barrel_asset.quantity`，送达时入账）；配送中 = `customer_barrel_in_transit` 的 `PENDING`；持有 = 权益 + 配送中（**仅展示**）；占用 = 权益 + over = **还桶上限**（**不含配送中**）。**下单抵扣与退押金只认权益**：`shortage = max(0, needed − 权益)`，逐桶型（`PaymentServiceImpl.quote` 与 `create.js` 同口径）；配送中的桶结束前**不参与抵扣、也不可退**（取代原 [DEF-5] 的 `− pending`）。
- **任何「按商品 / 按桶型」的桶汇总，必须取 `assets ∪ 配送中(PENDING) ∪ over` 的并集**：`customer_barrel_asset` 只记**已到手**的桶，只遍历它会让「首单还在配送途中」的商品**整行消失**（惯犯，见 §8.16）。**还桶上限一律用「占用」不用「持有」**（否则被拒"交回数超过当前持有数"）。
- **退桶（退押金）与欠桶互斥：「欠着空桶就不许退桶，先还清」**（`BarrelReturnGuardIntegrationTest`）：硬拦两处 —— `BarrelServiceImpl.previewReturn` 返回 blocked（控制器直接拒、不建申请单）、`doRefund` 再查一次 over；按商品各算（A 水的多不能抵 B 水的欠）。**绕开通道**：`StationAdjustmentServiceImpl` 撤桶权益走 `consumeLots` 直连、`OrderBarrelExceptionServiceImpl` 异常单撤桶同理 —— 只应由站长人工发起并留调整单/异常单痕迹，不给顾客或自动流程开口子。
- **「首单」是两处口径的合称**（`docs/design/04-订单与状态机.md`）：下单时 `OrderServiceImpl:453` 写 `orders.first_barrel_order = firstStationAsset && totalNeededBuckets > 0`（本站第一笔**买桶**订单）；`OrderWorkflowServiceImpl:296` 读它，为真则**整段跳过回桶核对**；押金口径同源（首单权益 0 → 收满押金）。用例 `OrderEntryAndInjectionIntegrationTest`。**查写入点必须连 XML mapper 一起查**（`OrderMapper.xml` 别漏）。
- **支付状态只前进、不倒滚**：`0 未付 / 1 待收款` = 钱还没到手，`2 已付款` = 收钱的结果，`3/4` 是终态。现金单**下单即 待收款(1)**；**发起收款**（`createPayment`）与**送达未收款**（`completeDelivery`）都**不许改写它**；唯一写 2 的入口是 `OrderMapper.markPaidIfCollectable`（从 0 或 1 迁入，绝不复活 3/4）。**禁止写 `updatePaymentStatusIf(..., UNPAID, PAID)`**（现金单现值是 1，CAS `expected=0` 恒不命中）；**未收款时不要动 `payment_status`**，「已送达未收款」用现成的 `payment_status=1 待收款`。`DashboardMapper` 待收款口径 = `payment_status=1 且未取消`。
- **`PaymentRecordMapper.updateStatusIf` 参数顺序是 `(id, 目标状态, 期望状态)`，无编译期保护**：SQL 是 `set status = #{status} ... and status = #{expectStatus}`，按 `(id, 原状态, 新状态)` 传参会**恒命中 0 行 —— 不报错、静默什么都没改**。**写 CAS 前先看 mapper 的 SQL，别凭参数名猜顺序。**
- **登录类端点必须按 IP 限流**（`interceptor/RateLimitInterceptor`）：[AQ-040] 锁定按**用户名**计数，换用户名即可绕开（撞库/用户名枚举），**两层正交、按来源 IP 那层不可省**。覆盖 `/api/auth/{login,wx-login,wx-login-staff,dev-login,refresh,change-password}`；计数**按 IP 聚合**；超限返回 **HTTP 429 + `{code:1,message}`**。默认 20 次/分钟，`RATE_LIMIT_ENABLED` / `RATE_LIMIT_AUTH_PER_MINUTE` 可调；多实例须先换集中式计数器（Redis）。**集成测试默认关闭它**，只有 `RateLimitIntegrationTest` 用 `@TestPropertySource` 单独打开。
- **告警分级投递：系统故障 → 系统管理员，运营故障 → 该站站长**（`AlertRoutingIntegrationTest`）：`alert_log` 表（v30）+ `AlertService`，方向由 `constant/AlertType`（`SYSTEM` / `OPERATION`）决定，**不靠字符串比较或调用方自觉**。SYSTEM（`station_id` 必须 NULL）= 对账不平（`ReconciliationService.dailyReconcile`）/ 桶异常补偿执行失败 / 未预期 500（`GlobalExceptionHandler`）→ **不发站长**；OPERATION（`station_id` 必填）= 桶异常待处置（`recordReturn`）/ 补偿已执行 / 异常被忽略 → 站长端只读 `GET /api/manager/alerts`。**三条硬约束**：① 先落库再谈渠道（webhook / 站长微信订阅消息；没配则 `notify_status=LOGGED`）；② 落库走**独立事务**（`REQUIRES_NEW`，必须经代理调用）；③ 失败**绝不连累业务**。**红线**：系统告警没有 HTTP 入口（漏给站长 = 跨租户 + 越权知情），运维直接 `select * from alert_log where alert_type='SYSTEM' order by id desc limit 50;`
- **押金/桶记录方向由类型决定，调用方金额一律传正数**：`DepositType`（`constant/DepositType.java`）收敛为 `isIncrease`（`1/5/9`）/ `isDecrease`（`2/3/4/6/7/8`），**扣减类以负数落库**（`DepositRecordServiceImpl.java:56-64`）—— 对账等式1（`balance == SUM(deposit_record.amount)`）的前提。`BarrelRecordType` = `6 人工调整(增)` / `9 人工调整(减)` 已纳入守恒对账 E5（`ReconciliationService.java:258-259`），不纳入则每次补录都误报。
- **三张流水表的调整场景唯一键**：`uk_deposit_adjustment`、`uk_record_adjustment`、`uk_ticket_adjustment`，配套可空列 `adjustment_id`。`ticket_record` 原有的 `uk_ticket_consume(order_id, product_id, source)` 在 `order_id IS NULL` 时**零保护**（调整场景 `order_id` 为 NULL，MySQL 唯一键中 NULL 互不冲突）。
- **退款只有两个入口，且都必须「原路径返回」**（2026-09-18 定；正本 `docs/design/05-支付与资金.md` §5.5.1）：① **取消订单** → `PaymentService.refundOrder`（`OrderStatus.isCancellable` 门槛：**已完成(4) / 已取消(5) 不得再取消**），同时是订单取消/拒单的单一编排入口（退水票 → 退流水 → 退押金 → 清配送中桶 → 回补库存 → 置已取消）；② **只退这一笔钱**（客户投诉多收/重复付款、订单继续履约）→ `PaymentService.refundPayment`（`PUT /api/payments/{id}/refund`），**不取消订单**。**原路径返回**：水票 → 回补 `ticket_lot` 批次（过批次账，否则 E8 不平）；现金 → 记**负金额**冲正流水（钱由站长当面退，界面文案要说清）；**微信渠道未接入 → 不许假装已退**（手工退款直接拒；取消链不阻断但在 `note` 写明需线下退款）。两条路径的**凭据形状必须一致**（共用 `insertRefundRecord`）、**水票回补口径必须一致**（共用 `restoreTicketsForOrder`）；无订单的在线购票流水退款（`order_id IS NULL` 且 `ticket_qty > 0`）**当前不做**，只给出明确拒绝。⚠️ **站别口径（2026-09-18 二次修订，取代旧「退款认归属站、确认收款认履约站」的不对称口径）**：**收款与退款都认结算站** —— 退款判权 `PaymentController.requireRefundStation`（旧名 `requirePaymentOwnerStation` 已废）；确认收款走 `requirePaymentOrderStation` → `StationUtil.deliveryStation`（履约站），正常数据下结算站与履约站同值。⚠️ **已修订（2026-09-18 落地）**：`OrderWorkflowServiceImpl:469-482` 的 `completeDelivery`（`collected=true`）分支**已改认结算站**（`StationUtil.settleStation`，不再要求归属站）—— 确认收款判权就此与已按新口径放行的 `confirmOfflinePay` 同口径，原 [AQ-043]「跨站单仅原归属站可确认收款」**已作废**（`DeliveryCompleteIntegrationTest` 已按新口径反向断言：履约站确认成功、归属站被拒）。⚠️ **押金与欠桶仍记归属站**，这层区分不随确认收款判权改变。
- **客户端与员工端是「两个小程序」，appid 不同**：`wx.login` 的 code 只能用**签发它的那一端**的 appid+secret 换 openid（用错端只回 `40013 invalid appid`，日志无指向性），故 `WeChatLoginService.code2Session(WeChatApp, code)` **强制显式传端**（`constant/WeChatApp.java`：`CUSTOMER` / `STAFF`）；openid 按 appid 隔离，`customer.openid` 与 `staff.openid` 互不干扰。配置键：客户端 `wechat.miniapp.appid/secret`、员工端 `wechat.miniapp.staff-appid/staff-secret`；**员工端这对本地缺失只 `log.warn`（dev-login 兜底），`prod` 必填（缺即拒启）**。本地客户端用开发者工具「**小程序**测试号」，**小游戏号填进小程序项目会编译失败**（见 §9）。
- **站长治理类入口**：资产调整单 `/api/manager/adjustments`（6 端点，类级 `@RequireRole("STATION_MANAGER")`）；`/api/manager/reconciliation` **只读**本站即时对账（**只有 `GET /`**；原 `POST /run` 写全平台结果 = 跨租户泄露，已删除、**不要加回**；运维记录由 03:00 定时任务落表），结果落 `reconciliation_result` 表。
- **公告（v32 起站长可发）**：`notice.status` = `0 下架 / 1 发布`，`type` = `1 系统公告 / 2 水站通知 / 3 活动`。**站长端管理列表必须包含草稿与已下架**（`NoticeMapper.listForStation` 不带 status 过滤 —— 带上 `status = 1` 会让"保存草稿后列表里没有它""点下架后从列表消失、再也点不回来"）。状态文案由 `Notice.getStatusText()` 下发（正本 `constant/NoticeStatus.java`），前端禁止自带映射表。⚠️ **`GET /api/notices`（顾客端列表）不做站过滤**，任何顾客能看到**所有水站**的已发布公告（`[AQ-038]` 只修了"按 id 读草稿"那条）—— 未修，口径与建议见 `docs/design/13` §9。
- **欠桶「只提醒、不阻断」**：原 [AQ-030]/[DEF-3] 的硬拦 `MAX_OWED_BUCKETS = 5` 已**移除**、**不要再加回**；下单响应 `warnings` 每次都提醒客户（幂等命中路径同样下发），**物理护栏不变**（占用 = 权益 + over ≥ 0）。`customer_barrel_over.owed_since`（v29）只用于展示、**不参与任何校验**；唯一维护点 `CustomerBarrelOverMapper.syncOwedSince`（over 由 ≤0 变 >0 写入、回到 ≤0 清空、已是正数再增加不重置）。站长端 `GET /api/manager/owed-barrels`（只读）。
- **迁移清单的正本是 `sql/README.md`（逐条带真实库执行证据与备份文件名）—— 本文件不再复述**。**新建迁移前先 `ls sql/` 看编号，别照任何清单的最后一个数字 +1**（本文件曾写「迁移已到 v47」并逐条列出，之后又加了 v48~v50：手写清单必然过期）。只留跨条目的判据：**v32 是软状态：不阻断下单、只提示**（正本 `docs/design/13-营业状态与公告.md`）；**v41 是唯一破坏性 DROP**（先上代码再执行 SQL；脚本自带"列内有值就中止"护栏，删列前先 `mysqldump` 到 `backup/`）；**MySQL 解析期校验列名**：对某列的 `MODIFY`／`WHERE col` 即使不执行到也会解析报 1054 —— 删列时必须把**历史迁移里对该列的引用一并清掉**（v41 即因此改了 v24）。
- **配送员计件工资是站长台账，且走独立对账**（v37；正本 `docs/design/18-配送员计件与工资.md`）：`staff_piece_rate`（站级单价，`product_id=0` = 该站默认价）+ `staff_earning`（收益明细）+ `staff_payroll`（结算单 草稿→已确认→已发放）；**写入口是 `StaffEarningService`**。① 发钱的是站长不是平台（不做打款/提现，只落 `paid_time` + `operator_id`）；② 计件单位是桶不是单，按 `order_item` 逐商品计（`auto_uk` 含 `product_id`）；③ 方向由 `kind` 决定、调用方传正数，唯一例外 `ADJUST`（`constant/EarningKind.allowsSignedAmount`）；④ 归属站 = 履约站（`delivery_station_id`）。**收益只在 `completeDelivery` 的状态 CAS 成功之后产生**；**工钱不进客户对账**（独立等式 **E-PAY**，告警 **OPERATION**）；`staff_earning.auto_uk` 的 **NULL 是有意的**，幂等由 `uk_earning_auto`（生成列）兜底 —— 与 `uk_ticket_consume`/`uk_payment_active_order` 的"NULL 零保护"形状相同但**语义相反**。用例 `StaffEarningAndPayrollIntegrationTest`。
  - **v44「自定义工资条目」只是人工调整流水上的标签**（`EarningItemDirection`：1 加项 / 2 扣项）：不参与自动计算、不进对账；**传了 `itemId` 只收正数**、**用过的条目只能停用不能删**、`item_name` 是写入时快照；`itemSummary` **不等于**未结合计。同批修掉越权：`POST /payroll/adjust` 原不校验 `staffId` 归属 → 现按「本站员工 **或** 在本站有过收益」并集放行（「我的工资」只按 `staff_id` 过滤）。用例 `StaffEarningItemIntegrationTest`（`docs/design/18` §10）。
  - **结算单期间上界必须用「结束日 + 1 天」**（`attachToPayroll` 的 `endExclusive`）：写 `<= 结束日` 会让**当天收益一条都结算不到**（同 §8.19）。
- **水票余额的真相源是 `ticket_lot`，`ticket_account` 只是派生汇总**（v36，同构于桶账 `customer_barrel_lot` → `customer_barrel_asset`；正本 `docs/design/19-水票档位与批次单价.md`）：`remain_quantity == Σ lot.remain_qty`、`right_amount == Σ remain_qty × unit_price`，由对账 **E8** 校验；**批次唯一写入口是 `TicketLotService`**（`createLot` / `consumeFifo`）。① **单价取实付均价**（`payment_record.amount / ticket_qty`）；② **消耗按 FIFO**，退款回补按**流水里的当时单价**还原（`ticket_record.unit_price`）；③ **任何改动水票数量的路径都必须过批次账**（在线购票入账、站长加票、用票支付、订单取消回补、资产调整单），漏一条 E8 就报不平。用例 `TicketPackageAndLotIntegrationTest`。**夹具 `createTicketAccount` 也必须建批次**（只插账户会打红 10 个现有用例）。
  - **MySQL 的 `SET` 从左到右求值，后面的表达式看到的是已更新的列值** —— `SET remain_qty = remain_qty - q, status = CASE WHEN remain_qty - q = 0 ...` 里的 `remain_qty` 已是 0，CASE 永远算不出 0。**要把 `status` 赋值排在 `remain_qty` 前面。**
- **配送计费（起送量 / 配送范围 / 运费 / 楼层费）只有一份实现**（v35；正本 `docs/design/17-配送计费与配送范围.md`）：纯规则在 `util/DeliveryFeeUtil`，IO 在 `service/DeliveryFeeService`；**`PaymentServiceImpl.quote` 与 `OrderServiceImpl.createOrder` 必须调同一个 `calcForOrder`，传同样的水费与桶数口径**（桶数只数 `category=1`）；一侧内联算费用 = 重演"计价双轨"事故（见 `PriceUtil` 文件头）。① **费用绝不并入 `water_amount` 或 `deposit_amount`**（后者可退，混入会导致取消订单多退钱），各自成列；② **门槛默认 WARN 不是 REJECT**；③ **拿不准就不收/不判**。用例 `DeliveryFeeUtilTest` + `DeliveryFeeIntegrationTest`（报价 `totalAmount` 必须等于下单 `total_amount`）。
- **无订单支付（在线购票）必须带客户端幂等键**（v33）：`order_id` 为 NULL 时 `PaymentServiceImpl.createPayment` 的重复流水检查（包在 `if (orderId != null)` 里）被跳过，`uk_payment_active_order` 建在生成列 `active_order_id` 上、**NULL 互不冲突 → 这条路径零保护**。`POST /api/tickets/purchase` 的 `idempotencyKey` **必传**；`uk_payment_idempotency(customer_id, idempotency_key)` **必须带 `customer_id`**。`confirmPayment` 的乐观锁只管**单条**流水，**管不住重复流水**。用例 `TicketPurchaseIdempotencyIntegrationTest`。
- **对账等式不能把「合法业务状态」算成差异**（三处实测均表现为**日结永远不平**；正本 `docs/design/07-数据一致性与对账.md`）：① **等式2 `p2a`**：`ReceivableService.settle` 只调 `markPaidIfCollectable` 置 `payment_status=2` 而不补 PAID 流水 → 每核销一单就报不平；**收款必须两步**：先 `PaymentService.recordCashCollection(orderId, note)`（幂等）再 `markPaidIfCollectable`，**不许另写"补流水"实现**。② **等式2 `p2c`**：`order_id IS NULL` 不等于孤儿（**在线购票无订单**），判据是 `AND p.ticket_qty IS NULL`。③ **等式3 `b3a`**：`customer_barrel_in_transit.status='DELIVERED'` 是**合法终态**（不物理删除），改为「标了已送达却没有对应权益批次」。**写对账等式前先问"这个状态在正常经营里会不会合法出现"，会就不能进差异计数。** 用例 `ReconciliationAfterNewFeaturesIntegrationTest`（V1/V2 每项为 0）。
- **应收账款 = 给「待收款」加账期维度，不新造金额口径**：金额真相源仍是 `payment_status = 1 AND status <> 5`（同 `DashboardMapper` 待收款口径）；**没建新列**，激活原有挂空列 `orders.settlement_status` / `orders.due_date`；账期由 `ReceivableService.resolveDueDate` **下单时快照一次**（**只有现金单有应付日期**）。端点 `/api/manager/receivables*`、`/customers/{id}/credit-terms`。① **核销 ⟹ 已收款**（`OrderMapper.settleIfCollected` 的 CAS 带 `payment_status = 2`），收款仍要**两步**（同对账等式①）；② **逾期只提醒、不改金额**；③ 对账 **E10**（`settlement_status = 2 AND (payment_status IS NULL OR payment_status <> 2)`）**只查单向**（反向是**正常经营状态**）；④ **设账期必须用只改 `due_days` 一列的 mapper，不要用 `CompanyInfoMapper.updateByCustomerId`**。**归属判据只认并集 `CustomerMapper.countCustomerOfStation`（绑定 `customer_station_config` **或**本站订单），不要用 `getStationCustomer`**（后者按订单算，会把**没下过单的新客户**判成"不属于本站"；`GET /api/customers` 是 orders 驱动、员工下单护栏原是绑定驱动 —— 同一口径坑已多次踩：客户特权、应收账款、代客下单护栏、更早的 `OrderController.getMyLatestStation`）。**代客下单复用 `POST /api/orders/create`，不要新建单端点**（两条建单路径算金额 = 计价双轨）；三个读接口在 `/api/manager/order-assist/*`；`GET /api/addresses` 与 `POST /api/payments/quote` 是**顾客自助**端点。用例 `ReceivableIntegrationTest`、`EmployeePlaceOrderIntegrationTest`。

## 2. 目录结构与关键入口

- **仓库结构问 `code_map`，不要照抄任何手写清单**（见 §0.4）。三条常年有效的位置判据：
  - 后端唯一入口 `AquaFlow-backend/`（Spring Boot + MyBatis；集成测试在 `src/test/java/.../integration/`）；SQL 基线 `sql/schema.sql`，迁移执行顺序以 `sql/README.md` 为准。
  - **CI 门禁 `.github/workflows/ci.yml` 必须在仓库根** —— 放进 `AquaFlow-backend/` 下 GitHub 不读。
  - `archive/**` 不维护；`backup/` 是本机备份、已被 gitignore，**勿提交**。

- 后端 API 统一响应 `{ code, message, data }`：`code 0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常；**业务错误 HTTP 状态仍是 200**（唯一例外：未认证返回真 401）。【仓 `common/Result.java`】
- 小程序 API 基址在各自的 `config/api.js`；**两端 `prod.baseUrl` 目前都是占位符** `https://your-domain.com`（见 §8.10）。
- **「不校验合法域名」= 两个位置，缺一不可**：① `project.config.json` 的 `urlCheck`（`miniapp-user` 已 `false`；`miniapp-delivery` 仍是 `true`，靠 gitignore 的 `project.private.config.json` 覆盖 —— **换机器 clone 后必须重新勾一次**）；② 真机上右上角 `…` →「打开调试」。少任何一个，本机 `http://<局域网IP>:8080` 的请求都会报「不在以下 request 合法域名列表中」。【仓】

## 3. 技术栈与本地运行 / 构建命令

| 项 | 值 |
|---|---|
| 后端 | Java 17（toolchain）、Spring Boot **4.0.6**、MyBatis-Spring-Boot 4.0.1、Jackson 3 |
| 构建 | Gradle Wrapper **9.4.1**（`gradlew.bat`）、Lombok、腾讯云 COS SDK |
| 数据库 | MySQL 8.x（本机 CLI：`D:\backend\MySQL\bin\mysql.exe`），库 `aquaflow` / 测试库 `aquaflow_test` |
| 小程序 | 微信原生（libVersion 3.17.0），无框架、无分包；**两端 appid 不同**（正本见 `miniapp-*/project.config.json`） |

```powershell
# —— 后端：编译 / 启动（端口 8080）——
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat clean compileJava
.\gradlew.bat bootRun
```

- **环境变量**：`.env.example` 是清单的权威来源。**启动期硬校验只有 3 项**（`config/RequiredConfigChecker.java`）：`JWT_SECRET`（**长度 < 32 也拒绝启动**）、`WX_APP_ID`、`WX_APP_SECRET`，缺一跳 `IllegalStateException` 拒绝启动。【仓】
- **其余变量不被 `RequiredConfigChecker` 检查，缺失后果由各自组件决定 —— 不要再写成「缺一即启动失败」**：`COS_SECRET_ID/KEY`、`WX_STAFF_APP_ID/SECRET` 未配置只 `log.warn`（COS 只影响上传；员工端只影响真机微信登录，dev-login 兜底、`application-prod.yml` 里该对无默认值、`prod` 缺失即拒启）；`DB_*` / `CORS_ALLOWED_ORIGINS` / `DEV_LOGIN_ENABLED`（**生产必须 `false`**）/ `MYBATIS_LOG_IMPL` / `RATE_LIMIT_*` 同理。【仓】
- 本地默认 profile 是 `local`，密钥读 `src/main/resources/application-local.yml`（**已 gitignore，含真实密钥，禁止提交、禁止回显**）；生产用 `--spring.profiles.active=prod` + 纯环境变量。
- 小程序：用微信开发者工具分别打开 `miniapp-user` / `miniapp-delivery` 目录（无 npm 构建步骤）。
- PowerShell 里**用 `;` 分隔多条命令，不要用 `&&`**。长任务（Gradle 构建、测试）放后台任务。【仓】

## 4. 数据库与迁移流程

- **Flyway 未启用**（无 flyway/liquibase 依赖、无相关配置）。`sql/**` **全部靠手工执行**，没有版本表、没有自动校验。（`src/main/resources/db/migration/` 目录**不存在**，勿按该路径找脚本。）
- **新建库的权威基线是 `sql/schema.sql`**（全部 `CREATE TABLE IF NOT EXISTS`，可重复执行）；`init.sql` 只建结构、不含种子数据，且 `SOURCE schema.sql` 依赖相对路径，**必须在 `sql/` 目录下执行**。表数以 `Select-String -Pattern '^CREATE TABLE'` 实测为准。
- **`schema.sql` 导入必须走字节级重定向**：`cmd /c "mysql -uroot --default-character-set=utf8mb4 库名 < schema.sql"`（CI 上是 bash 的 `<`）。**不要用 PowerShell 管道**（`Get-Content -Raw | mysql`，按控制台代码页重编码会把中文注释变乱码）。⚠️ **核对是否写坏要比字节（`HEX(TABLE_COMMENT)`），不要看控制台**。【仓】
- **老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑（**以该清单为唯一权威**）。执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。⚠️ **清单存在已知缺陷**：漏列 `v3`/`fix_schema_alignment`，且 `v25` 改名与 `v1_backfill` 依赖旧表名导致顺序冲突 —— **执行前需人工核对**。【仓】
- **迁移脚本必须幂等**：MySQL 8.4 无 `DROP ... IF EXISTS`，统一用 `information_schema` 预检 + `PREPARE`。**破坏性 DROP 必须先上代码、再执行 SQL**；新建脚本前先 `ls sql/` 看命名是否占用。【会】
- **严禁在生产执行**：`reset_data.sql`（TRUNCATE 多表）、`reconcile_order_814.sql`、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`（已过期且不可执行）。【仓】
- `sql/` 里**大量脚本是 SUPERSEDED/DUPLICATE**（见 `sql/README.md` 表格），已被 `schema.sql` 吸收，**不要在新环境执行**。

```powershell
# —— 全新库初始化（只建结构，空库）——
cd D:\backend\project\AquaFlow\AquaFlow-backend\sql
& 'D:\backend\MySQL\bin\mysql.exe' -u root -p < init.sql
```

## 5. 测试与验证方式

- 集成测试位于 `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/`；基类 `support/AbstractIntegrationTest` 启完整 Spring 容器、发真实 HTTP（JDK `HttpClient`）、每用例前 TRUNCATE 并**断言当前库名含 `test`**；件数以 `build/test-results/test/*.xml` 为准。
- **本机无 Docker**，因此不用 Testcontainers；测试库 `aquaflow_test` 是独立可重建库。
- **运行前置（硬要求）**：必须显式设置 `GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'`，否则 Gradle 往沙箱外的用户目录写缓存、被拒后直接失败；`--project-cache-dir .gradle_alt`（旧文档给的）**不足以**解决。【仓】
- **Gradle 锁坑**：若后端 `bootRun`（8080）在跑，直接 `gradlew` 会因 `fileHashes.lock` 失败 —— 统一加 `--no-daemon`（必要时先停后端）。
- **bash 用 Git 自带的** `D:\backend\Git\bin\bash.exe`（`Get-Command bash` 会解析到 `WindowsApps\bash.exe` 存根，报 `E_ACCESSDENIED`）；**受限沙箱下 Cygwin 起不来**（`couldn't create signal pipe, Win32 error 5`）。`python` 用 `D:\agent\python\python.exe`。⚠️ **跑含 `↔` 等非 GBK 字符的 python 脚本前先设 `$env:PYTHONIOENCODING='utf-8'`**（否则 `print` 抛 `UnicodeEncodeError`，表现为"脚本没问题却 exit=1"）；**别把脚本路径写进自定义函数的 `$args`**（自动变量，会让 python 无参启动、进 REPL 后 exit 0）。【仓】

```powershell
# —— 后端集成测试（本机唯一可跑通的入口）——
$env:GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat test --no-daemon --project-cache-dir .gradle_eval
```

- **断言看响应体 `code`，不看 HTTP 状态**（业务错误仍 200；未认证才是 401）。新增用例：继承 `AbstractIntegrationTest`，用 `createStation/createStaff/createCustomer/createProduct/createOrderFull/createOrderCrossStation` 造数，`customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete` 发请求，`intOf/decimalOf` 查库；并发参考 `ConcurrencyIntegrationTest.fireTogether`。【仓】
- **统计测试件数**：把所有 XML 相加，用 `$d = New-Object System.Xml.XmlDocument; $d.Load($path)` —— **不要 `Get-Content -Raw` 再转 `[xml]`**（按 ANSI 解码会弄坏测试名、**静默少算**）。【仓】
- **验证 CI 会不会绿，就在本机复现 CI 三步**：① `DROP DATABASE aquaflow_test; CREATE DATABASE aquaflow_test;` 后**字节级重定向**导入 `sql/schema.sql`；② `.gradlew.bat cleanTest test`（必须 `cleanTest`，否则报 `:test UP-TO-DATE` 而**根本没跑**）；③ **把 CI 的环境变量也照抄一遍**（尤其 `DEV_LOGIN_ENABLED=false` —— 本机 `application-local.yml` 是 `true`，两者不一致会让"只在 CI 上红"的用例长期隐身：2026-09-19 就抓到一条，见 §8.27 末段）。【仓】
- **按业务场景组织的覆盖地图见 `docs/audit/2026-09-16-场景测试矩阵.md`** —— 新增用例前先看它找空白。

## 6. 代码约定与风格

- 后端分层：`Controller` 只做认证 + DTO 校验 + 调服务 + 返回 `Result<T>`；**禁止 Controller 直接写 `orders` / `payment_record` / 库存 / 桶资产表**，订单状态与副作用只能经 `OrderWorkflowServiceImpl` 这类编排服务完成。
- **所有状态改写必须 CAS 并检查受影响行数**（`updateStatusIf` / `updatePaymentStatusIf`）；无 expected-state 的 `updateStatus` / `updatePaymentStatus` 属于待清除的旧路径。
- 业务前置不满足一律抛 `BusinessException`（→ `code=1`），**不要用 `RuntimeException`**（会被兜成 500）；**不要在被 `@Transactional` 注解的方法内 catch 业务异常**（会抛 `UnexpectedRollbackException`，把正常业务拒绝伪装成 500）。
- 权限：`@RequireRole({"STATION_MANAGER"})` + `@RequireStation`，由 AOP 切面 `execution(public * controller..*.*(..))` 统一保护，新增方法自动生效。**跨站校验一律以 `AuthContext` 中服务端刷新的 `stationId` 为准，不信任请求参数**；客户 ID 必须由登录态覆盖或与订单所有者严格比对。
- MyBatis：注解 SQL 用下划线列名（配 `map-underscore-to-camel-case: true`）；**注解 SQL 无编译期校验，新写必须手工在真实 MySQL 上跑过**。
- 金额、客户、订单归属、水票数量一律服务端推导或强校验。展示文案（`statusText` / `payMethodText` / `payStateText`）由后端下发，**前端禁止自带 1/2/3 映射表**（两端各写一套曾导致新客下单 100% 失败）。
- 写库顺序：先 `getByClientToken` 判断幂等再动手；Controller 调 service 后再写库必须 `@Transactional`。
- 日志禁止记录密码、JWT、微信授权码、完整手机号/地址、任何密钥。**回复中也不回显密钥**（用 `<redacted>`）。
- 术语统一：**配送中**（= 已付款买下桶权益但未送到，旧称「在途」**已禁用**）、**进行中**（= 待配送 1 + 配送中 2）。表名 `customer_barrel_in_transit` / 类名 `CustomerBarrelInTransit` 仅为兼容历史命名保留，注释与文案一律写「配送中」。`customer_owed_barrel` 已停止写入，欠桶改读 `customer_barrel_over`。
- 小程序：`wxml` 内禁止调用 Page 方法 / `Math.` / `Date.`；`wxml` 绑定的事件处理函数必须真实存在，否则点击**静默无反应**；注意 `require` 相对层级；后端 `/api/delivery/orders/{id}/xxx` 用模板串拼接。

### 6.1 注释契约（2026-09-14 立规，强制）

本仓库的注释**不是可选项** —— 它同时是下一个 AI 的**操作依据**。

1. **改代码必须同步改注释**：注释与代码不符**比没有注释更危险**（没有注释时人会去读代码，有错误注释时人会直接照做；三次真实事故见 §8.14）。**作废的 javadoc 必须删除，不能悬空留着占位。**
2. **分工：流程 / 规则写文档，代码注释只写「改这里会踩什么坑」。** 业务规则 / 领域模型 / 状态机 / 资金口径已写在 `docs/design/`，**代码注释不要复述它们**。
   - **该写注释**：反直觉约束（"金额方向由类型决定，调用方传正数"）、历史事故、"别加回来"的护栏、并发与加锁顺序、唯一键 / 幂等陷阱、"本类不是桶账写入口"这类边界声明；**该写文档**：业务流程 / 状态流转 / 字段口径 / 交互；**都不写**：`getXxx` / `setXxx`、直白循环与判空。确需提业务规则时**用一行指向文档**（如"见 `docs/design/04-订单与状态机.md`"）。
   - **正面样本**：`constant/PayMethod.java`（"前端曾把 2/3 写反导致下单必失败"）、`constant/AdjustType.java`（"方向由类型决定"）、`BarrelLedgerService`（加锁顺序防死锁）。**反面样本**：把整段业务背景抄进 Controller。
3. **修完缺陷就地留评论**：在**出问题的源头**（不只写在测试里）注明「原来是什么 / 为什么错 / 后果是什么 / 正确做法」并标日期。
4. **新增端点必须写明归属与调用方**：员工端点标 `@RequireRole`；顾客自助端点写明身份取自 `AuthContext`（见 `aspect/RequireRoleAspect.java`）；小程序侧注明「顾客端能不能调」。
5. **参数必填性、枚举取值、接口路径、表结构**这四类说明最容易过期，一旦改动必须当场同步。
6. 新增类 / 公开方法 / 非直觉分支要补 javadoc；**纯 getter/setter、显而易见的循环不补** —— 注释的价值是"降低误用概率"，不是覆盖率。

## 7. 协作注意：不要动 / 属于生成物

- **动手前先 `git status` 核对；不要顺手混入无关改动，也不要替用户提交**；工作区常有大量未提交改动，review 后再决定提交。
- 未获明确要求**不要 `git commit` / `git push`**；改动留在工作区供 review。当前分支 `master`。**提交数不要硬编码**（以 `git rev-list --count master` 为准）。
- **提交粒度与信息**：一次提交只做一件事；message 写「改了什么 + 为什么」，**不写过程叙述**，**不记录工具、环境或个人账号变动**。判据：这条 message 对三个月后排查问题的人有用吗？
- **提交规范 hook 已入库但默认未启用**（`.githooks/`）：`commit-msg` 强制 `<type>(<scope>): <subject>`；`pre-commit` 在暂存文件数 > 30 时拒绝提交。启用：`git config core.hooksPath .githooks`。⚠️ 受限沙箱下 Git 自带 `sh.exe` 起不来，启用会让每次 commit 失败。
- **不要改 `archive/**`**（`legacy-web-frontend`、`miniapp-station` 均为历史留档，后者缺 `app.js`、页面残缺）。**不要引用 `miniapp-station`**。
- **生成物 / 勿手改**：`AquaFlow-backend/build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`docs/design/*.html`、`tabbar-icons-preview.png`、`project.private.config.json`。另 `.gradlehome/`、`.gradle_alt2/`、`.gradle-user`、`.gradle_alt3`、`.dsh-code-index/`、`.openvisio/` 都是工具缓存，属临时产物。
- **`.gitattributes` 已加入**（`* text=auto` + `*.sh/*.py/*.yml/*.sql eol=lf`）：blob 一律存 LF，防止 Windows 检出 CRLF 后 `bash scripts/*.sh` 在 CI（Linux）上因 `\r` 失败。
- **根目录的 `*.py` 分两类**：**5 个已入库**（`audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py`、`audit_js_syntax.py` —— CI 与 `scripts/verify.sh` 的静态扫描门禁，`.gitignore` 对 `*.py` 开了 `!` 例外，**不入库则新克隆的 CI 必然失败**；前两个需传端名，`page_reach_audit.py` 还能识别 js 的 `url:` 与 wxml 的 `data-url="/pages/..."`）；另若干（`e2e_user_test.py`、`gen_tabbar_icons.py`、`api_reverse_audit.py`）属调试/审计残留，不可作为项目入口或规范依据。
- **`api_reverse_audit.py` 的已知偏差**：① 行号按**剥离注释后**计，**系统性偏早**（报 `:182`／实际 `:198`）—— 引用前回原文件核对；② 判定**只看路径、不看 HTTP 方法**，**报的数字是下界**；③ 认不出「**页面内路径常量拼接**」与「路径段由**函数参数**传入」的调用（前者已修：45→34，后者仍看不见），**在用端点会被判死**。**清单是候选、不是结论** —— 要动"删除端点"这类决定，必须自己重新证一遍零引用（见 §0.3）。

## 8. 已知坑与历史教训

> **每条只留「判据」**；发现经过、误判原因与排错时间线在 `docs/audit/2026-09-17-AGENTS-瘦身前全文存档.md`。
> ⚠️ **编号是外部引用锚点**（`docs/design/**` 与测试注释会写 §8.15 / §8.16 / §8.19 / §8.20 等）—— **不要重排、不要合并条目**，只压正文。

1. **接口报错但 HTTP 200** —— 只判 HTTP 状态会把业务失败当成功。**一律判 body `code`。**
2. **MySQL REPEATABLE READ 下的并发桶账** —— 只加行锁不够：第二个事务拿到锁后，普通 `SELECT` 读到的**仍是旧快照**（实测两个请求都读到 `overBefore=0`）。**并发写路径必须「加锁 + 当前读 `FOR UPDATE`」两件套**；`INSERT ... ON DUPLICATE KEY UPDATE` 必须 upsert。
3. **取消订单的"钱货分家"** —— 跨站外派单取消：钱/票记**归属站**、库存回补记**履约站**（曾混用，真丢钱）（订单**营收**的归属见 §1.1 三站语义：交付完成后归结算站）。`deposit_record.related_order_id` 必须落库。**是否释放押金要锚定「有没有入账凭据（PREPARE 流水）」，不能只看 `orders.deposit_amount`**（那是应收）。
4. **水票是唯一「下单即视同已付」的方式** —— 它绕过 `confirmPayment`，所以押金入账必须在 `TicketAccountServiceImpl` 那条路径自己补；否则客户用票付了押金、账户是 0，退桶退不出钱。
5. **`AbstractIntegrationTest.resetDatabase()` 会 TRUNCATE 全表** —— 靠「断言库名含 `test`」做最后护栏；**改测试数据源前先看这条护栏**。
6. **Spring Boot 4.0.6 已移除 `TestRestTemplate`** —— 测试用 JDK `HttpClient` + `@Value("${local.server.port}")`。
7. **Jackson 2/3 并存** —— Web 层是 Jackson 3（对它设 `WRITE_DATES_AS_TIMESTAMPS` 会启动失败）；手工 `new ObjectMapper()` 拿到的是 Jackson 2。时间统一 ISO-8601，前端一律 `new Date(str)`，**禁止 `.replace(/-/g,'/')`**。
8. **`station.offline_payment_enabled` 已从真实库删除** —— 货到付款唯一控制点是 `customer_station_config.offline_payment_enabled`（客户级、站长逐个开通）。漂移已消除，只在历史脚本与 `backup/*.sql` 里留存，属预期。
9. **`DEV_LOGIN_ENABLED` 默认关闭、生产必须 `false`** —— 但 `miniapp-delivery` 登录页**无条件渲染「开发者登录」按钮**，不检查 `__wxConfig.envVersion`。
10. **两端 `config/api.js` 的 `prod.baseUrl` 是占位域名** —— 发版前必须替换。
11. **文档漂移实例（不要照着做）** —— 曾点名根 `README.md`（`AquaFlow-frontend`／`seed_full_data.sql`／`1/3/4/5/6`／`station_payment_config`／`ManagerOrderController`）与 `docs/AGENTS.md`（`status=3`），**均已订正**。**判据一：点名某文档漂移前先复核**；**判据二：本机有一批有意不入库的文档**（规则在 `.git/info/exclude`），**不要把它们写进任何已入库文件**（README、本文件、代码注释都不行）。文档与代码冲突时以常量正本（如 `OrderStatus.java`）为准。
12. **「测试库全绿 ≠ 真实库可用」** —— 测试库由 `schema.sql` 建、结构永远等于基线；真实库是历史累积的，**索引可能停在旧形态**（实测：`uk_payment_order_status`、`uk_ticket_consume` 未纳入 `source` → 退款/回补**必然 1062**，而 78 个用例全绿毫无察觉）。**判据（长期有效）：涉及唯一键/索引的改动，必须到真实库核对 `information_schema.STATISTICS`，并优先用「事务内造数 → 观察是否成功 → ROLLBACK」的行为法验证。**（该漂移已核实消除）
13. **不要照真实库反向改 `schema.sql`** —— 先判定哪边对，再把两边同时改齐。（2026-09-16 实测 `aquaflow` 与基线两方向差额均为 0。）
14. **注释会诱导误用（三次事故的共同诱因）** —— `StationController./mine` 上方曾长期留着悬空 javadoc「当前登录**客户**选择的服务水站」，而它其实是**员工**接口；顾客端据此三次误调同一类接口。⚠️ **这类拒绝不是真 403**：`RequireRoleAspect` 抛 `BusinessException`，被兜成 **HTTP 200 + `code=1`**。**判据：注释是被信任的契约 —— 悬空/过期注释必须删、改代码必须同步改注释**。定位手法：搜「`*/` 后紧接 `/**`」（门禁脚本 `audit_comments.py`，已进 CI 与 `scripts/verify.sh`）。
15. **「请求体从裸 `Map` 收敛成强类型 DTO」会静默丢字段** —— `DeliveryOrderActionDTO.Complete` 漏抄 `collected` 与 `note`，而 service 一直在读、配送端一直在发；**Jackson 对未知字段静默忽略，不报错、不进日志** → 配送员点「已收款」一律被当「未收款」：订单停在已送达(3)、`payment_status` 从待收款(1) 被改写成未付(0)、**钱不入账也没有 PAID 流水**、备注写不进 `orders.special_note`。**判据：改强类型 DTO 必须逐字段核对老 Map 的键名与前端实际发送体（grep 前端调用点），并补一条走 HTTP 的用例**。用例 `DeliveryCompleteIntegrationTest`。
16. **「只遍历 `customer_barrel_asset`」是本仓惯犯（第 4 次）** —— 见 §1.1 的并集不变式。**占用口径在 `getBarrelSummary` 与 `getBarrelSummaryByType` 之间必须永远相等。** 用例 `occupiedCountsOwedBarrelsEvenWithoutRights`。
17. **「要求了补偿却没执行」也必须失败，不能标成已执行** —— 异常单补偿的退水票分支曾在 `adjustProductId` 为空时只 `log.warn`、然后照样把异常推到 `EXECUTED`（界面显示"已补偿"、水票一张没多，与 §8.15 同属"静默成功"陷阱）。现抛 `BusinessException`：整体回滚、退回 `STAFF_RECORDED` 可重试。**判据：任何"用户以为做成了、账上没动"的分支都算缺陷，宁可失败出声。** 用例 `BarrelExceptionFlowIntegrationTest.refundTicketsRequiresProductThenCreditsAccount`。
18. **订单状态不许倒滚** —— `PaymentService.unconfirmOrderCollection` 曾把 已完成(4) 改回 已送达(3) 且**不动 `payment_status`**；已删除（当时全仓零调用点）。**护栏**：`PaymentFlowIntegrationTest.noUnconfirmCollectionEndpoint` 断言 4 条可能路径全部 404。**通用规则：状态只前进；要表达异常态就新设一个状态，不要复用/回退已有的。**
19. **时间区间上界写成 `<= 当天` 会漏掉一整天** —— `LocalDate` 今天在 SQL 里等价于 `<= 今天 00:00:00`，**今天新增的记录一条都统计不到**（看板永远停在昨天）。**判据：按「某天（含）」筛 datetime 列，一律 `>= 起始 AND < 结束+1天`，不要用 `<= 结束日`。** 用例 `ExceptionStatsIntegrationTest.statsIncludesToday`。
20. **「按 id 操作记录」必须逐条验证归属，且必须检查受影响行数** —— 三处实例：`OrderTemplateServiceImpl.save` 传别人的模板 id 会**覆盖别人的模板并连带清空其明细**（[AQ-036]）；`AddressServiceImpl.delete` 的 SQL 带了 `and customer_id=?` 但 service **不看返回值**、控制器无条件 `Result.success()` → 客户端收到"成功"、刷新又冒出来；`setDefault` 先 `clearDefault(自己)` 再 `setDefault(任意id)` 不校验归属 → **清掉自己的默认、把别人的改成默认**。**判据：接口收一个客户端可编造的 id，就必须回答"这条记录属于调用者吗"；拿不到行数就别返回 success。** 用例 `AddressAndOrderTemplateIntegrationTest`。
21. **对象存储未配置（或不可用）不得升级成系统异常** —— COS 未配时上传/删除抛运行时异常 → **HTTP 200 + `code=500`**（还顺带触发一条 SYSTEM 告警），且 DB 行仍在。**判据：可预期的运维状态给业务错误（`code=1` + 可读文案）并记 ERROR 日志；删除路径可降级为 WARN 后继续清 DB 记录。凡是"外部依赖没配/挂了"的分支都不该表现为 500。** 用例 `FileUploadIntegrationTest`（判据看 body 的 `code`）。
22. **「零覆盖端点」的真实形态是「界面空白」而不是报错** —— 这类端点的 mapper JOIN 写错时**不抛异常、只返回空列表**，界面表现为"今天没有单"，与"确实没有单"无法区分。已补契约级用例（**这批接口本身没有功能性缺陷**，但抓到了 §8.21 与 §1 里 `updateStatusIf` 参数写反那批）：`DashboardNoticeSearchFeedbackIntegrationTest`、`InventoryStaffProductIntegrationTest`、`TicketDepositNotificationIntegrationTest`、`DeliveryConsoleAndSelfServiceIntegrationTest`、`DirectedReturnAndReturnToStationIntegrationTest`、`FileUploadIntegrationTest`。
23. **死端点评估（只读报告 `docs/audit/2026-09-16-死端点评估.md`，39 条：建议删 8 / 接线 19 / 保留 12）—— 两个必须先处理的发现，均已处置**：
    - **`GET /api/files` 系列跨站可见 —— 已修（2026-09-18，v45）**：原 `listAll` / `listByCategory` **无水站过滤**、`file_info` **无 `station_id` 列** → 任何站长 token 都能列出**全部水站**的文件名与临时 URL。现补 `station_id`（**NULL = 平台级文件，全站可见**，不是脏数据）+ `idx_file_station`，存量按 `uploader_id → staff.station_id` 回填（**查不到就保持 NULL**），查询改成 `listVisible(stationId)` / `listVisibleByCategory(stationId, category)`（本站 **或** 平台级），回归用例 `FileUploadIntegrationTest.fileListAndDeleteAreStationScoped`。**判据：接线前必须先做站隔离（加列 + 回填 + 过滤），否则整族删掉；"新列可为 NULL"不等于"没回填上"** —— 把有语义的 NULL 当脏数据清掉，会让平台级文件从所有站长列表里消失。
    - **`GET /api/delivery/orders/station-exception` 名不副实 —— 已删除（2026-09-18）**，回归用例 `ManagerOrderControllerRemovedIntegrationTest`：它过滤的是 `status = 5（已取消）`，一个"异常"标签返回的是**取消单**，且与 `GET /api/orders?status=5` 重复。**判据：端点的名字与返回集不符时必须删或改名**（同批删除的还有 `GET /api/dashboard/order-status`、`GET /api/dashboard/order-trend`、`GET /api/customer/exceptions/list`，`OrderMapper` 里对应的 `listStationExceptionOrders` / `countByStatusByStationId` / `trendLast7DaysByStationId` 一并删除并留墓碑注释）。
    - **其余删除候选等产品点头**；删除候选每条都挂着至少一条契约断言或文档表格行，所以"删代码"必须连带改测试与文档。
24. **写入口冗余（评估副产品，未动代码）** —— 押金有两条写路径：`POST /api/deposit-records`（接受全部白名单类型）与站长资产调整单 `DEPOSIT_GRANT/DEDUCT`（最终仍调同一个 `depositRecordService.add`）；水票同理，`addTicket` **没有** `adjustment_id` 幂等键（对比 `adjustTicket`，由 `uk_ticket_adjustment` 兜底）。`docs/design/10-站长资产调整单.md:66` 还把前者称作"押金调整的正确入口"。**动这两处前先想清楚哪条是正门。**
25. **`PUT /api/payments/{id}/cash-confirm` 与 `PUT /{id}/confirm` 实现逐字相同**（都只调 `paymentService.confirmPayment`），但已进验收文档（IT-PAY-002 / IT-CNF-002）→ **保留、登记，不要合并**（合并会动验收口径）。
26. **「隔离工作区跑全量测试」证明不了"应用能起来" —— 收尾必须验真实工作区**（2026-09-18）—— 隔离 worktree（`git worktree add --detach`）**只含 HEAD、不含未提交文件**："全绿"不能推出应用可启动。实测：多个构造器都没标 `@Autowired` → 上下文起不来，隔离 worktree 里 297 例却全绿。**判据：收尾三步都要做** —— ① 真实工作区 `.gradlew.bat clean compileJava compileTestJava`；② 真起一次上下文（`test --tests '*AquaFlowApplicationTests*'`，11 秒的用例才是真起过；0.05 秒红掉就是没起来）；③ 再在隔离 worktree 跑全量用例。**Spring 只在"有且仅有一个构造器"时自动选它**：多于一个就必须给生产那个显式标 `@Autowired`。⚠️ **隔离 worktree 里也没有 `application-local.yml`（gitignore）**，所以 `JWT_SECRET` / `WX_APP_ID` / `WX_APP_SECRET` / `DB_USERNAME` / `DB_PASSWORD` / `DEV_LOGIN_ENABLED` **都得从环境变量传**；缺了会让 `RequiredConfigChecker` 拒启，表现为**1 分钟红掉 344/382 例**（2026-09-19 实测），看着像"功能大面积坏了"。
27. **小程序 js 的「解析期」错误此前没有任何门禁（2026-09-19 真实事故）** —— `miniapp-delivery/api/station-mgmt.js` 里同一个 `const updateOfflinePayment` 被声明两次 → `SyntaxError: Identifier ... has already been declared` → **整个模块不执行**，而站长端 **15 个页面**都 require 这一个模块（看板 / 订单 / 客户 / 画像 / 待收款 / 商品 / 员工 / 营业状态 / 告警 / 欠桶 / 回桶 / 资产调整 3 页 …）→ 整端白屏；后端 382 例与 4 个静态门禁**一个都没报出来**（它们都不解析 js）。**判据一：扫绑定 / 扫引用 / 扫注释这类语义审计永远抓不到少括号、多逗号、重复声明 —— 改完小程序 js 必须过解析器**；已加门禁 `audit_js_syntax.py`（`node --check` 两端全部 js，进 `scripts/verify.sh` 与 CI，缺 node 时**跳过并明说**而不是判绿）。**判据二：api 模块里别写"重载形态"**（`(id, enabled)` 与 `(id, payload)` 靠调用方自觉区分 = 迟早撞车），一个函数只留一种签名、由调用方把 body 给全。**判据三（CI）：用例依赖什么就自己声明什么** —— 有 1 例靠 `application-local.yml` 打开 `dev-login`，而 CI 有意把 `DEV_LOGIN_ENABLED` 设成 `false`（端点带 `@ConditionalOnProperty`，根本不存在）→ **只在 CI 上红的用例**；同批用 `@TestPropertySource` 修掉（见 §5 第三步）。
28. **小程序文件带 UTF-8 BOM 会让 IDE 编译失败，而本地门禁全都发现不了（2026-09-19）** —— 用 PowerShell 的 `Set-Content -Encoding UTF8` 改写 `miniapp-delivery/pages/mine/index.wxss`，写入 **BOM + CRLF**（仓库其余文件都是 LF 无 BOM）→ 微信开发者工具报 `编译 .wxss 文件错误` 且**不指名文件**，而 `node --check` 与四个静态门禁、`page_reach_audit` **全绿**。**判据一：改小程序文件别用 `Set-Content -Encoding UTF8`**（用 `edit`/`write`，或显式 `New-Object System.Text.UTF8Encoding($false)` + `File.WriteAllText`）。**判据二：遇"编译错但门禁全绿"，先查 BOM**（前三字节 `EF BB BF`），**再读 IDE 日志**（`%LOCALAPPDATA%\微信开发者工具\User Data\<hash>\WeappLog\logs\*.log`，带时间戳，能和"改了哪个文件"对上）。
29. **「解除员工」不校验角色 → 站长能把自己解除，水站变孤儿（2026-09-19 实测确认并已修）** —— `POST /api/manager/bind/release` 原来只校验「员工存在 + 属于本站」，**无 role 校验、也无"别解除自己"**；而列表数据源 `StaffMapper.listByStationId`（`where station_id=? and status=1`）**不带 role 条件** → 站长自己也在列表里，前端两处都给了他「解除」按钮且真的成功：`staff.station_id` 置 NULL 后该站长所有 `requireStationId()` 端点全废、被 `app.js` 路由去"创建水站"，而 `station`/客户/订单/库存全留在库里 —— **客户照常给这个站下单却没人能接单**，重建水站是**新 id**、旧数据搬不回来。**判据一：凡是"把某行归属/所有者置空"的端点，先回答"置空之后谁会变孤儿"；判据二：列表 SQL 不过滤 role 时，前端不得按"列表里有什么"决定给不给按钮**（列表看不到 ≠ id 编不出来，同 §1.1）。现补两条护栏（role 必须是 `DELIVERY` + 不能解除自己），回归断言 `DeliveryBindingIntegrationTest.managerReleaseUnbindsDirectly`（**已反向验证**：临时摘掉护栏该用例即红、`code` 返回 0 = 真解除成功）。⚠️ **"转让水站"当前全仓无端点**，别把 release 当地址用。
## 9. 待确认 / 未验证清单

> 完整清单在 skill **`aquaflow-open-questions`**（动手前先加载）；这里只留仍在生效的判据。

以下条目**未取得确证，执行前必须自行核实**：

1. **「水厂端已彻底移除」是 2026-09-11 的复核结论**，此后未重新全库检索 `factory` 残留。
2. **已弃用表的实际停写状态未逐一复核调用链**：`customer_owed_barrel`（已停止写入）与 `customer_barrel_in_transit` 的写入点。
3. **`ManagerOrderController` 确已删除**（文件不存在，有 `ManagerOrderControllerRemovedIntegrationTest`），但 `.workbuddy/memory/MEMORY.md` 仍把它列为「仍未做」的高危项 —— **该记忆已过期**，也不排除有其他等效写入口。
4. **开发者工具「测试号」是否支持 `wx.login` / `jscode2session`，尚未实测**：官方只承诺「开发测试 + 真机预览」，**没有明文承诺登录能力**。真机「微信一键登录」能否跑通要实测（两对 appid/secret 填好后真机点登录，看日志 `微信code2Session响应[CUSTOMER]` / `[STAFF]`）。**测试号确定不能上传代码 / 发布 / 设为体验版**；若不支持登录，`dev-login` 是唯一可用登录路径。
5. **测试号分「小程序」与「小游戏」两种，不可混用**：把**小游戏**测试号的 appid 填进小程序项目（`compileType: "miniprogram"`）会**编译失败**。
6. **微信订阅消息对本项目不可行**：除少数行业（政务/医疗/交通等）外，订阅消息都是**一次性授权** —— 推一条要用户当面点一次「允许」，`wx.requestSubscribeMessage` **无法静默获取**；水站这种高频提醒摩擦过大，**产品裁定不做**（站长端只有应用内红点，见 `miniapp-delivery/utils/pending-reminder.js`）。另注：`WeChatNotifyService` 骨架读的是**客户端** appid，推员工要用员工端那对。

## 10. 本文件的来源与维护

- 原则：**能验证才写，不能验证就放进 §9**。任何一条若与本仓库当前代码冲突，**以代码为准**，并回来改本文件。
- 维护建议：做大改动/重构后核对 §1 的常量清单（`OrderStatus` / `PayMethod` / `PaymentStatus` / `DepositType` / `BarrelRecordType`）与 `sql/README.md` 的迁移清单；**新增枚举或迁移后必须同步 §1**（枚举值正本在 `constant/*.java`）；§9 被证实的条目应上移进正文并删除。
- **体积约束**：必须留在 65536 字节以内 —— 超出后**注入时会被静默削尾**。⚠️ **"≥10 KB 余量"早就不成立**：`§1.1` + `§8` 占近六成，且**只压得动个位数百分比**（剩下的全是判据本身）。**可执行规则**：① 加任何一段前先量体积（用 `node`/`read`，**别用 `Get-Content`**），**余量 < 4 KB 先瘦身**；② 瘦身**只压正文，不删条目、不重排 §8 编号**；③ 叙事写 `docs/audit/`、规格写 `docs/design/`；④ 别手写会漂移的计数。
- 已知待决策项：工作区未提交改动是否先 review 再按语义拆成数个提交（见 §7）；`docs/AGENTS.md` 的处置见下一条。
- **`docs/AGENTS.md` 已归档**（2026-09-18 `git mv` → `docs/audit/2026-09-18-docs-AGENTS-旧版归档.md`，正文一字未改，仅换顶部警示为终态声明）：判定口径是**过期文档要"离开会被自动加载的位置"，而不是"换个名字留在原地"** —— DSH 会把 `docs/` 下的 `AGENTS.md` 当附加指令**全文注入**（18 KB 过期指令进每个 docs 上下文），改名（原待决项 `docs/DOMAIN.md`）等于留在原地，**该待决策项就此关闭：不改名**。归档前把它仅存的两条判据（经营归属并集口径、抢单池跨租户可见面）搬进 §1.1。

## 11. 去哪找（工具与按需加载的 skill）

**代码结构别问文档，问工具**（DSH 装了 `dsh-code-index`，索引缓存在 `.dsh-code-index/`）：

| 想知道 | 用什么 |
|---|---|
| 仓库全貌、最核心/最重的文件 | `code_map` |
| "名字含 X 的符号在哪" | `code_search` / `code_symbols`（带 `file:line`） |
| "谁调用了 `BarrelLedgerService.checkout`" | `code_refs`（callers / callees） |
| 环依赖 / 孤儿模块 | `code_health`（需插件配置开 `codeHealth: true`） |
| 人能点的结构图（Atlas / City 3D） | `openvisio view . --no-open` → <http://127.0.0.1:7077>；重建 `openvisio index .` |

**按需加载的领域 skill**（放在 `.dsh/skills/`）：

| skill | 什么时候加载 |
|---|---|
| `aquaflow-open-questions` | 要动已弃用表 / 水厂端残留 / 微信测试号登录 / 判断 `.workbuddy` 记忆是否过期时 |

> ⚠️ skill 名（frontmatter 的 `name`）必须是 **ASCII 小写 + 连字符**（DSH 校验 `^[a-z0-9]+(?:-[a-z0-9]+)*$`）—— 中文名**不会报错，只会被静默忽略**（仅留一条 log warning）。

> 其余领域知识仍在 `docs/design/**` 与 `docs/audit/**` 里 —— 本文件只放"动手前必须知道的判据"，不放流程说明。
