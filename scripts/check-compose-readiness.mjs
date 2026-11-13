import fs from "node:fs";
import { execFileSync } from "node:child_process";

/**
 * Smoke chỉ đọc môi trường Compose: HTTP public/protected và WS ẩn danh, không seed hoặc gọi thanh toán.
 * Lấy port từ cấu hình trong RAM; chỉ ghi status/assertion, không ghi env/token/response body vào evidence.
 * Kết quả không thay UAT winner hoặc giao dịch sandbox; timeout/lỗi làm tiến trình trả mã khác 0.
 */
const config = JSON.parse(
    execFileSync("docker", ["compose", "config", "--format", "json"], {
        encoding: "utf8",
    }),
);
const port = (name, target) =>
    config.services[name].ports.find((p) => p.target === target).published;
const gateway = `http://127.0.0.1:${port("api-gateway", 8080)}`;
const web = `http://127.0.0.1:${port("web", 8080)}`;
const checks = [];
for (const [name, url, expected] of [
    ["web", web, 200],
    ["gateway-health", `${gateway}/actuator/health`, 200],
    ["public-products", `${gateway}/api/v1/products`, 200],
    ["public-auctions", `${gateway}/api/v1/auctions`, 200],
    ["protected-orders", `${gateway}/api/v1/orders`, 401],
]) {
    try {
        const response = await fetch(url, {
            signal: AbortSignal.timeout(10000),
        });
        checks.push({
            name,
            status: response.status,
            expected,
            pass: response.status === expected,
        });
        await response.body?.cancel();
    } catch {
        checks.push({
            name,
            pass: false,
            error: "Connection failed or timed out",
        });
    }
}
// Upgrade thành công chưa cấp quyền: CONNECT ẩn danh phải nhận ERROR và socket bị đóng.
checks.push(
    await new Promise((resolve) => {
        const socket = new WebSocket(
            gateway.replace("http:", "ws:") + "/ws/auctions",
            ["v12.stomp"],
        );
        let upgraded = false;
        let denied = false;
        let finished = false;
        const finish = (closed) => {
            if (finished) return;
            finished = true;
            clearTimeout(timer);
            socket.close();
            resolve({
                name: "websocket-anonymous-denied",
                upgraded,
                denied,
                closed,
                pass: upgraded && denied && closed,
            });
        };
        const timer = setTimeout(() => finish(false), 12000);
        socket.onopen = () => {
            upgraded = true;
            socket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0");
        };
        socket.onmessage = (event) => {
            if (String(event.data).startsWith("ERROR")) denied = true;
        };
        socket.onclose = () => finish(true);
        socket.onerror = () => finish(false);
    }),
);
const output = {
    checkedAt: new Date().toISOString(),
    scope: "read-only Compose smoke; no winner payment UAT",
    checks,
};
const directory = "docs/test-evidence/tt-s2-4";
fs.mkdirSync(directory, { recursive: true });
fs.writeFileSync(
    `${directory}/readiness.json`,
    JSON.stringify(output, null, 2) + "\n",
);
for (const check of checks)
    console.log(`${check.pass ? "PASS" : "FAIL"} ${check.name}`);
if (checks.some((c) => !c.pass)) process.exitCode = 1;
