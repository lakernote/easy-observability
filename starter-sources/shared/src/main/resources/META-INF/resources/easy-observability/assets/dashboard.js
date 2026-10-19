const api = '/api/easy-observability'
const byId = id => document.getElementById(id)
const labels = { HTTP: 'HTTP', JOB: 'Job', KAFKA_CONSUMER: 'Kafka 消费', REDIS: 'Redis', DATABASE: '数据库', SQL: '数据库', TRANSACTION: '事务', REMOTE: '远程调用', ELASTICSEARCH: 'Elasticsearch', KAFKA_PRODUCER: 'Kafka 生产' }
let requestStats = []
let traces = []
let slowOperations = []
let errorOperations = []
let selectedSource = 'ALL'
let seriesCatalog = []
let liveMetrics = []
let seriesCache = new Map()
let selectedSeriesKeys = new Set()
let seriesSelectionInitialized = false
const seriesLimit = 4
const recentPageSize = 30
let recentPage = 0

function safeText(value) { return value == null || value === '' ? '—' : String(value) }
function formatNumber(value, suffix = '') {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? `${number.toLocaleString(undefined, { maximumFractionDigits: 2 })}${suffix}` : '—'
}
function formatBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${formatNumber(bytes)} B`
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1048576).toFixed(1)} MiB`
}
function formatMetricValue(name, value) {
  if (name === 'process.cpu.usage' || name === 'system.cpu.usage') {
    return `${(Number(value) * 100).toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: 2 })}%`
  }
  if (name === 'jvm.memory.used') return formatBytes(value)
  return formatNumber(value)
}
function formatTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? safeText(value) : date.toLocaleString()
}
function formatChartTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return safeText(value)
  const options = Number(byId('period').value) <= 24
    ? { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }
    : { month: 'numeric', day: 'numeric' }
  return date.toLocaleString(undefined, options)
}
function esc(value) { return String(value ?? '').replaceAll('\\', '\\\\').replaceAll('"', '\\"') }
async function getJson(path) {
  const headers = { Accept: 'application/json' }
  let token = typeof window.EasyObservabilityAuth?.token === 'function' ? window.EasyObservabilityAuth.token() : ''
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${api}${path}`, { headers })
  if (response.status === 401 || response.status === 403) throw new Error('监控数据访问受限，请登录或配置 ObservabilityAccessPolicy')
  if (!response.ok) throw new Error(`读取监控数据失败：${response.status}`)
  return response.json()
}
function td(row, value, className = '') {
  const cell = document.createElement('td')
  cell.textContent = safeText(value)
  if (className) cell.className = className
  row.append(cell)
  return cell
}
function makeClickable(row, label, action) {
  row.classList.add('clickable')
  row.tabIndex = 0
  row.setAttribute('aria-label', label)
  row.addEventListener('click', action)
  row.addEventListener('keydown', event => {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    action()
  })
}
function periodStart() { return Date.now() - Number(byId('period').value) * 3600000 }
function inSelectedPeriod(trace) { return new Date(trace.finishedAt).getTime() >= periodStart() }
function showNotice(message = '') { byId('notice').textContent = message; byId('notice').hidden = !message }

function renderStats() {
  const visible = selectedSource === 'ALL' ? requestStats : requestStats.filter(item => item.source === selectedSource)
  const count = visible.reduce((sum, item) => sum + Number(item.count || 0), 0)
  const errors = visible.reduce((sum, item) => sum + Number(item.errorCount || 0), 0)
  const totalMs = visible.reduce((sum, item) => sum + Number(item.totalDurationMs || 0), 0)
  byId('total-count').textContent = formatNumber(count)
  byId('mean-duration').textContent = formatNumber(count ? totalMs / count : 0, ' ms')
  byId('error-rate').textContent = `${(count ? errors / count * 100 : 0).toFixed(2)}%`
  const rows = byId('entry-rows')
  rows.replaceChildren()
  const sumDuration = visible.reduce((sum, item) => sum + Number(item.totalDurationMs || 0), 0)
  for (const item of visible) {
    const row = document.createElement('tr')
    row.title = '查看保留的单次调用'
    makeClickable(row, `查看 ${labels[item.source] || item.source} ${item.operation} 的调用记录`, () => openEntryDetails(item))
    td(row, `${labels[item.source] || item.source} · ${item.operation}`)
    td(row, formatNumber(item.count), 'number')
    const shareCell = td(row, `${sumDuration ? (item.totalDurationMs / sumDuration * 100).toFixed(1) : '0.0'}%`, 'share')
    const bar = document.createElement('div'); bar.className = 'share-line'
    const fill = document.createElement('i'); fill.style.width = `${sumDuration ? Math.min(100, item.totalDurationMs / sumDuration * 100) : 0}%`; bar.append(fill); shareCell.append(bar)
    td(row, formatNumber(item.meanDurationMs, ' ms'), 'number')
    td(row, formatNumber(item.maxDurationMs, ' ms'), 'number')
    td(row, formatNumber(item.errorCount), 'number')
    td(row, `${Number(item.errorRate || 0).toFixed(2)}%`, 'number')
    td(row, formatNumber(item.meanCpuTimeMs, ' ms'), 'number')
    td(row, formatBytes(item.meanAllocatedBytes), 'number')
    rows.append(row)
  }
  byId('entry-empty').hidden = visible.length > 0
}

function entryOperationCandidates(trace) {
  return [
    `${trace.method || ''} · ${trace.name || ''}`,
    `${trace.name || ''} · ${trace.method || ''}`,
    `${trace.method || ''} ${trace.name || ''}`,
    trace.name || '', trace.method || ''
  ].map(value => value.trim().toLocaleLowerCase()).filter(Boolean)
}
function openEntryDetails(item) {
  const matches = traces.filter(trace => trace.sourceType === item.source
    && entryOperationCandidates(trace).some(candidate => candidate === item.operation.trim().toLocaleLowerCase()))
  byId('entry-dialog-title').textContent = `${labels[item.source] || item.source} · ${item.operation}`
  byId('entry-dialog-note').textContent = `累计 ${formatNumber(item.count)} 次；以下是当前 Trace 保留窗口中匹配的 ${matches.length} 条调用。`
  const rows = byId('entry-trace-rows'); rows.replaceChildren()
  for (const trace of matches.slice(0, 100)) {
    const row = document.createElement('tr'); makeClickable(row, `查看 Trace ${trace.traceId}`, () => {
      byId('entry-dialog').close(); openTrace(trace)
    })
    td(row, trace.error ? '错误' : trace.slow ? '慢' : '正常', `status ${trace.error ? 'error' : trace.slow ? 'slow' : 'ok'}`)
    td(row, formatNumber(trace.status), 'number'); td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, trace.traceId, 'mono'); td(row, formatTime(trace.finishedAt)); rows.append(row)
  }
  if (!matches.length) {
    const row = document.createElement('tr'); const cell = document.createElement('td')
    cell.colSpan = 5; cell.className = 'empty'; cell.textContent = '当前保留窗口没有匹配的 Trace；累计指标仍可用。'
    row.append(cell); rows.append(row)
  }
  byId('entry-dialog').showModal()
}

function traceEntryName(trace) {
  const name = (trace.name || '').trim()
  const detail = (trace.method || '').trim()
  if (!name) return detail
  if (!detail || name.includes(detail)) return name
  return `${detail} ${name}`
}

async function openTrace(trace) {
  byId('dialog-title').textContent = `${labels[trace.sourceType] || trace.sourceType || 'Trace'} · ${traceEntryName(trace)}`.trim()
  byId('trace-facts').replaceChildren()
  const facts = [
    `状态 ${trace.status ?? '—'}`, `耗时 ${formatNumber(trace.durationMs, ' ms')}`,
    `CPU ${formatNumber(trace.cpuTimeMs, ' ms')}`, `分配内存 ${formatBytes(trace.allocatedBytes)}`,
    `线程 ${formatNumber(trace.threadId)}`, `Trace ID ${trace.traceId || '—'}`,
    `完成 ${formatTime(trace.finishedAt)}`
  ]
  for (const fact of facts) { const span = document.createElement('span'); span.textContent = fact; byId('trace-facts').append(span) }
  byId('trace-tree').textContent = '正在读取调用树和下游明细…'
  byId('operation-rows').replaceChildren()
  byId('trace-dialog').showModal()
  let detail = trace
  try { detail = await getJson(`/traces/${encodeURIComponent(trace.traceId)}`) || trace }
  catch (error) { byId('trace-tree').textContent = error?.message || '读取 Trace 明细失败'; return }
  const focusedOperation = trace.incidentOperation
  if (focusedOperation?.type === 'KAFKA_CONSUMER') {
    byId('trace-tree').textContent = `\`- [Kafka 消费] ${focusedOperation.name || 'receive'}${focusedOperation.detail ? ` · ${focusedOperation.detail}` : ''} ${formatNumber(focusedOperation.durationMs, ' ms')}\n\nKafka 消费在 Listener 线程异步执行。下方明细按实际 Span 关系展示同 Trace 操作；仅 Trace ID 相同的操作不会误画成父子调用。`
  } else {
    byId('trace-tree').textContent = detail.tree || '此请求未触发慢或错误，未保存完整调用树；已采集的下游操作见下表。'
  }
  const operationRows = byId('operation-rows'); operationRows.replaceChildren()
  const renderedRows = new Set()
  for (const { operation, depth } of orderOperations(detail.operations || [])) {
    const row = document.createElement('tr')
    td(row, labels[operation.type] || operation.type)
    const operationName = `${depth ? `${'　'.repeat(depth)}↳ ` : ''}${operation.name || ''}${operation.detail ? ` · ${operation.detail}` : ''}`
    td(row, operationName)
    td(row, formatNumber(operation.durationMs, ' ms'), 'number')
    td(row, formatNumber(operation.cpuTimeMs, ' ms'), 'number')
    td(row, formatBytes(operation.allocatedBytes), 'number')
    td(row, `${operation.threadName || '—'} (${formatNumber(operation.threadId)})`)
    td(row, operation.error ? '错误' : operation.slow ? '慢' : '正常', operation.error ? 'status error' : operation.slow ? 'status slow' : 'status ok')
    const errorCell = td(row, [operation.errorType, operation.errorMessage].filter(Boolean).join(': '))
    if (operation.stackTrace) {
      const details = document.createElement('details')
      const summary = document.createElement('summary'); summary.textContent = '查看异常堆栈'
      const stack = document.createElement('pre'); stack.textContent = operation.stackTrace
      details.append(summary, stack); errorCell.append(details)
    }
    const visibleRow = row.textContent
    if (renderedRows.has(visibleRow)) continue
    renderedRows.add(visibleRow)
    operationRows.append(row)
  }
}

function orderOperations(operations) {
  const unique = []
  const seen = new Set()
  for (const operation of operations) {
    // Span IDs may differ when the same dependency was captured through two adapters. Collapse only
    // rows that are indistinguishable in the table; different timing/resource values remain separate.
    const identity = JSON.stringify([
      operation.traceId, labels[operation.type] || operation.type,
      operation.name, operation.detail, operation.threadName, operation.threadId,
      formatNumber(operation.durationMs, ' ms'), formatNumber(operation.cpuTimeMs, ' ms'),
      formatBytes(operation.allocatedBytes), operation.outcome,
      operation.errorType, operation.errorMessage
    ])
    if (seen.has(identity)) continue
    seen.add(identity)
    unique.push(operation)
  }
  const byId = new Map()
  for (const operation of unique) {
    if (!operation.spanId) continue
    const candidates = byId.get(operation.spanId) || []
    candidates.push(operation)
    byId.set(operation.spanId, candidates)
  }
  const children = new Map()
  const roots = []
  const ordered = unique.sort((left, right) => new Date(left.finishedAt).getTime() - new Date(right.finishedAt).getTime())
  for (const operation of ordered) {
    const parentId = operation.parentSpanId
    const candidates = parentId && parentId !== operation.spanId
      ? (byId.get(parentId) || []).filter(candidate => candidate !== operation)
      : []
    // 每个线程的根 Span 都从本地编号 1 开始；同 Trace 的异步消费者可能与 HTTP 根节点编号相同。
    // 父子关系优先匹配同一线程，避免仅因 Span ID 相同就把 Kafka consumer 错挂到 HTTP 调用树。
    const sameThread = candidates.filter(candidate => candidate.threadId === operation.threadId
      && candidate.threadName === operation.threadName)
    const parent = sameThread.length === 1 ? sameThread[0] : candidates.length === 1 ? candidates[0] : null
    if (parent) {
      const siblings = children.get(parent) || []
      siblings.push(operation)
      children.set(parent, siblings)
    } else roots.push(operation)
  }
  const result = []
  const visitedOperations = new Set()
  const visit = (operation, depth) => {
    if (visitedOperations.has(operation)) return
    visitedOperations.add(operation)
    result.push({ operation, depth })
    for (const child of children.get(operation) || []) visit(child, depth + 1)
  }
  roots.forEach(root => visit(root, 0))
  ordered.forEach(operation => {
    if (!visitedOperations.has(operation)) visit(operation, 0)
  })
  return result
}

function traceRow(trace, target) {
  const row = document.createElement('tr')
  makeClickable(row, `查看 Trace ${trace.traceId}`, () => openTrace(trace))
  td(row, `${labels[trace.sourceType] || trace.sourceType || '入口'} · ${traceEntryName(trace)}`.trim())
  if (target === 'slow') {
    td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, formatNumber(trace.cpuTimeMs, ' ms'), 'number')
    td(row, formatBytes(trace.allocatedBytes), 'number')
    td(row, formatNumber(trace.threadId), 'number')
  } else {
    td(row, formatNumber(trace.status), 'number')
    const root = trace.incidentOperation || (trace.operations || []).find(op => op.type === trace.sourceType)
    td(row, root ? [root.errorType, root.errorMessage].filter(Boolean).join(': ') : trace.tree?.split('\n').find(line => /ERROR|HTTP_5/.test(line)) || '—')
    td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, formatNumber(trace.threadId), 'number')
  }
  td(row, formatTime(trace.finishedAt))
  return row
}
function renderIncidentTables() {
  const incidentRows = (operations, flag) => {
    const rows = []
    const seen = new Set()
    for (const operation of operations) {
      if (!['HTTP', 'JOB', 'KAFKA_CONSUMER'].includes(operation.type) || !operation[flag]) continue
      const identity = `${operation.traceId}:${operation.type}:${operation.spanId}:${operation.finishedAt}`
      if (seen.has(identity)) continue
      seen.add(identity)
      const rootTrace = traces.find(trace => trace.traceId === operation.traceId)
      rows.push({
        ...(rootTrace || {}),
        id: `${operation.traceId}:${operation.type}:${operation.spanId}`,
        traceId: operation.traceId,
        sourceType: operation.type,
        name: rootTrace?.sourceType === operation.type ? rootTrace.name : operation.detail || operation.name,
        method: rootTrace?.sourceType === operation.type ? rootTrace.method : '',
        status: rootTrace?.sourceType === operation.type ? rootTrace.status : operation.error ? 500 : 200,
        durationMs: operation.durationMs,
        cpuTimeMs: operation.cpuTimeMs,
        allocatedBytes: operation.allocatedBytes,
        threadId: operation.threadId,
        finishedAt: operation.finishedAt,
        slow: operation.slow,
        error: operation.error,
        incidentOperation: operation,
        tree: rootTrace?.tree || `\`- [${operation.type}] ${operation.name}${operation.detail ? ` · ${operation.detail}` : ''} ${formatNumber(operation.durationMs, ' ms')}\n`,
        operations: rootTrace?.operations || [operation]
      })
    }
    // 老版本或已裁剪的操作记录可能没有保留入口 Operation，仍显示 Trace 根记录。
    for (const trace of traces) {
      if (!trace[flag] || rows.some(row => row.traceId === trace.traceId && row.sourceType === trace.sourceType)) continue
      rows.push(trace)
    }
    return rows.filter(inSelectedPeriod).sort((left, right) =>
      new Date(right.finishedAt).getTime() - new Date(left.finishedAt).getTime())
  }
  const slowMatches = incidentRows(slowOperations, 'slow')
  const errorMatches = incidentRows(errorOperations, 'error')
  const slow = slowMatches.slice(0, 50)
  const errors = errorMatches.slice(0, 50)
  byId('slow-count').textContent = formatNumber(slowMatches.length)
  byId('failure-count').textContent = formatNumber(errorMatches.length)
  byId('slow-total').textContent = `(${formatNumber(slowMatches.length)}${slowMatches.length > 50 ? '，显示前 50 条' : ''})`
  byId('error-total').textContent = `(${formatNumber(errorMatches.length)}${errorMatches.length > 50 ? '，显示前 50 条' : ''})`
  byId('slow-rows').replaceChildren(...slow.map(trace => traceRow(trace, 'slow')))
  byId('error-rows').replaceChildren(...errors.map(trace => traceRow(trace, 'error')))
  byId('slow-empty').hidden = slowMatches.length > 0
  byId('error-empty').hidden = errorMatches.length > 0
  const pageCount = Math.max(1, Math.ceil(traces.length / recentPageSize))
  recentPage = Math.min(recentPage, pageCount - 1)
  const start = recentPage * recentPageSize
  const recent = traces.slice(start, start + recentPageSize)
  const rows = byId('trace-rows'); rows.replaceChildren()
  for (const trace of recent) {
    const row = document.createElement('tr')
    makeClickable(row, `查看 Trace ${trace.traceId}`, () => openTrace(trace))
    const result = td(row, trace.error ? '错误' : trace.slow ? '慢' : '正常', `status ${trace.error ? 'error' : trace.slow ? 'slow' : 'ok'}`)
    td(row, `${labels[trace.sourceType] || trace.sourceType || '入口'} · ${traceEntryName(trace)}`.trim())
    td(row, formatNumber(trace.status), 'number'); td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, trace.traceId, 'mono'); td(row, formatTime(trace.finishedAt)); rows.append(row)
  }
  byId('trace-empty').hidden = recent.length > 0
  byId('trace-page').textContent = traces.length ? `${start + 1}–${start + recent.length} 条，共 ${traces.length} 条（最多读取最近 500 条）` : ''
  byId('trace-prev').disabled = recentPage <= 0
  byId('trace-next').disabled = recentPage >= pageCount - 1
}

function metricLabel(series) {
  const memoryPool = series.tags?.id ? ` · ${series.tags.id}` : ''
  const known = {
    'process.cpu.usage': '进程 CPU 使用率',
    'system.cpu.usage': '系统 CPU 使用率',
    'jvm.memory.used': `${series.tags?.area === 'heap' ? 'JVM 堆内存' : 'JVM 非堆内存'}已用${memoryPool}`,
    'jvm.memory.committed': `${series.tags?.area === 'heap' ? 'JVM 堆内存' : 'JVM 非堆内存'}已提交${memoryPool}`,
    'jvm.memory.max': `${series.tags?.area === 'heap' ? 'JVM 堆内存' : 'JVM 非堆内存'}上限${memoryPool}`,
    'hikaricp.connections.active': `数据库活跃连接${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'hikaricp.connections.idle': `数据库空闲连接${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'hikaricp.connections.pending': `数据库等待连接${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'hikaricp.connections.max': `数据库连接上限${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'tomcat.threads.busy': 'Tomcat 忙碌线程数',
    'tomcat.threads.current': 'Tomcat 当前线程数',
    'http.server.requests': 'HTTP 请求耗时',
    'process.uptime': '进程运行时长',
    'system.load.average.1m': '系统 1 分钟负载'
  }
  return known[series.name] || series.name
}
function seriesTitle(series) {
  const tags = Object.entries(series.tags || {})
    .filter(([key]) => !['application', 'environment', 'area', 'id', 'pool'].includes(key))
    .map(([key, value]) => `${key}=${value}`).join(', ')
  return `${metricLabel(series)}${tags ? ` · ${tags}` : ''}`
}
function seriesKey(series) {
  const tags = Object.entries(series.tags || {}).sort(([left], [right]) => left.localeCompare(right))
  return `${series.name}|${tags.map(([key, value]) => `${key}=${value}`).join('&')}`
}
function chooseSeries() {
  const liveKeys = new Set(liveMetrics.map(seriesKey))
  const currentSeries = seriesCatalog.filter(series => liveKeys.has(seriesKey(series)))
  const find = (name, predicate = () => true) => currentSeries.find(item => item.name === name && predicate(item))
    || seriesCatalog.find(item => item.name === name && predicate(item))
  const priority = [
    find('process.cpu.usage'),
    find('jvm.memory.used', item => item.tags?.area === 'heap'),
    find('system.cpu.usage'),
    find('hikaricp.connections.active'),
    find('http.server.requests')
  ].filter(Boolean)
  const seen = new Set()
  return priority.filter(item => { const key = seriesTitle(item); if (seen.has(key)) return false; seen.add(key); return true }).slice(0, seriesLimit)
}
function renderSeriesPicker() {
  if (!seriesSelectionInitialized) {
    selectedSeriesKeys = new Set(chooseSeries().map(seriesKey))
    seriesSelectionInitialized = true
  }
  const options = byId('trend-options')
  const query = byId('trend-search').value.trim().toLocaleLowerCase()
  const filtered = seriesCatalog.filter(series => !query || seriesTitle(series).toLocaleLowerCase().includes(query))
  const fragment = document.createDocumentFragment()
  for (const series of filtered) {
    const key = seriesKey(series)
    const label = document.createElement('label')
    label.className = 'trend-option'
    const checkbox = document.createElement('input')
    checkbox.type = 'checkbox'
    checkbox.value = key
    checkbox.checked = selectedSeriesKeys.has(key)
    checkbox.disabled = !checkbox.checked && selectedSeriesKeys.size >= seriesLimit
    const text = document.createElement('span')
    text.textContent = seriesTitle(series)
    label.append(checkbox, text)
    fragment.append(label)
  }
  options.replaceChildren(fragment)
  byId('trend-selection-note').textContent = `${selectedSeriesKeys.size}/${seriesLimit} 项已选择${filtered.length < seriesCatalog.length ? ` · 显示 ${filtered.length}/${seriesCatalog.length} 项` : ''}`
}
function drawChart(card, points, measurement, series, colorClass = '') {
  const ns = 'http://www.w3.org/2000/svg'
  const svg = document.createElementNS(ns, 'svg'); svg.setAttribute('viewBox', '0 0 440 180'); svg.setAttribute('preserveAspectRatio', 'none'); svg.setAttribute('role', 'img')
  const title = document.createElementNS(ns, 'title'); title.textContent = `${seriesTitle(series)} 指标趋势`; svg.append(title)
  for (const y of [30, 78, 126]) { const line = document.createElementNS(ns, 'line'); line.setAttribute('x1', '52'); line.setAttribute('x2', '430'); line.setAttribute('y1', String(y)); line.setAttribute('y2', String(y)); line.setAttribute('class', 'chart-grid'); svg.append(line) }
  const values = points.map(point => Number(point.measurements?.[measurement])).filter(Number.isFinite)
  const path = document.createElementNS(ns, 'path'); path.setAttribute('class', `chart-line ${colorClass}`)
  if (values.length > 1) {
    const min = Math.min(...values); const max = Math.max(...values); const range = max - min || 1
    const d = values.map((value, index) => `${index ? 'L' : 'M'} ${52 + index * 378 / (values.length - 1)} ${126 - (value - min) / range * 94}`).join(' ')
    path.setAttribute('d', d); svg.append(path)
    const lastX = 430; const lastY = 126 - (values.at(-1) - min) / range * 94
    const dot = document.createElementNS(ns, 'circle'); dot.setAttribute('cx', String(lastX)); dot.setAttribute('cy', String(lastY)); dot.setAttribute('r', '3.5'); dot.setAttribute('class', `chart-dot ${colorClass}`); svg.append(dot)
    const high = document.createElementNS(ns, 'text'); high.setAttribute('x', '2'); high.setAttribute('y', '33'); high.setAttribute('class', 'chart-label'); high.textContent = formatMetricValue(series.name, max); svg.append(high)
    const mid = document.createElementNS(ns, 'text'); mid.setAttribute('x', '2'); mid.setAttribute('y', '81'); mid.setAttribute('class', 'chart-label'); mid.textContent = formatMetricValue(series.name, (min + max) / 2); svg.append(mid)
    const low = document.createElementNS(ns, 'text'); low.setAttribute('x', '2'); low.setAttribute('y', '129'); low.setAttribute('class', 'chart-label'); low.textContent = formatMetricValue(series.name, min); svg.append(low)
    const firstTime = document.createElementNS(ns, 'text'); firstTime.setAttribute('x', '52'); firstTime.setAttribute('y', '157'); firstTime.setAttribute('class', 'chart-label time-label'); firstTime.textContent = formatChartTime(points[0].capturedAt); svg.append(firstTime)
    const lastTime = document.createElementNS(ns, 'text'); lastTime.setAttribute('x', '430'); lastTime.setAttribute('y', '157'); lastTime.setAttribute('text-anchor', 'end'); lastTime.setAttribute('class', 'chart-label time-label'); lastTime.textContent = formatChartTime(points.at(-1).capturedAt); svg.append(lastTime)
    const latest = document.createElement('strong'); latest.className = 'trend-current'; latest.textContent = formatMetricValue(series.name, values.at(-1)); card.append(latest)
  } else {
    const msg = document.createElementNS(ns, 'text'); msg.setAttribute('x', '160'); msg.setAttribute('y', '82'); msg.setAttribute('class', 'chart-label'); msg.textContent = '等待首个采样点'; svg.append(msg)
    const latest = document.createElement('strong'); latest.className = 'trend-current is-empty'; latest.textContent = '暂无数据'; card.append(latest)
  }
  card.append(svg)
}
async function renderTrends() {
  const grid = byId('trend-grid'); grid.replaceChildren()
  const seriesByKey = new Map(seriesCatalog.map(series => [seriesKey(series), series]))
  const selected = [...selectedSeriesKeys].map(key => seriesByKey.get(key)).filter(Boolean).slice(0, seriesLimit)
  if (!selected.length) { const p = document.createElement('p'); p.className = 'empty'; p.textContent = '当前没有可显示的 Micrometer 历史指标。'; grid.append(p); return }
  const placeholders = selected.map((series, index) => {
    const card = document.createElement('article'); card.className = 'trend-card is-loading'
    const heading = document.createElement('div'); heading.className = 'trend-card-heading'
    const title = document.createElement('h3'); title.textContent = seriesTitle(series)
    const badge = document.createElement('span'); badge.className = 'series-kind'; badge.textContent = series.tags?.area || series.tags?.type || '指标'
    heading.append(title, badge); card.append(heading)
    const skeleton = document.createElement('div'); skeleton.className = 'chart-skeleton'; skeleton.setAttribute('aria-label', '正在加载趋势'); card.append(skeleton)
    grid.append(card)
    return { series, card, key: seriesTitle(series), index }
  })
  // MetricHistoryStore deliberately admits one disk reader at a time. Load charts in order to avoid
  // turning a multi-select into concurrent requests that trip its bounded-read protection.
  for (const { series, card, key, index } of placeholders) {
    try {
      let points = seriesCache.get(key)
      if (!points) {
        // The longest visible range is 30 days. RRD4J uses at most 30-minute points there,
        // so 5,000 points cover the full chart without transferring the API's 50,000-point cap.
        const params = new URLSearchParams({ name: series.name, tags: JSON.stringify(series.tags || {}), limit: '5000' })
        points = await getJson(`/metrics/history?${params}`)
        seriesCache.set(key, points)
      }
      const measurement = (series.measurements || []).includes('VALUE') ? 'VALUE' : series.measurements?.[0]
      const from = periodStart()
      const windowPoints = points.filter(point => new Date(point.capturedAt).getTime() >= from
        && !(series.name === 'jvm.memory.used' && Number(point.measurements?.[measurement]) <= 0)).slice().reverse()
      card.querySelector('.chart-skeleton')?.remove()
      card.classList.remove('is-loading')
      drawChart(card, windowPoints, measurement, series, ['','alt','green','purple'][index])
    } catch (error) {
      card.classList.remove('is-loading')
      const message = document.createElement('p'); message.className = 'chart-error'; message.textContent = error?.message || '读取趋势失败'; card.append(message)
    }
  }
}

async function refresh() {
  byId('refresh').disabled = true; showNotice('')
  try {
    const [statsResult, tracesResult, metricsResult, slowResult, errorsResult] = await Promise.allSettled([
      getJson('/request-statistics?source=ALL&limit=500'), getJson('/traces?limit=500'), getJson('/metrics?limit=2000'),
      getJson('/slow?limit=500'), getJson('/errors?limit=500')
    ])
    const catalogResult = await getJson('/metrics/series').then(
      value => ({ status: 'fulfilled', value }), reason => ({ status: 'rejected', reason }))
    const storageResult = await getJson('/metrics/storage').then(
      value => ({ status: 'fulfilled', value }), reason => ({ status: 'rejected', reason }))
    const failed = []
    if (statsResult.status === 'fulfilled') { requestStats = statsResult.value; renderStats() } else failed.push(statsResult.reason?.message)
    if (tracesResult.status === 'fulfilled') traces = tracesResult.value
    else failed.push(tracesResult.reason?.message)
    if (slowResult.status === 'fulfilled') slowOperations = slowResult.value
    else failed.push(slowResult.reason?.message)
    if (errorsResult.status === 'fulfilled') errorOperations = errorsResult.value
    else failed.push(errorsResult.reason?.message)
    if (tracesResult.status === 'fulfilled' || slowResult.status === 'fulfilled' || errorsResult.status === 'fulfilled') renderIncidentTables()
    if (catalogResult.status === 'fulfilled') { seriesCatalog = catalogResult.value; seriesCache.clear() } else failed.push(catalogResult.reason?.message)
    const storageWarnings = []
    if (storageResult.status === 'fulfilled' && storageResult.value) {
      const storage = storageResult.value
      const storageSize = `${formatBytes(storage.storageBytes)} / ${formatBytes(storage.maxStorageBytes)}`
      const seriesCount = catalogResult.status === 'fulfilled' ? catalogResult.value.length : 0
      const storageText = storage.enabled
        ? `本地历史 · ${storageSize} · ${formatNumber(seriesCount)} 条序列`
        : '本地历史存储已关闭'
      byId('instance').textContent = `当前实例 · ${storage.enabled ? `RRD ${formatBytes(storage.storageBytes)}` : '历史未启用'}`
      byId('metric-storage-status').textContent = storageText
      const hasDrops = Number(storage.droppedWrites) > 0 || Number(storage.rejectedSeries) > 0
      byId('metric-storage-status').classList.toggle('is-warning', hasDrops)
      byId('instance').classList.toggle('is-warning', hasDrops)
      if (Number(storage.droppedWrites) > 0) storageWarnings.push(`指标历史有 ${formatNumber(storage.droppedWrites)} 次快照未完整写入`)
      if (Number(storage.rejectedSeries) > 0) storageWarnings.push(`${formatNumber(storage.rejectedSeries)} 条指标序列因存储限制被拒绝`)
    } else if (storageResult.status === 'rejected') failed.push(storageResult.reason?.message)
    if (metricsResult.status === 'fulfilled') liveMetrics = metricsResult.value
    else { liveMetrics = []; failed.push(metricsResult.reason?.message) }
    if (catalogResult.status === 'fulfilled') { renderSeriesPicker(); await renderTrends() }
    const notices = [...failed.filter(Boolean), ...storageWarnings]
    if (notices.length) {
      showNotice(notices.join('；'))
      byId('status-dot').className = 'live-dot is-warning'
      byId('updated').textContent = `部分数据异常 · ${new Date().toLocaleTimeString()}`
    } else {
      showNotice('')
      byId('status-dot').className = 'live-dot'
      byId('updated').textContent = `更新于 ${new Date().toLocaleTimeString()}`
    }
  } catch (error) {
    showNotice(error?.message || '加载监控报告失败')
    byId('status-dot').className = 'live-dot is-warning'
    byId('updated').textContent = `更新失败 · ${new Date().toLocaleTimeString()}`
  }
  finally { byId('refresh').disabled = false }
}

byId('refresh').addEventListener('click', refresh)
byId('trace-prev').addEventListener('click', () => { recentPage = Math.max(0, recentPage - 1); renderIncidentTables() })
byId('trace-next').addEventListener('click', () => { recentPage++; renderIncidentTables() })
byId('close-entry-dialog').addEventListener('click', () => byId('entry-dialog').close())
byId('trend-search').addEventListener('input', renderSeriesPicker)
const sectionLinks = [...document.querySelectorAll('.primary-nav a')]
const sectionObserver = new IntersectionObserver(entries => {
  const visible = entries.filter(entry => entry.isIntersecting).sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0]
  if (!visible) return
  sectionLinks.forEach(link => {
    const active = link.hash === `#${visible.target.id}`
    link.classList.toggle('active', active)
    if (active) link.setAttribute('aria-current', 'location')
    else link.removeAttribute('aria-current')
  })
}, { rootMargin: '-18% 0px -70% 0px', threshold: [0, 0.15, 0.35] })
for (const section of document.querySelectorAll('#overview, #metrics-section, #entry-section, #incidents-section, #traces-section')) sectionObserver.observe(section)
byId('trend-options').addEventListener('change', event => {
  const checkbox = event.target.closest('input[type="checkbox"]')
  if (!checkbox) return
  if (checkbox.checked) selectedSeriesKeys.add(checkbox.value)
  else selectedSeriesKeys.delete(checkbox.value)
  renderSeriesPicker()
  renderTrends().catch(error => showNotice(error?.message || '读取指标趋势失败'))
})
byId('period').addEventListener('change', () => { renderIncidentTables(); renderTrends().catch(error => showNotice(error.message)) })
byId('entry-filters').addEventListener('click', event => {
  const button = event.target.closest('button[data-source]')
  if (!button) return
  selectedSource = button.dataset.source
  byId('entry-filters').querySelectorAll('button').forEach(item => {
    const active = item === button
    item.classList.toggle('selected', active)
    item.setAttribute('aria-pressed', String(active))
  })
  renderStats()
})
byId('close-dialog').addEventListener('click', () => byId('trace-dialog').close())
refresh()
