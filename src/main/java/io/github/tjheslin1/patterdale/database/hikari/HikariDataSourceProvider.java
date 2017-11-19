/*
 * Copyright 2017 Thomas Heslin <tjheslin1@gmail.com>.
 *
 * This file is part of Patterdale-jvm.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.tjheslin1.patterdale.database.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.github.tjheslin1.patterdale.PatterdaleRuntimeParameters;
import io.github.tjheslin1.patterdale.config.Passwords;
import io.github.tjheslin1.patterdale.metrics.probe.DatabaseDefinition;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import oracle.jdbc.pool.OracleDataSource;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static io.github.tjheslin1.patterdale.database.JdbcUrlFormatter.databaseUrlWithCredentials;
import static java.lang.String.format;

public class HikariDataSourceProvider {

    public static HikariDataSource retriableDataSource(PatterdaleRuntimeParameters runtimeParams, DatabaseDefinition databaseDefinition, Passwords passwords, Logger logger) {
        RetryPolicy retryPolicy = new RetryPolicy()
                .retryOn(HikariPool.PoolInitializationException.class)
                .withDelay(runtimeParams.connectionRetryDelayInSeconds(), TimeUnit.SECONDS)
                .withMaxRetries(runtimeParams.maxConnectionRetries());

        return Failsafe.with(retryPolicy)
                .onRetry((result, failure, context) -> logRetry(runtimeParams, databaseDefinition, logger))
                .onFailedAttempt((result, failure, context) -> logFailedAttempt(databaseDefinition, logger))
                .onRetriesExceeded(throwable -> logRetriesExceeded(databaseDefinition, logger))
                .get(() -> dataSource(runtimeParams, databaseDefinition, passwords, logger));
    }

    private static HikariDataSource dataSource(PatterdaleRuntimeParameters runtimeParameters, DatabaseDefinition databaseDefinition, Passwords passwords, Logger logger)
            throws SQLException {
        try {
            OracleDataSource oracleDataSource = new OracleDataSource();
            oracleDataSource.setUser(databaseDefinition.user);

            String password = passwords.byDatabaseName(databaseDefinition.name).value;
            oracleDataSource.setPassword(password);

            HikariDataSource hikariDataSource = new HikariDataSource(jdbcConfig(runtimeParameters, databaseDefinition, password));
            hikariDataSource.setDataSource(oracleDataSource);
            return hikariDataSource;
        } catch (Exception e) {
            logger.error("Error occurred initialising Oracle and Hikari data sources.", e);
            throw e;    // caught by the RetryPolicy
        }
    }

    private static HikariConfig jdbcConfig(PatterdaleRuntimeParameters runtimeParameters, DatabaseDefinition databaseDefinition, String password) {
        HikariConfig jdbcConfig = new HikariConfig();
        jdbcConfig.setPoolName("patterdale-pool-" + databaseDefinition.name);
        jdbcConfig.setMaximumPoolSize(runtimeParameters.connectionPoolMaxSize());
        jdbcConfig.setMinimumIdle(runtimeParameters.connectionPoolMinIdle());
        jdbcConfig.setJdbcUrl(databaseUrlWithCredentials(databaseDefinition.jdbcUrl, databaseDefinition.user, password));
        return jdbcConfig;
    }

    private static void logRetriesExceeded(DatabaseDefinition databaseDefinition, Logger logger) {
        logger.error(format("Exceeded retry attempts to database %s at %s.",
                databaseDefinition.name,
                databaseDefinition.jdbcUrl));
    }

    private static void logFailedAttempt(DatabaseDefinition databaseDefinition, Logger logger) {
        logger.warn(format("Failed attempt connecting to database %s at %s." +
                        databaseDefinition.name,
                databaseDefinition.jdbcUrl));
    }

    private static void logRetry(PatterdaleRuntimeParameters runtimeParams, DatabaseDefinition databaseDefinition, Logger logger) {
        logger.info(format("Attempting database connection to: %s at %s.%n" +
                        "Configured to retry %d times with a delay between retries of %d seconds.",
                databaseDefinition.name,
                databaseDefinition.jdbcUrl,
                runtimeParams.maxConnectionRetries(),
                runtimeParams.connectionRetryDelayInSeconds()));
    }
}
