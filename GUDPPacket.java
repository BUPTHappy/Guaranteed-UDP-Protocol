import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;


/*GUDPPacket 类是一个用于封装和解封 UDP 数据包的类
在其上定义了一个名为 GUDP 的协议。这个类的主要目的是在网络通信中
帮助构造和解析具有自定义协议头（GUDP）的数据包
* */

public class GUDPPacket {
    public static final short GUDP_VERSION = 1; //指定 GUDP 协议的版本号，默认设置为 1
    public static final short HEADER_SIZE = 8;  //GUDP 数据包的头部大小，定义为 8 字节
    public static final Integer MAX_DATA_LEN = 1000;    //定义了数据部分的最大长度，最大为 1000 字节
    public static final Integer MAX_DATAGRAM_LEN = MAX_DATA_LEN + HEADER_SIZE;  //定义了整个数据包（头部 + 数据）的最大长度
    public static final Integer MAX_WINDOW_SIZE = 3; //GUDP 协议支持的最大窗口大小，定义为 3
    public static final short TYPE_DATA = 1; //用于携带应用数据
    public static final short TYPE_BSN = 2; //控制序列号，发送方使用BSN包告诉接收方DATA数据包序列号的起点
    public static final short TYPE_ACK = 3; //确认DATA数据包的接收
    public static final short TYPE_FIN = 4; //指示DATA传输的结束

    private InetSocketAddress sockaddr;  //存储目标的 InetSocketAddress，表示数据包将发送或接收的地址
    private ByteBuffer byteBuffer; //使用 ByteBuffer 作为底层数据结构来存储和处理数据包的字节
    private Integer payloadLength; //表示数据部分的长度

    /* 
     * Application send processing: Build a DATA GUDP packet to encaspulate payload
     * from the application. The application payload is in the form of a DatagramPacket,
     * containing data and socket address.
     */

    //发送：用于封装应用程序数据包。这个方法将 DatagramPacket 转换成带有 GUDP 协议头的 GUDPPacket
    public static GUDPPacket encapsulate(DatagramPacket packet) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(packet.getLength() + HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        GUDPPacket gudppacket = new GUDPPacket(buffer);
        gudppacket.setType(TYPE_DATA);
        gudppacket.setVersion(GUDP_VERSION);
        byte[] data = packet.getData();
        gudppacket.setPayload(data);
        gudppacket.setSocketAddress((InetSocketAddress) packet.getSocketAddress());
        return gudppacket;
    }

    /* 
     * Application receive processing: Extract application payload into a DatagramPacket, 
     * with data and socket address.
     */
    //接收：用于从 GUDPPacket 中提取出应用层的数据，并将其填充回 DatagramPacket 中
    public void decapsulate(DatagramPacket packet) throws IOException {
        int plength  = getPayloadLength();
        getPayload(packet.getData(), plength);
        packet.setLength(plength);
        packet.setSocketAddress(getSocketAddress());
    }

    /*
     * Input processing: Turn a DatagramPacket received from UDP into a GUDP packet
     */
    //输入处理：用于将接收到的 DatagramPacket 解封为 GUDPPacket，并处理其协议头
    public static GUDPPacket unpack(DatagramPacket packet) throws IOException {
        int plength = packet.getLength();
        if (plength < HEADER_SIZE)
            throw new IOException(String.format("Too short GUDP packet: %d bytes", plength));

        byte[] data = packet.getData();
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, plength);
        buffer.order(ByteOrder.BIG_ENDIAN);
        GUDPPacket gudppacket = new GUDPPacket(buffer);
        gudppacket.setPayloadLength(plength - HEADER_SIZE);
        gudppacket.setSocketAddress((InetSocketAddress) packet.getSocketAddress());
        return gudppacket;
    }

    /*
     * Output processing: Turn headers and payload into a DatagramPacket, for sending with UDP
     */
    //输出处理：将 GUDPPacket 转换回 DatagramPacket，以便通过 UDP 发送
    public DatagramPacket pack() throws IOException {
        int totlength = HEADER_SIZE + getPayloadLength();
        InetSocketAddress socketAddress = getSocketAddress();
        return new DatagramPacket(getBytes(), totlength, sockaddr);
    }
    
    /*
     * Constructor: create a GUDP packet with a ByteBuffer as back storage
     */
    public GUDPPacket(ByteBuffer buffer) {
        byteBuffer = buffer;
    }

    /* 
     * Serialization: Return packet as a byte array
     */
    public byte[] getBytes() {
        return byteBuffer.array();
    }

    public short getVersion() {
        return byteBuffer.getShort(0);
    }

    public short getType() {
        return byteBuffer.getShort(2);
    }

    public int getSeqno() {
        return byteBuffer.getInt(4);
    }

    public InetSocketAddress getSocketAddress() {
        return sockaddr;
    }

    public void setVersion(short version) {
        byteBuffer.putShort(0, version);
    }

    public void setType(short type) {
        byteBuffer.putShort(2, type);
    }

    public void setSeqno(int length) {
        byteBuffer.putInt(4, length);
    }

    public void setPayload(byte[] pload) {
        byteBuffer.position(HEADER_SIZE);
        byteBuffer.put(pload, 0, pload.length);
        payloadLength = pload.length;
    }

    public void setSocketAddress(InetSocketAddress socketAddress) {
        sockaddr = socketAddress;
    }

    public void setPayloadLength(int length) {
        payloadLength = length;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public void getPayload(byte[] dst, int length) {
        byteBuffer.position(HEADER_SIZE);
        byteBuffer.get(dst, 0, length);
    }
}
