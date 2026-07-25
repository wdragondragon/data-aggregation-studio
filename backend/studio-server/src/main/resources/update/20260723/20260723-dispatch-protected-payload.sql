-- P0-MC-02: keep ad-hoc script content and runtime arguments out of payload_json.
-- The value is encrypted with STUDIO_ENCRYPTION_SECRET and cleared by the
-- owning Worker before execution, or whenever the Dispatch enters a terminal state.

alter table dispatch_task
    add column protected_payload_ciphertext mediumtext after max_retries;
