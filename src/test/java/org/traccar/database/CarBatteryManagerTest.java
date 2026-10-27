package org.traccar.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CarBatteryManagerTest {

    @Test
    public void testEstimatePercent() {
        assertEquals(0, CarBatteryManager.estimatePercent(10.5));
        assertEquals(0, CarBatteryManager.estimatePercent(11.8));
        assertEquals(25, CarBatteryManager.estimatePercent(12.0));
        assertEquals(50, CarBatteryManager.estimatePercent(12.2));
        assertEquals(75, CarBatteryManager.estimatePercent(12.4));
        assertEquals(63, CarBatteryManager.estimatePercent(12.3));
        assertEquals(100, CarBatteryManager.estimatePercent(12.6));
        assertEquals(100, CarBatteryManager.estimatePercent(13.8));
    }

}
