package cloud.trotter.census.server.ops

import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/** Operator sign-in with no scripts, external resources, or reflected credentials. */
object LoginHtml {
    fun render(failed: Boolean = false): String = "<!DOCTYPE html>" + createHTML().html {
        head {
            meta { charset = "utf-8" }
            meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
            title { +"census · sign in" }
            // Only a constant stylesheet literal is passed to kotlinx.html's raw style renderer.
            style { unsafe { raw("body{font-family:system-ui;margin:2rem;max-width:30rem}label{display:block;margin:1rem 0}input,button{font:inherit;padding:.5rem}input{display:block;box-sizing:border-box;width:100%}") } }
        }
        body {
            h1 { +"Census sign in" }
            if (failed) p { +"Sign-in failed." }
            form(action = "/ops/login", method = FormMethod.post) {
                attributes["autocomplete"] = "off"
                label {
                    +"Operator token"
                    input(type = InputType.password, name = "token") {
                        attributes["autocomplete"] = "off"
                        required = true
                        maxLength = "256"
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
