# AquaFlow 用户微信小程序

> 面向C端用户的桶装水订购小程序 — 首页即下单页，一键复购

## 设计理念

### 核心原则：这不是电商 App

传统电商：浏览 → 加购 → 购物车 → 结算 → 支付 → 物流
AquaFlow：打开 → 选水 → 选数量 → 下单 → 等配送

桶装水的购买行为是**单一品类+周期性复购**，不是多品类随机购物。所以：

- ❌ 没有购物车
- ❌ 没有多品类合并结算
- ❌ 没有商品详情页（所有水类型信息已经在首页展示）
- ✅ 首页就是下单页
- ✅ 常用订单一键复购
- ✅ 水桶资产一目了然

### 首页设计逻辑

```
┌─────────────────────────────────────┐
│  📍 张三 138xxxx                     │  ← 第一层：在哪送？
│  济南市历下区XX路18号                 │
├─────────────────────────────────────┤
│  🪣 农夫山泉(大桶) ×2桶  ›          │  ← 第二层：手上有几桶？
├─────────────────────────────────────┤
│  ⚡ 已根据常用订单自动填入            │  ← 第三层：上次买了什么？
├─────────────────────────────────────┤
│  选择水类型                           │  ← 第四层：这次要什么？
│  ┌─────┐ ┌─────┐ ┌─────┐
│  │💧   │ │💧   │ │💧   │
│  │纯净水│ │矿泉水│ │天然水│
│  │¥20  │ │¥22  │ │¥18  │
│  └─────┘ └─────┘ └─────┘
├─────────────────────────────────────┤
│  数量  [−] 2 [+] 桶                  │  ← 第五层：要几桶？
├─────────────────────────────────────┤
│  备注  放门口、几号楼、联系人...       │  ← 第六层：特殊要求？
├─────────────────────────────────────┤
│  合计 ¥40           [立即下单]       │  ← 一步完成
└─────────────────────────────────────┘
```

**每一层解决一个问题，从上到下就是一次完整的下单决策。**

---

## 功能模块

### 1. 首页（下单页）

**定位：** 整个小程序的核心，90%的用户操作在这里完成。

**功能：**
- 默认地址展示，点击切换
- 水桶明细（各类型持有桶数）
- 水类型选择（网格卡片，带价格）
- 数量步进器
- 备注输入
- 一键下单
- 常用订单自动填入
- 最近订单再来一单

**数据来源：**
- 地址：`GET /api/addresses`
- 水桶：`GET /api/barrels/summary-by-type`
- 商品：`GET /api/products`
- 常用订单：`GET /api/order-templates/quick`
- 最近订单：`GET /api/orders`

### 2. 常用订单模板

**设计背景：** 桶装水是周期性消费，用户每次买的水类型和数量基本固定。

**功能：**
- 保存常用下单参数（水类型+桶数+地址+备注）
- 首页自动填入启用的模板
- 支持多个模板切换
- 下单成功后提示"存为常用订单"
- 从历史订单创建模板

**数据来源：**
- `GET /api/order-templates` — 获取模板列表
- `POST /api/order-templates` — 保存模板
- `PUT /api/order-templates/{id}/toggle` — 启用/禁用
- `DELETE /api/order-templates/{id}` — 删除
- `POST /api/order-templates/from-order` — 从订单创建

### 3. 水桶管理

**设计背景：** 桶是资产，不是消耗品。送出去的桶要回来，桶有押金。

**功能：**
- 持有桶数统计
- 按水类型分的桶数明细
- 押金余额
- 退桶申请
- 退桶记录

**数据来源：**
- `GET /api/barrels/summary` — 概况
- `GET /api/barrels/summary-by-type` — 按类型明细
- `POST /api/barrels/return` — 申请退桶

### 4. 地址管理

**功能：**
- 地址列表（支持选择模式，从首页进入可选中返回）
- 新增/编辑地址
- 设置默认地址
- 删除地址

**数据来源：**
- `GET /api/addresses` — 列表
- `POST /api/addresses` — 新增
- `PUT /api/addresses/{id}` — 修改
- `DELETE /api/addresses/{id}` — 删除
- `PUT /api/addresses/{id}/default` — 设默认

### 5. 水票

**设计背景：** 水票类似预付卡，客户预购一定数量水票，每次配送扣减。

**功能：**
- 水票余额查询（按水类型分）
- 消费记录
- 水票流水

**数据来源：**
- `GET /api/tickets` — 余额
- `GET /api/ticket-records` — 流水

### 6. 我的

**功能：**
- 用户信息（微信头像/昵称）
- 水桶押金概况（持有桶数/押金余额/元每桶）
- 菜单入口：商城、我的水桶、常用订单、地址管理、我的水票、联系客服、关于

### 7. 登录

**流程：**
```
用户打开小程序
  → wx.login() 获取 code
  → 发送到后端 /api/auth/wx-login
  → 后端用微信 code 换取 openid
  → 查找/创建客户记录
  → 返回 token + customerId
  → 存入本地存储
```

---

## 页面结构

```
pages/
├── home/              # 首页（下单页）— 核心页面
├── order/
│   ├── list/          # 订单列表
│   ├── detail/        # 订单详情
│   ├── create/        # 创建订单（商品详情入口）
│   └── success/       # 下单成功
├── address/
│   ├── list/          # 地址列表
│   └── edit/          # 地址编辑
├── template/          # 常用订单管理
├── barrel/            # 水桶管理
├── shop/              # 商城（浏览所有水类型）
├── ticket/            # 水票查询
├── mine/              # 我的
├── login/             # 微信登录
├── service/           # 联系客服
└── about/             # 关于
```

---

## 项目结构

```
miniapp-user/
├── api/                    # 接口层（每个模块一个文件）
│   ├── auth.js             # 登录接口
│   ├── product.js          # 水类型接口
│   ├── order.js            # 订单接口
│   ├── template.js         # 常用订单接口
│   ├── address.js          # 地址接口
│   ├── barrel.js           # 水桶接口
│   ├── ticket.js           # 水票接口
│   ├── customer.js         # 客户资料接口
│   └── payment.js          # 支付接口
│
├── components/             # 公共组件
│   ├── Loading/            # 加载组件
│   ├── Empty/              # 空状态组件
│   ├── Price/              # 价格组件
│   ├── ProductCard/        # 商品卡片
│   └── OrderCard/          # 订单卡片
│
├── config/
│   ├── api.js              # API 地址配置
│   ├── constant.js         # 常量配置
│   └── env.js              # 环境配置
│
├── pages/                  # 页面目录
│   ├── home/               # 首页（下单页）
│   ├── order/              # 订单（列表/详情/创建/成功）
│   ├── address/            # 地址（列表/编辑）
│   ├── template/           # 常用订单模板
│   ├── barrel/             # 水桶管理
│   ├── shop/               # 商城（浏览水类型）
│   ├── ticket/             # 水票查询
│   ├── payment/            # 支付页
│   ├── mine/               # 个人中心
│   ├── login/              # 微信登录
│   └── service/            # 联系客服
│
├── services/               # 业务逻辑层
│   └── loginService.js     # 登录状态管理
│
├── utils/                  # 工具函数
│   ├── request.js          # 网络请求封装
│   ├── storage.js          # 本地存储
│   ├── format.js           # 格式化工具
│   ├── validator.js        # 表单验证
│   └── backendCheck.js     # 后端连通性检查
│
├── styles/                 # 样式文件
│   ├── variable.wxss       # CSS 变量（主题色）
│   ├── common.wxss         # 公共样式
│   └── theme.wxss          # 主题样式
│
├── app.js                  # 小程序入口（登录检查）
├── app.json                # 小程序配置（TabBar 等）
└── app.wxss                # 全局样式
```

---

## 技术细节

### 主题色

```
主色：#4facfe → #00f2fe（渐变蓝）
强调色：#ff6b35（价格橙）
成功色：#52c41a
文字色：#333 / #666 / #999
背景色：#f5f7fa
```

### API 配置

`config/api.js` 中配置后端地址：

```javascript
const API_CONFIG = {
  dev: { baseUrl: 'http://192.168.0.104:8080' },  // 开发环境（局域网IP）
  prod: { baseUrl: 'https://your-domain.com' }     // 生产环境
}
```

> 注意：微信开发者工具模拟器中 `localhost` 不可用，必须使用局域网 IP。

### 请求封装

`utils/request.js` 封装了所有 HTTP 请求：

```javascript
const { get, post, put, del } = require('../utils/request')

// GET 请求
get(API.WATER_TYPES)

// POST 请求（JSON body）
post(API.CREATE_ORDER, { waterTypeId: 1, quantity: 2 })

// POST 请求（带 query 参数）
post(API.ADDRESSES, data)

// PUT 请求
put(`${API.ADDRESSES}/${id}`, data)

// DELETE 请求
del(`${API.ADDRESSES}/${id}`, { customerId: 1 })
```

### 客户标识

所有 API 请求自动携带 `customerId` 参数，来源：

```javascript
// config/api.js
const getCustomerId = () => {
  return wx.getStorageSync('customerId') || 1
}
```

登录后后端返回 `customerId`，存入本地存储。

---

## 下单流程

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  首页     │ →  │  选水类型 │ →  │  填数量   │ →  │  提交    │
│  自动填入 │    │  点击卡片 │    │  步进器   │    │  下单    │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                                                       │
                                                       ▼
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  首页     │ ←  │  存为常用 │ ←  │  下单成功 │ ←  │  后端    │
│  下次自动 │    │  订单模板 │    │  显示ID  │    │  创建订单│
│  填入     │    │          │    │          │    │          │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
```

### 错误处理

- 未选择水类型：`请选择水类型`
- 未选择地址：`请选择配送地址`
- 后端不可用：`下单失败: 请检查后端`
- 网络错误：`网络错误 xxx`

---

## 开发指南

### 添加新页面

1. 在 `pages/` 下创建目录
2. 创建 `index.js` / `index.wxml` / `index.wxss` / `index.json`
3. 在 `app.json` 的 `pages` 数组中注册
4. 如需 TabBar，在 `tabBar.list` 中添加

### 添加新 API

1. 在 `config/api.js` 的 `API` 对象中添加端点
2. 在对应的 `api/*.js` 文件中添加请求函数
3. 在页面中引入并调用

### 样式规范

- 使用 `styles/variable.wxss` 中的 CSS 变量
- 卡片圆角：`20rpx`
- 内边距：`24rpx 32rpx`
- 主色渐变：`linear-gradient(135deg, #4facfe, #00f2fe)`
- 价格色：`#ff6b35`

---

## License

MIT
