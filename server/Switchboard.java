import java.net.*;
import java.util.HashMap;


public class Switchboard {

    public static HashMap<Integer, VoicePeer> peers = new HashMap<Integer, VoicePeer>();
    public static void main(String[] args) {
        //Create a control channel
        try {
            ServerSocket serverSocket = new ServerSocket(1337);
            while (true){
                new VoicePeer(serverSocket.accept()).start();
            } 
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}