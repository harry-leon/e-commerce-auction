/**
 * Chức năng: Bộ kiểm thử đơn vị cho Auction API Adapter (auctionsApi).
 *            Kiểm tra các hàm lấy danh sách phiên đấu giá, xem chi tiết, đặt giá thầu,
 *            xử lý dữ liệu không hợp lệ (data validation/normalization) và cơ chế fallback.
 * Đầu vào: Dữ liệu mock phản hồi từ HTTP fetch, payload hợp lệ và không hợp lệ.
 * Kết quả: Đảm bảo adapter hoạt động ổn định, ném lỗi đúng chuẩn hoặc fallback an toàn.
 * Quy tắc & lý do: Bảo vệ chống hồi quy cho SRS §3.5 và các tiêu chí chấp nhận của Epic Auction Engine.
 * Lỗi & giới hạn: Chạy cô lập với vi.stubGlobal('fetch').
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
    auctionsApi,
    validateAndNormalizeAuction,
    validateAndNormalizeAuctionList,
    type RawApiAuction,
} from './auctions'
import { ApiClientError } from './client'
import { AUCTIONS } from '../mockData'

describe('Auctions API Adapter', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
        auctionsApi.resetMockStore()
        AUCTIONS.forEach((auction) => auctionsApi.addCreatedAuction(auction))
        vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
            const url = new URL(String(input), 'http://localhost')
            if (url.pathname.endsWith('/auctions') && init?.method === 'POST') {
                const body = JSON.parse(String(init.body))
                return new Response(JSON.stringify({
                    success: true,
                    data: {
                        id: '9b96b4ea-842c-4c91-9e9e-a91a2e55baf1',
                        product_id: body.productId,
                        seller_id: '1bb61969-bb72-4d69-8530-1b7eb7e30b50',
                        seller_name: 'Test Seller',
                        start_price: body.startPrice,
                        current_price: body.startPrice,
                        bid_increment: body.bidIncrement,
                        status: 'SCHEDULED',
                        start_time: body.startTime,
                        end_time: body.endTime,
                        visibility: body.visibility,
                    },
                }), { status: 201, headers: { 'Content-Type': 'application/json' } })
            }
            return new Response(JSON.stringify({ success: false, error: { message: 'Not found' } }), {
                status: 404,
                headers: { 'Content-Type': 'application/json' },
            })
        }))
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    describe('validateAndNormalizeAuction', () => {
        it('chuẩn hóa thành công đối tượng hợp lệ từ Backend với snake_case', () => {
            // Giữ fixture trong khoảng thời gian đang diễn ra để kiểm thử không hết hạn theo ngày chạy.
            const activeStart = new Date(Date.now() - 60 * 60 * 1000).toISOString()
            const activeEnd = new Date(Date.now() + 60 * 60 * 1000).toISOString()
            const raw: RawApiAuction = {
                id: 'auc-001',
                product_id: 'prod-001',
                seller_id: 'seller-001',
                seller_name: 'Antiques Co',
                start_price: 500000,
                bid_increment: 50000,
                current_price: 650000,
                status: 'ACTIVE',
                start_time: activeStart,
                end_time: activeEnd,
                visibility: 'PUBLIC',
                bid_count: 3,
                product_name: 'Đồng hồ Thụy Sĩ cổ',
                product_image: 'https://example.com/watch.jpg',
                bids: [
                    {
                        id: 'bid-1',
                        bidder_id: 'user-1',
                        bidder_name: 'Nguyen Van A',
                        amount: 650000,
                        placed_at: '2026-11-01T10:00:00Z',
                    },
                ],
            }

            const normalized = validateAndNormalizeAuction(raw)
            expect(normalized).not.toBeNull()
            expect(normalized?.id).toBe('auc-001')
            expect(normalized?.status).toBe('active')
            expect(normalized?.startingPrice).toBe(500000)
            expect(normalized?.currentBid).toBe(650000)
            expect(normalized?.minIncrement).toBe(50000)
            expect(normalized?.product.name).toBe('Đồng hồ Thụy Sĩ cổ')
            expect(normalized?.bids).toHaveLength(1)
            expect(normalized?.bids[0].amount).toBe(650000)
        })

        it('từ chối và trả về null khi dữ liệu thiếu ID hoặc không phải object', () => {
            expect(validateAndNormalizeAuction(null)).toBeNull()
            expect(validateAndNormalizeAuction(undefined)).toBeNull()
            expect(validateAndNormalizeAuction('invalid string')).toBeNull()
            expect(validateAndNormalizeAuction({ product_id: 'p1' })).toBeNull()
            expect(validateAndNormalizeAuction({ id: '   ' })).toBeNull()
        })

        // Bảo vệ việc chuẩn hóa tên và email người gửi duyệt từ các alias phổ biến của Auction API.
        it('chuẩn hóa thông tin tài khoản Seller riêng với tên cửa hàng', () => {
            const normalized = validateAndNormalizeAuction({
                id: 'auc-requester',
                seller_name: 'TechZone Store',
                seller_full_name: 'Seller Nexus',
                seller_email: 'seller@nexus.vn',
            })

            expect(normalized?.sellerName).toBe('TechZone Store')
            expect(normalized?.sellerAccountName).toBe('Seller Nexus')
            expect(normalized?.sellerEmail).toBe('seller@nexus.vn')
        })

        it('bảo vệ và làm sạch khi giá trị số bị NaN hoặc âm', () => {
            const corrupt = {
                id: 'auc-corrupt',
                start_price: -100,
                current_price: NaN,
                bid_increment: 'invalid',
                status: 'ACTIVE',
            }

            const normalized = validateAndNormalizeAuction(corrupt)
            expect(normalized).not.toBeNull()
            expect(normalized?.startingPrice).toBeGreaterThan(0)
            expect(normalized?.currentBid).toBeGreaterThanOrEqual(normalized?.startingPrice || 0)
            expect(normalized?.minIncrement).toBeGreaterThan(0)
        })

        it('chuẩn hóa thời gian kết thúc không hợp lệ về thời điểm an toàn trong tương lai', () => {
            const corruptDate = {
                id: 'auc-bad-date',
                start_time: 'not-a-valid-date',
                end_time: 'corrupt-time',
            }

            const normalized = validateAndNormalizeAuction(corruptDate)
            expect(normalized).not.toBeNull()
            expect(new Date(normalized!.startTime).getTime()).not.toBeNaN()
            expect(new Date(normalized!.endTime).getTime()).not.toBeNaN()
        })
    })

    describe('validateAndNormalizeAuctionList', () => {
        it('bóc tách mảng từ content hoặc data trong paginated response', () => {
            const envelope = {
                content: [
                    { id: 'auc-1', start_price: 100000, status: 'ACTIVE' },
                    { id: 'auc-2', start_price: 200000, status: 'SCHEDULED' },
                ],
                totalElements: 2,
            }

            const list = validateAndNormalizeAuctionList(envelope)
            expect(list).toHaveLength(2)
            expect(list[0].id).toBe('auc-1')
            expect(list[1].id).toBe('auc-2')
        })

        it('lọc bỏ các phần tử hỏng mà không làm sập toàn bộ danh sách', () => {
            const mixed = [
                { id: 'auc-valid', start_price: 100000 },
                null,
                { something: 'corrupt without id' },
                { id: 'auc-valid-2', start_price: 250000 },
            ]

            const list = validateAndNormalizeAuctionList(mixed)
            expect(list).toHaveLength(2)
            expect(list.map((a) => a.id)).toEqual(['auc-valid', 'auc-valid-2'])
        })

        it('ném ApiClientError khi payload hoàn toàn không phải danh sách', () => {
            expect(() => validateAndNormalizeAuctionList('invalid string')).toThrow(ApiClientError)
            expect(() => validateAndNormalizeAuctionList(12345)).toThrow(ApiClientError)
        })
    })

    describe('auctionsApi.getAuctions', () => {
        it('gọi đúng endpoint /auctions và trả về danh sách từ API khi backend thành công', async () => {
            const mockApiItems = [
                {
                    id: 'auc-live-1',
                    product_id: 'p1',
                    start_price: 300000,
                    current_price: 450000,
                    bid_increment: 20000,
                    status: 'ACTIVE',
                    product_name: 'Vintage Camera',
                },
            ]

            vi.stubGlobal(
                'fetch',
                vi.fn().mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            success: true,
                            data: mockApiItems,
                        }),
                        { status: 200, headers: { 'Content-Type': 'application/json' } },
                    ),
                ),
            )

            const res = await auctionsApi.getAuctions({ status: 'active', page: 1 })
            expect(res.items).toHaveLength(1)
            expect(res.items[0].id).toBe('auc-live-1')
            expect(res.items[0].currentBid).toBe(450000)
        })

        // Bảo vệ Seller/Admin khỏi fixture cũ; khi API chưa sẵn sàng danh sách bắt đầu rỗng.
        it('không trả fixture mặc định trong chế độ portal Seller/Admin khi API lỗi', async () => {
            vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

            const result = await auctionsApi.getAuctions({ includePending: true, excludeDemoData: true })

            expect(result.items).toEqual([])
            expect(result.total).toBe(0)

            // Một backend demo có thể trả lại chính fixture cũ; portal vẫn phải ẩn chúng.
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, data: AUCTIONS }), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            })))
            const demoApiResult = await auctionsApi.getAuctions({ includePending: true, excludeDemoData: true })
            expect(demoApiResult.items).toEqual([])
        })

        // Bảo vệ phiên vừa tạo khỏi bị mất khỏi Seller/Admin khi GET thành công nhưng backend chưa phản ánh bản ghi mới.
        it('giữ phiên mới tạo trong danh sách Seller và Admin khi API list trả mảng rỗng', async () => {
            const created = {
                ...AUCTIONS[1],
                id: 'auc-new-vintage-watch',
                sellerId: 'u-seller',
                sellerName: 'Seller Nexus',
                product: { ...AUCTIONS[1].product, name: 'Vintage Mechanical Watch' },
                currentBid: 2005,
                status: 'pending_approval' as const,
            }
            auctionsApi.addCreatedAuction(created)

            vi.stubGlobal(
                'fetch',
                vi.fn().mockImplementation(() =>
                    Promise.resolve(new Response(JSON.stringify({ success: true, data: [] }), {
                        status: 200,
                        headers: { 'Content-Type': 'application/json' },
                    })),
                ),
            )

            const sellerList = await auctionsApi.getAuctions({ sellerId: 'seller-current', sellerName: 'Seller Nexus' })
            const adminList = await auctionsApi.getAuctions({ includePending: true })

            expect(sellerList.items.some((auction) => auction.id === created.id)).toBe(true)
            expect(adminList.items.some((auction) => auction.id === created.id)).toBe(true)
            expect(sellerList.items.find((auction) => auction.id === created.id)?.currentBid).toBe(2005)
            expect(adminList.items.find((auction) => auction.id === created.id)?.product.name).toBe('Vintage Mechanical Watch')
            expect(sellerList.items.find((auction) => auction.id === created.id)?.sellerId).toBe('seller-current')

            vi.stubGlobal(
                'fetch',
                vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
                    success: true,
                    data: [{
                        id: created.id,
                        product_id: created.productId,
                        product_name: 'Vintage Mechanical Watch',
                        start_price: 2005,
                        current_price: 2005,
                        bid_increment: 1000,
                        status: 'DRAFT',
                        start_time: created.startTime,
                        end_time: created.endTime,
                    }],
                }), { status: 200, headers: { 'Content-Type': 'application/json' } }))),
            )
            const serverList = await auctionsApi.getAuctions({ sellerId: 'seller-current', sellerName: 'Seller Nexus' })
            expect(serverList.items.find((auction) => auction.id === created.id)?.sellerId).toBe('seller-current')
        })

        it('không tự hiển thị fixture khi backend trả về 404', async () => {
            vi.stubGlobal(
                'fetch',
                vi.fn().mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            success: false,
                            error: { code: 'NOT_FOUND', message: 'Resource not found' },
                        }),
                        { status: 404, headers: { 'Content-Type': 'application/json' } },
                    ),
                ),
            )

            const res = await auctionsApi.getAuctions({ status: 'all', page: 1, limit: 9 })
            expect(res.items).toEqual([])
            expect(res.total).toBe(0)
        })

        it('không dựng dữ liệu mẫu khi API mất kết nối', async () => {
            vi.stubGlobal(
                'fetch',
                vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
            )

            const resActive = await auctionsApi.getAuctions({ status: 'active' })
            expect(resActive.items).toEqual([])

            const resEnded = await auctionsApi.getAuctions({ status: 'ended' })
            expect(resEnded.items).toEqual([])
        })

        it('ẩn phiên local có ID timestamp vì phiên đó không thể được Auction Service cập nhật', async () => {
            auctionsApi.addCreatedAuction({ ...AUCTIONS[0], id: 'auc-1793977636924' })
            vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

            const result = await auctionsApi.getAuctions({ status: 'all' })

            expect(result.items.some((auction) => auction.id === 'auc-1793977636924')).toBe(false)
        })
    })

    describe('auctionsApi.getAuctionById', () => {
        it('trả về chi tiết phiên đấu giá khi tìm thấy ID', async () => {
            vi.stubGlobal(
                'fetch',
                vi.fn().mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                id: 'auc-detail-1',
                                product_id: 'p10',
                                start_price: 1000000,
                                current_price: 1500000,
                                bid_increment: 100000,
                                status: 'ACTIVE',
                                product_name: 'Tranh sơn mài cổ',
                            },
                        }),
                        { status: 200, headers: { 'Content-Type': 'application/json' } },
                    ),
                ),
            )

            const auction = await auctionsApi.getAuctionById('auc-detail-1')
            expect(auction.id).toBe('auc-detail-1')
            expect(auction.currentBid).toBe(1500000)
            expect(auction.product.name).toBe('Tranh sơn mài cổ')
        })

        it('ném lỗi 404 khi không tìm thấy phiên đấu giá ở cả API lẫn mock', async () => {
            vi.stubGlobal(
                'fetch',
                vi.fn().mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            success: false,
                            error: { code: 'NOT_FOUND', message: 'Auction not found' },
                        }),
                        { status: 404, headers: { 'Content-Type': 'application/json' } },
                    ),
                ),
            )

            await expect(auctionsApi.getAuctionById('non-existent-id-9999')).rejects.toThrow(
                ApiClientError,
            )
        })
    })

    describe('auctionsApi.placeBid', () => {
        it('từ chối khi mức giá đặt <= 0', async () => {
            await expect(auctionsApi.placeBid('a1', 0)).rejects.toThrow(ApiClientError)
            await expect(auctionsApi.placeBid('a1', -50000)).rejects.toThrow(ApiClientError)
        })

        it('đặt giá thầu thành công và cập nhật giá mới', async () => {
            vi.stubGlobal(
                'fetch',
                vi.fn().mockResolvedValue(
                    new Response(
                        JSON.stringify({
                            success: true,
                            data: {
                                bid: {
                                    id: 'bid-new-1',
                                    bidderId: 'u1',
                                    bidderName: 'Nguyen Van A',
                                    amount: 250.0,
                                    placedAt: new Date().toISOString(),
                                },
                                currentBid: 250.0,
                                bidCount: 19,
                            },
                        }),
                        { status: 200, headers: { 'Content-Type': 'application/json' } },
                    ),
                ),
            )

            const res = await auctionsApi.placeBid('a1', 250.0)
            expect(res.currentBid).toBe(250.0)
            expect(res.bidCount).toBe(19)
            expect(res.bid.amount).toBe(250.0)
        })
    })

    describe('auctionsApi.createAuction (Khởi tạo phiên đấu giá)', () => {
        /**
         * Kiểm thử validation chặt chẽ:
         * - Giá khởi điểm > 0
         * - Bước giá >= 1,000 VND
         * - Thời gian bắt đầu > thời gian hiện tại
         * - Thời gian kết thúc > thời gian bắt đầu
         * - Thời lượng tối thiểu 1 giờ, tối đa 7 ngày
         */
        it('từ chối tạo phiên khi thiếu sản phẩm', async () => {
            await expect(
                auctionsApi.createAuction({
                    productId: '',
                    startingPrice: 500000,
                    minIncrement: 10000,
                    startTime: new Date(Date.now() + 3600000).toISOString(),
                    endTime: new Date(Date.now() + 7200000).toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)
        })

        it('từ chối tạo phiên khi giá khởi điểm <= 0', async () => {
            await expect(
                auctionsApi.createAuction({
                    productId: 'p1',
                    startingPrice: 0,
                    minIncrement: 10000,
                    startTime: new Date(Date.now() + 3600000).toISOString(),
                    endTime: new Date(Date.now() + 7200000).toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)

            await expect(
                auctionsApi.createAuction({
                    productId: 'p1',
                    startingPrice: -1000,
                    minIncrement: 10000,
                    startTime: new Date(Date.now() + 3600000).toISOString(),
                    endTime: new Date(Date.now() + 7200000).toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)
        })

        it('từ chối tạo phiên khi bước giá nhỏ hơn quy định 1,000 VND', async () => {
            await expect(
                auctionsApi.createAuction({
                    productId: 'p1',
                    startingPrice: 500000,
                    minIncrement: 500,
                    startTime: new Date(Date.now() + 3600000).toISOString(),
                    endTime: new Date(Date.now() + 7200000).toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)
        })

        it('từ chối tạo phiên khi thời gian bắt đầu nằm trong quá khứ', async () => {
            await expect(
                auctionsApi.createAuction({
                    productId: 'p1',
                    startingPrice: 500000,
                    minIncrement: 10000,
                    startTime: new Date(Date.now() - 3600000).toISOString(),
                    endTime: new Date(Date.now() + 7200000).toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)
        })

        it('từ chối tạo phiên khi thời gian kết thúc trước thời gian bắt đầu hoặc thời lượng < 1 giờ', async () => {
            const start = new Date(Date.now() + 3600000)
            const endBeforeStart = new Date(start.getTime() - 10000)
            const endTooShort = new Date(start.getTime() + 1800000) // 30 phút

            await expect(
                auctionsApi.createAuction({
                    productId: 'p1',
                    startingPrice: 500000,
                    minIncrement: 10000,
                    startTime: start.toISOString(),
                    endTime: endBeforeStart.toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)

            await expect(
                auctionsApi.createAuction({
                    productId: 'p1',
                    startingPrice: 500000,
                    minIncrement: 10000,
                    startTime: start.toISOString(),
                    endTime: endTooShort.toISOString(),
                }),
            ).rejects.toThrow(ApiClientError)
        })

        it('tạo phiên qua API, nhận status SCHEDULED từ backend và không dùng dữ liệu cục bộ', async () => {
            const start = new Date(Date.now() + 3600000)
            const end = new Date(start.getTime() + 86400000) // 24 giờ

            const created = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 1000000,
                minIncrement: 50000,
                startTime: start.toISOString(),
                endTime: end.toISOString(),
                visibility: 'public',
            })

            expect(created).toBeDefined()
            expect(created.status).toBe('scheduled')
            expect(created.startingPrice).toBe(1000000)
            expect(created.minIncrement).toBe(50000)
            expect(created.productId).toBe('p1')

            // Nếu list backend chưa đồng bộ ở request kế tiếp, session Seller vẫn giữ response đã được server xác nhận.
            const list = await auctionsApi.getAuctions({ sellerId: created.sellerId, extraAuctions: [created] })
            expect(list.items.some((a) => a.id === created.id)).toBe(true)
        })

        it('không báo thành công hoặc lưu phiên local khi POST Auction API lỗi', async () => {
            vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
            const start = new Date(Date.now() + 3600000)

            await expect(auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 1000000,
                minIncrement: 50000,
                startTime: start.toISOString(),
                endTime: new Date(start.getTime() + 86400000).toISOString(),
            })).rejects.toThrow()

            expect(JSON.parse(localStorage.getItem('nexus.auction-portal-store.v2') || '[]')
                .some((auction: { id: string }) => /^auc-\d+$/.test(auction.id))).toBe(false)
        })
    })

    describe('auctionsApi.updateAuction (Chỉnh sửa phiên trước giờ mở)', () => {
        it('cập nhật thành công thông số khi phiên ở trạng thái scheduled và chưa bắt đầu', async () => {
            const start = new Date(Date.now() + 7200000)
            const end = new Date(start.getTime() + 86400000)

            const created = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 500000,
                minIncrement: 10000,
                startTime: start.toISOString(),
                endTime: end.toISOString(),
            })

            const updated = await auctionsApi.updateAuction(created.id, {
                startingPrice: 600000,
                minIncrement: 20000,
            })

            expect(updated.startingPrice).toBe(600000)
            expect(updated.minIncrement).toBe(20000)
        })

        it('từ chối cập nhật khi phiên đấu giá đã ACTIVE hoặc đã qua startTime', async () => {
            // Phiên a1 trong mockData đang ACTIVE
            await expect(
                auctionsApi.updateAuction('a1', {
                    startingPrice: 999999,
                }),
            ).rejects.toThrow(ApiClientError)
        })
    })

    describe('auctionsApi.cancelAuction (Hủy phiên đấu giá)', () => {
        it('từ chối hủy phiên khi không cung cấp lý do', async () => {
            await expect(auctionsApi.cancelAuction('a1', '', 'seller')).rejects.toThrow(
                ApiClientError,
            )
            await expect(auctionsApi.cancelAuction('a1', '   ', 'admin')).rejects.toThrow(
                ApiClientError,
            )
        })

        it('Người bán hủy thành công phiên scheduled trước giờ mở phiên', async () => {
            const start = new Date(Date.now() + 7200000)
            const end = new Date(start.getTime() + 86400000)

            const created = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 500000,
                minIncrement: 10000,
                startTime: start.toISOString(),
                endTime: end.toISOString(),
            })

            const cancelled = await auctionsApi.cancelAuction(
                created.id,
                'Người bán đổi ý không muốn đấu giá nữa',
                'seller',
            )

            expect(cancelled.status).toBe('cancelled')
            expect(cancelled.cancelReason).toBe('Người bán đổi ý không muốn đấu giá nữa')
        })

        it('Người bán không được phép hủy phiên đang ACTIVE (403 Forbidden)', async () => {
            // Phiên a1 đang ACTIVE
            await expect(
                auctionsApi.cancelAuction('a1', 'Muốn hủy ngang', 'seller'),
            ).rejects.toThrow(ApiClientError)
        })

        it('Quản trị viên (Admin) có quyền cưỡng chế hủy phiên vi phạm kể cả khi đang ACTIVE', async () => {
            const forceCancelled = await auctionsApi.cancelAuction(
                'a1',
                'Phát hiện hàng giả/vi phạm bản quyền nghiêm trọng',
                'admin',
            )

            expect(forceCancelled.status).toBe('cancelled')
            expect(forceCancelled.cancelReason).toBe(
                'Phát hiện hàng giả/vi phạm bản quyền nghiêm trọng',
            )
        })
    })

    describe('auctionsApi.reviewAuction (Phê duyệt và từ chối phiên của Admin)', () => {
        it('Admin phê duyệt phiên thành công chuyển sang scheduled', async () => {
            const start = new Date(Date.now() + 7200000)
            const end = new Date(start.getTime() + 86400000)

            const created = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 800000,
                minIncrement: 10000,
                startTime: start.toISOString(),
                endTime: end.toISOString(),
            })

            const approved = await auctionsApi.reviewAuction(created.id, 'scheduled')
            expect(approved?.status).toBe('scheduled')
        })

        // Bảo vệ quyết định duyệt khi API list vẫn trả DRAFT cũ sau thao tác Admin.
        it('giữ trạng thái đã duyệt sau khi API trả lại DRAFT cũ', async () => {
            const seededAuction = { ...AUCTIONS[4], status: 'pending_approval' as const }
            const staleDraft = {
                id: seededAuction.id,
                product_id: seededAuction.productId,
                product_name: seededAuction.product.name,
                start_price: seededAuction.startingPrice,
                current_price: seededAuction.currentBid,
                bid_increment: seededAuction.minIncrement,
                status: 'DRAFT',
                start_time: seededAuction.startTime,
                end_time: seededAuction.endTime,
            }

            vi.stubGlobal('fetch', vi.fn().mockImplementation((input: RequestInfo | URL) => {
                const url = String(input)
                if (url.includes('/review')) {
                    return Promise.resolve(new Response('{}', { status: 404 }))
                }
                return Promise.resolve(new Response(JSON.stringify({ success: true, data: [staleDraft] }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                }))
            }))

            await auctionsApi.reviewAuction(seededAuction.id, 'scheduled')
            const approvedList = await auctionsApi.getAuctions({ status: 'scheduled', includePending: true })
            const pendingList = await auctionsApi.getAuctions({ status: 'pending_approval', includePending: true })

            expect(approvedList.items.some((auction) => auction.id === seededAuction.id)).toBe(true)
            expect(pendingList.items.some((auction) => auction.id === seededAuction.id)).toBe(false)
            expect(JSON.parse(localStorage.getItem('nexus.auction-reviews-store') || '{}')[seededAuction.id].status)
                .toBe('scheduled')
        })

        it('Admin từ chối phiên kèm lý do chuyển trạng thái sang cancelled/rejected', async () => {
            const start = new Date(Date.now() + 7200000)
            const end = new Date(start.getTime() + 86400000)

            const created = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 800000,
                minIncrement: 10000,
                startTime: start.toISOString(),
                endTime: end.toISOString(),
            })

            const rejected = await auctionsApi.reviewAuction(
                created.id,
                'rejected',
                'Hình ảnh sản phẩm mờ không đạt tiêu chuẩn kiểm duyệt',
            )
            expect(rejected?.status).toBe('rejected')
            expect(rejected?.cancelReason).toBe('Hình ảnh sản phẩm mờ không đạt tiêu chuẩn kiểm duyệt')
        })

        it('từ chối yêu cầu từ chối phiên nếu Admin không nhập lý do', async () => {
            await expect(auctionsApi.reviewAuction('a1', 'rejected', '')).rejects.toThrow(
                ApiClientError,
            )
        })
    })
})
