package org.traccar.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.ManagedUser;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MemoryStorageTenantTest {

    private MemoryStorage storage;

    @BeforeEach
    public void setUp() {
        storage = new MemoryStorage();
    }

    private long user(long tenantId) throws StorageException {
        User user = new User();
        user.setTenantId(tenantId);
        user.setId(storage.addObject(user, new Request(new Columns.All())));
        return user.getId();
    }

    private long managedUser(long tenantId) throws StorageException {
        ManagedUser user = new ManagedUser();
        user.setTenantId(tenantId);
        user.setId(storage.addObject(user, new Request(new Columns.All())));
        return user.getId();
    }

    private long device(long tenantId, long groupId) throws StorageException {
        Device device = new Device();
        device.setTenantId(tenantId);
        device.setGroupId(groupId);
        device.setId(storage.addObject(device, new Request(new Columns.All())));
        return device.getId();
    }

    private long group(long tenantId) throws StorageException {
        Group group = new Group();
        group.setTenantId(tenantId);
        group.setId(storage.addObject(group, new Request(new Columns.All())));
        return group.getId();
    }

    private void link(Class<? extends org.traccar.model.BaseModel> owner, long ownerId,
                      Class<? extends org.traccar.model.BaseModel> property, long propertyId)
            throws StorageException {
        storage.addPermission(new Permission(owner, ownerId, property, propertyId));
    }

    private Set<Long> deviceIds(Condition condition) throws StorageException {
        List<Device> devices = storage.getObjects(Device.class, new Request(new Columns.All(), condition));
        return devices.stream().map(Device::getId).collect(Collectors.toSet());
    }

    private Set<Long> userIds(Condition condition) throws StorageException {
        List<User> users = storage.getObjects(User.class, new Request(new Columns.All(), condition));
        return users.stream().map(User::getId).collect(Collectors.toSet());
    }

    @Test
    public void tenantUserSeesTenantDevicesWithoutLink() throws StorageException {
        long u = user(1);
        long own = device(1, 0);
        long other = device(2, 0);
        long none = device(0, 0);
        assertEquals(Set.of(own), deviceIds(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void tenantUserIsolatedFromLinkedForeignDevices() throws StorageException {
        long u = user(1);
        long own = device(1, 0);
        long foreign = device(2, 0);
        long untenanted = device(0, 0);
        link(User.class, u, Device.class, foreign);
        link(User.class, u, Device.class, untenanted);
        assertEquals(Set.of(own), deviceIds(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void tenantUserIsolatedFromForeignDeviceInLinkedGroup() throws StorageException {
        long u = user(1);
        long g = group(1);
        long inGroupOwn = device(1, g);
        long inGroupForeign = device(2, g);
        link(User.class, u, Group.class, g);
        assertEquals(Set.of(inGroupOwn), deviceIds(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void tenantUserSeesTenantGroupsWithoutLink() throws StorageException {
        long u = user(1);
        long own = group(1);
        group(2);
        group(0);
        List<Group> groups = storage.getObjects(
                Group.class, new Request(new Columns.All(), new Condition.Permission(User.class, u, Group.class)));
        assertEquals(Set.of(own), groups.stream().map(Group::getId).collect(Collectors.toSet()));
    }

    @Test
    public void legacyUserKeepsCurrentBehavior() throws StorageException {
        long u = user(0);
        long linkedForeign = device(2, 0);
        long linkedNone = device(0, 0);
        device(1, 0);
        link(User.class, u, Device.class, linkedForeign);
        link(User.class, u, Device.class, linkedNone);
        assertEquals(Set.of(linkedForeign, linkedNone),
                deviceIds(new Condition.Permission(User.class, u, Device.class)));
    }

    @Test
    public void excludeGroupsShowsOnlyDirectLinksWithoutDerived() throws StorageException {
        long u = user(1);
        long direct = device(1, 0);
        device(1, 0);
        long foreign = device(2, 0);
        link(User.class, u, Device.class, direct);
        link(User.class, u, Device.class, foreign);
        assertEquals(Set.of(direct),
                deviceIds(new Condition.Permission(User.class, u, Device.class).excludeGroups()));
    }

    @Test
    public void deviceUsersIncludeSameTenantAndExcludeOthers() throws StorageException {
        long d = device(1, 0);
        long sameNoLink = user(1);
        long sameLinked = user(1);
        long foreignLinked = user(2);
        long legacyLinked = user(0);
        link(User.class, sameLinked, Device.class, d);
        link(User.class, foreignLinked, Device.class, d);
        link(User.class, legacyLinked, Device.class, d);
        assertEquals(Set.of(sameNoLink, sameLinked, legacyLinked),
                userIds(new Condition.Permission(User.class, Device.class, d)));
        assertEquals(Set.of(sameLinked, legacyLinked),
                userIds(new Condition.Permission(User.class, Device.class, d).excludeGroups()));
    }

    @Test
    public void untenantedDeviceUsersOnlyLegacyUsers() throws StorageException {
        long d = device(0, 0);
        long legacyLinked = user(0);
        long legacyNoLink = user(0);
        long tenantLinked = user(1);
        link(User.class, legacyLinked, Device.class, d);
        link(User.class, tenantLinked, Device.class, d);
        assertEquals(Set.of(legacyLinked), userIds(new Condition.Permission(User.class, Device.class, d)));
        assertEquals(false, userIds(new Condition.Permission(User.class, Device.class, d)).contains(legacyNoLink));
    }

    @Test
    public void tenantUserManagesOnlySameTenantUsers() throws StorageException {
        long manager = user(1);
        long same = managedUser(1);
        long foreign = managedUser(2);
        long legacy = managedUser(0);
        link(User.class, manager, ManagedUser.class, same);
        link(User.class, manager, ManagedUser.class, foreign);
        link(User.class, manager, ManagedUser.class, legacy);
        List<ManagedUser> result = storage.getObjects(ManagedUser.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, manager, ManagedUser.class)));
        assertEquals(Set.of(same), result.stream().map(ManagedUser::getId).collect(Collectors.toSet()));
    }

    @Test
    public void legacyUserManagesAllLinkedUsers() throws StorageException {
        long manager = user(0);
        long same = managedUser(1);
        long legacy = managedUser(0);
        managedUser(0);
        link(User.class, manager, ManagedUser.class, same);
        link(User.class, manager, ManagedUser.class, legacy);
        List<ManagedUser> result = storage.getObjects(ManagedUser.class, new Request(
                new Columns.All(), new Condition.Permission(User.class, manager, ManagedUser.class)));
        assertEquals(Set.of(same, legacy), result.stream().map(ManagedUser::getId).collect(Collectors.toSet()));
    }

}
