/**
 * Các thành phần giao diện hiển thị Điểm Uy tín, Trust Level và Biểu mẫu Đánh giá (Sprint 3 - HA-S3-2).
 * 
 * 1. Chức năng:
 *    - TrustLevelBadge: Huy hiệu cấp độ uy tín (VIP, HIGH, STANDARD, LOW) với màu sắc và icon phân biệt.
 *    - RatingStars: Hiển thị số sao đánh giá (chế độ xem hoặc chế độ chọn tương tác 1..5 sao).
 *    - RatingModal: Hộp thoại cho phép Buyer đánh giá Seller hoặc Seller đánh giá Buyer sau giao dịch.
 *    - RatingsList: Danh sách lịch sử đánh giá nhận được kèm phân trang, trạng thái tải và rỗng.
 * 2. Đầu vào & kết quả:
 *    - Nhận vào trustLevel, score, orderId, rateeId, rateeName, onSubmitSuccess.
 * 3. Luồng xử lý:
 *    - Gửi đánh giá tới reputationApi.createRating(), cập nhật state và kích hoạt callback.
 * 4. Quy tắc & lý do:
 *    - Kiểm tra và chặn tự đánh giá chính mình (raterId !== rateeId).
 *    - Bắt buộc chọn ít nhất 1 sao trước khi gửi.
 * 5. Lỗi & giới hạn:
 *    - Hiển thị thông báo lỗi rõ ràng nếu API từ chối (409 đã đánh giá, 403 không phải người mua/bán).
 */

import React, { useState } from 'react'
import { Star, Shield, CheckCircle, Modal, Button } from './UI'
import { reputationApi, type TrustLevel, type RatingResponse } from '../api/reputation'
import { useApp } from '../context'

interface TrustLevelBadgeProps {
    level: TrustLevel
    score?: number
    size?: 'sm' | 'md' | 'lg'
    className?: string
}

/**
 * Hiển thị huy hiệu Trust Level với phong cách nhận diện rõ ràng:
 * - VIP: Vàng / Cam hoàng gia (>=150 điểm).
 * - HIGH: Xanh lá bảo vệ / Khiên bảo lãnh (110..149 điểm).
 * - STANDARD: Xanh lam tiêu chuẩn (60..109 điểm).
 * - LOW: Xám / Cảnh báo cần cải thiện (<60 điểm).
 */
export function TrustLevelBadge({ level, score, size = 'md', className = '' }: TrustLevelBadgeProps) {
    const sizeClasses = {
        sm: 'text-[11px] px-2 py-0.5 gap-1',
        md: 'text-xs px-2.5 py-1 gap-1.5 font-semibold',
        lg: 'text-sm px-3.5 py-1.5 gap-2 font-bold',
    }[size]

    const config = {
        VIP: {
            bg: 'bg-gradient-to-r from-amber-500 to-yellow-400 text-white shadow-xs border-amber-300',
            label: 'VIP Trust',
            icon: <Star size={size === 'sm' ? 12 : 14} className="fill-white" />,
        },
        HIGH: {
            bg: 'bg-emerald-100 text-emerald-800 border-emerald-300',
            label: 'High Trust',
            icon: <Shield size={size === 'sm' ? 12 : 14} />,
        },
        STANDARD: {
            bg: 'bg-blue-100 text-blue-800 border-blue-300',
            label: 'Standard',
            icon: <CheckCircle size={size === 'sm' ? 12 : 14} />,
        },
        LOW: {
            bg: 'bg-gray-100 text-gray-700 border-gray-300',
            label: 'Low Trust',
            icon: (
                <svg
                    width={size === 'sm' ? 12 : 14}
                    height={size === 'sm' ? 12 : 14}
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                >
                    <circle cx="12" cy="12" r="10" />
                    <line x1="12" y1="8" x2="12" y2="12" />
                    <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
            ),
        },
    }[level] || {
        bg: 'bg-gray-100 text-gray-700 border-gray-300',
        label: level,
        icon: null,
    }

    return (
        <span
            className={`inline-flex items-center rounded-full border ${config.bg} ${sizeClasses} ${className}`}
            title={`Cấp độ uy tín: ${level}${score !== undefined ? ` (${score} điểm)` : ''}`}
        >
            {config.icon}
            <span>{config.label}</span>
            {score !== undefined && <span className="opacity-90 font-mono text-[10px]">({score})</span>}
        </span>
    )
}

interface RatingStarsProps {
    value: number // 1..5
    onChange?: (val: number) => void
    max?: number
    size?: number
    readOnly?: boolean
    className?: string
}

/**
 * Hiển thị hoặc cho phép tương tác chọn số sao đánh giá (1 đến 5 sao).
 */
export function RatingStars({
    value,
    onChange,
    max = 5,
    size = 20,
    readOnly = false,
    className = '',
}: RatingStarsProps) {
    const [hover, setHover] = useState<number | null>(null)

    return (
        <div className={`inline-flex items-center gap-1 ${className}`}>
            {Array.from({ length: max }, (_, i) => {
                const starVal = i + 1
                const isFilled = (hover !== null ? hover : value) >= starVal
                return (
                    <button
                        key={i}
                        type="button"
                        disabled={readOnly}
                        onClick={() => onChange?.(starVal)}
                        onMouseEnter={() => !readOnly && setHover(starVal)}
                        onMouseLeave={() => !readOnly && setHover(null)}
                        className={`${readOnly ? 'cursor-default' : 'cursor-pointer hover:scale-110 transition-transform'} p-0.5 focus:outline-hidden`}
                        aria-label={`${starVal} sao`}
                    >
                        <Star
                            size={size}
                            className={
                                isFilled
                                    ? 'text-amber-400 fill-amber-400'
                                    : 'text-gray-300'
                            }
                        />
                    </button>
                )
            })}
        </div>
    )
}

interface RatingModalProps {
    isOpen: boolean
    onClose: () => void
    orderId: string
    rateeId: string
    rateeName: string
    role: 'buyer' | 'seller' // Vai trò của người thực hiện đánh giá
    onSubmitSuccess?: (rating: RatingResponse) => void
}

/**
 * Hộp thoại Đánh giá đối tác sau giao dịch (Buyer -> Seller hoặc Seller -> Buyer).
 */
export function RatingModal({
    isOpen,
    onClose,
    orderId,
    rateeId,
    rateeName,
    role,
    onSubmitSuccess,
}: RatingModalProps) {
    const { user, lang, showToast } = useApp()
    const [score, setScore] = useState<number>(5)
    const [comment, setComment] = useState<string>('')
    const [loading, setLoading] = useState<boolean>(false)
    const [error, setError] = useState<string | null>(null)

    if (!isOpen) return null

    // Chặn người dùng tự đánh giá chính mình (AC 7)
    const isSelfRating = user?.id === rateeId

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault()
        if (isSelfRating) {
            setError(
                lang === 'vi'
                    ? 'Bạn không thể tự đánh giá chính mình.'
                    : 'You cannot rate yourself.',
            )
            return
        }

        if (score < 1 || score > 5) {
            setError(lang === 'vi' ? 'Vui lòng chọn từ 1 đến 5 sao.' : 'Please select 1 to 5 stars.')
            return
        }

        setLoading(true)
        setError(null)
        try {
            const res = await reputationApi.createRating({
                orderId,
                rateeId,
                score,
                comment: comment.trim(),
            })
            showToast({
                type: 'success',
                message:
                    lang === 'vi'
                        ? 'Gửi đánh giá thành công! Cảm ơn bạn đã đóng góp.'
                        : 'Rating submitted successfully!',
            })
            onSubmitSuccess?.(res)
            onClose()
        } catch (err: any) {
            setError(
                err?.message ||
                    (lang === 'vi'
                        ? 'Không thể gửi đánh giá. Vui lòng thử lại.'
                        : 'Failed to submit rating.'),
            )
        } finally {
            setLoading(false)
        }
    }

    return (
        <Modal
            open={isOpen}
            onClose={onClose}
            title={
                role === 'buyer'
                    ? lang === 'vi'
                        ? `Đánh giá người bán: ${rateeName}`
                        : `Rate Seller: ${rateeName}`
                    : lang === 'vi'
                      ? `Đánh giá người mua: ${rateeName}`
                      : `Rate Buyer: ${rateeName}`
            }
        >
            <form onSubmit={handleSubmit} className="space-y-4">
                {isSelfRating ? (
                    <div className="space-y-4">
                        <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700 flex items-center gap-2">
                            <span>
                                {lang === 'vi'
                                    ? 'Hệ thống không cho phép bạn tự đánh giá chính tài khoản của mình.'
                                    : 'You cannot rate yourself.'}
                            </span>
                        </div>
                        <div className="flex justify-end">
                            <Button type="button" variant="outline" size="sm" onClick={onClose}>
                                {lang === 'vi' ? 'Đóng' : 'Close'}
                            </Button>
                        </div>
                    </div>
                ) : (
                    <>
                        <div className="text-center py-2">
                            <p className="text-sm text-gray-600 mb-2 font-medium">
                                {lang === 'vi'
                                    ? 'Mức độ hài lòng của bạn về giao dịch này:'
                                    : 'Your rating for this transaction:'}
                            </p>
                            <RatingStars value={score} onChange={setScore} size={32} />
                            <p className="text-xs text-amber-600 font-bold mt-1">
                                {
                                    [
                                        '',
                                        lang === 'vi' ? 'Rất tệ' : 'Terrible',
                                        lang === 'vi' ? 'Không hài lòng' : 'Poor',
                                        lang === 'vi' ? 'Bình thường' : 'Average',
                                        lang === 'vi' ? 'Hài lòng' : 'Good',
                                        lang === 'vi' ? 'Tuyệt vời' : 'Excellent',
                                    ][score]
                                }
                            </p>
                        </div>

                        <div>
                            <label className="block text-xs font-semibold text-gray-700 mb-1">
                                {lang === 'vi' ? 'Nhận xét chi tiết (tùy chọn)' : 'Feedback (optional)'}
                            </label>
                            <textarea
                                value={comment}
                                onChange={(e) => setComment(e.target.value)}
                                maxLength={1000}
                                rows={4}
                                placeholder={
                                    lang === 'vi'
                                        ? 'Chia sẻ trải nghiệm về thái độ, chất lượng đóng gói, giao tiếp...'
                                        : 'Share your experience with packing, communication, delivery...'
                                }
                                className="w-full px-3 py-2 text-sm border border-gray-300 rounded-xl focus:ring-2 focus:ring-primary/20 focus:border-primary outline-hidden"
                            />
                            <div className="flex justify-between text-[11px] text-gray-400 mt-1">
                                <span>{lang === 'vi' ? 'Tối đa 1000 ký tự' : 'Max 1000 characters'}</span>
                                <span>{comment.length}/1000</span>
                            </div>
                        </div>

                        {error && (
                            <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-xs text-red-700">
                                {error}
                            </div>
                        )}

                        <div className="flex justify-end gap-2 pt-2">
                            <Button type="button" variant="outline" size="sm" onClick={onClose} disabled={loading}>
                                {lang === 'vi' ? 'Hủy' : 'Cancel'}
                            </Button>
                            <Button type="submit" variant="primary" size="sm" disabled={loading}>
                                {loading
                                    ? lang === 'vi'
                                        ? 'Đang gửi...'
                                        : 'Submitting...'
                                    : lang === 'vi'
                                      ? 'Gửi đánh giá'
                                      : 'Submit Rating'}
                            </Button>
                        </div>
                    </>
                )}
            </form>
        </Modal>
    )
}

interface RatingsListProps {
    ratings: RatingResponse[]
    loading?: boolean
    error?: string | null
    onRetry?: () => void
    emptyMessage?: string
}

/**
 * Hiển thị danh sách lịch sử đánh giá (Ratings History) kèm phân loại và trạng thái.
 */
export function RatingsList({
    ratings,
    loading = false,
    error = null,
    onRetry,
    emptyMessage,
}: RatingsListProps) {
    const { lang } = useApp()

    if (loading) {
        return (
            <div className="p-8 text-center text-gray-500" role="status">
                <div className="spinner-border spinner-border-sm text-primary mb-2 inline-block w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin" />
                <p className="text-sm">
                    {lang === 'vi' ? 'Đang tải lịch sử đánh giá...' : 'Loading ratings...'}
                </p>
            </div>
        )
    }

    if (error) {
        return (
            <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-center justify-between text-sm text-red-700">
                <span>{error}</span>
                {onRetry && (
                    <Button size="sm" variant="outline" onClick={onRetry}>
                        {lang === 'vi' ? 'Thử lại' : 'Retry'}
                    </Button>
                )}
            </div>
        )
    }

    if (ratings.length === 0) {
        return (
            <div className="p-8 text-center text-gray-400 bg-gray-50 rounded-xl">
                <Star size={32} className="mx-auto text-gray-300 mb-2" />
                <p className="text-sm font-medium">
                    {emptyMessage ||
                        (lang === 'vi' ? 'Chưa có đánh giá nào.' : 'No ratings yet.')}
                </p>
            </div>
        )
    }

    return (
        <div className="divide-y divide-gray-100">
            {ratings.map((r) => (
                <div key={r.id} className="py-4 space-y-1.5">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                            <RatingStars value={r.score} readOnly size={16} />
                            <span className="text-xs font-bold text-gray-700">{r.score}/5</span>
                            {(r.raterName || r.raterId) && (
                                <span className="text-xs font-medium text-gray-500">
                                    • {r.raterName || `ID: ${r.raterId}`}
                                </span>
                            )}
                        </div>
                        <span className="text-xs text-gray-400">
                            {new Date(r.createdAt).toLocaleDateString()}
                        </span>
                    </div>
                    {r.comment ? (
                        <p className="text-sm text-gray-800 bg-gray-50/70 p-2.5 rounded-lg">
                            {r.comment}
                        </p>
                    ) : (
                        <p className="text-xs italic text-gray-400">
                            {lang === 'vi' ? '(Không có nhận xét)' : '(No written comment)'}
                        </p>
                    )}
                </div>
            ))}
        </div>
    )
}
