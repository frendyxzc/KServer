package vip.frendy

import freemarker.cache.ClassTemplateLoader
import freemarker.cache.FileTemplateLoader
import freemarker.cache.MultiTemplateLoader
import kotlinx.coroutines.experimental.channels.consumeEach
import org.jetbrains.ktor.netty.*
import org.jetbrains.ktor.routing.*
import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.response.*
import org.jetbrains.ktor.application.install
import org.jetbrains.ktor.content.defaultResource
import org.jetbrains.ktor.content.resources
import org.jetbrains.ktor.content.static
import org.jetbrains.ktor.features.CallLogging
import org.jetbrains.ktor.features.DefaultHeaders
import org.jetbrains.ktor.freemarker.FreeMarker
import org.jetbrains.ktor.freemarker.FreeMarkerContent
import org.jetbrains.ktor.sessions.*
import org.jetbrains.ktor.util.nextNonce
import org.jetbrains.ktor.websocket.*
import vip.frendy.chat.ChatServer
import vip.frendy.chat.ChatSession
import java.io.File
import java.time.Duration


private val mChatServer = ChatServer()

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
    install(WebSockets) {
        pingPeriod = Duration.ofMinutes(1)
    }


    install(Routing) {
        get("/hello") {
            val name = call.request.queryParameters["name"] ?: "anonymous"
            val model = mapOf<String, Any>("name" to name)
            call.respond(FreeMarkerContent("hello.ftl", model, etag = ""))
        }

        install(Sessions) {
            cookie<ChatSession>("CHAT_SESSION")
        }
        intercept(ApplicationCallPipeline.Infrastructure) {
            if (call.sessions.get<ChatSession>() == null) {
                call.sessions.set(ChatSession(nextNonce()))
            }
        }
        webSocket("/ws") {
            val session = call.sessions.get<ChatSession>()
            if (session == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session"))
                return@webSocket
            }

            mChatServer.memberJoin(session.id, this)

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        mChatServer.receivedMessage(session.id, frame.readText())
                    }
                }
            } finally {
                mChatServer.memberLeft(session.id, this)
            }
        }
        static {
            defaultResource("index.html", "web")
            resources("web")
        }
    }
}

fun main(args: Array<String>) {
    val applicationEnvironment = commandLineEnvironment(args)
    NettyApplicationHost(applicationEnvironment).start()
}