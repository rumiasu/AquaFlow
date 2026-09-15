# AquaFlow Backend

Spring Boot 4.0.6 + MyBatis + MySQL 的后端服务，为管理后台（站长/配送员）和微信小程序（客户）提供统一 API。

## 项目规模

| 指标 | 数量 |
|------|------|
| Java 文件 | 150 |
| 实体类 | 24 |
| Mapper 接口 | 24 |
| Controller | 26 |
| Service 接口 | 26 |
| Service 实现 | 23 |
| 数据库表 | 32 |

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
│   ├── OrderStatus.java                  # 订单状态（1待配送/2配送中/3已送达/4已完成/5已取消，canonical 连续编号）
│   └── PaymentStatus.java                # 支付状态（1待确认/2已付款/3已退款/4已取消）
├── entity/                               # 24 个实体
│   ├── 核心业务: Customer, Orders, Address, Inventory, WaterType
│   ├── 资产管理: TicketAccount, TicketRecord, DepositRecord, BarrelRecord
│   ├── 组织: Station, Staff, CompanyInfo
│   ├── 运营: PaymentRecord
│   ├── 快捷: OrderTemplate, OrderTemplateItem, OrderImage
│   └── 基础设施: AuditLog, CustomerStationRecord, StationPaymentConfig
├── mapper/                               # MyBatis Mapper
├── service/
│   └── impl/                             # 业务实现
├── controller/
│   ├── LoginController.java              # 统一登录/登出/续期/改密/获取当前用户
│   └── *.java                            # 站长/通用 API
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
| `accessToken` | 2 小时 | 前端 localStorage + 请求头 | API 鉴权 |
| `refreshToken` | 7 天 | 前端 localStorage + 数据库 `user_token` 表 | 无感续期 |

### 登录流程

```
POST /api/auth/login { username, password }
  ├─ 员工姓名 → staff 表查找 → role=STATION_MANAGER→manager / DELIVERY→delivery
  └─ customer 表兼容（旧系统迁移过渡期）
```
密码校验：先明文 `equals()`，若不匹配则 BCrypt `PasswordUtil.matches()`。

### AOP 角色控制

- `@RequireRole({"STATION_MANAGER"})` 标注在 Controller 方法上
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

- **站长**：SQL 中 `WHERE station_id = #{stationId}` 自动过滤
- **配送员**：`WHERE delivery_person_id = #{staffId}` 限制本人任务

`AuthInterceptor` 从 token 中解析 `userId`/`userType`/`role`/`stationId`，注入 `AuthContext` 供后续请求使用。

## 数据库

37 张业务表（无视图；权威基线是 `sql/schema.sql`），详见 `sql/README.md` 与根目录 README。

### 数据量（当前）

| 表 | 记录数 | 说明 |
|---|---|---|
| customer | 84 | 购水客户 |
| orders | 265 | 订单 |
| payment_record | 192 | 支付记录 |
| inventory | 90 | 库存（10站×9种水） |
| audit_log | 50 | 操作审计 |
| staff | 40 | 员工 |
| deposit_record | 47 | 押金流水 |
| ticket_record | 79 | 水票流水 |
| ticket_account | 35 | 水票账户 |
| barrel_record | 20 | 退桶记录 |
| station | 10 | 水站 |
| water_type | 10 | 水品牌/规格 |
