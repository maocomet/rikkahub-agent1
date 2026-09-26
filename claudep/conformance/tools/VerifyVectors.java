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

    /**
     * Shared inputs for the v1-r4 M3 vectors — the same values as `AUTO_*` in gen-vectors.mjs.
     *
     * A divergence between the two files' constants would make the cross-check compare different
     * inputs, which is the one way this comparison could pass while proving nothing.
     */
    private static final String AUTO_BRANCH_A =
            "3f2a9c4e7b1d8056af3e21c9d0b47e6a5c8f1d2e3b4a59687766554433221100";
    private static final String AUTO_BRANCH_B =
            "0a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff";
    private static final String AUTO_ASSISTANT_A = "11111111-2222-3333-4444-555555555555";
    private static final String AUTO_ASSISTANT_B = "66666666-7777-8888-9999-aaaaaaaaaaaa";

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

    /**
     * Legacy (v1-r3) shape: no tail fields. Delegates rather than duplicating the body, so the
     * seven v1-r3 vectors below are computed by exactly the code that computes the r4 ones — if
     * the append rule were wrong, these would move and the corpus would say so.
     */
    private static String requestFingerprint(
            String deviceId, String remoteThreadId, String remoteBranchId,
            String mode, String modelAlias, String systemPrompt,
            Turn turn, List<Turn> rebuildHistory, String toolSnapshot,
            String attachmentManifest) throws Exception {
        return requestFingerprint(deviceId, remoteThreadId, remoteBranchId, mode, modelAlias,
                systemPrompt, turn, rebuildHistory, toolSnapshot, attachmentManifest, null, null);
    }

    /**
     * v1-r4 shape. `remoteBranchId`, `assistantId` and `bindingIntent` are all nullable;
     * `remoteBranchId`'s null is carried by the field's own presence byte at item 4, while the
     * two tail fields are appended **only when non-null** and write nothing when they are.
     */
    private static String requestFingerprint(
            String deviceId, String remoteThreadId, String remoteBranchId,
            String mode, String modelAlias, String systemPrompt,
            Turn turn, List<Turn> rebuildHistory, String toolSnapshot,
            String attachmentManifest, String assistantId, String bindingIntent)
            throws Exception {
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

        // Conditional tail fields (§12.4 items 16-17, §12.12). Appended, and **only when
        // present**: an absent one contributes no bytes at all, which is what leaves the seven
        // v1-r3 digests above untouched. Written with `if` rather than by passing null through,
        // because `field(label, null)` would emit a 0x00 presence byte and drift every r3 digest.
        if (assistantId != null) fw.field("assistant_id", assistantId);
        if (bindingIntent != null) fw.field("binding_intent", bindingIntent);

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

        // --- v1-r4 conditional tail fields (§12.4 items 16-17, §12.12) ---
        //
        // Each of these is defined by *which single field* differs from its sibling, so the
        // shared values are spelled once and reused. Two of them describe shapes §5.4 refuses;
        // they test the encoder, not the shape validator.

        Turn autoHello = new Turn("user", parts("text", "hello"));

        sb.append("fingerprint-auto-immediate\t").append(requestFingerprint(
                "dev-01", "thread-01", AUTO_BRANCH_A, "auto", "sonnet",
                null, autoHello, null, null, null,
                AUTO_ASSISTANT_A, "immediate")).append('\n');

        sb.append("fingerprint-auto-deferred\t").append(requestFingerprint(
                "dev-01", "thread-01", null, "auto", "sonnet",
                null, autoHello, null, null, null,
                AUTO_ASSISTANT_A, "deferred")).append('\n');

        // Encoder vector: auto + deferred + branch PRESENT (§5.4 row 6 refuses it). Differs from
        // fingerprint-auto-deferred in branch presence alone.
        sb.append("fingerprint-auto-branch-absent\t").append(requestFingerprint(
                "dev-01", "thread-01", AUTO_BRANCH_A, "auto", "sonnet",
                null, autoHello, null, null, null,
                AUTO_ASSISTANT_A, "deferred")).append('\n');

        sb.append("fingerprint-auto-second-branch\t").append(requestFingerprint(
                "dev-01", "thread-01", AUTO_BRANCH_B, "auto", "sonnet",
                null, autoHello, null, null, null,
                AUTO_ASSISTANT_A, "immediate")).append('\n');

        sb.append("fingerprint-auto-assistant-changed\t").append(requestFingerprint(
                "dev-01", "thread-01", AUTO_BRANCH_A, "auto", "sonnet",
                null, autoHello, null, null, null,
                AUTO_ASSISTANT_B, "immediate")).append('\n');

        // Encoder vector: auto + immediate + branch ABSENT (§5.4 row 5 refuses it). Differs from
        // fingerprint-auto-deferred in binding_intent alone.
        sb.append("fingerprint-auto-intent-changed\t").append(requestFingerprint(
                "dev-01", "thread-01", null, "auto", "sonnet",
                null, autoHello, null, null, null,
                AUTO_ASSISTANT_A, "immediate")).append('\n');

        System.out.print(sb);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
