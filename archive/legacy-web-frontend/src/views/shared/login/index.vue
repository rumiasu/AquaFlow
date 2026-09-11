<template>
  <div class="login-page">
    <div class="login-brand">
      <div class="brand-content">
        <div class="brand-logo">
          <span class="brand-icon">💧</span>
          <h1 class="brand-name">AquaFlow</h1>
        </div>
        <h2 class="brand-slogan">桶装水配送管理系统</h2>
        <p class="brand-desc">高效智能的水站运营管理平台，覆盖订单-配送-客户全链路</p>
        <div class="brand-features">
          <div class="feature-item">
            <div class="feature-dot"></div>
            <span>订单分配与配送追踪</span>
          </div>
          <div class="feature-item">
            <div class="feature-dot"></div>
            <span>客户、地址、商品一站式管理</span>
          </div>
          <div class="feature-item">
            <div class="feature-dot"></div>
            <span>水票、押金、退桶全流程</span>
          </div>
          <div class="feature-item">
            <div class="feature-dot"></div>
            <span>数据概览与经营统计</span>
          </div>
        </div>
        <div class="brand-footer">
          <span>© 2026 AquaFlow</span>
        </div>
      </div>
    </div>

    <div class="login-form-area">
      <div class="form-container">
        <div class="form-header">
          <h3>欢迎登录</h3>
          <p>请输入您的账号和密码</p>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="handleLogin" class="login-form">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" size="large" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码" prefix-icon="Lock" size="large" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleLogin" :loading="loading" size="large" class="login-btn">
              登 录
            </el-button>
          </el-form-item>
        </el-form>
        <div class="login-tip">
          <div class="tip-item"><span class="tip-role">提示</span><span class="tip-account">请联系管理员获取账号</span></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../../../api'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const form = ref({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await authApi.login(form.value)
    localStorage.setItem('accessToken', res.accessToken)
    localStorage.setItem('refreshToken', res.refreshToken)
    localStorage.setItem('userInfo', JSON.stringify(res))
    localStorage.setItem('userRole', res.role)
    if (res.stationId) localStorage.setItem('stationId', res.stationId)
    if (res.staffId) localStorage.setItem('staffId', res.staffId)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) {
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  height: 100vh;
  background: #f8fafc;
}

.login-brand {
  flex: 1;
  background: linear-gradient(135deg, #0f172a 0%, #1e3a5f 50%, #0f172a 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  min-width: 0;
}
.login-brand::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: radial-gradient(circle at 30% 40%, rgba(59,130,246,0.12) 0%, transparent 50%),
              radial-gradient(circle at 70% 60%, rgba(16,185,129,0.08) 0%, transparent 50%);
  animation: brand-bg 20s ease infinite;
}
@keyframes brand-bg {
  0%, 100% { transform: translate(0, 0); }
  50% { transform: translate(-2%, -1%); }
}

.brand-content {
  position: relative;
  z-index: 1;
  padding: 60px;
  max-width: 480px;
}
.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 32px;
}
.brand-icon {
  font-size: 40px;
  filter: drop-shadow(0 4px 12px rgba(59,130,246,0.3));
}
.brand-name {
  font-size: 32px;
  font-weight: 800;
  color: #f1f5f9;
  letter-spacing: -0.03em;
}
.brand-slogan {
  font-size: 22px;
  font-weight: 600;
  color: #e2e8f0;
  margin-bottom: 12px;
  line-height: 1.4;
}
.brand-desc {
  font-size: 14px;
  color: #94a3b8;
  line-height: 1.6;
  margin-bottom: 40px;
}
.brand-features {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 60px;
}
.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #cbd5e1;
  font-size: 14px;
}
.feature-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 8px rgba(59,130,246,0.5);
  flex-shrink: 0;
}
.brand-footer {
  color: #475569;
  font-size: 12px;
}

.login-form-area {
  width: 480px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ffffff;
  box-shadow: -4px 0 24px rgba(0,0,0,0.04);
}
.form-container {
  width: 100%;
  max-width: 360px;
  padding: 40px 0;
}
.form-header {
  margin-bottom: 36px;
}
.form-header h3 {
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 8px;
}
.form-header p {
  font-size: 14px;
  color: #64748b;
}

.login-form :deep(.el-input__wrapper) {
  border-radius: 8px !important;
  padding: 4px 12px !important;
  box-shadow: 0 0 0 1px #e2e8f0 inset !important;
  transition: all 0.2s ease !important;
}
.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #94a3b8 inset !important;
}
.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #2563eb inset !important;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  border-radius: 8px !important;
  background: #2563eb !important;
  border-color: #2563eb !important;
  letter-spacing: 2px;
}
.login-btn:hover {
  background: #3b82f6 !important;
  border-color: #3b82f6 !important;
}

.login-tip {
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid #f1f5f9;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tip-item {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}
.tip-role {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 20px;
  background: #f1f5f9;
  color: #64748b;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.tip-account {
  color: #94a3b8;
  font-family: 'SF Mono', 'Fira Code', monospace;
}

@media (max-width: 900px) {
  .login-brand { display: none; }
  .login-form-area { width: 100%; }
}
</style>
