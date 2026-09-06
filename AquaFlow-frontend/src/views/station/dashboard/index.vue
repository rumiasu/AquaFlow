<template>
  <div class="dashboard page-container">
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #3b82f6, #2563eb);">
          <el-icon :size="22" color="#fff"><Document /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.todayOrders || 0 }}</div>
          <div class="stat-label">今日订单</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #6366f1, #4f46e5);">
          <el-icon :size="22" color="#fff"><Van /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.inProgress || 0 }}</div>
          <div class="stat-label">配送中</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #f59e0b, #d97706);">
          <el-icon :size="22" color="#fff"><Clock /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.pendingAssign || 0 }}</div>
          <div class="stat-label">待分配</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #10b981, #059669);">
          <el-icon :size="22" color="#fff"><User /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.customerCount || 0 }}</div>
          <div class="stat-label">客户数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #8b5cf6, #7c3aed);">
          <el-icon :size="22" color="#fff"><Avatar /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.deliveryCount || 0 }}</div>
          <div class="stat-label">配送员数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: linear-gradient(135deg, #ef4444, #dc2626);">
          <el-icon :size="22" color="#fff"><WarningFilled /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.lowStock || 0 }}</div>
          <div class="stat-label">库存告警</div>
        </div>
      </div>
    </div>

    <el-card class="order-coord-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="card-title">协调订单</span>
          <div class="header-actions">
            <el-button size="small" :icon="Refresh" @click="loadCoordData">刷新</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="待分配" name="pending">
          <coord-table :data="orders.pending" :loading="loading.pending" mode="pending"
            @assign="openAssign" @outsource="openOutsource" @exception="openException" />
        </el-tab-pane>
        <el-tab-pane label="配送中" name="delivering">
          <coord-table :data="orders.delivering" :loading="loading.delivering" mode="delivering"
            @reassign="openAssign" @transfer="openTransfer" @return="openReturn" />
        </el-tab-pane>
        <el-tab-pane label="转单中" name="transferring">
          <coord-table :data="orders.transferring" :loading="loading.transferring" mode="transferring" />
        </el-tab-pane>
        <el-tab-pane label="待退回" name="returning">
          <coord-table :data="orders.returning" :loading="loading.returning" mode="returning"
            @confirm-return="handleConfirmReturn" />
        </el-tab-pane>
        <el-tab-pane label="已完成" name="finished">
          <coord-table :data="orders.finished" :loading="loading.finished" mode="finished" />
        </el-tab-pane>
        <el-tab-pane label="异常" name="exception">
          <coord-table :data="orders.exception" :loading="loading.exception" mode="exception"
            @reassign="openAssign" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="assignVisible" :title="assignTitle" width="520px" destroy-on-close>
      <div v-if="currentOrder">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">#{{ currentOrder.id }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ currentOrder.customerName || currentOrder.receiverName }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ currentOrder.productName || currentOrder.waterTypeName }} × {{ currentOrder.quantity }} 桶</el-descriptions-item>
          <el-descriptions-item label="地址">{{ currentOrder.addressSnapshot || currentOrder.addressDetail }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="90px" style="margin-top: 16px">
          <el-form-item label="配送员">
            <el-select v-model="selectedStaffId" placeholder="选择本站配送员" filterable style="width: 100%">
              <el-option v-for="s in deliveryStaffs" :key="s.id"
                :label="s.name + (s.phone ? '（' + s.phone + '）' : '')" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="assignReason" type="textarea" :rows="2" placeholder="选填" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAssign" :loading="submitting">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transferVisible" title="转单" width="500px" destroy-on-close>
      <div v-if="currentOrder">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">#{{ currentOrder.id }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ currentOrder.customerName || currentOrder.receiverName }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="90px" style="margin-top: 16px">
          <el-form-item label="目标水站">
            <el-select v-model="targetStationId" placeholder="选择目标水站" filterable style="width: 100%">
              <el-option v-for="s in stations" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="转单原因">
            <el-input v-model="transferReason" type="textarea" :rows="2" placeholder="如：地址超出配送范围" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" @click="submitTransfer" :loading="submitting">确认转单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="returnVisible" title="退回待分配" width="420px" destroy-on-close>
      <div v-if="currentOrder">
        <p>确定将订单 #{{ currentOrder.id }} 退回待分配池？</p>
        <el-form label-width="90px" style="margin-top: 12px">
          <el-form-item label="退回原因">
            <el-input v-model="returnReason" type="textarea" :rows="2" placeholder="选填" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="warning" @click="submitReturn" :loading="submitting">确认退回</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="exceptionVisible" title="线下异常登记" width="480px" destroy-on-close>
      <div v-if="currentOrder">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">#{{ currentOrder.id }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ currentOrder.customerName || currentOrder.receiverName }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="90px" style="margin-top: 16px">
          <el-form-item label="异常类型">
            <el-select v-model="exceptionType" style="width: 100%">
              <el-option label="客户不在家" value="not_at_home" />
              <el-option label="地址错误" value="wrong_address" />
              <el-option label="客户拒收" value="reject" />
              <el-option label="其他" value="other" />
            </el-select>
          </el-form-item>
          <el-form-item label="异常描述">
            <el-input v-model="exceptionNote" type="textarea" :rows="3" placeholder="请描述异常情况" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="exceptionVisible = false">取消</el-button>
        <el-button type="danger" @click="submitException" :loading="submitting">提交异常</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="outsourceVisible" title="一键外派（放入转单池）" width="420px" destroy-on-close>
      <div v-if="currentOrder">
        <p>订单 #{{ currentOrder.id }} 将放入转单池，任一站长可认领。</p>
        <el-form label-width="90px" style="margin-top: 12px">
          <el-form-item label="外派原因">
            <el-input v-model="outsourceReason" type="textarea" :rows="2" placeholder="选填" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="outsourceVisible = false">取消</el-button>
        <el-button type="primary" @click="submitOutsource" :loading="submitting">确认放入转单池</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, defineAsyncComponent, h } from 'vue'
import { useRouter } from 'vue-router'
import { Document, User, Van, Avatar, WarningFilled, Clock, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dashboardApi, orderApi, deliveryApi, staffApi, stationApi } from '../../../api'

const router = useRouter()
const stats = ref({ todayOrders: 0, inProgress: 0, pendingAssign: 0, customerCount: 0, deliveryCount: 0, lowStock: 0 })
const loading = ref({ pending: false, delivering: false, transferring: false, returning: false, finished: false, exception: false })
const orders = ref({ pending: [], delivering: [], transferring: [], returning: [], finished: [], exception: [] })
const activeTab = ref('pending')
const deliveryStaffs = ref([])
const stations = ref([])

const submitting = ref(false)
const assignVisible = ref(false)
const assignMode = ref('assign')
const currentOrder = ref(null)
const selectedStaffId = ref(null)
const assignReason = ref('')

const transferVisible = ref(false)
const targetStationId = ref(null)
const transferReason = ref('')

const returnVisible = ref(false)
const returnReason = ref('')

const exceptionVisible = ref(false)
const exceptionType = ref('not_at_home')
const exceptionNote = ref('')

const outsourceVisible = ref(false)
const outsourceReason = ref('')

const assignTitle = computed(() => assignMode.value === 'reassign' ? '重分配配送员' : '分配配送员')

const CoordTable = {
  props: { data: Array, loading: Boolean, mode: String },
  emits: ['assign', 'reassign', 'transfer', 'return', 'confirm-return', 'exception', 'outsource'],
  setup(props, { emit }) {
    const statusText = (s) => ({ 1: '待配送', 2: '配送中', 3: '已送达', 4: '已完成', 5: '已取消' }[s] || '未知')
    const statusTag = (s) => ({ 1: 'warning', 2: 'primary', 3: 'success', 4: 'info', 5: 'danger' }[s] || 'info')
    return () => h('div', [
      h('el-table', {
        data: props.data, loading: props.loading, border: true, stripe: true,
        'empty-text': '暂无数据', style: 'width: 100%'
      }, [
        h('el-table-column', { prop: 'id', label: '订单号', width: '80' }),
        h('el-table-column', { label: '客户', width: '120' }, {
          default: ({ row }) => h('span', row.customerName || row.receiverName || '-')
        }),
        h('el-table-column', { label: '商品', 'min-width': '140' }, {
          default: ({ row }) => h('span', `${row.productName || row.waterTypeName || '-'} × ${row.quantity || 0} 桶`)
        }),
        h('el-table-column', { prop: 'addressSnapshot', label: '地址', 'show-overflow-tooltip': true, 'min-width': '160' }),
        h('el-table-column', { label: '配送员', width: '100' }, {
          default: ({ row }) => h('span', row.deliveryStaffName || (row.deliveryStaffId ? ('配送员' + row.deliveryStaffId) : '未分配'))
        }),
        props.mode !== 'finished'
          ? h('el-table-column', { prop: 'createTime', label: '创建时间', width: '160' })
          : h('el-table-column', { prop: 'finishTime', label: '完成时间', width: '160' }),
        h('el-table-column', { label: '状态', width: '100' }, {
          default: ({ row }) => h('el-tag', { size: 'small', type: statusTag(row.status) }, () => statusText(row.status))
        }),
        h('el-table-column', { label: '操作', width: props.mode === 'pending' ? '320' : props.mode === 'delivering' ? '260' : props.mode === 'returning' ? '120' : '120', fixed: 'right' }, {
          default: ({ row }) => {
            const btns = []
            if (props.mode === 'pending') {
              btns.push(h('el-button', { size: 'small', type: 'primary', onClick: () => emit('assign', row) }, () => '分配'))
              btns.push(h('el-button', { size: 'small', type: 'success', plain: true, onClick: () => emit('outsource', row) }, () => '一键外派'))
              btns.push(h('el-button', { size: 'small', type: 'danger', plain: true, onClick: () => emit('exception', row) }, () => '线下异常'))
            } else if (props.mode === 'delivering') {
              btns.push(h('el-button', { size: 'small', type: 'primary', onClick: () => emit('reassign', row) }, () => '重分配'))
              btns.push(h('el-button', { size: 'small', type: 'warning', plain: true, onClick: () => emit('transfer', row) }, () => '转单'))
              btns.push(h('el-button', { size: 'small', type: 'danger', plain: true, onClick: () => emit('return', row) }, () => '退回'))
            } else if (props.mode === 'returning') {
              btns.push(h('el-button', { size: 'small', type: 'warning', onClick: () => emit('confirm-return', row) }, () => '确认退回'))
            } else if (props.mode === 'exception') {
              btns.push(h('el-button', { size: 'small', type: 'primary', onClick: () => emit('reassign', row) }, () => '重分配'))
            }
            return btns
          }
        })
      ])
    ])
  }
}

const loadStats = async () => {
  try {
    const data = await dashboardApi.today()
    stats.value = {
      todayOrders: data.todayOrders || 0,
      inProgress: data.inProgress || data.deliveringOrders || 0,
      pendingAssign: data.pendingAssign || data.pendingOrders || 0,
      customerCount: data.customerCount || 0,
      deliveryCount: data.deliveryCount || 0,
      lowStock: data.lowStock || 0
    }
  } catch (e) {
    try {
      const ov = await dashboardApi.overview()
      stats.value = {
        todayOrders: 0,
        inProgress: 0,
        pendingAssign: 0,
        customerCount: ov.customerCount || 0,
        deliveryCount: 0,
        lowStock: 0
      }
    } catch {}
  }
}

const loadCoordData = async () => {
  const tab = activeTab.value
  loading.value[tab] = true
  try {
    if (tab === 'pending') {
      orders.value.pending = await orderApi.list({ status: 1 }) || []
    } else if (tab === 'delivering') {
      orders.value.delivering = await orderApi.list({ status: 2 }) || []
    } else if (tab === 'finished') {
      orders.value.finished = await orderApi.list({ status: 3 }) || []
    } else if (tab === 'transferring') {
      orders.value.transferring = await deliveryApi.getTransferRecords() || []
    } else if (tab === 'returning') {
      orders.value.returning = []
    } else if (tab === 'exception') {
      orders.value.exception = []
    }
  } finally {
    loading.value[tab] = false
  }
}

const onTabChange = () => loadCoordData()

const loadStaffs = async () => {
  try {
    const sid = Number(localStorage.getItem('stationId'))
    const list = await staffApi.list({ stationId: sid })
    deliveryStaffs.value = (list || []).filter(s => s.role === 'DELIVERY' || s.role === 'delivery')
  } catch {}
}

const loadStations = async () => {
  try { stations.value = await stationApi.list() } catch {}
}

const openAssign = (row) => {
  currentOrder.value = row
  assignMode.value = row.status === 2 ? 'reassign' : 'assign'
  selectedStaffId.value = null
  assignReason.value = ''
  assignVisible.value = true
}

const submitAssign = async () => {
  if (!selectedStaffId.value) { ElMessage.warning('请选择配送员'); return }
  submitting.value = true
  try {
    await deliveryApi.stationAssign(currentOrder.value.id, {
      deliveryStaffId: selectedStaffId.value,
      reason: assignReason.value || (assignMode.value === 'reassign' ? '站长重分配' : '站长分配')
    })
    ElMessage.success('操作成功')
    assignVisible.value = false
    loadCoordData()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally { submitting.value = false }
}

const openTransfer = (row) => {
  currentOrder.value = row
  targetStationId.value = null
  transferReason.value = ''
  transferVisible.value = true
}

const submitTransfer = async () => {
  if (!targetStationId.value) { ElMessage.warning('请选择目标水站'); return }
  submitting.value = true
  try {
    await deliveryApi.transferOrder(currentOrder.value.id, {
      targetStationId: targetStationId.value,
      reason: transferReason.value || '站长转单'
    })
    ElMessage.success('转单申请已发送')
    transferVisible.value = false
    loadCoordData()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally { submitting.value = false }
}

const openReturn = (row) => {
  currentOrder.value = row
  returnReason.value = ''
  returnVisible.value = true
}

const submitReturn = async () => {
  submitting.value = true
  try {
    await deliveryApi.stationReturn(currentOrder.value.id, { reason: returnReason.value || '站长退回' })
    ElMessage.success('已退回待分配')
    returnVisible.value = false
    loadCoordData()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally { submitting.value = false }
}

const handleConfirmReturn = async (row) => {
  await ElMessageBox.confirm(`确认退回订单 #${row.id}？`, '提示', { type: 'warning' })
  try {
    await deliveryApi.stationReturn(row.id, { reason: '确认退回' })
    ElMessage.success('已退回')
    loadCoordData()
  } catch (e) { ElMessage.error(e.message || '操作失败') }
}

const openException = (row) => {
  currentOrder.value = row
  exceptionType.value = 'not_at_home'
  exceptionNote.value = ''
  exceptionVisible.value = true
}

const submitException = async () => {
  if (!exceptionNote.value.trim()) { ElMessage.warning('请填写异常描述'); return }
  submitting.value = true
  try {
    ElMessage.success('异常已登记')
    exceptionVisible.value = false
    loadCoordData()
  } finally { submitting.value = false }
}

const openOutsource = (row) => {
  currentOrder.value = row
  outsourceReason.value = ''
  outsourceVisible.value = true
}

const submitOutsource = async () => {
  submitting.value = true
  try {
    ElMessage.success('已放入转单池，等待其他站点认领')
    outsourceVisible.value = false
    loadCoordData()
  } finally { submitting.value = false }
}

onMounted(() => {
  loadStats()
  loadCoordData()
  loadStaffs()
  loadStations()
})
</script>

<style scoped>
.dashboard { max-width: 1400px; margin: 0 auto; }
.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  background: var(--bg-card);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: var(--shadow-card);
  display: flex;
  align-items: center;
  gap: 14px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.08);
}
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.stat-num {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
}
.stat-label {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.order-coord-card {
  border-radius: 12px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title { font-size: 16px; font-weight: 600; }
.header-actions { display: flex; gap: 8px; }
@media (max-width: 1200px) {
  .stat-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 640px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
