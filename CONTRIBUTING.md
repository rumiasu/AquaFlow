# 开发与验证指南

写给接手工程师，说明怎样配置、运行、验证及提交。业务目标见领域模型和范围决策；动手前读取 [AGENTS.md](AGENTS.md) 的仓库约定。

## 1. 环境

JDK 17、MySQL 8.4、Node.js、Python 3 和微信开发者工具。Gradle 使用后端 Wrapper，版本与依赖由仓库配置、锁和校验元数据决定。不需要小程序 npm 构建；集成测试使用真实 MySQL，不能指向生产库。

## 2. 数据库和凭据

新建开发库与独立测试库，先导入 [schema.sql](AquaFlow-backend/sql/schema.sql)。存量库按 [SQL 清单](AquaFlow-backend/sql/README.md)执行适用增量，不能重新导入基线当升级。

Windows PowerShell 中可使用 MySQL 客户端的 `source` 命令导入，避免 PowerShell 重编码管道。例如先进入仓库根，在确认两个目标都是新建本地空库后执行：

```powershell
mysql --default-character-set=utf8mb4 -u <本地用户> -p aquaflow -e "source AquaFlow-backend/sql/schema.sql"
mysql --default-character-set=utf8mb4 -u <本地用户> -p aquaflow_test -e "source AquaFlow-backend/sql/schema.sql"
```

将 `<本地用户>` 替换成自己的数据库用户；命令中的库须预先建立。其他 shell 可以在已确认的目标库用输入重定向。不要把口令写进命令文本或提交的脚本。

配置项见 [AquaFlow-backend/.env.example](AquaFlow-backend/.env.example)。该文件只是清单，不会自动加载；用环境变量或本机 `application-local.yml` 注入。后者被忽略，不能打入 JAR。微信客户端和员工端分别配对应 AppID/Secret。

独立权益默认开启，新库使用当前完整基线；存量库须满足启动保护器及购买/退款服务要求，适用增量只按 SQL 清单核对，不把“已有 v71”当作当前版本结构齐备。测试 profile 默认验证历史路径，新业务集成用例显式开启新模型。模型开启后的真实业务不能靠关闭开关继续按旧流程营业。

## 3. 启动和小程序

在后端目录运行：

```powershell
./gradlew.bat bootRun --no-daemon
```

类 Unix 系统使用 `./gradlew`。默认端口 8080；服务是否真正可用由启动输出和探针决定。

微信开发者工具分别打开两个小程序目录，配置 API 基址和本端 AppID。局域网真机调试还需合法域名校验与真机调试设置，详细步骤见[本地运行](docs/operations/02-本地运行-笔记本当服务器.md)。生产域名不能沿用占位值。

## 4. 后端验证

运行会重建库的 `scripts/provision-test-db.sh`、`scripts/prod-startup-check.js` 或恢复演练前，先确认目标属于本次可清空的环境，再将 `AQUAFLOW_ALLOW_DB_RESET` 设为该目标的精确库名。使用会话专用后缀可避免并行任务互相清表，允许范围及源目标保护见[部署与运维](docs/operations/01-部署与运维.md#5-备份与恢复)。该确认不自动授予生产迁移或恢复权限。

完整 `scripts/verify.sh` 使用测试准备和启动演练两个目标：测试库须有 `AQUAFLOW_ALLOW_DB_RESET` 精确库名与 `AQUAFLOW_ALLOW_TEST_DB_TARGET` 完整地址确认，启动演练须有独立的 `AQUAFLOW_ALLOW_PRODCHECK_RESET`。全部预检通过后才允许数据库动作，不以一个目标的确认授权另一个目标。完整 verify 的启动检查目前固定 `127.0.0.1:3306`，其它地址在任何数据库动作前拒绝；独立准备和受保护的直接测试可用经确认的远程 IPv4/端口。`node scripts/verify-local.js` 不重建数据库，无需这些确认。

测试基类每用例清表，必须使用专门可重建的 `aquaflow_test` 或其会话后缀，业务/备份/历史恢复名、源目标相同及缺精确确认均拒绝。准备和直接 Gradle 的 `MYSQL_HOST`、`MYSQL_PORT`、`TEST_DB_NAME`、`TEST_DB_URL` 以及存在时的 `DB_URL` 必须指向同一服务器、端口和库名；完整确认使用 `IPv4:端口/库名`。名称像测试库不能证明数据可删。仅支持规范 IPv4 单主机 TCP 和目标保护器允许的驱动参数，隐藏 Spring/JVM/MySQL 配置入口不能绕过检查。测试专用 initializer 在数据源建立前核有效配置，清表再以同一实际连接核 URL、catalog 和当前库。

测试库结构由准备脚本显式安装，测试上下文禁用自动 SQL 初始化。`spring.sql.init` 仅接受 `mode=never`；脚本位置、初始化凭据及未支持的启动迁移配置覆盖会在建立数据源前拒绝，不能用 `mode=never` 掩盖额外脚本来源。环境变量、JVM 属性和有效 Spring 配置均受检查，initializer 检查通过后再固定禁用初始化，避免启动脚本先切换库而每例清表护栏才发现。

核实目标确实可清空后，在运行以下数据库测试命令前设置目标。示例仅展示配置形状，不表示这个库已经获准清空；密码按本机既有安全方式提供：

```powershell
$env:MYSQL_HOST='127.0.0.1'
$env:MYSQL_PORT='3306'
$env:TEST_DB_NAME='aquaflow_test_session'
$env:TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/aquaflow_test_session?useSSL=false'
$env:AQUAFLOW_ALLOW_DB_RESET='aquaflow_test_session'
$env:AQUAFLOW_ALLOW_TEST_DB_TARGET='127.0.0.1:3306/aquaflow_test_session'
```

有 `DB_URL` 时同样指向该目标；独立根上下文测试不经过基类动态配置，自定义目标也须显式设置 `TEST_DB_URL`。允许参数与拒绝规则以 `scripts/lib/test-database-target.js` 和测试侧 `TestDatabaseTargetGuard` 为准；不通过删护栏解决配置冲突。

```powershell
./gradlew.bat test --rerun-tasks --no-daemon
./gradlew.bat test --tests '*IndependentBarrelBusinessIntegrationTest' --rerun-tasks --no-daemon
```

第一条全量，第二条有针对性地验证新模型。不要只看 UP-TO-DATE 或日志尾部，读取实际测试 XML 的 failures/errors/skipped。并行回归须分别用独立库、构建目录及项目缓存，不能共享清表库；具体命令见 AGENTS.md。

## 5. 小程序和契约验证

从仓库根执行：

```powershell
node tests/js/run-all.js
python audit_js_syntax.py
python audit_wxml_handlers.py
node scripts/check-api-doc.js
node scripts/check-sql-catalog.js
node scripts/check-pending-decisions.js
node scripts/check-miniapp-text.js
```

完整门禁入口为 `node scripts/verify-local.js` 或 `bash scripts/verify.sh`，CI 定义在根 `.github/workflows/ci.yml`。依赖缺失、扫描没执行和实际失败要分别报告，不把跳过当通过。页面函数/回调测试不能代替真机。

## 6. 发布

```powershell
./gradlew.bat bootJar --no-daemon
```

使用会话独立构建目录时，执行 `node scripts/check-jar-no-local-config.js --jar <本次生成的完整 JAR 路径>` 核验实际发布物；无参命令仍检查默认 `AquaFlow-backend/build/libs`。显式指定的文件不存在会拒绝，不回退到旧产物。该检查仅支持完整的单卷 ZIP32 中央目录（STORED/DEFLATED、ASCII 或标记为 UTF-8 的名称）；ZIP64、多卷、加密及不能可信解析的输入会拒绝。它不验证压缩载荷或 CRC，不能替代完整归档校验。

发布检查及迁移次序见[运维说明](docs/operations/01-部署与运维.md)。生产关闭开发登录和模拟支付，真实渠道未接入时不能开放假付款。先验证发布物不含本机配置，再部署正式环境。

## 7. 文档和 Git

业务未决定时，在对应规格写 TODO 指向唯一决策正本，不能由实现顺手拍板；验证结果写实际范围及证据。当前行为与产品目标不一致时分别说明，不能只改文档让缺口消失。

提交按相关问题组织，检查暂存差异和秘密文件。重建 Git 仓库按[重新发布说明](docs/operations/03-仓库重新发布.md)操作；新仓库初始快照不代表原工作区历史已经删除。仓库 URL 改变后再配置远端，不向旧远端误推。
