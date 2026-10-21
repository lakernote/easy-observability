const api = '/api/easy-observability'
const byId = id => document.getElementById(id)
const labels = { HTTP: 'HTTP', JOB: 'Job', EXECUTOR_TASK: '线程池任务', KAFKA_CONSUMER: 'Kafka 消费', REDIS: 'Redis', DATABASE: '数据库', SQL: '数据库', TRANSACTION: '事务', REMOTE: '远程调用', ELASTICSEARCH: 'Elasticsearch', KAFKA_PRODUCER: 'Kafka 生产' }
let requestStats = []
let traces = []
let slowIncidents = []
let errorIncidents = []
let selectedSource = 'ALL'
let seriesCatalog = []
let liveMetrics = []
let metricDataLoaded = false
let metricDataLoading = null
let usefulSeriesLimit = 64
let seriesCache = new Map()
let seriesLoading = new Map()
let seriesCacheGeneration = 0
let redHistoryCache = null
let redLoading = null
let metricTimeRange = null
let renderedMetricRangeKey = ''
let trendHistoryObserver = null
let trendRenderVersion = 0
let selectedRedMetric = 'requests'
let visibleRedUris = null
let redUriCatalog = new Set()
let historyRequestQueue = Promise.resolve()
const httpSeriesLimit = 48
const httpUriLimit = 8
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
function formatMetricValue(name, value, measurement = 'VALUE') {
  if (name === 'process.cpu.usage' || name === 'system.cpu.usage') {
    return `${(Number(value) * 100).toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: 2 })}%`
  }
  if (name.startsWith('jvm.memory.') || ['jvm.gc.live.data.size', 'jvm.gc.max.data.size', 'jvm.buffer.memory.used'].includes(name)) return formatBytes(value)
  if (name === 'jvm.gc.overhead') return `${(Number(value) * 100).toLocaleString(undefined, { maximumFractionDigits: 2 })}%`
  if (measurement === 'COUNT') return `${compactAxisValue(value)} 次`
  if (measurement === 'RATE') return isByteCounter(name) ? `${formatBytes(value)}/分钟` : `${compactAxisValue(value)} 次/分钟`
  if (measurement === 'INTERVAL_AVG') return `${formatNumber(value)} ms`
  if (measurement === 'TOTAL_TIME' || measurement === 'MAX') return `${formatNumber(value)} ms`
  if (name === 'process.uptime') return `${formatNumber(value)} 秒`
  if (name === 'system.load.average.1m') return `${compactAxisValue(value)} 负载`
  if (/^jvm\.classes\.|\.threads\.|\.connections\.|\.pool\.(active|idle|max|size|pending)|\.buffer\.count|process\.files\.|tomcat\.sessions\./.test(name)) return `${compactAxisValue(value)} 个`
  return compactAxisValue(value)
}
function metricAxisUnit(name, measurement) {
  if (name === 'process.cpu.usage' || name === 'system.cpu.usage' || name === 'jvm.gc.overhead') return '%'
  if (name.startsWith('jvm.memory.') || ['jvm.gc.live.data.size', 'jvm.gc.max.data.size', 'jvm.buffer.memory.used'].includes(name)) return '内存'
  if (name.startsWith('jvm.classes.') || name.startsWith('tomcat.sessions.') || name === 'logback.events') return '数量'
  if (measurement === 'COUNT') return '调用数（次）'
  if (measurement === 'RATE') return isByteCounter(name) ? '增量（B/分钟）' : '新增（次/分钟）'
  if (measurement === 'INTERVAL_AVG') return '平均耗时（ms）'
  if (measurement === 'TOTAL_TIME' || measurement === 'MAX') return '耗时（ms）'
  if (name === 'process.uptime') return '秒'
  if (name === 'system.load.average.1m') return '负载'
  if (name.startsWith('executor.')) return '数量'
  if (/\.threads\.|\.connections\.|\.pool\.(active|idle|max|size|pending)|\.buffer\.count|process\.files\./.test(name)) return '数量'
  return ''
}
function metricAxisTick(name, value, measurement = 'VALUE') {
  if (name === 'process.cpu.usage' || name === 'system.cpu.usage' || name === 'jvm.gc.overhead') {
    return `${(Number(value) * 100).toLocaleString(undefined, { maximumFractionDigits: 2 })}%`
  }
  if (name.startsWith('jvm.memory.') || ['jvm.gc.live.data.size', 'jvm.gc.max.data.size', 'jvm.buffer.memory.used'].includes(name)) return formatBytes(value)
  if (measurement === 'RATE') return isByteCounter(name) ? `${formatBytes(value)}/分` : `${compactAxisValue(value)}/分`
  if (measurement === 'INTERVAL_AVG') return `${formatNumber(value)} ms`
  if (name === 'process.uptime') return `${compactAxisValue(value)} 秒`
  return compactAxisValue(value)
}
const intervalCounterNames = new Set([
  'jvm.classes.unloaded', 'jvm.gc.memory.allocated', 'jvm.gc.memory.promoted',
  'logback.events', 'tomcat.sessions.created', 'tomcat.sessions.expired',
  'tomcat.sessions.rejected', 'executor.completed'
])
function isByteCounter(name) {
  return name === 'jvm.gc.memory.allocated' || name === 'jvm.gc.memory.promoted'
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
function getHistoryJson(path) {
  const request = historyRequestQueue.then(() => getJson(path))
  historyRequestQueue = request.catch(() => {})
  return request
}
function loadSeriesHistory(series, limit = 5000) {
  const key = seriesKey(series)
  if (seriesCache.has(key)) return Promise.resolve(seriesCache.get(key))
  if (seriesLoading.has(key)) return seriesLoading.get(key)
  const generation = seriesCacheGeneration
  const params = new URLSearchParams({ name: series.name, tags: JSON.stringify(series.tags || {}), limit: String(limit) })
  const request = getHistoryJson(`/metrics/history?${params}`)
    .then(points => { if (generation === seriesCacheGeneration) seriesCache.set(key, points); return points })
    .finally(() => { if (seriesLoading.get(key) === request) seriesLoading.delete(key) })
  seriesLoading.set(key, request)
  return request
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
function captureMetricTimeRange() {
  const end = Date.now()
  return { start: end - Number(byId('period').value) * 3600000, end }
}
function currentMetricTimeRange() {
  if (!metricTimeRange) metricTimeRange = captureMetricTimeRange()
  return metricTimeRange
}
function inSelectedPeriod(trace) { return new Date(trace.finishedAt).getTime() >= periodStart() }
let metricRetentionDays = null
let metricStorageStatus = null
function updateMetricRangeNote() {
  const note = byId('metric-range-note')
  if (!note) return
  if (!Number.isFinite(metricRetentionDays) || metricRetentionDays <= 0) {
    note.textContent = '指标历史保留期尚未读取。'
    note.classList.remove('is-within-retention')
    return
  }
  const selectedHours = Number(byId('period').value)
  const selectedLabel = byId('period').selectedOptions[0]?.textContent || '当前时间范围'
  const selectedDays = selectedHours / 24
  if (selectedDays > metricRetentionDays) {
    note.textContent = `所选时间范围：${selectedLabel}。指标历史保留 ${formatNumber(metricRetentionDays)} 天，超出保留期的部分没有可查询的指标历史。`
    note.classList.remove('is-within-retention')
  } else {
    note.textContent = `所选时间范围：${selectedLabel}；指标历史保留 ${formatNumber(metricRetentionDays)} 天。`
    note.classList.add('is-within-retention')
  }
}
function updateMetricStorageLabel() {
  if (!metricStorageStatus) return
  const storage = metricStorageStatus
  const size = `${formatBytes(storage.storageBytes)} / ${formatBytes(storage.maxStorageBytes)}`
  const series = metricDataLoaded ? ` · ${formatNumber(seriesCatalog.length)} 条序列` : ''
  byId('metric-storage-status').textContent = storage.enabled
    ? `指标历史 · ${size}${series} · 保留 ${formatNumber(storage.retentionDays)} 天`
    : '本地历史存储已关闭'
}
function showNotice(message = '') { byId('notice').textContent = message; byId('notice').hidden = !message }
function isDashboardHttpTraffic(source, value) {
  if (source !== 'HTTP') return false
  const path = String(value || '').match(/\/(?:api\/)?easy-observability(?:\/[^\s·]*)?/i)?.[0]?.replace(/[?#].*$/, '').replace(/\/$/, '')
  return path === '/easy-observability' || path?.startsWith('/easy-observability/assets/')
    || path === '/api/easy-observability' || path?.startsWith('/api/easy-observability/')
}
function isDashboardTrace(trace) {
  return isDashboardHttpTraffic(trace.sourceType, `${trace.method || ''} ${trace.name || ''}`)
}
function requestStatisticLabel(item) {
  return item.source === 'HTTP'
    ? String(item.operation || '').replace(/\s*·\s*http\.server\.requests$/i, '')
    : item.operation
}
function traceOperationLabel(trace) {
  if (trace.sourceType === 'HTTP') {
    if (trace.method && (trace.name === 'http.server.requests' || /\s·\s(?:GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)\b/i.test(trace.method))) {
      return trace.method
    }
    return trace.name || trace.method || ''
  }
  return [trace.method, trace.name].filter(Boolean).join(' ')
}

function renderStats() {
  const visible = requestStats.filter(item => !isDashboardHttpTraffic(item.source, item.operation)
    && (selectedSource === 'ALL' || item.source === selectedSource))
  const rows = byId('entry-rows')
  rows.replaceChildren()
  const sumDuration = visible.reduce((sum, item) => sum + Number(item.totalDurationMs || 0), 0)
  for (const item of visible) {
    const row = document.createElement('tr')
    row.title = '查看保留的单次调用'
    makeClickable(row, `查看 ${labels[item.source] || item.source} ${requestStatisticLabel(item)} 的调用记录`, () => openEntryDetails(item))
    td(row, `${labels[item.source] || item.source} · ${requestStatisticLabel(item)}`)
    td(row, formatNumber(item.count), 'number')
    const shareCell = td(row, `${sumDuration ? (item.totalDurationMs / sumDuration * 100).toFixed(1) : '0.0'}%`, 'share')
    const bar = document.createElement('div'); bar.className = 'share-line'
    const fill = document.createElement('i'); fill.style.width = `${sumDuration ? Math.min(100, item.totalDurationMs / sumDuration * 100) : 0}%`; bar.append(fill); shareCell.append(bar)
    td(row, formatNumber(item.meanDurationMs, ' ms'), 'number')
    td(row, Number(item.maxDurationMs) > 0 ? formatNumber(item.maxDurationMs, ' ms') : '—', 'number')
    td(row, formatNumber(item.errorCount), 'number')
    td(row, `${Number(item.errorRate || 0).toFixed(2)}%`, 'number')
    td(row, formatNumber(item.meanCpuTimeMs, ' ms'), 'number')
    td(row, formatBytes(item.meanAllocatedBytes), 'number')
    rows.append(row)
  }
  byId('entry-empty').hidden = visible.length > 0
}

async function openEntryDetails(item) {
  byId('entry-dialog-title').textContent = `${labels[item.source] || item.source} · ${requestStatisticLabel(item)}`
  const rows = byId('entry-trace-rows'); rows.replaceChildren()
  const loadingRow = document.createElement('tr'); const loadingCell = document.createElement('td')
  loadingCell.colSpan = 5; loadingCell.className = 'empty'; loadingCell.textContent = '正在读取当前进程保留的调用…'
  loadingRow.append(loadingCell); rows.append(loadingRow)
  byId('entry-dialog-note').textContent = '正在刷新当前进程的指标和 Trace 保留记录…'
  byId('entry-dialog').showModal()
  const params = new URLSearchParams({ source: item.source, operation: item.operation, limit: '100' })
  let matches
  let currentItem = item
  try {
    const [latestStats, latestTraces] = await Promise.all([
      getJson('/request-statistics?source=ALL&limit=200'), getJson(`/traces?${params}`)
    ])
    requestStats = latestStats || requestStats
    renderStats()
    currentItem = requestStats.find(stat => stat.source === item.source && stat.operation === item.operation) || item
    matches = latestTraces || []
  }
  catch (error) {
    const cell = loadingRow.querySelector('td'); cell.textContent = error?.message || '读取调用记录失败'
    byId('entry-dialog-note').textContent = '刷新当前进程指标或读取调用记录失败。'
    return
  }
  byId('entry-dialog-note').textContent = `Micrometer 当前进程累计 ${formatNumber(currentItem.count)} 次；Trace 保留窗口中匹配 ${matches.length}${matches.length >= 100 ? '+' : ''} 条调用。`
  rows.replaceChildren()
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
    cell.colSpan = 5; cell.className = 'empty'; cell.textContent = '当前进程的 Trace 保留窗口中没有匹配记录；累计指标仍可用。'
    row.append(cell); rows.append(row)
  }
  byId('entry-dialog').showModal()
}

async function openTrace(trace) {
  byId('dialog-title').textContent = `${labels[trace.sourceType] || trace.sourceType || 'Trace'} · ${traceOperationLabel(trace)}`.trim()
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
  byId('trace-operations-note').hidden = true
  byId('trace-operations-note').textContent = ''
  byId('trace-dialog').showModal()
  let detail = trace
  const sourceQuery = trace.sourceType ? `?source=${encodeURIComponent(trace.sourceType)}` : ''
  try { detail = await getJson(`/traces/${encodeURIComponent(trace.traceId)}${sourceQuery}`) || trace }
  catch (error) { byId('trace-tree').textContent = error?.message || '读取 Trace 明细失败'; return }
  byId('trace-tree').textContent = detail.tree || renderOperationTree(detail)
  const treeNodeCount = String(detail.tree || '').split('\n').filter(line => line.trim()).length
  const hasStructuredChild = (detail.operations || []).some(operation =>
    operation.type !== detail.sourceType || Boolean(operation.parentSpanId))
  if (treeNodeCount > 1 && detail.tree && hasStructuredChild) {
    byId('trace-operations-note').textContent = '调用树展示执行层级；下表展示已采集的结构化操作。自定义代码段可能只出现在调用树中，CPU、内存等数值取决于对应集成。'
    byId('trace-operations-note').hidden = false
  } else if (treeNodeCount > 1 && !hasStructuredChild) {
    byId('trace-operations-note').textContent = '调用树包含下游节点，但当前集成只采集了入口级结构化明细；下游节点的独立 CPU、内存和线程数据尚未采集。'
    byId('trace-operations-note').hidden = false
  }
  const operationRows = byId('operation-rows'); operationRows.replaceChildren()
  const renderedRows = new Set()
  for (const { operation, depth } of orderOperations(detail.operations || [])) {
    const row = document.createElement('tr')
    td(row, labels[operation.type] || operation.type)
    const displayName = operationDisplayName(operation)
    const operationName = `${depth ? `${'　'.repeat(depth)}↳ ` : ''}${displayName}`
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
  const byId = new Map(unique.filter(item => item.spanId).map(item => [item.spanId, item]))
  const children = new Map()
  const roots = []
  const operationStart = operation => {
    const finishedAt = new Date(operation.finishedAt).getTime()
    return Number.isFinite(finishedAt) ? finishedAt - Math.max(0, Number(operation.durationMs) || 0) : 0
  }
  const ordered = unique.sort((left, right) => operationStart(left) - operationStart(right))
  for (const operation of ordered) {
    const parentId = operation.parentSpanId
    if (parentId && parentId !== operation.spanId && byId.has(parentId)) {
      const siblings = children.get(parentId) || []
      siblings.push(operation)
      children.set(parentId, siblings)
    } else roots.push(operation)
  }
  const result = []
  const visitedOperations = new Set()
  const visitedSpanIds = new Set()
  const visit = (operation, depth) => {
    if (visitedOperations.has(operation)) return
    if (operation.spanId && visitedSpanIds.has(operation.spanId)) {
      visitedOperations.add(operation)
      return
    }
    visitedOperations.add(operation)
    if (operation.spanId) visitedSpanIds.add(operation.spanId)
    result.push({ operation, depth })
    for (const child of children.get(operation.spanId) || []) visit(child, depth + 1)
  }
  roots.forEach(root => visit(root, 0))
  ordered.forEach(operation => {
    if (!visitedOperations.has(operation)) visit(operation, 0)
  })
  return result
}

function operationDisplayName(operation) {
  if (['http.server.requests', 'http.client.requests'].includes(operation.name) && operation.detail) {
    return operation.detail
  }
  return `${operation.name || ''}${operation.detail ? ` · ${operation.detail}` : ''}`
}

function renderOperationTree(trace) {
  const ordered = orderOperations(trace.operations || [])
  if (!ordered.length) return '没有可展示的调用树或下游操作记录。'
  const lines = ordered.map(({ operation, depth }) => {
    const kind = labels[operation.type] || operation.type || '操作'
    const name = operationDisplayName(operation) || kind
    return `${'  '.repeat(depth)}${depth ? '↳ ' : ''}[${kind}] ${name} · ${formatNumber(operation.durationMs, ' ms')}`
  })
  lines.push('', '根据已采集操作的父子关系和开始时间整理；未采集的代码段不会显示。')
  return lines.join('\n')
}

function traceRow(trace, target) {
  const row = document.createElement('tr')
  makeClickable(row, `查看 Trace ${trace.traceId}`, () => openTrace(trace))
  td(row, `${labels[trace.sourceType] || trace.sourceType || '入口'} · ${traceOperationLabel(trace)}`.trim())
  if (target === 'slow') {
    td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, formatNumber(trace.cpuTimeMs, ' ms'), 'number')
    td(row, formatBytes(trace.allocatedBytes), 'number')
    td(row, formatNumber(trace.threadId), 'number')
  } else {
    td(row, formatNumber(trace.status), 'number')
    const root = (trace.operations || []).find(op => op.type === trace.sourceType)
    td(row, root ? [root.errorType, root.errorMessage].filter(Boolean).join(': ') : trace.tree?.split('\n').find(line => /ERROR|HTTP_5/.test(line)) || '—')
    td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, formatNumber(trace.threadId), 'number')
  }
  td(row, formatTime(trace.finishedAt))
  return row
}
function renderIncidentTables() {
  const visibleTraces = traces.filter(trace => !isDashboardTrace(trace))
  // Incident rows represent entry roots; downstream spans stay inside the Trace detail.
  const slowItems = slowIncidents.filter(trace => !isDashboardTrace(trace) && trace.slow && inSelectedPeriod(trace))
    .sort((left, right) => new Date(right.finishedAt).getTime() - new Date(left.finishedAt).getTime())
  const errorItems = errorIncidents.filter(trace => !isDashboardTrace(trace) && trace.error && inSelectedPeriod(trace))
    .sort((left, right) => new Date(right.finishedAt).getTime() - new Date(left.finishedAt).getTime())
  const slow = slowItems.slice(0, 50)
  const errors = errorItems.slice(0, 50)
  const slowCountLabel = slowItems.length >= 100 ? '100+' : formatNumber(slowItems.length)
  const errorCountLabel = errorItems.length >= 100 ? '100+' : formatNumber(errorItems.length)
  byId('slow-total').textContent = `(${slowCountLabel}${slowItems.length > 50 ? '，列表显示前 50 条' : ''})`
  byId('error-total').textContent = `(${errorCountLabel}${errorItems.length > 50 ? '，列表显示前 50 条' : ''})`
  byId('slow-rows').replaceChildren(...slow.map(trace => traceRow(trace, 'slow')))
  byId('error-rows').replaceChildren(...errors.map(trace => traceRow(trace, 'error')))
  byId('slow-empty').hidden = slowItems.length > 0
  byId('error-empty').hidden = errorItems.length > 0
  const pageCount = Math.max(1, Math.ceil(visibleTraces.length / recentPageSize))
  recentPage = Math.min(recentPage, pageCount - 1)
  const start = recentPage * recentPageSize
  const recent = visibleTraces.slice(start, start + recentPageSize)
  const rows = byId('trace-rows'); rows.replaceChildren()
  for (const trace of recent) {
    const row = document.createElement('tr')
    makeClickable(row, `查看 Trace ${trace.traceId}`, () => openTrace(trace))
    const result = td(row, trace.error ? '错误' : trace.slow ? '慢' : '正常', `status ${trace.error ? 'error' : trace.slow ? 'slow' : 'ok'}`)
    td(row, `${labels[trace.sourceType] || trace.sourceType || '入口'} · ${traceOperationLabel(trace)}`.trim())
    td(row, formatNumber(trace.status), 'number'); td(row, formatNumber(trace.durationMs, ' ms'), 'number')
    td(row, trace.traceId, 'mono'); td(row, formatTime(trace.finishedAt)); rows.append(row)
  }
  byId('trace-empty').hidden = recent.length > 0
  byId('trace-page').textContent = visibleTraces.length ? `${start + 1}–${start + recent.length} 条，共 ${visibleTraces.length} 条（已排除诊断页面自身请求，最多读取最近 100 条）` : ''
  byId('trace-prev').disabled = recentPage <= 0
  byId('trace-next').disabled = recentPage >= pageCount - 1
}

function metricLabel(series) {
  const memoryPool = series.tags?.id ? ` · ${memoryPoolLabel(series.tags.id)}` : ''
  const memoryArea = series.tags?.area === 'heap' ? '堆' : '非堆'
  const known = {
    'process.cpu.usage': '进程 CPU 使用率',
    'system.cpu.usage': '系统 CPU 使用率',
    'jvm.memory.used': `JVM ${memoryArea}内存已用${memoryPool}`,
    'jvm.memory.committed': `JVM ${memoryArea}内存已提交${memoryPool}`,
    'jvm.memory.max': `JVM ${memoryArea}内存上限${memoryPool}`,
    'jvm.gc.overhead': 'JVM GC 开销',
    'jvm.gc.live.data.size': 'GC 后存活内存',
    'jvm.gc.max.data.size': 'GC 后存活内存上限',
    'jvm.gc.memory.allocated': 'GC 新分配内存',
    'jvm.gc.memory.promoted': 'GC 晋升内存',
    'jvm.threads.live': 'JVM 活跃线程数',
    'jvm.threads.peak': 'JVM 峰值线程数',
    'jvm.classes.loaded': 'JVM 当前已加载类数',
    'jvm.classes.unloaded': 'JVM 类卸载速率',
    'jvm.buffer.memory.used': `JVM Buffer 内存已用${memoryPool}`,
    'jvm.buffer.total.capacity': `JVM Buffer 总容量${memoryPool}`,
    'jvm.buffer.count': `JVM Buffer 数量${memoryPool}`,
    'logback.events': '日志事件数',
    'tomcat.sessions.active.current': 'Tomcat 活跃会话数',
    'tomcat.sessions.active.max': 'Tomcat 活跃会话峰值',
    'tomcat.sessions.created': 'Tomcat 创建会话数',
    'tomcat.sessions.expired': 'Tomcat 过期会话数',
    'tomcat.sessions.rejected': 'Tomcat 拒绝会话数',
    'hikaricp.connections.active': `数据库活跃连接${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'hikaricp.connections.idle': `数据库空闲连接${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'hikaricp.connections.pending': `数据库等待连接${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'hikaricp.connections.max': `数据库连接上限${series.tags?.pool ? ` · ${series.tags.pool}` : ''}`,
    'tomcat.threads.busy': 'Tomcat 忙碌线程数',
    'tomcat.threads.current': 'Tomcat 当前线程数',
    'http.server.requests': 'HTTP 请求',
    'process.uptime': '进程运行时长',
    'system.load.average.1m': '系统 1 分钟负载'
  }
  return known[series.name] || series.name
}
function memoryPoolLabel(id) {
  const known = {
    'G1 Eden Space': 'Eden 新生代分配区',
    'G1 Survivor Space': 'Survivor 新生代存活区',
    'G1 Old Gen': 'Old 老年代',
    'PS Eden Space': 'Eden 新生代分配区',
    'PS Survivor Space': 'Survivor 新生代存活区',
    'PS Old Gen': 'Old 老年代',
    'Par Eden Space': 'Eden 新生代分配区',
    'Par Survivor Space': 'Survivor 新生代存活区',
    'Par Old Gen': 'Old 老年代',
    'Tenured Gen': '老年代',
    'CMS Old Gen': 'Old 老年代',
    'Metaspace': '元空间',
    'Compressed Class Space': '压缩类空间',
    'Code Cache': 'JIT 代码缓存'
  }
  if (id.startsWith("CodeHeap '")) return `JIT 代码缓存 · ${id.slice(10, -1)}`
  return known[id] || id
}
function memoryPoolShortLabel(id) {
  const short = {
    'G1 Eden Space': 'Eden', 'PS Eden Space': 'Eden', 'Par Eden Space': 'Eden',
    'G1 Survivor Space': 'Survivor', 'PS Survivor Space': 'Survivor', 'Par Survivor Space': 'Survivor',
    'G1 Old Gen': 'Old', 'PS Old Gen': 'Old', 'Par Old Gen': 'Old', 'CMS Old Gen': 'Old', 'Tenured Gen': 'Old',
    'Metaspace': '元空间', 'Compressed Class Space': '类空间', 'Code Cache': '代码缓存', 'CodeCache': '代码缓存'
  }
  if (id.startsWith("CodeHeap '")) return codeHeapLabel(id.slice(10, -1))
  return short[id] || id
}
function codeHeapLabel(kind) {
  return ({
    'non-nmethods': '非方法代码',
    'profiled nmethods': '已分析代码',
    'non-profiled nmethods': '未分析代码'
  })[kind] || kind
}
function memoryPoolGroup(series) {
  const id = String(series.tags?.id || '默认')
  const area = series.tags?.area || 'unknown'
  if (area === 'heap') {
    if (/Eden|Survivor/i.test(id)) return { key: 'young', title: '新生代' }
    if (/Old|Tenured|Gen/i.test(id)) return { key: 'old', title: '老年代' }
    return { key: `heap-${id}`, title: memoryPoolLabel(id) }
  }
  if (/^CodeHeap '/.test(id)) {
    const kind = id.slice(10, -1)
    return { key: `code-${kind}`, title: `代码缓存 · ${codeHeapLabel(kind)}` }
  }
  if (/Code ?Cache/i.test(id)) return { key: 'code-cache', title: '代码缓存' }
  if (/Metaspace|Class Space/i.test(id)) return { key: 'class-metadata', title: '类元数据' }
  return { key: `nonheap-${id}`, title: memoryPoolLabel(id) }
}
function memoryValueLabel(name) {
  return ({ 'jvm.memory.used': '已用', 'jvm.memory.committed': '已提交', 'jvm.memory.max': '上限' })[name]
}
function seriesTitle(series) {
  if (series.name.startsWith('jvm.memory.')) {
    const id = series.tags?.id || '默认'
    const singlePoolGroup = /^CodeHeap '|^Code ?Cache$|Old|Tenured/i.test(id)
    if (singlePoolGroup) return memoryValueLabel(series.name) || metricLabel(series)
    const pool = ({
      'Survivor': 'Surv', '元空间': '元', '类空间': '类'
    })[memoryPoolShortLabel(id)] || memoryPoolShortLabel(id)
    return `${memoryValueLabel(series.name) || metricLabel(series)} · ${pool}`
  }
  if (series.name === 'jvm.gc.live.data.size' || series.name === 'jvm.gc.max.data.size') {
    return series.name === 'jvm.gc.live.data.size' ? 'GC 后存活' : 'GC 后存活上限'
  }
  if (series.name === 'jvm.gc.memory.allocated' || series.name === 'jvm.gc.memory.promoted') {
    return series.name === 'jvm.gc.memory.allocated' ? '新分配' : '晋升'
  }
  if (/^jvm\.buffer\.(memory\.used|total\.capacity)$/.test(series.name)) {
    return series.name === 'jvm.buffer.memory.used' ? '已用' : '容量'
  }
  if (/^tomcat\.sessions\.active\.(current|max)$/.test(series.name)) {
    return series.name.endsWith('.current') ? '当前活跃' : '历史峰值'
  }
  if (/^tomcat\.sessions\.(created|expired|rejected)$/.test(series.name)) {
    return ({ created: '新建', expired: '过期', rejected: '拒绝' })[series.name.split('.').at(-1)]
  }
  if (series.name === 'easy.operations.duration') {
    const source = ({ HTTP: 'HTTP', JOB: 'Job', EXECUTOR_TASK: '线程池任务', KAFKA_CONSUMER: 'Kafka 消费', REDIS: 'Redis',
      DATABASE: '数据库', TRANSACTION: '事务', REMOTE: '远程调用', ELASTICSEARCH: 'Elasticsearch' })[series.tags?.source] || series.tags?.source || '应用'
    return `${source} · ${series.tags?.operation || '操作'}${series.tags?.outcome ? ` · ${series.tags.outcome}` : ''}`
  }
  if (series.name === 'logback.events' && series.tags?.level) {
    return ({ error: '错误日志', warn: '警告日志', info: '信息日志', debug: '调试日志', trace: '跟踪日志' })[series.tags.level] || `${series.tags.level} 日志`
  }
  const tags = Object.entries(series.tags || {})
    .filter(([key]) => !['application', 'environment', 'area', 'id', 'pool'].includes(key))
    .map(([key, value]) => series.name === 'logback.events' && key === 'level'
      ? `${({ error: '错误', warn: '警告', info: '信息', debug: '调试', trace: '跟踪' })[value] || value}日志`
      : `${key}=${value}`).join(', ')
  return `${metricLabel(series)}${tags ? ` · ${tags}` : ''}`
}
function seriesKey(series) {
  const tags = Object.entries(series.tags || {}).sort(([left], [right]) => left.localeCompare(right))
  return `${series.name}|${tags.map(([key, value]) => `${key}=${value}`).join('&')}`
}
function metricMeasurement(series) {
  const available = series.measurements || []
  if (series.name === 'easy.operations.duration' && available.includes('COUNT') && available.includes('TOTAL_TIME')) return 'INTERVAL_AVG'
  if (intervalCounterNames.has(series.name) && available.includes('COUNT')) return 'RATE'
  return ['VALUE', 'COUNT', 'TOTAL_TIME', 'MAX'].find(name => available.includes(name)) || available[0] || 'VALUE'
}
function metricHistoryValues(series, points, measurement, timeRange) {
  const chronological = points.slice().reverse()
  if (measurement === 'INTERVAL_AVG' || measurement === 'RATE') {
    const values = []
    let previous = null
    for (const point of chronological) {
      const capturedAt = new Date(point.capturedAt).getTime()
      const count = Number(point.measurements?.COUNT)
      const totalTime = Number(point.measurements?.TOTAL_TIME)
      const rawCount = Number(point.measurements?.COUNT)
      if (!Number.isFinite(capturedAt) || !Number.isFinite(rawCount)) { previous = null; continue }
      if (previous && capturedAt >= timeRange.start && capturedAt <= timeRange.end) {
        const elapsedMinutes = Math.max(1 / 60, (capturedAt - previous.capturedAt) / 60000)
        const deltaCount = count >= previous.count ? count - previous.count : count
        if (measurement === 'INTERVAL_AVG') {
          const deltaTime = totalTime >= previous.totalTime ? totalTime - previous.totalTime : totalTime
          if (deltaCount > 0 && Number.isFinite(deltaTime)) values.push([capturedAt, deltaTime * 1000 / deltaCount])
        } else if (deltaCount >= 0) {
          values.push([capturedAt, deltaCount / elapsedMinutes])
        }
      }
      previous = { capturedAt, count: rawCount, totalTime }
    }
    return values
  }
  return chronological
    .filter(point => {
      const timestamp = new Date(point.capturedAt).getTime()
      return timestamp >= timeRange.start && timestamp <= timeRange.end
    })
    .map(point => [new Date(point.capturedAt).getTime(), Number(point.measurements?.[measurement])])
    .filter(([timestamp, value]) => Number.isFinite(timestamp) && Number.isFinite(value)
      && !(series.name === 'jvm.memory.max' && value <= 0))
}
function trendGroup(series, category, measurement) {
  const name = String(series.name || '')
  if (name.startsWith('jvm.memory.')) {
    const area = series.tags?.area || 'unknown'
    const poolGroup = memoryPoolGroup(series)
    return { key: `${category}|jvm-memory|${area}|${poolGroup.key}`, title: `JVM ${poolGroup.title}内存` }
  }
  if (['jvm.gc.live.data.size', 'jvm.gc.max.data.size'].includes(name)) {
    return { key: `${category}|jvm-gc-live-memory`, title: 'GC 后存活内存' }
  }
  if (['jvm.gc.memory.allocated', 'jvm.gc.memory.promoted'].includes(name)) {
    return { key: `${category}|jvm-gc-memory-rate`, title: 'GC 内存变化' }
  }
  if (/^jvm\.buffer\.(memory\.used|total\.capacity)$/.test(name)) {
    const pool = series.tags?.id || '默认'
    return { key: `${category}|jvm-buffer-memory|${pool}`, title: `JVM Buffer 内存 · ${memoryPoolLabel(pool)}` }
  }
  if (/^tomcat\.sessions\.active\.(current|max)$/.test(name)) {
    return { key: `${category}|tomcat-active-sessions`, title: 'Tomcat 活跃会话' }
  }
  if (/^tomcat\.sessions\.(created|expired|rejected)$/.test(name) && measurement === 'RATE') {
    return { key: `${category}|tomcat-session-rate`, title: 'Tomcat 会话变化' }
  }
  if (name === 'easy.operations.duration') {
    const source = series.tags?.source || 'unknown'
    const sourceLabel = ({ HTTP: 'HTTP', JOB: 'Job', EXECUTOR_TASK: '线程池任务', KAFKA_CONSUMER: 'Kafka 消费', REDIS: 'Redis', DATABASE: '数据库',
      TRANSACTION: '事务', REMOTE: '远程调用', ELASTICSEARCH: 'Elasticsearch' })[source] || source
    return { key: `${category}|easy-operation-duration|${source}|${measurement}`, title: `${sourceLabel} 操作耗时` }
  }
  if (['process.cpu.usage', 'system.cpu.usage'].includes(name)) {
    return { key: `${category}|cpu-usage|${name}|${measurement}`, title: name === 'process.cpu.usage' ? '进程 CPU 使用率' : '系统 CPU 使用率' }
  }
  if (/^jvm\.threads\.(live|peak|daemon)$/.test(name)) {
    return { key: `${category}|thread-count|${measurement}`, title: 'JVM 线程数' }
  }
  if (name === 'jvm.classes.unloaded' && measurement === 'RATE') {
    return { key: `${category}|class-unload-rate`, title: 'JVM 类卸载速率' }
  }
  if (name === 'jvm.classes.loaded') {
    return { key: `${category}|loaded-class-count`, title: 'JVM 当前已加载类数' }
  }
  if (/^tomcat\.threads\.(busy|current)$/.test(name)) {
    return { key: `${category}|tomcat-thread-count|${measurement}`, title: 'Tomcat 线程' }
  }
  if (/^hikaricp\.connections\.(active|idle|pending|max|min)$/.test(name)) {
    return { key: `${category}|database-connections|${measurement}`, title: '数据库连接池' }
  }
  if (/^executor\.(active|queued|pool\.(size|core|max)|queue\.remaining)$/.test(name)) {
    return { key: `${category}|executor-count|${measurement}`, title: '线程池任务与容量' }
  }
  return { key: `${category}|${name}|${measurement}`, title: metricLabel(series) }
}
const metricCategoryLabels = {
  HTTP: 'HTTP', CPU: 'CPU', JVM: 'JVM', TOMCAT: 'Tomcat', LOGGING: '日志', PROCESS: '进程', SYSTEM: '系统',
  DATABASE: '数据库', MIDDLEWARE: '中间件', THREADS: '线程池', APPLICATION: '应用指标'
}
function metricCategory(series) {
  const name = String(series.name || '')
  if (name === 'process.cpu.usage' || name === 'system.cpu.usage') return 'CPU'
  if (/^(http\.client\.|kafka\.|spring\.kafka\.|redis\.|lettuce\.|cache\.|elasticsearch\.)/.test(name)) return 'MIDDLEWARE'
  if (/^(http\.|tomcat\.global\.request)/.test(name)) return 'HTTP'
  if (/^jvm\./.test(name)) return 'JVM'
  if (/^tomcat\.(threads\.|sessions\.)/.test(name)) return 'TOMCAT'
  if (/^tomcat\./.test(name)) return 'TOMCAT'
  if (/^(logback|log4j2|logging)\./.test(name)) return 'LOGGING'
  if (/^process\./.test(name)) return 'PROCESS'
  if (/^system\./.test(name)) return 'SYSTEM'
  if (/^(hikaricp\.|jdbc\.|db\.)/.test(name)) return 'DATABASE'
  if (/^executor\./.test(name)) return 'THREADS'
  if (/^easy\./.test(name)) return 'APPLICATION'
  return null
}
function metricCategoryOrder(category) {
  return Object.keys(metricCategoryLabels).indexOf(category)
}
const preferredMetricNames = [
  'process.cpu.usage', 'system.cpu.usage', 'system.load.average.1m',
  'jvm.memory.used', 'jvm.memory.committed', 'jvm.memory.max', 'jvm.gc.overhead',
  'jvm.gc.live.data.size', 'jvm.gc.max.data.size', 'jvm.gc.memory.allocated', 'jvm.gc.memory.promoted',
  'jvm.threads.live', 'jvm.threads.peak', 'jvm.threads.daemon', 'jvm.classes.loaded',
  'jvm.classes.unloaded', 'jvm.buffer.memory.used', 'jvm.buffer.total.capacity',
  'tomcat.threads.busy', 'tomcat.threads.current', 'tomcat.sessions.active.current',
  'tomcat.sessions.active.max', 'tomcat.sessions.created', 'tomcat.sessions.expired',
  'tomcat.sessions.rejected', 'logback.events', 'hikaricp.connections.active',
  'hikaricp.connections.idle', 'hikaricp.connections.pending', 'hikaricp.connections.max',
  'hikaricp.connections.min', 'executor.active', 'executor.queued', 'executor.pool.size',
  'executor.pool.core', 'executor.pool.max', 'executor.completed', 'executor.queue.remaining'
]
const preferredMetricRank = new Map(preferredMetricNames.map((name, index) => [name, index]))
function isUsefulMetric(series) {
  const name = String(series.name || '')
  // HTTP 的入口请求时延已由 RED 面板按 URI 展示，避免同一数据在应用指标里重复出现。
  if (name === 'easy.operations.duration' && series.tags?.source === 'HTTP') return false
  if (preferredMetricRank.has(name)) return true
  // 展示常见客户端依赖指标，但仍按指标名分图，避免把不同单位混在一起。
  return /^(http\.client\.requests|kafka\.(consumer|producer)\.|spring\.kafka\.|redis\.|lettuce\.|cache\.|elasticsearch\.)/.test(name)
    || /^easy\./.test(name)
}
function chooseUsefulSeries() {
  const candidates = seriesCatalog.filter(series => metricCategory(series) && isUsefulMetric(series))
  const liveKeys = new Set(liveMetrics.map(seriesKey))
  const sorted = candidates.sort((left, right) => {
    const leftRank = preferredMetricRank.get(left.name) ?? preferredMetricNames.length
    const rightRank = preferredMetricRank.get(right.name) ?? preferredMetricNames.length
    if (leftRank !== rightRank) return leftRank - rightRank
    const leftLive = liveKeys.has(seriesKey(left)) ? 0 : 1
    const rightLive = liveKeys.has(seriesKey(right)) ? 0 : 1
    return leftLive - rightLive || seriesTitle(left).localeCompare(seriesTitle(right), 'zh-CN')
  })
  const perMetricCount = new Map()
  const eligible = sorted.filter(series => {
    const key = `${series.name}|${series.tags?.area || ''}`
    const count = perMetricCount.get(key) || 0
    if (count >= 8) return false
    perMetricCount.set(key, count + 1)
    return true
  })
  const scope = byId('trend-series-scope')
  if (scope) {
    const visible = eligible.slice(0, usefulSeriesLimit)
    const summary = document.createElement('span')
    if (!candidates.length) summary.textContent = '未发现常用序列'
    else if (visible.length === candidates.length) summary.textContent = `显示 ${formatNumber(visible.length)} 条常用序列`
    else summary.textContent = `显示 ${formatNumber(visible.length)} / ${formatNumber(candidates.length)} 条常用序列${eligible.length < candidates.length ? ' · 单指标最多 8 条' : ''}`
    scope.replaceChildren(summary)
    if (visible.length < eligible.length) {
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'series-load-more'
      button.textContent = `再加载 ${Math.min(16, eligible.length - visible.length)} 条`
      button.addEventListener('click', () => {
        usefulSeriesLimit = Math.min(eligible.length, usefulSeriesLimit + 16)
        button.disabled = true
        renderTrends().catch(error => showNotice(error?.message || '加载更多指标失败'))
      })
      scope.append(button)
    }
  }
  return eligible.slice(0, usefulSeriesLimit)
}
const metricChartInstances = new Set()
const chartPalette = [
  '#4774df', '#d35b62', '#d58a30', '#239879', '#8868c7', '#1598aa', '#8c9d32', '#c35d9f',
  '#52616f', '#ba6a2b', '#4b7c58', '#8b5b9e', '#2b7e9c', '#b04f76', '#71833a', '#5d68a5',
  '#a47c22', '#367b70', '#9a5f4e', '#5c7899'
]
function disposeMetricCharts(container) {
  if (container === byId('trend-grid')) {
    trendHistoryObserver?.disconnect()
    trendHistoryObserver = null
  }
  container.querySelectorAll('.metric-chart').forEach(element => {
    const chart = window.echarts?.getInstanceByDom(element)
    if (!chart) return
    chart.dispose()
    metricChartInstances.delete(chart)
  })
}
function compactAxisValue(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(number)
}
function metricValueLabel(name, value, measurement = 'VALUE') {
  return formatMetricValue(name, value, measurement)
}
function createTimeSeriesChart(element, { series, yAxisName, valueFormatter, axisFormatter = compactAxisValue, showLegend = false, compact = false, description, timeRange }) {
  if (!window.echarts) throw new Error('图表组件加载失败，请刷新页面重试。')
  element.setAttribute('role', 'img')
  element.setAttribute('aria-label', description)
  const chart = window.echarts.init(element, null, { renderer: 'canvas', useDirtyRect: true })
  metricChartInstances.add(chart)
  chart.setOption({
    animation: false,
    color: chartPalette,
    aria: { enabled: true, description },
    grid: { left: compact ? 68 : 75, right: 17, top: showLegend ? 38 : 17, bottom: 50, containLabel: false },
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'cross', snap: true, lineStyle: { color: '#8493a9', type: 'dashed' }, crossStyle: { color: '#8493a9' } },
      backgroundColor: 'rgba(22, 35, 54, .94)',
      borderWidth: 0,
      padding: [9, 11],
      textStyle: { color: '#f6f8fb', fontSize: 11 },
      valueFormatter: value => valueFormatter(Number(value))
    },
    legend: showLegend ? {
      type: 'scroll', top: 3, left: 8, right: 8, height: 28,
      formatter: name => name.length > 22 ? `${name.slice(0, 11)}…${name.slice(-7)}` : name,
      pageIconColor: '#5574bd', pageTextStyle: { color: '#75839a', fontSize: 9 },
      itemWidth: 9, itemHeight: 9, itemGap: 10,
      textStyle: { color: '#65758c', fontSize: 9 },
      tooltip: { show: true }
    } : { show: false },
    xAxis: {
      type: 'time',
      ...(timeRange ? { min: timeRange.start, max: timeRange.end } : {}),
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#dce3ec' } },
      axisTick: { show: false },
      axisLabel: { color: '#748398', fontSize: 10, hideOverlap: true, margin: 10, formatter: value => formatChartTime(value) },
      splitLine: { show: false },
      axisPointer: { label: { formatter: params => formatChartTime(params.value) } }
    },
    yAxis: {
      type: 'value',
      name: yAxisName,
      nameLocation: 'end',
      nameGap: 10,
      nameTextStyle: { color: '#8491a3', fontSize: 9, align: 'right', padding: [0, 0, 0, 0] },
      min: 0,
      splitNumber: 4,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#748398', fontSize: 9, margin: 9, formatter: value => axisFormatter(Number(value)) },
      splitLine: { lineStyle: { color: '#edf1f6', type: 'solid' } },
      axisPointer: { label: { formatter: params => axisFormatter(Number(params.value)) } }
    },
    dataZoom: [
      { type: 'inside', xAxisIndex: 0, filterMode: 'none', zoomOnMouseWheel: 'shift', moveOnMouseMove: true },
      { type: 'slider', xAxisIndex: 0, filterMode: 'none', height: 12, bottom: 5, showDetail: false, borderColor: 'transparent', backgroundColor: '#f1f4f8', fillerColor: 'rgba(71, 116, 223, .17)', handleSize: 8, handleStyle: { color: '#fff', borderColor: '#91a9df' }, moveHandleSize: 0, dataBackground: { lineStyle: { color: '#b7c4d8', width: 1 }, areaStyle: { color: '#e7ecf4' } } }
    ],
    series: series.map(item => ({
      name: item.name,
      type: 'line',
      showSymbol: item.data.length <= 1,
      symbol: 'circle',
      symbolSize: 5,
      connectNulls: false,
      sampling: 'lttb',
      smooth: false,
      emphasis: { focus: 'series', lineStyle: { width: 2.7 } },
      lineStyle: { width: compact ? 1.8 : 2.1, color: item.color },
      itemStyle: { color: item.color },
      data: item.data
    }))
  })
  return chart
}
function isHttpError(tags) {
  const status = Number(tags?.status)
  return (Number.isFinite(status) && status >= 400)
    || ['CLIENT_ERROR', 'SERVER_ERROR', 'ERROR', 'FAILURE'].includes(String(tags?.outcome || '').toUpperCase())
    || Boolean(tags?.exception && String(tags.exception).toLowerCase() !== 'none')
}

const redMetricOptions = {
  requests: { label: '请求速率', summaryLabel: '请求总数', field: 'requests', unit: ' 次/分钟', totalUnit: ' 次', summaryUnit: ' 次' },
  errors: { label: '错误速率', summaryLabel: '错误总数', field: 'errors', unit: ' 次/分钟', totalUnit: ' 次', summaryUnit: ' 次' },
  duration: { label: '平均耗时', summaryLabel: '平均耗时', field: 'duration', unit: ' ms', totalUnit: ' ms', summaryUnit: ' ms' }
}
function redMetricCurrent(groups, metric) {
  if (metric.field === 'duration') {
    const points = groups.flatMap(group => group.points)
    const count = points.reduce((sum, point) => sum + point.durationCount, 0)
    return count ? points.reduce((sum, point) => sum + point.durationTotalMs, 0) / count : null
  }
  const countField = metric.field === 'requests' ? 'requestCount' : 'errorCount'
  return groups.reduce((total, group) => total + group.points.reduce((sum, point) => sum + point[countField], 0), 0)
}
function redMetricStats(group, metric) {
  const samples = group.points.filter(point => metric.field !== 'duration' || point.durationCount > 0)
  const values = samples.map(point => Number(point[metric.field])).filter(Number.isFinite)
  const total = metric.field === 'requests' ? group.points.reduce((sum, point) => sum + point.requestCount, 0)
    : metric.field === 'errors' ? group.points.reduce((sum, point) => sum + point.errorCount, 0)
      : group.points.reduce((sum, point) => sum + point.durationTotalMs, 0)
  const mean = metric.field === 'duration' && samples.length
    ? samples.reduce((sum, point) => sum + point.durationTotalMs, 0) / samples.reduce((sum, point) => sum + point.durationCount, 0)
    : values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null
  return { max: values.length ? Math.max(...values) : null, mean, total }
}
function renderHttpRedDashboard(grid, groups, timeRange) {
  const metric = redMetricOptions[selectedRedMetric]
  const dashboard = document.createElement('div'); dashboard.className = 'red-dashboard'
  const summary = document.createElement('div'); summary.className = 'red-summary-grid'; summary.setAttribute('role', 'group'); summary.setAttribute('aria-label', '选择 HTTP 趋势指标')
  Object.entries(redMetricOptions).forEach(([key, option]) => {
    const button = document.createElement('button'); button.type = 'button'
    button.className = `red-summary-card${key === selectedRedMetric ? ' selected' : ''}`
    button.dataset.redMetric = key; button.setAttribute('aria-pressed', String(key === selectedRedMetric))
    const label = document.createElement('span'); label.textContent = option.summaryLabel
    const value = document.createElement('strong'); const current = redMetricCurrent(groups, option)
    value.textContent = Number.isFinite(current) ? `${formatNumber(current)}${option.summaryUnit}` : '暂无数据'
    const hint = document.createElement('small'); hint.textContent = key === 'duration' ? '所选范围加权平均' : '所选范围累计'
    button.append(label, value, hint); summary.append(button)
  })
  dashboard.append(summary)

  const chartPanel = document.createElement('section'); chartPanel.className = 'red-chart-panel'; chartPanel.setAttribute('aria-label', `${metric.label}趋势`)
  const chartHeading = document.createElement('div'); chartHeading.className = 'red-chart-heading'
  const chartTitle = document.createElement('h3'); chartTitle.textContent = `${metric.label} · 按 URI`
  const chartHint = document.createElement('span'); chartHint.textContent = '点下方 URI 可显隐曲线'
  chartHeading.append(chartTitle, chartHint)
  const chartElement = document.createElement('div'); chartElement.className = 'metric-chart'; chartElement.setAttribute('aria-label', `${metric.label}按 URI 分组的趋势图`)
  chartPanel.append(chartHeading, chartElement); dashboard.append(chartPanel)

  const tableWrap = document.createElement('div'); tableWrap.className = 'red-detail-scroll'
  const table = document.createElement('table'); table.className = 'red-detail-table'; table.setAttribute('aria-label', `${metric.label} URI 统计和曲线筛选`)
  const head = document.createElement('thead'); const headerRow = document.createElement('tr')
  for (const name of ['URI / 曲线', '最大', '平均', '累计']) { const cell = document.createElement('th'); cell.textContent = name; headerRow.append(cell) }
  head.append(headerRow); table.append(head)
  const body = document.createElement('tbody')
  groups.map(group => ({ group, ...redMetricStats(group, metric) }))
    .sort((left, right) => right.total - left.total)
    .forEach(({ group, max, mean, total }) => {
      const row = document.createElement('tr'); const uriCell = document.createElement('td'); uriCell.className = 'red-uri-cell'; uriCell.title = group.uri
      const visible = visibleRedUris.has(group.uri); const color = chartPalette[groups.indexOf(group) % chartPalette.length]
      const toggle = document.createElement('button'); toggle.type = 'button'; toggle.className = `red-uri-toggle${visible ? '' : ' is-hidden'}`
      toggle.dataset.uri = group.uri; toggle.setAttribute('aria-pressed', String(visible)); toggle.setAttribute('aria-label', `${visible ? '隐藏' : '显示'}曲线 ${group.uri}`)
      toggle.style.setProperty('--uri-color', color)
      const marker = document.createElement('i'); marker.setAttribute('aria-hidden', 'true')
      const name = document.createElement('span'); name.textContent = group.uri
      toggle.append(marker, name); uriCell.append(toggle); row.append(uriCell)
      for (const [value, suffix] of [[max, metric.unit], [mean, metric.unit], [total, metric.totalUnit]]) {
        const cell = document.createElement('td'); cell.className = 'number'; cell.textContent = Number.isFinite(value) ? `${formatNumber(value)}${suffix}` : '—'; row.append(cell)
      }
      body.append(row)
    })
  table.append(body); tableWrap.append(table); dashboard.append(tableWrap)
  grid.append(dashboard)

  const chartSeries = groups.filter(group => visibleRedUris.has(group.uri)).map(group => ({
    name: group.uri,
    color: chartPalette[groups.indexOf(group) % chartPalette.length],
    data: group.points
      .filter(point => metric.field !== 'duration' || point.durationCount > 0)
      .map(point => [new Date(point.capturedAt).getTime(), Number(point[metric.field])])
      .filter(point => Number.isFinite(point[0]) && Number.isFinite(point[1]))
  }))
  createTimeSeriesChart(chartElement, {
    series: chartSeries, yAxisName: metric.unit.trim(),
    valueFormatter: value => `${formatNumber(value)}${metric.unit}`,
    axisFormatter: compactAxisValue, description: `${metric.label}按 URI 分组的时间趋势`, timeRange
  })
}

async function loadHttpRedHistory(timeRange) {
  if (redHistoryCache?.start === timeRange.start && redHistoryCache?.end === timeRange.end) return redHistoryCache.result
  if (redLoading?.start === timeRange.start && redLoading?.end === timeRange.end) return redLoading.promise
  const pending = { ...timeRange, promise: loadHttpRedHistoryUncached(timeRange) }
  redLoading = pending
  try { return await pending.promise } finally { if (redLoading === pending) redLoading = null }
}

async function loadHttpRedHistoryUncached(timeRange) {
  const activeKeys = new Set(liveMetrics.filter(item => item.name === 'http.server.requests').map(seriesKey))
  const candidates = seriesCatalog.filter(item => item.name === 'http.server.requests'
    && !isDashboardHttpTraffic('HTTP', item.tags?.uri || item.tags?.url || ''))
  candidates.sort((left, right) => Number(activeKeys.has(seriesKey(right))) - Number(activeKeys.has(seriesKey(left))))
  const groupsByUri = new Map()
  candidates.forEach(series => {
    const uri = series.tags?.uri || series.tags?.url || '（未标记 URI）'
    if (!groupsByUri.has(uri)) groupsByUri.set(uri, { uri, series: [], active: 0 })
    const group = groupsByUri.get(uri)
    group.series.push(series)
    if (activeKeys.has(seriesKey(series))) group.active++
  })
  const allGroups = [...groupsByUri.values()].sort((left, right) => right.active - left.active || left.uri.localeCompare(right.uri))
  const selectedGroups = []
  let selectedSeriesCount = 0
  for (const group of allGroups) {
    if (selectedGroups.length >= httpUriLimit || selectedSeriesCount + group.series.length > httpSeriesLimit) continue
    selectedGroups.push(group)
    selectedSeriesCount += group.series.length
  }
  const processStartByTimestamp = new Map()
  const processStartSeries = seriesCatalog.find(item => item.name === 'process.start.time')
  if (processStartSeries) {
    try {
      const history = await loadSeriesHistory(processStartSeries)
      history.forEach(point => {
        const value = Number(point.measurements?.VALUE)
        if (Number.isFinite(value)) processStartByTimestamp.set(point.capturedAt, value)
      })
    } catch { /* 没有进程启动时间序列时，仍按计数器自身的递减识别重启。 */ }
  }
  let covered = 0
  // 先按 URI 保留完整分组，再逐条串行读取；同一 URI 的状态码/结果序列会合并到一条曲线。
  for (const group of selectedGroups) {
    const bins = new Map()
    for (const series of group.series) {
      try {
        const history = await loadSeriesHistory(series)
        if (history.length) covered++
        const chronological = history.slice().reverse()
        let previous = null
        for (const point of chronological) {
          const count = Number(point.measurements?.COUNT)
          const total = Number(point.measurements?.TOTAL_TIME)
          if (!Number.isFinite(count) || !Number.isFinite(total)) continue
          if (previous) {
            const elapsedMinutes = Math.max(1, (new Date(point.capturedAt).getTime() - new Date(previous.at).getTime()) / 60000)
            const processStartTime = processStartByTimestamp.get(point.capturedAt)
            const processRestarted = Number.isFinite(processStartTime) && Number.isFinite(previous.processStartTime)
              && processStartTime !== previous.processStartTime
            const deltaCount = processRestarted ? count : count >= previous.count ? count - previous.count : count
            const deltaTime = processRestarted ? total : total >= previous.total ? total - previous.total : total
            const key = point.capturedAt
            const bucket = bins.get(key) || { capturedAt: key, requests: 0, errors: 0, requestCount: 0, errorCount: 0, durationTotalMs: 0, durationCount: 0 }
            const requestRate = deltaCount / elapsedMinutes
            bucket.requests += requestRate
            bucket.requestCount += deltaCount
            if (isHttpError(series.tags)) { bucket.errors += requestRate; bucket.errorCount += deltaCount }
            if (deltaCount > 0) {
              bucket.durationTotalMs += deltaTime * 1000
              bucket.durationCount += deltaCount
            }
            bins.set(key, bucket)
          }
          previous = { at: point.capturedAt, count, total, processStartTime: processStartByTimestamp.get(point.capturedAt) }
        }
      } catch { /* 单条历史序列不可读时，保留同组其他状态的趋势。 */ }
    }
    group.points = [...bins.values()].sort((left, right) => new Date(left.capturedAt) - new Date(right.capturedAt))
      .filter(point => {
        const timestamp = new Date(point.capturedAt).getTime()
        return timestamp >= timeRange.start && timestamp <= timeRange.end
      })
      .map(point => ({ ...point, duration: point.durationCount ? point.durationTotalMs / point.durationCount : 0,
        errorRate: point.requests ? point.errors / point.requests * 100 : 0 }))
  }
  const groups = selectedGroups.filter(group => group.points.length)
  const result = { groups, totalUriCount: allGroups.length, selectedUriCount: selectedGroups.length,
    totalSeriesCount: candidates.length, selectedSeriesCount, covered }
  redHistoryCache = { start: timeRange.start, end: timeRange.end, result }
  return result
}

async function renderHttpRed(timeRange = currentMetricTimeRange()) {
  const grid = byId('http-red-grid'); disposeMetricCharts(grid); grid.replaceChildren()
  const scope = byId('http-red-scope')
  const series = seriesCatalog.filter(item => item.name === 'http.server.requests')
  if (!series.length) {
    scope.textContent = '尚无 HTTP 历史序列'
    const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = '当前没有 HTTP Timer 历史。确认应用已启用 Spring MVC / WebFlux 指标，并等待至少两个采样点。'
    grid.append(empty); return
  }
  const loading = document.createElement('p'); loading.className = 'empty'; loading.textContent = '正在汇总 HTTP 历史…'; grid.append(loading)
  const result = await loadHttpRedHistory(timeRange)
  scope.textContent = `按 URI 分组 · 展示 ${formatNumber(result.groups.length)} / ${formatNumber(result.totalUriCount)} 组 · 读取 ${formatNumber(result.covered)} / ${formatNumber(result.selectedSeriesCount)} 条序列`
  grid.replaceChildren()
  if (!result.groups.length) {
    const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = '所选时间范围内暂无足够的 HTTP 历史采样。趋势至少需要两个采样点。'
    grid.append(empty); return
  }
  const previousUris = visibleRedUris
  visibleRedUris = new Set(result.groups
    .filter(group => previousUris === null || !redUriCatalog.has(group.uri) || previousUris.has(group.uri))
    .map(group => group.uri))
  redUriCatalog = new Set(result.groups.map(group => group.uri))
  renderHttpRedDashboard(grid, result.groups, timeRange)
}

async function renderTrends(timeRange = currentMetricTimeRange()) {
  const grid = byId('trend-grid'); disposeMetricCharts(grid); grid.replaceChildren()
  const renderVersion = ++trendRenderVersion
  const selected = chooseUsefulSeries()
  if (!selected.length) { const p = document.createElement('p'); p.className = 'empty'; p.textContent = '当前没有可展示的常用指标历史。启用 Micrometer 指标并等待至少两个采样点后，趋势会按类型显示在这里。'; grid.append(p); return }
  const groups = new Map()
  selected.forEach(series => {
    const category = metricCategory(series)
    const measurement = metricMeasurement(series)
    const descriptor = trendGroup(series, category, measurement)
    if (!groups.has(descriptor.key)) groups.set(descriptor.key, { category, title: descriptor.title, measurement, series: [] })
    groups.get(descriptor.key).series.push(series)
  })
  const placeholders = []
  const groupedByCategory = new Map()
  for (const group of groups.values()) {
    if (!groupedByCategory.has(group.category)) groupedByCategory.set(group.category, [])
    groupedByCategory.get(group.category).push(group)
  }
  ;[...groupedByCategory.entries()].sort(([left], [right]) => metricCategoryOrder(left) - metricCategoryOrder(right))
    .forEach(([category, entries]) => {
      const section = document.createElement('section'); section.className = 'trend-category-section'
      const heading = document.createElement('div'); heading.className = 'trend-category-heading'
      const title = document.createElement('h3'); title.textContent = metricCategoryLabels[category]
      const count = document.createElement('span'); count.textContent = `${entries.length} 张图表`
      heading.append(title, count)
      if (category === 'JVM') {
        const note = document.createElement('p'); note.className = 'trend-category-note'
        note.textContent = '内存按代和区域拆分；曲线分别表示已用、已提交和上限。新生代包含 Eden 与 Survivor，长期存活对象进入老年代。'
        section.append(heading, note)
      }
      const categoryGrid = document.createElement('div'); categoryGrid.className = 'trend-category-grid'
      if (category !== 'JVM') section.append(heading)
      section.append(categoryGrid); grid.append(section)
      entries.sort((left, right) => {
        const memoryRank = group => {
          const series = group.series.find(item => item.name.startsWith('jvm.memory.'))
          if (!series) return null
          const pool = memoryPoolGroup(series)
          if (series.tags?.area === 'heap') return pool.key === 'old' ? 0 : pool.key === 'young' ? 1 : 2
          if (pool.key === 'class-metadata') return 3
          if (pool.key.startsWith('code-') || pool.key === 'code-cache') return 4
          return 5
        }
        const leftMemoryRank = memoryRank(left)
        const rightMemoryRank = memoryRank(right)
        if (leftMemoryRank !== null || rightMemoryRank !== null) {
          if (leftMemoryRank === null) return 1
          if (rightMemoryRank === null) return -1
          if (leftMemoryRank !== rightMemoryRank) return leftMemoryRank - rightMemoryRank
        }
        const leftRank = preferredMetricRank.get(left.series[0].name) ?? preferredMetricNames.length
        const rightRank = preferredMetricRank.get(right.series[0].name) ?? preferredMetricNames.length
        return leftRank - rightRank || seriesTitle(left.series[0]).localeCompare(seriesTitle(right.series[0]), 'zh-CN')
      }).forEach(group => {
        const card = document.createElement('article'); card.className = 'trend-card is-loading'
        const cardHeading = document.createElement('div'); cardHeading.className = 'trend-card-heading'
        const cardTitle = document.createElement('h3'); cardTitle.textContent = group.title
        const badge = document.createElement('span'); badge.className = 'series-kind'; badge.textContent = group.series.length > 1 ? `${group.series.length} 条曲线` : group.series[0].tags?.area || group.series[0].tags?.type || metricCategoryLabels[category]
        cardHeading.append(cardTitle, badge); card.append(cardHeading)
        const skeleton = document.createElement('div'); skeleton.className = 'chart-skeleton'; skeleton.setAttribute('aria-label', '滚动到附近时加载趋势'); card.append(skeleton)
        categoryGrid.append(card)
        placeholders.push({ group, card })
      })
    })
  const loadCard = async ({ group, card }) => {
    try {
      const chartSeries = []
      for (let index = 0; index < group.series.length; index++) {
        if (renderVersion !== trendRenderVersion || !card.isConnected) return
        const series = group.series[index]
        // 5000 点覆盖最长时间范围；通过共享缓存避免同一序列被多个图表重复读取。
        const points = await loadSeriesHistory(series)
        const measurement = group.measurement
        const data = metricHistoryValues(series, points, measurement, timeRange)
        if (data.length) chartSeries.push({ name: seriesTitle(series), color: chartPalette[index % chartPalette.length], data })
      }
      if (renderVersion !== trendRenderVersion || !card.isConnected) return
      card.querySelector('.chart-skeleton')?.remove()
      card.classList.remove('is-loading')
      if (!chartSeries.length) {
        const empty = document.createElement('p'); empty.className = 'chart-error'; empty.textContent = '所选时间范围内暂无采样数据。'; card.append(empty)
        return
      }
      const representative = group.series[0]
      const measurement = group.measurement
      const valueFormatter = value => metricValueLabel(representative.name, value, measurement)
      const chartElement = document.createElement('div'); chartElement.className = 'metric-chart'; card.append(chartElement)
      const memoryAxis = /^(jvm\.memory\.|jvm\.buffer\.)/.test(representative.name)
        || ['jvm.gc.live.data.size', 'jvm.gc.max.data.size'].includes(representative.name)
      createTimeSeriesChart(chartElement, {
        series: chartSeries,
        yAxisName: memoryAxis ? '' : metricAxisUnit(representative.name, measurement),
        valueFormatter,
        axisFormatter: value => metricAxisTick(representative.name, value, measurement),
        showLegend: chartSeries.length > 1,
        description: `${group.title}多序列指标趋势`,
        timeRange
      })
    } catch (error) {
      card.classList.remove('is-loading')
      const message = document.createElement('p'); message.className = 'chart-error'; message.textContent = error?.message || '读取趋势失败'; card.append(message)
    }
  }
  // 只读取视口附近图表的历史序列，避免进入 Metrics 时串行扫完整个图表目录。
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        observer.unobserve(entry.target)
        const placeholder = placeholders.find(item => item.card === entry.target)
        if (placeholder) void loadCard(placeholder)
      }
    }, { rootMargin: '160px 0px' })
    trendHistoryObserver = observer
    placeholders.forEach(({ card }) => observer.observe(card))
  } else {
    placeholders.forEach(placeholder => { void loadCard(placeholder) })
  }
}

function renderMetricDashboard(timeRange = currentMetricTimeRange(), force = false) {
  const key = `${timeRange.start}:${timeRange.end}`
  if (!force && renderedMetricRangeKey === key) return
  renderedMetricRangeKey = key
  renderTrends(timeRange).catch(error => showNotice(error?.message || '读取指标趋势失败'))
  renderHttpRed(timeRange).catch(error => showNotice(error?.message || '读取 HTTP 趋势失败'))
}

async function loadMetricData(force = false) {
  if (!force && metricDataLoaded) return
  if (metricDataLoading) return metricDataLoading
  metricDataLoading = Promise.all([getJson('/metrics/series'), getJson('/metrics?limit=2000')])
    .then(([catalog, metrics]) => {
      seriesCatalog = catalog
      liveMetrics = metrics
      metricDataLoaded = true
      seriesCacheGeneration++
      seriesCache.clear()
      seriesLoading.clear()
      redHistoryCache = null
      updateMetricStorageLabel()
    })
    .finally(() => { metricDataLoading = null })
  return metricDataLoading
}

async function refresh() {
  byId('refresh').disabled = true; showNotice('')
  try {
    const metricsViewActive = !byId('metrics-section').hidden
    const [statsResult, tracesResult, slowResult, errorResult, storageResult] = await Promise.allSettled([
      getJson('/request-statistics?source=ALL&limit=200'), getJson('/traces?limit=100'),
      getJson('/incidents?kind=slow&limit=100'), getJson('/incidents?kind=error&limit=100'), getJson('/metrics/storage')
    ])
    const failed = []
    if (metricsViewActive) {
      const alreadyLoadingMetrics = Boolean(metricDataLoading)
      try { await loadMetricData(true) }
      catch (error) { failed.push(error?.message || '读取指标目录失败') }
      if (!alreadyLoadingMetrics && metricDataLoaded) {
        metricTimeRange = captureMetricTimeRange()
        if (!byId('metrics-section').hidden) renderMetricDashboard(metricTimeRange, true)
        else renderedMetricRangeKey = ''
      }
    }
    if (statsResult.status === 'fulfilled') { requestStats = statsResult.value; renderStats() } else failed.push(statsResult.reason?.message)
    if (tracesResult.status === 'fulfilled') traces = tracesResult.value
    else failed.push(tracesResult.reason?.message)
    if (slowResult.status === 'fulfilled') slowIncidents = slowResult.value
    else { slowIncidents = []; failed.push(slowResult.reason?.message) }
    if (errorResult.status === 'fulfilled') errorIncidents = errorResult.value
    else { errorIncidents = []; failed.push(errorResult.reason?.message) }
    if (tracesResult.status === 'fulfilled' || slowResult.status === 'fulfilled' || errorResult.status === 'fulfilled') {
      renderIncidentTables()
    }
    const storageWarnings = []
    if (storageResult.status === 'fulfilled' && storageResult.value) {
      const storage = storageResult.value
      metricStorageStatus = storage
      metricRetentionDays = Number(storage.retentionDays)
      updateMetricRangeNote()
      updateMetricStorageLabel()
      byId('instance').textContent = `当前实例 · ${storage.enabled ? `RRD ${formatBytes(storage.storageBytes)}` : '历史未启用'}`
      const hasDrops = Number(storage.droppedWrites) > 0 || Number(storage.rejectedSeries) > 0
      byId('metric-storage-status').classList.toggle('is-warning', hasDrops)
      byId('instance').classList.toggle('is-warning', hasDrops)
      if (Number(storage.droppedWrites) > 0) storageWarnings.push(`指标历史有 ${formatNumber(storage.droppedWrites)} 次快照未完整写入`)
      if (Number(storage.rejectedSeries) > 0) storageWarnings.push(`${formatNumber(storage.rejectedSeries)} 条指标序列因存储限制被拒绝`)
    } else if (storageResult.status === 'rejected') failed.push(storageResult.reason?.message)
    const notices = [...failed.filter(Boolean), ...storageWarnings]
    if (notices.length) {
      showNotice(notices.join('；'))
    } else {
      showNotice('')
    }
  } catch (error) {
    showNotice(error?.message || '加载监控报告失败')
  }
  finally { byId('refresh').disabled = false }
}

byId('refresh').addEventListener('click', refresh)
byId('http-red-grid').addEventListener('click', event => {
  const metricButton = event.target.closest('button[data-red-metric]')
  const uriButton = event.target.closest('button[data-uri]')
  let focusTarget
  if (metricButton) {
    selectedRedMetric = metricButton.dataset.redMetric
    focusTarget = button => button.dataset.redMetric === selectedRedMetric
  } else if (uriButton) {
    const uri = uriButton.dataset.uri
    if (visibleRedUris.has(uri)) visibleRedUris.delete(uri)
    else visibleRedUris.add(uri)
    focusTarget = button => button.dataset.uri === uri
  }
  if (focusTarget) {
    renderHttpRed().then(() => {
      const next = [...byId('http-red-grid').querySelectorAll('button[data-red-metric], button[data-uri]')].find(focusTarget)
      next?.focus({ preventScroll: true })
    }).catch(error => showNotice(error?.message || '读取 HTTP 趋势失败'))
  }
})
window.addEventListener('resize', () => metricChartInstances.forEach(chart => chart.resize()), { passive: true })
byId('trace-prev').addEventListener('click', () => { recentPage = Math.max(0, recentPage - 1); renderIncidentTables() })
byId('trace-next').addEventListener('click', () => { recentPage++; renderIncidentTables() })
byId('close-entry-dialog').addEventListener('click', () => byId('entry-dialog').close())
const sectionLinks = [...document.querySelectorAll('.primary-nav a')]
const viewSections = sectionLinks.map(link => document.querySelector(link.hash)).filter(Boolean)
function activateView(id, updateHistory = false) {
  const selected = viewSections.find(section => section.id === id) || byId('metrics-section')
  viewSections.forEach(section => { section.hidden = section !== selected })
  sectionLinks.forEach(link => {
    const active = link.hash === `#${selected.id}`
    link.classList.toggle('active', active)
    if (active) link.setAttribute('aria-current', 'page')
    else link.removeAttribute('aria-current')
  })
  if (updateHistory && location.hash !== `#${selected.id}`) history.pushState(null, '', `#${selected.id}`)
  window.scrollTo({ top: 0, behavior: 'smooth' })
  if (selected.id === 'metrics-section') {
    if (metricDataLoaded) {
      metricTimeRange ||= captureMetricTimeRange()
      renderMetricDashboard(metricTimeRange)
    } else {
      byId('http-red-grid').innerHTML = '<p class="empty">正在读取指标目录…</p>'
      byId('trend-grid').innerHTML = '<p class="empty">正在读取指标目录…</p>'
      loadMetricData().then(() => {
        metricTimeRange ||= captureMetricTimeRange()
        renderMetricDashboard(metricTimeRange, true)
        updateMetricRangeNote()
      }).catch(error => showNotice(error?.message || '读取指标趋势失败'))
    }
  }
}
sectionLinks.forEach(link => link.addEventListener('click', event => {
  event.preventDefault()
  activateView(link.hash.slice(1), true)
}))
document.querySelector('.brand').addEventListener('click', event => {
  event.preventDefault()
  activateView('metrics-section', true)
})
window.addEventListener('popstate', () => activateView(location.hash.slice(1)))
activateView(location.hash.slice(1))
byId('period').addEventListener('change', () => {
  updateMetricRangeNote()
  redHistoryCache = null
  renderIncidentTables()
  if (byId('metrics-section').hidden) return
  metricTimeRange = captureMetricTimeRange()
  renderMetricDashboard(metricTimeRange, true)
})
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
initializeEmbeddedAuth().then(refresh)
