<template>
  <div v-if="route.path === '/login'">
    <router-view />
  </div>
  <el-container v-else style="height: 100vh;">
    <el-aside :width="isCollapse ? '64px' : '200px'" class="sidebar" :class="{ collapsed: isCollapse }">
      <div class="sidebar-logo" @click="goHome">
        <span class="logo-icon">💧</span>
        <span v-show="!isCollapse" class="logo-text">AquaFlow</span>
        <span v-show="!isCollapse" class="logo-badge">{{ userRole === 'manager' ? '水站' : '配送' }}</span>
      </div>
      <el-menu :default-active="route.path" router :collapse="isCollapse"
        background-color="transparent" text-color="#94a3b8" active-text-color="#60a5fa"
        :collapse-transition="true" class="sidebar-menu">

        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon><span>首页</span>
        </el-menu-item>

        <template v-if="userRole === 'manager'">
          <el-menu-item index="/order">
            <el-icon><Document /></el-icon><span>订单管理</span>
          </el-menu-item>
          <el-menu-item index="/deliver-assign">
            <el-icon><Van /></el-icon><span>配送任务分配</span>
          </el-menu-item>
          <el-menu-item index="/inventory">
            <el-icon><Box /></el-icon><span>库存管理</span>
          </el-menu-item>
          <el-menu-item index="/customer">
            <el-icon><User /></el-icon><span>客户管理</span>
          </el-menu-item>
          <el-menu-item index="/address">
            <el-icon><Location /></el-icon><span>地址管理</span>
          </el-menu-item>
          <el-menu-item index="/water">
            <el-icon><Goods /></el-icon><span>商品管理</span>
          </el-menu-item>
          <el-menu-item index="/barrel-return">
            <el-icon><Box /></el-icon><span>退桶审批</span>
          </el-menu-item>
          <el-menu-item index="/ticket">
            <el-icon><Tickets /></el-icon><span>水票管理</span>
          </el-menu-item>
          <el-menu-item index="/deposit">
            <el-icon><Money /></el-icon><span>押金管理</span>
          </el-menu-item>
          <el-menu-item index="/payment">
            <el-icon><Wallet /></el-icon><span>支付管理</span>
          </el-menu-item>
          <el-menu-item index="/staff">
            <el-icon><Avatar /></el-icon><span>员工管理</span>
          </el-menu-item>
          <el-menu-item index="/address-map">
            <el-icon><MapLocation /></el-icon><span>地址地图</span>
          </el-menu-item>
          <el-menu-item index="/report">
            <el-icon><DataAnalysis /></el-icon><span>数据报表</span>
          </el-menu-item>
        </template>

        <template v-else-if="userRole === 'delivery'">
          <el-menu-item index="/my-deliveries">
            <el-icon><Van /></el-icon><span>我的配送</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header-toolbar" height="50px">
        <div style="display: flex; align-items: center; gap: 16px;">
          <el-icon class="collapse-btn" @click="isCollapse = !isCollapse" :size="20">
            <Fold v-if="!isCollapse" /><Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div style="display: flex; align-items: center; gap: 16px;">
          <span class="header-time">{{ currentTime }}</span>
          <el-icon class="header-icon" @click="showSearch = true" :size="18"><Search /></el-icon>
          <el-switch v-model="isDark" active-text="🌙" inactive-text="☀️" @change="toggleTheme"
            style="--el-switch-on-color: #409eff;" />
          <el-dropdown @command="handleCommand">
            <span style="display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--text-regular);">
              <el-avatar :size="28" style="background: #409eff;">{{ roleLabel }}</el-avatar>
              {{ userInfo.nickname || roleLabel }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="claim">认领所属</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>

    <GlobalSearch v-model="showSearch" @navigate="handleNavigate" />
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Odometer, User, Location, MapLocation, Goods, Box, Document, Tickets, DataAnalysis,
  Fold, Expand, Search, Money, Wallet, Avatar, Van } from '@element-plus/icons-vue'
import GlobalSearch from './components/GlobalSearch.vue'
import { authApi } from './api'

const route = useRoute()
const router = useRouter()
const isCollapse = ref(false)
const isDark = ref(localStorage.getItem('theme') === 'dark')
const showSearch = ref(false)
const currentTime = ref('')
const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || '{}'))

const userRole = computed(() => localStorage.getItem('userRole') || '')

const roleLabel = computed(() => {
  const map = { manager: '站', delivery: '配' }
  return map[userRole.value] || '用'
})

let timer = null

const goHome = () => {
  router.push('/dashboard')
}

const toggleTheme = (val) => {
  document.documentElement.setAttribute('data-theme', val ? 'dark' : 'light')
  localStorage.setItem('theme', val ? 'dark' : 'light')
}

const updateTime = () => {
  const now = new Date()
  currentTime.value = now.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

const handleNavigate = (path) => {
  showSearch.value = false
  router.push(path)
}

const handleCommand = (cmd) => {
  if (cmd === 'claim') router.push('/claim')
  else if (cmd === 'logout') handleLogout()
}

const handleLogout = async () => {
  try {
    await authApi.logout()
  } catch (e) {
  }
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('userInfo')
  localStorage.removeItem('userRole')
  localStorage.removeItem('stationId')
  localStorage.removeItem('staffId')
  router.push('/login')
}

onMounted(() => {
  if (isDark.value) toggleTheme(true)
  updateTime()
  timer = setInterval(updateTime, 60000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.sidebar {
  background: var(--bg-sidebar);
  background-image: linear-gradient(180deg, rgba(15,23,42,1) 0%, rgba(15,23,42,0.97) 100%);
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.sidebar-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 56px;
  cursor: pointer;
  border-bottom: 1px solid rgba(255,255,255,0.06);
  flex-shrink: 0;
  gap: 8px;
}
.logo-icon { font-size: 22px; }
.logo-text {
  color: #f1f5f9;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.02em;
  white-space: nowrap;
}
.logo-badge {
  font-size: 10px;
  color: #60a5fa;
  background: rgba(59,130,246,0.12);
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
  white-space: nowrap;
}
.sidebar-menu {
  border-right: none !important;
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}
.sidebar-menu:not(.el-menu--collapse) {
  width: 200px;
}
.header-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-header);
  border-bottom: 1px solid var(--border-color);
  transition: background var(--transition);
  padding: 0 24px;
  height: 56px;
}
.collapse-btn {
  cursor: pointer;
  color: var(--text-secondary);
  transition: color 0.2s;
}
.collapse-btn:hover { color: var(--color-primary); }
.header-time {
  font-size: 13px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.header-icon {
  cursor: pointer;
  color: var(--text-secondary);
  transition: color 0.2s;
}
.header-icon:hover { color: var(--color-primary); }
.main-content {
  padding: 24px;
  background: var(--bg-body);
  overflow-y: auto;
  transition: background var(--transition);
}
</style>
