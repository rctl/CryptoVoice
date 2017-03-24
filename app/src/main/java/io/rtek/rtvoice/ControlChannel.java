package io.rtek.rtvoice;

import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.AsyncTask;
import android.support.v4.util.Pair;
import android.util.Log;

import org.apache.http.conn.ssl.SSLSocketFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Created by rctl on 2017-01-22.
 */

public class ControlChannel extends Thread implements ICallListener {

    //Control
    IControlChannelListener listener;
    int number = 0;
    PrintWriter out;
    BufferedReader in;
    String server;
    Call currentCall;
    KeyPair identity;

    public ControlChannel(IControlChannelListener listener, String server){
        this.listener = listener;
        this.server = server;
    }

    //Actions
    public void answer(int callerId){
        transmitt("ANSWER FROM " + Integer.toString(callerId));
    }

    public void decline(int callerId){
        transmitt("DECLINE FROM " + Integer.toString(callerId));
        currentCall = null;
    }

    public boolean dial(int callerId){
        if(activeCall())
            return false;
        transmitt("DIAL " + Integer.toString(callerId));
        listener.callStatusChanged(CallStatus.DIALING);
        currentCall = new Call(this, this.number, callerId);
        return true;
    }

    public boolean hangup(){
        transmitt("HANGUP FROM " + Integer.toString(currentCall.getRemoteCallerId()));
        currentCall.close();
        return true;
    }

    @Override
    public void callStatusChanged(CallStatus status) {
        listener.callStatusChanged(status);
    }

    @Override
    public void callEnded() {
        listener.callEnded(0);
        this.currentCall = null;
    }

    //Statuses
    public boolean activeCall(){
        if (currentCall == null)
            return false;
        return !currentCall.closed();
    }

    public int getNumber(){
        return this.number;
    }

    public void createIncoming(int number){
        if (!activeCall()) {
            listener.incoming(number);
            currentCall = new Call(this, this.number, number);
        }
    }

    //Internals
    private void transmitt(final String message){
        new Thread(new Runnable() {
            @Override
            public void run() {
                while(socket != null && !socket.isConnected()){}
                out.println(message);
            }
        }).start();
    }

    public void generateIdentity() throws Exception{
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024, new SecureRandom());
        identity = generator.genKeyPair();
    }

    public String getPublicKey(){
        return bytesToHex(identity.getPublic().getEncoded());
    }

    private String getPrivateKey(){
        return bytesToHex(identity.getPrivate().getEncoded());
    }

    private void fromKeyPair(String publicKey, String privateKey) throws Exception{
        byte[] clearPublicKey = hexStringToByteArray(publicKey);
        byte[] clearPrivateKey = hexStringToByteArray(privateKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(clearPublicKey);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey key = keyFactory.generatePublic(spec);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(clearPrivateKey);
        KeyFactory fact = KeyFactory.getInstance("RSA");
        PrivateKey priv = fact.generatePrivate(keySpec);
        this.identity =  new KeyPair(key, priv);
    }

    public String encryptRSA(final String publickey, String message) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        X509EncodedKeySpec spec = new X509EncodedKeySpec(hexStringToByteArray(publickey));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey key = keyFactory.generatePublic(spec);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return bytesToHex(cipher.doFinal(message.getBytes()));
    }

    public String decryptRSA(String message) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, identity.getPrivate());
        return new String(cipher.doFinal(hexStringToByteArray(message)));
    }

    Socket socket;

    private boolean closing = false;
    public void close(){
        closing = true;
    }
    public boolean isCancelled(){
        return closing;
    }
    //Main
    public void run() {
        try{
            if(listener.getString("identity-public").isEmpty() || listener.getString("identity-private").isEmpty()){
                generateIdentity();
                listener.saveString("identity-public", getPublicKey());
                listener.saveString("identity-private", getPrivateKey());
            }else{
                try{
                    fromKeyPair(listener.getString("identity-public"), listener.getString("identity-private"));
                }catch (Exception e){
                    generateIdentity();
                    listener.saveString("identity-public", getPublicKey());
                    listener.saveString("identity-private", getPrivateKey());
                }
            }
        }catch (Exception e) {
            //Could not generate nor save identity
        }
        while(!isCancelled()){
            try {
                listener.ControlChannelDisconnected();
                TrustManagerFactory tmf;
                KeyManagerFactory kmfactory;
                try{
                    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                    trustStore.load(null);
                    InputStream stream = listener.getServerCertificate();
                    BufferedInputStream bis = new BufferedInputStream(stream);
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    while (bis.available() > 0) {
                        Certificate cert = cf.generateCertificate(bis);
                        trustStore.setCertificateEntry("cert" + bis.available(), cert);
                    }
                    kmfactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmfactory.init(trustStore, "1234".toCharArray());
                    tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(trustStore);
                }catch (Exception e){
                    throw new Exception("No valid truststore provided");
                }
                SSLContext context = SSLContext.getInstance("SSL");
                KeyManager[] keymanagers =  kmfactory.getKeyManagers();
                context.init(keymanagers, tmf.getTrustManagers(), new SecureRandom());
                socket = context.getSocketFactory().createSocket(this.server, 1338);
                Log.w("CC", "Connecting");
                while(!socket.isConnected()){}
                Log.w("CC", "Connected");
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // If no new identity and phone number saved, signed data of current date
                // If new identity and no saved phone number, send number request and public key to server
                // If new identity and save password, send authorized key and public key to server
                String savedNumber = listener.getString("number");
                if (identity == null){
                    throw new Exception("RSA not supported on device");
                }

                //Do auth
                if (savedNumber.isEmpty()){
                    //No number saved, request a new one with identity
                    Log.w("CC", "Requesting new number");
                    out.println("REQUEST NUMBER " + getPublicKey());
                }else{
                    //We have a number, determaine auth method
                    if (savedNumber.contains(":")){
                        Log.w("CC", "Registering and upgrading");
                        //We have a ':' in the number, use authkey method and upgrade number
                        out.println("REGISTER " + savedNumber.split(":")[0] + " " + savedNumber.split(":")[1] + " " + getPublicKey());
                    }else{
                        //We have no ':' in the number, use identity version
                        //Request random for auth
                        Log.w("CC", "Requesting random");
                        out.println("AUTH");
                        String rnd = in.readLine();
                        Log.w("CC", "Got random");
                        out.println("SIG " + savedNumber + " " + signData(rnd));
                        Log.w("CC", "Auth with RSA " + signData(rnd));
                    }
                }

                //Handle number response
                String response = in.readLine();
                if(response.startsWith("NUMBER ")){
                    Log.w("CC", "Got a new number");
                    //We got a new number, transmitt our device id
                    listener.saveString("number", response.split(" ")[1]);
                    this.number = Integer.parseInt(response.split(" ")[1]);
                    if(listener.getGcmDeviceId() != null)
                        out.println("DEVICE " + listener.getGcmDeviceId());
                }else if(response.equals("ASSOCIATED")){ //If we successfully associated we must have successfully requested an upgrade or successfully authed with our identity
                    Log.w("CC", "Associated");
                    //We successfully associated
                    String current = listener.getString("number");
                    if (current.contains(":")){
                        //Remove old auth key
                        listener.saveString("number", current.split(":")[0]);
                        this.number = Integer.parseInt(current.split(":")[0]);
                    }else{
                        this.number = Integer.parseInt(current);
                    }
                    if (!listener.getString("gcm").equals("registered")){
                        if(listener.getGcmDeviceId() != null)
                            out.println("DEVICE " + listener.getGcmDeviceId());
                    }
                }else{
                    throw new Exception("Unknown exception when connecting");
                }

                listener.setNumber(this.number);
                listener.ControlChannelConnected();
                while(socket.isConnected() && !isCancelled()){
                    final String cmd = in.readLine();
                    listener.messageReceived(cmd);
                    Log.w("CC", cmd);
                    if(cmd.equals("DEVICE ADDED")){
                        listener.saveString("gcm", "registered");
                    }
                    if(cmd.startsWith("CALL FROM ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[2]);
                        if (activeCall()){
                            out.println("BUSY FROM " + Integer.toString(callerId));
                        }else{
                            listener.incoming(callerId);
                            currentCall = new Call(this, this.number, callerId);
                        }
                    }
                    if(cmd.startsWith("LINK ")) {
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        listener.initializeCall(callerId);
                        currentCall.start(server + ":" + cmd.split(" ")[2], cmd.split(" ")[3]);
                        listener.callSecure(true);
                    }
                    if(cmd.startsWith("HANGUP ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        currentCall.close();
                        listener.callEnded(callerId);
                    }
                    if(cmd.startsWith("BUSY ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        listener.callStatusChanged(CallStatus.BUSY);
                        listener.callEnded(callerId);
                        currentCall = null;
                    }
                    if(cmd.startsWith("DECLINE ")){
                        int callerId = Integer.parseInt(cmd.split(" ")[1]);
                        listener.callStatusChanged(CallStatus.DECLINED);
                        listener.callEnded(callerId);
                        currentCall = null;
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }
        return;
    }

    public String signData(String message) throws Exception{
        byte[] data = message.getBytes();
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(identity.getPrivate());
        sig.update(data);
        return bytesToHex(sig.sign());
    }

    public boolean verifyData(String message, String signature, String publickey) throws Exception{
        X509EncodedKeySpec spec = new X509EncodedKeySpec(hexStringToByteArray(publickey));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey key = keyFactory.generatePublic(spec);
        byte[] data = message.getBytes();
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(hexStringToByteArray(signature));
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


}

