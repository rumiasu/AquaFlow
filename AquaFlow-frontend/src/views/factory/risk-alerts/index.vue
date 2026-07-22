<template>
  <div class="risk-alerts page-container">
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card"><div class="stat-num text-danger">{{ stats.critical || 0 }}</div><div class="stat-label">紧急预警</div></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card"><div class="stat-num text-warning">{{ stats.warning || 0 }}</div><div class="stat-label">警告</div></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card"><div class="stat-num text-primary">{{ stats.info || 0 }}</div><div class="stat-label">提示</div></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card"><div class="stat-num">{{ stats.total || 0 }}</div><div class="stat-label">待处理</div></el-card>
      </el-col>
    </el-row>
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">风险预警列表</span>
          <el-button type="primary" size="small" @click="runCheck" :loading="checking">手动检测</el-button>
        </div>
      </template>
      <el-table :data="alerts" border stripe v-loading="loading">
        <el-table-column prop="stationName" label="水站" width="120" />
        <el-table-column prop="title" label="预警标题" />
        <el-table-column label="等级" width="80">
          <template #default="{ row }"><el-tag :type="levelMap[row.alertLevel]?.type" size="small">{{ levelMap[row.alertLevel]?.label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }"><el-tag size="small" type="info">{{ typeMap[row.alertType] || row.alertType }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'danger' : row.status === 2 ? 'warning' : 'success'" size="small">{{ { 1: '未读', 2: '已读', 3: '已处理' }[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" size="small" text @click="markRead(row)">已读</el-button>
            <el-button v-if="row.status !== 3" size="small" text type="success" @click="handleAlert(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="handleVisible" title="处理预警" width="450px">
      <p style="color: var(--text-secondary);">{{ currentAlert?.title }}</p>
      <p style="color: var(--text-secondary); font-size: 13px;">{{ currentAlert?.content }}</p>
      <el-input v-model="handleNote" type="textarea" :rows="3" placeholder="处理备注（选填）" />
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="submitHandle">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { factoryOpsApi } from '../../../api'

const alerts = ref([])
const stats = ref({})
const loading = ref(false)
const checking = ref(false)
const handleVisible = ref(false)
const currentAlert = ref(null)
const handleNote = ref('')
const levelMap = { 1: { label: '提示', type: 'info' }, 2: { label: '警告', type: 'warning' }, 3: { label: '紧急', type: 'danger' } }
const typeMap = { ORDER_DECLINE: '订单下降', INVENTORY_BACKLOG: '库存积压', LOW_STOCK: '库存不足', CUSTOMER_LOSS: '客户流失', NO_ACTIVITY: '无活动' }

const loadData = async () => {
  loading.value = true
  try {
    const [a, s] = await Promise.all([factoryOpsApi.alertList(), factoryOpsApi.alertStats()])
    alerts.value = a; stats.value = s
  } finally { loading.value = false }
}

const runCheck = async () => { checking.value = true; try { await factoryOpsApi.alertCheck(); ElMessage.success('检测完成'); await loadData() } finally { checking.value = false } }
const markRead = async (row) => { await factoryOpsApi.alertRead(row.id); row.status = 2 }
const handleAlert = (row) => { currentAlert.value = row; handleNote.value = ''; handleVisible.value = true }
const submitHandle = async () => { await factoryOpsApi.alertHandle(currentAlert.value.id, { handleNote: handleNote.value }); ElMessage.success('已处理'); handleVisible.value = false; loadData() }

onMounted(loadData)
</script>

<style scoped>
/* 页面级补充 */
</style>
