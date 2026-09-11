<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">退桶审批</span>
          <span></span>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="customerId" label="客户ID" width="80" />
        <el-table-column prop="quantity" label="退回数量" width="100" />
        <el-table-column prop="depositRefund" label="退押金" width="100">
          <template #default="{ row }">
            <span v-if="row.depositRefund">¥{{ row.depositRefund }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 1" type="warning">待处理</el-tag>
            <el-tag v-else-if="row.status === 2" type="success">已确认</el-tag>
            <el-tag v-else-if="row.status === 3" type="primary">已退押金</el-tag>
            <el-tag v-else-if="row.status === 4" type="danger">已驳回</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="客户备注" show-overflow-tooltip />
        <el-table-column label="欠桶提醒" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.owedBuckets > 0" type="danger" size="small">
              欠 {{ row.owedBuckets }} 桶，不可退
            </el-tag>
            <el-tag v-else type="success" size="small">无欠桶</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="handleNote" label="处理备注" show-overflow-tooltip />
        <el-table-column prop="createTime" label="申请时间" width="160" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 1">
              <el-button size="small" type="success" @click="handleAction(row.id, 2, '确认收到空桶', row)">确认</el-button>
              <el-button size="small" type="primary" @click="handleAction(row.id, 3, '押金已退还', row)">退押金</el-button>
              <el-button size="small" type="danger" @click="showReject(row)">驳回</el-button>
            </template>
            <span v-else class="text-muted">已处理</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="rejectDialog" title="驳回退桶申请" width="400px">
      <el-form label-width="80px">
        <el-form-item label="驳回原因">
          <el-input v-model="rejectNote" type="textarea" :rows="3" placeholder="请输入驳回原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { barrelApi } from '../../../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const loading = ref(false)
const rejectDialog = ref(false)
const rejectId = ref(null)
const rejectNote = ref('')

const loadData = async () => {
  loading.value = true
  try { list.value = await barrelApi.allRecords() } finally { loading.value = false }
}

const handleAction = async (id, status, handleNote, row) => {
  // 退押金前若客户有欠桶，二次确认提醒
  if (status === 3 && row && row.owedBuckets > 0) {
    try {
      await ElMessageBox.confirm(
        `该客户当前欠 ${row.owedBuckets} 个空桶未归还。存在欠桶时不允许退桶，请先让客户归还欠桶。是否继续？`,
        '欠桶提醒',
        { confirmButtonText: '仍要处理', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }
  await barrelApi.handleReturn(id, status, handleNote)
  ElMessage.success('处理成功')
  loadData()
}

const showReject = (row) => {
  rejectId.value = row.id
  rejectNote.value = ''
  rejectDialog.value = true
}

const confirmReject = async () => {
  if (!rejectNote.value.trim()) {
    ElMessage.warning('请输入驳回原因')
    return
  }
  await barrelApi.handleReturn(rejectId.value, 4, rejectNote.value)
  rejectDialog.value = false
  ElMessage.success('已驳回')
  loadData()
}

onMounted(loadData)
</script>
