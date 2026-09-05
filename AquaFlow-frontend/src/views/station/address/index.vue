<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span class="page-title">地址管理</span>
          <div class="header-actions">
            <el-button @click="$router.push('/address-map')">查看地图</el-button>
            <el-button type="primary" @click="showAdd">新增地址</el-button>
          </div>
        </div>
      </template>
      <div class="filter-bar">
        <el-select v-model="filterTag" placeholder="按标签筛选" clearable style="width: 150px;">
          <el-option label="小区" value="小区" />
          <el-option label="工厂" value="工厂" />
          <el-option label="写字楼" value="写字楼" />
          <el-option label="商场" value="商场" />
        </el-select>
        <el-input v-model="filterKeyword" placeholder="搜索地址" clearable style="width: 250px;" @keyup.enter="loadData" />
        <el-button @click="loadData">搜索</el-button>
      </div>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="客户" width="120">
          <template #default="{ row }">
            <span v-if="row.customerId">{{ customerName(row.customerId) }}</span>
            <span v-else style="color: var(--text-secondary);">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="省市区" width="200">
          <template #default="{ row }">
            <span v-if="row.province || row.city || row.district">{{ row.province }}{{ row.city }}{{ row.district }}</span>
            <span v-else style="color: var(--text-secondary);">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详细地址" show-overflow-tooltip />
        <el-table-column prop="tag" label="标签" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.tag" size="small">{{ row.tag }}</el-tag>
            <span v-else style="color: var(--text-secondary);">-</span>
          </template>
        </el-table-column>
        <el-table-column label="坐标" width="160">
          <template #default="{ row }">
            <span v-if="row.lat && row.lng" style="font-size: 12px; color: var(--text-secondary);">{{ row.lat?.toFixed(4) }}, {{ row.lng?.toFixed(4) }}</span>
            <span v-else style="color: var(--text-secondary);">未设置</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" @click="showEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑地址' : '新增地址'" width="450px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="客户">
          <el-select v-model="form.customerId" placeholder="选择客户（可选）" filterable clearable>
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="省"><el-input v-model="form.province" placeholder="省" /></el-form-item>
        <el-form-item label="市"><el-input v-model="form.city" placeholder="市" /></el-form-item>
        <el-form-item label="区/县"><el-input v-model="form.district" placeholder="区/县" /></el-form-item>
        <el-form-item label="详细地址"><el-input v-model="form.detail" placeholder="小区/大厦/楼栋/单元/门牌号" /></el-form-item>
        <el-form-item label="标签"><el-input v-model="form.tag" placeholder="如：小区、工厂" /></el-form-item>
        <el-form-item label="纬度"><el-input-number v-model="form.lat" :precision="6" :step="0.001" style="width: 100%;" /></el-form-item>
        <el-form-item label="经度"><el-input-number v-model="form.lng" :precision="6" :step="0.001" style="width: 100%;" /></el-form-item>
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
import { addressApi, customerApi } from '../../../api'

const list = ref([])
const loading = ref(false)
const filterTag = ref('')
const filterKeyword = ref('')
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref({ province: '', city: '', district: '', detail: '', tag: '', lat: null, lng: null, customerId: null })
const customers = ref([])

const customerName = (id) => customers.value.find(c => c.id === id)?.name || ''

const loadData = async () => {
  loading.value = true
  try { list.value = await addressApi.list({ tag: filterTag.value, keyword: filterKeyword.value }) } finally { loading.value = false }
}
const loadCustomers = async () => { customers.value = await customerApi.list() }
const showAdd = () => { isEdit.value = false; form.value = { province: '', city: '', district: '', detail: '', tag: '', lat: null, lng: null, customerId: null }; dialogVisible.value = true }
const showEdit = (row) => { isEdit.value = true; form.value = { ...row }; dialogVisible.value = true }
const submit = async () => {
  if (isEdit.value) { await addressApi.update(form.value.id, form.value) }
  else { await addressApi.save(form.value) }
  dialogVisible.value = false
  loadData()
}

onMounted(() => { loadData(); loadCustomers() })
</script>
