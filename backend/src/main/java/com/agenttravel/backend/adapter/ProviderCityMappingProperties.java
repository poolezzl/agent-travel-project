package com.agenttravel.backend.adapter;

public final class ProviderCityMappingProperties {

    private String amapAdcode;
    private String amapCitycode;
    private String qweatherLocation;

    public ProviderCityMappingProperties() {
    }

    public ProviderCityMappingProperties(
            String amapAdcode, String amapCitycode, String qweatherLocation) {
        this.amapAdcode = amapAdcode;
        this.amapCitycode = amapCitycode;
        this.qweatherLocation = qweatherLocation;
    }

    public String getAmapAdcode() {
        return amapAdcode;
    }

    public void setAmapAdcode(String amapAdcode) {
        this.amapAdcode = amapAdcode;
    }

    public String getAmapCitycode() {
        return amapCitycode;
    }

    public void setAmapCitycode(String amapCitycode) {
        this.amapCitycode = amapCitycode;
    }

    public String getQweatherLocation() {
        return qweatherLocation;
    }

    public void setQweatherLocation(String qweatherLocation) {
        this.qweatherLocation = qweatherLocation;
    }
}
