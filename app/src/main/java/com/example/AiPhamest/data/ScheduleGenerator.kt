// File: ScheduleGenerator.kt
package com.example.AiPhamest.data

import java.time.*

import java.util.*

/**
 * Generate every dose that should occur on [day] (defaults to *today*),
 * beginning with the user-selected [firstDose] and repeating every
 * [PrescriptionEntity.frequencyHours] until the clock wraps past midnight.
 */
internal object ScheduleGenerator {

    fun forDay(
        p: PrescriptionEntity,
        firstDose: LocalTime,
        day: LocalDate = LocalDate.now()
    ): List<ScheduleEntity> {
        val gapMinutes = (p.frequencyHours * 60).toLong()
        require(gapMinutes > 0) { "frequencyHours must be positive" }
        val out = mutableListOf<ScheduleEntity>()

        var dt = day.atTime(firstDose) // LocalDateTime for precision
        val duration = Duration.ofMinutes(gapMinutes)

        // Keep generating while it's still the same day
        while (dt.toLocalDate() == day) {
            out += mk(p, dt)
            dt = dt.plus(duration)
        }
        return out
    }

    /* -------- new course-length generator -------- */
    fun forCourse(
        p: PrescriptionEntity,
        firstDose: LocalTime,
        startDay: LocalDate = LocalDate.now()
    ): List<ScheduleEntity> {

        val maxDoses = dosesLeft(p) ?: Int.MAX_VALUE      // “infinite” if no pack given
        val gapMinutes = (p.frequencyHours * 60).toLong()
        require(gapMinutes > 0) { "frequencyHours must be positive" }

        val out = mutableListOf<ScheduleEntity>()
        var dt  = startDay.atTime(firstDose)
        val step = Duration.ofMinutes(gapMinutes)

        repeat(maxDoses) {
            out += mk(p, dt)
            dt  = dt.plus(step)
        }
        return out
    }

    /* -------- new helper -------- */
    private fun dosesLeft(p: PrescriptionEntity): Int? {
        val pills = p.packAmount ?: return null      // unknown → open-ended
        val perDose = maxOf(1.0, p.dose)             // avoid div-by-zero
        return kotlin.math.ceil(pills / perDose).toInt()
    }

    /* ------------------------------------------------------------------ */

    private fun mk(p: PrescriptionEntity, dt: LocalDateTime) =
        ScheduleEntity(
            prescriptionId = p.id,
            date       = Date.from(dt.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()),
            hour       = dt.hour,
            minute     = dt.minute,
            dosageNote = "${p.dose} × ${p.strengthUnit}",
            status     = DoseStatus.UPCOMING
        )
}
