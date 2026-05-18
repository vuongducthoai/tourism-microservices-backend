package com.tourism.forum.controller;

import com.tourism.forum.dto.response.CategoryResponse;
import com.tourism.forum.service.ForumService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forum/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ForumService forumService;

    @GetMapping
    public ResponseEntity<?> getAllCategories() {
        List<CategoryResponse> categories = forumService.getAllCategories();
        return ResponseEntity.ok(Map.of("success", true, "data", categories));
    }

    @GetMapping("/popular")
    public ResponseEntity<?> getPopularCategories(
            @RequestParam(defaultValue = "5") int limit
    ) {
        List<CategoryResponse> categories = forumService.getPopularCategories(limit);
        return ResponseEntity.ok(Map.of("success", true, "data", categories));
    }
}
