import java.net.*;
import java.util.HashMap;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;

public class Switchboard {

    public static HashMap<Integer, VoicePeer> peers = new HashMap<Integer, VoicePeer>();
    public static void main(String[] args) {
        //Create a control channel
        try {
            //Create context
            SSLContext sslContext = SSLContext.getInstance("TLS");
            //Load keystore
            InputStream keyStoreResource = new FileInputStream("keystore.jks");
            KeyStore ksKeys = KeyStore.getInstance("JKS");
            ksKeys.load(keyStoreResource, Settings.keyStorePassword.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ksKeys, Settings.keyStorePassword.toCharArray());
            //Initialize context
            sslContext.init(kmf.getKeyManagers(), null, null);
            //Create SSLSocket
            SSLServerSocket sslserversocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(1338);

            //Backwards compatiability (remove later!!!)
            //Allow unencrypted on other port (for old clients)
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ServerSocket serverSocket = new ServerSocket(1337);
                        while (true){
                            new VoicePeer(serverSocket.accept()).start();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }   
                }
            }).start();     
            
            while (true){
                new VoicePeer((SSLSocket) sslserversocket.accept()).start();
            } 
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}