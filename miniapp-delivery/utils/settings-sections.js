function jumpToSettingSection(page, e) {
  const target = e.currentTarget.dataset.target
  if (!(page.data.settingsSections || []).some(it => it.target === target)) return
  if (typeof wx.pageScrollTo !== 'function' || typeof wx.createSelectorQuery !== 'function') return
  try {
    const q = wx.createSelectorQuery().in(page)
    q.select('#' + target).boundingClientRect(); q.select('.settings-section-nav').boundingClientRect(); q.selectViewport().scrollOffset()
    q.exec(rows => {
      if (page._unloaded || !rows || !rows[0] || !Number.isFinite(rows[0].top)) return
      const height = rows[1] && Number.isFinite(rows[1].height) ? rows[1].height : 56
      const scroll = rows[2] && Number.isFinite(rows[2].scrollTop) ? rows[2].scrollTop : 0
      wx.pageScrollTo({scrollTop: Math.max(0, rows[0].top + scroll - height - 12), duration:180})
    })
  } catch (_) { /* 表单保持原样，可继续手动滚动。 */ }
}
module.exports = { jumpToSettingSection }
