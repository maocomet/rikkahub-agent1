/**
 * Canonical hashing of specification documents.
 *
 * ## The problem this exists to solve
 *
 * `SPEC_REVISION.json` records a SHA-256 of the specification text so that a reader can
 * tell whether the corpus in their hands describes the document in front of them. The
 * first version of that binding hashed **raw working-tree bytes**, which made it depend
 * on the machine that ran the generator rather than on the document: with
 * `core.autocrlf=true`, Git checks a text file out as CRLF, so a Windows run recorded the
 * digest of the CRLF form while CI — which checks out the LF blob — recomputed the digest
 * of the LF form and could never match.
 *
 * That is not a tolerance problem to be papered over. A hash that depends on the
 * checkout's line endings is not a hash of the specification; it is a hash of one
 * machine's rendering of it. The failure it produced was real and was caught by CI:
 * `corpus revision is bound to the specification it was derived from` failed on every LF
 * checkout and could only ever have passed on one Windows machine.
 *
 * ## The rule
 *
 * 1. Decode the file as **UTF-8, strictly**. Invalid UTF-8 is rejected rather than
 *    replaced: a lossy decode would silently hash different bytes than the file contains.
 * 2. Normalize every `CRLF` to `LF`.
 * 3. **Reject a bare `CR`** (a `CR` not followed by `LF`). It is not a line ending this
 *    family of documents uses, and accepting it would mean the digest depends on which of
 *    two similar-looking byte sequences a text editor happened to write.
 * 4. Do **nothing else**. No trimming, no reordering, no collapsing of blank lines, no
 *    Unicode normalization, no BOM stripping beyond what strict UTF-8 decoding implies.
 *    Every other byte is content, and changing it must change the digest.
 * 5. SHA-256 over the UTF-8 encoding of the normalized text, lowercase hex.
 *
 * Two consequences are deliberate and are asserted by `selftest.mjs`:
 *
 * - Adding or removing a **trailing newline** changes the digest. It is a real content
 *   change, not a formatting detail.
 * - The output is **identical for the LF and CRLF forms of the same document**, which is
 *   the entire point.
 *
 * This is a hashing rule, not a parser. It does not interpret the document, and it must
 * not be extended to "clean up" anything — every exception added here is a way for two
 * different documents to share a digest.
 */

import { createHash } from 'node:crypto';

/** Thrown when input cannot be canonically hashed. Never carries the offending bytes. */
export class CanonicalizationError extends Error {
  constructor(reason) {
    super(`cannot canonicalize: ${reason}`);
    this.name = 'CanonicalizationError';
    this.reason = reason;
  }
}

/**
 * Strict UTF-8 decode. Rejects invalid sequences instead of substituting U+FFFD.
 */
function decodeStrict(bytes) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new CanonicalizationError('input is not valid UTF-8');
  }
}

/**
 * Applies the line-ending rule. Exported so a caller can inspect the canonical text
 * without hashing it — which is what makes the rule testable directly rather than only
 * through a digest comparison.
 */
export function canonicalizeLineEndings(text) {
  const normalized = text.split('\r\n').join('\n');
  // Anything still carrying a CR was a bare CR: step 2 removed every legitimate pair.
  if (normalized.includes('\r')) {
    throw new CanonicalizationError('input contains a bare CR (CR not followed by LF)');
  }
  return normalized;
}

/**
 * The canonical digest of a specification document.
 *
 * `bytes` is the raw file content. Returns lowercase hex SHA-256.
 */
export function canonicalSpecDigest(bytes) {
  const text = decodeStrict(bytes);
  const canonical = canonicalizeLineEndings(text);
  return createHash('sha256').update(Buffer.from(canonical, 'utf8')).digest('hex');
}
