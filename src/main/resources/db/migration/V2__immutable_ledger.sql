CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Historical ledger rows are immutable. Use compensating documents.'; END;
$$;
CREATE TRIGGER immutable_stock_movements BEFORE UPDATE OR DELETE ON stock_movements FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_sales BEFORE UPDATE OR DELETE ON sales FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_sale_lines BEFORE UPDATE OR DELETE ON sale_lines FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_allocations BEFORE UPDATE OR DELETE ON allocations FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_receipts BEFORE UPDATE OR DELETE ON receipts FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
