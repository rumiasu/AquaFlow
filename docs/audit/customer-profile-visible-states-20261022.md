# 顾客资料五类页面状态验收台账（2026-10-22）

本文件供父整合者与 Astra 只读验收本批页面使用，只记录候选实现、状态证据和未验边界。

基线：`398fc531d669de1055e74408b769d01869cb214b`。候选在 `C:\Users\26626\Documents\Codex\2026-10-22\task-3`；指定源副本仅只读核对，本批未向源副本或 D 盘写入。

范围仅为地址列表、地址编辑、公告列表、企业资料、异常记录五页 JS/WXML/WXSS。未改 home/notice、全局 app/styles/request/api/utils/shared、测试总注册、AppID/env/private、后端、数据库、权限或支付渠道。

依赖现存 `miniapp-user/utils/token.js` 的 `captureSession` / `isCurrentSession`，未改该模块，未新增认证流程。

白卡、浅灰背景和功能蓝；中文正文采用深色反馈，操作触控至少 88rpx。姓名、企业名、标签、备注与公告文本不自动翻译。责任说明和关键金额不折叠；长备注完整放在滚动弹窗。

| 页面 | 可见状态 | 触发 | 候选源码位置 | 改动或保留依据 | 验证与未验 |
| --- | --- | --- | --- | --- | --- |
| 地址列表 | 首次加载/真实空 | 首次进入；接口成功返回 [] | `miniapp-user/pages/address/list.js:34`；`miniapp-user/pages/address/list.wxml:36` | 显式中文加载卡；只有 loaded 且无错误才显示真实空态。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 刷新中/过期 | 返回页面或点击重新读取 | `miniapp-user/pages/address/list.js:34`；`miniapp-user/pages/address/list.wxml:9` | 保留同一登录周期旧数据，标明正在更新；选择与变更等待读取结束。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 读取失败/重试 | 首次或刷新失败，再点击重试 | `miniapp-user/pages/address/list.js:23`；`miniapp-user/pages/address/list.wxml:10` | 失败不会显示无地址；旧列表注明可能已变化；新请求清错，成功替换快照。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 权限/身份变化 | 明确 401/403、权限或登录错误；账号切换 | `miniapp-user/pages/address/list.js:25`；`miniapp-user/pages/address/list.wxml:11` | 给中文说明与登录入口；隐藏不可访问缓存，旧身份页面需退出重进。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 选择/管理入口 | 来源 home 或 order；管理页面 | `miniapp-user/pages/address/list.js:54`；`miniapp-user/pages/address/list.wxml:3` | 两种来源都显示选择提示；读错/刷新中不回写旧地址；管理按钮事件不冒泡重复执行。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 变更中/成功 | 确认删除、设置默认，服务确认成功 | `miniapp-user/pages/address/list.js:75`；`miniapp-user/pages/address/list.wxml:16` | 单次在途锁；明确正在处理与确认成功；再刷新列表，刷新失败保留操作确认及旧列表标识。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 拒绝/结果未知 | 明确业务拒绝；断网、超时、账号周期变化 | `miniapp-user/pages/address/list.js:90`；`miniapp-user/pages/address/list.wxml:18` | 明确拒绝与结果未知分开；未知锁定重复变更，要求先刷新核对。原错误及元数据不改写。 | 定向执行/模板静态核对；未目视 |
| 地址列表 | 异步旧响应 | 请求乱序、隐藏或卸载 | `miniapp-user/pages/address/list.js:34`；`miniapp-user/pages/address/list.wxml:10` | 读取序号和生命周期阻止旧结果覆盖；复用现存 token 登录周期保护缓存归属。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 首次读取/成功 | 带 id 读取原地址 | `miniapp-user/pages/address/edit.js:89`；`miniapp-user/pages/address/edit.wxml:4` | id 匹配且资料对象有效才开放表单；楼层/电梯/经纬度 0 和 null 保持原语义。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 缺失/失败/重试 | null、空对象、回错 id 或读取失败 | `miniapp-user/pages/address/edit.js:97`；`miniapp-user/pages/address/edit.wxml:7` | 缺失原地址属于失败，隐藏表单并在处理函数拒绝保存；重试成功前不能覆盖原地址。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 刷新与输入保留 | 编辑后重试；读取期间输入 | `miniapp-user/pages/address/edit.js:73`；`miniapp-user/pages/address/edit.wxml:5` | 脏表单和输入修订号保护当前输入；读取成功也不覆盖已修改内容。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 保存中/成功 | 提交有效表单，服务明确成功 | `miniapp-user/pages/address/edit.js:257`；`miniapp-user/pages/address/edit.wxml:96` | 在途禁重复提交和输入；成功后保持锁直到返回，卸载取消延迟导航。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 明确拒绝/未知 | 业务明确拒绝；网络无回执或响应不确定 | `miniapp-user/pages/address/edit.js:312`；`miniapp-user/pages/address/edit.wxml:11` | 明确拒绝保留输入可修正；未知保留输入、禁止再次提交，引导返回列表核对，不伪称未保存。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 权限拒绝 | 权限错误或错误账号 | `miniapp-user/pages/address/edit.js:69`；`miniapp-user/pages/address/edit.wxml:7` | 中文说明并给登录入口，不推断当前账号一定是员工。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 定位权限/取消/失败 | 权限拒绝、取消选点或位置获取失败 | `miniapp-user/pages/address/edit.js:167`；`miniapp-user/pages/address/edit.wxml:39` | 保留手填内容；定位绑定发起时登录周期与操作序号。打开地图、回填、失败弹窗、设置跳转、complete 统一校验；卸载/换号作废，正常地图 onHide/onShow 往返保留。权限说明可手填，取消不误报成功。 | 定向执行/模板静态核对；未目视 |
| 地址编辑 | 异步/账号边界 | 乱序读取、隐藏、卸载、登录周期变化 | `miniapp-user/pages/address/edit.js:79`；`miniapp-user/pages/address/edit.wxml:16` | 旧响应不更新；账号变化隐藏旧表单、拒绝继续保存，原写入仍标未知。 | 定向执行/模板静态核对；未目视 |
| 公告列表 | 首次加载/真实空 | 首次进入；成功返回 [] | `miniapp-user/pages/notice/index.js:20`；`miniapp-user/pages/notice/index.wxml:17` | 中文加载；成功空数据才显示无公告。 | 定向执行/模板静态核对；未目视 |
| 公告列表 | 刷新中/失败/重试 | 返回页面、下拉刷新或重新读取 | `miniapp-user/pages/notice/index.js:9`；`miniapp-user/pages/notice/index.wxml:5` | 保留旧内容，标明不是最新；失败不等于空；重试清除本次错误。 | 定向执行/模板静态核对；未目视 |
| 公告列表 | 访问拒绝/异步 | 权限登录错误、账号变化、乱序或隐藏 | `miniapp-user/pages/notice/index.js:11`；`miniapp-user/pages/notice/index.wxml:6` | 中文反馈和登录入口；隐藏不可访问缓存，旧结果不得覆盖当前查询。 | 定向执行/模板静态核对；未目视 |
| 公告列表 | 卡片/详情入口 | 点击公告卡 | `miniapp-user/pages/notice/index.js:43`；`miniapp-user/pages/notice/index.wxml:10` | 系统/水站/活动中文标签；未知类型只称公告；标题保留原文，缺时间标待核实；仍跳转 home/notice，失败给中文反馈。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 首次加载/真实空 | 首次进入；接口明确返回 null | `miniapp-user/pages/mine/company.js:36`；`miniapp-user/pages/mine/company.wxml:48` | 显式加载；成功 null 才提示联系水站登记，保留既有入口职责，不新增业务入口。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 刷新中/失败/重试 | 返回页面、重新读取 | `miniapp-user/pages/mine/company.js:25`；`miniapp-user/pages/mine/company.wxml:5` | 保留旧资料并标可能已变化；失败不清空 info；成功 null 清除旧资料。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 权限/异步/身份 | 权限错误、乱序、隐藏、登录周期变化 | `miniapp-user/pages/mine/company.js:19`；`miniapp-user/pages/mine/company.wxml:6` | 中文拒绝与登录入口；旧账号表单不转属新账号，结果未知不当业务拒绝。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 编辑/输入/取消 | 进入编辑、返回系统界面、取消 | `miniapp-user/pages/mine/company.js:60`；`miniapp-user/pages/mine/company.wxml:17` | 编辑期间 onShow 不回填；取消恢复最近保存快照。名称原文不翻译。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 保存中/成功/拒绝 | 保存当前资料 | `miniapp-user/pages/mine/company.js:81`；`miniapp-user/pages/mine/company.wxml:4` | 在途锁；使用服务返回资料确认成功；明确拒绝保留输入可修正，响应不完整不伪造成功。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 保存结果未知/核对 | 超时、断网、无完整返回资料 | `miniapp-user/pages/mine/company.js:15`；`miniapp-user/pages/mine/company.wxml:11` | 禁止重复保存；成功读取后单独展示已保存资料，与当前输入并列核对；读取失败不解锁，用户明确核对后才继续编辑。 | 定向执行/模板静态核对；未目视 |
| 企业资料 | 账期 0/null | 登记字段为 0 或缺失 | `miniapp-user/pages/mine/company.js:4`；`miniapp-user/pages/mine/company.wxml:22` | 0 显示 0 天，null 显示未登记。保留原字段接口，说明实际订单账期仍以水站设置及下单记录为准。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 首次加载/真实空 | 首次进入；成功返回 records=[] | `miniapp-user/pages/exception/list/list.js:68`；`miniapp-user/pages/exception/list/list.wxml:31` | 中文加载可见；成功空记录才显示暂无异常，不把读取错误当无异常。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 刷新中/失败/重试 | 返回页面、下拉刷新或重新读取 | `miniapp-user/pages/exception/list/list.js:68`；`miniapp-user/pages/exception/list/list.wxml:5` | 保留旧记录，明确处理状态/金额可能已变化；重试清错，旧请求不得覆盖。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 访问拒绝/异步 | 登录权限错误、乱序、隐藏或账号变化 | `miniapp-user/pages/exception/list/list.js:59`；`miniapp-user/pages/exception/list/list.wxml:6` | 中文解释及登录入口；不可访问缓存不展示。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 分页失败/重试 | 下一页读取失败，或与刷新交错 | `miniapp-user/pages/exception/list/list.js:55`；`miniapp-user/pages/exception/list/list.wxml:33` | 成功才推进页码；失败保留记录和页码，后续记录可重试；刷新废弃旧分页结果。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 状态/类型/数量未知 | 缺中文标签、内部英文 code、数量缺失 | `miniapp-user/pages/exception/list/list.js:93`；`miniapp-user/pages/exception/list/list.wxml:12` | 用独立展示字段，不改 status/statusText；未知待核实、不画完成进度；缺数量不装 0。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 补偿与金额 | 金额补偿/水票同时存在、只有建议或字段缺失 | `miniapp-user/pages/exception/list/list.js:21`；`miniapp-user/pages/exception/list/list.wxml:16` | 补偿金额和水票可并列展示；金额实际去向明确为客户押金账户。建议标拟计入，登记标执行状态待核实，只有 EXECUTED 的实际方案标已执行，不承诺现金到账；没有金额不说无需补偿。 | 定向执行/模板静态核对；未目视 |
| 异常记录 | 详情弹窗/责任备注 | 点卡片，关闭或点遮罩 | `miniapp-user/pages/exception/list/list.js:108`；`miniapp-user/pages/exception/list/list.wxml:38` | 卡片与弹窗共用字段；负差异显示多回。关键差异/金额/处理说明完整显示；长备注原文保留在可滚动弹窗，避免原生弹窗截断。 | 定向执行/模板静态核对；未目视 |


## Astra R2 两项复验（展示与定位回调）

- 补偿展示核对正本：`AquaFlow-backend/src/main/java/com/example/aquaflow/service/impl/OrderBarrelExceptionServiceImpl.java:340`，执行时构造增加客户押金账户的 DepositRecord 并在第 352 行交 depositRecordService.add。页面字段 refundCashAmount 的名字不代表现金到账。只改 `miniapp-user/pages/exception/list/list.js:21` 的展示，不改资金路径、状态或错误元数据。
- 定位回调正本：`miniapp-user/pages/address/edit.js:195`。operation 同时保存原登录周期、操作序号和阶段；getSetting success/fail、地图开启/成功/失败/complete、权限弹窗后续动作统一校验。onUnload、账号保护或开始保存使待选点操作失效；保存时明确提示选点已暂停，onHide 本身不使地图往返失效。
- 新增 8 项 R2 用例（49 项总计）：`tests/js/customer-profile-visible-states.test.js`，包含建议/登记/已执行在列表和详情的账户去向断言，以及卸载迟到、换号迟到、旧序号干扰新操作、正常往返、权限弹窗迟到回调反例。
- 仍未目视；没有重试截图通道。R1 原补丁 SHA-256：`2d71ebb91514a4e9675ead6614fd267c38f9df282662fd49a3171b06615ecb4b`。R2 只修改两份页面 JS、本批专属测试和本台账，精确范围与新哈希见 manifest。

## 定向验证

- `node tests/js/customer-profile-visible-states.test.js`：49 项通过，完成哨兵 `AQUAFLOW_SUITE_OK 49`。
- 实际执行 Page 方法，并用当前 WXML 条件/循环作可见文本断言；覆盖失败/空、刷新/重试、乱序/隐藏、分页、输入保留、保存锁、明确拒绝/结果未知、账号周期、事件绑定、原错误对象身份及 code 保留。
- 5 页模板结构、全部事件名、JS 解析和 UTF-8 无 BOM 已检查；未运行后端或全部 CI，未做真实支付退款、自动微信上传、DB 写入、新认证或真实用户操作。

## 可复现状态快照与目视缺口

`node tests/js/customer-profile-visible-preview.js` 生成 10 份当前代码合成状态 HTML/JSON，默认在 `qa/customer-profile-visible/`。包括地址首错/空态、编辑读取失败/保存未知、公告旧内容、企业首错/保存核对、异常卡片/详情/未知。

这些文件来自当前 JS 执行结果 + 当前 WXML 分支 + 当前 WXSS，是浏览器近似快照，不是微信运行时截图，也未声称已目视。

浏览器库存为空；一次建立通道返回 `Browser is not available: iab`。截图动作未执行，未绕过 URL 或认证。截图 Library IDs：无。全部页面均未截图目视，需父整合者/微信开发者工具核对小屏换行、原生输入、地图权限、弹窗滚动和真机触控。

## 交接与边界

补丁、19 项精确 manifest、逐文件 SHA-256、验证日志在候选根 `handoff/`。补丁独立应用结果以该目录报告为准。测试总注册留父整合；可直接运行本批单一测试文件。

公告详情正文属于首页 worker，本批只验证列表跳转及失败反馈，详情正文和实机跳转未验。读列表没有保存动作；不增加业务功能。身份变化要求退出本页后重新进入，防止原表单转属新账号；未知写入由原账号核对。

结果未知保护是当前页面内的重复操作边界，不承诺跨重启/跨设备写入幂等。没有改业务权限或后端。

本结论仅覆盖上述五页和专属测试，不代表全部 72 页通过。候选交接后停写，待父与 Astra 验收。
