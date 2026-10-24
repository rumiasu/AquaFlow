// Fixed categories and numeric codes only. Never copy server messages, file paths or signed URLs.
const CODES = ['UPLOAD_LOGIN_REQUIRED', 'UPLOAD_FILE_TOO_LARGE', 'UPLOAD_FILE_TYPE_INVALID',
  'UPLOAD_STORAGE_UNAVAILABLE', 'UPLOAD_STORAGE_UNCONFIRMED', 'UPLOAD_PREVIEW_UNAVAILABLE', 'UPLOAD_RECEIPT_INCOMPLETE']
const integer = value => Number.isSafeInteger(value) ? value : null
function uploadDiagnostic(error = {}) {
  return {
    statusCode: integer(error.statusCode), backendCode: integer(error.backendCode),
    uploadDiagnosticCode: CODES.includes(error.uploadDiagnosticCode) ? error.uploadDiagnosticCode : null,
    uploadOutcomeUnknown: !!error.uploadOutcomeUnknown,
    cloudErrorCode: integer(error.cloudErrorCode),
    stage: error.localUploadFailure ? 'local' : ['init', 'call', 'timeout'].includes(error.transportPhase) ? error.transportPhase
      : Number.isSafeInteger(error.statusCode) ? 'server' : error.malformedResponse ? 'response' : 'unclassified',
    requestMayHaveOccurred: error.requestMayHaveOccurred === true
  }
}
function diagnosticText(diagnostic) {
  const d = uploadDiagnostic({ ...diagnostic, transportPhase: diagnostic && diagnostic.stage,
    localUploadFailure: diagnostic && diagnostic.stage === 'local', malformedResponse: diagnostic && diagnostic.stage === 'response' })
  const value = v => v == null ? '-' : v
  return `AF-IMG2 stage=${d.stage} http=${value(d.statusCode)} backend=${value(d.backendCode)} code=${value(d.uploadDiagnosticCode)} sdk=${value(d.cloudErrorCode)} uncertain=${d.uploadOutcomeUnknown || d.requestMayHaveOccurred ? 1 : 0}`
}
module.exports = { uploadDiagnostic, diagnosticText }
