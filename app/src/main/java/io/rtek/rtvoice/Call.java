package io.rtek.rtvoice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Created by rctl on 2017-01-23.
 */

public class Call extends Thread {

    //Voice and transmission
    private Thread downlink;
    private DatagramSocket sock;
    private byte[] transmittBuffer;
    private byte[] receiveBuffer;
    private byte[] receiveBuffer_Encrypted;
    private byte[] transmittBuffer_Encrypted;
    private AudioRecord recorder;
    private AudioTrack track;
    private String server;
    //Constants
    final private int sampleRate = 16000;
    final private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    final private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    final private int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    private int remoteCallerId;
    private int localCallerId;
    //Listener
    private ICallListener listener;
    //Interrupt
    private boolean closing = false;
    private boolean secure = false;
    private byte[] key;



    public Call(ICallListener listener, int localCallerId, int remoteCallerId){
        this.listener = listener;
        this.localCallerId = localCallerId;
        this.remoteCallerId = remoteCallerId;
    }

    public boolean hasKey(){
        return key != null;
    }

    public byte[] generateKey() throws Exception{
        SecureRandom random = new SecureRandom();
        this.key = getRawKey(new BigInteger(130, random).toByteArray());
        return this.key;
    }

    public void close(){
        this.closing = true;
        if (sock != null){
            sock.close();
        }
        if (recorder != null){
            try{
                recorder.stop();
            }catch (Exception e){

            }
            try{
                recorder.release();
            }catch (Exception e){

            }
        }
        listener.callEnded();
    }

    //Supply new key
    public void start(String server, String encryptionKey) throws Exception{
        this.server = server;
        if(encryptionKey.isEmpty()){
            throw new Exception("No encryption key supplied!");
        }
        this.secure = true;
        this.key = hexStringToByteArray(encryptionKey);
        start();
    }

    //Use previously generated key
    public void start(String server) throws Exception{
        this.server = server;
        if(key == null){
            throw new Exception("No encryption key generated!");
        }
        this.secure = true;
        start();
    }

    //Legacy
    public void startWithoutEncryption(String server){
        this.server = server;
        this.secure = false;
        start();
    }

    public boolean closed(){
        return this.closing;
    }

    public int getRemoteCallerId(){
        return remoteCallerId;
    }

    public int getLocalCallerId(){
        return localCallerId;
    }

    public void run() {
        try {
            track = new AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize, AudioTrack.MODE_STREAM);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBufSize * 10);
            transmittBuffer = new byte[minBufSize];
            receiveBuffer = new byte[minBufSize];
            receiveBuffer_Encrypted = new byte[minBufSize+16];
            listener.callStatusChanged(CallStatus.CONNECTING);
            NoiseSuppressor.create(MediaRecorder.AudioSource.MIC);
            AcousticEchoCanceler.create(MediaRecorder.AudioSource.MIC);
            AutomaticGainControl.create(MediaRecorder.AudioSource.MIC);
            recorder.startRecording();
            track.play();

            sock = new DatagramSocket();
            sock.connect(new InetSocketAddress(server.split(":")[0], Integer.parseInt(server.split(":")[1])));
            listener.callStatusChanged(CallStatus.CONNECTED);
            //Bind to response port
            downlink = new Thread(new Runnable() {
                @Override
                public void run() {
                    int errors = 10;
                    while (true) {
                        if(closing)
                            return;
                        try {
                            while (sock.isConnected()) {
                                if(closing)
                                    return;
                                if(secure){
                                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer_Encrypted, receiveBuffer_Encrypted.length);
                                    sock.receive(receivePacket);
                                    Log.w("VOICE", "Packet of " + receivePacket.getLength() + " was received from " + receivePacket.getAddress());
                                    receiveBuffer = decrypt(key, receiveBuffer_Encrypted);
                                    track.write(receiveBuffer, 0, receiveBuffer.length);
                                }else {
                                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                                    sock.receive(receivePacket);
                                    Log.w("VOICE", "Packet of " + receivePacket.getLength() + " was received from " + receivePacket.getAddress());
                                    track.write(receivePacket.getData(), 0, receivePacket.getLength());
                                }

                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors--;
                            if (errors <= 0) {
                                listener.callStatusChanged(CallStatus.ERROR);
                                close();
                                listener.callEnded();
                                return;
                            }
                        }
                    }
                }
            });
            downlink.start();

            int errors = 10;
            while (true) {
                if(closing)
                    return;
                try {
                    while (sock.isConnected()) {
                        if(closing)
                            return;
                        int read = recorder.read(transmittBuffer, 0, transmittBuffer.length);
                        if(secure){
                            transmittBuffer_Encrypted = encrypt(key, transmittBuffer);
                            DatagramPacket packet = new DatagramPacket(transmittBuffer_Encrypted, transmittBuffer_Encrypted.length);
                            sock.send(packet);
                        }else{
                            DatagramPacket packet = new DatagramPacket(transmittBuffer, read);
                            sock.send(packet);
                        }
                    }
                    if (!closing) //reconnect from uplink thread if connection stopped but not interrupted
                        sock.connect(new InetSocketAddress(server.split(":")[0], Integer.parseInt(server.split(":")[1])));
                } catch (Exception e) {
                    e.printStackTrace();
                    errors--;
                    if (errors <= 0) {
                        listener.callStatusChanged(CallStatus.ERROR);
                        close();
                        listener.callEnded();
                        return;
                    }
                }
            }
            //Finish uplink
        } catch (Exception e) {
            listener.callStatusChanged(CallStatus.ERROR);
            close();
            listener.callEnded();
        }

    }

    private byte[] encrypt(byte[] raw, byte[] clear) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
        return cipher.doFinal(clear);
    }

    private byte[] decrypt(byte[] raw, byte[] encrypted) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivspec = new IvParameterSpec(encrypted, 0, 16);
        cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivspec);
        return cipher.doFinal(encrypted, 16, encrypted.length-16);
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
