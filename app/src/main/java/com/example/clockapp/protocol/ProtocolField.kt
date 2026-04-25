package com.example.clockapp.protocol

import java.nio.ByteBuffer

sealed class ProtocolField {
    abstract fun serialize(): ByteArray

    data class UnixTime(val timestamp: UInt) : ProtocolField() {
        override fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(5)
            buf.put(ProtocolConstants.TAG_UNIX_TIME.toByte())
            buf.putInt(timestamp.toInt())
            return buf.array()
        }
    }

    data class Vpn(val connected: Boolean) : ProtocolField() {
        override fun serialize(): ByteArray {
            return byteArrayOf(
                ProtocolConstants.TAG_VPN.toByte(),
                if (connected) 1 else 0
            )
        }
    }

    data class Playback(val isPlaying: Boolean) : ProtocolField() {
        override fun serialize(): ByteArray {
            return byteArrayOf(
                ProtocolConstants.TAG_PLAYBACK.toByte(),
                if (isPlaying) 1 else 0
            )
        }
    }

    data class Artist(val name: String) : ProtocolField() {
        override fun serialize(): ByteArray {
            val bytes = name.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            return byteArrayOf(ProtocolConstants.TAG_ARTIST.toByte()) +
                    byteArrayOf((len shr 8).toByte(), len.toByte()) +
                    bytes
        }
    }

    data class Track(val name: String) : ProtocolField() {
        override fun serialize(): ByteArray {
            val bytes = name.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            return byteArrayOf(ProtocolConstants.TAG_TRACK.toByte()) +
                    byteArrayOf((len shr 8).toByte(), len.toByte()) +
                    bytes
        }
    }
}