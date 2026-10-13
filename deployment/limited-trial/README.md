# 单站受限体验部署材料（草案，未部署）

基线 `c19a316732adadf308f4ba4abcd7e780c01dd543`。只供独立评审；没有服务、账号、环境或上线许可随此包创建。后端真实微信支付/退款仍未接入，禁止开启模拟支付或公网开发登录。本材料不是生产发布方案。

## 先复用已有内容

| 已有正本 | 本包如何使用 |
|---|---|
| `AquaFlow-backend/.env.example`、`application-prod.yml` | 继续作为变量语义和后端配置正本；trial是小程序环境，不另造Spring trial profile。本包只给必需变量的空值填写模板。 |
| `RequiredConfigChecker`、各schema启动guard | 继续执行原启动校验；额外入口只对本模板的空值/占位与危险覆盖先拒绝，不替代Spring最终值校验。 |
| `scripts/check-prod-config.js`、`check-jar-no-local-config.js` | 后续交付使用既有静态检查，不另写一套配置/JAR扫描器。准确JAR另经授权构建和检查。 |
| `SystemController /api/system/health`、`scripts/smoke-check.js` | 复用原存活探针与GET冒烟；本轮不运行联网冒烟。 |
| `sql/schema.sql`、`sql/README.md`及既有增量 | 不新增SQL、不重写迁移体系。新空库与存量库分开办理，见下。 |
| 既有trial路由、六条体验清单 | 保留已完成的隔离/禁开发登录成果；补可执行的受限操作稿，不重复改业务。 |

准确c19树中未找到Dockerfile/Compose或Spring trial profile，所以只补最小Dockerfile及入口。`miniapp-delivery/cloudbaserc.json`是旧脚手架，不代表已有Java云托管接线。没有读或复制本地私有配置、真实数据或旧凭据。

## 文件及安全默认值

- Dockerfile只复制准确审过的 `app.jar` 与入口，构建上下文白名单排除其他文件。**不含JAR**；必须后续把准确版本且已通过既有包检查的JAR以app.jar放入独立构建上下文。不得用旧阶段JAR冒充c19。
- `JRE_IMAGE`没有默认值；必须选定经审查、带digest的Linux Java17 JRE镜像，具备`/bin/sh`与`java`，支持非root UID10001及可写`/tmp`。本轮没有拉镜像/构建容器/验证平台架构，Docker兼容性仍待验证。应用为PID1（exec），工作目录/tmp，日志按当前控制台配置处理；外部持久化/日志收集须由平台落实，不依赖临时容器文件。
- 固定prod/8080、dev-login=false、mock=false、forward-headers=none、sql.init=never；保持既有桶权益模型，先装完整结构。入口拒绝本模板不支持的JVM/配置覆盖、额外命令参数；不接受通过它临时关模型或绕启动守卫。合法调优须另行审查显式命令，不能把真实秘密拼进参数。
- v1独审返修：`INDEPENDENT_BARREL_RIGHTS_ENABLED`及真正的`AQUAFLOW_BARREL_INDEPENDENT_RIGHTS_ENABLED`环境别名均只能为字面true；同时覆盖Boot规范去连字符形式`AQUAFLOW_BARREL_INDEPENDENTRIGHTSENABLED`。未设置时补为true，显式空值/false/冲突立即拒绝，不能用另一项true掩盖。启动命令固定实际属性`--aquaflow.barrel.independent-rights-enabled=true`。APP前缀不是c19模型属性，不列为有效映射。依据与离线边界见VALIDATION。
- `trial.env.example`所有九个必需值为空，有效值应只经核准平台的安全配置通道注入。无需把填好后的文件提交Git或回传Library。CORS来源另核，不能默认设`*`。
- `entrypoint.sh --check-only`只检形状，不启动Java、不连网/库、不验证凭据有效性，输出只含键名。缺值时普通启动同样先退出2，无默认账号/数据库或地址回退。Spring的最终值与schema守卫仍是第二道现有校验；本轮没有实际起后端证明它们通过。
- `transport-plan.example.json`仅设计输入，模式未选、云环境/服务标识为空，**两端运行代码不读取它**。缺标识安全拒绝的前端实现须在传输方案获批后完成，不能把该文件当已接通。

## 健康检查与接入

平台存活探针：容器8080，GET `/api/system/health`，期望HTTP200与项目成功封装内`data.status=UP`。端点固定、不查库；不以它证明数据库就绪、微信登录或业务可用。启动等待窗口/探针频率按批准实例实测后设定，本包不虚构已验证数值。

未来实际部署获批后，先核准确JAR版本和日志无启动guard拒绝，再复用`node scripts/smoke-check.js <核准HTTPS地址> --prod`区分存活、连库和认证拒绝。该工具只适合有可访问HTTPS入口的路线；仅云调用不能拿公网探针失败当业务坏了，应由获准体验端通过相同云传输做等价只读检查，不为跑脚本自动开放公网。平台内部HTTP探针仍可作存活检查。

单实例运行。现有内存限流/作业未按多实例验收；云平台自动扩缩到多实例不能默认开启。代理/云网关可能汇聚连接IP导致共享429，不改forward-header信任或取消限流来掩盖。

## 数据库初始化、迁移与回退（操作说明，不是执行授权）

1. 用户/运维确认独立试用目标、实际MySQL版本/字符比较规则、连接隔离和空库事实。现有基线为MySQL8.4；平台若提供8.0等版本，先核schema和guard兼容性，不能只因都是MySQL宣称通过。数据库与应用同一核准私网连接方案，不能默认公网开库。
2. **新空库**：由核准操作者创建目标库，再明确选中该目标安装准确c19 `schema.sql`。不要直接运行 `init.sql`，它在第20–21行固定创建/选择`aquaflow`；不要批量跑历史迁移。没有附自动建库或一键DROP脚本。应用运行账号权限与初始化DDL账号分别按平台落实。
3. **存量库**：先停写、备份并核已安装形状，只按 `sql/README.md`顺序执行适用增量；不向存量库导入完整schema充当升级。尤其v72–v79凭据/索引/版本/审计要与当前源码一致，不因草稿协议关闭省表。已有脚本/各阶段执行证据保持原样，不认历史“全部已执行”覆盖未知新目标。
4. 启动schema守卫并核准只读连库/401证据后，按`CLIENT-TRIAL.md`准备少量合成业务。真实微信身份仅用于具名体验授权，业务数据不复制真实库；合成资产来源必须明确，不伪装真实支付渠道到账。
5. 回退先停止新请求并保留现时数据库/日志/版本证据。仅在证明旧应用识别当前结构和全部新业务凭据后回退应用镜像；不得新业务后回旧模型、删表、删幂等封锁、抹退款/安排审计。需要恢复库时，另批准确目标/时间点及数据丢失取舍，恢复到核准目标并核对资产/库存/资金；不能把换镜像当数据回滚。不可恢复则保持停用并修复，不自动清库重来。

既有backup-restore-drill只支持其精确本机隔离目标，不擅自改为云恢复工具；prod-startup-check有演练库重建行为，不在云试用库或生产目标上运行。回滚/备份目的地与平台操作仍待用户授权。

## 本轮验证与剩余条件

本包提供无网络shell拒启/形状测试及文件约束测试，按现有 `AQUAFLOW_SUITE_OK N` 规范计数，详见外层证据。仅这些离线检查可称通过；容器构建、真实环境配置、平台账号资格、双端云环境共享、数据库初始化、微信登录、健康/就绪、手机扫码和真实收退款全部未执行。下一步先评审`TRANSPORT-DESIGN.md`影响面，再授权API代码实现；9430/9431未触碰。
