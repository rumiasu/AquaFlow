# 2026-10-23 商品图对象键链路交接

本文供接手工程师核对商品上传/保存合同及部署顺序，是基于开发分支`4e590a8ce39fce6f3253593ab141ea40692be240`的限定修复记录，不是实际云数据导入回执。

已确认两条缺陷：通用上传只返回临时URL，员工表单随后仅传`imageUrl`，`CatalogServiceImpl.pickImageValue`会丢弃HTTPS地址，图片无法随商品持久保存；编辑已有COS图片商品时也未保留`imageObjectName`，保存其他字段可能清图。原页面定向反例首次12条中仅3条通过；该负对照包括新上传和编辑已有图片的真实Page处理函数。

本批在原`POST /api/common/upload`成功响应局部追加真实`objectName`，原`data`字符串URL保持兼容；不改公共Result、路径、上传目录、鉴权、COS配置或存储权限。员工商品表单分别持有预览URL与持久对象键，保存传`imageObjectName`，编辑保留原键；预设/新建/模板/独立图片草稿清旧键，防止覆盖或串图。商品缺键、HTTPS或cloud标识等非法键回执继续保留原未知上传记录并阻止重传，证据上传的旧URL合同不变。

持久链路：员工`api/station-mgmt.js`原创建/更新端点→`ManagerProductDTO.imageObjectName`→`CatalogServiceImpl`→`ProductMapper`写`product.image_object_name`；读取仍由VO保留对象键、`ProductImageResolver`生成当前临时URL。`owner_station_id IS NULL`公共商品仍由平台维护，本站自定义商品仍按原归属守卫；本批不修改公共商品、真实站点或云库记录，也未把HTTP/cloud标识当对象键反推。

部署依赖：**需要配套部署后端。先后端、再员工小程序**；新后端追加字段兼容旧客户端。新员工端遇旧后端缺键时会保持待核实，不会伪报持久保存成功。已经丢失对象键的旧上传或被清空的商品图无法凭临时URL安全恢复，本批不猜对象键、不做数据回填；上传成功亦不代表商品已保存。

定向验证：新增商品图检查13条通过；相关上传37、商品模板47、R2未知上传40、恢复生命周期21、持久边界21通过。生产选值/解析函数原文的独立Java模拟往返也通过：旧HTTPS回填选值为null，新键留在内存模拟列中且两次读取签名URL可变化；预设路径原样返回。该模拟不执行真实SQL/COS/MVC。测试夹具仅补上新增元数据；缺键和非法键负对照单独构造，未通过默认成功夹具掩盖。

新增`CommonUploadReceiptTest`通过MockMvc检查实际JSON中的URL兼容、顶级对象键和未登录不触碰COS，**本云环境未执行到该测试**：现有Gradle Wrapper需下载`services.gradle.org/distributions/gradle-9.4.1-bin.zip`，默认及一次申请网络权限的同路线尝试均DNS失败，未得到发行包，已停止。该测试及原`ProductImageResolverTest`仍需CI/可用构建环境验证，不能写成已通过；未改Gradle配置或依赖锁。JavaScript检查已注册原CI运行器，未重跑全量后端。

没有实际文件上传、真实数据库写入、云登录/凭据操作、网络商品图采集或部署；既往浏览器和Library被拒入口均未重试。Astra独立审查以最终候选及明确未执行边界另行记录，未部署前此文不代表线上缺陷已经修复。

补充交接：旧服务仅返回URL的实际页面回执已定向检查，原商品key和预览URL保持不变，显示“图片提交结果待核实，勿重复上传”，下一次选图不再发POST，也不提示上传成功；新增后商品套件14条通过。后端`CommonUploadReceiptTest`及既有`ProductImageResolverTest`均在标准`src/test/java`中，由`build.gradle`的JUnit Platform默认发现，原CI的`./gradlew test --no-daemon --console=plain`未设测试过滤；前端对象键套件已进入原`tests/js/run-all.js`，CI直接执行该入口。本地后端仍未执行，推送供CI验证不等于部署。员工上传工作区须在后端先行部署、CI与交付顺序确认后再由协调者安排更新。
