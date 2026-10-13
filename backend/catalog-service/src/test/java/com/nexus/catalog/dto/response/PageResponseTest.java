package com.nexus.catalog.dto.response;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void from_ShouldMapPageCorrectly() {
        List<String> items = List.of("item1", "item2");
        Page<String> page = new PageImpl<>(items, PageRequest.of(0, 10), 2);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly("item1", "item2");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.isFirst()).isTrue();
        assertThat(response.isLast()).isTrue();
    }
}
