package com.tourism.tourcatalog.dto.internal;

/**
 * Kết quả thô từ Groq API sau khi parse JSON.
 */
public record GroqSummaryResult(String pros, String cons, String tips, String model) {}
