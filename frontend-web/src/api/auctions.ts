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
 *   4. Khi API lỗi tạm thời, chỉ giữ bản ghi mới do người dùng tạo cục bộ; không biến fixture thành đấu giá thật.
 * Quy tắc & lý do: Đảm bảo giao diện luôn hiển thị mượt mà, không bị gián đoạn hay crash khi dữ liệu API
 *                  thiếu trường, sai kiểu dữ liệu (NaN, null, negative price) hoặc khi server mất kết nối.
 * Lỗi & giới hạn: Dữ liệu phiên mới tạo có thể chỉ nằm cục bộ cho tới khi API đồng bộ thành công.
 */

import { api, post, ApiClientError, type ApiProduct, type ApiResponse, type Envelope } from './client'
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
    sellerId?: string
    /** Chỉ dùng để đối chiếu bản ghi local mới tạo khi API chưa cung cấp sellerId; không gửi lên backend. */
    sellerName?: string
    /** Ẩn các phiên fixture AUCTIONS chỉ ở portal Seller/Admin; không gửi tham số này lên API. */
    excludeDemoData?: boolean
    includePending?: boolean
    page?: number
    limit?: number
    extraAuctions?: Auction[]
    reviews?: Partial<Record<string, { status: string; reason?: string }>>
}

/** DTO yêu cầu khởi tạo phiên đấu giá mới từ Người bán */
export interface CreateAuctionPayload {
    productId: string
    skuId?: string | null
    startingPrice: number
    minIncrement: number
    startTime: string
    endTime: string
    visibility?: 'public' | 'restricted'
}

/** DTO yêu cầu cập nhật phiên đấu giá trước giờ mở */
export interface UpdateAuctionPayload {
    productId?: string
    skuId?: string | null
    startingPrice?: number
    minIncrement?: number
    startTime?: string
    endTime?: string
    visibility?: 'public' | 'restricted'
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

/**
 * Kết quả settlement chính thức; trang checkout dùng các mốc này thay vì giá/deadline fixture.
 * - SUCCESS: phiên kết thúc có người thắng hợp lệ, đơn hàng sẽ được handover.
 * - FAILED: phiên không có người thắng (không ai đấu giá hoặc không đạt giá khởi điểm).
 * - DEFAULTED: winner đã thắng nhưng không thanh toán đúng hạn → AuctionTimeoutSideEffectWorker
 *   đổi từ SUCCESS sang DEFAULTED, kho được nhả, winner bị phạt điểm uy tín.
 *   Trường này được BE MR KH-S2-3 (Khang) bổ sung qua endpoint internal/settlement/defaulted.
 */
export interface AuctionSettlement {
    id: string
    auctionId: string
    winnerId: string | null
    finalPrice: number | null
    result: 'SUCCESS' | 'FAILED' | 'DEFAULTED'
    orderId: string | null
    paymentDeadline: string | null
    settledAt: string
}

/** Đọc settlement thật qua Gateway; không fallback mock vì winner và deadline quyết định quyền thanh toán. */
export async function getAuctionSettlement(auctionId: string): Promise<AuctionSettlement> {
    const response = await api<ApiResponse<AuctionSettlement>>(
        `/auctions/${encodeURIComponent(auctionId)}/settlement`,
    )
    const settlement = response?.data
    if (
        !response?.success ||
        !settlement ||
        settlement.auctionId !== auctionId ||
        !settlement.id ||
        !settlement.result
    ) {
        throw new ApiClientError('Settlement response is invalid', 'INVALID_SETTLEMENT_RESPONSE', 502)
    }
    return settlement
}

export interface WinnerAuctionDetails {
    auctionId: string
    productId: string
    productName: string
    productImage?: string
}

/** Tải tên và ảnh sản phẩm từ Auction/Catalog thật để phần đơn thắng không hiển thị thông tin fixture. */
export async function getWinnerAuctionDetails(auctionId: string): Promise<WinnerAuctionDetails> {
    const auctionResponse = await api<ApiResponse<{ id: string; productId: string }>>(
        `/auctions/${encodeURIComponent(auctionId)}`,
    )
    const auction = auctionResponse?.data
    if (!auctionResponse?.success || !auction || auction.id !== auctionId || !auction.productId) {
        throw new ApiClientError('Auction response is invalid', 'INVALID_AUCTION_RESPONSE', 502)
    }

    const productResponse = await api<Envelope<ApiProduct>>(
        `/products/${encodeURIComponent(auction.productId)}`,
    )
    const product = productResponse?.data
    if (!productResponse?.success || !product || product.id !== auction.productId || !product.name) {
        throw new ApiClientError('Catalog response is invalid', 'INVALID_CATALOG_RESPONSE', 502)
    }

    return {
        auctionId,
        productId: product.id,
        productName: product.name,
        productImage: product.images?.[0]?.url,
    }
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
    sellerFullName?: string
    seller_full_name?: string
    sellerAccountName?: string
    seller_account_name?: string
    sellerEmail?: string
    seller_email?: string
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

/** Khóa lưu trữ danh sách phiên đấu giá trong localStorage để giữ vết dữ liệu khi reload/test */
const AUCTIONS_STORAGE_KEY = 'nexus.auction-portal-store.v2'
const LEGACY_AUCTIONS_STORAGE_KEY = 'nexus.mock-auctions-store'
const AUCTION_REVIEWS_STORAGE_KEY = 'nexus.auction-reviews-store'

/**
 * Chức năng: Cache ánh xạ productId → thông tin sản phẩm (tên, SKU, ảnh).
 * Mục đích: Khi Auction Service chỉ trả productId (UUID) mà không kèm productName/sku,
 *           ta tra cứu cache này để hiển thị đúng tên/SKU thật thay vì fallback.
 * Luồng: Seller tạo auction → gọi cacheAuctionProduct → cache được lưu vào localStorage.
 *        validateAndNormalizeAuction → tra cứu cache trước khi tạo placeholder.
 * Lỗi & giới hạn: Cache chỉ có hiệu lực trong cùng browser profile; xóa cache thì phiên
 *                 mới cần reload lại danh sách sản phẩm mới có tên đúng.
 */
const AUCTION_PRODUCTS_CACHE_KEY = 'nexus.auction-products-cache.v1'

interface AuctionProductCacheEntry {
    name: string
    nameVi?: string
    sku?: string
    images?: string[]
}

/** Nạp cache ánh xạ productId → metadata sản phẩm từ localStorage */
function loadAuctionProductCache(): Map<string, AuctionProductCacheEntry> {
    try {
        const raw = localStorage.getItem(AUCTION_PRODUCTS_CACHE_KEY)
        const parsed = raw ? JSON.parse(raw) : null
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
            return new Map(Object.entries(parsed))
        }
    } catch {}
    return new Map()
}

/** Lưu cache ánh xạ productId → metadata sản phẩm vào localStorage */
function persistAuctionProductCache(cache: Map<string, AuctionProductCacheEntry>) {
    try {
        const obj = Object.fromEntries(cache.entries())
        localStorage.setItem(AUCTION_PRODUCTS_CACHE_KEY, JSON.stringify(obj))
    } catch {}
}

/** Bộ nhớ in-memory + localStorage; được khởi tạo một lần khi module load */
let auctionProductCache: Map<string, AuctionProductCacheEntry> = loadAuctionProductCache()

/**
 * Chức năng: Lưu thông tin sản phẩm vào cache để auction hiển thị đúng tên/SKU.
 * Đầu vào: productId (UUID từ Catalog Service) và metadata { name, nameVi, sku, images }.
 * Luồng: Gọi sau khi Seller chọn sản phẩm để tạo auction; hoặc khi load danh sách sản phẩm.
 * Quy tắc: Chỉ ghi đè nếu entry mới có nhiều thông tin hơn entry cũ.
 */
export function cacheAuctionProduct(
    productId: string,
    entry: AuctionProductCacheEntry,
) {
    if (!productId || !entry.name) return
    const existing = auctionProductCache.get(productId)
    // Ưu tiên giữ bản có đầy đủ thông tin hơn
    if (!existing || (entry.sku && !existing.sku)) {
        auctionProductCache.set(productId, entry)
        persistAuctionProductCache(auctionProductCache)
    }
}

/** ID dạng auc-{timestamp} chỉ được sinh khi API lỗi; backend dùng UUID nên không được coi các bản ghi này là phiên thật. */
function isLocalFallbackAuction(auction: Pick<Auction, 'id'>): boolean {
    return /^auc-\d+$/.test(auction.id)
}

type StoredAuctionReview = { status: 'scheduled' | 'rejected'; reason?: string; reviewedAt: string }

/** Khôi phục quyết định Admin để API list trả DRAFT cũ không làm phiên đã duyệt quay lại hàng chờ sau reload. */
function loadStoredAuctionReviews(): Record<string, StoredAuctionReview> {
    try {
        const raw = localStorage.getItem(AUCTION_REVIEWS_STORAGE_KEY)
        const parsed = raw ? JSON.parse(raw) : null
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
    } catch {
        return {}
    }
}

function persistAuctionReviews(reviews: Record<string, StoredAuctionReview>) {
    try {
        localStorage.setItem(AUCTION_REVIEWS_STORAGE_KEY, JSON.stringify(reviews))
    } catch {
        // API vẫn có thể hoạt động nếu trình duyệt chặn localStorage.
    }
}

/**
 * Chức năng: Tải mock do người dùng tạo từ localStorage; loại fixture cũ để chúng không quay lại sau reload.
 * Đầu vào: Đọc từ localStorage qua khóa AUCTIONS_STORAGE_KEY.
 * Kết quả: Mảng Auction danh sách phiên đấu giá.
 */
function loadStoredAuctions(): Auction[] {
    try {
        // Đọc kho mới trước; nếu chỉ còn kho cũ thì di trú phiên người dùng tạo và bỏ fixture mặc định.
        const raw = localStorage.getItem(AUCTIONS_STORAGE_KEY) || localStorage.getItem(LEGACY_AUCTIONS_STORAGE_KEY)
        if (raw) {
            const parsed = JSON.parse(raw)
            if (Array.isArray(parsed)) {
                const demoIds = new Set(AUCTIONS.map((auction) => auction.id))
                const storedAuctions = parsed.filter((auction): auction is Auction =>
                    Boolean(
                        auction && typeof auction.id === 'string' &&
                        !demoIds.has(auction.id) && !isLocalFallbackAuction(auction),
                    ),
                )
                localStorage.setItem(AUCTIONS_STORAGE_KEY, JSON.stringify(storedAuctions))
                localStorage.removeItem(LEGACY_AUCTIONS_STORAGE_KEY)
                return storedAuctions
            }
        }
    } catch {
        // Bỏ qua lỗi parse và dùng dữ liệu mặc định
    }
    return []
}

/**
 * Chức năng: Lưu trữ danh sách phiên đấu giá vào localStorage.
 * Đầu vào: Danh sách auctions cần lưu.
 * Kết quả: Đồng bộ trạng thái vào localStorage.
 */
function persistStoredAuctions(auctions: Auction[]) {
    try {
        localStorage.setItem(AUCTIONS_STORAGE_KEY, JSON.stringify(auctions))
    } catch {
        // Bỏ qua lỗi vượt hạn mức lưu trữ
    }
}

/** Kho dữ liệu đấu giá lưu các lượt bid và phiên mới tạo, tự động đồng bộ localStorage */
let localAuctionsStore: Auction[] = loadStoredAuctions()
let localAuctionReviews = loadStoredAuctionReviews()

/** Dùng trạng thái API trừ khi phiên vẫn DRAFT nhưng frontend đã lưu quyết định duyệt gần nhất. */
function resolveAuctionStatus(
    auctionId: string,
    apiStatus: AuctionStatus,
    reviews: Partial<Record<string, { status: string }>> = {},
): AuctionStatus {
    const currentReview = reviews[auctionId]?.status as AuctionStatus | undefined
    if (currentReview) return currentReview
    if (apiStatus === 'pending_approval' && localAuctionReviews[auctionId]) {
        return localAuctionReviews[auctionId].status
    }
    return apiStatus
}

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
    const err = error as Error | undefined
    return (
        error instanceof TypeError ||
        error instanceof ReferenceError ||
        err?.name === 'TypeError' ||
        err?.name === 'ReferenceError' ||
        (typeof err?.message === 'string' &&
            (err.message.includes('fetch') ||
                err.message.includes('URL') ||
                err.message.includes('network') ||
                err.message.includes('Failed to parse')))
    )
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
    } else if (['PAUSED', 'SUSPENDED'].includes(rawStatus)) {
        status = 'paused'
    } else if (['SCHEDULED'].includes(rawStatus)) {
        status = 'scheduled'
    } else if (['ENDED'].includes(rawStatus)) {
        status = 'ended'
    } else if (['SETTLED', 'COMPLETED'].includes(rawStatus)) {
        status = 'settled'
    } else if (['CANCELLED'].includes(rawStatus)) {
        status = 'cancelled'
    } else if (['REJECTED'].includes(rawStatus)) {
        status = 'rejected'
    } else if (['FAILED'].includes(rawStatus)) {
        status = 'failed'
    } else if (['DRAFT', 'PENDING', 'PENDING_APPROVAL'].includes(rawStatus)) {
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
        // Tìm sản phẩm khớp từ kho mock (test) hoặc tra cache productId → metadata sản phẩm thật
        const existing = PRODUCTS.find((p) => p.id === productId)
        if (existing) {
            product = existing
        } else {
            // Tra cache được lưu khi Seller chọn sản phẩm để tạo auction (cacheAuctionProduct)
            const cached = auctionProductCache.get(productId)

            const fallbackName =
                item.productName ||
                item.product_name ||
                cached?.name ||
                'Auction Product'
            const fallbackNameVi =
                item.productNameVi ||
                item.product_name_vi ||
                cached?.nameVi ||
                cached?.name ||
                'Sản phẩm đấu giá'
            const fallbackSku =
                item.product?.sku ||
                cached?.sku ||
                'N/A'
            const fallbackImages =
                cached?.images ||
                (Array.isArray(item.productImages || item.product_images)
                    ? (item.productImages || item.product_images)!
                    : null)
            const fallbackImage =
                item.productImage ||
                item.product_image ||
                (fallbackImages ? fallbackImages[0] : null) ||
                'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600&auto=format&fit=crop&q=80'

            product = {
                id: productId,
                name: fallbackName,
                nameVi: fallbackNameVi,
                price: startingPrice,
                originalPrice: startingPrice,
                rating: 5,
                reviewCount: 0,
                images: [fallbackImage],
                category: 'Collectibles',
                categoryId: 'c6',
                location: 'ho-chi-minh',
                // Dùng SKU thật từ cache; không tạo giả dạng AUC-{id} gây nhầm lẫn
                sku: fallbackSku,
                status: 'active',
                description:
                    item.productDescription ||
                    item.product_description ||
                    '',
                descriptionVi:
                    item.productDescriptionVi ||
                    item.product_description_vi ||
                    '',
                sellerId: String(item.sellerId || item.seller_id || 'u-seller'),
                sellerName: item.sellerName || item.seller_name || 'Nexus Seller',
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
        sellerAccountName:
            item.sellerFullName || item.seller_full_name || item.sellerAccountName || item.seller_account_name,
        sellerEmail: item.sellerEmail || item.seller_email,
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
     *   3. Chuẩn hóa dữ liệu; nếu Backend lỗi mạng hoặc 404/503, chỉ dùng bản ghi người dùng tạo được lưu cục bộ.
     */
    async getAuctions(params: AuctionQueryParams = {}): Promise<AuctionListResponse> {
        const {
            status = 'all',
            search = '',
            sortBy = 'ending-soon',
            minPrice,
            maxPrice,
            sellerId,
            sellerName,
            excludeDemoData = false,
            includePending = false,
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
        if (sellerId) queryParts.push(`sellerId=${encodeURIComponent(sellerId)}`)
        if (includePending) queryParts.push('includePending=true')
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
                // Giữ các phiên vừa tạo ở trình duyệt khi API list chưa phản ánh kịp; dữ liệu server thắng nếu trùng ID.
                const serverIds = new Set(validatedItems.map((auction) => auction.id))
                const seededIds = new Set(AUCTIONS.map((auction) => auction.id))
                const submittedIds = new Set(extraAuctions.map((auction) => auction.id))
                const matchesCurrentSeller = (auction: Auction) =>
                    auction.sellerId === sellerId ||
                    Boolean(sellerName && [auction.sellerName, auction.sellerAccountName]
                        .some((name) => name?.trim().toLowerCase() === sellerName.trim().toLowerCase()))
                const localCreated = [...extraAuctions, ...localAuctionsStore].filter((auction, index, source) => {
                    // Chỉ ẩn bản ghi offline khi khách Buyer vãng lai xem danh sách công khai; Seller và Admin vẫn theo dõi được phiên vừa tạo.
                    if (isLocalFallbackAuction(auction) && (!sellerId && !includePending) && !submittedIds.has(auction.id)) return false
                    if (excludeDemoData && seededIds.has(auction.id) && !submittedIds.has(auction.id)) return false
                    if ((seededIds.has(auction.id) && !submittedIds.has(auction.id)) || serverIds.has(auction.id)) return false
                    if (source.findIndex((candidate) => candidate.id === auction.id) !== index) return false
                    // Ưu tiên ID Seller; tên chỉ đối chiếu bản ghi local mới tạo bị thiếu/sai sellerId sau khi reload.
                    if (sellerId && !matchesCurrentSeller(auction) && !submittedIds.has(auction.id)) return false
                    const effectiveStatus = resolveAuctionStatus(auction.id, auction.status, reviews)
                    if (status !== 'all' && effectiveStatus !== status) return false
                    if (!sellerId && !includePending && ['pending_approval', 'rejected', 'paused'].includes(effectiveStatus)) {
                        return false
                    }
                    if (search.trim()) {
                        const query = search.trim().toLowerCase()
                        if (!auction.product.name.toLowerCase().includes(query) &&
                            !auction.product.nameVi.toLowerCase().includes(query) &&
                            !auction.sellerName.toLowerCase().includes(query)) return false
                    }
                    if (minPrice !== undefined && auction.currentBid < minPrice) return false
                    if (maxPrice !== undefined && auction.currentBid > maxPrice) return false
                    return true
                }).map((auction) => ({
                    ...auction,
                    sellerId: sellerId && (submittedIds.has(auction.id) || matchesCurrentSeller(auction))
                        ? sellerId
                        : auction.sellerId,
                    status: resolveAuctionStatus(auction.id, auction.status, reviews),
                }))
                const serverItems = validatedItems
                    .filter((auction) => !excludeDemoData || !seededIds.has(auction.id) || submittedIds.has(auction.id))
                    .map((auction) => {
                    const localCreatedAuction = [...extraAuctions, ...localAuctionsStore].find(
                        (candidate) => candidate.id === auction.id && !seededIds.has(candidate.id),
                    )
                    if (!localCreatedAuction || (sellerId && !matchesCurrentSeller(localCreatedAuction))) return auction

                    // API list có thể thiếu seller_id; dùng owner đã cache cho phiên này nếu khớp Seller hiện tại.
                    const resolvedSellerId = sellerId || localCreatedAuction.sellerId
                    const resolvedSellerName = localCreatedAuction.sellerName || auction.sellerName
                    return {
                        ...auction,
                        sellerId: resolvedSellerId,
                        sellerName: resolvedSellerName,
                        sellerAccountName: auction.sellerAccountName || localCreatedAuction.sellerAccountName,
                        sellerEmail: auction.sellerEmail || localCreatedAuction.sellerEmail,
                        status: resolveAuctionStatus(auction.id, auction.status, reviews),
                        product: { ...auction.product, sellerId: resolvedSellerId, sellerName: resolvedSellerName },
                    }
                    })
                const items = [...localCreated, ...serverItems].filter((auction) => {
                    const effectiveStatus = resolveAuctionStatus(auction.id, auction.status, reviews)
                    return (!status || status === 'all' || effectiveStatus === status) &&
                        (sellerId ? auction.sellerId === sellerId : includePending || !['pending_approval', 'rejected', 'paused'].includes(effectiveStatus))
                }).map((auction) => ({
                    ...auction,
                    status: resolveAuctionStatus(auction.id, auction.status, reviews),
                }))
                return {
                    items,
                    total: items.length,
                    page,
                    limit,
                    totalPages: Math.max(1, Math.ceil(items.length / limit)),
                }
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        // Khi API không khả dụng, chỉ giữ phiên do người dùng tạo trong browser; fixture không đại diện cho dữ liệu đấu giá thật.
        const combined = [...extraAuctions, ...localAuctionsStore]
        const seen = new Set<string>()
        const submittedIds = new Set(extraAuctions.map((auction) => auction.id))
        const seededIds = new Set(AUCTIONS.map((auction) => auction.id))
        let filtered = combined
            .filter((a) => {
                if (seededIds.has(a.id) && !submittedIds.has(a.id)) return false
                if (isLocalFallbackAuction(a) && (!sellerId && !includePending) && !submittedIds.has(a.id)) return false
                if (seen.has(a.id)) return false
                seen.add(a.id)
                return true
            })
            .map((a) => ({
                ...a,
                status: resolveAuctionStatus(a.id, a.status, reviews),
            }))

        // Phiên chờ duyệt, bị từ chối hoặc tạm dừng không xuất hiện trong danh sách Buyer.
        if (status && status !== 'all') {
            filtered = filtered.filter((a) => a.status === status)
        }
        if (!sellerId && !includePending) {
            filtered = filtered.filter((a) => !['pending_approval', 'rejected', 'paused'].includes(a.status))
        }

        // Lọc theo người bán nếu có yêu cầu (phía Seller Portal)
        if (sellerId) {
            filtered = filtered.filter((auction) =>
                auction.sellerId === sellerId ||
                (!seededIds.has(auction.id) && Boolean(sellerName &&
                    [auction.sellerName, auction.sellerAccountName]
                        .some((name) => name?.trim().toLowerCase() === sellerName.trim().toLowerCase()))),
            ).map((auction) => ({ ...auction, sellerId }))
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

    /** Tải lịch sử bid phân trang từ Auction Service và chuẩn hóa về AuctionBid cho các màn chi tiết. */
    async getBidHistory(auctionId: string, page = 1, limit = 100): Promise<AuctionBid[]> {
        const envelope = await api<ApiResponse<unknown> | unknown[]>(
            `/auctions/${encodeURIComponent(auctionId)}/bids?page=${page}&limit=${limit}&sortBy=placedAt&sortDir=DESC`,
        )
        const rawData = envelope && typeof envelope === 'object' && 'data' in envelope
            ? (envelope as ApiResponse<unknown>).data
            : envelope
        if (!Array.isArray(rawData)) return []
        return rawData.flatMap((value, index) => {
            if (!value || typeof value !== 'object') return []
            const bid = value as RawApiBid
            const id = String(bid.id || bid.bidId || bid.bid_id || `bid-${auctionId}-${index}`)
            const amount = Number(bid.amount)
            if (!Number.isFinite(amount) || amount <= 0) return []
            const placedAt = bid.placedAt || bid.placed_at || bid.timestamp
            const timestamp = placedAt && !Number.isNaN(new Date(placedAt).getTime())
                ? new Date(placedAt).toISOString()
                : new Date().toISOString()
            return [{
                id,
                bidderId: String(bid.bidderId || bid.bidder_id || 'bidder-unknown'),
                bidderName: bid.bidderName || bid.bidder_name || `User ***${id.slice(-3)}`,
                amount,
                timestamp,
            }]
        })
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
                // Endpoint lịch sử là nguồn chuẩn mới được bổ sung; giữ detail hoạt động nếu route bid tạm lỗi.
                const bids = await this.getBidHistory(id).catch(() => validated.bids)
                return {
                    ...validated,
                    bids,
                    status: resolveAuctionStatus(validated.id, validated.status, reviews),
                }
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        // Fallback tra cứu trong kho local
        const found = extraAuctions.find((auction) => auction.id === id) ||
            localAuctionsStore.find((auction) => auction.id === id && !isLocalFallbackAuction(auction))
        if (found) {
            return {
                ...found,
                    status: resolveAuctionStatus(found.id, found.status, reviews),
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

        let extended = false
        let newEndTime = targetAuction?.endTime
        if (targetAuction) {
            const timeLeftMs = new Date(targetAuction.endTime).getTime() - Date.now()
            // Anti-sniping rule (BR-AUC-09): Nếu đặt giá trong 5 phút cuối (hoặc <= 300s), gia hạn thêm 5 phút (tối đa 5 lần)
            const currentExtensions = targetAuction.extensionCount || 0
            if (timeLeftMs > 0 && timeLeftMs <= 5 * 60 * 1000 && currentExtensions < 5) {
                extended = true
                newEndTime = new Date(new Date(targetAuction.endTime).getTime() + 5 * 60 * 1000).toISOString()
            }
            const updatedBids = [newBid, ...targetAuction.bids]
            const updatedAuction: Auction = {
                ...targetAuction,
                currentBid: amount,
                bidCount: targetAuction.bidCount + 1,
                bids: updatedBids,
                winnerId: bidderId,
                winnerName: bidderName,
                endTime: newEndTime || targetAuction.endTime,
                extensionCount: extended ? currentExtensions + 1 : currentExtensions,
                isExtended: extended || targetAuction.isExtended,
            }
            localAuctionsStore[targetIndex] = updatedAuction
            persistStoredAuctions(localAuctionsStore)
        }

        return {
            bid: newBid,
            currentBid: amount,
            bidCount: targetAuction ? targetAuction.bidCount + 1 : 1,
            endTime: newEndTime,
            extended,
        }
    },

    /**
     * Chức năng: Kích hoạt gia hạn thời gian cho phiên đấu giá (Anti-sniping extension).
     * Đầu vào: auctionId (mã phiên), extensionMinutes (số phút gia hạn, mặc định 5 phút).
     * Kết quả: Đối tượng Auction với mốc endTime mới được kéo dài thêm.
     * Luồng xử lý:
     *   1. Tra cứu phiên trong kho localAuctionsStore.
     *   2. Lấy mốc thời gian lớn hơn giữa Date.now() và endTime hiện tại, cộng thêm extensionMinutes.
     *   3. Tăng extensionCount, đánh dấu isExtended = true và lưu vào localStorage.
     * Quy tắc: Không gia hạn nếu phiên đã kết thúc hoặc hủy; tối đa 5 lần gia hạn.
     */
    async extendAuction(auctionId: string, extensionMinutes = 5): Promise<Auction> {
        const targetIndex = localAuctionsStore.findIndex((a) => a.id === auctionId)
        if (targetIndex < 0) {
            throw new ApiClientError('Không tìm thấy phiên đấu giá', 'AUCTION_NOT_FOUND', 404)
        }
        const current = localAuctionsStore[targetIndex]
        const baseTime = Math.max(Date.now(), new Date(current.endTime).getTime())
        const newEndTime = new Date(baseTime + extensionMinutes * 60 * 1000).toISOString()
        const updated: Auction = {
            ...current,
            endTime: newEndTime,
            extensionCount: (current.extensionCount || 0) + 1,
            isExtended: true,
            status: 'active',
        }
        localAuctionsStore[targetIndex] = updated
        persistStoredAuctions(localAuctionsStore)
        return updated
    },

    /**
     * Chức năng: Chốt kết quả quyết toán (Settlement) và kết thúc phiên đấu giá.
     * Đầu vào: auctionId.
     * Kết quả: Đối tượng Auction chuyển sang trạng thái 'ended', xác định winnerId/winnerName chính xác.
     * Luồng xử lý:
     *   1. Gửi POST /api/v1/auctions/{id}/settle lên backend nếu có kết nối.
     *   2. Nếu offline/mock, duyệt danh sách bids để chọn ra mức giá cao nhất.
     *   3. Kiểm tra điều kiện giá sàn (reservePrice nếu có); nếu thỏa mãn, gán winner.
     *   4. Chuyển trạng thái sang 'ended' và lưu vào kho local.
     */
    async settleAuction(auctionId: string): Promise<Auction> {
        const targetIndex = localAuctionsStore.findIndex((a) => a.id === auctionId)
        if (targetIndex < 0) {
            throw new ApiClientError('Không tìm thấy phiên đấu giá', 'AUCTION_NOT_FOUND', 404)
        }
        const current = localAuctionsStore[targetIndex]

        try {
            const envelope = await post<ApiResponse<any>>(`/auctions/${auctionId}/settle`, {})
            if (envelope?.data) {
                const data = envelope.data
                const updated: Auction = {
                    ...current,
                    status: 'ended',
                    settledAt: data.settledAt || new Date().toISOString(),
                    winnerId: data.winnerId || undefined,
                    winnerName: data.winnerName || current.winnerName,
                    currentBid: data.finalPrice ? Number(data.finalPrice) : current.currentBid,
                    orderId: data.orderId || current.orderId,
                }
                localAuctionsStore[targetIndex] = updated
                persistStoredAuctions(localAuctionsStore)
                return updated
            }
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
        }

        const highestBid = current.bids.length > 0 ? current.bids[0] : null
        const isReserveMet = !current.reservePrice || current.currentBid >= current.reservePrice
        const hasWinner = highestBid && isReserveMet
        const updated: Auction = {
            ...current,
            status: 'ended',
            settledAt: new Date().toISOString(),
            winnerId: hasWinner ? highestBid.bidderId : undefined,
            winnerName: hasWinner ? highestBid.bidderName : undefined,
        }
        localAuctionsStore[targetIndex] = updated
        persistStoredAuctions(localAuctionsStore)
        return updated
    },

    /**
     * Chức năng: Người bán khởi tạo phiên đấu giá mới cho sản phẩm/SKU.
     * Đầu vào: CreateAuctionPayload (productId, skuId, startingPrice, minIncrement, startTime, endTime, visibility).
     * Kết quả: Đối tượng Auction đã lưu ở Auction Service, trạng thái khởi tạo 'scheduled'.
     * Luồng xử lý:
     *   1. Kiểm tra validation nghiêm ngặt theo SRS §3.5 và AC của SPRINT2:
     *      - Giá khởi điểm > 0.
     *      - Bước giá >= 1,000 VND (hoặc cấu hình tối thiểu).
     *      - Thời gian bắt đầu phải lớn hơn thời gian hiện tại.
     *      - Thời gian kết thúc phải lớn hơn thời gian bắt đầu.
     *      - Thời lượng phiên tối thiểu 1 giờ (60 phút) và tối đa 7 ngày (168 giờ).
     *   2. Gọi POST /api/v1/auctions qua API Gateway.
     *   3. Chỉ trả thành công khi Backend trả về bản ghi đã lưu; chuẩn hóa response cho Seller/Admin/Buyer cùng đọc.
     *   4. Nếu đăng nhập, API hoặc response lỗi, ném lỗi để UI không báo thành công giả hoặc tạo phiên chỉ ở browser.
     * Quy tắc & lý do: Đảm bảo dữ liệu phiên đấu giá đáp ứng đầy đủ nghiệp vụ trước khi mở bán;
     *                  không cho phép các mốc thời gian sai lệch hay giá khởi điểm âm/bằng 0.
     * Lỗi & giới hạn: Trả về lỗi 422 kèm danh sách chi tiết lỗi trường dữ liệu khi validation thất bại.
     */
    async createAuction(payload: CreateAuctionPayload): Promise<Auction> {
        // Validation phía client chặt chẽ
        if (!payload.productId) {
            throw new ApiClientError(
                'Vui lòng chọn sản phẩm cho phiên đấu giá',
                'PRODUCT_REQUIRED',
                422,
                [{ field: 'productId', message: 'Vui lòng chọn sản phẩm cho phiên đấu giá' }],
            )
        }

        const startPrice = Number(payload.startingPrice)
        if (!Number.isFinite(startPrice) || startPrice <= 0) {
            throw new ApiClientError(
                'Giá khởi điểm phải lớn hơn 0',
                'INVALID_START_PRICE',
                422,
                [{ field: 'startingPrice', message: 'Giá khởi điểm phải lớn hơn 0' }],
            )
        }

        const minIncrement = Number(payload.minIncrement)
        if (!Number.isFinite(minIncrement) || minIncrement <= 0) {
            throw new ApiClientError(
                'Bước giá phải lớn hơn 0',
                'INVALID_BID_INCREMENT',
                422,
                [{ field: 'minIncrement', message: 'Bước giá phải lớn hơn 0' }],
            )
        }
        if (minIncrement < 1000) {
            throw new ApiClientError(
                'Bước giá tối thiểu phải từ 1,000 đ',
                'MIN_INCREMENT_TOO_LOW',
                422,
                [{ field: 'minIncrement', message: 'Bước giá tối thiểu phải từ 1,000 đ' }],
            )
        }

        const parsedStart = new Date(payload.startTime)
        if (isNaN(parsedStart.getTime()) || parsedStart.getTime() <= Date.now()) {
            throw new ApiClientError(
                'Thời gian bắt đầu phải lớn hơn thời gian hiện tại',
                'INVALID_START_TIME',
                422,
                [{ field: 'startTime', message: 'Thời gian bắt đầu phải lớn hơn thời gian hiện tại' }],
            )
        }

        const parsedEnd = new Date(payload.endTime)
        if (isNaN(parsedEnd.getTime()) || parsedEnd.getTime() <= parsedStart.getTime()) {
            throw new ApiClientError(
                'Thời gian kết thúc phải sau thời gian bắt đầu',
                'INVALID_END_TIME',
                422,
                [{ field: 'endTime', message: 'Thời gian kết thúc phải sau thời gian bắt đầu' }],
            )
        }

        const durationMs = parsedEnd.getTime() - parsedStart.getTime()
        if (durationMs < 3600000) {
            throw new ApiClientError(
                'Thời lượng phiên tối thiểu là 1 giờ',
                'DURATION_TOO_SHORT',
                422,
                [{ field: 'endTime', message: 'Thời lượng phiên tối thiểu là 1 giờ' }],
            )
        }
        if (durationMs > 7 * 86400000) {
            throw new ApiClientError(
                'Thời lượng phiên tối đa là 7 ngày',
                'DURATION_TOO_LONG',
                422,
                [{ field: 'endTime', message: 'Thời lượng phiên tối đa là 7 ngày' }],
            )
        }

        const session = readSession()

        // Kiểm tra định dạng UUID cho skuId để tránh lỗi Jackson HttpMessageNotReadableException
        const isUuidPattern = (val?: string | null) =>
            Boolean(val && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(val))

        const apiPayload = {
            productId: payload.productId,
            skuId: isUuidPattern(payload.skuId) ? payload.skuId : null,
            startPrice,
            bidIncrement: minIncrement,
            startTime: parsedStart.toISOString(),
            endTime: parsedEnd.toISOString(),
            visibility: payload.visibility === 'restricted' ? 'PRIVATE' : 'PUBLIC',
        }

        // Gửi yêu cầu khởi tạo phiên lên Auction Core Service
        const envelope = await post<ApiResponse<RawApiAuction>>('/auctions', apiPayload)
        if (envelope?.data) {
            const normalized = validateAndNormalizeAuction(envelope.data)
            if (normalized) {
                const rawSellerId = envelope.data.sellerId || envelope.data.seller_id
                const sellerId = rawSellerId || session?.user?.id || normalized.sellerId
                const sellerName =
                    envelope.data.sellerName ||
                    envelope.data.seller_name ||
                    (sellerId === session?.user?.id ? session?.user?.fullName : normalized.sellerName)
                const createdAuction: Auction = {
                    ...normalized,
                    sellerId,
                    sellerName,
                    sellerAccountName:
                        envelope.data.sellerFullName ||
                        envelope.data.seller_full_name ||
                        envelope.data.sellerAccountName ||
                        envelope.data.seller_account_name ||
                        (sellerId === session?.user?.id ? session?.user?.fullName : undefined),
                    sellerEmail: envelope.data.sellerEmail || envelope.data.seller_email || session?.user?.email,
                    product: { ...normalized.product, sellerId, sellerName },
                }
                localAuctionsStore = [createdAuction, ...localAuctionsStore.filter((auction) => auction.id !== createdAuction.id)]
                persistStoredAuctions(localAuctionsStore)
                return createdAuction
            }
        }

        // Báo lỗi dứt khoát nếu backend không trả về bản ghi hợp lệ, tuyệt đối không tạo mock giả lập
        throw new ApiClientError(
            'Không nhận được dữ liệu hợp lệ từ máy chủ khi tạo phiên đấu giá',
            'INVALID_AUCTION_RESPONSE',
            500,
        )
    },

    /**
     * Chức năng: Người bán chỉnh sửa thông số phiên đấu giá trước giờ mở.
     * Đầu vào: ID phiên và UpdateAuctionPayload chứa giá, bước giá, thời gian mới.
     * Kết quả: Đối tượng Auction đã cập nhật thông số.
     * Luồng xử lý:
     *   1. Kiểm tra điều kiện chỉnh sửa: Chỉ cho phép sửa khi trạng thái là 'scheduled', 'draft' hoặc 'pending_approval',
     *      và thời gian bắt đầu của phiên phải lớn hơn thời gian hiện tại (chưa mở phiên).
     *   2. Validate các trường giá, bước giá và thời lượng cập nhật (nếu có).
     *   3. Gửi PUT /api/v1/auctions/{id} lên backend.
     *   4. Nếu offline, cập nhật vào kho local và lưu bền vững vào localStorage.
     * Quy tắc & lý do: Nghiêm cấm chỉnh sửa khi phiên đã ACTIVE, ENDED, SETTLED hoặc CANCELLED để bảo vệ tính minh bạch.
     * Lỗi & giới hạn: Ném lỗi 400 nếu phiên không trong điều kiện cho phép chỉnh sửa.
     */
    async updateAuction(id: string, payload: UpdateAuctionPayload): Promise<Auction> {
        if (!id) {
            throw new ApiClientError('Mã phiên đấu giá không hợp lệ', 'INVALID_AUCTION_ID', 400)
        }

        const targetIndex = localAuctionsStore.findIndex((a) => a.id === id)
        const currentAuction = targetIndex >= 0 ? localAuctionsStore[targetIndex] : null

        if (currentAuction) {
            if (!['scheduled', 'draft', 'pending_approval'].includes(currentAuction.status)) {
                throw new ApiClientError(
                    'Chỉ có thể chỉnh sửa phiên đấu giá trước khi bắt đầu',
                    'AUCTION_NOT_EDITABLE',
                    400,
                )
            }
            if (new Date(currentAuction.startTime).getTime() <= Date.now()) {
                throw new ApiClientError(
                    'Phiên đấu giá đã đến giờ mở hoặc đang diễn ra, không thể chỉnh sửa',
                    'AUCTION_ALREADY_STARTED',
                    400,
                )
            }
        }

        // Validate các trường được cập nhật
        let newStartPrice = currentAuction?.startingPrice
        if (payload.startingPrice !== undefined) {
            const p = Number(payload.startingPrice)
            if (!Number.isFinite(p) || p <= 0) {
                throw new ApiClientError('Giá khởi điểm phải lớn hơn 0', 'INVALID_START_PRICE', 422, [
                    { field: 'startingPrice', message: 'Giá khởi điểm phải lớn hơn 0' },
                ])
            }
            newStartPrice = p
        }

        let newMinIncrement = currentAuction?.minIncrement
        if (payload.minIncrement !== undefined) {
            const inc = Number(payload.minIncrement)
            if (!Number.isFinite(inc) || inc <= 0) {
                throw new ApiClientError('Bước giá phải lớn hơn 0', 'INVALID_BID_INCREMENT', 422, [
                    { field: 'minIncrement', message: 'Bước giá phải lớn hơn 0' },
                ])
            }
            if (inc < 1000) {
                throw new ApiClientError('Bước giá tối thiểu phải từ 1,000 đ', 'MIN_INCREMENT_TOO_LOW', 422, [
                    { field: 'minIncrement', message: 'Bước giá tối thiểu phải từ 1,000 đ' },
                ])
            }
            newMinIncrement = inc
        }

        let newStartTime = currentAuction ? currentAuction.startTime : new Date().toISOString()
        if (payload.startTime) {
            const s = new Date(payload.startTime)
            if (isNaN(s.getTime()) || s.getTime() <= Date.now()) {
                throw new ApiClientError('Thời gian bắt đầu phải lớn hơn thời gian hiện tại', 'INVALID_START_TIME', 422, [
                    { field: 'startTime', message: 'Thời gian bắt đầu phải lớn hơn thời gian hiện tại' },
                ])
            }
            newStartTime = s.toISOString()
        }

        let newEndTime = currentAuction ? currentAuction.endTime : new Date(Date.now() + 86400000).toISOString()
        if (payload.endTime) {
            const e = new Date(payload.endTime)
            const s = new Date(newStartTime)
            if (isNaN(e.getTime()) || e.getTime() <= s.getTime()) {
                throw new ApiClientError('Thời gian kết thúc phải sau thời gian bắt đầu', 'INVALID_END_TIME', 422, [
                    { field: 'endTime', message: 'Thời gian kết thúc phải sau thời gian bắt đầu' },
                ])
            }
            const durationMs = e.getTime() - s.getTime()
            if (durationMs < 3600000) {
                throw new ApiClientError('Thời lượng phiên tối thiểu là 1 giờ', 'DURATION_TOO_SHORT', 422, [
                    { field: 'endTime', message: 'Thời lượng phiên tối thiểu là 1 giờ' },
                ])
            }
            if (durationMs > 7 * 86400000) {
                throw new ApiClientError('Thời lượng phiên tối đa là 7 ngày', 'DURATION_TOO_LONG', 422, [
                    { field: 'endTime', message: 'Thời lượng phiên tối đa là 7 ngày' },
                ])
            }
            newEndTime = e.toISOString()
        }

        try {
            const envelope = await api<ApiResponse<RawApiAuction>>(`/auctions/${id}`, {
                method: 'PUT',
                body: JSON.stringify({
                    startPrice: newStartPrice,
                    bidIncrement: newMinIncrement,
                    startTime: newStartTime,
                    endTime: newEndTime,
                    visibility: payload.visibility === 'restricted' ? 'PRIVATE' : 'PUBLIC',
                }),
            })
            if (envelope?.data) {
                const normalized = validateAndNormalizeAuction(envelope.data)
                if (normalized && targetIndex >= 0) {
                    localAuctionsStore[targetIndex] = normalized
                    persistStoredAuctions(localAuctionsStore)
                    return normalized
                }
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        if (targetIndex >= 0 && currentAuction) {
            const updatedAuction: Auction = {
                ...currentAuction,
                startingPrice: newStartPrice ?? currentAuction.startingPrice,
                currentBid: newStartPrice ?? currentAuction.currentBid,
                minIncrement: newMinIncrement ?? currentAuction.minIncrement,
                startTime: newStartTime,
                endTime: newEndTime,
                visibility: payload.visibility || currentAuction.visibility,
            }
            localAuctionsStore[targetIndex] = updatedAuction
            persistStoredAuctions(localAuctionsStore)
            return updatedAuction
        }

        throw new ApiClientError(`Không tìm thấy phiên đấu giá với ID ${id}`, 'AUCTION_NOT_FOUND', 404)
    },

    /**
     * Chức năng: Hủy phiên đấu giá phía Người bán (trước giờ mở) hoặc Admin can thiệp cưỡng chế.
     * Đầu vào: auctionId, lý do hủy (reason), quyền thực hiện (actor: 'seller' | 'admin').
     * Kết quả: Đối tượng Auction được chuyển sang trạng thái 'cancelled' kèm lý do hủy.
     * Luồng xử lý:
     *   1. Kiểm tra bắt buộc có lý do hủy.
     *   2. Với Người bán: chỉ cho phép hủy khi phiên chưa mở (startTime > now) và chưa kết thúc.
     *   3. Với Admin: có quyền cưỡng chế hủy ngay lập tức kể cả khi phiên đang ACTIVE (do vi phạm chính sách).
     *   4. Gửi POST /api/v1/auctions/{id}/cancel lên API Gateway.
     *   5. Cập nhật trạng thái 'cancelled' và lưu lý do vào localStorage.
     * Quy tắc & lý do: Đảm bảo kiểm soát quyền chặt chẽ; người bán không được tự ý hủy khi đang có người đấu giá sôi nổi.
     * Lỗi & giới hạn: Ném 403 Forbidden nếu Người bán cố tình hủy phiên đang ACTIVE.
     */
    async cancelAuction(
        id: string,
        reason: string,
        actor: 'seller' | 'admin' = 'seller',
    ): Promise<Auction> {
        if (!reason || !reason.trim()) {
            throw new ApiClientError(
                'Vui lòng nhập lý do hủy phiên đấu giá',
                'REASON_REQUIRED',
                422,
                [{ field: 'reason', message: 'Vui lòng nhập lý do hủy phiên đấu giá' }],
            )
        }

        const targetIndex = localAuctionsStore.findIndex((a) => a.id === id)
        const currentAuction = targetIndex >= 0 ? localAuctionsStore[targetIndex] : null

        if (currentAuction && actor === 'seller') {
            if (['active', 'ended', 'settled'].includes(currentAuction.status) || new Date(currentAuction.startTime).getTime() <= Date.now()) {
                throw new ApiClientError(
                    'Người bán chỉ có thể hủy phiên đấu giá trước giờ mở phiên',
                    'SELLER_CANNOT_CANCEL_ACTIVE_AUCTION',
                    403,
                )
            }
        }

        try {
            const envelope = await post<ApiResponse<RawApiAuction>>(`/auctions/${id}/cancel`, {
                reason: reason.trim(),
            })
            if (envelope?.data) {
                const normalized = validateAndNormalizeAuction(envelope.data)
                if (normalized && targetIndex >= 0) {
                    localAuctionsStore[targetIndex] = normalized
                    persistStoredAuctions(localAuctionsStore)
                    return normalized
                }
            }
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        if (targetIndex >= 0 && currentAuction) {
            const cancelledAuction: Auction = {
                ...currentAuction,
                status: 'cancelled',
                cancelReason: reason.trim(),
            }
            localAuctionsStore[targetIndex] = cancelledAuction
            persistStoredAuctions(localAuctionsStore)
            return cancelledAuction
        }

        throw new ApiClientError(`Không tìm thấy phiên đấu giá với ID ${id}`, 'AUCTION_NOT_FOUND', 404)
    },

    /**
     * Chức năng: Admin phê duyệt hoặc từ chối phiên đấu giá từ Người bán.
     * Đầu vào: auctionId, status ('scheduled' | 'rejected'), reason (nếu từ chối).
     * Kết quả: Phiên đấu giá được cập nhật trạng thái đã phê duyệt hoặc từ chối.
     * Luồng xử lý:
     *   1. Nếu từ chối, bắt buộc phải có lý do từ chối.
     *   2. Gửi POST /api/v1/auctions/{id}/review lên backend.
     *   3. Cập nhật trạng thái trong kho local và lưu trữ vào localStorage.
     * Lỗi & giới hạn: Ném 422 nếu thiếu lý do từ chối.
     */
    async reviewAuction(
        id: string,
        status: 'scheduled' | 'rejected',
        reason?: string,
    ): Promise<Auction | null> {
        if (status === 'rejected' && (!reason || !reason.trim())) {
            throw new ApiClientError(
                'Vui lòng nhập lý do từ chối phiên đấu giá',
                'REASON_REQUIRED',
                422,
                [{ field: 'reason', message: 'Vui lòng nhập lý do từ chối phiên đấu giá' }],
            )
        }

        const targetIndex = localAuctionsStore.findIndex((a) => a.id === id)
        const currentAuction = targetIndex >= 0 ? localAuctionsStore[targetIndex] : null

        try {
            await post<ApiResponse<unknown>>(`/auctions/${id}/review`, {
                status: status === 'scheduled' ? 'SCHEDULED' : 'REJECTED',
                reason: reason?.trim(),
            })
        } catch (error) {
            if (!canFallbackToMock(error)) {
                throw error
            }
        }

        // Lưu quyết định kể cả khi API list chưa có bản ghi tương ứng trong kho local.
        localAuctionReviews = {
            ...localAuctionReviews,
            [id]: { status, reason: reason?.trim(), reviewedAt: new Date().toISOString() },
        }
        persistAuctionReviews(localAuctionReviews)

        if (targetIndex >= 0 && currentAuction) {
            const reviewedAuction: Auction = {
                ...currentAuction,
                status,
                cancelReason: status === 'rejected' ? reason?.trim() : undefined,
            }
            localAuctionsStore[targetIndex] = reviewedAuction
            persistStoredAuctions(localAuctionsStore)
            return reviewedAuction
        }

        // Bản ghi API có thể chưa có trong cache; marker local vẫn giữ quyết định qua reload.
        return null
    },

    /**
     * Tạm dừng hoặc tiếp tục phiên đấu giá trong cổng Admin.
     * Gửi yêu cầu lên Auction API; khi endpoint chưa sẵn sàng, cập nhật kho mock và localStorage.
     */
    async setAuctionPaused(id: string, paused: boolean, reason: string): Promise<Auction> {
        if (!reason.trim()) {
            throw new ApiClientError('Vui lòng nhập lý do thao tác', 'REASON_REQUIRED', 422)
        }
        const index = localAuctionsStore.findIndex((auction) => auction.id === id)
        const auction = index >= 0 ? localAuctionsStore[index] : null
        if (!auction) throw new ApiClientError(`Không tìm thấy phiên đấu giá với ID ${id}`, 'AUCTION_NOT_FOUND', 404)

        try {
            await post<ApiResponse<unknown>>(`/auctions/${id}/${paused ? 'pause' : 'resume'}`, { reason: reason.trim() })
        } catch (error) {
            if (!canFallbackToMock(error)) throw error
        }

        const resumedStatus = new Date(auction.endTime).getTime() <= Date.now() ? 'ended' : 'active'
        const updated: Auction = { ...auction, status: paused ? 'paused' : resumedStatus }
        localAuctionsStore[index] = updated
        persistStoredAuctions(localAuctionsStore)
        return updated
    },

    /**
     * Chức năng: Thêm một phiên đấu giá do người bán tạo vào kho local.
     */
    addCreatedAuction(auction: Auction) {
        localAuctionsStore = [auction, ...localAuctionsStore]
        persistStoredAuctions(localAuctionsStore)
    },

    /**
     * Chức năng: Tra cứu đồng bộ phiên đấu giá từ kho cục bộ (hỗ trợ render tức thì không giật layout).
     * Đầu vào: id (mã phiên đấu giá).
     * Kết quả: Auction hoặc undefined nếu không tìm thấy.
     */
    getStoredAuctionById(id: string): Auction | undefined {
        return localAuctionsStore.find((a) => a.id === id)
    },

    /**
     * Chức năng: Xóa kho mock cục bộ và quyết định duyệt về trạng thái rỗng (dùng cho test/reset).
     */
    resetMockStore() {
        // Reset luôn rỗng; test cần fixture phải thêm tường minh qua addCreatedAuction.
        localAuctionsStore = []
        persistStoredAuctions(localAuctionsStore)
        localAuctionReviews = {}
        try {
            localStorage.removeItem(AUCTION_REVIEWS_STORAGE_KEY)
        } catch {
            // Test/reset vẫn có thể tiếp tục nếu storage không khả dụng.
        }
    },
}
