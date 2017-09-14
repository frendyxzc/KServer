package vip.frendy

import freemarker.cache.ClassTemplateLoader
import freemarker.cache.FileTemplateLoader
import freemarker.cache.MultiTemplateLoader
import org.jetbrains.ktor.netty.*
import org.jetbrains.ktor.routing.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.response.*
import org.jetbrains.ktor.application.install
import org.jetbrains.ktor.features.DefaultHeaders
import org.jetbrains.ktor.freemarker.FreeMarker
import org.jetbrains.ktor.freemarker.FreeMarkerContent
import org.jetbrains.ktor.logging.CallLogging
import java.io.File

fun Application.module() {
    install(DefaultHeaders)
    install(CallLogging)
    install(FreeMarker, {
        templateLoader = MultiTemplateLoader(
                arrayOf(
                        FileTemplateLoader(File("src/main/resources/templates/")),
                        ClassTemplateLoader(Application::class.java.classLoader, "templates")
                )
        )
    })

    install(Routing) {
        get("/") {
            call.respondText("Hello, world ~ !", org.jetbrains.ktor.http.ContentType.Text.Html)
        }
        get("/hello") {
            val name = call.request.queryParameters["name"] ?: "anonymous"
            val model = mapOf<String, Any>("name" to name)
            call.respond(FreeMarkerContent("hello.ftl", model, etag = ""))
        }
    }
}

fun main(args: Array<String>) {
    val applicationEnvironment = commandLineEnvironment(args)
    NettyApplicationHost(applicationEnvironment).start()
}