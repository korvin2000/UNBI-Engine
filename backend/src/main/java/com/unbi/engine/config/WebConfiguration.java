package com.unbi.engine.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Explicit development origins; credential mutations additionally reject cross-origin browser posts. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    private final Set<String> origins;

    public WebConfiguration(@Value("${unbi.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins.clone();
        this.origins = Set.of(allowedOrigins);
        if (origins.stream().anyMatch(origin -> origin.contains("*")))
            throw new IllegalArgumentException("Credentialed CORS requires explicit origins, not wildcards");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins).allowedMethods("GET", "POST", "DELETE")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                response.setHeader("Cache-Control", "no-store");
                if (!request.getMethod().equals("POST") && !request.getMethod().equals("DELETE")) return true;
                var origin = request.getHeader("Origin");
                if (origin != null && !origins.contains(origin) && !sameOrigin(origin, request)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Credential management is not allowed from this origin");
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/api/credentials", "/api/credentials/**");
    }

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            var uri = URI.create(origin);
            if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty()) return false;
            var port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return uri.getScheme().equalsIgnoreCase(request.getScheme()) && uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(request.getServerName()) && port == request.getServerPort();
        } catch (RuntimeException invalid) { return false; }
    }
}
