# AquaFlow V1.0

面向小型桶装水配送站的轻量级管理后台系统。

## 项目简介

AquaFlow 为桶装水配送站提供一站式管理工具，覆盖从客户下单到装车配送的完整流程。首页以装车（批次）为核心视角，配合地图选单创建批次，适合配送员兼站长的单人使用场景。

## 项目结构

```
AquaFlow/
├── AquaFlow-backend/          # 后端（Spring Boot + MyBatis）
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/aquaflow/
│   │   │   │   ├── AquaFlowApplication.java
│   │   │   │   ├── common/          # 统一返回封装
│   │   │   │   ├── config/          # 跨域等配置
│   │   │   │   ├── constant/        # 状态常量
│   │   │   │   ├── controller/      # 控制器
│   │   │   │   ├── dto/             # 请求 DTO
│   │   │   │   ├── entity/          # 实体类
│   │   │   │   ├── exception/       # 全局异常处理
│   │   │   │   ├── mapper/          # MyBatis Mapper
│   │   │   │   └── service/         # 业务逻辑
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       └── mapper/          # MyBatis XML
│   │   └── test/
│   ├── build.gradle
│   └── README.md
├── AquaFlow-frontend/         # 前端（Vue3 + Element Plus + Leaflet）
│   ├── src/
│   │   ├── api/               # API 接口定义
│   │   ├── router/            # 路由配置
│   │   ├── styles/            # CSS 变量 / 主题
│   │   ├── utils/             # Axios 封装
│   │   └── views/             # 页面组件
│   │       ├── login/         # 登录
│   │       ├── dashboard/     # 首页（装车视角）
│   │       ├── customer/      # 客户管理
│   │       ├── address/       # 地址管理
│   │       ├── address-map/   # 地址地图（订单/客户双模式）
│   │       ├── water/         # 水类型管理
│   │       ├── inventory/     # 库存管理
│   │       ├── order/         # 订单管理
│   │       ├── batch/         # 批次管理
│   │       └── report/        # 数据报表
│   ├── package.json
│   └── README.md
├── reset_data.sql             # 测试数据重置脚本
├── .gitignore
└── README.md                  # 本文件
```

## 快速开始

### 环境要求

| 环境 | 版本 |
|------|------|
| JDK | 17+ |
| MySQL | 8.x |
| Node.js | 18+ |
| npm | 9+ |

### 1. 初始化数据库

```bash
mysql -u root -p
```

```sql
CREATE DATABASE IF NOT EXISTS aquaflow DEFAULT CHARACTER SET utf8mb4;
USE aquaflow;
```

然后执行建表语句（见后端 README），或直接导入 `reset_data.sql` 生成测试数据：

```bash
mysql -u root -p aquaflow < reset_data.sql
```

### 2. 配置后端

编辑 `AquaFlow-backend/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/aquaflow?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Tokyo
    username: root
    password: 123456
```

### 3. 启动后端

```bash
cd AquaFlow-backend
./gradlew bootRun
# 或 Windows
.\gradlew.bat bootRun
```

后端启动后运行于 `http://localhost:8080`。

### 4. 启动前端

```bash
cd AquaFlow-frontend
npm install
npm run dev
```

前端启动后运行于 `http://localhost:5173`，自动代理 `/api` 到后端。

### 5. 登录

- 地址：`http://localhost:5173/login`
- 账号：`admin`
- 密码：`123456`

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| 后端 | Spring Boot | 4.0.6 |
| 后端 | MyBatis | 4.0.1 |
| 后端 | MySQL Connector | 8.x |
| 后端 | Lombok | - |
| 后端 | Gradle | 9.x |
| 后端 | Java | 17 |
| 前端 | Vue | 3.5.13 |
| 前端 | Vite | 6.3.5 |
| 前端 | Element Plus | 2.9.7 |
| 前端 | ECharts | 6.1.0 |
| 前端 | Leaflet | 1.9.4 |
| 前端 | Pinia | 3.0.2 |
| 前端 | Vue Router | 4.5.0 |
| 前端 | Axios | 1.7.9 |

## 核心功能

### 首页（装车视角）

- 顶部概览：待装车批次、装车中、已完成、待组批订单、库存预警
- 核心按钮：「去装车」→ 跳转地图选单创建批次
- 批次列表：待装车 / 装车中两栏，支持详情、装车、完成、删除
- 经营数据：客户总数、库存总量、历史总单/批次、趋势图、库存图、客户排行

### 地图选单创建批次

- 右上角切换：**订单模式**（默认）/ **客户模式**
- 订单模式：地图标记显示待组批订单（带订单ID的圆形图标），勾选后一键创建批次
- 客户模式：显示所有客户地址（按标签配色）
- 支持 URL 参数：`?mode=order&batchId=1` 编辑已有批次

### 订单管理

- 按状态、日期范围、地址标签筛选
- 新增订单时选择客户，自动带出该客户绑定的地址
- 支持电话、微信群、小程序三种来源

### 地址管理

- 支持按标签（小区/工厂/写字楼/商场）筛选
- 支持关键词搜索
- 地址可绑定客户（客户-地址捆绑）
- 地图可视化（经纬度标注）

### 数据报表

- 今日订单统计
- 近7天订单趋势
- 水类型销量排行
- 客户订购排行
- 地址标签分布

## 业务流程

```
水类型维护 → 库存入库 → 客户维护 → 地址维护（绑定客户）
                                        │
                                        ▼
                                   创建订单（待组批）
                                        │
                                        ▼
                              地图选单 → 创建配送批次
                                        │
                                  ┌─────┴─────┐
                                  │ 校验库存   │
                                  │ 扣减库存   │
                                  └─────┬─────┘
                                        │
                                        ▼
                                   开始装车配送
                                        │
                                        ▼
                                   完成配送
```

## 订单状态流转

```
1(待组批) → 4(已组批) → 2(配送中) → 3(已完成)
```

## 批次状态流转

```
1(待配送) → 2(配送中) → 3(已完成)
```

## 数据库表结构

| 表名 | 说明 | 关键字段 |
|------|------|----------|
| customer | 客户 | name, phone, note |
| address | 配送地址 | customer_id, detail, tag, lat, lng |
| water_type | 水类型 | name (唯一), spec |
| inventory | 库存 | water_type_id (唯一), quantity |
| orders | 订单 | customer_id, address_id, water_type_id, quantity, source, status |
| batch | 配送批次 | status, total_qty |
| batch_order | 批次-订单关联 | batch_id, order_id |
| order_image | 配送照片 | order_id, url, type |

## API 接口一览

| 模块 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 认证 | POST | `/api/auth/login` | 登录 |
| 客户 | GET | `/api/customers` | 客户列表 |
| 客户 | POST | `/api/customers` | 新增客户 |
| 客户 | PUT | `/api/customers/{id}` | 修改客户 |
| 地址 | GET | `/api/addresses` | 地址列表（支持 customerId/tag/keyword） |
| 地址 | POST | `/api/addresses` | 新增地址 |
| 地址 | PUT | `/api/addresses/{id}` | 修改地址 |
| 地址 | GET | `/api/addresses/report/tags` | 标签分布统计 |
| 水类型 | GET | `/api/water-types` | 水类型列表 |
| 水类型 | POST | `/api/water-types` | 新增水类型 |
| 库存 | GET | `/api/inventory` | 库存列表 |
| 库存 | POST | `/api/inventory/inbound` | 批量入库 |
| 订单 | GET | `/api/orders` | 订单列表（支持 status/tag/日期） |
| 订单 | POST | `/api/orders` | 创建订单 |
| 订单 | PUT | `/api/orders/{id}/status` | 更新状态 |
| 批次 | GET | `/api/batches` | 批次列表 |
| 批次 | GET | `/api/batches/{id}` | 批次详情（含订单） |
| 批次 | POST | `/api/batches` | 创建批次 |
| 批次 | POST | `/api/batches/{id}/start` | 开始配送 |
| 批次 | POST | `/api/batches/{id}/finish` | 完成配送 |
| 批次 | DELETE | `/api/batches/{id}` | 删除批次 |
| 首页 | GET | `/api/dashboard/today` | 今日概览 |
| 首页 | GET | `/api/dashboard/pending-batches` | 待装车批次 |
| 首页 | GET | `/api/dashboard/delivering-batches` | 装车中批次 |
| 首页 | GET | `/api/dashboard/overview` | 经营概览 |
| 首页 | GET | `/api/dashboard/order-trend` | 近7天趋势 |
| 首页 | GET | `/api/dashboard/top-customers` | 客户排行 |
| 搜索 | GET | `/api/search` | 全局搜索 |

## 文档

- [后端 README](./AquaFlow-backend/README.md) — 后端详细文档、API 接口文档、数据库设计
- [前端 README](./AquaFlow-frontend/README.md) — 前端项目说明、页面功能、开发指南

## License

MIT
