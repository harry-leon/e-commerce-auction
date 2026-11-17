import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createCarrierFixture } from "./dispatch.mjs";

/** HTTP fixture phải phân biệt ACK thành công, mất ACK sau nhận và request còn treo; sai token không được nhận đơn. */
test("carrier fixture records acceptance before failed or held acknowledgement", async () => {
    const { server, state } = await createCarrierFixture();
    const send = (order, token = state.token) => fetch(`${state.url}/shiip/public-api/v2/shipping-order/create`, {
        method: "POST", headers: { Token: token, "Content-Type": "application/json" },
        body: JSON.stringify({ client_order_code: order, cod_amount: 0, weight: 725 }), signal: AbortSignal.timeout(5000),
    });
    try {
        for (const mode of ["success", "lose-ack", "hold-ack"]) {
            const id = randomUUID(); const entry = { mode, calls: 0, tracking: `FIXTURE-${id}` }; state.orders.set(id, entry);
            assert.equal((await send(id, "wrong-token")).status, 400); assert.equal(entry.calls, 0);
            const pending = send(id);
            if (mode === "hold-ack") {
                const deadline = Date.now() + 3000;
                while (!entry.accepted && Date.now() < deadline) { await new Promise((resolve) => setTimeout(resolve, 10)); }
                assert.equal(entry.accepted, true); assert.equal(entry.calls, 1); entry.release();
            }
            const response = await pending;
            assert.equal(response.status, mode === "lose-ack" ? 503 : 200);
            assert.equal(entry.accepted, true); assert.equal(entry.calls, 1);
            if (response.status === 200) { assert.equal((await response.json()).data.order_code, entry.tracking); }
            // Detail là HTTP riêng: trả trạng thái carrier hiện tại hoặc lỗi mạng giả lập, không tăng số create.
            const detail = () => fetch(`${state.url}/shiip/public-api/v2/shipping-order/detail`, {
                method: "POST", headers: { Token: state.token, "Content-Type": "application/json" },
                body: JSON.stringify({ order_code: entry.tracking }), signal: AbortSignal.timeout(5000),
            });
            entry.status = "picked";
            assert.equal((await (await detail()).json()).data.status, "picked");
            entry.unavailable = true; assert.equal((await detail()).status, 503);
            assert.equal(entry.calls, 1); assert.equal(entry.reads, 2);
        }
    } finally {
        state.release(); server.closeAllConnections(); await new Promise((resolve) => server.close(resolve));
    }
});
