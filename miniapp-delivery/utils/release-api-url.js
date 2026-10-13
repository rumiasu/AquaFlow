// Mirrored in both independent miniapp packages; keep the two files identical.
// This validates configuration, not DNS reachability or WeChat's registered-domain list.
function assertReleaseBaseUrl(baseUrl, devConfig) {
  const match = typeof baseUrl === 'string' && !/[\\\s\u0000-\u001f\u007f-\u009f?#]/.test(baseUrl)
    && baseUrl.match(/^https:\/\/([A-Za-z0-9.-]+)(?::(\d+))?(?:\/.*)?$/i)
  const host = match ? match[1].replace(/\.$/, '').toLowerCase() : ''
  const labels = host.split('.')
  const devHosts = [devConfig.baseUrl, devConfig.devtoolsBaseUrl].map(url => {
    const parsed = typeof url === 'string' && url.match(/^https?:\/\/([A-Za-z0-9.-]+)(?::\d+)?(?:\/.*)?$/i)
    return parsed ? parsed[1].replace(/\.$/, '').toLowerCase() : ''
  })
  // A release API must be a HTTPS domain, never an IP literal/local name or either develop host.
  // Numeric IPv4 variants, raw controls, query/fragment, malformed labels and local names fail closed.
  if (!match || host.length > 253 || labels.length < 2
    || labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))
    || !/[a-z]/i.test(labels[labels.length - 1])
    // URL parsers interpret a numeric/hex final label as IPv4, even when it contains letters.
    || /^(?:[0-9]+|0x[0-9a-f]*)$/i.test(labels[labels.length - 1])
    || /(?:^|\.)(?:localhost|local|lan|internal|home|home\.arpa)$/.test(host)
    || host === 'your-domain.com' || host.endsWith('.your-domain.com')
    || (match[2] && (Number(match[2]) < 1 || Number(match[2]) > 65535))
    || devHosts.includes(host)) {
    throw new Error('正式版 API 必须配置合法 HTTPS 域名，不能使用占位、本机、局域网或开发地址。')
  }
}

module.exports = { assertReleaseBaseUrl }
