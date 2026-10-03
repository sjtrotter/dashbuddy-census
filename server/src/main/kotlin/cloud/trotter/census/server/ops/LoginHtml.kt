package cloud.trotter.census.server.ops

import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p

/** Operator sign-in with no scripts, external resources, or reflected credentials. */
object LoginHtml {
    fun render(failed: Boolean = false): String = opsPage(
        pageTitle = "census · sign in",
        logout = false,
        headerContent = { h1 { +"Census sign in" } },
    ) {
        div("panel login") {
            if (failed) p { +"Sign-in failed." }
            form(action = "/ops/login", method = FormMethod.post) {
                attributes["autocomplete"] = "off"
                label {
                    +"Operator token"
                    input(type = InputType.password, name = "token") {
                        attributes["autocomplete"] = "off"
                        required = true
                        maxLength = cloud.trotter.census.server.routes.MAX_TOKEN_LENGTH.toString()
                    }
                }
                label {
                    +"Authenticator code"
                    input(type = InputType.text, name = "code") {
                        attributes["inputmode"] = "numeric"
                        pattern = "[0-9]{6}"
                        attributes["autocomplete"] = "one-time-code"
                        required = true
                        maxLength = "6"
                    }
                }
                button(type = ButtonType.submit) { +"Sign in" }
            }
        }
    }
}
