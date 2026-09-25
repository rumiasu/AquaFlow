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
        <el-table-column prop="name" label="姓名" min-width="100">
          <template #default="{ row }">
            <span class="text-bold">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="130" />
        <el-table-column prop="totalOrders" label="订单数" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.totalOrders || 0 }}</el-tag>
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
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="showOrders(row)">订单</el-button>
            <el-button size="small" type="warning" plain @click="showAddresses(row)">地址</el-button>
            <el-button size="small" type="info" plain @click="showAssets(row)">桶资产</el-button>
            <el-button size="small" type="success" plain @click="showTickets(row)">水票</el-button>
            <el-button size="small" @click="showEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑客户' : '新增客户'" width="450px" destroy-on-close>
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
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

    <el-dialog v-model="ordersVisible" title="客户订单" width="760px" destroy-on-close>
      <el-table :data="customerOrders" border stripe size="small" empty-text="暂无订单">
        <el-table-column prop="id" label="订单号" width="80" />
        <el-table-column prop="productName" label="商品" width="120" />
        <el-table-column prop="quantity" label="数量" width="70" align="center" />
        <el-table-column prop="addressDetail" label="地址" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="{1:'warning',2:'primary',3:'success',4:'info',5:'danger'}[row.status] || 'info'">
              {{ {1:'待配送',2:'配送中',3:'已送达',4:'已完成',5:'已取消'}[row.status] || '未知' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="155" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="addressesVisible" title="客户地址列表" width="640px" destroy-on-close>
      <el-table :data="customerAddresses" border stripe size="small" empty-text="暂无地址">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="detail" label="地址详情" show-overflow-tooltip />
        <el-table-column prop="receiverName" label="收货人" width="100" />
        <el-table-column prop="receiverPhone" label="联系电话" width="130" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="assetsVisible" title="桶资产明细" width="640px" destroy-on-close>
      <el-table :data="customerAssets" border stripe size="small" empty-text="暂无资产记录">
        <el-table-column prop="id" label="记录ID" width="80" />
        <el-table-column prop="productName" label="商品" width="140" />
        <el-table-column prop="change" label="变动" width="80" align="center">
          <template #default="{ row }">
            <span :style="{ color: (row.change || 0) > 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">
              {{ (row.change || 0) > 0 ? '+' : '' }}{{ row.change || 0 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="balance" label="结余" width="80" align="center" />
        <el-table-column prop="note" label="说明" min-width="140" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="155" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="ticketsVisible" title="水票明细" width="720px" destroy-on-close>
      <el-tabs v-model="ticketTab">
        <el-tab-pane label="持有水票" name="hold">
          <el-table :data="customerTickets" border stripe size="small" empty-text="暂无水票">
            <el-table-column prop="productName" label="商品" min-width="140" />
            <el-table-column prop="totalQty" label="总票数" width="90" align="center" />
            <el-table-column prop="usedQty" label="已用" width="80" align="center" />
            <el-table-column prop="remainQty" label="剩余" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="success" effect="dark">{{ row.remainQty || 0 }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="水票流水" name="record">
          <el-table :data="ticketRecords" border stripe size="small" empty-text="暂无流水">
            <el-table-column prop="id" label="流水ID" width="80" />
            <el-table-column prop="productName" label="商品" min-width="140" />
            <el-table-column prop="change" label="变动" width="80" align="center">
              <template #default="{ row }">
                <span :style="{ color: (row.change || 0) > 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">
                  {{ (row.change || 0) > 0 ? '+' : '' }}{{ row.change || 0 }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="140" show-overflow-tooltip />
            <el-table-column prop="createTime" label="时间" width="155" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { customerApi, orderApi, addressApi, depositRecordApi, ticketApi, ticketRecordApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)
const form = ref({ name: '', phone: '', note: '' })

const ordersVisible = ref(false)
const addressesVisible = ref(false)
const assetsVisible = ref(false)
const ticketsVisible = ref(false)
const customerOrders = ref([])
const customerAddresses = ref([])
const customerAssets = ref([])
const customerTickets = ref([])
const ticketRecords = ref([])
const ticketTab = ref('hold')
const currentCustomerId = ref(null)

const formRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入电话', trigger: 'blur' }]
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

const showOrders = async (row) => {
  currentCustomerId.value = row.id
  customerOrders.value = []
  ordersVisible.value = true
  try {
    const all = await orderApi.list({})
    customerOrders.value = (all || []).filter(o => String(o.customerId) === String(row.id))
  } catch {}
}

const showAddresses = async (row) => {
  currentCustomerId.value = row.id
  customerAddresses.value = []
  addressesVisible.value = true
  try {
    const all = await addressApi.list({})
    customerAddresses.value = (all || []).filter(a => String(a.customerId) === String(row.id))
  } catch {}
}

const showAssets = async (row) => {
  currentCustomerId.value = row.id
  customerAssets.value = []
  assetsVisible.value = true
  try {
    customerAssets.value = await depositRecordApi.list(row.id) || []
  } catch {}
}

const showTickets = async (row) => {
  currentCustomerId.value = row.id
  customerTickets.value = []
  ticketRecords.value = []
  ticketTab.value = 'hold'
  ticketsVisible.value = true
  try {
    customerTickets.value = await ticketApi.list(row.id) || []
    ticketRecords.value = await ticketRecordApi.list(row.id) || []
  } catch {}
}

onMounted(loadData)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-title { font-size: 16px; font-weight: 600; }
</style>
