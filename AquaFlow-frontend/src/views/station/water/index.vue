<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">水类型管理</span>
          <el-button type="primary" @click="showAdd">新增水类型</el-button>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="spec" label="规格" />
        <el-table-column prop="price" label="单价" width="100">
          <template #default="{ row }">
            <span v-if="row.price">¥{{ row.price }}</span>
            <span v-else class="text-muted">未设</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="showEdit(row)">编辑</el-button>
            <el-popconfirm title="确定删除？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑水类型' : '新增水类型'" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如：农夫山泉" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.spec" placeholder="如：18.9L" /></el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.price" :min="0" :precision="2" placeholder="元/桶" /></el-form-item>
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
import { waterTypeApi } from '../../../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const form = ref({ name: '', spec: '', price: null, note: '' })

const loadData = async () => {
  loading.value = true
  try { list.value = await waterTypeApi.list() } finally { loading.value = false }
}

const showAdd = () => {
  isEdit.value = false
  editId.value = null
  form.value = { name: '', spec: '', price: null, note: '' }
  dialogVisible.value = true
}

const showEdit = (row) => {
  isEdit.value = true
  editId.value = row.id
  form.value = { name: row.name, spec: row.spec, price: row.price, note: row.note || '' }
  dialogVisible.value = true
}

const submit = async () => {
  if (isEdit.value) {
    await waterTypeApi.update(editId.value, form.value)
  } else {
    await waterTypeApi.save(form.value)
  }
  dialogVisible.value = false
  loadData()
}

const handleDelete = async (id) => {
  await waterTypeApi.delete(id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>
