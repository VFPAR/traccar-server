/*
 * Copyright 2012 - 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.traccar.storage.QueryIgnore;
import org.traccar.storage.SoftDelete;
import org.traccar.storage.StorageName;

import java.util.Date;
import java.util.regex.Pattern;

@StorageName("tc_devices")
@SoftDelete
public class Device extends GroupedModel implements Disableable, Schedulable, TenantModel, SoftDeletable {

    private long tenantId;

    private boolean tenantIdSpecified;

    @Override
    public long getTenantId() {
        return tenantId;
    }

    @Override
    public void setTenantId(long tenantId) {
        this.tenantId = tenantId;
        this.tenantIdSpecified = true;
    }

    @Override
    public boolean tenantIdSpecified() {
        return tenantIdSpecified;
    }

    private static final Pattern INVALID_UNIQUE_ID = Pattern.compile("[/\\\\]|\\A\\.{0,2}\\z");

    private long calendarId;

    @Override
    public long getCalendarId() {
        return calendarId;
    }

    @Override
    public void setCalendarId(long calendarId) {
        this.calendarId = calendarId;
    }

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String uniqueId;

    public String getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(String uniqueId) {
        uniqueId = uniqueId.trim();
        if (INVALID_UNIQUE_ID.matcher(uniqueId).find()) {
            throw new IllegalArgumentException("Invalid device identifier");
        }
        this.uniqueId = uniqueId;
    }

    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";

    private String status;

    @QueryIgnore
    public String getStatus() {
        return status != null ? status : STATUS_OFFLINE;
    }

    public void setStatus(String status) {
        this.status = status != null ? status.trim() : null;
    }

    private Date lastUpdate;

    @QueryIgnore
    public Date getLastUpdate() {
        return this.lastUpdate;
    }

    public void setLastUpdate(Date lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    private long positionId;

    @QueryIgnore
    public long getPositionId() {
        return positionId;
    }

    public void setPositionId(long positionId) {
        this.positionId = positionId;
    }

    private Double carBatteryVoltage;

    @QueryIgnore
    public Double getCarBatteryVoltage() {
        return carBatteryVoltage;
    }

    public void setCarBatteryVoltage(Double carBatteryVoltage) {
        this.carBatteryVoltage = carBatteryVoltage;
    }

    private Integer carBatteryPercent;

    @QueryIgnore
    public Integer getCarBatteryPercent() {
        return carBatteryPercent;
    }

    public void setCarBatteryPercent(Integer carBatteryPercent) {
        this.carBatteryPercent = carBatteryPercent;
    }

    private String phone;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone != null ? phone.trim() : null;
    }

    private String model;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    private String contact;

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    private String category;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    private String vehicleModel;

    public String getVehicleModel() {
        return vehicleModel;
    }

    public void setVehicleModel(String vehicleModel) {
        this.vehicleModel = vehicleModel;
    }

    private String plate;

    public String getPlate() {
        return plate;
    }

    public void setPlate(String plate) {
        this.plate = plate != null ? plate.trim() : null;
    }

    private String chassis;

    public String getChassis() {
        return chassis;
    }

    public void setChassis(String chassis) {
        this.chassis = chassis != null ? chassis.trim() : null;
    }

    private String color;

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    private String manufacturer;

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    private Date installationDate;

    public Date getInstallationDate() {
        return installationDate;
    }

    public void setInstallationDate(Date installationDate) {
        this.installationDate = installationDate;
    }

    private String vehicleType;

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    private Integer manufactureYear;

    public Integer getManufactureYear() {
        return manufactureYear;
    }

    public void setManufactureYear(Integer manufactureYear) {
        this.manufactureYear = manufactureYear;
    }

    private Integer modelYear;

    public Integer getModelYear() {
        return modelYear;
    }

    public void setModelYear(Integer modelYear) {
        this.modelYear = modelYear;
    }

    private boolean disabled;

    @Override
    public boolean getDisabled() {
        return disabled;
    }

    @Override
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    private Date expirationTime;

    @Override
    public Date getExpirationTime() {
        return expirationTime;
    }

    @Override
    public void setExpirationTime(Date expirationTime) {
        this.expirationTime = expirationTime;
    }

    private boolean motionStreak;

    @QueryIgnore
    @JsonIgnore
    public boolean getMotionStreak() {
        return motionStreak;
    }

    @JsonIgnore
    public void setMotionStreak(boolean motionStreak) {
        this.motionStreak = motionStreak;
    }

    private boolean motionState;

    @QueryIgnore
    @JsonIgnore
    public boolean getMotionState() {
        return motionState;
    }

    @JsonIgnore
    public void setMotionState(boolean motionState) {
        this.motionState = motionState;
    }

    private long motionPositionId;

    @QueryIgnore
    @JsonIgnore
    public long getMotionPositionId() {
        return motionPositionId;
    }

    @JsonIgnore
    public void setMotionPositionId(long motionPositionId) {
        this.motionPositionId = motionPositionId;
    }

    private Date motionTime;

    @QueryIgnore
    @JsonIgnore
    public Date getMotionTime() {
        return motionTime;
    }

    @JsonIgnore
    public void setMotionTime(Date motionTime) {
        this.motionTime = motionTime;
    }

    private double motionDistance;

    @QueryIgnore
    @JsonIgnore
    public double getMotionDistance() {
        return motionDistance;
    }

    @JsonIgnore
    public void setMotionDistance(double motionDistance) {
        this.motionDistance = motionDistance;
    }

    private double motionLatitude;

    @QueryIgnore
    @JsonIgnore
    public double getMotionLatitude() {
        return motionLatitude;
    }

    @JsonIgnore
    public void setMotionLatitude(double motionLatitude) {
        this.motionLatitude = motionLatitude;
    }

    private double motionLongitude;

    @QueryIgnore
    @JsonIgnore
    public double getMotionLongitude() {
        return motionLongitude;
    }

    @JsonIgnore
    public void setMotionLongitude(double motionLongitude) {
        this.motionLongitude = motionLongitude;
    }

    private boolean overspeedState;

    @QueryIgnore
    @JsonIgnore
    public boolean getOverspeedState() {
        return overspeedState;
    }

    @JsonIgnore
    public void setOverspeedState(boolean overspeedState) {
        this.overspeedState = overspeedState;
    }

    private Date overspeedTime;

    @QueryIgnore
    @JsonIgnore
    public Date getOverspeedTime() {
        return overspeedTime;
    }

    @JsonIgnore
    public void setOverspeedTime(Date overspeedTime) {
        this.overspeedTime = overspeedTime;
    }

    private long overspeedGeofenceId;

    @QueryIgnore
    @JsonIgnore
    public long getOverspeedGeofenceId() {
        return overspeedGeofenceId;
    }

    @JsonIgnore
    public void setOverspeedGeofenceId(long overspeedGeofenceId) {
        this.overspeedGeofenceId = overspeedGeofenceId;
    }

    private Date createdAt;

    @QueryIgnore
    @Override
    public Date getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    private Date updatedAt;

    @QueryIgnore
    @Override
    public Date getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    private Date deletedAt;

    @QueryIgnore
    @Override
    public Date getDeletedAt() {
        return deletedAt;
    }

    @Override
    public void setDeletedAt(Date deletedAt) {
        this.deletedAt = deletedAt;
    }

}
