import { Component, useEffect, type ReactNode, type ErrorInfo } from 'react'
import { AppProvider, useApp } from './context'
import { ToastContainer } from './components/UI'
import { AppRouter } from './routes/AppRouter'
import { connectNotificationStream, NOTIFICATIONS_CHANGED } from './api/notificationStream'

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

function AppInner() {
    const { toasts, dismissToast, user, showToast } = useApp()
    // Một socket theo phiên UI; đổi tài khoản/logout dọn socket cũ, không dùng token/profile demo làm xác thực.
    useEffect(() => {
        if (!user?.id || !/^[0-9a-f-]{36}$/i.test(user.id)) return
        const refreshInbox = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))
        return connectNotificationStream((event) => {
            showToast({ type: 'info', message: event.title })
            refreshInbox()
        }, refreshInbox)
    }, [user?.id, showToast])
    return (
        <>
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
