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

## 阶段改造记录

### P3 配送员分配 / 转让 / 退回站长（已完成）

- **语义**：站长分配＝待配送状态→直接置配送中(DELIVERING)并挂载配送员。配送员可**直转本站同事**（DELIVERING→DELIVERING 换人，留痕），也可**退回站长**（DELIVERING→待配送 清空配送员回待分配池，站长重新分配）。
- **规则**：① 仅限本站配送员 + 本站履约单（按 `delivery_station_id` 判站）；② 仅站长可分配（`@RequireRole STATION_MANAGER`）；③ 配送员只能操作自己名下的单；④ 分配/转让/退回均写 `special_note` 留痕 `[分配]/[转让]/[退回站长]`。
- **后端**：`DeliveryController` 改造 `assignOrder`、`transferOrder`（提交同事可直转）、新增 `returnToStation`；`OrderMapper.listTransferredOrders` 按备注 `[转让]/[退回站长]` 汇总转让记录。
- **前端**：Vue 后台新增站长页「配送任务分配」`/deliver-assign`（待配送/配送中/转让记录三 tab）；配送员小程序首页与订单详情「转给同事」「退回站长」双按钮。

### P2 桶资产联动（已完成）

- **下单**：桶装水订单计算 shortage = needed - held，预收缺桶押金，创建 `customer_barrel_in_transit`（PENDING）。
- **配送完成**：`completeOrder` 将 PENDING 配送中记录自动转入 `customer_barrel_asset`（持有桶），更新状态为 DELIVERED。
- **复购**：持有桶数正确读取，已持有的桶不再收押金。
- **安全**：`listPendingByOrderId` 只查 PENDING 状态，防止重复调用导致桶资产翻倍。

### P2 地址省市区拆分（已完成）

- `address` 表新增 `province`/`city`/`district` 字段，与 `detail` 分离。
- 前端地址编辑页：自动识别按钮 → `wx.chooseLocation` → `parseRegion()` 解析地址字符串自动填充省/市/区。
- 支持直辖市（北京市/上海市等）、标准格式（XX省XX市XX区）、省直市等变体。
- 地址列表/首页/下单页拼接显示 `{{province}}{{city}}{{district}} {{detail}}`。

### P2 首页改版（已完成）

- **常用订单**：显示全部3笔历史订单，每笔订单展示全部商品名+规格+数量，价格改为下次购买价（不含已付押金）。
- **购物车 badge**：修复 `loadData` 中 cart 引用问题，模板 items 加入后 cartCount 实时更新；防重复加载避免数量倍增。
- **水桶管理**：首页展示持有桶类型+数量。
- **水桶资产**：下单后自动联动录入 `customer_barrel_asset`。

### P1 资产与履约闭环（已完成）

- 桶资产/欠桶台账（`customer_barrel_asset`/`customer_owed_barrel`）、库存不足自动欠桶、收款确认/拒单/站长修正、支付落库等。

### P0 基础设施（已完成）

- JWT 双Token认证、BCrypt密码、幂等键防重复下单、地址快照等。

---

## 架构概览

### 三端协同 · 三种角色

管理后台（站长/配送员）与微信小程序（客户）共用同一套后端 API。

```
┌──────────────────────────┐   ┌──────────────────────────┐
│   管理后台 (Vue3)          │   │   微信小程序 (原生)        │
│   AquaFlow-frontend      │   │   miniapp-user (客户)     │
├──────────────────────────┤   │   miniapp-delivery          │
│                          │   │   (站长 + 配送员)    │
│  站长 ── 订单/配送/资产    │   ├──────────────────────────┤
│  配送员 ── 我的配送        │   │  客户 ── 下单/复购       │
│                          │   │        水桶/水票/地址     │
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
                  └────────────────────┘
```

### 管理后台角色权限

| 页面 | 路由 | 站长 `manager` | 配送员 `delivery` |
|------|------|:---:|:---:|
| 首页 | `/dashboard` | ✅ | ✅ |
| 订单管理 | `/order` | ✅ | |
| 配送任务分配 | `/deliver-assign` | ✅ | |
| 库存管理 | `/inventory` | ✅ | |
| 客户管理 | `/customer` | ✅ | |
| 地址管理 | `/address` | ✅ | |
| 商品管理 | `/water` | ✅ | |
| 退桶审批 | `/barrel-return` | ✅ | |
| 水票管理 | `/ticket` | ✅ | |
| 押金管理 | `/deposit` | ✅ | |
| 支付管理 | `/payment` | ✅ | |
| 员工管理 | `/staff` | ✅ | |
| 地址地图 | `/address-map` | ✅ | |
| 数据报表 | `/report` | ✅ | |
| 文件管理 | `/file-manage` | ✅ | |
| 我的配送 | `/my-deliveries` | | ✅ |

### 数据范围隔离

- **站长**：只能看到本水站的数据（通过 `station_id` 过滤）
- **配送员**：只能看到分配给自己的配送任务（通过 `delivery_staff_id` 过滤）

---

## 测试账号

所有密码均为 `123456`。

| 角色 | 账号 | 密码 | 所属水站 | 数据范围 |
|------|------|------|---------|---------|
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
mysql -u root -p aquaflow < AquaFlow-backend/sql/schema.sql
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
    │   ├── ManagerOrderController.java       # 站长订单管理（430行）
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

### 前端

```
AquaFlow-frontend/src/
├── api/index.js                  # 统一 API 定义（20+ 模块）
├── router/index.js               # 路由 + 角色守卫
├── utils/request.js              # Axios（Token自动续期）
├── styles/theme.css              # 亮色/暗色主题
├── components/
│   ├── GlobalSearch.vue          # 全局搜索
│   └── ImageUpload.vue           # 图片上传
├── App.vue                       # 根布局 + 双角色菜单
└── views/
    ├── shared/
    │   ├── login/                # 登录页（双栏设计）
    │   └── claim/                # 认领所属
    └── station/
        ├── dashboard/            # 首页仪表盘（536行）
        ├── order/                # 订单管理
        ├── deliver-assign/       # 配送任务分配
        ├── delivery/             # 我的配送（配送员专用）
        ├── inventory/            # 库存管理
        ├── customer/             # 客户管理
        ├── address/              # 地址管理
        ├── address-map/          # 地址地图（Leaflet）
        ├── water/                # 商品管理
        ├── barrel-return/        # 退桶审批
        ├── ticket/               # 水票管理
        ├── deposit/              # 押金管理
        ├── payment/              # 支付管理
        ├── staff/                # 员工管理
        ├── report/               # 数据报表
        └── file-manage/          # 文件管理
```

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
| `customer_owed_barrel` | 欠桶台账 | 配送差额（应回收-实际回收>0） |
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

### 订单状态流转（V1简化版）

```
待配送(1) ──站长分配──→ 配送中(3) ──完成配送──→ 按支付方式分流：
    ↑                                    │
    │                                    ├── 微信/水票 → 直接 COMPLETED(5)
    │                                    ├── 线下已收款 → COMPLETED(5)
    │                                    └── 线下未收款 → DELIVERED(4) → 收款确认 → COMPLETED(5)
    │
    │         配送中(3) ──退回站长──→ 待配送(1)[清空配送员]
    │
    └──── 已取消(6) ←── 退款/取消订单

判断是否分配配送员：通过 delivery_staff_id 是否为 NULL
```

### 配送员分配/转让/退回（P3）

```
待配送(1) --站长分配--> 配送中(3)[配送员A]
配送中(3)[A] --配送员A转同事--> 配送中(3)[配送员B]   （直转本站同事）
配送中(3)[A] --退回站长--> 待配送(1)[无配送员]        （站长重新分配）
```

### 桶资产流转

```
下单(桶装水) → 检查客户持有桶 vs 需要桶
  ├─ 持有足够 → 不收押金
  └─ 持有不足 → 预收缺桶押金 → 创建 in_transit 记录(PENDING)
配送完成 → in_transit 转入 customer_barrel_asset（持有桶自动增加）→ 状态变 DELIVERED
下次下单 → heldByProduct 正确读取持有数 → 已持有的桶不再收押金
回桶 → 实际回桶数 vs 送出桶数
  ├─ 相等 → 正常
  ├─ 少回 → barrel_discrepancy > 0, 记录异常
  └─ 多回 → barrel_discrepancy < 0
```

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

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/manager/orders/{id}/dispatch` | 派单出发 |
| POST | `/api/manager/offline-exception` | 线下异常处理 |

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
