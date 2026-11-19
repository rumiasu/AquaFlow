import {esc, facts, params, positive, rows, screen, table} from './desktop.js';

export async function employees(context) {
  const {api,user,container,current} = context;
  const {show,listen,submit} = screen(context);
  const binds = '/api/manager/bind';
  async function load() {
    const [staff,applications] = (await Promise.all([api.get('/api/staff'),api.get(params(`${binds}/applications`,{status:'PENDING'}))])).map(rows);
    const isDelivery = row => positive(row.id) && row.role === 'DELIVERY' && Number(row.stationId) === Number(user.stationId);
    show(`<form id="create-staff" class="toolbar"><label>新配送员姓名<input name="name" required maxlength="50"></label><label>手机号<input name="phone" type="tel" pattern="1[0-9]{10}" required></label><button>新增配送员</button></form><div id="employee-detail"></div>` +
      table(['姓名','电话','角色','状态','操作'],staff.map(row => [esc(row.name),esc(row.phone),row.role === 'DELIVERY' ? '配送员' : esc(row.role === 'STATION_MANAGER' ? '站长' : row.role),row.status === 1 ? '在职' : row.status === 2 ? '离职' : '待核实',
        positive(row.id) ? `<button data-staff-profile="${row.id}">员工资料</button>${isDelivery(row) && row.status === 1 ? `<button data-bind-code="${row.id}">签发绑定码</button><button data-release="${row.id}">解除绑定</button>` : ''}` : '待核实'])) +
      '<h2>待审批申请</h2>' + table(['员工','类型','申请说明','状态','操作'], applications.map(row => [esc(row.staffName),esc(row.typeName),esc(row.applyNote),esc(row.statusName),
        positive(row.id) && [1,2].includes(row.type) ? `<button data-review="${row.id}:yes">同意</button><button data-review="${row.id}:no">驳回</button>` : '待核实'])));
    listen('#create-staff','submit',async form => {
      const fields = new FormData(form), name = fields.get('name').trim(), phone = fields.get('phone').trim();
      if (!name || !/^1\d{10}$/.test(phone)) throw new Error('请核对配送员姓名及手机号');
      if (await submit('/api/staff','POST',{name,phone,role:'DELIVERY'},`新增配送员 ${name}（${phone}）？`)) await load();
    });
    listen('[data-review]','click',async button => {
      const [id,decision] = button.dataset.review.split(':');
      const row = applications.find(item => String(item.id) === id);
      const approve = decision === 'yes';
      const action = row.type === 2 ? (approve ? 'unbind-confirm' : 'unbind-reject') : (approve ? 'approve' : 'reject');
      if (await submit(`${binds}/${action}`,'POST',{applicationId:Number(id)},`${approve ? '同意' : '驳回'} ${row.staffName} 的${row.typeName}？`)) await load();
    });
    listen('[data-release]','click',async button => {
      const row = staff.find(item => String(item.id) === button.dataset.release);
      if (await submit(`${binds}/release`,'POST',{staffId:row.id},`解除 ${row.name} 与本站的绑定？`)) await load();
    });
    listen('[data-bind-code]','click',async button => {
      const row = staff.find(item => String(item.id) === button.dataset.bindCode);
      if (!confirm(`为 ${row.name} 签发一次性微信绑定码？`)) return;
      const data = await api.command(`/api/manager/staff/${row.id}/bind-code`,'POST');
      if (!current()) return;
      container.querySelector('#employee-detail').innerHTML = `<section class="panel"><h2>${esc(row.name)}的一次性绑定码</h2>${facts([['绑定码',data.code ?? data.bindCode],['到期时间',data.expireTime ?? data.expiresAt]])}</section>`;
    });
    listen('[data-staff-profile]','click',async button => {
      const row = staff.find(item => String(item.id) === button.dataset.staffProfile);
      const profile = await api.get(`/api/staff/${row.id}/profile`);
      if (!current()) return;
      container.querySelector('#employee-detail').innerHTML = `<section class="panel"><h2>${esc(row.name)}的员工资料</h2>${facts([['姓名',profile.name ?? row.name],['电话',profile.phone ?? row.phone],['订单数',profile.totalOrders],['配送桶数',profile.deliveryBucketQty]])}
        ${isDelivery(row) ? `<form id="staff-edit" class="toolbar"><label>姓名<input name="name" required maxlength="50" value="${esc(row.name)}"></label><label>电话<input name="phone" type="tel" pattern="1[0-9]{10}" required value="${esc(row.phone)}"></label><label>在职状态<select name="status"><option value="1">在职</option><option value="2" ${row.status === 2 ? 'selected' : ''}>离职</option></select></label><button>保存</button></form>` : ''}</section>`;
      listen('#staff-edit','submit',async form => {
        const data = new FormData(form), name = data.get('name').trim(), phone = data.get('phone').trim();
        if (!name || !/^1\d{10}$/.test(phone)) throw new Error('请核对姓名及电话');
        if (await submit(`/api/staff/${row.id}`,'PUT',{name,phone,status:Number(data.get('status'))},`保存 ${row.name} 的员工资料？`)) await load();
      });
    });
  }
  await load();
}
