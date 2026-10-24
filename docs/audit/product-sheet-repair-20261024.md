# 商品编辑弹层与上传恢复收口

2026-10-24。范围：员工端 `/pages/station-mgmt/products/index` 及其通用图片上传回执。用户手机反馈授权从冻结基线 `cd1eb91604a742771a59c33e909706d0f7b53826` 做有限修复；本记录随新候选提交保存。未部署、未上传微信、未改生产库、COS 权限或凭据。

## 已确定并修复

| 优先级 | 代码事实与影响 | 最小处置 | 交付影响 |
|---|---|---|---|
| P1 | 旧编辑器是 `modal-mask catchtouchmove → modal overflow-y:auto → 全部字段 → footer`。没有独立原生滚动正文，底栏随长表单溢出；用户明确报告无法下滑确定。 | 本站设置和自定义商品均改为兄弟遮罩、限定可视高度的 flex 弹层、独立 `scroll-view` 正文、正文外保存/取消及安全区。键盘几何区分窗口已缩小和未缩小，避免重复扣高度。 | 员工端需更新；原生可达性尚需手机复核。 |
| P1 | 旧图片恢复区位于根容器之后，缺少卡片布局和底栏避让；父线程已目视其灰按钮覆盖主 CTA。 | 恢复卡进入正文，默认仅数量、风险和一个展开主动作；展开后以继续原草稿为主、说明为文字入口。弹层开启时隐藏页面主底栏。 | 员工端需更新。 |
| P1 | 旧选图成功后直到收到完整回执才更新图片，未知时看不到所选图片；恢复新商品草稿会调用清空表单的 `openAdd`。 | 选图即保留独立本地预览；同一页面内恢复草稿保留输入、图片和档位。临时路径不写入可保存引用或持久恢复索引。 | 员工端需更新。 |
| P1 | `CommonController.upload` 旧实现将 PUT 与签名放在同一 try 中；PUT 成功后签名失败仍返回 code 1，丢掉已确认对象键。 | 回执区分 PUT 前拒绝、结果未知、已存储但预览不可用。仅商品允许明确 `stored + 合法 objectName + previewUnavailable` 无 URL 回执；保存实际对象键。 | 后端与员工端需统一更新；不需迁移或新增配置。 |
| P1 | 选图阶段未投影忙状态，保存和草稿切换未尊重该阶段；原有全局 picker 锁已存在，不能说旧版每次连点都产生重复 POST。 | 页面立即显示 selecting/uploading，拦保存和切换；编辑周期、身份、原目标和草稿约束迟到回调。 | 员工端需更新。 |
| P2 | 空图片同时显示上传和换图提示，模板说明重复且过长。 | 图片状态互斥，说明折叠；品牌/规格保留双列，金额和库存仍由本站填写。 | 随本轮交付。 |

主要源码位置：

- `miniapp-delivery/pages/station-mgmt/products/index.wxml:105`：恢复卡；`:245` 自定义商品独立弹层，`:250` 原生滚动正文，`:373` 固定底栏（本站设置从 `:132` 起采用相同结构）。
- 同页 `index.wxss`：有界弹层、正文 `flex:1/min-height:0/height:0`、底栏 `flex-shrink:0` 与安全区、长标题两行限制。
- 同页 `index.js:160`：键盘几何；`:849` 草稿快照，`:883` 未知换图，`:919` 状态投影，`:959` 保存守卫，`:1126` 即时预览与上传。
- `miniapp-delivery/utils/upload-recovery-page.js`：仅商品分支的折叠、忙状态与恢复钩子；其他上传用途保持原核实流程。
- `miniapp-delivery/utils/upload.js:checkReceipt` 与 `request.js:responseFailure`：受控回执和错误元数据。
- `AquaFlow-backend/src/main/java/com/example/aquaflow/controller/CommonController.java:upload`：三类上传结果。

## 未知结果的实际处理

没有删除未知风险，也没有将未知标成成功。当前图片未知时，图片区只有“重新选择图片”主动作及“查看原记录说明”文字入口。确认后创建独立图片草稿并立即选图；旧记录和同页原草稿保留，商品文字、金额和库存不变，不重放商品创建。仅新回执确认的引用可保存；同一未知草稿不会重复 POST。

上传前明确拒绝可人工重试。COS 异常仍保守未知：SDK 可能已重试，最终拒绝或超时不能证明此前没有写入。PUT 已确认而签名失败可保存对象键，本地预览继续显示，同时明确预览暂不可用。调整单凭证等其他用途继续要求有效 URL，不接收商品专用的无 URL 成功分支。

同页草稿快照是内存快照；重启后只承诺持久未知记录和原图片草稿编号仍保留，**不承诺未保存商品全文跨进程恢复**。通用上传没有按草稿查回原图片的授权接口，旧未知记录不会被自动当成已核实或静默清除。

## 诊断入口与边界

当前前后端成功回执契约原本一致：`{code:0,data:<URL>,objectName:<持久键>}`，MVC 也验证子类字段确实序列化。因此不能将用户手机故障直接归因为字段丢失、COS 账号或权限。用户已确认 aqua 配置无误，本轮没有查询或调整它。签名失败窗口是已证实代码缺陷，尚不能断言就是手机那次故障。

下次必要核查只看开发工具的 `[product-image-upload]`、`[product-image-preview]` 和 `/api/common/upload` 回执的非秘密状态：HTTP 状态、业务 code、固定诊断码；不要复制 token、签名 URL、文件内容或密钥。

| 固定诊断码 | 已知含义 |
|---|---|
| `UPLOAD_STORAGE_UNAVAILABLE` | 服务在 PUT 前明确判定上传配置未就绪；这是条件分支，不代表当前线上命中。 |
| `UPLOAD_STORAGE_UNCONFIRMED` | PUT 未获得可靠确认，保留未知。不能据此单独判断权限。 |
| `UPLOAD_RECEIPT_INCOMPLETE` | 缺少可验证 URL/对象键/专用 stored 标记，不能保存为新图片引用。 |
| `UPLOAD_PREVIEW_UNAVAILABLE` | PUT 已确认，生成预览失败；不需要重复上传对象。 |
| `IMAGE_PREVIEW_UNAVAILABLE` | 图片组件加载失败；不会因此清掉已保存对象键或将上传改判未知。 |

## 验证证据

- 12 组受影响前端回归 **412 项通过**，包括新增商品弹层/恢复 26 项、真实四页恢复与生命周期、HTTP/云容器合成上传、请求边界及按钮长度。不是全项目或 72 页验收。详见 [前端结果](product-sheet-verification-20261024/frontend-results.json)。
- 新增 26 项在 cd1 代码反例中 **18 失败、8 通过**；修复后 26 通过。部分保留的保存测试使用现工作区固定夹具，未将这部分当旧实现反例。详见 [反例](product-sheet-verification-20261024/baseline-negative.json)、[当前用例](product-sheet-verification-20261024/regression-cases.json)。
- `CommonUploadReceiptTest` **5 项通过**：真实 MVC 序列化、模拟 COS；新测试对旧 Controller 有 4 项失败。定向编译采用 JDK21 `-source 17 -target 17`，不是 Java17 原生运行、完整 Gradle 或 CI 证明。详见 [后端结果](product-sheet-verification-20261024/backend-results.json)。
- 真实 WXML/WXSS 映射为静态 HTML，在 Chromium 量测 **5 个代表布局**，合成图片/表单，禁止外部请求；短屏正文实际可设置非零 scrollTop，footer 均在视口内。320×308 键盘缩小投影正文仍有 172px，长名称本站设置正文有 133px；卡片末端与固定 CTA 保持约 20px 间隔。详见 [几何](product-sheet-verification-20261024/layout-metrics.json)、[短屏底部](product-sheet-verification-20261024/small-editor.png)、[未知上传顶部](product-sheet-verification-20261024/keyboard-editor-top.png)、[恢复卡](product-sheet-verification-20261024/catalog-recovery.png)。**这是静态投影，不是微信编译、原生滚动/键盘或真机截图。**
- JS 解析、WXML 条件链/事件方法、无 BOM 与 diff 检查；没有真实登录、上传、支付、退款或生产请求。

原始附件 `libfile_518bf56a1be88191a9f4bd22936ffe0c`、`libfile_2be80ffb279c8191abca5252fd5537cd` 已通过 Library 解析并尝试官方 helper 物化，但下载域名 DNS 返回 `gaierror -3`，文件未生成，本线程未实际 view 原像素。原图描述来自父线程已目视事实，不当作本线程目视验收；本轮静态投影已实际 view。

## 单次交付清单

- 新候选需同时包含员工小程序和后端；顾客小程序本轮无源码变化。
- 无数据库迁移、新增环境变量、桶 ACL 或凭据修改。保留 cd1 回退点与普利思永久 HTTPS 映射。
- 本线程仅提交代码、测试和本记录，不发布、不切流、不单独上传。
- 统一交付后仍须最小原生复核：Android 小屏长表单滚到底并确认；键盘弹出时操作底栏；选图即时预览与成功/明确失败/未知换图；恢复卡不压 CTA。真实上传是否能被下一次列表重新读取，尚无本线程线上证据。
