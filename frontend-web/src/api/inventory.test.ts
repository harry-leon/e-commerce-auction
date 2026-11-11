/**
 * Unit test cho client Inventory & Warehouse API (Fulfillment Service).
 * Xác minh URL, query string, HTTP method, header Idempotency-Key và bóc tách dữ liệu phân trang/lỗi.
 */

import { afterEach, describe, expect, it, vi } from 'vitest'
import {
    adjustStock,
    createInventoryIdempotencyKey,
    getBatchStock,
    getInventoryLedger,
    getStockBySkuCode,
    inboundStock,
    listInventory,
    listWarehouses,
    reconcileInventory,
    updateWarehouse,
} from './inventory'

describe('Inventory & Warehouse API Client', () => {
    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('listWarehouses & updateWarehouse gọi đúng endpoint và giữ nguyên phân trang', async () => {
        const fetcher = vi
            .fn()
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        success: true,
                        data: [
                            {
                                id: 'wh-1',
                                code: 'WH_001',
                                name: 'Kho TP.HCM',
                                address: 'HCM',
                                type: 'STANDARD',
                                active: true,
                            },
                        ],
                        meta: {
                            requestId: 'req_1',
                            timestamp: '2026-11-11T00:00:00Z',
                            pagination: {
                                page: 0,
                                size: 50,
                                totalElements: 1,
                                totalPages: 1,
                                hasNext: false,
                                hasPrevious: false,
                            },
                        },
                    }),
                    { status: 200 },
                ),
            )
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        success: true,
                        data: {
                            id: 'wh-1',
                            code: 'WH_001',
                            name: 'Kho TP.HCM Moi',
                            address: 'Thu Duc, HCM',
                            type: 'STANDARD',
                            active: true,
                        },
                    }),
                    { status: 200 },
                ),
            )

        vi.stubGlobal('fetch', fetcher)

        const list = await listWarehouses({ activeOnly: true, type: 'STANDARD', q: 'HCM' })
        expect(list.items).toHaveLength(1)
        expect(list.pagination.totalElements).toBe(1)
        expect(fetcher.mock.calls[0][0]).toContain(
            '/warehouses?activeOnly=true&type=STANDARD&q=HCM',
        )

        const updated = await updateWarehouse('wh-1', {
            name: 'Kho TP.HCM Moi',
            address: 'Thu Duc, HCM',
        })
        expect(updated.name).toBe('Kho TP.HCM Moi')
        expect((fetcher.mock.calls[1][1] as RequestInit).method).toBe('PUT')
    })

    it('listInventory, getStockBySkuCode & getBatchStock đọc đúng dữ liệu 4 trạng thái', async () => {
        const stock = {
            skuId: 'sku-1',
            warehouseId: 'wh-1',
            warehouseCode: 'WH_001',
            warehouseName: 'Kho TP.HCM',
            skuCode: 'NX-KB-01',
            totalQty: 30,
            availableQty: 25,
            reservedQty: 5,
            unavailableQty: 0,
        }
        const fetcher = vi
            .fn()
            .mockResolvedValueOnce(
                new Response(JSON.stringify({ success: true, data: [stock] }), { status: 200 }),
            )
            .mockResolvedValueOnce(
                new Response(JSON.stringify({ success: true, data: stock }), { status: 200 }),
            )
            .mockResolvedValueOnce(
                new Response(JSON.stringify({ success: true, data: [stock] }), { status: 200 }),
            )

        vi.stubGlobal('fetch', fetcher)

        const paged = await listInventory({ skuCode: 'NX-KB-01', warehouseId: 'wh-1' })
        expect(paged.items[0].availableQty).toBe(25)
        expect(fetcher.mock.calls[0][0]).toContain('/inventory?skuCode=NX-KB-01&warehouseId=wh-1')

        const byCode = await getStockBySkuCode('NX-KB-01', 'wh-1')
        expect(byCode.skuCode).toBe('NX-KB-01')
        expect(fetcher.mock.calls[1][0]).toContain('/inventory/sku/code/NX-KB-01?warehouseId=wh-1')

        const batch = await getBatchStock({ skuIds: ['sku-1'], warehouseId: 'wh-1' })
        expect(batch).toHaveLength(1)
        expect((fetcher.mock.calls[2][1] as RequestInit).method).toBe('POST')
    })

    it('inboundStock & adjustStock gửi kèm Idempotency-Key header và xử lý lỗi API', async () => {
        const stock = {
            skuId: 'sku-1',
            warehouseId: 'wh-1',
            warehouseCode: 'WH_001',
            warehouseName: 'Kho TP.HCM',
            skuCode: 'NX-KB-01',
            totalQty: 50,
            availableQty: 50,
            reservedQty: 0,
            unavailableQty: 0,
        }
        const fetcher = vi
            .fn()
            .mockResolvedValueOnce(
                new Response(JSON.stringify({ success: true, data: stock }), { status: 200 }),
            )
            .mockResolvedValueOnce(
                new Response(JSON.stringify({ success: true, data: stock }), { status: 200 }),
            )

        vi.stubGlobal('fetch', fetcher)

        const key = createInventoryIdempotencyKey('inb')
        expect(key).toMatch(/^inb-/)

        await inboundStock(
            { skuCode: 'NX-KB-01', warehouseId: 'wh-1', quantity: 20, reason: 'Nhap lo 1' },
            'fixed-inb-key',
        )
        const inbHeaders = (fetcher.mock.calls[0][1] as RequestInit).headers as Headers
        expect(inbHeaders.get('Idempotency-Key')).toBe('fixed-inb-key')

        await adjustStock({
            skuId: 'sku-1',
            targetTotalQty: 50,
            reason: 'Kiem ke',
            idempotencyKey: 'fixed-adj-key',
        })
        const adjHeaders = (fetcher.mock.calls[1][1] as RequestInit).headers as Headers
        expect(adjHeaders.get('Idempotency-Key')).toBe('fixed-adj-key')
    })

    it('getInventoryLedger & reconcileInventory truyền đủ filter và đọc kết quả đối soát', async () => {
        const fetcher = vi
            .fn()
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        success: true,
                        data: [
                            {
                                id: 'led-1',
                                skuId: 'sku-1',
                                warehouseId: 'wh-1',
                                warehouseCode: 'WH_001',
                                movementType: 'INBOUND',
                                quantity: 20,
                                refType: 'INBOUND',
                                refId: null,
                                reason: 'Nhap lo 1',
                                actorId: 'seller-1',
                                createdAt: '2026-11-11T00:00:00Z',
                            },
                        ],
                    }),
                    { status: 200 },
                ),
            )
            .mockResolvedValueOnce(
                new Response(
                    JSON.stringify({
                        success: true,
                        data: [
                            {
                                skuId: 'sku-1',
                                warehouseId: 'wh-1',
                                warehouseCode: 'WH_001',
                                totalQty: 20,
                                availableQty: 20,
                                reservedQty: 0,
                                unavailableQty: 0,
                                ledgerTotalQty: 20,
                                ledgerReservedQty: 0,
                                activeReservationQty: 0,
                                nonNegative: true,
                                matched: true,
                            },
                        ],
                    }),
                    { status: 200 },
                ),
            )

        vi.stubGlobal('fetch', fetcher)

        const ledger = await getInventoryLedger({
            warehouseId: 'wh-1',
            movementType: 'INBOUND',
            page: 0,
            size: 10,
        })
        expect(ledger.items[0].movementType).toBe('INBOUND')
        expect(fetcher.mock.calls[0][0]).toContain(
            '/inventory/ledger?warehouseId=wh-1&movementType=INBOUND&page=0&size=10',
        )

        const rec = await reconcileInventory({ warehouseId: 'wh-1' })
        expect(rec[0].matched).toBe(true)
        expect(fetcher.mock.calls[1][0]).toContain('/inventory/reconcile?warehouseId=wh-1')
    })
})
