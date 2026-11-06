package org.traccar.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.ManagedUser;
import org.traccar.model.User;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DatabaseStorageTenantTest {

    private DataSource dataSource;
    private DatabaseStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:databasestoragetenanttest;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        execute(
                "DROP TABLE IF EXISTS tc_user_user",
                "DROP TABLE IF EXISTS tc_user_device",
                "DROP TABLE IF EXISTS tc_user_group",
                "DROP TABLE IF EXISTS tc_group_device",
                "DROP TABLE IF EXISTS tc_devices",
                "DROP TABLE IF EXISTS tc_groups",
                "DROP TABLE IF EXISTS tc_users",
                "CREATE TABLE tc_users (id BIGINT PRIMARY KEY, tenantid BIGINT)",
                "CREATE TABLE tc_groups (id BIGINT PRIMARY KEY, groupid BIGINT, tenantid BIGINT)",
                "CREATE TABLE tc_devices (id BIGINT PRIMARY KEY, groupid BIGINT, tenantid BIGINT)",
                "CREATE TABLE tc_user_device (userid BIGINT, deviceid BIGINT)",
                "CREATE TABLE tc_user_group (userid BIGINT, groupid BIGINT)",
                "CREATE TABLE tc_group_device (groupid BIGINT, deviceid BIGINT)",
                "CREATE TABLE tc_user_user (userid BIGINT, manageduserid BIGINT)");
        storage = new DatabaseStorage(new Config(), dataSource, new ObjectMapper());
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private String tenant(long tenantId) {
        return tenantId > 0 ? String.valueOf(tenantId) : "NULL";
    }

    private void user(long id, long tenantId) throws Exception {
        execute("INSERT INTO tc_users VALUES (" + id + ", " + tenant(tenantId) + ")");
    }

    private void device(long id, long groupId, long tenantId) throws Exception {
        execute("INSERT INTO tc_devices VALUES (" + id + ", " + (groupId > 0 ? groupId : "NULL")
                + ", " + tenant(tenantId) + ")");
    }

    private void group(long id, long tenantId) throws Exception {
        execute("INSERT INTO tc_groups VALUES (" + id + ", NULL, " + tenant(tenantId) + ")");
    }

    private Set<Long> devices(Condition condition) throws Exception {
        List<Device> result = storage.getObjects(Device.class, new Request(new Columns.Include("id"), condition));
        return result.stream().map(Device::getId).collect(Collectors.toSet());
    }

    private Set<Long> groups(Condition condition) throws Exception {
        List<Group> result = storage.getObjects(Group.class, new Request(new Columns.Include("id"), condition));
        return result.stream().map(Group::getId).collect(Collectors.toSet());
    }

    private Set<Long> users(Condition condition) throws Exception {
        List<User> result = storage.getObjects(User.class, new Request(new Columns.Include("id"), condition));
        return result.stream().map(User::getId).collect(Collectors.toSet());
    }

    private Set<Long> managedUsers(Condition condition) throws Exception {
        List<ManagedUser> result = storage.getObjects(
                ManagedUser.class, new Request(new Columns.Include("id"), condition));
        return result.stream().map(ManagedUser::getId).collect(Collectors.toSet());
    }

    @Test
    public void tenantUserSeesTenantDevicesWithoutLink() throws Exception {
        user(1, 1);
        device(10, 0, 1);
        device(11, 0, 2);
        device(12, 0, 0);
        assertEquals(Set.of(10L), devices(new Condition.Permission(User.class, 1, Device.class)));
    }

    @Test
    public void tenantUserIsolatedFromLinkedForeignDevices() throws Exception {
        user(1, 1);
        device(10, 0, 1);
        device(11, 0, 2);
        device(12, 0, 0);
        execute("INSERT INTO tc_user_device VALUES (1, 11)", "INSERT INTO tc_user_device VALUES (1, 12)");
        assertEquals(Set.of(10L), devices(new Condition.Permission(User.class, 1, Device.class)));
    }

    @Test
    public void tenantUserIsolatedFromForeignDeviceInLinkedGroup() throws Exception {
        user(1, 1);
        group(5, 1);
        device(10, 5, 1);
        device(11, 5, 2);
        execute("INSERT INTO tc_user_group VALUES (1, 5)");
        assertEquals(Set.of(10L), devices(new Condition.Permission(User.class, 1, Device.class)));
    }

    @Test
    public void tenantUserSeesTenantGroupsWithoutLink() throws Exception {
        user(1, 1);
        group(5, 1);
        group(6, 2);
        group(7, 0);
        assertEquals(Set.of(5L), groups(new Condition.Permission(User.class, 1, Group.class)));
    }

    @Test
    public void legacyUserKeepsCurrentBehavior() throws Exception {
        user(1, 0);
        device(10, 0, 1);
        device(11, 0, 2);
        device(12, 0, 0);
        execute("INSERT INTO tc_user_device VALUES (1, 11)", "INSERT INTO tc_user_device VALUES (1, 12)");
        assertEquals(Set.of(11L, 12L), devices(new Condition.Permission(User.class, 1, Device.class)));
    }

    @Test
    public void excludeGroupsShowsOnlyDirectLinksWithoutDerived() throws Exception {
        user(1, 1);
        device(10, 0, 1);
        device(11, 0, 1);
        device(12, 0, 2);
        execute("INSERT INTO tc_user_device VALUES (1, 10)", "INSERT INTO tc_user_device VALUES (1, 12)");
        assertEquals(Set.of(10L), devices(new Condition.Permission(User.class, 1, Device.class).excludeGroups()));
    }

    @Test
    public void deviceUsersIncludeSameTenantAndExcludeOthers() throws Exception {
        device(10, 0, 1);
        user(1, 1);
        user(2, 1);
        user(3, 2);
        user(4, 0);
        execute("INSERT INTO tc_user_device VALUES (2, 10)",
                "INSERT INTO tc_user_device VALUES (3, 10)",
                "INSERT INTO tc_user_device VALUES (4, 10)");
        assertEquals(Set.of(1L, 2L, 4L), users(new Condition.Permission(User.class, Device.class, 10)));
        assertEquals(Set.of(2L, 4L),
                users(new Condition.Permission(User.class, Device.class, 10).excludeGroups()));
    }

    @Test
    public void untenantedDeviceUsersOnlyLegacyUsers() throws Exception {
        device(10, 0, 0);
        user(1, 0);
        user(2, 0);
        user(3, 1);
        execute("INSERT INTO tc_user_device VALUES (1, 10)", "INSERT INTO tc_user_device VALUES (3, 10)");
        Set<Long> result = users(new Condition.Permission(User.class, Device.class, 10));
        assertEquals(Set.of(1L), result);
        assertFalse(result.contains(2L));
    }

    @Test
    public void tenantUserManagesOnlySameTenantUsers() throws Exception {
        user(1, 1);
        user(2, 1);
        user(3, 2);
        user(4, 0);
        execute("INSERT INTO tc_user_user VALUES (1, 2)",
                "INSERT INTO tc_user_user VALUES (1, 3)",
                "INSERT INTO tc_user_user VALUES (1, 4)");
        assertEquals(Set.of(2L), managedUsers(new Condition.Permission(User.class, 1, ManagedUser.class)));
    }

    @Test
    public void legacyUserManagesAllLinkedUsers() throws Exception {
        user(1, 0);
        user(2, 1);
        user(3, 0);
        user(4, 0);
        execute("INSERT INTO tc_user_user VALUES (1, 2)", "INSERT INTO tc_user_user VALUES (1, 3)");
        assertEquals(Set.of(2L, 3L), managedUsers(new Condition.Permission(User.class, 1, ManagedUser.class)));
    }

    @Test
    public void userWithoutTenantRowKeepsLegacyBehavior() throws Exception {
        device(10, 0, 1);
        execute("INSERT INTO tc_user_device VALUES (99, 10)");
        assertEquals(Set.of(10L), devices(new Condition.Permission(User.class, 99, Device.class)));
    }

}
