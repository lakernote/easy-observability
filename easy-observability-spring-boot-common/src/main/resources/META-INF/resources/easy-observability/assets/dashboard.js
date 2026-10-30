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

function unfinishedSpanCount(nodes) {
  if (!Array.isArray(nodes)) return 0
  return nodes.reduce((count, item) => count + (item.unfinishedAtTraceEnd ? 1 : 0)
    + unfinishedSpanCount(item.children), 0)
}

function threadInfo(item, entryThread) {
  if (!item.threadName && item.threadId == null) return null
  const sameThread = entryThread && item.threadId != null && entryThread.threadId != null
    ? String(item.threadId) === String(entryThread.threadId)
    : Boolean(entryThread?.threadName && item.threadName === entryThread.threadName)
  const name = item.threadName || `线程 ${item.threadId}`
  return {
    label: sameThread ? '入口线程' : `子线程 · ${name}`,
    id: item.threadId,
    isEntry: Boolean(sameThread)
  }
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
      trace.highCpu ? 'CPU 时间超阈值' : '', trace.highAllocation ? '内存分配量超阈值' : '']
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
    if (trace.highCpu) resultCell.append(node('span', 'badge resource', 'CPU 时间超阈值'))
    if (trace.highAllocation) resultCell.append(node('span', 'badge resource', '内存分配量超阈值'))
    if (unfinishedSpanCount(trace.treeNodes)) {
      const unfinishedBadge = node('span', 'badge unfinished', '请求结束时未完成')
      unfinishedBadge.title = '该子 Span 在入口请求结束时仍在运行'
      resultCell.append(unfinishedBadge)
    }
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
    const threadKey = item.threadId != null ? `id:${item.threadId}`
      : item.threadName ? `name:${item.threadName}` : 'unknown'
    flat.push({ item, depth, threadKey })
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
  const entryThread = flat.find(({ depth }) => depth === 0)?.item
  const threadEntries = []
  const threadsByKey = new Map()
  for (const span of flat) {
    let thread = threadsByKey.get(span.threadKey)
    if (!thread) {
      thread = { key: `T${threadEntries.length + 1}`, info: threadInfo(span.item, entryThread), representative: span.item }
      threadsByKey.set(span.threadKey, thread)
      threadEntries.push(thread)
    }
  }
  const hasMultipleThreads = threadEntries.length > 1
  const deepest = candidates => candidates.reduce((best, candidate) => {
    if (!best || candidate.depth > best.depth) return candidate
    if (candidate.depth === best.depth
      && Number(candidate.item.durationMs || 0) > Number(best.item.durationMs || 0)) return candidate
    return best
  }, null)
  const primary = deepest(children.filter(({ item }) => item.error))
    || deepest(children.filter(({ item }) => item.slow))
    || deepest(children.filter(({ item }) => item.unfinishedAtTraceEnd))
    || children.reduce((longest, candidate) =>
      !longest || Number(candidate.item.durationMs || 0) > Number(longest.item.durationMs || 0) ? candidate : longest, null)
    || flat[0]
  const scale = node('div', 'timeline-scale')
  scale.append(node('span', 'timeline-scale-label', ''))
  scale.append(node('span', 'timeline-thread-heading', hasMultipleThreads ? '线程' : ''))
  const ticks = node('span', 'timeline-ticks')
  ticks.append(node('span', '', '0 ms'), node('span', '', `${Math.round(maxMs / 2)} ms`), node('span', '', `${maxMs} ms`))
  scale.append(ticks, node('span', 'timeline-scale-duration', ''))
  container.append(scale)
  if (hasMultipleThreads) {
    const legend = node('div', 'thread-legend')
    legend.append(node('span', 'thread-legend-title', '线程编号'))
    for (const thread of threadEntries) {
      const threadName = thread.representative.threadName || `线程 ${thread.representative.threadId || ''}`
      const role = thread.info?.isEntry ? '入口线程' : '子线程'
      const item = node('span', `thread-legend-item${thread.info?.isEntry ? ' entry-thread' : ' worker-thread'}`,
        `${thread.key} · ${role} · ${threadName}`)
      item.title = `${item.textContent}${thread.representative.threadId == null ? '' : ` · ID ${thread.representative.threadId}`}`
      legend.append(item)
    }
    container.insertBefore(legend, scale)
  }
  if (!hasOffsets && flat.length > 1) container.append(node('p', 'timeline-legacy', '该 Trace 是旧版本记录，未保存节点开始偏移；时间线位置仅供参考。'))
  let selectedRow = null
  let primaryRow = null
  for (const { item, depth, threadKey } of flat) {
    const thread = threadsByKey.get(threadKey)
    const row = node('button', `timeline-row${item.error ? ' has-error' : item.slow ? ' is-slow' : item.unfinishedAtTraceEnd ? ' is-unfinished' : ''}`)
    row.type = 'button'
    row.style.setProperty('--depth', Math.min(depth, 8))
    row.setAttribute('aria-label', `${item.kind || '调用'} ${item.name || '未命名'}，${Number(item.durationMs || 0)} 毫秒${hasMultipleThreads ? `，${thread.key} ${thread.info?.label || '线程信息未记录'}` : ''}${item.error ? '，错误' : ''}${item.slow ? '，慢调用' : ''}${item.unfinishedAtTraceEnd ? '，请求结束时仍在运行' : ''}`)
    const label = node('span', 'timeline-label')
    label.append(node('small', `timeline-kind kind-${String(item.kind || 'other').toLowerCase()}`, item.kind || 'Trace'))
    label.append(node('span', 'timeline-name', item.name || '未命名调用'))
    if (item.error) label.append(node('span', 'tree-state error-state', '错误'))
    if (item.slow) label.append(node('span', 'tree-state slow-state', '慢调用'))
    if (item.unfinishedAtTraceEnd) label.append(node('span', 'tree-state unfinished-state', '请求结束时仍运行'))
    const threadMarker = node('span', `timeline-thread${thread.info?.isEntry ? ' entry-thread' : ' worker-thread'}`,
      hasMultipleThreads ? thread.key : '')
    threadMarker.title = `${thread.key} · ${thread.info?.label || '线程信息未记录'} · ${thread.representative.threadName || ''}${thread.representative.threadId == null ? '' : ` · ID ${thread.representative.threadId}`}`
    const track = node('span', 'timeline-track')
    const bar = node('i', `timeline-bar${item.error ? ' error-fill' : item.slow ? ' slow-fill' : ''}${item.unfinishedAtTraceEnd ? ' is-unfinished' : ''}`)
    const offset = hasOffsets ? Math.max(0, Number(item.startOffsetMs || 0)) : 0
    const duration = Math.max(1, Number(item.durationMs) || 0)
    bar.style.left = `${Math.min(99, offset / maxMs * 100)}%`
    bar.style.width = `${Math.max(0.7, Math.min(100 - offset / maxMs * 100, duration / maxMs * 100))}%`
    bar.title = `${offset} ms 开始 · ${duration} ms 持续`
    track.append(bar)
    row.append(label, threadMarker, track, node('span', 'timeline-duration', `${Number(item.durationMs || 0).toLocaleString()} ms`))
    row.addEventListener('click', () => {
      if (selectedRow) selectedRow.setAttribute('aria-pressed', 'false')
      selectedRow = row
      row.setAttribute('aria-pressed', 'true')
      renderSpanDetail(detailContainer, item, offset, hasOffsets, entryThread)
    })
    row.setAttribute('aria-pressed', 'false')
    container.append(row)
    if (primary && item === primary.item) primaryRow = row
  }
  primaryRow?.click()
}

function renderSpanDetail(container, item, offset, hasOffsets, entryThread) {
  container.replaceChildren()
  const heading = node('div', 'span-detail-head')
  heading.append(node('span', 'eyebrow', 'SPAN DETAIL'), node('h4', '', item.name || '未命名调用'))
  container.append(heading)
  const facts = node('div', 'span-facts')
  const thread = threadInfo(item, entryThread)
  for (const [label, value] of [
    ['类型', item.kind || 'Trace'], ['耗时', `${Number(item.durationMs || 0).toLocaleString()} ms`],
    ['开始偏移', hasOffsets ? `${offset.toLocaleString()} ms` : '旧记录未采集'],
    ['线程', thread ? `${thread.label}${thread.id == null ? '' : ` · ${thread.id}`}` : '未记录'],
    ['状态', item.unfinishedAtTraceEnd ? '请求结束时仍在运行（时长按入口结束截取）'
      : item.error ? `错误${item.errorType ? ` · ${item.errorType}` : ''}` : item.slow ? '慢调用' : '已完成']
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
    const resourceAlerts = [detail.highCpu ? 'CPU 时间超阈值' : '', detail.highAllocation ? '内存分配量超阈值' : ''].filter(Boolean)
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
    addFact($('resource-facts-content'), 'CPU 时间', Number.isFinite(cpuTimeMs) && cpuTimeMs >= 0
      ? `${cpuTimeMs.toLocaleString()} ms` : '未采集', '',
      '统计入口线程在本次入口执行期间实际占用 CPU 的累计时间；不是 CPU 使用率，也不包含子线程')
    addFact($('resource-facts-content'), '内存分配量', formatAllocatedBytes(detail.allocatedBytes), '',
      '统计入口线程在本次入口执行期间累计分配的对象大小；不是当前堆占用，也不包含子线程或远程服务')
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
