/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.session;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import org.traccar.helper.LogAction;
import org.traccar.model.Attribute;
import org.traccar.model.BaseModel;
import org.traccar.model.Calendar;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.LinkedDevice;
import org.traccar.model.Maintenance;
import org.traccar.model.ManagedUser;
import org.traccar.model.Notification;
import org.traccar.model.Order;
import org.traccar.model.Permission;
import org.traccar.model.Report;
import org.traccar.model.TenantModel;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class TenantAccessService {

    private record Pair(long userId, long deviceId) { }

    private static final List<Class<? extends BaseModel>> OBJECT_LINKS = List.of(
            Geofence.class, Driver.class, Attribute.class, Command.class,
            Maintenance.class, Notification.class, Order.class, Report.class);

    private static final Map<Class<? extends BaseModel>, List<Class<? extends BaseModel>>> LINKS;

    static {
        List<Class<? extends BaseModel>> user = new ArrayList<>(
                List.of(ManagedUser.class, Device.class, Group.class, Calendar.class));
        user.addAll(OBJECT_LINKS);
        List<Class<? extends BaseModel>> device = new ArrayList<>(OBJECT_LINKS);
        device.add(LinkedDevice.class);
        LINKS = Map.of(User.class, user, Group.class, OBJECT_LINKS, Device.class, device);
    }

    private final Storage storage;
    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;
    private final LogAction actionLogger;

    @Inject
    public TenantAccessService(
            Storage storage, CacheManager cacheManager, ConnectionManager connectionManager,
            LogAction actionLogger) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
        this.actionLogger = actionLogger;
    }

    private static boolean isAccessClass(Class<? extends BaseModel> clazz) {
        return Device.class.equals(clazz) || User.class.equals(clazz);
    }

    private static Class<? extends BaseModel> baseClass(Class<? extends BaseModel> clazz) {
        if (ManagedUser.class.equals(clazz)) {
            return User.class;
        } else if (LinkedDevice.class.equals(clazz)) {
            return Device.class;
        }
        return clazz;
    }

    public void objectAdded(Class<? extends BaseModel> clazz, long id, long tenantId) throws Exception {
        if (isAccessClass(clazz) && tenantId > 0) {
            for (Pair pair : tenantPairs(clazz, id, tenantId)) {
                invalidate(pair, true, true, true);
            }
        }
    }

    public void objectRemoved(Class<? extends BaseModel> clazz, long id, long tenantId) throws Exception {
        if (isAccessClass(clazz) && tenantId > 0) {
            unlinkTenantPairs(clazz, id, tenantId, new Context(clazz, id));
        }
    }

    public void tenantChanged(
            HttpServletRequest request, long actorId, Class<? extends BaseModel> clazz, long id,
            long before, long after) throws Exception {
        if (before == after) {
            return;
        }

        Context context = new Context(clazz, id);
        Set<Pair> removedPairs = removeCrossTenantLinks(request, actorId, clazz, id, after);
        for (Pair pair : removedPairs) {
            if (context.hasAccess(pair)) {
                continue;
            }
            connectionManager.invalidatePermission(
                    true, User.class, pair.userId(), Device.class, pair.deviceId(), false);
        }

        if (isAccessClass(clazz)) {
            if (before > 0) {
                unlinkTenantPairs(clazz, id, before, context);
            }
            if (after > 0) {
                for (Pair pair : tenantPairs(clazz, id, after)) {
                    invalidate(pair, true, true, true);
                }
            }
        }
    }

    private List<Pair> tenantPairs(Class<? extends BaseModel> clazz, long id, long tenantId) throws Exception {
        List<Pair> pairs = new ArrayList<>();
        if (Device.class.equals(clazz)) {
            for (User user : storage.getObjects(User.class, new Request(
                    new Columns.Include("id"), new Condition.Equals("tenantId", tenantId)))) {
                pairs.add(new Pair(user.getId(), id));
            }
        } else {
            for (Device device : storage.getObjects(Device.class, new Request(
                    new Columns.Include("id"), new Condition.Equals("tenantId", tenantId)))) {
                pairs.add(new Pair(id, device.getId()));
            }
        }
        return pairs;
    }

    private void unlinkTenantPairs(
            Class<? extends BaseModel> clazz, long id, long tenantId, Context context) throws Exception {
        List<Pair> pairs = tenantPairs(clazz, id, tenantId);
        if (pairs.isEmpty()) {
            return;
        }
        Set<Long> directIds = new HashSet<>();
        if (Device.class.equals(clazz)) {
            for (Permission permission : storage.getPermissions(User.class, 0, Device.class, id)) {
                directIds.add(permission.getOwnerId());
            }
        } else {
            for (Permission permission : storage.getPermissions(User.class, id, Device.class, 0)) {
                directIds.add(permission.getPropertyId());
            }
        }
        for (Pair pair : pairs) {
            long counterpartId = Device.class.equals(clazz) ? pair.userId() : pair.deviceId();
            invalidate(pair, !directIds.contains(counterpartId), !context.hasAccess(pair), false);
        }
    }

    private void invalidate(Pair pair, boolean cache, boolean websocket, boolean link) throws Exception {
        if (cache) {
            cacheManager.invalidatePermission(true, User.class, pair.userId(), Device.class, pair.deviceId(), link);
        }
        if (websocket) {
            connectionManager.invalidatePermission(
                    true, User.class, pair.userId(), Device.class, pair.deviceId(), link);
        }
    }

    private List<Permission> linksOf(Class<? extends BaseModel> clazz, long id) throws Exception {
        List<Permission> result = new ArrayList<>();
        for (Class<? extends BaseModel> property : LINKS.getOrDefault(clazz, List.of())) {
            result.addAll(storage.getPermissions(clazz, id, property, 0));
        }
        for (var entry : LINKS.entrySet()) {
            for (Class<? extends BaseModel> property : entry.getValue()) {
                if (baseClass(property).equals(clazz)) {
                    result.addAll(storage.getPermissions(entry.getKey(), 0, property, id));
                }
            }
        }
        return result;
    }

    private Set<Pair> removeCrossTenantLinks(
            HttpServletRequest request, long actorId, Class<? extends BaseModel> clazz, long id, long tenantId)
            throws Exception {
        Set<Pair> removedPairs = new LinkedHashSet<>();
        Map<String, BaseModel> loaded = new HashMap<>();
        for (Permission permission : linksOf(clazz, id)) {
            if (User.class.equals(permission.getOwnerClass())) {
                User owner = (User) load(loaded, User.class, permission.getOwnerId());
                if (owner != null && owner.getAdministrator()) {
                    continue;
                }
            }
            long ownerTenant = tenantOf(
                    loaded, permission.getOwnerClass(), permission.getOwnerId(), clazz, id, tenantId);
            long propertyTenant = tenantOf(
                    loaded, permission.getPropertyClass(), permission.getPropertyId(), clazz, id, tenantId);
            if (ownerTenant == propertyTenant) {
                continue;
            }

            storage.removePermission(permission);
            cacheManager.invalidatePermission(
                    true, permission.getOwnerClass(), permission.getOwnerId(),
                    permission.getPropertyClass(), permission.getPropertyId(), false);
            actionLogger.unlink(
                    request, actorId, permission.getOwnerClass(), permission.getOwnerId(),
                    permission.getPropertyClass(), permission.getPropertyId());
            if (User.class.equals(permission.getOwnerClass()) && Device.class.equals(permission.getPropertyClass())) {
                removedPairs.add(new Pair(permission.getOwnerId(), permission.getPropertyId()));
            }
        }
        return removedPairs;
    }

    private BaseModel load(
            Map<String, BaseModel> loaded, Class<? extends BaseModel> clazz, long id) throws Exception {
        String key = clazz.getSimpleName() + ":" + id;
        if (!loaded.containsKey(key)) {
            loaded.put(key, storage.getObject(clazz, new Request(
                    new Columns.All(), new Condition.Equals("id", id))));
        }
        return loaded.get(key);
    }

    private long tenantOf(
            Map<String, BaseModel> loaded, Class<? extends BaseModel> clazz, long id,
            Class<? extends BaseModel> changedClass, long changedId, long changedTenantId) throws Exception {
        Class<? extends BaseModel> queryClass = baseClass(clazz);
        if (queryClass.equals(changedClass) && id == changedId) {
            return changedTenantId;
        }
        if (!TenantModel.class.isAssignableFrom(queryClass)) {
            return 0;
        }
        return load(loaded, queryClass, id) instanceof TenantModel tenantModel ? tenantModel.getTenantId() : 0;
    }

    private class Context {

        private final Class<? extends BaseModel> clazz;
        private final long id;
        private Set<Long> accessible;

        Context(Class<? extends BaseModel> clazz, long id) {
            this.clazz = clazz;
            this.id = id;
        }

        boolean hasAccess(Pair pair) throws Exception {
            if (!isAccessClass(clazz)) {
                return false;
            }
            if (accessible == null) {
                if (Device.class.equals(clazz)) {
                    accessible = storage.getObjects(User.class, new Request(
                            new Columns.Include("id"), new Condition.Permission(User.class, Device.class, id)))
                            .stream().map(BaseModel::getId).collect(Collectors.toSet());
                } else {
                    accessible = storage.getObjects(Device.class, new Request(
                            new Columns.Include("id"), new Condition.Permission(User.class, id, Device.class)))
                            .stream().map(BaseModel::getId).collect(Collectors.toSet());
                }
            }
            return accessible.contains(Device.class.equals(clazz) ? pair.userId() : pair.deviceId());
        }

    }

}
