<template>
  <div class="collaboration page-container">
    <el-card style="margin-bottom: 16px;">
      <template #header>
        <div class="page-header">
          <span class="page-title">发起调拨</span>
          <el-button type="primary" size="small" @click="showCreateDialog">新建调拨</el-button>
        </div>
      </template>
      <div v-if="availableStock.length === 0" style="text-align: center; padding: 20px; color: var(--text-secondary);">暂无可调拨库存</div>
      <el-table v-else :data="availableStock" border stripe size="small" max-height="250">
        <el-table-column prop="stationName" label="水站" />
        <el-table-column prop="waterTypeName" label="水类型" />
        <el-table-column prop="spec" label="规格" width="100" />
        <el-table-column prop="quantity" label="当前库存" width="90" />
        <el-table-column prop="available" label="可调拨" width="80">
          <template #default="{ row }"><span class="text-success text-bold">{{ row.available }}</span></template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">调拨记录</span>
          <el-select v-model="filterStatus" size="small" style="width: 120px;" clearable placeholder="全部状态">
            <el-option label="待审批" :value="1" /><el-option label="已审批" :value="2" />
            <el-option label="已完成" :value="3" /><el-option label="已取消" :value="4" />
          </el-select>
        </div>
      </template>
      <el-table :data="transfers" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="fromStationName" label="调出站" />
        <el-table-column prop="toStationName" label="调入站" />
        <el-table-column prop="waterTypeName" label="水类型" />
        <el-table-column prop="quantity" label="数量" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type" size="small">{{ statusMap[row.status]?.label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" size="small" type="success" text @click="handleApprove(row)">审批</el-button>
            <el-button v-if="row.status === 2" size="small" type="primary" text @click="handleComplete(row)">完成</el-button>
            <el-button v-if="row.status === 1" size="small" type="danger" text @click="handleCancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="createVisible" title="新建调拨" width="500px">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="调出站">
          <el-select v-model="createForm.fromStationId" placeholder="选择调出站" style="width: 100%;">
            <el-option v-for="s in availableStock" :key="`${s.stationId}-${s.waterTypeId}`" :label="`${s.stationName} - ${s.waterTypeName}`" :value="s.stationId" />
          </el-select>
        </el-form-item>
        <el-form-item label="调入站">
          <el-select v-model="createForm.toStationId" placeholder="选择调入站" style="width: 100%;">
            <el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="水类型">
          <el-select v-model="createForm.waterTypeId" placeholder="选择水类型" style="width: 100%;">
            <el-option v-for="w in waterTypes" :key="w.id" :label="`${w.name} ${w.spec}`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="createForm.quantity" :min="1" style="width: 100%;" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { factoryOpsApi, stationApi, waterTypeApi } from '../../../api'

const availableStock = ref([])
const transfers = ref([])
const stations = ref([])
const waterTypes = ref([])
const loading = ref(false)
const filterStatus = ref(null)
const createVisible = ref(false)
const createForm = ref({ fromStationId: null, toStationId: null, waterTypeId: null, quantity: 1 })
const statusMap = { 1: { label: '待审批', type: 'warning' }, 2: { label: '已审批', type: 'primary' }, 3: { label: '已完成', type: 'success' }, 4: { label: '已取消', type: 'info' } }

const loadData = async () => {
  loading.value = true
  try {
    const [stock, tl, s, w] = await Promise.all([factoryOpsApi.transferAvailable(), factoryOpsApi.transferList({ status: filterStatus.value }), stationApi.list(), waterTypeApi.list()])
    availableStock.value = stock; transfers.value = tl; stations.value = s; waterTypes.value = w
  } finally { loading.value = false }
}

watch(filterStatus, loadData)
const showCreateDialog = () => { createForm.value = { fromStationId: null, toStationId: null, waterTypeId: null, quantity: 1 }; createVisible.value = true }
const submitCreate = async () => { await factoryOpsApi.transferCreate(createForm.value); ElMessage.success('调拨已提交'); createVisible.value = false; loadData() }
const handleApprove = async (row) => { await ElMessageBox.confirm('确认审批通过？'); await factoryOpsApi.transferApprove(row.id, { note: '审批通过' }); ElMessage.success('已审批'); loadData() }
const handleComplete = async (row) => { await ElMessageBox.confirm('确认调拨完成？'); await factoryOpsApi.transferComplete(row.id, { note: '已完成' }); ElMessage.success('调拨完成'); loadData() }
const handleCancel = async (row) => { await ElMessageBox.confirm('确认取消？'); await factoryOpsApi.transferApprove(row.id, { note: '已取消' }); loadData() }

onMounted(loadData)
</script>

<style scoped>
.collaboration { max-width: 1400px; margin: 0 auto; }
</style>
