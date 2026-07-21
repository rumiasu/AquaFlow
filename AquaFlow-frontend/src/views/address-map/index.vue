<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="16">
        <el-card :body-style="{ padding: '0' }" style="position: relative;">
          <div ref="mapRef" style="height: 600px;"></div>
          <!-- 模式切换按钮 -->
          <div class="mode-switch">
            <el-button :type="mode === 'order' ? 'primary' : 'info'" size="small" @click="switchMode('order')">
              <el-icon><Document /></el-icon> 订单
            </el-button>
            <el-button :type="mode === 'customer' ? 'primary' : 'info'" size="small" @click="switchMode('customer')">
              <el-icon><User /></el-icon> 客户
            </el-button>
          </div>
          <!-- 订单模式：选中计数 + 创建批次 -->
          <div v-if="mode === 'order'" class="order-bar">
            <span>已选 <b>{{ selectedOrderIds.length }}</b> 单</span>
            <el-button type="primary" :disabled="selectedOrderIds.length === 0" @click="createBatch">
              创建批次
            </el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>{{ mode === 'order' ? '待组批订单' : '客户地址' }}</span>
              <el-tag size="small">{{ mode === 'order' ? pendingOrders.length : addresses.length }} 个</el-tag>
            </div>
          </template>
          <div style="max-height: 540px; overflow-y: auto;">
            <!-- 订单模式 -->
            <template v-if="mode === 'order'">
              <div v-for="o in pendingOrders" :key="o.id" class="list-item"
                @click="toggleOrder(o)" :class="{ active: selectedOrderIds.includes(o.id) }">
                <div style="display: flex; align-items: center; gap: 8px;">
                  <el-checkbox :model-value="selectedOrderIds.includes(o.id)" @click.stop />
                  <div>
                    <div style="font-weight: 500;">{{ o.customerName }} - {{ o.waterTypeName }}</div>
                    <div style="font-size: 12px; color: var(--text-secondary);">
                      {{ o.addressDetail }} · x{{ o.quantity }}
                    </div>
                  </div>
                </div>
              </div>
              <div v-if="pendingOrders.length === 0" style="text-align: center; padding: 30px; color: var(--text-secondary);">
                暂无待组批订单
              </div>
            </template>
            <!-- 客户模式 -->
            <template v-else>
              <div v-for="a in addresses" :key="a.id" class="list-item"
                @click="flyTo(a)" :class="{ active: selectedId === a.id }">
                <div style="display: flex; align-items: center; gap: 8px;">
                  <div class="tag-dot" :style="{ backgroundColor: getTagColor(a.tag) }"></div>
                  <div>
                    <div style="font-weight: 500;">{{ a.detail }}</div>
                    <div style="font-size: 12px; color: var(--text-secondary);">
                      <el-tag size="small" v-if="a.tag">{{ a.tag }}</el-tag>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Document, User } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { addressApi, orderApi, batchApi } from '../../api'

const route = useRoute()
const router = useRouter()

const mapRef = ref(null)
const addresses = ref([])
const pendingOrders = ref([])
const selectedId = ref(null)
const selectedOrderIds = ref([])
const mode = ref('order')
let map = null
let markers = []

const tagColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#b37feb', '#36cfc9']
const tagColorMap = {}

const getTagColor = (tag) => {
  if (!tag) return '#c0c4cc'
  if (!tagColorMap[tag]) {
    const keys = Object.keys(tagColorMap)
    tagColorMap[tag] = tagColors[keys.length % tagColors.length]
  }
  return tagColorMap[tag]
}

const flyTo = (addr) => {
  selectedId.value = addr.id
  if (addr.lat && addr.lng) map.flyTo([addr.lat, addr.lng], 15, { duration: 1 })
}

const toggleOrder = (o) => {
  const idx = selectedOrderIds.value.indexOf(o.id)
  if (idx === -1) selectedOrderIds.value.push(o.id)
  else selectedOrderIds.value.splice(idx, 1)
}

const createBatch = async () => {
  if (selectedOrderIds.value.length === 0) return
  await ElMessageBox.confirm(`确认将 ${selectedOrderIds.value.length} 个订单创建为一个批次？`, '创建批次')
  await batchApi.create(selectedOrderIds.value)
  ElMessage.success('批次创建成功')
  selectedOrderIds.value = []
  loadData()
}

const switchMode = (m) => {
  mode.value = m
  renderMap()
}

const clearMarkers = () => {
  markers.forEach(m => m.remove())
  markers = []
}

const renderMap = () => {
  clearMarkers()
  if (!map) return
  const group = L.featureGroup()

  if (mode.value === 'order') {
    pendingOrders.value.forEach((o, idx) => {
      if (!o.addressLat || !o.addressLng) return
      const selected = selectedOrderIds.value.includes(o.id)
      const color = selected ? '#F56C6C' : '#409EFF'
      const icon = L.divIcon({
        className: '',
        html: `<div style="
          width:32px;height:32px;border-radius:50%;background:${color};
          display:flex;align-items:center;justify-content:center;
          color:#fff;font-weight:bold;font-size:14px;
          box-shadow:0 3px 8px rgba(0,0,0,0.3);border:2px solid #fff;
          transition:transform 0.15s;
        ">${o.id}</div>`,
        iconSize: [32, 32],
        iconAnchor: [16, 32],
        popupAnchor: [0, -32]
      })
      const marker = L.marker([o.addressLat, o.addressLng], { icon }).addTo(map)
      marker.bindPopup(`<b>${o.customerName}</b><br/>${o.addressDetail}<br/>${o.waterTypeName} x${o.quantity}`)
      marker.on('click', () => toggleOrder(o))
      markers.push(marker)
      group.addLayer(marker)
    })
  } else {
    addresses.value.filter(a => a.lat && a.lng).forEach(a => {
      const color = getTagColor(a.tag)
      const initial = a.detail?.slice(0, 1) || '?'
      const icon = L.divIcon({
        className: '',
        html: `<div style="
          width:36px;height:36px;border-radius:50%;background:${color};
          display:flex;align-items:center;justify-content:center;
          color:#fff;font-weight:bold;font-size:15px;
          box-shadow:0 3px 10px rgba(0,0,0,0.25);border:2px solid #fff;
        ">${initial}</div>`,
        iconSize: [36, 36],
        iconAnchor: [18, 36],
        popupAnchor: [0, -36]
      })
      const marker = L.marker([a.lat, a.lng], { icon }).addTo(map)
      marker.bindPopup(`<b>${a.detail}</b><br/>${a.tag || ''}`)
      marker.on('click', () => { selectedId.value = a.id })
      markers.push(marker)
      group.addLayer(marker)
    })
  }

  if (group.getLayers().length > 0) map.fitBounds(group.getBounds().pad(0.1))
}

const loadData = async () => {
  const [addrs, orders] = await Promise.all([
    addressApi.list({}),
    orderApi.list({ status: 1 })
  ])
  addresses.value = addrs
  pendingOrders.value = orders

  // URL 参数支持
  if (route.query.mode) mode.value = route.query.mode
  if (route.query.batchId) {
    const batch = await batchApi.getById(route.query.batchId)
    const batchOrderIds = (batch.orders || []).map(o => o.id)
    selectedOrderIds.value = batchOrderIds
  }

  renderMap()
}

onMounted(async () => {
  await loadData()
  map = L.map(mapRef.value).setView([36.65, 117.02], 12)
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap'
  }).addTo(map)
  renderMap()
})

onBeforeUnmount(() => { map?.remove() })
</script>

<style scoped>
.mode-switch {
  position: absolute; top: 12px; right: 12px; z-index: 1000;
  display: flex; gap: 4px; background: rgba(255,255,255,0.92);
  padding: 4px; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.12);
}
.order-bar {
  position: absolute; bottom: 12px; left: 50%; transform: translateX(-50%); z-index: 1000;
  display: flex; align-items: center; gap: 16px;
  background: rgba(255,255,255,0.95); padding: 10px 20px;
  border-radius: 10px; box-shadow: 0 2px 12px rgba(0,0,0,0.15);
  font-size: 14px;
}
.list-item {
  padding: 10px 8px; border-bottom: 1px solid var(--border-light);
  cursor: pointer; border-radius: 6px; transition: all 0.2s;
}
.list-item:hover { background: var(--bg-hover); }
.list-item.active { background: var(--bg-sidebar-active); }
.tag-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
:deep(.leaflet-div-icon) { background: none !important; border: none !important; }
</style>
