import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Second, independent derivation of the Claude P conformance vectors.
 *
 * Why this file exists: the vectors must be reproducible from the frozen implementation
 * rather than typed out by hand and then "verified" against themselves. This program
 * mirrors the Kotlin algorithms on the JVM — the same runtime the Android code runs on,
 * and therefore the same `String.length` (UTF-16 code units), the same
 * `getBytes(UTF_8)`, and the same `MessageDigest`. Its output is compared against
 * `gen-vectors.mjs`, an independent implementation in a different language. Two
 * implementations agreeing on the same bytes is evidence; one implementation agreeing
 * with itself is not.
 *
 * This is NOT a substitute for the Android-side test, which runs the real Kotlin
 * functions. It is the strongest check available without an Android SDK, and it is
 * deliberately written from the Kotlin source (cited per algorithm) rather than from
 * the protocol document.
 *
 * Emits TSV on stdout: `<caseId>\t<hex>` for transcripts, `<caseId>\t<sha256hex>` for
 * fingerprints, in three sections separated by a line containing `--`.
 *
 * Compile: javac -encoding UTF-8 VerifyVectors.java
 * Run:     java -Dfile.encoding=UTF-8 VerifyVectors
 */
public final class VerifyVectors {

    // -----------------------------------------------------------------------------------------
    // Mirrors ClaudePDeviceIdentity.kt:232-256
    // -----------------------------------------------------------------------------------------

    private static final String HANDSHAKE_DOMAIN = "rikkahub-claude-p-handshake-v1";

    /**
     * Kotlin: `"${value.length}:$value"`. `String.length()` is UTF-16 code units, so an
     * astral character contributes 2 and the following UTF-8 encoding contributes 4 bytes.
     */
    private static String kotlinLengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private static String handshakeTranscript(
            String deviceId, String nonce, String gatewayAuthority, String appVersion) {
        String joined = kotlinLengthPrefixed(HANDSHAKE_DOMAIN)
                + kotlinLengthPrefixed(deviceId)
                + kotlinLengthPrefixed(nonce)
                + kotlinLengthPrefixed(gatewayAuthority)
                + kotlinLengthPrefixed(appVersion);
        return toHex(joined.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------------
    // Mirrors ClaudePPairingTransport.kt:113-135
    // -----------------------------------------------------------------------------------------

    private static final String PAIRING_DOMAIN = "rikkahub-claude-p-pairing-v1";

    private static String pairingTranscript(
            String origin, String ticket, String devicePublicKeyBase64Url,
            String state, String challenge, String appVersion) {
        String joined = kotlinLengthPrefixed(PAIRING_DOMAIN)
                + kotlinLengthPrefixed(origin)
                + kotlinLengthPrefixed(ticket)
                + kotlinLengthPrefixed(devicePublicKeyBase64Url)
                + kotlinLengthPrefixed(state)
                + kotlinLengthPrefixed(challenge)
                + kotlinLengthPrefixed(appVersion);
        return toHex(joined.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------------
    // Mirrors ClaudePGatewayClient.kt:153-219
    // -----------------------------------------------------------------------------------------

    private static final String FINGERPRINT_DOMAIN = "rikkahub-claude-p-request-fingerprint-v1";

    private static final class TurnPart {
        final String type;
        final String text;
        TurnPart(String type, String text) { this.type = type; this.text = text; }
    }

    private static final class Turn {
        final String role;
        final List<TurnPart> parts;
        Turn(String role, List<TurnPart> parts) { this.role = role; this.parts = parts; }
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] bytes) {
        int size = bytes.length;
        digest.update((byte) (size >>> 24));
        digest.update((byte) (size >>> 16));
        digest.update((byte) (size >>> 8));
        digest.update((byte) size);
        digest.update(bytes);
    }

    private static String requestFingerprint(
            String deviceId, String remoteThreadId, String remoteBranchId,
            String mode, String modelAlias, String systemPrompt,
            Turn turn, List<Turn> rebuildHistory, String toolSnapshot,
            String attachmentManifest) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        // `field(label, value)`: label is always length-prefixed and written; the value is
        // preceded by a presence byte, and only written when present.
        record FieldWriter(MessageDigest d) {
            void field(String label, String value) {
                updateLengthPrefixed(d, label.getBytes(StandardCharsets.UTF_8));
                if (value == null) {
                    d.update((byte) 0);
                } else {
                    d.update((byte) 1);
                    updateLengthPrefixed(d, value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        FieldWriter fw = new FieldWriter(digest);

        fw.field("domain", FINGERPRINT_DOMAIN);
        fw.field("device_id", deviceId);
        fw.field("remote_thread_id", remoteThreadId);
        fw.field("remote_branch_id", remoteBranchId);
        fw.field("mode", mode);
        fw.field("model_alias", modelAlias);
        fw.field("system_prompt", systemPrompt);
        fw.field("turn_role", turn.role);
        fw.field("turn_parts", Integer.toString(turn.parts.size()));
        for (int i = 0; i < turn.parts.size(); i++) {
            TurnPart part = turn.parts.get(i);
            fw.field("turn_part_" + i + ".type", part.type);
            fw.field("turn_part_" + i + ".text", part.text);
        }
        List<Turn> history = rebuildHistory == null ? List.of() : rebuildHistory;
        fw.field("rebuild_history_turns", Integer.toString(history.size()));
        for (int i = 0; i < history.size(); i++) {
            Turn entry = history.get(i);
            fw.field("rebuild_" + i + ".role", entry.role);
            for (int j = 0; j < entry.parts.size(); j++) {
                TurnPart part = entry.parts.get(j);
                fw.field("rebuild_" + i + "." + j + ".type", part.type);
                fw.field("rebuild_" + i + "." + j + ".text", part.text);
            }
        }
        fw.field("tool_snapshot", toolSnapshot);
        fw.field("attachment_manifest", attachmentManifest);

        return toHex(digest.digest());
    }

    // -----------------------------------------------------------------------------------------
    // Inputs — identical to gen-vectors.mjs. A divergence in inputs would make the
    // comparison meaningless, so they are kept in the same order and with the same values.
    // -----------------------------------------------------------------------------------------

    private static List<TurnPart> parts(String... pairs) {
        List<TurnPart> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) list.add(new TurnPart(pairs[i], pairs[i + 1]));
        return list;
    }

    private static String hexHandshake(String id, String deviceId, String nonce,
            String authority, String appVersion) {
        return id + "\t" + handshakeTranscript(deviceId, nonce, authority, appVersion);
    }

    private static String hexPairing(String id, String origin, String ticket, String key,
            String state, String challenge, String appVersion) {
        return id + "\t" + pairingTranscript(origin, ticket, key, state, challenge, appVersion);
    }

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();

        // --- handshake transcripts ---
        sb.append(hexHandshake("handshake-ascii", "dev-01", "n0nce-abc",
                "gateway.example.com", "1.0.0")).append('\n');
        sb.append(hexHandshake("handshake-multibyte-unicode", "设备一号", "随机数",
                "网关.example.com", "1.0.0-中文")).append('\n');
        sb.append(hexHandshake("handshake-astral-emoji", "dev-😀", "🔐🔐",
                "gateway.example.com", "1.0.0")).append('\n');
        sb.append(hexHandshake("handshake-empty-fields", "", "", "", "")).append('\n');
        sb.append(hexHandshake("handshake-ambiguity-guard", "ab", "c",
                "gateway.example.com", "1.0.0")).append('\n');
        sb.append(hexHandshake("handshake-ambiguity-guard-shifted", "a", "bc",
                "gateway.example.com", "1.0.0")).append('\n');

        sb.append("--\n");

        // --- pairing transcripts ---
        String key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE";
        sb.append(hexPairing("pairing-ascii", "https://gateway.example.com", "ticket-abc123",
                key, "state-xyz", "challenge-789", "1.0.0")).append('\n');
        sb.append(hexPairing("pairing-multibyte-unicode", "https://网关.example.com", "票据-一二三",
                key, "状态", "挑战", "1.0.0")).append('\n');
        sb.append(hexPairing("pairing-empty-ticket", "https://gateway.example.com", "",
                "", "", "", "")).append('\n');
        sb.append(hexPairing("pairing-field-order-binding", "https://gateway.example.com",
                "ticket-abc123", key, "challenge-789", "state-xyz", "1.0.0")).append('\n');

        sb.append("--\n");

        // --- request fingerprints ---
        Turn hello = new Turn("user", parts("text", "hello"));
        sb.append("fingerprint-minimal\t").append(requestFingerprint(
                "dev-01", "thread-01", "branch-01", "new", "sonnet",
                null, hello, null, null, null)).append('\n');

        sb.append("fingerprint-null-vs-empty-system-prompt\t").append(requestFingerprint(
                "dev-01", "thread-01", "branch-01", "new", "sonnet",
                "", hello, null, null, null)).append('\n');

        Turn again = new Turn("user", parts("text", "again"));
        List<Turn> history = List.of(
                new Turn("user", parts("text", "first")),
                new Turn("assistant", parts("text", "second-a", "text", "second-b")));
        sb.append("fingerprint-with-history\t").append(requestFingerprint(
                "dev-01", "thread-01", "branch-01", "new", "sonnet",
                "be brief", again, history, null, null)).append('\n');

        Turn unicodeTurn = new Turn("user", parts("text", "你好 🌏"));
        sb.append("fingerprint-unicode\t").append(requestFingerprint(
                "设备-01", "线程-01", "分支-01", "new", "sonnet",
                "请简短回答 😀", unicodeTurn, null, null, null)).append('\n');

        Turn emptyParts = new Turn("user", List.of());
        sb.append("fingerprint-empty-turn-parts\t").append(requestFingerprint(
                "dev-01", "thread-01", "branch-01", "new", "sonnet",
                null, emptyParts, null, null, null)).append('\n');

        Turn boundaryA = new Turn("user", parts("text", "ab", "text", "c"));
        sb.append("fingerprint-part-boundary\t").append(requestFingerprint(
                "dev-01", "thread-01", "branch-01", "new", "sonnet",
                null, boundaryA, null, null, null)).append('\n');

        Turn boundaryB = new Turn("user", parts("text", "a", "text", "bc"));
        sb.append("fingerprint-part-boundary-shifted\t").append(requestFingerprint(
                "dev-01", "thread-01", "branch-01", "new", "sonnet",
                null, boundaryB, null, null, null)).append('\n');

        System.out.print(sb);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
