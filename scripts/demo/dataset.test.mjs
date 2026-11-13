import test from "node:test";
import assert from "node:assert/strict";
import { buildDataset, validateDataset, uid } from "./dataset.mjs";
import { seedSql, ident } from "./runtime.mjs";

/** Bảo vệ contract mới: mã SKU ở kho, Catalog bỏ price, payment có snapshot và ref kho riêng. */
test("fixture tương thích Catalog V7, Fulfillment V6 và Commerce V12", () => {
    const { services } = buildDataset({ asOf: "2026-11-13T00:00:00Z" });
    assert(services.catalog.categories.every((c) => c.name_vi));
    assert(
        services.catalog.skus.every(
            (sku) => !("price" in sku) && !("sku_code" in sku),
        ),
    );
    const masters = new Set(
        services.fulfillment.seller_skus.map((sku) => sku.id),
    );
    assert(
        services.fulfillment.inventories.every((row) =>
            masters.has(row.seller_sku_id),
        ),
    );
    for (const payment of services.commerce.payments) {
        assert.equal(payment.price_snapshot.total, payment.amount);
        assert.equal(payment.inventory_ref_id, payment.order_id);
        assert.equal(payment.merchant_code, "DEMO_ONLY");
    }
});

const create = () => buildDataset({ asOf: "2026-11-04T12:00:00.000Z" });

/** Reset làm mới lịch phiên, nhưng giữ hồ sơ cũ để lần apply không xung đột User hoặc đổi IAM. */
test("reset mốc nghiệp vụ giữ timestamp User", () => {
    const previous = create();
    const next = buildDataset({
        asOf: "2026-11-13T00:00:00Z",
        userAsOf: previous.asOf,
    });
    assert.deepEqual(previous.services.user, next.services.user);
    assert.notDeepEqual(
        previous.services.auction.auctions,
        next.services.auction.auctions,
    );
});

/** Hồi quy seed: cùng input phải có cùng UUID/thời gian, VND nguyên và liên kết đủ cho 7 service. */
test("fixture ổn định, đủ vai trò và giữ các bất biến nghiệp vụ", () => {
    assert.deepEqual(create(), create());
    const data = create();
    assert.equal(data.accounts.length, 8);
    assert.equal(data.services.catalog.products.length, 18);
    assert.equal(data.services.catalog.skus.length, 36);
    assert.equal(data.services.commerce.orders.length, 9);
    assert.equal(data.services.fulfillment.inventories.length, 42);
    assert(validateDataset(data));
    assert.equal(uid("sku:0:0"), data.services.catalog.skus[0].id);
});

/** Không cho thanh toán giả lập lẫn merchant thật, sai người bán, sai tổng hoặc tồn kho vượt mức lọt tới DB. */
for (const [name, mutate] of [
    ["tổng tiền", (d) => d.services.commerce.orders[0].total++],
    [
        "seller",
        (d) => (d.services.commerce.orders[0].seller_id = d.accounts[0].id),
    ],
    ["kho âm", (d) => (d.services.fulfillment.inventories[0].total_qty = -1)],
    ["ledger", (d) => d.services.fulfillment.inventory_ledger[0].quantity++],
    [
        "merchant",
        (d) =>
            (d.services.commerce.payments[0].merchant_code = "REAL_MERCHANT"),
    ],
    [
        "winner",
        (d) =>
            (d.services.auction.auction_settlements[0].winner_id =
                d.accounts[0].id),
    ],
    [
        "người nhận",
        (d) =>
            (d.services.notification.notifications[0].user_id =
                d.accounts[0].id),
    ],
    [
        "review",
        (d) =>
            (d.services.catalog.product_reviews[0].user_id = d.accounts[0].id),
    ],
    ["CCCD", (d) => (d.accounts[0].identity = "079xxxxxxxxx")],
    ["phone", (d) => (d.accounts[0].phone = "09123456789")],
])
    test("chặn fixture sai " + name, () => {
        const data = create();
        mutate(data);
        assert.throws(() => validateDataset(data));
    });

/** SQL không được nâng seed thành reset DB hoặc ghi đè; JSON literal phải escape apostrophe và whitelist identifier. */
test("SQL chỉ insert trong transaction, có chống ghi đè và thứ tự FK đúng", () => {
    const data = create();
    data.services.catalog.products[0].name = "Sổ tay 'Nexus'";
    const query = seedSql("catalog", data.services.catalog, "nexus_catalog");
    assert(query.includes("Sổ tay ''Nexus''"));
    assert(query.includes("BEGIN;") && query.includes("COMMIT;"));
    assert(query.includes("IS DISTINCT FROM") && query.includes("DO NOTHING"));
    assert(!/\b(?:DELETE|TRUNCATE|DROP|UPDATE)\b/.test(query));
    assert(
        query.indexOf('INSERT INTO "nexus_catalog"."products"') <
            query.indexOf('INSERT INTO "nexus_catalog"."skus"'),
    );
    assert.throws(() => ident("public;drop table users"));
});

/** UUID IAM thực phải lan sang mọi FK logic, không còn liên kết tới UUID xem trước. */
test("nhận UUID IAM thật thay cho UUID preview mà vẫn giữ toàn bộ bất biến", () => {
    const identities = Object.fromEntries(
        create().accounts.map((a) => [a.key, uid("actual-iam:" + a.key)]),
    );
    const data = buildDataset({ asOf: "2026-11-04T12:00:00Z", identities });
    assert(validateDataset(data));
    for (const account of data.accounts)
        assert.equal(account.id, identities[account.key]);
});
