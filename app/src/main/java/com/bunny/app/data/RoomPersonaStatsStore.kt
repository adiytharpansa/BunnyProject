package com.bunny.app.data

import com.bunny.engine.lontar.PersonaHealthState
import com.bunny.engine.lontar.PersonaStatSnapshot
import com.bunny.engine.lontar.PersonaStatsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Adaptive Ladder's memory, Room-backed (Lontar Hidup). Only
 * aggregate counts are kept — persona, network class, outcome counts,
 * timestamps. No video ids, no network names, no addresses, and the
 * data never leaves the device.
 */
@Singleton
class RoomPersonaStatsStore @Inject constructor(
    private val dao: PersonaStatDao,
) : PersonaStatsStore {

    private val mutex = Mutex()

    override suspend fun record(
        persona: String,
        networkClass: String,
        state: PersonaHealthState,
    ) {
        // An unknown verdict is not evidence; it leaves no trace.
        if (state == PersonaHealthState.TAK_DIKETAHUI) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            val cur = dao.get(persona, networkClass)
                ?: PersonaStatEntity(persona = persona, networkClass = networkClass)
            dao.upsert(
                when (state) {
                    PersonaHealthState.SEHAT -> cur.copy(
                        sehat = cur.sehat + 1,
                        lastCheckEpochMs = now,
                        lastSuccessEpochMs = now,
                    )
                    PersonaHealthState.TERGERBANG_GVS -> cur.copy(
                        tergerbang = cur.tergerbang + 1,
                        lastCheckEpochMs = now,
                    )
                    PersonaHealthState.DITOLAK_PLAYER -> cur.copy(
                        ditolak = cur.ditolak + 1,
                        lastCheckEpochMs = now,
                    )
                    else -> cur.copy(
                        gagal = cur.gagal + 1,
                        lastCheckEpochMs = now,
                    )
                },
            )
        }
    }

    override suspend fun snapshot(
        networkClass: String,
    ): Map<String, PersonaStatSnapshot> =
        dao.forNetwork(networkClass).associate { entity ->
            entity.persona to PersonaStatSnapshot(
                sehat = entity.sehat,
                tergerbang = entity.tergerbang,
                ditolak = entity.ditolak,
                gagal = entity.gagal,
            )
        }
}
