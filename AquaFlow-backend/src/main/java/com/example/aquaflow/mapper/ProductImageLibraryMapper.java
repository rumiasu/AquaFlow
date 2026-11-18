package com.example.aquaflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** The station picker reads only the platform's published images. */
@Mapper
public interface ProductImageLibraryMapper {
    record Photo(String imageKey, String label, String imageRef) {}

    @Select("""
            SELECT image_key AS imageKey, label, image_ref AS imageRef
            FROM product_image_library
            WHERE published = 1
            ORDER BY sort_order, image_key
            """)
    List<Photo> listPublished();
}
