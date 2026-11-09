# 两端文案全面复核（2026-11-08）

## 2026-11-09 过度提示续修

本次从 `650666c` 接续，针对用户反馈重新读实际显示文案，不把“有说明”当作必须常驻的理由。范围是经营看板、利润、外派与接单、商品编辑、配送详情、顾客申请记录，以及两端共用错误展示。站间结算也复核，但付款双方、金额、实际交付与登记的区别属于操作依据，予以保留。未宣称再次逐屏验证全部页面。

- 看板的比较期间只在标题显示一次，各指标保留基准值；零基准改为“新增 / 持平 / 减少”，没有历史订单仍单独标“无对比记录”，不制造百分比。三段统计说明缩短并按需展开，移除指标旁常驻的现金区别警告。
- 利润的已确认期间只在金额卡显示一次，收入解释收进现有分组帮助；成本缺失仍是实际状态，不假填利润。说明加载失败只提示重试，不由页面临时拼一段计算规则。
- 外派统一提示去掉“建议拒单、口径不清、共同承担”等责任式话术，保留原站桶/押金账、双方交接确认与禁止入抢单池。调度帮助用“配送进度”替代“履约”，用一句话解释桶折款；退回、撤回和取消仍说明实际安排或不可恢复后果，不重复泛泛谨慎提示。
- 商品模板与示意图不重复品牌/定价免责；“只读”改为“不可修改”，水票设置提示缩短。客户期望送达不再在备注帮助里重复解释。顾客申请记录只说明本人申请及进度，不额外强调“不会办理新申请”。
- 现金押金去掉“系统无法核实、纠纷双方自行解决”的责任式提示，只保留水站当面收取、登记和保留收据。水站确认收款后才生效的原有条件不变，模拟付款提示仍如实区分真实资金。
- 两端错误展示过滤中文内部排障词，原始错误、业务拒绝标记及未知结果恢复语义保持原样。真实退款、收款、注销及结果未知时的防重复提示保留，协议正文和版本未修改。

本轮验证：各变更的页面/请求 mock 与 WXML 渲染专项通过；全量 JS 首次运行发现两处旧文案预期，已只更新文案断言并保留确认/提交次数等行为断言，相关套件重跑通过。最终全量 JS 255 套 / 5137 项通过，随后追加的现金押金文字与同步注释另由 13 项文案测试核实。两端文本、require、WXML/WXSS、注释与全部 JS 语法检查通过。后端编译与无数据库比较单测在 Gradle 首次下载即因网络不可达中止，未进入编译或测试、未碰数据库；后端结果需核实本机缓存执行或本次精确提交的正式 CI。浏览器投影与微信原生/真机仍区分记录。

## 范围与基线

- 仓库 `rumiasu/AquaFlow`，既有开发分支 `codex/stage-handoff-20261004`，起点 `3f9fc34a2b55d55dfdc805c60f010cb97829b14c`。云端独立检出起初无工作区改动。
- 目标：准确传达业务事实，保留资金、授权、状态和未知结果边界；去掉无依据承诺、内部术语、重复教学与过度提示。不是为统一措辞机械替换。
- 不改变接口、数据结构、校验条件、金额计算、状态转换或判权；不触数据库、部署或上传小程序。协议正文、版本和生成镜像保持原样。
- 文案审查覆盖源码中的页面、组件、导航、空态、弹窗、toast、无障碍标签、恢复提示与后端返回文字；这不等于真机逐屏体验或生产接口全路径实测。

## 逐页覆盖

以下每个已注册页面的 JS、WXML、JSON、WXSS 均已纳入检查。未改处因原文准确简明、属于必要授权/资金保护，或无可见文案而保留。

### miniapp-user（27 个注册页面）

- `pages/home/index`
- `pages/home/notice`
- `pages/order/list`
- `pages/order/detail`
- `pages/order/create`
- `pages/order/success`
- `pages/mine/index`
- `pages/login/index`
- `pages/mine/about`
- `pages/mine/edit`
- `pages/mine/agreement/index`
- `pages/mine/account-data/index`
- `pages/mine/company`
- `pages/mine/asset-claims/index`
- `pages/address/list`
- `pages/address/edit`
- `pages/barrel/index`
- `pages/barrel/purchase`
- `pages/deposit/records/index`
- `pages/exception/list/list`
- `pages/service/index`
- `pages/shop/index`
- `pages/template/index`
- `pages/ticket/index`
- `pages/notice/index`
- `pages/payment/records`
- `pages/case-handling/index`

### miniapp-delivery（48 个注册页面）

- `pages/login/index`
- `pages/mine/agreement/index`
- `pages/mine/account-data/index`
- `pages/role-select/index`
- `pages/bind-wait/index`
- `pages/home/index`
- `pages/order/detail`
- `pages/order/complete`
- `pages/mine/index`
- `pages/coordination/index`
- `pages/barrel-records/index`
- `pages/my-problem-receipts/index`
- `pages/my-earnings/index`
- `pages/history/index`
- `pages/transfer/index`
- `pages/report/index`
- `pages/settings/index`
- `pages/mine/edit`
- `pages/station-mgmt/create-station/index`
- `pages/station-mgmt/apply-bind/index`
- `pages/station-mgmt/index`
- `pages/station-mgmt/business-waiting/index`
- `pages/station-mgmt/dashboard/index`
- `pages/station-mgmt/orders/index`
- `pages/station-mgmt/customers/index`
- `pages/station-mgmt/customers/detail/index`
- `pages/station-mgmt/customers/adjust/index`
- `pages/station-mgmt/customers/adjust/edit/index`
- `pages/station-mgmt/customers/adjust/detail/index`
- `pages/station-mgmt/products/index`
- `pages/station-mgmt/station-status/index`
- `pages/station-mgmt/station-info/index`
- `pages/station-mgmt/delivery-config/index`
- `pages/station-mgmt/place-order/index`
- `pages/station-mgmt/receivables/index`
- `pages/station-mgmt/inter-station/index`
- `pages/station-mgmt/payroll/index`
- `pages/station-mgmt/ticket-packages/index`
- `pages/station-mgmt/gross-profit/index`
- `pages/station-mgmt/barrel-return/index`
- `pages/station-mgmt/owed-barrels/index`
- `pages/station-mgmt/exceptions/index`
- `pages/station-mgmt/customer-feedback/index`
- `pages/station-mgmt/payments/index`
- `pages/station-mgmt/staff/index`
- `pages/station-mgmt/staff/profile/index`
- `pages/station-mgmt/asset-claims/index`
- `pages/case-handling/index`

## 共有与动态文字覆盖

- 顾客端全部12个组件、37个utils文件、20个API文件，以及app、store、服务、配置和共有样式；员工端全部6个组件、API、utils、Behavior、模板、自定义导航和配置。协议镜像仅只读检查。
- 后端以源码为准筛查全部480个Java文件中的中文字符串，按controller、DTO、业务服务、常量、VO、鉴权、异常处理及SQL返回展示值逐类核对；日志、启动保护、SQL条件和审计标记保留诊断含义，不当作界面文案改写。
- 后端DTO仅补充或校准校验message，不增删约束；异常处理保留原错误分类、日志与告警路径。HTTP方法诊断保留项目原有要求。
- 二进制图片、品牌/包装素材未修改或重新生成，未作像素级文案辨读与真机视觉验收。历史archive不纳入维护范围。

## 实质修正

- 历史认领：累计交过的押金总额改为当前历史押金余额；拥有桶数与实际在手区分，不要求实物盘点、不重复收款。
- 支付与配送：收款完成不再等同配送完成；新押金交付守卫明确收齐本单应收款，覆盖水款、押金及其他应收服务费用。
- 退款：去除微信退款数分钟到账等无依据承诺；申请退回、已退回、转给他人分开表达；未知提交与原款恢复提醒保留。
- 调账：扣减桶资产的试算不再称实际退款。该路径只扣桶批次及对应资产，不执行资金交付。
- 水票：对应水费与另付服务费分开表达；不足预览不再把未覆盖商品数量冒充缺少票数；利润帮助按实际服务费收入说明。
- 模拟付款：明确无真实扣款，不把模拟微信渠道当可追溯的真实押金收款。线下押金无法由系统核验、收据及纠纷责任提示保留。
- 工资与经营：不再保证可取消订单必无工钱；无计件配置是未生成流水，不等于实际不欠工钱；员工登记时间不写成入职时间。
- 营业与资料：休息/预约不承诺明日处理；已保存地址缺地图位置的后果准确说明。通用隐私弹窗不把所有操作都说成定位。匿名反馈保留填写联系方式仍可见的事实。
- 删除两行纯装饰三步流程，其他重复指导缩短；常用订水清单不暗示自动定期下单。删除证据见[登记表](删除登记表.md#2026-11-08-两行静态三步流程装饰)。
- 失败反馈：内部字段/凭据名改为可理解提示；事务未生效不再写“回滚”，并明确是登记未生效，避免误导线下现金也已被撤回。

## 验证

- JS流程回归：完整254/254套、5102计数项、0失败，逐套核实完成哨兵，退出码0。仅调整41个测试文件中确认为文案变化的预期，保留请求次数、身份、金额、状态、防重复、原结果核对与对比度断言。全量产生的4个sdk合成夹具已还原基线字节。
- `check-miniapp-text`：524文件、4811文本行通过，UTF-8无BOM、无编码损坏、WXML检查范围内无开发词。
- `check-miniapp-requires`：2个包、217个JS、590个require闭包通过。两端JS语法检查及站长JSON解析通过。
- `sync-agreement-drafts.js --check`通过；协议生成件无改动。`git diff --check`通过。
- 后端在本机独立临时clone以JDK17、Gradle9.4.1既有缓存离线补验，未触原工作区或数据库：精确`1e734be496ebb344eb60c404c4d3429cc8412728`的`compileJava compileTestJava`通过；17类280项首轮279通过、1项旧文案断言失败。随后`dfc1ce0e1f63e1075997a74bbff778b9c7b4cad4`仅同步该测试一行中的三个完整提示，`compileTestJava`实际重编译及`ManagerFeedbackRepairTest`全12项通过，0失败/错误/跳过。其余16类268项沿用父提交通过结果，未重复跑全集；生产源码与父提交相同。云端因缺JDK17/Gradle且下载站点不可达未作后端编译。
- 本机未运行会清表的数据库集成测试。正式CI在一次性MySQL容器执行数据库集成验证，结果见下方续验记录；无库单测不能替代集成验证。
- 未执行微信开发者工具编译、真机视觉、真实微信收退款、线上部署或真实业务数据验证。不得将静态与模拟回归称作这些项目已通过。

## 2026-11-09 正式CI续验记录

本节冻结于本轮测试修正提交时；待验修正不记为已通过，最终完整结果以对应提交的正式CI为准。

- [CI #236](https://github.com/rumiasu/AquaFlow/actions/runs/42536589400)对应`4d0d7a477470ef2a19869d3d6f3cb1e7688c9f0b`：298类、2426项，2405通过、21失败，0错误、0跳过。后端步骤失败，后续静态、JS、bootJar及包检查未执行；没有可交付后端包。
- 已逐一读取14个失败类的源码、实际响应和生产提示正本，21项均停在旧文案contains断言。修正同方法后续尚未执行的同类旧词，保留全部权限来源、身份隔离、金额数量、状态不变、无写入、渠道拒绝与资金不重复办理断言。仅改测试文字及对应说明，不改生产代码、测试数据或预期业务结果。
- `RefundFeedbackIntegrationTest.barrelReturnUsesAssetStationAndWrongRecordTypeCannotBeLinked`故意只插入退还记录而无拥有桶批次；`available=false`、`legacy=true`是原有预期且已通过。失败点是旧词“超过拥有的桶权益数”，新断言钉住实际“退桶数(1)超过拥有桶数(0)”。不补资产、不放宽退款资格，后续越权拒绝、资金桶账不变及不产生系统告警检查仍保留。
- 涉及测试类：AuthContextLifecycle、AuthzIsolation、BarrelOwed、BarrelRefundDelivery、CrossStationPoolRiskAndProfileIsolation、CustomerPrivilege、InventoryLockProtocol、ManagerPayrollListAndDirections、MockWechatPay、PaymentReadEndpoints、RefundFeedback、StationExceptionConfig、StationSetupGuide、TicketPayPreview（均为IntegrationTest）。
- 本轮推前仅完成逐项语义与差异核对、UTF-8/无BOM及非字符串token不变检查；云端缺JDK17/Gradle/MySQL，未宣称本地执行通过。经本次明确授权，测试修正正常快进至原开发分支，由既有正式CI在隔离MySQL重新执行全部用例，包含首次失败断言之后尚未执行的检查。不改CI、不跳过测试、不动本机或真实业务库。

## 协议待决定（正文未改）

TODO(待拍板)：是否授权按现行v90业务校正协议草稿，并通过版本机制生成新摘要和镜像？本次不替用户起草或确认新的法律约定；沿用既有C-09协议机制。选择暂不更新则保留当前协议及其下述漂移；选择更新需单独审阅正文、生成新版本并复核同意记录。

- `AquaFlow-backend/src/main/resources/agreements/customer-user-draft-20261009-r3.json:21`：“用票成功计为已付”未区分v90另收服务费实收前的整单状态，建议另行审阅为水费核销与余款确认两个事实。
- `AquaFlow-backend/src/main/resources/agreements/customer-privacy-draft-20261008-r2.json:21`：“本人注销前检查检查”重复；同段业务凭据/台账等术语可在不减损含义前提下另行精简。
- 两端 `data/agreement-drafts.js/json` 是生成镜像，不能直接手改；后续获准修改正本后用 `node scripts/sync-agreement-drafts.js` 生成并 `--check` 验证。
