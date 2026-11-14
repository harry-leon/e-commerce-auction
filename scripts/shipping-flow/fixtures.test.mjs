import test from "node:test";
import assert from "node:assert/strict";
import { createIamFixture, createFlowFixture } from "./fixtures.mjs";

/** Notifier không được quyền Shipping/merchant, client sai có cùng privilege để test azp độc lập với role. */
test("realm fixture separates notifier, shipping identity and user roles", () => {
    const fixture = createIamFixture("isolated-test");
    const account = (id) => fixture.realm.users.find((user) => user.serviceAccountClientId === id);
    assert.deepEqual(account("nexus-commerce-notifier").realmRoles, ["NOTIFICATION_WRITER"]);
    assert.deepEqual(account("nexus-wrong-service").realmRoles, ["COMMERCE.SHIPPING_EVENTS"]);
    assert.equal(fixture.realm.users.find((user) => user.username === "buyer").realmRoles.includes("ADMIN"), false);
    assert.notEqual(fixture.secrets["nexus-fulfillment-service"], createIamFixture("other").secrets["nexus-fulfillment-service"]);
});

/** Ba event cùng order/shipment nhưng id khác nhau, không giả lập giao dịch provider hoặc dữ liệu người dùng thật. */
test("shipping fixture keeps aggregate identity across lifecycle", () => {
    const fixture = createFlowFixture("test-buyer");
    assert.equal(new Set(fixture.events.map((event) => event.eventId)).size, 3);
    assert.equal(new Set(fixture.events.map((event) => event.orderId)).size, 1);
    assert.equal(new Set(fixture.events.map((event) => event.shipmentId)).size, 1);
    assert.equal(fixture.events.at(-1).status, "DELIVERED");
});
