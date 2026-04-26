package com.example.clockapp.ble

interface DataProvider {
    fun getUnixTime(): UInt
    fun getVpnStatus(): Boolean
    fun getPlaybackStatus(): Boolean
    fun getArtist(): String
    fun getTrack(): String
}