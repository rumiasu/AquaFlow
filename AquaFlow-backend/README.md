# AquaFlow Backend

Spring Boot 4.0.6 + MyBatis + MySQL 的后端服务。

## 项目结构

```
com.example.aquaflow
├── AquaFlowApplication.java              # @EnableScheduling 启动类
├── common/Result.java                    # 统一返回 { code, message, data }
├── config/
│   ├── CorsConfig.java                   # 跨域（localhost:5173）
│   ├── AuthConfig.java                   # JWT 配置（secret/有效期/白名单）
│   └── JwtUtil.java                      # JWT 双Token（accessToken + refreshToken）
├── constant/
│   ├── OrderStatus.java                  # 订单状态常量（1待组批/2配送中/3已完成/4已组批）
│   └── BatchStatus.java                  # 批次状态常量（1待出发/2配送中/3已完成）
├── entity/                               # 24 个实体（含 UserToken / AuditLog / PaymentRecord）
├── mapper/                               # MyBatis Mapper（含 AuditLogMapper / UserTokenMapper）
│   └── factory/                          # 运营平台 Mapper（FactoryOpsMapper / AlertMapper）
├── service/
│   ├── impl/
│   │   └── factory/                      # 厂长业务实现（5个）
│   ├── RefreshTokenService.java          # refresh_token 存储/校验/清理
│   ├── AuditLogService.java              # 操作审计日志
│   └── WeChatLoginService.java           # 微信小程序登录
├── controller/
│   ├── LoginController.java              # 统一登录/登出/续期/改密/获取当前用户
│   ├── factory/                          # 厂长 API（FactoryOps / Alert / TransferController 等）
│   └── *.java                            # 站长/通用 API
├── dto/                                  # LoginRequest / LoginResponse / RefreshTokenRequest 等
├── filter/JwtAuthFilter.java             # JWT 鉴权 → 提取 userId/userType/role/stationId 到 AuthContext
├── interceptor/
│   └── AuditLogInterceptor.java          # 操作审计日志（MyBatis 拦截器）
├── scheduler/
│   └── RiskAlertScheduler.java           # 每日凌晨1点自动检测风险
└── util/
    ├── PasswordUtil.java                 # BCrypt 密码工具
    └── IpUtil.java                       # IP 地址提取
```

## 认证机制

### JWT 双 Token

| 令牌 | 有效期 | 存储 | 用途 |
|------|--------|------|------|
| `accessToken` | 30 分钟 | 前端 localStorage + 请求头 | API 鉴权 |
| `refreshToken` | 7 天 | 前端 localStorage + 数据库 `user_token` 表 | 无感续期 |

### 登录流程

```
POST /api/auth/login { username, password }
  ├─ admin 用户 → staff 表查找 → role=FACTORY_ADMIN → factory 角色
  ├─ 员工姓名 → staff 表查找 → role=STATION_MANAGER→manager / DELIVERY→delivery
  └─ customer 表兼容（旧系统迁移过渡期）
```
密码校验：先明文 `equals()`，若不匹配则 BCrypt `PasswordUtil.matches()`。

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（返回双Token + 角色信息） |
| POST | `/api/auth/logout` | 登出（删除 refresh_token） |
| POST | `/api/auth/refresh` | 刷新 access_token |
| POST | `/api/auth/change-password` | 修改密码 |
| GET | `/api/auth/me` | 获取当前用户信息 |

## 数据范围隔离

- **厂长**：跨水站汇总/分析，无 `station_id` 过滤
- **站长**：SQL 中 `WHERE station_id = #{stationId}` 自动过滤
- **配送员**：`WHERE delivery_person_id = #{staffId}` 限制本人任务

`JwtAuthFilter` 从 token 中解析 `userId`/`userType`/`role`/`stationId`/`factoryId`，注入 `AuthContext` 供后续请求使用。

## 数据库

24 张表，详见根目录 README。
