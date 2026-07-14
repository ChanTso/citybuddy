GRANT CREATE ON commerce_db.* TO 'auth_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, TRIGGER ON commerce_db.auth_schema_history TO 'auth_migration'@'%';
GRANT CREATE ON commerce_db.* TO 'commerce_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, TRIGGER ON commerce_db.commerce_schema_history TO 'commerce_migration'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER ON cs_db.* TO 'agent_migration'@'%';
