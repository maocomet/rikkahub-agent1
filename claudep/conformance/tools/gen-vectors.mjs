#!/usr/bin/env node
/**
 * Generates the canonical conformance vectors for the Claude P wire protocol from the
 * *frozen Kotlin implementation* in `ai/src/main/java/me/rerere/ai/provider/claudep/`.
 *
 * This is one of two independent implementations used to derive the vectors. The other
 * is `VerifyVectors.java`, which mirrors the same algorithms on the JVM — the same
 * runtime the Kotlin code actually runs on, and therefore the same `String.length`
 * (UTF-16 code units) and UTF-8 encoding semantics. `tools/README.md` records how the
 * two are compared and why that comparison is the point.
 *
 * Every algorithm below cites the file and line range it mirrors. Nothing here is
 * written from memory of the docs: the docs are downstream of the code, and where they
 * disagree the code wins.
 *
 * Emits a single JSON document on stdout. The corpus writer consumes that document.
 */

import { createHash } from 'node:crypto';

// ---------------------------------------------------------------------------------------------
// Mirrors ClaudePDeviceIdentity.kt:232-256 — ClaudePHandshakeTranscript
// ---------------------------------------------------------------------------------------------

const HANDSHAKE_DOMAIN = 'rikkahub-claude-p-handshake-v1';

/**
 * `"${value.length}:$value"` — Kotlin `String.length` is a count of UTF-16 code units,
 * NOT code points and NOT UTF-8 bytes. For an astral character (e.g. an emoji) that
 * count is 2, not 1. JS `String.length` has identical semantics, so the two runtimes
 * agree; a naive implementation in a language with code-point or byte semantics would
 * not, which is exactly what the astral vectors below exist to catch.
 */
function kotlinLengthPrefixed(value) {
  return `${value.length}:${value}`;
}

function handshakeTranscript({ deviceId, nonce, gatewayAuthority, appVersion }) {
  const joined =
    kotlinLengthPrefixed(HANDSHAKE_DOMAIN) +
    kotlinLengthPrefixed(deviceId) +
    kotlinLengthPrefixed(nonce) +
    kotlinLengthPrefixed(gatewayAuthority) +
    kotlinLengthPrefixed(appVersion);
  return Buffer.from(joined, 'utf8');
}

// ---------------------------------------------------------------------------------------------
// Mirrors ClaudePPairingTransport.kt:113-135 — ClaudePPairingTranscript
// ---------------------------------------------------------------------------------------------

const PAIRING_DOMAIN = 'rikkahub-claude-p-pairing-v1';

function pairingTranscript({
  origin,
  ticket,
  devicePublicKeyBase64Url,
  state,
  challenge,
  appVersion,
}) {
  const joined =
    kotlinLengthPrefixed(PAIRING_DOMAIN) +
    kotlinLengthPrefixed(origin) +
    kotlinLengthPrefixed(ticket) +
    kotlinLengthPrefixed(devicePublicKeyBase64Url) +
    kotlinLengthPrefixed(state) +
    kotlinLengthPrefixed(challenge) +
    kotlinLengthPrefixed(appVersion);
  return Buffer.from(joined, 'utf8');
}

// ---------------------------------------------------------------------------------------------
// Mirrors ClaudePGatewayClient.kt — ClaudePRequestFingerprint
//
// A *different* framing from the transcripts above, and the difference is load-bearing:
//   - the label is written as a 4-byte big-endian UTF-8 byte length, then the label bytes;
//   - the value is written as a single presence byte (0 = null, 1 = present), and only
//     when present is it followed by a 4-byte big-endian UTF-8 byte length and its bytes.
//
// So `systemPrompt = null` and `systemPrompt = ""` produce *different* digests. Collapsing
// them would let an absent system prompt and an empty one share an idempotency key, which
// is precisely the collision the fingerprint exists to prevent.
//
// v1-r4 adds two *conditional tail fields*, `assistantId` and `bindingIntent` (§12.4 items
// 16-17, §12.12). They are appended after `attachment_manifest` and, when absent, write
// **zero bytes** — not a `0x00` presence byte. That is what leaves the v1-r3 digests
// untouched: a request carrying neither field emits the byte stream r3 emitted.
//
// Do not "tidy" this into the fixed-field loop above. The two tiers differ precisely in
// what an absent value writes, and the corpus asserts both.
// ---------------------------------------------------------------------------------------------

const FINGERPRINT_DOMAIN = 'rikkahub-claude-p-request-fingerprint-v1';

function requestFingerprint({
  deviceId,
  remoteThreadId,
  remoteBranchId,
  mode,
  modelAlias,
  systemPrompt,
  turn,
  rebuildHistory = null,
  toolSnapshot = null,
  attachmentManifest = null,
  assistantId = null,
  bindingIntent = null,
}) {
  const digest = createHash('sha256');

  // MessageDigest.updateLengthPrefixed — ClaudePGatewayClient.kt:212-219
  function updateLengthPrefixed(bytes) {
    const size = bytes.length;
    const header = Buffer.alloc(4);
    // Kotlin writes `(size ushr 24).toByte()` etc. — big-endian, and `toByte()` truncates
    // to the low 8 bits. `writeUInt32BE` is the same thing for sizes below 2^31, and a
    // length above 2^31 is not reachable here because the input is already bounded.
    header.writeUInt32BE(size >>> 0, 0);
    digest.update(header);
    digest.update(bytes);
  }

  function field(label, value) {
    updateLengthPrefixed(Buffer.from(label, 'utf8'));
    if (value === null) {
      digest.update(Buffer.from([0]));
    } else {
      digest.update(Buffer.from([1]));
      updateLengthPrefixed(Buffer.from(value, 'utf8'));
    }
  }

  field('domain', FINGERPRINT_DOMAIN);
  field('device_id', deviceId);
  field('remote_thread_id', remoteThreadId);
  field('remote_branch_id', remoteBranchId);
  field('mode', mode);
  field('model_alias', modelAlias);
  field('system_prompt', systemPrompt);
  field('turn_role', turn.role);
  // `turn.parts.size.toString()` — a decimal string, not a number.
  field('turn_parts', String(turn.parts.length));
  turn.parts.forEach((part, index) => {
    field(`turn_part_${index}.type`, part.type);
    field(`turn_part_${index}.text`, part.text);
  });
  const history = rebuildHistory ?? [];
  field('rebuild_history_turns', String(history.length));
  history.forEach((entry, index) => {
    field(`rebuild_${index}.role`, entry.role);
    entry.parts.forEach((part, partIndex) => {
      field(`rebuild_${index}.${partIndex}.type`, part.type);
      field(`rebuild_${index}.${partIndex}.text`, part.text);
    });
  });
  field('tool_snapshot', toolSnapshot);
  field('attachment_manifest', attachmentManifest);

  // Conditional tail fields — appended, and only when present. Absent writes nothing at all,
  // so the stream above stays byte-identical to v1-r3. Order is fixed (r4 §12.12).
  if (assistantId !== null) field('assistant_id', assistantId);
  if (bindingIntent !== null) field('binding_intent', bindingIntent);

  return digest.digest('hex');
}

// ---------------------------------------------------------------------------------------------
// Vector inputs. Shared verbatim with VerifyVectors.java so a divergence is a real
// divergence and not a difference of inputs.
// ---------------------------------------------------------------------------------------------

const HANDSHAKE_CASES = [
  {
    id: 'handshake-ascii',
    note: 'Baseline: every field is BMP ASCII, so UTF-16 length equals UTF-8 byte length.',
    input: {
      deviceId: 'dev-01',
      nonce: 'n0nce-abc',
      gatewayAuthority: 'gateway.example.com',
      appVersion: '1.0.0',
    },
  },
  {
    id: 'handshake-multibyte-unicode',
    note:
      'CJK fields. Each character is 1 UTF-16 unit and 3 UTF-8 bytes, so a byte-length ' +
      'prefix gives a different transcript than a code-unit prefix.',
    input: {
      deviceId: '设备一号',
      nonce: '随机数',
      gatewayAuthority: '网关.example.com',
      appVersion: '1.0.0-中文',
    },
  },
  {
    id: 'handshake-astral-emoji',
    note:
      'Astral characters. Kotlin String.length counts a surrogate PAIR as 2, while the ' +
      'UTF-8 encoding is 4 bytes. This is the case that separates a correct ' +
      'implementation from one that prefixes byte or code-point counts.',
    input: {
      deviceId: 'dev-😀',
      nonce: '🔐🔐',
      gatewayAuthority: 'gateway.example.com',
      appVersion: '1.0.0',
    },
  },
  {
    id: 'handshake-empty-fields',
    note:
      'Empty strings are still length-prefixed as "0:". An implementation that skips the ' +
      'prefix for empty values produces different bytes.',
    input: { deviceId: '', nonce: '', gatewayAuthority: '', appVersion: '' },
  },
  {
    id: 'handshake-ambiguity-guard',
    note:
      'The pair ("ab","c") versus ("a","bc"). Without length prefixes both would ' +
      'concatenate identically; with them the transcripts must differ.',
    input: {
      deviceId: 'ab',
      nonce: 'c',
      gatewayAuthority: 'gateway.example.com',
      appVersion: '1.0.0',
    },
  },
  {
    id: 'handshake-ambiguity-guard-shifted',
    note: 'The second half of handshake-ambiguity-guard. Must NOT equal the first.',
    input: {
      deviceId: 'a',
      nonce: 'bc',
      gatewayAuthority: 'gateway.example.com',
      appVersion: '1.0.0',
    },
  },
];

const PAIRING_CASES = [
  {
    id: 'pairing-ascii',
    note: 'Baseline pairing transcript, all fields ASCII.',
    input: {
      origin: 'https://gateway.example.com',
      ticket: 'ticket-abc123',
      devicePublicKeyBase64Url: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE',
      state: 'state-xyz',
      challenge: 'challenge-789',
      appVersion: '1.0.0',
    },
  },
  {
    id: 'pairing-multibyte-unicode',
    note: 'Non-ASCII origin and ticket: the length prefix stays a UTF-16 code-unit count.',
    input: {
      origin: 'https://网关.example.com',
      ticket: '票据-一二三',
      devicePublicKeyBase64Url: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE',
      state: '状态',
      challenge: '挑战',
      appVersion: '1.0.0',
    },
  },
  {
    id: 'pairing-empty-ticket',
    note: 'An empty ticket is still framed as "0:".',
    input: {
      origin: 'https://gateway.example.com',
      ticket: '',
      devicePublicKeyBase64Url: '',
      state: '',
      challenge: '',
      appVersion: '',
    },
  },
  {
    id: 'pairing-field-order-binding',
    note:
      'state and challenge swapped relative to pairing-ascii, everything else equal. The ' +
      'transcript must change, which is what makes the signature cover field ORDER and ' +
      'not merely the field set.',
    input: {
      origin: 'https://gateway.example.com',
      ticket: 'ticket-abc123',
      devicePublicKeyBase64Url: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE',
      state: 'challenge-789',
      challenge: 'state-xyz',
      appVersion: '1.0.0',
    },
  },
];

/**
 * Shared inputs for the v1-r4 M3 vectors.
 *
 * Kept as named constants because every one of those vectors is defined by *which single field
 * differs* from its sibling. Spelling the values once makes that structural — a divergence in
 * the generators or a typo that changes two fields at once would stop being a comparison.
 *
 * `AUTO_BRANCH_*` are 64 lowercase hex characters, the shape §5.3 requires for an `immediate`
 * branch identity, and `AUTO_ASSISTANT_*` are uuid-v4 shaped, as §5.4 requires.
 */
const AUTO_BRANCH_A = '3f2a9c4e7b1d8056af3e21c9d0b47e6a5c8f1d2e3b4a59687766554433221100';
const AUTO_BRANCH_B = '0a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff';
const AUTO_ASSISTANT_A = '11111111-2222-3333-4444-555555555555';
const AUTO_ASSISTANT_B = '66666666-7777-8888-9999-aaaaaaaaaaaa';

const FINGERPRINT_CASES = [
  {
    id: 'fingerprint-minimal',
    note: 'Smallest valid request: one turn, one text part, no optional fields.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: 'branch-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-null-vs-empty-system-prompt',
    note:
      'systemPrompt = "" where fingerprint-minimal has null. The two digests MUST differ: ' +
      'the presence byte exists so an absent system prompt cannot collide with an empty one.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: 'branch-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: '',
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-with-history',
    note: 'Two rebuild-history turns, the second with two parts.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: 'branch-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: 'be brief',
      turn: { role: 'user', parts: [{ type: 'text', text: 'again' }] },
      rebuildHistory: [
        { role: 'user', parts: [{ type: 'text', text: 'first' }] },
        {
          role: 'assistant',
          parts: [
            { type: 'text', text: 'second-a' },
            { type: 'text', text: 'second-b' },
          ],
        },
      ],
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-unicode',
    note: 'Astral and CJK content in the prompt and history.',
    input: {
      deviceId: '设备-01',
      remoteThreadId: '线程-01',
      remoteBranchId: '分支-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: '请简短回答 😀',
      turn: { role: 'user', parts: [{ type: 'text', text: '你好 🌏' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-empty-turn-parts',
    note: 'A turn with zero parts: "turn_parts" is the string "0" and no part fields follow.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: 'branch-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-part-boundary',
    note:
      'Two adjacent parts "ab" and "c". Length prefixes must keep this distinct from the ' +
      'next case, which is parts "a" and "bc".',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: 'branch-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: {
        role: 'user',
        parts: [
          { type: 'text', text: 'ab' },
          { type: 'text', text: 'c' },
        ],
      },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-part-boundary-shifted',
    note: 'The shifted counterpart of fingerprint-part-boundary. Must NOT be equal to it.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: 'branch-01',
      mode: 'new',
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: {
        role: 'user',
        parts: [
          { type: 'text', text: 'a' },
          { type: 'text', text: 'bc' },
        ],
      },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },

  // -------------------------------------------------------------------------------------------
  // v1-r4: conditional tail fields (§12.4 items 16-17, §12.12).
  //
  // The seven vectors above are the v1-r3 set. Their digests MUST NOT move under r4 — that is
  // what "append only when present" buys, and it is the single most important property in this
  // group. The six below pin the new tail and the fields it carries.
  //
  // Two of them (`fingerprint-auto-branch-absent`, `fingerprint-auto-intent-changed`) describe
  // shapes that §5.4 REJECTS on the wire. That is deliberate: they test the *encoder*, not the
  // shape validator, and the note on each says so. A corpus of legal shapes alone could not
  // show that `binding_intent` is actually bound into the digest.
  // -------------------------------------------------------------------------------------------

  {
    id: 'fingerprint-auto-immediate',
    note:
      'M3 auto/immediate: legal §5.4 shape 2. Branch is the 64-lowercase-hex digest and both ' +
      'tail fields are present, so the stream ends with assistant_id then binding_intent.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: AUTO_BRANCH_A,
      mode: 'auto',
      bindingIntent: 'immediate',
      assistantId: AUTO_ASSISTANT_A,
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-auto-deferred',
    note:
      'M3 auto/deferred: legal §5.4 shape 3. remoteBranchId is null — the branch does not exist ' +
      'yet — so item 4 writes the presence byte 0x00. Must differ from fingerprint-auto-immediate, ' +
      'which is otherwise identical apart from carrying a branch.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: null,
      mode: 'auto',
      bindingIntent: 'deferred',
      assistantId: AUTO_ASSISTANT_A,
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-auto-branch-absent',
    note:
      'ENCODER vector, not a legal shape: auto + deferred + branch PRESENT is refused by §5.4 ' +
      'row 6. It differs from fingerprint-auto-deferred in exactly one thing — branch presence — ' +
      'so the two digests must differ, which is what proves remote_branch_id is still bound at ' +
      'item 4 with its original presence semantics rather than moved to the tail.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: AUTO_BRANCH_A,
      mode: 'auto',
      bindingIntent: 'deferred',
      assistantId: AUTO_ASSISTANT_A,
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-auto-second-branch',
    note:
      'Identical to fingerprint-auto-immediate except for the branch VALUE. Two different ' +
      'branches must not share an idempotency key, or one branch could replay another\'s answer.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: AUTO_BRANCH_B,
      mode: 'auto',
      bindingIntent: 'immediate',
      assistantId: AUTO_ASSISTANT_A,
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-auto-assistant-changed',
    note:
      'Identical to fingerprint-auto-immediate except for assistant_id. The tail field must be ' +
      'bound into the digest, not merely carried alongside it.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: AUTO_BRANCH_A,
      mode: 'auto',
      bindingIntent: 'immediate',
      assistantId: AUTO_ASSISTANT_B,
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
  {
    id: 'fingerprint-auto-intent-changed',
    note:
      'ENCODER vector, not a legal shape: auto + immediate + branch ABSENT is refused by §5.4 ' +
      'row 5. It differs from fingerprint-auto-deferred in exactly one thing — binding_intent — ' +
      'so the two digests must differ, which is what proves the new field is bound rather than ' +
      'decoration. Without this pair, immediate and deferred would be indistinguishable in the ' +
      'idempotency key whenever the branch is absent.',
    input: {
      deviceId: 'dev-01',
      remoteThreadId: 'thread-01',
      remoteBranchId: null,
      mode: 'auto',
      bindingIntent: 'immediate',
      assistantId: AUTO_ASSISTANT_A,
      modelAlias: 'sonnet',
      systemPrompt: null,
      turn: { role: 'user', parts: [{ type: 'text', text: 'hello' }] },
      rebuildHistory: null,
      toolSnapshot: null,
      attachmentManifest: null,
    },
  },
];

// ---------------------------------------------------------------------------------------------

function hex(buf) {
  return buf.toString('hex');
}

const out = {
  handshakeTranscripts: HANDSHAKE_CASES.map((c) => ({
    id: c.id,
    note: c.note,
    input: c.input,
    transcriptHex: hex(handshakeTranscript(c.input)),
    transcriptUtf8: handshakeTranscript(c.input).toString('utf8'),
  })),
  pairingTranscripts: PAIRING_CASES.map((c) => ({
    id: c.id,
    note: c.note,
    input: c.input,
    transcriptHex: hex(pairingTranscript(c.input)),
    transcriptUtf8: pairingTranscript(c.input).toString('utf8'),
  })),
  fingerprints: FINGERPRINT_CASES.map((c) => ({
    id: c.id,
    note: c.note,
    input: c.input,
    sha256Hex: requestFingerprint(c.input),
  })),
};

process.stdout.write(`${JSON.stringify(out, null, 2)}\n`);
