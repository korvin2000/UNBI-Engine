package com.unbi.engine.config;

import com.unbi.engine.core.type.TypeSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the parts of {@code core} that know nothing about Spring.
 *
 * <p>Keeping the domain classes annotation-free costs one small configuration class and buys the
 * ability to unit-test all of them without a container.
 */
@Configuration
public class EngineConfiguration {

    /** Shared because its assignability cache is worth reusing across every validation. */
    @Bean
    TypeSystem typeSystem() {
        return new TypeSystem();
    }
}
