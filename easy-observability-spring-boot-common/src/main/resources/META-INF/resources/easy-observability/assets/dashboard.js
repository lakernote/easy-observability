const api = '/api/easy-observability'
const $ = id => document.getElementById(id)
const PAGE_SIZE = 50
let traces = []
let page = 0
let timer
let searchTimer
let detailRequest = 0

const sourceNames = { HTTP: 'HTTP', JOB: '定时任务', KAFKA_CONSUMER: 'Kafka 消费' }
const escText = value => value == null || value === '' ? '—' : String(value)

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = typeof window.EasyObservabilityAuth?.token === 'function' ? window.EasyObservabilityAuth.token() : ''
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(`${api}${path}`, { credentials: 'same-origin', ...options, headers })
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? '没有查看 Trace 的权限，请登录或检查 ObservabilityAccessPolicy'
    : `请求失败（HTTP ${response.status}）`)
  return response.status === 204 ? null : response.json()
}

function initializeEmbeddedAuth() {
  if (window.parent === window) return Promise.resolve()
  return new Promise(resolve => {
    let completed = false
    const finish = token => {
      if (completed) return
      completed = true
      clearTimeout(timeout)
      window.removeEventListener('message', receiveAuth)
      window.EasyObservabilityAuth = { token: () => token }
      resolve()
    }
    const receiveAuth = event => {
      if (event.origin !== window.location.origin || event.source !== window.parent
        || event.data?.type !== 'easy-observability:auth-response') return
      finish(typeof event.data.token === 'string' ? event.data.token : '')
    }
    const timeout = setTimeout(() => finish(''), 1500)
    window.addEventListener('message', receiveAuth)
    window.parent.postMessage({ type: 'easy-observability:auth-request' }, window.location.origin)
  })
}

function time(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? escText(value) : date.toLocaleString('zh-CN')
}

function node(tag, className, text) {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text != null) element.textContent = text
  return element
}

function filtered() {
  const query = $('search').value.trim().toLocaleLowerCase()
  const result = $('result-filter').value
  const source = $('source-filter').value
  return traces.filter(trace => {
    const resourceAlert = trace.highCpu || trace.highAllocation
    if (!trace.slow && !trace.error && !resourceAlert) return false
    if (result === 'slow' && (!trace.slow || trace.error)) return false
    if (result === 'error' && !trace.error) return false
    if (result === 'resource' && !resourceAlert) return false
    if (source && trace.sourceType !== source) return false
    if (!query) return true
    const matchesNode = nodes => Array.isArray(nodes) && nodes.some(item =>
      [item.name, item.tag, item.kind, item.errorType, item.threadName]
        .some(value => String(value || '').toLocaleLowerCase().includes(query)) || matchesNode(item.children))
    return [trace.name, trace.method, trace.traceId, trace.errorType, trace.errorMessage,
      trace.highCpu ? '高 CPU' : '', trace.highAllocation ? '高分配' : '']
      .some(value => String(value || '').toLocaleLowerCase().includes(query)) || matchesNode(trace.treeNodes)
  })
}

function render() {
  const rows = filtered()
  const lastPage = Math.max(0, Math.ceil(rows.length / PAGE_SIZE) - 1)
  page = Math.min(page, lastPage)
  const visible = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const tbody = $('trace-rows')
  tbody.replaceChildren()
  for (const trace of visible) {
    const tr = document.createElement('tr')
    const resultCell = node('td')
    if (trace.error) resultCell.append(node('span', 'badge error', '错误'))
    if (trace.slow) resultCell.append(node('span', 'badge slow', '慢调用'))
    if (trace.highCpu) resultCell.append(node('span', 'badge resource', '高 CPU'))
    if (trace.highAllocation) resultCell.append(node('span', 'badge resource', '高分配'))
    tr.append(resultCell)
    tr.append(node('td', 'entry', `${sourceNames[trace.sourceType] || 'Trace'} · ${trace.name || trace.method || '入口'}`))
    tr.append(node('td', 'allocated', formatAllocatedBytes(trace.allocatedBytes)))
    tr.append(node('td', 'duration', `${Number(trace.durationMs || 0).toLocaleString()} ms`))
    tr.append(node('td', 'trace-id', trace.traceId))
    tr.append(node('td', 'time', time(trace.finishedAt)))
    tr.tabIndex = 0
    tr.setAttribute('role', 'button')
    tr.setAttribute('aria-label', `查看 ${trace.name || trace.traceId} 的诊断 Trace 详情`)
    tr.addEventListener('click', () => openTrace(trace))
    tr.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openTrace(trace) } })
    tbody.append(tr)
  }
  $('empty').hidden = rows.length > 0
  $('result-count').textContent = `${rows.length} 条诊断 Trace`
  const noFilters = $('result-filter').value === 'all' && !$('source-filter').value && !$('search').value.trim()
  $('empty').textContent = noFilters
    ? '暂无诊断 Trace；正常请求不会显示在此列表中'
    : '没有符合当前筛选条件的 Trace'
  $('page-label').textContent = rows.length ? `第 ${page + 1} / ${lastPage + 1} 页` : ''
  $('previous').disabled = page <= 0
  $('next').disabled = page >= lastPage
}

function addFact(container, label, value, className = '', title = '') {
  const item = node('div', `fact ${className}`)
  const content = node('span', '', escText(value))
  if (title) content.title = title
  item.append(node('small', '', label), content)
  container.append(item)
}

function formatAllocatedBytes(value) {
  if (value == null || value === '') return '未采集'
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return '未采集'
  if (bytes < 1024) return `${bytes.toLocaleString()} B`
  const units = ['KiB', 'MiB', 'GiB']
  let amount = bytes
  let unit = 'B'
  for (let index = 0; index < units.length; index += 1) {
    amount /= 1024
    unit = units[index]
    if (amount < 1024 || index === units.length - 1) break
  }
  return `${amount.toFixed(1)} ${unit}`
}

function renderTraceTree(container, detailContainer, structuredNodes, totalMs) {
  const roots = Array.isArray(structuredNodes) ? structuredNodes : []
  container.replaceChildren()
  detailContainer.replaceChildren(node('p', '', '选择一个调用节点查看详情'))
  if (!roots.length) {
    container.append(node('p', 'tree-empty', '没有可展示的调用节点'))
    return
  }
  const flat = []
  const visit = (item, depth = 0) => {
    flat.push({ item, depth })
    for (const child of (Array.isArray(item.children) ? item.children : [])) visit(child, depth + 1)
  }
  roots.forEach(root => visit(root))
  let hasOffsets = true
  let maxMs = Math.max(1, totalMs)
  for (const { item } of flat) {
    const offset = item.startOffsetMs == null ? NaN : Number(item.startOffsetMs)
    if (!Object.prototype.hasOwnProperty.call(item, 'startOffsetMs') || !Number.isFinite(offset) || offset < 0) {
      hasOffsets = false
    }
    maxMs = Math.max(maxMs, Math.max(0, offset || 0) + Math.max(0, Number(item.durationMs) || 0))
  }
  const children = flat.filter(({ depth }) => depth > 0)
  const deepest = candidates => candidates.reduce((best, candidate) => {
    if (!best || candidate.depth > best.depth) return candidate
    if (candidate.depth === best.depth
      && Number(candidate.item.durationMs || 0) > Number(best.item.durationMs || 0)) return candidate
    return best
  }, null)
  const primary = deepest(children.filter(({ item }) => item.error))
    || deepest(children.filter(({ item }) => item.slow))
    || children.reduce((longest, candidate) =>
      !longest || Number(candidate.item.durationMs || 0) > Number(longest.item.durationMs || 0) ? candidate : longest, null)
    || flat[0]
  if (primary) {
    const duration = Math.max(0, Number(primary.item.durationMs) || 0)
    const percent = totalMs > 0 ? Math.round(duration / totalMs * 100) : 0
    const isError = Boolean(primary.item.error)
    const summary = node('div', `diagnosis-summary${isError ? ' is-error' : ''}`)
    summary.append(
      node('strong', '', primary.depth === 0 ? '入口 Trace' : isError ? '错误节点' : '最长子调用'),
      node('span', '', `${primary.item.kind || '调用'} · ${primary.item.name || '未命名调用'}`),
      node('b', '', `${duration.toLocaleString()} ms${totalMs > 0 && percent <= 100 ? ` · 约 ${percent}% 总耗时` : ''}`)
    )
    container.append(summary)
  }
  const scale = node('div', 'timeline-scale')
  scale.append(node('span', 'timeline-scale-label', ''))
  const ticks = node('span', 'timeline-ticks')
  ticks.append(node('span', '', '0 ms'), node('span', '', `${Math.round(maxMs / 2)} ms`), node('span', '', `${maxMs} ms`))
  scale.append(ticks, node('span', 'timeline-scale-duration', ''))
  container.append(scale)
  if (!hasOffsets && flat.length > 1) container.append(node('p', 'timeline-legacy', '该 Trace 是旧版本记录，未保存节点开始偏移；时间线位置仅供参考。'))
  let selectedRow = null
  let primaryRow = null
  for (const { item, depth } of flat) {
    const row = node('button', `timeline-row${item.error ? ' has-error' : item.slow ? ' is-slow' : ''}`)
    row.type = 'button'
    row.style.setProperty('--depth', Math.min(depth, 8))
    row.setAttribute('aria-label', `${item.kind || '调用'} ${item.name || '未命名'}，${Number(item.durationMs || 0)} 毫秒${item.error ? '，错误' : ''}${item.slow ? '，慢调用' : ''}`)
    const label = node('span', 'timeline-label')
    label.append(node('small', `timeline-kind kind-${String(item.kind || 'other').toLowerCase()}`, item.kind || 'Trace'))
    label.append(node('span', 'timeline-name', item.name || '未命名调用'))
    if (item.error) label.append(node('span', 'tree-state error-state', '错误'))
    if (item.slow) label.append(node('span', 'tree-state slow-state', '慢调用'))
    const track = node('span', 'timeline-track')
    const bar = node('i', `timeline-bar${item.error ? ' error-fill' : item.slow ? ' slow-fill' : ''}`)
    const offset = hasOffsets ? Math.max(0, Number(item.startOffsetMs || 0)) : 0
    const duration = Math.max(1, Number(item.durationMs || 0))
    bar.style.left = `${Math.min(99, offset / maxMs * 100)}%`
    bar.style.width = `${Math.max(0.7, Math.min(100 - offset / maxMs * 100, duration / maxMs * 100))}%`
    bar.title = `${offset} ms 开始 · ${duration} ms 持续`
    track.append(bar)
    row.append(label, track, node('span', 'timeline-duration', `${Number(item.durationMs || 0).toLocaleString()} ms`))
    row.addEventListener('click', () => {
      if (selectedRow) selectedRow.setAttribute('aria-pressed', 'false')
      selectedRow = row
      row.setAttribute('aria-pressed', 'true')
      renderSpanDetail(detailContainer, item, offset, hasOffsets)
    })
    row.setAttribute('aria-pressed', 'false')
    container.append(row)
    if (primary && item === primary.item) primaryRow = row
  }
  primaryRow?.click()
}

function renderSpanDetail(container, item, offset, hasOffsets) {
  container.replaceChildren()
  const heading = node('div', 'span-detail-head')
  heading.append(node('span', 'eyebrow', 'SPAN DETAIL'), node('h4', '', item.name || '未命名调用'))
  container.append(heading)
  const facts = node('div', 'span-facts')
  for (const [label, value] of [
    ['类型', item.kind || 'Trace'], ['耗时', `${Number(item.durationMs || 0).toLocaleString()} ms`],
    ['开始偏移', hasOffsets ? `${offset.toLocaleString()} ms` : '旧记录未采集'],
    ['线程', item.threadName ? `${item.threadName}${item.threadId ? ` · ${item.threadId}` : ''}` : '未记录'],
    ['状态', item.error ? `错误${item.errorType ? ` · ${item.errorType}` : ''}` : item.slow ? '慢调用' : '正常']
  ]) facts.append(node('div', 'span-fact', `${label}：${value}`))
  container.append(facts)
  if (item.tag) {
    const tag = node('pre', 'span-tag', item.tag)
    container.append(tag)
  }
}

async function openTrace(summary) {
  const requestId = ++detailRequest
  $('detail-title').textContent = summary.name || summary.traceId || '调用详情'
  $('facts').replaceChildren()
  $('error-stack-details').open = false
  $('tree').replaceChildren(node('p', 'tree-empty', '正在读取调用树…'))
  $('error-section').hidden = true
  $('detail-dialog').showModal()
  try {
    const detail = await request(`/traces/${encodeURIComponent(summary.traceId)}?source=${encodeURIComponent(summary.sourceType || '')}`)
    if (requestId !== detailRequest || !$('detail-dialog').open) return
    if (!detail) throw new Error('这条 Trace 已不在保留范围内')
    $('facts').replaceChildren()
    const resourceAlerts = [detail.highCpu ? '高 CPU' : '', detail.highAllocation ? '高分配' : ''].filter(Boolean)
    const result = [detail.error ? '错误' : '', detail.slow ? '慢调用' : '', ...resourceAlerts]
      .filter(Boolean).join(' · ') || '正常'
    addFact($('facts'), '结果', result, detail.error ? 'bad' : detail.slow || resourceAlerts.length ? 'warn' : '')
    addFact($('facts'), '状态', detail.status || '—')
    addFact($('facts'), '总耗时', `${Number(detail.durationMs || 0).toLocaleString()} ms`)
    addFact($('facts'), '入口', `${sourceNames[detail.sourceType] || 'Trace'} · ${detail.name || detail.method || '入口'}`)
    const traceIdFact = node('div', 'fact trace-id-fact wide')
    traceIdFact.append(node('small', '', 'Trace ID'), node('span', 'trace-id-value', detail.traceId))
    const copyTraceId = node('button', 'copy-trace-id', '复制 Trace ID')
    copyTraceId.type = 'button'
    copyTraceId.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(detail.traceId || '')
        copyTraceId.textContent = '已复制'
        window.setTimeout(() => { copyTraceId.textContent = '复制 Trace ID' }, 1500)
      } catch { copyTraceId.textContent = '复制失败' }
    })
    traceIdFact.append(copyTraceId)
    $('facts').append(traceIdFact)
    $('resource-facts-content').replaceChildren()
    addFact($('resource-facts-content'), '入口线程', detail.threadName || detail.threadId)
    const cpuTimeMs = detail.cpuTimeMs == null ? NaN : Number(detail.cpuTimeMs)
    addFact($('resource-facts-content'), '入口线程 CPU', Number.isFinite(cpuTimeMs) && cpuTimeMs >= 0
      ? `${cpuTimeMs.toLocaleString()} ms` : '未采集', '',
      '只统计入口线程在 Trace 期间消耗的 CPU 时间；不等于整个请求链路的 CPU 总和')
    addFact($('resource-facts-content'), '入口线程分配量', formatAllocatedBytes(detail.allocatedBytes), '',
      '入口线程在 Trace 期间累计分配的对象内存；不是当前堆占用，也不代表 DB 或远程服务的内存')
    renderTraceTree($('tree'), $('span-detail'), detail.treeNodes, Number(detail.durationMs || 0))
    if (detail.error || detail.errorType || detail.errorStackTrace) {
      $('error-section').hidden = false
      $('error-message').textContent = [detail.errorType, detail.errorMessage].filter(Boolean).join(': ') || '有错误标记，未捕获到异常堆栈。'
      $('error-stack').textContent = detail.errorStackTrace || '当前异常没有可用的堆栈。'
    }
  } catch (error) {
    if (requestId !== detailRequest || !$('detail-dialog').open) return
    $('tree').replaceChildren(node('p', 'tree-empty', error.message))
  }
}

async function refresh() {
  try {
    traces = await request('/traces?limit=500')
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = '已连接'
    $('notice').hidden = true
    render()
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = '连接失败'
    $('notice').textContent = `${error.message}。请检查应用权限和服务状态。`
    $('notice').hidden = false
  }
}

function bind() {
  for (const id of ['result-filter', 'source-filter']) $(id).addEventListener('change', () => { page = 0; render() })
  $('search').addEventListener('input', () => {
    window.clearTimeout(searchTimer)
    searchTimer = window.setTimeout(() => { page = 0; render() }, 140)
  })
  $('previous').addEventListener('click', () => { page = Math.max(0, page - 1); render() })
  $('next').addEventListener('click', () => { page += 1; render() })
  $('refresh').addEventListener('click', refresh)
  $('close-dialog').addEventListener('click', () => { detailRequest += 1; $('detail-dialog').close() })
  $('detail-dialog').addEventListener('close', () => { detailRequest += 1 })
  $('clear-history').addEventListener('click', () => $('clear-dialog').showModal())
  $('cancel-clear').addEventListener('click', () => $('clear-dialog').close())
  $('confirm-clear').addEventListener('click', async () => {
    $('confirm-clear').disabled = true
    try {
      await request('/storage/clear', { method: 'POST' })
      $('clear-dialog').close()
      await refresh()
    } catch (error) {
      $('notice').textContent = error.message
      $('notice').hidden = false
    } finally { $('confirm-clear').disabled = false }
  })
}

bind()
initializeEmbeddedAuth().then(refresh)
timer = window.setInterval(refresh, 10000)
window.addEventListener('beforeunload', () => window.clearInterval(timer), { once: true })
