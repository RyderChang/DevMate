import { randomBytes } from 'node:crypto'
import http from 'node:http'

// Local browser acceptance fixture. All state is synthetic, in memory, and bound to loopback.
const port = Number(process.argv[2] ?? 19091)
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error('Invalid fixture port')
}

const now = '2026-10-09T01:00:00Z'
const user = {
  id: 7,
  username: 'browser-fixture',
  nickname: 'Browser Fixture',
  avatar: null,
  role: 'USER',
}
const project = {
  id: 1,
  name: '合成项目',
  description: '仅用于浏览器验收',
  createTime: now,
  updateTime: now,
}
const conversation = {
  id: 1,
  projectId: 1,
  title: '引用展示验收',
  generationState: 'IDLE',
  createdAt: now,
  updatedAt: now,
}
const source = {
  pointId: 'synthetic-point-1',
  documentId: 11,
  filename: '<img src=x onerror=alert(1)>.md',
  processingId: 12,
  indexId: 13,
  processingGeneration: 1,
  indexGeneration: 1,
  parserVersion: 'browser-fixture-v1',
  strategyVersion: 'browser-fixture-v1',
  sourceSha256: 'a'.repeat(64),
  chunkSha256: 'b'.repeat(64),
  ordinal: 0,
  start: 0,
  end: 24,
  startLine: 1,
  endLine: 2,
}
const rag = {
  retrievalId: '11111111-1111-4111-8111-111111111111',
  spec: 'browser-fixture-v1',
  queryTokens: 3,
  rounds: 1,
  inspectedPoints: 1,
  templateVersion: 'project-rag-v1',
  checkedAt: now,
  offsetUnit: 'NORMALIZED_UNICODE_CODE_POINT',
}
const messages = [
  {
    id: 1,
    role: 'USER',
    content: '历史引用是否仍可用？',
    sequenceNo: 1,
    createdAt: now,
    evidence: null,
  },
  {
    id: 2,
    role: 'ASSISTANT',
    content: '这是一条合成历史回答。',
    sequenceNo: 2,
    createdAt: now,
    evidence: {
      rag,
      citations: [{ citationId: 'C1', source, available: false }],
    },
  },
]
const tokens = new Set()
const requests = new Map()
const stats = {
  chatRequests: 0,
  ragRequests: 0,
  ragReplays: 0,
  unknownResponses: 0,
  terminalResponses: 0,
}

function result(data) {
  return { code: 200, message: 'success', data }
}

function send(res, status, payload) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  res.end(JSON.stringify(payload))
}

async function readJson(req) {
  let size = 0
  const chunks = []
  for await (const chunk of req) {
    size += chunk.length
    if (size > 16384) throw new Error('Request too large')
    chunks.push(chunk)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function page(items, url) {
  const pageNumber = Number(url.searchParams.get('page') ?? 1)
  const pageSize = Number(url.searchParams.get('pageSize') ?? 20)
  if (
    !Number.isInteger(pageNumber) ||
    pageNumber < 1 ||
    !Number.isInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > 100
  ) {
    throw new Error('Invalid page')
  }
  return {
    page: pageNumber,
    pageSize,
    total: items.length,
    items: items.slice((pageNumber - 1) * pageSize, pageNumber * pageSize),
  }
}

function completedResponse(content, mode) {
  const userMessage = {
    id: messages.length + 1,
    role: 'USER',
    content,
    sequenceNo: messages.length + 1,
    createdAt: now,
    evidence: null,
  }
  const assistantMessage = {
    id: messages.length + 2,
    role: 'ASSISTANT',
    content: mode === 'rag' ? '合成回答：请核对来源。 [C1]' : '合成普通聊天回答。',
    sequenceNo: messages.length + 2,
    createdAt: now,
  }
  const invocation = {
    status: 'SUCCEEDED',
    provider: 'fixture',
    model: 'synthetic',
    inputTokens: 3,
    outputTokens: 5,
    totalTokens: 8,
    durationMs: 1,
    completedAt: now,
  }
  if (mode === 'rag') {
    const citations = [{ citationId: 'C1', source, available: true }]
    messages.push(userMessage, {
      ...assistantMessage,
      evidence: { rag, citations },
    })
    return {
      conversationId: 1,
      userMessage,
      assistantMessage,
      invocation,
      rag,
      citations,
    }
  }
  messages.push(userMessage, { ...assistantMessage, evidence: null })
  return { conversationId: 1, userMessage, assistantMessage, invocation }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://127.0.0.1')
  try {
    if (req.method === 'GET' && url.pathname === '/__fixture/stats') {
      return send(res, 200, { ...stats, messageCount: messages.length })
    }
    if (req.method === 'POST' && url.pathname === '/auth/login') {
      const body = await readJson(req)
      if (
        typeof body.username !== 'string' ||
        !body.username.trim() ||
        typeof body.password !== 'string' ||
        !body.password
      ) {
        return send(res, 400, {
          code: 400,
          message: 'Invalid login',
          data: null,
        })
      }
      const token = randomBytes(32).toString('hex')
      tokens.add(token)
      return send(res, 200, result({ token, tokenType: 'Bearer', expiresIn: 3600, user }))
    }
    if (!tokens.has(req.headers.authorization?.replace(/^Bearer /, ''))) {
      return send(res, 401, { code: 401, message: 'Unauthorized', data: null })
    }
    if (req.method === 'GET' && url.pathname === '/auth/me') return send(res, 200, result(user))
    if (req.method === 'GET' && url.pathname === '/projects')
      return send(res, 200, result(page([project], url)))
    if (req.method === 'GET' && url.pathname === '/projects/1')
      return send(res, 200, result(project))
    if (req.method === 'GET' && url.pathname === '/projects/1/conversations') {
      return send(res, 200, result(page([conversation], url)))
    }
    if (req.method === 'GET' && url.pathname === '/projects/1/conversations/1') {
      return send(res, 200, result(conversation))
    }
    if (req.method === 'GET' && url.pathname === '/projects/1/conversations/1/messages') {
      return send(res, 200, result(page(messages, url)))
    }
    if (
      req.method === 'POST' &&
      /^\/projects\/1\/conversations\/1\/(rag-messages|messages)$/.test(url.pathname)
    ) {
      const mode = url.pathname.endsWith('/rag-messages') ? 'rag' : 'chat'
      if (mode === 'rag') stats.ragRequests += 1
      else stats.chatRequests += 1
      const body = await readJson(req)
      if (
        typeof body.clientRequestId !== 'string' ||
        typeof body.content !== 'string' ||
        !body.content.trim()
      ) {
        return send(res, 400, {
          code: 400,
          message: 'Invalid message',
          data: null,
        })
      }
      const prior = requests.get(body.clientRequestId)
      if (prior) {
        if (prior.mode !== mode || prior.content !== body.content) {
          return send(res, 409, {
            code: 409,
            message: 'Request mode or content conflicts',
            data: null,
          })
        }
        stats.ragReplays += Number(mode === 'rag')
        return send(res, 200, result(prior.response))
      }
      if (mode === 'rag' && body.content.includes('终态')) {
        stats.terminalResponses += 1
        return send(res, 409, {
          code: 409,
          message: 'No bounded document context is available',
          data: null,
        })
      }
      const response = completedResponse(body.content, mode)
      requests.set(body.clientRequestId, {
        mode,
        content: body.content,
        response,
      })
      if (mode === 'rag' && body.content.includes('未知')) {
        stats.unknownResponses += 1
        return send(res, 503, {
          code: 503,
          message: 'RAG state cannot be confirmed',
          data: null,
        })
      }
      return send(res, 200, result(response))
    }
    return send(res, 404, { code: 404, message: 'Not found', data: null })
  } catch {
    return send(res, 400, {
      code: 400,
      message: 'Invalid fixture request',
      data: null,
    })
  }
})

server.requestTimeout = 10000
server.headersTimeout = 5000
server.listen(port, '127.0.0.1', () =>
  console.log(`RAG browser fixture ready on 127.0.0.1:${port}`),
)
