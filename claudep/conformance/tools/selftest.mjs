#!/usr/bin/env node
/**
 * Regression tests for the canonical specification digest.
 *
 * Run from this directory: `node selftest.mjs`
 *
 * These exist because the first version of this binding was wrong in a way that only
 * showed up on a *different platform*: it hashed raw working-tree bytes, so it passed on
 * the machine that generated the corpus and failed on CI. A rule whose failure mode is
 * "works here, not there" needs a test that does not depend on where it runs — which is
 * why every case below constructs its input in memory rather than reading a file whose
 * line endings would themselves depend on the checkout.
 *
 * Exits non-zero on the first failure, printing which case failed.
 */

import { canonicalSpecDigest, canonicalizeLineEndings, CanonicalizationError } from './canonical-hash.mjs';

let failures = 0;

function check(name, fn) {
  try {
    fn();
    console.log(`  ok    ${name}`);
  } catch (error) {
    failures += 1;
    console.log(`  FAIL  ${name}`);
    console.log(`        ${error.message}`);
  }
}

function assertEqual(actual, expected, what) {
  if (actual !== expected) {
    throw new Error(`${what}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function assertThrows(fn, what) {
  try {
    fn();
  } catch (error) {
    if (error instanceof CanonicalizationError) return;
    throw new Error(`${what}: threw ${error.name} instead of CanonicalizationError`);
  }
  throw new Error(`${what}: did not throw`);
}

const bytes = (text) => Buffer.from(text, 'utf8');

console.log('canonical specification digest');

// --- the property the whole rule exists for ------------------------------------------------
check('LF and CRLF forms of the same document hash identically', () => {
  const lf = 'alpha\nbeta\ngamma\n';
  const crlf = 'alpha\r\nbeta\r\ngamma\r\n';
  assertEqual(canonicalSpecDigest(bytes(crlf)), canonicalSpecDigest(bytes(lf)), 'CRLF vs LF digest');
});

check('a document that is already LF is unchanged by canonicalization', () => {
  const lf = 'alpha\nbeta\ngamma\n';
  assertEqual(canonicalizeLineEndings(lf), lf, 'LF passthrough');
});

// --- fail closed on the ambiguous byte sequence ---------------------------------------------
check('a bare CR is rejected', () => {
  // A lone CR with no LF. Accepting it would make the digest depend on which of two
  // similar-looking byte sequences some editor wrote.
  assertThrows(() => canonicalSpecDigest(bytes('alpha\rbeta\n')), 'bare CR');
});

check('a bare CR at end of input is rejected', () => {
  assertThrows(() => canonicalSpecDigest(bytes('alpha\nbeta\r')), 'trailing bare CR');
});

check('invalid UTF-8 is rejected rather than replaced', () => {
  // 0xFF is never valid UTF-8. A lossy decode would substitute U+FFFD and hash something
  // the file does not contain.
  assertThrows(() => canonicalSpecDigest(Buffer.from([0x61, 0xff, 0x62])), 'invalid UTF-8');
});

// --- content changes must change the digest --------------------------------------------------
check('a real content change changes the digest', () => {
  const a = canonicalSpecDigest(bytes('alpha\nbeta\n'));
  const b = canonicalSpecDigest(bytes('alpha\nBETA\n'));
  if (a === b) throw new Error('case-only content change did not change the digest');
});

check('adding a trailing newline changes the digest', () => {
  const without = canonicalSpecDigest(bytes('alpha\nbeta'));
  const with_ = canonicalSpecDigest(bytes('alpha\nbeta\n'));
  if (without === with_) throw new Error('trailing newline is content and must change the digest');
});

check('removing a trailing newline changes the digest', () => {
  const with_ = canonicalSpecDigest(bytes('alpha\nbeta\n\n'));
  const without = canonicalSpecDigest(bytes('alpha\nbeta\n'));
  if (with_ === without) throw new Error('removing a trailing newline must change the digest');
});

check('whitespace inside a line is content', () => {
  const a = canonicalSpecDigest(bytes('alpha beta\n'));
  const b = canonicalSpecDigest(bytes('alpha  beta\n'));
  if (a === b) throw new Error('internal whitespace must not be normalized away');
});

check('a CRLF-to-LF change does NOT change the digest while a CR-to-LF change is rejected', () => {
  // The two rules must not be confused: \r\n is a line ending and is normalized; \r alone
  // is ambiguous and is refused. This asserts both halves in one place so a future change
  // that "helpfully" treats bare CR as a line ending is caught.
  const crlfDigest = canonicalSpecDigest(bytes('a\r\nb\r\n'));
  const lfDigest = canonicalSpecDigest(bytes('a\nb\n'));
  assertEqual(crlfDigest, lfDigest, 'CRLF normalized');
  assertThrows(() => canonicalSpecDigest(bytes('a\rb\n')), 'bare CR rejected');
});

// --- the digest is stable across repeated computation ----------------------------------------
check('the digest is deterministic across repeated computation', () => {
  const text = bytes('alpha\r\nbeta\r\ngamma\r\n');
  const first = canonicalSpecDigest(text);
  for (let i = 0; i < 5; i += 1) {
    assertEqual(canonicalSpecDigest(text), first, 'repeat run');
  }
});

console.log(
  failures === 0
    ? '\nall canonical-hash cases passed'
    : `\n${failures} canonical-hash case(s) FAILED`,
);
process.exit(failures === 0 ? 0 : 1);
