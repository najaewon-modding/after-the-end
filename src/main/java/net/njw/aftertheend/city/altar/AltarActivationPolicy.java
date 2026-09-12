package net.njw.aftertheend.city.altar;

final class AltarActivationPolicy {
    private AltarActivationPolicy() { }

    static boolean canActivate(int activatedCount, int maxActivatedAltars, int unlockedCityCount, int maxCityCount) {
        if (activatedCount >= maxActivatedAltars) return false;
        return activatedCount > 0 || unlockedCityCount < maxCityCount;
    }

    static boolean unlocksCity(int activatedCount, int maxActivatedAltars, int unlockedCityCount, int maxCityCount) {
        return activatedCount == 0 && canActivate(activatedCount, maxActivatedAltars, unlockedCityCount, maxCityCount);
    }
}
