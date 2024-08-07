package com.commafeed;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import org.mockserver.socket.PortFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.codahale.metrics.MetricRegistry;
import com.commafeed.CommaFeedConfiguration.CacheType;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class CommaFeedDropwizardAppExtension extends DropwizardAppExtension<CommaFeedConfiguration> {
	private static final String TEST_DATABASE = System.getenv().getOrDefault("TEST_DATABASE", "h2");
	private static final boolean REDIS_ENABLED = Boolean.parseBoolean(System.getenv().getOrDefault("REDIS", "false"));

	private static final ConfigOverride[] CONFIG_OVERRIDES;
	private static final List<String> DROP_ALL_STATEMENTS;
	static {
		List<ConfigOverride> overrides = new ArrayList<>();
		overrides.add(ConfigOverride.config("server.applicationConnectors[0].port", String.valueOf(PortFactory.findFreePort())));

		Properties imageNames = readProperties("/docker-images.properties");

		DatabaseConfiguration config = buildConfiguration(TEST_DATABASE, imageNames.getProperty(TEST_DATABASE));
		JdbcDatabaseContainer<?> container = config.container();
		if (container != null) {
			container.withDatabaseName("commafeed");
			container.withEnv("TZ", "UTC");
			container.start();

			overrides.add(ConfigOverride.config("database.url", container.getJdbcUrl()));
			overrides.add(ConfigOverride.config("database.user", container.getUsername()));
			overrides.add(ConfigOverride.config("database.password", container.getPassword()));
			overrides.add(ConfigOverride.config("database.driverClass", container.getDriverClassName()));
		}

		if (REDIS_ENABLED) {
			GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(imageNames.getProperty("redis")))
					.withExposedPorts(6379);
			redis.start();

			overrides.add(ConfigOverride.config("app.cache", "redis"));
			overrides.add(ConfigOverride.config("redis.host", redis.getHost()));
			overrides.add(ConfigOverride.config("redis.port", redis.getMappedPort(6379).toString()));
		}

		CONFIG_OVERRIDES = overrides.toArray(new ConfigOverride[0]);
		DROP_ALL_STATEMENTS = config.dropAllStatements();
	}

	public CommaFeedDropwizardAppExtension() {
		super(CommaFeedApplication.class, ResourceHelpers.resourceFilePath("config.test.yml"), CONFIG_OVERRIDES);
	}

	private static DatabaseConfiguration buildConfiguration(String databaseName, String imageName) {
		if ("postgresql".equals(databaseName)) {
			JdbcDatabaseContainer<?> container = new PostgreSQLContainer<>(imageName).withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));
			return new DatabaseConfiguration(container, List.of("DROP SCHEMA public CASCADE", "CREATE SCHEMA public"));
		} else if ("mysql".equals(databaseName)) {
			JdbcDatabaseContainer<?> container = new MySQLContainer<>(imageName).withTmpFs(Map.of("/var/lib/mysql", "rw"));
			return new DatabaseConfiguration(container, List.of("DROP DATABASE IF EXISTS commafeed", " CREATE DATABASE commafeed"));
		} else if ("mariadb".equals(databaseName)) {
			JdbcDatabaseContainer<?> container = new MariaDBContainer<>(imageName).withTmpFs(Map.of("/var/lib/mysql", "rw"));
			return new DatabaseConfiguration(container, List.of("DROP DATABASE IF EXISTS commafeed", " CREATE DATABASE commafeed"));
		} else {
			// h2
			return new DatabaseConfiguration(null, List.of("DROP ALL OBJECTS"));
		}
	}

	private static Properties readProperties(String path) {
		Properties properties = new Properties();
		try (InputStream is = CommaFeedDropwizardAppExtension.class.getResourceAsStream(path)) {
			properties.load(is);
		} catch (IOException e) {
			throw new RuntimeException("could not read resource " + path, e);
		}
		return properties;
	}

	@Override
	public void after() {
		super.after();

		// clean database after each test
		DataSource dataSource = getConfiguration().getDataSourceFactory().build(new MetricRegistry(), "cleanup");
		try (Connection connection = dataSource.getConnection()) {
			for (String statement : DROP_ALL_STATEMENTS) {
				connection.prepareStatement(statement).executeUpdate();
			}
		} catch (SQLException e) {
			throw new RuntimeException("could not cleanup database", e);
		}

		// clean redis cache after each test
		if (getConfiguration().getApplicationSettings().getCache() == CacheType.REDIS) {
			try (JedisPool pool = getConfiguration().getRedisPoolFactory().build(); Jedis jedis = pool.getResource()) {
				jedis.flushAll();
			}
		}
	}

	private record DatabaseConfiguration(JdbcDatabaseContainer<?> container, List<String> dropAllStatements) {
	}

}
