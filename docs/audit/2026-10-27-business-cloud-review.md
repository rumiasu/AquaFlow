# AquaFlow 业务云端复核（2026-10-27）

本记录供接手工程师核对证据与未完成验证。复核基线为 `b3f45dd3cf8752954b445c5c3898a628c15148d8`，已包含资产刷新修复 `c1135003ec52d2849f033dbea2f0c9e7ea929601`。本轮检查计费保存、可选建议、报价与下单入参，以及历史认领照片与草稿边界；没有接入真实数据库、云对象或部署环境。

## 发现与最小修复

计费建议的最低价候选未排除已停用商品。`CatalogServiceImpl.deleteMyProduct` 只调用软停用（`mapper/ProductMapper.java:56`，仅写商品状态），库存行仍保留；`service/impl/DeliveryConfigGuideService.java:152-163` 原来仅检查库存上架和品类，未检查商品状态。例：停用桶装水旧售价 1 元、在售桶装水本站价 10 元，旧逻辑会给出 2 元/3 元建议，而依据文案声称它来自已上架商品最低价。该结论由当前调用与过滤代码确认，后端运行复现受下述环境限制。

修复仅将商品状态为 1 加入最低价候选条件，继续使用 `PriceUtil.calcUnitPrice` 的站级有效售价。建议不会自动保存，也不改变报价算法或已保存的金额。新增 `DeliveryConfigGuideServiceTest` 三项覆盖停用/停售与库存未上架、仅在售非桶装回退、全部停用无建议。这三项**尚未执行**，不能作为后端通过证据。

## 已核对的既有行为

- `DeliveryFeeServiceImpl.calcForOrder` 要求保存过配置；`PaymentServiceImpl.quote:1374` 与 `OrderServiceImpl.createOrder:551` 均调用该服务，桶数均按 `BarrelScope`、金额均按 `PriceUtil` 汇总本单商品水费。已保存的 null 金额不作为缺配置拒绝，WARN 不被目录上架情况升级为硬拦；未发现新计价分叉。此项是代码核对，未在本环境执行 HTTP/MySQL 集成测试。
- 配送计费页仍区分 null 与明确 0；填入建议仅改草稿，保存后回读核对再显示成功。现有 `delivery-fee-tutorial` 与报价错误分流回归通过。
- 照片上传使用本人客户 ID，客户与 token 唯一，上传与提交共用客户行锁；同 token 跨站被拒。站长经 `managerDetail` 核本站申请权限后才查询照片；签名失败返回照片存在但预览不可用。已有对应 Java HTTP/权限用例，本轮未运行，不重复声称通过。
- v86 只新增照片旁表；表主键与 token 长度/二进制排序规则、对象名长度及非空条件有启动护栏。脚本为 `CREATE TABLE IF NOT EXISTS`，不会修复已有坏形状，坏形状由启动护栏拒绝。没有发现需追加迁移的实证。本轮未执行 SQL，也没有执行或声称隔离库备份恢复验证。

## 本轮真实执行的验证

新增三项真实页面处理函数场景：已确认照片草稿按原水站切换并在重开后恢复、移除已确认照片后以新 token 无照提交、离页迟到上传不确认当前表单且用原照片查询恢复。三项均通过，照片流程没有据此追加源码改动。

| Node 套件 | 通过数 |
|---|---:|
| historical-asset-claim-flow | 74 |
| delivery-fee-tutorial | 22 |
| claim-photo-transport | 4 |
| order-quote-debounce | 14 |
| station-pricing-confirmation | 3 |
| 合计 | 117 |

每组均有 `AQUAFLOW_SUITE_OK` 收尾哨兵，执行日志随交接 ZIP 保存。`audit_js_syntax.py` 199 文件解析失败 0；`audit_wxml_handlers.py --root .` 缺失绑定 0；`git diff --check` 通过。测试使用合成 API、存储与页面条件投影；不是微信原生编译或真机验证。上一提交的 111 项结果是上一轮证据，没有重跑或混记为本轮结果。

## 未完成验证与环境阻塞

环境只有 OpenJDK 21.0.12.1，没有 Java 17。指定隔离 `GRADLE_USER_HOME` 后，原 wrapper 获取 Gradle 9.4.1 因 `UnknownHostException: services.gradle.org` 失败。获取 Java 17 的官方 Adoptium API 请求返回 HTTP 403 后停止，没有改网络、凭据或尝试其他渠道绕过。未用 Java 21 加 source/target 17 代替所要求的 Java 17。环境未提供 `mysql`/`mysqld` 可执行文件。

因此 Java 17 编译、三项新增后端单元测试、既有计费及认领照片 HTTP/MySQL 用例、v86 幂等与隔离库备份恢复仍待具备相应环境后执行。此候选包包含可恢复代码与明确限制，不代表后端或迁移已完成验收；不得据本记录直接部署。
