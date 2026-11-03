package com.nexus.common.exception;

/**
 * Danh mục mã lỗi kiểm tra dữ liệu đầu vào (Field Validation Codes) theo quy chuẩn v2.
 * Dùng chung cho mọi module; chỉ xuất hiện trong mảng error.details[].code của HTTP 422 VALIDATION_ERROR.
 */
public final class ValidationCode {

    private ValidationCode() {
        // Hằng số, không cho phép khởi tạo
    }

    /** Chuỗi không được trống hoặc chỉ khoảng trắng */
    public static final String NOT_BLANK = "NOT_BLANK";

    /** Giá trị bắt buộc không được null/thiếu */
    public static final String NOT_NULL = "NOT_NULL";

    /** Số phải lớn hơn 0 */
    public static final String MUST_BE_POSITIVE = "MUST_BE_POSITIVE";

    /** Số phải lớn hơn hoặc bằng 0 */
    public static final String MUST_BE_NON_NEGATIVE = "MUST_BE_NON_NEGATIVE";

    /** Giá trị số nhỏ hơn mức tối thiểu (kèm param: min) */
    public static final String MIN_VALUE = "MIN_VALUE";

    /** Giá trị số vượt mức tối đa (kèm param: max) */
    public static final String MAX_VALUE = "MAX_VALUE";

    /** Độ dài chuỗi/số phần tử mảng chưa đạt tối thiểu (kèm param: min) */
    public static final String MIN_SIZE = "MIN_SIZE";

    /** Độ dài chuỗi/số phần tử mảng vượt tối đa (kèm param: max) */
    public static final String MAX_SIZE = "MAX_SIZE";

    /** Không khớp định dạng yêu cầu regex chung (kèm param: pattern?) */
    public static final String INVALID_FORMAT = "INVALID_FORMAT";

    /** Sai định dạng email */
    public static final String INVALID_EMAIL = "INVALID_EMAIL";

    /** Sai định dạng số điện thoại */
    public static final String INVALID_PHONE = "INVALID_PHONE";

    /** Field trong body không đúng định dạng UUID */
    public static final String INVALID_UUID = "INVALID_UUID";

    /** Giá trị không thuộc tập cho phép (kèm param: allowed) */
    public static final String INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE";

    /** Thời điểm phải nằm trong tương lai */
    public static final String FUTURE_DATE = "FUTURE_DATE";

    /** Thời điểm phải nằm trong quá khứ */
    public static final String PAST_DATE = "PAST_DATE";

    /** Thời điểm kết thúc phải sau thời điểm bắt đầu (kèm param: startField) */
    public static final String END_BEFORE_START = "END_BEFORE_START";

    /** Khoảng thời gian chưa đạt mức tối thiểu (kèm param: minMinutes) */
    public static final String MIN_DURATION = "MIN_DURATION";
}
