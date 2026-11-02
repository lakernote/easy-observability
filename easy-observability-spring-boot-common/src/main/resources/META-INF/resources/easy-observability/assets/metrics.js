const api = '/api/easy-observability/metrics'
const $ = id => document.getElementById(id)
const groups = [
  { id: 'http', label: 'HTTP', prefixes: ['http_'] },
  { id: 'tasks', label: '任务与 Kafka', prefixes: ['scheduled_', 'kafka_'] },
  { id: 'redis', label: 'Redis', prefixes: ['redis_'] },
  { id: 'jvm', label: 'JVM 与 CPU', prefixes: ['jvm_', 'memory_', 'gc_', 'process_', 'system_'] },
  { id: 'executor', label: '线程池', prefixes: ['executor_'] }
]
let selectedGroup = 'http'
let latestHistory
let activePlots = []

async function request(path) {
  const headers = new Headers()
  const token = typeof window.EasyObservabilityAuth?.token === 'function' ? window.EasyObservabilityAuth.token() : ''
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(path, { credentials: 'same-origin', headers })
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? '没有查看指标的权限，请登录或检查 ObservabilityAccessPolicy'
    : `请求失败（HTTP ${response.status}）`)
  return response.json()
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

function number(value, unit) {
  if (!Number.isFinite(value)) return '暂无数据'
  if (unit === 'bytes') {
    if (value < 1024) return `${Math.round(value)} B`
    const units = ['KiB', 'MiB', 'GiB', 'TiB']
    let amount = value
    for (const label of units) {
      amount /= 1024
      if (amount < 1024 || label === 'TiB') return `${amount.toFixed(1)} ${label}`
    }
  }
  const digits = unit === 'count'
    ? value >= 100 ? 0 : value < 1 ? 2 : 1
    : unit === 'ms' && value < 1 ? value < 0.01 ? 3 : 2 : 1
  return `${value.toLocaleString('zh-CN', { maximumFractionDigits: digits })}${unit === 'count' ? '' : ` ${unit}`}`
}

function displayValue(value, source) {
  if (source.id.endsWith('_pool_max') && value >= 2147483647) return '不设上限'
  if (source.id === 'process_cpu_pct' || source.id === 'system_cpu_pct') {
    return Number.isFinite(value) ? `${value.toFixed(1)}%` : '暂无数据'
  }
  return number(value, source.unit)
}

function pointIntervalLabel() {
  const seconds = latestHistory.pointIntervalSeconds
  if (seconds < 60) return `${seconds}秒`
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainingSeconds = seconds % 60
  if (hours) return `${hours}小时${minutes ? `${minutes}分` : ''}`
  return `${Math.floor(seconds / 60)}分${remainingSeconds ? `${remainingSeconds}秒` : ''}`
}

function pointValueLabel(source) {
  if (['7d', '30d'].includes($('period').value)) {
    return `每点约${pointIntervalLabel()}采样均值`
  }
  const interval = `${latestHistory.stepSeconds}秒`
  if (/_count$|_errors$|_completed$/.test(source.id)) return `每点为${interval}内数量`
  if (source.unit === 'ms') return `每点为${interval}平均耗时`
  return `每点为${interval}采样值`
}

function emptyDataHint() {
  const period = $('period').value
  if (period === '7d' || period === '30d') {
    return `此范围暂时没有可展示的归档点。${period === '7d' ? '7 天' : '30 天'}视图约每${pointIntervalLabel()}汇总一次，新建历史库后首个归档点形成前会暂时为空。`
  }
  return '此时间范围内还没有指标样本。指标按配置的采集周期写入，稍后刷新即可查看。'
}

function node(tag, className, text) {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text != null) element.textContent = text
  return element
}

function renderCategories() {
  const nav = $('categories')
  nav.replaceChildren()
  for (const group of groups) {
    const button = node('button', selectedGroup === group.id ? 'active' : '', group.label)
    button.type = 'button'
    button.setAttribute('aria-pressed', String(selectedGroup === group.id))
    button.addEventListener('click', () => { selectedGroup = group.id; render() })
    nav.append(button)
  }
}

function chart(chartSources, points) {
  const source = chartSources[0]
  const sources = chartSources.filter(item => points.some(point => Number.isFinite(point.values[item.id])))
  const card = node('article', 'metric-chart')
  const head = node('div', 'metric-chart-head')
  head.append(node('h2', '', source.displayName))
  const actions = node('div', 'metric-chart-actions')
  actions.append(node('span', 'metric-chart-hint', sources.length
    ? `${sources.length} 条曲线 · ${pointValueLabel(source)} · 点击图例开关`
    : '暂无曲线'))
  const showAll = node('button', 'metric-reset metric-show-all', '显示全部')
  showAll.type = 'button'
  showAll.hidden = true
  actions.append(showAll)
  const reset = node('button', 'metric-reset', '重置缩放')
  reset.type = 'button'
  reset.hidden = !sources.length
  actions.append(reset)
  head.append(actions)
  card.append(head)
  if (!sources.length) {
    card.append(node('div', 'metric-empty', emptyDataHint()))
    return card
  }
  const chartPoints = points.filter(point => sources.some(item => Number.isFinite(point.values[item.id])))
  const plotHost = node('div', 'metric-plot')
  plotHost.setAttribute('role', 'img')
  plotHost.setAttribute('aria-label', `${source.displayName}趋势图`)
  card.append(plotHost)
  const legend = node('div', 'metric-legend')
  legend.setAttribute('aria-label', `${source.displayName}曲线开关`)
  card.append(legend)
  const times = chartPoints.map(point => point.timestamp)
  const colors = ['#4169c9', '#08a68a', '#df8d28', '#8a62c2', '#db5d68', '#2696ad', '#5d9d4d', '#bd6e31',
    '#3955a5', '#00a6a6', '#e27730', '#a0559a', '#c94747', '#197aa6', '#799b34', '#8f6637',
    '#6876d9', '#24a37b', '#cf6b9e', '#64748b', '#9a741d', '#667b27', '#5266a8', '#b55230']
  const data = [times, ...sources.map(item => chartPoints.map(point => Number.isFinite(point.values[item.id]) ? point.values[item.id] : null))]
  const legendValues = []
  const legendButtons = []
  const visible = sources.map(() => true)
  sources.forEach((item, index) => {
    const button = node('button', 'metric-legend-item')
    button.type = 'button'
    button.setAttribute('aria-pressed', 'true')
    button.setAttribute('aria-label', `${item.legend}，显示曲线`)
    button.title = item.legend
    const marker = node('span', 'metric-legend-marker')
    marker.style.setProperty('--series-color', colors[index % colors.length])
    const label = node('span', 'metric-legend-label', item.legend)
    const value = node('span', 'metric-legend-value', '—')
    button.append(marker, label, value)
    legend.append(button)
    legendButtons.push(button)
    legendValues.push(value)
  })
  const latestIndex = item => {
    for (let index = chartPoints.length - 1; index >= 0; index--) {
      if (Number.isFinite(chartPoints[index].values[item.id])) return index
    }
    return -1
  }
  const showLatestValues = () => sources.forEach((item, index) => {
    const pointIndex = latestIndex(item)
    legendValues[index].textContent = pointIndex < 0
      ? '暂无数据' : displayValue(chartPoints[pointIndex].values[item.id], item)
  })
  const hoverTime = node('span', 'metric-hover-time', '最新值')
  const plottedSeries = [{ label: '时间' }, ...sources.map((item, index) => ({
    label: item.legend,
    stroke: colors[index % colors.length],
    width: 2,
    points: { show: false },
    value: (u, value) => value == null ? '—' : displayValue(value, item)
  }))]
  let clearingSelection = false
  const plot = new uPlot({
    width: Math.max(320, plotHost.clientWidth),
    height: 250,
    class: 'metric-uplot',
    scales: {
      x: { time: true },
      y: { range: (u, min, max) => {
        if (!Number.isFinite(min) || !Number.isFinite(max)) return [0, 1]
        if (min === max) return [Math.min(0, min), max + (Math.abs(max) * 0.05 || 1)]
        return [min, max]
      } }
    },
    axes: [
      { stroke: '#77869a', grid: { stroke: '#edf1f6', width: 1 }, ticks: { stroke: '#e4e9f0' },
        values: (u, values) => values.map(value => {
          const date = new Date(value * 1000)
          return $('period').value === '30d'
            ? `${date.getMonth() + 1}/${date.getDate()}`
            : $('period').value === '7d'
              ? `${date.getMonth() + 1}/${date.getDate()} ${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
              : $('period').value === '1h' && latestHistory.stepSeconds < 60
                ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
                : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        }) },
      { stroke: '#77869a', grid: { stroke: '#edf1f6', width: 1 }, ticks: { stroke: '#e4e9f0' },
        values: (u, values) => values.map(value => displayValue(value, source)) }
    ],
    series: plottedSeries,
    legend: { show: false },
    cursor: { drag: { x: true, y: false, setScale: false, dist: 8 } },
    hooks: { setCursor: [u => {
      const cursorIndex = u.cursor.idx
      if (cursorIndex == null) {
        showLatestValues()
        hoverTime.textContent = '最新值'
        return
      }
      const indices = u.cursor.idxs || []
      sources.forEach((item, index) => {
        const pointIndex = indices[index + 1] ?? cursorIndex
        const value = chartPoints[pointIndex]?.values[item.id]
        legendValues[index].textContent = Number.isFinite(value) ? displayValue(value, item) : '—'
      })
      const timestamp = chartPoints[cursorIndex]?.timestamp
      hoverTime.textContent = timestamp == null ? '悬停查看数值' : new Date(timestamp * 1000).toLocaleString('zh-CN')
    }] },
    select: { show: true },
    plugins: [{ hooks: { setSelect: [u => {
      if (clearingSelection) return
      const selection = u.select
      clearingSelection = true
      try {
        if (selection.width > 8) {
          u.setScale('x', { min: u.posToVal(selection.left, 'x'), max: u.posToVal(selection.left + selection.width, 'x') })
        }
        u.setSelect({ left: 0, top: 0, width: 0, height: 0 })
      } finally {
        clearingSelection = false
      }
    }] } }]
  }, data, plotHost)
  sources.forEach((item, index) => {
    legendButtons[index].addEventListener('click', () => {
      visible[index] = !visible[index]
      plot.setSeries(index + 1, { show: visible[index] })
      legendButtons[index].setAttribute('aria-pressed', String(visible[index]))
      legendButtons[index].setAttribute('aria-label', `${item.legend}，${visible[index] ? '显示' : '隐藏'}曲线`)
      legendButtons[index].classList.toggle('is-hidden', !visible[index])
      showAll.hidden = !visible.some(show => !show)
    })
  })
  showAll.addEventListener('click', () => {
    sources.forEach((_, index) => {
      visible[index] = true
      plot.setSeries(index + 1, { show: true })
      legendButtons[index].setAttribute('aria-pressed', 'true')
      legendButtons[index].setAttribute('aria-label', `${sources[index].legend}，显示曲线`)
      legendButtons[index].classList.remove('is-hidden')
    })
    showAll.hidden = true
  })
  plotHost.addEventListener('dblclick', () => plot.setScale('x', { min: times[0], max: times.at(-1) }))
  showLatestValues()
  const resizeObserver = new ResizeObserver(entries => {
    const width = Math.floor(entries[0].contentRect.width)
    if (width > 0 && width !== plot.width) plot.setSize({ width, height: 250 })
  })
  resizeObserver.observe(plotHost)
  activePlots.push({ plot, resizeObserver })
  reset.addEventListener('click', () => plot.setScale('x', { min: times[0], max: times.at(-1) }))
  const foot = node('div', 'metric-chart-foot')
  foot.append(node('span', '', times.length ? new Date(times[0] * 1000).toLocaleString('zh-CN') : ''))
  foot.append(hoverTime)
  foot.append(node('span', '', `${chartPoints.length} 个有效时间点 · 双击图表恢复范围`))
  foot.append(node('span', '', times.length ? new Date(times.at(-1) * 1000).toLocaleString('zh-CN') : ''))
  card.append(foot)
  return card
}

function render() {
  for (const item of activePlots) {
    item.resizeObserver.disconnect()
    item.plot.destroy()
  }
  activePlots = []
  renderCategories()
  const group = groups.find(item => item.id === selectedGroup)
  const series = latestHistory.series.filter(item => group.prefixes.some(prefix => item.id.startsWith(prefix)))
  const charts = new Map()
  for (const item of series) {
    if (!charts.has(item.chartId)) charts.set(item.chartId, [])
    charts.get(item.chartId).push(item)
  }
  const grid = $('metric-grid')
  grid.replaceChildren(...[...charts.values()].map(items => chart(items, latestHistory.points)))
  const hasData = series.some(item => latestHistory.points.some(point => Number.isFinite(point.values[item.id])))
  $('empty').hidden = hasData
  $('empty').textContent = emptyDataHint()
}

async function refresh() {
  $('notice').hidden = true
  try {
    const [history, storage] = await Promise.all([
      request(`${api}?period=${encodeURIComponent($('period').value)}`),
      request(`${api}/storage`)
    ])
    latestHistory = history
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = '已连接'
    $('storage').textContent = `历史存储 ${(storage.storageBytes / 1024).toFixed(0)} KiB / ${(storage.maxStorageBytes / 1024 / 1024).toFixed(0)} MiB · ${storage.stepSeconds} 秒采样 · 保留 ${Math.round(storage.retentionSeconds / 86400)} 天`
    render()
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = '未连接'
    $('notice').textContent = error.message
    $('notice').hidden = false
    $('metric-grid').replaceChildren()
    $('empty').hidden = true
  }
}

await initializeEmbeddedAuth()
$('refresh').addEventListener('click', refresh)
$('period').addEventListener('change', refresh)
await refresh()
