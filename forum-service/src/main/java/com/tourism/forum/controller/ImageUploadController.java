package com.tourism.forum.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/forum/upload")
@RequiredArgsConstructor
public class ImageUploadController {

    private final Cloudinary cloudinary;

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap("folder", "tourism_app_tours/forum_images")
            );
            String imageUrl = (String) result.get("secure_url");
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of("imageUrl", imageUrl)
            ));
        } catch (Exception e) {
            log.error("Image upload failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Upload ảnh thất bại: " + e.getMessage()));
        }
    }
}
