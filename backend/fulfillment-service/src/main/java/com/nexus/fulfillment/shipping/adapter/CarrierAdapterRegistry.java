package com.nexus.fulfillment.shipping.adapter;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bộ định tuyến và lựa chọn Carrier Adapter theo cấu hình hệ thống và quy tắc giao vận (SRS §2.6, §3.6.2).
 *
 * Chức năng:
 * - Tự động gom tất cả bean triển khai ShippingCarrierAdapter theo mã hãng (GHN, GHTK, ...).
 * - Lọc theo danh sách SUPPORTED_CARRIERS trong cấu hình để có thể bật/tắt hãng mà không sửa code nghiệp vụ.
 * - Chọn adapter theo thứ tự ưu tiên: hãng chỉ định trong request -> quy tắc trọng lượng/dịch vụ -> DEFAULT_CARRIER.
 */
@Component
public class CarrierAdapterRegistry {

    private static final int HEAVY_PARCEL_WEIGHT_GRAMS = 10_000;

    private final Map<String, ShippingCarrierAdapter> adaptersByCode;
    private final FulfillmentProperties properties;

    public CarrierAdapterRegistry(
            List<ShippingCarrierAdapter> adapters,
            FulfillmentProperties properties) {
        this.properties = properties;
        Map<String, ShippingCarrierAdapter> map = new LinkedHashMap<>();
        if (adapters != null) {
            for (ShippingCarrierAdapter adapter : adapters) {
                if (adapter != null && adapter.getCarrierCode() != null) {
                    map.put(adapter.getCarrierCode().trim().toUpperCase(Locale.ROOT), adapter);
                }
            }
        }
        this.adaptersByCode = Map.copyOf(map);
    }

    /**
     * Chọn adapter phù hợp cho yêu cầu tạo vận đơn hoặc báo giá đơn lẻ.
     * Quy tắc: nếu caller chỉ định carrier thì phải nằm trong SUPPORTED_CARRIERS;
     * nếu bỏ trống, ưu tiên GHTK cho kiện hàng nặng (>10kg) khi khả dụng, còn lại dùng DEFAULT_CARRIER.
     */
    public ShippingCarrierAdapter resolveAdapter(
            String requestedCarrier,
            Integer weightGrams,
            String serviceType) {
        List<String> supported = normalizedSupportedCarriers();
        if (requestedCarrier != null && !requestedCarrier.isBlank()) {
            String code = requestedCarrier.trim().toUpperCase(Locale.ROOT);
            if (!supported.contains(code) || !adaptersByCode.containsKey(code)) {
                throw new BusinessException(
                        ErrorCode.INVALID_PARAM_VALUE,
                        "Hãng vận chuyển không được hỗ trợ hoặc chưa kích hoạt: " + requestedCarrier);
            }
            return adaptersByCode.get(code);
        }

        // Quy tắc chọn hãng tự động: hàng nặng > 10kg (không yêu cầu EXPRESS) ưu tiên GHTK nếu được hỗ trợ.
        if (weightGrams != null
                && weightGrams > HEAVY_PARCEL_WEIGHT_GRAMS
                && !"EXPRESS".equalsIgnoreCase(serviceType)
                && supported.contains("GHTK")
                && adaptersByCode.containsKey("GHTK")) {
            return adaptersByCode.get("GHTK");
        }

        String defaultCode = properties.getShipping().getDefaultCarrier() != null
                ? properties.getShipping().getDefaultCarrier().trim().toUpperCase(Locale.ROOT)
                : "GHN";
        if (supported.contains(defaultCode) && adaptersByCode.containsKey(defaultCode)) {
            return adaptersByCode.get(defaultCode);
        }

        for (String code : supported) {
            if (adaptersByCode.containsKey(code)) {
                return adaptersByCode.get(code);
            }
        }
        throw new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Không tìm thấy hãng vận chuyển khả dụng trong cấu hình hệ thống");
    }

    /** Lấy adapter theo mã hãng đã biết (dùng khi tracking vận đơn đã lưu). */
    public ShippingCarrierAdapter getRequiredAdapter(String carrierCode) {
        if (carrierCode == null || carrierCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thiếu mã hãng vận chuyển");
        }
        String normalized = carrierCode.trim().toUpperCase(Locale.ROOT);
        ShippingCarrierAdapter adapter = adaptersByCode.get(normalized);
        if (adapter == null) {
            throw new BusinessException(
                    ErrorCode.SHIPMENT_CARRIER_ERROR,
                    "Không tìm thấy adapter cho hãng vận chuyển: " + normalized);
        }
        return adapter;
    }

    /** Lấy adapter tùy chọn theo mã hãng, trả về rỗng nếu không tìm thấy (dùng khi fallback webhook). */
    public java.util.Optional<ShippingCarrierAdapter> findAdapter(String carrierCode) {
        if (carrierCode == null || carrierCode.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(adaptersByCode.get(carrierCode.trim().toUpperCase(Locale.ROOT)));
    }

    /** Trả về danh sách adapter thuộc SUPPORTED_CARRIERS theo đúng thứ tự cấu hình. */
    public List<ShippingCarrierAdapter> getSupportedAdapters() {
        List<ShippingCarrierAdapter> result = new ArrayList<>();
        for (String code : normalizedSupportedCarriers()) {
            ShippingCarrierAdapter adapter = adaptersByCode.get(code);
            if (adapter != null) {
                result.add(adapter);
            }
        }
        return result;
    }

    private List<String> normalizedSupportedCarriers() {
        List<String> configured = properties.getShipping().getSupportedCarriers();
        if (configured == null || configured.isEmpty()) {
            return List.of("GHN", "GHTK");
        }
        return configured.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
