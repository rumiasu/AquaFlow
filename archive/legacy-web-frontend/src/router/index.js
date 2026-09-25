import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/shared/login/index.vue'), meta: { public: true } },

  // ========== 统一路由 - 根据 meta.roles 控制访问权限 ==========
  // roles: undefined = 所有人可访问, ['manager'] = 仅站长, ['delivery'] = 仅配送员

  // --- 通用页面（所有角色） ---
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'Dashboard', component: () => import('../views/station/dashboard/index.vue'), meta: { title: '首页' } },

  // --- 站长专用页面 ---
  { path: '/order', name: 'Order', component: () => import('../views/station/order/index.vue'), meta: { title: '订单管理', roles: ['manager'] } },
  { path: '/deliver-assign', name: 'DeliverAssign', component: () => import('../views/station/deliver-assign/index.vue'), meta: { title: '配送任务分配', roles: ['manager'] } },
  { path: '/inventory', name: 'Inventory', component: () => import('../views/station/inventory/index.vue'), meta: { title: '库存管理', roles: ['manager'] } },
  { path: '/customer', name: 'Customer', component: () => import('../views/station/customer/index.vue'), meta: { title: '客户管理', roles: ['manager'] } },
  { path: '/address', name: 'Address', component: () => import('../views/station/address/index.vue'), meta: { title: '地址管理', roles: ['manager'] } },
  { path: '/address-map', name: 'AddressMap', component: () => import('../views/station/address-map/index.vue'), meta: { title: '地址地图', roles: ['manager'] } },
  { path: '/water', name: 'Water', component: () => import('../views/station/water/index.vue'), meta: { title: '商品管理', roles: ['manager'] } },
  { path: '/barrel-return', name: 'BarrelReturn', component: () => import('../views/station/barrel-return/index.vue'), meta: { title: '退桶审批', roles: ['manager'] } },
  { path: '/ticket', name: 'Ticket', component: () => import('../views/station/ticket/index.vue'), meta: { title: '水票管理', roles: ['manager'] } },
  { path: '/deposit', name: 'Deposit', component: () => import('../views/station/deposit/index.vue'), meta: { title: '押金管理', roles: ['manager'] } },
  { path: '/payment', name: 'Payment', component: () => import('../views/station/payment/index.vue'), meta: { title: '支付管理', roles: ['manager'] } },
  { path: '/staff', name: 'Staff', component: () => import('../views/station/staff/index.vue'), meta: { title: '员工管理', roles: ['manager'] } },
  { path: '/report', name: 'Report', component: () => import('../views/station/report/index.vue'), meta: { title: '数据报表', roles: ['manager'] } },
  { path: '/file-manage', name: 'FileManage', component: () => import('../views/station/file-manage/index.vue'), meta: { title: '文件管理', roles: ['manager'] } },

  // --- 认领所属 ---
  { path: '/claim', name: 'Claim', component: () => import('../views/shared/claim/index.vue'), meta: { title: '认领所属' } },

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
  if (!userRole) {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    return next('/login')
  }
  const requiredRoles = to.meta.roles

  if (requiredRoles && !requiredRoles.includes(userRole)) {
    return next('/dashboard')
  }

  next()
})

export default router
