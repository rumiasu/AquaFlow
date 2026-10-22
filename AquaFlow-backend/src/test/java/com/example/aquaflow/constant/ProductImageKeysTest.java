package com.example.aquaflow.constant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductImageKeysTest {
    @Test
    void neutralPresetIsExplicitAndUnknownKeysStayUnresolved() {
        assertEquals("/assets/product/barrel-neutral-template.webp",
                ProductImageKeys.toPath("barrel-neutral-template"));
        assertNull(ProductImageKeys.toPath(null));
        assertNull(ProductImageKeys.toPath("pulisi-pure"));
        assertThrows(UnsupportedOperationException.class,
                () -> ProductImageKeys.all().put("unknown", "/wrong.webp"));
    }
}
