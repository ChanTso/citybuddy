CREATE UNIQUE INDEX uq_auth_signing_key_one_current
  ON auth_signing_key_metadata ((CASE WHEN state = 'CURRENT' THEN 1 ELSE NULL END));
