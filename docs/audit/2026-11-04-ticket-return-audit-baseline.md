# 2026-11-04 水票退票与退回状态修复基线

本文件记录本轮独立 worktree 的基线与增量，不替代代码、迁移脚本和测试结果。

## 分支与基线

- worktree：`D:\backend\project\AquaFlow-ticket-return-audit`
- 分支：`codex/ticket-return-audit-20261104`
- HEAD：`50e205fb`
- 基线已完成全量：265 个测试类、2111 项、0 失败、0 跳过、0 错误（2026-11-04 15:00:16）。
- ⚠️ 该次全量原始 XML 已被后续单类 `cleanTest` 覆盖；`AquaFlow-backend/full-test-v91c.log` 保留 `BUILD SUCCESSFUL`，解析结果记录在 `build/evidence/offline-cos-20261104/full-run-result.txt`。

## 本轮增量

1. 退票未知结果恢复：
   - 新增 `miniapp-delivery/utils/ticket-exit-recovery.js`；
   - `business-waiting` 保存原 `paymentId/quantity/amount/requestKey`，按员工+站点隔离；
   - 网络未知保留记录并原键重试；业务明确拒绝才解除；存储失败不提交；
   - 退款成功但清除失败时，把 unknown 写回模块读取层；同键重试即使被业务拒绝、或重启后，也不得误清已成功凭据；
   - 新增 `tests/js/ticket-exit-recovery.test.js`。
2. 退回状态收紧：
   - 审批前核对申请与当前履约站/配送员一致；
   - 申请后分配、接单、改派均不得用旧申请改写新履约状态；
   - 旧审计必须晚于申请创建时间，否则证据不足。
3. v91 迁移改为 DMC 逐句模式：
   - 新增只读 `precheck_v91_ticket_exit_multiple_refunds.sql`；
   - `migration_v91_ticket_exit_multiple_refunds.sql` 只保留一条 ALTER；
   - 新增只读 `verify_v91_ticket_exit_multiple_refunds.sql`；
   - 不再使用 session 变量或 PREPARE。
4. COS 测试隔离：
   - 新增 `OfflineCosTestConfiguration`，所有 Spring 测试使用离线 mock；
   - `FileUploadIntegrationTest` 增加离线/失败关闭断言；
   - `AquaFlowApplicationTests` 增加同一断言；
   - `HistoricalClaimPhotoIntegrationTest` 改为本地合成签名 URL，不再构造真实 `CosUtil`。

## 数据库与测试边界

- 未使用既有测试库做新测试。
- 新建前经 `information_schema.schemata` 核实不存在的独占合成库：
  - `aquaflow_test_pay_ticketr220261104`（定向后端测试）
  - `aquaflow_test_pay_v91r220261104`（v91 旧结构升级验证）
  - `aquaflow_test_pay_v91good20261104`（v91 严格预检正向验证）
  - `aquaflow_test_pay_v91bad20261104`（复合唯一索引反例，precheck=STOP）
  - `aquaflow_test_pay_v91prefix20261104`（MySQL 将 BIGINT 前缀索引规范为完整索引；sub_part 条件仍保留）
- 未对既有库执行 DROP/清空/重置；未操作真实业务库或云数据库。
- v91 迁移只在上述新建合成库验证：旧结构 precheck=GO，单句 ALTER 后 verify=GO，旧行保留；迁移后重跑 precheck 返回 STOP；复合唯一索引反例返回 STOP。
- 定向后端回归：42 项 / 0 失败 / 0 跳过（5 个测试类）。
- 未执行真实/云端迁移。

## 证据目录

- `AquaFlow-backend/build/evidence/offline-cos-20261104/`
- `AquaFlow-backend/build/evidence/ticket-return-r2-20261104/`
