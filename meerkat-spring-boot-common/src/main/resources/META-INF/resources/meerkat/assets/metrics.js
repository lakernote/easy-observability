import {
  $, DISABLED_MESSAGE, formatValue, initializeEmbeddedAuth, lineChart, request
} from './metrics-common.js?v=20261106.11'

const PERIODS = new Set(['1h', '24h', '7d', '30d'])
const REFRESH_MS = 60000
/** Each chart lists series keys; a key ending in ':' matches every pool, executor or area of that meter. */
const RESOURCE_CHARTS = [
  { group: 'cpu-memory', title: t('CPU 使用率'), unit: 'percent', series: [
    { key: 'process.cpu.usage', label: t('进程'), scale: 100 }, { key: 'system.cpu.usage', label: t('系统'), scale: 100 }] },
  { group: 'cpu-memory', title: t('系统负载（1 分钟）'), unit: 'count', series: [{ key: 'system.load.average.1m', label: t('负载') }] },
  { group: 'cpu-memory', title: t('堆内存'), unit: 'bytes', series: [
    { key: 'jvm.memory.used:heap', label: t('已用') }, { key: 'jvm.memory.committed:heap', label: t('已提交') },
    { key: 'jvm.memory.max:heap', label: t('上限') }] },
  { group: 'cpu-memory', title: t('非堆内存'), unit: 'bytes', series: [
    { key: 'jvm.memory.used:nonheap', label: t('已用') }, { key: 'jvm.memory.committed:nonheap', label: t('已提交') }] },
  { group: 'gc', title: t('GC 次数（每分钟）'), unit: 'count', series: [{ key: 'jvm.gc.pause.count', label: t('GC 次数') }] },
  { group: 'gc', title: t('GC 停顿时间（每分钟）'), unit: 'ms', series: [{ key: 'jvm.gc.pause.time', label: t('停顿时间') }] },
  { group: 'gc', title: t('内存分配速率（每分钟）'), unit: 'bytes', series: [{ key: 'jvm.gc.memory.allocated', label: t('分配量') }] },
  { group: 'threads', title: t('线程'), unit: 'count', series: [
    { key: 'jvm.threads.live', label: t('JVM 活跃') }, { key: 'jvm.threads.daemon', label: t('JVM 守护') },
    { key: 'tomcat.threads.busy', label: t('Tomcat 忙碌') }, { key: 'tomcat.threads.current', label: t('Tomcat 当前') },
    { key: 'tomcat.threads.config.max', label: t('Tomcat 上限') }, { key: 'jetty.threads.busy', label: t('Jetty 忙碌') },
    { key: 'jetty.threads.current', label: t('Jetty 当前') }, { key: 'jetty.threads.config.max', label: t('Jetty 上限') }] },
  { group: 'database', title: t('数据库连接池'), unit: 'count', series: [
    { key: 'hikaricp.connections.active:', label: t('活跃') }, { key: 'hikaricp.connections.idle:', label: t('空闲') },
    { key: 'hikaricp.connections.pending:', label: t('等待') }, { key: 'hikaricp.connections.max:', label: t('上限') }] },
  { group: 'database', title: t('获取连接超时（每分钟）'), unit: 'count', series: [{ key: 'hikaricp.connections.timeout:', label: t('超时') }] },
  { group: 'threads', title: t('线程池'), unit: 'count', series: [
    { key: 'executor.active:', label: t('活跃') }, { key: 'executor.queued:', label: t('排队') },
    { key: 'executor.pool.size:', label: t('线程数', null, 'pool') }, { key: 'executor.pool.max:', label: t('上限') }] },
  { group: 'threads', title: t('线程池完成任务（每分钟）'), unit: 'count', series: [{ key: 'executor.completed:', label: t('完成') }] },
  { group: 'system', title: t('HTTP 会话'), unit: 'count', series: [{ key: 'tomcat.sessions.active.current', label: t('活跃会话') }] },
  { group: 'system', title: t('文件句柄'), unit: 'count', series: [
    { key: 'process.files.open', label: t('已打开') }, { key: 'process.files.max', label: t('上限') }] },
  { group: 'system', title: t('已加载类'), unit: 'count', series: [{ key: 'jvm.classes.loaded', label: t('类') }] }
]
// Hikari's acquire timer is stored as <pool>.count and <pool>.time per minute; its chart shows time / count.
const ACQUIRE_KEY = 'hikaricp.connections.acquire:'
const KNOWN_KEYS = new Set(['jvm.memory.used:', 'jvm.memory.committed:', 'jvm.memory.max:', 'jvm.gc.pause.count',
  'jvm.gc.pause.time', ACQUIRE_KEY,
  'jvm.threads.peak', ...RESOURCE_CHARTS.flatMap(chart => chart.series.map(item => item.key))])
/** Sections of the page in display order; collapsed by default unless {@code open}. The choice is remembered per browser. */
const GROUPS = [
  { id: 'cpu-memory', title: t('CPU 与内存'), note: t('CPU 使用率、系统负载、堆和非堆内存'), open: true },
  { id: 'gc', title: t('GC 与内存分配'), note: t('GC 次数、停顿时间和分配速率'), open: true },
  { id: 'threads', title: t('线程与线程池'), note: t('JVM 和 Web 容器线程、应用线程池') },
  { id: 'database', title: t('数据库连接池'), note: t('活跃、空闲、等待连接、获取连接耗时和超时') },
  { id: 'system', title: t('Web 与系统'), note: t('HTTP 会话、文件句柄、已加载类') },
  { id: 'custom', title: t('自定义指标'), note: t('通过 meerkat.metrics.include 追加') }
]
const OPEN_STATE_KEY = 'meerkat:metrics:open-groups'
let charts = []
let timer
const sections = new Map()

function storedOpenGroups() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(OPEN_STATE_KEY) || 'null')
    return Array.isArray(stored) ? new Set(stored) : null
  } catch { return null }
}

function rememberOpenGroups() {
  const open = [...sections].filter(([, section]) => section.details.open).map(([id]) => id)
  try { window.localStorage.setItem(OPEN_STATE_KEY, JSON.stringify(open)) } catch { /* storage unavailable: keep in memory only */ }
}

function buildSections() {
  const stored = storedOpenGroups()
  for (const group of GROUPS) {
    const details = document.createElement('details')
    details.className = 'metric-group'
    details.open = stored ? stored.has(group.id) : Boolean(group.open)
    const summary = document.createElement('summary')
    const title = document.createElement('span')
    title.className = 'metric-group-title'
    title.textContent = group.title
    const note = document.createElement('span')
    note.className = 'metric-group-note'
    note.textContent = group.note
    const count = document.createElement('span')
    count.className = 'metric-group-count'
    summary.append(title, note, count)
    const grid = document.createElement('div')
    grid.className = 'metric-grid'
    details.append(summary, grid)
    details.addEventListener('toggle', rememberOpenGroups)
    $('metric-groups').append(details)
    sections.set(group.id, { details, grid, count })
  }
}

function fillSection(id, list) {
  const section = sections.get(id)
  section.grid.replaceChildren(...list.map(chart => chart.card))
  section.count.textContent = t('{n} 个图表', { n: list.length })
  section.details.hidden = list.length === 0
}

function setAllOpen(open) {
  for (const section of sections.values()) section.details.open = open
  rememberOpenGroups()
}

function matches(spec, key) {
  return spec.key.endsWith(':') ? key.startsWith(spec.key) : key === spec.key
}

function known(key) {
  for (const item of KNOWN_KEYS) {
    if (item.endsWith(':') ? key.startsWith(item) : key === item) return true
  }
  return false
}

function acquireWaitChart(resources, keys) {
  const series = []
  for (const countKey of keys.filter(key => key.startsWith(ACQUIRE_KEY) && key.endsWith('.count'))) {
    const pool = countKey.slice(ACQUIRE_KEY.length, -'.count'.length)
    const counts = resources.series[countKey]
    const times = resources.series[`${ACQUIRE_KEY}${pool}.time`]
    if (!times) continue
    series.push({ label: pool, values: counts.map((count, index) => count > 0 && times[index] != null ? times[index] / count : null) })
  }
  if (!series.length) return null
  return { group: 'database', ...lineChart({ title: t('获取连接平均等待'), unit: 'ms', hint: t('持续升高说明连接不够用'),
    timestamps: resources.timestamps, stepMillis: resources.stepMillis, series }) }
}

function resourceCharts(resources) {
  const keys = Object.keys(resources.series)
  const result = []
  for (const chart of RESOURCE_CHARTS) {
    const series = []
    for (const spec of chart.series) {
      for (const key of keys.filter(item => matches(spec, item))) {
        const group = spec.key.endsWith(':') ? key.slice(spec.key.length) : ''
        series.push({
          label: group ? `${group} ${spec.label}` : spec.label,
          values: resources.series[key].map(value => value == null ? null : value * (spec.scale || 1))
        })
      }
    }
    if (series.length) {
      const rendered = lineChart({ ...chart, timestamps: resources.timestamps, stepMillis: resources.stepMillis, series })
      result.push({ ...rendered, group: chart.group })
    }
  }
  const acquire = acquireWaitChart(resources, keys)
  if (acquire) result.push(acquire)
  // Meters added through meerkat.metrics.include get one chart per meter name.
  const custom = new Map()
  for (const key of keys.filter(item => !known(item))) {
    const meter = key.replace(/:.*$/, '').replace(/\.(count|time|total)$/, '')
    if (!custom.has(meter)) custom.set(meter, [])
    custom.get(meter).push(key)
  }
  for (const [meter, meterKeys] of custom) {
    result.push({ group: 'custom', ...lineChart({ title: meter, unit: 'count', timestamps: resources.timestamps,
      stepMillis: resources.stepMillis,
      series: meterKeys.map(key => ({ label: key.slice(meter.length).replace(/^[.:]/, '') || meter, values: resources.series[key] })) }) })
  }
  return result
}

function storageText(storage) {
  if (!storage.persistent) return t('历史存储不可用，只显示内存中最近 2 小时')
  return t('历史存储 {used} / {max} · 保留 {days} 天', { used: formatValue(storage.storageBytes, 'bytes'),
    max: formatValue(storage.maxStorageBytes, 'bytes'), days: storage.retentionDays })
}

async function refresh() {
  const period = $('period').value
  try {
    const [resources, storage] = await Promise.all([
      request(`metrics/resources?period=${period}`),
      request('metrics/storage')
    ])
    for (const chart of charts) chart.destroy()
    const resourceList = resourceCharts(resources)
    charts = resourceList
    for (const group of GROUPS) {
      fillSection(group.id, resourceList.filter(chart => chart.group === group.id))
    }
    $('empty').hidden = resourceList.length > 0
    $('empty').textContent = t('还没有资源指标：指标每分钟采样一次，请稍后刷新')
    $('storage').textContent = storageText(storage)
    $('instance').classList.toggle('degraded', !storage.persistent)
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = t(storage.persistent ? '已连接' : '存储降级')
    $('notice').hidden = true
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = t(error.message === DISABLED_MESSAGE ? '未启用' : '连接失败')
    $('notice').textContent = error.message
    $('notice').hidden = false
    if (error.message === DISABLED_MESSAGE) window.clearInterval(timer)
  }
}

const requested = new URLSearchParams(window.location.search).get('period')
if (PERIODS.has(requested)) $('period').value = requested
$('period').addEventListener('change', () => {
  const url = new URL(window.location.href)
  url.searchParams.set('period', $('period').value)
  window.history.replaceState(null, '', url)
  refresh()
})
$('refresh').addEventListener('click', refresh)
$('expand-all').addEventListener('click', () => setAllOpen(true))
$('collapse-all').addEventListener('click', () => setAllOpen(false))
buildSections()
await initializeEmbeddedAuth()
await refresh()
timer = window.setInterval(refresh, REFRESH_MS)
window.addEventListener('beforeunload', () => window.clearInterval(timer), { once: true })
