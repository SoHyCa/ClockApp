package com.example.clockapp.protocol

data class PacketHeader(
    val flags: Int,
    val msgId: Int,
    val fragOffset: Int,
    val payloadLen: Int
)

data class ParsedMessage(
    val isRequest: Boolean,
    val tags: List<Int>
)

object ProtocolDecoder {
    private const val MAX_MESSAGE_SIZE = 4096
    private const val REASSEMBLY_TIMEOUT_MS = 5000L

    private data class ReassemblyBuffer(
        var msgId: Int = 0,
        var buffer: ByteArray? = null,
        var receivedLen: Int = 0,
        var active: Boolean = false,
        var startTime: Long = 0
    )

    private var reassembly = ReassemblyBuffer()

    private fun resetReassembly() {
        reassembly.buffer = null
        reassembly.active = false
        reassembly.receivedLen = 0
    }

    private fun initReassembly(msgId: Int) {
        resetReassembly()
        reassembly.buffer = ByteArray(MAX_MESSAGE_SIZE)
        reassembly.msgId = msgId
        reassembly.active = true
        reassembly.startTime = System.currentTimeMillis()
    }

    fun decodeHeader(data: ByteArray): PacketHeader {
        return PacketHeader(
            flags = data[0].toInt() and 0xFF,
            msgId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF),
            fragOffset = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF),
            payloadLen = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
        )
    }

    private fun parsePayloadTags(payload: ByteArray, len: Int): List<Int> {
        val tags = mutableListOf<Int>()
        var i = 0
        while (i < len) {
            val tag = payload[i].toInt() and 0xFF
            tags.add(tag)
            when (tag) {
                ProtocolConstants.TAG_UNIX_TIME -> i += 5
                ProtocolConstants.TAG_VPN,
                ProtocolConstants.TAG_PLAYBACK -> i += 2
                ProtocolConstants.TAG_ARTIST,
                ProtocolConstants.TAG_TRACK -> {
                    if (i + 2 < len) {
                        val strLen = ((payload[i + 1].toInt() and 0xFF) shl 8) or
                                (payload[i + 2].toInt() and 0xFF)
                        i += 3 + strLen
                    } else {
                        i += 1
                    }
                }
                ProtocolConstants.REQ_TIME,
                ProtocolConstants.REQ_VPN,
                ProtocolConstants.REQ_PLAYBACK,
                ProtocolConstants.REQ_ARTIST,
                ProtocolConstants.REQ_TRACK -> i += 1
                else -> i += 1
            }
        }
        return tags
    }

    fun processPacket(data: ByteArray, len: Int = data.size): ParsedMessage? {
        if (len < 7) return null

        val header = decodeHeader(data)
        val moreFragments = (header.flags and 0x01) != 0
        val isRequest = (header.flags and 0x02) != 0

        if (len < 7 + header.payloadLen) return null

        val payload = data.copyOfRange(7, 7 + header.payloadLen)

        if (!moreFragments && header.fragOffset == 0) {
            if (reassembly.active) resetReassembly()
            return ParsedMessage(isRequest, parsePayloadTags(payload, payload.size))
        }

        if (header.fragOffset == 0) {
            resetReassembly()
            initReassembly(header.msgId)
        }

        if (!reassembly.active || reassembly.msgId != header.msgId) {
            resetReassembly()
            return null
        }

        if (header.fragOffset + header.payloadLen > MAX_MESSAGE_SIZE) {
            resetReassembly()
            return null
        }

        reassembly.buffer?.let { buf ->
            System.arraycopy(payload, 0, buf, header.fragOffset, header.payloadLen)
            if (header.fragOffset + header.payloadLen > reassembly.receivedLen) {
                reassembly.receivedLen = header.fragOffset + header.payloadLen
            }
        }

        return if (!moreFragments) {
            val result = ParsedMessage(
                isRequest,
                parsePayloadTags(reassembly.buffer!!, reassembly.receivedLen)
            )
            resetReassembly()
            result
        } else {
            null
        }
    }

    fun checkTimeout(): Boolean {
        if (reassembly.active && (System.currentTimeMillis() - reassembly.startTime > REASSEMBLY_TIMEOUT_MS)) {
            resetReassembly()
            return true
        }
        return false
    }
}