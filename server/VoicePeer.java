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


public class VoicePeer extends Thread {
    private SSLSocket socket;
    private Socket unsafeSocket;
    private int number = 0;
    private VoiceRelay relay;
    PrintWriter out;
    BufferedReader in;
    
    public VoicePeer(SSLSocket socket) {
        this.socket = socket;
    }
    public VoicePeer(Socket socket) {
        this.unsafeSocket = socket;
    }

    public boolean secure(){
        return socket != null;
    }
    public void run() {

        try {
            if (socket == null){
                //Use unsafe socket if safe is unavailable (old clients)
                out = new PrintWriter(unsafeSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(unsafeSocket.getInputStream()));
            }else{
                 out = new PrintWriter(socket.getOutputStream(), true);
                 in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            }
            String inputLine, outputLine;
            while ((inputLine = in.readLine()) != null) {
                System.out.println(inputLine);
                if(inputLine.equals("REQUEST NUMBER")){
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
                        String key = new BigInteger(130, random).toString(32);
                        writer.println("key=" + key);
                        writer.close();
                        //Associate this peer with the server
                        Switchboard.peers.put(this.number, this);
                        out.println("NUMBER " + Integer.toString(this.number) + " " + key);
                        System.out.println("NUMBER " + Integer.toString(this.number));
                    } catch (IOException e) {
                    // do something
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
                        while ((text = reader.readLine()) != null) {
                            if (text.startsWith("key=")){
                                if (!text.split("=")[1].equals(key)){
                                    out.println("INVALID KEY");
                                }else{
                                    Switchboard.peers.put(this.number, this);
                                    out.println("ASSOCIATED");
                                    System.out.println("ASSOCIATED " + Integer.toString(this.number) + " " + ((socket == null) ? "INSECURE" : "SECURE"));
                                }
                                break;
                            }
                        }
                    } catch (Exception e) {
                        out.println("INVALID NUMBER FORMAT");
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
                        String key = new BigInteger(130, random).toString();
                        if(dstPeer.secure() && this.secure()){
                            dstPeer.out.println("LINK " + Integer.toString(this.number) + " " + Integer.toString(callRelay.getPair2Port()) + " " + bytesToHex(getRawKey(key.getBytes())));
                            out.println("LINK " + Integer.toString(dstPeer.number) + " " + Integer.toString(callRelay.getPair1Port()) + " " + bytesToHex(getRawKey(key.getBytes())));
                        }else{
                            dstPeer.out.println("LINK " + Integer.toString(this.number) + " " + Integer.toString(callRelay.getPair2Port()));
                            out.println("LINK " + Integer.toString(dstPeer.number) + " " + Integer.toString(callRelay.getPair1Port()));
                        }
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

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
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
