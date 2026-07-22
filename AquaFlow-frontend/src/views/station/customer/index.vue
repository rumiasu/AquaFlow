<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="page-header">
          <span class="page-title">客户管理</span>
          <el-button type="success" :icon="Plus" @click="showAdd">新增客户</el-button>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading" empty-text="暂无客户数据">
        <el-table-column prop="id" label="ID" width="60" align="center" />
        <el-table-column prop="name" label="客户名" min-width="100">
          <template #default="{ row }">
            <span class="text-bold">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="totalOrders" label="订单数" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.totalOrders || 0 }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalConsumption" label="累计消费" width="100" align="right">
          <template #default="{ row }">
            <span v-if="row.totalConsumption">¥{{ Number(row.totalConsumption).toFixed(0) }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="avgCycleDays" label="订水周期" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.avgCycleDays">{{ row.avgCycleDays }}天</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastDeliveryTime" label="最近配送" width="155">
          <template #default="{ row }">
            <span v-if="row.lastDeliveryTime">{{ row.lastDeliveryTime }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="155" />
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="showEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑客户' : '新增客户'" width="450px" destroy-on-close>
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="客户名" prop="name">
          <el-input v-model="form.name" placeholder="请输入客户名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="3" placeholder="备注信息" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { customerApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)
const form = ref({ name: '', phone: '', note: '' })

const formRules = {
  name: [{ required: true, message: '请输入客户名', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }]
}

const loadData = async () => {
  loading.value = true
  try { list.value = await customerApi.list() } finally { loading.value = false }
}

const showAdd = () => {
  isEdit.value = false
  form.value = { name: '', phone: '', note: '' }
  dialogVisible.value = true
}

const showEdit = (row) => {
  isEdit.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

const submit = async () => {
  if (formRef.value) {
    try { await formRef.value.validate() } catch { return }
  }
  submitting.value = true
  try {
    if (isEdit.value) { await customerApi.update(form.value.id, form.value) }
    else { await customerApi.save(form.value) }
    ElMessage.success(isEdit.value ? '修改成功' : '添加成功')
    dialogVisible.value = false
    loadData()
  } finally { submitting.value = false }
}

onMounted(loadData)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-title { font-size: 16px; font-weight: 600; }
</style>
