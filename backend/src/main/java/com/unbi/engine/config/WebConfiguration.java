package com.unbi.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access for the development setup, where Angular serves on 4200 and this on 8080.
 *
 * <p>Origins come from configuration rather than a wildcard so that a production build cannot
 * accidentally ship an open API: the property is what changes, not the code.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfiguration(@Value("${unbi.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins.clone();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins).allowedMethods("GET", "POST", "DELETE");
    }
}
