import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";
import assert from "node:assert/strict";
import {
    DATASET,
    SERVICES,
    buildDataset,
    validateDataset,
} from "./demo/dataset.mjs";
import {
    runtime,
    sql,
    ident,
    HTTP_READ_RETRIES,
    seedSql,
    resetSql,
    readRows,
    save,
    preflight,
    http,
    iamToken,
    discoverAccounts,
    ensureAccounts,
} from "./demo/runtime.mjs";

/**
 * Seed demo theo cấu hình Compose hiện tại: plan → kiểm thử DB tạm → tạo IAM riêng → INSERT từng service → kiểm chứng.
 * Reset chỉ xóa PK fixture sau kiểm tra tham chiếu; giữ schema/IAM và không gọi cổng thanh toán.
 * Mỗi service có transaction riêng; manifest/checkpoint và password demo chỉ lưu dưới Git directory để resume an toàn.
 */
const mode = process.argv.find((a) =>
    ["--plan", "--check", "--apply", "--verify", "--reset"].includes(a),
);
if (!mode) {
    console.log(
        "node scripts/seed-demo.mjs --plan | --check | --apply --target <fingerprint> | --verify | --reset --target <fingerprint>",
    );
    process.exit(0);
}
const rt = runtime();
const stateFile = path.join(rt.directory, "state.json");
const state = fs.existsSync(stateFile)
    ? JSON.parse(fs.readFileSync(stateFile, "utf8"))
    : {
          dataset: DATASET,
          fingerprint: rt.fingerprint,
          asOf: new Date().toISOString(),
          identities: {},
          phases: [],
      };
assert.equal(state.dataset, DATASET);
assert.equal(state.fingerprint, rt.fingerprint);
// Manifest cũ chưa có userAsOf: giữ mốc gốc trước khi reset làm mới lịch phiên và giữ kho.
state.userAsOf ??= state.asOf;
const checkpoint = () => save(stateFile, state);

/** Test FK/CHECK và chạy SQL hai lần trong DB local ngẫu nhiên; chỉ dọn các DB có tên do chính lượt chạy tạo. */
function sandbox(data) {
    const pg = rt.config.services.postgres.environment;
    const admin = {
        host: "postgres",
        port: "5432",
        database: "postgres",
        schema: "public",
        username: pg.POSTGRES_USER,
        password: pg.POSTGRES_PASSWORD,
        ssl: "disable",
    };
    const created = [];
    try {
        for (const service of SERVICES) {
            const name = `nx_demo_check_${Date.now()}_${service}`;
            if (!/^nx_demo_check_[0-9]+_[a-z]+$/.test(name))
                throw new Error("Tên DB tạm không hợp lệ");
            sql(rt, service, `CREATE DATABASE ${ident(name)};`, admin);
            created.push(name);
            const target = { ...admin, database: name };
            const directory = `backend/${service}-service/src/main/resources/db/migration`;
            const migrations = fs
                .readdirSync(directory)
                .filter((f) => /^V\d+__.*\.sql$/.test(f))
                .sort(
                    (a, b) =>
                        Number(a.match(/^V(\d+)/)[1]) -
                        Number(b.match(/^V(\d+)/)[1]),
                );
            for (const file of migrations)
                sql(
                    rt,
                    service,
                    fs.readFileSync(path.join(directory, file), "utf8"),
                    target,
                );
            const query = seedSql(service, data.services[service], "public");
            sql(rt, service, query, target);
            sql(rt, service, query, target);
            readRows(rt, service, data.services[service], target);
            console.log("Sandbox schema/constraints/idempotency OK:", service);
        }
    } finally {
        for (const name of created.reverse())
            sql(rt, "commerce", `DROP DATABASE ${ident(name)};`, admin);
    }
    state.sandboxValidatedAt = new Date().toISOString();
    checkpoint();
}

/** Xuất SQL và danh mục số lượng để kiểm tra; file không có password, chỉ chứa tài khoản/hàng hóa giả lập. */
function exportPlan(data) {
    for (const service of SERVICES)
        fs.writeFileSync(
            path.join(rt.directory, `${service}.sql`),
            seedSql(
                service,
                data.services[service],
                rt.targets[service].schema,
            ),
        );
    const plan = {
        dataset: DATASET,
        asOf: state.asOf,
        fingerprint: rt.fingerprint,
        targets: rt.publicTargets,
        counts: Object.fromEntries(
            SERVICES.map((s) => [
                s,
                Object.fromEntries(
                    Object.entries(data.services[s]).map(([t, r]) => [
                        t,
                        r.length,
                    ]),
                ),
            ]),
        ),
    };
    save(path.join(rt.directory, "plan.json"), plan);
    return plan;
}

/** Đọc lại đúng dataset để kiểm tra xuyên DB; dữ liệu bị thay đổi sau buổi demo được báo, không tự ghi đè/reset. */
function verifyDatabase(data) {
    const actual = { ...data, services: {} };
    for (const service of SERVICES)
        actual.services[service] = readRows(
            rt,
            service,
            data.services[service],
        );
    validateDataset(actual);
    return actual;
}

/** Kiểm chứng login/role/profile, API hàng hóa, kho, đơn và inbox; không tạo đơn/giao dịch mới trong lúc verify. */
async function verifyApis(data, password) {
    const results = [];
    let inventoryToken;
    for (const account of data.accounts) {
        const response = await fetch(
            `${rt.iam}/realms/${rt.realm}/protocol/openid-connect/token`,
            {
                method: "POST",
                body: new URLSearchParams({
                    grant_type: "password",
                    client_id: "nexus-web",
                    scope: "openid",
                    username: account.email,
                    password,
                }),
                signal: AbortSignal.timeout(30000),
            },
        );
        if (!response.ok)
            throw new Error(
                `Không login được tài khoản demo ${account.key}: HTTP ${response.status}`,
            );
        const token = (await response.json()).access_token;
        const claims = JSON.parse(
            Buffer.from(token.split(".")[1], "base64url").toString(),
        );
        assert.equal(claims.sub, account.id);
        assert(
            claims.realm_access.roles.includes("ROLE_" + account.role),
            "Sai role trong JWT demo",
        );
        const profile = await http(rt.gateway + "/api/v1/users/me", { token });
        assert.equal((profile.data || profile).id, account.id);
        if (account.role === "BUYER") {
            // Gateway yêu cầu xác thực cho route inventory dù endpoint đọc bên Fulfillment là public.
            inventoryToken = token;
            const orders = await http(rt.gateway + "/api/v1/orders?size=100", {
                token,
            });
            const expected = data.services.commerce.orders.filter(
                (o) => o.buyer_id === account.id,
            );
            for (const order of expected) {
                assert(
                    orders.data.some((o) => o.id === order.id),
                    "Thiếu đơn trên API buyer",
                );
                const detail = await http(
                    rt.gateway + "/api/v1/orders/" + order.id,
                    { token },
                );
                assert.equal(detail.data.total, order.total);
                assert.equal(detail.data.items.length, 2);
            }
            const inbox = await http(
                rt.gateway + "/api/v1/notifications?size=100",
                { token },
            );
            assert.equal(inbox.data.totalElements, 6);
            assert.equal(inbox.data.unreadCount, 3);
            const cart = await http(rt.gateway + "/api/v1/cart", { token });
            assert.equal(cart.data.items.length, 2);
        }
        results.push({
            account: account.key,
            login: true,
            profile: true,
            role: account.role,
        });
        console.log("API login/profile OK:", account.key);
    }
    const list = await http(rt.gateway + "/api/v1/products?size=100");
    const expectedActive = data.services.catalog.products.filter(
        (p) => p.status === "ACTIVE",
    );
    for (const product of expectedActive)
        assert(
            list.data.some((p) => p.id === product.id),
            "Thiếu sản phẩm public",
        );
    const sku = data.services.catalog.skus[0];
    const stock = await http(rt.gateway + "/api/v1/inventory/sku/" + sku.id, {
        token: inventoryToken,
    });
    const expected = data.services.fulfillment.inventories.find(
        (i) => i.sku_id === sku.id && i.warehouse_id === data.warehouseId,
    );
    assert.equal(stock.data.totalQty, expected.total_qty);
    return {
        accounts: results,
        activeProducts: expectedActive.length,
        inventory: true,
    };
}

let locked = false;
const lockFile = path.join(rt.directory, "apply.lock");
try {
    if (mode === "--apply" || mode === "--reset") {
        assert.equal(
            process.argv[process.argv.indexOf("--target") + 1],
            rt.fingerprint,
            "Cần --target đúng fingerprint của --plan",
        );
        fs.writeFileSync(lockFile, String(process.pid), { flag: "wx" });
        locked = true;
    }
    const token = await iamToken(rt);
    // Reset nhiều DB có thể dừng giữa chừng; phải hoàn tất reset trước khi seed/verify lại baseline.
    assert(
        !state.resetting || mode === "--reset" || mode === "--plan",
        "Reset đang dở; chạy lại --reset cùng target",
    );
    const definitions = buildDataset({ asOf: state.asOf }).accounts;
    const discovered = await discoverAccounts(
        rt,
        definitions,
        token,
        state.identities,
    );
    Object.assign(state.identities, discovered);
    const warehouseSchema = ident(rt.targets.fulfillment.schema);
    const warehouseId = sql(
        rt,
        "fulfillment",
        `SELECT id FROM ${warehouseSchema}.warehouses WHERE code='WH_001' AND is_active;`,
    );
    let data = buildDataset({
        asOf: state.asOf,
        userAsOf: state.userAsOf,
        identities: state.identities,
        warehouseId,
    });
    const schemaReport = preflight(rt, data);
    checkpoint();
    exportPlan(data);
    console.log("Dataset:", DATASET, "Target:", rt.fingerprint);
    console.log(JSON.stringify(rt.publicTargets, null, 2));
    if (mode === "--plan") {
        console.log("Plan:", rt.directory, JSON.stringify(schemaReport));
    } else if (mode === "--reset") {
        // Dừng ứng dụng để scheduler không tạo dữ liệu giữa các transaction DB khi reset.
        assert(
            state.completedAt || state.resetting,
            "Chỉ reset dataset đã apply có manifest local",
        );
        const running = execFileSync(
            "docker",
            ["compose", "ps", "--services", "--status", "running"],
            { encoding: "utf8" },
        )
            .trim()
            .split(/\s+/);
        assert(
            !running.some(
                (name) =>
                    name.endsWith("-service") ||
                    name === "api-gateway" ||
                    name === "web",
            ),
            "Dừng backend, Gateway và web trước reset; giữ postgres và keycloak",
        );
        state.resetting = true;
        checkpoint();
        for (const service of [...SERVICES].reverse()) {
            if ((state.resetPhases || []).includes(service)) continue;
            // Giữ User/IAM để không mất tài khoản đăng nhập hoặc tham chiếu danh tính.
            if (service !== "user")
                sql(
                    rt,
                    service,
                    resetSql(
                        service,
                        data.services[service],
                        rt.targets[service].schema,
                    ),
                );
            state.resetPhases = [...(state.resetPhases || []), service];
            checkpoint();
        }
        delete state.completedAt;
        delete state.resetting;
        delete state.resetPhases;
        state.phases = [];
        state.asOf = new Date().toISOString();
        checkpoint();
        console.log(
            "Reset fixture hoàn tất; giữ IAM/schema. Chạy --apply cùng target để seed lại.",
        );
    } else if (mode === "--check") {
        sandbox(data);
    } else {
        const credentialsFile = path.join(rt.directory, "credentials.json");
        let credentials = fs.existsSync(credentialsFile)
            ? JSON.parse(fs.readFileSync(credentialsFile, "utf8"))
            : null;
        if (mode === "--apply" && !state.completedAt) {
            // Kiểm tra schema và SQL thật trước khi tạo bất kỳ tài khoản mới hoặc ghi dữ liệu nghiệp vụ.
            sandbox(data);
            if (!credentials) {
                credentials = {
                    dataset: DATASET,
                    fingerprint: rt.fingerprint,
                    password: "NxDemo1!" + randomBytes(16).toString("hex"),
                };
                save(credentialsFile, credentials);
            }
            await ensureAccounts(
                rt,
                data.accounts,
                state,
                credentials.password,
                checkpoint,
            );
            data = buildDataset({
                asOf: state.asOf,
                userAsOf: state.userAsOf,
                identities: state.identities,
                warehouseId,
            });
            preflight(rt, data);
            exportPlan(data);
            for (const service of SERVICES) {
                if (state.phases.includes(service)) continue;
                sql(
                    rt,
                    service,
                    seedSql(
                        service,
                        data.services[service],
                        rt.targets[service].schema,
                    ),
                );
                state.phases.push(service);
                checkpoint();
                console.log("Committed demo fixture:", service);
            }
            state.completedAt = new Date().toISOString();
            checkpoint();
        }
        assert(
            credentials,
            "Thiếu credential demo local; không tự reset mật khẩu IAM",
        );
        verifyDatabase(data);
        const api = await verifyApis(data, credentials.password);
        save(credentialsFile, {
            ...credentials,
            accounts: data.accounts.map(({ key, email, role }) => ({
                key,
                email,
                role,
            })),
        });
        save(path.join(rt.directory, "verification.json"), {
            dataset: DATASET,
            fingerprint: rt.fingerprint,
            verifiedAt: new Date().toISOString(),
            schema: schemaReport,
            api,
            transientReadRetries: HTTP_READ_RETRIES,
        });
        state.verifiedAt = new Date().toISOString();
        checkpoint();
        console.log("Verified. Credentials (local only):", credentialsFile);
    }
} catch (error) {
    console.error(error.message);
    console.error(
        "Dừng tại checkpoint; không rollback dữ liệu của DB khác. Chạy lại sau khi xử lý nguyên nhân, không xóa dữ liệu nhóm.",
    );
    process.exitCode = 1;
} finally {
    if (locked) fs.unlinkSync(lockFile);
}
