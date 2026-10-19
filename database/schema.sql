-- Run:  mysql -u root -p < database/schema.sql
DROP DATABASE IF EXISTS upiguard;
CREATE DATABASE upiguard;
USE upiguard;

CREATE TABLE users (
  user_id  BIGINT PRIMARY KEY AUTO_INCREMENT,
  name     VARCHAR(60)  NOT NULL,
  upi_id   VARCHAR(80)  NOT NULL UNIQUE,
  balance  DECIMAL(12,2) NOT NULL DEFAULT 0 CHECK (balance >= 0)
);

CREATE TABLE upi_ids (
  upi_id         VARCHAR(80) PRIMARY KEY,
  first_seen     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  report_count   INT NOT NULL DEFAULT 0,
  risk_score     INT NOT NULL DEFAULT 0,
  label          VARCHAR(10) NOT NULL DEFAULT 'SAFE',
  is_whitelisted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE reports (
  report_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
  upi_id      VARCHAR(80) NOT NULL,
  reporter_id BIGINT NOT NULL,
  category    ENUM('SCAM','SPAM','JUNK') NOT NULL,
  description VARCHAR(255),
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (upi_id) REFERENCES upi_ids(upi_id),
  FOREIGN KEY (reporter_id) REFERENCES users(user_id),
  UNIQUE KEY one_report_per_user (upi_id, reporter_id),   -- stops report spamming
  INDEX idx_reports_upi (upi_id, created_at)
);

CREATE TABLE transactions (
  txn_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  payer_id      BIGINT NOT NULL,
  receiver_upi  VARCHAR(80) NOT NULL,
  amount        DECIMAL(12,2) NOT NULL,
  status        ENUM('SUCCESS','CANCELLED','FAILED') NOT NULL,
  warning_label VARCHAR(10),
  risk_score    INT,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (payer_id) REFERENCES users(user_id)
);

CREATE TABLE risk_logs (
  log_id     BIGINT PRIMARY KEY AUTO_INCREMENT,
  upi_id     VARCHAR(80) NOT NULL,
  score      INT NOT NULL,
  label      VARCHAR(10) NOT NULL,
  reasons    VARCHAR(500),
  checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_logs_upi (upi_id)
);

-- TRIGGER: keeps the report counter in sync automatically
DELIMITER $$
CREATE TRIGGER trg_after_report AFTER INSERT ON reports
FOR EACH ROW
BEGIN
  UPDATE upi_ids SET report_count = report_count + 1 WHERE upi_id = NEW.upi_id;
END$$
DELIMITER ;

-- VIEW: used by the dashboard
CREATE VIEW top_reported_ids AS
SELECT u.upi_id, u.report_count, u.risk_score, u.label,
       SUM(r.category='SCAM') AS scam_reports,
       SUM(r.category='SPAM') AS spam_reports,
       SUM(r.category='JUNK') AS junk_reports
FROM upi_ids u JOIN reports r ON r.upi_id = u.upi_id
GROUP BY u.upi_id, u.report_count, u.risk_score, u.label
ORDER BY u.report_count DESC;

-- ---------- SAMPLE DATA ----------
INSERT INTO users(name, upi_id, balance) VALUES
 ('Arun',    'arun@okaxis',    5000),
 ('Priya',   'priya@ybl',      8000),
 ('Karthik', 'karthik@oksbi',  3000),
 ('Divya',   'divya@paytm',    6000),
 ('Ravi',    'ravi@ibl',       4000);

INSERT INTO upi_ids(upi_id) SELECT upi_id FROM users;
INSERT INTO upi_ids(upi_id, is_whitelisted) VALUES
 ('sunrise.stores@okaxis', TRUE),
 ('amazonpay@apl', TRUE);
INSERT INTO upi_ids(upi_id) VALUES
 ('refund.helpdesk@ybl'), ('lucky.lottery99@paytm'), ('9876501234@ybl');

INSERT INTO reports(upi_id, reporter_id, category, description) VALUES
 ('refund.helpdesk@ybl',   1, 'SCAM', 'Asked me to pay Rs 5 to get a refund'),
 ('refund.helpdesk@ybl',   2, 'SCAM', 'Fake bank KYC call'),
 ('refund.helpdesk@ybl',   3, 'SCAM', 'Sent a collect request'),
 ('refund.helpdesk@ybl',   4, 'SCAM', 'Pretended to be customer care'),
 ('refund.helpdesk@ybl',   5, 'SCAM', 'Same scam, different number'),
 ('lucky.lottery99@paytm', 1, 'SCAM', 'Lottery prize fee'),
 ('lucky.lottery99@paytm', 2, 'SCAM', 'Asked for processing fee'),
 ('lucky.lottery99@paytm', 3, 'SCAM', 'Fake lucky draw'),
 ('9876501234@ybl',        2, 'SPAM', 'Keeps sending payment requests'),
 ('9876501234@ybl',        4, 'SPAM', 'Unwanted promotional collect requests');
