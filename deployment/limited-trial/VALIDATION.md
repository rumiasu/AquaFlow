# 离线复核方法

在私有副本根运行；不需要账号、容器、数据库、网络或现有后端：

```powershell
$env:AQUAFLOW_TEST_SH='D:\backend\Git\bin\sh.exe'
& 'D:\backend\Git\bin\sh.exe' -n deployment/limited-trial/entrypoint.sh
& 'D:\frontend\node.exe' tests/js/trial-deployment-materials.test.js
& 'D:\frontend\node.exe' scripts/check-prod-config.js
```

Linux已有Node与POSIX sh时使用 `node tests/js/trial-deployment-materials.test.js` 即可；Windows须显式指定实际可用的sh。新套件注册到既有run-all，遵守 `AQUAFLOW_SUITE_OK N`，shell每次子进程最长5秒。不自动安装shell、依赖或拉镜像。

41项包括原28项及13项独立权益模型护栏。新增检查三种有效环境名的false、空值、true及互相冲突，并检查全部缺省时为true及最终命令固定真实属性。通过条件为正确退出码与成功标记；缺shell不能当通过。正向只运行check-only，模型负向对照也只运行check-only，没有启动Java或尝试连接DB。

真实源码依据为c19 `BarrelBusinessPolicy.java:13` 的 `aquaflow.barrel.independent-rights-enabled` 和 `application.yml:137` 对 `INDEPENDENT_BARREL_RIGHTS_ENABLED` 的映射。官方[SystemEnvironmentPropertySource](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/env/SystemEnvironmentPropertySource.html)说明环境属性的大写及下划线变体；[Boot外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)说明去连字符的环境绑定形式及命令行高于环境/文件的优先级。入口先拒绝显式冲突，再统一导出true，并以最终命令行钉住实际属性；不依赖别名间谁胜出。c19没有APP前缀的该属性。此为源码和官方规则核对，未启动Spring应用或连库实测。

既有check-prod-config仅在准确c19静态副本跑原脚本；本包不更改它或RequiredConfigChecker。未跑全套、不跑prod-startup-check，不用已有测试数字冒充本次执行。Dockerfile只有静态契约检查，未执行docker build/run，不声明镜像、容器权限或云平台兼容已验证。
