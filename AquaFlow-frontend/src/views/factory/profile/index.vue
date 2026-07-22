<template>
  <div class="station-profile page-container" v-loading="loading">
    <el-card v-if="profile" style="margin-bottom: 16px;">
      <div class="profile-header">
        <div class="profile-info">
          <h2>{{ profile.station?.name }}</h2>
          <p>站长：{{ profile.station?.manager }} | 电话：{{ profile.station?.phone }}</p>
          <p>地址：{{ profile.station?.address }}</p>
          <el-tag :type="profile.station?.status === 1 ? 'success' : 'danger'" size="small">{{ profile.station?.status === 1 ? '营业' : '停业' }}</el-tag>
        </div>
        <div class="profile-stats">
          <div class="pstat"><div class="pstat-num text-primary">{{ profile.todayOrders || 0 }}</div><div class="pstat-label">今日订单</div></div>
          <div class="pstat"><div class="pstat-num text-success">{{ profile.totalOrders || 0 }}</div><div class="pstat-label">累计订单</div></div>
          <div class="pstat"><div class="pstat-num text-warning">{{ profile.totalCustomers || 0 }}</div><div class="pstat-label">客户总数</div></div>
          <div class="pstat"><div class="pstat-num text-success">{{ profile.activeCustomers || 0 }}</div><div class="pstat-label">活跃客户</div></div>
          <div class="pstat"><div class="pstat-num text-danger">{{ profile.churnedCustomers || 0 }}</div><div class="pstat-label">流失客户</div></div>
        </div>
      </div>
    </el-card>
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="16">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span class="page-title" style="font-size: 15px;">销量趋势</span>
              <el-radio-group v-model="trendPeriod" size="small" @change="loadTrend">
                <el-radio-button value="month">近30天</el-radio-button>
                <el-radio-button value="year">近一年</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div ref="trendChartRef" style="height: 300px;"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><span>客户统计</span></template>
          <div ref="customerChartRef" style="height: 300px;"></div>
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header><span>库存周转</span></template>
          <el-table :data="inventoryTurnover" border stripe size="small">
            <el-table-column prop="waterTypeName" label="水类型" />
            <el-table-column prop="currentStock" label="当前库存" width="100" />
            <el-table-column prop="turnoverDays" label="周转天数" width="100">
              <template #default="{ row }">{{ row.turnoverDays >= 0 ? row.turnoverDays + '天' : '-' }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span>回款速度</span></template>
          <div style="text-align: center; padding: 30px;">
            <div class="payment-stat"><div class="payment-num">{{ profile?.totalOrders || 0 }}</div><div class="payment-label">累计订单</div></div>
            <div class="payment-stat"><div class="payment-num">{{ paymentSpeed?.avgCycleDays || 7 }}天</div><div class="payment-label">平均回款周期</div></div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { factoryOpsApi } from '../../../api'

const route = useRoute()
const stationId = route.params.id
const loading = ref(false)
const profile = ref(null)
const trendPeriod = ref('month')
const trendData = ref([])
const customerStats = ref({})
const inventoryTurnover = ref([])
const paymentSpeed = ref({})
const trendChartRef = ref(null)
const customerChartRef = ref(null)
let charts = []

const loadTrend = async () => {
  trendData.value = await factoryOpsApi.profileSalesTrend(stationId, { period: trendPeriod.value })
  await nextTick()
  if (trendChartRef.value && trendData.value.length > 0) {
    const tc = echarts.init(trendChartRef.value); charts.push(tc)
    tc.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 10, bottom: 24, top: 10 },
      xAxis: { type: 'category', data: trendData.value.map(d => d.date?.slice(5)) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [{ type: 'line', smooth: true, data: trendData.value.map(d => d.count),
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(64,158,255,0.3)' }, { offset: 1, color: 'rgba(64,158,255,0.02)' }] } },
        lineStyle: { color: 'var(--color-primary)', width: 2 }, itemStyle: { color: '#409EFF' } }]
    })
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const [p, cs, it, ps] = await Promise.all([
      factoryOpsApi.profile(stationId), factoryOpsApi.profileCustomerStats(stationId),
      factoryOpsApi.profileInventoryTurnover(stationId), factoryOpsApi.profilePaymentSpeed(stationId)
    ])
    profile.value = p; customerStats.value = cs; inventoryTurnover.value = it; paymentSpeed.value = ps
    await loadTrend()
    await nextTick()
    if (customerChartRef.value && customerStats.value.totalCustomers > 0) {
      const cc = echarts.init(customerChartRef.value)
      charts.push(cc)
      cc.setOption({
        tooltip: { trigger: 'item' },
        series: [{
          type: 'pie',
          radius: ['40%', '65%'],
          data: [
            { name: '活跃客户', value: customerStats.value.activeCustomers || 0 },
            { name: '流失客户', value: customerStats.value.churnedCustomers || 0 },
            { name: '新增客户', value: customerStats.value.newCustomers || 0 }
          ],
          label: { show: true }
        }]
      })
    }
  } finally { loading.value = false }
}

const handleResize = () => charts.forEach(c => c?.resize())
onMounted(() => { loadData(); window.addEventListener('resize', handleResize) })
onBeforeUnmount(() => { window.removeEventListener('resize', handleResize); charts.forEach(c => c?.dispose()) })
</script>

<style scoped>
.station-profile { max-width: 1400px; margin: 0 auto; }
.profile-header { display: flex; justify-content: space-between; align-items: flex-start; }
.profile-info h2 { margin: 0 0 8px; font-size: 20px; color: var(--text-primary); }
.profile-info p { margin: 4px 0; font-size: 13px; color: var(--text-secondary); }
.profile-stats { display: flex; gap: 24px; }
.pstat { text-align: center; }
.pstat-num { font-size: 28px; font-weight: bold; }
.pstat-label { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.payment-stat { margin: 16px 0; }
.payment-num { font-size: 32px; font-weight: bold; color: var(--color-primary); }
.payment-label { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
</style>
