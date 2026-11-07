/**
 * Chụp bằng chứng browser → Gateway → Auction bằng code production, trên các cổng loopback riêng.
 * IAM là JWKS/RS256 sinh trong RAM, repository PUBLIC/PRIVATE là fixture test; không đọc .env hoặc DB nhóm.
 * Đầu vào: Gateway JAR, test classpath Auction đã build và Playwright của frontend-web.
 * Đầu ra: screenshot/JSON đã loại token tại docs/test-evidence/tt-s2-2; log JVM nằm trong target bị Git bỏ qua.
 * Mọi assertion lấy kết quả request/frame thật. finally dừng browser, JVM và HTTP fixture kể cả khi test lỗi.
 */
import assert from 'node:assert/strict'
import { generateKeyPairSync, sign } from 'node:crypto'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises'
import { createWriteStream } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(path.join(root, 'frontend-web/package.json'))
const { chromium } = require('@playwright/test')
const output = path.join(root, 'docs/test-evidence/tt-s2-2')
const gateway = 'http://127.0.0.1:18460'
const allowed = 'http://127.0.0.1:18461'
const denied = 'http://127.0.0.1:18462'
const auctionUrl = 'http://127.0.0.1:18464'
const issuer = `${allowed}/realms/evidence`
const publicId = '11111111-1111-4111-8111-111111111111'
const privateId = '22222222-2222-4222-8222-222222222222'
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'evidence', alg: 'RS256', use: 'sig' }
const children = []
const servers = []
const results = []
const now = () => new Date().toLocaleString('en-GB', { timeZone: 'Asia/Ho_Chi_Minh' })
let browser

/** Ký JWT test; private key/token chỉ tồn tại trong tiến trình và không được đưa vào ảnh hoặc JSON kết quả. */
function token({ seconds = 300, roles = ['ROLE_BUYER', 'AUCTION.BID'], signer = privateKey } = {}) {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
    const payload = `${encode({ alg: 'RS256', kid: 'evidence' })}.${encode({
        iss: issuer, sub: '44444444-4444-4444-8444-444444444444',
        iat: Math.floor(Date.now() / 1000) - 300, exp: Math.floor(Date.now() / 1000) + seconds,
        realm_access: { roles },
    })}`
    return `${payload}.${sign('RSA-SHA256', Buffer.from(payload), signer).toString('base64url')}`
}

/** Trang chẩn đoán chỉ hiển thị kết quả assertion, không giả giao diện bid engine hoặc dữ liệu production. */
const html = `<!doctype html><html lang="vi"><meta charset="utf-8"><title>TT-S2-2 Security evidence</title>
<style>body{font:17px/1.5 Segoe UI,sans-serif;color:#172033;background:#f4f6fa;margin:0;padding:36px}main{max-width:1200px;margin:auto}h1{font-size:30px;margin:0 0 8px}.note{background:#fff3cd;padding:14px 18px;border-left:5px solid #b58100;border-radius:6px}table{width:100%;border-collapse:collapse;background:white;margin:22px 0}th,td{text-align:left;padding:13px;border-bottom:1px solid #e0e5ed;vertical-align:top}th{background:#172033;color:white}.pass{color:#11743e;font-weight:700}.fail{color:#b42318;font-weight:700}small{color:#58677d}code{font:14px Consolas,monospace;overflow-wrap:anywhere}footer{margin-top:18px;color:#58677d}</style>
<main><small>NEXUS · TT-S2-2 · Browser integration evidence</small><h1 id="title">Gateway, WebSocket STOMP & Service security</h1>
<p class="note"><b>Phạm vi:</b> Browser (Edge), Gateway Netty và Auction Tomcat/STOMP chạy thật trên loopback. JWKS/RS256 và repository Auction là fixture test, không phải realm/DB nhóm. Không kiểm chứng nghiệp vụ ghi bid hoặc tạo order.</p>
<p id="context"></p><table><thead><tr><th style="width:8%">Kết quả</th><th style="width:32%">Tình huống</th><th>Bằng chứng quan sát</th></tr></thead><tbody id="rows"></tbody></table><footer id="footer"></footer></main>
<script>window.render=(items,title,meta)=>{document.querySelector('#title').textContent=title;document.querySelector('#context').textContent=meta;const body=document.querySelector('#rows');body.replaceChildren();for(const item of items){const tr=document.createElement('tr');for(const value of [item.pass?'PASS':'FAIL',item.name,item.detail]){const td=document.createElement('td');td.textContent=value;tr.append(td)}tr.children[0].className=item.pass?'pass':'fail';body.append(tr)}document.querySelector('#footer').textContent='Kết quả từ assertion thật · token/secret không được hiển thị · '+items.length+' kiểm tra'}</script></html>`

/** Hai HTTP origin cố định giúp browser tự gửi Origin thật; không giả Origin bằng header từ JavaScript. */
async function serve(url) {
    const server = createServer((req, res) => {
        if (req.url === '/certs') {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ keys: [jwk] }))
        } else {
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
            res.end(html)
        }
    })
    servers.push(server)
    await new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(Number(new URL(url).port), '127.0.0.1', resolve)
    })
}

/** Khởi động JVM dạng child process ẩn, dùng argfile để classpath Windows dài không vượt giới hạn command line. */
async function launch(name, args) {
    const directory = path.join(root, `backend/${name}/target`)
    const argfile = path.join(directory, 'security-evidence.args')
    const common = ['--spring.cloud.config.enabled=false', '--eureka.client.enabled=false',
        '--spring.flyway.enabled=false', '--logging.level.root=WARN', '--spring.main.banner-mode=off',
        `--app.keycloak.jwk-set-uri=${allowed}/certs`, `--app.keycloak.issuer=${issuer}`,
        `--app.web.allowed-origins=${allowed}`]
    await writeFile(argfile, [...args, ...common].map(arg => JSON.stringify(arg.replaceAll('\\', '/'))).join('\n'))
    const log = createWriteStream(path.join(directory, 'security-evidence.log'))
    const child = spawn('java', [`@${argfile}`], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    child.stdout.pipe(log)
    child.stderr.pipe(log)
    children.push({ child, log })
    child.on('error', error => console.error(`${name}: ${error.message}`))
}

/** Chỉ thăm health loopback; deadline hữu hạn và phát hiện tiến trình chết sớm, không đợi treo CI. */
async function ready(url) {
    const deadline = Date.now() + 90000
    while (Date.now() < deadline) {
        if (children.some(({ child }) => child.exitCode !== null)) throw new Error('JVM dừng sớm; xem target/security-evidence.log')
        try { if ((await fetch(`${url}/actuator/health`, { signal: AbortSignal.timeout(1000) })).ok) return } catch {}
        await new Promise(resolve => setTimeout(resolve, 250))
    }
    throw new Error(`Health timeout: ${url}`)
}

/** Chạy WebSocket API của browser, ghi loại frame và close code; không trả token/payload auth ra report. */
async function socketProbe(page, options) {
    return page.evaluate(({ gateway, publicId, privateId, ...options }) => new Promise(resolve => {
        let connected = false
        let finished = false
        const frames = []
        const socket = new WebSocket(gateway.replace('http:', 'ws:') + '/ws/auctions', 'v12.stomp')
        const done = extra => {
            if (finished) return
            finished = true
            clearTimeout(timer)
            resolve({ connected, frames, ...extra })
            if (socket.readyState === WebSocket.OPEN) socket.close()
        }
        const timer = setTimeout(() => done({ timeout: true }), 12000)
        socket.onopen = () => socket.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n' +
            (options.token ? 'Authorization:Bearer ' + options.token + '\n' : '') + '\n\0')
        socket.onmessage = async event => {
            const frame = String(event.data)
            const type = frame.split('\n')[0].trim()
            if (!type) return
            frames.push(type)
            if (type === 'CONNECTED') {
                connected = true
                if (options.action === 'connect') done({})
                else if (options.action === 'inject') socket.send(`SEND\ndestination:/topic/auctions/${publicId}\n\nforged\0`)
                else if (options.action === 'private') socket.send(`SUBSCRIBE\nid:private\ndestination:/topic/auctions/${privateId}\n\n\0`)
                else if (options.action === 'bid') socket.send(`SEND\ndestination:/app/auctions/${publicId}/bids\n\n{}\0`)
                else if (options.action === 'broadcast') {
                    socket.send(`SUBSCRIBE\nid:public\ndestination:/topic/auctions/${publicId}\n\n\0`)
                    const headers = { Authorization: 'Bearer ' + options.token, 'X-Nexus-Request': '1' }
                    try {
                        let subscribed = false
                        for (let attempt = 0; attempt < 40; attempt++) {
                            const response = await fetch(gateway + '/api/v1/auctions/evidence/subscriptions', { headers })
                            if ((await response.json()).subscriptions > 0) { subscribed = true; break }
                            await new Promise(resolve => setTimeout(resolve, 100))
                        }
                        if (!subscribed) return done({ error: 'Broker chưa ghi nhận subscription' })
                        const response = await fetch(gateway + '/api/v1/auctions/evidence/broadcast', { method: 'POST', headers })
                        if (!response.ok) done({ error: 'Fixture publish HTTP ' + response.status })
                    } catch { done({ error: 'Fixture publish thất bại' }) }
                }
            } else if (type === 'MESSAGE') done({ fixtureEvent: frame.includes('SECURITY_TEST_FIXTURE') })
        }
        socket.onerror = () => { /* Browser không cho JS đọc HTTP status handshake; onclose xác nhận kết nối bị chặn. */ }
        socket.onclose = event => done({ closeCode: event.code })
    }), { gateway, publicId, privateId, ...options })
}

/** Chỉ lưu giá trị quan sát không nhạy cảm; thất bại vẫn ghi kết quả để ảnh/report không che lỗi. */
function record(group, name, pass, detail) {
    const item = { group, name, pass: Boolean(pass), detail }
    results.push(item)
    console.log(`${item.pass ? 'PASS' : 'FAIL'} ${name}: ${detail}`)
}

try {
    await mkdir(output, { recursive: true })
    await serve(allowed)
    await serve(denied)
    const cp = (await readFile(path.join(root, 'backend/auction-service/target/evidence-classpath.txt'), 'utf8')).trim()
    await launch('auction-service', ['-cp', [path.join(root, 'backend/auction-service/target/test-classes'),
        path.join(root, 'backend/auction-service/target/classes'), cp].join(path.delimiter),
        'com.nexus.auction.evidence.AuctionSecurityEvidenceServer', '--server.address=127.0.0.1', '--server.port=18464'])
    await launch('api-gateway', ['-jar', path.join(root, 'backend/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar'),
        '--server.address=127.0.0.1', '--server.port=18460', `--AUCTION_WS_URL=ws://127.0.0.1:18464`,
        `--AUCTION_SERVICE_URL=${auctionUrl}`, '--app.auth.cookie-secure=false'])
    await Promise.all([ready(gateway), ready(auctionUrl)])
    browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge', headless: true })
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
    const page = await context.newPage()
    await page.goto(allowed)
    const evil = await context.newPage()
    await evil.goto(denied)

    // CORS được browser thực thi; APIRequestContext bổ sung status/header của preflight để ảnh dễ kiểm tra.
    const corsOptions = origin => ({ headers: { Origin: origin, 'Access-Control-Request-Method': 'GET',
        'Access-Control-Request-Headers': 'X-Nexus-Request' } })
    const allowedPreflight = await context.request.fetch(gateway + '/actuator/health', { method: 'OPTIONS', ...corsOptions(allowed) })
    const goodFetch = await page.evaluate(async gateway => {
        const response = await fetch(gateway + '/actuator/health', { headers: { 'X-Nexus-Request': '1' }, credentials: 'include' })
        return { status: response.status, health: (await response.json()).status }
    }, gateway)
    record('cors', 'Origin tin cậy: browser đọc được API', allowedPreflight.status() === 200 && goodFetch.health === 'UP',
        `Origin ${allowed} · OPTIONS ${allowedPreflight.status()} · GET ${goodFetch.status} · health=${goodFetch.health}`)
    const allowedHeader = allowedPreflight.headers()['access-control-allow-origin']
    const credentialHeader = allowedPreflight.headers()['access-control-allow-credentials']
    record('cors', 'CORS credential dùng origin cụ thể', allowedHeader === allowed && credentialHeader === 'true',
        `Access-Control-Allow-Origin: ${allowedHeader}; Allow-Credentials: ${credentialHeader}`)
    const deniedPreflight = await context.request.fetch(gateway + '/actuator/health', { method: 'OPTIONS', ...corsOptions(denied) })
    const evilFetch = await evil.evaluate(async gateway => {
        try { await fetch(gateway + '/actuator/health', { headers: { 'X-Nexus-Request': '1' }, credentials: 'include' }); return 'readable' }
        catch { return 'blocked' }
    }, gateway)
    record('cors', 'Origin lạ: browser bị chặn đọc API', deniedPreflight.status() === 403 && evilFetch === 'blocked',
        `Origin ${denied} · OPTIONS ${deniedPreflight.status()} · browser fetch=${evilFetch}`)

    const protectedHttp = await context.request.get(gateway + '/api/v1/orders')
    record('auth', 'HTTP nghiệp vụ vẫn bắt JWT', protectedHttp.status() === 401, `GET /api/v1/orders không token → ${protectedHttp.status()}`)
    const absent = await socketProbe(page, { action: 'connect' })
    record('stomp', 'Thiếu JWT bị từ chối sau upgrade', !absent.connected && absent.frames.includes('ERROR') && absent.closeCode,
        `Frames=${absent.frames.join(' → ')} · close=${absent.closeCode}`)
    const valid = token()
    const connected = await socketProbe(page, { action: 'connect', token: valid })
    record('stomp', 'Upgrade qua Gateway và CONNECT JWT', connected.connected, `Browser → Gateway :18460 → Auction :18464 · ${connected.frames.join(' → ')}`)
    await context.addCookies([{ name: 'nexus_access', value: valid, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Strict' }])
    const cookie = await socketProbe(page, { action: 'connect' })
    record('stomp', 'Cookie HttpOnly qua Gateway', cookie.connected, `CONNECT không mang native bearer · ${cookie.frames.join(' → ')} · cookie HttpOnly=true`)
    await context.clearCookies()
    const wrongKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey
    for (const [label, value] of [['JWT sai chữ ký', token({ signer: wrongKey })], ['JWT hết hạn', token({ seconds: -120 })]]) {
        const observed = await socketProbe(page, { action: 'connect', token: value })
        record('stomp', label + ' bị từ chối', !observed.connected && observed.frames.includes('ERROR') && observed.closeCode,
            `Frames=${observed.frames.join(' → ')} · close=${observed.closeCode}`)
    }
    const broadcast = await socketProbe(page, { action: 'broadcast', token: valid })
    record('stomp', 'SUBSCRIBE PUBLIC nhận server broadcast', broadcast.connected && broadcast.fixtureEvent,
        `${broadcast.frames.join(' → ')} · type=SECURITY_TEST_FIXTURE · không ghi bid DB`)
    for (const [label, action, value] of [['Chặn SEND giả vào topic', 'inject', valid],
        ['Chặn SUBSCRIBE phiên PRIVATE', 'private', valid], ['Chặn SEND thiếu AUCTION.BID', 'bid', token({ roles: ['ROLE_SELLER'] })]]) {
        const observed = await socketProbe(page, { action, token: value })
        record('stomp', label, observed.connected && observed.frames.includes('ERROR') && observed.closeCode,
            `Frames=${observed.frames.join(' → ')} · close=${observed.closeCode}`)
    }
    const wrongOrigin = await socketProbe(evil, { action: 'connect', token: valid })
    record('cors', 'WebSocket từ Origin lạ bị chặn', !wrongOrigin.connected && !wrongOrigin.frames.includes('CONNECTED') && wrongOrigin.closeCode,
        `Browser Origin ${denied} · CONNECTED=false · close=${wrongOrigin.closeCode}`)
    const idle = await socketProbe(page, { action: 'idle', token: token({ seconds: 4 }) })
    record('stomp', 'Socket nhàn rỗi bị đóng khi JWT hết hạn', idle.connected && idle.closeCode === 1008,
        `CONNECT hợp lệ → không gửi thêm frame → close=${idle.closeCode}`)

    // Báo cáo service security lấy trực tiếp từ JUnit XML, tách rõ với các assertion browser bên trên.
    const reports = [
        ['common', 'com.nexus.common.security.ServiceIdentityTest', 'ServiceIdentity: bắt buộc azp + privilege'],
        ['commerce-service', 'com.nexus.commerce.config.ServiceIdentitySecurityTest', 'Commerce: chặn user/sai service ở API nội bộ'],
        ['commerce-service', 'com.nexus.commerce.client.HttpInventoryClientTest', 'Commerce → Inventory: dùng service token'],
        ['fulfillment-service', 'com.nexus.fulfillment.controller.InventoryControllerTest', 'Inventory: service đúng quyền được phép, user token bị chặn'],
    ]
    for (const [module, name, label] of reports) {
        const xml = await readFile(path.join(root, `backend/${module}/target/surefire-reports/TEST-${name}.xml`), 'utf8')
        const attrs = xml.match(/<testsuite\b([^>]+)>/)[1]
        const get = key => Number(attrs.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1] ?? -1)
        const passed = get('tests') > 0 && get('failures') === 0 && get('errors') === 0 && get('skipped') === 0
        record('service', label, passed, `JUnit ${module}: ${get('tests')} tests · failures=${get('failures')} · errors=${get('errors')} · skipped=${get('skipped')}`)
    }
    // Tổng hợp trực tiếp mọi JUnit XML của lần chạy Maven; ghi rõ skip thay vì coi test PostgreSQL là đã chạy.
    const validation = []
    for (const module of ['common', 'api-gateway', 'auction-service', 'commerce-service', 'fulfillment-service']) {
        const directory = path.join(root, `backend/${module}/target/surefire-reports`)
        const summary = { module, tests: 0, failures: 0, errors: 0, skipped: 0 }
        for (const file of (await readdir(directory)).filter(name => /^TEST-.*\.xml$/.test(name))) {
            const xml = await readFile(path.join(directory, file), 'utf8')
            const attrs = xml.match(/<testsuite\b([^>]+)>/)[1]
            for (const key of ['tests', 'failures', 'errors', 'skipped']) {
                summary[key] += Number(attrs.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1] ?? 0)
            }
        }
        validation.push(summary)
        record('automation', module, summary.tests > summary.skipped && summary.failures === 0 && summary.errors === 0,
            `${summary.tests - summary.skipped} passed · ${summary.skipped} skipped · failures=${summary.failures} · errors=${summary.errors}`)
    }
    await writeFile(path.join(output, 'validation-summary.json'), JSON.stringify({ modules: validation,
        skippedReason: 'Commerce PostgreSQL suites require NEXUS_TEST_DB_URL; local Docker daemon unavailable',
        scope: 'JUnit XML aggregate; not a claim that skipped database tests or production IAM were verified' }, null, 2) + '\n')
    const meta = `Chạy lúc ${now()} (Asia/Ho_Chi_Minh) · loopback riêng · không dùng dữ liệu/credential nhóm`
    for (const [name, items, title] of [
        ['01-cors-origin.png', results.filter(r => r.group === 'cors'), 'AC4 · CORS / Origin an toàn cho frontend'],
        ['02-websocket-stomp-auth.png', results.filter(r => r.group === 'stomp' || r.group === 'auth'), 'AC1–3 · WebSocket routing, STOMP JWT & frame authorization'],
        ['03-service-security.png', results.filter(r => r.group === 'service'), 'AC5–6 · Service identity / security — kết quả JUnit'],
        ['04-backend-test-summary.png', results.filter(r => r.group === 'automation'), 'AC6 · Tổng hợp kiểm thử backend — JUnit XML'],
    ]) {
        await page.evaluate(({ items, title, meta }) => window.render(items, title, meta), { items, title, meta })
        await page.screenshot({ path: path.join(output, name), fullPage: true })
    }
    await writeFile(path.join(output, 'browser-results.json'), JSON.stringify({ executedAt: new Date().toISOString(),
        timezone: 'Asia/Ho_Chi_Minh', fixture: 'Local JWKS/RS256 and AuctionRepository; no production DB/realm',
        results }, null, 2) + '\n')
    assert(results.every(item => item.pass), 'Có kiểm tra thất bại; xem browser-results.json và screenshot, không coi là nghiệm thu đạt')
} finally {
    if (browser) await browser.close()
    for (const { child, log } of children) {
        child.kill()
        await Promise.race([new Promise(resolve => child.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 3000))])
        log.end()
    }
    await Promise.all(servers.map(server => new Promise(resolve => { server.closeAllConnections(); server.close(resolve) })))
}
