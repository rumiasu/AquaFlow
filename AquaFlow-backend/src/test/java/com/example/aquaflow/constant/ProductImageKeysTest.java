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

    @Test
    void verifiedOriginalsMatchExactStoredKeysAndKeepLegacyPackageLabels() {
        String[][] photos = {
                {"pulisi-purified-photo", "catalog-sample-20261023-pulisi-purified-87bf702c165d.png",
                        "https://www.pulisi.com/data/upload/image/20201207/1607307675540169.png", "pulisi-mineral.webp"},
                {"pulisi-spring-photo", "catalog-sample-20261023-pulisi-spring-f576447ab103.png",
                        "https://www.pulisi.com/data/upload/image/20200928/1601258020255537.png", "pulisi-spring.webp"},
                {"pulisi-mineral-photo", "catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png",
                        "https://www.pulisi.com/data/upload/image/20200928/1601257945414406.png", "pulisi-pure.webp"}
        };
        for (String[] photo : photos) {
            assertEquals(photo[1], ProductImageKeys.toPath(photo[0]), "预设仍保留稳定对象键");
            assertEquals(photo[2], ProductImageKeys.verifiedPublicOriginal(photo[1]));
            assertEquals("/assets/product/" + photo[3], ProductImageKeys.localFallback(photo[1]));
            assertNull(ProductImageKeys.verifiedPublicOriginal(photo[1] + ".other"), "不能按前缀猜图");
        }
        assertNull(ProductImageKeys.verifiedPublicOriginal(null));
        assertNull(ProductImageKeys.verifiedPublicOriginal(""));
        assertNull(ProductImageKeys.verifiedPublicOriginal("product/another-pulisi-photo.png"));
        assertNull(ProductImageKeys.localFallback("product/another-pulisi-photo.png"));
    }
}
