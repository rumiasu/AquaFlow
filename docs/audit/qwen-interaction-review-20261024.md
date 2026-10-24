# 2026-10-24 Qwen交互报告复核与有限修复

核查基线：开发分支 `codex/fix-fee-drawer-layout-20261018` 的 `a7c6d105647e70b962e23ed255aacf93af07a517`。本记录仅覆盖用户交回的回桶、收款及补充交互主张，不重新评价全站，不替代领域规格。证据行号固定在核查基线，修复后以方法名定位。

## 只读结论快照

没有证实“桶账丢失或重复收款入账”的P0；回桶历史错筛、今日统计日期漂移、未知写结果恢复及资金登记确认存在实际P1。下列结论在实施修复前保存，后续处置另列，不改写初核事实。

| 编号 | 主张与判定 | 级别 | 基线证据与最小建议 |
|---|---|---|---|
| QW01 | 回桶历史筛配送中，漏掉正常送达：属实；“永远为空/恒0”过度概括 | P1 | `AquaFlow-backend/src/main/java/com/example/aquaflow/mapper/OrderMapper.java:637` 筛2；`AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/OrderWorkflowServiceImpl.java:694、728` 同事务转3/4并写实际回桶。改历史为3/4，保留零回桶。 |
| QW02 | 今日回桶使用状态4及可变更新时间：属实 | P1 | `AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/DeliveryConsoleServiceImpl.java:169`、`AquaFlow-backend/src/main/java/com/example/aquaflow/mapper/OrderMapper.java:629`。状态3漏计，补收款/改备注可改变统计日期。使用已有可信送达事件时间，缺失日期明确未核实。 |
| QW03 | detail没有防重锁：误报；未知结果后解除锁且不核实：属实 | P1 | `miniapp-delivery/pages/order/detail.js:263、301、313`。已有在途及身份保护，仍不消费 `requestMayHaveOccurred`。未知结果不得作为确定失败自动放行重发。 |
| QW04 | home收款无订单级在途锁，确认缺金额及后果：属实 | P1 | `miniapp-delivery/pages/home/index.js:376`、`pages/order/detail.js:545`。卡片金额不是最终确认。可信金额缺失应阻止确认，不能默认为0。 |
| QW05 | 所有写动作重试都会重复入账：误报；退回站长可重复待审批：属实 | P1 | `AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/OrderWorkflowServiceImpl.java:387、420、430` 和 `AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/PaymentServiceImpl.java:529` 防收款重入；但 `AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/OrderWorkflowServiceImpl.java:1599、1617、1625` 重放退回可重复插入。应在既有订单锁内核对待审批申请。 |
| QW06 | 应收登记缺提交前所选金额确认：属实；直接调用外部支付：误报 | P1 | `miniapp-delivery/pages/station-mgmt/receivables/index.js:241、249` 直接POST。后端执行线下已收登记与销账，仍需操作员核实真实到账。增加客户、所选订单、可信合计及后果确认。 |
| QW07 | 现金待收款隐藏导航：属实 | P2 | `miniapp-delivery/pages/order/detail.wxml:215、218` 导航与收款互斥，现金配送中也成立。两入口应并存。 |
| QW08 | 未登录登录/重载共显：属实；template固定按钮同位 | P2 | `miniapp-user/pages/template/index.js:58`、WXML`:11、12、51`；`pages/barrel/index.js:118`、WXML`:11、14`。只保留登录行动，新建按已登录显隐。实际遮挡仍属渲染推断。 |
| QW09 | 下单必经三次确认：误报，条件叠加时可出现三次 | P2 | `miniapp-user/pages/order/create.js:1230、1247、1783` 分别按风险、新增押金、非空warnings触发；普通订单不是必经三次。保留必要资金授权。 |
| QW10 | 三个押金名称都是同义词：部分误报 | P2 | `miniapp-user/pages/order/create.wxml:167、249`、JS`:1265` 同笔钱可统一“本次新增桶押金”；“用桶权益”是容量份数，不等于金额。 |
| QW11 | 新模型折叠导致多还桶丢失：误报；发现性下降属实 | P2 | `miniapp-user/pages/barrel/index.wxml:63、91–94` 仍能展开“暂存或待领取”；`AquaFlow-backend/src/main/java/com/example/aquaflow/service/BarrelLedgerService.java:113` 买权益也令over为负，不能全称多还实物。 |
| QW12 | 只有2个hover所以普遍无反馈：推论不成立 | P2 | 剥注释bindtap833，其中331为原生button；有共享按压规则、loading与写入锁。`miniapp-user/styles/theme.wxss:15、31、85`；`miniapp-delivery/pages/order/detail.wxml:218`。普通view局部缺反馈可延期抽验。 |
| QW13 | 蓝色少一位、都违反旧36：误报；局部色值漂移属实 | P2 | `docs/design/36-两端呈现层收口-功能色语义与视觉规则.md:3` 以37为准；37指定`#1264D6`。D1/D6都是合法6位色值，尚无通用色值白名单lint。不全局换皮。 |
| QW14 | 订单/桶空态无行动：部分属实 | P2 | `miniapp-user/pages/order/list.wxml:36` 无就地订水；桶WXML`:53` 已有新模型押金入口。按上下文补行动可延期。 |
| QW15 | shop/template均无安全区：部分误报 | P2 | shop WXSS`:430、434` 已处理；template容器/弹层已处理，仅固定按钮WXSS`:156` 未加safe偏移。本轮仅顺手补固定按钮。 |
| QW16 | template用“件”导致数量/计费错：后半无证据 | P2 | `miniapp-user/pages/template/index.wxml:35` 固定件，但未换算quantity。应后续补可信商品单位，不按名称猜。 |
| QW17 | home待收款联系不可达：误报；卡片少拨号属实 | P2 | `miniapp-delivery/pages/home/index.wxml:239` 无拨号，卡片仍可进详情，详情WXML`:37` 有联系入口。 |
| QW18 | 台账缺3/5筛选：属实；角标冒充全量：误报 | P2 | `miniapp-delivery/pages/station-mgmt/orders/index.js:37、105` 无3/5筛选，但“全部”仍含；WXML`:16、23` 已说明已加载范围。 |
| QW19 | status-tag重复覆盖导致消失：未证实 | P2 | `miniapp-delivery/pages/station-mgmt/orders/index.wxss:9、66` 与顾客OrderCard`:34、203` 仅局部尺寸级联，语义色保留；员工状态3缺专属底色可延期。 |
| WB08 | 普通反馈未知结果后重复建异常单：沿用既有问题 | P1 | `AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/OrderBarrelExceptionServiceImpl.java:228` 每次插入；不当新发现重复计数，不以收款幂等代替其验证。 |

回桶字段不只在送达结果更新：下单 `OrderServiceImpl.setReturnBucketQty` 和XML会保存传入值。异常 `recordReturn` 也写字段，但当前生产调用仅在同一送达事务内，并非独立配送中实际回桶入口。实际桶账由 `BarrelLedgerService.applyDelivery` 和type8配送收发流水持久化；错误读取不能证明账务写入丢失。

回桶历史按服务端本人staffId读取，角色守卫及跨站客户画像脱敏存在；没有日期限制、分页或当前站过滤。没有据此认定越权。若以后分页或改历史站范围，必须显示范围，不可静默截断并称全部。

初核探针：隔离H2执行实际Mapper及生产服务的17条断言，前端2组处理器反例；相关既有前端回归154项通过。状态2预填5被错误列出，3/4的6/7被排除；今日先得7，改历史更新时间后变16。收款重放仅一条支付流水，但退回站长可有两条待审批。此处不是生产MySQL并发证明，也不是真机连点证据。

## 本轮处置与验证

实施范围为QW01–08及template固定按钮安全区，其他P2延期。最终已修/保留、正式回归、旧版负对照和上传边界在本节及同名JSON更新；未完成前不得将计划项称为已修。

初核记录提交 `c61ae8d`；后端与时间展示修复 `e7a4166`；收款与登录恢复修复 `feb0304`。这些提交保留在独立修复分支；最终快进同步结果以Git远端与任务回执为准，不覆盖原历史。

| 处置 | 最终行为 |
|---|---|
| QW01、QW02 已修 | 历史包含本人3/4及零回桶；只读`deliveryTime`来自最早COMPLETE事件、其次type8流水。两个来源预聚合JOIN，避免多商品重复订单；今日列表与统计共用上海业务日的半开区间，不使用`update_time`。无可信日期旧单仍在历史并显示待核实，`deliveryTimeUnverifiedCount`说明未计入今日的历史数。 |
| QW03 部分已修、QW04 已修 | home/detail确认可信金额及记账后果，缺金额阻止提交，不伪造0；首页按订单单飞。结果不明保存原订单记录，跨页面/离页/续期阻断重发，只GET核实。仍未付不代表未执行；已付、退款取消、已有相应申请等正向事实可恢复。存储丢失有内存在途/未知保护，保存失败则不发送。只读查询失败可见且可重试，不误建未知写。 |
| QW05 已修 | 订单行锁后当前读STAFF待审批申请；仅唯一且同配送员/同履约站的原RETURN_STATION幂等返回，其他或历史重复申请明确拒绝，不再追加备注和申请。收款原有后端去重守卫保留。 |
| QW06 已修 | 按整数分合计所选可信订单，确认客户、逐单、合计及“已实际收到”。身份/选中/金额/读取条件变化使旧确认失效。未知或损坏回执清勾选，仅刷新核实，读取失败保留阻断提示，不自动POST。 |
| QW07、QW08、QW15 已修 | 现金导航与收款并存；未登录只有登录行动，template无权时也隐藏并守卫新建；固定按钮及内容预留补安全区。 |
| WB08 保留 | 详情普通反馈未知结果已保守阻断，但后端普通反馈仍无原申请幂等凭据，首页普通反馈及拒付等无法可靠匹配的核实没有全面闭环。不得把收款安全或当前阻断称作WB08全部修复。 |
| 其他P2延期 | 确认文案去重、押金命名、桶明细摘要、自绘条目反馈、色值token、空态行动、模板单位、待收款拨号、台账3/5筛选及局部状态样式。本轮不全局换皮。 |

最终验证（仅列有效最终运行，重复执行不重复累计）：

- 41项相关后端单测通过，含8项新增服务回归；实际Mapper隔离H2检查9项通过。旧版历史SQL、日期SQL和退回服务三个行为负对照失败，分别是错误返回集、错误今日返回集、实际插入两条而非一条，不是编译失败代替反例。
- 新前端回归104项：时间展示5、收款核实47、应收/登录52；同一测试针对精确a7源码分别为0/5、7/47、1/52，96项修复检查按预期抓到旧行为。
- 关联前端回归225项：原详情73、首页身份17、应收事实25、页面失败恢复110。原生共享模块7项、弹窗按钮文案1项通过；185个JS文件解析失败0。
- 439个主Java源、221个测试Java源隔离编译通过；在现有MySQL/HTTP集成类追加两项正式回归并再次定向编译通过，另修正旧跨站画像测试里将配送中当回桶历史的断言。**这些MySQL/HTTP测试未执行**：当前没有可用的精确授权测试库目标，不启动会清表的上下文，不以H2代替MySQL事务或执行计划证明。

回归注册在`tests/js/run-all.js`；SQL探针源码为`tests/java/DeliveryEventSqlProbe.java`，只连接本JVM合成H2，不加载Spring或数据源配置。正式数据库回归使用既有`TestDatabaseTargetGuard`，后续只能在确认过的独立测试库运行。关键最终日志哈希见同名JSON，不依赖仓库外文件作为使用前提。

上传交接：**后端、员工小程序和顾客小程序都需重新上传本批版本**；无DB迁移、无新增配置。后端新增投影字段和修正列表语义与前端同批同步；新前端面对旧后端缺失送达日期时只提示待核实。普利思永久图片映射`9665f5f`祖先保留且未改，本轮不需要再改图片数据。本轮未生产部署，不能说线上已含本修复；013旧基线部署由原任务独立完成。

受拒本地D盘及Actions操作保持停止；不查业务库、不操作真实支付/退款、不部署。基线已含普利思永久图片映射，本轮不改图片逻辑、不干扰013旧基线部署。本记录不宣称72页原生视觉或真实渠道验收。
