import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class VoiceRelay{

    private int port1;
    private int port2;
    private boolean closing = false;

    public VoiceRelay() throws Exception{
        final DatagramSocket sock1 = new DatagramSocket();
        final DatagramSocket sock2 = new DatagramSocket();
        //Log socket ports to terminal
        System.out.println("Address 1: " + Integer.toString(sock1.getLocalPort()));
        System.out.println("Address 2: " + Integer.toString(sock2.getLocalPort()));
        port1 = sock1.getLocalPort();
        port2 = sock2.getLocalPort();
        bridge(sock1, sock2);
        bridge(sock2, sock1);
    }

    public void close(){
        this.closing = true;
    }

    public int getPair1Port(){
        return port1;
    }
    public int getPair2Port(){
        return port2;
    }

    private Thread bridge(DatagramSocket src, DatagramSocket dst){
        Thread br = new Thread(new Runnable() {
                @Override
                public void run() {
                    //Always keep the relay open, this allows clients to reconnect
                    while(!closing){
                        byte[] receiveData = new byte[2048];
                        try {
                            //Connect to first available peer
                            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                            src.receive(receivePacket);
                            src.connect(receivePacket.getSocketAddress());
                            while(src.isConnected()){
                                //Receive packet from client
                                receivePacket = new DatagramPacket(receiveData, receiveData.length);
                                src.receive(receivePacket);
                                System.out.println("Packet of " + Integer.toString(receivePacket.getLength()) + " was received from " + receivePacket.getAddress().toString());
                                //Forward packet to other socket if connected
                                if (dst.isConnected()){
                                    DatagramPacket packet = new DatagramPacket(receiveData,receivePacket.getLength());
                                    dst.send(packet);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    src.close();
                }
        });
        br.start();
        return br;
    }

}