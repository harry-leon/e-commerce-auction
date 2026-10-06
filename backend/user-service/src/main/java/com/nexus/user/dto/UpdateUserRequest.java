package com.nexus.user.dto;

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

    @Pattern(regexp = "^$|[0-9]{10,11}", message = "Số điện thoại không hợp lệ")
    private String phone;

    private String identifyNumber;

    @Pattern(regexp = "^$|MALE|FEMALE|OTHER", message = "Giới tính phải là MALE, FEMALE hoặc OTHER")
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
