package com.faforever.icebreaker.web

import com.faforever.icebreaker.persistence.TurnServerEntity
import com.faforever.icebreaker.persistence.TurnServerRepository
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.Matchers.emptyString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionControllerTest {

    @Inject
    lateinit var turnServerRepository: TurnServerRepository

    val gameId = 100L
    val testJwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIiwiZXh0Ijp7InJvbGVzIjpbIlVTRVIiXSwiZ2FtZUlkIjoxMDB9LCJzY3AiOlsibG9iYnkiXSwiaXNzIjoiaHR0cHM6Ly9pY2UuZmFmb3JldmVyLmNvbSIsImF1ZCI6Imh0dHBzOi8vaWNlLmZhZm9yZXZlci5jb20iLCJleHAiOjIwMDAwMDAwMDAsImlhdCI6MTc0MTAwMDAwMCwianRpIjoiMDE5YjBmMDYtOGJlYi00NzEyLWFiNWUtNGUyNmVjMTM0YjFlIn0.CHEtH0I-BacvjIc_a8ZSKcXMmRZqObGIqScs8BNbZrcje9GVvnTeJEkOxh3Lpo0C1Cm8_x_YQ-zilMTmVu87ZH31_FRYvJuaU9gjo3izmHcncWmSOpjg2n8BtkPXcnggdxM5DW7bPUytkgPGhvFUbeTNRw0Lv1Atb9L2NcW33jhQ-jz-3Ev0fVfgAzJMxrhDCpoCw4QMk6doEIbmJ0Egl1-9AHyr3jd1PXMQAI2K3dX2v0hUmOJ2MxClukUFXkXRp76ZJ9L594YU1gLlIprcuPtRQCIvgJ_gD2Cd6iPQHAUFFvNFmpyLVDU3fgrznWIRkcu2CWSlybhFHCvx5Eldhg"

    /** Deletes existing TURN servers and inserts test server data. */
    @BeforeAll
    @Transactional
    fun insertTestData() {
        turnServerRepository.deleteAll()
        turnServerRepository.persist(
            TurnServerEntity(
                id = 0L, // Will be auto-generated
                region = "test-region",
                host = "turn.test.example.com",
                stunPort = 1001,
                turnUdpPort = 1002,
                turnTcpPort = 1003,
                turnsTcpPort = 1004,
                presharedKey = "test-preshared-key-123",
                contactEmail = "test@example.com",
                active = true,
            ),
        )
        turnServerRepository.persist(
            TurnServerEntity(
                id = 0L, // Will be auto-generated
                region = "test-region-2",
                host = "turn2.test.example.com",
                stunPort = 2001,
                turnUdpPort = 2002,
                turnTcpPort = 2003,
                turnsTcpPort = 2004,
                presharedKey = "test-preshared-key-456",
                contactEmail = "test2@example.com",
                active = true,
            ),
        )
        turnServerRepository.persist(
            TurnServerEntity(
                id = 0L, // Will be auto-generated
                region = "disabled-region",
                host = "turn.disabled.example.com",
                stunPort = 3001,
                turnUdpPort = 3002,
                turnTcpPort = 3003,
                turnsTcpPort = 3004,
                presharedKey = "disabled-key",
                contactEmail = "disabled@example.com",
                active = false,
            ),
        )
    }

    @Test
    fun `Unauthenticated GET session game endpoint returns 401 response`() {
        given()
            .`when`().get("/session/game/$gameId")
            .then()
            .statusCode(401)
    }

    @Test
    fun `Authenticated GET session game endpoint returns active servers`() {
        given()
            .header("Authorization", "Bearer $testJwt")
            .`when`().get("/session/game/$gameId")
            .then()
            .statusCode(200)
            .body("id", equalTo("100"))
            // Two of the servers are active
            .body("servers.size()", equalTo(2))
            .body("servers[0].id", not(emptyString()))
            .body("servers[0].urls.size()", greaterThan(0))
            .body("servers[0].username", not(emptyString()))
            .body("servers[0].credential", not(emptyString()))
    }
}
