package com.faforever.icebreaker.service.hetzner

import io.smallrye.config.ConfigMapping
import java.util.Optional

@ConfigMapping(prefix = "hetzner")
interface HetznerProperties {
    /**
     * ID of the Hetzner cloud firewall that protects our TURN servers.
     */
    fun firewallId(): Optional<String>

    /**
     * The maximum number of IPs that Hetzner allows to be put into each firewall rule.
     * See https://docs.hetzner.com/cloud/firewalls/overview/#limits
     * and https://docs.hetzner.cloud/reference/cloud#firewall-actions-set-rules
     */
    fun maxIpsPerRule(): Int

    /** The API key that should be used as a bearer authorisation token when calling Hetzner APIs.*/
    fun apiKey(): String
}
