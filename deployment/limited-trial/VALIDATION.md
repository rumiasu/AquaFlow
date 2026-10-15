# 离线复核方法

在私有副本根运行；不需要账号、容器、数据库、网络或现有后端：

```powershell
$env:AQUAFLOW_TEST_SH='D:\backend\Git\bin\sh.exe'
& 'D:\backend\Git\bin\sh.exe' -n deployment/limited-trial/entrypoint.sh
& 'D:\frontend\node.exe' tests/js/trial-deployment-materials.test.js
& 'D:\frontend\node.exe' scripts/check-prod-config.js
```

Linux已有Node与POSIX sh时使用 `node tests/js/trial-deployment-materials.test.js` 即可；Windows须显式指定实际可用的sh。新套件注册到既有run-all，遵守 `AQUAFLOW_SUITE_OK N`，shell每次子进程最长5秒。不自动安装shell、依赖或拉镜像。

43项包括原28项、13项独立权益模型护栏及2项目录权限回归。模型检查覆盖三种有效环境名的false、空值、true及互相冲突，并检查全部缺省时为true及最终命令固定真实属性。目录回归分别约束显式创建可遍历父目录及独立于源文件mode的COPY、实际非root用户执行构建权限检查。通过条件为正确退出码与成功标记；缺shell不能当通过。此Node套件正向只运行check-only，模型负向对照也只运行check-only，不启动Java或尝试连接DB。

真实源码依据为c19 `BarrelBusinessPolicy.java:13` 的 `aquaflow.barrel.independent-rights-enabled` 和 `application.yml:137` 对 `INDEPENDENT_BARREL_RIGHTS_ENABLED` 的映射。官方[SystemEnvironmentPropertySource](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/env/SystemEnvironmentPropertySource.html)说明环境属性的大写及下划线变体；[Boot外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)说明去连字符的环境绑定形式及命令行高于环境/文件的优先级。入口先拒绝显式冲突，再统一导出true，并以最终命令行钉住实际属性；不依赖别名间谁胜出。c19没有APP前缀的该属性。此为源码和官方规则核对，未启动Spring应用或连库实测。

既有check-prod-config不更改，RequiredConfigChecker保持原样。此次不跑全套、不跑prod-startup-check，不用历史测试数字冒充本次执行。上述Node套件只做离线shell及静态契约检查；2026-10-15额外完成的容器验证如下，不能将二者混称完整云平台验收。

## 2026-10-15 目录权限修复验证

- 输入ZIP SHA256 `9b2e658f7fc482ed26055d39c605ba653fde9f1c09c3a6738ca958a948ff2b50`，006日志Library `libfile_df177233b7a0819180d06f83cf4c016f` v0。006日志证明两条COPY设0644、构建推送成功、随后入口读取失败；没有拉取006云端镜像，不能直接声称已读取它的文件系统权限。
- 用相同输入ZIP和004的基础镜像 `eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7` 实建复现：`/`、`/opt` 为0755，`/opt/aquaflow` 为0644，两个文件均为0644，全部root:root。10001:10001下原入口退出2，错误与006相同。缺少的是父目录x遍历权限。
- 修复后镜像实建成功；10001:10001下父目录为0755、文件0644，原入口`--check-only`退出0。缺配置或MOCK_WECHAT_PAY=true仍退出2。仓库Dockerfile复用已验证的WORKDIR之后全部构建/运行指令，只保留原有ARG JRE_IMAGE/FROM选择方式；JAR与入口没有重编或改写。
- 用`--network none`和虚构的离线参数经原入口实际启动原JAR：Java17.0.20.1、Spring Boot4.0.6、prod、Tomcat8080及WebApplicationContext开始初始化，随后CustomerConfirmationSchemaGuard因本容器127.0.0.1:9连接被拒绝而退出1。该结果证明权限阻塞已解除，未证明完整服务健康、数据库结构、TLS/微信登录或平台初始化hook成功；没有真实数据库连接、初始化或迁移。
- 仓库回归检查已对修复前Dockerfile实际报出两项失败，再对修复后通过，随现有`tests/js/run-all.js`接入原验证入口；不增加Docker依赖或新的发布脚本。重新封包步骤和已验证ZIP的Library ID、哈希见 [README.md](README.md)。
