package com.agent.auth;

// 401 状态码异常，表示用户未登录或登录过期
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);// 构造函数，设置异常信息
    }
}
