package app.downloads;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Expected hash of a download, e.g. the sha1/sha512 values published by Modrinth. */
public record Checksum(Algorithm algorithm, String hex) {

    public enum Algorithm {
        SHA1("SHA-1", 40),
        SHA256("SHA-256", 64),
        SHA512("SHA-512", 128);

        private final String javaName;
        private final int hexLength;

        Algorithm(String javaName, int hexLength) {
            this.javaName = javaName;
            this.hexLength = hexLength;
        }

        public String javaName() {
            return javaName;
        }

        public int hexLength() {
            return hexLength;
        }
    }

    public Checksum {
        if (algorithm == null || hex == null) throw new IllegalArgumentException("Checksum is incomplete.");
        hex = hex.trim().toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{" + algorithm.hexLength() + "}")) {
            throw new IllegalArgumentException("That is not a valid " + algorithm.javaName() + " checksum.");
        }
    }

    /** Guesses the algorithm from the length: 40 = SHA-1, 64 = SHA-256, 128 = SHA-512. */
    public static Checksum parse(String text) {
        String t = text == null ? "" : text.trim();
        for (Algorithm a : Algorithm.values()) {
            if (t.length() == a.hexLength()) return new Checksum(a, t);
        }
        throw new IllegalArgumentException(
                "The checksum must be a SHA-1 (40), SHA-256 (64) or SHA-512 (128 character) hex value.");
    }

    /** Streams the file through the hash function, so large files do not use much memory. */
    public static String compute(Algorithm algorithm, Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.javaName());
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm.javaName() + " is not available on this Java", e);
        }
    }
}
