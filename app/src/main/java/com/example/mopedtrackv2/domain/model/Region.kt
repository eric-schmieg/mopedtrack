package com.example.mopedtrackv2.domain.model

data class Region(
    val regionId: String,
    val name: String,
    val adminLevel: Int,
    val osmRelationId: Long,
    val boundingBox: String,
    val lastUpdatedTimestamp: Long
)
