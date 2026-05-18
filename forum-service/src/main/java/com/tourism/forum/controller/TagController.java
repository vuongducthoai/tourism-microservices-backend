package com.tourism.forum.controller;

import com.tourism.forum.dto.response.PostListResponse;
import com.tourism.forum.service.ForumService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forum/tags")
@RequiredArgsConstructor
public class TagController {

    private final ForumService forumService;

    @GetMapping("/popular")
    public ResponseEntity<?> getPopularTags(
            @RequestParam(defaultValue = "15") int limit
    ) {
        List<PostListResponse.TagInfo> tags = forumService.getPopularTags(limit);
        return ResponseEntity.ok(Map.of("success", true, "data", tags));
    }
}
