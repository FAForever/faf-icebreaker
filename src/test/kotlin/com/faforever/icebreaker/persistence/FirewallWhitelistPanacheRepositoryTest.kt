package com.faforever.icebreaker.persistence

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock

@QuarkusTest
class FirewallWhitelistPanacheRepositoryTest {

    @Inject
    lateinit var repository: FirewallWhitelistPanacheRepository

    @Inject
    lateinit var clock: Clock

    @BeforeEach
    fun setup() {
        repository.deleteAll()
    }

    @Test
    fun `persistOrGet creates new entry when none exists`() {
        val entity = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        assertThat(entity.userId).isEqualTo(123L)
        assertThat(entity.sessionId).isEqualTo("game/200")
        assertThat(entity.allowedIp).isEqualTo("1.2.3.4")
        assertThat(entity.deletedAt).isNull()

        val allActive = repository.getAllActive()
        assertThat(allActive).hasSize(1)
        assertThat(allActive[0].id).isEqualTo(entity.id)
    }

    @Test
    fun `persistOrGet returns existing entry when duplicate session and user`() {
        val firstEntity = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        val secondEntity = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.5", // Different IP, same user/session
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        assertThat(secondEntity.id).isEqualTo(firstEntity.id)
        assertThat(repository.getAllActive()).hasSize(1)
    }

    @Test
    fun `persistOrGet allows different users in same session`() {
        val otherUser = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        val actual = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 456L,
                sessionId = "game/200",
                allowedIp = "1.2.3.5",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        assertThat(actual.id).isNotEqualTo(otherUser.id)
        assertThat(repository.getAllActive()).hasSize(2)
    }

    @Test
    fun `persistOrGet allows same user in different sessions`() {
        val otherGame = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        val result = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/201",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        assertThat(result.id).isNotEqualTo(otherGame.id)
        assertThat(repository.getAllActive()).hasSize(2)
    }

    @Test
    fun `persistOrGet creates new entry after previous one was deleted`() {
        val old = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )
        repository.markSessionUserAsDeleted("game/200", 123L)
        assertThat(repository.getAllActive()).hasSize(0)

        val new = repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = 123L,
                sessionId = "game/200",
                allowedIp = "1.2.3.4",
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )

        assertThat(new.id).isNotEqualTo(old.id)
        assertThat(repository.getAllActive()).hasSize(1)
    }
}
