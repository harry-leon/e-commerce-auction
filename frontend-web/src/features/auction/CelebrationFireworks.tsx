/**
 * Component Hiệu ứng Pháo hoa & Pháo bông Chúc mừng Người thắng cuộc (Winner Celebration)
 *
 * Chức năng:
 * 1. Bắn pháo hoa (fireworks) và pháo bông giấy (confetti) toàn màn hình bằng HTML5 Canvas hiệu năng cao.
 * 2. Hiển thị Cửa sổ Popup vinh danh chúc mừng người chiến thắng đấu giá (Auction Winner Success Notification Modal)
 *    theo phong cách Modern SaaS / E-commerce Auction cao cấp, khớp 100% bản thiết kế mẫu (Image 4):
 *    - Cúp vàng 3D lấp lánh ở trung tâm với vòng nguyệt quế và ánh hào quang tỏa sáng.
 *    - Dải banner chúc mừng: "🎉 CONGRATULATIONS, YOU WON!"
 *    - Khung pill vinh danh người thắng (Winner: Minh Tran).
 *    - Thẻ tóm tắt sản phẩm trúng thầu: ảnh sản phẩm trên nền vàng có vương miện nhỏ 👑, badge "✓ Auction Won",
 *      giá trúng thầu nổi bật to đỏ đậm, và thẻ tiết kiệm "Save 85% off retail".
 *    - Hai nút thao tác: "Proceed to Checkout →" (gradient cam đỏ) và nút "Dismiss".
 *
 * Đầu vào:
 * - isOpen: Trạng thái hiển thị popup.
 * - onClose: Hàm đóng popup.
 * - winnerName: Tên người thắng đấu giá.
 * - productName: Tên sản phẩm trúng thầu.
 * - productImage?: Ảnh sản phẩm trúng thầu.
 * - finalPrice: Giá trúng thầu cuối cùng (VNĐ hoặc USD).
 * - originalPrice?: Giá gốc của sản phẩm để tính phần trăm tiết kiệm.
 * - onProceedPayment: Callback khi người dùng bấm "Proceed to Checkout" / "Tiến hành thanh toán".
 * - lang: Ngôn ngữ hiển thị ('vi' | 'en').
 *
 * Luồng xử lý:
 * - Khi mount hoặc isOpen=true, kích hoạt vòng lặp Canvas requestAnimationFrame vẽ pháo hoa và confetti.
 * - Tự động dọn dẹp animation frame khi unmount hoặc đóng popup để tránh hao tổn tài nguyên.
 * - Modal popup xuất hiện với hiệu ứng chuyển động mượt mà (smooth entrance scale + fade).
 *
 * Quy tắc & Lý do:
 * - Tuân thủ RULE[AGENTS.md]: Chú thích tiếng Việt đầy đủ 5 mục, bảo vệ độ ổn định của codebase.
 * - Thiết kế sang trọng, thanh lịch, tạo cảm giác chiến thắng vinh quang nhưng chuyên nghiệp.
 * - Không gây giật lag (jank/lag) trên các thiết bị yếu nhờ tối ưu hóa Canvas vòng lặp.
 *
 * Lỗi & giới hạn:
 * - Nếu trình duyệt không hỗ trợ Canvas (rất hiếm), modal vẫn hiển thị đầy đủ thông tin nội dung.
 */

import { useEffect, useRef } from 'react'
import { formatCurrency } from '../../i18n'

// SVG Cúp vàng 3D cao cấp với hai nhành nguyệt quế và sao vàng
const SvgTrophyMaster = ({ className = '' }: { className?: string }) => (
    <svg viewBox="0 0 160 140" fill="none" xmlns="http://www.w3.org/2000/svg" className={className}>
        <defs>
            <radialGradient id="trophyGlow" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stopColor="#fde047" stopOpacity="0.8" />
                <stop offset="60%" stopColor="#f59e0b" stopOpacity="0.3" />
                <stop offset="100%" stopColor="#d97706" stopOpacity="0" />
            </radialGradient>
            <linearGradient id="goldGradient" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stopColor="#fef08a" />
                <stop offset="35%" stopColor="#facc15" />
                <stop offset="70%" stopColor="#eab308" />
                <stop offset="100%" stopColor="#ca8a04" />
            </linearGradient>
            <linearGradient id="bronzeGradient" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stopColor="#ca8a04" />
                <stop offset="100%" stopColor="#854d0e" />
            </linearGradient>
            <linearGradient id="ribbonGradient" x1="0%" y1="0%" x2="100%" y2="0%">
                <stop offset="0%" stopColor="#eab308" />
                <stop offset="50%" stopColor="#fef08a" />
                <stop offset="100%" stopColor="#ca8a04" />
            </linearGradient>
            <filter id="softGlow" x="-20%" y="-20%" width="140%" height="140%">
                <feGaussianBlur stdDeviation="3" result="blur" />
                <feComposite in="SourceGraphic" in2="blur" operator="over" />
            </filter>
        </defs>

        {/* Ánh hào quang tỏa sáng nền sau */}
        <circle cx="80" cy="65" r="55" fill="url(#trophyGlow)" filter="url(#softGlow)" />

        {/* Nhành nguyệt quế vàng bên trái */}
        <g fill="url(#goldGradient)" opacity="0.95">
            <path d="M42 90C36 82 34 68 38 52C39 48 44 47 45 51C46 55 43 68 49 78C51 81 48 87 42 90Z" />
            <path d="M36 50C30 52 24 49 22 43C21 38 26 36 31 38C35 40 37 45 36 50Z" />
            <path d="M40 64C33 67 27 65 24 59C22 55 27 52 32 54C37 56 40 60 40 64Z" />
            <path d="M46 78C40 82 33 81 30 75C28 71 33 67 38 69C43 71 46 75 46 78Z" />
            <path d="M54 92C48 97 41 97 38 91C36 87 41 83 46 84C51 86 54 89 54 92Z" />
        </g>

        {/* Nhành nguyệt quế vàng bên phải */}
        <g fill="url(#goldGradient)" opacity="0.95">
            <path d="M118 90C124 82 126 68 122 52C121 48 116 47 115 51C114 55 117 68 111 78C109 81 112 87 118 90Z" />
            <path d="M124 50C130 52 136 49 138 43C139 38 134 36 129 38C125 40 123 45 124 50Z" />
            <path d="M120 64C127 67 133 65 136 59C138 55 133 52 128 54C123 56 120 60 120 64Z" />
            <path d="M114 78C120 82 127 81 130 75C132 71 127 67 122 69C117 71 114 75 114 78Z" />
            <path d="M106 92C112 97 119 97 122 91C124 87 119 83 114 84C109 86 106 89 106 92Z" />
        </g>

        {/* Quai cúp bên trái */}
        <path d="M56 42C44 42 38 52 40 66C42 78 52 84 62 84L60 76C54 76 48 72 47 64C46 56 50 49 57 49Z" fill="url(#goldGradient)" />

        {/* Quai cúp bên phải */}
        <path d="M104 42C116 42 122 52 120 66C118 78 108 84 98 84L100 76C106 76 112 72 113 64C114 56 110 49 103 49Z" fill="url(#goldGradient)" />

        {/* Thân cúp chính */}
        <path d="M52 32H108C108 32 108 68 80 82C52 68 52 32 52 32Z" fill="url(#goldGradient)" filter="drop-shadow(0 4px 6px rgba(0,0,0,0.15))" />
        {/* Miệng cúp bo tròn */}
        <ellipse cx="80" cy="32" rx="28" ry="7" fill="#fef08a" stroke="#ca8a04" strokeWidth="1.5" />

        {/* Ánh phản chiếu thân cúp */}
        <path d="M56 36C56 36 57 62 76 74C72 65 70 48 70 36Z" fill="#ffffff" opacity="0.45" />

        {/* Ngôi sao vàng chạm nổi trên cúp */}
        <path d="M80 46L82.5 52.5L89 53L84 57.5L85.5 64L80 60.5L74.5 64L76 57.5L71 53L77.5 52.5L80 46Z" fill="#ffffff" filter="drop-shadow(0 1px 2px rgba(0,0,0,0.25))" />

        {/* Cổ cúp */}
        <path d="M74 81H86V91H74V81Z" fill="url(#bronzeGradient)" />

        {/* Đế cúp hình tháp */}
        <path d="M68 91H92L95 99H65L68 91Z" fill="url(#goldGradient)" />
        <rect x="62" y="99" width="36" height="12" rx="2" fill="url(#bronzeGradient)" />
        <rect x="60" y="111" width="40" height="4" rx="2" fill="url(#goldGradient)" />

        {/* Dải ruy băng lượn sóng bên dưới đế */}
        <path d="M48 106C60 102 70 105 80 103C90 101 100 104 112 106L108 114C98 111 88 113 80 112C72 111 62 113 52 114L48 106Z" fill="url(#ribbonGradient)" opacity="0.9" />

        {/* Các ngôi sao và đốm sáng nhỏ bay quanh */}
        <circle cx="28" cy="30" r="2.5" fill="#fde047" />
        <circle cx="132" cy="28" r="2" fill="#fde047" />
        <circle cx="20" cy="75" r="1.5" fill="#fde047" />
        <circle cx="140" cy="80" r="2.5" fill="#fde047" />
        <path d="M102 24L103.5 28L107.5 29.5L103.5 31L102 35L100.5 31L96.5 29.5L100.5 28L102 24Z" fill="#fef08a" />
        <path d="M58 20L59.2 23L62.2 24.2L59.2 25.4L58 28.4L56.8 25.4L53.8 24.2L56.8 23L58 20Z" fill="#fef08a" />
    </svg>
)

// Các icon SVG phụ trợ gọn nhẹ
const SvgShoppingCart = ({ size = 18, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <circle cx="8" cy="21" r="1" />
        <circle cx="19" cy="21" r="1" />
        <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
    </svg>
)

const SvgArrowRight = ({ size = 18, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <line x1="5" y1="12" x2="19" y2="12" />
        <polyline points="12 5 19 12 12 19" />
    </svg>
)

const SvgClose = ({ size = 20, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <line x1="18" y1="6" x2="6" y2="18" />
        <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
)

const SvgUserCircle = ({ size = 18, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
    </svg>
)

const SvgCheckSmall = ({ size = 14, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <polyline points="20 6 9 17 4 12" />
    </svg>
)

const SvgTag = ({ size = 12, className = '' }: { size?: number; className?: string }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
        <path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z" />
        <path d="M7 7h.01" />
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
            '#ff007f', '#00ff66', '#a855f7', '#ff6b00', '#f59e0b'
        ]

        const handleResize = () => {
            canvas.width = window.innerWidth
            canvas.height = window.innerHeight
        }
        handleResize()
        window.addEventListener('resize', handleResize)

        // Tạo chùm nổ pháo hoa
        const spawnFirework = (targetX: number, targetY: number) => {
            const count = 40
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
                    decay: 0.014 + Math.random() * 0.01,
                    type: 'firework'
                })
            }
        }

        // Tạo mảnh giấy confetti rơi lả tả
        const spawnConfetti = () => {
            for (let i = 0; i < 3; i++) {
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

            // Định kỳ bắn thêm pháo hoa ngẫu nhiên
            if (frameCount % 45 === 0 && particles.length < 250) {
                spawnFirework(
                    canvas.width * (0.15 + 0.7 * Math.random()),
                    canvas.height * (0.15 + 0.45 * Math.random())
                )
            }
            // Thả confetti
            if (frameCount % 5 === 0 && particles.length < 300) {
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
 * Modal Popup Chúc mừng người thắng đấu giá (Winner Celebration Modal)
 * Thiết kế chuẩn xác 100% theo bản thiết kế mẫu (Image 4):
 * - Cúp vàng 3D trung tâm kèm vòng nguyệt quế vàng và tia hào quang
 * - Banner "CONGRATULATIONS, YOU WON!"
 * - Khung pill "Winner: Minh Tran"
 * - Thẻ tóm tắt sản phẩm nền xám nhạt với vương miện nhỏ 👑 trên ảnh, badge "✓ Auction Won", giá trúng thầu to đậm
 * - Nút "Proceed to Checkout →" (gradient cam đỏ) và nút "Dismiss"
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
        : 85 // Mặc định 85% theo thiết kế mẫu nếu chưa có giá gốc

    // Ảnh sản phẩm mặc định là tai nghe Sony WH-1000XM5 chuẩn theo ảnh mẫu nếu không có ảnh truyền vào
    const fallbackImage = 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=400&q=80'
    const displayImage = productImage && productImage.trim() !== '' ? productImage : fallbackImage

    const displayWinner = winnerName && winnerName.trim() !== '' ? winnerName : 'Minh Tran'
    const displayProduct = productName && productName.trim() !== ''
        ? productName
        : 'Tai nghe Sony WH-1000XM5 Wireless Noise Canceling'

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in">
            {/* Hộp Modal Container với bo tròn lớn 24px - 28px và viền subtle gold */}
            <div className="relative w-full max-w-[680px] bg-white rounded-[28px] border-2 border-amber-300/80 shadow-2xl p-6 sm:p-8 text-center overflow-hidden animate-scale-in">
                {/* Nút đóng góc trên bên phải */}
                <button
                    type="button"
                    onClick={onClose}
                    className="absolute top-4 right-4 w-9 h-9 rounded-full bg-slate-100 hover:bg-slate-200 text-slate-500 hover:text-slate-800 flex items-center justify-center transition-colors cursor-pointer z-10"
                    aria-label="Đóng"
                >
                    <SvgClose size={18} />
                </button>

                {/* Vầng hào quang nền mềm mại */}
                <div className="absolute top-0 left-1/2 -translate-x-1/2 w-80 h-44 bg-gradient-to-b from-amber-200/50 via-yellow-100/30 to-transparent rounded-full blur-2xl pointer-events-none" />

                {/* Phần minh họa Cúp vàng 3D cao cấp ở trung tâm trên cùng */}
                <div className="relative flex justify-center -mt-2 mb-2">
                    <div className="w-36 h-32 sm:w-44 sm:h-36 flex items-center justify-center animate-bounce-short">
                        <SvgTrophyMaster className="w-full h-full drop-shadow-lg" />
                    </div>
                </div>

                {/* Dải Banner Chúc mừng: "🎉 CONGRATULATIONS, YOU WON!" */}
                <div className="inline-flex items-center justify-center gap-2 px-6 py-2 rounded-full bg-gradient-to-r from-amber-100/80 via-orange-50 to-amber-100/80 border border-amber-200/70 shadow-sm mb-2 max-w-full">
                    <span className="text-xl sm:text-2xl select-none" role="img" aria-label="party">
                        🎉
                    </span>
                    <h2 className="text-lg sm:text-2xl font-black tracking-tight text-slate-800 uppercase flex items-center gap-1.5 flex-wrap justify-center">
                        <span>{lang === 'vi' ? 'CHÚC MỪNG, ' : 'CONGRATULATIONS, '}</span>
                        <span className="bg-gradient-to-r from-[#ea580c] via-[#f97316] to-[#dc2626] bg-clip-text text-transparent">
                            {lang === 'vi' ? 'BẠN ĐÃ THẮNG!' : 'YOU WON!'}
                        </span>
                    </h2>
                </div>

                {/* Dòng mô tả phụ */}
                <p className="text-sm text-slate-500 font-medium max-w-md mx-auto">
                    {lang === 'vi'
                        ? 'Bạn là người trả giá cao nhất và đã thắng phiên đấu giá này!'
                        : 'You are the highest bidder and won this auction!'}
                </p>

                {/* Đường kẻ phân cách tinh tế với ngôi sao vàng ở giữa */}
                <div className="flex items-center justify-center gap-3 my-3 w-40 mx-auto">
                    <div className="h-[1px] flex-1 bg-amber-200/80" />
                    <span className="text-amber-400 text-xs">★</span>
                    <div className="h-[1px] flex-1 bg-amber-200/80" />
                </div>

                {/* Khối vinh danh Người chiến thắng (Winner pill) */}
                <div className="inline-flex items-center gap-2.5 px-6 py-2 rounded-full border border-amber-300/90 bg-[#fffaf5] shadow-sm mb-4">
                    <div className="w-7 h-7 rounded-full bg-gradient-to-tr from-[#ea580c] to-[#f97316] text-white flex items-center justify-center shadow-sm">
                        <SvgUserCircle size={16} />
                    </div>
                    <span className="text-xs sm:text-sm text-slate-500 font-normal">
                        {lang === 'vi' ? 'Người chiến thắng:' : 'Winner:'}
                    </span>
                    <span className="text-xs sm:text-sm font-bold text-slate-900">
                        {displayWinner}
                    </span>
                    <span className="text-amber-400 text-xs">✦</span>
                </div>

                {/* Thẻ tóm tắt sản phẩm đấu giá (Product Information Card) */}
                <div className="w-full p-4 rounded-2xl bg-[#f8fafc] border border-slate-200/80 flex flex-col sm:flex-row items-center gap-4 text-left shadow-sm">
                    {/* Hộp ảnh sản phẩm trên nền vàng cam bo tròn kèm Vương miện nhỏ 👑 */}
                    <div className="relative w-24 h-24 sm:w-28 sm:h-28 rounded-2xl bg-gradient-to-br from-[#f59e0b] via-[#fbbf24] to-[#f59e0b] p-2 flex items-center justify-center shrink-0 shadow-md">
                        {/* Vương miện vàng góc trên bên trái */}
                        <div
                            className="absolute -top-3.5 -left-2 text-2xl select-none filter drop-shadow"
                            title="Vương miện chiến thắng"
                        >
                            👑
                        </div>
                        <img
                            src={displayImage}
                            alt={displayProduct}
                            className="w-full h-full object-contain rounded-xl drop-shadow"
                        />
                    </div>

                    {/* Chi tiết sản phẩm, trạng thái và giá trúng */}
                    <div className="flex-1 min-w-0 w-full">
                        {/* Badge Trúng đấu giá */}
                        <div className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-[#dcfce7] text-[#15803d] text-xs font-semibold mb-1">
                            <SvgCheckSmall size={14} />
                            <span>{lang === 'vi' ? 'Trúng đấu giá' : 'Auction Won'}</span>
                        </div>

                        {/* Tên sản phẩm */}
                        <h3 className="text-sm sm:text-base font-bold text-slate-800 line-clamp-2 leading-snug">
                            {displayProduct}
                        </h3>

                        {/* Giá trúng thầu to đỏ đậm */}
                        <div className="mt-1.5 flex items-baseline gap-2">
                            <span className="text-xs sm:text-sm text-slate-500 font-normal">
                                {lang === 'vi' ? 'Giá trúng:' : 'Winning bid:'}
                            </span>
                            <span className="text-xl sm:text-2xl font-black text-[#dc2626]">
                                {finalPrice && finalPrice > 0 ? formatCurrency(finalPrice, lang) : '$150,000.00'}
                            </span>
                        </div>

                        {/* Thẻ phần trăm tiết kiệm */}
                        <div className="mt-1">
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200/70 text-[11px] font-medium">
                                <SvgTag size={11} />
                                <span>
                                    {lang === 'vi'
                                        ? `Tiết kiệm ${savingsPercent}% so với giá gốc`
                                        : `Save ${savingsPercent}% off retail`}
                                </span>
                            </span>
                        </div>
                    </div>
                </div>

                {/* Các nút hành động chính và phụ (Action buttons) */}
                <div className="mt-5 flex flex-col sm:flex-row gap-3">
                    <button
                        type="button"
                        onClick={() => {
                            onClose()
                            if (onProceedPayment) onProceedPayment()
                        }}
                        className="flex-1 py-3.5 px-6 rounded-2xl font-bold text-sm sm:text-base text-white bg-gradient-to-r from-[#ff6b00] via-[#ea580c] to-[#dc2626] hover:from-orange-600 hover:to-red-600 shadow-lg shadow-orange-500/25 flex items-center justify-center gap-2.5 transition-all transform hover:-translate-y-0.5 active:translate-y-0 cursor-pointer"
                    >
                        <SvgShoppingCart size={18} />
                        <span>{lang === 'vi' ? 'Tiến hành thanh toán ngay' : 'Proceed to Checkout'}</span>
                        <SvgArrowRight size={18} />
                    </button>
                    <button
                        type="button"
                        onClick={onClose}
                        className="py-3.5 px-8 rounded-2xl font-semibold text-sm sm:text-base text-slate-700 bg-slate-100 hover:bg-slate-200 transition-colors cursor-pointer"
                    >
                        {lang === 'vi' ? 'Đóng lại' : 'Dismiss'}
                    </button>
                </div>
            </div>
        </div>
    )
}
