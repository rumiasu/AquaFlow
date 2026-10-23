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

## 普利思缺图定向补充（2026-10-23，基线 ba9e5bf）

本节供接手工程师定位用户已反馈的“农夫山泉显示了，普利思没有”。012已由协调者确认在线，本节不将该反馈归因于012未生效。只检查商品图链路，不重复全站审查。

**确定事实与修复**：农夫山泉保存永久HTTPS图片；协调者提供的普利思公共商品4–6保存三条无前导斜杠的COS对象键。旧解析器仅在凭据未配置时使用包内回退，签名异常直接返回null；即使签出URL，也不能由签名成功推断手机实际可读。这是已确认的解析依赖/回退缺口，尚未取得线上响应及图片错误事件，不能把某次签名异常说成已证实的线上根因。新实现精确识别这三个已登记对象键，直接返回对应官网永久HTTPS原图，早于COS配置检查和签名；其它COS上传、包内路径、农夫永久URL及非法外链拒绝规则均保留。

| 水种 | 稳定对象键 | 永久预览来源 | 旧包内资源 |
|---|---|---|---|
| 饮用纯净水 | `catalog-sample-20261023-pulisi-purified-87bf702c165d.png` | [官网产品128](https://www.pulisi.com/product/128.html) → [原图](https://www.pulisi.com/data/upload/image/20201207/1607307675540169.png) | `pulisi-mineral.webp`，红标 |
| 饮用天然泉水 | `catalog-sample-20261023-pulisi-spring-f576447ab103.png` | [官网产品130](https://www.pulisi.com/product/130.html) → [原图](https://www.pulisi.com/data/upload/image/20200928/1601258020255537.png) | `pulisi-spring.webp`，绿标 |
| 饮用天然矿泉水 | `catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png` | [官网产品129](https://www.pulisi.com/product/129.html) → [原图](https://www.pulisi.com/data/upload/image/20200928/1601257945414406.png) | `pulisi-pure.webp`，蓝标 |

三个官网产品页及其图片链接已由网页读取核对；本环境直接下载三张PNG时，代理隧道均返回403，已停止该下载，未核对官网原图当前字节哈希。旧包内WebP已直接检查：双端均存在、为有效WebP且各对字节一致；旧pure/mineral文件名反置，实际映射按标签正确。没有更换素材、生成新图、访问COS对象或使用COS凭据。

**页面与字段证据**：

- `ProductImageKeys.java:22`登记原键与永久原图，`ProductImageResolver.java:104`在COS之前精确解析；`ManagerCatalogController.java:62`的`path`供展示，`objectName`继续保存原键。
- 员工路由`/pages/station-mgmt/products/index`的目录行使用`item.imageUrl`；`index.wxml:40`，读失败显示“商品图待补”。`utils/water-product-templates.js:17`接收永久HTTPS或包内路径，并独立保留对象键；没有给路径追加API域名。
- `CatalogServiceImpl.java:94` / `:188`读取目录与本站商品，`:441`统一填充`imageUrl`；客户`ProductController.java:170`与配送控制台`DeliveryConsoleServiceImpl.java:153`同用解析器。`ProductMapper.java:117` / `:136`均读`product.image_object_name`，库存表不覆盖图片。公共同一商品的站点选用仍共用该图；同名本站自定义商品有独立id/图片列，本次不会按商品名覆盖它。
- Library附件`libfile_61fe9f411c108191a1d2157c52d900bc`与`libfile_76923748156c8191a39f7cd78d74f3dc`的提取文本包含“商品与库存”“平台”“选用”，支持定位到站长公共目录；附件像素下载被403拒绝后停止，未进行原生截图目视验收。用户的实际缺图反馈与代码推断分开记录。

**定向验证**：`water-product-templates.test.js`59项、`product-image-persistence.test.js`14项完成并输出ASCII收尾哨兵。新增检查覆盖三水种目录可见图片节点/状态、模板使用和保存原键、六个既有包内资源、同名自定义商品不被模板覆盖；WXML检查是测试投影，不等于微信原生图片加载。

Java改用独立编译/JUnit入口：从Maven Central取得仓库锁定版本依赖，每个应用/测试依赖JAR均核对仓库`gradle/verification-metadata.xml`的SHA256；临时JUnit Console 6.0.3核对发布SHA512。直接编译**实际未改写源码**（含真实`CosUtil`、解析器、控制器及其DTO），在Java21以`-source 17 -target 17`运行`ProductImageKeysTest`2项、`ProductImageResolverTest`9项、`ManagerCatalogPresetImagesTest`1项，**12/12通过，失败/错误/跳过0**。没有类型桩、源码摘录或真实COS请求；单测COS由注入签名器/Mockito隔离。只把解析器替换为`ba9e5bf`源码时，新增`verifiedPulisiKeysNeverDependOnCosSigner`用例按预期失败，证明会抓到旧签名路径。

标准Gradle构建/CI状态未核实，既有Wrapper下载DNS故障不代表JUnit失败，本轮未重试被拒的Actions。新增HTTP/MySQL回归`ProductImageIntegrationTest.verifiedPulisiKeysReadPermanentOriginalsThroughCatalog`已入标准测试目录，**未执行**；未连接或清理数据库。机读结果见[pulisi-image-verification.json](pulisi-image-verification.json)，原始定向日志与JUnit XML在云环境`/tmp/aquaflow-pulisi-java/`。

**交付边界**：这是仅后端业务代码的可部署修复，无需为这三张图反复更新小程序素材包，也无需改库；`imageObjectName`不变，所有既有读口继续得到可展示地址。实际手机读取仍依赖官网原图可访问，本环境网页核对不能替代手机验收。修复尚未部署；后续由协调者安排部署后核对公共商品4–6的`imageUrl`和原反馈页。若是另一个自定义商品且其图片列为空，需要另据实际商品id处理，本次不猜图补库。不代表72页视觉全验收。
