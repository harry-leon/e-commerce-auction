import { Component, useState, useEffect, useRef, type ReactNode, type ErrorInfo } from 'react'
import { AppProvider, useApp } from './context'
import { ToastContainer } from './components/UI'
import { AppRouter } from './routes/AppRouter'

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
 * Chức năng: Component trung tâm kết nối Router và Toast.
 * Luồng: Tự động hiển thị spinner Bootstrap thương hiệu (#ee4d2d) khi người dùng điều hướng chuyển trang (page transition).
 * Quy tắc: Không trì hoãn việc mount trang con trong React tree để đảm bảo hiệu năng và tính ổn định cho kiểm thử.
 */
function AppInner() {
    const { toasts, dismissToast, page } = useApp()
    const [pageTransitioning, setPageTransitioning] = useState(false)
    const prevPageRef = useRef(page)

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
            <AppRouter />
            <ToastContainer toasts={toasts} onDismiss={dismissToast} />
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
