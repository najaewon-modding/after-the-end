package net.njw.aftertheend.city;

public final class CityTravelAccessPolicy {
    private CityTravelAccessPolicy() { }

    public static boolean canTravelWithoutActivatedAltar(int currentCityIndex, int targetCityIndex) {
        return currentCityIndex >= 0 && targetCityIndex >= 0 && targetCityIndex < currentCityIndex;
    }
}
