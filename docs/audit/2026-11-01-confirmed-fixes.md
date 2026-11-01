# 2026-11-01 已确认审计缺陷修复记录

本文件写给复核和接手修复的工程师，记录已实施改动及可复现回归；不是生产验收或历史数据订正记录。

## 首批：资金与用途授权

| 审计问题 | 实施边界 | 回归入口 |
|---|---|---|
| 水票默认外派报酬漏计真实消费 | 消费 writer 与协议 SQL 共用 `TicketRecordSource.CONSUMPTION`；缺消费事实拒绝创建默认报价，确有零价值消费允许零水费；已有报价不自动重算 | `AuditPaymentAgreementIntegrationTest` 真实购票、单/多批次消费、0/正服务费、接受与结款、零值与缺凭据 |
| 支付请求隐式改换方式 | 锁订单后、幂等返回前核对原方式；既有错配待收流水不允许继续确认为已付，不修改原款或代为退款 | 同一测试中的方式矩阵、有/无随单押金、重放、并发及非本人请求 |
| 本人资料保存恢复员工旧状态 | 专用条件 SQL 只写姓名/电话，省略值在 SQL 内保留；不写状态、角色、水站或密码，停用已提交时拒绝更新 | `StaffProfileStatusRaceIntegrationTest` 用 MySQL 行锁闸门构造停用后写入及反向顺序 |
| 私有照片被商品路径重新签名 | 自定义商品写入复用图片引用校验；读取历史引用在接触签名器/缓存前拒绝私有、未知或绕行对象键；正常 public 上传、包内图、已核品牌和永久图保持 | `ProductImageIntegrationTest`、`ProductImageResolverTest`，拒签时签名器调用为零 |

修复前新增行为反例有明确失败，修复后同时检查正常支付、跨站结款、员工管理、令牌刷新与权限等邻接流程。按仓库 `CONTRIBUTING.md` 的安全测试库流程执行上述测试；未配置真实支付、COS 或外部告警。完整测试件数以后续实际 XML/CI 为准，不把定向结果拼成最终全量。

本批不需要数据库迁移。没有自动更正已接受报价、历史支付、员工状态或已存在的私有图片引用；历史私有引用只在读取时拒绝重新签名。已签发 URL 的自然有效期没有被追溯撤销。

其余已确认的报价版本、地址清空、文件归属、告警 JSON、保存反馈及测试门禁问题另批处理。正式域名、协议生效、真实微信收退款、云端运行版本和两端真机验收不因本记录自动完成。

## 第二批：文件归属与告警报文

- 文件删除必须有明确上传人且属于当前站长；平台级可见性不构成删除权。保留平台历史行明确上传人本人的原删除路径，他站文件仍拒绝。`FileManageControllerOwnershipTest` 断言拒绝时 COS 与数据库删除均零调用；`FileUploadIntegrationTest` 覆盖双 NULL、未绑定站长、他站、配送员及客户等 HTTP 分支。
- WECOM、DINGTALK 和 RAW 使用 JSON 序列化，控制字符可严格往返；SERVERCHAN 保持 URL 编码表单。`AlertWebhookPayloadTest` 逐通道验证，`AlertWebhookIntegrationTest` 的本地接收桩先解析 JSON，另用非法报文和 HTTP 拒绝作负对照，拒绝不得记成 PUSHED。
- 该批原实现的七项反例中四项失败；修复后报文、文件及告警路由和分层共 28 项通过。不涉及迁移、真实对象删除或外部通知；平台双 NULL 的真实记录是否存在仍未知。

## 测试门禁：失败关闭与套件生命周期

- `scan-secrets.sh` 区分 grep 的匹配、无匹配和执行错误；Git 不可用、权限或仓库错误不得再打印“未发现”。仍只输出疑似文件与计数，不扩大扫描排除范围。
- `run-all.js` 在启动子套件前递归核对发现集、登记集与有理由的排除集；目录读取失败、漏登记、重复或无理由排除均失败。新增套件须打印完成哨兵，退出 0 本身不足以算通过。
- 登记原遗漏的 approved-asset-pages、delivery-g04-typography-contrast、fee-sheet-redesign、ordinary-form-retry、plain-user-copy、product-phone-feedback、product-simple-upload、scene-retry、station-status-loading、ui-scene 十套。补 scene-retry 的三组同步完成标记；企业资料取消按现有确认放弃行为修正旧断言；启动 mock 补 business-dialog，收款文案断言按现行实际可见含义更新。
- 保留但明确排除 ua-customer-profile-visible-states（与 customer-profile-visible-states 重复49例）及 ua-mine-service-hierarchy（旧标签合同由 mine-service-hierarchy 替代）；没有删除旧文件或倒改产品规则。
- 新增假 Git 和未登记合成套件负对照；修复前均能检出误放行。十套恢复登记及两套治理回归共十二套、109个计数项通过；这不是完整两端或后端全量结果。
