# 离线视觉截图脚本

本文件写给接手截图的工程师：使用已准备的独立离线视觉工程保存原生模拟器截图。

该脚本只负责切页、核实实际路由、保存 PNG 和回执，不生成夹具，也不启动后端。适用现成的 `offline_native_visual_samples` 工程；入口校验只是防止选错目录，不能代替隔离审查。

2026-10-21 的完整工程、分阶段生成脚本和清单已放入[已交付附件](https://chatgpt.com/api/library/files/libfile_5828f7e2c7f881919d1834f9dcc924f8/download)内的 `tools/AquaFlow-d2dd414-offline-visual-fixtures-20261021.zip`。先解压主包和内部夹具包，保持夹具包目录结构。夹具的后续修订包含在成品工程中；分阶段生成脚本不是一键可重建流程，因此没有重复收入仓库。

前提：微信开发者工具已打开独立视觉工程，现有授权可用；提供支持 `automation_navigate`、`automation_runtime_info`、`simulator_screenshot` 的 CLI。不要把 VisualProjects 指向两端产品源码。夹具仅保留原 WXML/WXSS/WXS 与静态素材，替换业务生命周期并阻断业务网络、登录和支付。

```powershell
./scripts/visual-review/capture-offline.ps1 `
  -Cli '<微信开发者工具 CLI 的绝对路径>' `
  -VisualProjects '<解压后的 visual-projects 目录>' `
  -FixtureManifest '<解压后的 visual-project-manifest.json>' `
  -Output '<仓库外的新截图输出目录>'
```

默认计划 `representative-pages.json` 是两端 72 个路由，每页一个默认视觉状态。它不是已交付 80 图的完整重放计划；已交付替代图、附加状态及回执以主包为准。自定义 `-Plan` 为同结构 JSON 数组，元素含 `id`、`side`、`route`、`state`，可选 `variant`（夹具查询参数 visual）。

输出目录保留 `originals/`、`receipts/` 和 `captures.json`。已记录保存的同 id 会跳过；同名图片存在却没有确认回执时拒绝覆盖。输出目录须专属于同一版本的一次截图任务，换版本请新建目录。出现授权拒绝或 pending 时停止；传输错误最多尝试三次，结果不明时先检查现有回执及文件，不重复发起。

截图均应标为合成视觉样例，不能充当真实业务验证。不要为复现截图执行登录、部署、数据库准备、真实下单、支付或退款。图表需要 Canvas 绘制时静态数据可能只能得到容器，应在覆盖清单中标注组件缺口。

本次审阅范围和例外见[覆盖记录](../../docs/audit/visual-review-20261021/README.md)。此工具提交不会改变旧截图基线。
