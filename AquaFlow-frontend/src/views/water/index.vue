<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>水类型管理</span>
          <el-button type="primary" @click="showAdd">新增水类型</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="spec" label="规格" />
        <el-table-column prop="note" label="备注" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="160" />
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增水类型" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如：农夫山泉" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.spec" placeholder="如：18.9L" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="2" /></el-form-item>
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
import { waterTypeApi } from '../../api'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref({ name: '', spec: '', note: '' })

const loadData = async () => {
  loading.value = true
  try { list.value = await waterTypeApi.list() } finally { loading.value = false }
}
const showAdd = () => { form.value = { name: '', spec: '', note: '' }; dialogVisible.value = true }
const submit = async () => { await waterTypeApi.save(form.value); dialogVisible.value = false; loadData() }

onMounted(loadData)
</script>
