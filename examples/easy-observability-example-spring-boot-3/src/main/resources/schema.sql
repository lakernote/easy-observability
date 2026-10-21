CREATE TABLE IF NOT EXISTS demo_orders (
    order_id VARCHAR(36) PRIMARY KEY,
    customer_name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
