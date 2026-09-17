# AGENTS.md — AquaFlow 仓库级 AI 协作指令

> 适用范围：仓库根 `D:\backend\project\AquaFlow` 及全部子目录。DSH/Claude 系 agent 读本文件作为**操作型契约**（命令、入口、禁改、坑）。
>
> **本文件 2026-09-17 瘦身过：判据全留、叙事外移。** 压缩前的完整原文（每条坑的发现经过、误判原因、排错时间线与全部证据）在
> `docs/audit/2026-09-17-AGENTS-瘦身前全文存档.md` —— **怀疑某条判据是否还成立时，回去查它当初是怎么被发现的**。瘦身前的体量是 68338 字节，已超出 agent 的指令预算（65536）被截断，这是瘦身的直接原因。
>
> 冲突时优先级（高 → 低）：**§0 事实基准 + §1 领域不变量 → 本文件其余部分 → `docs/AGENTS.md`（工程/领域约定，已知过期，只在进入 `docs/` 上下文时被加载）→ 根 `README.md` 与 `docs/**` 其它文档（大面积失真，仅作线索）**。
> `docs/AGENTS.md` 与本文件同名但不同层级，**不是**本文件的替代品。
> 证据标注：`【仓】`= 已在仓库文件中直接核对；`【会】`= 来自历史会话日志或 `.workbuddy` 记忆。

## 0. 事实基准（最高优先级）

1. **一切以代码为准**。`README.md`、`docs/**`、`.workbuddy/memory/**` 仅作参考且**已知大面积过期**；与代码冲突时以 `src/**`、`sql/schema.sql`、可运行测试为准，并顺手指出过期文档。【仓】
2. 唯一可信来源顺序：**当前 Java/WXML/JS 源码 → `AquaFlow-backend/sql/schema.sql` → 通过的集成测试与实际接口行为 → 仓库 Markdown**。（`docs/AI_EXECUTION_HANDOFF.md` §0 明文规定）【仓】
3. 动手前先只读排查；破坏性操作（删文件、改 git 历史、清库、执行历史迁移 SQL）**先报告证据与影响并等确认**。用户明确要求：删除类改动必须先证明零引用且属永久废案。【会】
4. **数値の SSOT —— 有正本就不要在本文件重述**：表定义看 `sql/schema.sql`、迁移执行顺序看 `sql/README.md`、枚举值看 `constant/*.java`、API 实路径看 `controller/**` 注解、测试件数看 `build/test-results/test/*.xml`、**代码结构看 `code_map`（别手写"33 个 Controller / 22 页"这类计数，必然过期）**。本文件与其它文档只引用、不重定义这些数值。【仓】
5. 仓库内文档的入口是 `docs/README.md`（2026-09-15 重写为只索引**实际存在**的文档）。`docs/**` 已知过期，只作线索、不作规格。【仓】

## 1. 项目概览与领域不变量

- **业务**：桶装水（18.9L）配送管理系统，服务对象是**水站**（站长 + 配送员）。非 Demo、非课程设计，目标是可真实上线的企业级系统。
- **在维护的端只有两个原生微信小程序**：`miniapp-user`（客户端）、`miniapp-delivery`（站长 + 配送员）。**仓库中不存在可维护的 Vue 管理后台**（`AquaFlow-frontend` 已不存在，仅 `archive/legacy-web-frontend` 留档）。
- **角色**：`STATION_MANAGER`（站长）、`DELIVERY`（配送员）、客户（微信 openid）。`staff.role` 只有前两个；`FACTORY_ADMIN` 与整个水厂端已在 DB/后端/小程序三处彻底移除。

### 1.1 不可凭直觉改写的领域不变量

- **客户是全局身份**：`customer` 表**没有 `station_id` 列**；订单/桶/水票/押金一律按 `(customer_id, station_id)` 隔离。【仓】
- **双水站模型**：`orders.station_id` = **交易/营收归属**，`orders.delivery_station_id` = **实际履约归属**，两者不可混用。跨站外派单（两者不等）下「**钱与票记归属站、库存走履约站**」。【仓 `StationUtil`】
- **订单状态**以 `constant/OrderStatus.java` 为准：`1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消`（连续编号；历史 1/3/4/5/6 已废弃）。非法流转由 `isValidTransition` 拒绝。
- **支付方式**以 `constant/PayMethod.java` 为准：`1 微信 / 2 现金(货到付款) / 3 水票`（水票 = 下单即视同已付）。**微信支付渠道未接入**，`availableMethods()` 中该选项恒为 disabled。
- **支付状态**以 `constant/PaymentStatus.java` 为准：`0 未付 / 1 待收款 / 2 已付 / 3 已退款 / 4 已取消`。唯一真值是 `orders.payment_status`。
- **桶账唯一写入口是 `BarrelLedgerService`**；权益真相源是 `customer_barrel_lot.remain_qty`，`customer_barrel_over` 可为负（= 水站暂存）。
- **桶的四个数（2026-09-15 定稿，测试锁定）**：
  - **权益** = 已到手（`customer_barrel_asset.quantity`，送达时入账）
  - **配送中** = `customer_barrel_in_transit` 里 `PENDING`
  - **持有** = 权益 + 配送中，**仅用于展示**（"买了就是你的"）
  - **占用** = 权益 + over = 物理在手 / **还桶上限**（**不含配送中** —— 那批桶还没到手上）
  - **恒等式：占用 = 权益 + over**（over 可为负）
  - **下单抵扣与退押金只认权益**：`shortage = max(0, needed − 权益)`，逐桶型（付款报价 `PaymentServiceImpl.quote` 与小程序下单页 `create.js` 同口径）；配送中的桶在订单结束前**不参与抵扣、也不可退**。⚠️ 本条取代了原 [DEF-5] 的 `− pending`（连点两次下单会各收一份桶押金）。详见 `docs/design/11-桶空闲与在途锁定-提案.md`。
- **任何「按商品 / 按桶型」的桶汇总，必须取 `assets ∪ 配送中(PENDING) ∪ over` 的并集**（2026-09-16 立为不变式）。`customer_barrel_asset` 只记录**已到手**的桶，只遍历它会让「首单还在配送途中」的商品**整行消失** —— 概览写着"配送中 2"、明细却一行都没有，连退桶弹窗都选不到那种桶。**这个漏法在本仓已犯 4 次**（员工端概览、顾客端按桶型、概览的持有/押金、概览的占用），见 §8.16。**还桶上限一律用「占用」，不要用「持有」**（持有含配送中，会多报，提交后被后端以"交回数超过当前持有数"拒绝）。
- **退桶（退押金）与欠桶互斥：「欠着空桶就不许退桶，先还清」**（2026-09-16 立规并测试锁定，`BarrelReturnGuardIntegrationTest`）。欠桶 = 客户手上端着比权益更多的桶，此时退押金等于桶在别人手里、担保物也没了。硬拦点两处：**申请时**（`BarrelServiceImpl.previewReturn` 返回 blocked，控制器直接拒、不建申请单）与**退押金时**（`doRefund` 再查一次 over —— 申请后、审批前客户完全可能又欠上桶）。按商品各算，A 水的多不能抵 B 水的欠。⚠️ **已知绕开这道校验的通道**：`StationAdjustmentServiceImpl` 的撤桶权益走 `consumeLots` 直连（站长人工订正通道）、`OrderBarrelExceptionServiceImpl` 的异常单撤桶权益同理。这两条只应由站长人工发起且留调整单/异常单痕迹，**不要**给顾客或自动流程开这种口子。
- **「首单」是两处口径的合称，别只看一个**：下单时 `OrderServiceImpl:453` 写 `orders.first_barrel_order = firstStationAsset && totalNeededBuckets > 0`（本站第一笔**买桶**订单）；完成配送时 `OrderWorkflowServiceImpl:296` 读它，为真则**整段跳过回桶核对** —— 客户刚买下桶、手上没有空桶可还，回桶数填 0 也**不得**生成桶异常单。押金口径同源：首单权益 0 → 收满押金，复购权益够 → 押金 0。回归用例 `OrderEntryAndInjectionIntegrationTest`。（本文件一度把该列记成"无写入点的孤儿列"，是**只 grep 了 `*.java` 漏掉 `OrderMapper.xml`** 造成的误判 —— **查写入点必须连 XML mapper 一起查**。）
- **支付状态只前进、不倒滚**（2026-09-16 定稿）：`0 未付 / 1 待收款` 都表示「钱还没到手」，`2 已付款` 是收钱的结果，`3/4` 是终态。现金单**下单即 待收款(1)**；**发起收款**（`createPayment`）与**送达未收款**（`completeDelivery`）都**不许改写它**；只有真收到钱才写 2，唯一入口是 `OrderMapper.markPaidIfCollectable`（从 0 或 1 迁入，绝不复活 3/4）。**禁止再写 `updatePaymentStatusIf(..., UNPAID, PAID)` 这种收款写法** —— 现金单现值是 1，CAS 的 `expected=0` 恒不命中：钱收了、单子永远停在待收款。**未收款时不要动 `payment_status`**；要表达「已送达未收款」请用现成的 `payment_status=1 待收款`，**不要再发明一个倒滚的状态值**。判据：`DashboardMapper` 的待收款金额口径就是 `payment_status=1 且未取消` —— 谁把它改成 0，这笔应收就从站长「待收款」合计里消失。
- **`PaymentRecordMapper.updateStatusIf` 的参数顺序是 `(id, 目标状态, 期望状态)`，没有编译期保护**（2026-09-16 抓到 3 处写反）。SQL 是 `set status = #{status} ... and status = #{expectStatus}`；按 `(id, 原状态, 新状态)` 的直觉传参会**恒命中 0 行 —— 不报错、不返回失败，静默地什么都没改**（曾导致钱退给客户了、支付流水永远显示"已付款"）。**判据：写 CAS 前先回去看 mapper 的 SQL，别凭参数名猜顺序。**
- **登录类端点必须按 IP 限流**（2026-09-16，`interceptor/RateLimitInterceptor`）：已有的 [AQ-040] 锁定是**按用户名**计数的，攻击者每次换用户名即可绕开（撞库/用户名枚举），所以按来源 IP 的那一层不可省，**两层正交、都要有**。覆盖 `/api/auth/{login,wx-login,wx-login-staff,dev-login,refresh,change-password}`；计数粒度是**按 IP 聚合**（不按 IP+端点，否则把配额分摊到多个端点就能绕过）；超限返回 **HTTP 429 + `{code:1,message}`**（只给 429 前端拿不到文案，只给 code 前端无法按状态码统计）。默认 20 次/分钟，`RATE_LIMIT_ENABLED` / `RATE_LIMIT_AUTH_PER_MINUTE` 可调。⚠️ 多实例部署时进程内计数会退化成"每实例各限一份"，必须先换集中式计数器（Redis）。**整套集成测试默认关闭它**（用例都从 127.0.0.1 发请求，开着会互相干扰），只有 `RateLimitIntegrationTest` 用 `@TestPropertySource` 单独打开验证。
- **告警必须分级投递：系统故障 → 系统管理员，运营故障 → 该站站长**（2026-09-16 产品口径，测试锁定 `AlertRoutingIntegrationTest`）。实现：`alert_log` 表（v30）+ `AlertService`，方向由 `constant/AlertType` 决定（`SYSTEM` / `OPERATION`），**不要靠字符串比较或调用方自觉**。
  - **SYSTEM（`station_id` 必须为 NULL）**：对账不平（`ReconciliationService.dailyReconcile`）、桶异常补偿执行失败、未预期的 500（`GlobalExceptionHandler`）。站长既看不懂也修不了，**不该发给他**。
  - **OPERATION（`station_id` 必填）**：桶异常待处置（`recordReturn`）、补偿已执行、异常被忽略。站长端只读入口 `GET /api/manager/alerts`（只返回本站 OPERATION）。
  - **三条硬约束**：① **先落库再谈渠道** —— 外部渠道（系统告警 webhook、站长微信订阅消息）没配时 `notify_status=LOGGED`，"渠道没配"绝不等于"告警不存在"；② 落库走**独立事务**（`REQUIRES_NEW`，且必须经代理调用）—— 否则业务一回滚告警跟着消失，而告警最常见的触发场景恰恰就是业务失败；③ 落库/推送失败**绝不连累业务**（只记日志）。
  - **可见性红线**：系统告警没有 HTTP 入口（含平台级细节，漏给站长 = 跨租户 + 越权知情）。运维直接 `select * from alert_log where alert_type='SYSTEM' order by id desc limit 50;`
- **押金/桶记录的方向由类型决定，调用方金额一律传正数**：`DepositType`（`constant/DepositType.java`）方向判定收敛为 `isIncrease`（`1/5/9`）/ `isDecrease`（`2/3/4/6/7/8`），**扣减类流水以负数落库**（`DepositRecordServiceImpl.java:56-64`）—— 这是对账等式1（`balance == SUM(deposit_record.amount)`）的前提。`BarrelRecordType` = `6 人工调整(增)` / `9 人工调整(减)`，已纳入守恒对账 E5（`ReconciliationService.java:258-259`），不纳入则每次补录都误报。
- **三张流水表的调整场景唯一键**：`uk_deposit_adjustment`、`uk_record_adjustment`、`uk_ticket_adjustment`，配套可空列 `adjustment_id`。⚠️ `ticket_record` 原有的 `uk_ticket_consume(order_id, product_id, source)` 在 `order_id IS NULL` 时**零保护**（调整场景 `order_id` 为 NULL，而 MySQL 唯一键中 NULL 互不冲突）。
- **取消退款的唯一入口是 `PaymentService.refundOrder`**，已加 `OrderStatus.isCancellable` 门槛：**已完成(4) / 已取消(5) 订单不得再取消**。它同时是订单取消/拒单的单一编排入口。
- **客户端与员工端是「两个小程序」，appid 不同**（2026-09-15 起）：`wx.login` 的 code 只能用**签发它的那一端**的 appid+secret 换 openid，用错端微信只回 `40013 invalid appid` 且日志无指向性 —— 所以 `WeChatLoginService.code2Session(WeChatApp, code)` **强制显式传端**（`constant/WeChatApp.java`：`CUSTOMER` / `STAFF`）。配置键：客户端 `wechat.miniapp.appid/secret`、员工端 `wechat.miniapp.staff-appid/staff-secret`。**员工端这对本地缺失只 `log.warn`（dev-login 兜底），`prod` profile 里是必填（缺即拒启）**。openid 按 appid 隔离，`customer.openid` 与 `staff.openid` 天然互不干扰。⚠️ 本地客户端用开发者工具「**小程序**测试号」；测试号分小程序/小游戏两种，**小游戏号填进小程序项目会编译失败**（见 §9）。
- **站长治理类入口**：资产调整单 `/api/manager/adjustments`（6 端点，类级 `@RequireRole("STATION_MANAGER")`）；站长经 `/api/manager/reconciliation` **只读**查询本站即时对账（**只有 `GET /`**，原 `POST /run` 已于 2026-09-13 删除 —— 它会写入全平台结果，站长可读即跨租户泄露；运维记录由 03:00 定时任务落表）。对账结果落 `reconciliation_result` 表。
- **欠桶已改为「只提醒、不阻断」**：原 [AQ-030]/[DEF-3] 的硬拦 `MAX_OWED_BUCKETS = 5` 已于 2026-09-15 按产品决定**移除**；下单响应 `warnings` 每次都提醒客户（幂等命中路径同样下发），**物理护栏不变**（占用 = 权益 + over ≥ 0）。`customer_barrel_over.owed_since`（v29）只用于展示、**不参与任何校验**；唯一维护点 `CustomerBarrelOverMapper.syncOwedSince`（over 由 ≤0 变 >0 写入、回到 ≤0 清空、已是正数再增加不重置）。站长端 `GET /api/manager/owed-barrels`（只读，有前端入口）。
- **迁移已到 v39**（v27 资产调整单 / v28 支付防重 / v29 `owed_since` / v30 `alert_log` / v31 商品与库存重构 / v32 营业状态 / v33 在线购票幂等键 / v34 配送计费前置字段 / v35 站级配送计费配置 / v36 水票档位与批次 / v37 配送员计件工资 / v39 进货成本）。⚠️ **`v38` 已被另一个工作流占用**（商品图片库，工作区内尚未入库）—— **新建迁移前先 `ls sql/` 看编号，别照着本清单的最后一个数字 +1**。**v32 营业状态是软状态：不阻断下单、只提示**（`docs/design/13-营业状态与公告.md`）。**v34 只加列**、**v35/v37 只加表**、**v36 只加表加列 + 存量派生回填**、**v39 只加列**，存量水站行为不变。补跑清单以 `sql/README.md` 为唯一权威（见 §4）。
- **配送员计件工资是站长台账，且走独立对账**（v37，2026-09-17）：`staff_piece_rate`（站级单价，`product_id=0` = 该站默认价）+ `staff_earning`（收益明细）+ `staff_payroll`（结算单 草稿→已确认→已发放）。**写入口是 `StaffEarningService`**。四条口径：① **发钱的是站长不是平台** —— 不做平台结算单/佣金/骑手钱包，**也不做打款/提现**（发钱是线下动作，系统只落 `paid_time` + `operator_id` 留痕）；② **计件单位是桶不是单**，且**按 `order_item` 逐商品计**（不同品类单价可不同，`auto_uk` 含 `product_id`）；③ **方向由 `kind` 决定、调用方一律传正数**，唯一例外是 `ADJUST`（`constant/EarningKind.allowsSignedAmount`）；④ **归属站 = 履约站**（`delivery_station_id`）—— 工钱是履约成本，跟出车的人走。⚠️ **收益只在 `completeDelivery` 的状态 CAS 成功之后产生**：钉在这个时点让"能取消的单一定还没产生收益"，因此**不存在收益回滚问题**（订单状态只前进、`isCancellable` 已拦掉 4/5）。⚠️ **工钱不进客户对账** —— 混进等式 1~4/E3~E8 会让每天 03:00 日结必然报不平、淹没真问题；它走独立等式 **E-PAY**，且告警分级是 **OPERATION**（站长能看懂也能修）而不是 SYSTEM。⚠️ `staff_earning.auto_uk` 的 **NULL 是有意的**：NULL = 人工调整，本来就允许无限多条；自动收益的幂等由 `uk_earning_auto`（生成列）兜底 —— 这与 `uk_ticket_consume`/`uk_payment_active_order` 那个"NULL 导致零保护"的坑形状相同但**语义相反**，别当成同一个错误去"修"。用例 `StaffEarningAndPayrollIntegrationTest`。
  - **结算单期间的时间上界必须用「结束日 + 1 天」**（`attachToPayroll` 的 `endExclusive`），写 `<= 结束日` 会让**当天的收益一条都结算不到**（AGENTS §8.19 同一个坑）。
- **水票余额的真相源是 `ticket_lot`，`ticket_account` 只是派生汇总**（v36，与桶账 `customer_barrel_lot` → `customer_barrel_asset` 完全同构）：`remain_quantity == Σ lot.remain_qty`、`right_amount == Σ remain_qty × unit_price`，两条由对账 **E8** 校验。**批次唯一写入口是 `TicketLotService`**（`createLot` / `consumeFifo`）。⚠️ 三条口径：① **单价取实付均价**（`payment_record.amount / ticket_qty`），不取站级单张价 —— 档位套餐下两者不同，用后者记会让"8 元买的票按 9 元退"；② **消耗按 FIFO**，退款回补按**流水里记的当时单价**还原（`ticket_record.unit_price`），绝不按退款时的当前价；③ **任何改动水票数量的路径都必须过批次账**（在线购票入账、站长加票、用票支付、订单取消回补、资产调整单），漏一条 E8 就报不平。用例 `TicketPackageAndLotIntegrationTest`。⚠️ **测试夹具 `createTicketAccount` 也必须建批次**（只插账户不建批次会打红 10 个现有用例）。
  - **MySQL 的 `SET` 子句从左到右求值，后面的表达式看到的是已更新后的列值** —— `SET remain_qty = remain_qty - q, status = CASE WHEN remain_qty - q = 0 ...` 里的 `remain_qty` 已经是 0，CASE 永远算不出 0，批次扣光了状态还停在「有效」。**要把 `status` 的赋值排在 `remain_qty` 前面。**
- **配送计费（起送量 / 配送范围 / 运费 / 楼层费）只有一份实现**（v35，2026-09-17）：纯规则在 `util/DeliveryFeeUtil`，IO（查配置 + 取站点/地址坐标 + 算距离）在 `service/DeliveryFeeService`；**`PaymentServiceImpl.quote` 与 `OrderServiceImpl.createOrder` 必须调同一个 `calcForOrder`，且传同样的水费口径与桶数口径**（桶数只数 `category=1`）。任何一侧内联算费用 = 重演"计价双轨"事故（结算页一个价、订单另一个价 → 客诉，见 `PriceUtil` 文件头）。⚠️ 三条口径：① **费用绝不并入 `water_amount`（污染水费）或 `deposit_amount`（那是可退押金，混入会导致取消订单多退钱）**，各自成列；② **门槛默认 WARN 不是 REJECT**（默认硬拦等于站长一建站就把客户挡在门外）；③ **拿不准就不收/不判** —— 距离算不出来（站点没坐标/地址没定位）或楼层未填时跳过，绝不按"无电梯/超范围"硬收硬拦。用例 `DeliveryFeeUtilTest`（纯规则，不起 Spring 容器）+ `DeliveryFeeIntegrationTest`（端到端，含"报价 `totalAmount` 必须等于下单 `total_amount`"这条核心断言）。
- **无订单支付（在线购票）必须带客户端幂等键**（v33，2026-09-17）：`order_id` 为 NULL 时，`PaymentServiceImpl.createPayment` 的重复流水检查（整段包在 `if (orderId != null)` 里）被跳过，而 `uk_payment_active_order` 建在生成列 `active_order_id` 上、**MySQL 唯一键中 NULL 互不冲突 → 这条路径零保护**（与 `uk_ticket_consume` 同形状）。`POST /api/tickets/purchase` 的 `idempotencyKey` **必传**；唯一键是 `uk_payment_idempotency(customer_id, idempotency_key)`，**必须带 `customer_id`** —— 只按 token 唯一的话，客户端传别人的 token 就能取回别人的支付记录。⚠️ `confirmPayment` 的乐观锁只保证**单条**流水确认一次，**管不住重复流水**，所以防重必须在落流水这一步。用例 `TicketPurchaseIdempotencyIntegrationTest`。
- **应收账款 = 给「待收款」加账期维度，不新造金额口径**（2026-09-17）：金额真相源仍是 `payment_status = 1 AND status <> 5`（与 `DashboardMapper` 的待收款合计**逐字同源**）。⚠️ **它没有建任何新列** —— 激活的是两个 2026-09-17 之前就存在、全仓零读写的挂空列 `orders.settlement_status` / `orders.due_date`（正面样本：接上它们只花了一个 service + 一个 controller）。账期在下单时由 `ReceivableService.resolveDueDate` **快照一次**（**只有现金/货到付款单才有应付日期**；微信即时到账、水票下单即视同已付，都不产生账期），之后只读 —— 站长事后改账期不能改到历史单的到期日。写入口 `ReceivableService`（设账期 / 核销），端点 `/api/manager/receivables*` 与 `/api/manager/customers/{id}/credit-terms`。四条判据：① **核销 ⟹ 已收款**，由 `OrderMapper.settleIfCollected` 的 CAS（`and payment_status = 2`）钉在 SQL 层 —— B2B 最怕"账面销了、钱没到"；② **逾期只提醒、不改任何金额**（对齐"欠桶只提醒不阻断"）；③ 对账 **E10**（`settlement_status = 2 AND (payment_status IS NULL OR payment_status <> 2)`）**只查单向** —— 反向"收了钱还没核销"是**正常经营状态**（现金单送货上门当场收钱、站长之后才走月结），谁改成双向比较日结就天天误报；④ **设账期必须用只改 `due_days` 一列的 mapper，不要用 `CompanyInfoMapper.updateByCustomerId`**（整行覆盖，会把客户自己填的企业资料抹成 NULL）。⚠️ **归属判据用 `CustomerMapper.countCustomerOfStation`（绑定 `customer_station_config` **或**本站订单，取并集），不要用 `getStationCustomer`** —— 后者是「客户画像」口径（SQL 要求有订单），会把**没下过单的新客户**判成"不属于本站"，而给新客户设账期/开特权恰恰是最常见的场景（2026-09-17 同一个坑一天踩了三次：客户特权红掉 3 个用例、应收账款、代客下单的员工归属护栏；更早还有 `OrderController.getMyLatestStation`）。⚠️ **`GET /api/customers` 是 orders 驱动、员工下单护栏原来是绑定驱动 —— 两者都不是「本站客户」的完整口径，只有并集才是**，而"列表里点得到、一下单就说不是本站客户"是最难排查的一类拒绝。⚠️ **代客下单页复用 `POST /api/orders/create`，不要新建单端点**（两条建单路径算金额 = 计价双轨）；页面要的三个读接口在 `/api/manager/order-assist/*`（客户选择器 / 客户地址 / 试算）—— 注意 `GET /api/addresses` 与 `POST /api/payments/quote` 都是**顾客自助**端点，站长调不到。用例 `ReceivableIntegrationTest`、`EmployeePlaceOrderIntegrationTest`。

## 2. 目录结构与关键入口

```
AquaFlow/
├─ AquaFlow-backend/          # 唯一后端（Spring Boot + MyBatis）
│  ├─ src/main/java/com/example/aquaflow/
│  │  ├─ controller/                  # 认证/订单/配送/桶/水票/押金/站长/资产调整/对账/欠桶台账/站长告警…
│  │  ├─ service/ + service/impl/     # 业务；OrderWorkflowServiceImpl = 订单状态唯一编排入口
│  │  ├─ mapper/ + resources/mapper/*.xml  # MyBatis（注解 SQL + 少量 XML）
│  │  ├─ interceptor/AuthInterceptor.java  # JWT 解析 → AuthContext(ThreadLocal)
│  │  ├─ aspect/RequireRoleAspect.java     # @RequireRole 权限切面
│  │  └─ config/RequiredConfigChecker.java # 启动期强制校验密钥，缺失即失败退出
│  ├─ src/test/java/.../integration/       # 集成测试（真实上下文 + 真实 MySQL + 真 HTTP）
│  ├─ sql/                    # schema.sql(基线) / init.sql / 迁移脚本 / README.md(权威)
│  └─ gradlew.bat
├─ miniapp-user/              # 客户端小程序（无分包）
├─ miniapp-delivery/          # 站长 + 配送员小程序（无分包）
├─ .github/workflows/ci.yml   # CI 门禁（**必须在仓库根**；放 AquaFlow-backend/ 下 GitHub 不读）
├─ docs/                      # 已入库文档索引见 docs/README.md（docs/AGENTS.md 已知过期）
├─ scripts/                   # verify.sh / provision-test-db.sh / scan-secrets.sh（本机需 Git Bash，见 §5）
├─ archive/                   # 不维护：legacy-web-frontend、miniapp-station
└─ backup/                    # 本地 DB 备份（.gitignore 忽略，勿提交）
```

> 各目录的文件数、Controller 数、页面数 **不要手写** —— 跑 `code_map` / `code_symbols` 看实时结构（见 §11）。

- 后端 API 统一响应 `{ code, message, data }`：`code 0` 成功 / `1` 业务错误 / `404` 路由不存在 / `500` 系统异常；**业务错误 HTTP 状态仍是 200**（唯一例外：未认证返回真 401）。【仓 `common/Result.java`】
- 小程序 API 基址在各自的 `config/api.js`；**两端 `prod.baseUrl` 目前都是占位符** `https://your-domain.com`，release 构建指向不存在的域名（见 §8.10）。
- **「不校验合法域名」= 两个位置，缺一不可**：① `project.config.json` 的 `urlCheck`（`miniapp-user` 已 `false`；`miniapp-delivery` 仍是 `true`，本机靠 gitignore 的 `project.private.config.json` 覆盖 —— **换机器 clone 后必须重新勾一次**）；② 真机上右上角 `…` →「打开调试」。少任何一个，本机 `http://<局域网IP>:8080` 的请求都会报「不在以下 request 合法域名列表中」（合法域名只收已备案 HTTPS 域名，IP 无法配置）。【仓 2026-09-15 实测】

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
- **其余变量不被 `RequiredConfigChecker` 检查，缺失后果由各自组件决定 —— 不要再写成「缺一即启动失败」**：`COS_SECRET_ID/KEY` 与 `WX_STAFF_APP_ID/SECRET` 未配置只 `log.warn`（COS 只影响上传；员工端只影响真机微信登录，本地 dev-login 兜底，但 `application-prod.yml` 里这对不给默认值、prod 缺失即拒启）；`DB_*` / `CORS_ALLOWED_ORIGINS` / `DEV_LOGIN_ENABLED`（**生产必须 `false`**）/ `MYBATIS_LOG_IMPL` / `RATE_LIMIT_*` 同理。【仓】
- 本地默认 profile 是 `local`，密钥读 `src/main/resources/application-local.yml`（**已 gitignore，含真实密钥，禁止提交、禁止回显**）；生产用 `--spring.profiles.active=prod` + 纯环境变量。
- 小程序：用微信开发者工具分别打开 `miniapp-user` / `miniapp-delivery` 目录（无 npm 构建步骤）。
- PowerShell 里**用 `;` 分隔多条命令，不要用 `&&`**。长任务（Gradle 构建、测试）放后台任务。【仓】

## 4. 数据库与迁移流程

- **Flyway 未启用**（无 flyway/liquibase 依赖、无相关配置）。`sql/**` **全部靠手工执行**，没有版本表、没有自动校验。（`src/main/resources/db/migration/` 目录**不存在** —— 曾存在于 `.workbuddy/recovery-backup/`，勿再按该路径找脚本。）
- **新建库的权威基线是 `sql/schema.sql`**（全部 `CREATE TABLE IF NOT EXISTS`，可重复执行）；`init.sql` 只建结构、不含种子数据，且 `SOURCE schema.sql` 依赖相对路径，**必须在 `sql/` 目录下执行**。表数以 `Select-String -Pattern '^CREATE TABLE'` 实测为准。
- **`schema.sql` 导入必须走字节级重定向**：`cmd /c "mysql -uroot --default-character-set=utf8mb4 库名 < schema.sql"`（CI 上是 bash 的 `<`，天然正确）。**不要用 PowerShell 管道**（`Get-Content -Raw | mysql`）—— PowerShell 按控制台代码页重编码，中文注释全变乱码（本项目出过同类编码事故，专门做过 `migration_v24_fix_garbled_column_comments`）。⚠️ **核对是否写坏要比字节（`HEX(TABLE_COMMENT)`），不要看控制台**（控制台是 GBK 渲染，看着像乱码不代表数据坏）。【仓 2026-09-14 实测】
- **已有老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑（**以该清单为唯一权威**），否则运行期缺表崩溃。执行务必带库名：`mysql -uroot <库名> < 脚本.sql`。⚠️ **清单存在已知缺陷**：漏列 `v3`/`fix_schema_alignment`，且 `v25` 改名与 `v1_backfill` 依赖旧表名导致顺序冲突 —— **执行前需人工核对**。【仓 `sql/README.md`】
- **迁移脚本必须幂等**：MySQL 8.4 无 `DROP ... IF EXISTS` 便利，统一用 `information_schema` 预检 + `PREPARE`。**破坏性 DROP 必须先上代码、再执行 SQL**。新建脚本前先 `ls sql/` 看命名是否占用。【会】
- **严禁在生产执行**：`reset_data.sql`（TRUNCATE 多表）、`reconcile_order_814.sql`（一次性修复）、`seed_dev_account.sql`、`seed_new_user_83.sql`、`sql/archive/**`（已过期且不可执行）。【仓】
- `sql/` 里**大量脚本是 SUPERSEDED/DUPLICATE**（见 `sql/README.md` 表格），已被 `schema.sql` 吸收，**不要在新环境执行**。

```powershell
# —— 全新库初始化（只建结构，空库）——
cd D:\backend\project\AquaFlow\AquaFlow-backend\sql
& 'D:\backend\MySQL\bin\mysql.exe' -u root -p < init.sql
```

## 5. 测试与验证方式

- 集成测试位于 `AquaFlow-backend/src/test/java/com/example/aquaflow/integration/`；基类 `support/AbstractIntegrationTest` 启动完整 Spring 容器、发真实 HTTP（JDK `HttpClient`）、每用例前 TRUNCATE 并**断言当前库名含 `test`**（防止误清真实库）。测试凭据继承 `application-local.yml`。件数以 `build/test-results/test/*.xml` 为准。
- **本机无 Docker**，因此不用 Testcontainers；测试库 `aquaflow_test` 是独立可重建库。
- **运行前置（硬要求）**：本机必须显式设置 `GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'`，否则 Gradle 往沙箱外的用户目录写缓存、被拒后直接失败。`docs/AGENTS.md` 等旧文档给的 `--project-cache-dir .gradle_alt` **不足以**解决（它只改 project cache，不改 Gradle user home）。【仓】
- **Gradle 锁坑**：若后端 `bootRun`（8080）正在运行，直接 `gradlew` 会因 `fileHashes.lock` 失败 —— 统一加 `--no-daemon`（必要时先停后端）。
- **bash 要用 Git 自带的**：`D:\backend\Git\bin\bash.exe` 可用（`Get-Command bash` 会解析到 `WindowsApps\bash.exe` 存根，报 `E_ACCESSDENIED`）。**受限沙箱下 Cygwin 起不来**（`couldn't create signal pipe, Win32 error 5`）。`python` 用 `D:\agent\python\python.exe`。⚠️ **中文 Windows 上跑含 `↔` 等非 GBK 字符的 python 脚本前先设 `$env:PYTHONIOENCODING='utf-8'`**，否则 `print` 抛 `UnicodeEncodeError`（表现为"脚本没问题却 exit=1"）。**别把脚本路径写进自定义函数的 `$args`** —— `$args` 是自动变量，会导致 python 无参启动、进 REPL 后 exit 0（看着像通过）。【仓 2026-09-14 实测】

```powershell
# —— 后端集成测试（本机唯一可跑通的入口）——
$env:GRADLE_USER_HOME='D:\backend\project\AquaFlow\.gradlehome'
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat test --no-daemon --project-cache-dir .gradle_eval
```

- **断言看响应体 `code`，不看 HTTP 状态**（业务错误仍 200；未认证才是 401）。新增用例：继承 `AbstractIntegrationTest`，用 `createStation/createStaff/createCustomer/createProduct/createOrderFull/createOrderCrossStation` 造数，`customerToken(id)` / `staffToken(id, role, stationId)` 取令牌，`get/post/put/delete` 发请求，`intOf/decimalOf` 直接查库。并发用例参考 `ConcurrencyIntegrationTest.fireTogether`。【仓】
- **统计测试件数**：把所有 XML 相加，**不要用 `Get-Content -Raw` 再转 `[xml]`** —— 中文 Windows 上它按 ANSI 解码会弄坏测试名里的中文、解析直接失败（且**静默少算**）；改用 `$d = New-Object System.Xml.XmlDocument; $d.Load($path)`。【仓 2026-09-16】
- **验证 CI 会不会绿，就在本机复现 CI 两步**：① `DROP DATABASE aquaflow_test; CREATE DATABASE aquaflow_test;` 后用**字节级重定向**导入 `sql/schema.sql`；② `.\gradlew.bat cleanTest test`（必须 `cleanTest`，否则 Gradle 报 `:test UP-TO-DATE` 而**根本没跑**）。【仓】
- **按业务场景组织的覆盖地图见 `docs/audit/2026-09-16-场景测试矩阵.md`** —— 新增用例前先看它找空白。

## 6. 代码约定与风格

- 后端分层：`Controller` 只做认证 + DTO 校验 + 调服务 + 返回 `Result<T>`；**禁止 Controller 直接写 `orders` / `payment_record` / 库存 / 桶资产表**。订单状态与副作用只能经 `OrderWorkflowServiceImpl` 这类编排服务完成。
- **所有状态改写必须 CAS 并检查受影响行数**（`updateStatusIf` / `updatePaymentStatusIf`）；无 expected-state 的 `updateStatus` / `updatePaymentStatus` 属于待清除的旧路径。
- 业务前置不满足一律抛 `BusinessException`（→ `code=1`），**不要用 `RuntimeException`**（会被兜成 500）。**不要在被 `@Transactional` 注解的方法内 catch 业务异常** —— 会抛 `UnexpectedRollbackException`，把正常业务拒绝伪装成 500。
- 权限：`@RequireRole({"STATION_MANAGER"})` + `@RequireStation`，由 AOP 切面 `execution(public * controller..*.*(..))` 统一保护，新增方法自动生效。**跨站校验一律以 `AuthContext` 中服务端刷新的 `stationId` 为准，不信任请求参数**；客户 ID 必须由登录态覆盖或与订单所有者严格比对。
- MyBatis：注解 SQL 用下划线列名（配 `map-underscore-to-camel-case: true`）；**注解 SQL 无编译期校验，新写必须手工在真实 MySQL 上跑过**。
- 金额、客户、订单归属、水票数量一律服务端推导或强校验；客户端传的金额不可信。展示文案（`statusText` / `payMethodText` / `payStateText`）由后端下发，**前端禁止自带 1/2/3 映射表**（历史上两端各写一套，导致新客下单 100% 失败）。
- 写库顺序：先 `getByClientToken` 判断幂等再动手；Controller 调 service 后再写库必须 `@Transactional`。
- 日志禁止记录密码、JWT、微信授权码、完整手机号/地址、任何密钥。**回复中也不回显密钥**（用 `<redacted>`）。
- 术语统一：**配送中**（= 已付款买下桶权益但未送到，旧称「在途」**已禁用**）、**进行中**（= 待配送 1 + 配送中 2）。表名 `customer_barrel_in_transit` / 类名 `CustomerBarrelInTransit` 仅为兼容历史命名保留，注释与文案一律写「配送中」。`customer_owed_barrel` 已停止写入，欠桶改读 `customer_barrel_over`。
- 小程序：`wxml` 内禁止调用 Page 方法 / `Math.` / `Date.`；`wxml` 绑定的事件处理函数必须真实存在，否则点击**静默无反应**；注意 `require` 相对层级；后端 `/api/delivery/orders/{id}/xxx` 用模板串拼接。

### 6.1 注释契约（2026-09-14 立规，强制）

本仓库的注释**不是可选项** —— 它同时是下一个 AI 的**操作依据**。

1. **改代码必须同步改注释**。注释与代码不符**比没有注释更危险**：没有注释时人会去读代码，有错误注释时人会直接照做（本仓库已有三次真实事故源于悬空/过期注释，见 §8.14）。**作废的 javadoc 必须删除，不能悬空留着占位。**
2. **分工：流程 / 规则写文档，代码注释只写「改这里会踩什么坑」。** 业务规则、领域模型、状态机、资金口径已写在 `docs/design/` 里，**代码注释不要复述它们** —— 复述必然与文档不同步，最后两边都不可信。
   - **该写注释**：反直觉的约束（"金额方向由类型决定，调用方一律传正数"）、历史事故（"曾因此丢钱"）、并发与加锁顺序、唯一键 / 幂等陷阱、"本类不是桶账写入口"这类边界声明、"别把它加回来"的护栏。
   - **该写文档**：业务流程、状态流转规则、字段口径、使用说明、界面交互。
   - **都不写**：`getXxx` / `setXxx`、直白的循环与判空。
   - 代码里确需提业务规则时，**用一行指向文档**（如"见 `docs/design/04-订单与状态机.md`"），不要就地展开。
   - **正面样本**：`constant/PayMethod.java`（记录"前端曾把 2/3 写反导致下单必失败"）、`constant/AdjustType.java`（"方向由类型决定"）、`BarrelLedgerService`（加锁顺序防死锁）。**反面样本**：把整段业务背景抄进 Controller（那属于设计文档）。
3. **修完缺陷就地留评论**：在**出问题的源头**（而不是只写在测试里）注明「原来是什么 / 为什么错 / 后果是什么 / 正确做法」，并标日期。下次有人要重构或回退时，这段注释就是护栏。
4. **新增端点必须写明归属与调用方**：员工端点标 `@RequireRole`；顾客自助端点必须写明身份取自 `AuthContext`（见 `aspect/RequireRoleAspect.java` 的「新增端点强制约定」）。小程序侧要注明「这个接口顾客端能不能调」。
5. **参数必填性、枚举取值、接口路径、表结构**这四类说明最容易过期，一旦改动必须当场同步。
6. 新增类 / 公开方法 / 非直觉分支要补 javadoc；**纯 getter/setter、显而易见的循环不补** —— 注释的价值是"降低误用概率"，不是覆盖率。

## 7. 协作注意：不要动 / 属于生成物

- **动手前先 `git status` 核对；不要顺手混入与本任务无关的改动，也不要替用户提交。** 工作区长期存在大量未提交改动（含 AI 工具产物与历史收尾），review 后再决定提交。
- 未获明确要求**不要 `git commit` / `git push`**；改动留在工作区供 review。当前分支 `master`。**提交数不要硬编码**（以 `git rev-list --count master` 为准 —— 有正本就不要重述）。
- **提交粒度与信息**：一次提交只做一件事；message 写「改了什么 + 为什么」，**不写过程叙述**，**不记录工具、环境或个人账号变动**。判据：这条 message 对三个月后排查问题的人有用吗？
- **提交规范 hook 已入库但默认未启用**（`.githooks/`）：`commit-msg` 强制 `<type>(<scope>): <subject>`；`pre-commit` 在暂存文件数 > 30 时拒绝提交。启用：`git config core.hooksPath .githooks`。⚠️ 受限沙箱下 Git 自带 `sh.exe` 起不来，在该环境启用会让每次 commit 失败，故未默认开启。
- **不要改 `archive/**`**（`legacy-web-frontend`、`miniapp-station` 均为历史留档，`miniapp-station` 缺 `app.js`、页面残缺，不再维护）。**不要引用 `miniapp-station`**。
- **生成物 / 勿手改**：`AquaFlow-backend/build/`、`out/`、`.gradle*`、`*.log`、`backup/`、`generated-images/`、`docs/design/*.html`、`tabbar-icons-preview.png`、`project.private.config.json`。另 `.gradlehome/`、`.gradle_alt2/`、`.gradle-user`、`.gradle_alt3`、`.dsh-code-index/`、`.openvisio/` 都是工具缓存，属临时产物。
- **`.gitattributes` 已加入**（`* text=auto` + `*.sh/*.py/*.yml/*.sql eol=lf`）：blob 一律存 LF，防止 Windows 检出 CRLF 后 `bash scripts/*.sh` 在 CI（Linux）上因 `\r` 失败。
- **根目录的 `*.py` 分两类**：**4 个已入库**（`audit_wxml_handlers.py`、`page_reach_audit.py`、`static_audit_user.py`、`audit_comments.py` —— 它们是 CI 与 `scripts/verify.sh` 的静态扫描门禁，`.gitignore` 对 `*.py` 开了 `!` 例外，**不入库则新克隆的 CI 必然失败**；`page_reach_audit.py` / `static_audit_user.py` 需传端名；`page_reach_audit.py` 已同时识别 js 的 `url:` 与 wxml 的 `data-url="/pages/..."` —— 看到它报的"孤岛"名单时**先确认跳转写法**）；另若干（`e2e_user_test.py`、`gen_tabbar_icons.py`、`api_reverse_audit.py`）属调试/审计残留，不可作为项目入口或规范依据。
- **`api_reverse_audit.py` 有两个已知偏差，看它的输出前先记住**：① 它按**剥离注释后**的源码计行号，报出的行号**系统性偏早**（实测 `/api/payments/all` 报 `:182`、实际 `:198`）—— 引用前回原文件核对；② 死端点判定**只看路径、不看 HTTP 方法**，所以"同路径不同方法"的端点**不会被报出来**，**它报的数字是下界而非全量**。要动"删除端点"这类决定时，必须自己重新证一遍零引用（见 §0.3）。

## 8. 已知坑与历史教训

> **每条只留「判据」；发现经过、误判原因与排错时间线在 `docs/audit/2026-09-17-AGENTS-瘦身前全文存档.md`。**

1. **接口报错但 HTTP 200** —— 只判断 HTTP 状态码会把业务失败当成功。**一律判断 body `code`。**
2. **MySQL REPEATABLE READ 下的并发桶账** —— 只加行锁不够：第二个事务拿到锁后，普通 `SELECT` 读到的**仍是旧快照**（实测两个请求都读到 `overBefore=0` 双双通过）。**并发写路径必须「加锁 + 当前读 `FOR UPDATE`」两件套**；`INSERT ... ON DUPLICATE KEY UPDATE` 必须 upsert（`SELECT FOR UPDATE` 对不存在的行不加锁）。
3. **取消订单的"钱货分家"** —— 跨站外派单取消时，钱/票记**归属站**、库存回补记**履约站**（历史上 `refundOrder` 混用过，真丢钱）。`deposit_record.related_order_id` 必须落库，否则按订单反查押金释放记录查不到。**是否需要释放押金要锚定「有没有入账凭据（PREPARE 流水）」，不能只看 `orders.deposit_amount`**（那是应收，未付款单也有值）。
4. **水票是唯一「下单即视同已付」的支付方式** —— 它绕过 `confirmPayment`，所以押金入账必须在 `TicketAccountServiceImpl` 那条路径自己补，否则客户用票付了押金、账户是 0，退桶退不出钱。
5. **`AbstractIntegrationTest.resetDatabase()` 会 TRUNCATE 全表** —— 靠「断言库名含 `test`」做最后护栏；**改测试数据源前务必先看这条护栏**。
6. **Spring Boot 4.0.6 已移除 `TestRestTemplate`** —— 测试用 JDK `HttpClient` + `@Value("${local.server.port}")`。
7. **Jackson 2/3 并存** —— Web 层是 Jackson 3（对 Jackson 3 设置 `WRITE_DATES_AS_TIMESTAMPS` 会启动失败）；手工 `new ObjectMapper()` 拿到的是 Jackson 2。时间统一 ISO-8601 字符串，前端一律 `new Date(str)`，**禁止 `.replace(/-/g,'/')`**。
8. **`station.offline_payment_enabled` 已从真实库删除（2026-09-12）** —— 货到付款唯一控制点是 `customer_station_config.offline_payment_enabled`（客户级，站长逐个开通）。该漂移已消除（`schema.sql` 无此列、主代码 0 引用），只在历史脚本与 `backup/*.sql` 里留存，属预期。
9. **`DEV_LOGIN_ENABLED` 默认关闭，生产必须 `false`** —— 但 `miniapp-delivery` 登录页目前**无条件渲染「开发者登录」按钮**，不检查 `__wxConfig.envVersion`。
10. **两端 `config/api.js` 的 `prod.baseUrl` 是占位域名** —— 正式发版前必须替换。
11. **文档漂移实例（不要照着做）** —— 根 `README.md` 仍写 `AquaFlow-frontend`、`seed_full_data.sql`、订单状态 `1/3/4/5/6`、支付方式 `1微信/2水票/3线下`、`station_payment_config` 表、`ManagerOrderController`（该文件已不存在）；`docs/AGENTS.md` 的「订单流转」小节把接单后状态写成 `status=3`，与 `OrderStatus.DELIVERING=2` 矛盾 —— **以 `OrderStatus.java` 为准**。
12. **「测试库全绿 ≠ 真实库可用」** —— `aquaflow_test` 由 `schema.sql` 建库、结构永远等于基线；而真实库是历史累积的，**索引可能停留在旧形态**。曾实测：真实库带唯一键 `uk_payment_order_status`、且 `uk_ticket_consume` 未纳入 `source` → 退款/回补流水在真实库上**必然报 1062 失败**，而 78 个用例全绿、毫无察觉（当时 v23 从未在真实库执行）。**判据（长期有效）：涉及唯一键 / 索引的改动，必须到真实库核对 `information_schema.STATISTICS`，并优先用「事务内造数据 → 观察是否成功 → ROLLBACK」的行为法验证。**（该漂移 2026-09-14 晚已核实消除）
13. **真实库与基线已完全对齐（2026-09-16 实测）** —— `aquaflow` = `schema.sql` 的 39 张表，0 视图 / 0 备份表 / 0 迁移残留，两个方向差额均为 0。**仍然不要照真实库反向改 `schema.sql`** —— 正确做法是先判定哪边对，再把两边同时改齐。
14. **注释会诱导误用（三次事故的共同诱因）** —— `StationController./mine` 上方长期残留一段**悬空 javadoc**「当前登录**客户**选择的服务水站」，而该方法实际是**员工**接口（`@RequireRole({"STATION_MANAGER","DELIVERY"})`）；顾客端据此三次误调同一类接口（模板页 `stationId` 恒 null、下单页"再来一单"跨站校验沦为死分支、更早的取水站电话）。⚠️ **它不是真 403**：`RequireRoleAspect` 抛 `BusinessException`，被 `GlobalExceptionHandler` 兜成 **HTTP 200 + `code=1`**（只有「未认证」才是真 401）—— 用 HTTP 状态码判断会漏掉这类拒绝。**判据：注释是被信任的契约 —— 悬空/过期注释必须删、改代码必须同步改注释**（见 §6.1）。定位手法：搜「`*/` 后紧接 `/**`」（已做成门禁脚本 `audit_comments.py`，接入了 CI 与 `scripts/verify.sh`）。
15. **「请求体从裸 `Map` 收敛成强类型 DTO」会静默丢字段** —— `DeliveryOrderActionDTO.Complete` 漏抄 `collected` 与 `note`，而 service 一直在读、配送端一直在发；**Jackson 对未知字段静默忽略，不报错、不进日志** → 配送员点「已收款」一律被当「未收款」处理：订单停在已送达(3)、`payment_status` 从待收款(1) 被改写成未付(0)、**钱不入账也没有 PAID 流水**、跨站收款护栏成死代码，配送备注也永远写不进 `orders.special_note`。**判据：改强类型 DTO 必须逐字段核对老 Map 的键名与前端实际发送体（grep 前端调用点），并补一条走 HTTP 的用例** —— 直接调 service 的用例发现不了（Map 是自己拼的）。用例 `DeliveryCompleteIntegrationTest`。
16. **「只遍历 `customer_barrel_asset`」是本仓库的惯犯（第 4 次）** —— 详见 §1 的并集不变式。**占用口径在 `getBarrelSummary` 与 `getBarrelSummaryByType` 之间必须永远相等**。用例 `occupiedCountsOwedBarrelsEvenWithoutRights`。
17. **「要求了补偿却没执行」也必须失败，不能标成已执行** —— 异常单补偿 `executeCompensation` 的退水票分支原本在 `adjustProductId` 为空时只 `log.warn`，然后照样把异常推到 `EXECUTED`：站长界面显示"已处理/已补偿"，客户水票账户一张都没多（与 §8.15 同属"静默成功"陷阱）。现改抛 `BusinessException`：事务整体回滚、异常退回 `STAFF_RECORDED` 可重试。**判据：任何"用户以为做成了、账上没动"的分支都算缺陷，宁可失败出声。** 用例 `BarrelExceptionFlowIntegrationTest.refundTicketsRequiresProductThenCreditsAccount`。
18. **订单状态不许倒滚** —— `PaymentService.unconfirmOrderCollection` 把 已完成(4) 改回 已送达(3) 且**不动 `payment_status`**，回滚后停在「已送达 + 已付款(2)」这种自相矛盾的组合上；它当时全仓零调用点，已删除。**护栏**：`PaymentFlowIntegrationTest.noUnconfirmCollectionEndpoint` 断言 4 条可能的路径全部 404 —— 谁把这类端点加回来就红。**通用规则：状态只前进；需要表达异常态就新设一个状态，不要复用/回退已有的。**
19. **时间区间上界写成 `<= 当天` 会漏掉一整天** —— `endDate` 是 `LocalDate`（今天）时，SQL 里等价于 `<= 今天 00:00:00`，于是**今天新增的记录一条都统计不到**：站长看板永远停在昨天、刚发生的问题显示"无异常"（排查时最容易被误读成"没事"）。**判据：凡是按「某天（含）」筛选 datetime 列，一律用 `>= 起始 AND < 结束+1天`，不要用 `<= 结束日`。** 用例 `ExceptionStatsIntegrationTest.statsIncludesToday`。
20. **「按 id 操作记录」的接口必须逐条验证归属，且必须检查受影响行数** —— 同一天在地址簿与常用模板抓到三处：`OrderTemplateServiceImpl.save` 传 `{id: 别人的模板id}` 会**覆盖别人的模板并连带清空别人的模板明细**（[AQ-036] 当时修了 setDefault / toggleEnabled / delete 三处，**漏了这一处**）；`AddressServiceImpl.delete` 的 SQL 带了 `and customer_id=?`（没有越权删除，这点是对的），但 service **不看返回值**、控制器又无条件 `Result.success()` → 删别人的地址时客户端收到"成功"、前端把它移出列表、刷新后又冒出来；`AddressServiceImpl.setDefault` 先 `clearDefault(自己)` 再 `setDefault(任意id)` 不校验归属 → 传别人的地址 id 会**清掉自己的默认并把别人的那条改成默认**。**判据：接口收一个客户端可编造的 id，就必须回答"这条记录属于调用者吗"；拿不到行数就别返回 success。** 用例 `AddressAndOrderTemplateIntegrationTest`。
21. **对象存储未配置（或不可用）不得升级成系统异常** —— `COS_SECRET_ID/KEY` 留空时上传/删除会抛运行时异常，一路冒到 `GlobalExceptionHandler` → **HTTP 200 + `code=500`**（还会顺带触发一条 SYSTEM 告警），且 DB 行仍在、连废记录都清不掉。**判据：可预期的运维状态给业务错误（`code=1` + 可读文案）并把异常记进 ERROR 日志，删除路径可降级为 WARN 后继续清 DB 记录；凡是"外部依赖没配/挂了"的分支都不该表现为 500。** 用例 `FileUploadIntegrationTest`（判据看 body 的 `code`，不是 HTTP 状态）。
22. **「零覆盖端点」的真实形态是「界面空白」而不是报错** —— 这类端点的 mapper JOIN 写错时**不会抛异常，只是返回空列表**，界面上表现为"今天没有单"，与"确实没有单"无法区分。已为下列家族补了契约级用例（**结论：这批接口本身没有功能性缺陷**，但抓到了 §8.21 与 §1 里 `updateStatusIf` 参数写反那批）：`DashboardNoticeSearchFeedbackIntegrationTest`、`InventoryStaffProductIntegrationTest`、`TicketDepositNotificationIntegrationTest`、`DeliveryConsoleAndSelfServiceIntegrationTest`、`DirectedReturnAndReturnToStationIntegrationTest`、`FileUploadIntegrationTest`（覆盖站长看板 5 个 / 公告 CRUD / 综合搜索 / 意见反馈 / 库存与入库流水 / 员工增删改与画像 / 商品目录与站长商品管家 / 水票押金流水 / 客户通知 / 企业资料 / 定向退回三连 / 登录态自助 / 桶流水与退桶预检 / 支付退款）。
23. **死端点评估（只读报告 `docs/audit/2026-09-16-死端点评估.md`）—— 两个必须先处理的发现**：
    - **`GET /api/files` 系列存在跨站可见（未修，仅登记）**：`FileInfoMapper.listAll()` / `listByCategory` **没有任何水站过滤**，而 `file_info` 表**也没有 `station_id` 列** → 任何站长 token 都能列出**全部水站**的文件名与可用临时 URL。**接线前必须先做站隔离（加列 + 回填 + 过滤），否则就整族删掉** —— 不要"先接界面后补隔离"。
    - **`GET /api/delivery/orders/station-exception` 名不副实**：它过滤的是 `status = 5（已取消）`，一个"异常"标签返回的是**取消单**，且与 `GET /api/orders?status=5` 重复。谁按名字接它进界面，站长看到的就会是取消单列表。
    - 评估结论（39 条）：建议删除 8 / 建议接线 19 / 建议保留 12。**任何删除都还没做，等产品点头**；删除候选每条都挂着至少一条契约断言或文档表格行，所以"删代码"必须连带改测试与文档。
24. **写入口冗余（评估副产品，未动代码）** —— 押金有两条写路径：`POST /api/deposit-records`（接受全部白名单类型）与站长资产调整单 `DEPOSIT_GRANT/DEDUCT`（后者最终仍调同一个 `depositRecordService.add`）；水票同理，`addTicket` **没有** `adjustment_id` 幂等键（对比 `adjustTicket`，由 `uk_ticket_adjustment` 兜底）。`docs/design/10-站长资产调整单.md:66` 还把前者称作"押金调整的正确入口"。**动这两处前先想清楚哪条是正门。**
25. **`PUT /api/payments/{id}/cash-confirm` 与 `PUT /{id}/confirm` 实现逐字相同**（都只调 `paymentService.confirmPayment`），但已进验收文档（IT-PAY-002 / IT-CNF-002）→ **保留、登记，不要合并**（合并会动验收口径）。

## 9. 待确认 / 未验证清单

> 完整的 12 条（含 7 条已结案的结论）在 skill **`aquaflow-open-questions`** 里，**执行相关操作前先加载它**。这里只留仍在生效的判据。

以下条目**未取得确证，执行前必须自行核实**：

1. **「水厂端已彻底移除」是 2026-09-11 的复核结论**，此后未重新全库检索 `factory` 残留。
2. **已弃用表的实际停写状态未逐一复核调用链**：`customer_owed_barrel`（已停止写入）与 `customer_barrel_in_transit` 的写入点。
3. **`ManagerOrderController` 确已删除**（文件不存在，有 `ManagerOrderControllerRemovedIntegrationTest`），但 `.workbuddy/memory/MEMORY.md` 仍把它列为「仍未做」的高危项 —— **该记忆条目已过期，不代表当前存在该风险**，但也不排除有其他等效写入口。
4. **开发者工具「测试号」是否支持 `wx.login` / `jscode2session`，尚未实测**：官方对测试号只承诺「开发测试 + 真机预览」，**没有明文承诺登录能力**。当前本地只有客户端用测试号；真机「微信一键登录」能否跑通要实测（把两对 appid/secret 填进配置后，真机点登录，看后端日志里 `微信code2Session响应[CUSTOMER]` / `[STAFF]` 的返回）。**测试号确定不能上传代码 / 发布 / 设为体验版**；若不支持登录，`dev-login` 是唯一可用登录路径。
5. **测试号分「小程序」与「小游戏」两种，不可混用**：申请页各给一个，把**小游戏**测试号的 appid 填进小程序项目（`compileType: "miniprogram"`）会**编译失败**（配送端曾误填小游戏测试号，已改回原 appid）。**填之前先确认拿到的是「小程序测试号」。**

## 10. 本文件的来源与维护

- 原则：**能验证才写，不能验证就放进 §9**。任何一条若与本仓库当前代码冲突，**以代码为准**，并回来改本文件。
- 维护建议：做大改动/重构后顺手核对 §1 的常量清单（`OrderStatus` / `PayMethod` / `PaymentStatus` / `DepositType` / `BarrelRecordType`）与 `sql/README.md` 的迁移清单；**新增枚举或迁移后必须同步 §1**（枚举值正本在 `constant/*.java`，本文件只引用、不重定义）；§9 的条目被证实后应上移进正文并删除。
- **体积约束：本文件必须留在 agent 的指令预算（65536 字节）以内，并保住 ≥10 KB 余量。** 实测：瘦身前 68338 字节（**已超预算 2802 字节、尾部被截断**）→ 2026-09-17 瘦身后 **约 51 KB**（-25%，余量 ≈14 KB）。⚠️ **不要指望再压很多**：这次压缩后剩下的几乎全是"判据本身"（行号、类名、确切条件），再压就是丢判据；想加内容时先把叙事写进 `docs/audit/` 或 skill，正文只加判据。**不要手写会漂移的计数**（文件数、Controller 数、页面数、测试件数）—— 那些交给 §11 的工具。
  - ⚠️ **量这个文件的体积/行数要用 node 或 `read` 工具，不要用 PowerShell `Get-Content`** —— 实测 `Get-Content` 会把它读成 109 行（真实 263 行），据此做章节体检会得出完全错误的结论（2026-09-17 瘦身时就先被它骗过一次，误以为存在"一行 20968 字节"的巨型行）。
- 已知待决策项：`docs/AGENTS.md` 是否重命名为 `docs/DOMAIN.md` 以免与根 `AGENTS.md` 撞名；工作区那批未提交改动是否先 review 后按语义拆成数个提交（见 §7）。

## 11. 去哪找（工具与按需加载的 skill）

**代码结构别问文档，问工具**（DSH 装了 `dsh-code-index`，走会话 cwd 找最近的 `.git`，索引缓存在 `.dsh-code-index/`）：

| 想知道 | 用什么 |
|---|---|
| 仓库全貌、最核心/最重的文件 | `code_map` |
| "名字含 X 的符号在哪" | `code_search` / `code_symbols`（带 `file:line`） |
| "谁调用了 `BarrelLedgerService.checkout`" | `code_refs`（callers / callees） |
| 环依赖 / 孤儿模块 | `code_health`（需插件配置开 `codeHealth: true`） |
| 人能点的结构图（Atlas / City 3D） | `openvisio view . --no-open` → <http://127.0.0.1:7077>；重建 `openvisio index .` |

**按需加载的领域 skill**（放在 `.dsh/skills/`，会话 cwd 在仓库内时自动出现在技能目录）：

| skill | 什么时候加载 |
|---|---|
| `aquaflow-open-questions` | 要动已弃用表 / 水厂端残留 / 微信测试号登录 / 判断 `.workbuddy` 记忆是否过期时 |

> ⚠️ skill 名（frontmatter 的 `name`）必须是 **ASCII 小写 + 连字符**（DSH 的校验是 `^[a-z0-9]+(?:-[a-z0-9]+)*$`）——
> 写成中文名**不会报错，只会被静默忽略**（仅留一条 log warning），然后你会以为它加载了。

> 其余领域知识仍在 `docs/design/**`（设计文档）与 `docs/audit/**`（事故与审计报告）里 —— 本文件只放"动手前必须知道的判据"，不放流程说明。
