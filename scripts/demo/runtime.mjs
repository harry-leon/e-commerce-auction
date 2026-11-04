import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { DATASET, SERVICES, VERSIONS } from "./dataset.mjs";

/** Đọc cấu hình Compose thực, giữ credential trong bộ nhớ; fingerprint chỉ chứa đích host/DB/schema, không chứa secret. */
export function runtime() {
    const config = JSON.parse(
        execFileSync("docker", ["compose", "config", "--format", "json"], {
            encoding: "utf8",
            stdio: ["pipe", "pipe", "pipe"],
        }),
    );
    const targets = Object.fromEntries(
        SERVICES.map((name) => {
            const env = config.services[`${name}-service`].environment;
            const url = new URL(env.DB_URL.replace(/^jdbc:/, ""));
            const schema = (
                url.searchParams.get("currentSchema") || "public"
            ).split(",")[0];
            ident(schema);
            return [
                name,
                {
                    host: url.hostname,
                    port: url.port || "5432",
                    database: url.pathname.slice(1),
                    schema,
                    username: env.DB_USERNAME,
                    password: env.DB_PASSWORD,
                    ssl: url.searchParams.get("sslmode") || "prefer",
                },
            ];
        }),
    );
    const publicTargets = Object.fromEntries(
        Object.entries(targets).map(
            ([key, { host, port, database, schema }]) => [
                key,
                { host, port, database, schema },
            ],
        ),
    );
    const fingerprint = createHash("sha256")
        .update(JSON.stringify(publicTargets))
        .digest("hex")
        .slice(0, 16);
    const gitDir = execFileSync("git", ["rev-parse", "--absolute-git-dir"], {
        encoding: "utf8",
    }).trim();
    const directory = path.join(gitDir, "nexus-demo", fingerprint);
    fs.mkdirSync(directory, { recursive: true });
    const env = config.services["api-gateway"].environment;
    const issuer = new URL(
        env.KEYCLOAK_ISSUER || "http://localhost:8180/realms/nexus-realm",
    );
    const gatewayPort = config.services["api-gateway"].ports.find(
        (p) => p.target === 8080,
    ).published;
    return {
        config,
        targets,
        publicTargets,
        fingerprint,
        directory,
        gateway: `http://127.0.0.1:${gatewayPort}`,
        iam: issuer.origin,
        realm: issuer.pathname.split("/").filter(Boolean).at(-1),
        client: env.KEYCLOAK_ADMIN_CLIENT_ID || "nexus-admin-cli",
        secret: env.KEYCLOAK_ADMIN_CLIENT_SECRET,
    };
}

/** Whitelist identifier vì schema/table không thể dùng tham số SQL; giá trị row luôn qua literal JSON đã escape. */
export function ident(value) {
    if (!/^[a-z][a-z0-9_]*$/.test(value))
        throw new Error("Tên schema/table/column không hợp lệ");
    return `"${value}"`;
}
/** Escape literal SQL cho JSON/dữ liệu giả lập; không dùng hàm này cho identifier hoặc credential. */
export const literal = (value) => `'${String(value).replaceAll("'", "''")}'`;
/** Hai bảng ERD có PK khác id; dùng cùng khóa cho chống trùng và kiểm chứng sau seed. */
export const primaryKey = (table) =>
    table === "reputations"
        ? ["user_id"]
        : table === "order_reservations"
          ? ["order_id", "reservation_id"]
          : ["id"];

/**
 * Chạy psql trong container postgres hiện có, mỗi lần kết nối đúng một service.
 * Password chỉ truyền bằng environment tiến trình, SQL qua stdin; không đưa secret vào argv/log/file SQL.
 */
export function sql(rt, service, query, target = rt.targets[service]) {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            return execFileSync(
                "docker",
                [
                    "compose",
                    "exec",
                    "-T",
                    "-e",
                    "PGPASSWORD",
                    "-e",
                    "PGSSLMODE",
                    "-e",
                    "PGCONNECT_TIMEOUT",
                    "postgres",
                    "psql",
                    "-XqAt",
                    "-v",
                    "ON_ERROR_STOP=1",
                    "-h",
                    target.host,
                    "-p",
                    String(target.port),
                    "-U",
                    target.username,
                    "-d",
                    target.database,
                ],
                {
                    encoding: "utf8",
                    input: query,
                    maxBuffer: 16 * 1024 * 1024,
                    env: {
                        ...process.env,
                        PGPASSWORD: target.password,
                        PGSSLMODE: target.ssl,
                        PGCONNECT_TIMEOUT: "10",
                    },
                    stdio: ["pipe", "pipe", "pipe"],
                },
            ).trim();
        } catch (error) {
            // DNS thất bại trước kết nối nên chưa gửi SQL; chỉ case này được thử lại ngắn tối đa hai lần.
            // Không tự retry mất ACK/connection trong transaction vì chưa biết COMMIT đã thành công hay chưa.
            if (
                attempt < 2 &&
                /could not translate host name/.test(String(error.stderr || ""))
            ) {
                Atomics.wait(
                    new Int32Array(new SharedArrayBuffer(4)),
                    0,
                    0,
                    500 * 2 ** attempt,
                );
                continue;
            }
            const details = String(error.stderr || "")
                .split("\n")
                .filter((line) =>
                    /ERROR:|FATAL:|could not|connection to server/.test(line),
                )
                .slice(0, 2)
                .join(" ");
            throw new Error(
                `PostgreSQL ${service}: ${details || "không thực hiện được truy vấn"}`,
            );
        }
    }
}

/** Ghi manifest atomically; trạng thái và credential chỉ nằm dưới Git directory, không đưa vào commit. */
export function save(file, value) {
    fs.writeFileSync(file + ".tmp", JSON.stringify(value, null, 2) + "\n", {
        mode: 0o600,
    });
    fs.renameSync(file + ".tmp", file);
}

/** Kiểm tra version Flyway, cột và kho mặc định bằng SELECT trước mọi INSERT lên dữ liệu đang dùng. */
export function preflight(rt, data) {
    const result = {};
    for (const service of SERVICES) {
        const { schema } = rt.targets[service];
        const info = JSON.parse(
            sql(
                rt,
                service,
                `SELECT json_build_object('version',
            (SELECT max(version::int) FROM ${ident(schema)}.flyway_schema_history WHERE success),
            'columns',(SELECT json_object_agg(table_name,cols) FROM (SELECT table_name,json_agg(column_name) cols
            FROM information_schema.columns WHERE table_schema=${literal(schema)} GROUP BY table_name) t));`,
            ),
        );
        if (info.version !== VERSIONS[service])
            throw new Error(
                `Version schema ${service} khác bản seed: ${info.version}`,
            );
        for (const [table, records] of Object.entries(data.services[service]))
            for (const row of records) {
                for (const column of Object.keys(row))
                    if (!info.columns[table]?.includes(column))
                        throw new Error(
                            `Thiếu cột ${service}.${table}.${column}`,
                        );
            }
        result[service] = {
            version: info.version,
            tables: Object.fromEntries(
                Object.entries(data.services[service]).map(
                    ([table, records]) => [table, records.length],
                ),
            ),
        };
    }
    const schema = ident(rt.targets.fulfillment.schema);
    const warehouses = JSON.parse(
        sql(
            rt,
            "fulfillment",
            `SELECT coalesce(json_agg(t),'[]') FROM ${schema}.warehouses t WHERE code='WH_001' AND is_active;`,
        ),
    );
    if (warehouses.length !== 1 || warehouses[0].id !== data.warehouseId)
        throw new Error(
            "Kho WH_001 không khớp; dừng để tránh gán tồn nhầm kho",
        );
    return result;
}

const TABLE_ORDER = {
    user: ["users"],
    catalog: [
        "categories",
        "products",
        "skus",
        "product_images",
        "product_reviews",
    ],
    fulfillment: [
        "warehouses",
        "carriers",
        "inventories",
        "inventory_reservations",
        "inventory_ledger",
        "shipments",
        "shipment_status_history",
    ],
    commerce: [
        "carts",
        "cart_items",
        "orders",
        "order_items",
        "payments",
        "order_status_history",
        "order_reservations",
    ],
    auction: ["auctions", "bids", "auction_settlements"],
    reputation: ["reputations", "ratings"],
    notification: ["notifications", "notification_attempts"],
};

/**
 * Sinh transaction chỉ INSERT theo PK riêng của dataset, không UPDATE/DELETE/TRUNCATE và không sửa migration.
 * PK trùng nhưng nội dung khác làm rollback cả service; natural-key conflict cũng dừng, không ghi đè dữ liệu có sẵn.
 * Chỉ có atomicity trong từng DB; manifest lưu checkpoint để resume khi service sau lỗi.
 */
export function seedSql(service, records, schema) {
    const statements = [
        "BEGIN;",
        "SET LOCAL lock_timeout='5s';",
        "SET LOCAL statement_timeout='60s';",
        `SELECT pg_advisory_xact_lock(hashtextextended(${literal(DATASET + ":" + service)},0));`,
    ];
    for (const table of TABLE_ORDER[service])
        for (const row of records[table] || []) {
            const qualified = `${ident(schema)}.${ident(table)}`;
            const columns = Object.keys(row).map(ident);
            const incoming = `json_populate_record(NULL::${qualified},${literal(JSON.stringify(row))})`;
            const pk = primaryKey(table)
                .map((key) => `t.${ident(key)}=i.${ident(key)}`)
                .join(" AND ");
            const different = columns
                .map((c) => `t.${c} IS DISTINCT FROM i.${c}`)
                .join(" OR ");
            statements.push(`DO $seed$ BEGIN IF EXISTS (SELECT 1 FROM ${qualified} t,${incoming} i WHERE ${pk} AND (${different}))
            THEN RAISE EXCEPTION 'Dataset row conflict: ${service}.${table}'; END IF; END $seed$;`);
            statements.push(`INSERT INTO ${qualified} (${columns}) SELECT ${columns} FROM ${incoming}
            ON CONFLICT (${primaryKey(table).map(ident)}) DO NOTHING;`);
        }
    statements.push("COMMIT;");
    return (
        "-- Fixture Nexus Demo; thanh toán/lịch sử giả lập, không chứa credential.\n" +
        statements.join("\n") +
        "\n"
    );
}

/** Đọc lại đúng các PK của dataset; không trả dữ liệu người dùng khác ra báo cáo. */
export function readRows(rt, service, records, target = rt.targets[service]) {
    const found = {};
    for (const [table, rows] of Object.entries(records)) {
        const keys = primaryKey(table);
        const predicates = rows
            .map(
                (row) =>
                    "(" +
                    keys
                        .map(
                            (key) => `${ident(key)}=${literal(row[key])}::uuid`,
                        )
                        .join(" AND ") +
                    ")",
            )
            .join(" OR ");
        found[table] = JSON.parse(
            sql(
                rt,
                service,
                `SELECT coalesce(json_agg(t),'[]') FROM ${ident(target.schema)}.${ident(table)} t WHERE ${predicates};`,
                target,
            ),
        );
        if (found[table].length !== rows.length)
            throw new Error(
                `Thiếu dữ liệu ${service}.${table}: ${found[table].length}/${rows.length}`,
            );
    }
    return found;
}

/** Lưu retry đọc để báo cáo không che lỗi hạ tầng tạm thời; chỉ giữ path/status, không giữ header/body/token. */
export const HTTP_READ_RETRIES = [];

/** HTTP có timeout, GET 502/503/504 được thử lại tối đa hai lần; không retry POST hoặc lỗi auth/nghiệp vụ. */
export async function http(
    url,
    { token, method = "GET", body, headers = {} } = {},
) {
    for (let attempt = 0; attempt < 3; attempt++) {
        const result = await fetch(url, {
            method,
            headers: {
                ...(token ? { Authorization: "Bearer " + token } : {}),
                ...(body ? { "Content-Type": "application/json" } : {}),
                ...headers,
            },
            ...(body ? { body: JSON.stringify(body) } : {}),
            signal: AbortSignal.timeout(30000),
        });
        if (
            method === "GET" &&
            [502, 503, 504].includes(result.status) &&
            attempt < 2
        ) {
            await result.body?.cancel();
            const retry = {
                path: new URL(url).pathname,
                status: result.status,
                attempt: attempt + 1,
            };
            HTTP_READ_RETRIES.push(retry);
            console.warn("Retry GET tạm thời:", retry.path, retry.status);
            await new Promise((resolve) =>
                setTimeout(resolve, 500 * (attempt + 1)),
            );
            continue;
        }
        if (!result.ok)
            throw new Error(`HTTP ${result.status}: ${new URL(url).pathname}`);
        return result.status === 204 ||
            result.headers.get("content-length") === "0"
            ? null
            : result.json().catch(() => null);
    }
}

/** Service account chỉ dùng cho Admin API IAM; token giữ trong bộ nhớ, không cấp quyền cho tài khoản đang có. */
export async function iamToken(rt) {
    const r = await fetch(
        `${rt.iam}/realms/${rt.realm}/protocol/openid-connect/token`,
        {
            method: "POST",
            body: new URLSearchParams({
                grant_type: "client_credentials",
                client_id: rt.client,
                client_secret: rt.secret,
            }),
            signal: AbortSignal.timeout(20000),
        },
    );
    if (!r.ok)
        throw new Error("IAM service account không sẵn sàng: HTTP " + r.status);
    return (await r.json()).access_token;
}

/**
 * Tìm exact email và kiểm tra marker hoặc UUID đã checkpoint sau POST tạo user thành công.
 * Keycloak có thể lọc custom attributes theo User Profile; fallback chỉ dùng UUID local đã biết, không nhận theo email đơn thuần.
 * Mất manifest mà IAM không giữ marker thì phải dừng, không tự nhận/nâng quyền tài khoản có sẵn.
 */
export async function discoverAccounts(
    rt,
    accounts,
    token,
    knownIdentities = {},
) {
    const found = {};
    const root = `${rt.iam}/admin/realms/${rt.realm}`;
    for (const account of accounts) {
        const rows = await http(
            root +
                "/users?exact=true&email=" +
                encodeURIComponent(account.email),
            { token },
        );
        if (rows.length > 1)
            throw new Error("IAM có nhiều tài khoản cùng email demo");
        // Đọc representation đầy đủ vì endpoint search có thể lược bỏ attributes khi bật brief representation.
        const detail = rows.length
            ? await http(root + "/users/" + rows[0].id, { token })
            : null;
        const marked =
            detail?.attributes?.nexus_demo_dataset?.[0] === DATASET &&
            detail?.attributes?.nexus_demo_key?.[0] === account.key;
        const checkpointed =
            detail &&
            knownIdentities[account.key] === detail.id &&
            detail.email === account.email &&
            detail.username === account.email;
        if (detail && !marked && !checkpointed)
            throw new Error("Email demo đã thuộc tài khoản khác; không ghi đè");
        if (rows.length) found[account.key] = rows[0].id;
    }
    for (const role of new Set(["BUYER", ...accounts.map((a) => a.role)]))
        await http(root + "/roles/ROLE_" + role, { token });
    return found;
}

/** Chỉ tạo IAM user mới có marker, gán role hiện có; chạy lại giữ mật khẩu và role đã tồn tại, không sửa user ngoài demo. */
export async function ensureAccounts(
    rt,
    accounts,
    state,
    password,
    checkpoint,
) {
    let token = await iamToken(rt);
    const root = `${rt.iam}/admin/realms/${rt.realm}`;
    const existing = await discoverAccounts(
        rt,
        accounts,
        token,
        state.identities,
    );
    for (const account of accounts) {
        if (!existing[account.key]) {
            await http(root + "/users", {
                token,
                method: "POST",
                body: {
                    username: account.email,
                    email: account.email,
                    firstName: account.name.split(" ").at(-1),
                    lastName: account.name.split(" ").slice(0, -1).join(" "),
                    enabled: true,
                    emailVerified: true,
                    requiredActions: [],
                    attributes: {
                        nexus_demo_dataset: [DATASET],
                        nexus_demo_key: [account.key],
                    },
                    credentials: [
                        { type: "password", value: password, temporary: false },
                    ],
                },
            });
            const rows = await http(
                root +
                    "/users?exact=true&email=" +
                    encodeURIComponent(account.email),
                { token },
            );
            if (rows.length !== 1)
                throw new Error(
                    "Không xác định được user vừa tạo; chạy lại để resume",
                );
            existing[account.key] = rows[0].id;
        }
        state.identities[account.key] = existing[account.key];
        checkpoint();
        const assigned = await http(
            `${root}/users/${existing[account.key]}/role-mappings/realm`,
            { token },
        );
        const desired = [...new Set(["BUYER", account.role])].filter(
            (role) => !assigned.some((r) => r.name === "ROLE_" + role),
        );
        if (desired.length) {
            const roles = [];
            for (const role of desired)
                roles.push(await http(root + "/roles/ROLE_" + role, { token }));
            await http(
                `${root}/users/${existing[account.key]}/role-mappings/realm`,
                { token, method: "POST", body: roles },
            );
        }
        console.log("IAM sẵn sàng:", account.key);
        // Mỗi batch nhiều request vẫn dùng token mới để không lưu refresh/access token vào manifest.
        token = await iamToken(rt);
    }
}
