package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionServiceTest {
    @Test fun parses_api_site_and_lives() {
        val payload = """
            {
              "api_site": {
                "dyttzy": {"key":"dyttzy","name":"电影天堂","api":"https://a.example/api","detail":"https://a.example/detail","from":"custom"},
                "heimuer": {"name":"黑木耳","api":"https://b.example/api"}
              },
              "lives": {
                "iptv1": {"key":"iptv1","name":"IPTV1","url":"https://l.example/iptv1.m3u","ua":"ua1","epg":"https://epg.example","from":"custom"}
              }
            }
        """.trimIndent()
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(2, parsed.searchResources.size)
        assertEquals(1, parsed.liveSources.size)
        // 缺 "key" 时用 map entry key 兜底
        val heimuer = parsed.searchResources.first { it.name == "黑木耳" }
        assertEquals("heimuer", heimuer.key)
    }

    @Test fun lone_api_site_is_accepted() {
        val payload = """{"api_site":{"a":{"name":"A","api":"https://x"}}}"""
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(1, parsed.searchResources.size)
        assertEquals(0, parsed.liveSources.size)
    }

    @Test fun lone_lives_is_accepted() {
        val payload = """{"lives":{"l":{"name":"L","url":"https://x"}}}"""
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(0, parsed.searchResources.size)
        assertEquals(1, parsed.liveSources.size)
    }

    @Test fun empty_payload_throws() {
        try {
            SubscriptionService.parseSubscriptionJson("""{"api_site":{},"lives":{}}""")
            fail("应当抛 SubscriptionException")
        } catch (e: SubscriptionService.SubscriptionException) {
            assertEquals("订阅内容无可用源", e.message)
        }
    }

    @Test fun malformed_json_throws() {
        try {
            SubscriptionService.parseSubscriptionJson("not json")
            fail("应当抛 SubscriptionException")
        } catch (_: SubscriptionService.SubscriptionException) {}
    }

    @Test fun api_site_as_array_is_skipped_not_thrown() {
        // api_site 不是对象（这里是数组）：整段降级为空，不抛 IllegalArgumentException
        val payload = """{"api_site":["a","b"],"lives":{"l":{"name":"L","url":"https://x"}}}"""
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(0, parsed.searchResources.size)
        assertEquals(1, parsed.liveSources.size)
    }

    @Test fun bad_entry_in_api_site_is_skipped() {
        // 一个条目的 name 字段被填成嵌套对象：该条目被跳过，其它正常解析
        val payload = """
            {
              "api_site": {
                "good": {"name":"G","api":"https://g"},
                "bad":  {"name":{"lang":"zh"},"api":"https://b"}
              }
            }
        """.trimIndent()
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(1, parsed.searchResources.size)
        assertEquals("G", parsed.searchResources.first().name)
    }

    @Test fun lives_as_string_is_skipped_not_thrown() {
        val payload = """{"api_site":{"a":{"name":"A","api":"https://x"}},"lives":"flat_string"}"""
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(1, parsed.searchResources.size)
        assertEquals(0, parsed.liveSources.size)
    }

    @Test fun parses_tvbox_sites_array() {
        val payload = """
            {
              "sites": [
                {"key": "site1", "name": "TVBox站点1", "api": "https://api1.com/provide/vod"},
                {"key": "site2", "name": "TVBox站点2", "api": "https://api2.com/provide/vod"}
              ],
              "lives": [
                {"key": "live1", "name": "央视高清", "url": "https://live.com/cctv.m3u8"}
              ]
            }
        """.trimIndent()
        val parsed = SubscriptionService.parseSubscriptionJson(payload)
        assertEquals(2, parsed.searchResources.size)
        assertEquals(1, parsed.liveSources.size)
        assertEquals("TVBox站点1", parsed.searchResources[0].name)
        assertEquals("央视高清", parsed.liveSources[0].name)
    }

    @Test fun parses_local_file_content() {
        val tempFile = java.io.File.createTempFile("test_sub_", ".json")
        try {
            tempFile.writeText("""
                {
                  "api_site": {
                    "local1": {"name": "本地源1", "api": "https://local.api/vod"}
                  }
                }
            """.trimIndent())
            val parsed = SubscriptionService.fetchAndParse(tempFile.absolutePath)
            assertEquals(1, parsed.searchResources.size)
            assertEquals("本地源1", parsed.searchResources[0].name)

            val parsedFileUri = SubscriptionService.fetchAndParse("file://${tempFile.absolutePath}")
            assertEquals(1, parsedFileUri.searchResources.size)
            assertEquals("本地源1", parsedFileUri.searchResources[0].name)
        } finally {
            tempFile.delete()
        }
    }
}
