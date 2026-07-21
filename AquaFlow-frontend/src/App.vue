<template>
  <div v-if="route.path === '/login'">
    <router-view />
  </div>
  <el-container v-else style="height: 100vh;">
    <!-- 可折叠侧边栏 -->
    <el-aside :width="isCollapse ? '64px' : '200px'" class="sidebar" :class="{ collapsed: isCollapse }">
      <div class="sidebar-logo" @click="$router.push('/dashboard')">
        <span class="logo-icon">💧</span>
        <span v-show="!isCollapse" class="logo-text">AquaFlow</span>
      </div>
      <el-menu :default-active="route.path" router :collapse="isCollapse"
        background-color="#1a2332" text-color="#8a919e" active-text-color="#409eff"
        :collapse-transition="true" class="sidebar-menu">
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon><span>首页</span>
        </el-menu-item>
        <el-menu-item index="/order">
          <el-icon><Document /></el-icon><span>订单管理</span>
        </el-menu-item>
        <el-menu-item index="/batch">
          <el-icon><Tickets /></el-icon><span>批次管理</span>
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
          <el-icon><Goods /></el-icon><span>水类型</span>
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
        <el-menu-item index="/staff">
          <el-icon><Avatar /></el-icon><span>员工管理</span>
        </el-menu-item>
        <el-menu-item index="/address-map">
          <el-icon><MapLocation /></el-icon><span>地址地图</span>
        </el-menu-item>
        <el-menu-item index="/station">
          <el-icon><OfficeBuilding /></el-icon><span>水站管理</span>
        </el-menu-item>
        <el-menu-item index="/factory">
          <el-icon><House /></el-icon><span>水厂管理</span>
        </el-menu-item>
        <el-menu-item index="/report">
          <el-icon><DataAnalysis /></el-icon><span>数据报表</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 右侧主区域 -->
    <el-container>
      <!-- 顶部工具栏 -->
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
          <el-dropdown>
            <span style="display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--text-regular);">
              <el-avatar :size="28" style="background: #409eff;">管</el-avatar>
              {{ userInfo.nickname || '管理员' }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 主内容区 -->
      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>

    <!-- 全局搜索 -->
    <GlobalSearch v-model="showSearch" @navigate="handleNavigate" />
  </el-container>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Odometer, User, Location, MapLocation, Goods, Box, Document, Tickets, DataAnalysis,
  Fold, Expand, Search, Money } from '@element-plus/icons-vue'
import GlobalSearch from './components/GlobalSearch.vue'

const route = useRoute()
const router = useRouter()
const isCollapse = ref(false)
const isDark = ref(localStorage.getItem('theme') === 'dark')
const showSearch = ref(false)
const currentTime = ref('')
const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || '{}'))

let timer = null

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

const handleLogout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
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
  background: #1a2332;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}
.sidebar-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 50px;
  cursor: pointer;
  border-bottom: 1px solid #243447;
}
.logo-icon { font-size: 24px; }
.logo-text {
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  margin-left: 8px;
  white-space: nowrap;
}
.sidebar-menu {
  border-right: none !important;
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
  padding: 0 16px;
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
}
.header-icon {
  cursor: pointer;
  color: var(--text-secondary);
  transition: color 0.2s;
}
.header-icon:hover { color: var(--color-primary); }
.main-content {
  padding: 20px;
  background: var(--bg-body);
  overflow-y: auto;
  transition: background var(--transition);
}
</style>
