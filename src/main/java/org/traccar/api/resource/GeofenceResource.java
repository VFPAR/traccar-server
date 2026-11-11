/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
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

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.traccar.api.ExtendedObjectResource;
import org.traccar.model.Geofence;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("geofences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeofenceResource extends ExtendedObjectResource<Geofence> {

    @Inject
    private ObjectMapper objectMapper;

    public GeofenceResource() {
        super(Geofence.class, "name", List.of("name"));
    }

    @Path("{id}")
    @PATCH
    public Response patch(@PathParam("id") long id, JsonNode changes) throws Exception {
        permissionsService.checkPermission(Geofence.class, getUserId(), id);
        if (changes == null || !changes.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }

        Geofence geofence = storage.getObject(Geofence.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (geofence == null) {
            throw new IllegalArgumentException("Geofence not found");
        }

        ObjectNode fields = ((ObjectNode) changes).deepCopy();
        fields.remove("id");
        JsonNode attributes = fields.remove("attributes");
        objectMapper.readerForUpdating(geofence).readValue(fields);

        if (attributes != null && attributes.isObject()) {
            attributes.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNull()) {
                    geofence.getAttributes().remove(entry.getKey());
                } else {
                    geofence.getAttributes().put(
                            entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
                }
            });
        }

        geofence.setId(id);
        return update(geofence);
    }

}
