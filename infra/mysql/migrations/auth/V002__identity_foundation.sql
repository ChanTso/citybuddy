CREATE TABLE auth_user_principal (
  principal_id CHAR(36) NOT NULL,
  subject VARCHAR(190) NOT NULL,
  login_identifier VARCHAR(190) NOT NULL,
  state ENUM('ACTIVE', 'DISABLED') NOT NULL,
  permissions VARCHAR(512) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (principal_id),
  UNIQUE KEY uq_auth_user_subject (subject),
  UNIQUE KEY uq_auth_user_login (login_identifier)
) ENGINE=InnoDB;

CREATE TABLE auth_login_credential (
  principal_id CHAR(36) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (principal_id),
  CONSTRAINT fk_auth_login_principal
    FOREIGN KEY (principal_id) REFERENCES auth_user_principal (principal_id)
    ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE auth_service_identity (
  service_id CHAR(36) NOT NULL,
  client_id VARCHAR(190) NOT NULL,
  credential_hash VARCHAR(100) NOT NULL,
  state ENUM('ACTIVE', 'REVOKED') NOT NULL,
  allowed_scopes VARCHAR(512) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (service_id),
  UNIQUE KEY uq_auth_service_client (client_id)
) ENGINE=InnoDB;

CREATE TABLE auth_signing_key_metadata (
  kid VARCHAR(128) NOT NULL,
  state ENUM('CURRENT', 'OVERLAP', 'RETIRED') NOT NULL,
  activated_at TIMESTAMP(6) NOT NULL,
  retire_after TIMESTAMP(6) NULL,
  PRIMARY KEY (kid),
  CONSTRAINT chk_auth_key_retirement
    CHECK (state <> 'OVERLAP' OR (retire_after IS NOT NULL AND retire_after > activated_at))
) ENGINE=InnoDB;
