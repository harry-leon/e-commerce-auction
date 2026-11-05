import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { DATASET } from "./dataset.mjs";
import { discoverAccounts, ensureAccounts, http } from "./runtime.mjs";

/** Fixture IAM chỉ nghe loopback, không nạp Compose/.env và không liên hệ tài khoản thật. */
async function fixture(marked, run) {
    const writes = [];
    const account = {
        key: "buyer-test",
        role: "BUYER",
        email: "fixture@nexus.example",
    };
    const user = {
        id: "11111111-1111-4111-8111-111111111111",
        email: account.email,
        username: account.email,
        ...(marked
            ? {
                  attributes: {
                      nexus_demo_dataset: [DATASET],
                      nexus_demo_key: [account.key],
                  },
              }
            : {}),
    };
    const server = createServer((request, response) => {
        if (request.method !== "GET" && !request.url.endsWith("/token"))
            writes.push(request.method + " " + request.url);
        let body;
        if (request.url.endsWith("/token"))
            body = { access_token: "fixture-service-token" };
        else if (request.url.includes("/users?"))
            body = [{ id: user.id, email: user.email }];
        else if (request.url.endsWith("/role-mappings/realm"))
            body = [{ id: "buyer-role", name: "ROLE_BUYER" }];
        else if (request.url.includes("/roles/"))
            body = { id: "buyer-role", name: "ROLE_BUYER" };
        else body = user;
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end(JSON.stringify(body));
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
        await run(
            {
                iam: `http://127.0.0.1:${server.address().port}`,
                realm: "fixture",
                client: "fixture",
                secret: "fixture",
            },
            account,
            user,
            writes,
        );
    } finally {
        await new Promise((resolve) => server.close(resolve));
    }
}

/** Email trùng người dùng không có marker phải dừng, tuyệt đối không gán role/reset password cho tài khoản đó. */
test("chặn tài khoản ngoài demo trước mọi thay đổi IAM", async () => {
    await fixture(false, async (rt, account, user, writes) => {
        await assert.rejects(
            discoverAccounts(rt, [account], "fixture"),
            /không ghi đè/,
        );
        assert.deepEqual(writes, []);
    });
});

/** Chạy lại với user demo đã đủ role không POST tạo lại hoặc reset credential; marker đọc từ representation đầy đủ. */
test("giữ nguyên user demo hiện có và resume đúng UUID IAM", async () => {
    await fixture(true, async (rt, account, user, writes) => {
        const state = { identities: {} };
        await ensureAccounts(
            rt,
            [account],
            state,
            "unused-fixture-password",
            () => {},
        );
        assert.equal(state.identities[account.key], user.id);
        assert.deepEqual(writes, []);
    });
});

/** Keycloak lọc custom attributes vẫn được resume đúng UUID từng tạo; UUID checkpoint khác phải bị chặn. */
test("fallback ownership dùng đúng UUID checkpoint, không chỉ dựa vào email", async () => {
    await fixture(false, async (rt, account, user, writes) => {
        const found = await discoverAccounts(rt, [account], "fixture", {
            [account.key]: user.id,
        });
        assert.equal(found[account.key], user.id);
        await assert.rejects(
            discoverAccounts(rt, [account], "fixture", {
                [account.key]: "different-user-id",
            }),
            /không ghi đè/,
        );
        assert.deepEqual(writes, []);
    });
});

/** GET lỗi tạm được thử lại nhưng POST không được lặp để tránh tạo user/giao dịch hai lần khi mất ACK. */
test("retry GET có giới hạn và không retry POST", async () => {
    const calls = { GET: 0, POST: 0 };
    const server = createServer((request, response) => {
        calls[request.method]++;
        const ok = request.method === "GET" && calls.GET > 1;
        response.writeHead(ok ? 200 : 503, {
            "Content-Type": "application/json",
        });
        response.end(JSON.stringify({ ok }));
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const url = `http://127.0.0.1:${server.address().port}/fixture`;
    try {
        assert.deepEqual(await http(url), { ok: true });
        assert.equal(calls.GET, 2);
        await assert.rejects(
            http(url, { method: "POST", body: { fixture: true } }),
            /HTTP 503/,
        );
        assert.equal(calls.POST, 1);
    } finally {
        await new Promise((resolve) => server.close(resolve));
    }
});
