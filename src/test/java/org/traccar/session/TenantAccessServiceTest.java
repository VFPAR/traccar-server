package org.traccar.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class TenantAccessServiceTest {

    private Storage storage;
    private CacheManager cacheManager;
    private ConnectionManager connectionManager;
    private LogAction actionLogger;
    private TenantAccessService service;

    private Map<Long, List<User>> usersByTenant = Map.of();
    private Map<Long, List<Device>> devicesByTenant = Map.of();
    private List<User> accessibleUsers = List.of();
    private List<Device> accessibleDevices = List.of();
    private Map<Long, User> allUsers = Map.of();

    private static User user(long id, long tenantId, boolean administrator) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setAdministrator(administrator);
        return user;
    }

    private static Device device(long id) {
        Device device = new Device();
        device.setId(id);
        return device;
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        cacheManager = mock(CacheManager.class);
        connectionManager = mock(ConnectionManager.class);
        actionLogger = mock(LogAction.class);
        service = new TenantAccessService(storage, cacheManager, connectionManager, actionLogger);

        when(storage.getObjects(eq(User.class), any(Request.class))).thenAnswer(invocation -> {
            Condition condition = ((Request) invocation.getArgument(1)).getCondition();
            if (condition instanceof Condition.Equals equals) {
                return usersByTenant.getOrDefault((Long) equals.getValue(), List.of());
            }
            return accessibleUsers;
        });
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenAnswer(invocation -> {
            Condition condition = ((Request) invocation.getArgument(1)).getCondition();
            if (condition instanceof Condition.Equals equals) {
                return devicesByTenant.getOrDefault((Long) equals.getValue(), List.of());
            }
            return accessibleDevices;
        });
        when(storage.getObject(eq(User.class), any(Request.class))).thenAnswer(invocation -> {
            Condition.Equals equals = (Condition.Equals) ((Request) invocation.getArgument(1)).getCondition();
            return allUsers.get((Long) equals.getValue());
        });
    }

    private void verifyCache(long userId, long deviceId, boolean link) throws Exception {
        verify(cacheManager).invalidatePermission(true, User.class, userId, Device.class, deviceId, link);
        verify(connectionManager).invalidatePermission(true, User.class, userId, Device.class, deviceId, link);
    }

    @Test
    public void deviceTenantChangeRelinksUsers() throws Exception {
        usersByTenant = Map.of(5L, List.of(user(2, 5, false), user(3, 5, false)), 6L, List.of(user(4, 6, false)));

        service.tenantChanged(null, 1, Device.class, 1, 5, 6);

        verifyCache(2, 1, false);
        verifyCache(3, 1, false);
        verifyCache(4, 1, true);
    }

    @Test
    public void deviceUnlinkSkipsDirectLinkInCacheAndAccessibleUserInWebsocket() throws Exception {
        usersByTenant = Map.of(5L, List.of(user(2, 5, false), user(3, 5, false)));
        allUsers = Map.of(2L, user(2, 0, true));
        accessibleUsers = List.of(user(3, 5, false));
        when(storage.getPermissions(User.class, 0, Device.class, 1)).thenReturn(
                List.of(new Permission(User.class, 2, Device.class, 1)));

        service.tenantChanged(null, 1, Device.class, 1, 5, 0);

        verify(cacheManager, never()).invalidatePermission(
                true, User.class, 2L, Device.class, 1L, false);
        verify(cacheManager).invalidatePermission(true, User.class, 3L, Device.class, 1L, false);
        verify(connectionManager).invalidatePermission(true, User.class, 2L, Device.class, 1L, false);
        verify(connectionManager, never()).invalidatePermission(
                true, User.class, 3L, Device.class, 1L, false);
    }

    @Test
    public void userTenantChangeRelinksDevices() throws Exception {
        devicesByTenant = Map.of(5L, List.of(device(10), device(11)), 6L, List.of(device(12)));
        accessibleDevices = List.of(device(11));

        service.tenantChanged(null, 1, User.class, 2, 5, 6);

        verify(cacheManager).invalidatePermission(true, User.class, 2L, Device.class, 10L, false);
        verify(cacheManager).invalidatePermission(true, User.class, 2L, Device.class, 11L, false);
        verify(connectionManager).invalidatePermission(true, User.class, 2L, Device.class, 10L, false);
        verify(connectionManager, never()).invalidatePermission(
                true, User.class, 2L, Device.class, 11L, false);
        verifyCache(2, 12, true);
    }

    @Test
    public void addedTenantDeviceIsLinkedToTenantUsers() throws Exception {
        usersByTenant = Map.of(5L, List.of(user(2, 5, false)));

        service.objectAdded(Device.class, 1, 5);

        verifyCache(2, 1, true);
    }

    @Test
    public void addedLegacyObjectDoesNothing() throws Exception {
        service.objectAdded(Device.class, 1, 0);
        service.objectAdded(Group.class, 1, 5);

        verifyNoInteractions(storage, cacheManager, connectionManager);
    }

    @Test
    public void removedTenantUserIsUnlinkedFromTenantDevices() throws Exception {
        devicesByTenant = Map.of(5L, List.of(device(10)));

        service.objectRemoved(User.class, 2, 5);

        verifyCache(2, 10, false);
    }

    @Test
    public void unchangedTenantDoesNothing() throws Exception {
        service.tenantChanged(null, 1, Device.class, 1, 5, 5);

        verifyNoInteractions(storage, cacheManager, connectionManager, actionLogger);
    }

    @Test
    public void tenantChangeRemovesLinksCrossingTenants() throws Exception {
        allUsers = Map.of(2L, user(2, 5, false), 3L, user(3, 6, false));
        when(storage.getPermissions(User.class, 0, Group.class, 10)).thenReturn(List.of(
                new Permission(User.class, 2, Group.class, 10),
                new Permission(User.class, 3, Group.class, 10)));

        service.tenantChanged(null, 1, Group.class, 10, 5, 6);

        verify(storage).removePermission(argThat(permission -> permission.getOwnerId() == 2));
        verify(storage, never()).removePermission(argThat(permission -> permission.getOwnerId() == 3));
        verify(cacheManager).invalidatePermission(true, User.class, 2L, Group.class, 10L, false);
        verify(actionLogger).unlink(null, 1L, User.class, 2L, Group.class, 10L);
        verify(actionLogger, never()).unlink(any(), anyLong(), any(), eq(3L), any(), anyLong());
    }

    @Test
    public void administratorOwnerLinksAreKept() throws Exception {
        allUsers = Map.of(1L, user(1, 0, true));
        when(storage.getPermissions(User.class, 0, Group.class, 10)).thenReturn(List.of(
                new Permission(User.class, 1, Group.class, 10)));

        service.tenantChanged(null, 1, Group.class, 10, 0, 6);

        verify(storage, never()).removePermission(any());
        verify(actionLogger, never()).unlink(any(), anyLong(), any(), anyLong(), any(), anyLong());
    }

    @Test
    public void removedUserDeviceLinkLeavesWebsocketOnlyWhenNotAccessible() throws Exception {
        allUsers = Map.of(2L, user(2, 5, false));
        when(storage.getPermissions(User.class, 0, Device.class, 1)).thenReturn(List.of(
                new Permission(User.class, 2, Device.class, 1)));

        service.tenantChanged(null, 1, Device.class, 1, 0, 6);

        verify(storage).removePermission(argThat(permission -> permission.getOwnerId() == 2));
        verify(cacheManager).invalidatePermission(true, User.class, 2L, Device.class, 1L, false);
        verify(connectionManager).invalidatePermission(true, User.class, 2L, Device.class, 1L, false);
        verify(connectionManager, never()).invalidatePermission(
                eq(true), eq(User.class), anyLong(), eq(Device.class), anyLong(), eq(true));
    }

}
