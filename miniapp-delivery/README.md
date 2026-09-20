# AquaFlow 配送员小程序

配送员专用微信小程序，用于接收订单、完成配送、回收空桶等操作。

## 项目结构

```
miniapp-delivery/
├── app.js                 # 应用入口
├── app.json               # 应用配置
├── app.wxss               # 全局样式
├── project.config.json    # 项目配置
├── sitemap.json           #  sitemap 配置
├── api/                   # 接口封装
│   ├── auth.js           # 认证接口
│   ├── delivery.js       # 配送接口
│   ├── feedback.js       # 反馈接口
│   └── station-mgmt.js   # 水站管理接口
├── components/            # 公共组件
│   └── PrivacyPopup/     # 隐私协议弹窗
├── config/                # 配置文件
│   ├── api.js            # API 地址配置
│   └── env.js            # 环境配置
├── pages/                 # 页面（15+页）
│   ├── home/             # 首页（订单列表）
│   ├── order/            # 订单相关
│   │   ├── detail/       # 订单详情
│   │   └── complete/     # 完成配送
│   ├── coordination/     # 协调页（站长6Tab管理）
│   ├── station-mgmt/     # 水站管理
│   ├── history/          # 配送历史
│   ├── transfer/         # 转让记录
│   ├── barrel-records/   # 空桶记录
│   ├── mine/             # 个人中心
│   ├── login/            # 登录
│   ├── role-select/      # 角色选择
│   ├── bind-wait/        # 绑定等待
│   └── apply-bind/       # 申请绑定
├── static/                # 静态资源
│   └── tabbar/           # 底部导航图标
└── utils/                 # 工具函数
    ├── request.js        # 网络请求封装
    └── upload.js         # 图片上传
```

## 功能模块

### 1. 首页（站长专用）
4 个子 Tab：

| Tab | 内容 | 操作 |
|-----|------|------|
| 待分配 | 未分配的单 + 转单请求（合并） | 分配配送员 / 外派抢单池 |
| 接单 | 已分配但配送员未接单 | 换人 / 取消分配 |
| 抢单池 | 其他水站外派的订单 | 跳过 / 抢单 |
| 外派 | 本站外派订单状态 | 取消外派 / 重新外派 |

### 2. 配送页（配送员 + 站长共用）
- **待配送 Tab**：`status=1` 的单 —— 站长分配给我的 + 全站还没派出去的，按钮「接单配送」。
  [2026-09-19] 原叫「待接单」：站长自己也会接单（`acceptOrder` 允许两种角色），
  一个"待接单"的标签在站长眼里读起来像"还没人派单"，改为与订单状态同名的「待配送」。
- **配送中 Tab**：配送中订单（只含我自己的），按钮「完成配送」+「调解」
- **已完成 Tab**：今日完成订单（`date(update_time) = 今天`，**不是**全部历史；历史见「配送历史」）
- 调解功能：转给别的配送员 / 退回给站长

### 3. 订单详情
- 客户联系方式（接单后可见）
- 详细地址（支持复制）
- 订单内容
- 历史订单统计
- 客户备注（醒目显示）
- 操作按钮：导航、完成配送、异常反馈、转给同事、退回站长

### 4. 完成配送
- 回收空桶数量选择
- 付款方式选择（现金/微信/水票/月结）
- 备注输入
- 确认完成

### 5. 个人中心
- 账号与水站信息
- 统计卡**按角色分叉**（[2026-09-19]）：
  - 站长 = 今日净利 / 今日单数 / 桶异常·欠桶（净利点进去是「利润报表」的今日视图）
  - 配送员 = 今日完成 / 配送中 / 回桶数
  - 「待配送」不再出现在这里 —— 它在「配送」页就是第一个页签，重复报没有意义
- 功能菜单：配送历史、转让记录、异常反馈、设置
- 退出登录

## 开发说明

### 1. 配置 API 地址

编辑 `config/api.js`，修改 `dev.baseUrl` 为你的后端地址：

```javascript
const API_CONFIG = {
  dev: { baseUrl: 'http://127.0.0.1:8080' },  // 改为你的后端地址
  prod: { baseUrl: 'https://your-domain.com' }
}
```

### 2. 配置 AppID

编辑 `project.config.json`，修改 `appid` 为你的小程序 AppID：

```json
{
  "appid": "your-appid-here"
}
```

### 3. 替换 Tabbar 图标

当前使用的是占位图标，请替换为实际的配送图标：

- `static/tabbar/delivery.png` - 配送图标（未选中）
- `static/tabbar/delivery-active.png` - 配送图标（选中）
- `static/tabbar/mine.png` - 我的图标（未选中）
- `static/tabbar/mine-active.png` - 我的图标（选中）

图标要求：
- 格式：PNG
- 尺寸：81px × 81px
- 大小：不超过 40KB

### 4. 开发模式登录

小程序内置开发模式登录功能，无需微信认证即可测试：

1. 打开小程序，进入"我的"页面
2. 点击"开发模式登录"
3. 自动以配送员身份登录

### 5. 后端接口要求

配送员小程序需要以下后端接口支持：

| 接口 | 方法 | 说明 |
|-----|------|------|
| `/api/auth/dev-login` | POST | 开发模式登录 |
| `/api/auth/refresh` | POST | 刷新 Token |
| `/api/auth/logout` | POST | 退出登录 |
| `/api/auth/me` | GET | 获取当前用户信息 |
| `/api/delivery/orders/assigned-to-me` | GET | 配送员待配送列表（分配给我但未接单） |
| `/api/delivery/orders/delivering` | GET | 获取配送中订单 |
| `/api/delivery/orders/completed-today` | GET | 今日已完成订单 |
| `/api/delivery/orders/{id}` | GET | 获取订单详情 |
| `/api/delivery/orders/accept/{id}` | POST | 接单（status 1→3） |
| `/api/delivery/orders/complete/{id}` | POST | 完成配送 |
| `/api/delivery/orders/transfer/{id}` | POST | 转让订单（直转本站同事，须带 `deliveryStaffId`） |
| `/api/delivery/orders/return/{id}` | POST | 退回站长（待站长确认，确认后 delivery_staff_id 置空→回待分配池） |
| `/api/delivery/orders/assign/{id}` | POST | 站长分配（仅站长，分配后 status 不变，配送员手动接单） |
| `/api/delivery/orders/station-pending` | GET | 站长待分配列表（未分配配送员的单） |
| `/api/delivery/orders/station-assigned` | GET | 站长已分配但未接单的订单 |
| `/api/delivery/orders/station-transfer` | GET | 站长转单列表 |
| `/api/delivery/orders/pool` | GET | 抢单池（跨站外派订单） |
| `/api/delivery/orders/claim-pool/{id}` | POST | 从抢单池认领订单 |
| `/api/delivery/orders/dispatch-tracking` | GET | 外派追踪列表 |
| `/api/delivery/orders/cancel-dispatch/{id}` | POST | 取消外派 |
| `/api/delivery/orders/station-reject/{id}` | POST | 站长拒单 |
| `/api/delivery/stats/today` | GET | 今日统计 |

## 设计系统

使用与用户端小程序一致的设计系统：

- 主色：`#4F8EF7`（清新蓝）
- 成功色：`#34C759`
- 警告色：`#FF9500`
- 错误色：`#FF3B30`
- 背景色：`#F5F7FA`
- 卡片圆角：`24rpx`
- 阴影：`0 2rpx 12rpx rgba(0, 0, 0, 0.04)`

## 注意事项

1. **网络请求**：所有接口使用 JWT Token 认证，Token 过期会自动刷新
2. **隐私协议**：已集成微信隐私协议弹窗组件
3. **位置权限**：导航功能需要用户授权位置权限
4. **数据同步**：完成配送后数据会同步到后端，请确保网络连接正常

## 开发计划

- [x] 配送历史页面
- [x] 转让记录页面
- [x] 协调页（站长管理）
- [x] 水站管理
- [x] 绑定申请流程
- [x] 协调页重构（4 Tab：待分配/转单/抢单池/外派追踪）
- [x] Tab 角色分离（首页=站长，配送=配送员）
- [x] 转单合并到待分配tab + 新增接单tab
- [x] 首页改名（协调→首页）
- [x] 抢单池商品匹配提示
- [x] 订单流转优化（分配→待接单→接单→配送中）
- [x] 修复 ProductMapper SQL（product表无station_id）
- [ ] 异常反馈详情页面
- [ ] 推送通知（新订单提醒）
- [ ] 离线模式支持
- [ ] 拍照签收功能
