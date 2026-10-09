// Generated packaged drafts provide read-only offline text. Only current server-published text can supply login evidence.
// 2026-10-09：原生登录/协议页把 JSON require 解析为 .json.js，实际中断页面加载。
// 与用户端复用正文正本生成的 JS 镜像；草稿仍关闭，不产生正式接受证据。
const drafts = require('../data/agreement-drafts.js')
const audience = 'STAFF'
function validDocument(doc, type) {
  return !!doc && doc.audience === audience && doc.type === type &&
    typeof doc.contentSha256 === 'string' && /^[0-9a-f]{64}$/.test(doc.contentSha256) &&
    doc.versionId === audience.toLowerCase() + '-' + type + '-' + doc.contentSha256 &&
    typeof doc.navTitle === 'string' && typeof doc.docTitle === 'string' && Array.isArray(doc.sections) &&
    doc.sections.length > 0 && doc.sections.every(s => s && typeof s.heading === 'string' && typeof s.body === 'string')
}
function validCatalog(value) {
  return !!value && typeof value.notice === 'string' && Array.isArray(value.documents) && value.documents.length === 2 &&
    ['user', 'privacy'].every(type => value.documents.filter(d => d && d.type === type && validDocument(d, type)).length === 1) &&
    (value.enabled === false || value.enabled === true && value.documents.every(d => d.active === true && d.status === 'APPROVED'))
}
function loginEvidence(value) {
  if (!validCatalog(value) || value.enabled !== true) return undefined
  return { termsVersionId: value.documents.find(d => d.type === 'user').versionId,
    privacyVersionId: value.documents.find(d => d.type === 'privacy').versionId }
}
function find(value, type) { return value.documents.find(d => d.type === type) }
module.exports = { drafts, validDocument, validCatalog, loginEvidence, find }
