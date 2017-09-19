package vip.frendy.chat

import kotlinx.coroutines.experimental.channels.ClosedSendChannelException
import org.jetbrains.ktor.util.buildByteBuffer
import org.jetbrains.ktor.websocket.CloseReason
import org.jetbrains.ktor.websocket.Frame
import org.jetbrains.ktor.websocket.WebSocketSession
import org.jetbrains.ktor.websocket.close
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ChatRoomServer {
    val usersCounter = AtomicInteger()
    val memberNames = ConcurrentHashMap<String, String>()
    val rooms = ConcurrentHashMap<String, ChatRoom>()

    suspend fun memberJoin(member: String, socket: WebSocketSession, roomId: String?) {
        if(roomId == null) return

        val name = memberNames.computeIfAbsent(member) { "user${usersCounter.incrementAndGet()}" }
        val room = rooms.computeIfAbsent(roomId) { ChatRoom(roomId, ConcurrentHashMap<String, MutableList<WebSocketSession>>()) }
        val list = room.members.computeIfAbsent(member) { CopyOnWriteArrayList<WebSocketSession>() }
        list.add(socket)

        if (list.size == 1) {
            broadcast("server", "Room $roomId, Member joined: $name.", roomId)
        }

        val messages = synchronized(room.lastMessages) { room.lastMessages.toList() }
        for (message in messages) {
            socket.send(Frame.Text(message))
        }
    }

    suspend fun memberRenamed(member: String, to: String, roomId: String) {
        val oldName = memberNames.put(member, to) ?: member
        broadcast("server", "Member renamed from $oldName to $to", roomId)
    }

    suspend fun memberLeft(member: String, socket: WebSocketSession, roomId: String?) {
        val room = rooms[roomId] ?: return
        val connections = room.members[member]
        connections?.remove(socket)

        if (connections != null && connections.isEmpty()) {
            val name = memberNames[member] ?: member
            broadcast("server", "Member left: $name.", roomId!!)
        }
    }

    suspend fun who(sender: String, roomId: String) {
        val room = rooms[roomId] ?: return
        room.members[sender]?.send(Frame.Text(memberNames.values.joinToString(prefix = "[server::who] ")))
    }

    suspend fun help(sender: String, roomId: String) {
        val room = rooms[roomId] ?: return
        room.members[sender]?.send(Frame.Text("[server::help] Possible commands are: /user, /help and /who"))
    }

    suspend fun sendTo(recipient: String, sender: String, message: String, roomId: String) {
        val room = rooms[roomId] ?: return
        room.members[recipient]?.send(Frame.Text("[$sender] $message"))
    }

    suspend fun sendToOne(recipient: String, sender: String, message: String, roomId: String) {
        val room = rooms[roomId] ?: return
        room.members[recipient]?.send(Frame.Text("[from $sender] $message"))
    }

    suspend fun message(sender: String, message: String, roomId: String) {
        val room = rooms[roomId] ?: return
        val name = memberNames[sender] ?: sender
        val formatted = "[$name] $message"

        broadcast(formatted, room)
        synchronized(room.lastMessages) {
            room.lastMessages.add(formatted)
            if (room.lastMessages.size > 100) {
                room.lastMessages.removeFirst()
            }
        }
    }

    suspend fun broadcast(message: String, room: ChatRoom) {
        broadcast(buildByteBuffer {
            putString(message, Charsets.UTF_8)
        }, room.members)
    }

    suspend fun broadcast(sender: String, message: String, roomId: String) {
        val room = rooms[roomId] ?: return
        val name = memberNames[sender] ?: sender
        broadcast("[$name] $message", room)
    }

    suspend fun broadcast(serialized: ByteBuffer, members: ConcurrentHashMap<String, MutableList<WebSocketSession>>) {
        members.values.forEach { socket ->
            socket.send(Frame.Text(true, serialized.duplicate()))
        }
    }

    suspend fun List<WebSocketSession>.send(frame: Frame) {
        forEach {
            try {
                it.send(frame.copy())
            } catch (t: Throwable) {
                try {
                    it.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, ""))
                } catch (ignore: ClosedSendChannelException) {
                    // at some point it will get closed
                }
            }
        }
    }

    suspend fun receivedMessage(id: String, command: String, roomId: String?) {
        if(roomId == null) return

        when {
            command.startsWith("/who") -> who(id, roomId)
            command.startsWith("/user") -> {
                val newName = command.removePrefix("/user").trim()
                when {
                    newName.isEmpty() -> sendTo(id, "server::help", "/user [newName]", roomId)
                    newName.length > 50 -> sendTo(id, "server::help", "new name is too long: 50 characters limit", roomId)
                    else -> memberRenamed(id, newName, roomId)
                }
            }
            command.startsWith("/help") -> help(id, roomId)
            command.startsWith("/") -> {
                val name = command.takeWhile { !it.isWhitespace() }.substring(1)
                val message = if(command.length > name.length + 1) command.substring(name.length + 1) else ""
                if(memberNames.contains(name)) {
                    sendTo(id, memberNames[id]!!, command, roomId)
                    sendToOne(getMemberId(name), memberNames[id]!!, message, roomId)
                } else {
                    sendTo(id, "server::help", "Unknown command ${command.takeWhile { !it.isWhitespace() }}", roomId)
                }
            }
            else -> message(id, command, roomId)
        }
    }

    private fun getMemberId(name: String): String {
        for(member in memberNames) {
            if(member.value.equals(name)) return member.key
        }
        return ""
    }
}