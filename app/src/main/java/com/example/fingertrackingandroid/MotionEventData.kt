package com.example.fingertrackingandroid

data class MotionEventData(
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val action: Int
)