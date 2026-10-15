import { useApp } from '../context'
import { DEMO_ADMIN, DEMO_BUYER, DEMO_SELLER, DEMO_SUPPORT } from '../mockData'
import type { User } from '../types'

export function DemoNavigation() {
  const { user, setUser, navigate } = useApp()
  const roles: [User, string, string][] = [
    [DEMO_BUYER, 'Buyer', 'home'],
    [DEMO_SELLER, 'Seller', 'seller-dashboard'],
    [DEMO_ADMIN, 'Admin', 'admin-dashboard'],
    [DEMO_SUPPORT, 'Support', 'support-dashboard'],
  ]

  return (
    <div className="fixed bottom-5 left-1/2 -translate-x-1/2 z-50 flex items-center gap-2 bg-gray-900 text-white rounded-xl px-4 py-2.5 shadow-lg text-xs">
      <span className="text-gray-400 font-medium mr-1">Demo:</span>
      {roles.map(([demoUser, label, startPage]) => (
        <button key={label} onClick={() => { setUser(demoUser); navigate(startPage as never) }}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl font-semibold transition-colors ${user?.id === demoUser.id ? 'bg-primary text-white' : 'text-gray-300 hover:bg-gray-700'}`}>
          <img src={demoUser.avatar} alt="" className="w-5 h-5 rounded-full object-cover" />
          {label}
        </button>
      ))}
      <div className="w-px h-5 bg-gray-700 mx-1" />
      <button onClick={() => navigate('auction-detail', { id: 'a1' })} className="px-3 py-1.5 rounded-xl text-gray-300 hover:bg-gray-700 font-semibold">
        Auction
      </button>
    </div>
  )
}
