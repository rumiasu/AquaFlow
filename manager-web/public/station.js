import {esc, facts, money, positive, rows, screen, table} from './desktop.js';

export async function pendingSummary(context) {
  const result = await context.api.get('/api/manager/pending-summary');
  screen(context).show(table(['待办','数量','金额'],rows(result.items).map(row => [esc(row.label),row.available === true ? esc(row.count) : '待核实',row.amount == null ? '—' : money(row.amount)])));
}

export async function stationStatus(context) {
  const {api,user,current,container} = context;
  const {show,listen,submit} = screen(context);
  async function load() {
    const [station,noticeList] = await Promise.all([api.get('/api/manager/station-status'),api.get('/api/notices/all')]);
    if (!current()) return;
    const options = rows(station.options), notices = rows(noticeList);
    show(`<div class="actions"><button id="station-profile">水站资料</button><button id="station-fees">配送计费</button></div><section class="panel"><h2>${esc(station.stationName)}</h2>${facts([['当前营业状态',station.statusText],['顾客端提示',station.currentEffect]])}
      <form class="toolbar" id="operating-form"><label>营业状态<select name="state" required><option value="">请选择</option>${options.map(option => positive(option.value) ? `<option value="${option.value}" ${option.value === station.operatingStatus ? 'selected' : ''}>${esc(option.text)}</option>` : '').join('')}</select></label>
        <label>对顾客的留言<input name="note" maxlength="100" value="${esc(station.note ?? '')}"></label><button>保存营业状态</button></form></section>
      <h2>水站公告</h2><button id="new-notice">新建公告</button><div id="notice-editor"></div>` + table(['标题','内容','状态','时间','操作'],notices.map(row => [esc(row.title),esc(row.content),esc(row.statusText),esc(row.updateTime ?? row.createTime),
        positive(row.id) && String(row.stationId) === String(user.stationId) ? `<button data-notice="${row.id}">编辑</button><button data-delete-notice="${row.id}">删除</button>` : '—'])));
    listen('#operating-form','submit',async form => {
      const data = new FormData(form), selected = options.find(option => String(option.value) === data.get('state'));
      if (!selected) throw new Error('请选择营业状态');
      if (await submit('/api/manager/station-status','PUT',{operatingStatus:selected.value,note:data.get('note').trim()},`将水站设为「${selected.text}」？\n${selected.effect ?? ''}`)) await load();
    });
    listen('#new-notice','click',() => edit());
    listen('#station-profile','click',profile);
    listen('#station-fees','click',fees);
    listen('[data-notice]','click',button => edit(notices.find(row => String(row.id) === button.dataset.notice)));
    listen('[data-delete-notice]','click',async button => {
      const row = notices.find(item => String(item.id) === button.dataset.deleteNotice);
      if (await submit(`/api/notices/${row.id}`,'DELETE',undefined,`删除公告「${row.title}」？`)) await load();
    });
    function edit(row) {
      container.querySelector('#notice-editor').innerHTML = `<form id="notice-form" class="panel"><label class="field">标题<input name="title" required maxlength="100" value="${esc(row?.title ?? '')}"></label>
        <label class="field">内容<textarea name="content" rows="5" required>${esc(row?.content ?? '')}</textarea></label><label class="field">状态<select name="status"><option value="0">保存为草稿／下架</option><option value="1" ${row?.status === 1 ? 'selected' : ''}>发布</option></select></label><button>保存公告</button></form>`;
      listen('#notice-form','submit',async form => {
        const fields = new FormData(form), title = fields.get('title').trim(), content = fields.get('content').trim(), status = Number(fields.get('status'));
        if (!title || !content) throw new Error('请填写标题和内容');
        if (await submit(row ? `/api/notices/${row.id}` : '/api/notices',row ? 'PUT' : 'POST',{title,content,status,type:row?.type ?? 2},
          `${status ? '发布' : '保存'}公告「${title}」？`)) await load();
      });
    }
  }
  async function profile() {
    const station = await api.get('/api/stations/mine');
    if (!positive(station?.id) || String(station.id) !== String(user.stationId)) throw new Error('本站资料待核实');
    show(`<button id="station-back">返回营业与公告</button><form id="station-info" class="panel"><h2>水站资料</h2>
      <label class="field">水站名称<input name="name" maxlength="100" required value="${esc(station.name ?? '')}"></label>
      <label class="field">联系电话<input name="phone" maxlength="30" required value="${esc(station.phone ?? '')}"></label>
      <label class="field">地址<input name="address" maxlength="200" required value="${esc(station.address ?? '')}"></label>
      ${facts([['当前纬度',station.lat],['当前经度',station.lng]])}<label class="field">地图位置<select name="mode"><option value="KEEP">保留原点（地址未变或仅补门牌）</option><option value="SET">填写新坐标</option><option value="CLEAR">清除原位置</option></select></label>
      <div id="station-position" hidden><label class="field">纬度<input name="lat" type="number" step="0.000001" min="-90" max="90"></label><label class="field">经度<input name="lng" type="number" step="0.000001" min="-180" max="180"></label></div><button>保存水站资料</button></form>`);
    if (!current()) return;
    const form = container.querySelector('#station-info');
    form.elements.mode.addEventListener('change',() => {container.querySelector('#station-position').hidden = form.elements.mode.value !== 'SET';});
    listen('#station-back','click',load);
    listen('#station-info','submit',async () => {
      const data = new FormData(form), coordinateMode = data.get('mode'), address = data.get('address').trim(), phone = data.get('phone').trim(), name = data.get('name').trim();
      const lat = data.get('lat'), lng = data.get('lng');
      if (!name || !address || !phone || coordinateMode === 'SET' && (!lat || !lng || Math.abs(Number(lat)) > 90 || Math.abs(Number(lng)) > 180)) throw new Error('请核对名称、地址、电话及地图位置');
      if (await submit(`/api/stations/${station.id}/profile`,'PUT',{name,phone,address,coordinateMode,...(coordinateMode === 'SET' ? {lat:Number(lat),lng:Number(lng)} : {})},
        `保存 ${name} 的水站资料？\n${address}${coordinateMode === 'CLEAR' ? '\n同时清除原地图位置。' : ''}`)) await profile();
    });
  }
  async function fees() {
    const path = '/api/manager/delivery-config';
    const response = await api.get(path);
    const cfg = response.config;
    if (!cfg || !response.snapshot) throw new Error('计费规则快照待核实');
    const limits = {WARN:'仅提示',REJECT:'不接单',FEE:'加收费用'};
    const floors = {PER_ORDER:'按单',PER_BUCKET:'按桶'};
    const groups = [
      ['起送门槛',[['minOrderBuckets','起送桶数',true],['minOrderAmount','起送金额'],['minOrderFee','门槛加收金额']],['minOrderMode','未达起送门槛',limits]],
      ['配送范围',[['deliveryRadiusM','配送半径（米）',true],['remoteFee','超范围加收金额']],['overRadiusMode','超出配送范围',limits]],
      ['配送费',[['baseDeliveryFee','基础运费'],['freeDeliveryBuckets','免运费桶数',true],['freeDeliveryAmount','免运费金额']]],
      ['楼层费',[['floorFreeLevel','免费楼层',true],['floorFeePerLevel','每层收费']],['floorFeeMode','计费方式',floors]]
    ];
    show(`<button id="fees-back">返回营业与公告</button><form id="fees-edit"><h2>${response.configured === true ? '已配置的配送计费' : '配送计费尚未配置'}</h2>
      <p>门槛留空表示该项未设置。收费金额填 0 表示免费。</p>${groups.map(([title,numbers,mode]) => `<fieldset class="panel"><legend>${title}</legend><div class="toolbar">
        ${numbers.map(([field,label,integer]) => `<label>${label}<input type="number" name="${field}" min="0" step="${integer ? '1' : '0.01'}" value="${esc(cfg[field] ?? '')}"></label>`).join('')}
        ${mode ? `<label>${mode[1]}<select name="${mode[0]}">${Object.entries(mode[2]).map(([value,label]) => `<option value="${value}" ${cfg[mode[0]] === value ? 'selected' : ''}>${label}</option>`).join('')}</select></label>` : ''}</div></fieldset>`).join('')}
      <div class="actions"><button>保存配送计费</button></div></form>`);
    listen('#fees-back','click',load);
    listen('#fees-edit','submit',async form => {
      const formData = new FormData(form), body = {expectedSnapshot:response.snapshot};
      for (const [,numbers,mode] of groups) {
        for (const [field,,integer] of numbers) {
          const value = formData.get(field);
          if (value && !(integer ? /^\d+$/ : /^\d+(\.\d{1,2})?$/).test(value)) throw new Error('请核对计费数值');
          body[field] = value === '' ? null : Number(value);
        }
        if (mode) body[mode[0]] = formData.get(mode[0]);
      }
      if (body.minOrderMode === 'FEE' && !body.minOrderFee || body.overRadiusMode === 'FEE' && !body.remoteFee) throw new Error('加收费用须填写对应金额');
      if (await submit(path,'PUT',body,'保存本站配送计费规则？新订单会使用本次设置。')) await fees();
    });
  }
  await load();
}
