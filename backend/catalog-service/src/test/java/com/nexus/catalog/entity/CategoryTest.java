package com.nexus.catalog.entity;

import com.nexus.catalog.enums.CategoryStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CategoryTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Category newValidCategory() {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Electronics");
        return category;
    }

    @Test
    void defaultsAreLevelOneAndActive() {
        Category category = new Category();
        assertEquals(1, category.getLevel());
        assertEquals(CategoryStatus.ACTIVE, category.getStatus());
        assertNotNull(category.getChildren());
        assertTrue(category.getChildren().isEmpty());
        assertNull(category.getParent());
    }

    @Test
    void settersAndGettersRoundTrip() {
        Category category = new Category();
        UUID id = UUID.randomUUID();
        Category parent = new Category();

        category.setId(id);
        category.setParent(parent);
        category.setName("Phones");
        category.setLevel(2);
        category.setStatus(CategoryStatus.INACTIVE);

        assertEquals(id, category.getId());
        assertEquals(parent, category.getParent());
        assertEquals("Phones", category.getName());
        assertEquals(2, category.getLevel());
        assertEquals(CategoryStatus.INACTIVE, category.getStatus());
    }

    @Test
    void childrenAreMutable() {
        Category category = new Category();
        Category child = new Category();
        category.getChildren().add(child);
        assertEquals(1, category.getChildren().size());

        category.setChildren(List.of(child));
        assertEquals(1, category.getChildren().size());
    }

    @Test
    void validCategoryHasNoViolations() {
        assertTrue(validator.validate(newValidCategory()).isEmpty());
    }

    @Test
    void blankNameIsRejected() {
        Category category = newValidCategory();
        category.setName(" ");
        assertFalse(validator.validate(category).isEmpty());
    }

    @Test
    void levelBelowOneIsRejected() {
        Category category = newValidCategory();
        category.setLevel(0);
        assertFalse(validator.validate(category).isEmpty());
    }

    @Test
    void levelAboveThreeIsRejected() {
        Category category = newValidCategory();
        category.setLevel(4);
        assertFalse(validator.validate(category).isEmpty());
    }

    @Test
    void levelThreeIsAccepted() {
        Category category = newValidCategory();
        category.setLevel(3);
        assertTrue(validator.validate(category).isEmpty());
    }
}
