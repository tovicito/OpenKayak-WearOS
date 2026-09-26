package com.openkayak.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val durationSeconds: Long,
    val distanceMeters: Float,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val totalStrokes: Int = 0,
    val avgStrokeRateSpm: Int = 0,
    val estimatedCalories: Int,
    val routeGpsJson: String,
    val heartRateJson: String = "[]"
)
