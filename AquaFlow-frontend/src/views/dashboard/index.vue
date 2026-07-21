<template>
  <div class="dashboard">
    <!-- ===== 一级：今日概况 ===== -->
    <div class="top-bar">
      <div class="top-item">
        <div class="top-num" style="color: #E6A23C;">{{ today.pendingBatches || 0 }}</div>
        <div class="top-label">待装车</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num" style="color: #409EFF;">{{ today.deliveringBatches || 0 }}</div>
        <div class="top-label">装车中</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num" style="color: #67C23A;">{{ today.finishedBatches || 0 }}</div>
        <div class="top-label">已完成</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num">{{ today.pendingOrders || 0 }}</div>
        <div class="top-label">待组批订单</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num" style="color: #F56C6C;">{{ today.lowStock || 0 }}</div>
        <div class="top-label">库存预警</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num" style="color: #E6A23C;">{{ today.totalBucketsOwed || 0 }}</div>
        <div class="top-label">客户欠桶</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num" style="color: #F56C6C;">{{ today.unpaidOrders || 0 }}</div>
        <div class="top-label">待付款</div>
      </div>
      <div class="top-divider"></div>
      <div class="top-item">
        <div class="top-num" style="color: #909399;">{{ today.enterprisePendingCount || 0 }}</div>
        <div class="top-label">企业待结算</div>
      </div>
    </div>

    <!-- ===== 核心：待装车批次 ===== -->
    <div class="action-section">
      <el-button class="big-btn" type="primary" size="large" round @click="goCreateBatch">
        <el-icon :size="18"><Box /></el-icon> 去装车
      </el-button>
    </div>

    <!-- 待装车批次列表 -->
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>待装车 ({{ pendingList.length }})</span>
              <el-button size="small" text type="primary" @click="loadData">刷新</el-button>
            </div>
          </template>
          <div v-if="pendingList.length === 0" style="text-align: center; padding: 30px; color: var(--text-secondary);">
            暂无待装车批次
          </div>
          <div v-else class="batch-list">
            <div v-for="b in pendingList" :key="b.id" class="batch-item">
              <div style="flex: 1;">
                <div style="font-weight: 500;">批次 #{{ b.id }}</div>
                <div style="font-size: 12px; color: var(--text-secondary);">
                  {{ b.totalQty || b.totalQTY }} 件 · {{ formatTime(b.createTime) }}
                </div>
              </div>
              <div style="display: flex; gap: 6px;">
                <el-button size="small" text @click="showDetail(b.id)">详情</el-button>
                <el-button size="small" type="primary" text @click="goToMap(b.id)">地图</el-button>
                <el-button size="small" type="warning" text @click="startBatch(b.id)">装车</el-button>
                <el-button size="small" type="danger" text @click="deleteBatch(b.id)">删除</el-button>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span>装车中 ({{ deliveringList.length }})</span></template>
          <div v-if="deliveringList.length === 0" style="text-align: center; padding: 30px; color: var(--text-secondary);">
            暂无装车中批次
          </div>
          <div v-else class="batch-list">
            <div v-for="b in deliveringList" :key="b.id" class="batch-item">
              <div style="flex: 1;">
                <div style="font-weight: 500;">批次 #{{ b.id }}</div>
                <div style="font-size: 12px; color: var(--text-secondary);">
                  {{ b.totalQty || b.totalQTY }} 件 · {{ formatTime(b.createTime) }}
                </div>
              </div>
              <div style="display: flex; gap: 6px;">
                <el-button size="small" text @click="showDetail(b.id)">详情</el-button>
                <el-button size="small" type="success" text @click="showFinish(b)">完成</el-button>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ===== 三级：经营数据 ===== -->
    <div class="boss-section">
      <div class="section-title">经营数据</div>
    </div>
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #409EFF;"><el-icon :size="20" color="#fff"><User /></el-icon></div>
          <div class="stat-label">客户总数</div>
          <div class="stat-value">{{ overview.customerCount || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #67C23A;"><el-icon :size="20" color="#fff"><Box /></el-icon></div>
          <div class="stat-label">库存总量</div>
          <div class="stat-value">{{ overview.inventoryTotal || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #E6A23C;"><el-icon :size="20" color="#fff"><Document /></el-icon></div>
          <div class="stat-label">历史总单</div>
          <div class="stat-value">{{ overview.orderCount || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #F56C6C;"><el-icon :size="20" color="#fff"><Tickets /></el-icon></div>
          <div class="stat-label">历史批次</div>
          <div class="stat-value">{{ overview.batchCount || 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="8">
        <el-card>
          <template #header><span>近7天订单趋势</span></template>
          <div ref="trendChartRef" style="height: 260px;"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><span>库存概览</span></template>
          <div ref="inventoryChartRef" style="height: 260px;"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><span>Top 5 客户</span></template>
          <div ref="customerChartRef" style="height: 260px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 批次详情 -->
    <el-dialog v-model="detailVisible" title="批次详情" width="700px">
      <el-table :data="detailOrders" border stripe max-height="400">
        <el-table-column prop="id" label="订单ID" width="80" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="addressDetail" label="地址" show-overflow-tooltip />
        <el-table-column prop="waterTypeName" label="水类型" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="['','warning','','success','info'][row.status]">{{ { 1: '待组批', 2: '配送中', 3: '已完成', 4: '已组批' }[row.status] }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 完成配送 -->
    <el-dialog v-model="finishVisible" title="完成配送" width="500px">
      <p style="color: var(--text-secondary);">请勾选已完成的订单：</p>
      <el-checkbox-group v-model="finishedIds">
        <div v-for="o in finishBatchOrders" :key="o.id" style="margin: 8px 0; padding: 6px; border-radius: 4px; background: var(--bg-input);">
          <el-checkbox :label="o.id">{{ o.customerName }} - {{ o.waterTypeName }} x{{ o.quantity }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="finishVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFinish">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { User, Box, Document, Tickets } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { dashboardApi, batchApi, inventoryApi } from '../../api'

const router = useRouter()
const today = ref({})
const overview = ref({})
const pendingList = ref([])
const deliveringList = ref([])
const trendChartRef = ref(null)
const inventoryChartRef = ref(null)
const customerChartRef = ref(null)
let charts = []

const detailVisible = ref(false)
const detailOrders = ref([])

const finishVisible = ref(false)
const finishBatchId = ref(null)
const finishBatchOrders = ref([])
const finishedIds = ref([])

const formatTime = (t) => {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const loadData = async () => {
  const [todayData, pending, delivering, overviewData, inventoryData, trendData, topCustomers] = await Promise.all([
    dashboardApi.today(),
    dashboardApi.pendingBatches(),
    dashboardApi.deliveringBatches(),
    dashboardApi.overview(),
    inventoryApi.list(),
    dashboardApi.orderTrend(),
    dashboardApi.topCustomers()
  ])
  today.value = todayData
  pendingList.value = pending
  deliveringList.value = delivering
  overview.value = overviewData

  await nextTick()
  initCharts(trendData, inventoryData, topCustomers)
}

const initCharts = (trendData, inventoryData, topCustomers) => {
  charts.forEach(c => c?.dispose()); charts = []

  const tc = echarts.init(trendChartRef.value); charts.push(tc)
  tc.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 10, bottom: 24, top: 10 },
    xAxis: { type: 'category', data: trendData.map(d => d.date?.slice(5)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'line', data: trendData.map(d => d.count), smooth: true, areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(64,158,255,0.3)' }, { offset: 1, color: 'rgba(64,158,255,0.02)' }] } }, lineStyle: { color: '#409EFF', width: 2 }, itemStyle: { color: '#409EFF' } }]
  })

  const ic = echarts.init(inventoryChartRef.value); charts.push(ic)
  ic.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 10, bottom: 40, top: 10 },
    xAxis: { type: 'category', data: inventoryData.map(d => d.waterTypeName), axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{ type: 'bar', data: inventoryData.map(d => ({ value: d.quantity, itemStyle: { color: d.quantity < 20 ? '#F56C6C' : '#409EFF', borderRadius: [3, 3, 0, 0] } })), barMaxWidth: 30 }]
  })

  const cc = echarts.init(customerChartRef.value); charts.push(cc)
  const reversed = [...topCustomers].slice(0, 5).reverse()
  cc.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 20, bottom: 10, top: 10 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: reversed.map(d => d.customerName) },
    series: [{ type: 'bar', data: reversed.map(d => d.totalQuantity), itemStyle: { borderRadius: [0, 3, 3, 0], color: '#67C23A' }, barMaxWidth: 20 }]
  })
}

const goCreateBatch = () => router.push('/address-map?mode=order')
const goToMap = (id) => router.push(`/address-map?mode=order&batchId=${id}`)

const showDetail = async (id) => {
  const batch = await batchApi.getById(id)
  detailOrders.value = batch.orders || []
  detailVisible.value = true
}

const startBatch = async (id) => {
  await ElMessageBox.confirm('确定开始装车？', '确认')
  await batchApi.start(id)
  ElMessage.success('已开始装车')
  loadData()
}

const deleteBatch = async (id) => {
  await ElMessageBox.confirm('确定删除该批次？', '确认')
  await batchApi.delete(id)
  ElMessage.success('已删除')
  loadData()
}

const showFinish = async (row) => {
  const batch = await batchApi.getById(row.id)
  finishBatchId.value = row.id
  finishBatchOrders.value = batch.orders || []
  finishedIds.value = []
  finishVisible.value = true
}

const submitFinish = async () => {
  const allIds = finishBatchOrders.value.map(o => o.id)
  const unfinishedIds = allIds.filter(id => !finishedIds.value.includes(id))
  await batchApi.finish(finishBatchId.value, { finishedOrderIds: finishedIds.value, unfinishedOrderIds: unfinishedIds })
  ElMessage.success('配送完成')
  finishVisible.value = false
  loadData()
}

const handleResize = () => charts.forEach(c => c?.resize())

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  charts.forEach(c => c?.dispose())
})
</script>

<style scoped>
.dashboard { max-width: 1400px; margin: 0 auto; }

.top-bar {
  display: flex; align-items: center; justify-content: center;
  background: var(--bg-card); border-radius: 12px; padding: 20px 40px;
  margin-bottom: 16px; box-shadow: var(--shadow-card);
}
.top-item { text-align: center; padding: 0 24px; }
.top-num { font-size: 32px; font-weight: bold; color: var(--text-primary); }
.top-label { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.top-divider { width: 1px; height: 40px; background: var(--border-light); }

.action-section { text-align: center; margin-bottom: 20px; }
.big-btn {
  min-width: 200px; height: 56px; font-size: 20px; font-weight: bold;
  background: linear-gradient(135deg, #409EFF 0%, #337ecc 100%);
  border: none; box-shadow: 0 6px 24px rgba(64,158,255,0.4);
  transition: all 0.25s ease;
}
.big-btn:hover {
  transform: translateY(-2px) scale(1.03);
  box-shadow: 0 8px 32px rgba(64,158,255,0.5);
  background: linear-gradient(135deg, #66b1ff 0%, #409EFF 100%);
}
.big-btn:active { transform: translateY(0) scale(0.98); }

.batch-list { max-height: 280px; overflow-y: auto; }
.batch-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 0; border-bottom: 1px solid var(--border-light);
}
.batch-item:last-child { border-bottom: none; }

.boss-section { margin: 20px 0 12px; }
.section-title { font-size: 16px; font-weight: 600; color: var(--text-primary); padding-left: 10px; border-left: 3px solid #409EFF; }

.stat-card { text-align: center; }
.stat-card .stat-icon { display: inline-flex; border-radius: 10px; padding: 10px; margin-bottom: 8px; }
.stat-card .stat-label { font-size: 12px; color: var(--text-secondary); }
.stat-card .stat-value { font-size: 24px; font-weight: bold; color: var(--text-primary); margin-top: 2px; }
</style>
