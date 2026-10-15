package com.nexus.user.dto.shared;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * DTO chuẩn bọc dữ liệu phân trang để đồng nhất cấu trúc response phân trang
 * cho tất cả các service theo SRS §4.2.
 *
 * <p>Thay vì trả về trực tiếp {@code Page<T>} của Spring Data (chứa nhiều metadata không cần thiết),
 * dùng PagedResponse để kiểm soát chính xác những field nào được expose ra ngoài.
 *
 * @param <T> Kiểu dữ liệu của phần tử trong trang
 * @author Vinh Khang
 * @since 13/10/2026
 */
public class PagedResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    /**
     * Khởi tạo đối tượng PagedResponse mặc định.
     */
    public PagedResponse() {
    }

    /**
     * Xây dựng PagedResponse từ đối tượng Page của Spring Data.
     *
     * @param springPage Trang dữ liệu gốc từ Spring Data JPA
     */
    public PagedResponse(Page<T> springPage) {
        this.content = springPage.getContent();
        this.page = springPage.getNumber();
        this.size = springPage.getSize();
        this.totalElements = springPage.getTotalElements();
        this.totalPages = springPage.getTotalPages();
        this.first = springPage.isFirst();
        this.last = springPage.isLast();
    }

    /**
     * Tạo PagedResponse từ đối tượng Page của Spring Data.
     *
     * @param springPage Trang dữ liệu gốc từ Spring Data JPA
     * @param <T>        Kiểu dữ liệu phần tử
     * @return Đối tượng PagedResponse
     */
    public static <T> PagedResponse<T> of(Page<T> springPage) {
        return new PagedResponse<>(springPage);
    }

    public List<T> getContent() {
        return content;
    }

    public void setContent(List<T> content) {
        this.content = content;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public boolean isFirst() {
        return first;
    }

    public void setFirst(boolean first) {
        this.first = first;
    }

    public boolean isLast() {
        return last;
    }

    public void setLast(boolean last) {
        this.last = last;
    }
}
