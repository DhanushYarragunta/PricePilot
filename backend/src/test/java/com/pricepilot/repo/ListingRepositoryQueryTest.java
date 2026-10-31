package com.pricepilot.repo;

import com.pricepilot.domain.AlertLog;
import com.pricepilot.domain.ApiUsage;
import com.pricepilot.domain.AppUser;
import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.ScrapeAttempt;
import com.pricepilot.domain.Tracker;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate/Spring Data query compilation; deliberately does not execute SQL or use .env. */
class ListingRepositoryQueryTest {
    @Test void schedulerAndWriteLockQueriesCompileAgainstMappedEntitiesWithoutDatabaseAccess() {
        var connections = new NoConnections();
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", false)
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .addService(ConnectionProvider.class, connections)
                .build();
        try {
            var mappings = new MetadataSources(registry);
            for (Class<?> entity : List.of(AppUser.class, Product.class, Listing.class, Tracker.class,
                    PricePoint.class, ScrapeAttempt.class, DealAnalysis.class, ApiUsage.class, AlertLog.class)) {
                mappings.addAnnotatedClass(entity);
            }
            try (var sessions = mappings.buildMetadata().buildSessionFactory();
                 var entityManager = sessions.createEntityManager()) {
                // Repository creation asks Hibernate to validate every declared JPQL query and
                // Spring Data to resolve all derived methods and their parameter/return metadata.
                var repository = new JpaRepositoryFactory(entityManager).getRepository(ListingRepository.class);
                assertThat(repository).isNotNull();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
        assertThat(connections.requests.get()).isZero();
    }

    private static final class NoConnections implements ConnectionProvider {
        private final AtomicInteger requests = new AtomicInteger();
        @Override public Connection getConnection() throws SQLException {
            requests.incrementAndGet();
            throw new SQLException("This query-compilation test must not request a database connection");
        }
        @Override public void closeConnection(Connection connection) { throw new AssertionError("No connection exists"); }
        @Override public boolean supportsAggressiveRelease() { return false; }
        @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) { return type.cast(this); }
    }
}
