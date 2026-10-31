# 本机启动与交付前置核对（2026-10-31）

旧私有 application-local.yml 可能仍同时开启开发登录和旧模拟微信开关；当前 MockPaymentPolicy 会拒绝未经核实隔离的旧模拟模式。不要回显、复制或提交该文件。

普通本机调试可显式覆盖旧模拟设置：

```powershell
cd D:\backend\project\AquaFlow\AquaFlow-backend
.\gradlew.bat bootRun --args="--spring.profiles.active=local --app.payment.mock-wechat-pay=false --app.payment.test-mode=false --app.dev-login-enabled=true"
```

这条命令只消除已证实的支付配置冲突；实际启动仍须操作者核准目标库、凭据和 v80–v87 结构。本轮没有启动或迁移本机业务库，不能称此命令已在其上实跑通过。缺结构时按只读预检确认缺口并先备份，由获准操作者处理；不关闭启动守卫。开发登录仅限受控本机，生产关闭。

模拟付款沿用既有已批准的全测试 trial 部署或严格 payment-sandbox 流程。PAYMENT_TEST_MODE 是整库测试付款姿态，既有设计允许沿用 prod 配置名且不核对数据源；不应将该标志开启在真实业务库。此次没有改变此产品决策、环境变量或凭据。进一步限制实际目标库可作为单独加固方案评审，不能伪称现已存在该护栏。

测试登录与测试付款是不同边界：TemporaryTestLogin 另有 URL、实连 catalog 与 SELECT DATABASE() 的 aquaflow_trial 校验。配置账号使用 TEST_LOGIN_ACCOUNTS_JSON 内的 passwordHash，不存在 TEST_LOGIN_PASSWORD 属性。

未知/旧微信原款没有 SIM-WX 渠道证据时，现实现拒绝模拟确认与退款；取消事务回滚，保留原款、预留和订单事实。已付订单仍能正常履约。本轮不自动补标、不伪造已退款、不修改历史数据；需逐笔核原渠道与实际资金，核实前保留人工待处理。只读清单见交付包中的 legacy-wechat-provenance-readonly.sql。

无配送配置行时拒绝报价/下单是现行明确门槛，站长保存后才可经营；明确保存零费用仍合法。WARN 是起送量提示策略，不等于缺配置也免费。指导接口的默认值是编辑建议，不代表已保存。本站 pricing_confirmed 当前是定价确认标记，报价与下单可采用有效参考价，未确认标记本身不阻断。

微信云托管实例使用既有证书桥接时保留 WECHAT_MINIAPP_CLOUD_CALL_TLS_ENABLED=true 和 /app/cert/certificate.crt；普通本机无需开启。COS_REGION 与 COS_BUCKET_NAME 被实际读取。固定场景签名接口不证明对象已上传或存在，必须在获准目标环境单独验证。

本说明不构成部署、迁移、补账、真实退款或权限变更记录。
