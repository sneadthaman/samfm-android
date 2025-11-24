package com.samfm.radio;

public final class Constants {
    private Constants() {}

    // Use HTTPS + DNS so Android cleartext policies don’t block playback on-device.
    public static final String STREAM_URL = "https://radio.samjanvey.com/radio/8000/radio.mp3";
    public static final String NOWPLAYING_URL = "https://radio.samjanvey.com/api/nowplaying/sam_fm";
}
