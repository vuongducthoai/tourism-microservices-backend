package com.tourism.forum.service;

import com.tourism.forum.dto.moderation.ModerationResult;

public interface ModerationService {
    ModerationResult analyze(String content, String contentType);
}
