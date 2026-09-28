-- Run only with an authorized operator's database session.
-- Set @devmate_document_id to the reviewed numeric ID before sourcing this file.
-- This resumes inspection/cleanup; it never asserts that an uncertain PUT ended.
UPDATE knowledge_documents
SET needs_manual = 0,
    retry_count = 0,
    operation_version = operation_version + 1,
    lease_owner = NULL,
    lease_until = NULL,
    next_attempt_at = UTC_TIMESTAMP(6),
    update_time = UTC_TIMESTAMP(6)
WHERE id = @devmate_document_id
  AND needs_manual = 1
  AND storage_state IN ('UPLOADING', 'FAILED', 'DELETE_PENDING')
  AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6));

SELECT id, storage_state, error_code, retry_count, needs_manual
FROM knowledge_documents
WHERE id = @devmate_document_id;
