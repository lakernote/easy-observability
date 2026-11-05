const api = new URL('../../api/easy-observability/', import.meta.url)
const $ = id => document.getElementById(id)
const REFRESH_MS = 10000
// Day ranges change slowly and read history files, so poll them less often.
const LONG_RANGE_REFRESH_MS = 60000
const WINDOWS = new Set(['1h', 'today', '7d', '30d'])
const DISABLED_MESSAGE = '调用统计未启用：请确认 easy.observability.trace.enabled 和 easy.observability.stats.enabled 均为 true'
const categoryNames = {
  HTTP: 'HTTP 接口', SQL: 'SQL', REMOTE: '远程调用', REDIS: 'Redis',
  JOB: '定时任务', KAFKA_CONSUMER: 'Kafka 消费', KAFKA_PRODUCER: 'Kafka 发送'
}
// Trace Explorer can filter by these entry types; dependency rows search inside Trace trees instead.
const traceSources = new Set(['HTTP', 'JOB', 'KAFKA_CONSUMER'])
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
let snapshot = null
let activeCategory = ''
let sortKey = 'totalMs'
let sortDescending = true
let timer
let searchTimer

async function request(path, options = {}, deniedMessage = '没有查看统计的权限，请登录或检查 ObservabilityAccessPolicy') {
  const headers = new Headers(options.headers || {})
  const token = typeof window.EasyObservabilityAuth?.token === 'function' ? window.EasyObservabilityAuth.token() : ''
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const method = (options.method || 'GET').toUpperCase()
  // Spring Security's CookieCsrfTokenRepository exposes XSRF-TOKEN; echo it on state-changing requests.
  const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  if (csrf && method !== 'GET' && method !== 'HEAD') headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf[1]))
  const response = await fetch(new URL(path.replace(/^\/+/, ''), api),
    { credentials: 'same-origin', ...options, headers })
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? deniedMessage
    : response.status === 404 ? DISABLED_MESSAGE : `请求失败（HTTP ${response.status}）`)
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

function node(tag, className, text) {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text != null) element.textContent = text
  return element
}

function formatMs(value) {
  const ms = Number(value || 0)
  if (ms >= 1000) return `${(ms / 1000).toLocaleString(undefined, { maximumFractionDigits: ms >= 100000 ? 0 : 2 })} s`
  if (ms >= 100) return `${Math.round(ms).toLocaleString()} ms`
  if (ms >= 1) return `${ms.toFixed(1)} ms`
  return `${ms.toFixed(2)} ms`
}

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
    button.addEventListener('click', () => { activeCategory = category.category; render() })
    nav.append(button)
  }
}

function renderHead() {
  const head = $('stats-head')
  head.replaceChildren()
  for (const column of columns) {
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

function traceLink(row) {
  const target = new URL('../', window.location.href)
  // traceQuery matches the text traces store for this operation; the display name may join several fields.
  target.searchParams.set('q', String(row.traceQuery).slice(0, 200))
  if (traceSources.has(activeCategory)) target.searchParams.set('source', activeCategory)
  return target
}

function renderRows(category) {
  const tbody = $('stats-rows')
  tbody.replaceChildren()
  const rows = sortedRows(category)
  for (const row of rows) {
    const tr = document.createElement('tr')
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
      node('td', 'numeric', formatMs(row.maxMs)), node('td', 'numeric strong', formatMs(row.totalMs)), share)
    if (row.traceQuery) {
      tr.tabIndex = 0
      tr.setAttribute('role', 'link')
      tr.setAttribute('aria-label', `查看 ${row.name} 相关的慢调用和错误 Trace`)
      tr.title = '查看相关的慢调用和错误 Trace'
      const open = () => { window.location.href = traceLink(row).toString() }
      tr.addEventListener('click', open)
      tr.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open() } })
    } else {
      tr.classList.add('static-row')
    }
    tbody.append(tr)
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

async function refresh() {
  try {
    const next = await request(`stats?window=${encodeURIComponent($('window').value)}`)
    if (!activeCategory) activeCategory = next.categories.find(item => item.count > 0)?.category || 'HTTP'
    snapshot = next
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = '已连接'
    $('notice').hidden = true
    render()
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = '连接失败'
    $('notice').textContent = error.message === DISABLED_MESSAGE ? error.message : `${error.message}。请检查应用权限和服务状态。`
    $('notice').hidden = false
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
      await request('/stats/reset', { method: 'POST' },
        '没有重置统计的权限，请检查 ObservabilityAccessPolicy#canClear 或 CSRF 配置')
      $('reset-dialog').close()
      await refresh()
    } catch (error) {
      $('notice').textContent = error.message
      $('notice').hidden = false
    } finally { $('confirm-reset').disabled = false }
  })
}

bind()
initializeEmbeddedAuth().then(() => Promise.all([loadPermissions(), refresh()]))
schedule()
window.addEventListener('beforeunload', () => window.clearInterval(timer), { once: true })
