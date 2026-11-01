'use strict'
// Return real page routes, preserving both main-package and subpackage entries.
function registeredPages(config) {
  return [...(config.pages || []), ...(config.subPackages || config.subpackages || []).flatMap(pkg => (pkg.pages || []).map(page => pkg.root.replace(/\/$/, '') + '/' + page.replace(/^\//, '')))]
}
module.exports = { registeredPages }
