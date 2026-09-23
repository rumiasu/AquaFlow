<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <span class="page-title">认领所属</span>
      </template>

      <el-result v-if="affiliated" icon="success" title="您已有所属组织" sub-title="无需认领">
        <template #extra>
          <el-button type="primary" @click="$router.push('/dashboard')">返回首页</el-button>
        </template>
      </el-result>

      <template v-else>
        <el-alert title="您当前没有所属组织，请选择以下选项进行认领" type="warning" :closable="false" show-icon style="margin-bottom: 20px;" />

        <div v-if="userRole === 'manager'" style="margin-top: 24px;">
          <h3 style="margin-bottom: 16px;">认领水站（站长）</h3>
          <el-table :data="stations" border stripe v-loading="stationLoading">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="name" label="水站名称" />
            <el-table-column prop="address" label="地址" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="claimStation(row.id)">认领</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="userRole === 'delivery'" style="margin-top: 24px;">
          <h3 style="margin-bottom: 16px;">认领水站（配送员）</h3>
          <el-table :data="stations" border stripe v-loading="stationLoading">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="name" label="水站名称" />
            <el-table-column prop="address" label="地址" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="claimStation(row.id)">认领</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { staffApi, stationApi } from '../../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

const router = useRouter()
const userRole = ref(localStorage.getItem('userRole') || '')
const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || '{}'))

const stations = ref([])
const stationLoading = ref(false)

const affiliated = computed(() => {
  const info = userInfo.value
  if (userRole.value === 'manager' || userRole.value === 'delivery') {
    return !!info.stationId
  }
  return true
})

const loadStations = async () => {
  stationLoading.value = true
  try { stations.value = await stationApi.list() } finally { stationLoading.value = false }
}

const claimStation = async (stationId) => {
  const staffId = localStorage.getItem('staffId')
  if (!staffId) return ElMessage.error('无法获取员工信息')
  await ElMessageBox.confirm('确定认领该水站？', '提示', { type: 'info' })
  await staffApi.claim(parseInt(staffId), { stationId })
  ElMessage.success('认领成功！请重新登录')
  localStorage.clear()
  router.push('/login')
}

onMounted(() => {
  if (userRole.value === 'manager' || userRole.value === 'delivery') loadStations()
})
</script>
