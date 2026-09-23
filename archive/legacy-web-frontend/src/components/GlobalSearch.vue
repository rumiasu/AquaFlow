<template>
  <el-dialog v-model="visible" title="全局搜索" width="640px" :show-close="false" top="8vh" destroy-on-close>
    <el-input v-model="keyword" placeholder="搜索客户、地址、订单..." clearable autofocus
      @input="handleSearch" size="large" prefix-icon="Search" />
    <div v-if="results" class="search-results">
      <div v-if="results.customers && results.customers.length">
        <div class="section-title">客户</div>
        <div v-for="c in results.customers" :key="'c'+c.id" class="search-item"
          @click="$emit('navigate', '/customer')">
          <div class="item-main">
            <el-icon><User /></el-icon>
            <strong>{{ c.name }}</strong>
            <span class="item-sub">{{ c.phone }}</span>
          </div>
        </div>
      </div>
      <div v-if="results.addresses && results.addresses.length">
        <div class="section-title">地址</div>
        <div v-for="a in results.addresses" :key="'a'+a.id" class="search-item"
          @click="$emit('navigate', '/address')">
          <div class="item-main">
            <el-icon><Location /></el-icon>
            <strong>{{ a.detail }}</strong>
            <el-tag size="small" v-if="a.tag" effect="plain">{{ a.tag }}</el-tag>
          </div>
        </div>
      </div>
      <div v-if="results.orders && results.orders.length">
        <div class="section-title">订单</div>
        <div v-for="o in results.orders" :key="'o'+o.id" class="search-item"
          @click="$emit('navigate', '/order')">
          <div class="item-main">
            <el-icon><Document /></el-icon>
            <strong>#{{ o.id }} {{ o.customerName || o.receiverName }}</strong>
            <span class="item-sub">{{ o.customerPhone || o.receiverPhone }}</span>
            <el-tag size="small" :type="orderTagType(o.status)">{{ orderStatusText(o.status) }}</el-tag>
          </div>
        </div>
      </div>
      <div v-if="isEmpty" class="empty-result">
        <el-icon :size="40" color="#c0c4cc"><Search /></el-icon>
        <p>无搜索结果</p>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Search, User, Location, Document } from '@element-plus/icons-vue'
import { searchApi } from '../api'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'navigate'])
const visible = ref(false)
const keyword = ref('')
const results = ref(null)
let timer = null

const isEmpty = computed(() => {
  if (!results.value) return false
  const r = results.value
  return (!r.customers || !r.customers.length) &&
    (!r.addresses || !r.addresses.length) &&
    (!r.orders || !r.orders.length)
})

const orderStatusText = (s) => ({ 1: '待配送', 2: '配送中', 3: '已送达', 4: '已完成', 5: '已取消' }[s] || '未知')
const orderTagType = (s) => ({ 1: 'warning', 2: 'primary', 3: 'success', 4: 'info', 5: 'danger' }[s] || 'info')

watch(() => props.modelValue, (v) => { visible.value = v; if (!v) { keyword.value = ''; results.value = null } })
watch(visible, (v) => emit('update:modelValue', v))

const handleSearch = () => {
  clearTimeout(timer)
  if (!keyword.value.trim()) { results.value = null; return }
  timer = setTimeout(async () => {
    try { results.value = await searchApi.search(keyword.value) }
    catch { results.value = { customers: [], addresses: [], orders: [] } }
  }, 300)
}
</script>

<style scoped>
.search-results { margin-top: 16px; max-height: 420px; overflow-y: auto; }
.section-title { color: #909399; font-size: 13px; margin-bottom: 8px; padding-left: 4px; font-weight: 500; }
.search-item {
  padding: 10px 12px; border-radius: 8px; cursor: pointer; margin-bottom: 4px;
  transition: background 0.2s;
}
.search-item:hover { background: #f5f7fa; }
.item-main { display: flex; align-items: center; gap: 8px; }
.item-sub { color: #909399; font-size: 13px; }
.empty-result { text-align: center; padding: 40px 0; color: #c0c4cc; }
.empty-result p { margin-top: 8px; }
</style>
