package com.nexus.auction.config;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Theo dõi socket đang mở bằng timer cục bộ, đọc expiry do CONNECT đã xác thực ghi vào session attributes.
 * Socket chưa CONNECT có hạn 10 giây; socket nhàn rỗi vẫn bị đóng 1008 trong tối đa một giây sau expiry.
 * Mỗi session có một timer; disconnect hủy timer, Spring shutdown dừng executor để không rò thread.
 * Không lưu bearer/secret và không gia hạn socket bằng token refresh; client phải tạo kết nối mới.
 */
@Component
public class ExpiringWebSocketSessions implements WebSocketHandlerDecoratorFactory {
    static final String EXPIRY = ExpiringWebSocketSessions.class.getName() + ".expiry";
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "auction-ws-expiry");
        thread.setDaemon(true);
        return thread;
    });

    /** Chờ CONNECT tối đa 10 giây; sau xác thực kiểm tra expiry mỗi giây, không refresh token trên socket cũ. */
    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                super.afterConnectionEstablished(session);
                // Trước CONNECT chưa có expiry; deadline này ngăn giữ socket ẩn danh vô thời hạn.
                Instant deadline = Instant.now().plusSeconds(10);
                timers.put(session.getId(), scheduler.scheduleAtFixedRate(() -> {
                    Object value = session.getAttributes().get(EXPIRY);
                    Instant expiry = value instanceof Instant instant ? instant : deadline;
                    if (!Instant.now().isBefore(expiry)) {
                        // Đóng transport thật để session hết hạn không tiếp tục nhận broadcast khi không gửi frame.
                        try { session.close(CloseStatus.POLICY_VIOLATION); }
                        catch (java.io.IOException ignored) { /* Socket đã mất; vẫn dọn timer bên dưới. */ }
                        cancel(session.getId());
                    }
                }, 1, 1, TimeUnit.SECONDS));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                // Dọn trước khi chuyển tiếp callback vì callback downstream cũng có thể ném lỗi.
                cancel(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Remove trước cancel để callback đóng socket và timer có thể gọi đồng thời mà không hủy lặp. */
    private void cancel(String id) {
        var timer = timers.remove(id);
        if (timer != null) { timer.cancel(false); }
    }

    /** Dừng timer khi context đóng để không rò thread qua lần restart/test. */
    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); timers.clear(); }
}
