package app.freespacewiper.engine;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces the junk data that gets written. One instance per file and per thread (not thread safe).
 */
public abstract class DataSource {

    private static final SecureRandom SEED = new SecureRandom();
    /** Read-only cipher input shared by all streams; never written, so sharing across threads is safe. */
    private static byte[] sharedZeros = new byte[0];

    private static synchronized byte[] zeros(int len) {
        if (sharedZeros.length < len) sharedZeros = new byte[len];
        return sharedZeros;
    }

    /** Fills {@code buf[0..len)} with the next bytes of the stream. */
    public abstract void fill(byte[] buf, int len);

    public static DataSource create(WipeMode mode, int maxLen) {
        if (mode == WipeMode.ZERO) return new Zero();
        try {
            return new AesCtr(maxLen);
        } catch (GeneralSecurityException e) {
            // Every Android release ships AES/CTR (Conscrypt); this is only a safety net.
            return new SecureRandomSource();
        }
    }

    /** Writes zeros. The buffer is never modified by write(2), so nothing needs to happen here. */
    static final class Zero extends DataSource {
        @Override
        public void fill(byte[] buf, int len) {
            // The engine hands every Zero source a buffer that it zeroed itself.
        }
    }

    /**
     * AES-256 in counter mode, encrypting a zero buffer: the output is the raw keystream.
     * Cryptographically strong pseudo-random data at several hundred MB/s to GB/s per core
     * on ARMv8 (AES instructions), versus tens of MB/s for SecureRandom.nextBytes.
     */
    static final class AesCtr extends DataSource {
        private final Cipher cipher;
        private final byte[] zeros;

        AesCtr(int maxLen) throws GeneralSecurityException {
            byte[] key = new byte[32];
            byte[] iv = new byte[16];
            SEED.nextBytes(key);
            SEED.nextBytes(iv);
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            zeros = zeros(maxLen);
        }

        @Override
        public void fill(byte[] buf, int len) {
            if (len > zeros.length) throw new IllegalArgumentException("len > maxLen");
            int done = 0;
            try {
                while (done < len) {
                    int n = cipher.update(zeros, 0, len - done, buf, done);
                    if (n <= 0) throw new IllegalStateException("cipher produced no output");
                    done += n;
                }
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class SecureRandomSource extends DataSource {
        private final SecureRandom random = new SecureRandom();
        private byte[] tmp = new byte[0];

        @Override
        public void fill(byte[] buf, int len) {
            if (len == buf.length) {
                random.nextBytes(buf);
                return;
            }
            if (tmp.length != len) tmp = new byte[len];
            random.nextBytes(tmp);
            System.arraycopy(tmp, 0, buf, 0, len);
        }
    }
}
