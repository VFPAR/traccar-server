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
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives the vehicle battery voltage and an estimated charge percentage from the last position
 * that carried a main power reading. The percentage is only an estimate for a 12 V lead-acid battery
 * and is only available when the sample was taken with the ignition off.
 */
@Singleton
public class CarBatteryManager {

    private static final long LOOKBACK_MILLIS = 24L * 60 * 60 * 1000;
    private static final long CACHE_MILLIS = 60 * 1000;
    private static final int SCAN_LIMIT = 100;

    // resting voltage -> charge percentage
    private static final double[][] CURVE = {
            {11.8, 0}, {12.0, 25}, {12.2, 50}, {12.4, 75}, {12.6, 100},
    };

    private record Sample(Double voltage, Integer percent, long loadedAt) {
    }

    private final Storage storage;
    private final Map<Long, Sample> cache = new ConcurrentHashMap<>();

    @Inject
    public CarBatteryManager(Storage storage) {
        this.storage = storage;
    }

    public static Integer estimatePercent(double voltage) {
        if (voltage <= CURVE[0][0]) {
            return 0;
        }
        for (int i = 1; i < CURVE.length; i++) {
            if (voltage <= CURVE[i][0]) {
                double ratio = (voltage - CURVE[i - 1][0]) / (CURVE[i][0] - CURVE[i - 1][0]);
                return (int) Math.round(CURVE[i - 1][1] + ratio * (CURVE[i][1] - CURVE[i - 1][1]));
            }
        }
        return 100;
    }

    public void fill(Device device) {
        Sample sample = get(device.getId());
        device.setCarBatteryVoltage(sample.voltage());
        device.setCarBatteryPercent(sample.percent());
    }

    private Sample get(long deviceId) {
        long now = System.currentTimeMillis();
        Sample cached = cache.get(deviceId);
        if (cached != null && now - cached.loadedAt() < CACHE_MILLIS) {
            return cached;
        }
        Sample loaded = load(deviceId, now);
        cache.put(deviceId, loaded);
        return loaded;
    }

    private Sample load(long deviceId, long now) {
        try {
            var positions = storage.getObjects(Position.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.Compare("fixTime", ">=", new Date(now - LOOKBACK_MILLIS))),
                    new Order("fixTime", true, SCAN_LIMIT)));
            for (Position position : positions) {
                if (position.hasAttribute(Position.KEY_POWER)) {
                    double voltage = position.getDouble(Position.KEY_POWER);
                    boolean ignitionOff = position.hasAttribute(Position.KEY_IGNITION)
                            && !position.getBoolean(Position.KEY_IGNITION);
                    return new Sample(voltage, ignitionOff ? estimatePercent(voltage) : null, now);
                }
            }
        } catch (StorageException e) {
            // derived data is best effort and must not break the device listing
        }
        return new Sample(null, null, now);
    }

}
