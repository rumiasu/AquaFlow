# 2026-11-02 两端74页优化与交付核对

写给接手续修、验收和更新的工程师；逐页记录真实改动、保留依据和验证边界。接续过程与已有v89边界见 [夜间交接](../development/20261102-night-work-handoff.md)。本轮不改品牌图形和艺术字，不新增付款/赔付规则。

## 已落实的业务改动

1. 异常建议补偿只发送事务办理命令；原来handle内已执行，页面仍追加execute导致成功报失败。网络结果未知只查原异常，不自动追加执行或重发补偿。
2. 员工画像返回刷新；旧请求、离页和换身份回执不能覆盖新资料或触发原订单导航。
3. 顾客无需补偿结案使用后端明确结果和完成进度，消除“已结案/结果待核实”并存。
4. 跨站现场提醒与异常同认履约责任站，不扩大原提醒查询权限。
5. 两端办理记录补原事项摘要、状态和阻断原因；站长可沿已有权限查看原订单，匿名反馈不下发身份和联系方式。
6. 本人收益新增历史日期、更早工资单和单据明细。工资单按id倒序游标，查询身份只取登录态，单据和明细均校验本人，不新增转账。
7. 20个此前未预留底部安全区的员工页面补本页留白；经营环比长数字、员工绩效、空桶记录、设置说明、历史时间和反馈长文本分别调整，原有成熟页面按下表保留。

## 覆盖口径

路由来自两端当前app.json主包和分包，共27+47=74；表中的载/错/空表示源码存在相应显示分支，部分表单使用操作状态与错误提示。入口同时核对JS、WXML及封装导航；顾客结果页和创建水站页均有真实入口，不采纳工具的孤岛假阳性。未注册的辅助JS模块不当成遗漏页面。

本轮已逐页审查模板、事件/导航、状态和本地样式；高风险问题继续沿API、Service和Mapper核证。**没有运行微信开发者工具或真机，原生像素验收仍为0/74**。下面的界面结论只证明源码落实和回归，不证明所有手机已目视通过。

## miniapp-user（27页）

| 路由 | 角色 / 主任务 | 主要入口 → 出口 | 状态分支 | 本轮处理或保留依据 |
|---|---|---|---|---|
| `pages/home/index` | 顾客：选水订水 | 底栏 → address/list、order/create、order/detail | 载/错/空 | 保留；已有选站局部重试、商品图回退、数量草稿及进行中原单入口，主任务清楚。 |
| `pages/home/notice` | 顾客：阅读公告 | notice/index → notice/index | 载/错/空 | 保留；正文与读取失败分开，已有返回公告列表出口及长文本安全区。 |
| `pages/order/list` | 顾客：查订单与再订 | 底栏 → login/index、home/index、order/create | 载/错/空 | 保留；分页失败保留已读记录，空态可回订水，主操作由订单卡承接。 |
| `pages/order/detail` | 顾客：付款取消及订单进度 | home/index、order/success → order/list、order/create | 载/错/空 | 保留；订单、付款与图片分区，取消和再订走原流程，金额未知不补零。 |
| `pages/order/create` | 顾客：核对费用并下单 | home/index、order/detail → login/index、home/index、address/list | 载/错/空 | 保留；费用、押金和付款确认有独立分支，未知原单可核对，不为视觉改交易顺序。 |
| `pages/order/success` | 顾客：核对原订单结果 | 应用/封装导航 → home/index、order/detail、order/list | 载/错 | 保留；结果缺编号有显式错误，原单付款与模板保存独立，导航失败可恢复。 |
| `pages/mine/index` | 顾客：个人资料及本站资产 | 底栏 → ticket/index、deposit/records/index、barrel/index | 载/错 | 保留；资产按站分开读取，长数字和放大字已有堆叠布局，未加载有局部重试。 |
| `pages/login/index` | 顾客：顾客登录 | address/edit、address/list → home/index、mine/agreement/index | 载/错 | 保留；原A标和艺术字、协议及登录错误已分层，不重设计品牌。 |
| `pages/mine/about` | 顾客：品牌与协议入口 | mine/index → mine/agreement/index、service/index | 静态 / 操作反馈 | 保留；静态品牌说明、客服与协议入口完整，已有阅读留白和底部安全区。 |
| `pages/mine/edit` | 顾客：修改个人资料 | mine/index → login/index | 载/错 | 保留；失败不清输入，已有明确恢复/放弃草稿及保存反馈。 |
| `pages/mine/agreement/index` | 顾客：阅读协议 | login/index、mine/about → 原页返回 / 本页办理 | 载/错 | 保留；读取与重试独立，长正文可滚动，不增加无关业务按钮。 |
| `pages/mine/account-data/index` | 顾客：本人信息与账号申请 | mine/index → login/index | 载/错/空 | 保留；申请与历史详情分开，未核实条件不当成允许销户，原申请可追踪。 |
| `pages/mine/company` | 顾客：企业资料 | mine/index → login/index | 载/错 | 保留；资料查看与编辑明确，已有取消、草稿和失败恢复。 |
| `pages/mine/asset-claims/index` | 顾客：历史资产认领 | mine/index → login/index | 载/错/空 | 保留；按商品核对权益/持桶，照片选填且可恢复，历史实收押金不靠乘价猜测。 |
| `pages/address/list` | 顾客：选择及管理地址 | address/edit、home/index → login/index、address/edit | 载/错/空 | 保留；选用地址和编辑操作有区分，加载失败不伪装为空地址。 |
| `pages/address/edit` | 顾客：编辑收货地址 | address/list → login/index、address/list | 载/错 | 保留；地图、门牌、楼层和电梯分组，定位失败与手填均有出口。 |
| `pages/barrel/index` | 顾客：桶容量、实物与退还 | home/index、mine/index → login/index、home/index、deposit/records/index | 载/错/空 | 保留；实物/容量/押金分清，已有退还安排、撤回和原请求核对，照片不阻塞资产。 |
| `pages/barrel/purchase` | 顾客：购买桶容量 | barrel/index、mine/index → login/index | 载/错/空 | 保留；商品、数量、报价与原款恢复分组，未知结果保留原请求。 |
| `pages/deposit/records/index` | 顾客：押金流水 | barrel/index、mine/index → login/index | 载/错/空 | 保留；金额方向与合法/缺失日期区分，按资产站查询且有空错态。 |
| `pages/exception/list/list` | 顾客：异常办理结果 | mine/index → service/index、login/index | 载/错/空 | 修复；VERIFIED使用后端无需补偿结案文案、完成进度与成功样式，不再显示结果待核实。 |
| `pages/service/index` | 顾客：联系水站、反馈与退款异议 | barrel/index、mine/about → case-handling/index、login/index | 载/错/空 | 保留；普通反馈、退款异议和原退款说明分开，提交失败可核对本人历史。 |
| `pages/shop/index` | 顾客：搜索选购 | home/index、mine/index → order/create、login/index | 载/错/空 | 保留；搜索、选站和结算主任务突出，商品与水站错误分别重试。 |
| `pages/template/index` | 顾客：常用订单模板 | mine/index → login/index | 载/错/空 | 保留；新建/编辑/启停和删除有明确动作，表单失败及草稿恢复完整。 |
| `pages/ticket/index` | 顾客：水票余额与购票 | mine/index、order/create → login/index、barrel/purchase | 载/错/空 | 保留；余额、套餐和原购票结果分区，待核实款项不诱导另付一次。 |
| `pages/notice/index` | 顾客：公告列表 | home/notice、mine/index → login/index、home/notice | 载/错/空 | 保留；标题、日期和正文入口清楚，已有失败重试和真实空列表。 |
| `pages/payment/records` | 顾客：付款流水 | mine/index → order/detail、login/index | 载/错/空 | 保留；原订单入口、日期分组、未知金额和读取身份保护已落实。 |
| `pages/case-handling/index` | 顾客：本人反馈的水站回复 | service/index → order/detail | 载/错/空 | 新增原反馈摘要、类别和站别处理状态；换身份清掉旧摘要，顾客端不提供员工订单入口。 |

## miniapp-delivery（47页）

| 路由 | 角色 / 主任务 | 主要入口 → 出口 | 状态分支 | 本轮处理或保留依据 |
|---|---|---|---|---|
| `pages/login/index` | 员工/引导身份：员工登录 | mine/index → mine/agreement/index | 载/错 | 保留原Q标和艺术字、登录与协议操作；本轮补底部安全区。 |
| `pages/mine/agreement/index` | 员工/引导身份：阅读协议 | login/index → 原页返回 / 本页办理 | 载/错 | 保留长协议阅读和重试；本轮补底部安全区。 |
| `pages/mine/account-data/index` | 员工/引导身份：本人信息与账号申请 | mine/index → 原页返回 / 本页办理 | 载/错/空 | 保留本人申请/详情/失败分支；本轮补底部安全区。 |
| `pages/role-select/index` | 员工/引导身份：初次选择身份 | station-mgmt/apply-bind/index、station-mgmt/create-station/index → home/index、station-mgmt/create-station/index、bind-wait/index | 静态 / 操作反馈 | 保留两种身份的明确任务及选择单飞；本轮补底部安全区。 |
| `pages/bind-wait/index` | 员工/引导身份：绑定审核进度 | role-select/index、station-mgmt/apply-bind/index → home/index、station-mgmt/apply-bind/index | 载/错/空 | 保留审核、撤回、刷新和重新申请路径；本轮补底部安全区。 |
| `pages/home/index` | 员工/引导身份：本人配送任务 | 底栏 → home/index、order/detail、order/complete | 载/错/空 | 保留；配送状态、原单结果核对和收款恢复已有独立入口，既有统计间距不重复重写。 |
| `pages/order/detail` | 员工/引导身份：订单办理与售后 | barrel-records/index、coordination/index → home/index、order/complete | 载/错/空 | 保留；接单、现场问题、送达和售后按原权限呈现，凭证失败有重试。 |
| `pages/order/complete` | 员工/引导身份：核对收款回桶并送达 | home/index、order/detail → home/index、station-mgmt/products/index、order/detail | 载/错/空 | 保留；收款、实际回桶、异常理由及照片分组，库存不足有合法站务出口。 |
| `pages/mine/index` | 员工/引导身份：角色账户与功能入口 | 底栏 → mine/index、station-mgmt/gross-profit/index、station-mgmt/exceptions/index | 载/错/空 | 保留；按站长/配送员分组入口，统计读取失败有常驻重试，自绘底栏安全区已存在。 |
| `pages/coordination/index` | 站长：站长派单与待办 | 底栏 → station-mgmt/receivables/index、station-mgmt/payments/index、station-mgmt/customer-feedback/index | 载/错/空 | 保留；派单与审批队列分区，首页统计数字文字间距已由40b5修复。 |
| `pages/barrel-records/index` | 员工/引导身份：本人空桶记录 | mine/index → order/detail | 载/错/空 | 补安全区；回桶统计标签与记录时间提高至14px，保留原订单入口和未知数量说明。 |
| `pages/my-earnings/index` | 员工/引导身份：本人收益和历史工资 | mine/index → 原页返回 / 本页办理 | 载/错/空 | 新增历史日期、更早工资单与本人明细；按id游标翻页、双重本人校验、失败局部重试；补安全区及14px关键辅助文。 |
| `pages/history/index` | 员工/引导身份：本人配送历史 | mine/index → order/detail | 载/错/空 | 补安全区；历史日期改14px且可换行，保留订单快照与详情入口。 |
| `pages/transfer/index` | 员工/引导身份：转让记录 | mine/index → order/detail | 载/错/空 | 补安全区；转让日期/订单记录提高至14px，保留原权限和详情入口。 |
| `pages/report/index` | 员工/引导身份：员工意见反馈 | mine/index → 原页返回 / 本页办理 | 载/错/空 | 保留；类型选择、内容及历史分区，离页/身份变化处理和提交失败反馈已有覆盖。 |
| `pages/settings/index` | 员工/引导身份：提醒偏好与账户设置 | mine/index → 原页返回 / 本页办理 | 载 | 补安全区与14px设置说明；保留仅站长待办开关、可重建缓存清理和退出确认。 |
| `pages/mine/edit` | 员工/引导身份：修改昵称和电话 | mine/index → 原页返回 / 本页办理 | 载/错 | 保留；资料读取失败有重试，保存状态与昵称/电话分组清楚。 |
| `pages/station-mgmt/create-station/index` | 员工/引导身份：首次创建水站 | role-select/index → role-select/index、home/index | 载/错 | 保留；地点与站务必填分组，定位失败可处理，已有退出/返回选身份。 |
| `pages/station-mgmt/apply-bind/index` | 员工/引导身份：配送员申请绑定 | bind-wait/index、role-select/index → role-select/index、bind-wait/index | 载/错/空 | 保留；搜索、位置和申请历史分开，结果未知不再发第二次申请。 |
| `pages/station-mgmt/index` | 站长：站务目录与待办 | coordination/index、mine/index → station-mgmt/business-waiting/index、station-mgmt/barrel-return/index、station-mgmt/receivables/index | 载/错 | 补目录底部安全区，保留待办和功能分组及既有统计间距。 |
| `pages/station-mgmt/business-waiting/index` | 站长：库存、拒付、资产退出待办 | station-mgmt/index → order/detail、station-mgmt/barrel-return/index | 载/错/空 | 补安全区，保留缺货/拒付/退还/票退出的独立办理与原请求恢复，不新增核销政策。 |
| `pages/station-mgmt/dashboard/index` | 站长：周期经营数据 | station-mgmt/index → 原页返回 / 本页办理 | 载/错/空 | 补安全区；环比、上期数、排行长金额可换行，周期选项可折行；保留统计口径和图表。 |
| `pages/station-mgmt/orders/index` | 站长：站内订单管理 | coordination/index、station-mgmt/index → order/detail | 载/错/空 | 补安全区，保留日期、跨站筛选、分页失败重试和原订单入口。 |
| `pages/station-mgmt/customers/index` | 站长：客户和站级授权 | coordination/index、station-mgmt/index → station-mgmt/customers/detail/index | 载/错/空 | 保留；客户搜索、企业权限和货到付款设置分开，设置失败不当成关闭。 |
| `pages/station-mgmt/customers/detail/index` | 站长：客户资料及本站资产 | station-mgmt/customers/index、station-mgmt/exceptions/index → order/detail、station-mgmt/customers/adjust/index、station-mgmt/place-order/index | 载/错/空 | 保留；按站读取资产、还桶原请求和回页刷新已存在，异常入口继续复用该页。 |
| `pages/station-mgmt/customers/adjust/index` | 站长：资产调整清单 | station-mgmt/index、station-mgmt/customers/detail/index → station-mgmt/customers/adjust/edit/index、station-mgmt/customers/adjust/detail/index | 载/错/空 | 补安全区，保留清单状态、新建/详情及执行权限。 |
| `pages/station-mgmt/customers/adjust/edit/index` | 站长：新建资产调整 | station-mgmt/customers/adjust/index → station-mgmt/customers/adjust/detail/index | 载/错 | 补安全区，保留客户/商品选择、调整方向与证据、原提交恢复。 |
| `pages/station-mgmt/customers/adjust/detail/index` | 站长：调整执行与冲正详情 | station-mgmt/customers/adjust/index、station-mgmt/customers/adjust/edit/index → station-mgmt/customers/adjust/detail/index | 载/错/空 | 补安全区，保留执行/冲正的原单据和理由保护，不增加直接抹账。 |
| `pages/station-mgmt/products/index` | 站长：商品、库存与入库 | order/complete、station-mgmt/index → station-mgmt/products/index、station-mgmt/ticket-packages/index | 载/错/空 | 保留；商品/库存/入库和记录已分组，原生滚动容器与图片失败恢复完整。 |
| `pages/station-mgmt/station-status/index` | 站长：营业状态与公告 | station-mgmt/index → 原页返回 / 本页办理 | 载/错/空 | 保留；营业状态与公告分区，草稿/发布/下架均可查并处理。 |
| `pages/station-mgmt/station-info/index` | 站长：水站资料 | mine/index、station-mgmt/index → station-mgmt/station-info/index | 载/错 | 补安全区，保留坐标及文本的同次保存、草稿和只读引导状态。 |
| `pages/station-mgmt/delivery-config/index` | 站长：配送计费规则 | station-mgmt/index → station-mgmt/delivery-config/index | 载/错 | 保留；分段计费教程、草稿和明确保存完整，退出及失败能恢复。 |
| `pages/station-mgmt/place-order/index` | 站长：代客下单 | station-mgmt/customers/detail/index → order/detail | 载/错/空 | 补安全区，保留客户→地址→商品→报价→下单和未知原单重试，不另造计价。 |
| `pages/station-mgmt/receivables/index` | 站长：应收与账期 | coordination/index、station-mgmt/index → order/detail | 载/错/空 | 保留；应收金额、逾期和账期分区，明细失败不当成结清，核销仍认实际收款。 |
| `pages/station-mgmt/inter-station/index` | 站长：站间结算和售后分担 | coordination/index、station-mgmt/index → order/detail | 载/错/空 | 保留；结算方向、责任金额与实际交付分清，未知结果保留原请求。 |
| `pages/station-mgmt/payroll/index` | 站长：计件、工资和结算单 | coordination/index、station-mgmt/index → 原页返回 / 本页办理 | 载/错/空 | 保留；薪资单价、预览、调整、结算单历史已分区且有更早记录入口。 |
| `pages/station-mgmt/ticket-packages/index` | 站长：水票套餐档位 | station-mgmt/products/index → station-mgmt/payments/index | 载/错/空 | 保留；适用商品、张数/折扣/总价及档位启停分组，保存草稿保护完整。 |
| `pages/station-mgmt/gross-profit/index` | 站长：收入、成本与利润 | coordination/index、mine/index → 原页返回 / 本页办理 | 载/错/空 | 保留；成本未知只显示收入，金额结构和合作款说明分层，负值不丢。 |
| `pages/station-mgmt/barrel-return/index` | 站长：退还审批、交桶与退款 | station-mgmt/index、station-mgmt/business-waiting/index → 原页返回 / 本页办理 | 载/错/空 | 保留；收桶、实际退款和交付登记分清，多页历史及原申请结果可核对。 |
| `pages/station-mgmt/owed-barrels/index` | 站长：欠桶客户与实际还桶 | mine/index、station-mgmt/index → station-mgmt/customers/detail/index | 载/错/空 | 保留；40b5已补实际还桶入口，继续复用客户资产页，不重复建设或扣权益退款。 |
| `pages/station-mgmt/exceptions/index` | 站长：异常处置与留痕 | coordination/index、mine/index → case-handling/index、order/detail、station-mgmt/customers/detail/index | 载/错/空 | 修复补偿一次编排及未知结果只读核对；保留原单、资产和核实结案入口及已修统计间距。 |
| `pages/station-mgmt/customer-feedback/index` | 站长：顾客反馈及退款争议 | coordination/index、station-mgmt/index → case-handling/index | 载/错/空 | 补安全区、长姓名/内容/联系方式折行与14px时间；保留普通反馈回复和退款争议的独立权限。 |
| `pages/station-mgmt/payments/index` | 站长：收款确认与购票历史 | coordination/index、station-mgmt/index → 原页返回 / 本页办理 | 载/错/空 | 保留；待核实到账与已发生流水分区，购票历史有搜索/日期/继续加载及未知金额。 |
| `pages/station-mgmt/staff/index` | 站长：员工及绑定申请 | coordination/index、mine/index → station-mgmt/payroll/index、station-mgmt/staff/profile/index | 载/错/空 | 保留；员工、绑定/解绑申请、薪资后续分区，站长本人不可解绑护栏保留。 |
| `pages/station-mgmt/staff/profile/index` | 站长：员工画像与进行中订单 | station-mgmt/staff/index → order/detail | 载/错/空 | 修复回页刷新、旧响应及换站隔离；补安全区、指标留白和14px标签，保留原进行中订单权限。 |
| `pages/station-mgmt/asset-claims/index` | 站长：历史资产核对 | station-mgmt/index → 原页返回 / 本页办理 | 载/错/空 | 保留；客户/商品/实收押金独立核对，照片可重试、匿名及跨站边界不放开。 |
| `pages/case-handling/index` | 站长：异常核实及顾客反馈办理 | station-mgmt/customer-feedback/index、station-mgmt/exceptions/index → order/detail | 载/错/空 | 新增原事项摘要、当前状态、不能结案原因和原订单入口；无差异结案仍不执行钱票桶调整。 |

## 回归与最终交付

本轮本地前端完整回归已通过：227套、4739项；15项独立静态检查通过，事件绑定与JS语法另核通过。后端完整回归及最终提交CI仍在运行，不登记为完整通过。第一次完整前端回归有3套既有表达式投影在100ms上限发生调度超时；投影仍保留有限1秒预算，并新增无限循环/语法错误反向控制，不减少业务断言或套件。失败套件独立复跑已通过。

数据库只使用本会话新建的隔离MySQL8.4容器，绑定127.0.0.1:13316，空库准备后测试；不连接用户云端业务库，不重导215条演示数据。最终完整XML生成STATS，不使用定向XML伪造全量。

## 更新顺序与未验范围

1. 核对当前库和v89只读预检；表缺失时才按既有单条CREATE准备，已有表核对字段及唯一键，不DROP、不覆盖旧记录。v88及更早迁移依原清单核对。本轮未新增迁移。
2. 更新后端全部承流实例；再分别编译两端小程序，核对实际包体并上传相应体验版。
3. 手机逐页检查320/375/414宽度、放大字体、多位数字、长姓名地址、键盘、安全区、图片失败和原请求恢复；按本人/站长/配送员/跨站真实测试身份走完整流程。

本轮未部署、未修改数据库/云资源权限、未真实收退款。真实微信支付、真机编译/像素和云端更新后的v89结构仍需在对应环境实际验收。准确源码、JAR、文件哈希及本轮CI链接随最终交付记录，不使用旧ZIP冒充新版。
