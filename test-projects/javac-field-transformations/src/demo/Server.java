package demo;

import java.io.IOException;
import java.net.ServerSocket;

/** Its socket is assigned inside a try, so making it final needs a temporary. */
public class Server {
    private ServerSocket socket;

    public Server(int port) {
        try {
            this.socket = new ServerSocket(port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int port() {
        return socket == null ? -1 : socket.getLocalPort();
    }

    public void stop() throws IOException {
        socket.close();
    }
}
