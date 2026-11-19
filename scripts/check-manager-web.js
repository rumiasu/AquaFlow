#!/usr/bin/env node
// The desktop client has no installation or build step.
const { spawnSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../manager-web')
const publicDir = path.join(root, 'public')
const visited = new Set()

function run(args) {
  const child = spawnSync(process.execPath, args, { cwd: root, stdio: 'inherit' })
  if (child.error) throw child.error
  if (child.status !== 0) process.exit(child.status ?? 1)
}

function checkAsset(file) {
  if (!file.startsWith(publicDir + path.sep) || !fs.statSync(file).isFile()) {
    throw Error('Missing or outside public/ directory: ' + file)
  }
  if (visited.has(file)) return
  visited.add(file)
  const ext = path.extname(file)
  const references = {
    '.html': /\b(?:src|href)\s*=\s*["']([^"']+)["']/g,
    '.css': /\burl\(\s*["']?([^)'"]+)["']?\s*\)/g,
    '.js': /^\s*import\s+(?:[^\n'"]*?\s*from\s*)?["']([^"']+)["']/gm
  }
  if (!(ext in references)) return
  const contents = fs.readFileSync(file, 'utf8')
  for (const [, ref] of contents.matchAll(references[ext])) {
    if (ref.startsWith('#')) continue
    if (/^(?:[a-z][a-z0-9+.-]*:|[/\\])/i.test(ref)) {
      throw Error('External or absolute bundle reference: ' + ref)
    }
    checkAsset(path.resolve(path.dirname(file), ref))
  }
  if (ext === '.js') {
    // HTML templates reference images relative to the entry page.
    for (const [, asset] of contents.matchAll(/\bsrc=["'](assets\/[\w./-]+)["']/g)) {
      checkAsset(path.resolve(publicDir, asset))
    }
  }
}

checkAsset(path.join(publicDir, 'index.html'))
run(['scripts/check.mjs'])
const tests = fs.readdirSync(path.join(root, 'test')).filter(x => x.endsWith('.test.js')).sort()
if (!tests.includes('sales.test.js')) throw Error('Missing desktop sales regression tests')
run(['--test', ...tests.map(x => 'test/' + x)])
console.log('AQUAFLOW_MANAGER_WEB_OK', visited.size, 'assets;', tests.length, 'test files')
