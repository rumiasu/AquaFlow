'use strict'

/**
 * F-74 (2026-10-02): scan declarations, not annotation lines. Mask Java literals
 * and comments before matching braces so URLs, text blocks and annotation arrays
 * cannot truncate or invent method bodies. This is source analysis, not proof of
 * Spring proxy execution or inherited/composed transaction annotations.
 */
function maskJava(source) {
  const out = source.split('')
  const hide = (from, to) => { for (let j = from; j < to; j++) if (out[j] !== '\n' && out[j] !== '\r') out[j] = ' ' }
  for (let i = 0; i < source.length;) {
    let end = i
    if (source.startsWith('//', i)) {
      end = source.indexOf('\n', i + 2)
      if (end < 0) end = source.length
    } else if (source.startsWith('/*', i)) {
      end = source.indexOf('*/', i + 2)
      if (end < 0) throw new Error('未闭合的 Java 注释')
      end += 2
    } else if (source.startsWith('"""', i)) {
      end = i + 3
      while (end < source.length && !source.startsWith('"""', end)) end += source[end] === '\\' ? 2 : 1
      if (end >= source.length) throw new Error('未闭合的 Java 文本块')
      end += 3
    } else if (source[i] === '"' || source[i] === "'") {
      const quote = source[i]
      end = i + 1
      while (end < source.length && source[end] !== quote) end += source[end] === '\\' ? 2 : 1
      if (end >= source.length) throw new Error('未闭合的 Java 字面量')
      end++
    }
    if (end > i) { hide(i, end); i = end } else i++
  }
  return out.join('')
}

function closing(text, start, open, close) {
  let depth = 0
  for (let i = start; i < text.length; i++) {
    if (text[i] === open) depth++
    else if (text[i] === close && --depth === 0) return i
  }
  throw new Error(`未闭合的 Java ${open}`)
}

function annotations(signature) {
  let transaction = null
  // Remove annotations (including multiline/array arguments) before identifying
  // classes and method signatures. An argument like Foo.class is not a class.
  const chars = signature.split('')
  const re = /@([\w.]+)/g
  let match
  while ((match = re.exec(signature))) {
    let end = re.lastIndex
    while (/\s/.test(signature[end] || '') && end < signature.length) end++
    const argsStart = end
    if (signature[end] === '(') end = closing(signature, end, '(', ')') + 1
    if (match[1] === 'Transactional' || match[1] === 'org.springframework.transaction.annotation.Transactional') {
      const args = signature.slice(argsStart, end)
      transaction = !/\bpropagation\s*=\s*(?:[\w.]+\.)?(?:NOT_SUPPORTED|NEVER)\b/.test(args)
    }
    for (let j = match.index; j < end; j++) chars[j] = ' '
    re.lastIndex = end
  }
  return { transaction, clean: chars.join('') }
}

function scanTransactionalCatches(source) {
  const text = maskJava(source)
  const violations = []
  const unsupported = []
  let inspected = 0
  const line = position => source.slice(0, position).split('\n').length

  function declarations(from, to, owner) {
    let start = from
    let parens = 0
    for (let i = from; i < to; i++) {
      if (text[i] === '(') parens++
      else if (text[i] === ')') parens--
      if (parens < 0) throw new Error('Java 声明括号不匹配')
      if (parens !== 0) continue
      if (text[i] === ';') {
        const annotation = annotations(text.slice(start, i))
        // Abstract/interface declarations have no executable body. A field with
        // @Transactional is not a supported declaration; never silently pass it.
        if (annotation.transaction !== null && !annotation.clean.includes('(')) unsupported.push(line(start))
        start = i + 1
      } else if (text[i] === '{') {
        const end = closing(text, i, '{', '}')
        const signature = annotations(text.slice(start, i))
        const clazz = /\b(class|interface|enum|record)\s+(\w+)/.exec(signature.clean)
        if (clazz && !signature.clean.includes('=')) {
          declarations(i + 1, end, { name: clazz[2], transactional: signature.transaction === true })
        } else {
          const method = /\b([\w$]+)\s*\(/.exec(signature.clean)
          const executable = owner && method && !signature.clean.includes('=') && method[1] !== owner.name
          const active = signature.transaction !== null ? signature.transaction
            : owner && owner.transactional && !/\b(private|static)\b/.test(signature.clean)
          if (executable && active) {
            inspected++
            const body = text.slice(i + 1, end)
            const catches = /\bcatch\s*\([^)]*\bBusinessException\b[^)]*\)/g
            let caught
            while ((caught = catches.exec(body))) violations.push({ method: method[1], line: line(i + 1 + caught.index) })
          } else if (signature.transaction === true && !executable) unsupported.push(line(start))
        }
        i = end
        start = end + 1
      }
    }
    if (parens !== 0) throw new Error('Java 声明括号未闭合')
  }
  declarations(0, text.length, null)
  return { violations, unsupported, inspected }
}

module.exports = { scanTransactionalCatches }
