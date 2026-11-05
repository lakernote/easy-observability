import {
  $, DISABLED_MESSAGE, categoryNames, countsSince, formatValue, initializeEmbeddedAuth, lineChart, request
} from './metrics-common.js?v=20261106.1'

const PERIODS = new Set(['1h', '24h', '7d', '30d'])
const REFRESH_MS = 60000
/** Each chart lists series keys; a key ending in ':' matches every pool, executor or area of that meter. */
const RESOURCE_CHARTS = [
  { title: 'CPU 使用率', unit: 'percent', series: [
    { key: 'process.cpu.usage', label: '进程', scale: 100 }, { key: 'system.cpu.usage', label: '系统', scale: 100 }] },
  { title: '系统负载（1 分钟）', unit: 'count', series: [{ key: 'system.load.average.1m', label: '负载' }] },
  { title: '堆内存', unit: 'bytes', series: [
    { key: 'jvm.memory.used:heap', label: '已用' }, { key: 'jvm.memory.committed:heap', label: '已提交' },
    { key: 'jvm.memory.max:heap', label: '上限' }] },
  { title: '非堆内存', unit: 'bytes', series: [
    { key: 'jvm.memory.used:nonheap', label: '已用' }, { key: 'jvm.memory.committed:nonheap', label: '已提交' }] },
  { title: 'GC 次数（每分钟）', unit: 'count', series: [{ key: 'jvm.gc.pause.count', label: 'GC 次数' }] },
  { title: 'GC 停顿时间（每分钟）', unit: 'ms', series: [{ key: 'jvm.gc.pause.time', label: '停顿时间' }] },
  { title: '内存分配速率（每分钟）', unit: 'bytes', series: [{ key: 'jvm.gc.memory.allocated', label: '分配量' }] },
  { title: '线程', unit: 'count', series: [
    { key: 'jvm.threads.live', label: 'JVM 活跃' }, { key: 'jvm.threads.daemon', label: 'JVM 守护' },
    { key: 'tomcat.threads.busy', label: 'Tomcat 忙碌' }, { key: 'tomcat.threads.current', label: 'Tomcat 当前' },
    { key: 'tomcat.threads.config.max', label: 'Tomcat 上限' }, { key: 'jetty.threads.busy', label: 'Jetty 忙碌' },
    { key: 'jetty.threads.current', label: 'Jetty 当前' }, { key: 'jetty.threads.config.max', label: 'Jetty 上限' }] },
  { title: '数据库连接池', unit: 'count', series: [
    { key: 'hikaricp.connections.active:', label: '活跃' }, { key: 'hikaricp.connections.idle:', label: '空闲' },
    { key: 'hikaricp.connections.pending:', label: '等待' }, { key: 'hikaricp.connections.max:', label: '上限' }] },
  { title: '获取连接超时（每分钟）', unit: 'count', series: [{ key: 'hikaricp.connections.timeout:', label: '超时' }] },
  { title: '线程池', unit: 'count', series: [
    { key: 'executor.active:', label: '活跃' }, { key: 'executor.queued:', label: '排队' },
    { key: 'executor.pool.size:', label: '线程数' }, { key: 'executor.pool.max:', label: '上限' }] },
  { title: '线程池完成任务（每分钟）', unit: 'count', series: [{ key: 'executor.completed:', label: '完成' }] },
  { title: 'HTTP 会话', unit: 'count', series: [{ key: 'tomcat.sessions.active.current', label: '活跃会话' }] },
  { title: '文件句柄', unit: 'count', series: [
    { key: 'process.files.open', label: '已打开' }, { key: 'process.files.max', label: '上限' }] },
  { title: '已加载类', unit: 'count', series: [{ key: 'jvm.classes.loaded', label: '类' }] }
]
const KNOWN_KEYS = new Set(['jvm.memory.used:', 'jvm.memory.committed:', 'jvm.memory.max:', 'jvm.gc.pause.count',
  'jvm.gc.pause.time', 'hikaricp.connections.acquire.count:', 'hikaricp.connections.acquire.time:',
  'jvm.threads.peak', ...RESOURCE_CHARTS.flatMap(chart => chart.series.map(item => item.key))])
let charts = []
let timer

function matches(spec, key) {
  return spec.key.endsWith(':') ? key.startsWith(spec.key) : key === spec.key
}

function known(key) {
  for (const item of KNOWN_KEYS) {
    if (item.endsWith(':') ? key.startsWith(item) : key === item) return true
  }
  return false
}

function operationCharts(trends) {
  const entries = Object.entries(trends).filter(([, trend]) => trend.points.some(point => point.count > 0))
  const first = Object.values(trends)[0]
  if (!first) return []
  const timestamps = first.points.map(point => point.timestamp)
  const step = first.stepMillis
  const series = (pick, from = entries) => from.map(([category, trend]) => ({
    label: categoryNames[category] || category, values: pick(trend)
  }))
  const failing = entries.filter(([, trend]) => trend.points.some(point => point.errors > 0))
  const averages = pick => trend => trend.points.map(pick)
  const result = [
    lineChart({ title: '调用次数', unit: 'count', timestamps, stepMillis: step,
      series: series(trend => countsSince(trend, point => point.count)) }),
    lineChart({ title: '错误次数', unit: 'count', timestamps, stepMillis: step, empty: '所选时间范围内没有错误',
      series: series(trend => countsSince(trend, point => point.errors), failing) }),
    lineChart({ title: '平均耗时', unit: 'ms', timestamps, stepMillis: step, series: series(averages(point => point.meanMs)) })
  ]
  if (first.period !== 'HOUR') {
    result.push(lineChart({ title: 'P95 耗时', unit: 'ms', hint: '由直方图估算，误差约 ±20%', timestamps,
      stepMillis: step, series: series(averages(point => point.p95Ms)) }))
  }
  return result
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
    if (series.length) result.push(lineChart({ ...chart, timestamps: resources.timestamps, stepMillis: resources.stepMillis, series }))
  }
  // Meters added through easy.observability.metrics.include get one chart per meter name.
  const custom = new Map()
  for (const key of keys.filter(item => !known(item))) {
    const meter = key.replace(/:.*$/, '').replace(/\.(count|time|total)$/, '')
    if (!custom.has(meter)) custom.set(meter, [])
    custom.get(meter).push(key)
  }
  for (const [meter, meterKeys] of custom) {
    result.push(lineChart({ title: meter, hint: '自定义指标', unit: 'count', timestamps: resources.timestamps,
      stepMillis: resources.stepMillis,
      series: meterKeys.map(key => ({ label: key.slice(meter.length).replace(/^[.:]/, '') || meter, values: resources.series[key] })) }))
  }
  return result
}

function storageText(storage) {
  if (!storage.persistent) return '历史存储不可用，只显示内存中最近 2 小时'
  return `历史存储 ${formatValue(storage.storageBytes, 'bytes')} / ${formatValue(storage.maxStorageBytes, 'bytes')} · 保留 ${storage.retentionDays} 天`
}

async function refresh() {
  const period = $('period').value
  try {
    const [trends, resources, storage] = await Promise.all([
      request(`metrics/operations/trends?period=${period}`),
      request(`metrics/resources?period=${period}`),
      request('metrics/storage')
    ])
    for (const chart of charts) chart.destroy()
    const operations = operationCharts(trends)
    const resourceList = resourceCharts(resources)
    charts = [...operations, ...resourceList]
    $('operation-grid').replaceChildren(...operations.map(chart => chart.card))
    $('resource-grid').replaceChildren(...resourceList.map(chart => chart.card))
    $('empty').hidden = resourceList.length > 0
    $('empty').textContent = '还没有资源指标：指标每分钟采样一次，请稍后刷新'
    $('storage').textContent = storageText(storage)
    $('instance').classList.toggle('degraded', !storage.persistent)
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = storage.persistent ? '已连接' : '存储降级'
    $('notice').hidden = true
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = error.message === DISABLED_MESSAGE ? '未启用' : '连接失败'
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
await initializeEmbeddedAuth()
await refresh()
timer = window.setInterval(refresh, REFRESH_MS)
window.addEventListener('beforeunload', () => window.clearInterval(timer), { once: true })
