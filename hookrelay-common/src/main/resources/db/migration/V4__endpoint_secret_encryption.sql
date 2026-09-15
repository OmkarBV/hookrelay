-- Correction to the Phase 1 schema: secret_hash was modeled on the
-- password/API-key pattern (store only a one-way hash, verify by
-- comparison). That's wrong for an HMAC signing secret — the dispatcher must
-- reproduce the actual secret bytes on every delivery to compute
-- Hookrelay-Signature, and a SHA-256 hash cannot be reversed back into
-- anything usable for that. This column must hold a reversible form of the
-- secret, so it is encrypted at rest (AES-256-GCM, see
-- io.hookrelay.common.crypto.SecretEncryptionService) rather than hashed.
-- varchar(64) (sized for a hex SHA-256 digest) is too narrow for an
-- IV+ciphertext+tag, base64-encoded — widened accordingly.
alter table endpoint_secret rename column secret_hash to secret_ciphertext;
alter table endpoint_secret alter column secret_ciphertext type varchar(255);
