package ru.milk.maxbot.repository;

import com.fasterxml.jackson.databind.JsonNode;
import ru.milk.maxbot.db.Database;
import ru.milk.maxbot.domain.BotUser;
import ru.milk.maxbot.domain.ConversationSession;
import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.NamedSummary;
import ru.milk.maxbot.domain.PendingRegistration;
import ru.milk.maxbot.domain.ReceivingPoint;
import ru.milk.maxbot.domain.StatsSummary;
import ru.milk.maxbot.domain.UserRole;
import ru.milk.maxbot.util.Jsons;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BotRepository {
    private final Database database;

    public BotRepository(Database database) {
        this.database = database;
    }

    public BotUser upsertUser(long maxUserId, Long chatId, String username, String firstName, String lastName, boolean bootstrapAdmin) {
        String displayName = buildDisplayName(firstName, lastName, username, maxUserId);
        String now = Instant.now().toString();
        try (Connection connection = database.getConnection()) {
            Optional<BotUser> existing = findUserByMaxId(connection, maxUserId);
            if (existing.isPresent()) {
                String updateSql = """
                        UPDATE users
                           SET chat_id = COALESCE(?, chat_id),
                               username = CASE
                                   WHEN (username IS NULL OR TRIM(username) = '')
                                        AND ? IS NOT NULL
                                        AND TRIM(?) <> '' THEN ?
                                   ELSE username
                               END,
                               first_name = CASE
                                   WHEN (first_name IS NULL OR TRIM(first_name) = '')
                                        AND ? IS NOT NULL
                                        AND TRIM(?) <> '' THEN ?
                                   ELSE first_name
                               END,
                               last_name = CASE
                                   WHEN (last_name IS NULL OR TRIM(last_name) = '')
                                        AND ? IS NOT NULL
                                        AND TRIM(?) <> '' THEN ?
                                   ELSE last_name
                               END,
                               display_name = CASE
                                   WHEN display_name IS NULL OR TRIM(display_name) = '' THEN ?
                                   ELSE display_name
                               END,
                               updated_at = ?,
                               role = CASE
                                   WHEN role = 'PENDING' AND ? = 1 THEN 'ADMINISTRATOR'
                                   ELSE role
                               END
                         WHERE max_user_id = ?
                        """;
                try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                    bindNullableLong(ps, 1, chatId);
                    ps.setString(2, username);
                    ps.setString(3, username);
                    ps.setString(4, username);
                    ps.setString(5, firstName);
                    ps.setString(6, firstName);
                    ps.setString(7, firstName);
                    ps.setString(8, lastName);
                    ps.setString(9, lastName);
                    ps.setString(10, lastName);
                    ps.setString(11, displayName);
                    ps.setString(12, now);
                    ps.setInt(13, bootstrapAdmin ? 1 : 0);
                    ps.setLong(14, maxUserId);
                    ps.executeUpdate();
                }
            } else {
                String insertSql = """
                        INSERT INTO users (
                            max_user_id, chat_id, username, first_name, last_name, display_name, phone, role,
                            receiving_point_id, active, daily_digest_enabled, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, 1, 1, ?, ?)
                        """;
                try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                    ps.setLong(1, maxUserId);
                    bindNullableLong(ps, 2, chatId);
                    ps.setString(3, username);
                    ps.setString(4, firstName);
                    ps.setString(5, lastName);
                    ps.setString(6, displayName);
                    ps.setString(7, bootstrapAdmin ? UserRole.ADMINISTRATOR.name() : UserRole.PENDING.name());
                    ps.setString(8, now);
                    ps.setString(9, now);
                    ps.executeUpdate();
                }
            }
            return findUserByMaxId(connection, maxUserId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert user", e);
        }
    }

    public Optional<BotUser> findUserByMaxId(long maxUserId) {
        try (Connection connection = database.getConnection()) {
            return findUserByMaxId(connection, maxUserId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find user", e);
        }
    }

    public List<BotUser> listAdmins() {
        return listUsersByRole(UserRole.ADMINISTRATOR);
    }

    public List<BotUser> listReportRecipients() {
        String sql = """
                SELECT * FROM users
                 WHERE active = 1
                   AND daily_digest_enabled = 1
                   AND role IN ('DIRECTOR', 'GENERAL_DIRECTOR', 'ADMINISTRATOR')
                 ORDER BY display_name
                """;
        return queryUsers(sql);
    }

    public List<BotUser> listUsers() {
        return queryUsers("SELECT * FROM users ORDER BY display_name");
    }

    public int countUsers() {
        return countBySql("SELECT COUNT(*) FROM users");
    }

    public List<BotUser> listUsersPage(int limit, int offset) {
        String sql = "SELECT * FROM users ORDER BY display_name LIMIT ? OFFSET ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return executeUserList(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list users page", e);
        }
    }

    public Optional<BotUser> findUserById(long userId) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapUser(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find user by id", e);
        }
    }

    public void setUserRoleAndPoint(long userId, UserRole role, Long pointId) {
        String sql = """
                UPDATE users
                   SET role = ?, receiving_point_id = ?, updated_at = ?
                 WHERE id = ?
                """;
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, role.name());
            bindNullableLong(ps, 2, pointId);
            ps.setString(3, Instant.now().toString());
            ps.setLong(4, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update user role", e);
        }
    }

    public void setUserActive(long userId, boolean active) {
        String sql = "UPDATE users SET active = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update user active flag", e);
        }
    }

    public void setDailyDigestEnabled(long userId, boolean enabled) {
        String sql = "UPDATE users SET daily_digest_enabled = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update digest flag", e);
        }
    }

    public List<ReceivingPoint> listPoints() {
        String sql = "SELECT id, name, active FROM receiving_points ORDER BY id";
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<ReceivingPoint> points = new ArrayList<>();
            while (rs.next()) {
                points.add(new ReceivingPoint(rs.getLong("id"), rs.getString("name"), rs.getInt("active") == 1));
            }
            return points;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list points", e);
        }
    }

    public Optional<ReceivingPoint> findPoint(long id) {
        String sql = "SELECT id, name, active FROM receiving_points WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReceivingPoint(rs.getLong("id"), rs.getString("name"), rs.getInt("active") == 1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find point", e);
        }
    }

    public List<Farm> listFarms(boolean activeOnly) {
        String sql = "SELECT id, name, active FROM farms " + (activeOnly ? "WHERE active = 1 " : "") + "ORDER BY name";
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Farm> farms = new ArrayList<>();
            while (rs.next()) {
                farms.add(new Farm(rs.getLong("id"), rs.getString("name"), rs.getInt("active") == 1));
            }
            return farms;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list farms", e);
        }
    }

    public Optional<Farm> findFarm(long id) {
        String sql = "SELECT id, name, active FROM farms WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Farm(rs.getLong("id"), rs.getString("name"), rs.getInt("active") == 1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find farm", e);
        }
    }

    public Farm addFarm(String name) {
        String now = Instant.now().toString();
        String sql = "INSERT INTO farms (name, active, created_at, updated_at) VALUES (?, 1, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name.trim());
            ps.setString(2, now);
            ps.setString(3, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return new Farm(rs.getLong(1), name.trim(), true);
                }
                throw new IllegalStateException("Farm ID was not generated");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add farm", e);
        }
    }

    public void setFarmActive(long id, boolean active) {
        String sql = "UPDATE farms SET active = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update farm", e);
        }
    }

    public PendingRegistration createOrUpdateRegistration(long userId, String requestedRole, Long requestedPointId, String comment) {
        String now = Instant.now().toString();
        try (Connection connection = database.getConnection()) {
            Optional<Long> existingPending = findPendingRegistrationIdByUser(connection, userId);
            if (existingPending.isPresent()) {
                String updateSql = """
                        UPDATE registration_requests
                           SET requested_role = ?, requested_point_id = ?, comment = ?, updated_at = ?, status = 'PENDING'
                         WHERE id = ?
                        """;
                try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                    ps.setString(1, requestedRole);
                    bindNullableLong(ps, 2, requestedPointId);
                    ps.setString(3, comment);
                    ps.setString(4, now);
                    ps.setLong(5, existingPending.get());
                    ps.executeUpdate();
                }
            } else {
                String insertSql = """
                        INSERT INTO registration_requests (
                            user_id, requested_role, requested_point_id, comment, status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                        """;
                try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                    ps.setLong(1, userId);
                    ps.setString(2, requestedRole);
                    bindNullableLong(ps, 3, requestedPointId);
                    ps.setString(4, comment);
                    ps.setString(5, now);
                    ps.setString(6, now);
                    ps.executeUpdate();
                }
            }
            return listPendingRegistrations().stream()
                    .filter(it -> it.userId() == userId)
                    .findFirst()
                    .orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create registration request", e);
        }
    }

    public List<PendingRegistration> listPendingRegistrations() {
        String sql = """
                SELECT rr.id,
                       rr.user_id,
                       u.max_user_id,
                       u.display_name,
                       u.phone,
                       rr.requested_role,
                       rr.requested_point_id,
                       rp.name AS requested_point_name,
                       rr.comment,
                       rr.status,
                       rr.created_at
                  FROM registration_requests rr
                  JOIN users u ON u.id = rr.user_id
             LEFT JOIN receiving_points rp ON rp.id = rr.requested_point_id
                 WHERE rr.status = 'PENDING'
                 ORDER BY rr.created_at
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<PendingRegistration> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new PendingRegistration(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getLong("max_user_id"),
                        rs.getString("display_name"),
                        rs.getString("phone"),
                        rs.getString("requested_role"),
                        nullableLong(rs, "requested_point_id"),
                        rs.getString("requested_point_name"),
                        rs.getString("comment"),
                        rs.getString("status"),
                        Instant.parse(rs.getString("created_at"))
                ));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list pending registrations", e);
        }
    }

    public Optional<PendingRegistration> findPendingRegistration(long requestId) {
        return listPendingRegistrations().stream().filter(it -> it.id() == requestId).findFirst();
    }

    public Optional<BotUser> findUserByRegistrationRequestId(long requestId) {
        String sql = """
                SELECT u.*
                  FROM registration_requests rr
                  JOIN users u ON u.id = rr.user_id
                 WHERE rr.id = ?
                """;
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapUser(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find user by registration request", e);
        }
    }

    public void approveRegistration(long requestId, UserRole role, Long pointId) {
        String now = Instant.now().toString();
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            long userId = getRegistrationUserId(connection, requestId);
            try (PreparedStatement userPs = connection.prepareStatement("""
                    UPDATE users
                       SET role = ?, receiving_point_id = ?, active = 1, updated_at = ?
                     WHERE id = ?
                    """);
                 PreparedStatement requestPs = connection.prepareStatement("""
                    UPDATE registration_requests
                       SET status = 'APPROVED', updated_at = ?
                     WHERE id = ?
                    """)) {
                userPs.setString(1, role.name());
                bindNullableLong(userPs, 2, pointId);
                userPs.setString(3, now);
                userPs.setLong(4, userId);
                userPs.executeUpdate();

                requestPs.setString(1, now);
                requestPs.setLong(2, requestId);
                requestPs.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to approve registration", e);
        }
    }

    public void rejectRegistration(long requestId) {
        String sql = "UPDATE registration_requests SET status = 'REJECTED', updated_at = ? WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setLong(2, requestId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reject registration", e);
        }
    }

    public void setUserPhone(long userId, String phone) {
        String sql = "UPDATE users SET phone = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, phone);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update phone", e);
        }
    }

    public void updateUserEditableField(long userId, String field, String value) {
        BotUser user = findUserById(userId).orElseThrow();
        String firstName = user.firstName();
        String lastName = user.lastName();
        String username = user.username();
        String phone = user.phone();

        switch (field) {
            case "first_name" -> firstName = value;
            case "last_name" -> lastName = value;
            case "username" -> username = value;
            case "phone" -> phone = value;
            default -> throw new IllegalArgumentException("Unknown editable field: " + field);
        }

        String displayName = buildDisplayName(firstName, lastName, username, user.maxUserId());
        String sql = """
                UPDATE users
                   SET first_name = ?,
                       last_name = ?,
                       username = ?,
                       display_name = ?,
                       phone = ?,
                       updated_at = ?
                 WHERE id = ?
                """;
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, firstName);
            ps.setString(2, lastName);
            ps.setString(3, username);
            ps.setString(4, displayName);
            ps.setString(5, phone);
            ps.setString(6, Instant.now().toString());
            ps.setLong(7, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update user editable field", e);
        }
    }

    public ConversationSession getSession(long maxUserId) {
        String sql = "SELECT state, data_json FROM user_sessions WHERE max_user_id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, maxUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new ConversationSession("IDLE", Jsons.object());
                }
                return new ConversationSession(rs.getString("state"), Jsons.readTree(rs.getString("data_json")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read session", e);
        }
    }

    public void saveSession(long maxUserId, String state, JsonNode data) {
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO user_sessions (max_user_id, state, data_json, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(max_user_id) DO UPDATE SET
                    state = excluded.state,
                    data_json = excluded.data_json,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, maxUserId);
            ps.setString(2, state);
            ps.setString(3, Jsons.write(data));
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save session", e);
        }
    }

    public MilkReceipt createReceipt(long createdByUserId,
                                     long pointId,
                                     long farmId,
                                     String sectionLabel,
                                     LocalDate deliveryDate,
                                     double weightKg,
                                     double fatPercent,
                                     double proteinPercent,
                                     double creditWeightKg,
                                     String photoToken,
                                     String photoPayloadJson,
                                     Integer photoWidth,
                                     Integer photoHeight,
                                     String photoStatus,
                                     String originalMessageId,
                                     String note) {
        String publicId = "REC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant now = Instant.now();
        Instant editableUntil = now.plusSeconds(3600);
        String sql = """
                INSERT INTO milk_receipts (
                    public_id, created_by_user_id, point_id, farm_id, section_label, delivery_date,
                    weight_kg, fat_percent, protein_percent, credit_weight_kg, photo_token, photo_payload_json,
                    photo_width, photo_height, photo_status, original_message_id, note, editable_until,
                    admin_override_unlocked_until, deleted, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, ?, ?)
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, publicId);
            ps.setLong(2, createdByUserId);
            ps.setLong(3, pointId);
            ps.setLong(4, farmId);
            ps.setString(5, sectionLabel);
            ps.setString(6, deliveryDate.toString());
            ps.setDouble(7, weightKg);
            ps.setDouble(8, fatPercent);
            ps.setDouble(9, proteinPercent);
            ps.setDouble(10, creditWeightKg);
            ps.setString(11, photoToken);
            ps.setString(12, photoPayloadJson);
            bindNullableInt(ps, 13, photoWidth);
            bindNullableInt(ps, 14, photoHeight);
            ps.setString(15, photoStatus);
            ps.setString(16, originalMessageId);
            ps.setString(17, note);
            ps.setString(18, editableUntil.toString());
            ps.setString(19, now.toString());
            ps.setString(20, now.toString());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Receipt ID was not generated");
                }
                long id = rs.getLong(1);
                return findReceiptById(id).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create receipt", e);
        }
    }

    public Optional<MilkReceipt> findReceiptById(long receiptId) {
        String sql = baseReceiptSelect() + " WHERE mr.id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, receiptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapReceipt(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find receipt", e);
        }
    }

    public List<MilkReceipt> listRecentReceiptsForUser(long userId, int limit) {
        String sql = baseReceiptSelect() + """
                 WHERE mr.created_by_user_id = ? AND mr.deleted = 0
                 ORDER BY mr.created_at DESC
                 LIMIT ?
                """;
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            return executeReceiptList(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list user receipts", e);
        }
    }

    public int countReceiptsForUser(long userId) {
        String sql = "SELECT COUNT(*) FROM milk_receipts WHERE created_by_user_id = ? AND deleted = 0";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count user receipts", e);
        }
    }

    public List<MilkReceipt> listReceiptsForUserPage(long userId, int limit, int offset) {
        String sql = baseReceiptSelect() + """
                 WHERE mr.created_by_user_id = ? AND mr.deleted = 0
                 ORDER BY mr.created_at DESC
                 LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return executeReceiptList(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list user receipts page", e);
        }
    }

    public List<MilkReceipt> listReceipts(LocalDate start, LocalDate end, Long pointId, Long farmId, boolean includeDeleted) {
        StringBuilder sql = new StringBuilder(baseReceiptSelect())
                .append(" WHERE mr.delivery_date BETWEEN ? AND ? ");
        if (!includeDeleted) {
            sql.append("AND mr.deleted = 0 ");
        }
        if (pointId != null) {
            sql.append("AND mr.point_id = ? ");
        }
        if (farmId != null) {
            sql.append("AND mr.farm_id = ? ");
        }
        sql.append("ORDER BY mr.delivery_date DESC, mr.created_at DESC");
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, start.toString());
            ps.setString(idx++, end.toString());
            if (pointId != null) {
                ps.setLong(idx++, pointId);
            }
            if (farmId != null) {
                ps.setLong(idx++, farmId);
            }
            return executeReceiptList(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list receipts", e);
        }
    }

    public StatsSummary summarize(LocalDate start, LocalDate end, Long pointId, Long farmId) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS records_count,
                       COALESCE(SUM(weight_kg), 0) AS total_weight,
                       COALESCE(SUM(credit_weight_kg), 0) AS total_credit_weight,
                       COALESCE(SUM(weight_kg * fat_percent) / NULLIF(SUM(weight_kg), 0), 0) AS avg_fat,
                       COALESCE(SUM(weight_kg * protein_percent) / NULLIF(SUM(weight_kg), 0), 0) AS avg_protein
                  FROM milk_receipts
                 WHERE deleted = 0
                   AND delivery_date BETWEEN ? AND ?
                """);
        if (pointId != null) {
            sql.append(" AND point_id = ? ");
        }
        if (farmId != null) {
            sql.append(" AND farm_id = ? ");
        }
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, start.toString());
            ps.setString(idx++, end.toString());
            if (pointId != null) {
                ps.setLong(idx++, pointId);
            }
            if (farmId != null) {
                ps.setLong(idx++, farmId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return StatsSummary.empty();
                }
                return new StatsSummary(
                        rs.getLong("records_count"),
                        rs.getDouble("total_weight"),
                        rs.getDouble("total_credit_weight"),
                        rs.getDouble("avg_fat"),
                        rs.getDouble("avg_protein")
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to summarize receipts", e);
        }
    }

    public List<NamedSummary> summarizeByPoint(LocalDate start, LocalDate end) {
        String sql = """
                SELECT rp.id,
                       rp.name,
                       COUNT(mr.id) AS records_count,
                       COALESCE(SUM(mr.weight_kg), 0) AS total_weight,
                       COALESCE(SUM(mr.credit_weight_kg), 0) AS total_credit_weight,
                       COALESCE(SUM(mr.weight_kg * mr.fat_percent) / NULLIF(SUM(mr.weight_kg), 0), 0) AS avg_fat,
                       COALESCE(SUM(mr.weight_kg * mr.protein_percent) / NULLIF(SUM(mr.weight_kg), 0), 0) AS avg_protein
                  FROM receiving_points rp
             LEFT JOIN milk_receipts mr
                    ON mr.point_id = rp.id
                   AND mr.deleted = 0
                   AND mr.delivery_date BETWEEN ? AND ?
                 GROUP BY rp.id, rp.name
                 ORDER BY rp.id
                """;
        return summarizeNamed(sql, start, end, null);
    }

    public List<NamedSummary> summarizeByFarm(LocalDate start, LocalDate end, Long pointId) {
        String sql = """
                SELECT f.id,
                       f.name,
                       COUNT(mr.id) AS records_count,
                       COALESCE(SUM(mr.weight_kg), 0) AS total_weight,
                       COALESCE(SUM(mr.credit_weight_kg), 0) AS total_credit_weight,
                       COALESCE(SUM(mr.weight_kg * mr.fat_percent) / NULLIF(SUM(mr.weight_kg), 0), 0) AS avg_fat,
                       COALESCE(SUM(mr.weight_kg * mr.protein_percent) / NULLIF(SUM(mr.weight_kg), 0), 0) AS avg_protein
                  FROM farms f
             LEFT JOIN milk_receipts mr
                    ON mr.farm_id = f.id
                   AND mr.deleted = 0
                   AND mr.delivery_date BETWEEN ? AND ?
                   %s
                 GROUP BY f.id, f.name
                 ORDER BY f.name
                """.formatted(pointId != null ? "AND mr.point_id = ?" : "");
        return summarizeNamed(sql, start, end, pointId);
    }

    public void updateReceipt(long receiptId,
                              long changedByUserId,
                              long farmId,
                              String sectionLabel,
                              double weightKg,
                              double fatPercent,
                              double proteinPercent,
                              double creditWeightKg,
                              String photoToken,
                              String photoPayloadJson,
                              Integer photoWidth,
                              Integer photoHeight,
                              String photoStatus,
                              String note,
                              Instant adminOverrideUnlockedUntil) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            MilkReceipt before = findReceiptById(receiptId).orElseThrow();
            String sql = """
                    UPDATE milk_receipts
                       SET farm_id = ?,
                           section_label = ?,
                           weight_kg = ?,
                           fat_percent = ?,
                           protein_percent = ?,
                           credit_weight_kg = ?,
                           photo_token = ?,
                           photo_payload_json = ?,
                           photo_width = ?,
                           photo_height = ?,
                           photo_status = ?,
                           note = ?,
                           admin_override_unlocked_until = ?,
                           updated_at = ?
                     WHERE id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, farmId);
                ps.setString(2, sectionLabel);
                ps.setDouble(3, weightKg);
                ps.setDouble(4, fatPercent);
                ps.setDouble(5, proteinPercent);
                ps.setDouble(6, creditWeightKg);
                ps.setString(7, photoToken);
                ps.setString(8, photoPayloadJson);
                bindNullableInt(ps, 9, photoWidth);
                bindNullableInt(ps, 10, photoHeight);
                ps.setString(11, photoStatus);
                ps.setString(12, note);
                ps.setString(13, adminOverrideUnlockedUntil == null ? null : adminOverrideUnlockedUntil.toString());
                ps.setString(14, Instant.now().toString());
                ps.setLong(15, receiptId);
                ps.executeUpdate();
            }
            MilkReceipt after = findReceiptById(receiptId).orElseThrow();
            insertAudit(connection, receiptId, changedByUserId, "UPDATE", Jsons.MAPPER.valueToTree(before), Jsons.MAPPER.valueToTree(after));
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update receipt", e);
        }
    }

    public void unlockReceiptForOneHour(long receiptId, long changedByUserId) {
        Instant unlockUntil = Instant.now().plusSeconds(3600);
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            MilkReceipt before = findReceiptById(receiptId).orElseThrow();
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE milk_receipts
                       SET admin_override_unlocked_until = ?, updated_at = ?
                     WHERE id = ?
                    """)) {
                ps.setString(1, unlockUntil.toString());
                ps.setString(2, Instant.now().toString());
                ps.setLong(3, receiptId);
                ps.executeUpdate();
            }
            MilkReceipt after = findReceiptById(receiptId).orElseThrow();
            insertAudit(connection, receiptId, changedByUserId, "UNLOCK", Jsons.MAPPER.valueToTree(before), Jsons.MAPPER.valueToTree(after));
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to unlock receipt", e);
        }
    }

    public void softDeleteReceipt(long receiptId, long changedByUserId) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            MilkReceipt before = findReceiptById(receiptId).orElseThrow();
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE milk_receipts
                       SET deleted = 1, updated_at = ?
                     WHERE id = ?
                    """)) {
                ps.setString(1, Instant.now().toString());
                ps.setLong(2, receiptId);
                ps.executeUpdate();
            }
            MilkReceipt after = findReceiptById(receiptId).orElseThrow();
            insertAudit(connection, receiptId, changedByUserId, "DELETE", Jsons.MAPPER.valueToTree(before), Jsons.MAPPER.valueToTree(after));
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete receipt", e);
        }
    }

    public boolean isDailyDigestAlreadySent(LocalDate date, long recipientUserId) {
        String sql = "SELECT 1 FROM daily_digest_log WHERE digest_date = ? AND recipient_user_id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.setLong(2, recipientUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check digest log", e);
        }
    }

    public void markDailyDigestSent(LocalDate date, long recipientUserId) {
        String sql = "INSERT OR IGNORE INTO daily_digest_log (digest_date, recipient_user_id, sent_at) VALUES (?, ?, ?)";
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.setLong(2, recipientUserId);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark digest sent", e);
        }
    }

    private Optional<BotUser> findUserByMaxId(Connection connection, long maxUserId) throws SQLException {
        String sql = "SELECT * FROM users WHERE max_user_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, maxUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapUser(rs));
            }
        }
    }

    private List<BotUser> listUsersByRole(UserRole role) {
        return queryUsers("SELECT * FROM users WHERE role = '" + role.name() + "' AND active = 1 ORDER BY display_name");
    }

    private List<BotUser> queryUsers(String sql) {
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<BotUser> users = new ArrayList<>();
            while (rs.next()) {
                users.add(mapUser(rs));
            }
            return users;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query users", e);
        }
    }

    private List<BotUser> executeUserList(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<BotUser> users = new ArrayList<>();
            while (rs.next()) {
                users.add(mapUser(rs));
            }
            return users;
        }
    }

    private int countBySql(String sql) {
        try (Connection connection = database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count rows", e);
        }
    }

    private long getRegistrationUserId(Connection connection, long requestId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT user_id FROM registration_requests WHERE id = ?")) {
            ps.setLong(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Registration request not found: " + requestId);
                }
                return rs.getLong("user_id");
            }
        }
    }

    private Optional<Long> findPendingRegistrationIdByUser(Connection connection, long userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id
                  FROM registration_requests
                 WHERE user_id = ?
                   AND status = 'PENDING'
                 ORDER BY created_at DESC
                 LIMIT 1
                """)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty();
            }
        }
    }

    private void insertAudit(Connection connection, long receiptId, long changedByUserId, String action, JsonNode before, JsonNode after) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO receipt_audit (receipt_id, changed_by_user_id, action, before_json, after_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, receiptId);
            ps.setLong(2, changedByUserId);
            ps.setString(3, action);
            ps.setString(4, Jsons.write(before));
            ps.setString(5, Jsons.write(after));
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private List<NamedSummary> summarizeNamed(String sql, LocalDate start, LocalDate end, Long optionalPointId) {
        try (Connection connection = database.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, start.toString());
            ps.setString(2, end.toString());
            if (optionalPointId != null) {
                ps.setLong(3, optionalPointId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<NamedSummary> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new NamedSummary(
                            rs.getLong("id"),
                            rs.getString("name"),
                            new StatsSummary(
                                    rs.getLong("records_count"),
                                    rs.getDouble("total_weight"),
                                    rs.getDouble("total_credit_weight"),
                                    rs.getDouble("avg_fat"),
                                    rs.getDouble("avg_protein")
                            )
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to summarize named groups", e);
        }
    }

    private List<MilkReceipt> executeReceiptList(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<MilkReceipt> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapReceipt(rs));
            }
            return result;
        }
    }

    private String baseReceiptSelect() {
        return """
                SELECT mr.*,
                       u.display_name AS created_by_name,
                       rp.name AS point_name,
                       f.name AS farm_name
                  FROM milk_receipts mr
                  JOIN users u ON u.id = mr.created_by_user_id
                  JOIN receiving_points rp ON rp.id = mr.point_id
                  JOIN farms f ON f.id = mr.farm_id
                """;
    }

    private BotUser mapUser(ResultSet rs) throws SQLException {
        return new BotUser(
                rs.getLong("id"),
                rs.getLong("max_user_id"),
                nullableLong(rs, "chat_id"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("display_name"),
                rs.getString("phone"),
                UserRole.valueOf(rs.getString("role")),
                nullableLong(rs, "receiving_point_id"),
                rs.getInt("active") == 1,
                rs.getInt("daily_digest_enabled") == 1
        );
    }

    private MilkReceipt mapReceipt(ResultSet rs) throws SQLException {
        return new MilkReceipt(
                rs.getLong("id"),
                rs.getString("public_id"),
                rs.getLong("created_by_user_id"),
                rs.getString("created_by_name"),
                rs.getLong("point_id"),
                rs.getString("point_name"),
                rs.getLong("farm_id"),
                rs.getString("farm_name"),
                rs.getString("section_label"),
                LocalDate.parse(rs.getString("delivery_date")),
                rs.getDouble("weight_kg"),
                rs.getDouble("fat_percent"),
                rs.getDouble("protein_percent"),
                rs.getDouble("credit_weight_kg"),
                rs.getString("photo_token"),
                rs.getString("photo_payload_json"),
                nullableInt(rs, "photo_width"),
                nullableInt(rs, "photo_height"),
                rs.getString("photo_status"),
                rs.getString("original_message_id"),
                rs.getString("note"),
                Instant.parse(rs.getString("editable_until")),
                rs.getString("admin_override_unlocked_until") == null ? null : Instant.parse(rs.getString("admin_override_unlocked_until")),
                rs.getInt("deleted") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private static String buildDisplayName(String firstName, String lastName, String username, long maxUserId) {
        String fullName = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return "Пользователь " + maxUserId;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void bindNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void bindNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setInt(index, value);
        }
    }
}
