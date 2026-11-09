ALTER TABLE beneficiary_consent ADD COLUMN captured_at VARCHAR(40);
ALTER TABLE beneficiary_consent ADD COLUMN consent_version VARCHAR(40);
ALTER TABLE operation ADD COLUMN installment_snapshot VARCHAR(16000);
