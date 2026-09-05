<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">水票管理</span>
          <div class="header-actions">
            <el-input v-model="customerId" placeholder="客户ID" style="width: 120px" @keyup.enter="loadData" />
            <el-button type="primary" @click="loadData">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="tickets" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="customerId" label="客户ID" width="80" />
        <el-table-column prop="productName" label="商品" />
        <el-table-column prop="productSpec" label="规格" />
        <el-table-column prop="remainQuantity" label="剩余张数" width="100" />
      </el-table>
    </el-card>

    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">水票流水</span>
          <el-button type="primary" size="small" @click="showAddTicket">发放水票</el-button>
        </div>
      </template>
      <el-table :data="records" border stripe v-loading="recordsLoading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="customerId" label="客户ID" width="80" />
        <el-table-column prop="productName" label="商品" />
        <el-table-column prop="increaseQty" label="发放" width="80">
          <template #default="{ row }">
            <span v-if="row.increaseQty > 0" class="text-success">+{{ row.increaseQty }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="decreaseQty" label="消耗" width="80">
          <template #default="{ row }">
            <span v-if="row.decreaseQty > 0" class="text-danger">-{{ row.decreaseQty }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
    </el-card>

    <el-dialog v-model="addDialog" title="发放水票" width="420px">
      <el-form :model="addForm" label-width="80px">
        <el-form-item label="客户ID">
          <el-input-number v-model="addForm.customerId" :min="1" />
        </el-form-item>
        <el-form-item label="商品">
          <el-select v-model="addForm.productId" placeholder="选择商品">
            <el-option v-for="p in products" :key="p.id" :label="p.name + ' ' + p.spec" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="addForm.quantity" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" @click="submitAdd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ticketApi, ticketRecordApi, productApi } from '../../../api'
import { ElMessage } from 'element-plus'

const customerId = ref('')
const tickets = ref([])
const records = ref([])
const products = ref([])
const loading = ref(false)
const recordsLoading = ref(false)
const addDialog = ref(false)
const addForm = ref({ customerId: 1, productId: null, quantity: 1 })

const loadData = async () => {
  if (!customerId.value) return
  loading.value = true
  recordsLoading.value = true
  try {
    const [t, r] = await Promise.all([
      ticketApi.list(customerId.value),
      ticketRecordApi.list(customerId.value)
    ])
    tickets.value = t || []
    records.value = r || []
  } finally {
    loading.value = false
    recordsLoading.value = false
  }
}

const showAddTicket = async () => {
  addForm.value = { customerId: parseInt(customerId.value) || 1, productId: null, quantity: 1 }
  if (products.value.length === 0) {
    products.value = await productApi.list()
  }
  addDialog.value = true
}

const submitAdd = async () => {
  await ticketApi.add(addForm.value)
  addDialog.value = false
  ElMessage.success('发放成功')
  loadData()
}

onMounted(loadData)
</script>
