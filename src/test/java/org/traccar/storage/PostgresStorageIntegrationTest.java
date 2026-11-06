package org.traccar.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.ManagedUser;
import org.traccar.model.Tenant;
import org.traccar.model.User;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real PostgreSQL. Disabled unless TRACCAR_TEST_PG_URL is set.
 * Variables: TRACCAR_TEST_PG_URL (jdbc:postgresql://host:5432/db), TRACCAR_TEST_PG_USER,
 * TRACCAR_TEST_PG_PASSWORD. WARNING: drops and recreates schema "public" of the target database.
 */
public class PostgresStorageIntegrationTest {

    private static final String URL = System.getenv("TRACCAR_TEST_PG_URL");
    private static final String USER = System.getenv("TRACCAR_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("TRACCAR_TEST_PG_PASSWORD");

    private static DataSource dataSource;
    private static DatabaseStorage storage;

    @BeforeAll
    public static void migrate() throws Exception {
        assumeTrue(URL != null && !URL.isEmpty(), "TRACCAR_TEST_PG_URL not set");

        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(URL);
        pg.setUser(USER);
        pg.setPassword(PASSWORD);
        dataSource = pg;
        execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");

        System.setProperty("liquibase.analytics.enabled", "false");
        Path schema = Path.of("schema").toAbsolutePath().normalize();
        var accessor = new DirectoryResourceAccessor(schema);
        Database database = DatabaseFactory.getInstance().openDatabase(
                URL, USER, PASSWORD, "org.postgresql.Driver", null, null, null, accessor);
        try (Liquibase liquibase = new Liquibase("changelog-master.xml", accessor, database)) {
            liquibase.update(new Contexts());
        }

        storage = new DatabaseStorage(new Config(), dataSource, new ObjectMapper());
    }

    @AfterAll
    public static void cleanup() throws Exception {
        if (dataSource != null) {
            execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
        }
    }

    @BeforeEach
    public void clean() throws Exception {
        assumeTrue(dataSource != null);
        execute("DELETE FROM tc_user_user", "DELETE FROM tc_user_device", "DELETE FROM tc_user_group",
                "DELETE FROM tc_devices", "DELETE FROM tc_groups",
                "DELETE FROM tc_users", "DELETE FROM tc_tenants");
    }

    private static void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private Map<String, String> columns(String table) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT column_name, data_type FROM information_schema.columns "
                                + "WHERE table_schema = 'public' AND table_name = '" + table + "'")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    @Test
    public void schemaHasExpectedColumns() throws Exception {
        Map<String, String> tenants = columns("tc_tenants");
        assertEquals("double precision", tenants.get("baselatitude"));
        assertEquals("double precision", tenants.get("baselongitude"));
        assertEquals("integer", tenants.get("baseradius"));
        assertEquals("character varying", tenants.get("legalname"));
        assertEquals("boolean", tenants.get("disabled"));

        Map<String, String> devices = columns("tc_devices");
        for (String column : List.of("vehiclemodel", "plate", "chassis", "color", "manufacturer", "vehicletype")) {
            assertEquals("character varying", devices.get(column), column);
        }
        assertEquals("integer", devices.get("manufactureyear"));
        assertEquals("integer", devices.get("modelyear"));
        assertEquals("timestamp without time zone", devices.get("installationdate"));

        List<String> tables = List.of("tc_users", "tc_devices", "tc_groups", "tc_geofences", "tc_drivers",
                "tc_calendars", "tc_notifications", "tc_commands", "tc_attributes", "tc_maintenances",
                "tc_orders", "tc_reports");
        for (String table : tables) {
            assertEquals("bigint", columns(table).get("tenantid"), table);
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "SELECT count(*) FROM information_schema.table_constraints tc "
                                    + "JOIN information_schema.key_column_usage k ON tc.constraint_name = k.constraint_name "
                                    + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '" + table
                                    + "' AND k.column_name = 'tenantid'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "FK " + table);
            }
        }
    }

    private long addTenant(Tenant tenant) throws Exception {
        return storage.addObject(tenant, new Request(new Columns.Exclude("id")));
    }

    private Tenant getTenant(long id) throws Exception {
        return storage.getObject(Tenant.class, new Request(new Columns.All(), new Condition.Equals("id", id)));
    }

    @Test
    public void tenantRoundTripWithAndWithoutBase() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setName("T1");
        tenant.setBaseLatitude(-11.8583582);
        tenant.setBaseLongitude(-55.4993192);
        tenant.setBaseRadius(500);
        long id = addTenant(tenant);
        Tenant loaded = getTenant(id);
        assertEquals(-11.8583582, loaded.getBaseLatitude());
        assertEquals(-55.4993192, loaded.getBaseLongitude());
        assertEquals(500, loaded.getBaseRadius());

        loaded.setBaseLatitude(null);
        loaded.setBaseLongitude(null);
        loaded.setBaseRadius(null);
        storage.updateObject(loaded, new Request(new Columns.Exclude("id"), new Condition.Equals("id", id)));
        loaded = getTenant(id);
        assertNull(loaded.getBaseLatitude());
        assertNull(loaded.getBaseLongitude());
        assertNull(loaded.getBaseRadius());

        Tenant empty = new Tenant();
        empty.setName("T2");
        long emptyId = addTenant(empty);
        Tenant emptyLoaded = getTenant(emptyId);
        assertNull(emptyLoaded.getBaseLatitude());
        assertNull(emptyLoaded.getBaseRadius());

        emptyLoaded.setBaseLatitude(1.5);
        emptyLoaded.setBaseRadius(7);
        storage.updateObject(emptyLoaded,
                new Request(new Columns.Exclude("id"), new Condition.Equals("id", emptyId)));
        assertEquals(1.5, getTenant(emptyId).getBaseLatitude());
        assertEquals(7, getTenant(emptyId).getBaseRadius());
    }

    @Test
    public void deviceRoundTripWithAndWithoutVehicleFields() throws Exception {
        Date date = new Date(1700000000000L);
        Device device = new Device();
        device.setName("D1");
        device.setUniqueId("pg-d1");
        device.setManufactureYear(2020);
        device.setModelYear(2021);
        device.setInstallationDate(date);
        device.setPlate("ABC1D23");
        long id = storage.addObject(device, new Request(new Columns.Exclude("id")));
        Device loaded = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("id", id)));
        assertEquals(2020, loaded.getManufactureYear());
        assertEquals(2021, loaded.getModelYear());
        assertEquals(date, loaded.getInstallationDate());

        loaded.setManufactureYear(null);
        loaded.setModelYear(null);
        loaded.setInstallationDate(null);
        storage.updateObject(loaded, new Request(new Columns.Exclude("id"), new Condition.Equals("id", id)));
        loaded = storage.getObject(Device.class, new Request(new Columns.All(), new Condition.Equals("id", id)));
        assertNull(loaded.getManufactureYear());
        assertNull(loaded.getModelYear());
        assertNull(loaded.getInstallationDate());

        Device empty = new Device();
        empty.setName("D2");
        empty.setUniqueId("pg-d2");
        long emptyId = storage.addObject(empty, new Request(new Columns.Exclude("id")));
        Device emptyLoaded = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("id", emptyId)));
        assertNotNull(emptyLoaded);
        assertNull(emptyLoaded.getManufactureYear());
        assertNull(emptyLoaded.getModelYear());
        assertNull(emptyLoaded.getInstallationDate());
    }

    private long tenant(String name) throws Exception {
        Tenant tenant = new Tenant();
        tenant.setName(name);
        return addTenant(tenant);
    }

    private long user(String name, long tenantId) throws Exception {
        User user = new User();
        user.setName(name);
        user.setEmail(name + "@example.com");
        if (tenantId > 0) {
            user.setTenantId(tenantId);
        }
        return storage.addObject(user, new Request(new Columns.Exclude("id")));
    }

    private long device(String name, long groupId, long tenantId) throws Exception {
        Device device = new Device();
        device.setName(name);
        device.setUniqueId("pg-" + name);
        device.setGroupId(groupId);
        if (tenantId > 0) {
            device.setTenantId(tenantId);
        }
        return storage.addObject(device, new Request(new Columns.Exclude("id")));
    }

    private long group(String name, long tenantId) throws Exception {
        Group group = new Group();
        group.setName(name);
        if (tenantId > 0) {
            group.setTenantId(tenantId);
        }
        return storage.addObject(group, new Request(new Columns.Exclude("id")));
    }

    private Set<Long> devices(Condition condition) throws Exception {
        return storage.getObjects(Device.class, new Request(new Columns.Include("id"), condition))
                .stream().map(Device::getId).collect(Collectors.toSet());
    }

    private Set<Long> groups(Condition condition) throws Exception {
        return storage.getObjects(Group.class, new Request(new Columns.Include("id"), condition))
                .stream().map(Group::getId).collect(Collectors.toSet());
    }

    private Set<Long> users(Condition condition) throws Exception {
        return storage.getObjects(User.class, new Request(new Columns.Include("id"), condition))
                .stream().map(User::getId).collect(Collectors.toSet());
    }

    private Set<Long> managed(Condition condition) throws Exception {
        return storage.getObjects(ManagedUser.class, new Request(new Columns.Include("id"), condition))
                .stream().map(ManagedUser::getId).collect(Collectors.toSet());
    }

    @Test
    public void tenantUserSeesTenantDevicesWithoutLink() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u = user("u1", t1);
        long d10 = device("d10", 0, t1);
        device("d11", 0, t2);
        device("d12", 0, 0);
        assertEquals(Set.of(d10), devices(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void tenantUserIsolatedFromLinkedForeignDevices() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u = user("u1", t1);
        long d10 = device("d10", 0, t1);
        long d11 = device("d11", 0, t2);
        long d12 = device("d12", 0, 0);
        execute("INSERT INTO tc_user_device VALUES (" + u + ", " + d11 + ")",
                "INSERT INTO tc_user_device VALUES (" + u + ", " + d12 + ")");
        assertEquals(Set.of(d10), devices(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void tenantUserIsolatedFromForeignDeviceInLinkedGroup() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u = user("u1", t1);
        long g = group("g", t1);
        long d10 = device("d10", g, t1);
        device("d11", g, t2);
        execute("INSERT INTO tc_user_group VALUES (" + u + ", " + g + ")");
        assertEquals(Set.of(d10), devices(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void tenantUserSeesTenantGroupsWithoutLink() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u = user("u1", t1);
        long g5 = group("g5", t1);
        group("g6", t2);
        group("g7", 0);
        assertEquals(Set.of(g5), groups(new Condition.Permission(User.class, u, Group.class)));
    }

    @Test
    public void legacyUserKeepsCurrentBehavior() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u = user("u1", 0);
        device("d10", 0, t1);
        long d11 = device("d11", 0, t2);
        long d12 = device("d12", 0, 0);
        execute("INSERT INTO tc_user_device VALUES (" + u + ", " + d11 + ")",
                "INSERT INTO tc_user_device VALUES (" + u + ", " + d12 + ")");
        assertEquals(Set.of(d11, d12), devices(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void excludeGroupsShowsOnlyDirectLinks() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u = user("u1", t1);
        long d10 = device("d10", 0, t1);
        device("d11", 0, t1);
        long d12 = device("d12", 0, t2);
        execute("INSERT INTO tc_user_device VALUES (" + u + ", " + d10 + ")",
                "INSERT INTO tc_user_device VALUES (" + u + ", " + d12 + ")");
        assertEquals(Set.of(d10),
                devices(new Condition.Permission(User.class, u, Device.class).excludeGroups()));
    }

    @Test
    public void deviceUsersIncludeSameTenantAndExcludeOthers() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long d = device("d10", 0, t1);
        long u1 = user("u1", t1);
        long u2 = user("u2", t1);
        long u3 = user("u3", t2);
        long u4 = user("u4", 0);
        execute("INSERT INTO tc_user_device VALUES (" + u2 + ", " + d + ")",
                "INSERT INTO tc_user_device VALUES (" + u3 + ", " + d + ")",
                "INSERT INTO tc_user_device VALUES (" + u4 + ", " + d + ")");
        assertEquals(Set.of(u1, u2, u4), users(new Condition.Permission(User.class, Device.class, d)));
        assertEquals(Set.of(u2, u4),
                users(new Condition.Permission(User.class, Device.class, d).excludeGroups()));
    }

    @Test
    public void untenantedDeviceUsersOnlyLegacyUsers() throws Exception {
        long t1 = tenant("a");
        long d = device("d10", 0, 0);
        long u1 = user("u1", 0);
        user("u2", 0);
        long u3 = user("u3", t1);
        execute("INSERT INTO tc_user_device VALUES (" + u1 + ", " + d + ")",
                "INSERT INTO tc_user_device VALUES (" + u3 + ", " + d + ")");
        assertEquals(Set.of(u1), users(new Condition.Permission(User.class, Device.class, d)));
    }

    @Test
    public void tenantUserManagesOnlySameTenantUsers() throws Exception {
        long t1 = tenant("a");
        long t2 = tenant("b");
        long u1 = user("u1", t1);
        long u2 = user("u2", t1);
        long u3 = user("u3", t2);
        long u4 = user("u4", 0);
        execute("INSERT INTO tc_user_user VALUES (" + u1 + ", " + u2 + ")",
                "INSERT INTO tc_user_user VALUES (" + u1 + ", " + u3 + ")",
                "INSERT INTO tc_user_user VALUES (" + u1 + ", " + u4 + ")");
        assertEquals(Set.of(u2), managed(new Condition.Permission(User.class, u1, ManagedUser.class)));
    }

    @Test
    public void legacyUserManagesAllLinkedUsers() throws Exception {
        long t1 = tenant("a");
        long u1 = user("u1", 0);
        long u2 = user("u2", t1);
        long u3 = user("u3", 0);
        user("u4", 0);
        execute("INSERT INTO tc_user_user VALUES (" + u1 + ", " + u2 + ")",
                "INSERT INTO tc_user_user VALUES (" + u1 + ", " + u3 + ")");
        assertEquals(Set.of(u2, u3), managed(new Condition.Permission(User.class, u1, ManagedUser.class)));
    }

}
