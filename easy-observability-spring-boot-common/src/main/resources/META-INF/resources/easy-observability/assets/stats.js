import {
  $, DISABLED_MESSAGE, categoryNames, countsSince, formatBytes, formatMs, initializeEmbeddedAuth, lineChart, node, request
} from './metrics-common.js?v=20261106.4'

const REFRESH_MS = 10000
// Day ranges change slowly and read history files, so poll them less often.
const LONG_RANGE_REFRESH_MS = 60000
const WINDOWS = new Set(['1h', 'today', '7d', '30d'])
const TREND_PERIOD = { '1h': '1h', today: '24h', '7d': '7d', '30d': '30d' }
const NOT_COLLECTING_MESSAGE = '调用统计来自 Trace 采集点，当前 easy.observability.trace.enabled=false，不会记录新的调用'
const columns = [
  { key: 'name', label: '名称' },
  { key: 'count', label: '次数', numeric: true },
  { key: 'errors', label: '错误', numeric: true },
  { key: 'meanMs', label: '平均', numeric: true },
  { key: 'p95Ms', label: 'P95', numeric: true, title: '由直方图估算，误差约 ±20%' },
  { key: 'maxMs', label: '最大', numeric: true },
  { key: 'totalMs', label: '总耗时', numeric: true },
  { key: 'share', label: '耗时占比', title: '该操作总耗时占本类别总耗时的比例' }
]
// Entries run on one thread from start to finish, so their CPU time and allocation are measured per call.
const ENTRY_CATEGORIES = new Set(['HTTP', 'JOB', 'KAFKA_CONSUMER'])
const resourceColumns = [
  { key: 'meanCpuMs', label: '平均 CPU', numeric: true, title: '入口线程每次调用消耗的 CPU 时间；异步请求不统计' },
  { key: 'meanAllocatedBytes', label: '平均内存分配', numeric: true, title: '入口线程每次调用分配的堆内存（不是占用量）；异步请求不统计' }
]

function visibleColumns() {
  if (!ENTRY_CATEGORIES.has(activeCategory)) return columns
  return [...columns.slice(0, -1), ...resourceColumns, columns[columns.length - 1]]
}
let snapshot = null
let activeCategory = ''
let sortKey = 'totalMs'
let sortDescending = true
let timer
let searchTimer
// The expanded row survives re-renders; its chart is reloaded on every refresh.
let expanded = null
let detail = null
let detailCharts = []

function formatPercent(value) {
  if (!Number.isFinite(value) || value <= 0) return '0%'
  return value < 0.1 ? '<0.1%' : `${value.toFixed(value < 10 ? 1 : 0)}%`
}

function currentCategory() {
  return snapshot?.categories.find(item => item.category === activeCategory)
}

function renderCategories() {
  const nav = $('categories')
  nav.replaceChildren()
  for (const category of snapshot.categories) {
    const button = node('button', category.category === activeCategory ? 'active' : '')
    button.type = 'button'
    button.append(categoryNames[category.category] || category.category,
      node('small', '', Number(category.count).toLocaleString()))
    button.setAttribute('aria-pressed', String(category.category === activeCategory))
    button.addEventListener('click', () => { activeCategory = category.category; collapse(); render() })
    nav.append(button)
  }
}

function renderHead() {
  const head = $('stats-head')
  head.replaceChildren()
  for (const column of visibleColumns()) {
    const th = node('th', column.numeric ? 'numeric' : '')
    th.scope = 'col'
    if (column.title) th.title = column.title
    if (column.key === sortKey) th.setAttribute('aria-sort', sortDescending ? 'descending' : 'ascending')
    const button = node('button', '', column.label)
    button.type = 'button'
    button.addEventListener('click', () => {
      if (sortKey === column.key) sortDescending = !sortDescending
      else { sortKey = column.key; sortDescending = column.key !== 'name' }
      render()
    })
    th.append(button)
    head.append(th)
  }
}

function sortedRows(category) {
  const query = $('search').value.trim().toLocaleLowerCase()
  const total = Number(category.totalMs || 0)
  const rows = category.rows
    .filter(row => !query || String(row.name).toLocaleLowerCase().includes(query))
    .map(row => ({ ...row, share: total > 0 ? row.totalMs / total * 100 : 0 }))
  const direction = sortDescending ? -1 : 1
  return rows.sort((left, right) => {
    const a = sortKey === 'errors' ? left.errors / left.count : left[sortKey]
    const b = sortKey === 'errors' ? right.errors / right.count : right[sortKey]
    if (sortKey === 'name') return direction * String(a).localeCompare(String(b))
    return direction * (Number(a) - Number(b)) || String(left.name).localeCompare(String(right.name))
  })
}

function isExpanded(row) {
  return expanded && expanded.category === activeCategory && expanded.name === row.name
}

function collapse() {
  expanded = null
  for (const chart of detailCharts) chart.destroy()
  detailCharts = []
  detail = null
}

function toggle(row) {
  if (isExpanded(row)) collapse()
  else {
    collapse()
    expanded = { category: activeCategory, name: row.name }
  }
  render()
  if (expanded) loadTrend()
}

function detailRow() {
  if (!detail) {
    detail = document.createElement('tr')
    detail.className = 'stats-detail'
    const cell = node('td')
    cell.colSpan = visibleColumns().length
    cell.append(node('div', 'stats-detail-body'))
    detail.append(cell)
  }
  return detail
}

// Trace search matches entry names, SQL and span tags, so the operation name finds the recorded slow and failed calls.
function traceLink(target) {
  const actions = node('div', 'stats-detail-actions')
  const link = node('a', '', '查看相关的慢 / 错误 Trace →')
  // Remote rows are named "GET host /path"; trace spans only carry the path.
  const remotePath = target.category === 'REMOTE' ? /^[A-Z]+ \S+ (.+)$/.exec(target.name)?.[1] : null
  link.href = `../?q=${encodeURIComponent(remotePath || target.name)}`
  actions.append(link)
  return actions
}

async function loadTrend() {
  if (!expanded) return
  const target = expanded
  const period = TREND_PERIOD[$('window').value] || '1h'
  const body = detailRow().querySelector('.stats-detail-body')
  if (!detailCharts.length) body.replaceChildren(node('p', 'stats-detail-status', '读取趋势中…'))
  try {
    const trend = await request(`metrics/operations/trend?category=${encodeURIComponent(target.category)}`
      + `&name=${encodeURIComponent(target.name)}&period=${period}`)
    if (expanded !== target) return
    for (const chart of detailCharts) chart.destroy()
    const timestamps = trend.points.map(point => point.timestamp)
    const minute = trend.period === 'HOUR'
    detailCharts = [
      lineChart({ title: '调用次数', unit: 'count', timestamps, stepMillis: trend.stepMillis, height: 180, series: [
        { label: '次数', values: countsSince(trend, point => point.count) },
        ...(trend.points.some(point => point.errors > 0)
          ? [{ label: '错误', values: countsSince(trend, point => point.errors) }] : [])] }),
      lineChart({ title: '耗时', unit: 'ms', timestamps, stepMillis: trend.stepMillis, height: 180,
        hint: minute ? '按分钟统计时不计算 P95' : 'P95 由直方图估算，误差约 ±20%', series: [
          { label: '平均', values: trend.points.map(point => point.meanMs) },
          ...(minute ? [] : [{ label: 'P95', values: trend.points.map(point => point.p95Ms) }]),
          { label: '最大', values: trend.points.map(point => point.maxMs) }] })
    ]
    const grid = node('div', 'stats-detail-grid')
    grid.append(...detailCharts.map(chart => chart.card))
    body.replaceChildren(traceLink(target), grid)
  } catch (error) {
    if (expanded === target) body.replaceChildren(node('p', 'stats-detail-status', error.message))
  }
}

function renderRows(category) {
  const tbody = $('stats-rows')
  // Detach the detail row first so replaceChildren keeps its charts alive.
  if (detail) detail.remove()
  tbody.replaceChildren()
  const rows = sortedRows(category)
  for (const row of rows) {
    const tr = document.createElement('tr')
    const open = isExpanded(row)
    const name = node('td', 'name', row.name)
    name.title = row.name
    const errorRate = row.count > 0 ? row.errors / row.count * 100 : 0
    const errors = node('td', `numeric${row.errors > 0 ? ' bad' : ''}`,
      row.errors > 0 ? `${Number(row.errors).toLocaleString()}（${formatPercent(errorRate)}）` : '0')
    const share = node('td')
    const shareBox = node('div', 'stats-share')
    const bar = node('div', 'stats-share-bar')
    const fill = node('i')
    fill.style.width = `${Math.min(100, Math.max(0, row.share)).toFixed(1)}%`
    bar.append(fill)
    shareBox.append(bar, node('span', '', formatPercent(row.share)))
    share.append(shareBox)
    tr.append(name, node('td', 'numeric strong', Number(row.count).toLocaleString()), errors,
      node('td', 'numeric', formatMs(row.meanMs)), node('td', 'numeric', `≈ ${formatMs(row.p95Ms)}`),
      node('td', 'numeric', formatMs(row.maxMs)), node('td', 'numeric strong', formatMs(row.totalMs)),
      ...(ENTRY_CATEGORIES.has(activeCategory) ? [
        node('td', 'numeric', row.meanCpuMs == null ? '—' : formatMs(row.meanCpuMs)),
        node('td', 'numeric', formatBytes(row.meanAllocatedBytes))] : []),
      share)
    tr.tabIndex = 0
    tr.setAttribute('aria-expanded', String(open))
    tr.title = open ? '收起趋势' : '查看趋势'
    if (open) tr.classList.add('is-expanded')
    tr.addEventListener('click', () => toggle(row))
    tr.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggle(row) }
    })
    tbody.append(tr)
    if (open) tbody.append(detailRow())
  }
  const empty = $('empty')
  empty.hidden = rows.length > 0
  empty.textContent = category.rows.length
    ? '没有名称匹配的操作'
    : `所选时间范围内还没有${categoryNames[category.category] || ''}调用`
}

function renderSummary() {
  const since = new Date(snapshot.since)
  const sinceText = Number.isNaN(since.getTime()) ? '' : `自 ${since.toLocaleString('zh-CN')} 起`
  const limit = snapshot.keys >= snapshot.maxKeys ? '，已达上限，新操作计入“其他”' : ''
  const memoryOnly = snapshot.window !== 'HOUR' && !snapshot.persistent
    ? ' · 存储目录不可用，只含内存中最近 2 小时' : ''
  $('summary').textContent = `${sinceText} · 内存中 ${snapshot.keys.toLocaleString()} 个操作（上限 ${snapshot.maxKeys.toLocaleString()}${limit}）${memoryOnly}`
}

function render() {
  if (!snapshot) return
  if (!currentCategory()) activeCategory = snapshot.categories[0]?.category || ''
  renderCategories()
  renderHead()
  renderSummary()
  const category = currentCategory()
  if (category) renderRows(category)
}

function showNotice(message, warning = false) {
  $('notice').textContent = message
  $('notice').classList.toggle('warning', warning)
  $('notice').hidden = false
}

async function refresh() {
  try {
    const next = await request(`metrics/operations?window=${encodeURIComponent($('window').value)}`)
    if (!activeCategory) activeCategory = next.categories.find(item => item.count > 0)?.category || 'HTTP'
    snapshot = next
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = '已连接'
    if (next.collecting) $('notice').hidden = true
    else showNotice(NOT_COLLECTING_MESSAGE, true)
    render()
    loadTrend()
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = '连接失败'
    showNotice(error.message === DISABLED_MESSAGE ? error.message : `${error.message}。请检查应用权限和服务状态。`)
    if (error.message === DISABLED_MESSAGE) {
      window.clearInterval(timer)
      $('instance').lastChild.textContent = '未启用'
    }
  }
}

async function loadPermissions() {
  try {
    const permissions = await request('/permissions')
    $('reset-stats').hidden = !permissions.canClear
  } catch {
    $('reset-stats').hidden = true
  }
}

function schedule() {
  window.clearInterval(timer)
  timer = window.setInterval(refresh, $('window').value === '1h' || $('window').value === 'today'
    ? REFRESH_MS : LONG_RANGE_REFRESH_MS)
}

function bind() {
  const requested = new URLSearchParams(window.location.search).get('window')
  if (WINDOWS.has(requested)) $('window').value = requested
  $('window').addEventListener('change', () => {
    const url = new URL(window.location.href)
    url.searchParams.set('window', $('window').value)
    window.history.replaceState(null, '', url)
    for (const chart of detailCharts) chart.destroy()
    detailCharts = []
    schedule()
    refresh()
  })
  $('search').addEventListener('input', () => {
    window.clearTimeout(searchTimer)
    searchTimer = window.setTimeout(render, 140)
  })
  $('refresh').addEventListener('click', refresh)
  $('reset-stats').addEventListener('click', () => $('reset-dialog').showModal())
  $('cancel-reset').addEventListener('click', () => $('reset-dialog').close())
  $('confirm-reset').addEventListener('click', async () => {
    $('confirm-reset').disabled = true
    try {
      await request('/metrics/operations/reset', { method: 'POST' },
        '没有重置统计的权限，请检查 ObservabilityAccessPolicy#canClear 或 CSRF 配置')
      $('reset-dialog').close()
      collapse()
      await refresh()
    } catch (error) {
      showNotice(error.message)
    } finally { $('confirm-reset').disabled = false }
  })
}

bind()
initializeEmbeddedAuth().then(() => Promise.all([loadPermissions(), refresh()]))
schedule()
window.addEventListener('beforeunload', () => window.clearInterval(timer), { once: true })
