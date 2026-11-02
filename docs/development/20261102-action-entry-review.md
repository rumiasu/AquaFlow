# 2026-11-02 体验版办理入口修复

基线 728b64cdf0b52f7439732e114ada583f016e2aba，开发分支 codex/stage-handoff-20261004。原4件测试夹具脏改和既有未跟踪文件保留。

## 已修

- coordination/index.wxss：四项调度数量与文字使用显式块布局、8px下间距、自适应高度和多位数字换行，避免依赖微信 flex gap。真机小屏尚未验。
- exceptions/index.js/index.wxml：每张异常可进入订单详情和客户资产；无建议弹窗可直接进入原订单，不再声称只能忽略。导航校验当前身份、页面可见性和原列表记录，导航不发送任何补偿写请求。
- owed-barrels/index.js/index.wxml：新增“登记实际还桶”，直接进入对应客户既有独立还桶流程。实际收到空桶才登记；与退权益退押金明确分开。
- customers/detail/index.js：action=return-empty 只在成功读取当前身份下客户资产后打开既有表单，失败不自动打开，不重复打开。原确认、幂等、未知结果恢复和权限守卫继续使用。

后端链路依据：BarrelController.returnEmpty → BarrelService.returnEmptyWithRecord（事务、clientToken、type7留痕）→ BarrelLedger唯一写入口。还桶冲减over，不扣权益、不退款。无需新增接口/迁移/云端数据修改。本轮发布只需员工端更新。

## 同类巡检与待决清单

1. 异常单无建议不是可自动结案的事实：送2回2但桶身划痕可能仍需线下核实。已有A1契约只开放IGNORE/APPROVE，本轮只接通核实和独立资产办理，不将忽略改名为解决、不自动改钱或欠桶。
   TODO(待拍板)：是否增加“核实无需补偿结案”或“已另行办理结案”？A选项维持原A1，异常继续待处理直至忽略或建议补偿；B选项新增有理由、关联处理凭据和版本CAS的非财务结案状态，须同时修改ManagerExceptionController/OrderBarrelExceptionService、状态枚举和界面，不能伪装成现有IGNORE。本条是该问题正本。
2. 客户资产调整是独立有依据留痕路径，不自动使原异常结案；不得用它抹去未收回实物的欠桶。
3. 顾客售后/退款争议已有重新提出、站长理由结案及未知结果重试，相关17项回归通过；结案不表示退款已支付。退桶预约已有原申请ID、版本和显式变更，相关6+37项回归通过。已完成配送的回桶行显示实收，不再要求终态操作（9项通过）。
4. 普通反馈页明确“仅供查看”：miniapp-delivery/pages/station-mgmt/customer-feedback/index.wxml，无回复办理入口，是仍存在的功能边界。TODO(待拍板)：维持只读告知，或新增有客户可见回复和结案记录的处理流程？后者需独立接口/权限/并发与通知范围设计，不能仅增加“已处理”按钮。
5. 员工申请审批、业务待办入口本轮静态抽查未确认新的可复现故障，不代表所有状态均已真机走查。错误态、权限拒绝和历史终态不自动算“死局”。

## 验证

9个受影响/邻接套件合计150计数项通过：新workflow-entry-regression 8、return-empty-intent31、exception-closeout17、return-arrangement6、delivery-g04-typography9、experience-candidate20、barrel-return-visible-identity37、delivery-detail-return-row9、suite-registration13。新套件已登记run-all。文本门禁503文件通过；WXML绑定缺失0；触及文件git diff --check通过（只有换行风格提示）。没有重跑全量、没有调用数据库。

截图像素未完成：官方Library物化脚本下载后在Windows抛AttributeError: os has no attribute setxattr；已按支持流程有界重试，无最终本地图片。Library image_file读取也只返回文本而无像素。四图未假称已看；布局依据源码与用户描述，需微信小屏/多位数字实际布局验收。

交付源码未提交、未推送、未部署；原后端ZIP和215条云演示数据未动。

## 后续收口验证
完整注册JS套件224个：首次221通过、3个shell环境失败；使用已安装Git Bash及AQUAFLOW_TEST_SH定向重跑3个全部通过（35+44+11项）。没有以此冒称第二次全量全绿。完整日志task-5/workflow-full-js.log。18项非数据库静态门禁全部退出0，逐项记录task-5/workflow-static-results.json；场景矩阵的后端STATS校验明确SKIPPED。未执行verify.sh中的清库、后端Gradle、JAR/生产启动与服务冒烟步骤。
台账以客户分组，不把某一商品误当整客户欠桶：入口只传当前客户ID，站点始终取登录态；表单重新读取本站全部可还商品，按商品显示实际可还上限，由操作者确认实际收到的数量。返回台账onShow重新加载。异常跳转只传原订单/客户ID，权限和不存在状态由原详情接口处理，不构造资产或绕过原权限。
员工端上传：微信开发者工具打开D:\backend\project\AquaFlow\miniapp-delivery，编译，先预览核对调度四格/异常入口/实际还桶，上传新版本，再在公众平台将该上传版本设为体验版。仅需员工端更新；本轮没有后端或数据库变更。


最终同一版本完整JS回归：224个套件，共4706个计数项全部通过；日志task-5/workflow-full-js-final.log。提交与推送动作被自动审批拒绝，尚未执行，因此没有新commit或CI。


最终同一版本完整JS回归：224 个套件，共 4706 个计数项。使用已安装Git Bash环境；18项静态门禁结果沿用上节证据。
