<template>
  <div class="image-upload">
    <el-upload
      ref="uploadRef"
      :auto-upload="false"
      :show-file-list="false"
      :on-change="handleChange"
      accept="image/jpeg,image/png,image/gif,image/webp"
      :limit="1"
    >
      <template #trigger>
        <el-button type="primary" :loading="uploading" :disabled="uploading">
          <el-icon style="margin-right:4px"><Upload /></el-icon>{{ uploading ? '上传中...' : '选择图片' }}
        </el-button>
      </template>
    </el-upload>

    <div v-if="previewUrl" class="preview-wrapper">
      <el-image :src="previewUrl" fit="cover" class="preview-img" />
      <div class="preview-actions">
        <el-button size="small" type="success" @click="$emit('confirm', previewUrl)">
          <el-icon><Check /></el-icon> 确认
        </el-button>
        <el-button size="small" @click="clearPreview">
          <el-icon><Close /></el-icon> 取消
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Upload, Check, Close } from '@element-plus/icons-vue'
import { uploadApi } from '../api'

const emit = defineEmits(['confirm'])
const uploadRef = ref(null)
const previewUrl = ref('')
const uploading = ref(false)

const handleChange = async (uploadFile) => {
  const file = uploadFile.raw
  if (!file) return

  // 本地预览
  previewUrl.value = URL.createObjectURL(file)
}

const uploadFile = async (file) => {
  uploading.value = true
  try {
    const url = await uploadApi.upload(file)
    previewUrl.value = ''
    return url
  } finally {
    uploading.value = false
  }
}

const clearPreview = () => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}
</script>

<style scoped>
.image-upload { display: flex; flex-direction: column; gap: 12px; }
.preview-wrapper { position: relative; width: 200px; }
.preview-img { width: 200px; height: 200px; border-radius: 8px; border: 1px solid #dcdfe6; }
.preview-actions { display: flex; gap: 8px; margin-top: 8px; }
</style>
