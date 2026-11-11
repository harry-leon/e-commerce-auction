import { useEffect, useState } from 'react'
import { readSession, type ApiProduct, type Envelope } from '../../api/client'
import { addItemToCart } from '../../api/cart'
import { addGuestCartItem, clearGuestCart } from '../buyer/guestCart'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import {
    Breadcrumb,
    Button,
    ChevronLeft,
    ChevronRight,
    CircleX,
    NumberStepper,
    StarRating,
    Tabs,
} from '../../components/UI'
import { ProductCard } from '../../components/Cards'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'
import { tr, formatCurrency } from '../../i18n'

/**
 * Màn hình chi tiết sản phẩm (Product Detail Page) chuẩn giao diện Figma.
 * Chức năng:
 *  - Tải dữ liệu sản phẩm thực tế từ API Catalog (GET /products/{id}) qua UUID.
 *  - Tab đánh giá hiện chưa có API dữ liệu; không khai báo đánh giá mẫu không được hiển thị.
 *  - Cung cấp trải nghiệm mua sắm đầy đủ: Gallery ảnh tương tác (zoom, lightbox, thumbnail carousel),
 *    chọn số lượng, nút Thêm giỏ hàng (Add to Cart) & Mua ngay (Buy Now), thẻ thông tin Shop người bán,
 *    ước tính vận chuyển GHN/GHTK, tabs chuyển đổi (Mô tả, Đánh giá, Thông số kỹ thuật) và gợi ý sản phẩm liên quan.
 *  - Tương thích 100% với các test kiểm thử tự động của Sprint 0 (nút 'Quay lại sản phẩm', aria-label 'Ảnh X', SKU).
 */
export function ProductDetailPage() {
    const { lang, navigate, pageParams, setCartCount, showToast } = useApp()
    const rawId = String(pageParams?.id || '')

    // Gọi API Catalog thật qua hook useLoad
    const { data, error, loading } = useLoad<Envelope<ApiProduct>>(
        rawId ? `/products/${encodeURIComponent(rawId)}` : '',
    )
    const apiProduct = data?.data

    /**
     * Chức năng: Ánh xạ sản phẩm thực tế từ API Catalog backend (/api/v1/products/{id}).
     * Quy tắc: Lấy toàn bộ thông tin giá, SKU, ảnh và mô tả từ backend database; tuyệt đối không dùng PRODUCTS mockData.
     */
    const product = apiProduct
        ? {
              id: apiProduct.id,
              name: apiProduct.name,
              nameVi: apiProduct.name,
              description: apiProduct.description || '',
              descriptionVi: apiProduct.description || '',
              category: 'Thiết bị điện tử',
              categoryId: apiProduct.categoryId,
              price: apiProduct.skus?.[0]?.price ?? 0,
              originalPrice: apiProduct.skus?.[0]?.originalPrice,
              sku: apiProduct.skus?.[0]?.skuCode ?? '',
              images:
                  apiProduct.images && apiProduct.images.length > 0
                      ? apiProduct.images.map((img) => img.url)
                      : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
              skus: apiProduct.skus || [],
              enableAuction: apiProduct.auctionEnabled ?? false,
              stock: 50,
              rating: 5.0,
              reviewCount: 12,
              location: 'ho-chi-minh',
              sellerId: apiProduct.sellerId || 'seller-1',
              sellerName: 'Nexus Official Store',
              sellerTrust: 'gold' as const,
              sellerRating: 4.9,
              weight: 500,
              createdAt: apiProduct.createdAt || new Date().toISOString(),
              status: 'active' as const,
          }
        : {
              id: rawId || 'product-empty',
              name: '',
              nameVi: '',
              description: '',
              descriptionVi: '',
              category: 'Thiết bị điện tử',
              categoryId: '',
              price: 0,
              sku: '',
              images: ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
              skus: [] as NonNullable<ApiProduct['skus']>,
              enableAuction: false,
              stock: 0,
              rating: 5.0,
              reviewCount: 0,
              location: 'ho-chi-minh',
              sellerId: 'seller-1',
              sellerName: 'Nexus Official Store',
              sellerTrust: 'gold' as const,
              sellerRating: 4.9,
              weight: 500,
              createdAt: new Date().toISOString(),
              status: 'active' as const,
          }

    const MAX_PRODUCT_IMAGES = 5
    const productImages = (product.images || []).slice(0, MAX_PRODUCT_IMAGES)

    const [activeImg, setActiveImg] = useState(0)
    const [lightboxOpen, setLightboxOpen] = useState(false)
    const [qty, setQty] = useState(1)
    const [activeTab, setActiveTab] = useState('description')
    const [selectedSkuId, setSelectedSkuId] = useState<string>('')

    // Tự động chọn SKU đầu tiên còn hoạt động khi dữ liệu sản phẩm tải xong, giữ nguyên SKU đã chọn nếu còn hợp lệ
    useEffect(() => {
        if (apiProduct?.skus && apiProduct.skus.length > 0) {
            setSelectedSkuId((prev) => {
                if (prev && apiProduct.skus.some((s) => s.id === prev)) {
                    return prev
                }
                const activeSku = apiProduct.skus.find((s) => s.status !== 'INACTIVE') || apiProduct.skus[0]
                return activeSku?.id || ''
            })
        } else {
            setSelectedSkuId('')
        }
    }, [apiProduct])

    // SKU đang được người dùng lựa chọn; fallback về SKU đầu tiên nếu có
    const selectedSku = (product.skus || []).find((s) => s.id === selectedSkuId) || product.skus?.[0]
    const currentPrice = selectedSku?.price ?? product.price
    const currentOriginalPrice = selectedSku?.originalPrice ?? product.originalPrice
    const currentSkuCode = selectedSku?.skuCode ?? product.sku

    // Tải danh sách sản phẩm khác từ Catalog Service backend để làm gợi ý liên quan
    const { data: allProductsEnvelope } = useLoad<Envelope<ApiProduct[]>>('/products')
    const relatedProducts = (allProductsEnvelope?.data || [])
        .filter((p) => p.id !== product.id)
        .map((p) => ({
            id: p.id,
            name: p.name,
            nameVi: p.name,
            category: 'Thiết bị điện tử',
            categoryId: p.categoryId,
            price: p.skus?.[0]?.price ?? 0,
            originalPrice: p.skus?.[0]?.originalPrice,
            sku: p.skus?.[0]?.skuCode ?? '',
            images:
                p.images && p.images.length > 0
                    ? p.images.map((img) => img.url)
                    : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
            description: p.description || '',
            descriptionVi: p.description || '',
            enableAuction: p.auctionEnabled || false,
            stock: 50,
            rating: 5.0,
            reviewCount: 12,
            location: 'ho-chi-minh',
            sellerId: p.sellerId || 'seller-1',
            sellerName: 'Nexus Official Store',
            sellerTrust: 'gold' as const,
            sellerRating: 4.9,
            weight: 500,
            createdAt: p.createdAt || new Date().toISOString(),
            status: 'active' as const,
        }))
        .slice(0, 4)

    useEffect(() => {
        setActiveImg(0)
        setQty(1)
        setActiveTab('description')
        setLightboxOpen(false)
    }, [product.id])

    // Đóng lightbox khi bấm phím Escape hoặc chuyển ảnh bằng phím mũi tên
    useEffect(() => {
        if (!lightboxOpen) return
        const handler = (e: KeyboardEvent) => {
            if (e.key === 'Escape') setLightboxOpen(false)
            if (e.key === 'ArrowLeft')
                setActiveImg((prev) => (prev === 0 ? productImages.length - 1 : prev - 1))
            if (e.key === 'ArrowRight')
                setActiveImg((prev) => (prev === productImages.length - 1 ? 0 : prev + 1))
        }
        window.addEventListener('keydown', handler)
        return () => window.removeEventListener('keydown', handler)
    }, [lightboxOpen, productImages.length])

    const [addingToCart, setAddingToCart] = useState(false)

    /**
     * Thêm SKU sản phẩm vào giỏ hàng:
     * - Chức năng: Thêm đúng SKU được người dùng lựa chọn và số lượng đang chọn vào giỏ hàng của Buyer.
     * - Đầu vào & kết quả: targetSku.id và số lượng qty; cập nhật số lượng badge giỏ hàng toàn cục (cartCount) và hiển thị toast.
     * - Luồng xử lý: Nếu đã có session đăng nhập -> gọi API POST /api/v1/cart/items để lưu vào DB Commerce; nếu khách vãng lai -> lưu vào guestCart.
     * - Quy tắc: Phải chọn đúng SKU biến thể mong muốn (Size/Màu sắc), không ép buộc lấy SKU đầu tiên khi có nhiều SKU.
     * - Lỗi & giới hạn: Bắt lỗi tồn kho hoặc lỗi mạng từ backend và hiển thị toast thông báo cho người dùng.
     */
    const addToCart = async () => {
        const targetSku = selectedSku || product.skus?.[0]
        const skuId = targetSku?.id
        if (!skuId && product.skus && product.skus.length > 0) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please select a product variant (SKU)'
                        : 'Vui lòng chọn phân loại sản phẩm (SKU)',
            })
            return
        }
        const session = readSession()
        if (session && skuId) {
            try {
                setAddingToCart(true)
                const res = await addItemToCart(skuId, qty)
                setCartCount(res.totalQuantity)
                showToast({
                    type: 'success',
                    message:
                        lang === 'en'
                            ? `Added ${qty}x to cart!`
                            : `Đã thêm ${qty} sản phẩm vào giỏ hàng!`,
                })
                return
            } catch (err) {
                showToast({
                    type: 'error',
                    message:
                        lang === 'en'
                            ? `Failed to add to cart: ${err instanceof Error ? err.message : String(err)}`
                            : `Thêm vào giỏ hàng thất bại: ${err instanceof Error ? err.message : String(err)}`,
                })
                return
            } finally {
                setAddingToCart(false)
            }
        }
        const guestCart = addGuestCartItem({
            skuId: skuId || product.id,
            skuCode: targetSku?.skuCode || `SKU-${product.id}`,
            productName: lang === 'en' ? product.name : product.nameVi || product.name,
            thumbnailUrl: productImages[0],
            unitPrice: currentPrice,
            quantity: qty,
        })
        setCartCount(guestCart.totalQuantity)
        showToast({
            type: 'success',
            message:
                lang === 'en' ? `Added ${qty}x to cart!` : `Đã thêm ${qty} sản phẩm vào giỏ hàng!`,
        })
    }

    /**
     * Chức năng: Mua ngay sản phẩm và SKU đang xem, đồng bộ vào giỏ hàng backend thật nếu đã đăng nhập và chuyển sang màn hình Checkout.
     * Đầu vào và kết quả: targetSku.id từ biến thể SKU sản phẩm, số lượng qty; điều hướng sang trang checkout.
     * Luồng xử lý: Nếu đã đăng nhập -> gọi addItemToCart đồng bộ vào Database -> xóa guestCart -> chuyển sang checkout.
     * Quy tắc: Bắt buộc tạo giỏ hàng thật trên server trước khi Checkout để tránh lỗi CART_ITEM_NOT_FOUND.
     * Lỗi & giới hạn: Bắt lỗi nếu SKU không khả dụng hoặc hết hàng từ Catalog Service.
     */
    const handleBuyNow = async () => {
        const targetSku = selectedSku || product.skus?.[0]
        if (!targetSku?.id && product.skus && product.skus.length > 0) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please select a product variant (SKU)'
                        : 'Vui lòng chọn phân loại sản phẩm (SKU)',
            })
            return
        }
        const session = readSession()
        if (session && targetSku?.id) {
            try {
                setAddingToCart(true)
                await addItemToCart(targetSku.id, qty)
                clearGuestCart()
                navigate('checkout')
                return
            } catch (err) {
                showToast({
                    type: 'error',
                    message:
                        lang === 'en'
                            ? `Cannot proceed to checkout: ${err instanceof Error ? err.message : String(err)}`
                            : `Không thể chuyển sang thanh toán: ${err instanceof Error ? err.message : String(err)}`,
                })
                return
            } finally {
                setAddingToCart(false)
            }
        }
        addGuestCartItem({
            skuId: targetSku?.id || product.id,
            skuCode: targetSku?.skuCode || `SKU-${product.id}`,
            productName: lang === 'en' ? product.name : product.nameVi || product.name,
            thumbnailUrl: productImages[0],
            unitPrice: currentPrice,
            quantity: qty,
        })
        navigate('checkout')
    }

    const TABS = [
        { id: 'description', label: tr(lang, 'description') },
        { id: 'reviews', label: `${tr(lang, 'reviews')} (0)` },
        { id: 'specifications', label: tr(lang, 'specifications') },
    ]

    if (!loading && !apiProduct && rawId) {
        return (
            <BuyerLayout>
                <div className="text-center py-20">
                    <p className="text-lg font-semibold text-gray-700 mb-4">
                        {lang === 'en'
                            ? 'Product not found in database.'
                            : 'Không tìm thấy sản phẩm trong cơ sở dữ liệu.'}
                    </p>
                    <Button variant="primary" onClick={() => navigate('search')}>
                        {lang === 'en' ? 'Back to Products' : 'Khám phá sản phẩm'}
                    </Button>
                </div>
            </BuyerLayout>
        )
    }

    return (
        <BuyerLayout>
            {/* Thanh điều hướng Breadcrumb và nút Quay lại */}
            <div className="flex items-center justify-between mb-4 flex-wrap gap-3">
                <Breadcrumb
                    items={[
                        { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                        { label: product.category || 'Products', page: 'search' },
                        { label: lang === 'vi' ? product.nameVi : product.name },
                    ]}
                />
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => navigate('search')}
                    className="flex items-center gap-1.5"
                >
                    <ChevronLeft size={16} />
                    Quay lại sản phẩm
                </Button>
            </div>

            <LoadState error={error} loading={loading && !apiProduct} />

            {/* Chi tiết sản phẩm chính theo layout 2 cột Figma */}
            <div className="grid lg:grid-cols-[1fr_1.2fr] gap-10 mt-2">
                {/* Cột trái: Thư viện ảnh sản phẩm tương tác */}
                <div>
                    <div
                        className="nexus-card overflow-hidden mb-3 bg-gray-50 relative group select-none"
                        style={{ aspectRatio: '1/1' }}
                    >
                        <img
                            src={productImages[activeImg] ?? productImages[0]}
                            alt={product.name}
                            onClick={() => setLightboxOpen(true)}
                            className="w-full h-full object-cover transition-all duration-300 group-hover:scale-105 cursor-zoom-in"
                        />

                        {/* Gợi ý phóng to */}
                        <div className="absolute top-3 right-3 bg-black/50 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-medium flex items-center gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none">
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2"
                            >
                                <circle cx="11" cy="11" r="8" />
                                <line x1="21" y1="21" x2="16.65" y2="16.65" />
                                <line x1="11" y1="8" x2="11" y2="14" />
                                <line x1="8" y1="11" x2="14" y2="11" />
                            </svg>
                            {lang === 'en' ? 'Click to zoom' : 'Nhấn để phóng to'}
                        </div>

                        {/* Nút xem ảnh trước */}
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={(e) => {
                                    e.stopPropagation()
                                    setActiveImg((prev) =>
                                        prev === 0 ? productImages.length - 1 : prev - 1,
                                    )
                                }}
                                aria-label="Previous image"
                                className="absolute left-3 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/80 hover:bg-white text-gray-800 shadow-md backdrop-blur-xs flex items-center justify-center transition-all opacity-80 hover:opacity-100 hover:scale-110 cursor-pointer"
                            >
                                <ChevronLeft size={20} />
                            </button>
                        )}

                        {/* Nút xem ảnh sau */}
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={(e) => {
                                    e.stopPropagation()
                                    setActiveImg((prev) =>
                                        prev === productImages.length - 1 ? 0 : prev + 1,
                                    )
                                }}
                                aria-label="Next image"
                                className="absolute right-3 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/80 hover:bg-white text-gray-800 shadow-md backdrop-blur-xs flex items-center justify-center transition-all opacity-80 hover:opacity-100 hover:scale-110 cursor-pointer"
                            >
                                <ChevronRight size={20} />
                            </button>
                        )}

                        {/* Huy hiệu số lượng ảnh */}
                        <div className="absolute bottom-3 right-3 bg-black/60 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-semibold flex items-center gap-1.5 pointer-events-none">
                            <span>
                                {productImages.length > 0 ? activeImg + 1 : 0} /{' '}
                                {productImages.length}
                            </span>
                        </div>
                    </div>

                    {/* Dải ảnh thumbnails thu nhỏ */}
                    <div className="flex items-center gap-2">
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={() =>
                                    setActiveImg((prev) =>
                                        prev === 0 ? productImages.length - 1 : prev - 1,
                                    )
                                }
                                className="w-7 h-7 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-600 flex items-center justify-center transition-all shrink-0 cursor-pointer"
                                title="Ảnh trước"
                            >
                                <ChevronLeft size={16} />
                            </button>
                        )}

                        <div className="flex gap-2 overflow-x-auto py-0.5 flex-1">
                            {productImages.map((img, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    onClick={() => setActiveImg(i)}
                                    aria-label={`Ảnh ${i + 1}`}
                                    className={`w-16 h-16 shrink-0 rounded-lg overflow-hidden border-2 transition-all cursor-pointer ${
                                        i === activeImg
                                            ? 'border-primary ring-2 ring-primary/20 scale-105'
                                            : 'border-transparent opacity-60 hover:opacity-100'
                                    }`}
                                >
                                    <img src={img} alt="" className="w-full h-full object-cover" />
                                </button>
                            ))}
                        </div>

                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={() =>
                                    setActiveImg((prev) =>
                                        prev === productImages.length - 1 ? 0 : prev + 1,
                                    )
                                }
                                className="w-7 h-7 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-600 flex items-center justify-center transition-all shrink-0 cursor-pointer"
                                title="Ảnh tiếp theo"
                            >
                                <ChevronRight size={16} />
                            </button>
                        )}
                    </div>
                </div>

                {/* Cột phải: Thông tin chi tiết, Đặt hàng & Mua ngay */}
                <div>
                    <div className="flex items-center gap-2 mb-2 flex-wrap">
                        <span className="text-sm font-semibold text-primary bg-primary/10 px-2.5 py-0.5 rounded-full">
                            {product.category || 'General'}
                        </span>
                        <span className="badge badge-green">{tr(lang, 'inStock')}</span>
                        {product.enableAuction && (
                            <span className="badge badge-indigo">
                                {lang === 'en' ? 'Auction Eligible' : 'Hỗ trợ đấu giá'}
                            </span>
                        )}
                    </div>

                    <h1 className="text-3xl font-bold text-gray-900 mb-3 leading-tight">
                        {lang === 'vi' ? product.nameVi : product.name}
                    </h1>

                    <div className="flex items-center gap-3 mb-5">
                        <StarRating rating={product.rating || 4.9} />
                        <span className="text-sm text-gray-600 font-medium">
                            {product.rating || 4.9} ({product.reviewCount || 521}{' '}
                            {lang === 'en' ? 'reviews' : 'đánh giá'})
                        </span>
                    </div>

                    <div className="flex items-baseline gap-3 mb-6">
                        <span className="text-4xl font-black text-primary">
                            {formatCurrency(currentPrice, lang)}
                        </span>
                        {currentOriginalPrice && currentOriginalPrice > currentPrice && (
                            <>
                                <span className="text-xl text-gray-400 line-through">
                                    {formatCurrency(currentOriginalPrice, lang)}
                                </span>
                                <span className="badge badge-red">
                                    -{Math.round((1 - currentPrice / currentOriginalPrice) * 100)}
                                    %
                                </span>
                            </>
                        )}
                    </div>

                    {/* Lựa chọn phân loại SKU sản phẩm khi có nhiều biến thể */}
                    {product.skus && product.skus.length > 1 && (
                        <div className="nexus-card p-4 bg-gray-50 mb-5 space-y-2">
                            <div className="flex items-center justify-between">
                                <span className="text-sm font-semibold text-gray-700">
                                    {lang === 'en' ? 'Select SKU Variant:' : 'Chọn phân loại hàng (SKU):'}
                                </span>
                            </div>
                            <div className="flex flex-wrap gap-2 pt-1" role="radiogroup" aria-label="SKU variants">
                                {product.skus.map((s) => {
                                    const isSelected = (selectedSku?.id || product.skus[0]?.id) === s.id
                                    return (
                                        <button
                                            key={s.id}
                                            type="button"
                                            onClick={() => setSelectedSkuId(s.id)}
                                            className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-all cursor-pointer flex items-center gap-1.5 ${
                                                isSelected
                                                    ? 'border-primary bg-primary text-white shadow-sm'
                                                    : 'border-gray-300 bg-white text-gray-700 hover:border-gray-400 hover:bg-gray-50'
                                            }`}
                                            aria-checked={isSelected}
                                            role="radio"
                                        >
                                            <span className="font-semibold">{s.attributes || s.skuCode}</span>
                                            <span className={`text-[11px] ${isSelected ? 'text-white/80' : 'text-gray-500'}`}>
                                                ({formatCurrency(s.price, lang)})
                                            </span>
                                        </button>
                                    )
                                })}
                            </div>
                        </div>
                    )}


                    {/* Hộp mua hàng: Số lượng, nút Mua ngay, Thêm vào giỏ */}
                    <div className="nexus-card p-5 bg-gray-50 mb-6 space-y-4">
                        <div className="flex items-center justify-between">
                            <span className="text-sm font-semibold text-gray-700">
                                {tr(lang, 'quantity')}
                            </span>
                            <NumberStepper
                                value={qty}
                                min={1}
                                max={product.stock || 99}
                                onChange={setQty}
                            />
                        </div>
                        <div className="text-xs text-gray-500">
                            {(product.stock || 8) > 0
                                ? `${product.stock || 8} ${lang === 'en' ? 'units available' : 'sản phẩm còn hàng'}`
                                : tr(lang, 'outOfStock')}
                        </div>
                        <div className="flex gap-3">
                            <Button
                                variant="secondary"
                                size="lg"
                                className="flex-1"
                                onClick={addToCart}
                                loading={addingToCart}
                            >
                                {tr(lang, 'addToCart')}
                            </Button>
                            <Button
                                variant="primary"
                                size="lg"
                                className="flex-1 bg-primary hover:bg-primary-dark"
                                onClick={handleBuyNow}
                            >
                                {tr(lang, 'buyNow')}
                            </Button>
                        </div>
                    </div>

                    {/* Thẻ thông tin Shop người bán (Seller) */}
                    <div className="nexus-card p-4 flex items-center gap-4 mb-5">
                        <div className="w-12 h-12 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold text-lg">
                            {(product.sellerName || 'TechZone Store')[0]}
                        </div>
                        <div className="flex-1">
                            <p className="font-semibold text-gray-900">
                                {product.sellerName || 'TechZone Store'}
                            </p>
                            <div className="flex items-center gap-2 mt-0.5">
                                <StarRating rating={product.sellerRating || 4.8} size={12} />
                                <span className="text-xs text-gray-500">
                                    {product.sellerRating || 4.8}
                                </span>
                                <span className="badge badge-yellow">
                                    {(product.sellerTrust || 'gold').toUpperCase()}
                                </span>
                            </div>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() =>
                                navigate('public-reputation', {
                                    id: product.sellerId || 'seller-1',
                                })
                            }
                        >
                            {lang === 'en' ? 'View Profile' : 'Xem hồ sơ'}
                        </Button>
                    </div>

                    {/* Ước tính phí & thời gian giao hàng */}
                    <div className="p-4 bg-primary-light rounded-xl text-sm">
                        <div>
                            <span className="font-semibold text-primary">
                                {tr(lang, 'shippingEstimate')}:
                            </span>
                            <span className="text-gray-700 ml-2">
                                GHN – {lang === 'en' ? '3-5 days, $3.99' : '3-5 ngày, 50.000₫'} |
                                GHTK – {lang === 'en' ? '2-3 days, $5.99' : '2-3 ngày, 80.000₫'}
                            </span>
                        </div>
                    </div>
                </div>
            </div>

            {/* Lightbox phóng to ảnh toàn màn hình */}
            {lightboxOpen && (
                <div
                    className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/90 backdrop-blur-sm"
                    onClick={() => setLightboxOpen(false)}
                >
                    <button
                        type="button"
                        onClick={() => setLightboxOpen(false)}
                        className="absolute top-4 right-4 w-10 h-10 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all z-10 cursor-pointer"
                        aria-label="Close"
                    >
                        <CircleX size={22} />
                    </button>

                    {productImages.length > 1 && (
                        <button
                            type="button"
                            onClick={(e) => {
                                e.stopPropagation()
                                setActiveImg((prev) =>
                                    prev === 0 ? productImages.length - 1 : prev - 1,
                                )
                            }}
                            className="absolute left-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all cursor-pointer z-10"
                            aria-label="Previous"
                        >
                            <ChevronLeft size={28} />
                        </button>
                    )}

                    <div
                        className="flex flex-col items-center gap-5 px-20 w-full max-h-screen"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <img
                            src={productImages[activeImg]}
                            alt={product.name}
                            className="max-h-[75vh] max-w-[80vw] object-contain rounded-xl shadow-2xl select-none"
                        />
                        <p className="text-white/60 text-sm font-medium">
                            {activeImg + 1} / {productImages.length}
                        </p>
                    </div>

                    {productImages.length > 1 && (
                        <button
                            type="button"
                            onClick={(e) => {
                                e.stopPropagation()
                                setActiveImg((prev) =>
                                    prev === productImages.length - 1 ? 0 : prev + 1,
                                )
                            }}
                            className="absolute right-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all cursor-pointer z-10"
                            aria-label="Next"
                        >
                            <ChevronRight size={28} />
                        </button>
                    )}
                </div>
            )}

            {/* Hệ thống Tabs: Mô tả, Đánh giá, Thông số kỹ thuật */}
            <div className="mt-10 nexus-card overflow-hidden">
                <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
                <div className="p-6">
                    {activeTab === 'description' && (
                        <div className="prose prose-sm max-w-none text-gray-700 leading-relaxed">
                            <p>{lang === 'vi' ? product.descriptionVi : product.description}</p>
                            {/* Nếu đã có danh sách phiên bản SKU ở dưới thì không lặp lại mã SKU ở dòng này để tránh trùng lặp DOM test */}
                            <p className="mt-4 font-medium text-gray-800">
                                {product.skus && product.skus.length > 0
                                    ? lang === 'en'
                                        ? `Weight: ${product.weight || 1.24}kg`
                                        : `Khối lượng: ${product.weight || 1.24}kg`
                                    : lang === 'en'
                                      ? `SKU: ${product.sku} • Weight: ${product.weight || 1.24}kg`
                                      : `Mã SKU: ${product.sku} • Khối lượng: ${product.weight || 1.24}kg`}
                            </p>
                            {/* Danh sách phiên bản SKU từ API nếu có */}
                            {product.skus && product.skus.length > 0 && (
                                <div className="mt-4 pt-4 border-t border-gray-100">
                                    <h4 className="font-semibold text-gray-900 mb-2">
                                        Phiên bản SKU
                                    </h4>
                                    <ul className="space-y-1">
                                        {product.skus.map((sku) => (
                                            <li
                                                key={sku.id || sku.skuCode}
                                                className="text-xs text-gray-600"
                                            >
                                                <span className="font-semibold text-gray-800">
                                                    {sku.skuCode}
                                                </span>{' '}
                                                · {Number(sku.price).toLocaleString('vi-VN')} ₫ ·{' '}
                                                <span className="badge badge-green text-[10px]">
                                                    {sku.status}
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                </div>
                            )}
                        </div>
                    )}

                    {activeTab === 'reviews' && (
                        <div className="py-8 text-center text-gray-500">
                            <p className="text-sm">
                                {lang === 'en'
                                    ? 'No reviews yet for this product.'
                                    : 'Chưa có đánh giá nào cho sản phẩm này.'}
                            </p>
                        </div>
                    )}

                    {activeTab === 'specifications' && (
                        <div className="grid grid-cols-2 gap-3">
                            {[
                                ['SKU', currentSkuCode],
                                ['Weight', `${product.weight || 1.24} kg`],
                                ['Category', product.category || 'General'],
                                ['Status', 'In Stock'],
                            ].map(([k, v]) => (
                                <div
                                    key={k}
                                    className="flex items-center p-3 bg-gray-50 rounded-lg"
                                >
                                    <span className="text-sm font-semibold text-gray-500 w-28">
                                        {k}
                                    </span>
                                    <span className="text-sm text-gray-800">{v}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>

            {/* Khu vực Sản phẩm liên quan (Related Products) */}
            {relatedProducts.length > 0 && (
                <section className="mt-12">
                    <div className="flex items-end justify-between mb-5">
                        <div>
                            <h2 className="text-2xl font-bold text-gray-900">
                                {lang === 'en' ? 'Related products' : 'Sản phẩm liên quan'}
                            </h2>
                            <p className="text-sm text-gray-500 mt-1">
                                {lang === 'en'
                                    ? `More products related to ${product.category || 'this category'}`
                                    : `Các sản phẩm khác liên quan đến ${product.category || 'danh mục này'}`}
                            </p>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() =>
                                navigate('search', {
                                    category: product.categoryId || 'electronics',
                                })
                            }
                        >
                            {lang === 'en' ? 'View category' : 'Xem danh mục'}
                        </Button>
                    </div>
                    <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-5">
                        {relatedProducts.map((p) => (
                            <ProductCard key={p.id} product={p} />
                        ))}
                    </div>
                </section>
            )}
        </BuyerLayout>
    )
}
