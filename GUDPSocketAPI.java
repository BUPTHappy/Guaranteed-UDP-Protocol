import java.net.DatagramPacket;
import java.io.IOException;

public interface GUDPSocketAPI {

    //需要完成这四个方法体的实现：在GUDPSocket.java里
    public void send(DatagramPacket packet) throws IOException;
    public void receive(DatagramPacket packet) throws IOException;
    public void finish() throws IOException;
    public void close() throws IOException;
}

