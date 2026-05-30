package gg.grounds.notifications.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
class NotificationResourceTest {
    @Test
    fun statusReturnsOk() {
        given()
            .`when`()
            .get("/notifications/status")
            .then()
            .statusCode(200)
            .body("status", equalTo("ok"))
    }
}
