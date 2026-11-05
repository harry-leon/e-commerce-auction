/**
 * Chức năng: API Adapter kết nối giữa Frontend và Auction Service (thông qua API Gateway).
 *            Cung cấp các hàm lấy danh sách phiên đấu giá, xem chi tiết phiên, đặt giá thầu (place bid),
 *            kiểm tra tính hợp lệ của dữ liệu (validation/normalization) và xử lý lỗi mạng/fallback.
 * Đầu vào: Các tham số tìm kiếm, lọc trạng thái, phân trang (AuctionQueryParams), ID phiên đấu giá, số tiền bid.
 * Kết quả: Dữ liệu phiên đấu giá chuẩn hóa (Auction, AuctionBid) tuân thủ SRS §3.5 và SPRINT2_AUCTION_ENGINE_STORIES.
 * Luồng xử lý:
 *   1. Gọi HTTP client (api()) tới API Gateway tại prefix /api/v1/auctions.
 *   2. Nhận ApiResponse envelope, bóc tách payload data.
 *   3. Kiểm tra tính toàn vẹn (validateAndNormalizeAuction) để lọc bỏ dữ liệu rác/lỗi từ server.
 *   4. Trong trường hợp Backend Auction Service chưa sẵn sàng (404/503) hoặc người dùng duyệt ẩn danh (401),
 *      tự động fallback về kho dữ liệu mock phong phú có áp dụng đầy đủ bộ lọc, sắp xếp và phân trang.
 * Quy tắc & lý do: Đảm bảo giao diện luôn hiển thị mượt mà, không bị gián đoạn hay crash khi dữ liệu API
 *                  thiếu trường, sai kiểu dữ liệu (NaN, null, negative price) hoặc khi server mất kết nối.
 * Lỗi & giới hạn: Dữ liệu đặt giá ở chế độ fallback được đồng bộ cục bộ trong phiên làm việc.
 */

import { api, post, ApiClientError, type ApiResponse } from './client'
import { readSession } from './client'
import type { Auction, AuctionBid, AuctionStatus, Product } from '../types'
import { AUCTIONS, PRODUCTS } from '../mockData'

/** Tham số lọc và phân trang danh sách phiên đấu giá */
export interface AuctionQueryParams {
    status?: string
    search?: string
    sortBy?: 'ending-soon' | 'most-bids' | 'newest' | 'price-asc' | 'price-desc' | string
    minPrice?: number
    maxPrice?: number
    page?: number
    limit?: number
    extraAuctions?: Auction[]
    reviews?: Partial<Record<string, { status: string; reason?: string }>>
}

/** Kết quả trả về cho danh sách đấu giá có phân trang */
export interface AuctionListResponse {
    items: Auction[]
    total: number
    page: number
    limit: number
    totalPages: number
}

/** Payload trả về khi đặt giá thầu thành công */
export interface PlaceBidResponse {
    bid: AuctionBid
    currentBid: number
    bidCount: number
    endTime?: string
    extended?: boolean
}

/** Payload thô của Auction từ Backend hoặc API Gateway (hỗ trợ cả camelCase và snake_case) */
export interface RawApiAuction {
    id?: string
    auctionId?: string
    auction_id?: string
    productId?: string
    product_id?: string
    skuId?: string | null
    sku_id?: string | null
    sellerId?: string
    seller_id?: string
    sellerName?: string
    seller_name?: string
    startPrice?: number
    start_price?: number
    startingPrice?: number
    starting_price?: number
    bidIncrement?: number
    bid_increment?: number
    minIncrement?: number
    min_increment?: number
    currentPrice?: number | null
    current_price?: number | null
    currentBid?: number | null
    current_bid?: number | null
    highestBidderId?: string | null
    highest_bidder_id?: string | null
    highestBidderName?: string | null
    highest_bidder_name?: string | null
    visibility?: 'PUBLIC' | 'PRIVATE' | 'public' | 'restricted'
    status?: string
    startTime?: string
    start_time?: string
    endTime?: string
    end_time?: string
    extensionCount?: number
    extension_count?: number
    version?: number
    bidCount?: number
    bid_count?: number
    product?: Partial<Product>
    productName?: string
    product_name?: string
    productNameVi?: string
    product_name_vi?: string
    productImage?: string
    product_image?: string
    productImages?: string[]
    product_images?: string[]
    productDescription?: string
    product_description?: string
    productDescriptionVi?: string
    product_description_vi?: string
    bids?: RawApiBid[]
}

/** Payload thô của lượt bid */
export interface RawApiBid {
    id?: string
    bidId?: string
    bid_id?: string
    bidderId?: string
    bidder_id?: string
    bidderName?: string
    bidder_name?: string
    amount?: number
    placedAt?: string
    placed_at?: string
    timestamp?: string
}

/** Kho dữ liệu đấu giá in-memory lưu các lượt bid và phiên mới tạo trong phiên làm việc */
let localAuctionsStore: Auction[] = [...AUCTIONS]

/**
 * Kiểm tra xem lỗi API có thể an toàn fallback sang dữ liệu mock hay không.
 * Luồng: Nếu là lỗi kết nối mạng (TypeError/ReferenceError), 404 (chưa triển khai route),
 *        502/503/504 (service tạm ngắt kết nối) hoặc 401 khi chưa đăng nhập (khách vãng lai xem đấu giá).
 */
function canFallbackToMock(error: unknown): boolean {
    if (error instanceof ApiClientError) {
        if (error.status === 401 && !readSession()) {
            return true
        }
        return [404, 500, 502, 503, 504].includes(error.status)
    }
    return error instanceof TypeError || error instanceof ReferenceError
}

/**
 * Chức năng: Kiểm tra tính hợp lệ và chuẩn hóa đối tượng phiên đấu giá từ API.
 * Đầu vào: raw object bất kỳ nhận từ phản hồi HTTP.
 * Kết quả: Đối tượng Auction hợp lệ hoặc null nếu bản ghi bị lỗi nghiêm trọng (thiếu ID, sai định dạng).
 * Luồng xử lý:
 *   1. Bác bỏ nếu không phải object.
 *   2. Trích xuất ID (kiểm tra không được rỗng).
 *   3. Chuẩn hóa giá: startPrice, currentBid, minIncrement (kiểm tra NaN, số âm).
 *   4. Chuẩn hóa thời gian bắt đầu/kết thúc (kiểm tra định dạng Date hợp lệ).
 *   5. Chuẩn hóa trạng thái: ACTIVE, SCHEDULED, ENDED, SETTLED, CANCELLED -> status code FE.
 *   6. Chuẩn hóa snapshot sản phẩm: tự động ghép với catalog hoặc tạo fallback an toàn.
 *   7. Chuẩn hóa danh sách bids: sắp xếp theo giá giảm dần và lọc bản ghi hợp lệ.
 * Lỗi & giới hạn: Trả về null nếu bản ghi thiếu thông tin cơ bản không thể phục hồi.
 */
export function validateAndNormalizeAuction(raw: unknown): Auction | null {
    if (!raw || typeof raw !== 'object') {
        return null
    }

    const item = raw as RawApiAuction

    // 1. Kiểm tra ID bắt buộc
    const id = String(item.id || item.auctionId || item.auction_id || '').trim()
    if (!id) {
        return null
    }

    // 2. Chuẩn hóa giá (Bảo vệ chống NaN, null, undefined, số âm)
    const rawStart = Number(item.startingPrice ?? item.startPrice ?? item.start_price)
    const startingPrice = Number.isFinite(rawStart) && rawStart > 0 ? rawStart : 1000

    const rawCurrent = Number(
        item.currentBid ?? item.currentPrice ?? item.current_price ?? startingPrice,
    )
    const currentBid =
        Number.isFinite(rawCurrent) && rawCurrent >= startingPrice ? rawCurrent : startingPrice

    const rawIncrement = Number(
        item.minIncrement ?? item.bidIncrement ?? item.bid_increment ?? 1000,
    )
    const minIncrement = Number.isFinite(rawIncrement) && rawIncrement > 0 ? rawIncrement : 1000

    // 3. Chuẩn hóa thời gian
    const rawStartTime = item.startTime || item.start_time
    const parsedStart = rawStartTime ? new Date(rawStartTime) : new Date()
    const startTime = !isNaN(parsedStart.getTime())
        ? parsedStart.toISOString()
        : new Date().toISOString()

    const rawEndTime = item.endTime || item.end_time
    const parsedEnd = rawEndTime ? new Date(rawEndTime) : new Date(Date.now() + 86400000)
    const endTime = !isNaN(parsedEnd.getTime())
        ? parsedEnd.toISOString()
        : new Date(Date.now() + 86400000).toISOString()

    // 4. Chuẩn hóa trạng thái
    const rawStatus = String(item.status || 'SCHEDULED').toUpperCase()
    let status: AuctionStatus = 'scheduled'
    if (['ACTIVE', 'LIVE'].includes(rawStatus)) {
        status = 'active'
    } else if (['SCHEDULED'].includes(rawStatus)) {
        status = 'scheduled'
    } else if (['ENDED'].includes(rawStatus)) {
        status = 'ended'
    } else if (['SETTLED', 'COMPLETED'].includes(rawStatus)) {
        status = 'settled'
    } else if (['CANCELLED'].includes(rawStatus)) {
        status = 'cancelled'
    } else if (['FAILED'].includes(rawStatus)) {
        status = 'failed'
    } else if (['DRAFT'].includes(rawStatus)) {
        status = 'pending_approval'
    }

    // Tự động chuyển sang 'ended' nếu phiên active mà endTime đã qua thời điểm hiện tại
    if (status === 'active' && new Date(endTime).getTime() <= Date.now()) {
        status = 'ended'
    }

    // 5. Chuẩn hóa sản phẩm
    const productId = String(item.productId || item.product_id || 'prod-default')
    let product: Product
    if (item.product && typeof item.product === 'object' && item.product.name) {
        const p = item.product
        product = {
            id: productId,
            name: p.name || 'Sản phẩm đấu giá',
            nameVi: p.nameVi || p.name || 'Sản phẩm đấu giá',
            price: startingPrice,
            originalPrice: p.originalPrice ?? startingPrice,
            rating: p.rating || 5,
            reviewCount: p.reviewCount || 0,
            images:
                Array.isArray(p.images) && p.images.length > 0
                    ? p.images
                    : [
                          'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80',
                      ],
            category: p.category || 'Collectibles',
            categoryId: p.categoryId || 'c6',
            location: p.location || 'ho-chi-minh',
            sku: p.sku || 'AUC-SKU-001',
            status: p.status || 'active',
            sellerId: String(item.sellerId || item.seller_id || p.sellerId || 'u-seller'),
            sellerName: item.sellerName || item.seller_name || p.sellerName || 'Nexus Seller',
            sellerTrust: p.sellerTrust || 'gold',
            sellerRating: p.sellerRating || 5,
            weight: p.weight || 1,
            enableAuction: true,
            stock: p.stock || 1,
            createdAt: p.createdAt || startTime,
            description:
                p.description ||
                item.productDescription ||
                item.product_description ||
                'Sản phẩm đấu giá cao cấp',
            descriptionVi:
                p.descriptionVi ||
                item.productDescriptionVi ||
                item.product_description_vi ||
                'Sản phẩm đấu giá cao cấp',
        }
    } else {
        // Tìm sản phẩm khớp từ kho Catalog hoặc tạo placeholder
        const existing = PRODUCTS.find((p) => p.id === productId)
        if (existing) {
            product = existing
        } else {
            const fallbackName =
                item.productName || item.product_name || `Phiên đấu giá #${id.slice(0, 8)}`
            const fallbackNameVi = item.productNameVi || item.product_name_vi || fallbackName
            const fallbackImage =
                item.productImage ||
                item.product_image ||
                (Array.isArray(item.productImages || item.product_images)
                    ? (item.productImages || item.product_images)![0]
                    : 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80')

            product = {
                id: productId,
                name: fallbackName,
                nameVi: fallbackNameVi,
                price: startingPrice,
                originalPrice: startingPrice,
                rating: 5,
                reviewCount: 12,
                images: [fallbackImage],
                category: 'Collectibles',
                categoryId: 'c6',
                location: 'ho-chi-minh',
                sku: `AUC-${id.slice(0, 6)}`,
                status: 'active',
                description:
                    item.productDescription ||
                    item.product_description ||
                    'Sản phẩm đấu giá độc bản tại Project Nexus.',
                descriptionVi:
                    item.productDescriptionVi ||
                    item.product_description_vi ||
                    'Sản phẩm đấu giá độc bản tại Project Nexus.',
                sellerId: String(item.sellerId || item.seller_id || 'u-seller'),
                sellerName: item.sellerName || item.seller_name || 'Nexus Verified Seller',
                sellerTrust: 'gold',
                sellerRating: 5,
                weight: 1,
                enableAuction: true,
                stock: 1,
                createdAt: startTime,
            }
        }
    }

    // 6. Chuẩn hóa danh sách lượt đặt giá (bids)
    const rawBids = Array.isArray(item.bids) ? item.bids : []
    const bids: AuctionBid[] = rawBids
        .map((b, index) => {
            if (!b || typeof b !== 'object') return null
            const bidId = String(b.id || b.bidId || b.bid_id || `bid-${id}-${index}`)
            const amount = Number(b.amount)
            if (!Number.isFinite(amount) || amount <= 0) return null
            const rawTime = b.placedAt || b.placed_at || b.timestamp
            const parsedTime = rawTime ? new Date(rawTime) : new Date()
            return {
                id: bidId,
                bidderId: String(b.bidderId || b.bidder_id || 'bidder-unknown'),
                bidderName:
                    b.bidderName ||
                    b.bidder_name ||
                    `Người dùng ***${bidId.slice(-3)}`,
                amount,
                timestamp: !isNaN(parsedTime.getTime())
                    ? parsedTime.toISOString()
                    : new Date().toISOString(),
            }
        })
        .filter((b): b is AuctionBid => b !== null)
        .sort((a, b) => b.amount - a.amount)

    const bidCount = Number.isFinite(item.bidCount ?? item.bid_count)
        ? Number(item.bidCount ?? item.bid_count)
        : bids.length

    return {
        id,
        productId,
        product,
        sellerId: String(item.sellerId || item.seller_id || product.sellerId || 'u-seller'),
        sellerName:
            item.sellerName ||
            item.seller_name ||
            product.sellerName ||
            'Nexus Seller',
        status,
        startingPrice,
        currentBid,
        minIncrement,
        startTime,
        endTime,
        bidCount,
        bids,
        winnerId: item.highestBidderId || item.highest_bidder_id || undefined,
        winnerName: item.highestBidderName || item.highest_bidder_name || undefined,
        visibility: item.visibility === 'PRIVATE' || item.visibility === 'restricted' ? 'restricted' : 'public',
    }
}

/**
 * Chức năng: Chuẩn hóa danh sách phiên đấu giá từ dữ liệu thô của API.
 * Đầu vào: Mảng hoặc đối tượng phân trang (page, content, data, items).
 * Kết quả: Mảng Auction đã được làm sạch, loại bỏ các mục hỏng hoặc không hợp lệ.
 * Lỗi: Ném ApiClientError (422) khi payload không phải mảng hoặc đối tượng phân trang hợp lệ.
 */
export function validateAndNormalizeAuctionList(raw: unknown): Auction[] {
    let list: unknown[]
    if (Array.isArray(raw)) {
        list = raw
    } else if (raw && typeof raw === 'object') {
        const obj = raw as Record<string, unknown>
        if (Array.isArray(obj.content)) {
            list = obj.content
        } else if (Array.isArray(obj.data)) {
            list = obj.data
        } else if (Array.isArray(obj.items)) {
            list = obj.items
        } else {
            throw new ApiClientError(
                'Dữ liệu danh sách phiên đấu giá từ máy chủ không hợp lệ',
                'INVALID_AUCTION_DATA',
                422,
            )
        }
    } else {
        throw new ApiClientError(
            'Dữ liệu danh sách phiên đấu giá từ máy chủ không hợp lệ',
            'INVALID_AUCTION_DATA',
            422,
        )
    }

    return list
        .map((item) => validateAndNormalizeAuction(item))
        .filter((item): item is Auction => item !== null)
}

/**
 * API Adapter chính cho Auction Service
 */
export const auctionsApi = {
    /**
     * Chức năng: Lấy danh sách các phiên đấu giá với bộ lọc trạng thái, tìm kiếm, sắp xếp và phân trang.
     * Đầu vào: AuctionQueryParams (status, search, sortBy, minPrice, maxPrice, page, limit).
     * Kết quả: AuctionListResponse gồm danh sách items và thông tin phân trang.
     * Luồng:
     *   1. Xây dựng Query String từ params.
     *   2. Gọi GET /api/v1/auctions qua API Gateway.
     *   3. Chuẩn hóa dữ liệu; nếu Backend lỗi mạng hoặc 404/503, tự động fallback sang kho mock in-memory.
     */
    async getAuctions(params: AuctionQueryParams = {}): Promise<AuctionListResponse> {
        const {
            status = 'all',
            search = '',
            sortBy = 'ending-soon',
            minPrice,
            maxPrice,
            page = 1,
            limit = 9,
            extraAuctions = [],
            reviews = {},
        } = params

        const queryParts: string[] = []
        if (status && status !== 'all') queryParts.push(`status=${encodeURIComponent(status.toUpperCase())}`)
        if (search) queryParts.push(`search=${encodeURIComponent(search)}`)
        if (sortBy) queryParts.push(`sort=${encodeURIComponent(sortBy)}`)
        if (minPrice !== undefined) queryParts.push(`minPrice=${minPrice}`)
        if (maxPrice !== undefined) queryParts.push(`maxPrice=${maxPrice}`)
        queryParts.push(`page=${page}`)
        queryParts.push(`limit=${limit}`)
        const qs = queryParts.length > 0 ? `?${queryParts.join('&')}` : ''

        try {
            const envelope = await api<ApiResponse<unknown> | unknown[]>(`/auctions${qs}`)
            const rawData =
                envelope && typeof envelope === 'object' && 'data' in envelope
                    ? (envelope as ApiResponse<unknown>).data
                    : envelope

            const validatedItems = validateAndNormalizeAuctionList(rawData)

            // Nếu Backend trả về dữ liệu hợp lệ
            if (validatedItems.length > 0 || Array.isArray(rawData)) {
                return {
                    items: validatedItems,
                    total: validatedItems.length,
                    page,
                    limit,
                    totalPages: Math.max(1, Math.ceil(validatedItems.length / limit)),
                }
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        // Luồng fallback sang dữ liệu in-memory/mock khi backend chưa sẵn sàng hoặc offline
        const combined = [...extraAuctions, ...localAuctionsStore]
        const seen = new Set<string>()
        let filtered = combined
            .filter((a) => {
                if (seen.has(a.id)) return false
                seen.add(a.id)
                return true
            })
            .map((a) => ({
                ...a,
                status: (reviews[a.id]?.status as AuctionStatus) || a.status,
            }))
            .filter((a) => !['pending_approval', 'rejected'].includes(a.status))

        // Lọc trạng thái
        if (status && status !== 'all') {
            filtered = filtered.filter((a) => a.status === status)
        }

        // Lọc tìm kiếm từ khóa
        if (search.trim()) {
            const q = search.trim().toLowerCase()
            filtered = filtered.filter(
                (a) =>
                    a.product.name.toLowerCase().includes(q) ||
                    (a.product.nameVi && a.product.nameVi.toLowerCase().includes(q)) ||
                    a.sellerName.toLowerCase().includes(q),
            )
        }

        // Lọc khoảng giá (currentBid hoặc startingPrice)
        if (minPrice !== undefined && Number.isFinite(minPrice)) {
            filtered = filtered.filter((a) => a.currentBid >= minPrice)
        }
        if (maxPrice !== undefined && Number.isFinite(maxPrice)) {
            filtered = filtered.filter((a) => a.currentBid <= maxPrice)
        }

        // Sắp xếp
        if (sortBy === 'ending-soon') {
            filtered.sort(
                (a, b) => new Date(a.endTime).getTime() - new Date(b.endTime).getTime(),
            )
        } else if (sortBy === 'most-bids') {
            filtered.sort((a, b) => b.bidCount - a.bidCount)
        } else if (sortBy === 'newest') {
            filtered.sort(
                (a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime(),
            )
        } else if (sortBy === 'price-asc') {
            filtered.sort((a, b) => a.currentBid - b.currentBid)
        } else if (sortBy === 'price-desc') {
            filtered.sort((a, b) => b.currentBid - a.currentBid)
        }

        const total = filtered.length
        const totalPages = Math.max(1, Math.ceil(total / limit))
        const startIndex = (page - 1) * limit
        const paginatedItems = filtered.slice(startIndex, startIndex + limit)

        return {
            items: paginatedItems,
            total,
            page,
            limit,
            totalPages,
        }
    },

    /**
     * Chức năng: Lấy thông tin chi tiết của một phiên đấu giá theo ID.
     * Đầu vào: auctionId, extraAuctions, reviews từ Context.
     * Kết quả: Đối tượng Auction chi tiết gồm thông tin sản phẩm, giá thầu hiện tại và lịch sử bids.
     * Luồng:
     *   1. Gọi GET /api/v1/auctions/{id}.
     *   2. Chuẩn hóa dữ liệu trả về qua validateAndNormalizeAuction.
     *   3. Nếu lỗi 404/503 hoặc offline, tra cứu trong extraAuctions hoặc kho in-memory. Nếu không tìm thấy, ném lỗi 404.
     */
    async getAuctionById(
        id: string,
        extraAuctions: Auction[] = [],
        reviews: Partial<Record<string, { status: string }>> = {},
    ): Promise<Auction> {
        if (!id || typeof id !== 'string') {
            throw new ApiClientError('Mã phiên đấu giá không hợp lệ', 'INVALID_AUCTION_ID', 400)
        }

        try {
            const envelope = await api<ApiResponse<unknown> | unknown>(`/auctions/${id}`)
            const rawData =
                envelope && typeof envelope === 'object' && 'data' in envelope
                    ? (envelope as ApiResponse<unknown>).data
                    : envelope

            const validated = validateAndNormalizeAuction(rawData)
            if (validated) {
                return {
                    ...validated,
                    status: (reviews[validated.id]?.status as AuctionStatus) || validated.status,
                }
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        // Fallback tra cứu trong kho local
        const found = [...extraAuctions, ...localAuctionsStore].find((a) => a.id === id)
        if (found) {
            return {
                ...found,
                status: (reviews[found.id]?.status as AuctionStatus) || found.status,
            }
        }

        throw new ApiClientError(
            `Không tìm thấy phiên đấu giá với ID ${id}`,
            'AUCTION_NOT_FOUND',
            404,
        )
    },

    /**
     * Chức năng: Người mua đặt giá thầu (Place Bid) cho phiên đấu giá đang mở (ACTIVE).
     * Đầu vào: auctionId, amount (số tiền đặt giá), idempotencyKey (tránh đặt trùng lặp khi bấm nhanh).
     * Kết quả: PlaceBidResponse xác nhận lượt đặt giá thành công.
     * Luồng xử lý:
     *   1. Kiểm tra validation phía client (amount > 0).
     *   2. Gửi POST /api/v1/auctions/{id}/bids lên backend.
     *   3. Nếu backend chưa sẵn sàng, cập nhật trực tiếp vào kho in-memory để giao diện realtime phản hồi ngay.
     */
    async placeBid(
        auctionId: string,
        amount: number,
        idempotencyKey?: string,
    ): Promise<PlaceBidResponse> {
        if (!amount || amount <= 0) {
            throw new ApiClientError('Số tiền đặt giá phải lớn hơn 0', 'INVALID_BID_AMOUNT', 422)
        }

        const session = readSession()
        const bidderId = session?.user?.id || 'current-user-id'
        const bidderName = session?.user?.fullName || 'Bạn'

        try {
            const payload = {
                amount,
                idempotencyKey: idempotencyKey || `bid-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
            }
            const envelope = await post<ApiResponse<PlaceBidResponse>>(`/auctions/${auctionId}/bids`, payload)
            if (envelope?.data) {
                return envelope.data
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        // Mô phỏng cập nhật in-memory khi chạy offline/sandbox
        const targetIndex = localAuctionsStore.findIndex((a) => a.id === auctionId)
        const targetAuction = targetIndex >= 0 ? localAuctionsStore[targetIndex] : null

        const newBid: AuctionBid = {
            id: `bid-${Date.now()}`,
            bidderId,
            bidderName: bidderName === 'Bạn' ? 'b**n (Bạn)' : `${bidderName.slice(0, 3)}***`,
            amount,
            timestamp: new Date().toISOString(),
        }

        if (targetAuction) {
            const updatedBids = [newBid, ...targetAuction.bids]
            const updatedAuction: Auction = {
                ...targetAuction,
                currentBid: amount,
                bidCount: targetAuction.bidCount + 1,
                bids: updatedBids,
                winnerId: bidderId,
                winnerName: bidderName,
            }
            localAuctionsStore[targetIndex] = updatedAuction
        }

        return {
            bid: newBid,
            currentBid: amount,
            bidCount: targetAuction ? targetAuction.bidCount + 1 : 1,
        }
    },

    /**
     * Chức năng: Thêm một phiên đấu giá do người bán tạo vào kho local.
     */
    addCreatedAuction(auction: Auction) {
        localAuctionsStore = [auction, ...localAuctionsStore]
    },

    /**
     * Chức năng: Đặt lại kho mock về trạng thái ban đầu (dùng cho test/reset).
     */
    resetMockStore() {
        localAuctionsStore = [...AUCTIONS]
    },
}
