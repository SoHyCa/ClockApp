package com.example.clockapp.protocol

import kotlin.random.Random

object ProtocolEncoder {
    fun encodeMessage(fields: List<ProtocolField>, isRequest: Boolean = false): List<ByteArray> {
        val payload = fields.flatMap { it.serialize().toList() }.toByteArray()
        val msgId = Random.nextInt(0, 65536)
        val packets = mutableListOf<ByteArray>()

        var offset = 0
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val chunkSize = minOf(remaining, ProtocolConstants.MAX_PAYLOAD_SIZE)
            val isLast = (offset + chunkSize) >= payload.size

            val flags = buildFlags(isRequest, moreFragments = !isLast)
            val packet = ByteArray(7 + chunkSize)

            packet[0] = flags
            packet[1] = (msgId shr 8).toByte()
            packet[2] = msgId.toByte()
            packet[3] = (offset shr 8).toByte()
            packet[4] = offset.toByte()
            packet[5] = (chunkSize shr 8).toByte()
            packet[6] = chunkSize.toByte()

            System.arraycopy(payload, offset, packet, 7, chunkSize)

            packets.add(packet)
            offset += chunkSize
        }

        return packets
    }

    private fun buildFlags(isRequest: Boolean, moreFragments: Boolean): Byte {
        var flags = 0
        if (moreFragments) flags = flags or 0x01
        if (isRequest) flags = flags or 0x02
        return flags.toByte()
    }
}