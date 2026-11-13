import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import net from "node:net";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { buildDataset, SERVICES, VERSIONS } from "./demo/dataset.mjs";
import { seedSql, resetSql } from "./demo/runtime.mjs";

/**
 * Kiểm chứng migration/seed/reset bằng cluster PostgreSQL tạm trên loopback, không đọc .env.
 * Nhận thư mục bin PostgreSQL qua argv; giữ thư mục tạm để điều tra và luôn dừng cluster trong finally.
 * Kiểm tra cả FK CASCADE ngoài fixture: reset phải rollback, không được xóa dữ liệu liên quan.
 */
const bin = process.argv[2];
assert(bin, "Truyền đường dẫn bin PostgreSQL");
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "nexus-demo-check-"));
const dataDirectory = path.join(directory, "pgdata");
const server = net.createServer();
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const port = server.address().port;
await new Promise((resolve) => server.close(resolve));
const executable = (name) =>
    path.join(bin, name + (process.platform === "win32" ? ".exe" : ""));
// Xóa biến kết nối kế thừa để mọi câu SQL chỉ đến cluster do lượt kiểm tra này tạo.
const env = {
    ...Object.fromEntries(
        Object.entries(process.env).filter(
            ([key]) => !key.toUpperCase().startsWith("PG"),
        ),
    ),
    PGHOST: "127.0.0.1",
    PGPORT: String(port),
    PGUSER: "postgres",
};
const run = (name, args, input) =>
    execFileSync(executable(name), args, {
        // pg_ctl trên Windows có thể để postgres giữ pipe của tiến trình cha; bỏ pipe cho riêng lệnh điều khiển.
        env,
        input,
        encoding: "utf8",
        windowsHide: true,
        stdio:
            name === "pg_ctl"
                ? ["ignore", "ignore", "ignore"]
                : ["pipe", "pipe", "pipe"],
    });
const sql = (db, input) =>
    run("psql", ["-XqAt", "-v", "ON_ERROR_STOP=1", "-d", db], input);
let started = false;
try {
    run("initdb", [
        "-D",
        dataDirectory,
        "-U",
        "postgres",
        "-A",
        "trust",
        "--encoding=UTF8",
        "--locale=C",
    ]);
    run("pg_ctl", [
        "-D",
        dataDirectory,
        "-l",
        path.join(directory, "postgres.log"),
        "-o",
        `-h 127.0.0.1 -p ${port}`,
        "-w",
        "start",
    ]);
    started = true;
    const fixture = buildDataset({ asOf: "2026-11-13T00:00:00Z" });
    for (const service of SERVICES) {
        const db = `demo_${service}`;
        sql("postgres", `CREATE DATABASE ${db};`);
        const migrations = path.join(
            "backend",
            `${service}-service`,
            "src/main/resources/db/migration",
        );
        // Chặn seed quên nâng version khi repository có migration mới, trước khi kiểm chứng SQL.
        assert.equal(
            Math.max(
                ...fs
                    .readdirSync(migrations)
                    .filter((f) => /^V\d+__.*\.sql$/.test(f))
                    .map((f) => Number(f.match(/^V(\d+)/)[1])),
            ),
            VERSIONS[service],
        );
        for (const file of fs
            .readdirSync(migrations)
            .filter((f) => /^V\d+__.*\.sql$/.test(f))
            .sort(
                (a, b) =>
                    Number(a.match(/^V(\d+)/)[1]) -
                    Number(b.match(/^V(\d+)/)[1]),
            )) {
            sql(db, fs.readFileSync(path.join(migrations, file), "utf8"));
        }
        const records = fixture.services[service];
        sql(db, seedSql(service, records, "public"));
        sql(db, seedSql(service, records, "public"));
        if (service === "catalog") {
            sql(
                db,
                `CREATE TABLE reset_probe(id uuid PRIMARY KEY, product_id uuid REFERENCES products(id) ON DELETE CASCADE);
                INSERT INTO reset_probe VALUES (gen_random_uuid(), '${records.products[0].id}');`,
            );
            assert.throws(
                () => sql(db, resetSql(service, records, "public")),
                /Reset blocked by external references/,
            );
            assert.equal(
                sql(db, "SELECT count(*) FROM reset_probe;").trim(),
                "1",
            );
            sql(db, "DROP TABLE reset_probe;");
        }
        sql(db, resetSql(service, records, "public"));
        sql(db, resetSql(service, records, "public"));
        sql(db, seedSql(service, records, "public"));
        console.log(
            `PASS ${service}: migration, seed replay, reset replay, reseed`,
        );
    }
} finally {
    if (started)
        run("pg_ctl", ["-D", dataDirectory, "-m", "fast", "-w", "stop"]);
    console.log("Cluster tạm đã dừng; artifacts:", directory);
}
