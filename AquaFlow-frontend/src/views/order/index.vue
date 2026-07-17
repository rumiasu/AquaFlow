<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>订单列表</span>
          <div style="display: flex; align-items: center; gap: 10px;">
            <el-select v-model="query.status" style="width: 120px;">
              <el-option label="待组批" :value="1" />
              <el-option label="已组批" :value="4" />
              <el-option label="配送中" :value="2" />
              <el-option label="已完成" :value="3" />
            </el-select>
            <el-input v-model="query.tag" placeholder="地址标签" clearable style="width: 120px;" />
            <el-date-picker v-model="dateRange" type="daterange" range-separator="~" start-placeholder="开始日期"
              end-placeholder="结束日期" value-format="YYYY-MM-DD" style="width: 260px;" @change="handleDateChange" />
            <el-button @click="loadData">查询</el-button>
            <el-button type="primary" @click="showAdd">新增订单</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="addressDetail" label="地址" show-overflow-tooltip />
        <el-table-column prop="waterTypeName" label="水类型" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="source" label="来源" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="[null,'','success','warning'][row.source]">{{ { 1: '电话', 2: '微信群', 3: '小程序' }[row.source] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="['','warning','','success','info'][row.status]">{{ { 1: '待组批', 2: '配送中', 3: '已完成', 4: '已组批' }[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" />
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增订单" width="450px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="客户">
          <el-select v-model="form.customerId" placeholder="选择客户" filterable @change="onCustomerChange">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址">
          <el-select v-model="form.addressId" placeholder="选择地址" filterable
            :disabled="!form.customerId" :no-data-text="form.customerId ? '该客户暂无地址，请先在地址管理中添加' : '请先选择客户'">
            <el-option v-for="a in customerAddresses" :key="a.id" :label="a.detail" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="水类型">
          <el-select v-model="form.waterTypeId" placeholder="选择水类型">
            <el-option v-for="w in waterTypes" :key="w.id" :label="`${w.name} ${w.spec}`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="form.quantity" :min="1" /></el-form-item>
        <el-form-item label="来源">
          <el-select v-model="form.source">
            <el-option label="电话" :value="1" />
            <el-option label="微信群" :value="2" />
            <el-option label="小程序" :value="3" />
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
import { ref, computed, onMounted } from 'vue'
import { orderApi, customerApi, addressApi, waterTypeApi } from '../../api'

const list = ref([])
const loading = ref(false)
const customers = ref([])
const addresses = ref([])
const waterTypes = ref([])
const query = ref({ status: 1, tag: '', createTimeStart: '', createTimeEnd: '' })
const dateRange = ref(null)
const dialogVisible = ref(false)
const form = ref({ customerId: null, addressId: null, waterTypeId: null, quantity: 1, source: 1 })

const customerAddresses = computed(() => {
  if (!form.value.customerId) return []
  return addresses.value.filter(a => a.customerId === form.value.customerId)
})

const handleDateChange = (val) => {
  query.value.createTimeStart = val?.[0] || ''
  query.value.createTimeEnd = val?.[1] || ''
}

const loadData = async () => {
  loading.value = true
  try { list.value = await orderApi.list(query.value) } finally { loading.value = false }
}
const loadOptions = async () => {
  customers.value = await customerApi.list()
  addresses.value = await addressApi.list({})
  waterTypes.value = await waterTypeApi.list()
}
const showAdd = () => {
  form.value = { customerId: null, addressId: null, waterTypeId: null, quantity: 1, source: 1 }
  dialogVisible.value = true
}
const onCustomerChange = () => {
  form.value.addressId = null
  // 自动选中该客户的唯一地址
  const addrs = addresses.value.filter(a => a.customerId === form.value.customerId)
  if (addrs.length === 1) form.value.addressId = addrs[0].id
}
const submit = async () => { await orderApi.save(form.value); dialogVisible.value = false; loadData() }

onMounted(() => { loadData(); loadOptions() })
</script>
