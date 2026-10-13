import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import type { AppState, Auction, AuctionReview, Lang, Page, Toast, User } from './types'
import { DEMO_BUYER } from './mockData'

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

export function AppProvider({ children }: { children: ReactNode }) {
  const [lang, setLang] = useState<Lang>('en')
  const [page, setPage] = useState<Page>('login')
  const [pageParams, setPageParams] = useState<Record<string, unknown>>({})
  const [user, setUser] = useState<User | null>(DEMO_BUYER)
  const [cartCount, setCartCount] = useState(3)
  const [toasts, setToasts] = useState<Toast[]>([])
  const [auctionReviews, setAuctionReviews] = useState<Record<string, AuctionReview>>({})
  const [createdAuctions, setCreatedAuctions] = useState<Auction[]>([])

  const navigate = useCallback((p: Page, params?: Record<string, unknown>) => {
    setPage(p)
    setPageParams(params || {})
    window.scrollTo(0, 0)
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
