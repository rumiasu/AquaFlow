# 微信云托管实例证书接入补丁

供 AquaFlow 测试环境更新和验收使用。本补丁针对已确认的云托管开放接口服务：`api.weixin.qq.com` 被映射到实例内代理，HTTPS 使用平台生成的实例证书，官方固定文件为 `/app/cert/certificate.crt`。

## 更新时的唯一新增配置

在测试服务环境变量中设置：

```text
WECHAT_MINIAPP_CLOUD_CALL_TLS_ENABLED=true
```

默认值为 `false`，保持普通公网 HTTPS 的现行行为；只更新 JAR 而不启用这个变量，不会启用证书桥接。继续使用现有启动入口，不设置 `JAVA_TOOL_OPTIONS` 或 `javax.net.ssl.trustStore`，不修改 hosts、DNS、代理或系统/JRE 信任库。由用户手动上传更新，补丁没有自动部署操作。

## 行为和影响范围

- 应用启动不读取或等待证书。首次微信换码请求才从固定文件读取，最多等候 5 秒；文件缺失、不可读、损坏、过期或用途不符，只使该次登录明确失败，后续请求可以重试。其他业务接口不等待证书。
- 成功后仅缓存当前进程内的微信 HTTPS 客户端；每个新进程/新实例首次请求重新读取其证书。按平台“每容器一次性证书”的机制处理，不把某实例证书固定进镜像，也不建立后台轮询。
- 在内存中保留默认根 CA 并增加该实例证书，只用于 `https://api.weixin.qq.com:443`。保留标准 HTTPS 主机名校验，关闭此客户端自动重定向；其他 HTTPS 客户端和全局默认设置不变。
- 不从网络握手抓取证书作为信任来源，不使用用户粘贴的 PEM，不跳过验证，不把携带 AppSecret 的请求改为 HTTP。
- 只记录证书 SHA-256 和有效期，不输出 PEM、密钥、code、会话信息或请求 URL 参数。证书超出有效期时继续明确拒绝，不回退成无验证连接。

## 最小现场只读核对

```sh
if [ -f /app/cert/certificate.crt ] && [ -r /app/cert/certificate.crt ]; then
  ls -l /app/cert/certificate.crt
  openssl x509 -in /app/cert/certificate.crt -noout -subject -issuer -dates -fingerprint -sha256
else
  printf '%s\n' 'WX_CLOUD_CERT_MISSING_OR_UNREADABLE'
fi
java -version
ls -l /opt/java/openjdk/lib/security/jssecacerts /opt/java/openjdk/lib/security/cacerts 2>/dev/null
```

这只输出公开证书摘要和运行时信息，不需要完整环境变量、进程参数或 OpenSSL SSLSession。用户手动更新后，确认初始化日志指纹与该固定文件一致，再分别进行一次新客户/员工登录；已有会话及非登录业务应继续可用。

## 相关隔离验证

`WeChatCloudCallTlsTest` 使用每次新生成的临时合成证书、临时身份密钥库和真实回环 TLS 握手；不访问微信、数据库或实际平台证书。覆盖默认根保留、全局设置不变、错误证书和主机名拒绝、固定源限制、有限等待、文件迟到、失败后可重试、成功缓存及新实例证书更换。结合已有 `WeChatLoginTimeoutTest` / `WeChatLoginLoggingTest` 检查双端换码、超时和日志边界。

本补丁的确切 JRE 隔离结果随交付说明提供；测试通过不等于 019 已恢复。真实文件摘要和用户手动更新后的双端新登录结果仍需现场核对。
