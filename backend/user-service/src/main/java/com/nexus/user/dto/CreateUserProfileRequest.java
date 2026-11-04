package com.nexus.user.dto;

import com.nexus.user.validation.ValidPhoneNumber;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * DTO nhận thông tin tạo hồ sơ người dùng từ API Gateway / BFF.
 * Hoàn toàn không chứa thông tin xác thực hay mật khẩu (credentials).
 */
public class CreateUserProfileRequest {

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(max = 255, message = "Họ và tên tối đa 255 ký tự")
    private String fullName;

    // Dùng chung quy tắc di động Việt Nam với API cập nhật; bỏ trống được phép vì phone chưa bắt buộc ở BE.
    @ValidPhoneNumber
    private String phone;

    // Hồ sơ mới bắt buộc có CCCD đúng 12 chữ số ASCII, giữ số 0 đầu; chỉ kiểm tra định dạng, chưa xác minh danh tính.
    @NotBlank(message = "Số CCCD không được để trống")
    @Pattern(regexp = "[0-9]{12}", message = "Số CCCD phải gồm đúng 12 chữ số")
    private String identifyNumber;

    @Pattern(regexp = "^$|MALE|FEMALE|OTHER", message = "Giới tính phải là MALE, FEMALE hoặc OTHER")
    private String gender;

    private LocalDate dateOfBirth;

    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String address;

    public CreateUserProfileRequest() {
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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
