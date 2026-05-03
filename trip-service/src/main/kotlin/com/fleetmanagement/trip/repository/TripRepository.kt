package com.fleetmanagement.trip.repository

import com.fleetmanagement.proto.trip.Trip
import com.fleetmanagement.proto.trip.TripStatus
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

data class PagedResult<T>(val items: List<T>, val total: Int)

@Repository
class TripRepository {

    private val trips = ConcurrentHashMap<String, Trip>()

    fun save(trip: Trip) {
        trips[trip.id] = trip
    }

    fun findById(id: String): Trip? = trips[id]

    fun findAll(vehicleId: String?, statusFilter: TripStatus, page: Int, pageSize: Int): PagedResult<Trip> {
        val effectivePageSize = if (pageSize <= 0) 20 else pageSize

        val filtered = trips.values
            .filter { vehicleId.isNullOrBlank() || it.vehicleId == vehicleId }
            .filter { statusFilter == TripStatus.TRIP_STATUS_UNSPECIFIED || it.status == statusFilter }
            .sortedByDescending { it.scheduledStart.seconds }

        val total = filtered.size
        val from = page * effectivePageSize
        val items = if (from >= total) emptyList() else filtered.subList(from, minOf(from + effectivePageSize, total))

        return PagedResult(items, total)
    }
}