/**
 * Kiểm thử màn hình SellerInventoryPage và AdminWarehousesPage sau khi nối API thật:
 * xác minh tải danh sách kho, tồn kho 4 trạng thái, nhập kho/điều chỉnh kèm Idempotency-Key,
 * tạo kho hàng Admin và đối soát sổ cái bất biến.
 */

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState } from '../types'
import { DEMO_ADMIN, DEMO_SELLER } from '../mockData'
import { SellerInventoryPage } from '../features/seller/SellerPages'
import { AdminWarehousesPage } from '../features/admin/AdminPages'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))

function makeJsonResponse(data: unknown, status = 200) {
    return new Response(
        JSON.stringify({
            success: status < 400,
            data,
            error: null,
            meta: {
                requestId: 'req_test',
                timestamp: '2026-11-11T00:00:00Z',
                pagination: {
                    page: 0,
                    size: 50,
                    totalElements: Array.isArray(data) ? data.length : 1,
                    totalPages: 1,
                    hasNext: false,
                    hasPrevious: false,
                },
            },
        }),
        { status },
    )
}

describe('SellerInventoryPage & AdminWarehousesPage API integration', () => {
    beforeEach(() => {
        holder.current = {
            lang: 'en',
            setLang: vi.fn(),
            page: 'seller-inventory',
            navigate: vi.fn(),
            pageParams: {},
            user: DEMO_SELLER,
            setUser: vi.fn(),
            cartCount: 0,
            setCartCount: vi.fn(),
            toasts: [],
            showToast: vi.fn(),
            dismissToast: vi.fn(),
            auctionReviews: {},
            reviewAuction: vi.fn(),
            createdAuctions: [],
            submitAuction: vi.fn(),
        }
    })

    afterEach(() => {
        cleanup()
        vi.restoreAllMocks()
        vi.unstubAllGlobals()
    })

    it('SellerInventoryPage tải tồn kho thật, nhập kho idempotent và đối soát sổ cái', async () => {
        const warehouses = [
            {
                id: 'wh-1',
                code: 'WH_001',
                name: 'Saigon Hub',
                address: 'Thu Duc, HCM',
                type: 'STANDARD',
                active: true,
            },
        ]
        const stocks = [
            {
                skuId: 'sku-1',
                warehouseId: 'wh-1',
                warehouseCode: 'WH_001',
                warehouseName: 'Saigon Hub',
                skuCode: 'NX-KB-01',
                totalQty: 25,
                availableQty: 20,
                reservedQty: 5,
                unavailableQty: 0,
            },
        ]
        const ledger = [
            {
                id: 'led-1',
                skuId: 'sku-1',
                warehouseId: 'wh-1',
                warehouseCode: 'WH_001',
                movementType: 'INBOUND',
                quantity: 25,
                refType: 'INBOUND',
                refId: null,
                reason: 'Initial batch',
                actorId: DEMO_SELLER.id,
                createdAt: '2026-11-11T08:00:00Z',
            },
        ]

        const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
            const url = String(input)
            if (url.includes('/warehouses')) return makeJsonResponse(warehouses)
            if (url.includes('/inventory/reconcile')) {
                return makeJsonResponse([
                    {
                        skuId: 'sku-1',
                        warehouseId: 'wh-1',
                        warehouseCode: 'WH_001',
                        totalQty: 35,
                        availableQty: 30,
                        reservedQty: 5,
                        unavailableQty: 0,
                        ledgerTotalQty: 35,
                        ledgerReservedQty: 5,
                        activeReservationQty: 5,
                        nonNegative: true,
                        matched: true,
                    },
                ])
            }
            if (url.includes('/inventory/inbound') && init?.method === 'POST') {
                stocks[0] = { ...stocks[0], totalQty: 35, availableQty: 30 }
                return makeJsonResponse(stocks[0])
            }
            if (url.includes('/inventory/ledger')) return makeJsonResponse(ledger)
            if (url.includes('/inventory')) return makeJsonResponse(stocks)
            if (url.includes('/products')) {
                return makeJsonResponse([
                    {
                        id: 'prod-1',
                        name: 'Nexus Mechanical Keyboard',
                        skus: [
                            { id: 'sku-1', skuCode: 'NX-KB-01', price: 1200000, status: 'ACTIVE' },
                        ],
                    },
                ])
            }
            return makeJsonResponse([])
        })
        vi.stubGlobal('fetch', fetcher)

        render(<SellerInventoryPage />)

        await waitFor(() => {
            expect(screen.getAllByText('NX-KB-01').length).toBeGreaterThan(0)
        })
        expect(screen.getByText('Nexus Mechanical Keyboard')).toBeInTheDocument()
        expect(screen.getByText('Initial batch')).toBeInTheDocument()

        // Mở modal Nhập kho và gửi request kèm Idempotency-Key
        fireEvent.click(screen.getByRole('button', { name: /Inbound Stock/i }))
        fireEvent.click(screen.getByRole('button', { name: /Confirm Inbound/i }))

        await waitFor(() => {
            const postCall = fetcher.mock.calls.find(([u]) =>
                String(u).includes('/inventory/inbound'),
            )
            expect(postCall).toBeTruthy()
            const headers = (postCall?.[1] as RequestInit)?.headers as Headers
            expect(headers.get('Idempotency-Key')).toMatch(/^seller-inb-/)
        })

        // Chạy đối soát sổ cái
        fireEvent.click(screen.getByRole('button', { name: /Reconcile Ledger/i }))
        await waitFor(() => {
            expect(screen.getByText('Matched')).toBeInTheDocument()
        })
    })

    it('AdminWarehousesPage hiển thị danh mục kho và gọi POST /warehouses khi tạo kho mới', async () => {
        holder.current.user = DEMO_ADMIN
        holder.current.page = 'admin-warehouses'

        const warehouses = [
            {
                id: 'wh-1',
                code: 'WH_001',
                name: 'Saigon Hub',
                address: 'Thu Duc, HCM',
                type: 'STANDARD',
                active: true,
            },
        ]

        const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
            const url = String(input)
            if (url.endsWith('/warehouses') && init?.method === 'POST') {
                const body = JSON.parse(String(init.body))
                const created = { id: 'wh-4', ...body }
                warehouses.push(created)
                return makeJsonResponse(created, 201)
            }
            if (url.includes('/warehouses')) return makeJsonResponse(warehouses)
            if (url.includes('/inventory/ledger')) return makeJsonResponse([])
            if (url.includes('/inventory')) return makeJsonResponse([])
            return makeJsonResponse([])
        })
        vi.stubGlobal('fetch', fetcher)

        render(<AdminWarehousesPage />)

        await waitFor(() => {
            expect(screen.getByText('WH_001')).toBeInTheDocument()
            expect(screen.getByText('Saigon Hub')).toBeInTheDocument()
        })

        fireEvent.click(screen.getByRole('button', { name: /Add Warehouse/i }))
        fireEvent.change(screen.getByPlaceholderText('VD: WH_004'), {
            target: { value: 'wh_004' },
        })
        fireEvent.change(screen.getByPlaceholderText('VD: Kho Cần Thơ'), {
            target: { value: 'Can Tho Hub' },
        })
        const textareas = document.querySelectorAll('textarea')
        fireEvent.change(textareas[0], { target: { value: 'Ninh Kieu, Can Tho' } })
        fireEvent.click(screen.getByRole('button', { name: /Save/i }))

        await waitFor(() => {
            const createCall = fetcher.mock.calls.find(
                ([u, init]) => String(u).endsWith('/warehouses') && init?.method === 'POST',
            )
            expect(createCall).toBeTruthy()
            expect(JSON.parse(String((createCall?.[1] as RequestInit).body)).code).toBe('WH_004')
        })
    })
})
