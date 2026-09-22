#!/usr/bin/env node
/**
 * Writes the Claude P conformance corpus into `claudep/conformance/`.
 *
 * Run from this directory: `node build-corpus.mjs`
 *
 * Two classes of vector live in this corpus, and the difference matters when reading it:
 *
 * 1. **Byte vectors** (`transcripts/`, `fingerprints/`) are produced by `gen-vectors.mjs`
 *    and independently reconfirmed by `VerifyVectors.java`. Two implementations in two
 *    languages agree on every byte before anything is written. See `tools/README.md`.
 *
 * 2. **Routing vectors** (`frames/`, `expect/`) are derived by reading
 *    `ClaudePProtocol.parseInbound` and are NOT executed here — no Android SDK is
 *    available in this environment. They are the specification's expected behaviour as
 *    the frozen Kotlin reads it, and the Android-side test
 *    (`ClaudePConformanceCorpusTest`) is what actually holds the implementation to them.
 *    Each entry carries `derivedFrom` so this is never mistaken for executed evidence.
 *
 * The corpus is authoritative in `rikkahub-agent1`. The server repository vendors a
 * byte-identical copy and verifies `MANIFEST.sha256`; a mismatch must fail that build.
 */

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readdirSync, writeFileSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');

const SPEC_REVISION = {
  protocol_spec: 'claudep/02-wire-protocol-v1.md',
  protocol_spec_sha256: createHash('sha256')
    .update(readFileSync(join(ROOT, '..', '02-wire-protocol-v1.md')))
    .digest('hex'),
  trust_boundaries: 'claudep/01-architecture-and-trust-boundaries.md',
  trust_boundaries_sha256: createHash('sha256')
    .update(readFileSync(join(ROOT, '..', '01-architecture-and-trust-boundaries.md')))
    .digest('hex'),
  protocol_id: 'rikkahub.claude-p.v1',
  major_version: 1,
  android_corpus_origin: 'maocomet/rikkahub-agent1',
};

// ---------------------------------------------------------------------------------------------
// Byte vectors, from the dual-verified generator.
// ---------------------------------------------------------------------------------------------

const generated = JSON.parse(
  execFileSync(process.execPath, [join(HERE, 'gen-vectors.mjs')], { encoding: 'utf8' }),
);

// ---------------------------------------------------------------------------------------------
// Routing vectors.
//
// `expect.kind` is one of:
//   "event"           — routed to a typed event; `eventType` names it
//   "ignored_unknown" — a well-formed envelope whose type is not routed
//   "rejected"        — protocol violation; `reason` is a ClaudePParseRejection name
//
// Every `raw` is stored exactly as the bytes that would arrive on the wire. Several
// entries exist purely to pin ORDER-sensitivity, and their `note` says so.
// ---------------------------------------------------------------------------------------------

const frames = [
  // ---- envelope-level rejections -------------------------------------------------------
  {
    id: 'protocol-field-absent',
    note:
      'The `protocol` field is missing entirely. Because ClaudePEnvelope.protocol has no ' +
      'default, this fails JSON decoding and is rejected as MALFORMED_FRAME — it never ' +
      'reaches the MISSING_PROTOCOL branch. This is the anti-"assume v1" guard.',
    raw: '{"type":"server.hello","body":{}}',
    expect: { kind: 'rejected', reason: 'MALFORMED_FRAME' },
  },
  {
    id: 'protocol-field-empty',
    note:
      '`protocol` is present but empty. Decoding succeeds, so this DOES reach the ' +
      'MISSING_PROTOCOL branch — a distinct outcome from protocol-field-absent.',
    raw: '{"protocol":"","type":"server.hello","body":{}}',
    expect: { kind: 'rejected', reason: 'MISSING_PROTOCOL' },
  },
  {
    id: 'protocol-field-whitespace-only',
    note: '`protocol` is trimmed before the emptiness check, so whitespace is also MISSING_PROTOCOL.',
    raw: '{"protocol":"   ","type":"server.hello","body":{}}',
    expect: { kind: 'rejected', reason: 'MISSING_PROTOCOL' },
  },
  {
    id: 'protocol-malformed-unversioned',
    note: 'No `.v<major>` suffix, so majorVersionOf returns null → MALFORMED_PROTOCOL.',
    raw: '{"protocol":"rikkahub.claude-p","type":"server.hello","body":{}}',
    expect: { kind: 'rejected', reason: 'MALFORMED_PROTOCOL' },
  },
  {
    id: 'protocol-malformed-uppercase-family',
    note:
      'The family pattern is lowercase-only ([a-z0-9][a-z0-9.-]*), so an uppercase ' +
      'family does not match and is MALFORMED_PROTOCOL rather than a major mismatch.',
    raw: '{"protocol":"RikkaHub.claude-p.v1","type":"server.hello","body":{}}',
    expect: { kind: 'rejected', reason: 'MALFORMED_PROTOCOL' },
  },
  {
    id: 'protocol-major-mismatch-v2',
    note:
      'A known type under a different major. The major is checked BEFORE the type, so ' +
      'this is rejected rather than routed — a newer major may have changed what ' +
      '`server.hello` means.',
    raw: '{"protocol":"rikkahub.claude-p.v2","type":"server.hello","body":{}}',
    expect: { kind: 'rejected', reason: 'PROTOCOL_MAJOR_MISMATCH' },
  },
  {
    id: 'protocol-major-mismatch-unknown-type',
    note:
      'Both the major and the type are unfamiliar. Major wins: still ' +
      'PROTOCOL_MAJOR_MISMATCH, never ignored_unknown.',
    raw: '{"protocol":"rikkahub.claude-p.v9","type":"future.thing","body":{}}',
    expect: { kind: 'rejected', reason: 'PROTOCOL_MAJOR_MISMATCH' },
  },
  {
    id: 'malformed-json',
    note: 'Not JSON at all. The decoder exception may quote the offending text, so it is never surfaced.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":',
    expect: { kind: 'rejected', reason: 'MALFORMED_FRAME' },
  },
  {
    id: 'not-an-object',
    note: 'Valid JSON, wrong shape.',
    raw: '["rikkahub.claude-p.v1","server.hello"]',
    expect: { kind: 'rejected', reason: 'MALFORMED_FRAME' },
  },
  {
    id: 'type-field-absent',
    note:
      '`type` is absent. ClaudePEnvelope.type also has no default, so this fails ' +
      'decoding as MALFORMED_FRAME — NOT MISSING_TYPE.',
    raw: '{"protocol":"rikkahub.claude-p.v1","body":{}}',
    expect: { kind: 'rejected', reason: 'MALFORMED_FRAME' },
  },
  {
    id: 'type-field-empty',
    note: '`type` present but empty → MISSING_TYPE.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"","body":{}}',
    expect: { kind: 'rejected', reason: 'MISSING_TYPE' },
  },

  // ---- unknown optional events ---------------------------------------------------------
  {
    id: 'unknown-event-tool-requested',
    note:
      'A Phase 3 tool event. Unknown types are dropped, and dropping them can never ' +
      'produce text or end a generation — which is what makes forward compatibility safe.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"tool.requested","body":{"tool":"echo"}}',
    expect: { kind: 'ignored_unknown' },
  },
  {
    id: 'unknown-event-future-minor',
    note: 'A hypothetical event added by a newer minor of the same major.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"generation.progress","body":{}}',
    expect: { kind: 'ignored_unknown' },
  },
  {
    id: 'unknown-event-with-malformed-body',
    note:
      'Unknown type AND a body this build cannot parse. The type check happens first, ' +
      'so the body is never decoded and the frame is still merely ignored.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"future.event","body":{"nested":{"deep":[1,2,3]}}}',
    expect: { kind: 'ignored_unknown' },
  },

  // ---- known events with malformed bodies ----------------------------------------------
  {
    id: 'known-event-bad-body-type',
    note:
      '`server.hello` with a string where an integer is declared. The type is known, so ' +
      'the body IS decoded and the failure becomes MALFORMED_EVENT_BODY.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"server.hello","body":{"max_frame_bytes":"not-a-number"}}',
    expect: { kind: 'rejected', reason: 'MALFORMED_EVENT_BODY' },
  },
  {
    id: 'known-event-body-wrong-shape',
    note: '`catalog.result` expects `models` to be a list; an object is a schema violation.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"catalog.result","body":{"models":{"alias":"sonnet"}}}',
    expect: { kind: 'rejected', reason: 'MALFORMED_EVENT_BODY' },
  },

  // ---- well-formed known events --------------------------------------------------------
  {
    id: 'valid-server-hello',
    note: 'The handshake response. All body fields are optional, so an empty body is valid.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"server.hello","body":{"protocol_version":"v1","gateway_build":"c1","max_frame_bytes":262144}}',
    expect: { kind: 'event', eventType: 'server.hello' },
  },
  {
    id: 'valid-server-hello-empty-body',
    note: 'Every field of ClaudePServerHelloBody has a default, so `{}` decodes.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"server.hello","body":{}}',
    expect: { kind: 'event', eventType: 'server.hello' },
  },
  {
    id: 'valid-server-hello-absent-body',
    note: '`body` itself defaults to an empty object, so omitting it entirely also decodes.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"server.hello"}',
    expect: { kind: 'event', eventType: 'server.hello' },
  },
  {
    id: 'valid-text-delta',
    note: 'Content-carrying event. It is also the one that must never arrive after a terminal.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"text.delta","sequence":3,"body":{"message_id":"m1","index":0,"text":"hello"}}',
    expect: { kind: 'event', eventType: 'text.delta' },
  },
  {
    id: 'valid-generation-failed',
    note: 'A terminal carrying only the enum; any human-readable message is dropped by ignoreUnknownKeys.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"generation.failed","sequence":9,"body":{"error_code":"worker_busy","message":"ignored on purpose"}}',
    expect: { kind: 'event', eventType: 'generation.failed' },
  },

  // ---- order sensitivity ---------------------------------------------------------------
  {
    id: 'order-insensitive-fields-reordered',
    note:
      'ORDER-INSENSITIVE PROJECTION. The same fields as valid-server-hello in a different ' +
      'JSON order, plus an unknown extra key. JSON object order is not semantically ' +
      'meaningful and ignoreUnknownKeys is on, so this must route to the SAME event. A ' +
      'server that made its routing depend on key order would fail this vector.',
    raw: '{"type":"server.hello","body":{"max_frame_bytes":262144,"gateway_build":"c1","protocol_version":"v1","unknown_extra":true},"protocol":"rikkahub.claude-p.v1"}',
    expect: { kind: 'event', eventType: 'server.hello' },
  },
  {
    id: 'order-sensitive-envelope-protocol-first',
    note:
      'ORDER-SENSITIVE BYTE RECORD. The canonical spelling of the envelope: `protocol` ' +
      'first, then `type`, then `body`. This raw byte string is what the server is ' +
      'expected to emit, and it is what the byte vectors in transcripts/ and ' +
      'fingerprints/ are computed over. Reordering these keys does not change routing ' +
      '(see the previous vector) but DOES change the bytes, so it must not be treated ' +
      'as equivalent for any value that gets hashed or signed.',
    raw: '{"protocol":"rikkahub.claude-p.v1","type":"server.hello","body":{"protocol_version":"v1","gateway_build":"c1","max_frame_bytes":262144}}',
    expect: { kind: 'event', eventType: 'server.hello' },
  },
];

// ---------------------------------------------------------------------------------------------
// Write.
// ---------------------------------------------------------------------------------------------

const written = [];

function writeJson(relPath, value) {
  const full = join(ROOT, relPath);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  written.push(relPath);
}

writeJson('transcripts/handshake.json', {
  kind: 'handshake-transcript',
  derivedFrom: 'ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePDeviceIdentity.kt:232-256',
  algorithm:
    'UTF-8 of the concatenation of lengthPrefixed(DOMAIN), lengthPrefixed(deviceId), ' +
    'lengthPrefixed(nonce), lengthPrefixed(gatewayAuthority), lengthPrefixed(appVersion), ' +
    'where lengthPrefixed(v) = `${v.length}:${v}` and `length` counts UTF-16 code units.',
  domain: 'rikkahub-claude-p-handshake-v1',
  signature_algorithm: 'SHA256withECDSA',
  verification: 'dual-implementation (gen-vectors.mjs + VerifyVectors.java) agreed byte-for-byte',
  vectors: generated.handshakeTranscripts,
});

writeJson('transcripts/pairing.json', {
  kind: 'pairing-transcript',
  derivedFrom: 'ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePPairingTransport.kt:113-135',
  algorithm:
    'UTF-8 of the concatenation of lengthPrefixed(DOMAIN), lengthPrefixed(origin), ' +
    'lengthPrefixed(ticket), lengthPrefixed(devicePublicKeyBase64Url), lengthPrefixed(state), ' +
    'lengthPrefixed(challenge), lengthPrefixed(appVersion), same lengthPrefixed rule.',
  domain: 'rikkahub-claude-p-pairing-v1',
  signature_algorithm: 'SHA256withECDSA',
  verification: 'dual-implementation (gen-vectors.mjs + VerifyVectors.java) agreed byte-for-byte',
  vectors: generated.pairingTranscripts,
});

writeJson('fingerprints/vectors.json', {
  kind: 'request-fingerprint',
  derivedFrom: 'ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePGatewayClient.kt:153-219',
  algorithm:
    'SHA-256 over a label/value stream. Every label is written as a 4-byte big-endian ' +
    'UTF-8 byte length followed by its bytes. Every value is preceded by a presence byte ' +
    '(0 = null, 1 = present) and, only when present, a 4-byte big-endian UTF-8 byte length ' +
    'followed by its bytes. NOTE this framing is deliberately DIFFERENT from the ' +
    'transcripts above, which use a decimal `length:` prefix over UTF-16 code units.',
  domain: 'rikkahub-claude-p-request-fingerprint-v1',
  field_order: [
    'domain', 'device_id', 'remote_thread_id', 'remote_branch_id', 'mode', 'model_alias',
    'system_prompt', 'turn_role', 'turn_parts', 'turn_part_{i}.type', 'turn_part_{i}.text',
    'rebuild_history_turns', 'rebuild_{i}.role', 'rebuild_{i}.{j}.type', 'rebuild_{i}.{j}.text',
    'tool_snapshot', 'attachment_manifest',
  ],
  invariants: [
    'system_prompt null and "" MUST produce different digests (presence byte).',
    'parts ["ab","c"] and ["a","bc"] MUST produce different digests (length prefixes).',
    'field ORDER is part of the input; reordering the labels changes the digest.',
  ],
  verification: 'dual-implementation (gen-vectors.mjs + VerifyVectors.java) agreed byte-for-byte',
  vectors: generated.fingerprints,
});

writeJson('frames/envelope.json', {
  kind: 'raw-envelope-frame',
  derivedFrom: 'ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePProtocol.kt:112-145',
  note:
    'Each `raw` is the exact text that would arrive on the wire. Do not reformat, ' +
    're-serialize or re-order these strings when vendoring.',
  verification:
    'READ-DERIVED, not executed in this environment. The Android-side test ' +
    'ClaudePConformanceCorpusTest replays these through the real parseInbound.',
  frames,
});

writeJson('expect/routing.json', {
  kind: 'routing-expectation',
  derivedFrom: 'ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePProtocol.kt:112-145',
  outcome_kinds: {
    event: 'routed to a typed event; `eventType` names the routed ClaudePEventType',
    ignored_unknown: 'well-formed envelope, unrouted type — must produce no text and no terminal',
    rejected: 'protocol violation; `reason` is a ClaudePParseRejection name',
  },
  rejection_reasons: [
    'MALFORMED_FRAME', 'MISSING_PROTOCOL', 'MALFORMED_PROTOCOL',
    'PROTOCOL_MAJOR_MISMATCH', 'MISSING_TYPE', 'MALFORMED_EVENT_BODY',
  ],
  note:
    'One expectation per frames/envelope.json entry, keyed by `id`, in the same order. ' +
    'Kept separate so a consumer can diff the frame bytes independently of the expected ' +
    'outcome.',
  expectations: frames.map((f) => ({
    id: f.id,
    expect: f.expect,
    note: f.note,
  })),
});

writeJson('SPEC_REVISION.json', SPEC_REVISION);

// ---------------------------------------------------------------------------------------------
// MANIFEST.sha256
//
// Discovered by walking the directory rather than tracked as "the files this script
// wrote". The Android test asserts that the manifest and the directory agree in BOTH
// directions, so a hand-added file that the manifest did not know about would be a real
// failure — which is the intent: everything shipped as the corpus is covered, including
// prose like README.md.
//
// Excluded: the manifest itself (it cannot hash itself) and tools/ (how the vectors were
// derived, not part of the artifact the server vendors).
// ---------------------------------------------------------------------------------------------

const EXCLUDED_DIRS = new Set(['tools']);
const EXCLUDED_FILES = new Set(['MANIFEST.sha256']);

function corpusFiles() {
  const found = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith('.')) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (EXCLUDED_DIRS.has(entry.name)) continue;
        walk(full);
      } else if (entry.isFile() && !EXCLUDED_FILES.has(entry.name)) {
        found.push(relative(ROOT, full).replace(/\\/g, '/'));
      }
    }
  };
  walk(ROOT);
  return found.sort();
}

const manifestLines = corpusFiles().map((relPath) => {
  const digest = createHash('sha256').update(readFileSync(join(ROOT, relPath))).digest('hex');
  return `${digest}  ${relPath}`;
});

writeFileSync(join(ROOT, 'MANIFEST.sha256'), `${manifestLines.join('\n')}\n`, 'utf8');

process.stdout.write(
  `wrote ${written.length} corpus files + MANIFEST.sha256\n${manifestLines.join('\n')}\n`,
);
