package app.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatsTest {

    @Test
    void formatsByteSizes() {
        assertEquals("?", Formats.bytes(-1));
        assertEquals("0 B", Formats.bytes(0));
        assertEquals("1023 B", Formats.bytes(1023));
        assertEquals("1.0 KB", Formats.bytes(1024));
        assertEquals("1.5 KB", Formats.bytes(1536));
        assertEquals("1.0 MB", Formats.bytes(1024L * 1024));
        assertEquals("150 MB", Formats.bytes(150L * 1024 * 1024));
    }

    @Test
    void formatsSpeedAndTime() {
        assertEquals("-", Formats.speed(0));
        assertEquals("2.0 MB/s", Formats.speed(2 * 1024 * 1024));
        assertEquals("-", Formats.eta(-1));
        assertEquals("42s", Formats.eta(42));
        assertEquals("1m 05s", Formats.eta(65));
        assertEquals("1h 01m", Formats.eta(3700));
    }
}
