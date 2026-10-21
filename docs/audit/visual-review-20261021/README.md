# 2026-10-21 美术截图覆盖记录

本文件写给美术审阅者：说明本次可审阅图片、对应版本与未覆盖的视觉组件。

截图基线：`d2dd414509e36d6186757a537135505f9c0cfd4c`，两端 app.json 共 72 页（顾客 26、员工 46）。[逐页索引](coverage.csv)覆盖 72/72 个真实路由；其中 images、states、files 关联主包内的原图，路径均相对主包根目录。页面有图不等于全部状态或组件完整。

主审阅集共 80 张原图：79 张微信原生模拟器渲染的独立离线合成视觉样例，以及 1 张未改动工程的实际游客首页。离线工程使用该提交的 WXML/WXSS/WXS 和静态素材，排除正式 Page/App 生命周期、API 模块与业务处理器，以合成数据展示；不代表真机、真实登录或业务通过。

主包保留全部 94 张原始 PNG，其中 14 张早期帧／被替代夹具图不计入主审阅集。打开包内 index.html 查看可搜索缩略目录；contact-sheets 内有 7 张总览。原图从原生模拟器保存，总览只缩放排列原图。

经营概览页面的 Canvas 趋势／小时图未绘制内容，目前只有静态容器和指标卡；这是明确的组件缺口。默认页、少量空态、长文本和弹窗供美术参考，不能据 72/72 页宣称状态全覆盖。历史截图仅作参考，没有计入本次覆盖。认领页展示为合成数据，未调用认领／审核业务接口。

[下载已交付主包](https://chatgpt.com/api/library/files/libfile_5828f7e2c7f881919d1834f9dcc924f8/download)：`AquaFlow-Art-Review-d2dd414-20261021.zip`，12,418,017 字节。SHA256：`3dd29ab798bd319d1b086e9f12eebb941191ab54d088e86f71ef4145e7e79e78`。Library ID：`libfile_5828f7e2c7f881919d1834f9dcc924f8`。

包内 saved-images.json 为主审阅索引，captures.json 记录截图尝试，superseded-originals.json 标记排除项，image-sha256.json 记录原图哈希；source-integrity.json 记录固定源码 476 文件及原工作区保护文件 14 项未变，夹具包内 visual-project-manifest.json 记录原渲染文件哈希。完整离线工程及分阶段生成材料位于 tools 内嵌包。

本记录及截图工具的后续提交仅保存证据与复用方式，不意味着图片已按后续版本重截。工程师操作见[离线截图说明](../../../scripts/visual-review/README.md)。
