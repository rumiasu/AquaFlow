# f4 极端交互只读复核（2026-10-24）

只读初核时基线与远端均为 `f4b9d676a856ed9d5b214525ae710e400a1e165b`，开发分支 `codex/fix-fee-drawer-layout-20261018`。前文是只读快照，证据行号固定于f4；用户随后授权集中修复，最终处置见末节。未部署。

本轮收束 **7项P1、3项P2；没有证实P0**。建议统一交付前处理P1对应的核心路径，P2不挡整包、不为它们分批部署。严重性按实际影响评估，不用条数或总分替代验收。

## 范围与去重

按长表单/弹层/固定底栏、图片与草稿恢复、未知结果、返回重进和身份变化的共享结构分三路只读核对。根任务已重读最严重链并独立复跑处理器反例。没有机械重读全站，也没有重跑旧412项回归。

已对照 `qwen-interaction-review-20261024.md` 与 `product-sheet-repair-20261024.md`：f4已修商品滚动结构、固定保存、恢复卡叠压、本地选图预览不列新发现；既有回桶、收款、WB08、登录和template安全区亦不重复计算。新商品页发现是此前未覆盖的库存/多步保存状态，不宣称旧视觉修复失效。

| 编号 | 优先级 | 当前问题 | 证据性质 | 是否挡对应路径交付 |
|---|---|---|---|---|
| EX01 | P1 | A商品保存未结束时打开B，B档位被发往A | actual-handler-counterexample + backend-source | 是，本站商品设置保存路径。 |
| EX02 | P1 | 确认入库可连点或未知后重发，后端逐次累加 | actual-handler-counterexample + actual-SQL-source | 是，库存入库路径。 |
| EX03 | P1 | 旧未知图片恢复入口能在调整提交中更换财务请求键 | actual-visible-handler-counterexample + synthetic-idempotent-backend | 是，有旧图片未知记录的调整单创建路径。 |
| EX04 | P1 | 常用订单点击商品选项会修改另一行 | actual-WXML-binding-and-handler-counterexample | 是，常用订单编辑。 |
| EX05 | P1 | 两端首次隐私授权组件未满足平台事件合同 | verified-source-contract-mismatch; native-outcome-unverified | 是，首次隐私授权路径；不将既有授权用户的可用性判失败。 |
| EX06 | P1 | 创建商品成功后档位失败，重试仍新建而无法恢复 | actual-handler-counterexample + schema-verified-duplicate-guard | 是，新商品带水票档位的一次保存路径。 |
| EX07 | P1 | 旧地图选点回调可用新会话保存旧站坐标 | actual-page-api-request-counterexample under synthetic-session-switch | 是，身份变化下站点坐标编辑的安全边界；普通同会话地图不泛判失败。 |
| EX08 | P2 | 公告发布结果未知后可再次创建同一公告 | actual-handler-counterexample + backend-source | 否，不为此拆分整包发布。 |
| EX09 | P2 | 普通顾客反馈未知后正文不变仍可再次提交 | actual-handler-counterexample | 否，可延期。 |
| EX10 | P2 | 键盘与大字号叠加长错误，常用订单底栏再次溢出 | static-layout-risk-reproduced; native-unverified | 否，有限P2；建议低成本同批处理，不单独发版。 |

## EX01（P1）：A商品保存未结束时打开B，B档位被发往A

路由/组件：`miniapp-delivery/pages/station-mgmt/products/index`。

**用户场景**：A开启水票并已读取档位，点击保存；请求未结束时取消弹层、打开B；A请求随后返回。

**缺陷**：closeSetting/onOpenSetting没有保存期间的目标锁。onSaveSetting捕获A的id，但await后savePkgRows读取当前页面B的pkgRows。

**当前源码证据**：`miniapp-delivery/pages/station-mgmt/products/index.js:328`；`miniapp-delivery/pages/station-mgmt/products/index.js:366`；`miniapp-delivery/pages/station-mgmt/products/index.js:457`；`miniapp-delivery/pages/station-mgmt/products/index.js:614`；`miniapp-delivery/pages/station-mgmt/products/index.wxml:238`；`AquaFlow-backend/src/main/java/com/example/aquaflow/controller/TicketPackageController.java:92`。

**反例与边界**：实际Page处理器反例：A=41原档10张/100元，B=42档50张/150元，最终发出{productId:41,qty:50,price:150}，B弹层还被旧请求关闭。后端按传入productId/qty做本站upsert；没有执行真实价格写入或购票。

**用户影响**：同站不同商品的水票价目配置错绑，后续顾客可能看到错误档位。

**最小修法**：保存前捕获不可变商品id、完整档位快照和页面周期；保存期间关闭/换目标入口守卫；旧请求不得读取新弹层数据或关闭它。

**交付判断**：是，本站商品设置保存路径。 证据产物：`root-probes.json:crossProductSave`。

## EX02（P1）：确认入库可连点或未知后重发，后端逐次累加

路由/组件：`miniapp-delivery/pages/station-mgmt/products/index`。

**用户场景**：库存10，输入入库3；弱网中连续点两次确认，或首次超时后原输入不变再点。

**缺陷**：onSubmitStock/doInbound没有在途或原请求未知锁，WXML入库按钮也无对应禁用。后台行锁保护并发一致性，不提供同一用户意图去重。

**当前源码证据**：`miniapp-delivery/pages/station-mgmt/products/index.wxml:153`；`miniapp-delivery/pages/station-mgmt/products/index.js:508`；`miniapp-delivery/pages/station-mgmt/products/index.js:552`；`miniapp-delivery/api/station-mgmt.js:240`；`AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/InventoryServiceImpl.java:64`；`AquaFlow-backend/src/main/java/com/example/aquaflow/mapper/InventoryMapper.java:65`。

**反例与边界**：真实处理器在首请求未结束时发出2份相同{productId:42,quantity:3}，模拟成功后有2次入库成功提示；requestMayHaveOccurred超时后再次点击也发出第2份。实际SQL每次quantity=quantity+传入量且每次写INBOUND。库存16是源码推导，未在真实库观测。

**用户影响**：一次物理入库可能被记两次，库存与实物不符并影响缺货补位。

**最小修法**：订单式单飞保护应覆盖库存目标；未知结果保留原意图并核实，不能仅解锁原按钮。若支持可靠重试，应有服务端原请求编号，而非仅加前端loading。

**交付判断**：是，库存入库路径。 证据产物：`root-probes.json:doubleInbound/unknownInbound`。

## EX03（P1）：旧未知图片恢复入口能在调整提交中更换财务请求键

路由/组件：`miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index`。

**用户场景**：同一客户存在旧unknown图片记录；另开当前图片草稿并上传有效证据；试算并提交10元押金调整，在请求途中点击可见的另开独立图片草稿。

**缺陷**：共享wrapper仅对product检查忙状态。adjustment分支清证据并resetPreview，触发clientToken换新并清_submittedIntent；首请求未知后可用新键再提交。

**当前源码证据**：`miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.wxml:134`；`miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js:155`；`miniapp-delivery/pages/station-mgmt/customers/adjust/edit/index.js:471`；`miniapp-delivery/utils/upload-recovery-page.js:119`；`AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/StationAdjustmentServiceImpl.java:137`；`AquaFlow-backend/sql/schema.sql:989`。

**反例与边界**：独立复跑真实Page、wrapper、upload-store；实际渲染确认按钮可见且未禁用，showLoading无mask。合成服务按真实clientToken幂等规则得到2张各10元PENDING，第一张带证据、第二张证据空；assetExecutionCalls=0、realNetworkCalls=0。

**用户影响**：可能产生重复待执行财务单并丢失新单证据；不等于已双加/双扣资产。

**最小修法**：所有图片草稿入口尊重formLocked及原提交未知状态；财务意图在途中/未知时不得因图片变化换键或改原内容，先沿原键核实。

**交付判断**：是，有旧图片未知记录的调整单创建路径。 证据产物：`adjustment-probe.js / adjustment-probe.log`。

## EX04（P1）：常用订单点击商品选项会修改另一行

路由/组件：`miniapp-user/pages/template/index`。

**用户场景**：表单两行；在第1行点第2个商品选项，或在第2行点第1个选项。

**缺陷**：内外wx:for均默认index，内层商品序号覆盖表单行索引，data-index传错。

**当前源码证据**：`miniapp-user/pages/template/index.wxml:80`；`miniapp-user/pages/template/index.wxml:85`；`miniapp-user/pages/template/index.wxml:89`；`miniapp-user/pages/template/index.js:206`。

**反例与边界**：真实WXML取data-index表达式并调用真实处理器，setData支持数组深路径：row0/option1写入form.items[1]；row1/option0写入form.items[0]，目标行保持旧值。0个网络请求。

**用户影响**：用户选的商品与保存的常用订单组合不一致；只有一行时还可能写入多余不完整行。

**最小修法**：显式rowIndex/productIndex，data-index只传rowIndex；处理器检查合法现有行索引。

**交付判断**：是，常用订单编辑。 证据产物：`customer-handler-probes.js / customer-handler-probes.log`。

## EX05（P1）：两端首次隐私授权组件未满足平台事件合同

路由/组件：`miniapp-user/components/PrivacyPopup + miniapp-delivery/components/PrivacyPopup`。

**用户场景**：尚未同意隐私协议的新用户触发需隐私授权的定位等操作；点击自绘同意或拒绝。

**缺陷**：resolve仅传buttonId，没有必填event；同意入口缺原生agreePrivacyAuthorization按钮和对应事件。弹窗却先被关闭。

**当前源码证据**：`miniapp-user/components/PrivacyPopup/index.js:22`；`miniapp-user/components/PrivacyPopup/index.js:30`；`miniapp-user/components/PrivacyPopup/index.wxml:11`；`miniapp-delivery/components/PrivacyPopup/index.js:41`；`miniapp-delivery/components/PrivacyPopup/index.js:49`；`miniapp-delivery/components/PrivacyPopup/index.wxml:16`；`miniapp-user/app.json:63`；`miniapp-delivery/app.json:84`。

**反例与边界**：实际Component合同探针确认两端同意/拒绝响应均缺event且不存在原生同意按钮；腾讯官方当前API源码文档要求agree/disagree和原生按钮事件。两端libVersion=3.17.0且页面实际挂载。合同不匹配已证实；模拟pending不是微信实测，原生非法响应具体会挂起、失败或抛错未验。已授权用户不触发，不能据此推断所有上传都坏。

**用户影响**：首次授权定位/相关隐私操作可能不能正常继续或取消；不是登录失败，也不是当前COS权限根因。

**最小修法**：按官方合同使用原生同意按钮事件和明确agree/disagree响应；补首次同意、拒绝、返回重进的原生验证。

**交付判断**：是，首次隐私授权路径；不将既有授权用户的可用性判失败。 证据产物：`privacy-contract-probe.js / privacy-contract-probe.log`。

## EX06（P1）：创建商品成功后档位失败，重试仍新建而无法恢复

路由/组件：`miniapp-delivery/pages/station-mgmt/products/index`。

**用户场景**：新建商品并配置水票档位；商品创建已返回id，随后档位保存失败；原弹层再次点击创建。

**缺陷**：newId仅在try内使用，没有转成已创建商品的编辑/继续状态。catch只显示保存失败，isAdd=true/editId=null保持。

**当前源码证据**：`miniapp-delivery/pages/station-mgmt/products/index.js:1022`；`miniapp-delivery/pages/station-mgmt/products/index.js:1041`；`miniapp-delivery/pages/station-mgmt/products/index.js:457`；`AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/CatalogServiceImpl.java:195`；`AquaFlow-backend/sql/schema.sql:639`。

**反例与边界**：真实处理器：创建返回101后档位失败，仍showEdit=true/isAdd=true/editId=null；再次保存第2次调用create而非继续101的档位。合成端按真实站内唯一约束返回数据已存在。不能说同名同品牌同规格会必然生成两条，数据库uk_product_station会阻止。

**用户影响**：商品已建但用户只见失败，正常重试不能补完档位；需退出重进寻找已建商品。

**最小修法**：确认创建返回id后保留该阶段与原id，后续只继续保存档位；部分成功文案必须准确。创建本身未知时不能按名称猜匹配。

**交付判断**：是，新商品带水票档位的一次保存路径。 证据产物：`root-probes.json:partialCreate`。

## EX07（P1）：旧地图选点回调可用新会话保存旧站坐标

路由/组件：`miniapp-delivery/pages/station-mgmt/station-info/index`。

**用户场景**：A站打开地图选点；回调返回前应用会话已变为B站；旧坐标回调继续运行。此为极端身份变化时序，非普通每次地图操作。

**缺陷**：原生回调未绑定发起时会话、站别和选点周期；API在回调发起时捕获B的凭据，后台/mine按当前B写。

**当前源码证据**：`miniapp-delivery/pages/station-mgmt/station-info/index.js:126`；`miniapp-delivery/api/station-mgmt.js:215`；`miniapp-delivery/utils/request.js:49`；`AquaFlow-backend/src/main/java/com/example/aquaflow/controller/StationController.java:230`；`AquaFlow-backend/src/main/java/com/example/aquaflow/controller/StationController.java:242`。

**反例与边界**：独立复跑实际Page、api、request，仅transport离线截获且执行beforeDispatch：旧A坐标31.2/121.4配合synthetic-B凭据发PUT/mine/coordinates。没有真实账号切换或写库；微信是否产生同样完整事件时序未验。

**用户影响**：旧意图可能错误改写当前新站位置，影响配送范围定位；不代表绕过后台权限。

**最小修法**：打开地图前捕获原身份、站和选点编号；回调核对仍属于同一意图再发API，正确允许普通地图hide/show和合法续期。必要时后台校验预期站别。

**交付判断**：是，身份变化下站点坐标编辑的安全边界；普通同会话地图不泛判失败。 证据产物：`manager-probe.js / manager-probe.log`。

## EX08（P2）：公告发布结果未知后可再次创建同一公告

路由/组件：`miniapp-delivery/pages/station-mgmt/station-status/index`。

**用户场景**：发布公告首次已可能落库但回包超时，保留原表单再次点发布。

**缺陷**：catch只提示并finally恢复saving=false，无原发布意图/回执核实；后台每次insert。

**当前源码证据**：`miniapp-delivery/pages/station-mgmt/station-status/index.js:377`；`miniapp-delivery/pages/station-mgmt/station-status/index.js:392`；`miniapp-delivery/pages/station-mgmt/station-status/index.wxml:118`；`AquaFlow-backend/src/main/java/com/example/aquaflow/controller/NoticeController.java:72`。

**反例与边界**：实际处理器反例在requestMayHaveOccurred=true后发2次create、0次核实读取。后台未真实执行，重复已发布公告是首次实际已成功时的条件性后果。

**用户影响**：可能出现两份相同公开通知，造成重复阅读和维护；不涉及资产入账。

**最小修法**：未知时保留原发布意图并引导核实；可靠重试采用原编号，不能只按标题猜成功或静默重建。

**交付判断**：否，不为此拆分整包发布。 证据产物：`manager-probe.js / manager-probe.log`。

## EX09（P2）：普通顾客反馈未知后正文不变仍可再次提交

路由/组件：`miniapp-user/pages/service/index`。

**用户场景**：普通反馈超时，保留原正文再点提交；不包括有独立原意图的退款说明。

**缺陷**：已有请先查看历史的提醒，但finally解锁，未区分同一次未知意图与新反馈；后台每次insert。

**当前源码证据**：`miniapp-user/pages/service/index.js:351`；`miniapp-user/pages/service/index.js:370`；`miniapp-user/pages/service/index.wxml:103`；`AquaFlow-backend/src/main/java/com/example/aquaflow/controller/FeedbackController.java:66`。

**反例与边界**：真实处理器同正文2次提交，requestMayHaveOccurred=true后submitting=false，记录相同POST体2份；0个真实网络请求。

**用户影响**：可能重复反馈并增加处理工作。已有警示降低风险，不上升为P1或重报已修WB08配送反馈。

**最小修法**：保留本次普通反馈意图；需要可靠重试时加原编号回执或明确的新反馈操作，不误堵后续真实新事件。

**交付判断**：否，可延期。 证据产物：`customer-handler-probes.js / customer-handler-probes.log`。

## EX10（P2）：键盘与大字号叠加长错误，常用订单底栏再次溢出

路由/组件：`miniapp-user/pages/template/index`。

**用户场景**：320×308模拟键盘可视区、1.5倍CSS字号、69字saveError；有长商品编号/地址。

**缺陷**：saveError在flex-shrink:0的footer中且无高度界限，长错误挤占正文并把按钮推到视口外。不是旧85vh/65vh简单相加：末尾CSS已覆盖为flex/88vh。

**当前源码证据**：`miniapp-user/pages/template/index.wxml:133`；`miniapp-user/pages/template/index.wxss:369`；`miniapp-user/pages/template/index.wxss:376`。

**反例与边界**：root独立复跑真实WXML/WXSS静态Chromium投影并view：极端组合确认/取消bottom336.86>308，正文20.47px；320×568或1.3倍字号对照按钮可达。仅内存移动错误到正文，确认bottom301.19、正文156.47px。微信键盘和系统字体缩放未验。

**用户影响**：极端错误恢复时保存/取消可能不可达；不能泛称普通模板弹层不能滚动。

**最小修法**：将长错误移入滚动正文，底栏只保留操作，限制头/底栏整体高度并复核原生短屏。

**交付判断**：否，有限P2；建议低成本同批处理，不单独发版。 证据产物：`template-layout.json / template-keyboard-large-error.png`。

## 平台合同依据与验证边界

EX05已独立查询[腾讯官方API源码文档](https://raw.githubusercontent.com/wechat-miniprogram/api-typings/master/types/wx/lib.wx.api.d.ts)的 onNeedPrivacyAuthorization 段：要求明确操作事件及原生同意按钮事件后响应；已同意过的用户不再触发该路径。我们证实的是实现不满足合同，不把合成探针的pending状态说成微信原生必然行为，也不据此诊断当前手机COS故障。

强证据的实际范围：真实Page/Component/WXML绑定或真实API/request处理器，离线替换transport及业务回执；没有真实登录、生产HTTP、真实DB、支付、退款、COS读写或权限操作。EX03只产生合成待执行单，不是资产双扣/双加；EX02的真实库存后果来自实际SQL推导；EX06已核对数据库唯一约束，因此不是必然重复建品。

EX10静态投影已实际view，7个对照场景均保存在证据目录。1.3倍字号/普通视口可达，极端叠加才溢出。CSS字号放大和缩小视口并不等于微信系统字号/键盘；没有任何一条被称为真机全验，也不作72页视觉结论。未把单个catchtouchmove、hover数量、早期CSS声明或纯审美偏好当作缺陷。

## 可复跑证据与停止条件

所有探针及原始输出复制于 `/workspace/af-extreme-review-evidence-20261024/`（非业务目录、未入库）；结构化问题、反例、源码位置、最小建议和交付判断另见同名JSON。

以下是只读初核的f4反例脚本，需配合该基线复跑；修复后请使用末节正式回归，不将旧脚本针对当前源码的预期反例失败当成新缺陷。

- `root-probes.cjs`：入库连点/未知重发、创建部分成功、跨商品保存目标错绑。
- `adjustment-probe.js`：真实旧unknown记录、可见恢复按钮、途中换键和两待执行单；完成哨兵 `ADJUSTMENT_DRAFT_REPLAY_PROBE_OK`。
- `customer-handler-probes.js`：实际嵌套列表绑定与普通反馈；完成哨兵 `AQUAFLOW_CUSTOMER_EDGE_PROBES_OK`。
- `manager-probe.js`：实际Page/API/request的旧地图回调及公告未知；完成哨兵 `READONLY_MANAGER_PROBES_OK`。
- `privacy-contract-probe.js`：两端实际组件响应参数；完成哨兵 `PRIVACY_CONTRACT_PROBE_OK 2`。
- `template-layout.cjs/.json`：真实WXML/WXSS的静态几何对照与内存反事实；仅投影，非微信编译或点击。

只读停止条件已达到：三路核查返回，最严重结论逐条核源码并由根任务独立复跑，一轮交叉复核完成。没有继续寻找无限新增项。该时点问题尚未修复；后续授权处置如下。

## 集中修复与单一候选（2026-10-24）

本阶段仅处理前述7项P1，顺手修EX10，不扩模块或继续审计。候选以包含本报告的提交为准，保留f4回退点；本线程正常快进同步开发分支，不部署、上传或切流，016线上配置版不动。

| 项目 | 当前处置 |
|---|---|
| EX01 已修 | 保存开始即冻结原商品、表单、档位和身份，阻止途中关闭/换目标；每段异步写入与返回都核对原范围，后续不读取新弹层档位、不关闭新弹层。 |
| EX02 已修 | 入库先持久保存原编号/数量，单飞并明确确认“继续原入库”。后台按站、员工、编号与内容去重，库存、流水和回执同事务。先锁站行，READ_COMMITTED读取已提交回执，避免增加审计扫描范围锁。原键重试不加第二次；同编号改内容、损坏/重复回执拒绝。首发同时校验通用库/本站商品，正向回执优先于后续商品状态。 |
| EX03 已修 | 调整单途中/未知时，全部图片草稿入口尊重财务锁，原证据、body与clientToken保留。恢复确认明确“已有返回原单，否则按原键原内容创建一次待执行单”，不是伪装只读查询；后台原有原键同内容去重保持。 |
| EX04 已修 | 外层行、内层商品分别命名索引；稳定rowKey与行边界/键校验，旧行事件不再写入另一行；保存只发商品与数量。 |
| EX05 已修 | 同意使用原生隐私按钮事件，再回应agree；拒绝明确disagree。页面返回重新绑定当前组件的稳定监听，卸载旧监听失效，一次明确选择回应多个pending，离页取消尚未完成的请求而不授予同意；已有授权用户不重复要求授权。没有改登录流程。 |
| EX06 已修指定路径 | 创建得到可信ID立即保存原ID、原档位、进度游标，失败/返回后只续原商品，不重复create。创建本身没有可信ID时继续明确待核实，不根据名称猜成功，不阻碍已有商品编辑；该边界仍需人工核实。 |
| EX07 已修 | 地图选点捕获发起身份/站别/周期，写入前及补地址第二次写前复核；失效回调不写。正常原生地图hide/show与同身份token续期继续有效。 |
| EX10 已修 | 长错误移入滚动正文，底栏只保留操作。实际新版源码静态投影中原极端场景按钮bottom为301.19px，小于308px视口；仍非原生证明。 |
| EX08、EX09 延期 | 公告和普通顾客反馈的未知后重复风险仍存在。它们需要各自的原编号/回执协议，不涉及本轮资产写入；不为两个P2扩协议或分批发版。已有反馈核实警示保留，不能称为已幂等。 |

### 关键兼容与恢复契约

新员工端带编号入库只走`POST /api/inventory/inbound-with-receipt`，需要匹配`clientRequestId/stationId/items/status=APPLIED`才清理原记录；收到成功码但无完整回执不宣称已确认。独立新路径使旧后端返回404，避免旧累加端点忽略编号后先执行、升级后再执行一次。原`/inbound`无编号调用兼容保留，**不保证旧客户端重试去重**；须同批更新后端与员工端。

没有新表、列、索引或环境变量。复用既有`audit_log`的`INVENTORY_INBOUND / RECEIPT`行作为持久业务凭据，必须与库存流水一并长期保留，不能按普通审计清理。配置/数据库未操作；本次仅核源码schema字段兼容，没有连接016线上核库。部署契约已同步`docs/operations/01-部署与运维.md`。

### 实际验证与交付边界

- 最终19组受影响前端组合 **423项通过**，其中4个新增套件为商品19、入库意图8、调整/地图22、模板/隐私21。不是全站/72页验收。见[组合结果](extreme-fix-verification-20261024/frontend-results.json)。
- 商品与调整组精确f4负对照分别仅1/19、4/22通过；模板/隐私原15项负对照3/15通过，后补6个监听归属/并发授权/离页取消检查另行通过。主要反例是实际错目标请求、错误行与额外行、重复提交和财务换键；不把新增字段或方法缺失本身当成业务实锤。见[负对照记录](extreme-fix-verification-20261024/negative-controls.json)。
- **14项后端检查通过**：10个入库服务/MVC合同与4个分层门禁。另用实际Mapper、库存/流水/回执服务和Spring事务管理器，在专用内存H2中通过**10条断言**：同键并发只加一次、原数量变更拒绝、回执写失败整体回滚、原键重试、损坏回执保守拒绝；旧无键负对照确实增加两次。没有真实业务库双改或生产MySQL并发证明。见[后端结果](extreme-fix-verification-20261024/backend-results.json)、[SQL记录](extreme-fix-verification-20261024/sql-probe.log)。
- 当前工作区全部439个Java主源、224个Java测试源编译通过，使用JDK21的source/target17及参数元数据；不是Java17运行或Gradle/CI通过声明。186个小程序JS解析失败0。差异、BOM、WXML绑定及条件链检查通过。
- 保留f4已修商品图、普利思永久HTTPS映射及长表单结构；本轮未修改COS解析、权限或凭据。旧图片/模板与四页上传生命周期检查在组合中继续通过。只为新增业务恢复存储给离线夹具补精确命名空间，凭据读取仍拒绝；另修正phase2原有HTTP503文案断言误配，f4也会失败，未为该测试改产品请求文案。
- 当前模板布局静态投影7个对照均可达，原1.5倍字/长错误/308px场景已实际view；[几何](extreme-fix-verification-20261024/template-layout.json)、[代表投影](extreme-fix-verification-20261024/template-keyboard-large-error.png)。微信原生隐私首次同意/拒绝/返回、手机键盘与真实上传回读仍未验，不能说原生通过。

单次交付需后端、员工小程序、顾客小程序使用同一候选；无需迁移或新增配置。本轮不发布中间版本，不改016，不触真实登录/业务写入/支付/退款。
