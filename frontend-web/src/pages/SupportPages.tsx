import { useState } from 'react'
import { useApp } from '../context'
import { tr, formatCurrency, formatDate } from '../i18n'
import { SupportLayout } from '../components/Layout'
import { Button, Input, Textarea, Select, Modal, AlertBanner, EmptyState, StatusBadge, DataTable, Search } from '../components/UI'
import { ORDERS, USERS } from '../mockData'

function SupportWorkspacePage({ activeSection }: { activeSection: 'queue' | 'order' | 'user' }) {
  const { lang, showToast, navigate, pageParams } = useApp()
  const initialOrderId = typeof pageParams?.id === 'string' ? pageParams.id : ''
  const [lookupOrderId, setLookupOrderId] = useState(initialOrderId)
  const [lookupUserId, setLookupUserId] = useState('')
  const [foundOrder, setFoundOrder] = useState<typeof ORDERS[0] | null>(() => ORDERS.find(order => order.id === initialOrderId) || null)
  const [foundUser, setFoundUser] = useState<typeof USERS[0] | null>(null)
  const [caseOpen, setCaseOpen] = useState(false)
  const [caseOrderId, setCaseOrderId] = useState('')
  const [caseTicketId, setCaseTicketId] = useState<string | null>(null)
  const [caseStatus, setCaseStatus] = useState('in-review')
  const [recommendation, setRecommendation] = useState('')
  const [evidenceNotes, setEvidenceNotes] = useState('')
  const [internalNotes, setInternalNotes] = useState('')
  const [ticketOverrides, setTicketOverrides] = useState<Record<string, string>>({})

  const statusLabel = (s: string) => ({ pending: tr(lang, 'statusPending'), paid: tr(lang, 'statusPaid'), processing: tr(lang, 'statusProcessing'), shipped: tr(lang, 'statusShipped'), delivered: tr(lang, 'statusDelivered'), completed: tr(lang, 'statusCompleted'), cancelled: tr(lang, 'statusCancelled'), refunded: tr(lang, 'statusRefunded') }[s] || s)

  const TICKETS = [
    { id: 'TKT-001', subject: lang === 'en' ? 'Order not delivered' : 'Đơn hàng chưa được giao', orderId: 'ORD-20240215-001', priority: 'high', createdAt: new Date(Date.now() - 3600000 * 2).toISOString(), status: 'open' },
    { id: 'TKT-002', subject: lang === 'en' ? 'Wrong item received' : 'Nhận sai sản phẩm', orderId: 'ORD-20240220-002', priority: 'medium', createdAt: new Date(Date.now() - 3600000 * 8).toISOString(), status: 'open' },
    { id: 'TKT-003', subject: lang === 'en' ? 'Refund not processed' : 'Hoàn tiền chưa được xử lý', orderId: 'ORD-20240101-003', priority: 'high', createdAt: new Date(Date.now() - 3600000 * 24).toISOString(), status: 'in-progress' },
  ]

  const openCase = (orderId: string, ticketId?: string) => {
    setCaseOrderId(orderId)
    setCaseTicketId(ticketId || null)
    setCaseStatus(ticketId ? (ticketOverrides[ticketId] || 'in-review') : 'in-review')
    setRecommendation('')
    setEvidenceNotes('')
    setInternalNotes('')
    setCaseOpen(true)
  }

  const saveCase = (escalate = false) => {
    const nextStatus = escalate ? 'escalated-to-admin' : caseStatus
    if (caseTicketId) setTicketOverrides(current => ({ ...current, [caseTicketId]: nextStatus }))
    setCaseOpen(false)
    showToast({
      type: 'success',
      message: escalate
        ? (lang === 'en' ? 'Case escalated to Admin for a financial decision.' : 'Đã chuyển vụ việc cho Admin để đưa ra quyết định tài chính.')
        : (lang === 'en' ? 'Case progress saved.' : 'Đã lưu tiến độ xử lý.'),
    })
  }

  const pageTitle = activeSection === 'order'
    ? (lang === 'en' ? 'Order Lookup' : 'Tra cứu đơn hàng')
    : activeSection === 'user'
      ? (lang === 'en' ? 'User Lookup' : 'Tra cứu người dùng')
      : (lang === 'en' ? 'Support Dashboard' : 'Bảng điều khiển hỗ trợ')

  return (
    <SupportLayout title={pageTitle}>
      {/* Ticket Queue */}
      {activeSection === 'queue' && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <div className="flex gap-3">
              <div className="nexus-card px-4 py-2 flex items-center gap-2">
                <span className="w-2.5 h-2.5 bg-danger rounded-full" />
                <span className="text-sm font-semibold">{lang === 'en' ? 'High Priority' : 'Ưu tiên cao'}: 2</span>
              </div>
              <div className="nexus-card px-4 py-2 flex items-center gap-2">
                <span className="w-2.5 h-2.5 bg-warning rounded-full" />
                <span className="text-sm font-semibold">{lang === 'en' ? 'Medium' : 'Trung bình'}: 1</span>
              </div>
            </div>
            <select className="nexus-input text-sm py-2 w-36">
              <option>{lang === 'en' ? 'All Status' : 'Tất cả'}</option>
              <option>Open</option>
              <option>In Review</option>
              <option>Waiting for Buyer</option>
              <option>Waiting for Seller</option>
              <option>Escalated to Admin</option>
              <option>Resolved</option>
              <option>Closed</option>
            </select>
          </div>
          <div className="space-y-3">
            {TICKETS.map(t => (
              <div key={t.id} className={`nexus-card p-4 border-l-4 ${t.priority === 'high' ? 'border-danger' : 'border-warning'}`}>
                <div className="flex items-start justify-between">
                  <div>
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-mono text-xs text-gray-500">{t.id}</span>
                      <span className={`badge ${t.priority === 'high' ? 'badge-red' : 'badge-yellow'}`}>{t.priority}</span>
                      <span className="badge badge-blue">{ticketOverrides[t.id] || t.status}</span>
                    </div>
                    <h3 className="font-semibold text-gray-900">{t.subject}</h3>
                    <p className="text-xs text-gray-500 mt-0.5">{lang === 'en' ? 'Order' : 'Đơn hàng'}: <span className="font-mono">{t.orderId}</span></p>
                    <p className="text-xs text-gray-400 mt-0.5">{new Date(t.createdAt).toLocaleString()}</p>
                  </div>
                  <div className="flex gap-2 shrink-0">
                    <Button variant="outline" size="sm" onClick={() => navigate('support-order-lookup', { id: t.orderId })}>
                      {lang === 'en' ? 'View Order' : 'Xem đơn'}
                    </Button>
                    <Button variant="primary" size="sm" onClick={() => openCase(t.orderId, t.id)}>
                      {lang === 'en' ? 'Handle Case' : 'Xử lý vụ việc'}
                    </Button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Lookup Order */}
      {activeSection === 'order' && (
        <div>
          <div className="flex gap-3 mb-5">
            <Input value={lookupOrderId} onChange={e => setLookupOrderId(e.target.value)}
              placeholder={lang === 'en' ? 'Enter Order ID (e.g. ORD-20240215-001)' : 'Nhập mã đơn hàng'}
              className="flex-1" icon={<Search size={14} />} />
            <Button variant="primary" onClick={() => { const o = ORDERS.find(o => o.id === lookupOrderId); setFoundOrder(o || null) }}>
              {lang === 'en' ? 'Lookup' : 'Tra cứu'}
            </Button>
          </div>
          {foundOrder ? (
            <div className="nexus-card">
              <div className="p-5 pb-0"><AlertBanner type="info" message={lang === 'en' ? 'Support access is read-only. Financial and shipment changes require Admin approval.' : 'Support chỉ có quyền tra cứu. Mọi thay đổi tài chính và vận chuyển cần Admin phê duyệt.'} /></div>
              <div className="p-5 border-b border-border flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-3 mb-1">
                    <StatusBadge status={foundOrder.status} label={statusLabel(foundOrder.status)} />
                    <span className="font-mono font-semibold">{foundOrder.id}</span>
                  </div>
                  <p className="text-sm text-gray-500">{lang === 'en' ? 'Buyer' : 'Người mua'}: <span className="font-semibold">{foundOrder.buyerName}</span></p>
                </div>
                <div className="flex gap-2">
                  <Button variant="primary" size="sm" onClick={() => openCase(foundOrder.id)}>{lang === 'en' ? 'Create Support Case' : 'Tạo vụ việc hỗ trợ'}</Button>
                </div>
              </div>
              <div className="p-5 grid grid-cols-2 gap-4 text-sm">
                {[[lang === 'en' ? 'Seller' : 'Người bán', foundOrder.sellerName], [lang === 'en' ? 'Total' : 'Tổng', formatCurrency(foundOrder.total, lang)], [lang === 'en' ? 'Payment' : 'Thanh toán', foundOrder.paymentMethod], [lang === 'en' ? 'Created' : 'Ngày đặt', formatDate(foundOrder.createdAt, lang)], [lang === 'en' ? 'Shipping' : 'Địa chỉ', foundOrder.shippingAddress]].map(([k, v]) => (
                  <div key={String(k)} className="p-3 bg-gray-50 rounded-lg">
                    <p className="text-xs font-semibold text-gray-500 mb-0.5">{k}</p>
                    <p className="text-gray-800">{v}</p>
                  </div>
                ))}
              </div>
              <div className="p-5 border-t border-border">
                <h4 className="font-bold mb-3">{lang === 'en' ? 'Items' : 'Sản phẩm'}</h4>
                {foundOrder.items.map(item => (
                  <div key={item.productId} className="flex items-center gap-4 py-2">
                    <img src={item.image} alt="" className="w-12 h-12 object-cover rounded-lg" />
                    <div className="flex-1"><p className="font-semibold text-sm">{item.productName}</p><p className="text-xs text-gray-500">×{item.quantity}</p></div>
                    <span className="font-bold text-primary">{formatCurrency(item.subtotal, lang)}</span>
                  </div>
                ))}
              </div>
            </div>
          ) : lookupOrderId ? (
            <EmptyState icon={<Search size={20} />} title={tr(lang, 'noData')} subtitle={lang === 'en' ? `No order found with ID "${lookupOrderId}"` : `Không tìm thấy đơn hàng với mã "${lookupOrderId}"`} />
          ) : (
            <div className="nexus-card p-8 text-center text-gray-400">
              <Search size={24} className="mx-auto mb-3 text-gray-500" />
              <p>{lang === 'en' ? 'Enter an order ID to look up details.' : 'Nhập mã đơn hàng để tra cứu.'}</p>
              <p className="text-xs mt-2 text-gray-300">{lang === 'en' ? 'Try: ORD-20240215-001' : 'Thử: ORD-20240215-001'}</p>
            </div>
          )}
        </div>
      )}

      {/* Lookup User */}
      {activeSection === 'user' && (
        <div>
          <AlertBanner type="info" message={lang === 'en' ? 'User lookup is read-only. Contact admin to make changes.' : 'Tra cứu người dùng chỉ đọc. Liên hệ quản trị viên để thay đổi.'} />
          <div className="flex gap-3 my-4">
            <Input value={lookupUserId} onChange={e => setLookupUserId(e.target.value)}
              placeholder={lang === 'en' ? 'Enter email or user ID...' : 'Nhập email hoặc mã người dùng...'}
              className="flex-1" icon={<Search size={14} />} />
            <Button variant="primary" onClick={() => { const u = USERS.find(u => u.email === lookupUserId || u.id === lookupUserId); setFoundUser(u || USERS[0]) }}>
              {lang === 'en' ? 'Lookup' : 'Tra cứu'}
            </Button>
          </div>
          {foundUser && (
            <div className="nexus-card p-5">
              <div className="flex items-center gap-5 mb-4">
                <img src={foundUser.avatar} alt="" className="w-14 h-14 rounded-xl object-cover" />
                <div>
                  <h3 className="text-lg font-bold">{foundUser.fullName}</h3>
                  <p className="text-gray-500 text-sm">{foundUser.email} · {foundUser.phone}</p>
                  <div className="flex gap-2 mt-1">
                    <span className="badge badge-indigo">{foundUser.role}</span>
                    <StatusBadge status={foundUser.trustLevel} label={foundUser.trustLevel.charAt(0).toUpperCase() + foundUser.trustLevel.slice(1)} />
                  </div>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3 text-sm">
                {[[lang === 'en' ? 'ID Number' : 'CMND/CCCD', foundUser.idNumber.replace(/.(?=.{4})/g, '•')], [lang === 'en' ? 'Reputation Score' : 'Điểm uy tín', foundUser.reputationScore + ' pts'], [lang === 'en' ? 'Address' : 'Địa chỉ', foundUser.address], [lang === 'en' ? 'Joined' : 'Ngày tham gia', formatDate(foundUser.createdAt, lang)]].map(([k, v]) => (
                  <div key={String(k)} className="p-3 bg-gray-50 rounded-lg">
                    <p className="text-xs font-semibold text-gray-500">{k}</p>
                    <p className="text-gray-800 mt-0.5">{v}</p>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Support case workflow */}
      <Modal open={caseOpen} onClose={() => setCaseOpen(false)} title={lang === 'en' ? 'Handle support case' : 'Xử lý vụ việc hỗ trợ'} size="lg"
        footer={<><Button variant="outline" onClick={() => setCaseOpen(false)}>{tr(lang, 'cancel')}</Button><Button variant="outline" onClick={() => saveCase(false)}>{lang === 'en' ? 'Save Progress' : 'Lưu tiến độ'}</Button><Button variant="primary" disabled={!recommendation} onClick={() => saveCase(true)}>{lang === 'en' ? 'Escalate to Admin' : 'Chuyển cho Admin'}</Button></>}>
        <div className="space-y-4">
          <AlertBanner type="info" message={lang === 'en' ? 'Support may collect evidence and recommend an outcome. Only Admin can move escrow funds, approve refunds, or release payment.' : 'Support có thể thu thập bằng chứng và đề xuất hướng xử lý. Chỉ Admin mới có quyền hoàn tiền, giải ngân hoặc thay đổi tiền giữ hộ.'} />
          <div className="grid grid-cols-2 gap-3">
            <div className="p-3 bg-gray-50 rounded-lg"><p className="text-xs text-gray-500">{lang === 'en' ? 'Ticket' : 'Ticket'}</p><p className="font-mono text-sm font-semibold mt-1">{caseTicketId || (lang === 'en' ? 'New case' : 'Vụ việc mới')}</p></div>
            <div className="p-3 bg-gray-50 rounded-lg"><p className="text-xs text-gray-500">{lang === 'en' ? 'Order' : 'Đơn hàng'}</p><p className="font-mono text-sm font-semibold mt-1">{caseOrderId}</p></div>
          </div>
          <Select label={lang === 'en' ? 'Case status' : 'Trạng thái vụ việc'} value={caseStatus} onChange={e => setCaseStatus(e.target.value)} options={[
            { value: 'in-review', label: lang === 'en' ? 'In Review' : 'Đang xem xét' },
            { value: 'waiting-for-buyer', label: lang === 'en' ? 'Waiting for Buyer' : 'Chờ Buyer' },
            { value: 'waiting-for-seller', label: lang === 'en' ? 'Waiting for Seller' : 'Chờ Seller' },
            { value: 'resolved', label: lang === 'en' ? 'Resolved' : 'Đã xử lý' },
          ]} />
          <Textarea label={lang === 'en' ? 'Evidence and communication summary' : 'Tóm tắt bằng chứng và trao đổi'} value={evidenceNotes} onChange={e => setEvidenceNotes(e.target.value)} rows={3} placeholder={lang === 'en' ? 'Record evidence from Buyer, Seller, and carrier...' : 'Ghi lại bằng chứng từ Buyer, Seller và đơn vị vận chuyển...'} />
          <Select label={lang === 'en' ? 'Recommended outcome' : 'Đề xuất xử lý'} value={recommendation} onChange={e => setRecommendation(e.target.value)} options={[
            { value: '', label: lang === 'en' ? 'Select a recommendation' : 'Chọn đề xuất' },
            { value: 'refund-buyer', label: lang === 'en' ? 'Refund Buyer' : 'Hoàn tiền cho Buyer' },
            { value: 'release-seller', label: lang === 'en' ? 'Release payment to Seller' : 'Giải ngân cho Seller' },
            { value: 'partial-refund', label: lang === 'en' ? 'Partial refund' : 'Hoàn tiền một phần' },
            { value: 'reject-dispute', label: lang === 'en' ? 'Reject dispute' : 'Từ chối khiếu nại' },
          ]} />
          <Textarea label={lang === 'en' ? 'Internal notes' : 'Ghi chú nội bộ'} value={internalNotes} onChange={e => setInternalNotes(e.target.value)} rows={2} placeholder={lang === 'en' ? 'Visible to Support and Admin only...' : 'Chỉ Support và Admin có thể xem...'} />
        </div>
      </Modal>
    </SupportLayout>
  )
}

export function SupportDashboardPage() {
  return <SupportWorkspacePage activeSection="queue" />
}

export function SupportOrderLookupPage() {
  return <SupportWorkspacePage activeSection="order" />
}

export function SupportUserLookupPage() {
  return <SupportWorkspacePage activeSection="user" />
}

export function SupportProfilePage() {
  const { lang, user, showToast } = useApp()
  const [saving, setSaving] = useState(false)
  if (!user) return null

  const save = async () => {
    setSaving(true)
    await new Promise(resolve => setTimeout(resolve, 600))
    setSaving(false)
    showToast({ type: 'success', message: lang === 'en' ? 'Support profile updated.' : 'Đã cập nhật hồ sơ nhân viên hỗ trợ.' })
  }

  return (
    <SupportLayout title={lang === 'en' ? 'Profile' : 'Hồ sơ'}>
      <div className="max-w-3xl">
        <div className="flex items-center gap-5 mb-6">
          <img src={user.avatar} alt="" className="w-16 h-16 rounded-xl object-cover" />
          <div>
            <h2 className="text-xl font-bold">{user.fullName}</h2>
            <p className="text-sm text-gray-500">{user.email}</p>
            <div className="mt-2"><StatusBadge status="active" label={lang === 'en' ? 'Support Staff' : 'Nhân viên hỗ trợ'} /></div>
          </div>
        </div>
        <div className="nexus-card p-6">
          <h3 className="font-bold mb-5">{lang === 'en' ? 'Account information' : 'Thông tin tài khoản'}</h3>
          <div className="grid grid-cols-2 gap-4">
            <Input label={tr(lang, 'fullName')} defaultValue={user.fullName} />
            <Input label={tr(lang, 'email')} type="email" defaultValue={user.email} />
            <Input label={tr(lang, 'phone')} defaultValue={user.phone} />
            <Input label={lang === 'en' ? 'Role' : 'Vai trò'} defaultValue={lang === 'en' ? 'Support Staff' : 'Nhân viên hỗ trợ'} disabled />
          </div>
          <div className="mt-4"><Input label={tr(lang, 'address')} defaultValue={user.address} /></div>
          <div className="mt-5"><Button variant="primary" loading={saving} onClick={save}>{tr(lang, 'save')}</Button></div>
        </div>
        <div className="nexus-card p-6 mt-5">
          <h3 className="font-bold mb-5">{lang === 'en' ? 'Change password' : 'Đổi mật khẩu'}</h3>
          <div className="grid grid-cols-2 gap-4">
            <Input label={tr(lang, 'newPassword')} type="password" placeholder="••••••••" />
            <Input label={tr(lang, 'confirmPassword')} type="password" placeholder="••••••••" />
          </div>
          <div className="mt-5"><Button variant="outline" onClick={save}>{lang === 'en' ? 'Update Password' : 'Cập nhật mật khẩu'}</Button></div>
        </div>
      </div>
    </SupportLayout>
  )
}
