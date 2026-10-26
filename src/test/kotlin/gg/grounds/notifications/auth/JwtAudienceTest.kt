package gg.grounds.notifications.auth

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.smallrye.jwt.build.Jwt
import org.junit.jupiter.api.Test

private const val ISSUER = "https://issuer.test/realms/grounds"

private val signedTokenConfig =
    mapOf(
        "mp.jwt.verify.issuer" to ISSUER,
        "mp.jwt.verify.publickey.location" to "jwt/publicKey.pem",
        "smallrye.jwt.sign.key.location" to "jwt/privateKey.pem",
    )

private fun token(vararg audiences: String): String {
    val builder = Jwt.issuer(ISSUER).subject("audience-test-user")
    return (if (audiences.isEmpty()) builder else builder.audience(audiences.toSet())).sign()
}

class RequiredAudienceProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        signedTokenConfig + ("mp.jwt.verify.audiences" to "service-notifications,other-api")
}

class DefaultAudienceProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = signedTokenConfig
}

@QuarkusTest
@TestProfile(RequiredAudienceProfile::class)
class JwtAudienceRequiredTest {
    @Test
    fun acceptsTokenCarryingAConfiguredAudience() {
        given()
            .auth()
            .oauth2(token("account", "service-notifications"))
            .get("/v1/notifications")
            .then()
            .statusCode(200)
    }

    @Test
    fun rejectsTokenForAnotherAudience() {
        given()
            .auth()
            .oauth2(token("grounds-forge-api"))
            .get("/v1/notifications")
            .then()
            .statusCode(401)
    }

    @Test
    fun rejectsTokenWithoutAudience() {
        given().auth().oauth2(token()).get("/v1/notifications").then().statusCode(401)
    }
}

@QuarkusTest
@TestProfile(DefaultAudienceProfile::class)
class JwtAudienceDefaultTest {
    @Test
    fun acceptsTokenWithoutAudienceWhenNoneIsConfigured() {
        given().auth().oauth2(token()).get("/v1/notifications").then().statusCode(200)
    }
}
