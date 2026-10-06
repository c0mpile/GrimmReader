package com.c0mpile.grimmreader.api.grimmory

import com.c0mpile.grimmreader.api.grimmory.ServerVersion.Support
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthcheckTest {
    @Test fun recognisesGrimmory() =
        assertEquals(
            HealthcheckResult.Grimmory("v3.5.0"),
            HealthcheckClassifier.classify(200, "application/json", Fixtures.read("healthcheck.json")),
        )

    @Test fun htmlIsAGatewayLoginPage() {
        assertEquals(HealthcheckResult.LoginPage, HealthcheckClassifier.classify(200, "text/html", Fixtures.read("login_page.html")))
        assertEquals(HealthcheckResult.LoginPage, HealthcheckClassifier.classify(401, null, Fixtures.read("login_page.html")))
    }

    @Test fun otherJsonIsNotGrimmory() =
        assertEquals(HealthcheckResult.NotGrimmory, HealthcheckClassifier.classify(200, "application/json", "{\"ok\":true}"))

    @Test fun httpErrors() = assertEquals(HealthcheckResult.HttpError(502), HealthcheckClassifier.classify(502, "application/json", "{}"))

    @Test fun versionSupport() {
        assertEquals(Support.Supported, ServerVersion.support("v3.5.0"))
        assertEquals(Support.Supported, ServerVersion.support("3.5.7"))
        assertEquals(Support.Untested, ServerVersion.support("v3.6.0"))
        assertEquals(Support.TooOld, ServerVersion.support("v3.4.9"))
        assertEquals(Support.Unknown, ServerVersion.support("nightly"))
        assertEquals(Support.Unknown, ServerVersion.support(null))
    }
}
