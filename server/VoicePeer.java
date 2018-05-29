import java.net.*;
import java.util.Random;
import java.io.*;
import java.security.SecureRandom;
import java.math.BigInteger;
import javax.net.ssl.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;


public class VoicePeer extends Thread {
    private SSLSocket socket;
    private int number = 0;
    private VoiceRelay relay;
    private PublicKey publicKey;
    PrintWriter out;
    BufferedReader in;
    private String authKey;
    
    public VoicePeer(SSLSocket socket) {
        this.socket = socket;
    }

    public void run() {

        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String inputLine, outputLine;
            while ((inputLine = in.readLine()) != null) {
                System.out.println(inputLine);

                //Client requested some auth key data, save this and transmitt
                if (inputLine.equals("AUTH")){
                    SecureRandom random = new SecureRandom();
                    this.authKey = new BigInteger(130, random).toString(32);
                    out.println(this.authKey);
                    continue;
                }

                if(inputLine.startsWith("REQUEST NUMBER")){
                    boolean rsaSupport = inputLine.split(" ").length > 2;
                    Random rand = new Random();
                    try{
                        boolean exists = true;
                        //Generate numbers until non existing number is found
                        while(exists){
                            this.number = 100000 + rand.nextInt(899999);
                            File nr = new File(Settings.rootPath + Integer.toString(this.number) + ".rtv");
                            exists = nr.exists();
                        }
                        SecureRandom random = new SecureRandom();
                        PrintWriter writer = new PrintWriter(Settings.rootPath +Integer.toString(this.number) + ".rtv", "UTF-8");
                        if (!rsaSupport){
                            String key = new BigInteger(130, random).toString(32);
                            writer.println("key=" + key);
                            out.println("NUMBER " + Integer.toString(this.number) + " " + key);
                        }
                        if(rsaSupport){
                            writer.println("publickey=" + inputLine.split(" ")[2]);
                            setPublicKey(inputLine.split(" ")[2]);
                            System.out.println("NUMBER " + Integer.toString(this.number));
                            out.println("NUMBER " + Integer.toString(this.number));
                        }
                        writer.close();
                        Switchboard.peers.put(this.number, this);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }else if (inputLine.startsWith("REGISTER ")){
                    try {
                        this.number = Integer.parseInt(inputLine.split(" ")[1]);
                        String key = inputLine.split(" ")[2];
                        File file = new File(Settings.rootPath + Integer.toString(this.number) + ".rtv");
                        if (!file.exists()){
                            out.println("UNKNOWN NUMBER");
                            continue;
                        }
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        String text = null;
                        //Check for public key and denie register if exist
                        while ((text = reader.readLine()) != null) {
                            if (text.startsWith("publickey=")){
                                out.println("USE PUBLIC KEY");
                                continue;
                            }
                        }
                        reader = new BufferedReader(new FileReader(file));
                        while ((text = reader.readLine()) != null) {
                            if (text.startsWith("key=")){
                                if (!text.split("=")[1].equals(key)){
                                    out.println("INVALID KEY");
                                }else{
                                    Switchboard.peers.put(this.number, this);
                                    out.println("ASSOCIATED");
                                    System.out.println("ASSOCIATED " + Integer.toString(this.number) + " SECURE");
                                    if (inputLine.split(" ").length == 4){
                                        System.out.println("ASSOCIATED " + Integer.toString(this.number) + " SECURE AND UPGRADED");
                                        Writer output = new BufferedWriter(new FileWriter(Settings.rootPath + Integer.toString(this.number) + ".rtv", true));
                                        output.append("\npublickey=" + inputLine.split(" ")[3]);
                                        output.close();
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
                        continue;
                    }
                }else if (inputLine.startsWith("SIG ")){
                    try {
                        this.number = Integer.parseInt(inputLine.split(" ")[1]);
                        File file = new File(Settings.rootPath + Integer.toString(this.number) + ".rtv");
                        if (!file.exists()){
                            out.println("UNKNOWN NUMBER");
                            continue;
                        }
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        String text = null;
                        while ((text = reader.readLine()) != null) {
                            if (text.startsWith("publickey=")){
                                    setPublicKey(text.split("=")[1]);
                            }
                        }
                        if (verifyData(this.authKey, inputLine.split(" ")[2])){
                            Switchboard.peers.put(this.number, this);
                            out.println("ASSOCIATED");
                            System.out.println("ASSOCIATED " + Integer.toString(this.number) + " SECURE" + " RSA");
                        }else{
                            out.println("INVALID KEY");
                             System.out.println("RSA VALIDATION FAILED FOR " + Integer.toString(this.number));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        out.println("SERVER ERROR");
                        continue;
                    }
                }
                
                //Do not allow to continue below if not associated with a number
                if(!Switchboard.peers.containsKey(this.number)){
                    out.println("ASSOCIATE");
                    continue;
                }

                if(inputLine.startsWith("DEVICE ")){
                    Writer output = new BufferedWriter(new FileWriter(Settings.rootPath + Integer.toString(this.number) + ".rtv", true));
                    output.append("\ndeviceid=" + inputLine.split(" ")[1]);
                    output.close();
                    out.println("DEVICE ADDED");
                }

                if(inputLine.startsWith("DIAL ")){
                    int dst;
                    try {
                        dst = Integer.parseInt(inputLine.split(" ")[1]);
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
                        continue;
                    }
                    if(!Switchboard.peers.containsKey(dst)){
                        //Find if GCM is associated, if so dial all gcm ids
                        File file = new File(Settings.rootPath + inputLine.split(" ")[1] + ".rtv");
                        if (!file.exists()){
                            out.println("UNKNOWN NUMBER");
                            continue;
                        }
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        String text = null;
                        while ((text = reader.readLine()) != null) {
                            if (text.startsWith("deviceid=")){
                                String deviceId = text.split("=")[1];
                                System.out.println(deviceId);
                                Runtime.getRuntime().exec("/root/gcm "+deviceId+" "+Integer.toString(this.number));
                            }
                        }

                        //If no GCM was found send this
                        out.println("DIALED VIA GCM");
                        continue;
                    }
                    try {
                        VoicePeer dstPeer = Switchboard.peers.get(dst);
                        dstPeer.out.println("CALL FROM " + Integer.toString(this.number));
                        

                        out.println("DIALING");
                    } catch (Exception e) {
                        out.println("ASSOCIATION ERROR");
                    }
                }

                if(inputLine.startsWith("ANSWER FROM ")){
                    int dst;
                    try {
                        dst = Integer.parseInt(inputLine.split(" ")[2]);
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
                        continue;
                    }
                    try {
                        VoiceRelay callRelay = new VoiceRelay();
                        VoicePeer dstPeer = Switchboard.peers.get(dst);
                        this.relay = callRelay;
                        dstPeer.relay = callRelay;
                        SecureRandom random = new SecureRandom();
                        String key = bytesToHex(getRawKey(new BigInteger(130, random).toString().getBytes()));
                        dstPeer.out.println("LINK " + Integer.toString(this.number) + " " + Integer.toString(callRelay.getPair2Port()) + " " + key);
                        out.println("LINK " + Integer.toString(dstPeer.number) + " " + Integer.toString(callRelay.getPair1Port()) + " " + key);
                    } catch (Exception e) {
                        out.println("ASSOCIATION ERROR");
                    }
                }

                if(inputLine.startsWith("HANGUP FROM ")){
                    int dst;
                    try {
                        dst = Integer.parseInt(inputLine.split(" ")[2]);
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
                        continue;
                    }
                    try {
                        VoicePeer dstPeer = Switchboard.peers.get(dst);
                        dstPeer.out.println("HANGUP " + Integer.toString(this.number));
                        out.println("HANGUP " + Integer.toString(dstPeer.number));
                        relay.close();
                        dstPeer.relay.close();
                    } catch (Exception e) {
                        out.println("ASSOCIATION ERROR");
                    }
                }

                if(inputLine.startsWith("DECLINE FROM ")){
                    int dst;
                    try {
                        dst = Integer.parseInt(inputLine.split(" ")[2]);
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
                        continue;
                    }
                    try {
                        VoicePeer dstPeer = Switchboard.peers.get(dst);
                        dstPeer.out.println("DECLINE " + Integer.toString(this.number));
                    } catch (Exception e) {
                        out.println("ASSOCIATION ERROR");
                    }
                }

                if(inputLine.startsWith("BUSY FROM ")){
                    int dst;
                    try {
                        dst = Integer.parseInt(inputLine.split(" ")[2]);
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
                        continue;
                    }
                    try {
                        VoicePeer dstPeer = Switchboard.peers.get(dst);
                        dstPeer.out.println("BUSY " + Integer.toString(this.number));
                    } catch (Exception e) {
                        out.println("ASSOCIATION ERROR");
                    }
                }

                if (inputLine.equals("CLOSE"))
                    break;
            }
            out.close();
            in.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Disassociate peer of no longer connected
        if(Switchboard.peers.containsKey(this.number))
            Switchboard.peers.remove(this.number);
    }

    public boolean verifyData(String message, String signature) throws Exception{
        byte[] data = message.getBytes();
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(this.publicKey);
        sig.update(data);
        return sig.verify(hexStringToByteArray(signature));
    }

    public String encryptRSA(String message) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, this.publicKey);
        return bytesToHex(cipher.doFinal(message.getBytes()));
    }


    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private void setPublicKey(String publicKey) throws Exception{
        X509EncodedKeySpec spec = new X509EncodedKeySpec(hexStringToByteArray(publicKey));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        this.publicKey = keyFactory.generatePublic(spec);
    }

    public static String bytesToHex(byte[] bytes) {
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
    private static byte[] getRawKey(byte[] seed) throws Exception {
        KeyGenerator kgen = KeyGenerator.getInstance("AES");
        SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
        sr.setSeed(seed);
        kgen.init(128, sr);
        SecretKey skey = kgen.generateKey();
        byte[] raw = skey.getEncoded();
        return raw;
    }
}
