import { useEffect, useRef, useState } from "react";

export interface AuctionEvent {
  type: "BidPlaced" | "Outbid" | "AuctionEnded" | "AuctionExtended";
  auctionId: string;
  amount?: number;
  endTime?: string;
}

/**
 * Kết nối WebSocket nhận cập nhật đấu giá realtime (SRS 3.5.2).
 * Tự reconnect với backoff khi mất kết nối.
 * Endpoint /ws/auctions/{id} do auction-service cung cấp qua Gateway.
 */
export function useAuctionSocket(auctionId: string, onEvent: (e: AuctionEvent) => void) {
  const [connected, setConnected] = useState(false);
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    let ws: WebSocket | null = null;
    let retry = 0;
    let timer: ReturnType<typeof setTimeout>;
    let closed = false;

    const base =
      import.meta.env.VITE_WS_BASE_URL ||
      `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`;

    const connect = () => {
      ws = new WebSocket(`${base}/auctions/${auctionId}`);
      ws.onopen = () => {
        retry = 0;
        setConnected(true);
      };
      ws.onmessage = (msg) => {
        try {
          handlerRef.current(JSON.parse(msg.data) as AuctionEvent);
        } catch {
          /* bỏ qua message không hợp lệ */
        }
      };
      ws.onclose = () => {
        setConnected(false);
        if (closed) return;
        timer = setTimeout(connect, Math.min(1000 * 2 ** retry++, 15_000));
      };
    };
    connect();

    return () => {
      closed = true;
      clearTimeout(timer);
      ws?.close();
    };
  }, [auctionId]);

  return { connected };
}
