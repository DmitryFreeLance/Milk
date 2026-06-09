package ru.milk.maxbot.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.milk.maxbot.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final AppConfig config;

    public Database(AppConfig config) {
        this.config = config;
    }

    public void init() {
        try {
            Files.createDirectories(config.dbPath().toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create database directory", e);
        }

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS receiving_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        active INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS farms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        active INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        max_user_id INTEGER NOT NULL UNIQUE,
                        chat_id INTEGER,
                        username TEXT,
                        first_name TEXT,
                        last_name TEXT,
                        display_name TEXT NOT NULL,
                        phone TEXT,
                        role TEXT NOT NULL,
                        receiving_point_id INTEGER,
                        active INTEGER NOT NULL DEFAULT 1,
                        daily_digest_enabled INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (receiving_point_id) REFERENCES receiving_points (id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS registration_requests (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        requested_role TEXT,
                        requested_point_id INTEGER,
                        comment TEXT,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (user_id) REFERENCES users (id),
                        FOREIGN KEY (requested_point_id) REFERENCES receiving_points (id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS milk_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        public_id TEXT NOT NULL UNIQUE,
                        created_by_user_id INTEGER NOT NULL,
                        point_id INTEGER NOT NULL,
                        farm_id INTEGER NOT NULL,
                        section_label TEXT NOT NULL,
                        delivery_date TEXT NOT NULL,
                        weight_kg REAL NOT NULL,
                        fat_percent REAL NOT NULL,
                        protein_percent REAL NOT NULL,
                        credit_weight_kg REAL NOT NULL,
                        photo_token TEXT,
                        photo_payload_json TEXT,
                        photo_width INTEGER,
                        photo_height INTEGER,
                        photo_status TEXT NOT NULL,
                        original_message_id TEXT,
                        note TEXT,
                        editable_until TEXT NOT NULL,
                        admin_override_unlocked_until TEXT,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (created_by_user_id) REFERENCES users (id),
                        FOREIGN KEY (point_id) REFERENCES receiving_points (id),
                        FOREIGN KEY (farm_id) REFERENCES farms (id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS receipt_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        receipt_id INTEGER NOT NULL,
                        changed_by_user_id INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        before_json TEXT,
                        after_json TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY (receipt_id) REFERENCES milk_receipts (id),
                        FOREIGN KEY (changed_by_user_id) REFERENCES users (id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_sessions (
                        max_user_id INTEGER PRIMARY KEY,
                        state TEXT NOT NULL,
                        data_json TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS daily_digest_log (
                        digest_date TEXT NOT NULL,
                        recipient_user_id INTEGER NOT NULL,
                        sent_at TEXT NOT NULL,
                        PRIMARY KEY (digest_date, recipient_user_id),
                        FOREIGN KEY (recipient_user_id) REFERENCES users (id)
                    )
                    """);

            seedReferenceData(connection);
            log.info("Database initialized at {}", config.dbPath().toAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("Database initialization failed", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + config.dbPath().toAbsolutePath());
    }

    private void seedReferenceData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT OR IGNORE INTO receiving_points (id, name, active) VALUES (1, 'Чепас', 1)");
            statement.executeUpdate("INSERT OR IGNORE INTO receiving_points (id, name, active) VALUES (2, 'Большая Арать', 1)");
            statement.executeUpdate("INSERT OR IGNORE INTO receiving_points (id, name, active) VALUES (3, 'Пильна', 1)");

            String now = java.time.Instant.now().toString();
            statement.executeUpdate("INSERT OR IGNORE INTO farms (name, active, created_at, updated_at) VALUES ('Березники', 1, '%s', '%s')".formatted(now, now));
            statement.executeUpdate("INSERT OR IGNORE INTO farms (name, active, created_at, updated_at) VALUES ('Языково', 1, '%s', '%s')".formatted(now, now));
            statement.executeUpdate("INSERT OR IGNORE INTO farms (name, active, created_at, updated_at) VALUES ('Байково', 1, '%s', '%s')".formatted(now, now));
            statement.executeUpdate("INSERT OR IGNORE INTO farms (name, active, created_at, updated_at) VALUES ('Тарталей', 1, '%s', '%s')".formatted(now, now));
        }
    }
}
