import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/login/index.vue'), meta: { public: true } },
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'Dashboard', component: () => import('../views/dashboard/index.vue'), meta: { title: '首页' } },
  { path: '/customer', name: 'Customer', component: () => import('../views/customer/index.vue'), meta: { title: '客户管理' } },
  { path: '/address', name: 'Address', component: () => import('../views/address/index.vue'), meta: { title: '地址管理' } },
  { path: '/address-map', name: 'AddressMap', component: () => import('../views/address-map/index.vue'), meta: { title: '地址地图' } },
  { path: '/water', name: 'WaterType', component: () => import('../views/water/index.vue'), meta: { title: '水类型管理' } },
  { path: '/barrel-return', name: 'BarrelReturn', component: () => import('../views/barrel-return/index.vue'), meta: { title: '退桶审批' } },
  { path: '/ticket', name: 'Ticket', component: () => import('../views/ticket/index.vue'), meta: { title: '水票管理' } },
  { path: '/deposit', name: 'Deposit', component: () => import('../views/deposit/index.vue'), meta: { title: '押金管理' } },
  { path: '/inventory', name: 'Inventory', component: () => import('../views/inventory/index.vue'), meta: { title: '库存管理' } },
  { path: '/order', name: 'Order', component: () => import('../views/order/index.vue'), meta: { title: '订单管理' } },
  { path: '/batch', name: 'Batch', component: () => import('../views/batch/index.vue'), meta: { title: '批次管理' } },
  { path: '/staff', name: 'Staff', component: () => import('../views/delivery-staff/index.vue'), meta: { title: '员工管理' } },
  { path: '/station', name: 'Station', component: () => import('../views/station/index.vue'), meta: { title: '水站管理' } },
  { path: '/factory', name: 'Factory', component: () => import('../views/factory/index.vue'), meta: { title: '水厂管理' } },
  { path: '/report', name: 'Report', component: () => import('../views/report/index.vue'), meta: { title: '数据报表' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.public) return next()
  const token = localStorage.getItem('token')
  if (!token) return next('/login')
  next()
})

export default router
