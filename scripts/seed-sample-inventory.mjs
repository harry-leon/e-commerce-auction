/**
 * Script khởi tạo dữ liệu kho hàng và tồn kho mẫu (idempotent) phục vụ dev/demo cho Frontend (Đang) và Admin/Seller.
 *
 * Chức năng:
 * - Cung cấp danh sách kho hàng mẫu (WH_001, WH_002, WH_003, WH_RETURN) và payload nhập kho/điều chỉnh mẫu.
 * - Hỗ trợ chế độ `--dry-run` (mặc định khi không có token) để in trước cấu trúc dữ liệu mà không cần DB/service chạy sẵn.
 * - Khi có token (`NEXUS_ACCESS_TOKEN` hoặc `--token`) và API Gateway sẵn sàng, script tra cứu kho/SKU, gọi `/api/v1/inventory/inbound`
 *   với `Idempotency-Key` cố định theo từng lô để chạy lại nhiều lần không làm cộng dồn tồn kho, rồi gọi `/api/v1/inventory/reconcile`.
 *
 * Cách dùng:
 *   node scripts/seed-sample-inventory.mjs --dry-run
 *   node scripts/seed-sample-inventory.mjs --seller-id <UUID> --token <JWT> --gateway http://localhost:8080/api/v1
 *   node scripts/seed-sample-inventory.mjs --sku-codes NX-KB-01,NX-MS-01 --token <JWT>
 */

import { pathToFileURL } from 'node:url'

/** Danh sách 4 kho hàng chuẩn khớp với migration V4 và V5 trong fulfillment-service. */
export const SAMPLE_WAREHOUSES = [
    {
        code: 'WH_001',
        name: 'Kho mặc định TP.HCM (Saigon Central Hub)',
        address: 'Khu Công nghệ cao, TP. Thủ Đức, TP. Hồ Chí Minh',
        type: 'STANDARD',
        active: true,
    },
    {
        id: 'a0000000-0000-0000-0000-000000000002',
        code: 'WH_002',
        name: 'Kho Miền Bắc Nexus (Hanoi Hub)',
        address: 'Khu Công nghiệp Bắc Thăng Long, Đông Anh, Hà Nội',
        type: 'STANDARD',
        active: true,
    },
    {
        id: 'a0000000-0000-0000-0000-000000000003',
        code: 'WH_003',
        name: 'Kho Miền Trung Nexus (Da Nang Hub)',
        address: 'Khu Công nghiệp Hòa Khánh, Liên Chiểu, TP. Đà Nẵng',
        type: 'STANDARD',
        active: true,
    },
    {
        id: 'a0000000-0000-0000-0000-000000000004',
        code: 'WH_RETURN',
        name: 'Kho Hàng Hoàn Trả Nexus (Return Center)',
        address: 'Khu Công nghệ cao, TP. Thủ Đức, TP. Hồ Chí Minh',
        type: 'RETURN',
        active: true,
    },
]

/** Phân bổ số lượng nhập mẫu theo từng kho cho mỗi SKU. */
const DEFAULT_DISTRIBUTION = [
    { warehouseCode: 'WH_001', quantity: 25, reason: 'Tồn đầu kỳ kho trung tâm TP.HCM' },
    { warehouseCode: 'WH_002', quantity: 15, reason: 'Điều phối tồn đầu kỳ kho Miền Bắc' },
    { warehouseCode: 'WH_003', quantity: 10, reason: 'Điều phối tồn đầu kỳ kho Miền Trung' },
]

/**
 * Tạo danh sách request nhập kho mẫu kèm Idempotency-Key cố định cho tập SKU đầu vào.
 * Quy tắc: giữ idempotencyKey cố định theo (batchTag, warehouseCode, identifier) để chạy lại an toàn.
 */
export function buildSampleInboundPlans(skuEntries, warehousesByCode = {}, batchTag = 'sample-v1') {
    const plans = []
    for (const entry of skuEntries) {
        const keyPart = entry.skuId || entry.skuCode
        if (!keyPart) {
            continue
        }
        for (const dist of DEFAULT_DISTRIBUTION) {
            const warehouse = warehousesByCode[dist.warehouseCode]
            const idempotencyKey = `seed-${batchTag}-${dist.warehouseCode}-${keyPart}`
            plans.push({
                idempotencyKey,
                warehouseCode: dist.warehouseCode,
                payload: {
                    skuId: entry.skuId ?? null,
                    skuCode: entry.skuCode ?? null,
                    warehouseId: warehouse?.id ?? null,
                    quantity: dist.quantity,
                    reason: dist.reason,
                    idempotencyKey,
                },
            })
        }
    }
    return plans
}

function parseArgs(argv) {
    const options = {
        gateway: process.env.NEXUS_GATEWAY_URL || 'http://localhost:8080/api/v1',
        token: process.env.NEXUS_ACCESS_TOKEN || '',
        sellerId: process.env.NEXUS_SELLER_ID || '',
        skuCodes: [],
        dryRun: false,
    }
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i]
        if (arg === '--dry-run') {
            options.dryRun = true
        } else if (arg === '--gateway' && argv[i + 1]) {
            options.gateway = argv[++i].replace(/\/$/, '')
        } else if (arg === '--token' && argv[i + 1]) {
            options.token = argv[++i]
        } else if (arg === '--seller-id' && argv[i + 1]) {
            options.sellerId = argv[++i]
        } else if (arg === '--sku-codes' && argv[i + 1]) {
            options.skuCodes = argv[++i]
                .split(',')
                .map((s) => s.trim())
                .filter(Boolean)
        }
    }
    if (!options.token) {
        options.dryRun = true
    }
    return options
}

async function apiRequest(url, options = {}) {
    const response = await fetch(url, {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            'X-Nexus-Request': '1',
            ...(options.headers || {}),
        },
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || payload?.success === false) {
        const code = payload?.error?.code || `HTTP_${response.status}`
        const msg = payload?.error?.message || payload?.message || response.statusText
        throw new Error(`${code}: ${msg}`)
    }
    return payload
}

async function run() {
    const opts = parseArgs(process.argv.slice(2))

    if (opts.dryRun) {
        const sampleSkus =
            opts.skuCodes.length > 0
                ? opts.skuCodes.map((skuCode) => ({ skuCode }))
                : [{ skuCode: 'SAMPLE-SKU-001' }, { skuCode: 'SAMPLE-SKU-002' }]
        const plans = buildSampleInboundPlans(
            sampleSkus,
            Object.fromEntries(SAMPLE_WAREHOUSES.filter((w) => w.id).map((w) => [w.code, w])),
        )
        console.log(
            JSON.stringify(
                {
                    mode: 'dry-run',
                    note: 'Truyền --token <JWT> hoặc NEXUS_ACCESS_TOKEN để thực thi trực tiếp qua API Gateway.',
                    warehouses: SAMPLE_WAREHOUSES,
                    sampleInboundPlans: plans,
                },
                null,
                2,
            ),
        )
        return
    }

    const authHeaders = { Authorization: `Bearer ${opts.token}` }

    // 1. Nạp danh mục kho hàng hiện có (tự tạo WH_002/003/RETURN nếu Admin chạy trên môi trường chưa chạy V5)
    const whResponse = await apiRequest(`${opts.gateway}/warehouses?size=50`, {
        headers: authHeaders,
    })
    const warehousesByCode = {}
    for (const wh of whResponse.data || []) {
        warehousesByCode[wh.code] = wh
    }
    for (const sampleWh of SAMPLE_WAREHOUSES) {
        if (warehousesByCode[sampleWh.code]) {
            continue
        }
        try {
            const created = await apiRequest(`${opts.gateway}/warehouses`, {
                method: 'POST',
                headers: authHeaders,
                body: JSON.stringify({
                    code: sampleWh.code,
                    name: sampleWh.name,
                    address: sampleWh.address,
                    type: sampleWh.type,
                    active: sampleWh.active,
                }),
            })
            warehousesByCode[sampleWh.code] = created.data
        } catch (err) {
            console.warn(`Bỏ qua tạo kho ${sampleWh.code}: ${err.message}`)
        }
    }

    // 2. Xác định danh sách SKU cần seed từ --sku-codes hoặc từ Catalog của Seller
    let skuEntries = opts.skuCodes.map((skuCode) => ({ skuCode }))
    if (skuEntries.length === 0 && opts.sellerId) {
        const catalogRes = await apiRequest(
            `${opts.gateway}/products/seller/${encodeURIComponent(opts.sellerId)}?page=0&size=50`,
            { headers: authHeaders },
        )
        skuEntries = []
        for (const product of catalogRes.data || []) {
            for (const sku of product.skus || []) {
                skuEntries.push({ skuId: sku.id, skuCode: sku.skuCode })
            }
        }
    }

    if (skuEntries.length === 0) {
        console.log('Không có SKU đầu vào. Hãy truyền --seller-id <UUID> hoặc --sku-codes CODE1,CODE2.')
        return
    }

    // 3. Nhập kho idempotent theo kế hoạch phân bổ đa kho
    const plans = buildSampleInboundPlans(skuEntries, warehousesByCode)
    for (const plan of plans) {
        const res = await apiRequest(`${opts.gateway}/inventory/inbound`, {
            method: 'POST',
            headers: {
                ...authHeaders,
                'Idempotency-Key': plan.idempotencyKey,
            },
            body: JSON.stringify(plan.payload),
        })
        console.log(
            `[INBOUND] warehouse=${res.data.warehouseCode} sku=${plan.payload.skuCode || plan.payload.skuId} total=${res.data.totalQty} available=${res.data.availableQty}`,
        )
    }

    // 4. Đối soát khớp sổ cái toàn bộ tồn kho vừa seed
    const recRes = await apiRequest(`${opts.gateway}/inventory/reconcile`, {
        headers: authHeaders,
    })
    const items = recRes.data || []
    const unmatched = items.filter((item) => !item.matched || !item.nonNegative)
    console.log(
        `[RECONCILE] checked=${items.length}, matched=${items.length - unmatched.length}, unmatched=${unmatched.length}`,
    )
    if (unmatched.length > 0) {
        console.error(JSON.stringify(unmatched, null, 2))
        process.exitCode = 1
    }
}

const isEntrypoint =
    process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href

if (isEntrypoint) {
    run().catch((error) => {
        console.error(`[seed-sample-inventory] Lỗi: ${error.message}`)
        process.exitCode = 1
    })
}
