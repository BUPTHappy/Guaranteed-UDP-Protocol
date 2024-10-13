import java.io.IOException;
//import java.util.LinkedList;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Iterator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.TimerTask;
import java.util.Timer;


/*
* 这个GUDPEndPoint类实现了一个类似于"Go-Back-N" (GBN) 协议的通信端点
* 主要用于发送和接收基于UDP协议的数据包。
* 它包含了发送、接收、超时处理和丢包模拟等功能。
* */


class GUDPEndPoint {
    /* Pre-defined constant values for key variables */
    public static final int MAX_WINDOW_SIZE = 3;//定义了发送窗口的最大大小（此处为3）
    public static final long TIMEOUT_DURATION = 3000L; // 定义了超时时间（3秒）
    public static final int MAX_RETRY = 7; //最大重传次数，设定为7次

    /* Variables for the control block */
    private InetSocketAddress remoteEndPoint;  //远程端点的地址和端口号，用于标识通信的对端
    //private LinkedList<GUDPPacket> packetList = new LinkedList<>(); //list of GUDPPacket
    private ConcurrentLinkedQueue<GUDPPacket> bufferQueue = new ConcurrentLinkedQueue<>(); //用于存储要发送或接收的数据包的队列（使用ConcurrentLinkedQueue来确保线程安全）

    private int windowSize;  //发送窗口的大小，控制同时未被确认（未ACK）的数据包数量
    private long timeoutDuration;  //超时时间，超过这个时间未收到ACK会触发超时重传
    private int maxRetry;  //最大重传次数，用于控制超时后的重传上限
    private int retry = 0;   //当前已经重传的次数

    /* GBN sender */ //发送端要维护的变量
    private int base;            // 发送的最早未确认的包的序号，即“基序号”（base）
    private int nextseqnum;      // 下一个要发送的数据包的序号
    private int last;            // 缓冲区中的最后一个数据包序号

    /* GBN receiver */ //接受端要维护的变量
    private int expectedseqnum;  // 接收时期望的下一个数据包序号

    private boolean finished = false;        // 表示通信是否已经完成的标志

    /* for testing drop packets */    
    private boolean dropSend = false;        // 用于测试是否模拟发送和接收数据包的丢失
    private boolean dropReceive = false;
    private double chance = 0.2;            //丢包的概率（0.2表示20%的丢包率）
    
    /* States for GBN sender and receiver combined in one */
    protected enum endPointState {
        INIT,   //初始化状态
        WAIT,   //等待状态，可能在等待ACK
        SEND,   //发送数据包的状态
	RCV,        //接收数据包的状态
        TIMEOUT,   //超时状态，需要重传未确认的包
        DEFAULT //默认状态
    }
    private endPointState state = endPointState.INIT;

    //这个构造函数接受一个IP地址和端口号，初始化远程端点的地址，同时设置默认的窗口大小、超时和最大重传次数。
    public GUDPEndPoint(InetAddress addr, int port) {
        setRemoteEndPoint(addr, port);
        this.windowSize = MAX_WINDOW_SIZE;
        this.maxRetry = MAX_RETRY;
        this.timeoutDuration = TIMEOUT_DURATION;
    }
 
    public InetSocketAddress getRemoteEndPoint() {
        return this.remoteEndPoint;
    }

    /*这些方法允许外部访问和修改类的成员变量：
        主要是一些基本的设置，比如远程端点、窗口大小、重试次数、超时时间、序号等
        。方便协议在不同状态下根据需求调整这些参数。*/
    public void setRemoteEndPoint(InetAddress addr, int port) {
        this.remoteEndPoint = new InetSocketAddress(addr, port);
    }

    public int getWindowSize() {
        return this.windowSize;
    }

    public void setWindowSize(int size) {
        this.windowSize = size;
    }

    public int getMaxRetry() {
        return this.maxRetry;
    }

    public void setMaxRetry(int retry) {
        this.maxRetry = retry;
    }

    public long getTimeoutDuration() {
        return this.timeoutDuration;
    }

    public void setTimeoutDuration(long duration) {
        this.timeoutDuration = duration;
    }

    public int getRetry() {
        return this.retry;
    }

    public void setRetry(int retry) {
        this.retry = retry;
    }

    public int getBase() {
        return this.base;
    }

    public void setBase(int ack) {
        this.base = ack;
    }

    public int getNextseqnum() {
        return this.nextseqnum;
    }

    public void setNextseqnum(int seq) {
        this.nextseqnum = seq;
    }

    public int getLast() {
        return this.last;
    }

    public void setLast(int last) {
        this.last = last;
    }

    public int getExpectedseqnum() {
        return this.expectedseqnum;
    }

    public void setExpectedseqnum(int seq) {
        this.expectedseqnum = seq;
    }

    public boolean getFinished() {
        return this.finished;
    }

    public void setFinished(boolean value) {
        this.finished = value;
    }

    public boolean getDropSend() {
        return this.dropSend;
    }

    public void setDropSend(boolean value) {
        this.dropSend = value;
    }

    public boolean getDropReceive() {
        return this.dropReceive;
    }

    public void setDropReceive(boolean value) {
        this.dropReceive = value;
    }

    public double getChance() {
        return this.chance;
    }

    public void setChance(double value) {
        this.chance = value;
    }

    public endPointState getState() {
        return this.state;
    }

    public void setState(endPointState state) {
        this.state = state;
    }

    //将GUDPPacket数据包添加到缓冲队列中
    public void add(GUDPPacket gpacket) {
        bufferQueue.add(gpacket);
    }
    //从缓冲队列中移除指定的GUDPPacket
    public void remove(GUDPPacket gpacket) {
    	bufferQueue.remove(gpacket);
    }

    /*
     * 从队列中移除并返回第一个数据包（类似于FIFO操作）
     */
    public GUDPPacket remove() {
    	return bufferQueue.poll();
    }

    /*
     * 根据序号找到特定的数据包，但不会将其从缓冲队列中移除
     * IMPORTANT: the packet is still in the bufferQueue!
     */
    public GUDPPacket getPacket(int seq) {
        Iterator<GUDPPacket> iter = bufferQueue.iterator();
        while(iter.hasNext()) {
	    GUDPPacket p = iter.next();
            if (p.getSeqno() == seq) {
                return p;
            }
        }
	return null;
    }

    /*
     * 移除队列中所有序号小于或等于ACK号的数据包，这些数据包已被确认收到。
     * Assuming those packets were successfully received
     */
    public void removeAllACK(int ack) {
        while ((!isEmptyQueue()) && (bufferQueue.peek().getSeqno() <= ack)) {
            bufferQueue.poll();
        }
    }

    /*
     * 清空队列并重置所有相关变量，恢复到初始状态
     */
    public void clear() {
        bufferQueue.clear();
	this.setRetry(0);
	this.setBase(0);
	this.setNextseqnum(0);
	this.setLast(0);
	this.setExpectedseqnum(0);
	this.setFinished(false);
    }

    //检查队列是否为空
    public boolean isEmptyQueue() {
        return bufferQueue.isEmpty();
    }
    //返回队列的大小
    public int queueSize() {
        return bufferQueue.size();
    }

    /*
     * 启动一个计时器用于超时检测，
     * 如果在timeoutDuration时间内未收到ACK，触发超时，改变状态为TIMEOUT，并取消计时器。
     */
    Timer timer;
    public void startTimer() {
        timer = new Timer("Timer");
        TimerTask task = new TimerTask() {
            public void run() {
                System.out.println("TIMEOUT " + getRetry() + ":\t" 
				+ remoteEndPoint.getAddress() + ":" + remoteEndPoint.getPort());
                setState(endPointState.TIMEOUT);
		timer.cancel();
            }
        };
        timer.schedule(task, timeoutDuration);
    }
    //停止当前计时器，避免误触发超时
    public void stopTimer() {
        timer.cancel();
    }

}
