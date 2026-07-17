<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>客户管理</span>
          <el-button type="primary" @click="showAdd">新增客户</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="客户名" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column prop="note" label="备注" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" @click="showEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑客户' : '新增客户'" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="客户名"><el-input v-model="form.name" placeholder="请输入客户名" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" placeholder="请输入手机号" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="3" placeholder="备注信息" /></el-form-item>
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
import { customerApi } from '../../api'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({ name: '', phone: '', note: '' })

const loadData = async () => {
  loading.value = true
  try { list.value = await customerApi.list() } finally { loading.value = false }
}
const showAdd = () => { isEdit.value = false; form.value = { name: '', phone: '', note: '' }; dialogVisible.value = true }
const showEdit = (row) => { isEdit.value = true; form.value = { ...row }; dialogVisible.value = true }
const submit = async () => {
  if (isEdit.value) { await customerApi.update(form.value.id, form.value) }
  else { await customerApi.save(form.value) }
  dialogVisible.value = false
  loadData()
}

onMounted(loadData)
</script>
