CREATE TABLE IF NOT EXISTS forward_hourly_traffic (
    forward_id INT NOT NULL,
    hour_start BIGINT NOT NULL,
    in_flow BIGINT NOT NULL DEFAULT 0,
    out_flow BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (forward_id, hour_start),
    KEY idx_forward_hour_start (hour_start)
) ENGINE=InnoDB;
