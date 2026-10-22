# 双端全页视觉改造覆盖

基线 `19caae6dda913efd55b49b109a07c8c4848b2c92`。按两端 `app.json` 实际路由核对：顾客26、员工46，共72页；每页均有明确改造或保留理由，另覆盖共享组件和既有弹层。当前方案见[视觉定稿](../../design/37-两端全页视觉定稿.md)。

完整机器清单：[coverage.json](coverage.json)，逐页包含改动、保留原因、文件与验证边界。静态核对全72页不等于逐页原生目视。代表截图为独立离线工程的原生模拟器样例，使用合成数据，业务生命周期和网络均被排除，不验证真实登录、下单、支付、退款或后端部署。旧 d2dd414 截图保持历史版本。

## 逐页行动

| 端 | 路由 | 本轮行动 |
|---|---|---|
| 顾客 | `pages/home/index` | 真实原订单ID直接进入确认，不改变现有购物车；导航未返回时防连点；站名/地址紧凑，当前订单及快捷复购优先，实际商品静态海报按配送状态后移，数量区改为商品正文下方 |
| 顾客 | `pages/home/notice` | 新增任务标题与简短用途说明；长标题、换行正文完整阅读；独立错误和刷新提示保持常显 |
| 顾客 | `pages/order/list` | 订单查询用途标题、可换行状态筛选、共享卡片全商品摘要与付款状态；订单支付/取消/再次购买仍保留原有守卫 |
| 顾客 | `pages/order/detail` | 商品与配送信息先于订单编号等管理信息，长金额/地址可换行；合计与收款状态前置，既有恢复/凭证/取消动作保持 |
| 顾客 | `pages/order/create` | 确认标题与资产站别边界常显，商品真实单位展示；费用/未知/恢复闸门保持；地址/数量可改并重新报价，费用抽屉关闭触控扩大、支付选项去卡套卡与低对比禁用 |
| 顾客 | `pages/order/success` | 缩小装饰结果图形，真实状态/金额/付款入口前置；常用订单和后续操作降低卡片层级；不改变付款与未知状态条件 |
| 顾客 | `pages/address/list` | 新增任务标题与简短用途说明；地址正文提升为主信息；联系人换行、整行触控操作与安全区新增地址入口 |
| 顾客 | `pages/address/edit` | 新增任务标题与简短用途说明；定位/标签/电梯/保存触控区域增大；楼层费用依据常显；省市区窄屏不撑宽 |
| 顾客 | `pages/barrel/index` | 汇总缩减装饰、桶列表改白底分隔行；桶账明细沿用原折叠；退桶弹层及各操作提高触控范围；欠桶、押金与退款未知责任未隐藏 |
| 顾客 | `pages/barrel/purchase` | 页头从卡片改为用途说明，本次押金报价用主色边栏；待核实原购买与新增购买/历史记录分区；所有支付闸门保持 |
| 顾客 | `pages/shop/index` | 商品图片完整显示、本站价格及押金参考就近，目录说明明确报价边界；88rpx实际选购/清单动作、长商品名、独立站点选择class修正 |
| 顾客 | `pages/template/index` | 常用配置说明对齐真实现状，不再宣称首页自动填模板；表单商品选择/数量/弹层关闭操作扩大，次要编辑与删除区分 |
| 顾客 | `pages/ticket/index` | 余额/适用商品/记录层级区分；购票弹层字号与触控提升；原购买恢复入口保持突出；删除无依据的“越买越便宜”通用承诺 |
| 顾客 | `pages/mine/index` | 本站真实资产与未加载分开，水站范围保留，减少装饰身份信息；资产入口/长提示/菜单换行，退桶入口与责任说明保留 |
| 顾客 | `pages/login/index` | 登录页改为白底入口与明确协议告知；移除高占比装饰与低对比白字；登录逻辑与测试入口条件未动 |
| 顾客 | `pages/service/index` | 新增任务标题与简短用途说明；联系水站前置、反馈与处理记录分区；分类/拨打/提交/重试提升触控；退款权限、结案边界与匿名后果不折叠；普通反馈历史按需展开，退款模式的处理说明/责任/未知结果仍常显；只增加展示开关 |
| 顾客 | `pages/mine/about` | 协议/隐私/反馈三个箭头补真实既有路由导航；只阅读不改变同意或登录状态 |
| 顾客 | `pages/mine/edit` | 新增任务标题与简短用途说明；资料表单输入左对齐与明确保存动作；取消大留白和胶囊主按钮 |
| 顾客 | `pages/mine/agreement/index` | 保留单栏长文结构与全量条款；版本信息可读、段落导航视觉增强；草稿/加载失败/阅读不等于同意声明常显 |
| 顾客 | `pages/mine/account-data/index` | 请求选项/登记/记录区分、输入与操作统一；未开放/未结事项/不会自动注销等关键责任信息常显 |
| 顾客 | `pages/payment/records` | 新增任务标题与简短用途说明；账单从卡片堆叠改为连续白底流水；金额/付款方式/未知与刷新失败状态保持可辨 |
| 顾客 | `pages/deposit/records/index` | 水站范围优先、流水连续列表与金额方向说明；长备注/金额可换行；不将账上余额承诺为可退 |
| 顾客 | `pages/notice/index` | 新增任务标题与简短用途说明；公告改连续列表；完整长标题允许换行，时间和来源次一级；真实详情入口保持 |
| 顾客 | `pages/mine/company` | 新增任务标题与简短用途说明；公司名/联系/账期分层；长内容与输入适配窄屏；编辑保存触控增大，结算字段不改 |
| 顾客 | `pages/exception/list/list` | 异常状态只在卡头强调一次、保留差异/补偿；进度节点样式统一且字号可读；去大色块头部 |
| 顾客 | `pages/mine/asset-claims/index` | 内部 E/H 改为已付用桶权益/实际持有水桶、差额值不变；认领表单分隔与状态层级整理；审核即入账与原请求恢复提示常显 |
| 员工 | `pages/login/index` | 减少装饰空白，登录动作明确，协议告知和只读链接完整保留。 |
| 员工 | `pages/mine/agreement/index` | 文档分段白区与长文可读性提升，版本与草稿/失败说明完整常显。 |
| 员工 | `pages/mine/account-data/index` | 申请/注销检查/不可自动删除边界常显，输入与记录/查询操作分层，触区扩大。 |
| 员工 | `pages/role-select/index` | 以角色与工作说明为主，压缩无内容图标与留白，身份生效边界常显。 |
| 员工 | `pages/bind-wait/index` | 审核状态与时间形成事实区，刷新/撤回/退出触区扩大，模式区分原样保留。 |
| 员工 | `pages/station-mgmt/create-station/index` | 创建表单减少外框装饰，必填名称/电话/定位更清晰；底部操作扩大并预留遮挡空间。 |
| 员工 | `pages/station-mgmt/apply-bind/index` | 站名与地址允许换行，搜索/地图/定位操作扩大，卡片改清楚分隔的工作列表。 |
| 员工 | `pages/home/index` | 欠款列表常显；统计改紧凑白区；地址、商品桶数、电话与主动作强化，动作换行且96rpx。 |
| 员工 | `pages/order/detail` | 目的地在电话之前；联系卡前置；订单金额与桶数分层；底部和抽屉操作保持足够触区。 |
| 员工 | `pages/order/complete` | 地址电话前置于货物清单；回桶数字控件96rpx；收款选择和异常/未知/图片恢复常显；选填项保留原折叠。 |
| 员工 | `pages/mine/index` | 身份资料改白底清楚对比；水站状态、工资/桶异常与入口分层；审批与解绑动作扩大。 |
| 员工 | `pages/coordination/index` | 待办先于管理入口；管理及帮助入口保留；分配与审批大按钮；费用、责任风险常显。 |
| 员工 | `pages/station-mgmt/index` | 保留五组全部入口，增加工作台层级，统一待办行字号与未知徽标，压低装饰。 |
| 员工 | `pages/station-mgmt/business-waiting/index` | Added a work heading distinguishing pending duties from completed results；Converted nested tinted task cards into separated task rows；Increased task action/input targets to at least 88rpx; long labels can wrap |
| 员工 | `pages/station-mgmt/dashboard/index` | 指标从卡中卡改分隔数据区，宽金额可换行；原双窄列改纵向图表；日期与指标切换扩大。 |
| 员工 | `pages/station-mgmt/orders/index` | 增加订单页任务标题，放大收货人及金额，筛选/日期/加载更多扩大；长头部和跨站汇总可换行。 |
| 员工 | `pages/station-mgmt/customers/index` | 客户编号/电话/地址清晰排列，操作从右侧窄列移到卡底整行，货到付款和企业申请弹窗支持大触控与换行。 |
| 员工 | `pages/station-mgmt/customers/detail/index` | 本站资产提前到消费统计之前；替客户下单入口主动作化；桶票押金两列清楚展示，流水和还桶弹层扩大。 |
| 员工 | `pages/station-mgmt/customers/adjust/index` | 调整单客户身份/金额与状态清楚排布，新建、详情、执行操作扩大；长客户信息允许换行。 |
| 员工 | `pages/station-mgmt/customers/adjust/edit/index` | 大输入和完整宽度方向选择，试算表格与风险说明增大，提交名称明确为保存待执行单。 |
| 员工 | `pages/station-mgmt/customers/adjust/detail/index` | 金额/数量与调整快照保持常显，仅原始记录详情新增可逆展开开关；执行/撤销按钮扩大并保留后果。 |
| 员工 | `pages/station-mgmt/products/index` | 现有商品/库存排在模板建档前；商品动作在卡底整行；设置/库存/图库/水票弹层与输入大触控。补商品读取失败与重试，失败不显示空目录。 |
| 员工 | `pages/station-mgmt/station-status/index` | 营业选项纵向可读，顾客实际看到的预览突出，公告操作与编辑弹层扩大。 |
| 员工 | `pages/station-mgmt/station-info/index` | 清楚资料表单和定位分区，大输入/地图选点/清除操作；坐标长值可换行。 |
| 员工 | `pages/station-mgmt/delivery-config/index` | 起送、范围、配送和楼层费分区更清楚，选项改全宽，大输入和保存动作；完善度与当前费用条件保持可读。 |
| 员工 | `pages/station-mgmt/place-order/index` | 明确选客户→地址→核对订单进度，大数量按钮，突出客户/地址、本单真实报价与合计；修正文案中的不存在建档入口与绝对报价承诺。 |
| 员工 | `pages/station-mgmt/receivables/index` | Outstanding total gets a full-width summary before overdue/customer subfacts；Selected order count and actual-receipt caution appear immediately before settlement；Customer, amount and action rows wrap; terms dialog scrolls within viewport |
| 员工 | `pages/station-mgmt/inter-station/index` | Consolidated station balances into a single summary strip；Reversal tasks receive a visible warning boundary；Responsibility records use flat dividers instead of nested cards; large wrapped action rows |
| 员工 | `pages/station-mgmt/payroll/index` | Flattened configured rates/product/custom-item nesting；Settlement amount and period read left to right instead of an oversized centered poster；Added always-visible actual-payment-before-marking caution beside payable slips；Enlarged tabs, filters, menus and actions with wrapped labels; removed warm local palette overrides |
| 员工 | `pages/station-mgmt/ticket-packages/index` | Existing active/inactive price tiers now precede presets and the editor；Named the task as setting station water-ticket prices；Form labels stack above full-width fields; per-tier controls sit below price facts；Readable product/specification and at-least-88rpx tier actions |
| 员工 | `pages/station-mgmt/gross-profit/index` | Compact side-by-side period selection with large touch targets；Profit result receives a full-width line after revenue/cost facts；Report and product ledger rows wrap; cost editor scrolls with wrapped action footer |
| 员工 | `pages/station-mgmt/barrel-return/index` | Historical undelivered-deposit caution moved outside the expandable record list；Compact approval heading/cards; clear flat summary and scrollable original approval sheet；Search, close, refund/recheck and secondary actions have at-least-88rpx targets |
| 员工 | `pages/station-mgmt/owed-barrels/index` | Made current net debt and reminder-only policy a clear short heading；Increased owed quantity prominence and large wrap-friendly date filters；Customer/date/product rows support long text without clipping |
| 员工 | `pages/station-mgmt/exceptions/index` | Exception task list now precedes 30-day statistics；Flattened individual exceptions with a visible pending boundary；Larger filters, load-more and compensation actions; clear operation-trace text |
| 员工 | `pages/station-mgmt/customer-feedback/index` | 争议和处理任务清楚，正文/记录与表单可读，重试与结案/说明动作扩大。 |
| 员工 | `pages/station-mgmt/payments/index` | Receivable/ledger amount promoted to the first line in every payment record；Actual-receipt requirement is a prominent persistent caution before pending records；Large pending/history tabs, filters and confirmation action; long identities/amounts wrap |
| 员工 | `pages/station-mgmt/staff/index` | 员工身份与电话清楚，动作改卡底整行大按钮；绑定与解绑申请保留。补员工及申请加载/失败/未核实状态，真空结果才显示暂无。 |
| 员工 | `pages/station-mgmt/staff/profile/index` | 当前进行中订单提前到历史业绩前；地址完整换行；业绩改两列，电话触控扩大。 |
| 员工 | `pages/barrel-records/index` | 目的地与回桶数量强化，差异记录完整换行，错误重试与空态保留。 |
| 员工 | `pages/my-earnings/index` | 未结与区间收益分区；日期/结算状态/明细可换行，筛选触区扩大。 |
| 员工 | `pages/history/index` | 目的地在商品之前，商品图/规格保留；数量与回桶事实清楚，记录长文可换行。 |
| 员工 | `pages/transfer/index` | 目的地在商品之前，保留原转让解释及记录判据，长备注可完整阅读。 |
| 员工 | `pages/report/index` | 反馈分类96rpx触区、内容输入优先，联系方式/历史记录长文适配，提交动作扩大。 |
| 员工 | `pages/settings/index` | 账户值可换行，设置说明可读，开关功能蓝，退出与重试目标扩大。 |
| 员工 | `pages/mine/edit` | 资料白区与112rpx字段行，保存主动作矩形化，输入可伸缩。 |
| 员工 | `pages/station-mgmt/asset-claims/index` | 客户/商品/实收押金清楚分列，移除可见 E/H 术语；金额、原申请待核实与审核按钮突出。 |

## 验证与交接

已完成逐页结构/条件链、事件绑定与 UTF-8 检查；有限行为回归使用实际页面处理函数，包含复购参数、新价格/地址/库存读取、报价和未知阻断；最终JS总跑107套件、2996计数项全部通过；18项可执行本地静态门禁通过，72页结构及条件链核对通过。分项证据见[verification.json](verification.json)。本地未构建jar、未启动8080后端，相关CI结果以精确提交回执为准。站长名单/申请与商品目录修正“请求失败像真实空列表”的展示，新增独立回归并注册总跑。生产后端、数据库及微信未部署。

代表页原生目视9张、7个路由（顾客首页3态、费用抽屉、配送列表/送达确认、站长待办、收款空态、客户资产），其余65路由为静态审阅；未目视路由仍在 JSON 保留 visualPending，不能把结构审查算作像素通过。Canvas 趋势图不在本次独立视觉工程完整绘制范围，沿用真实绘制代码，不伪造图表数据。

恢复：改造前已保留本地恢复分支及旧日常目录8个未跟踪文件的校验副本。正常快进提交/推送；旧源码证据目录和设计A/B/定稿HTML不改。后续主海报精细素材可独立调整，本轮只使用已有中性商品图与实际字段。

## 2026-10-22 独立复核补充

以上为 a568a65 批次历史记录，不把其勾选直接当成复核通过。后续以 `2000a1866b76905e4eeef4c60e54a7ecd8910297` 为实际基线，重新读取两端72路由的模板/样式/条件和关键处理函数，复看原生截图像素，发现并修复复购站别、异步通知上下文和选站读取状态等问题。见[独立评审与设计建议](independent-review.md)、[实际路由复核记录](independent-routes.json)、[定向检查回执](independent-checks.json)。本次没有新增72页原生截图，未将旧d2图用作当前证据。

最终补充：上一段“修复复购站别”的初判撤回，恢复既有默认策略；确定修复、未采纳理由与最终证据以独立评审末节“最终收束”为准。

## 2026-10-22 两处视觉层级收束

本节供接手维护者核对，基线为 `6760c9670e5c9ce9827f480123e779de7935a14e`。用户批准后，仅落实以下两项：

- 配送首页将原统计区的三个列表数量并入对应页签，沿用 `switchTab` 与列表长度。移除重复外框及其局部样式，保留状态文字的 14px、数量的 13px 和页签至少 48px 触区，长数量可换行。待收款、转给我的单、地址、电话、商品桶数及全部业务处理不改。
- 站长客户资产页的普通指标数字改用正文深色；欠桶、配送中保持原语义色，电话、代客下单与资产操作仍保留蓝色。只改默认指标样式，不改数据、权限或状态分支。

相关现有回归 `delivery-g04-typography-contrast`、`delivery-home-station-identity`、`delivery-runtime-copy`、`manager-first-batch` 共 4 套、71 项通过；额外执行 10 项临时合成状态核对，涵盖三个筛选、加载/空/部分失败、配送员/站长范围、客户资产加载/错误/空态，并确认首页业务 JS 仅注释变化。两页定向 WXSS 选择器、JS 解析和 UTF-8 无 BOM 检查通过，未降低或跳过现有断言。

同一合成数据的实际 WXML/WXSS 静态投影中，首条配送地址提前约 82 CSS px；金额、地址电话及订单卡文字一致。此为离线排版证据，不代表微信原生触控、键盘或真实交易验收。顾客首页沿用已有顶部水光，不采用竖版背景；未实施“我的”标题色带，不扩到其它页面。整合结果以本次精确提交 CI 为准。
