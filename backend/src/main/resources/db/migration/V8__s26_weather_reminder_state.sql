CREATE TABLE weather_reminder_state (
 trip_id BINARY(16) NOT NULL,
 city_code VARCHAR(64) NOT NULL,
 forecast_date DATE NOT NULL,
 risk_signature VARCHAR(32) NOT NULL,
 retrieved_at DATETIME(6) NOT NULL,
 PRIMARY KEY(trip_id,city_code,forecast_date),
 CONSTRAINT fk_weather_reminder_trip FOREIGN KEY(trip_id) REFERENCES trip(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
