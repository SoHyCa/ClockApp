package com.example.clockapp.protocol

object ProtocolConstants {
    const val MAX_PAYLOAD_SIZE = 500

    const val TAG_UNIX_TIME = 0x01
    const val TAG_VPN       = 0x02
    const val TAG_PLAYBACK  = 0x03
    const val TAG_ARTIST    = 0x04
    const val TAG_TRACK     = 0x05

    const val REQ_TIME     = 0x11
    const val REQ_VPN      = 0x12
    const val REQ_PLAYBACK = 0x13
    const val REQ_ARTIST   = 0x14
    const val REQ_TRACK    = 0x15
}