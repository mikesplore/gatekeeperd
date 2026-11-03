ALTER TABLE customers ADD COLUMN first_name TEXT;
ALTER TABLE customers ADD COLUMN last_name TEXT;

UPDATE customers
SET first_name = split_part(trim(name), ' ', 1),
    last_name = CASE
        WHEN position(' ' IN trim(name)) > 0 THEN substring(trim(name) FROM position(' ' IN trim(name)) + 1)
        ELSE ''
    END;

ALTER TABLE customers ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE customers ALTER COLUMN last_name SET NOT NULL;
ALTER TABLE customers DROP COLUMN name;
