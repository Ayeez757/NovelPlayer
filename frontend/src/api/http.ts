export interface ApiErrorResponse {
  timestamp: string
  status: number
  error: string
  messages: string[]
}

/**
 * 统一浏览器请求封装。
 *
 * 后端所有错误都会返回统一错误对象，因此这里集中转换为异常，
 * 页面层只需要展示错误信息。
 */
export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {})
    },
    ...options
  })

  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as ApiErrorResponse | null
    const message = payload?.messages?.join('\n') || response.statusText
    throw new Error(message)
  }

  return response.json() as Promise<T>
}
