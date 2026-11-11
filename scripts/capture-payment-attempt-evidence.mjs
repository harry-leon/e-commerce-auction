// Chạy Commerce JAR/Flyway/PostgreSQL thật và chụp Swagger UI sau khi gọi API thật.
// Catalog, Fulfillment, OAuth và chữ ký VNPAY là HTTP fixture cục bộ; không đăng nhập/thu tiền tại merchant.
// Script tạo DB riêng, JWT/secret tạm trong RAM; ảnh/transcript không lưu token, URL đã ký hoặc payload IPN.
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { createHmac, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { createWriteStream, mkdirSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'

const require = createRequire(resolve('frontend-web/package.json'))
const { chromium } = require('playwright')
const pgBin = process.env.PG_BIN || 'C:/Program Files/PostgreSQL/18/bin'
const pgPort = process.env.EVIDENCE_PG_PORT || '15479'
const port = Number(process.env.EVIDENCE_COMMERCE_PORT || 18083)
const base = 'http://127.0.0.1:' + port
const database = 'nexus_attempt_evidence_' + process.pid
const evidence = resolve('docs/test-evidence/payment-attempt-pricing')
mkdirSync(evidence, { recursive: true })
const logPath = join(tmpdir(), 'nexus-attempt-commerce-' + process.pid + '.log')
const log = createWriteStream(logPath)
const buyer = randomUUID(), seller = randomUUID(), sku = randomUUID()
const secret = randomBytes(32).toString('hex')
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'attempt-evidence', use: 'sig', alg: 'RS256' }
const reservations = new Map()
const expiredRefs = new Set()
const transcript = []
let price = 150000, committed = 0, released = 0, fixtureUrl, app, browser, databaseCreated = false
const delay = ms => new Promise(done => setTimeout(done, ms))

// Chỉ DB có tên nội bộ của lần chạy được tạo/xóa; không đọc .env hoặc DB của nhóm.
function pg(program, args) {
    return execFileSync(join(pgBin, program + (process.platform === 'win32' ? '.exe' : '')), [
        '-h', '127.0.0.1', '-p', pgPort, '-U', 'postgres', ...args,
    ], { encoding: 'utf8', windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
}

function jwt() {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
    const now = Math.floor(Date.now() / 1000)
    const data = encode({ alg: 'RS256', kid: jwk.kid }) + '.' + encode({
        iss: fixtureUrl, sub: buyer, iat: now, exp: now + 1200,
        realm_access: { roles: ['BUYER', 'ADMIN', 'ORDER.CREATE', 'CHECKOUT.START'] },
    })
    return data + '.' + sign('RSA-SHA256', Buffer.from(data), privateKey).toString('base64url')
}

// Ref kho riêng mỗi attempt giúp phát hiện release cũ nhả nhầm hàng mới.
const fixture = createServer(async (req, res) => {
    try {
        const chunks = []
        for await (const chunk of req) chunks.push(chunk)
        const raw = Buffer.concat(chunks).toString()
        const body = raw && req.headers['content-type']?.includes('json') ? JSON.parse(raw) : null
        let data
        if (req.url === '/certs') {
            res.setHeader('content-type', 'application/json')
            return res.end(JSON.stringify({ keys: [jwk] }))
        }
        if (req.url === '/token') {
            res.setHeader('content-type', 'application/json')
            return res.end(JSON.stringify({ access_token: jwt(), expires_in: 1200 }))
        }
        if (req.url === '/api/v1/products/skus/' + sku) {
            data = { skuId: sku, skuCode: 'AO-M', productName: 'Ao thun demo', price, stockQuantity: null, isActive: true, sellerId: seller }
        } else if (req.url === '/api/v1/inventory/reserve') {
            assert.ok(req.headers.authorization?.startsWith('Bearer '))
            assert.equal(body.refType, 'ORDER')
            assert.ok(!reservations.has(body.refId), 'duplicate reserve reference')
            const receipt = { refId: body.refId, refType: 'ORDER', status: 'PENDING',
                items: body.items.map(item => ({ ...item, reservationId: randomUUID(), status: 'PENDING' })) }
            reservations.set(body.refId, receipt)
            data = receipt
        } else if (req.url?.match(/^\/api\/v1\/inventory\/orders\/[^/]+\/commit$/)) {
            const ref = req.url.split('/')[5]
            const receipt = reservations.get(ref)
            assert.ok(receipt, 'missing reservation')
            if (expiredRefs.has(ref)) {
                receipt.status = 'EXPIRED'
                res.statusCode = 409
                res.setHeader('content-type', 'application/json')
                return res.end(JSON.stringify({ success: false, data: null,
                    error: { code: 'INVENTORY_RESERVATION_EXPIRED', message: 'Fixture reservation expired', details: null }, meta: {} }))
            }
            assert.notEqual(receipt.status, 'RELEASED', 'cannot commit released attempt')
            if (receipt.status !== 'CONFIRMED') {
                receipt.status = 'CONFIRMED'
                receipt.items.forEach(item => { item.status = 'CONFIRMED' })
                committed++
            }
            data = receipt
        } else if (req.url === '/api/v1/inventory/release') {
            const receipt = reservations.get(body.refId)
            if (receipt?.status === 'PENDING') { receipt.status = 'RELEASED'; released++ }
            data = null
        } else if (req.url?.startsWith('/api/v1/inventory/sku/')) {
            data = { availableQty: 100 }
        } else {
            res.statusCode = 404
            return res.end()
        }
        res.setHeader('content-type', 'application/json')
        res.end(JSON.stringify({ success: true, data, error: null, meta: {} }))
    } catch {
        res.statusCode = 500
        res.end(JSON.stringify({ success: false, error: { code: 'FIXTURE_ASSERTION_FAILED' } }))
    }
})

async function ready(check, label, timeout = 90000) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
        if (await check().catch(() => false)) return
        await delay(300)
    }
    throw new Error(label + ' timed out; private local log: ' + logPath)
}

// Chứng cứ không lưu callback có chữ ký hoặc URL chứa secure hash.
function sanitized(value) {
    if (Array.isArray(value)) return value.map(sanitized)
    if (!value || typeof value !== 'object') return value
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
        key, key === 'paymentUrl' && item ? '[signed sandbox URL omitted]' : sanitized(item),
    ]))
}

async function api(path, method = 'GET', body, key, expected = 200) {
    const response = await fetch(base + path, {
        method, signal: AbortSignal.timeout(15000),
        headers: { Authorization: 'Bearer ' + jwt(), ...(body ? { 'Content-Type': 'application/json' } : {}),
            ...(key ? { 'Idempotency-Key': key } : {}) },
        body: body ? JSON.stringify(body) : undefined,
    })
    const result = await response.json()
    assert.equal(response.status, expected, method + ' ' + path + ': ' + JSON.stringify(sanitized(result)))
    transcript.push({ method, path, status: response.status, response: sanitized(result) })
    return result.data
}

// Callback được ký theo quy tắc form-encode/sort, không gọi merchant VNPAY.
async function ipn(payment, success) {
    const params = {
        vnp_TmnCode: 'TESTONLY', vnp_TxnRef: payment.paymentId,
        vnp_Amount: String(payment.amount * 100), vnp_ResponseCode: success ? '00' : '24',
        vnp_TransactionStatus: success ? '00' : '02',
        vnp_TransactionNo: String(BigInt('0x' + payment.paymentId.replaceAll('-', '').slice(0, 12))),
    }
    const encoded = new URLSearchParams(Object.entries(params).sort(([a], [b]) => a.localeCompare(b))).toString()
    params.vnp_SecureHash = createHmac('sha512', secret).update(encoded).digest('hex')
    const response = await fetch(base + '/api/v1/payments/vnpay/ipn?' + new URLSearchParams(params))
    const result = await response.json()
    transcript.push({ method: 'GET', path: '/api/v1/payments/vnpay/ipn [signed fixture query omitted]', response: result })
    return result.RspCode
}

// Swagger thực thi HTTP thật; JWT chỉ thêm ở transport, không xuất hiện trong ảnh/curl của UI.
async function swaggerCapture(path, id, filename, caption) {
    const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 })
    await page.route('**/api/v1/**', route => route.continue({
        headers: { ...route.request().headers(), authorization: 'Bearer ' + jwt() },
    }))
    await page.goto(base + '/swagger-ui/index.html')
    const operation = page.locator('.opblock').filter({
        has: page.locator('.opblock-summary-path').filter({ hasText: new RegExp('^' + path.replace(/[{}]/g, '\\$&') + '$') }),
    }).filter({ has: page.locator('.opblock-summary-method', { hasText: 'GET' }) })
    await operation.locator('.opblock-summary').click()
    await operation.getByRole('button', { name: 'Try it out' }).click()
    await operation.locator('input[type="text"]').first().fill(id)
    await operation.getByRole('button', { name: 'Execute', exact: true }).click()
    await operation.locator('.live-responses-table').waitFor()
    await operation.locator('.live-responses-table .response-col_description pre').first().waitFor()
    // Ẩn bảng schema tham khảo để tập trung vào response thực thi, không sửa nội dung response.
    await page.addStyleTag({ content: '.responses-inner > .responses-table,.curl-command,.request-url{display:none!important} .highlight-code,.highlight-code pre{max-height:none!important;overflow:visible!important} pre,code{font-size:13px!important;line-height:1.35!important}' })
    await operation.evaluate((element, text) => {
        const note = document.createElement('div')
        note.textContent = text
        note.style.cssText = 'padding:16px;background:#fff5cf;color:#332a00;font:600 15px sans-serif'
        element.prepend(note)
    }, caption + ' | Commerce + PostgreSQL thật; Catalog/Fulfillment/OAuth/VNPAY fixture. Không thanh toán merchant.')
    await operation.screenshot({ path: join(evidence, filename) })
    await page.close()
}

try {
    pg('createdb', [database])
    databaseCreated = true
    await new Promise(done => fixture.listen(0, '127.0.0.1', done))
    fixtureUrl = 'http://127.0.0.1:' + fixture.address().port
    app = spawn('java', ['-jar', resolve('backend/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar'),
        '--spring.profiles.active=evidence', '--spring.config.import=', '--spring.cloud.config.enabled=false',
        '--server.port=' + port, '--spring.datasource.url=jdbc:postgresql://127.0.0.1:' + pgPort + '/' + database,
        '--spring.datasource.username=postgres', '--spring.datasource.password=', '--eureka.client.enabled=false',
        '--app.keycloak.issuer=' + fixtureUrl, '--app.keycloak.jwk-set-uri=' + fixtureUrl + '/certs',
        '--app.payment.inventory.poll-ms=200', '--app.payment.auction.expiry-poll-ms=60000',
        '--logging.level.org.hibernate.SQL=OFF', '--spring.jpa.show-sql=false',
        '--logging.level.org.springframework=INFO', '--debug=false',
    ], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env,
        CATALOG_SERVICE_URL: fixtureUrl, FULFILLMENT_SERVICE_URL: fixtureUrl,
        VNPAY_TMN_CODE: 'TESTONLY', VNPAY_HASH_SECRET: secret, VNPAY_RETURN_URL: base + '/result',
        PAYMENT_INVENTORY_TOKEN_URL: fixtureUrl + '/token', PAYMENT_INVENTORY_CLIENT_ID: 'evidence',
        PAYMENT_INVENTORY_CLIENT_SECRET: randomBytes(16).toString('hex'),
        NOTIFICATION_CLIENT_ID: '', NOTIFICATION_CLIENT_SECRET: '',
    } })
    app.stdout.pipe(log, { end: false })
    app.stderr.pipe(log, { end: false })
    await ready(async () => (await fetch(base + '/actuator/health')).ok, 'Commerce')
    browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
    const cart = await api('/api/v1/cart/items', 'POST', { skuId: sku, quantity: 2 })
    const created = await api('/api/v1/orders', 'POST', {
        shippingAddress: '123 Demo Street', recipientName: 'Demo Buyer', recipientPhone: '0901234567',
        paymentMethod: 'VNPAY', selectedItemIds: [cart.items[0].id],
    }, randomUUID(), 201)
    assert.equal(created.total, null)
    assert.equal(created.priceFinalized, false)
    assert.equal(created.estimatedTotal, 300000)
    assert.equal(reservations.size, 0)
    await swaggerCapture('/api/v1/orders/{orderId}', created.id, '01-draft-order.png', '01 — Đơn mới: giá chính thức null, dự kiến 300.000 VND')

    price = 160000
    const first = await api('/api/v1/payments', 'POST', { orderId: created.id }, randomUUID())
    assert.equal(first.amount, 320000)
    assert.equal(new URL(first.paymentUrl).searchParams.get('vnp_Amount'), '32000000')
    assert.equal(await ipn(first, false), '00')
    const failed = await api('/api/v1/payments/' + first.paymentId)
    assert.equal(failed.status, 'FAILED')
    await swaggerCapture('/api/v1/payments/{id}', first.paymentId, '02-failed-attempt.png', '02 — Attempt thất bại: snapshot 320.000 VND, đơn chưa chốt giá')

    price = 170000
    const key = randomUUID()
    const second = await api('/api/v1/payments', 'POST', { orderId: created.id }, key)
    assert.equal(second.amount, 340000)
    assert.notEqual(first.paymentId, second.paymentId)
    assert.equal((await api('/api/v1/payments', 'POST', { orderId: created.id }, key)).paymentId, second.paymentId)
    price = 990000
    assert.equal(await ipn(second, true), '00')
    assert.equal(await ipn(second, true), '02')
    await ready(async () => (await api('/api/v1/payments/' + second.paymentId)).status === 'SUCCESS', 'Inventory commit')
    const paid = await api('/api/v1/orders/' + created.id)
    assert.equal(paid.total, 340000)
    assert.equal(paid.items[0].unitPrice, 170000)
    assert.equal(paid.priceFinalized, true)
    assert.equal(paid.status, 'PAID')
    assert.equal(committed, 1)
    await swaggerCapture('/api/v1/orders/{orderId}', created.id, '03-paid-frozen-price.png', '03 — Retry thành công: chốt 340.000 VND theo attempt, Catalog đã đổi nhưng không tính lại')

    assert.equal(await ipn(first, true), '00')
    assert.equal((await api('/api/v1/payments/' + first.paymentId)).status, 'REQUIRES_REVIEW')
    assert.equal((await api('/api/v1/orders/' + created.id)).total, 340000)
    await swaggerCapture('/api/v1/payments/{id}', first.paymentId, '04-late-funds-review.png', '04 — Success muộn của attempt cũ: cần đối soát, không chốt đè đơn đã trả tiền')
    assert.equal(committed, 1)
    await ready(async () => released === 1, 'Failed attempt release')
    const catalogPriceAfterFrozenAttempt = price

    // Hồi quy P1: tiền A đến muộn trong khi B còn PENDING phải chặn URL cả GET, replay key B và key mới.
    price = 160000
    const reviewCart = await api('/api/v1/cart/items', 'POST', { skuId: sku, quantity: 2 })
    const reviewOrder = await api('/api/v1/orders', 'POST', {
        shippingAddress: '123 Review Street', recipientName: 'Demo Buyer', recipientPhone: '0901234567',
        paymentMethod: 'VNPAY', selectedItemIds: [reviewCart.items[0].id],
    }, randomUUID(), 201)
    const oldAttempt = await api('/api/v1/payments', 'POST', { orderId: reviewOrder.id }, randomUUID())
    assert.equal(await ipn(oldAttempt, false), '00')
    const pendingKey = randomUUID()
    const pendingAttempt = await api('/api/v1/payments', 'POST', { orderId: reviewOrder.id }, pendingKey)
    assert.equal(await ipn(oldAttempt, true), '00')
    const reviewedPending = await api('/api/v1/payments/' + pendingAttempt.paymentId)
    assert.equal(reviewedPending.status, 'REQUIRES_REVIEW')
    assert.equal(reviewedPending.paymentUrl, null)
    const reviewedReplay = await api('/api/v1/payments', 'POST', { orderId: reviewOrder.id }, pendingKey)
    assert.equal(reviewedReplay.status, 'REQUIRES_REVIEW')
    assert.equal(reviewedReplay.paymentUrl, null)
    await api('/api/v1/payments', 'POST', { orderId: reviewOrder.id }, randomUUID(), 409)
    await swaggerCapture('/api/v1/payments/{id}', pendingAttempt.paymentId, '05-reviewed-order-blocks-pending-url.png',
        '05 — Attempt còn PENDING nhưng đơn có REVIEW: GET/replay không cấp URL trả tiền tiếp')

    // Hồi quy P2: COD đã thu tiền, kho hết hạn thì lưu REVIEW và giữ tiền, không PAID/giao hàng hoặc tự hoàn tiền.
    price = 25000
    const codCart = await api('/api/v1/cart/items', 'POST', { skuId: sku, quantity: 1 })
    const codOrder = await api('/api/v1/orders', 'POST', {
        shippingAddress: '123 COD Street', recipientName: 'Demo Buyer', recipientPhone: '0901234567',
        paymentMethod: 'COD', selectedItemIds: [codCart.items[0].id],
    }, randomUUID(), 201)
    expiredRefs.add(codOrder.id)
    const codReceipt = await api('/api/v1/orders/' + codOrder.id + '/status', 'PATCH',
        { status: 'PAID', reason: 'Fixture admin confirms COD funds' })
    assert.equal(codReceipt.status, 'PENDING_PAYMENT')
    assert.equal(codReceipt.fulfillmentStatus, 'PENDING')
    assert.equal(codReceipt.priceFinalized, true)
    await ready(async () => (await api('/api/v1/orders/' + codOrder.id)).fulfillmentStatus === 'REVIEW', 'COD review')
    const codReview = await api('/api/v1/orders/' + codOrder.id)
    assert.equal(codReview.status, 'CANCELLED')
    assert.equal(codReview.total, 25000)
    assert.equal(codReview.priceFinalized, true)
    await api('/api/v1/orders/' + codOrder.id + '/status', 'PATCH', { status: 'PROCESSING', reason: 'Must be blocked' }, undefined, 409)
    const codReplay = await api('/api/v1/orders/' + codOrder.id + '/status', 'PATCH', { status: 'PAID', reason: 'Replay receipt' })
    assert.equal(codReplay.fulfillmentStatus, 'REVIEW')
    await swaggerCapture('/api/v1/orders/{orderId}', codOrder.id, '06-cod-expired-stock-review.png',
        '06 — COD đã thu 25.000 VND nhưng kho hết hạn: CANCELLED/REVIEW, giữ giá để đối soát và chặn giao hàng')
    assert.equal(committed, 1)
    writeFileSync(join(evidence, 'http-transcript.json'), JSON.stringify({
        scope: 'Real Commerce HTTP/JWT/Flyway/PostgreSQL; Catalog/Fulfillment/OAuth/VNPAY fixtures. Not merchant payment.',
        assertions: { committedExactlyOnce: committed === 1,
            releasedOldAttemptOnly: reservations.get(first.paymentId).status === 'RELEASED' && reservations.get(second.paymentId).status === 'CONFIRMED',
            finalAmount: paid.total, catalogPriceAfterUrl: catalogPriceAfterFrozenAttempt, duplicateIpnCode: '02',
            reviewedPendingUrlSuppressed: reviewedPending.paymentUrl === null && reviewedReplay.paymentUrl === null,
            codExpiredStockRequiresReview: codReview.fulfillmentStatus === 'REVIEW', codFundsPreserved: codReview.total === 25000 },
        requests: transcript,
    }, null, 2) + '\n')
    console.log('PASS: 6 Swagger screenshots; frozen price, REVIEW replay/GET and expired COD safeguards verified')
} finally {
    await browser?.close()
    if (app && app.exitCode === null) {
        app.kill()
        await Promise.race([new Promise(done => app.once('exit', done)), delay(10000)])
    }
    fixture.closeAllConnections()
    if (fixture.listening) await new Promise(done => fixture.close(done))
    if (databaseCreated) pg('dropdb', ['--force', database])
    log.end()
}
