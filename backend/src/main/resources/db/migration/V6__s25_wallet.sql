CREATE TABLE wallet_quote (
 id BINARY(16) NOT NULL,
 actor_id BINARY(16) NOT NULL,
 user_id BINARY(16) NOT NULL,
 quote_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 package_code VARCHAR(32),
 points_delta BIGINT NOT NULL,
 account_version BIGINT,
 balance_after BIGINT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 applied_reference VARCHAR(128),
 PRIMARY KEY (id),
 UNIQUE KEY uk_wallet_quote_request (actor_id, quote_key),
 UNIQUE KEY uk_wallet_quote_application (applied_reference),
 CONSTRAINT fk_wallet_quote_actor FOREIGN KEY (actor_id) REFERENCES user_account(id),
 CONSTRAINT fk_wallet_quote_user FOREIGN KEY (user_id) REFERENCES user_account(id),
 CONSTRAINT ck_wallet_quote_delta CHECK (points_delta <> 0),
 CONSTRAINT ck_wallet_quote_balance CHECK (balance_after >= 0),
 CONSTRAINT ck_wallet_quote_kind CHECK (
   (package_code IS NOT NULL AND actor_id = user_id AND account_version IS NULL AND points_delta > 0)
   OR (package_code IS NULL AND account_version IS NOT NULL AND account_version >= 0)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
