package com.pluxy.server.metadata

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMediaPolicyTest {
    @Test fun `stills match the exact episode and missing paths stay absent`() {
        val season = org.json.JSONObject("""{"episodes":[
            {"episode_number":1,"still_path":"/one.jpg"},
            {"episode_number":2,"still_path":"/two.jpg"},
            {"episode_number":3,"still_path":null},
            {"episode_number":4,"still_path":"https://unknown.invalid/image.jpg"}
        ]}""")
        val images = TmdbMediaPolicy.stills(season)
        assertEquals("https://image.tmdb.org/t/p/w780/one.jpg", images[1])
        assertEquals("https://image.tmdb.org/t/p/w780/two.jpg", images[2])
        assertEquals(null, images[3])
        assertEquals(null, images[4])
    }
    @Test fun `only trailers and teasers are selected with French and official priority`() {
        val videos = JSONArray("""[
            {"site":"YouTube","type":"Featurette","key":"behindscene","iso_639_1":"fr","official":true},
            {"site":"YouTube","type":"Trailer","key":"english0001","iso_639_1":"en","official":true},
            {"site":"YouTube","type":"Trailer","key":"francais001","iso_639_1":"fr"},
            {"site":"YouTube","type":"Trailer","key":"francais002","iso_639_1":"fr","official":true}
        ]""")
        assertEquals("francais002", TmdbMediaPolicy.trailer(videos))
        assertEquals(null, TmdbMediaPolicy.trailer(JSONArray("""[{"site":"YouTube","type":"Trailer","key":"invalid/key"}]""")))
        assertEquals(null, TmdbMediaPolicy.trailer(null))
    }
}
