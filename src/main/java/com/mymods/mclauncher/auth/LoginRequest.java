package com.mymods.mclauncher.auth;

import java.util.Map;

/**
 * What the UI must collect before a provider can hand out a session.
 *
 * @param providerId one of the ids in {@link AuthProviderInfo}
 * @param fields ordered form fields; empty means the provider needs a browser/device code only
 * @param notes extra text to show under the form, e.g. an explanation of 2FA handling
 */
public record LoginRequest(String providerId, Map<String, String> fields, String notes) {

    /** Signals that the user cancelled, e.g. closed the device code window. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** Signals bad credentials or a blocked account. */
    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * Signals that the provider needs one more piece of input before it can continue,
     * for example an Ely TOTP code. The UI shows {@code fields} and calls {@link Authenticator#login}
     * again with the extra values merged in.
     */
    public static final class NeedsMoreInput extends Exception {
        private final String message;
        private final Map<String, String> fields;

        public NeedsMoreInput(String message, Map<String, String> fields) {
            super(message);
            this.message = message;
            this.fields = fields;
        }

        public Map<String, String> fields() {
            return fields;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}