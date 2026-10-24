# 商品图片上传失败专项定位（2026-10-24）

基线：`6b3cbcf08b2dfb3dca982b5a2d14802384772e72`。用户提供同次复制文本：

```text
AF-IMG2 stage=server http=200 backend=1 code=UPLOAD_STORAGE_UNCONFIRMED sdk=- uncertain=0
```

结论：已定位到后端“读取上传文件 / 调用 COS PUT 抛异常”的统一返回分支。尚不能确定具体存储故障；没有同次服务器异常日志，不能据此断言权限、签名、网络或配置错误。此次只修复诊断中 `uncertain=0` 的信息遗漏，不宣称修好了真实上传失败。

## 这条信息确切说明什么

| 字段 | 当前源码含义 |
| --- | --- |
| `stage=server` | 前端收到带 HTTP 状态的服务响应并归为 server；不是 COS 阶段码。 |
| `http=200` | AquaFlow 接口返回 HTTP 200；业务失败也使用 200，不是 COS PUT 成功状态。 |
| `backend=1` | 接口响应体业务失败。 |
| `UPLOAD_STORAGE_UNCONFIRMED` | `CommonController.upload` 的文件读取 / 上传 try 块捕获异常；响应 `uploadOutcome=unknown`，没有可用服务端对象引用。 |
| `sdk=-` | 没有前端微信云调用的数字错误码；不表示 COS SDK 没有异常。 |
| `uncertain=0` | 旧前端漏传服务端 unknown 标记产生的误导诊断，不能据此证明未写入。 |

依据：`miniapp-delivery/utils/request.js:117-128`、`utils/upload-diagnostic.js:5-20`；后端 `AquaFlow-backend/src/main/java/com/example/aquaflow/controller/CommonController.java:58-78`。

## 完整存储链与排除边界

1. 登录、大小、扩展名先校验。空密钥预检返回另一码 `UPLOAD_STORAGE_UNAVAILABLE`。预检只检查非空，不验证有效性；此次不读取或更改任何实际配置。
2. `CommonController.java:65` 在同一个 try 内先执行 `file.getBytes()`，再调用 `CosUtil.uploadPublic`。因此该码甚至不能单独证明请求已到达 COS。
3. `CosUtil.java:129-131` 生成 `public/common/<UUID>.<扩展名>`，`78-84` 设置 MIME / 长度后同步 `putObject`，返回后才记成功日志和对象键。没有额外 HEAD / GET 确认。
4. 文件读取异常、COS 服务拒绝（含 PUT 鉴权 / 签名错误）、客户端超时等异常都落入上述 catch。前端收到同一码，无法从中进一步区分；SDK 重试或丢失成功响应也意味着不能保证对象从未写入。
5. PUT 正常返回后才生成 GET 预览签名（`CommonController.java:71-78`；`CosUtil.java:118-124`）。该步失败返回 `code=0`、`UPLOAD_PREVIEW_UNAVAILABLE` 并保留对象键，和这次返回不同。生成预签名也不等同实际 GET 已读通。
6. 后端没有把异常完全吞掉：`CommonController.java:67` 已 ERROR 记录异常消息和堆栈。测试中 COS 403 与客户端超时都返回相同公开码，但日志保留不同异常。

已接受此前确认的 aqua 账号、桶和地域信息。本次没有检查线上配置；不再把桶 ACL 作为默认原因。线上完整 SHA 未独立确认，此诊断只说明上传服务实现了该响应契约。

## 已修与保持

唯一生产代码改动：员工端 `miniapp-delivery/utils/request.js:126` 保留 `uploadOutcome=unknown`；`UPLOAD_STORAGE_UNCONFIRMED` 即使缺少 outcome 也保守标为 unknown。复制文本现在准确显示 `uncertain=1`。明确的上传前拒绝仍为 0。

使用真实请求模块与脱敏模拟响应，在移除这一行的对照版本复现用户原文；新版本只改变诊断位，图片失败提示仍为“图片上传失败，请重试”。没有改变页面、预览或直接重试，也没有增加人工核实流程，没有修改顾客端、后端生产代码、数据库或权限。该小修可随下一次员工端同步，不需要为定位此故障单独重传小程序；也不需要重传后端。

## 真正定位还缺的最小证据

从**现有运行服务**找同次 `文件上传失败` 记录，提取：

- 发生时间（含时区），能取得时附实例 / 修订版本；无需提供文件名或用户 ID。
- 异常类、最底层 cause 类与简短原因。
- 若为 `CosServiceException`：COS `statusCode`、`errorCode`、`requestId`。当前固定 SDK 5.6.227 本地 API 已核对；[腾讯云 SDK 异常源码](https://raw.githubusercontent.com/tencentyun/cos-java-sdk-v5/master/src/main/java/com/qcloud/cos/exception/CosServiceException.java)也定义了这些诊断字段。
- 若为 `CosClientException`：根因如 socket / connection / DNS / TLS 异常；没有服务响应时可能没有 COS requestId。

不要提供密钥、Authorization、签名 URL、完整请求头或原始 XML。现有日志已足够开始区分原因，本次不为此新增后端日志 / 部署。当前执行环境没有可用腾讯云服务日志读取工具，未尝试其他访问路径；生产根因仍待这条日志，不是已证实的代码修复结果。

## 验证

- JS：`phone-catalog-home` 12、`product-simple-upload` 15、`employee-request-session` 19、`cloud-upload.mock` 37；合计 83 个套件内断言用例全部通过，均有结束哨兵。
- 后端：当前相关生产源重新编译，独立 MockMvc `CommonUploadReceiptTest` 6 项通过，新增真实 COS 异常类型的脱敏模拟反例。没有启动应用、访问 COS / DB，也不是线上或原生微信验收。使用已有固定依赖和 JDK21 编译到 Java17，未宣称 Gradle / CI 通过。
- 对照证据、测试汇总见同目录 `product-upload-diagnostic-20261024.json`。

本次没有部署、真实上传、生产数据写入、凭据读取或权限调整。实际图片上传仍需服务器异常证据继续定位，不能把诊断修正算作上传故障已解决。
