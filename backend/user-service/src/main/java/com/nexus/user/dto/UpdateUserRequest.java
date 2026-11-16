package com.nexus.user.dto;

import com.nexus.user.validation.ValidGender;
import com.nexus.user.validation.ValidPhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * DTO nhận yêu cầu cập nhật thông tin người dùng theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class UpdateUserRequest {

    /**
     * Chức năng: Họ và tên người dùng khi cập nhật hồ sơ cá nhân.
     * Quy tắc và lý do: Bắt buộc không để trống, chỉ chứa chữ cái Unicode (có dấu tiếng Việt)
     * và khoảng trắng (từ 2 đến 100 ký tự). Chặn số và ký tự đặc biệt để đảm bảo tính xác thực
     * và đồng bộ chính xác với định danh Keycloak IAM (firstName/lastName).
     */
    @NotBlank(message = "Họ và tên không được để trống")
    @Pattern(
        regexp = "^[\\p{L}\\s']{2,100}$",
        message = "Họ và tên chỉ được chứa chữ cái và khoảng trắng (2–100 ký tự)"
    )
    private String fullName;

    @ValidPhoneNumber
    private String phone;

    // Áp dụng cùng quy tắc tạo hồ sơ để API cập nhật không lưu CCCD rỗng/sai định dạng; không sửa dữ liệu cũ tự động.
    @NotBlank(message = "Số CCCD không được để trống")
    @Pattern(regexp = "[0-9]{12}", message = "Số CCCD phải gồm đúng 12 chữ số")
    private String identifyNumber;

    @ValidGender
    private String gender;

    // Ngày sinh không bắt buộc; nếu có bắt buộc phải ở trong quá khứ.
    @Past(message = "Ngày sinh phải ở trong quá khứ")
    private LocalDate dateOfBirth;

    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String address;

    /**
     * Khởi tạo đối tượng UpdateUserRequest mặc định.
     */
    public UpdateUserRequest() {
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getIdentifyNumber() {
        return identifyNumber;
    }

    public void setIdentifyNumber(String identifyNumber) {
        this.identifyNumber = identifyNumber;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}
