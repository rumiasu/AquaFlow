# AquaFlow V1.0

面向小型桶装水配送站的轻量级管理后台。

## 项目简介

AquaFlow 旨在提高桶装水配送站的日常运营效率，核心功能包括：

- **录单管理**：快速创建订单，支持电话/微信群/小程序多来源
- **组批配送**：合理勾选订单组批，自动校验并扣减库存
- **库存管理**：入库、查询、组批扣减、删除恢复
- **客户/地址管理**：维护客户信息与配送地址

目标：轻量、易用，不追求复杂 ERP，专注核心业务流程。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.0.6 | Web 框架 |
| MyBatis | 4.0.1 | ORM 框架 |
| MySQL | 8.x | 数据库 |
| Lombok | - | 简化实体类代码 |
| Gradle | - | 构建工具 |
| Java | 17 | 运行环境 |

## 项目结构

```
com.example.aquaflow
├── AquaFlowApplication.java              # 启动类
├── common/
│   └── Result.java                        # 统一返回封装
├── config/
│   └── CorsConfig.java                    # 跨域配置
├── constant/
│   ├── OrderStatus.java                   # 订单状态常量
│   └── BatchStatus.java                   # 批次状态常量
├── entity/                                # 实体类（对应数据库表）
│   ├── Customer.java                      # 客户
│   ├── Address.java                       # 地址
│   ├── WaterType.java                     # 水类型
│   ├── Inventory.java                     # 库存
│   ├── Orders.java                        # 订单
│   ├── Batch.java                         # 配送批次
│   ├── BatchOrder.java                    # 批次-订单关联
│   └── OrderImage.java                    # 订单图片
├── dto/                                   # 数据传输对象
│   ├── BatchCreateDTO.java                # 批次创建请求
│   ├── BatchFinishDTO.java                # 批次完成请求
│   ├── InventoryInboundDTO.java           # 入库请求
│   └── OrderStatusDTO.java                # 订单状态更新
├── mapper/                                # MyBatis 映射接口
│   ├── CustomerMapper.java
│   ├── AddressMapper.java
│   ├── WaterTypeMapper.java
│   ├── InventoryMapper.java
│   ├── OrderMapper.java
│   ├── BatchMapper.java
│   ├── BatchOrderMapper.java
│   └── OrderImageMapper.java
├── service/                               # 业务逻辑层
│   ├── CustomerService.java
│   ├── AddressService.java
│   ├── WaterTypeService.java
│   ├── InventoryService.java
│   ├── OrderService.java
│   ├── BatchService.java
│   ├── OrderImageService.java
│   └── impl/                              # 实现类
├── controller/                            # 控制器层
│   ├── CustomerController.java
│   ├── AddressController.java
│   ├── WaterTypeController.java
│   ├── InventoryController.java
│   ├── OrderController.java
│   ├── BatchController.java
│   └── OrderImageController.java
└── exception/
    └── GlobalExceptionHandler.java        # 全局异常处理

resources/mapper/                          # MyBatis XML 映射文件
├── CustomerMapper.xml
├── AddressMapper.xml
├── WaterTypeMapper.xml
├── InventoryMapper.xml
├── OrderMapper.xml
└── BatchMapper.xml
```

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.x
- Gradle（或使用项目自带 `gradlew`）

### 数据库初始化

```sql
CREATE DATABASE IF NOT EXISTS aquaflow DEFAULT CHARACTER SET utf8mb4;
USE aquaflow;

-- 客户表
CREATE TABLE customer (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    phone VARCHAR(20),
    note VARCHAR(255),
    create_time DATETIME,
    update_time DATETIME
);

-- 地址表
CREATE TABLE address (
    id INT AUTO_INCREMENT PRIMARY KEY,
    detail VARCHAR(255) NOT NULL,
    tag VARCHAR(50),
    lat DOUBLE,
    lng DOUBLE,
    create_time DATETIME,
    update_time DATETIME
);

-- 水类型表
CREATE TABLE water_type (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    spec VARCHAR(50),
    note VARCHAR(255),
    create_time DATETIME,
    update_time DATETIME
);

-- 库存表
CREATE TABLE inventory (
    id INT AUTO_INCREMENT PRIMARY KEY,
    water_type_id INT NOT NULL,
    quantity INT DEFAULT 0,
    update_time DATETIME
);

-- 订单表
CREATE TABLE orders (
    id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    address_id INT NOT NULL,
    water_type_id INT NOT NULL,
    quantity INT NOT NULL,
    source INT COMMENT '1电话 2微信群 3小程序',
    status INT DEFAULT 1 COMMENT '1待组批 2配送中 3已完成 4已组批',
    create_time DATETIME,
    update_time DATETIME
);

-- 配送批次表
CREATE TABLE batch (
    id INT AUTO_INCREMENT PRIMARY KEY,
    status INT DEFAULT 1 COMMENT '1待配送 2配送中 3已完成',
    total_qty INT DEFAULT 0,
    create_time DATETIME,
    update_time DATETIME
);

-- 批次-订单关联表
CREATE TABLE batch_order (
    id INT AUTO_INCREMENT PRIMARY KEY,
    batch_id INT NOT NULL,
    order_id INT NOT NULL,
    create_time DATETIME
);

-- 订单图片表
CREATE TABLE order_image (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL,
    url VARCHAR(255),
    type INT COMMENT '1正常 2异常',
    create_time DATETIME
);
```

### 启动项目

```bash
# 修改数据库配置
# src/main/resources/application.yml

# 构建并运行
./gradlew bootRun
```

服务启动后访问：`http://localhost:8080`

## 数据库设计

```
┌──────────┐     ┌──────────┐     ┌────────────┐
│ customer │────<│ orders   │>────│ water_type │
└──────────┘     └──────────┘     └────────────┘
                      │                  │
                      │                  │
                 ┌────┴─────┐     ┌──────┴──────┐
                 │batch_order│     │  inventory  │
                 └────┬─────┘     └─────────────┘
                      │
                 ┌────┴─────┐
                 │  batch   │
                 └──────────┘

┌──────────┐
│ address  │ (独立维护，订单引用)
└──────────┘
```

| 表名 | 说明 |
|------|------|
| customer | 客户信息 |
| address | 配送地址 |
| water_type | 水类型（如农夫山泉18.9L） |
| inventory | 库存数量 |
| orders | 订单 |
| batch | 配送批次 |
| batch_order | 批次与订单的多对多关联 |
| order_image | 配送完成照片 |

## API 接口文档

### 基础路径

```
http://localhost:8080/api
```

### 统一返回格式

```json
{
    "code": 0,       // 0成功, 1失败
    "message": "...",
    "data": { ... }
}
```

---

### 客户管理 `/api/customers`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/customers` | 获取所有客户 |
| GET | `/api/customers/{id}` | 根据ID获取客户 |
| POST | `/api/customers` | 新增客户 |
| PUT | `/api/customers/{id}` | 修改客户 |

**新增/修改请求体：**
```json
{
    "name": "张三",
    "phone": "13800138000",
    "note": "备注信息"
}
```

---

### 地址管理 `/api/addresses`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/addresses` | 查询地址（支持tag/keyword筛选） |
| GET | `/api/addresses/{id}` | 根据ID获取地址 |
| POST | `/api/addresses` | 新增地址 |
| PUT | `/api/addresses/{id}` | 修改地址 |

**查询参数：**
- `tag`：按标签筛选（如"小区"、"工厂"）
- `keyword`：按详细地址模糊搜索

**请求体：**
```json
{
    "detail": "XX小区1号楼101",
    "tag": "小区",
    "lat": 30.123,
    "lng": 120.456
}
```

---

### 水类型管理 `/api/water-types`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/water-types` | 获取所有水类型 |
| GET | `/api/water-types/{id}` | 根据ID获取水类型 |
| POST | `/api/water-types` | 新增水类型 |

**请求体：**
```json
{
    "name": "农夫山泉",
    "spec": "18.9L",
    "note": "桶装水"
}
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
| GET | `/api/orders` | 按状态查询订单列表 |
| GET | `/api/orders/{id}` | 根据ID获取订单 |
| POST | `/api/orders` | 创建订单 |
| PUT | `/api/orders/{id}/status` | 更新订单状态 |

**查询参数：**
- `status`（必填）：订单状态（1待组批/2配送中/3已完成/4已组批）
- `tag`：按地址标签筛选
- `createTimeStart`：开始时间
- `createTimeEnd`：结束时间

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
| GET | `/api/batches/{id}` | 根据ID获取批次详情（含订单列表） |
| POST | `/api/batches` | 创建批次（组批） |
| POST | `/api/batches/{id}/start` | 开始配送 |
| POST | `/api/batches/{id}/finish` | 完成配送 |
| DELETE | `/api/batches/{id}` | 删除批次（仅待配送状态） |

**创建批次请求体：**
```json
{
    "orderIds": [1, 2, 3]
}
```

**完成配送请求体：**
```json
{
    "finishedOrderIds": [1, 2],
    "unfinishedOrderIds": [3]
}
```

---

## 核心业务流程

```
水类型维护 → 库存入库 → 客户/地址维护
                              │
                              ▼
                         创建订单（待组批）
                              │
                              ▼
                    勾选订单 → 创建配送批次
                              │
                    ┌─────────┴─────────┐
                    │ 校验库存是否充足   │
                    │ 扣减库存          │
                    │ 订单状态→已组批    │
                    └─────────┬─────────┘
                              │
                              ▼
                         开始配送
                              │
                              ▼
                         完成配送
                    ┌─────────┴─────────┐
                    │ 标记完成/未完成    │
                    │ 批次状态→已完成    │
                    └───────────────────┘
```

## 订单状态流转

```
1(待组批) → 4(已组批) → 2(配送中) → 3(已完成)
```

## 批次状态流转

```
1(待配送) → 2(配送中) → 3(已完成)
```

> 注意：删除批次仅允许在"待配送"状态，删除后订单恢复为"待组批"状态。

## 未来规划（V2）

- [ ] 订单支持多水类型（order_item 表）
- [ ] 客户默认地址
- [ ] 库存流水记录
- [ ] 配送完成照片上传
- [ ] 微信小程序端
- [ ] 配送员端
- [ ] 数据统计与客户分析

## 开发规范

1. **业务驱动**：先想用户怎么用，再设计接口和数据库
2. **RESTful**：接口路径使用复数名词，HTTP 方法区分操作
3. **分层清晰**：Controller → Service → Mapper，职责分离
4. **统一返回**：所有接口返回 `Result<T>` 格式
5. **异常处理**：业务异常抛出 RuntimeException，由全局处理器统一返回

## License

MIT
