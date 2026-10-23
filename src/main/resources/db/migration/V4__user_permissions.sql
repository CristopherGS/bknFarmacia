-- Permisos por usuario. El rol pasa a ser una plantilla inicial; el administrador activa o
-- desactiva cada permiso. ADMIN tiene todos implícitamente y no se guarda aquí.
CREATE TABLE user_permissions(
  user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
  permission VARCHAR(40) NOT NULL,
  PRIMARY KEY(user_id, permission)
);
-- Los usuarios existentes conservan lo que podían ver y hacer con su rol (mismas plantillas que
-- Permissions.java; PermissionMigrationTest comprueba que no se desalineen).
INSERT INTO user_permissions(user_id, permission)
SELECT u.id, t.permission
FROM users u
JOIN (VALUES
  ('SUPERVISOR','POS_SELL'),
  ('SUPERVISOR','SALES_VIEW'),
  ('SUPERVISOR','CASH_VIEW'),
  ('SUPERVISOR','CASH_MANAGE'),
  ('SUPERVISOR','CATALOG_VIEW'),
  ('SUPERVISOR','CATALOG_MANAGE'),
  ('SUPERVISOR','INVENTORY_VIEW'),
  ('SUPERVISOR','COSTS_VIEW'),
  ('SUPERVISOR','PURCHASES_VIEW'),
  ('SUPERVISOR','PURCHASES_RECEIVE'),
  ('SUPERVISOR','SUPPLIERS_VIEW'),
  ('SUPERVISOR','SUPPLIERS_MANAGE'),
  ('SUPERVISOR','AUDIT_VIEW'),
  ('CASHIER','POS_SELL'),
  ('CASHIER','SALES_VIEW'),
  ('CASHIER','CASH_VIEW'),
  ('CASHIER','CASH_MANAGE'),
  ('CASHIER','CATALOG_VIEW'),
  ('CASHIER','INVENTORY_VIEW'),
  ('INVENTORY','CATALOG_VIEW'),
  ('INVENTORY','INVENTORY_VIEW'),
  ('INVENTORY','COSTS_VIEW'),
  ('INVENTORY','PURCHASES_VIEW'),
  ('INVENTORY','PURCHASES_RECEIVE'),
  ('INVENTORY','SUPPLIERS_VIEW'),
  ('INVENTORY','SUPPLIERS_MANAGE'),
  ('ACCOUNTANT','SALES_VIEW'),
  ('ACCOUNTANT','CASH_VIEW'),
  ('ACCOUNTANT','CATALOG_VIEW'),
  ('ACCOUNTANT','INVENTORY_VIEW'),
  ('ACCOUNTANT','COSTS_VIEW'),
  ('ACCOUNTANT','PURCHASES_VIEW'),
  ('ACCOUNTANT','SUPPLIERS_VIEW'),
  ('ACCOUNTANT','AUDIT_VIEW')
) AS t(role, permission) ON t.role = u.role;
