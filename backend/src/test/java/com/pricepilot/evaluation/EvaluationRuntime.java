package com.pricepilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiUsage;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.repo.ApiUsageRepository;
import com.pricepilot.scrape.PolitenessGate;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.browseract.BrowserActClient;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.usage.BudgetGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Local evaluation wiring only. Never scanned or packaged into the production application. */
final class EvaluationRuntime implements AutoCloseable {
    static final class ConfigurationFailure extends IllegalArgumentException {
        final String category;
        ConfigurationFailure(String category) { super(category); this.category = category; }
    }
    private static final Pattern LOCAL_DATABASE = Pattern.compile(
            "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::([1-9][0-9]{0,4}))?/([A-Za-z0-9_][A-Za-z0-9_-]{0,62})$");
    final AppProperties properties;
    final BudgetGuard budget;
    final ScrapeEngine engine;
    final UrlCanonicalizer canonicalizer;
    final DomainRules rules;
    final ScrapeMetrics metrics;
    final List<String> secrets;
    private final HikariDataSource dataSource;
    private final LocalContainerEntityManagerFactoryBean entityManagerFactory;
    private final SimpleMeterRegistry registry;

    static ConfigurableEnvironment environment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // This is the app's actual base YAML and placeholder binding. No Boot application,
        // component scan, profile activation, Flyway, schema updater, or scheduler is started.
        environment.getPropertySources().addLast(new YamlPropertySourceLoader()
                .load("evaluation-application", new ClassPathResource("application.yml")).get(0));
        return environment;
    }

    static String localDatabaseUrl(String value) {
        var match = LOCAL_DATABASE.matcher(value == null ? "" : value);
        if (!match.matches() || (match.group(2) != null && Integer.parseInt(match.group(2)) > 65535)) {
            throw new ConfigurationFailure("loopback_database_configuration_required");
        }
        return value;
    }

    EvaluationRuntime(ConfigurableEnvironment environment, boolean readOnly) {
        properties = Binder.get(environment).bind("pricepilot", Bindable.of(AppProperties.class))
                .orElseThrow(() -> new IllegalArgumentException("Application configuration missing."));
        var browserAct = properties.getBrowserAct();
        if (!browserAct.isConfigured() || browserAct.getTimeoutMs() <= 0
                || browserAct.getApiKey().chars().anyMatch(Character::isWhitespace)
                || browserAct.getWorkflowId().chars().anyMatch(Character::isWhitespace)
                || (browserAct.getMonthlyCallBudget() <= 0 && browserAct.getMonthlyCallBudget() != -1)) {
            throw new ConfigurationFailure("browseract_configuration_missing_disabled_or_invalid");
        }
        String databaseUrl = localDatabaseUrl(environment.getRequiredProperty("spring.datasource.url"));
        String username = environment.getRequiredProperty("spring.datasource.username");
        String password = environment.getRequiredProperty("spring.datasource.password");
        secrets = List.of(browserAct.getApiKey(), browserAct.getWorkflowId(), password);

        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(databaseUrl);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(10000);
        pool.setInitializationFailTimeout(-1);
        pool.setReadOnly(readOnly);
        pool.setPoolName("pricepilot-evaluation-budget");
        dataSource = new HikariDataSource(pool);
        entityManagerFactory = new LocalContainerEntityManagerFactoryBean();
        registry = new SimpleMeterRegistry();
        try {
            entityManagerFactory.setDataSource(dataSource);
            // Only this one entity can be persisted through this EntityManagerFactory.
            entityManagerFactory.setManagedTypes(PersistenceManagedTypes.of(ApiUsage.class.getName()));
            entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            entityManagerFactory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "jakarta.persistence.schema-generation.database.action", "none",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                    "hibernate.show_sql", "false",
                    "hibernate.jdbc.time_zone", "UTC"));
            entityManagerFactory.afterPropertiesSet();
            var factory = entityManagerFactory.getObject();
            var repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(ApiUsageRepository.class);
            budget = transactionalBudget(repository, new JpaTransactionManager(factory));
            rules = new DomainRules(properties, new DefaultResourceLoader());
            rules.reload();
            canonicalizer = new UrlCanonicalizer(rules);
            metrics = new ScrapeMetrics(registry);
            var client = new BrowserActClient(properties, new ObjectMapper(), budget, canonicalizer, metrics);
            engine = new ScrapeEngine(rules, new PolitenessGate(properties), canonicalizer,
                    client, properties, metrics);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    static BudgetGuard transactionalBudget(ApiUsageRepository repository,
                                            org.springframework.transaction.PlatformTransactionManager manager) {
        TransactionInterceptor transactions = new TransactionInterceptor();
        transactions.setTransactionManager(manager);
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        transactions.afterPropertiesSet();
        ProxyFactory proxy = new ProxyFactory(new BudgetGuard(repository));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(transactions);
        return (BudgetGuard) proxy.getProxy();
    }

    @Override public void close() {
        entityManagerFactory.destroy();
        dataSource.close();
        registry.close();
    }
}
