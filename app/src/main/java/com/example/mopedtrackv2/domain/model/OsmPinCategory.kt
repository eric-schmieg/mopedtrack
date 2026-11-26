package com.example.mopedtrackv2.domain.model

enum class OsmPinCategory(val label: String) {
    MISSING_ROAD("Missing Road"),
    ROAD_CLOSED_REMOVED("Road Closed / Removed"),
    PRIVATE_GATED("Private / Gated"),
    WRONG_NAME("Wrong Name"),
    DIVIDED_GEOMETRY_ISSUE("Divided / Geometry Issue");

    companion object {
        fun fromCategoryName(name: String): OsmPinCategory? {
            return entries.firstOrNull { it.name == name }
        }
    }
}
