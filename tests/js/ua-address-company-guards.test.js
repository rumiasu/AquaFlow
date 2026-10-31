const assert = require('assert')
const { fixture, event, tap } = require('./ua-customer-profile-visible-fixture')
async function main() {
  let sheet, setCalls = 0, deleted = 0
  const address = fixture('address', { wx: { showActionSheet: o => { sheet = o } }, api: {
    getAddresses: async () => ({data:[{id:3,name:'A',detail:'B',isDefault:0}]}),
    setDefaultAddress: async () => { setCalls++ }, deleteAddress: async () => { deleted++ }
  } })
  await address.p.loadAddresses(); address.p.onMoreAddress(tap({id:3})); assert.deepStrictEqual(Array.from(sheet.itemList), ['设为默认','删除地址'])
  sheet.success({tapIndex:1}); assert.equal(address.calls.modal.length, 1); assert.equal(deleted,0)
  address.calls.modal[0].success({confirm:false}); assert.equal(deleted,0)
  address.p.onMoreAddress(tap({id:3})); const stale = sheet; address.p.onHide(); stale.success({tapIndex:0}); assert.equal(setCalls,0)
  const warning = []
  const edit = fixture('edit', { wx: { enableAlertBeforeUnload: () => warning.push('on'), disableAlertBeforeUnload: () => warning.push('off') } })
  edit.p.onLoad({}); edit.p.onInputChange(event('name','A')); assert.equal(warning.at(-1),'on')
  edit.p.onInputChange(event('name','')); assert.equal(warning.at(-1),'off')
  const company = fixture('company', { wx: { enableAlertBeforeUnload: () => warning.push('company-on'), disableAlertBeforeUnload: () => warning.push('company-off') }, api: { getCompanyInfo: async () => ({data:{companyName:'原名',dueDays:0}}) } })
  await company.p.loadInfo(); company.p.onStartEdit(); company.p.onInput(event('companyName','修改'))
  assert.equal(warning.at(-1),'company-on'); company.p.onCancelEdit(); company.calls.modal[0].success({confirm:false}); assert.equal(company.p.data.form.companyName,'修改')
  company.p.onCancelEdit(); company.calls.modal[1].success({confirm:true}); assert.equal(company.p.data.editing,false); assert.equal(warning.at(-1),'company-off')
  console.log('AQUAFLOW_SUITE_OK 3')
}
main().catch(e => {console.error(e); process.exitCode=1})
