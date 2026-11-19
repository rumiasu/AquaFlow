import {esc, money, params, positive, rows, screen, table} from './desktop.js';

const PACKAGE = '/api/ticket-packages', DISCOUNT = '/api/ticket-discounts', OFFER = '/api/manager/ticket-delivery-offers';

export async function tickets(context) {
  const {api,user,container,current} = context;
  const {show,listen,submit} = screen(context);
  const products = rows(await api.get(params('/api/products/sale-by-station',{stationId:user.stationId}))).filter(row => row.category === 1 && positive(row.id));
  async function load(selected = 'discount') {
    const discount = selected === 'discount', offers = selected === 'offers';
    const entries = rows(await api.get(discount ? DISCOUNT : offers ? OFFER : params(`${PACKAGE}/manage`,{productId:selected})));
    const selector = `<form class="toolbar" id="ticket-view"><label>档位<select name="tier"><option value="discount">全站统一折扣</option><option value="offers" ${offers ? 'selected' : ''}>一次配送权益</option>${products.map(row => `<option value="${row.id}" ${String(row.id) === selected ? 'selected' : ''}>${esc(row.name)} · 商品套餐</option>`).join('')}</select></label><button>查看</button></form>`;
    if (offers) {
      show(selector + '<p>仅抵一次配送费，楼层费按原规则收取。</p>' + table(['档位','商品','状态','操作'],entries.map((row,index) => [esc(row.label ?? row.title ?? row.qty),esc(row.productName),row.enabled === true ? '已启用' : '未启用',
        typeof row.enabled === 'boolean' && row.kind && row.tierKey ? `<button data-offer="${index}">${row.enabled ? '停用' : '启用'}</button>` : '待核实'])));
      listen('[data-offer]','click',async button => {
        const row = entries[Number(button.dataset.offer)];
        if (await submit(OFFER,'PUT',{kind:row.kind,tierKey:row.tierKey,productId:row.productId,enabled:!row.enabled},`${button.textContent}此购票档位的一次配送权益？`)) await load(selected);
      });
    } else {
      const priceField = discount ? '折扣（如 9.5）' : '套餐总价（元）';
      const endpoint = discount ? DISCOUNT : PACKAGE;
      show(selector + `<form id="ticket-tier" class="toolbar"><label>张数<input name="qty" type="number" min="1" step="1" required></label><label>${priceField}<input name="price" type="number" min="0.01" step="0.01" ${discount ? 'max="10"' : ''} required></label>
        <label>名称<input name="title" maxlength="100"></label><label>顺序<input name="sort" type="number" value="0" step="1" required></label><button>保存档位</button></form>` +
        table(['张数',discount ? '折扣' : '总价','名称','状态','操作'],entries.map(row => [esc(row.qty),discount ? Number.isFinite(Number(row.discountPerMille)) && row.discountPerMille != null ? `${esc(row.discountPerMille / 100)} 折` : '待核实' : money(row.price),esc(row.title),row.status === 1 ? '生效' : row.status === 0 ? '停用' : '待核实',
          positive(row.id) && [0,1].includes(row.status) ? `<button data-tier-edit="${row.id}">编辑</button><button data-tier-toggle="${row.id}">${row.status ? '停用' : '启用'}</button><button data-tier-delete="${row.id}">删除</button>` : '待核实'])));
      listen('[data-tier-edit]','click',button => {
        const row = entries.find(item => String(item.id) === button.dataset.tierEdit);
        const form = container.querySelector('#ticket-tier');
        form.elements.qty.value = row.qty; form.elements.price.value = discount ? Number(row.discountPerMille) / 100 : row.price;
        form.elements.title.value = row.title ?? ''; form.elements.sort.value = row.sort ?? 0;
        form.dataset.status = row.status; form.elements.qty.readOnly = true; form.elements.price.focus();
      });
      listen('#ticket-tier','submit',async form => {
        const fields = new FormData(form), qty = Number(fields.get('qty')), amount = Number(fields.get('price')), sort = Number(fields.get('sort'));
        if (!positive(qty) || !Number.isFinite(amount) || amount <= 0 || discount && amount > 10 || !Number.isSafeInteger(sort)) throw new Error('请核对购票档位');
        const body = {qty,title:fields.get('title').trim() || null,sort,status:Number(form.dataset.status ?? 1),
          ...(discount ? {discountPerMille:Math.round(amount * 100)} : {productId:Number(selected),price:amount})};
        if (await submit(endpoint,'POST',body,`保存 ${qty} 张的购票档位？`)) await load(selected);
      });
      listen('[data-tier-toggle]','click',async button => {
        const row = entries.find(item => String(item.id) === button.dataset.tierToggle);
        const body = {qty:row.qty,title:row.title,sort:row.sort,status:row.status === 1 ? 0 : 1,
          ...(discount ? {discountPerMille:row.discountPerMille} : {productId:row.productId,price:row.price})};
        if (await submit(endpoint,'POST',body,`${button.textContent} ${row.qty} 张的档位？`)) await load(selected);
      });
      listen('[data-tier-delete]','click',async button => {
        const row = entries.find(item => String(item.id) === button.dataset.tierDelete);
        if (await submit(`${endpoint}/${row.id}`,'DELETE',undefined,`删除 ${row.qty} 张的档位？`)) await load(selected);
      });
    }
    if (!current()) return;
    listen('#ticket-view','submit',form => load(new FormData(form).get('tier')));
  }
  await load();
}
