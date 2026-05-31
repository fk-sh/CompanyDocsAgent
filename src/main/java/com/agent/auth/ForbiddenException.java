package com.agent.auth;

// 403 �码异常，表示用户没有权限访问资源，已登录但无权限，比如不是管理员
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
