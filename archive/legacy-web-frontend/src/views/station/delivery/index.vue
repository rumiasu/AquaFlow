<template>
  <div class="delivery-page page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">我的配送任务</span>
          <el-button type="primary" @click="loadMyDeliveries">刷新</el-button>
        </div>
      </template>

      <el-form :inline="true" :model="filter" class="filter-form">
        <el-form-item label="状态">
          <el-select v-model="filter.status" placeholder="全部" clearable style="width: 150px">
            <el-option label="待配送" :value="1" />
            <el-option label="配送中" :value="2" />
            <el-option label="已送达" :value="3" />
            <el-option label="已完成" :value="4" />
            <el-option label="已取消" :value="5" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-table :data="filteredOrders" v-loading="loading" stripe>
        <el-table-column prop="id" label="订单号" width="100" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="商品数量" width="100">
          <template #default="{ row }">{{ row.productName || row.waterTypeName }} × {{ row.quantity }}</template>
        </el-table-column>
        <el-table-column prop="receiverName" label="收货人" width="120" />
        <el-table-column prop="receiverPhone" label="电话" width="140" />
        <el-table-column prop="addressSnapshot" label="地址" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button size="small" @click="showOrderDetail(row)">详情</el-button>
            <el-button v-if="row.status === 1" type="primary" size="small" @click="startDelivery(row)">开始配送</el-button>
            <el-button v-if="row.status === 2" type="success" size="small" @click="finishDelivery(row)">确认送达</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" title="订单详情" width="640px" destroy-on-close>
      <div v-if="currentOrder">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单号">#{{ currentOrder.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(currentOrder.status)">{{ statusText(currentOrder.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="商品">{{ currentOrder.productName || currentOrder.waterTypeName }} × {{ currentOrder.quantity }} 桶</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(currentOrder.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="收货人" :span="1">{{ currentOrder.receiverName }}</el-descriptions-item>
          <el-descriptions-item label="联系电话" :span="1">{{ currentOrder.receiverPhone }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="2">{{ currentOrder.addressSnapshot }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ currentOrder.specialNote || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="currentOrder && currentOrder.status === 1" type="primary" @click="startDelivery(currentOrder)">开始配送</el-button>
        <el-button v-if="currentOrder && currentOrder.status === 2" type="success" @click="finishDelivery(currentOrder)">确认送达</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deliveryApi } from '../../../api'

const loading = ref(false)
const orders = ref([])
const filter = ref({ status: null })

const detailVisible = ref(false)
const currentOrder = ref(null)

const filteredOrders = computed(() => {
  if (!filter.value.status) return orders.value
  return orders.value.filter(o => o.status === filter.value.status)
})

const statusText = (status) => {
  const map = { 1: '待配送', 2: '配送中', 3: '已送达', 4: '已完成', 5: '已取消' }
  return map[status] || '未知'
}

const statusTagType = (status) => {
  const map = { 1: 'warning', 2: 'primary', 3: 'success', 4: 'info', 5: 'danger' }
  return map[status] || 'info'
}

const formatTime = (time) => {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 19)
}

const loadMyDeliveries = async () => {
  loading.value = true
  try {
    const res = await deliveryApi.getMyDeliveries()
    orders.value = res || res.data || []
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const showOrderDetail = (order) => {
  currentOrder.value = order
  detailVisible.value = true
}

const startDelivery = async (order) => {
  try {
    await ElMessageBox.confirm('确认开始配送该订单？', '提示', { type: 'warning' })
    ElMessage.success('已开始配送')
    loadMyDeliveries()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('操作失败')
  }
}

const finishDelivery = async (order) => {
  try {
    await ElMessageBox.confirm('确认已将商品送达客户？确认后将进入收款流程。', '提示', { type: 'warning' })
    await deliveryApi.confirmCollection(order.id)
    ElMessage.success('配送完成')
    detailVisible.value = false
    loadMyDeliveries()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('操作失败')
  }
}

onMounted(() => {
  loadMyDeliveries()
})
</script>

<style scoped>
.filter-form { margin-bottom: 16px; }
</style>
