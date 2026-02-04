package com.Chris__.realm_ruler.modes.ctf;

import java.util.Locale;

/**
 * Pure CTF rules + IDs.
 * No world writes, no inventory writes, no plugin references.
 */
public final class CtfRules {

    private CtfRules() {}

    // Stand block IDs
    public static final String STAND_EMPTY  = "Flag_Stand";
    public static final String STAND_RED    = "Flag_Stand_Red";
    public static final String STAND_BLUE   = "Flag_Stand_Blue";
    public static final String STAND_WHITE  = "Flag_Stand_White";
    public static final String STAND_YELLOW = "Flag_Stand_Yellow";

    // Flag item IDs
    public static final String FLAG_RED    = "Realm_Ruler_Flag_Red";
    public static final String FLAG_BLUE   = "Realm_Ruler_Flag_Blue";
    public static final String FLAG_WHITE  = "Realm_Ruler_Flag_White";
    public static final String FLAG_YELLOW = "Realm_Ruler_Flag_Yellow";

    public static boolean isStandId(String id) {
        return STAND_EMPTY.equals(id)
                || STAND_RED.equals(id)
                || STAND_BLUE.equals(id)
                || STAND_WHITE.equals(id)
                || STAND_YELLOW.equals(id);
    }

    public static boolean isCustomFlagId(String itemId) {
        if (itemId == null) return false;
        return itemId.equals(FLAG_RED)
                || itemId.equals(FLAG_BLUE)
                || itemId.equals(FLAG_WHITE)
                || itemId.equals(FLAG_YELLOW);
    }

    public static boolean isEmptyHandId(String itemId) {
        if (itemId == null) return true;
        String s = itemId.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.equals("<empty>") || s.equals("air") || s.endsWith(":air");
    }

    /**
     * Select the desired stand variant based on current rules.
     * NOTE: phase1ToggleBlueOnly is passed in so rules remain "pure".
     */
    public static String selectDesiredStand(String clickedStandId, String itemInHandId, boolean phase1ToggleBlueOnly) {
        if (phase1ToggleBlueOnly) {
            return STAND_BLUE.equals(clickedStandId) ? STAND_EMPTY : STAND_BLUE;
        }

        if (FLAG_RED.equals(itemInHandId)) return STAND_RED;
        if (FLAG_BLUE.equals(itemInHandId)) return STAND_BLUE;
        if (FLAG_WHITE.equals(itemInHandId)) return STAND_WHITE;
        if (FLAG_YELLOW.equals(itemInHandId)) return STAND_YELLOW;

        return STAND_EMPTY;
    }
}
