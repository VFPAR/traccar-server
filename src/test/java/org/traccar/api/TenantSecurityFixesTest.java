package org.traccar.api;

import com.google.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.resource.PermissionsResource;
import org.traccar.api.resource.TenantResource;
import org.traccar.api.resource.UserResource;
import org.traccar.api.security.LoginService;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.helper.LogAction;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Server;
import org.traccar.model.Tenant;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.TenantAccessService;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TenantSecurityFixesTest {

    private Storage storage;
    private PermissionsService permissionsService;

    private static void inject(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static User user(long id, long tenantId, boolean administrator) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setAdministrator(administrator);
        return user;
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() throws Exception {
        Field classes = org.traccar.model.Permission.class.getDeclaredField("CLASSES");
        classes.setAccessible(true);
        var map = (java.util.Map<String, Class<? extends BaseModel>>) classes.get(null);
        map.put("User", User.class);
        map.put("Device", Device.class);
        storage = mock(Storage.class);
        permissionsService = mock(PermissionsService.class);
    }

    private <T extends BaseModel> BaseObjectResource<T> resource(Class<T> clazz, long userId, User logged)
            throws Exception {
        BaseObjectResource<T> resource = new BaseObjectResource<>(clazz) {
            @Override
            protected long getUserId() {
                return userId;
            }
        };
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        inject(BaseObjectResource.class, resource, "cacheManager", mock(CacheManager.class));
        inject(BaseObjectResource.class, resource, "connectionManager", mock(ConnectionManager.class));
        inject(BaseObjectResource.class, resource, "actionLogger", mock(LogAction.class));
        inject(BaseObjectResource.class, resource, "tenantAccessService", mock(TenantAccessService.class));
        when(permissionsService.getUser(userId)).thenReturn(logged);
        when(permissionsService.notAdmin(userId)).thenReturn(!logged.getAdministrator());
        return resource;
    }

    private static Group group(long id, long tenantId) {
        Group group = new Group();
        group.setId(id);
        group.setTenantId(tenantId);
        return group;
    }

    @Test
    public void loggedNonManagerRegistrationCreatesLegacyUser() throws Exception {
        UserResource resource = new UserResource() {
            @Override
            protected long getUserId() {
                return 2;
            }
        };
        Server server = new Server();
        server.setRegistration(true);
        PermissionsService service = new PermissionsService(storage);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(server);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user(2, 5, false));
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(List.of(new User()));
        when(storage.addObject(any(), any(Request.class))).thenReturn(9L);
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", service);
        inject(BaseObjectResource.class, resource, "tenantAccessService", mock(TenantAccessService.class));
        inject(UserResource.class, resource, "config", mock(Config.class));
        inject(UserResource.class, resource, "actionLogger", mock(LogAction.class));

        User entity = new User();
        entity.setTenantId(5);
        resource.add(entity);
        assertEquals(0, entity.getTenantId());
    }

    @Test
    public void deviceAddWithGroupOfOtherTenantIsRejected() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 6));
        Device device = new Device();
        device.setTenantId(5);
        device.setGroupId(10);
        var thrown = assertThrows(IllegalArgumentException.class, () -> resource.add(device));
        assertEquals("Group belongs to a different tenant", thrown.getMessage());
        verify(storage, never()).addObject(any(), any(Request.class));
    }

    @Test
    public void deviceAddWithGroupOfSameTenantIsAccepted() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 5));
        Device device = new Device();
        device.setTenantId(5);
        device.setGroupId(10);
        assertDoesNotThrow(() -> resource.add(device));
    }

    @Test
    public void legacyDeviceWithTenantGroupIsRejected() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 6));
        Device device = new Device();
        device.setGroupId(10);
        assertThrows(IllegalArgumentException.class, () -> resource.add(device));
    }

    @Test
    public void groupUpdateWithParentOfOtherTenantIsRejected() throws Exception {
        var resource = resource(Group.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 6));
        Group child = group(11, 5);
        child.setGroupId(10);
        assertThrows(IllegalArgumentException.class, () -> resource.update(child));
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void deviceTenantChangeWithCurrentGroupInOldTenantIsRejected() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        Device current = new Device();
        current.setId(3);
        current.setTenantId(5);
        current.setGroupId(10);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(current);
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 5));
        Device update = new Device();
        update.setId(3);
        update.setTenantId(6);
        update.setGroupId(10);
        var thrown = assertThrows(IllegalArgumentException.class, () -> resource.update(update));
        assertEquals("Group belongs to a different tenant", thrown.getMessage());
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    private Device currentDevice(long tenantId) throws Exception {
        Device current = new Device();
        current.setId(3);
        current.setTenantId(tenantId);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(current);
        return current;
    }

    @Test
    public void adminUpdateWithoutTenantIdKeepsTenant() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        currentDevice(5);
        Device update = new Device();
        update.setId(3);
        resource.update(update);
        assertEquals(5, update.getTenantId());
    }

    @Test
    public void adminUpdateWithTenantIdChangesTenant() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        currentDevice(5);
        Device update = new Device();
        update.setId(3);
        update.setTenantId(6);
        resource.update(update);
        assertEquals(6, update.getTenantId());
    }

    @Test
    public void adminUpdateWithExplicitZeroTenantClearsTenant() throws Exception {
        var resource = resource(Device.class, 1, user(1, 0, true));
        currentDevice(5);
        Device update = new Device();
        update.setId(3);
        update.setTenantId(0);
        resource.update(update);
        assertEquals(0, update.getTenantId());
    }

    @Test
    public void tenantIdSpecifiedIsNotSerialized() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertFalse(mapper.writeValueAsString(new Device()).contains("tenantIdSpecified"));
        Device parsed = mapper.readValue("{\"name\":\"a\"}", Device.class);
        assertFalse(parsed.tenantIdSpecified());
        parsed = mapper.readValue("{\"tenantId\":0}", Device.class);
        assertTrue(parsed.tenantIdSpecified());
    }

    private LoginService loginService(User stored, Tenant tenant) throws Exception {
        TokenManager tokenManager = mock(TokenManager.class);
        TokenManager.TokenData data = mock(TokenManager.TokenData.class);
        when(data.getUserId()).thenReturn(stored.getId());
        when(tokenManager.verifyToken("t")).thenReturn(data);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(stored);
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(tenant);
        @SuppressWarnings("unchecked")
        Provider<PermissionsService> provider = mock(Provider.class);
        return new LoginService(mock(Config.class), storage, tokenManager, null, provider);
    }

    @Test
    public void userOfDisabledTenantCannotLogin() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setDisabled(true);
        LoginService service = loginService(user(2, 5, false), tenant);
        var thrown = assertThrows(SecurityException.class, () -> service.login("t"));
        assertEquals("User is disabled", thrown.getMessage());
    }

    @Test
    public void userOfEnabledTenantCanLogin() throws Exception {
        LoginService service = loginService(user(2, 5, false), new Tenant());
        assertEquals(2, service.login("t").getUser().getId());
    }

    @Test
    public void userIdCheckRejectsDisabledTenant() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setDisabled(true);
        LoginService service = loginService(user(2, 5, false), tenant);
        assertThrows(SecurityException.class, () -> service.checkUserEnabled(2));
        assertDoesNotThrow(() -> loginService(user(2, 5, false), new Tenant()).checkUserEnabled(2));
    }

    @Test
    public void administratorIsNeverCheckedAgainstTenant() throws Exception {
        LoginService service = loginService(user(1, 0, true), null);
        assertEquals(1, service.login("t").getUser().getId());
        verify(storage, never()).getObject(eq(Tenant.class), any(Request.class));
    }

    @Test
    public void openIdDoesNotPromoteTenantUser() throws Exception {
        User stored = user(2, 5, false);
        stored.setEmail("a@b.c");
        stored.setName("n");
        LoginService service = loginService(stored, new Tenant());
        service.login("a@b.c", "n", true);
        assertFalse(stored.getAdministrator());
    }

    @Test
    public void openIdPromotesLegacyUser() throws Exception {
        User stored = user(2, 0, false);
        stored.setEmail("a@b.c");
        stored.setName("n");
        LoginService service = loginService(stored, null);
        service.login("a@b.c", "n", true);
        assertTrue(stored.getAdministrator());
    }

    @Test
    public void tenantUserSeesOnlyIdAndNameOfTenant() throws Exception {
        TenantResource resource = new TenantResource() {
            @Override
            protected long getUserId() {
                return 2;
            }
        };
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        when(permissionsService.getUser(2)).thenReturn(user(2, 5, false));
        Tenant tenant = new Tenant();
        tenant.setId(5);
        tenant.setName("acme");
        tenant.set("secret", "x");
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(tenant);
        Tenant result = (Tenant) resource.getSingle(5).getEntity();
        assertEquals(5, result.getId());
        assertEquals("acme", result.getName());
        assertNull(result.getAttributes().get("secret"));
    }

    @Test
    public void administratorSeesFullTenant() throws Exception {
        TenantResource resource = new TenantResource() {
            @Override
            protected long getUserId() {
                return 1;
            }
        };
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        when(permissionsService.getUser(1)).thenReturn(user(1, 0, true));
        Tenant tenant = new Tenant();
        tenant.set("secret", "x");
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(tenant);
        assertEquals(tenant, resource.getSingle(5).getEntity());
    }

    private PermissionsResource permissionsResource() throws Exception {
        PermissionsResource resource = new PermissionsResource() {
            @Override
            protected long getUserId() {
                return 1;
            }
        };
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        inject(PermissionsResource.class, resource, "cacheManager", mock(CacheManager.class));
        inject(PermissionsResource.class, resource, "actionLogger", mock(LogAction.class));
        when(permissionsService.notAdmin(1)).thenReturn(false);
        return resource;
    }

    @Test
    public void permissionWithMissingObjectIsRejected() throws Exception {
        var resource = permissionsResource();
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user(2, 5, false));
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(null);
        LinkedHashMap<String, Long> link = new LinkedHashMap<>();
        link.put("userId", 2L);
        link.put("deviceId", 3L);
        assertThrows(IllegalArgumentException.class, () -> resource.add(link));
        verify(storage, never()).addPermission(any());
    }

    @Test
    public void deviceLimitCountsOnlyLinkedDevices() throws Exception {
        User limited = user(2, 5, false);
        limited.setDeviceLimit(1);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(limited);
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());
        new PermissionsService(storage).checkEdit(2, Device.class, true, false);
        var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(storage).getObjects(eq(Device.class), captor.capture());
        var condition = (Condition.Permission) captor.getValue().getCondition();
        assertFalse(condition.getTenantDerived());
    }

    private void groupTenantChange(List<Device> devices, List<Group> groups) throws Exception {
        var resource = resource(Group.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 5));
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(devices);
        when(storage.getObjects(eq(Group.class), any(Request.class))).thenReturn(groups);
        Group update = group(10, 6);
        var thrown = assertThrows(IllegalArgumentException.class, () -> resource.update(update));
        assertEquals("Group has children or devices from a different tenant", thrown.getMessage());
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void groupTenantChangeWithDeviceOfOtherTenantIsRejected() throws Exception {
        Device device = new Device();
        device.setTenantId(5);
        groupTenantChange(List.of(device), List.of());
    }

    @Test
    public void groupTenantChangeWithChildOfOtherTenantIsRejected() throws Exception {
        groupTenantChange(List.of(), List.of(group(11, 5)));
    }

    @Test
    public void groupTenantChangeWithMatchingChildrenIsAccepted() throws Exception {
        var resource = resource(Group.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 5));
        Device device = new Device();
        device.setTenantId(6);
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of(device));
        when(storage.getObjects(eq(Group.class), any(Request.class))).thenReturn(List.of(group(11, 6)));
        assertDoesNotThrow(() -> resource.update(group(10, 6)));
    }

    @Test
    public void groupUpdateWithoutTenantChangeDoesNotScanChildren() throws Exception {
        var resource = resource(Group.class, 1, user(1, 0, true));
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(group(10, 5));
        resource.update(group(10, 5));
        verify(storage, never()).getObjects(eq(Device.class), any(Request.class));
    }

    @Test
    public void permissionConditionCanExcludeTenantDerivation() {
        var base = new Condition.Permission(User.class, Device.class, 3);
        assertTrue(base.getTenantDerived());
        assertFalse(base.linkedOnly().getTenantDerived());
        assertFalse(base.excludeGroups().linkedOnly().getIncludeGroups());
        assertTrue(base.linkedOnly().getIncludeGroups());
        assertFalse(base.linkedOnly().excludeGroups().getTenantDerived());
    }

}
