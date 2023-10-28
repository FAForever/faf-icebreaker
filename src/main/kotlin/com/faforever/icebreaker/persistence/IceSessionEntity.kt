package com.faforever.icebreaker.persistence

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.inject.Singleton
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ice_sessions")
data class IceSessionEntity(
    @Id
    val id: String = UUID.randomUUID().toString(),

    val gameId: Long,

    val createdAt: LocalDateTime,

) : PanacheEntityBase

@Singleton
class IceSessionRepository : PanacheRepository<IceSessionEntity> {
    fun findByGameId(gameId: Long) =
        find("gameId = ?1", gameId).firstResult()
}
