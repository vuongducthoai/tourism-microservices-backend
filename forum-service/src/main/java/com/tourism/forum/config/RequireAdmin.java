package com.tourism.forum.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu endpoint chỉ cho phép role ADMIN truy cập (MODERATOR sẽ bị chặn).
 * Endpoint không có annotation này → cho cả MODERATOR và ADMIN.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
