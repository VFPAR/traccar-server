package org.traccar.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.resource.PermissionsResource;
import org.traccar.api.resource.ShareResource;
import org.traccar.api.resource.UserResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.signature.TokenManager;
import org.traccar.config.Config;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.TenantAccessService;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TenantWriteRulesTest {

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
        var map = (java.util.Map<String, Class<? extends org.traccar.model.BaseModel>>) classes.get(null);
        map.put("User", User.class);
        map.put("Device", Device.class);
        storage = mock(Storage.class);
        permissionsService = mock(PermissionsService.class);
    }

    private BaseObjectResource<Group> groupResource(long userId, User logged) throws Exception {
        BaseObjectResource<Group> resource = new BaseObjectResource<>(Group.class) {
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

    @Test
    public void nonAdminAddForcesOwnTenant() throws Exception {
        var resource = groupResource(2, user(2, 5, false));
        Group group = new Group();
        group.setTenantId(99);
        resource.add(group);
        assertEquals(5, group.getTenantId());
    }

    @Test
    public void legacyNonAdminAddForcesZero() throws Exception {
        var resource = groupResource(2, user(2, 0, false));
        Group group = new Group();
        group.setTenantId(99);
        resource.add(group);
        assertEquals(0, group.getTenantId());
    }

    @Test
    public void adminAddKeepsTenant() throws Exception {
        var resource = groupResource(1, user(1, 0, true));
        Group group = new Group();
        group.setTenantId(99);
        resource.add(group);
        assertEquals(99, group.getTenantId());
    }

    @Test
    public void nonAdminUpdateKeepsStoredTenant() throws Exception {
        var resource = groupResource(2, user(2, 5, false));
        Group before = new Group();
        before.setId(10);
        before.setTenantId(5);
        when(storage.getObject(eq(Group.class), any(Request.class))).thenReturn(before);
        Group group = new Group();
        group.setId(10);
        group.setTenantId(99);
        resource.update(group);
        assertEquals(5, group.getTenantId());
    }

    @Test
    public void adminUpdateChangesTenant() throws Exception {
        var resource = groupResource(1, user(1, 0, true));
        Group group = new Group();
        group.setId(10);
        group.setTenantId(99);
        resource.update(group);
        assertEquals(99, group.getTenantId());
    }

    private PermissionsService realService(User logged) throws Exception {
        PermissionsService service = new PermissionsService(storage);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(logged);
        return service;
    }

    @Test
    public void checkUserUpdateRequiresAdminToChangeTenant() throws Exception {
        PermissionsService service = realService(user(2, 5, false));
        User before = user(3, 5, false);
        User after = user(3, 6, false);
        assertThrows(SecurityException.class, () -> service.checkUserUpdate(2, before, after));
    }

    @Test
    public void checkUserUpdateRejectsAdministratorWithTenant() throws Exception {
        PermissionsService service = realService(user(1, 0, true));
        User before = user(3, 0, false);
        User after = user(3, 6, true);
        assertThrows(IllegalArgumentException.class, () -> service.checkUserUpdate(1, before, after));
    }

    @Test
    public void checkUserUpdateAdminMayChangeTenant() throws Exception {
        PermissionsService service = realService(user(1, 0, true));
        assertDoesNotThrow(() -> service.checkUserUpdate(1, user(3, 0, false), user(3, 6, false)));
    }

    @Test
    public void checkUserRequiresCompatibleTenants() throws Exception {
        User manager = user(2, 5, false);
        manager.setUserLimit(-1);
        PermissionsService service = new PermissionsService(storage);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(manager, user(3, 6, false));
        when(storage.getPermissions(any(), eq(2L), any(), eq(3L))).thenReturn(
                java.util.List.of(new org.traccar.model.Permission(
                        User.class, 2, org.traccar.model.ManagedUser.class, 3)));
        assertThrows(SecurityException.class, () -> service.checkUser(2, 3));
    }

    @Test
    public void checkUserAllowsSameTenant() throws Exception {
        User manager = user(2, 5, false);
        manager.setUserLimit(-1);
        PermissionsService service = new PermissionsService(storage);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(manager, user(3, 5, false));
        when(storage.getPermissions(any(), eq(2L), any(), eq(3L))).thenReturn(
                java.util.List.of(new org.traccar.model.Permission(
                        User.class, 2, org.traccar.model.ManagedUser.class, 3)));
        assertDoesNotThrow(() -> service.checkUser(2, 3));
    }

    private UserResource userResource(long userId, User logged) throws Exception {
        UserResource resource = new UserResource() {
            @Override
            protected long getUserId() {
                return userId;
            }
        };
        Server server = new Server();
        server.setRegistration(true);
        PermissionsService service = new PermissionsService(storage);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(server);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(logged);
        when(storage.addObject(any(), any(Request.class))).thenReturn(9L);
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", service);
        inject(BaseObjectResource.class, resource, "tenantAccessService", mock(TenantAccessService.class));
        inject(UserResource.class, resource, "config", mock(Config.class));
        inject(UserResource.class, resource, "actionLogger", mock(LogAction.class));
        return resource;
    }

    @Test
    public void anonymousRegistrationCreatesLegacyUser() throws Exception {
        UserResource resource = userResource(0, null);
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(java.util.List.of(new User()));
        User entity = new User();
        entity.setTenantId(7);
        resource.add(entity);
        assertEquals(0, entity.getTenantId());
    }

    @Test
    public void tenantManagerCreatesUserInOwnTenant() throws Exception {
        User manager = user(2, 5, false);
        manager.setUserLimit(-1);
        UserResource resource = userResource(2, manager);
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(java.util.List.of());
        User entity = new User();
        entity.setTenantId(7);
        resource.add(entity);
        assertEquals(5, entity.getTenantId());
    }

    @Test
    public void adminCannotCreateAdministratorWithTenant() throws Exception {
        UserResource resource = userResource(1, user(1, 0, true));
        User entity = new User();
        entity.setAdministrator(true);
        entity.setTenantId(7);
        assertThrows(IllegalArgumentException.class, () -> resource.add(entity));
        verify(storage, never()).addObject(any(), any(Request.class));
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

    private static LinkedHashMap<String, Long> link(long userId, long deviceId) {
        LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        map.put("userId", userId);
        map.put("deviceId", deviceId);
        return map;
    }

    @Test
    public void permissionBetweenDifferentTenantsIsRejected() throws Exception {
        var resource = permissionsResource();
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user(2, 5, false));
        Device device = new Device();
        device.setTenantId(6);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        assertThrows(IllegalArgumentException.class, () -> resource.add(link(2, 3)));
        verify(storage, never()).addPermission(any());
    }

    @Test
    public void permissionBetweenSameTenantsIsAccepted() throws Exception {
        var resource = permissionsResource();
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user(2, 5, false));
        Device device = new Device();
        device.setTenantId(5);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        resource.add(link(2, 3));
        verify(storage).addPermission(any());
    }

    @Test
    public void permissionBetweenLegacyObjectsIsAccepted() throws Exception {
        var resource = permissionsResource();
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user(2, 0, false));
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(new Device());
        resource.add(link(2, 3));
        verify(storage).addPermission(any());
    }

    @Test
    public void shareUserDoesNotInheritTenant() throws Exception {
        ShareResource resource = new ShareResource() {
            @Override
            protected long getUserId() {
                return 2;
            }
        };
        User owner = user(2, 5, false);
        owner.setEmail("a@b.c");
        Device device = new Device();
        device.setUniqueId("u1");
        device.setName("d");
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        when(storage.addObject(any(), any(Request.class))).thenReturn(11L);
        when(permissionsService.getUser(2)).thenReturn(owner);
        when(permissionsService.getServer()).thenReturn(new Server());
        TokenManager tokenManager = mock(TokenManager.class);
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        inject(ShareResource.class, resource, "config", mock(Config.class));
        inject(ShareResource.class, resource, "tokenManager", tokenManager);

        resource.shareDevice(3, new java.util.Date(System.currentTimeMillis() + 100000));

        var captor = forClass(Object.class);
        verify(storage).addObject(captor.capture(), any(Request.class));
        assertEquals(0, ((User) captor.getValue()).getTenantId());
    }

}
