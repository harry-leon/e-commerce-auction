// Smoke ERD V2: dựng PostgreSQL/Keycloak tạm, chạy JAR đã build và kiểm chứng luồng qua Gateway.
// Chỉ tạo container có tên ngẫu nhiên và DB tạm; không đọc .env, không sửa volume hoặc tài khoản nhóm.
// Chạy: node scripts/smoke-erd-v2.mjs sau khi mvn package các module user/catalog/commerce/notification/gateway.
import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { createWriteStream, mkdtempSync, existsSync, copyFileSync, unlinkSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { createServer } from 'node:net'

const suffix = randomBytes(5).toString('hex')
const secret = randomBytes(24).toString('hex')
const containers = []
const processes = []
const artifacts = []
const logs = mkdtempSync(join(tmpdir(), 'nexus-erd-v2-'))
const docker = (args, env = {}) => execFileSync('docker', args, {
    encoding: 'utf8', windowsHide: true, env: { ...process.env, ...env }, stdio: ['pipe', 'pipe', 'pipe'],
}).trim()
const sleep = (ms) => new Promise((done) => setTimeout(done, ms))
const freePort = () => new Promise((done, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
        const port = server.address().port
        server.close(() => done(port))
    })
})

// Đợi có deadline; lỗi khởi động được nêu bằng tên thành phần, không in credential hoặc response chứa JWT.
async function ready(label, check, timeout = 120000) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
        if (await check().catch(() => false)) return
        await sleep(1000)
    }
    throw new Error(label + ' was not ready; logs: ' + logs)
}

async function request(base, path, method = 'GET', body, token, expected = 200) {
    const response = await fetch(base + path, {
        method,
        headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
            ...(token ? { Authorization: 'Bearer ' + token } : {}) },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(20000),
    })
    assert.equal(response.status, expected, method + ' ' + path + ': unexpected status')
    return response.status === 204 ? null : response.json()
}

// Mỗi JAR nhận env riêng, tắt discovery/config; memory hữu hạn để có thể chạy nhiều module trên máy dev.
function launch(module, port, env) {
    const artifact = resolve('backend', module, 'target', module + '-0.0.1-SNAPSHOT.jar')
    assert.ok(existsSync(artifact), 'Build JAR first: ' + module)
    // Windows khóa JAR đang chạy; dùng snapshot riêng để Maven có thể package lại mà không đụng file bị khóa.
    const snapshot = join(logs, module + '.jar')
    copyFileSync(artifact, snapshot)
    artifacts.push(snapshot)
    const output = createWriteStream(join(logs, module + '.log'))
    const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
    const child = spawn(java, ['-Xms64m', '-Xmx256m', '-jar', snapshot,
        '--debug=false', '--logging.level.root=INFO', '--logging.level.web=INFO',
        '--logging.level.org.springframework=INFO', '--logging.level.sql=INFO'], {
        windowsHide: true,
        env: { ...process.env, SERVER_PORT: String(port), SYSTEM_ENV: 'dev',
            SPRING_CLOUD_CONFIG_ENABLED: 'false', SPRING_CLOUD_CONFIG_IMPORT_CHECK_ENABLED: 'false',
            SPRING_CONFIG_IMPORT: '', EUREKA_ENABLED: 'false',
            LOGGING_LEVEL_COM_NEXUS: 'INFO', LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB: 'INFO',
            LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY: 'INFO', ...env },
        stdio: ['ignore', 'pipe', 'pipe'],
    })
    child.stdout.pipe(output)
    child.stderr.pipe(output)
    processes.push(child)
    child.on('error', (error) => output.write(error.message))
    return 'http://127.0.0.1:' + port
}

try {
    const pgName = 'nexus-erd-pg-' + suffix
    const pg = docker(['run', '-d', '--name', pgName, '-e', 'POSTGRES_PASSWORD', '-p', '127.0.0.1::5432',
        'postgres:16-alpine'], { POSTGRES_PASSWORD: secret })
    containers.push(pg)
    const pgPort = Number(docker(['port', pg, '5432/tcp']).split(':').at(-1))
    await ready('PostgreSQL', async () => {
        try { docker(['exec', pg, 'pg_isready', '-U', 'postgres']); return true } catch { return false }
    })
    for (const database of ['nexus_user', 'nexus_catalog', 'nexus_commerce', 'nexus_notification']) {
        docker(['exec', pg, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE ' + database])
    }
    // Kiểm tra V5 trên schema V4 có hồ sơ cũ; không chỉ kiểm tra database mới trống.
    docker(['exec', pg, 'psql', '-U', 'postgres', '-c', 'CREATE DATABASE nexus_upgrade'])
    const migrationDir = resolve('backend/user-service/src/main/resources/db/migration')
    const previous = ['V1__init.sql', 'V2__init_schema.sql', 'V3__iam_enhancements.sql', 'V4__split_iam_and_reputation.sql']
        .map((name) => readFileSync(join(migrationDir, name), 'utf8')).join('\n')
    const fixture = "INSERT INTO users (id,email,full_name,keycloak_id,created_at) VALUES ('11111111-1111-4111-8111-111111111111','upgrade@example.com','Upgrade','22222222-2222-4222-8222-222222222222','2026-01-01');"
    execFileSync('docker', ['exec', '-i', pg, 'psql', '-U', 'postgres', '-d', 'nexus_upgrade', '-v', 'ON_ERROR_STOP=1'], {
        input: previous + '\n' + fixture + '\n' + readFileSync(join(migrationDir, 'V5__align_user_profile_with_erd_v2.sql'), 'utf8'),
        encoding: 'utf8', windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    })
    assert.equal(docker(['exec', pg, 'psql', '-U', 'postgres', '-d', 'nexus_upgrade', '-tAc',
        "SELECT count(*) FROM users WHERE id='11111111-1111-4111-8111-111111111111' AND keycloak_id='22222222-2222-4222-8222-222222222222' AND updated_at=created_at"]), '1')
    console.log('PASS V4-to-V5 upgrade preserves existing profile ID, IAM link and timestamps')

    const kcPort = await freePort()
    const keycloak = docker(['run', '-d', '--name', 'nexus-erd-iam-' + suffix,
        '-p', '127.0.0.1:' + kcPort + ':8080', '-e', 'KEYCLOAK_ADMIN_CLIENT_SECRET',
        '-e', 'JAVA_OPTS_APPEND=-Xms64m -Xmx384m',
        '-v', resolve('deploy/keycloak') + ':/opt/keycloak/data/import:ro',
        'quay.io/keycloak/keycloak:24.0.5', 'start-dev', '--import-realm'],
        { KEYCLOAK_ADMIN_CLIENT_SECRET: secret })
    containers.push(keycloak)
    const iam = 'http://localhost:' + kcPort
    const issuer = iam + '/realms/nexus-realm'
    await ready('Keycloak realm import', async () => (await fetch(issuer + '/.well-known/openid-configuration')).ok)
    console.log('PASS isolated PostgreSQL and Keycloak realm import')

    const ports = await Promise.all([freePort(), freePort(), freePort(), freePort(), freePort()])
    const shared = { KEYCLOAK_URL: iam, KEYCLOAK_ISSUER: issuer, KEYCLOAK_ADMIN_CLIENT_SECRET: secret,
        DB_USERNAME: 'postgres', DB_PASSWORD: secret }
    // Audit thuộc notification theo ERD; User cần URL thật để kiểm tra service token và lịch sử qua HTTP.
    const notification = launch('notification-service', ports[4], { ...shared, DB_URL: 'jdbc:postgresql://127.0.0.1:' + pgPort + '/nexus_notification' })
    const user = launch('user-service', ports[0], { ...shared, NOTIFICATION_SERVICE_URL: notification, DB_URL: 'jdbc:postgresql://127.0.0.1:' + pgPort + '/nexus_user' })
    const catalog = launch('catalog-service', ports[1], { ...shared, DB_URL: 'jdbc:postgresql://127.0.0.1:' + pgPort + '/nexus_catalog' })
    const commerce = launch('commerce-service', ports[2], { ...shared, CATALOG_SERVICE_URL: catalog,
        DB_URL: 'jdbc:postgresql://127.0.0.1:' + pgPort + '/nexus_commerce' })
    const gateway = launch('api-gateway', ports[3], { ...shared, USER_SERVICE_URL: user, USER_PROFILE_URL: user,
        CATALOG_SERVICE_URL: catalog, COMMERCE_SERVICE_URL: commerce })
    await Promise.all([['Notification/Flyway', notification], ['User/Flyway V1-V6', user], ['Catalog', catalog], ['Commerce', commerce], ['Gateway', gateway]]
        .map(([label, url]) => ready(label, async () => (await fetch(url + '/actuator/health')).ok)))
    console.log('PASS Flyway migrations and Hibernate schema validation')
    const base = gateway + '/api/v1'
    const email = 'erd-' + suffix + '@example.com'
    const password = 'ErdVerification1!'
    const profile = await request(base, '/auth/register', 'POST', { email, password, fullName: 'ERD Verification' }, undefined, 201)
    assert.ok(profile.id)
    const login = await request(base, '/auth/login', 'POST', { email, password })
    assert.ok(login.accessToken && login.refreshToken && login.user.id)
    assert.ok(login.user.roles.includes('BUYER'))
    const token = login.accessToken
    const me = await request(base, '/users/me', 'GET', undefined, token)
    assert.equal(me.id, profile.id)
    assert.equal(me.email, email)
    const refreshed = await request(base, '/auth/refresh', 'POST', { refreshToken: login.refreshToken })
    assert.ok(refreshed.accessToken && refreshed.user)
    const updated = await request(base, '/users/me', 'PUT', { fullName: 'Updated ERD', gender: 'OTHER' }, token)
    assert.equal(updated.fullName, 'Updated ERD')
    console.log('PASS register, BUYER assignment, login, refresh and profile update')

    // Gọi thẳng service bằng header giả phải bị 401; privilege USER.UPDATE không đủ để gán ADMIN.
    const spoof = await fetch(user + '/api/v1/users/me', { headers: { 'X-User-Id': profile.id, 'X-User-Roles': 'ROLE_ADMIN' } })
    assert.equal(spoof.status, 401)
    await request(base, '/users/' + profile.id + '/roles', 'PUT', ['ADMIN'], token, 403)
    await request(base, '/categories', 'POST', { name: 'Forbidden', status: 'ACTIVE' }, token, 403)
    console.log('PASS forged headers and buyer privilege escalation denied')

    // Chỉ cấp ADMIN cho fixture trong Keycloak tạm để kiểm tra CRUD/Cart với token thật.
    const adminResponse = await fetch(issuer + '/protocol/openid-connect/token', {
        method: 'POST', body: new URLSearchParams({ grant_type: 'client_credentials',
            client_id: 'nexus-admin-cli', client_secret: secret }),
    })
    assert.equal(adminResponse.status, 200)
    const adminToken = (await adminResponse.json()).access_token
    const adminBase = iam + '/admin/realms/nexus-realm'
    const adminRole = await request(adminBase, '/roles/ROLE_ADMIN', 'GET', undefined, adminToken)
    await request(adminBase, '/users/' + profile.id + '/role-mappings/realm', 'POST', [adminRole], adminToken, 204)
    const adminLogin = await request(base, '/auth/login', 'POST', { email, password })
    const appAdmin = adminLogin.accessToken
    const roleList = await request(base, '/roles', 'GET', undefined, appAdmin)
    assert.ok(roleList.some((role) => role.code === 'ADMIN'))
    assert.ok(!roleList.some((role) => role.code === 'PROFILE_WRITER'))
    const createdRole = await request(base, '/roles', 'POST', { code: 'SMOKE_' + suffix.toUpperCase(),
        name: 'Smoke role', privilegeCodes: ['PROFILE.VIEW'] }, appAdmin, 201)
    assert.ok(createdRole.privileges.includes('PROFILE.VIEW'))
    const updatedRole = await request(base, '/roles/' + createdRole.id, 'PUT', {
        name: 'Updated smoke role', privilegeCodes: ['PROFILE.UPDATE'] }, appAdmin)
    assert.deepEqual(updatedRole.privileges, ['PROFILE.UPDATE'])
    await request(base, '/roles/' + createdRole.id, 'DELETE', undefined, appAdmin)
    console.log('PASS Role API CRUD through Keycloak without local IAM tables')
    const category = (await request(base, '/categories', 'POST', { name: 'ERD ' + suffix, status: 'ACTIVE' }, appAdmin, 201)).data
    const product = (await request(base, '/products', 'POST', { name: 'ERD Product', categoryId: category.id,
        auctionEnabled: false, skus: [{ skuCode: 'ERD-' + suffix, price: 125000, status: 'ACTIVE' }], images: [] }, appAdmin, 201)).data
    await request(base, '/products/' + product.id + '/approve', 'POST', undefined, appAdmin)
    const sku = product.skus[0]
    const snapshot = (await request(base, '/products/skus/' + sku.id)).data
    assert.equal(snapshot.price, 125000)
    assert.equal(snapshot.stockQuantity, null)
    const cart = (await request(base, '/cart/items', 'POST', { skuId: sku.id, quantity: 2 }, appAdmin)).data
    assert.equal(cart.items[0].unitPrice, 125000)
    assert.equal(cart.items[0].stockAvailable, null)
    assert.equal(cart.items[0].isAvailable, true)
    console.log('PASS Keycloak composites, Catalog SKU lookup and real Cart pricing')

    await request(base, '/auth/reset-password', 'POST', {}, undefined, 410)
    await request(base, '/auth/change-password', 'POST', { currentPassword: password, newPassword: 'ErdChanged1!' }, appAdmin)
    await request(base, '/auth/login', 'POST', { email, password }, undefined, 401)
    await request(base, '/auth/login', 'POST', { email, password: 'ErdChanged1!' })
    console.log('PASS IAM password change and legacy reset-token retirement')

    const schema = docker(['exec', pg, 'psql', '-U', 'postgres', '-d', 'nexus_user', '-tAc',
        "SELECT string_agg(column_name, ',') FROM information_schema.columns WHERE table_name='users'"])
    assert.ok(schema.includes('updated_at') && schema.includes('keycloak_id'))
    assert.ok(!schema.includes('password_hash') && !schema.includes('token_version'))
    assert.equal(docker(['exec', pg, 'psql', '-U', 'postgres', '-d', 'nexus_user', '-tAc',
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='audit_logs'"]), '0')
    const history = await request(base, '/users/' + profile.id + '/activity-history', 'GET', undefined, appAdmin)
    assert.ok(history.content.some((event) => event.action === 'CREATE_PROFILE'))
    assert.equal(docker(['exec', pg, 'psql', '-U', 'postgres', '-d', 'nexus_notification', '-tAc',
        "SELECT count(*) FROM event_logs WHERE source_service='user-service' AND payload->>'action'='CREATE_PROFILE'"]), '1')
    console.log('PASS final ERD schema and profile audit persisted in notification.event_logs')
    console.log('ERD V2 SMOKE PASSED')
} catch (error) {
    console.error(error.message)
    console.error('Inspect service logs at ' + logs)
    process.exitCode = 1
} finally {
    // Chỉ dừng process/container được chính lần kiểm tra này tạo; không down Compose hoặc xóa volume nhóm.
    for (const child of processes) child.kill()
    await Promise.all(processes.map((child) => child.exitCode !== null ? Promise.resolve() :
        Promise.race([new Promise((done) => child.once('exit', done)), sleep(5000)])))
    for (const artifact of artifacts) {
        try { unlinkSync(artifact) } catch { console.error('JAR snapshot cleanup failed: ' + artifact) }
    }
    for (const id of containers.reverse()) {
        try { docker(['rm', '-f', id]) } catch { console.error('Cleanup container failed: ' + id) }
    }
}
