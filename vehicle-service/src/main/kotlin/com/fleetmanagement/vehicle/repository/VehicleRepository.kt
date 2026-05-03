package com.fleetmanagement.vehicle.repository

import com.fleetmanagement.proto.vehicle.LocationUpdate
import com.fleetmanagement.proto.vehicle.Vehicle
import com.fleetmanagement.proto.vehicle.VehicleStatus
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

data class PagedResult<T>(val items: List<T>, val total: Int)

@Repository
class VehicleRepository {

    private val vehicles = ConcurrentHashMap<String, Vehicle>()
    // vehicleId -> list of location updates (last 100 kept in memory)
    private val locations = ConcurrentHashMap<String, ArrayDeque<LocationUpdate>>()

    fun save(vehicle: Vehicle) {
        vehicles[vehicle.id] = vehicle
    }

    fun findById(id: String): Vehicle? = vehicles[id]

    fun findAll(statusFilter: VehicleStatus, page: Int, pageSize: Int): PagedResult<Vehicle> {
        val effectivePageSize = if (pageSize <= 0) 20 else pageSize

        val filtered = if (statusFilter == VehicleStatus.VEHICLE_STATUS_UNSPECIFIED) {
            vehicles.values.toList()
        } else {
            vehicles.values.filter { it.status == statusFilter }
        }

        val sorted = filtered.sortedBy { it.id }
        val total = sorted.size
        val from = page * effectivePageSize
        val items = if (from >= total) emptyList() else sorted.subList(from, minOf(from + effectivePageSize, total))

        return PagedResult(items, total)
    }

    fun saveLocation(location: LocationUpdate) {
        val deque = locations.getOrPut(location.vehicleId) { ArrayDeque() }
        deque.addLast(location)
        if (deque.size > 100) deque.removeFirst()
    }

    fun getRecentLocations(vehicleId: String): List<LocationUpdate> =
        locations[vehicleId]?.toList() ?: emptyList()
}