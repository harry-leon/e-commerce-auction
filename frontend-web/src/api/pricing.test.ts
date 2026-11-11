import { afterEach, describe, expect, it, vi } from 'vitest'
import {
    createPricingRule,
    deletePricingRule,
    listActivePricingRules,
    listAllPricingRules,
    previewDynamicPrice,
    updatePricingRule,
} from './pricing'
import { startPayment } from './payments'
import { normalizeOrder } from './orderData'
import { formatPriceRange, price } from '../features/catalog/catalog'
import type { ApiProduct } from './client'

afterEach(() => {
    vi.unstubAllGlobals()
})

/**
 * Kiểm thử đơn vị cho hợp đồng Dynamic Pricing & Deferred Order Price Settlement trên Frontend.
 * Bảo vệ trước hồi quy:
 * 1. Hiển thị khoảng giá động [minPrice – maxPrice] từ ACTIVE SKUs.
 * 2. Bảo toàn cờ priceFinalized=false và fallback sang estimated* khi official price là null.
 * 3. Gọi API /pricing-rules/* và /payments kèm header Idempotency-Key khi retry.
 */
describe('Dynamic pricing & payment attempt contracts', () => {
    it('renders dynamic price range from minPrice and maxPrice', () => {
        const rangedProduct = {
            id: 'p-1',
            minPrice: 115000,
            maxPrice: 230000,
            skus: [],
        } as unknown as ApiProduct
        expect(price(rangedProduct)).toContain('115.000')
        expect(price(rangedProduct)).toContain('230.000')

        const flatProduct = {
            id: 'p-2',
            minPrice: 115000,
            maxPrice: 115000,
            skus: [],
        } as unknown as ApiProduct
        expect(formatPriceRange(flatProduct.minPrice, flatProduct.maxPrice, 'en')).toBe(
            '$115,000.00',
        )

        const skuFallback = {
            id: 'p-3',
            skus: [
                { id: 's1', skuCode: 'S1', price: 150000, originalPrice: 100000, status: 'ACTIVE' },
                { id: 's2', skuCode: 'S2', price: 300000, originalPrice: 200000, status: 'ACTIVE' },
                {
                    id: 's3',
                    skuCode: 'S3',
                    price: 999999,
                    originalPrice: 900000,
                    status: 'INACTIVE',
                },
            ],
        } as unknown as ApiProduct
        const rendered = price(skuFallback)
        expect(rendered).toContain('150.000')
        expect(rendered).toContain('300.000')
        expect(rendered).not.toContain('999.999')
    })

    it('preserves provisional estimated totals when official order prices are null', () => {
        const order = normalizeOrder({
            id: 'ord-draft-1',
            status: 'PENDING_PAYMENT',
            priceFinalized: false,
            subtotal: null,
            shippingFee: null,
            discount: null,
            total: null,
            estimatedSubtotal: 460000,
            estimatedShippingFee: 30000,
            estimatedDiscount: 0,
            estimatedTotal: 490000,
            items: [
                {
                    skuId: 'sku-a',
                    productName: 'Laptop Pro',
                    quantity: 2,
                    unitPrice: null,
                    subtotal: null,
                    estimatedUnitPrice: 230000,
                    estimatedSubtotal: 460000,
                },
            ],
        })
        expect(order.priceFinalized).toBe(false)
        expect(order.officialTotal).toBeNull()
        expect(order.total).toBe(490000)
        expect(order.shipping).toBe(30000)
        expect(order.items[0].unitPrice).toBe(230000)
        expect(order.items[0].officialUnitPrice).toBeNull()
    })

    it('calls pricing-rules APIs and unwraps ApiResponse payloads', async () => {
        const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
            const url = String(input)
            if (url.includes('/pricing-rules/preview')) {
                return new Response(
                    JSON.stringify({
                        success: true,
                        data: {
                            originalPrice: 100000,
                            sellingPrice: 120000,
                            appliedRuleId: 'r-1',
                            appliedRuleName: 'Tech Season',
                            marginRate: 0.15,
                            seasonFactor: 0.05,
                            marketAdjustmentRate: 0,
                            effectiveRate: 0.2,
                        },
                    }),
                )
            }
            if (url.endsWith('/pricing-rules') || url.endsWith('/pricing-rules/all')) {
                return new Response(JSON.stringify({ success: true, data: [{ id: 'r-1' }] }))
            }
            return new Response(
                JSON.stringify({ success: true, data: { id: 'r-1', name: 'Rule' } }),
            )
        })
        vi.stubGlobal('fetch', fetchMock)

        const preview = await previewDynamicPrice(100000, 'cat-1')
        expect(preview.sellingPrice).toBe(120000)
        expect(await listActivePricingRules()).toHaveLength(1)
        expect(await listAllPricingRules()).toHaveLength(1)
        await createPricingRule({
            name: 'Holiday',
            marginRate: 0.15,
            seasonFactor: 0.05,
            marketAdjustmentRate: 0,
            priority: 10,
        })
        await updatePricingRule('r-1', {
            name: 'Holiday V2',
            marginRate: 0.15,
            seasonFactor: 0.05,
            marketAdjustmentRate: 0,
            priority: 10,
        })
        await deletePricingRule('r-1')
        expect(fetchMock).toHaveBeenCalledTimes(6)
    })

    it('forwards Idempotency-Key header when retrying startPayment', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            new Response(
                JSON.stringify({
                    success: true,
                    data: {
                        paymentId: 'pay-retry-2',
                        orderId: 'ord-1',
                        status: 'PENDING',
                        amount: 490000,
                        currency: 'VND',
                        paymentUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?v=1',
                        expiresAt: '2026-11-11T12:00:00Z',
                    },
                }),
            ),
        )
        vi.stubGlobal('fetch', fetchMock)

        const result = await startPayment('ord-1', '11111111-1111-1111-1111-111111111111')
        expect(result.paymentId).toBe('pay-retry-2')
        const [, init] = fetchMock.mock.calls[0]
        // api() chuẩn hóa init.headers thành Headers instance trước khi truyền xuống fetch().
        const headers = new Headers(init?.headers)
        expect(headers.get('Idempotency-Key')).toBe('11111111-1111-1111-1111-111111111111')
    })
})
