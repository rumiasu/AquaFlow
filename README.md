# AquaFlow

> 桶装水行业三端协同平台 — 水厂 · 水站 · 客户

## 项目定位

AquaFlow 不是一个通用的订单管理系统，而是一个**围绕桶装水行业真实业务规则设计**的垂直领域平台。

```
水厂（factory）       供货、定价、品牌管理
  └── 水站（station）    接单、配送、库存、财务
       └── 客户（customer）   下单、复购、押金、水票
```

**当前状态：** V1.0 水站管理后台 + C端微信小程序已完成

**目标演进：** 从单人配送站 → 多人协作 → 水厂-水站-客户三端协同

---

## 设计理念

### 一、行业驱动，不是通用 CRUD

桶装水行业和电商有本质区别：

| 维度 | 通用电商 | AquaFlow |
|------|---------|----------|
| 购买单元 | 多品类购物车 | 单品类 + 数量 |
| 消费频率 | 低频/随机 | 高频/周期性（每周/每两周） |
| 物流模式 | 快递到家 | 配送员上门 + 空桶回收 |
| 资产管理 | 无 | 桶是资产（押金追踪） |
| 支付方式 | 在线支付 | 线下为主 / 水票预购 / 企业账期 |

因此 AquaFlow **没有购物车、没有多品类结算、没有在线支付**。一切围绕「选水 → 选桶数 → 地址 → 下单 → 配送」这条主线。

### 二、桶是资产，不是消耗品

这是桶装水行业最核心的业务逻辑：

```
送桶（delivery_bucket_qty）→ 客户持有 → 回桶（return_bucket_qty）
         │                              │
         └── 需要押金（deposit）──────────┘ 退桶退押金
```

- 每个订单记录送出桶数和回收桶数
- 客户持有桶数 = 送出 - 回收
- 桶有押金（¥30/桶），退桶时退还
- 水站需要追踪每个客户手上有几桶、什么类型

这就是为什么首页地址栏下方要显示"水桶明细"——它不是装饰，是**资产管理的核心入口**。

### 三、首页即工作台

#### 水站管理端（AquaFlow-frontend）

打开系统就要知道**今天要做什么**：

```
┌─────────────────────────────────────────────┐
│              今日工作台                       │
├──────────────┬──────────────┬───────────────┤
│  今日待配送   │  今日待装车   │   库存预警     │
│     8 单      │     2 批     │    3 种       │
├──────────────┴──────────────┴───────────────┤
│  待收桶        │  企业待结算    │  欠款提醒     │
│   12 桶        │   3 单/¥850  │   2 客户     │
└─────────────────────────────────────────────┘
```

#### C端小程序（miniapp-user）

用户打开小程序的目的很明确：**再订一桶水**。所以首页直接是下单表单：

```
┌─────────────────────────────┐
│  📍 张三 138xxxx             │  ← 知道在哪送
│  济南市历下区XX路18号         │
├─────────────────────────────┤
│  🪣 农夫山泉(大桶) ×2桶  ›  │  ← 知道手上有几桶
├─────────────────────────────┤
│  选择水类型                   │  ← 选要订什么
│  [💧纯净水] [💧矿泉水] ...   │
├─────────────────────────────┤
│  数量  [−] 2 [+] 桶          │  ← 要几桶
├─────────────────────────────┤
│  备注  放门口、几号楼...       │
├─────────────────────────────┤
│  合计 ¥40      [立即下单]     │  ← 一步完成
└─────────────────────────────┘
```

### 四、常用订单 = 一键复购

桶装水是**周期性消费**——用户每次买的水类型和数量基本固定。

```
第一次：选水 → 选数量 → 填地址 → 下单
         │
         └── "存为常用订单"
              │
第二次：首页自动填入 → 直接点"立即下单"
```

常用订单模板（order_template）记录用户的偏好：水类型 + 桶数 + 地址 + 备注。下次打开小程序，首页自动填好，一键复购。

### 五、渐进式功能叠加

核心是**下单**，其他功能在此基础上叠加，不干扰主流程：

```
核心：下单（首页直接完成）
  │
  ├── 常用订单 → 复购加速（不用重新选）
  ├── 水桶明细 → 资产管理（知道自己有几桶）
  ├── 水票 → 预购优惠（买票打折）
  ├── 地址管理 → 多地址（家/公司/父母家）
  ├── 押金管理 → 财务透明（在我的页面）
  └── 订单历史 → 再来一单（最近订单一键复购）
```

### 六、三端架构

```
水厂（factory）
  │  供货管理、品牌管理、价格体系
  │
  └── 水站（station）
       │  接单、配送、库存、财务
       │  ┌──────────────────────┐
       │  │ AquaFlow-frontend    │  ← 管理后台（Vue3）
       │  └──────────────────────┘
       │
       └── 客户（customer）
            │  下单、复购、查询
            │  ┌──────────────────────┐
            │  │ miniapp-user         │  ← 微信小程序
            │  └──────────────────────┘
```

---

## 项目结构

```
AquaFlow/
├── AquaFlow-backend/          # 后端（Spring Boot + MyBatis）
│   ├── src/main/java/com/example/aquaflow/
│   │   ├── controller/        # API 控制器
│   │   ├── entity/            # 实体类
│   │   ├── mapper/            # MyBatis Mapper
│   │   ├── service/           # 业务逻辑
│   │   ├── dto/               # 请求 DTO
│   │   ├── common/            # 统一返回封装
│   │   ├── config/            # 跨域等配置
│   │   └── constant/          # 状态常量
│   ├── src/main/resources/mapper/  # MyBatis XML
│   └── sql/                   # 数据库迁移脚本
├── AquaFlow-frontend/         # 管理后台（Vue3 + Element Plus + Leaflet）
│   └── src/views/
│       ├── dashboard/         # 首页（今日工作台）
│       ├── customer/          # 客户管理
│       ├── address/           # 地址管理
│       ├── address-map/       # 地图选单（创建批次）
│       ├── water/             # 水类型管理
│       ├── inventory/         # 库存管理
│       ├── order/             # 订单管理
│       ├── batch/             # 批次管理
│       └── report/            # 数据报表
├── miniapp-user/              # C端微信小程序
│   ├── pages/
│   │   ├── home/              # 首页（下单页）
│   │   ├── order/             # 订单管理
│   │   ├── address/           # 地址管理
│   │   ├── ticket/            # 水票查询
│   │   ├── barrel/            # 水桶管理
│   │   ├── template/          # 常用订单
│   │   ├── shop/              # 商城（浏览所有水类型）
│   │   ├── mine/              # 我的（含押金概况）
│   │   └── login/             # 微信登录
│   ├── api/                   # 接口层
│   ├── components/            # 公共组件
│   └── utils/                 # 工具函数
└── reset_data.sql             # 测试数据
```

---

## 快速开始

### 环境要求

| 环境 | 版本 |
|------|------|
| JDK | 17+ |
| MySQL | 8.x |
| Node.js | 18+ |
| 微信开发者工具 | 最新版 |

### 1. 初始化数据库

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS aquaflow DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p aquaflow < reset_data.sql
```

### 2. 启动后端

```bash
cd AquaFlow-backend
.\gradlew.bat bootRun    # Windows
./gradlew bootRun        # Mac/Linux
```

后端运行于 `http://localhost:8080`

### 3. 启动管理后台

```bash
cd AquaFlow-frontend
npm install
npm run dev
```

管理后台运行于 `http://localhost:5173`

登录：`admin` / `123456`

### 4. 启动小程序

1. 微信开发者工具导入 `miniapp-user` 目录
2. `config/api.js` 配置后端地址（默认 `http://192.168.0.104:8080`）
3. 编译运行

---

## 技术栈

| 端 | 技术 | 版本 |
|----|------|------|
| 后端 | Spring Boot + MyBatis + MySQL | 4.0.6 / 4.0.1 / 8.x |
| 管理后台 | Vue3 + Vite + Element Plus + ECharts + Leaflet | 3.5 / 6.3 / 2.9 / 6.1 / 1.9 |
| 小程序 | 微信原生 + JavaScript ES6+ | - |

---

## 核心业务流程

### 水站管理端

```
水类型维护 → 库存入库 → 客户维护 → 地址维护
                                        │
                                        ▼
                                   创建订单（待组批）
                                        │
                                        ▼
                              地图选单 → 创建配送批次
                                        │
                                        ▼
                                   开始装车配送
                                        │
                                        ▼
                                   完成配送 → 回桶登记
```

### C端小程序

```
微信登录（openid）
    │
    ▼
首页（自动填入常用订单）
    │
    ├── 选水类型
    ├── 选桶数
    ├── 选地址
    └── 备注
    │
    ▼
提交订单 → 下单成功 → 存为常用订单
    │
    ▼
等待配送 → 完成
```

### 订单状态流转

```
1(待组批) → 4(已组批) → 2(配送中) → 3(已完成)
```

---

## 数据库表结构

### 核心业务表

| 表名 | 说明 | 设计要点 |
|------|------|---------|
| `customer` | 客户/用户 | 含 `openid`（微信登录）、`deposit_balance`（押金余额） |
| `address` | 配送地址 | 含 `name`/`phone`（收件人）、`is_default`（默认地址） |
| `water_type` | 水类型 | 含 `price`（单价），是库存和订单的基础 |
| `orders` | 订单 | 含 `delivery_bucket_qty`/`return_bucket_qty`（桶追踪） |
| `inventory` | 库存 | 水类型维度的库存量 |
| `batch` | 配送批次 | 多个订单合并配送 |
| `batch_order` | 批次-订单关联 | 批次和订单的多对多关系 |

### 资产管理表

| 表名 | 说明 | 设计要点 |
|------|------|---------|
| `barrel_record` | 退桶记录 | 客户申请退桶，水站确认 |
| `deposit_record` | 押金记录 | 充值/退款/赔偿扣除 |
| `ticket_account` | 水票账户 | 按水类型维度的预购余额 |
| `ticket_record` | 水票流水 | 增加/消费记录 |

### 模板与快捷

| 表名 | 说明 | 设计要点 |
|------|------|---------|
| `order_template` | 常用订单模板 | 记录用户偏好，首页一键复购 |

### 组织架构表

| 表名 | 说明 |
|------|------|
| `factory` | 水厂 |
| `station` | 水站 |
| `staff` | 员工/配送员 |
| `company_info` | 企业客户信息 |
| `customer_station_record` | 客户归属变更记录 |
| `order_image` | 订单配送照片 |

---

## API 接口一览

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（admin/123456） |
| POST | `/api/auth/wx-login` | 微信小程序登录 |
| PUT | `/api/auth/update-profile` | 更新用户资料 |

### 水类型

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/water-types` | 水类型列表（支持 `?keyword=` 搜索） |
| POST | `/api/water-types` | 新增水类型 |
| GET | `/api/water-types/my` | 用户已购买的水类型 |

### 订单

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/orders` | 订单列表（支持 `customerId/status/tag/日期`） |
| POST | `/api/orders` | 创建订单 |
| PUT | `/api/orders/{id}/status` | 更新状态 |

### 常用订单模板

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/order-templates` | 获取模板列表 |
| POST | `/api/order-templates` | 保存模板 |
| PUT | `/api/order-templates/{id}/toggle` | 启用/禁用模板 |
| DELETE | `/api/order-templates/{id}` | 删除模板 |
| GET | `/api/order-templates/quick` | 快速下单数据 |
| POST | `/api/order-templates/from-order` | 从订单创建模板 |

### 地址

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/addresses` | 地址列表（支持 `customerId/tag/keyword`） |
| POST | `/api/addresses` | 新增地址 |
| PUT | `/api/addresses/{id}` | 修改地址 |
| DELETE | `/api/addresses/{id}` | 删除地址 |
| PUT | `/api/addresses/{id}/default` | 设为默认地址 |

### 水票

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tickets` | 水票余额（含水类型名称） |
| GET | `/api/ticket-records` | 水票流水（含水类型名称） |
| POST | `/api/tickets/add` | 充值水票 |
| POST | `/api/tickets/consume` | 消费水票 |

### 水桶

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/barrels/summary` | 水桶概况（持有数/押金余额） |
| GET | `/api/barrels/summary-by-type` | 按水类型统计持有桶数 |
| GET | `/api/barrels/records` | 退桶记录 |
| POST | `/api/barrels/return` | 申请退桶 |

### 批次

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/batches` | 批次列表 |
| POST | `/api/batches` | 创建批次 |
| POST | `/api/batches/{id}/start` | 开始配送 |
| POST | `/api/batches/{id}/finish` | 完成配送 |
| DELETE | `/api/batches/{id}` | 删除批次 |

### 首页/报表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dashboard/today` | 今日概览 |
| GET | `/api/dashboard/overview` | 经营概览 |
| GET | `/api/dashboard/order-trend` | 近7天趋势 |
| GET | `/api/dashboard/top-customers` | 客户排行 |

---

## V2.0 规划

从单纯水站管理，逐步扩展为**水厂-水站-客户三端协同平台**。

| 阶段 | 模块 | 优先级 |
|------|------|--------|
| 1 | 数据库结构调整（所有新增字段/表） | P0 |
| 2 | 客户归属体系 | P0 |
| 3 | 押金管理 | P1 |
| 4 | 水票体系 | P1 |
| 5 | 回桶管理 | P1 |
| 6 | 企业客户 + 账期 | P2 |
| 7 | 配送员管理 | P2 |
| 8 | 客户画像 + 自动标签 | P2 |
| 9 | 首页改造（今日工作台） | P2 |
| 10 | 水厂/水站表预留 | P3 |

---

## 文档

- [后端 README](./AquaFlow-backend/README.md)
- [前端 README](./AquaFlow-frontend/README.md)
- [小程序 README](./miniapp-user/README.md)

## License

MIT
