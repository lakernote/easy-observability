const api = '/api/easy-observability'
const $ = id => document.getElementById(id)
const PAGE_SIZE = 50
let traces = []
let page = 0
let timer

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
  return Number.isNaN(date.getTime()) ? escText(value) : date.toLocaleString()
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
    if (result === 'slow' && !trace.slow) return false
    if (result === 'error' && !trace.error) return false
    if (source && trace.sourceType !== source) return false
    if (!query) return true
    return [trace.name, trace.method, trace.traceId, trace.tree, trace.errorType, trace.errorMessage]
      .some(value => String(value || '').toLocaleLowerCase().includes(query))
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
    const status = trace.error ? ['error', '错误'] : trace.slow ? ['slow', '慢'] : ['ok', '正常']
    const badge = node('span', `badge ${status[0]}`, status[1])
    const resultCell = node('td'); resultCell.append(badge); tr.append(resultCell)
    tr.append(node('td', 'entry', `${sourceNames[trace.sourceType] || 'Trace'} · ${trace.name || trace.method || '入口'}`))
    tr.append(node('td', '', trace.status ? String(trace.status) : '—'))
    tr.append(node('td', 'duration', `${Number(trace.durationMs || 0).toLocaleString()} ms`))
    tr.append(node('td', 'trace-id', trace.traceId))
    tr.append(node('td', 'time', time(trace.finishedAt)))
    tr.tabIndex = 0
    tr.setAttribute('role', 'button')
    tr.setAttribute('aria-label', `查看 ${trace.name || trace.traceId} 的 Trace 详情`)
    tr.addEventListener('click', () => openTrace(trace))
    tr.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openTrace(trace) } })
    tbody.append(tr)
  }
  $('empty').hidden = rows.length > 0
  $('result-count').textContent = `${rows.length} 条 Trace`
  $('page-label').textContent = rows.length ? `第 ${page + 1} / ${lastPage + 1} 页` : ''
  $('previous').disabled = page <= 0
  $('next').disabled = page >= lastPage
}

function addFact(container, label, value, className = '') {
  const item = node('div', `fact ${className}`)
  item.append(node('small', '', label), node('span', '', escText(value)))
  container.append(item)
}

async function openTrace(summary) {
  $('detail-title').textContent = summary.name || summary.traceId || '调用详情'
  $('facts').replaceChildren()
  $('tree').textContent = '正在读取调用树…'
  $('error-section').hidden = true
  $('detail-dialog').showModal()
  try {
    const detail = await request(`/traces/${encodeURIComponent(summary.traceId)}?source=${encodeURIComponent(summary.sourceType || '')}`)
    if (!detail) throw new Error('这条 Trace 已不在保留范围内')
    $('facts').replaceChildren()
    addFact($('facts'), '结果', detail.error ? '错误' : detail.slow ? '慢调用' : '正常', detail.error ? 'bad' : detail.slow ? 'warn' : '')
    addFact($('facts'), 'HTTP 状态', detail.status || '—')
    addFact($('facts'), '总耗时', `${Number(detail.durationMs || 0).toLocaleString()} ms`)
    addFact($('facts'), '入口', `${sourceNames[detail.sourceType] || 'Trace'} · ${detail.name || detail.method || '入口'}`)
    addFact($('facts'), 'Trace ID', detail.traceId, 'wide')
    addFact($('facts'), '线程', detail.threadName || detail.threadId)
    $('tree').textContent = detail.tree || '没有可展示的子调用'
    if (detail.error || detail.errorType || detail.errorStackTrace) {
      $('error-section').hidden = false
      $('error-message').textContent = [detail.errorType, detail.errorMessage].filter(Boolean).join(': ') || '有错误标记，未捕获到异常堆栈。'
      $('error-stack').textContent = detail.errorStackTrace || '当前异常没有可用的堆栈。'
    }
  } catch (error) {
    $('tree').textContent = error.message
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
  $('search').addEventListener('input', () => { page = 0; render() })
  $('previous').addEventListener('click', () => { page = Math.max(0, page - 1); render() })
  $('next').addEventListener('click', () => { page += 1; render() })
  $('refresh').addEventListener('click', refresh)
  $('close-dialog').addEventListener('click', () => $('detail-dialog').close())
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
