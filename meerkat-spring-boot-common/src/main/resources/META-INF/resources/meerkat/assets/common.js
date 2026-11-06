// Shared by every dashboard page: API access, embedded auth, notices and small DOM helpers.
// Same labels on every page for span kinds; t() from i18n.js translates them.
const KIND_NAMES = { Http: 'HTTP', Database: 'SQL', Redis: 'Redis', Remote: '远程调用', Kafka: 'Kafka', Transaction: '事务',
  Schedule: '定时任务', Service: '服务', Code: '代码', Other: '其他' }
export const kindName = kind => t(KIND_NAMES[kind] || kind || '调用')

export const api = new URL('../../api/meerkat/', import.meta.url)
export const $ = id => document.getElementById(id)

/** Headers for an API call: the embedding page's bearer token and, on state-changing requests, the CSRF token. */
export function authHeaders(method = 'GET', initial = {}) {
  const headers = new Headers(initial)
  const token = typeof window.MeerkatAuth?.token === 'function' ? window.MeerkatAuth.token() : ''
  if (token) headers.set('Authorization', 'Bearer ' + token)
  // Spring Security's CookieCsrfTokenRepository exposes XSRF-TOKEN; echo it on state-changing requests.
  const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  const upper = method.toUpperCase()
  if (csrf && upper !== 'GET' && upper !== 'HEAD') headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf[1]))
  return headers
}

/** Fetches {@code path} under the API root and parses JSON; errors carry a message ready to show. */
export async function request(path, requestOptions = {}, deniedMessage = t('没有查看的权限，请登录或检查 ObservabilityAccessPolicy')) {
  const { notFoundMessage, ...options } = requestOptions
  const headers = authHeaders(options.method || 'GET', options.headers || {})
  const response = await fetch(new URL(path.replace(/^\/+/, ''), api),
    { credentials: 'same-origin', ...options, headers })
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? deniedMessage
    : response.status === 404 && notFoundMessage ? notFoundMessage
      : t('请求失败（HTTP {status}）', { status: response.status }))
  return response.status === 204 ? null : response.json()
}

export function initializeEmbeddedAuth() {
  if (window.parent === window) return Promise.resolve()
  return new Promise(resolve => {
    let completed = false
    const finish = token => {
      if (completed) return
      completed = true
      clearTimeout(timeout)
      window.removeEventListener('message', receiveAuth)
      window.MeerkatAuth = { token: () => token }
      resolve()
    }
    const receiveAuth = event => {
      if (event.origin !== window.location.origin || event.source !== window.parent
        || event.data?.type !== 'meerkat:auth-response') return
      finish(typeof event.data.token === 'string' ? event.data.token : '')
    }
    const timeout = setTimeout(() => finish(''), 1500)
    window.addEventListener('message', receiveAuth)
    window.parent.postMessage({ type: 'meerkat:auth-request' }, window.location.origin)
  })
}

export function node(tag, className, text) {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text != null) element.textContent = text
  return element
}

/** Shows {@code message} in the page's #notice banner; an empty message hides it. */
export function showNotice(message, warning = false) {
  const notice = $('notice')
  notice.textContent = message
  notice.classList.toggle('warning', warning)
  notice.hidden = !message
}
