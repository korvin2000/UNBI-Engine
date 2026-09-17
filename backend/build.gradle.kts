plugins {
    java
    alias(libs.plugins.spring.boot)
}

version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt()) }
}

// META-INF/build-info.properties, so the engine can say which version it is on the settings page.
springBoot {
    buildInfo()
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.validation)
    // Settings bundles: a .ucfg is a zip, AES-256 when password-protected, so 7-Zip can open it too.
    implementation(libs.zip4j)
    implementation(libs.spring.security.oauth2.client)
    implementation(libs.jackson.yaml)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // `-parameters` lets Jackson bind record components without @JsonProperty on every field.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
