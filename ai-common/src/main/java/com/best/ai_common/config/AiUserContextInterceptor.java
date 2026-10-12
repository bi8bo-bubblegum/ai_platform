package com.best.ai_common.config;

import com.best.ai_common.context.AiUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public class AiUserContextInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final String USER_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userId = request.getHeader(USER_HEADER);
        AiUserContext.set(userId == null || userId.isBlank() ? AiUserContext.ANONYMOUS : userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        AiUserContext.clear();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(this).addPathPatterns("/**");
    }
}
