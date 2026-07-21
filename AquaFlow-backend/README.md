# AquaFlow Backend

Spring Boot 后端服务，提供桶装水配送站的全部业务 API。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.0.6 | Web 框架 |
| MyBatis | 4.0.1 | ORM 框架 |
| MySQL | 8.x | 数据库 |
| Lombok | - | 简化实体类代码 |
| Gradle | 9.x | 构建工具 |
| Java | 17 | 运行环境 |

## 项目结构

```
com.example.aquaflow
├── AquaFlowApplication.java              # 启动类
├── common/
│   └── Result.java                        # 统一返回封装 { code, message, data }
├── config/
│   └── CorsConfig.java                    # 跨域配置（允许 localhost:5173）
├── constant/
│   ├── OrderStatus.java                   # 订单状态：1待组批 2配送中 3已完成 4已组批
│   └── BatchStatus.java                   # 批次状态：1待配送 2配送中 3已完成
├── entity/                                # 实体类（对应数据库表）
│   ├── Customer.java                      # 客户
│   ├── Address.java                       # 地址（含 customerId 绑定客户）
│   ├── WaterType.java                     # 水类型
│   ├── Inventory.java                     # 库存
│   ├── Orders.java                        # 订单（含关联查询字段）
│   ├── Batch.java                         # 配送批次
│   ├── BatchOrder.java                    # 批次-订单关联
│   └── OrderImage.java                    # 订单图片
├── dto/                                   # 数据传输对象
│   ├── BatchCreateDTO.java                # 批次创建请求 { orderIds: [1,2,3] }
│   ├── BatchFinishDTO.java                # 批次完成请求 { finishedOrderIds, unfinishedOrderIds }
│   ├── InventoryInboundDTO.java           # 入库请求 { items: [{ waterTypeId, quantity }] }
│   └── OrderStatusDTO.java                # 订单状态更新 { status: 2 }
├── mapper/                                # MyBatis 映射接口
│   ├── CustomerMapper.java
│   ├── AddressMapper.java
│   ├── WaterTypeMapper.java
│   ├── InventoryMapper.java
│   ├── OrderMapper.java                   # 含复杂联表查询
│   ├── BatchMapper.java
│   ├── BatchOrderMapper.java
│   └── OrderImageMapper.java
├── service/                               # 业务逻辑层
│   ├── CustomerService.java
│   ├── AddressService.java
│   ├── WaterTypeService.java
│   ├── InventoryService.java
│   ├── OrderService.java
│   ├── BatchService.java                  # 核心：组批、库存校验扣减、完成
│   ├── OrderImageService.java
│   └── impl/                              # 实现类
├── controller/                            # 控制器层
│   ├── LoginController.java               # POST /api/auth/login
│   ├── CustomerController.java
│   ├── AddressController.java
│   ├── WaterTypeController.java
│   ├── InventoryController.java
│   ├── OrderController.java
│   ├── BatchController.java
│   ├── DashboardController.java           # 首页统计接口
│   ├── SearchController.java              # GET /api/search
│   └── OrderImageController.java
└── exception/
    └── GlobalExceptionHandler.java        # 全局异常处理，统一 Result 格式

resources/mapper/                          # MyBatis XML 映射文件
├── CustomerMapper.xml
├── AddressMapper.xml                      # 支持 customerId/tag/keyword 筛选
├── WaterTypeMapper.xml
├── InventoryMapper.xml
├── OrderMapper.xml                        # 含复杂联表（customer/address/water_type）
├── BatchMapper.xml
├── BatchOrderMapper.xml
└── OrderImageMapper.xml
```

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.x
- Gradle（或使用项目自带 `gradlew`）

### 数据库配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/aquaflow?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Tokyo
    username: root
    password: 123456
```

### 构建运行

```bash
# 开发模式（热重载）
./gradlew bootRun

# 打包
./gradlew build -x test

# 运行 JAR
java -jar build/libs/aquaflow-0.0.1-SNAPSHOT.jar
```

服务启动后访问：`http://localhost:8080`

### 测试数据

```bash
mysql -u root -p aquaflow < ../reset_data.sql
```

生成 50 客户、50 地址（济南市历城区）、10 水类型、35 订单。

## 数据库设计

### ER 图

```
┌──────────┐       ┌──────────┐       ┌────────────┐
│ customer │──1:N──│ address  │       │ water_type │
└──────────┘       └──────────┘       └────────────┘
      │                                     │
      │1:N                                  │1:1
      ▼                                     ▼
┌──────────┐       ┌────────────┐     ┌────────────┐
│  orders  │──N:1──│batch_order │──N:1│  inventory │
└──────────┘       └────────────┘     └────────────┘
      │                  │
      │1:N               │N:1
      ▼                  ▼
┌────────────┐     ┌──────────┐
│order_image │     │  batch   │
└────────────┘     └──────────┘
```

### 建表语句

```sql
-- 客户表
CREATE TABLE customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(30),
    note VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 地址表（关联客户）
CREATE TABLE address (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT COMMENT '关联客户ID',
    detail VARCHAR(255) NOT NULL,
    tag VARCHAR(100) COMMENT '标签：小区/工厂/写字楼/商场',
    lat DECIMAL(10,6) COMMENT '纬度',
    lng DECIMAL(10,6) COMMENT '经度',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 水类型表（name 唯一）
CREATE TABLE water_type (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    spec VARCHAR(100) COMMENT '规格：18.9L/桶',
    note VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 库存表（每种水类型一条记录）
CREATE TABLE inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    water_type_id BIGINT NOT NULL UNIQUE,
    quantity INT NOT NULL DEFAULT 0,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 订单表
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    address_id BIGINT NOT NULL,
    water_type_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    source TINYINT NOT NULL COMMENT '1电话 2微信群 3小程序',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1待组批 2配送中 3已完成 4已组批',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 配送批次表
CREATE TABLE batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1待配送 2配送中 3已完成',
    total_qty INT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 批次-订单关联表
CREATE TABLE batch_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 订单图片表
CREATE TABLE order_image (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    type TINYINT DEFAULT 1 COMMENT '1正常 2异常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

## API 接口文档

### 基础路径

```
http://localhost:8080/api
```

### 统一返回格式

```json
{
    "code": 0,
    "message": null,
    "data": { ... }
}
```

`code` 为 0 表示成功，非 0 表示失败。

---

### 认证 `/api/auth`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录 |

**请求体：**
```json
{ "username": "admin", "password": "123456" }
```

**响应：**
```json
{ "code": 0, "data": { "token": "xxx" } }
```

---

### 客户管理 `/api/customers`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/customers` | 获取所有客户 |
| GET | `/api/customers/{id}` | 根据 ID 获取客户 |
| POST | `/api/customers` | 新增客户 |
| PUT | `/api/customers/{id}` | 修改客户 |

**请求体：**
```json
{ "name": "张三", "phone": "13800138000", "note": "备注" }
```

---

### 地址管理 `/api/addresses`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/addresses` | 查询地址 |
| GET | `/api/addresses/{id}` | 根据 ID 获取地址 |
| POST | `/api/addresses` | 新增地址 |
| PUT | `/api/addresses/{id}` | 修改地址 |
| GET | `/api/addresses/report/tags` | 标签分布统计 |

**查询参数：**
- `customerId`：按客户筛选
- `tag`：按标签筛选（小区/工厂/写字楼/商场）
- `keyword`：按详细地址模糊搜索

**请求体：**
```json
{
    "customerId": 1,
    "detail": "济南市历城区洪家楼西路18号",
    "tag": "小区",
    "lat": 36.683,
    "lng": 117.065
}
```

---

### 水类型管理 `/api/water-types`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/water-types` | 获取所有水类型 |
| GET | `/api/water-types/{id}` | 根据 ID 获取水类型 |
| POST | `/api/water-types` | 新增水类型 |

**请求体：**
```json
{ "name": "纯净水(大桶)", "spec": "18.9L/桶" }
```

---

### 库存管理 `/api/inventory`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/inventory` | 查询所有库存 |
| POST | `/api/inventory/inbound` | 批量入库 |

**入库请求体：**
```json
{
    "items": [
        { "waterTypeId": 1, "quantity": 50 },
        { "waterTypeId": 2, "quantity": 30 }
    ]
}
```

---

### 订单管理 `/api/orders`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/orders` | 按条件查询订单 |
| GET | `/api/orders/{id}` | 根据 ID 获取订单 |
| POST | `/api/orders` | 创建订单 |
| PUT | `/api/orders/{id}/status` | 更新订单状态 |

**查询参数：**
- `status`：订单状态（1/2/3/4）
- `tag`：按地址标签筛选
- `createTimeStart`：开始日期（YYYY-MM-DD）
- `createTimeEnd`：结束日期（YYYY-MM-DD）

**创建订单请求体：**
```json
{
    "customerId": 1,
    "addressId": 1,
    "waterTypeId": 1,
    "quantity": 5,
    "source": 1
}
```

---

### 批次管理 `/api/batches`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/batches` | 查询批次列表 |
| GET | `/api/batches/{id}` | 批次详情（含订单列表） |
| POST | `/api/batches` | 创建批次（组批） |
| POST | `/api/batches/{id}/start` | 开始配送 |
| POST | `/api/batches/{id}/finish` | 完成配送 |
| DELETE | `/api/batches/{id}` | 删除批次（仅待配送状态） |

**创建批次请求体：**
```json
{ "orderIds": [1, 2, 3] }
```

**完成配送请求体：**
```json
{
    "finishedOrderIds": [1, 2],
    "unfinishedOrderIds": [3]
}
```

---

### 首页统计 `/api/dashboard`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dashboard/today` | 今日概览（待装车/装车中/已完成/库存预警） |
| GET | `/api/dashboard/pending-batches` | 待装车批次列表 |
| GET | `/api/dashboard/delivering-batches` | 装车中批次列表 |
| GET | `/api/dashboard/overview` | 经营概览（客户数/库存/总单/总批次） |
| GET | `/api/dashboard/order-trend` | 近7天订单趋势 |
| GET | `/api/dashboard/top-customers` | Top 10 客户排行 |
| GET | `/api/dashboard/order-source` | 订单来源分布 |
| GET | `/api/dashboard/order-status` | 订单状态分布 |
| GET | `/api/dashboard/water-type-sales` | 水类型销量统计 |

---

### 全局搜索 `/api/search`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/search` | 搜索客户和地址 |

**查询参数：** `keyword`（搜索关键词）

---

## 核心业务逻辑

### 组批流程（BatchService.create）

1. 接收订单 ID 列表
2. 校验所有订单状态为"待组批"
3. 按水类型汇总所需数量
4. 校验库存是否充足
5. 扣减库存
6. 创建批次记录
7. 创建 batch_order 关联
8. 更新订单状态为"已组批"

### 完成配送（BatchService.finish）

1. 接收已完成和未完成的订单 ID 列表
2. 已完成订单 → 状态改为"已完成"
3. 未完成订单 → 恢复为"待组批"，恢复库存
4. 批次状态改为"已完成"

## 开发规范

1. **分层清晰**：Controller → Service → Mapper，职责分离
2. **统一返回**：所有接口返回 `Result<T>` 格式
3. **异常处理**：业务异常抛 RuntimeException，全局处理器统一返回
4. **关联查询**：订单查询通过 XML 联表获取客户名、地址、水类型等信息

---

# V2.0 数据库预留设计

以下表和字段为 V2 规划预留，当前版本不实现，但数据库设计已考虑兼容性。

## 新增表

```sql
-- 客户归属变更记录
CREATE TABLE customer_station_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    from_station_id BIGINT,
    to_station_id BIGINT,
    reason VARCHAR(200),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 押金记录
CREATE TABLE deposit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    type TINYINT NOT NULL COMMENT '1充值 2退款 3赔偿扣除 4调整',
    amount DECIMAL(10,2) NOT NULL,
    note VARCHAR(200),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 水票账户
CREATE TABLE ticket_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    water_type_id BIGINT NOT NULL,
    remain_quantity INT NOT NULL DEFAULT 0,
    UNIQUE KEY (customer_id, water_type_id)
);

-- 水票流水
CREATE TABLE ticket_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    water_type_id BIGINT NOT NULL,
    increase_qty INT DEFAULT 0,
    decrease_qty INT DEFAULT 0,
    order_id BIGINT,
    source VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 企业客户信息
CREATE TABLE company_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL UNIQUE,
    company_name VARCHAR(100) NOT NULL,
    contact_person VARCHAR(50),
    contact_phone VARCHAR(30),
    payment_method VARCHAR(50),
    due_days INT DEFAULT 30
);

-- 配送员
CREATE TABLE delivery_staff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    phone VARCHAR(30),
    station_id BIGINT,
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 水站
CREATE TABLE station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    factory_id BIGINT,
    name VARCHAR(100) NOT NULL,
    manager VARCHAR(50),
    phone VARCHAR(30),
    address VARCHAR(200),
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 水厂
CREATE TABLE factory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    contact_person VARCHAR(50),
    contact_phone VARCHAR(30),
    address VARCHAR(200),
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

## 现有表新增字段

```sql
-- customer 表
ALTER TABLE customer ADD COLUMN owner_station_id BIGINT COMMENT '所属水站ID';
ALTER TABLE customer ADD COLUMN bind_time DATETIME COMMENT '绑定时间';
ALTER TABLE customer ADD COLUMN bind_reason VARCHAR(100) COMMENT '绑定原因';
ALTER TABLE customer ADD COLUMN last_delivery_time DATETIME COMMENT '最近配送时间';
ALTER TABLE customer ADD COLUMN deposit_balance DECIMAL(10,2) DEFAULT 0 COMMENT '押金余额';
ALTER TABLE customer ADD COLUMN customer_type TINYINT DEFAULT 1 COMMENT '1个人 2企业';
ALTER TABLE customer ADD COLUMN first_order_time DATETIME COMMENT '首次下单时间';
ALTER TABLE customer ADD COLUMN total_orders INT DEFAULT 0 COMMENT '累计订单数';
ALTER TABLE customer ADD COLUMN total_consumption DECIMAL(10,2) DEFAULT 0 COMMENT '累计消费';
ALTER TABLE customer ADD COLUMN avg_cycle_days INT COMMENT '平均配送周期';
ALTER TABLE customer ADD COLUMN tags VARCHAR(200) COMMENT '标签';

-- orders 表
ALTER TABLE orders ADD COLUMN payment_status TINYINT DEFAULT 1 COMMENT '1待付款 2已付款';
ALTER TABLE orders ADD COLUMN settlement_status TINYINT DEFAULT 1 COMMENT '1未结算 2已结算';
ALTER TABLE orders ADD COLUMN due_date DATE COMMENT '应付款日期';
ALTER TABLE orders ADD COLUMN delivery_bucket_qty INT DEFAULT 0 COMMENT '送出空桶数';
ALTER TABLE orders ADD COLUMN return_bucket_qty INT DEFAULT 0 COMMENT '回收空桶数';
ALTER TABLE orders ADD COLUMN delivery_staff_id BIGINT COMMENT '配送员ID';
ALTER TABLE orders ADD COLUMN guard_info VARCHAR(200) COMMENT '门卫信息';
ALTER TABLE orders ADD COLUMN delivery_time_request VARCHAR(100) COMMENT '配送时间要求';
ALTER TABLE orders ADD COLUMN special_note VARCHAR(200) COMMENT '特殊说明';
ALTER TABLE orders ADD COLUMN factory_id BIGINT COMMENT '水厂ID';
ALTER TABLE orders ADD COLUMN station_id BIGINT COMMENT '水站ID';
```

## V2 实体类预留字段

当前 Entity 不做改动，V2 开发时按需添加上述字段。
