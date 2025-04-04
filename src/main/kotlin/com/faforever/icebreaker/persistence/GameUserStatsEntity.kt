package com.faforever.icebreaker.persistence

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hypersistence.utils.hibernate.type.json.JsonType
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.inject.Singleton
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(name = "game_user_stats")
data class GameUserStatsEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Int? = null,
    val gameId: Long,
    val userId: Long,
    val connectionAttempts: Long = 1,
    val logBytesPushed: Long = 0,
    @Type(JsonType::class)
    val connectivityStatus: ObjectNode = JsonNodeFactory.instance.objectNode(),
    @Column(insertable = false, updatable = false) val createdAt: Instant = Instant.now(),
    @Column(insertable = false, updatable = false) val updatedAt: Instant = Instant.now(),
) : PanacheEntityBase

@Singleton
@Transactional
class GameUserStatsRepository : PanacheRepository<GameUserStatsEntity> {
    fun deleteByGameId(gameId: Long) = delete("gameId = ?1", gameId)

    fun findByGameIdAndUserId(gameId: Long, userId: Long) =
        find("gameId = ?1 and userId = ?2", gameId, userId).firstResult()

    fun incrementConnectionAttempts(gameId: Long, userId: Long) {
        update(
            "connectionAttempts = connectionAttempts + 1 WHERE gameId = ?1 and userId = ?2",
            gameId,
            userId,
        )
    }

    fun incrementLogBytesPushed(gameId: Long, userId: Long, bytesPushed: Int) {
        update(
            "logBytesPushed = logBytesPushed + ?1 WHERE gameId = ?2 and userId = ?3",
            bytesPushed,
            gameId,
            userId,
        )
    }
}
