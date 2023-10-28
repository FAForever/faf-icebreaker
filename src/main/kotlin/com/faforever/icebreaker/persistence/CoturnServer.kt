package com.faforever.icebreaker.persistence

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.inject.Singleton
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "coturn_servers")
data class CoturnServer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long,

    val region: String,

    val host: String,

    val port: Int,

    val presharedKey: String,

    val contactEmail: String,

    val active: Boolean,

) : PanacheEntityBase

@Singleton
class CoturnServerRepository : PanacheRepository<CoturnServer> {
    fun findActive() = find("active").list()
}
