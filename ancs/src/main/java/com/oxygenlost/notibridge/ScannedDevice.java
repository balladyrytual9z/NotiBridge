package com.oxygenlost.notibridge;

/**
 * Model class for scanned Bluetooth devices
 */
public class ScannedDevice {
    private String name;
    private String address;

    public ScannedDevice(String name, String address) {
        this.name = name;
        this.address = address;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ScannedDevice that = (ScannedDevice) obj;
        return address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }
}
