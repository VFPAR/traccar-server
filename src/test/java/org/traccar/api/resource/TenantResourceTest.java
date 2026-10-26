package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Tenant;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TenantResourceTest {

    private Storage storage;
    private PermissionsService permissionsService;
    private TenantResource resource;
    private LogAction actionLogger;

    private static void inject(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        permissionsService = mock(PermissionsService.class);
        resource = new TenantResource() {
            @Override
            protected long getUserId() {
                return 2;
            }
        };
        inject(BaseResource.class, resource, "storage", storage);
        inject(BaseResource.class, resource, "permissionsService", permissionsService);
        actionLogger = mock(LogAction.class);
        inject(TenantResource.class, resource, "actionLogger", actionLogger);
    }

    @Test
    public void listRequiresAdministrator() throws Exception {
        doThrow(new SecurityException("Administrator access required")).when(permissionsService).checkAdmin(2);
        assertThrows(SecurityException.class, () -> resource.get());
        verify(storage, never()).getObjectsStream(any(), any());
    }

    @Test
    public void addRequiresAdministratorAndSetsId() throws Exception {
        when(storage.addObject(any(), any(Request.class))).thenReturn(7L);
        Tenant tenant = new Tenant();
        tenant.setName("acme");
        Response response = resource.add(tenant);
        assertEquals(7L, ((Tenant) response.getEntity()).getId());

        doThrow(new SecurityException("Administrator access required")).when(permissionsService).checkAdmin(2);
        assertThrows(SecurityException.class, () -> resource.add(new Tenant()));
    }

    @Test
    public void deleteWithLinkedObjectsIsReportedClearly() throws Exception {
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of(new Device()));
        assertThrows(IllegalStateException.class, () -> resource.remove(5));
        verify(storage, never()).removeObject(eq(Tenant.class), any(Request.class));
    }

    @Test
    public void deleteWithLinkedUsersIsReportedClearly() throws Exception {
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(List.of(new User()));
        assertThrows(IllegalStateException.class, () -> resource.remove(5));
        verify(storage, never()).removeObject(eq(Tenant.class), any(Request.class));
    }

    @Test
    public void otherDeleteFailuresPropagate() throws Exception {
        doThrow(new StorageException("boom")).when(storage).removeObject(eq(Tenant.class), any(Request.class));
        assertThrows(StorageException.class, () -> resource.remove(5));
    }

    @Test
    public void deleteWithoutLinksSucceeds() throws Exception {
        assertEquals(204, resource.remove(5).getStatus());
        verify(storage).removeObject(eq(Tenant.class), any(Request.class));
    }

    @Test
    public void updateOfMissingTenantReturnsNotFoundWithoutAudit() throws Exception {
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(null);
        assertEquals(404, resource.update(5, new Tenant()).getStatus());
        verify(storage, never()).updateObject(any(), any(Request.class));
        verify(actionLogger, never()).edit(any(), anyLong(), any());
    }

    @Test
    public void updateWithoutDisabledKeepsCurrentValue() throws Exception {
        Tenant current = new Tenant();
        current.setDisabled(true);
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(current);
        Tenant update = new ObjectMapper().readValue("{\"name\":\"a\"}", Tenant.class);
        resource.update(5, update);
        assertTrue(update.getDisabled());
    }

    @Test
    public void updateWithDisabledAppliesIt() throws Exception {
        Tenant current = new Tenant();
        current.setDisabled(true);
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(current);
        Tenant update = new ObjectMapper().readValue("{\"name\":\"a\",\"disabled\":false}", Tenant.class);
        resource.update(5, update);
        assertFalse(update.getDisabled());
    }

    @Test
    public void tenantUserReadsOnlyOwnTenant() throws Exception {
        User user = new User();
        user.setTenantId(5);
        when(permissionsService.getUser(2)).thenReturn(user);
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(new Tenant());
        assertEquals(200, resource.getSingle(5).getStatus());
        assertThrows(SecurityException.class, () -> resource.getSingle(6));
    }

    @Test
    public void legacyNonAdministratorCannotReadTenant() throws Exception {
        when(permissionsService.getUser(2)).thenReturn(new User());
        assertThrows(SecurityException.class, () -> resource.getSingle(5));
    }

    private Tenant validTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("acme");
        return tenant;
    }

    @Test
    public void addNormalizesValidDocumentToDigits() throws Exception {
        Tenant tenant = validTenant();
        tenant.setDocument("11.222.333/0001-81");
        resource.add(tenant);
        assertEquals("11222333000181", tenant.getDocument());
    }

    @Test
    public void addRejectsInvalidDocument() throws Exception {
        Tenant wrongDigit = validTenant();
        wrongDigit.setDocument("11.222.333/0001-82");
        assertThrows(IllegalArgumentException.class, () -> resource.add(wrongDigit));
        Tenant repeated = validTenant();
        repeated.setDocument("11111111111111");
        assertThrows(IllegalArgumentException.class, () -> resource.add(repeated));
        Tenant shortDocument = validTenant();
        shortDocument.setDocument("123");
        assertThrows(IllegalArgumentException.class, () -> resource.add(shortDocument));
        verify(storage, never()).addObject(any(), any(Request.class));
    }

    @Test
    public void blankDocumentIsTreatedAsAbsent() throws Exception {
        Tenant tenant = validTenant();
        tenant.setDocument("  ");
        resource.add(tenant);
        assertEquals(null, tenant.getDocument());
    }

    @Test
    public void addValidatesCoordinates() throws Exception {
        Tenant badLatitude = validTenant();
        badLatitude.setBaseLatitude(90.1);
        assertThrows(IllegalArgumentException.class, () -> resource.add(badLatitude));
        Tenant badLongitude = validTenant();
        badLongitude.setBaseLongitude(-180.5);
        assertThrows(IllegalArgumentException.class, () -> resource.add(badLongitude));
        Tenant ok = validTenant();
        ok.setBaseLatitude(-23.5);
        ok.setBaseLongitude(-46.6);
        ok.setBaseRadius(500);
        assertEquals(200, resource.add(ok).getStatus());
    }

    @Test
    public void addValidatesRadiusAndEmail() throws Exception {
        Tenant badRadius = validTenant();
        badRadius.setBaseRadius(-1);
        assertThrows(IllegalArgumentException.class, () -> resource.add(badRadius));
        Tenant badEmail = validTenant();
        badEmail.setEmail("not-an-email");
        assertThrows(IllegalArgumentException.class, () -> resource.add(badEmail));
        Tenant goodEmail = validTenant();
        goodEmail.setEmail("a@b.com");
        assertEquals(200, resource.add(goodEmail).getStatus());
    }

    @Test
    public void updateValidatesAndNormalizesDocument() throws Exception {
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(new Tenant());
        Tenant invalid = validTenant();
        invalid.setDocument("12345678000100");
        assertThrows(IllegalArgumentException.class, () -> resource.update(5, invalid));
        verify(storage, never()).updateObject(any(), any(Request.class));
        Tenant valid = validTenant();
        valid.setDocument("11.222.333/0001-81");
        resource.update(5, valid);
        assertEquals("11222333000181", valid.getDocument());
    }

    @Test
    public void tenantUserGetsOnlyIdAndName() throws Exception {
        User user = new User();
        user.setTenantId(5);
        when(permissionsService.getUser(2)).thenReturn(user);
        Tenant stored = validTenant();
        stored.setDocument("11222333000181");
        stored.setCity("Sao Paulo");
        when(storage.getObject(eq(Tenant.class), any(Request.class))).thenReturn(stored);
        Tenant result = (Tenant) resource.getSingle(5).getEntity();
        assertEquals("acme", result.getName());
        assertEquals(null, result.getDocument());
        assertEquals(null, result.getCity());
    }

}
