<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="page-header">
          <span class="page-title">订单管理</span>
          <div class="header-actions">
            <el-select v-model="query.status" placeholder="订单状态" clearable style="width: 120px;">
              <el-option label="待配送" :value="1" />
              <el-option label="配送中" :value="2" />
              <el-option label="已送达" :value="3" />
              <el-option label="已完成" :value="4" />
              <el-option label="已取消" :value="5" />
            </el-select>
            <el-select v-model="query.paymentStatus" placeholder="付款状态" clearable style="width: 120px;">
              <el-option label="未付款" :value="0" />
              <el-option label="待收款" :value="1" />
              <el-option label="已付款" :value="2" />
              <el-option label="已退款" :value="3" />
              <el-option label="已取消" :value="4" />
            </el-select>
            <el-input v-model="query.keyword" placeholder="客户/电话搜索" clearable style="width: 160px;" />
            <el-date-picker v-model="dateRange" type="daterange" range-separator="~"
              start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD"
              style="width: 250px;" @change="handleDateChange" />
            <el-button type="primary" :icon="Search" @click="loadData">查询</el-button>
            <el-button type="success" :icon="Plus" @click="showAdd">新增订单</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading" empty-text="暂无订单数据">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="customerName" label="客户" min-width="100" />
        <el-table-column prop="addressDetail" label="地址" min-width="160" show-overflow-tooltip />
        <el-table-column prop="productName" label="商品" width="110" />
        <el-table-column prop="quantity" label="数量" width="70" align="center" />
        <el-table-column prop="source" label="来源" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="sourceTagType(row.source)" effect="plain">{{ sourceText(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="订单状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="paymentStatus" label="付款" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="payTagType(row.paymentStatus)" effect="dark">{{ payText(row.paymentStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="155" />
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canCancel(row)" size="small" type="danger" plain @click="handleCancel(row)">取消</el-button>
            <el-button v-if="row.status === 3 && (!row.paymentMethod || row.paymentMethod !== 3)" size="small" type="warning" plain @click="handleUnconfirm(row)">修正收款</el-button>
            <el-button v-if="row.status === 1" size="small" type="danger" plain @click="handleReject(row)">拒单</el-button>
            <el-button size="small" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增订单" width="480px" destroy-on-close>
      <el-form :model="form" label-width="80px" :rules="formRules" ref="formRef">
        <el-form-item label="客户" prop="customerId">
          <el-select v-model="form.customerId" placeholder="选择客户" filterable @change="onCustomerChange" style="width: 100%;">
            <el-option v-for="c in customers" :key="c.id" :label="`${c.name} (${c.phone})`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址" prop="addressId">
          <el-select v-model="form.addressId" placeholder="选择地址" filterable
            :disabled="!form.customerId" :no-data-text="form.customerId ? '暂无地址，请先添加' : '请先选择客户'" style="width: 100%;">
            <el-option v-for="a in customerAddresses" :key="a.id" :label="a.detail" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品" prop="productId">
          <el-select v-model="form.productId" placeholder="选择商品" style="width: 100%;">
            <el-option v-for="w in products" :key="w.id" :label="`${w.name} ${w.spec}`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number v-model="form.quantity" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="来源" prop="source">
          <el-select v-model="form.source" style="width: 100%;">
            <el-option label="电话" :value="1" />
            <el-option label="微信群" :value="2" />
            <el-option label="小程序" :value="3" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="订单详情" width="680px" destroy-on-close @closed="clearOrderImages">
      <el-descriptions :column="2" border v-if="detailOrder">
        <el-descriptions-item label="订单ID">{{ detailOrder.id }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ detailOrder.customerName }}</el-descriptions-item>
        <el-descriptions-item label="归属站点ID" :span="1">
          <el-tag v-if="detailOrder.ownerStationId" size="small" type="success">#{{ detailOrder.ownerStationId }}</el-tag>
          <span v-else class="text-muted">-</span>
        </el-descriptions-item>
        <el-descriptions-item label="履约站点ID" :span="1">
          <el-tag v-if="detailOrder.deliveryStationId" size="small" type="warning">#{{ detailOrder.deliveryStationId }}</el-tag>
          <span v-else class="text-muted">-</span>
        </el-descriptions-item>
        <el-descriptions-item label="地址" :span="2">{{ detailOrder.addressDetail }}</el-descriptions-item>
        <el-descriptions-item label="商品">{{ detailOrder.productName || detailOrder.waterTypeName }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ detailOrder.quantity }} 桶</el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceText(detailOrder.source) }}</el-descriptions-item>
        <el-descriptions-item label="订单状态">
          <el-tag size="small" :type="statusTagType(detailOrder.status)">{{ statusText(detailOrder.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="付款状态">
          <el-tag size="small" :type="payTagType(detailOrder.paymentStatus)" effect="dark">{{ payText(detailOrder.paymentStatus) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="配送员">
          <span v-if="detailOrder.deliveryStaffName">{{ detailOrder.deliveryStaffName }}</span>
          <span v-else-if="detailOrder.deliveryStaffId">配送员{{ detailOrder.deliveryStaffId }}</span>
          <span v-else class="text-muted">未分配</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detailOrder.createTime }}</el-descriptions-item>
      </el-descriptions>

      <el-divider v-if="detailOrder" />

      <div v-if="detailOrder" class="order-images-section">
        <div class="section-label">订单图片</div>

        <div class="image-list" v-if="orderImages.length">
          <div v-for="img in orderImages" :key="img.id" class="image-item">
            <el-image :src="img.url" fit="cover" class="order-img" :preview-src-list="[img.url]" preview-teleported />
            <el-tag size="small" :type="img.type === 1 ? 'success' : 'danger'" class="image-tag">
              {{ img.type === 1 ? '正常' : '异常' }}
            </el-tag>
          </div>
        </div>

        <div class="upload-area">
          <el-upload
            ref="uploadRef"
            :http-request="handleUpload"
            :show-file-list="false"
            accept="image/jpeg,image/png,image/gif"
          >
            <el-button type="primary" :icon="Upload" :loading="uploading">
              {{ uploading ? '上传中...' : (uploadType === 1 ? '上传正常图片' : '上传异常图片') }}
            </el-button>
          </el-upload>
          <el-radio-group v-model="uploadType" size="small" style="margin-left: 8px;">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="2">异常</el-radio>
          </el-radio-group>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Upload } from '@element-plus/icons-vue'
import { orderApi, customerApi, addressApi, productApi, orderImageApi, deliveryApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const submitting = ref(false)
const customers = ref([])
const addresses = ref([])
const products = ref([])
const query = ref({ status: '', paymentStatus: '', keyword: '', createTimeStart: '', createTimeEnd: '' })
const dateRange = ref(null)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const detailOrder = ref(null)
const formRef = ref(null)
const form = ref({ customerId: null, addressId: null, productId: null, quantity: 1, source: 1 })

const formRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  addressId: [{ required: true, message: '请选择地址', trigger: 'change' }],
  productId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入数量', trigger: 'blur' }]
}

const customerAddresses = computed(() => {
  if (!form.value.customerId) return []
  return addresses.value.filter(a => a.customerId === form.value.customerId)
})

const statusText = (s) => ({ 1: '待配送', 2: '配送中', 3: '已送达', 4: '已完成', 5: '已取消' }[s] || '未知')
const statusTagType = (s) => ({ 1: 'warning', 2: 'primary', 3: 'success', 4: 'info', 5: 'danger' }[s] || 'info')
const sourceText = (s) => ({ 1: '电话', 2: '微信群', 3: '小程序' }[s] || '其他')
const sourceTagType = (s) => ({ 1: '', 2: 'success', 3: 'warning' }[s] || 'info')
const payText = (s) => ({ 0: '未付款', 1: '待收款', 2: '已付款', 3: '已退款', 4: '已取消' }[s] || '未知')
const payTagType = (s) => ({ 0: 'info', 1: 'warning', 2: 'success', 3: 'info', 4: 'info' }[s] || 'info')

const canCancel = (row) => row.status === 1 || row.status === 2 || row.status === 3

const handleDateChange = (val) => {
  query.value.createTimeStart = val?.[0] || ''
  query.value.createTimeEnd = val?.[1] || ''
}

const loadData = async () => {
  loading.value = true
  try {
    const params = { ...query.value }
    if (!params.status) delete params.status
    if (params.paymentStatus === '' || params.paymentStatus === null) delete params.paymentStatus
    let data = await orderApi.list(params) || []
    if (query.value.keyword) {
      const kw = String(query.value.keyword).trim().toLowerCase()
      data = data.filter(o =>
        (o.customerName && String(o.customerName).toLowerCase().includes(kw)) ||
        (o.customerPhone && String(o.customerPhone).includes(kw)) ||
        (o.receiverPhone && String(o.receiverPhone).includes(kw))
      )
    }
    list.value = data
  } finally { loading.value = false }
}

const loadOptions = async () => {
  customers.value = await customerApi.list()
  addresses.value = await addressApi.list({})
  products.value = await productApi.list()
}

const showAdd = () => {
  form.value = { customerId: null, addressId: null, productId: null, quantity: 1, source: 1 }
  dialogVisible.value = true
}

const onCustomerChange = () => {
  form.value.addressId = null
  const addrs = addresses.value.filter(a => a.customerId === form.value.customerId)
  if (addrs.length === 1) form.value.addressId = addrs[0].id
}

const submit = async () => {
  if (formRef.value) {
    try { await formRef.value.validate() } catch { return }
  }
  submitting.value = true
  try {
    const payload = { ...form.value, waterTypeId: form.value.productId }
    await orderApi.save(payload)
    ElMessage.success('订单创建成功')
    dialogVisible.value = false
    loadData()
  } finally { submitting.value = false }
}

const orderImages = ref([])
const uploadType = ref(1)
const uploading = ref(false)
const uploadRef = ref(null)

const showDetail = async (row) => {
  detailOrder.value = row
  detailVisible.value = true
  try {
    orderImages.value = await orderImageApi.listByOrder(row.id)
  } catch {
    orderImages.value = []
  }
}

const handleUpload = async ({ file }) => {
  uploading.value = true
  try {
    const url = await orderImageApi.upload(detailOrder.value.id, file, uploadType.value)
    ElMessage.success('图片上传成功')
    orderImages.value = await orderImageApi.listByOrder(detailOrder.value.id)
  } catch (e) {
    ElMessage.error('图片上传失败')
  } finally {
    uploading.value = false
  }
}

const clearOrderImages = () => {
  orderImages.value = []
}

const handleCancel = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定取消订单 #${row.id} 吗？取消后将自动释放库存、退水票、退款。`,
      '取消订单', { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '返回' }
    )
    await orderApi.cancel(row.id)
    ElMessage.success('订单已取消')
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('取消失败: ' + (e.message || '未知错误'))
  }
}

const handleUnconfirm = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定将订单 #${row.id} 改回"待收款"吗？`,
      '修正收款', { type: 'warning', confirmButtonText: '确定修正', cancelButtonText: '返回' }
    )
    await deliveryApi.unconfirmCollection(row.id)
    ElMessage.success('已改回待收款')
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('修正失败: ' + (e.message || '未知错误'))
  }
}

const handleReject = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入拒单原因', '拒单', {
      inputValue: '水站拒单',
      confirmButtonText: '确定拒单',
      cancelButtonText: '返回',
      inputPlaceholder: '请输入拒单原因'
    })
    await deliveryApi.rejectOrder(row.id, value || '水站拒单')
    ElMessage.success('已拒单并回池')
    loadData()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('拒单失败: ' + (e.message || '未知错误'))
  }
}

onMounted(() => { loadData(); loadOptions() })
</script>

<style scoped>
.order-images-section { margin-top: 4px; }
.section-label { font-weight: 500; margin-bottom: 8px; color: #303133; }
.image-list { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.image-item { position: relative; width: 120px; }
.order-img { width: 120px; height: 120px; border-radius: 6px; border: 1px solid #dcdfe6; }
.image-tag { position: absolute; top: 4px; right: 4px; }
.upload-area { display: flex; align-items: center; }
</style>
