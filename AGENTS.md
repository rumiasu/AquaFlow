# AGENTS.md — AquaFlow 仓库级 AI 协作指令

> 适用范围：仓库根 `D:\backend\project\AquaFlow` 及全部子目录。DSH/Claude 系 agent 读本文件作为**操作型契约**（命令、入口、禁改、坑）。
>
> **瘦身过三轮（判据全留、叙事外移）**：瘦身前的全文字节级存档留在仓库外的工作记录里（共三份，2026-09）。第二轮那次 66579 字节**超预算被静默削尾**；第三轮把 §8 的 30 条**原样**搬进 skill `aquaflow-known-traps`（同 §9 当年拆出去的先例）。
>
> 冲突优先级（高 → 低）：**§0 事实基准 + §1 领域不变量 → 本文件其余部分 → 根 `README.md` 与 `docs/**`（`architecture/` 与 `development/` 已逐条核对；`design/` 规格与其余仅作线索）**。原 `docs/AGENTS.md` 已于 2026-09-18 整体作废并移出仓库，`docs/` 下**已无**自动注入的指令文件。
> 证据标注：`【仓】`= 已在仓库文件中直接核对；`【会】`= 来自历史会话日志或 `.workbuddy` 记忆。
> **`§8.N` 的落点**：本文内联引用的 `§8.15` / `§8.26` 这类编号，指 skill **`aquaflow-known-traps`** 的条目
>（§8 全文在第三轮瘦身时搬去那里，内容与编号都没变；本文 §8 只留指针）。

## 0. 事实基准（最高优先级）

1. **一切以代码为准**。`README.md`、`docs/**`、`.workbuddy/memory/**` 仅作参考且**已知大面积过期**；冲突时以 `src/**`、`sql/schema.sql`、可运行的测试为准。【仓】
2. 唯一可信来源顺序：**Java/WXML/JS 源码 → `AquaFlow-backend/sql/schema.sql` → 通过的集成测试与实际接口行为 → 仓库 Markdown**（本文 §0）。【仓】
3. **删除类改动协议（2026-09-19 立，强制）**：删代码 / 端点 / 文件前先只读排查，**报备必须给六项**：① 核实到哪一步（几遍、什么方法）；② 证据（逐条 `文件:行号`）；③ **原来为什么存在**；④ **删掉会怎样**（连带测试 / 文档 / 常量 / mapper）；⑤ **推荐删或留 + 理由**；⑥ 风险等级。**登记表正本**：`docs/audit/删除登记表.md`（含六项协议、核实纪律与已确认删除的端点清单；旧判定会过期）。**核实纪律**：光跑 `api_reverse_audit.py` 不算（既有假阳性也有假阴性）。**判据：自己 grep 出所有出现位置，逐个确认是"真调用"还是仅"常量定义 / 文档 / 注释 / 测试断言"—— 定义 ≠ 调用**；零引用确认后再跑全量测试留基线。**查引用必须覆盖 `.js` / `.wxml` / `.java` / `*.md`**：小程序调用常只写在 **`.wxml`**（`data-url` + `bindtap`），只 grep `*.js` 会把有入口的页面误判成孤儿页；还要**顺 handler 往下看一跳**确认真的会跳。破坏性操作（删文件、改 git 历史、清库、跑历史迁移 SQL）先报证据与影响并等确认。
4. **数値の SSOT —— 有正本就不要在本文件重述**：表定义看 `sql/schema.sql`、迁移顺序看 `sql/README.md`、枚举值看 `constant/*.java`、API 实路径看 `controller/**` 注解、测试件数看 `build/test-results/test/*.xml`、**结构看 `code_map`（别手写这类计数，必然过期）**。【仓】
5. 仓库内文档的入口是 `docs/README.md`；`docs/**` 已知过期，只作线索、不作规格。【仓】
6. **「待拍板」必须当场落成 TODO（2026-09-20 立，强制）**：改动 / 结论**取决于产品（用户）拍板**时，**不能只在对话里问一句**（对话会被搁置，下一个人还会踩同一个坑）。当场三件事：① 在**代码或文档的对应位置**留 `TODO(待拍板)`，写清**问什么 / 两种选择的差别 / 拍板后改哪里**；② 在文档「待拍板」清单登记（同一问题只保留**一处正本**）；③ 回复用户时明确列出。**判据：只读代码与文档就知道"悬着一个决定、卡在哪、怎么落地"。** 典型悬置点：营收与计价口径、状态机语义、是否新增字段 / 状态、跨模块取舍。

## 1. 项目概览与领域不变量

- **业务**：桶装水（18.9L）配送管理系统，服务对象是**水站**（站长 + 配送员）；目标是可真实上线的企业级系统。
- **在维护的端只有两个原生微信小程序**：`miniapp-user`（客户端）、`miniapp-delivery`（站长 + 配送员）。**不存在可维护的 Vue 管理后台**（`AquaFlow-frontend` 已删除，仅 `archive/legacy-web-frontend` 留档）。
- **角色**：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）、客户（微信 openid）。`staff.role` 只有前两个；`FACTORY_ADMIN` 与整个水厂端已在 DB/后端/小程序三处彻底移除。

### 1.1 不可凭直觉改写的领域不变量

- **客户是全局身份**：`customer` 表**没有 `station_id` 列**；订单/桶/水票/押金一律按 `(customer_id, station_id)` 隔离。**经营归属** = 「绑定（`customer_station_config`）∪ 本站订单」并集（目的是保护水站已开发的客户不被别站抢走），是口径不是字段：`customer` 表**永远不加** `station_id` / `owner_station_id`（后者只属于 `product`，NULL = 通用商品库）。【仓】
- **三站语义（2026-09-18 起取代旧「双水站模型」）**：`orders.station_id` = **归属站**（客户选定的站，**定价方**，`DeliveryFeeUtil` 按它算费）；`delivery_station_id` = **履约站**（库存扣它、配送员清单、计件工钱记它）；`settle_station_id` = **结算站** = 本单营收（水费 + 配送费 + 楼层费）归谁（v47 新增列，正本 `sql/migration_v47_order_settle_station.sql` 文件头）。取值：下单 = 归属站；**抢单 / 定向外派成功后 = 履约站**；召回 / 退回池 / 指定退回-同意 = 回归属站。【仓 `StationUtil.settleStation`】
  - **钱认结算站**：看板、毛利报表（**成本 join 也按结算站**）、应收账款与核销、**确认收款判权**、**退款判权**（`PaymentController.requireRefundStation`，旧名 `requirePaymentOwnerStation` 已废）、`payment_record.station_id` 写入（`recordCashCollection`）。⚠️ **客户资产认归属站**：押金账户 / 水票 / 桶权益（`customer_deposit_account` / `ticket_*` / `customer_barrel_*`）一律**不动**。**SQL 读取一律 `coalesce(o.settle_station_id, o.delivery_station_id, o.station_id)`**（末级是**防御**，正常写入必须落 `settle_station_id`）。
  - **抢单池 / 他站外派 = 跨租户可见面**：下发给别站站长的字段**只许带"钱货去向"文案与快照金额**（`DeliveryController.feeInfoOf`），**不许带归属站的成本 / 库存 / 联系方式**，**不许下发客户画像**（`customerName` / `customerPhone`）；与本站既无绑定又无本站订单的客户，画像端点一律不可见。**含押金 / 桶权益的单禁止进抢单池**（直接拒）；**定向外派必须双方确认**（外派方先勾知悉风险 → 接收站接单再确认，两处留痕）；不涉押金的普通单不加摩擦。**被接单之后这单归接单站管**（2026-09-22 裁定「外派出去的本单就不归本站管了，只能接单站管，联系等都是接单站执行」）：归属站只能在**还没被接单**时召回（`cancelDispatch` 只收 `待配送(1)`，放行 `配送中(2)` 会把状态倒滚、且货已在别站车上）；接单之后拒单 / 解决 / 送达 / 收款一律按**履约站**判权，归属站唯一还能做的是「指定退回」审批。**联系客户由接单站执行**：快照里的 `receiverName` / `receiverPhone` 照常下发，抹掉的只是**归属站的客户档案**。**认领之后同样不含画像**：履约站侧配送员面（待接单 / 配送中 / 今日完成 / 历史 / 回桶记录 / 转给我的单）与站长端「员工画像 · 当前进行中」只带订单快照，**唯一实现** `util/CustomerProfileMask`（只抹 `customerName` / `customerPhone`；Map 形态调用方 SQL **必须显式 as 出** `stationId` / `deliveryStationId`，读不到即静默不抹）。
  - **`payment_record` 待收款流水跟着结算站走**：站别在「发起收款」时写死，换站由 `movePendingToStation` 搬（**只搬 `PENDING`**）；收款走 `confirmPendingToPaid` **就地确认**。`uk_payment_active_order` = **一单一条活跃流水**，已有待收款再插 PAID 必撞 1062、整笔送达回滚。
  - **只有收到钱的单才进站长 / 配送员视野**：判据两条 —— `payment_status = 2`，或 `payment_method = 2`（现金＝货到付款；客户没开通时**下单即被拒**）。水票扣票在下单后那次支付请求里，故"没扣票的水票单进不了站长端"由判据本身保证，**别**改成下单时扣票（会让票不够的客户连单都下不出来）。**判据三处必须一致**（两张列表 SQL + 接单/分配闸门，防"列表看不到但 id 可编造"）；未付微信单"当不存在"，回调置 2 即自动出现（`TODO(微信支付接入)` 在那三处）。转单状态一律查 `order_transfer` 结构化表，`special_note LIKE` 只许出现在「外派追踪」列表（且必须排除 `[指定退回待确认]`）。
- **订单状态**以 `constant/OrderStatus.java` 为准：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`（连续编号；历史 1/3/4/5/6 已废弃）；非法流转由 `isValidTransition` 拒绝。
- **支付方式**以 `constant/PayMethod.java` 为准：`1 微信 / 2 现金(货到付款) / 3 水票`（**水票 = 扣票成功即视同已付**）。**微信渠道未接入**：本地只有模拟渠道（`app.payment.mock-wechat-pay`，生产关闭），它是**新客户第一单的唯一自助通道** —— 判据见 `PayMethod.availableMethods` 微信项注释（**别再当"新客户死锁"报一遍**）。
- **支付状态**以 `constant/PaymentStatus.java` 为准：`0 未付 / 1 待收款 / 2 已付 / 3 已退款 / 4 已取消`；唯一真值是 `orders.payment_status`。
- **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源 `customer_barrel_lot.remain_qty`，`customer_barrel_over` 可为负（= 水站暂存）。
- **桶的四个数**（正本 `docs/architecture/02-领域模型.md` §4）：权益 = 已到手（`customer_barrel_asset.quantity`，送达入账）；配送中 = `customer_barrel_in_transit` 的 `PENDING`；持有 = 权益 + 配送中（**仅展示**）；占用 = 权益 + over = **还桶上限**（**不含配送中**）。**下单抵扣与退押金只认权益**：`shortage = max(0, needed − 权益)`，逐桶型（`PaymentServiceImpl.quote` 与 `create.js` 同口径）；配送中的桶结束前**不参与抵扣、也不可退**（取代原 [DEF-5] 的 `− pending`）。
- **任何「按商品 / 按桶型」的桶汇总，必须取 `assets ∪ 配送中(PENDING) ∪ over` 并集**：`customer_barrel_asset` 只记**已到手**的桶，只遍历它会让「首单还在配送途中」的商品**整行消失**（惯犯，见 §8.16）。**还桶上限一律用「占用」不用「持有」**。
- **退桶（退押金）与欠桶互斥：「欠着空桶就不许退桶，先还清」**（`BarrelReturnGuardIntegrationTest`）：硬拦两处 —— `BarrelServiceImpl.previewReturn` 返回 blocked（控制器直接拒、不建申请单）、`doRefund` 再查一次 over；按商品各算（A 水的多不能抵 B 水的欠）。**绕开通道**：`StationAdjustmentServiceImpl` / `OrderBarrelExceptionServiceImpl` 撤桶权益都走 `consumeLots` 直连 —— 只应由站长人工发起并留调整单/异常单痕迹。**退桶审批两步 `1→2→3`**（2 = 确认收到空桶、3 = 已退押金，不允许跳步），界面必须给到「退押金」那一步，否则顾客押金退不出来。
- **「首单」是两处口径的合称**（`docs/architecture/02-领域模型.md` §7.1）：下单时 `OrderServiceImpl:453` 写 `orders.first_barrel_order = firstStationAsset && totalNeededBuckets > 0`（本站第一笔**买桶**订单）；`OrderWorkflowServiceImpl:296` 读它，为真则**整段跳过回桶核对**；押金口径同源（首单权益 0 → 收满押金）。用例 `OrderEntryAndInjectionIntegrationTest`。**查写入点必须连 XML mapper 一起查**。
- **支付状态只前进、不倒滚**：`0 未付 / 1 待收款` = 钱还没到手，`2 已付款` = 收钱的结果，`3/4` 是终态。现金单**下单即 待收款(1)**；**发起收款**（`createPayment`）与**送达未收款**（`completeDelivery`）都**不许改写它**；唯一写 2 的入口是 `OrderMapper.markPaidIfCollectable`（从 0 或 1 迁入，绝不复活 3/4）。**禁止写 `updatePaymentStatusIf(..., UNPAID, PAID)`**（现金单现值是 1，CAS `expected=0` 恒不命中）；**未收款时不要动 `payment_status`**。`DashboardMapper` 待收款口径 = `payment_status=1 且未取消`。
- **CAS 改状态有「两派」参数顺序，靠名字区分（2026-09-23 改名收口）**：`updateStatusIf(id, 期望, 新)`（`OrderMapper` / `OrderBarrelExceptionMapper`）vs **`updateStatusTo(id, 新, 期望)`**（`PaymentRecordMapper` / `StaffPayrollMapper` —— 这两个**原名也叫 `updateStatusIf`、顺序却相反**，本仓因此在这一点上**静默失败过 3 次**）。**判据：名字带 `To` 的第二个参数就是目标状态，带 `If` 的第二个是期望状态。写 CAS 前先看 mapper 的 SQL —— 传反了恒命中 0 行，不报错、静默什么都没改。**
- **登录类端点必须按 IP 限流**（`interceptor/RateLimitInterceptor`）：[AQ-040] 锁定按**用户名**计数，换用户名即可绕开（撞库/枚举），**两层正交、按来源 IP 那层不可省**。覆盖 `/api/auth/{login,wx-login,wx-login-staff,dev-login,refresh,change-password}`；计数**按 IP 聚合**；超限 **HTTP 429 + `{code:1,message}`**。默认 20 次/分钟（`RATE_LIMIT_*`）；多实例须先换集中式计数器（Redis）。**集成测试默认关闭**，只有 `RateLimitIntegrationTest` 用 `@TestPropertySource` 打开。
- **告警分级投递：系统故障 → 系统管理员，运营故障 → 该站站长**（`AlertRoutingIntegrationTest`）：`alert_log`（v30）+ `AlertService`，方向由 `constant/AlertType`（`SYSTEM` / `OPERATION`）决定，**不靠字符串比较或调用方自觉**。SYSTEM（`station_id` 必须 NULL）= 对账不平 / 桶异常补偿失败 / 未预期 500 → **不发站长**；OPERATION（`station_id` 必填）= 桶异常待处置 / 补偿已执行 / 异常被忽略 → 站长端只读 `GET /api/manager/alerts`。**三条硬约束**：① 先落库再谈渠道（没配则 `notify_status=LOGGED`）；② 落库走**独立事务**（`REQUIRES_NEW`，必须经代理调用）；③ 失败**绝不连累业务**。**红线**：系统告警没有 HTTP 入口（漏给站长 = 跨租户 + 越权知情），运维 `select * from alert_log where alert_type='SYSTEM' order by id desc limit 50;`
- **押金/桶记录方向由类型决定，调用方金额一律传正数**：`DepositType` 收敛为 `isIncrease`（`1/5/9`）/ `isDecrease`（`2/3/4/6/7/8`），**扣减类以负数落库**（`DepositRecordServiceImpl.java:56-64`）—— 对账等式1（`balance == SUM(deposit_record.amount)`）的前提。`BarrelRecordType` = `6 人工调整(增)` / `9 人工调整(减)` 已纳入守恒对账 E5（`ReconciliationService.java:258-259`），不纳入则每次补录都误报。**`BarrelRecord.getStatusText()` 只对 `type=2` 下发**（其它类型的 `status` 只是处理标记，照原样映射会让配送流水显示"已退押金"）。
- **三张流水表的调整场景唯一键**：`uk_deposit_adjustment`、`uk_record_adjustment`、`uk_ticket_adjustment`，配套可空列 `adjustment_id`。`ticket_record` 的 `uk_ticket_consume(order_id, product_id, source)` 在 `order_id IS NULL` 时**零保护**。
- **退款只有两个入口，都必须「原路径返回」**（正本 `docs/architecture/02-领域模型.md` §5）：① **取消订单** → `refundOrder`（门槛 `OrderStatus.isCancellable`：**已完成/已取消不得再取消**），也是取消/拒单的单一编排入口（退水票 → 退流水 → 退押金 → 清配送中桶 → 回补库存 → 置已取消）；② **只退这一笔钱** → `refundPayment`，**不取消订单**。**原路径返回**：水票 → 回补 `ticket_lot` 批次（不过批次账 E8 就平不了）；现金 → 记**负金额**冲正流水；**微信未接入 → 不许假装已退**（手工退款直接拒；取消链不阻断但要在 `note` 写明需线下退款）。两条路径共用 `insertRefundRecord` / `restoreTicketsForOrder`；无订单的在线购票退款（`order_id IS NULL` 且 `ticket_qty > 0`）**当前不做**，只给明确拒绝。⚠️ **收款与退款都认结算站**（取代旧「退款认归属站、确认收款认履约站」），原 [AQ-043] **已作废**。⚠️ **押金与欠桶仍记归属站**。
- **客户端与员工端是「两个小程序」，appid 不同**：`wx.login` 的 code 只能用**签发它的那一端**的 appid+secret 换 openid（用错端只回 `40013 invalid appid`，日志无指向性），故 `WeChatLoginService.code2Session(WeChatApp, code)` **强制显式传端**（`CUSTOMER` / `STAFF`）；openid 按 appid 隔离。配置键：客户端 `wechat.miniapp.appid/secret`、员工端 `wechat.miniapp.staff-appid/staff-secret`；**员工端这对本地缺失只 `log.warn`（dev-login 兜底），`prod` 必填（缺即拒启）**。本地客户端用「**小程序**测试号」，**小游戏号填进小程序项目会编译失败**（见 §9）。
- **站长治理类入口**：资产调整单 `/api/manager/adjustments`（6 端点，类级 `@RequireRole("STATION_MANAGER")`）；`/api/manager/reconciliation` **只读**本站即时对账（**只有 `GET /`**；原 `POST /run` 写全平台结果 = 跨租户泄露，已删除、**不要加回**；运维记录由 03:00 定时任务落表），结果落 `reconciliation_result` 表。
- **公告（v32 起站长可发）**：`notice.status` = `0 下架 / 1 发布`，`type` = `1 系统公告 / 2 水站通知 / 3 活动`。**站长端管理列表必须含草稿与已下架**（带上 `status = 1` 会让"存草稿后列表里没有它""点下架后从列表消失、再也点不回来"）。状态文案由 `Notice.getStatusText()` 下发（正本 `constant/NoticeStatus.java`），前端禁止自带映射表。⚠️ **`GET /api/notices`（顾客端列表）不做站过滤**，任何顾客能看到**所有水站**的已发布公告（`[AQ-038]` 只修了"按 id 读草稿"）—— 未修，见 `docs/design/13` §9。
- **欠桶「只提醒、不阻断」**：原硬拦 `MAX_OWED_BUCKETS = 5` 已**移除**、**不要再加回**；下单响应 `warnings` 每次都提醒（幂等命中路径同样下发），**物理护栏不变**（占用 = 权益 + over ≥ 0）。`customer_barrel_over.owed_since`（v29）只用于展示、**不参与任何校验**；唯一维护点 `CustomerBarrelOverMapper.syncOwedSince`（≤0 变 >0 写入、回到 ≤0 清空、已是正数再增加不重置）。站长端 `GET /api/manager/owed-barrels`（只读）。
- **迁移清单正本是 `sql/README.md`（逐条带真实库执行证据与备份文件名），本文件不复述；新建迁移前先 `ls sql/` 看编号，别照任何清单的最后一个数字 +1**。跨条目判据：**v32 是软状态：不阻断下单、只提示**；**v41 是唯一破坏性 DROP**（先上代码再执行 SQL；脚本自带"列内有值就中止"护栏，删列前先 `mysqldump`）；**MySQL 解析期校验列名**：对某列的 `MODIFY`／`WHERE col` 即使不执行到也会解析报 1054 —— 删列时必须把**历史迁移里对该列的引用一并清掉**（v41 即因此改了 v24）。
- **配送员计件工资是站长台账，且走独立对账**（v37；正本 `docs/design/18-配送员计件与工资.md`）：`staff_piece_rate`（站级单价，`product_id=0` = 该站默认价）+ `staff_earning` + `staff_payroll`（草稿→已确认→已发放）；**写入口是 `StaffEarningService`**。① 发钱的是站长不是平台（不做打款/提现，只落 `paid_time` + `operator_id`）；② 计件单位是桶不是单，按 `order_item` 逐商品计（`auto_uk` 含 `product_id`）；③ 方向由 `kind` 决定、调用方传正数，唯一例外 `ADJUST`（`EarningKind.allowsSignedAmount`）；④ 归属站 = 履约站（`delivery_station_id`）。**收益只在 `completeDelivery` 的状态 CAS 成功之后产生**；**工钱不进客户对账**（独立等式 **E-PAY**，告警 **OPERATION**）；`staff_earning.auto_uk` 的 **NULL 是有意的**，幂等由 `uk_earning_auto`（生成列）兜底 —— 与 `uk_ticket_consume` 的"NULL 零保护"形状相同但**语义相反**。用例 `StaffEarningAndPayrollIntegrationTest`。
  - **v44「自定义工资条目」只是人工调整流水上的标签**（`EarningItemDirection`：1 加项 / 2 扣项）：不参与自动计算、不进对账；**传了 `itemId` 只收正数**、**用过的条目只能停用不能删**、`item_name` 是写入时快照；`itemSummary` **不等于**未结合计。`POST /payroll/adjust` 现按「本站员工 **或** 在本站有过收益」并集放行（「我的工资」只按 `staff_id` 过滤）。用例 `StaffEarningItemIntegrationTest`。
  - **结算单期间上界必须用「结束日 + 1 天」**（`attachToPayroll` 的 `endExclusive`）：写 `<= 结束日` 会让**当天收益一条都结算不到**（同 §8.19）。
- **水票余额真相源是 `ticket_lot`，`ticket_account` 只是派生汇总**（v36，同构于 `customer_barrel_lot` → `customer_barrel_asset`；正本 `docs/design/19`）：`remain_quantity == Σ lot.remain_qty`、`right_amount == Σ remain_qty × unit_price`，由对账 **E8** 校验；**批次唯一写入口 `TicketLotService`**（`createLot` / `consumeFifo`）。① **单价取实付均价**（`payment_record.amount / ticket_qty`）；② **消耗按 FIFO**，退款回补按**流水里的当时单价**还原（`ticket_record.unit_price`）；③ **任何改动水票数量的路径都必须过批次账**（在线购票入账、站长加票、用票支付、订单取消回补、资产调整单），漏一条 E8 就报不平。用例 `TicketPackageAndLotIntegrationTest`。**夹具 `createTicketAccount` 也必须建批次**（只插账户会打红 10 个现有用例）。
  - **MySQL 的 `SET` 从左到右求值，后面的表达式看到的是已更新的列值** —— `SET remain_qty = remain_qty - q, status = CASE WHEN remain_qty - q = 0 ...` 里的 `remain_qty` 已是 0，CASE 永远算不出 0。**要把 `status` 赋值排在 `remain_qty` 前面。**
- **配送计费（起送量 / 配送范围 / 运费 / 楼层费）只有一份实现**（v35；正本 `docs/design/17`）：纯规则在 `util/DeliveryFeeUtil`，IO 在 `service/DeliveryFeeService`；**`PaymentServiceImpl.quote` 与 `OrderServiceImpl.createOrder` 必须调同一个 `calcForOrder`、传同样口径**（桶数只数 `category=1`）；一侧内联算费用 = 重演"计价双轨"。① **费用绝不并入 `water_amount` 或 `deposit_amount`**（后者可退，混入会导致取消多退钱），各自成列；② **门槛默认 WARN 不是 REJECT**；③ **拿不准就不收/不判**。用例 `DeliveryFeeUtilTest` + `DeliveryFeeIntegrationTest`（报价 `totalAmount` 必须等于下单 `total_amount`）。
- **无订单支付（在线购票）必须带客户端幂等键**（v33）：`order_id` 为 NULL 时 `createPayment` 的重复流水检查被跳过，而 `uk_payment_active_order` 建在生成列上、**NULL 互不冲突 → 这条路径零保护**。`POST /api/tickets/purchase` 的 `idempotencyKey` **必传**；`uk_payment_idempotency` **必须带 `customer_id`**。`confirmPayment` 的乐观锁只管**单条**流水。用例 `TicketPurchaseIdempotencyIntegrationTest`。
- **对账等式不能把「合法业务状态」算成差异**（三处实测均表现为**日结永远不平**；正本 `docs/architecture/03-数据模型.md` §9）：① **等式2 `p2a`**：核销只置 `payment_status=2` 而不补 PAID 流水 → 每核销一单就报不平；**收款必须两步**：先 `recordCashCollection(orderId, note)`（幂等）再 `markPaidIfCollectable`，**不许另写"补流水"实现**。② **等式2 `p2c`**：`order_id IS NULL` 不等于孤儿（**在线购票无订单**），判据是 `AND p.ticket_qty IS NULL`。③ **等式3 `b3a`**：`customer_barrel_in_transit.status='DELIVERED'` 是**合法终态**，改为「标了已送达却没有权益批次」。**写等式前先问"这个状态在正常经营里会不会合法出现"，会就不能进差异计数。** 用例 `ReconciliationAfterNewFeaturesIntegrationTest`。
- **应收账款 = 给「待收款」加账期维度，不新造金额口径**：金额真相源仍是 `payment_status = 1 AND status <> 5`（同 `DashboardMapper`）；**没建新列**，激活原有挂空列 `orders.settlement_status` / `orders.due_date`；账期由 `ReceivableService.resolveDueDate` **下单时快照一次**（**只有现金单有应付日期**）。端点 `/api/manager/receivables*`、`/customers/{id}/credit-terms`。① **核销 ⟹ 已收款**（`OrderMapper.settleIfCollected` 的 CAS 带 `payment_status = 2`），收款仍要**两步**；② **逾期只提醒、不改金额**；③ 对账 **E10**（`settlement_status = 2 AND (payment_status IS NULL OR payment_status <> 2)`）**只查单向**（反向是**正常经营状态**）；④ **账期自 v60 起是「站级」**（存 `customer_station_config.due_days` / `settlement_cycle`，同一客户 A 站月结、B 站可现结），设置要用只改这一张表的专用 mapper；**不要**用 `CompanyInfoMapper.updateByCustomerId`（整行覆盖会抹掉企业资料）。**`company_info.due_days` 已无人读取**（列与数据留着，效果 = 账期清空、由站长重设）。**归属判据只认并集 `CustomerMapper.countCustomerOfStation`（绑定 ∪ 本站订单），不要用 `getStationCustomer`**（后者按订单算，会把**没下过单的新客户**判成"不属于本站"；同一坑已多次踩）。**代客下单复用 `POST /api/orders/create`**（两条建单路径算金额 = 计价双轨）；读接口在 `/api/manager/order-assist/*`。用例 `ReceivableIntegrationTest`、`EmployeePlaceOrderIntegrationTest`。

## 2. 目录结构与关键入口

- **仓库结构问 `code_map`，不要照抄任何手写清单**（见 §0.4）。三条常年有效的位置判据：
  - 后端唯一入口 `AquaFlow-backend/`（Spring Boot + MyBatis；集成测试在 `src/test/java/.../integration/`）；SQL 基线 `sql/schema.sql`，迁移顺序以 `sql/README.md` 为准。
  - **CI 门禁 `.github/workflows/ci.yml` 必须在仓库根** —— 放进 `AquaFlow-backend/` 下 GitHub 不读。
  - `archive/**` 不维护；`backup/` 是本机备份、已被 gitignore，**勿提交**。
- 后端 API 统一响应 `{ code, message, data }`：`code 0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常；**业务错误 HTTP 状态仍是 200**（唯一例外：未认证返回真 401）。【仓 `common/Result.java`】
- 小程序 API 基址在各自的 `config/api.js`；**两端 `prod.baseUrl` 目前都是占位符** `https://your-domain.com`（见 §8.10）。
- **「不校验合法域名」= 两个位置，缺一不可**：① `project.config.json` 的 `urlCheck` —— **两端都已是 `false`**（2026-09-23 实测；旧文档写"`miniapp-delivery` 仍是 `true`、靠 gitignore 的 `project.private.config.json` 覆盖"**已过期**，manifest 里本来就是 false）；② 真机上右上角 `…` →「打开调试」。少任何一个，本机 `http://<局域网IP>:8080` 的请求都会报「不在以下 request 合法域名列表中」。【仓】

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

- **环境变量**：`.env.example` 是清单权威来源。**启动期硬校验只有 3 项**（`config/RequiredConfigChecker.java`）：`JWT_SECRET`（**长度 < 32 也拒绝启动**）、`WX_APP_ID`、`WX_APP_SECRET`，缺一跳 `IllegalStateException` 拒绝启动。【仓】
- **其余变量不被 `RequiredConfigChecker` 检查，缺失后果由各自组件决定 —— 不要再写成「缺一即启动失败」**：`COS_SECRET_ID/KEY`、`WX_STAFF_APP_ID/SECRET` 未配置只 `log.warn`（COS 只影响上传；员工端只影响真机微信登录，dev-login 兜底、`application-prod.yml` 里该对无默认值、`prod` 缺失即拒启）；`DB_*` / `CORS_ALLOWED_ORIGINS` / `DEV_LOGIN_ENABLED`（**生产必须 `false`**）/ `MYBATIS_LOG_IMPL` / `RATE_LIMIT_*` 同理。【仓】
- 本地默认 profile `local`，密钥读 `src/main/resources/application-local.yml`（**已 gitignore，含真实密钥，禁止提交、禁止回显**）；生产用 `--spring.profiles.active=prod` + 纯环境变量。该文件还开着 `dev-login` 与微信**模拟支付渠道**、并按 IP **关掉了登录限流**（真机联调与手机共用出口 IP）—— 只在本文件里，生产不受影响。
- 小程序：用微信开发者工具分别打开 `miniapp-user` / `miniapp-delivery` 目录（无 npm 构建步骤）。
- PowerShell 里**用 `;` 分隔多条命令，不要用 `&&`**。长任务（Gradle 构建、测试）放后台任务。【仓】

## 4. 数据库与迁移流程

- **Flyway 未启用**（无依赖、无配置）。`sql/**` **全部靠手工执行**，没有版本表、没有自动校验。（`src/main/resources/db/migration/` **不存在**，勿按该路径找脚本。）
- **新建库的权威基线是 `sql/schema.sql`**（全 `CREATE TABLE IF NOT EXISTS`，可重复执行）；`init.sql` 只建结构、不含种子数据，且 `SOURCE schema.sql` 依赖相对路径，**必须在 `sql/` 目录下执行**。表数以 `Select-String -Pattern '^CREATE TABLE'` 实测为准。
- **`schema.sql` 导入必须走字节级重定向**：`cmd /c "mysql -uroot --default-character-set=utf8mb4 库名 < schema.sql"`（CI 上是 bash 的 `<`）。**不要用 PowerShell 管道**（`Get-Content -Raw | mysql` 按控制台代码页重编码会把中文注释变乱码）。⚠️ **核对是否写坏要比字节（`HEX(TABLE_COMMENT)`），不要看控制台**。【仓】
- **老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑（**该清单为唯一权威**）。执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。⚠️ **清单已知缺陷**：漏列 `v3`/`fix_schema_alignment`，且 `v25` 改名与 `v1_backfill` 依赖旧表名导致顺序冲突 —— **执行前人工核对**。【仓】
- **迁移脚本必须幂等**：MySQL 8.4 无 `DROP ... IF EXISTS`，统一用 `information_schema` 预检 + `PREPARE`。**破坏性 DROP 必须先上代码、再执行 SQL**；新建脚本前先 `ls sql/` 看命名是否占用。【会】
- **严禁在生产执行**：`sql/reset_data.sql`（TRUNCATE 多表）、`sql/clear_data.sql`、`reconcile_order_814.sql`、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`（已过期且不可执行）。【仓】
- `sql/` 里**大量脚本是 SUPERSEDED/DUPLICATE**（见 `sql/README.md` 表格），已被 `schema.sql` 吸收，**不要在新环境执行**。

```powershell
# —— 全新库初始化（只建结构，空库）——
cd D:\backend\project\AquaFlow\AquaFlow-backend\sql
& 'D:\backend\MySQL\bin\mysql.exe' -u root -p < init.sql
```

## 5. 测试与验证方式

- 集成测试位于 `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/`；基类 `support/AbstractIntegrationTest` 启完整 Spring 容器、发真实 HTTP（JDK `HttpClient`）、每用例前 TRUNCATE 并**断言当前库名含 `test`**；件数以 `build/test-results/test/*.xml` 为准。
- **本机无 Docker**，不用 Testcontainers；测试库 `aquaflow_test` 是独立可重建库。
- **运行前置（硬要求）**：必须显式设置 `GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'`，否则 Gradle 往沙箱外的用户目录写缓存、被拒后直接失败；`--project-cache-dir .gradle_alt`（旧文档给的）**不足以**解决。【仓】
- **Gradle 锁坑**：后端 `bootRun`（8080）在跑时直接 `gradlew` 会因 `fileHashes.lock` 失败 —— 统一加 `--no-daemon`；换个 `--project-cache-dir` 可与在跑的 `bootRun` 并存（必要时先停后端）。
- **bash 用 Git 自带的** `D:\backend\Git\bin\bash.exe`（`Get-Command bash` 会解析到 `WindowsApps\bash.exe` 存根，报 `E_ACCESSDENIED`）；**受限沙箱下 Cygwin 起不来**（`couldn't create signal pipe, Win32 error 5`）。`python` 用 `D:\agent\python\python.exe`。⚠️ **跑含 `↔` 等非 GBK 字符的 python 脚本前先设 `$env:PYTHONIOENCODING='utf-8'`**（否则 `print` 抛 `UnicodeEncodeError`，表现为"脚本没问题却 exit=1"）；**别把脚本路径写进自定义函数的 `$args`**（自动变量，会让 python 无参启动、进 REPL 后 exit 0）。【仓】

```powershell
# —— 后端集成测试（本机唯一可跑通的入口）——
$env:GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat test --no-daemon --project-cache-dir .gradle_eval
```

- **断言看响应体 `code`，不看 HTTP 状态**（业务错误仍 200；未认证才是 401）。新增用例：继承 `AbstractIntegrationTest`，用 `createStation/createStaff/createCustomer/createProduct/createOrderFull/createOrderCrossStation` 造数，`customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete` 发请求，`intOf/decimalOf` 查库；并发参考 `ConcurrencyIntegrationTest.fireTogether`。【仓】
- **统计测试件数**：把所有 XML 相加，用 `$d = New-Object System.Xml.XmlDocument; $d.Load($path)` —— **不要 `Get-Content -Raw` 再转 `[xml]`**（按 ANSI 解码会弄坏测试名、**静默少算**）。【仓】
- **验证 CI 会不会绿，就在本机复现 CI 三步**：① `DROP DATABASE aquaflow_test; CREATE DATABASE aquaflow_test;` 后**字节级重定向**导入 `sql/schema.sql`；② `.gradlew.bat cleanTest test`（必须 `cleanTest`，否则报 `:test UP-TO-DATE` 而**根本没跑**）；③ **把 CI 环境变量也照抄一遍**（尤其 `DEV_LOGIN_ENABLED=false` —— 本机是 `true`，不一致会让"只在 CI 上红"的用例长期隐身，见 §8.27 末段）。【仓】
- **按业务场景组织的覆盖地图见 `docs/audit/2026-09-16-场景测试矩阵.md`** —— 新增用例前先看它找空白。

## 6. 代码约定与风格

- 后端分层：`Controller` 只做认证 + DTO 校验 + 调服务 + 返回 `Result<T>`；**禁止 Controller 直接写 `orders` / `payment_record` / 库存 / 桶资产表**，订单状态与副作用只能经 `OrderWorkflowServiceImpl` 这类编排服务完成。
- **所有状态改写必须 CAS 并检查受影响行数**（`updateStatusIf` / `updatePaymentStatusIf`）；无 expected-state 的 `updateStatus` / `updatePaymentStatus` 属于待清除的旧路径。
- 业务前置不满足一律抛 `BusinessException`（→ `code=1`），**不要用 `RuntimeException`**（会被兜成 500）；**不要在被 `@Transactional` 注解的方法内 catch 业务异常**（会抛 `UnexpectedRollbackException`，把正常业务拒绝伪装成 500）。
- 权限：`@RequireRole({"STATION_MANAGER"})` + `@RequireStation`，由 AOP 切面 `execution(public * controller..*.*(..))` 统一保护，新增方法自动生效。**跨站校验一律以 `AuthContext` 中服务端刷新的 `stationId` 为准，不信任请求参数**；客户 ID 必须由登录态覆盖或与订单所有者严格比对。
- MyBatis：注解 SQL 用下划线列名（配 `map-underscore-to-camel-case: true`）；**注解 SQL 无编译期校验，新写必须手工在真实 MySQL 上跑过**。
- 金额、客户、订单归属、水票数量一律服务端推导或强校验。展示文案（`statusText` / `payMethodText` / `payStateText`）由后端下发，**前端禁止自带 1/2/3 映射表**（两端各写一套曾导致新客下单 100% 失败）。
- **请求体的枚举入参必须白名单校验**（2026-09-20 实测：`OrderCreateDTO.paymentMethod` 无校验，传 99 也建单成功）；兜底文案**不许把未知值说成某个已知值**（`PayMethod.textOf` 的 `default` 返回「现金」，会让幽灵单在客户端显示成货到付款）。
- 写库顺序：先 `getByClientToken` 判断幂等再动手；Controller 调 service 后再写库必须 `@Transactional`。
- 日志禁止记录密码、JWT、微信授权码、完整手机号/地址、任何密钥。**回复中也不回显密钥**（用 `<redacted>`）。
- 术语统一：**配送中**（= 已付款买下桶权益但未送到，旧称「在途」**已禁用**）、**进行中**（= 待配送 1 + 配送中 2）。表名 `customer_barrel_in_transit` / 类名 `CustomerBarrelInTransit` 仅为兼容历史命名保留，注释与文案一律写「配送中」。`customer_owed_barrel` 已停止写入，欠桶改读 `customer_barrel_over`。
- 小程序：`wxml` 内禁止调用 Page 方法 / `Math.` / `Date.`；`wxml` 绑定的事件处理函数必须真实存在，否则点击**静默无反应**；注意 `require` 相对层级；后端 `/api/delivery/orders/{id}/xxx` 用模板串拼接。

### 6.1 注释契约（2026-09-14 立规，强制）

本仓库的注释**不是可选项** —— 它同时是下一个 AI 的**操作依据**。

1. **改代码必须同步改注释**：注释与代码不符**比没有注释更危险**（没有注释人会去读代码，注释错了人会直接照做；三次事故见 §8.14）。**作废的 javadoc 必须删除，不能悬空留着占位。**
2. **分工：流程 / 规则写文档，代码注释只写「改这里会踩什么坑」。** 业务规则 / 领域模型 / 状态机 / 资金口径已写在 `docs/design/`，**代码注释不要复述**。
   - **该写**：反直觉约束、历史事故、"别加回来"的护栏、并发与加锁顺序、唯一键 / 幂等陷阱、"本类不是桶账写入口"这类边界声明。**该写文档**：业务流程 / 状态流转 / 字段口径 / 交互。**都不写**：`getXxx` / `setXxx`、直白循环与判空。要提业务规则就**一行指向文档**（如"见 `docs/architecture/02-领域模型.md`"）。
   - **正面样本**：`constant/PayMethod.java`（"前端曾把 2/3 写反导致下单必失败"）、`constant/AdjustType.java`、`BarrelLedgerService`（加锁顺序）。**反面样本**：把整段业务背景抄进 Controller。
3. **修完缺陷就地留评论**：在**出问题的源头**（不只写在测试里）注明「原来是什么 / 为什么错 / 后果是什么 / 正确做法」并标日期。
4. **新增端点必须写明归属与调用方**：员工端点标 `@RequireRole`；顾客自助端点写明身份取自 `AuthContext`（见 `aspect/RequireRoleAspect.java`）；小程序侧注明「顾客端能不能调」。
5. **参数必填性、枚举取值、接口路径、表结构**四类说明最易过期，改动必须当场同步。
6. 新增类 / 公开方法 / 非直觉分支补 javadoc；**纯 getter/setter、显而易见的循环不补** —— 注释的价值是"降低误用概率"，不是覆盖率。

### 6.2 概念引用契约（2026-09-21 立规，强制）

**引用一个「本次对话 / 本轮工作里临时造出来的代号或概念」时，必须当场再给一次定义或一句话说明，不许裸用。**

- **对象**：自己起的编号与代号（`T1`/`T2`、`高危2`、`场景 B`、`档位`、`层 A`……）、只在会话里出现过的新名词、自己发明的简称。
- **为什么**：用户与下一个 agent 的**上下文都是有限的**，记不住几十轮之前那个 `T2` 是什么。
  裸用代号 = 让对方回翻聊天记录，或**凭猜测理解**——而猜错会直接做错事。
  这与 §6.1 第 1 条同源：**被信任的文本比没有文本更危险**。
- **判据**：把那句话单独摘出来给一个**没看过前文**的人读，他能不能懂？不能就必须补定义。
- **写法**：`T2（取消已送达订单时，客户手上的桶权益没有被撤销）` —— 代号 + 一句话，别只写代号。
- **落在哪**：对话、文档、代码注释都适用；文档里给代号时同样要带定义（文档会被单独打开）。
- **不等于啰嗦**：同一段话里连续引用同一个概念，第一次给定义后可用简称；**跨段落、跨回复就必须重新给**。

## 7. 协作注意：不要动 / 属于生成物

- **动手前先 `git status` 核对；不要顺手混入无关改动，也不要替用户提交**；工作区常有大量未提交改动，review 后再决定提交。
- 未获明确要求**不要 `git commit` / `git push`**；改动留在工作区供 review。当前分支 `master`，**提交数不要硬编码**（以 `git rev-list --count master` 为准）。
- **提交粒度与信息**：一次提交只做一件事；message 写「改了什么 + 为什么」，**不写过程叙述**，**不记录工具、环境或个人账号变动**。判据：这条 message 对三个月后排查问题的人有用吗？
- **提交规范 hook 已入库但默认未启用**（`.githooks/`）：`commit-msg` 强制 `<type>(<scope>): <subject>`；`pre-commit` 在暂存文件数 > 30 时拒绝提交。启用：`git config core.hooksPath .githooks`。⚠️ 受限沙箱下 Git 自带 `sh.exe` 起不来，启用会让每次 commit 失败。
- **不要改 `archive/**`**（`legacy-web-frontend`、`miniapp-station` 均为历史留档，后者缺 `app.js`、页面残缺）。**不要引用 `miniapp-station`**。
- **生成物 / 勿手改**：`AquaFlow-backend/build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`docs/design/*.html`、`tabbar-icons-preview.png`、`project.private.config.json`。另 `.gradlehome/`、`.gradle_alt2/`、`.gradle-user`、`.gradle_alt3`、`.dsh-code-index/`、`.openvisio/` 都是工具缓存。
- **`.gitattributes` 已加入**（`* text=auto` + `*.sh/*.py/*.yml/*.sql eol=lf`）：blob 一律存 LF，防止 Windows 检出 CRLF 后 `bash scripts/*.sh` 在 CI（Linux）上因 `\r` 失败。
- **根目录 `*.py` 分两类**：**6 个已入库**（`audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py`、`audit_js_syntax.py`、`audit_scenario_matrix.py` —— CI 与 `scripts/verify.sh` 的静态门禁，`.gitignore` 对 `*.py` 开了 `!` 例外，**不入库则新克隆的 CI 必然失败**；前两个需传端名，`page_reach_audit.py` 还能识别 js 的 `url:` 与 wxml 的 `data-url="/pages/..."`）；另若干（`e2e_user_test.py`、`gen_tabbar_icons.py`、`api_reverse_audit.py`）属调试/审计残留，不可作为项目入口或规范依据。
- **`api_reverse_audit.py` 的已知偏差**：① 行号按**剥离注释后**计，**系统性偏早**（报 `:182`／实际 `:198`）—— 引用前回原文件核对；② 判定**只看路径、不看 HTTP 方法**，**报的数字是下界**；③ 认不出「**页面内路径常量拼接**」与「路径段由**函数参数**传入」的调用（前者已修：45→34，后者仍看不见），**在用端点会被判死**。**清单是候选、不是结论** —— 要动"删除端点"这类决定，必须自己重新证一遍零引用（见 §0.3）。

## 8. 已知坑与历史教训

> ### ⚠️ 这 30 条的**正本已移出本文件**（2026-09-22 第三轮瘦身）
>
> 体积原因（本文件 ≤ 65536 字节，超出会被**静默削尾**），按 §9 当年的同一先例搬成按需加载的 skill：
> **`.dsh/skills/aquaflow-known-traps/`** —— **动手改代码前加载它**。
> 条目号没变：`docs/design/**` 与测试注释里的 `§8.15` / `§8.16` / `§8.19` / `§8.20` 仍指那些条目。
>
> **最常踩的 10 条（全文见 skill，这里只给一行版防"没加载就动手"）**：
>
> 1. **报错但 HTTP 200** —— 一律判 body `code`，不判 HTTP 状态（§8.1）。
> 2. **请求体从裸 `Map` 收敛成 DTO 会静默丢字段** —— Jackson 忽略未知字段，改 DTO 必须逐字段核对调用点并补走 HTTP 的用例（§8.15）。
> 3. **只遍历 `customer_barrel_asset` 是本仓惯犯（第 4 次）** —— 桶汇总必须取 `assets ∪ 配送中 ∪ over` 并集；还桶上限用「占用」不用「持有」（§8.16）。
> 4. **状态不许倒滚** —— 只前进；要表达异常态就新设状态（§8.18）。
> 5. **时间区间上界写 `<= 当天` 会漏掉一整天** —— 一律 `>= 起始 AND < 结束+1天`（§8.19）。
> 6. **按 id 操作记录必须验归属 + 看受影响行数** —— 拿不到行数就别返回 success（§8.20）。
> 7. **「要求了补偿却没执行」也必须失败** —— "用户以为做成了、账上没动"一律算缺陷（§8.17）。
> 8. **隔离 worktree 跑全量证明不了"应用能起来"** —— 收尾三步：真实工作区编译 → 真起一次上下文 → 再隔离跑全量（§8.26）。
> 9. **改完小程序 js 必须过解析器**（`audit_js_syntax.py`）；**小程序文件别写出 BOM**（§8.27 / §8.28）。
> 10. **弹窗的「确认」必须让流程继续走** —— 只改 UI 状态的确认按钮 = 静默失败（§8.30）。

## 9. 待确认 / 未验证清单

> 完整清单在 skill **`aquaflow-open-questions`**（动手前先加载）；这里只留仍在生效的判据。

以下条目**未取得确证，执行前必须自行核实**：

1. **「水厂端已彻底移除」是 2026-09-11 的复核结论**，此后未重新全库检索 `factory` 残留。
2. **已弃用表的实际停写状态未逐一复核调用链**：`customer_owed_barrel`（已停止写入）与 `customer_barrel_in_transit` 的写入点。
3. **`ManagerOrderController` 确已删除**（文件不存在，有 `ManagerOrderControllerRemovedIntegrationTest`），但 `.workbuddy/memory/MEMORY.md` 仍把它列为「仍未做」的高危项 —— **该记忆已过期**，也不排除有其他等效写入口。
4. **开发者工具「测试号」是否支持 `wx.login` / `jscode2session`，尚未实测**：官方只承诺「开发测试 + 真机预览」，**没有明文承诺登录能力**。真机「微信一键登录」能否跑通要实测（两对 appid/secret 填好后真机点登录，看日志 `微信code2Session响应[CUSTOMER]` / `[STAFF]`）。**测试号确定不能上传代码 / 发布 / 设为体验版**；若不支持登录，`dev-login` 是唯一可用登录路径。
5. **测试号分「小程序」与「小游戏」两种，不可混用**：把**小游戏**测试号的 appid 填进小程序项目（`compileType: "miniprogram"`）会**编译失败**。
6. **微信订阅消息对本项目不可行**：除少数行业（政务/医疗/交通等）外都是**一次性授权** —— 推一条要用户当面点一次「允许」，`wx.requestSubscribeMessage` **无法静默获取**；水站这种高频提醒摩擦过大，**产品裁定不做**（站长端只有应用内红点，见 `miniapp-delivery/utils/pending-reminder.js`）。另注：`WeChatNotifyService` 骨架读的是**客户端** appid，推员工要用员工端那对。**同族缺口**：客户侧正向进度（接单 / 配送中 / 已送达 / 已收款 / 退桶结果 / 水票到账）**一条通知都没有** —— `OrderWorkflowServiceImpl` 只写"被拒单"与"临时外派"两种负面通知，`NotificationServiceImpl` 六个方法全是拼文案写 log 的空壳。

## 10. 本文件的来源与维护

- 原则：**能验证才写，不能验证就放进 §9**。任何一条若与本仓库当前代码冲突，**以代码为准**，并回来改本文件。
- 维护建议：做大改动/重构后核对 §1 的常量清单（`OrderStatus` / `PayMethod` / `PaymentStatus` / `DepositType` / `BarrelRecordType`）与 `sql/README.md` 的迁移清单；**新增枚举或迁移后必须同步 §1**（枚举值正本在 `constant/*.java`）；§9 被证实的条目应上移进正文并删除。
- **体积约束**：≤ 65536 字节，超出会被**静默削尾**。**规则**：① 加段前先量体积（用 `node`/`read`，别用 `Get-Content`），**余量 < 4 KB 先瘦身**；② 瘦身**只压正文、不删条目、不重排 §8 编号**，瘦身前先留全文字节级存档（放仓库外的工作记录，已三份）；③ 叙事写工作记录、规格写 `docs/architecture/`；④ 别手写会漂移的计数。
- **第三轮瘦身（2026-09-22）**：瘦身前正本为仓库外的工作记录（第三轮存档）。
  就地压正文只省 ~2 KB、余量仍只剩 2.7 KB（微调已到边际），于是按 §9 的先例做**结构整理**：
  **§8 的 30 条整体搬进 skill `aquaflow-known-traps`**（逐条校验过「内容零丢失、编号不变」），
  本文 §8 只留**指针 + 最常踩的 10 条一行版**。结果 64.8 KB → 50.4 KB，**余量 ~15 KB**。
  ⚠️ **下一次要瘦就动 §1（23 KB / 占 36%）** —— 但那是领域不变量，压之前先逐条确认判据没被压没。
- 已知待决策项：工作区未提交改动是否先 review 再按语义拆成数个提交（见 §7）。
- **`docs/AGENTS.md` 已作废并移出仓库**：口径是**过期文档要"离开会被自动加载的位置"**（DSH 会把 `docs/AGENTS.md` 当附加指令全文注入，改名等于留在原地）—— **该待决策项已关闭：不改名**。

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
| `aquaflow-known-traps` | **动手写 / 改代码前** —— §8 那 30 条坑与判据的正本（状态倒滚、桶汇总并集、时间区间上界、DTO 丢字段、隔离 worktree 假绿、小程序 js/BOM、弹窗确认没接上…） |
| `aquaflow-open-questions` | 要动已弃用表 / 水厂端残留 / 微信测试号登录 / 判断 `.workbuddy` 记忆是否过期时 |

> ⚠️ skill 名（frontmatter 的 `name`）必须是 **ASCII 小写 + 连字符**（DSH 校验 `^[a-z0-9]+(?:-[a-z0-9]+)*$`）—— 中文名**不会报错，只会被静默忽略**（仅留一条 log warning）。

> 其余领域知识见 `docs/architecture/**`（领域模型与数据模型）—— 本文件只放"动手前必须知道的判据"，不放流程说明。
