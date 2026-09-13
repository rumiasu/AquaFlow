# AquaFlow — 桶装水配送管理系统

> 管理后台 · 微信小程序 三端协同，统一 API

---

## 📐 设计文档（先读这个）

本仓库的设计说明按模块拆成一组文档，**每一篇都回答"为什么这么设计、为什么不那么设计"**，
包含建模推导过程、被否决的方案和真实踩坑复盘：

| # | 文档 |
|---|------|
| 00 | [文档导航与讲述路线](./docs/design/00-文档导航.md) |
| 01 | [项目总览与技术选型](./docs/design/01-项目总览与技术选型.md) |
| 02 | [领域建模](./docs/design/02-领域建模.md) |
| 03 | [**桶权益模型（核心设计）**](./docs/design/03-桶权益模型.md) |
| 04 | [订单与状态机](./docs/design/04-订单与状态机.md) |
| 05 | [支付与资金](./docs/design/05-支付与资金.md) |
| 06 | [权限与安全](./docs/design/06-权限与安全.md) |
| 07 | [数据一致性与对账](./docs/design/07-数据一致性与对账.md) |
| 08 | [踩坑与复盘](./docs/design/08-踩坑与复盘.md) |
| 09 | [面试速答 Q&A](./docs/design/09-面试速答.md) |

> 本 README 是**功能与操作层面的总览**（状态定义、接口清单、启动方式等）；
> 设计决策、建模推导、踩坑复盘在上述 `docs/design/` 中。
> 面向代码的速查约定见 [docs/AGENTS.md](./docs/AGENTS.md)。

---

## 项目定位

AquaFlow 是一个围绕**桶装水行业真实业务规则**设计的垂直领域管理系统，覆盖订单管理、配送调度、资产管理（水票/押金/退桶）、企业账期等核心业务环节。

不是通用 CRUD，而是深度绑定行业逻辑的业务平台。

### 行业差异

| 维度 | 通用电商 | AquaFlow |
|------|---------|----------|
| 购买单元 | 多品类购物车 | 单品类 + 桶数 |
| 消费频率 | 低频/随机 | 高频/周期性（每周/每两周） |
| 物流模式 | 快递到家 | 配送员上门 + 空桶回收 |
| 资产管理 | 无 | 桶是资产（押金追踪） |
| 支付方式 | 在线支付 | 线下为主 / 水票预购 / 企业账期 |

### 客户与水站关系模型

客户是全局身份，不绑定单一水站。客户可自由选择不同水站下单，不同订单可对应不同水站。

| 关系 | 字段 | 含义 |
|------|------|------|
| 订单归属 | `orders.station_id` | 本次交易所属水站（客户自选） |
| 订单履约 | `orders.delivery_station_id` | 实际配送水站（可被站长切换） |
| 资产归属 | `ticket_account.station_id` | 水票按水站隔离 |
| 资产归属 | `customer_barrel_asset.station_id` | 桶资产按水站隔离 |
| 资产归属 | `customer_deposit_account.station_id` | 押金按水站隔离 |
| 客户授权 | `customer_station_config` | 客户×水站权限配置（当前仅线下支付授权） |

> 客户在水站 A 购买的水票、押金等资产属于水站 A，切换到水站 B 下单不会迁移这些资产。

---

## 架构概览

### 两端协同 · 三种角色

**在维护的端只有两个原生微信小程序**，共用同一套后端 API。
（原 Vue3 管理后台 `AquaFlow-frontend` 已不在仓库中，仅 `archive/legacy-web-frontend` 留档，
不再维护；站长与配送员的全部管理动作现在都在 `miniapp-delivery` 里完成。）

```
        ┌────────────────────────────┐
        │  微信小程序 (原生, 无框架)     │
        ├──────────────┬─────────────┤
        │ miniapp-user │ miniapp-delivery
        │  客户         │  站长 + 配送员
        ├──────────────┼─────────────┤
        │ 下单 / 复购    │ 站长：订单/派单/库存/
        │ 水桶/水票/地址 │      商品/客户/员工/
        │ 押金/账单     │      退桶审批/待确认收款
        │              │ 配送员：待接单/配送中/
        │              │      收款/异常上报
        └──────┬───────┴──────┬──────┘
               │              │
               └──────┬───────┘
                      ▼
            ┌────────────────────┐
            │  Spring Boot 后端    │
            │  + MyBatis + JWT    │
            │  Port: 8080         │
            └─────────┬──────────┘
                      ▼
            ┌────────────────────┐
            │  MySQL 8.x          │
            └────────────────────┘
```

### 小程序功能与角色权限

`miniapp-delivery` 内的 `pages/station-mgmt/**` 为站长专用（后端以 `@RequireRole("STATION_MANAGER")` 兜底）：

| 功能 | 路由 | 站长 `STATION_MANAGER` | 配送员 `DELIVERY` |
|------|------|:---:|:---:|
| 首页（站长协作台：待分配/抢单池/外派） | `pages/coordination/index` | ✅ | |
| 配送工作台（待接单/配送中/已完成） | `pages/home/index` | ✅ | ✅ |
| 数据看板 | `pages/station-mgmt/dashboard/index` | ✅ | |
| 订单管理 | `pages/station-mgmt/orders/index` | ✅ | |
| 客户查询 / 客户画像 | `pages/station-mgmt/customers/**` | ✅ | |
| 商品与库存 | `pages/station-mgmt/products/index` | ✅ | |
| 退桶审批 | `pages/station-mgmt/barrel-return/index` | ✅ | |
| 待确认收款 | `pages/station-mgmt/payments/index` | ✅ | |
| 员工管理 | `pages/station-mgmt/staff/**` | ✅ | |
| 我的（含绑定审批） | `pages/mine/index` | ✅ | ✅ |
| 订单详情（接单/转单/退回/外派/异常上报） | `pages/order/detail` | ✅ | ✅ |
| 完成配送（回桶核对+拍照） | `pages/order/complete` | ✅ | ✅ |
| 空桶记录 / 配送历史 / 转让记录 | `pages/barrel-records`、`history`、`transfer` | ✅ | ✅ |
| 意见反馈 / 设置 / 编辑资料 | `pages/report`、`settings`、`mine/edit` | ✅ | ✅ |

`miniapp-user`（顾客端，22 页）：登录 / 订水首页 / 商城+搜索 / 商品详情 / 下单确认 /
订单列表与详情 / 桶账与退桶申请 / 水票 / 地址簿 / 账单记录 / 常用订单模板 / 服务记录 /
公告 / 企业资料 / 关于。

### 数据范围隔离

- **站长**：只能看到本水站的数据（通过 `station_id` 过滤）
- **配送员**：只能看到分配给自己的配送任务（通过 `delivery_staff_id` 过滤）

---

## 账号与初始化

**本仓库不含任何测试账号**，也不再提供种子数据 —— 基线库是空库，
基础数据（水站 → 员工 → 商品 → 库存）由管理员在实际环境里手动创建，
避免误把测试数据带进验收/生产。参考 `AquaFlow-backend/sql/README.md`。

- 员工登录：后端 `POST /api/auth/login`（姓名 + BCrypt 密码）。
  密码哈希由 `PasswordInitializer` 在启动时为**没有密码的员工**写入初始值（`123456`，`admin` 账号为 `admin123`），
  上线前必须逐个改掉（`POST /api/auth/change-password`）。
- 微信端登录：`POST /api/auth/wx-login`（客户）/ `POST /api/auth/wx-login-staff`（员工），
  均由微信 `code2Session` 换取 openid。
- 开发态免微信登录：`POST /api/auth/dev-login`，**默认关闭**，
  需同时满足 `DEV_LOGIN_ENABLED=true` 且非 prod profile；生产环境该 Controller 物理不加载。

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
cd AquaFlow-backend/sql
mysql -u root -p < init.sql        # 建库 + schema.sql（必须在本目录执行，init.sql 用相对路径 SOURCE）
```

> **只建结构，不灌任何种子数据**（空库）。原先文档里的 `seed_full_data.sql` 已移入
> `sql/archive/` 且**不可执行**（停留在 V1 大迁移之前，引用 `factory` / `water_type` 等已删对象）。
> 基础数据按依赖顺序手动创建：水站 → 员工 → 商品 → 库存。详见 `AquaFlow-backend/sql/README.md`。

**已有老库升级**必须按 `sql/README.md`「基线之后必须补跑的迁移」顺序补跑脚本，
否则运行期会因缺表/缺列崩溃。注意：**Flyway 未启用**，所有迁移都是手工执行。

### 2. 配置连接与密钥（一律走环境变量，禁止写回 yml）

敏感配置在 `application.yml` 里**不提供默认值**，缺失时 `RequiredConfigChecker` 会在启动阶段直接失败退出：

| 环境变量 | 说明 |
|---|---|
| `JWT_SECRET` | 签名密钥，长度 ≥ 32；泄露 = 任何人可伪造任意身份 token |
| `WX_APP_ID` / `WX_APP_SECRET` | 微信小程序凭据 |
| `COS_REGION` / `COS_SECRET_ID` / `COS_SECRET_KEY` / `COS_BUCKET_NAME` | 腾讯云 COS（未配置时仅对象存储不可用，不阻塞启动） |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接 |
| `CORS_ALLOWED_ORIGINS` | 生产必须填真实域名 |
| `DEV_LOGIN_ENABLED` | 开发登录后门，**生产必须 false**（prod profile 下该 Controller 物理不加载） |

本地开发：把真实值写进 `src/main/resources/application-local.yml`（**已 gitignore，禁止提交**），
默认 profile 就是 `local`；生产用 `--spring.profiles.active=prod` + 纯环境变量。
完整清单见 `AquaFlow-backend/.env.example`。

### 3. 启动后端

```bash
cd AquaFlow-backend
.\gradlew.bat bootRun        # Windows
./gradlew bootRun            # Mac/Linux
```

后端运行于 `http://localhost:8080`

### 4. 打开小程序

用微信开发者工具分别打开 `miniapp-user/`（顾客端）与 `miniapp-delivery/`（站长+配送员端）目录，
无 npm 构建步骤。API 基址在各自 `config/api.js` 里配置。

> ⚠️ 两端的 `prod.baseUrl` 目前都是占位符 `https://your-domain.com`，
> 而 `project.config.json` 里 `urlCheck: false` 导致开发者工具不会提示 ——
> **发版前必须替换成真实域名**，否则 release 版指向一个不存在的地址。

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
| 地图 | Leaflet (OpenStreetMap) | 1.9 |
| 路由 | Vue Router | 4.5 |
| HTTP | Axios | 1.7 |

---

## 项目结构

### 后端

```
AquaFlow-backend/
└── src/main/java/com/example/aquaflow/
    ├── AquaFlowApplication.java              # 启动类
    ├── common/Result.java                    # 统一返回 { code, message, data }
    ├── config/
    │   ├── CorsConfig.java                   # 跨域
    │   ├── WebMvcConfig.java                 # MVC 配置（拦截器注册）
    │   ├── PasswordInitializer.java          # 初始密码 BCrypt 加密
    │   └── OssConfiguration.java             # 腾讯云 COS 配置
    ├── entity/                               # 28 个实体类
    │   ├── (核心业务) Customer, Orders, OrderItem, Address, Inventory, Product
    │   ├── (资产管理) TicketAccount, TicketRecord, DepositRecord, BarrelRecord,
    │   │              CustomerBarrelAsset, CustomerBarrelInTransit, CustomerDepositAccount
    │   ├── (组织) Station, Staff, StaffStationApplication, CompanyInfo, CustomerStationConfig
    │   ├── (支付) PaymentRecord
    │   ├── (快捷) OrderTemplate, OrderTemplateItem, OrderImage, OrderBarrelException
    │   └── (基础设施) AuditLog, UserToken, Notice, Feedback, FileInfo
    ├── mapper/                               # MyBatis Mapper 接口（27个）
    ├── service/                              # 业务接口（20个）
    │   └── impl/                             # 业务实现
    ├── controller/                           # API 控制器（27个）
    │   ├── LoginController.java              # 统一登录（JWT 双Token）
    │   ├── OrderController.java              # 订单 CRUD
    │   ├── DeliveryController.java           # 配送员订单 API（672行）
    │   ├── DeliveryBindingController.java    # 配送员绑定/解绑（624行）
    │   ├── ManagerProductController.java     # 商品+库存管理（288行）
    │   ├── ManagerExceptionController.java   # 桶异常管理
    │   ├── PaymentController.java            # 支付管理
    │   ├── BarrelController.java             # 桶资产
    │   ├── CustomerController.java           # 客户
    │   ├── AddressController.java            # 地址
    │   ├── StationController.java            # 水站
    │   ├── StaffController.java              # 员工
    │   ├── InventoryController.java          # 库存
    │   ├── DashboardController.java          # 仪表盘
    │   ├── TicketAccountController.java      # 水票
    │   ├── TicketRecordController.java       # 水票流水
    │   ├── DepositRecordController.java      # 押金
    │   ├── OrderTemplateController.java      # 常用订单模板
    │   ├── OrderImageController.java         # 订单图片
    │   ├── FileManageController.java         # 文件管理
    │   ├── CommonController.java             # 通用上传
    │   ├── SearchController.java             # 全局搜索
    │   ├── NoticeController.java             # 公告
    │   ├── FeedbackController.java           # 反馈
    │   └── CompanyInfoController.java        # 企业信息
    ├── dto/                                  # 9 个数据传输对象
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
        ├── PasswordUtil.java                 # BCrypt 密码工具
        ├── CosUtil.java                      # 腾讯云 COS 工具
        └── StationUtil.java                  # 水站工具
```

### 前端（已不在本仓库）

原 Vue3 管理后台 `AquaFlow-frontend` 已从仓库移除，仅 `archive/legacy-web-frontend/` 留档、不再维护。
站长与配送员的全部管理界面现在都在 `miniapp-delivery` 里（见 `### 小程序` 与本文档「小程序功能与角色权限」）。

### 小程序

```
├── miniapp-user/                 # 客户端（3 Tab: 首页/订单/我的）
│   └── pages/
│       ├── home/                 # 首页（核心下单页）
│       ├── order/                # 订单（列表/详情/创建/成功）
│       ├── address/              # 地址（列表/编辑）
│       ├── barrel/               # 水桶管理
│       ├── ticket/               # 水票管理
│       ├── shop/                 # 商城
│       ├── template/             # 常用订单
│       ├── payment/              # 支付（支付/记录）
│       ├── mine/                 # 我的（个人中心/编辑/企业资料）
│       ├── service/              # 客服与反馈
│       ├── notice/               # 公告
│       └── login/                # 登录
└── miniapp-delivery/             # 配送端（站长 + 配送员，3 Tab: 配送/协调/我的）
    └── pages/
        ├── home/                 # 配送列表
        ├── order/                # 订单（详情/完成）
        ├── coordination/         # 协调（站长6Tab管理）
        ├── station-mgmt/         # 水站管理
        ├── mine/                 # 我的
        ├── login/                # 登录
        ├── role-select/          # 角色选择
        ├── bind-wait/            # 绑定等待
        ├── apply-bind/           # 申请绑定
        ├── history/              # 配送历史
        ├── transfer/             # 转让记录
        └── barrel-records/       # 空桶记录
```

---

## 认证系统

### JWT 双 Token

| 令牌 | 存于 | 有效期 | 用途 |
|------|------|--------|------|
| `accessToken` | 前端 localStorage + 请求头 | 2 小时 | API 鉴权 |
| `refreshToken` | 前端 localStorage + 数据库 | 7 天 | 无感续期 |

### 登录流程

```
管理后台 POST /api/auth/login { username, password }
    │
    ├── staff 姓名 + 密码 → STATION_MANAGER→站长 / DELIVERY→配送员
    │
    ▼
返回 { accessToken, refreshToken, role, stationId, staffId, nickname }
存储到 localStorage

微信小程序 POST /api/auth/wx-login { code }
    │
    ├── 客户：openid → 查/建 customer → 签发 JWT
    ├── 配送端：openid → 查 staff → 已绑定返回信息 / 未绑定返回 UNSELECTED
    └── 首次配送端：select-role 选择站长或配送员
```

### Token 续期

- 请求 401 时自动调用 `/api/auth/refresh` 续期
- 续期成功 → 重放原请求
- 续期失败 → 清除登录状态，跳转登录页
- 并发请求排队等待续期（防止多次刷新）

### 授权：AOP 角色控制

- `@RequireRole({"STATION_MANAGER"})` — 注解标注在 Controller 方法上
- `RequireRoleAspect` — AOP 切面，拦截带注解的方法，校验 `AuthContext` 中的角色
- `AuthContext` — ThreadLocal 存储当前请求的 `userId`/`userType`/`role`/`stationId`
- `AuthInterceptor` — 从 JWT token 解析用户信息注入 `AuthContext`

### 公开路径（无需登录）

- `/api/auth/*` — 登录相关
- `/api/stations/public` — 公开水站列表（客户选站）
- `/api/stations/search` — 搜索水站

---

## 数据库设计

### 核心表结构

#### 组织与员工

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `station` | 水站 | `status`(1营业/2停业)，`offline_payment_enabled`(线下支付总开关)，`creator_staff_id`(创建者站长) |
| `staff` | 员工/登录账号 | `role`(STATION_MANAGER/DELIVERY)，`station_id`(所属水站，NULL=未绑定)，`password_hash`(BCrypt) |
| `staff_station_application` | 绑定申请 | `type`(1绑定/2解绑)，`status`(1待审批/2已同意/3已拒绝/4已取消) |

#### 客户与地址

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `customer` | 购水客户 | `customer_type`(1个人/2企业)，`openid`(微信唯一) |
| `address` | 配送地址 | `customer_id` 关联，`province`/`city`/`district` 省市区，`detail` 详细地址，`lat`/`lng` 地图坐标，`label`(家/公司) |
| `company_info` | 企业资料 | UNIQUE(`customer_id`)，`due_days` 账期天数 |
| `customer_station_config` | 客户×水站配置 | `offline_payment_enabled`(线下支付授权) |

#### 商品与库存

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `product` | 商品 | `category`(1桶装水/2瓶装水/3饮水器)，`price`/`deposit`/`status` |
| `inventory` | 水站库存 | UNIQUE(`station_id`,`product_id`) 每站每商品一条，`quantity<20` 低库存预警 |

#### 订单

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `orders` | 订单主表 | `status`(1待配送/3配送中/4已送达/5已完成/6已取消)，`station_id`(归属站)/`delivery_station_id`(履约站) 双站模型，`delivery_bucket_qty`/`return_bucket_qty` 送退桶追踪 |
| `order_item` | 订单商品明细 | `product_name_snapshot`/`brand_snapshot`/`spec_snapshot` 商品快照，支持一单多商品 |
| `order_image` | 配送照片 | `type`(1正常/2异常) |
| `order_barrel_exception` | 桶异常记录 | 配送完成时自动创建 |

#### 资产管理

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `customer_barrel_asset` | 客户持有桶资产 | UNIQUE(`customer_id`,`product_id`,`station_id`) 按水站隔离 |
| `customer_barrel_in_transit` | 配送中桶 | 下单收押金但未送达确认的桶，`status`(PENDING/DELIVERED/CANCELLED)，配送完成自动转入持有桶资产 |
| ~~`customer_owed_barrel`~~ | ~~欠桶台账~~ | **已归档**（0 行、全仓零读写）：由 `migration_v25` 备份后改名为 `bak_v25_customer_owed_barrel_retired`。欠桶改读 `customer_barrel_over` |
| `barrel_record` | 桶变动记录 | `type`(1新增/2退桶/3丢失/4损坏/5赔偿/6人工调整) |
| `customer_deposit_account` | 押金余额 | UNIQUE(`customer_id`,`station_id`) 按水站隔离 |
| `deposit_record` | 押金流水 | `type`(1新增/2退/3丢桶赔偿/4其他) |
| `ticket_account` | 水票余额 | UNIQUE(`customer_id`,`product_id`,`station_id`) 三元唯一 |
| `ticket_record` | 水票流水 | `increase_qty`/`decrease_qty`，`ticket_source`(1线上/2线下) |

#### 支付

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `payment_record` | 支付记录 | `payment_method`(1微信/2水票/3线下)，`status`(1待支付/2已支付/3已退款/4已取消) |
| `station_payment_config` | 站点支付配置 | 各种支付方式开关 |

#### 系统

| 表名 | 作用 | 关键设计 |
|------|------|--------|
| `user_token` | refresh_token 存储 | `user_id`+`user_type`(staff/customer)，`expire_time` |
| `audit_log` | 操作审计日志 | `user_id`, `module`, `action`, `target`, `ip` |
| `file_info` | 文件管理 | COS 对象键 |
| `notice` | 公告 | `type`(1系统/2水站/3促销) |
| `feedback` | 意见反馈 | `category`(bug/feature/other) |
| `order_template` | 订水模板 | `customer_id`+`name`（如"家里"/"公司"）一键复购 |
| `order_template_item` | 模板明细 | `template_id`, `product_id`, `quantity` |

### 数据库迁移记录

| 版本 | 文件 | 说明 |
|------|------|------|
| v1 | `migration_v1_final.sql` | 初始表结构 |
| v14 | `migration_v14_add_order_amounts.sql` | 订单金额字段 |
| v15 | `migration_v15_order_item_water_type.sql` | 订单商品明细 |
| v18 | `migration_v18_offline_payment.sql` | 线下支付配置 |
| v19 | `migration_v19_fix_barrel_asset_backfill.sql` | 补录历史桶资产 |
| v20 | `migration_v20_address_region.sql` | 地址省市区拆分 |

---

## 核心业务流程

### 订单状态流转

状态以 `constant/OrderStatus.java` 为唯一真值来源：**1 待配送 / 2 配送中 / 3 已送达 / 4 已完成 / 5 已取消**
（连续编号；历史版本用过 1/3/4/5/6，已废弃）。

```
待配送(1) ──接单──→ 配送中(2) ──完成配送──→ 按支付方式分流：
    ↑                                    │
    │                                    ├── 微信/水票（已付） → 已完成(4)
    │                                    ├── 现金已收款        → 已完成(4)
    │                                    └── 现金未收款        → 已送达(3) ──收款确认──→ 已完成(4)
    │
    │         配送中(2) ──退回站长──→ 待配送(1)[同意后清空配送员]
    │
    └──── 已取消(5) ←── 退款/取消订单（唯一编排入口 PaymentService.refundOrder）

判断是否已分配配送员：看 delivery_staff_id 是否为 NULL（与 status 无关）
```

> 「站长分配」只写 `delivery_staff_id`、**不改状态**（仍是待配送 1），配送员点「接单」才进 2。
> 所有状态改写都走带 expected-state 的 CAS（`updateStatusIf`）并检查受影响行数。

### 配送员分配/转让/退回（P3）

```
待配送(1) --站长分配--> 待配送(1)[配送员A]           （分配只改 delivery_staff_id，状态不变）
待配送(1)[A] --配送员A接单--> 配送中(2)[配送员A]
配送中(2)[A] --转同事--> 配送中(2)[配送员B]           （直转本站同事，留痕 order_transfer）
配送中(2)[A] --退回站长--> 待配送(1)[仍挂A] --站长同意--> 待配送(1)[无配送员]
```

> 退回申请期间**不清空配送员**：否则站长点「拒绝退回」时已无人可恢复，会留下
> 「配送中但无配送员」的孤儿卡死单。清空只发生在站长**同意**退回时。

### 桶资产流转

桶账唯一写入口是 `BarrelLedgerService`。核心概念：

```
权益 Right    = Σ customer_barrel_lot.remain_qty          （押金条批次，金额真相源）
占用 Occupied = 顾客手上实际有几个桶（派生值，不落表）
over          = 占用 − 权益                                 （customer_barrel_over，可为负）
恒等式：占用 = 权益 + over
```

```
下单(桶装水) → 按商品算 shortage = 需要 − 已持有权益 − 已在配送中
  ├─ shortage ≤ 0 → 不收押金
  └─ shortage > 0 → 计入订单 deposit_amount（此时【不入账】）→ 建 in_transit 记录(PENDING)
支付成功      → applyDepositOnPaid：预收押金入 customer_deposit_account（幂等，按订单去重）
配送完成      → applyDelivery：newOver = oldOver + (delivered − returned) − rightPurchase
                同时 in_transit 转正为 lot（押金条）+ 增加权益，in_transit 置 DELIVERED
                唯一校验是【物理上限】returned ≤ 占用_before（不是 returned ≤ delivered）
纯还桶        → 只动 over，允许变负（顾客多还 / 水站暂存），不扣权益、不退款
退桶(终止权益) → 按押金条 FIFO 核销，退款 = Σ 核销数 × 该批次【买入时】单价
```

> **over < 0 是合法状态**（水站替顾客存桶），任何地方都不许写 `over >= 0` 形式的拦截校验。
> 能退款的只有 lot 里剩余的权益，over 永远不产生退款。

### 水票流转

```
购买水票 → ticket_account 增加 + ticket_record 记录
下单用水票支付 → ticket_account 扣减 + ticket_record 记录
站长操作 → ticket_account 增加/扣减 + ticket_record 记录
```

### 押金流转

```
桶装水首次/缺桶 → 预收押金(deposit_account 增加)
退桶 → 退押金(deposit_account 减少)
丢桶/损坏 → 赔偿(可能不退押金)
异常补偿 → 站长决策退押金
```

### 线下支付控制

两级开关：
1. 水站总开关 `station.offline_payment_enabled`（站长控制）
2. 客户授权 `customer_station_config.offline_payment_enabled`（站长为特定客户设置）

两者都为 1 时，客户下单才能选择线下支付。

---

## 关键设计决策

### 1. 桶是资产，不是消耗品

每个订单 `delivery_bucket_qty` 和 `return_bucket_qty` 追踪桶的流动。客户持有桶数 = 送出 - 回收。桶有押金，退桶时退还。配送完成时配送中桶自动转入持有桶资产。

### 2. 双站模型

订单有 `station_id`（订单归属站，客户自选）和 `delivery_station_id`（实际履约配送站，可被站长切换）。客户可自由选择不同水站下单，不同订单可对应不同水站。

### 3. 三端协同，角色控制

管理后台（站长/配送员）和微信小程序（客户/站长/配送员）共用后端 API。管理后台登录后根据 staff 表 `role` 决定菜单和数据范围。后端通过 `@RequireRole` AOP 注解 + `AuthContext` 实现接口级权限控制。

### 4. JWT 双 Token 无感续期

access_token 2 小时过期，refresh_token 7 天。401 时自动续期并重放请求，用户体验无感知。

### 5. 水站级数据隔离

站长只看到本站数据（`station_id`），配送员只看到自己任务（`delivery_staff_id`）。前端 `request.js` 自动注入 `stationId`。客户资产（水票/桶/押金）按水站隔离，切换水站不自动迁移。

### 6. 幂等键防重复下单

`idempotencyKey` 字段防止网络重试导致重复订单。

### 7. 地址快照

订单创建时快照完整地址信息到 `address_snapshot`，不受后续地址修改影响。

---

## 核心 API

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 管理后台登录（返回双Token） |
| POST | `/api/auth/wx-login` | 客户微信登录 |
| POST | `/api/auth/wx-login-staff` | 配送端微信登录 |
| POST | `/api/auth/select-role` | 首次进入配送端选角色 |
| POST | `/api/auth/create-station` | 站长创建水站并绑定 |
| POST | `/api/auth/logout` | 登出 |
| POST | `/api/auth/refresh` | 刷新 access_token |
| POST | `/api/auth/change-password` | 修改密码 |
| GET | `/api/auth/me` | 获取当前用户 |

### 订单

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders/create` | 创建订单（幂等） |
| POST | `/api/orders` | 保存订单 |
| GET | `/api/orders` | 订单列表 |
| GET | `/api/orders/{id}` | 订单详情 |
| PUT | `/api/orders/{id}/status` | 更新订单状态 |

### 配送

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/delivery/orders/pending` | 待配送订单 |
| GET | `/api/delivery/orders/delivering` | 配送中订单 |
| GET | `/api/delivery/orders/completed-today` | 今日已完成 |
| GET | `/api/delivery/orders/station-pending` | 站长：站点全部待分配 |
| GET | `/api/delivery/orders/station-delivering` | 站长：站点全部配送中 |
| GET | `/api/delivery/orders/station-completed` | 站长：站点全部已完成 |
| GET | `/api/delivery/orders/station-transfer` | 站长：转单中订单 |
| GET | `/api/delivery/orders/station-return` | 站长：退回订单 |
| GET | `/api/delivery/orders/station-exception` | 站长：异常订单 |
| POST | `/api/delivery/orders/{id}/accept` | 接单 |
| POST | `/api/delivery/orders/{id}/complete` | 完成配送 |
| POST | `/api/delivery/orders/{id}/confirm-offline-pay` | 确认线下收款 |
| POST | `/api/delivery/orders/reject/{id}` | 拒单 |
| POST | `/api/delivery/orders/{id}/dispatch` | 外派订单 |
| POST | `/api/delivery/orders/{id}/resolve` | 解决订单（拒单+取消+退款） |
| POST | `/api/delivery/orders/assign/{id}` | 站长分配 |
| POST | `/api/delivery/orders/transfer/{id}` | 转让给同事 |
| POST | `/api/delivery/orders/return/{id}` | 退回站长 |
| POST | `/api/delivery/orders/transfer/{id}/outsource` | 放入转单池 |
| POST | `/api/delivery/orders/transfer/{id}/cancel` | 取消转让 |
| POST | `/api/delivery/orders/transfer/{id}/claim` | 认领转单 |
| POST | `/api/delivery/orders/transfer/{id}/reject` | 拒绝认领 |
| POST | `/api/delivery/orders/return/{id}/approve` | 批准退回 |
| POST | `/api/delivery/orders/return/{id}/reject` | 拒绝退回 |
| GET | `/api/delivery/stats/today` | 今日统计 |
| GET | `/api/delivery/history` | 配送历史 |
| GET | `/api/delivery/transfers` | 转单记录 |

### 配送员绑定

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/delivery/bind/apply` | 申请绑定水站 |
| POST | `/api/delivery/bind/cancel` | 取消绑定申请 |
| POST | `/api/delivery/bind/unbind-request` | 申请解绑 |
| GET | `/api/delivery/bind/status` | 查询绑定状态 |
| POST | `/api/manager/bind/approve` | 站长同意绑定 |
| POST | `/api/manager/bind/reject` | 站长拒绝绑定 |
| POST | `/api/manager/bind/unbind-confirm` | 站长同意解绑 |
| POST | `/api/manager/bind/unbind-reject` | 站长拒绝解绑 |
| POST | `/api/manager/bind/release` | 站长强制解除 |
| GET | `/api/manager/bind/applications` | 申请列表 |

### 站长订单管理

`ManagerOrderController` **已整体删除**：它原有的 8 个订单写端点（`/api/manager/orders/**`、
`/api/manager/offline-exception`）全部是「无 CAS 的整行直写」，且三个小程序**零调用**，
其中 `offline-exception` 还允许前端直传 `paymentStatus`，属越权高危。
订单写操作现在只有一条入口 —— `OrderWorkflowService`（见「配送」章节的 `/api/delivery/orders/**`）。
删除有回归用例锁定：`ManagerOrderControllerRemovedIntegrationTest` 断言这些路径返回 `code=404`。

### 商品+库存

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/products` | 商品列表 |
| GET | `/api/products/{id}` | 商品详情 |
| POST | `/api/products` | 创建商品 |
| PUT | `/api/products/{id}` | 更新商品 |
| DELETE | `/api/products/{id}` | 删除商品 |
| GET | `/api/manager/products` | 商品+库存列表 |
| POST | `/api/manager/products` | 创建商品+配置库存 |
| PUT | `/api/manager/products/{id}` | 更新商品+库存 |
| PUT | `/api/manager/products/{id}/shelf` | 上架/下架 |
| PUT | `/api/manager/products/{id}/priority` | 优先展示（上限3个） |
| POST | `/api/manager/products/inbound` | 批量入库 |
| GET | `/api/inventory` | 库存列表 |
| POST | `/api/inventory/inbound` | 入库 |

### 桶异常管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/manager/exceptions` | 异常列表（分页+筛选） |
| GET | `/api/manager/exceptions/{id}` | 异常详情 |
| POST | `/api/manager/exceptions/{id}/handle` | 处理异常 |
| POST | `/api/manager/exceptions/{id}/execute` | 执行补偿 |
| GET | `/api/manager/exceptions/stats` | 异常统计 |
| GET | `/api/manager/exceptions/config` | 站点异常配置 |
| PUT | `/api/manager/exceptions/config` | 更新异常配置 |

### 支付

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/payments/quote` | 支付试算 |
| POST | `/api/payments` | 创建支付记录 |
| PUT | `/api/payments/{id}/confirm` | 确认支付 |
| PUT | `/api/payments/{id}/cash-confirm` | 现金收款确认 |
| PUT | `/api/payments/{id}/refund` | 退款 |
| GET | `/api/payments` | 支付记录列表 |
| GET | `/api/payments/config` | 站点支付配置 |
| PUT | `/api/payments/config` | 更新支付配置 |

### 资产管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/barrels/summary-by-type` | 按类型桶资产摘要 |
| GET | `/api/barrels/assets` | 桶资产列表 |
| GET | `/api/barrels/records` | 桶异常记录 |
| POST | `/api/barrels/handle-exception` | 处理桶异常 |
| GET | `/api/barrels/all-records` | 所有桶记录 |
| GET | `/api/tickets` | 水票列表 |
| POST | `/api/tickets/add` | 加水票 |
| POST | `/api/tickets/consume` | 消耗水票 |
| POST | `/api/tickets/purchase` | 线上购买水票 |
| GET | `/api/ticket-records/customer/{customerId}` | 水票流水 |
| GET | `/api/deposit-records` | 押金流水 |
| POST | `/api/deposit-records` | 新增押金记录 |

### 地址

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT | `/api/addresses` | 地址 CRUD（含 province/city/district 省市区字段） |

### 其他

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT | `/api/customers` | 客户 CRUD |
| GET/POST/PUT/DELETE | `/api/staff` | 员工 |
| GET/POST/PUT/DELETE | `/api/stations` | 水站 |
| GET | `/api/dashboard/today` | 今日数据 |
| GET | `/api/dashboard/overview` | 概览统计 |
| GET | `/api/dashboard/order-status` | 订单状态分布 |
| GET | `/api/dashboard/order-trend` | 近7天订单趋势 |
| GET/POST/PUT/DELETE | `/api/order-templates` | 常用订单模板 |
| POST | `/api/order-images/upload` | 上传订单图片 |
| GET | `/api/order-images/by-order/{orderId}` | 订单图片 |
| POST | `/api/files/upload` | 上传文件 |
| GET | `/api/files` | 文件列表 |
| DELETE | `/api/files/{id}` | 删除文件 |
| POST | `/api/common/upload` | 通用图片上传 |
| GET | `/api/search` | 全局搜索 |
| GET/POST/PUT/DELETE | `/api/notices` | 公告 |
| POST | `/api/feedback` | 提交反馈 |
| GET | `/api/feedback/customers` | 客户反馈汇总 |
| GET/POST/PUT | `/api/company-info` | 企业信息 |

---

## License

MIT
