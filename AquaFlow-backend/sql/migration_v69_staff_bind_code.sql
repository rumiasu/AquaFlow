-- =============================================================================
-- v69 · 员工绑定的「一次性绑定码」
--
-- 背景（F-03③，正本 `docs/design/16-范围决策与实施路线图.md` §9.3）：
--   端点 `POST /api/auth/bind-staff` 是**免认证**的，原来的凭据只有「姓名 + 手机号」——
--   这两项都是**公开信息**（问得到、猜得到、泄漏得到），却能**签发员工会话**：
--   谁拿到某个在职员工的姓名与手机号，就能把那个员工账号绑到自己的微信上，之后以他的身份
--   看订单、看客户、做站长操作。2026-09-30 做过两层缓解（失败文案统一消除姓名枚举；
--   加按来源 IP 限流），但**限流只能减慢、不能阻止** —— 凭据本身是公开信息，防线就不存在。
--
-- 本迁移把这层凭据换成**只有站长能签发、且一次性**的东西：
--   站长在员工管理里点「生成绑定码」→ 拿到 6 位数字码（10 分钟有效、用一次即废）
--   → 当面/电话告诉该员工 → 员工在绑定页输入码 → 绑定成功。
--   攻击者要抢绑，得先拿到**实时**的那 6 位数（而不是一个查得到的手机号）。
--
-- 表只存码本身与生命周期；**不存任何会话/令牌**（绑定成功后走的还是原有 JWT 签发路径）。
--
-- ⚠️ 不加外键：与 `orders.station_id` / `inter_station_settlement` 一致（这些列本来就没有 FK），
--   加了反而让水站无法清理历史站。
-- ⚠️ 一员工同时只有一个未用码：生成时先删掉该员工旧的未用码（见 deleteUnusedForStaff），
--   所以"最新那个才是有效的"，不需要在查询里再做时间排序。
--
-- 影响面：**纯新增 1 张表**，无 UPDATE/DELETE 存量数据。
-- 执行方式：出错即停止的客户端（mysql CLI 默认，**不要 `--force`**）；
--   本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 回滚：`DROP TABLE staff_bind_code;` ⚠️ 先回代码再删表。
-- =============================================================================

SET @db := DATABASE();

-- -----------------------------------------------------------------------------
-- 第 0 步：预检（依赖表必须在）
-- -----------------------------------------------------------------------------
SET @deps := (SELECT COUNT(*) FROM information_schema.TABLES
              WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('staff'));
SELECT IF(@deps = 1, 'OK: staff 在',
          CONCAT('ABORT: 依赖表命中 ', @deps, '/1 —— 先跑 schema.sql')) AS precheck;

SET @abort := IF(@deps <> 1, 'SELECT * FROM __ABORT_V69_PRECHECK_FAILED__', 'SELECT 1');
PREPARE st_abort FROM @abort;
EXECUTE st_abort;
DEALLOCATE PREPARE st_abort;

-- -----------------------------------------------------------------------------
-- 第 1 步：建表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `staff_bind_code` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint NOT NULL COMMENT '要绑定微信的员工（staff.id）',
  `station_id` bigint NOT NULL COMMENT '签发它的站长所属水站；用于审计「哪个站给谁发了码」',
  `code` varchar(16) NOT NULL COMMENT '一次性绑定码（6 位数字；用一次即废）',
  `expires_at` datetime NOT NULL COMMENT '过期时间（签发后 10 分钟）',
  `used_at` datetime DEFAULT NULL COMMENT '被使用的时间；NULL = 还没用过。判据一律是 used_at IS NULL AND expires_at > NOW()',
  `used_openid` varchar(64) DEFAULT NULL COMMENT '用掉它的那个微信 openid（留痕：谁绑的）',
  `created_by` bigint DEFAULT NULL COMMENT '签发人（staff.id，站长）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_bind_code` (`code`),
  KEY `idx_staff_bind_code_staff` (`staff_id`,`used_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='员工微信绑定码(v69): 站长签发、10分钟有效、一次性; 取代「姓名+手机号」这种公开信息凭据';

-- -----------------------------------------------------------------------------
-- 第 2 步：自查（期望 1 张表 / 9 列 / 1 个唯一键 / 0 行）
-- -----------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_bind_code')  AS table_ready,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_bind_code')  AS column_count,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'staff_bind_code'
           AND INDEX_NAME = 'uk_staff_bind_code')                      AS uk_code,
       (SELECT COUNT(*) FROM staff_bind_code)                          AS total_rows;
-- ↑ 期望：table_ready=1, column_count=9, uk_code=1, total_rows=0
