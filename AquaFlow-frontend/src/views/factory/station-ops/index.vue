<template>
  <div class="station-ops page-container">
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="8" v-for="item in summaryCards" :key="item.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value" :style="{ color: item.color }">{{ item.value }}</div>
          <div class="stat-label">{{ item.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="10">
        <el-card>
          <template #header><span>水站销量排行</span></template>
          <el-table :data="ranking" border stripe size="small">
            <el-table-column type="index" label="排名" width="60" />
            <el-table-column prop="stationName" label="水站" />
            <el-table-column prop="totalQuantity" label="总销量" width="100" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="goProfile(row.stationId)">画像</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <template #header><span>近7天各站销量趋势</span></template>
          <div ref="trendChartRef" style="height: 320px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header><span>各站库存概览</span></template>
      <div ref="inventoryChartRef" style="height: 300px;"></div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { factoryOpsApi } from '../../../api'

const router = useRouter()
const ranking = ref([])
const trendData = ref([])
const inventoryData = ref([])
const trendChartRef = ref(null)
const inventoryChartRef = ref(null)
let charts = []

const summaryCards = [
  { label: '水站总数', value: ref(0), color: 'var(--color-primary)' },
  { label: '今日总销量', value: ref(0), color: 'var(--color-success)' },
  { label: '库存预警', value: ref(0), color: 'var(--color-danger)' }
]

const loadData = async () => {
  try {
    const [rank, trend, inventory] = await Promise.all([
      factoryOpsApi.stationRanking(),
      factoryOpsApi.stationTrend(),
      factoryOpsApi.inventoryOverview()
    ])
    ranking.value = rank
    trendData.value = trend
    inventoryData.value = inventory
    summaryCards[0].value.value = rank.length
    summaryCards[1].value.value = rank.reduce((s, r) => s + (r.totalQuantity || 0), 0)
    summaryCards[2].value.value = inventory.filter(i => i.quantity < 10).length
    await nextTick()
    initCharts()
  } catch (e) { console.error(e) }
}

const initCharts = () => {
  charts.forEach(c => c?.dispose()); charts = []
  if (trendChartRef.value && trendData.value.length > 0) {
    const tc = echarts.init(trendChartRef.value); charts.push(tc)
    const stations = [...new Set(trendData.value.map(d => d.stationName))]
    const dates = [...new Set(trendData.value.map(d => d.date))].sort()
    tc.setOption({
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.96)', borderColor: '#e4e7ed', textStyle: { color: '#303133' } },
      legend: { data: stations, bottom: 0, type: 'scroll', itemWidth: 10, itemHeight: 10 },
      grid: { left: 40, right: 10, bottom: 40, top: 10 },
      xAxis: { type: 'category', data: dates.map(d => d?.slice(5)) },
      yAxis: { type: 'value', minInterval: 1 },
      series: stations.map(name => ({
        name, type: 'line', smooth: true,
        data: dates.map(date => {
          const item = trendData.value.find(d => d.date === date && d.stationName === name)
          return item ? item.count : 0
        })
      }))
    })
  }
  if (inventoryChartRef.value && inventoryData.value.length > 0) {
    const ic = echarts.init(inventoryChartRef.value); charts.push(ic)
    const stationNames = [...new Set(inventoryData.value.map(d => d.stationName))]
    const waterTypes = [...new Set(inventoryData.value.map(d => d.waterTypeName))]
    ic.setOption({
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.96)', borderColor: '#e4e7ed', textStyle: { color: '#303133' } },
      legend: { data: stationNames, bottom: 0, type: 'scroll', itemWidth: 10, itemHeight: 10 },
      grid: { left: 40, right: 10, bottom: 40, top: 10 },
      xAxis: { type: 'category', data: waterTypes },
      yAxis: { type: 'value', minInterval: 1 },
      series: stationNames.map(name => ({
        name, type: 'bar', stack: 'total',
        data: waterTypes.map(wt => {
          const item = inventoryData.value.find(d => d.stationName === name && d.waterTypeName === wt)
          return item ? item.quantity : 0
        })
      }))
    })
  }
}

const goProfile = (id) => router.push(`/profile/${id}`)
const handleResize = () => charts.forEach(c => c?.resize())
onMounted(() => { loadData(); window.addEventListener('resize', handleResize) })
onBeforeUnmount(() => { window.removeEventListener('resize', handleResize); charts.forEach(c => c?.dispose()) })
</script>

<style scoped>
.station-ops { max-width: 1400px; margin: 0 auto; }
.stat-card { text-align: center; }
.stat-value { font-size: 28px; font-weight: bold; }
.stat-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
</style>
