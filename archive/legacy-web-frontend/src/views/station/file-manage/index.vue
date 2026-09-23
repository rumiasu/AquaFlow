<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">文件管理</span>
          <el-upload
            :show-file-list="false"
            :http-request="handleUpload"
            accept="image/*,video/*,.pdf,.doc,.docx"
            multiple>
            <el-button type="primary">上传文件</el-button>
          </el-upload>
        </div>
      </template>

      <div v-loading="loading">
        <el-empty v-if="!loading && list.length === 0" description="暂无文件" />
        <div v-else class="file-grid">
          <div v-for="item in list" :key="item.id" class="file-card">
            <div class="file-preview">
              <el-image v-if="item.fileType === 'image'" :src="item.url" fit="cover" class="preview-img" :preview-src-list="[item.url]" />
              <video v-else-if="item.fileType === 'video'" :src="item.url" class="preview-img" controls />
              <div v-else class="file-icon">
                <el-icon :size="40"><Document /></el-icon>
              </div>
            </div>
            <div class="file-info">
              <div class="file-name" :title="item.fileName">{{ item.fileName }}</div>
              <div class="file-meta">
                <span>{{ formatSize(item.fileSize) }}</span>
                <span>{{ item.createTime }}</span>
              </div>
            </div>
            <div class="file-actions">
              <el-button link type="primary" size="small" @click="copyUrl(item.url)">复制链接</el-button>
              <el-popconfirm title="确定删除？" @confirm="handleDelete(item.id)">
                <template #reference>
                  <el-button link type="danger" size="small">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { fileApi } from '../../../api'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'

const list = ref([])
const loading = ref(false)

const loadData = async () => {
  loading.value = true
  try { list.value = await fileApi.list() } finally { loading.value = false }
}

const handleUpload = async ({ file }) => {
  try {
    await fileApi.upload(file)
    ElMessage.success('上传成功')
    loadData()
  } catch (e) {
    ElMessage.error('上传失败')
  }
}

const handleDelete = async (id) => {
  await fileApi.delete(id)
  ElMessage.success('删除成功')
  loadData()
}

const copyUrl = (url) => {
  navigator.clipboard.writeText(url)
  ElMessage.success('链接已复制')
}

const formatSize = (bytes) => {
  if (!bytes) return '0B'
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / 1024 / 1024).toFixed(1) + 'MB'
}

onMounted(loadData)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.file-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
.file-card { border: 1px solid #ebeef5; border-radius: 8px; overflow: hidden; }
.file-preview { height: 160px; background: #f5f7fa; display: flex; align-items: center; justify-content: center; }
.preview-img { width: 100%; height: 100%; object-fit: cover; }
.file-icon { color: #909399; }
.file-info { padding: 8px 12px; }
.file-name { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-meta { font-size: 12px; color: #909399; display: flex; justify-content: space-between; margin-top: 4px; }
.file-actions { padding: 4px 12px 8px; display: flex; justify-content: space-between; }
</style>
