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
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stores the device photos uploaded through the API (tracker, plate, odometer) in AWS S3. Disabled unless
 * {@link Keys#MEDIA_S3_BUCKET} is set, in which case photos stay in the media folder.
 */
@Singleton
public class DeviceImageStorage {

    private static final Pattern IMAGE_NAME = Pattern.compile("^(device|tracker|plate|odometer)\\.(jpg|png|gif|webp)$");

    private static final Map<String, String> FOLDERS = Map.of(
            "tracker", "rastreador",
            "plate", "rastreadoreplaca",
            "odometer", "odometro",
            "device", "dispositivo");

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    @Inject
    public DeviceImageStorage(Config config) {
        bucket = config.getString(Keys.MEDIA_S3_BUCKET);
        if (bucket == null || bucket.isBlank()) {
            client = null;
            prefix = null;
            return;
        }

        String configuredPrefix = config.getString(Keys.MEDIA_S3_PREFIX);
        prefix = configuredPrefix == null || configuredPrefix.isBlank()
                ? "" : configuredPrefix.replaceAll("^/+|/+$", "") + "/";

        var builder = S3Client.builder();
        String region = config.getString(Keys.MEDIA_S3_REGION);
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        String endpoint = config.getString(Keys.MEDIA_S3_ENDPOINT);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        client = builder.build();
    }

    public boolean isEnabled() {
        return client != null;
    }

    public static boolean isImageName(String name) {
        return name != null && IMAGE_NAME.matcher(name).matches();
    }

    /**
     * Maps a photo name such as tracker.jpg to the key folder/uniqueId.jpg, one folder per photo type.
     */
    private String key(String uniqueId, String name) {
        int dot = name.indexOf('.');
        return prefix + FOLDERS.get(name.substring(0, dot)) + "/" + uniqueId + name.substring(dot);
    }

    public void put(String uniqueId, String name, String contentType, File file) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key(uniqueId, name))
                        .contentType(contentType)
                        .build(),
                RequestBody.fromFile(file));
    }

    /**
     * Opens the stored photo, or returns null when it is not in the bucket (for example a legacy file that only
     * exists in the media folder).
     */
    public ResponseInputStream<GetObjectResponse> get(String uniqueId, String name) {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(uniqueId, name)).build());
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

}
