<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="page-header">
          <span class="page-title">支付管理</span>
          <div class="header-actions">
            <el-select v-model="query.status" placeholder="付款状态" clearable style="width: 130px;">
              <el-option label="待确认" :value="1" />
              <el-option label="已付款" :value="2" />
              <el-option label="已退款" :value="3" />
              <el-option label="已取消" :value="4" />
            </el-select>
            <el-select v-model="query.method" placeholder="支付方式" clearable style="width: 120px;">
              <el-option label="微信" :value="1" />
              <el-option label="现金" :value="2" />
              <el-option label="水票" :value="3" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="loadData">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading" empty-text="暂无支付记录">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="orderId" label="订单ID" width="80" align="center" />
        <el-table-column prop="amount" label="金额" width="100" align="right">
          <template #default="{ row }">
            <span class="text-money">¥{{ Number(row.amount).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="method" label="支付方式" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="methodTagType(row.method)" effect="plain">{{ methodText(row.method) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)" effect="dark">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="transactionId" label="交易号" min-width="180" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="155" />
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" size="small" type="success" plain @click="handleConfirm(row)">确认</el-button>
            <el-button v-if="row.status === 1" size="small" type="warning" plain @click="handleCash(row)">现金</el-button>
            <el-button v-if="row.status === 2" size="small" type="danger" plain @click="handleRefund(row)">退款</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { paymentApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const query = ref({ status: '', method: '' })

const statusText = (s) => ({ 1: '待确认', 2: '已付款', 3: '已退款', 4: '已取消' }[s] || '未知')
const statusTagType = (s) => ({ 1: 'warning', 2: 'success', 3: 'info', 4: 'danger' }[s] || 'info')
const methodText = (m) => ({ 1: '微信', 2: '现金', 3: '水票' }[m] || '其他')
const methodTagType = (m) => ({ 1: 'success', 2: 'warning', 3: '' }[m] || 'info')

const loadData = async () => {
  loading.value = true
  try {
    const params = { ...query.value }
    if (params.status === '' || params.status === null) delete params.status
    if (params.method === '' || params.method === null) delete params.method
    list.value = await paymentApi.list(params)
  } finally { loading.value = false }
}

const handleConfirm = async (row) => {
  try {
    await ElMessageBox.confirm(`确认支付记录 #${row.id} 已到账？`, '确认收款', { type: 'info' })
    await paymentApi.confirm(row.id)
    ElMessage.success('已确认收款')
    loadData()
  } catch (e) { if (e !== 'cancel') ElMessage.error('操作失败') }
}

const handleCash = async (row) => {
  try {
    await ElMessageBox.confirm(`确认支付记录 #${row.id} 为现金收款？`, '现金收款', { type: 'warning' })
    await paymentApi.cashConfirm(row.id)
    ElMessage.success('已确认现金收款')
    loadData()
  } catch (e) { if (e !== 'cancel') ElMessage.error('操作失败') }
}

const handleRefund = async (row) => {
  try {
    await ElMessageBox.confirm(`确定退款支付记录 #${row.id}？退款将原路返回。`, '退款确认', { type: 'warning' })
    await paymentApi.refund(row.id)
    ElMessage.success('退款成功')
    loadData()
  } catch (e) { if (e !== 'cancel') ElMessage.error('退款失败') }
}

onMounted(loadData)
</script>

<style scoped>
/* 全局 theme.css 已接管 */
</style>
