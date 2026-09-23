<template>
  <div class="deliver-assign-page page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">配送任务分配</span>
          <el-button type="primary" @click="loadAll">刷新</el-button>
        </div>
      </template>

      <el-tabs v-model="activeTab" @tab-change="loadAll">
        <el-tab-pane label="待配送" name="pending">
          <el-table :data="pendingOrders" v-loading="loading" stripe>
            <el-table-column prop="id" label="订单号" width="100" />
            <el-table-column label="客户" width="150">
              <template #default="{ row }">{{ row.customerName || row.receiverName }}</template>
            </el-table-column>
            <el-table-column label="商品" min-width="140">
              <template #default="{ row }">{{ row.productName || row.waterTypeName }} × {{ row.quantity }} 桶</template>
            </el-table-column>
            <el-table-column prop="addressSnapshot" label="地址" show-overflow-tooltip />
            <el-table-column prop="createTime" label="创建时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="当前配送员" width="120">
              <template #default="{ row }">{{ deliveryStaffName(row.deliveryStaffId) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="openAssign(row)">分配</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && pendingOrders.length === 0" description="暂无待配送订单" />
        </el-tab-pane>

        <el-tab-pane label="配送中" name="delivering">
          <el-table :data="deliveringOrders" v-loading="loading" stripe>
            <el-table-column prop="id" label="订单号" width="100" />
            <el-table-column label="客户" width="150">
              <template #default="{ row }">{{ row.customerName || row.receiverName }}</template>
            </el-table-column>
            <el-table-column label="商品" min-width="140">
              <template #default="{ row }">{{ row.productName || row.waterTypeName }} × {{ row.quantity }} 桶</template>
            </el-table-column>
            <el-table-column prop="addressSnapshot" label="地址" show-overflow-tooltip />
            <el-table-column label="配送员" width="120">
              <template #default="{ row }">{{ deliveryStaffName(row.deliveryStaffId) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="openAssign(row)">重分配</el-button>
                <el-button type="warning" size="small" @click="handleReturn(row)">退回</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && deliveringOrders.length === 0" description="暂无配送中订单" />
        </el-tab-pane>

        <el-tab-pane label="转让/退回记录" name="records">
          <el-table :data="records" v-loading="loading" stripe>
            <el-table-column prop="id" label="订单号" width="100" />
            <el-table-column label="客户" width="150">
              <template #default="{ row }">{{ row.customerName || row.receiverName }}</template>
            </el-table-column>
            <el-table-column label="商品" min-width="140">
              <template #default="{ row }">{{ row.productName || row.waterTypeName }} × {{ row.quantity }} 桶</template>
            </el-table-column>
            <el-table-column label="当前状态" width="110">
              <template #default="{ row }">
                <el-tag size="small">{{ orderStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="specialNote" label="留痕" min-width="240" show-overflow-tooltip />
            <el-table-column prop="updateTime" label="更新时间" width="170">
              <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && records.length === 0" description="暂无转让/退回记录" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 分配/重分配对话框 -->
    <el-dialog v-model="assignVisible" :title="assignMode === 'reassign' ? '重分配配送员' : '分配配送员'" width="520px">
      <div v-if="currentOrder">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="订单号">#{{ currentOrder.id }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ currentOrder.customerName || currentOrder.receiverName }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ currentOrder.productName || currentOrder.waterTypeName }} × {{ currentOrder.quantity }} 桶</el-descriptions-item>
          <el-descriptions-item v-if="assignMode === 'reassign'" label="当前配送员">{{ deliveryStaffName(currentOrder.deliveryStaffId) }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="90px" style="margin-top: 16px">
          <el-form-item label="配送员">
            <el-select v-model="selectedStaffId" placeholder="请选择本站在职配送员" style="width: 100%">
              <el-option v-for="s in deliveryStaffs" :key="s.id" :label="s.name + (s.phone ? '（' + s.phone + '）' : '')" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="assignReason" type="textarea" :rows="2" placeholder="选填，如：临时改派、设备故障等" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAssign" :loading="submitting">确认{{ assignMode === 'reassign' ? '重分配' : '分配' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deliveryApi, orderApi, staffApi } from '../../../api'

const activeTab = ref('pending')
const loading = ref(false)
const submitting = ref(false)
const pendingOrders = ref([])
const deliveringOrders = ref([])
const records = ref([])
const deliveryStaffs = ref([])

const assignVisible = ref(false)
const assignMode = ref('assign')
const currentOrder = ref(null)
const selectedStaffId = ref(null)
const assignReason = ref('')

const stationId = () => Number(localStorage.getItem('stationId'))

const deliveryStaffName = (id) => {
  if (!id) return '未分配'
  const s = deliveryStaffs.value.find(x => String(x.id) === String(id))
  return s ? s.name : ('配送员' + id)
}

const orderStatusText = (status) => {
  const map = { 1: '待配送', 2: '配送中', 3: '已送达', 4: '已完成', 5: '已取消' }
  return map[status] || '未知'
}

const formatTime = (time) => {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 19)
}

const loadStaffs = async () => {
  try {
    const staffs = await staffApi.list({ stationId: stationId() })
    deliveryStaffs.value = (staffs || []).filter(s => s.role === 'DELIVERY')
  } catch (e) {
    console.error('加载配送员失败:', e)
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    const sid = stationId()
    if (!sid) throw new Error('无法识别当前水站')
    const [pendingRes, deliveringRes, recordsRes] = await Promise.all([
      orderApi.list({ stationId: sid, status: 4 }),
      orderApi.list({ stationId: sid, status: 2 }),
      deliveryApi.getTransferRecords()
    ])
    pendingOrders.value = pendingRes || []
    deliveringOrders.value = deliveringRes || []
    records.value = recordsRes || []
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const openAssign = (order) => {
  currentOrder.value = order
  assignMode.value = order.status === 2 ? 'reassign' : 'assign'
  selectedStaffId.value = null
  assignReason.value = ''
  assignVisible.value = true
}

const confirmAssign = async () => {
  if (!selectedStaffId.value) {
    ElMessage.warning('请选择配送员')
    return
  }
  submitting.value = true
  try {
    await deliveryApi.stationAssign(currentOrder.value.id, {
      deliveryStaffId: selectedStaffId.value,
      reason: assignReason.value || (assignMode.value === 'reassign' ? '站长重分配' : '站长分配')
    })
    ElMessage.success('操作成功')
    assignVisible.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

const handleReturn = async (order) => {
  try {
    await ElMessageBox.confirm(
      `确认将订单 #${order.id} 退回待配送池？退回后需重新分配。`,
      '提示',
      { type: 'warning' }
    )
    await deliveryApi.stationReturn(order.id, { reason: '站长退回' })
    ElMessage.success('已退回')
    await loadAll()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '操作失败')
  }
}

onMounted(() => {
  loadStaffs()
  loadAll()
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>