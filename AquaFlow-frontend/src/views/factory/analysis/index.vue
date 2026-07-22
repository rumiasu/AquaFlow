<template>
  <div class="analysis-page page-container">
    <el-card style="margin-bottom: 16px;">
      <template #header><span>智能运营建议</span></template>
      <div v-if="suggestions.length === 0" style="text-align: center; padding: 30px; color: var(--text-secondary);">暂无需要关注的事项</div>
      <div v-else class="suggestion-list">
        <div v-for="(s, i) in suggestions" :key="i" class="suggestion-item">
          <el-tag :type="s.priority === 'high' ? 'danger' : 'warning'" size="small">{{ s.priority === 'high' ? '紧急' : '注意' }}</el-tag>
          <span class="suggestion-station">{{ s.stationName }}</span>
          <span class="suggestion-msg">{{ s.message }}</span>
        </div>
      </div>
    </el-card>
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header><span>销量下降分析</span></template>
          <div v-if="declines.length === 0" style="text-align: center; padding: 30px; color: var(--text-secondary);">各站销量正常</div>
          <el-table v-else :data="declines" border stripe size="small">
            <el-table-column prop="stationName" label="水站" />
            <el-table-column prop="declineRate" label="下降%" width="80">
              <template #default="{ row }"><span class="text-danger text-bold">{{ row.declineRate }}%</span></template>
            </el-table-column>
            <el-table-column prop="consecutiveDays" label="连续天数" width="90" />
            <el-table-column label="原因" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-for="(r, i) in row.reasons" :key="i">{{ r }}<span v-if="i < row.reasons.length - 1">，</span></span>
              </template>
            </el-table-column>
            <el-table-column prop="suggestion" label="建议" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span>客户流失分析</span></template>
          <div v-if="churns.length === 0" style="text-align: center; padding: 30px; color: var(--text-secondary);">客户状况良好</div>
          <el-table v-else :data="churns" border stripe size="small">
            <el-table-column prop="stationName" label="水站" />
            <el-table-column prop="totalCustomers" label="总客户" width="80" />
            <el-table-column prop="activeCustomers" label="活跃" width="70" />
            <el-table-column prop="churnedCustomers" label="流失" width="70">
              <template #default="{ row }"><span :class="row.churnedCustomers > 0 ? 'text-danger' : ''">{{ row.churnedCustomers }}</span></template>
            </el-table-column>
            <el-table-column prop="churnRate" label="流失率" width="80">
              <template #default="{ row }"><span :class="row.churnRate > 15 ? 'text-danger text-bold' : 'text-warning text-bold'">{{ row.churnRate }}%</span></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <el-card>
      <template #header><span>库存压力分析</span></template>
      <el-table :data="pressures" border stripe>
        <el-table-column prop="stationName" label="水站" />
        <el-table-column prop="totalInventory" label="当前库存" width="100" />
        <el-table-column prop="totalSales" label="近30天销量" width="110" />
        <el-table-column prop="turnoverRate" label="周转率" width="90">
          <template #default="{ row }">{{ row.turnoverRate >= 0 ? row.turnoverRate : '-' }}</template>
        </el-table-column>
        <el-table-column prop="pressure" label="压力等级" width="100">
          <template #default="{ row }">
            <el-tag :type="row.pressure === '积压' ? 'danger' : row.pressure === '偏高' ? 'warning' : 'success'" size="small">{{ row.pressure }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { factoryOpsApi } from '../../../api'

const declines = ref([])
const churns = ref([])
const pressures = ref([])
const suggestions = ref([])

const loadData = async () => {
  try {
    const [d, c, p, s] = await Promise.all([
      factoryOpsApi.salesDecline(), factoryOpsApi.customerChurn(),
      factoryOpsApi.inventoryPressure(), factoryOpsApi.suggestions()
    ])
    declines.value = d; churns.value = c; pressures.value = p; suggestions.value = s
  } catch (e) { console.error(e) }
}

onMounted(loadData)
</script>

<style scoped>
.analysis-page { max-width: 1400px; margin: 0 auto; }
.suggestion-list { display: flex; flex-direction: column; gap: 10px; }
.suggestion-item { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.suggestion-item:last-child { border-bottom: none; }
.suggestion-station { font-weight: 600; color: var(--text-primary); min-width: 80px; }
.suggestion-msg { color: var(--text-regular); font-size: 13px; }
</style>
