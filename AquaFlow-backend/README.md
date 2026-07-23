# AquaFlow Backend

Spring Boot 4.0.6 + MyBatis + MySQL 的后端服务，为管理后台（厂长/站长/配送员）和微信小程序（客户）提供统一 API。

## 项目规模

| 指标 | 数量 |
|------|------|
| Java 文件 | 150 |
| 实体类 | 24 |
| Mapper 接口 | 24（含厂长模块 2 个） |
| Controller | 26（含厂长模块 5 个） |
| Service 接口 | 26（含厂长模块 5 个） |
| Service 实现 | 23（含厂长模块 5 个） |
| 数据库表 | 25 |

## 项目结构

```
com.example.aquaflow
├── AquaFlowApplication.java              # @EnableScheduling 启动类
├── common/Result.java                    # 统一返回 { code, message, data }
├── config/
│   ├── CorsConfig.java                   # 跨域（localhost:5173）
│   ├── WebMvcConfig.java                 # MVC 配置（AuthInterceptor 注册）
│   └── PasswordInitializer.java          # 初始密码 BCrypt 加密
├── constant/
│   ├── OrderStatus.java                  # 订单状态（1待组批/2配送中/3已完成/4已组批/5已取消）
│   ├── BatchStatus.java                  # 批次状态（1待出发/2配送中/3已完成）
│   └── PaymentStatus.java                # 支付状态（1待确认/2已付款/3已退款/4已取消）
├── entity/                               # 24 个实体
│   ├── 核心业务: Customer, Orders, Address, Batch, Inventory, WaterType
│   ├── 资产管理: TicketAccount, TicketRecord, DepositRecord, BarrelRecord
│   ├── 组织: Factory, Station, Staff, CompanyInfo
│   ├── 运营: RiskAlert, StockTransfer, PaymentRecord
│   ├── 快捷: OrderTemplate, OrderTemplateItem, OrderImage
│   └── 基础设施: AuditLog, CustomerStationRecord, StationPaymentConfig
├── mapper/                               # MyBatis Mapper（22 个 + factory 2 个）
│   └── factory/                          # 运营平台 Mapper（RiskAlertMapper, StationAnalysisMapper 等）
├── service/
│   ├── impl/                             # 业务实现（18 个）
│   ├── factory/                          # 厂长业务接口（5 个）
│   └── impl/factory/                     # 厂长业务实现（5 个）
├── controller/
│   ├── LoginController.java              # 统一登录/登出/续期/改密/获取当前用户
│   ├── factory/                          # 厂长 API（5 个 Controller）
│   │   ├── StationOperationController    # 水站运营总览
│   │   ├── StationAnalysisController     # 数据分析
│   │   ├── StationProfileController      # 水站画像
│   │   ├── StockTransferController       # 库存调拨
│   │   └── RiskAlertController           # 风险预警
│   └── *.java                            # 站长/通用 API（21 个 Controller）
├── dto/                                  # 8 个数据传输对象
├── annotation/RequireRole.java           # 角色权限注解
├── aspect/RequireRoleAspect.java         # AOP 角色校验切面
├── interceptor/AuthInterceptor.java      # JWT 鉴权 → 提取用户信息到 AuthContext
├── exception/
│   ├── BusinessException.java            # 业务异常
│   ├── ValidationException.java          # 参数校验异常
│   ├── ResourceNotFoundException.java    # 资源不存在
│   └── GlobalExceptionHandler.java       # 全局异常处理
└── util/
    ├── AuthContext.java                  # ThreadLocal 请求上下文（userId/userType/role/stationId）
    ├── JwtUtil.java                      # JWT 双Token（accessToken + refreshToken）
    └── PasswordUtil.java                 # BCrypt 密码工具
```

## 认证与授权

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

### AOP 角色控制

- `@RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})` 标注在 Controller 方法上
- `RequireRoleAspect` AOP 切面拦截，校验 `AuthContext` 中的角色
- 所有管理端写操作（POST/PUT/DELETE）均有保护，客户 token 无法调用

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（返回双Token + 角色信息） |
| POST | `/api/auth/logout` | 登出（删除 refresh_token） |
| POST | `/api/auth/refresh` | 刷新 access_token |
| POST | `/api/auth/change-password` | 修改密码 |
| GET | `/api/auth/me` | 获取当前用户信息 |
| POST | `/api/auth/wx-login` | 微信小程序登录 |
| POST | `/api/auth/dev-login` | 开发模式登录 |

## 数据范围隔离

- **厂长**：跨水站汇总/分析，无 `station_id` 过滤
- **站长**：SQL 中 `WHERE station_id = #{stationId}` 自动过滤
- **配送员**：`WHERE delivery_person_id = #{staffId}` 限制本人任务

`AuthInterceptor` 从 token 中解析 `userId`/`userType`/`role`/`stationId`/`factoryId`，注入 `AuthContext` 供后续请求使用。

## 数据库

25 张表，详见根目录 README 数据库设计章节。

### 数据量（当前）

| 表 | 记录数 | 说明 |
|---|---|---|
| customer | 84 | 购水客户 |
| orders | 265 | 订单 |
| payment_record | 192 | 支付记录 |
| batch | 49 | 配送批次 |
| inventory | 90 | 库存（10站×9种水） |
| audit_log | 50 | 操作审计 |
| staff | 40 | 员工 |
| deposit_record | 47 | 押金流水 |
| ticket_record | 79 | 水票流水 |
| ticket_account | 35 | 水票账户 |
| risk_alert | 15 | 风险预警 |
| barrel_record | 20 | 退桶记录 |
| station | 10 | 水站 |
| water_type | 10 | 水品牌/规格 |
| factory | 3 | 水厂 |
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
