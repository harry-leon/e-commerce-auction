/**
 * Component Hiệu ứng Pháo hoa & Pháo bông Chúc mừng Người thắng cuộc (Winner Celebration)
 *
 * Chức năng:
 * 1. Vẽ hiệu ứng hạt pháo hoa (fireworks) nổ bừng sáng và pháo bông giấy (confetti) rơi toàn màn hình bằng HTML5 Canvas.
 * 2. Hiển thị Modal Popup chúc mừng chiến thắng phiên đấu giá với hiệu ứng động: cúp vàng lấp lánh,
 *    thông tin người thắng, sản phẩm và giá trúng thầu nổi bật, nút điều hướng thanh toán.
 *
 * Đầu vào:
 * - isOpen: Trạng thái hiển thị popup.
 * - onClose: Hàm đóng popup.
 * - winnerName: Tên người thắng đấu giá.
 * - productName: Tên sản phẩm trúng thầu.
 * - productImage?: Ảnh sản phẩm trúng thầu.
 * - finalPrice: Giá trúng thầu cuối cùng (VNĐ).
 * - originalPrice?: Giá gốc của sản phẩm để tính phần trăm tiết kiệm.
 * - onProceedPayment: Callback khi người dùng bấm "Tiến hành thanh toán ngay".
 * - lang: Ngôn ngữ hiển thị ('vi' | 'en').
 *
 * Luồng xử lý:
 * - Khi mount hoặc isOpen=true, kích hoạt vòng lặp Canvas requestAnimationFrame bắn nhiều chùm pháo hoa và confetti.
 * - Tự động dọn dẹp (cleanup) animation frame khi unmount để tránh rò rỉ bộ nhớ.
 * - Modal popup nổi lên chính giữa màn hình với hiệu ứng scale-in và âm hưởng vinh danh.
 *
 * Quy tắc & Lý do:
 * - Tuân thủ AGENTS.md: Ghi rõ tài liệu tiếng Việt, không can thiệp logic backend.
 * - Đảm bảo hiệu năng cao: Canvas render trực tiếp không gây lag UI, z-index phủ toàn trang.
 */

import { useEffect, useRef } from 'react'
import { formatCurrency } from '../../i18n'

// Các biểu tượng SVG tự định nghĩa chuẩn kích thước, không phụ thuộc thư viện ngoài
const SvgTrophy = ({ size = 24, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <path d="M8 21h8M12 17v4M7 4h10v4a5 5 0 0 1-10 0V4Z" />
        <path d="M7 6H3v2a4 4 0 0 0 4 4M17 6h4v2a4 4 0 0 1-4 4" />
    </svg>
)

const SvgSparkles = ({ size = 20, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <path d="m12 3-1.9 5.8a2 2 0 0 1-1.3 1.3L3 12l5.8 1.9a2 2 0 0 1 1.3 1.3L12 21l1.9-5.8a2 2 0 0 1 1.3-1.3L21 12l-5.8-1.9a2 2 0 0 1-1.3-1.3Z" />
    </svg>
)

const SvgCheck = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <polyline points="20 6 9 17 4 12" />
    </svg>
)

const SvgArrowRight = ({ size = 16, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <line x1="5" y1="12" x2="19" y2="12" />
        <polyline points="12 5 19 12 12 19" />
    </svg>
)

const SvgClose = ({ size = 20, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <line x1="18" y1="6" x2="6" y2="18" />
        <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
)

interface CelebrationFireworksProps {
    isOpen: boolean
    onClose: () => void
    winnerName: string
    productName: string
    productImage?: string
    finalPrice: number
    originalPrice?: number
    onProceedPayment?: () => void
    lang?: 'vi' | 'en'
}

interface Particle {
    x: number
    y: number
    vx: number
    vy: number
    color: string
    size: number
    alpha: number
    decay: number
    type: 'firework' | 'confetti'
    rotation?: number
    rotationSpeed?: number
}

/**
 * Canvas bắn pháo hoa và confetti toàn màn hình
 */
export function CelebrationCanvas({ active }: { active: boolean }) {
    const canvasRef = useRef<HTMLCanvasElement | null>(null)

    useEffect(() => {
        if (!active) return

        const canvas = canvasRef.current
        if (!canvas) return
        const ctx = canvas.getContext('2d')
        if (!ctx) return

        let animationFrameId: number
        let particles: Particle[] = []
        const colors = [
            '#ff3e3e', '#ffaa00', '#ffd700', '#00e5ff',
            '#ff007f', '#00ff66', '#a855f7', '#ff6b00'
        ]

        const handleResize = () => {
            canvas.width = window.innerWidth
            canvas.height = window.innerHeight
        }
        handleResize()
        window.addEventListener('resize', handleResize)

        // Tạo chùm nổ pháo hoa
        const spawnFirework = (targetX: number, targetY: number) => {
            const count = 45
            const baseColor = colors[Math.floor(Math.random() * colors.length)]
            for (let i = 0; i < count; i++) {
                const angle = (Math.PI * 2 * i) / count
                const speed = 2 + Math.random() * 5
                particles.push({
                    x: targetX,
                    y: targetY,
                    vx: Math.cos(angle) * speed + (Math.random() - 0.5) * 1.5,
                    vy: Math.sin(angle) * speed + (Math.random() - 0.5) * 1.5,
                    color: Math.random() > 0.3 ? baseColor : colors[Math.floor(Math.random() * colors.length)],
                    size: 2.5 + Math.random() * 2.5,
                    alpha: 1,
                    decay: 0.012 + Math.random() * 0.01,
                    type: 'firework'
                })
            }
        }

        // Tạo mảnh giấy confetti rơi
        const spawnConfetti = () => {
            for (let i = 0; i < 4; i++) {
                particles.push({
                    x: Math.random() * canvas.width,
                    y: -10,
                    vx: (Math.random() - 0.5) * 2.5,
                    vy: 2 + Math.random() * 3,
                    color: colors[Math.floor(Math.random() * colors.length)],
                    size: 6 + Math.random() * 6,
                    alpha: 1,
                    decay: 0.003,
                    type: 'confetti',
                    rotation: Math.random() * 360,
                    rotationSpeed: (Math.random() - 0.5) * 10
                })
            }
        }

        // Bắn đợt đầu ngay khi mở
        for (let i = 0; i < 4; i++) {
            spawnFirework(
                canvas.width * (0.2 + 0.6 * Math.random()),
                canvas.height * (0.2 + 0.4 * Math.random())
            )
        }

        let frameCount = 0
        const render = () => {
            frameCount++
            ctx.clearRect(0, 0, canvas.width, canvas.height)

            // Định kỳ bắn pháo hoa ngẫu nhiên
            if (frameCount % 35 === 0 && particles.length < 350) {
                spawnFirework(
                    canvas.width * (0.15 + 0.7 * Math.random()),
                    canvas.height * (0.15 + 0.45 * Math.random())
                )
            }
            // Thả confetti liên tục
            if (frameCount % 4 === 0) {
                spawnConfetti()
            }

            // Cập nhật và vẽ các hạt
            for (let i = particles.length - 1; i >= 0; i--) {
                const p = particles[i]
                p.x += p.vx
                p.y += p.vy
                p.alpha -= p.decay

                if (p.type === 'firework') {
                    p.vy += 0.06
                    p.vx *= 0.98
                    ctx.save()
                    ctx.globalAlpha = Math.max(0, p.alpha)
                    ctx.fillStyle = p.color
                    ctx.shadowBlur = 8
                    ctx.shadowColor = p.color
                    ctx.beginPath()
                    ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
                    ctx.fill()
                    ctx.restore()
                } else {
                    p.vy += 0.03
                    p.x += Math.sin(frameCount * 0.05 + p.y * 0.01) * 0.8
                    if (p.rotation !== undefined && p.rotationSpeed !== undefined) {
                        p.rotation += p.rotationSpeed
                    }
                    ctx.save()
                    ctx.globalAlpha = Math.max(0, p.alpha)
                    ctx.fillStyle = p.color
                    ctx.translate(p.x, p.y)
                    ctx.rotate(((p.rotation || 0) * Math.PI) / 180)
                    ctx.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2)
                    ctx.restore()
                }

                if (p.alpha <= 0 || p.y > canvas.height + 20) {
                    particles.splice(i, 1)
                }
            }

            animationFrameId = requestAnimationFrame(render)
        }

        render()

        return () => {
            window.removeEventListener('resize', handleResize)
            cancelAnimationFrame(animationFrameId)
        }
    }, [active])

    if (!active) return null

    return (
        <canvas
            ref={canvasRef}
            className="fixed inset-0 pointer-events-none z-50 w-full h-full"
            style={{ width: '100vw', height: '100vh' }}
        />
    )
}

/**
 * Modal Popup Chúc mừng người thắng đấu giá
 */
export function WinnerCelebrationModal({
    isOpen,
    onClose,
    winnerName,
    productName,
    productImage,
    finalPrice,
    originalPrice,
    onProceedPayment,
    lang = 'vi',
}: CelebrationFireworksProps) {
    if (!isOpen) return null

    const savingsPercent = originalPrice && originalPrice > finalPrice
        ? Math.round(((originalPrice - finalPrice) / originalPrice) * 100)
        : null

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/65 backdrop-blur-sm animate-fade-in">
            {/* Hộp Modal */}
            <div className="relative w-full max-w-lg overflow-hidden bg-gradient-to-b from-amber-500/10 via-white to-white rounded-3xl border-2 border-amber-400 shadow-2xl p-6 sm:p-8 animate-scale-in text-center">
                {/* Nút đóng */}
                <button
                    type="button"
                    onClick={onClose}
                    className="absolute top-4 right-4 p-2 text-gray-400 hover:text-gray-700 rounded-full hover:bg-gray-100 transition-colors cursor-pointer"
                    aria-label="Đóng"
                >
                    <SvgClose size={20} />
                </button>

                {/* Vầng hào quang & Icon Cúp vàng */}
                <div className="relative inline-flex items-center justify-center mb-4">
                    <div className="absolute -inset-4 bg-gradient-to-r from-amber-400 via-yellow-300 to-orange-400 rounded-full blur-xl opacity-70 animate-pulse" />
                    <div className="relative w-24 h-24 rounded-full bg-gradient-to-tr from-amber-500 via-yellow-400 to-amber-300 p-1 shadow-lg flex items-center justify-center">
                        <div className="w-full h-full rounded-full bg-amber-50 flex items-center justify-center border-2 border-amber-300">
                            <SvgTrophy size={46} className="text-amber-600 drop-shadow animate-bounce" />
                        </div>
                    </div>
                    <div className="absolute -top-1 -right-1 bg-red-500 text-white rounded-full p-1.5 shadow-md">
                        <SvgSparkles size={16} className="animate-spin text-yellow-200" />
                    </div>
                </div>

                {/* Tiêu đề chúc mừng */}
                <h2 className="text-2xl sm:text-3xl font-black bg-gradient-to-r from-amber-600 via-orange-600 to-red-600 bg-clip-text text-transparent">
                    {lang === 'vi' ? '🎉 CHÚC MỪNG BẠN ĐÃ CHIẾN THẮNG!' : '🎉 CONGRATULATIONS, YOU WON!'}
                </h2>
                <p className="text-sm text-gray-600 mt-1 max-w-md mx-auto">
                    {lang === 'vi'
                        ? 'Bạn đã xuất sắc trở thành người trả giá cao nhất khi phiên đấu giá kết thúc.'
                        : 'You are the highest bidder and won this auction!'}
                </p>

                {/* Khối vinh danh Người thắng */}
                <div className="mt-5 inline-flex items-center gap-3 px-4 py-2 bg-gradient-to-r from-amber-50 to-orange-50 border border-amber-300/80 rounded-full shadow-sm">
                    <span className="w-7 h-7 rounded-full bg-gradient-to-r from-amber-500 to-orange-500 text-white font-black text-xs flex items-center justify-center shadow">
                        ★
                    </span>
                    <span className="text-xs text-gray-600">
                        {lang === 'vi' ? 'Người chiến thắng:' : 'Winner:'}
                    </span>
                    <span className="text-sm font-bold text-gray-900">
                        {winnerName || 'Nguyễn Văn Buyer'}
                    </span>
                </div>

                {/* Thẻ thông tin sản phẩm trúng thầu */}
                <div className="mt-5 p-4 rounded-2xl bg-gray-50 border border-gray-200/80 flex items-center gap-4 text-left shadow-inner">
                    {productImage && (
                        <img
                            src={productImage}
                            alt={productName}
                            className="w-20 h-20 rounded-xl object-cover border-2 border-amber-300/60 shadow-sm shrink-0"
                        />
                    )}
                    <div className="flex-1 min-w-0">
                        <span className="inline-flex items-center gap-1 text-[11px] font-bold text-emerald-700 bg-emerald-100/80 px-2 py-0.5 rounded-full mb-1">
                            <SvgCheck size={12} />
                            {lang === 'vi' ? 'Trúng đấu giá' : 'Auction Won'}
                        </span>
                        <h4 className="text-sm font-bold text-gray-900 line-clamp-2 leading-tight">
                            {productName}
                        </h4>
                        <div className="mt-2 flex items-baseline gap-2">
                            <span className="text-xs text-gray-500">{lang === 'vi' ? 'Giá trúng:' : 'Winning bid:'}</span>
                            <span className="text-xl sm:text-2xl font-black text-[#ee4d2d]">
                                {formatCurrency(finalPrice, lang)}
                            </span>
                        </div>
                        {savingsPercent && savingsPercent > 0 && (
                            <p className="text-xs text-emerald-600 font-semibold mt-0.5">
                                {lang === 'vi' ? `Tiết kiệm ${savingsPercent}% so với giá gốc` : `Save ${savingsPercent}% off retail`}
                            </p>
                        )}
                    </div>
                </div>

                {/* Nút hành động */}
                <div className="mt-6 flex flex-col sm:flex-row gap-3">
                    <button
                        type="button"
                        onClick={() => {
                            onClose()
                            if (onProceedPayment) onProceedPayment()
                        }}
                        className="flex-1 py-3.5 px-6 rounded-xl font-bold text-sm text-white bg-gradient-to-r from-orange-500 via-[#ee4d2d] to-red-500 hover:from-orange-600 hover:to-red-600 shadow-lg shadow-orange-500/30 flex items-center justify-center gap-2 transition-transform active:scale-95 cursor-pointer"
                    >
                        <span>{lang === 'vi' ? 'Tiến hành thanh toán ngay' : 'Proceed to Checkout'}</span>
                        <SvgArrowRight size={16} />
                    </button>
                    <button
                        type="button"
                        onClick={onClose}
                        className="py-3.5 px-5 rounded-xl font-semibold text-sm text-gray-600 hover:text-gray-900 bg-gray-100 hover:bg-gray-200 transition-colors cursor-pointer"
                    >
                        {lang === 'vi' ? 'Đóng lại' : 'Dismiss'}
                    </button>
                </div>
            </div>
        </div>
    )
}
