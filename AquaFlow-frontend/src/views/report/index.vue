<template>
  <div>
    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="14">
        <el-card>
          <template #header><span>地址标签统计（Top 10）</span></template>
          <div ref="barChartRef" style="height: 350px;"></div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header><span>标签占比</span></template>
          <div ref="pieChartRef" style="height: 350px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-bottom: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header><span>水类型销量排行</span></template>
          <div ref="waterChartRef" style="height: 320px;"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span>Top 10 高频客户</span></template>
          <div ref="customerChartRef" style="height: 320px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header><span>地址标签明细</span></template>
      <el-table :data="tagData" border stripe>
        <el-table-column type="index" label="排名" width="80" />
        <el-table-column prop="tag" label="标签" />
        <el-table-column prop="count" label="地址数量" width="120" />
        <el-table-column label="占比" width="120">
          <template #default="{ row }">{{ ((row.count / totalCount) * 100).toFixed(1) }}%</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { reportApi, dashboardApi } from '../../api'

const tagData = ref([])
const barChartRef = ref(null)
const pieChartRef = ref(null)
const waterChartRef = ref(null)
const customerChartRef = ref(null)
let charts = []

const totalCount = computed(() => tagData.value.reduce((s, d) => s + d.count, 0))
const pieColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#b37feb', '#36cfc9', '#ff85c0', '#ffc53d', '#73d13d']

const loadData = async () => {
  const [tags, waterSales, topCustomers] = await Promise.all([
    reportApi.addressTags(),
    dashboardApi.waterTypeSales(),
    dashboardApi.topCustomers()
  ])
  tagData.value = tags

  // 柱状图
  const bc = echarts.init(barChartRef.value); charts.push(bc)
  bc.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 60, right: 20, bottom: 50, top: 10 },
    xAxis: { type: 'category', data: tags.map(d => d.tag), axisLabel: { rotate: 20 } },
    yAxis: { type: 'value', name: '地址数量', minInterval: 1 },
    series: [{ type: 'bar', data: tags.map((d, i) => ({ value: d.count, itemStyle: { borderRadius: [4, 4, 0, 0], color: pieColors[i % pieColors.length] } })), barMaxWidth: 50 }]
  })

  // 饼图
  const pc = echarts.init(pieChartRef.value); charts.push(pc)
  pc.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll', itemWidth: 10, itemHeight: 10 },
    color: pieColors,
    series: [{ type: 'pie', radius: ['35%', '65%'], itemStyle: { borderRadius: 8, borderColor: 'var(--bg-card)', borderWidth: 2 }, label: { show: false }, emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } }, data: tags.map(d => ({ name: d.tag, value: d.count })) }]
  })

  // 水类型销售
  const wc = echarts.init(waterChartRef.value); charts.push(wc)
  wc.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 20, bottom: 30, top: 10 },
    xAxis: { type: 'category', data: waterSales.map(d => d.waterTypeName) },
    yAxis: { type: 'value', name: '销量', minInterval: 1 },
    series: [{ type: 'bar', data: waterSales.map(d => d.totalQuantity), itemStyle: { borderRadius: [4, 4, 0, 0], color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#E6A23C' }, { offset: 1, color: '#f0c78a' }]) }, barMaxWidth: 40 }]
  })

  // 客户排行
  const cc = echarts.init(customerChartRef.value); charts.push(cc)
  const reversed = [...topCustomers].reverse()
  cc.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 30, bottom: 10, top: 10 },
    xAxis: { type: 'value', name: '配送量' },
    yAxis: { type: 'category', data: reversed.map(d => d.customerName) },
    series: [{ type: 'bar', data: reversed.map(d => d.totalQuantity), itemStyle: { borderRadius: [0, 4, 4, 0], color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [{ offset: 0, color: '#409EFF' }, { offset: 1, color: '#79bbff' }]) }, barMaxWidth: 24 }]
  })
}

const handleResize = () => { charts.forEach(c => c?.resize()) }

onMounted(() => { loadData(); window.addEventListener('resize', handleResize) })
onBeforeUnmount(() => { window.removeEventListener('resize', handleResize); charts.forEach(c => c?.dispose()); charts = [] })
</script>
