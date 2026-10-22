package io.github.lakernote.observability.example;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class DemoDatabaseService {
    private final JdbcTemplate jdbc;

    public DemoDatabaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createAndReadOrder(String orderId, String customer) {
        jdbc.update("insert into demo_orders(order_id, customer_name, status, created_at) values (?, ?, ?, ?)",
                orderId, customer, "CREATED", OffsetDateTime.now());
        return jdbc.queryForMap("select order_id, customer_name, status, created_at from demo_orders where order_id = ?",
                orderId);
    }

    @Transactional
    public void insertThenRollback(String orderId) {
        jdbc.update("insert into demo_orders(order_id, customer_name, status, created_at) values (?, ?, ?, ?)",
                orderId, "rollback-demo", "ROLLBACK", OffsetDateTime.now());
        throw new IllegalStateException("Intentional demo failure; transaction should roll back");
    }

    @Transactional
    public void insertDuplicateOrder(String orderId) {
        insertOrder(orderId, "first insert");
        insertOrder(orderId, "duplicate insert");
    }

    private void insertOrder(String orderId, String customer) {
        jdbc.update("insert into demo_orders(order_id, customer_name, status, created_at) values (?, ?, ?, ?)",
                orderId, customer, "CREATED", OffsetDateTime.now());
    }
}
