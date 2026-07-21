<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>水厂管理</span>
          <el-button type="primary" @click="showAdd">新增水厂</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="水厂名称" />
        <el-table-column prop="contactPerson" label="联系人" />
        <el-table-column prop="contactPhone" label="联系电话" />
        <el-table-column prop="address" label="地址" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '营业' : '停业' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" @click="showEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑水厂' : '新增水厂'" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="水厂名称"><el-input v-model="form.name" placeholder="请输入水厂名称" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactPerson" placeholder="请输入联系人" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.contactPhone" placeholder="请输入联系电话" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" placeholder="请输入地址" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" placeholder="请选择状态">
            <el-option :value="1" label="营业" />
            <el-option :value="2" label="停业" />
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
import { factoryApi } from '../../api'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({ name: '', contactPerson: '', contactPhone: '', address: '', status: 1 })

const loadData = async () => {
  loading.value = true
  try { list.value = await factoryApi.list() } finally { loading.value = false }
}
const showAdd = () => { isEdit.value = false; form.value = { name: '', contactPerson: '', contactPhone: '', address: '', status: 1 }; dialogVisible.value = true }
const showEdit = (row) => { isEdit.value = true; form.value = { ...row }; dialogVisible.value = true }
const submit = async () => {
  if (isEdit.value) { await factoryApi.update(form.value.id, form.value) }
  else { await factoryApi.save(form.value) }
  dialogVisible.value = false
  loadData()
}

onMounted(loadData)
</script>
