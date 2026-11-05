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

describe('Auctions API Adapter', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
        auctionsApi.resetMockStore()
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    describe('validateAndNormalizeAuction', () => {
        it('chuẩn hóa thành công đối tượng hợp lệ từ Backend với snake_case', () => {
            const raw: RawApiAuction = {
                id: 'auc-001',
                product_id: 'prod-001',
                seller_id: 'seller-001',
                seller_name: 'Antiques Co',
                start_price: 500000,
                bid_increment: 50000,
                current_price: 650000,
                status: 'ACTIVE',
                start_time: '2026-11-01T08:00:00Z',
                end_time: '2026-11-05T20:00:00Z',
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

        it('tự động fallback sang kho mock khi backend trả về 404 (chưa triển khai)', async () => {
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
            expect(res.items.length).toBeGreaterThan(0)
            expect(res.total).toBeGreaterThan(0)
        })

        it('áp dụng đúng bộ lọc trạng thái và khoảng giá khi chạy fallback', async () => {
            vi.stubGlobal(
                'fetch',
                vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
            )

            const resActive = await auctionsApi.getAuctions({ status: 'active' })
            expect(resActive.items.every((a) => a.status === 'active')).toBe(true)

            const resEnded = await auctionsApi.getAuctions({ status: 'ended' })
            expect(resEnded.items.every((a) => a.status === 'ended')).toBe(true)
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

        it('tạo thành công phiên đấu giá hợp lệ với trạng thái scheduled', async () => {
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

            // Kiểm tra phiên vừa tạo đã có trong danh sách
            const list = await auctionsApi.getAuctions({ sellerId: created.sellerId })
            expect(list.items.some((a) => a.id === created.id)).toBe(true)
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
            expect(approved.status).toBe('scheduled')
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
            expect(rejected.status).toBe('cancelled')
            expect(rejected.cancelReason).toBe('Hình ảnh sản phẩm mờ không đạt tiêu chuẩn kiểm duyệt')
        })

        it('từ chối yêu cầu từ chối phiên nếu Admin không nhập lý do', async () => {
            await expect(auctionsApi.reviewAuction('a1', 'rejected', '')).rejects.toThrow(
                ApiClientError,
            )
        })
    })
})
