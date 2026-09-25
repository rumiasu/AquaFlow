# AquaFlow Frontend

Vue 3 + Element Plus + ECharts + Leaflet 的管理后台。三角色共用同一个 URL，登录后动态显示功能菜单。

## 项目规模

| 指标 | 数量 |
|------|------|
| Vue 页面 | 26 |
| API 模块 | 20+ |
| 路由页面 | 厂长 8 页 + 站长/配送 14 页 + 共享 1 页 |

## 项目结构

```
src/
├── api/index.js                  # 统一 API（auth + 20+ 业务模块 + factoryOps）
├── router/index.js               # 路由定义 + 角色守卫（beforeEach 拦截越权访问）
├── utils/request.js              # Axios 封装（自动注入 token/stationId + 401 自动续期）
├── styles/theme.css              # 亮色/暗色主题 CSS 变量
├── components/GlobalSearch.vue   # 全局搜索框
├── App.vue                       # 根布局 + 三角色菜单模板 + 角色标识
├── views/
│   ├── shared/login/             # 登录页（双栏品牌设计 + 角色提示）
│   ├── station/                  # 站长/配送员页面（14页）
│   │   ├── dashboard/            # 首页（站长/配送员共用）
│   │   ├── delivery/             # 我的配送（配送员专用）
│   │   ├── order/                # 订单管理
│   │   ├── batch/                # 批次管理
│   │   ├── inventory/            # 库存管理
│   │   ├── customer/             # 客户管理
│   │   ├── address/              # 地址管理
│   │   ├── address-map/          # 地址地图（Leaflet）
│   │   ├── water/                # 水类型管理
│   │   ├── barrel-return/        # 退桶审批
│   │   ├── ticket/               # 水票管理
│   │   ├── deposit/              # 押金管理
│   │   ├── payment/              # 支付管理
│   │   ├── staff/                # 员工管理
│   │   └── report/               # 数据报表
│   └── factory/                  # 厂长页面（8页）
│       ├── dashboard/            # 首页（独立于 station/dashboard）
│       ├── station-ops/          # 水站运营总览
│       ├── analysis/             # 数据分析
│       ├── profile/              # 水站画像
│       ├── collaboration/        # 水站协同
│       ├── risk-alerts/          # 风险预警
│       ├── station-mgmt/         # 水站管理
│       └── factory-mgmt/         # 水厂管理
└── main.js
```

## 认证

### JWT 双 Token 机制

| 令牌 | 存于 localStorage | 用途 |
|------|-------------------|------|
| `accessToken` | `accessToken` | 每次请求头携带 |
| `refreshToken` | `refreshToken` | 续期专用，不参与业务请求 |

### Token 自动续期（request.js）

1. 请求返回 401 → 拦截器检查是否正在刷新
2. 若无并发刷新 → 调用 `/api/auth/refresh`
3. 续期成功 → 更新 localStorage，重放原请求队列
4. 续期失败 → 清除登录态，跳转登录页
5. 若有并发刷新 → 请求进入等待队列，续期完毕统一重放

### 角色守卫（router/index.js）

```
router.beforeEach → 检查 localStorage 中 userRole
  ├─ 无 role → 跳转登录页
  ├─ 有 role → 校验路由 meta.roles 是否包含当前 role
  ├─ 厂长访问 /dashboard → 自动 redirect 到 /factory-dashboard
  └─ 越权访问 → 重定向到对应角色的首页
```

## 角色菜单

| 角色 | 见到的菜单 |
|------|-----------|
| 厂长 `factory` | 首页 + 水站运营 + 数据分析 + 水站协同 + 风险预警 + 水站管理 + 水厂管理 |
| 站长 `manager` | 首页 + 订单 + 批次 + 库存 + 客户 + 地址 + 水类型 + 退桶 + 水票 + 押金 + 支付 + 员工 + 地图 + 报表 |
| 配送员 `delivery` | 首页 + 我的配送 |

`App.vue` 中根据 `userRole` 渲染三套不同的 `el-menu`，并显示角色标识徽章。

## 登录页

双栏设计：
- **左栏**：品牌标识 + 水站配送场景插画 + 标语
- **右栏**：登录表单（用户名/密码/角色提示信息）

登录框提示：`admin / admin123` → 厂长，`张建国 / 123456` → 站长，`李永强 / 123456` → 配送员

## 数据范围

- **厂长**：看到所有水站的汇总/统计/分析数据
- **站长**：只能看到本水站的数据（`request.js` 自动注入 `stationId`）
- **配送员**：只能看到分配给自己的配送任务
# AquaFlow Frontend

Vue 3 + Element Plus + ECharts + Leaflet 的管理后台。三角色共用同一个 URL，登录后动态显示功能菜单。

## 项目结构

```
src/
├── api/index.js                  # 统一 API（auth + 20+ 业务模块 + factoryOps）
├── router/index.js               # 路由定义 + 角色守卫（beforeEach 拦截越权访问）
├── utils/request.js              # Axios 封装（自动注入 token/stationId + 401 自动续期）
├── styles/theme.css              # 亮色/暗色主题 CSS 变量
├── components/GlobalSearch.vue   # 全局搜索框
├── App.vue                       # 根布局 + 三角色菜单模板 + 角色标识
├── views/
│   ├── shared/login/             # 登录页（双栏品牌设计 + 角色提示）
│   ├── station/                  # 站长/配送员页面（14页）
│   │   ├── dashboard/            # 首页（站长/配送员共用）
│   │   ├── delivery/             # 我的配送（配送员专用）
│   │   ├── order/ / batch/ / inventory/ / customer/ / address/
│   │   ├── address-map/ / water/ / barrel-return/
│   │   ├── ticket/ / deposit/ / payment/ / staff/ / report/
│   └── factory/                  # 厂长页面（8页）
│       ├── dashboard/            # 首页（独立于 station/dashboard）
│       ├── station-ops/ / analysis/ / profile/
│       ├── collaboration/ / risk-alerts/
│       └── station-mgmt/ / factory-mgmt/
└── main.js
```

## 认证

### JWT 双 Token 机制

| 令牌 | 存于 localStorage | 用途 |
|------|-------------------|------|
| `accessToken` | `accessToken` | 每次请求头携带 |
| `refreshToken` | `refreshToken` | 续期专用，不参与业务请求 |

### Token 自动续期（request.js）

1. 请求返回 401 → 拦截器检查是否正在刷新
2. 若无并发刷新 → 调用 `/api/auth/refresh`
3. 续期成功 → 更新 localStorage，重放原请求队列
4. 续期失败 → 清除登录态，跳转登录页
5. 若有并发刷新 → 请求进入等待队列，续期完毕统一重放

### 角色守卫（router/index.js）

```
router.beforeEach → 检查 localStorage 中 userRole
  ├─ 无 role → 跳转登录页
  ├─ 有 role → 校验路由 meta.roles 是否包含当前 role
  ├─ 厂长访问 /dashboard → 自动 redirect 到 /factory-dashboard
  └─ 越权访问 → 重定向到对应角色的首页
```

## 角色菜单

| 角色 | 见到的菜单 |
|------|-----------|
| 厂长 | 首页 + 水站运营 + 数据分析 + 水站协同 + 风险预警 + 水站管理 + 水厂管理 |
| 站长 | 首页 + 订单 + 批次 + 库存 + 客户 + 地址 + 水类型 + 退桶 + 水票 + 押金 + 支付 + 员工 + 地图 + 报表 |
| 配送员 | 首页 + 我的配送 |

`App.vue` 中根据 `userRole` 渲染三套不同的 `el-menu`，并显示角色标识徽章。

## 登录页

双栏设计：
- **左栏**：品牌标识 + 水站配送场景插画 + 标语
- **右栏**：登录表单（用户名/密码/角色提示信息）

登录框提示：`admin / admin123` → 厂长，`张建国 / 123456` → 站长，`李永强 / 123456` → 配送员
