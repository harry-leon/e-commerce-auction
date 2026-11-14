import http from "node:http";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

/** Carrier HTTP chỉ trên loopback: lưu số lần create và tracking đã nhận; không gọi sandbox hoặc log PII/token.
 * lose-ack trả 503 sau nhận; hold-ack giữ response để test kill JVM trước ACK. Cleanup giải phóng tất cả response.
 */
export async function createCarrierFixture() {
    const state = { token: randomUUID(), orders: new Map(), release: () => {
        for (const order of state.orders.values()) { order.release?.(); }
    } };
    const server = http.createServer(async (incoming, outgoing) => {
        try {
            if (incoming.method !== "POST" || incoming.headers.token !== state.token
                || incoming.url !== "/shiip/public-api/v2/shipping-order/create") {
                outgoing.writeHead(400); outgoing.end(); return;
            }
            const chunks = []; for await (const chunk of incoming) { chunks.push(chunk); }
            const body = JSON.parse(Buffer.concat(chunks).toString());
            const order = state.orders.get(body.client_order_code);
            if (!order) { outgoing.writeHead(404); outgoing.end(); return; }
            order.calls++; order.payload = body; order.accepted = true;
            if (order.mode === "hold-ack") { await new Promise((resolve) => { order.release = resolve; }); }
            if (outgoing.destroyed) { return; }
            if (order.mode === "lose-ack") { outgoing.writeHead(503); outgoing.end(); return; }
            outgoing.writeHead(200, { "Content-Type": "application/json" });
            outgoing.end(JSON.stringify({ code: 200, message: "fixture", data: {
                order_code: order.tracking, total_fee: 25000, expected_delivery_time: new Date(Date.now() + 86400000).toISOString(),
            } }));
        } catch { if (!outgoing.destroyed) { outgoing.writeHead(400); outgoing.end(); } }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    state.url = `http://127.0.0.1:${server.address().port}`;
    return { server, state };
}

/** Đăng ký kiện qua API thật và chạy worker/guard/recovery trên JVM thật.
 * SQL chỉ seed đầu vào đã xác nhận tiền/kho và làm hết lease sau crash; không giả kết quả claim/dispatch/reconcile.
 * Không kiểm chứng checkout, IPN hoặc provider sandbox; refund worker và polling carrier vẫn tắt.
 */
export async function exerciseDispatch({ sql, q, request, pass, waitFor, start, stop, urls, admin, buyer, buyerSubject, carrier, commerceProxy }) {
    const commerce = "e2e_commerce"; const fulfillment = "e2e_fulfillment";
    const jobs = (order, column) => sql(fulfillment, `SELECT ${column} FROM shipment_dispatch_jobs WHERE order_id=${q(order)}`);
    // Mỗi scenario có Order/Payment/handoff riêng; giá trị merchant/reference chỉ là fixture, không phải thu tiền thật.
    function seed(mode) {
        const order = randomUUID(); const payment = randomUUID(); const handoff = randomUUID();
        const reference = BigInt("0x" + payment.replaceAll("-", "").slice(0, 15)).toString();
        carrier.orders.set(order, { mode, calls: 0, accepted: false, tracking: `FIXTURE-${order}` });
        sql(commerce, `BEGIN;
            INSERT INTO orders(id,order_no,buyer_id,seller_id,source_type,status,subtotal,total,shipping_address,recipient_name,recipient_phone,payment_method,price_finalized)
            VALUES (${q(order)},${q(`DISPATCH-${order}`)},${q(buyerSubject)},${q(randomUUID())},'REGULAR','PROCESSING',50000,50000,'Fixture address','Fixture receiver','0900000000','VNPAY',true);
            INSERT INTO payments(id,order_id,provider,method,amount,status,provider_ref,merchant_code,response_code,transaction_status,completed_at,inventory_status,inventory_ref_id,price_snapshot)
            VALUES (${q(payment)},${q(order)},'VNPAY','VNPAY',50000,'SUCCESS',${q(reference)},'FIXTURE_E2E','00','00',now(),'CONFIRMED',${q(payment)},'{"total":50000}'::jsonb);
            INSERT INTO shipping_handoff_tasks(id,order_id,payment_id,amount) VALUES (${q(handoff)},${q(order)},${q(payment)},50000); COMMIT;`);
        return { order, payment, handoff };
    }
    // Chỉ Admin được khai báo cân nặng/địa bàn; recipient và tiền vẫn lấy từ snapshot Commerce qua preflight.
    async function register(fixture) {
        const url = `${urls.fulfillment}/api/v1/shipments/preparation/${fixture.order}/parcels`;
        const body = { warehouseId: "a0000000-0000-0000-0000-000000000001", carrier: "GHN", serviceType: "STANDARD",
            weightGrams: 725, toDistrictId: 12, toWardCode: "21211" };
        assert.equal((await request(url, { token: buyer, key: randomUUID(), body })).status, 403);
        assert.equal((await request(url, { token: admin, key: randomUUID(), body })).status, 201);
    }
    const refund = (fixture) => request(`${urls.commerce}/api/v1/refunds`, {
        token: admin, key: randomUUID(), body: { paymentId: fixture.payment, reason: "Isolated dispatch fixture" },
    });
    const happy = seed("success"); commerceProxy.loseClaim = true;
    await register(happy);
    await waitFor("dispatch committed after lost claim ACK", () => jobs(happy.order, "state") === "COMMITTED");
    const happyJob = jobs(happy.order, "id"); const accepted = carrier.orders.get(happy.order);
    assert.equal(accepted.calls, 1); assert.equal(accepted.payload.weight, 725); assert.equal(accepted.payload.cod_amount, 0);
    assert.equal(accepted.payload.to_name, "Fixture receiver"); assert.equal(accepted.payload.to_address, "Fixture address");
    assert(commerceProxy.claimKeys.filter((key) => key === happyJob).length >= 2, "claim must replay same job key");
    assert.equal(sql(commerce, `SELECT count(*) FROM shipping_dispatch_claims WHERE order_id=${q(happy.order)}`), "1");
    assert.equal(sql(fulfillment, `SELECT status FROM shipment_dispatch_attempts WHERE order_id=${q(happy.order)}`), "COMMITTED");
    assert.equal((await refund(happy)).status, 409);
    pass("real_dispatch_lost_claim_ack_single_carrier_create_and_refund_fence");

    const blocked = seed("success"); assert.equal((await refund(blocked)).status, 201); await register(blocked);
    await waitFor("refund before claim blocks dispatch", () => jobs(blocked.order, "state") === "REQUIRES_REVIEW");
    assert.equal(carrier.orders.get(blocked.order).calls, 0);
    assert.equal(sql(commerce, `SELECT count(*) FROM shipping_dispatch_claims WHERE order_id=${q(blocked.order)}`), "0");
    pass("real_refund_before_claim_prevents_carrier_mutation");

    const lost = seed("lose-ack"); await register(lost);
    await waitFor("lost carrier ACK requires review", () => jobs(lost.order, "state") === "REQUIRES_REVIEW");
    assert.equal(carrier.orders.get(lost.order).calls, 1);
    assert.equal(sql(fulfillment, `SELECT status FROM shipment_dispatch_attempts WHERE order_id=${q(lost.order)}`), "UNKNOWN");

    const crash = seed("hold-ack"); await register(crash);
    await waitFor("carrier accepted before JVM crash", () => carrier.orders.get(crash.order).accepted);
    assert.equal(jobs(crash.order, "state"), "DISPATCHING");
    await stop("fulfillment"); carrier.orders.get(crash.order).release?.();
    // Tiến đồng hồ lease trong DB test, không sửa guard/state: JVM mới phải tự đưa DISPATCHING sang REVIEW.
    sql(fulfillment, `UPDATE shipment_dispatch_jobs SET lease_expires_at=now()-interval '1 second' WHERE order_id=${q(crash.order)};`);
    await start("fulfillment");
    await waitFor("crashed dispatch requires review after restart", () => jobs(crash.order, "state") === "REQUIRES_REVIEW");
    assert.equal(carrier.orders.get(crash.order).calls, 1); assert.equal(carrier.orders.get(lost.order).calls, 1);
    assert.equal(carrier.orders.get(happy.order).calls, 1);
    assert.equal(sql(fulfillment, `SELECT status FROM shipment_dispatch_attempts WHERE order_id=${q(crash.order)}`), "UNKNOWN");
    assert.equal(sql(fulfillment, `SELECT count(*) FROM shipments WHERE order_id IN (${q(crash.order)},${q(lost.order)})`), "0");
    assert.equal((await request(`${urls.fulfillment}/api/v1/shipments/dispatch-jobs/${jobs(crash.order, "id")}/reconcile`, {
        token: admin, key: randomUUID(), body: { evidenceReference: "fixture:unresolved", note: "Guard still unknown" },
    })).status, 409, "cannot reconcile an unknown guard without a recovered shipment");
    pass("real_carrier_lost_ack_and_jvm_crash_never_auto_resend");

    // Dùng tracking mà HTTP fixture đã nhận làm bằng chứng ngoài hệ thống; Admin phục hồi guard trước, rồi đóng job.
    const attempt = sql(fulfillment, `SELECT id FROM shipment_dispatch_attempts WHERE order_id=${q(crash.order)}`);
    const manualUrl = `${urls.fulfillment}/api/v1/shipments/dispatches/${attempt}/manual-resolution`;
    const manualKey = randomUUID(); const manualBody = { trackingNumber: carrier.orders.get(crash.order).tracking,
        shippingFee: 25000, evidenceReference: `fixture:${attempt}`, note: "Fixture carrier accepted before process exit" };
    assert.equal((await request(manualUrl, { token: buyer, key: manualKey, body: manualBody })).status, 403);
    const manual = await request(manualUrl, { token: admin, key: manualKey, body: manualBody }); assert.equal(manual.status, 200);
    assert.deepEqual((await request(manualUrl, { token: admin, key: manualKey, body: manualBody })).data.data, manual.data.data);
    const job = jobs(crash.order, "id"); const reconcileUrl = `${urls.fulfillment}/api/v1/shipments/dispatch-jobs/${job}/reconcile`;
    const key = randomUUID(); const body = { evidenceReference: `fixture:${attempt}`, note: "Recovered local shipment matches accepted fixture" };
    const metadataUrl = `${urls.fulfillment}/api/v1/shipments/dispatch-jobs/${job}`;
    assert.equal((await request(metadataUrl, { token: buyer })).status, 403);
    const metadata = await request(metadataUrl, { token: admin }); assert.equal(metadata.status, 200);
    assert.equal(metadata.data.data.leaseToken, undefined); assert.equal(metadata.data.data.recipientName, undefined);
    assert.equal((await request(reconcileUrl, { token: buyer, key, body })).status, 403);
    const result = await request(reconcileUrl, { token: admin, key, body }); assert.equal(result.status, 201);
    const replay = await request(reconcileUrl, { token: admin, key, body }); assert.equal(replay.status, 200);
    assert.deepEqual(replay.data.data, result.data.data);
    assert.equal((await request(reconcileUrl, { token: admin, key, body: { ...body, note: "Changed" } })).status, 409);
    assert.equal(jobs(crash.order, "state"), "COMMITTED");
    assert.equal(sql(fulfillment, `SELECT count(*) FROM shipment_dispatch_job_reconciliations WHERE job_id=${q(job)}`), "1");
    assert.equal(sql(fulfillment, `SELECT count(*) FROM shipment_dispatch_resolutions WHERE attempt_id=${q(attempt)}`), "1");
    assert.equal(sql(fulfillment, `SELECT count(*) FROM shipments WHERE order_id=${q(crash.order)}`), "1");
    assert.equal(sql(fulfillment, `SELECT count(*) FROM fulfillment_event_outbox WHERE event_type='ShipmentCreated' AND payload->>'orderId'=${q(crash.order)}`), "1");
    assert.equal(carrier.orders.get(crash.order).calls, 1); assert.equal((await refund(crash)).status, 409);
    await waitFor("recovered shipment event delivered", () => sql(fulfillment,
        `SELECT done FROM fulfillment_event_outbox WHERE event_type='ShipmentCreated' AND payload->>'orderId'=${q(crash.order)}`) === "t");
    pass("real_admin_manual_recovery_and_job_reconcile_are_audited_idempotent");
}
