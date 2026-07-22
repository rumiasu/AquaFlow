<template>
  <div class="factory-dashboard">
    <!-- ===== 顶部全局KPI ===== -->
    <div class="kpi-bar">
      <div class="kpi-item" v-for="kpi in kpiList" :key="kpi.label">
        <div class="kpi-icon" :style="{ background: kpi.bg }">
          <el-icon :size="22" color="#fff"><component :is="kpi.icon" /></el-icon>
        </div>
        <div class="kpi-info">
          <div class="kpi-value">{{ kpi.value }}</div>
          <div class="kpi-label">{{ kpi.label }}</div>
        </div>
      </div>
    </div>

    <!-- ===== 快捷入口 ===== -->
    <div class="quick-nav">
      <div v-for="item in quickLinks" :key="item.path" class="nav-card" @click="$router.push(item.path)">
        <div class="nav-icon" :style="{ background: item.color }">
          <el-icon :size="26" color="#fff"><component :is="item.icon" /></el-icon>
        </div>
        <div class="nav-text">
          <div class="nav-title">{{ item.title }}</div>
          <div class="nav-desc">{{ item.desc }}</div>
        </div>
        <el-icon class="nav-arrow" :size="16"><ArrowRight /></el-icon>
      </div>
    </div>

    <!-- ===== 图表区 ===== -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="16">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">各水站近7天销量趋势</span>
              <el-tag type="info" size="small">自动刷新</el-tag>
            </div>
          </template>
          <div ref="trendChartRef" style="height: 340px;"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="chart-card">
          <template #header>
            <span class="card-title">水站销量排行</span>
          </template>
          <div ref="rankChartRef" style="height: 340px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ===== 水站运营概览 + 预警 ===== -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="14">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">水站运营概览</span>
              <el-button text type="primary" size="small" @click="$router.push('/station-ops')">查看全部</el-button>
            </div>
          </template>
          <el-table :data="stationList" stripe size="small" class="station-table">
            <el-table-column prop="stationName" label="水站" min-width="100" />
            <el-table-column prop="totalQuantity" label="7日销量" width="90" align="center">
              <template #default="{ row }">
                <span class="table-num">{{ row.totalQuantity }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="light" round>
                  {{ row.status === 1 ? '运营中' : '已停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button text type="primary" size="small" @click="$router.push(`/profile/${row.stationId}`)">画像</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">风险预警</span>
              <el-badge :value="alerts.length" type="danger" />
            </div>
          </template>
          <div v-if="alerts.length === 0" class="empty-state">
            <el-icon :size="48" color="#e4e7ed"><CircleCheck /></el-icon>
            <p>暂无预警，运营正常</p>
          </div>
          <div v-else class="alert-list">
            <div v-for="a in alerts" :key="a.id" class="alert-item" @click="$router.push('/risk-alerts')">
              <div class="alert-dot" :class="'level-' + a.alertLevel"></div>
              <div class="alert-content">
                <div class="alert-title">{{ a.title }}</div>
                <div class="alert-meta">
                  <el-tag size="small" :type="a.alertLevel === 3 ? 'danger' : a.alertLevel === 2 ? 'warning' : 'info'" effect="light">
                    {{ { 3: '紧急', 2: '警告', 1: '提示' }[a.alertLevel] }}
                  </el-tag>
                  <span class="alert-time">{{ formatTime(a.createTime) }}</span>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ===== 库存分布 ===== -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="24">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">各站库存分布</span>
              <el-tag type="info" size="small">按水类型堆叠</el-tag>
            </div>
          </template>
          <div ref="inventoryChartRef" style="height: 320px;"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Odometer, TrendCharts, WarningFilled, User, Box, Tickets, ArrowRight, CircleCheck, Monitor, Document } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { factoryOpsApi } from '../../../api'

const overview = ref({})
const alerts = ref([])
const stationList = ref([])
const trendChartRef = ref(null)
const rankChartRef = ref(null)
const inventoryChartRef = ref(null)
let charts = []

const kpiList = computed(() => [
  { label: '水站总数', value: overview.value.stationCount || 0, icon: Monitor, bg: 'linear-gradient(135deg, #409EFF, #2d8cf0)' },
  { label: '今日订单', value: overview.value.todayOrders || 0, icon: Document, bg: 'linear-gradient(135deg, #67C23A, #4caf50)' },
  { label: '客户总数', value: overview.value.totalCustomers || 0, icon: User, bg: 'linear-gradient(135deg, #E6A23C, #f39c12)' },
  { label: '库存预警站', value: overview.value.lowStockStations || 0, icon: Box, bg: 'linear-gradient(135deg, #F56C6C, #e74c3c)' },
  { label: '风险预警', value: overview.value.riskAlerts || 0, icon: WarningFilled, bg: 'linear-gradient(135deg, #9B59B6, #8e44ad)' }
])

const quickLinks = [
  { title: '水站运营', desc: '各站经营数据对比', path: '/station-ops', color: 'linear-gradient(135deg, #409EFF, #2d8cf0)', icon: TrendCharts },
  { title: '数据分析', desc: '销量归因与客户分析', path: '/analysis', color: 'linear-gradient(135deg, #67C23A, #4caf50)', icon: Odometer },
  { title: '水站协同', desc: '跨站库存调拨', path: '/collaboration', color: 'linear-gradient(135deg, #E6A23C, #f39c12)', icon: Tickets },
  { title: '风险预警', desc: '异常监控与处理', path: '/risk-alerts', color: 'linear-gradient(135deg, #F56C6C, #e74c3c)', icon: WarningFilled }
]

const formatTime = (t) => {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const loadData = async () => {
  try {
    const [ov, trendData, rankData, invData, alertData] = await Promise.all([
      factoryOpsApi.overview(),
      factoryOpsApi.stationTrend(),
      factoryOpsApi.stationRanking(),
      factoryOpsApi.inventoryOverview(),
      factoryOpsApi.recentAlerts()
    ])
    overview.value = ov
    alerts.value = (alertData || []).slice(0, 8)
    stationList.value = rankData || []
    await nextTick()
    initCharts(trendData, rankData, invData)
  } catch (e) {
    console.error('Factory dashboard load error:', e)
  }
}

const initCharts = (trendData, rankData, invData) => {
  charts.forEach(c => c?.dispose())
  charts = []

  // 趋势图
  if (trendChartRef.value && trendData?.length) {
    const tc = echarts.init(trendChartRef.value)
    charts.push(tc)
    const stations = [...new Set(trendData.map(d => d.stationName))]
    const dates = [...new Set(trendData.map(d => d.date))].sort()
    const colors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#9B59B6', '#1ABC9C', '#34495E', '#E67E22', '#2ECC71', '#3498DB']
    tc.setOption({
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.96)', borderColor: '#e4e7ed', textStyle: { color: '#303133' } },
      legend: { data: stations, bottom: 0, type: 'scroll', itemWidth: 12, itemHeight: 3, textStyle: { fontSize: 11 } },
      grid: { left: 45, right: 15, bottom: 45, top: 15 },
      xAxis: { type: 'category', data: dates.map(d => d?.slice(5)), axisLine: { lineStyle: { color: '#e4e7ed' } }, axisLabel: { color: '#909399' } },
      yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#f0f2f5', type: 'dashed' } }, axisLabel: { color: '#909399' } },
      series: stations.map((name, i) => ({
        name, type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
        data: dates.map(date => { const item = trendData.find(d => d.date === date && d.stationName === name); return item ? item.count : 0 }),
        lineStyle: { width: 2.5, color: colors[i % colors.length] },
        itemStyle: { color: colors[i % colors.length] },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: colors[i % colors.length] + '20' }, { offset: 1, color: colors[i % colors.length] + '02' }]) }
      }))
    })
  }

  // 排行图
  if (rankChartRef.value && rankData?.length) {
    const rc = echarts.init(rankChartRef.value)
    charts.push(rc)
    const reversed = [...rankData].slice(0, 10).reverse()
    rc.setOption({
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.96)', borderColor: '#e4e7ed', textStyle: { color: '#303133' } },
      grid: { left: 85, right: 25, bottom: 10, top: 10 },
      xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f2f5', type: 'dashed' } }, axisLabel: { color: '#909399' } },
      yAxis: { type: 'category', data: reversed.map(d => d.stationName), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#606266', fontSize: 12 } },
      series: [{
        type: 'bar', barMaxWidth: 22, data: reversed.map(d => d.totalQuantity),
        itemStyle: { borderRadius: [0, 4, 4, 0], color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [{ offset: 0, color: '#409EFF' }, { offset: 1, color: '#79bbff' }]) },
        label: { show: true, position: 'right', color: '#606266', fontSize: 12 }
      }]
    })
  }

  // 库存堆叠图
  if (inventoryChartRef.value && invData?.length) {
    const ic = echarts.init(inventoryChartRef.value)
    charts.push(ic)
    const stationNames = [...new Set(invData.map(d => d.stationName))]
    const waterTypes = [...new Set(invData.map(d => d.waterTypeName))]
    const colors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#9B59B6', '#1ABC9C', '#34495E', '#E67E22', '#2ECC71', '#3498DB']
    ic.setOption({
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.96)', borderColor: '#e4e7ed', textStyle: { color: '#303133' } },
      legend: { data: stationNames, bottom: 0, type: 'scroll', itemWidth: 12, itemHeight: 12, textStyle: { fontSize: 11 } },
      grid: { left: 45, right: 15, bottom: 45, top: 15 },
      xAxis: { type: 'category', data: waterTypes, axisLine: { lineStyle: { color: '#e4e7ed' } }, axisLabel: { color: '#909399', rotate: waterTypes.length > 6 ? 20 : 0 } },
      yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#f0f2f5', type: 'dashed' } }, axisLabel: { color: '#909399' } },
      series: stationNames.map((name, i) => ({
        name, type: 'bar', stack: 'total', barMaxWidth: 32,
        data: waterTypes.map(wt => { const item = invData.find(d => d.stationName === name && d.waterTypeName === wt); return item ? item.quantity : 0 }),
        itemStyle: { color: colors[i % colors.length], borderRadius: i === stationNames.length - 1 ? [3, 3, 0, 0] : [0, 0, 0, 0] }
      }))
    })
  }
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
.factory-dashboard {
  max-width: 1480px;
  margin: 0 auto;
}

/* KPI Bar */
.kpi-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}
.kpi-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 14px;
  background: var(--bg-card);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: var(--shadow-card);
  transition: all 0.3s ease;
}
.kpi-item:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}
.kpi-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  flex-shrink: 0;
}
.kpi-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
}
.kpi-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}

/* Quick Nav */
.quick-nav {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.nav-card {
  display: flex;
  align-items: center;
  gap: 14px;
  background: var(--bg-card);
  border-radius: 12px;
  padding: 18px 20px;
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: all 0.3s ease;
}
.nav-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}
.nav-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 50px;
  height: 50px;
  border-radius: 12px;
  flex-shrink: 0;
}
.nav-text { flex: 1; }
.nav-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.nav-desc { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.nav-arrow { color: var(--text-secondary); opacity: 0.5; }

/* Charts */
.chart-row { margin-bottom: 0; }
.chart-row + .chart-row { margin-top: 20px; }
.chart-card { border-radius: 12px; }
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

/* Station Table */
.station-table { border-radius: 8px; overflow: hidden; }
.table-num { font-weight: 600; color: var(--color-primary); }

/* Alert List */
.alert-list { max-height: 340px; overflow-y: auto; }
.alert-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-light);
  cursor: pointer;
  transition: background 0.2s;
  border-radius: 6px;
  padding-left: 8px;
  padding-right: 8px;
}
.alert-item:last-child { border-bottom: none; }
.alert-item:hover { background: var(--bg-hover); }
.alert-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
}
.alert-dot.level-3 { background: #F56C6C; box-shadow: 0 0 6px rgba(245,108,108,0.4); }
.alert-dot.level-2 { background: #E6A23C; box-shadow: 0 0 6px rgba(230,162,60,0.4); }
.alert-dot.level-1 { background: #909399; }
.alert-content { flex: 1; min-width: 0; }
.alert-title { font-size: 13px; color: var(--text-primary); font-weight: 500; margin-bottom: 4px; }
.alert-meta { display: flex; align-items: center; gap: 8px; }
.alert-time { font-size: 11px; color: var(--text-secondary); }

/* Empty State */
.empty-state {
  text-align: center;
  padding: 60px 0;
  color: var(--text-secondary);
}
.empty-state p { margin-top: 12px; font-size: 14px; }
</style>
