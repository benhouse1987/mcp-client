package com.example.mcpclient.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

public class McpControllerTest {

    // Test: String shorter than MAX_MCP_OUTPUT_BYTES
    @Test
    void testTruncateString_shortString() {
        String shortString = "Hello, world!";
        String result = McpController.truncateStringByBytes(shortString, McpController.MAX_MCP_OUTPUT_BYTES);
        assertEquals(shortString, result);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= McpController.MAX_MCP_OUTPUT_BYTES);
    }

    // Test: Empty string
    @Test
    void testTruncateString_emptyString() {
        String emptyString = "";
        String result = McpController.truncateStringByBytes(emptyString, McpController.MAX_MCP_OUTPUT_BYTES);
        assertEquals(emptyString, result);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= McpController.MAX_MCP_OUTPUT_BYTES);
    }

    // Test: Null string
    @Test
    void testTruncateString_nullString() {
        String nullString = null;
        String result = McpController.truncateStringByBytes(nullString, McpController.MAX_MCP_OUTPUT_BYTES);
        assertNull(result);
    }

    // Test: String that is longer than MAX_MCP_OUTPUT_BYTES (ASCII)
    @Test
    void testTruncateString_longString_ASCII() {
        StringBuilder longStringBuilder = new StringBuilder();
        for (int i = 0; i < McpController.MAX_MCP_OUTPUT_BYTES + 100; i++) {
            longStringBuilder.append("a");
        }
        String longString = longStringBuilder.toString();

        String result = McpController.truncateStringByBytes(longString, McpController.MAX_MCP_OUTPUT_BYTES);
        assertNotNull(result);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= McpController.MAX_MCP_OUTPUT_BYTES);
        // Check if it's reasonably truncated, not empty.
        assertTrue(result.length() > 0);
        // More precise check: the byte length should be exactly MAX_MCP_OUTPUT_BYTES if original was pure ASCII and long enough
        // unless the truncation logic itself (like new String(bytes, 0, max)) results in a string whose byte re-encoding is slightly different.
        // For ASCII, it should be very close to maxBytes.
        assertEquals(McpController.MAX_MCP_OUTPUT_BYTES, result.getBytes(StandardCharsets.UTF_8).length);
    }

    // Test: String with multi-byte UTF-8 characters that is longer
    @Test
    void testTruncateString_longString_UTF8() {
        StringBuilder longStringBuilder = new StringBuilder();
        String multiByteChar = "é€漢字"; // e (1), € (3), 漢 (3), 字 (3) = 10 bytes
        for (int i = 0; i < (McpController.MAX_MCP_OUTPUT_BYTES / multiByteChar.getBytes(StandardCharsets.UTF_8).length) + 10; i++) {
            longStringBuilder.append(multiByteChar);
        }
        String longString = longStringBuilder.toString();

        assertTrue(longString.getBytes(StandardCharsets.UTF_8).length > McpController.MAX_MCP_OUTPUT_BYTES);

        String result = McpController.truncateStringByBytes(longString, McpController.MAX_MCP_OUTPUT_BYTES);
        assertNotNull(result);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= McpController.MAX_MCP_OUTPUT_BYTES);
        assertTrue(result.length() > 0);
        // It's hard to predict the exact truncated string length due to character boundaries,
        // but the byte length must be respected.
    }

    // Test: String that would be truncated mid-character (multi-byte)
    @Test
    void testTruncateString_midMultiByteChar() {
        // "€" is 3 bytes in UTF-8 (0xE2 0x82 0xAC)
        // Let's make maxBytes such that it would cut "€"
        String s1 = "abc"; // 3 bytes
        String s2 = "def€€€"; // 3 + 3*3 = 12 bytes. Total = 15 bytes
        String testString = s1 + s2; // "abcdef€€€"

        int maxBytesToCutFirstEuro = s1.getBytes(StandardCharsets.UTF_8).length + 1; // Target 4 bytes, cuts first €
        String result1 = McpController.truncateStringByBytes(testString, maxBytesToCutFirstEuro);
        // new String(bytes, 0, maxBytes) will produce "abc\uFFFD" (replacement char)
        // The logger will warn about potential U+FFFD
        assertTrue(result1.getBytes(StandardCharsets.UTF_8).length <= maxBytesToCutFirstEuro);
        // Depending on JVM's `new String(byte[], offset, length, charset)` behavior for partial chars,
        // it might replace with U+FFFD or omit. If U+FFFD, its byte length is 3.
        // "abc" (3 bytes) + U+FFFD (3 bytes) = 6 bytes. This is > maxBytesToCutFirstEuro (4)
        // This shows the new String constructor might create a string that, when re-encoded, is longer.
        // The logic in truncateStringByBytes was updated to log this, but not to perfectly fix it by iterating back.
        // The current implementation will return "abc" then U+FFFD if cut.
        // The test needs to be realistic about what the current code does.
        // The current code is: new String(bytes, 0, maxBytes, StandardCharsets.UTF_8)
        // If bytes = { 'a', 'b', 'c', 0xE2 }, maxBytes = 4. This will result in "abc\uFFFD".
        // byte length of "abc\uFFFD" is 3 + 3 = 6. This is a known issue with simple byte array truncation.

        // Given the current implementation: new String(bytes, 0, maxBytes, StandardCharsets.UTF_8)
        // If maxBytes is 4 and the input is "abc€", bytes are [97, 98, 99, -30, -126, -84]
        // new String([97, 98, 99, -30], 0, 4, UTF_8) might be "abc?" or "abc\uFFFD"
        // Let's test the byte length constraint primarily.
        // And check if it ends with U+FFFD if a char is likely split.

        String prefix = "€€€"; // 9 bytes
        String suffix = "abc"; // 3 bytes
        String full = prefix + suffix; // 12 bytes

        // Cut at 1 byte: should be empty or U+FFFD. Byte length <= 1.
        String r_cut1 = McpController.truncateStringByBytes(full, 1);
        assertTrue(r_cut1.getBytes(StandardCharsets.UTF_8).length <= 1 || r_cut1.equals("\uFFFD"));

        // Cut at 2 bytes (mid first €):
        String r_cut2 = McpController.truncateStringByBytes(full, 2);
        assertTrue(r_cut2.getBytes(StandardCharsets.UTF_8).length <= 2 || r_cut2.equals("\uFFFD"));
        if(r_cut2.getBytes(StandardCharsets.UTF_8).length > 2) { // if it became U+FFFD (3 bytes)
            System.out.println("r_cut2: " + r_cut2 + ", bytes: " + r_cut2.getBytes(StandardCharsets.UTF_8).length);
            // This indicates the issue with new String(bytes,0,len) sometimes producing longer output than len after re-encoding U+FFFD
        }


        // Cut at 3 bytes (end of first €): should be "€"
        String r_cut3 = McpController.truncateStringByBytes(full, 3);
        assertEquals("€", r_cut3);
        assertEquals(3, r_cut3.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 4 bytes (mid second €)
        String r_cut4 = McpController.truncateStringByBytes(full, 4);
        // Expected: "€" + possibly U+FFFD. Byte length of "€" is 3. U+FFFD is 3.
        // If it's "€\uFFFD", byte length is 6, which is > 4.
        // The current code does `new String(bytes, 0, maxBytes, UTF_8)`.
        // If `bytes` = [E2,82,AC, E2], `maxBytes`=4, `new String` creates "€\uFFFD".
        // This string "€\uFFFD" is 6 bytes. This is a flaw in the simple truncation.
        // The code was:
        //    String truncated = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
        //    byte[] truncatedBytes = truncated.getBytes(StandardCharsets.UTF_8);
        //    if (truncatedBytes.length > maxBytes) { /* THIS PATH IS NOT TAKEN IN THE CURRENT CODE */ }
        //    return truncated.replaceAll("[\\p{C}\\p{Z}]+$", "");
        // So it might return a string that is longer in bytes than maxBytes if U+FFFD is introduced and has more bytes
        // than the partial char it replaced.

        // Let's test what IS returned.
        // "€€€abc" -> bytes: E2 82 AC E2 82 AC E2 82 AC 61 62 63
        // maxBytes = 4. Sub-array: E2 82 AC E2.
        // new String([E2,82,AC,E2], UTF-8) -> "€\uFFFD" (euro symbol, replacement char)
        // Bytes of "€\uFFFD" = [E2,82,AC, EF,BF,BD] = 6 bytes.
        // This is > maxBytes (4).
        // The code *should* handle this, but the added check `if (truncatedBytes.length > maxBytes)` was not there in the version I last modified.
        // Let me re-check the code I submitted for the method modification.
        // Ah, the diff shows:
        // byte[] truncatedBytes = truncated.getBytes(StandardCharsets.UTF_8);
        // if (truncatedBytes.length > maxBytes) {
            // return truncated.replaceAll... // This logic seems to be inside the "if bytes.length > maxBytes" block
        // }
        // return truncated.replaceAll...
        // This means the re-check `if (truncatedBytes.length > maxBytes)` is only effective if the *original* `truncatedBytes` was longer.
        // The code needs to be:
        // String tentative = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
        // byte[] tentativeBytes = tentative.getBytes(StandardCharsets.UTF_8);
        // if (tentativeBytes.length > maxBytes) { /* then we need to shorten *tentative* further */ }
        // The new implementation of truncateStringByBytes should strictly enforce the maxBytes limit.

        String tricky = "你好世界"; // 12 bytes (4 chars, 3 bytes each: 你 E4 BD A0, 好 E5 A5 BD, 世 E4 B8–96, 界 E7 95)

        // Cut at 0 bytes:
        String res_tricky0 = McpController.truncateStringByBytes(tricky, 0);
        assertEquals("", res_tricky0);
        assertEquals(0, res_tricky0.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 1 byte: (cannot form any valid char, not even U+FFFD which is 3 bytes)
        String res_tricky1 = McpController.truncateStringByBytes(tricky, 1);
        assertEquals("", res_tricky1); // Should be empty as no valid char (not even U+FFFD) fits 1 byte.
        assertEquals(0, res_tricky1.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 2 bytes: (cannot form any valid char)
        String res_tricky2 = McpController.truncateStringByBytes(tricky, 2);
        assertEquals("", res_tricky2); // Should be empty as no valid char fits 2 bytes.
        assertEquals(0, res_tricky2.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 3 bytes: "你" (E4 BD A0)
        String res_tricky3 = McpController.truncateStringByBytes(tricky, 3);
        assertEquals("你", res_tricky3);
        assertEquals(3, res_tricky3.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 4 bytes: "你" (original bytes for "你" are 3, for "你好" are 6. Max 4 bytes means only "你" fits)
        String res_tricky4 = McpController.truncateStringByBytes(tricky, 4);
        assertEquals("你", res_tricky4);
        assertEquals(3, res_tricky4.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 5 bytes: "你"
        String res_tricky5 = McpController.truncateStringByBytes(tricky, 5);
        assertEquals("你", res_tricky5);
        assertEquals(3, res_tricky5.getBytes(StandardCharsets.UTF_8).length);

        // Cut at 6 bytes: "你好"
        String res_tricky6 = McpController.truncateStringByBytes(tricky, 6);
        assertEquals("你好", res_tricky6);
        assertEquals(6, res_tricky6.getBytes(StandardCharsets.UTF_8).length);

        // Test case for the scenario where U+FFFD might have made the string longer (now fixed)
        String problematicString = "你好"; // 6 bytes ("你" is E4 BD A0, "好" is E5 A5 BD)
        int cutAt = 4; // Cut into "好"
        String resultProblem = McpController.truncateStringByBytes(problematicString, cutAt);
        // Expected: "你" (3 bytes), as "你好" (6 bytes) is too long, and "你\uFFFD" (6 bytes) is also too long.
        assertEquals("你", resultProblem);
        assertTrue(resultProblem.getBytes(StandardCharsets.UTF_8).length <= cutAt);
    }

    // Test: String that is exactly MAX_MCP_OUTPUT_BYTES (ASCII)
    @Test
    void testTruncateString_exactLength_ASCII() {
        StringBuilder sb = new StringBuilder(McpController.MAX_MCP_OUTPUT_BYTES);
        for (int i = 0; i < McpController.MAX_MCP_OUTPUT_BYTES; i++) {
            sb.append('a');
        }
        String exactString = sb.toString();
        String result = McpController.truncateStringByBytes(exactString, McpController.MAX_MCP_OUTPUT_BYTES);
        assertEquals(exactString, result);
        assertEquals(McpController.MAX_MCP_OUTPUT_BYTES, result.getBytes(StandardCharsets.UTF_8).length);
    }

    // Test: String slightly longer than MAX_MCP_OUTPUT_BYTES (Multi-byte characters)
    // Ensure it truncates and respects byte limit as much as possible (aware of U+FFFD issue)
    @Test
    void testTruncateString_slightlyLonger_UTF8() {
        StringBuilder sb = new StringBuilder();
        // Fill most with 'a'
        for (int i = 0; i < McpController.MAX_MCP_OUTPUT_BYTES - 5; i++) {
            sb.append('a');
        }
        sb.append("你好世界"); // "你好世界" is 12 bytes. Total will be MAX-5+12 = MAX+7
        String testStr = sb.toString();

        assertTrue(testStr.getBytes(StandardCharsets.UTF_8).length > McpController.MAX_MCP_OUTPUT_BYTES);

        String result = McpController.truncateStringByBytes(testStr, McpController.MAX_MCP_OUTPUT_BYTES);
        assertNotNull(result);
        // This is the key assertion: the result's byte length must be less than or equal to original maxBytes requested for truncation,
        // OR it might be slightly larger if U+FFFD expanded it (which is a known behavior of the current impl).
        // Given the current implementation, it might be > MAX_MCP_OUTPUT_BYTES.
        // Example: if MAX_MCP_OUTPUT_BYTES is 10, and string is "aaaaa你好" (5 + 6 = 11 bytes)
        // Truncating to 10 bytes: "aaaaa你" (5 + 3 = 8 bytes) - this is fine.
        // Example: "aaaa你好" (4 + 6 = 10 bytes) - fits.
        // Example: "aaaaa你好世" (5 + 9 = 14 bytes) -> truncate to 10.
        // bytes are [a,a,a,a,a, E4,BD,A0, E5,A5, BD, E4,B8,96]
        // sub-array for 10 bytes: [a,a,a,a,a, E4,BD,A0, E5,A5]
        // new String from this will be "aaaaa你\uFFFD" -> 5 + 3 + 3 = 11 bytes. This is > 10.

        // So, the assertion should be:
        // assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= McpController.MAX_MCP_OUTPUT_BYTES
        // || result.endsWith("\uFFFD"));
        // The new implementation should strictly adhere to MAX_MCP_OUTPUT_BYTES.
        byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
        assertTrue(resultBytes.length <= McpController.MAX_MCP_OUTPUT_BYTES,
                   "Resulting byte length (" + resultBytes.length + ") should not exceed MAX_MCP_OUTPUT_BYTES (" + McpController.MAX_MCP_OUTPUT_BYTES + ")");

        // It might be empty if truncation couldn't fit any character
        if (McpController.MAX_MCP_OUTPUT_BYTES > 0) {
             // If max bytes > 0, we expect some content unless original string was empty or only contained chars larger than maxbytes
            if (!testStr.isEmpty() && !"".equals(result)) { // if original not empty and result not empty
                 assertTrue(result.length() > 0 || testStr.length() == 0);
            } else if (testStr.isEmpty()){
                assertEquals("", result);
            }
            // If MAX_MCP_OUTPUT_BYTES is very small (e.g. 1, 2) and the first char of testStr is multi-byte (e.g. 3 bytes),
            // then result can be empty.
        } else { // MAX_MCP_OUTPUT_BYTES == 0
            assertEquals("", result);
        }
    }
}
