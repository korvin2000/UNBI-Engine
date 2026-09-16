package com.unbi.engine.llm.spec;

import java.util.List;
import java.util.Locale;

/**
 * One turn of the conversation.
 *
 * @param cacheBreakpoint marks the end of the intended prompt-cache prefix. Providers cache on the
 *                        longest common token prefix, so a run over many documents pays for the
 *                        system prompt once — as long as nothing document-specific ever leaks in
 *                        front of this mark. Gateways without explicit breakpoints simply benefit
 *                        from the ordering it forces.
 */
public record ChatMessage(Role role, String text, List<Attachment> attachments, boolean cacheBreakpoint) {

    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("A message needs a role");
        }
        text = text == null ? "" : text;
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text, List.of(), false);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), false);
    }

    public static ChatMessage user(String text, List<Attachment> attachments) {
        return new ChatMessage(Role.USER, text, attachments, false);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text, List.of(), false);
    }

    public ChatMessage withCacheBreakpoint() {
        return new ChatMessage(role, text, attachments, true);
    }

    public boolean isEmpty() {
        return text.isBlank() && attachments.isEmpty();
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
