CREATE TABLE beneficiary_consent(id VARCHAR(36) PRIMARY KEY, beneficiary VARCHAR(60) NOT NULL, agreement VARCHAR(60) NOT NULL, UNIQUE(beneficiary,agreement));
CREATE TABLE operation(id VARCHAR(36) PRIMARY KEY, idem_key VARCHAR(100) NOT NULL UNIQUE, payload VARCHAR(16000) NOT NULL, beneficiary VARCHAR(60) NOT NULL, status VARCHAR(30) NOT NULL, protocol VARCHAR(36), attempts INT NOT NULL DEFAULT 0, next_attempt BIGINT NOT NULL, created_at VARCHAR(40) NOT NULL, reason VARCHAR(300));
CREATE TABLE reservation(parcel_id VARCHAR(160) PRIMARY KEY, operation_id VARCHAR(36) NOT NULL REFERENCES operation(id));
CREATE TABLE audit_event(sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, operation_id VARCHAR(36) NOT NULL REFERENCES operation(id), at VARCHAR(40) NOT NULL, type VARCHAR(40) NOT NULL, detail VARCHAR(500) NOT NULL);
CREATE TABLE submission_lock(id INT PRIMARY KEY);
INSERT INTO submission_lock VALUES(1);
CREATE INDEX operation_pending ON operation(status,next_attempt);
