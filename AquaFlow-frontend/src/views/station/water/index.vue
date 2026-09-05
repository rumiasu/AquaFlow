<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">商品管理</span>
          <el-button type="primary" @click="showAdd">新增商品</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe style="width:100%">
        <el-table-column label="图片" width="90">
          <template #default="{ row }">
            <el-image v-if="row.imageUrl" :src="row.imageUrl" fit="cover" class="table-img" @click="handleImageClick(row)" />
            <el-button v-else text size="small" @click="handleImageClick(row)">上传</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column prop="brand" label="品牌" width="110" />
        <el-table-column prop="spec" label="规格" width="100" />
        <el-table-column prop="price" label="售价" width="90">
          <template #default="{ row }">¥{{ row.price }}</template>
        </el-table-column>
        <el-table-column prop="deposit" label="押金" width="80">
          <template #default="{ row }">¥{{ row.deposit || 0 }}</template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="60" align="center" />
        <el-table-column prop="status" label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.status === 1" @change="(val) => toggleStatus(row, val)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="showEdit(row)">编辑</el-button>
            <el-popconfirm title="确定删除？" @confirm="handleDelete(row.id)">
              <template #reference><el-button text type="danger" size="small">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" description="暂无商品" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑商品' : '新增商品'" width="500px" destroy-on-close>
      <el-form :model="form" label-width="80px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="商品名称"><el-input v-model="form.name" placeholder="如：农夫山泉18.9L" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="品牌"><el-input v-model="form.brand" placeholder="如：农夫山泉" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="规格"><el-input v-model="form.spec" placeholder="如：18.9L" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="售价(元)"><el-input-number v-model="form.price" :min="0" :precision="2" style="width:100%" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="押金(元)"><el-input-number v-model="form.deposit" :min="0" :precision="2" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" style="width:100%" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="上下架">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="上架" inactive-text="下架" />
        </el-form-item>
        <el-form-item label="商品图片">
          <div class="form-image">
            <el-image v-if="form.imageUrl" :src="form.imageUrl" fit="cover" class="form-image-preview" />
            <el-upload :show-file-list="false" :http-request="handleFormUpload" accept="image/jpeg,image/png,image/gif,image/webp">
              <el-button type="primary" plain :loading="uploading">
                {{ uploading ? '上传中...' : form.imageUrl ? '更换图片' : '选择图片' }}
              </el-button>
            </el-upload>
            <el-button v-if="form.imageUrl" type="danger" plain size="small" @click="form.imageUrl = ''">清除</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <input ref="fileInputRef" type="file" accept="image/jpeg,image/png,image/gif,image/webp" style="display:none" @change="onFileSelected" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { productApi, uploadApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const submitting = ref(false)
const uploading = ref(false)
const fileInputRef = ref(null)
const pendingImageId = ref(null)
const form = ref({ name: '', brand: '', spec: '', price: null, deposit: 0, imageUrl: '', sort: 0, status: 1 })

const loadData = async () => {
  loading.value = true
  try { list.value = await productApi.list() } finally { loading.value = false }
}

const showAdd = () => {
  isEdit.value = false
  editId.value = null
  form.value = { name: '', brand: '', spec: '', price: null, deposit: 0, imageUrl: '', sort: 0, status: 1 }
  dialogVisible.value = true
}

const showEdit = (row) => {
  isEdit.value = true
  editId.value = row.id
  form.value = {
    name: row.name, brand: row.brand || '', spec: row.spec, price: row.price,
    deposit: row.deposit || 0, imageUrl: row.imageUrl || '', sort: row.sort ?? 0, status: row.status ?? 1
  }
  dialogVisible.value = true
}

const submit = async () => {
  submitting.value = true
  try {
    if (isEdit.value) {
      await productApi.update(editId.value, form.value)
    } else {
      await productApi.save(form.value)
    }
    dialogVisible.value = false
    ElMessage.success(isEdit.value ? '修改成功' : '新增成功')
    loadData()
  } finally { submitting.value = false }
}

const handleDelete = async (id) => {
  await productApi.delete(id)
  ElMessage.success('删除成功')
  loadData()
}

const toggleStatus = async (item, on) => {
  const status = on ? 1 : 0
  await productApi.update(item.id, { ...item, status })
  item.status = status
  ElMessage.success(status === 1 ? '已上架' : '已下架')
}

// 通过通用上传接口上传图片，返回临时 URL
const uploadImage = async (file) => {
  const formData = new FormData()
  formData.append('file', file)
  const res = await uploadApi.upload(file)
  return res
}

// 表格中点击图片 → 上传新图片并更新商品
const handleImageClick = (item) => {
  pendingImageId.value = item.id
  fileInputRef.value.click()
}

const onFileSelected = async (e) => {
  const file = e.target.files[0]
  if (!file) return
  uploading.value = true
  try {
    const url = await uploadImage(file)
    await productApi.update(pendingImageId.value, { imageUrl: url })
    ElMessage.success('图片上传成功')
    loadData()
  } catch {
    ElMessage.error('图片上传失败')
  } finally {
    uploading.value = false
    fileInputRef.value.value = ''
  }
}

// 表单中上传图片（先上传，提交时随表单一起保存）
const handleFormUpload = async ({ file }) => {
  uploading.value = true
  try {
    const url = await uploadImage(file)
    form.value.imageUrl = url
    ElMessage.success('图片上传成功')
  } catch {
    ElMessage.error('图片上传失败')
  } finally {
    uploading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.table-img { width: 56px; height: 56px; border-radius: 6px; object-fit: cover; cursor: pointer; }
.form-image { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.form-image-preview { width: 80px; height: 80px; border-radius: 6px; border: 1px solid #dcdfe6; object-fit: cover; }
:deep(.el-table .cell) { white-space: nowrap; }
</style>
