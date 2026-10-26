package org.traccar.session.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Notification;
import org.traccar.model.Permission;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CacheManagerTenantTest {

    private Storage storage;
    private CacheManager cacheManager;

    private static User user(long id, long tenantId) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenantId);
        return user;
    }

    private static Device device(long id, long tenantId) {
        Device device = new Device();
        device.setId(id);
        device.setTenantId(tenantId);
        return device;
    }

    private static Set<Long> ids(Set<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toSet());
    }

    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        cacheManager = new CacheManager(new Config(), storage, mock(BroadcastService.class));
    }

    @Test
    public void tenantDeviceIsLinkedToEveryUserOfTheTenant() throws Exception {
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(1, 5));
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(List.of(user(2, 5), user(3, 5)));

        cacheManager.addDevice(1, new Object());

        assertEquals(Set.of(2L, 3L), ids(cacheManager.getDeviceObjects(1, User.class)));
    }

    @Test
    public void tenantDeviceKeepsDirectLinks() throws Exception {
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(1, 5));
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(List.of(user(2, 5)));
        when(storage.getPermissions(eq(User.class), eq(Device.class))).thenReturn(
                List.of(new Permission(User.class, 9, Device.class, 1)));
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user(9, 0));

        cacheManager.addDevice(1, new Object());

        assertEquals(Set.of(2L, 9L), ids(cacheManager.getDeviceObjects(1, User.class)));
    }

    @Test
    public void legacyDeviceDoesNotQueryTenantUsers() throws Exception {
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(1, 0));

        cacheManager.addDevice(1, new Object());

        verify(storage, never()).getObjects(eq(User.class), any(Request.class));
        assertEquals(Set.of(), ids(cacheManager.getDeviceObjects(1, User.class)));
    }

    @Test
    public void alwaysNotificationOfTenantUserAppliesToTenantDevice() throws Exception {
        Notification notification = new Notification();
        notification.setId(7);
        notification.setAlways(true);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(1, 5));
        when(storage.getObject(eq(Notification.class), any(Request.class))).thenReturn(notification);
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(List.of(user(2, 5), user(3, 5)));
        when(storage.getPermissions(eq(User.class), eq(Notification.class))).thenReturn(
                List.of(new Permission(User.class, 2, Notification.class, 7)));

        cacheManager.addDevice(1, new Object());

        assertEquals(Set.of(7L), cacheManager.getDeviceNotifications(1).stream()
                .map(Notification::getId).collect(Collectors.toSet()));
        assertEquals(Set.of(2L), ids(cacheManager.getNotificationUsers(7, 1)));
    }

}
