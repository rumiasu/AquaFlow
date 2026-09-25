<template>
  <div class="page-container">
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="6">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-title">总订单数</div>
          <div class="summary-num">{{ overview.totalOrders || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-title">已完成订单</div>
          <div class="summary-num summary-success">{{ overview.finishedOrders || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-title">总销售额</div>
          <div class="summary-num summary-warning">¥{{ Number(overview.totalSales || 0).toFixed(0) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-title">客户总数</div>
          <div class="summary-num summary-primary">{{ overview.customerCount || 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-head">
              <span>商品销量排行</span>
              <el-button size="small" plain @click="loadData">刷新</el-button>
            </div>
          </template>
          <el-table :data="productSales" border stripe size="small" empty-text="暂无数据">
            <el-table-column type="index" label="排名" width="70" align="center" />
            <el-table-column prop="productName" label="商品" min-width="140" />
            <el-table-column prop="totalQuantity" label="销量" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="success" effect="plain">{{ row.totalQuantity || 0 }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalAmount" label="销售额" width="120" align="right">
              <template #default="{ row }">¥{{ Number(row.totalAmount || 0).toFixed(0) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-head">
              <span>订单来源分布</span>
            </div>
          </template>
          <el-table :data="sourceStats" border stripe size="small" empty-text="暂无数据">
            <el-table-column prop="source" label="来源" width="120" />
            <el-table-column prop="count" label="订单数" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.type" effect="plain">{{ row.count || 0 }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="占比" width="140">
              <template #default="{ row }">
                <el-progress :percentage="row.percent || 0" :stroke-width="14" :color="row.color" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>Top 10 客户（按订单数）</span>
        </div>
      </template>
      <el-table :data="topCustomers" border stripe size="small" empty-text="暂无数据">
        <el-table-column type="index" label="排名" width="70" align="center" />
        <el-table-column prop="customerName" label="客户姓名" min-width="120" />
        <el-table-column prop="customerPhone" label="联系电话" width="140" />
        <el-table-column prop="totalOrders" label="订单数" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="warning" effect="dark">{{ row.totalOrders || 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastOrderTime" label="最近下单时间" width="170">
          <template #default="{ row }">{{ row.lastOrderTime || '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { orderApi, customerApi, productApi } from '../../../api'

const overview = ref({ totalOrders: 0, finishedOrders: 0, totalSales: 0, customerCount: 0 })
const productSales = ref([])
const sourceStats = ref([])
const topCustomers = ref([])

const loadData = async () => {
  try {
    const [orders, customers, products] = await Promise.all([
      orderApi.list({}),
      customerApi.list(),
      productApi.list()
    ])
    const orderList = orders || []

    overview.value.totalOrders = orderList.length
    overview.value.finishedOrders = orderList.filter(o => o.status === 3).length
    overview.value.totalSales = orderList
      .filter(o => o.status === 3)
      .reduce((s, o) => s + Number(o.totalAmount || o.amount || 0), 0)
    overview.value.customerCount = (customers || []).length

    const productMap = {}
    orderList.forEach(o => {
      const key = o.productId || o.waterTypeId
      const name = o.productName || o.waterTypeName || '未知商品'
      if (!productMap[key]) productMap[key] = { productName: name, totalQuantity: 0, totalAmount: 0 }
      productMap[key].totalQuantity += Number(o.quantity || 0)
      productMap[key].totalAmount += Number(o.totalAmount || 0)
    })
    productSales.value = Object.values(productMap).sort((a, b) => b.totalQuantity - a.totalQuantity).slice(0, 10)

    const sourceMap = { 1: { name: '电话', type: '', color: '#409eff' }, 2: { name: '微信群', type: 'success', color: '#67c23a' }, 3: { name: '小程序', type: 'warning', color: '#e6a23c' } }
    const sourceCount = { 1: 0, 2: 0, 3: 0 }
    orderList.forEach(o => { if (sourceCount[o.source] !== undefined) sourceCount[o.source]++ })
    const total = orderList.length || 1
    sourceStats.value = Object.keys(sourceMap).map(k => ({
      source: sourceMap[k].name,
      count: sourceCount[k],
      type: sourceMap[k].type,
      color: sourceMap[k].color,
      percent: Math.round((sourceCount[k] / total) * 100)
    }))

    const custMap = {}
    orderList.forEach(o => {
      const cid = o.customerId
      if (!cid) return
      if (!custMap[cid]) custMap[cid] = { customerName: o.customerName, customerPhone: o.customerPhone, totalOrders: 0, lastOrderTime: o.createTime }
      custMap[cid].totalOrders++
      if (o.createTime && o.createTime > custMap[cid].lastOrderTime) custMap[cid].lastOrderTime = o.createTime
    })
    topCustomers.value = Object.values(custMap).sort((a, b) => b.totalOrders - a.totalOrders).slice(0, 10)
  } catch (e) {}
}

onMounted(loadData)
</script>

<style scoped>
.summary-card { text-align: center; }
.summary-title { font-size: 13px; color: var(--text-secondary); margin-bottom: 8px; }
.summary-num { font-size: 30px; font-weight: 700; color: var(--text-primary); }
.summary-success { color: #67c23a; }
.summary-warning { color: #e6a23c; }
.summary-primary { color: #409eff; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
</style>
