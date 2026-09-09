// Root build: intentionally thin. Each subproject configures itself, which keeps
// Gradle's configuration cache happy and avoids cross-project configuration.
plugins {
    alias(libs.plugins.spring.boot) apply false
}
