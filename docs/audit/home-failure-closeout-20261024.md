# 首页读取失败版面补修 · 2026-10-24

本记录供同步与验收的工程师使用。基线 `d088919a0293c60d2fe7c54f0abc7482d2d80f15`，只补两张明确截图对应的首页问题，不扩大审计。父任务已实际查看原PNG，确认截图是微信开发工具的iPhone模拟器；本云任务依据其像素说明定位，并实际查看本次静态投影，**不称原图或改后真机已验收**。

## 测试站名的已知源头与当前边界

原文为 `SYNTH Task5 Scenario Station`，是明确的旧场景测试站名，不是一般英文站名，也不是HTTP错误。父任务本地只读确认：旧 `task-5/scenario-api.cjs:14` 通过 `/api/auth/create-station` 在本地 `127.0.0.1:8097` 的隔离库 `aquaflow_test_scenario_20261015t1247z_t5` 创建它；`samples.json:1686` 有同名报价。旧交接 `handoff-bundle.json:222` 说明样例未清理、列有 CleanupEmpty；现存恢复收据恢复的是场景样例，缺 empty-restore-receipt，不能据此说当前库已空或仍有该站。这些是父任务核实的本地资料，不是本云副本文件。

云仓库的精确串最早见 `e073ba2` 的 `tests/js/customer-home-recovery-view.test.js`：最初设置合成Page状态，后续改走模拟 `selectedStation` 缓存恢复。该缓存是 `harness.js` 的内存Map，不会写微信开发工具storage；它不是原生截图数据的最初创建源头。仓库没有找到生产代码或种子脚本生成此精确串。

首页运行链仍是优先 `selectedStation`，缺失后读最近订单水站。**当前截图究竟来自旧缓存、旧运行项目或场景接口仍未确认，未读取实际微信storage，也未改任何库。** 详见 [来源记录](home-failure-closeout-20261024/origin.json)。

本次仅对这一精确名称作中文展示标记“示例水站（测试）”，同时覆盖首页与首页选站行；保留站点ID、原始名称、缓存和正常选择逻辑。其他英文名和不同的SYNTH名称继续原样显示。实现：`miniapp-user/utils/station-view.js`；这不是广泛正则替名，也不冒称已清理旧测试数据。

## 对应截图的具体补修

| 截图问题 | d088仍存在的原因 | 本次结果 |
| --- | --- | --- |
| 顾客总提示与商品失败大卡重复重试 | 共享失败条和 `catalogState === error` 卡同时渲染。 | 总提示收为“5项信息暂未加载”，完整失败内容保留在详情；已有总提示时不再渲染第二张商品错误大卡。保留一个主要重试入口、原选购数量和站点切换入口。没有共享提示时仍有商品错误卡兜底。 |
| 配送两块提示巨大，按钮切断长文 | `.load-error` 中text/button未独立分栏，待收款读取失败另起同类块。仅中和底色没有修结构。 | 合为一个 `home-read-feedback`，正文独立可收缩列、按钮独立固定列；保留“待收款未加载，不代表结清。”。实际收款未知恢复卡继续单列，不混作普通读取失败。 |
| 页签重复“待核实”，营业未读取像业务警告 | 未加载数量每项长字；状态读取失败和真实营业警告共用橙色class。 | 数量未加载显示“—”，成功读取零单仍显示“0 单”；窄屏名称/数量上下排列。营业读取失败显示中性“状态未加载”，真实延迟/待填仍保留警告色。共享导航仅改未加载呈现，不改营业状态或权限。 |

相关源码为双端 `pages/home/index.js/.wxml`、员工首页WXSS、`templates/station-navbar.wxml`、`styles/station-navbar.wxss`及上述站名展示helper，共8份源码。没有改收款、库存、资产、报价或会话保护。

## 验证与冻结范围

[6个定向套件150项通过](home-failure-closeout-20261024/test-results.json)，其中新增6项覆盖精确测试名、其他英文名与缓存ID保持、五类错误合并、独立商品失败兜底、财务未加载与已确认零、真实营业警告。相关切站/换号与收款未知保护通过。双端188份JS解析通过；变更WXML分支、无BOM、`git diff --check`通过。

[8次改前/改后静态投影](home-failure-closeout-20261024/layout.json)使用真实d088/当前WXML/WXSS及与父任务像素说明一致的合成状态：375 CSS像素iPhone布局假设、320宽字体1.3压力场景。附件原始物理尺寸未在本云独立读取，**不冒称投影与原附件物理像素完全相同**。投影未执行真实网络、微信原生或数据库操作。

已实际查看：[顾客改前](home-failure-closeout-20261024/before-customer-iphone.png)、[顾客改后](home-failure-closeout-20261024/after-customer-iphone.png)、[配送改前](home-failure-closeout-20261024/before-delivery-iphone.png)、[配送改后](home-failure-closeout-20261024/after-delivery-iphone.png)、[小屏顾客](home-failure-closeout-20261024/after-customer-small-large-font.png)、[小屏配送](home-failure-closeout-20261024/after-delivery-small-large-font.png)。配送375宽页签顶由281.5px提前至180.1px；顾客主要重试由两个减为一个；新提示列与按钮无重叠、无横向溢出。

本批需统一同步上传两个小程序。**后端未改，无需重打；无迁移、新配置或数据库/缓存清理，本任务未部署。** 保留d088回退。旧场景库、生产数据均未触碰；此前真实图片上传失败仍按原记录未定位，未纳入本批。
