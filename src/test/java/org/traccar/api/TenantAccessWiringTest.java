package org.traccar.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.resource.UserResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.TenantAccessService;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TenantAccessWiringTest {

    private Storage storage;
    private PermissionsService permissionsService;
    private TenantAccessService tenantAccessService;

    private static void inject(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Device device(long id, long tenantId) {
        Device device = new Device();
        device.setId(id);
        device.setTenantId(tenantId);
        return device;
    }

    private static User administrator() {
        User user = new User();
        user.setId(1);
        user.setAdministrator(true);
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
        tenantAccessService = mock(TenantAccessService.class);
        when(permissionsService.getUser(1)).thenReturn(administrator());
        when(permissionsService.notAdmin(1)).thenReturn(false);
    }

    private BaseObjectResource<Device> deviceResource() throws Exception {
        BaseObjectResource<Device> resource = new BaseObjectResource<>(Device.class) {
            @Override
            protected long getUserId() {
                return 1;
            }
        };
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        inject(BaseObjectResource.class, resource, "cacheManager", mock(CacheManager.class));
        inject(BaseObjectResource.class, resource, "connectionManager", mock(ConnectionManager.class));
        inject(BaseObjectResource.class, resource, "actionLogger", mock(LogAction.class));
        inject(BaseObjectResource.class, resource, "tenantAccessService", tenantAccessService);
        return resource;
    }

    @Test
    public void updateWithTenantChangeNotifiesService() throws Exception {
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(10, 5));

        deviceResource().update(device(10, 6));

        verify(tenantAccessService).tenantChanged(any(), eq(1L), eq(Device.class), eq(10L), eq(5L), eq(6L));
    }

    @Test
    public void updateWithoutTenantChangeDoesNotNotifyService() throws Exception {
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(10, 5));

        deviceResource().update(device(10, 5));

        verify(tenantAccessService, never()).tenantChanged(any(), anyLong(), any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void addNotifiesService() throws Exception {
        when(storage.addObject(any(), any(Request.class))).thenReturn(10L);

        deviceResource().add(device(0, 5));

        verify(tenantAccessService).objectAdded(Device.class, 10, 5);
    }

    @Test
    public void removeNotifiesServiceWithStoredTenant() throws Exception {
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device(10, 5));

        deviceResource().remove(10);

        verify(tenantAccessService).objectRemoved(Device.class, 10, 5);
    }

    @Test
    public void userAddNotifiesService() throws Exception {
        UserResource resource = new UserResource() {
            @Override
            protected long getUserId() {
                return 1;
            }
        };
        Server server = new Server();
        PermissionsService service = mock(PermissionsService.class);
        when(service.getUser(1)).thenReturn(administrator());
        when(service.getServer()).thenReturn(server);
        when(storage.addObject(any(), any(Request.class))).thenReturn(9L);
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", service);
        inject(BaseObjectResource.class, resource, "tenantAccessService", tenantAccessService);
        inject(UserResource.class, resource, "config", mock(Config.class));
        inject(UserResource.class, resource, "actionLogger", mock(LogAction.class));

        User entity = new User();
        entity.setTenantId(5);
        resource.add(entity);

        verify(tenantAccessService).objectAdded(User.class, 9, 5);
    }

}
