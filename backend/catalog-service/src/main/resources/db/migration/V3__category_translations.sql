ALTER TABLE categories ADD COLUMN name_vi varchar(255);
UPDATE categories SET name_vi = name WHERE name_vi IS NULL;
