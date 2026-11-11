import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState } from '../types'
import { DEMO_ADMIN } from '../mockData'
import { formatSkuDisplay, saveSession } from '../api/client'
import {
    adjustStock,
    getInventoryLedger,
    getSellerSkuDetail,
    inboundStock,
    listSellerSkus,
} from '../api/inventory'
import { CreateProductPage, SellerInventoryPage } from '../features/seller/SellerPages'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))

const SELLER_UUID = '11111111-1111-4111-8111-111111111111'
const SKU_UUID = '22222222-2222-4222-8222-222222222222'
const PRODUCT_UUID = '33333333-3333-4333-8333-333333333333'
const WAREHOUSE_UUID = '44444444-4444-4444-8444-444444444444'

const baseSellerUser = {
    ...DEMO_ADMIN,
    id: SELLER_UUID,
    role: 'seller' as const,
    name: 'Seller One',
    email: 'seller@nexus.local',
}

/**
 * Bộ kiểm thử hồi quy cho HG-S2-6 (tách Catalog SKU ID khỏi Fulfillment SKU Code FSKU-...):
 * - Đảm bảo Catalog UI không gửi skuCode/price mà chỉ gửi originalPrice và giữ nguyên sku.id khi cập nhật.
 * - Đảm bảo SellerInventoryPage và api/inventory.ts gọi đúng endpoint Fulfillment, hiển thị FSKU-... ngay khi nhập kho,
 *   và không gọi API sổ cái /adjust dành cho Admin khi đăng nhập bằng tài khoản Seller.
 */
beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    holder.current = {
        lang: 'en',
        setLang: vi.fn(),
        page: 'seller-inventory',
        navigate: vi.fn(),
        pageParams: {},
        user: baseSellerUser,
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
        setCreatedAuctions: vi.fn(),
        createdProducts: [],
        addCreatedProduct: vi.fn(),
        selectedOrderId: '',
        setSelectedOrderId: vi.fn(),
    } as unknown as AppState
})

describe('HG-S2-6 formatSkuDisplay & inventory API wrappers', () => {
    it('ưu tiên mã skuCode khi có, fallback SKU-<8 ký tự UUID> khi Catalog không trả skuCode', () => {
        expect(formatSkuDisplay(SKU_UUID, 'FSKU-00000046')).toBe('FSKU-00000046')
        expect(formatSkuDisplay(SKU_UUID, null)).toBe('SKU-22222222')
        expect(formatSkuDisplay('abcdef12-3456-7890-abcd-ef1234567890', '   ')).toBe('SKU-ABCDEF12')
        expect(formatSkuDisplay(undefined, undefined)).toBe('')
    })

    it('gọi đúng endpoint Fulfillment khi listSellerSkus, getSellerSkuDetail, inboundStock, adjustStock, getInventoryLedger', async () => {
        const fetchMock = vi.fn(async (input: string, init: RequestInit = {}) => {
            const url = String(input)
            if (url.includes('/inventory/skus?') && (!init.method || init.method === 'GET')) {
                return new Response(
                    JSON.stringify({
                        success: true,
                        data: [
                            {
                                id: 'ssku-1',
                                sellerSkuId: 'ssku-1',
                                sellerId: SELLER_UUID,
                                skuId: SKU_UUID,
                                skuCode: 'FSKU-00000046',
                                status: 'ACTIVE',
                                totalQty: 15,
                                availableQty: 15,
                                reservedQty: 0,
                                unavailableQty: 0,
                                warehouseCount: 1,
                                createdAt: '2026-11-11T10:00:00Z',
                                updatedAt: '2026-11-11T10:00:00Z',
                            },
                        ],
                        meta: { pagination: { totalElements: 1 } },
                    }),
                )
            }
            if (url.endsWith('/inventory/skus/ssku-1')) {
                return new Response(
                    JSON.stringify({
                        success: true,
                        data: {
                            id: 'ssku-1',
                            sellerSkuId: 'ssku-1',
                            sellerId: SELLER_UUID,
                            skuId: SKU_UUID,
                            skuCode: 'FSKU-00000046',
                            status: 'ACTIVE',
                            totalQty: 15,
                            availableQty: 15,
                            reservedQty: 0,
                            unavailableQty: 0,
                            warehouseCount: 1,
                            createdAt: '2026-11-11T10:00:00Z',
                            updatedAt: '2026-11-11T10:00:00Z',
                            warehouses: [
                                {
                                    skuId: SKU_UUID,
                                    sellerSkuId: 'ssku-1',
                                    skuCode: 'FSKU-00000046',
                                    warehouseId: WAREHOUSE_UUID,
                                    warehouseCode: 'WH_001',
                                    totalQty: 15,
                                    availableQty: 15,
                                    reservedQty: 0,
                                    unavailableQty: 0,
                                },
                            ],
                        },
                    }),
                )
            }
            if (url.endsWith('/inventory/inbound') && init.method === 'POST') {
                return new Response(
                    JSON.stringify({
                        success: true,
                        data: {
                            skuId: SKU_UUID,
                            sellerSkuId: 'ssku-1',
                            skuCode: 'FSKU-00000046',
                            warehouseId: WAREHOUSE_UUID,
                            warehouseCode: 'WH_001',
                            totalQty: 25,
                            availableQty: 25,
                            reservedQty: 0,
                            unavailableQty: 0,
                        },
                    }),
                )
            }
            if (url.endsWith('/inventory/adjust') && init.method === 'POST') {
                return new Response(
                    JSON.stringify({
                        success: true,
                        data: {
                            skuId: SKU_UUID,
                            sellerSkuId: 'ssku-1',
                            skuCode: 'FSKU-00000046',
                            warehouseId: WAREHOUSE_UUID,
                            warehouseCode: 'WH_001',
                            totalQty: 20,
                            availableQty: 20,
                            reservedQty: 0,
                            unavailableQty: 0,
                        },
                    }),
                )
            }
            if (url.includes('/inventory/ledger?')) {
                return new Response(
                    JSON.stringify({
                        success: true,
                        data: [
                            {
                                id: 'led-1',
                                skuId: SKU_UUID,
                                warehouseId: WAREHOUSE_UUID,
                                movementType: 'INBOUND',
                                quantity: 25,
                                refType: 'PURCHASE_ORDER',
                                refId: null,
                                createdAt: '2026-11-11T10:00:00Z',
                            },
                        ],
                    }),
                )
            }
            return new Response(JSON.stringify({ success: true, data: [] }))
        })
        vi.stubGlobal('fetch', fetchMock)

        const list = await listSellerSkus({ keyword: 'FSKU-00000046', status: 'ACTIVE' })
        expect(list.total).toBe(1)
        expect(list.items[0].skuCode).toBe('FSKU-00000046')

        const detail = await getSellerSkuDetail('ssku-1')
        expect(detail.warehouses[0].warehouseCode).toBe('WH_001')

        const inbound = await inboundStock({ skuId: SKU_UUID, quantity: 25, unitCost: 120000 })
        expect(inbound.skuCode).toBe('FSKU-00000046')

        const adjusted = await adjustStock({
            skuId: SKU_UUID,
            deltaQty: -5,
            reason: 'Cycle count',
        })
        expect(adjusted.totalQty).toBe(20)

        const ledger = await getInventoryLedger({ skuId: SKU_UUID })
        expect(ledger).toHaveLength(1)
        expect(ledger[0].movementType).toBe('INBOUND')
    })
})

describe('HG-S2-6 CreateProductPage Catalog SKU decoupling', () => {
    it('gửi POST /products chỉ có originalPrice và tuyệt đối không gửi skuCode hoặc price', async () => {
        saveSession({ user: baseSellerUser })
        let submittedBody: Record<string, unknown> | null = null

        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string, init: RequestInit = {}) => {
                const url = String(input)
                if (url.includes('/categories')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: [
                                {
                                    id: 'cat-uuid-1',
                                    name: 'Electronics',
                                    nameVi: 'Điện tử',
                                    level: 1,
                                    parentId: null,
                                    status: 'ACTIVE',
                                    productCount: 0,
                                    children: [],
                                },
                            ],
                        }),
                    )
                }
                if (url.includes('/pricing-rules/preview')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                originalPrice: 200000,
                                sellingPrice: 240000,
                                markupPercent: 20,
                                fixedFee: 0,
                                seasonalMultiplier: 1,
                            },
                        }),
                    )
                }
                if (url.endsWith('/products') && init.method === 'POST') {
                    submittedBody = JSON.parse(String(init.body))
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                id: PRODUCT_UUID,
                                name: 'Mechanical Keyboard',
                                description: 'Hot-swap tactile keyboard',
                                categoryId: 'cat-uuid-1',
                                sellerId: SELLER_UUID,
                                status: 'ACTIVE',
                                auctionEnabled: false,
                                createdAt: '2026-11-11T10:00:00Z',
                                skus: [
                                    {
                                        id: SKU_UUID,
                                        attributes: 'Black / Tactile',
                                        originalPrice: 200000,
                                        sellingPrice: 240000,
                                        status: 'ACTIVE',
                                    },
                                ],
                                images: [],
                            },
                        }),
                        { status: 201 },
                    )
                }
                return new Response(JSON.stringify({ success: true, data: [] }))
            }),
        )

        render(<CreateProductPage />)
        await screen.findByText(/SKU Variants & Minimum Price/i)

        // Không còn trường nhập skuCode ở Catalog
        expect(screen.queryByLabelText(/SKU Code/i)).toBeNull()

        fireEvent.change(screen.getByLabelText(/Product Name/i), {
            target: { value: 'Mechanical Keyboard' },
        })
        fireEvent.change(screen.getByLabelText(/Attributes \(Color \/ Size\)/i), {
            target: { value: 'Black / Tactile' },
        })
        fireEvent.change(screen.getByLabelText(/Min Price \/ Original Price/i), {
            target: { value: '200000' },
        })

        fireEvent.click(screen.getByRole('button', { name: /^Save$|^Lưu$/i }))

        await waitFor(() => expect(submittedBody).not.toBeNull())
        const skus = (submittedBody as unknown as { skus: Array<Record<string, unknown>> }).skus
        expect(skus).toHaveLength(1)
        expect(skus[0]).toEqual({
            attributes: 'Black / Tactile',
            originalPrice: 200000,
            status: 'ACTIVE',
        })
        expect('skuCode' in skus[0]).toBe(false)
        expect('price' in skus[0]).toBe(false)
    })

    it('khi chỉnh sửa sản phẩm, PUT /products/{id} giữ nguyên sku.id để không mất liên kết kho', async () => {
        saveSession({ user: baseSellerUser })
        holder.current.pageParams = { id: PRODUCT_UUID }
        let updatedBody: Record<string, unknown> | null = null

        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string, init: RequestInit = {}) => {
                const url = String(input)
                if (url.includes('/categories')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: [
                                {
                                    id: 'cat-uuid-1',
                                    name: 'Electronics',
                                    nameVi: 'Điện tử',
                                    level: 1,
                                    parentId: null,
                                    status: 'ACTIVE',
                                    productCount: 1,
                                    children: [],
                                },
                            ],
                        }),
                    )
                }
                if (
                    url.endsWith(`/products/${PRODUCT_UUID}`) &&
                    (!init.method || init.method === 'GET')
                ) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                id: PRODUCT_UUID,
                                name: 'Mechanical Keyboard',
                                description: 'Original desc',
                                categoryId: 'cat-uuid-1',
                                sellerId: SELLER_UUID,
                                status: 'ACTIVE',
                                auctionEnabled: false,
                                createdAt: '2026-11-11T10:00:00Z',
                                skus: [
                                    {
                                        id: SKU_UUID,
                                        attributes: 'Black / Tactile',
                                        originalPrice: 200000,
                                        status: 'ACTIVE',
                                    },
                                ],
                                images: [],
                            },
                        }),
                    )
                }
                if (url.endsWith(`/products/${PRODUCT_UUID}`) && init.method === 'PUT') {
                    updatedBody = JSON.parse(String(init.body))
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                id: PRODUCT_UUID,
                                name: 'Mechanical Keyboard V2',
                                description: 'Original desc',
                                categoryId: 'cat-uuid-1',
                                sellerId: SELLER_UUID,
                                status: 'ACTIVE',
                                auctionEnabled: false,
                                createdAt: '2026-11-11T10:00:00Z',
                                skus: [
                                    {
                                        id: SKU_UUID,
                                        attributes: 'Black / Linear',
                                        originalPrice: 220000,
                                        status: 'ACTIVE',
                                    },
                                ],
                                images: [],
                            },
                        }),
                    )
                }
                return new Response(JSON.stringify({ success: true, data: {} }))
            }),
        )

        render(<CreateProductPage />)
        await screen.findByDisplayValue('Mechanical Keyboard')
        expect(screen.getByText(/Catalog ID: 22222222/i)).toBeVisible()

        fireEvent.change(screen.getByLabelText(/Attributes \(Color \/ Size\)/i), {
            target: { value: 'Black / Linear' },
        })
        fireEvent.click(screen.getByRole('button', { name: /^Save$|^Lưu$/i }))

        await waitFor(() => expect(updatedBody).not.toBeNull())
        const skus = (updatedBody as unknown as { skus: Array<Record<string, unknown>> }).skus
        expect(skus[0].id).toBe(SKU_UUID)
        expect('skuCode' in skus[0]).toBe(false)
    })
})

describe('HG-S2-6 SellerInventoryPage Fulfillment SKU workflow', () => {
    it('nạp dữ liệu seller_skus thật, cho phép nhập kho và hiển thị ngay mã FSKU-... được cấp', async () => {
        let inboundDone = false
        const fetchCalls: string[] = []

        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string, init: RequestInit = {}) => {
                const url = String(input)
                fetchCalls.push(`${init.method || 'GET'} ${url}`)

                if (url.includes(`/products/seller/${SELLER_UUID}`)) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: [
                                {
                                    id: PRODUCT_UUID,
                                    name: 'Studio Headphones',
                                    description: 'Hi-Res',
                                    categoryId: 'cat-1',
                                    sellerId: SELLER_UUID,
                                    status: 'ACTIVE',
                                    auctionEnabled: false,
                                    createdAt: '',
                                    skus: [
                                        {
                                            id: SKU_UUID,
                                            attributes: 'Matte Black',
                                            originalPrice: 1500000,
                                            status: 'ACTIVE',
                                        },
                                    ],
                                    images: [],
                                },
                            ],
                        }),
                    )
                }

                if (url.includes('/inventory/skus?') && (!init.method || init.method === 'GET')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: inboundDone
                                ? [
                                      {
                                          id: 'ssku-46',
                                          sellerSkuId: 'ssku-46',
                                          sellerId: SELLER_UUID,
                                          skuId: SKU_UUID,
                                          skuCode: 'FSKU-00000046',
                                          status: 'ACTIVE',
                                          totalQty: 12,
                                          availableQty: 12,
                                          reservedQty: 0,
                                          unavailableQty: 0,
                                          warehouseCount: 1,
                                          createdAt: '2026-11-11T10:00:00Z',
                                          updatedAt: '2026-11-11T10:00:00Z',
                                      },
                                  ]
                                : [],
                            meta: { pagination: { totalElements: inboundDone ? 1 : 0 } },
                        }),
                    )
                }

                if (url.endsWith('/inventory/inbound') && init.method === 'POST') {
                    inboundDone = true
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                skuId: SKU_UUID,
                                sellerSkuId: 'ssku-46',
                                skuCode: 'FSKU-00000046',
                                warehouseId: WAREHOUSE_UUID,
                                warehouseCode: 'WH_001',
                                totalQty: 12,
                                availableQty: 12,
                                reservedQty: 0,
                                unavailableQty: 0,
                            },
                        }),
                    )
                }

                if (url.endsWith('/inventory/skus/ssku-46')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                id: 'ssku-46',
                                sellerSkuId: 'ssku-46',
                                sellerId: SELLER_UUID,
                                skuId: SKU_UUID,
                                skuCode: 'FSKU-00000046',
                                status: 'ACTIVE',
                                totalQty: 12,
                                availableQty: 12,
                                reservedQty: 0,
                                unavailableQty: 0,
                                warehouseCount: 1,
                                createdAt: '2026-11-11T10:00:00Z',
                                updatedAt: '2026-11-11T10:00:00Z',
                                warehouses: [
                                    {
                                        skuId: SKU_UUID,
                                        sellerSkuId: 'ssku-46',
                                        skuCode: 'FSKU-00000046',
                                        warehouseId: WAREHOUSE_UUID,
                                        warehouseCode: 'WH_001',
                                        totalQty: 12,
                                        availableQty: 12,
                                        reservedQty: 0,
                                        unavailableQty: 0,
                                    },
                                ],
                            },
                        }),
                    )
                }

                return new Response(JSON.stringify({ success: true, data: [] }))
            }),
        )

        render(<SellerInventoryPage />)

        // Ban đầu chưa có SKU nào trong kho Fulfillment; Seller không được gọi /inventory/ledger (tránh 403)
        await screen.findByText(/No Fulfillment SKUs registered yet/i)
        expect(fetchCalls.some((c) => c.includes('/inventory/ledger'))).toBe(false)

        // Mở Modal nhập kho và gửi POST /inventory/inbound
        fireEvent.click(screen.getByRole('button', { name: /Inbound Stock/i }))
        const dialog = await screen.findByRole('dialog')
        fireEvent.change(within(dialog).getByLabelText(/Quantity \*/i), {
            target: { value: '12' },
        })
        fireEvent.click(within(dialog).getByRole('button', { name: /Confirm Inbound/i }))

        // Banner và bảng danh sách cập nhật hiển thị mã FSKU-00000046 mới được Fulfillment cấp
        await screen.findByText(/Fulfillment SKU FSKU-00000046 at WH_001/i)
        await screen.findByText('Studio Headphones')

        // Mở modal Chi tiết kho GET /inventory/skus/ssku-46
        fireEvent.click(screen.getByRole('button', { name: /Details/i }))
        await screen.findByText('WH_001')
    })

    it('khi đăng nhập bằng Admin thì nạp /inventory/ledger và cho phép mở Adjust modal', async () => {
        holder.current.user = { ...DEMO_ADMIN, id: SELLER_UUID, role: 'admin' }
        let adjustCalled = false

        vi.stubGlobal(
            'fetch',
            vi.fn(async (input: string, init: RequestInit = {}) => {
                const url = String(input)
                if (url.includes('/inventory/skus?') && (!init.method || init.method === 'GET')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: [
                                {
                                    id: 'ssku-46',
                                    sellerSkuId: 'ssku-46',
                                    sellerId: SELLER_UUID,
                                    skuId: SKU_UUID,
                                    skuCode: 'FSKU-00000046',
                                    status: 'ACTIVE',
                                    totalQty: adjustCalled ? 10 : 12,
                                    availableQty: adjustCalled ? 10 : 12,
                                    reservedQty: 0,
                                    unavailableQty: 0,
                                    warehouseCount: 1,
                                    createdAt: '2026-11-11T10:00:00Z',
                                    updatedAt: '2026-11-11T10:00:00Z',
                                },
                            ],
                            meta: { pagination: { totalElements: 1 } },
                        }),
                    )
                }
                if (url.includes('/inventory/ledger?')) {
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: [
                                {
                                    id: 'led-1',
                                    skuId: SKU_UUID,
                                    warehouseId: WAREHOUSE_UUID,
                                    movementType: 'INBOUND',
                                    quantity: 12,
                                    refType: 'RESTOCK',
                                    refId: null,
                                    createdAt: '2026-11-11T10:00:00Z',
                                },
                            ],
                        }),
                    )
                }
                if (url.endsWith('/inventory/adjust') && init.method === 'POST') {
                    adjustCalled = true
                    return new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                skuId: SKU_UUID,
                                sellerSkuId: 'ssku-46',
                                skuCode: 'FSKU-00000046',
                                warehouseId: WAREHOUSE_UUID,
                                warehouseCode: 'WH_001',
                                totalQty: 10,
                                availableQty: 10,
                                reservedQty: 0,
                                unavailableQty: 0,
                            },
                        }),
                    )
                }
                return new Response(JSON.stringify({ success: true, data: [] }))
            }),
        )

        render(<SellerInventoryPage />)
        await screen.findByText('RESTOCK')

        fireEvent.click(screen.getByRole('button', { name: /^Adjust$/i }))
        const dialog = await screen.findByRole('dialog')
        fireEvent.change(within(dialog).getByLabelText(/Delta Quantity/i), {
            target: { value: '-2' },
        })
        fireEvent.change(within(dialog).getByLabelText(/Reason \*/i), {
            target: { value: 'Damaged box' },
        })
        fireEvent.click(within(dialog).getByRole('button', { name: /Apply Adjustment/i }))

        await waitFor(() => expect(adjustCalled).toBe(true))
    })
})
