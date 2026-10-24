# 2026-10-24 手机目录与首页反馈收口

本记录供接手同步与验收的工程师使用。基线为开发分支 `codex/fix-fee-drawer-layout-20261018` 的 `88aedb0552d27c897254ffc7f316af91bd7e49b7`；仅处理本轮四张截图、顾客首页标题及选站失败提示。没有重新审查全站。

## 截图事实与处置

四张 Library JPEG 已按支持的物化流程下载，在本执行环境实际查看像素。用户原图未入库。

| 证据 | 核实结果 | 本批处置 |
| --- | --- | --- |
| `libfile_5f32ebe382a481919f5a726f7c58262b`，1000041290.jpg | 目录筛选块过重；`低库存(&lt;20)` 原样显示；选用独占整行，留白多。 | 改为文字页签、轻量分类、横向快捷筛选；保持筛选含义，显示“库存少于20”；选用/设置移进商品信息列，保持原商品 ID 和事件。 |
| `libfile_2a5d1097594c8191b75b68d2b10b6244`，1000041291.jpg | 目录底部重复铺开三份常用水模板和长说明，原生灰按钮的主次关系不清。 | 模板默认收起，在底部新建区提供次级“从模板新建”；主动展开后滚到模板，保留使用、修改、已有 SKU 匹配及不上架默认规则。 |
| `libfile_d2f6fc6ece948191b7f497dd8cbc350b`，1000041292.jpg | 明确显示“仅本地预览，尚未上传成功”和上传失败；不能证明服务已接收。 | 保留本地预览、直接重试/重选、编辑内容和有效服务引用校验；新增失败时的轻量“复制问题信息”。**尚未定位实机失败根因。** |
| `libfile_cf152820ed248191956354e7cb6fba2a`，1000041293.jpg | 保存出的自定义商品没有图，旧“商品图待补”以大字挤成竖列；设置/编辑/上报/停用分散。 | 图片占位缩为小字“暂无图片”，两行明确排版；动作同行可换行，商品长编号可断行；整张瓶图用 aspectFit。失败的新预览仍不得冒充已保存的图片。 |

目录证据：`miniapp-delivery/pages/station-mgmt/products/index.wxml:12–119`、`index.wxss:567–607`、`index.js:960–996`。原按钮、权限、商品写入、价格库存和图片回调隔离均保留。

## 顾客首页

- 标题由“订水”改为“送水到家”；无选站时以“日常饮水 / 家里用水，随手就订”作简短引导，已选站时突出“服务水站”和实际站名。仅调整层级、文案与蓝色细线，无时效或促销承诺。见 `miniapp-user/pages/home/index.wxml:36–53`、`index.wxss:816–821`。
- **没有把本轮英文反馈直接判成 88aedb0 的已复现缺陷。** 该基线的选站 WXML 已显示固定中文，原始错误只写内部 `stationListError` 和日志。离线反例中旧版和新版的用户可见英文均为 false，详见 [反例记录](phone-catalog-home-20261024/counterexamples.json)。手机未提供该场景截图/运行版本，不能推定是同一版本。
- 本批在本页错误出口再用共享 `displayMessage` 过滤，并用 `Array.isArray` 验证列表；后台原始错误留日志。有用的中文业务解释保留；加载、失败和真实空列表不混淆，失败清除旧候选。见 `miniapp-user/pages/home/index.js:374–405`。选站弹层继续固定中文和“重新加载”入口。

## 上传链路结论与下一条有效证据

已核对实际路径：`chooseImages` 本地预览 → 文件字节校验 → multipart → `request` → `callContainer` / HTTPS `uploadFile` → `/api/common/upload` → COS PUT → 签名读取 → 服务端对象键持久化。

- `miniapp-delivery/utils/upload.js:111–127, 207–220` 要求有效商品对象键；仅后端明确 `stored + previewUnavailable` 才允许无预览链接的已存图片。`multipart-body.js` 使用 `name="file"`，与 `CommonController.java:40` 的参数一致。签名失败已有“已存储但预览不可用”分支，并不一概丢掉对象键（该控制器 65–78 行）。
- [微信官方类型定义](https://github.com/wechat-miniprogram/api-typings/blob/master/types/wx/lib.wx.cloud.d.ts#L160-L169)明确列出 callContainer.data 支持 string、object、ArrayBuffer。因此没有把“手工二进制 multipart 一定不支持”当根因。离线协议测试可证明构造的字段和字节，**不能证明实机 SDK 或线上 COS 接收成功**。
- 可确认的诊断缺口：共享传输之前丢掉 SDK 数字 `errCode`。本批双端镜像传输仅补固定阶段、数字错误码，超时/会话边界和禁止自动换路重发保持不变。旧反例丢码，新版保留 `-501000`，见上述反例记录；该值是合成测试值，不是用户真实错误。
- 员工失败区现在可复制 `AF-IMG2` 短诊断，仅固定阶段、HTTP 数字、业务数字、固定上传分类、SDK 数字及未确认标记。白名单之外的消息、文件路径、用户身份、URL、token、密钥均不进入剪贴板。入口只在失败时显示；成功后隐藏；重新打开当前编辑可保留诊断。见 `miniapp-delivery/utils/upload-diagnostic.js`、商品页 `index.js:1420–1427, 1488–1502`。
- 最小后续证据是**同一次失败复制的一条 AF-IMG2 文本**，或已有 `[product-image-upload]` 的四字段 `statusCode/backendCode/uploadDiagnosticCode/uploadOutcomeUnknown`。有数字 SDK 码时再附 `cloudErrorCode/stage`。不能由“相册本地可预览”推断上传成功，也不能由当前截图推断桶权限错误。未读取生产设置、密钥或真实响应。

## 验证与视觉边界

11 个定向套件 **310 项通过**，包括本轮新增 11 项。名称和件数保存在 [test-results.json](phone-catalog-home-20261024/test-results.json)。范围包含模板选用/默认值、商品图片保存、超时直接重试、旧标记不打扰、迟到响应、编辑返回、商品写入快照、选站切号、云 401 和禁止换路重放。两端共 187 个 JS 通过解析；变更 WXML 通过分支/结构解析，文件无 BOM，`git diff --check` 通过。

旧传输套件有六条断言仍要求向员工显示“未配置/缺少/不支持”，在独立 88aedb0 副本同样失败。本批更新为允许已有中文兜底，补充配置失败分类断言，保留零分发和无 HTTPS 兜底断言；未为通过测试恢复技术术语。模板测试改为主动展开后再检查三个真实入口。

[静态布局记录](phone-catalog-home-20261024/layout.json)来自真实 WXML/WXSS（含导入样式），使用明确合成商品/水站数据，320×568 放大字体 1.3 和 375×720 两种尺寸，5 个场景改前/改后共 20 次投影。另 [长表单几何](phone-catalog-home-20261024/sheet-layout.json)确认 3 个场景的编辑正文可独立滚动、关闭区和底部保存可达。代表投影已实际查看；不是微信原生控件、系统返回、软键盘或实机上传验收。

| 相同合成目录 | 88aedb0 | 本批 |
| --- | ---: | ---: |
| 375 宽筛选区高度 | 311.8px | 241px |
| 首个自定义商品卡高度 | 287.1px | 174.1px |
| 320 宽、字体 1.3 的文档宽度 | 339px（溢出） | 320px |

代表投影：[目录改前](phone-catalog-home-20261024/before-catalog-phone.png)、[目录改后](phone-catalog-home-20261024/after-catalog-phone.png)、[小屏放大字体](phone-catalog-home-20261024/after-catalog-small-large-font.png)、[模板主动展开](phone-catalog-home-20261024/after-templates-phone.png)、[首页](phone-catalog-home-20261024/after-home-phone.png)、[长站名](phone-catalog-home-20261024/after-home-selected-small-large-font.png)、[选站失败](phone-catalog-home-20261024/after-station-error-small-large-font.png)。复现命令：`node tests/visual/phone-catalog-home-render.js <输出目录> 88aedb0`。

## 交接范围

需同步并上传**员工端和顾客端**，本任务没有自动上传。**后端源码未改，不需为本批重新打包；无迁移、新配置或权限调整。** 未动数据库、COS 对象、凭据、桶 ACL 或线上版本。保留 88aedb0 及此前所有提交作为回退基础。

阻塞边界：布局和文案代码可交付；上传功能的实机验收仍缺同次失败响应，不能称“图片上传已恢复”。钱、库存、资产等原有结果未确认保护未按图片规则简化；此前另列的剩余工作未纳入本批。
