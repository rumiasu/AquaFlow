import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/shared/login/index.vue'), meta: { public: true } },

  // ========== 统一路由 - 根据 meta.roles 控制访问权限 ==========
  // roles: undefined = 所有人可访问, ['factory'] = 仅厂长, ['manager'] = 仅站长, ['delivery'] = 仅配送员

  // --- 通用页面（所有角色） ---
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'Dashboard', component: () => import('../views/station/dashboard/index.vue'), meta: { title: '首页' } },
  { path: '/factory-dashboard', name: 'FactoryDashboard', component: () => import('../views/factory/dashboard/index.vue'), meta: { title: '首页', roles: ['factory'] } },

  // --- 站长专用页面 ---
  { path: '/order', name: 'Order', component: () => import('../views/station/order/index.vue'), meta: { title: '订单管理', roles: ['manager'] } },
  { path: '/batch', name: 'Batch', component: () => import('../views/station/batch/index.vue'), meta: { title: '批次管理', roles: ['manager'] } },
  { path: '/inventory', name: 'Inventory', component: () => import('../views/station/inventory/index.vue'), meta: { title: '库存管理', roles: ['manager'] } },
  { path: '/customer', name: 'Customer', component: () => import('../views/station/customer/index.vue'), meta: { title: '客户管理', roles: ['manager'] } },
  { path: '/address', name: 'Address', component: () => import('../views/station/address/index.vue'), meta: { title: '地址管理', roles: ['manager'] } },
  { path: '/address-map', name: 'AddressMap', component: () => import('../views/station/address-map/index.vue'), meta: { title: '地址地图', roles: ['manager'] } },
  { path: '/water', name: 'Water', component: () => import('../views/station/water/index.vue'), meta: { title: '水类型管理', roles: ['manager'] } },
  { path: '/barrel-return', name: 'BarrelReturn', component: () => import('../views/station/barrel-return/index.vue'), meta: { title: '退桶审批', roles: ['manager'] } },
  { path: '/ticket', name: 'Ticket', component: () => import('../views/station/ticket/index.vue'), meta: { title: '水票管理', roles: ['manager'] } },
  { path: '/deposit', name: 'Deposit', component: () => import('../views/station/deposit/index.vue'), meta: { title: '押金管理', roles: ['manager'] } },
  { path: '/payment', name: 'Payment', component: () => import('../views/station/payment/index.vue'), meta: { title: '支付管理', roles: ['manager'] } },
  { path: '/staff', name: 'Staff', component: () => import('../views/station/staff/index.vue'), meta: { title: '员工管理', roles: ['manager'] } },
  { path: '/report', name: 'Report', component: () => import('../views/station/report/index.vue'), meta: { title: '数据报表', roles: ['manager'] } },

  // --- 厂长专用页面 ---
  { path: '/station-ops', name: 'StationOps', component: () => import('../views/factory/station-ops/index.vue'), meta: { title: '水站运营', roles: ['factory'] } },
  { path: '/analysis', name: 'Analysis', component: () => import('../views/factory/analysis/index.vue'), meta: { title: '数据分析', roles: ['factory'] } },
  { path: '/collaboration', name: 'Collaboration', component: () => import('../views/factory/collaboration/index.vue'), meta: { title: '水站协同', roles: ['factory'] } },
  { path: '/risk-alerts', name: 'RiskAlerts', component: () => import('../views/factory/risk-alerts/index.vue'), meta: { title: '风险预警', roles: ['factory'] } },
  { path: '/station-mgmt', name: 'StationMgmt', component: () => import('../views/factory/station-mgmt/index.vue'), meta: { title: '水站管理', roles: ['factory'] } },
  { path: '/factory-mgmt', name: 'FactoryMgmt', component: () => import('../views/factory/factory-mgmt/index.vue'), meta: { title: '水厂管理', roles: ['factory'] } },
  { path: '/profile/:id', name: 'StationProfile', component: () => import('../views/factory/profile/index.vue'), meta: { title: '水站画像', roles: ['factory'] } },

  // --- 配送员专用页面 ---
  { path: '/my-deliveries', name: 'MyDeliveries', component: () => import('../views/station/delivery/index.vue'), meta: { title: '我的配送', roles: ['delivery'] } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.public) return next()
  const token = localStorage.getItem('accessToken')
  if (!token) return next('/login')

  const userRole = localStorage.getItem('userRole')
  // 安全修复：角色缺失时不默认最高权限，而是要求重新登录
  if (!userRole) {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    return next('/login')
  }
  const requiredRoles = to.meta.roles

  // 路由有角色限制，且与用户角色不匹配 → 拦截
  if (requiredRoles && !requiredRoles.includes(userRole)) {
    return next('/dashboard')
  }

  // 厂长访问 /dashboard 时重定向到水厂首页
  if (to.path === '/dashboard' && userRole === 'factory') {
    return next('/factory-dashboard')
  }

  next()
})

export default router
