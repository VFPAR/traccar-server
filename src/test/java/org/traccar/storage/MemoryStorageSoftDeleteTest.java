package org.traccar.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryStorageSoftDeleteTest {

    private MemoryStorage storage;

    @BeforeEach
    public void setUp() {
        storage = new MemoryStorage();
    }

    private Device addDevice() throws StorageException {
        Device device = new Device();
        device.setId(storage.addObject(device, new Request(new Columns.Exclude("id"))));
        return device;
    }

    @Test
    public void addFillsCreatedAndUpdatedAt() throws StorageException {
        Device device = addDevice();
        assertNotNull(device.getCreatedAt());
        assertEquals(device.getCreatedAt(), device.getUpdatedAt());
        assertNull(device.getDeletedAt());
    }

    @Test
    public void removeIsSoftAndHidesObject() throws StorageException {
        Device device = addDevice();
        storage.removeObject(Device.class, new Request(new Condition.Equals("id", device.getId())));

        assertTrue(storage.getObjects(Device.class, new Request(new Columns.All())).isEmpty());
        assertNotNull(device.getDeletedAt());
    }

    @Test
    public void updateIgnoresDeletedObject() throws StorageException {
        Device device = addDevice();
        storage.removeObject(Device.class, new Request(new Condition.Equals("id", device.getId())));

        Device changes = new Device();
        changes.setName("changed");
        storage.updateObject(changes, new Request(
                new Columns.Include("name"), new Condition.Equals("id", device.getId())));

        assertNull(device.getName());
    }

    @Test
    public void onlyFullEditTouchesUpdatedAt() throws StorageException {
        Device device = addDevice();
        Date created = device.getUpdatedAt();
        device.setUpdatedAt(new Date(0));

        Device telemetry = new Device();
        telemetry.setPositionId(5);
        storage.updateObject(telemetry, new Request(
                new Columns.Include("positionId"), new Condition.Equals("id", device.getId())));
        assertEquals(new Date(0), device.getUpdatedAt());

        Device edit = new Device();
        edit.setName("edited");
        storage.updateObject(edit, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", device.getId())));
        assertTrue(device.getUpdatedAt().getTime() >= created.getTime());
        assertTrue(device.getUpdatedAt().getTime() > 0);
    }

    @Test
    public void permissionsOfDeletedEntitiesAreHiddenAndKept() throws StorageException {
        Device device = addDevice();
        Geofence geofence = new Geofence();
        geofence.setId(storage.addObject(geofence, new Request(new Columns.Exclude("id"))));
        storage.addPermission(new Permission(User.class, 1, Device.class, device.getId()));
        storage.addPermission(new Permission(Device.class, device.getId(), Geofence.class, geofence.getId()));

        assertEquals(1, storage.getPermissions(User.class, 1, Device.class, 0).size());

        storage.removeObject(Device.class, new Request(new Condition.Equals("id", device.getId())));

        assertTrue(storage.getPermissions(User.class, 1, Device.class, 0).isEmpty());
        assertTrue(storage.getPermissions(Device.class, device.getId(), Geofence.class, 0).isEmpty());
    }
}
