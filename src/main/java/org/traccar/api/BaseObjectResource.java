/*
 * Copyright 2017 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;
import org.traccar.api.security.ServiceAccountUser;
import org.traccar.model.ObjectOperation;
import org.traccar.helper.LogAction;
import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.GroupedModel;
import org.traccar.model.Permission;
import org.traccar.model.TenantModel;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.TenantAccessService;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

public abstract class BaseObjectResource<T extends BaseModel> extends BaseResource {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private LogAction actionLogger;

    @Inject
    protected TenantAccessService tenantAccessService;

    @Context
    private HttpServletRequest request;

    protected final Class<T> baseClass;

    public BaseObjectResource(Class<T> baseClass) {
        this.baseClass = baseClass;
    }

    @Path("{id}")
    @GET
    public Response getSingle(@PathParam("id") long id) throws StorageException {
        permissionsService.checkPermission(baseClass, getUserId(), id);
        T entity = storage.getObject(baseClass, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (entity != null) {
            return Response.ok(entity).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    public Response add(T entity) throws Exception {
        if (entity instanceof TenantModel tenantModel && permissionsService.notAdmin(getUserId())) {
            tenantModel.setTenantId(permissionsService.getUser(getUserId()).getTenantId());
        }
        checkGroupTenant(entity);
        permissionsService.checkEdit(getUserId(), entity, true, false);

        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        actionLogger.create(request, getUserId(), entity);

        if (getUserId() != ServiceAccountUser.ID) {
            storage.addPermission(new Permission(User.class, getUserId(), baseClass, entity.getId()));
            cacheManager.invalidatePermission(true, User.class, getUserId(), baseClass, entity.getId(), true);
            connectionManager.invalidatePermission(true, User.class, getUserId(), baseClass, entity.getId(), true);
            actionLogger.link(request, getUserId(), User.class, getUserId(), baseClass, entity.getId());
        }

        if (entity instanceof TenantModel tenantModel) {
            tenantAccessService.objectAdded(baseClass, entity.getId(), tenantModel.getTenantId());
        }

        return Response.ok(entity).build();
    }

    private void checkGroupTenant(T entity) throws StorageException {
        if (entity instanceof GroupedModel grouped && entity instanceof TenantModel tenantModel
                && grouped.getGroupId() > 0) {
            Group group = storage.getObject(Group.class, new Request(
                    new Columns.Include("tenantId"), new Condition.Equals("id", grouped.getGroupId())));
            if (group != null && group.getTenantId() != tenantModel.getTenantId()) {
                throw new IllegalArgumentException("Group belongs to a different tenant");
            }
        }
    }

    private void checkGroupContentTenant(Group group) throws StorageException {
        Group current = storage.getObject(Group.class, new Request(
                new Columns.Include("tenantId"), new Condition.Equals("id", group.getId())));
        if (current == null || current.getTenantId() == group.getTenantId()) {
            return;
        }
        Condition byGroup = new Condition.Equals("groupId", group.getId());
        Columns columns = new Columns.Include("id", "tenantId");
        boolean mismatch = storage.getObjects(Device.class, new Request(columns, byGroup)).stream()
                .anyMatch(device -> device.getTenantId() != group.getTenantId())
                || storage.getObjects(Group.class, new Request(columns, byGroup)).stream()
                .anyMatch(child -> child.getTenantId() != group.getTenantId());
        if (mismatch) {
            throw new IllegalArgumentException("Group has children or devices from a different tenant");
        }
    }

    @Path("{id}")
    @PUT
    public Response update(T entity) throws Exception {
        permissionsService.checkPermission(baseClass, getUserId(), entity.getId());

        Long previousTenantId = null;
        if (entity instanceof TenantModel tenantModel) {
            T current = storage.getObject(baseClass, new Request(
                    new Columns.All(), new Condition.Equals("id", entity.getId())));
            if (current instanceof TenantModel currentTenant) {
                previousTenantId = currentTenant.getTenantId();
                if (permissionsService.notAdmin(getUserId()) || !tenantModel.tenantIdSpecified()) {
                    tenantModel.setTenantId(previousTenantId);
                }
            }
        }
        checkGroupTenant(entity);

        boolean skipReadonly = false;
        if (entity instanceof User after) {
            User before = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", entity.getId())));
            permissionsService.checkUserUpdate(getUserId(), before, after);
            skipReadonly = permissionsService.getUser(getUserId())
                    .compare(after, "notificationTokens", "termsAccepted");
        } else if (entity instanceof Group group) {
            long parentId = group.getGroupId();
            int depth = Storage.MAX_GROUP_DEPTH;
            while (parentId > 0 && depth-- > 0) {
                if (parentId == group.getId()) {
                    throw new IllegalArgumentException("Cycle in group hierarchy");
                }
                Group parent = storage.getObject(Group.class, new Request(
                        new Columns.Include("groupId"), new Condition.Equals("id", parentId)));
                parentId = parent != null ? parent.getGroupId() : 0;
            }
            checkGroupContentTenant(group);
        }

        permissionsService.checkEdit(getUserId(), entity, false, skipReadonly);

        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"),
                new Condition.Equals("id", entity.getId())));
        if (entity instanceof User user) {
            if (user.getHashedPassword() != null) {
                storage.updateObject(entity, new Request(
                        new Columns.Include("hashedPassword", "salt"),
                        new Condition.Equals("id", entity.getId())));
            }
        }
        cacheManager.invalidateObject(true, entity.getClass(), entity.getId(), ObjectOperation.UPDATE);
        actionLogger.edit(request, getUserId(), entity);

        if (previousTenantId != null && entity instanceof TenantModel tenantModel
                && previousTenantId != tenantModel.getTenantId()) {
            tenantAccessService.tenantChanged(
                    request, getUserId(), baseClass, entity.getId(), previousTenantId, tenantModel.getTenantId());
        }

        return Response.ok(entity).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        permissionsService.checkPermission(baseClass, getUserId(), id);
        permissionsService.checkEdit(getUserId(), baseClass, false, false);

        long tenantId = 0;
        if (Device.class.equals(baseClass) || User.class.equals(baseClass)) {
            T current = storage.getObject(baseClass, new Request(
                    new Columns.All(), new Condition.Equals("id", id)));
            if (current instanceof TenantModel currentTenant) {
                tenantId = currentTenant.getTenantId();
            }
        }

        storage.removeObject(baseClass, new Request(new Condition.Equals("id", id)));
        cacheManager.invalidateObject(true, baseClass, id, ObjectOperation.DELETE);

        actionLogger.remove(request, getUserId(), baseClass, id);

        tenantAccessService.objectRemoved(baseClass, id, tenantId);

        return Response.noContent().build();
    }

}
