#!/usr/bin/env node
'use strict'
// Check actual package inputs, not Node's directory/index resolution or test module stubs.
const fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process')
const slash = value => value.split(path.sep).join('/')
function requires(source) {
  const found = [], unsupported = []
  // Skip comments and complete strings/templates before recognizing actual literal require calls.
  const token = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\brequire\s*\(\s*(['"])([^'"\r\n]*)\1\s*\)|\brequire\s*\(/g
  let match
  while ((match = token.exec(source))) {
    if (match[2] !== undefined) found.push({ specifier: match[2], line: source.slice(0, match.index).split('\n').length })
    else if (/^require\b/.test(match[0])) unsupported.push(source.slice(0, match.index).split('\n').length)
  }
  return { found, unsupported }
}
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => entry.isDirectory()
    ? ['.git', 'node_modules'].includes(entry.name) ? [] : walk(path.join(dir, entry.name)) : [path.join(dir, entry.name)])
}
function exactExists(root, relative) {
  let current = root
  for (const part of relative.split('/')) {
    if (!fs.existsSync(current) || !fs.statSync(current).isDirectory() || !fs.readdirSync(current).includes(part)) return false
    current = path.join(current, part)
  }
  return fs.existsSync(current) && fs.statSync(current).isFile()
}
function resolveId(importer, specifier) {
  if (!specifier.startsWith('.') && !specifier.startsWith('/')) throw Error('non-package require: ' + specifier)
  const value = path.posix.normalize(specifier.startsWith('/') ? specifier.slice(1) : path.posix.join(path.posix.dirname(importer), specifier))
  if (value.startsWith('../') || value === '..') throw Error('require escapes miniapp: ' + specifier)
  if (path.posix.extname(value) && !value.endsWith('.js')) throw Error('native JS require cannot use Node file loader: ' + specifier)
  return value.endsWith('.js') ? value : value + '.js'
}
function ignored(relative, rules) {
  return rules.some(rule => {
    const value = String(rule.value || '').replace(/^\//, '').replace(/\/$/, '')
    if (rule.type === 'file') return relative === value
    if (rule.type === 'folder') return relative === value || relative.startsWith(value + '/')
    if (rule.type === 'suffix') return relative.endsWith(value)
    if (rule.type === 'prefix') return relative.startsWith(value)
    if (rule.type === 'regexp') return new RegExp(rule.value).test(relative)
    throw Error('unsupported pack ignore rule: ' + rule.type)
  })
}
function analyzeProject(projectRoot, repoRoot, tracked) {
  const config = JSON.parse(fs.readFileSync(path.join(projectRoot, 'project.config.json'), 'utf8'))
  const root = path.resolve(projectRoot, config.miniprogramRoot || '.')
  const prefix = slash(path.relative(repoRoot, root)) + '/', errors = [], edges = []
  const rules = config.packOptions && config.packOptions.ignore || []
  const files = walk(root).filter(file => file.endsWith('.js')).map(file => slash(path.relative(root, file)))
  const check = (id, from) => {
    if (!exactExists(root, id)) errors.push({ from, target: id, reason: 'missing file or incorrect filename case' })
    else if (!tracked.has(prefix + id)) errors.push({ from, target: id, reason: 'module exists but is not git tracked' })
    if (ignored(id, rules)) errors.push({ from, target: id, reason: 'module excluded by packOptions.ignore' })
  }
  // An incomplete copy must fail even if its newly omitted file was not reached in this session.
  for (const file of tracked) if (file.startsWith(prefix) && file.endsWith('.js') && !exactExists(root, file.slice(prefix.length))) {
    errors.push({ target: file.slice(prefix.length), reason: 'tracked JS missing from working package' })
  }
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  for (const entry of ['app.js', ...(app.pages || []).map(route => route + '.js')]) check(entry, 'app.json')
  for (const file of files) {
    if (ignored(file, rules)) continue
    const parsed = requires(fs.readFileSync(path.join(root, file), 'utf8'))
    for (const line of parsed.unsupported) errors.push({ from: file, line, reason: 'dynamic require needs explicit native packaging review' })
    for (const item of parsed.found) {
      try { const target = resolveId(file, item.specifier); edges.push({ from: file, target, ...item }); check(target, file + ':' + item.line) }
      catch (error) { errors.push({ from: file, ...item, reason: error.message }) }
    }
  }
  return { project: slash(path.relative(repoRoot, projectRoot)), root, files, edges, errors, packIgnore: rules }
}
function checkRepo(repoRoot) {
  // File descriptors also work in Windows sandboxes that reject child-process stdout pipes.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'af-package-index-'))
  let stdout, stderr, names
  try {
    stdout = fs.openSync(path.join(dir, 'tracked'), 'w'); stderr = fs.openSync(path.join(dir, 'error'), 'w')
    const result = cp.spawnSync('git', ['ls-files', '-z', '--', 'miniapp-user', 'miniapp-delivery'], { cwd: repoRoot, stdio: ['ignore', stdout, stderr] })
    if (result.error || result.status !== 0) throw Error('cannot read git tracked package inputs')
    names = fs.readFileSync(path.join(dir, 'tracked'), 'utf8').split('\0').filter(Boolean)
  } finally {
    if (stdout !== undefined) fs.closeSync(stdout)
    if (stderr !== undefined) fs.closeSync(stderr)
    fs.rmSync(dir, { recursive: true, force: true })
  }
  if (!names.length) throw Error('cannot verify native inputs: git tracked package list is empty')
  return ['miniapp-user', 'miniapp-delivery'].map(side => analyzeProject(path.join(repoRoot, side), repoRoot, new Set(names)))
}
if (require.main === module) {
  try {
    const results = checkRepo(path.resolve(__dirname, '..')), errors = results.flatMap(r => r.errors.map(e => ({ project: r.project, ...e })))
    if (errors.length) { console.error(JSON.stringify(errors, null, 2)); process.exitCode = 1 }
    else console.log('AQUAFLOW_REQUIRE_CLOSURE_OK ' + results.length + ' packages / ' + results.reduce((n, r) => n + r.files.length, 0) + ' JS / ' + results.reduce((n, r) => n + r.edges.length, 0) + ' requires')
  } catch (error) { console.error('AQUAFLOW_REQUIRE_CLOSURE_UNKNOWN ' + error.message); process.exitCode = 1 }
}
module.exports = { requires, resolveId, analyzeProject, checkRepo }
