<template>
  <el-dialog v-model="visible" title="全局搜索" width="600px" :show-close="false" top="10vh">
    <el-input v-model="keyword" placeholder="搜索客户、地址..." clearable autofocus
      @input="handleSearch" size="large" prefix-icon="Search" />
    <div v-if="results" style="margin-top: 16px; max-height: 400px; overflow-y: auto;">
      <div v-if="results.customers.length">
        <h4 style="color: #909399; margin-bottom: 8px;">客户</h4>
        <div v-for="c in results.customers" :key="'c'+c.id"
          style="padding: 8px 12px; border-radius: 6px; cursor: pointer; margin-bottom: 4px;"
          class="search-item" @click="$emit('navigate', '/customer')">
          <strong>{{ c.name }}</strong> <span style="color: #909399;">{{ c.phone }}</span>
        </div>
      </div>
      <div v-if="results.addresses.length" style="margin-top: 12px;">
        <h4 style="color: #909399; margin-bottom: 8px;">地址</h4>
        <div v-for="a in results.addresses" :key="'a'+a.id"
          style="padding: 8px 12px; border-radius: 6px; cursor: pointer; margin-bottom: 4px;"
          class="search-item" @click="$emit('navigate', '/address')">
          <strong>{{ a.detail }}</strong> <el-tag size="small" v-if="a.tag">{{ a.tag }}</el-tag>
        </div>
      </div>
      <div v-if="results.customers.length === 0 && results.addresses.length === 0"
        style="text-align: center; padding: 30px; color: #c0c4cc;">
        无搜索结果
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { searchApi } from '../api'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'navigate'])
const visible = ref(false)
const keyword = ref('')
const results = ref(null)
let timer = null

watch(() => props.modelValue, (v) => { visible.value = v; if (!v) { keyword.value = ''; results.value = null } })
watch(visible, (v) => emit('update:modelValue', v))

const handleSearch = () => {
  clearTimeout(timer)
  if (!keyword.value.trim()) { results.value = null; return }
  timer = setTimeout(async () => {
    results.value = await searchApi.search(keyword.value)
  }, 300)
}
</script>

<style scoped>
.search-item:hover { background: #f5f7fa; }
</style>
