package com.samfm.radio;

public final class Constants {
    private Constants() {}

    // TODO: switch to HTTPS + DNS when ready:
    // public static final String STREAM_URL = "https://radio.samjanvey.com/radio/8000/radio.mp3";
    // public static final String NOWPLAYING_URL = "https://radio.samjanvey.com/api/nowplaying/sam_fm";

    // Dev over IP/HTTP:
    public static final String STREAM_URL = "http://radio.samjanvey.com/radio/8000/radio.mp3";
    public static final String NOWPLAYING_URL = "http://radio.samjanvey.com/api/nowplaying/sam_fm";
}
