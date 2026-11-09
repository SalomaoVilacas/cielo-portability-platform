CREATE TABLE beneficiary_consent(id VARCHAR(36) PRIMARY KEY, beneficiary VARCHAR(60) NOT NULL, agreement VARCHAR(60) NOT NULL, UNIQUE(beneficiary,agreement));
CREATE TABLE installment(id VARCHAR(160) PRIMARY KEY, beneficiary VARCHAR(60) NOT NULL, agreement VARCHAR(60) NOT NULL, amount DECIMAL(15,2) NOT NULL, due_date DATE NOT NULL, status VARCHAR(20) NOT NULL, bank VARCHAR(3) NOT NULL, branch VARCHAR(6) NOT NULL, account VARCHAR(20) NOT NULL);
CREATE TABLE operation(protocol VARCHAR(36) PRIMARY KEY, idem_key VARCHAR(100) NOT NULL UNIQUE, payload VARCHAR(16000) NOT NULL, status VARCHAR(20) NOT NULL, ready_at BIGINT NOT NULL, scenario VARCHAR(30) NOT NULL, reason VARCHAR(300));
CREATE TABLE reservation(parcel_id VARCHAR(160) PRIMARY KEY REFERENCES installment(id), protocol VARCHAR(36) NOT NULL REFERENCES operation(protocol));
CREATE TABLE submission_lock(id INT PRIMARY KEY);
INSERT INTO submission_lock VALUES(1);
