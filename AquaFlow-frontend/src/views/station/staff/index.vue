<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">员工管理</span>
          <el-button type="primary" @click="showAdd">新增员工</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="phone" label="电话" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'STATION_MANAGER' ? 'warning' : row.role === 'FACTORY_ADMIN' ? 'danger' : 'success'">
              {{ roleMap[row.role] || row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stationId" label="所属水站" width="100" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '在职' : '离职' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" @click="showEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑员工' : '新增员工'" width="480px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="姓名"><el-input v-model="form.name" placeholder="请输入姓名" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" placeholder="请输入电话" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" placeholder="请选择角色">
            <el-option value="STATION_MANAGER" label="站长" />
            <el-option value="DELIVERY" label="配送员" />
            <el-option value="FACTORY_ADMIN" label="水厂管理员" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属水站"><el-input-number v-model="form.stationId" :min="1" placeholder="水站ID" /></el-form-item>
        <el-form-item label="所属水厂"><el-input-number v-model="form.factoryId" :min="1" placeholder="水厂ID" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option :value="1" label="在职" />
            <el-option :value="2" label="离职" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { staffApi } from '../../../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const roleMap = { STATION_MANAGER: '站长', DELIVERY: '配送员', FACTORY_ADMIN: '水厂管理员' }
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({ name: '', phone: '', role: 'DELIVERY', stationId: 1, factoryId: 1, status: 1 })

const loadData = async () => {
  loading.value = true
  try { list.value = await staffApi.list() } finally { loading.value = false }
}
const showAdd = () => { isEdit.value = false; form.value = { name: '', phone: '', role: 'DELIVERY', stationId: 1, factoryId: 1, status: 1 }; dialogVisible.value = true }
const showEdit = (row) => { isEdit.value = true; form.value = { ...row }; dialogVisible.value = true }
const handleDelete = async (id) => {
  await ElMessageBox.confirm('确定删除该员工？', '提示', { type: 'warning' })
  await staffApi.delete(id)
  ElMessage.success('删除成功')
  loadData()
}
const submit = async () => {
  if (isEdit.value) { await staffApi.update(form.value.id, form.value) }
  else { await staffApi.save(form.value) }
  dialogVisible.value = false
  ElMessage.success(isEdit.value ? '更新成功' : '新增成功')
  loadData()
}

onMounted(loadData)
</script>
