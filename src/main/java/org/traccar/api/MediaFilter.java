/*
 * Copyright 2018 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2018 Andrey Kunitsyn (andrey@traccar.org)
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

import com.google.inject.Provider;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.traccar.api.security.LoginResult;
import org.traccar.api.security.LoginService;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.DeviceImageStorage;
import org.traccar.database.StatisticsManager;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Singleton
public class MediaFilter implements Filter {

    private final Storage storage;
    private final StatisticsManager statisticsManager;
    private final LoginService loginService;
    private final DeviceImageStorage deviceImageStorage;
    private final Provider<PermissionsService> permissionsServiceProvider;

    @Inject
    public MediaFilter(
            Storage storage, StatisticsManager statisticsManager, LoginService loginService,
            DeviceImageStorage deviceImageStorage,
            Provider<PermissionsService> permissionsServiceProvider) {
        this.storage = storage;
        this.statisticsManager = statisticsManager;
        this.loginService = loginService;
        this.deviceImageStorage = deviceImageStorage;
        this.permissionsServiceProvider = permissionsServiceProvider;
    }

    /**
     * Streams a device photo from S3. Returns false when the photo is not there, so the request falls back to the
     * media folder (photos uploaded before S3 was enabled).
     */
    private boolean serveFromStorage(String uniqueId, String name, HttpServletResponse response)
            throws IOException {
        try (var object = deviceImageStorage.get(uniqueId, name)) {
            if (object == null) {
                return false;
            }
            var metadata = object.response();
            response.setContentType(metadata.contentType());
            if (metadata.contentLength() != null) {
                response.setContentLengthLong(metadata.contentLength());
            }
            response.setHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "private, max-age=300");
            object.transferTo(response.getOutputStream());
            return true;
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setHeader(HttpHeaderNames.CONTENT_SECURITY_POLICY.toString(), "sandbox");
        try {
            PermissionsService permissionsService = permissionsServiceProvider.get();
            LoginResult loginResult = loginService.login((HttpServletRequest) request);
            if (loginResult == null || loginResult.getUser() == null) {
                httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            long userId = loginResult.getUser().getId();
            statisticsManager.registerRequest(userId);

            String path = ((HttpServletRequest) request).getPathInfo();
            String[] parts = path != null ? path.split("/") : null;
            if (parts != null && parts.length >= 2) {
                Device device = storage.getObject(Device.class, new Request(
                        new Columns.All(), new Condition.Equals("uniqueId", parts[1])));
                if (device != null) {
                    permissionsService.checkPermission(Device.class, userId, device.getId());
                    if (deviceImageStorage.isEnabled() && parts.length == 3
                            && DeviceImageStorage.isImageName(parts[2])
                            && serveFromStorage(device.getUniqueId(), parts[2], httpResponse)) {
                        return;
                    }
                    chain.doFilter(request, response);
                    return;
                }
            }

            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
        } catch (SecurityException | StorageException e) {
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }

}
