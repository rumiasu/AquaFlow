<template>
  <div class="delivery-page page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">我的配送任务</span>
          <el-button type="primary" @click="loadMyBatches">刷新</el-button>
        </div>
      </template>

      <!-- 筛选 -->
      <el-form :inline="true" :model="filter" class="filter-form">
        <el-form-item label="状态">
          <el-select v-model="filter.status" placeholder="全部" clearable style="width: 150px">
            <el-option label="待出发" :value="1" />
            <el-option label="配送中" :value="2" />
            <el-option label="已完成" :value="3" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- 批次列表 -->
      <el-table :data="filteredBatches" v-loading="loading" stripe>
        <el-table-column prop="id" label="批次号" width="100" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalQTY" label="总数量" width="100" />
        <el-table-column label="订单数" width="100">
          <template #default="{ row }">
            {{ row.orders ? row.orders.length : 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="showBatchDetail(row)">查看详情</el-button>
            <el-button v-if="row.status === 1" type="success" size="small" @click="startBatch(row)">出发</el-button>
            <el-button v-if="row.status === 2" type="primary" size="small" @click="finishBatch(row)">完成</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 批次详情对话框 -->
    <el-dialog v-model="detailVisible" title="批次详情" width="800px">
      <div v-if="currentBatch">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="批次号">{{ currentBatch.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(currentBatch.status)">{{ statusText(currentBatch.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总数量">{{ currentBatch.totalQTY }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(currentBatch.createTime) }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 20px 0 10px">订单列表</h4>
        <el-table :data="currentBatch.orders" stripe border>
          <el-table-column prop="id" label="订单号" width="100" />
          <el-table-column prop="receiverName" label="收货人" width="120" />
          <el-table-column prop="receiverPhone" label="电话" width="140" />
          <el-table-column prop="addressSnapshot" label="地址" show-overflow-tooltip />
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small">{{ orderStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 完成批次对话框 -->
    <el-dialog v-model="finishVisible" title="完成配送" width="600px">
      <div v-if="currentBatch">
        <p>请选择已完成的订单：</p>
        <el-checkbox-group v-model="finishedOrderIds">
          <el-checkbox
            v-for="order in currentBatch.orders"
            :key="order.id"
            :label="order.id"
          >
            #{{ order.id }} {{ order.receiverName }} - {{ order.addressSnapshot }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button @click="finishVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmFinish">确认完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchApi } from '../../../api'

const loading = ref(false)
const batches = ref([])
const filter = ref({ status: null })

const detailVisible = ref(false)
const currentBatch = ref(null)

const finishVisible = ref(false)
const finishedOrderIds = ref([])

const filteredBatches = computed(() => {
  if (!filter.value.status) return batches.value
  return batches.value.filter(b => b.status === filter.value.status)
})

const statusText = (status) => {
  const map = { 1: '待出发', 2: '配送中', 3: '已完成' }
  return map[status] || '未知'
}

const statusTagType = (status) => {
  const map = { 1: 'warning', 2: 'primary', 3: 'success' }
  return map[status] || 'info'
}

const orderStatusText = (status) => {
  const map = { 1: '待配送', 2: '配送中', 3: '已完成', 4: '已取消' }
  return map[status] || '未知'
}

const formatTime = (time) => {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 19)
}

const loadMyBatches = async () => {
  loading.value = true
  try {
    const res = await batchApi.getMyBatches()
    batches.value = res.data || []
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const showBatchDetail = (batch) => {
  currentBatch.value = batch
  detailVisible.value = true
}

const startBatch = async (batch) => {
  try {
    await ElMessageBox.confirm('确认出发？出发后批次状态将变为配送中', '提示', { type: 'warning' })
    await batchApi.start(batch.id)
    ElMessage.success('出发成功')
    loadMyBatches()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('操作失败')
  }
}

const finishBatch = (batch) => {
  currentBatch.value = batch
  finishedOrderIds.value = []
  finishVisible.value = true
}

const confirmFinish = async () => {
  if (finishedOrderIds.value.length === 0) {
    ElMessage.warning('请至少选择一个已完成的订单')
    return
  }
  try {
    const unfinishedOrderIds = currentBatch.value.orders
      .map(o => o.id)
      .filter(id => !finishedOrderIds.value.includes(id))
    await batchApi.finish(currentBatch.id, finishedOrderIds.value, unfinishedOrderIds)
    ElMessage.success('完成成功')
    finishVisible.value = false
    loadMyBatches()
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

onMounted(() => {
  loadMyBatches()
})
</script>

<style scoped>
/* 全局 theme.css 已接管，保留页面补充 */
.filter-form {
  margin-bottom: 16px;
}
</style>
