package com.nexus.user.dto;

import com.nexus.user.validation.ValidGender;
import com.nexus.user.validation.ValidPhoneNumber;
import jakarta.validation.constraints.NotBlank;
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

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(max = 255, message = "Họ và tên tối đa 255 ký tự")
    private String fullName;

    @ValidPhoneNumber
    private String phone;

    // Áp dụng cùng quy tắc tạo hồ sơ để API cập nhật không lưu CCCD rỗng/sai định dạng; không sửa dữ liệu cũ tự động.
    @NotBlank(message = "Số CCCD không được để trống")
    @Pattern(regexp = "[0-9]{12}", message = "Số CCCD phải gồm đúng 12 chữ số")
    private String identifyNumber;

    @ValidGender
    private String gender;

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
