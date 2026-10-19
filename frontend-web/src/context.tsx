import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from 'react'
import type { AppState, Auction, AuctionReview, Lang, Page, Toast, User } from './types'
import { useSessionStore } from './sessionStore'
import { readRoute, writeRoute } from './routing'

const AppContext = createContext<AppState>({
  lang: 'en',
  setLang: () => undefined,
  page: 'login',
  navigate: () => undefined,
  pageParams: {},
  user: null,
  setUser: () => undefined,
  cartCount: 0,
  setCartCount: () => undefined,
  toasts: [],
  showToast: () => undefined,
  dismissToast: () => undefined,
  auctionReviews: {},
  reviewAuction: () => undefined,
  createdAuctions: [],
  submitAuction: () => undefined,
})

/**
 * Cầu nối cho component cũ: session lấy từ Zustand, route lấy từ URL, còn toast/ngôn ngữ dùng state tại đây.
 * cartCount, auctionReviews và createdAuctions chỉ là state phía trình duyệt cho màn demo, chưa lưu backend.
 */
export function AppProvider({ children }: { children: ReactNode }) {
  const [lang, setLang] = useState<Lang>('en')
  const [page, setPage] = useState<Page>(() => readRoute().page)
  const [pageParams, setPageParams] = useState<Record<string, unknown>>(() => readRoute().params)
  const { user, setUser } = useSessionStore()
  const [cartCount, setCartCount] = useState(0)
  const [toasts, setToasts] = useState<Toast[]>([])
  const [auctionReviews, setAuctionReviews] = useState<Partial<Record<string, AuctionReview>>>({})
  const [createdAuctions, setCreatedAuctions] = useState<Auction[]>([])

  // pushState không phát popstate: chủ động cập nhật URL và React state cùng lúc khi điều hướng trong app.
  const navigate = useCallback((p: Page, params?: Record<string, unknown>) => {
    writeRoute(p, params || {})
    setPage(p)
    setPageParams(params || {})
    window.scrollTo(0, 0)
  }, [])

  // Back/Forward do trình duyệt phát popstate; đọc lại URL để màn hình và tham số khớp lịch sử duyệt.
  useEffect(() => {
    const sync = () => { const route = readRoute(); setPage(route.page); setPageParams(route.params) }
    window.addEventListener('popstate', sync)
    return () => window.removeEventListener('popstate', sync)
  }, [])

  const showToast = useCallback((t: Omit<Toast, 'id'>) => {
    const id = Math.random().toString(36).slice(2)
    const toast: Toast = { ...t, id }
    setToasts(prev => [...prev, toast])
    setTimeout(() => setToasts(prev => prev.filter(x => x.id !== id)), t.duration ?? 3500)
  }, [])

  const dismissToast = useCallback((id: string) => {
    setToasts(prev => prev.filter(x => x.id !== id))
  }, [])

  const reviewAuction = useCallback((auctionId: string, status: 'scheduled' | 'rejected', reason?: string) => {
    setAuctionReviews(current => ({
      ...current,
      [auctionId]: { status, reason, reviewedAt: new Date().toISOString() },
    }))
  }, [])

  const submitAuction = useCallback((auction: Auction) => {
    setCreatedAuctions(current => [auction, ...current])
  }, [])

  return (
    <AppContext.Provider value={{ lang, setLang, page, navigate, pageParams, user, setUser, cartCount, setCartCount, toasts, showToast, dismissToast, auctionReviews, reviewAuction, createdAuctions, submitAuction }}>
      {children}
    </AppContext.Provider>
  )
}

export function useApp() { return useContext(AppContext) }
