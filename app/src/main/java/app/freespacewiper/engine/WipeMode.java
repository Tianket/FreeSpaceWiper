package app.freespacewiper.engine;

public enum WipeMode {
    /** AES-256-CTR keystream with a fresh random key per file: indistinguishable from random, hardware accelerated. */
    RANDOM,
    /** All zero bytes: fastest. On a phone with file-based encryption the flash still receives ciphertext. */
    ZERO;

    public static WipeMode parse(String s) {
        if (s != null) {
            for (WipeMode m : values()) {
                if (m.name().equals(s)) return m;
            }
        }
        return RANDOM;
    }
}
