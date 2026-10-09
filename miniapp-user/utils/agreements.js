// Generated packaged drafts provide read-only offline text. Only current server-published text can supply login evidence.
const drafts = require('../data/agreement-drafts.json')
const audience = 'CUSTOMER'
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
