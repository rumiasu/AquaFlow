<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">库存管理</span>
          <div class="header-actions">
            <el-tag type="info" size="large">总库存：<strong>{{ totalQuantity }}</strong></el-tag>
            <el-tag type="danger" size="large" v-if="lowStockCount > 0">低库存：<strong>{{ lowStockCount }}</strong> 项</el-tag>
            <el-button type="primary" @click="showInbound">入库</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="productName" label="商品" />
        <el-table-column prop="spec" label="规格" />
        <el-table-column prop="quantity" label="库存数量" width="140">
          <template #default="{ row }">
            <span :class="row.quantity < 20 ? 'text-danger text-bold' : ''">
              {{ row.quantity }}
            </span>
            <el-tag type="danger" size="small" v-if="row.quantity < 20" style="margin-left: 6px;">低</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="最后更新" width="160" />
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="批量入库" width="550px">
      <el-form :model="inboundForm" label-width="80px">
        <div v-for="(item, index) in inboundForm.items" :key="index" style="display: flex; gap: 10px; margin-bottom: 10px; align-items: center;">
          <el-select v-model="item.productId" placeholder="选择商品" style="flex: 1;">
            <el-option v-for="p in products" :key="p.id" :label="`${p.name} ${p.spec}`" :value="p.id" />
          </el-select>
          <el-input-number v-model="item.quantity" :min="1" style="width: 130px;" />
          <el-button type="danger" :icon="Delete" circle v-if="inboundForm.items.length > 1" @click="inboundForm.items.splice(index, 1)" />
        </div>
        <el-button @click="inboundForm.items.push({ productId: null, quantity: 1 })">+ 添加一项</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import { inventoryApi, productApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const products = ref([])
const dialogVisible = ref(false)
const inboundForm = ref({ items: [{ productId: null, quantity: 1 }] })

const totalQuantity = computed(() => list.value.reduce((sum, item) => sum + (item.quantity || 0), 0))
const lowStockCount = computed(() => list.value.filter(item => item.quantity < 20).length)

const loadData = async () => {
  loading.value = true
  try { list.value = await inventoryApi.list() } finally { loading.value = false }
}
const loadProducts = async () => { products.value = await productApi.list() }
const showInbound = () => { inboundForm.value = { items: [{ productId: null, quantity: 1 }] }; dialogVisible.value = true }
const submit = async () => { await inventoryApi.inbound(inboundForm.value); dialogVisible.value = false; loadData() }

onMounted(() => { loadData(); loadProducts() })
</script>
