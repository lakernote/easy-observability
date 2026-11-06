// Loaded before every page script: picks the dashboard language and exposes t(), which maps the Chinese source text
// to English. Pages keep Chinese in their markup and scripts; this file translates the static markup at startup.
(() => {
  const STORAGE_KEY = 'meerkat.lang'
  // Keys are the exact Chinese source text; "context|text" keys disambiguate the same text used in different places.
  const EN = {
    // Shared: navigation, connection state and errors
    'Trace 诊断': 'Traces',
    '运行指标': 'Runtime metrics',
    '实时诊断': 'Live',
    '调用排行': 'Operations',
    '指标趋势': 'Metrics',
    'Meerkat · 调用排行': 'Meerkat · Operations',
    'Meerkat · 指标趋势': 'Meerkat · Metrics',
    'Meerkat · 实时诊断': 'Meerkat · Live',
    '跳到主要内容': 'Skip to main content',
    '可观测性视图': 'Observability views',
    '运行指标视图': 'Runtime metrics views',
    '实时诊断视图': 'Live views',
    '切换语言': 'Switch language',
    '连接中': 'Connecting',
    '已连接': 'Connected',
    '连接失败': 'Connection failed',
    '未启用': 'Disabled',
    '存储降级': 'Storage degraded',
    '刷新': 'Refresh',
    '读取中': 'Loading',
    '取消': 'Cancel',
    '关闭': 'Close',
    '表格可横向滚动查看全部字段': 'Scroll the table sideways to see all columns',
    '没有查看的权限，请登录或检查 ObservabilityAccessPolicy': 'Access denied. Sign in or check ObservabilityAccessPolicy',
    '请求失败（HTTP {status}）': 'Request failed (HTTP {status})',
    '{message}。请检查应用权限和服务状态。': '{message}. Check the application permissions and service status.',
    '{label}：{value}': '{label}: {value}',
    '{label}：': '{label}: ',
    '，': ', ',
    '、': ', ',
    // Span kinds and entry sources
    'HTTP 接口': 'HTTP endpoints',
    '远程调用': 'Remote call',
    '事务': 'Transaction',
    '定时任务': 'Scheduled job',
    '服务': 'Service',
    '代码': 'Code',
    '其他': 'Other',
    '调用': 'Call',
    'Kafka 消费': 'Kafka consumer',
    'Kafka 发送': 'Kafka producer',
    // Traces page
    '只记录慢、出错或资源超阈值的入口（HTTP、定时任务、Kafka 消费）。打开一条记录，沿调用时间线定位根因。':
      'Only entries (HTTP, scheduled jobs, Kafka consumers) that are slow, fail or exceed a resource threshold are recorded. Open one and follow its call timeline to the root cause.',
    'Trace 筛选': 'Trace filters',
    '结果': 'Result',
    '全部': 'All',
    '慢调用': 'Slow',
    '错误': 'Error',
    '资源告警': 'Resource alert',
    '入口': 'Entry',
    '全部来源': 'All sources',
    '搜索入口、Trace ID、SQL、调用节点或错误': 'Search entries, Trace IDs, SQL, spans or errors',
    '例如 /api/orders 或 SQLException': 'e.g. /api/orders or SQLException',
    '慢调用、资源告警和错误 Trace 列表': 'Slow, resource-alert and error traces',
    '慢调用、资源告警和错误 Trace；选择记录可查看入口线程 CPU 时间、内存分配量与调用时间线':
      'Slow, resource-alert and error traces; select one to see the entry thread CPU time, allocation and call timeline',
    '完成时间': 'Finished',
    '总耗时': 'Total time',
    'CPU 时间': 'CPU time',
    '内存分配量': 'Allocated',
    '入口线程在本次入口执行期间占用 CPU 的累计时间，不含子线程':
      'CPU time used by the entry thread while handling this entry, excluding child threads',
    '入口线程在本次入口执行期间累计分配的对象大小，不是当前堆占用':
      'Bytes allocated by the entry thread while handling this entry; not current heap usage',
    '暂无诊断 Trace；正常请求不会显示在此列表中': 'No diagnostic traces yet. Normal requests are not listed here.',
    '没有符合当前筛选条件的 Trace': 'No traces match the current filters',
    '已搜索的范围内没有匹配的 Trace，点击“下一页”继续搜索更早的记录':
      'No matching traces in the searched range. Click "Next" to search older records.',
    '上一页': 'Previous',
    '下一页': 'Next',
    '继续搜索': 'Keep searching',
    '第 {page} / {pages} 页': 'Page {page} of {pages}',
    '第 {page} 页': 'Page {page}',
    '{n} 条诊断 Trace': '{n} diagnostic traces',
    '本页 {n} 条匹配': '{n} matches on this page',
    '清空历史': 'Clear history',
    'Trace 历史来自当前应用实例。Spring Boot Actuator 指标仍由应用自身配置管理。':
      'Trace history comes from this application instance. Spring Boot Actuator metrics are still managed by the application\'s own configuration.',
    '复制 Trace ID': 'Copy Trace ID',
    '复制': 'Copy',
    '已复制': 'Copied',
    '复制失败': 'Copy failed',
    '调用详情': 'Call details',
    '入口和入口线程资源': 'Entry and entry thread resources',
    '异常': 'Exception',
    '查看异常堆栈': 'Show stack trace',
    '调用时间线': 'Call timeline',
    '按调用关系排列 · 连续重复的调用合并为一行 · 横条表示开始时间和持续时间':
      'Ordered by call hierarchy · consecutive repeats merged into one row · bars show start time and duration',
    '端到端调用时间线': 'End-to-end call timeline',
    '清空 Trace 历史？': 'Clear trace history?',
    '将删除当前实例内存中的近期记录和诊断 Trace 归档文件。':
      'This deletes the recent records held in this instance\'s memory and the archived diagnostic trace files.',
    '清空': 'Clear',
    '没有查看 Trace 的权限，请登录或检查 ObservabilityAccessPolicy': 'No permission to view traces. Sign in or check ObservabilityAccessPolicy',
    '没有清空历史的权限，请检查 ObservabilityAccessPolicy#canClear 或 CSRF 配置':
      'No permission to clear history. Check ObservabilityAccessPolicy#canClear or the CSRF setup',
    '这条 Trace 已不在保留范围内': 'This trace is no longer retained',
    '刚刚': 'just now',
    '{n} 秒前': '{n}s ago',
    '{n} 分钟前': '{n} min ago',
    '{n} 小时前': '{n} h ago',
    '{n} 天前': '{n} d ago',
    '未采集': 'Not collected',
    '线程 {id}': 'Thread {id}',
    '入口线程': 'Entry thread',
    '子线程': 'Child thread',
    '子线程 · {name}': 'Child thread · {name}',
    '慢': 'Slow',
    'CPU 高': 'High CPU',
    '分配高': 'High allocation',
    '子任务未完成': 'Unfinished tasks',
    '{n} 个子调用在入口请求结束时仍在运行': '{n} child calls were still running when the entry request ended',
    '查看 {name} 的诊断 Trace 详情': 'View diagnostic trace details for {name}',
    '选择一个调用节点查看详情': 'Select a span to see its details',
    '没有可展示的调用节点': 'No spans to show',
    '正在读取调用树…': 'Loading call tree…',
    '线程编号': 'Thread keys',
    '未命名': 'Unnamed',
    '未命名调用': 'Unnamed span',
    '重复 {count} 次，合计 {ms} 毫秒': 'repeated {count} times, {ms} ms in total',
    '{ms} 毫秒': '{ms} ms',
    '请求结束时仍在运行': 'Still running when the request ended',
    '请求结束时仍运行': 'Running at request end',
    '连续重复 {count} 次，已合并显示': 'Repeated {count} times in a row, merged into one row',
    '{offset} ms 起 {count} 次调用，跨度 {duration} ms': '{count} calls from {offset} ms, spanning {duration} ms',
    '{offset} ms 开始 · {duration} ms 持续': 'Starts at {offset} ms · lasts {duration} ms',
    '重复调用的合计耗时': 'Total time of the repeated calls',
    '次数': 'Count',
    '{count} 次': '{count} times',
    '合计': 'Total',
    '平均': 'Avg',
    '最大': 'Max',
    '最短 / 最长': 'Min / Max',
    '首次开始': 'First start',
    '耗时': 'Duration',
    '开始偏移': 'Start offset',
    '类型': 'Type',
    '状态': 'Status',
    '已完成': 'Completed',
    '正常': 'Normal',
    '请求结束时仍在运行（时长按入口结束截取）': 'Still running when the request ended (duration cut at the entry end)',
    '同一调用在循环中连续执行了 {count} 次。如果是逐条查询，可以考虑改为一次批量查询（N+1 问题）。':
      'The same call ran {count} times in a row inside a loop. If it fetches one row at a time, consider a single batch query (N+1 problem).',
    'CPU 时间超阈值': 'CPU time over threshold',
    '内存分配量超阈值': 'Allocation over threshold',
    '入口线程在本次入口执行期间实际占用 CPU 的累计时间；不是 CPU 使用率，也不包含子线程':
      'CPU time actually used by the entry thread while handling this entry; not CPU usage, and excludes child threads',
    '入口线程在本次入口执行期间累计分配的对象大小；不是当前堆占用，也不包含子线程或远程服务':
      'Bytes allocated by the entry thread while handling this entry; not current heap usage, and excludes child threads and remote services',
    '有错误标记，未捕获到异常堆栈。': 'Marked as failed, but no exception stack was captured.',
    '当前异常没有可用的堆栈。': 'No stack trace is available for this exception.',
    // Operations page
    '按 HTTP 接口、SQL、远程调用、Redis 命令、定时任务和 Kafka 汇总每个操作的次数、耗时和错误，快速找出最耗时、最慢和最常出错的地方。点击一行可展开它的次数、错误和耗时趋势。':
      'Count, latency and errors of every operation across HTTP endpoints, SQL, remote calls, Redis commands, scheduled jobs and Kafka, to quickly find what costs the most time, is slowest or fails most often. Click a row to expand its count, error and latency trends.',
    '统计筛选': 'Stats filters',
    '时间范围': 'Time range',
    '最近 1 小时': 'Last hour',
    '今天': 'Today',
    '最近 24 小时': 'Last 24 hours',
    '最近 7 天': 'Last 7 days',
    '最近 30 天': 'Last 30 days',
    '搜索名称': 'Search names',
    '例如 /api/orders 或 select': 'e.g. /api/orders or select',
    '统计类别': 'Categories',
    '操作统计排行': 'Operation ranking',
    '按所选列排序的操作统计；选择一行展开它的趋势': 'Operation stats sorted by the selected column; select a row to expand its trend',
    '重置统计': 'Reset stats',
    '每个实例各自统计，每 10 分钟保存到存储目录（metrics/），重启后各时间范围都保留，保留天数由 meerkat.metrics.retention 决定。“最近 1 小时”按 10 分钟分片滚动，实际覆盖 50 到 60 分钟；重启前的部分在分钟曲线上按 10 分钟均摊。P95 由对数分桶直方图估算，误差约 ±20%。SQL 已去除字面量，IN 列表和多行 VALUES 会合并为同一条。':
      'Each instance keeps its own stats and saves them to the storage directory (metrics/) every 10 minutes, so every time range survives restarts; retention is set by meerkat.metrics.retention. "Last hour" rolls in 10-minute slices and covers 50 to 60 minutes; data from before a restart is spread evenly over 10 minutes on the per-minute chart. P95 is estimated from a log-bucketed histogram with about ±20% error. SQL literals are stripped, and IN lists and multi-row VALUES are merged into one statement.',
    '重置调用统计？': 'Reset operation stats?',
    '将清空当前实例的全部调用统计，包括已保存的历史；Trace 历史和资源指标不受影响。':
      'This clears all operation stats of this instance, including saved history. Trace history and resource metrics are not affected.',
    '重置': 'Reset',
    '调用统计来自 Trace 采集点，当前 meerkat.trace.enabled=false，不会记录新的调用':
      'Operation stats come from trace instrumentation; meerkat.trace.enabled=false, so no new calls are recorded',
    '（其他：超出统计数量上限）': '(Other: over the tracked-operation limit)',
    '名称': 'Name',
    '由直方图估算，误差约 ±20%': 'Estimated from a histogram, about ±20% error',
    '耗时占比': 'Time share',
    '该操作总耗时占本类别总耗时的比例': 'This operation\'s share of the total time in its category',
    '平均 CPU': 'Avg CPU',
    '入口线程每次调用消耗的 CPU 时间；异步请求不统计': 'CPU time used by the entry thread per call; async requests are excluded',
    '平均内存分配': 'Avg allocation',
    '入口线程每次调用分配的堆内存（不是占用量）；异步请求不统计':
      'Heap allocated by the entry thread per call (not memory in use); async requests are excluded',
    '查看相关的慢 / 错误 Trace →': 'View related slow / error traces →',
    '读取趋势中…': 'Loading trend…',
    '调用次数': 'Calls',
    '按分钟统计时不计算 P95': 'P95 is not computed for per-minute buckets',
    'P95 由直方图估算，误差约 ±20%': 'P95 is estimated from a histogram, about ±20% error',
    '{count}（{percent}）': '{count} ({percent})',
    '收起趋势': 'Hide trend',
    '查看趋势': 'Show trend',
    '没有名称匹配的操作': 'No operations match this name',
    '所选时间范围内还没有{category}调用': 'Nothing recorded for {category} in the selected time range',
    '自 {time} 起': 'Since {time}',
    '，已达上限，新操作计入“其他”': '; limit reached, new operations count as "Other"',
    ' · 存储目录不可用，只含内存中最近 2 小时': ' · Storage directory unavailable; only the last 2 hours in memory',
    '内存中 {keys} 个操作（上限 {max}{limit}）': '{keys} operations in memory (limit {max}{limit})',
    '没有重置统计的权限，请检查 ObservabilityAccessPolicy#canClear 或 CSRF 配置':
      'No permission to reset stats. Check ObservabilityAccessPolicy#canClear or the CSRF setup',
    // Metrics page and charts
    '查看 JVM 内存、GC、CPU、线程、连接池和线程池等资源指标的走势；单个接口、SQL 的调用量和耗时趋势在“调用排行”中点开一行查看。资源指标直接读取 Spring Boot 已经配置好的 Micrometer 指标，不改变应用原有的指标导出。':
      'Trends of resource metrics such as JVM memory, GC, CPU, threads, connection pools and thread pools. For the call volume and latency of a single endpoint or SQL statement, open its row in "Operations". Resource metrics read the Micrometer meters Spring Boot already configures and do not change the application\'s existing metric export.',
    '资源指标是每分钟采样，较长范围取平均；GC 次数和停顿时间是每分钟的量。点击图例可开关曲线，点击分组标题可展开或收起。':
      'Resource metrics are sampled every minute and averaged over longer ranges; GC count and pause time are per minute. Click a legend item to toggle a series, or a group title to expand or collapse it.',
    '指标趋势筛选': 'Metric filters',
    '历史存储：读取中': 'History storage: loading',
    '全部展开': 'Expand all',
    '全部收起': 'Collapse all',
    '每个实例各自保存历史，位于存储目录的 metrics/ 下：最近 2 天保留细粒度数据，更早的按小时（调用）或 10 分钟（资源）合并；保留天数和容量上限由 meerkat.metrics.retention 和 max-storage-size 决定。可用 meerkat.metrics.include 追加自定义 Micrometer 指标。':
      'Each instance keeps its own history under metrics/ in the storage directory: fine-grained data for the last 2 days, older data merged per hour (calls) or per 10 minutes (resources). Retention and the size cap are set by meerkat.metrics.retention and max-storage-size. Add custom Micrometer meters with meerkat.metrics.include.',
    '运行指标未启用：请确认 meerkat.metrics.enabled 为 true': 'Runtime metrics are disabled: make sure meerkat.metrics.enabled is true',
    '没有查看运行指标的权限，请登录或检查 ObservabilityAccessPolicy': 'No permission to view runtime metrics. Sign in or check ObservabilityAccessPolicy',
    '每小时': 'per hour',
    '每 10 分钟': 'per 10 minutes',
    '每分钟': 'per minute',
    '{step}一个点 · 点击图例开关曲线': 'One point {step} · Click the legend to toggle series',
    '所选时间范围内暂无数据': 'No data in the selected time range',
    '图表组件加载失败': 'Chart library failed to load',
    '{title}趋势图': '{title} trend chart',
    '时间': 'Time',
    '最新值': 'Latest',
    'CPU 使用率': 'CPU usage',
    '进程': 'Process',
    '系统': 'System',
    '系统负载（1 分钟）': 'System load (1 min)',
    '负载': 'Load',
    '堆内存': 'Heap memory',
    '非堆内存': 'Non-heap memory',
    '已用': 'Used',
    '已提交': 'Committed',
    '上限': 'Max',
    'GC 次数（每分钟）': 'GC count (per minute)',
    'GC 次数': 'GC count',
    'GC 停顿时间（每分钟）': 'GC pause time (per minute)',
    '停顿时间': 'Pause time',
    '内存分配速率（每分钟）': 'Allocation rate (per minute)',
    '分配量': 'Allocated',
    'JVM 活跃': 'JVM live',
    'JVM 守护': 'JVM daemon',
    'Tomcat 忙碌': 'Tomcat busy',
    'Tomcat 当前': 'Tomcat current',
    'Tomcat 上限': 'Tomcat max',
    'Jetty 忙碌': 'Jetty busy',
    'Jetty 当前': 'Jetty current',
    'Jetty 上限': 'Jetty max',
    '数据库连接池': 'Database connection pool',
    '活跃': 'Active',
    '空闲': 'Idle',
    '等待': 'Pending',
    '获取连接超时（每分钟）': 'Connection acquire timeouts (per minute)',
    '超时': 'Timeouts',
    '线程池': 'Thread pools',
    '排队': 'Queued',
    'pool|线程数': 'Pool size',
    '线程池完成任务（每分钟）': 'Thread pool completed tasks (per minute)',
    '完成': 'Completed',
    'HTTP 会话': 'HTTP sessions',
    '活跃会话': 'Active sessions',
    '文件句柄': 'File descriptors',
    '已打开': 'Open',
    '已加载类': 'Loaded classes',
    '类': 'Classes',
    'CPU 与内存': 'CPU & memory',
    'CPU 使用率、系统负载、堆和非堆内存': 'CPU usage, system load, heap and non-heap memory',
    'GC 与内存分配': 'GC & allocation',
    'GC 次数、停顿时间和分配速率': 'GC count, pause time and allocation rate',
    '线程与线程池': 'Threads & thread pools',
    'JVM 和 Web 容器线程、应用线程池': 'JVM and web container threads, application thread pools',
    '活跃、空闲、等待连接、获取连接耗时和超时': 'Active, idle and pending connections, acquire time and timeouts',
    'Web 与系统': 'Web & system',
    'HTTP 会话、文件句柄、已加载类': 'HTTP sessions, file descriptors, loaded classes',
    '自定义指标': 'Custom metrics',
    '通过 meerkat.metrics.include 追加': 'Added via meerkat.metrics.include',
    '{n} 个图表': '{n} charts',
    '获取连接平均等待': 'Avg connection acquire time',
    '持续升高说明连接不够用': 'A steady rise means the pool is too small',
    '历史存储不可用，只显示内存中最近 2 小时': 'History storage unavailable; showing only the last 2 hours in memory',
    '历史存储 {used} / {max} · 保留 {days} 天': 'History storage {used} / {max} · kept {days} days',
    '还没有资源指标：指标每分钟采样一次，请稍后刷新': 'No resource metrics yet: they are sampled every minute, so refresh shortly',
    // Live page
    '查看此刻正在执行的请求和线程：哪个请求卡住了、卡在哪一步，哪个线程在消耗 CPU，有没有死锁。数据在打开页面或点击刷新时读取，不在后台采样，也不保存。':
      'See the requests and threads running right now: which request is stuck and at which step, which thread is burning CPU, and whether there is a deadlock. Data is read when the page opens or you click Refresh; nothing is sampled in the background or stored.',
    '进行中请求': 'In-flight requests',
    '线程': 'Threads',
    'one|线程': 'Thread',
    '请求筛选': 'Request filters',
    '已执行': 'Elapsed',
    '≥ 1 秒': '≥ 1 s',
    '≥ 5 秒': '≥ 5 s',
    '≥ 30 秒': '≥ 30 s',
    '每 5 秒自动刷新': 'Auto-refresh every 5 s',
    '正在执行的入口请求，按已执行时间排序；选择一行查看线程堆栈': 'Running entry requests sorted by elapsed time; select a row to see the thread stack',
    '当前步骤': 'Current step',
    '当前没有正在执行的请求': 'No requests are running',
    '线程筛选': 'Thread filters',
    '全部状态': 'All states',
    '搜索线程名或堆栈': 'Search thread names or stacks',
    '例如 http-nio 或 com.example': 'e.g. http-nio or com.example',
    '合并相同堆栈': 'Merge identical stacks',
    '下载线程 Dump': 'Download thread dump',
    '线程列表，按采样期间的 CPU 使用率排序；选择一行查看堆栈': 'Threads sorted by CPU usage during sampling; select a row to see its stack',
    '没有匹配的线程': 'No matching threads',
    '进行中请求只包含 HTTP、定时任务和 Kafka 消费入口；异步请求在入口线程返回后不再显示线程和堆栈。线程 CPU 使用率为 1 秒采样内占用单个核心的比例，100% 表示占满一个核心。虚拟线程不在线程列表中。':
      'In-flight requests cover HTTP, scheduled job and Kafka consumer entries only; async requests no longer show a thread or stack once the entry thread returns. Thread CPU usage is the share of one core used during the 1-second sample, so 100% means one full core. Virtual threads are not listed.',
    '没有查看实时诊断的权限，请登录或检查 ObservabilityAccessPolicy': 'No permission to view live diagnostics. Sign in or check ObservabilityAccessPolicy',
    '异步': 'Async',
    '没有可用的堆栈': 'No stack available',
    '{n} 个进行中': '{n} in flight',
    '，显示最久的 {n} 个': ', showing the {n} longest',
    '入口线程已返回': 'Entry thread returned',
    '开始': 'Started',
    '路径': 'Path',
    '已记录节点': 'Recorded spans',
    '内存分配': 'Allocated',
    '等待锁': 'Waiting on lock',
    '（持有者 {owner}）': ' (held by {owner})',
    '当前步骤 [{kind}] {name}，已执行 {elapsed}': 'Current step [{kind}] {name}, running for {elapsed}',
    '请求已转为异步处理，入口线程已返回，没有对应的线程堆栈':
      'The request switched to async processing and its entry thread has returned, so there is no thread stack',
    '正在采样 1 秒…': 'Sampling for 1 s…',
    '死锁': 'Deadlock',
    '只看 {state} 线程': 'Show only {state} threads',
    '显示全部线程': 'Show all threads',
    '{name} 等待 {lock}，持有者 {owner}': '{name} waits for {lock}, held by {owner}',
    '检测到 {n} 个线程死锁': 'Deadlock detected: {n} threads',
    '线程数': 'Count',
    '栈顶': 'Top frame',
    '累计 CPU': 'Total CPU',
    '锁': 'Lock',
    '，持有者 {owner}': ', held by {owner}',
    ' 等 {n} 个': ' and others ({n} total)',
    '线程 Dump 刚刚生成过或正在生成，请 5 秒后再试': 'A thread dump was just taken or is in progress. Try again in 5 seconds',
    '下载失败（HTTP {status}）': 'Download failed (HTTP {status})',
    '另一个线程采样正在进行，请稍后再试': 'Another thread sample is in progress. Try again shortly',
    '重新采样': 'Resample'
  }
  const TRANSLATED_ATTRIBUTES = ['title', 'placeholder', 'aria-label', 'alt']

  function detectLanguage() {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY)
      if (stored === 'zh' || stored === 'en') return stored
    } catch { /* storage unavailable: fall back to the browser language */ }
    return String(navigator.language || '').toLowerCase().startsWith('zh') ? 'zh' : 'en'
  }

  const lang = detectLanguage()
  const english = lang === 'en'

  function format(text, params) {
    if (!params) return text
    return text.replace(/\{(\w+)\}/g, (match, name) => name in params ? String(params[name]) : match)
  }

  /** Returns {@code text} in the active language with {name} placeholders filled; unknown text is returned as is. */
  function t(text, params, context) {
    const source = String(text)
    let value = source
    if (english) {
      const contextual = context ? EN[`${context}|${source}`] : undefined
      value = contextual ?? (Object.hasOwn(EN, source) ? EN[source] : source)
    }
    return format(value, params)
  }

  // Keeps surrounding whitespace so inline text keeps its spacing.
  function translateText(value) {
    const trimmed = value.trim()
    if (!trimmed || !Object.hasOwn(EN, trimmed)) return value
    return value.replace(trimmed, () => EN[trimmed])
  }

  function translateDocument() {
    const walker = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (node.nodeType === Node.TEXT_NODE) return NodeFilter.FILTER_ACCEPT
        if (node.matches('script, style, [translate="no"]')) return NodeFilter.FILTER_REJECT
        return NodeFilter.FILTER_ACCEPT
      }
    })
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.nodeType === Node.TEXT_NODE) {
        const translated = translateText(node.nodeValue)
        if (translated !== node.nodeValue) node.nodeValue = translated
        continue
      }
      for (const name of TRANSLATED_ATTRIBUTES) {
        const value = node.getAttribute(name)
        if (value) node.setAttribute(name, translateText(value))
      }
    }
  }

  function setLanguage(next) {
    try { window.localStorage.setItem(STORAGE_KEY, next) } catch { /* storage unavailable: the choice lasts until reload */ }
    window.location.reload()
  }

  function bindToggle() {
    const toggle = document.getElementById('lang-toggle')
    if (!toggle) return
    toggle.title = t('切换语言')
    toggle.setAttribute('aria-label', english ? 'Switch to Chinese' : '切换到英文')
    toggle.addEventListener('click', () => setLanguage(english ? 'zh' : 'en'))
  }

  document.documentElement.lang = english ? 'en' : 'zh-CN'
  window.t = t
  window.MeerkatI18n = { lang, locale: english ? 'en-US' : 'zh-CN', t, setLanguage }
  // Loaded with defer ahead of the page modules, so the markup is parsed and nothing has rendered dynamic text yet.
  if (english) translateDocument()
  bindToggle()
})()
