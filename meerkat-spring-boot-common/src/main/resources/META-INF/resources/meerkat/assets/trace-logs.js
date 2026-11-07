// Renders the application log lines Meerkat buffered while a trace ran; shared by the trace and capture pages.
import { node } from './common.js?v=20261106.11'

const LEVEL_CLASS = { ERROR: 'bad', WARN: 'warn' }

/** Returns a section listing {@code logs}, or null when there are none. */
export function renderTraceLogs(logs) {
  if (!Array.isArray(logs) || logs.length === 0) return null
  const section = node('section', 'trace-logs')
  section.append(node('h3', '', t('日志（{count} 行）', { count: logs.length })))
  const list = node('ol', 'trace-log-list')
  for (const line of logs) {
    const item = node('li', `trace-log ${LEVEL_CLASS[line.level] || ''}`.trim())
    item.append(
      node('span', 'trace-log-offset', `+${Number(line.offsetMs || 0)}ms`),
      node('span', 'trace-log-level', line.level || ''),
      node('span', 'trace-log-thread', line.thread || ''),
      node('span', 'trace-log-logger', shortLogger(line.logger)),
      node('span', 'trace-log-message', line.message || ''))
    item.title = [line.logger, line.step ? t('所在步骤：{step}', { step: line.step }) : ''].filter(Boolean).join('\n')
    list.append(item)
  }
  section.append(list)
  return section
}

function shortLogger(logger) {
  if (!logger) return ''
  const parts = logger.split('.')
  return parts.length <= 2 ? logger : parts.slice(0, -1).map(part => part.charAt(0)).join('.') + '.' + parts.at(-1)
}
