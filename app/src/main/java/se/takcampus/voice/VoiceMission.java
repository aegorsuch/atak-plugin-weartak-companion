package se.takcampus.voice;

import java.util.Collections;
import java.util.List;

/** Configuration only. Importing a mission never grants channel access or changes TLS trust. */
public final class VoiceMission {
    public final String id, name;
    public final List<Channel> channels;

    public VoiceMission(String id, String name, List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.channels = Collections.unmodifiableList(channels);
    }

    public static final class Channel {
        public final String id, name, host;
        public final int port, serverChannelId;

        public Channel(String id, String name, String host, int port, int serverChannelId) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.serverChannelId = serverChannelId;
        }
    }
}
