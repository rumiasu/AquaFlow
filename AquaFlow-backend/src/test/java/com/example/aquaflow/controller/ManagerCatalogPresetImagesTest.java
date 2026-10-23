package com.example.aquaflow.controller;

import com.example.aquaflow.util.CosUtil;
import com.example.aquaflow.util.ProductImageResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ManagerCatalogPresetImagesTest {
    @Test
    void originalPhotosExposeStableKeysSeparatelyFromSignedPreviews() {
        CosUtil cos = mock(CosUtil.class);
        when(cos.generatePublicUrl(anyString())).thenAnswer(invocation ->
                "https://synthetic.example.test/" + invocation.getArgument(0) + "?signature=synthetic");
        ManagerCatalogController controller = new ManagerCatalogController();
        ReflectionTestUtils.setField(controller, "imageResolver", new ProductImageResolver(cos));

        var photos = controller.presetImages().getData();
        var pure = photos.stream().filter(row -> "pulisi-purified-photo".equals(row.get("key"))).findFirst().orElseThrow();
        assertEquals("catalog-sample-20261023-pulisi-purified-87bf702c165d.png", pure.get("objectName"));
        assertTrue(pure.get("path").endsWith("?signature=synthetic"));
        assertNotEquals(pure.get("path"), pure.get("objectName"));
        assertFalse(pure.get("objectName").startsWith("/"));

        var neutral = photos.stream().filter(row -> "barrel-neutral-template".equals(row.get("key"))).findFirst().orElseThrow();
        assertEquals("/assets/product/barrel-neutral-template.webp", neutral.get("path"));
        assertEquals("", neutral.get("objectName"));
        verify(cos, never()).generatePublicUrl("/assets/product/barrel-neutral-template.webp");
    }
}
