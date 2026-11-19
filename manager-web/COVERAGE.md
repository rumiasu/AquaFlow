# 电脑站长端覆盖清单

除下段增量外，历史核对基准为 2026-11-13 本机 `codex/stage-handoff-20261004` 的 `829bdc30` 和当前未提交源码。入口来自 `miniapp-delivery/app.json`、`pages/station-mgmt/index.wxml`、协作、配送和我的页面。子包内下钻与状态按实际 JS、WXML、API 封装和控制器核对。接口以下省略共同前缀 `/api`。已有网页入口仍需按本表所列状态验收。

本次增量：代下单展示服务端本站有效价、订单展示已持久化的配送承诺、商品库存支持开启/修改/结束本站促销。营业时段的设置仍在员工小程序；本批不改变后台跨站、收款或退款规则。

| 小程序页面或入口 | 站长可查与可办 | 当前接口 | 电脑端覆盖 |
|---|---|---|---|
| `login`、`role-select`、`station-mgmt/create-station` | 微信登录、协议、选角色、建站、绑定、已绑定后进入工作台 | `/auth/wx-login-staff`、`/auth/select-role`、`/auth/create-station`、`/auth/me`、`/agreements/*` | 已绑定站长密码登录、刷新、退出。微信扫码、初次选角、建站、协议仍由小程序办理 |
| `station-mgmt/index` | 业务、收款、桶、认领等待办数量与下钻，未知状态提示 | `/manager/pending-summary`、`/manager/setup-guide` | 待办汇总与各业务独立入口已接。待办下钻与信息完善引导待接 |
| `station-mgmt/dashboard` | 今天、近七天、近三十天，订单、收入、回款、桶数快照、趋势与排行、按范围下钻 | `/dashboard/report` | 关键指标、未知桶数、服务端统计说明、每日订单及商品排行已接。其他排名、拆分与下钻联动待接 |
| `station-mgmt/orders`、`order/detail` | 本站、跨站订单筛选、搜索、订单及商品详情、付款状态、收货快照、进入办理 | `/orders`、`/delivery/orders/cross-station`、`/delivery/orders/{id}` | 本站状态/日期分页、跨站履约台账、授权详情与应回桶已接。全量筛选、详情内拒付/补偿/退款等动作待接 |
| `station-mgmt/place-order` | 客户、地址、商品、报价、可用支付方式、用票余款、新增押金、缺货确认、原单恢复 | `/manager/order-assist/customers`、`/manager/order-assist/customers/{id}/addresses`、`/manager/order-assist/quote`、`/products/sale-by-station`、`/orders/create` | 已接。只取后端金额；按原键核对未知结果及缺货确认 |
| `coordination` | 待分配、同站派单、客户取消、站内退回、抢单、外派、跨站风险确认、接收报价、追踪、召回和跨站退回 | `/delivery/orders/station-pending`、`/delivery/orders/assign/{id}`、`/delivery/orders/pending-approvals`、`/delivery/orders/pool`、`/delivery/orders/{id}/claim-pool[-quoted]`、`/delivery/orders/{id}/cross-station-risk`、`/delivery/orders/transfer/{id}/outsource`、`/delivery/orders/directed-incoming`、`/delivery/orders/assign-quoted/{id}`、`/delivery/orders/dispatch-tracking`、`/delivery/orders/directed-returns`、`/delivery/orders/{id}/directed-return/*` | 本站派单、外派与指定接收、取消/退回审批、召回、抢单及报价确认已接。改派、报价创建/修改和其它转让动作待接 |
| `home`、`order/complete`、`history`、`transfer`、`report` | 站长自送/代配送，接单、收款、逐品送达与实际回桶、缺货/拒收、转让、上报和历史 | `/delivery/orders/{id}/{accept,complete,confirm-offline-pay}`、`/delivery/orders/report/{id}`、`/delivery/orders/delivering`、`/delivery/orders/history`、`/delivery/transfers` | 普通本站单站长接单、本站配送中详情、实收情况和逐品回桶送达已接。复杂接单、配送员转让、个人历史、拒收及上报待接 |
| `station-mgmt/payments` | 待确认到账、游标翻页、订单、购票、独立押金、历史流水 | `/payments/pending`、`/payments/{id}/confirm`、`/payments`、`/payments/customer/{id}` | 待收、翻页、确认、最近流水已接。按客户下钻与历史筛选待接 |
| `station-mgmt/receivables` | 应收、逾期、明细、合资格现金实收登记、设置账期、重算与客户风险 | `/manager/receivables[/orders|/settle]`、`/manager/customers/{id}/credit-terms[/recalculate]`、`/manager/customers/{id}/risk` | 应收汇总、明细、订单下钻、合资格现金登记、账期维护与重算已接。风险深层明细待接 |
| `station-mgmt/customers`、`customers/detail` | 客户搜索、画像、资产、当前桶/票/押金、流水、纯还桶、货到付款、企业身份审核 | `/customers`、`/customers/{id}/{profile,assets,offline-payment}`、`/barrels/return-empty`、企业申请和配置接口 | 已接搜索、资产、流水、纯还桶、基本画像与货到付款配置。企业审核和画像的其它下钻待接 |
| `station-mgmt/owed-barrels` | 当前欠桶、全部/≥7 天、未知天数、按品类下钻、实收空桶 | `/manager/owed-barrels`、`/customers/{id}/assets`、`/barrels/return-empty` | 已接，历史差异与当前欠桶分别展示 |
| `customers/adjust`、`adjust/edit`、`adjust/detail` | 手工补录或订正，试算、待执行、生效、撤销与快照 | `/manager/adjustments`、`/manager/adjustments/{id}`、`/manager/adjustments/preview`、`/manager/adjustments/{id}/{execute,reverse}` | 已接列表、客户资格、试算、创建、执行和反向单。照片证据上传和客户详情联动待接 |
| `station-mgmt/asset-claims` | 原认领单、照片、逐商品桶权益/票、押金、核对通过入账或拒绝、原审批回查 | `/manager/asset-claims`、`/manager/asset-claims/{id}[/photo|/review]`、`/manager/catalog` | 已接列表、明细、照片链接、通过/拒绝与回查。真实 COS 凭据待联调 |
| `station-mgmt/barrel-return`、`barrel-records` | 待审批、待取桶/交接、原安排、新安排/补费、实收确认、原款实际退押金、待补登记 | `/manager/business-waiting/returns`、`/barrels/records/{id}/*`、`/manager/pending-summary/return-record/{id}`、原款退款与交接证据接口 | 已接申请查询、批准原安排、驳回、服务费实收、实际交接、引用配送凭据及经资格核对的实退现金登记。更改安排、线上原路退款、服务费退款与历史补登记待接 |
| `station-mgmt/exceptions`、`case-handling`、`my-problem-receipts` | 异常列表、原因、联系信息、历史与当前桶差、核实归还、已解决、误报/无需处理、拒付、处理记录；当前小程序已停用主动补偿入口 | `/manager/exceptions`、`/manager/exceptions/{id}/{resolution,resolve,write-off}`、`/barrels/return-empty` | 最新能力标志、版本、处理历史、逐项结果、回执、订单与桶账下钻及已有拒付异常的核实已接。新建拒付、手工异常与个人回执待接 |
| `station-mgmt/customer-feedback`、`case-handling` | 匿名反馈、回复、回复并结案、退款异议与说明 | `/feedback/customers`、`/feedback/{id}/{handling,reply,close}`、`/feedback/refund-disputes/*`、`/feedback/refund-notes` | 普通反馈及处理记录已接。退款异议和退款说明待接 |
| `station-mgmt/business-waiting` | 缺货、待退款、退桶交接、站间桶款及追收、拒付冻结、异议、可办理性 | `/manager/business-waiting`、`/manager/inter-station-recoveries`、`/manager/station-barrel-balances`、拒付与退款接口 | 分散覆盖现有收款/异常/欠桶。待办总页、拒付冻结及复杂处理待接 |
| `station-mgmt/inter-station` | 应收/应付、计价、付款站登记结清、冲销、售后退款分摊与履约交接 | `/manager/inter-station-settlements` 及 `/{orderId}/{settle,price-by-listed,reverse}`、`/manager/inter-station-aftercare/*`、`/manager/station-barrel-balances/*` | 台账、结清、挂牌价与冲销已接。售后分摊、桶争议及追收待接 |
| `station-mgmt/payroll`、`my-earnings` | 工资试算、明细、草稿/确认/已发、计件单价、楼层补贴、人工调整、自定义条目、个人工资 | `/manager/payroll[/preview|/settle]`、`/manager/payroll/{id}/{settlement-preview,settle,confirm,pay}`、`/manager/earnings`、`/manager/piece-rate`、`/manager/payroll/adjust`、`/manager/earning-items` | 试算、结算历史、明细、实发登记、计件及楼层配置、人工加扣与自定义条目已接。独立草稿确认和个人工资视图待接 |
| `station-mgmt/ticket-packages` | 统一折扣与按商品套餐、上架/停用/删除、一次配送费权益 | `/ticket-discounts[/presets]`、`/ticket-packages[/manage]`、`/manager/ticket-delivery-offers` | 档位编辑、启停、删除与一次配送权益已接。预设建议、客户票款退出等其它入口待接 |
| `station-mgmt/staff`、`staff/profile` | 员工、创建配送员、绑定码、绑定/解绑申请、资料、在岗订单、工作量、工资下钻 | `/staff`、`/staff/{id}/profile`、`/manager/staff/{id}/bind-code`、`/manager/bind/*` | 员工查询、基础资料/状态、新增、绑定码、审批和解除已接。完整画像和工资联动待接 |
| `station-mgmt/gross-profit` | 日期、收入、成本、工资、利润可比性、合作款、成本配置 | `/manager/gross-profit[/cost|/missing-cost]` | 日期、收入、出库成本、计件工资及受可比性限制的利润已接。合作款拆分和成本录入待接 |
| `station-mgmt/products` | 商品选用、上下架、进货、盘点、实物/可用库存、流水、缺货补齐、私有商品与上报 | `/manager/catalog/*`、`/manager/my-products/*`、`/inventory/*` | 商品选用、本站售价及上下架、带凭据进货入库、实物盘点与最近流水已接。私有商品、报损、批量入库和更多商品设置待接 |
| `station-mgmt/station-info`、`delivery-config`、`station-status` | 水站资料与定位、范围/起送量/楼层与运费、营业状态/留言、公告 | `/stations/mine`、`/stations/{id}/profile`、`/manager/delivery-config`、`/manager/station-status`、`/notices/*` | 营业与公告、水站资料及坐标、基于快照的配送计费已接。微信地图选点、计费引导和草稿恢复待接 |
| `mine`、`mine/edit`、`settings`、`mine/agreement`、`mine/account-data` | 个人资料、角色、站点、今日工作量、设置、密码、协议与数据相关请求 | `/auth/me`、`/auth/update-profile`、`/auth/change-password`、`/delivery/stats/today`、`/agreements/*` | 已接站长资料、修改密码与退出。今日工作量、协议与数据请求待接 |

`bind-wait`、`station-mgmt/apply-bind` 是配送员绑定等待/申请入口。站长侧对应的审核在员工管理中。微信特有地图授权、拨号、微信身份绑定按其操作分别验收。
