package com.flansmod.recoded.aircraft

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a plane or helicopter flies: the `flight` section of a vehicle definition of type `plane` or `helicopter`.
 * Top speed, acceleration, drag, braking and turn rate come from the vehicle definition itself (and its upgrades).
 * Speeds are in blocks per tick, angles in degrees.
 */
@Serializable
data class FlightDefinition(
    /** Planes: airspeed at which the wings carry the plane (take-off speed; slower than this it stalls and sinks). */
    @SerialName("lift_speed") val liftSpeed: Double = 0.7,
    /** Planes: degrees per tick the nose pitches towards where the pilot looks (at full lift). */
    @SerialName("pitch_speed") val pitchSpeed: Float = 2.5f,
    /** Planes: steepest climb or dive the nose follows. */
    @SerialName("max_pitch") val maxPitch: Float = 60f,
    /** Throttle change per tick while forward/back is held (planes keep their throttle setting). */
    @SerialName("throttle_rate") val throttleRate: Float = 0.02f,
    /** Bank (roll) per degree of turn per tick: visual, and the hit boxes and seats follow it. */
    val bank: Float = 12f,
    @SerialName("max_bank") val maxBank: Float = 70f,
    /** Helicopters: vertical speed while climbing (jump) or descending (sprint key). */
    @SerialName("climb_speed") val climbSpeed: Double = 0.25,
    /** Helicopters: nose-down tilt at full forward speed (nose up when flying backwards). */
    val tilt: Float = 15f,
    /** Running into the ground or a wall faster than this (blocks per tick lost on impact) damages the hull... */
    @SerialName("crash_speed") val crashSpeed: Double = 0.45,
    /** ...by this much per block per tick above [crashSpeed]. */
    @SerialName("crash_damage") val crashDamage: Float = 120f,
)
