package com.agent.auth;

//在一次 HTTP 请求处理过程中，把当前用户保存在线程上下文里，让 Controller / Service 随时可以取到当前用户。
public final class CurrentUserHolder {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();// 线程本地存储当前用户

    private CurrentUserHolder() {
    }

    public static void set(CurrentUser user) {// 设置当前用户
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {// 获取当前用户，如果不存在则抛出异常
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new UnauthorizedException("请先登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
