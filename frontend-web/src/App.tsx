import { Component, useState, useEffect, useRef, type ReactNode, type ErrorInfo } from 'react'
import { AppProvider, useApp } from './context'
import { AppRouter } from './routes/AppRouter'
import { connectNotificationStream, NOTIFICATIONS_CHANGED } from './api/notificationStream'
import { Toaster } from 'sonner'

class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
    state = { error: null }
    static getDerivedStateFromError(error: Error) {
        return { error }
    }
    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('[Nexus Error]', error, info)
    }
    render() {
        const { error } = this.state
        if (error)
            return (
                <div
                    style={{
                        minHeight: '100vh',
                        background: '#F9FAFB',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: '2rem',
                    }}
                >
                    <div
                        style={{
                            background: '#fff',
                            border: '1px solid #E5E7EB',
                            borderRadius: 12,
                            padding: '2rem',
                            maxWidth: 560,
                            width: '100%',
                            boxShadow: '0 4px 24px rgba(0,0,0,.08)',
                        }}
                    >
                        <div
                            style={{
                                color: '#DC2626',
                                fontWeight: 700,
                                fontSize: 18,
                                marginBottom: 8,
                            }}
                        >
                            Application Error / Lỗi ứng dụng
                        </div>
                        <pre
                            style={{
                                background: '#FEF2F2',
                                border: '1px solid #FECACA',
                                borderRadius: 8,
                                padding: '1rem',
                                fontSize: 12,
                                overflowX: 'auto',
                                color: '#7F1D1D',
                                whiteSpace: 'pre-wrap',
                            }}
                        >
                            {(error as Error).message}
                            {'\n\n'}
                            {(error as Error).stack}
                        </pre>
                        <button
                            onClick={() => this.setState({ error: null })}
                            style={{
                                marginTop: 16,
                                background: '#4F46E5',
                                color: '#fff',
                                border: 'none',
                                borderRadius: 8,
                                padding: '8px 20px',
                                fontWeight: 600,
                                cursor: 'pointer',
                            }}
                        >
                            Retry / Thử lại
                        </button>
                    </div>
                </div>
            )
        return this.props.children
    }
}

/**
 * Kết nối Router, Toast và push theo phiên; đổi trang chỉ bật spinner, không tạo lại socket thông báo.
 * Đổi tài khoản/logout dọn socket và request cũ; spinner không trì hoãn mount trang con.
 */
function AppInner() {
    const { user, showToast, page } = useApp()
    const [pageTransitioning, setPageTransitioning] = useState(false)
    const prevPageRef = useRef(page)
    // Một socket theo phiên UI; đổi tài khoản/logout dọn socket cũ, không dùng token/profile demo làm xác thực.
    useEffect(() => {
        if (!user?.id || !/^[0-9a-f-]{36}$/i.test(user.id)) return
        const refreshInbox = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))
        return connectNotificationStream((event) => {
            showToast({ type: 'info', message: event.title })
            refreshInbox()
        }, refreshInbox)
    }, [user?.id, showToast])
    // Chuyển trang chỉ điều khiển spinner 300 ms; giữ kết nối push xuyên suốt cùng một tài khoản.
    useEffect(() => {
        if (prevPageRef.current !== page) {
            prevPageRef.current = page
            setPageTransitioning(true)
            const timer = setTimeout(() => setPageTransitioning(false), 300)
            return () => clearTimeout(timer)
        }
    }, [page])

    return (
        <>
            {pageTransitioning && (
                <div
                    style={{
                        position: 'fixed',
                        top: 14,
                        right: 14,
                        zIndex: 9999,
                        pointerEvents: 'none',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        background: 'rgba(255, 255, 255, 0.96)',
                        padding: '6px 14px',
                        borderRadius: '9999px',
                        boxShadow: '0 4px 14px rgba(0, 0, 0, 0.12)',
                        border: '1px solid #fee2e2',
                        transition: 'opacity 0.2s ease-in-out',
                    }}
                    role="status"
                    aria-label="Đang tải trang"
                >
                    <div
                        className="spinner-border spinner-border-sm text-primary"
                        style={{ color: '#ee4d2d' }}
                        role="status"
                    >
                        <span className="visually-hidden">Đang tải...</span>
                    </div>
                    <span style={{ fontSize: '12px', fontWeight: 600, color: '#374151' }}>
                        Đang tải...
                    </span>
                </div>
            )}
            <div key={page} className="page-transition-enter w-full">
                <AppRouter />
            </div>
            <Toaster
                richColors
                position="top-right"
                closeButton
                duration={3500}
                toastOptions={{
                    className: 'font-sans text-sm rounded-xl shadow-xl border border-gray-100',
                    style: {
                        fontFamily: "'Inter', system-ui, sans-serif",
                    },
                }}
            />
        </>
    )
}

export default function App() {
    return (
        <ErrorBoundary>
            <AppProvider>
                <ErrorBoundary>
                    <AppInner />
                </ErrorBoundary>
            </AppProvider>
        </ErrorBoundary>
    )
}
