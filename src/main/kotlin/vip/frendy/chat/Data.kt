package vip.frendy.chat

import org.jetbrains.ktor.websocket.WebSocketSession
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class ChatSession(
        val id: String
)

data class ChatRoom(
        val id: String,
        val members: ConcurrentHashMap<String, MutableList<WebSocketSession>> = ConcurrentHashMap<String, MutableList<WebSocketSession>>(),
        val lastMessages: LinkedList<String> = LinkedList<String>()
)