package com.faforever.icebreaker.service.xirsys.geolocation

data class XirsysRegion(
    val apiUrl: String,
    val name: String,
    val city: String,
    override val latitude: Double,
    override val longitude: Double,
) : GeoLocation {
    companion object {
        // listed on https://docs.xirsys.com/?pg=api-intro (manually enriched with geo coordinates)
        val allRegions =
            listOf(
                XirsysRegion("https://ws.xirsys.com", "US West", "San Jose, CA", 37.33939000, -121.89496000),
                XirsysRegion("https://us.xirsys.com", "US East", "Washington, DC", 38.89511000, -77.03637000),
                XirsysRegion("https://es.xirsys.com", "Europe", "Amsterdam", 52.37403000, 4.88969000),
                XirsysRegion("https://bs.xirsys.com", "India", "Bangalore", 12.97194000, 77.59369000),
                XirsysRegion("https://tk.xirsys.com", "Japan", "Tokyo", 35.67619190, 139.65031060),
                XirsysRegion("https://hk.xirsys.com", "China", "Hong Kong", 22.396428, 114.109497),
                XirsysRegion("https://ss.xirsys.com", "Asia", "Singapore", 1.352083, 103.819836),
                XirsysRegion("https://ms.xirsys.com", "Australia", "Sydney", -33.86785, 151.20732),
                XirsysRegion("https://sp.xirsys.com", "Brazil", "São Paulo", -23.5475, -46.63611),
                XirsysRegion("https://to.xirsys.com", "Canada East", "Toronto", 43.70011, -79.4163),
                XirsysRegion("https://jb.xirsys.com", "South Africa", "Johannesburg", -26.20227, 28.04363),
                XirsysRegion("https://fr.xirsys.com", "Germany", "Frankfurt", 50.11552, 8.68417),
            )

        val fallbackRegion = allRegions.last()
    }
}
