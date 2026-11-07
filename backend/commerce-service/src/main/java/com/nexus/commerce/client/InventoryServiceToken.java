package com.nexus.commerce.client;

import com.nexus.common.security.ClientCredentialsToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OAuth client_credentials cho checkout/outbox; cache RAM và không ghi form secret qua HTTP converter DEBUG. */
@Component
public class InventoryServiceToken {
    private final ClientCredentialsToken delegate;
    private final boolean configured;

    public InventoryServiceToken(@Value("${app.payment.inventory.token-url:}") String url,
                                  @Value("${app.payment.inventory.client-id:}") String clientId,
                                  @Value("${app.payment.inventory.client-secret:}") String secret) {
        configured = !url.isBlank() && !clientId.isBlank() && !secret.isBlank();
        delegate = new ClientCredentialsToken(url, clientId, secret);
    }

    /** Thiếu account chặn mutation mới; worker giữ outbox để thử lại, không fallback token buyer. */
    public boolean configured() { return configured; }

    /** Delegate đồng bộ refresh, kiểm tra response và làm mới trước expiry; không lưu token DB. */
    public String get() { return delegate.get(); }

    /** Khi kho trả 401 thì lần retry sau phải lấy token mới, không tiếp tục gửi cache đã bị thu hồi. */
    public void invalidate() { delegate.invalidate(); }
}
