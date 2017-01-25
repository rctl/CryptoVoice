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
            //Generate keystore with `keytool -genkey -keystore keystore.jks -keyalg RSA`, put password in settings
            // keytool -selfcert -alias mykey -keystore keystore.jks -validity 3950
            // keytool -export -alias mykey -keystore keystore.jks -rfc -file server.crt
            // keytool -importcert -alias mykey -file server.crt -keystore truststore.jks
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
            while (true){
                new VoicePeer((SSLSocket) sslserversocket.accept()).start();
            } 
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}