# 2026-11-03 交互报告独立核验与修复

基线：`925f7a5e2a0704bcd43648f3d932f23e5a3d0cde`。本次不部署、不执行云端迁移、不改历史账。没有新增数据库结构。

## 采纳并修复

| 问题 | 处理与证据 |
| --- | --- |
| 历史成功页误清当前购物车 | 成功页只读取原单；仅已确认创建的新购物车订单，且原站、原会话、原导航和完整购物车数量仍匹配时消费一次。超时重放、再来一单和购物车已编辑均保留。`tests/js/submitted-cart-flow.test.js`、`tests/js/order-result-navigation.test.js`、`tests/js/customer-night-visual-states.test.js`。 |
| 订单列表缺支付能力及取消处理结果 | 顾客列表复用详情的渠道能力判定与取消申请投影；员工列表不附加顾客操作能力。`OrderListProjectionTest` 修前4项中3项失败，修后4项通过。 |
| 站长收款/核销后已送达订单仍停留已送达 | 三条既有收款路径复用同一事务内3→4 CAS。未送达不提前完成；失败回滚，不修历史状态。`CollectionCompletionIntegrationTest` 修前6项中4项失败，修后6项通过，含越权、重试、并发及SQL故障回滚。 |
| 水票费用显示误导及流水事由错误 | 区分水费参考、原单其他费用合计、原单参考总额；0元仍显示。新费用流水标记“不含水费”，收款默认事由不假定操作者是配送员。原金额、扣票、退款及历史凭据不变。`TicketServicePaymentIntegrationTest`、`tests/js/ticket-service-fee-ui.test.js`。 |
| 资产站名称缓存不刷新、缺当前订水入口 | 每次激活重新核对本资产站资料，迟到响应保持隔离；组件提供返回订水首页入口，不写全局订水站或资产站。`tests/js/asset-view-station-flow.test.js`。 |
| 退桶选择某商品仍使用全站数量上限 | 商品上限取当前商品可退数量与全站上限较小值，换商品后重新核对。全站入口资格与当前商品上限分离，A无可退不阻止选择B。`tests/js/barrel-product-limit.test.js` 修前4项失败、修后4项通过。 |
| 伴随订单只读首页，漏后续页 | 对待配送/配送中两个状态分别按实际page/pageSize读取，去重并过滤站别；身份变化弃用迟到结果，异常和重复满页显式失败，不当空列表。单状态最多50页，超出提示改到店/单独上门或重试，不静默漏单。`tests/js/companion-orders-pagination.test.js`、`tests/js/return-arrangement-flow.test.js`。 |

旧试算/草稿测试中“已加载”仅填写全站max、未给商品权益事实的夹具已补齐；保留其原有数量、取消、迟到响应和未知结果断言，没有放宽生产守卫。新增费用模板测试使用真实页面默认数据再投影，避免遗漏无关字段导致假失败。

## 核实后保留

- 当前v90列表和详情都已投影`ticketFeePaymentMethod`、`ticketCashAmount`、`ticketDeliveryCredit`、`ticketWaterState`；拆单流水的水费为0、配送抵扣/楼层费已有快照。不能把旧版本缺字段结论套用当前代码。
- 员工详情已有电话、当前订单详情和展开业务数据，操作均受生命周期、读取状态和画像身份守卫；不擅自新增停用、权限或跨站管理功能。代码：`miniapp-delivery/pages/station-mgmt/staff/profile/index.js` 的 `onCall`、`onOrderTap`；既有`tests/js/staff-account-request-ui.test.js`、`tests/js/staff-astra-review-regressions.test.js`。
- 停业历史资产站仍应可查看资产。组件采用后端`statusText`，不把“可查资产”当成“可下单”；下单服务站与资产查看站保持分离。`tests/js/asset-view-station-flow.test.js`含停业站和A/B切换。

## 仅诊断，不自动补账

1. `StationAdjustmentServiceImpl` 的 `BARREL_GRANT` 经桶批次账增加权益，`DEPOSIT_GRANT` 经押金流水独立记录金额。两项代表不同事实；赠桶/历史权益不等于已实收押金。隔离HTTP诊断确认补2份权益没有产生押金或支付流水。建议若需联办，先明确实际收款凭据及两项金额/数量，再用显式确认的联合业务命令；本次不自动联动。
2. `PayrollPreviewMapper.unrecordedDeliveries`主要检查`DELIVERY_BUCKET`是否缺失，并受当前每桶费配置影响，没有对每一笔历史楼层奖励独立证明完整性。当前配置不是历史应付依据。建议新增“历史计件/楼层记录待核”证据提示，再由授权人员按当时约定补录，禁止按当前单价自动追算或重发。本次仅做合成数据诊断，未修改工资与结算规则。

## 验证边界

本机独立编译当前466个主源码成功。最终收款与水票费用两类真实HTTP/MySQL合计22项通过；每轮专用本机测试库均先核对目标UUID及独占状态、备份，结束恢复原85表数量及校验和。诊断首轮工资夹具误写不存在的订单floor字段，该轮失败不作为业务证据，已恢复后用实际地址字段重新验证。

完整JS和标准GitHub CI结果以对应最终提交的回执为准，不把局部测试相加冒充最终全量。原四个SDK夹具、私有配置及无关未跟踪文件不纳入本次提交。小屏、字体放大、真机键盘与原生WXML仍需微信工具及手机验收，源码投影不能代替实机。

## 更新范围

先更新后端，再从原`miniapp-user`工程编译上传顾客端。员工端本次无源码变更；其收款/核销行为依赖新后端。无需新迁移，不重复v90或导入215条数据。本次代码归档不是生产发布证明。

最终本机回执：完整JS 233套件/4810项通过；19条静态/契约/敏感检查退出0，其中矩阵统计如无当前全量XML仍属历史基线，不是新后端全量。两项隔离业务诊断通过，原65件保护文件哈希一致。标准完整后端与制品启动检查由本提交CI补齐。
