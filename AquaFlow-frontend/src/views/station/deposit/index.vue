<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">押金管理</span>
          <div class="header-actions">
            <el-input v-model="customerId" placeholder="客户ID" style="width: 120px" @keyup.enter="loadData" />
            <el-button type="primary" @click="loadData">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="records" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="customerId" label="客户ID" width="80" />
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.type === 1" type="success">充值</el-tag>
            <el-tag v-else-if="row.type === 2" type="primary">退还</el-tag>
            <el-tag v-else-if="row.type === 3" type="warning">扣除</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="120">
          <template #default="{ row }">
            <span :class="row.type === 1 ? 'text-success' : 'text-danger'">
              {{ row.type === 1 ? '+' : '-' }}¥{{ row.amount }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { depositRecordApi } from '../../../api'

const customerId = ref('')
const records = ref([])
const loading = ref(false)

const loadData = async () => {
  if (!customerId.value) return
  loading.value = true
  try {
    records.value = await depositRecordApi.list(customerId.value) || []
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>
