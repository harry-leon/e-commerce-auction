import { randomBytes, randomUUID } from "node:crypto";

/** IAM fixture riêng cho integration: không đọc realm/.env thật và không cấp quyền merchant/kho cho notifier. */
export function createIamFixture(realmName, { dispatch = false } = {}) {
    const password = `Test!${randomBytes(18).toString("hex")}`;
    const secrets = Object.fromEntries(["nexus-fulfillment-service", "nexus-commerce-notifier", "nexus-wrong-service"]
        .map((id) => [id, randomBytes(24).toString("hex")]));
    const roles = ["ADMIN", "BUYER", "NOTIFICATION_WRITER", "COMMERCE.SHIPPING_EVENTS", "COMMERCE.SHIPPING_READ"];
    const grants = {
        "nexus-fulfillment-service": ["COMMERCE.SHIPPING_EVENTS", "COMMERCE.SHIPPING_READ"],
        "nexus-commerce-notifier": ["NOTIFICATION_WRITER"],
        "nexus-wrong-service": ["COMMERCE.SHIPPING_EVENTS"],
    };
    // Chế độ dispatch cấp cùng privilege cho client sai để kiểm tra azp độc lập với role;
    // notifier vẫn chỉ được gửi notification, còn fixture transport mặc định giữ nguyên.
    if (dispatch) {
        roles.push("COMMERCE.SHIPPING_DISPATCH");
        grants["nexus-fulfillment-service"].push("COMMERCE.SHIPPING_DISPATCH");
        grants["nexus-wrong-service"].push("COMMERCE.SHIPPING_DISPATCH", "COMMERCE.SHIPPING_READ");
    }
    const users = ["admin", "buyer", "other"].map((username) => ({
        id: randomUUID(), username, enabled: true, emailVerified: true, firstName: "E2E", lastName: "Shipping",
        email: `${username}@example.test`, requiredActions: [], realmRoles: [username === "admin" ? "ADMIN" : "BUYER"],
        credentials: [{ type: "password", value: password, temporary: false }],
    }));
    return {
        password, secrets, users,
        realm: {
            realm: realmName, enabled: true, sslRequired: "none", registrationAllowed: false, accessTokenLifespan: 600,
            roles: { realm: roles.map((name) => ({ name })) },
            clients: [
                { clientId: "shipping-e2e-web", publicClient: true, enabled: true, protocol: "openid-connect",
                    directAccessGrantsEnabled: true, standardFlowEnabled: false, fullScopeAllowed: true, defaultClientScopes: ["roles"] },
                ...Object.entries(secrets).map(([clientId, secret]) => ({ clientId, secret, enabled: true, protocol: "openid-connect",
                    publicClient: false, serviceAccountsEnabled: true, standardFlowEnabled: false, directAccessGrantsEnabled: false,
                    fullScopeAllowed: true, defaultClientScopes: ["roles"] })),
            ],
            users: [...users, ...Object.entries(grants).map(([serviceAccountClientId, realmRoles]) => ({
                username: `service-account-${serviceAccountClientId}`, enabled: true, serviceAccountClientId, realmRoles,
            }))],
        },
    };
}

/** Chỉ dựng fixture đơn đã PROCESSING cho transport test; không tạo giao dịch hoặc gọi refund/payment provider. */
export function createFlowFixture(buyer) {
    const orderId = randomUUID(); const shipmentId = randomUUID();
    const trackingNumber = `TRACK-${randomUUID()}`;
    const events = [
        ["ShipmentCreated", "PENDING"], ["ShipmentPickedUp", "PICKED_UP"], ["ShipmentDelivered", "DELIVERED"],
    ].map(([eventType, status]) => ({ eventId: randomUUID(), eventType, occurredAt: new Date().toISOString(), shipmentId,
        orderId, carrier: "GHN", trackingNumber, status }));
    return { orderId, shipmentId, trackingNumber, buyer, seller: randomUUID(), events };
}

/** Escape literal SQL của fixture; bảng/cột luôn cố định trong harness, không nhận câu SQL từ HTTP hoặc .env. */
export const literal = (value) => `'${String(value).replaceAll("'", "''")}'`;
