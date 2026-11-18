package com.example.aquaflow.controller;

import com.example.aquaflow.util.CosUtil;
import com.example.aquaflow.util.ProductImageResolver;
import com.example.aquaflow.mapper.ProductImageLibraryMapper;
import com.example.aquaflow.service.impl.CatalogServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerCatalogPresetImagesTest {
    @Test
    void verifiedOriginalsExposePermanentPreviewsWithoutChangingStoredKeys() {
        CosUtil cos = mock(CosUtil.class);
        ManagerCatalogController controller = new ManagerCatalogController();
        CatalogServiceImpl catalog = new CatalogServiceImpl();
        ReflectionTestUtils.setField(controller, "catalogService", catalog);
        ReflectionTestUtils.setField(catalog, "imageResolver", new ProductImageResolver(cos));
        ProductImageLibraryMapper library = mock(ProductImageLibraryMapper.class);
        ReflectionTestUtils.setField(catalog, "imageLibrary", library);

        String[][] expected = {
                {"pulisi-purified-photo", "catalog-sample-20261023-pulisi-purified-87bf702c165d.png",
                        "https://www.pulisi.com/data/upload/image/20201207/1607307675540169.png"},
                {"pulisi-spring-photo", "catalog-sample-20261023-pulisi-spring-f576447ab103.png",
                        "https://www.pulisi.com/data/upload/image/20200928/1601258020255537.png"},
                {"pulisi-mineral-photo", "catalog-sample-20261023-pulisi-mineral-cef12b0ef29a.png",
                        "https://www.pulisi.com/data/upload/image/20200928/1601257945414406.png"}
        };
        when(library.listPublished()).thenReturn(List.of(
                new ProductImageLibraryMapper.Photo("barrel-neutral-template", "桶装水示意图", "/assets/product/barrel-neutral-template.webp"),
                new ProductImageLibraryMapper.Photo(expected[0][0], "纯净水", expected[0][1]),
                new ProductImageLibraryMapper.Photo(expected[1][0], "天然泉水", expected[1][1]),
                new ProductImageLibraryMapper.Photo(expected[2][0], "天然矿泉水", expected[2][1])));
        for (boolean configured : new boolean[] {true, false}) {
            when(cos.isCredentialsConfigured()).thenReturn(configured);
            var photos = controller.presetImages().getData();
            for (String[] photo : expected) {
                var row = photos.stream().filter(item -> photo[0].equals(item.get("key"))).findFirst().orElseThrow();
                assertEquals(photo[1], row.get("objectName"), "已有COS对象键不能被预览地址覆盖");
                assertEquals(photo[2], row.get("path"), "COS配置状态不能影响已核原图预览");
                assertNotEquals(row.get("objectName"), row.get("path"));
            }
            var neutral = photos.stream().filter(row -> "barrel-neutral-template".equals(row.get("key"))).findFirst().orElseThrow();
            assertEquals("/assets/product/barrel-neutral-template.webp", neutral.get("path"));
            assertEquals("", neutral.get("objectName"));
        }
        assertNull(new ProductImageResolver(cos).resolve("unknown/real-upload.png"), "未知对象仍明确无图，不能猜路径");
        verify(cos, never()).generatePublicUrl(anyString());
    }
}
