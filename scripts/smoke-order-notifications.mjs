// TT-S1-4: chạy Gateway, Commerce và Notification JAR thật với PostgreSQL tạm, API/JWT có xác minh RSA.
// Catalog, Fulfillment, OAuth và VNPAY là fixture cục bộ: không dùng tài khoản, tiền hoặc database của nhóm.
// Tạo dữ liệu qua cart/checkout, gây lỗi giao nhận, kiểm tra retry/IDOR; cleanup chỉ tài nguyên do lần chạy tạo.
import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { createHmac, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { copyFileSync, createWriteStream, existsSync, mkdtempSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const logs = mkdtempSync(join(tmpdir(), 'nexus-tt4-'))
const pgName = 'nexus-tt4-' + randomBytes(5).toString('hex')
const password = randomBytes(24).toString('hex')
const hashSecret = randomBytes(32).toString('hex')
const processes = []
let pgCreated = false
let notificationUrl
let deliver = false
let loseAck = true
const sku = randomUUID()
const seller = randomUUID()
const reservations = new Set()
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'tt4-only', use: 'sig', alg: 'RS256' }
let issuer
const sleep = ms => new Promise(done => setTimeout(done, ms))
const docker = args => execFileSync('docker', args, { encoding: 'utf8', windowsHide: true,
    env: { ...process.env, POSTGRES_PASSWORD: password }, stdio: ['pipe', 'pipe', 'pipe'] }).trim()

// Mọi vòng đợi có deadline; không in JWT, secret hoặc response chứa dữ liệu cá nhân vào log test.
async function ready(label, check, timeout = 120000) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
        if (await check().catch(() => false)) return
        await sleep(500)
    }
    throw new Error(`${label} timeout; logs: ${logs}`)
}

function jwt(subject, roles) {
    const now = Math.floor(Date.now() / 1000)
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
    const data = encode({ alg: 'RS256', kid: jwk.kid }) + '.' + encode({ iss: issuer, sub: subject,
        iat: now, exp: now + 600, realm_access: { roles } })
    return data + '.' + sign('RSA-SHA256', Buffer.from(data), privateKey).toString('base64url')
}

async function request(base, path, { method = 'GET', body, token, expected = 200, headers = {} } = {}) {
    const response = await fetch(base + path, { method, signal: AbortSignal.timeout(20000),
        headers: { ...(body ? { 'Content-Type': 'application/json' } : {}),
            ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
        body: body === undefined ? undefined : JSON.stringify(body) })
    assert.equal(response.status, expected, `${method} ${path}: unexpected status`)
    const text = await response.text()
    return text ? JSON.parse(text) : null
}

// Fixture ngoài phạm vi TT-S1-4 dùng hợp đồng HTTP thật; proxy có thể mất ACK sau khi Notification đã lưu thành công.
const fixture = createServer(async (req, res) => {
    try {
        const chunks = []
        for await (const part of req) chunks.push(part)
        const raw = Buffer.concat(chunks).toString()
        let data
        if (req.url.endsWith('/certs')) data = { keys: [jwk] }
        else if (req.url === '/token') {
            const client = new URLSearchParams(raw).get('client_id')
            assert.ok(['notification-fixture', 'inventory-fixture'].includes(client))
            data = { access_token: jwt(randomUUID(), client === 'notification-fixture' ? ['NOTIFICATION_WRITER'] : ['INVENTORY.UPDATE']), expires_in: 600 }
        } else if (req.url.startsWith('/api/v1/products/skus/')) {
            data = { success: true, data: { skuId: sku, skuCode: 'TT4-DEMO', productName: 'Sản phẩm demo TT4',
                price: 25000, stockQuantity: 100, isActive: true, sellerId: seller } }
        } else if (req.url.startsWith('/api/v1/inventory/sku/')) data = { success: true, data: { availableQty: 100 } }
        else if (req.url === '/api/v1/inventory/reserve') {
            const input = JSON.parse(raw)
            data = { success: true, data: { refId: input.refId, refType: 'ORDER', status: 'PENDING',
                items: input.items.map(item => ({ ...item, reservationId: randomUUID(), status: 'PENDING' })) } }
        } else if (req.url.endsWith('/confirm') || req.url === '/api/v1/inventory/release') {
            reservations.add(req.url)
            data = { success: true, data: {} }
        } else if (req.url === '/internal/commerce-notifications') {
            if (!deliver) { res.writeHead(503).end(); return }
            const upstream = await fetch(notificationUrl + req.url, { method: 'POST', body: raw,
                headers: { Authorization: req.headers.authorization, 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(10000) })
            if (upstream.status === 204 && loseAck) { loseAck = false; res.writeHead(503).end(); return }
            res.writeHead(upstream.status, { 'Content-Type': 'application/json' }).end(await upstream.text())
            return
        } else { res.writeHead(404).end(); return }
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(data))
    } catch { res.writeHead(500).end() }
})

async function freePort() {
    const server = createServer()
    await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done) })
    const port = server.address().port
    await new Promise(done => server.close(done))
    return port
}

// Snapshot JAR tránh khóa artifact Maven trên Windows; mỗi tiến trình nhận config riêng và log INFO không chứa bearer.
async function launch(module, env) {
    const artifact = resolve('backend', module, 'target', module + '-0.0.1-SNAPSHOT.jar')
    assert.ok(existsSync(artifact), `Build ${module} first`)
    const snapshot = join(logs, module + '.jar')
    copyFileSync(artifact, snapshot)
    const output = createWriteStream(join(logs, module + '.log'))
    const port = await freePort()
    const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
    const child = spawn(java, ['-Xms64m', '-Xmx256m', '-Duser.timezone=UTC', '-jar', snapshot,
        '--logging.level.root=INFO', '--logging.level.com.nexus=INFO', '--logging.level.web=INFO',
        '--logging.level.sql=INFO', '--logging.level.org.springframework=INFO'], {
        windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env,
            SERVER_PORT: String(port), SPRING_CONFIG_IMPORT: '', SPRING_CLOUD_CONFIG_ENABLED: 'false',
            SPRING_CLOUD_CONFIG_IMPORT_CHECK_ENABLED: 'false', EUREKA_ENABLED: 'false', SYSTEM_ENV: 'dev',
            KEYCLOAK_URL: issuer.replace('/realms/nexus-realm', ''), KEYCLOAK_ISSUER: issuer, ...env } })
    child.stdout.pipe(output)
    child.stderr.pipe(output)
    child.on('error', error => output.write(error.message))
    processes.push(child)
    return `http://127.0.0.1:${port}`
}

try {
    await new Promise(done => fixture.listen(0, '127.0.0.1', done))
    const external = `http://127.0.0.1:${fixture.address().port}`
    issuer = external + '/realms/nexus-realm'
    docker(['run', '-d', '--name', pgName, '-e', 'POSTGRES_PASSWORD', '-p', '127.0.0.1::5432', 'postgres:16-alpine'])
    pgCreated = true
    const pgPort = docker(['port', pgName, '5432/tcp']).split(':').at(-1)
    await ready('PostgreSQL', async () => { docker(['exec', pgName, 'pg_isready', '-U', 'postgres']); return true })
    const sql = (database, statement) => docker(['exec', pgName, 'psql', '-U', 'postgres', '-d', database, '-v', 'ON_ERROR_STOP=1', '-tAc', statement])
    sql('postgres', 'CREATE DATABASE tt4_commerce')
    sql('postgres', 'CREATE DATABASE tt4_notification')
    const db = name => ({ DB_USERNAME: 'postgres', DB_PASSWORD: password, DB_URL: `jdbc:postgresql://127.0.0.1:${pgPort}/${name}` })
    notificationUrl = await launch('notification-service', db('tt4_notification'))
    const commerce = await launch('commerce-service', { ...db('tt4_commerce'), CATALOG_SERVICE_URL: external, FULFILLMENT_SERVICE_URL: external,
        VNPAY_TMN_CODE: 'TESTONLY', VNPAY_HASH_SECRET: hashSecret, VNPAY_RETURN_URL: external + '/payment-result',
        PAYMENT_INVENTORY_TOKEN_URL: external + '/token', PAYMENT_INVENTORY_CLIENT_ID: 'inventory-fixture', PAYMENT_INVENTORY_CLIENT_SECRET: password,
        NOTIFICATION_SERVICE_URL: external, NOTIFICATION_TOKEN_URL: external + '/token',
        NOTIFICATION_CLIENT_ID: 'notification-fixture', NOTIFICATION_CLIENT_SECRET: password,
        APP_NOTIFICATION_POLL_MS: '250', APP_PAYMENT_INVENTORY_POLL_MS: '250' })
    const gateway = await launch('api-gateway', { COMMERCE_SERVICE_URL: commerce, NOTIFICATION_SERVICE_URL: notificationUrl })
    for (const base of [notificationUrl, commerce, gateway]) {
        await ready('Service health', async () => (await fetch(base + '/actuator/health', { signal: AbortSignal.timeout(2000) })).ok)
    }
    console.log('PASS real Gateway, Commerce, Notification, Flyway/PostgreSQL; external integrations use local fixtures')
    const buyerId = randomUUID()
    const buyer = jwt(buyerId, ['BUYER', 'ORDER.CREATE', 'CHECKOUT.START'])
    const other = jwt(randomUUID(), ['BUYER'])
    await request(gateway, '/api/v1/notifications', { expected: 401 })
    const orders = []
    for (const success of [true, false]) {
        const cart = (await request(gateway, '/api/v1/cart/items', { method: 'POST', token: buyer, body: { skuId: sku, quantity: 2 } })).data
        const preview = (await request(gateway, '/api/v1/orders/checkout/preview', { method: 'POST', token: buyer,
            body: { itemIds: [cart.items[0].id], shippingAddress: '123 Đường Demo' } })).data
        assert.equal(preview.totalAmount, 50000)
        const body = { shippingAddress: '123 Đường Demo', recipientName: 'Buyer demo', recipientPhone: '0901234567',
            paymentMethod: 'VNPAY', selectedItemIds: [cart.items[0].id] }
        const headers = { 'Idempotency-Key': randomUUID() }
        const order = (await request(gateway, '/api/v1/orders', { method: 'POST', body, token: buyer, headers, expected: 201 })).data
        assert.equal((await request(gateway, '/api/v1/orders', { method: 'POST', body, token: buyer, headers, expected: 201 })).data.id, order.id)
        const paymentId = new URL(order.paymentUrl).searchParams.get('vnp_TxnRef')
        const params = { vnp_Amount: '5000000', vnp_ResponseCode: success ? '00' : '24', vnp_TmnCode: 'TESTONLY',
            vnp_TransactionNo: success ? '123456789' : '0', vnp_TransactionStatus: success ? '00' : '02', vnp_TxnRef: paymentId }
        const unsigned = Object.keys(params).sort().map(key => `${key}=${encodeURIComponent(params[key])}`).join('&')
        const signature = createHmac('sha512', hashSecret).update(unsigned).digest('hex')
        const path = '/api/v1/payments/vnpay/ipn?' + unsigned + '&vnp_SecureHash=' + signature
        assert.equal((await request(gateway, path + 'x')).RspCode, '97')
        assert.equal((await request(gateway, path)).RspCode, '00')
        assert.equal((await request(gateway, path)).RspCode, '02')
        const payment = (await request(gateway, '/api/v1/payments/' + paymentId, { token: buyer })).data
        assert.equal(payment.status, success ? 'SUCCESS' : 'FAILED')
        orders.push({ order, paymentId, success })
    }
    await ready('Outbox retry after outage', async () => Number(sql('tt4_commerce', 'SELECT count(*) FROM commerce_notification_outbox WHERE attempts>0 AND NOT done')) === 4)
    assert.equal((await request(gateway, '/api/v1/notifications', { token: buyer })).data.totalElements, 0)
    assert.equal(sql('tt4_commerce', "SELECT count(*) FROM orders WHERE status='PAID'"), '1')
    assert.equal(sql('tt4_commerce', "SELECT count(*) FROM orders WHERE status='CANCELLED'"), '1')
    console.log('PASS cart → checkout → order → signed IPN success/failure; duplicate requests and delivery outage preserve financial results')
    deliver = true
    // Chỉ kéo lịch retry tại DB tạm do script tạo, tránh phải chờ backoff 10 giây trong smoke.
    sql('tt4_commerce', 'UPDATE commerce_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP')
    await ready('Lost ACK retry', async () => !loseAck)
    sql('tt4_commerce', 'UPDATE commerce_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE NOT done')
    await ready('Notification delivery', async () => Number(sql('tt4_commerce', 'SELECT count(*) FROM commerce_notification_outbox WHERE done')) === 4)
    const inbox = (await request(gateway, '/api/v1/notifications', { token: buyer })).data
    assert.equal(inbox.totalElements, 4)
    assert.equal(inbox.unreadCount, 4)
    assert.equal(inbox.content.filter(item => item.type === 'ORDER_CREATED').length, 2)
    for (const { order, success } of orders) {
        const item = inbox.content.find(item => item.orderId === order.id && item.type === (success ? 'PAYMENT_SUCCESS' : 'PAYMENT_FAILED'))
        assert.ok(item)
        assert.ok(item.content.includes(order.orderNo) && item.content.includes('50000.00 VND'))
    }
    assert.equal((await request(gateway, '/api/v1/notifications', { token: other })).data.totalElements, 0)
    await request(gateway, `/api/v1/notifications/${inbox.content[0].id}/read`, { method: 'POST', token: other, expected: 404 })
    await request(notificationUrl, '/internal/commerce-notifications', { method: 'POST', token: buyer, expected: 403,
        body: { id: randomUUID(), userId: buyerId, orderId: orders[0].order.id, orderNo: 'ORD-FORGED',
            type: 'ORDER_CREATED', amount: 1, occurredAt: new Date().toISOString() } })
    await request(gateway, `/api/v1/notifications/${inbox.content[0].id}/read`, { method: 'POST', token: buyer })
    await request(gateway, '/api/v1/notifications/read-all', { method: 'POST', token: buyer })
    assert.equal((await request(gateway, '/api/v1/notifications', { token: buyer })).data.unreadCount, 0)
    assert.equal(sql('tt4_notification', 'SELECT count(*) FROM notification_attempts'), '4')
    await ready('Inventory outbox', async () => Number(sql('tt4_commerce', 'SELECT count(*) FROM payment_inventory_tasks WHERE done')) === 2)
    assert.equal(reservations.size, 2)
    console.log('PASS correct buyer/content, read ownership, role enforcement, lost ACK deduplication, inventory worker; all four notifications delivered once')
    console.log('Logs: ' + logs)
} catch (error) {
    console.error('Smoke failed; service logs: ' + logs)
    throw error
} finally {
    // Chỉ kết thúc PID/container đã tạo trong script; không động volume hoặc tiến trình dev của người dùng.
    for (const child of processes) child.kill()
    await Promise.all(processes.map(child => child.exitCode !== null ? Promise.resolve() : new Promise(done => {
        child.once('exit', done)
        setTimeout(done, 10000).unref()
    })))
    fixture.closeAllConnections()
    await new Promise(done => fixture.close(done))
    if (pgCreated) docker(['rm', '-f', pgName])
}
