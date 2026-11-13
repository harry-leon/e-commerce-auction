/**
 * Unit Tests cho Reputation & Rating API Client (Sprint 3 - HA-S3-2)
 *
 * Kiểm tra các chức năng:
 * 1. calculateTrustLevel: phân hạng TrustLevel chuẩn theo SRS (<60 LOW, 60..109 STANDARD, 110..149 HIGH, >=150 VIP).
 * 2. createRating: kiểm tra validation client (orderId, rateeId, score 1..5, comment length) và gửi request POST.
 * 3. getRatingsForUser: gọi endpoint GET danh sách đánh giá theo userId.
 * 4. getRatingsForOrder: gọi endpoint GET danh sách đánh giá theo orderId.
 * 5. getMyReputation & getUserReputation: lấy thông tin tóm tắt điểm uy tín.
 * 6. checkEligibility: kiểm tra điều kiện đấu giá theo action.
 * 7. flagDispute: gửi yêu cầu khiếu nại đánh giá vi phạm.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reputationApi, calculateTrustLevel } from './reputation'
import * as client from './client'

describe('Reputation & Rating API Service', () => {
    beforeEach(() => {
        vi.restoreAllMocks()
    })

    describe('calculateTrustLevel', () => {
        it('phân hạng chính xác cấp độ uy tín dựa trên điểm số', () => {
            expect(calculateTrustLevel(160)).toBe('VIP')
            expect(calculateTrustLevel(150)).toBe('VIP')
            expect(calculateTrustLevel(120)).toBe('HIGH')
            expect(calculateTrustLevel(110)).toBe('HIGH')
            expect(calculateTrustLevel(100)).toBe('STANDARD')
            expect(calculateTrustLevel(60)).toBe('STANDARD')
            expect(calculateTrustLevel(59)).toBe('LOW')
            expect(calculateTrustLevel(0)).toBe('LOW')
        })
    })

    describe('createRating', () => {
        it('chặn gửi rating nếu thiếu orderId hoặc rateeId', async () => {
            await expect(
                reputationApi.createRating({ orderId: '', rateeId: 'user-2', score: 5 }),
            ).rejects.toThrow('Mã đơn hàng không được để trống')

            await expect(
                reputationApi.createRating({ orderId: 'ord-1', rateeId: '', score: 5 }),
            ).rejects.toThrow('Mã người được đánh giá không được để trống')
        })

        it('chặn điểm đánh giá không hợp lệ (ngoài khoảng 1..5)', async () => {
            await expect(
                reputationApi.createRating({ orderId: 'ord-1', rateeId: 'user-2', score: 0 }),
            ).rejects.toThrow('Điểm đánh giá phải từ 1 đến 5 sao')

            await expect(
                reputationApi.createRating({ orderId: 'ord-1', rateeId: 'user-2', score: 6 }),
            ).rejects.toThrow('Điểm đánh giá phải từ 1 đến 5 sao')
        })

        it('gửi POST /ratings với dữ liệu hợp lệ', async () => {
            const mockResponse = {
                id: 'rate-123',
                orderId: 'ord-1',
                raterId: 'user-1',
                rateeId: 'user-2',
                score: 5,
                comment: 'Giao hàng nhanh, đóng gói cẩn thận',
                isAggregated: true,
                isDisputed: false,
                createdAt: '2026-11-13T10:00:00Z',
            }

            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: mockResponse,
            } as any)

            const result = await reputationApi.createRating({
                orderId: 'ord-1',
                rateeId: 'user-2',
                score: 5,
                comment: 'Giao hàng nhanh, đóng gói cẩn thận',
            })

            expect(apiSpy).toHaveBeenCalledWith(
                '/ratings',
                expect.objectContaining({
                    method: 'POST',
                    body: expect.stringContaining('"score":5'),
                }),
            )
            expect(result.id).toBe('rate-123')
            expect(result.score).toBe(5)
        })
    })

    describe('getRatingsForUser', () => {
        it('gọi GET /ratings/user/{userId} phân trang', async () => {
            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: {
                    content: [
                        { id: 'rate-1', score: 5, comment: 'Tốt' },
                        { id: 'rate-2', score: 4, comment: 'Hài lòng' },
                    ],
                    totalElements: 2,
                },
            } as any)

            const result = await reputationApi.getRatingsForUser('user-seller-1', 0, 10)
            expect(apiSpy).toHaveBeenCalledWith('/ratings/user/user-seller-1?page=0&size=10')
            expect(result.content.length).toBe(2)
            expect(result.totalElements).toBe(2)
        })
    })

    describe('getRatingsForOrder', () => {
        it('gọi GET /ratings/order/{orderId}', async () => {
            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: [{ id: 'rate-1', orderId: 'ord-99', score: 5 }],
            } as any)

            const result = await reputationApi.getRatingsForOrder('ord-99')
            expect(apiSpy).toHaveBeenCalledWith('/ratings/order/ord-99')
            expect(result.length).toBe(1)
        })
    })

    describe('getMyReputation & getUserReputation', () => {
        it('gọi GET /reputations/me cho tài khoản hiện tại', async () => {
            const mockSummary = {
                userId: 'user-current',
                score: 120,
                trustLevel: 'HIGH',
                totalTx: 15,
                successTx: 15,
                failedTx: 0,
                auctionWon: 3,
                auctionFailed: 0,
                avgRating: 4.9,
                totalRatings: 12,
            }

            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: mockSummary,
            } as any)

            const result = await reputationApi.getMyReputation()
            expect(apiSpy).toHaveBeenCalledWith('/reputations/me')
            expect(result.trustLevel).toBe('HIGH')
            expect(result.score).toBe(120)
        })

        it('gọi GET /reputations/{userId} cho người dùng cụ thể', async () => {
            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: { userId: 'user-seller-1', score: 160, trustLevel: 'VIP' },
            } as any)

            const result = await reputationApi.getUserReputation('user-seller-1')
            expect(apiSpy).toHaveBeenCalledWith('/reputations/user-seller-1')
            expect(result.trustLevel).toBe('VIP')
        })
    })

    describe('checkEligibility & flagDispute', () => {
        it('gọi GET /reputations/{userId}/eligibility?action=BID', async () => {
            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: { eligible: true, score: 100, minRequiredScore: 40, trustLevel: 'STANDARD' },
            } as any)

            const result = await reputationApi.checkEligibility('user-buyer-1', 'BID')
            expect(apiSpy).toHaveBeenCalledWith('/reputations/user-buyer-1/eligibility?action=BID')
            expect(result.eligible).toBe(true)
        })

        it('gọi POST /ratings/{ratingId}/dispute khi khiếu nại đánh giá', async () => {
            const apiSpy = vi.spyOn(client, 'api').mockResolvedValue({
                success: true,
                data: { id: 'rate-1', isDisputed: true },
            } as any)

            const result = await reputationApi.flagDispute('rate-1', 'Người mua đánh giá sai sự thật')
            expect(apiSpy).toHaveBeenCalledWith(
                '/ratings/rate-1/dispute',
                expect.objectContaining({
                    method: 'POST',
                }),
            )
            expect(result.isDisputed).toBe(true)
        })
    })
})
