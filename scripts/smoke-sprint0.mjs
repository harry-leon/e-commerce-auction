// Local Compose smoke test. Creates a temporary account and CRUD fixtures, then removes them.
// Set NEXUS_SMOKE_BOOTSTRAP_ADMIN=true to grant ADMIN to that new fixture account in local PostgreSQL.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

// Chỉ dùng để kiểm chứng môi trường local: tạo fixture riêng, gọi API qua Gateway và cleanup trong finally.
const base = process.env.NEXUS_SMOKE_URL || 'http://localhost:3000/api/v1'
const suffix = Date.now().toString()
const email = `sprint0-${suffix}@example.com`
let token = ''
let fixtureUser
let productId
const categoryIds = []

// Eureka registry propagation can lag the HTTP health checks on a cold start.
for (const path of ['/products?size=1', '/ping']) {
  let ready = false
  for (let attempt = 0; attempt < 45; attempt++) {
    const response = await fetch(base + path).catch(() => null)
    if (response?.ok) { ready = true; break }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  assert.ok(ready, `Gateway route is not ready: ${path}`)
}

async function request(path, method = 'GET', body, expected = 200, authenticated = true) {
  const response = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const result = response.status === 204 ? null : await response.json()
  assert.equal(response.status, expected, `${method} ${path}: ${JSON.stringify(result)}`)
  return result
}

// Cấp ADMIN trực tiếp trong PostgreSQL chỉ cho UUID tài khoản test vừa tạo, sau khi kiểm tra cờ opt-in và localhost.
function bootstrapAdmin(id) {
  assert.equal(process.env.NEXUS_SMOKE_BOOTSTRAP_ADMIN, 'true', 'Set NEXUS_SMOKE_BOOTSTRAP_ADMIN=true only for local Compose verification')
  assert.match(base, /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//)
  assert.match(id, /^[0-9a-f-]{36}$/i)
  const config = Object.fromEntries(readFileSync('.env', 'utf8').split(/\r?\n/)
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1).replace(/^['"]|['"]$/g, '')] }))
  execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1',
    '-U', config.LOCAL_DB_USERNAME || 'nexus', '-d', 'nexus_user', '-c',
    `INSERT INTO user_roles (user_id, role_id) SELECT '${id}', id FROM roles WHERE code = 'ADMIN' ON CONFLICT DO NOTHING;`], { stdio: 'pipe' })
}

try {
  const registration = await request('/auth/register', 'POST', {
    email, password: 'Sprint0Password1!', fullName: 'Sprint 0 Verification',
    phone: '0912345678', gender: 'OTHER',
  }, 201, false)
  fixtureUser = registration.user.id
  token = registration.accessToken
  await request('/categories', 'POST', { name: 'Forbidden', status: 'ACTIVE' }, 403)
  console.log('PASS registration and category write authorization')

  bootstrapAdmin(fixtureUser)
  token = (await request('/auth/login', 'POST', { email, password: 'Sprint0Password1!' }, 200, false)).accessToken
  let parentId = null
  for (let level = 1; level <= 3; level++) {
    const category = (await request('/categories', 'POST', { name: `Smoke ${suffix} level ${level}`, nameVi: `Smoke ${level}`, parentId, status: 'ACTIVE' }, 201)).data
    categoryIds.push(category.id); parentId = category.id
  }
  await request('/categories', 'POST', { name: 'Too deep', parentId, status: 'ACTIVE' }, 400)
  await request(`/categories/${categoryIds[0]}`, 'PUT', { name: 'Cycle', parentId, status: 'ACTIVE' }, 400)
  console.log('PASS category tree, maximum depth and cycle rejection')

  const product = (await request('/products', 'POST', {
    name: `Smoke Laptop ${suffix}`, description: 'PostgreSQL verification product', categoryId: parentId,
    auctionEnabled: false, skus: [{ skuCode: `SMOKE-${suffix}`, price: 500, originalPrice: 600, status: 'ACTIVE' }], images: [],
  }, 201)).data
  productId = product.id
  await request(`/products/${productId}/approve`, 'POST')
  const results = await request(`/products?q=${suffix}&categoryId=${categoryIds[0]}&minPrice=400&maxPrice=550`, 'GET', undefined, 200, false)
  assert.ok(results.data.some(item => item.id === productId))
  await request(`/products/${productId}`, 'GET', undefined, 200, false)
  await request(`/categories/${parentId}`, 'DELETE', undefined, 400)
  console.log('PASS PostgreSQL product/SKU persistence, public search/detail and category deletion guard')

  const profile = await request('/users/me', 'PUT', { fullName: 'Updated Verification', phone: '0912345678', gender: 'OTHER' })
  assert.equal(profile.fullName, 'Updated Verification')
  const activities = await request('/users/me/activity-history')
  assert.ok(activities.content.length > 0)
  console.log('PASS profile update and activity history')

  await request('/auth/forgot-password', 'POST', { email }, 200, false)
  const mailList = await fetch('http://localhost:8025/api/v1/messages').then(response => response.json())
  const message = mailList.messages.find(item => item.To?.some(recipient => recipient.Address === email))
  assert.ok(message, 'Reset email must be received by SMTP server')
  const mail = await fetch(`http://localhost:8025/api/v1/message/${message.ID}`).then(response => response.json())
  const rawToken = new URL(mail.Text.match(/https?:\/\/\S+/)[0]).searchParams.get('token')
  await request('/auth/reset-password', 'POST', { token: rawToken, newPassword: 'ChangedPassword1!' }, 200, false)
  await request('/auth/reset-password', 'POST', { token: rawToken, newPassword: 'AnotherPassword1!' }, 400, false)
  const login = await request('/auth/login', 'POST', { email, password: 'ChangedPassword1!' }, 200, false)
  token = login.accessToken
  const refreshed = await request('/auth/refresh', 'POST', { refreshToken: login.refreshToken }, 200, false)
  assert.ok(refreshed.accessToken)
  console.log('PASS SMTP reset email, password reset, single-use token and JWT refresh')
} finally {
  // Luôn thử cleanup fixture, kể cả khi một bước kiểm chứng trước đó thất bại.
  if (productId) await request(`/products/${productId}`, 'DELETE', undefined, 204).catch(error => console.error('Fixture product cleanup failed:', error.message))
  for (const id of categoryIds.reverse()) await request(`/categories/${id}`, 'DELETE', undefined, 204).catch(error => console.error('Fixture category cleanup failed:', error.message))
  if (fixtureUser && categoryIds.length) await request(`/users/${fixtureUser}`, 'DELETE').catch(error => console.error('Fixture account cleanup failed:', error.message))
}
