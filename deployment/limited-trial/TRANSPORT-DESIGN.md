# 双端 trial JSON 传输候选

本文件供接线审查者和后续配置负责人使用。基线 `5286651a3333240839ee22597f8b40771de59bbd`；实现仅在隔离副本，云环境未开通，运行双端、数据库与后端代码未改。v1修复独审发现的两个App入口漏接，v0保留为已知有缺口的历史候选。

## 配置与范围

两端各自 `config/api.js` 的 `API_CONFIG.trial` 显式选择 `transport: 'https'` 或 `'cloudContainer'`。默认仍为HTTPS且`baseUrl`为空；develop和release继续原路由及域名守卫。

云模式填写 `cloudContainer.env`（环境ID）、`service`（服务名称）、必要时的`resourceAppid`（资源方小程序AppID）。这些均为非密标识；不要在客户端放AppSecret或JWT签名秘密。共享环境需平台完成关联和同主体共享授权，不能由AppID来自同一个人推定。`transport-plan.example.json`仅为工作表，运行代码不读它。

缺环境/服务、未知模式、缺SDK构造器或调用方法、初始化失败均明确拒绝，绝不回退develop、prod、HTTPS或开发登录。云模式调用`getBaseUrl()`会拒绝，避免未适配上传路径误用旧地址；第一轮仅JSON与只读探针，不接上传、真实支付或新数据库。

## 官方契约依据与限制

- [CloudBase官方小程序接入](https://docs.cloudbase.net/run/develop/access/mini)：正文抓取超时，但官方索引摘要可核到`new wx.cloud.Cloud({resourceAppid, resourceEnv})`、`await client.init()`、`client.callContainer()`及`X-WX-SERVICE`服务名路由；同主体共享与先关联环境的要求亦可核到。
- [微信官方API类型定义，固定提交6092df9100c73b84e140c20b9a42a8e8799e0660](https://github.com/wechat-miniprogram/api-typings/blob/6092df9100c73b84e140c20b9a42a8e8799e0660/types/wx/lib.wx.cloud.d.ts#L131)：回调重载返回void，未传回调的重载返回`Promise<CallContainerResult>`。参数声明`path/method/header/data/dataType/timeout`，结果声明`statusCode/data/header/callID`。选择Promise调用，设置`dataType:'json'`及既有超时毫秒值；结果保留HTTP状态和项目响应外壳，不把SDK成功当业务成功。
- 返回类型未声明RequestTask或abort；候选不调用未核实的SDK取消方法。`send()`返回的云`abort()`仅停止本地等待，标记`localOnly`与可能已发生请求，绝不保证服务器取消或事务回滚。HTTPS仍返回原wx.request的task。

SDK字段的存在由官方声明支持，真实基础库版本、SDK超时/错误表现、JSON解析、跨端共享和JWT头到达服务仍需获准环境中的只读实测。没有声称已验证真机或真实云。

## 实现与业务边界

相同字节的`utils/transport.js`分别放入两个独立小程序包，无跨包require；`send({path,query,method,header,data,timeout,success,fail,complete})`只处理传输。云实例每包/SDK/资源配置初始化一次；首次请求等待初始化且受本地总期限约束，过期初始化不会继续发请求。

普通请求、401刷新及原内容重放、顾客系统错误上报、顾客App启动缓存会话校验`/me`、员工App退出`/logout`均走适配器。顾客`backendCheck.ping()`走相同路由GET `/api/stations/public`，保留5秒期限；业务请求保留15秒期限。云SDK也收到该timeout值，本地另有一次结算保护，超时、停止等待后的迟到成功/401/失败都被忽略，没有网络错误自动重发。

Authorization JWT保留，刷新单飞、会话隔离、请求体快照、HTTP401/code401、业务拒绝与损坏外壳守卫仍在原`request.js`。未新增客户端role授权或信任网关身份头。登录继续顾客`/api/auth/wx-login`、员工`/api/auth/wx-login-staff`和后端各自AppID/secret配置。

已发送写入超时仍按现有页面保留unknown和原key/完整body；不换key，不宣称未建单。云初始化或缺配置虽然未发请求，候选不扩大业务层自动释放原请求的规则。

## 离线与后续实测

`tests/js/trial-cloud-transport.test.js`已接`run-all.js`：以SDK Promise、合成登录状态和可控时钟执行真实config/transport/request/App及下单Page，验证双端正常、HTTP/业务/损坏响应、401一次刷新与原体重放、云失败、超时迟到、初始化迟到、缺配置/SDK、开发和HTTPS路径、只读探针及上报路由。桩不建立真实环境、不调用微信或HTTP、不访问数据库。

新增`trial-cloud-app-lifecycle.test.js`执行真实App注册、缓存启动和退出，覆盖HTTPS/云两路的成功、失败、重复退出、缺配置、迟到跨登录、15秒超时及一次续期。旧App源码负向回放35项中21项失败，已在独审包保留；不把v0的50项通过当App入口已覆盖。

继续执行既有request-session-boundary、request-malformed-envelope、employee-request-session、employee-credential-faults、环境路由及原订单恢复专项。两个员工VM夹具加载真实transport模块，未降低旧会话断言。

获准启用后的最小只读实测：两端各完成真实微信登录；确认共享实例初始化；GET本人`/me`验证JWT、HTTP状态与JSON外壳；GET公开探针；验证实际SDK超时及迟到保护。平台共享/费用、服务与数据库准备由负责人另行确认；独审前不提交、不部署或启用配置。

## v1 直接网络调用清单

| 源码位置/类别 | 决定与证据 |
|---|---|
| 顾客App `validateToken()` | v0直接getBaseUrl/wx.request使云启动缓存校验停在配置弹窗且/me零调用；v1使用send，JWT与旧身份/凭据守卫保留，同步缺配置不读取注册前getApp。 |
| 员工App `logout()` | v0先拿锁再getBaseUrl抛错，complete未触发导致无法退出；v1使用send，成功/失败/超时均执行一次原本地清理，同步原生错误亦收尾，已退出的重复调用不再发请求。旧登录回调不能清理新登录。 |
| 两端`utils/request.js`全部JSON路径 | 普通、刷新、重放及顾客上报均已send；业务/API模块没有额外原生请求。 |
| 顾客`utils/backendCheck.js` | 已send，同路由只读GET探针。 |
| 两端`utils/transport.js`中的wx.request | 仅mode=https分支；develop/release及显式trial HTTPS调用原API，云失败不进入此分支。 |
| 两端`config/api.js`中的getBaseUrl调用 | 仅非trial或显式trial HTTPS的配置分支；云分支直接返回资源配置。getBaseUrl自身拒绝云模式，不能给未适配路径伪造地址。 |
| 员工`utils/upload.js`的getBaseUrl、uploadFile及其wx.request刷新 | 保留HTTP上传专项，不在本次JSON接线范围。上传在首个getBaseUrl处拒绝云模式，因此不会上传或进入该HTTP刷新；App专项已断言云模式上传/请求计数均0。不支持运行中途手改静态配置后续接正在进行的上传。 |
| 顾客shop/ticket页的getBaseUrl导入 | 仅未使用的导入，没有调用；本轮不做无关删除。其业务请求使用原request模块。 |
| 注释、日志里的wx.request及wx.requestPayment文字 | 不是网络调用；没有新增真实支付路径。 |

扫描覆盖两端全部.js源码的getBaseUrl、wx.request和方括号request写法，并人工区分导入/定义/注释/调用。v1没有新增上传能力或改后端。
