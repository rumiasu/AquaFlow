# 商品编辑的两项手机反馈收口

基线 `74a425025b2c26449158b47fa13c571dba319eeb`，2026-10-24。仅员工端商品与库存页及其图片恢复展示。没有部署、上传微信、真实上传测试、数据库/权限/凭据操作；用户正在手动上传的上一候选与本次代码推送分开。

## 手机证据与上传记录结论

两张附件已通过当前 Library prepare_materialize 与官方 transfer helper 成功物化，本线程实际 view：`libfile_0cd0ea93e25c81918634631739e57828`（1000041288.jpg）显示本地图片预览、待核实状态及极窄顶部退出缝；`libfile_d2aa0941a1e88191b9844325070379ea`（1000041289.jpg）显示三条未知上传草稿各自铺出主按钮。原图仅用于本地核查，没有提交到仓库。

**不能把截图定性为“每次成功上传都没清理”。** 当前基线 `utils/upload.js` 的 `upload()` 在收到可验证 URL/对象键（或商品专用 stored 无预览回执）后，先 `removeCase()` 再 resolve；16 项新测试中的完整成功回执、重复选图复用、明确拒绝后重试三个基础契约在旧版也通过。未知结果、回执缺字段、已发出请求后超时、持久标记清理失败则保留记录；独立草稿不会擅自清除旧未知。这就是截图中旧记录持续可见的源码机制。截图无法区分手机那三次究竟命中哪个失败原因；没有取生产回执，不能归罪 COS 权限或宣称上传端到端已修通。

本轮补齐完成后的界面刷新（原来只有状态投影，未同步重读恢复列表），保存完成也刷新。未获确认记录仍保留，但默认折叠，展开后改为原生 picker 选择一条记录，仅显示一个“继续原草稿”主动作和一个说明入口；数量按“次上传暂未确认”表达，避免假称三次都是三张已存图片。完整确认的上传不产生待核实记录；独立新图成功不冒充旧未知已确认，也不删除云对象或业务凭据。

若后续手机仍每次待核实，已有非秘密诊断入口为开发工具 `[product-image-upload]` 的 `statusCode`、`backendCode`、固定 `uploadDiagnosticCode` 和 `uploadOutcomeUnknown`。不需要发送密钥、token、签名 URL 或原始 SDK 报文；历史本地记录没有保存这些诊断，不能补猜。

## 退出与未保存资料

- 自定义编辑新增可见“关闭 ×”，最小 60×44px；顶部留全宽 56px 退出区，内容面板内点击不关闭。独立 scroll-view 及正文外底栏保持，退出遮罩不是滚动正文的祖先。
- 接入微信原生 `page-container`，`show` 仅绑定自定义编辑，原生离开事件关闭编辑，不调用 navigateBack、不添加假页面栈。图库也移入相同原生层，避免落到编辑容器后面。设置页其他入口及导航工具不变。
- 关闭/取消/返回后可从本页“继续未保存编辑”恢复表单、档位、图片预览和原草稿；文案明确仅本页内存暂存，不承诺杀进程后保留商品全文。主动开始另一份新商品仍创建独立意图。
- 保存中普通关闭按钮禁用；原生返回关闭容器时保留已发请求的快照及写锁直至结束，禁止趁返回再创建一次。成功移除继续编辑入口；失败或未知保留原商品创建进度。切账号后不能把旧表单暂存在新身份下。

主要位置（本候选）：

| 位置 | 处置 |
|---|---|
| `miniapp-delivery/pages/station-mgmt/products/index.js:286` | 键盘与窗口尺寸扣减一次，自定义编辑预留退出区。 |
| 同页 `index.js:1088`、`:1110`、`:1476` | 原生容器离开、同页继续编辑、上传完成同步刷新。 |
| 同页 `index.wxml:111`、`:135`、`:265` | 单记录恢复区、未保存继续入口、原生容器与关闭按钮。 |
| 同页 `index.wxss:602` | 退出区、44px 关闭按钮与紧凑记录选择。 |
| `miniapp-delivery/utils/upload-recovery-page.js:23` | 仅商品分支的记录选择及空列表收起；订单/调整证据恢复契约保留。 |

支持机制核对：[微信官方示例 WXML](https://raw.githubusercontent.com/wechat-miniprogram/miniprogram-demo/master/miniprogram/packageComponent/pages/view/page-container/page-container.wxml)、[官方示例 JS](https://raw.githubusercontent.com/wechat-miniprogram/miniprogram-demo/master/miniprogram/packageComponent/pages/view/page-container/page-container.js)。官方开发文档正文本环境不可直读，未据第三方文章增加自造 onBackPress 或全局返回拦截。官方示例使用 show、beforeleave、clickoverlay 与 close-on-slide-down；目标工程既有基础库配置 3.17.0。本地请求 GitHub API 树被代理 403 拒绝后已停止该动作；不影响已通过 web 取得的官方示例和独立源码工作。

## 验证与交付

- 新增 `tests/js/product-phone-feedback.test.js` **16 项通过**。旧 74a 源码反向运行 **3 通过/13 失败**：三个已有上传基础契约本就通过；新失败包括真实三条记录同时铺出三个按钮、退出区尺寸差异，其他含新方法/接线缺失检查，不能将13项全算成独立旧业务故障。
- 11 组受影响前端回归 **278 项通过**，均校验结束哨兵；[机器结果](product-phone-feedback-verification-20261024/frontend-results.json)。旧边界夹具两处先打开实际商品编辑再触发选图，避免在不可见编辑器上调用选图来模拟关闭；原同文件21项安全断言全部通过。
- 新 `tests/visual/product-phone-feedback-render.js` 将实际 WXML/WXSS 映射到 Chromium，禁止外部请求。320×568、320×308/字体1.3倍、375×720 三组均：退出区命中 closeEdit、面板内部不命中退出区、正文 scrollTop 实际增加、底栏位于窗口内；最紧一组正文约84.5px，底栏底300px≤308px，关闭按钮44px。静态投影已 view，详见[布局数据](product-phone-feedback-verification-20261024/layout.json)。**不是微信编译、真机滚动、键盘、物理返回、右滑或在线上传验收。**
- 定向 JS 解析、WXML 条件链/事件与 BOM/diff 核查。没有后端变更，因此本轮不需要重新上传后端，不需要迁移或新配置；仅员工端前端更新。74a 中既有后端/顾客端修复仍随完整仓库保留，本轮没有把它们撤回。

交付边界：这两项代码修复完成；手机那三次未知上传的具体网络/回执原因仍未被证实。真实微信优先复核“选一张新图取得确认→保存→回目录无新增待核实”及“键盘收起后第一次系统返回只关编辑→可继续未保存编辑”。不以此次278项离线回归宣称原生或全站验收。
