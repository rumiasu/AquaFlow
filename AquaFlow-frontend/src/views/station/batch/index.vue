<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">批次列表</span>
          <div class="header-actions">
            <el-select v-model="query.status" clearable placeholder="状态" style="width: 120px;">
              <el-option label="待配送" :value="1" />
              <el-option label="配送中" :value="2" />
              <el-option label="已完成" :value="3" />
            </el-select>
            <el-date-picker v-model="dateRange" type="daterange" range-separator="~" start-placeholder="开始"
              end-placeholder="结束" value-format="YYYY-MM-DD" style="width: 260px;" @change="handleDateChange" />
            <el-button @click="loadData">查询</el-button>
            <el-button type="primary" @click="showCreate">创建批次</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="['','warning','','success'][row.status]">{{ { 1: '待配送', 2: '配送中', 3: '已完成' }[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalQTY" label="总数量" width="100" />
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <el-button size="small" @click="showDetail(row.id)">详情</el-button>
            <el-button size="small" type="primary" plain @click="showRoute(row)" v-if="row.status === 1 || row.status === 2">配送路线</el-button>
            <el-button size="small" type="warning" @click="startBatch(row.id)" v-if="row.status === 1">开始配送</el-button>
            <el-button size="small" type="success" @click="showFinish(row)" v-if="row.status === 2">完成配送</el-button>
            <el-button size="small" type="danger" @click="deleteBatch(row.id)" v-if="row.status === 1">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建批次 -->
    <el-dialog v-model="createVisible" title="创建配送批次" width="750px">
      <p style="margin-bottom: 10px; color: var(--text-secondary);">勾选待组批的订单，系统将自动校验并扣减库存：</p>
      <el-table :data="pendingOrders" border stripe @selection-change="handleSelectionChange" max-height="400">
        <el-table-column type="selection" width="50" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="addressDetail" label="地址" show-overflow-tooltip />
        <el-table-column prop="waterTypeName" label="水类型" />
        <el-table-column prop="quantity" label="数量" width="70" />
        <el-table-column prop="source" label="来源" width="70">
          <template #default="{ row }">{{ { 1: '电话', 2: '微信群', 3: '小程序' }[row.source] }}</template>
        </el-table-column>
      </el-table>
      <div v-if="pendingOrders.length === 0" class="empty-state">暂无待组批订单</div>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate" :disabled="selectedOrders.length === 0">
          创建批次（已选 {{ selectedOrders.length }} 单）
        </el-button>
      </template>
    </el-dialog>

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

    <!-- 配送路线地图 -->
    <el-dialog v-model="routeVisible" title="配送路线" width="900px" top="5vh">
      <div ref="routeMapRef" style="height: 500px;"></div>
    </el-dialog>

    <!-- 完成配送 -->
    <el-dialog v-model="finishVisible" title="完成配送" width="500px">
      <p style="color: var(--text-secondary);">请勾选已完成的订单（未勾选的将保持配送中状态）：</p>
      <el-checkbox-group v-model="finishedIds">
        <div v-for="o in finishBatchOrders" :key="o.id" style="margin: 8px 0; padding: 6px; border-radius: 4px; background: var(--bg-input);">
          <el-checkbox :label="o.id">{{ o.customerName }} - {{ o.waterTypeName }} x{{ o.quantity }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="finishVisible = false">取消</el-button>
        <el-button type="warning" @click="submitFinishAll">全部完成</el-button>
        <el-button type="primary" @click="submitFinish">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { batchApi, orderApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const query = ref({ status: '', createTimeStart: '', createTimeEnd: '' })
const dateRange = ref(null)

const createVisible = ref(false)
const pendingOrders = ref([])
const selectedOrders = ref([])

const detailVisible = ref(false)
const detailOrders = ref([])

const routeVisible = ref(false)
const routeMapRef = ref(null)
let routeMap = null

const finishVisible = ref(false)
const finishBatchId = ref(null)
const finishBatchOrders = ref([])
const finishedIds = ref([])

const handleDateChange = (val) => {
  query.value.createTimeStart = val?.[0] || ''
  query.value.createTimeEnd = val?.[1] || ''
}

const loadData = async () => {
  loading.value = true
  try { list.value = await batchApi.list(query.value) } finally { loading.value = false }
}

const showCreate = async () => {
  pendingOrders.value = await orderApi.list({ status: 1 })
  selectedOrders.value = []
  createVisible.value = true
}

const handleSelectionChange = (s) => { selectedOrders.value = s }

const submitCreate = async () => {
  if (selectedOrders.value.length === 0) { ElMessage.warning('请至少选择一个订单'); return }
  try {
    await batchApi.create(selectedOrders.value.map(o => o.id))
    ElMessage.success('批次创建成功')
    createVisible.value = false
    loadData()
  } catch (e) {}
}

const showDetail = async (id) => {
  const batch = await batchApi.getById(id)
  detailOrders.value = batch.orders || []
  detailVisible.value = true
}

const showRoute = async (row) => {
  const batch = await batchApi.getById(row.id)
  const orders = batch.orders || []
  routeVisible.value = true
  await nextTick()
  if (routeMap) { routeMap.remove(); routeMap = null }
  routeMap = L.map(routeMapRef.value).setView([39.9042, 116.4074], 5)
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '&copy; OpenStreetMap' }).addTo(routeMap)
  const group = L.featureGroup()
  const tagColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#b37feb', '#36cfc9']
  orders.forEach((o, i) => {
    if (o.addressLat && o.addressLng) {
      const color = tagColors[i % tagColors.length]
      L.circleMarker([o.addressLat, o.addressLng], { radius: 8, fillColor: color, color: '#fff', weight: 2, fillOpacity: 0.9 })
        .addTo(routeMap)
        .bindPopup(`<b>${o.customerName}</b><br/>${o.addressDetail}<br/>${o.waterTypeName} x${o.quantity}`)
      group.addLayer(L.circleMarker([o.addressLat, o.addressLng], { radius: 0 }))
    }
  })
  if (group.getLayers().length > 0) {
    routeMap.fitBounds(group.getBounds().pad(0.2))
  }
}

const startBatch = async (id) => {
  await ElMessageBox.confirm('确定开始配送？', '确认')
  await batchApi.start(id)
  ElMessage.success('已开始配送')
  loadData()
}

const deleteBatch = async (id) => {
  await ElMessageBox.confirm('确定删除该批次？订单将恢复为待组批状态。', '确认')
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

const submitFinishAll = async () => {
  await ElMessageBox.confirm('确定全部完成？所有订单将标记为已完成。', '确认')
  await batchApi.finishAll(finishBatchId.value)
  ElMessage.success('全部完成')
  finishVisible.value = false
  loadData()
}

onMounted(loadData)
</script>
