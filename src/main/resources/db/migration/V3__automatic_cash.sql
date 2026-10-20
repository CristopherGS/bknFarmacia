ALTER TABLE cash_sessions ADD COLUMN automatic BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cash_sessions ADD COLUMN business_date DATE;
ALTER TABLE cash_sessions ADD COLUMN close_mode VARCHAR(30);
ALTER TABLE cash_sessions ADD COLUMN recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE UNIQUE INDEX automatic_cash_day ON cash_sessions(branch_id,business_date) WHERE automatic=TRUE;
