import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import net from "node:net";
import http from "node:http";
import assert from "node:assert/strict";
import { spawn, execFileSync } from "node:child_process";
import { parseArgs } from "node:util";
import { createHash, randomUUID } from "node:crypto";
import { createIamFixture, createFlowFixture, literal as q } from "./shipping-flow/fixtures.mjs";
import { createCarrierFixture, exerciseDispatch } from "./shipping-flow/dispatch.mjs";

/**
 * Ba JVM + PostgreSQL tạm + Keycloak container riêng: kiểm tra HTTP/JWT/ACK/replay/restart, không đọc .env hoặc dùng provider.
 * --pg-bin và --maven bắt buộc; roots mặc định repo hiện tại, có thể trỏ ba checkout khác nhau để kiểm chứng MR chưa merge.
 * Secrets chỉ ở bộ nhớ/import fixture tạm, không in token; finally dừng đúng process/container/cluster do lượt này tạo.
 */
const { values: args } = parseArgs({ options: {
    "pg-bin": { type: "string" }, maven: { type: "string" }, java: { type: "string", default: "java" },
    "commerce-root": { type: "string", default: process.cwd() }, "fulfillment-root": { type: "string", default: process.cwd() },
    "notification-root": { type: "string", default: process.cwd() }, "keycloak-image": { type: "string", default: "quay.io/keycloak/keycloak:24.0.5" },
    "skip-build": { type: "boolean", default: false },
    // Kiểm chứng JWT và cờ đóng/mở dispatch trên JVM thật; không bật worker hoặc gọi carrier.
    "dispatch-security": { type: "boolean", default: false },
    // Bao gồm security, sau đó bật worker với carrier HTTP local; không dùng merchant/provider thật.
    "dispatch": { type: "boolean", default: false },
} });
if (args.dispatch) { args["dispatch-security"] = true; }
assert(args["pg-bin"] && args.maven, "Cần --pg-bin và --maven; không đọc cấu hình kết nối thật");
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "nexus-shipping-flow-"));
// fetch/AbortSignal có thể chỉ giữ handle unref khi IAM đang boot; giữ event loop đến khi finally dọn tài nguyên.
const keepAlive = setInterval(() => {}, 1000);
// Report phân biệt rõ carrier HTTP giả lập và transport-only; không biến fixture thành bằng chứng sandbox.
const report = { scope: args.dispatch ? "isolated-real-keycloak-http-fixture-carrier" : "isolated-real-keycloak-http-no-carrier",
    cases: [], sources: {}, cleanup: false };
const children = new Map(); const proxies = []; const logs = [];
let container; let pgStarted = false;
const baseEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
    /^(PATH|PATHEXT|SYSTEMROOT|WINDIR|COMSPEC|TEMP|TMP|USERPROFILE|APPDATA|LOCALAPPDATA|PROGRAMDATA|JAVA_HOME)$/i.test(key)));
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const ports = new Set();
/** Cấp cổng loopback khác các cổng đã chọn trong cùng lượt, không dùng port cố định của môi trường đang chạy. */
async function freePort() {
    const server = net.createServer(); await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const port = server.address().port; await new Promise((resolve) => server.close(resolve));
    if (ports.has(port)) { return freePort(); } ports.add(port); return port;
}
/** Gọi argv trực tiếp với env allowlist, không nội suy credential/path vào shell. */
const run = (exe, argv, options = {}) => execFileSync(exe, argv, { env: baseEnv, windowsHide: true, encoding: "utf8", ...options });
const pg = (name, argv, input) => run(path.join(args["pg-bin"], name + (process.platform === "win32" ? ".exe" : "")), argv,
    { input, ...(name === "pg_ctl" ? { stdio: "ignore" } : {}) });
let pgPort;
// Mọi SQL buộc host/port/user của cluster test, không nhận PGHOST/PGPASSWORD kế thừa hoặc URL từ .env.
const sql = (db, input) => pg("psql", ["-XqAt", "-h", "127.0.0.1", "-p", String(pgPort), "-U", "postgres", "-v", "ON_ERROR_STOP=1", "-d", db], input).trim();
const pass = (name) => { report.cases.push(name); console.log(`PASS ${name}`); };
/** Poll startup/ACK hữu hạn; chịu lỗi HTTP tạm thời nhưng dừng ngay khi JVM đã thoát. */
async function waitFor(label, check, timeout = 120000, alive = () => true) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
        if (!alive()) { throw new Error(`${label}: process đã dừng; xem artifact logs`); }
        try { if (await check()) { return; } } catch { /* Startup/restart chưa sẵn sàng: poll hữu hạn. */ }
        await pause(200);
    }
    throw new Error(`Timeout: ${label}; xem artifact logs`);
}
/** Request test giữ JWT trong header, không log body/token; trả status/JSON để assert contract thật. */
async function request(url, { token, body, key, method = body === undefined ? "GET" : "POST" } = {}) {
    const response = await fetch(url, { method, redirect: "manual", signal: AbortSignal.timeout(10000),
        headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(key ? { "Idempotency-Key": key } : {}),
            ...(body === undefined ? {} : { "Content-Type": "application/json" }) }, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await response.text(); let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = null; }
    return { status: response.status, data };
}

/** Proxy chỉ fault-inject response sau khi consumer thật commit; không thay body/JWT và không giả xử lý nghiệp vụ. */
async function faultProxy(target, field, loseType) {
    const state = { calls: new Map(), lost: false, holdId: null, held: false, release: null, loseClaim: false, claimKeys: [] };
    const server = http.createServer(async (incoming, outgoing) => {
        try {
            const chunks = []; for await (const chunk of incoming) { chunks.push(chunk); }
            const raw = Buffer.concat(chunks); const event = raw.length ? JSON.parse(raw.toString()) : {};
            const kind = event[field]; state.calls.set(kind, (state.calls.get(kind) ?? 0) + 1);
            const response = await fetch(target + incoming.url, { method: incoming.method, redirect: "manual", signal: AbortSignal.timeout(10000),
                headers: { "Content-Type": "application/json", ...(incoming.headers.authorization ? { Authorization: incoming.headers.authorization } : {}),
                    ...(incoming.headers["idempotency-key"] ? { "Idempotency-Key": incoming.headers["idempotency-key"] } : {}) },
                body: raw.length ? raw : undefined });
            const body = await response.text();
            // Làm mất ACK sau khi Commerce đã commit claim thật; giữ key qua proxy để retry không tạo claim mới.
            if (incoming.url === "/api/v1/internal/commerce/shipping-dispatch-claims") {
                state.claimKeys.push(incoming.headers["idempotency-key"]);
                if (state.loseClaim && response.status === 201) {
                    state.loseClaim = false; outgoing.writeHead(503); outgoing.end(); return;
                }
            }
            if (response.status === 204 && event.id === state.holdId) {
                state.held = true; await new Promise((resolve) => { state.release = resolve; });
            }
            if (response.status === 204 && kind === loseType && !state.lost) {
                state.lost = true; outgoing.writeHead(503); outgoing.end(); return;
            }
            if (!outgoing.destroyed) { outgoing.writeHead(response.status, { "Content-Type": "application/json" }); outgoing.end(body); }
        } catch { if (!outgoing.destroyed) { outgoing.writeHead(502); outgoing.end(); } }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    state.url = `http://127.0.0.1:${server.address().port}`; proxies.push({ server, state }); return state;
}

/** Chạy Maven bằng argv/env, không nội suy đường dẫn vào shell command; không in secret từ cấu hình test. */
async function build(service) {
    const root = path.resolve(args[`${service}-root`]); const folder = path.join(root, "backend", `${service}-service`);
    if (!args["skip-build"]) {
        console.log(`BUILD ${service}`);
        const log = fs.openSync(path.join(directory, `build-${service}.log`), "w");
        const command = process.platform === "win32" ? "powershell.exe" : args.maven;
        const argv = process.platform === "win32" ? ["-NoProfile", "-NonInteractive", "-Command",
            "& $env:NEXUS_E2E_MAVEN -q -f $env:NEXUS_E2E_POM '-Dmaven.test.skip=true' package; exit $LASTEXITCODE"]
            : ["-q", "-f", path.join(folder, "pom.xml"), "-Dmaven.test.skip=true", "package"];
        const child = spawn(command, argv, { windowsHide: true, env: { ...baseEnv, NEXUS_E2E_MAVEN: args.maven, NEXUS_E2E_POM: path.join(folder, "pom.xml") }, stdio: ["ignore", log, log] });
        const code = await new Promise((resolve, reject) => { child.once("error", reject); child.once("exit", resolve); }); fs.closeSync(log);
        assert.equal(code, 0, `Build ${service} lỗi; xem artifact log`);
    }
    const jar = path.join(folder, "target", `${service}-service-0.0.1-SNAPSHOT.jar`); assert(fs.existsSync(jar), `Thiếu jar ${service}`);
    // Checksum ghi nhận đúng artifact đã chạy, kể cả source đang review chưa merge; không đưa credential vào report.
    report.sources[service] = { sha256: createHash("sha256").update(fs.readFileSync(jar)).digest("hex"),
        checkoutHead: run("git", ["-C", root, "rev-parse", "HEAD"]).trim(), reusedArtifact: args["skip-build"] };
    return jar;
}
/** Chỉ dừng child JVM do lượt này spawn; đợi exit hữu hạn và không đụng process Java/Docker khác. */
async function stop(service) {
    const child = children.get(service); if (!child) { return; }
    if (child.pid && child.exitCode === null && child.signalCode === null) {
        const exited = new Promise((resolve) => child.once("exit", resolve)); child.kill();
        await Promise.race([exited, pause(10000)]);
        if (child.exitCode === null && child.signalCode === null) { child.kill("SIGKILL"); await Promise.race([exited, pause(5000)]); }
        assert(child.exitCode !== null || child.signalCode !== null, `Không dừng được JVM ${service}`);
    }
    children.delete(service);
}

try {
    const endpoint = run("docker", ["context", "inspect", "--format", "{{.Endpoints.docker.Host}}"] ).trim();
    assert(/^(npipe:|unix:)/.test(endpoint), "Chỉ cho phép Docker local, không dùng remote context");
    run("docker", ["image", "inspect", args["keycloak-image"]]);
    const realmName = `shipping-e2e-${randomUUID()}`; const iam = createIamFixture(realmName, { dispatch: args["dispatch-security"] });
    const realmFolder = path.join(directory, "realm"); fs.mkdirSync(realmFolder);
    fs.writeFileSync(path.join(realmFolder, "realm.json"), JSON.stringify(iam.realm));
    const iamPort = await freePort(); const issuer = `http://127.0.0.1:${iamPort}/realms/${realmName}`;
    const containerName = `nexus-shipping-e2e-${randomUUID()}`;
    container = run("docker", ["run", "-d", "--name", containerName, "-p", `127.0.0.1:${iamPort}:8080`,
        "--mount", `type=bind,source=${realmFolder},target=/opt/keycloak/data/import,readonly`, args["keycloak-image"],
        "start-dev", "--import-realm", "--hostname-strict=false"]).trim();
    assert(/^[a-f0-9]{64}$/.test(container), "Container id không hợp lệ");
    fs.writeFileSync(path.join(directory, "resources.json"), JSON.stringify({ container, pgData: path.join(directory, "pgdata") }));
    console.log("START isolated Keycloak");
    await waitFor("Keycloak import", async () => (await fetch(`${issuer}/.well-known/openid-configuration`, { signal: AbortSignal.timeout(3000) })).status === 200);
    pass("real_keycloak_realm_ready");
    /** Xin grant thật ở realm fixture, không dùng token tự ký; lỗi chỉ ghi status, không ghi credential/body. */
    async function token(client, username) {
        const form = new URLSearchParams(username ? { grant_type: "password", client_id: "shipping-e2e-web", username, password: iam.password }
            : { grant_type: "client_credentials", client_id: client, client_secret: iam.secrets[client] });
        const response = await fetch(`${issuer}/protocol/openid-connect/token`, { method: "POST", body: form, signal: AbortSignal.timeout(10000) });
        assert.equal(response.status, 200, "IAM test từ chối grant; không in credential/body");
        const data = await response.json(); assert(data.access_token, "IAM thiếu access_token"); return data.access_token;
    }
    const admin = await token(null, "admin"); const buyer = await token(null, "buyer"); const other = await token(null, "other");
    const fulfillmentToken = await token("nexus-fulfillment-service"); const wrong = await token("nexus-wrong-service");
    // Client sai thực sự có cùng privilege: 403 phải đến từ identity policy, không phải fixture thiếu quyền.
    const wrongClaims = JSON.parse(Buffer.from(wrong.split(".")[1], "base64url").toString());
    assert.equal(wrongClaims.azp, "nexus-wrong-service");
    assert(wrongClaims.realm_access?.roles?.includes("COMMERCE.SHIPPING_EVENTS"), "Wrong-client fixture thiếu privilege cần kiểm tra");
    pgPort = await freePort(); const pgData = path.join(directory, "pgdata");
    pg("initdb", ["-D", pgData, "-U", "postgres", "-A", "trust", "--encoding=UTF8", "--locale=C"]);
    pg("pg_ctl", ["-D", pgData, "-l", path.join(directory, "postgres.log"), "-o", `-h 127.0.0.1 -p ${pgPort}`, "-w", "start"]); pgStarted = true;
    const names = ["notification", "commerce", "fulfillment"]; const configs = {}; const jars = {}; const urls = {};
    for (const service of names) {
        sql("postgres", `CREATE DATABASE e2e_${service};`); const port = await freePort(); urls[service] = `http://127.0.0.1:${port}`;
        jars[service] = await build(service);
        configs[service] = { "server.address": "127.0.0.1", "server.port": port, "spring.application.name": `${service}-service`, "spring.cloud.config.enabled": false,
            "spring.datasource.url": `jdbc:postgresql://127.0.0.1:${pgPort}/e2e_${service}`, "spring.datasource.username": "postgres", "spring.datasource.password": "",
            "spring.flyway.enabled": true, "spring.jpa.hibernate.ddl-auto": "validate", "spring.jpa.properties.hibernate.jdbc.time_zone": "UTC",
            "eureka.client.enabled": false, "app.keycloak.issuer": issuer, "app.keycloak.jwk-set-uri": `${issuer}/protocol/openid-connect/certs`,
            "management.endpoint.health.show-details": "when_authorized", "management.endpoint.health.show-components": "when_authorized", "management.endpoint.health.roles": "ADMIN",
            "management.endpoints.web.exposure.include": "health", "management.health.mail.enabled": false,
            "logging.level.root": "INFO", "logging.level.org.springframework.web": "INFO", "logging.level.org.springframework.jdbc.core": "INFO",
            "app.payment.vnpay.merchant-api-enabled": false, "app.payment.vnpay.refund-worker-enabled": false,
            "notification.email-enabled": false, "notification.push-enabled": true, "spring.mail.host": "127.0.0.1", "spring.mail.port": 1,
            "app.fulfillment.reservation-scheduler.enabled": false, "app.fulfillment.shipping-polling-scheduler.enabled": false };
    }
    const notificationProxy = await faultProxy(urls.notification, "type", "ORDER_DELIVERED");
    const commerceProxy = await faultProxy(urls.commerce, "eventType", "ShipmentPickedUp");
    Object.assign(configs.commerce, { "app.notification.url": notificationProxy.url, "app.notification.token-url": `${issuer}/protocol/openid-connect/token`,
        "app.notification.client-id": "nexus-commerce-notifier", "app.notification.client-secret": iam.secrets["nexus-commerce-notifier"],
        "app.shipping.notification-enabled": true, "app.shipping.notification-delay-ms": 250, "app.shipping.notification-backlog-max-age-seconds": 3600 });
    Object.assign(configs.fulfillment, { "app.shipping.commerce.url": commerceProxy.url, "app.shipping.commerce.token-url": `${issuer}/protocol/openid-connect/token`,
        "app.shipping.commerce.client-id": "nexus-fulfillment-service", "app.shipping.commerce.client-secret": iam.secrets["nexus-fulfillment-service"],
        "app.shipping.commerce.events-enabled": true, "app.shipping.commerce.events-delay-ms": 250, "app.shipping.commerce.backlog-max-age-seconds": 3600 });
    let carrier;
    if (args.dispatch) {
        carrier = await createCarrierFixture(); proxies.push(carrier);
        // Token giả không rỗng ép adapter dùng HTTP, tránh fallback local che mất lỗi contract/network.
        // Mọi endpoint carrier đều trỏ loopback; tắt polling để tập trung vào create/ACK/recovery.
        Object.assign(configs.fulfillment, { "app.shipping.dispatch-worker-enabled": true, "app.shipping.dispatch-worker-delay-ms": 250,
            "app.fulfillment.shipping.ghn.base-url": carrier.state.url, "app.fulfillment.shipping.ghn.token": carrier.state.token,
            "app.fulfillment.shipping.ghn.shop-id": "1", "app.fulfillment.shipping.ghn.read-timeout-ms": 60000,
            "app.fulfillment.shipping.ghtk.base-url": carrier.state.url, "app.fulfillment.shipping.ghtk.token": carrier.state.token,
            "app.fulfillment.shipping.api-timeout-ms": 60000, "app.fulfillment.shipping.free-shipping-threshold": 1000000 });
    }
    /** JAR thật dùng config JSON tạm trên loopback; worker Shipping bật, mọi merchant/refund/email provider bị tắt. */
    async function start(service) {
        const log = fs.openSync(path.join(directory, `${service}-${Date.now()}.log`), "w"); logs.push(log);
        const child = spawn(args.java, ["-Xms64m", "-Xmx384m", "-jar", jars[service], "--spring.config.name=shipping-e2e-isolated"], {
            windowsHide: true, cwd: directory, env: { ...baseEnv, SPRING_APPLICATION_JSON: JSON.stringify(configs[service]) }, stdio: ["ignore", log, log] });
        children.set(service, child); child.on("error", () => {});
        console.log(`START ${service}`);
        await waitFor(`${service} health`, async () => (await request(`${urls[service]}/actuator/health`)).status === 200,
            120000, () => Boolean(child.pid) && child.exitCode === null && child.signalCode === null);
    }
    for (const service of names) { await start(service); }
    // Chỉ dùng sub token do IAM test phát để seed owner; ứng dụng thật vẫn tự xác minh JWT bằng JWKS/issuer.
    const buyerSubject = JSON.parse(Buffer.from(buyer.split(".")[1], "base64url").toString()).sub;
    assert(/^[a-f0-9-]{36}$/.test(buyerSubject), "IAM fixture phải phát subject UUID");
    const fixture = createFlowFixture(buyerSubject);
    sql("e2e_commerce", `INSERT INTO orders(id,order_no,buyer_id,seller_id,source_type,status,subtotal,total,shipping_address,recipient_name,recipient_phone,payment_method,price_finalized)
        VALUES (${q(fixture.orderId)},${q(`E2E-${fixture.orderId}`)},${q(fixture.buyer)},${q(fixture.seller)},'REGULAR','PROCESSING',50000,50000,'Test address','Test buyer','0900000000','VNPAY',true);`);
    const ingress = `${urls.commerce}/api/v1/internal/commerce/shipping-events`;
    for (const [auth, expected] of [[null, 401], [buyer, 403], [admin, 403], [wrong, 403]]) {
        assert.equal((await request(ingress, { token: auth, body: fixture.events[0] })).status, expected);
    }
    pass("real_jwt_enforces_client_and_privilege");
    for (const [index, event] of fixture.events.entries()) {
        sql("e2e_fulfillment", `INSERT INTO fulfillment_event_outbox(id,aggregate_type,aggregate_id,event_type,payload,done,attempts,next_attempt_at)
            VALUES (${q(event.eventId)},'SHIPMENT',${q(event.shipmentId)},${q(event.eventType)},${q(JSON.stringify(event))}::jsonb,false,0,CURRENT_TIMESTAMP-interval '${3 - index} seconds');`);
    }
    await waitFor("Notification delivered before dropped ACK", () => sql("e2e_notification", "SELECT count(*) FROM notifications WHERE channel='IN_APP' AND event_type='ORDER_DELIVERED'") === "1");
    const notificationId = sql("e2e_notification", "SELECT id FROM notifications WHERE channel='IN_APP' AND event_type='ORDER_DELIVERED'");
    assert.equal((await request(`${urls.notification}/api/v1/notifications/${notificationId}/read`, { method: "POST", token: buyer })).status, 200);
    await waitFor("both outbox ACKs", () => sql("e2e_fulfillment", "SELECT count(*) FROM fulfillment_event_outbox WHERE done=true") === "3"
        && sql("e2e_commerce", "SELECT count(*) FROM shipping_notification_outbox WHERE status='DELIVERED'") === "2");
    assert.equal(sql("e2e_commerce", `SELECT status FROM orders WHERE id=${q(fixture.orderId)}`), "DELIVERED");
    assert.equal(sql("e2e_notification", "SELECT count(*) FROM notifications WHERE channel='IN_APP'"), "2");
    assert.equal(sql("e2e_notification", `SELECT status FROM notifications WHERE id=${q(notificationId)}`), "READ");
    assert(commerceProxy.calls.get("ShipmentPickedUp") >= 2 && notificationProxy.calls.get("ORDER_DELIVERED") >= 2);
    pass("real_workers_recover_lost_ack_without_duplicate_notifications");
    assert.equal((await request(ingress, { token: fulfillmentToken, body: fixture.events[1] })).status, 204);
    assert.equal((await request(ingress, { token: fulfillmentToken, body: { ...fixture.events[1], trackingNumber: "CONFLICT" } })).status, 409);
    assert.equal(sql("e2e_commerce", `SELECT count(*) FROM order_status_history WHERE order_id=${q(fixture.orderId)}`), "2");
    assert.equal((await request(`${urls.notification}/api/v1/notifications`, { token: other })).data.data.totalElements, 0);
    pass("duplicates_conflicts_and_notification_owner_scope");
    const extra = { ...fixture.events[2], eventId: randomUUID() };
    sql("e2e_fulfillment", `INSERT INTO fulfillment_event_outbox(id,aggregate_type,aggregate_id,event_type,payload,done,attempts,next_attempt_at)
        VALUES (${q(extra.eventId)},'SHIPMENT',${q(extra.shipmentId)},${q(extra.eventType)},${q(JSON.stringify(extra))}::jsonb,false,8,CURRENT_TIMESTAMP);`);
    const replayUrl = `${urls.fulfillment}/api/v1/shipments/events/${extra.eventId}/retry`; const key = randomUUID(); const reason = { reason: "E2E isolated replay fixture" };
    assert.equal((await request(replayUrl, { token: buyer, key, body: reason })).status, 403);
    const replay = await request(replayUrl, { token: admin, key, body: reason }); assert.equal(replay.status, 200);
    await waitFor("event replay ACK", () => sql("e2e_fulfillment", `SELECT done FROM fulfillment_event_outbox WHERE id=${q(extra.eventId)}`) === "t");
    assert.deepEqual((await request(replayUrl, { token: admin, key, body: reason })).data.data, replay.data.data);
    assert.equal((await request(replayUrl, { token: admin, key, body: { reason: "Changed" } })).status, 409);
    sql("e2e_commerce", `UPDATE shipping_notification_outbox SET status='DEAD_LETTER',attempts=8 WHERE id=${q(notificationId)};`);
    const notificationReplayUrl = `${urls.commerce}/api/v1/orders/shipping-notifications/${notificationId}/retry`; const notificationKey = randomUUID();
    const notificationReplay = await request(notificationReplayUrl, { token: admin, key: notificationKey, body: reason }); assert.equal(notificationReplay.status, 200);
    await waitFor("notification replay ACK", () => sql("e2e_commerce", `SELECT status FROM shipping_notification_outbox WHERE id=${q(notificationId)}`) === "DELIVERED");
    assert.deepEqual((await request(notificationReplayUrl, { token: admin, key: notificationKey, body: reason })).data.data, notificationReplay.data.data);
    assert.equal(sql("e2e_notification", `SELECT status FROM notifications WHERE id=${q(notificationId)}`), "READ");
    pass("real_admin_replay_apis_preserve_audit_and_read_state");
    notificationProxy.holdId = notificationId;
    sql("e2e_commerce", `UPDATE shipping_notification_outbox SET status='PENDING',attempts=0,next_attempt_at=now(),delivered_at=NULL WHERE id=${q(notificationId)};`);
    await waitFor("consumer ACK held before restart", () => notificationProxy.held);
    await stop("commerce"); notificationProxy.holdId = null; notificationProxy.release?.();
    assert.equal(sql("e2e_commerce", `SELECT status FROM shipping_notification_outbox WHERE id=${q(notificationId)}`), "PENDING");
    await start("commerce");
    await waitFor("delivery after actual JVM restart", () => sql("e2e_commerce", `SELECT status FROM shipping_notification_outbox WHERE id=${q(notificationId)}`) === "DELIVERED");
    assert.equal(sql("e2e_notification", "SELECT count(*) FROM notifications WHERE channel='IN_APP'"), "2");
    assert.equal(sql("e2e_notification", `SELECT status FROM notifications WHERE id=${q(notificationId)}`), "READ");
    assert.equal(sql("e2e_commerce", "SELECT count(*) FROM payments"), "0");
    pass("jvm_restart_after_consumer_commit_replays_without_duplicate_or_payment");
    for (const service of ["commerce", "fulfillment"]) {
        const publicHealth = await request(`${urls[service]}/actuator/health`); assert.equal(publicHealth.data.components, undefined);
        const component = service === "commerce" ? "shippingNotificationTransport" : "shippingEventTransport";
        assert.equal((await request(`${urls[service]}/actuator/health/${component}`, { token: admin })).data.details.connectivityVerified, false);
    }
    pass("real_admin_health_visibility");
    // Chạy sau transport/restart: kiểm tra boundary bằng JWT do Keycloak ký thật, không seed payment/claim.
    // Service hợp lệ gặp 503 khi cờ tắt và 404 khi bật nhưng chưa có dữ liệu; client sai luôn bị 403.
    if (args["dispatch-security"]) {
        const base = `${urls.commerce}/api/v1/internal/commerce`;
        const missing = randomUUID();
        const calls = [
            [`${base}/shipping-handoffs/${missing}`, {}],
            [`${base}/shipping-dispatch-claims`, { key: randomUUID(), body: { handoffId: missing, parcelId: randomUUID() } }],
            [`${base}/shipping-dispatch-claims/${missing}/preflight`, { method: "POST" }],
        ];
        for (const [url, options] of calls) {
            for (const [token, expected] of [[null, 401], [buyer, 403], [admin, 403], [wrong, 403]]) {
                assert.equal((await request(url, { ...options, token })).status, expected, `dispatch identity ${expected}`);
            }
            assert.equal((await request(url, { ...options, token: fulfillmentToken })).status, 503, "dispatch disabled");
        }
        pass("real_dispatch_jwt_identity_and_default_disabled");
        await stop("commerce");
        configs.commerce["app.shipping.dispatch-claim-enabled"] = true;
        await start("commerce");
        for (const index of [0, 2]) {
            const [url, options] = calls[index];
            assert.equal((await request(url, { ...options, token: fulfillmentToken })).status, 404, "missing dispatch fixture");
            assert.equal((await request(url, { ...options, token: wrong })).status, 403, "wrong azp with matching privileges");
        }
        assert.equal(sql("e2e_commerce", "SELECT count(*) FROM shipping_dispatch_claims"), "0");
        assert.equal(sql("e2e_commerce", "SELECT count(*) FROM payments"), "0");
        pass("real_dispatch_enabled_missing_records_do_not_create_financial_state");
    }
    if (args.dispatch) {
        await exerciseDispatch({ sql, q, request, pass, waitFor, start, stop, urls, admin, buyer, buyerSubject,
            carrier: carrier.state, commerceProxy });
    }
    report.result = "PASS";
} catch (error) {
    report.result = "FAIL"; report.error = error instanceof assert.AssertionError ? "Assertion failed; inspect fixture status and logs" : error.message;
    process.exitCode = 1; console.error(`FAIL ${report.error}`);
} finally {
    for (const { state } of proxies) { state.release?.(); }
    for (const service of [...children.keys()]) {
        try { await stop(service); } catch { report.cleanupError = `JVM cleanup failed: ${service}`; }
    }
    for (const { server } of proxies) { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); }
    for (const log of logs) { fs.closeSync(log); }
    if (container && /^[a-f0-9]{64}$/.test(container)) { try { run("docker", ["rm", "-f", container]); } catch { report.cleanupError = "Container cleanup failed"; } }
    if (pgStarted) { try { pg("pg_ctl", ["-D", path.join(directory, "pgdata"), "-m", "fast", "-w", "stop"]); } catch { report.cleanupError = "PostgreSQL cleanup failed"; } }
    // Chỉ xóa file realm credential do lượt này tạo; giữ log/report/DB tạm cho điều tra, không đọc hoặc xóa .env thật.
    const realmFile = path.join(directory, "realm", "realm.json"); if (fs.existsSync(realmFile)) { fs.unlinkSync(realmFile); }
    report.cleanup = !report.cleanupError;
    if (!report.cleanup) { process.exitCode = 1; report.result = "FAIL_CLEANUP"; }
    fs.writeFileSync(path.join(directory, "report.json"), JSON.stringify(report, null, 2));
    console.log(`ARTIFACTS ${directory}`);
    clearInterval(keepAlive);
}
