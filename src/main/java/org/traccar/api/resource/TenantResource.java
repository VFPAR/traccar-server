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
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Tenant;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.stream.Stream;

@Path("tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantResource extends BaseResource {

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    @GET
    public Stream<Tenant> get() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        return storage.getObjectsStream(Tenant.class, new Request(new Columns.All(), new Order("name")));
    }

    @Path("{id}")
    @GET
    public Response getSingle(@PathParam("id") long id) throws StorageException {
        User user = permissionsService.getUser(getUserId());
        if (!user.getAdministrator() && (user.getTenantId() == 0 || user.getTenantId() != id)) {
            throw new SecurityException("Administrator access required");
        }
        Tenant tenant = storage.getObject(Tenant.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (tenant != null) {
            if (!user.getAdministrator()) {
                Tenant limited = new Tenant();
                limited.setId(tenant.getId());
                limited.setName(tenant.getName());
                tenant = limited;
            }
            return Response.ok(tenant).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    public Response add(Tenant entity) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        normalize(entity);
        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        actionLogger.create(request, getUserId(), entity);
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @PUT
    public Response update(@PathParam("id") long id, Tenant entity) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        normalize(entity);
        Tenant current = storage.getObject(Tenant.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (current == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        boolean disabled = entity.disabledSpecified() ? entity.getDisabled() : current.getDisabled();
        entity.setId(id);
        entity.setDisabled(disabled);
        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", id)));
        actionLogger.edit(request, getUserId(), entity);
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        if (!storage.getObjects(Device.class, new Request(
                new Columns.Include("id"), new Condition.Equals("tenantId", id))).isEmpty()
                || !storage.getObjects(User.class, new Request(
                new Columns.Include("id"), new Condition.Equals("tenantId", id))).isEmpty()) {
            throw new IllegalStateException("Tenant cannot be deleted while it has linked objects");
        }
        storage.removeObject(Tenant.class, new Request(new Condition.Equals("id", id)));
        actionLogger.remove(request, getUserId(), Tenant.class, id);
        return Response.noContent().build();
    }

    private static void normalize(Tenant tenant) {
        String document = tenant.getDocument();
        if (document != null) {
            String digits = document.replaceAll("[^0-9]", "");
            if (digits.isEmpty() && document.isBlank()) {
                tenant.setDocument(null);
            } else if (isValidCnpj(digits)) {
                tenant.setDocument(digits);
            } else {
                throw new IllegalArgumentException("Invalid document");
            }
        }
        String email = tenant.getEmail();
        if (email != null && email.isBlank()) {
            tenant.setEmail(null);
        } else if (email != null && !email.matches("^[^@ \t]+@[^@ \t]+[.][^@ \t]+$")) {
            throw new IllegalArgumentException("Invalid email");
        }
        Double latitude = tenant.getBaseLatitude();
        if (latitude != null && (latitude.isNaN() || latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("Invalid latitude");
        }
        Double longitude = tenant.getBaseLongitude();
        if (longitude != null && (longitude.isNaN() || longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("Invalid longitude");
        }
        Integer radius = tenant.getBaseRadius();
        if (radius != null && radius < 0) {
            throw new IllegalArgumentException("Invalid radius");
        }
    }

    private static boolean isValidCnpj(String digits) {
        if (digits.length() != 14 || digits.chars().distinct().count() == 1) {
            return false;
        }
        return cnpjDigit(digits, 12) == digits.charAt(12) - '0'
                && cnpjDigit(digits, 13) == digits.charAt(13) - '0';
    }

    private static int cnpjDigit(String digits, int length) {
        int sum = 0;
        int weight = 2;
        for (int i = length - 1; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

}
