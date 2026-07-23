# AquaFlow — 桶装水配送管理系统

> 管理后台 · 水厂运营平台 · 微信小程序 三端协同，统一 API

---

## 项目定位

AquaFlow 是一个围绕**桶装水行业真实业务规则**设计的垂直领域管理系统，覆盖订单管理、配送调度、资产管理（水票/押金/退桶）、企业账期、多站数据分析与风险预警等核心业务环节。

不是通用 CRUD，而是深度绑定行业逻辑的业务平台。

### 行业差异

| 维度 | 通用电商 | AquaFlow |
|------|---------|----------|
| 购买单元 | 多品类购物车 | 单品类 + 桶数 |
| 消费频率 | 低频/随机 | 高频/周期性（每周/每两周） |
| 物流模式 | 快递到家 | 配送员上门 + 空桶回收 |
| 资产管理 | 无 | 桶是资产（押金追踪） |
| 支付方式 | 在线支付 | 线下为主 / 水票预购 / 企业账期 |

---

## 架构概览

### 三端协同 · 四种角色

管理后台（厂长/站长/配送员）与微信小程序（客户）共用同一套后端 API。

```
┌──────────────────────────┐   ┌──────────────────────────┐
│   管理后台 (Vue3)          │   │   微信小程序 (原生)        │
│   AquaFlow-frontend      │   │   miniapp-user           │
├──────────────────────────┤   ├──────────────────────────┤
│                          │   │                          │
│  厂长 ── 水厂运营/分析     │   │  客户 ── 下单/复购       │
│  站长 ── 订单/配送/资产    │   │        水桶/水票/地址     │
│  配送员 ── 我的配送        │   │                          │
│                          │   │                          │
└────────────┬─────────────┘   └────────────┬─────────────┘
             │                               │
             └──────────────┬────────────────┘
                            ▼
                  ┌────────────────────┐
                  │  Spring Boot 后端    │
                  │  + MyBatis + JWT    │
                  │  Port: 8080        │
                  └─────────┬──────────┘
                            ▼
                  ┌────────────────────┐
                  │  MySQL 8.x         │
                  │  25 张表            │
                  └────────────────────┘
```

### 管理后台角色权限

| 页面 | 路由 | 厂长 `factory` | 站长 `manager` | 配送员 `delivery` |
|------|------|:---:|:---:|:---:|
| 首页（站长/配送） | `/dashboard` | | ✅ | ✅ |
| 首页（厂长版） | `/factory-dashboard` | ✅ | | |
| 订单管理 | `/order` | | ✅ | |
| 批次管理 | `/batch` | | ✅ | |
| 库存管理 | `/inventory` | | ✅ | |
| 客户管理 | `/customer` | | ✅ | |
| 地址管理 | `/address` | | ✅ | |
| 水类型管理 | `/water` | | ✅ | |
| 退桶审批 | `/barrel-return` | | ✅ | |
| 水票管理 | `/ticket` | | ✅ | |
| 押金管理 | `/deposit` | | ✅ | |
| 支付管理 | `/payment` | | ✅ | |
| 员工管理 | `/staff` | | ✅ | |
| 地址地图 | `/address-map` | | ✅ | |
| 数据报表 | `/report` | | ✅ | |
| **水站运营** | `/station-ops` | ✅ | | |
| **数据分析** | `/analysis` | ✅ | | |
| **水站协同** | `/collaboration` | ✅ | | |
| **风险预警** | `/risk-alerts` | ✅ | | |
| **水站管理** | `/station-mgmt` | ✅ | | |
| **水厂管理** | `/factory-mgmt` | ✅ | | |
| **水站画像** | `/profile/:id` | ✅ | | |
| **我的配送** | `/my-deliveries` | | | ✅ |

### 数据范围隔离

- **厂长**：看到所有水站的汇总/统计/分析数据
- **站长**：只能看到本水站的数据（通过 `station_id` 过滤）
- **配送员**：只能看到分配给自己的配送任务（通过 `delivery_person_id` 过滤）

---

## 测试账号

所有密码均为 `123456`（厂长 admin 为 `admin123`）。

| 角色 | 账号 | 密码 | 所属水站 | 数据范围 |
|------|------|------|---------|---------|
| 厂长 | `admin` | `admin123` | - | 全部水站 |
| 站长 | `张建国` | `123456` | 张店水站 | 本站 |
| 站长 | `淄站长` | `123456` | 淄川水站 | 本站 |
| 站长 | `博站长` | `123456` | 博山水站 | 本站 |
| 站长 | `历站长` | `123456` | 历下水站 | 本站 |
| 站长 | `槐站长` | `123456` | 槐荫水站 | 本站 |
| 配送员 | `李永强` | `123456` | 张店水站 | 本站任务 |
| 配送员 | `王师傅` | `123456` | 张店水站 | 本站任务 |
| 配送员 | `赵师傅` | `123456` | 淄川水站 | 本站任务 |
| 配送员 | `孙师傅` | `123456` | 淄川水站 | 本站任务 |
| 配送员 | `周师傅` | `123456` | 博山水站 | 本站任务 |
| 配送员 | `吴师傅` | `123456` | 历下水站 | 本站任务 |
| 配送员 | `郑师傅` | `123456` | 历下水站 | 本站任务 |
| 配送员 | `陈师傅` | `123456` | 槐荫水站 | 本站任务 |

---

## 快速开始

### 环境要求

| 环境 | 版本 |
|------|------|
| JDK | 17+ |
| MySQL | 8.x |
| Node.js | 18+ |
| Gradle | 9.x（使用 gradlew） |

### 1. 初始化数据库

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS aquaflow DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p aquaflow < AquaFlow-backend/sql/migration_factory_ops.sql
mysql -u root -p aquaflow < AquaFlow-backend/sql/seed_full_data.sql
```

### 2. 配置数据库连接

`AquaFlow-backend/src/main/resources/application.yml` 中修改：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/aquaflow?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: 123456
```

### 3. 启动后端

```bash
cd AquaFlow-backend
.\gradlew.bat bootRun        # Windows
./gradlew bootRun            # Mac/Linux
```

后端运行于 `http://localhost:8080`

### 4. 启动管理后台

```bash
cd AquaFlow-frontend
npm install
npm run dev
```

管理后台运行于 `http://localhost:5173`

---

## 技术栈

| 端 | 技术 | 版本 |
|----|------|------|
| 后端 | Spring Boot | 4.0.6 |
| ORM | MyBatis | 4.0.1 |
| 数据库 | MySQL | 8.x |
| 构建 | Gradle | 9.x（Wrapper） |
| 语言 | Java | 17 |
| 认证 | JWT (双Token) + BCrypt | - |
| 前端框架 | Vue 3 (Composition API) | 3.5 |
| 构建工具 | Vite | 6.3 |
| UI 库 | Element Plus | 2.9 |
| 图表 | ECharts | 6.1 |
| 地图 | Leaflet (OpenStreetMap) | 1.9 |
| 状态管理 | Pinia | 3.0 |
| 路由 | Vue Router | 4.5 |
| HTTP | Axios | 1.7 |

---

## 项目结构

### 后端

```
AquaFlow-backend/
└── src/main/java/com/example/aquaflow/
    ├── AquaFlowApplication.java              # @EnableScheduling 启动类
    ├── common/Result.java                    # 统一返回 { code, message, data }
    ├── config/
    │   ├── CorsConfig.java                   # 跨域
    │   ├── WebMvcConfig.java                 # MVC 配置（拦截器注册）
    │   └── PasswordInitializer.java          # 初始密码 BCrypt 加密
    ├── constant/
    │   ├── OrderStatus.java                  # 订单状态（1待组批/2配送中/3已完成/4已组批/5已取消）
    │   ├── BatchStatus.java                  # 批次状态（1待出发/2配送中/3已完成）
    │   └── PaymentStatus.java                # 支付状态
    ├── entity/                               # 24 实体类
    │   ├── (核心业务) Customer, Orders, Address, Batch, Inventory, WaterType
    │   ├── (资产管理) TicketAccount, TicketRecord, DepositRecord, BarrelRecord
    │   ├── (组织) Factory, Station, Staff, CompanyInfo
    │   ├── (运营) RiskAlert, StockTransfer, PaymentRecord
    │   ├── (快捷) OrderTemplate, OrderTemplateItem, OrderImage
    │   └── (基础设施) AuditLog, CustomerStationRecord, StationPaymentConfig
    ├── mapper/                               # MyBatis Mapper 接口（22个）
    │   └── factory/                          # 运营平台 Mapper（2个）
    ├── service/
    │   ├── impl/                             # 业务实现（18个）
    │   ├── factory/                          # 厂长业务接口（5个）
    │   └── impl/factory/                     # 厂长业务实现（5个）
    ├── controller/
    │   ├── LoginController.java              # 统一登录（JWT 双Token）
    │   ├── factory/                          # 厂长 API（5个）
    │   └── *.java                            # 站长/通用 API（21个）
    ├── dto/                                  # 8 个数据传输对象
    ├── annotation/RequireRole.java           # 角色权限注解
    ├── aspect/RequireRoleAspect.java         # AOP 角色校验切面
    ├── interceptor/AuthInterceptor.java      # JWT 鉴权拦截器
    ├── exception/
    │   ├── BusinessException.java            # 业务异常
    │   ├── ValidationException.java          # 参数校验异常
    │   ├── ResourceNotFoundException.java    # 资源不存在异常
    │   └── GlobalExceptionHandler.java       # 全局异常处理
    └── util/
        ├── AuthContext.java                  # 请求上下文（userId/userType/role/stationId）
        ├── JwtUtil.java                      # JWT 工具（双Token生成/校验）
        └── PasswordUtil.java                 # BCrypt 密码工具
```

### 前端

```
AquaFlow-frontend/src/
├── api/index.js                  # 统一 API 定义（20+ 模块）
├── router/index.js               # 路由 + 角色守卫
├── utils/request.js              # Axios（Token自动续期）
├── styles/theme.css              # 亮色/暗色主题
├── components/GlobalSearch.vue   # 全局搜索
├── App.vue                       # 根布局 + 三角色菜单
├── views/
│   ├── shared/login/             # 登录页（双栏设计）
│   ├── station/                  # 站长/配送员页面（14页）
│   │   ├── dashboard/
│   │   ├── delivery/             # 配送员专用
│   │   ├── order/
│   │   ├── batch/
│   │   ├── inventory/
│   │   ├── customer/
│   │   ├── address/
│   │   ├── address-map/
│   │   ├── water/
│   │   ├── barrel-return/
│   │   ├── ticket/
│   │   ├── deposit/
│   │   ├── payment/              # 支付管理
│   │   ├── staff/
│   │   └── report/
│   └── factory/                  # 厂长页面（8页）
│       ├── dashboard/
│       ├── station-ops/
│       ├── analysis/
│       ├── profile/
│       ├── collaboration/
│       ├── risk-alerts/
│       ├── station-mgmt/
│       └── factory-mgmt/
└── main.js
```

---

## 认证系统

### JWT 双 Token

| 令牌 | 存于 | 有效期 | 用途 |
|------|------|--------|------|
| `accessToken` | 前端 localStorage + 请求头 | 30 分钟 | API 鉴权 |
| `refreshToken` | 前端 localStorage + 数据库 | 7 天 | 无感续期 |

### 登录流程

```
POST /api/auth/login { username, password }
    │
    ├── admin + admin123 → 厂长
    ├── staff 姓名 + 123456 → STATION_MANAGER→站长 / DELIVERY→配送员
    └── customer 表兼容（迁移过渡期）
    │
    ▼
返回 { accessToken, refreshToken, role, stationId, staffId, nickname }
存储到 localStorage
```

### Token 续期

- 请求 401 时自动调用 `/api/auth/refresh` 续期
- 续期成功 → 重放原请求
- 续期失败 → 清除登录状态，跳转登录页
- 并发请求排队等待续期（防止多次刷新）

### 授权：AOP 角色控制

- `@RequireRole({"FACTORY_ADMIN", "STATION_MANAGER"})` — 注解标注在 Controller 方法上
- `RequireRoleAspect` — AOP 切面，拦截带注解的方法，校验 `AuthContext` 中的角色
- `AuthContext` — ThreadLocal 存储当前请求的 `userId`/`userType`/`role`/`stationId`
- `AuthInterceptor` — 从 JWT token 解析用户信息注入 `AuthContext`

所有管理端写操作（POST/PUT/DELETE）均通过 `@RequireRole` 保护，客户 token 无法调用。

---

## 数据库设计 — 25 张表

### 基础数据（4表）

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `factory` | 水厂 | `status`(0停用/1启用) |
| `station` | 水站 | `factory_id` 关联水厂，`status`(0停用/1启用) |
| `water_type` | 水品牌+规格 | UNIQUE(`name`,`spec`) 同品牌不同规格 |
| `staff` | 员工/登录账号 | `role`(FACTORY_ADMIN/STATION_MANAGER/DELIVERY)，`password` BCrypt |

### 业务核心（6表）

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `customer` | 购水客户 | `station_id` 归属，`deposit_balance` 押金，`customer_type`(1个人/2企业)，`tags` 标签 |
| `address` | 配送地址 | `customer_id` 关联，`tag` 小区分组，`lat`/`lng` 地图坐标 |
| `orders` | 订单 | `status`(1待组批/2配送中/3已完成/4已组批/5已取消)，`delivery_bucket_qty`/`return_bucket_qty` 送退桶追踪 |
| `inventory` | 库存 | UNIQUE(`station_id`,`water_type_id`) 每站每种水一条，`quantity<20` 低库存预警 |
| `batch` | 配送批次 | `status`(1待出发/2配送中/3已完成)，`delivery_person_id` 配送员 |
| `batch_order` | 批次-订单关联 | UNIQUE(`batch_id`,`order_id`) |

### 资产管理（5表）

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `ticket_account` | 水票余额 | UNIQUE(`customer_id`,`water_type_id`) 按水类型分账户 |
| `ticket_record` | 水票流水 | `increase_qty`/`decrease_qty`，`source`(购买/消费) |
| `deposit_record` | 押金流水 | `type`(1充值/2退还/3扣除) |
| `barrel_record` | 退桶申请 | `status`(1待处理/2已确认/3已退押金/4已驳回)，`deposit_refund` |
| `payment_record` | 支付记录 | `payment_method`(1微信/2现金/3水票)，`status`(1待确认/2已付款/3已退款/4已取消) |

### 运营管理（5表）

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `risk_alert` | 风险预警 | `alert_type`(ORDER_DECLINE/INVENTORY_BACKLOG/LOW_STOCK/CUSTOMER_LOSS/NO_ACTIVITY) |
| `stock_transfer` | 水站调拨 | `from_station_id`→`to_station_id`，`status`(1待审批/2已审批/3已完成/4已取消) |
| `audit_log` | 操作审计日志 | `user_id`, `module`, `action`, `target`, `ip` |
| `user_token` | refresh_token 存储 | `user_id`, `user_type`, `refresh_token`, `expire_time` |
| `station_payment_config` | 支付配置 | 各种支付方式开关 |

### 快捷功能（2表）

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `order_template` | 订水模板 | `customer_id`+`name`（如"家里"/"公司"）一键复购 |
| `order_template_item` | 模板明细 | `template_id`, `water_type_id`, `quantity` |

### 辅助（3表）

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `company_info` | 企业客户 | UNIQUE(`customer_id`)，`due_days` 账期天数 |
| `customer_station_record` | 客户迁移记录 | `from_station_id`→`to_station_id` |
| `order_image` | 配送照片 | `type`(1正常/2异常) |

---

## 数据范围隔离

- **厂长**：看到所有水站的汇总/统计/分析数据
- **站长**：只能看到本水站的数据（通过 `station_id` 过滤）
- **配送员**：只能看到分配给自己的配送任务（通过 `delivery_person_id` 过滤）

---

## 核心业务流程

### 订单流转

```
待组批(1) ──组批──→ 已组批(4) ──出发──→ 配送中(2) ──完成──→ 已完成(3)
   ↑                    │                                    │
   │                    └── 删除批次 → 恢复待组批              │
   │                                                         │
   └──── 配送未完成 → 恢复库存 + 恢复待组批                    │
                                                             │
               已完成 → 付款 / 减水票 / 更新押金 / 退桶 ───────┘
```

### 配送流程

```
收货入库 → 客户下单(电话/微信/小程序)
               │
               ▼
          地图选单 → 创建批次 → 分配配送员
               │
               ▼
          开始配送 → 完成配送 → 回桶登记
               │
               └── 未完成订单退回库存
```

### 调拨流程

```
水站 A 发起调拨 → 厂长审批 → 水站 A 出库 + 水站 B 入库 → 完成
                     ↓
                审批不通过 → 取消
```

### 风险预警机制（每日 @Scheduled 自动检测）

```
├── 近7天销量下降 > 50% → ORDER_DECLINE
├── 库存超量（周转率偏低）→ INVENTORY_BACKLOG
├── 库存不足（< 阈值）→ LOW_STOCK
├── 60 天未下单客户数 → CUSTOMER_LOSS
└── 门店停用状态 → NO_ACTIVITY
```

---

## 关键设计决策

### 1. 桶是资产，不是消耗品

每个订单 `delivery_bucket_qty` 和 `return_bucket_qty` 追踪桶的流动。客户持有桶数 = 送出 - 回收。桶有押金，退桶时退还。

### 2. 三端协同，角色控制

管理后台（厂长/站长/配送员）和微信小程序（客户）共用后端 API。管理后台登录后根据 staff 表 `role` 决定菜单和数据范围。后端通过 `@RequireRole` AOP 注解 + `AuthContext` 实现接口级权限控制，所有管理端写操作均有保护。

### 3. JWT 双 Token 无感续期

access_token 30 分钟过期，refresh_token 7 天。401 时自动续期并重放请求，用户体验无感知。

### 4. 水站级数据隔离

站长只看到本站数据（`station_id`），配送员只看到自己任务（`delivery_person_id`），厂长看到全站汇总。前端 `request.js` 自动注入 `stationId`。

### 5. 批次配送

多个订单合并一批，支持分配配送员、分批完成（部分订单继续配送）。降低配送成本。

### 6. 没有在线支付

桶装水行业以线下支付为主，支持水票预购和企业账期。支付方式：微信/现金/水票/挂账。

---

## 核心 API

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 统一登录（返回双Token） |
| POST | `/api/auth/logout` | 登出（清除 refresh_token） |
| POST | `/api/auth/refresh` | 刷新 access_token |
| POST | `/api/auth/change-password` | 修改密码 |
| GET | `/api/auth/me` | 获取当前用户 |

### 厂长模块（`/api/factory-ops/`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/factory-ops/overview` | 水厂总览指标 |
| GET | `/factory-ops/stations/ranking` | 水站销量排行 |
| GET | `/factory-ops/stations/trend` | 各站销量趋势 |
| GET | `/factory-ops/analysis/sales-decline` | 销量下降分析 |
| GET | `/factory-ops/analysis/customer-churn` | 客户流失分析 |
| GET | `/factory-ops/analysis/inventory-pressure` | 库存压力 |
| GET | `/factory-ops/analysis/suggestions` | 智能建议 |
| GET | `/factory-ops/profile/{id}` | 单站画像 |
| GET/POST/PUT | `/factory-ops/transfers` | 库存调拨 |
| GET/POST/PUT | `/factory-ops/alerts` | 风险预警 |
| POST | `/factory-ops/alerts/check` | 手动检测预警 |

### 站长模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT | `/customers` | 客户 CRUD |
| GET/POST/PUT | `/addresses` | 地址 CRUD |
| GET/POST | `/water-types` | 水类型 CRUD |
| GET/POST | `/inventory` | 库存查询/入库 |
| GET/POST/PUT | `/orders` | 订单（含取消） |
| GET/POST/DELETE | `/batches` | 批次（含分配配送员） |
| GET/POST/PUT | `/payments` | 支付管理 |
| GET/POST | `/tickets` | 水票 |
| GET/POST/PUT | `/deposit-records` | 押金 |
| GET/PUT | `/barrels` | 退桶 |
| GET/POST/PUT/DELETE | `/staff` | 员工 |
| GET/POST/PUT/DELETE | `/stations` | 水站（站长可管理下属站） |
| GET/POST/PUT/DELETE | `/factories` | 水厂 |

---

## License

MIT
